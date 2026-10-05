package io.github.fartown.movo.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.monitor.MonitorSettings
import io.github.fartown.movo.config.Prefs
import io.github.fartown.movo.ui.components.movo.CardFooter
import io.github.fartown.movo.ui.components.movo.CardTitle
import io.github.fartown.movo.ui.components.movo.MovoCard
import io.github.fartown.movo.ui.components.movo.MovoConfirmDialog
import io.github.fartown.movo.ui.components.movo.MovoListPage
import io.github.fartown.movo.ui.components.movo.SettingsRow
import io.github.fartown.movo.ui.navigation.AppRoute

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
    val context = androidx.compose.ui.platform.LocalContext.current
    var monitorMaxMs by remember { mutableStateOf(MonitorSettings.maxDurationMs(context)) }
    var showMonitorChoice by remember { mutableStateOf(false) }

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
        // 后台监听（规范 8.12，Figma「18-13」）：最长监听时长，默认 2 小时；修改只影响之后新开始的监听。
        item(key = "monitor") {
            MovoCard {
                CardTitle(stringResource(R.string.monitor_settings_group))
                SettingsRow(
                    title = stringResource(R.string.monitor_settings_max_duration),
                    trailing = io.github.fartown.movo.ui.components.movo.RowTrailing.Arrow(io.github.fartown.movo.ui.components.monitorDurationLabel(monitorMaxMs)),
                    showDivider = false,
                    onClick = { showMonitorChoice = true },
                )
                // 规范 10.1：页脚一句一行，不在词中间折行。
                CardFooter(
                    listOf(
                        stringResource(R.string.monitor_settings_footer_1),
                        stringResource(R.string.monitor_settings_footer_2),
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

    io.github.fartown.movo.ui.components.movo.MovoChoiceDialog(
        show = showMonitorChoice,
        title = stringResource(R.string.monitor_settings_max_duration),
        description = stringResource(R.string.monitor_settings_choice_desc),
        options = MonitorSettings.MAX_CHOICES_MS.map { io.github.fartown.movo.ui.components.monitorDurationLabel(it) },
        selectedIndex = MonitorSettings.MAX_CHOICES_MS.indexOf(monitorMaxMs),
        onSelect = { index ->
            val value = MonitorSettings.MAX_CHOICES_MS[index]
            MonitorSettings.setMaxDurationMs(context, value)
            monitorMaxMs = value
        },
        onDismissRequest = { showMonitorChoice = false },
    )

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
