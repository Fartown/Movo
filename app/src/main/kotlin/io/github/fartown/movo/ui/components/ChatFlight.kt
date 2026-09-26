package io.github.fartown.movo.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import io.github.fartown.movo.ui.components.movo.MovoOrb
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoTypography
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
    // 窗口坐标原点：只在放置 / 图层阶段读取。
    val origin = remember { mutableStateOf(Offset.Zero) }
    Box(modifier = modifier.fillMaxSize().onGloballyPositioned { origin.value = it.windowRect().topLeft }) {
        controller.bubble?.let { BubbleFlightLayer(controller, it) { origin.value } }
        controller.orb?.let { OrbFlightLayer(controller, it) { origin.value } }
    }
}

/**
 * Q1 飞行中的气泡。进度与实时落点逐帧变化，只在放置（位置）、测量（外框尺寸）与绘制（底色、描边、圆角、文字透明度）阶段读取；
 * 组合期只关心「有没有落点」，飞行中不重组。文字按落点（气泡最终尺寸）排版，最终尺寸不变时不重新排版。
 */
@Composable
private fun BubbleFlightLayer(controller: ChatFlightController, flight: BubbleFlight, origin: () -> Offset) {
    val hasTarget by remember(flight) { derivedStateOf { flight.target != null } }
    LaunchedEffect(flight) {
        // 发送被拒绝等情况下没有新气泡出现：1s 后放弃，不留残影；最多存在 2 秒。
        delay(1_000)
        if (flight.target == null) controller.finishBubble(flight)
        delay(1_000)
        controller.finishBubble(flight)
    }
    LaunchedEffect(flight, hasTarget) {
        if (!hasTarget) return@LaunchedEffect
        flight.progress.animateTo(1f, tween(MovoMotion.SLOW, easing = MovoMotion.EasingStandard))
        controller.finishBubble(flight)
    }
    if (!hasTarget) return
    val density = LocalDensity.current
    val padX = with(density) { 16.dp.toPx() }
    val padY = with(density) { 11.dp.toPx() }
    val hairline = with(density) { MovoSize.hairline.toPx() }
    val startRadius = with(density) { if (flight.startFilled) 28.dp.toPx() else 20.dp.toPx() }
    val endRadius = with(density) { 20.dp.toPx() }
    val endTail = with(density) { 8.dp.toPx() }
    // 当前外框（窗口坐标）。输入框起点：文字第一行与输入框文字重合，所以起点框 = 文字位置向外扩出气泡内边距，
    // 尺寸取气泡最终尺寸（最终排版）。
    fun currentBox(end: Rect): Rect {
        val startBox = if (flight.startFilled) {
            flight.start
        } else {
            Rect(Offset(flight.start.left - padX, flight.start.top - padY), end.size)
        }
        return lerp(startBox, end, flight.progress.value)
    }
    val fillPath = remember { Path() }
    val strokePath = remember { Path() }
    Box(
        modifier = Modifier
            .offset {
                val end = flight.target ?: return@offset IntOffset.Zero
                val box = currentBox(end)
                val o = origin()
                IntOffset((box.left - o.x).roundToInt(), (box.top - o.y).roundToInt())
            }
            .drawBehind {
                val t = flight.progress.value
                // 底色与描边在前 50% 淡入（能力卡起点本来就有底色）。
                val fill = if (flight.startFilled) 1f else (t / 0.5f).coerceIn(0f, 1f)
                if (fill <= 0f) return@drawBehind
                val radius = lerp(startRadius, endRadius, t)
                val tail = lerp(startRadius, endTail, t)
                fillPath.reset()
                fillPath.addRoundRect(bubbleRoundRect(0f, 0f, size.width, size.height, radius, tail))
                drawPath(fillPath, MovoColors.bgSurface, alpha = fill)
                // 描边与 Modifier.border 一样画在框内。
                val inset = hairline / 2
                strokePath.reset()
                strokePath.addRoundRect(
                    bubbleRoundRect(inset, inset, size.width - inset, size.height - inset, radius - inset, tail - inset),
                )
                drawPath(strokePath, MovoColors.borderHairline, alpha = fill, style = Stroke(hairline))
            }
            .layout { measurable, _ ->
                val end = flight.target ?: Rect.Zero
                val box = currentBox(end)
                val placeable = measurable.measure(
                    Constraints.fixed(end.width.roundToInt().coerceAtLeast(0), end.height.roundToInt().coerceAtLeast(0)),
                )
                layout(box.width.roundToInt().coerceAtLeast(0), box.height.roundToInt().coerceAtLeast(0)) {
                    placeable.place(0, 0)
                }
            },
    ) {
        Text(
            text = flight.text,
            style = MovoTypography.bodyReading,
            color = MovoColors.textPrimary,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 11.dp)
                .fillMaxSize()
                .graphicsLayer {
                    // 能力卡起点：文字在 30%–100% 淡入（卡片文字随首页淡出）。
                    alpha = if (flight.startFilled) ((flight.progress.value - 0.3f) / 0.7f).coerceIn(0f, 1f) else 1f
                },
        )
    }
}

