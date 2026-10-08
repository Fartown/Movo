package io.github.fartown.movo.agent.tools.terminal

import io.github.fartown.movo.agent.tools.core.TerminalBody
import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.fileName
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.uiBytes
import io.github.fartown.movo.agent.tools.core.uiTime
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.Evidence
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
import io.github.fartown.movo.agent.tools.core.fail
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONArray
import org.json.JSONObject

internal enum class TerminalJobAction { LIST, READ, WRITE, STOP }

internal data class TerminalJobInput(
    val action: TerminalJobAction,
    val jobId: String?,
    val input: String?,
    val waitMs: Long,
    val cursor: String?,
    val stream: TerminalStream,
) : ToolInput

internal data class TerminalJobOutput(
    val json: JSONObject?,
    val textBody: String?,
) : ToolOutput

/** §G.32 terminal_job：管理后台任务：list 列出、read 读输出、write 发输入、stop 停止。 */
internal class TerminalJobTool(
    private val backend: TerminalJobBackend,
) : ToolContract<TerminalJobInput, TerminalJobOutput> {
    override val name = "terminal_job"
    override val domain = ToolDomain.TERMINAL
    override val summary =
        "管理后台命令：action=list 列出、read 读输出（可等待）、write 给 tty 任务发输入、stop 停止。" +
            "普通后台命令只在启动它的那次任务里；keep_alive 常驻任务之后的任务也能管，read 读的是它的日志。" +
            "read 不带 cursor 读最新的尾部，带 next_cursor 续读，cursor=0:0 从头读。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("action", "动作", required = true, enum = TerminalJobAction.entries.map { it.name.lowercase() })
        string("job_id", "任务 ID（read、write、stop 必填）")
        string("input", "要写入的输入（write 必填）")
        integer(
            "wait_ms",
            "read/write 最多等多少毫秒，默认 0 不等。read 在这段时间里攒输出：攒够一段（约 4000 字）或命令结束就返回，" +
                "否则等满再把这期间的输出一起给；write 在对方一有回应时就返回。带 cursor 从游标算起，不带从这次调用算起",
            min = 0,
            max = 180_000,
        )
        string("cursor", "续读游标：上一次 read 返回的 next_cursor；不带则读最新的尾部，0:0 从头读")
        string("stream", "读取的输出流，默认 both", enum = TerminalStream.entries.map { it.name.lowercase() })
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): TerminalJobInput {
        val action = args.enum<TerminalJobAction>("action")
        val jobId = args.stringOrNull("job_id")?.trim()?.ifEmpty { null }
        if (action != TerminalJobAction.LIST && jobId == null) {
            fail(ToolErrorCode.INVALID_ARGUMENTS, "action=${action.name.lowercase()} 需要 job_id")
        }
        val input = args.stringOrNull("input")
        if (action == TerminalJobAction.WRITE && input == null) {
            fail(ToolErrorCode.INVALID_ARGUMENTS, "action=write 需要 input")
        }
        return TerminalJobInput(
            action = action,
            jobId = jobId,
            input = input,
            waitMs = args.long("wait_ms", default = 0, range = 0L..180_000L),
            cursor = args.stringOrNull("cursor"),
            stream = args.enum<TerminalStream>("stream", TerminalStream.BOTH),
        )
    }

    override fun resolve(input: TerminalJobInput, env: ToolEnvironment): CallResolution {
        val risk = when (input.action) {
            TerminalJobAction.LIST, TerminalJobAction.READ -> Risk.READ
            TerminalJobAction.WRITE, TerminalJobAction.STOP -> Risk.LOCAL
        }
        return CallResolution(
            risk = risk,
            sensitivity = Sensitivity.PRIVATE,
            resources = setOf(ResourceKey(ToolResource.TERMINAL)),
        )
    }

    override fun execute(
        input: TerminalJobInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<TerminalJobOutput> {
        if (!ctx.env.switches.terminal) {
            return Verdict.Failed(ToolError(ToolErrorCode.DISABLED, "终端需要开启「终端与文件」开关"))
        }
        ctx.checkCancelled()
        return when (input.action) {
            TerminalJobAction.LIST -> Verdict.Read(TerminalJobOutput(json = listJson(), textBody = null))
            TerminalJobAction.READ -> readVerdict(input, ctx)
            TerminalJobAction.WRITE -> writeVerdict(input, ctx)
            TerminalJobAction.STOP -> stopVerdict(input)
        }
    }

    private fun listJson(): JSONObject {
        val jobs = JSONArray()
        backend.list().forEach { info -> jobs.put(infoJson(info)) }
        return JSONObject().put("jobs", jobs).put("count", jobs.length())
    }

    private fun readVerdict(input: TerminalJobInput, ctx: ToolContext): Verdict<TerminalJobOutput> {
        val read = backend.read(input.jobId!!, input.cursor, input.stream, input.waitMs, cancelled = { ctx.isCancelled })
            ?: return notFound(input.jobId)
        // 等待中被取消：交给管线按取消处理，不把半截结果当成读到的输出。
        ctx.checkCancelled()
        val body = buildString {
            append("job_id: ").append(read.info.jobId).append('\n')
            append("running: ").append(read.info.running).append('\n')
            read.info.exitCode?.let { append("exit_code: ").append(it).append('\n') }
            // 说清这次为什么返回、等了多久：wait_ms 是上限，有新输出就提前回来。
            waitNote(read.wake)?.let { append("waited_ms: ").append(read.waitedMs).append("（").append(it).append("）\n") }
            append("identity: ").append(read.info.identity.name.lowercase()).append('\n')
            if (read.info.streamsMerged) append("streams: merged（stderr 也在同一路输出里）\n")
            read.info.logPath?.let { append("log_path: ").append(it).append('\n') }
            read.nextCursor?.let { append("next_cursor: ").append(it).append('\n') }
            // 说清这段之前有没有没给的输出，以及怎么读到：读尾部省略的开头可以 0:0 从头读；续读跳过的是缓冲区已丢弃的。
            val skippedNote = if (read.tail) "之前的输出没给，cursor=0:0 从头读" else "缓冲区已丢弃，读不回来"
            if (read.stdoutSkipped > 0) append("stdout_skipped: ").append(read.stdoutSkipped).append("（$skippedNote）\n")
            if (read.stderrSkipped > 0) append("stderr_skipped: ").append(read.stderrSkipped).append("（$skippedNote）\n")
            if (read.hasMore) append("more: true（还有没读完的输出，用 next_cursor 续读）\n")
            if (input.stream != TerminalStream.STDERR) {
                append("--- stdout ---\n").append(read.stdout).append('\n')
            }
            if (input.stream != TerminalStream.STDOUT) {
                append("--- stderr ---\n").append(read.stderr)
            }
        }
        return Verdict.Read(TerminalJobOutput(json = null, textBody = body))
    }

    private fun waitNote(wake: TerminalWake): String? = when (wake) {
        TerminalWake.NONE, TerminalWake.CANCELLED -> null
        TerminalWake.NEW_OUTPUT -> "有新输出，提前返回"
        TerminalWake.ENOUGH_OUTPUT -> "攒够一段输出，提前返回"
        TerminalWake.EXITED -> "命令已结束"
        TerminalWake.TIMEOUT -> "等满 wait_ms"
    }

    private fun writeVerdict(input: TerminalJobInput, ctx: ToolContext): Verdict<TerminalJobOutput> {
        val delivered = backend.write(input.jobId!!, input.input!!, input.waitMs, cancelled = { ctx.isCancelled })
        if (!delivered) return notFound(input.jobId)
        ctx.checkCancelled()
        // 送达型：已把输入写进任务 stdin，但程序是否消费无法回读。
        return Verdict.Dispatched(
            TerminalJobOutput(json = JSONObject().put("job_id", input.jobId).put("delivered", true), textBody = null),
        )
    }

    private fun stopVerdict(input: TerminalJobInput): Verdict<TerminalJobOutput> {
        return when (backend.stop(input.jobId!!)) {
            TerminalStopOutcome.STOPPED -> Verdict.Done(
                TerminalJobOutput(JSONObject().put("job_id", input.jobId).put("stopped", true), null),
                Evidence.ReadBack("job ${input.jobId} 已停止"),
            )
            TerminalStopOutcome.NOT_FOUND -> notFound(input.jobId)
            TerminalStopOutcome.ROOT_REQUIRED -> Verdict.Failed(
                ToolError(ToolErrorCode.ROOT_REQUIRED, "停止 Root 身份的常驻任务需要 Root"),
            )
            TerminalStopOutcome.STILL_RUNNING -> Verdict.Unknown(
                reason = "已发停止但未能确认任务停下",
                next = "用 terminal_job list 或 read 核实任务是否仍在运行，不要直接重复 stop",
            )
        }
    }

    override fun uiTitle(input: TerminalJobInput): String = when (input.action) {
        TerminalJobAction.LIST -> "查看后台命令"
        TerminalJobAction.READ -> "读取后台命令的输出"
        TerminalJobAction.WRITE -> "向后台命令输入"
        TerminalJobAction.STOP -> "停止后台命令"
    }

    override fun renderForUi(input: TerminalJobInput, output: TerminalJobOutput): ToolUiView {
        TerminalBody.parse(output.textBody)?.let { body ->
            return ToolUiView(summary = body.exitCode?.let { body.summary() } ?: "还在运行", blocks = body.outputBlocks())
        }
        val json = output.json ?: return ToolUiView(summary = "完成")
        json.optJSONArray("jobs")?.let { jobs ->
            val items = (0 until jobs.length()).mapNotNull { jobs.optJSONObject(it) }.map { job ->
                ToolUiBlock.Item(
                    title = job.optString("description").ifBlank { job.optString("command") },
                    subtitle = job.optString("command").takeIf { job.optString("description").isNotBlank() },
                    trailing = if (job.optBoolean("running")) "运行中" else job.opt("exit_code")?.let { "退出码 $it" },
                )
            }
            return ToolUiView(
                summary = if (items.isEmpty()) "没有后台命令" else "${items.size} 个",
                blocks = listOf(ToolUiBlock.Items(items)).filter { items.isNotEmpty() },
            )
        }
        return ToolUiView(
            summary = when (input.action) {
                TerminalJobAction.STOP -> "已停止"
                TerminalJobAction.WRITE -> "已输入"
                else -> if (json.optBoolean("running")) "还在运行" else json.opt("exit_code")?.let { "退出码 $it" } ?: "完成"
            },
        )
    }

    override fun renderForModel(output: TerminalJobOutput): ModelContent =
        if (output.textBody != null) {
            ModelContent.Text(output.textBody)
        } else {
            ModelContent.Json(output.json ?: JSONObject())
        }

    private fun infoJson(info: TerminalJobInfo): JSONObject = JSONObject()
        .put("job_id", info.jobId)
        .put("command", info.command)
        .apply { info.description?.let { put("description", it) } }
        .put("environment", info.environment.name.lowercase())
        .put("identity", info.identity.name.lowercase())
        .put("running", info.running)
        .put("keep_alive", info.keepAlive)
        .apply { info.exitCode?.let { put("exit_code", it) } }
        .put("started_at", info.startedAtMillis)
        .apply { info.endedAtMillis?.let { put("ended_at", it) } }
        .apply { if (info.streamsMerged) put("streams", "merged") }
        .apply { info.logPath?.let { put("log_path", it) } }

    private fun notFound(jobId: String): Verdict<TerminalJobOutput> = Verdict.Failed(
        ToolError(
            ToolErrorCode.NOT_FOUND,
            "任务不存在：$jobId",
            hint = "普通后台命令只在启动它的那次任务里能查看，keep_alive 常驻任务停掉后也查不到了；用 terminal_job list 看现有的",
        ),
    )
}
