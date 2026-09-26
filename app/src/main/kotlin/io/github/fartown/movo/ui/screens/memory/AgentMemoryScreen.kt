package io.github.fartown.movo.ui.screens.memory

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.components.movo.rememberLastNonNull
import io.github.fartown.movo.ui.components.movo.rememberDoneFlash
import io.github.fartown.movo.ui.components.movo.MovoFailureDialog
import io.github.fartown.movo.ui.components.movo.MovoDoneBlockButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import io.github.fartown.movo.R
import io.github.fartown.movo.ui.components.movo.BlockTone
import io.github.fartown.movo.ui.components.movo.CardFooter
import io.github.fartown.movo.ui.components.movo.CardTitle
import io.github.fartown.movo.ui.components.movo.MovoBlockButton
import io.github.fartown.movo.ui.components.movo.MovoButtonRow
import io.github.fartown.movo.ui.components.movo.MovoCard
import io.github.fartown.movo.ui.components.movo.MovoConfirmDialog
import io.github.fartown.movo.ui.components.movo.MovoPage
import io.github.fartown.movo.ui.components.movo.RowTrailing
import io.github.fartown.movo.ui.components.movo.SettingsRow
import io.github.fartown.movo.ui.layout.horizontalCutoutPadding
import io.github.fartown.movo.ui.model.AgentMemoryAction
import io.github.fartown.movo.ui.model.AgentMemoryUiState
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text
import io.github.fartown.movo.ui.components.movo.TextField
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.text.NumberFormat

/**
 * 设置 · 记忆（规范 8.7 二级页）：上方状态卡（记忆开关、注入预算，页脚写关闭后的后果）可滚动，
 * 下方 MEMORY.md 编辑卡固定在底部并随键盘上移，保证编辑器完整可见。
 */
