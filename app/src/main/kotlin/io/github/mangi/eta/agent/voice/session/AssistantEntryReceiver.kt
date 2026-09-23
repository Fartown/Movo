package io.github.mangi.eta.agent.voice.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.agent.voice.EtaAssistantVoiceService

/** Main-process entry bridge; it owns neither a window nor a microphone lifetime. */
internal class AssistantEntryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EtaAssistantVoiceService.showAssistant(context,
            intent.getBooleanExtra(EtaAssistantVoiceService.EXTRA_AUTO_LISTEN, false))
    }
}
