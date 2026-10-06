package io.github.fartown.movo.agent.tools.clockmedia

import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.ResourceKey
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.ToolResource
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolContract
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolError
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONObject

internal enum class MediaAction { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, FAST_FORWARD, REWIND }

internal data class MediaControlInput(val action: MediaAction) : ToolInput

internal data class MediaControlOutput(
    val action: MediaAction,
    /** 当前媒体应用包名；检测不到为 null。 */
    val session: String?,
) : ToolOutput

/** 当前媒体会话状态：区分「确实没有会话」与「无法检测」。 */
internal sealed interface MediaSessionState {
    data class Active(val packageName: String?) : MediaSessionState
    /** 确实没有活动会话。 */
    data object None : MediaSessionState
    /** 无法检测（缺通知使用权且无 Root）。 */
    data object Unknown : MediaSessionState
}

/** 可测后端：真实实现查 MediaSessionManager / dumpsys 并派发媒体键；测试用假实现。 */
internal interface MediaControlBackend {
    fun sessionState(env: ToolEnvironment): MediaSessionState
    fun dispatch(action: MediaAction)
}

/**
 * media_control（送达型）。媒体键已派发不代表目标播放器已响应，effect_verified=false，需再查播放状态。
 * 确实没有会话时，非 play 动作报 NOT_FOUND；play 会唤起上一个媒体应用，照常派发。
 */
internal class MediaControlTool(
    private val backend: MediaControlBackend,
) : ToolContract<MediaControlInput, MediaControlOutput> {
    override val name = "media_control"
    override val domain = ToolDomain.CLOCK_MEDIA
    override val summary =
        "控制正在播放的媒体：play、pause、toggle、next、previous、fast_forward（快进）、rewind（快退）。" +
            "快进/快退的步长由播放器决定。媒体键只是派发，不代表播放器已响应，" +
            "需再查播放状态确认。独占音频。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string(
            "action", "播放控制动作", required = true,
            enum = MediaAction.entries.map { it.name.lowercase() },
        )
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): MediaControlInput =
        MediaControlInput(args.enum("action"))

    override fun resolve(input: MediaControlInput, env: ToolEnvironment): CallResolution =
        CallResolution(
            risk = Risk.LOCAL,
            sensitivity = Sensitivity.NORMAL,
            resources = setOf(ResourceKey(ToolResource.AUDIO)),   // 独占音频，避免并发派发媒体键
        )

    override fun execute(
        input: MediaControlInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<MediaControlOutput> {
        ctx.checkCancelled()
        val state = runCatching { backend.sessionState(ctx.env) }.getOrDefault(MediaSessionState.Unknown)
        if (state is MediaSessionState.None && input.action != MediaAction.PLAY) {
            return Verdict.Failed(
                ToolError(
                    code = ToolErrorCode.NOT_FOUND,
                    message = "当前没有正在播放的媒体会话",
                    hint = "用 play 唤起上一个媒体应用，或先确认有在播放的应用",
                ),
            )
        }
        backend.dispatch(input.action)
        val session = (state as? MediaSessionState.Active)?.packageName
        return Verdict.Dispatched(MediaControlOutput(input.action, session))
    }

    override fun uiTitle(input: MediaControlInput): String = when (input.action) {
        MediaAction.PLAY -> "播放"
        MediaAction.PAUSE -> "暂停"
        MediaAction.TOGGLE -> "播放 / 暂停"
        MediaAction.NEXT -> "下一首"
        MediaAction.PREVIOUS -> "上一首"
    }

    override fun renderForUi(input: MediaControlInput, output: MediaControlOutput): ToolUiView =
        ToolUiView(summary = output.session?.let { "已发送给「${io.github.fartown.movo.agent.tools.ui.appLabel(it)}」" } ?: "已发送")

    override fun renderForModel(output: MediaControlOutput): ModelContent {
        val json = JSONObject().put("action", output.action.name.lowercase())
        output.session?.let { json.put("session", it) }
        return ModelContent.Json(json)
    }
}
