package io.github.fartown.movo.agent.tools.terminal

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
        "管理后台任务：action=list 列出、read 读输出（可分页、可等待）、write 给 tty 任务发输入、stop 停止。" +
            "read 不带 cursor 读尾部，带则续读。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("action", "动作", required = true, enum = TerminalJobAction.entries.map { it.name.lowercase() })
        string("job_id", "任务 ID（read、write、stop 必填）")
        string("input", "要写入的输入（write 必填）")
        integer("wait_ms", "read/write 等待输出的毫秒", min = 0, max = 180_000)
        string("cursor", "续读游标；不带则读尾部")
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
            return Verdict.Failed(ToolError(ToolErrorCode.DISABLED, "终端需要开启「文件与终端」开关"))
        }
        ctx.checkCancelled()
        return when (input.action) {
            TerminalJobAction.LIST -> Verdict.Read(TerminalJobOutput(json = listJson(), textBody = null))
            TerminalJobAction.READ -> readVerdict(input)
            TerminalJobAction.WRITE -> writeVerdict(input)
            TerminalJobAction.STOP -> stopVerdict(input)
        }
    }

    private fun listJson(): JSONObject {
        val jobs = JSONArray()
        backend.list().forEach { info -> jobs.put(infoJson(info)) }
        return JSONObject().put("jobs", jobs).put("count", jobs.length())
    }

    private fun readVerdict(input: TerminalJobInput): Verdict<TerminalJobOutput> {
        val read = backend.read(input.jobId!!, input.cursor, input.stream, input.waitMs)
            ?: return notFound(input.jobId)
        val body = buildString {
            append("job_id: ").append(read.info.jobId).append('\n')
            append("running: ").append(read.info.running).append('\n')
            read.info.exitCode?.let { append("exit_code: ").append(it).append('\n') }
            append("identity: ").append(read.info.identity.name.lowercase()).append('\n')
            read.nextCursor?.let { append("next_cursor: ").append(it).append('\n') }
            if (input.stream != TerminalStream.STDERR) {
                append("--- stdout ---\n").append(read.stdout).append('\n')
            }
            if (input.stream != TerminalStream.STDOUT) {
                append("--- stderr ---\n").append(read.stderr)
            }
        }
        return Verdict.Read(TerminalJobOutput(json = null, textBody = body))
    }

    private fun writeVerdict(input: TerminalJobInput): Verdict<TerminalJobOutput> {
        val delivered = backend.write(input.jobId!!, input.input!!, input.waitMs)
        if (!delivered) return notFound(input.jobId)
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
                ToolError(ToolErrorCode.ROOT_REQUIRED, "停止 Root 守护任务需要 Root"),
            )
            TerminalStopOutcome.STILL_RUNNING -> Verdict.Unknown(
                reason = "已发停止但未能确认任务停下",
                next = "用 terminal_job list 或 read 核实任务是否仍在运行，不要直接重复 stop",
            )
        }
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

    private fun notFound(jobId: String): Verdict<TerminalJobOutput> = Verdict.Failed(
        ToolError(
            ToolErrorCode.NOT_FOUND,
            "任务不存在：$jobId",
            hint = "非 keep_alive 任务会随本次任务结束清理",
        ),
    )
}
