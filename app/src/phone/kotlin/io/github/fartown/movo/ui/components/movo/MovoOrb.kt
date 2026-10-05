package io.github.fartown.movo.ui.components.movo

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoMotion

/** 光球状态（规范 9.7）。 */
internal enum class OrbState { STANDBY, LISTENING, THINKING, SPEAKING, ERROR }

/**
 * 光球 `Movo/Orb`（规范 4.3、8 组件「光球」、9.7）：淡丁香底 + 品牌渐变角向漩涡（模糊）+ 左上高光，外圈柔光。
 * - 待命：渐变 12s 一圈 + 缩放 1 ↔ 1.03 呼吸（`ambient`）。
 * - 聆听：缩放跟随音量（[level]，9.6 平滑音量），最大 1.12；[speechKey] 变化（开口说话）时外圈扩散一道波纹。
 * - 思考：转速 ×2，色相偏向丁香色。
 * - 播报：随振幅轻微起伏（最大 1.06）。
 * - 出错：饱和度降低，左右抖动 2 两次。
 * 状态之间 `standard` 过渡，不跳变。小光球（16）渐变 3s 一圈、不显示外圈光（光在 16 上会被裁成方块）。
 * 减少动画时为静态渐变（9.8）。
 */
@Composable
internal fun MovoOrb(
    size: Dp,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    state: OrbState = OrbState.STANDBY,
    level: Float = 0f,
    speechKey: Int = 0,
    /** 外圈柔光的不透明度，在绘制阶段读取；飞行层（Q6）按固定尺寸缩放光球时用它模拟小光球「无外圈光」。 */
    glowAlpha: () -> Float = { 1f },
    /**
     * 按 App 图标（规范 6.1 B7）画：光球上叠同色浅调 M，不画外圈柔光，改为下方 Indigo 柔和投影（首页、悬浮球用；
     * Figma「11 · Logo 光球」）。
     */
    logo: Boolean = false,
    /** logo 光球上 M 的不透明度（绘制阶段读取）；飞行层缩小成执行卡里的小光球时淡出 M。 */
    markAlpha: () -> Float = { 1f },
) {
    val reduced = LocalReducedMotion.current
    val mini = size < 24.dp
    val moving = animated && !reduced
    // 转速：思考 ×2，过渡 `standard`；角度逐帧累加，改变转速时不跳。
    val speed by animateFloatAsState(if (state == OrbState.THINKING) 2f else 1f, MovoMotion.standard(), label = "orbSpeed")
    val period = if (mini) MovoMotion.MINI_ORB_GRADIENT_PERIOD else MovoMotion.ORB_GRADIENT_PERIOD
    var rotation by remember { mutableFloatStateOf(0f) }
    if (moving) {
        LaunchedEffect(period) {
            var last = androidx.compose.animation.core.withInfiniteAnimationFrameNanos { it }
            while (true) {
                val now = androidx.compose.animation.core.withInfiniteAnimationFrameNanos { it }
                rotation = (rotation + 360f * ((now - last) / 1_000_000f) / period * speed) % 360f
                last = now
            }
        }
    }
    // 呼吸与音量都逐帧变化：只保存 State，在下方 graphicsLayer 里读，不让光球所在界面每帧重组。
    val breath: State<Float>? = if (moving && state == OrbState.STANDBY && !mini) {
        val transition = rememberInfiniteTransition(label = "movoOrb")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                tween(MovoMotion.AMBIENT / 2, easing = MovoMotion.EasingStandard),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "movoOrbBreath",
        )
    } else {
        null
    }
    val voiceLevel = rememberSmoothedLevelState(if (state == OrbState.LISTENING || state == OrbState.SPEAKING) level else 0f)
    val voiceGain = when (state) {
        OrbState.LISTENING -> 0.12f
        OrbState.SPEAKING -> 0.06f
        else -> 0f
    }
    val lilac by animateFloatAsState(if (state == OrbState.THINKING) 0.28f else 0f, MovoMotion.standard(), label = "orbLilac")
    val desaturate by animateFloatAsState(if (state == OrbState.ERROR) 0.7f else 0f, MovoMotion.standard(), label = "orbDesaturate")
    val shake = remember { Animatable(0f) }
    LaunchedEffect(state) {
        if (state == OrbState.ERROR && !reduced) {
            for (target in floatArrayOf(2f, -2f, 2f, -2f, 0f)) shake.animateTo(target, tween(50, easing = MovoMotion.EasingStandard))
        }
    }
    val ripple = remember { Animatable(1f) }
    LaunchedEffect(speechKey) {
        if (speechKey > 0 && state == OrbState.LISTENING && !reduced && !mini) {
            ripple.snapTo(0f)
            ripple.animateTo(1f, tween(MovoMotion.SLOW * 2, easing = MovoMotion.EasingStandard))
        }
    }
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { translationX = shake.value * this.density },
        contentAlignment = Alignment.Center,
    ) {
        if (logo && !mini) {
            // 图标同款投影：Indigo 18%，下移 5%、模糊约 16%（不用外发光，小尺寸发虚）。
            Canvas(modifier = Modifier.requiredSize(size * 1.4f)) {
                val d = this.size.minDimension / 1.4f
                drawCircle(
                    brush = Brush.radialGradient(
                        0.55f to Color(0x2E5B4BFF),
                        1f to Color(0x005B4BFF),
                        center = Offset(center.x, center.y + d * 0.05f),
                        radius = d * 0.66f,
                    ),
                    radius = d * 0.66f,
                    center = Offset(center.x, center.y + d * 0.05f),
                )
            }
        }
        if (!mini) {
            // 外圈柔光：丁香 34% → 蜜桃 12% → 透明，直径约 2.2 倍；logo 光球不画柔光，只保留聆听波纹。
            Canvas(modifier = Modifier.requiredSize(size * 2.2f).graphicsLayer { alpha = glowAlpha() }) {
                if (!logo) drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color(0x57A68CFF),
                        0.5f to Color(0x1FFF9E80),
                        1f to Color(0x00FF9E80),
                    ),
                )
                // 聆听：开口说话时外圈扩散一道波纹。
                val r = ripple.value
                if (r < 1f) {
                    val base = this.size.minDimension / 2f / 2.2f
                    drawCircle(
                        color = Color(0xFF8C7BFF).copy(alpha = 0.35f * (1f - r)),
                        radius = base * (1f + 0.6f * r),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(with(density) { 1.5.dp.toPx() }),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    val s = (breath?.value ?: 1f) * (1f + voiceGain * voiceLevel.value)
                    scaleX = s
                    scaleY = s
                }
                .clip(CircleShape)
                .background(OrbBase)
                .drawWithContent {
                    drawContent()
                    // 思考：色相偏向丁香；出错：饱和度降低。
                    if (lilac > 0f) drawCircle(Color(0xFFB39BFF).copy(alpha = lilac), blendMode = androidx.compose.ui.graphics.BlendMode.Color)
                    if (desaturate > 0f) drawCircle(Color(0xFF8A8A8A).copy(alpha = desaturate), blendMode = androidx.compose.ui.graphics.BlendMode.Saturation)
                },
            contentAlignment = Alignment.Center,
        ) {
            val blurPx = with(density) { (size * 0.22f).toPx() }
            val blur = remember(blurPx) { BlurEffect(blurPx, blurPx, TileMode.Decal) }
            Canvas(
                modifier = Modifier
                    .requiredSize(size * 1.5f)
                    .graphicsLayer {
                        rotationZ = rotation
                        renderEffect = blur
                    },
            ) {
                drawCircle(brush = Brush.sweepGradient(*OrbSweep))
            }
            Canvas(modifier = Modifier.size(size)) {
                // 高光：中心在左上（0.34, 0.28），白 95% → 35% → 透明。
                val center = Offset(this.size.width * 0.34f, this.size.height * 0.28f)
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color(0xF2FFFFFF),
                        0.38f to Color(0x59FFFFFF),
                        0.72f to Color(0x00FFFFFF),
                        center = center,
                        radius = this.size.width * 0.28f / 0.72f,
                    ),
                    radius = this.size.width,
                    center = center,
                )
            }
            if (logo) {
                Canvas(modifier = Modifier.size(size).graphicsLayer { alpha = markAlpha() }) {
                    drawLogoMark()
                }
            }
        }
    }
}

