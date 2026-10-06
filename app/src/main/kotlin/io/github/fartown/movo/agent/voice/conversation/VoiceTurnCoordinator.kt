package io.github.fartown.movo.agent.voice.conversation

/** Single-writer conversation policy. ASR segments, Agent execution and playback are independent. */
internal class VoiceTurnCoordinator(
    /** 由设备本地处理的口令（例如电视的「看全文」）；命中时不交给 Agent。 */
    private val localCommands: Set<String> = emptySet(),
    /** 这个对话还有后台监听在跑：没有在跑的一轮时「结束任务」也要结束它们（规范 8.12「结束」）。 */
    private val hasBackgroundTask: () -> Boolean = { false },
) {
    data class Turn(val id: Long, val text: String)
    sealed interface Action {
        data class Submit(val turn: Turn) : Action
        data class Speak(val turn: Turn) : Action
        data object PauseSpeech : Action
        data object DiscardSpeech : Action
        data object ResumeSpeech : Action
        data object CancelTask : Action
        data object EndSession : Action
        data class Notice(val text: String) : Action
        data class Local(val command: String) : Action
    }

    var active = false; private set
    var hearing = false; private set
    var latestTurnId = 0L; private set
    var transcript = ""; private set
    var candidate: Turn? = null; private set
    var running: Turn? = null; private set
    var pending: Turn? = null; private set
    var speaking: Turn? = null; private set
    /** 已登记“这一轮回答念完就结束”（end_call）：不再接收新的话，回答念完、没有回答或失败时结束。 */
    var closing = false; private set
    private var prefix = ""
    private var heldDraft = ""
    private var endedTurn = -1L
    private var speechDiscarded = false
    private var responseOwner: Long? = null
    private var deferredAnswer: Turn? = null

    val trulyIdle: Boolean get() = active && !hearing && candidate == null &&
        running == null && pending == null && speaking == null && deferredAnswer == null

    fun start() {
        check(running == null) { "A running task must be reconciled before a new voice session" }
        active = true
        latestTurnId = 0
        hearing = false
        transcript = ""
        candidate = null
        pending = null
        speaking = null
        prefix = ""
        heldDraft = ""
        endedTurn = -1
        speechDiscarded = false
        responseOwner = null
        deferredAnswer = null
        closing = false
    }

    fun speechStarted(id: Long): List<Action> {
        if (!active || closing || id <= latestTurnId) return emptyList()
        // A new segment during the end-of-turn grace period is still the same user turn.
        prefix = candidate?.text.orEmpty()
        candidate = null
        latestTurnId = id
        transcript = prefix
        hearing = true
        speechDiscarded = false
        return if (speaking != null) listOf(Action.PauseSpeech) else emptyList()
    }

    fun partial(id: Long, text: String): List<Action> {
        if (!active || closing || id != latestTurnId || (!hearing && candidate == null) || text.isBlank()) return emptyList()
        transcript = listOf(prefix, text.trim()).filter(String::isNotBlank).joinToString("，")
        candidate?.let { candidate = it.copy(text = transcript) }
        if (speaking != null && !speechDiscarded) {
            speaking = null
            speechDiscarded = true
            return listOf(Action.DiscardSpeech)
        }
        return emptyList()
    }

    fun speechEnded(id: Long): List<Action> {
        if (!active || closing || id != latestTurnId || endedTurn == id) return emptyList()
        endedTurn = id
        hearing = false
        if (transcript.isBlank()) {
            return if (speaking != null && !speechDiscarded) listOf(Action.ResumeSpeech) else resumeDeferred()
        }
        candidate = Turn(id, transcript.trim())
        return emptyList()
    }

    /** Timer carries the segment identity; stale callbacks cannot submit a new utterance. */
    fun commit(id: Long): List<Action> {
        val turn = candidate?.takeIf { active && !closing && !hearing && it.id == id } ?: return emptyList()
        candidate = null
        transcript = ""
        prefix = ""
        val command = turn.text.trim().lowercase().trimEnd('。', '！', '!', '？', '?', '.', '，', ',')
        return when (command) {
            "结束对话", "退出语音", "退出语音对话", "结束语音对话", "停止对话", "再见" -> end()
            "别念了", "别读了", "停止播报", "不用念了" -> {
                speaking = null
                responseOwner = null
                deferredAnswer = null
                listOf(Action.DiscardSpeech, Action.Notice("已停止播报，你可以继续说"))
            }
            "取消任务", "取消这个任务", "停止任务", "停止当前任务", "不要做了", "结束任务", "结束这个任务" -> {
                speaking = null
                responseOwner = null
                deferredAnswer = null
                pending = null
                listOf(Action.DiscardSpeech) + if (running != null || hasBackgroundTask()) listOf(Action.CancelTask)
                else listOf(Action.Notice("当前没有正在执行的任务"))
            }
            "等一下", "稍等", "等等", "停", "停一下" -> {
                responseOwner = null
                deferredAnswer = null
                listOf(Action.Notice(if (running != null) "我在听，任务仍在执行；要取消请说取消任务" else "我在听，你可以继续说"))
            }
            "做到哪了", "任务进度", "进度怎么样" -> if (running != null)
                listOf(Action.Notice("任务仍在执行，完成后会告诉你")) else if (deferredAnswer != null) resumeDeferred() else submit(turn)
            "嗯", "好", "好的", "嗯嗯" -> resumeDeferred()
            in localCommands -> listOf(Action.Local(command))
            else -> submit(turn)
        }
    }

    private fun submit(turn: Turn): List<Action> {
        speaking = null
        deferredAnswer = null
        if (running != null) {
            responseOwner = null
            if (pending != null) {
                // Keep the accepted pending request; leave the extra utterance visible, never overwrite it.
                heldDraft = listOf(heldDraft, turn.text).filter(String::isNotBlank).joinToString("\n")
                transcript = turn.text
                return listOf(Action.Notice("已经记下一句待处理的话，请等当前任务结束后再补充"))
            }
            pending = turn
            return listOf(Action.Notice("听到了，当前任务完成后接着处理"))
        }
        running = turn
        responseOwner = turn.id
        return listOf(Action.Submit(turn))
    }

    fun runtimeFinished(id: Long, answer: String): List<Action> {
        val done = running?.takeIf { it.id == id } ?: return emptyList()
        running = null
        if (!active) return emptyList()
        if (closing) {
            // 念完这一轮的回答（告别语）就结束；没有回答（失败、取消）就直接结束。待发的话不再发出，结束时留作草稿。
            if (answer.isBlank()) return end()
            deferredAnswer = null
            speaking = done.copy(id = latestTurnId, text = answer)
            return listOf(Action.Speak(speaking!!))
        }
        val next = pending
        pending = null
        if (next != null) {
            return submit(next)
        }
        if (responseOwner != id || answer.isBlank()) return emptyList()
        deferredAnswer = done.copy(text = answer)
        return resumeDeferred()
    }

    private fun resumeDeferred(): List<Action> {
        val answer = deferredAnswer ?: return emptyList()
        if (!active || hearing || candidate != null || responseOwner != answer.id) return emptyList()
        deferredAnswer = null
        // Acknowledgements may create a new vendor ASR round without starting a new Agent task.
        speaking = answer.copy(id = latestTurnId)
        return listOf(Action.Speak(speaking!!))
    }

    /**
     * 登记“这一轮回答念完就结束”（end_call 工具）：立即不再接收新的话；正在执行的这一轮结束后念出回答，念完结束。
     * 没有在执行、也没有在念时直接结束。
     */
    fun endAfterReply(): List<Action> {
        if (!active) return emptyList()
        closing = true
        hearing = false
        candidate = null
        if (running == null && speaking == null) return end()
        return emptyList()
    }

    /** 界面上的"停止播报"：只停这次朗读，会话与任务都继续。与语音命令"别念了"同一条路径。 */
    fun stopSpeaking(): List<Action> {
        // 告别语被打断：直接结束。
        if (active && closing && speaking != null) return end()
        if (!active || (speaking == null && deferredAnswer == null)) return emptyList()
        speaking = null
        responseOwner = null
        deferredAnswer = null
        return listOf(Action.DiscardSpeech)
    }

    /** 朗读结束；登记了 [endAfterReply] 时返回结束会话的动作。 */
    fun playbackFinished(id: Long): List<Action> {
        if (speaking?.id != id) return emptyList()
        speaking = null
        return if (active && closing && running == null) end() else emptyList()
    }

    fun end(): List<Action> {
        val unfinished = candidate?.text ?: if (hearing || heldDraft.isBlank()) transcript else ""
        active = false
        hearing = false
        responseOwner = null
        deferredAnswer = null
        transcript = listOf(pending?.text.orEmpty(), unfinished, heldDraft)
            .filter(String::isNotBlank).joinToString("\n")
        heldDraft = ""
        candidate = null
        pending = null
        speaking = null
        closing = false
        return listOf(Action.DiscardSpeech, Action.EndSession)
    }
}
