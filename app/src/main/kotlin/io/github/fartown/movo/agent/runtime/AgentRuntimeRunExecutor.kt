package io.github.fartown.movo.agent.runtime

import io.github.fartown.movo.agent.tools.core.UserInteraction
import io.github.fartown.movo.flavor.FlavorModule
import android.content.Context
import io.github.fartown.movo.data.db.MovoDatabase
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.model.AgentModelExecutionException
import io.github.fartown.movo.agent.model.AgentModelFailure
import io.github.fartown.movo.agent.memory.AgentMemoryContext
import io.github.fartown.movo.agent.memory.AgentMemoryContextBuilder
import io.github.fartown.movo.agent.roleplay.RoleplayRunContext
import io.github.fartown.movo.agent.skill.SkillCompatibilityChecker
import io.github.fartown.movo.agent.skill.SkillContext
import io.github.fartown.movo.agent.skill.SkillRuntime
import io.github.fartown.movo.agent.tool.AgentToolCapabilities
import io.github.fartown.movo.agent.tools.AgentToolSubsystem
import io.github.fartown.movo.agent.tools.mcp.McpCatalog
import io.github.fartown.movo.agent.tools.mcp.McpCatalogLoader
import io.github.fartown.movo.agent.tools.GuiReadinessGuard
import io.github.fartown.movo.agent.tools.ToolServices
import io.github.fartown.movo.agent.tools.toToolEnvironment
import io.github.fartown.movo.agent.tools.core.MemoryScope
import io.github.fartown.movo.agent.tools.core.ModelInput
import io.github.fartown.movo.agent.tools.core.ToolSwitches
import io.github.fartown.movo.agent.tools.interaction.AgentInteractionBroker
import io.github.fartown.movo.agent.tools.interaction.AgentInteractionRegistry
import io.github.fartown.movo.agent.tools.interaction.BrokeredUserInteraction
import io.github.fartown.movo.agent.voice.MovoAssistantVoiceService
import io.github.fartown.movo.core.AndroidAgentLogger
import io.github.fartown.movo.core.safeLogType
import io.github.fartown.movo.data.repository.AgentMemoryRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

/**
 * 单次 Runtime run 的阻塞执行器。
 *
 * 它只拥有模型、工具和终态提交，不持有 Service、Messenger、Compose 或 WindowManager 状态。
 * 所有外部副作用都通过窄回调交回宿主。
 */
