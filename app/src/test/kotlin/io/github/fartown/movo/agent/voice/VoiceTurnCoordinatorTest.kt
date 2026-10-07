package io.github.fartown.movo.agent.voice

import io.github.fartown.movo.agent.voice.conversation.VoiceTurnCoordinator
import io.github.fartown.movo.agent.voice.conversation.VoiceTurnCoordinator.Action
import org.junit.Assert.*
import org.junit.Test

class VoiceTurnCoordinatorTest {
    private fun session() = VoiceTurnCoordinator().apply { start() }
    /** 任务在跑时说话：先交给任务；模拟运行时拒收，退回排队（旧的排队路径）。 */
    private fun VoiceTurnCoordinator.sayRefused(id: Long, text: String): List<Action> {
        val supplement = say(id, text).single() as Action.Supplement
        return supplementRejected(supplement.turn)
    }
    private fun VoiceTurnCoordinator.say(id: Long, text: String): List<Action> {
        speechStarted(id); partial(id, text); speechEnded(id)
        return commit(id)
    }

    @Test fun threeTurnsSubmitExactlyOnceAndReturnToIdleAfterPlayback() {
        val s = session()
        repeat(3) { index ->
            val id = index.toLong() + 1
            assertEquals(listOf(Action.Submit(VoiceTurnCoordinator.Turn(id, "问题$id"))), s.say(id, "问题$id"))
            assertTrue(s.commit(id).isEmpty())
            assertTrue(s.speechEnded(id).isEmpty())
            assertEquals(listOf(Action.Speak(VoiceTurnCoordinator.Turn(id, "回答$id"))), s.runtimeFinished(id, "回答$id"))
            assertFalse(s.trulyIdle)
            s.playbackFinished(id)
            assertTrue(s.trulyIdle)
        }
    }
    @Test fun stopSpeakingDiscardsPlaybackAndKeepsTheSessionListening() {
        val s = session()
        s.say(1, "念一段给我听")
        assertEquals(listOf(Action.Speak(VoiceTurnCoordinator.Turn(1, "好的，这是一段"))), s.runtimeFinished(1, "好的，这是一段"))
        assertEquals(listOf(Action.DiscardSpeech), s.stopSpeaking())
        assertTrue(s.active)
        assertTrue(s.trulyIdle)
        // 已经停过的播报不会再被停第二次，也不会凭空产生动作。
        assertTrue(s.stopSpeaking().isEmpty())
    }

    @Test fun stopSpeakingNeverTouchesARunningTask() {
        val s = session()
        s.say(1, "帮我跑个任务")
        assertNotNull(s.running)
        assertTrue(s.stopSpeaking().isEmpty())
        assertNotNull(s.running)
    }

