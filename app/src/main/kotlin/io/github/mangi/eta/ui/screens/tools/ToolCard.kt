package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.ui.components.iconForTool
import io.github.mangi.eta.ui.components.movo.BlockTone
import io.github.mangi.eta.ui.components.movo.MovoBlockButton
import io.github.mangi.eta.ui.components.movo.MovoDialogHost
import io.github.mangi.eta.ui.components.movo.MovoIconButton
import io.github.mangi.eta.ui.components.movo.PressKind
import io.github.mangi.eta.ui.components.movo.movoClickable
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.model.actualToolName
import io.github.mangi.eta.ui.model.toolCardAction
import io.github.mangi.eta.ui.model.toolCardRequirement
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoIcon
import io.github.mangi.eta.ui.theme.MovoIcons
import io.github.mangi.eta.ui.theme.MovoRadius
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoSpacing
import io.github.mangi.eta.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/** 类别色（规范 4.2）：浅底 + 深色图标，成对使用。 */
internal data class ToolCategoryTone(val background: Color, val foreground: Color)

/**
 * 分组 → 类别色，按 4.2 的固定语义：Agent 操作 App（屏幕、文本输入）与记忆 = Indigo；网页 = Blue；
 * 应用与系统、设备直达 = Amber；敏感设备能力（通知、短信、系统设置）= Rose；个人数据检索、读图 = Green（感知）；
 * 终端与文件 = Graphite。相邻分组合起来一屏不超过 4 种类别色。
 */
internal fun toolCategoryTone(groupId: String): ToolCategoryTone = when (groupId) {
    "screen", "text", "memory" -> ToolCategoryTone(MovoColors.indigoBg, MovoColors.indigoFg)
    "web" -> ToolCategoryTone(MovoColors.blueBg, MovoColors.blueFg)
    "app", "device_direct" -> ToolCategoryTone(MovoColors.amberBg, MovoColors.amberFg)
    "device_sensitive" -> ToolCategoryTone(MovoColors.roseBg, MovoColors.roseFg)
    "personal_data", "file_vision" -> ToolCategoryTone(MovoColors.greenBg, MovoColors.greenFg)
    else -> ToolCategoryTone(MovoColors.graphiteBg, MovoColors.graphiteFg)
}

/**
 * 工具格子（放在分组卡片里）：图标底块 40 / 圆角 12 / 图标 20（类别色）→ 标题 Body/Strong（≤ 2 行）→
 * 说明 Label/Regular 次要色（≤ 3 行）→ 条件（需要 Root / 权限 / ColorOS）→ 底部动作。
 * 点格子：有动作时执行（去系统增强 / 打开浏览器 / 去权限），没有动作时弹说明；有动作时右上 ⓘ 也弹说明。
 * 格子圆角 20 = 卡片 28 − 内缩 8（同心）；按压按卡片反馈（缩放 0.98 + 叠加层）。
 */
