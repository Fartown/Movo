package io.github.fartown.movo.agent.runtime

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import android.app.Service
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import io.github.fartown.movo.ui.model.AgentInteractionUiState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
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
import io.github.fartown.movo.agent.overlay.InteractionCardCoordinator
import io.github.fartown.movo.agent.overlay.OrbGeometry
import io.github.fartown.movo.agent.overlay.OverlayLifecyclePolicy
import io.github.fartown.movo.agent.overlay.OverlayMonitor
import io.github.fartown.movo.agent.overlay.OverlayTaskPanel
import io.github.fartown.movo.agent.monitor.MonitorEnding
import io.github.fartown.movo.agent.monitor.MonitorInfo
import io.github.fartown.movo.agent.monitor.MonitorRegistry
import io.github.fartown.movo.agent.monitor.MonitorRegistryCore
import io.github.fartown.movo.agent.overlay.applyEvent
import io.github.fartown.movo.config.Prefs
import io.github.fartown.movo.agent.tools.interaction.AgentInteractionRegistry
import io.github.fartown.movo.agent.tools.interaction.InteractionReply
import io.github.fartown.movo.core.AndroidAgentLogger
import io.github.fartown.movo.core.ModuleConfig
import io.github.fartown.movo.core.safeLogType
import io.github.fartown.movo.core.toSafeLogToken
import io.github.fartown.movo.data.repository.RuntimeConfigRepository
import io.github.fartown.movo.flavor.FlavorModule
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
    private data class InstrumentationFixture(
        val session: AgentRuntimeSession,
        val leaseId: String,
        @Volatile var taskActive: Boolean = true,
        @Volatile var stopObserved: Boolean = false,
    )
    @Volatile private var instrumentationFixture: InstrumentationFixture? = null
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
    /** 打开补充输入时自动暂停了任务：发送或取消后自动继续（用户自己暂停的不自动继续）。 */
    private var pausedForTyping = false
    /** 下一次创建悬浮球时是否播进场；重建浮窗时为 false。 */
    private var orbEntrance = true
    /** 重建浮窗时沿用的悬浮球位置（用户可能拖过）。 */
    private var restoreOrbPosition: WindowManager.LayoutParams? = null
    private val onAccessibilityInstanceChanged: () -> Unit = {
        mainHandler.post(::onAccessibilityInstanceChangedOnMain)
    }
    private var glowView: ComposeView? = null
    private var orbView: ComposeView? = null
    private var bubbleView: ComposeView? = null
    private var glowParams: WindowManager.LayoutParams? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    /** 光晕正在随结束淡出（之后移除窗口）；有新的前台操作时撤销。 */
    private val glowRetired = mutableStateOf(false)
    private val glowRetireToken = Any()
    /**
     * 无障碍断开 / 重连时按新 context 重建浮窗失败（例如无障碍刚断开、又没有悬浮窗权限）：记下需要的浮层，
     * 下次无障碍实例变化时按原状态再建；否则悬浮球就此消失、不会自己回来。
     */
    private var pendingOverlayRestore: OverlayRestore? = null

    private data class OverlayRestore(
        val standby: Boolean,
        val glow: Boolean,
        val position: WindowManager.LayoutParams?,
    )

    /** 正在拖动悬浮球（可能从展开卡的球侧通道开始）：展开卡窗口等拖动结束再移除，否则这次拖动会被中断。 */
    private var orbDragging = false
    private var bubbleRemovalDeferred = false

    /**
     * 后台监听唤醒的一轮：悬浮层上有结果待查看（✓ / !）时，这一轮在需要前台操作之前不动悬浮层，
     * 自己的状态先记在这里；要操作其他 App（揭开悬浮层）时才接管。
     */
    private var backgroundRun: BackgroundRun? = null

    private class BackgroundRun(
        val session: AgentRuntimeSession,
        val conversationTarget: AgentConversationTarget?,
        val runId: String,
        var state: AgentOverlayState,
    )

    // 跨应用审批卡 / 提问卡的悬浮宿主（实施方案 §6.1 + overlay-approval-card-plan）：没有 App 内宿主（主界面、对话浮层 resumed）
    // 时浮在目标应用上。显示哪一张、在哪一处由 [InteractionCardCoordinator] 决定：同一张卡任何时候只在一处。
    private var interactionView: ComposeView? = null
    private var interactionParams: WindowManager.LayoutParams? = null
    /** 悬浮卡窗口取自哪个 context；与当前 [overlayContext] 不同（无障碍重连）时旧窗口已被系统移除，要重建。 */
    private var interactionOwner: Context? = null
    private var interactionWindowManager: WindowManager? = null
    /** 悬浮卡当前显示的是锁屏解锁提示（窗口只有卡片大小、不拦截卡片外的触摸，用户仍可在锁屏上解锁）。 */
    private val interactionLocked = mutableStateOf(false)
    private var interactionFocusable = false
    /** 亮屏 / 息屏 / 解锁：锁屏状态变了，悬浮卡在审批卡与解锁提示之间切换。 */
    private val keyguardReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            mainHandler.post { onKeyguardStateChanged(action) }
        }
    }
    /** 展开卡的原始位置（距屏幕底部）；键盘补充时抬到键盘上方，键盘收起后回到这里。 */
    private var bubbleBaseY = 0
    /**
     * 悬浮球玻璃圆当前在屏幕上的中心（px），供展开卡的揭开动画作为“起点圆心”。
     * 每次 orbParams 变化（新建、拖动、吸附、键盘避让、rebuild）都会更新一次；null 时表示没有悬浮球。
     */
    private val orbCenterOnScreen = mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)
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
    /** 任务结束后 ✓ / ! 保留到这个时刻（uptime），之后没有执行中任务就淡出悬浮球。 */
    private var resultOrbVisibleUntil = 0L
    /**
     * 运行中的后台监听（结束后等待撤销的不算）：没有在跑的一轮时有它们就是「监听中」（规范 8.12「22」）——任务还没完，
     * 悬浮球显示琥珀整环 + 时钟，「常驻悬浮球」关着也显示。
     */
    private val monitors = mutableStateOf<List<MonitorInfo>>(emptyList())
    /** 刚结束了任务和它的监听、还能撤销：展开卡原位显示「已结束·撤销」，到期收起。 */
    private val endedTask = mutableStateOf<OverlayTaskPanel.Ended?>(null)
    private val endedTaskToken = Any()
    /**
     * 监听自己结束（到时限、命令退出）后悬浮球再留一会儿（uptime）：自然结束会唤醒 Movo 说明一次，那一轮要接着用这颗球显示执行中、
     * 结束留 ✓（规范 8.12：到时限自然结束留 ✓）。「常驻悬浮球」关时才用得上。
     */
    private var monitorsEndedHoldUntil = 0L
    private val monitorsIdleToken = Any()
    private val orbFadeToken = Any()
    private val appLeaveToken = Any()
    private val orbPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != io.github.fartown.movo.agent.overlay.OrbPrefs.KEY_KEEP_ORB) return@OnSharedPreferenceChangeListener
        mainHandler.post {
            val keep = io.github.fartown.movo.agent.overlay.OrbPrefs.keepOrbAfterExit(this)
            when {
                keep && activeSession == null -> ensureStandbyOrb()
                // 关掉常驻、又没有任务：待命悬浮球没有用处，撤掉并停服务。
                !keep && activeSession == null && standby.value -> dismissAndStop()
            }
            updateStandbyOrbVisibility()
        }
    }
    /** 悬浮球退场（移除）时置 false，播完退场再移除窗口。 */
    private val orbShown = mutableStateOf(true)
    private val removeEngaged = mutableStateOf(false)
    private val removeZoneVisible = mutableStateOf(false)
    /**
     * 展开卡状态说明（规范 8.1 / 8.11：悬浮窗的失败提示写进展开卡，不用 Toast）：缺麦克风权限、语音没能开始、打不开结果对话。
     * 展开卡收起时清掉。
     */
    private val panelNotice = mutableStateOf<String?>(null)
    /** 最近一次从展开卡 / 长按悬浮球发起语音的时刻：之后几秒内语音服务报告的「没能开始」转述到展开卡。 */
    private var panelVoiceRequestedAt = 0L
    private var removeZoneView: ComposeView? = null
    /** 拖动中手指对应的悬浮球窗口位置（吸附到移除区时窗口不跟手，松开吸附后回到这里）。 */
    private var fingerX = 0f
    private var fingerY = 0f
    /** App 自己的页面进出前台时刷新待命悬浮球的显隐（前台判断用 [VoiceSurfaceTracker]，它从进程启动起就在计数）。 */
    private val appActivityCallbacks = object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: android.app.Activity) {
            mainHandler.removeCallbacksAndMessages(appLeaveToken)
            mainHandler.post {
                // 又回到 Movo 的页面：收回球里那次交接作废，按页面在前台藏球。
                if (sheetHandoff == SheetHandoff.RETURNED) setSheetHandoff(SheetHandoff.NONE) else updateStandbyOrbVisibility()
            }
        }
        override fun onActivityPaused(activity: android.app.Activity) {
            // 离开 Movo 的页面稍等再判断：Movo 页面之间切换（例如对话浮层「展开到 App」）时，旧页暂停到新页恢复之间有一段空档，
            // 立即判断会让悬浮球在全屏浮层上闪一下（真机约 10ms）。
            mainHandler.removeCallbacksAndMessages(appLeaveToken)
            mainHandler.postDelayed(::updateStandbyOrbVisibility, appLeaveToken, APP_LEAVE_SETTLE_MS)
        }
        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: android.app.Activity) = Unit
        override fun onActivityStopped(activity: android.app.Activity) = Unit
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: android.app.Activity) = Unit
    }
    private val panelIdleToken = Any()
    private var hasExecutedForegroundTool = false
    /** 这一轮悬浮球离开过待命、显示过执行中（用户在 Movo 外面能看到这一轮在跑）。 */
    private var runShownOnOrb = false
    private val supplementsLock = Any()
    private val activeSupplements = mutableListOf<AgentUiHandoffPayload.Supplement>()
    private var nextSupplementIndex = 1
    @Volatile
    private var lastCompletedRunContext: CompletedRunContext? = null
    private val hideToken = Any()

    override fun onCreate() {
        super.onCreate()
        liveInstance = this
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        application.registerActivityLifecycleCallbacks(appActivityCallbacks)
        io.github.fartown.movo.agent.overlay.OrbPrefs.prefs(this).registerOnSharedPreferenceChangeListener(orbPrefsListener)
        AgentAccessibilityService.addInstanceListener(onAccessibilityInstanceChanged)
        // 语音对话与悬浮窗联动（规范 8.5）：执行中语音开始时展开卡以语音模式弹出；语音结束后展开卡恢复自动收起。
        lifecycleScope.launch {
            VoiceSessionManager.state.map { it.active }.distinctUntilChanged().collect(::onVoiceActiveChanged)
        }
        // 语音服务在后台没能转前台（例如系统不允许从后台启动麦克风）时只会写输入框上方的提示；
        // 刚从展开卡发起的，把原因转述到展开卡（此时屏幕上没有 Movo 的输入框）。
        lifecycleScope.launch {
            VoiceSessionManager.state.map { it.notice }.distinctUntilChanged().collect { notice ->
                val recent = android.os.SystemClock.uptimeMillis() - panelVoiceRequestedAt <= PANEL_VOICE_NOTICE_WINDOW_MS
                if (notice != null && recent && !VoiceSessionManager.active) showPanelNotice(notice)
            }
        }
        // 审批卡 / 提问卡：待作答的卡、App 内宿主（主界面 / 对话浮层 resumed）、「去解锁」任一变化都重新决定悬浮卡显隐，
        // 前后台切换时卡片在 App 内与悬浮窗之间迁移。
        lifecycleScope.launch { InteractionCardCoordinator.pending.collect { syncInteractionOverlay() } }
        lifecycleScope.launch { InteractionCardCoordinator.activeHost.collect { syncInteractionOverlay() } }
        lifecycleScope.launch { InteractionCardCoordinator.unlockInProgress.collect { syncInteractionOverlay() } }
        // 后台监听增减：悬浮球在「监听中」与待命 / 完成之间切换（规范 8.12「22」）。
        lifecycleScope.launch { MonitorRegistry.active.collect(::onMonitorsChanged) }
        // 在别处撤销（App 里的结束行、常驻通知）或期满：展开卡上的「已结束·撤销」随之收掉。
        lifecycleScope.launch { MonitorRegistry.endings.collect(::onEndingsChanged) }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                keyguardReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != AgentRuntimeWire.ACTION_BIND) return null
        return serviceMessenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STANDBY_ORB) {
            // 常驻悬浮球：有任务时沿用任务的悬浮球；没有任务时建待命悬浮球，建不出来（没有无障碍也没有悬浮窗权限）就停。
            if (activeSession == null && !ensureStandbyOrb()) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_KEEP_ALIVE || activeSession == null) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * 「常驻悬浮球」开（默认，[io.github.fartown.movo.agent.overlay.OrbPrefs]）时，没有任务也保留一个待命悬浮球：
     * 只有光球、没有光晕与状态环；Movo 自己在前台时藏起（[updateStandbyOrbVisibility]）。返回是否有悬浮球。
     */
    private fun ensureStandbyOrb(evenIfNotKept: Boolean = false): Boolean {
        if (!evenIfNotKept && !io.github.fartown.movo.agent.overlay.OrbPrefs.keepOrbAfterExit(this)) return false
        // 上次重建失败留下的浮层（可能带着待查看的结果）优先按原状态恢复，不被待命外观覆盖。
        if (orbView == null && pendingOverlayRestore != null) restorePendingOverlay()
        if (orbView != null) return true
        val wasVisible = VoiceSurfaceTracker.appVisible
        // App 在前台时建：先不播进场，离开 App 时直接出现在原位。
        orbEntrance = !wasVisible
        showOverlay()
        orbEntrance = true
        if (orbView == null) return false
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView = null
        glowParams = null
        state.value = AgentOverlayState.Initial
        standby.value = true
        updateStandbyOrbVisibility()
        return true
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
        finishInstrumentationFixture()
        if (liveInstance === this) liveInstance = null
        application.unregisterActivityLifecycleCallbacks(appActivityCallbacks)
        io.github.fartown.movo.agent.overlay.OrbPrefs.prefs(this).unregisterOnSharedPreferenceChangeListener(orbPrefsListener)
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
        backgroundRun = null
        pendingOverlayRestore = null
        runCatching { unregisterReceiver(keyguardReceiver) }
        resultIo.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        // 服务停了就没有在等作答的 run：卡片两处都收起。悬浮卡用它自己的窗口管理器移除（dismissAndStop 之后 windowManager 已为空），
        // 否则会留下一个全屏、可获焦的透明窗口挡住触摸。
        InteractionCardCoordinator.clearRun(null)
        removeInteractionOverlay()
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

                AgentRuntimeWire.MSG_INTERACTION_REPLY -> {
                    val (runId, requestId, reply) =
                        AgentRuntimeWire.interactionReplyFromBundle(msg.data ?: return) ?: return
                    val delivered = AgentInteractionRegistry.deliver(runId, requestId, reply)
                    AndroidAgentLogger.info(
                        "Agent runtime interaction reply: delivered=$delivered, request=${requestId.toSafeLogToken()}"
                    )
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
            // 后台监听唤醒的一轮与语音同样不得取代正在执行的任务：忙时返回 BUSY，由 App 层排队稍后再发。
            AgentRuntimeAdmission.Owner(request.runId, request.voiceSessionId.isNotBlank() || request.isMonitorOrigin),
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
                                    io.github.fartown.movo.agent.model.UserFacingFailure.MODEL_UNAVAILABLE
                                else -> "Agent Runtime 无法准备请求"
                            },
                            replyTo,
                            incoming.request.runId,
                            request = incoming.request,
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
        val conversationTarget = AgentConversationTarget.from(request.handoff)
        // 后台监听唤醒的一轮：悬浮层上有结果待查看（✓ / !）时先不动它——点开查看的目标、展开卡、外观都保留，
        // 这一轮要操作其他 App（揭开悬浮层）时才接管。isIdle 的语义不变：仍是「没有在跑的 run」。
        val keepPendingResult = OverlayLifecyclePolicy.keepsPendingResult(
            monitorOrigin = request.isMonitorOrigin,
            fromResultCard = fromResultCard,
            resultPending = hasPendingResult(),
        )
        if (!keepPendingResult) {
            clearResultHandoff()
            resultConversationTarget = conversationTarget
            resultConversationRunId = request.runId
        }
        // 只有 App 自己发起、并订阅着的一轮（replyTo 非空）才能并入后台监听事件；悬浮层「继续」发起的续跑没人订阅，
        // 事件并进去 App 看不到，也补不回事件行。
        activeRunConversation = conversationTarget
            ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE && replyTo != null }
            ?.let { request.runId to it.key }
        isResultConversation = fromResultCard || FlavorModule.surfaces.isConversationVisible(conversationTarget)
        activeSession?.controller?.cancel()
        // 被替换的任务若在等审批 / 提问，它的等待已随取消结束：卡片（App 内与悬浮）一并收起，不等它迟到的「已处理」。
        InteractionCardCoordinator.clearRun(activeSession?.runId)
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
            this, "run:${request.runId}", allowBoundFallback = allowBoundFallback, task = request.runId,
        ) { session.controller.cancel() }
        if (!executionHeld && !allowBoundFallback) {
            session.complete(AgentRuntimeWire.RunResult(
                runId = request.runId, ok = false, content = "",
                error = "无法启动后台执行服务，请返回 Movo 后重试",
            )) {}
            return
        }
        activeSession = session
        if (!keepPendingResult) lastCompletedRunContext = null
        runCatching {
            startService(Intent(this, AgentRuntimeService::class.java).setAction(ACTION_KEEP_ALIVE))
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_keep_alive_start_failed") {
                "Agent runtime keep-alive start failed: type=${throwable.safeLogType()}"
            }
        }
        mainHandler.removeCallbacksAndMessages(hideToken)
        hasExecutedForegroundTool = false
        runShownOnOrb = false
        backgroundRun = if (keepPendingResult) {
            BackgroundRun(session, conversationTarget, request.runId, AgentOverlayState.Initial)
        } else {
            state.value = AgentOverlayState.Initial
            // 上一轮开着的展开卡（例如失败原因）要真正收起，不能只改标记：否则窗口还在、悬浮球却按「已收起」显示。
            pausedForTyping = false
            collapseBubble()
            null
        }
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
        // 在主线程取得独立使用权，覆盖工具调用之间的模型等待；旧 run 的 finally
        // 只释放自己的使用权，不会暂停刚开始的新 run。没有浏览器时也不会创建 WebView。
        val browserUse = io.github.fartown.movo.agent.browser.AgentBrowserSession.keepActive()
        try {
            thread(name = "agent-runtime") {
                try {
                    executeRun(session, request)
                } finally {
                    browserUse.close()
                    AgentExecutionService.release("run:${request.runId}")
                }
            }
        } catch (error: Throwable) {
            browserUse.close()
            AgentExecutionService.release("run:${request.runId}")
            throw error
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
        // 「已处理」不论这一轮是否已结束或被替换都要收卡：从通知栏停止、被新任务替换时悬浮卡不残留。
        // 与「请求作答」走同一个主线程队列，先后不乱（不会先收后出、留下一张没人等的卡）。
        if (event is AgentEvent.InteractionResolved) {
            mainHandler.post { InteractionCardCoordinator.resolve(event.requestId) }
        }
        if (activeSession !== session) return
        val revealsForegroundOperation = AgentOverlayVisibilityPolicy.shouldRevealFor(event)
        val requiresEntrySurfaceDismissal =
            AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event)
        val entrySurfaceReady = (!requiresEntrySurfaceDismissal || entrySurfaceGuard == null ||
            runCatching { entrySurfaceGuard.dismissOnce() }.getOrDefault(false)) &&
            (!requiresEntrySurfaceDismissal || !isResultConversation ||
                FlavorModule.surfaces.hideConversationForDeviceOperation())
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
                if (event is AgentEvent.InteractionRequested) publishInteraction(session, event)
                val background = backgroundRun?.takeIf { it.session === session }
                if (background != null) {
                    // 后台监听的一轮：先只记在自己的状态里，悬浮层继续显示上一轮待查看的结果。
                    background.state = background.state.applyEvent(event)
                    if (!(revealsForegroundOperation && entrySurfaceReady)) return@runCatching
                    // 要操作其他 App 了：这一轮接管悬浮层。
                    takeOverOverlay(background)
                } else {
                    val phaseBefore = state.value.phase
                    state.value = state.value.applyEvent(event)
                    // 用户发起的任务在跑：常驻悬浮球离开待命、显示执行中（规范 8.1：没有任务时才是待命）。
                    // 不操作其他 App 的任务也一样，否则用户在别的 App 里看到的球没有任何状态、点了是打开浮层（球随之藏起）、
                    // 完成也没有 ✓（真机反馈）。光晕、收起入口窗口仍只在操作其他 App 时做（下面的 reveal 分支）。
                    if (standby.value && orbView != null) {
                        standby.value = false
                        runShownOnOrb = true
                        updateStandbyOrbVisibility()
                    }
                    // ✓ / ! 可能先由事件流（RunFinished / RunFailed）点亮，早于终态交付：从这一刻起算 3 秒保留（常驻关闭时）。
                    val phaseAfter = state.value.phase
                    if (phaseAfter != phaseBefore &&
                        (phaseAfter == AgentOverlayPhase.FINISHED || phaseAfter == AgentOverlayPhase.FAILED)
                    ) {
                        scheduleResultOrbHide()
                        updateStandbyOrbVisibility()
                    }
                }
                if (revealsForegroundOperation && entrySurfaceReady) {
                    if (orbView == null) {
                        AgentHapticFeedback.perform(
                            this,
                            AgentHapticFeedback.Type.RUN_STARTED,
                        )
                    }
                    // 已常驻（待命）时不重新弹出，状态环与角标原地交叉淡化为执行中（规范 9.5）。
                    standby.value = false
                    runShownOnOrb = true
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
            // 这一轮结束了，它的卡不会再有人等：不论会话是否已被替换都收起（作答的「已处理」可能因会话已结束被丢掉）。
            InteractionCardCoordinator.clearRun(session.runId)
            if (activeSession !== session) return@post
            activeSession = null
            if (backgroundRun?.session === session) {
                // 后台监听的一轮没有操作其他 App：结果在对话里，悬浮层保持原样（上一轮的结果仍待查看，或用户已看过回到待命）。
                backgroundRun = null
                stopIfOverlayUnneeded()
                return@post
            }
            lastCompletedRunContext = completedContext
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
        request: AgentRuntimeWire.RunRequest? = null,
    ) {
        sendResultTo(
            replyTo,
            AgentRuntimeWire.RunResult(runId = runId, ok = false, content = "", error = message, resultKind = "rejected"),
        )
        if (activeSession != null) return
        // 后台监听的一轮没准备好：失败原因在对话里，悬浮层不动（上一轮的结果仍待查看）。
        if (request?.isMonitorOrigin == true) return
        // 这是新的一轮：不沿用上一轮的前台执行标记和会话目标，否则会弹「!」、点「查看」打开上一轮的对话。
        resetRunOverlayContext(request, runId)
        enterFinalState(
            AgentOverlayState(
                phase = AgentOverlayPhase.FAILED,
                status = AgentOverlayStatus.RunFailed,
                detailText = message
            )
        )
    }

    /** 新的一轮开始（含准备失败的一轮）：前台执行标记、结果对话目标都归这一轮，不沿用上一轮的。 */
    private fun resetRunOverlayContext(request: AgentRuntimeWire.RunRequest?, runId: String) {
        clearResultHandoff()
        hasExecutedForegroundTool = false
        runShownOnOrb = false
        isResultConversation = false
        resultConversationTarget = AgentConversationTarget.from(request?.handoff)
        resultConversationRunId = runId.ifBlank { null }
        lastCompletedRunContext = null
        backgroundRun = null
    }

    /**
     * 展开卡「结束任务」：这一轮和这个对话里的后台监听一起结束（规范 8.12「结束」），不确认，5 秒内可撤销；
     * 没有在跑的一轮、只有监听时结束所有监听。
     */
    private fun requestStop() {
        val session = activeSession
        if (session == null) {
            if (monitors.value.isNotEmpty()) endMonitorTask() else dismissAndStop()
            return
        }
        cancelRun(session.runId)
        val conversationId = monitorConversationOf(
            backgroundRun?.takeIf { it.session === session }?.conversationTarget ?: resultConversationTarget,
        ) ?: return
        beginTaskEnding { it.conversationId == conversationId }
    }

    /** App 对话的 id（后台监听按它归属）；外部入口的会话没有监听。 */
    private fun monitorConversationOf(target: AgentConversationTarget?): String? =
        target?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }?.key

    /** [target] 这个对话还有监听在等：这件事没完，这一轮答完是「监听中」不是 ✓（规范 8.12「任务与状态」）。 */
    private fun taskMonitored(target: AgentConversationTarget?): Boolean {
        val conversationId = monitorConversationOf(target) ?: return false
        return monitors.value.any { it.conversationId == conversationId }
    }

    /** 监听中点「结束任务」：结束所有运行中的监听。 */
    private fun endMonitorTask() {
        beginTaskEnding { true }
    }

    private fun beginTaskEnding(predicate: (MonitorInfo) -> Boolean) {
        val ending = MonitorRegistry.endLater(predicate) ?: return
        showEndedPanel(ending)
    }

    /** 展开卡原位换成「已结束·撤销」，[MonitorRegistryCore.END_UNDO_MS] 后收起（规范 8.12「撤销」）。 */
    private fun showEndedPanel(ending: MonitorEnding) {
        endedTask.value = OverlayTaskPanel.Ended(ending.id, ending.names)
        mainHandler.removeCallbacksAndMessages(endedTaskToken)
        mainHandler.postDelayed({ finishEndedPanel(ending.id) }, endedTaskToken, MonitorRegistryCore.END_UNDO_MS)
        if (orbView != null && collapsed.value) expandBubble()
    }

    private fun onEndingsChanged(endings: List<MonitorEnding>) {
        val ended = endedTask.value ?: return
        if (endings.any { it.id == ended.endingId }) return
        mainHandler.removeCallbacksAndMessages(endedTaskToken)
        endedTask.value = null
        if (activeSession != null) return
        // 撤销了（监听还在）：卡片回到「监听中」，稍后自动收起；期满：收起。
        if (MonitorRegistry.active.value.isNotEmpty()) {
            scheduleBubbleAutoCollapse()
        } else {
            collapseBubble()
            updateStandbyOrbVisibility()
            stopIfOverlayUnneeded()
        }
    }

    private fun finishEndedPanel(endingId: String) {
        if (endedTask.value?.endingId != endingId) return
        endedTask.value = null
        if (activeSession == null) collapseBubble()
        updateStandbyOrbVisibility()
        stopIfOverlayUnneeded()
    }

    /** 撤销：监听照常继续（已经停下的那一轮不恢复），卡片回到「监听中」，稍后自动收起。 */
    private fun undoTaskEnding() {
        val ended = endedTask.value ?: return
        mainHandler.removeCallbacksAndMessages(endedTaskToken)
        endedTask.value = null
        AndroidAgentLogger.info("Monitor ending undo: source=overlay")
        MonitorRegistry.undoEnding(ended.endingId)
        scheduleBubbleAutoCollapse()
    }

    /** 展开卡在没有在跑的一轮时显示什么：刚结束（可撤销）优先，其次监听中；有一轮在跑时按这一轮显示（null）。 */
    private fun taskPanel(): OverlayTaskPanel? {
        endedTask.value?.let { return it }
        val running = monitors.value
        if (running.isEmpty() || !standby.value) return null
        return OverlayTaskPanel.Monitoring(running.map { OverlayMonitor(it.name, it.eventCount, it.deadlineAtMillis) })
    }

    /**
     * 后台监听增减。有了：没有悬浮球就建一颗（「常驻悬浮球」关着也要，任务还没完；用户本次亲手移除过除外）。
     * 都没了：卡片若正显示监听中就收起；自己结束的（到时限、命令退出）悬浮球再留一会儿，等唤醒的那一轮接着用。
     */
    private fun onMonitorsChanged(list: List<MonitorInfo>) {
        val had = monitors.value.isNotEmpty()
        monitors.value = list
        mainHandler.removeCallbacksAndMessages(monitorsIdleToken)
        AndroidAgentLogger.info("Agent orb monitors: count=${list.size}, had=$had, orb=${orbView != null}, run=${activeSession != null}")
        if (list.isNotEmpty()) {
            ensureMonitoringOrb()
            updateStandbyOrbVisibility()
            return
        }
        if (!had) return
        if (endedTask.value == null && MonitorRegistry.endings.value.isEmpty()) {
            monitorsEndedHoldUntil = android.os.SystemClock.uptimeMillis() + MONITORS_ENDED_HOLD_MS
            mainHandler.postAtTime({
                updateStandbyOrbVisibility()
                stopIfOverlayUnneeded()
            }, monitorsIdleToken, monitorsEndedHoldUntil + 1)
        }
        if (endedTask.value == null && activeSession == null && standby.value && !collapsed.value) collapseBubble()
        updateStandbyOrbVisibility()
    }

    /**
     * 还有监听在跑、又没有悬浮球（「常驻悬浮球」关、这一轮没建过球）：建一颗显示监听中——任务还没完，常驻关也显示（规范 8.1）。
     * 有一轮在跑时等它结束再说；用户本次亲手移除过除外。返回是否有悬浮球。
     */
    private fun ensureMonitoringOrb(): Boolean {
        if (orbView != null) return true
        if (activeSession != null || pendingStartRequest != null) return false
        if (MonitorRegistry.active.value.isEmpty() || io.github.fartown.movo.agent.overlay.OrbPrefs.isRemovedByUser) return false
        return ensureStandbyOrb(evenIfNotKept = true)
    }

    /** 监听中点键盘 / 语音：打开最近开始的那个监听所在的对话（[autoListen] = 直接进语音）。 */
    private fun openMonitorConversation(autoListen: Boolean) {
        val conversationId = monitors.value.maxByOrNull { it.startedAtMillis }?.conversationId ?: return
        if (resultConversationOpening.value) return
        collapseBubble()
        markSheetFromOrb()
        val token = Any()
        resultHandoffToken = token
        resultConversationOpening.value = true
        val receiver = object : ResultReceiver(mainHandler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (resultHandoffToken !== token) return
                if (resultCode == AgentConversationHandoff.RESULT_READY) clearResultHandoff() else failResultHandoff(token, notify = false)
            }
        }
        sendConversationIntent(
            AgentConversationTarget(AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, conversationId),
            runId = null,
            receiver = receiver,
            autoListen = autoListen,
            token = token,
        )
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
        pausedForTyping = false
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

    /** 最近一次启动的 run 所属的 App 对话（runId → conversationId），用于把后台监听事件并入同一对话的这一轮。 */
    @Volatile private var activeRunConversation: Pair<String, String>? = null

    private fun injectMonitorEventOnService(
        conversationId: String,
        expectedRunId: String,
        modelText: String,
        events: List<AgentEvent>,
    ): Boolean {
        val session = activeSession?.takeUnless { it.isTerminal } ?: return false
        val (runId, runConversation) = activeRunConversation ?: return false
        // 三方核对：服务记的这一轮、正在跑的会话、App 以为自己订阅着的那一轮，必须是同一个。
        if (runId != session.runId || runId != expectedRunId || runConversation != conversationId) return false
        return session.injectMonitorEvent(modelText, events)
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
        if (!FlavorModule.runSurface.usesRuntimeWindows) return
        if (orbView != null && overlayOwner !== overlayContext()) rebuildOverlayIfOwnerChanged()
        if (orbView != null) {
            // 悬浮球已常驻：只补上边缘光晕（正在淡出的撤销淡出）。
            windowManager?.let(::showGlow)
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
            FlavorModule.runSurface.Orb(
                mode = orbMode(
                    state.value.phase, standby.value, voice.active,
                    stopped = state.value.status == AgentOverlayStatus.Stopped,
                    monitoring = monitors.value.isNotEmpty(),
                    taskMonitored = taskMonitored(resultConversationTarget),
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
                // 展开卡出现时悬浮球留在原处，卡片从球心长出来（规范 8.1 / 9.5，2026-09-27 定）。
                // 10-05 改成过「球淡出、像一颗球在变形」，真机看就是点了球、球没了，卡片也不像从球里出来，已撤回。
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
        // 加不上就按收起处理：悬浮球不能因为「展开中」一直淡在透明。
        if (!collapsed.value && !showBubble(wm)) {
            collapsed.value = true
        }
    }

    /** 无障碍实例变化（连上 / 断开 / 重连成新实例）：浮窗按新 context 重建；之前重建失败的浮层此时再建一次。 */
    private fun onAccessibilityInstanceChangedOnMain() {
        rebuildOverlayIfOwnerChanged()
        restorePendingOverlay()
        // 审批卡 / 提问卡：旧实例名下的悬浮卡窗口已被系统移除，按新 context 重建（不然之后的新卡都加不出来，只能等超时）。
        syncInteractionOverlay()
    }

    /**
     * 无障碍服务重连（或断开、恢复）后，旧实例名下的浮窗已被系统移除，旧 WindowManager 再加窗口会
     * BadTokenException（光晕、展开卡加不上，悬浮球消失）。用当前可用的 context 按原状态重建。
     */
    private fun rebuildOverlayIfOwnerChanged() {
        if (orbView == null || overlayOwner === overlayContext()) return
        AndroidAgentLogger.debug { "Agent runtime overlay owner changed; rebuilding overlay windows" }
        val wasStandby = standby.value
        // 正在淡出的光晕不重建（它马上就要撤掉）。
        val hadGlow = glowView != null && !glowRetired.value
        // 先用新的 context 加好新窗口，再撤旧窗口：旧窗口还在屏幕上时（例如从普通悬浮窗换回无障碍浮窗）不留空档；
        // 新球沿用原位置、不播进场。
        val oldManager = windowManager
        val oldViews = listOfNotNull(orbView, bubbleView, glowView, removeZoneView)
        val previousPosition = orbParams
        restoreOrbPosition = orbParams
        mainHandler.removeCallbacksAndMessages(glowRetireToken)
        glowRetired.value = false
        bubbleRemovalDeferred = false
        removeZoneView = null
        orbView = null
        bubbleView = null
        glowView = null
        orbParams = null
        bubbleParams = null
        glowParams = null
        orbDiscRect = null
        orbCenterOnScreen.value = null
        standby.value = false
        windowManager = null
        overlayOwner = null
        orbEntrance = false
        showOverlay()
        orbEntrance = true
        restoreOrbPosition = null
        oldViews.forEach { view -> runCatching { oldManager?.removeView(view) } }
        if (orbView == null) {
            // 新 context 加不上窗口（例如无障碍刚断开、又没有悬浮窗权限）：记下需要的浮层，下次无障碍实例变化时再建。
            pendingOverlayRestore = OverlayRestore(standby = wasStandby, glow = hadGlow, position = previousPosition)
            collapsed.value = true
            bubbleVisible.value = true
            return
        }
        finishOverlayRestore(standby = wasStandby, glow = hadGlow)
    }

    /** 之前重建失败（[pendingOverlayRestore]）的浮层：按原状态（位置、待命、光晕）再建一次，仍建不出就继续等下次。 */
    private fun restorePendingOverlay() {
        val restore = pendingOverlayRestore ?: return
        if (orbView != null) {
            pendingOverlayRestore = null
            return
        }
        restoreOrbPosition = restore.position
        orbEntrance = false
        showOverlay()
        orbEntrance = true
        restoreOrbPosition = null
        if (orbView == null) return
        AndroidAgentLogger.info("Agent runtime overlay restored after accessibility change")
        finishOverlayRestore(standby = restore.standby, glow = restore.glow)
    }

    private fun finishOverlayRestore(standby: Boolean, glow: Boolean) {
        pendingOverlayRestore = null
        if (!glow) {
            glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
            glowView = null
            glowParams = null
        }
        if (standby) this.standby.value = true
        // 重建出来的球默认可见：Movo 自己在前台时要重新按规则藏起来。
        updateStandbyOrbVisibility()
    }

    /** 氛围光窗口：全屏触摸穿透，彩虹光圈，截图时被 takeScreenshotOfWindow 过滤。 */
    private fun showGlow(wm: WindowManager) {
        if (glowView != null) {
            // 正在随上一轮结束淡出：又有前台操作了，撤销淡出、照常流动。
            unretireGlow()
            return
        }
        mainHandler.removeCallbacksAndMessages(glowRetireToken)
        glowRetired.value = false
        val glow = createOverlayComposeView {
            // 淡出中按结束态画（透明度随 `standard` 降到 0），不随状态复位成执行中重新满亮度流动。
            val current = state.value
            FlavorModule.runSurface.Glow(
                state = if (glowRetired.value) current.copy(phase = AgentOverlayPhase.FINISHED) else current,
            )
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

    /** 光晕随这一轮结束淡出（`standard`），播完移除窗口；期间再有前台操作会撤销（[unretireGlow]）。 */
    private fun retireGlow() {
        val view = glowView ?: return
        glowRetired.value = true
        mainHandler.removeCallbacksAndMessages(glowRetireToken)
        mainHandler.postDelayed({
            if (glowView === view && glowRetired.value) {
                runCatching { windowManager?.removeView(view) }
                glowView = null
                glowParams = null
            }
        }, glowRetireToken, GLOW_FADE_MS)
    }

    private fun unretireGlow() {
        if (!glowRetired.value) return
        mainHandler.removeCallbacksAndMessages(glowRetireToken)
        glowRetired.value = false
    }

    /**
     * 点悬浮球：待命时打开对话浮层；执行中 / 暂停时展开或收起展开卡；完成 / 失败时打开对话浮层查看结果
     * （规范 8.1：完成后保持 ✓，点开才看结果，不自动弹出）。
     */
    private fun onOrbTapped() {
        if (standby.value) {
            // 监听中（或刚结束、还能撤销）：展开卡显示监听，与执行中点球一致（规范 8.12「22」）。
            if (monitors.value.isNotEmpty() || endedTask.value != null) {
                toggleCollapse()
                return
            }
            markSheetFromOrb()
            MovoAssistantVoiceService.showAssistant(this, autoListen = false)
            return
        }
        val phase = state.value.phase
        if ((phase == AgentOverlayPhase.FINISHED || phase == AgentOverlayPhase.FAILED) && !foregroundRunActive()) {
            collapseBubble()
            markSheetFromOrb()
            openResultConversation()
            return
        }
        toggleCollapse()
    }

    /** 有占着悬浮层的任务在跑（不算还没接管悬浮层的后台监听轮次：那时悬浮层仍显示上一轮待查看的结果）。 */
    private fun foregroundRunActive(): Boolean {
        val session = activeSession ?: return false
        return backgroundRun?.session !== session
    }

    /** 悬浮层上有结果待查看：✓ / !（或用户停止后保留的结果），点开打开结果对话。 */
    private fun hasPendingResult(): Boolean =
        OverlayLifecyclePolicy.resultPending(
            orbPresent = orbView != null,
            standby = standby.value,
            foregroundRunActive = foregroundRunActive(),
            hasResultTarget = resultConversationTarget != null,
            phase = state.value.phase,
        )

    /** 后台监听的一轮要操作其他 App 了：接管悬浮层，上一轮待查看的结果让位（结果仍在对话里）。 */
    private fun takeOverOverlay(background: BackgroundRun) {
        backgroundRun = null
        clearResultHandoff()
        resultConversationTarget = background.conversationTarget
        resultConversationRunId = background.runId
        lastCompletedRunContext = null
        state.value = background.state
        pausedForTyping = false
        collapseBubble()
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
            !foregroundRunActive() && (phase == AgentOverlayPhase.FINISHED || phase == AgentOverlayPhase.FAILED) -> {
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
        panelNotice.value = null
        // 没能开始的原因写进展开卡（规范 8.1 / 8.11，不用 Toast）；展开卡此时已展开。
        when (VoiceEntry.startInPlace(this, showNotice = false)) {
            VoiceEntry.StartResult.STARTED -> panelVoiceRequestedAt = android.os.SystemClock.uptimeMillis()
            VoiceEntry.StartResult.ALREADY_ACTIVE -> Unit
            VoiceEntry.StartResult.MIC_PERMISSION_REQUIRED -> showPanelNotice(getString(R.string.movo_overlay_mic_required))
            VoiceEntry.StartResult.CLOSING -> showPanelNotice(VoiceEntry.CLOSING_NOTICE)
            VoiceEntry.StartResult.FAILED -> {
                AndroidAgentLogger.warn("Overlay voice start failed")
                showPanelNotice(getString(R.string.movo_overlay_voice_failed))
            }
        }
    }

    /** 在展开卡里说明原因：没展开就展开（规范 8.1 展开卡状态行）。 */
    private fun showPanelNotice(message: String) {
        panelNotice.value = message
        if (collapsed.value) expandBubble() else scheduleBubbleAutoCollapse()
    }

    private fun toggleCollapse() {
        if (collapsed.value) expandBubble() else collapseBubble()
    }

    private fun expandBubble() {
        // Movo 自己的界面在前台时悬浮球藏着，展开卡也不出现（例如 App 内开始语音、失败自动弹出）。
        if (VoiceSurfaceTracker.appVisible) return
        // 窗口加好了才算展开：没有窗口管理器或加窗失败时保持收起，悬浮球不会因「展开中」一直淡在透明。
        val wm = windowManager ?: return
        mainHandler.removeCallbacksAndMessages(bubbleRemovalToken)
        bubbleRemovalDeferred = false
        // 卡片从球心长出来：起点取球此刻在屏幕上的位置。
        publishOrbRect()
        bubbleVisible.value = true
        if (bubbleView == null && !showBubble(wm)) {
            collapsed.value = true
            return
        }
        collapsed.value = false
        scheduleBubbleAutoCollapse()
    }

    /** 收起：先让展开卡缩回球心（250ms），再移除窗口。 */
    private fun collapseBubble() {
        val wasOpen = !collapsed.value
        collapsed.value = true
        mainHandler.removeCallbacksAndMessages(panelIdleToken)
        if (wasOpen) mainHandler.post { updateStandbyOrbVisibility() }
        val view = bubbleView ?: run { panelNotice.value = null; return }
        bubbleVisible.value = false
        setBubbleInputMode(focusable = false)
        mainHandler.postDelayed({
            if (collapsed.value && bubbleView === view) {
                // 从展开卡的球侧通道开始的拖动还没结束：移除窗口会中断这次拖动，等拖动结束再移除。
                if (orbDragging) {
                    bubbleRemovalDeferred = true
                } else {
                    removeCollapsedBubble()
                }
            }
        }, bubbleRemovalToken, BUBBLE_EXIT_MS)
    }

    private fun removeCollapsedBubble() {
        bubbleRemovalDeferred = false
        val view = bubbleView ?: return
        if (!collapsed.value) return
        runCatching { windowManager?.removeView(view) }
        bubbleView = null
        bubbleParams = null
        panelNotice.value = null
    }

    /** 展开卡 4s 无操作自动收回；暂停态、输入补充时不收回（规范 8.1）。 */
    private fun scheduleBubbleAutoCollapse() {
        mainHandler.removeCallbacksAndMessages(panelIdleToken)
        mainHandler.postDelayed({
            val lp = bubbleParams
            val typing = lp != null && lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
            if (!collapsed.value && state.value.phase == AgentOverlayPhase.RUNNING && !typing && !VoiceSessionManager.active &&
                endedTask.value == null
            ) {
                collapseBubble()
            }
        }, panelIdleToken, PANEL_AUTO_COLLAPSE_MS)
    }

    private val bubbleRemovalToken = Any()

    /** 加展开卡窗口；返回展开卡窗口是否在（已有或刚加上）。 */
    private fun showBubble(wm: WindowManager): Boolean {
        if (bubbleView != null) return true
        val bubble = createOverlayComposeView {
            FlavorModule.runSurface.Bubble(
                state = state.value,
                onCollapse = ::collapseBubble,
                onPause = ::requestPause,
                onResume = ::requestResume,
                onStop = ::requestStop,
                onSupplementModeChange = ::setBubbleInputMode,
                onSupplement = ::requestSupplement,
                onSupplementKeyboardRequested = ::onSupplementKeyboardRequested,
                anchorEnd = orbOnEnd.value,
                visible = bubbleVisible.value,
                onInteraction = ::scheduleBubbleAutoCollapse,
                voice = VoiceSessionManager.state.collectAsState().value,
                onStartVoice = ::startPanelVoice,
                onEndVoice = VoiceSessionManager::switchToText,
                onOpenResult = ::onOrbTapped,
                notice = panelNotice.value,
                orbCenterOnScreen = { orbCenterOnScreen.value },
                orbLiftPx = { ((bubbleParams?.y ?: bubbleBaseY) - bubbleBaseY).toFloat() },
                // 展开卡的球侧通道盖住了隐形的真球：落在球上的点按、长按、拖动按悬浮球处理。
                onOrbTap = ::onOrbTapped,
                onOrbLongPress = ::onOrbLongPressed,
                onOrbDragStart = ::onOrbDragStart,
                onOrbDrag = ::handleDrag,
                onOrbDragEnd = ::onOrbDragEnd,
                taskPanel = taskPanel(),
                onEndMonitors = ::endMonitorTask,
                onUndoEnd = ::undoTaskEnding,
                onOpenMonitorConversation = ::openMonitorConversation,
            )
        }
        val lp = bubbleLayoutParams()
        runCatching { wm.addView(bubble, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_bubble_add_view_failed") {
                "Agent runtime bubble addView failed: type=${throwable.safeLogType()}"
            }
            return false
        }
        bubbleView = bubble
        bubbleParams = lp
        bubbleBaseY = lp.y
        bubbleTargetY = lp.y
        return true
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

    /** 上次读到的键盘高度（距屏幕底）；下次打开补充输入时先按它上移，不等输入法窗口出现。 */
    private var lastImeHeight: Int
        get() = imeHeightCache.takeIf { it > 0 }
            ?: getSharedPreferences(OVERLAY_PREFS, Context.MODE_PRIVATE).getInt(PREF_IME_HEIGHT, 0).also { imeHeightCache = it }
        set(value) {
            if (value == imeHeightCache) return
            imeHeightCache = value
            getSharedPreferences(OVERLAY_PREFS, Context.MODE_PRIVATE).edit().putInt(PREF_IME_HEIGHT, value).apply()
        }
    private var bubbleYAnimator: android.animation.ValueAnimator? = null
    private var bubbleTargetY = 0

    private fun screenRealHeight(): Int = displaySize().y

    /** 整块屏幕的尺寸（含状态栏、导航栏）：浮窗都按它摆放（[OrbGeometry]）。 */
    private fun displaySize(): android.graphics.Point =
        displaySize(windowManager ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager)

    /** 展开卡底边放在距屏幕底 [imeHeight] 的键盘上方 8（窗口按底部对齐，卡片四周有阴影余量）。 */
    private fun bubbleYAbove(imeHeight: Int): Int =
        maxOf(bubbleBaseY, imeHeight + dpToPx(8) - dpToPx(PANEL_SHADOW_DP))

    /**
     * 打开补充输入：实测过键盘高度就立刻按它上移，与键盘同时到位；第一次没有实测值时不猜
     * （猜高了会先跳上去再掉下来），等读到稳定的输入法位置再移。
     */
    private fun liftBubbleForTyping() {
        typingLift = 0
        sessionImeMax = 0
        // 没有实测值时先按屏高 33% 上移：不高于常见键盘，之后读到实际位置只会再往上补一点，不会先高后掉。
        val estimate = lastImeHeight.takeIf { it > 0 } ?: (screenRealHeight() * 0.33f).toInt()
        liftBubbleTo(bubbleYAbove(estimate))
    }

    /** 键盘收起后再点输入框：键盘即将弹出，按缓存高度提前上移（键盘已在时高度不变，不会移动）。 */
    private fun onSupplementKeyboardRequested() {
        val lp = bubbleParams ?: return
        if (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) return
        if (sessionImeMax == 0) liftBubbleForTyping()
    }

    /** 本次补充输入里读到的最大键盘高度；0 = 键盘还没出现过（或已被收起）。 */
    private var sessionImeMax = 0

    /** 本次补充输入里展开卡已经抬到的高度：输入期间只升不降（键盘升起过程中的读数会变小，跟着降就会上下晃）。 */
    private var typingLift = 0

    private fun liftBubbleTo(target: Int) {
        if (target <= typingLift) return
        typingLift = target
        moveBubbleTo(target)
    }

    private fun placeBubbleAboveIme(imeTop: Int?) {
        if (imeTop == null) {
            // 键盘出现过又没了（用户按返回收起）：卡片回原位；再点输入框弹出键盘时重新上移。
            // 键盘还没出现时（正在升起）保持当前位置。
            if (sessionImeMax > 0) {
                sessionImeMax = 0
                typingLift = 0
                moveBubbleTo(bubbleBaseY)
            }
            return
        }
        val height = screenRealHeight() - imeTop
        if (height <= 0) return
        // 只记本次输入里的最大高度：升起或收起过程中读到的中间值偏小，记下来会让下次上移不够。
        if (height > sessionImeMax) {
            sessionImeMax = height
            lastImeHeight = height
        }
        liftBubbleTo(bubbleYAbove(height))
    }

    /** 展开卡窗口的上下移动：`fast` + `standard`；减少动画时直接到位。 */
    private fun moveBubbleTo(target: Int) {
        val wm = windowManager ?: return
        val bubble = bubbleView ?: return
        val lp = bubbleParams ?: return
        if (target == bubbleTargetY && (lp.y == target || bubbleYAnimator?.isRunning == true)) return
        bubbleTargetY = target
        bubbleYAnimator?.cancel()
        if (io.github.fartown.movo.ui.theme.isReducedMotion(this)) {
            lp.y = target
            runCatching { wm.updateViewLayout(bubble, lp) }
            return
        }
        bubbleYAnimator = android.animation.ValueAnimator.ofInt(lp.y, target).apply {
            duration = io.github.fartown.movo.ui.theme.MovoMotion.FAST.toLong()
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { animator ->
                if (bubbleParams !== lp || bubbleView !== bubble) return@addUpdateListener
                lp.y = animator.animatedValue as Int
                runCatching { wm.updateViewLayout(bubble, lp) }
            }
            start()
        }
    }

    private fun createOverlayComposeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(overlayContext()).apply {
            setViewTreeLifecycleOwner(this@AgentRuntimeService)
            setViewTreeSavedStateRegistryOwner(this@AgentRuntimeService)
            setContent {
                FlavorModule.runSurface.Host(this@AgentRuntimeService, ::isNightMode, content)
            }
        }

    /** 这一轮请求作答（审批 / 提问）：发布给 [InteractionCardCoordinator]，由它决定在 App 内还是悬浮窗显示。 */
    private fun publishInteraction(session: AgentRuntimeSession, event: AgentEvent.InteractionRequested) {
        InteractionCardCoordinator.publish(
            AgentInteractionUiState(
                runId = session.runId,
                requestId = event.requestId,
                isApproval = event.kind == "approval",
                title = event.title,
                detail = event.detail,
                options = event.options,
                allowFreeText = event.allowFreeText,
                note = event.note,
                reason = event.reason,
            ),
        )
    }

    /**
     * 悬浮卡显隐（跨应用审批 / 提问）：有待作答的卡、且 App 内没有可显示它的页面（主界面 / 对话浮层 resumed）时浮在目标应用上，
     * 否则撤下——同一张卡任何时候只在一处。锁屏时不给出「允许」，改显示解锁提示；「去解锁」进行中先撤下，不挡住系统解锁界面。
     * 无障碍重连后旧实例名下的窗口已被系统移除，按新 context 重建。
     */
    private fun syncInteractionOverlay() {
        if (!FlavorModule.interactionCards) {
            removeInteractionOverlay()
            return
        }
        val model = InteractionCardCoordinator.pending.value
        val floating = InteractionCardCoordinator.currentPlacement == InteractionCardCoordinator.Placement.FLOATING &&
            !InteractionCardCoordinator.unlockInProgress.value
        if (model == null || !floating) {
            removeInteractionOverlay()
            return
        }
        val locked = isKeyguardLocked()
        val focusable = InteractionCardCoordinator.floatingFocusable(model, locked)
        if (interactionView != null && (interactionOwner !== overlayContext() || interactionLocked.value != locked)) {
            removeInteractionOverlay()
        }
        interactionLocked.value = locked
        if (interactionView == null) {
            showInteractionOverlay(locked, focusable)
        } else if (interactionFocusable != focusable) {
            updateInteractionFocusable(focusable)
        }
    }

    private fun showInteractionOverlay(locked: Boolean, focusable: Boolean) {
        if (interactionView != null) return
        // 窗口类型按无障碍是否可用决定（overlayType），窗口管理器必须取同一个上下文的：
        // 悬浮层还没显示过时 windowManager 为空，用 Service 自己的去加 TYPE_ACCESSIBILITY_OVERLAY 会被拒，卡片出不来。
        val owner = overlayContext()
        val wm = owner.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = createOverlayComposeView {
            val model = InteractionCardCoordinator.pending.collectAsState().value
            if (model != null) {
                if (interactionLocked.value) {
                    FlavorModule.runSurface.UnlockPrompt(
                        onCancel = { InteractionCardCoordinator.reply(model, InteractionReply.Cancelled) },
                        onUnlock = ::requestUnlockForInteraction,
                    )
                } else {
                    FlavorModule.runSurface.Interaction(
                        model = model,
                        onApprove = { replyFromFloatingCard(model, InteractionReply.Approval(approved = true)) },
                        onDecline = { InteractionCardCoordinator.reply(model, InteractionReply.Approval(approved = false)) },
                        onAnswer = { text, idx -> replyFromFloatingCard(model, InteractionReply.Answer(text, idx)) },
                        onCancel = { InteractionCardCoordinator.reply(model, InteractionReply.Cancelled) },
                    )
                }
            }
        }
        val lp = interactionLayoutParams(locked, focusable)
        runCatching { wm.addView(view, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_interaction_overlay_add_failed") {
                "Agent runtime interaction overlay addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        interactionView = view
        InteractionCardCoordinator.setFloatingAttached(true)
        interactionParams = lp
        interactionOwner = owner
        interactionWindowManager = wm
        interactionFocusable = focusable
    }

    /** 允许 / 作答前再确认没锁屏：息屏后锁屏状态的广播可能晚于用户点按，锁着就换成解锁提示、这次不作答。拒绝与取消不受限。 */
    private fun replyFromFloatingCard(model: AgentInteractionUiState, reply: InteractionReply) {
        if (isKeyguardLocked()) {
            syncInteractionOverlay()
            return
        }
        InteractionCardCoordinator.reply(model, reply)
    }

    private fun updateInteractionFocusable(focusable: Boolean) {
        val view = interactionView ?: return
        val lp = interactionParams ?: return
        val wm = interactionWindowManager ?: return
        lp.flags = if (focusable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { wm.updateViewLayout(view, lp) }
        interactionFocusable = focusable
    }

    private fun removeInteractionOverlay() {
        interactionView?.let { view -> runCatching { (interactionWindowManager ?: windowManager)?.removeView(view) } }
        InteractionCardCoordinator.setFloatingAttached(false)
        interactionWindowManager = null
        interactionView = null
        interactionParams = null
        interactionOwner = null
        interactionFocusable = false
    }

    private fun interactionLayoutParams(locked: Boolean, focusable: Boolean): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            // 锁屏解锁提示：窗口只有卡片大小，卡片外的触摸交给锁屏（用户仍可直接滑动解锁）；
            // 平时全屏：轻度压暗的遮罩点一下 = 取消。
            if (locked) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                // 审批卡不可获焦：可获焦的全屏窗口会抢走目标窗口的焦点、收起输入法，目标界面内容随之变化，
                // 批准后工具执行时观察已过期，要再批一次。提问卡要自由输入时才可获焦。
                (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
                (if (locked) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else 0),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            // 可获焦（提问卡自由输入）时 IME 弹出缩放布局。
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            windowAnimations = 0
        }

    private fun isKeyguardLocked(): Boolean =
        runCatching { getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true }.getOrDefault(false)

    /**
     * 「去解锁」：先撤下悬浮卡（别挡住系统解锁界面），用透明页面请求解锁；解锁后 ACTION_USER_PRESENT 换回原卡，
     * 取消解锁或页面没拉起来时重新显示解锁提示。
     */
    private fun requestUnlockForInteraction() {
        InteractionCardCoordinator.setUnlockInProgress(true)
        mainHandler.removeCallbacksAndMessages(unlockTimeoutToken)
        // 解锁页面没有回报（被系统直接回收等）时兜底放开，卡片不会一直不出现。
        mainHandler.postDelayed(
            { InteractionCardCoordinator.setUnlockInProgress(false) },
            unlockTimeoutToken,
            UNLOCK_REQUEST_TIMEOUT_MS,
        )
        runCatching {
            FlavorModule.runSurface.requestUnlock(AgentAccessibilityService.current() ?: this)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_unlock_request_failed") {
                "Agent runtime unlock request failed: type=${throwable.safeLogType()}"
            }
            mainHandler.removeCallbacksAndMessages(unlockTimeoutToken)
            InteractionCardCoordinator.setUnlockInProgress(false)
        }
    }

    private val unlockTimeoutToken = Any()

    /** 亮屏 / 息屏 / 解锁：解锁成功或息屏时解锁界面已不在，放开「解锁进行中」，卡片按当前锁屏状态重新显示。 */
    private fun onKeyguardStateChanged(action: String?) {
        if (action == Intent.ACTION_USER_PRESENT || action == Intent.ACTION_SCREEN_OFF) {
            mainHandler.removeCallbacksAndMessages(unlockTimeoutToken)
            InteractionCardCoordinator.setUnlockInProgress(false)
        }
        syncInteractionOverlay()
    }

    /**
     * 开始拖动：收起展开卡；没有任务在跑时底部中间淡入「移除」区（规范 9.5「悬浮球 · 移除」）。
     * 执行中不给移除入口——悬浮球是 App 外唯一的暂停入口。
     */
    private fun onOrbDragStart() {
        orbDragging = true
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
        val display = displaySize()
        val orbSize = dpToPx(ORB_WINDOW_DP)
        fingerX = (fingerX - dx).coerceIn(0f, (display.x - orbSize).toFloat())
        fingerY = (fingerY + dy).coerceIn(0f, (display.y - orbSize).toFloat())
        val engaged = removeZoneView != null && isOverRemoveZone(fingerX, fingerY)
        if (engaged != removeEngaged.value) {
            removeEngaged.value = engaged
            if (engaged) AgentHapticFeedback.perform(this, AgentHapticFeedback.Type.TAP)
        }
        if (engaged) {
            lp.x = display.x / 2 - orbSize / 2
            lp.y = removeZoneCenterY() - orbSize / 2
        } else {
            lp.x = fingerX.toInt()
            lp.y = fingerY.toInt()
        }
        runCatching { wm.updateViewLayout(view, lp) }
        // 拖动过程持续更新，展开卡再次弹出时揭开起点跟随最新位置。
        publishOrbRect()
    }

    /** 松手：在移除区里 → 缩小淡出后移除；否则吸附到最近的左右边缘。 */
    private fun onOrbDragEnd() {
        orbDragging = false
        val remove = removeEngaged.value
        removeEngaged.value = false
        hideRemoveZone()
        if (remove) {
            orbShown.value = false
            collapseBubble()
            mainHandler.postDelayed({
                if (activeSession == null) {
                    // 用户亲手移除：下次打开 Movo 前，无障碍重连等系统事件不再自动把待命球请回来。
                    io.github.fartown.movo.agent.overlay.OrbPrefs.markRemovedByUser()
                    dismissAndStop()
                } else {
                    orbShown.value = true
                }
            }, ORB_EXIT_MS)
        } else {
            snapOrbToEdge()
        }
        // 拖动是从展开卡的球侧通道开始的：展开卡已收起，拖动结束后再移除它的窗口。
        if (bubbleRemovalDeferred) removeCollapsedBubble()
    }

    private fun removeZoneCenterY(): Int =
        displaySize().y - dpToPx(REMOVE_ZONE_BOTTOM_DP) - dpToPx(REMOVE_ZONE_DP) / 2

    private fun isOverRemoveZone(x: Float, y: Float): Boolean {
        val width = displaySize().x
        val half = dpToPx(ORB_WINDOW_DP) / 2f
        val cx = width - x - half
        val cy = y + half
        return kotlin.math.hypot(cx - width / 2f, cy - removeZoneCenterY()) < dpToPx(REMOVE_ZONE_DP)
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
            x = (displaySize().x - window) / 2
            y = removeZoneCenterY() - window / 2
            windowAnimations = 0
            inDisplayCoordinates()
        }
        val view = createOverlayComposeView {
            FlavorModule.runSurface.RemoveZone(visible = removeZoneVisible.value)
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
        val width = displaySize().x
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
                publishOrbRect()
                // 吸附途中点开的展开卡跟着球走，到边时仍贴着球（不然停在点开那一刻的位置，和球错开）。
                followOrbWithBubble()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = publishOrbRect()
            })
            start()
        }
    }

    /** 展开卡窗口横向跟到悬浮球当前的位置（键盘避让的纵向抬高不动）。 */
    private fun followOrbWithBubble() {
        val wm = windowManager ?: return
        val bubble = bubbleView ?: return
        val lp = bubbleParams ?: return
        val placed = bubbleLayoutParams()
        if (lp.gravity == placed.gravity && lp.x == placed.x) return
        lp.gravity = placed.gravity
        lp.x = placed.x
        runCatching { wm.updateViewLayout(bubble, lp) }
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
            // 右侧中下，贴近右边缘。x 是窗口右边到屏幕右边的距离，y 是窗口上边到屏幕顶的距离，都按整块屏幕算（[OrbGeometry]）。
            gravity = Gravity.END or Gravity.TOP
            // 默认停靠右边缘（距边 8），球心距屏幕底部约 1/4 屏高，处在单手拇指可及区（规范 8.1）。
            x = dpToPx(ORB_EDGE_DP)
            y = OrbGeometry.defaultTop(displaySize().y, dpToPx(ORB_WINDOW_DP))
            inDisplayCoordinates()
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
            // 卡片在球朝屏幕中心的一侧、距球 8，底边与球对齐（卡片四周留 12 的阴影余量）。
            // 窗口在球那一侧一直延伸到球窗口的外边缘（卡片内容里留出 PANEL_ORB_LANE 通道）：
            // 展开卡从球心长出来，起点要在本窗口内才画得出来（规范 9.5「悬浮球 → 展开卡」）。
            // 位置只由悬浮球的窗口参数推出来，和球在同一套整块屏幕坐标里（[OrbGeometry]），不读球窗口的实际位置。
            val display = displaySize()
            val window = dpToPx(ORB_WINDOW_DP)
            val orbFromRight = orbParams?.x ?: dpToPx(ORB_EDGE_DP)
            val orbTop = orbParams?.y ?: OrbGeometry.defaultTop(display.y, window)
            gravity = (if (orbOnEnd.value) Gravity.END else Gravity.START) or Gravity.BOTTOM
            x = OrbGeometry.bubbleX(orbOnEnd.value, display.x, orbFromRight, window)
            y = OrbGeometry.bubbleY(display.y, orbTop, window, dpToPx(ORB_DISC_DP), dpToPx(PANEL_SHADOW_DP))
            windowAnimations = 0
            inDisplayCoordinates()
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
        // 打字期间 Agent 不能同时操作屏幕：展开卡拿走了输入焦点，前台会被判成 Movo，「返回」也只会收起键盘。
        // 所以打开补充输入时先暂停（停在下一步之前），发送或取消后再继续。
        if (focusable && activeSession != null && state.value.phase == AgentOverlayPhase.RUNNING) {
            pausedForTyping = true
            requestPause()
        } else if (!focusable && pausedForTyping) {
            pausedForTyping = false
            if (activeSession != null && state.value.phase == AgentOverlayPhase.PAUSED) requestResume()
        }
        lp.flags = nextFlags
        runCatching { wm.updateViewLayout(bubble, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_bubble_focus_update_failed") {
                "Agent runtime bubble focus update failed: type=${throwable.safeLogType()}"
            }
        }
        mainHandler.removeCallbacks(trackImeForBubble)
        if (focusable) {
            liftBubbleForTyping()
            mainHandler.postDelayed(trackImeForBubble, IME_TRACK_INTERVAL_MS)
        } else {
            moveBubbleTo(bubbleBaseY)
        }
    }

    private fun openResultConversation(autoListen: Boolean = false) {
        // 后台监听的一轮还没接管悬浮层时，悬浮层上仍是上一轮待查看的结果，可以照常点开。
        if (resultConversationOpening.value || foregroundRunActive() || pendingStartRequest != null) return
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
                    // 浮层已经打开并在内容区显示了原因与「重试」，这里不再另外提示。
                    failResultHandoff(token, notify = false)
                }
            }
        }
        sendConversationIntent(target, runId, receiver, autoListen, token)
    }

    /**
     * 拉起对话浮层打开 [target]（[runId] 为 null = 只打开这个对话，不交付某一轮的结果）；10 秒没回音按没打开处理。
     */
    private fun sendConversationIntent(
        target: AgentConversationTarget,
        runId: String?,
        receiver: ResultReceiver,
        autoListen: Boolean,
        token: Any,
    ) {
        runCatching {
            val creatorOptions = ActivityOptions.makeBasic().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    pendingIntentCreatorBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0x524553,
                AgentConversationHandoff.intent(this, target, runId, receiver)
                    .setClass(this, FlavorModule.surfaces.conversationActivity)
                    .putExtra(MovoAssistantVoiceService.EXTRA_AUTO_LISTEN, autoListen),
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                creatorOptions.toBundle(),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val senderOptions = ActivityOptions.makeBasic().apply {
                    pendingIntentBackgroundActivityStartMode = if (Build.VERSION.SDK_INT >= 36)
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                    else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
                pendingIntent.send(senderOptions.toBundle())
            } else {
                // Android 14 以前没有后台启动 Activity 的发送方选项。
                pendingIntent.send()
            }
        }.onFailure {
            AndroidAgentLogger.warn("Agent result conversation launch failed")
            failResultHandoff(token)
            return
        }
        mainHandler.postDelayed({ failResultHandoff(token) }, token, 10_000)
    }

    /**
     * 结果对话没能打开：悬浮球保留当前状态（点它可重试）；[notify] 时把原因写进展开卡并展开（规范 8.1 / 8.11，不用 Toast）。
     * 浮层自己报告失败时它已在内容区说明，不重复提示。
     */
    private fun failResultHandoff(token: Any, notify: Boolean = true) {
        if (resultHandoffToken !== token) return
        clearResultHandoff()
        AndroidAgentLogger.warn("Agent conversation sheet not ready; runtime overlay retained")
        if (notify && orbView != null) showPanelNotice(getString(R.string.overlay_result_open_failed))
    }

    private fun clearResultHandoff() {
        resultHandoffToken?.let { mainHandler.removeCallbacksAndMessages(it) }
        resultHandoffToken = null
        resultConversationOpening.value = false
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun enterFinalState(finalState: AgentOverlayState, keepVisible: Boolean = false) {
        pausedForTyping = false
        // 「结束任务」连带结束了监听：卡片原位显示「已结束·撤销」，这一轮不再另显示停止态（规范 8.12「撤销」）。
        if (endedTask.value != null && finalState.status == AgentOverlayStatus.Stopped) {
            retireGlow()
            state.value = AgentOverlayState.Initial
            standby.value = true
            updateStandbyOrbVisibility()
            return
        }
        // 这件事还有监听在等（开监听的那一轮、监听叫醒的一轮答完）：任务没完，直接回到「监听中」，不出 ✓（规范 8.12「任务与状态」）。
        // 操作没操作其他 App、从 App 还是对话浮层发起都一样：结果已存进对话，监听中点开展开卡、键盘 / 语音都打开这个对话。
        if (finalState.phase == AgentOverlayPhase.FINISHED && taskMonitored(resultConversationTarget)) {
            state.value = finalState
            if (FlavorModule.surfaces.isConversationVisible(resultConversationTarget)) {
                // 对话浮层正在前台（从浮层发起）：结果照常交给它，交付后回到待命（[enterStandby]）。
                // 这期间悬浮球藏着，外观也已是监听中（[orbMode]）。
                collapseBubble()
                retireGlow()
                mainHandler.removeCallbacksAndMessages(hideToken)
                openResultConversation()
            } else {
                ensureMonitoringOrb()
                retireRunOverlayToStandby()
            }
            return
        }
        state.value = finalState

        val finish = OverlayLifecyclePolicy.finish(
            executedForegroundTool = hasExecutedForegroundTool,
            resultConversation = isResultConversation,
            standbyOrbPresent = standby.value && orbView != null,
            resultAwaitedOnOrb = runShownOnOrb && orbView != null && !VoiceSurfaceTracker.appVisible,
            keepStandbyOrb = { activeSession == null && (ensureStandbyOrb() || ensureMonitoringOrb()) },
        )
        if (finish == OverlayLifecyclePolicy.Finish.SHOW_RESULT) {
            // 规范 8.1 / 9.5：悬浮球不退场，完成保持 ✓（失败保持 !），用户点开才打开对话浮层查看结果；
            // 不自动弹出，避免打断用户正在看的 App。结果交付仍走原来的 handoff（点球时发起，带回执与失败提示）。
            // 屏幕边缘光晕随状态淡出后移除窗口。
            collapseBubble()
            if (!FlavorModule.surfaces.isConversationVisible(resultConversationTarget)) {
                // 没操作其他 App 的一轮：球已经在了，不再点亮边缘光晕（否则结束时闪一下）。
                if (hasExecutedForegroundTool || orbView == null) ensureOverlayVisible()
                scheduleResultOrbHide()
                updateStandbyOrbVisibility()
                // 失败：展开卡自动弹出显示原因，保持失败态直到用户点开（规范 9.5）。
                // Movo 自己在前台时原因已在 App 里显示，球也藏着，不单独弹展开卡。
                if (finalState.phase == AgentOverlayPhase.FAILED && finalState.status != AgentOverlayStatus.Stopped &&
                    !VoiceSurfaceTracker.appVisible
                ) {
                    mainHandler.postDelayed({
                        if (state.value.phase == AgentOverlayPhase.FAILED && activeSession == null) expandBubble()
                    }, BUBBLE_EXIT_MS)
                }
            } else {
                // 对话浮层已在前台（从浮层发起）：直接交付到浮层，与原来一致。
                openResultConversation()
            }
            retireGlow()
            mainHandler.removeCallbacksAndMessages(hideToken)
        } else if (finish == OverlayLifecyclePolicy.Finish.RETIRE_TO_STANDBY) {
            // 这一轮没有操作其他 App（纯问答；或流式阶段已按前台工具揭开了浮层，但工具没真正开始就被停止、流中断或改成纯文本回答）：
            // 结果在对话里，常驻的悬浮球回到待命——不带 ✓（真机：沿用了结束态的绿勾），展开卡、光晕、提示一并撤掉。
            retireRunOverlayToStandby()
        } else {
            dismissAndStop()
        }
    }

    /** 回到待命外观：收起展开卡（连同里面的提示）、光晕淡出撤掉、状态复位；常驻关时撤掉悬浮球并停服务。 */
    private fun retireRunOverlayToStandby() {
        collapseBubble()
        retireGlow()
        state.value = AgentOverlayState.Initial
        standby.value = true
        updateStandbyOrbVisibility()
        stopIfOverlayUnneeded()
    }

    /** 看过结果后回到待命：绿环与角标淡出，悬浮球留在原处（规范 8.1 / 9.5「悬浮球 · 完成」）。 */
    private fun enterStandby() {
        clearResultHandoff()
        if (orbView == null) {
            // 后台监听的一轮还在跑（结果是在它没接管悬浮层时点开的）：不停服务。
            if (activeSession != null) return
            // 看完结果、任务还有监听在等（常驻关时这一轮没建过球）：建一颗显示监听中，不停服务。
            if (!ensureMonitoringOrb()) dismissAndStop()
            return
        }
        collapseBubble()
        retireGlow()
        if (activeSession == null) isResultConversation = false
        state.value = AgentOverlayState.Initial
        standby.value = true
        updateStandbyOrbVisibility()
        // 常驻关：看完结果又没有任务时，藏着的悬浮球和服务都没有用处。
        stopIfOverlayUnneeded()
    }

    /**
     * 「常驻悬浮球」关、没有任务（含准备中）、也没有可查看的结果时，藏着的悬浮球与服务都没有用处：撤掉并停服务。
     * 可查看的结果 = ✓ / ! 还在保留时长内、失败原因的展开卡开着、或正在打开结果对话。返回是否已停。
     */
    private fun stopIfOverlayUnneeded(): Boolean {
        val policy = OverlayLifecyclePolicy
        val unneeded = policy.overlayUnneeded(
            keepOrbAfterExit = io.github.fartown.movo.agent.overlay.OrbPrefs.keepOrbAfterExit(this),
            runActive = activeSession != null,
            preparingRun = pendingStartRequest != null,
            openingResult = resultConversationOpening.value,
            monitoring = monitorsKeepOrb(),
            resultViewable = policy.resultViewable(
                orbPresent = orbView != null,
                standby = standby.value,
                phase = state.value.phase,
                nowUptimeMillis = android.os.SystemClock.uptimeMillis(),
                resultVisibleUntilUptimeMillis = resultOrbVisibleUntil,
                panelOpen = !collapsed.value,
            ),
        )
        if (!unneeded) return false
        dismissAndStop()
        return true
    }

    /**
     * 悬浮球显隐。「常驻悬浮球」开（默认）时退出 App 后一直在；关时只在有执行中（含暂停）的任务时显示
     * （设置 → 系统助手，[io.github.fartown.movo.agent.overlay.OrbPrefs]）。以下是关闭常驻时的规则：
     * 任务在其他 App 里结束时，✓ / ! 保留 [RESULT_ORB_HOLD_MS] 让用户看到结果（这段时间点它仍打开结果），随后淡出；
     * 展开卡开着（例如失败原因）时等它收起再淡出。Movo 自己在前台时结果已在 App 里，直接藏起。
     */
    private fun updateStandbyOrbVisibility() {
        val view = orbView ?: return
        // 浮层收回球里之后页面已经离开：交接结束，之后照常判断。
        if (sheetHandoff == SheetHandoff.RETURNED && !VoiceSurfaceTracker.appVisible) {
            mainHandler.removeCallbacksAndMessages(sheetHandoffToken)
            sheetHandoff = SheetHandoff.NONE
        }
        // Movo 自己的页面挡在前面：对话浮层与球交接的两段除外（[SheetHandoff]）。
        val movoInFront = VoiceSurfaceTracker.appVisible && sheetHandoff == SheetHandoff.NONE
        // 结束后会话引用要等结果被查看才清掉，是否「执行中」按阶段判断（真机：只看 activeSession 时 ✓ 一直不淡出）。
        val phase = state.value.phase
        val running = !standby.value && activeSession != null &&
            (phase == AgentOverlayPhase.RUNNING || phase == AgentOverlayPhase.PAUSED)
        val show = if (movoInFront) {
            // Movo 自己的界面在前台时一律藏起（含执行中 / 暂停）：App 里已有执行卡、暂停提示条与主按钮，
            // 悬浮球和展开卡只会重复并压住这些控件（2026-09-27 定）。离开后按当时的状态出现。
            false
        } else if (io.github.fartown.movo.agent.overlay.OrbPrefs.keepOrbAfterExit(this)) {
            // 常驻（默认）：离开 App 后一直在（待命、执行中、✓ / ! 保留到点开）。
            true
        } else {
            val holdingResult = !running && !standby.value &&
                android.os.SystemClock.uptimeMillis() < resultOrbVisibleUntil
            // 监听中任务没完：常驻关也显示（规范 8.1）；刚结束可撤销、或监听刚自己结束（等唤醒的一轮）时也留着。
            val monitoring = monitorsKeepOrb()
            running || holdingResult || monitoring ||
                (!running && !standby.value && !collapsed.value)
        }
        // 注意：这里不能取消 [orbFadeToken] 上的回调——那是 3 秒后的这次复查本身（原来在这里取消，✓ 永远不淡出）。
        AndroidAgentLogger.info(
            "Agent orb visibility: show=$show running=$running phase=$phase standby=${standby.value} monitors=${monitors.value.size} " +
                "appVisible=${VoiceSurfaceTracker.appVisible} handoff=$sheetHandoff collapsed=${collapsed.value}",
        )
        if (show) {
            view.animate().cancel()
            view.alpha = 1f
            view.visibility = View.VISIBLE
            return
        }
        // 失败时自动弹出的展开卡也一起收起：否则切回 Movo 后它留在键盘上，透明的阴影区还会吃掉点击。
        if (!collapsed.value) collapseBubble()
        // 常驻关：藏起后若已没有任务、也没有可查看的结果（✓ / ! 保留时长已过），悬浮球和服务都撤掉（先播完淡出）。
        if (view.visibility != View.VISIBLE) {
            stopIfOverlayUnneeded()
            return
        }
        if (movoInFront || io.github.fartown.movo.ui.theme.isReducedMotion(this)) {
            view.visibility = View.GONE
            stopIfOverlayUnneeded()
            return
        }
        // 淡出 `standard-exit`，播完再藏起窗口内容。
        view.animate().alpha(0f).setDuration(MovoMotion.STANDARD_EXIT.toLong()).withEndAction {
            if (orbView === view) view.visibility = View.GONE
            view.alpha = 1f
            if (orbView === view) stopIfOverlayUnneeded()
        }.start()
    }

    /** 后台监听让悬浮球留着：监听中、刚结束还能撤销、或监听刚自己结束（[monitorsEndedHoldUntil]）。 */
    private fun monitorsKeepOrb(): Boolean =
        monitors.value.isNotEmpty() || endedTask.value != null ||
            android.os.SystemClock.uptimeMillis() < monitorsEndedHoldUntil

    /** 任务结束后在 [RESULT_ORB_HOLD_MS] 到期时重新判断悬浮球显隐（展开卡开着时，收起后再判断）。 */
    private fun scheduleResultOrbHide() {
        resultOrbVisibleUntil = android.os.SystemClock.uptimeMillis() + RESULT_ORB_HOLD_MS
        mainHandler.removeCallbacksAndMessages(orbFadeToken)
        mainHandler.postAtTime({ updateStandbyOrbVisibility() }, orbFadeToken, resultOrbVisibleUntil + 1)
    }

    private fun onVoiceActiveChanged(active: Boolean) {
        if (orbView == null || standby.value) return
        val running = activeSession != null &&
            (state.value.phase == AgentOverlayPhase.RUNNING || state.value.phase == AgentOverlayPhase.PAUSED)
        if (active && running && collapsed.value) expandBubble()
        if (!active && !collapsed.value) scheduleBubbleAutoCollapse()
    }

    /**
     * Deterministic same-process fixture for device instrumentation.
     *
     * The entry is unreachable in non-debuggable APKs and never replaces a real run. Runtime
     * events still pass through the production reducer/visibility path, and the execution lease,
     * WindowManager overlay, Compose content and voice session are the production implementations.
     */
    private fun beginInstrumentationFixtureOnService(): Boolean {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        if (instrumentationFixture != null || activeSession != null || pendingStartRequest != null) return false
        val runId = "instrumentation-overlay-${android.os.SystemClock.elapsedRealtime()}"
        val session = AgentRuntimeSession(runId = runId, voiceSessionId = "instrumentation")
        val leaseId = "instrumentation:$runId"
        if (!AgentExecutionService.acquire(this, leaseId, task = runId) { session.controller.cancel() }) {
            return false
        }
        val fixture = InstrumentationFixture(session = session, leaseId = leaseId)
        instrumentationFixture = fixture
        activeSession = session
        standby.value = false
        collapsed.value = true
        bubbleVisible.value = true
        state.value = AgentOverlayState.Initial

        fun accept(event: AgentEvent) {
            session.emit(event)
            handleAcceptedRunEvent(session, event, entrySurfaceGuard = null)
        }
        accept(AgentEvent.RunStarted(initialImages = 0, initialImageBytes = 0, toolCount = 1, terminalTools = false))
        accept(
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "instrumentation-step",
                // 用类型化工具子系统的真实工具名（旧名 tap 已不会再出现），自检走与线上相同的揭开与显示名路径。
                name = "ui_tap",
                argsPreview = "正在验证悬浮层",
            ).also { it.atMillis = System.currentTimeMillis() },
        )
        thread(name = "agent-runtime-instrumentation-fixture") {
            while (instrumentationFixture === fixture && !session.controller.isCancelled) {
                android.os.SystemClock.sleep(20)
            }
            if (session.controller.isCancelled) {
                mainHandler.post { completeInstrumentationFixtureStop(fixture) }
            }
        }
        return true
    }

    private fun expandInstrumentationFixtureOnService(): Boolean {
        val fixture = instrumentationFixture ?: return false
        if (!fixture.taskActive || activeSession !== fixture.session) return false
        ensureOverlayVisible()
        expandBubble()
        // Device instrumentation needs a stable inspection window; production interactions still
        // use the normal four-second auto-collapse path.
        mainHandler.removeCallbacksAndMessages(panelIdleToken)
        updateStandbyOrbVisibility()
        return !collapsed.value && bubbleView?.isAttachedToWindow == true
    }

    private fun completeInstrumentationFixtureStop(fixture: InstrumentationFixture) {
        if (instrumentationFixture !== fixture || !fixture.taskActive) return
        fixture.taskActive = false
        fixture.stopObserved = true
        if (activeSession === fixture.session) {
            fixture.session.complete(
                AgentRuntimeWire.RunResult(
                    runId = fixture.session.runId,
                    ok = false,
                    content = "",
                    error = "已停止",
                ),
            )
            activeSession = null
        }
        AgentExecutionService.release(fixture.leaseId)
        state.value = state.value.copy(
            phase = AgentOverlayPhase.FAILED,
            status = AgentOverlayStatus.Stopped,
            pausedAtMillis = state.value.pausedAtMillis ?: System.currentTimeMillis(),
        )
        updateStandbyOrbVisibility()
    }

    private fun instrumentationFixtureSnapshotOnService(): AgentRuntimeInstrumentationAccess.Snapshot {
        val fixture = instrumentationFixture
        val params = bubbleParams
        return AgentRuntimeInstrumentationAccess.Snapshot(
            available = fixture != null,
            taskActive = fixture?.taskActive == true && activeSession === fixture.session,
            voiceActive = VoiceSessionManager.active,
            overlayAttached = orbView?.isAttachedToWindow == true && bubbleView?.isAttachedToWindow == true,
            expanded = !collapsed.value && bubbleView?.isAttachedToWindow == true,
            typing = params != null && params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0,
            phase = state.value.phase.name,
            stopObserved = fixture?.stopObserved == true,
        )
    }

    private fun finishInstrumentationFixture() {
        val fixture = instrumentationFixture ?: return
        instrumentationFixture = null
        if (activeSession === fixture.session) activeSession = null
        fixture.session.cancel("Instrumentation fixture finished")
        if (fixture.taskActive) {
            fixture.taskActive = false
            AgentExecutionService.release(fixture.leaseId)
        }
        mainHandler.removeCallbacksAndMessages(panelIdleToken)
        mainHandler.removeCallbacksAndMessages(bubbleRemovalToken)
        removeAmbientWindows()
        state.value = AgentOverlayState.Initial
        collapsed.value = true
        bubbleVisible.value = true
        pausedForTyping = false
    }

    private fun removeAmbientWindows() {
        orbDiscRect = null
        orbCenterOnScreen.value = null
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
        // 窗口撤了，展开卡与光晕的标记也复位：否则下次建出来的球按「展开中」淡在透明、展开卡也加不出来。
        mainHandler.removeCallbacksAndMessages(bubbleRemovalToken)
        mainHandler.removeCallbacksAndMessages(panelIdleToken)
        mainHandler.removeCallbacksAndMessages(glowRetireToken)
        mainHandler.removeCallbacks(trackImeForBubble)
        bubbleYAnimator?.cancel()
        collapsed.value = true
        bubbleVisible.value = true
        bubbleRemovalDeferred = false
        orbDragging = false
        glowRetired.value = false
        panelNotice.value = null
        // 悬浮审批卡用它自己的窗口管理器移除，不留全屏、可获焦的透明窗口挡住触摸。
        removeInteractionOverlay()
    }

    private fun dismissAndStop() {
        clearResultHandoff()
        removeAmbientWindows()
        pendingOverlayRestore = null
        // 没有任务了：不会再有人等审批 / 提问的作答。
        if (activeSession == null) InteractionCardCoordinator.clearRun(null)
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
            orbCenterOnScreen.value = null
            return
        }
        // 只按球自己的窗口参数算（球窗口不被系统栏推开，参数就是它在屏幕上的位置）；
        // 不读窗口的实际位置：吸附动画、拖动中刚 updateViewLayout 时读到的是上一帧。
        val disc = OrbGeometry.disc(displaySize().x, lp.x, lp.y, dpToPx(ORB_WINDOW_DP), dpToPx(ORB_DISC_DP))
        orbDiscRect = android.graphics.Rect(disc.left, disc.top, disc.right, disc.bottom)
        orbCenterOnScreen.value = androidx.compose.ui.geometry.Offset(disc.centerX, disc.centerY)
    }

    /** 从悬浮球打开对话浮层前登记起点：浮层从球的位置长出来（Q4）。浮层真正开始长出来之前球留着（[SheetHandoff.OPENING]）。 */
    private fun markSheetFromOrb() {
        publishOrbRect()
        val rect = orbDiscRect ?: return
        FlavorModule.surfaces.expandConversationFromOrb(rect)
        setSheetHandoff(SheetHandoff.OPENING)
    }

    /**
     * 对话浮层与悬浮球的交接（Q4，规范 9.5「从球里长出、收回球里」）：
     * - [OPENING]：点了球、浮层还在准备内容，还没开始从球里长出来——球留着，不能先没了（原来浮层页面一恢复球就藏，
     *   中间空一段才见浮层长出来）；
     * - [RETURNED]：浮层已经收回球里——球立刻接上，不等页面暂停后再判断（原来中间空约 8 帧，看起来是球先消失）。
     * 其余时候按 Movo 页面是否在前台（[VoiceSurfaceTracker.appVisible]）。
     */
    private enum class SheetHandoff { NONE, OPENING, RETURNED }

    private var sheetHandoff = SheetHandoff.NONE
    private val sheetHandoffToken = Any()

    private fun setSheetHandoff(value: SheetHandoff) {
        mainHandler.removeCallbacksAndMessages(sheetHandoffToken)
        sheetHandoff = value
        // 浮层没打开、没回报（打开失败、被系统拦下）时不能让球一直无视 Movo 页面在前台。
        if (value != SheetHandoff.NONE) {
            mainHandler.postDelayed({ setSheetHandoff(SheetHandoff.NONE) }, sheetHandoffToken, SHEET_HANDOFF_TIMEOUT_MS)
        }
        updateStandbyOrbVisibility()
    }

    internal companion object {
        @Volatile private var liveInstance: AgentRuntimeService? = null

        /**
         * 后台监听事件：本对话正有一轮在执行时，并入它的下一步（不打断当前模型请求或工具）。
         * 返回 false 表示没有可并入的运行（空闲、别的对话在跑、或本轮已在收尾），由调用方排队或另开事件轮。主线程调用。
         */
        internal fun injectMonitorEvent(
            conversationId: String,
            runId: String,
            modelText: String,
            events: List<AgentEvent>,
        ): Boolean = liveInstance?.injectMonitorEventOnService(conversationId, runId, modelText, events) == true

        /**
         * Runtime 当前没有在执行或准备执行的 run（服务未启动也算空闲）。主线程调用。
         * 悬浮层上有结果待查看不算忙：监听唤醒的一轮在需要前台操作之前不动它（见 [startRun]）。
         */
        internal fun isIdle(): Boolean =
            liveInstance?.let { service -> service.activeSession?.isTerminal != false && service.pendingStartRequest == null } ?: true

        internal fun beginInstrumentationFixture(): Boolean =
            liveInstance?.beginInstrumentationFixtureOnService() == true

        internal fun expandInstrumentationFixture(): Boolean =
            liveInstance?.expandInstrumentationFixtureOnService() == true

        internal fun instrumentationFixtureSnapshot(): AgentRuntimeInstrumentationAccess.Snapshot =
            liveInstance?.instrumentationFixtureSnapshotOnService()
                ?: AgentRuntimeInstrumentationAccess.Snapshot()

        internal fun finishInstrumentationFixture() {
            liveInstance?.finishInstrumentationFixture()
        }

        /** 进程内缓存的键盘高度（跨运行时服务重建保留）。 */
        private var imeHeightCache = 0

        @Volatile
        var orbDiscRect: android.graphics.Rect? = null
            private set

        /** 对话浮层开始从球里长出来（或不带动画直接出现）：球此刻让位。主线程调用。 */
        fun onSheetCoversOrb() {
            liveInstance?.takeIf { it.sheetHandoff == SheetHandoff.OPENING }?.setSheetHandoff(SheetHandoff.NONE)
        }

        /** 对话浮层收回到球里了：球立刻接上（页面随后才暂停）。主线程调用。 */
        fun onSheetReturnedToOrb() {
            liveInstance?.setSheetHandoff(SheetHandoff.RETURNED)
        }

        /** 当前悬浮球位置；没有悬浮球时取默认停靠位置（右边缘距边 8、球心在 75% 屏高）。 */
        fun orbDiscRectOrDefault(context: Context): android.graphics.Rect {
            orbDiscRect?.let { return it }
            // 与 [orbLayoutParams] 的默认停靠同一个算法、同一块屏幕尺寸：之后建出来的球就在这里。
            val density = context.resources.displayMetrics.density
            val px = { dp: Int -> (dp * density).toInt() }
            val display = displaySize(context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            val window = px(ORB_WINDOW_DP)
            val disc = OrbGeometry.disc(display.x, px(ORB_EDGE_DP), OrbGeometry.defaultTop(display.y, window), window, px(ORB_DISC_DP))
            return android.graphics.Rect(disc.left, disc.top, disc.right, disc.bottom)
        }

        /** 整块屏幕的尺寸（含状态栏、导航栏、刘海区）。 */
        fun displaySize(wm: WindowManager): android.graphics.Point =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.maximumWindowMetrics.bounds.let { android.graphics.Point(it.width(), it.height()) }
            } else {
                android.graphics.Point().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(it) }
            }

        /**
         * 浮窗按整块屏幕的绝对坐标摆放：不被状态栏、导航栏、刘海推开（悬浮球、展开卡、移除区共用，见 [OrbGeometry]）；
         * 位置变化不让系统补动画：开着系统动画（手机默认）时，窗口左上角一变——展开卡按内容长大、悬浮球拖动 / 吸附——
         * 系统会把整块窗口从旧位置平移到新位置，展开卡就从屏幕角落滑进来，不是从球里长出（10-07 小米真机逐帧实测；
         * 云真机默认关了系统动画，之前一直没测到）。位置都由我们逐帧给出，不需要它。
         */
        private fun WindowManager.LayoutParams.inDisplayCoordinates() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setCanPlayMoveAnimation(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        const val ORB_DISC_DP = 32
        const val ACTION_KEEP_ALIVE = "io.github.fartown.movo.agent.runtime.KEEP_ALIVE"
        /** 常驻悬浮球：没有任务时也建待命悬浮球（见 [io.github.fartown.movo.agent.overlay.OrbPrefs.requestStandbyOrb]）。 */
        const val ACTION_STANDBY_ORB = "io.github.fartown.movo.agent.runtime.STANDBY_ORB"
        const val HIDE_DELAY_MS = 2_500L
        const val ORB_WINDOW_DP = 44
        const val ORB_EDGE_DP = 8
        const val PANEL_SHADOW_DP = 12
        /** 展开卡先播完缩回球心（PANEL_MORPH_OUT_MS 250）再移除窗口；多留一帧余量。 */
        const val BUBBLE_EXIT_MS = io.github.fartown.movo.agent.overlay.PANEL_MORPH_OUT_MS + 20L

        /** 任务在其他 App 里结束后，悬浮球保留 ✓ / ! 的时长。 */
        const val RESULT_ORB_HOLD_MS = 3_000L

        /** 监听自己结束后悬浮球再留的时长：等唤醒说明的那一轮开始（常驻关时）。 */
        const val MONITORS_ENDED_HOLD_MS = 4_000L

        /** Movo 页面暂停后多久再判断悬浮球显隐（跳过页面之间切换的空档）。 */
        const val APP_LEAVE_SETTLE_MS = 300L
        /** 交接等对话浮层回报的上限（浮层进场最多等内容 400ms + 两帧，留足余量）。 */
        const val SHEET_HANDOFF_TIMEOUT_MS = 1_500L
        const val PANEL_AUTO_COLLAPSE_MS = 4_000L
        /** 从展开卡发起语音后，语音服务报告「没能开始」时转述到展开卡的时间窗。 */
        const val PANEL_VOICE_NOTICE_WINDOW_MS = 3_000L
        const val IME_TRACK_INTERVAL_MS = 60L
        private const val OVERLAY_PREFS = "agent_overlay"
        private const val PREF_IME_HEIGHT = "ime_height_px"
        const val GLOW_FADE_MS = 300L
        /** 「去解锁」后等解锁页面回报的上限；超过就重新显示悬浮卡（解锁提示或原卡）。 */
        const val UNLOCK_REQUEST_TIMEOUT_MS = 30_000L
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
