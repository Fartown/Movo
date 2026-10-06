package io.github.fartown.movo.tv

import android.content.Context
import android.view.KeyEvent
import io.github.fartown.movo.agent.voice.session.VoiceChannel
import io.github.fartown.movo.agent.voice.session.VoiceSessionManager
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import io.github.fartown.movo.platform.KeyInterceptor
import io.github.fartown.movo.ui.app.AgentAppSession

/**
 * 语音会话或任务进行中的遥控器（Figma「Movo TV」v2 · 连续对话规则、D 组）：
 * 返回 = 停；朗读时确认 = 打断；方向、主页、媒体等操作键 = 你来操作：停任务、结束对话，按键照常交给前台应用。
 */
internal object TvBackHandler : KeyInterceptor {
    internal const val YIELD_MESSAGE = "你来操作，我先停下"

    private val confirmKeys = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)

    /** 视为「你拿起遥控器操作电视」的键。音量、静音、语音键不算：边说边调音量不该结束对话。 */
    internal val operateKeys: Set<Int> = confirmKeys + setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_INFO,
    ) + (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)

    internal enum class RemoteAction { PassThrough, Interrupt, Hold, Yield }

    private var app: Context? = null
    private var consuming = false
    private var consumingCode = KeyEvent.KEYCODE_UNKNOWN

    fun init(context: Context) { app = context.applicationContext }

    fun cancel(): Boolean = stop("本轮已取消")

    private fun stop(message: String): Boolean {
        val session = app?.let(AgentAppSession::get)
        if (!VoiceSessionManager.active && session?.voiceRuntimeBusy != true) return false
        VoiceSessionManager.cancelTask()
        VoiceSessionManager.end(message)
        session?.stopCurrentRun()
        return true
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (TvConversationOverlay.onKeyEvent(event)) return true
        if (TvVoicePanel.onKeyEvent(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) consuming = cancel()
            val handled = consuming
            if (event.action == KeyEvent.ACTION_UP) consuming = false
            return handled
        }
        // 按下时消费的键，抬起也要消费，前台应用不能只收到半个按键。
        if (consumingCode == event.keyCode) {
            if (event.action == KeyEvent.ACTION_UP) consumingCode = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        val session = app?.let(AgentAppSession::get)
        val action = remoteAction(
            keyCode = event.keyCode,
            channel = VoiceSessionManager.state.value.channel,
            taskRunning = session?.voiceRuntimeBusy == true,
            movoInFront = TvAppSurfaces.visible || TvConversationOverlay.expanded,
        )
        when (action) {
            RemoteAction.PassThrough -> return false
            RemoteAction.Interrupt -> VoiceSessionManager.stopSpeaking()
            RemoteAction.Hold -> Unit
            RemoteAction.Yield -> {
                MemoryDiagnostics.record("tv.remote", "yield", fields = mapOf("key" to KeyEvent.keyCodeToString(event.keyCode)))
                if (stop(YIELD_MESSAGE)) TvVoicePanel.showYield()
                return false
            }
        }
        consumingCode = event.keyCode
        return true
    }

    /**
     * 一次按下该怎么处理。Movo 自己的页面在前台时不接管：那里的方向键就是在用 Movo。
     * 正在听你说话时按确认不交给节目（免得把视频暂停），等停顿后自动发送；实时对话由服务端断句，没有「立即说完」。
     */
    internal fun remoteAction(keyCode: Int, channel: VoiceChannel, taskRunning: Boolean, movoInFront: Boolean): RemoteAction {
        if (movoInFront || keyCode !in operateKeys) return RemoteAction.PassThrough
        val voiceActive = channel != VoiceChannel.Off
        if (!voiceActive && !taskRunning) return RemoteAction.PassThrough
        if (keyCode in confirmKeys) when (channel) {
            VoiceChannel.Speaking -> return RemoteAction.Interrupt
            VoiceChannel.Hearing -> return RemoteAction.Hold
            else -> Unit
        }
        return RemoteAction.Yield
    }
}