@Composable
internal fun ToolCard(
    tool: ToolItemUi,
    tone: ToolCategoryTone,
    rootGranted: Boolean,
    capabilities: AgentToolCapabilities,
    onAction: (AgentToolsAction) -> Unit,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
) {
    var showDescription by remember(tool.id) { mutableStateOf(false) }
    val description = if (!rootGranted && tool.id == "terminal") {
        stringResource(R.string.capability_terminal_ordinary_summary)
    } else {
        tool.summary
    }
    val requirementText = toolRequirementText(tool.id, rootGranted, capabilities)
    val action = toolCardAction(tool.id, capabilities)
    val actionText = when (action) {
        AgentToolsAction.OpenBrowser -> stringResource(R.string.action_open_browser)
        AgentToolsAction.OpenPermissions -> stringResource(R.string.tools_manage_permissions)
        AgentToolsAction.OpenEnhancements -> stringResource(R.string.tools_view_enhancements)
        else -> stringResource(R.string.ui_view_description)
    }
    val shape = RoundedCornerShape(MovoRadius.lg)
    Column(
        modifier = modifier
            .heightIn(min = 136.dp)
            .movoClickable(PressKind.Card, shape = shape) {
                if (action != null) onAction(action) else showDescription = true
            }
            .padding(MovoSpacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(MovoSize.iconTile)
                    .clip(RoundedCornerShape(MovoRadius.sm))
                    .background(tone.background),
                contentAlignment = Alignment.Center,
            ) {
                // 73 个工具还没有逐个对应的 Lucide 图标，暂用 iconForTool 的 Material 图标（见 restyle-C.md）。
                Icon(
                    imageVector = iconForTool(tool.id),
                    contentDescription = null,
                    modifier = Modifier.size(MovoSize.iconMedium),
                    tint = tone.foreground,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (action != null) {
                MovoIconButton(
                    icon = MovoIcons.Info,
                    contentDescription = stringResource(R.string.ui_description_named, tool.title),
                    onClick = { showDescription = true },
                    iconSize = MovoSize.iconMedium,
                    tint = MovoColors.textSecondary,
                )
            }
        }
        Spacer(modifier = Modifier.height(MovoSpacing.md))
        Text(
            text = tool.title,
            style = MovoTypography.bodyStrong,
            color = MovoColors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = description,
            style = MovoTypography.labelRegular,
            color = MovoColors.textSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        requirementText?.let {
            Spacer(modifier = Modifier.height(MovoSpacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MovoIcon(MovoIcons.ShieldAlert, contentDescription = null, size = MovoSize.iconLabel, tint = MovoColors.textSecondary)
                Spacer(Modifier.width(MovoSpacing.xs))
                Text(
                    text = it,
                    style = MovoTypography.labelRegular,
                    color = MovoColors.textSecondary,
                )
            }
        }
        if (fillHeight) Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(MovoSpacing.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = actionText,
                style = if (action != null) MovoTypography.labelMedium else MovoTypography.labelRegular,
                color = if (action != null) MovoColors.textPrimary else MovoColors.textTertiary,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (action != null) {
                Spacer(Modifier.width(MovoSpacing.xs))
                MovoIcon(MovoIcons.ArrowRight, contentDescription = null, size = MovoSize.iconSmall, tint = MovoColors.textPrimary)
            }
        }
    }
    ToolDescriptionDialog(
        show = showDescription,
        title = tool.title,
        description = listOfNotNull(description, requirementText).joinToString("\n\n"),
        onDismiss = { showDescription = false },
    )
}

/** 工具说明（规范 8.11 对话框容器）：标题 Title/Section → 8 → 说明 Body/Regular 次要色（可滚动）→ 整行「关闭」。 */
@Composable
private fun ToolDescriptionDialog(
    show: Boolean,
    title: String,
    description: String,
    onDismiss: () -> Unit,
) {
    MovoDialogHost(show = show, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(MovoSpacing.xxl)) {
            Text(title, style = MovoTypography.titleSection, color = MovoColors.textPrimary)
            Spacer(Modifier.height(MovoSpacing.sm))
            Text(
                text = description,
                style = MovoTypography.bodyRegular,
                color = MovoColors.textSecondary,
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        MovoBlockButton(
            label = stringResource(R.string.action_close),
            onClick = onDismiss,
            tone = BlockTone.Secondary,
            modifier = Modifier.fillMaxWidth().padding(MovoSpacing.xs),
        )
    }
}

@Composable
private fun toolRequirementText(id: String, rootGranted: Boolean, capabilities: AgentToolCapabilities): String? {
    val requirement = toolCardRequirement(id)
    val unavailableCode = capabilities.unavailableCode(actualToolName(id))
    return when {
        !rootGranted && requirement.rootRequirement == RootRequirement.REQUIRED -> stringResource(R.string.capability_root_required)
        !capabilities.accessibilityAvailable && requirement.accessibility -> stringResource(R.string.capability_accessibility_required)
        unavailableCode == "NOTIFICATION_ACCESS_REQUIRED" -> stringResource(R.string.capability_notification_access_required)
        unavailableCode == "APP_USAGE_ACCESS_REQUIRED" -> stringResource(R.string.capability_usage_access_required)
        unavailableCode == "LOCATION_PERMISSION_REQUIRED" -> stringResource(R.string.capability_location_access_required)
        requirement.colorOs -> stringResource(R.string.capability_coloros_required)
        !rootGranted && requirement.rootRequirement == RootRequirement.PARTIAL -> stringResource(R.string.capability_root_partial)
        else -> null
    }
}
