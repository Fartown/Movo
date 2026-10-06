package io.github.fartown.movo.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
 * 电视语音浮窗 v3（2026-10-06 定稿：1-a 跟着朗读翻屏、2-a 先小胶囊、3-a + C-2 Movo 回答卡片淡彩底）。
 *
 * 照手机「悬浮球 + 展开卡」：球固定在右下角（距右 64、距下 80，高过节目字幕区），一轮里不动；
 * 左边没开口时是小胶囊，开口后长成定宽定高的卡片（宽 464、正文两行每行 16 字），之后只换文字不变大小。
 * 不抢焦点、不可点；反问选项时临时变高，选完收回；空闲最后 3 秒变暗，结束淡出。
 */
internal object TvVoicePanel {
    private const val TEXT = 0xFF151515.toInt()
    private const val SECONDARY = 0xFF6B6964.toInt()
    private const val INDIGO = 0xFF4F56E3.toInt()
    private const val INK = 0xFF454CD2.toInt()
    private const val SELECTED = 0xFFEEF0FF.toInt()
    private const val GLASS = 0xE0FFFFFF.toInt()          // glass/surface-fallback 88%
    private const val HAIRLINE = 0x1A141414
    private const val DIM_WINDOW_MS = 3_000L
    private const val LINGER_MS = 2_500L
    private const val DONE_MS = 1_500L
    private const val YIELD_MS = 2_000L
    private const val CARD_DP = 464
    /** 每屏最多两行、每行 16 字（中文字幕规范）。 */
    internal const val SCREEN_CHARS = 32
    /** 普通话朗读约 5 字/秒；略慢一点，宁可字幕晚半拍也不抢在声音前面。 */
    private const val CHARS_PER_SECOND = 4.6

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

    internal enum class Indicator { None, BarsLive, BarsIdle, Speaker, MiniOrb, Check }
    internal data class Choices(val header: String, val items: List<String>)
    /** [compact] 非空时是小胶囊，否则是卡片；[movo] 为 Movo 在说的回答（卡片淡彩底）。 */
    private data class Model(
        val ring: TvOrbRing,
        val compact: String? = null,
        val compactColor: Int = TEXT,
        val indicator: Indicator = Indicator.None,
        val status: String = "",
        val body: String = "",
        val movo: Boolean = false,
        val context: String? = null,
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

    /** 你拿起遥控器：任务已停、对话已结束，小胶囊显示「你来操作，我先停下」2 秒后淡出，不出声。 */
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
        // 空闲超时直接淡出；其他结束原因（开始播放等）停留一下，让结果看得到。
        if (notice == null || notice == VoiceConversationController.IDLE_END_MESSAGE) { lingerUntil = 0; return }
        val text = latestAnswer()?.let { screensOf(it.content).lastOrNull() } ?: notice
        linger = Model(TvOrbRing.Done, indicator = Indicator.Check, status = "Movo", body = text, context = "对话已结束，需要时再叫我")
        val hold = if (latestAnswer() != null) DONE_MS else LINGER_MS
        lingerUntil = SystemClock.elapsedRealtime() + hold
        main.postDelayed(::refresh, hold + 50)
    }

