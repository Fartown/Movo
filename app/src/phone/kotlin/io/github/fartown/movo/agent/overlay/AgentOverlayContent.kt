package io.github.fartown.movo.agent.overlay

import android.graphics.BlurMaskFilter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.togetherWith
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.liveRegion
import android.view.MotionEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.voice.session.VoiceChannel
import io.github.fartown.movo.agent.voice.session.VoiceSessionUiState
import io.github.fartown.movo.ui.components.movo.MovoEntrance
import io.github.fartown.movo.ui.components.movo.MovoOrb
import io.github.fartown.movo.ui.components.movo.MovoSpinner
import io.github.fartown.movo.ui.components.movo.PressKind
import io.github.fartown.movo.ui.components.movo.VoiceModeSplitControl
import io.github.fartown.movo.ui.components.movo.VoiceModeSplitControlSize
import io.github.fartown.movo.ui.components.movo.movoClickable
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIconData
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text

/*
 * App 外浮窗（规范 7.1 `Material/Glass`、8.1 `Overlay/Orb` / `Overlay/Panel` / 屏幕边缘光晕、9.5）。
 * 跨窗口背景模糊需要窗口级 API，这里统一用降级材质 glass/surface-fallback（白 88%，不模糊）。
 */

private val GlassSurface = MovoColors.glassSurfaceFallback

/**
 * 屏幕边缘光晕：沿屏幕圆角的品牌渐变柔光（2 宽描边 + 10 宽、模糊 14 的光晕），不压暗屏幕内容。
 * 执行中渐变沿周长 12s 一圈 `linear` + 亮度 70%–100% `ambient` 往复；暂停降到 40% 并停止流动；
 * 结束淡出 `standard`；减少动画时静态 80%。全屏触摸穿透，截图时被无障碍窗口过滤。
 */
@Composable
internal fun AgentOverlayGlow(state: AgentOverlayState) {
    val reduced = LocalReducedMotion.current
    val phase = state.phase
    val targetAlpha = when (phase) {
        AgentOverlayPhase.RUNNING -> 1f
        AgentOverlayPhase.PAUSED -> 0.4f
        else -> 0f
    }
    // 动画值都保存为 State，只在 graphicsLayer / Canvas 绘制 lambda 里读 `.value`：执行中光晕每帧只重画，不重组（审查 A6）。
    val alpha = animateFloatAsState(targetAlpha, MovoMotion.standard(), label = "glowAlpha")
    val shown by remember { derivedStateOf { alpha.value > 0.001f } }
    if (!shown) return
    val flowing = phase == AgentOverlayPhase.RUNNING && !reduced
    val rotation: State<Float>?
    val breath: State<Float>?
    if (flowing) {
        val transition = rememberInfiniteTransition(label = "glow")
        rotation = transition.animateFloat(
            0f, 360f,
            infiniteRepeatable(tween(MovoMotion.ORB_GRADIENT_PERIOD, easing = MovoMotion.EasingLinear)),
            label = "glowRotation",
        )
        breath = transition.animateFloat(
            0.7f, 1f,
            infiniteRepeatable(tween(MovoMotion.AMBIENT / 2, easing = MovoMotion.EasingStandard), RepeatMode.Reverse),
            label = "glowBreath",
        )
    } else {
        rotation = null
        breath = null
    }
    val staticBreath = if (reduced && phase == AgentOverlayPhase.RUNNING) 0.8f else 1f
    val colors = remember { (MovoColors.brandGradient + MovoColors.brandGradient.first()).map { it.toArgb() }.toIntArray() }
    // 光晕画笔复用，不在每帧新建 Paint / Shader（盘点 B11）。
    val glowPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            // 半分辨率的光圈图放大绘制时做双线性过滤，边缘不起锯齿。
            isFilterBitmap = true
        }
    }
    val shaderMatrix = remember { android.graphics.Matrix() }
    val cache = remember { GlowCache() }
    Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value * (breath?.value ?: staticBreath) }) {
        val corner = 36.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawIntoCanvas { canvas ->
            val shader = cache.shader(cx, cy, colors)
            shaderMatrix.setRotate(rotation?.value ?: 0f, cx, cy)
            shader.setLocalMatrix(shaderMatrix)
            glowPaint.shader = shader
            // 柔光：10 宽、模糊 14，向内渐隐。模糊后的光圈只按尺寸算一次（半分辨率透明度图），之后每帧只用旋转的
            // 渐变给这张图着色，由 GPU 贴图；不再每帧对全屏描边做模糊（模糊滤镜走 CPU 光栅化，执行中一直在耗）。
            glowPaint.maskFilter = null
            val mask = cache.glowMask(size.width, size.height, corner, 10.dp.toPx(), 14.dp.toPx(), 5.dp.toPx())
            cache.dst.set(0f, 0f, size.width, size.height)
            canvas.nativeCanvas.drawBitmap(mask, null, cache.dst, glowPaint)
            // 实线：2 宽。
            glowPaint.strokeWidth = 2.dp.toPx()
            val edge = 1.dp.toPx()
            canvas.nativeCanvas.drawRoundRect(edge, edge, size.width - edge, size.height - edge, corner, corner, glowPaint)
        }
    }
}

/** 光晕的渐变与模糊滤镜只在尺寸变化时重建。 */
private class GlowCache {
    private var shader: android.graphics.SweepGradient? = null
    private var shaderKey = 0L
    private var blur: BlurMaskFilter? = null
    private var blurRadius = -1f

    fun shader(cx: Float, cy: Float, colors: IntArray): android.graphics.SweepGradient {
        val key = (cx.toLong() shl 32) or cy.toLong()
        return shader?.takeIf { shaderKey == key } ?: android.graphics.SweepGradient(cx, cy, colors, null).also {
            shader = it
            shaderKey = key
        }
    }

    fun blur(radius: Float): BlurMaskFilter =
        blur?.takeIf { blurRadius == radius } ?: BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL).also {
            blur = it
            blurRadius = radius
        }

    val dst = android.graphics.RectF()
    private var mask: android.graphics.Bitmap? = null
    private var maskKey = 0L

    /**
     * 模糊光圈的透明度图（ALPHA_8，半分辨率）：画一次、按尺寸缓存。绘制时 paint 的渐变着色器决定颜色
     * （只有透明度的位图按 paint 的颜色 / 着色器上色）。
     */
    fun glowMask(width: Float, height: Float, corner: Float, stroke: Float, blurRadius: Float, inset: Float): android.graphics.Bitmap {
        val key = (width.toLong() shl 32) or height.toLong()
        mask?.takeIf { maskKey == key && !it.isRecycled }?.let { return it }
        val scale = MASK_SCALE
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ALPHA_8)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = stroke * scale
            maskFilter = BlurMaskFilter(blurRadius * scale, BlurMaskFilter.Blur.NORMAL)
        }
        val i = inset * scale
        canvas.drawRoundRect(i, i, w - i, h - i, corner * scale, corner * scale, paint)
        mask?.recycle()
        mask = bitmap
        maskKey = key
        return bitmap
    }

    private companion object {
        const val MASK_SCALE = 0.5f
    }
}

