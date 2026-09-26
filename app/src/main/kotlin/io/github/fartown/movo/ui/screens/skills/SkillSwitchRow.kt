package io.github.fartown.movo.ui.screens.skills

import io.github.fartown.movo.ui.components.movo.MovoPopoverMenu
import io.github.fartown.movo.ui.components.movo.MovoMenuItem
import io.github.fartown.movo.ui.components.movo.MovoInfoDialog
import io.github.fartown.movo.ui.components.movo.MovoIconButton
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import io.github.fartown.movo.ui.components.movo.MovoDivider
import io.github.fartown.movo.ui.components.movo.MovoSwitch
import io.github.fartown.movo.ui.components.movo.PressKind
import io.github.fartown.movo.ui.components.movo.movoClickable
import io.github.fartown.movo.ui.model.SkillItemUi
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text

/**
 * Skill 开关行（规范 8.7 二级页 `Settings/Row`，icon=false）：整行点击切换；右侧「更多」`Popover/Menu` + 开关。
 * 说明最多两行（Skill 描述来自 SKILL.md，可能较长）。
 * 「查看说明」只在说明被截断时出现（说明为空或行内已完整显示时不出，避免弹窗与行内容重复，C4）；
 * 删除为 Rose 垃圾桶；菜单没有任何项时不显示「更多」。
 */
@Composable
internal fun SkillSwitchRow(
    skill: SkillItemUi,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
    showDivider: Boolean = true,
) {
    var showDescription by remember(skill.id) { mutableStateOf(false) }
    var showMenu by remember(skill.id) { mutableStateOf(false) }
    var descriptionTruncated by remember(skill.id) { mutableStateOf(false) }
    val hasDescription = skill.description.isNotBlank()
    val subtitle = if (hasDescription) skill.description else stringResource(R.string.skills_no_description)
    val viewDescription = stringResource(R.string.ui_view_description)
    val deleteLabel = stringResource(R.string.ui_delete_3755f5)
    val menuItems = buildList {
        if (hasDescription && descriptionTruncated) {
            add(MovoMenuItem(icon = MovoIcons.Info, label = viewDescription) { showDescription = true })
        }
        if (onDelete != null) {
            add(MovoMenuItem(icon = MovoIcons.Trash2, label = deleteLabel, destructive = true, enabled = enabled) { onDelete() })
        }
    }
    SkillRow(
        title = skill.name,
        subtitle = subtitle,
        enabled = enabled,
        showDivider = showDivider,
        role = Role.Switch,
        onSubtitleOverflow = { descriptionTruncated = it },
        onClick = { onToggle(!skill.enabled) },
    ) {
        if (menuItems.isNotEmpty()) {
            Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                MovoIconButton(
                    icon = MovoIcons.Ellipsis,
                    contentDescription = stringResource(R.string.skills_more_named, skill.name),
                    onClick = { showMenu = true },
                    iconSize = MovoSize.iconMedium,
                    tint = MovoColors.textSecondary,
                )
                MovoPopoverMenu(show = showMenu, onDismiss = { showMenu = false }, items = menuItems, alignEnd = true)
            }
            Spacer(Modifier.width(MovoSpacing.xs))
        }
        // 整行负责切换，开关本身不再接收点击，避免一次点击切两次。
        MovoSwitch(checked = skill.enabled, onCheckedChange = null, enabled = enabled)
    }
    // `Dialog/Info`：Skill 属于 Agent 的扩展能力，图标块沿用工具页「其他」分组的 Graphite + Skills 图标（puzzle）。
    // 没有可执行的动作，只有整行「知道了」。
    MovoInfoDialog(
        show = showDescription,
        icon = MovoIcons.Puzzle,
        iconBackground = MovoColors.graphiteBg,
        iconTint = MovoColors.graphiteFg,
        title = skill.name,
        message = skill.description,
        onDismiss = { showDescription = false },
    )
}

/**
 * 二级页行（icon=false）：左右 16、上下 14，最小高 56 / 68；标题 15 Medium 主色，说明 13 Regular 次要色、最多两行。
 * 与公共 `SettingsRow` 相同的视觉，额外支持说明行数限制与 [onClickLabel]（无障碍）。
 */
@Composable
internal fun SkillRow(
    title: String,
    subtitle: String?,
    enabled: Boolean,
    showDivider: Boolean,
    onClick: (() -> Unit)?,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    onSubtitleOverflow: ((Boolean) -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.movoClickable(
                        kind = PressKind.Row,
                        enabled = enabled,
                        role = role,
                        onClickLabel = onClickLabel,
                        onClick = onClick,
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MovoTypography.bodyStrong,
                    color = MovoColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MovoTypography.labelRegular,
                        color = MovoColors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { layout -> onSubtitleOverflow?.invoke(layout.hasVisualOverflow) },
                    )
                }
            }
            Spacer(Modifier.width(MovoSpacing.sm))
            trailing()
        }
        if (showDivider) MovoDivider(modifier = Modifier.align(Alignment.BottomStart), start = MovoSpacing.lg)
    }
}

/** 16 Indigo 加载圈：800ms 一圈 linear；减少动画时为静态完整圆环（规范 9.3、9.8）。 */
@Composable
internal fun SkillSpinner(modifier: Modifier = Modifier) {
    if (LocalReducedMotion.current) {
        Canvas(modifier.size(MovoSize.iconSmall)) {
            drawCircle(
                color = MovoColors.indigoFg,
                radius = size.minDimension * 0.375f,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "skillSpinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(MovoMotion.SPINNER_PERIOD, easing = MovoMotion.EasingLinear)),
        label = "skillSpinnerAngle",
    )
    MovoIcon(
        MovoIcons.LoaderCircle,
        contentDescription = null,
        size = MovoSize.iconSmall,
        tint = MovoColors.indigoFg,
        modifier = modifier.graphicsLayer { rotationZ = angle },
    )
}
