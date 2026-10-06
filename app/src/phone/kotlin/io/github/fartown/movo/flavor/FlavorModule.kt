package io.github.fartown.movo.flavor

import android.content.Context
import io.github.fartown.movo.MovoApp
import io.github.fartown.movo.agent.monitor.MonitorToolProvider
import io.github.fartown.movo.agent.tools.ToolProviderInputs
import io.github.fartown.movo.agent.tools.browser.BrowserToolProvider
import io.github.fartown.movo.agent.tools.clockmedia.ClockMediaToolProvider
import io.github.fartown.movo.agent.tools.conversation.ConversationToolProvider
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.device.DeviceToolProvider
import io.github.fartown.movo.agent.tools.file.FileToolProvider
import io.github.fartown.movo.agent.tools.mcp.McpToolProvider
import io.github.fartown.movo.agent.tools.memory.MemoryToolProvider
import io.github.fartown.movo.agent.tools.personal.PersonalToolProvider
import io.github.fartown.movo.agent.tools.skill.SkillToolProvider
import io.github.fartown.movo.agent.tools.terminal.TerminalToolProvider
import io.github.fartown.movo.agent.tools.ui.UiToolProvider
import io.github.fartown.movo.agent.voice.MovoWakeWordController
import io.github.fartown.movo.agent.voice.session.VoiceConversationHost
import io.github.fartown.movo.data.repository.AppearanceSettingsRepository
import io.github.fartown.movo.platform.AppSurfaces
import io.github.fartown.movo.platform.Flavor
import io.github.fartown.movo.platform.KeyInterceptor
import io.github.fartown.movo.platform.PromptProfile
import io.github.fartown.movo.platform.RunSurfaceRenderer
import io.github.fartown.movo.ui.app.AgentAppSession
import io.github.fartown.movo.ui.app.PredictiveBackController
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** 手机版装配：全部返回现有实现，手机行为不变。 */
internal object FlavorModule : Flavor {
    override val surfaces: AppSurfaces = PhoneAppSurfaces
    override val runSurface: RunSurfaceRenderer = PhoneRunSurfaceRenderer
    override val prompt: PromptProfile = PhonePromptProfile

    override val interactionCards: Boolean = true

    /** 手机的无障碍配置不请求按键过滤。 */
    override val keyInterceptor: KeyInterceptor? = null

    override fun voiceHost(context: Context): VoiceConversationHost = AgentAppSession.get(context)

    override fun toolProviders(inputs: ToolProviderInputs): List<ToolProvider> {
        val services = inputs.services
        val context = services.context
        val logger = services.logger
        val root = services.root()
        val rootAvailable = services.rootAvailable
        return listOf(
            DeviceToolProvider(context, root, rootAvailable),
            ClockMediaToolProvider(context, logger, root, rootAvailable),
            UiToolProvider(context, logger, rootAvailable, screenshotExcludedPackages = services.screenshotExcludedPackages),
            PersonalToolProvider(context, root, rootAvailable),
            FileToolProvider(context, root, rootAvailable),
            TerminalToolProvider(logger),
            BrowserToolProvider(context),
            MemoryToolProvider(context, inputs.characterId),
            SkillToolProvider(context),
            ConversationToolProvider(inputs.conversationLoader),
            McpToolProvider(inputs.mcpCatalog),
            MonitorToolProvider(context, isRoleplay = { inputs.characterId() != null }),
        )
    }

    override val initializers: List<(MovoApp) -> Unit> = listOf(
        { app -> MovoWakeWordController.startObserving(app) },
        { app ->
            val predictiveBackEnabled = runBlocking(Dispatchers.IO) {
                AppearanceSettingsRepository.settings().predictiveBackEnabled
            }
            PredictiveBackController.apply(app.applicationInfo, predictiveBackEnabled)
        },
        { app -> XposedServiceHelper.registerListener(app) },
    )
}
