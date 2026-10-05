package io.github.fartown.movo.agent.monitor

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.runtime.AgentExecutionService
import io.github.fartown.movo.core.AndroidAgentLogger
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 监听为什么结束。决定界面是否插结束行、是否唤醒 Movo（规范 8.12）。 */
internal enum class MonitorEndReason {
    /** 命令自己结束。 */
    EXIT,
    /** 到了期限。 */
    TIMEOUT,
    /** Movo 调用 monitor_stop：停止是执行卡里的一步，不另插行、不唤醒。 */
    STOPPED_BY_AGENT,
    /** 用户在列表 / 通知里点了停止：插「已停止监听」，不唤醒。 */
    STOPPED_BY_USER,
    /** 输出持续超速被停。 */
    RATE_LIMIT,
    /** 所属对话已不存在（删除、删光所有轮次、导入备份）：没有地方插行，也不唤醒。 */
    SESSION_END,
    ;

    /** 自动结束要唤醒 Movo 说明一次。 */
    val wakesAgent: Boolean get() = this == EXIT || this == TIMEOUT || this == RATE_LIMIT
}

internal data class MonitorInfo(
    val id: String,
    val conversationId: String,
    val name: String,
    val command: String,
    val startedAtMillis: Long,
    val deadlineAtMillis: Long,
    val timeoutMs: Long,
    val eventCount: Int,
)

/** 交给对话的通知。文本已生成好，不持有进程或等待结束的回调。 */
internal sealed interface MonitorNotice {
    val taskId: String
    val conversationId: String
    val name: String
    val atMillis: Long

    data class Event(
        override val taskId: String,
        override val conversationId: String,
        override val name: String,
        override val atMillis: Long,
        val seq: Int,
        val text: String,
        val suppressedBefore: Int,
        /** 排队期间超过每个监听的积压上限、被省略的更早事件数（见 [MonitorDeliveryQueue]）。 */
        val omittedBefore: Int = 0,
    ) : MonitorNotice

    data class Ended(
        override val taskId: String,
        override val conversationId: String,
        override val name: String,
        override val atMillis: Long,
        val reason: MonitorEndReason,
        val exitCode: Int?,
        /** 退出时还没交付的末尾输出。 */
        val tail: String?,
        val eventCount: Int,
        val timeoutMs: Long,
        val suppressed: Int,
    ) : MonitorNotice
}

internal fun interface MonitorNoticeSink {
    /** 主线程调用。 */
    fun deliver(notice: MonitorNotice)
}

/** 注册表依赖的宿主能力；正式实现是 [AndroidMonitorHost]，单测换成假的。 */
internal interface MonitorHost {
    /** pid、取消标记、stderr 日志等临时文件所在目录。 */
    val workDir: File
    /** 命令的工作目录。 */
    val homeDir: File
    /** 系统里 setsid 的路径（toybox）；没有时为 null，结束进程退回按进程树逐个结束。 */
    val setsidCommand: String?
    fun maxDurationMs(): Long
    /** 前台服务租约；拿不到时不启动。[onStop] 由常驻通知「全部停止」等入口调用（任意线程）。 */
    fun acquireLease(leaseId: String, onStop: () -> Unit): Boolean
    fun releaseLease(leaseId: String)
    /** 监听增减：刷新常驻通知。 */
    fun leasesChanged()
    /** 切到主线程执行。 */
    fun post(block: () -> Unit)
    fun readRecord(): String?
    fun writeRecord(value: String?)
    /** 监听结束：收回它发过的通知上的「停止提醒」。 */
    fun onTaskEnded(taskId: String)
}

/**
 * 后台监听注册表（主进程单例）。每个监听 = 一条 `sh -c`（或 `su -c`）命令：
 * stdout 推送式读取 → 分帧、合批、限流 → [MonitorNotice] 交给 [sink]（由 App 层路由到所属对话）。
 * 自然退出、到期、Movo 停止、用户停止、超速、对话被删六个入口统一走一次性终止。
 */
