package io.github.mangi.eta.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.AgentConversationSheet
import io.github.mangi.eta.agent.overlay.AgentResultSheetSizing
import io.github.mangi.eta.agent.runtime.AgentConversationHandoff
import io.github.mangi.eta.agent.runtime.AgentConversationTarget
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.diagnostics.MemoryDiagnostics
import io.github.mangi.eta.ui.app.AgentAppSession
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.app.AgentConversationContent
import io.github.mangi.eta.ui.markdown.InAppBrowserUriHandler
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text

/** Compact viewport of the conversation. Expansion hands off to the original MainActivity page. */
internal class AgentConversationSheetActivity : ComponentActivity() {
    private val agentState by lazy { AgentAppSession.get(application) }
    private var request by mutableStateOf<AgentConversationHandoff.Request?>(null)
    /** 助手入口：没有 run handoff，直接承载当前这条共享会话。 */
    private var assistantMode by mutableStateOf(false)
    private var ready by mutableStateOf(false)
    private var autoListen by mutableStateOf(false)
    private var mainHandoffToken: Any? = null
    private var resizeAnimator: ValueAnimator? = null
    private var windowHeight = 0
    private var keyboardLift = 0
    private var dragging = false
    private var dragHeight = 0f
    private var pendingKeyboardLift: Int? = null
    private var afterHidden: (() -> Unit)? = null
    private val keyguardGate = KeyguardContentGate(this, ::finish)
    private val microphonePermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { allowed ->
        if (allowed && !isFinishing) io.github.mangi.eta.agent.voice.session.VoiceEntry.startInPlace(this)
        else Toast.makeText(this, "未获得麦克风权限，可以继续文字输入", Toast.LENGTH_LONG).show()
    }

