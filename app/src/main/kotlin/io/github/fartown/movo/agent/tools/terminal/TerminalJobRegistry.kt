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
 *
 * TODO（见报告）：
 * - 环形缓冲落盘：当前只在内存保留首尾，超限截断，不落盘。
 * - keep_alive 跨 run 存活：应走 DetachedTaskSupervisor，这里只在本注册表内保留，不真正脱离进程。
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
        val background = spec.mode != TerminalMode.WAIT
        if (background) {
            return TerminalRunResult.Backgrounded(
                jobId = job.id,
                reason = if (spec.mode == TerminalMode.KEEP_ALIVE) "keep_alive" else "background",
                startedAtMillis = job.startedAtMillis,
                keepAlive = spec.mode == TerminalMode.KEEP_ALIVE,
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
                )
            }
        }
        if (!finished) {
            return TerminalRunResult.Backgrounded(
                jobId = job.id,
                reason = "wait_timeout",
                startedAtMillis = job.startedAtMillis,
                keepAlive = false,
            )
        }
        job.finish()
        jobs.remove(job.id)
        val elapsed = (job.endedAtMillis ?: System.currentTimeMillis()) - job.startedAtMillis
        return TerminalRunResult.Completed(
            exitCode = job.exitCode ?: -1,
            stdout = job.snapshot(StreamBuf.OUT).text,
            stderr = job.snapshot(StreamBuf.ERR).text,
            elapsedMs = elapsed,
            stdoutTruncated = job.snapshot(StreamBuf.OUT).truncated,
            stderrTruncated = job.snapshot(StreamBuf.ERR).truncated,
        )
    }

    // ---- TerminalJobBackend ----

    override fun list(): List<TerminalJobInfo> = jobs.values.map { it.info() }

    override fun read(jobId: String, cursor: String?, stream: TerminalStream, waitMs: Long): TerminalJobReadResult? {
        val job = jobs[jobId] ?: return null
        if (job.isRunning() && waitMs > 0) job.waitForOutput(waitMs)
        job.refreshExit()
        val outSlice = if (stream == TerminalStream.STDERR) StreamSlice.EMPTY else job.slice(StreamBuf.OUT, cursor)
        val errSlice = if (stream == TerminalStream.STDOUT) StreamSlice.EMPTY else job.slice(StreamBuf.ERR, cursor)
        val nextCursor = maxOf(outSlice.nextOffset, errSlice.nextOffset)
            .takeIf { it > (cursor?.toIntOrNull() ?: 0) }?.toString()
        return TerminalJobReadResult(job.info(), outSlice.text, errSlice.text, nextCursor)
    }

    override fun write(jobId: String, input: String, waitMs: Long): Boolean {
        val job = jobs[jobId] ?: return false
        if (!job.isRunning()) return false
        return runCatching {
            job.process.outputStream.apply {
                write((input + "\n").toByteArray(Charsets.UTF_8))
                flush()
            }
            if (waitMs > 0) job.waitForOutput(waitMs)
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
                // TODO：keep_alive 应移交 DetachedTaskSupervisor 继续存活；非 keep_alive 这里停掉。
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
            )
        }.getOrNull() ?: run {
            logger.warn("terminal_run start failed: environment=${launch.environment.wireName} identity=${launch.identity}")
            throw io.github.fartown.movo.agent.tools.core.ToolFailure(
                io.github.fartown.movo.agent.tools.core.ToolErrorCode.SYSTEM_REJECTED,
                if (launch.environment.isLinux) "Linux 环境里的命令无法启动" else "命令无法启动",
                hint = if (launch.environment.isLinux) "确认设置里的 Linux 工具环境已装好；需要 Root 的 chroot 方式要先授权 Root" else null,
            )
        }
        val job = Job(id, spec, process)
        jobs[id] = job
        job.startReaders()
        return job
    }

    // -----------------------------------------------------------------------

    private enum class StreamBuf { OUT, ERR }

    private data class StreamSlice(val text: String, val nextOffset: Int) {
        companion object { val EMPTY = StreamSlice("", 0) }
    }

    private class BoundedBuffer(private val max: Int = 256 * 1024) {
        private val sb = StringBuilder()
        var truncated = false
            private set

        @Synchronized
        fun append(text: String) {
            if (sb.length >= max) {
                truncated = true
                return
            }
            sb.append(text)
            if (sb.length > max) {
                sb.setLength(max)
                truncated = true
            }
        }

        @Synchronized
        fun length(): Int = sb.length

        @Synchronized
        fun snapshot(): String = sb.toString()

        @Synchronized
        fun slice(cursor: Int): Pair<String, Int> {
            val from = cursor.coerceIn(0, sb.length)
            return sb.substring(from) to sb.length
        }
    }

    private inner class Job(
        val id: String,
        private val spec: TerminalRunSpec,
        val process: Process,
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
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        out.append(String(buf, 0, n))
                        synchronized(lock) { lock.notifyAll() }
                    }
                }
            }
            thread("err") {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        err.append(String(buf, 0, n))
                        synchronized(lock) { lock.notifyAll() }
                    }
                }
            }
        }

        private fun thread(suffix: String, block: () -> Unit) {
            Thread({ runCatching { block() } }, "terminal-$id-$suffix").apply {
                isDaemon = true
                start()
            }
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

        fun waitForOutput(waitMs: Long) {
            val deadline = System.currentTimeMillis() + waitMs
            synchronized(lock) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining > 0 && process.isAlive) {
                    runCatching { lock.wait(remaining) }
                }
            }
        }

        fun snapshot(which: StreamBuf): BufferView {
            val buffer = if (which == StreamBuf.OUT) out else err
            return BufferView(buffer.snapshot(), buffer.truncated)
        }

        fun slice(which: StreamBuf, cursor: String?): StreamSlice {
            val buffer = if (which == StreamBuf.OUT) out else err
            val from = cursor?.toIntOrNull() ?: 0
            val (text, next) = buffer.slice(from)
            return StreamSlice(text, next)
        }

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
        )
    }

    private data class BufferView(val text: String, val truncated: Boolean)
}

/** 一次启动用的环境、身份与命令（Linux 环境按设置里选的发行版与后端决定身份）。 */
private data class LaunchPlan(
    val environment: io.github.fartown.movo.agent.terminal.TerminalEnvironment,
    val identity: String,
    val command: String,
    val rootfsPath: String?,
    val sharedMounts: List<io.github.fartown.movo.agent.terminal.SharedFolderMount>,
)

private fun launchPlan(spec: TerminalRunSpec): LaunchPlan {
    val command = spec.cwd?.let { "cd ${shellQuote(it)} && ${spec.command}" } ?: spec.command
    if (spec.environment != TerminalEnv.LINUX) {
        return LaunchPlan(
            environment = io.github.fartown.movo.agent.terminal.TerminalEnvironment.ANDROID,
            identity = if (spec.identity == TerminalIdentity.ROOT) "root" else "user",
            command = command,
            rootfsPath = null,
            sharedMounts = emptyList(),
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
    )
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private const val CANCEL_POLL_MS = 200L
