package io.github.mangi.eta.agent.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.wake.WakeEngineFactory
import io.github.mangi.eta.agent.voice.wake.WakeWordEngine
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.model.WakePhraseRules
import io.github.mangi.eta.data.model.WakeSensitivity
import io.github.mangi.eta.data.repository.VoiceSettingsRepository
import io.github.mangi.eta.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground microphone service for local wake-word listening.
 * Does not stream ambient audio to Doubao; ASR starts only after wake / manual mic.
 */
internal class EtaWakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val coordinator = EtaMicSessionCoordinator()
    private var wakeEngine: WakeWordEngine? = null
    private var currentPhrase: String = WakePhraseRules.DEFAULT
    private var sensitivity: WakeSensitivity = WakeSensitivity.Medium
    private var foregroundActive = false
    private var wakeError: String? = null

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
        if (!ensureForeground()) return
        scope.launch {
            VoiceSettingsRepository.wakeSettingsFlow().collectLatest { settings ->
                currentPhrase = settings.effectivePhrase()
                sensitivity = settings.sensitivity
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
        if (!EtaWakeWordController.hasMicPermission(this)) {
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
                wakeEngine?.resume()
                refreshNotification()
            }
            else -> {
                if (ensureForeground()) refreshNotification()
            }
        }
        return if (foregroundActive) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        clearForegroundNotification()
        stopWakeListening()
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureForeground(): Boolean {
        if (!EtaWakeWordController.hasMicPermission(this)) {
            stopSelfSafe()
            return false
        }
        if (foregroundActive) return true
        return try {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            foregroundActive = true
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
        } else {
            wakeEngine?.updateKeywords(currentPhrase, sensitivity)
            if (mayListen) wakeEngine?.resume()
        }
        refreshNotification()
    }

    private fun stopWakeListening() {
        coordinator.onWakeDisabled()
        wakeEngine?.stop()
        wakeEngine = null
    }

    private fun handleWakeDetected(phrase: String) {
        if (!coordinator.onWakeDetected()) {
            wakeEngine?.resume()
            return
        }
        wakeEngine?.pause()
        refreshNotification()
        // Prefer VIS session; fall back to overlay show.
        val sessionOk = EtaVoiceInteractionService.requestSession()
        if (!sessionOk) {
            EtaAssistantOverlayService.show(this, autoListen = true)
        } else {
            EtaAssistantOverlayService.requestAutoListen(this)
        }
        // The overlay resumes wake listening after its dictation session ends.
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
            Intent(this, EtaWakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = wakeError ?: when {
            coordinator.shouldPauseWakeForDictation() -> "语音输入中，唤醒监听已暂停"
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
        if (!EtaWakeWordController.hasMicPermission(this)) {
            stopWakeListening()
            stopSelfSafe()
            return
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification())
    }

    private fun clearForegroundNotification() {
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
        private const val CHANNEL = "eta_wake_word"
        private const val NOTIFICATION_ID = 1108
        const val ACTION_STOP = "io.github.mangi.eta.action.STOP_WAKE_WORD"
        const val ACTION_PAUSE_WAKE = "io.github.mangi.eta.action.PAUSE_WAKE_WORD"
        const val ACTION_RESUME_WAKE = "io.github.mangi.eta.action.RESUME_WAKE_WORD"

        @Volatile
        private var instance: EtaWakeWordService? = null

        fun isRunning(): Boolean = instance != null

        fun start(context: Context) {
            if (!EtaWakeWordController.hasMicPermission(context)) {
                stop(context)
                return
            }
            val app = context.applicationContext
            val intent = Intent(app, EtaWakeWordService::class.java)
            app.startForegroundService(intent)
        }

        fun stop(context: Context) {
            if (instance != null) dispatchToRunningService(ACTION_STOP)
            else {
                context.applicationContext.stopService(Intent(context, EtaWakeWordService::class.java))
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
