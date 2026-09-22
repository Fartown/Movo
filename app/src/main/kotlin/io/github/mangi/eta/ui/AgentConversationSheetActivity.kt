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
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text

/** A resizable host of the original conversation. Resizing never navigates or resets Compose. */
internal class AgentConversationSheetActivity : ComponentActivity() {
    private val agentState by lazy { AgentAppSession.get(application) }
    private var request by mutableStateOf<AgentConversationHandoff.Request?>(null)
    private var ready by mutableStateOf(false)
    private var expanded by mutableStateOf(false)
    private var resizeAnimator: ValueAnimator? = null
    private var windowHeight = 0
    private var keyboardLift = 0
    private var dragging = false
    private var dragHeight = 0f
    private var pendingKeyboardLift: Int? = null
    private var afterHidden: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = AgentConversationHandoff.from(intent)
        if (request == null) { finish(); return }
        current = WeakReference(this)
        enableEdgeToEdge()
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        window.attributes = window.attributes.apply { windowAnimations = 0; dimAmount = 0f }
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        expanded = savedInstanceState?.getBoolean(STATE_EXPANDED) ?: false
        updateHeight(AgentResultSheetSizing.height(screenHeight(), expanded))
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
                        LaunchedEffect(request) {
                            val opening = request ?: return@LaunchedEffect
                            val opened = agentState.openResultConversation(opening.target, opening.runId)
                            ready = opened
                            opening.acknowledge(opened)
                            if (!opened) {
                                Toast.makeText(this@AgentConversationSheetActivity, R.string.overlay_result_open_failed, Toast.LENGTH_LONG).show()
                                finish()
                            }
                        }
                        BackHandler { if (expanded) settle(false) else finish() }
                        val pane = agentState.conversationPaneState
                        AgentConversationSheet(
                            title = pane.conversations.firstOrNull { it.id == pane.selectedConversationId }?.title
                                ?: getString(R.string.app_name),
                            expanded = expanded,
                            onDrag = ::drag,
                            onDragStopped = ::endDrag,
                            onToggleExpanded = { settle(!expanded) },
                            onClose = ::finish,
                        ) {
                            if (ready) {
                                AgentConversationContent(
                                    agentState = agentState,
                                    onOpenBrowser = ::openBrowser,
                                    onNavigateBack = ::finish,
                                    initiallyShowLatestMessage = true,
                                )
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(getString(R.string.overlay_result_opening_conversation))
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
        resizeAnimator?.cancel()
        afterHidden?.invoke()
        afterHidden = null
        if (current.get() === this) current.clear()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_EXPANDED, expanded)
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        resizeAnimator?.cancel()
        updateHeight(AgentResultSheetSizing.height(screenHeight(), expanded, keyboardLift))
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
        updateHeight(AgentResultSheetSizing.height(screenHeight(), expanded, keyboardLift))
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
        expanded = full
        val height = AgentResultSheetSizing.height(screenHeight(), full, keyboardLift)
        MemoryDiagnostics.record("conversation", "sheet.resized", fields = mapOf("expanded" to full))
        resizeAnimator = ValueAnimator.ofInt(windowHeight, height).apply {
            duration = 220
            addUpdateListener { updateHeight(it.animatedValue as Int) }
            start()
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
        private const val STATE_EXPANDED = "sheet_expanded"
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
