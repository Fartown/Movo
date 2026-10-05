package io.github.fartown.movo.ui.screens.settings

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.fartown.movo.MovoApp
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import io.github.fartown.movo.config.PowerAssistantTarget
import io.github.fartown.movo.config.Prefs
import io.github.fartown.movo.data.repository.RuntimeConfigRepository
import io.github.fartown.movo.systemizer.RootManager
import io.github.fartown.movo.systemizer.SystemizerInstallResult
import io.github.fartown.movo.ui.app.EnhancementSettingsHistory
import io.github.fartown.movo.ui.components.movo.RowTrailing
import io.github.fartown.movo.ui.components.movo.SettingsRow
import kotlinx.coroutines.launch

/**
 * 设置页与系统助手页共用的框架配置状态：
 * [prefs] 为 LSPosed RemotePreferences，框架未就绪时为 null（Hook 开关置灰但显示上次的值）；
 * [hasConnectedFramework] / [hasUsedSystemizer] 决定框架相关分组与系统化入口是否可见。
 */
@Stable
internal class FrameworkPrefsState(
    val history: EnhancementSettingsHistory,
) {
    var prefs by mutableStateOf<SharedPreferences?>(null)
    var hasConnectedFramework by mutableStateOf(history.hasConnected)
    var hasUsedSystemizer by mutableStateOf(history.hasUsedSystemizer)

    /** 框架在线或曾经连接过：显示框架相关的开关。 */
    val showFrameworkRows: Boolean get() = prefs != null || hasConnectedFramework

    /** 框架当前在线：可以修改。 */
    val frameworkLive: Boolean get() = prefs != null
}

@Composable
internal fun rememberFrameworkPrefsState(): FrameworkPrefsState {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val state = remember(context.applicationContext) {
        FrameworkPrefsState(EnhancementSettingsHistory(context)).also {
            it.prefs = Prefs.remotePreferencesForUi(MovoApp.serviceInstance)
        }
    }
    DisposableEffect(state) {
        val listener = object : MovoApp.ServiceStateListener {
            override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
                state.prefs = Prefs.remotePreferencesForUi(service)
                state.prefs?.let { connected ->
                    state.history.captureConnected(connected)
                    state.hasConnectedFramework = true
                }
                Prefs.reconcileAgentPreferences(service)
                coroutineScope.launch { RuntimeConfigRepository.ensureDefaults(service) }
            }
        }
        MovoApp.addServiceStateListener(listener, notifyImmediately = true)
        onDispose { MovoApp.removeServiceStateListener(listener) }
    }
    return state
}

/** 读取布尔配置并跟随外部改动；框架离线时读快照。 */
@Composable
internal fun rememberBooleanPref(prefs: SharedPreferences?, key: String): androidx.compose.runtime.MutableState<Boolean> {
    val context = LocalContext.current
    val history = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: true
    val checked = remember(prefs, key) {
        mutableStateOf(prefs?.getBoolean(key, default) ?: history.checked(key, default))
    }
    DisposableEffect(prefs, key) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, changedKey ->
            if (changedKey == key) checked.value = changedPrefs.getBoolean(key, default)
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return checked
}

/**
 * 布尔开关行：同步提交，RemotePreferences 提交失败时不改 UI、在行说明里就地提示（规范 8.11 不用 Toast）；
 * 本地 Agent 开关提交后回写远端。[confirmEnable] 不为 null 时，开启前先经它确认（风险开关）。
 */
