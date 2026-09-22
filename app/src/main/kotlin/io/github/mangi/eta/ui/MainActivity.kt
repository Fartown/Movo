package io.github.mangi.eta.ui

import android.app.UiModeManager
import android.content.Intent
import android.os.Bundle
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
import io.github.mangi.eta.agent.runtime.AgentConversationHandoff
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppRoot
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.app.PredictiveBackController
import io.github.mangi.eta.ui.app.installStartupSplash
import io.github.mangi.eta.ui.markdown.InAppBrowserUriHandler
import io.github.mangi.eta.ui.markdown.normalizeBrowserLink
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var assistantConversationKey by mutableStateOf<String?>(null)
    private var resultConversationHandoff by mutableStateOf<AgentConversationHandoff.Request?>(null)
    private var browserUrl by mutableStateOf<String?>(null)
    private var appliedPredictiveBackEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var contentReady = false
        installStartupSplash { contentReady }
        enableEdgeToEdge()
        updateAssistantHandoff(intent)
        lifecycleScope.launch {
            val initialAppearance = AppearanceSettingsRepository.settings()
            appliedPredictiveBackEnabled = initialAppearance.predictiveBackEnabled
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow()
                    .collectAsState(initial = initialAppearance)

                LaunchedEffect(appearance.themeMode) {
                    updateApplicationNightMode(appearance.themeMode)
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
                            assistantConversationKey = assistantConversationKey,
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
                            onAssistantConversationOpened = { opened ->
                                assistantConversationKey = null
                                intent.removeExtra(EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY)
                                if (opened) {
                                    EtaAssistantOverlayService.notifyHandoffReady(this@MainActivity)
                                }
                            },
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
        io.github.mangi.eta.agent.voice.EtaWakeWordController.refresh(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateAssistantHandoff(intent)
    }

    private fun updateAssistantHandoff(intent: Intent?) {
        if (intent?.action == AgentConversationHandoff.ACTION_OPEN) {
            assistantConversationKey = null
            browserUrl = null
            resultConversationHandoff = AgentConversationHandoff.from(intent)
            return
        }
        if (intent?.action == InAppBrowserUriHandler.ACTION_OPEN_BROWSER) {
            browserUrl = intent.getStringExtra(InAppBrowserUriHandler.EXTRA_BROWSER_URL)
                ?.let { if (it.isBlank()) "" else normalizeBrowserLink(it) }
            return
        }
        if (intent?.action != EtaAssistantOverlayService.ACTION_OPEN_CONVERSATION) return
        assistantConversationKey = intent.getStringExtra(
            EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY,
        )?.takeIf(String::isNotBlank)
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
}
