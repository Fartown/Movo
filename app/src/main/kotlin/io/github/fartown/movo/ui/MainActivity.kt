package io.github.fartown.movo.ui

import android.app.UiModeManager
import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.lifecycleScope
import io.github.fartown.movo.agent.runtime.AgentConversationHandoff
import io.github.fartown.movo.data.model.AppearanceThemeMode
import io.github.fartown.movo.data.repository.AppearanceSettingsRepository
import io.github.fartown.movo.ui.app.AgentAppRoot
import io.github.fartown.movo.ui.app.AgentAppTheme
import io.github.fartown.movo.ui.app.PredictiveBackController
import io.github.fartown.movo.ui.app.installStartupSplash
import io.github.fartown.movo.ui.markdown.InAppBrowserUriHandler
import io.github.fartown.movo.ui.markdown.normalizeBrowserLink
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var resultConversationHandoff by mutableStateOf<AgentConversationHandoff.Request?>(null)
    private var browserUrl by mutableStateOf<String?>(null)
    private var openVoiceSettings by mutableStateOf(false)
    private var appliedPredictiveBackEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logRelaunchConfigDiff()
        var contentReady = false
        // 冷启动：首页进场等启动页开始退场再播。
        if (savedInstanceState == null) io.github.fartown.movo.ui.app.StartupReveal.hold()
        installStartupSplash { contentReady }
        enableEdgeToEdge()
        updateAssistantHandoff(intent)
        lifecycleScope.launch {
            val initialAppearance = AppearanceSettingsRepository.settings()
            appliedPredictiveBackEnabled = initialAppearance.predictiveBackEnabled
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow()
                    .collectAsState(initial = initialAppearance)

                // 规范 v1 只做浅色：应用级固定为浅色，存储的主题模式保留但不生效。
                LaunchedEffect(Unit) {
                    updateApplicationNightMode(AppearanceThemeMode.LIGHT)
                }

                LaunchedEffect(appearance.predictiveBackEnabled) {
                    val enabled = appearance.predictiveBackEnabled
                    if (enabled != appliedPredictiveBackEnabled &&
                        PredictiveBackController.apply(applicationInfo, enabled)
                    ) {
                        appliedPredictiveBackEnabled = enabled
                        recreateWithoutTransition()
                    }
                }

                AgentAppTheme(
                    appearance = appearance,
                    applyInterfaceScale = true,
                    onResolvedDarkModeChange = ::updateSystemBars,
                ) {
                    CompositionLocalProvider(LocalUriHandler provides InAppBrowserUriHandler(this@MainActivity)) {
                        AgentAppRoot(
                            resultConversationHandoff = resultConversationHandoff,
                            onResultConversationOpened = { request, opened ->
                                request.acknowledge(opened)
                                if (resultConversationHandoff === request) {
                                    resultConversationHandoff = null
                                    AgentConversationHandoff.consume(intent)
                                }
                            },
                            browserUrl = browserUrl,
                            onBrowserOpened = {
                                browserUrl = null
                                intent.removeExtra(InAppBrowserUriHandler.EXTRA_BROWSER_URL)
                            },
                            openVoiceSettings = openVoiceSettings,
                            onVoiceSettingsOpened = { openVoiceSettings = false },
                        )
                    }
                }
            }
            contentReady = true
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have changed in system settings while this activity was paused.
        io.github.fartown.movo.agent.voice.MovoWakeWordController.refresh(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateAssistantHandoff(intent)
    }

    private fun updateAssistantHandoff(intent: Intent?) {
        if (intent?.action == AgentConversationHandoff.ACTION_OPEN) {
            browserUrl = null
            resultConversationHandoff = AgentConversationHandoff.from(intent)
            return
        }
        if (intent?.action == InAppBrowserUriHandler.ACTION_OPEN_BROWSER) {
            browserUrl = intent.getStringExtra(InAppBrowserUriHandler.EXTRA_BROWSER_URL)
                ?.let { if (it.isBlank()) "" else normalizeBrowserLink(it) }
            return
        }
        // Long press on the wake Quick Settings tile, or the tile asking for mic permission.
        if (intent?.action == TileService.ACTION_QS_TILE_PREFERENCES) {
            openVoiceSettings = true
            return
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        lastConfiguration = android.content.res.Configuration(newConfig)
    }

    /**
     * 被系统重建（而不是走 onConfigurationChanged）时，记录与上一次配置相差的项。
     * 重建会整屏黑一下；日志用于定位还缺哪些 configChanges（2026-09-26 据此补上 screenLayout：
     * 切换语言时 screenLayout 的布局方向位随之变化，冷启动后首次切换曾被重建、黑屏约 167ms）。
     */
    private fun logRelaunchConfigDiff() {
        val previous = lastConfiguration
        val current = android.content.res.Configuration(resources.configuration)
        lastConfiguration = current
        if (previous == null) return
        val diff = previous.diff(current)
        if (diff == 0) return
        io.github.fartown.movo.core.AndroidAgentLogger.info(
            "MainActivity relaunched: config diff=0x${Integer.toHexString(diff)} (${configDiffNames(diff)})",
        )
    }

    private fun updateApplicationNightMode(themeMode: AppearanceThemeMode) {
        val mode = when (themeMode) {
            AppearanceThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            AppearanceThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
            // 应用级 AUTO 清除夜间模式覆盖，恢复跟随系统。
            AppearanceThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        }
        getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
    }

    private fun updateSystemBars(isDark: Boolean) {
        val style = if (isDark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(
                scrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            )
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        window.decorView.post {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun recreateWithoutTransition() {
        overridePendingTransition(0, 0)
        recreate()
        overridePendingTransition(0, 0)
    }

    private companion object {
        /** 进程内上一次看到的 Activity 配置（Activity 重建时仍在）。 */
        var lastConfiguration: android.content.res.Configuration? = null

        fun configDiffNames(diff: Int): String {
            val names = listOf(
                android.content.pm.ActivityInfo.CONFIG_MCC to "mcc",
                android.content.pm.ActivityInfo.CONFIG_MNC to "mnc",
                android.content.pm.ActivityInfo.CONFIG_LOCALE to "locale",
                android.content.pm.ActivityInfo.CONFIG_TOUCHSCREEN to "touchscreen",
                android.content.pm.ActivityInfo.CONFIG_KEYBOARD to "keyboard",
                android.content.pm.ActivityInfo.CONFIG_KEYBOARD_HIDDEN to "keyboardHidden",
                android.content.pm.ActivityInfo.CONFIG_NAVIGATION to "navigation",
                android.content.pm.ActivityInfo.CONFIG_ORIENTATION to "orientation",
                android.content.pm.ActivityInfo.CONFIG_SCREEN_LAYOUT to "screenLayout",
                android.content.pm.ActivityInfo.CONFIG_UI_MODE to "uiMode",
                android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE to "screenSize",
                android.content.pm.ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE to "smallestScreenSize",
                android.content.pm.ActivityInfo.CONFIG_DENSITY to "density",
                android.content.pm.ActivityInfo.CONFIG_LAYOUT_DIRECTION to "layoutDirection",
                android.content.pm.ActivityInfo.CONFIG_COLOR_MODE to "colorMode",
                android.content.pm.ActivityInfo.CONFIG_GRAMMATICAL_GENDER to "grammaticalGender",
                android.content.pm.ActivityInfo.CONFIG_FONT_WEIGHT_ADJUSTMENT to "fontWeightAdjustment",
                android.content.pm.ActivityInfo.CONFIG_FONT_SCALE to "fontScale",
            )
            val known = names.filter { (flag, _) -> diff and flag != 0 }.joinToString("|") { it.second }
            val rest = diff and names.fold(0) { acc, (flag, _) -> acc or flag }.inv()
            return if (rest != 0) "$known|other=0x${Integer.toHexString(rest)}" else known
        }
    }
}