internal object MonitorRegistry {
    const val MAX_PER_CONVERSATION = MonitorRegistryCore.MAX_PER_CONVERSATION

    private val core = MonitorRegistryCore()

    var sink: MonitorNoticeSink?
        get() = core.sink
        set(value) { core.sink = value }

    /** 运行中的监听（按启动顺序），界面的「监听」入口与列表读这里。 */
    val active: StateFlow<List<MonitorInfo>> get() = core.active

    fun list(conversationId: String?): List<MonitorInfo> = core.list(conversationId)

    fun find(taskId: String): MonitorInfo? = core.find(taskId)

    fun start(
        context: Context,
        conversationId: String,
        name: String,
        command: String,
        root: Boolean,
        requestedTimeoutMs: Long?,
    ): MonitorRegistryCore.StartResult {
        core.bind(AndroidMonitorHost.get(context))
        return core.start(conversationId, name, command, root, requestedTimeoutMs)
    }

    /** 只做登记与收尾，结束进程在后台线程池里进行，任何线程调用都不会卡住。 */
    fun stop(taskId: String, reason: MonitorEndReason): Boolean = core.stop(taskId, reason)

    fun stopConversation(conversationId: String, reason: MonitorEndReason) = core.stopConversation(conversationId, reason)

    /** 停掉所属对话不在 [conversationIds] 里的监听（对话被移除、导入备份后留下的监听）。 */
    fun stopOrphans(conversationIds: Set<String>): Int =
        core.stopWhere(MonitorEndReason.SESSION_END) { it.conversationId !in conversationIds }

    /** 常驻通知正文：「后台监听 2 个·喝水提醒、电量播报，17:00 自动结束」。没有监听时为 null。 */
    fun executionSummary(context: Context): String? {
        val running = core.active.value.takeIf { it.isNotEmpty() } ?: return null
        val names = running.joinToString("、") { it.name }
        val end = MonitorTime.clock(context, running.maxOf { it.deadlineAtMillis })
        return context.resources.getQuantityString(R.plurals.monitor_execution_summary, running.size, running.size, names, end)
    }

    data class Interrupted(
        val taskId: String,
        val conversationId: String,
        val name: String,
        val startedAtMillis: Long,
        /** 最后一次心跳（每分钟记一次）；旧记录没有时为 0。 */
        val aliveAtMillis: Long,
    ) {
        /** 中断大约发生的时刻：开始时刻与最后一次心跳里较晚的那个（之后进程随时可能被杀）。 */
        val interruptedAtMillis: Long get() = maxOf(startedAtMillis, aliveAtMillis)
    }

    /** 上一个进程里没正常结束的监听（本进程里正在运行的除外），取出后清空记录。 */
    fun takeInterrupted(context: Context): List<Interrupted> {
        core.bind(AndroidMonitorHost.get(context))
        return core.takeInterrupted()
    }
}

/**
 * [MonitorRegistry] 的实现（单测直接构造，换掉宿主、时钟与结束进程的 shell）。
 *
 * - 名字、数量检查与登记在同一把锁里，并发启动不会出现重名；
 * - 运行列表与本地记录都在注册表锁里计算并写出，后写的一定是新的（不会用旧列表覆盖新列表）；
 * - 终止只做登记与通知，结束进程在单独的线程池里做：不占主线程，也不占共用的计时线程；
 * - 有 setsid 时命令跑在自己的会话 / 进程组里，按进程组结束（含 `&`、nohup 起的孙进程）；
 *   没有时退回按进程树从下往上结束；命令已经自己结束（进程退出且输出已关）时不再按旧进程号结束，避免进程号复用误杀。
 */
