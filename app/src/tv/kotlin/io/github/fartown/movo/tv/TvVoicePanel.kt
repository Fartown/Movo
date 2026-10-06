package io.github.fartown.movo.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.TransitionDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
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
 * 电视语音浮窗 v4（2026-10-06 定稿，Figma「Movo TV」第一页）：光球演变成胶囊。
 *
 * 左下角（距左 64、距下 80，高过节目字幕区）平时只有一个球（56）；要显示内容时球的右边拉长成单行胶囊，
 * 球就是胶囊左端的半圆，永远不动。字从左往右长，最宽约 20 字，超出就整屏淡入换下一段，始终一行。
 * 你说话白色；Movo 回答胶囊带淡淡的光球色。光球不加任何外圈：状态靠光球自身动效 + 图标 + 文字。
 * 反问选项临时在胶囊上方展开小卡，选完收回；空闲最后 3 秒变暗；结束时字淡出 → 缩回球 → 淡出。
 */
internal object TvVoicePanel {
    private const val TEXT = 0xFF151515.toInt()
    private const val SECONDARY = 0xFF6B6964.toInt()
    private const val INK = 0xFF454CD2.toInt()
    private const val SELECTED = 0xFFEEF0FF.toInt()
    private const val GLASS = 0xE0FFFFFF.toInt()          // glass/surface-fallback 88%
    private const val HAIRLINE = 0x1A141414
    private const val DIM_WINDOW_MS = 3_000L
    private const val LINGER_MS = 2_500L
    private const val DONE_MS = 1_500L
    private const val YIELD_MS = 2_000L
    /** 胶囊一行最多约 20 字（文字区 520 / 26 字号）。 */
    internal const val LINE_CHARS = 20
    /** 普通话朗读约 5 字/秒；略慢一点，宁可字幕晚半拍也不抢在声音前面。 */
    private const val CHARS_PER_SECOND = 4.6
    private const val BALL = 56f
    private const val ORB = 44f
    private const val TEXT_MAX = 520f
    private const val MARGIN = 16f
    private val STANDARD = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var context: Context? = null
    private var app: AgentAppState? = null
    private var initialized = false
    private var observingApp = false
    private val applyPending = AtomicBoolean(false)
    private var owner: AgentAccessibilityService? = null
    private var root: FrameLayout? = null
    private var views: Views? = null
    private var screenshotSuppressed = false
    private var lastActive = false
    private var lingerUntil = 0L
    private var linger: Model? = null
    private var yieldUntil = 0L
    private var speakingSince = 0L
    private var lastChannel = VoiceChannel.Off
    /** 本次语音会话开始前的最后一条消息；只显示它之后的回答，新会话不带出上一个会话的旧回答。 */
    private var sessionBaseline: String? = null
    private var choiceFocus = 0
    private var choices: Choices? = null
    private val dimTask = Runnable { root?.animate()?.alpha(0.5f)?.setDuration(DIM_WINDOW_MS)?.start() }
    private val pageTask = Runnable { refresh() }

    internal enum class Indicator { None, BarsLive, BarsIdle, Speaker }
    internal enum class OrbMotion { Still, Working, Speaking }
    internal data class Choices(val header: String, val items: List<String>)
    /** [key] 相同的一段内容（同一次回答）宽度只增不减，换屏不会忽宽忽窄。 */
    private data class Model(
        val text: String,
        val textColor: Int = TEXT,
        val indicator: Indicator = Indicator.None,
        val movo: Boolean = false,
        val motion: OrbMotion = OrbMotion.Still,
        val choices: Choices? = null,
        val key: String = "",
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

    /** 你拿起遥控器：任务已停、对话已结束，胶囊显示「你来操作，我先停下」2 秒后收起，不出声。 */
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
        if (voice.channel == VoiceChannel.Speaking && lastChannel != VoiceChannel.Speaking) speakingSince = SystemClock.elapsedRealtime()
        lastChannel = voice.channel
        val service = AgentAccessibilityService.current()
        if (screenshotSuppressed || TvAppSurfaces.visible || TvConversationOverlay.expanded || service == null) { choices = null; removeNow(); return }
        val model = model(voice)
        if (model == null) { choices = null; collapse(); return }
        show(service, model)
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
        // 空闲超时直接收起；其他结束原因（开始播放等）停留一下，让结果看得到。
        if (notice == null || notice == VoiceConversationController.IDLE_END_MESSAGE) { lingerUntil = 0; return }
        val answer = latestAnswer()
        linger = Model(answer?.let { screensOf(it.content).lastOrNull() } ?: notice, key = "linger")
        val hold = if (answer != null) DONE_MS else LINGER_MS
        lingerUntil = SystemClock.elapsedRealtime() + hold
        main.postDelayed(::refresh, hold + 50)
    }

