package io.github.fartown.movo.ui.theme

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon

/** 线条图标：颜色由 [tint] 给出，图标本身不带颜色（规范 6：除品牌 Logo 外不用多色图标）。 */
@Composable
internal fun MovoIcon(
    icon: MovoIconData,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = MovoSize.iconMedium,
    tint: Color = MovoColors.textPrimary,
) {
    val vector = remember(icon, size) { icon.vector(size) }
    Icon(
        imageVector = vector,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}
