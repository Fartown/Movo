package io.github.fartown.movo.agent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class AgentRunController {
    private val resources = CopyOnWriteArraySet<CancellableResource>()

    @Volatile
    private var cancelled = false

    val isCancelled: Boolean
        get() = cancelled

    private val lock = ReentrantLock()
    private val pauseCondition = lock.newCondition()
    private val steeringMessages = ArrayDeque<SteeringItem>()
    private var acceptingSteering = true
    @Volatile
    private var paused = false

    fun cancel() {
        lock.withLock {
            cancelled = true
            acceptingSteering = false
            // 没消费的监听事件随队列丢弃：App 只认运行时发回的「已消费」，这一轮结束时会把它们放回自己的队首。
            steeringMessages.clear()
            paused = false
            pauseCondition.signalAll()
        }
        resources.forEach { resource ->
            runCatching { resource.cancel() }
        }
    }

    /**
     * 将补充指令排入下一个 turn。steering 不取消当前模型请求或工具批次。
     */
    fun steer(text: String): Boolean {
        val prompt = text.trim()
        if (prompt.isBlank()) return false
        lock.withLock {
            if (cancelled || !acceptingSteering) return false
            steeringMessages.addLast(SteeringItem.User(prompt))
        }
        return true
    }

    /**
     * 后台监听的事件排入下一个边界。[blocks] 是事件的标签段（不含开头结尾的说明），[events] 是模型读到时
     * 要发给界面的「已消费」事件。不取消当前模型请求或工具批次；本 run 已在收尾（不再接收）时返回 false。
     */
    fun injectEvent(blocks: String, events: List<AgentEvent> = emptyList()): Boolean {
        if (blocks.isBlank()) return false
        lock.withLock {
            if (cancelled || !acceptingSteering) return false
            steeringMessages.addLast(SteeringItem.Event(blocks, events))
        }
        return true
    }

    /**
     * 步骤边界取下一条：用户补充优先，逐条消费，避免后来的补充指令越过前一条的模型回合；
     * 没有补充时（且 [allowEvents]）把排着的监听事件一次取完、合成一条（同一边界上积压的事件一起交给模型）。
     * 补充先于事件：补充在界面上是发出时就显示的，事件行在模型读到时才插入，这样两者在界面与模型历史里的先后一致。
     */
    fun pollSteeringMessage(allowEvents: Boolean = true): SteeringItem? =
        lock.withLock { pollLocked(allowEvents) }

    /**
     * 自然结束前原子地消费最后一条用户补充；没有补充时永久关闭本 run 的接收入口。
     * 这样 Service 不会在 loop 已返回后仍把补充指令误报为已接收。
     * 监听事件不让本轮续跑：留在队列里随本轮结束丢弃，由 App 放回队首、交给下一个事件轮。
     */
    fun pollSteeringOrSeal(): SteeringItem? =
        lock.withLock {
            pollLocked(allowEvents = false)?.let { return it }
            acceptingSteering = false
            null
        }

    private fun pollLocked(allowEvents: Boolean): SteeringItem? {
        val supplement = steeringMessages.firstOrNull { it is SteeringItem.User }
        if (supplement != null) {
            steeringMessages.remove(supplement)
            return supplement
        }
        if (!allowEvents) return null
        val events = steeringMessages.filterIsInstance<SteeringItem.Event>()
        if (events.isEmpty()) return null
        steeringMessages.removeAll(events.toSet())
        return SteeringItem.Event.merge(events)
    }

    val hasPendingSteering: Boolean
        get() = lock.withLock { steeringMessages.isNotEmpty() }

    /**
     * 暂停执行：后续 [throwIfCancelled] 调用会阻塞挂起，直到 [resume] 或 [cancel]。
     * 在工作线程的检查点调用，不会阻塞调用方线程。
     */
    fun pause() {
        lock.withLock { paused = true }
    }

    /**
     * 恢复执行：唤醒被 [throwIfCancelled] 阻塞的工作线程，从挂起点继续。
     */
    fun resume() {
        lock.withLock {
            paused = false
            pauseCondition.signalAll()
        }
    }

    /**
     * 检查点：若已取消则抛异常；若已暂停则阻塞挂起直到恢复或取消。
     * 在 agent 循环的每轮/每步调用，实现暂停可恢复、取消即终止。
     */
    fun throwIfCancelled() {
        lock.withLock {
            while (paused && !cancelled) {
                try {
                    pauseCondition.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    cancelled = true
                }
            }
        }
        if (cancelled) throw AgentRunCancelledException()
    }

    fun awaitRetryDelay(delayMs: Long) {
        throwIfCancelled()
        val cancelledLatch = CountDownLatch(1)
        val binding = register { cancelledLatch.countDown() }
        try {
            cancelledLatch.await(delayMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentRunCancelledException()
        } finally {
            binding.close()
        }
        throwIfCancelled()
    }

    fun register(cancel: () -> Unit): ResourceBinding {
        val resource = CancellableResource(cancel)
        resources.add(resource)
        if (cancelled) resource.cancel()
        return ResourceBinding { resources.remove(resource) }
    }

    inner class ResourceBinding internal constructor(private val closeBlock: () -> Unit) {
        fun close() {
            closeBlock()
        }
    }

    private class CancellableResource(private val cancelBlock: () -> Unit) {
        private val cancelled = AtomicBoolean(false)

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) cancelBlock()
        }
    }
}

/** steering 队列里的一项：用户补充，或后台监听事件。 */
internal sealed interface SteeringItem {
    val text: String
    data class User(override val text: String) : SteeringItem

    /**
     * 后台监听事件：[text] 为标签段，[events] 为模型读到时发给界面的事件。
     * 合并后的一条在模型历史里只占一条 user 条目，所以只有第一条事件是界面的历史锚点。
     */
    data class Event(override val text: String, val events: List<AgentEvent> = emptyList()) : SteeringItem {
        companion object {
            fun merge(items: List<Event>): Event {
                val events = items.flatMap { it.events }
                var anchored = false
                return Event(
                    text = items.joinToString("") { item -> item.text.let { if (it.endsWith('\n')) it else it + '\n' } },
                    events = events.map { event ->
                        if (event is AgentEvent.MonitorEventReceived) {
                            event.copy(anchor = !anchored).also { anchored = true }
                        } else {
                            event
                        }
                    },
                )
            }
        }
    }
}

internal class AgentRunCancelledException : RuntimeException("Agent run cancelled")
