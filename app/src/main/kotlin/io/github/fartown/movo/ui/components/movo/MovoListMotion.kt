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
import kotlin.math.roundToInt
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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

    suspend fun hold(durationMillis: Long = MovoMotion.STANDARD.toLong()) {
        holders++
        try {
            kotlinx.coroutines.delay(durationMillis + LIST_RESIZE_SLACK_MS)
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

/** 展开状态 [key] 变化（不含首次出现）时，在高度过渡（[durationMillis]）期间通知所在列表与所在卡片。 */
@Composable
internal fun ReportMovoListResize(key: Any?, durationMillis: Long = MovoMotion.STANDARD.toLong()) {
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
            if (card != null) launch { card.hold(durationMillis) }
            if (resize != null) launch { resize.hold(durationMillis) }
        }
    }
}

/** 过渡结束后再多等一两帧才恢复移位动画。 */
private const val LIST_RESIZE_SLACK_MS = 32L

/**
 * 卡片里逐行排出（非 Lazy）的行的增删（规范 9.3「列表增删」）：新增行从顶部展开 `standard`、内容同时淡入 `fast`；
 * 删除行内容先淡出 120ms，随后高度收起 `standard`，播完才真正移除。首次出现的行不播放。
 * 变化期间通知所在卡片与列表（[ReportMovoListResize]），卡片高度直接跟随行的每一帧、下方各项跟着实际位置走，
 * 不会出现「行已消失、卡片还没缩，下半截一块空白」或「新行一帧出现、下面的内容先被裁掉」。
 */
@Composable
internal fun <T> MovoAnimatedRows(
    items: List<T>,
    key: (T) -> Any,
    content: @Composable (T) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val rows = remember { androidx.compose.runtime.mutableStateListOf<AnimatedRow<T>>() }
    val firstComposition = remember { booleanArrayOf(true) }
    // 按新列表的顺序排，离场中的行留在它原来的位置之后，直到退场播完。
    val byKey = rows.associateBy { it.key }
    val newKeys = items.mapTo(HashSet(), key)
    val merged = ArrayList<AnimatedRow<T>>(items.size + rows.size)
    var next = 0
    fun take(item: T): AnimatedRow<T> {
        val k = key(item)
        val existing = byKey[k]
        return if (existing != null) {
            existing.item = item
            existing.state.targetState = true
            existing
        } else {
            AnimatedRow(k, item, androidx.compose.animation.core.MutableTransitionState(firstComposition[0] || reduced).apply { targetState = true })
        }
    }
    for (row in rows) {
        if (row.key in newKeys) {
            while (next < items.size) {
                val item = items[next++]
                merged += take(item)
                if (key(item) == row.key) break
            }
        } else {
            row.state.targetState = false
            merged += row
        }
    }
    while (next < items.size) merged += take(items[next++])
    // 退场播完的行真正移除。
    merged.removeAll { !it.state.targetState && !it.state.currentState && it.state.isIdle }
    if (merged.map { it.key } != rows.map { it.key }) {
        rows.clear()
        rows.addAll(merged)
    }
    firstComposition[0] = false
    ReportMovoListResize(items.map(key), durationMillis = (MovoMotion.FAST_EXIT + MovoMotion.STANDARD).toLong())
    for (row in merged) {
        androidx.compose.runtime.key(row.key) {
            AnimatedVisibility(
                visibleState = row.state,
                enter = if (reduced) fadeIn(MovoMotion.fast()) else fadeIn(MovoMotion.fast()) +
                    expandVertically(MovoMotion.standard(), expandFrom = Alignment.Top),
                exit = if (reduced) fadeOut(MovoMotion.fastExit()) else fadeOut(MovoMotion.fastExit()) +
                    shrinkVertically(
                        tween(MovoMotion.STANDARD, delayMillis = MovoMotion.FAST_EXIT, easing = MovoMotion.EasingStandard),
                        shrinkTowards = Alignment.Top,
                    ),
            ) {
                content(row.item)
            }
        }
    }
}

@androidx.compose.runtime.Stable
private class AnimatedRow<T>(
    val key: Any,
    item: T,
    val state: androidx.compose.animation.core.MutableTransitionState<Boolean>,
) {
    var item by androidx.compose.runtime.mutableStateOf(item)
}