internal class AgentRuntimeRunExecutor(
    context: Context,
    private val currentPermissions: () -> AgentRuntimePolicy.Permissions,
    private val snapshotRequest: (AgentRuntimeWire.RunRequest) -> AgentRuntimeWire.RunRequest,
    private val onAcceptedEvent: (AgentEvent, EntrySurfaceGuard?) -> Unit,
    private val persistArtifacts: (
        AgentRuntimeWire.RunRequest,
        AgentRuntimeWire.RunResult,
        List<AgentEvent>,
    ) -> Unit,
) {
    data class Outcome(
        val result: AgentRuntimeWire.RunResult,
        val entrySurfaceGuard: EntrySurfaceGuard?,
        val completedRequest: AgentRuntimeWire.RunRequest? = null,
        val response: AgentModelClient.ModelResponse.Text? = null,
        val shouldUpdateHost: Boolean,
    )

    private val appContext = context.applicationContext

    fun execute(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
    ): Outcome = io.github.fartown.movo.diagnostics.MemoryDiagnostics.withRun(
        onStart = { run ->
            io.github.fartown.movo.diagnostics.runlog.RunLog.open(
                run, io.github.fartown.movo.diagnostics.runlog.RunLogRecorder.startFields(request),
            )
        },
    ) {
        executeTracked(session, request)
    }

    private fun executeTracked(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
    ): Outcome {
        io.github.fartown.movo.diagnostics.MemoryDiagnostics.bindRun(
            request.runId,
            request.handoff
                ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
                ?.let { runCatching { AgentUiHandoffPayload.from(it.payload) }.getOrNull() }
                ?.conversationId
                ?.takeIf { it.isNotBlank() },
        )
        val runController = session.controller
        val archivedEvents = mutableListOf<AgentEvent>()
        var entrySurfaceGuard: EntrySurfaceGuard? = null
        var toolExecutor: AutoCloseable? = null
        var toolsBinding: AgentRunController.ResourceBinding? = null
        var response: AgentModelClient.ModelResponse.Text? = null
        var cancelled = false
        // App 发起的一轮：这一轮产生的模型消息按条追加到对话的进行中记录（交给对话存储的写线程，不挡执行）。
        var runLogConversation: String? = null
        var publishedTranscript = emptyList<AgentModelClient.ConversationMessage>()
        val timing = AgentRunTiming(AndroidAgentLogger)

        val result = try {
            // 登记“这一轮在跑”（一行）：进程被杀后据此按中断处理。
            if (AgentRunCheckpointStore.start(appContext, request)) {
                runLogConversation = request.handoff?.let { runCatching { AgentUiHandoffPayload.from(it.payload).conversationId }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }
            }
            entrySurfaceGuard = EntrySurfaceGuard.from(
                handoff = request.handoff,
                logger = AndroidAgentLogger,
                movoVoiceSurfaceDismissal = {
                    MovoAssistantVoiceService.dismissForForegroundOperation(appContext)
                },
            )
            val skillIndexService = SkillRuntime.createIndexService(appContext)
            val skillContext = SkillContext(
                installedSkills = skillIndexService.listInstalledSkills()
                    .filter { SkillCompatibilityChecker.evaluate(it).available },
            )
            val memoryEnabled = runBlocking { AgentMemoryRepository.isEnabled() }
            val uiPayload = request.handoff
                ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
                ?.let { AgentUiHandoffPayload.from(it.payload) }
            val conversationId = uiPayload?.conversationId
                ?.takeIf { it.isNotBlank() }
            val roleplayContext = conversationId?.let { id ->
                runBlocking { RoleplayRunContext.resolve(appContext, id, request.config.contextWindow, memoryEnabled) }
            }
            if (request.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
                require(roleplayContext != null) { "只有角色会话可以改写角色回复" }
                val target = request.rewriteTargetMessageId?.takeIf { it.isNotBlank() && it.length <= 256 }
                    ?: throw IllegalArgumentException("缺少有效的角色回复目标")
                require(runBlocking {
                    MovoDatabase.get(appContext).conversationDao().hasAssistantMessage(conversationId, target)
                }) { "角色回复目标不存在或不属于当前会话" }
            }
            val memoryContext = if (memoryEnabled) {
                runCatching {
                    AgentMemoryContextBuilder.build(
                        snapshot = AgentMemoryRepository.snapshot(),
                        contextWindow = request.config.contextWindow,
                    )
                }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_context_failed") {
                        "Agent memory context unavailable: type=${throwable.safeLogType()}"
                    }
                    AgentMemoryContextBuilder.empty(request.config.contextWindow)
                }
            } else {
                AgentMemoryContext.DISABLED
            }
            timing.preparationFinished(skillContext.installedSkills.size)
            // 类型化工具子系统：目录、系统提示分节、执行、审批、投影统一出口。提问/审批经交互通道（broker）
            // 投为 AgentEvent.InteractionRequested，界面作答经 wire 消息 19（或跨应用悬浮卡本进程）回传。
            val interactionBroker = AgentInteractionBroker()
            val dispatchInteractionEvent: (AgentEvent) -> Unit = { ev ->
                runCatching {
                    acceptEvent(session, ev, archivedEvents, entrySurfaceGuard)
                }
            }
            AgentInteractionRegistry.register(request.runId, interactionBroker)
            // run 开始快照一次 MCP 目录（用户已添加可用 MCP 时暴露 mcp_* 工具；为空则不暴露）。
            val mcpCatalog = runBlocking {
                runCatching { McpCatalogLoader.load() }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_mcp_catalog_failed") {
                        "MCP catalog unavailable: type=${throwable.safeLogType()}"
                    }
                    McpCatalog.EMPTY
                }
            }
            // 「设置 → 工具」的开关和记忆开关在运行中现读，和任务开始时的配置取与：中途关掉的，下一次调用就拦下
            // （重构前的行为）；中途打开的，这次任务不生效。
            val liveSwitches = { liveToolSwitches(request.config, currentPermissions()) }
            val liveMemoryScope = {
                val enabledNow = memoryEnabled &&
                    runCatching { runBlocking { AgentMemoryRepository.isEnabled() } }.getOrDefault(true)
                when {
                    !enabledNow -> MemoryScope.DISABLED
                    roleplayContext != null -> MemoryScope.CHARACTER
                    else -> MemoryScope.REAL
                }
            }
            val typedSubsystem = AgentToolSubsystem(
                services = ToolServices(
                    appContext,
                    AndroidAgentLogger,
                    request.runId,
                    // 入口面板（对话浮层、小布 / 小爱面板）正在退场时，第一张截图把它排除掉（重构前的行为）。
                    screenshotExcludedPackages = { entrySurfaceGuard?.consumeScreenshotExcludedPackages().orEmpty() },
                ),
                mcpCatalog = mcpCatalog,
                environment = {
                    AgentToolCapabilities.capture(appContext).toToolEnvironment(
                        switches = liveSwitches(),
                        linuxReady = linuxEnvironmentReady(appContext),
                        memoryScope = liveMemoryScope(),
                        conversationBound = conversationId != null,
                        conversationId = conversationId,
                        // 后台监听唤醒的一轮在屏幕关着时没人能作答：不给 ask_user，审批立即返回「无法确认」。
                        // 用户自己发起的任务照常等：亮屏后卡片出现（锁着时先出解锁提示）。
                        interactive = FlavorModule.interactionCards && userCanAnswer(request, appContext),
                        // 答复会被念出来时，提问卡片上的字不会念，语音里只会冷场：不给 ask_user，缺信息就在答复里直接问。
                        spokenReply = request.spokenReply.spoken,
                        modelInputs = setOf(ModelInput.TEXT, ModelInput.IMAGE),
                        approvalPolicy = io.github.fartown.movo.agent.tools.core.ApprovalSettings.load(appContext),
                    )
                },
                interaction = if (!FlavorModule.interactionCards) UserInteraction.NONE else BrokeredUserInteraction(
                    broker = interactionBroker,
                    emit = { prompt ->
                        dispatchInteractionEvent(
                            AgentEvent.InteractionRequested(
                                requestId = prompt.requestId,
                                kind = prompt.kind.name.lowercase(),
                                title = prompt.title,
                                detail = prompt.detail,
                                options = prompt.options,
                                allowFreeText = prompt.allowFreeText,
                                note = prompt.note,
                                reason = prompt.reason,
                            ),
                        )
                    },
                    cancelled = { runController.isCancelled },
                    onResolved = { requestId ->
                        dispatchInteractionEvent(AgentEvent.InteractionResolved(requestId))
                    },
                    availableNow = { userCanAnswer(request, appContext) },
                    afterApproved = {
                        io.github.fartown.movo.agent.overlay.InteractionCardCoordinator.awaitSettled(
                            activePackage = {
                                io.github.fartown.movo.agent.accessibility.AgentAccessibilityService.current()?.currentPackageName()
                            },
                            selfPackage = appContext.packageName,
                        )
                    },
                ),
                cancelled = { runController.isCancelled },
                characterId = { roleplayContext?.characterId },
                conversationLoader = { request.history },
                // GUI 就绪守卫：UI 工具执行前关入口窗口 + 保活无障碍。
                guards = listOf(GuiReadinessGuard(appContext) { entrySurfaceGuard }),
                refreshSwitches = { env -> env.copy(switches = liveSwitches(), memoryScope = liveMemoryScope()) },
            ).also { built ->
                toolExecutor = AutoCloseable {
                    AgentInteractionRegistry.unregister(request.runId)
                    built.close()
                }
                // 按停止时立刻关掉工具（重构前的行为）；正常结束时在 finally 里关。
                toolsBinding = runController.closeOnStop(built)
            }
            val effectiveExecutor = typedSubsystem.pipeline
            val typedCatalog: (AgentToolCapabilities) -> org.json.JSONArray = { _ -> typedSubsystem.pipeline.catalog() }
            val completedResponse = AgentModelClient.complete(
                config = request.config,
                sessionId = request.effectiveModelSessionId,
                operationId = request.runId,
                initialUserMessageId = uiPayload?.promptMessageId(request.runId) ?: "user-${request.runId}",
                initialSupplementIndex = uiPayload?.lastSupplementIndex ?: 0,
                roleplayContext = roleplayContext,
                rewriteReply = request.operation == AgentRuntimeWire.OP_REWRITE_REPLY,
                spokenReply = request.spokenReply,
                compactOnly = request.operation == AgentRuntimeWire.OP_COMPACT,
                onContextSnapshot = { snapshot ->
                    val committed = snapshot.copy(operationId = request.runId)
                    runLogConversation?.let { conversationId ->
                        io.github.fartown.movo.ui.app.ConversationRepository.get(appContext)
                            .saveRunSnapshot(conversationId, request.runId, committed.encode())
                    }
                    session.updateContext(committed)
                },
                onTranscript = { transcript ->
                    runLogConversation?.let { conversationId ->
                        val current = transcript.toList()
                        io.github.fartown.movo.ui.app.ConversationRepository.get(appContext)
                            .syncRunTranscript(conversationId, request.runId, publishedTranscript, current)
                        publishedTranscript = current
                    }
                    session.updateTranscript(transcript)
                },
                capabilitiesProvider = { AgentToolCapabilities.capture(appContext) },
                prompt = request.prompt,
                toolExecutor = effectiveExecutor,
                typedCatalog = typedCatalog,
                toolGuide = {
                    val switches = liveSwitches()
                    val liveConfig = request.config.copy(
                        deviceSensitiveReadTools = switches.sensitiveRead,
                        deviceSensitiveActionTools = switches.sensitiveAction,
                    )
                    (typedSubsystem.pipeline.promptSections().map { it.text.trim() } + switchNotes(liveConfig))
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                },
                environmentExtra = { recentAppEnvironment(appContext) },
                images = request.images,
                history = request.history,
                runController = runController,
                skillContext = skillContext,
                memoryContext = memoryContext,
            ) { event ->
                timing.accept(event)
                acceptEvent(
                    session,
                    event,
                    archivedEvents,
                    entrySurfaceGuard,
                )
            }
            response = completedResponse
            io.github.fartown.movo.diagnostics.runlog.RunLog.end("completed")
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = true,
                content = completedResponse.content,
                reasoningContent = completedResponse.reasoningContent,
                transcript = completedResponse.transcript,
                contextSnapshot = completedResponse.contextSnapshot?.copy(operationId = request.runId),
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } catch (throwable: Throwable) {
            cancelled = runController.isCancelled || throwable is AgentRunCancelledException
            io.github.fartown.movo.diagnostics.MemoryDiagnostics.record(
                "runtime", if (cancelled) "run.cancelled" else "run.failed",
                if (cancelled) io.github.fartown.movo.diagnostics.DiagnosticLevel.INFO else io.github.fartown.movo.diagnostics.DiagnosticLevel.ERROR,
                fields = mapOf("causes" to io.github.fartown.movo.diagnostics.MemoryDiagnostics.causes(throwable)) +
                    io.github.fartown.movo.diagnostics.MemoryDiagnostics.environmentSnapshot(),
            )
            val modelFailure = throwable as? AgentModelExecutionException
            io.github.fartown.movo.diagnostics.runlog.RunLog.end(
                status = if (cancelled) "cancelled" else "failed",
                code = (modelFailure?.cause as? AgentModelFailure)?.code ?: (throwable as? AgentModelFailure)?.code,
                exception = io.github.fartown.movo.diagnostics.MemoryDiagnostics.causes(throwable),
                message = throwable.message,
            )
            val message = if (cancelled) {
                "已停止"
            } else {
                throwable.message ?: throwable.javaClass.simpleName
            }
            if (cancelled) {
                AndroidAgentLogger.info("Agent runtime stopped")
            } else {
                val requestFailure = modelFailure?.cause as? AgentModelFailure
                AndroidAgentLogger.error(
                    "Agent runtime failed: type=${throwable.safeLogType()}, " +
                        "model_code=${requestFailure?.code.orEmpty()}, " +
                        "cause_type=${requestFailure?.cause?.safeLogType().orEmpty()}"
                )
                val event = AgentEvent.RunFailed(message)
                runCatching {
                    acceptEvent(
                        session,
                        event,
                        archivedEvents,
                        entrySurfaceGuard,
                    )
                }.onFailure { checkpointFailure ->
                    AndroidAgentLogger.error(
                        "Agent runtime failure checkpoint failed: " +
                            "type=${checkpointFailure.safeLogType()}"
                    )
                    session.emit(event)
                }
            }
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = message,
                reasoningContent = modelFailure?.reasoningContent.orEmpty(),
                transcript = modelFailure?.transcript.orEmpty(),
                contextSnapshot = modelFailure?.contextSnapshot?.copy(operationId = request.runId) ?: session.contextSnapshot,
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } finally {
            runCatching { toolsBinding?.close() }
            runCatching { toolExecutor?.close() }
        }

        if (cancelled && session.isTerminal) {
            runCatching {
                persistArtifacts(snapshotRequest(request), result, archivedEvents)
            }.onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime cancelled result persistence failed: " +
                        "type=${throwable.safeLogType()}"
                )
            }
            return Outcome(
                result = result,
                entrySurfaceGuard = entrySurfaceGuard,
                shouldUpdateHost = true,
            )
        }

        val completedRequest = runCatching { snapshotRequest(request) }
            .getOrElse { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime request snapshot failed: type=${throwable.safeLogType()}"
                )
                request
            }
        val committed = session.complete(result) {
            runCatching { persistArtifacts(completedRequest, result, archivedEvents) }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime artifact persistence failed: type=${throwable.safeLogType()}"
                    )
                }
        }
        return Outcome(
            result = result,
            entrySurfaceGuard = entrySurfaceGuard,
            completedRequest = completedRequest.takeIf { committed },
            response = response.takeIf { committed },
            shouldUpdateHost = committed,
        )
    }

    private fun acceptEvent(
        session: AgentRuntimeSession,
        event: AgentEvent,
        archivedEvents: MutableList<AgentEvent>,
        entrySurfaceGuard: EntrySurfaceGuard?,
    ) {
        if (!session.emit(event)) return
        archivedEvents += event
        io.github.fartown.movo.diagnostics.runlog.RunLogRecorder.agentEvent(event)
        recordDiagnosticEvent(event)
        if (event is AgentEvent.ModelRetryScheduled) {
            AndroidAgentLogger.warn("Agent runtime event: ${event.toLogLine()}")
        } else if (event !is AgentEvent.AssistantBlockDelta) {
            AndroidAgentLogger.debug { "Agent runtime event: ${event.toLogLine()}" }
        }
        runCatching { onAcceptedEvent(event, entrySurfaceGuard) }
            .onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_event_projection_failed") {
                    "Agent runtime event projection failed: type=${throwable.safeLogType()}"
                }
            }
    }
}

