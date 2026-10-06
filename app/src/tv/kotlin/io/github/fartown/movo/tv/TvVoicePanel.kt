package io.github.fartown.movo.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import io.github.fartown.movo.agent.overlay.toolDisplayNameResource
import io.github.fartown.movo.agent.voice.conversation.VoiceConversationController
import io.github.fartown.movo.agent.voice.session.VoiceChannel
import io.github.fartown.movo.agent.voice.session.VoiceSessionManager
import io.github.fartown.movo.agent.voice.session.VoiceSessionUiState
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import io.github.fartown.movo.ui.app.AgentAppSession
import io.github.fartown.movo.ui.app.AgentAppState
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.UserMessageUi
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 电视语音胶囊（Figma「Movo TV」候选 v2：右下角、光球 + 一行字；已选 1-a 变暗、2-a 右下）。
 *
 * 平时只是一枚不抢焦点、不可点的无障碍浮层：节目和遥控器照常。状态靠光球外圈、音量条、喇叭表达；
 * 回答带编号选项时临时展开成反问小卡（说「第二个」，或方向键 + 确认）；空闲最后 3 秒变暗，结束时淡出。
 */
internal object TvVoicePanel {
    private const val TEXT = 0xFF151515.toInt()
    private const val SECONDARY = 0xFF6B6964.toInt()
    private const val TERTIARY = 0xFF8E8C86.toInt()
    private const val INDIGO = 0xFF4F56E3.toInt()
    private const val SELECTED = 0xFFEEF0FF.toInt()
    private const val DIM_WINDOW_MS = 3_000L
    private const val LINGER_MS = 2_500L
    private const val YIELD_MS = 2_000L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var context: Context? = null
    private var app: AgentAppState? = null
    private var initialized = false
    private var observingApp = false
    private val applyPending = AtomicBoolean(false)
    private var owner: AgentAccessibilityService? = null
    private var root: FrameLayout? = null
    private var screenshotSuppressed = false
    private var lastActive = false
    private var lingerUntil = 0L
    private var lingerText: String? = null
    private var yieldUntil = 0L
    /** 本次语音会话开始前的最后一条消息；只显示它之后的回答，新会话不带出上一个会话的旧回答。 */
    private var sessionBaseline: String? = null
    private var choiceFocus = 0
    private var choices: Choices? = null
    private val dimTask = Runnable { root?.animate()?.alpha(0.5f)?.setDuration(DIM_WINDOW_MS)?.start() }

