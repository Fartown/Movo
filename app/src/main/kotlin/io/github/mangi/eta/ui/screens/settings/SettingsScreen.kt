package io.github.mangi.eta.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.LocaleList
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.LanguageSettingsRepository
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.repository.VoiceSettingsRepository
import io.github.mangi.eta.data.model.VoiceWakeSettings
import io.github.mangi.eta.ui.components.modelOrProviderBrandLogoRes
import io.github.mangi.eta.ui.components.movo.CardTitle
import io.github.mangi.eta.ui.components.movo.MovoCard
import io.github.mangi.eta.ui.components.movo.MovoChoiceDialog
import io.github.mangi.eta.ui.components.movo.MovoListPage
import io.github.mangi.eta.ui.components.movo.MovoPillButton
import io.github.mangi.eta.ui.components.movo.RowLeading
import io.github.mangi.eta.ui.components.movo.RowTrailing
import io.github.mangi.eta.ui.components.movo.SettingsRow
import io.github.mangi.eta.ui.model.PermissionHealthUiState
import io.github.mangi.eta.ui.model.PermissionStatusUi
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoIcon
import io.github.mangi.eta.ui.theme.MovoIcons
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoSpacing
import io.github.mangi.eta.ui.theme.MovoTypography
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

/** 设置页上 Kimi Web 行需要的状态与操作（原首页顶栏 ⋮ 菜单里的入口，2026-09-25 移到这里）。 */
internal data class KimiWebEntry(
    val label: String,
    val canStop: Boolean,
    val onLaunch: () -> Unit,
    val onStop: () -> Unit,
    val onRefresh: () -> Unit,
)

/**
 * 设置主页（规范 8.7，Figma「18 · 设置」）：5 组卡片，卡内标题；主页面不写说明，当前状态写在右侧值里。
 * 所有配置的唯一入口：侧边栏只保留一行「设置」。
 */