    private fun startVoiceInput() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            io.github.mangi.eta.agent.voice.session.VoiceEntry.startInPlace(this)
        } else microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        assistantMode = intent.action == ACTION_ASSISTANT
        autoListen = intent.getBooleanExtra(io.github.mangi.eta.agent.voice.EtaAssistantVoiceService.EXTRA_AUTO_LISTEN, false)
        request = AgentConversationHandoff.from(intent)
        if (request == null && !assistantMode) { finish(); return }
        current = WeakReference(this)
        if (assistantMode) keyguardGate.check()
        enableEdgeToEdge()
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        window.attributes = window.attributes.apply { windowAnimations = 0; dimAmount = 0f }
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        updateHeight(AgentResultSheetSizing.height(screenHeight(), false))
        MemoryDiagnostics.record("conversation", "sheet.created")
        lifecycleScope.launch {
            val initialAppearance = AppearanceSettingsRepository.settings()
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow().collectAsState(initialAppearance)
                AgentAppTheme(appearance, applyInterfaceScale = true, onResolvedDarkModeChange = { dark ->
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }) {
                    CompositionLocalProvider(LocalUriHandler provides InAppBrowserUriHandler(this@AgentConversationSheetActivity) {
                        moveTaskToBack(true)
                    }) {
                        val density = LocalDensity.current
                        val imeBottom = WindowInsets.ime.getBottom(density)
                        val navigationBottom = WindowInsets.navigationBars.getBottom(density)
                        LaunchedEffect(imeBottom, navigationBottom) {
                            avoidKeyboard(imeBottom, navigationBottom)
                        }
                        LaunchedEffect(request, assistantMode, keyguardGate.locked) {
                            // 锁屏时不加载会话，解锁成功后本 effect 会重新执行。
                            if (keyguardGate.locked) return@LaunchedEffect
                            val opening = request
                            if (opening == null) {
                                // 助手入口不新建会话：语音和文字共用当前这条，界面只是它的另一个容器。
                                val opened = runCatching { agentState.voiceConversationId() }
                                ready = opened.isSuccess
                                opened.onFailure { failure ->
                                    Toast.makeText(
                                        this@AgentConversationSheetActivity,
                                        failure.message ?: getString(R.string.overlay_result_open_failed),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    finish()
                                }
                                return@LaunchedEffect
                            }
                            val opened = agentState.openResultConversation(opening.target, opening.runId)
                            ready = opened
                            opening.acknowledge(opened)
                            if (!opened) {
                                Toast.makeText(this@AgentConversationSheetActivity, R.string.overlay_result_open_failed, Toast.LENGTH_LONG).show()
                                finish()
                            }
                        }
                        LaunchedEffect(ready, autoListen, keyguardGate.locked) {
                            if (ready && autoListen && !keyguardGate.locked) {
                                autoListen = false
                                startVoiceInput()
                            }
                        }
                        BackHandler { finish() }
                        val pane = agentState.conversationPaneState
                        AgentConversationSheet(
                            title = pane.conversations.firstOrNull { it.id == pane.selectedConversationId }?.title
                                ?.takeUnless { keyguardGate.locked }
                                ?: getString(R.string.app_name),
                            onDrag = ::drag,
                            onDragStopped = ::endDrag,
                            onOpenConversation = ::openInApp,
                            onClose = ::finish,
                        ) {
                            if (ready && !keyguardGate.locked) {
                                AgentConversationContent(
                                    agentState = agentState,
                                    onOpenBrowser = ::openBrowser,
                                    onNavigateBack = ::finish,
                                    initiallyShowLatestMessage = true,
                                )
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(getString(
                                        if (keyguardGate.locked) R.string.overlay_unlock_to_continue
                                        else R.string.overlay_result_opening_conversation,
                                    ))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_ASSISTANT) {
            assistantMode = true
            autoListen = intent.getBooleanExtra(io.github.mangi.eta.agent.voice.EtaAssistantVoiceService.EXTRA_AUTO_LISTEN, false)
            request = null
            keyguardGate.check()
            return
        }
        val next = AgentConversationHandoff.from(intent) ?: return
        // Keep the existing body mounted when the same conversation completes another turn.
        val currentConversation = next.target.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE &&
            next.target.key == agentState.conversationPaneState.selectedConversationId
        if (next.target != request?.target && !currentConversation) ready = false
        request = next
    }

    override fun onResume() {
        super.onResume()
        current = WeakReference(this)
        agentState.refreshRuntimeResults()
    }

    override fun onStop() {
        super.onStop()
        afterHidden?.invoke()
        afterHidden = null
    }

    override fun onDestroy() {
        mainHandoffToken = null
        resizeAnimator?.cancel()
        afterHidden?.invoke()
        afterHidden = null
        if (current.get() === this) current.clear()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        resizeAnimator?.cancel()
        updateHeight(AgentResultSheetSizing.height(screenHeight(), false, keyboardLift))
    }

    private fun screenHeight(): Int = windowManager.maximumWindowMetrics.bounds.height()

    private fun avoidKeyboard(imeBottom: Int, navigationBottom: Int) {
        val lift = (imeBottom - navigationBottom).coerceAtLeast(0)
        if (dragging) {
            pendingKeyboardLift = lift
            return
        }
        if (lift == keyboardLift) return
        resizeAnimator?.cancel()
        keyboardLift = lift
        // The original composer already consumes IME insets. Lift this same window by the
        // keyboard's extra height so attachments and text retain their space above it.
        // This does not expand the sheet or replace its conversation composition.
        updateHeight(AgentResultSheetSizing.height(screenHeight(), false, keyboardLift))
    }

    private fun updateHeight(height: Int) {
        windowHeight = height
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, height)
    }

    private fun drag(deltaY: Float) {
        if (!dragging) dragHeight = windowHeight.toFloat()
        dragging = true
        resizeAnimator?.cancel()
        dragHeight = AgentResultSheetSizing.drag(dragHeight, deltaY, screenHeight(), keyboardLift)
        updateHeight(dragHeight.roundToInt())
    }

    private fun endDrag(velocity: Float) {
        val full = AgentResultSheetSizing.settleExpanded(
            windowHeight, screenHeight(), velocity, resources.displayMetrics.density * 600, keyboardLift,
        )
        dragging = false
        // Apply the latest keyboard state after choosing the anchor, not during the gesture.
        pendingKeyboardLift?.let { keyboardLift = it }
        pendingKeyboardLift = null
        settle(full)
    }

    private fun settle(full: Boolean) {
        resizeAnimator?.cancel()
        if (full) {
            openInApp()
            return
        }
        val height = AgentResultSheetSizing.height(screenHeight(), false, keyboardLift)
        resizeAnimator = ValueAnimator.ofInt(windowHeight, height).apply {
            duration = 220
            addUpdateListener { updateHeight(it.animatedValue as Int) }
            start()
        }
    }

    private fun openInApp() {
        if (!ready || keyguardGate.locked || mainHandoffToken != null) return
        val opening = request
        val target = opening?.target ?: if (assistantMode) {
            agentState.conversationPaneState.selectedConversationId?.let {
                AgentConversationTarget(AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, it)
            }
        } else null
        if (target == null) return
        val token = Any()
        mainHandoffToken = token
        fun failed() {
            if (mainHandoffToken !== token) return
            mainHandoffToken = null
            settle(false)
            Toast.makeText(this, R.string.overlay_result_open_failed, Toast.LENGTH_LONG).show()
        }
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (mainHandoffToken !== token) return
                if (resultCode == AgentConversationHandoff.RESULT_READY) {
                    mainHandoffToken = null
                    finish()
                } else failed()
            }
        }
        // MainActivity owns the original Home conversation route and all of its top-bar actions.
        // Both hosts already share AgentAppSession and the conversation-keyed composer draft.
        runCatching {
            startActivity(AgentConversationHandoff.intent(this, target, opening?.runId, receiver))
        }.onFailure { failed() }
        lifecycleScope.launch {
            delay(10_000)
            failed()
        }
    }

    private fun openBrowser() {
        startActivity(Intent(this, MainActivity::class.java)
            .setAction(InAppBrowserUriHandler.ACTION_OPEN_BROWSER)
            .putExtra(InAppBrowserUriHandler.EXTRA_BROWSER_URL, "")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        moveTaskToBack(true)
    }

    companion object {
        /** 系统入口（唤醒词 / 电源键 / 助手手势）打开助手界面时使用。 */
        const val ACTION_ASSISTANT = "io.github.mangi.eta.ui.ASSISTANT"
        @Volatile private var current = WeakReference<AgentConversationSheetActivity>(null)

        fun isConversationVisible(target: AgentConversationTarget?): Boolean {
            val activity = current.get() ?: return false
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
            return target != null && (target == activity.request?.target ||
                target.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE &&
                target.key == activity.agentState.conversationPaneState.selectedConversationId)
        }

        /** Tool dispatch waits for onStop, so screenshots cannot capture the conversation itself. */
        fun hideForDeviceOperation(): Boolean {
            val activity = current.get() ?: return true
            val hidden = CountDownLatch(1)
            val success = AtomicBoolean(false)
            val hide = Runnable {
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    success.set(true)
                    hidden.countDown()
                } else {
                    activity.afterHidden = { success.set(true); hidden.countDown() }
                    if (!activity.moveTaskToBack(true)) {
                        activity.afterHidden = null
                        hidden.countDown()
                    }
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                hide.run()
                return success.get()
            }
            Handler(Looper.getMainLooper()).post(hide)
            return try { hidden.await(3, TimeUnit.SECONDS) && success.get() }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        }
    }
}
