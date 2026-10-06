package io.github.fartown.movo.tv

import android.os.Handler
import android.os.Looper
import io.github.fartown.movo.agent.tools.core.*
import io.github.fartown.movo.agent.voice.session.VoiceSessionManager
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object TvEndCallInput : ToolInput
internal data class TvEndCallOutput(val json: JSONObject) : ToolOutput

/**
 * 结束当前语音对话（docs/solutions/tv-voice-app/语音对话退出方案.md）。
 * 执行时登记“这一轮回答念完就结束”：告别语（参数 reply，经 finishingTools 直接成为本轮回答）念完后会话关闭。
 * 何时调用写在 [TvEndCallToolProvider] 的用法分节里，描述只写契约。
 */
internal class TvEndCallTool : ToolContract<TvEndCallInput, TvEndCallOutput> {
    override val name = NAME
    override val domain = ToolDomain.META
    override val summary = "结束当前语音对话：念完告别语后关闭会话。当前不在语音对话中时返回失败。"

    /** 只在语音发起的这一轮可用；打字对话里没有语音会话可结束。 */
    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.spokenReply) ToolAvailability.Available
        else ToolAvailability.Unavailable(ToolErrorCode.UNSUPPORTED, "只在语音对话中可用")

    override fun schema(env: ToolEnvironment) = objectSchema {
        string(REPLY, "告别语，一句简短的话，作为本轮最后的回答念给用户。", required = true)
    }

    // 告别语由 AgentLoop 取走作为本轮回答，执行时不再需要。
    override fun parse(args: ToolArgs, env: ToolEnvironment) = TvEndCallInput

    override fun resolve(input: TvEndCallInput, env: ToolEnvironment) = CallResolution(
        risk = Risk.LOCAL, sensitivity = Sensitivity.NORMAL, resources = emptySet())

    override fun execute(input: TvEndCallInput, resolution: CallResolution, ctx: ToolContext): Verdict<TvEndCallOutput> {
        // 会话只能在主线程操作；在后台线程时投递过去，等登记完再回报。
        val registered = AtomicBoolean(false)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            registered.set(VoiceSessionManager.endAfterReply())
        } else {
            val done = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try { registered.set(VoiceSessionManager.endAfterReply()) } finally { done.countDown() }
            }
            if (!done.await(REGISTER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return Verdict.Unknown("结束语音对话的请求还没处理完", "不要重复调用，直接给出告别语")
            }
        }
        MemoryDiagnostics.record("tv.voice", "end_call", fields = mapOf("registered" to registered.get()))
        if (!registered.get()) {
            return Verdict.Failed(ToolError(ToolErrorCode.NOT_FOUND, "当前不在语音对话中", "直接回答即可"))
        }
        return Verdict.Done(TvEndCallOutput(JSONObject().put("ends_after_reply", true)),
            Evidence.ReadBack("语音会话已登记念完告别语后结束"))
    }

    override fun renderForModel(output: TvEndCallOutput) = ModelContent.Json(output.json)

    companion object {
        const val NAME = "end_call"
        const val REPLY = "reply"
        private const val REGISTER_TIMEOUT_MS = 2_000L
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
            "调用 end_call，告别语写在它的参数里；只在回答里说再见不会结束对话。" +
            "只是拒绝某个提议、取消某件事、事情办完了，或意图不明确时，不要调用。"
    }
}
