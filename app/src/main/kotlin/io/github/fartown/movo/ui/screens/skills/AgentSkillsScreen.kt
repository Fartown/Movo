package io.github.fartown.movo.ui.screens.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.fartown.movo.R
import io.github.fartown.movo.ui.components.movo.CardTitle
import io.github.fartown.movo.ui.components.movo.MovoCard
import io.github.fartown.movo.ui.components.movo.MovoConfirmDialog
import io.github.fartown.movo.ui.components.movo.MovoListPage
import io.github.fartown.movo.ui.components.movo.MovoPillButton
import io.github.fartown.movo.ui.model.AgentSkillsAction
import io.github.fartown.movo.ui.model.AgentSkillsUiState
import io.github.fartown.movo.ui.model.SkillItemUi
import io.github.fartown.movo.ui.model.canDeleteUserSkill
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text

/**
 * Skills（规范 8.7 二级页，设置 · 能力与扩展 · Skills）：安装卡（从 ZIP 导入，检查中显示加载圈）、
 * 内置 / 用户安装 / 已移除三张分组卡（标题在卡内），替换 / 删除 / 结果通知对话框。
 * 所有操作仍通过 [AgentSkillsAction] 分派；导入或单个 Skill 处理中时整页操作禁用。
 */
@Composable
fun AgentSkillsScreen(
    state: AgentSkillsUiState,
    onAction: (AgentSkillsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleteTarget by remember { mutableStateOf<SkillItemUi?>(null) }
    val operationPending = state.isImporting || state.busySkillId != null
    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onAction(AgentSkillsAction.ImportZip(uri.toString()))
    }
    val openZipPicker = {
        zipPicker.launch(
            arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
            ),
        )
    }

    MovoListPage(
        title = stringResource(R.string.route_skills),
        onBack = { onAction(AgentSkillsAction.NavigateBack) },
        modifier = modifier,
    ) {
        val installed = state.skills.filter { it.installed }
        val builtinInstalled = installed.filter { it.source == "builtin" }
        val userInstalled = installed.filter { it.canDeleteUserSkill }
        val removed = state.skills.filter { !it.installed }

        item(key = "zip-import-card") {
            MovoCard {
                CardTitle(stringResource(R.string.ui_install_087db6))
                SkillRow(
                    title = if (state.isImporting) {
                        stringResource(R.string.skills_checking_package)
                    } else {
                        stringResource(R.string.skills_import_zip)
                    },
                    subtitle = if (state.isImporting) {
                        stringResource(R.string.skills_installing_package)
                    } else {
                        stringResource(R.string.skills_choose_package)
                    },
                    enabled = !operationPending,
                    showDivider = false,
                    onClick = openZipPicker,
                    onClickLabel = stringResource(R.string.skills_choose_zip),
                ) {
                    if (state.isImporting) {
                        SkillSpinner()
                    } else {
                        MovoIcon(MovoIcons.ChevronRight, null, size = MovoSize.iconSmall, tint = MovoColors.textTertiary)
                    }
                }
            }
        }

        if (builtinInstalled.isNotEmpty()) {
            item(key = "builtin-card") {
                MovoCard {
                    CardTitle(stringResource(R.string.ui_built_in_skills_1ceedf))
                    builtinInstalled.forEachIndexed { index, skill ->
                        SkillSwitchRow(
                            skill = skill,
                            enabled = !operationPending,
                            onToggle = { enabled ->
                                onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                            },
                            showDivider = index != builtinInstalled.lastIndex,
                        )
                    }
                }
            }
        }

        if (userInstalled.isNotEmpty()) {
            item(key = "user-card") {
                MovoCard {
                    CardTitle(stringResource(R.string.ui_user_skills_748e7f))
                    userInstalled.forEachIndexed { index, skill ->
                        SkillSwitchRow(
                            skill = skill,
                            enabled = !operationPending,
                            onToggle = { enabled ->
                                onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                            },
                            onDelete = { deleteTarget = skill },
                            showDivider = index != userInstalled.lastIndex,
                        )
                    }
                }
            }
        }

        if (removed.isNotEmpty()) {
            item(key = "removed-card") {
                MovoCard {
                    CardTitle(stringResource(R.string.ui_removed_4e5c49))
                    removed.forEachIndexed { index, skill ->
                        SkillRow(
                            title = skill.name,
                            subtitle = stringResource(R.string.ui_click_to_reinstall_dc60de),
                            enabled = !operationPending,
                            showDivider = index != removed.lastIndex,
                            onClick = { onAction(AgentSkillsAction.ReinstallBuiltin(skill.id)) },
                        ) {
                            MovoIcon(MovoIcons.Download, null, size = MovoSize.iconSmall, tint = MovoColors.textTertiary)
                        }
                    }
                }
            }
        }

        if (state.skills.isEmpty() && !state.isLoading) {
            item(key = "empty") {
                MovoCard {
                    Column(modifier = Modifier.padding(MovoSpacing.lg)) {
                        Text(
                            stringResource(R.string.ui_no_skills_installed_yet_4e960f),
                            style = MovoTypography.bodyStrong,
                            color = MovoColors.textPrimary,
                        )
                        Text(
                            stringResource(R.string.skills_choose_package),
                            style = MovoTypography.labelRegular,
                            color = MovoColors.textSecondary,
                        )
                        Spacer(Modifier.size(MovoSpacing.md))
                        MovoPillButton(
                            label = stringResource(R.string.skills_import_zip),
                            onClick = openZipPicker,
                            enabled = !operationPending,
                            primary = true,
                        )
                    }
                }
            }
        }
    }

    // 对话框退场动画期间内容保持上一次的值，避免文字闪空。
    val replacement = rememberLastNonNull(state.replacement)
    MovoConfirmDialog(
        show = state.replacement != null,
        title = stringResource(R.string.ui_replace_user_skills_250e98),
        message = replacement?.let { stringResource(R.string.skills_replace_summary, it.name, it.id) },
        confirmText = stringResource(R.string.skills_replace),
        confirmEnabled = !operationPending,
        onConfirm = { onAction(AgentSkillsAction.ConfirmZipReplacement) },
        onDismissRequest = { onAction(AgentSkillsAction.CancelZipReplacement) },
    )

    val deleting = rememberLastNonNull(deleteTarget)
    MovoConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.ui_delete_user_skills_f319a9),
        message = deleting?.let { stringResource(R.string.skills_delete_summary, it.name) },
        confirmText = stringResource(R.string.ui_delete_3755f5),
        destructive = true,
        confirmEnabled = !operationPending,
        onConfirm = {
            val target = deleteTarget ?: return@MovoConfirmDialog
            deleteTarget = null
            onAction(AgentSkillsAction.DeleteSkill(target.id))
        },
        onDismissRequest = { deleteTarget = null },
    )

    val notice = rememberLastNonNull(state.notice)
    SkillTextDialog(
        show = state.notice != null,
        title = notice?.title.orEmpty(),
        text = notice?.message.orEmpty(),
        buttonText = stringResource(R.string.ui_knew_cb63c6),
        isError = notice?.isError == true,
        onDismiss = { onAction(AgentSkillsAction.DismissNotice) },
    )
}

private class LastValue<T : Any> {
    var value: T? = null
}

/** 返回 [value]；为 null 时返回上一次的非空值（给对话框退场动画用）。 */
@Composable
private fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { LastValue<T>() }
    if (value != null) holder.value = value
    return value ?: holder.value
}