/**
 * 悬浮球 `Overlay/Orb`（规范 8.1 / 9.5）：32 半透明圆（热区 44）内含 22 光球，外圈 1.5 状态环 + 右上 14 角标：
 * 执行中 = Indigo 弧线旋转（1200ms 一圈）；暂停 = 灰环 + 光球去饱和 + ‖ 角标；聆听 = Indigo 整环 + 波纹 + 声波角标；
 * 失败 = Rose 整环 + ! 角标 + 左右抖动 2 两次；完成 = Green 整环 + ✓ 角标（一直保留到用户点开）；待命 = 只有玻璃圆 + 光球。
 * 手势：点击 [onTap]；按住 300ms [onLongPress]（长按触感）；按住后移动超过 8 进入拖动（不再触发长按）。
 * 拖动用屏幕坐标，窗口跟手移动时手指相对窗口的位置不变，不能用窗口内坐标算位移。
 * [shown] 变为 false 时缩小到 0.5 并淡出 170ms + `exit`（移除悬浮球时由调用方等退场播完再移除窗口）。
 */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun AgentOverlayOrb(
    mode: OrbMode,
    onTap: () -> Unit,
    onLongPress: () -> Unit = {},
    onDragStart: () -> Unit = {},
    onDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    shown: Boolean = true,
    engaged: Boolean = false,
    hearing: Boolean = false,
    longRun: Boolean = false,
    animateEntrance: Boolean = true,
    /**
     * 展开卡揭开时把真球淡出到 0；收起时揭开逆放到接近球位置时再淡回。这样看起来是**同一颗球在变形**，
     * 而不是“真球 + 从真球后面钻出来一张卡”。
     * `null`（默认）时全程按 [shown] 显示，不受揭开动画影响，保持旧行为兼容测试与预览。
     */
    hideForReveal: Boolean = false,
) {
    val reduced = LocalReducedMotion.current
    // Q8：看着它从执行中变为完成、且任务用时 ≥ 10 秒时，光球亮度 1 → 1.3 → 1（600ms），只播一次。
    var sawActive by remember { mutableStateOf(false) }
    if (mode == OrbMode.RUNNING || mode == OrbMode.PAUSED || mode == OrbMode.LISTENING) sawActive = true
    val brighten = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(mode) {
        if (mode == OrbMode.FINISHED && sawActive && longRun && !reduced) {
            sawActive = false
            brighten.animateTo(0.3f, tween(300, easing = MovoMotion.EasingStandard))
            brighten.animateTo(0f, tween(300, easing = MovoMotion.EasingStandard))
        }
    }
    // 浮窗重建（无障碍服务重连）时球本来就在屏幕上，直接显示，不再从 0.5 放大进场。
    var entered by remember { mutableStateOf(!animateEntrance) }
    LaunchedEffect(Unit) { entered = true }
    var dragging by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val lift = animateFloatAsState(
        when {
            reduced -> 1f
            engaged -> 1.1f
            dragging -> 1.08f
            pressed -> 0.95f
            else -> 1f
        },
        MovoMotion.fast(),
        label = "orbLift",
    )
    // 失败：左右抖动 2、两次（减少动画时不抖）。
    val shake = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(mode) {
        if (mode == OrbMode.FAILED && !reduced) {
            for (target in floatArrayOf(2f, -2f, 2f, -2f, 0f)) shake.animateTo(target, tween(50, easing = MovoMotion.EasingStandard))
        }
    }
    val tapLabel = stringResource(R.string.movo_overlay_orb)
    val scope = rememberCoroutineScope()
    val touchSlop = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
    val pointer = remember(scope, touchSlop) { OrbPointerHandler(scope, touchSlop) }
    pointer.onTap = onTap
    pointer.onLongPress = onLongPress
    pointer.onDragStart = onDragStart
    pointer.onDrag = onDrag
    pointer.onDragEnd = onDragEnd
    pointer.onPressedChange = { pressed = it }
    pointer.onDraggingChange = { dragging = it }
    // 展开卡揭开时让真球淡出；收起时先保持隐藏，等卡片缩到末段再淡回，避免“真球 + 残卡”双影。
    val revealAlpha = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(hideForReveal, reduced) {
        when {
            reduced -> revealAlpha.snapTo(if (hideForReveal) 0f else 1f)
            hideForReveal -> revealAlpha.animateTo(0f, tween(MovoMotion.FAST))
            else -> {
                delay(PANEL_MORPH_OUT_MS - 80L)
                revealAlpha.animateTo(1f, tween(80))
            }
        }
    }
    AnimatedVisibility(
        visible = entered && shown,
        enter = fadeIn(MovoMotion.fast()) + if (reduced) fadeIn(snap()) else scaleIn(MovoMotion.gentle(), initialScale = 0.5f),
        exit = fadeOut(tween(MovoMotion.STANDARD_EXIT, easing = MovoMotion.EasingExit)) +
            if (reduced) fadeOut(snap()) else scaleOut(tween(MovoMotion.STANDARD_EXIT, easing = MovoMotion.EasingExit), targetScale = 0.5f),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer {
                    translationX = shake.value * density
                    alpha = revealAlpha.value
                }
                .semantics {
                    contentDescription = tapLabel
                    onClick { onTap(); true }
                    onLongClick { onLongPress(); true }
                }
                .pointerInteropFilter { event ->
                    pointer.onTouch(event)
                    true
                },
            contentAlignment = Alignment.Center,
        ) {
            if (mode == OrbMode.LISTENING) OrbRipple(hearing)
            val shadowAlpha = if (dragging) 0.18f else 0.10f
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .graphicsLayer { scaleX = lift.value; scaleY = lift.value }
                    .dropShadow(CircleShape, Shadow(radius = 16.dp, offset = DpOffset(0.dp, 6.dp), color = MovoColors.shadow, alpha = shadowAlpha))
                    .clip(CircleShape)
                    .background(GlassSurface)
                    .border(0.5.dp, MovoColors.borderHairline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                val desaturate = mode == OrbMode.PAUSED
                val saturation = animateFloatAsState(if (desaturate) 0f else 1f, MovoMotion.fast(), label = "orbSaturation")
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            val value = saturation.value
                            alpha = if (value < 1f) 0.7f + 0.3f * value else 1f
                        }
                        .drawWithContent {
                            drawContent()
                            val b = brighten.value
                            if (b > 0f) drawCircle(Color.White.copy(alpha = b), blendMode = androidx.compose.ui.graphics.BlendMode.Screen)
                        },
                ) {
                    // 待命时光球为静态渐变、不再逐帧请求绘制（审查 A14）；只有执行中 / 聆听在转。
                    MovoOrb(size = 22.dp, animated = mode == OrbMode.RUNNING || mode == OrbMode.LISTENING, logo = true)
                }
                OrbStatusRing(mode)
            }
            OrbBadge(mode, modifier = Modifier.align(Alignment.TopEnd).offset(x = (-3).dp, y = 3.dp))
        }
    }
}

/**
 * 悬浮球的按下 / 长按 / 拖动判定（屏幕坐标），纯状态机：悬浮球本身与展开卡的球侧通道共用同一套判定。
 * 按住超过 [touchSlop] 进入拖动（不再触发长按）；长按由调用方计时后调 [longPressTimeout]。
 */
internal class OrbGesture(private val touchSlop: Float) {
    enum class State { IDLE, PENDING, LONG_PRESSED, DRAGGING }

    sealed interface Action {
        data object Tap : Action
        data object LongPress : Action
        data object DragStart : Action
        data class Drag(val dx: Float, val dy: Float) : Action
        data object DragEnd : Action
    }

    var state = State.IDLE
        private set
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    fun down(x: Float, y: Float) {
        state = State.PENDING
        downX = x; downY = y; lastX = x; lastY = y
    }