private fun bubbleRoundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float, tail: Float): RoundRect {
    val r = CornerRadius(radius.coerceAtLeast(0f))
    return RoundRect(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        topLeftCornerRadius = r,
        topRightCornerRadius = r,
        bottomRightCornerRadius = CornerRadius(tail.coerceAtLeast(0f)),
        bottomLeftCornerRadius = r,
    )
}

/**
 * Q6 飞行中的光球。光球按起点尺寸（首页 52）固定排版，飞行只改图层的缩放、位移与透明度：
 * 不逐帧重组、不逐帧重新布局，[MovoOrb] 的模糊半径也不逐帧重建（缩放后的模糊与小光球同比例）。
 */
@Composable
private fun OrbFlightLayer(controller: ChatFlightController, flight: OrbFlight, origin: () -> Offset) {
    val hasTarget by remember(flight) { derivedStateOf { flight.target != null } }
    LaunchedEffect(flight) {
        delay(1_500)
        if (flight.target == null) controller.finishOrb(flight)
        // 兜底：无论落点与首个事件如何，飞行中的光球最多存在 2 秒。
        delay(500)
        controller.finishOrb(flight)
    }
    LaunchedEffect(flight, hasTarget) {
        if (!hasTarget) return@LaunchedEffect
        flight.progress.animateTo(1f, tween(MovoMotion.SLOW, easing = MovoMotion.EasingStandard))
        controller.finishOrb(flight)
    }
    LaunchedEffect(flight, flight.lost) {
        // 首个事件先到（等待位置已消失）：小光球淡出 120ms。
        if (!flight.lost) return@LaunchedEffect
        flight.fade.animateTo(0f, tween(MovoMotion.FAST_EXIT, easing = MovoMotion.EasingExit))
        controller.finishOrb(flight)
    }
    val density = LocalDensity.current
    val arcPx = with(density) { 24.dp.toPx() }
    // MovoOrb 小于 24 时是小光球（不画外圈光）：缩放到这个尺寸以下时同样去掉外圈光。
    val miniPx = with(density) { 24.dp.toPx() }
    val startSize = flight.start.width
    fun currentBox(): Rect = lerp(flight.start, flight.target ?: flight.start, flight.progress.value)
    Box(
        modifier = Modifier.graphicsLayer {
            val t = flight.progress.value
            val box = currentBox()
            // 轻微弧线：横向外凸最多 24。
            val arc = arcPx * sin(PI * t).toFloat()
            val o = origin()
            val scale = if (startSize > 0f) box.width / startSize else 1f
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = scale
            scaleY = scale
            translationX = box.left + arc - o.x
            translationY = box.top - o.y
            alpha = flight.fade.value
        },
    ) {
        MovoOrb(
            size = with(density) { startSize.toDp() },
            glowAlpha = { if (currentBox().width < miniPx) 0f else 1f },
            // 起飞时与首页 logo 光球一致；缩到一半尺寸前 M 淡出，落地成执行卡里的小光球。
            logo = true,
            markAlpha = {
                val w = currentBox().width
                if (startSize <= 0f) 1f else ((w / startSize - 0.5f) / 0.5f).coerceIn(0f, 1f)
            },
        )
    }
}
