package io.github.fartown.movo.agent.voice.conversation

import android.content.Context
import androidx.annotation.Keep
import io.github.fartown.movo.agent.runtime.AgentEvent
import io.github.fartown.movo.agent.runtime.AgentRuntimeWire
import io.github.fartown.movo.agent.voice.session.VoiceConversationHost
import io.github.fartown.movo.agent.voice.session.VoiceSessionManager
import io.github.fartown.movo.agent.voice.session.VoiceEntry
import io.github.fartown.movo.config.Prefs
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import io.github.fartown.movo.ui.app.AgentAppSession
import io.github.fartown.movo.ui.app.AgentConversationStore
import io.github.fartown.movo.ui.model.PendingImageUi
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
        VoiceEntry.startFromSystemEntry(context, autoListen = true)
        return previous
    }

    /**
     * Deterministic foreground entry used by overlay instrumentation after MainActivity is already
     * resumed. It avoids making a fresh device's assistant-role/PendingIntent route a prerequisite
     * for exercising the actual Voice foreground service and SessionManager.
     */
    fun beginInPlace(context: Context): String? {
        val state = AgentAppSession.get(context)
        val previous = state.conversationPaneState.selectedConversationId
        state.createConversation()
        check(VoiceEntry.startInPlace(context) == VoiceEntry.StartResult.STARTED) {
            "Unable to start the foreground voice session in place"
        }
        return previous
    }

    fun beginFixture(context: Context): String? {
        val state = AgentAppSession.get(context)
        val previous = state.conversationPaneState.selectedConversationId
        check(VoiceSessionManager.beginInstrumentationFixture(context, InstrumentationVoiceHost)) {
            "Unable to start the deterministic voice fixture"
        }
        return previous
    }

    private object InstrumentationVoiceHost : VoiceConversationHost {
        private const val CONVERSATION_ID = "instrumentation-voice"
        override val voiceSelectedConversationId: String = CONVERSATION_ID
        override val voiceRuntimeBusy: Boolean = true
        override fun voiceConversationId(): String = CONVERSATION_ID
        override fun retainVoiceDraft(conversationId: String, text: String, images: List<PendingImageUi>) = Unit
        override fun pendingVoiceImages(conversationId: String): List<PendingImageUi> = emptyList()
        override fun sendVoiceMessage(
            conversationId: String,
            runId: String,
            prompt: String,
            images: List<PendingImageUi>,
            voiceSessionId: String,
            onEvent: (AgentEvent) -> Unit,
            onResult: (AgentRuntimeWire.RunResult) -> Unit,
        ) = error("Deterministic voice fixture cannot dispatch a model request")
        override fun cancelVoiceRun(runId: String) = Unit
        override fun stopCurrentRun() = Unit
        override fun steerVoiceTurn(conversationId: String, text: String, onResult: (Boolean) -> Unit) =
            onResult(true)
    }

    fun end(context: Context) = VoiceEntry.end(context)

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