    @Test fun resumedSpeechBeforeCommitMergesIntoOneTurnAndOldTimerDoesNothing() {
        val s = session()
        s.speechStarted(1); s.partial(1, "请告诉我那个"); s.speechEnded(1)
        s.speechStarted(2); assertTrue(s.commit(1).isEmpty())
        s.partial(2, "暗号，只读暗号"); s.speechEnded(2)
        val action = s.commit(2).single() as Action.Submit
        assertEquals("请告诉我那个，暗号，只读暗号", action.turn.text)
        assertEquals(2L, action.turn.id)
    }
    @Test fun repeatedEndedAndLatePartialCannotChangeCommittedText() {
        val s = session(); s.say(1, "打开计算器")
        s.partial(1, "错误尾包"); s.speechEnded(1)
        assertTrue(s.commit(1).isEmpty()); assertEquals("", s.transcript)
        assertEquals("打开计算器", s.running?.text)
    }
    @Test fun partialDoesNotSubmitAndOldCallbacksCannotAffectNewTurn() {
        val s = session(); s.speechStarted(1); s.partial(1, "甲")
        assertNull(s.running); s.speechEnded(1); s.speechStarted(2)
        s.partial(1, "旧"); s.speechEnded(1)
        assertTrue(s.hearing); assertEquals("甲", s.transcript)
    }
    @Test fun bargeInPausesThenDiscardsSpeechWithoutCancelingTask() {
        val s = session(); s.say(1, "问题"); s.runtimeFinished(1, "长答案")
        assertEquals(listOf(Action.PauseSpeech), s.speechStarted(2))
        assertEquals(listOf(Action.DiscardSpeech), s.partial(2, "换个问题"))
        s.playbackFinished(1)
        s.speechEnded(2)
        assertTrue(s.commit(2).single() is Action.Submit)
        assertTrue(s.runtimeFinished(1, "旧尾包").isEmpty())
    }
    @Test fun emptyNoiseResumesUnrevokedSpeech() {
        val s = session(); s.say(1, "问题"); s.runtimeFinished(1, "答案")
        s.speechStarted(2)
        assertEquals(listOf(Action.ResumeSpeech), s.speechEnded(2))
        assertTrue(s.commit(2).isEmpty())
    }
    @Test fun speechWhileBusyGoesToTheRunningTaskWhoseAnswerIsStillSpoken() {
        val s = session(); s.say(1, "打开哔哩哔哩搜索罗翔")
        assertEquals(listOf(Action.Supplement(VoiceTurnCoordinator.Turn(2, "算了，不用了"))), s.say(2, "算了，不用了"))
        assertEquals(1L, s.running?.id); assertNull(s.pending)
        val speak = s.runtimeFinished(1, "好的，不搜了").single() as Action.Speak
        assertEquals("好的，不搜了", speak.turn.text)
    }
    @Test fun ordinarySpeechWhileBusyQueuesAndDoesNotCancel() {
        val s = session(); s.say(1, "任务甲")
        assertTrue(s.sayRefused(2, "任务乙").all { it is Action.Notice })
        assertEquals(1L, s.running?.id); assertEquals(2L, s.pending?.id)
        assertEquals(listOf(Action.Submit(VoiceTurnCoordinator.Turn(2, "任务乙"))), s.runtimeFinished(1, "甲完成"))
    }
    @Test fun thirdUtteranceDoesNotOverwriteAcceptedPending() {
        val s = session(); s.say(1, "甲"); s.sayRefused(2, "乙"); s.sayRefused(3, "丙")
        assertEquals("乙", s.pending?.text); assertEquals("丙", s.transcript)
    }
    @Test fun finishDuringNewSpeechDoesNotSpeakStaleAnswer() {
        val s = session(); s.say(1, "甲"); s.speechStarted(2); s.partial(2, "乙")
        assertTrue(s.runtimeFinished(1, "甲答案").isEmpty()); assertNull(s.speaking)
        s.speechEnded(2); assertTrue(s.commit(2).single() is Action.Submit)
    }
    @Test fun endVoiceDoesNotCancelRunningTaskOrDispatchPendingLater() {
        val s = session(); s.say(1, "甲"); s.sayRefused(2, "乙")
        val actions = s.end()
        assertFalse(s.active); assertEquals("乙", s.transcript)
        assertEquals(1L, s.running?.id); assertNull(s.pending)
        assertFalse(actions.contains(Action.CancelTask))
        assertTrue(s.runtimeFinished(1, "甲完成").isEmpty())
        assertTrue(s.say(3, "结束后不应执行").isEmpty()); assertNull(s.running)
    }
    @Test fun cancelWaitsForRealTerminalAndDoesNotPretendToFinish() {
        val s = session(); s.say(1, "甲")
        assertTrue(s.say(2, "取消任务。").contains(Action.CancelTask))
        assertEquals(1L, s.running?.id)
        s.sayRefused(3, "乙"); assertEquals(3L, s.pending?.id)
        assertTrue(s.runtimeFinished(1, "已停止").single() is Action.Submit)
    }
    @Test fun endTaskAlsoEndsBackgroundMonitorsWhenNothingIsRunning() {
        var monitoring = true
        val s = VoiceTurnCoordinator(hasBackgroundTask = { monitoring }).apply { start() }
        // 没有在跑的一轮，但还有后台监听：「结束任务」照样交给宿主去结束（连监听一起，规范 8.12「结束」）。
        assertTrue(s.say(1, "结束任务。").contains(Action.CancelTask))
        monitoring = false
        val idle = s.say(2, "结束任务")
        assertFalse(idle.contains(Action.CancelTask))
        assertTrue(idle.any { it is Action.Notice })
    }
    @Test fun negationOrQuestionIsNotACommand() {
        listOf("不要取消", "取消按钮在哪里", "请告诉我结束对话是什么意思").forEach { text ->
            val s = session(); assertTrue(s.say(1, text).single() is Action.Submit)
        }
    }
    @Test fun controlsEndSessionWithoutSendingToModel() {
        val s = session()
        assertTrue(s.say(1, "结束对话。").contains(Action.EndSession))
        assertFalse(s.active); assertNull(s.running)
    }
    @Test fun feedbackAndProgressDoNotCreateSecondTask() {
        listOf("好的", "等一下", "做到哪了").forEach { text ->
            val s = session(); s.say(1, "任务"); val actions = s.say(2, text)
            assertNull(s.pending); assertTrue(actions.none { it is Action.Submit || it == Action.CancelTask })
        }
    }
    @Test fun pendingCanStartAtTerminalWhileNextSegmentIsBeingCaptured() {
        val s = session(); s.say(1,"甲"); s.sayRefused(2,"乙"); s.speechStarted(3); s.partial(3,"丙")
        assertTrue(s.runtimeFinished(1,"甲答案").single() is Action.Submit)
        s.speechEnded(3); s.supplementRejected((s.commit(3).single() as Action.Supplement).turn)
        assertEquals("乙", s.running?.text); assertEquals("丙", s.pending?.text)
    }
    @Test fun startRefusesUnreconciledRunningTask() {
        val s = session(); s.say(1,"甲"); s.end()
        assertThrows(IllegalStateException::class.java) { s.start() }
        s.runtimeFinished(1,"完成"); s.start(); assertTrue(s.active)
    }
    @Test fun endingPreservesBothPendingAndExtraDraftWithoutDispatchingEither() {
        val s = session(); s.say(1, "甲"); s.sayRefused(2, "乙"); s.sayRefused(3, "丙")
        s.say(4, "结束对话")
        assertFalse(s.active)
        assertEquals("乙\n丙", s.transcript)
        assertEquals("甲", s.running?.text)
        assertTrue(s.runtimeFinished(1,"甲完成").isEmpty())
    }

