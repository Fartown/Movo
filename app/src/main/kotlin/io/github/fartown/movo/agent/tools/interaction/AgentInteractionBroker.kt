package io.github.fartown.movo.agent.tools.interaction

import io.github.fartown.movo.agent.tools.core.ApprovalDecision
import io.github.fartown.movo.agent.tools.core.ApprovalRequest
import io.github.fartown.movo.agent.tools.core.UserAnswer
import io.github.fartown.movo.agent.tools.core.UserInteraction
import io.github.fartown.movo.agent.tools.core.UserQuestion
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 交互通道运行时核心（实施方案 §6.1；运行时部分不等 Figma 设计稿）。
 *
 * 负责「发出提问/审批请求 → 按 requestId 等待界面回传结果」的关联，与具体事件/IPC 类型解耦：
 * - 运行线程调用 [BrokeredUserInteraction.ask]/[approve]，经 [emit] 把 [InteractionPrompt] 送出（上层再转成
 *   `AgentEvent.InteractionRequested` 投给界面，属薄适配层，需真机联调）。
 * - 界面回传时上层调用 [AgentInteractionBroker.deliver]（上层从 wire 消息 13 解析，按 requestId 投递）。
 * - **一次性**：同一 requestId 只接受第一次回传（重连回放去重由此保证）。
 * - **超时/取消**：等待按 [cancelled] 轮询，取消立即返回，超时返回 null。
 */
internal sealed interface InteractionReply {
    data class Answer(val text: String, val optionIndex: Int?) : InteractionReply
    data class Approval(val approved: Boolean, val remember: Boolean) : InteractionReply
    data object Cancelled : InteractionReply
}

internal enum class InteractionKind { QUESTION, APPROVAL }

/** 送给界面的提示内容（与设计稿无关，稿只决定怎么渲染）。 */
internal data class InteractionPrompt(
    val requestId: String,
    val kind: InteractionKind,
    val title: String,
    val detail: String,
    val options: List<String> = emptyList(),
    val allowFreeText: Boolean = true,
    val rememberLabel: String? = null,
    val reason: String? = null,
)

internal class AgentInteractionBroker {
    private val pending = ConcurrentHashMap<String, ArrayBlockingQueue<InteractionReply>>()

    /** 界面回传结果；只接受第一次（容量 1 的队列保证一次性），未在等待或已回传返回 false。 */
    fun deliver(requestId: String, reply: InteractionReply): Boolean =
        pending[requestId]?.offer(reply) ?: false

    /** 发起等待：先登记再由调用方 emit，避免回传早于登记而丢失。返回 null 表示超时。 */
    fun await(requestId: String, timeoutMs: Long, cancelled: () -> Boolean, pollMs: Long = 150): InteractionReply? {
        val queue = pending.computeIfAbsent(requestId) { ArrayBlockingQueue(1) }
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (cancelled()) return InteractionReply.Cancelled
                val slice = minOf(pollMs, deadline - System.currentTimeMillis()).coerceAtLeast(1)
                val reply = queue.poll(slice, TimeUnit.MILLISECONDS)
                if (reply != null) return reply
            }
            return null
        } finally {
            pending.remove(requestId)
        }
    }

    fun register(requestId: String) {
        pending.computeIfAbsent(requestId) { ArrayBlockingQueue(1) }
    }
}

/**
 * 进程内按 runId 定位 broker 的注册表。执行器（Runtime 进程）启动时登记、结束时注销；
 * Service 的交互回传消息处理器据此把界面结果投递给正在等待的运行线程。执行器与 Service 同进程，故用全局对象。
 */
internal object AgentInteractionRegistry {
    private val brokers = ConcurrentHashMap<String, AgentInteractionBroker>()

    fun register(runId: String, broker: AgentInteractionBroker) {
        if (runId.isNotBlank()) brokers[runId] = broker
    }

    fun unregister(runId: String) {
        if (runId.isNotBlank()) brokers.remove(runId)
    }

    /** 把界面回传投递给指定 run 的 broker；该 run 不在交互等待或已回传时返回 false。 */
    fun deliver(runId: String, requestId: String, reply: InteractionReply): Boolean =
        brokers[runId]?.deliver(requestId, reply) ?: false
}

/**
 * 把交互通道接成工具子系统的 [UserInteraction]。[emit] 把提示送给界面；[cancelled] 是本次运行的取消信号。
 * 真正可用还需上层把 [emit] 接到事件、把 wire 消息 13 接到 [AgentInteractionBroker.deliver]（薄适配层，真机联调）。
 */
internal class BrokeredUserInteraction(
    private val broker: AgentInteractionBroker,
    private val emit: (InteractionPrompt) -> Unit,
    private val cancelled: () -> Boolean = { false },
    private val onResolved: (String) -> Unit = {},
    private val idPrefix: String = "ix",
    /** 现在有没有人能作答（屏幕亮着）。没有时审批、提问立即返回「无法确认」，不干等 120 秒。 */
    private val availableNow: () -> Boolean = { true },
) : UserInteraction {
    private val seq = AtomicLong(0)
    override val available: Boolean get() = runCatching(availableNow).getOrDefault(true)

    private fun nextId(): String = "$idPrefix-${System.currentTimeMillis()}-${seq.incrementAndGet()}"

    private fun awaitReply(prompt: InteractionPrompt, timeoutMs: Long): InteractionReply? {
        broker.register(prompt.requestId)   // 先登记，防止回传早于等待
        runCatching { emit(prompt) }
        return try {
            broker.await(prompt.requestId, timeoutMs, cancelled)
        } finally {
            // 无论作答 / 取消 / 超时，都通知界面收起对应的卡（避免卡片残留）。
            runCatching { onResolved(prompt.requestId) }
        }
    }

    override fun ask(question: UserQuestion, timeoutMs: Long): UserAnswer {
        val prompt = InteractionPrompt(
            requestId = nextId(),
            kind = InteractionKind.QUESTION,
            title = question.question,
            detail = "",
            // 补充说明并进选项文字（定稿 16-03：「王伟 · 手机 138****2048」），否则用户看不出候选的区别。
            options = question.options.map { option ->
                option.detail?.takeIf { it.isNotBlank() }?.let { "${option.label} · $it" } ?: option.label
            },
            allowFreeText = question.allowFreeText,
        )
        return when (val reply = awaitReply(prompt, timeoutMs)) {
            is InteractionReply.Answer -> UserAnswer.Answered(reply.text, reply.optionIndex)
            InteractionReply.Cancelled -> UserAnswer.Declined
            is InteractionReply.Approval -> UserAnswer.Declined   // 类型不符当拒绝，安全侧
            null -> UserAnswer.TimedOut
        }
    }

    override fun approve(request: ApprovalRequest, timeoutMs: Long): ApprovalDecision {
        val prompt = InteractionPrompt(
            requestId = nextId(),
            kind = InteractionKind.APPROVAL,
            title = request.title,
            detail = request.detail,
            rememberLabel = request.rememberScope,
            reason = request.reason.name,
        )
        return when (val reply = awaitReply(prompt, timeoutMs)) {
            is InteractionReply.Approval -> if (reply.approved) ApprovalDecision.Approved(reply.remember) else ApprovalDecision.Declined
            InteractionReply.Cancelled -> ApprovalDecision.Declined
            is InteractionReply.Answer -> ApprovalDecision.Declined   // 类型不符当拒绝，安全侧
            null -> ApprovalDecision.TimedOut
        }
    }
}
