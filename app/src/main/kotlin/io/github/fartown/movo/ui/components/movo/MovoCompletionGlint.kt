package io.github.fartown.movo.ui.components.movo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoMotion

/**
 * Q8 完成的一道光（规范 9.3.2）：[trigger] 变为 true 时，卡片描边上一段品牌渐变光（长度 = 周长 25%，宽 1.5）
 * 从左上角沿边框走一圈（600ms `easing/standard`）后淡出。只播一次；减少动画时不播。
 */
internal fun Modifier.completionGlint(trigger: Boolean, cornerRadius: Dp): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(-1f) }
    LaunchedEffect(trigger, reduced) {
        if (trigger && !reduced && progress.value < 0f) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(600, easing = MovoMotion.EasingStandard))
            progress.animateTo(2f, tween(MovoMotion.FAST_EXIT, easing = MovoMotion.EasingExit))
        }
    }
    val path = remember { Path() }
    val segment = remember { Path() }
    drawWithContent {
        drawContent()
        val p = progress.value
        if (p < 0f || p >= 2f) return@drawWithContent
        val radius = cornerRadius.toPx()
        path.reset()
        path.addRoundRect(RoundRect(0f, 0f, size.width, size.height, radius, radius))
        val measure = PathMeasure().apply { setPath(path, true) }
        val length = measure.length
        val span = length * 0.25f
        val head = length * p.coerceAtMost(1f)
        val start = (head - span).coerceAtLeast(0f)
        segment.reset()
        measure.getSegment(start, head, segment, true)
        val alpha = if (p <= 1f) 1f else 2f - p
        drawPath(
            segment,
            brush = Brush.linearGradient(MovoColors.brandGradient),
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
            alpha = alpha,
        )
    }
}
