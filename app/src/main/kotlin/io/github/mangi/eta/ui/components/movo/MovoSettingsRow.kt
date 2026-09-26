package io.github.mangi.eta.ui.components.movo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.mangi.eta.ui.theme.MovoMotion
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoIcon
import io.github.mangi.eta.ui.theme.MovoIconData
import io.github.mangi.eta.ui.theme.MovoIcons
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoSpacing
import io.github.mangi.eta.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text

/** 设置行右侧（规范 8.7「行右侧」）。 */
internal sealed interface RowTrailing {
    /** 进入下一级：16 箭头三级色；有值时值在箭头左侧。 */
    data class Arrow(val value: String? = null) : RowTrailing

    /** 外部链接：值 + 16 外链图标。 */
    data class External(val value: String? = null) : RowTrailing

    /** 开关：整行可点即切换。 */
    data class Switch(val checked: Boolean, val onCheckedChange: ((Boolean) -> Unit)?) : RowTrailing

    /** 只显示值，不可进入。 */
    data class Value(val value: String) : RowTrailing

    /** 自定义右侧内容。 */
    class Custom(val content: @Composable () -> Unit) : RowTrailing

    data object None : RowTrailing
}

/** 设置行前导：`icon=true` 为 20 线条图标（无底块），也可放品牌 Logo 等自定义内容。 */
internal sealed interface RowLeading {
    data class Icon(val icon: MovoIconData) : RowLeading
    class Custom(val content: @Composable () -> Unit) : RowLeading
}

/**
 * `Settings/Row`（规范 8.7）：左右 16、上下 14，单行最小高 56，带说明 68；标题 15 Medium 主色、说明 13 Regular 次要色。
 * `icon=true`：图标 20 → 12 → 文字，分隔线从卡内 48 起；`icon=false`：文字从卡内 16 起，分隔线从 16 起。
 * [attention] 为「需要处理」：图标换 Rose 警示图标，值前 8 的 Rose 状态点。
 */
@Composable
internal fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: RowLeading? = null,
    trailing: RowTrailing = RowTrailing.Arrow(),
    showDivider: Boolean = true,
    enabled: Boolean = true,
    attention: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val switch = trailing as? RowTrailing.Switch
    // Q4：带「›」的行进入二级页时，行标题移动到二级页顶栏（落点由二级页顶栏认领）。
    var titleRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val reducedMotion = io.github.mangi.eta.ui.theme.LocalReducedMotion.current
    val clickAction: (() -> Unit)? = when {
        switch != null -> switch.onCheckedChange?.let { change -> { change(!switch.checked) } }
        onClick != null && trailing is RowTrailing.Arrow && !reducedMotion -> {
            {
                titleRect?.let { TitleMorph.launch(title, it) }
                onClick()
            }
        }
        else -> onClick
    }
    val textStart = if (leading != null) 48.dp else MovoSpacing.lg
    // 开关行：整行按下时开关同步拉长。
    val rowInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (clickAction != null) {
                    Modifier.movoClickable(
                        kind = PressKind.Row,
                        enabled = enabled,
                        role = if (switch != null) Role.Switch else Role.Button,
                        onLongClick = onLongClick,
                        interactionSource = rowInteraction,
                        onClick = clickAction,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (subtitle != null) 68.dp else 56.dp)
                .padding(horizontal = MovoSpacing.lg, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (leading) {
                is RowLeading.Icon -> MovoIcon(
                    if (attention) MovoIcons.ShieldAlert else leading.icon,
                    contentDescription = null,
                    size = MovoSize.iconMedium,
                    tint = if (attention) MovoColors.roseFg else MovoColors.textPrimary,
                )
                is RowLeading.Custom -> Box(Modifier.size(MovoSize.iconMedium), contentAlignment = Alignment.Center) {
                    leading.content()
                }
                null -> Unit
            }
            if (leading != null) Spacer(Modifier.width(MovoSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MovoTypography.bodyStrong,
                    color = MovoColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .onGloballyPositioned { titleRect = it.boundsInWindow() }
                        .graphicsLayer { alpha = if (TitleMorph.hidesRow(title)) 0f else 1f },
                )
                if (subtitle != null) {
                    Text(subtitle, style = MovoTypography.labelRegular, color = MovoColors.textSecondary)
                }
            }
            RowTrailingContent(trailing, attention, enabled, rowInteraction)
        }
        if (showDivider) {
            MovoDivider(modifier = Modifier.align(Alignment.BottomStart), start = textStart)
        }
    }
}

@Composable
private fun RowTrailingContent(
    trailing: RowTrailing,
    attention: Boolean,
    enabled: Boolean,
    interaction: androidx.compose.foundation.interaction.MutableInteractionSource? = null,
) {
    when (trailing) {
        is RowTrailing.Arrow -> {
            RowValue(trailing.value, attention)
            MovoIcon(MovoIcons.ChevronRight, null, size = MovoSize.iconSmall, tint = MovoColors.textTertiary)
        }
        is RowTrailing.External -> {
            RowValue(trailing.value, attention)
            MovoIcon(MovoIcons.ExternalLink, null, size = MovoSize.iconSmall, tint = MovoColors.textTertiary)
        }
        is RowTrailing.Value -> {
            Spacer(Modifier.width(MovoSpacing.sm))
            RowValue(trailing.value, attention, gap = false)
        }
        is RowTrailing.Switch -> {
            Spacer(Modifier.width(MovoSpacing.md))
            // 整行负责切换，开关本身不再接收点击，避免一次点击切两次。
            MovoSwitch(checked = trailing.checked, onCheckedChange = null, enabled = enabled, interactionSource = interaction)
        }
        is RowTrailing.Custom -> {
            Spacer(Modifier.width(MovoSpacing.sm))
            trailing.content()
        }
        RowTrailing.None -> Unit
    }
}

@Composable
private fun RowValue(value: String?, attention: Boolean, gap: Boolean = true) {
    if (value == null) return
    Spacer(Modifier.width(MovoSpacing.sm))
    if (attention) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(MovoColors.roseFg))
        Spacer(Modifier.width(6.dp))
    }
    // 值变化：交叉淡化 `fast`，宽度变化同步 `standard`（规范 9.3「值变化」）。
    androidx.compose.animation.AnimatedContent(
        targetState = value,
        transitionSpec = {
            (androidx.compose.animation.fadeIn(MovoMotion.fast()) togetherWith androidx.compose.animation.fadeOut(MovoMotion.fastExit()))
                .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> MovoMotion.standard() })
        },
        label = "rowValue",
    ) { current ->
        Text(
            current,
            style = MovoTypography.labelRegular,
            color = MovoColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
    }
    if (gap) Spacer(Modifier.width(MovoSpacing.xs))
}
