package io.github.fartown.movo.ui.screens.skills

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import io.github.fartown.movo.ui.components.movo.BlockTone
import io.github.fartown.movo.ui.components.movo.MovoBlockButton
import io.github.fartown.movo.ui.components.movo.MovoDialogHost
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
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu

/**
 * Skill 开关行（规范 8.7 二级页 `Settings/Row`，icon=false）：整行点击切换；右侧「更多」菜单（查看说明 / 删除）+ 开关。
 * 说明最多两行（Skill 描述来自 SKILL.md，可能较长）。
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
    val description = skill.description.ifBlank { stringResource(R.string.skills_no_description) }
    SkillRow(
        title = skill.name,
        subtitle = description,
        enabled = enabled,
        showDivider = showDivider,
        role = Role.Switch,
        onClick = { onToggle(!skill.enabled) },
    ) {
        OverlayIconDropdownMenu(
            modifier = Modifier.align(Alignment.CenterVertically),
            entry = DropdownEntry(
                items = listOfNotNull(
                    DropdownItem(
                        text = stringResource(R.string.ui_view_description),
                        onClick = { showDescription = true },
                    ),
                    onDelete?.let { delete ->
                        DropdownItem(
                            text = stringResource(R.string.ui_delete_3755f5),
                            enabled = enabled,
                            onClick = { if (enabled) delete() },
                        )
                    },
                ),
            ),
        ) {
            MovoIcon(
                MovoIcons.Ellipsis,
                contentDescription = stringResource(R.string.skills_more_named, skill.name),
                size = MovoSize.iconMedium,
                tint = MovoColors.textSecondary,
            )
        }
        Spacer(Modifier.width(MovoSpacing.xs))
        // 整行负责切换，开关本身不再接收点击，避免一次点击切两次。
        MovoSwitch(checked = skill.enabled, onCheckedChange = null, enabled = enabled)
    }
    SkillTextDialog(
        show = showDescription,
        title = skill.name,
        text = description,
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
                    )
                }
            }
            Spacer(Modifier.width(MovoSpacing.sm))
            trailing()
        }
        if (showDivider) MovoDivider(modifier = Modifier.align(Alignment.BottomStart), start = MovoSpacing.lg)
    }
}

/** 长文本对话框：标题 + 可滚动正文 + 单个「关闭」/「知道了」按钮（规范 8.11 容器）。 */
@Composable
internal fun SkillTextDialog(
    show: Boolean,
    title: String,
    text: String,
    onDismiss: () -> Unit,
    buttonText: String = stringResource(R.string.action_close),
    isError: Boolean = false,
) {
    MovoDialogHost(show = show, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(MovoSpacing.xxl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isError) {
                    MovoIcon(MovoIcons.CircleAlert, null, size = MovoSize.iconMedium, tint = MovoColors.roseFg)
                    Spacer(Modifier.width(MovoSpacing.sm))
                }
                Text(title, style = MovoTypography.titleSection, color = MovoColors.textPrimary)
            }
            Spacer(Modifier.size(MovoSpacing.sm))
            Text(
                text,
                style = MovoTypography.bodyRegular,
                color = MovoColors.textSecondary,
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        MovoBlockButton(
            label = buttonText,
            onClick = onDismiss,
            tone = if (isError) BlockTone.Secondary else BlockTone.Primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(MovoSpacing.xs),
        )
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
