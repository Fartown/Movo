package io.github.fartown.movo.tv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fartown.movo.R

/**
 * 电视 App 的颜色与组件，全部取自手机规范（docs/DESIGN_SYSTEM.md §4、§7、§8 设置页），几何尺寸为手机 ×1.5。
 * 焦点用 F-a「整行浅紫」：手机「选中」样式（Indigo 浅底 + Indigo 图标与文字），不加描边；光球不参与焦点。
 */
internal object TvTokens {
    val canvas = Color(0xFFF4F3EF)          // bg/canvas
    val surface = Color.White               // bg/surface
    val muted = Color(0xFFF0EFEA)           // bg/surface-muted
    val text = Color(0xFF151515)            // text/primary
    val secondary = Color(0xFF6B6964)       // text/secondary
    val tertiary = Color(0xFF8E8C86)        // text/tertiary
    val primary = Color(0xFFDCDFFF)         // action/primary-bg
    val primaryFocused = Color(0xFFCFD3FA)  // 主按钮获焦：比 primary 深一档（F-a）
    val ink = Color(0xFF454CD2)             // action/primary-fg
    val selected = Color(0xFFEEF0FF)        // accent/indigo-bg：焦点整行浅紫
    val hairline = Color(0x1A141414)        // border/hairline 10%
    val strong = Color(0x24141414)          // border/strong 14%
    val rose = Color(0xFFD63C4A)
    val orbShadow = Color(0xFF4F56E3)       // 光球下方 Indigo 18% 柔和投影
}

@Composable internal fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = TvTokens.ink, onPrimary = Color.White, background = TvTokens.canvas,
        surface = TvTokens.surface, onSurface = TvTokens.text, onBackground = TvTokens.text,
    ), content = content)
}

@Composable internal fun TvIcon(@DrawableRes id: Int, size: Dp, tint: Color, modifier: Modifier = Modifier) =
    Icon(painterResource(id), contentDescription = null, tint = tint, modifier = modifier.size(size))

/** 手机 Button/Block（48 高、全圆角）×1.5。获焦：主按钮底色深一档；次要按钮整块浅紫 + Indigo 文字。 */
@Composable internal fun TvButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when { primary && focused -> TvTokens.primaryFocused; primary -> TvTokens.primary; focused -> TvTokens.selected; else -> TvTokens.muted }
    val fg = if (primary || focused) TvTokens.ink else TvTokens.text
    val shape = RoundedCornerShape(percent = 50)
    Row(modifier.heightIn(min = 72.dp).widthIn(min = 160.dp)
        .alpha(if (enabled) 1f else .4f)
        .onFocusChanged { focused = it.isFocused }
        .clip(shape).background(bg, shape)
        .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)
        .padding(horizontal = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) TvIcon(icon, 30.dp, fg)
        Text(label, fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

/** 手机 Switch（轨道 48 × 28、滑块 22）×1.5。开：浅 Indigo 轨道 + Indigo 滑块；关：浅灰轨道 + 描边 + 白滑块。 */
@Composable internal fun TvSwitch(on: Boolean) {
    val shape = RoundedCornerShape(percent = 50)
    Box(Modifier.size(72.dp, 42.dp).clip(shape)
        .background(if (on) TvTokens.primary else TvTokens.muted, shape)
        .then(if (on) Modifier else Modifier.border(1.5.dp, TvTokens.strong, shape))
        .padding(4.5.dp), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(33.dp).then(if (on) Modifier else Modifier.shadow(2.dp, CircleShape))
            .background(if (on) TvTokens.ink else Color.White, CircleShape))
    }
}

/** 设置行的内容。[onClick] 为空的行只展示，不获焦。 */
internal class TvRowSpec(
    @DrawableRes val icon: Int,
    val title: String,
    val subtitle: String? = null,
    val value: String? = null,
    val alert: Boolean = false,
    val switch: Boolean? = null,
    val focusRequester: FocusRequester? = null,
    val onClick: (() -> Unit)? = null,
)

/**
 * 手机设置页卡片：圆角 28、白底、0.5 发丝描边、无阴影，卡片标题 13 Medium 次要色（×1.5）。
 * 行：56 高、图标 20、标题 15 Medium、右侧值 13 次要色 + 箭头 16，分隔线从文字起点开始（×1.5）。
 * 焦点行整行浅紫（卡内内缩 6、圆角 36 = 42 − 6），它和上一行的分隔线隐藏。
 */