/**
 * 当前选中的 Linux 发行版能不能跑命令：装好了（与终端页的判断一致），且对应后端可用——
 * 免 Root 方式要有 PRoot 组件，chroot 方式要有 Root。
 */
/** 环境信息里「用户最近在用的其他应用」（半小时内的才算）。 */
private fun recentAppEnvironment(context: android.content.Context): String =
    io.github.fartown.movo.agent.accessibility.RecentAppTracker.environmentLine(
        nowElapsedMillis = android.os.SystemClock.elapsedRealtime(),
        maxAgeMillis = 30 * 60_000L,
    ) { pkg ->
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
    }

private fun linuxEnvironmentReady(context: android.content.Context): Boolean = runCatching {
    val distribution = io.github.fartown.movo.data.repository.LinuxEnvironmentSettingsRepository.current(context)
    val rootfs = io.github.fartown.movo.agent.terminal.LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
    if (!io.github.fartown.movo.agent.terminal.LinuxEnvironmentPaths.rootfsReady(rootfs)) return@runCatching false
    when (io.github.fartown.movo.agent.terminal.LinuxEnvironmentPaths.backendOf(rootfs)) {
        io.github.fartown.movo.agent.terminal.LinuxExecutionBackend.PROOT ->
            io.github.fartown.movo.agent.terminal.ProotCommandBuilder.available()
        else -> io.github.fartown.movo.agent.terminal.TerminalRuntime.rootAvailable
    }
}.getOrDefault(false)

