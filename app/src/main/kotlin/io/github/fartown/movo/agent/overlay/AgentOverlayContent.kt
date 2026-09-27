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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
    val gesture = remember { OrbGesture() }
    AnimatedVisibility(
        visible = entered && shown,
        enter = fadeIn(MovoMotion.fast()) + if (reduced) fadeIn(snap()) else scaleIn(MovoMotion.gentle(), initialScale = 0.5f),
        exit = fadeOut(tween(MovoMotion.STANDARD_EXIT, easing = MovoMotion.EasingExit)) +
            if (reduced) fadeOut(snap()) else scaleOut(tween(MovoMotion.STANDARD_EXIT, easing = MovoMotion.EasingExit), targetScale = 0.5f),
    ) {
        val touchSlop = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
        Box(
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer { translationX = shake.value * density }
                .semantics {
                    contentDescription = tapLabel
                    onClick { onTap(); true }
                    onLongClick { onLongPress(); true }
                }
                .pointerInteropFilter { event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            gesture.down(event.rawX, event.rawY)
                            pressed = true
                            gesture.longPressJob = scope.launch {
                                delay(MovoMotion.LONG_PRESS.toLong())
                                if (gesture.state == OrbGesture.State.PENDING) {
                                    gesture.state = OrbGesture.State.LONG_PRESSED
                                    pressed = false
                                    onLongPress()
                                }
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (gesture.state == OrbGesture.State.PENDING && gesture.distanceFromDown(event.rawX, event.rawY) > touchSlop) {
                                gesture.longPressJob?.cancel()
                                gesture.state = OrbGesture.State.DRAGGING
                                pressed = false
                                dragging = true
                                onDragStart()
                            }
                            if (gesture.state == OrbGesture.State.DRAGGING) {
                                val (dx, dy) = gesture.moveTo(event.rawX, event.rawY)
                                onDrag(dx, dy)
                            } else {
                                gesture.moveTo(event.rawX, event.rawY)
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            gesture.longPressJob?.cancel()
                            pressed = false
                            when (gesture.state) {
                                OrbGesture.State.PENDING -> if (event.actionMasked == MotionEvent.ACTION_UP) onTap()
                                OrbGesture.State.DRAGGING -> {
                                    dragging = false
                                    onDragEnd()
                                }
                                else -> Unit
                            }
                            gesture.state = OrbGesture.State.IDLE
                        }
                    }
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

/** 悬浮球的按下 / 长按 / 拖动判定（屏幕坐标）。 */
private class OrbGesture {
    enum class State { IDLE, PENDING, LONG_PRESSED, DRAGGING }

    var state = State.IDLE
    var longPressJob: kotlinx.coroutines.Job? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    fun down(x: Float, y: Float) {
        state = State.PENDING
        downX = x; downY = y; lastX = x; lastY = y
    }

    fun distanceFromDown(x: Float, y: Float): Float = kotlin.math.hypot(x - downX, y - downY)

    fun moveTo(x: Float, y: Float): Pair<Float, Float> {
        val delta = (x - lastX) to (y - lastY)
        lastX = x; lastY = y
        return delta
    }
}

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
) {
    val reduced = LocalReducedMotion.current
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val scope = rememberCoroutineScope()
    var supplementMode by remember { mutableStateOf(false) }
    var supplementText by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun enterSupplementMode() {
        onInteraction()
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
                    tween(PANEL_MORPH_IN_MS, easing = MovoMotion.EasingStandard)
                } else {
                    tween(PANEL_MORPH_OUT_MS, easing = MovoMotion.EasingExit)
                }
            },
            label = "panelReveal",
        ) { if (it == androidx.compose.animation.EnterExitState.Visible || reduced) 1f else 0f }
        // 内容透明度：展开时从 0.2 起 240ms `enter` 到 1；收起时在收起末段淡到 0。
        val contentFade = transition.animateFloat(
            transitionSpec = {
                if (targetState == androidx.compose.animation.EnterExitState.Visible) {
                    tween(MovoMotion.STANDARD, easing = MovoMotion.EasingEnter)
                } else {
                    tween(PANEL_MORPH_OUT_MS, easing = MovoMotion.EasingExit)
                }
            },
            label = "panelContentFade",
        ) { if (it == androidx.compose.animation.EnterExitState.Visible || reduced) 1f else 0f }
        val entering = transition.targetState == androidx.compose.animation.EnterExitState.Visible
        // 展开卡窗口在球那一侧多留一条与球重叠的通道（PANEL_ORB_LANE，AgentRuntimeService.bubbleLayoutParams）：
        // 容器从球心开始长，起点必须落在本窗口里，否则前几帧被窗口边缘裁掉，看起来是从边缘冒出来。
        // 通道盖住了悬浮球，点它等同点球（收起）。
        Box {
            Column(
                modifier = Modifier
                    .padding(
                        start = if (anchorEnd) 12.dp else PANEL_ORB_LANE,
                        end = if (anchorEnd) PANEL_ORB_LANE else 12.dp,
                        top = 12.dp,
                        bottom = 12.dp,
                    )
                    .width(224.dp)
                    // 容器的裁切、阴影、玻璃底与描边都按当前揭开矩形画，只在绘制阶段读进度，不重组。
                    // 阴影用硬件阴影（RenderNode 按轮廓实时算），跟随揭开形状，过渡中不丢阴影、不出方角（审查 A10）。
                    .orbReveal(progress = { reveal.value }, anchorEnd = anchorEnd)
                    .padding(4.dp)
                    .graphicsLayer {
                        val f = contentFade.value
                        alpha = if (entering) PANEL_CONTENT_START_ALPHA + (1f - PANEL_CONTENT_START_ALPHA) * f else f
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
                            onPause = { onInteraction(); onPause() },
                            onResume = { onInteraction(); onResume() },
                            onStop = { onInteraction(); onStop() },
                        )
                    }
                }
            }
            Box(
                Modifier
                    .matchParentSize()
                    .wrapContentWidth(if (anchorEnd) Alignment.End else Alignment.Start)
                    .width(PANEL_ORB_LANE)
                    .pointerInput(onCollapse) { detectTapGestures { onCollapse() } },
            )
        }
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
        // 左 = 输入入口：打字补充（键盘）+ 语音对话（声波）；语音模式中只留「切回文字」（键盘，原位）。
        if (voiceMode) {
            CompactCircle(MovoIcons.Keyboard, stringResource(R.string.movo_voice_back_to_text), onEndVoice)
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
internal const val PANEL_MORPH_OUT_MS = MovoMotion.SLOW_EXIT

/** 卡片与窗口球侧边缘之间的通道：球窗口 44 + 卡片与球间距 8，正好盖住悬浮球（这一侧的阴影余量落在通道里）。 */
internal val PANEL_ORB_LANE = 52.dp

/** 展开时内容的起始透明度（Figma A1：从 20% 淡入）。 */
private const val PANEL_CONTENT_START_ALPHA = 0.2f

/**
 * 揭开容器：圆角矩形从与悬浮球重合的 32 圆（圆角 16）长到卡片边界（圆角 16），卡片内容不缩放、不位移。
 * 卡片与球的相对位置由窗口摆放决定（AgentRuntimeService.bubbleLayoutParams）：卡片在球朝屏幕中心的一侧、间距 8；
 * 卡片底边比球窗口底边高 6，球窗口 44、球心在窗口中央。所以球心在卡片外侧 8 + 22、卡片底边上方 22 − 6。
 * 揭开矩形在起点时有一部分落在卡片外（球那一侧的通道里）：裁切只按轮廓（不裁到卡片边界），玻璃底也按矩形画，
 * 所以第一帧就是一个完整的玻璃圆，不会从卡片边缘冒出来。
 */
private fun Modifier.orbReveal(progress: () -> Float, anchorEnd: Boolean): Modifier = this
    .graphicsLayer {
        val rect = orbRevealRect(size, progress(), anchorEnd, density)
        val r = 16.dp.toPx()
        shape = object : androidx.compose.ui.graphics.Shape {
            override fun createOutline(
                size: androidx.compose.ui.geometry.Size,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                density: androidx.compose.ui.unit.Density,
            ) = androidx.compose.ui.graphics.Outline.Rounded(
                androidx.compose.ui.geometry.RoundRect(rect, androidx.compose.ui.geometry.CornerRadius(r, r)),
            )
        }
        clip = true
        // 色调取规范第 7 章暖灰阴影色；系统会再乘主题的 ambient / spot 透明度，spot 取一半使主阴影接近 `0 12 32 −8 / 10%`。
        shadowElevation = 12.dp.toPx()
        ambientShadowColor = MovoColors.shadow
        spotShadowColor = MovoColors.shadow.copy(alpha = 0.5f)
    }
    .drawBehind {
        val rect = orbRevealRect(size, progress(), anchorEnd, density)
        val r = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx())
        drawRoundRect(GlassSurface, topLeft = rect.topLeft, size = rect.size, cornerRadius = r)
        val stroke = 0.5.dp.toPx()
        drawRoundRect(
            MovoColors.borderHairline,
            topLeft = androidx.compose.ui.geometry.Offset(rect.left + stroke / 2f, rect.top + stroke / 2f),
            size = androidx.compose.ui.geometry.Size(rect.width - stroke, rect.height - stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r.x - stroke / 2f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
        )
    }

/** 揭开进度 [p] 时容器在卡片坐标里的矩形：球心处 32 圆 → 卡片边界，四边各自线性插值。 */
private fun orbRevealRect(
    size: androidx.compose.ui.geometry.Size,
    p: Float,
    anchorEnd: Boolean,
    density: Float,
): androidx.compose.ui.geometry.Rect {
    if (p >= 1f) return androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)
    val half = 16f * density
    val sideGap = (8 + 22) * density
    val cx = if (anchorEnd) size.width + sideGap else -sideGap
    val cy = size.height - (22 - 6) * density
    fun lerp(a: Float, b: Float) = a + (b - a) * p
    return androidx.compose.ui.geometry.Rect(
        lerp(cx - half, 0f),
        lerp(cy - half, 0f),
        lerp(cx + half, size.width),
        lerp(cy + half, size.height),
    )
}

