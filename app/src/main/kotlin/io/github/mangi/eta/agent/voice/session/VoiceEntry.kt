package io.github.mangi.eta.agent.voice.session

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.mangi.eta.agent.voice.EtaAssistantVoiceService

/**
 * 语音的所有入口都收敛在这里。
 *
 * 界面形态由"聊天页是否正在屏幕上"决定，而不是由入口决定：
 * 聊天页可见就地进语音模态，否则打开浮层 Sheet。两种情况用的是同一条会话。
 */
internal object VoiceEntry {
    /** 聊天页或浮层里开始语音：就地开始，不开新窗口，也不需要悬浮窗权限。 */
    fun startInPlace(context: Context) {
        val app = context.applicationContext
        // startForegroundService 之后服务必须转前台，否则系统按超时崩溃处理；
        // 转前台又要求已有麦克风权限。所以不能开始的情况在这里挡掉，不把服务拉起来。
        if (ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(app, "需要麦克风权限才能开始语音", Toast.LENGTH_LONG).show()
            return
        }
        if (VoiceSessionManager.busy) {
            // 已经在语音里就什么都不做；上一段还在断开时明确告诉用户，而不是没反应。
            if (!VoiceSessionManager.active) {
                Toast.makeText(app, "上一段语音正在结束，请稍后再试", Toast.LENGTH_SHORT).show()
            }
            return
        }
        app.startForegroundService(
            Intent(app, EtaAssistantVoiceService::class.java)
                .setAction(EtaAssistantVoiceService.ACTION_START_VOICE),
        )
    }

    /** 唤醒词 / 电源键 / 助手手势：需要先把助手界面摆出来。 */
    fun startFromSystemEntry(context: Context, autoListen: Boolean) {
        // VIS lives in :voice_session. Route in the UI process, whose tracker owns the
        // actual resumed chat. Displaying an assistant never starts a foreground service.
        context.sendBroadcast(Intent(context, AssistantEntryReceiver::class.java)
            .putExtra(EtaAssistantVoiceService.EXTRA_AUTO_LISTEN, autoListen))
    }

    fun end(context: Context) {
        context.applicationContext.startService(
            Intent(context.applicationContext, EtaAssistantVoiceService::class.java)
                .setAction(EtaAssistantVoiceService.ACTION_END_VOICE),
        )
    }
}