@Composable
internal fun SettingsScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    permissionHealth: PermissionHealthUiState,
    onRefreshPermissions: () -> Unit,
    kimiWeb: KimiWebEntry,
) {
    val context = LocalContext.current
    val agentPrefs = remember { Prefs.localAgentPreferences() }

    // 右侧值：模型、唤醒词、记忆条数、Skills / MCP 数量、语言。回到前台时刷新。
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val selectedProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    val selectedModelId by RuntimeConfigRepository.selectedModelIdFlow().collectAsState(initial = null)
    val selectedProvider = remember(providers, selectedProviderId) { providers.find { it.id == selectedProviderId } }
    val selectedModel = remember(selectedProvider, selectedModelId) {
        selectedProvider?.models?.find { it.id == selectedModelId }
    }
    val wake by remember { VoiceSettingsRepository.wakeSettingsFlow() }.collectAsState(initial = VoiceWakeSettings())
    val mcpServers by remember { McpServerRepository.serversFlow() }.collectAsState(initial = null)
    var memoryValue by remember { mutableStateOf<String?>(null) }
    var skillsCount by remember { mutableStateOf<Int?>(null) }
    var resumeTick by remember { mutableIntStateOf(0) }
    OnResumeEffect {
        resumeTick++
        onRefreshPermissions()
        kimiWeb.onRefresh()
    }
    val memoryOff = stringResource(R.string.movo_settings_memory_off)
    LaunchedEffect(resumeTick) {
        memoryValue = withContext(Dispatchers.IO) {
            runCatching {
                if (!AgentMemoryRepository.isEnabled()) {
                    memoryOff
                } else {
                    context.getString(R.string.movo_settings_memory_count, memoryEntryCount(AgentMemoryRepository.snapshot().content))
                }
            }.getOrNull()
        }
        skillsCount = withContext(Dispatchers.IO) {
            runCatching {
                SkillRuntime.createIndexService(context.applicationContext).listSkillsForManagement().count { it.enabled }
            }.getOrNull()
        }
    }
    val toolsEnabled = rememberEnabledCount(agentPrefs, ToolSwitchKeys)
    val attentionLabel = permissionHealth.attentionLabel(
        overlayOff = stringResource(R.string.movo_permission_overlay_off),
        accessibilityOff = stringResource(R.string.movo_permission_accessibility_off),
    )

    val configuration = LocalConfiguration.current
    // LocaleManager 取不到时（非常规环境）不显示语言行，其余照常。
    val languageRepository = remember(context.applicationContext) {
        runCatching { LanguageSettingsRepository(context.applicationContext) }.getOrNull()
    }
    var selectedLocale by remember(languageRepository, configuration) { mutableStateOf(languageRepository?.selectedLocale()) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    val locales = languageRepository?.supportedLocales.orEmpty()
    val languageLabels = listOf(stringResource(R.string.settings_language_system)) + locales.map { it.getDisplayName(it) }
    val languageIndex = selectedLocale?.let { selected ->
        locales.indexOfFirst { LocaleList.matchesLanguageAndScript(it, selected) } + 1
    } ?: 0

    MovoListPage(title = stringResource(R.string.ui_set_up_7debf9), onBack = onBack) {
        item(key = "model_chat") {
            MovoCard {
                CardTitle(stringResource(R.string.movo_settings_group_model_chat))
                SettingsRow(
                    title = stringResource(R.string.movo_settings_model),
                    leading = RowLeading.Custom {
                        BrandLogo(
                            modelId = selectedModel?.id,
                            sourceType = selectedProvider?.let { ProviderSourceRegistry.resolve(it) },
                        )
                    },
                    trailing = RowTrailing.Arrow(
                        selectedModel?.displayName
                            ?: selectedProvider?.let { stringResource(R.string.settings_model_not_selected) }
                            ?: stringResource(R.string.movo_settings_model_none),
                    ),
                    onClick = { onNavigate(AppRoute.ModelProviders) },
                )
                SettingsRow(
                    title = stringResource(R.string.voice_settings_title),
                    leading = RowLeading.Icon(MovoIcons.Mic),
                    trailing = RowTrailing.Arrow(if (wake.wakeEnabled) wake.effectivePhrase() else null),
                    onClick = { onNavigate(AppRoute.VoiceSettings) },
                )
                PrefSwitchRow(
                    prefs = agentPrefs,
                    key = Prefs.Keys.AGENT_THINKING_ENABLED,
                    title = stringResource(R.string.movo_settings_thinking_default),
                    leading = RowLeading.Icon(MovoIcons.Atom),
                )
                SettingsRow(
                    title = stringResource(R.string.ui_memory_b55ff5),
                    leading = RowLeading.Icon(MovoIcons.BookOpen),
                    trailing = RowTrailing.Arrow(memoryValue),
                    showDivider = false,
                    onClick = { onNavigate(AppRoute.Memory) },
                )
            }
        }
        item(key = "capabilities") {
            MovoCard {
                CardTitle(stringResource(R.string.movo_settings_group_capabilities))
                SettingsRow(
                    title = stringResource(R.string.movo_settings_tools),
                    leading = RowLeading.Icon(MovoIcons.Wrench),
                    trailing = RowTrailing.Arrow(stringResource(R.string.movo_settings_tools_enabled, toolsEnabled)),
                    onClick = { onNavigate(AppRoute.ToolSettings) },
                )
                SettingsRow(
                    title = stringResource(R.string.route_skills),
                    leading = RowLeading.Icon(MovoIcons.Puzzle),
                    trailing = RowTrailing.Arrow(skillsCount?.let { stringResource(R.string.movo_settings_count_items, it) }),
                    onClick = { onNavigate(AppRoute.Skills) },
                )
                SettingsRow(
                    title = stringResource(R.string.route_mcp_servers),
                    leading = RowLeading.Icon(MovoIcons.Plug),
                    trailing = RowTrailing.Arrow(
                        mcpServers?.let { servers ->
                            if (servers.isEmpty()) {
                                stringResource(R.string.movo_settings_mcp_none)
                            } else {
                                stringResource(R.string.movo_settings_count_items, servers.size)
                            }
                        },
                    ),
                    onClick = { onNavigate(AppRoute.McpServers) },
                )
                SettingsRow(
                    title = stringResource(R.string.movo_settings_characters),
                    leading = RowLeading.Icon(MovoIcons.VenetianMask),
                    onClick = { onNavigate(AppRoute.Characters) },
                )
                SettingsRow(
                    title = stringResource(R.string.movo_settings_terminal),
                    leading = RowLeading.Icon(MovoIcons.Terminal),
                    onClick = { onNavigate(AppRoute.Terminal) },
                )
                SettingsRow(
                    title = stringResource(R.string.movo_settings_browser),
                    leading = RowLeading.Icon(MovoIcons.Globe),
                    onClick = { onNavigate(AppRoute.Browser) },
                )
                SettingsRow(
                    title = stringResource(R.string.movo_settings_kimi_web),
                    leading = RowLeading.Custom {
                        androidx.compose.foundation.Image(
                            painter = painterResource(R.drawable.ic_kimi_code),
                            contentDescription = null,
                            modifier = Modifier.size(MovoSize.iconMedium),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MovoColors.textPrimary),
                        )
                    },
                    trailing = if (kimiWeb.canStop) {
                        RowTrailing.Custom {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(kimiWeb.label, style = MovoTypography.labelRegular, color = MovoColors.textSecondary, maxLines = 1)
                                Spacer(Modifier.width(MovoSpacing.sm))
                                MovoPillButton(label = stringResource(R.string.movo_settings_stop), onClick = kimiWeb.onStop)
                            }
                        }
                    } else {
                        RowTrailing.Arrow(kimiWeb.label)
                    },
                    showDivider = false,
                    onClick = kimiWeb.onLaunch,
                )
            }
        }
        item(key = "system") {
            MovoCard {
                CardTitle(stringResource(R.string.movo_settings_group_system))
                SettingsRow(
                    title = stringResource(R.string.ui_permissions_560165),
                    leading = RowLeading.Icon(MovoIcons.Shield),
                    trailing = RowTrailing.Arrow(attentionLabel),
                    attention = attentionLabel != null,
                    onClick = { onNavigate(AppRoute.Permissions) },
                )
                SettingsRow(
                    title = stringResource(R.string.movo_settings_system_assistant),
                    leading = RowLeading.Icon(MovoIcons.Power),
                    onClick = { onNavigate(AppRoute.SystemAssistant) },
                )
                SettingsRow(
                    title = stringResource(R.string.movo_settings_root),
                    leading = RowLeading.Icon(MovoIcons.Zap),
                    showDivider = false,
                    onClick = { onNavigate(AppRoute.SystemEnhance) },
                )
            }
        }
        item(key = "general") {
            MovoCard {
                CardTitle(stringResource(R.string.settings_general))
                SettingsRow(
                    title = stringResource(R.string.movo_settings_appearance),
                    leading = RowLeading.Icon(MovoIcons.Palette),
                    trailing = RowTrailing.Arrow(stringResource(R.string.movo_settings_appearance_light)),
                    onClick = { onNavigate(AppRoute.AppearanceSettings) },
                )
                if (languageRepository != null) {
                    SettingsRow(
                        title = stringResource(R.string.settings_language),
                        leading = RowLeading.Icon(MovoIcons.Languages),
                        trailing = RowTrailing.Arrow(languageLabels.getOrNull(languageIndex)),
                        onClick = { showLanguageDialog = true },
                    )
                }
                SettingsRow(
                    title = stringResource(R.string.data_backup_title),
                    leading = RowLeading.Icon(MovoIcons.Archive),
                    onClick = { onNavigate(AppRoute.DataBackup) },
                )
                SettingsRow(
                    title = stringResource(R.string.movo_settings_run_log),
                    leading = RowLeading.Icon(MovoIcons.FileText),
                    showDivider = false,
                    onClick = { onNavigate(AppRoute.Diagnostics) },
                )
            }
        }
        item(key = "about") {
            MovoCard {
                CardTitle(stringResource(R.string.ui_about_bed172))
                SettingsRow(
                    title = stringResource(R.string.movo_settings_version),
                    leading = RowLeading.Icon(MovoIcons.Info),
                    trailing = RowTrailing.Value(BuildConfig.VERSION_NAME),
                )
                SettingsRow(
                    title = stringResource(R.string.ui_source_code_740296),
                    leading = RowLeading.Icon(MovoIcons.CodeXml),
                    trailing = RowTrailing.External("GitHub"),
                    showDivider = false,
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_CODE_URL)))
                        }
                    },
                )
            }
        }
    }

    MovoChoiceDialog(
        show = showLanguageDialog,
        title = stringResource(R.string.settings_language),
        options = languageLabels,
        selectedIndex = languageIndex,
        onSelect = { index ->
            if (languageRepository != null && index in languageLabels.indices) {
                languageRepository.selectLocale(if (index == 0) null else locales[index - 1])
                selectedLocale = languageRepository.selectedLocale()
            }
        },
        onDismissRequest = { showLanguageDialog = false },
    )
}

