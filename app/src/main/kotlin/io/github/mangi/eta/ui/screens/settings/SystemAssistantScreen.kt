package io.github.mangi.eta.ui.screens.settings

import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.systemizer.GoogleAppSystemizerInstaller
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.components.movo.CardFooter
import io.github.mangi.eta.ui.components.movo.CardTitle
import io.github.mangi.eta.ui.components.movo.MovoCard
import io.github.mangi.eta.ui.components.movo.MovoChoiceDialog
import io.github.mangi.eta.ui.components.movo.MovoConfirmDialog
import io.github.mangi.eta.ui.components.movo.MovoListPage
import io.github.mangi.eta.ui.components.movo.RowTrailing
import io.github.mangi.eta.ui.components.movo.SettingsRow
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 · 系统助手（规范 8.7「系统助手」二级页）：数字助理应用始终显示；电源键长按、自动设置默认助理、
 * 小布 / 小爱兼容、Gemini、一圈即搜在框架在线或曾经连接过时显示，离线时置灰并显示上次的值；
 * 「将 Google App 转为系统应用」只看 Root（或曾用过系统化），与框架无关。
 */
@Composable
internal fun SystemAssistantScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val capabilities = rememberDeviceCapabilities()
    val framework = rememberFrameworkPrefsState()
    val prefs = framework.prefs
    var openAssistantFailed by remember { mutableStateOf(false) }
    var showPowerDialog by remember { mutableStateOf(false) }
    var powerSaveFailed by remember { mutableStateOf(false) }
    var showSystemizerDialog by remember { mutableStateOf(false) }
    var installingSystemizer by remember { mutableStateOf(false) }
    var systemizerResult by remember { mutableStateOf<String?>(null) }

    var powerAssistantTarget by remember(prefs) {
        mutableStateOf(prefs?.let(Prefs::powerAssistantTarget) ?: framework.history.powerTarget())
    }
    DisposableEffect(prefs) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, key ->
            if (key == Prefs.Keys.POWER_KEY_ASSISTANT_TARGET || key == Prefs.Keys.POWER_KEY_TAKEOVER) {
                powerAssistantTarget = Prefs.powerAssistantTarget(changedPrefs)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val powerTargets = PowerAssistantTarget.entries
    val showGemini = framework.showFrameworkRows || capabilities.root.isGranted || framework.hasUsedSystemizer

    MovoListPage(title = stringResource(R.string.movo_settings_system_assistant), onBack = onBack) {
        item(key = "default_assistant") {
            MovoCard {
                CardTitle(stringResource(R.string.movo_assistant_group_default))
                SettingsRow(
                    title = stringResource(R.string.ui_eta_system_assistant_003e9b),
                    subtitle = if (openAssistantFailed) stringResource(R.string.settings_open_assistant_failed) else null,
                    trailing = RowTrailing.External(),
                    showDivider = framework.showFrameworkRows,
                    onClick = {
                        openAssistantFailed = runCatching {
                            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                        }.isFailure
                    },
                )
                if (framework.showFrameworkRows) {
                    SettingsRow(
                        title = stringResource(R.string.ui_long_press_the_power_button_1958d0),
                        subtitle = if (powerSaveFailed) stringResource(R.string.movo_settings_save_failed) else null,
                        trailing = RowTrailing.Arrow(powerAssistantTarget.displayName(context)),
                        enabled = framework.frameworkLive,
                        onClick = { showPowerDialog = true },
                    )
                    PrefSwitchRow(
                        prefs = prefs,
                        key = Prefs.Keys.ASSISTANT_AUTO_CONFIG,
                        title = stringResource(R.string.ui_automatically_set_default_assistant_f86963),
                        showDivider = false,
                    )
                    if (!framework.frameworkLive) {
                        CardFooter(listOf(stringResource(R.string.movo_assistant_footer_offline)))
                    }
                } else {
                    CardFooter(listOf(stringResource(R.string.movo_assistant_footer_framework)))
                }
            }
        }
        if (framework.showFrameworkRows) {
            item(key = "oem_assistant") {
                MovoCard {
                    CardTitle(stringResource(R.string.movo_assistant_group_oem))
                    PrefSwitchRow(
                        prefs = prefs,
                        key = Prefs.Keys.AGENT_CUSTOM_MODEL,
                        title = stringResource(R.string.ui_enable_vendor_assistant_custom_models_c8e465),
                    )
                    PrefSwitchRow(
                        prefs = prefs,
                        key = Prefs.Keys.AGENT_REQUIRE_PREFIX,
                        title = stringResource(R.string.ui_only_take_over_with_agent_prefix_d17556),
                        showDivider = false,
                    )
                }
            }
        }
        if (showGemini) {
            item(key = "gemini") {
                MovoCard {
                    CardTitle(stringResource(R.string.movo_assistant_group_gemini))
                    val showSystemizer = capabilities.root.isGranted || framework.hasUsedSystemizer
                    if (framework.showFrameworkRows) {
                        PrefSwitchRow(
                            prefs = prefs,
                            key = Prefs.Keys.HOTWORD_SELF_HEAL,
                            title = stringResource(R.string.ui_maintain_hey_google_detection_after_screen_rest_9d6877),
                        )
                        PrefSwitchRow(
                            prefs = prefs,
                            key = Prefs.Keys.LOCKSCREEN_VOICE_COMMAND,
                            title = stringResource(R.string.ui_lock_screen_evokes_automatic_voice_input_1cde18),
                        )
                        PrefSwitchRow(
                            prefs = prefs,
                            key = Prefs.Keys.SCREEN_ON_VOICE_COMMAND,
                            title = stringResource(R.string.ui_bright_screen_evokes_automatic_voice_input_4358fe),
                            showDivider = showSystemizer,
                        )
                    }
                    if (showSystemizer) {
                        SettingsRow(
                            title = stringResource(R.string.ui_convert_google_apps_to_system_apps_0f6d89),
                            subtitle = when {
                                installingSystemizer -> stringResource(R.string.status_processing)
                                systemizerResult != null -> systemizerResult
                                !capabilities.root.isGranted -> stringResource(R.string.capability_root_required)
                                else -> null
                            },
                            enabled = !installingSystemizer,
                            showDivider = false,
                            onClick = {
                                if (!capabilities.root.isGranted) {
                                    onNavigate(AppRoute.SystemEnhance)
                                } else if (!installingSystemizer) {
                                    showSystemizerDialog = true
                                }
                            },
                        )
                    }
                }
            }
        }
        if (framework.showFrameworkRows) {
            item(key = "circle_to_search") {
                MovoCard {
                    CardTitle(stringResource(R.string.movo_assistant_group_circle))
                    PrefSwitchRow(
                        prefs = prefs,
                        key = Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
                        title = stringResource(R.string.ui_long_press_on_the_gesture_bar_triggers_a_circle_to_s_b80117),
                    )
                    PrefSwitchRow(
                        prefs = prefs,
                        key = Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
                        title = stringResource(R.string.ui_long_press_with_two_fingers_to_trigger_a_circle_sear_ab597a),
                        showDivider = false,
                    )
                }
            }
        }
    }

    MovoChoiceDialog(
        show = showPowerDialog,
        title = stringResource(R.string.ui_long_press_the_power_button_1958d0),
        options = powerTargets.map { it.displayName(context) },
        selectedIndex = powerTargets.indexOf(powerAssistantTarget),
        onSelect = { index ->
            val target = powerTargets.getOrNull(index)
            val targetPrefs = prefs
            if (target != null && targetPrefs != null) {
                if (putStringSync(targetPrefs, Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, target.persistedValue)) {
                    powerSaveFailed = false
                    powerAssistantTarget = target
                    framework.history.recordCommittedTarget(target)
                } else {
                    powerSaveFailed = true
                }
            }
        },
        onDismissRequest = { showPowerDialog = false },
    )

    MovoConfirmDialog(
        show = showSystemizerDialog,
        title = stringResource(R.string.ui_convert_google_apps_to_system_apps_0f6d89),
        message = stringResource(R.string.ui_system_applications_have_voice_wake_up_permissions_f_0190f2),
        confirmText = if (installingSystemizer) stringResource(R.string.status_processing) else stringResource(R.string.action_confirm),
        confirmEnabled = !installingSystemizer,
        cancelEnabled = !installingSystemizer,
        onDismissRequest = { if (!installingSystemizer) showSystemizerDialog = false },
        onConfirm = confirm@{
            if (installingSystemizer) return@confirm
            if (!capabilities.root.isGranted) {
                showSystemizerDialog = false
                onNavigate(AppRoute.SystemEnhance)
                return@confirm
            }
            framework.history.recordSystemizerUse()
            framework.hasUsedSystemizer = true
            showSystemizerDialog = false
            installingSystemizer = true
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    GoogleAppSystemizerInstaller(context.applicationContext).install()
                }
                installingSystemizer = false
                systemizerResult = result.toMessage(context)
            }
        },
    )
}
