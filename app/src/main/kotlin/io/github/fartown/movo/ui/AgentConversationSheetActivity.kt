package io.github.fartown.movo.ui

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
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.overlay.AgentConversationSheet
import io.github.fartown.movo.agent.overlay.AgentResultSheetSizing
import io.github.fartown.movo.agent.runtime.AgentConversationHandoff
import io.github.fartown.movo.agent.runtime.AgentConversationTarget
import io.github.fartown.movo.agent.runtime.AgentRuntimeWire
import io.github.fartown.movo.data.repository.AppearanceSettingsRepository
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import io.github.fartown.movo.ui.app.AgentAppSession
import io.github.fartown.movo.ui.app.AgentAppTheme
import io.github.fartown.movo.ui.app.AgentConversationContent
import io.github.fartown.movo.ui.markdown.InAppBrowserUriHandler
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.isReducedMotion
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    /** 系统分享进来的内容（规范 8.9.1）：打开时新建会话并预填进输入框，用完置空。 */
    private var sharedContent by mutableStateOf<io.github.fartown.movo.ui.share.SharedContent?>(null)
    /**
     * 这个浮层是为分享打开的：内容已预填进新会话后，不能再走助手入口的 `voiceConversationId()`，
     * 否则会给这条新会话分配 id，分享提示（只属于未发消息的新会话）随之消失。
     */
    private var shareSession by mutableStateOf(false)
    private var mainHandoffToken: Any? = null
    /** 会话没能打开（规范 8.11：不用 Toast）：内容区显示原因与「重试」，不直接关闭浮层。 */
    private var openError by mutableStateOf<String?>(null)
    /** 点「重试」加一，重新执行打开会话。 */
    private var openAttempt by mutableIntStateOf(0)
    /** 「展开到 App」没成功：头部标题处短暂说明原因（规范 8.11 就地反馈）。 */
    private var headerNotice by mutableStateOf<String?>(null)
    private var resizeAnimator: ValueAnimator? = null
    /** 进场、下拉回弹与退场共用：整块浮层的位移与后方遮罩（规范 8.9 / 9.5）。 */
    private var sheetAnimator: ValueAnimator? = null
    /** 在半屏高度继续往下拖时，浮层整体下移的距离（不压缩内容）。 */
    private var dismissOffset = 0f
    private var closing = false
    /** 这次是从悬浮球点开的：关闭时反向收回球。 */
    private var openedFromOrb = false
    /**
     * 进场动画（从球展开 / 从底部上移）等浮层内容就绪后再开始：冷启动时 Compose 内容要等读完外观设置才挂上，
     * 会话也还没打开；提前开跑会先空白几帧，露出时轮廓已长到半路、里面还是「正在打开对话…」（真机）。
     */
    private var pendingEntrance: (() -> Unit)? = null
    /** 进场已开始：此前内容区的状态切换（打开中 → 会话）直接换，不做淡出淡入。 */
    private var entranceStarted by mutableStateOf(false)
    /** Q4 浮层 → App：推满全屏的进度（驱动顶部圆角与把手）。只在绘制阶段读取。 */
    private var expandProgress by mutableFloatStateOf(0f)
    /*
     * 窗口固定全屏（审查 A1）：拖动、展开、进出场都不再改窗口尺寸与 dimAmount（每帧跨进程 relayout），
     * 浮层的高度在 Compose 布局阶段、位移 / 透明度 / 轮廓 / 遮罩在绘制阶段读取下面这些状态。
     */
    /** 浮层高度（窗口坐标 px，贴屏幕底）；原来的窗口高度。 */
    private var windowHeight by mutableIntStateOf(0)
    /** 浮层整体下移（进场、下拉关闭、退场）。 */
    private var sheetOffset by mutableFloatStateOf(0f)
    /** 后方遮罩 `overlay/scrim` 的显示比例 0–1。 */
    private var scrimFraction by mutableFloatStateOf(0f)
    /** 浮层整体透明度：从球展开前隐藏（裁切起点就位前不露出整块浮层）；不影响遮罩。 */
    private var sheetAlpha by mutableFloatStateOf(1f)
    /**
     * 浮层内容的透明度（Q4 球 ↔ 浮层，Figma 候选「动效全集」A5b）：容器（浮层底色）从球心揭开时始终不透明，
     * 只有内容 0.2 → 1 淡入；收起时内容在末段淡出。只在绘制阶段读取。
     */
    private var sheetContentAlpha by mutableFloatStateOf(1f)
    /** Q4 球 ↔ 浮层：起点（悬浮球玻璃圆，浮层自身坐标）；为 null 时不做轮廓裁切。 */
    private var orbMorphStart by mutableStateOf<android.graphics.RectF?>(null)
    private var orbMorphProgress by mutableFloatStateOf(1f)
    /** 根布局（= 窗口）的高度，用来算浮层顶边在窗口里的位置。 */
    private var rootHeight by mutableIntStateOf(0)
    private var keyboardLift = 0
    private var dragging = false
    private var dragHeight = 0f
    private var pendingKeyboardLift: Int? = null
    private var afterHidden: (() -> Unit)? = null
    private val keyguardGate = KeyguardContentGate(this, ::finish)
    private val microphonePermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { allowed ->
        if (allowed && !isFinishing) io.github.fartown.movo.agent.voice.session.VoiceEntry.startInPlace(this)
        // 不用 Toast（规范 8.11）：原因显示在浮层输入框上方的语音提示里。
        else if (!allowed) io.github.fartown.movo.agent.voice.session.VoiceSessionManager.showNotice(MIC_DENIED_NOTICE)
    }

    private fun startVoiceInput() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            io.github.fartown.movo.agent.voice.session.VoiceEntry.startInPlace(this)
        } else microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        assistantMode = intent.action == ACTION_ASSISTANT
        autoListen = intent.getBooleanExtra(io.github.fartown.movo.agent.voice.MovoAssistantVoiceService.EXTRA_AUTO_LISTEN, false)
        request = AgentConversationHandoff.from(intent)
        if (intent.action == ACTION_SHARE) {
            sharedContent = io.github.fartown.movo.ui.share.SharedContent.fromExtras(intent)
            shareSession = true
        }
        if (request == null && !assistantMode && sharedContent == null) { finish(); return }
        current = WeakReference(this)
        if (assistantMode) keyguardGate.check()
        enableEdgeToEdge()
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // 窗口固定全屏、只设这一次；后方 `overlay/scrim` 32% 由 Compose 画在浮层下面：浮层是模态的，点遮罩关闭浮层（不停止任务）。
        window.attributes = window.attributes.apply { windowAnimations = 0 }
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
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
                        // 键盘避让：只在 snapshotFlow 里读 insets，键盘动画期间不重组整棵树，浮层高度只触发重新布局。
                        val ime = WindowInsets.ime
                        val navigationBars = WindowInsets.navigationBars
                        LaunchedEffect(ime, navigationBars, density) {
                            snapshotFlow { ime.getBottom(density) to navigationBars.getBottom(density) }
                                .collect { (imeBottom, navigationBottom) -> avoidKeyboard(imeBottom, navigationBottom) }
                        }
                        LaunchedEffect(request, assistantMode, sharedContent, shareSession, keyguardGate.locked, openAttempt) {
                            // 锁屏时不加载会话，解锁成功后本 effect 会重新执行。
                            if (keyguardGate.locked) return@LaunchedEffect
                            openError = null
                            sharedContent?.let { shared ->
                                // 分享进来：新会话，内容预填进输入框，自动获焦等用户补一句指令（不自动发送）。
                                sharedContent = null
                                agentState.openSharedContent(shared)
                                ready = true
                                return@LaunchedEffect
                            }
                            // 分享打开的浮层：清空 sharedContent 会让本 effect 重跑，这时会话已就绪，什么都不做。
                            if (shareSession && request == null) {
                                ready = true
                                return@LaunchedEffect
                            }
                            val opening = request
                            if (opening == null) {
                                // 助手入口不新建会话：语音和文字共用当前这条，界面只是它的另一个容器。
                                val opened = runCatching { agentState.voiceConversationId() }
                                ready = opened.isSuccess
                                opened.onFailure { failure ->
                                    // 不用 Toast、不直接关闭（规范 8.11）：内容区显示原因与「重试」。
                                    openError = failure.message ?: getString(R.string.overlay_result_open_failed)
                                }
                                return@LaunchedEffect
                            }
                            val opened = agentState.openResultConversation(opening.target, opening.runId)
                            ready = opened
                            opening.acknowledge(opened)
                            if (!opened) openError = getString(R.string.overlay_result_open_failed)
                        }
                        LaunchedEffect(ready, autoListen, keyguardGate.locked) {
                            if (ready && autoListen && !keyguardGate.locked) {
                                autoListen = false
                                startVoiceInput()
                            }
                        }
                        LaunchedEffect(headerNotice) {
                            if (headerNotice != null) {
                                delay(HEADER_NOTICE_HOLD_MS)
                                headerNotice = null
                            }
                        }
                        LaunchedEffect(Unit) { startEntranceWhenContentReady() }
                        BackHandler { dismissAnimated() }
                        val pane = agentState.conversationPaneState
                        val statusBars = WindowInsets.statusBars
                        val sheetSurface = io.github.fartown.movo.ui.theme.MovoColors.bgCanvas
                        // Miuix 弹出菜单挂在 Scaffold 上：Scaffold 放在铺满窗口的根上，菜单按窗口坐标定位，不被浮层裁切。
                        top.yukonga.miuix.kmp.basic.Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            containerColor = ComposeColor.Transparent,
                            contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
                        ) {
                            Box(Modifier.fillMaxSize().onSizeChanged { rootHeight = it.height }) {
                                // 后方遮罩 `overlay/scrim`：透明度只在绘制阶段读；点遮罩关闭浮层。
                                Box(
                                    Modifier.fillMaxSize()
                                        .graphicsLayer { alpha = scrimFraction }
                                        .background(io.github.fartown.movo.ui.theme.MovoColors.overlayScrim)
                                        .pointerInput(Unit) {
                                            awaitEachGesture {
                                                awaitFirstDown(requireUnconsumed = false)
                                                if (waitForUpOrCancellation() != null) dismissAnimated()
                                            }
                                        },
                                )
                                Box(
                                    Modifier.align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        // 高度在布局阶段读取：拖动、展开、键盘避让都只重新布局浮层，不重组、不改窗口。
                                        .layout { measurable, constraints ->
                                            val height = windowHeight.coerceIn(0, constraints.maxHeight)
                                            val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                                            layout(placeable.width, height) { placeable.place(0, 0) }
                                        }
                                        .graphicsLayer {
                                            translationY = sheetOffset
                                            alpha = sheetAlpha
                                            val start = orbMorphStart
                                            if (start != null) {
                                                shape = orbMorphShape(start, orbMorphProgress, this.size, 28.dp.toPx())
                                                clip = true
                                            } else {
                                                clip = false
                                            }
                                        }
                                        // 揭开期间容器本身不透明（内容另有透明度）：裁切区里先铺一层浮层底色。
                                        .drawBehind { if (orbMorphStart != null) drawRect(sheetSurface) }
                                        // 浮层本身拦下触摸，空白处不落到下面的遮罩上（遮罩点一下会关闭浮层）。
                                        .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } },
                                ) {
                                    val title = pane.conversations.firstOrNull { it.id == pane.selectedConversationId }?.title
                                        ?.takeUnless { keyguardGate.locked }
                                        ?: getString(R.string.app_name)
                                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = sheetContentAlpha }) {
                                        AgentConversationSheet(
                                            title = headerNotice ?: title,
                                            titleIsNotice = headerNotice != null,
                                            onDrag = ::drag,
                                            onDragStopped = ::endDrag,
                                            onOpenConversation = ::expandIntoApp,
                                            onClose = ::dismissAnimated,
                                            expandProgress = { expandProgress },
                                            // 原来窗口只盖住下半屏、收不到状态栏 insets；现在窗口全屏，浮层顶边碰到状态栏时才让出重叠部分。
                                            topInset = {
                                                val root = rootHeight
                                                if (root <= 0) 0 else (statusBars.getTop(density) - (root - windowHeight).coerceAtLeast(0)).coerceAtLeast(0)
                                            },
                                        ) {
                                            SheetBody()
                                        }
                                    }
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
        if (intent.action == ACTION_SHARE) {
            assistantMode = false
            autoListen = false
            request = null
            sharedContent = io.github.fartown.movo.ui.share.SharedContent.fromExtras(intent)
            shareSession = true
            return
        }
        if (intent.action == ACTION_ASSISTANT) {
            shareSession = false
            assistantMode = true
            autoListen = intent.getBooleanExtra(io.github.fartown.movo.agent.voice.MovoAssistantVoiceService.EXTRA_AUTO_LISTEN, false)
            request = null
            keyguardGate.check()
            return
        }
        val next = AgentConversationHandoff.from(intent) ?: return
        shareSession = false
        // 长按完成态的悬浮球：打开结果的同时直接进入语音模式（规范 8.1）。
        if (intent.getBooleanExtra(io.github.fartown.movo.agent.voice.MovoAssistantVoiceService.EXTRA_AUTO_LISTEN, false)) autoListen = true
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

    /** 只改浮层高度状态（布局阶段读取），窗口尺寸不变。 */
    private fun updateHeight(height: Int) {
        windowHeight = height
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
            openedFromOrb = true
            setScrim(0f)
            sheetAlpha = 0f
            pendingEntrance = { morphWithOrb(orb, expand = true) {} }
            return
        }
        if (isReducedMotion(this)) {
            // 减少动画：浮层与遮罩一起淡入 `fast`（遮罩现在画在窗口里，随窗口透明度一起淡入）。
            entranceStarted = true
            decor.alpha = 0f
            decor.animate().alpha(1f).setDuration(MovoMotion.FAST.toLong()).start()
            setScrim(1f)
            return
        }
        sheetOffset = screenHeight().toFloat()
        setScrim(0f)
        pendingEntrance = {
            val from = windowHeight.toFloat().coerceAtLeast(1f)
            animateSheetOffset(from, 0f, MovoMotion.SLOW.toLong(), EASE_ENTER)
        }
    }

    /**
     * 等浮层内容能画出真实内容再开始进场：会话已打开（或失败 / 需解锁），最多等 [ENTRANCE_CONTENT_WAIT_MS]；
     * 再等两帧，让会话内容完成首次布局（滚到最新消息）后才露出。
     */
    private suspend fun startEntranceWhenContentReady() {
        val entrance = pendingEntrance ?: return
        kotlinx.coroutines.withTimeoutOrNull(ENTRANCE_CONTENT_WAIT_MS) {
            snapshotFlow { ready || openError != null || keyguardGate.locked }.first { it }
        }
        repeat(2) { androidx.compose.runtime.withFrameNanos { } }
        if (pendingEntrance !== entrance) return
        pendingEntrance = null
        entranceStarted = true
        if (!closing) entrance()
    }

    /**
     * 关闭浮层（✕、返回、点遮罩、下拉）：先向下退场 `slow-exit`，再结束 Activity；只关浮层，不停止任务。
     * 从悬浮球点开的浮层（没被拖动过）按 Q4 反向收回悬浮球（规范 9.3.2「返回反向」、8.9「浮层收进悬浮球」）。
     */
    private fun dismissAnimated() {
        if (closing) return
        closing = true
        resizeAnimator?.cancel()
        sheetAnimator?.cancel()
        // 还没露出就关闭（进场在等内容）：没有可以反向播放的画面，直接结束。
        if (pendingEntrance != null) {
            pendingEntrance = null
            finish()
            return
        }
        if (isReducedMotion(this)) {
            window.decorView.animate().alpha(0f).setDuration(MovoMotion.FAST_EXIT.toLong())
                .withEndAction { finish() }.start()
            return
        }
        if (openedFromOrb && dismissOffset == 0f) {
            val orb = io.github.fartown.movo.agent.runtime.AgentRuntimeService.orbDiscRect
            if (orb != null) {
                morphWithOrb(orb, expand = false) { finish() }
                return
            }
        }
        val from = sheetOffset
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
     * Q4 球 ↔ 浮层（规范 9.3.2 / 9.5，Figma 候选「动效全集」A5b，与悬浮球展开卡 A1 同一套「从球里揭开」）：
     * 裁切轮廓从悬浮球玻璃圆（圆角 16）长成浮层（顶部圆角 28）。浮层内容按最终布局原位绘制、不缩放不位移，
     * 随容器边缘露出；容器底色从第一帧起不透明，只有内容淡入。[orb] 为屏幕坐标。
     * - 展开：轮廓 `slow` + `standard`；内容 0.2 → 1，240ms + `enter`；遮罩 240ms `standard` 淡入。
     * - 收起：轮廓 250ms + `exit` 原路缩回球心；内容在末段淡出（80ms 后开始，结束前 30ms 淡完）；遮罩 170ms + `exit` 淡出。
     * 轮廓、透明度、遮罩都是 Compose 图层属性，只在绘制阶段读取，不改窗口。
     */
    private fun morphWithOrb(orb: android.graphics.Rect, expand: Boolean, onEnd: () -> Unit) {
        sheetAnimator?.cancel()
        // 屏幕坐标 → 浮层自身坐标：减去窗口在屏幕上的位置和浮层顶边在窗口里的位置。
        val windowOnScreen = IntArray(2).also(window.decorView::getLocationOnScreen)
        val windowSize = rootHeight.takeIf { it > 0 } ?: screenHeight()
        val sheetTop = (windowSize - windowHeight).coerceAtLeast(0)
        orbMorphStart = android.graphics.RectF(orb).apply {
            offset(-windowOnScreen[0].toFloat(), -(windowOnScreen[1] + sheetTop).toFloat())
        }
        sheetOffset = 0f
        // 裁切起点已就位：浮层可以露出（之前为了不闪出整块浮层一直隐藏）。
        sheetAlpha = 1f
        orbMorphProgress = if (expand) 0f else 1f
        val duration = if (expand) MovoMotion.SLOW.toLong() else MovoMotion.SLOW_EXIT.toLong()
        sheetContentAlpha = if (expand) ORB_CONTENT_START_ALPHA else 1f
        sheetAnimator = ValueAnimator.ofFloat(orbMorphProgress, if (expand) 1f else 0f).apply {
            this.duration = duration
            interpolator = if (expand) EASE_STANDARD else EASE_EXIT
            addUpdateListener {
                orbMorphProgress = it.animatedValue as Float
                val elapsed = it.currentPlayTime.toFloat()
                if (expand) {
                    val fade = (elapsed / MovoMotion.STANDARD).coerceIn(0f, 1f)
                    sheetContentAlpha = ORB_CONTENT_START_ALPHA + (1f - ORB_CONTENT_START_ALPHA) * EASE_ENTER.getInterpolation(fade)
                    setScrim(EASE_STANDARD.getInterpolation(fade))
                } else {
                    val fadeSpan = (duration - ORB_CONTENT_FADE_OUT_DELAY_MS - ORB_CONTENT_FADE_OUT_TAIL_MS).coerceAtLeast(1L)
                    val fade = ((elapsed - ORB_CONTENT_FADE_OUT_DELAY_MS) / fadeSpan).coerceIn(0f, 1f)
                    sheetContentAlpha = 1f - EASE_EXIT.getInterpolation(fade)
                    setScrim(1f - EASE_EXIT.getInterpolation((elapsed / MovoMotion.STANDARD_EXIT).coerceIn(0f, 1f)))
                }
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
        orbMorphStart = null
        orbMorphProgress = 1f
        sheetAlpha = 1f
        sheetContentAlpha = 1f
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
        morphWithOrb(io.github.fartown.movo.agent.runtime.AgentRuntimeService.orbDiscRectOrDefault(this), expand = false, onEnd = onCollapsed)
    }

    /** 浮层下移 [offset]：遮罩按露出比例同步变淡。 */
    private fun applySheetOffset(offset: Float) {
        sheetOffset = offset
        val height = windowHeight.toFloat().coerceAtLeast(1f)
        setScrim(1f - (offset / height).coerceIn(0f, 1f))
    }

    /** 遮罩比例（绘制阶段读取）；不再改窗口 `dimAmount`。 */
    private fun setScrim(fraction: Float) {
        scrimFraction = fraction
    }

    private fun settle(full: Boolean) {
        resizeAnimator?.cancel()
        if (full) {
            expandIntoApp()
            return
        }
        val height = AgentResultSheetSizing.height(screenHeight(), false, keyboardLift)
        resizeAnimator = ValueAnimator.ofInt(windowHeight, height).apply {
            duration = MovoMotion.STANDARD.toLong()
            interpolator = EASE_STANDARD
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
            // 不用 Toast（规范 8.11）：在头部标题处短暂说明，浮层回到半屏。
            headerNotice = getString(R.string.overlay_result_open_failed)
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
            // 主界面是从桌面图标等系统入口拉起的：把它换成由 Movo 自己拉起的新实例，否则切过去时系统会插一个整屏启动画面（真机深灰一闪）。
            AgentConversationHandoff.releaseSystemLaunchedMain(this, taskId)
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

    private enum class SheetBodyState { OPENING, LOCKED, FAILED, READY }

    /** 打开失败时最后一条原因：交叉淡化退出失败态期间沿用，不闪空白。 */
    private var lastOpenError = ""

    /** 浮层内容区：「正在打开会话 / 解锁提示 / 打开失败 + 重试」与会话内容之间交叉淡化 `standard`（审查 B14）。 */
    @androidx.compose.runtime.Composable
    private fun SheetBody() {
        openError?.let { lastOpenError = it }
        val body = when {
            keyguardGate.locked -> SheetBodyState.LOCKED
            ready -> SheetBodyState.READY
            openError != null -> SheetBodyState.FAILED
            else -> SheetBodyState.OPENING
        }
        // 先淡出再淡入（不交叉）：交叉淡化时「正在打开对话…」会和已加载的消息叠在一起（真机）。
        androidx.compose.animation.AnimatedContent(
            targetState = body,
            transitionSpec = {
                // 进场前（浮层还没露出）直接换，露出的第一帧就是最终内容。
                if (!entranceStarted) {
                    androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                } else androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core.tween(
                        MovoMotion.STANDARD,
                        delayMillis = MovoMotion.FAST_EXIT,
                        easing = MovoMotion.EasingStandard,
                    ),
                ) togetherWith androidx.compose.animation.fadeOut(MovoMotion.fastExit())
            },
            label = "sheetBody",
        ) { current ->
            if (current == SheetBodyState.READY) {
                // 与 App 一致（规范 8.9）：失败卡的「查看日志」先展开到 App，再在 App 里打开运行日志。
                androidx.compose.runtime.CompositionLocalProvider(
                    io.github.fartown.movo.ui.screens.diagnostics.LocalRunLogOpener provides { runId ->
                        io.github.fartown.movo.ui.app.AppHandoffRoute.request(
                            runId?.let(io.github.fartown.movo.ui.navigation.AppRoute::DiagnosticsRun)
                                ?: io.github.fartown.movo.ui.navigation.AppRoute.Diagnostics,
                        )
                        expandIntoApp()
                    },
                    // 同上：「去模型设置」（失败卡）与「去配置」（未配置模型提示）展开到 App 后打开设置里的模型页。
                    io.github.fartown.movo.ui.components.LocalOpenModelSettings provides {
                        io.github.fartown.movo.ui.app.AppHandoffRoute.request(io.github.fartown.movo.ui.navigation.AppRoute.ModelProviders)
                        expandIntoApp()
                    },
                ) {
                    AgentConversationContent(
                        agentState = agentState,
                        onOpenBrowser = ::openBrowser,
                        onNavigateBack = ::finish,
                        initiallyShowLatestMessage = true,
                    )
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            when (current) {
                                SheetBodyState.LOCKED -> getString(R.string.overlay_unlock_to_continue)
                                SheetBodyState.FAILED -> lastOpenError
                                else -> getString(R.string.overlay_result_opening_conversation)
                            },
                            textAlign = TextAlign.Center,
                        )
                        if (current == SheetBodyState.FAILED) {
                            Spacer(Modifier.height(12.dp))
                            io.github.fartown.movo.ui.components.movo.MovoPillButton(
                                label = getString(R.string.action_retry),
                                onClick = {
                                    openError = null
                                    openAttempt++
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** 浮层里点麦克风被拒绝时的提示（原来的 Toast 文案）。 */
        private const val MIC_DENIED_NOTICE = "未获得麦克风权限，可以继续文字输入"
        /** 「展开到 App」失败的说明在头部停留的时长。 */
        private const val HEADER_NOTICE_HOLD_MS = 4_000L
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
        /** 从球揭开时内容的起始透明度（A5b：内容从 20% 淡入，一开始就能看出是对话）。 */
        private const val ORB_CONTENT_START_ALPHA = 0.2f
        /** 进场最多等会话打开这么久；更慢时先露出「正在打开对话…」。 */
        private const val ENTRANCE_CONTENT_WAIT_MS = 400L
        /** 收回球里时内容淡出的起点与收尾（相对收起开始 / 结束）。 */
        private const val ORB_CONTENT_FADE_OUT_DELAY_MS = 80L
        private const val ORB_CONTENT_FADE_OUT_TAIL_MS = 30L
        private val EASE_ENTER = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        private val EASE_EXIT = android.view.animation.PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
        private val EASE_STANDARD = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)

        /** 系统入口（唤醒词 / 电源键 / 助手手势）打开助手界面时使用。 */
        const val ACTION_ASSISTANT = "io.github.fartown.movo.ui.ASSISTANT"
        /** 分享接收（`ShareReceiverActivity`）转交内容时使用；本 Activity 不对外导出。 */
        const val ACTION_SHARE = "io.github.fartown.movo.ui.SHARE"
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

/**
 * Q4 球 ↔ 浮层的裁切轮廓（浮层自身坐标）：从悬浮球玻璃圆 [start]（圆角 = 半高）插值到整块浮层（顶部圆角 [sheetRadius]）；
 * 底边随展开伸出浮层外，最终底部圆角落在屏幕外（浮层贴底，底部圆角 0）。
 */
internal fun orbMorphShape(start: android.graphics.RectF, progress: Float, size: Size, sheetRadius: Float): Shape {
    val p = progress
    val radius = start.height() / 2f + (sheetRadius - start.height() / 2f) * p
    val left = start.left + (0f - start.left) * p
    val top = start.top + (0f - start.top) * p
    val right = start.right + (size.width - start.right) * p
    val bottom = start.bottom + (size.height + radius - start.bottom) * p
    return OrbMorphShape(RoundRect(left, top, right, bottom, CornerRadius(radius)))
}

private class OrbMorphShape(private val rect: RoundRect) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = Outline.Rounded(rect)
}