@Composable
internal fun AgentMemoryScreen(
    state: AgentMemoryUiState,
    onAction: (AgentMemoryAction) -> Unit,
) {
    var showClearDialog by remember { mutableStateOf(false) }
    // 结果就地反馈（8.11，C5）：保存成功 =「保存」原地换成 ✓；清空成功 = 编辑框变空，本身就是结果；
    // 只有失败才弹 `Dialog/Info`，标题按用户刚做的操作写具体（「记忆保存失败」而不是「记忆」）。
    var lastAction by remember { mutableStateOf<MemoryUserAction?>(null) }
    val saved = rememberDoneFlash()
    val savedNotice = stringResource(R.string.state_ui_memory_saved_a2c61c)
    val clearedNotice = stringResource(R.string.state_ui_memory_cleared_b415bb)
    LaunchedEffect(state.notice) {
        when (state.notice) {
            savedNotice -> {
                saved.trigger()
                onAction(AgentMemoryAction.DismissNotice)
            }
            clearedNotice -> onAction(AgentMemoryAction.DismissNotice)
        }
    }
    val act: (MemoryUserAction, AgentMemoryAction) -> Unit = { kind, action ->
        lastAction = kind
        onAction(action)
    }

    MovoPage(
        title = stringResource(R.string.ui_memory_b55ff5),
        onBack = { onAction(AgentMemoryAction.NavigateBack) },
    ) { contentPadding, sidePadding ->
        val edge = sidePadding + MovoSpacing.pageEdge
        // 整页一起滚动（真机验收：窄屏 / 大字号时，上半部分单独滚动的状态卡被编辑器压成半张，像被盖住）。
        // 键盘弹出时编辑器随页面滚到可见位置。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .scrollEndHaptic()
                .padding(top = contentPadding.calculateTopPadding()),
        ) {
            Column(modifier = Modifier.padding(start = edge, end = edge, top = MovoSpacing.md, bottom = MovoSpacing.lg)) {
                run {
                    MovoCard {
                        CardTitle(stringResource(R.string.ui_memory_b55ff5))
                        SettingsRow(
                            title = stringResource(R.string.movo_memory_switch),
                            subtitle = stringResource(R.string.movo_memory_switch_desc),
                            enabled = !state.isLoading,
                            trailing = RowTrailing.Switch(state.enabled) { act(MemoryUserAction.Toggle, AgentMemoryAction.ToggleEnabled(it)) },
                        )
                        SettingsRow(
                            title = stringResource(R.string.ui_core_memory_injection_budget_48b5d5),
                            subtitle = stringResource(R.string.memory_budget_summary, formatNumber(state.coreBudgetChars)),
                            trailing = RowTrailing.None,
                            showDivider = false,
                        )
                        CardFooter(
                            listOf(
                                stringResource(R.string.movo_memory_footer_1),
                                stringResource(R.string.movo_memory_footer_2),
                            ),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = edge)
                    .navigationBarsPadding()
                    .padding(bottom = MovoSpacing.md),
            ) {
                MovoCard {
                    CardTitle("MEMORY.md")
                    Column(
                        modifier = Modifier.padding(
                            start = MovoSpacing.lg,
                            end = MovoSpacing.lg,
                            top = MovoSpacing.sm,
                            bottom = MovoSpacing.md,
                        ),
                    ) {
                        TextField(
                            value = state.draft,
                            onValueChange = { onAction(AgentMemoryAction.DraftChanged(it)) },
                            label = stringResource(R.string.ui_core_memory_user_name_long_term_preferences_aa6ff9),
                            useLabelAsPlaceholder = true,
                            enabled = !state.isLoading && !state.isSaving,
                            minLines = 6,
                            maxLines = 12,
                            textStyle = MovoTypography.labelRegular.copy(
                                fontFamily = FontFamily.Monospace,
                                color = MovoColors.textPrimary,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(MovoSpacing.sm))
                        val overLimit = state.draftBytes > state.maxBytes
                        // 超限：Rose 警示图标 + 主色文字（4.2 规则 2：Rose 不用于文字，颜色不是唯一信号）。
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (overLimit) {
                                MovoIcon(MovoIcons.CircleAlert, contentDescription = null, size = MovoSize.iconLabel, tint = MovoColors.roseFg)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = when {
                                    overLimit -> stringResource(R.string.memory_over_limit)
                                    state.hasUnsavedChanges -> stringResource(R.string.memory_unsaved_changes)
                                    else -> ""
                                },
                                color = if (overLimit) MovoColors.textPrimary else MovoColors.textSecondary,
                                style = MovoTypography.labelRegular,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${formatBytes(state.draftBytes)} / 1 MiB",
                                color = MovoColors.textSecondary,
                                style = MovoTypography.numericLabel,
                            )
                        }
                        Spacer(modifier = Modifier.height(MovoSpacing.md))
                        MovoButtonRow {
                            MovoBlockButton(
                                label = stringResource(R.string.ui_clear_84fcd7),
                                enabled = !state.isLoading && !state.isSaving && state.draft.isNotEmpty(),
                                onClick = { showClearDialog = true },
                                tone = BlockTone.Secondary,
                                modifier = Modifier.weight(1f),
                            )
                            MovoDoneBlockButton(
                                label = if (state.isSaving) stringResource(R.string.memory_saving) else stringResource(R.string.memory_save),
                                doneLabel = stringResource(R.string.feedback_saved),
                                done = saved.active,
                                // 显示 ✓ 期间保持可用外观（此时没有未保存的修改，点按不执行）。
                                enabled = state.canSave || saved.active,
                                onClick = { act(MemoryUserAction.Save, AgentMemoryAction.Save) },
                                tone = BlockTone.Primary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }

    MovoConfirmDialog(
        show = showClearDialog,
        title = stringResource(R.string.ui_clear_all_memory_a43bd3),
        message = stringResource(R.string.ui_the_entire_contents_of_memory_md_will_be_deleted_and_83a8ac),
        confirmText = stringResource(R.string.memory_clear),
        destructive = true,
        confirmEnabled = !state.isSaving,
        onDismissRequest = { showClearDialog = false },
        onConfirm = {
            showClearDialog = false
            act(MemoryUserAction.Clear, AgentMemoryAction.Clear)
        },
    )

    val failure = state.notice?.takeIf { it != savedNotice && it != clearedNotice }
    val shownFailure = rememberLastNonNull(failure)
    val failureTitle = stringResource(
        when (lastAction) {
            MemoryUserAction.Save -> R.string.state_ui_memory_save_failed_1f501e
            MemoryUserAction.Clear -> R.string.state_ui_memory_clearing_failed_7f0aba
            MemoryUserAction.Toggle -> R.string.state_ui_memory_switch_failed_to_save_83b5d6
            null -> R.string.feedback_memory_read_failed_title
        },
    )
    val retry = stringResource(R.string.feedback_try_again_later)
    MovoFailureDialog(
        show = failure != null,
        title = failureTitle,
        // 失败文案与标题相同（只有「保存失败」一句）时，说明写下一步。
        message = shownFailure?.takeIf { it != failureTitle } ?: retry,
        onDismiss = { onAction(AgentMemoryAction.DismissNotice) },
    )
}

/** 用户最近一次触发的操作，失败说明据此写具体标题。 */
private enum class MemoryUserAction { Save, Clear, Toggle }

private fun formatBytes(bytes: Int): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "%.1f KiB".format(bytes / 1_024.0)
    else -> "%.2f MiB".format(bytes / (1_024.0 * 1_024.0))
}

private fun formatNumber(value: Int): String = NumberFormat.getIntegerInstance().format(value)
