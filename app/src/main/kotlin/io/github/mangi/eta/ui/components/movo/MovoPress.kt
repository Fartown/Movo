package io.github.mangi.eta.ui.components.movo

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.theme.LocalReducedMotion
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoMotion

/** 规范 9.3.1 按压反馈按控件类型区分。 */
internal enum class PressKind {
    /** 卡片：缩放 0.98 + 整卡叠加层。 */
    Card,

    /** 实心按钮、胶囊、芯片：缩放 0.95 + 叠加层。 */
    Solid,

    /** 无底色图标按钮：图标不缩放，40 圆形叠加层从 0.8 放大到 1。 */
    Icon,

    /** 列表行：整行叠加，不缩放。 */
    Row,

    /** 文字链接：不透明度到 60%。 */
    Link,
}

/**
 * Movo 统一的可点击修饰符：按下即反馈（instant），松手回弹（spring/snappy），不用水波纹；
 * 禁用时整体 40% 且不反馈（规范 8 通用状态、9.3.1 四条规则）。
 */
internal fun Modifier.movoClickable(
    kind: PressKind,
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val active = pressed && enabled
    val scaleTarget = when {
        !active || reduced -> 1f
        kind == PressKind.Card -> 0.98f
        kind == PressKind.Solid -> 0.95f
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = scaleTarget,
        animationSpec = if (active) MovoMotion.instant() else MovoMotion.snappy(),
        label = "movoPressScale",
    )
    val overlay by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = if (active) MovoMotion.instant() else MovoMotion.fast(),
        label = "movoPressOverlay",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = when {
                !enabled -> 0.4f
                kind == PressKind.Link -> 1f - 0.4f * overlay
                else -> 1f
            }
        }
        .drawWithContent {
            drawContent()
            if (overlay <= 0f || kind == PressKind.Link) return@drawWithContent
            if (kind == PressKind.Icon) {
                val radius = 20.dp.toPx() * (0.8f + 0.2f * overlay)
                drawCircle(MovoColors.overlayPressed, radius, Offset(size.width / 2, size.height / 2), alpha = overlay)
            } else {
                drawOutline(shape.createOutline(size, layoutDirection, this), MovoColors.overlayPressed, alpha = overlay)
            }
        }
        .combinedClickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            role = role,
            onClickLabel = onClickLabel,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}
