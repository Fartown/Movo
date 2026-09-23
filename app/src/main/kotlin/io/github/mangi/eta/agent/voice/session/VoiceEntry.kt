package io.github.mangi.eta.agent.voice.session

import android.content.Context
import android.content.Intent
import io.github.mangi.eta.agent.voice.EtaAssistantVoiceService

/**
 * 语音的所有入口都收敛在这里。
 *
 * 界面形态由"当前 Movo 是否在前台"决定，而不是由入口决定：
 * 已经在 Movo 里就就地进语音模态，不在就打开浮层 Sheet。两种情况用的是同一条会话。
 */
internal object VoiceEntry {
    /** App 页里点麦克风：就地开始语音，不开新窗口，也不需要悬浮窗权限。 */
    fun startInPlace(context: Context) {
        context.applicationContext.startForegroundService(
            Intent(context.applicationContext, EtaAssistantVoiceService::class.java)
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
