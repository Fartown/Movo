package io.github.mangi.eta.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Movo 设计规范 v1（docs/DESIGN_SYSTEM.md）的代码令牌，名称与文档一一对应（`bg/surface-muted` → `bgSurfaceMuted`）。
 * 新页面与改版页面只从这里取颜色、间距、圆角与字体，不在页面里裸写数值。按需补充，未用到的令牌暂不声明。
 */
internal object MovoColors {
    val bgCanvas = Color(0xFFF4F3EF)
    val bgSurface = Color(0xFFFFFFFF)
    val bgSurfaceMuted = Color(0xFFF0EFEA)
    val actionPrimaryBg = Color(0xFFDCDFFF)
    val actionPrimaryFg = Color(0xFF454CD2)
    val textPrimary = Color(0xFF151515)
    val textSecondary = Color(0xFF6B6964)
    val textTertiary = Color(0xFF8E8C86)
    val bgInverse = Color(0xFF151515)
    val textOnInverse = Color(0xFFFFFFFF)
    val borderHairline = Color(0x1A141414)
    val borderStrong = Color(0x24141414)
    val overlayPressed = Color(0x0F151515)
    val overlayPressedInverse = Color(0x24FFFFFF)
    val overlayScrim = Color(0x52151515)
    val glassSurface = Color(0xA6FFFFFF)
    val glassSurfaceFallback = Color(0xE0FFFFFF)
    val glassFill = Color(0x0F151515)
    val statusOnline = Color(0xFF1FB768)

    /** 阴影色：暖灰，不用纯黑（规范 7）。 */
    val shadow = Color(0xFF2B2419)

    /** 品牌渐变：只用于光球、光晕与边缘光晕（规范 4.3）。 */
    val brandGradient = listOf(
        Color(0xFFFFC2A6),
        Color(0xFFFF9EC2),
        Color(0xFFB39BFF),
        Color(0xFF8CC6FF),
        Color(0xFFBFEFE3),
    )

    val indigoBg = Color(0xFFEEF0FF)
    val indigoFg = Color(0xFF4F56E3)
    val amberBg = Color(0xFFFFF1E3)
    val amberFg = Color(0xFFD2640C)
    val greenBg = Color(0xFFE6F5ED)
    val greenFg = Color(0xFF178A55)
    val blueBg = Color(0xFFE5F1FC)
    val blueFg = Color(0xFF1A72C8)
    val roseBg = Color(0xFFFDEBEC)
    val roseFg = Color(0xFFD63C4A)
    val graphiteBg = Color(0xFFEFEEE9)
    val graphiteFg = Color(0xFF3A3936)
}

internal object MovoSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val section = 32.dp

    /** 页面边距线。 */
    val pageEdge = 20.dp

    /** 分组标题对齐的内容线（20 + 16）。 */
    val contentLine = 36.dp
}

internal object MovoRadius {
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val pillLg = 24.dp
    val xl = 28.dp
}

internal object MovoSize {
    val controlCompact = 24.dp
    val controlSmall = 32.dp
    val controlMedium = 40.dp
    val controlLarge = 48.dp
    val touchTarget = 44.dp
    val topBar = 56.dp
    val iconTiny = 12.dp
    val iconLabel = 14.dp
    val iconSmall = 16.dp
    val iconMedium = 20.dp
    val iconLarge = 24.dp
    val iconTile = 40.dp
    val hairline = 0.5.dp

    /** 列表行最小高 = 上下 10 + 图标底块 40。 */
    val listRowMin = 60.dp
}

internal object MovoTypography {
    private const val TABULAR_NUMBERS = "tnum"

    val displayGreeting = TextStyle(
        fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp,
    )
    val titlePage = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp)
    val titleSection = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val bodyReading = TextStyle(fontSize = 16.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal)
    val inputPlaceholder = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
    val bodyStrong = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val bodyRegular = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    val labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val labelRegular = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    val microMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp)
    val numericLabel = TextStyle(
        fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TABULAR_NUMBERS,
    )
    val numericBadge = TextStyle(
        fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TABULAR_NUMBERS,
    )
    val numericMicro = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = TABULAR_NUMBERS,
    )
}

/** 规范 7：所有浮起表面 = 0.5 发丝描边 + 暖灰双层阴影。 */
internal enum class MovoElevation { E0, Card, Composer, Overlay }
