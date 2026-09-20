package io.github.mangi.eta.agent.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.SpeechRecognizer
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
    private var audioManager: AudioManager? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                coordinator.onCallInterrupted()
                wakeEngine?.pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (coordinator.onCallResumed()) {
                    wakeEngine?.resume()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(AudioManager::class.java)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.wake_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        ensureForeground()
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
                ensureForeground()
                refreshNotification()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        stopWakeListening()
        scope.cancel()
        abandonAudioFocus()
        super.onDestroy()
    }

    private fun ensureForeground() {
        if (foregroundActive) return
        try {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            foregroundActive = true
        } catch (failure: RuntimeException) {
            AndroidAgentLogger.warn("Wake FGS start failed: type=${failure.safeLogType()}")
            stopSelf()
        }
    }

    private fun startWakeListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            AndroidAgentLogger.warn("Wake recognition unavailable on device")
        }
        requestAudioFocus()
        coordinator.onWakeEnabled()
        if (wakeEngine == null) {
            val engine = WakeEngineFactory.create(this)
            wakeEngine = engine
            engine.start(
                phrase = currentPhrase,
                sensitivity = sensitivity,
                listener = object : WakeWordEngine.Listener {
                    override fun onDetected(phrase: String) {
                        handleWakeDetected(phrase)
                    }

                    override fun onError(message: String) {
                        AndroidAgentLogger.warn("Wake engine: $message")
                        refreshNotification(statusOverride = message)
                    }
                },
            )
        } else {
            wakeEngine?.updateKeywords(currentPhrase, sensitivity)
            wakeEngine?.resume()
        }
        refreshNotification()
    }

    private fun stopWakeListening() {
        coordinator.onWakeDisabled()
        wakeEngine?.stop()
        wakeEngine = null
        abandonAudioFocus()
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
        // Auto dictation is started by overlay when autoListen is set.
        mainHandler.postDelayed({
            if (coordinator.currentPhase() == EtaMicSessionCoordinator.Phase.Dictating) {
                // Overlay owns dictation UI; resume wake after timeout if still paused.
            }
        }, 30_000L)
    }

    private fun notification(statusOverride: String? = null): Notification {
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
        val text = statusOverride ?: getString(
            R.string.wake_notification_waiting,
            currentPhrase,
        )
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

    private fun refreshNotification(statusOverride: String? = null) {
        if (!foregroundActive) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(statusOverride))
    }

    private fun requestAudioFocus() {
        audioManager?.requestAudioFocus(
            audioFocusChangeListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
        )
    }

    private fun abandonAudioFocus() {
        audioManager?.abandonAudioFocus(audioFocusChangeListener)
    }

    private fun stopSelfSafe() {
        if (foregroundActive) {
            foregroundActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
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
            val app = context.applicationContext
            val intent = Intent(app, EtaWakeWordService::class.java)
            app.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            app.startService(
                Intent(app, EtaWakeWordService::class.java).setAction(ACTION_STOP),
            )
        }

        fun pauseWake(context: Context) {
            val app = context.applicationContext
            app.startService(
                Intent(app, EtaWakeWordService::class.java).setAction(ACTION_PAUSE_WAKE),
            )
        }

        fun resumeWake(context: Context) {
            val app = context.applicationContext
            app.startService(
                Intent(app, EtaWakeWordService::class.java).setAction(ACTION_RESUME_WAKE),
            )
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
