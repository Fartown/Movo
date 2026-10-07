package io.github.fartown.movo.agent.tools.terminal

import io.github.fartown.movo.agent.terminal.ShellProcessSupervisor
import io.github.fartown.movo.agent.terminal.isLinux
import io.github.fartown.movo.core.AgentLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程内的终端任务注册表，同时作为 terminal_run 与 terminal_job 的真实后端。
 *
 * 进程由 [ShellProcessSupervisor] 启动（与终端页、重构前的 Agent 终端同一套）：Android 环境按身份 `sh` / `su`，
 * Linux 环境进入用户在设置里选的发行版（免 Root 走 PRoot，Root 走 chroot，共享文件夹挂到 /workspace/mounts）。
 * 停止与取消按进程树结束。wait 模式在 wait_ms 内结束返回 Completed，否则登记任务转后台；
 * 前台等待期间每 200ms 检查一次运行是否已取消，取消就结束进程树，不等命令自己跑完（真机：sleep 20 时点停止要等 21 秒）。
 * tty=true 时进程跑在伪终端里（与终端页同一套 PTY 启动器），stdout/stderr 合成一路，直接转后台、用 terminal_job write 发输入。
 * 没传 cwd 时命令在工作区里跑（见 [terminalWorkspace]），与 file_* 的相对路径同一个目录。
 * terminal_job read / write 的 wait_ms 是最多等多久：有新输出或命令结束就返回，都没有就等满（见 [TerminalWake]）。
 *
 * 输出只在内存里保留开头和最近的一段（见 [BoundedBuffer]），不落盘。
 * keep_alive 不跨任务：本次任务结束时不停止进程，但注册表随任务一起释放，之后的任务看不到、也停不了它
 * （跨任务守护应走 DetachedTaskSupervisor，没有实现）。
 */
