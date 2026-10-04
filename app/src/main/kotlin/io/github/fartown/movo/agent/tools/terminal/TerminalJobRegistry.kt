package io.github.fartown.movo.agent.tools.terminal

import io.github.fartown.movo.core.AgentLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程内的终端任务注册表，同时作为 terminal_run 与 terminal_job 的真实后端。
 *
 * 用户与 Root 命令都通过 [ProcessBuilder]（`sh -c` / `su -c`）启动，拿到 Process 句柄以便读输出、
 * 发输入、停止。wait 模式在 wait_ms 内结束返回 Completed，否则登记任务转后台。
 *
 * TODO（见报告）：
 * - 环形缓冲落盘：当前只在内存保留首尾，超限截断，不落盘。
 * - keep_alive 跨 run 存活：应走 DetachedTaskSupervisor，这里只在本注册表内保留，不真正脱离进程。
 * - Linux(proot) 环境：未接 ProotCommandBuilder，environment=linux 时按普通 shell 跑。
 * - Root 停止子进程、补退出码记录：su 的子进程可能杀不干净。
 */
internal class TerminalJobRegistry(
    private val logger: AgentLogger,
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
        val finished = runCatching {
            job.process.waitFor(spec.waitMs, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
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
        runCatching { job.process.destroy() }
        runCatching { job.process.waitFor(500, TimeUnit.MILLISECONDS) }
        if (job.process.isAlive) runCatching { job.process.destroyForcibly() }
        runCatching { job.process.waitFor(500, TimeUnit.MILLISECONDS) }
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
                runCatching { job.process.destroy() }
            }
        }
        jobs.clear()
    }

    private fun start(spec: TerminalRunSpec): Job {
        val id = "job_" + counter.incrementAndGet()
        val argv = when (spec.identity) {
            TerminalIdentity.USER -> listOf("sh", "-c", spec.command)
            TerminalIdentity.ROOT -> listOf("su", "-c", spec.command)
        }
        val builder = ProcessBuilder(argv).redirectErrorStream(false)
        spec.cwd?.let { cwd -> runCatching { builder.directory(File(cwd)) } }
        val process = runCatching { builder.start() }.getOrElse {
            logger.warn("terminal_run start failed: ${it.javaClass.simpleName}")
            throw io.github.fartown.movo.agent.tools.core.ToolFailure(
                io.github.fartown.movo.agent.tools.core.ToolErrorCode.SYSTEM_REJECTED,
                "命令无法启动",
                detail = it.javaClass.simpleName,
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
