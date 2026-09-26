package io.github.fartown.movo.agent.voice.session

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.fartown.movo.agent.voice.MovoAssistantVoiceService

/**
 * 语音的所有入口都收敛在这里。
 *
 * 界面形态由"聊天页是否正在屏幕上"决定，而不是由入口决定：
 * 聊天页可见就地进语音模态，否则打开浮层 Sheet。两种情况用的是同一条会话。
 */
internal object VoiceEntry {
    /** [startInPlace] 的结果：没能开始时由调用方在自己的界面就地说明（规范 8.11 不用 Toast）。 */
    enum class StartResult {
        STARTED,
        /** 已经在语音里，什么都不做。 */
        ALREADY_ACTIVE,
        /** 上一段语音还在断开。 */
        CLOSING,
        MIC_PERMISSION_REQUIRED,
        /** 系统拒绝启动麦克风服务（例如从后台启动）。 */
        FAILED,
    }

    /**
     * 聊天页或浮层里开始语音：就地开始，不开新窗口，也不需要悬浮窗权限。
     * [showNotice] 为 true 时（调用方是带输入框的聊天页 / 对话浮层），没能开始的原因写进输入框上方的语音提示；
     * 悬浮球等没有输入框的调用方传 false，按返回值在自己的界面说明。
     */
    fun startInPlace(context: Context, showNotice: Boolean = true): StartResult {
        val app = context.applicationContext
        val result = start(app)
        if (showNotice) noticeFor(result)?.let(VoiceSessionManager::showNotice)
        return result
    }

    private fun start(app: Context): StartResult {
        // startForegroundService 之后服务必须转前台，否则系统按超时崩溃处理；
        // 转前台又要求已有麦克风权限。所以不能开始的情况在这里挡掉，不把服务拉起来。
        if (ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return StartResult.MIC_PERMISSION_REQUIRED
        }
        if (VoiceSessionManager.busy) {
            // 已经在语音里就什么都不做；上一段还在断开时明确告诉用户，而不是没反应。
            return if (VoiceSessionManager.active) StartResult.ALREADY_ACTIVE else StartResult.CLOSING
        }
        return runCatching {
            app.startForegroundService(
                Intent(app, MovoAssistantVoiceService::class.java)
                    .setAction(MovoAssistantVoiceService.ACTION_START_VOICE),
            )
            StartResult.STARTED
        }.getOrElse { StartResult.FAILED }
    }

    /** 没能开始的原因（给输入框上方的语音提示用）；开始了或已在语音里时为 null。 */
    fun noticeFor(result: StartResult): String? = when (result) {
        StartResult.MIC_PERMISSION_REQUIRED -> MIC_REQUIRED_NOTICE
        StartResult.CLOSING -> CLOSING_NOTICE
        StartResult.FAILED -> FAILED_NOTICE
        StartResult.STARTED, StartResult.ALREADY_ACTIVE -> null
    }

    const val MIC_REQUIRED_NOTICE = "需要麦克风权限才能开始语音"
    const val CLOSING_NOTICE = "上一段语音正在结束，请稍后再试"
    const val FAILED_NOTICE = "暂时无法开始语音，请重试"

    /** 唤醒词 / 电源键 / 助手手势：需要先把助手界面摆出来。 */
    fun startFromSystemEntry(context: Context, autoListen: Boolean) {
        // VIS lives in :voice_session. Route in the UI process, whose tracker owns the
        // actual resumed chat. Displaying an assistant never starts a foreground service.
        context.sendBroadcast(Intent(context, AssistantEntryReceiver::class.java)
            .putExtra(MovoAssistantVoiceService.EXTRA_AUTO_LISTEN, autoListen))
    }

    fun end(context: Context) {
        context.applicationContext.startService(
            Intent(context.applicationContext, MovoAssistantVoiceService::class.java)
                .setAction(MovoAssistantVoiceService.ACTION_END_VOICE),
        )
    }
}
