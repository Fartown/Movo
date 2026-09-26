package io.github.mangi.eta.ui.components.movo

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.theme.MovoMotion
import top.yukonga.miuix.kmp.nav.runtime.NavChange
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettlePhase
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitionScope
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition

/**
 * 页面切换（规范 9.3「页面切换」）：
 * - 前进：新页从右侧 24 处移入并淡入，`slow` + `enter`；旧页不动，被新页盖住。
 * - 点返回：当前页右移 24 淡出，250ms + `exit`。
 * - 返回手势（系统预测性返回 / 边缘滑动）：跟手，当前页缩小到 0.9 并露出上一页；松手完成或取消 `standard`。
 * - 减少动画：纯淡入淡出 `fast`，不位移不缩放（规范 9.8）。
 *
 * Miuix 导航按 `relativeDepth` 驱动：≤ 0 为当前 / 进场页（-1 完全移出，0 就位），> 0 为被盖住的页。
 * 程序触发的切换以线性进度驱动，这里按方向换算成进场 / 退场曲线：退场在前 250ms 内走完。
 */
internal fun movoNavTransition(reducedMotion: Boolean): NavTransition {
    val gestureSettle = NavSettleSpec.Tween(MovoMotion.STANDARD, MovoMotion.EasingStandard)
    val programmatic = NavSettleSpec.Tween(if (reducedMotion) MovoMotion.FAST else MovoMotion.SLOW, MovoMotion.EasingLinear)
    return navGraphicsTransition(
        opaqueDepth = 1f,
        motion = NavMotion(commit = gestureSettle, cancel = gestureSettle, programmatic = programmatic),
        scrim = { 0f },
    ) { scope ->
        val depth = scope.relativeDepth
        if (depth > 0f) return@navGraphicsTransition // 旧页不动
        val hidden = (-depth).coerceIn(0f, 1f) // 0 = 就位，1 = 完全离开
        if (reducedMotion) {
            alpha = 1f - hidden
            return@navGraphicsTransition
        }
        if (scope.isGestureDriven()) {
            NavGestureTracker.lastGestureMillis = NavGestureTracker.nowMillis()
            val scale = 1f - GESTURE_SCALE_DROP * hidden
            scaleX = scale
            scaleY = scale
            // 手势中只缩小、不淡出（露出的是缩小后四周的上一页），避免看起来「拖到一半就完成了」；
            // 松手确认返回后在收尾的 `standard` 时长里淡出。
            val settle = scope.settle
            alpha = if (scope.gesture == null && settle?.phase == NavSettlePhase.Commit) {
                1f - (settle.elapsedMillis / MovoMotion.STANDARD).coerceIn(0f, 1f)
            } else {
                1f
            }
            return@navGraphicsTransition
        }
        val shift = with(scope.density) { PAGE_SHIFT.toPx() }
        val visible = if (scope.change == NavChange.Pop) {
            // 退场：线性进度压缩到 250ms（总时长 360ms 的前 69%），`exit` 曲线。
            val t = (hidden * MovoMotion.SLOW / MovoMotion.SLOW_EXIT).coerceIn(0f, 1f)
            1f - MovoMotion.EasingExit.transform(t)
        } else {
            MovoMotion.EasingEnter.transform(1f - hidden)
        }
        translationX = shift * (1f - visible) * if (scope.layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) -1f else 1f
        alpha = visible
    }
}

private fun NavTransitionScope.isGestureDriven(): Boolean {
    if (gesture != null) return true
    val phase = settle?.phase ?: return false
    return phase == NavSettlePhase.Commit || phase == NavSettlePhase.Cancel
}

/** 最近一次由返回手势驱动页面的时刻；手势触发的返回不再播标题飞回（页面已被手势缩走）。 */
internal object NavGestureTracker {
    @Volatile
    var lastGestureMillis = 0L

    fun recentlyGestured(windowMillis: Long = 500L): Boolean =
        lastGestureMillis > 0L && nowMillis() - lastGestureMillis < windowMillis

    fun nowMillis(): Long = System.nanoTime() / 1_000_000L
}

private val PAGE_SHIFT = 24.dp
private const val GESTURE_SCALE_DROP = 0.1f

/**
 * Q4 容器变形的起点登记：执行卡 / 执行条排版后按执行卡 key 登记自己的窗口位置，打开执行详情页前取出作为起点。
 */
internal object RunDetailMorph {
    private val cards = HashMap<String, androidx.compose.ui.geometry.Rect>()

    /** 本次打开的起点；返回时仍按它收回。 */
    var origin: androidx.compose.ui.geometry.Rect? = null
        private set

    fun report(key: String, rect: androidx.compose.ui.geometry.Rect) {
        cards[key] = rect
    }

    fun prepare(key: String) {
        origin = cards[key]
    }
}

/**
 * Q4 容器变形（规范 9.3.2）：执行卡「查看」→ 执行详情页。可见区域从卡片框插值到全屏（圆角 28 → 0），
 * 底色从 `bg/surface` 过渡到 `bg/canvas`，页面内容在 30%–100% 淡入；`slow` + `standard`，返回反向，返回手势跟手。
 * 被盖住的对话页不动。没有起点（例如从日志直达）或减少动画时退回默认页面切换。
 */
internal fun movoMorphTransition(reducedMotion: Boolean, origin: () -> androidx.compose.ui.geometry.Rect?): NavTransition {
    val fallback = movoNavTransition(reducedMotion)
    if (reducedMotion) return fallback
    val gestureSettle = NavSettleSpec.Tween(MovoMotion.STANDARD, MovoMotion.EasingStandard)
    val programmatic = NavSettleSpec.Tween(MovoMotion.SLOW, MovoMotion.EasingStandard)
    return object : NavTransition {
        override val motion: NavMotion = NavMotion(commit = gestureSettle, cancel = gestureSettle, programmatic = programmatic)

        override fun scrimFraction(scope: NavTransitionScope): Float = 0f

        override fun androidx.compose.ui.Modifier.transformEntry(scope: NavTransitionScope): androidx.compose.ui.Modifier {
            val modifier = this
            val start = origin() ?: return with(fallback) { modifier.transformEntry(scope) }
            fun progress(): Float = if (scope.relativeDepth > 0f) 1f else 1f - (-scope.relativeDepth).coerceIn(0f, 1f)
            return modifier
                .graphicsLayer {
                    val p = progress()
                    if (p >= 1f) return@graphicsLayer
                    val full = androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)
                    val rect = androidx.compose.ui.geometry.lerp(start, full, p)
                    val radius = (MORPH_START_RADIUS.toPx()) * (1f - p)
                    shape = MorphShape(rect, radius)
                    clip = true
                }
                .drawBehind {
                    val p = progress()
                    if (p < 1f) drawRect(androidx.compose.ui.graphics.lerp(io.github.mangi.eta.ui.theme.MovoColors.bgSurface, io.github.mangi.eta.ui.theme.MovoColors.bgCanvas, p))
                }
                .graphicsLayer {
                    alpha = ((progress() - 0.3f) / 0.7f).coerceIn(0f, 1f)
                }
        }
    }
}

private class MorphShape(private val rect: androidx.compose.ui.geometry.Rect, private val radius: Float) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): androidx.compose.ui.graphics.Outline = androidx.compose.ui.graphics.Outline.Rounded(
        androidx.compose.ui.geometry.RoundRect(rect, androidx.compose.ui.geometry.CornerRadius(radius, radius)),
    )
}

private val MORPH_START_RADIUS = 28.dp
