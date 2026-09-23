package io.github.mangi.eta.agent.voice.session

import android.content.Context
import androidx.annotation.MainThread
import androidx.compose.runtime.snapshotFlow
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.voice.conversation.VoiceConversationController
import io.github.mangi.eta.agent.voice.conversation.VoiceConversationSession
import io.github.mangi.eta.agent.voice.conversation.VoiceReplyContent
import io.github.mangi.eta.agent.voice.conversation.VoiceTurnCoordinator
import io.github.mangi.eta.ui.app.AgentAppSession
import io.github.mangi.eta.ui.model.PendingImageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.UUID

/** The existing AppState owns conversations and admission; this port does not store another history. */
internal interface VoiceConversationHost {
    val voiceSelectedConversationId: String?
    val voiceRuntimeBusy: Boolean
    fun voiceConversationId(): String
    fun retainVoiceDraft(conversationId: String, text: String, images: List<PendingImageUi> = emptyList())
    fun pendingVoiceImages(conversationId: String): List<PendingImageUi>
    fun sendVoiceMessage(
        conversationId: String, runId: String, prompt: String, images: List<PendingImageUi>,
        voiceSessionId: String, onEvent: (AgentEvent) -> Unit,
        onResult: (AgentRuntimeWire.RunResult) -> Unit,
    )
    fun cancelVoiceRun(runId: String)
    fun stopCurrentRun()
}

/** One process-wide owner in production; injectable boundaries exercise real lifecycle ordering in tests. */
internal object VoiceSessionManager : VoiceSessionOwner()

