package io.github.fartown.movo.agent.tools.clockmedia

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import io.github.fartown.movo.agent.tools.core.*
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

internal data class MediaSessionInput(val action: String, val seconds: Int, val positionSeconds: Int = -1) : ToolInput
internal data class MediaSessionOutput(val json: JSONObject) : ToolOutput

/**
 * media_control（手机、电视共用）：通过系统媒体会话控制正在播放的 App，并回读播放器状态与进度确认效果。
 * 取得媒体会话要一项通知使用权：[accessComponents] 里任一项已授权就行（手机是「播放控制」或已有的「通知历史」，
 * 后者只是复用已授的权，不会因此新开通知历史；两者都只用来取得会话，不在这里读取通知内容）。
 * 都没授权时，有 [keyFallback]（手机）就把播放、暂停、停止、切换交给它：只发媒体键，按会话或媒体声音回读，
 * 读不到就不算确认。上下集、快进快退、跳转不盲发：确认不了是否生效，模型再去界面补一次就会多跳
 * （真机 10-07：第 4 集变成第 6 集）。
 */
internal class MediaSessionControlTool(
    private val context: Context,
    private val accessComponents: () -> List<ComponentName>,
    private val keyFallback: MediaControlTool? = null,
) : ToolContract<MediaSessionInput, MediaSessionOutput> {
    override val name = "media_control"
    override val domain = ToolDomain.CLOCK_MEDIA
    override val summary = "读取播放状态(status)，或播放、暂停、切换、上下集、快进、快退、跳到指定时间(seek)。" +
        "快进快退 seconds 默认30秒；seek 用 position_s 指定跳到第几秒。回读播放器状态与进度确认效果。需要先授权播放控制。"
    override fun schema(env: ToolEnvironment) = objectSchema {
        string("action", "播放操作或只读状态", required = true,
            enum = listOf("status", "play", "pause", "toggle", "stop", "fast_forward", "rewind", "seek", "next", "previous"))
        integer("seconds", "快进快退秒数，默认30，仅用于快进快退", min = 1, max = 3600)
        integer("position_s", "跳到的位置（从开头算的秒数），仅用于 seek，例如 5 分钟 = 300", min = 0, max = 86_400)
    }
    override fun parse(args: ToolArgs, env: ToolEnvironment): MediaSessionInput {
        val action = args.string("action")
        val position = args.int("position_s", -1, -1..86_400)
        if (action == "seek" && position < 0) invalidArgs("seek 需要 position_s", "例如 5 分钟传 300")
        return MediaSessionInput(action, args.int("seconds", 30, 1..3600), position)
    }
    override fun resolve(input: MediaSessionInput, env: ToolEnvironment) = CallResolution(
        risk = if (input.action == "status") Risk.READ else Risk.LOCAL,
        sensitivity = Sensitivity.NORMAL, resources = setOf(ResourceKey(ToolResource.AUDIO)))

    override fun execute(input: MediaSessionInput, resolution: CallResolution, ctx: ToolContext): Verdict<MediaSessionOutput> {
        ctx.checkCancelled()
        val access = enabledAccess()
        if (access == null) {
            keyFallback(input, resolution, ctx)?.let { return it }
            return Verdict.Failed(ToolError(ToolErrorCode.PERMISSION_REQUIRED,
                "尚未授权播放控制，读不了状态、不能跳转",
                if (keyFallback != null) "play、pause、stop、toggle 仍可直接调用（只发媒体键并回读）；上下集和跳转改用界面操作，或请用户在 Movo 的权限页授权「播放控制」"
                else "请用户在 Movo 的设置里授权播放控制；跳转可改用界面操作"))
        }
        val controller = try { controller(access) } catch (_: SecurityException) {
            return Verdict.Failed(ToolError(ToolErrorCode.PERMISSION_REQUIRED, "播放控制授权尚未生效，请重新授权"))
        } ?: return Verdict.Failed(ToolError(ToolErrorCode.NOT_FOUND, "当前没有可读取的播放器会话", "先在视频应用打开影片"))
        val before = snapshot(controller)
        if (input.action == "status") return Verdict.Read(MediaSessionOutput(before))
        val state = controller.playbackState ?: return Verdict.Failed(ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "播放器尚未提供状态"))
        val action = when (input.action) {
            "toggle" -> if (state.state == PlaybackState.STATE_PLAYING) "pause" else "play"
            // 停止按暂停处理：会话里的 stop 常会退出播放页，用户说「停一下」要的是停住。
            "stop" -> "pause"
            else -> input.action
        }
        val transport = controller.transportControls
        val beforePosition = position(state)
        val seeking = action == "fast_forward" || action == "rewind" || action == "seek"
        if (seeking && beforePosition < 0) return Verdict.Failed(ToolError(ToolErrorCode.SOURCE_UNAVAILABLE,
            "播放器尚未提供有效进度，未执行跳转"))
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        val target = (if (action == "seek") input.positionSeconds * 1000L
            else beforePosition + (if (action == "rewind") -1 else 1) * input.seconds * 1000L)
            .coerceAtLeast(0).let { if (duration != null) it.coerceAtMost(duration) else it }
        val gala = controller.packageName == "com.tcl.qiyiguo"
        val required = when (action) {
            "play" -> PlaybackState.ACTION_PLAY
            "pause" -> PlaybackState.ACTION_PAUSE
            "fast_forward", "rewind", "seek" -> PlaybackState.ACTION_SEEK_TO
            "next" -> PlaybackState.ACTION_SKIP_TO_NEXT
            else -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
        }
        // During adverts Gala publishes actions=0. Do not treat the advert as controllable content.
        val galaEpisode = gala && action in setOf("next", "previous") && state.actions != 0L
        if (!galaEpisode && state.actions and required == 0L) return Verdict.Failed(ToolError(ToolErrorCode.UNSUPPORTED,
            "当前播放器状态不支持此操作（可能仍在广告或加载中）", "先读取状态或观察画面，不要重复派发"))
        // getPlaybackState() extrapolates time even without a new player update. Only
        // callbacks can prove a seek; polling its timestamp would produce false positives.
        val observed = AtomicReference<PlaybackState?>()
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) { observed.set(state) }
        }
        controller.registerCallback(callback, Handler(Looper.getMainLooper()))
        val dispatchedAt = SystemClock.elapsedRealtime()
        var after = before
        var verified = false
        var seekConfirmed = false
        var pauseRequested = false
        var confirmedPosition: Long? = null
        val seekDistance = abs(target - beforePosition)
        val tolerance = minOf(if (gala) 10000L else 2000L, maxOf(250L, seekDistance / 2))
        try {
            when (action) {
                "play" -> transport.play()
                "pause" -> transport.pause()
                "fast_forward", "rewind", "seek" -> if (gala) {
                    // Gala's relative protocol uses a delayed scrubber. An absolute target
                    // avoids accumulating jumps; its standard seekTo is not reliable on TCL.
                    transport.sendCustomAction("com.gala.video.mediasession.custom.action.SEEK",
                        Bundle().apply { putLong("position", target) })
                } else transport.seekTo(target)
                "next", "previous" -> if (gala) {
                    transport.sendCustomAction("com.gala.video.mediasession.custom.action.SELECT_EPISODE",
                        Bundle().apply { putString("action", if (action == "next") "next" else "prev") })
                } else if (action == "next") transport.skipToNext() else transport.skipToPrevious()
            }
            val deadline = dispatchedAt + if (seeking) 12000 else 5000
            while (SystemClock.elapsedRealtime() < deadline) {
                ctx.checkCancelled()
                val current = controller.playbackState
                val actual = observed.get()?.takeIf { it.lastPositionUpdateTime >= dispatchedAt }
                after = snapshot(controller)
                verified = when (action) {
                    "play" -> current?.state == PlaybackState.STATE_PLAYING &&
                        (state.state == PlaybackState.STATE_PLAYING || actual?.state == PlaybackState.STATE_PLAYING)
                    "pause" -> current?.state == PlaybackState.STATE_PAUSED &&
                        (state.state == PlaybackState.STATE_PAUSED || actual?.state == PlaybackState.STATE_PAUSED)
                    "fast_forward", "rewind", "seek" -> {
                        if (confirmedPosition == null && actual != null && actual.position >= 0 &&
                            actual.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED)) {
                            val naturalPosition = beforePosition + if (state.state == PlaybackState.STATE_PLAYING)
                                ((actual.lastPositionUpdateTime - dispatchedAt).coerceAtLeast(0) * state.playbackSpeed).toLong() else 0
                            val jump = actual.position - naturalPosition
                            val changedInDirection = when {
                                action == "rewind" -> jump < -250
                                action == "seek" -> if (target < naturalPosition) jump < -250 else jump > 250
                                else -> jump > 250
                            }
                            if (changedInDirection && abs(jump) >= seekDistance / 2) {
                                confirmedPosition = actual.position
                                seekConfirmed = abs(actual.position - target) <= tolerance
                            }
                        }
                        // Gala resumes after seeking. Keep a paused player's original state.
                        if (confirmedPosition != null && state.state == PlaybackState.STATE_PAUSED &&
                            current?.state != PlaybackState.STATE_PAUSED && !pauseRequested) {
                            transport.pause()
                            pauseRequested = true
                        }
                        seekConfirmed && (state.state != PlaybackState.STATE_PAUSED ||
                            actual?.state == PlaybackState.STATE_PAUSED)
                    }
                    // 换集：内容 ID、标题或简介任一变了就算确认（B 站换集后 ID 不变，只有标题和「自动连播(3/16)」变）。
                    else -> listOf("media_id", "title", "description").any { key ->
                        after.optString(key).isNotBlank() && after.optString(key) != before.optString(key)
                    }
                }
                if (verified) break
                Thread.sleep(100)
            }
            // Even an unconfirmed seek must not leave a previously paused player playing.
            if (seeking && state.state == PlaybackState.STATE_PAUSED &&
                controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                transport.pause()
                val restoreDeadline = SystemClock.elapsedRealtime() + 2000
                while (SystemClock.elapsedRealtime() < restoreDeadline &&
                    controller.playbackState?.state != PlaybackState.STATE_PAUSED) {
                    ctx.checkCancelled()
                    Thread.sleep(100)
                }
                after = snapshot(controller)
                verified = seekConfirmed && after.optString("state") == "paused"
            }
        } finally {
            controller.unregisterCallback(callback)
        }
        MemoryDiagnostics.record("media", "control", fields = mapOf("action" to action,
            "package" to controller.packageName, "verified" to verified, "before_state" to before.optString("state"),
            "after_state" to after.optString("state"), "before_ms" to beforePosition, "after_ms" to after.optLong("position_ms"),
            "target_ms" to if (seeking) target else -1, "callback_position_ms" to confirmedPosition, "last_callback_ms" to observed.get()?.position))
        val output = MediaSessionOutput(JSONObject().put("action", action).put("before", before).put("after", after)
            .apply { if (seeking) { put("target_ms", target); put("confirmed_position_ms", confirmedPosition)
                put("verification_tolerance_ms", tolerance)
                put("seek_error_ms", confirmedPosition?.minus(target))
                put("accuracy", "定位有偏差时按 confirmed_position_ms 报告实际回调落点；容差由本工具设置，不代表播放器声明，不能推断误差原因或声称精确跳转") } })
        return if (verified) Verdict.Done(output, Evidence.ReadBack(after.toString()))
            else Verdict.Unknown("已发送控制但未确认，操作前：$before；目标进度：${if (seeking) target else "无"}；操作后：$after",
                "先用 status 或截图核实；播放器可能延迟更新，不能仅凭旧进度重新快进或快退")
    }

    /** 第一个已授权的通知使用权组件；都没授权为 null。 */
    private fun enabledAccess(): ComponentName? {
        val notifications = context.getSystemService(NotificationManager::class.java)
        return accessComponents().firstOrNull { notifications.isNotificationListenerAccessGranted(it) }
    }

    /** 前台 App 的会话优先，其次正在播放的，再其次任意一个有状态的会话。 */
    private fun controller(access: ComponentName): MediaController? {
        val sessions = context.getSystemService(MediaSessionManager::class.java).getActiveSessions(access)
            .filter { it.playbackState != null }
        val foreground = AgentAccessibilityService.current()?.currentPackageName()
        return sessions.firstOrNull { it.packageName == foreground }
            ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
    }

    /** 没授权时：播放、暂停、停止、切换交给媒体键工具（发键并回读）；其余不盲发，返回 null 由调用方报需要授权。 */
    private fun keyFallback(input: MediaSessionInput, resolution: CallResolution, ctx: ToolContext): Verdict<MediaSessionOutput>? {
        val tool = keyFallback ?: return null
        val action = when (input.action) {
            "play" -> MediaAction.PLAY
            "pause" -> MediaAction.PAUSE
            "stop" -> MediaAction.STOP
            "toggle" -> MediaAction.TOGGLE
            else -> return null
        }
        val wrap = { output: MediaControlOutput ->
            val json = (tool.renderForModel(output) as? ModelContent.Json)?.obj ?: JSONObject()
            MediaSessionOutput(json.put("via", "media_key"))
        }
        return when (val verdict = tool.execute(MediaControlInput(action), resolution, ctx)) {
            is Verdict.Done -> Verdict.Done(wrap(verdict.output), verdict.evidence)
            is Verdict.Dispatched -> Verdict.Dispatched(wrap(verdict.output))
            is Verdict.Read -> Verdict.Read(wrap(verdict.output))
            is Verdict.Backgrounded -> Verdict.Dispatched(wrap(verdict.output))
            is Verdict.Unknown -> verdict
            is Verdict.Failed -> verdict
        }
    }

    private fun position(state: PlaybackState): Long = if (state.state == PlaybackState.STATE_PLAYING && state.position >= 0)
        state.position + ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0) * state.playbackSpeed).toLong()
        else state.position

    private fun snapshot(controller: MediaController): JSONObject {
        val state = controller.playbackState
        val metadata = controller.metadata
        return JSONObject().put("package", controller.packageName)
            .put("state", when (state?.state) { PlaybackState.STATE_PLAYING -> "playing"; PlaybackState.STATE_PAUSED -> "paused"
                PlaybackState.STATE_BUFFERING -> "buffering"; PlaybackState.STATE_STOPPED -> "stopped"; else -> "unknown" })
            .put("position_ms", state?.let(::position) ?: -1)
            .put("position_estimated", state?.state == PlaybackState.STATE_PLAYING)
            .put("duration_ms", metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0)
            .put("title", metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty())
            .put("media_id", metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty())
            .put("description", metadata?.description?.description?.toString().orEmpty())
            .put("actions", state?.actions ?: 0)
    }
    override fun uiTitle(input: MediaSessionInput): String = when (input.action) {
        "status" -> "查看播放状态"
        "play" -> "播放"
        "pause" -> "暂停"
        "toggle" -> "播放 / 暂停"
        "stop" -> "停止播放"
        "next" -> "下一集"
        "previous" -> "上一集"
        "fast_forward" -> "快进 ${input.seconds} 秒"
        "rewind" -> "后退 ${input.seconds} 秒"
        "seek" -> "跳到 %d:%02d".format(input.positionSeconds / 60, input.positionSeconds % 60)
        else -> "播放控制"
    }

    override fun renderForModel(output: MediaSessionOutput) = ModelContent.Json(output.json)
}
