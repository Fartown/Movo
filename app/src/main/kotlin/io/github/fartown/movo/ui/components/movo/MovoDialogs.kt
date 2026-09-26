package io.github.fartown.movo.ui.components.movo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.fartown.movo.R
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
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

/**
 * 对话框容器（规范 8.11、9.3「对话框」）：遮罩 overlay/scrim 淡入 `standard`；对话框缩放 0.96 → 1 并淡入 `standard` + `enter`；
 * 退场淡出并缩放到 0.98，170ms + `exit`。退场播完才关闭窗口。宽 348（左右各留 32），圆角 28，白底，E3。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun MovoDialogHost(
    show: Boolean,
    onDismissRequest: () -> Unit,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = show
    if (!state.currentState && !state.targetState) return
    val reduced = LocalReducedMotion.current
    Dialog(
        onDismissRequest = { if (dismissible) onDismissRequest() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setDimAmount(0f)
            window?.setWindowAnimations(0)
        }
        val transition = rememberTransition(state, label = "movoDialog")
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            transition.AnimatedVisibility(
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
                            enabled = dismissible,
                            onClick = onDismissRequest,
                        ),
                )
            }
            transition.AnimatedVisibility(
                visible = { it },
                enter = fadeIn(MovoMotion.standard(MovoMotion.EasingEnter)) +
                    if (reduced) fadeIn(tween(0)) else scaleIn(MovoMotion.standard(MovoMotion.EasingEnter), initialScale = 0.96f),
                exit = fadeOut(MovoMotion.standardExit()) +
                    if (reduced) fadeOut(tween(0)) else scaleOut(MovoMotion.standardExit(), targetScale = 0.98f),
            ) {
                val shape = RoundedCornerShape(MovoRadius.xl)
                Column(
                    modifier = Modifier
                        .padding(horizontal = 32.dp)
                        .widthIn(max = 348.dp)
                        .fillMaxWidth()
                        .movoElevation(MovoElevation.Overlay, shape)
                        .clip(shape)
                        .background(MovoColors.bgSurface),
                    content = content,
                )
            }
        }
    }
}

/**
 * `Dialog/Confirm`：内容区 24：标题 Title/Section（危险操作标题前加 20 Rose 警示图标）→ 8 → 说明 Body/Regular 次要色；
 * 按钮区内边距 4、间距 4，两个 48 整行按钮：左「取消」，右确认（普通 = 主操作色，危险 = bg/inverse + 白字）。
 */
@Composable
internal fun MovoConfirmDialog(
    show: Boolean,
    title: String,
    message: String?,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    destructive: Boolean = false,
    cancelText: String = stringResource(R.string.action_cancel),
    confirmEnabled: Boolean = true,
    cancelEnabled: Boolean = true,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    MovoDialogHost(show = show, onDismissRequest = onDismissRequest, dismissible = cancelEnabled) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (destructive) {
                    MovoIcon(MovoIcons.TriangleAlert, null, size = MovoSize.iconMedium, tint = MovoColors.roseFg)
                    Spacer(Modifier.width(MovoSpacing.sm))
                }
                Text(title, style = MovoTypography.titleSection, color = MovoColors.textPrimary)
            }
            if (message != null) {
                Spacer(Modifier.size(MovoSpacing.sm))
                Text(message, style = MovoTypography.bodyRegular, color = MovoColors.textSecondary)
            }
            extraContent?.invoke(this)
        }
        MovoButtonRow(modifier = Modifier.padding(MovoSpacing.xs)) {
            MovoBlockButton(
                label = cancelText,
                onClick = onDismissRequest,
                tone = BlockTone.Secondary,
                enabled = cancelEnabled,
                modifier = Modifier.weight(1f),
            )
            MovoBlockButton(
                label = confirmText,
                onClick = onConfirm,
                tone = if (destructive) BlockTone.Destructive else BlockTone.Primary,
                enabled = confirmEnabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 单选对话框（规范 9.3.1「单选」）：行按压态；松手后旧 ✓ 淡出、新 ✓ 缩放淡入，停留 160ms 再关闭。
 */
@Composable
internal fun MovoChoiceDialog(
    show: Boolean,
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var pending by remember(show) { mutableIntStateOf(-1) }
    LaunchedEffect(pending) {
        if (pending >= 0) {
            delay(MovoMotion.FAST.toLong() + MovoMotion.MENU_CLOSE_DELAY)
            onSelect(pending)
            onDismissRequest()
        }
    }
    MovoDialogHost(show = show, onDismissRequest = onDismissRequest) {
        Text(
            title,
            style = MovoTypography.titleSection,
            color = MovoColors.textPrimary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = MovoSpacing.sm),
        )
        Column(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MovoSpacing.sm)
                .padding(bottom = MovoSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            options.forEachIndexed { index, option ->
                val selected = if (pending >= 0) index == pending else index == selectedIndex
                val shape = RoundedCornerShape(MovoRadius.sm)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(shape)
                        .movoClickable(PressKind.Row, shape = shape, role = Role.RadioButton) {
                            if (pending < 0) pending = index
                        }
                        .padding(horizontal = MovoSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        option,
                        style = if (selected) MovoTypography.bodyStrong else MovoTypography.bodyRegular,
                        color = MovoColors.textPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    CheckMark(visible = selected)
                }
            }
        }
    }
}

@Composable
private fun CheckMark(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(MovoMotion.fast()) + scaleIn(MovoMotion.fast(), initialScale = 0.72f),
        exit = fadeOut(MovoMotion.fastExit()),
    ) {
        MovoIcon(
            MovoIcons.Check,
            null,
            size = MovoSize.iconMedium,
            tint = MovoColors.indigoFg,
            modifier = Modifier.graphicsLayer { },
        )
    }
}
