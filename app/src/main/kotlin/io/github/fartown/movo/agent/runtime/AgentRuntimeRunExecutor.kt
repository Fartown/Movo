package io.github.fartown.movo.agent.runtime

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
    ): Outcome = io.github.fartown.movo.diagnostics.MemoryDiagnostics.withRun {
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
        var checkpointRecorder: AgentRunCheckpointRecorder? = null
        val timing = AgentRunTiming(AndroidAgentLogger)

        val result = try {
            checkpointRecorder = AgentRunCheckpointRecorder.create(appContext, request)
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
                    acceptEvent(session, ev, archivedEvents, entrySurfaceGuard, checkpointRecorder)
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
                        switches = ToolSwitches(
                            browser = request.config.browserTools,
                            deviceDirect = request.config.deviceDirectTools,
                            terminal = request.config.terminalTools,
                            sensitiveRead = request.config.deviceSensitiveReadTools,
                            sensitiveAction = request.config.deviceSensitiveActionTools,
                        ),
                        linuxReady = linuxEnvironmentReady(appContext),
                        memoryScope = when {
                            !memoryEnabled -> MemoryScope.DISABLED
                            roleplayContext != null -> MemoryScope.CHARACTER
                            else -> MemoryScope.REAL
                        },
                        conversationBound = conversationId != null,
                        conversationId = conversationId,
                        // 后台监听唤醒的一轮在屏幕关着时没人能作答：不给 ask_user，审批立即返回「无法确认」。
                        // 用户自己发起的任务照常等：亮屏后卡片出现（锁着时先出解锁提示）。
                        interactive = userCanAnswer(request, appContext),
                        modelInputs = setOf(ModelInput.TEXT, ModelInput.IMAGE),
                        approvalPolicy = io.github.fartown.movo.agent.tools.core.ApprovalSettings.load(appContext),
                    )
                },
                interaction = BrokeredUserInteraction(
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
            ).also { built ->
                toolExecutor = AutoCloseable {
                    AgentInteractionRegistry.unregister(request.runId)
                    built.close()
                }
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
                voiceConversation = request.voiceSessionId.isNotBlank(),
                compactOnly = request.operation == AgentRuntimeWire.OP_COMPACT,
                onContextSnapshot = { snapshot ->
                    val committed = snapshot.copy(operationId = request.runId)
                    AgentRunCheckpointStore.saveContext(appContext, request.runId, committed)
                    session.updateContext(committed)
                },
                onTranscript = { transcript ->
                    AgentRunCheckpointStore.saveTranscript(appContext, request.runId, transcript)
                    session.updateTranscript(transcript)
                },
                capabilitiesProvider = { AgentToolCapabilities.capture(appContext) },
                prompt = request.prompt,
                toolExecutor = effectiveExecutor,
                typedCatalog = typedCatalog,
                toolGuide = {
                    (typedSubsystem.pipeline.promptSections().map { it.text.trim() } + switchNotes(request.config))
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                },
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
                    checkpointRecorder,
                )
            }
            response = completedResponse
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
                        checkpointRecorder,
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
            runCatching { checkpointRecorder?.seal() }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime checkpoint seal failed: type=${throwable.safeLogType()}"
                    )
                }
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
        checkpointRecorder: AgentRunCheckpointRecorder?,
    ) {
        checkpointRecorder?.accept(event)
        if (!session.emit(event)) return
        archivedEvents += event
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
