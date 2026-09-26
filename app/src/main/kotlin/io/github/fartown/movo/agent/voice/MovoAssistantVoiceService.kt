package io.github.fartown.movo.agent.voice

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
import io.github.fartown.movo.agent.voice.conversation.DoubaoDialogEngine
import io.github.fartown.movo.agent.voice.session.VoiceEntry
import io.github.fartown.movo.agent.voice.session.VoiceSessionManager
import io.github.fartown.movo.agent.voice.session.VoiceSessionOwner
import io.github.fartown.movo.agent.voice.session.VoiceSessionUiState
import io.github.fartown.movo.agent.voice.session.VoiceSurfaceTracker
import io.github.fartown.movo.core.AndroidAgentLogger
import io.github.fartown.movo.ui.AgentConversationSheetActivity

/** Owns microphone FGS lifetime only. Opening/restoring a chat does not start this service. */
internal class MovoAssistantVoiceService : Service(), VoiceSessionOwner.ServiceHost {
    private var foreground = false
    private var notifiedText: String? = null

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
        // VoiceEntry 已经在调用 startForegroundService 之前挡掉这两种情况；这里只剩竞态。
        // 系统规定 startForegroundService 之后必须 startForeground，不转前台就停服务同样按超时崩溃处理，
        // 所以先短暂转前台满足契约再退出。缺麦克风权限时连转前台都会被拒，只能靠入口检查。
        if (VoiceSessionManager.busy) {
            if (!foreground) leaveWithoutVoice()
            return
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请在聊天页允许麦克风权限后开始语音", Toast.LENGTH_LONG).show()
            leaveWithoutVoice()
            return
        }
        val started = runCatching {
            promote("正在连接语音…")
            MovoWakeWordService.pauseWake(this)
            VoiceSessionManager.beginSession(this)
        }.getOrElse {
            AndroidAgentLogger.warn("Voice session start failed: type=${it.javaClass.simpleName}")
            Toast.makeText(this, "暂时无法开始语音，请重试", Toast.LENGTH_LONG).show()
            false
        }
        if (!started) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            MovoWakeWordService.resumeWake(this)
            stopIfIdle()
        }
    }
    override fun onVoiceState(state: VoiceSessionUiState) {
        if (!foreground || !state.active) return
        // 识别中间结果每秒会发布多次，但通知只显示状态文案；文案不变就不刷新，避免被系统限流丢掉关键状态。
        val text = state.statusText.ifBlank { "语音对话进行中" }
        if (text == notifiedText) return
        notifiedText = text
        runCatching { getSystemService(NotificationManager::class.java).notify(VOICE_NOTIFICATION, notification(text)) }
    }

    private fun promote(text: String) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(VOICE_CHANNEL, "语音对话", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(VOICE_NOTIFICATION, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        notifiedText = text
        foreground = true
    }

    private fun leaveWithoutVoice() {
        runCatching { promote("语音对话已结束") }
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
        stopIfIdle()
    }
    override fun onVoiceClosed() {
        notifiedText = null
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
        if (!DoubaoDialogEngine.hasOpenAudio()) MovoWakeWordService.resumeWake(this)
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
            this, 2401, Intent(this, MovoAssistantVoiceService::class.java).setAction(ACTION_END_VOICE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )).build())
        .build()

    internal companion object {
        private const val VOICE_CHANNEL = "movo_voice_conversation"
        private const val VOICE_NOTIFICATION = 2400
        const val ACTION_START_VOICE = "io.github.fartown.movo.agent.voice.START_VOICE"
        const val ACTION_END_VOICE = "io.github.fartown.movo.agent.voice.END_CONVERSATION"
        const val EXTRA_AUTO_LISTEN = "io.github.fartown.movo.agent.voice.extra.AUTO_LISTEN"

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

        /** 旧的 movo_voice 来源任务在 GUI 操作前收起系统助理窗口；新任务的浮层由 Runtime 直接收起。 */
        fun dismissForForegroundOperation(context: Context): Boolean {
            MovoVoiceInteractionSession.requestHideForForegroundOperation(context)
            return true
        }
        /** VIS runs in :voice_session. Send an intent to the actual microphone owner. */
        fun dismiss(context: Context) {
            VoiceEntry.end(context)
            MovoVoiceInteractionSession.requestHideForForegroundOperation(context)
        }
    }
}