    private fun model(voice: VoiceSessionUiState): Model? {
        val session = app
        val messages = sessionMessages()
        val turn = messages.asReversed().takeWhile { it !is UserMessageUi }
        val lastUser = messages.lastOrNull { it is UserMessageUi } as? UserMessageUi
        val answer = turn.firstOrNull { it is AgentMessageUi } as? AgentMessageUi
        val tools = turn.filterIsInstance<ToolActivityMessageUi>()
        val busy = session?.voiceRuntimeBusy == true
        val said = voice.transcript.ifBlank { lastUser?.content.orEmpty() }
        val supplement = "停顿后补充到当前任务"
        if (!voice.active) {
            // 任务停止要一点时间，这期间仍显示让出提示，不闪回「正在…」。
            if (SystemClock.elapsedRealtime() < yieldUntil) return Model(TvOrbRing.None, compact = TvBackHandler.YIELD_MESSAGE)
            if (busy) return working(tools, lastUser?.content.orEmpty(), null)
            if (SystemClock.elapsedRealtime() < lingerUntil) return linger
            return null
        }
        return when (voice.channel) {
            VoiceChannel.Connecting -> Model(TvOrbRing.Listening, compact = "正在连接…", compactColor = SECONDARY)
            VoiceChannel.Hearing -> Model(TvOrbRing.Listening, indicator = Indicator.BarsLive, status = "你在说",
                body = tail(voice.transcript.ifBlank { "…" }), context = if (busy) supplement else "停顿后自动发送")
            VoiceChannel.Thinking -> if (busy && tools.isNotEmpty()) working(tools, said, supplement)
                else Model(TvOrbRing.Working, indicator = Indicator.MiniOrb, status = "正在想", body = tail(said), context = supplement)
            VoiceChannel.Speaking -> answer?.let { speaking(it, lastUser) }
                ?: Model(TvOrbRing.Speaking, indicator = Indicator.Speaker, status = "Movo", body = "", movo = true)
            VoiceChannel.Listening, VoiceChannel.Off -> when {
                voice.transcript.isNotBlank() -> Model(TvOrbRing.Listening, indicator = Indicator.BarsLive, status = "你在说",
                    body = tail(voice.transcript), context = if (busy) supplement else "停顿后自动发送")
                // 任务在跑时会话仍可继续听；环境声音会把通道拉回「听」，此时优先显示在做什么。
                busy -> working(tools, said, supplement)
                answer != null -> parseChoices(answer.content)?.let { card ->
                    Model(TvOrbRing.Listening, indicator = Indicator.Speaker, status = "Movo 在问", body = card.header,
                        context = "说「第二个」，或用方向键选、确认", choices = card)
                } ?: run {
                    val screens = screensOf(answer.content)
                    Model(TvOrbRing.Listening, indicator = Indicator.BarsIdle, status = "可以接着说", body = screens.lastOrNull().orEmpty(),
                        movo = true, context = if (screens.size > 1) "说「看全文」看完整回答" else question(lastUser))
                }
                else -> Model(TvOrbRing.Listening, compact = "我在，请说")
            }
        }
    }

    /** 1-a：跟着朗读整屏换，按已朗读时间估算到第几屏；念完由 Listening 状态停在最后一屏。 */
    private fun speaking(answer: AgentMessageUi, lastUser: UserMessageUi?): Model {
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
        val page = if (screens.size > 1) " · ${index + 1} / ${screens.size}" else ""
        return Model(TvOrbRing.Speaking, indicator = Indicator.Speaker, status = "Movo", body = screens.getOrElse(index) { "" },
            movo = true, context = question(lastUser)?.plus(page) ?: page.removePrefix(" · ").ifBlank { null })
    }

    private fun question(lastUser: UserMessageUi?) = lastUser?.content?.lineSequence()?.firstOrNull()?.trim()
        ?.takeIf { it.isNotBlank() }?.let { "你问：$it" }

    private fun working(tools: List<ToolActivityMessageUi>, said: String, context: String?): Model {
        val latest = tools.firstOrNull()
        val label = latest?.toolName?.let { name -> toolDisplayNameResource(name)?.let { this.context?.getString(it) } ?: name }
        val step = tools.size.takeIf { it > 0 }?.let { "第 $it 步" }
        val status = listOfNotNull(label?.let { "正在$it" } ?: "正在处理", step).joinToString(" · ")
        return Model(TvOrbRing.Working, indicator = Indicator.MiniOrb, status = status, body = tail(said), context = context)
    }

    private fun latestAnswer(): AgentMessageUi? = sessionMessages().asReversed()
        .takeWhile { it !is UserMessageUi }.firstOrNull { it is AgentMessageUi } as? AgentMessageUi

    private fun sessionMessages(): List<io.github.fartown.movo.ui.model.AgentChatMessageUi> {
        val all = app?.homeState?.messages.orEmpty()
        val start = sessionBaseline?.let { id -> all.indexOfLast { it.id == id } } ?: -1
        return if (start >= 0) all.drop(start + 1) else if (sessionBaseline == null) all else emptyList()
    }

