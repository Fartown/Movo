package io.github.fartown.movo.agent.tools.clockmedia

import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.Evidence
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.Retry
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

internal enum class MediaAction { PLAY, PAUSE, TOGGLE, STOP, NEXT, PREVIOUS, FAST_FORWARD, REWIND }

internal data class MediaControlInput(val action: MediaAction) : ToolInput

internal data class MediaControlOutput(
    val action: MediaAction,
    /** 当前媒体应用包名；检测不到为 null。 */
    val session: String?,
    /** 派发后回读到的是否在播；没回读到为 null。 */
    val playing: Boolean? = null,
    /** 回读依据：media_session（会话播放状态）、audio（媒体声音是否在响）、track（曲目变了）；只送达未证实为 null。 */
    val confirmedBy: String? = null,
) : ToolOutput

/** 当前媒体会话状态：区分「确实没有会话」与「无法检测」。 */
internal sealed interface MediaSessionState {
    /**
     * 有活动会话（暂停中的播放器也算）。[playing]：会话报的状态是否在播（缓冲、快进、切歌中也算），
     * 会话没报状态为 null；[track]：当前曲目标识（标题、歌手），切歌回读用，读不到为 null。
     */
    data class Active(
        val packageName: String?,
        val playing: Boolean? = null,
        val track: String? = null,
    ) : MediaSessionState
    /** 确实没有活动会话。 */
    data object None : MediaSessionState
    /** 看不到会话（缺通知使用权且无 Root）；有没有在播只能靠 [MediaControlBackend.musicActive]。 */
    data object Unknown : MediaSessionState
}

/** 可测后端：真实实现查 MediaSessionManager / dumpsys / AudioManager 并派发媒体键；测试用假实现。 */
internal interface MediaControlBackend {
    fun sessionState(env: ToolEnvironment): MediaSessionState
    /** 媒体声音此刻是否在响（AudioManager.isMusicActive，免权限）；读不到为 null。 */
    fun musicActive(): Boolean?
    fun dispatch(action: MediaAction)
}

/**
 * media_control。派发前先看有没有在播：确实没有可控制的播放时直接报错，不派发、不冒领
 * （真机：没通知使用权、没有活跃会话时 pause 仍回 ok，模型据此说「已停住」）。
 * 派发后回读：会话播放状态或媒体声音与请求一致 → Done；回读到与请求相反 → Unknown；
 * 什么都读不到（或快进、快退这类无法回读的动作）→ Dispatched（只是送达）。
 */
