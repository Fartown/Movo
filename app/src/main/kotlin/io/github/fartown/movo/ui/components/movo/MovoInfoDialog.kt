package io.github.fartown.movo.ui.components.movo

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIconData
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text

/**
 * 说明对话框 `Dialog/Info`（规范 8.11，Figma「09 · 工具说明弹窗」）：容器同 `Dialog/Confirm`。
 * 内容区 24：类别色图标块 40（圆角 12，图标 20）→ 16 → 标题 Title/Section 主色 → 8 → 说明 Body/Regular 次要色（超长可滚动）
 * → 16 → 条件提示块（有条件时：bg/surface-muted、圆角 12、内边距 12 × 10，shield-alert 16 次要色 + 8 + Label/Regular 次要色）。
 * 按钮区同 `Dialog/Confirm`：有动作时左「知道了」+ 右动作（主操作色）；没有动作时只有整行「知道了」（Secondary）。
 *
 * 退场动画期间内容要保持上一次的值：调用方用 [rememberLastNonNull] 保留标题与说明。
 */
@Composable
internal fun MovoInfoDialog(
    show: Boolean,
    icon: MovoIconData,
    iconBackground: Color,
    iconTint: Color,
    title: String,
    message: String,
    onDismiss: () -> Unit,
    requirement: String? = null,
    actionText: String? = null,
    onAction: () -> Unit = {},
    dismissText: String = stringResource(R.string.ui_knew_cb63c6),
) {
    MovoDialogHost(show = show, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(MovoSpacing.xxl)) {
            Box(
                modifier = Modifier
                    .size(MovoSize.iconTile)
                    .clip(RoundedCornerShape(MovoRadius.sm))
                    .background(iconBackground),
                contentAlignment = Alignment.Center,
            ) {
                MovoIcon(icon, contentDescription = null, size = MovoSize.iconMedium, tint = iconTint)
            }
            Spacer(Modifier.height(MovoSpacing.lg))
            Text(title, style = MovoTypography.titleSection, color = MovoColors.textPrimary)
            if (message.isNotBlank()) {
                Spacer(Modifier.height(MovoSpacing.sm))
                Text(
                    text = message,
                    style = MovoTypography.bodyRegular,
                    color = MovoColors.textSecondary,
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            if (requirement != null) {
                Spacer(Modifier.height(MovoSpacing.lg))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MovoRadius.sm))
                        .background(MovoColors.bgSurfaceMuted)
                        .padding(horizontal = MovoSpacing.md, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MovoIcon(MovoIcons.ShieldAlert, null, size = MovoSize.iconSmall, tint = MovoColors.textSecondary)
                    Spacer(Modifier.width(MovoSpacing.sm))
                    Text(requirement, style = MovoTypography.labelRegular, color = MovoColors.textSecondary)
                }
            }
        }
        MovoButtonRow(modifier = Modifier.padding(MovoDialogButtonPadding)) {
            MovoBlockButton(
                label = dismissText,
                onClick = onDismiss,
                tone = BlockTone.Secondary,
            )
            if (actionText != null) {
                MovoBlockButton(
                    label = actionText,
                    onClick = onAction,
                    tone = BlockTone.Primary,
                )
            }
        }
    }
}

/**
 * 失败说明（规范 8.11「只有失败且需要用户知情时」）：`Dialog/Info`，图标块为 Rose 浅底 + Rose 警示图标（4.2「错误」），
 * 标题写清哪件事没成（「没能导出对话」），说明写原因与下一步；只有整行「知道了」。
 */
@Composable
internal fun MovoFailureDialog(
    show: Boolean,
    title: String,
    message: String,
    onDismiss: () -> Unit,
    actionText: String? = null,
    onAction: () -> Unit = {},
) {
    MovoInfoDialog(
        show = show,
        icon = MovoIcons.CircleAlert,
        iconBackground = MovoColors.roseBg,
        iconTint = MovoColors.roseFg,
        title = title,
        message = message,
        onDismiss = onDismiss,
        actionText = actionText,
        onAction = onAction,
    )
}

private class LastNonNull<T : Any> {
    var value: T? = null
}

/** 返回 [value]；为 null 时返回上一次的非空值（给对话框退场动画用，避免文字闪空）。 */
@Composable
internal fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { LastNonNull<T>() }
    if (value != null) holder.value = value
    return value ?: holder.value
}
