package io.github.mangi.eta.agent.voice

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.mangi.eta.agent.voice.conversation.DoubaoDialogEngine
import io.github.mangi.eta.agent.voice.session.VoiceEntry
import io.github.mangi.eta.agent.voice.session.VoiceSessionManager
import io.github.mangi.eta.agent.voice.session.VoiceSessionOwner
import io.github.mangi.eta.agent.voice.session.VoiceSessionUiState
import io.github.mangi.eta.agent.voice.session.VoiceSurfaceTracker
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.AgentConversationSheetActivity

/** Owns microphone FGS lifetime only. Opening/restoring a chat does not start this service. */
internal class EtaAssistantVoiceService : Service(), VoiceSessionOwner.ServiceHost {
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        VoiceSessionManager.attach(this, this)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_VOICE -> startVoice()
            ACTION_END_VOICE -> VoiceSessionManager.end()
            else -> { showAssistant(this, false); stopIfIdle() }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        VoiceSessionManager.detach(this)
        super.onDestroy()
    }

    private fun startVoice() {
        if (VoiceSessionManager.busy) {
            if (!foreground) stopSelf()
            return
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请在聊天页允许麦克风权限后开始语音", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        val started = runCatching {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(VOICE_CHANNEL, "语音对话", NotificationManager.IMPORTANCE_LOW),
            )
            startForeground(VOICE_NOTIFICATION, notification("正在连接语音…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            foreground = true
            EtaWakeWordService.pauseWake(this)
            VoiceSessionManager.beginSession(this)
        }.getOrElse {
            AndroidAgentLogger.warn("Voice session start failed: type=${it.javaClass.simpleName}")
            Toast.makeText(this, "暂时无法开始语音，请重试", Toast.LENGTH_LONG).show()
            false
        }
        if (!started) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            EtaWakeWordService.resumeWake(this)
            stopIfIdle()
        }
    }
    override fun onVoiceState(state: VoiceSessionUiState) {
        if (!foreground || !state.active) return
        runCatching { getSystemService(NotificationManager::class.java).notify(VOICE_NOTIFICATION,
            notification(state.statusText.ifBlank { "语音对话进行中" })) }
    }
    override fun onVoiceClosed() {
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
        if (!DoubaoDialogEngine.hasOpenAudio()) EtaWakeWordService.resumeWake(this)
        stopIfIdle()
    }
    private fun stopIfIdle() { if (!VoiceSessionManager.busy) stopSelf() }
    private fun notification(text: String): Notification = Notification.Builder(this, VOICE_CHANNEL)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Movo 语音对话")
        .setContentText(text)
        .setContentIntent(assistantPendingIntent(this, autoListen = false))
        .setOngoing(true)
        .addAction(Notification.Action.Builder(null, "结束语音", PendingIntent.getService(
            this, 2401, Intent(this, EtaAssistantVoiceService::class.java).setAction(ACTION_END_VOICE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )).build())
        .build()

    internal companion object {
        private const val VOICE_CHANNEL = "movo_voice_conversation"
        private const val VOICE_NOTIFICATION = 2400
        const val ACTION_START_VOICE = "io.github.mangi.eta.agent.voice.START_VOICE"
        const val ACTION_END_VOICE = "io.github.mangi.eta.agent.voice.END_CONVERSATION"
        const val EXTRA_AUTO_LISTEN = "io.github.mangi.eta.agent.voice.extra.AUTO_LISTEN"

        /** Safe across processes: the Activity opens first, then starts audio while visible. */
        fun showAssistant(context: Context, autoListen: Boolean) {
            if (VoiceSurfaceTracker.chatVisible) {
                if (autoListen) VoiceEntry.startInPlace(context)
                return
            }
            val senderOptions = ActivityOptions.makeBasic().apply {
                pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
            runCatching { assistantPendingIntent(context, autoListen).send(senderOptions.toBundle()) }
                .onFailure { AndroidAgentLogger.warn("Assistant sheet launch failed") }
        }

        private fun assistantPendingIntent(context: Context, autoListen: Boolean): PendingIntent {
            val intent = Intent(context, AgentConversationSheetActivity::class.java)
                .setAction(AgentConversationSheetActivity.ACTION_ASSISTANT)
                .putExtra(EXTRA_AUTO_LISTEN, autoListen)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            val options = ActivityOptions.makeBasic().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    pendingIntentCreatorBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
            }
            return PendingIntent.getActivity(context, if (autoListen) 0x455443 else 0x455442, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options.toBundle())
        }

        fun dismissForForegroundOperation(context: Context): Boolean {
            EtaVoiceInteractionSession.requestHideForForegroundOperation(context)
            return true
        }
        /** VIS runs in :voice_session. Send an intent to the actual microphone owner. */
        fun dismiss(context: Context) {
            VoiceEntry.end(context)
            EtaVoiceInteractionSession.requestHideForForegroundOperation(context)
        }
    }
}
