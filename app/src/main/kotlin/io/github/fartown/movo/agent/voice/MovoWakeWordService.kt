package io.github.fartown.movo.agent.voice

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.voice.session.VoiceEntry
import io.github.fartown.movo.agent.voice.session.VoiceSurfaceTracker
import io.github.fartown.movo.agent.voice.wake.WakeEngineFactory
import io.github.fartown.movo.agent.voice.wake.WakeWordEngine
import io.github.fartown.movo.core.AndroidAgentLogger
import io.github.fartown.movo.core.safeLogType
import io.github.fartown.movo.data.model.WakeListenScope
import io.github.fartown.movo.data.model.WakePhraseRules
import io.github.fartown.movo.data.model.WakeSensitivity
import io.github.fartown.movo.data.repository.VoiceSettingsRepository
import io.github.fartown.movo.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground microphone service for local wake-word listening.
 * Does not stream ambient audio to Doubao; ASR starts only after wake / manual mic.
 * Releases the microphone while the screen is off: an open recording keeps the device
 * awake all night. Listening resumes when the screen turns on. With the default
 * [WakeListenScope.AppOpen] it also releases the microphone while no Movo screen is visible.
 */
internal class MovoWakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val coordinator = MovoMicSessionCoordinator()
    private var wakeEngine: WakeWordEngine? = null
    private var currentPhrase: String = WakePhraseRules.DEFAULT
    private var sensitivity: WakeSensitivity = WakeSensitivity.Medium
    private var foregroundActive = false
    private var wakeError: String? = null
    private var screenReceiverRegistered = false
    private var lifecycleRegistered = false
    private val visibilityCheck = Runnable { onAppVisibilityChanged(VoiceSurfaceTracker.appVisible) }
    // VoiceSurfaceTracker registers first, so its count is current when these run. Hiding is
    // debounced so moving between two Movo screens does not reopen the microphone.
    private val appLifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            mainHandler.removeCallbacks(visibilityCheck)
            onAppVisibilityChanged(true)
        }
        override fun onActivityPaused(activity: Activity) {
            mainHandler.removeCallbacks(visibilityCheck)
            mainHandler.postDelayed(visibilityCheck, HIDE_DEBOUNCE_MS)
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenChanged(on = false)
                Intent.ACTION_SCREEN_ON -> onScreenChanged(on = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.wake_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        // New app-initiated starts are gated in start(). A system sticky restart must
        // still be allowed to restore an already established foreground listener.
        if (!ensureForeground()) return
        coordinator.onScreenChanged(getSystemService(PowerManager::class.java).isInteractive)
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            RECEIVER_NOT_EXPORTED,
        )
        screenReceiverRegistered = true
        coordinator.onAppVisibilityChanged(VoiceSurfaceTracker.appVisible)
        application.registerActivityLifecycleCallbacks(appLifecycle)
        lifecycleRegistered = true
        scope.launch {
            VoiceSettingsRepository.wakeSettingsFlow().collectLatest { settings ->
                currentPhrase = settings.effectivePhrase()
                sensitivity = settings.sensitivity
                coordinator.setListenInBackground(settings.listenScope == WakeListenScope.ScreenOn)
                coordinator.setPhrase(currentPhrase)
                refreshNotification()
                if (!settings.wakeEnabled) {
                    stopWakeListening()
                    stopSelfSafe()
                    return@collectLatest
                }
                startWakeListening()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!MovoWakeWordController.hasMicPermission(this)) {
            stopWakeListening()
            stopSelfSafe()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    VoiceSettingsRepository.setWakeEnabled(false)
                }
                stopWakeListening()
                stopSelfSafe()
            }
            ACTION_PAUSE_WAKE -> {
                coordinator.onDictationStarted()
                wakeEngine?.pause()
                refreshNotification()
            }
            ACTION_RESUME_WAKE -> {
                coordinator.onDictationFinished(resumeWake = true)
                if (coordinator.mayCaptureWake()) wakeEngine?.resume()
                refreshNotification()
            }
            else -> {
                if (ensureForeground(force = startId > 0)) refreshNotification()
            }
        }
        return if (foregroundActive) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        if (lifecycleRegistered) {
            application.unregisterActivityLifecycleCallbacks(appLifecycle)
            lifecycleRegistered = false
        }
        clearForegroundNotification()
        stopWakeListening()
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureForeground(force: Boolean = false): Boolean {
        if (!MovoWakeWordController.hasMicPermission(this)) {
            stopSelfSafe()
            return false
        }
        if (foregroundActive && !force) return true
        return try {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            foregroundActive = true
            mutableListeningState.value = WakeListeningState.Starting
            true
        } catch (failure: RuntimeException) {
            AndroidAgentLogger.warn("Wake FGS start failed: type=${failure.safeLogType()}")
            stopSelfSafe()
            false
        }
    }

    private fun startWakeListening() {
        val mayListen = coordinator.onWakeEnabled()
        if (wakeEngine == null) {
            if (!mayListen) return
            wakeError = null
            val engine = WakeEngineFactory.create(this)
            wakeEngine = engine
            engine.start(
                phrase = currentPhrase,
                sensitivity = sensitivity,
                listener = object : WakeWordEngine.Listener {
                    override fun onDetected(phrase: String) {
                        handleWakeDetected(phrase)
                    }

                    override fun onListeningChanged(listening: Boolean) {
                        if (listening) wakeError = null
                        refreshNotification()
                    }

                    override fun onError(message: String) {
                        AndroidAgentLogger.warn("Wake engine: $message")
                        wakeError = message
                        refreshNotification()
                    }
                },
            )
            if (!coordinator.mayCaptureWake()) engine.pause()
        } else {
            wakeEngine?.updateKeywords(currentPhrase, sensitivity)
            if (mayListen && coordinator.mayCaptureWake()) wakeEngine?.resume() else wakeEngine?.pause()
        }
        refreshNotification()
    }

    private fun onScreenChanged(on: Boolean) {
        if (coordinator.onScreenChanged(on)) applyCapture()
    }

    private fun onAppVisibilityChanged(visible: Boolean) {
        if (coordinator.onAppVisibilityChanged(visible)) applyCapture()
    }

    private fun applyCapture() {
        if (coordinator.mayCaptureWake()) wakeEngine?.resume() else wakeEngine?.pause()
        refreshNotification()
    }

    private fun stopWakeListening() {
        coordinator.onWakeDisabled()
        wakeEngine?.stop()
        wakeEngine = null
    }

    private fun handleWakeDetected(phrase: String) {
        if (!coordinator.onWakeDetected()) {
            if (coordinator.isScreenOn()) wakeEngine?.resume()
            return
        }
        wakeEngine?.pause()
        refreshNotification()
        // 已经在 Movo 界面里就就地进语音模态，不再盖一层浮层。
        VoiceEntry.startFromSystemEntry(this, autoListen = true)
        // 语音宿主服务在 SDK 真正销毁后恢复唤醒监听。
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MovoWakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = wakeError ?: when {
            coordinator.shouldPauseWakeForDictation() -> "语音输入中，唤醒监听已暂停"
            !coordinator.isScreenOn() -> "息屏时暂停监听，亮屏后自动恢复"
            coordinator.isWaitingForApp() -> getString(R.string.voice_settings_state_app_hidden)
            wakeEngine?.isRunning() == true -> getString(R.string.wake_notification_waiting, currentPhrase)
            else -> "正在启动本地唤醒"
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.wake_notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.wake_notification_stop),
                    stop,
                ).build(),
            )
            .build()
    }

    private fun refreshNotification() {
        if (!foregroundActive) return
        if (!MovoWakeWordController.hasMicPermission(this)) {
            stopWakeListening()
            stopSelfSafe()
            return
        }
        mutableListeningState.value = when {
            wakeError != null -> WakeListeningState.Failed(wakeError!!)
            coordinator.shouldPauseWakeForDictation() -> WakeListeningState.Dictating
            !coordinator.isScreenOn() -> WakeListeningState.ScreenOff
            coordinator.isWaitingForApp() -> WakeListeningState.AppHidden
            wakeEngine?.isRunning() == true -> WakeListeningState.Listening
            else -> WakeListeningState.Starting
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification())
    }

    private fun clearForegroundNotification() {
        mutableListeningState.value = WakeListeningState.Stopped
        if (foregroundActive) {
            foregroundActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun stopSelfSafe() {
        clearForegroundNotification()
        stopSelf()
    }

    companion object {
        private val mutableListeningState = MutableStateFlow<WakeListeningState>(WakeListeningState.Stopped)
        val listeningState = mutableListeningState.asStateFlow()
        private const val CHANNEL = "movo_wake_word"
        private const val NOTIFICATION_ID = 1108
        private const val HIDE_DEBOUNCE_MS = 1_500L
        const val ACTION_STOP = "io.github.fartown.movo.action.STOP_WAKE_WORD"
        const val ACTION_PAUSE_WAKE = "io.github.fartown.movo.action.PAUSE_WAKE_WORD"
        const val ACTION_RESUME_WAKE = "io.github.fartown.movo.action.RESUME_WAKE_WORD"

        @Volatile
        private var instance: MovoWakeWordService? = null

        fun isRunning(): Boolean = instance != null

        fun start(context: Context) {
            if (!MovoWakeWordController.hasMicPermission(context)) {
                stop(context)
                return
            }
            // An existing foreground listener observes settings itself. Reissuing an FGS
            // start is unnecessary and creates another promotion deadline on some devices.
            if (instance?.foregroundActive == true) return
            if (!VoiceSurfaceTracker.appVisible) return
            val app = context.applicationContext
            val intent = Intent(app, MovoWakeWordService::class.java)
            try {
                app.startForegroundService(intent)
            } catch (failure: RuntimeException) {
                AndroidAgentLogger.warn("Wake service launch failed: type=${failure.safeLogType()}")
                mutableListeningState.value = WakeListeningState.Failed("暂时无法开启唤醒，请返回应用后重试")
            }
        }

        fun stop(context: Context) {
            if (instance != null) dispatchToRunningService(ACTION_STOP)
            else {
                mutableListeningState.value = WakeListeningState.Stopped
                context.applicationContext.stopService(Intent(context, MovoWakeWordService::class.java))
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            }
        }

        fun pauseWake(context: Context) {
            dispatchToRunningService(ACTION_PAUSE_WAKE)
        }

        fun resumeWake(context: Context) {
            dispatchToRunningService(ACTION_RESUME_WAKE)
        }

        private fun dispatchToRunningService(action: String) {
            val service = instance ?: return
            val dispatch = Runnable {
                if (instance === service) service.onStartCommand(Intent().setAction(action), 0, 0)
            }
            if (Looper.myLooper() == Looper.getMainLooper()) dispatch.run()
            else service.mainHandler.post(dispatch)
        }

        fun syncFromSettings(context: Context, enabled: Boolean, micGranted: Boolean) {
            if (enabled && micGranted) {
                start(context)
            } else {
                stop(context)
            }
        }
    }
}

internal sealed interface WakeListeningState {
    data object Stopped : WakeListeningState
    data object Starting : WakeListeningState
    data object Listening : WakeListeningState
    data object Dictating : WakeListeningState
    data object ScreenOff : WakeListeningState
    data object AppHidden : WakeListeningState
    data class Failed(val message: String) : WakeListeningState
}
