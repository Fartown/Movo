package io.github.fartown.movo.agent.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** App 侧投递调度：积压上限、在途确认与放回、事件轮撤回。 */
class MonitorDeliveryQueueTest {

    private fun event(seq: Int, task: String = "m1", conversation: String = "c1") =
        MonitorNotice.Event(task, conversation, "名字-$task", seq.toLong(), seq, "tick $seq", suppressedBefore = 0)

    private fun ended(task: String = "m1", conversation: String = "c1") = MonitorNotice.Ended(
        task, conversation, "名字-$task", 99L, MonitorEndReason.EXIT, exitCode = 0, tail = null,
        eventCount = 3, timeoutMs = 60_000L, suppressed = 0,
    )

    @Test
    fun eachMonitorKeepsOnlyTheLatestEventsAndTellsHowManyWereOmitted() {
        val queue = MonitorDeliveryQueue(perTaskLimit = 3)
        (1..5).forEach { queue.enqueue(event(it)) }
        queue.enqueue(event(1, task = "m2"))
        queue.enqueue(ended())

        val batch = queue.take("c1")

        assertEquals(listOf(3, 4, 5), batch.filterIsInstance<MonitorNotice.Event>().filter { it.taskId == "m1" }.map { it.seq })
        // 省略数只挂在这个监听交付的第一条上，别的监听不受影响；结束通知不省略。
        val m1 = batch.filterIsInstance<MonitorNotice.Event>().filter { it.taskId == "m1" }
        assertEquals(listOf(2, 0, 0), m1.map { it.omittedBefore })
        assertEquals(0, batch.filterIsInstance<MonitorNotice.Event>().single { it.taskId == "m2" }.omittedBefore)
        assertTrue(batch.last() is MonitorNotice.Ended)
        assertFalse(queue.hasPending("c1"))
        assertTrue(MonitorEventFormatter.format(batch).contains("更早的 2 条已省略"))
    }

    @Test
    fun consumedGroupsAreConfirmedAndTheRestGoesBackToTheFrontInOrder() {
        val queue = MonitorDeliveryQueue()
        queue.enqueue(event(1))
        queue.enqueue(event(2))
        val batch = queue.take("c1")
        queue.markInFlight("run-1", "c1", batch)
        queue.enqueue(event(3))
        assertTrue(queue.hasInFlight("run-1"))

        // 运行时报告读到了第 1 条（锚点）；第 2 条没读到这一轮就结束了。
        assertEquals(1, (queue.confirm("run-1", MonitorDeliveryQueue.rowKey(batch[0]), anchor = true) as MonitorNotice.Event).seq)
        assertNull(queue.confirm("run-1", "monitor-m9-event-1", anchor = true))
        val settlement = queue.settle("run-1", consumedGroups = 1)

        assertEquals(listOf(2), settlement.requeue.map { (it as MonitorNotice.Event).seq })
        assertTrue(settlement.removeRowKeys.isEmpty())
        // 放回队首：先于这一轮里新到的第 3 条。
        assertEquals(listOf(2, 3), queue.take("c1").map { (it as MonitorNotice.Event).seq })
        assertFalse(queue.hasInFlight("run-1"))
    }

    @Test
    fun groupsTheTranscriptDoesNotContainAreUndoneAndRequeued() {
        val queue = MonitorDeliveryQueue()
        val first = listOf(event(1), event(2))
        val second = listOf(event(3))
        queue.markInFlight("run-1", "c1", first + second)
        queue.confirm("run-1", MonitorDeliveryQueue.rowKey(first[0]), anchor = true)
        queue.confirm("run-1", MonitorDeliveryQueue.rowKey(first[1]), anchor = false)
        queue.confirm("run-1", MonitorDeliveryQueue.rowKey(second[0]), anchor = true)

        // 停止落在「已消费」与写入上下文之间：结果 transcript 里只有第一批。
        val settlement = queue.settle("run-1", consumedGroups = 1)

        assertEquals(setOf("monitor-m1-event-3"), settlement.removeRowKeys)
        assertEquals(listOf(3), queue.take("c1").map { (it as MonitorNotice.Event).seq })
    }

    @Test
    fun withoutAUsableTranscriptOnlyUnconfirmedNoticesAreRequeued() {
        val queue = MonitorDeliveryQueue()
        queue.markInFlight("run-1", "c1", listOf(event(1), event(2)))
        queue.confirm("run-1", "monitor-m1-event-1", anchor = true)
        val settlement = queue.settle("run-1", consumedGroups = null)
        assertTrue(settlement.removeRowKeys.isEmpty())
        assertEquals(listOf(2), queue.take("c1").map { (it as MonitorNotice.Event).seq })
    }