    private enum class Icon { None, BarsOn, BarsOff, Speaker }
    internal data class Choices(val header: String, val items: List<String>)
    private data class Model(
        val ring: TvOrbRing,
        val icon: Icon,
        val text: String,
        val color: Int,
        val fromStart: Boolean = false,
        val sub: String? = null,
        val hint: String? = null,
        val choices: Choices? = null,
    )

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        this.context = context.applicationContext
        AgentAccessibilityService.addInstanceListener(::refresh)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch { VoiceSessionManager.state.collect { refresh() } }
        scope.launch { VoiceConversationController.idleEndsAt.collect { scheduleDim(it) } }
    }

    /** 你拿起遥控器：任务已停、对话已结束，胶囊显示「你来操作，我先停下」2 秒后淡出，不出声（设计稿 D2）。 */
    fun showYield() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(::showYield); return }
        choices = null
        lingerUntil = 0
        yieldUntil = SystemClock.elapsedRealtime() + YIELD_MS
        main.postDelayed(::refresh, YIELD_MS + 50)
        refresh()
    }

    fun suppressForScreenshot(suppress: Boolean) {
        screenshotSuppressed = suppress
        refresh()
    }

    /** 反问小卡出现时才接管方向键、数字键和确认键；其余时间所有按键都交给前台应用。 */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val card = choices ?: return false
        if (root == null) return false
        val code = event.keyCode
        val number = if (code in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) code - KeyEvent.KEYCODE_1 else -1
        val handled = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || number in card.items.indices
        if (!handled || event.action != KeyEvent.ACTION_UP) return handled
        when {
            code == KeyEvent.KEYCODE_DPAD_UP -> { choiceFocus = (choiceFocus - 1).coerceAtLeast(0); refresh() }
            code == KeyEvent.KEYCODE_DPAD_DOWN -> { choiceFocus = (choiceFocus + 1).coerceAtMost(card.items.lastIndex); refresh() }
            number >= 0 -> choose(number)
            else -> choose(choiceFocus)
        }
        return true
    }

    private fun choose(index: Int) {
        val session = app ?: return
        MemoryDiagnostics.record("tv.capsule", "choice.remote", fields = mapOf("index" to index + 1))
        choices = null
        VoiceSessionManager.end()
        session.sendCurrentMessage("第${index + 1}个")
    }

    fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(::refresh); return }
        val voice = VoiceSessionManager.state.value
        if (voice.active) { observeApp(); yieldUntil = 0 }
        if (voice.active && !lastActive) sessionBaseline = app?.homeState?.messages?.lastOrNull()?.id
        if (lastActive && !voice.active) startLinger(voice)
        lastActive = voice.active
        val service = AgentAccessibilityService.current()
        val model = if (screenshotSuppressed || TvAppSurfaces.visible || TvConversationOverlay.expanded || service == null) null
            else model(voice)
        if (model == null) { choices = null; hide(); return }
        show(service!!, model)
    }

    private fun observeApp() {
        if (observingApp) return
        val ctx = context ?: return
        observingApp = true
        val session = AgentAppSession.get(ctx)
        app = session
        // 浮层没有 Compose 帧时钟：发布快照写入，snapshotFlow 才能看到消息和运行状态的变化。
        Snapshot.registerGlobalWriteObserver {
            if (applyPending.compareAndSet(false, true)) main.post { applyPending.set(false); Snapshot.sendApplyNotifications() }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            snapshotFlow { session.homeState.messages to session.voiceRuntimeBusy }.collect { refresh() }
        }
    }

    private fun startLinger(voice: VoiceSessionUiState) {
        val notice = voice.notice
        if (SystemClock.elapsedRealtime() < yieldUntil) { lingerUntil = 0; return }
        // 空闲超时直接淡出（不再弹「需要时再叫我」）；其他结束原因停留一下，让结果看得到。
        if (notice == null || notice == VoiceConversationController.IDLE_END_MESSAGE) { lingerUntil = 0; return }
        lingerText = latestAnswer()?.let(::summary) ?: notice
        lingerUntil = SystemClock.elapsedRealtime() + LINGER_MS
        main.postDelayed(::refresh, LINGER_MS + 50)
    }

    private fun model(voice: VoiceSessionUiState): Model? {
        val session = app
        val messages = sessionMessages()
        val turn = messages.asReversed().takeWhile { it !is UserMessageUi }
        val lastUser = messages.lastOrNull { it is UserMessageUi } as? UserMessageUi
        val answer = turn.firstOrNull { it is AgentMessageUi } as? AgentMessageUi
        val tools = turn.filterIsInstance<ToolActivityMessageUi>()
        val busy = session?.voiceRuntimeBusy == true
        if (!voice.active) {
            // 任务停止要一点时间，这期间仍显示让出提示，不闪回「正在…」。
            if (SystemClock.elapsedRealtime() < yieldUntil) return Model(TvOrbRing.None, Icon.None, TvBackHandler.YIELD_MESSAGE, TEXT)
            if (busy) return working(tools, lastUser)
            if (SystemClock.elapsedRealtime() < lingerUntil) return lingerText?.let { Model(TvOrbRing.Done, Icon.None, it, TEXT) }
            return null
        }
        return when (voice.channel) {
            VoiceChannel.Connecting -> Model(TvOrbRing.Listening, Icon.None, "正在连接…", TERTIARY)
            VoiceChannel.Hearing -> Model(TvOrbRing.Listening, Icon.BarsOn, voice.transcript.ifBlank { "正在听…" }, TEXT, fromStart = true)
            VoiceChannel.Thinking -> if (busy && tools.isNotEmpty()) working(tools, lastUser)
                else Model(TvOrbRing.Working, Icon.None, voice.transcript.ifBlank { lastUser?.content ?: "好的" }, SECONDARY, fromStart = true)
            VoiceChannel.Speaking -> answer?.let { Model(TvOrbRing.Speaking, Icon.Speaker, summary(it), TEXT, hint = longHint(it)) }
                ?: Model(TvOrbRing.Speaking, Icon.Speaker, "正在回答", SECONDARY)
            VoiceChannel.Listening, VoiceChannel.Off -> when {
                voice.transcript.isNotBlank() -> Model(TvOrbRing.Listening, Icon.BarsOn, voice.transcript, TEXT, fromStart = true)
                // 任务在跑时会话仍可继续听；环境声音会把通道拉回「听」，此时优先显示在做什么。
                busy -> working(tools, lastUser)
                answer != null -> {
                    val card = parseChoices(answer.content)
                    Model(TvOrbRing.Listening, Icon.BarsOff, summary(answer), SECONDARY, hint = if (card == null) longHint(answer) else null, choices = card)
                }
                else -> Model(TvOrbRing.Listening, Icon.None, "我在，请说", TERTIARY)
            }
        }
    }

    private fun working(tools: List<ToolActivityMessageUi>, lastUser: UserMessageUi?): Model {
        val latest = tools.firstOrNull()
        val label = latest?.toolName?.let { name -> toolDisplayNameResource(name)?.let { context?.getString(it) } ?: name }
        val step = tools.size.takeIf { it > 0 }?.let { "第 $it 步" }
        return Model(TvOrbRing.Working, Icon.None, label?.let { "正在$it" } ?: (lastUser?.content ?: "正在处理"), if (label != null) TEXT else SECONDARY, sub = step)
    }

    private fun latestAnswer(): AgentMessageUi? = sessionMessages().asReversed()
        .takeWhile { it !is UserMessageUi }.firstOrNull { it is AgentMessageUi } as? AgentMessageUi

    private fun sessionMessages(): List<io.github.fartown.movo.ui.model.AgentChatMessageUi> {
        val all = app?.homeState?.messages.orEmpty()
        val start = sessionBaseline?.let { id -> all.indexOfLast { it.id == id } } ?: -1
        return if (start >= 0) all.drop(start + 1) else if (sessionBaseline == null) all else emptyList()
    }

    /** 一行要点：取第一句有内容的文字，去掉 Markdown 记号。 */
    private fun summary(answer: AgentMessageUi): String = summaryOf(answer.content)

    internal fun summaryOf(content: String): String = lines(content).firstOrNull() ?: "已回答"

    private fun longHint(answer: AgentMessageUi): String? = longHintOf(answer.content)

    internal fun longHintOf(content: String): String? {
        val all = lines(content)
        return if (all.size > 1 || (all.firstOrNull()?.length ?: 0) > 40) "说「看全文」" else null
    }

    private fun lines(content: String): List<String> = content.lines()
        .map { it.trim().trimStart('#', '>', '-', '*', ' ').replace("**", "").replace("`", "").trim() }
        .filter { it.isNotBlank() }

    private val numbered = Regex("""^\s*(\d{1,2})\s*[.、．)）]\s*(.+)$""")

    internal fun parseChoices(content: String): Choices? {
        val raw = content.lines()
        val items = raw.mapNotNull { numbered.find(it)?.groupValues?.get(2)?.replace("**", "")?.trim() }
        if (items.size < 2 || items.size > 6) return null
        val header = raw.map { it.trim() }.firstOrNull { it.isNotBlank() && numbered.find(it) == null } ?: "要哪一个？"
        return Choices(header.replace("**", ""), items)
    }

    // ---------------- 窗口 ----------------

    private fun show(service: AgentAccessibilityService, model: Model) {
        if (owner !== service) removeNow()
        val density = service.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        var container = root
        val fresh = container == null
        if (container == null) {
            container = FrameLayout(service)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = dp(64); y = dp(36); setTitle("Movo 语音胶囊") }
            try {
                service.getSystemService(WindowManager::class.java).addView(container, params)
            } catch (_: Exception) {
                MemoryDiagnostics.record("tv.capsule", "window.unavailable")
                return
            }
            root = container; owner = service
        }
        val card = model.choices
        if (card != choices) choiceFocus = 0
        choices = card
        container.removeAllViews()
        container.addView(if (card == null) capsule(service, model, ::dp) else choiceCard(service, card, ::dp))
        if (fresh) {
            container.alpha = 0f; container.translationY = dp(16).toFloat()
            container.animate().alpha(1f).translationY(0f).setDuration(200).start()
        }
        scheduleDim(VoiceConversationController.idleEndsAt.value)
    }

    private fun capsule(context: Context, model: Model, dp: (Int) -> Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(24), dp(8))
        background = surface(dp, radius = 999)
        elevation = dp(8).toFloat()
        addView(TvOrbView(context, 36f).apply { ring = model.ring })
        icon(context, model.icon, dp)?.let { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(14) }) }
        addView(TextView(context).apply {
            text = model.text; textSize = 24f; setTextColor(model.color); maxLines = 1
            ellipsize = if (model.fromStart) TextUtils.TruncateAt.START else TextUtils.TruncateAt.END
            maxWidth = dp(560)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(14) })
        model.sub?.let { addView(label(context, it, TERTIARY), marginStart(dp(14))) }
        model.hint?.let { addView(label(context, it, SECONDARY), marginStart(dp(16))) }
    }

    private fun choiceCard(context: Context, card: Choices, dp: (Int) -> Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(20), dp(14))
        background = surface(dp, radius = 24)
        elevation = dp(8).toFloat()
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TvOrbView(context, 28f).apply { ring = TvOrbRing.Listening })
            addView(TextView(context).apply { text = card.header; textSize = 24f; setTextColor(TEXT); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(420) }, marginStart(dp(12)))
        })
        card.items.forEachIndexed { index, item ->
            val focused = index == choiceFocus
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(16), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    if (focused) { setColor(SELECTED); setStroke(dp(2), INDIGO) } else setColor(0)
                }
                addView(TextView(context).apply {
                    text = "${index + 1}"; textSize = 16f; gravity = Gravity.CENTER
                    setTextColor(if (focused) 0xFFFFFFFF.toInt() else INDIGO)
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (focused) INDIGO else SELECTED) }
                }, LinearLayout.LayoutParams(dp(28), dp(28)))
                addView(TextView(context).apply { text = item; textSize = 24f; setTextColor(TEXT); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(400) }, marginStart(dp(12)))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        addView(label(context, "说「第二个」，或用方向键选", TERTIARY), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun surface(dp: (Int) -> Int, radius: Int) = GradientDrawable().apply {
        setColor(0xEBFFFFFF.toInt()); cornerRadius = dp(radius).toFloat(); setStroke(dp(1).coerceAtLeast(1), 0x14141414)
    }

    private fun label(context: Context, text: String, color: Int) = TextView(context).apply { this.text = text; textSize = 20f; setTextColor(color); maxLines = 1 }

    private fun marginStart(px: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px }

    private fun icon(context: Context, icon: Icon, dp: (Int) -> Int): View? = when (icon) {
        Icon.None -> null
        Icon.BarsOn -> TvBarsView(context, listOf(9, 18, 24, 15, 21, 12))
        Icon.BarsOff -> TvBarsView(context, List(6) { 6 })
        Icon.Speaker -> TvSpeakerView(context)
    }

    private fun scheduleDim(endsAt: Long) {
        main.removeCallbacks(dimTask)
        val view = root ?: return
        if (endsAt == 0L || !VoiceSessionManager.state.value.active) {
            if (view.alpha < 1f) view.animate().alpha(1f).setDuration(160).start()
            return
        }
        val wait = endsAt - DIM_WINDOW_MS - SystemClock.elapsedRealtime()
        if (wait <= 0) dimTask.run() else main.postDelayed(dimTask, wait)
    }

    private fun hide() {
        main.removeCallbacks(dimTask)
        val view = root ?: return
        root = null
        val service = owner
        owner = null
        view.animate().alpha(0f).setDuration(150).withEndAction {
            runCatching { service?.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
        }.start()
    }

    private fun removeNow() {
        val view = root ?: return
        runCatching { owner?.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
        root = null; owner = null
    }
}

/** 音量条：6 条，宽 4、间距 3、最高 24（手机规范 9.6 ×1.5）；没有真实电平时静止，不做假动画。 */
private class TvBarsView(context: Context, private val heightsDp: List<Int>) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4F56E3.toInt() }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(((6 * 4 + 5 * 3) * density).toInt(), (24 * density).toInt())
    override fun onDraw(canvas: Canvas) {
        heightsDp.forEachIndexed { i, hDp ->
            val x = i * 7 * density; val h = hDp * density; val y = (height - h) / 2f
            canvas.drawRoundRect(RectF(x, y, x + 4 * density, y + h), 2 * density, 2 * density, paint)
        }
    }
}

/** 播报图标（喇叭 + 两道声波）。 */
private class TvSpeakerView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4F56E3.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4F56E3.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((24 * density).toInt(), (24 * density).toInt())
    override fun onDraw(canvas: Canvas) {
        val d = density
        canvas.drawPath(Path().apply { moveTo(3 * d, 9 * d); lineTo(7 * d, 9 * d); lineTo(12 * d, 5 * d); lineTo(12 * d, 19 * d); lineTo(7 * d, 15 * d); lineTo(3 * d, 15 * d); close() }, fill)
        stroke.strokeWidth = 2 * d
        canvas.drawPath(Path().apply { moveTo(15.5f * d, 9 * d); cubicTo(16.8f * d, 10.4f * d, 16.8f * d, 13.6f * d, 15.5f * d, 15 * d) }, stroke)
        canvas.drawPath(Path().apply { moveTo(18 * d, 6.5f * d); cubicTo(21 * d, 9.5f * d, 21 * d, 14.5f * d, 18 * d, 17.5f * d) }, stroke)
    }
}
