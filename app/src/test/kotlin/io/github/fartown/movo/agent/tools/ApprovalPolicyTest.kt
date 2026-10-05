package io.github.fartown.movo.agent.tools

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.ApprovalDecision
import io.github.fartown.movo.agent.tools.core.ApprovalReason
import io.github.fartown.movo.agent.tools.core.ApprovalRequest
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.TaintKind
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolAvailability
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolContract
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.ToolPipeline
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.agent.tools.core.ToolSwitchGate
import io.github.fartown.movo.agent.tools.core.ToolSwitches
import io.github.fartown.movo.agent.tools.core.UserAnswer
import io.github.fartown.movo.agent.tools.core.UserInteraction
import io.github.fartown.movo.agent.tools.core.UserQuestion
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.core.AndroidAgentLogger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 审批规则（实施方案 5.1、5.3、5.4，2026-10-05 用户反馈后的收窄）：
 * 污点分两类，同时成立且动作会外发才确认；本地动作不受污点影响；「本次任务内」勾选后同类不再问；
 * 没人能作答时立即返回；五个开关管对应的工具。
 */
@RunWith(RobolectricTestRunner::class)
class ApprovalPolicyTest {

    private data class In(val text: String) : ToolInput
    private data class Out(val ok: Boolean) : ToolOutput

    /** 可配置的假工具：声明污点、风险、外发。 */
    private class Fake(
        override val name: String,
        private val taints: Set<TaintKind> = emptySet(),
        private val risk: Risk = Risk.LOCAL,
        private val exfiltrates: Boolean = false,
    ) : ToolContract<In, Out> {
        var executed = 0
        override val domain = ToolDomain.DEVICE
        override val summary = "测试工具"
        override fun schema(env: ToolEnvironment) = JSONObject().put("type", "object")
        override fun parse(args: ToolArgs, env: ToolEnvironment) = In(args.raw.optString("text"))
        override fun resolve(input: In, env: ToolEnvironment) = CallResolution(
            risk = risk, sensitivity = Sensitivity.NORMAL, resources = emptySet(), exfiltrates = exfiltrates,
        )
        override fun execute(input: In, resolution: CallResolution, ctx: ToolContext): Verdict<Out> {
            executed++
            return Verdict.Read(Out(true))
        }
        override fun renderForModel(output: Out) = ModelContent.Json(JSONObject().put("ok", output.ok))
        override fun taintKinds(input: In) = taints
    }

    private val untrustedReader = Fake("fake_web_read", taints = setOf(TaintKind.UNTRUSTED))
    private val personalReader = Fake("fake_sms_read", taints = setOf(TaintKind.PERSONAL))
    private val localAction = Fake("fake_alarm")
    private val outbound = Fake("fake_upload", exfiltrates = true)

    private class Recorder(
        private val decision: ApprovalDecision,
        override val available: Boolean = true,
    ) : UserInteraction {
        val requests = mutableListOf<ApprovalRequest>()
        override fun ask(question: UserQuestion, timeoutMs: Long) = UserAnswer.Declined
        override fun approve(request: ApprovalRequest, timeoutMs: Long): ApprovalDecision {
            requests += request
            return decision
        }
    }

    private fun pipeline(interaction: UserInteraction, env: ToolEnvironment = ToolEnvironment()): ToolPipeline {
        val provider = object : ToolProvider {
            override val tools: List<AgentTool> = listOf(untrustedReader, personalReader, localAction, outbound).map { ContractTool(it) }
        }
        return ToolPipeline(
            registry = ToolRegistry(listOf(provider)),
            environment = { env },
            appContext = ApplicationProvider.getApplicationContext(),
            logger = AndroidAgentLogger,
            runId = "run1",
            cancelled = { false },
            interaction = interaction,
        ).also { it.catalog() }
    }

    private fun call(name: String, args: String = "{}") = AgentModelClient.ToolCall("c-$name", name, args)

    @Test
    fun readingOnlyUntrustedContent_doesNotGateOutbound() {
        val recorder = Recorder(ApprovalDecision.Declined)
        val p = pipeline(recorder)
        p.execute(call("fake_web_read"))
        val r = p.execute(call("fake_upload"))
        assertEquals("ok", r.status)
        assertTrue(recorder.requests.isEmpty())
    }

    @Test
    fun bothTaintKinds_gateOutbound_withReadableCard() {
        val recorder = Recorder(ApprovalDecision.Declined)
        val p = pipeline(recorder)
        p.execute(call("fake_web_read"))
        p.execute(call("fake_sms_read"))
        val r = p.execute(call("fake_upload", """{"text":"hello"}"""))
        assertEquals("USER_DECLINED", r.errorCode)
        val request = recorder.requests.single()
        assertEquals(ApprovalReason.TAINTED, request.reason)
        assertFalse("确认卡不贴原始 JSON", request.detail.contains("{"))
        assertEquals(0, outbound.executed)
    }

    @Test
    fun bothTaintKinds_doNotGateLocalActions() {
        val recorder = Recorder(ApprovalDecision.Declined)
        val p = pipeline(recorder)
        p.execute(call("fake_web_read"))
        p.execute(call("fake_sms_read"))
        assertEquals("ok", p.execute(call("fake_alarm")).status)
        assertTrue("设闹钟、调音量这类本地动作不受污点影响", recorder.requests.isEmpty())
    }

    @Test
    fun taskScope_rememberedForTheRestOfTheRun() {
        val recorder = Recorder(ApprovalDecision.Approved(remember = true))
        val p = pipeline(recorder)
        p.execute(call("fake_web_read"))
        p.execute(call("fake_sms_read"))
        p.execute(call("fake_upload"))
        p.execute(call("fake_upload"))
        assertEquals(1, recorder.requests.size)
        assertEquals(ToolPipeline.TASK_SCOPE_LABEL, recorder.requests.single().rememberScope)
        assertEquals(2, outbound.executed)
    }

    @Test
    fun noOneToAnswer_returnsImmediatelyWithoutWaiting() {
        val recorder = Recorder(ApprovalDecision.Approved(remember = false), available = false)
        val p = pipeline(recorder)
        p.execute(call("fake_web_read"))
        p.execute(call("fake_sms_read"))
        val r = p.execute(call("fake_upload"))
        assertEquals(ToolErrorCode.UNSUPPORTED.name, r.errorCode)
        assertTrue(recorder.requests.isEmpty())
    }

    @Test
    fun switches_gateTheirTools() {
        val off = ToolSwitches(browser = false, deviceDirect = false, terminal = false, sensitiveRead = false, sensitiveAction = false)
        for (name in listOf("browser_open", "clock_create", "volume_set", "terminal_run", "file_write",
            "personal_search", "sms_code_read", "setting_read", "setting_write", "device_toggle", "app_control")) {
            val gate = ToolSwitchGate.check(name, off)
            assertNotNull("$name 应受开关控制", gate)
            assertEquals(ToolErrorCode.DISABLED, (gate as ToolAvailability.Unavailable).code)
        }
        for (name in listOf("ui_tap", "app_open", "memory_write", "ask_user", "monitor_start")) {
            assertNull("$name 不归开关管", ToolSwitchGate.check(name, off))
        }
        assertNull(ToolSwitchGate.check("personal_search", ToolSwitches()))
    }
}
