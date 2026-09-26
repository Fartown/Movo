package io.github.fartown.movo.ui.components

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import io.github.fartown.movo.ui.components.movo.MovoOrb
import io.github.fartown.movo.ui.components.movo.MovoSectionHeader
import io.github.fartown.movo.ui.components.movo.PressKind
import io.github.fartown.movo.ui.components.movo.movoClickable
import io.github.fartown.movo.ui.components.movo.movoElevation
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoElevation
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIconData
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.components.movo.MovoWordRevealText
import io.github.fartown.movo.ui.components.movo.revealWordInterval
import io.github.fartown.movo.ui.components.movo.revealWordRanges
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import java.util.Calendar
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Text

/** 「全部能力 ›」打开工具能力目录；由 App 根提供（对话浮层里为 null，不显示该入口）。 */
internal val LocalOpenCapabilities = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * 首页（规范 8.0，Figma「01 · 首页」）：顶栏下 16：光球 52 → 24 → 日期 → 8 → 两行问候（Display/Greeting）
 * → 32 →「试试让 Movo」+ 横滑能力卡。
 * 「为你留意」需要通知与日程的摘要数据源，当前还没有，按规范「没有内容时整张卡隐藏」不显示。
 * 角色会话保留原来的「角色名 + 故事从这里开始」。
 */
@Composable
internal fun MovoHomeContent(
    characterName: String?,
    showCapabilities: Boolean,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (characterName != null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(characterName, style = MovoTypography.titlePage, color = MovoColors.textPrimary)
                Spacer(Modifier.height(MovoSpacing.sm))
                Text(stringResource(R.string.movo_home_story_start), style = MovoTypography.bodyRegular, color = MovoColors.textSecondary)
            }
        }
        return
    }
    val openCapabilities = LocalOpenCapabilities.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(MovoSpacing.lg))
        val chatFlight = LocalChatFlight.current
        androidx.compose.runtime.DisposableEffect(chatFlight) { onDispose { chatFlight?.reportHomeOrb(null) } }
        EntranceItem(step = 0) {
            // Q6：从首页发出第一句时，光球由飞行层接走（缩小飞向执行卡标题位置）。
            // 9.7：首页上开始语音对话时，光球跟随语音状态（聆听 / 思考 / 播报）。
            val voice by io.github.fartown.movo.agent.voice.session.VoiceSessionManager.state.collectAsState()
            var speechKey by remember { mutableStateOf(0) }
            LaunchedEffect(voice.channel) {
                if (voice.channel == io.github.fartown.movo.agent.voice.session.VoiceChannel.Hearing) speechKey++
            }
            MovoOrb(
                size = 52.dp,
                state = when (voice.channel) {
                    io.github.fartown.movo.agent.voice.session.VoiceChannel.Listening,
                    io.github.fartown.movo.agent.voice.session.VoiceChannel.Hearing,
                    io.github.fartown.movo.agent.voice.session.VoiceChannel.Connecting -> io.github.fartown.movo.ui.components.movo.OrbState.LISTENING
                    io.github.fartown.movo.agent.voice.session.VoiceChannel.Thinking -> io.github.fartown.movo.ui.components.movo.OrbState.THINKING
                    io.github.fartown.movo.agent.voice.session.VoiceChannel.Speaking -> io.github.fartown.movo.ui.components.movo.OrbState.SPEAKING
                    io.github.fartown.movo.agent.voice.session.VoiceChannel.Off -> io.github.fartown.movo.ui.components.movo.OrbState.STANDBY
                },
                // 语音对话引擎目前只给出「是否听到说话」：听到时按 0.6 电平起伏。
                level = if (voice.channel == io.github.fartown.movo.agent.voice.session.VoiceChannel.Hearing ||
                    voice.channel == io.github.fartown.movo.agent.voice.session.VoiceChannel.Speaking) 0.6f else 0f,
                speechKey = speechKey,
                modifier = Modifier
                    .padding(start = MovoSpacing.pageEdge)
                    .onGloballyPositioned { chatFlight?.reportHomeOrb(it.windowRect()) },
            )
        }
        Spacer(Modifier.height(MovoSpacing.xxl))
        // 问候自带进场：日期淡入，两行问候按 Q2 逐词显现（不再整块淡入上移）。
        Greeting()
        AnimatedVisibility(
            visible = showCapabilities,
            enter = fadeIn(MovoMotion.standard()),
            exit = fadeOut(MovoMotion.fastExit()),
        ) {
            EntranceItem(step = 2) {
                Column {
                    Spacer(Modifier.height(MovoSpacing.section))
                    MovoSectionHeader(
                        text = stringResource(R.string.movo_home_try),
                        actionLabel = stringResource(R.string.movo_home_all_capabilities).takeIf { openCapabilities != null },
                        onAction = openCapabilities,
                        modifier = Modifier.padding(start = MovoSpacing.pageEdge, end = 15.dp),
                    )
                    Spacer(Modifier.height(MovoSpacing.md))
                    CapabilityCards(onSend = onSend)
                }
            }
        }
        Spacer(Modifier.height(MovoSpacing.lg))
    }
}