private const val SOURCE_CODE_URL = "https://github.com/Mangi-11/Eta"

/** 模型行图标位：20 品牌 Logo（圆形裁切 + 0.5 描边）；没有 Logo 时用模型图标。 */
@Composable
private fun BrandLogo(modelId: String?, sourceType: String?) {
    val logo = modelOrProviderBrandLogoRes(modelId, sourceType)
    if (logo != null) {
        Image(
            painter = painterResource(logo),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(MovoSize.iconMedium)
                .clip(CircleShape)
                .border(MovoSize.hairline, MovoColors.borderHairline, CircleShape),
        )
    } else {
        MovoIcon(MovoIcons.Cpu, contentDescription = null)
    }
}

/**
 * 「权限」行的待处理提示：只看 Movo 核心能力依赖的悬浮窗与无障碍（其余权限按需开启，不在主页面报警）。
 */
internal fun PermissionHealthUiState.attentionLabel(overlayOff: String, accessibilityOff: String): String? {
    fun missing(id: String) = items.firstOrNull { it.id == id }?.status == PermissionStatusUi.Missing
    return when {
        missing("overlay") -> overlayOff
        missing("accessibility") -> accessibilityOff
        else -> null
    }
}

/** MEMORY.md 里的条目数：列表项（- / * / + / 1.）优先，没有列表时数非空、非标题行。 */
internal fun memoryEntryCount(content: String): Int {
    val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val bullets = lines.count { BULLET.containsMatchIn(it) }
    return if (bullets > 0) bullets else lines.count { !it.startsWith("#") }
}

private val BULLET = Regex("^([-*+]|\\d+[.)])\\s+")
