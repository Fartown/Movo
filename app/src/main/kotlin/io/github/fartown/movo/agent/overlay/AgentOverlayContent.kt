package io.github.fartown.movo.agent.overlay

import android.graphics.BlurMaskFilter
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.voice.session.VoiceChannel
import io.github.fartown.movo.agent.voice.session.VoiceSessionUiState
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
    val alpha by animateFloatAsState(targetAlpha, MovoMotion.standard(), label = "glowAlpha")
    if (alpha <= 0.001f) return
    val flowing = phase == AgentOverlayPhase.RUNNING && !reduced
    val rotation: Float
    val breath: Float
    if (flowing) {
        val transition = rememberInfiniteTransition(label = "glow")
        val r by transition.animateFloat(
            0f, 360f,
            infiniteRepeatable(tween(MovoMotion.ORB_GRADIENT_PERIOD, easing = MovoMotion.EasingLinear)),
            label = "glowRotation",
        )
        val b by transition.animateFloat(
            0.7f, 1f,
            infiniteRepeatable(tween(MovoMotion.AMBIENT / 2, easing = MovoMotion.EasingStandard), RepeatMode.Reverse),
            label = "glowBreath",
        )
        rotation = r
        breath = b
    } else {
        rotation = 0f
        breath = if (reduced && phase == AgentOverlayPhase.RUNNING) 0.8f else 1f
    }
    val colors = remember { (MovoColors.brandGradient + MovoColors.brandGradient.first()).map { it.toArgb() }.toIntArray() }
    // 光晕画笔复用，不在每帧新建 Paint / Shader（盘点 B11）。
    val glowPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
        }
    }
    val shaderMatrix = remember { android.graphics.Matrix() }
    val cache = remember { GlowCache() }
    Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha * breath }) {
        val corner = 36.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawIntoCanvas { canvas ->
            val shader = cache.shader(cx, cy, colors)
            shaderMatrix.setRotate(rotation, cx, cy)
            shader.setLocalMatrix(shaderMatrix)
            glowPaint.shader = shader
            // 柔光：10 宽、模糊 14，向内渐隐。
            glowPaint.strokeWidth = 10.dp.toPx()
            glowPaint.maskFilter = cache.blur(14.dp.toPx())
            val inset = 5.dp.toPx()
            canvas.nativeCanvas.drawRoundRect(inset, inset, size.width - inset, size.height - inset, corner, corner, glowPaint)
            // 实线：2 宽。
            glowPaint.maskFilter = null
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
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    var dragging by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(
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
                    .graphicsLayer { scaleX = lift; scaleY = lift }
                    .dropShadow(CircleShape, Shadow(radius = 16.dp, offset = DpOffset(0.dp, 6.dp), color = MovoColors.shadow, alpha = shadowAlpha))
                    .clip(CircleShape)
                    .background(GlassSurface)
                    .border(0.5.dp, MovoColors.borderHairline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                val desaturate = mode == OrbMode.PAUSED
                val saturation by animateFloatAsState(if (desaturate) 0f else 1f, MovoMotion.fast(), label = "orbSaturation")
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            if (saturation < 1f) alpha = 0.7f + 0.3f * saturation
                        }
                        .drawWithContent {
                            drawContent()
                            val b = brighten.value
                            if (b > 0f) drawCircle(Color.White.copy(alpha = b), blendMode = androidx.compose.ui.graphics.BlendMode.Screen)
                        },
                ) {
                    MovoOrb(size = 22.dp, animated = mode == OrbMode.RUNNING || mode == OrbMode.LISTENING || mode == OrbMode.STANDBY)
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
    val progress = if (spreading) {
        val transition = rememberInfiniteTransition(label = "orbRipple")
        val value by transition.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(MovoMotion.ORB_ARC_PERIOD, easing = MovoMotion.EasingStandard)),
            label = "orbRippleProgress",
        )
        value
    } else {
        -1f
    }
    Canvas(modifier = Modifier.size(44.dp)) {
        val stroke = 1.dp.toPx()
        drawCircle(MovoColors.indigoFg.copy(alpha = 0.22f), radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
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
    val color by animateColorAsState(
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
    val rotation = if (spinning) {
        val transition = rememberInfiniteTransition(label = "orbArc")
        val value by transition.animateFloat(
            0f, 360f,
            infiniteRepeatable(tween(MovoMotion.ORB_ARC_PERIOD, easing = MovoMotion.EasingLinear)),
            label = "orbArcRotation",
        )
        value
    } else {
        0f
    }
    Canvas(modifier = Modifier.size(32.dp)) {
        if (color.alpha <= 0.001f) return@Canvas
        val stroke = 1.5.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        if (spinning) {
            rotate(rotation) {
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
 * 暂停态：标题「已暂停·第 N 步」、计时冻结改次要色、当前步图标换成 ‖。以靠球一侧的底角为锚点缩放 0.9 → 1 进场。
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
    anchorEnd: Boolean = true,
    visible: Boolean = true,
    onInteraction: () -> Unit = {},
    voice: VoiceSessionUiState = VoiceSessionUiState(),
    onStartVoice: () -> Unit = {},
    onEndVoice: () -> Unit = {},
    onOpenResult: () -> Unit = {},
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

    val origin = TransformOrigin(if (anchorEnd) 1f else 0f, 1f)
    AnimatedVisibility(
        visible = entered && visible,
        enter = fadeIn(MovoMotion.fast()) + if (reduced) fadeIn(snap()) else scaleIn(MovoMotion.fast(), initialScale = 0.9f, transformOrigin = origin),
        exit = fadeOut(MovoMotion.fastExit()) + if (reduced) fadeOut(snap()) else scaleOut(MovoMotion.fastExit(), targetScale = 0.9f, transformOrigin = origin),
    ) {
        val shape = RoundedCornerShape(16.dp)
        Column(
            modifier = Modifier
                .padding(12.dp)
                .width(224.dp)
                .dropShadow(shape, Shadow(radius = 32.dp, spread = (-8).dp, offset = DpOffset(0.dp, 12.dp), color = MovoColors.shadow, alpha = 0.10f))
                .dropShadow(shape, Shadow(radius = 3.dp, offset = DpOffset(0.dp, 1.dp), color = MovoColors.shadow, alpha = 0.05f))
                .clip(shape)
                .background(GlassSurface)
                .border(0.5.dp, MovoColors.borderHairline, shape)
                .padding(4.dp),
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
                        if (state.phase == AgentOverlayPhase.FAILED) PanelFailure(state, onOpenResult) else RecentSteps(state)
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
        Text(
            text = when {
                state.phase == AgentOverlayPhase.FAILED -> state.status.localizedText()
                paused && stepCount > 0 -> stringResource(R.string.movo_overlay_paused_step, stepCount)
                current != null -> stringResource(R.string.movo_overlay_step, stepCount, current.title)
                else -> state.status.localizedText()
            },
            style = MovoTypography.labelMedium,
            color = MovoColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        state.elapsedMillis(now)?.let { elapsed ->
            Spacer(Modifier.width(8.dp))
            val seconds = elapsed / 1000
            Text(
                String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60),
                style = MovoTypography.numericLabel,
                color = MovoColors.textSecondary,
            )
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
    if (recent.isEmpty()) return
    val paused = state.phase == AgentOverlayPhase.PAUSED
    Column(
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        recent.forEachIndexed { index, step ->
            val isCurrent = index == recent.lastIndex
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 16.dp)) {
                Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                    when {
                        isCurrent && paused -> MovoIcon(MovoIcons.Pause, null, size = 12.dp, tint = MovoColors.textSecondary)
                        step.status == OverlayStepStatus.RUNNING -> MovoSpinner(size = 12.dp)
                        step.status == OverlayStepStatus.DONE -> MovoIcon(MovoIcons.Check, null, size = 12.dp, tint = MovoColors.greenFg)
                        else -> MovoIcon(MovoIcons.X, null, size = 12.dp, tint = MovoColors.roseFg)
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
    if (phase == AgentOverlayPhase.FINISHED || phase == AgentOverlayPhase.FAILED) return
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
        Crossfade(targetState = phase == AgentOverlayPhase.PAUSED, animationSpec = MovoMotion.fast(), label = "panelActions") { paused ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (paused) {
                    CompactPill(null, stringResource(R.string.movo_work_end_task), primary = false, onClick = onStop)
                    CompactPill(MovoIcons.Play, stringResource(R.string.movo_overlay_resume), primary = true, onClick = onResume)
                } else {
                    CompactPill(MovoIcons.Pause, stringResource(R.string.movo_overlay_pause), primary = false, onClick = onPause)
                }
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
            if (transcript != null) PanelTranscript(transcript)
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

/** 补充输入：最多 4 行；「取消」「发送」。窗口在此期间可获焦以弹出键盘。 */
@Composable
private fun SupplementInput(
    value: String,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
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
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                textStyle = MovoTypography.labelRegular.copy(color = MovoColors.textPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
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