/** 问候：日期（Label/Medium 三级色）→ 8 → 第一行按时段（主色）+ 第二行「今天想交给我什么？」（三级色）。 */
@Composable
private fun Greeting() {
    val locale: Locale = LocalConfiguration.current.locales[0]
    val now = remember { Date() }
    val hour = remember(now) { Calendar.getInstance().apply { time = now }.get(Calendar.HOUR_OF_DAY) }
    val dateLabel = remember(now, locale) {
        val date = java.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale).format(now)
        val weekday = java.text.SimpleDateFormat("EEEE", locale).format(now)
        // 中文并列用不带空格的「·」（规范 5），其他语言两侧留空格。
        if (locale.language == "zh") "$date·$weekday" else "$date · $weekday"
    }
    val greeting = stringResource(
        when (hour) {
            in 5..11 -> R.string.movo_home_morning
            in 12..17 -> R.string.movo_home_afternoon
            else -> R.string.movo_home_evening
        },
    )
    val ask = stringResource(R.string.movo_home_ask)
    // 问候按 Q2 逐词显现（规范 9.4「首页进场」）：两行共用一条时间轴，词序接续，整句 ≤ 400ms；减少动画时直接出现。
    val reduced = LocalReducedMotion.current
    val firstLineWords = remember(greeting, locale) { revealWordRanges(greeting, locale).size }
    val totalWords = remember(greeting, ask, locale) { firstLineWords + revealWordRanges(ask, locale).size }
    val interval = revealWordInterval(totalWords)
    val elapsed = remember { Animatable(if (reduced) Float.MAX_VALUE else 0f) }
    LaunchedEffect(Unit) {
        io.github.fartown.movo.ui.app.StartupReveal.await()
        if (elapsed.value < Float.MAX_VALUE) {
            val total = (totalWords - 1).coerceAtLeast(0) * interval + MovoMotion.FAST
            elapsed.animateTo(
                total,
                tween(total.toInt(), delayMillis = MovoMotion.staggerDelay(1), easing = MovoMotion.EasingLinear),
            )
            elapsed.snapTo(Float.MAX_VALUE)
        }
    }
    // 窄屏上问候会折行：用标题式均衡断行，避免第二行只剩一个字（真机 360dp 宽时「么？」单独成行）。
    val greetingStyle = MovoTypography.displayGreeting.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading)
    Column(modifier = Modifier.padding(horizontal = MovoSpacing.pageEdge)) {
        Text(
            dateLabel,
            style = MovoTypography.labelMedium,
            color = MovoColors.textTertiary,
            modifier = Modifier.graphicsLayer { alpha = (elapsed.value / MovoMotion.FAST).coerceIn(0f, 1f) },
        )
        Spacer(Modifier.height(MovoSpacing.sm))
        MovoWordRevealText(
            text = greeting,
            style = greetingStyle,
            color = MovoColors.textPrimary,
            elapsedMillis = { elapsed.value },
            firstWordIndex = 0,
            intervalMillis = interval,
            modifier = Modifier.semantics { heading() },
        )
        MovoWordRevealText(
            text = ask,
            style = greetingStyle,
            color = MovoColors.textTertiary,
            elapsedMillis = { elapsed.value },
            firstWordIndex = firstLineWords,
            intervalMillis = interval,
        )
    }
}

private data class Capability(
    val icon: MovoIconData,
    val tileBg: Color,
    val tileFg: Color,
    val label: Int,
    val prompt: Int,
)

private val Capabilities = listOf(
    Capability(MovoIcons.MousePointerClick, MovoColors.indigoBg, MovoColors.indigoFg, R.string.movo_cap_operate, R.string.movo_cap_operate_prompt),
    Capability(MovoIcons.AlarmClock, MovoColors.amberBg, MovoColors.amberFg, R.string.movo_cap_system, R.string.movo_cap_system_prompt),
    Capability(MovoIcons.ScanText, MovoColors.greenBg, MovoColors.greenFg, R.string.movo_cap_screen, R.string.movo_cap_screen_prompt),
    Capability(MovoIcons.Globe, MovoColors.blueBg, MovoColors.blueFg, R.string.movo_cap_research, R.string.movo_cap_research_prompt),
    Capability(MovoIcons.Terminal, MovoColors.graphiteBg, MovoColors.graphiteFg, R.string.movo_cap_terminal, R.string.movo_cap_terminal_prompt),
)