    private fun model(voice: VoiceSessionUiState): Model? {
        val session = app
        val messages = sessionMessages()
        val turn = messages.asReversed().takeWhile { it !is UserMessageUi }
        val answer = turn.firstOrNull { it is AgentMessageUi } as? AgentMessageUi
        val tools = turn.filterIsInstance<ToolActivityMessageUi>()
        val busy = session?.voiceRuntimeBusy == true
        if (!voice.active) {
            // 任务停止要一点时间，这期间仍显示让出提示，不闪回「正在…」。
            if (SystemClock.elapsedRealtime() < yieldUntil) return Model(TvBackHandler.YIELD_MESSAGE, key = "yield")
            if (busy) return working(tools)
            if (SystemClock.elapsedRealtime() < lingerUntil) return linger
            return null
        }
        val hearing = { text: String -> Model(transcriptPage(text), indicator = Indicator.BarsLive, key = "you") }
        return when (voice.channel) {
            VoiceChannel.Connecting -> Model("正在连接…", SECONDARY, key = "idle")
            VoiceChannel.Hearing -> hearing(voice.transcript.ifBlank { "…" })
            VoiceChannel.Thinking -> if (busy && tools.isNotEmpty()) working(tools) else Model("正在想…", motion = OrbMotion.Working, key = "work")
            VoiceChannel.Speaking -> answer?.let(::speaking) ?: Model("", indicator = Indicator.Speaker, movo = true, motion = OrbMotion.Speaking)
            VoiceChannel.Listening, VoiceChannel.Off -> when {
                voice.transcript.isNotBlank() -> hearing(voice.transcript)
                // 任务在跑时会话仍可继续听；环境声音会把通道拉回「听」，此时优先显示在做什么。
                busy -> working(tools)
                answer != null -> parseChoices(answer.content)?.let { card ->
                    Model(card.header, indicator = Indicator.Speaker, choices = card, key = "choice")
                } ?: screensOf(answer.content).let { screens ->
                    if (screens.size > 1) Model("说「看全文」看完整回答", SECONDARY, Indicator.BarsIdle, movo = true, key = answer.id)
                    else Model(screens.firstOrNull().orEmpty(), indicator = Indicator.BarsIdle, movo = true, key = answer.id)
                }
                else -> Model("我在，请说", indicator = Indicator.BarsIdle, key = "idle")
            }
        }
    }

    /** 跟着朗读整屏换：按已朗读时间估算到第几屏；念完由 Listening 状态接上「看全文」提示。 */
    private fun speaking(answer: AgentMessageUi): Model {
        val screens = screensOf(answer.content)
        val spokenChars = ((SystemClock.elapsedRealtime() - speakingSince) / 1000.0 * CHARS_PER_SECOND).toInt()
        var index = 0
        var consumed = 0
        while (index < screens.lastIndex && consumed + screens[index].length <= spokenChars) { consumed += screens[index].length; index++ }
        main.removeCallbacks(pageTask)
        if (index < screens.lastIndex) {
            val nextAt = ((consumed + screens[index].length) / CHARS_PER_SECOND * 1000).toLong()
            main.postDelayed(pageTask, (nextAt - (SystemClock.elapsedRealtime() - speakingSince)).coerceAtLeast(50))
        }
        return Model(screens.getOrElse(index) { "" }, indicator = Indicator.Speaker, movo = true, motion = OrbMotion.Speaking, key = answer.id)
    }

