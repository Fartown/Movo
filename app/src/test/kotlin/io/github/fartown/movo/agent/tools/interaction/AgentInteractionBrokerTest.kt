package io.github.fartown.movo.agent.tools.interaction

import io.github.fartown.movo.agent.tools.core.ApprovalDecision
import io.github.fartown.movo.agent.tools.core.ApprovalReason
import io.github.fartown.movo.agent.tools.core.ApprovalRequest
import io.github.fartown.movo.agent.tools.core.QuestionOption
import io.github.fartown.movo.agent.tools.core.UserAnswer
import io.github.fartown.movo.agent.tools.core.UserQuestion
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 交互通道运行时核心单测（§6.1）：关联、一次性去重、超时、取消。 */
class AgentInteractionBrokerTest {

    private fun approvalReq() = ApprovalRequest(
        toolName = "setting_write", title = "改系统设置", detail = "亮度 → 80%",
        rememberScope = "系统设置", reason = ApprovalReason.EXTERNAL_EFFECT,
    )

    /** 后台线程等 emit 后按 requestId 投递，驱动阻塞中的 ask/approve。 */
    private fun deliverWhenEmitted(emitted: AtomicReference<InteractionPrompt?>, broker: AgentInteractionBroker, reply: (String) -> InteractionReply) =
        thread {
            val start = System.currentTimeMillis()
            while (emitted.get() == null && System.currentTimeMillis() - start < 3000) Thread.sleep(5)
            emitted.get()?.let { broker.deliver(it.requestId, reply(it.requestId)) }
        }

    @Test
    fun approve_delivered_returnsApprovedWithRemember() {
        val broker = AgentInteractionBroker()
        val emitted = AtomicReference<InteractionPrompt?>(null)
        val ix = BrokeredUserInteraction(broker, emit = { emitted.set(it) })
        val t = deliverWhenEmitted(emitted, broker) { InteractionReply.Approval(approved = true, remember = true) }
        val decision = ix.approve(approvalReq(), 3000)
        t.join()
        assertTrue(decision is ApprovalDecision.Approved)
        assertTrue((decision as ApprovalDecision.Approved).remember)
        // 请求内容正确映射给界面
        assertEquals(InteractionKind.APPROVAL, emitted.get()!!.kind)
        assertEquals("系统设置", emitted.get()!!.rememberLabel)
    }

    @Test
    fun approve_declinedReply_returnsDeclined() {
        val broker = AgentInteractionBroker()
        val emitted = AtomicReference<InteractionPrompt?>(null)
        val ix = BrokeredUserInteraction(broker, emit = { emitted.set(it) })
        val t = deliverWhenEmitted(emitted, broker) { InteractionReply.Approval(approved = false, remember = false) }
        val decision = ix.approve(approvalReq(), 3000)
        t.join()
        assertEquals(ApprovalDecision.Declined, decision)
    }

    @Test
    fun ask_delivered_returnsAnswer() {
        val broker = AgentInteractionBroker()
        val emitted = AtomicReference<InteractionPrompt?>(null)
        val ix = BrokeredUserInteraction(broker, emit = { emitted.set(it) })
        val t = deliverWhenEmitted(emitted, broker) { InteractionReply.Answer("王伟（同事）", 1) }
        val answer = ix.ask(UserQuestion("哪个王伟？", listOf(QuestionOption("A"), QuestionOption("B"))), 3000)
        t.join()
        assertTrue(answer is UserAnswer.Answered)
        assertEquals(1, (answer as UserAnswer.Answered).optionIndex)
        assertEquals("王伟（同事）", answer.text)
    }

    @Test
    fun approve_timeout_returnsTimedOut() {
        val broker = AgentInteractionBroker()
        val ix = BrokeredUserInteraction(broker, emit = { /* 界面不回传 */ })
        val decision = ix.approve(approvalReq(), 250)
        assertEquals(ApprovalDecision.TimedOut, decision)
    }

    @Test
    fun approve_cancelled_returnsDeclined() {
        val broker = AgentInteractionBroker()
        val cancel = AtomicBoolean(false)
        val ix = BrokeredUserInteraction(broker, emit = { cancel.set(true) }, cancelled = { cancel.get() })
        val decision = ix.approve(approvalReq(), 3000)
        assertEquals(ApprovalDecision.Declined, decision)
    }

    @Test
    fun deliver_isOnceOnly_andUnknownIdFails() {
        val broker = AgentInteractionBroker()
        assertFalse("未登记的 id 投递失败", broker.deliver("nope", InteractionReply.Cancelled))
        broker.register("r1")
        assertTrue("首次投递成功", broker.deliver("r1", InteractionReply.Approval(true, false)))
        assertFalse("第二次投递被去重拒绝", broker.deliver("r1", InteractionReply.Approval(true, true)))
    }
}