internal class TerminalJobRegistry(
    private val logger: AgentLogger,
    private val supervisor: ShellProcessSupervisor = ShellProcessSupervisor(),
) : TerminalRunBackend, TerminalJobBackend, AutoCloseable {

    private val jobs = ConcurrentHashMap<String, Job>()
    private val counter = AtomicInteger(0)

    // ---- TerminalRunBackend ----

    override fun run(spec: TerminalRunSpec): TerminalRunResult {
        val job = start(spec)
        // 交互程序在等输入，前台等到 wait_ms 也不会结束：tty 直接转后台，交还 job_id。
        val background = spec.mode != TerminalMode.WAIT || spec.tty
        if (background) {
            return TerminalRunResult.Backgrounded(
                jobId = job.id,
                reason = when {
                    spec.mode == TerminalMode.KEEP_ALIVE -> "keep_alive"
                    spec.mode == TerminalMode.BACKGROUND -> "background"
                    else -> "tty"
                },
                startedAtMillis = job.startedAtMillis,
                keepAlive = spec.mode == TerminalMode.KEEP_ALIVE,
                cwd = job.cwd,
            )
        }
        val deadline = System.currentTimeMillis() + spec.waitMs
        var finished = false
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            finished = runCatching {
                job.process.waitFor(minOf(remaining, CANCEL_POLL_MS), TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (finished) break
            if (spec.cancelled()) {
                // 运行被取消：结束整棵进程树，不再等命令自己跑完。调用方随后按取消处理。
                terminate(job)
                jobs.remove(job.id)
                return TerminalRunResult.Completed(
                    exitCode = job.exitCode ?: -1,
                    stdout = job.snapshot(StreamBuf.OUT).text,
                    stderr = job.snapshot(StreamBuf.ERR).text,
                    elapsedMs = System.currentTimeMillis() - job.startedAtMillis,
                    stdoutTruncated = job.snapshot(StreamBuf.OUT).truncated,
                    stderrTruncated = job.snapshot(StreamBuf.ERR).truncated,
                    cwd = job.cwd,
                )
            }
        }
        if (!finished) {
            return TerminalRunResult.Backgrounded(
                jobId = job.id,
                reason = "wait_timeout",
                startedAtMillis = job.startedAtMillis,
                keepAlive = false,
                cwd = job.cwd,
            )
        }
        job.finish()
        job.drainOutput()
        jobs.remove(job.id)
        val elapsed = (job.endedAtMillis ?: System.currentTimeMillis()) - job.startedAtMillis
        return TerminalRunResult.Completed(
            exitCode = job.exitCode ?: -1,
            stdout = job.snapshot(StreamBuf.OUT).text,
            stderr = job.snapshot(StreamBuf.ERR).text,
            elapsedMs = elapsed,
            stdoutTruncated = job.snapshot(StreamBuf.OUT).truncated,
            stderrTruncated = job.snapshot(StreamBuf.ERR).truncated,
            cwd = job.cwd,
        )
    }

    // ---- TerminalJobBackend ----

    override fun list(): List<TerminalJobInfo> = jobs.values.map { it.info() }

    /**
     * 不带 cursor 读尾部（每路最近的一段）；带 cursor 从游标处续读。两路各有偏移，游标写成「stdout偏移:stderr偏移」，
     * 每次都返回 next_cursor（没有新输出时也给，模型拿它等下一段）。一次最多给 [READ_CHARS] 字，两路都读时各占一半。
     *
     * [waitMs] 是最多等多久：有新输出就尽快返回，命令结束也返回，都没有就等满。带 cursor 时「新输出」是 cursor 之后的输出
     * （之后已经有了就不等）；不带 cursor 时是这次调用之后才产生的输出。
     * 以前读线程一有动静就返回（不认 cursor，命令悄悄结束了也不醒、要等满），结果里也不说为什么返回：
     * 模型要等 8–20 秒，1–700ms 就带着已有的输出回来了，以为 wait_ms 没生效，一个每秒一行的命令读了 10 次（真机 t3c-bg）。
     * 现在结果里写明等了多久、为什么返回（见 TerminalJobTool）。
     */
    override fun read(
        jobId: String,
        cursor: String?,
        stream: TerminalStream,
        waitMs: Long,
        cancelled: () -> Boolean,
    ): TerminalJobReadResult? {
        val job = jobs[jobId] ?: return null
        val from = cursor?.let { JobCursor.parse(it) }
        val startedAt = System.currentTimeMillis()
        val wake = if (job.isRunning() && waitMs > 0) {
            // 等哪一路的新输出、从哪儿算起：带 cursor 从游标算，不带从现在算。
            val outFrom = if (stream == TerminalStream.STDERR) null else from?.out ?: job.total(StreamBuf.OUT)
            val errFrom = if (stream == TerminalStream.STDOUT) null else from?.err ?: job.total(StreamBuf.ERR)
            // read 攒一段再回：一行一行出的命令不至于每行回一次（真机：每秒一行的命令被追着读了 12 次）。
            job.awaitOutput(outFrom, errFrom, waitMs, cancelled, enoughChars = READ_ENOUGH_CHARS)
        } else {
            TerminalWake.NONE
        }
        val waited = if (wake == TerminalWake.NONE) 0L else System.currentTimeMillis() - startedAt
        job.refreshExit()
        job.drainOutput()
        val budget = if (stream == TerminalStream.BOTH) READ_CHARS / 2 else READ_CHARS
        val out = if (stream == TerminalStream.STDERR) null else job.read(StreamBuf.OUT, from?.out, budget)
        val err = if (stream == TerminalStream.STDOUT) null else job.read(StreamBuf.ERR, from?.err, budget)
        // 这次没读的那一路保留原游标（没有就从 0 开始），之后换成两路一起读不会漏。
        val next = JobCursor(out?.next ?: from?.out ?: 0L, err?.next ?: from?.err ?: 0L)
        return TerminalJobReadResult(
            info = job.info(),
            stdout = out?.text.orEmpty(),
            stderr = err?.text.orEmpty(),
            nextCursor = next.encode(),
            tail = from == null,
            stdoutSkipped = out?.skipped ?: 0L,
            stderrSkipped = err?.skipped ?: 0L,
            hasMore = (out != null && out.next < job.total(StreamBuf.OUT)) ||
                (err != null && err.next < job.total(StreamBuf.ERR)),
            wake = wake,
            waitedMs = waited,
        )
    }

    /** 写入后最多等 [waitMs]：程序对这次输入有了回应（新输出）、命令结束或超时就返回。 */
    override fun write(jobId: String, input: String, waitMs: Long, cancelled: () -> Boolean): Boolean {
        val job = jobs[jobId] ?: return false
        if (!job.isRunning()) return false
        return runCatching {
            val outFrom = job.total(StreamBuf.OUT)
            val errFrom = job.total(StreamBuf.ERR)
            job.process.outputStream.apply {
                write((input + "\n").toByteArray(Charsets.UTF_8))
                flush()
            }
            if (waitMs > 0) job.awaitOutput(outFrom, errFrom, waitMs, cancelled)
            true
        }.getOrDefault(false)
    }

    override fun stop(jobId: String): TerminalStopOutcome {
        val job = jobs[jobId] ?: return TerminalStopOutcome.NOT_FOUND
        if (!job.isRunning()) {
            jobs.remove(jobId)
            return TerminalStopOutcome.STOPPED
        }
        terminate(job)
        return if (!job.process.isAlive) {
            job.finish()
            jobs.remove(jobId)
            TerminalStopOutcome.STOPPED
        } else {
            // TODO：su 的子进程可能杀不干净，这里保守报未确认。
            TerminalStopOutcome.STILL_RUNNING
        }
    }

    override fun close() {
        jobs.values.forEach { job ->
            if (!job.keepAlive) {
                // keep_alive 不停（进程留着，但注册表一清就没人管得到它；跨任务守护没有实现）；其余的这里停掉。
                terminate(job)
            }
        }
        jobs.clear()
    }

    /** 按进程树结束（su、PRoot、chroot 下的子进程一起），再兜底 destroy。 */
    private fun terminate(job: Job) {
        runCatching { supervisor.terminateProcessTree(job.process) }
        if (job.process.isAlive) {
            runCatching { job.process.destroy() }
            runCatching { job.process.waitFor(500, TimeUnit.MILLISECONDS) }
            if (job.process.isAlive) runCatching { job.process.destroyForcibly() }
            runCatching { job.process.waitFor(500, TimeUnit.MILLISECONDS) }
        }
        if (!job.process.isAlive) job.finish()
    }

    private fun start(spec: TerminalRunSpec): Job {
        val id = "job_" + counter.incrementAndGet()
        val launch = launchPlan(spec)
        val process = runCatching {
            supervisor.startShellProcess(
                identity = launch.identity,
                command = launch.command,
                mergeStderr = false,
                environment = launch.environment,
                linuxRootfsPath = launch.rootfsPath,
                linuxSharedMounts = launch.sharedMounts,
                pty = spec.tty,
            )
        }.getOrNull() ?: run {
            logger.warn(
                "terminal_run start failed: environment=${launch.environment.wireName} identity=${launch.identity} tty=${spec.tty}",
            )
            throw io.github.fartown.movo.agent.tools.core.ToolFailure(
                io.github.fartown.movo.agent.tools.core.ToolErrorCode.SYSTEM_REJECTED,
                when {
                    launch.environment.isLinux -> "Linux 环境里的命令无法启动"
                    spec.tty -> "伪终端（tty）里的命令无法启动"
                    else -> "命令无法启动"
                },
                hint = when {
                    launch.environment.isLinux -> "确认设置里的 Linux 工具环境已装好；需要 Root 的 chroot 方式要先授权 Root"
                    // Root 身份的伪终端靠 BusyBox script；没有时只能去掉 tty 或改用 user 身份。
                    spec.tty -> "去掉 tty 再试，或改用 identity=user"
                    else -> null
                },
            )
        }
        val job = Job(id, spec, process, launch.cwd)
        jobs[id] = job
        job.startReaders()
        return job
    }

    // -----------------------------------------------------------------------

    private enum class StreamBuf { OUT, ERR }

    /** 一次读出的一段：[next] 是读到哪儿（总偏移），[skipped] 是这段之前没给出的字数。 */
    private data class StreamSlice(val text: String, val next: Long, val skipped: Long)

    /**
     * 有界输出缓冲：保留开头 [headMax] 字和最近的 [tailMax] 字，中间的丢弃，长时间运行的任务也读得到最新输出。
     * 偏移按「已产生的总字数」计；续读游标落在已丢弃的那段时，从仍保留的最早位置接着读，并报告跳过了多少字。
     */
    private class BoundedBuffer(
        private val headMax: Int = 64 * 1024,
        private val tailMax: Int = 192 * 1024,
    ) {
        private val head = StringBuilder()
        private val tail = StringBuilder()
        /** 已产生的总字数（含已丢弃的）。 */
        private var total = 0L
        /** [tail] 第一个字在全部输出里的偏移。 */
        private var tailStart = 0L

        /** 中间丢弃了多少字。 */
        private val dropped: Long get() = if (tail.isEmpty()) 0L else tailStart - head.length

        @get:Synchronized
        val truncated: Boolean get() = dropped > 0

        @Synchronized
        fun total(): Long = total

        @Synchronized
        fun append(text: String) {
            var from = 0
            if (tail.isEmpty() && total == head.length.toLong() && head.length < headMax) {
                var take = minOf(headMax - head.length, text.length)
                if (take in 1 until text.length && text[take - 1].isHighSurrogate()) take--
                head.append(text, 0, take)
                total += take
                from = take
                if (from == text.length) return
            }
            if (tail.isEmpty()) tailStart = total
            tail.append(text, from, text.length)
            total += text.length - from
            // 攒到多出四分之一再丢最旧的，避免每次追加都搬动整段。
            if (tail.length > tailMax + tailMax / 4) {
                var drop = tail.length - tailMax
                if (drop < tail.length && tail[drop].isLowSurrogate()) drop++
                tail.delete(0, drop)
                tailStart += drop
            }
        }

        /** 全部保留的输出；中间丢弃过时在接缝处注明省略了多少字。 */
        @Synchronized
        fun snapshot(): String = if (dropped > 0) {
            "$head\n…(输出过长，缓冲区只保留开头和最近的部分，中间省略 $dropped 字符)…\n$tail"
        } else {
            head.toString() + tail
        }

        /**
         * 从总偏移 [from] 起最多读 [max] 字。开头那段读到头、后面紧接着就是保留的最近部分时接着读；
         * 中间丢过的话先停在开头那段末尾，下一次再从保留的最近部分读，并报告跳过的字数。
         */
        @Synchronized
        fun slice(from: Long, max: Int): StreamSlice {
            var pos = from.coerceIn(0L, total)
            var skipped = 0L
            val out = StringBuilder()
            if (pos < head.length) {
                val end = minOf(head.length.toLong(), pos + max).toInt()
                out.append(head, pos.toInt(), end)
                pos = end.toLong()
                if (dropped > 0 || out.length >= max) return StreamSlice(out.toString(), pos, skipped)
            }
            if (pos < total && tail.isNotEmpty()) {
                if (pos < tailStart) {
                    skipped = tailStart - pos
                    pos = tailStart
                }
                val start = (pos - tailStart).toInt()
                val end = minOf(tail.length, start + (max - out.length))
                out.append(tail, start, end)
                pos = tailStart + end
            }
            return StreamSlice(out.toString(), pos, skipped)
        }

        /** 最近的 [max] 字；[StreamSlice.skipped] 是它之前还有多少字没给。 */
        @Synchronized
        fun tail(max: Int): StreamSlice {
            val start = maxOf(0L, total - max)
            val slice = slice(start, max)
            return slice.copy(skipped = start)
        }
    }

    private inner class Job(
        val id: String,
        private val spec: TerminalRunSpec,
        val process: Process,
        /** 命令实际的工作目录。 */
        val cwd: String,
    ) {
        val startedAtMillis: Long = System.currentTimeMillis()
        val keepAlive: Boolean = spec.mode == TerminalMode.KEEP_ALIVE
        var exitCode: Int? = null
            private set
        var endedAtMillis: Long? = null
            private set

        private val out = BoundedBuffer()
        private val err = BoundedBuffer()
        private val lock = Object()

        fun startReaders() {
            thread("out") {
                try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = reader.read(buf)
                            if (n < 0) break
                            out.append(String(buf, 0, n))
                            synchronized(lock) { lock.notifyAll() }
                        }
                    }
                } finally {
                    // 读到头（命令结束、管道关闭）也叫醒等待的人，不用等到超时才发现命令已经结束。
                    synchronized(lock) { lock.notifyAll() }
                }
            }
            thread("err") {
                try {
                    process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = reader.read(buf)
                            if (n < 0) break
                            err.append(String(buf, 0, n))
                            synchronized(lock) { lock.notifyAll() }
                        }
                    }
                } finally {
                    synchronized(lock) { lock.notifyAll() }
                }
            }
        }

        private val readers = mutableListOf<Thread>()

        private fun thread(suffix: String, block: () -> Unit) {
            readers += Thread({ runCatching { block() } }, "terminal-$id-$suffix").apply {
                isDaemon = true
                start()
            }
        }

        /**
         * 进程退出后管道里可能还有没读完的输出：先等读线程收尾（最多 [READER_DRAIN_MS]），
         * 读到的才是完整结尾。后台留下的子进程还占着管道时等不完，按时间放弃。
         */
        fun drainOutput() {
            if (process.isAlive) return
            val deadline = System.currentTimeMillis() + READER_DRAIN_MS
            readers.forEach { reader -> runCatching { reader.join((deadline - System.currentTimeMillis()).coerceAtLeast(1)) } }
        }

        fun isRunning(): Boolean = process.isAlive

        fun refreshExit() {
            if (!process.isAlive && exitCode == null) finish()
        }

        fun finish() {
            if (exitCode == null) {
                exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
                endedAtMillis = System.currentTimeMillis()
            }
        }

        /**
         * 最多等 [waitMs]，直到 stdout 越过 [outFrom]、stderr 越过 [errFrom] 的新输出合计达到 [enoughChars]
         * （为空的那一路不看）、命令结束或超时。已经够了就不等。[enoughChars] 为 1 时有新输出就返回（write 用）；
         * read 攒一段再回，返回 [TerminalWake.ENOUGH_OUTPUT]。每 [CANCEL_POLL_MS] 醒一次检查 [cancelled] 和进程是否已退出
         * （读线程收尾时也会叫醒）。
         */
        fun awaitOutput(
            outFrom: Long?,
            errFrom: Long?,
            waitMs: Long,
            cancelled: () -> Boolean,
            enoughChars: Long = 1,
        ): TerminalWake {
            val deadline = System.currentTimeMillis() + waitMs
            synchronized(lock) {
                while (true) {
                    val unread = (outFrom?.let { out.total() - it } ?: 0) + (errFrom?.let { err.total() - it } ?: 0)
                    if (unread >= enoughChars) {
                        return if (enoughChars > 1) TerminalWake.ENOUGH_OUTPUT else TerminalWake.NEW_OUTPUT
                    }
                    if (!process.isAlive) return TerminalWake.EXITED
                    if (cancelled()) return TerminalWake.CANCELLED
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) return TerminalWake.TIMEOUT
                    try {
                        lock.wait(minOf(remaining, CANCEL_POLL_MS))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return TerminalWake.CANCELLED
                    }
                }
            }
        }

        fun snapshot(which: StreamBuf): BufferView {
            val buffer = if (which == StreamBuf.OUT) out else err
            return BufferView(buffer.snapshot(), buffer.truncated)
        }

        /** 带偏移续读；偏移为空时读尾部。 */
        fun read(which: StreamBuf, from: Long?, max: Int): StreamSlice {
            val buffer = if (which == StreamBuf.OUT) out else err
            return if (from == null) buffer.tail(max) else buffer.slice(from, max)
        }

        fun total(which: StreamBuf): Long = (if (which == StreamBuf.OUT) out else err).total()

        fun info(): TerminalJobInfo = TerminalJobInfo(
            jobId = id,
            command = spec.command,
            description = spec.description,
            environment = spec.environment,
            identity = spec.identity,
            running = process.isAlive,
            keepAlive = keepAlive,
            exitCode = exitCode,
            startedAtMillis = startedAtMillis,
            endedAtMillis = endedAtMillis,
            // 伪终端里 stderr 也写到终端上，与 stdout 是同一路。
            streamsMerged = spec.tty,
        )
    }

    private data class BufferView(val text: String, val truncated: Boolean)
}

