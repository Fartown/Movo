package io.github.fartown.movo.tv

import android.os.Handler
import android.os.Looper
import io.github.fartown.movo.agent.tools.core.*
import io.github.fartown.movo.agent.voice.session.VoiceSessionManager
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object TvEndCallInput : ToolInput
internal data class TvEndCallOutput(val json: JSONObject) : ToolOutput

/**
 * 结束当前语音对话（docs/solutions/tv-voice-app/语音对话退出方案.md）：立即结束会话，与返回键走同一个入口。
 * 何时调用写在 [TvEndCallToolProvider] 的用法分节里，描述只写契约。
 */
internal class TvEndCallTool : ToolContract<TvEndCallInput, TvEndCallOutput> {
    override val name = NAME
    override val domain = ToolDomain.META
    // 只写做什么：写“不会念出”时模型会把“别念了”当成要结束（评测 14/300 误调用，见方案 §7.3）。
    override val summary = "结束当前语音对话。当前不在语音对话中时返回失败。"

    /** 只在语音发起的这一轮可用；打字对话里没有语音会话可结束。 */
    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.spokenReply) ToolAvailability.Available
        else ToolAvailability.Unavailable(ToolErrorCode.UNSUPPORTED, "只在语音对话中可用")

    override fun schema(env: ToolEnvironment) = objectSchema {}

    override fun parse(args: ToolArgs, env: ToolEnvironment) = TvEndCallInput

    override fun resolve(input: TvEndCallInput, env: ToolEnvironment) = CallResolution(
        risk = Risk.LOCAL, sensitivity = Sensitivity.NORMAL, resources = emptySet())

    override fun execute(input: TvEndCallInput, resolution: CallResolution, ctx: ToolContext): Verdict<TvEndCallOutput> {
        if (!VoiceSessionManager.active) {
            return Verdict.Failed(ToolError(ToolErrorCode.NOT_FOUND, "当前不在语音对话中", "直接回答即可"))
        }
        // 会话只能在主线程结束；在后台线程时投递过去，等结束后回读。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            VoiceSessionManager.end()
        } else {
            val done = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { try { VoiceSessionManager.end() } finally { done.countDown() } }
            done.await(END_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        val ended = !VoiceSessionManager.active
        MemoryDiagnostics.record("tv.voice", "end_call", fields = mapOf("ended" to ended))
        if (!ended) return Verdict.Unknown("已请求结束语音对话，但会话仍在进行", "不要重复调用")
        return Verdict.Done(TvEndCallOutput(JSONObject().put("ended", true)), Evidence.ReadBack("语音会话已结束"))
    }

    override fun renderForModel(output: TvEndCallOutput) = ModelContent.Json(output.json)

    companion object {
        const val NAME = "end_call"
        private const val END_TIMEOUT_MS = 2_000L
    }
}

/** 电视语音专用工具；用法分节只在工具可用（语音轮）时注入。 */
internal class TvEndCallToolProvider : ToolProvider {
    override val tools: List<AgentTool> = listOf(ContractTool(TvEndCallTool()))
    override val promptSection = PromptSection("tv_end_call", ToolDomain.META, USAGE)

    companion object {
        // 措辞按真实模型评测选定（.docs/tv-voice-exit/eval，方案 §7.3）：不说“只说再见不会结束”时模型常只在回答里告别；
        // 示例说法与评测题不重合，验证集上同样有效。
        const val USAGE = "结束语音对话：用户明确表示要结束对话或不再需要你时（例如「行了，没你的事了」「你歇着吧」「今天就到这儿」），" +
            "调用 end_call；只在回答里说再见不会结束对话。" +
            "只是拒绝某个提议、取消某件事、事情办完了，或意图不明确时，不要调用。"
    }
}
