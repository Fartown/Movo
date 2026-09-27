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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
 * [contentSize] 为 true 时列表项自身高度变化也按 `standard` 过渡；它会按动画高度裁切，卡片不要用它——
 * `MovoCard` 默认在底色里面做高度过渡（否则过渡期间卡片底部圆角被裁成直角）。
 * 上方有卡片正在按 [contentSize] 长高 / 缩短时，下方各项传 [placement] = false：位置每帧直接跟着卡片的实际高度走，
 * 天然同步；否则移位动画每帧重新起跑、落后于卡片，会与卡片叠在一起。
 * 减少动画时只淡入淡出，位置与高度直接到位。
 */
@Composable
internal fun LazyItemScope.movoAnimateItem(contentSize: Boolean = false, placement: Boolean = true): Modifier {
    val reduced = LocalReducedMotion.current
    // 同一列表里有展开区正在长高 / 收起（[MovoExpandable] 报告，见 [MovoListResize]）时，各项位置直接跟着走。
    val followResize = LocalMovoListResize.current?.active == true
    return Modifier
        .animateItem(
            fadeInSpec = MovoMotion.fast(),
            placementSpec = if (reduced || followResize || !placement) null else MovoMotion.standard(),
            fadeOutSpec = MovoMotion.fastExit(),
        )
        .then(if (!reduced && contentSize) Modifier.animateContentSize(MovoMotion.standard<IntSize>()) else Modifier)
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
 * 展开内容（9.3「展开 / 收起」）：高度 `standard`，内容与高度同时开始淡入 `fast`（不等待，第一帧就有内容）；
 * 收起时内容淡出 `fast` 退场，高度同时收起。减少动画时只淡入淡出，高度直接到位。
 */
@Composable
internal fun MovoExpandable(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduced = LocalReducedMotion.current
    ReportMovoListResize(visible)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduced) {
            fadeIn(MovoMotion.fast())
        } else {
            expandVertically(MovoMotion.standard(), expandFrom = Alignment.Top) +
                fadeIn(tween(MovoMotion.FAST, easing = MovoMotion.EasingStandard))
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

/**
 * Lazy 列表里「有一项正在连续改变高度」（展开区长高 / 收起）的信号。
 *
 * 上方某项每帧改变高度时，下方各项的目标位置每帧都在变；`animateItem` 的移位动画每帧重新起跑，会一直落后于实际位置，
 * 表现为下方分区慢半拍、中间空出一截（真机：服务商详情收起请求头时「偏好与策略」晚约 70ms 才补上）。
 * 过渡期间让 [movoAnimateItem] 关掉移位动画，各项每帧直接按实际位置排布，与展开区天然同步。
 * 由使用 Lazy 列表的页面用 [ProvideMovoListResize] 包住列表。
 */
@androidx.compose.runtime.Stable
internal class MovoListResize {
    private var holders by androidx.compose.runtime.mutableIntStateOf(0)
    val active: Boolean get() = holders > 0

    suspend fun hold() {
        holders++
        try {
            kotlinx.coroutines.delay(MovoMotion.STANDARD.toLong() + LIST_RESIZE_SLACK_MS)
        } finally {
            holders--
        }
    }
}

internal val LocalMovoListResize = androidx.compose.runtime.staticCompositionLocalOf<MovoListResize?> { null }

/** 所在 [MovoCard] 的「卡内正在变高」信号：卡内展开区过渡期间，卡片的高度动画直接跟随，不再叠一层。 */
internal val LocalMovoCardResize = androidx.compose.runtime.staticCompositionLocalOf<MovoListResize?> { null }

@Composable
internal fun ProvideMovoListResize(content: @Composable () -> Unit) {
    val resize = remember { MovoListResize() }
    androidx.compose.runtime.CompositionLocalProvider(LocalMovoListResize provides resize, content = content)
}

/** 展开状态 [key] 变化（不含首次出现）时，在高度过渡期间通知所在列表与所在卡片。 */
@Composable
internal fun ReportMovoListResize(key: Any?) {
    val resize = LocalMovoListResize.current
    val card = LocalMovoCardResize.current
    if (resize == null && card == null) return
    // 首次组合（页面打开时已展开 / 已收起）不算一次过渡；之后每次 [key] 变化才在高度过渡期间通知列表。
    val initial = remember { key }
    val changed = remember { booleanArrayOf(false) }
    if (key != initial) changed[0] = true
    if (!changed[0]) return
    LaunchedEffect(key) {
        coroutineScope {
            if (card != null) launch { card.hold() }
            if (resize != null) launch { resize.hold() }
        }
    }
}

/** 过渡结束后再多等一两帧才恢复移位动画。 */
private const val LIST_RESIZE_SLACK_MS = 32L