    @Test fun acknowledgementDoesNotSuppressTheRunningTasksFinalAnswer() {
        val s = session(); s.say(1, "长任务"); s.say(2, "好的")
        val action = s.runtimeFinished(1,"完成了").single() as Action.Speak
        assertEquals("完成了", action.turn.text)
        assertEquals(2L, action.turn.id)
    }
    @Test fun answerFinishingDuringSpeechWaitsUntilFeedbackIsResolved() {
        val s = session(); s.say(1, "长任务"); s.speechStarted(2); s.partial(2, "嗯")
        assertTrue(s.runtimeFinished(1,"完成了").isEmpty())
        s.speechEnded(2)
        assertEquals("完成了", (s.commit(2).single() as Action.Speak).turn.text)
    }
    @Test fun standaloneAcknowledgementDoesNotStartAnUnwantedModelTask() {
        val s = session(); assertTrue(s.say(1,"好的").isEmpty()); assertNull(s.running)
    }

    @Test fun deviceLocalCommandIsHandledLocallyInsteadOfStartingATask() {
        val s = VoiceTurnCoordinator(localCommands = setOf("看全文")).apply { start() }
        assertEquals(listOf(Action.Local("看全文")), s.say(1, "看全文。"))
        assertNull(s.running)
        // 没有声明为本地口令的设备（手机）照常交给 Agent。
        assertEquals(listOf(Action.Submit(VoiceTurnCoordinator.Turn(1, "看全文"))), session().say(1, "看全文"))
    }
}
