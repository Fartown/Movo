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
import android.view.MotionEvent
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
import io.github.mangi.eta.ui.theme.MovoMotion
import io.github.mangi.eta.ui.theme.isReducedMotion
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
    /** 进场、下拉回弹与退场共用：整块浮层的位移与后方遮罩（规范 8.9 / 9.5）。 */
    private var sheetAnimator: ValueAnimator? = null
    /** 在半屏高度继续往下拖时，浮层整体下移的距离（不压缩内容）。 */
    private var dismissOffset = 0f
    private var closing = false
    /** Q4 浮层 → App：推满全屏的进度（驱动顶部圆角与把手）。 */
    private var expandProgress by mutableStateOf(0f)
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
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        // 后方 `overlay/scrim` 32%：浮层是模态的，点遮罩关闭浮层（不停止任务）。
        window.attributes = window.attributes.apply { windowAnimations = 0; dimAmount = 0f }
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        updateHeight(AgentResultSheetSizing.height(screenHeight(), false))
        animateIn()
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
                        BackHandler { dismissAnimated() }
                        val pane = agentState.conversationPaneState
                        AgentConversationSheet(
                            title = pane.conversations.firstOrNull { it.id == pane.selectedConversationId }?.title
                                ?.takeUnless { keyguardGate.locked }
                                ?: getString(R.string.app_name),
                            onDrag = ::drag,
                            onDragStopped = ::endDrag,
                            onOpenConversation = ::expandIntoApp,
                            onClose = ::dismissAnimated,
                            expandProgress = { expandProgress },
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
        // 长按完成态的悬浮球：打开结果的同时直接进入语音模式（规范 8.1）。
        if (intent.getBooleanExtra(io.github.mangi.eta.agent.voice.EtaAssistantVoiceService.EXTRA_AUTO_LISTEN, false)) autoListen = true
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
        // 收成悬浮球后退到后台：恢复窗口外观，下次回到前台时正常显示。
        sheetAnimator?.cancel()
        resetOrbMorph()
        setScrim(1f)
        afterHidden?.invoke()
        afterHidden = null
    }

    override fun onDestroy() {
        mainHandoffToken = null
        resizeAnimator?.cancel()
        sheetAnimator?.cancel()
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
        if (closing) return
        if (!dragging) dragHeight = windowHeight.toFloat()
        dragging = true
        resizeAnimator?.cancel()
        sheetAnimator?.cancel()
        // 已在半屏高度还往下拖：浮层整体跟手下移（规范 8.9「向下拖过 1/3 关闭」），内容不被压缩。
        val collapsedHeight = AgentResultSheetSizing.height(screenHeight(), false, keyboardLift)
        if (dismissOffset > 0f || (deltaY > 0f && dragHeight <= collapsedHeight)) {
            dismissOffset = (dismissOffset + deltaY).coerceAtLeast(0f)
            applySheetOffset(dismissOffset)
            return
        }
        dragHeight = AgentResultSheetSizing.drag(dragHeight, deltaY, screenHeight(), keyboardLift)
        updateHeight(dragHeight.roundToInt())
    }

    private fun endDrag(velocity: Float) {
        val flingThreshold = resources.displayMetrics.density * 600
        dragging = false
        // Apply the latest keyboard state after choosing the anchor, not during the gesture.
        pendingKeyboardLift?.let { keyboardLift = it }
        pendingKeyboardLift = null
        if (dismissOffset > 0f) {
            if (AgentResultSheetSizing.shouldDismiss(dismissOffset, windowHeight, velocity, flingThreshold)) {
                dismissAnimated()
            } else {
                animateSheetOffset(dismissOffset, 0f, MovoMotion.SLOW.toLong(), EASE_STANDARD) { dismissOffset = 0f }
            }
            return
        }
        val full = AgentResultSheetSizing.settleExpanded(
            windowHeight, screenHeight(), velocity, flingThreshold, keyboardLift,
        )
        settle(full)
    }

    /**
     * 进场：从底部上移弹出到半屏 `slow` + `enter`，遮罩淡入 `standard`；从悬浮球点开时按 Q4 从球的位置展开；
     * 减少动画时只淡入 `fast`。
     */
    private fun animateIn() {
        val decor = window.decorView
        val orb = consumeOrbOrigin()
        if (orb != null && !isReducedMotion(this)) {
            setScrim(0f)
            decor.alpha = 0f
            decor.post { if (!closing) morphWithOrb(orb, expand = true) {} }
            return
        }
        if (isReducedMotion(this)) {
            decor.alpha = 0f
            decor.animate().alpha(1f).setDuration(MovoMotion.FAST.toLong()).start()
            setScrim(1f)
            return
        }
        decor.translationY = screenHeight().toFloat()
        setScrim(0f)
        decor.post {
            if (closing) return@post
            val from = windowHeight.toFloat().coerceAtLeast(1f)
            animateSheetOffset(from, 0f, MovoMotion.SLOW.toLong(), EASE_ENTER)
        }
    }

    /** 关闭浮层（✕、返回、点遮罩、下拉）：先向下退场 `slow-exit`，再结束 Activity；只关浮层，不停止任务。 */
    private fun dismissAnimated() {
        if (closing) return
        closing = true
        resizeAnimator?.cancel()
        sheetAnimator?.cancel()
        if (isReducedMotion(this)) {
            window.decorView.animate().alpha(0f).setDuration(MovoMotion.FAST_EXIT.toLong())
                .withEndAction { finish() }.start()
            return
        }
        val from = window.decorView.translationY
        animateSheetOffset(from, windowHeight.toFloat().coerceAtLeast(from + 1f), MovoMotion.SLOW_EXIT.toLong(), EASE_EXIT) {
            finish()
        }
    }

    private fun animateSheetOffset(
        from: Float,
        to: Float,
        duration: Long,
        interpolator: android.view.animation.Interpolator,
        onEnd: () -> Unit = {},
    ) {
        sheetAnimator?.cancel()
        sheetAnimator = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { applySheetOffset(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) onEnd() }
            })
            start()
        }
    }

    /**
     * Q4 球 ↔ 浮层（规范 9.3.2 / 9.5）：窗口轮廓在悬浮球玻璃圆（圆角 16）与浮层（顶部圆角 28）之间插值，
     * `slow` + `standard`；浮层在前 30% 淡入（收起时后 30% 淡出），遮罩同步。[orb] 为屏幕坐标。
     */
    private fun morphWithOrb(orb: android.graphics.Rect, expand: Boolean, onEnd: () -> Unit) {
        val decor = window.decorView
        sheetAnimator?.cancel()
        val windowTop = screenHeight() - windowHeight
        val start = android.graphics.RectF(orb).apply { offset(0f, -windowTop.toFloat()) }
        val width = decor.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val end = android.graphics.RectF(0f, 0f, width.toFloat(), windowHeight.toFloat())
        val sheetRadius = resources.displayMetrics.density * 28f
        var progress = if (expand) 0f else 1f
        decor.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                val p = progress
                val radius = start.height() / 2f + (sheetRadius - start.height() / 2f) * p
                val left = start.left + (end.left - start.left) * p
                val top = start.top + (end.top - start.top) * p
                val right = start.right + (end.right - start.right) * p
                // 底边随展开伸出窗口外，最终底部圆角被屏幕裁掉（浮层贴底，底部圆角 0）。
                val bottom = start.bottom + (end.bottom + radius - start.bottom) * p
                outline.setRoundRect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt(), radius)
            }
        }
        decor.clipToOutline = true
        decor.translationY = 0f
        sheetAnimator = ValueAnimator.ofFloat(progress, if (expand) 1f else 0f).apply {
            duration = MovoMotion.SLOW.toLong()
            interpolator = EASE_STANDARD
            addUpdateListener {
                progress = it.animatedValue as Float
                decor.invalidateOutline()
                decor.alpha = (progress / 0.3f).coerceIn(0f, 1f)
                setScrim(progress)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (expand || cancelled) resetOrbMorph()
                    if (!cancelled) onEnd()
                }
            })
            start()
        }
    }

    private fun resetOrbMorph() {
        val decor = window.decorView
        decor.clipToOutline = false
        decor.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        decor.alpha = 1f
    }

    /**
     * Movo 要操作其他 App：浮层按 Q4 反向收成悬浮球当前位置（没有悬浮球时收到默认停靠位置）的玻璃圆，再退到后台。
     * 语音会话不中断。减少动画时直接退到后台。
     */
    private fun collapseToOrb(onCollapsed: () -> Unit) {
        if (isReducedMotion(this) || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            onCollapsed()
            return
        }
        resizeAnimator?.cancel()
        morphWithOrb(io.github.mangi.eta.agent.runtime.AgentRuntimeService.orbDiscRectOrDefault(this), expand = false, onEnd = onCollapsed)
    }

    /** 浮层下移 [offset]：遮罩按露出比例同步变淡。 */
    private fun applySheetOffset(offset: Float) {
        window.decorView.translationY = offset
        val height = windowHeight.toFloat().coerceAtLeast(1f)
        setScrim(1f - (offset / height).coerceIn(0f, 1f))
    }

    private fun setScrim(fraction: Float) {
        window.attributes = window.attributes.apply { dimAmount = SCRIM_ALPHA * fraction }
    }

    /** 浮层是模态的：点浮层以外（遮罩）关闭浮层。 */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val decor = window.decorView
        val outside = event.y < 0f || event.x < 0f || event.x > decor.width || event.y > decor.height
        if (outside && event.actionMasked == MotionEvent.ACTION_UP) {
            dismissAnimated()
            return true
        }
        return outside || super.onTouchEvent(event)
    }

    private fun settle(full: Boolean) {
        resizeAnimator?.cancel()
        if (full) {
            expandIntoApp()
            return
        }
        val height = AgentResultSheetSizing.height(screenHeight(), false, keyboardLift)
        resizeAnimator = ValueAnimator.ofInt(windowHeight, height).apply {
            duration = 220
            addUpdateListener { updateHeight(it.animatedValue as Int) }
            start()
        }
    }

    /**
     * 「展开到 App」（Q4，规范 9.5）：浮层上沿推到屏幕顶，顶部圆角 28 → 0、把手淡出，`slow` + `standard`；
     * 到位后无动画切到 App 内同一会话页。减少动画时直接切换。
     */
    private fun expandIntoApp() {
        if (!ready || keyguardGate.locked || mainHandoffToken != null || closing) return
        resizeAnimator?.cancel()
        sheetAnimator?.cancel()
        applySheetOffset(0f)
        val full = screenHeight()
        if (isReducedMotion(this) || windowHeight >= full) {
            expandProgress = 1f
            openInApp()
            return
        }
        val from = windowHeight
        resizeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MovoMotion.SLOW.toLong()
            interpolator = EASE_STANDARD
            addUpdateListener {
                val p = it.animatedValue as Float
                expandProgress = p
                updateHeight((from + (full - from) * p).roundToInt())
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) openInApp() }
            })
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
            expandProgress = 0f
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
            // 浮层已推满全屏，切到 App 内同一会话时不再播窗口动画，看不出切换。
            startActivity(
                // HyperOS 切换任务栈时会忽略自定义动画、播放系统缩放动画（真机出现 1 帧灰色缩小窗口），显式禁用。
                AgentConversationHandoff.intent(this, target, opening?.runId, receiver)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
                android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle(),
            )
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
        private const val SCRIM_ALPHA = 0.32f
        private const val ORB_ORIGIN_TTL_MS = 2_000L
        @Volatile private var orbOrigin: android.graphics.Rect? = null
        @Volatile private var orbOriginAt = 0L

        /** 悬浮球点开浮层前登记：下一次进场从球的位置展开（2 秒内有效）。 */
        fun expandFromOrb(orb: android.graphics.Rect) {
            orbOrigin = android.graphics.Rect(orb)
            orbOriginAt = android.os.SystemClock.uptimeMillis()
        }

        private fun consumeOrbOrigin(): android.graphics.Rect? {
            val origin = orbOrigin
            orbOrigin = null
            return origin?.takeIf { android.os.SystemClock.uptimeMillis() - orbOriginAt <= ORB_ORIGIN_TTL_MS }
        }
        private val EASE_ENTER = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        private val EASE_EXIT = android.view.animation.PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
        private val EASE_STANDARD = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)

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
            // 工具线程等待期间先播「浮层收成悬浮球」（Q4，360ms），再退到后台。
            Handler(Looper.getMainLooper()).post {
                if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) activity.collapseToOrb(hide::run) else hide.run()
            }
            return try { hidden.await(3, TimeUnit.SECONDS) && success.get() }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        }
    }
}
