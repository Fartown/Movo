package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import io.github.mangi.eta.ui.components.movo.MovoOrb
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoMotion
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoTypography
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

/**
 * 对话页的「飞行层」：Q1 文字飞成气泡、Q6 光球延续（规范 9.3.2 / 9.4「首页 → 对话」）。
 *
 * 发送时由起点（输入框文字行 / 能力卡 / 首页光球）登记起飞；新出现的用户气泡与等待中的小光球排版后登记落点。
 * 飞行层按 `slow` + `standard` 从起点插值到**实时**落点（列表同时滚动也跟得上），期间真实的气泡 / 小光球隐藏，
 * 落地后换回真实元素。坐标一律用窗口坐标。减少动画时不起飞，真实元素直接出现。
 */
@Stable
internal class ChatFlightController(private val reducedMotion: Boolean) {
    internal var bubble by mutableStateOf<BubbleFlight?>(null)
        private set
    internal var orb by mutableStateOf<OrbFlight?>(null)
        private set
    /** 首页光球当前位置（首页可见时由首页登记）。 */
    private var homeOrb: Rect? = null
    /** 起飞前已经出现过的用户气泡，不作为落点（避免同样文字的旧消息被认领）。 */
    private val seenBubbles = HashSet<String>()

    fun reportHomeOrb(rect: Rect?) {
        homeOrb = rect
    }

    /**
     * 发送：[start] 为起点框（输入框文字行时是文字本身的位置；能力卡时是整张卡），[filled] 表示起点本身有底色（能力卡）。
     */
    fun launch(text: String, start: Rect, filled: Boolean) {
        val request = text.trim()
        if (request.isEmpty() || reducedMotion) return
        bubble = BubbleFlight(request, start, filled)
        // 从首页发出（首页光球在屏幕上）时，光球按 Q6 同时飞向等待位置。
        orb = homeOrb?.let { OrbFlight(it) }
    }

    /** 用户气泡排版后登记（[box] 为气泡外框）；返回这条气泡此刻是否要隐藏（正在飞来）。 */
    fun reportBubble(id: String, request: String, box: Rect) {
        val flight = bubble
        if (flight != null && flight.targetId == null && id !in seenBubbles && request.trim() == flight.text) {
            flight.targetId = id
        }
        seenBubbles += id
        if (flight != null && flight.targetId == id) flight.target = box
    }

    /** 组合阶段即可判断（第一次出现时还没登记位置，也不能先闪一帧）。 */
    fun hidesBubble(id: String, request: String): Boolean {
        val flight = bubble ?: return false
        if (flight.landed) return false
        return flight.targetId == id ||
            (flight.targetId == null && id !in seenBubbles && request.trim() == flight.text)
    }

    /** 等待中的小光球排版后登记；null 表示它已消失（首个事件已到达）。 */
    fun reportWaitingOrb(rect: Rect?) {
        val flight = orb ?: return
        if (rect == null) flight.lost = true else flight.target = rect
    }

    fun hidesWaitingOrb(): Boolean = orb?.let { !it.landed } == true

    internal fun finishBubble(flight: BubbleFlight) {
        flight.landed = true
        if (bubble === flight) bubble = null
    }

    internal fun finishOrb(flight: OrbFlight) {
        flight.landed = true
        if (orb === flight) orb = null
    }
}

@Stable
internal class BubbleFlight(val text: String, val start: Rect, val startFilled: Boolean) {
    var targetId: String? = null
    var target by mutableStateOf<Rect?>(null)
    var landed by mutableStateOf(false)
    val progress = Animatable(0f)
}

@Stable
internal class OrbFlight(val start: Rect) {
    var target by mutableStateOf<Rect?>(null)
    var lost by mutableStateOf(false)
    var landed by mutableStateOf(false)
    val progress = Animatable(0f)
    val fade = Animatable(1f)
}

internal val LocalChatFlight = staticCompositionLocalOf<ChatFlightController?> { null }

internal fun LayoutCoordinates.windowRect(): Rect = boundsInWindow()

/** 在对话页最上层绘制飞行中的气泡与光球。放在对话页根 Box 里，铺满。 */
@Composable
internal fun ChatFlightOverlay(controller: ChatFlightController, modifier: Modifier = Modifier) {
    var origin by androidx.compose.runtime.remember { mutableStateOf(Offset.Zero) }
    Box(modifier = modifier.fillMaxSize().onGloballyPositioned { origin = it.windowRect().topLeft }) {
        controller.bubble?.let { BubbleFlightLayer(controller, it, origin) }
        controller.orb?.let { OrbFlightLayer(controller, it, origin) }
    }
}

