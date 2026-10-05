package io.github.fartown.movo.agent.tools.clockmedia

import android.os.Build
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import io.github.fartown.movo.agent.device.AgentNotificationHistoryService
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tool.AgentPrivateDatabaseTools
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.core.AgentLogger
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * clock_create 真实后端（回读型，方案 a）。
 * 派发顺序：挂透明窗 → 派发 Intent → 按证据核实 → 撤窗。
 * 核实归因到本次创建：无 Root 用 AlarmManager.getNextAlarmClock() 匹配本次请求时刻；
 * 有 Root 再用 dumpsys alarm 做更精确的归因（见 [rootAttributed]），不被日历 0 点闹钟占住。
 */
internal class RealClockCreateBackend(
    private val context: Context,
    private val logger: AgentLogger,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean,
) : ClockCreateBackend {

    override fun createAndVerify(input: ClockCreateInput, env: ToolEnvironment): ClockCreateResult {
        val intent = buildIntent(input)
        if (resolveClockActivity(intent) == null) return ClockCreateResult.NoClockApp

        val before = nextAlarmTriggerMs()
        val dispatchedAt = System.currentTimeMillis()
        val dispatched = ClockBackgroundAnchor.withVisibleWindow(context) {
            runCatching { context.startActivity(intent) }.isSuccess
        }
        if (!dispatched) return ClockCreateResult.NoClockApp
        logger.info("clock_create dispatched type=${input.type} marker=${ClockCreateTool.requestMarker(input)}")

        val expected = expectedTriggerMs(input, dispatchedAt)
        val marker = ClockCreateTool.requestMarker(input)
        val deadline = SystemClock.elapsedRealtime() + POLL_TIMEOUT_MS
        do {
            val after = nextAlarmTriggerMs()
            if (after != null && after != before && matches(input, after, expected)) {
                return ClockCreateResult.Attributed(after, marker)
            }
            if (rootAvailable() && rootAttributed(input, expected)) {
                return ClockCreateResult.Attributed(expected, marker)
            }
            if (SystemClock.elapsedRealtime() >= deadline) break
            runCatching { Thread.sleep(POLL_INTERVAL_MS) }
        } while (true)
        return ClockCreateResult.NotAttributed
    }

    private fun buildIntent(input: ClockCreateInput): Intent = when (input.type) {
        ClockType.ALARM -> Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, input.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, input.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_VIBRATE, input.vibrate)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply {
                input.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                if (input.repeatDays.isNotEmpty()) {
                    putIntegerArrayListExtra(
                        AlarmClock.EXTRA_DAYS,
                        ArrayList(input.repeatDays.map { it.calendarDay }),
                    )
                }
            }
        ClockType.TIMER -> Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, input.durationSeconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { input.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
    }

    /** 优先用 ColorOS 时钟处理；否则用系统能解析到的时钟。解析不到返回 null。 */
    private fun resolveClockActivity(intent: Intent): Intent? {
        val pm = context.packageManager
        val preferred = Intent(intent).setPackage(COLOROS_CLOCK_PACKAGE)
        return when {
            preferred.resolveActivity(pm) != null -> preferred
            intent.resolveActivity(pm) != null -> intent
            else -> null
        }
    }

    private fun nextAlarmTriggerMs(): Long? =
        runCatching {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock?.triggerTime
        }.getOrNull()

    private fun expectedTriggerMs(input: ClockCreateInput, dispatchedAt: Long): Long = when (input.type) {
        ClockType.ALARM -> nextAlarmTrigger(dispatchedAt, input.hour, input.minute, input.repeatDays.map { it.calendarDay })
        ClockType.TIMER -> dispatchedAt + input.durationSeconds * 1_000L
    }

    private fun matches(input: ClockCreateInput, afterTriggerMs: Long, expected: Long): Boolean = when (input.type) {
        ClockType.ALARM -> abs(afterTriggerMs - expected) < ALARM_TOLERANCE_MS
        ClockType.TIMER ->
            afterTriggerMs in (expected - TIMER_EARLY_TOLERANCE_MS)..(expected + POLL_TIMEOUT_MS + TIMER_LATE_TOLERANCE_MS)
    }

    /**
     * 有 Root 时用 `dumpsys alarm` 匹配本次请求 HH:MM/时长新出现的 ALARM_ALERT/TIMER_ALERT，
     * 比 getNextAlarmClock 更能归因到本次创建（不被日历 0 点闹钟占住）。
     * alarm 直接按请求 HH:MM 匹配；timer 把 expected 触发时刻折算成 HH:MM 再匹配。
     * 解析用纯函数 [DumpsysAlarmParser]（见其单测）；dumpsys 执行失败或无命中都返回 false，由 getNextAlarmClock 兜底。
     */
    private fun rootAttributed(input: ClockCreateInput, expected: Long): Boolean {
        val dump = runCatching { root.execute("dumpsys alarm", maxOutputBytes = 512 * 1024) }
            .getOrNull()
            ?.takeIf { it.ok }
            ?.stdout
            ?: return false
        return when (input.type) {
            ClockType.ALARM -> DumpsysAlarmParser.hasAlarmAt(dump, input.hour, input.minute)
            ClockType.TIMER -> {
                val cal = Calendar.getInstance().apply { timeInMillis = expected }
                DumpsysAlarmParser.hasTimerAt(dump, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            }
        }
    }

    private companion object {
        const val COLOROS_CLOCK_PACKAGE = "com.coloros.alarmclock"
        const val POLL_TIMEOUT_MS = 4_000L
        const val POLL_INTERVAL_MS = 200L
        const val TIMER_EARLY_TOLERANCE_MS = 2_000L
        const val TIMER_LATE_TOLERANCE_MS = 10_000L
        const val ALARM_TOLERANCE_MS = 60_000L

        /** [nowMs] 之后第一个 hour:minute:00；给了 repeatDays 时还要落在这些星期里。 */
        fun nextAlarmTrigger(nowMs: Long, hour: Int, minute: Int, repeatDays: Collection<Int>): Long {
            val candidate = Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            repeat(8) {
                if (candidate.timeInMillis > nowMs &&
                    (repeatDays.isEmpty() || candidate.get(Calendar.DAY_OF_WEEK) in repeatDays)
                ) {
                    return candidate.timeInMillis
                }
                candidate.add(Calendar.DAY_OF_YEAR, 1)
            }
            return candidate.timeInMillis
        }
    }
}

/**
 * 后台发起 Activity 时临时挂一个 1px 透明浮窗（方案 a）。
 * 系统对「有可见窗口」的应用放行后台启动（logcat：BAL_ALLOW_VISIBLE_WINDOW），小米「后台弹出界面」
 * 未授权时也认这一条。优先用无障碍浮层（免悬浮窗权限），没有无障碍时退回应用浮窗；两者都没有就不挂，
 * 照常发起。透明窗只负责「让它真设上」，不参与「是否成功」判断。
 */
internal object ClockBackgroundAnchor {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun <T> withVisibleWindow(context: Context, block: () -> T): T {
        val anchor = attach(context)
        try {
            return block()
        } finally {
            anchor?.let { detach(it) }
        }
    }

    private class Anchor(val windowManager: WindowManager, val view: View)

    private fun attach(context: Context): Anchor? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val service = AgentAccessibilityService.current()
        val overlayContext: Context
        val type: Int
        when {
            service != null -> {
                overlayContext = service
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            }
            Settings.canDrawOverlays(context) -> {
                overlayContext = context.applicationContext
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }
            else -> return null
        }
        val windowManager = overlayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return null
        val shown = CountDownLatch(1)
        val anchor = AtomicReference<Anchor?>(null)
        val abandoned = AtomicBoolean(false)
        mainHandler.post {
            if (abandoned.get()) return@post
            val view = View(overlayContext)
            val params = WindowManager.LayoutParams(
                1,
                1,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "MovoStartAnchor"
            }
            runCatching { windowManager.addView(view, params) }
                .onSuccess {
                    val attached = Anchor(windowManager, view)
                    anchor.set(attached)
                    if (abandoned.get()) {
                        detach(attached)
                        return@onSuccess
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        view.viewTreeObserver.registerFrameCommitCallback {
                            mainHandler.postDelayed({ shown.countDown() }, VISIBLE_SETTLE_MS)
                        }
                    } else {
                        // Android 10 以前没有帧提交回调：等下一次布局绘制后再计时。
                        view.post { mainHandler.postDelayed({ shown.countDown() }, VISIBLE_SETTLE_MS) }
                    }
                    view.invalidate()
                }
                .onFailure { shown.countDown() }
        }
        if (!shown.await(ATTACH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            abandoned.set(true)
            return anchor.getAndSet(null)
        }
        return anchor.get()
    }

    private fun detach(anchor: Anchor) {
        mainHandler.post { runCatching { anchor.windowManager.removeView(anchor.view) } }
    }

    private const val VISIBLE_SETTLE_MS = 80L
    private const val ATTACH_TIMEOUT_MS = 600L
}

/**
 * clock_read 真实后端。ColorOS 走 [AgentPrivateDatabaseTools] 读 alarms.db；
 * 其他机型在私有库读不到时，Root 下解析 dumpsys alarm 作兜底（见 [readFromDumpsys]），仍拿不到才返回 Unavailable。
 */
internal class RealClockReadBackend(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
) : ClockReadBackend {

    override fun read(type: ClockType?, enabledOnly: Boolean, limit: Int, env: ToolEnvironment): ClockReadResult {
        val pdb = AgentPrivateDatabaseTools(context, root)
        val items = JSONArray()
        var lastReason: String? = null

        if (type == null || type == ClockType.ALARM) {
            val args = JSONObject().put("enabled_only", enabledOnly).put("limit", limit)
            when (val r = parse(pdb.execute("list_alarms", args))) {
                is Parsed.Ok -> r.items.forEach { items.put(it.put("kind", "alarm")) }
                is Parsed.Err -> lastReason = r.reason
            }
        }
        if (type == null || type == ClockType.TIMER) {
            val args = JSONObject().put("limit", limit)
            when (val r = parse(pdb.execute("list_active_timers", args))) {
                is Parsed.Ok -> r.items.forEach { items.put(it.put("kind", "timer")) }
                is Parsed.Err -> lastReason = r.reason
            }
        }

        if (items.length() == 0 && lastReason != null) {
            // 非 ColorOS 机型：ColorOS 私有库读不到时，用 Root `dumpsys alarm` 解析（有时刻无用户标签）作兜底来源。
            // 解析走纯函数 [DumpsysAlarmParser]（见其单测）。拿不到任何时刻时仍如实把数据库不可用作为原因返回。
            val fallback = readFromDumpsys(type, limit)
            if (fallback.length() > 0) return ClockReadResult.Items(fallback)
            return ClockReadResult.Unavailable(lastReason)
        }
        return ClockReadResult.Items(items)
    }

    /** Root 下解析 `dumpsys alarm` 作为非 ColorOS 兜底：只给出触发时刻与类别，没有用户标签/开关态。 */
    private fun readFromDumpsys(type: ClockType?, limit: Int): JSONArray {
        val items = JSONArray()
        val dump = runCatching { root.execute("dumpsys alarm", maxOutputBytes = 512 * 1024) }
            .getOrNull()
            ?.takeIf { it.ok }
            ?.stdout
            ?: return items
        DumpsysAlarmParser.parse(dump)
            .asSequence()
            .filter { entry ->
                when (type) {
                    ClockType.ALARM -> entry.kind != ClockEntryKind.TIMER
                    ClockType.TIMER -> entry.kind == ClockEntryKind.TIMER
                    null -> true
                }
            }
            .take(limit.coerceAtLeast(0))
            .forEach { entry ->
                items.put(
                    JSONObject()
                        .put("kind", if (entry.kind == ClockEntryKind.TIMER) "timer" else "alarm")
                        .put("hour", entry.hour)
                        .put("minute", entry.minute)
                        .put("trigger_clock", "%02d:%02d".format(entry.hour, entry.minute))
                        .put("source", "dumpsys_alarm")
                        .put("label", JSONObject.NULL),
                )
            }
        return items
    }

    private sealed interface Parsed {
        data class Ok(val items: List<JSONObject>) : Parsed
        data class Err(val reason: String) : Parsed
    }

    private fun parse(result: AgentModelClient.ToolResult?): Parsed {
        val content = result?.content ?: return Parsed.Err("时钟数据暂时不可访问")
        val json = runCatching { JSONObject(content) }.getOrNull()
            ?: return Parsed.Err("时钟数据解析失败")
        if (!json.optBoolean("ok")) {
            return Parsed.Err(json.optString("message").ifEmpty { "时钟数据暂时不可访问" })
        }
        val array = json.optJSONArray("items") ?: JSONArray()
        return Parsed.Ok((0 until array.length()).mapNotNull { array.optJSONObject(it) })
    }
}

/** media_control 真实后端。 */
internal class RealMediaControlBackend(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
) : MediaControlBackend {

    override fun sessionState(env: ToolEnvironment): MediaSessionState {
        // 有通知使用权时走 MediaSessionManager 列会话（最准）。
        if (env.notificationAccess) {
            val viaManager = runCatching {
                val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val component = ComponentName(context, AgentNotificationHistoryService::class.java)
                val sessions = manager.getActiveSessions(component)
                if (sessions.isEmpty()) {
                    MediaSessionState.None
                } else {
                    val playing = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                        ?: sessions.first()
                    MediaSessionState.Active(playing.packageName)
                }
            }.getOrDefault(MediaSessionState.Unknown)
            if (viaManager != MediaSessionState.Unknown) return viaManager
        }
        // 无通知权但有 Root：用 `dumpsys media_session` 解析活动会话/包名（纯函数 DumpsysMediaSessionParser，见其单测）。
        if (env.rootAvailable) {
            return sessionStateViaRoot()
        }
        // 无通知权、无 Root：无法检测，如实返回 Unknown，不冒领。
        return MediaSessionState.Unknown
    }

    private fun sessionStateViaRoot(): MediaSessionState {
        val dump = runCatching { root.execute("dumpsys media_session", maxOutputBytes = 256 * 1024) }
            .getOrNull()
            ?.takeIf { it.ok }
            ?.stdout
            ?: return MediaSessionState.Unknown
        val sessions = DumpsysMediaSessionParser.parse(dump)
        // 命令成功执行：空列表即确实没有活动会话（不冒领）。
        val active = DumpsysMediaSessionParser.activeSession(sessions) ?: return MediaSessionState.None
        return MediaSessionState.Active(active.packageName)
    }

    override fun dispatch(action: MediaAction) {
        val keyCode = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.TOGGLE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}

/** volume_set 真实后端。 */
internal class RealVolumeSetBackend(
    private val context: Context,
) : VolumeSetBackend {

    override fun setAndReadBack(stream: VolumeStream, percent: Int): VolumeReadBack {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val androidStream = androidStream(stream)
        val max = audio.getStreamMaxVolume(androidStream).coerceAtLeast(1)
        val min = audio.getStreamMinVolume(androidStream).coerceIn(0, max)
        val level = (min + ((percent / 100.0) * (max - min)).roundToInt()).coerceIn(min, max)
        try {
            audio.setStreamVolume(androidStream, level, 0)
        } catch (security: SecurityException) {
            throw VolumeSetBackend.VolumeRejected(security.message ?: "SecurityException")
        }
        val actualLevel = audio.getStreamVolume(androidStream)
        val actualPercent = if (max == min) {
            100
        } else {
            ((actualLevel - min).toDouble() / (max - min) * 100).roundToInt().coerceIn(0, 100)
        }
        return VolumeReadBack(percent, actualPercent, actualLevel, max, min)
    }

    private fun androidStream(stream: VolumeStream): Int = when (stream) {
        VolumeStream.MUSIC -> AudioManager.STREAM_MUSIC
        VolumeStream.RING -> AudioManager.STREAM_RING
        VolumeStream.ALARM -> AudioManager.STREAM_ALARM
        VolumeStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        VolumeStream.CALL -> AudioManager.STREAM_VOICE_CALL
    }
}