    /** 移动：超过触摸阈值时先给出 [Action.DragStart]，拖动中每次给出相对上一次的位移。 */
    fun move(x: Float, y: Float): List<Action> {
        val actions = mutableListOf<Action>()
        if (state == State.PENDING && kotlin.math.hypot(x - downX, y - downY) > touchSlop) {
            state = State.DRAGGING
            actions += Action.DragStart
        }
        val dx = x - lastX
        val dy = y - lastY
        lastX = x; lastY = y
        if (state == State.DRAGGING) actions += Action.Drag(dx, dy)
        return actions
    }

    /** 按住到长按时长：还在按下未移动时成为长按。 */
    fun longPressTimeout(): Action? =
        if (state == State.PENDING) {
            state = State.LONG_PRESSED
            Action.LongPress
        } else {
            null
        }

    /** 抬起（[cancelled] = 手势被取消）：按下未移动 → 点按；拖动中 → 拖动结束。 */
    fun up(cancelled: Boolean): Action? {
        val action = when (state) {
            State.PENDING -> if (cancelled) null else Action.Tap
            State.DRAGGING -> Action.DragEnd
            else -> null
        }
        state = State.IDLE
        return action
    }
}

/** 把触摸事件交给 [OrbGesture] 并回调；长按按 `MovoMotion.LONG_PRESS` 计时。回调每次重组时更新。 */
private class OrbPointerHandler(
    private val scope: kotlinx.coroutines.CoroutineScope,
    touchSlop: Float,
) {
    private val gesture = OrbGesture(touchSlop)
    private var longPressJob: kotlinx.coroutines.Job? = null
    var onTap: () -> Unit = {}
    var onLongPress: () -> Unit = {}
    var onDragStart: () -> Unit = {}
    var onDrag: (Float, Float) -> Unit = { _, _ -> }
    var onDragEnd: () -> Unit = {}
    var onPressedChange: (Boolean) -> Unit = {}
    var onDraggingChange: (Boolean) -> Unit = {}

    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture.down(event.rawX, event.rawY)
                onPressedChange(true)
                longPressJob?.cancel()
                longPressJob = scope.launch {
                    delay(MovoMotion.LONG_PRESS.toLong())
                    if (gesture.longPressTimeout() != null) {
                        onPressedChange(false)
                        onLongPress()
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> gesture.move(event.rawX, event.rawY).forEach { action ->
                when (action) {
                    OrbGesture.Action.DragStart -> {
                        longPressJob?.cancel()
                        onPressedChange(false)
                        onDraggingChange(true)
                        onDragStart()
                    }
                    is OrbGesture.Action.Drag -> onDrag(action.dx, action.dy)
                    else -> Unit
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressJob?.cancel()
                onPressedChange(false)
                when (gesture.up(cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL)) {
                    OrbGesture.Action.Tap -> onTap()
                    OrbGesture.Action.DragEnd -> {
                        onDraggingChange(false)
                        onDragEnd()
                    }
                    else -> Unit
                }
            }
        }
    }
}

/**
 * 展开卡球侧通道里的按下点是否落在悬浮球的命中区（球窗口 44，以球心为中心）。[orbCenter] 为屏幕坐标；没有球时为 false。
 * 落在球上的按下按悬浮球处理（点按、长按、拖动），通道其余部分点一下收起展开卡。
 */
internal fun orbLaneHit(x: Float, y: Float, orbCenter: Offset?, halfSizePx: Float): Boolean =
    orbCenter != null && kotlin.math.abs(x - orbCenter.x) <= halfSizePx && kotlin.math.abs(y - orbCenter.y) <= halfSizePx

/**
 * 聆听态的波纹：44 淡环（Indigo 22%，1 宽）；听到说话时再有一道从 32 扩散到 44 并淡出的波纹（`ambient` 循环，
 * 减少动画时不扩散）。设计稿外圈还有一道 54 的淡环，超出 44 热区窗口，这里不画。
 */
@Composable
private fun OrbRipple(hearing: Boolean) {
    val reduced = LocalReducedMotion.current
    val spreading = hearing && !reduced
    // 进度只在 Canvas 绘制 lambda 里读（审查 A6）。
    val progressState: State<Float>? = if (spreading) {
        val transition = rememberInfiniteTransition(label = "orbRipple")
        transition.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(MovoMotion.ORB_ARC_PERIOD, easing = MovoMotion.EasingStandard)),
            label = "orbRippleProgress",
        )
    } else {
        null
    }
    Canvas(modifier = Modifier.size(44.dp)) {
        val stroke = 1.dp.toPx()
        drawCircle(MovoColors.indigoFg.copy(alpha = 0.22f), radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
        val progress = progressState?.value ?: -1f
        if (progress >= 0f) {
            val from = 16.dp.toPx()
            val to = size.minDimension / 2 - stroke / 2
            drawCircle(
                MovoColors.indigoFg.copy(alpha = 0.3f * (1f - progress)),
                radius = from + (to - from) * progress,
                style = Stroke(stroke),
            )
        }
    }
}

