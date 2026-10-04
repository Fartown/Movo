package io.github.fartown.movo.ui.components.movo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.fartown.movo.ui.model.AgentInteractionUiState
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoElevation
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text

/**
 * 同步交互卡（实施方案 §6.1 交互通道；S5 审批/提问 UI）。底部锚定，背景静止（不后退不缩放，见规范「弹窗时背后页面不动」）。
 *
 * 由 [AgentInteractionUiState] 驱动，覆盖定稿「候选 · 审批交互 v1」的四类：
 * 外发消息审批（可一直允许）、支付类审批（不可一直允许、深色确认）、提问（选项 + 可选自由文本）、以及解锁提示（前向兼容）。
 * 作答经回调回传给正在等待的 run；用户点遮罩或取消按取消处理（审批=拒绝、提问=拒绝，安全侧）。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun AgentInteractionCard(
    state: AgentInteractionUiState?,
    onApprove: (remember: Boolean) -> Unit,
    onDecline: () -> Unit,
    onAnswer: (text: String, optionIndex: Int?) -> Unit,
    onCancel: () -> Unit,
) {
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = state != null
    if (!transition.currentState && !transition.targetState) return
    // 退场动画期间保留最后一次内容，避免卡片在滑出时内容突然清空。
    val shown = remember { mutableStateOf(state) }
    if (state != null) shown.value = state
    val model = shown.value ?: return
    val reduced = LocalReducedMotion.current

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setDimAmount(0f)
            window?.setWindowAnimations(0)
        }
        val anim = rememberTransition(transition, label = "interactionCard")
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            anim.AnimatedVisibility(
                visible = { it },
                enter = fadeIn(MovoMotion.standard()),
                exit = fadeOut(MovoMotion.standardExit()),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MovoColors.overlayScrim)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onCancel,
                        ),
                )
            }
            anim.AnimatedVisibility(
                visible = { it },
                enter = fadeIn(MovoMotion.standard(MovoMotion.EasingEnter)) +
                    if (reduced) fadeIn(tween(0)) else slideInVertically(MovoMotion.standard(MovoMotion.EasingEnter)) { it / 3 },
                exit = fadeOut(MovoMotion.standardExit()) +
                    if (reduced) fadeOut(tween(0)) else slideOutVertically(MovoMotion.standardExit()) { it / 3 },
            ) {
                InteractionCardBody(
                    model = model,
                    onApprove = onApprove,
                    onDecline = onDecline,
                    onAnswer = onAnswer,
                )
            }
        }
    }
}

/**
 * 悬浮态内容（系统悬浮窗里用，不走 Dialog）：轻度压暗遮罩（目标应用仍可见）+ 底部锚定的卡体（复用 [InteractionCardBody]）。
 * 跨应用操作时由 Service 的悬浮窗宿主渲染；点遮罩=取消。背景（目标应用）不动。
 */
@Composable
internal fun AgentInteractionOverlayContent(
    model: AgentInteractionUiState,
    onApprove: (remember: Boolean) -> Unit,
    onDecline: () -> Unit,
    onAnswer: (text: String, optionIndex: Int?) -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 轻度压暗（用户定：目标应用仍清晰可见，区别于应用内的深色遮罩）。
            .background(MovoColors.textPrimary.copy(alpha = 0.14f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCancel,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // 卡体区域吞掉点击，避免点卡片空白处穿透到遮罩触发取消。
        Box(
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        ) {
            InteractionCardBody(model, onApprove, onDecline, onAnswer)
        }
    }
}

@Composable
internal fun InteractionCardBody(
    model: AgentInteractionUiState,
    onApprove: (Boolean) -> Unit,
    onDecline: () -> Unit,
    onAnswer: (String, Int?) -> Unit,
) {
    val shape = RoundedCornerShape(MovoRadius.xl)
    Column(
        modifier = Modifier
            .padding(horizontal = MovoSpacing.lg)
            .navigationBarsPadding()
            .imePadding()
            .padding(bottom = MovoSpacing.lg)
            .fillMaxWidth()
            .movoElevation(MovoElevation.Overlay, shape)
            .clip(shape)
            .background(MovoColors.bgSurface),
    ) {
        Column(modifier = Modifier.padding(MovoSpacing.lg)) {
            InteractionHeader(model)
            Spacer(Modifier.size(MovoSpacing.md))
            Text(model.title, style = MovoTypography.titleSection, color = MovoColors.textPrimary)
            if (model.isApproval) {
                ApprovalContent(model, onApprove, onDecline)
            } else {
                QuestionContent(model, onAnswer)
            }
        }
    }
}

/** 头部：原因图标 chip + 小标题（审批「需要你确认」/ 提问「需要你选择」）。 */
@Composable
private fun InteractionHeader(model: AgentInteractionUiState) {
    val (icon, chipFg, label) = headerSpec(model)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(MovoRadius.sm))
                .background(MovoColors.bgSurfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                MovoIcon(icon, null, size = MovoSize.iconSmall, tint = chipFg)
            } else {
                Text("?", style = MovoTypography.bodyStrong, color = chipFg)
            }
        }
        Spacer(Modifier.width(MovoSpacing.sm))
        Text(label, style = MovoTypography.labelMedium, color = MovoColors.textTertiary)
    }
}

private data class HeaderSpec(
    val icon: io.github.fartown.movo.ui.theme.MovoIconData?,
    val chipFg: androidx.compose.ui.graphics.Color,
    val label: String,
)

