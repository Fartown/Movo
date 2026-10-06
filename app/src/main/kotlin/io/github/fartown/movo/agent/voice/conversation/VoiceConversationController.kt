package io.github.fartown.movo.agent.voice.conversation

import io.github.fartown.movo.flavor.FlavorModule
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.Keep
import io.github.fartown.movo.data.repository.VoiceSettingsRepository
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import java.util.UUID

/** Recording boundary; orchestration can be exercised without opening native audio. */
internal interface VoiceConversationSession {
    val active: Boolean
    val busy: Boolean
    fun start()
    fun end(message: String = "语音对话已结束")
    fun stopSpeaking()
    fun result(turn: Long, answer: String, confirmed: Boolean = true, failure: String? = null)
    fun runtimeEvent(turn: Long, event: io.github.fartown.movo.agent.runtime.AgentEvent)
}

/** Main-thread owner of one continuous voice session; windows may come and go independently. */
@Keep
internal class VoiceConversationController(
    private val context: Context,
    private val host: Host,
) : VoiceConversationSession {
    interface Host {
        /** event 是控制器的稳定事件名；界面据此派生通道状态，不解析状态文案。 */
        fun onState(event: String, active: Boolean, status: String, transcript: String)
        fun submit(turn: VoiceTurnCoordinator.Turn, sessionId: String)
        fun cancelTask()
        fun onClosed()
        /** 这次语音所在的对话还有后台监听在跑（「结束任务」要连它们一起结束）。 */
        fun hasBackgroundTask(): Boolean = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val turns = VoiceTurnCoordinator(FlavorModule.voiceLocalCommands, hasBackgroundTask = { host.hasBackgroundTask() })
    private val commitGate = VoiceCommitGate()
    private var engine: DoubaoDialogEngine? = null
    private var generation = 0L
    private var sessionId = ""
    private var status = ""
    private var idleSince = 0L
    private var utteranceSince = 0L
    private var commit: Runnable? = null
    /** 本轮里 [FlavorModule.voiceHandoffTools] 中的工具已成功：回答念完（或没有回答）就结束会话。 */
    private var handoff = false
    override val active: Boolean get() = turns.active
    override val busy: Boolean get() = engine != null || turns.running != null

    override fun start() {
        if (busy) return
        // The controller survives while its overlay stays open. A new connection must not
        // inherit the previous session's expired idle/utterance deadlines (R15 UI regression).
        idleSince = 0
        utteranceSince = 0
        turns.start()
        sessionId = UUID.randomUUID().toString()
        val current = ++generation
        status = "正在连接语音…"
        publish("connecting")
        engine = DoubaoDialogEngine(context, object : DoubaoDialogEngine.Listener {
            private fun valid() = current == generation && turns.active
            override fun onReady() { if (valid()) { status = "我在听，说完会自动发送"; publish("listening"); tick(current) } }
            override fun onSpeechStarted(turn: Long) {
                if (!valid()) return
                // 你接着说了，说明还要继续，不按「打开 App 就结束」处理。
                handoff = false
                commit?.let(main::removeCallbacks)
                utteranceSince = SystemClock.elapsedRealtime()
                idleSince = 0
                apply(turns.speechStarted(turn))
                status = "正在听你说…"
                publish("hearing", turn)
            }
            override fun onPartial(turn: Long, text: String) {
                if (!valid()) return
                apply(turns.partial(turn, text))
                publish("transcript", turn)
            }
            override fun onInputActivity(atElapsedMs: Long) {
                if (!valid()) return
                commitGate.activity(atElapsedMs)
                if (turns.candidate != null) observer?.invoke("input.activity", turns.latestTurnId, "", "")
            }
            override fun onSpeechEnded(turn: Long) {
                if (!valid()) return
                utteranceSince = 0
                apply(turns.speechEnded(turn))
                status = if (turns.candidate != null) "听到了，可以继续补充…" else idleStatus()
                publish("endpoint", turn)
                commit?.let(main::removeCallbacks)
                if (turns.candidate?.id != turn) return
                commitGate.endpoint(SystemClock.elapsedRealtime())
                commit = Runnable {
                    if (!valid() || turns.candidate?.id != turn) return@Runnable
                    when (commitGate.decision(SystemClock.elapsedRealtime(), engine?.submissionGraceMs ?: VoiceCommitGate.AUTO_SEND_WAIT_MS)) {
                        VoiceCommitGate.Decision.WAIT -> commit?.let { main.postDelayed(it, 100) }
                        VoiceCommitGate.Decision.EXPIRE -> end("补充语音未能识别，已保留文字，请重新连接后继续")
                        VoiceCommitGate.Decision.COMMIT -> {
                            apply(turns.commit(turn))
                            if (status == "听到了，可以继续补充…") status = idleStatus()
                            publish(if (active) "committed" else "ended", turn)
                        }
                    }
                }.also { main.postDelayed(it, 650) }
            }
            override fun onPlaybackStarted(turn: Long) {
                if (valid()) {
                    status = if (engine?.supportsAcousticBargeIn != false) "正在回答，你可以直接插话" else "正在回答，返回键可停止"
                    publish("speaking", turn)
                }
            }
            override fun onPlaybackFinished(turn: Long) {
                if (!valid()) return
                turns.playbackFinished(turn)
                if (handoff && turns.running == null && turns.speaking == null) { handoff = false; end(APP_HANDOFF_NOTICE); return }
                status = idleStatus()
                publish("listening", turn)
            }
            override fun onMediaPlaybackStarted() {
                if (!valid()) return
                // Opening a video app may take focus before its tool result comes back.
                // Keep that accepted run until its result is saved; never listen to the movie.
                if (turns.running == null) end(DoubaoDialogEngine.MEDIA_HANDOFF_NOTICE)
            }
            override fun onError(message: String) { if (valid()) end(message) }
        }).also { it.start(VoiceSettingsRepository.loadDoubaoCredentials()) }
    }

    override fun result(turn: Long, answer: String, confirmed: Boolean, failure: String?) {
        if (!confirmed) {
            // Never retry a possibly accepted task. Keep the run identity until reconciliation.
            end("任务连接中断，执行状态待确认，请到对话记录查看；不会自动重发")
            return
        }
        observer?.invoke("runtime.terminal", turn, failure.orEmpty(), if (failure == null) "ok" else "failed")
        observer?.invoke("answer", turn, answer, status)
        apply(turns.runtimeFinished(turn, answer))
        if (active && turns.speaking == null) status = failure?.takeIf { turns.running == null } ?: idleStatus()
        publish("result", turn)
        // 打开了其他 App 但没有要念的回答：直接结束，不留在后台听节目。
        if (handoff && active && answer.isBlank() && turns.running == null && turns.speaking == null) { handoff = false; end(APP_HANDOFF_NOTICE) }
    }

    override fun runtimeEvent(turn: Long, event: io.github.fartown.movo.agent.runtime.AgentEvent) {
        when (event) {
            is io.github.fartown.movo.agent.runtime.AgentEvent.ToolStarted ->
                observer?.invoke("tool.started", turn, event.name, "")
            is io.github.fartown.movo.agent.runtime.AgentEvent.ToolFinished -> {
                if (event.success == true && event.name in FlavorModule.voiceHandoffTools) handoff = true
                observer?.invoke("tool.finished", turn, event.name, event.success.toString())
            }
            is io.github.fartown.movo.agent.runtime.AgentEvent.AssistantBlockDelta ->
                if (event.kind == io.github.fartown.movo.agent.runtime.AgentEvent.AssistantBlockKind.TEXT)
                    observer?.invoke("text.delta", turn, event.delta, "${event.round}:${event.index}")
            is io.github.fartown.movo.agent.runtime.AgentEvent.AssistantBlockEnd ->
                if (event.kind == io.github.fartown.movo.agent.runtime.AgentEvent.AssistantBlockKind.TEXT)
                    observer?.invoke("text.end", turn, event.replacementContent.orEmpty(), "${event.round}:${event.index}")
            else -> Unit
        }
    }

    /** 停止当前播报，不结束语音通道、不取消任务。 */
    override fun stopSpeaking() {
        if (!turns.active) return
        val actions = turns.stopSpeaking()
        if (actions.isEmpty()) return
        apply(actions)
        status = "已停止播报，你可以继续说"
        publish("listening")
    }

    override fun end(message: String) {
        status = message
        apply(turns.end())
        publish("ended")
    }

    private fun apply(actions: List<VoiceTurnCoordinator.Action>) {
        actions.forEach { action -> when (action) {
            is VoiceTurnCoordinator.Action.Submit -> {
                status = "正在处理，你可以继续说"
                observer?.invoke("request", action.turn.id, action.turn.text, status)
                publish("dispatch", action.turn.id)
                host.submit(action.turn, sessionId)
            }
            is VoiceTurnCoordinator.Action.Speak -> {
                status = "正在准备播报…"
                observer?.invoke("speech.request", action.turn.id, action.turn.text, "")
                engine?.speak(action.turn.id, action.turn.text)
            }
            VoiceTurnCoordinator.Action.PauseSpeech -> engine?.pause()
            VoiceTurnCoordinator.Action.DiscardSpeech -> engine?.discard()
            VoiceTurnCoordinator.Action.ResumeSpeech -> engine?.resume()
            VoiceTurnCoordinator.Action.CancelTask -> {
                status = "正在停止任务…"
                publish("cancel.request")
                host.cancelTask()
            }
            VoiceTurnCoordinator.Action.EndSession -> {
                idleEndsAt.value = 0L
                // Retain partial/pending text before close can synchronously notify onClosed.
                publish("ended")
                commit?.let(main::removeCallbacks)
                generation++
                val closing = engine
                if (closing == null) host.onClosed() else closing.close {
                    if (engine === closing) {
                        engine = null
                        host.onClosed()
                    }
                }
                if (status.isBlank() || status == "听到了，可以继续补充…") status = "语音对话已结束"
            }
            is VoiceTurnCoordinator.Action.Notice -> status = action.text
            is VoiceTurnCoordinator.Action.Local ->
                status = FlavorModule.onVoiceLocalCommand(context, action.command) ?: idleStatus()
        } }
    }

    private fun idleStatus(): String = when {
        turns.hearing -> "正在听你说…"
        turns.pending != null -> "听到了，等当前任务完成后接着处理"
        turns.running != null -> "正在处理，你可以继续说"
        turns.speaking != null -> "正在回答，你可以直接插话"
        else -> "我在听，可以继续说"
    }

    private fun tick(current: Long) {
        if (current != generation || !active) return
        val now = SystemClock.elapsedRealtime()
        val timeout = FlavorModule.voiceIdleTimeoutMs
        if (turns.trulyIdle) {
            if (idleSince == 0L) idleSince = now
            idleEndsAt.value = idleSince + timeout
            if (now - idleSince >= timeout) { idleEndsAt.value = 0L; end(IDLE_END_MESSAGE); return }
        } else { idleSince = 0; idleEndsAt.value = 0L }
        if (utteranceSince > 0 && now - utteranceSince > 60_000) {
            end("没有检测到说话结束，已保留文字，请在安静环境中重试")
            return
        }
        main.postDelayed({ tick(current) }, 1000)
    }

    private fun publish(event: String, turn: Long = turns.latestTurnId) {
        host.onState(event, active, status, turns.transcript)
        MemoryDiagnostics.record("voice", "conversation.$event", fields = mapOf(
            "session" to sessionId, "turn" to turn, "active" to active, "chars" to turns.transcript.length))
        observer?.invoke(event, turn, turns.transcript, status)
    }

    @Keep
    companion object {
        /** In-process, same-signature instrumentation only; no exported component or network hook. */
        @Keep @JvmStatic var observer: ((String, Long, String, String) -> Unit)? = null

        /** 空闲超时结束时的状态文案。 */
        const val IDLE_END_MESSAGE = "暂时没有听到说话，语音已结束，可再次唤醒"

        /** 打开了其他 App 后结束会话的提示。 */
        const val APP_HANDOFF_NOTICE = "已打开，语音已结束"

        /**
         * 当前会话空闲到期的时刻（elapsedRealtime，毫秒）；不在空闲时为 0。电视胶囊据此在最后 3 秒变暗。
         */
        val idleEndsAt = kotlinx.coroutines.flow.MutableStateFlow(0L)
    }
}
