package io.github.fartown.movo

import android.app.Application
import android.os.Handler
import android.os.Looper
import io.github.fartown.movo.agent.skill.SkillRuntime
import io.github.fartown.movo.agent.device.RootAccess
import io.github.fartown.movo.agent.terminal.TerminalRuntime
import io.github.fartown.movo.agent.voice.MovoWakeWordController
import io.github.fartown.movo.agent.voice.session.VoiceSurfaceTracker
import io.github.fartown.movo.config.Prefs
import io.github.fartown.movo.core.AndroidAgentLogger
import io.github.fartown.movo.core.safeLogType
import io.github.fartown.movo.data.auth.ChatGptAuth
import io.github.fartown.movo.data.auth.ChatGptLoginManager
import io.github.fartown.movo.data.datastore.SettingsDataStore
import io.github.fartown.movo.data.repository.AgentMemoryRepository
import io.github.fartown.movo.data.repository.AppearanceSettingsRepository
import io.github.fartown.movo.data.repository.McpServerRepository
import io.github.fartown.movo.data.repository.LinuxEnvironmentSettingsRepository
import io.github.fartown.movo.data.repository.ProviderRepository
import io.github.fartown.movo.data.repository.VoiceSettingsRepository
import io.github.fartown.movo.ui.app.PredictiveBackController
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 模块 UI 进程的 Application。
 *
 * 在进程启动时注册 [XposedServiceHelper] 监听器，框架会通过 XposedProvider 推送 binder，
 * 随后 UI 即可拿到 [XposedService] 写入 RemotePreferences，跨进程同步到各 hook 进程。
 *
 * UI 侧通过 [XposedService] 写入 RemotePreferences。
 */
class MovoApp : Application(), XposedServiceHelper.OnServiceListener {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.initLocal(this)
        if (!AppProcessPolicy.shouldInitializeFullRuntime(Application.getProcessName(), packageName)) {
            return
        }
        io.github.fartown.movo.diagnostics.DiagnosticsEnvironment.initialize(this)
        TerminalRuntime.initialize(this)
        RootAccess.initialize(this)
        SettingsDataStore.init(this)
        VoiceSettingsRepository.init(this)
        VoiceSurfaceTracker.install(this)
        MovoWakeWordController.startObserving(this)
        val predictiveBackEnabled = runBlocking(Dispatchers.IO) {
            AppearanceSettingsRepository.settings().predictiveBackEnabled
        }
        PredictiveBackController.apply(applicationInfo, predictiveBackEnabled)
        AgentMemoryRepository.init(this)
        ProviderRepository.init(this)
        ChatGptAuth.init(this)
        ChatGptLoginManager.init(this)
        McpServerRepository.init(this)
        XposedServiceHelper.registerListener(this)
        applicationScope.launch {
            LinuxEnvironmentSettingsRepository.initialize(this@MovoApp)
            runCatching {
                SkillRuntime.createIndexService(this@MovoApp).listInstalledSkills()
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Agent skill index prewarm failed: type=${throwable.safeLogType()}"
                )
            }
        }
    }

    override fun onServiceBind(service: XposedService) {
        serviceInstance = service
        Prefs.reconcileAgentPreferences(service)
        dispatch(service)
    }

    override fun onServiceDied(service: XposedService) {
        // 只有当前持有的 service 死亡时才清空并派发 null；
        // 多 framework 场景下死掉的可能是已被替换的旧实例，无需影响 UI。
        if (serviceInstance === service) {
            serviceInstance = null
            dispatch(null)
        }
    }

    companion object {
        @Volatile
        var serviceInstance: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            listeners.add(listener)
            if (notifyImmediately) {
                dispatchTo(listener, serviceInstance)
            }
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }

        private fun dispatch(service: XposedService?) {
            listeners.forEach { dispatchTo(it, service) }
        }

        private fun dispatchTo(listener: ServiceStateListener, service: XposedService?) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onServiceStateChanged(service)
            } else {
                mainHandler.post {
                    if (listeners.contains(listener)) {
                        listener.onServiceStateChanged(service)
                    }
                }
            }
        }
    }
}
