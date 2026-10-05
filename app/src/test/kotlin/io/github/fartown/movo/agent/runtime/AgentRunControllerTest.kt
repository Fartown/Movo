package io.github.fartown.movo.agent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunControllerTest {
    @Test
    fun cancellationWakesLongRetryWait() {
        val controller = AgentRunController()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = thread {
            started.countDown()
            try {
                controller.awaitRetryDelay(60_000L)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            controller.cancel()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(failure.get() is AgentRunCancelledException)
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
    }

    @Test
    fun steeringIsQueuedOneAtATimeWithoutCancellingResources() {
        val controller = AgentRunController()
        val cancellations = AtomicInteger(0)
        controller.register { cancellations.incrementAndGet() }

        assertTrue(controller.steer("  first  "))
        assertFalse(controller.steer("   "))
        assertTrue(controller.steer("second"))

        assertEquals(0, cancellations.get())
        assertEquals("first", controller.pollSteeringMessage()?.text)
        assertEquals("second", controller.pollSteeringMessage()?.text)
        assertNull(controller.pollSteeringMessage())
    }

    @Test
    fun supplementsAreConsumedBeforeMonitorEventsEvenIfTheyArrivedLater() {
        val controller = AgentRunController()
        assertTrue(controller.injectEvent("<monitor-event>1</monitor-event>\n", listOf(monitorEvent(1))))
        assertTrue(controller.steer("补充"))
        // 补充在界面上发出时就显示，事件行在读到时才插：补充先消费，两边先后才一致。
        assertEquals(SteeringItem.User("补充"), controller.pollSteeringMessage())
        val event = controller.pollSteeringMessage() as SteeringItem.Event
        assertEquals(listOf(1), event.events.map { (it as AgentEvent.MonitorEventReceived).seq })
        assertNull(controller.pollSteeringMessage())
    }

    @Test
    fun queuedMonitorEventsAreMergedIntoOneItemWithOneHistoryAnchor() {
        val controller = AgentRunController()
        assertTrue(controller.injectEvent("<a/>", listOf(monitorEvent(1, anchor = true))))
        assertTrue(controller.injectEvent("<b/>\n", listOf(monitorEvent(2, anchor = true), monitorEvent(3))))

        val merged = controller.pollSteeringMessage() as SteeringItem.Event

        assertEquals("<a/>\n<b/>\n", merged.text)
        val events = merged.events.map { it as AgentEvent.MonitorEventReceived }
        assertEquals(listOf(1, 2, 3), events.map { it.seq })
        // 合并后在模型历史里只占一条 user 条目：只有第一条是界面的历史锚点。
        assertEquals(listOf(true, false, false), events.map { it.anchor })
        assertNull(controller.pollSteeringMessage())
    }

    @Test
    fun naturalEndContinuesOnlyForSupplementsAndSealsWithEventsLeftBehind() {
        val controller = AgentRunController()
        assertTrue(controller.injectEvent("<a/>", listOf(monitorEvent(1))))
        // 只剩监听事件：本轮不续跑，直接封口；事件随本轮丢弃，由 App 放回队首交给下一个事件轮。
        assertNull(controller.pollSteeringOrSeal())
        assertFalse(controller.injectEvent("<late/>", listOf(monitorEvent(2))))
        assertFalse(controller.steer("too late"))

        val withSupplement = AgentRunController()
        assertTrue(withSupplement.injectEvent("<a/>", listOf(monitorEvent(1))))
        assertTrue(withSupplement.steer("补充"))
        assertEquals(SteeringItem.User("补充"), withSupplement.pollSteeringOrSeal())
    }

    @Test
    fun eventsWaitWhileTheRunHasUsedItsInjections() {
        val controller = AgentRunController()
        assertTrue(controller.injectEvent("<a/>", listOf(monitorEvent(1))))
        assertNull(controller.pollSteeringMessage(allowEvents = false))
        assertTrue(controller.hasPendingSteering)
        assertTrue(controller.pollSteeringMessage(allowEvents = true) is SteeringItem.Event)
    }

    @Test
    fun cancelDropsQueuedEventsWithoutReportingThemConsumed() {
        val controller = AgentRunController()
        assertTrue(controller.injectEvent("<a/>", listOf(monitorEvent(1))))
        controller.cancel()
        assertFalse(controller.hasPendingSteering)
        assertFalse(controller.injectEvent("<b/>", listOf(monitorEvent(2))))
    }

    private fun monitorEvent(seq: Int, anchor: Boolean = false) = AgentEvent.MonitorEventReceived(
        taskId = "m1", name = "喝水提醒", kind = "event", seq = seq, atMillis = seq.toLong(), text = "tick $seq", anchor = anchor,
    )

    @Test
    fun finalPollAtomicallySealsSteering() {
        val controller = AgentRunController()

        assertNull(controller.pollSteeringOrSeal())
        assertFalse(controller.steer("too late"))
    }

    @Test
    fun cancelClearsSteeringAndCancelsEachResourceOnce() {
        val controller = AgentRunController()
        val cancellations = AtomicInteger(0)
        controller.register { cancellations.incrementAndGet() }
        controller.steer("pending")

        controller.cancel()
        controller.cancel()

        assertEquals(1, cancellations.get())
        assertFalse(controller.hasPendingSteering)
        assertFalse(controller.steer("late"))
    }

    @Test
    fun closedBindingIsNotCancelledLater() {
        val controller = AgentRunController()
        val cancellations = AtomicInteger(0)
        val binding = controller.register { cancellations.incrementAndGet() }

        binding.close()
        controller.cancel()

        assertEquals(0, cancellations.get())
    }

    @Test
    fun resourceRegisteredAfterCancellationIsCancelledExactlyOnce() {
        val controller = AgentRunController()
        val cancellations = AtomicInteger(0)
        controller.cancel()

        val binding = controller.register { cancellations.incrementAndGet() }
        controller.cancel()
        binding.close()

        assertEquals(1, cancellations.get())
    }

    @Test
    fun pauseBlocksUntilResume() {
        val controller = AgentRunController()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        controller.pause()
        val worker = thread(name = "controller-pause-test") {
            entered.countDown()
            runCatching(controller::throwIfCancelled).exceptionOrNull()?.let(failure::set)
            finished.countDown()
        }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            controller.resume()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertNull(failure.get())
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
        assertFalse(worker.isAlive)
    }

    @Test
    fun steeringDoesNotResumeAPausedRun() {
        val controller = AgentRunController()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        controller.pause()
        val worker = thread(name = "controller-paused-steering-test", isDaemon = true) {
            entered.countDown()
            runCatching(controller::throwIfCancelled)
            finished.countDown()
        }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(controller.steer("补充条件"))
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            assertEquals("补充条件", controller.pollSteeringMessage()?.text)
            controller.resume()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
    }

    @Test
    fun cancelWhilePausedWakesWorkerWithCancellation() {
        val controller = AgentRunController()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        controller.pause()
        val worker = thread(name = "controller-cancel-test") {
            entered.countDown()
            runCatching(controller::throwIfCancelled).exceptionOrNull()?.let(failure::set)
            finished.countDown()
        }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            controller.cancel()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(failure.get() is AgentRunCancelledException)
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
        assertFalse(worker.isAlive)
    }
}
