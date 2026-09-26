package io.github.mangi.eta.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.components.movo.CardFooter
import io.github.mangi.eta.ui.components.movo.CardTitle
import io.github.mangi.eta.ui.components.movo.MovoCard
import io.github.mangi.eta.ui.components.movo.MovoConfirmDialog
import io.github.mangi.eta.ui.components.movo.MovoListPage
import io.github.mangi.eta.ui.components.movo.SettingsRow
import io.github.mangi.eta.ui.navigation.AppRoute

/**
 * 设置 · 工具（规范 8.7，Figma「19 · 设置 · 工具」）：基础能力 3 个开关、敏感权限 2 个开关（页脚写后果，
 * 开启「敏感设备操作」先确认）、环境（Linux 工具环境、全部工具 → 只读的工具能力目录）。
 * 开关的存储 key 与默认值不变（[Prefs.Keys]），都是 App 本地配置，提交后回写远端。
 */
@Composable
internal fun ToolSettingsScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val agentPrefs = remember { Prefs.localAgentPreferences() }
    var pendingConfirm by remember { mutableStateOf<(() -> Unit)?>(null) }

    MovoListPage(title = stringResource(R.string.movo_settings_tools), onBack = onBack) {
        item(key = "basic") {
            MovoCard {
                CardTitle(stringResource(R.string.movo_tools_group_basic))
                PrefSwitchRow(
                    prefs = agentPrefs,
                    key = Prefs.Keys.AGENT_BROWSER_TOOLS,
                    title = stringResource(R.string.movo_tools_browser),
                    subtitle = stringResource(R.string.movo_tools_browser_desc),
                )
                PrefSwitchRow(
                    prefs = agentPrefs,
                    key = Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
                    title = stringResource(R.string.movo_tools_device),
                    subtitle = stringResource(R.string.movo_tools_device_desc),
                )
                PrefSwitchRow(
                    prefs = agentPrefs,
                    key = Prefs.Keys.AGENT_TERMINAL_TOOLS,
                    title = stringResource(R.string.movo_tools_terminal),
                    subtitle = stringResource(R.string.movo_tools_terminal_desc),
                    showDivider = false,
                )
            }
        }
        item(key = "sensitive") {
            MovoCard {
                CardTitle(stringResource(R.string.movo_tools_group_sensitive))
                PrefSwitchRow(
                    prefs = agentPrefs,
                    key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
                    title = stringResource(R.string.movo_tools_sensitive_read),
                    subtitle = stringResource(R.string.movo_tools_sensitive_read_desc),
                )
                PrefSwitchRow(
                    prefs = agentPrefs,
                    key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
                    title = stringResource(R.string.movo_tools_sensitive_action),
                    subtitle = stringResource(R.string.movo_tools_sensitive_action_desc),
                    showDivider = false,
                    confirmEnable = { proceed -> pendingConfirm = proceed },
                )
                CardFooter(
                    listOf(
                        stringResource(R.string.movo_tools_sensitive_footer_1),
                        stringResource(R.string.movo_tools_sensitive_footer_2),
                    ),
                )
            }
        }
        item(key = "environment") {
            MovoCard {
                CardTitle(stringResource(R.string.movo_tools_group_environment))
                SettingsRow(
                    title = stringResource(R.string.ui_linux_tool_environment_314d22),
                    onClick = { onNavigate(AppRoute.LinuxEnvironment) },
                )
                SettingsRow(
                    title = stringResource(R.string.movo_tools_all),
                    showDivider = false,
                    onClick = { onNavigate(AppRoute.Tools) },
                )
            }
        }
    }

    MovoConfirmDialog(
        show = pendingConfirm != null,
        title = stringResource(R.string.movo_tools_confirm_title),
        message = stringResource(R.string.movo_tools_confirm_message),
        confirmText = stringResource(R.string.movo_action_turn_on),
        onConfirm = {
            pendingConfirm?.invoke()
            pendingConfirm = null
        },
        onDismissRequest = { pendingConfirm = null },
    )
}
