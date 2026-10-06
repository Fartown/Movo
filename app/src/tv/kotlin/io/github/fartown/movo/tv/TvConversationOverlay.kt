package io.github.fartown.movo.tv

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.ObserverHandle
import java.util.concurrent.atomic.AtomicBoolean
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import io.github.fartown.movo.agent.voice.session.VoiceEntry
import io.github.fartown.movo.agent.voice.session.VoiceSessionManager
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import io.github.fartown.movo.ui.app.AgentAppSession
import io.github.fartown.movo.ui.app.AgentAppState
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.SystemNoticeMessageUi
import io.github.fartown.movo.ui.model.UserMessageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Conversation in the same shared session as the app, hosted by accessibility rather than
 * an Activity. Opening it neither pauses the foreground player nor requests audio focus. */
internal object TvConversationOverlay {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var app: AgentAppState? = null
    private var owner: AgentAccessibilityService? = null
    private var window: LinearLayout? = null
    private var title: TextView? = null
    private var response: TextView? = null
    private var input: EditText? = null
    private var draft = ""
    private var suppressed = false
    private var menuHeld = false
    private var backHeld = false
    private var appContext: Context? = null
    private var snapshotObserver: ObserverHandle? = null
    private val snapshotApplyPending = AtomicBoolean(false)
    @Volatile var enabled = false
        private set
    @Volatile var expanded = false
        private set