private fun headerSpec(model: AgentInteractionUiState): HeaderSpec = when {
    !model.isApproval -> HeaderSpec(null, MovoColors.indigoFg, "需要你选择")
    model.reason == "PROTECTED_APP" -> HeaderSpec(MovoIcons.TriangleAlert, MovoColors.roseFg, "需要你确认")
    isUnlock(model) -> HeaderSpec(MovoIcons.Lock, MovoColors.textPrimary, "需要你操作")
    else -> HeaderSpec(MovoIcons.ArrowUp, MovoColors.indigoFg, "需要你确认")
}

/** 解锁提示（前向兼容）：当前运行时无此 reason，保留按 reason 字串识别的分支。 */
private fun isUnlock(model: AgentInteractionUiState): Boolean = model.reason == "UNLOCK"

@Composable
private fun ApprovalContent(
    model: AgentInteractionUiState,
    onApprove: (Boolean) -> Unit,
    onDecline: () -> Unit,
) {
    val protectedApp = model.reason == "PROTECTED_APP"
    if (model.detail.isNotBlank()) {
        if (isUnlock(model)) {
            Spacer(Modifier.size(MovoSpacing.sm))
            Text(model.detail, style = MovoTypography.bodyRegular, color = MovoColors.textSecondary)
        } else {
            Spacer(Modifier.size(MovoSpacing.md))
            PreviewBox(model.detail)
        }
    }

    var remember by remember(model.requestId) { mutableStateOf(model.rememberLabel != null) }
    if (model.rememberLabel != null) {
        Spacer(Modifier.size(MovoSpacing.md))
        RememberRow(
            label = rememberText(model.rememberLabel),
            checked = remember,
            onToggle = { remember = it },
        )
    } else if (protectedApp) {
        Spacer(Modifier.size(MovoSpacing.md))
        Text(
            "此操作不提供「一直允许」，确认后可能仍需在设备上点按或完成身份验证。",
            style = MovoTypography.labelRegular,
            color = MovoColors.textTertiary,
        )
    }

    Spacer(Modifier.size(MovoSpacing.lg))
    MovoButtonRow {
        MovoBlockButton(label = "拒绝", onClick = onDecline, tone = BlockTone.Secondary)
        MovoBlockButton(
            label = if (protectedApp) "继续" else "允许",
            onClick = { onApprove(remember) },
            tone = if (protectedApp) BlockTone.Destructive else BlockTone.Primary,
        )
    }
}

@Composable
private fun QuestionContent(
    model: AgentInteractionUiState,
    onAnswer: (String, Int?) -> Unit,
) {
    Spacer(Modifier.size(MovoSpacing.md))
    Column(
        modifier = Modifier
            .heightIn(max = 380.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(MovoSpacing.sm),
    ) {
        model.options.forEachIndexed { index, option ->
            OptionRow(label = option) { onAnswer(option, index) }
        }
        if (model.allowFreeText) {
            FreeTextRow(onSubmit = { onAnswer(it, null) })
        }
    }
}

@Composable
private fun PreviewBox(detail: String) {
    // detail 第一行作为对象/标题（强调），其余作为内容（次要）。
    val lines = detail.split('\n', limit = 2)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MovoRadius.md))
            .background(MovoColors.bgSurfaceMuted)
            .padding(MovoSpacing.md),
        verticalArrangement = Arrangement.spacedBy(MovoSpacing.xs),
    ) {
        Text(lines.first(), style = MovoTypography.bodyStrong, color = MovoColors.textPrimary)
        if (lines.size > 1 && lines[1].isNotBlank()) {
            Text(lines[1], style = MovoTypography.bodyRegular, color = MovoColors.textSecondary)
        }
    }
}

@Composable
private fun RememberRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MovoRadius.sm))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Checkbox,
                onClick = { onToggle(!checked) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(MovoRadius.xs))
                .background(if (checked) MovoColors.actionPrimaryBg else MovoColors.bgSurfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) MovoIcon(MovoIcons.Check, null, size = 14.dp, tint = MovoColors.actionPrimaryFg)
        }
        Spacer(Modifier.width(MovoSpacing.sm))
        Text(label, style = MovoTypography.bodyRegular, color = MovoColors.textSecondary)
    }
}

@Composable
private fun OptionRow(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(MovoRadius.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .drawBehind { drawRect(MovoColors.indigoBg) }
            .movoClickable(PressKind.Row, shape = shape, role = Role.Button, onClick = onClick)
            .semantics { selected = false }
            .padding(horizontal = MovoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MovoTypography.bodyRegular,
            color = MovoColors.textPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FreeTextRow(onSubmit: (String) -> Unit) {
    val shape = RoundedCornerShape(MovoRadius.md)
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(MovoColors.bgSurfaceMuted)
            .padding(horizontal = MovoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (text.isEmpty()) {
                Text("其他…（直接输入回答）", style = MovoTypography.bodyRegular, color = MovoColors.textTertiary)
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = false,
                maxLines = 3,
                textStyle = MovoTypography.bodyRegular.copy(color = MovoColors.textPrimary),
                cursorBrush = SolidColor(MovoColors.actionPrimaryFg),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (text.isNotBlank()) {
            Spacer(Modifier.width(MovoSpacing.sm))
            MovoCircleButton(
                icon = MovoIcons.ArrowUp,
                contentDescription = "发送回答",
                onClick = { onSubmit(text.trim()) },
                primary = true,
            )
        }
    }
}

private fun rememberText(scope: String): String =
    if (scope.isBlank()) "本次任务内，这类操作都允许" else "本次任务内，「$scope」这类操作都允许"
