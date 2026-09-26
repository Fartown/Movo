package io.github.fartown.movo.agent.voice.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.fartown.movo.agent.voice.MovoAssistantVoiceService

/** Main-process entry bridge; it owns neither a window nor a microphone lifetime. */
internal class AssistantEntryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MovoAssistantVoiceService.showAssistant(context,
            intent.getBooleanExtra(MovoAssistantVoiceService.EXTRA_AUTO_LISTEN, false))
    }
}
