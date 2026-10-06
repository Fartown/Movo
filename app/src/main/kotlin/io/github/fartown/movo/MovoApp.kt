package io.github.fartown.movo

import android.app.Application
import android.os.Handler
import android.os.Looper
import io.github.fartown.movo.agent.skill.SkillRuntime
import io.github.fartown.movo.agent.device.RootAccess
import io.github.fartown.movo.agent.terminal.TerminalRuntime
import io.github.fartown.movo.agent.voice.session.VoiceSurfaceTracker
import io.github.fartown.movo.config.Prefs
import io.github.fartown.movo.core.AndroidAgentLogger
import io.github.fartown.movo.core.safeLogType
import io.github.fartown.movo.data.auth.ChatGptAuth
import io.github.fartown.movo.data.auth.ChatGptLoginManager
import io.github.fartown.movo.data.datastore.SettingsDataStore
import io.github.fartown.movo.data.repository.AgentMemoryRepository
import io.github.fartown.movo.data.repository.McpServerRepository
import io.github.fartown.movo.data.repository.LinuxEnvironmentSettingsRepository
import io.github.fartown.movo.data.repository.ProviderRepository
import io.github.fartown.movo.data.repository.VoiceSettingsRepository
import io.github.fartown.movo.flavor.FlavorModule
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    /**
     * 只在 debug 包里：数据目录里有 profile-startup 标记时，从进程启动起采样 20 秒到 files/startup.trace
     * （查进程刚起来时主线程被谁占着；系统 am profile 在电视上写不了文件）。
     */
    private fun debugStartupProfile() {
        if (!BuildConfig.DEBUG) return
        val marker = java.io.File(filesDir, "profile-startup")
        if (!marker.delete()) return
        val trace = java.io.File(filesDir, "startup.trace").apply { delete() }
        android.os.Debug.startMethodTracingSampling(trace.path, 64 * 1024 * 1024, 1000)
        android.os.Handler(mainLooper).postDelayed({ android.os.Debug.stopMethodTracing() }, 20_000)
    }

    override fun onCreate() {
        super.onCreate()
        debugStartupProfile()
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
        AgentMemoryRepository.init(this)
        ProviderRepository.init(this)
        ChatGptAuth.init(this)
        ChatGptLoginManager.init(this)
        McpServerRepository.init(this)
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
        // 设备专属的初始化（手机：唤醒词监听、预测性返回、Xposed 服务监听）。
        FlavorModule.initializers.forEach { it(this) }
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
