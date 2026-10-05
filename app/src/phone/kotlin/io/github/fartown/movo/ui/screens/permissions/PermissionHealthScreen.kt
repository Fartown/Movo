package io.github.fartown.movo.ui.screens.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.accessibility.AccessibilityProtectionClient
import io.github.fartown.movo.ui.components.label
import io.github.fartown.movo.ui.components.movo.CardTitle
import io.github.fartown.movo.ui.components.movo.MovoCard
import io.github.fartown.movo.ui.components.movo.MovoListPage
import io.github.fartown.movo.ui.components.movo.RowTrailing
import io.github.fartown.movo.ui.components.movo.SettingsRow
import io.github.fartown.movo.ui.model.PermissionHealthAction
import io.github.fartown.movo.ui.model.PermissionHealthItemUi
import io.github.fartown.movo.ui.model.PermissionHealthUiState
import io.github.fartown.movo.ui.model.PermissionStatusUi
import io.github.fartown.movo.ui.screens.settings.FrameworkPrefsState
import io.github.fartown.movo.ui.screens.settings.OnResumeEffect
import io.github.fartown.movo.ui.screens.settings.rememberFrameworkPrefsState

/** Movo 核心能力（操作其他 App、后台执行）依赖的权限；其余按需开启。 */
private val RequiredPermissionIds = listOf("overlay", "accessibility", "background")

/**
 * 设置 · 权限（规范 8.7「权限」二级页）：全部 10 项权限与「强制保持无障碍」都在这里，
 * 分「必要权限」「按需开启」两张卡；缺失的必要权限在值前加 Rose 状态点。
 */
@Composable
fun PermissionHealthScreen(
    state: PermissionHealthUiState,
    onAction: (PermissionHealthAction) -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit = {},
) {
    OnResumeEffect(onRefresh)
    val framework = rememberFrameworkPrefsState()
    val required = RequiredPermissionIds.mapNotNull { id -> state.items.firstOrNull { it.id == id } }
    val optional = state.items.filter { it.id !in RequiredPermissionIds }
    MovoListPage(
        title = stringResource(R.string.ui_permissions_560165),
        onBack = { onAction(PermissionHealthAction.NavigateBack) },
        modifier = modifier,
    ) {
        if (required.isNotEmpty()) {
            item(key = "required") {
                MovoCard {
                    CardTitle(stringResource(R.string.movo_permissions_group_required))
                    required.forEachIndexed { index, item ->
                        val divider = index < required.lastIndex || framework.showFrameworkRows
                        PermissionRow(item, attentionWhenMissing = true, showDivider = divider) {
                            onAction(PermissionHealthAction.OpenItemAction(item.id))
                        }
                    }
                    AccessibilityProtectionRow(framework, onChanged = onRefresh)
                }
            }
        }
        if (optional.isNotEmpty()) {
            item(key = "optional") {
                MovoCard {
                    CardTitle(stringResource(R.string.movo_permissions_group_optional))
                    optional.forEachIndexed { index, item ->
                        PermissionRow(item, attentionWhenMissing = false, showDivider = index < optional.lastIndex) {
                            onAction(PermissionHealthAction.OpenItemAction(item.id))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    item: PermissionHealthItemUi,
    attentionWhenMissing: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    SettingsRow(
        title = item.title,
        subtitle = item.summary.takeIf { it.isNotBlank() },
        trailing = RowTrailing.Arrow(item.status.label()),
        attention = attentionWhenMissing && item.status == PermissionStatusUi.Missing,
        showDivider = showDivider,
        onClick = onClick,
    )
}

/**
 * 强制保持无障碍（原设置页「权限」组）：框架在线或曾连接时显示，在线且没有进行中的请求时可切换；
 * 异步结果回写，失败原因就地写在行说明里。
 */
@Composable
private fun AccessibilityProtectionRow(framework: FrameworkPrefsState, onChanged: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AccessibilityProtectionClient.isEnabled(context)) }
    var pending by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    OnResumeEffect { enabled = AccessibilityProtectionClient.isEnabled(context) }
    if (!framework.showFrameworkRows) return
    SettingsRow(
        title = stringResource(R.string.ui_enforce_accessibility_55e838),
        subtitle = failure,
        enabled = framework.frameworkLive && !pending,
        showDivider = false,
        trailing = RowTrailing.Switch(enabled) { value ->
            if (pending) return@Switch
            pending = true
            AccessibilityProtectionClient.setEnabled(context = context, enabled = value) { result ->
                pending = false
                enabled = result.enabled
                failure = when (result.status) {
                    AccessibilityProtectionClient.ControlStatus.APPLIED -> null
                    AccessibilityProtectionClient.ControlStatus.UNAVAILABLE ->
                        context.getString(R.string.accessibility_protection_unavailable)
                    AccessibilityProtectionClient.ControlStatus.REJECTED ->
                        context.getString(R.string.accessibility_protection_rejected)
                }
                onChanged()
            }
        },
    )
}