/** 屏幕亮着才可能有人看到确认卡、提问卡。 */
private fun screenInteractive(context: android.content.Context): Boolean = runCatching {
    (context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
}.getOrDefault(true)

/**
 * 这一步要确认或提问时，能不能等用户作答。用户自己发起的任务总是等：用户可能只是刚按了锁屏，
 * 亮屏后卡片会出现（锁着时先出解锁提示），超时才按未确认处理。后台监听唤醒的一轮没人在旁边，
 * 屏幕关着时直接按「无法确认」处理，不干等。
 */
internal fun userCanAnswer(isMonitorOrigin: Boolean, screenOn: () -> Boolean): Boolean =
    !isMonitorOrigin || screenOn()

private fun userCanAnswer(request: AgentRuntimeWire.RunRequest, context: android.content.Context): Boolean =
    userCanAnswer(request.isMonitorOrigin) { screenInteractive(context) }

/** 任务开始时的配置和现在的「设置 → 工具」开关取与：运行中只能关、不能开。 */
internal fun liveToolSwitches(
    config: io.github.fartown.movo.agent.model.AgentModelClient.ModelConfig,
    now: AgentRuntimePolicy.Permissions,
): ToolSwitches = ToolSwitches(
    browser = config.browserTools && now.browserTools,
    deviceDirect = config.deviceDirectTools && now.deviceDirectTools,
    terminal = config.terminalTools && now.terminalTools,
    sensitiveRead = config.deviceSensitiveReadTools && now.deviceSensitiveReadTools,
    sensitiveAction = config.deviceSensitiveActionTools && now.deviceSensitiveActionTools,
)

/** 按停止时立刻关掉这次运行的工具：打断网页加载、MCP 请求、技能下载和前台命令，不等它们自己超时。 */
internal fun AgentRunController.closeOnStop(tools: AutoCloseable): AgentRunController.ResourceBinding =
    register { runCatching { tools.close() } }

/**
 * 用户在「设置 → 工具」里关掉的能力：工具已经不进目录，这里再告诉模型不要换个办法（打开对应应用看屏幕、跑命令）绕过去。
 * 真机上关了「读取敏感信息」后，模型曾打开系统通讯录 App 读出联系人。
 */
internal fun switchNotes(config: io.github.fartown.movo.agent.model.AgentModelClient.ModelConfig): String = buildList {
    if (!config.deviceSensitiveReadTools) {
        add(
            "- 用户关闭了「读取敏感信息」：不要读取通知、位置、验证码、通讯录、短信、通话记录、剪贴板，" +
                "也不要打开对应的应用看屏幕或用命令去读；需要这些信息时，告诉用户可以在 设置 → 工具 里开启。",
        )
    }
    if (!config.deviceSensitiveActionTools) {
        add(
            "- 用户关闭了「敏感设备操作」：不要修改系统设置、开关网络与蓝牙、停止或冻结应用，" +
                "也不要打开设置应用替用户改；需要时告诉用户可以在 设置 → 工具 里开启，或让用户自己操作。",
        )
    }
    // 「设备直达」关掉只是不走直达捷径，仍可以在时钟、音乐等应用里操作界面完成，不需要额外说明。
}.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "## 用户关闭的能力\n").orEmpty()
