package io.github.mangi.eta.agent.voice.conversation

import android.content.Context
import androidx.annotation.Keep
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.diagnostics.MemoryDiagnostics
import io.github.mangi.eta.ui.app.AgentAppSession
import io.github.mangi.eta.ui.app.AgentConversationStore
import org.json.JSONArray
import org.json.JSONObject

/** Stable in-process boundary for testing the actual minified Release APK.
 * No exported Android component: only same-signature instrumentation can install the PCM hooks.
 * Keeping this small boundary avoids disabling R8 or keeping all application state classes.
 */
@Keep
internal object VoiceInstrumentationAccess {
    fun begin(context: Context): String? {
        val state = AgentAppSession.get(context)
        val previous = state.conversationPaneState.selectedConversationId
        state.createConversation()
        EtaAssistantOverlayService.show(context, autoListen = true)
        return previous
    }

    fun end(context: Context) = EtaAssistantOverlayService.dismiss(context)

    fun restore(context: Context, conversationId: String?) {
        val state = AgentAppSession.get(context)
        if (conversationId != null) state.selectConversation(conversationId) else state.createConversation()
    }

    fun selectionSaved(context: Context, conversationId: String): Boolean =
        AgentConversationStore.load(context).selectedConversationId == conversationId

    fun terminalToolsEnabled(): Boolean = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS)

    fun diagnostics(): String = JSONArray().apply {
        MemoryDiagnostics.buffer.snapshot().entries
            .filter { it.category == "voice" || it.event == "response.output" }
            .forEach { entry -> put(JSONObject().put("time_ms", entry.timeMillis)
                .put("event", entry.event).put("details", entry.details)) }
    }.toString(2)
}