    private fun working(tools: List<ToolActivityMessageUi>): Model {
        val latest = tools.firstOrNull()
        val label = latest?.toolName?.let { name -> toolDisplayNameResource(name)?.let { context?.getString(it) } ?: name }
        val step = tools.size.takeIf { it > 0 }?.let { "第 $it 步" }
        return Model(listOfNotNull(label?.let { "正在$it" } ?: "正在处理", step).joinToString(" · "), motion = OrbMotion.Working, key = "work")
    }

    private fun latestAnswer(): AgentMessageUi? = sessionMessages().asReversed()
        .takeWhile { it !is UserMessageUi }.firstOrNull { it is AgentMessageUi } as? AgentMessageUi

    private fun sessionMessages(): List<io.github.fartown.movo.ui.model.AgentChatMessageUi> {
        val all = app?.homeState?.messages.orEmpty()
        val start = sessionBaseline?.let { id -> all.indexOfLast { it.id == id } } ?: -1
        return if (start >= 0) all.drop(start + 1) else if (sessionBaseline == null) all else emptyList()
    }

    /**
     * 实时字幕：一行放得下就全显示；放不下就从第 [LINE_CHARS] 字起每 19 字一屏，前面加「…」——
     * 满宽后整屏换下一段（轮播），不换行、不左右滚动。
     */
    internal fun transcriptPage(text: String): String {
        val t = text.trim()
        if (t.length <= LINE_CHARS) return t
        val step = LINE_CHARS - 1
        val start = LINE_CHARS + (t.length - LINE_CHARS - 1) / step * step
        return "…" + t.substring(start)
    }

    /** 一行要点：取第一句有内容的文字，去掉 Markdown 记号。 */
    internal fun summaryOf(content: String): String = lines(content).firstOrNull() ?: "已回答"

    internal fun longHintOf(content: String): String? {
        val all = lines(content)
        return if (all.size > 1 || (all.firstOrNull()?.length ?: 0) > 40) "说「看全文」" else null
    }

