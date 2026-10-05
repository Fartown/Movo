package io.github.fartown.movo.agent.monitor

/**
 * 监听通知交给谁、什么时候交（纯逻辑，AgentAppState 收集事实后调用）。
 * - 并入：只并入 App 自己订阅着、运行时已开始执行的同一对话的普通任务；语音轮、压缩、改写不并入；
 *   上一批还没被模型读到时先不再交（新到的留在 App 队列里，受积压上限约束）。
 * - 事件轮：不打断用户——用户在编辑消息、有排队等发的消息、在切模型、在语音里时都不开；运行时也得空闲。
 */
internal object MonitorWakePolicy {
    data class RunFacts(
        val sameConversation: Boolean,
        val started: Boolean,
        val streaming: Boolean,
        val compacting: Boolean,
        val roleplay: Boolean,
        val voiceTurn: Boolean,
        val replyRewrite: Boolean,
        val batchInFlight: Boolean,
    )

    data class IdleFacts(
        val appRunActive: Boolean,
        val anyConversationBusy: Boolean,
        val userEditing: Boolean,
        val userMessageQueued: Boolean,
        val modelChanging: Boolean,
        val voiceActive: Boolean,
        val voiceClosing: Boolean,
        val runtimeIdle: Boolean,
        val roleplay: Boolean,
    )

    fun canInject(run: RunFacts): Boolean =
        run.sameConversation && run.started && run.streaming && !run.compacting && !run.roleplay &&
            !run.voiceTurn && !run.replyRewrite && !run.batchInFlight

    fun canStartEventTurn(idle: IdleFacts): Boolean =
        !idle.appRunActive && !idle.anyConversationBusy && !idle.userEditing && !idle.userMessageQueued &&
            !idle.modelChanging && !idle.voiceActive && !idle.voiceClosing && idle.runtimeIdle && !idle.roleplay

    /** 阻塞解除时本身会触发一次投递（本 App 的运行结束、编辑结束、排队消息发出、模型切换完成、语音结束），不用定时重试。 */
    fun blockedObservably(idle: IdleFacts): Boolean =
        idle.appRunActive || idle.anyConversationBusy || idle.userEditing || idle.userMessageQueued ||
            idle.modelChanging || idle.voiceActive

    /** 看不到何时解除的阻塞（运行时被别的入口占着、语音还在收尾）下的重试间隔：从 [minMs] 起逐次加倍，最长 [maxMs]。 */
    fun nextRetryDelay(previousMs: Long, minMs: Long, maxMs: Long): Long = (previousMs * 2).coerceIn(minMs, maxMs)
}

/**
 * App 侧还没交给模型的监听通知（主线程使用，纯逻辑，便于单测）。
 *
 * - 排队：按对话、按到达顺序；每个监听只留最近 [perTaskLimit] 条事件，更早的计入「已省略 M 条」，
 *   随这个监听下一条交付的事件告诉模型。结束通知不省略。
 * - 在途：并入运行中一轮的通知在模型真正读到（运行时发回「已消费」事件）之前都记在这一轮名下；
 *   这一轮结束时没读到的放回队首，留给下一个事件轮。
 * - 事件轮：起点那一批也记下，事件轮没真正开始就被拒 / 被顶掉时整批放回队首。
 */
internal class MonitorDeliveryQueue(private val perTaskLimit: Int = DEFAULT_PER_TASK_LIMIT) {
    private val pending = LinkedHashMap<String, ArrayDeque<MonitorNotice>>()
    private val omitted = HashMap<String, Int>()
    private val dropped = HashSet<String>()
    private val inFlight = LinkedHashMap<String, InFlight>()
    private val eventTurns = HashMap<String, Launched>()

    private class InFlight(val conversationId: String) {
        /** 已交给运行时、还没确认读到的通知（按交付顺序）。 */
        val unconfirmed = mutableListOf<MonitorNotice>()
        /** 已确认读到的批次：每批对应模型历史里的一条 user 条目（运行时在边界上合并后的那条）。 */
        val groups = mutableListOf<MutableList<MonitorNotice>>()
    }

    private class Launched(val conversationId: String, val notices: List<MonitorNotice>)

    /** 结算结果：[requeue] 已放回队首；[removeRowKeys] 界面上要撤掉的行（模型其实没读到）。 */
    data class Settlement(val requeue: List<MonitorNotice>, val removeRowKeys: Set<String>)

    fun enqueue(notice: MonitorNotice) {
        if (notice.taskId in dropped) return
        val queue = pending.getOrPut(notice.conversationId) { ArrayDeque() }
        queue.addLast(notice)
        if (notice is MonitorNotice.Event) trim(queue, notice.taskId)
    }

    /** 某个监听被用户停掉（不唤醒）：丢掉它还在排队的事件，之后放回的也不再要。 */
    fun dropTask(taskId: String) {
        dropped += taskId
        omitted.remove(taskId)
        pending.values.forEach { queue -> queue.removeAll { it.taskId == taskId } }
        pending.entries.removeAll { it.value.isEmpty() }
    }

    /** 对话没了：排队、在途、事件轮记录一起清掉。 */
    fun dropConversation(conversationId: String) {
        pending.remove(conversationId)?.forEach { omitted.remove(it.taskId) }
        inFlight.entries.removeAll { it.value.conversationId == conversationId }
        eventTurns.entries.removeAll { it.value.conversationId == conversationId }
    }

    /** 只保留这些对话的排队、在途与事件轮记录（导入备份后用）。 */
    fun retainConversations(conversationIds: Set<String>) {
        (pending.keys + inFlight.values.map { it.conversationId } + eventTurns.values.map { it.conversationId })
            .filterNot { it in conversationIds }
            .distinct()
            .forEach(::dropConversation)
    }