internal open class VoiceSessionOwner(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val createController: (Context, VoiceConversationController.Host) -> VoiceConversationSession =
        { context, host -> VoiceConversationController(context, host) },
) {
    interface ServiceHost {
        fun onVoiceState(state: VoiceSessionUiState)
        fun onVoiceClosed()
    }

    private val mutableState = MutableStateFlow(VoiceSessionUiState())
    val state: StateFlow<VoiceSessionUiState> = mutableState.asStateFlow()
    private var host: ServiceHost? = null
    private var app: VoiceConversationHost? = null
    private var controller: VoiceConversationSession? = null
    private var conversationId: String? = null
    private var activeRunId: String? = null
    private var queuedTurn: VoiceTurnCoordinator.Turn? = null
    private var queuedSessionId = ""
    private var drainJob: Job? = null
    private var epoch = 0L
    private var accepting = false
    private var retainedTranscript = false
    val active: Boolean get() = state.value.active
    // Closing audio still owns the microphone, even after the visible mode has changed.
    val busy: Boolean get() = controller != null

    @MainThread
    fun attach(serviceHost: ServiceHost, context: Context) =
        attach(serviceHost, AgentAppSession.get(context.applicationContext))

    internal fun attach(serviceHost: ServiceHost, conversations: VoiceConversationHost) {
        host = serviceHost
        app = conversations
    }

    @MainThread
    fun detach(serviceHost: ServiceHost) {
        if (host !== serviceHost) return
        end()
        host = null
    }

    @MainThread
    fun beginSession(context: Context): Boolean {
        val conversations = app ?: return false
        if (busy) return active
        val id = runCatching { conversations.voiceConversationId() }.getOrElse {
            publish("ended", false, it.message ?: "暂时无法开始语音", "")
            return false
        }
        conversationId = id
        accepting = true
        retainedTranscript = false
        val generation = ++epoch
        controller = createController(context.applicationContext, controllerHost(generation))
        controller?.start()
        if (accepting) drainJob = scope.launch {
            snapshotFlow { conversations.voiceRuntimeBusy to conversations.voiceSelectedConversationId }
                .distinctUntilChanged().collect { (running, selected) ->
                    if (selected != conversationId) onConversationChanging(selected)
                    else if (!running) drainQueuedTurn(generation)
                }
        }
        return true
    }

    /** Called before AppState changes the selected conversation or removes its history. */
    fun onConversationChanging(nextId: String?) {
        if (conversationId != null && conversationId != nextId) end("已切换对话，语音已结束")
    }

    @MainThread
    fun end(message: String = "语音对话已结束") {
        freezeInput()
        val current = controller
        if (current == null) {
            publish("ended", false, message, "")
            host?.onVoiceClosed()
        } else if (current.active) current.end(message)
    }

    fun switchToText() { if (active) end("已切到文字输入") }
    fun stopSpeaking() { if (active) controller?.stopSpeaking() }
    fun cancelTask() {
        val conversations = app ?: return
        val waiting = queuedTurn
        queuedTurn = null
        if (waiting != null) controller?.result(waiting.id, "", failure = "已取消待发送消息")
        activeRunId?.let(conversations::cancelVoiceRun)
            ?: if (conversationId == conversations.voiceSelectedConversationId) conversations.stopCurrentRun() else Unit
    }
    fun ownsConversation(id: String?): Boolean = active && id != null && id == conversationId

    private fun valid(generation: Long) = generation == epoch && accepting && controller?.active == true

    /** Revoke dispatch synchronously, before the SDK's asynchronous close or any late result. */
    private fun freezeInput() {
        accepting = false
        drainJob?.cancel(); drainJob = null
        val queued = queuedTurn
        queuedTurn = null
        activeRunId = null
        if (queued != null) conversationId?.let { app?.retainVoiceDraft(it, queued.text) }
    }

    private fun publish(event: String, active: Boolean, status: String, transcript: String) {
        mutableState.value = state.value.copy(
            channel = VoiceSessionUiState.channelFor(event, active, state.value.channel),
            statusText = status, transcript = transcript,
            conversationId = if (active) conversationId else null,
        )
        host?.onVoiceState(state.value)
    }

    private fun drainQueuedTurn(generation: Long) {
        if (!valid(generation) || app?.voiceRuntimeBusy != false) return
        val next = queuedTurn ?: return
        queuedTurn = null
        dispatch(next, queuedSessionId, generation)
    }

    private fun dispatch(turn: VoiceTurnCoordinator.Turn, session: String, generation: Long) {
        if (!valid(generation)) return
        val conversations = app ?: return
        val id = conversationId ?: return
        if (id != conversations.voiceSelectedConversationId) { end("已切换对话，语音已结束"); return }
        if (conversations.voiceRuntimeBusy) {
            queuedTurn = turn; queuedSessionId = session
            publish("dispatch", true, "已记下这句话，等当前任务完成后发送", "")
            return
        }
        val run = UUID.randomUUID().toString()
        activeRunId = run
        val images = conversations.pendingVoiceImages(id)
        var rejectionHandled = false
        conversations.sendVoiceMessage(
            id, run, turn.text, images, session,
            onEvent = { event -> scope.launch {
                if (valid(generation) && activeRunId == run) controller?.runtimeEvent(turn.id, event)
            } },
            onResult = { result -> scope.launch {
                if (result.resultKind == "rejected") {
                    // Admission failed: restore the exact request, even if audio closed meanwhile.
                    // Never automatically retry a request that may already have been archived.
                    if (!rejectionHandled) {
                        rejectionHandled = true
                        conversations.retainVoiceDraft(id, turn.text, images)
                    }
                    if (valid(generation) && activeRunId == run) {
                        // Closing also retains any later utterance. Do not let that utterance
                        // automatically consume the rejected request's restored attachments.
                        end("这句话未发送，内容和附件已保留，请检查后重试")
                    }
                    return@launch
                }
                if (!valid(generation) || activeRunId != run) return@launch
                activeRunId = null
                when (result.resultKind) {
                    "unconfirmed" -> controller?.result(turn.id, "", confirmed = false)
                    else -> controller?.result(turn.id, VoiceReplyContent.body(result),
                        failure = if (result.ok) null else result.error ?: "任务未完成")
                }
            } },
        )
    }

    private fun controllerHost(generation: Long) = object : VoiceConversationController.Host {
        override fun onState(event: String, active: Boolean, status: String, transcript: String) {
            if (generation != epoch) return
            if (!active) {
                freezeInput()
                if (!retainedTranscript) {
                    retainedTranscript = true
                    if (transcript.isNotBlank()) conversationId?.let { app?.retainVoiceDraft(it, transcript) }
                }
            }
            publish(event, active && accepting, status, transcript)
        }
        override fun submit(turn: VoiceTurnCoordinator.Turn, sessionId: String) = dispatch(turn, sessionId, generation)
        override fun cancelTask() { if (valid(generation)) this@VoiceSessionOwner.cancelTask() }
        override fun onClosed() {
            if (generation != epoch) return
            freezeInput()
            controller = null
            conversationId = null
            host?.onVoiceClosed()
        }
    }
}
