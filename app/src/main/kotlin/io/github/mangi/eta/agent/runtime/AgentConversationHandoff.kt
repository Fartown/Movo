package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.content.Intent
import android.os.ResultReceiver
import io.github.mangi.eta.ui.MainActivity

/** Identifies an existing conversation; opening a result must never create another chat. */
internal data class AgentConversationTarget(val source: String, val key: String) {
    companion object {
        fun from(handoff: AgentRuntimeWire.EntryHandoff?): AgentConversationTarget? {
            handoff ?: return null
            val key = if (handoff.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
                AgentUiHandoffPayload.from(handoff.payload).conversationId
                    .takeUnless { it.startsWith("{") }
            } else {
                AgentExternalArchivePayload.from(handoff.payload)?.conversationKey
            }
            return key?.takeIf { it.isNotBlank() && handoff.source.isNotBlank() }
                ?.let { AgentConversationTarget(handoff.source, it) }
        }
    }
}

/** The result overlay stays visible until the original chat has restored and selected its history. */
internal object AgentConversationHandoff {
    const val ACTION_OPEN = "io.github.mangi.eta.agent.runtime.OPEN_CONVERSATION"
    const val RESULT_READY = 1
    const val RESULT_FAILED = 0
    private const val EXTRA_SOURCE = "conversation_source"
    private const val EXTRA_KEY = "conversation_key"
    private const val EXTRA_RUN_ID = "conversation_run_id"
    private const val EXTRA_CURRENT_CONVERSATION = "current_conversation"
    private const val EXTRA_RECEIVER = "conversation_receiver"

    data class Request(
        val target: AgentConversationTarget,
        val runId: String?,
        val receiver: ResultReceiver?,
    ) {
        fun acknowledge(opened: Boolean) {
            receiver?.send(if (opened) RESULT_READY else RESULT_FAILED, null)
        }
    }

    fun intent(context: Context, target: AgentConversationTarget, runId: String?, receiver: ResultReceiver): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN)
            .putExtra(EXTRA_SOURCE, target.source)
            .putExtra(EXTRA_KEY, target.key)
            .putExtra(EXTRA_RUN_ID, runId)
            .putExtra(EXTRA_CURRENT_CONVERSATION, runId == null)
            .putExtra(EXTRA_RECEIVER, receiver)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun from(intent: Intent?): Request? {
        if (intent?.action != ACTION_OPEN) return null
        val source = intent.getStringExtra(EXTRA_SOURCE)?.takeIf(String::isNotBlank) ?: return null
        val key = intent.getStringExtra(EXTRA_KEY)?.takeIf(String::isNotBlank) ?: return null
        val runId = intent.getStringExtra(EXTRA_RUN_ID)
        // A voice sheet can open the existing app conversation before any task has finished.
        // Result handoffs still require a nonblank run ID and retain history validation.
        if (runId == null) {
            if (!intent.getBooleanExtra(EXTRA_CURRENT_CONVERSATION, false) ||
                source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return null
        } else if (runId.isBlank()) return null
        return Request(AgentConversationTarget(source, key), runId,
            intent.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver::class.java))
    }

    fun consume(intent: Intent) {
        if (intent.action != ACTION_OPEN) return
        intent.action = null
        listOf(EXTRA_SOURCE, EXTRA_KEY, EXTRA_RUN_ID, EXTRA_CURRENT_CONVERSATION, EXTRA_RECEIVER).forEach(intent::removeExtra)
    }
}