    fun conversations(): List<String> = pending.filterValues { it.isNotEmpty() }.keys.toList()

    fun hasPending(conversationId: String): Boolean = pending[conversationId]?.isNotEmpty() == true

    fun hasAnyPending(): Boolean = pending.values.any { it.isNotEmpty() }

    fun pendingCount(conversationId: String): Int = pending[conversationId]?.size ?: 0

    /** 取出这个对话排队的全部通知（一并交给模型）；每个监听第一条事件带上已省略的条数。 */
    fun take(conversationId: String): List<MonitorNotice> {
        val queue = pending.remove(conversationId) ?: return emptyList()
        val seen = HashSet<String>()
        return queue.map { notice ->
            if (notice is MonitorNotice.Event && seen.add(notice.taskId)) {
                val count = omitted.remove(notice.taskId) ?: 0
                if (count > 0) notice.copy(omittedBefore = notice.omittedBefore + count) else notice
            } else {
                notice
            }
        }
    }

    /** 放回队首（保持原来的先后），再按每个监听的上限裁掉最旧的。 */
    fun requeueFront(conversationId: String, notices: List<MonitorNotice>) {
        val kept = notices.filterNot { it.taskId in dropped }
        if (kept.isEmpty()) return
        val queue = pending.getOrPut(conversationId) { ArrayDeque() }
        kept.asReversed().forEach { queue.addFirst(it) }
        kept.filterIsInstance<MonitorNotice.Event>().map { it.taskId }.distinct().forEach { trim(queue, it) }
    }

    // ---------------- 运行中并入 ----------------

    fun markInFlight(runId: String, conversationId: String, notices: List<MonitorNotice>) {
        inFlight.getOrPut(runId) { InFlight(conversationId) }.unconfirmed += notices
    }

    fun hasInFlight(runId: String): Boolean = inFlight[runId]?.unconfirmed?.isNotEmpty() == true

    /**
     * 运行时发回「已消费」：[anchor] 为 true 表示模型历史里新起一条 user 条目（新的一批）。
     * 返回对应的通知；不在在途里（重放、或来自事件轮起点）时返回 null。
     */
    fun confirm(runId: String, rowKey: String, anchor: Boolean): MonitorNotice? {
        val flight = inFlight[runId] ?: return null
        val index = flight.unconfirmed.indexOfFirst { rowKey(it) == rowKey }
        if (index < 0) return null
        val notice = flight.unconfirmed.removeAt(index)
        if (anchor || flight.groups.isEmpty()) flight.groups += mutableListOf(notice) else flight.groups.last() += notice
        return notice
    }

    /**
     * 这一轮结束：没确认的放回队首；[consumedGroups] 为结果 transcript 里实际写进去的事件条目数，
     * 少于已确认的批数时（停止恰好落在发出「已消费」与写入上下文之间），多出来的批次也放回，并撤掉它们的行。
     * transcript 不可用（结果要等恢复）时传 null，只放回没确认的。
     */
    fun settle(runId: String, consumedGroups: Int?): Settlement {
        val flight = inFlight.remove(runId) ?: return Settlement(emptyList(), emptySet())
        val extra = if (consumedGroups != null && consumedGroups < flight.groups.size) {
            flight.groups.drop(consumedGroups.coerceAtLeast(0)).flatten()
        } else {
            emptyList()
        }
        val requeue = extra + flight.unconfirmed
        requeueFront(flight.conversationId, requeue)
        return Settlement(requeue, extra.mapTo(mutableSetOf(), ::rowKey))
    }

    // ---------------- 事件轮 ----------------

    fun markEventTurn(runId: String, conversationId: String, notices: List<MonitorNotice>) {
        eventTurns[runId] = Launched(conversationId, notices)
    }

    fun isEventTurn(runId: String): Boolean = runId in eventTurns

    /** 事件轮正常结束（不论成败）：起点那一批已经在模型历史里了。 */
    fun finishEventTurn(runId: String) {
        eventTurns.remove(runId)
    }

    /** 事件轮没真正开始：整批放回队首，返回放回的通知。 */
    fun rollbackEventTurn(runId: String): List<MonitorNotice> {
        val launched = eventTurns.remove(runId) ?: return emptyList()
        requeueFront(launched.conversationId, launched.notices)
        return launched.notices
    }

    private fun trim(queue: ArrayDeque<MonitorNotice>, taskId: String) {
        val events = queue.count { it is MonitorNotice.Event && it.taskId == taskId }
        var excess = events - perTaskLimit
        if (excess <= 0) return
        omitted[taskId] = (omitted[taskId] ?: 0) + excess
        val iterator = queue.iterator()
        while (excess > 0 && iterator.hasNext()) {
            val notice = iterator.next()
            if (notice is MonitorNotice.Event && notice.taskId == taskId) {
                // 被裁掉的那条自己若带着更早的省略数，也一并算进去。
                if (notice.omittedBefore > 0) omitted[taskId] = (omitted[taskId] ?: 0) + notice.omittedBefore
                iterator.remove()
                excess--
            }
        }
    }

    companion object {
        const val DEFAULT_PER_TASK_LIMIT = 20

        /** 一条通知在对话里那一行的键（与运行时「已消费」事件对应）。 */
        fun rowKey(notice: MonitorNotice): String = when (notice) {
            is MonitorNotice.Event -> rowKey(notice.taskId, "event", notice.seq)
            is MonitorNotice.Ended -> rowKey(notice.taskId, "ended", notice.eventCount)
        }

        fun rowKey(taskId: String, kind: String, seq: Int): String = "monitor-$taskId-$kind-$seq"
    }
}