internal class MediaControlTool(
    private val backend: MediaControlBackend,
    private val readBackTimeoutMs: Long = READBACK_TIMEOUT_MS,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) : ToolContract<MediaControlInput, MediaControlOutput> {
    override val name = "media_control"
    override val domain = ToolDomain.CLOCK_MEDIA
    override val summary =
        "控制媒体播放：play、pause、toggle、stop、next、previous、fast_forward、rewind（快进快退步长由播放器定）。" +
            "没有播放器时 pause/stop/切歌/快进快退直接报错；暂停中的播放器能切歌。派发后回读：effect_verified=true " +
            "才是确认生效，false 只是送达。独占音频。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string(
            "action", "播放控制动作",
            required = true,
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
        val before = observe(ctx.env)
        nothingToControl(input.action, before)?.let { return Verdict.Failed(it) }
        backend.dispatch(input.action)
        return readBack(input.action, before, ctx)
    }

    /** 一次观察：会话状态 + 媒体声音。 */
    private data class Playback(val session: MediaSessionState, val audio: Boolean?) {
        val packageName: String? get() = (session as? MediaSessionState.Active)?.packageName
        val track: String? get() = (session as? MediaSessionState.Active)?.track

        /** 是否在播：会话报的状态优先；会话没报或看不到会话时看媒体声音。都读不到为 null。 */
        val playing: Boolean?
            get() = when (session) {
                is MediaSessionState.Active -> session.playing ?: audio
                MediaSessionState.None -> audio ?: false
                MediaSessionState.Unknown -> audio
            }

        /** [playing] 依据的来源：会话自己报的状态（或会话都没了）为 media_session，否则是媒体声音。 */
        val basis: String
            get() = when {
                (session as? MediaSessionState.Active)?.playing != null -> "media_session"
                session == MediaSessionState.None && audio != true -> "media_session"
                else -> "audio"
            }
    }

    private fun observe(env: ToolEnvironment) = Playback(
        session = runCatching { backend.sessionState(env) }.getOrDefault(MediaSessionState.Unknown),
        audio = runCatching { backend.musicActive() }.getOrNull(),
    )

    /**
     * 派发前判断：确实没有可控制的播放时返回错误（不派发）。play / toggle 照常派发：
     * 没有会话时媒体键会唤起上一个播放应用。暂停中的播放器仍可切歌、快进，只有 pause / stop 要求正在播。
     * 看不到会话（没有通知使用权、没有 Root）时分不清「没有播放器」和「播放器暂停着」：只有 pause / stop
     * 在没声音时报没在播；切歌、快进快退照发媒体键（重构前的做法），结果只算送达。
     */
    private fun nothingToControl(action: MediaAction, now: Playback): ToolError? {
        if (action == MediaAction.PLAY || action == MediaAction.TOGGLE) return null
        val needsPlaying = action == MediaAction.PAUSE || action == MediaAction.STOP
        return when (val session = now.session) {
            MediaSessionState.None -> if (now.audio == true) {
                ToolError(
                    code = ToolErrorCode.NOT_ACTIONABLE,
                    message = "有声音在响，但不是能用媒体键控制的播放器（没有媒体会话）",
                    hint = "到发出声音的应用界面里操作，或用 volume_set 调小音量；不要说已经控制住了",
                    detail = "audio_without_session",
                )
            } else {
                nothingPlaying(
                    "现在没有正在播放的内容（没有任何媒体会话）",
                    hint = "如实告诉用户现在没有在播放；用户要开始播放才用 play（会唤起上一个播放应用）",
                    detail = "no_media_session",
                )
            }
            is MediaSessionState.Active -> if (needsPlaying && session.playing == false && now.audio != true) {
                nothingPlaying(
                    "现在没有正在播放的内容（${session.packageName ?: "播放器"} 已经是暂停或停止状态）",
                    hint = "不需要再暂停；如实告诉用户现在没有在播放",
                    detail = "already_paused",
                )
            } else {
                null
            }
            MediaSessionState.Unknown -> if (needsPlaying && now.audio == false) {
                nothingPlaying(
                    "现在没有正在播放的内容（没有检测到媒体声音）",
                    hint = "如实告诉用户现在没有在播放，不要说已经暂停或切换。没有通知使用权，看不到暂停中的播放器；" +
                        "用户要继续之前暂停的内容时用 play",
                    detail = "no_music_active",
                )
            } else {
                null
            }
        }
    }

    private fun nothingPlaying(message: String, hint: String, detail: String) = ToolError(
        code = ToolErrorCode.NOT_FOUND,
        message = message,
        hint = hint,
        detail = detail,
        // 不是参数错：换个动作重试（例如自作主张 play）不对，应当如实告诉用户。
        retry = Retry.NEVER,
    )

    /** 派发后按动作期望回读，直到一致或超时。 */
    private fun readBack(action: MediaAction, before: Playback, ctx: ToolContext): Verdict<MediaControlOutput> {
        val wantPlaying: Boolean? = when (action) {
            MediaAction.PLAY -> true
            MediaAction.PAUSE, MediaAction.STOP -> false
            MediaAction.TOGGLE -> before.playing?.not()
            else -> null
        }
        val trackBefore = before.track.takeIf { action == MediaAction.NEXT || action == MediaAction.PREVIOUS }
        // 快进、快退，或切歌前读不到曲目、切换前读不到是否在播：无从回读，只能算送达。
        if (wantPlaying == null && trackBefore == null) return dispatched(action, before)

        // 按次数轮询（与 device_toggle 一致），总时长约 readBackTimeoutMs。
        val attempts = (readBackTimeoutMs / pollIntervalMs.coerceAtLeast(1L)).coerceIn(1L, 1_000L).toInt()
        var after = before
        repeat(attempts) {
            ctx.checkCancelled()
            if (pollIntervalMs > 0) Thread.sleep(pollIntervalMs)
            after = observe(ctx.env)
            if (trackBefore != null) {
                val trackNow = after.track
                if (trackNow != null && trackNow != trackBefore) {
                    return Verdict.Done(
                        MediaControlOutput(action, after.packageName ?: before.packageName, after.playing, "track"),
                        Evidence.ReadBack("track=$trackNow"),
                    )
                }
            } else if (after.playing == wantPlaying) {
                return Verdict.Done(
                    MediaControlOutput(action, after.packageName ?: before.packageName, wantPlaying, after.basis),
                    Evidence.ReadBack("playing=$wantPlaying via ${after.basis}"),
                )
            }
        }
        // 切歌：曲目没变不代表没切（不少视频应用不更新曲目信息），只算送达。
        if (trackBefore != null || after.playing == null) return dispatched(action, before)
        return Verdict.Unknown(
            reason = if (wantPlaying == true) {
                "已发送${actionLabel(action)}键，但 ${"%.1f".format(readBackTimeoutMs / 1000.0)} 秒内没检测到开始播放" +
                    "（可能没有能唤起的播放器，或播放器还在加载）"
            } else {
                "已发送${actionLabel(action)}键，但回读仍在播放（可能是别的应用在出声，或播放器不响应媒体键）"
            },
            next = "先看画面或问用户确认，不要直接重复本动作；没确认前不要说已经${actionLabel(action)}",
        )
    }

    private fun dispatched(action: MediaAction, before: Playback): Verdict<MediaControlOutput> =
        Verdict.Dispatched(MediaControlOutput(action, before.packageName))

    override fun uiTitle(input: MediaControlInput): String = when (input.action) {
        MediaAction.PLAY -> "播放"
        MediaAction.PAUSE -> "暂停"
        MediaAction.TOGGLE -> "播放 / 暂停"
        MediaAction.STOP -> "停止播放"
        MediaAction.NEXT -> "下一首"
        MediaAction.PREVIOUS -> "上一首"
        MediaAction.FAST_FORWARD -> "快进"
        MediaAction.REWIND -> "快退"
    }

    override fun renderForUi(input: MediaControlInput, output: MediaControlOutput): ToolUiView {
        val app = output.session?.let { io.github.fartown.movo.agent.tools.ui.appLabel(it) }
        val confirmed = when {
            output.confirmedBy == null -> null
            output.confirmedBy == "track" -> "已切换"
            output.playing == true -> "正在播放"
            output.action == MediaAction.STOP -> "已停止"
            else -> "已暂停"
        }
        val summary = when {
            confirmed != null -> app?.let { "「$it」$confirmed" } ?: confirmed
            app != null -> "已发送给「$app」"
            else -> "已发送"
        }
        return ToolUiView(summary = summary)
    }

    override fun renderForModel(output: MediaControlOutput): ModelContent {
        val json = JSONObject().put("action", output.action.name.lowercase())
        output.session?.let { json.put("session", it) }
        if (output.confirmedBy != null) {
            output.playing?.let { json.put("playing", it) }
            json.put("confirmed_by", output.confirmedBy)
        } else {
            json.put("note", "媒体键已送达，但读不到播放状态，没确认播放器是否响应")
        }
        return ModelContent.Json(json)
    }

    private fun actionLabel(action: MediaAction): String = when (action) {
        MediaAction.PLAY -> "播放"
        MediaAction.PAUSE -> "暂停"
        MediaAction.TOGGLE -> "播放/暂停"
        MediaAction.STOP -> "停止"
        MediaAction.NEXT -> "下一首"
        MediaAction.PREVIOUS -> "上一首"
        MediaAction.FAST_FORWARD -> "快进"
        MediaAction.REWIND -> "快退"
    }

    internal companion object {
        /** 播放器响应媒体键通常在 1 秒内；冷启动唤起会更久，超时按未确认处理。 */
        const val READBACK_TIMEOUT_MS = 2_000L
        const val POLL_INTERVAL_MS = 250L
    }
}
