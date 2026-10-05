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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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

/**
 * 后台监听注册表（主进程单例）。每个监听 = 一条 `sh -c`（或 `su -c`）命令：
 * stdout 推送式读取 → 分帧、合批、限流 → [MonitorNotice] 交给 [sink]（由 App 层路由到所属对话）。
 * 自然退出、到期、Movo 停止、用户停止、超速五个入口统一走一次性 [terminate]。
 */
internal object MonitorRegistry {
    const val MAX_PER_CONVERSATION = 8

    sealed interface StartResult {
        data class Started(val info: MonitorInfo) : StartResult
        data class Rejected(val code: String, val message: String) : StartResult
    }

    @Volatile var sink: MonitorNoticeSink? = null
    @Volatile private var appContext: Context? = null

    private val tasks = ConcurrentHashMap<String, Task>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val timers = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "movo-monitor-timer").apply { isDaemon = true }
    }
    private val _active = MutableStateFlow<List<MonitorInfo>>(emptyList())

    /** 运行中的监听（按启动顺序），界面的「监听」入口与列表读这里。 */
    val active: StateFlow<List<MonitorInfo>> = _active.asStateFlow()

    fun list(conversationId: String?): List<MonitorInfo> =
        _active.value.filter { conversationId == null || it.conversationId == conversationId }

    fun find(taskId: String): MonitorInfo? = tasks[taskId]?.info()

    fun start(
        context: Context,
        conversationId: String,
        name: String,
        command: String,
        root: Boolean,
        requestedTimeoutMs: Long?,
    ): StartResult {
        appContext = context.applicationContext
        val trimmedName = name.trim()
        val running = list(conversationId)
        if (running.any { it.name.equals(trimmedName, ignoreCase = true) }) {
            return StartResult.Rejected("DUPLICATE_NAME", "本对话已有名为「$trimmedName」的监听，换一个名字，或先停掉原来的")
        }
        if (running.size >= MAX_PER_CONVERSATION) {
            return StartResult.Rejected("TOO_MANY", "本对话已有 ${running.size} 个监听在运行，先停掉不用的再开新的")
        }
        val maxMs = MonitorSettings.maxDurationMs(context)
        val timeoutMs = (requestedTimeoutMs ?: MonitorSettings.DEFAULT_TIMEOUT_MS).coerceIn(1_000L, maxMs)
        val id = "m" + UUID.randomUUID().toString().replace("-", "").take(8)
        val leaseId = "monitor:$id"
        val leased = AgentExecutionService.acquire(
            context, leaseId, label = R.string.monitor_execution_label, task = leaseId,
        ) { stop(id, MonitorEndReason.STOPPED_BY_USER) }
        if (!leased) {
            return StartResult.Rejected("BACKGROUND_DENIED", "系统不允许 Movo 在后台保持运行，监听没有启动")
        }
        // shell 先把自己的进程号写进 pid 文件，停止时据此连同子进程（如循环里的 sleep）一起结束。
        val pidFile = File(context.cacheDir, "monitor/$id.pid").apply { parentFile?.mkdirs(); delete() }
        val script = "echo $$ > '${pidFile.absolutePath}'; $command"
        val argv = if (root) listOf("su", "-c", script) else listOf("sh", "-c", script)
        val process = try {
            ProcessBuilder(argv).directory(context.filesDir).redirectErrorStream(false).start()
        } catch (failure: Exception) {
            AgentExecutionService.release(leaseId)
            return StartResult.Rejected("START_FAILED", "命令无法启动：${failure.javaClass.simpleName}")
        }
        val now = System.currentTimeMillis()
        val task = Task(
            id = id, conversationId = conversationId, name = trimmedName, command = command,
            root = root, startedAtMillis = now, timeoutMs = timeoutMs, leaseId = leaseId, process = process,
            stderrFile = File(context.cacheDir, "monitor/$id.stderr.log"),
            pidFile = pidFile,
        )
        tasks[id] = task
        publish()
        persistRunning()
        task.begin()
        AgentExecutionService.refresh()
        return StartResult.Started(task.info())
    }

    fun stop(taskId: String, reason: MonitorEndReason): Boolean {
        val task = tasks[taskId] ?: return false
        task.terminate(reason, exitCode = null)
        return true
    }

    fun stopConversation(conversationId: String, reason: MonitorEndReason) {
        tasks.values.filter { it.conversationId == conversationId }.forEach { it.terminate(reason, null) }
    }

    /** 常驻通知正文：「后台监听 2 个·喝水提醒、电量播报，17:00 自动结束」。没有监听时为 null。 */
    fun executionSummary(context: Context): String? {
        val running = _active.value.takeIf { it.isNotEmpty() } ?: return null
        val names = running.joinToString("、") { it.name }
        val end = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(running.maxOf { it.deadlineAtMillis }))
        return context.getString(R.string.monitor_execution_summary, running.size, names, end)
    }

    /** 记下运行中的监听；进程被系统杀掉后，下次启动时据此在对话里补「监听已中断」。 */
    private fun persistRunning() {
        val context = appContext ?: return
        val array = org.json.JSONArray()
        tasks.values.filter { !it.ended }.forEach { task ->
            array.put(org.json.JSONObject().put("id", task.id).put("conversation", task.conversationId).put("name", task.name))
        }
        context.getSharedPreferences(MonitorSettings.PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RUNNING, array.toString()).apply()
    }

    data class Interrupted(val taskId: String, val conversationId: String, val name: String)

    /** 上一个进程里没正常结束的监听（本进程里正在运行的除外），取出后清空记录。 */
    fun takeInterrupted(context: Context): List<Interrupted> {
        val prefs = context.applicationContext.getSharedPreferences(MonitorSettings.PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RUNNING, null) ?: return emptyList()
        val result = runCatching {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Interrupted(item.optString("id"), item.optString("conversation"), item.optString("name"))
                    .takeIf { it.taskId.isNotBlank() && !tasks.containsKey(it.taskId) }
            }
        }.getOrDefault(emptyList())
        appContext = context.applicationContext
        persistRunning()
        if (tasks.isEmpty()) prefs.edit().remove(KEY_RUNNING).apply()
        return result
    }

    private const val KEY_RUNNING = "running"

    private fun publish() {
        _active.value = tasks.values.filter { !it.ended }.sortedBy { it.startedAtMillis }.map { it.info() }
    }

    private fun post(notice: MonitorNotice) {
        mainHandler.post { sink?.deliver(notice) }
    }

    private class Task(
        val id: String,
        val conversationId: String,
        val name: String,
        val command: String,
        val root: Boolean,
        val startedAtMillis: Long,
        val timeoutMs: Long,
        val leaseId: String,
        val process: Process,
        val stderrFile: File,
        val pidFile: File,
    ) {
        private val lock = Any()
        private val framer = MonitorLineFramer()
        private val batcher = MonitorBatcher()
        private val limiter = MonitorRateLimiter()
        private var deadlineFuture: ScheduledFuture<*>? = null
        private var flushFuture: ScheduledFuture<*>? = null
        private var seq = 0
        private var suppressedTotal = 0
        @Volatile var ended = false

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
                        if (read < 0) break
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
            synchronized(lock) {
                if (ended) return
                val now = System.currentTimeMillis()
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
            var stopFor: MonitorRateLimiter.Decision.Stop? = null
            synchronized(lock) {
                if (ended) return
                val now = System.currentTimeMillis()
                val text = batcher.flush(now, force = true) ?: return
                when (val decision = limiter.tryDeliver(now)) {
                    is MonitorRateLimiter.Decision.Deliver -> {
                        seq++
                        post(MonitorNotice.Event(id, conversationId, name, now, seq, text, decision.suppressedBefore))
                    }
                    MonitorRateLimiter.Decision.Suppress -> suppressedTotal++
                    is MonitorRateLimiter.Decision.Stop -> { suppressedTotal = decision.suppressed; stopFor = decision }
                }
            }
            if (stopFor != null) terminate(MonitorEndReason.RATE_LIMIT, null) else publish()
        }

        fun terminate(reason: MonitorEndReason, exitCode: Int?) {
            val tail: String?
            val events: Int
            synchronized(lock) {
                if (ended) return
                ended = true
                deadlineFuture?.cancel(false)
                flushFuture?.cancel(false)
                val pending = batcher.flush(System.currentTimeMillis(), force = true)
                tail = if (reason == MonitorEndReason.EXIT) {
                    listOfNotNull(pending, framer.drain()).joinToString("\n").takeIf { it.isNotBlank() }
                } else {
                    null
                }
                events = seq
            }
            killProcess()
            tasks.remove(id)
            AgentExecutionService.release(leaseId)
            publish()
            persistRunning()
            AndroidAgentLogger.info("Monitor ended: reason=${reason.name}, events=$events")
            post(
                MonitorNotice.Ended(
                    taskId = id, conversationId = conversationId, name = name, atMillis = System.currentTimeMillis(),
                    reason = reason, exitCode = exitCode, tail = tail?.let(MonitorLimits::truncateBatch),
                    eventCount = events, timeoutMs = timeoutMs, suppressed = suppressedTotal,
                ),
            )
        }

        private fun killProcess() {
            // sh -c 的循环里常有 sleep 子进程：先按父进程号结束直接子进程，再结束 shell 本身。
            val pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull()
            if (pid != null && pid > 1) {
                val kill = "pkill -TERM -P $pid; kill -TERM $pid"
                runCatching {
                    ProcessBuilder(if (root) listOf("su", "-c", kill) else listOf("sh", "-c", kill))
                        .start().waitFor(2, TimeUnit.SECONDS)
                }
            }
            runCatching { pidFile.delete() }
            if (!process.isAlive) return
            runCatching { process.destroy() }
            runCatching { if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly() }
        }
    }

    private const val STDERR_CAP = 256L * 1024
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