    fun show(context: Context, autoListen: Boolean): Boolean {
        if (AgentAccessibilityService.current() == null) return false
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { show(context, autoListen) }
            return true
        }
        // A native overlay can be the only UI after a background start. Without a
        // Compose frame clock, publish state writes so snapshotFlow sees run completion.
        if (snapshotObserver == null) snapshotObserver = Snapshot.registerGlobalWriteObserver {
            if (snapshotApplyPending.compareAndSet(false, true)) main.post {
                snapshotApplyPending.set(false)
                Snapshot.sendApplyNotifications()
            }
        }
        init(context)
        enabled = true
        expanded = true
        suppressed = false
        render()
        if (window == null) { enabled = false; expanded = false; return false }
        app?.refreshRuntimeResults()
        TvVoicePanel.refresh()
        if (autoListen) VoiceEntry.startInPlace(context)
        MemoryDiagnostics.record("tv.overlay", "opened")
        return true
    }

    private fun init(context: Context) {
        if (app != null) return
        appContext = context.applicationContext
        val session = AgentAppSession.get(context)
        app = session
        AgentAccessibilityService.addInstanceListener { main.post { if (enabled) render() } }
        scope.launch {
            snapshotFlow { session.homeState.messages to session.voiceRuntimeBusy }.collect { updateText() }
        }
        scope.launch { VoiceSessionManager.state.collect { updateText() } }
    }

    /** Only enabled while the user has chosen the floating assistant. Closing restores MENU. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!enabled) return false
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN) menuHeld = true
            if (event.action == KeyEvent.ACTION_UP && menuHeld) {
                menuHeld = false
                if (expanded) collapse() else appContext?.let { show(it, false) }
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && (expanded || backHeld)) {
            if (event.action == KeyEvent.ACTION_DOWN) backHeld = true
            if (event.action == KeyEvent.ACTION_UP && backHeld) { backHeld = false; collapse() }
            return true
        }
        return false
    }

    fun yieldToDevice(): Boolean {
        if (!expanded) return true
        if (Looper.myLooper() == Looper.getMainLooper()) { collapse(); return true }
        val done = CountDownLatch(1)
        main.post { try { collapse() } finally { done.countDown() } }
        return try { done.await(2, TimeUnit.SECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    }

    fun suppressForScreenshot(value: Boolean) {
        suppressed = value
        render()
    }

    private fun collapse() {
        if (!enabled) return
        hideKeyboard()
        expanded = false
        render()
        MemoryDiagnostics.record("tv.overlay", "collapsed")
    }

    private fun close() {
        hideKeyboard()
        enabled = false
        expanded = false
        snapshotObserver?.dispose()
        snapshotObserver = null
        remove()
        TvVoicePanel.refresh()
    }

    private fun hideKeyboard() {
        input?.let { field -> owner?.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0) }
    }

    private fun send() {
        val text = input?.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        draft = ""
        input?.setText("")
        VoiceSessionManager.end()
        collapse()
        app?.sendCurrentMessage(text)
        MemoryDiagnostics.record("tv.overlay", "text.submitted", fields = mapOf("chars" to text.length))
    }

    private fun render() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(::render); return }
        remove()
        val service = AgentAccessibilityService.current() ?: return
        // 收起后不常驻小框：状态由右下角语音胶囊显示，菜单键随时重新展开。
        if (!enabled || suppressed || !expanded) { TvVoicePanel.refresh(); return }
        owner = service
        appContext = service.applicationContext
        val density = service.resources.displayMetrics.density
        fun dp(value: Int) = (density * value).toInt()
        fun label(size: Float, secondary: Boolean = false) = TextView(service).apply {
            textSize = size
            setTextColor(if (secondary) 0xFF6B6964.toInt() else 0xFF151515.toInt())
            isSoundEffectsEnabled = false
        }
        fun button(text: String, action: () -> Unit) = Button(service).apply {
            this.text = text; textSize = 20f; isAllCaps = false
            isSoundEffectsEnabled = false
            setOnClickListener { action() }
        }
        val root = object : LinearLayout(service) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean = TvConversationOverlay.onKeyEvent(event) || super.dispatchKeyEvent(event)
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            isSoundEffectsEnabled = false
            background = GradientDrawable().apply {
                setColor(0xFFF4F3EF.toInt()); cornerRadius = dp(20).toFloat(); setStroke(dp(2), 0xFF454CD2.toInt())
            }
            elevation = dp(8).toFloat()
        }
        title = label(24f).also { root.addView(it) }
        if (expanded) {
            response = label(22f).apply { setPadding(0, dp(12), 0, dp(12)) }
            root.addView(ScrollView(service).apply { addView(response) }, LinearLayout.LayoutParams(-1, 0, 1f))
            input = EditText(service).apply {
                hint = "输入电视操作指令"; textSize = 22f; isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEND
                isSoundEffectsEnabled = false
                setText(draft)
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
                }
            }
            root.addView(input, LinearLayout.LayoutParams(-1, dp(64)))
            val sendButton = button("发送", ::send)
            input?.setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                    sendButton.requestFocus(); true
                } else false
            }
            val actions = LinearLayout(service)
            listOf(sendButton, button("停止任务") { TvBackHandler.cancel() }, button("收起", ::collapse), button("关闭浮窗", ::close))
                .forEach { actions.addView(it, LinearLayout.LayoutParams(0, dp(56), 1f)) }
            root.addView(actions)
            root.addView(label(16f, true).apply { text = "菜单键展开 · 返回键收起 · 关闭浮窗后恢复菜单键" })
        } else {
            root.isFocusable = false
            root.setOnClickListener { show(service, false) }
            root.addView(label(16f, true).apply { text = "菜单键打开对话" })
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (expanded) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val params = WindowManager.LayoutParams(
            if (expanded) minOf(dp(560), service.resources.displayMetrics.widthPixels - dp(80)) else dp(220),
            if (expanded) minOf(dp(520), service.resources.displayMetrics.heightPixels - dp(80)) else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM; x = dp(32); y = dp(32)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            setTitle("Movo 悬浮助手")
        }
        runCatching { service.getSystemService(WindowManager::class.java).addView(root, params) }
            .onSuccess { window = root; updateText(); if (expanded) input?.requestFocus() }
            .onFailure { remove(); MemoryDiagnostics.record("tv.overlay", "window.failed") }
    }

    private fun updateText() {
        val session = app ?: return
        val voice = VoiceSessionManager.state.value
        title?.text = when {
            voice.active -> "Movo · ${voice.statusText}"
            session.voiceRuntimeBusy -> "Movo · 正在执行"
            else -> "Movo"
        }
        // Suggestions and trace rows can follow the answer. Stay within this user turn
        // so a new request never displays the previous turn's answer as its result.
        val last = session.homeState.messages.asReversed()
            .takeWhile { it !is UserMessageUi }
            .firstOrNull { it is AgentMessageUi || it is SystemNoticeMessageUi }
        response?.text = if (voice.active && voice.transcript.isNotBlank()) voice.transcript
            else when (last) {
                is AgentMessageUi -> last.content.takeIf { it.isNotBlank() }
                is SystemNoticeMessageUi -> last.detail ?: "本轮已停止，可输入新指令。"
                else -> null
            }
                ?: if (session.voiceRuntimeBusy) "正在处理你的指令……" else "输入指令，让我帮你操作当前电视画面。"
    }

    private fun remove() {
        input?.let { draft = it.text.toString() }
        window?.let { view -> runCatching { owner?.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) } }
        window = null; input = null; title = null; response = null; owner = null
    }
}