/**
 * `Card/Capability` 能力卡：156 × 152、圆角 28、内边距 16、E1；图标底块 40 → 类别标签（Micro，三级色）→ 指令（Body/Strong，≤ 2 行）。
 * 横向滚动，间距 12，首项从 20 开始；点卡片即发送卡片上的指令（按压缩放 0.98）。
 */
@Composable
private fun CapabilityCards(onSend: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(start = MovoSpacing.pageEdge, end = MovoSpacing.pageEdge, bottom = MovoSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(MovoSpacing.md),
    ) {
        itemsIndexed(Capabilities) { _, capability ->
            val prompt = stringResource(capability.prompt)
            val shape = RoundedCornerShape(MovoRadius.xl)
            val chatFlight = LocalChatFlight.current
            var cardRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
            Column(
                modifier = Modifier
                    .size(width = 156.dp, height = 152.dp)
                    .onGloballyPositioned { cardRect = it.windowRect() }
                    .movoElevation(MovoElevation.Card, shape)
                    .movoClickable(PressKind.Card, shape = shape) {
                        val text = prompt.replace("\n", "")
                        // Q4：能力卡收成用户气泡并移到对话顶部（由飞行层绘制）。
                        cardRect?.let { chatFlight?.launch(text, it, filled = true) }
                        onSend(text)
                    }
                    .clip(shape)
                    .background(MovoColors.bgSurface)
                    .padding(MovoSpacing.lg),
            ) {
                Box(
                    modifier = Modifier
                        .size(MovoSize.iconTile)
                        .clip(RoundedCornerShape(MovoRadius.sm))
                        .background(capability.tileBg),
                    contentAlignment = Alignment.Center,
                ) {
                    MovoIcon(capability.icon, null, size = MovoSize.iconMedium, tint = capability.tileFg)
                }
                Spacer(Modifier.height(MovoSpacing.lg))
                Text(stringResource(capability.label), style = MovoTypography.microMedium, color = MovoColors.textTertiary, maxLines = 1)
                Spacer(Modifier.height(MovoSpacing.xs))
                Text(prompt, style = MovoTypography.bodyStrong, color = MovoColors.textPrimary, maxLines = 2)
            }
        }
    }
}

/**
 * 9.4「首页进场」：依次淡入并上移 8，`standard` + `stagger`；第一项（光球）从 0.9 放大到 1。
 * 减少动画时只淡入。
 */
@Composable
private fun EntranceItem(step: Int, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        io.github.fartown.movo.ui.app.StartupReveal.await()
        progress.animateTo(
            1f,
            tween(
                durationMillis = if (reduced) MovoMotion.FAST else MovoMotion.STANDARD,
                delayMillis = MovoMotion.staggerDelay(step),
                easing = MovoMotion.EasingEnter,
            ),
        )
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val shift = with(density) { 8.dp.toPx() }
    Box(
        modifier = Modifier.graphicsLayer {
            val p = progress.value
            alpha = p
            if (!reduced) {
                if (step == 0) {
                    val s = 0.9f + 0.1f * p
                    scaleX = s
                    scaleY = s
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                } else {
                    translationY = shift * (1f - p)
                }
            }
        },
    ) {
        content()
    }
}

/**
 * 背景光晕（Figma `atmosphere`）：左上丁香、右上蜜桃两团径向柔光，模糊 60；首页与会话页都有，位置不动。
 */
@Composable
internal fun MovoAtmosphere(modifier: Modifier = Modifier) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val blur = with(density) { 60.dp.toPx() }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(520.dp)
            .graphicsLayer { renderEffect = BlurEffect(blur, blur, TileMode.Decal) },
    ) {
        val unit = size.width / 412f
        fun haze(x: Float, y: Float, w: Float, h: Float, color: Color) {
            val topLeft = Offset(x * unit, y * unit)
            val ovalSize = Size(w * unit, h * unit)
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0f)),
                    center = Offset(topLeft.x + ovalSize.width / 2, topLeft.y + ovalSize.height / 2),
                    radius = maxOf(ovalSize.width, ovalSize.height) / 2,
                ),
                topLeft = topLeft,
                size = ovalSize,
            )
        }
        haze(-140f, -40f, 360f, 300f, Color(0xFFC9B8FF))
        haze(150f, -120f, 300f, 240f, Color(0xFFFFD3BF))
    }
}
