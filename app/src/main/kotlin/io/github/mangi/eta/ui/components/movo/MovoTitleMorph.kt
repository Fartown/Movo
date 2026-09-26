package io.github.mangi.eta.ui.components.movo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoMotion
import io.github.mangi.eta.ui.theme.MovoTypography
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

/**
 * Q4 设置行 → 二级页（规范 9.3.2）：行标题从行内位置移动到顶栏居中标题位置，只移动不缩放，`slow` + `standard`；
 * 其余内容按页面切换。点行时登记起点，二级页顶栏标题排版后登记落点（标题文字相同才认领），
 * 落地前顶栏标题先隐藏；没有等到落点（行打开的是对话框、外部页面或标题不同）就什么都不画。
 * 返回（按钮、系统返回、手势）时反向：标题从顶栏飞回原来那一行，期间顶栏标题与行标题都隐藏。
 */
internal object TitleMorph {
    internal var flight by mutableStateOf<TitleFlight?>(null)
        private set

    /** 最近一次去程：返回时据此飞回。 */
    private var trip: Trip? = null

    private class Trip(val title: String, val row: Rect) {
        var route: Any? = null
        var topBar: Rect? = null
    }

    fun launch(title: String, start: Rect) {
        flight = TitleFlight(title, start)
        trip = Trip(title, start)
    }

    /** 去程刚起飞时推入的页面即这次标题移动的目的页。 */
    fun bindRoute(route: Any) {
        val current = trip ?: return
        if (current.route == null && flight?.returning == false) current.route = route
    }

    fun reportTarget(title: String, rect: Rect) {
        trip?.takeIf { it.title == title && it.route != null }?.topBar = rect
        val current = flight ?: return
        if (current.title == title && !current.landed && !current.returning) current.target = rect
    }

    /** 离开 [route]：若它是标题移动的目的页，标题从顶栏飞回原来那一行。 */
    fun onPop(route: Any) {
        val current = trip?.takeIf { it.route == route } ?: return
        trip = null
        // 返回手势已经把页面缩走、露出上一页：标题不再从顶栏飞回（否则松手后又飞一次）。
        if (NavGestureTracker.recentlyGestured()) return
        val from = current.topBar ?: return
        flight = TitleFlight(current.title, from, returning = true).apply { target = current.row }
    }

    /** 顶栏标题：去程落地前、回程途中都隐藏。 */
    fun hides(title: String): Boolean = flight?.let { it.title == title && !it.landed } == true

    /** 设置行标题：回程途中隐藏（由飞行中的标题落回原位）。 */
    fun hidesRow(title: String): Boolean = flight?.let { it.returning && it.title == title && !it.landed } == true

    internal fun finish(target: TitleFlight) {
        target.landed = true
        if (flight === target) flight = null
    }
}

@Stable
internal class TitleFlight(val title: String, val start: Rect, val returning: Boolean = false) {
    var target by mutableStateOf<Rect?>(null)
    var landed by mutableStateOf(false)
    val progress = Animatable(0f)
}

/** 放在导航容器之上、铺满窗口。 */
@Composable
internal fun TitleMorphOverlay() {
    val flight = TitleMorph.flight ?: return
    val reduced = io.github.mangi.eta.ui.theme.LocalReducedMotion.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val target = flight.target
    LaunchedEffect(flight) {
        delay(MovoMotion.SLOW.toLong())
        if (flight.target == null) TitleMorph.finish(flight)
    }
    LaunchedEffect(flight, target != null) {
        if (target == null) return@LaunchedEffect
        if (!reduced) flight.progress.animateTo(1f, tween(MovoMotion.SLOW, easing = MovoMotion.EasingStandard))
        TitleMorph.finish(flight)
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInWindow().topLeft }) {
        val end = target ?: return@Box
        if (reduced) return@Box
        val position = lerp(flight.start, end, flight.progress.value).topLeft
        Text(
            text = flight.title,
            style = MovoTypography.bodyStrong,
            color = MovoColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.offset { IntOffset((position.x - origin.x).roundToInt(), (position.y - origin.y).roundToInt()) },
        )
    }
}
