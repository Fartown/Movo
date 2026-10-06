package io.github.fartown.movo.tv

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshotFlow
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import io.github.fartown.movo.agent.voice.session.VoiceSessionManager
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import io.github.fartown.movo.ui.app.AgentAppSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 遥控器按键只在 Movo 工作时才先经过 Movo：语音会话进行中、有任务在执行、对话浮窗或选项小卡显示着。
 * 平时（看电视、Movo 空闲）一律直接交给电视：Movo 进程在启动、卡住或崩溃时，遥控器也不受影响。
 * 无障碍配置里默认不拦（不带 flagRequestFilterKeyEvents），由这里按状态打开、关闭。
 */
internal object TvKeyFilter {
    private val main = Handler(Looper.getMainLooper())
    private var initialized = false
    /** 现在是否在拦；null = 还没对当前无障碍实例设置过。 */
    private var filtering: Boolean? = null
    private val applyPending = AtomicBoolean(false)

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        // 无障碍实例换了（重连、重启）：重新按当前状态设置。
        AgentAccessibilityService.addInstanceListener { filtering = null; update() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch { VoiceSessionManager.state.collect { update() } }
        // 任务状态在会话里（Compose 状态）；会话建好后再观察，不为了看按键去建它。
        AgentAppSession.onCreated { session ->
            Snapshot.registerGlobalWriteObserver {
                if (applyPending.compareAndSet(false, true)) main.post { applyPending.set(false); Snapshot.sendApplyNotifications() }
            }
            scope.launch { snapshotFlow { session.voiceRuntimeBusy }.collect { update() } }
        }
        update()
    }

    /**
     * Movo 这时需不需要先看遥控器按键。[overlayEnabled]：用户打开了浮窗模式（菜单键收起 / 展开浮窗），
     * 这是用户自己选的，拦截一直开着。
     */
    internal fun keysNeeded(
        voiceActive: Boolean, taskRunning: Boolean, overlayExpanded: Boolean, choicesShowing: Boolean,
        overlayEnabled: Boolean = false,
    ) = voiceActive || taskRunning || overlayExpanded || choicesShowing || overlayEnabled

    fun update() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(::update); return }
        val service = AgentAccessibilityService.current() ?: return
        val needed = keysNeeded(
            voiceActive = VoiceSessionManager.state.value.active,
            taskRunning = AgentAppSession.peek()?.voiceRuntimeBusy == true,
            overlayExpanded = TvConversationOverlay.expanded,
            choicesShowing = TvVoicePanel.showingChoices,
            overlayEnabled = TvConversationOverlay.enabled,
        )
        // 按下时被 Movo 收下的键，等它抬起再关，前台应用不会只收到半个按键（超过 2 秒当作抬起丢了）。
        if (!needed && (TvBackHandler.holdingKey || TvVoicePanel.holdingChoiceKey)) {
            main.postDelayed(::update, TvBackHandler.HOLD_TIMEOUT_MS)
            return
        }
        if (needed == filtering) return
        val info = service.serviceInfo ?: return
        info.flags = if (needed) info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            else info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        service.serviceInfo = info
        filtering = needed
        MemoryDiagnostics.record("tv.remote", "filter", fields = mapOf("on" to needed))
    }
}
