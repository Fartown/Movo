package io.github.fartown.movo.ui.components.movo

import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSpacing

/**
 * 单选的选中态（规范 8.11「单选」，Figma「10 · 单选样式（整行浅紫）」）：选中项整行 `accent/indigo-bg`，
 * 标题 `accent/indigo-fg` Medium，不放任何图标（不用 ✓、不用单选圈）。卡内列表行内缩 4、圆角 12；
 * 对话框选项条自己画底色（圆角 16），只用 [movoSelectedTextColor]。底色与文字颜色过渡 `fast`。
 */
@Composable
internal fun Modifier.movoSelectedRow(
    selected: Boolean,
    inset: Dp = MovoSpacing.xs,
    radius: Dp = MovoRadius.sm,
): Modifier {
    val background by animateColorAsState(
        if (selected) MovoColors.indigoBg else MovoColors.indigoBg.copy(alpha = 0f),
        MovoMotion.fast(),
        label = "selectedRow",
    )
    return drawBehind {
        if (background.alpha <= 0f) return@drawBehind
        val insetPx = inset.toPx()
        drawRoundRect(
            color = background,
            topLeft = Offset(insetPx, insetPx),
            size = Size(size.width - insetPx * 2, size.height - insetPx * 2),
            cornerRadius = CornerRadius(radius.toPx()),
        )
    }
}

/** 选中项的标题颜色：选中 `accent/indigo-fg`，否则 [default]；过渡 `fast`。 */
@Composable
internal fun movoSelectedTextColor(selected: Boolean, default: Color = MovoColors.textPrimary): Color {
    val color by animateColorAsState(if (selected) MovoColors.indigoFg else default, MovoMotion.fast(), label = "selectedText")
    return color
}