@Composable
private fun OrbStatusRing(mode: OrbMode) {
    val reduced = LocalReducedMotion.current
    val color = animateColorAsState(
        when (mode) {
            OrbMode.RUNNING, OrbMode.LISTENING -> MovoColors.indigoFg
            OrbMode.PAUSED -> MovoColors.textTertiary
            OrbMode.FINISHED -> MovoColors.greenFg
            OrbMode.FAILED -> MovoColors.roseFg
            OrbMode.STANDBY -> MovoColors.indigoFg.copy(alpha = 0f)
        },
        MovoMotion.fast(),
        label = "orbRing",
    )
    val spinning = mode == OrbMode.RUNNING && !reduced
    // 状态弧的角度与颜色只在 Canvas 绘制 lambda 里读（审查 A6）：执行中每帧只重画这一个 32 的环。
    val rotation: State<Float>? = if (spinning) {
        val transition = rememberInfiniteTransition(label = "orbArc")
        transition.animateFloat(
            0f, 360f,
            infiniteRepeatable(tween(MovoMotion.ORB_ARC_PERIOD, easing = MovoMotion.EasingLinear)),
            label = "orbArcRotation",
        )
    } else {
        null
    }
    Canvas(modifier = Modifier.size(32.dp)) {
        val color = color.value
        if (color.alpha <= 0.001f) return@Canvas
        val stroke = 1.5.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        if (spinning) {
            rotate(rotation?.value ?: 0f) {
                drawArc(color, -90f, 90f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        } else {
            drawArc(color, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        }
    }
}

/** 右上 14 角标：暂停 ‖（bg/inverse）、聆听 声波（Indigo）、失败 !（Rose）、完成 ✓（Green）；执行中与待命不显示。 */
@Composable
private fun OrbBadge(mode: OrbMode, modifier: Modifier = Modifier) {
    Crossfade(targetState = mode, animationSpec = MovoMotion.fast(), modifier = modifier, label = "orbBadge") { current ->
        val (bg, icon) = when (current) {
            OrbMode.PAUSED -> MovoColors.bgInverse to MovoIcons.Pause
            OrbMode.LISTENING -> MovoColors.indigoFg to MovoIcons.AudioLines
            OrbMode.FAILED -> MovoColors.roseFg to null
            OrbMode.FINISHED -> MovoColors.greenFg to MovoIcons.Check
            OrbMode.RUNNING, OrbMode.STANDBY -> return@Crossfade
        }
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(bg)
                .border(1.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                MovoIcon(icon, null, size = 8.dp, tint = Color.White)
            } else {
                Text("!", style = MovoTypography.numericBadge, color = Color.White)
            }
        }
    }
}

/**
 * 移除区 `Overlay/RemoveZone`（规范 9.5「悬浮球 · 移除」，Figma M5「13 拖到移除区」）：拖动悬浮球时屏幕底部中间
 * 淡入 48 玻璃圆 + ✕ 20；球拖入时吸附到区域中心并放大 1.1（由悬浮球自己表现，盖住 ✕）。窗口触摸穿透。
 */
@Composable
internal fun AgentOverlayRemoveZone(visible: Boolean) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    AnimatedVisibility(
        visible = entered && visible,
        enter = fadeIn(MovoMotion.fast()),
        exit = fadeOut(MovoMotion.fastExit()),
    ) {
        Box(
            modifier = Modifier
                .padding(12.dp)
                .size(48.dp)
                .dropShadow(CircleShape, Shadow(radius = 32.dp, spread = (-8).dp, offset = DpOffset(0.dp, 12.dp), color = MovoColors.shadow, alpha = 0.10f))
                .dropShadow(CircleShape, Shadow(radius = 3.dp, offset = DpOffset(0.dp, 1.dp), color = MovoColors.shadow, alpha = 0.05f))
                .clip(CircleShape)
                .background(GlassSurface)
                .border(0.5.dp, MovoColors.borderHairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MovoIcon(MovoIcons.X, stringResource(R.string.movo_overlay_remove), size = 20.dp, tint = MovoColors.textPrimary)
        }
    }
}

/**
 * 悬浮球展开卡 `Overlay/Panel`：宽 224、内边距 4、圆角 16、玻璃材质。
 * 「第 N 步·动作」+ 计时 → 最近 3 步（12 图标 + Micro/Medium）→ 底部操作行（紧凑档 24，热区 32）：
 * 左 = 打字补充（键盘）、语音对话（声波）；右 = 运行中「‖ 暂停」，暂停后「结束任务」+「▶ 继续」。同一时刻只出现一种「停」。
 * 暂停态：标题「已暂停·第 N 步」、计时冻结改次要色、当前步图标换成 ‖。
 * 进场从悬浮球里长出来（2026-09-27 定）：卡片先是与悬浮球重合的 32 圆，以球心为锚点放大成卡片，圆角由圆过渡到 16，
 * 内容在 45%–100% 淡入（`slow` + `standard`，前后匀速，看得出从球里长出来）；收起反向缩回球里（140ms + `exit`）。
 * 减少动画时只淡入淡出。
 * 语音对话进行中（[voice] active）切到语音模式（Figma「展开卡（语音模式）」）：状态行 → 字幕（最多 3 行，顶部淡出）→
 * 上下文 → 操作行（左「切回文字」，右不变）；内容区交叉淡化、高度同步 `standard`，操作行原位不动。
 */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun AgentOverlayBubble(
    state: AgentOverlayState,
    onCollapse: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onSupplementModeChange: (Boolean) -> Unit,
    onSupplement: (String) -> Unit,
    /** 补充输入框被点（键盘收起后再弹出）：让窗口按缓存的键盘高度提前上移。 */
    onSupplementKeyboardRequested: () -> Unit = {},
    anchorEnd: Boolean = true,
    visible: Boolean = true,
    onInteraction: () -> Unit = {},
    voice: VoiceSessionUiState = VoiceSessionUiState(),
    onStartVoice: () -> Unit = {},
    onEndVoice: () -> Unit = {},
    onOpenResult: () -> Unit = {},
    /** 展开卡状态说明（规范 8.1 / 8.11：悬浮窗的失败提示写进展开卡，不用 Toast），例如缺麦克风权限、打不开对话。 */
    notice: String? = null,
    /**
     * 悬浮球在屏幕上的中心（px），揭开动画从这一点径向长成卡片。
     * `null` 时按“球贴在卡片外沿正外侧”的默认几何近似。
     */
    orbCenterOnScreen: () -> Offset? = { null },
    /**
     * 展开卡的球侧通道盖住了（此时隐形的）真球：落在球上的点按、长按、拖动转给悬浮球的处理逻辑，
     * 与没展开时点球一致（例如失败态点球打开结果、长按开始语音、拖动挪球）。
     */
    onOrbTap: () -> Unit = onCollapse,
    onOrbLongPress: () -> Unit = {},
    onOrbDragStart: () -> Unit = {},
    onOrbDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    onOrbDragEnd: () -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val scope = rememberCoroutineScope()
    val laneTouchSlop = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
    val orbHalfPx = with(androidx.compose.ui.platform.LocalDensity.current) { 22.dp.toPx() }
    val laneOrbPointer = remember(scope, laneTouchSlop) { OrbPointerHandler(scope, laneTouchSlop) }
    laneOrbPointer.onTap = onOrbTap
    laneOrbPointer.onLongPress = onOrbLongPress
    laneOrbPointer.onDragStart = onOrbDragStart
    laneOrbPointer.onDrag = onOrbDrag
    laneOrbPointer.onDragEnd = onOrbDragEnd
    val laneCollapseGesture = remember(laneTouchSlop) { OrbGesture(laneTouchSlop) }
    /** 本次按下是否落在真球上（按下时判定，整个手势沿用）。 */
    val laneTargetsOrb = remember { BooleanArray(1) }
    var supplementMode by remember { mutableStateOf(false) }
    var supplementText by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun enterSupplementMode() {
        onInteraction()
        onSupplementModeChange(true)
        supplementMode = true
    }

    fun enterSupplementModeFromVoice() {
        onInteraction()
        onEndVoice()
        onSupplementModeChange(true)
        supplementMode = true
    }

    fun exitSupplementMode() {
        onSupplementModeChange(false)
        supplementMode = false
        supplementText = ""
    }

    // 补充输入需要窗口可获焦；切回不可获焦前先收键盘（80ms，保留原有顺序）。
    fun closeSupplementMode() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        scope.launch {
            delay(80)
            exitSupplementMode()
        }
    }

    fun submitSupplement() {
        val text = supplementText.trim()
        if (text.isBlank()) return
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        scope.launch {
            delay(80)
            exitSupplementMode()
            onSupplement(text)
        }
    }

    Box {
        AnimatedVisibility(
            visible = entered && visible,
            // 进场不整体淡入：起点就是与球重合的玻璃圆（球在上层盖着），由下面的揭开过渡负责；
            // 退场等揭开缩回球心后再移除。减少动画时只淡入淡出。
            enter = if (reduced) fadeIn(MovoMotion.fast()) else EnterTransition.None,
            // 退场期间整层保持不透明（容器缩回球心、内容在末段淡出由下面两个过渡负责），缩完那一刻才移除。
            exit = if (reduced) fadeOut(MovoMotion.fastExit()) else fadeOut(tween(durationMillis = 1, delayMillis = PANEL_MORPH_OUT_MS)),
        ) {
            // 规范 9.5「展开卡」/ Figma 候选「动效全集」A1「揭开」：圆角容器（玻璃底 + 阴影）从球心 32 圆长到卡片边界，
            // 卡片内容按最终布局原位绘制、不缩放不位移，随容器边缘露出。0 = 与球重合的 32 圆，1 = 卡片。
            val reveal = transition.animateFloat(
                transitionSpec = {
                    if (targetState == androidx.compose.animation.EnterExitState.Visible) {
                        tween(PANEL_MORPH_IN_MS, easing = MovoMotion.EasingLinear)
                    } else {
                        tween(PANEL_MORPH_OUT_MS, easing = MovoMotion.EasingLinear)
                    }
                },
                label = "panelReveal",
            ) { if (it == androidx.compose.animation.EnterExitState.Visible || reduced) 1f else 0f }
            val entering = transition.targetState == androidx.compose.animation.EnterExitState.Visible
            // 展开卡窗口在球那一侧多留一条与球重叠的通道（PANEL_ORB_LANE，AgentRuntimeService.bubbleLayoutParams）：
            // 容器从球心开始长，起点必须落在本窗口里，否则前几帧被窗口边缘裁掉，看起来是从边缘冒出来。
            // 通道盖住了悬浮球，落在球上的手势转给悬浮球（见下方通道）。
            // 追踪卡片在窗口坐标系里的位置：把 orbCenterOnScreen 换算到卡片自身坐标，作为揭开起点。
            var cardOriginInWindow by remember { mutableStateOf<Offset?>(null) }
            val hostView = LocalView.current
            val orbCenterInCard: () -> Offset? = orbCenterInCard@{
                val onScreen = orbCenterOnScreen() ?: return@orbCenterInCard null
                val origin = cardOriginInWindow ?: return@orbCenterInCard null
                val loc = IntArray(2).also(hostView::getLocationOnScreen)
                Offset(
                    onScreen.x - loc[0] - origin.x,
                    onScreen.y - loc[1] - origin.y,
                )
            }
            Box {
                Column(
                    modifier = Modifier
                        .padding(
                            start = if (anchorEnd) 12.dp else PANEL_ORB_LANE,
                            end = if (anchorEnd) PANEL_ORB_LANE else 12.dp,
                            top = 12.dp,
                            bottom = 12.dp,
                        )
                        // 224dp is the visual baseline. Let the card grow for large system fonts so
                        // the compact voice control and the two task actions never clip or collide.
                        .widthIn(min = 224.dp, max = 280.dp)
                        .onGloballyPositioned { cardOriginInWindow = it.positionInWindow() }
                        // 容器的裁切、阴影、玻璃底与描边都按当前揭开矩形画，只在绘制阶段读进度，不重组。
                        // 阴影用硬件阴影（RenderNode 按轮廓实时算），跟随揭开形状，过渡中不丢阴影、不出方角（审查 A10）。
                        .orbReveal(
                            progress = { reveal.value },
                            anchorEnd = anchorEnd,
                            orbCenter = orbCenterInCard,
                        )
                        .padding(4.dp)
                        .graphicsLayer {
                            // 先让玻璃形状从球边揭开，再淡入内容；收起一开始先淡出内容，避免末段文字跳闪。
                            alpha = if (reduced) {
                                1f
                            } else if (entering) {
                                ((reveal.value - PANEL_CONTENT_ENTER_START) /
                                    (PANEL_CONTENT_ENTER_END - PANEL_CONTENT_ENTER_START)).coerceIn(0f, 1f)
                            } else {
                                (reveal.value / PANEL_CONTENT_EXIT_END).coerceIn(0f, 1f)
                            }
                        },
                ) {
                    val voiceMode = voice.active && !supplementMode
                    Crossfade(
                        targetState = voiceMode,
                        animationSpec = MovoMotion.standard(),
                        modifier = Modifier.animateContentSize(MovoMotion.standard()),
                        label = "panelVoiceMode",
                    ) { inVoice ->
                        if (inVoice) PanelVoiceBody(state, voice) else PanelHeader(state)
                    }
                    PanelNotice(notice)
                    AnimatedVisibility(
                        visible = supplementMode,
                        enter = fadeIn(MovoMotion.fast()) + expandVertically(MovoMotion.standard()),
                        exit = fadeOut(MovoMotion.fastExit()) + shrinkVertically(MovoMotion.standard()),
                    ) {
                        SupplementInput(
                            value = supplementText,
                            onValueChange = { supplementText = it; onInteraction() },
                            onCancel = ::closeSupplementMode,
                            onSend = ::submitSupplement,
                            onTap = onSupplementKeyboardRequested,
                        )
                    }
                    AnimatedVisibility(
                        visible = !supplementMode,
                        enter = fadeIn(MovoMotion.fast()) + expandVertically(MovoMotion.standard()),
                        exit = fadeOut(MovoMotion.fastExit()) + shrinkVertically(MovoMotion.standard()),
                    ) {
                        Column {
                            AnimatedVisibility(
                                visible = !voiceMode,
                                enter = fadeIn(MovoMotion.standard()) + expandVertically(MovoMotion.standard()),
                                exit = fadeOut(MovoMotion.fastExit()) + shrinkVertically(MovoMotion.standard()),
                            ) {
                                // 失败与最近步骤之间交叉淡化，高度同步 `standard`（审查 B6）。
                                Crossfade(
                                    targetState = state.phase == AgentOverlayPhase.FAILED,
                                    animationSpec = MovoMotion.fast(),
                                    modifier = Modifier.animateContentSize(MovoMotion.standard()),
                                    label = "panelFailure",
                                ) { failed ->
                                    if (failed) PanelFailure(state, onOpenResult) else RecentSteps(state)
                                }
                            }
                            PanelActions(
                                phase = state.phase,
                                voiceMode = voiceMode,
                                onType = ::enterSupplementMode,
                                onStartVoice = { onInteraction(); onStartVoice() },
                                onEndVoice = { onInteraction(); onEndVoice() },
                                onVoiceKeyboard = ::enterSupplementModeFromVoice,
                                onPause = { onInteraction(); onPause() },
                                onResume = { onInteraction(); onResume() },
                                onStop = { onInteraction(); onStop() },
                            )
                        }
                    }
                }
            }
        }
        // 球侧通道放在揭开 / 退场动画之外：从这里开始拖动球时展开卡随即收起，卡片内容退场后通道仍在，
        // 同一次拖动不会因为内容被移除而中断（窗口由 Runtime 等拖动结束再移除）。
        Box(
            Modifier
                .matchParentSize()
                .wrapContentWidth(if (anchorEnd) Alignment.End else Alignment.Start)
                .width(PANEL_ORB_LANE)
                .pointerInteropFilter { event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        laneTargetsOrb[0] = orbLaneHit(event.rawX, event.rawY, orbCenterOnScreen(), orbHalfPx)
                    }
                    if (laneTargetsOrb[0]) {
                        laneOrbPointer.onTouch(event)
                    } else {
                        // 通道其余部分（球上方的空白）：点一下收起展开卡。
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> laneCollapseGesture.down(event.rawX, event.rawY)
                            MotionEvent.ACTION_MOVE -> laneCollapseGesture.move(event.rawX, event.rawY)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                val action = laneCollapseGesture.up(cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL)
                                if (action == OrbGesture.Action.Tap) onCollapse()
                            }
                        }
                    }
                    true
                },
        )
    }
}