/** 一次启动用的环境、身份、工作目录与命令（Linux 环境按设置里选的发行版与后端决定身份）。 */
private data class LaunchPlan(
    val environment: io.github.fartown.movo.agent.terminal.TerminalEnvironment,
    val identity: String,
    val command: String,
    val rootfsPath: String?,
    val sharedMounts: List<io.github.fartown.movo.agent.terminal.SharedFolderMount>,
    val cwd: String,
)

/**
 * 工作区：没传 cwd 时命令在这里跑。以前不传 cwd 就停在 App 进程的当前目录 `/`，普通身份读不了
 * （真机：「列出当前目录」得到 `/` 和 Permission denied，试了 3 次才找到工作区），而 HOME 和 file_* 的相对路径都在工作区。
 * - Android 普通身份：App 的终端工作区，与 file_* 的相对路径、`~` 以及 HOME 是同一个目录；
 * - Android Root 身份：Root 工作区 /data/local/tmp/movo（与终端页、重构前一致，Root 建的文件不落进 App 私有目录）；
 * - Linux：/workspace（免 Root 的 PRoot 挂的就是 App 工作区，chroot 挂的是 Root 工作区）。
 */
internal fun terminalWorkspace(environment: TerminalEnv, identity: TerminalIdentity): String =
    if (environment == TerminalEnv.LINUX) {
        LINUX_WORKSPACE
    } else {
        io.github.fartown.movo.agent.terminal.TerminalRuntime.workspace(if (identity == TerminalIdentity.ROOT) "root" else "user")
    }