    /**
     * 把回答切成单行字幕屏：每屏不超过 [perScreen] 字，优先在句号、问号、分号处断，句子太长再在逗号处断，
     * 仍放不下才硬切；相邻短句合进同一屏。
     */
    internal fun screensOf(content: String, perScreen: Int = LINE_CHARS): List<String> {
        val sentences = lines(content).flatMap { line ->
            Regex("""[^。！？；!?;]+[。！？；!?;]*""").findAll(line).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        }.flatMap { sentence ->
            if (sentence.length <= perScreen) listOf(sentence)
            else Regex("""[^，,、]+[，,、]*""").findAll(sentence).map { it.value }.toList()
                .flatMap { part -> if (part.length <= perScreen) listOf(part) else part.chunked(perScreen) }
        }
        val screens = mutableListOf<String>()
        val current = StringBuilder()
        for (piece in sentences) {
            if (current.isNotEmpty() && current.length + piece.length > perScreen) { screens += current.toString(); current.clear() }
            current.append(piece)
        }
        if (current.isNotEmpty()) screens += current.toString()
        return screens
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

    private class Views(
        val column: LinearLayout,
        val capsule: FrameLayout,
        val orb: TvCapsuleOrbView,
        val slot: FrameLayout,
        val text: TextView,
        val background: TransitionDrawable,
        var card: LinearLayout? = null,
        var indicator: Indicator? = null,
        var tinted: Boolean = false,
        var width: Int = 0,
        var widthAnimator: ValueAnimator? = null,
        var growKey: String = "",
        var growWidth: Int = 0,
        var collapsing: Boolean = false,
    )

    private fun dp(context: Context, v: Float) = (v * context.resources.displayMetrics.density).toInt()

    private fun show(service: AgentAccessibilityService, model: Model) {
        if (owner !== service) removeNow()
        var container = root
        val fresh = container == null
        if (container == null) {
            container = build(service) ?: return
        }
        val v = views ?: return
        if (v.collapsing) { v.collapsing = false; container.animate().cancel(); container.alpha = 1f }
        if (fresh) container.alpha = 1f
        bind(service, v, model)
        scheduleDim(VoiceConversationController.idleEndsAt.value)
    }

    /**
     * 窗口固定大小、透明、不可点，锚在左下角；胶囊拉长 / 缩回只在窗口里重排，不每帧改窗口尺寸。
     * 胶囊在最底下，反问小卡在它上方临时出现。
     */
    private fun build(service: AgentAccessibilityService): FrameLayout? {
        fun dp(v: Float) = dp(service, v)
        val container = FrameLayout(service).apply { clipChildren = false; clipToPadding = false; setPadding(dp(MARGIN), dp(MARGIN), dp(MARGIN), dp(MARGIN)) }
        val column = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM or Gravity.START; clipChildren = false }
        container.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val background = TransitionDrawable(arrayOf(glass(dp(BALL / 2).toFloat(), dp(1f), tinted = false), glass(dp(BALL / 2).toFloat(), dp(1f), tinted = true)))
            .apply { isCrossFadeEnabled = true }
        val capsule = FrameLayout(service).apply {
            this.background = background
            elevation = dp(6f).toFloat()
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6f), 0, 0, 0) }
        val orb = TvCapsuleOrbView(service)
        row.addView(orb, LinearLayout.LayoutParams(dp(ORB), dp(ORB)))
        val slot = FrameLayout(service).apply { visibility = View.GONE }
        row.addView(slot, LinearLayout.LayoutParams(dp(40f), dp(38f)).apply { marginStart = dp(14f) })
        val text = TextView(service).apply {
            textSize = 26f; setTextColor(TEXT); includeFontPadding = false; maxLines = 1; isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxWidth = dp(TEXT_MAX); alpha = 0f
        }
        row.addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(14f) })
        // 内容层按最大宽度排版、由胶囊轮廓裁切：拉长过程中文字不被挤压、不闪省略号。
        capsule.addView(row, FrameLayout.LayoutParams(dp(6f + ORB + 14f + 40f + 14f + TEXT_MAX + 26f), ViewGroup.LayoutParams.MATCH_PARENT))
        val start = dp(BALL)
        column.addView(capsule, LinearLayout.LayoutParams(start, dp(BALL)))
        val windowWidth = dp(6f + ORB + 14f + 40f + 14f + TEXT_MAX + 26f + 2 * MARGIN)
        val windowHeight = dp(BALL + 12f + 420f + 2 * MARGIN)
        val params = WindowManager.LayoutParams(
            windowWidth, windowHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.START; x = dp(64f) - dp(MARGIN); y = dp(80f) - dp(MARGIN); setTitle("Movo 语音胶囊") }
        try {
            service.getSystemService(WindowManager::class.java).addView(container, params)
        } catch (_: Exception) {
            MemoryDiagnostics.record("tv.capsule", "window.unavailable")
            return null
        }
        root = container; owner = service
        views = Views(column, capsule, orb, slot, text, background, width = start)
        // 出现：先是一个球淡入。
        capsule.alpha = 0f; capsule.animate().alpha(1f).setDuration(150).start()
        return container
    }

    private fun bind(context: Context, v: Views, model: Model) {
        fun dp(x: Float) = dp(context, x)
        v.orb.motion = model.motion
        if (v.tinted != model.movo) {
            if (model.movo) v.background.startTransition(200) else v.background.reverseTransition(200)
            v.tinted = model.movo
        }
        setIndicator(context, v, if (model.text.isEmpty()) Indicator.None else model.indicator)
        val expanding = v.text.text.isEmpty() && model.text.isNotEmpty()
        val pageChange = !expanding && model.text.isNotEmpty() && v.text.text.toString() != model.text &&
            !model.text.startsWith(v.text.text.toString().removeSuffix("…"))
        v.text.setTextColor(model.textColor)
        v.text.text = model.text
        when {
            model.text.isEmpty() -> v.text.animate().alpha(0f).setDuration(120).start()
            expanding -> { v.text.alpha = 0f; v.text.animate().alpha(1f).setStartDelay(135).setDuration(165).start() }
            pageChange -> { v.text.alpha = 0f; v.text.animate().alpha(1f).setStartDelay(0).setDuration(180).start() }
            else -> if (v.text.alpha < 1f && v.text.animate() != null) v.text.animate().alpha(1f).setStartDelay(0).setDuration(120).start()
        }
        // 目标宽度：球 + 图标槽 + 文字；同一段内容（同一次回答）只增不减。
        var target = if (model.text.isEmpty()) dp(BALL) else {
            val textWidth = minOf(v.text.paint.measureText(model.text), dp(TEXT_MAX).toFloat()).toInt() + 2
            dp(6f + ORB + 14f) + (if (v.slot.visibility == View.VISIBLE) dp(40f + 14f) else 0) + textWidth + dp(26f)
        }
        if (model.key.isNotEmpty() && model.key == v.growKey) target = maxOf(target, v.growWidth)
        v.growKey = model.key; v.growWidth = target
        animateWidth(v, target, if (expanding) 300 else 200)
        bindChoices(context, v, model.choices)
    }

    private fun animateWidth(v: Views, target: Int, duration: Long) {
        if (target == v.width && v.widthAnimator == null) return
        v.widthAnimator?.cancel()
        val from = (v.capsule.layoutParams as LinearLayout.LayoutParams).width
        v.width = target
        v.widthAnimator = ValueAnimator.ofInt(from, target).apply {
            this.duration = duration; interpolator = STANDARD
            addUpdateListener { anim -> v.capsule.layoutParams = (v.capsule.layoutParams as LinearLayout.LayoutParams).apply { width = anim.animatedValue as Int } }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { if (v.widthAnimator === animation) v.widthAnimator = null }
            })
            start()
        }
    }

    private fun bindChoices(context: Context, v: Views, card: Choices?) {
        fun dp(x: Float) = dp(context, x)
        if (card != choices) choiceFocus = 0
        choices = card
        if (card == null) { v.card?.let { v.column.removeView(it) }; v.card = null; return }
        val list = v.card ?: LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            background = GradientDrawable().apply { setColor(GLASS); cornerRadius = dp(24f).toFloat(); setStroke(dp(1f).coerceAtLeast(1), HAIRLINE) }
            elevation = dp(6f).toFloat()
        }.also { v.column.addView(it, 0, LinearLayout.LayoutParams(dp(560f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12f) }); v.card = it }
        list.removeAllViews()
        card.items.forEachIndexed { index, item ->
            val focused = index == choiceFocus
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(if (focused) SELECTED else 0) }
                addView(TextView(context).apply {
                    text = "${index + 1}"; textSize = 18f; gravity = Gravity.CENTER
                    setTextColor(if (focused) 0xFFFFFFFF.toInt() else TEXT)
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (focused) INK else 0x0F151515) }
                }, LinearLayout.LayoutParams(dp(32f), dp(32f)))
                addView(TextView(context).apply {
                    text = item; textSize = 24f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
                    setTextColor(if (focused) INK else TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(14f) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (index > 0) topMargin = dp(4f) })
        }
        list.addView(TextView(context).apply {
            text = "说「第二个」，或用方向键选、确认"; textSize = 18f; setTextColor(SECONDARY); includeFontPadding = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f); marginStart = dp(12f) })
    }

    private fun setIndicator(context: Context, v: Views, kind: Indicator) {
        if (v.indicator == kind) return
        v.indicator = kind
        v.slot.removeAllViews()
        val view: View? = when (kind) {
            Indicator.None -> null
            Indicator.BarsLive -> TvBarsView(context, listOf(9, 18, 24, 15, 21, 12))
            Indicator.BarsIdle -> TvBarsView(context, List(6) { 6 })
            Indicator.Speaker -> TvSpeakerView(context)
        }
        v.slot.visibility = if (view == null) View.GONE else View.VISIBLE
        view?.let { v.slot.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START)) }
    }

    /** Material/Glass 的降级（电视不支持跨窗口模糊）：白 88% + 发丝描边；Movo 回答时叠一层从球那头带出的光球色。 */
    private fun glass(radius: Float, hairline: Int, tinted: Boolean): Drawable {
        val base = GradientDrawable().apply { setColor(GLASS); cornerRadius = radius; setStroke(hairline.coerceAtLeast(1), HAIRLINE) }
        if (!tinted) return base
        val tint = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x73B39BFF, 0x61FF9EC2, 0x52FFC2A6, 0x4D8CC6FF, 0x47BFEFE3)).apply { cornerRadius = radius }
        return LayerDrawable(arrayOf(base, tint))
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

    /** 收起：字淡出 → 缩回球 → 淡出并移除窗口。 */
    private fun collapse() {
        main.removeCallbacks(dimTask)
        main.removeCallbacks(pageTask)
        val view = root ?: return
        val v = views
        if (v == null) { removeNow(); return }
        if (v.collapsing) return
        v.collapsing = true
        v.card?.let { v.column.removeView(it) }; v.card = null
        v.text.animate().alpha(0f).setStartDelay(0).setDuration(120).start()
        setIndicator(view.context, v, Indicator.None)
        v.growKey = ""
        main.postDelayed({
            if (root !== view || !v.collapsing) return@postDelayed
            animateWidth(v, dp(view.context, BALL), 200)
            view.animate().alpha(0f).setStartDelay(200).setDuration(150).withEndAction {
                if (root === view && v.collapsing) removeNow()
            }.start()
        }, 120)
        v.text.text = ""
    }

    private fun removeNow() {
        val view = root ?: return
        views?.widthAnimator?.cancel()
        runCatching { owner?.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
        root = null; owner = null; views = null
    }
}

