package io.github.fartown.movo.ui.components.movo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoSize

/*
 * 列表增删、展开收起的通用动效（规范 9.3「列表增删」「展开 / 收起」、9.8 减少动画）。
 */

/**
 * Lazy 列表项的增删与高度变化：新增淡入 `fast`，删除淡出 120ms，其余项移位 `standard`；
 * [contentSize] 为 true 时列表项自身高度变化也按 `standard` 过渡；它会按动画高度裁切，卡片请改用
 * `MovoCard(animateHeight = true)`，否则过渡期间卡片底部圆角被裁成直角。
 * 上方有卡片正在按 [contentSize] 长高 / 缩短时，下方各项传 [placement] = false：位置每帧直接跟着卡片的实际高度走，
 * 天然同步；否则移位动画每帧重新起跑、落后于卡片，会与卡片叠在一起。
 * 减少动画时只淡入淡出，位置与高度直接到位。
 */
@Composable
internal fun LazyItemScope.movoAnimateItem(contentSize: Boolean = false, placement: Boolean = true): Modifier {
    val reduced = LocalReducedMotion.current
    return if (reduced) {
        Modifier.animateItem(fadeInSpec = MovoMotion.fast(), placementSpec = null, fadeOutSpec = MovoMotion.fastExit())
    } else {
        Modifier
            .animateItem(
                fadeInSpec = MovoMotion.fast(),
                placementSpec = if (placement) MovoMotion.standard() else null,
                fadeOutSpec = MovoMotion.fastExit(),
            )
            .then(if (contentSize) Modifier.animateContentSize(MovoMotion.standard<IntSize>()) else Modifier)
    }
}

/** 非 Lazy 容器（一张卡里 forEach 出的行）的高度变化：`standard`；减少动画时直接到位。 */
@Composable
internal fun Modifier.movoAnimateContentSize(): Modifier =
    if (LocalReducedMotion.current) this else this.animateContentSize(MovoMotion.standard<IntSize>())

/**
 * 展开 / 收起箭头（9.3「展开 / 收起」）：单一 chevron-down 图标，向下 ↔ 向上旋转 180°，`fast`；不用换图标代替旋转。
 */
@Composable
internal fun MovoExpandChevron(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = MovoSize.iconSmall,
    tint: androidx.compose.ui.graphics.Color = MovoColors.textTertiary,
) {
    val reduced = LocalReducedMotion.current
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduced) snap() else MovoMotion.fast(),
        label = "expandChevron",
    )
    MovoIcon(
        MovoIcons.ChevronDown,
        contentDescription = null,
        size = size,
        tint = tint,
        modifier = modifier.graphicsLayer { rotationZ = rotation },
    )
}

/**
 * 展开内容（9.3「展开 / 收起」）：高度 `standard`，内容在高度过渡开始 40ms 后淡入 `fast`；
 * 收起时内容淡出 `fast` 退场，高度同时收起。减少动画时只淡入淡出，高度直接到位。
 */
@Composable
internal fun MovoExpandable(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduced) {
            fadeIn(MovoMotion.fast())
        } else {
            expandVertically(MovoMotion.standard(), expandFrom = Alignment.Top) +
                fadeIn(tween(MovoMotion.FAST, delayMillis = MovoMotion.STAGGER, easing = MovoMotion.EasingStandard))
        },
        exit = if (reduced) {
            fadeOut(MovoMotion.fastExit())
        } else {
            shrinkVertically(MovoMotion.standard(), shrinkTowards = Alignment.Top) + fadeOut(MovoMotion.fastExit())
        },
    ) {
        Column(content = content)
    }
}

/**
 * 数据到达前页面留空、到达后内容淡入（相当于从空白交叉淡化到内容，`fast`）。
 * 一开始就有数据时直接显示，不播动画。返回的 State 在 graphicsLayer 里读，不引起重组。
 */
@Composable
internal fun rememberContentReveal(ready: Boolean): State<Float> {
    val alpha = remember { Animatable(if (ready) 1f else 0f) }
    LaunchedEffect(ready) {
        if (ready && alpha.value < 1f) {
            alpha.animateTo(1f, MovoMotion.fast())
        }
    }
    return alpha.asState()
}

/** 按 [rememberContentReveal] 的值淡入。 */
internal fun Modifier.revealAlpha(alpha: State<Float>): Modifier = graphicsLayer { this.alpha = alpha.value }