@Composable
private fun PanelHeader(state: AgentOverlayState) {
    val paused = state.phase == AgentOverlayPhase.PAUSED
    val now by produceState(System.currentTimeMillis(), state.phase) {
        while (state.phase == AgentOverlayPhase.RUNNING) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
        value = System.currentTimeMillis()
    }
    val stepCount = state.steps.size
    val current = state.steps.lastOrNull()
    Row(
        modifier = Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val title = when {
            state.phase == AgentOverlayPhase.FAILED -> state.status.localizedText()
            // 暂停时标题不变（2026-09-27 定，防跳闪）：只把右侧计时原位换成「已暂停」。
            current != null -> stringResource(R.string.movo_overlay_step, stepCount, current.title)
            else -> state.status.localizedText()
        }
        // 标题变化交叉淡化 `fast`（规范 9.3「值变化」，审查 B6）；计时直接换数字。
        AnimatedContent(
            targetState = title,
            transitionSpec = { fadeIn(MovoMotion.fast()) togetherWith fadeOut(MovoMotion.fastExit()) },
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier.weight(1f),
            label = "panelTitle",
        ) { text ->
            Text(
                text = text,
                style = MovoTypography.labelMedium,
                color = MovoColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 继续后的第一帧 produceState 还没刷新，时钟仍是暂停那一刻，而暂停时长已扣掉，会闪一帧偏小的计时；
        // 执行中取当前时刻兜底。
        val clock = if (state.phase == AgentOverlayPhase.RUNNING) maxOf(now, System.currentTimeMillis()) else now
        state.elapsedMillis(clock)?.let { elapsed ->
            Spacer(Modifier.width(8.dp))
            val seconds = elapsed / 1000
            // 计时 ↔「已暂停」原位交叉淡化 `fast`：两段文字叠在同一格里，格宽取两者较宽的一个，暂停 / 继续时标题不被挤动。
            val pausedLabel = animateFloatAsState(if (paused) 1f else 0f, MovoMotion.fast(), label = "panelPausedLabel")
            Box(contentAlignment = Alignment.CenterEnd) {
                Text(
                    String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60),
                    style = MovoTypography.numericLabel,
                    color = MovoColors.textSecondary,
                    modifier = Modifier.graphicsLayer { alpha = 1f - pausedLabel.value },
                )
                Text(
                    stringResource(R.string.movo_overlay_paused),
                    style = MovoTypography.labelMedium,
                    color = MovoColors.textSecondary,
                    maxLines = 1,
                    modifier = Modifier.graphicsLayer { alpha = pausedLabel.value },
                )
            }
        }
    }
}

/**
 * 失败（规范 9.5「悬浮球 · 失败」）：展开卡自动弹出显示原因（`Micro/Medium` 次要色，最多 2 行）+「查看」胶囊；
 * 点「查看」或点悬浮球打开结果，看过后回到待命。
 */
@Composable
private fun PanelFailure(state: AgentOverlayState, onOpenResult: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (state.detailText.isNotBlank()) {
            Text(
                state.detailText,
                style = MovoTypography.microMedium,
                color = MovoColors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 4.dp),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.End) {
            CompactPill(null, stringResource(R.string.movo_work_view), primary = true, onClick = onOpenResult)
        }
    }
}