@Composable internal fun TvCard(rows: List<TvRowSpec>, modifier: Modifier = Modifier, title: String? = null) {
    var focusedIndex by remember { mutableStateOf(-1) }
    val shape = RoundedCornerShape(42.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(TvTokens.surface, shape).border(1.dp, TvTokens.hairline, shape)
        .padding(top = if (title == null) 6.dp else 0.dp, bottom = 6.dp)) {
        if (title != null) Text(title, Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 6.dp),
            fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, color = TvTokens.secondary)
        rows.forEachIndexed { index, spec ->
            TvRow(spec, divider = index < rows.lastIndex && focusedIndex != index && focusedIndex != index + 1) { focused ->
                if (focused) focusedIndex = index else if (focusedIndex == index) focusedIndex = -1
            }
        }
    }
}

@Composable private fun TvRow(spec: TvRowSpec, divider: Boolean, onFocus: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val action = spec.onClick
    Box(Modifier.fillMaxWidth()
        .then(spec.focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
        .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
        .then(if (action != null) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = action) else Modifier)) {
        if (focused) Box(Modifier.matchParentSize().padding(horizontal = 6.dp).background(TvTokens.selected, RoundedCornerShape(36.dp)))
        Row(Modifier.fillMaxWidth().heightIn(min = 84.dp)
            .padding(horizontal = 24.dp, vertical = if (spec.subtitle != null) 18.dp else 25.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            TvIcon(spec.icon, 30.dp, when { focused -> TvTokens.ink; spec.alert -> TvTokens.rose; else -> TvTokens.text })
            Column(Modifier.weight(1f)) {
                Text(spec.title, fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium,
                    color = if (focused) TvTokens.ink else TvTokens.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                spec.subtitle?.let { Text(it, fontSize = 20.sp, lineHeight = 28.sp, color = TvTokens.secondary) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (spec.alert) Box(Modifier.size(12.dp).background(TvTokens.rose, CircleShape))
                spec.value?.let {
                    Text(it, Modifier.widthIn(max = 360.dp), fontSize = 20.sp, lineHeight = 28.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (focused) TvTokens.ink else TvTokens.secondary)
                }
                spec.switch?.let { TvSwitch(it) }
                if (action != null && spec.switch == null) TvIcon(R.drawable.tv_ic_chevron_right, 24.dp, if (focused) TvTokens.ink else TvTokens.tertiary)
            }
        }
        if (divider) Box(Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 24.dp).fillMaxWidth().height(1.dp).background(TvTokens.hairline))
    }
}

/** 卡片页脚（手机 Card/Footer）：后果说明写在卡片下面，不在页顶放说明段落。 */
@Composable internal fun TvFooter(text: String) =
    Text(text, Modifier.padding(horizontal = 24.dp), fontSize = 20.sp, lineHeight = 30.sp, color = TvTokens.tertiary)

/** 二级页：顶栏只有居中标题（返回用遥控器返回键），内容单列居中、可滚动；焦点移动时自动滚到可见。 */
@Composable internal fun TvPage(title: String?, width: Dp = 760.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(TvTokens.canvas)) {
        if (title != null) Box(Modifier.fillMaxWidth().height(84.dp), contentAlignment = Alignment.Center) {
            Text(title, Modifier.widthIn(max = 880.dp), fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium,
                color = TvTokens.text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        Column(Modifier.align(Alignment.CenterHorizontally).width(width).weight(1f).verticalScroll(rememberScrollState())
            .padding(top = 24.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(24.dp), content = content)
    }
}

/** 首页 logo 光球（与手机同款：光球 + 同色浅调 M，下方 Indigo 18% 柔和投影；无外圈、无外发光）。 */
@Composable internal fun TvOrbLogo(size: Dp, ring: TvOrbRing = TvOrbRing.None) {
    val density = LocalDensity.current.density
    Canvas(Modifier.size(size + 24.dp)) {
        val d = size.toPx()
        val r = d / 2f
        val blur = d * .16f
        val shadowCenter = Offset(center.x, center.y + d * .05f)
        drawCircle(Brush.radialGradient(
            0f to TvTokens.orbShadow.copy(alpha = .18f),
            ((r - blur) / (r + blur)) to TvTokens.orbShadow.copy(alpha = .18f),
            1f to Color.Transparent,
            center = shadowCenter, radius = r + blur), radius = r + blur, center = shadowCenter)
        drawIntoCanvas { TvOrbPainter.draw(it.nativeCanvas, center.x, center.y, d, ring, 6.dp.toPx(), 0f, density) }
    }
}

@Composable internal fun TvTitle(text: String) = Text(text, color = TvTokens.text, fontSize = 36.sp,
    lineHeight = 48.sp, fontWeight = FontWeight.Medium)
@Composable internal fun TvBody(text: String, secondary: Boolean = false) = Text(text,
    color = if (secondary) TvTokens.secondary else TvTokens.text, fontSize = 24.sp, lineHeight = 36.sp)
@Composable internal fun TvHint(text: String) = Text(text, color = TvTokens.secondary, fontSize = 20.sp, lineHeight = 30.sp)
