package io.github.fartown.movo.ui.components.movo

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoMotion
import top.yukonga.miuix.kmp.basic.Text

/**
 * Q3 进行中光带（规范 9.3.2）：文字原色，一道宽 = 文字宽 40% 的高光带从左扫到右，中心为原色 35% 不透明度；
 * 扫过 1200ms `easing/standard` + 停 400ms 循环；[active] 为 false 或减少动画时是静态文字。
 */
@Composable
internal fun MovoShimmerText(
    text: String,
    style: TextStyle,
    color: Color,
    active: Boolean,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    val reduced = LocalReducedMotion.current
    if (!active || reduced) {
        Text(text, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
        return
    }
    var width by remember { mutableFloatStateOf(0f) }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = MovoMotion.SHIMMER_SWEEP + MovoMotion.SHIMMER_PAUSE
                0f at 0 using MovoMotion.EasingStandard
                1f at MovoMotion.SHIMMER_SWEEP
            },
        ),
        label = "shimmerProgress",
    )
    val band = width * 0.4f
    val center = -band / 2 + (width + band) * progress
    val brush = Brush.linearGradient(
        colors = listOf(color, color.copy(alpha = 0.35f), color),
        start = Offset(center - band / 2, 0f),
        end = Offset(center + band / 2, 0f),
    )
    androidx.compose.foundation.text.BasicText(
        text,
        style = style.copy(brush = brush),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        onTextLayout = { width = it.size.width.toFloat() },
    )
}

/**
 * 逐词切分（规范 9.3.2 Q2「首页问候逐词显现」）：中文按系统分词 `BreakIterator.getWordInstance`，英文按空格；
 * 只返回含可见字符的片段（空格不计入词序）。
 */
internal fun revealWordRanges(text: String, locale: java.util.Locale): List<IntRange> {
    if (text.isEmpty()) return emptyList()
    val iterator = java.text.BreakIterator.getWordInstance(locale)
    iterator.setText(text)
    val result = ArrayList<IntRange>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != java.text.BreakIterator.DONE) {
        if (text.substring(start, end).any { !it.isWhitespace() }) result += start until end
        start = end
        end = iterator.next()
    }
    return result
}

/** 词间隔：每词 24ms，整句（从第一个词开始到最后一个词显现完）不超过 400ms。 */
internal fun revealWordInterval(wordCount: Int): Float {
    if (wordCount <= 1) return 0f
    val budget = (MAX_SENTENCE_REVEAL_MS - MovoMotion.FAST).toFloat()
    return minOf(WORD_INTERVAL_MS, budget / (wordCount - 1))
}

/**
 * Q2 逐词显现的文字：第 i 个词（从 [firstWordIndex] 起算）在 `i × interval` 时开始淡入 + 模糊 4 → 0，
 * `fast` + `enter`；[elapsedMillis] 为共享时间轴（多行一起排词序），显现完即按普通文字绘制。
 */
@Composable
internal fun MovoWordRevealText(
    text: String,
    style: TextStyle,
    color: Color,
    elapsedMillis: () -> Float,
    firstWordIndex: Int,
    intervalMillis: Float,
    modifier: Modifier = Modifier,
) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val words = remember(text, locale) { revealWordRanges(text, locale) }
    var layout by remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    Text(
        text = text,
        style = style,
        color = color,
        onTextLayout = { layout = it },
        modifier = modifier.drawWithCache {
            val layers = List(words.size) { obtainGraphicsLayer() }
            val blurMax = 4.dp.toPx()
            onDrawWithContent {
                val current = layout
                val elapsed = elapsedMillis()
                val lastStart = (firstWordIndex + words.size - 1) * intervalMillis
                if (current == null || elapsed >= lastStart + MovoMotion.FAST) {
                    drawContent()
                    return@onDrawWithContent
                }
                val contentScope = this
                words.forEachIndexed { index, range ->
                    val fraction = ((elapsed - (firstWordIndex + index) * intervalMillis) / MovoMotion.FAST).coerceIn(0f, 1f)
                    if (fraction <= 0f) return@forEachIndexed
                    val path = current.getPathForRange(range.first, range.last + 1)
                    if (fraction >= 1f) {
                        clipPath(path) { contentScope.drawContent() }
                        return@forEachIndexed
                    }
                    val eased = MovoMotion.EasingEnter.transform(fraction)
                    val layer = layers[index]
                    layer.alpha = eased
                    val radius = blurMax * (1f - eased)
                    layer.renderEffect = if (radius > 0.05f) {
                        androidx.compose.ui.graphics.BlurEffect(radius, radius, androidx.compose.ui.graphics.TileMode.Decal)
                    } else {
                        null
                    }
                    layer.record { contentScope.drawContent() }
                    clipPath(path) { drawLayer(layer) }
                }
            }
        },
    )
}

private const val WORD_INTERVAL_MS = 24f
private const val MAX_SENTENCE_REVEAL_MS = 400
