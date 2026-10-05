package io.github.fartown.movo.ui.components.movo

import io.github.fartown.movo.ui.theme.MovoMotion
import top.yukonga.miuix.kmp.nav.runtime.NavChange
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettlePhase
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavSwipeEdge
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitionScope
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition

/**
 * 页面切换（规范 9.3「页面切换」）：
 * - 前进：新页从右侧移入；旧页不动，被不透明的新页盖住。
 * - 点返回 / 系统返回键：当前页右移退出，露出上一页。页面文字不会在淡化时互相穿透。
 * - 返回手势（从屏幕边缘拖动的预测性返回）：跟手，当前页缩小到 0.9 并露出上一页；松手完成或取消 `standard`。
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
        val rtlSign = if (scope.layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) -1f else 1f
        if (scope.isGestureDriven()) {
            if (scope.hasSwipeEdge()) {
                applyGestureBack(scope, hidden)
            } else {
                // 系统返回键也经由预测性返回收尾，但没有跟手过程：与点返回一致，整页右移退出，不淡出叠字。
                translationX = size.width * hidden * rtlSign
                alpha = 1f
            }
            return@navGraphicsTransition
        }
        val visible = if (scope.change == NavChange.Pop) {
            // 退场：线性进度压缩到 250ms（总时长 360ms 的前 69%），`exit` 曲线。
            val t = (hidden * MovoMotion.SLOW / MovoMotion.SLOW_EXIT).coerceIn(0f, 1f)
            1f - MovoMotion.EasingExit.transform(t)
        } else {
            MovoMotion.EasingEnter.transform(1f - hidden)
        }
        // 让不透明页面边缘横向移动，只有两页各自的可见区域相邻；两套正文不再透叠。
        translationX = size.width * (1f - visible) * rtlSign
        alpha = 1f
    }
}

/**
 * 返回手势跟手：只缩小、不淡出（露出的是缩小后四周的上一页），避免看起来「拖到一半就完成了」；
 * 松手确认返回后在收尾的 `standard` 时长里淡出。系统给的手势进度在部分机型上拖出很短就接近 1，
 * 所以手势阶段只能用这种幅度有限的变化。
 */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.applyGestureBack(scope: NavTransitionScope, hidden: Float) {
    NavGestureTracker.lastGestureMillis = NavGestureTracker.nowMillis()
    val scale = 1f - GESTURE_SCALE_DROP * hidden
    scaleX = scale
    scaleY = scale
    // 确认返回后的收尾：导航库在收尾期间仍保留手势上下文（gesture 非空），只能按收尾阶段判断。
    val settle = scope.settle
    // 只淡出正在离开的页（hidden > 0）；回到栈顶的页在最后一帧深度正好为 0，不能跟着变透明。
    alpha = if (settle?.phase == NavSettlePhase.Commit && hidden > 0f) {
        1f - (settle.elapsedMillis / MovoMotion.STANDARD).coerceIn(0f, 1f)
    } else {
        1f
    }
}

/**
 * 真正从屏幕边缘拖出的返回手势。Android 14+ 的系统返回键也会合成一次 onBackStarted
 * （EDGE_LEFT、触点 (0,0)、进度 0）后立即提交，只能按触点和进度把它和真手势区分开。
 */
private fun NavTransitionScope.hasSwipeEdge(): Boolean {
    val g = gesture ?: return false
    if (g.swipeEdge == NavSwipeEdge.None) return false
    return g.initialTouchY != 0f || g.touchY != 0f || g.progress > 0f
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

private const val GESTURE_SCALE_DROP = 0.1f
