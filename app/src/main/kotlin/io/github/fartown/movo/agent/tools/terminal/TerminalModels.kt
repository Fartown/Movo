package io.github.fartown.movo.agent.tools.terminal

/** 运行环境：android=直接在 Android 上跑；linux=proot/chroot 的 Linux 工具链。 */
internal enum class TerminalEnv { ANDROID, LINUX }

/** 身份：user=App 进程；root=su。 */
internal enum class TerminalIdentity { USER, ROOT }

/** 运行方式：wait=前台等 wait_ms；background=直接转后台；keep_alive=后台且本次任务结束后仍存活。 */
internal enum class TerminalMode { WAIT, BACKGROUND, KEEP_ALIVE }

/** 读取的输出流。 */
internal enum class TerminalStream { STDOUT, STDERR, BOTH }

// ---------------------------------------------------------------------------
// terminal_run 后端
// ---------------------------------------------------------------------------

internal data class TerminalRunSpec(
    val command: String,
    val description: String?,
    val environment: TerminalEnv,
    val identity: TerminalIdentity,
    val cwd: String?,
    val waitMs: Long,
    val tty: Boolean,
    val mode: TerminalMode,
    /** 本次运行是否已被取消：前台等待期间按它检查，取消就立刻结束命令（不等 wait_ms 跑完）。 */
    val cancelled: () -> Boolean = { false },
)

internal sealed interface TerminalRunResult {
    /** 在 wait_ms 内结束：以退出码为结果，非 0 仍算完成（不是错误）。 */
    data class Completed(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val elapsedMs: Long,
        val stdoutTruncated: Boolean,
        val stderrTruncated: Boolean,
    ) : TerminalRunResult

    /** 到 wait_ms 未结束，或 background/keep_alive：转后台。 */
    data class Backgrounded(
        val jobId: String,
        val reason: String,
        val startedAtMillis: Long,
        val keepAlive: Boolean,
    ) : TerminalRunResult
}

internal interface TerminalRunBackend {
    fun run(spec: TerminalRunSpec): TerminalRunResult
}

// ---------------------------------------------------------------------------
// terminal_job 后端
// ---------------------------------------------------------------------------

internal data class TerminalJobInfo(
    val jobId: String,
    val command: String,
    val description: String?,
    val environment: TerminalEnv,
    val identity: TerminalIdentity,
    val running: Boolean,
    val keepAlive: Boolean,
    val exitCode: Int?,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    /** keep_alive 合并日志时标注。 */
    val streamsMerged: Boolean = false,
)

internal data class TerminalJobReadResult(
    val info: TerminalJobInfo,
    val stdout: String,
    val stderr: String,
    val nextCursor: String?,
)

/** stop 的判定结果。 */
internal enum class TerminalStopOutcome {
    /** 已确认停止。 */
    STOPPED,
    /** 任务不存在（非 keep_alive 已随任务结束清理）。 */
    NOT_FOUND,
    /** 要求 Root 才能停（root 守护任务）。 */
    ROOT_REQUIRED,
    /** 发了停止但未能确认已停。 */
    STILL_RUNNING,
}

internal interface TerminalJobBackend {
    fun list(): List<TerminalJobInfo>

    /** 读取输出；任务不存在返回 null。 */
    fun read(jobId: String, cursor: String?, stream: TerminalStream, waitMs: Long): TerminalJobReadResult?

    /** 向 tty 任务写输入；成功返回 true，任务不存在/不可写返回 false。 */
    fun write(jobId: String, input: String, waitMs: Long): Boolean

    fun stop(jobId: String): TerminalStopOutcome
}