/**
 * App 图标上的 M（规范 6.1）：两段弧、笔画 = 直径 × 6.5 / 62、圆头；描边为光球同色系浅调渐变
 * （左 → 右 `#FFE6DA` → `#EFE8FF` → `#DDEFFF`），90% 不透明度。中心线取自 `ic_launcher_monochrome.xml`
 * 的 M 轮廓（108 画布，光球直径 62、左上角在 23），这里换算成光球直径的比例。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLogoMark() {
    val d = size.minDimension
    fun x(v: Float) = (v - 23f) / 62f * d
    fun y(v: Float) = (v - 23f) / 62f * d
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(x(39.5f), y(65f))
        cubicTo(x(39.5f), y(60.5f), x(39.9f), y(57.4f), x(40.85f), y(54.96f))
        cubicTo(x(41.9f), y(52.3f), x(43.1f), y(50.8f), x(44.3f), y(50.3f))
        cubicTo(x(46f), y(49.6f), x(47.6f), y(49.9f), x(49f), y(51f))
        cubicTo(x(50.8f), y(52.3f), x(52.4f), y(53.9f), x(54f), y(55.75f))
        cubicTo(x(55.6f), y(53.9f), x(57.2f), y(52.3f), x(59f), y(51f))
        cubicTo(x(60.4f), y(49.9f), x(62f), y(49.6f), x(63.7f), y(50.3f))
        cubicTo(x(64.9f), y(50.8f), x(66.1f), y(52.3f), x(67.15f), y(54.96f))
        cubicTo(x(68.1f), y(57.4f), x(68.5f), y(60.5f), x(68.5f), y(65f))
    }
    drawPath(
        path = path,
        brush = Brush.horizontalGradient(
            0f to Color(0xFFFFE6DA),
            0.5f to Color(0xFFEFE8FF),
            1f to Color(0xFFDDEFFF),
            startX = x(36.25f),
            endX = x(71.75f),
        ),
        alpha = 0.9f,
        style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = d * 6.5f / 62f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        ),
    )
}

private val OrbBase = Color(0xFFEDE6FF)

private val OrbSweep = arrayOf(
    0f to Color(0xFFFFC2A6),
    0.2f to Color(0xFFFF9EC2),
    0.42f to Color(0xFFB39BFF),
    0.64f to Color(0xFF8CC6FF),
    0.82f to Color(0xFFBFEFE3),
    1f to Color(0xFFFFC2A6),
)