/** 最近 3 步：12 图标 + Micro/Medium，行距 2；已完成为次要色、当前为主色。 */
@Composable
private fun RecentSteps(state: AgentOverlayState) {
    val recent = state.steps.takeLast(3)
    // 展开卡出现时已有的步骤直接显示（整张卡在做进场），之后新出现的步骤才播淡入上移。
    val seen = remember { state.steps.mapTo(mutableSetOf()) { it.id } }
    if (recent.isEmpty()) return
    val paused = state.phase == AgentOverlayPhase.PAUSED
    Column(
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        recent.forEachIndexed { index, step ->
            // 按步骤 id 保持每一行的身份：新步骤淡入上移 6 `fast`（规范 9.4「执行卡 · 新步骤」，审查 B6）。
            key(step.id) {
                val isNew = remember { seen.add(step.id) }
                val isCurrent = index == recent.lastIndex
                MovoEntrance(play = isNew, shift = 6.dp, durationMillis = MovoMotion.FAST) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 16.dp)) {
                        val icon = when {
                            isCurrent && paused -> StepIcon.PAUSED
                            step.status == OverlayStepStatus.RUNNING -> StepIcon.RUNNING
                            step.status == OverlayStepStatus.DONE -> StepIcon.DONE
                            else -> StepIcon.FAILED
                        }
                        // 状态图标交叉淡化 `fast`（加载圈 → ✓ / ✕ / ‖）。
                        // 图标状态切换：交叉淡化 + 缩放 0.72 ↔ 1 `fast`（规范 9.3.1「图标状态切换」）。
                        AnimatedContent(
                            targetState = icon,
                            transitionSpec = {
                                (fadeIn(MovoMotion.fast()) + scaleIn(MovoMotion.fast(), initialScale = 0.72f)) togetherWith
                                    (fadeOut(MovoMotion.fastExit()) + scaleOut(MovoMotion.fastExit(), targetScale = 0.72f))
                            },
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(12.dp),
                            label = "panelStepIcon",
                        ) { current ->
                            Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                                when (current) {
                                    StepIcon.PAUSED -> MovoIcon(MovoIcons.Pause, null, size = 12.dp, tint = MovoColors.textSecondary)
                                    StepIcon.RUNNING -> MovoSpinner(size = 12.dp)
                                    StepIcon.DONE -> MovoIcon(MovoIcons.Check, null, size = 12.dp, tint = MovoColors.greenFg)
                                    StepIcon.FAILED -> MovoIcon(MovoIcons.X, null, size = 12.dp, tint = MovoColors.roseFg)
                                }
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            step.title,
                            style = MovoTypography.microMedium,
                            color = if (isCurrent) MovoColors.textPrimary else MovoColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private enum class StepIcon { PAUSED, RUNNING, DONE, FAILED }

/** 展开卡状态说明：`Micro/Medium` 次要色、最多 2 行（与失败原因同一写法），出现 / 消失淡入淡出并收放高度。 */
@Composable
private fun PanelNotice(notice: String?) {
    var last by remember { mutableStateOf("") }
    if (notice != null) last = notice
    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn(MovoMotion.fast()) + expandVertically(MovoMotion.standard()),
        exit = fadeOut(MovoMotion.fastExit()) + shrinkVertically(MovoMotion.standard()),
    ) {
        Text(
            last,
            style = MovoTypography.microMedium,
            color = MovoColors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 4.dp)
                .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun PanelActions(
    phase: AgentOverlayPhase,
    voiceMode: Boolean,
    onType: () -> Unit,
    onStartVoice: () -> Unit,
    onEndVoice: () -> Unit,
    onVoiceKeyboard: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    // 完成 / 失败时操作行淡出并收起，而不是直接消失（审查 B6）。
    AnimatedVisibility(
        visible = phase != AgentOverlayPhase.FINISHED && phase != AgentOverlayPhase.FAILED,
        enter = fadeIn(MovoMotion.fast()) + expandVertically(MovoMotion.standard()),
        exit = fadeOut(MovoMotion.fastExit()) + shrinkVertically(MovoMotion.standard()),
    ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左 = 输入入口：打字补充（键盘）+ 语音对话（声波）；语音模式中显示退出 / 临时键盘分段控件。
        if (voiceMode) {
            VoiceModeSplitControl(
                onExitVoice = onEndVoice,
                onKeyboardInput = onVoiceKeyboard,
                size = VoiceModeSplitControlSize.Compact,
            )
        } else {
            CompactCircle(MovoIcons.Keyboard, stringResource(R.string.movo_overlay_type), onType)
            CompactCircle(MovoIcons.AudioLines, stringResource(R.string.movo_voice_conversation), onStartVoice)
        }
        Spacer(Modifier.weight(1f))
        // 执行中与暂停时始终两个胶囊（2026-09-27 定，防跳闪）：「结束任务」+ 状态胶囊；暂停只让状态胶囊原位变化。
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactPill(null, stringResource(R.string.movo_work_end_task), primary = false, onClick = onStop)
            PauseResumePill(paused = phase == AgentOverlayPhase.PAUSED, onPause = onPause, onResume = onResume)
        }
    }
    }
}

/**
 * 展开卡语音模式的内容（Figma `91:1977` / `116:1907`）：状态行高 18（指示 + `Label/Medium` 次要色，左右 10、间距 6）→
 * 字幕 `Label/Medium`（最多 3 行共 54，超出顶部 12 淡出，跟随最新一行）→ 上下文 `Micro/Medium` 次要色，间距 2 → 6。
 * 执行中说的话停顿后自动作为补充发出，「已补充」停留 1.5s 后回到聆听。
 */
@Composable
private fun PanelVoiceBody(state: AgentOverlayState, voice: VoiceSessionUiState) {
    var supplemented by remember { mutableStateOf(false) }
    LaunchedEffect(voice.supplements) {
        if (voice.supplements > 0) {
            supplemented = true
            delay(SUPPLEMENTED_HOLD_MS)
            supplemented = false
        }
    }
    val channel = voice.channel
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(18.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 20.dp, height = 12.dp), contentAlignment = Alignment.CenterStart) {
                when (channel) {
                    VoiceChannel.Speaking -> MovoIcon(MovoIcons.Volume2, null, size = 12.dp, tint = MovoColors.indigoFg)
                    VoiceChannel.Connecting -> Box(Modifier.size(6.dp).clip(CircleShape).background(MovoColors.textTertiary))
                    else -> PanelLevelMeter(hearing = channel == VoiceChannel.Hearing)
                }
            }
            Spacer(Modifier.width(6.dp))
            Crossfade(
                targetState = when {
                    supplemented -> R.string.movo_overlay_supplemented
                    channel == VoiceChannel.Speaking -> R.string.movo_voice_speaking
                    channel == VoiceChannel.Connecting -> R.string.movo_voice_connecting
                    else -> R.string.movo_overlay_listening
                },
                animationSpec = MovoMotion.fast(),
                label = "panelVoiceStatus",
            ) { status ->
                Text(
                    stringResource(status),
                    style = MovoTypography.labelMedium,
                    color = MovoColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val transcript = voice.transcript.takeIf { it.isNotBlank() && channel != VoiceChannel.Speaking && !supplemented }
            // 字幕出现 / 消失淡入淡出并收放高度（审查 B6）；退出期间沿用最后一段字幕，不闪空白。
            var lastTranscript by remember { mutableStateOf("") }
            if (transcript != null) lastTranscript = transcript
            AnimatedVisibility(
                visible = transcript != null,
                enter = fadeIn(MovoMotion.fast()) + expandVertically(MovoMotion.standard()),
                exit = fadeOut(MovoMotion.fastExit()) + shrinkVertically(MovoMotion.standard()),
            ) {
                PanelTranscript(lastTranscript)
            }
            val stepCount = state.steps.size
            Text(
                text = if (stepCount > 0) {
                    stringResource(R.string.movo_overlay_voice_context, stepCount)
                } else {
                    stringResource(R.string.movo_overlay_voice_context_idle)
                },
                style = MovoTypography.microMedium,
                color = MovoColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** 字幕：最多 3 行（`Label/Medium` 行高 18，共 54），超出时顶部 12 渐变淡出，始终显示最新一行。 */
@Composable
private fun PanelTranscript(text: String) {
    var overflowing by remember { mutableStateOf(false) }
    val fade = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.toPx() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 54.dp)
            .clip(RoundedCornerShape(0.dp))
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (overflowing) {
                    drawRect(
                        Brush.verticalGradient(0f to Color.Transparent, fade / size.height to Color.Black),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                }
            },
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            text,
            style = MovoTypography.labelMedium,
            color = MovoColors.textPrimary,
            modifier = Modifier.wrapContentHeight(align = Alignment.Bottom, unbounded = true),
            onTextLayout = { overflowing = it.lineCount > 3 },
        )
    }
}

/** 展开卡语音指示：6 条宽 2、间距 1.5、最高 12；听到说话时停在设计稿高度（4 / 8 / 12 / 7 / 10 / 5），静音 3。 */
@Composable
private fun PanelLevelMeter(hearing: Boolean) {
    val heights = listOf(4f, 8f, 12f, 7f, 10f, 5f)
    Row(horizontalArrangement = Arrangement.spacedBy(1.5.dp), verticalAlignment = Alignment.CenterVertically) {
        heights.forEachIndexed { index, h ->
            val height by androidx.compose.animation.core.animateDpAsState(
                if (hearing && !LocalReducedMotion.current) h.dp else 3.dp,
                MovoMotion.fast(),
                label = "panelBar$index",
            )
            Box(Modifier.size(width = 2.dp, height = height).clip(RoundedCornerShape(1.dp)).background(MovoColors.indigoFg))
        }
    }
}

private const val SUPPLEMENTED_HOLD_MS = 1_500L

/** 紧凑档圆按钮：24（热区 32）、图标 12、glass/fill。 */
@Composable
private fun CompactCircle(icon: MovoIconData, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .movoClickable(PressKind.Solid, shape = CircleShape, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(MovoColors.glassFill), contentAlignment = Alignment.Center) {
            MovoIcon(icon, null, size = 12.dp, tint = MovoColors.textPrimary)
        }
    }
}

/** 紧凑档胶囊：高 24（热区 32）、12 图标 + Micro/Medium、内边距 8 / 10；主操作为 action/primary 实底。 */
@Composable
private fun CompactPill(icon: MovoIconData?, label: String, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val fg = if (primary) MovoColors.actionPrimaryFg else MovoColors.textPrimary
    Box(
        modifier = Modifier
            .height(32.dp)
            .movoClickable(PressKind.Solid, shape = shape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(24.dp)
                .clip(shape)
                .background(if (primary) MovoColors.actionPrimaryBg else MovoColors.glassFill)
                .padding(start = if (icon != null) 8.dp else 10.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                MovoIcon(icon, null, size = 12.dp, tint = fg)
                Spacer(Modifier.width(4.dp))
            }
            Text(label, style = MovoTypography.microMedium, color = fg, maxLines = 1)
        }
    }
}

/**
 * 状态胶囊：「‖ 暂停」↔「▶ 继续」原位变化（规范 9.5「展开卡暂停 / 继续」，Figma A4）。
 * 底色 `glass/fill` ↔ `action/primary-bg` 过渡 `standard`；图标交叉淡化 + 缩放 0.72 ↔ 1、文字交叉淡化，都是 `fast`。
 * 两段文字叠在同一格里，胶囊宽度取两者较宽的一个，切换时不变宽、不挤动左边的「结束任务」。
 */
@Composable
private fun PauseResumePill(paused: Boolean, onPause: () -> Unit, onResume: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val bg = animateColorAsState(
        if (paused) MovoColors.actionPrimaryBg else MovoColors.glassFill,
        MovoMotion.standard(),
        label = "pauseResumeBg",
    )
    val toResume = animateFloatAsState(if (paused) 1f else 0f, MovoMotion.fast(), label = "pauseResumeLabel")
    Box(
        modifier = Modifier
            .height(32.dp)
            .movoClickable(PressKind.Solid, shape = shape, onClick = if (paused) onResume else onPause),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(24.dp)
                .clip(shape)
                .drawBehind { drawRect(bg.value) }
                .padding(start = 8.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = paused,
                transitionSpec = {
                    (fadeIn(MovoMotion.fast()) + scaleIn(MovoMotion.fast(), initialScale = 0.72f)) togetherWith
                        (fadeOut(MovoMotion.fastExit()) + scaleOut(MovoMotion.fastExit(), targetScale = 0.72f))
                },
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(12.dp),
                label = "pauseResumeIcon",
            ) { resume ->
                MovoIcon(
                    if (resume) MovoIcons.Play else MovoIcons.Pause,
                    null,
                    size = 12.dp,
                    tint = if (resume) MovoColors.actionPrimaryFg else MovoColors.textPrimary,
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(contentAlignment = Alignment.CenterStart) {
                Text(
                    stringResource(R.string.movo_overlay_pause),
                    style = MovoTypography.microMedium,
                    color = MovoColors.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.graphicsLayer { alpha = 1f - toResume.value },
                )
                Text(
                    stringResource(R.string.movo_overlay_resume),
                    style = MovoTypography.microMedium,
                    color = MovoColors.actionPrimaryFg,
                    maxLines = 1,
                    modifier = Modifier.graphicsLayer { alpha = toResume.value },
                )
            }
        }
    }
}

/** 补充输入：最多 4 行；「取消」「发送」。窗口在此期间可获焦以弹出键盘。 */
@Composable
private fun SupplementInput(
    value: String,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    onTap: () -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        delay(180)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp, max = 112.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MovoColors.glassFill)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            if (value.isBlank()) {
                Text(
                    text = stringResource(R.string.overlay_supplement_hint),
                    style = MovoTypography.labelRegular,
                    color = MovoColors.textSecondary,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    // 只观察按下、不消费：点输入框会弹出键盘，先通知窗口上移。
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            onTap()
                        }
                    }
                    // 实体键盘 / 注入的回车同样发送（Shift+回车仍换行）。
                    .onPreviewKeyEvent { event ->
                        val enter = event.key == androidx.compose.ui.input.key.Key.Enter ||
                            event.key == androidx.compose.ui.input.key.Key.NumPadEnter
                        if (!enter || event.isShiftPressed) return@onPreviewKeyEvent false
                        if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && value.isNotBlank()) onSend()
                        true
                    },
                textStyle = MovoTypography.labelRegular.copy(color = MovoColors.textPrimary),
                // 键盘的回车键直接发送（展开卡里的发送键可能被键盘挡住）。
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { if (value.isNotBlank()) onSend() }),
                cursorBrush = SolidColor(MovoColors.indigoFg),
                maxLines = 4,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactPill(null, stringResource(R.string.action_cancel), primary = false, onClick = onCancel)
            Spacer(Modifier.width(4.dp))
            CompactPill(MovoIcons.ArrowUp, stringResource(R.string.overlay_send), primary = value.isNotBlank(), onClick = onSend)
        }
    }
}

