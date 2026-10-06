package io.github.fartown.movo.flavor

import android.content.Context
import io.github.fartown.movo.MovoApp
import io.github.fartown.movo.agent.tools.ToolProviderInputs
import io.github.fartown.movo.agent.tools.core.ApprovalMode
import io.github.fartown.movo.agent.tools.conversation.ConversationToolProvider
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.memory.MemoryToolProvider
import io.github.fartown.movo.agent.tools.ui.UiToolProvider
import io.github.fartown.movo.agent.voice.session.VoiceConversationHost
import io.github.fartown.movo.platform.AppSurfaces
import io.github.fartown.movo.platform.Flavor
import io.github.fartown.movo.platform.KeyInterceptor
import io.github.fartown.movo.platform.PromptProfile
import io.github.fartown.movo.platform.RunSurfaceRenderer
import io.github.fartown.movo.tv.TvAppSurfaces
import io.github.fartown.movo.tv.TvPromptProfile
import io.github.fartown.movo.tv.TvRunSurfaceRenderer
import io.github.fartown.movo.tv.TvDeviceToolProvider
import io.github.fartown.movo.tv.TvMediaToolProvider
import io.github.fartown.movo.ui.app.AgentAppSession

/**
 * 电视版装配（实施方案 §5.5）。P1 只有工程骨架：首页与运行中浮层是占位，
 * 语音面板与返回键取消在 P2 按 Figma 定稿实现（§5.8、§5.9）。
 */
internal object FlavorModule : Flavor {
    override val surfaces: AppSurfaces = TvAppSurfaces
    override val runSurface: RunSurfaceRenderer = TvRunSurfaceRenderer
    override val prompt: PromptProfile = TvPromptProfile

    /** 免审：需要确认的动作直接执行。 */
    override val approvalMode: ApprovalMode = ApprovalMode.SKIP

    /** 电视不弹提问 / 审批卡：没有 ask_user，模型在回答里直接问，用户下一句接着说。 */
    override val interactionCards: Boolean = false

    /** P2 接入：本轮进行中按返回取消（§5.8）。 */
    override val keyInterceptor: KeyInterceptor = io.github.fartown.movo.tv.TvBackHandler

    override val screenCapture: io.github.fartown.movo.platform.DeviceScreenCapture =
        io.github.fartown.movo.tv.TvAssistantScreenCapture

    override fun startExecutionService(context: Context, intent: android.content.Intent) {
        // TCL Android 9 silently rejects startForegroundService from its bound assistant
        // (default_borbid), even with APP_AUTO_START allowed, then kills it for an FGS ANR.
        // A system-bound accessibility service can start an ordinary service; onCreate
        // still attempts foreground promotion. The bound-service lifetime avoids that FGS
        // timeout when the OEM rejects promotion; task leases still stop it when work ends.
        if (android.os.Build.VERSION.SDK_INT == 28 &&
            android.os.Build.MANUFACTURER.startsWith("TCL", ignoreCase = true) &&
            io.github.fartown.movo.agent.accessibility.AgentAccessibilityService.current() != null) {
            checkNotNull(context.startService(intent))
        } else super.startExecutionService(context, intent)
    }

    /** 电视回答完 10 秒没人说话就收起：会话期间节目是暂停的（连续对话规则，见实施方案）。 */
    override val voiceIdleTimeoutMs: Long = 10_000L

    /** 打开了其他 App（含直达视频搜索）后，回答念完就结束会话：不留在后台把节目声音当成你在说话。 */
    override val voiceHandoffTools: Set<String> = setOf("app_open", "video_search")

    /** 「看全文」在本地打开阅读页，不交给模型。 */
    override val voiceLocalCommands: Set<String> = setOf("看全文", "看看全文", "打开全文", "全文", "放大看看", "看完整的")

    override fun onVoiceLocalCommand(context: Context, command: String): String? =
        if (io.github.fartown.movo.tv.TvAppSurfaces.openReading(context)) "已打开全文" else null

    override fun voiceHost(context: Context): VoiceConversationHost = AgentAppSession.get(context)

    override fun voiceInput(context: Context, onError: (String) -> Unit) =
        if (io.github.fartown.movo.tv.TclPcmInput.supported(context)) {
            io.github.fartown.movo.tv.TclPcmInput(context, onError)
        } else null

    /**
     * 白名单（§5.6）：手机新增的 Provider 不会自动进电视。个人数据、文件、终端、浏览器、技能、MCP 暂不装配。
     */
    override fun toolProviders(inputs: ToolProviderInputs): List<ToolProvider> {
        val services = inputs.services
        val context = services.context
        val rootAvailable = services.rootAvailable
        return listOf(
            TvDeviceToolProvider(services),
            TvMediaToolProvider(services),
            UiToolProvider(context, services.logger, rootAvailable, includeTouchscreenTools = false),
            MemoryToolProvider(context, inputs.characterId),
            ConversationToolProvider(inputs.conversationLoader),
        ).also { providers ->
            io.github.fartown.movo.diagnostics.MemoryDiagnostics.record("tv.tools", "registered",
                fields = mapOf("names" to providers.flatMap { it.tools }.joinToString(",") { it.name }))
        }
    }

    /** 电视不启用唤醒词、预测性返回与 Xposed。 */
    override val initializers: List<(MovoApp) -> Unit> = listOf({
        io.github.fartown.movo.tv.TvAssistantPermission.restoreIfEnabled(it)
        io.github.fartown.movo.tv.TvBackHandler.init(it)
        io.github.fartown.movo.tv.TvVoicePanel.init(it)
        io.github.fartown.movo.tv.TclWakeService.restoreIfEnabled(it)
        io.github.fartown.movo.agent.accessibility.AgentAccessibilityService.addInstanceListener {
            io.github.fartown.movo.tv.TclWakeService.restoreIfEnabled(it)
        }
    })
}