internal class MonitorRegistryCore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val runShell: (root: Boolean, script: String) -> Unit = ::runMonitorShell,
    private val heartbeatMs: Long = HEARTBEAT_MS,
    private val lateKillCheckMs: Long = LATE_KILL_CHECK_MS,
) {
    sealed interface StartResult {
        /** [requestedTimeoutMs] 为模型请求的期限（没填为 null），[maxTimeoutMs] 为用户设置的上限：超出时按上限生效。 */
        data class Started(val info: MonitorInfo, val requestedTimeoutMs: Long?, val maxTimeoutMs: Long) : StartResult
        data class Rejected(val code: String, val message: String) : StartResult
    }

    @Volatile var sink: MonitorNoticeSink? = null
    @Volatile private var host: MonitorHost? = null

    private val lock = Any()
    private val tasks = LinkedHashMap<String, Task>()
    /** 已通过名字检查、还在启动进程的名字（规范化后）。 */
    private val reservedNames = HashSet<Pair<String, String>>()
    private val timers = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "movo-monitor-timer").apply { isDaemon = true }
    }
    private val killers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "movo-monitor-kill").apply { isDaemon = true }
    }
    private val kills = CopyOnWriteArrayList<Future<*>>()
    private var heartbeat: ScheduledFuture<*>? = null
    private val _active = MutableStateFlow<List<MonitorInfo>>(emptyList())

    val active: StateFlow<List<MonitorInfo>> = _active.asStateFlow()

    fun bind(host: MonitorHost) {
        if (this.host == null) synchronized(lock) { if (this.host == null) this.host = host }
    }

    fun list(conversationId: String?): List<MonitorInfo> =
        _active.value.filter { conversationId == null || it.conversationId == conversationId }

    fun find(taskId: String): MonitorInfo? = synchronized(lock) { tasks[taskId]?.takeUnless { it.ended }?.info() }

    fun start(
        conversationId: String,
        name: String,
        command: String,
        root: Boolean,
        requestedTimeoutMs: Long?,
    ): StartResult {
        val host = checkNotNull(host) { "MonitorRegistry is not bound" }
        val displayName = MonitorNames.sanitize(name)
        if (displayName.isEmpty()) return StartResult.Rejected("EMPTY_NAME", "监听需要一个名字（description）")
        val reservation = conversationId to MonitorNames.key(displayName)
        synchronized(lock) {
            val running = tasks.values.filter { it.conversationId == conversationId && !it.ended }
            if (running.any { MonitorNames.key(it.name) == reservation.second } || reservation in reservedNames) {
                return StartResult.Rejected("DUPLICATE_NAME", "本对话已有名为「$displayName」的监听，换一个名字，或先停掉原来的")
            }
            val starting = reservedNames.count { it.first == conversationId }
            if (running.size + starting >= MAX_PER_CONVERSATION) {
                return StartResult.Rejected("TOO_MANY", "本对话已有 ${running.size} 个监听在运行，先停掉不用的再开新的")
            }
            reservedNames += reservation
        }
        try {
            val maxMs = host.maxDurationMs()
            val timeoutMs = (requestedTimeoutMs ?: MonitorSettings.DEFAULT_TIMEOUT_MS).coerceIn(MIN_TIMEOUT_MS, maxMs)
            val id = "m" + UUID.randomUUID().toString().replace("-", "").take(8)
            val leaseId = "monitor:$id"
            val leased = host.acquireLease(leaseId) { stop(id, MonitorEndReason.STOPPED_BY_USER) }
            if (!leased) return StartResult.Rejected("BACKGROUND_DENIED", "系统不允许 Movo 在后台保持运行，监听没有启动")
            val dir = File(host.workDir, "monitor").apply { mkdirs() }
            val pidFile = File(dir, "$id.pid").apply { delete() }
            val cancelFile = File(dir, "$id.cancel").apply { delete() }
            val stderrFile = File(dir, "$id.stderr.log")
            val setsid = host.setsidCommand
            // 先把自己的进程号写进 pid 文件，再看有没有取消标记（su 等授权期间就被停掉时，授权后自己退出）。
            val script = "echo $$ > ${shellQuote(pidFile.absolutePath)}\n" +
                "if [ -e ${shellQuote(cancelFile.absolutePath)} ]; then exit 143; fi\n" +
                command
            // setsid：命令成为新会话的首进程，进程号即进程组号，停止时整组结束（-w：需要另起子进程时等它结束）。
            val argv = when {
                root && setsid != null -> listOf("su", "-c", "exec $setsid -w sh -c ${shellQuote(script)}")
                root -> listOf("su", "-c", script)
                setsid != null -> listOf(setsid, "-w", "sh", "-c", script)
                else -> listOf("sh", "-c", script)
            }
            val process = try {
                ProcessBuilder(argv).directory(host.homeDir).redirectErrorStream(false).start()
            } catch (failure: Exception) {
                host.releaseLease(leaseId)
                return StartResult.Rejected("START_FAILED", "命令无法启动：${failure.javaClass.simpleName}")
            }
            // 监听不读标准输入：立刻关掉，读 stdin 的命令拿到 EOF 而不是一直挂着。
            runCatching { process.outputStream.close() }
            val task = Task(
                id = id, conversationId = conversationId, name = displayName, command = command, root = root,
                startedAtMillis = clock(), timeoutMs = timeoutMs, leaseId = leaseId, process = process,
                groupMode = setsid != null, pidFile = pidFile, cancelFile = cancelFile, stderrFile = stderrFile,
            )
            synchronized(lock) {
                reservedNames -= reservation
                tasks[id] = task
                publishLocked()
                persistLocked()
                ensureHeartbeatLocked()
            }
            task.begin()
            host.leasesChanged()
            return StartResult.Started(task.info(), requestedTimeoutMs, maxMs)
        } finally {
            synchronized(lock) { reservedNames -= reservation }
        }
    }

    fun stop(taskId: String, reason: MonitorEndReason): Boolean {
        val task = synchronized(lock) { tasks[taskId] } ?: return false
        task.terminate(reason, exitCode = null)
        return true
    }

    fun stopConversation(conversationId: String, reason: MonitorEndReason) {
        stopWhere(reason) { it.conversationId == conversationId }
    }

    /** 返回停掉的个数。 */
    fun stopWhere(reason: MonitorEndReason, predicate: (MonitorInfo) -> Boolean): Int {
        val matched = synchronized(lock) { tasks.values.filter { !it.ended && predicate(it.info()) } }
        matched.forEach { it.terminate(reason, exitCode = null) }
        return matched.size
    }

    fun takeInterrupted(): List<MonitorRegistry.Interrupted> {
        val host = host ?: return emptyList()
        return synchronized(lock) {
            val raw = host.readRecord() ?: return emptyList()
            val result = runCatching {
                val array = org.json.JSONArray(raw)
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    MonitorRegistry.Interrupted(
                        taskId = item.optString("id"),
                        conversationId = item.optString("conversation"),
                        name = item.optString("name"),
                        startedAtMillis = item.optLong("started_at"),
                        aliveAtMillis = item.optLong("alive_at"),
                    ).takeIf { it.taskId.isNotBlank() && !tasks.containsKey(it.taskId) }
                }
            }.getOrDefault(emptyList())
            persistLocked()
            result
        }
    }

    /** 测试用：等已经开始的结束进程做完。 */
    internal fun awaitKills(timeoutMs: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        kills.forEach { future ->
            runCatching { future.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS) }
        }
    }

    fun shutdown() {
        timers.shutdownNow()
        killers.shutdown()
    }

    private fun publishLocked() {
        _active.value = tasks.values.filter { !it.ended }.sortedBy { it.startedAtMillis }.map { it.info() }
    }

    /** 记下运行中的监听与心跳时刻；进程被系统杀掉后，下次启动时据此在对话里补「监听已中断」。 */
    private fun persistLocked() {
        val host = host ?: return
        val running = tasks.values.filter { !it.ended }
        if (running.isEmpty()) {
            host.writeRecord(null)
            return
        }
        val now = clock()
        val array = org.json.JSONArray()
        running.forEach { task ->
            array.put(
                org.json.JSONObject()
                    .put("id", task.id)
                    .put("conversation", task.conversationId)
                    .put("name", task.name)
                    .put("started_at", task.startedAtMillis)
                    .put("alive_at", now),
            )
        }
        host.writeRecord(array.toString())
    }

    private fun ensureHeartbeatLocked() {
        if (heartbeat != null) return
        heartbeat = timers.scheduleWithFixedDelay({
            synchronized(lock) {
                if (tasks.values.none { !it.ended }) {
                    heartbeat?.cancel(false)
                    heartbeat = null
                } else {
                    persistLocked()
                }
            }
        }, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS)
    }

    private fun post(notice: MonitorNotice) {
        val host = host ?: return
        host.post { sink?.deliver(notice) }
    }

    private inner class Task(
        val id: String,
        val conversationId: String,
        val name: String,
        val command: String,
        val root: Boolean,
        val startedAtMillis: Long,
        val timeoutMs: Long,
        val leaseId: String,
        val process: Process,
        val groupMode: Boolean,
        val pidFile: File,
        val cancelFile: File,
        val stderrFile: File,
    ) {
        private val taskLock = Any()
        private val framer = MonitorLineFramer()
        private val batcher = MonitorBatcher()
        private val limiter = MonitorRateLimiter()
        private var deadlineFuture: ScheduledFuture<*>? = null
        private var flushFuture: ScheduledFuture<*>? = null
        @Volatile private var seq = 0
        private var suppressedTotal = 0
        @Volatile var ended = false
            private set
        /** 读到 stdout 末尾：所有持有输出管道的进程都已退出或关掉了它。 */
        @Volatile private var stdoutClosed = false

        val deadlineAtMillis get() = startedAtMillis + timeoutMs

        fun info() = MonitorInfo(id, conversationId, name, command, startedAtMillis, deadlineAtMillis, timeoutMs, seq)

        fun begin() {
            deadlineFuture = timers.schedule({ terminate(MonitorEndReason.TIMEOUT, null) }, timeoutMs, TimeUnit.MILLISECONDS)
            Thread({ readStdout() }, "movo-monitor-$id-out").apply { isDaemon = true }.start()
            Thread({ drainStderr() }, "movo-monitor-$id-err").apply { isDaemon = true }.start()
        }

        private fun readStdout() {
            try {
                // 流式 UTF-8 解码：跨块的多字节字符由 Reader 处理。
                InputStreamReader(process.inputStream, Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(4096)
                    while (!ended) {
                        val read = reader.read(buffer)
                        if (read < 0) {
                            stdoutClosed = true
                            break
                        }
                        onOutput(String(buffer, 0, read))
                    }
                }
            } catch (_: Exception) {
                // 进程被杀或流关闭：交给下面的退出处理。
            }
            if (ended) return
            val exit = runCatching { process.waitFor() }.getOrNull()
            terminate(MonitorEndReason.EXIT, exit)
        }

        private fun drainStderr() {
            runCatching {
                stderrFile.parentFile?.mkdirs()
                stderrFile.outputStream().use { out ->
                    val buffer = ByteArray(4096)
                    var written = 0L
                    val input = process.errorStream
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (written < STDERR_CAP) {
                            out.write(buffer, 0, minOf(read.toLong(), STDERR_CAP - written).toInt())
                            written += read
                        }
                    }
                }
            }
        }

        private fun onOutput(chunk: String) {
            synchronized(taskLock) {
                if (ended) return
                val now = clock()
                framer.accept(chunk).forEach { line ->
                    val opening = batcher.isEmpty()
                    val due = batcher.add(line, now)
                    if (opening) {
                        flushFuture = timers.schedule({ flush() }, (due - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    }
                }
            }
        }

        private fun flush() {
            var stop = false
            var delivered = false
            synchronized(taskLock) {
                if (ended) return
                val now = clock()
                val text = batcher.flush(now, force = true) ?: return
                when (val decision = limiter.tryDeliver(now)) {
                    is MonitorRateLimiter.Decision.Deliver -> {
                        seq++
                        delivered = true
                        post(MonitorNotice.Event(id, conversationId, name, now, seq, text, decision.suppressedBefore))
                    }
                    MonitorRateLimiter.Decision.Suppress -> suppressedTotal++
                    is MonitorRateLimiter.Decision.Stop -> {
                        suppressedTotal = decision.suppressed
                        stop = true
                    }
                }
            }
            if (stop) {
                terminate(MonitorEndReason.RATE_LIMIT, null)
            } else if (delivered) {
                synchronized(lock) { if (!ended) publishLocked() }
            }
        }

        fun terminate(reason: MonitorEndReason, exitCode: Int?) {
            val tail: String?
            val events: Int
            val suppressed: Int
            synchronized(taskLock) {
                if (ended) return
                ended = true
                deadlineFuture?.cancel(false)
                flushFuture?.cancel(false)
                val pending = batcher.flush(clock(), force = true)
                tail = if (reason == MonitorEndReason.EXIT) {
                    listOfNotNull(pending, framer.drain()).joinToString("\n").takeIf { it.isNotBlank() }
                } else {
                    null
                }
                events = seq
                suppressed = suppressedTotal
            }
            synchronized(lock) {
                tasks.remove(id)
                publishLocked()
                persistLocked()
            }
            host?.releaseLease(leaseId)
            host?.onTaskEnded(id)
            // 结束进程可能要等上一两秒（TERM → 等 → KILL）：放到单独的线程池里，调用方（主线程、计时线程）立刻返回。
            kills.removeAll { it.isDone }
            runCatching { killers.submit { killProcess() } }.onSuccess { kills += it }
            AndroidAgentLogger.info("Monitor ended: reason=${reason.name}, events=$events")
            post(
                MonitorNotice.Ended(
                    taskId = id, conversationId = conversationId, name = name, atMillis = clock(),
                    reason = reason, exitCode = exitCode, tail = tail?.let(MonitorLimits::truncateBatch),
                    eventCount = events, timeoutMs = timeoutMs, suppressed = suppressed,
                ),
            )
        }

        private fun killProcess() {
            // 先放取消标记：pid 还没写出来（su 等授权）时，脚本之后启动也会自己退出。
            runCatching { cancelFile.parentFile?.mkdirs(); cancelFile.createNewFile() }
            val pid = readPid()
            val leaderAlive = process.isAlive
            when {
                pid == null -> Unit
                // 命令已经自己结束、输出也关了：进程号可能已被复用，不再按它结束任何进程。
                !leaderAlive && stdoutClosed -> Unit
                // 进程组号被组内仍存活的进程占着，不会被复用；组已空时 kill 只会返回「没有这个进程组」。
                groupMode -> runShell(root, groupKillScript(pid))
                // 没有 setsid：沿进程树从下往上结束。只在能确认还是自己的进程时做（Java 持有的 sh 仍存活，或 root 下核对命令行）。
                root -> runShell(true, treeKillScript(pid, verifyToken = "$id.pid"))
                leaderAlive -> runShell(false, treeKillScript(pid, verifyToken = null))
            }
            if (process.isAlive) {
                runCatching { process.destroy() }
                runCatching { if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly() }
            }
            // 关掉各条流，读线程不会一直挂着；删掉临时文件。
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { stderrFile.delete() }
            if (pid != null) {
                runCatching { pidFile.delete() }
                runCatching { cancelFile.delete() }
            } else {
                // 还没拿到进程号：取消标记先留着，过一会儿再看一次（授权后才启动的脚本会写出 pid）。
                runCatching { timers.schedule({ lateKill() }, lateKillCheckMs, TimeUnit.MILLISECONDS) }
            }
        }

        private fun lateKill() {
            if (killers.isShutdown) return
            killers.execute {
                readPid()?.let { pid ->
                    runShell(root, if (groupMode) groupKillScript(pid) else treeKillScript(pid, verifyToken = "$id.pid"))
                }
                runCatching { pidFile.delete() }
                runCatching { cancelFile.delete() }
            }
        }

        private fun readPid(): Int? = runCatching { pidFile.readText().trim().toInt() }.getOrNull()?.takeIf { it > 1 }
    }

    companion object {
        const val MAX_PER_CONVERSATION = 8
        const val MIN_TIMEOUT_MS = 1_000L
        private const val STDERR_CAP = 256L * 1024
        private const val HEARTBEAT_MS = 60_000L
        private const val LATE_KILL_CHECK_MS = 30_000L

        /** 结束整个进程组：TERM，最多等 1.5 秒，还在就 KILL。 */
        fun groupKillScript(pgid: Int): String =
            "kill -TERM -- -$pgid 2>/dev/null || exit 0\n" +
                "i=0\n" +
                "while kill -0 -- -$pgid 2>/dev/null; do\n" +
                "  i=\$((i+1)); [ \$i -ge 15 ] && break\n" +
                "  sleep 0.1\n" +
                "done\n" +
                "kill -0 -- -$pgid 2>/dev/null && kill -KILL -- -$pgid 2>/dev/null\n" +
                "exit 0\n"

        /**
         * 没有进程组时按进程树结束：先收集全部后代（深的在前），一起 TERM，稍等后对还在的 KILL。
         * [verifyToken] 非空时先核对 /proc/<pid>/cmdline 里有这个标记（root 下确认进程号还是自己的脚本）。
         */
        fun treeKillScript(pid: Int, verifyToken: String?): String = buildString {
            if (verifyToken != null) {
                append("grep -q ").append(shellQuote(verifyToken)).append(" /proc/$pid/cmdline 2>/dev/null || exit 0\n")
            }
            append("L=\"\"\n")
            append("k() { for c in \$(pgrep -P \"\$1\" 2>/dev/null); do k \"\$c\"; done; L=\"\$L \$1\"; }\n")
            append("k $pid\n")
            append("kill -TERM \$L 2>/dev/null\n")
            append("sleep 0.3\n")
            append("for p in \$L; do kill -0 \$p 2>/dev/null && kill -KILL \$p 2>/dev/null; done\n")
            append("exit 0\n")
        }

        fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}

/** 运行一段结束进程用的 shell（root 时经 su），最多等 5 秒。 */
internal fun runMonitorShell(root: Boolean, script: String) {
    val process = runCatching {
        ProcessBuilder(if (root) listOf("su", "-c", script) else listOf("sh", "-c", script))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
            .start()
    }.getOrNull() ?: return
    runCatching { process.outputStream.close() }
    runCatching { if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly() }
}

/** 正式宿主：前台服务租约、主线程、SharedPreferences 记录、toybox setsid。 */
internal class AndroidMonitorHost private constructor(private val context: Context) : MonitorHost {
    private val mainHandler = Handler(Looper.getMainLooper())

    override val workDir: File get() = context.cacheDir
    override val homeDir: File get() = context.filesDir
    override val setsidCommand: String? by lazy {
        listOf("/system/bin/setsid", "/system/xbin/setsid", "/vendor/bin/setsid").firstOrNull { File(it).canExecute() }
    }

    override fun maxDurationMs(): Long = MonitorSettings.maxDurationMs(context)

    override fun acquireLease(leaseId: String, onStop: () -> Unit): Boolean =
        AgentExecutionService.acquire(context, leaseId, label = R.string.monitor_execution_label, task = leaseId, onStop = onStop)

    override fun releaseLease(leaseId: String) = AgentExecutionService.release(leaseId)

    override fun leasesChanged() = AgentExecutionService.refresh()

    override fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private val prefs get() = context.getSharedPreferences(MonitorSettings.PREFS, Context.MODE_PRIVATE)

    override fun readRecord(): String? = prefs.getString(KEY_RUNNING, null)

    override fun writeRecord(value: String?) {
        prefs.edit().apply { if (value == null) remove(KEY_RUNNING) else putString(KEY_RUNNING, value) }.apply()
    }

    override fun onTaskEnded(taskId: String) = MonitorNotifications.retire(context, taskId)

    companion object {
        private const val KEY_RUNNING = "running"
        @Volatile private var instance: AndroidMonitorHost? = null

        fun get(context: Context): AndroidMonitorHost =
            instance ?: synchronized(this) {
                instance ?: AndroidMonitorHost(context.applicationContext).also { instance = it }
            }
    }
}

/** 监听名字：一行、去掉控制字符、按字素截断（不截坏 emoji）；重名按规范化后的写法判断。 */
internal object MonitorNames {
    const val MAX_GRAPHEMES = 20

    fun sanitize(raw: String): String {
        // 换行、制表等控制字符和各种空白（含全角空格）都收成一个半角空格，首尾去掉。
        val oneLine = StringBuilder(raw.length)
        var pendingSpace = false
        raw.forEach { c ->
            if (c.isWhitespace() || Character.isISOControl(c) || c == ' ' || c == ' ') {
                pendingSpace = oneLine.isNotEmpty()
            } else {
                if (pendingSpace) oneLine.append(' ')
                pendingSpace = false
                oneLine.append(c)
            }
        }
        return takeGraphemes(oneLine.toString(), MAX_GRAPHEMES).trimEnd()
    }

    /** 重名判断用：全角半角统一（NFKC）、去掉所有空白、不分大小写。 */
    fun key(name: String): String =
        java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKC)
            .filterNot { it.isWhitespace() }
            .lowercase(java.util.Locale.ROOT)

    /** 取前 [max] 个字素：组合符号、变体选择符、肤色、ZWJ 连接的 emoji、国旗对都不拆开。 */
    fun takeGraphemes(text: String, max: Int): String {
        var index = 0
        var count = 0
        while (index < text.length) {
            if (count == max) return text.substring(0, index)
            index = graphemeEnd(text, index)
            count++
        }
        return text
    }

    private fun graphemeEnd(text: String, start: Int): Int {
        val first = text.codePointAt(start)
        var index = start + Character.charCount(first)
        if (isRegionalIndicator(first) && index < text.length && isRegionalIndicator(text.codePointAt(index))) {
            index += Character.charCount(text.codePointAt(index))
        }
        while (index < text.length) {
            val cp = text.codePointAt(index)
            when {
                cp == ZWJ -> {
                    index += Character.charCount(cp)
                    if (index < text.length) index += Character.charCount(text.codePointAt(index))
                }
                isExtend(cp) -> index += Character.charCount(cp)
                else -> return index
            }
        }
        return index
    }

    private fun isExtend(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F || cp in 0xE0100..0xE01EF
    }

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    private const val ZWJ = 0x200D
}

/** 设置 → 工具 →「后台监听」：最长监听时长（规范 8.12）。修改只影响之后新启动的监听。 */
internal object MonitorSettings {
    const val DEFAULT_TIMEOUT_MS = 30 * 60_000L
    const val DEFAULT_MAX_MS = 2 * 60 * 60_000L
    val MAX_CHOICES_MS = listOf(30 * 60_000L, 60 * 60_000L, 2 * 60 * 60_000L, 4 * 60 * 60_000L, 8 * 60 * 60_000L)
    const val PREFS = "movo_monitor"
    private const val KEY_MAX = "max_duration_ms"

    fun maxDurationMs(context: Context): Long =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_MAX, DEFAULT_MAX_MS)
            .takeIf { it in MAX_CHOICES_MS } ?: DEFAULT_MAX_MS

    fun setMaxDurationMs(context: Context, value: Long) {
        require(value in MAX_CHOICES_MS)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_MAX, value).apply()
    }
}