    @Test
    fun userStoppedMonitorsAreNotDeliveredAgain() {
        val queue = MonitorDeliveryQueue()
        queue.enqueue(event(1))
        queue.markInFlight("run-1", "c1", listOf(event(2)))
        queue.dropTask("m1")
        queue.settle("run-1", consumedGroups = 0)
        queue.enqueue(event(3))
        assertFalse(queue.hasAnyPending())
    }

    @Test
    fun eventTurnThatNeverStartedIsPutBackWhole() {
        val queue = MonitorDeliveryQueue()
        queue.enqueue(event(1))
        queue.enqueue(event(2))
        val batch = queue.take("c1")
        queue.markEventTurn("run-e", "c1", batch)
        queue.enqueue(event(3))
        assertTrue(queue.isEventTurn("run-e"))

        val requeued = queue.rollbackEventTurn("run-e")

        assertEquals(batch, requeued)
        assertFalse(queue.isEventTurn("run-e"))
        assertEquals(listOf(1, 2, 3), queue.take("c1").map { (it as MonitorNotice.Event).seq })

        queue.markEventTurn("run-f", "c1", listOf(event(4)))
        queue.finishEventTurn("run-f")
        assertTrue(queue.rollbackEventTurn("run-f").isEmpty())
    }

    @Test
    fun eventsJoinOnlyTheSubscribedStartedTaskRunAndOneBatchAtATime() {
        val run = MonitorWakePolicy.RunFacts(
            sameConversation = true, started = true, streaming = true, compacting = false, roleplay = false,
            voiceTurn = false, replyRewrite = false, batchInFlight = false,
        )
        assertTrue(MonitorWakePolicy.canInject(run))
        // 运行时还没开始执行（可能是别的入口的续跑）、语音轮、上一批还没读到、别的对话：都不并入。
        assertFalse(MonitorWakePolicy.canInject(run.copy(started = false)))
        assertFalse(MonitorWakePolicy.canInject(run.copy(voiceTurn = true)))
        assertFalse(MonitorWakePolicy.canInject(run.copy(batchInFlight = true)))
        assertFalse(MonitorWakePolicy.canInject(run.copy(sameConversation = false)))
        assertFalse(MonitorWakePolicy.canInject(run.copy(compacting = true)))
        assertFalse(MonitorWakePolicy.canInject(run.copy(replyRewrite = true)))
    }

    @Test
    fun eventTurnsNeverInterruptTheUser() {
        val idle = MonitorWakePolicy.IdleFacts(
            appRunActive = false, anyConversationBusy = false, userEditing = false, userMessageQueued = false,
            modelChanging = false, voiceActive = false, voiceClosing = false, runtimeIdle = true, roleplay = false,
        )
        assertTrue(MonitorWakePolicy.canStartEventTurn(idle))
        listOf(
            idle.copy(userEditing = true),
            idle.copy(userMessageQueued = true),
            idle.copy(appRunActive = true),
            idle.copy(voiceActive = true),
            idle.copy(voiceClosing = true),
            idle.copy(runtimeIdle = false),
            idle.copy(modelChanging = true),
            idle.copy(roleplay = true),
        ).forEach { assertFalse(it.toString(), MonitorWakePolicy.canStartEventTurn(it)) }
        // 编辑结束、排队消息发出、语音结束会自己触发投递，不轮询；运行时被别的入口占着、语音收尾时才退避重试。
        assertTrue(MonitorWakePolicy.blockedObservably(idle.copy(userEditing = true)))
        assertTrue(MonitorWakePolicy.blockedObservably(idle.copy(voiceActive = true)))
        assertFalse(MonitorWakePolicy.blockedObservably(idle.copy(runtimeIdle = false)))
        assertFalse(MonitorWakePolicy.blockedObservably(idle.copy(voiceClosing = true)))
        assertEquals(listOf(2_000L, 4_000L, 30_000L, 30_000L), listOf(1_000L, 2_000L, 16_000L, 30_000L).map {
            MonitorWakePolicy.nextRetryDelay(it, 1_000L, 30_000L)
        })
    }

    @Test
    fun removedConversationsLeaveNothingBehind() {
        val queue = MonitorDeliveryQueue()
        queue.enqueue(event(1, conversation = "c1"))
        queue.enqueue(event(1, task = "m2", conversation = "c2"))
        queue.markInFlight("run-2", "c2", listOf(event(2, task = "m2", conversation = "c2")))
        queue.markEventTurn("run-3", "c3", listOf(event(1, task = "m3", conversation = "c3")))

        queue.retainConversations(setOf("c1"))

        assertEquals(listOf("c1"), queue.conversations())
        assertFalse(queue.hasInFlight("run-2"))
        assertFalse(queue.isEventTurn("run-3"))
        assertEquals(1, queue.pendingCount("c1"))
    }
}
