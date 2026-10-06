package io.github.fartown.movo.tv

import android.content.Context
import android.view.KeyEvent
import io.github.fartown.movo.agent.voice.session.VoiceSessionManager
import io.github.fartown.movo.platform.KeyInterceptor
import io.github.fartown.movo.ui.app.AgentAppSession

internal object TvBackHandler : KeyInterceptor {
    private var app: Context? = null
    private var consuming = false
    fun init(context: Context) { app = context.applicationContext }
    fun cancel(): Boolean {
        val session = app?.let(AgentAppSession::get)
        if (!VoiceSessionManager.active && session?.voiceRuntimeBusy != true) return false
        VoiceSessionManager.cancelTask()
        VoiceSessionManager.end("本轮已取消")
        session?.stopCurrentRun()
        return true
    }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (TvConversationOverlay.onKeyEvent(event)) return true
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) consuming = cancel()
        val handled = consuming
        if (event.action == KeyEvent.ACTION_UP) consuming = false
        return handled
    }
}