    /** 实时字幕只显示末尾两行的量，左对齐、框不变宽（Android TV 搜索栏做法）。 */
    internal fun tail(text: String): String = text.trim().let { if (it.length <= SCREEN_CHARS) it else "…" + it.takeLast(SCREEN_CHARS - 1) }

    /** 一行要点：取第一句有内容的文字，去掉 Markdown 记号。 */
    internal fun summaryOf(content: String): String = lines(content).firstOrNull() ?: "已回答"

    internal fun longHintOf(content: String): String? {
        val all = lines(content)
        return if (all.size > 1 || (all.firstOrNull()?.length ?: 0) > 40) "说「看全文」" else null
    }

    /**
     * 把回答切成字幕屏：每屏不超过 [SCREEN_CHARS] 字，优先在句号、问号、分号处断，句子太长再在逗号处断，
     * 仍放不下才硬切；相邻短句合进同一屏。
     */
    internal fun screensOf(content: String): List<String> {
        val sentences = lines(content).flatMap { line ->
            Regex("""[^。！？；!?;]+[。！？；!?;]*""").findAll(line).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        }.flatMap { sentence ->
            if (sentence.length <= SCREEN_CHARS) listOf(sentence)
            else Regex("""[^，,、]+[，,、]*""").findAll(sentence).map { it.value }.toList()
                .flatMap { part -> if (part.length <= SCREEN_CHARS) listOf(part) else part.chunked(SCREEN_CHARS) }
        }
        val screens = mutableListOf<String>()
        val current = StringBuilder()
        for (piece in sentences) {
            if (current.isNotEmpty() && current.length + piece.length > SCREEN_CHARS) { screens += current.toString(); current.clear() }
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

    /** 一个窗口里同时只有一种形态（小胶囊 / 卡片）；同一形态只换文字，不重建、不改尺寸。 */
    private class Views(
        val row: LinearLayout,
        val ball: TvBallView,
        var mode: String = "",
        var content: View? = null,
        var indicatorKind: Indicator? = null,
        var indicatorHost: FrameLayout? = null,
        var status: TextView? = null,
        var body: TextView? = null,
        var list: LinearLayout? = null,
        var footer: TextView? = null,
        var pill: TextView? = null,
        var tinted: Boolean = false,
    )

    private fun show(service: AgentAccessibilityService, model: Model) {
        if (owner !== service) removeNow()
        val density = service.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        var container = root
        val fresh = container == null
        if (container == null) {
            container = FrameLayout(service).apply { clipChildren = false; clipToPadding = false; setPadding(dp(16f), dp(16f), 0, 0) }
            val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; clipChildren = false }
            val ball = TvBallView(service)
            row.addView(ball, LinearLayout.LayoutParams(dp(48f), dp(48f)))
            container.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = dp(64f); y = dp(80f); setTitle("Movo 语音胶囊") }
            try {
                service.getSystemService(WindowManager::class.java).addView(container, params)
            } catch (_: Exception) {
                MemoryDiagnostics.record("tv.capsule", "window.unavailable")
                return
            }
            root = container; owner = service; views = Views(row, ball)
        }
        val v = views ?: return
        v.ball.ring = model.ring
        val card = model.choices
        if (card != choices) choiceFocus = 0
        choices = card
        val mode = when { model.compact != null -> "pill"; card != null -> "choice"; else -> "card" }
        if (mode != v.mode) build(service, v, mode, ::dp)
        bind(service, v, model, ::dp)
        if (fresh) {
            container.alpha = 0f; container.translationY = dp(16f).toFloat()
            container.animate().alpha(1f).translationY(0f).setDuration(200).start()
        }
        scheduleDim(VoiceConversationController.idleEndsAt.value)
    }