/**
 * 可能超过可见区的内容（执行卡全部步骤、长思考、长回答）展开 / 收起用的高度过渡（规范 9.3「展开 / 收起」）：
 * 只有「元素顶部到可见区底边」这一段做 `standard` 过渡，看不见的部分在可见区外一次到位。
 * 直接按全高过渡时，`standard` 曲线前段很快，几千 px 的内容第一帧就越过了整块可见区域，看起来是一帧展开；
 * 按整屏高度封顶也不够——卡片在屏幕中下方时，可见的只有输入栏上方那一小段，过渡仍然只露出 30ms（真机 verify3）。
 * 展开：可见部分 0 → 可见高度按曲线长出，结束时补齐到全高；收起：先把可见区外那段收掉，再按曲线收到 0。
 * 用法：[rememberVisibleHeightCap] + 在做高度过渡的节点上挂 [trackVisibleHeightCap]，再把它传给
 * [rememberViewportCappedStandard]。
 */
@androidx.compose.runtime.Stable
internal class VisibleHeightCap internal constructor(
    private val view: android.view.View,
    private val visibleBottom: () -> Int?,
) {
    /** 做高度过渡的节点顶边（窗口坐标）；还没排过版时为 null，按整窗高度封顶。 */
    internal var topPx: Int? = null

    /** 做高度过渡的节点底边（窗口坐标）。 */
    internal var bottomPx: Int? = null


    fun remainingPx(): Int {
        val bottom = visibleBottom() ?: view.rootView.height
        val top = topPx ?: 0
        return (bottom - top).coerceIn(1, bottom.coerceAtLeast(1))
    }
}

/** 列表可见区的底边（窗口坐标，已扣掉压在列表上的输入栏等）；没提供时按整窗高度。 */
internal val LocalVisibleViewportBottom = androidx.compose.runtime.staticCompositionLocalOf<() -> Int?> { { null } }

@Composable
internal fun rememberVisibleHeightCap(): VisibleHeightCap {
    val view = androidx.compose.ui.platform.LocalView.current
    val visibleBottom = LocalVisibleViewportBottom.current
    return remember(view, visibleBottom) { VisibleHeightCap(view, visibleBottom) }
}

internal fun Modifier.trackVisibleHeightCap(cap: VisibleHeightCap): Modifier =
    this.then(
        Modifier.onGloballyPositioned {
            val top = it.positionInWindow().y.roundToInt()
            cap.topPx = top
            cap.bottomPx = top + it.size.height
        },
    )

@Composable
internal fun rememberViewportCappedStandard(
    cap: VisibleHeightCap,
    delayMillis: Int = 0,
): androidx.compose.animation.core.FiniteAnimationSpec<IntSize> =
    remember(cap, delayMillis) {
        ViewportCappedSizeSpec(cap::remainingPx, tween(MovoMotion.STANDARD, delayMillis, MovoMotion.EasingStandard))
    }

private class ViewportCappedSizeSpec(
    private val capPx: () -> Int,
    private val tween: androidx.compose.animation.core.TweenSpec<IntSize>,
) : androidx.compose.animation.core.FiniteAnimationSpec<IntSize> {
    override fun <V : androidx.compose.animation.core.AnimationVector> vectorize(
        converter: androidx.compose.animation.core.TwoWayConverter<IntSize, V>,
    ): androidx.compose.animation.core.VectorizedFiniteAnimationSpec<V> {
        val inner = tween.vectorize(converter)
        return object : androidx.compose.animation.core.VectorizedFiniteAnimationSpec<V> {
            private fun capped(value: V): V {
                val size = converter.convertFromVector(value)
                return converter.convertToVector(IntSize(size.width, size.height.coerceAtMost(capPx())))
            }

            override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
                if (playTimeNanos >= inner.getDurationNanos(initialValue, targetValue, initialVelocity)) {
                    targetValue
                } else {
                    inner.getValueFromNanos(playTimeNanos, capped(initialValue), capped(targetValue), initialVelocity)
                }

            override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
                inner.getVelocityFromNanos(playTimeNanos, capped(initialValue), capped(targetValue), initialVelocity)

            override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long =
                inner.getDurationNanos(initialValue, targetValue, initialVelocity)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ViewportCappedSizeSpec && other.capPx == capPx && other.tween == tween

    override fun hashCode(): Int = 31 * capPx.hashCode() + tween.hashCode()
}