@Composable
private fun BubbleFlightLayer(controller: ChatFlightController, flight: BubbleFlight, origin: Offset) {
    val target = flight.target
    LaunchedEffect(flight) {
        // 发送被拒绝等情况下没有新气泡出现：1s 后放弃，不留残影；最多存在 2 秒。
        delay(1_000)
        if (flight.target == null) controller.finishBubble(flight)
        delay(1_000)
        controller.finishBubble(flight)
    }
    LaunchedEffect(flight, target != null) {
        if (target == null) return@LaunchedEffect
        flight.progress.animateTo(1f, tween(MovoMotion.SLOW, easing = MovoMotion.EasingStandard))
        controller.finishBubble(flight)
    }
    val end = target ?: return
    val t = flight.progress.value
    val density = LocalDensity.current
    val padX = with(density) { 16.dp.toPx() }
    val padY = with(density) { 11.dp.toPx() }
    // 输入框起点：文字第一行与输入框文字重合，所以起点框 = 文字位置向外扩出气泡内边距，尺寸取气泡最终尺寸（最终排版）。
    val startBox = if (flight.startFilled) {
        flight.start
    } else {
        Rect(Offset(flight.start.left - padX, flight.start.top - padY), end.size)
    }
    val box = lerp(startBox, end, t)
    // 底色与描边在前 50% 淡入（能力卡起点本来就有底色）。
    val fill = if (flight.startFilled) 1f else (t / 0.5f).coerceIn(0f, 1f)
    // 能力卡起点：文字在 30%–100% 淡入（卡片文字随首页淡出）。
    val textAlpha = if (flight.startFilled) ((t - 0.3f) / 0.7f).coerceIn(0f, 1f) else 1f
    val radius = with(density) { lerp(if (flight.startFilled) 28.dp.toPx() else 20.dp.toPx(), 20.dp.toPx(), t) }
    val tail = with(density) { lerp(if (flight.startFilled) 28.dp.toPx() else 20.dp.toPx(), 8.dp.toPx(), t) }
    val shape = RoundedCornerShape(
        topStart = with(density) { radius.toDp() },
        topEnd = with(density) { radius.toDp() },
        bottomEnd = with(density) { tail.toDp() },
        bottomStart = with(density) { radius.toDp() },
    )
    Box(
        modifier = Modifier
            .offset { IntOffset((box.left - origin.x).roundToInt(), (box.top - origin.y).roundToInt()) }
            .size(with(density) { box.width.toDp() }, with(density) { box.height.toDp() })
            .graphicsLayer { clip = false }
            .background(MovoColors.bgSurface.copy(alpha = fill), shape)
            .border(MovoSize.hairline, MovoColors.borderHairline.copy(alpha = MovoColors.borderHairline.alpha * fill), shape),
    ) {
        Text(
            text = flight.text,
            style = MovoTypography.bodyReading,
            color = MovoColors.textPrimary,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 11.dp)
                .size(with(density) { (end.width - 2 * padX).coerceAtLeast(0f).toDp() }, with(density) { (end.height - 2 * padY).coerceAtLeast(0f).toDp() })
                .graphicsLayer { alpha = textAlpha },
        )
    }
}

@Composable
private fun OrbFlightLayer(controller: ChatFlightController, flight: OrbFlight, origin: Offset) {
    val target = flight.target
    LaunchedEffect(flight) {
        delay(1_500)
        if (flight.target == null) controller.finishOrb(flight)
        // 兜底：无论落点与首个事件如何，飞行中的光球最多存在 2 秒。
        delay(500)
        controller.finishOrb(flight)
    }
    LaunchedEffect(flight, target != null) {
        if (target == null) return@LaunchedEffect
        flight.progress.animateTo(1f, tween(MovoMotion.SLOW, easing = MovoMotion.EasingStandard))
        controller.finishOrb(flight)
    }
    LaunchedEffect(flight, flight.lost) {
        // 首个事件先到（等待位置已消失）：小光球淡出 120ms。
        if (!flight.lost) return@LaunchedEffect
        flight.fade.animateTo(0f, tween(MovoMotion.FAST_EXIT, easing = MovoMotion.EasingExit))
        controller.finishOrb(flight)
    }
    val end = target ?: flight.start
    val t = flight.progress.value
    val density = LocalDensity.current
    val box = lerp(flight.start, end, t)
    // 轻微弧线：横向外凸最多 24。
    val arc = with(density) { 24.dp.toPx() } * sin(PI * t).toFloat()
    val size = box.width
    Box(
        modifier = Modifier
            .offset { IntOffset((box.left + arc - origin.x).roundToInt(), (box.top - origin.y).roundToInt()) }
            .graphicsLayer { alpha = flight.fade.value },
    ) {
        MovoOrb(size = with(density) { size.toDp() })
    }
}
