package io.github.fartown.movo.agent.runtime

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import android.app.Service
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.ResultReceiver
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.fartown.movo.MovoApp
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import io.github.fartown.movo.agent.device.RootAccess
import io.github.fartown.movo.agent.media.AgentImageCodec
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.overlay.AgentHapticFeedback
import io.github.fartown.movo.agent.overlay.AgentOverlayBubble
import io.github.fartown.movo.agent.overlay.AgentOverlayGlow
import io.github.fartown.movo.agent.overlay.AgentOverlayOrb
import io.github.fartown.movo.agent.overlay.AgentOverlayRemoveZone
import io.github.fartown.movo.agent.overlay.orbMode
import io.github.fartown.movo.agent.voice.MovoAssistantVoiceService
import io.github.fartown.movo.agent.voice.session.VoiceChannel
import io.github.fartown.movo.agent.voice.session.VoiceEntry
import io.github.fartown.movo.agent.voice.session.VoiceSessionManager
import io.github.fartown.movo.agent.voice.session.VoiceSurfaceTracker
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.agent.overlay.AgentOverlayPhase
import io.github.fartown.movo.agent.overlay.AgentOverlayState
import io.github.fartown.movo.agent.overlay.markPaused
import io.github.fartown.movo.agent.overlay.markResumed
import io.github.fartown.movo.agent.overlay.AgentOverlayStatus
import io.github.fartown.movo.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.fartown.movo.agent.overlay.applyEvent
import io.github.fartown.movo.config.Prefs
import io.github.fartown.movo.core.AndroidAgentLogger
import io.github.fartown.movo.core.ModuleConfig
import io.github.fartown.movo.core.safeLogType
import io.github.fartown.movo.data.repository.RuntimeConfigRepository
import io.github.fartown.movo.ui.AgentConversationSheetActivity
import io.github.fartown.movo.ui.markdown.InAppBrowserUriHandler
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 模块进程内的通用 Agent Runtime。
 *
 * Hook 入口只发送请求和接收结果；模型调用、工具执行、运行状态浮窗都在本服务中完成。
 */
