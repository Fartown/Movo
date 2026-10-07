package io.github.fartown.movo.agent.tools.terminal

/** 运行环境：android=直接在 Android 上跑；linux=proot/chroot 的 Linux 工具链。 */
internal enum class TerminalEnv { ANDROID, LINUX }

/** 身份：user=App 进程；root=su。 */
internal enum class TerminalIdentity { USER, ROOT }

/**
 * 运行方式：wait=前台等 wait_ms；background=直接转后台，本次任务结束时停止；
 * keep_alive=直接转后台，本次任务结束时不停止进程，但之后的任务看不到、也停不了它（跨任务守护没有实现）。
 */
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
        /** 缓冲区是否在中间丢过输出（丢过时正文接缝处已注明省略了多少字）。 */
        val stdoutTruncated: Boolean,
        val stderrTruncated: Boolean,
        /** 命令实际在哪个目录里跑（没传 cwd 时是工作区）；为空时按传入的 cwd 显示。 */
        val cwd: String? = null,
    ) : TerminalRunResult

    /** 到 wait_ms 未结束，或 background/keep_alive：转后台。 */
    data class Backgrounded(
        val jobId: String,
        val reason: String,
        val startedAtMillis: Long,
        val keepAlive: Boolean,
        /** 同 [Completed.cwd]。 */
        val cwd: String? = null,
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
    /** 没带 cursor、读的是尾部（最新的输出）。 */
    val tail: Boolean = false,
    /** 本次给出的这段之前没有给出的字数：读尾部时是省略的前面部分，续读时是缓冲区已丢弃、跳过的部分。 */
    val stdoutSkipped: Long = 0,
    val stderrSkipped: Long = 0,
    /** next_cursor 之后还有已产生、没读完的输出。 */
    val hasMore: Boolean = false,
    /** 这次读为什么返回（没有等待时为 [TerminalWake.NONE]）。 */
    val wake: TerminalWake = TerminalWake.NONE,
    /** 实际等了多少毫秒。 */
    val waitedMs: Long = 0,
)

/**
 * read / write 带 wait_ms 时为什么返回：有新输出就尽快返回，命令结束也返回，都没有就等满 wait_ms。
 * 「新输出」带 cursor 时指 cursor 之后的输出（之后已经有了就不等），不带 cursor 时指这次调用之后才产生的输出。
 */
internal enum class TerminalWake {
    /** 没有等：wait_ms=0，或任务已经结束。 */
    NONE,
    NEW_OUTPUT,
    EXITED,
    TIMEOUT,
    /** 运行被取消，提前结束等待（调用方随后按取消处理）。 */
    CANCELLED,
}

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

    /**
     * 读取输出；任务不存在返回 null。[waitMs] 是最多等多久（见 [TerminalWake]）；
     * 等待期间按 [cancelled] 检查运行是否已取消，取消就立刻返回。
     */
    fun read(
        jobId: String,
        cursor: String?,
        stream: TerminalStream,
        waitMs: Long,
        cancelled: () -> Boolean = { false },
    ): TerminalJobReadResult?

    /** 向 tty 任务写输入，再最多等 [waitMs] 看有没有新输出；成功返回 true，任务不存在/不可写返回 false。 */
    fun write(jobId: String, input: String, waitMs: Long, cancelled: () -> Boolean = { false }): Boolean

    fun stop(jobId: String): TerminalStopOutcome
}
