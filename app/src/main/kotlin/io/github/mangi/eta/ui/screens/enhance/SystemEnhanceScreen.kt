package io.github.mangi.eta.ui.screens.enhance

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.description
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.components.movo.CardFooter
import io.github.mangi.eta.ui.components.movo.CardTitle
import io.github.mangi.eta.ui.components.movo.MovoCard
import io.github.mangi.eta.ui.components.movo.MovoListPage
import io.github.mangi.eta.ui.components.movo.MovoPillButton
import io.github.mangi.eta.ui.components.movo.RowTrailing
import io.github.mangi.eta.ui.components.movo.SettingsRow
import io.github.mangi.eta.ui.model.AgentSystemEnhanceAction

/**
 * 设置 · Root 与系统增强（规范 8.7 二级页）：连接状态卡（Root 状态 + 授权 / 重新检测、框架通信，
 * 页脚写模块启用与作用域说明）、Root 能力与系统助手与增强两张只读说明卡。
 * Root 状态随 [rememberDeviceCapabilities] 在回到前台时刷新。
 */
@Composable
fun SystemEnhanceScreen(
    onAction: (AgentSystemEnhanceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val capabilities = rememberDeviceCapabilities()
    val canRequestRoot = capabilities.root.suPresent && !capabilities.root.isGranted
    MovoListPage(
        title = stringResource(R.string.capability_enhancements),
        onBack = { onAction(AgentSystemEnhanceAction.NavigateBack) },
        modifier = modifier,
    ) {
        item(key = "access") {
            MovoCard {
                CardTitle(stringResource(R.string.movo_enhance_group_status))
                SettingsRow(
                    title = "Root",
                    subtitle = capabilities.root.description(context),
                    trailing = RowTrailing.Custom {
                        MovoPillButton(
                            label = stringResource(
                                if (canRequestRoot) R.string.capability_root_request
                                else R.string.capability_root_refresh,
                            ),
                            primary = canRequestRoot,
                            enabled = !capabilities.root.isChecking,
                            onClick = {
                                onAction(
                                    if (canRequestRoot) AgentSystemEnhanceAction.RequestRoot
                                    else AgentSystemEnhanceAction.RefreshRoot,
                                )
                            },
                        )
                    },
                )
                SettingsRow(
                    title = stringResource(R.string.capability_xposed_service),
                    subtitle = stringResource(
                        if (capabilities.xposedConnected) R.string.capability_xposed_connected
                        else R.string.capability_xposed_disconnected,
                    ),
                    trailing = RowTrailing.None,
                    showDivider = false,
                )
                CardFooter(
                    listOf(
                        stringResource(R.string.movo_enhance_footer_1),
                        stringResource(R.string.movo_enhance_footer_2),
                    ),
                )
            }
        }
        item(key = "root-features") {
            MovoCard {
                CardTitle(stringResource(R.string.capability_root_features))
                InfoRow(R.string.capability_root_device, R.string.capability_root_device_summary)
                InfoRow(R.string.capability_root_data, R.string.capability_root_data_summary)
                InfoRow(R.string.capability_root_linux, R.string.capability_root_linux_summary, last = true)
            }
        }
        item(key = "hook-features") {
            MovoCard {
                CardTitle(stringResource(R.string.capability_system_features))
                InfoRow(R.string.capability_hook_assistants, R.string.capability_hook_assistants_summary)
                InfoRow(R.string.capability_hook_google, R.string.capability_hook_google_summary)
                InfoRow(R.string.capability_hook_accessibility, R.string.capability_hook_accessibility_summary, last = true)
            }
        }
    }
}

/** 只读说明行：标题 + 说明，不可点击。 */
@Composable
private fun InfoRow(title: Int, summary: Int, last: Boolean = false) {
    SettingsRow(
        title = stringResource(title),
        subtitle = stringResource(summary),
        trailing = RowTrailing.None,
        showDivider = !last,
    )
}