internal class AgentRuntimeService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    private val resultIo = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "agent-result-io") }
    private val serviceMessenger = Messenger(IncomingHandler())

    @Volatile
    private var activeSession: AgentRuntimeSession? = null
    private var startRequestGeneration = 0L
    private var pendingStartRequest: PendingStartRequest? = null

    private data class PendingStartRequest(
        val generation: Long,
        val incoming: AgentRuntimeWire.IncomingRunRequest,
        val replyTo: Messenger?,
    )

    private var windowManager: WindowManager? = null
    /** [windowManager] 取自哪个 context（无障碍服务实例或本服务）；变了就要整体重建浮窗。 */
    private var overlayOwner: Context? = null
    /** 下一次创建悬浮球时是否播进场；重建浮窗时为 false。 */
    private var orbEntrance = true
    /** 重建浮窗时沿用的悬浮球位置（用户可能拖过）。 */
    private var restoreOrbPosition: WindowManager.LayoutParams? = null
    private val onAccessibilityInstanceChanged: () -> Unit = {
        mainHandler.post(::rebuildOverlayIfOwnerChanged)
    }
    private var glowView: ComposeView? = null
    private var orbView: ComposeView? = null
    private var bubbleView: ComposeView? = null
    private var glowParams: WindowManager.LayoutParams? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    /** 展开卡的原始位置（距屏幕底部）；键盘补充时抬到键盘上方，键盘收起后回到这里。 */
    private var bubbleBaseY = 0
    private val resultConversationOpening = mutableStateOf(false)
    private var resultConversationTarget: AgentConversationTarget? = null
    private var resultConversationRunId: String? = null
    private var resultHandoffToken: Any? = null
    private var isResultConversation = false

    private val state = mutableStateOf(AgentOverlayState.Initial)
    private val collapsed = mutableStateOf(true)
    /** 展开卡的可见状态：先播退场动画再移除窗口（规范 9.10「浮窗」）。 */
    private val bubbleVisible = mutableStateOf(true)
    /** 悬浮球停靠在右边缘（true）还是左边缘；展开卡出现在球朝屏幕中心的一侧。 */
    private val orbOnEnd = mutableStateOf(true)
    /**
     * 待命（规范 8.1）：悬浮球出现后常驻，看过结果后回到待命（只有玻璃圆 + 光球），直到拖入「移除」区。
     * 待命时 Movo 自己的界面在前台就先藏起来，回到其他 App 再出现。
     */
    private val standby = mutableStateOf(false)
    /** 悬浮球退场（移除）时置 false，播完退场再移除窗口。 */
    private val orbShown = mutableStateOf(true)
    private val removeEngaged = mutableStateOf(false)
    private val removeZoneVisible = mutableStateOf(false)
    private var removeZoneView: ComposeView? = null
    /** 拖动中手指对应的悬浮球窗口位置（吸附到移除区时窗口不跟手，松开吸附后回到这里）。 */
    private var fingerX = 0f
    private var fingerY = 0f
    /** App 自己的页面进出前台时刷新待命悬浮球的显隐（前台判断用 [VoiceSurfaceTracker]，它从进程启动起就在计数）。 */
    private val appActivityCallbacks = object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: android.app.Activity) {
            mainHandler.post(::updateStandbyOrbVisibility)
        }
        override fun onActivityPaused(activity: android.app.Activity) {
            mainHandler.post(::updateStandbyOrbVisibility)
        }
        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: android.app.Activity) = Unit
        override fun onActivityStopped(activity: android.app.Activity) = Unit
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: android.app.Activity) = Unit
    }
    private val panelIdleToken = Any()
    private var hasExecutedForegroundTool = false
    private val supplementsLock = Any()
    private val activeSupplements = mutableListOf<AgentUiHandoffPayload.Supplement>()
    private var nextSupplementIndex = 1
    @Volatile
    private var lastCompletedRunContext: CompletedRunContext? = null
    private val hideToken = Any()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        application.registerActivityLifecycleCallbacks(appActivityCallbacks)
        AgentAccessibilityService.addInstanceListener(onAccessibilityInstanceChanged)
        // 语音对话与悬浮窗联动（规范 8.5）：执行中语音开始时展开卡以语音模式弹出；语音结束后展开卡恢复自动收起。
        lifecycleScope.launch {
            VoiceSessionManager.state.map { it.active }.distinctUntilChanged().collect(::onVoiceActiveChanged)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != AgentRuntimeWire.ACTION_BIND) return null
        return serviceMessenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_KEEP_ALIVE || activeSession == null) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        io.github.fartown.movo.diagnostics.MemoryDiagnostics.record("lifecycle", "runtime.unbound",
            fields = mapOf("run_active" to (activeSession?.isTerminal == false)))
        if (activeSession?.isTerminal == false) {
            AndroidAgentLogger.debug {
                "Agent runtime client unbound while run is active; detached run continues"
            }
        }
        return false
    }

    override fun onDestroy() {
        application.unregisterActivityLifecycleCallbacks(appActivityCallbacks)
        AgentAccessibilityService.removeInstanceListener(onAccessibilityInstanceChanged)
        clearResultHandoff()
        io.github.fartown.movo.diagnostics.MemoryDiagnostics.record("lifecycle", "runtime.destroyed",
            fields = mapOf("run_active" to (activeSession?.isTerminal == false)))
        startRequestGeneration++
        pendingStartRequest?.let { pending ->
            pending.incoming.close()
            sendRequestIngestedTo(pending.replyTo, pending.incoming.request.runId)
            sendResultTo(
                pending.replyTo,
                AgentRuntimeWire.RunResult(
                    runId = pending.incoming.request.runId,
                    ok = false,
                    content = "",
                    error = "Agent Runtime 服务已停止",
                ),
            )
        }
        pendingStartRequest = null
        activeSession?.cancel("Agent Runtime 服务已停止")
        activeSession = null
        resultIo.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        bubbleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        orbView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        removeZoneView?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubbleView = null
        orbView = null
        glowView = null
        removeZoneView = null
        bubbleParams = null
        orbParams = null
        glowParams = null
        windowManager = null
        overlayOwner = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (!isMessageSenderAllowed(msg)) {
                if (msg.what == AgentRuntimeWire.MSG_START_RUN) {
                    AgentRuntimeWire.closeImageDescriptors(msg.data)
                }
                return
            }
            when (msg.what) {
                AgentRuntimeWire.MSG_START_RUN -> {
                    val data = msg.data
                    if (data == null) {
                        finishWithFailure("Agent Runtime 请求缺少消息体", msg.replyTo)
                        return
                    }
                    val incoming = runCatching {
                        AgentRuntimeWire.incomingRunRequestFromBundle(data)
                    }.getOrElse { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_invalid_start_request") {
                            "Agent runtime rejected invalid start request: type=${throwable.safeLogType()}"
                        }
                        finishWithFailure("Agent Runtime 请求格式无效", msg.replyTo)
                        return
                    }
                    val request = incoming.request
                    if (request.runId.isBlank() || (request.operation != AgentRuntimeWire.OP_COMPACT && request.prompt.isBlank() && incoming.images.isEmpty() && !incoming.hasDeferredPrompt)) {
                        incoming.close()
                        finishWithFailure("Agent Runtime 请求缺少 runId 或用户输入", msg.replyTo)
                        return
                    }
                    ingestRunRequest(incoming, msg.replyTo)
                }

                AgentRuntimeWire.MSG_CANCEL -> {
                    val runId = msg.data?.let(AgentRuntimeWire::runIdFromBundle).orEmpty()
                    if (runId.isNotBlank()) cancelRun(runId)
                }

                AgentRuntimeWire.MSG_ACK_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    dispatchResultIo { AgentRuntimeResultStore.remove(this@AgentRuntimeService, runId) }
                }

                AgentRuntimeWire.MSG_READ_CONTEXT_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    val owner = msg.data.getString("context_owner").orEmpty()
                    val replyTo = msg.replyTo
                    dispatchResultIo {
                        val target = AgentRuntimeResultStore.readOwned(this@AgentRuntimeService, runId, owner)
                        sendResultNow(replyTo, target?.result ?: AgentRuntimeWire.RunResult(
                            runId, false, "", "完整运行结果不可用", contextSnapshotRef = runId,
                        ))
                    }
                }

                AgentRuntimeWire.MSG_DRAIN_RESULTS -> {
                    sendDrainedResults(msg.replyTo, msg.data?.getBoolean("complete_result_refs") == true)
                }

                AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN -> {
                    sendActiveRun(msg.replyTo)
                }

                AgentRuntimeWire.MSG_ATTACH_RUN -> {
                    attachRun(
                        runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return),
                        replyTo = msg.replyTo,
                    )
                }

                AgentRuntimeWire.MSG_RESUME -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    if (activeSession?.takeIf { !it.isTerminal }?.runId == runId) requestResume()
                }

                AgentRuntimeWire.MSG_STEER -> {
                    val data = msg.data ?: return
                    val runId = AgentRuntimeWire.runIdFromBundle(data)
                    val accepted = steerRun(runId, AgentRuntimeWire.steerTextFromBundle(data))
                    runCatching {
                        msg.replyTo?.send(
                            Message.obtain(null, AgentRuntimeWire.MSG_STEER_RESPONSE).apply {
                                this.data = AgentRuntimeWire.steerResponseBundle(runId, accepted)
                            },
                        )
                    }
                }
            }
        }
    }

    private fun ingestRunRequest(
        incoming: AgentRuntimeWire.IncomingRunRequest,
        replyTo: Messenger?,
    ) {
        val request = incoming.request
        val running = activeSession?.takeUnless { it.isTerminal }
        val preparing = pendingStartRequest?.incoming?.request
        // Voice turns must never replace a running tool task, including one from another entry.
        val admission = AgentRuntimeAdmission.decide(
            AgentRuntimeAdmission.Owner(request.runId, request.voiceSessionId.isNotBlank()),
            running?.let { AgentRuntimeAdmission.Owner(it.runId, it.voiceSessionId.isNotBlank()) },
            preparing?.let { AgentRuntimeAdmission.Owner(it.runId, it.voiceSessionId.isNotBlank()) },
        )
        if (admission == AgentRuntimeAdmission.Decision.ATTACH) {
            incoming.close()
            sendRequestIngestedTo(replyTo, request.runId)
            attachRun(request.runId, replyTo)
            return
        }
        if (admission == AgentRuntimeAdmission.Decision.BUSY) {
            incoming.close()
            sendRequestIngestedTo(replyTo, request.runId)
            sendResultTo(replyTo, AgentRuntimeWire.RunResult(request.runId, false, "",
                "当前任务仍在执行，请等完成后再说", resultKind = "rejected"))
            return
        }
        val generation = ++startRequestGeneration
        pendingStartRequest?.let { previous ->
            previous.incoming.close()
            sendRequestIngestedTo(previous.replyTo, previous.incoming.request.runId)
            sendResultTo(
                previous.replyTo,
                AgentRuntimeWire.RunResult(
                    runId = previous.incoming.request.runId,
                    ok = false,
                    content = "",
                    error = "已被新的 Agent 任务替换",
                ),
            )
        }
        val pending = PendingStartRequest(generation, incoming, replyTo)
        pendingStartRequest = pending
        thread(name = "agent-runtime-image-ingest") {
            val prepared = runCatching {
                val request = AgentRuntimeImageTransfer.materialize(incoming)
                if (request.voiceSessionId.isNotBlank() &&
                    !VoiceRunReceiptStore(java.io.File(filesDir, "voice-run-receipts")).claim(request.runId)) {
                    throw DuplicateVoiceRunException()
                }
                if (!AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request)) {
                    request
                } else {
                    val runtimeConfig = runBlocking {
                        RuntimeConfigRepository.currentRuntimeConfig()
                    } ?: throw RuntimeConfigUnavailableException()
                    AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, runtimeConfig)
                }
            }
            mainHandler.post {
                if (generation != startRequestGeneration || pendingStartRequest !== pending) return@post
                pendingStartRequest = null
                sendRequestIngestedTo(replyTo, incoming.request.runId)
                prepared.fold(
                    onSuccess = { request ->
                        val permissions = AgentRuntimePolicy.permissions(
                            Prefs.localAgentPreferences()
                        )
                        startRun(
                            request.copy(
                                config = AgentRuntimePolicy.constrain(request.config, permissions),
                            ),
                            replyTo,
                        )
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_request_prepare_failed") {
                            "Agent runtime request preparation failed: type=${throwable.safeLogType()}"
                        }
                        if (throwable is DuplicateVoiceRunException) {
                            sendResultTo(replyTo, AgentRuntimeWire.RunResult(incoming.request.runId, false, "",
                                "这个任务已经接收过，请查看原对话；不会重复执行", resultKind = "unconfirmed"))
                            return@fold
                        }
                        finishWithFailure(
                            when (throwable) {
                                is AgentRuntimeImageTransfer.ImageTransferException ->
                                    throwable.message ?: "Agent Runtime 无法读取图片"
                                is RuntimeConfigUnavailableException ->
                                    "请先在 Movo 中配置可用的模型"
                                else -> "Agent Runtime 无法准备请求"
                            },
                            replyTo,
                            incoming.request.runId,
                        )
                    },
                )
            }
        }
    }

    private fun startRun(
        request: AgentRuntimeWire.RunRequest,
        replyTo: Messenger? = null,
        fromResultCard: Boolean = false,
    ) {
        clearResultHandoff()
        resultConversationTarget = AgentConversationTarget.from(request.handoff)
        isResultConversation = fromResultCard || AgentConversationSheetActivity.isConversationVisible(resultConversationTarget)
        resultConversationRunId = request.runId
        activeSession?.controller?.cancel()
        val session = AgentRuntimeSession(
            runId = request.runId,
            voiceSessionId = request.voiceSessionId,
            operation = request.operation,
            eventSink = { event -> sendEventTo(replyTo, event) },
            resultSink = { result -> sendResultTo(replyTo, result) },
        )
        // Root 入口保留原有绑定服务生命周期；新增 FGS 不能成为厂商后台入口的新前置权限。
        val allowBoundFallback = RootAccess.isGranted
        val executionHeld = AgentExecutionService.acquire(
            this, "run:${request.runId}", allowBoundFallback = allowBoundFallback,
        ) { session.controller.cancel() }
        if (!executionHeld && !allowBoundFallback) {
            session.complete(AgentRuntimeWire.RunResult(
                runId = request.runId, ok = false, content = "",
                error = "无法启动后台执行服务，请返回 Movo 后重试",
            )) {}
            return
        }
        activeSession = session
        lastCompletedRunContext = null
        runCatching {
            startService(Intent(this, AgentRuntimeService::class.java).setAction(ACTION_KEEP_ALIVE))
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_keep_alive_start_failed") {
                "Agent runtime keep-alive start failed: type=${throwable.safeLogType()}"
            }
        }
        mainHandler.removeCallbacksAndMessages(hideToken)
        state.value = AgentOverlayState.Initial
        collapsed.value = true
        hasExecutedForegroundTool = false
        synchronized(supplementsLock) {
            activeSupplements.clear()
            nextSupplementIndex = 1
            if (request.handoff?.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
                val payload = AgentUiHandoffPayload.from(request.handoff.payload)
                activeSupplements += payload.supplements
                nextSupplementIndex = payload.lastSupplementIndex + 1
            }
        }

        if (fromResultCard) ensureOverlayVisible()
        thread(name = "agent-runtime") {
            try {
                executeRun(session, request)
            } finally {
                AgentExecutionService.release("run:${request.runId}")
            }
        }
    }

    private fun executeRun(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
    ) {
        val outcome = AgentRuntimeRunExecutor(
            context = this,
            currentPermissions = ::currentRuntimePermissions,
            snapshotRequest = { it.withActiveSupplements() },
            onAcceptedEvent = { event, entrySurfaceGuard ->
                handleAcceptedRunEvent(session, event, entrySurfaceGuard)
            },
            persistArtifacts = ::persistRunArtifacts,
        ).execute(session, request)
        if (!outcome.shouldUpdateHost) return
        postTerminalOverlay(
            session = session,
            result = outcome.result,
            entrySurfaceGuard = outcome.entrySurfaceGuard,
            completedContext = CompletedRunContext(
                request = outcome.completedRequest ?: request.withActiveSupplements(),
                response = outcome.response ?: AgentModelClient.ModelResponse.Text(
                    content = outcome.result.content,
                    transcript = outcome.result.transcript.ifEmpty { session.transcript },
                    contextSnapshot = outcome.result.contextSnapshot ?: session.contextSnapshot,
                ),
            ),
        )
    }

    private fun handleAcceptedRunEvent(
        session: AgentRuntimeSession,
        event: AgentEvent,
        entrySurfaceGuard: EntrySurfaceGuard?,
    ) {
        if (activeSession !== session) return
        val revealsForegroundOperation = AgentOverlayVisibilityPolicy.shouldRevealFor(event)
        val requiresEntrySurfaceDismissal =
            AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event)
        val entrySurfaceReady = (!requiresEntrySurfaceDismissal || entrySurfaceGuard == null ||
            runCatching { entrySurfaceGuard.dismissOnce() }.getOrDefault(false)) &&
            (!requiresEntrySurfaceDismissal || !isResultConversation ||
                AgentConversationSheetActivity.hideForDeviceOperation())
        mainHandler.post {
            if (activeSession !== session) return@post
            if (
                AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(
                    event,
                    entrySurfaceReady,
                )
            ) {
                hasExecutedForegroundTool = true
            }
            if (session.isTerminal) return@post
            runCatching {
                state.value = state.value.applyEvent(event)
                if (revealsForegroundOperation && entrySurfaceReady) {
                    if (orbView == null) {
                        AgentHapticFeedback.perform(
                            this,
                            AgentHapticFeedback.Type.RUN_STARTED,
                        )
                    }
                    // 已常驻（待命）时不重新弹出，状态环与角标原地交叉淡化为执行中（规范 9.5）。
                    standby.value = false
                    ensureOverlayVisible()
                    updateStandbyOrbVisibility()
                    // 语音对话从 App 内延续过来：展开卡直接以语音模式出现（规范 8.2「跨界面不断线」）。
                    if (VoiceSessionManager.active && collapsed.value) expandBubble()
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_overlay_event_failed") {
                    "Agent runtime overlay event failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun persistRunArtifacts(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>,
    ) {
        // outbox 是终态与在途 checkpoint 之间的提交点；失败时保留 checkpoint 供下次恢复。
        persistCompletedRun(request, result)
        runCatching { persistArchivedRun(request, result, events) }
            .onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime archive persistence failed: type=${throwable.safeLogType()}"
                )
            }
    }

    private fun postTerminalOverlay(
        session: AgentRuntimeSession,
        result: AgentRuntimeWire.RunResult,
        entrySurfaceGuard: EntrySurfaceGuard?,
        completedContext: CompletedRunContext? = null,
    ) {
        mainHandler.post {
            if (activeSession !== session) return@post
            lastCompletedRunContext = completedContext
            activeSession = null
            runCatching {
                if (result.ok) {
                    enterFinalState(
                        state.value.copy(
                            phase = AgentOverlayPhase.FINISHED,
                            status = AgentOverlayStatus.ResultReady,
                            detailText = result.content.trim().ifBlank { state.value.detailText },
                        ),
                        keepVisible = entrySurfaceGuard?.wasTriggered == true,
                    )
                } else {
                    enterFinalState(
                        AgentOverlayState(
                            phase = AgentOverlayPhase.FAILED,
                            status = if (result.error == "已停止") {
                                AgentOverlayStatus.Stopped
                            } else {
                                AgentOverlayStatus.RunFailed
                            },
                            detailText = io.github.fartown.movo.agent.model.UserFacingFailure.message(
                                result.error,
                                getString(R.string.movo_failure_network),
                            ).orEmpty(),
                        ),
                        keepVisible = entrySurfaceGuard?.wasTriggered == true,
                    )
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_terminal_overlay_failed") {
                    "Agent runtime terminal overlay failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun sendEventTo(
        target: Messenger?,
        event: AgentEvent,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_EVENT)
            msg.data = AgentRuntimeWire.eventToBundle(event)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_event_delivery_failed") {
                "Agent runtime event delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun dispatchResultIo(block: () -> Unit) {
        try {
            resultIo.execute {
                try { block() } catch (failure: Exception) {
                    AndroidAgentLogger.warnThrottled("runtime_result_io_failed") {
                        "Agent runtime result I/O failed: type=${failure.safeLogType()}"
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            AndroidAgentLogger.info("Agent runtime result delivery deferred after service stop")
        }
    }

    private fun sendResultTo(target: Messenger?, result: AgentRuntimeWire.RunResult) {
        dispatchResultIo { sendResultNow(target, result) }
    }

    private fun sendResultNow(
        target: Messenger?,
        result: AgentRuntimeWire.RunResult,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_RESULT)
            msg.data = AgentRuntimeWire.toBundle(result, cacheDir)
            AgentWireText.send(target, msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_delivery_failed") {
                "Agent runtime result delivery failed: type=${throwable.safeLogType()}"
            }
            // 不把传输失败伪装成已交付终态；引用使新客户端保留 outbox，等待完整恢复。
            val fallback = AgentRuntimeWire.RunResult(result.runId, false, "",
                "完整结果传输失败，已保存的历史未删除。请重新打开会话恢复。",
                contextSnapshotRef = result.runId, operation = result.operation)
            try {
                target?.send(Message.obtain(null, AgentRuntimeWire.MSG_RESULT).apply {
                    data = AgentRuntimeWire.toBundle(fallback)
                })
            } catch (deliveryFailure: Exception) {
                AndroidAgentLogger.warnThrottled("runtime_result_failure_notice_undelivered") {
                    "Agent runtime result notice undelivered: type=${deliveryFailure.safeLogType()}"
                }
            }
        }
    }

    private fun sendRequestIngestedTo(
        target: Messenger?,
        runId: String,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_REQUEST_INGESTED)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_ingest_ack_failed") {
                "Agent runtime ingest acknowledgement failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendDrainedResults(replyTo: Messenger?, referencesOnly: Boolean) {
        dispatchResultIo { sendDrainedResultsNow(replyTo, referencesOnly) }
    }

    private fun sendDrainedResultsNow(replyTo: Messenger?, referencesOnly: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE)
            msg.data = AgentRuntimeWire.completedRunsToBundle(
                if (referencesOnly) AgentRuntimeResultStore.pendingPage(this) else AgentRuntimeResultStore.list(this).take(8)
            )
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_drain_results_failed") {
                "Agent runtime drain results failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendActiveRun(replyTo: Messenger?) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN_RESPONSE)
            msg.data = AgentRuntimeWire.ackBundle(activeSession?.takeUnless { it.isTerminal }?.runId ?: pendingStartRequest?.incoming?.request?.runId.orEmpty())
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_active_run_delivery_failed") {
                "Agent runtime active run delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun attachRun(runId: String, replyTo: Messenger?) {
        val session = activeSession
        val attached = replyTo != null &&
            runId.isNotBlank() &&
            session?.runId == runId &&
            session.attach(
                eventSink = { event -> sendEventTo(replyTo, event) },
                resultSink = { result -> sendResultTo(replyTo, result) },
                onReplayComplete = { sendAttachRunResponse(runId, replyTo, attached = true) },
            )
        if (!attached) sendAttachRunResponse(runId, replyTo, attached = false)
    }

    private fun sendAttachRunResponse(runId: String, replyTo: Messenger?, attached: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE)
            msg.data = AgentRuntimeWire.attachRunResponseBundle(runId, attached)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_attach_run_delivery_failed") {
                "Agent runtime attach response failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun persistCompletedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult
    ) {
        val handoff = request.handoff ?: return
        AgentRuntimeResultStore.add(
            this,
            AgentRuntimeWire.CompletedRun(
                handoff = handoff,
                result = result,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun persistArchivedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>
    ) {
        val handoff = request.handoff ?: return
        AgentExternalArchivePayload.from(handoff.payload) ?: return
        val userImagePreviews = if (
            handoff.source == AgentRuntimeWire.MOVO_VOICE_HANDOFF_SOURCE
        ) {
            request.images
                .asSequence()
                .take(MAX_ARCHIVED_USER_IMAGE_PREVIEWS)
                .mapNotNull { image ->
                    AgentImageCodec.previewFromReference(this, image)?.reference
                }
                .toList()
        } else {
            emptyList()
        }
        AgentRunArchiveStore.add(
            this,
            AgentRunArchiveStore.ArchivedRun(
                handoff = handoff,
                events = events,
                result = result,
                createdAt = System.currentTimeMillis(),
                userImagePreviews = userImagePreviews,
            )
        )
    }

    private fun finishWithFailure(
        message: String,
        replyTo: Messenger? = null,
        runId: String = "",
    ) {
        sendResultTo(
            replyTo,
            AgentRuntimeWire.RunResult(runId = runId, ok = false, content = "", error = message, resultKind = "rejected"),
        )
        if (activeSession != null) return
        enterFinalState(
            AgentOverlayState(
                phase = AgentOverlayPhase.FAILED,
                status = AgentOverlayStatus.RunFailed,
                detailText = message
            )
        )
    }

    private fun requestStop() {
        val session = activeSession
        if (session == null) {
            dismissAndStop()
            return
        }
        cancelRun(session.runId)
    }

    private fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        pendingStartRequest?.takeIf { pending -> pending.incoming.request.runId == runId }?.let { pending ->
            startRequestGeneration++
            pendingStartRequest = null
            pending.incoming.close()
            sendRequestIngestedTo(pending.replyTo, runId)
            sendResultTo(
                pending.replyTo,
                AgentRuntimeWire.RunResult(
                    runId = runId,
                    ok = false,
                    content = "",
                    error = "已停止",
                ),
            )
            return
        }
        val session = activeSession ?: return
        if (runId != session.runId) {
            AndroidAgentLogger.debug { "Agent runtime ignored stale cancel request" }
            return
        }
        if (!session.isTerminal) {
            session.controller.cancel()
            state.value = state.value.copy(status = AgentOverlayStatus.Stopping)
        }
    }

    private fun requestPause() {
        activeSession?.controller?.pause()
        activeSession?.broadcast(AgentEvent.RunPaused)
        state.value = state.value.markPaused()
    }

    private fun requestResume() {
        activeSession?.controller?.resume()
        activeSession?.broadcast(AgentEvent.RunResumed)
        state.value = state.value.markResumed()
    }

    private fun requestSupplement(text: String) {
        val supplementText = text.trim()
        if (supplementText.isBlank()) return
        setBubbleInputMode(focusable = false)
        activeSession?.let { session ->
            val event = session.steer(supplementText) {
                recordSupplementEvent(supplementText)
            }
            if (event == null) {
                if (!session.isTerminal) {
                    state.value = state.value.copy(
                        status = AgentOverlayStatus.Finishing,
                    )
                    return
                }
            } else {
                AndroidAgentLogger.info(
                    "Agent runtime supplement received: index=${event.index}, chars=${event.text.length}"
                )
                state.value = state.value.applyEvent(event)
                return
            }
        }

        continueFromResult(supplementText)
    }

    /**
     * App 内输入框的补充：只交给指定的、仍在执行的 run，返回是否被接收。
     * 与悬浮球「补充」共用 steering 通道与补充序号；这里不做「基于结果续跑」，未接收时由入口层排队。
     */
    private fun steerRun(runId: String, text: String): Boolean {
        val supplementText = text.trim()
        if (supplementText.isBlank()) return false
        val session = activeSession?.takeIf { it.runId == runId && !it.isTerminal } ?: return false
        val event = session.steer(supplementText) { recordSupplementEvent(supplementText) } ?: return false
        AndroidAgentLogger.info(
            "Agent runtime supplement received from app: index=${event.index}, chars=${event.text.length}"
        )
        state.value = state.value.applyEvent(event)
        return true
    }

    private fun continueFromResult(text: String): Boolean {
        val supplementText = text.trim()
        if (supplementText.isEmpty() || activeSession != null || pendingStartRequest != null) return false
        val completed = lastCompletedRunContext ?: return false
        if (completed.request.operation != AgentRuntimeWire.OP_CHAT) {
            state.value = state.value.copy(status = AgentOverlayStatus.ContinuationUnavailable)
            return false
        }
        val continuationRequest = AgentContinuationBuilder.build(
            request = completed.request,
            response = completed.response,
            supplement = supplementText,
        )
        startRun(continuationRequest, fromResultCard = true)
        return true
    }

    private fun recordSupplementEvent(text: String): AgentEvent.UserSupplementReceived {
        val supplement = synchronized(supplementsLock) {
            AgentUiHandoffPayload.Supplement(
                index = nextSupplementIndex++,
                text = text,
                createdAt = System.currentTimeMillis(),
            ).also { activeSupplements += it }
        }
        return AgentEvent.UserSupplementReceived(
            index = supplement.index,
            text = supplement.text,
        )
    }

    private fun ensureOverlayVisible() {
        showOverlay()
    }

    private fun showOverlay() {
        if (orbView != null && overlayOwner !== overlayContext()) rebuildOverlayIfOwnerChanged()
        if (orbView != null) {
            // 悬浮球已常驻：只补上边缘光晕。
            if (glowView == null) windowManager?.let(::showGlow)
            return
        }
        // TYPE_ACCESSIBILITY_OVERLAY 免 SYSTEM_ALERT_WINDOW 权限；仅回退态（无障碍未启用）才需检查
        if (AgentAccessibilityService.current() == null && !Settings.canDrawOverlays(this)) return
        val owner = overlayContext()
        val wm = owner.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm
        overlayOwner = owner

        showGlow(wm)

        // ── 光球窗口：始终显示，右侧中下 ──────────────────────────────
        orbShown.value = true
        val animateOrbEntrance = orbEntrance
        orbEntrance = true
        val orb = createOverlayComposeView {
            val voice by VoiceSessionManager.state.collectAsState()
            AgentOverlayOrb(
                mode = orbMode(
                    state.value.phase, standby.value, voice.active,
                    stopped = state.value.status == AgentOverlayStatus.Stopped,
                ),
                onTap = ::onOrbTapped,
                onLongPress = ::onOrbLongPressed,
                onDragStart = ::onOrbDragStart,
                onDrag = ::handleDrag,
                onDragEnd = ::onOrbDragEnd,
                shown = orbShown.value,
                engaged = removeEngaged.value,
                hearing = voice.channel == VoiceChannel.Hearing,
                longRun = (state.value.elapsedMillis(System.currentTimeMillis()) ?: 0L) >= 10_000L,
                animateEntrance = animateOrbEntrance,
            )
        }
        val orbLp = orbLayoutParams().apply {
            restoreOrbPosition?.let { previous ->
                gravity = previous.gravity
                x = previous.x
                y = previous.y
            }
            restoreOrbPosition = null
        }
        runCatching { wm.addView(orb, orbLp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_orb_add_view_failed") {
                "Agent runtime orb addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        orbView = orb
        orbParams = orbLp
        orb.visibility = View.VISIBLE
        publishOrbRect()

        // ── 小气泡窗口：展开态显示，跟随光球，窗口外触摸穿透 ─────────
        if (!collapsed.value) {
            showBubble(wm)
        }
    }

    /**
     * 无障碍服务重连（或断开、恢复）后，旧实例名下的浮窗已被系统移除，旧 WindowManager 再加窗口会
     * BadTokenException（光晕、展开卡加不上，悬浮球消失）。用当前可用的 context 按原状态重建。
     */
    private fun rebuildOverlayIfOwnerChanged() {
        if (orbView == null || overlayOwner === overlayContext()) return
        AndroidAgentLogger.debug { "Agent runtime overlay owner changed; rebuilding overlay windows" }
        val wasStandby = standby.value
        val hadGlow = glowView != null
        // 先用新的 context 加好新窗口，再撤旧窗口：旧窗口还在屏幕上时（例如从普通悬浮窗换回无障碍浮窗）不留空档；
        // 新球沿用原位置、不播进场。
        val oldManager = windowManager
        val oldViews = listOfNotNull(orbView, bubbleView, glowView, removeZoneView)
        restoreOrbPosition = orbParams
        removeZoneView = null
        orbView = null
        bubbleView = null
        glowView = null
        orbParams = null
        bubbleParams = null
        glowParams = null
        orbDiscRect = null
        standby.value = false
        windowManager = null
        overlayOwner = null
        orbEntrance = false
        showOverlay()
        orbEntrance = true
        restoreOrbPosition = null
        oldViews.forEach { view -> runCatching { oldManager?.removeView(view) } }
        if (orbView == null) return
        if (!hadGlow) {
            glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
            glowView = null
            glowParams = null
        }
        if (wasStandby) {
            standby.value = true
            updateStandbyOrbVisibility()
        }
    }

    /** 氛围光窗口：全屏触摸穿透，彩虹光圈，截图时被 takeScreenshotOfWindow 过滤。 */
    private fun showGlow(wm: WindowManager) {
        if (glowView != null) return
        val glow = createOverlayComposeView {
            AgentOverlayGlow(state = state.value)
        }
        val glowLp = glowLayoutParams()
        runCatching { wm.addView(glow, glowLp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_glow_add_view_failed") {
                "Agent runtime glow addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        glowView = glow
        glowParams = glowLp
    }

    /**
     * 点悬浮球：待命时打开对话浮层；执行中 / 暂停时展开或收起展开卡；完成 / 失败时打开对话浮层查看结果
     * （规范 8.1：完成后保持 ✓，点开才看结果，不自动弹出）。
     */
    private fun onOrbTapped() {
        if (standby.value) {
            markSheetFromOrb()
            MovoAssistantVoiceService.showAssistant(this, autoListen = false)
            return
        }
        val phase = state.value.phase
        if ((phase == AgentOverlayPhase.FINISHED || phase == AgentOverlayPhase.FAILED) && activeSession == null) {
            collapseBubble()
            markSheetFromOrb()
            openResultConversation()
            return
        }
        toggleCollapse()
    }

    /**
     * 长按悬浮球 300ms（规范 8.1）：执行中 / 暂停 → 展开卡以语音模式弹出；待命 / 完成 → 打开对话浮层并直接进入语音模式。
     */
    private fun onOrbLongPressed() {
        AgentHapticFeedback.perform(this, AgentHapticFeedback.Type.LONG_PRESS)
        val phase = state.value.phase
        when {
            standby.value -> {
                markSheetFromOrb()
                MovoAssistantVoiceService.showAssistant(this, autoListen = true)
            }
            activeSession == null && (phase == AgentOverlayPhase.FINISHED || phase == AgentOverlayPhase.FAILED) -> {
                collapseBubble()
                markSheetFromOrb()
                openResultConversation(autoListen = true)
            }
            else -> {
                expandBubble()
                startPanelVoice()
            }
        }
    }

    /** 展开卡里的声波 / 长按悬浮球：开始语音对话；执行中说的话停顿后作为补充交给当前任务。 */
    private fun startPanelVoice() {
        if (VoiceSessionManager.active) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.movo_overlay_mic_required, Toast.LENGTH_LONG).show()
            return
        }
        runCatching { VoiceEntry.startInPlace(this) }.onFailure { throwable ->
            AndroidAgentLogger.warn("Overlay voice start failed: type=${throwable.safeLogType()}")
            Toast.makeText(this, R.string.movo_overlay_voice_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleCollapse() {
        if (collapsed.value) expandBubble() else collapseBubble()
    }

    private fun expandBubble() {
        collapsed.value = false
        val wm = windowManager ?: return
        mainHandler.removeCallbacksAndMessages(bubbleRemovalToken)
        bubbleVisible.value = true
        if (bubbleView == null) showBubble(wm)
        scheduleBubbleAutoCollapse()
    }

    /** 收起：先让展开卡播退场（120ms），再移除窗口。 */
    private fun collapseBubble() {
        collapsed.value = true
        mainHandler.removeCallbacksAndMessages(panelIdleToken)
        val view = bubbleView ?: return
        bubbleVisible.value = false
        setBubbleInputMode(focusable = false)
        mainHandler.postDelayed({
            if (collapsed.value && bubbleView === view) {
                runCatching { windowManager?.removeView(view) }
                bubbleView = null
                bubbleParams = null
            }
        }, bubbleRemovalToken, BUBBLE_EXIT_MS)
    }

    /** 展开卡 4s 无操作自动收回；暂停态、输入补充时不收回（规范 8.1）。 */
    private fun scheduleBubbleAutoCollapse() {
        mainHandler.removeCallbacksAndMessages(panelIdleToken)
        mainHandler.postDelayed({
            val lp = bubbleParams
            val typing = lp != null && lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
            if (!collapsed.value && state.value.phase == AgentOverlayPhase.RUNNING && !typing && !VoiceSessionManager.active) collapseBubble()
        }, panelIdleToken, PANEL_AUTO_COLLAPSE_MS)
    }

    private val bubbleRemovalToken = Any()

    private fun showBubble(wm: WindowManager) {
        if (bubbleView != null) return
        val bubble = createOverlayComposeView {
            AgentOverlayBubble(
                state = state.value,
                onCollapse = ::collapseBubble,
                onPause = ::requestPause,
                onResume = ::requestResume,
                onStop = ::requestStop,
                onSupplementModeChange = ::setBubbleInputMode,
                onSupplement = ::requestSupplement,
                anchorEnd = orbOnEnd.value,
                visible = bubbleVisible.value,
                onInteraction = ::scheduleBubbleAutoCollapse,
                voice = VoiceSessionManager.state.collectAsState().value,
                onStartVoice = ::startPanelVoice,
                onEndVoice = VoiceSessionManager::switchToText,
                onOpenResult = ::onOrbTapped,
            )
        }
        val lp = bubbleLayoutParams()
        runCatching { wm.addView(bubble, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_bubble_add_view_failed") {
                "Agent runtime bubble addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        bubbleView = bubble
        bubbleParams = lp
        bubbleBaseY = lp.y
    }

    /**
     * 键盘补充：浮窗不会被系统随键盘挪动，无障碍浮窗也收不到键盘 insets。输入期间定时从无障碍服务读取
     * 输入法窗口的位置，把展开卡抬到键盘上方 8；键盘收起或退出输入后回到原位。
     */
    private val trackImeForBubble = object : Runnable {
        override fun run() {
            val lp = bubbleParams ?: return
            if (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) return
            placeBubbleAboveIme(imeTopOnScreen())
            mainHandler.postDelayed(this, IME_TRACK_INTERVAL_MS)
        }
    }

    private fun imeTopOnScreen(): Int? = runCatching {
        AgentAccessibilityService.current()?.windows
            ?.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { window ->
                android.graphics.Rect().also(window::getBoundsInScreen).takeIf { it.height() > 0 }?.top
            }
    }.getOrNull()

    private fun placeBubbleAboveIme(imeTop: Int?) {
        val wm = windowManager ?: return
        val bubble = bubbleView ?: return
        val lp = bubbleParams ?: return
        val target = if (imeTop == null) {
            bubbleBaseY
        } else {
            val screenHeight = runCatching {
                android.graphics.Point().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(it) }.y
            }.getOrDefault(resources.displayMetrics.heightPixels)
            // 窗口按底部对齐，y = 窗口底边到屏幕底边的距离；卡片四周有阴影余量，减掉它让卡片本身离键盘 8。
            maxOf(bubbleBaseY, screenHeight - imeTop + dpToPx(8) - dpToPx(PANEL_SHADOW_DP))
        }
        if (lp.y == target) return
        lp.y = target
        runCatching { wm.updateViewLayout(bubble, lp) }
    }

    private fun createOverlayComposeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(overlayContext()).apply {
            setViewTreeLifecycleOwner(this@AgentRuntimeService)
            setViewTreeSavedStateRegistryOwner(this@AgentRuntimeService)
            setContent {
                MiuixTheme(colors = if (isNightMode()) darkColorScheme() else lightColorScheme()) {
                    // 部分 ROM 会给 TYPE_ACCESSIBILITY_OVERLAY 分配软件 Canvas；Miuix 的
                    // RuntimeShader 只检查系统版本，因此系统浮层统一使用其普通圆角回退。
                    CompositionLocalProvider(
                        LocalSquircleEnabled provides false,
                        LocalUriHandler provides InAppBrowserUriHandler(this@AgentRuntimeService),
                    ) {
                        io.github.fartown.movo.ui.theme.ProvideReducedMotion(content)
                    }
                }
            }
        }

    /**
     * 开始拖动：收起展开卡；没有任务在跑时底部中间淡入「移除」区（规范 9.5「悬浮球 · 移除」）。
     * 执行中不给移除入口——悬浮球是 App 外唯一的暂停入口。
     */
    private fun onOrbDragStart() {
        if (!collapsed.value) collapseBubble()
        val lp = orbParams ?: return
        fingerX = lp.x.toFloat()
        fingerY = lp.y.toFloat()
        if (activeSession == null && pendingStartRequest == null) showRemoveZone()
    }

    /** 拖动悬浮球：跟手移动（窗口以右边缘为 x 基准，向右拖 x 变小）；进入移除区时吸附到区域中心。 */
    private fun handleDrag(dx: Float, dy: Float) {
        val lp = orbParams ?: return
        val wm = windowManager ?: return
        val view = orbView ?: return
        val metrics = resources.displayMetrics
        val orbSize = dpToPx(ORB_WINDOW_DP)
        fingerX = (fingerX - dx).coerceIn(0f, (metrics.widthPixels - orbSize).toFloat())
        fingerY = (fingerY + dy).coerceIn(0f, (metrics.heightPixels - orbSize).toFloat())
        val engaged = removeZoneView != null && isOverRemoveZone(fingerX, fingerY)
        if (engaged != removeEngaged.value) {
            removeEngaged.value = engaged
            if (engaged) AgentHapticFeedback.perform(this, AgentHapticFeedback.Type.TAP)
        }
        if (engaged) {
            lp.x = metrics.widthPixels / 2 - orbSize / 2
            lp.y = removeZoneCenterY() - orbSize / 2
        } else {
            lp.x = fingerX.toInt()
            lp.y = fingerY.toInt()
        }
        runCatching { wm.updateViewLayout(view, lp) }
    }

    /** 松手：在移除区里 → 缩小淡出后移除；否则吸附到最近的左右边缘。 */
    private fun onOrbDragEnd() {
        val remove = removeEngaged.value
        removeEngaged.value = false
        hideRemoveZone()
        if (remove) {
            orbShown.value = false
            collapseBubble()
            mainHandler.postDelayed({ if (activeSession == null) dismissAndStop() else orbShown.value = true }, ORB_EXIT_MS)
        } else {
            snapOrbToEdge()
        }
    }

    private fun removeZoneCenterY(): Int =
        resources.displayMetrics.heightPixels - dpToPx(REMOVE_ZONE_BOTTOM_DP) - dpToPx(REMOVE_ZONE_DP) / 2

    private fun isOverRemoveZone(x: Float, y: Float): Boolean {
        val metrics = resources.displayMetrics
        val half = dpToPx(ORB_WINDOW_DP) / 2f
        val cx = metrics.widthPixels - x - half
        val cy = y + half
        return kotlin.math.hypot(cx - metrics.widthPixels / 2f, cy - removeZoneCenterY()) < dpToPx(REMOVE_ZONE_DP)
    }

    private fun showRemoveZone() {
        val wm = windowManager ?: return
        mainHandler.removeCallbacksAndMessages(removeZoneToken)
        removeZoneVisible.value = true
        if (removeZoneView != null) return
        val window = dpToPx(REMOVE_ZONE_DP + REMOVE_ZONE_SHADOW_DP * 2)
        val lp = WindowManager.LayoutParams(
            window,
            window,
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - window) / 2
            y = removeZoneCenterY() - window / 2
            windowAnimations = 0
        }
        val view = createOverlayComposeView {
            AgentOverlayRemoveZone(visible = removeZoneVisible.value)
        }
        runCatching { wm.addView(view, lp) }.onFailure { return }
        removeZoneView = view
    }

    private val removeZoneToken = Any()

    private fun hideRemoveZone() {
        val view = removeZoneView ?: return
        removeZoneVisible.value = false
        mainHandler.postDelayed({
            if (removeZoneView === view && !removeZoneVisible.value) {
                runCatching { windowManager?.removeView(view) }
                removeZoneView = null
            }
        }, removeZoneToken, MovoMotion.FAST_EXIT.toLong() + 30)
    }

    /** 松手吸附到最近的左右边缘（距边 8），`spring/gentle` 近似为 360ms standard 曲线。 */
    private fun snapOrbToEdge() {
        val lp = orbParams ?: return
        val wm = windowManager ?: return
        val view = orbView ?: return
        val width = resources.displayMetrics.widthPixels
        val orbWidth = dpToPx(ORB_WINDOW_DP)
        val edge = dpToPx(ORB_EDGE_DP)
        val centerFromRight = lp.x + orbWidth / 2
        val onEnd = centerFromRight < width / 2
        orbOnEnd.value = onEnd
        val target = if (onEnd) edge else width - orbWidth - edge
        android.animation.ValueAnimator.ofInt(lp.x, target).apply {
            duration = 360L
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { animator ->
                if (orbView !== view) return@addUpdateListener
                lp.x = animator.animatedValue as Int
                runCatching { wm.updateViewLayout(view, lp) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = publishOrbRect()
            })
            start()
        }
    }

    private fun orbLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 右侧中下，贴近右边缘
            gravity = Gravity.END or Gravity.TOP
            // 默认停靠右边缘（距边 8），球心距屏幕底部约 1/4 屏高，处在单手拇指可及区（规范 8.1）。
            x = dpToPx(ORB_EDGE_DP)
            y = (resources.displayMetrics.heightPixels * 0.75f).toInt() - dpToPx(ORB_WINDOW_DP) / 2
        }

    private fun bubbleLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 出现在球朝屏幕中心的一侧、距球 8，底边与球对齐（卡片四周留 12 的阴影余量）。
            val orb = orbParams
            val orbX = orb?.x ?: dpToPx(ORB_EDGE_DP)
            val orbBottom = (orb?.y ?: 0) + dpToPx(ORB_WINDOW_DP)
            gravity = (if (orbOnEnd.value) Gravity.END else Gravity.START) or Gravity.BOTTOM
            val width = resources.displayMetrics.widthPixels
            val besideOrb = orbX + dpToPx(ORB_WINDOW_DP) + dpToPx(8) - dpToPx(PANEL_SHADOW_DP)
            x = if (orbOnEnd.value) besideOrb else (width - orbX - dpToPx(ORB_WINDOW_DP)) + dpToPx(ORB_WINDOW_DP) + dpToPx(8) - dpToPx(PANEL_SHADOW_DP)
            y = (resources.displayMetrics.heightPixels - orbBottom - dpToPx(PANEL_SHADOW_DP) + dpToPx(6)).coerceAtLeast(0)
            windowAnimations = 0
        }

    private fun overlayType(): Int =
        // 无障碍服务可用时用 TYPE_ACCESSIBILITY_OVERLAY（免 SYSTEM_ALERT_WINDOW 权限，且截图
        // filterValidWindows 可过滤）；需用无障碍服务 context 创建，否则 BadTokenException
        if (AgentAccessibilityService.current() != null)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun overlayContext(): Context =
        AgentAccessibilityService.current() ?: this

    @Suppress("DEPRECATION")
    private fun glowLayoutParams(): WindowManager.LayoutParams {
        // 真实屏幕高度（含状态栏 + 导航栏），MATCH_PARENT 在部分设备不含系统栏
        val realHeight = runCatching {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealSize(point)
            point.y
        }.getOrDefault(WindowManager.LayoutParams.MATCH_PARENT)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            realHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 全屏覆盖（含状态栏/导航栏），触摸穿透不拦截页面操作；
            // TYPE_ACCESSIBILITY_OVERLAY 让 takeScreenshotOfWindow 过滤掉，对 Agent 透明
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    private fun setBubbleInputMode(focusable: Boolean) {
        val wm = windowManager ?: return
        val bubble = bubbleView ?: return
        val lp = bubbleParams ?: return
        val nextFlags = if (focusable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (lp.flags == nextFlags) return
        lp.flags = nextFlags
        if (!focusable) lp.y = bubbleBaseY
        runCatching { wm.updateViewLayout(bubble, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_bubble_focus_update_failed") {
                "Agent runtime bubble focus update failed: type=${throwable.safeLogType()}"
            }
        }
        mainHandler.removeCallbacks(trackImeForBubble)
        if (focusable) mainHandler.postDelayed(trackImeForBubble, IME_TRACK_INTERVAL_MS)
    }

    private fun openResultConversation(autoListen: Boolean = false) {
        if (resultConversationOpening.value || activeSession != null || pendingStartRequest != null) return
        val target = resultConversationTarget ?: return
        val runId = resultConversationRunId ?: return
        val token = Any()
        resultHandoffToken = token
        resultConversationOpening.value = true
        val receiver = object : ResultReceiver(mainHandler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (resultHandoffToken !== token || resultConversationRunId != runId) return
                if (resultCode == AgentConversationHandoff.RESULT_READY) {
                    enterStandby()
                } else {
                    failResultHandoff(token)
                }
            }
        }
        runCatching {
            val creatorOptions = ActivityOptions.makeBasic().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    pendingIntentCreatorBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0x524553,
                AgentConversationHandoff.intent(this, target, runId, receiver)
                    .setClass(this, AgentConversationSheetActivity::class.java)
                    .putExtra(MovoAssistantVoiceService.EXTRA_AUTO_LISTEN, autoListen),
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                creatorOptions.toBundle(),
            )
            val senderOptions = ActivityOptions.makeBasic().apply {
                pendingIntentBackgroundActivityStartMode = if (Build.VERSION.SDK_INT >= 36)
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
            pendingIntent.send(senderOptions.toBundle())
        }.onFailure {
            AndroidAgentLogger.warn("Agent result conversation launch failed")
            failResultHandoff(token)
            return
        }
        mainHandler.postDelayed({ failResultHandoff(token) }, token, 10_000)
    }

    private fun failResultHandoff(token: Any) {
        if (resultHandoffToken !== token) return
        clearResultHandoff()
        AndroidAgentLogger.warn("Agent conversation sheet not ready; runtime overlay retained")
        Toast.makeText(this, R.string.overlay_result_open_failed, Toast.LENGTH_LONG).show()
    }

    private fun clearResultHandoff() {
        resultHandoffToken?.let { mainHandler.removeCallbacksAndMessages(it) }
        resultHandoffToken = null
        resultConversationOpening.value = false
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun enterFinalState(finalState: AgentOverlayState, keepVisible: Boolean = false) {
        state.value = finalState

        if (hasExecutedForegroundTool || isResultConversation) {
            // 规范 8.1 / 9.5：悬浮球不退场，完成保持 ✓（失败保持 !），用户点开才打开对话浮层查看结果；
            // 不自动弹出，避免打断用户正在看的 App。结果交付仍走原来的 handoff（点球时发起，带回执与失败提示）。
            // 屏幕边缘光晕随状态淡出后移除窗口。
            collapseBubble()
            if (!AgentConversationSheetActivity.isConversationVisible(resultConversationTarget)) {
                ensureOverlayVisible()
                // 失败：展开卡自动弹出显示原因，保持失败态直到用户点开（规范 9.5）。
                if (finalState.phase == AgentOverlayPhase.FAILED && finalState.status != AgentOverlayStatus.Stopped) {
                    mainHandler.postDelayed({
                        if (state.value.phase == AgentOverlayPhase.FAILED && activeSession == null) expandBubble()
                    }, BUBBLE_EXIT_MS)
                }
            } else {
                // 对话浮层已在前台（从浮层发起）：直接交付到浮层，与原来一致。
                openResultConversation()
            }
            glowView?.let { view ->
                mainHandler.postDelayed({
                    if (glowView === view && state.value.phase != AgentOverlayPhase.RUNNING && state.value.phase != AgentOverlayPhase.PAUSED) {
                        runCatching { windowManager?.removeView(view) }
                        glowView = null
                        glowParams = null
                    }
                }, GLOW_FADE_MS)
            }
            mainHandler.removeCallbacksAndMessages(hideToken)
        } else if (standby.value && orbView != null) {
            // 常驻的待命悬浮球：这次没有操作其他 App，结果就在对话里，悬浮球保持待命。
            state.value = AgentOverlayState.Initial
        } else {
            dismissAndStop()
        }
    }

    /** 看过结果后回到待命：绿环与角标淡出，悬浮球留在原处（规范 8.1 / 9.5「悬浮球 · 完成」）。 */
    private fun enterStandby() {
        clearResultHandoff()
        if (orbView == null) {
            dismissAndStop()
            return
        }
        collapseBubble()
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView = null
        glowParams = null
        isResultConversation = false
        state.value = AgentOverlayState.Initial
        standby.value = true
        updateStandbyOrbVisibility()
    }

    private fun updateStandbyOrbVisibility() {
        val view = orbView ?: return
        view.visibility = if (standby.value && VoiceSurfaceTracker.appVisible) View.GONE else View.VISIBLE
    }

    private fun onVoiceActiveChanged(active: Boolean) {
        if (orbView == null || standby.value) return
        val running = activeSession != null &&
            (state.value.phase == AgentOverlayPhase.RUNNING || state.value.phase == AgentOverlayPhase.PAUSED)
        if (active && running && collapsed.value) expandBubble()
        if (!active && !collapsed.value) scheduleBubbleAutoCollapse()
    }

    private fun removeAmbientWindows() {
        orbDiscRect = null
        removeZoneView?.let { view -> runCatching { windowManager?.removeView(view) } }
        removeZoneView = null
        standby.value = false
        orbView?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubbleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        orbView = null
        bubbleView = null
        glowView = null
        orbParams = null
        bubbleParams = null
        glowParams = null
    }

    private fun dismissAndStop() {
        clearResultHandoff()
        removeAmbientWindows()
        windowManager = null
        overlayOwner = null
        stopSelf()
    }

    private fun isMessageSenderAllowed(msg: Message): Boolean {
        val uid = msg.sendingUid
        if (uid == Process.myUid()) return true
        val packages = runCatching {
            packageManager.getPackagesForUid(uid)
        }.getOrNull().orEmpty()
        return packages.any { it in ModuleConfig.AGENT_RUNTIME_ENTRY_PACKAGES }
    }

    private fun isNightMode(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun currentRuntimePermissions(): AgentRuntimePolicy.Permissions =
        AgentRuntimePolicy.permissions(
            Prefs.localAgentPreferences()
        )

    private fun AgentRuntimeWire.RunRequest.withActiveSupplements(): AgentRuntimeWire.RunRequest {
        val handoff = handoff ?: return this
        if (handoff.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return this
        val supplements = synchronized(supplementsLock) { activeSupplements.toList() }
        if (supplements.isEmpty()) return this
        val payload = AgentUiHandoffPayload.from(handoff.payload).copy(
            supplements = supplements,
        )
        return copy(
            handoff = handoff.copy(payload = payload.toJson())
        )
    }

    /** 悬浮球玻璃圆（32）在屏幕上的位置，供对话浮层做 Q4「球 ↔ 浮层」变形；没有悬浮球时为 null。 */
    private fun publishOrbRect() {
        val lp = orbParams
        if (lp == null || orbView == null) {
            orbDiscRect = null
            return
        }
        val window = dpToPx(ORB_WINDOW_DP)
        val inset = (window - dpToPx(ORB_DISC_DP)) / 2
        val left = resources.displayMetrics.widthPixels - lp.x - window + inset
        val top = lp.y + inset
        orbDiscRect = android.graphics.Rect(left, top, left + dpToPx(ORB_DISC_DP), top + dpToPx(ORB_DISC_DP))
    }

    /** 从悬浮球打开对话浮层前登记起点：浮层从球的位置长出来（Q4）。 */
    private fun markSheetFromOrb() {
        publishOrbRect()
        orbDiscRect?.let { AgentConversationSheetActivity.expandFromOrb(it) }
    }

    internal companion object {
        @Volatile
        var orbDiscRect: android.graphics.Rect? = null
            private set

        /** 当前悬浮球位置；没有悬浮球时取默认停靠位置（右边缘距边 8、球心在 75% 屏高）。 */
        fun orbDiscRectOrDefault(context: Context): android.graphics.Rect {
            orbDiscRect?.let { return it }
            val metrics = context.resources.displayMetrics
            val disc = (ORB_DISC_DP * metrics.density).toInt()
            val edge = ((ORB_EDGE_DP + (ORB_WINDOW_DP - ORB_DISC_DP) / 2f) * metrics.density).toInt()
            val centerY = (metrics.heightPixels * 0.75f).toInt()
            val right = metrics.widthPixels - edge
            return android.graphics.Rect(right - disc, centerY - disc / 2, right, centerY + disc / 2)
        }

        const val ORB_DISC_DP = 32
        const val ACTION_KEEP_ALIVE = "io.github.fartown.movo.agent.runtime.KEEP_ALIVE"
        const val HIDE_DELAY_MS = 2_500L
        const val ORB_WINDOW_DP = 44
        const val ORB_EDGE_DP = 8
        const val PANEL_SHADOW_DP = 12
        const val BUBBLE_EXIT_MS = 150L
        const val PANEL_AUTO_COLLAPSE_MS = 4_000L
        const val IME_TRACK_INTERVAL_MS = 120L
        const val GLOW_FADE_MS = 300L
        const val ORB_EXIT_MS = 200L
        const val REMOVE_ZONE_DP = 48
        const val REMOVE_ZONE_SHADOW_DP = 12
        /** 移除区下缘距屏幕底部（避开手势条）。 */
        const val REMOVE_ZONE_BOTTOM_DP = 64
        const val RESULT_REVIEW_DELAY_MS = 120_000L
        const val MAX_ARCHIVED_USER_IMAGE_PREVIEWS = 4
    }

    private data class CompletedRunContext(
        val request: AgentRuntimeWire.RunRequest,
        val response: AgentModelClient.ModelResponse.Text,
    )

    private class RuntimeConfigUnavailableException : IllegalStateException()
}