/**
 * 展开卡从悬浮球长出 / 缩回的时长（规范 9.5「悬浮球 → 展开卡」，Figma A1）：展开 `slow`，收起 `slow` 退场档 250。
 * 收起须不长于窗口移除的延迟（AgentRuntimeService.BUBBLE_EXIT_MS）。
 */
private const val PANEL_MORPH_IN_MS = MovoMotion.SLOW

/** 卡片与窗口球侧边缘之间的通道：球窗口 44 + 卡片与球间距 8，正好盖住悬浮球（这一侧的阴影余量落在通道里）。 */
internal val PANEL_ORB_LANE = 52.dp

/** 展开时内容在揭开 35% 后开始淡入，85% 时完全显示。 */
private const val PANEL_CONTENT_ENTER_START = 0.35f
private const val PANEL_CONTENT_ENTER_END = 0.85f

/** 收起时内容在前 35% 的逆放过程中淡出。 */
private const val PANEL_CONTENT_EXIT_END = 0.35f

/**
 * 揭开容器：最终圆角卡在原位绘制，用“以球心为圆心的圆”和最终卡片做交集裁剪。
 * 圆形半径在整个时长内线性增长；球心位于卡片角附近时，这比先快后慢的曲线更不容易一两帧就盖住整块卡片。
 * 起点几何优先用 [orbCenter] 提供的球心（卡片自身坐标，px）；为 null 时按当前停靠几何近似。
 */
