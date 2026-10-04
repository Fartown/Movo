package io.github.fartown.movo.agent.tools.terminal

import io.github.fartown.movo.agent.tools.core.ApprovalNeed
import io.github.fartown.movo.agent.tools.core.ApprovalReason
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.JobRef
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.ResourceKey
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolContract
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolError
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.ToolResource
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONObject

internal data class TerminalRunInput(
    val command: String,
    val description: String?,
    val environment: TerminalEnv,
    val identity: TerminalIdentity,
    val cwd: String?,
    val waitMs: Long,
    val tty: Boolean,
    val mode: TerminalMode,
) : ToolInput

internal data class TerminalRunOutput(
    val textBody: String,
) : ToolOutput

/** §G.31 terminal_run：运行一条命令并返回退出码和输出；到 wait_ms 未结束转后台。 */
internal class TerminalRunTool(
    private val backend: TerminalRunBackend,
) : ToolContract<TerminalRunInput, TerminalRunOutput> {
    override val name = "terminal_run"
    override val domain = ToolDomain.TERMINAL
    override val summary =
        "执行 Linux/shell 命令行命令，返回退出码和输出。仅在确实要跑命令行时用：查 App 用量用 usage_read、" +
            "找文件照片用 file_search、读系统设置用 setting_read，别用 find/ls/dumpsys 代替它们。" +
            "一次性用 wait；长任务 mode=background 后用 terminal_job；交互程序 tty=true。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("command", "要执行的命令，≤4000", required = true, maxLength = 4000)
        string("description", "供卡片展示的简述，≤60", maxLength = 60)
        string("environment", "运行环境，默认 android", enum = TerminalEnv.entries.map { it.name.lowercase() })
        string("identity", "身份，默认 user（root 需确认）", enum = TerminalIdentity.entries.map { it.name.lowercase() })
        string("cwd", "工作目录")
        integer("wait_ms", "前台等待毫秒，1000–180000，默认 30000，到时未结束转后台", min = 1000, max = 180_000)
        boolean("tty", "交互式伪终端，返回 job_id，用 terminal_job write 发输入")
        string("mode", "运行方式，默认 wait", enum = TerminalMode.entries.map { it.name.lowercase() })
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): TerminalRunInput = TerminalRunInput(
        command = args.nonBlank("command"),
        description = args.stringOrNull("description")?.trim()?.ifEmpty { null },
        environment = args.enum<TerminalEnv>("environment", TerminalEnv.ANDROID),
        identity = args.enum<TerminalIdentity>("identity", TerminalIdentity.USER),
        cwd = args.stringOrNull("cwd")?.trim()?.ifEmpty { null },
        waitMs = args.long("wait_ms", default = 30_000, range = 1000L..180_000L),
        tty = args.bool("tty", default = false),
        mode = args.enum<TerminalMode>("mode", TerminalMode.WAIT),
    )

    override fun resolve(input: TerminalRunInput, env: ToolEnvironment): CallResolution {
        // root 命令一律确认（放行白名单解析略，见报告 TODO）；keep_alive 要确认；污点由中央派生叠加。
        val risk = if (input.identity == TerminalIdentity.ROOT || input.mode == TerminalMode.KEEP_ALIVE) {
            Risk.EXTERNAL
        } else {
            Risk.LOCAL
        }
        val approval = if (input.mode == TerminalMode.KEEP_ALIVE) {
            ApprovalNeed(
                reason = ApprovalReason.DECLARED_EFFECT,
                title = "保持后台任务运行",
                detail = "keep_alive 任务在本次任务结束后仍将继续运行：${input.command.take(120)}",
            )
        } else {
            null
        }
        return CallResolution(
            risk = risk,
            sensitivity = Sensitivity.PRIVATE,
            resources = setOf(ResourceKey(ToolResource.TERMINAL)),
            toolApproval = approval,
        )
    }

    override fun execute(
        input: TerminalRunInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<TerminalRunOutput> {
        if (!ctx.env.switches.terminal) {
            return Verdict.Failed(ToolError(ToolErrorCode.DISABLED, "终端需要开启「文件与终端」开关"))
        }
        if (input.identity == TerminalIdentity.ROOT && !ctx.env.rootAvailable) {
            return Verdict.Failed(ToolError(ToolErrorCode.ROOT_REQUIRED, "该命令需要 Root"))
        }
        if (input.environment == TerminalEnv.LINUX && !ctx.env.linuxReady) {
            return Verdict.Failed(
                ToolError(ToolErrorCode.UNSUPPORTED, "Linux 环境尚未就绪", detail = "linux_not_ready"),
            )
        }
        ctx.checkCancelled()

        return when (val result = backend.run(input.toSpec())) {
            is TerminalRunResult.Completed -> Verdict.Read(TerminalRunOutput(completedBody(input, result)))
            is TerminalRunResult.Backgrounded -> Verdict.Backgrounded(
                TerminalRunOutput(backgroundedBody(input, result)),
                JobRef(
                    jobId = result.jobId,
                    keepAlive = result.keepAlive,
                    startedAtMillis = result.startedAtMillis,
                    reason = result.reason,
                ),
            )
        }
    }

    override fun renderForModel(output: TerminalRunOutput): ModelContent = ModelContent.Text(output.textBody)

    private fun TerminalRunInput.toSpec() = TerminalRunSpec(
        command = command,
        description = description,
        environment = environment,
        identity = identity,
        cwd = cwd,
        waitMs = waitMs,
        tty = tty,
        mode = mode,
    )

    private fun completedBody(input: TerminalRunInput, result: TerminalRunResult.Completed): String = buildString {
        append("exit_code: ").append(result.exitCode).append('\n')
        append("elapsed_ms: ").append(result.elapsedMs).append('\n')
        append("environment: ").append(input.environment.name.lowercase()).append('\n')
        append("identity: ").append(input.identity.name.lowercase()).append('\n')
        input.cwd?.let { append("cwd: ").append(it).append('\n') }
        append("--- stdout ---\n")
        append(result.stdout)
        if (result.stdoutTruncated) append("\n…(stdout 已截断)…")
        append("\n--- stderr ---\n")
        append(result.stderr)
        if (result.stderrTruncated) append("\n…(stderr 已截断)…")
    }

    private fun backgroundedBody(input: TerminalRunInput, result: TerminalRunResult.Backgrounded): String =
        buildString {
            // job_id / running / reason 由 ContractTool 注入 data（避免重复，这里不再写）。
            append("environment: ").append(input.environment.name.lowercase()).append('\n')
            append("identity: ").append(input.identity.name.lowercase()).append('\n')
            input.cwd?.let { append("cwd: ").append(it).append('\n') }
            append("命令已转入后台，用 terminal_job read 查看输出；不要 sleep 轮询。")
        }
}
