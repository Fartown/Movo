package io.github.fartown.movo.ui.components.movo

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import io.github.fartown.movo.ui.theme.MovoMotion
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIconData
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
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
    // 进入二级页按默认页面切换；原来的「行标题飞到顶栏」（Q4）2026-09-27 去掉：观感不好。
    val clickAction: (() -> Unit)? = when {
        switch != null -> switch.onCheckedChange?.let { change -> { change(!switch.checked) } }
        else -> onClick
    }
    val textStart = if (leading != null) 48.dp else MovoSpacing.lg
    // 开关行：整行按下时开关同步拉长。
    val rowInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val valueCap = remember { RowValueCap() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            // 记下行宽给右侧值算上限（布局阶段读写，不重组）。原来用 BoxWithConstraints：每行一个子组合，
            // 进二级页的第一帧要在测量阶段现组合整页十几行，拖慢页面切换。
            .layout { measurable, constraints ->
                valueCap.rowWidthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .then(
                if (clickAction != null) {
                    Modifier.movoClickable(
                        kind = PressKind.Row,
                        enabled = enabled,
                        role = if (switch != null) Role.Switch else Role.Button,
                        onLongClick = onLongClick,
                        interactionSource = rowInteraction,
                        onClick = clickAction,
                    ).then(
                        // 开关行读屏要报出「已开启 / 已关闭」：开关本身不接收点击，状态挂在整行上。
                        if (switch != null) {
                            Modifier.semantics {
                                toggleableState = androidx.compose.ui.state.ToggleableState(switch.checked)
                            }
                        } else {
                            Modifier
                        },
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        // 右侧值最多占行宽 40%（且不超过 160），标题优先完整显示：长的是值（模型名等），不能把标题挤成「Mo…」。
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
                    // 译文较长（如「Thinking by default」）时折成两行，不截断。
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(subtitle, style = MovoTypography.labelRegular, color = MovoColors.textSecondary)
                }
            }
            RowTrailingContent(trailing, attention, enabled, rowInteraction, valueCap)
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
    valueCap: RowValueCap = RowValueCap(),
) {
    when (trailing) {
        is RowTrailing.Arrow -> {
            RowValue(trailing.value, attention, cap = valueCap)
            MovoIcon(MovoIcons.ChevronRight, null, size = MovoSize.iconSmall, tint = MovoColors.textTertiary)
        }
        is RowTrailing.External -> {
            RowValue(trailing.value, attention, cap = valueCap)
            MovoIcon(MovoIcons.ExternalLink, null, size = MovoSize.iconSmall, tint = MovoColors.textTertiary)
        }
        is RowTrailing.Value -> {
            Spacer(Modifier.width(MovoSpacing.sm))
            RowValue(trailing.value, attention, gap = false, cap = valueCap)
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
private fun RowValue(
    value: String?,
    attention: Boolean,
    gap: Boolean = true,
    cap: RowValueCap = RowValueCap(),
) {
    // 值变化：交叉淡化 `fast`，宽度变化同步 `standard`（规范 9.3「值变化」）；值从无到有（读取完成）同样淡入，
    // 不直接蹦出。null 渲染为空，间距与状态点一起放进过渡内容里，宽度一并过渡。
    androidx.compose.animation.AnimatedContent(
        targetState = value,
        transitionSpec = {
            (androidx.compose.animation.fadeIn(MovoMotion.fast()) togetherWith androidx.compose.animation.fadeOut(MovoMotion.fastExit()))
                .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> MovoMotion.standard() })
        },
        contentAlignment = Alignment.CenterEnd,
        label = "rowValue",
    ) { current ->
        if (current == null) return@AnimatedContent
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(MovoSpacing.sm))
            if (attention) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(MovoColors.roseFg))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                current,
                style = MovoTypography.labelRegular,
                color = MovoColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.layout { measurable, constraints ->
                    val maxWidth = minOf(constraints.maxWidth, cap.maxWidthPx(this)).coerceAtLeast(constraints.minWidth)
                    val placeable = measurable.measure(constraints.copy(maxWidth = maxWidth))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
            )
            if (gap) Spacer(Modifier.width(MovoSpacing.xs))
        }
    }
}

/** 设置行右侧值的宽度上限：行宽 40%，且不超过 160（行宽由行本身在布局阶段写入）。 */
private class RowValueCap {
    var rowWidthPx = 0

    fun maxWidthPx(density: androidx.compose.ui.unit.Density): Int {
        val limit = with(density) { 160.dp.roundToPx() }
        return if (rowWidthPx > 0) minOf((rowWidthPx * 0.4f).toInt(), limit) else limit
    }
}