private fun Modifier.orbReveal(
    progress: () -> Float,
    anchorEnd: Boolean,
    orbCenter: () -> Offset? = { null },
): Modifier = this
    .graphicsLayer {
        val p = progress()
        shape = object : androidx.compose.ui.graphics.Shape {
            override fun createOutline(
                size: androidx.compose.ui.geometry.Size,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                density: androidx.compose.ui.unit.Density,
            ): androidx.compose.ui.graphics.Outline {
                val cardCorner = 16f * density.density
                val cardBounds = androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)
                if (p >= 1f) {
                    return androidx.compose.ui.graphics.Outline.Rounded(
                        androidx.compose.ui.geometry.RoundRect(
                            cardBounds,
                            androidx.compose.ui.geometry.CornerRadius(cardCorner, cardCorner),
                        ),
                    )
                }
                if (p <= 0f) {
                    return androidx.compose.ui.graphics.Outline.Rectangle(
                        androidx.compose.ui.geometry.Rect(0f, 0f, 0f, 0f),
                    )
                }
                val center = orbRevealCenter(size, density.density, anchorEnd, orbCenter())
                val radius = orbRevealRadius(size, center, p, density.density)
                val cardPath = androidx.compose.ui.graphics.Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            cardBounds,
                            androidx.compose.ui.geometry.CornerRadius(cardCorner, cardCorner),
                        ),
                    )
                }
                val revealPath = androidx.compose.ui.graphics.Path().apply {
                    addOval(
                        androidx.compose.ui.geometry.Rect(
                            center.x - radius,
                            center.y - radius,
                            center.x + radius,
                            center.y + radius,
                        ),
                    )
                }
                return androidx.compose.ui.graphics.Outline.Generic(
                    androidx.compose.ui.graphics.Path.combine(
                        androidx.compose.ui.graphics.PathOperation.Intersect,
                        cardPath,
                        revealPath,
                    ),
                )
            }
        }
        clip = true
        // 色调取规范第 7 章暖灰阴影色；系统会再乘主题的 ambient / spot 透明度，spot 取一半使主阴影接近 `0 12 32 −8 / 10%`。
        shadowElevation = 12.dp.toPx()
        ambientShadowColor = MovoColors.shadow
        spotShadowColor = MovoColors.shadow.copy(alpha = 0.5f)
    }
    .drawBehind {
        val p = progress()
        val cardCorner = 16.dp.toPx()
        drawRoundRect(
            GlassSurface,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cardCorner, cardCorner),
        )
        val stroke = 0.5.dp.toPx()
        drawRoundRect(
            MovoColors.borderHairline,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                cardCorner - stroke / 2f,
                cardCorner - stroke / 2f,
            ),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
        )
        if (p in 0f..1f) {
            val center = orbRevealCenter(size, density, anchorEnd, orbCenter())
            val radius = orbRevealRadius(size, center, p, density)
            drawCircle(
                MovoColors.borderHairline,
                radius = (radius - stroke / 2f).coerceAtLeast(0f),
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
            )
        }
    }

/** 揭开圆的中心：优先使用实时球心，否则按卡片与球的默认间距计算。 */
private fun orbRevealCenter(
    size: androidx.compose.ui.geometry.Size,
    density: Float,
    anchorEnd: Boolean,
    orbCenter: Offset?,
): Offset {
    val sideGap = (8 + 22) * density
    val fallbackCx = if (anchorEnd) size.width + sideGap else -sideGap
    val fallbackCy = size.height - (22 - 6) * density
    return orbCenter ?: Offset(fallbackCx, fallbackCy)
}

/** 揭开圆半径：从 32dp 玻璃圆线性增加到足以覆盖整块卡片。 */
private fun orbRevealRadius(
    size: androidx.compose.ui.geometry.Size,
    center: Offset,
    p: Float,
    density: Float,
): Float {
    val half = 16f * density
    val target = kotlin.math.hypot(
        maxOf(center.x, size.width - center.x).toDouble(),
        maxOf(center.y, size.height - center.y).toDouble(),
    ).toFloat() + density
    return half + (target - half) * p.coerceIn(0f, 1f)
}

