package io.github.fartown.movo.tv

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicReference
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import io.github.fartown.movo.agent.tools.core.*
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import org.json.JSONObject
import kotlin.math.abs

/** Grants media-session access only. Notification contents are neither queried nor stored. */
class TvMediaAccessService : NotificationListenerService()

internal object TvMediaAccess {
    fun component(context: Context) = ComponentName(context, TvMediaAccessService::class.java)
    fun enabled(context: Context) = context.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(component(context))

    fun controller(context: Context): MediaController? {
        val sessions = context.getSystemService(MediaSessionManager::class.java).getActiveSessions(component(context))
            .filter { it.playbackState != null }
        val foreground = AgentAccessibilityService.current()?.currentPackageName()
        return sessions.firstOrNull { it.packageName == foreground }
            ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
    }
}

internal data class TvMediaInput(val action: String, val seconds: Int) : ToolInput
internal data class TvMediaOutput(val json: JSONObject) : ToolOutput

/** TV playback controls with actual MediaSession readback; no root or key injection. */
internal class TvMediaControlTool(private val context: Context) : ToolContract<TvMediaInput, TvMediaOutput> {
    override val name = "media_control"
    override val domain = ToolDomain.CLOCK_MEDIA
    override val summary = "读取播放状态(status)，或播放、暂停、切换、快进、快退、上下集；快进快退 seconds 默认30秒。回读播放器状态与进度确认效果。先在设置授权播放控制。"
    override fun schema(env: ToolEnvironment) = objectSchema {
        string("action", "播放操作或只读状态", required = true,
            enum = listOf("status", "play", "pause", "toggle", "fast_forward", "rewind", "next", "previous"))
        integer("seconds", "快进快退秒数，默认30，仅用于快进快退", min = 1, max = 3600)
    }
    override fun parse(args: ToolArgs, env: ToolEnvironment) = TvMediaInput(args.string("action"), args.int("seconds", 30, 1..3600))
    override fun resolve(input: TvMediaInput, env: ToolEnvironment) = CallResolution(
        risk = if (input.action == "status") Risk.READ else Risk.LOCAL,
        sensitivity = Sensitivity.NORMAL, resources = setOf(ResourceKey(ToolResource.AUDIO)))

    override fun execute(input: TvMediaInput, resolution: CallResolution, ctx: ToolContext): Verdict<TvMediaOutput> {
        ctx.checkCancelled()
        if (!TvMediaAccess.enabled(context)) return Verdict.Failed(ToolError(ToolErrorCode.PERMISSION_REQUIRED,
            "尚未授权播放控制", "打开 Movo 设置 → 播放控制权限，允许媒体会话访问"))
        val controller = try { TvMediaAccess.controller(context) } catch (_: SecurityException) {
            return Verdict.Failed(ToolError(ToolErrorCode.PERMISSION_REQUIRED, "播放控制授权尚未生效，请重新授权"))
        } ?: return Verdict.Failed(ToolError(ToolErrorCode.NOT_FOUND, "当前没有可读取的播放器会话", "先在视频应用打开影片"))
        val before = snapshot(controller)
        if (input.action == "status") return Verdict.Read(TvMediaOutput(before))
        val state = controller.playbackState ?: return Verdict.Failed(ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "播放器尚未提供状态"))
        val action = if (input.action == "toggle") {
            if (state.state == PlaybackState.STATE_PLAYING) "pause" else "play"
        } else input.action
        val transport = controller.transportControls
        val beforePosition = position(state)
        val seeking = action == "fast_forward" || action == "rewind"
        if (seeking && beforePosition < 0) return Verdict.Failed(ToolError(ToolErrorCode.SOURCE_UNAVAILABLE,
            "播放器尚未提供有效进度，未执行跳转"))
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        val target = (beforePosition + (if (action == "rewind") -1 else 1) * input.seconds * 1000L)
            .coerceAtLeast(0).let { if (duration != null) it.coerceAtMost(duration) else it }
        val gala = controller.packageName == "com.tcl.qiyiguo"
        val required = when (action) {
            "play" -> PlaybackState.ACTION_PLAY
            "pause" -> PlaybackState.ACTION_PAUSE
            "fast_forward", "rewind" -> PlaybackState.ACTION_SEEK_TO
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
                "fast_forward", "rewind" -> if (gala) {
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
                    "fast_forward", "rewind" -> {
                        if (confirmedPosition == null && actual != null && actual.position >= 0 &&
                            actual.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED)) {
                            val naturalPosition = beforePosition + if (state.state == PlaybackState.STATE_PLAYING)
                                ((actual.lastPositionUpdateTime - dispatchedAt).coerceAtLeast(0) * state.playbackSpeed).toLong() else 0
                            val jump = actual.position - naturalPosition
                            val changedInDirection = if (action == "rewind") jump < -250 else jump > 250
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
                    else -> after.optString("media_id") != before.optString("media_id") && after.optString("media_id").isNotBlank()
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
        MemoryDiagnostics.record("tv.media", "control", fields = mapOf("action" to action,
            "package" to controller.packageName, "verified" to verified, "before_state" to before.optString("state"),
            "after_state" to after.optString("state"), "before_ms" to beforePosition, "after_ms" to after.optLong("position_ms"),
            "target_ms" to if (seeking) target else -1, "callback_position_ms" to confirmedPosition, "last_callback_ms" to observed.get()?.position))
        val output = TvMediaOutput(JSONObject().put("action", action).put("before", before).put("after", after)
            .apply { if (seeking) { put("target_ms", target); put("confirmed_position_ms", confirmedPosition)
                put("verification_tolerance_ms", tolerance)
                put("seek_error_ms", confirmedPosition?.minus(target))
                put("accuracy", "定位有偏差时按 confirmed_position_ms 报告实际回调落点；容差由本工具设置，不代表播放器声明，不能推断误差原因或声称精确跳转") } })
        return if (verified) Verdict.Done(output, Evidence.ReadBack(after.toString()))
            else Verdict.Unknown("已发送控制但未确认，操作前：$before；目标进度：${if (seeking) target else "无"}；操作后：$after",
                "先用 status 或截图核实；播放器可能延迟更新，不能仅凭旧进度重新快进或快退")
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
            .put("actions", state?.actions ?: 0)
    }
    override fun renderForModel(output: TvMediaOutput) = ModelContent.Json(output.json)
}