/** 解析 cwd：没传就在 [workspace]；`~` 和相对路径也按工作区算（Linux 里 `~` 是 /root）；绝对路径原样。 */
internal fun resolveTerminalCwd(cwd: String?, environment: TerminalEnv, workspace: String): String {
    val raw = cwd?.trim().orEmpty()
    val home = if (environment == TerminalEnv.LINUX) LINUX_HOME else workspace
    return when {
        raw.isEmpty() -> workspace
        raw == "~" -> home
        raw.startsWith("~/") -> "$home/${raw.removePrefix("~/")}"
        raw.startsWith("/") -> raw
        else -> "${workspace.trimEnd('/')}/$raw"
    }
}

/** 先进工作目录再跑命令；Android 的工作区可能还没建（新装、清过数据），先建好。Linux 的 /workspace 由挂载提供。 */
internal fun commandInDirectory(command: String, cwd: String, environment: TerminalEnv, workspace: String): String {
    val setup = if (environment == TerminalEnv.ANDROID && cwd == workspace) "mkdir -p ${shellQuote(cwd)} && " else ""
    return "${setup}cd ${shellQuote(cwd)} && $command"
}

private fun launchPlan(spec: TerminalRunSpec): LaunchPlan {
    val workspace = terminalWorkspace(spec.environment, spec.identity)
    val cwd = resolveTerminalCwd(spec.cwd, spec.environment, workspace)
    val command = commandInDirectory(spec.command, cwd, spec.environment, workspace)
    if (spec.environment != TerminalEnv.LINUX) {
        return LaunchPlan(
            environment = io.github.fartown.movo.agent.terminal.TerminalEnvironment.ANDROID,
            identity = if (spec.identity == TerminalIdentity.ROOT) "root" else "user",
            command = command,
            rootfsPath = null,
            sharedMounts = emptyList(),
            cwd = cwd,
        )
    }
    val context = io.github.fartown.movo.agent.runtime.AgentAppContext.resolve()
        ?: throw io.github.fartown.movo.agent.tools.core.ToolFailure(
            io.github.fartown.movo.agent.tools.core.ToolErrorCode.UNSUPPORTED, "Linux 环境尚未就绪", detail = "linux_not_ready",
        )
    val distribution = io.github.fartown.movo.data.repository.LinuxEnvironmentSettingsRepository.current(context)
    val environment = when (distribution) {
        io.github.fartown.movo.agent.terminal.LinuxDistribution.ALPINE -> io.github.fartown.movo.agent.terminal.TerminalEnvironment.ALPINE
        io.github.fartown.movo.agent.terminal.LinuxDistribution.DEBIAN -> io.github.fartown.movo.agent.terminal.TerminalEnvironment.DEBIAN
    }
    val rootfs = io.github.fartown.movo.agent.terminal.LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
    return LaunchPlan(
        environment = environment,
        // 免 Root（PRoot）用普通身份，chroot 用 Root：由用户在设置里选的后端决定，不看模型传的 identity。
        identity = io.github.fartown.movo.agent.terminal.TerminalRuntime.defaultIdentity(environment, rootfs),
        command = command,
        rootfsPath = rootfs,
        sharedMounts = runCatching { io.github.fartown.movo.agent.terminal.SharedFolderMounts.current() }.getOrDefault(emptyList()),
        cwd = cwd,
    )
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private const val CANCEL_POLL_MS = 200L

/** read 攒到这么多字的新输出就提前返回，不必等满 wait_ms。 */
private const val READ_ENOUGH_CHARS = 4_000L

/** Linux 环境的工作区与 HOME（与终端页一致）。 */
private const val LINUX_WORKSPACE = "/workspace"
private const val LINUX_HOME = "/root"

/** 进程退出后等读线程把管道读完的上限。 */
private const val READER_DRAIN_MS = 500L

/** terminal_job read 一次最多给的字数（两路都读时各一半），加上头部仍在给模型的 16000 字上限之内。 */
internal const val READ_CHARS = 14_000

/** terminal_job read 的游标：stdout、stderr 各自的总偏移，写成「out:err」；只有一个数时两路同用（旧格式）。 */
internal data class JobCursor(val out: Long, val err: Long) {
    fun encode(): String = "$out:$err"

    companion object {
        fun parse(raw: String): JobCursor {
            val parts = raw.trim().split(':')
            val numbers = parts.map { it.trim().toLongOrNull()?.takeIf { n -> n >= 0 } }
            if (numbers.isEmpty() || numbers.size > 2 || numbers.any { it == null }) {
                throw io.github.fartown.movo.agent.tools.core.ToolFailure(
                    io.github.fartown.movo.agent.tools.core.ToolErrorCode.INVALID_ARGUMENTS,
                    "cursor 格式不对：$raw",
                    hint = "用上一次 read 返回的 next_cursor；不带 cursor 读最新的尾部，0:0 从头读",
                )
            }
            return JobCursor(numbers.first()!!, numbers.last()!!)
        }
    }
}