/**
 * 胶囊左端的光球（44，与手机首页 logo 光球同款）：不加任何外圈。
 * 在想 / 在做：渐变转快（1.5 秒一圈）；回答：随朗读轻微起伏（1 ↔ 1.04）；其他时候静止。
 */
internal class TvCapsuleOrbView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private var rotation = 0f
    private var scale = 1f
    private var animator: ValueAnimator? = null
    var motion: TvVoicePanel.OrbMotion = TvVoicePanel.OrbMotion.Still
        set(value) {
            if (field == value) return
            field = value
            restart()
        }

    override fun onDraw(canvas: Canvas) {
        TvOrbPainter.draw(canvas, width / 2f, height / 2f, minOf(width, height) * scale, TvOrbRing.None, 0f, 0f, density, rotation)
    }

    private fun restart() {
        animator?.cancel(); animator = null; scale = 1f
        when (motion) {
            TvVoicePanel.OrbMotion.Still -> Unit
            TvVoicePanel.OrbMotion.Working -> animator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 1500; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { rotation = it.animatedValue as Float; invalidate() }; start()
            }
            TvVoicePanel.OrbMotion.Speaking -> animator = ValueAnimator.ofFloat(1f, 1.04f).apply {
                duration = 600; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
                addUpdateListener { scale = it.animatedValue as Float; invalidate() }; start()
            }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() { animator?.cancel(); animator = null; super.onDetachedFromWindow() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); restart() }
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
