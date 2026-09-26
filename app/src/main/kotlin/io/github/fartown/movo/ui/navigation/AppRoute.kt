package io.github.fartown.movo.ui.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data object Home : AppRoute

    @Serializable
    data object Chat : AppRoute

    @Serializable
    data object Browser : AppRoute

    @Serializable
    data object Terminal : AppRoute

    @Serializable
    data object Tools : AppRoute

    @Serializable
    data object Skills : AppRoute

    @Serializable
    data object Characters : AppRoute

    @Serializable
    data class CharacterDetail(val characterId: String) : AppRoute

    @Serializable
    data class CharacterEditor(val characterId: String? = null) : AppRoute

    @Serializable
    data object CharacterPersona : AppRoute

    @Serializable
    data class CharacterMemory(val characterId: String) : AppRoute

    @Serializable
    data object Permissions : AppRoute

    @Serializable
    data object SystemEnhance : AppRoute

    @Serializable
    data object Settings : AppRoute

    /** 设置 · 工具：工具开关与环境（[Tools] 是只读的工具能力目录）。 */
    @Serializable
    data object ToolSettings : AppRoute

    /** 设置 · 系统助手：数字助理、电源键、厂商助手、Gemini、一圈即搜。 */
    @Serializable
    data object SystemAssistant : AppRoute

    /** 执行详情（规范 8.8）：当前会话里 [workKey] 对应的那张执行卡的完整记录。 */
    @Serializable
    data class RunDetail(val workKey: String) : AppRoute

    @Serializable
    data object VoiceSettings : AppRoute

    @Serializable
    data object Diagnostics : AppRoute

    /** 运行日志 · 任务详情；[runId] 是诊断任务号（R12）。 */
    @Serializable
    data class DiagnosticsRun(val runId: String) : AppRoute

    @Serializable
    data object DiagnosticsSystem : AppRoute

    @Serializable
    data object AppearanceSettings : AppRoute

    @Serializable
    data object DataBackup : AppRoute

    @Serializable
    data object Memory : AppRoute

    @Serializable
    data object LinuxEnvironment : AppRoute

    @Serializable
    data object SharedFolders : AppRoute

    @Serializable
    data object Workspace : AppRoute

    @Serializable
    data class LinuxFiles(val distribution: String) : AppRoute

    @Serializable
    data object ModelProviders : AppRoute

    @Serializable
    data object McpServers : AppRoute

    @Serializable
    data class McpServerDetail(val serverId: String) : AppRoute

    @Serializable
    data class ModelProviderDetail(val providerId: String) : AppRoute

    @Serializable
    data class ModelProviderNew(val providerType: NewProviderType) : AppRoute
}

@Serializable
enum class NewProviderType { OpenAiCompatible, Anthropic }