    /** 换形态：小胶囊 → 卡片时从球的方向长出来（300ms，同手机展开卡）。 */
    private fun build(context: Context, v: Views, mode: String, dp: (Float) -> Int) {
        v.content?.let { v.row.removeView(it) }
        v.indicatorKind = null
        val content: View = if (mode == "pill") {
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20f), 0, dp(24f), 0)
                background = glass(dp(24f).toFloat(), dp, tinted = false)
                val host = FrameLayout(context); v.indicatorHost = host
                addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12f) })
                val text = text(context, 24f, TEXT, medium = true).apply { maxLines = 1 }
                addView(text); v.pill = text
            }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48f)) }
        } else {
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24f), dp(18f), dp(24f), dp(18f))
                background = glass(dp(24f).toFloat(), dp, tinted = false)
                val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val host = FrameLayout(context); v.indicatorHost = host
                head.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12f) })
                val status = text(context, 20f, SECONDARY, medium = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                head.addView(status); v.status = status
                addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30f)))
                val body = text(context, 26f, TEXT, medium = true).apply {
                    lineHeight = dp(38f); val lines = if (mode == "choice") 1 else 2
                    minLines = lines; maxLines = lines; ellipsize = TextUtils.TruncateAt.END
                }
                addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
                v.body = body
                if (mode == "choice") {
                    val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                    addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f) })
                    v.list = list
                } else v.list = null
                val footer = text(context, 18f, SECONDARY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; lineHeight = dp(26f) }
                addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
                v.footer = footer
            }.also { it.layoutParams = LinearLayout.LayoutParams(dp(CARD_DP.toFloat()), ViewGroup.LayoutParams.WRAP_CONTENT) }
        }
        (content.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12f)
        v.row.addView(content, 0)
        if (v.mode == "pill" && mode != "pill") {
            content.pivotX = dp(CARD_DP.toFloat()).toFloat(); content.pivotY = 0f
            content.alpha = 0f; content.scaleX = .9f; content.scaleY = .9f
            content.post { content.pivotY = content.height.toFloat(); content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300).start() }
        }
        v.mode = mode; v.content = content; v.tinted = false
    }

    private fun bind(context: Context, v: Views, model: Model, dp: (Float) -> Int) {
        setIndicator(context, v, if (model.compact != null) Indicator.BarsIdle.takeIf { model.compact == "我在，请说" } ?: Indicator.None else model.indicator, dp)
        if (model.compact != null) {
            v.pill?.apply { text = model.compact; setTextColor(model.compactColor) }
            return
        }
        if (v.tinted != model.movo) { v.content?.background = glass(dp(24f).toFloat(), dp, tinted = model.movo); v.tinted = model.movo }
        v.status?.text = model.status
        v.body?.let { body ->
            if (body.text.toString() != model.body) {
                body.text = model.body
                // 翻屏：整屏淡入替换，不滚动、不跑马灯。
                if (model.movo && model.body.isNotEmpty()) { body.alpha = 0f; body.animate().alpha(1f).setDuration(180).start() }
            }
        }
        v.footer?.apply { text = model.context.orEmpty(); visibility = View.VISIBLE }
        val card = model.choices
        v.list?.let { list ->
            list.removeAllViews()
            card?.items?.forEachIndexed { index, item ->
                val focused = index == choiceFocus
                list.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                    background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(if (focused) SELECTED else 0) }
                    addView(TextView(context).apply {
                        text = "${index + 1}"; textSize = 18f; gravity = Gravity.CENTER
                        setTextColor(if (focused) 0xFFFFFFFF.toInt() else TEXT)
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (focused) INK else 0x0F151515) }
                    }, LinearLayout.LayoutParams(dp(32f), dp(32f)))
                    addView(text(context, 24f, if (focused) INK else TEXT, medium = true).apply { text = item; maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(14f) })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f) })
            }
        }
    }

    private fun setIndicator(context: Context, v: Views, kind: Indicator, dp: (Float) -> Int) {
        if (v.indicatorKind == kind) return
        v.indicatorKind = kind
        val host = v.indicatorHost ?: return
        host.removeAllViews()
        val view: View? = when (kind) {
            Indicator.None -> null
            Indicator.BarsLive -> TvBarsView(context, listOf(9, 18, 24, 15, 21, 12))
            Indicator.BarsIdle -> TvBarsView(context, List(6) { 6 })
            Indicator.Speaker -> TvSpeakerView(context)
            Indicator.MiniOrb -> TvOrbView(context, 22f)
            Indicator.Check -> TvCheckView(context)
        }
        host.visibility = if (view == null) View.GONE else View.VISIBLE
        view?.let { host.addView(it) }
    }

    private fun text(context: Context, sp: Float, color: Int, medium: Boolean = false) = TextView(context).apply {
        textSize = sp; setTextColor(color); includeFontPadding = false
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** Material/Glass 的降级（电视不支持跨窗口模糊）：白 88% + 发丝描边；C-2：Movo 回答时叠一层 38% 的光球色。 */
    private fun glass(radius: Float, dp: (Float) -> Int, tinted: Boolean): android.graphics.drawable.Drawable {
        val base = GradientDrawable().apply { setColor(GLASS); cornerRadius = radius; setStroke(dp(1f).coerceAtLeast(1), HAIRLINE) }
        if (!tinted) return base
        val tint = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x61FFC2A6, 0x61FF9EC2, 0x61B39BFF, 0x618CC6FF, 0x61BFEFE3)).apply { cornerRadius = radius }
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

    private fun hide() {
        main.removeCallbacks(dimTask)
        main.removeCallbacks(pageTask)
        val view = root ?: return
        root = null; views = null
        val service = owner
        owner = null
        view.animate().alpha(0f).setDuration(150).withEndAction {
            runCatching { service?.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
        }.start()
    }

    private fun removeNow() {
        val view = root ?: return
        runCatching { owner?.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
        root = null; owner = null; views = null
    }
}

/**
 * 悬浮球（手机 Overlay/Orb 32 → 48）：玻璃圆 + logo 光球 33 + 2.25 宽状态环。
 * 聆听 = Indigo 整环；执行中 = Indigo 弧线旋转；完成 = Green 整环；待命无环。
 */
internal class TvBallView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE0FFFFFF.toInt() }
    private val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1A141414; style = Paint.Style.STROKE; strokeWidth = density }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var sweep = 0f
    private var spinner: ValueAnimator? = null
    var ring: TvOrbRing = TvOrbRing.None
        set(value) {
            if (field == value) return
            field = value
            if (value == TvOrbRing.Working) startSpin() else stopSpin()
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy)
        canvas.drawCircle(cx, cy, r - density / 2, glass)
        canvas.drawCircle(cx, cy, r - density / 2, hair)
        TvOrbPainter.draw(canvas, cx, cy, 33 * density, TvOrbRing.None, 0f, 0f, density)
        val stroke = 2.25f * density
        ringPaint.strokeWidth = stroke
        val oval = RectF(cx - r + stroke / 2, cy - r + stroke / 2, cx + r - stroke / 2, cy + r - stroke / 2)
        when (ring) {
            TvOrbRing.Listening, TvOrbRing.Speaking -> { ringPaint.color = 0xFF4F56E3.toInt(); canvas.drawOval(oval, ringPaint) }
            TvOrbRing.Working -> { ringPaint.color = 0xFF4F56E3.toInt(); canvas.drawArc(oval, sweep - 90f, 200f, false, ringPaint) }
            TvOrbRing.Done -> { ringPaint.color = 0xFF178A55.toInt(); canvas.drawOval(oval, ringPaint) }
            TvOrbRing.None, TvOrbRing.Focused -> Unit
        }
    }

    private fun startSpin() {
        if (spinner != null) return
        spinner = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1200; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopSpin() { spinner?.cancel(); spinner = null }
    override fun onDetachedFromWindow() { stopSpin(); super.onDetachedFromWindow() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (ring == TvOrbRing.Working) startSpin() }
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

/** 完成 ✓（Green）。 */
private class TvCheckView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF178A55.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((24 * density).toInt(), (24 * density).toInt())
    override fun onDraw(canvas: Canvas) {
        val d = density; stroke.strokeWidth = 2 * d
        canvas.drawPath(Path().apply { moveTo(20 * d, 6 * d); lineTo(9 * d, 17 * d); lineTo(4 * d, 12 * d) }, stroke)
    }
}