@Composable
internal fun PrefSwitchRow(
    prefs: SharedPreferences?,
    key: String,
    title: String,
    subtitle: String? = null,
    leading: io.github.fartown.movo.ui.components.movo.RowLeading? = null,
    showDivider: Boolean = true,
    confirmEnable: ((proceed: () -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current
    val history = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    var checked by rememberBooleanPref(prefs, key)
    var failed by remember(key) { mutableStateOf(false) }
    val commit: (Boolean) -> Unit = commit@{ value ->
        val targetPrefs = prefs ?: return@commit
        if (putBooleanSync(targetPrefs, key, value)) {
            failed = false
            checked = value
            history.recordCommittedBoolean(key, value)
            if (key in Prefs.Keys.LOCAL_AGENT_KEYS) {
                Prefs.reconcileAgentPreferences(MovoApp.serviceInstance)
            }
        } else {
            failed = true
        }
    }
    SettingsRow(
        title = title,
        subtitle = if (failed) stringResource(R.string.movo_settings_save_failed) else subtitle,
        leading = leading,
        showDivider = showDivider,
        enabled = prefs != null,
        trailing = RowTrailing.Switch(checked) { value ->
            if (value && confirmEnable != null) confirmEnable { commit(true) } else commit(value)
        },
    )
}

/** 组内计数：已开启的本地开关数量，跟随改动刷新。 */
@Composable
internal fun rememberEnabledCount(prefs: SharedPreferences?, keys: List<String>): Int {
    fun count() = keys.count { prefs?.getBoolean(it, Prefs.Keys.BOOLEAN_DEFAULTS[it] ?: true) ?: (Prefs.Keys.BOOLEAN_DEFAULTS[it] ?: true) }
    var value by remember(prefs, keys) { mutableIntStateOf(count()) }
    DisposableEffect(prefs, keys) {
        if (prefs == null) return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key in keys) value = count() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

/** 页面回到前台（从系统设置返回）时执行 [onResume]。 */
@Composable
internal fun OnResumeEffect(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latest by androidx.compose.runtime.rememberUpdatedState(onResume)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) latest() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

internal val ToolSwitchKeys = listOf(
    Prefs.Keys.AGENT_BROWSER_TOOLS,
    Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
    Prefs.Keys.AGENT_TERMINAL_TOOLS,
    Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
    Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
)

/**
 * 同步写入布尔值。RemotePreferences 的 commit 先更新本进程 map 再同步等待 binder 提交，
 * 失败返回 false 但本进程 map 已被改写——此时 hook 进程收不到新值，调用方据此决定是否更新 UI。
 */
internal fun putBooleanSync(prefs: SharedPreferences, key: String, value: Boolean): Boolean =
    runCatching { prefs.edit().putBoolean(key, value).commit() }.getOrDefault(false)

internal fun putStringSync(prefs: SharedPreferences, key: String, value: String): Boolean =
    runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)

internal fun PowerAssistantTarget.displayName(context: Context): String =
    when (this) {
        PowerAssistantTarget.OEM -> context.getString(R.string.power_assistant_system_default)
        PowerAssistantTarget.GEMINI -> "Gemini"
        PowerAssistantTarget.MOVO -> "Movo"
    }

internal fun isAgentAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(context, AgentAccessibilityService::class.java).flattenToString()
    val enabledServices = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}

internal fun SystemizerInstallResult.toMessage(context: Context): String =
    when (this) {
        SystemizerInstallResult.AlreadySystemized -> context.getString(R.string.systemizer_already_system)
        SystemizerInstallResult.GoogleAppMissing -> context.getString(R.string.systemizer_google_missing)
        SystemizerInstallResult.UnsupportedRootManager -> context.getString(R.string.systemizer_root_manager_missing)
        SystemizerInstallResult.KernelSuMetamoduleMissing -> context.getString(R.string.systemizer_metamodule_missing)
        is SystemizerInstallResult.RootPermissionUnavailable -> when (rootManager) {
            RootManager.KERNEL_SU -> context.getString(R.string.systemizer_grant_kernelsu)
            RootManager.MAGISK -> context.getString(R.string.systemizer_grant_magisk)
            RootManager.UNSUPPORTED -> context.getString(R.string.systemizer_root_denied)
        }
        is SystemizerInstallResult.InstalledRebootRequired -> context.getString(R.string.systemizer_installed)
        is SystemizerInstallResult.Failed -> commandOutput
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?.let { "$message：$it" }
            ?: message
    }
