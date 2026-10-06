package io.github.fartown.movo.agent.tools

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.ApprovalDecision
import io.github.fartown.movo.agent.tools.core.ApprovalCategory
import io.github.fartown.movo.agent.tools.core.ApprovalPolicy
import io.github.fartown.movo.agent.tools.core.ApprovalRequest
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.PermissionMode
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.Sensitivity
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
 * 权限模式（docs/research/tool-redesign/Movo 权限模式方案.md）：默认 YOLO 一律不问；
 * 手动审批固定问付款、密码、删东西、发消息和提交，其余类别与应用按用户加的规则问；
 * 卡片只有允许 / 拒绝；没人能作答时立即返回；五个开关管对应的工具能不能用。
 */
@RunWith(RobolectricTestRunner::class)
class ApprovalPolicyTest {

    private data class In(val text: String) : ToolInput
    private data class Out(val ok: Boolean) : ToolOutput

    /** 可配置的假工具：声明这一步属于哪类动作、在哪个应用里。 */
    private class Fake(
        override val name: String,
        private val category: ApprovalCategory? = null,
        private val appPackage: String? = null,
    ) : ToolContract<In, Out> {
        var executed = 0
        override val domain = ToolDomain.DEVICE
        override val summary = "测试工具"
        override fun schema(env: ToolEnvironment) = JSONObject().put("type", "object")
        override fun parse(args: ToolArgs, env: ToolEnvironment) = In(args.raw.optString("text"))
        override fun resolve(input: In, env: ToolEnvironment) = CallResolution(
            risk = Risk.LOCAL, sensitivity = Sensitivity.NORMAL, resources = emptySet(),
            category = category, appPackage = appPackage,
        )
        override fun execute(input: In, resolution: CallResolution, ctx: ToolContext): Verdict<Out> {
            executed++
            return Verdict.Read(Out(true))
        }
        override fun renderForModel(output: Out) = ModelContent.Json(JSONObject().put("ok", output.ok))
    }

    private val plain = Fake("fake_alarm")
    private val pay = Fake("fake_pay", ApprovalCategory.PAYMENT)
    private val delete = Fake("fake_delete", ApprovalCategory.DELETE)
    private val send = Fake("fake_send", ApprovalCategory.SEND)
    private val password = Fake("fake_password", ApprovalCategory.PASSWORD)
    private val system = Fake("fake_setting", ApprovalCategory.SYSTEM)
    private val outbound = Fake("fake_upload", ApprovalCategory.OUTBOUND)
    private val inBank = Fake("fake_tap_bank", appPackage = "com.example.bank")
    private val inChat = Fake("fake_tap_chat", appPackage = "com.example.chat")
    private val all = listOf(plain, pay, delete, send, password, system, outbound, inBank, inChat)

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

    private fun pipeline(interaction: UserInteraction, policy: ApprovalPolicy): ToolPipeline {
        val provider = object : ToolProvider {
            override val tools: List<AgentTool> = all.map { ContractTool(it) }
        }
        val env = ToolEnvironment(approvalPolicy = policy)
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
    fun yolo_isTheDefault_andNeverAsks() {
        assertEquals(PermissionMode.YOLO, ToolEnvironment().approvalPolicy.mode)
        val recorder = Recorder(ApprovalDecision.Declined)
        val p = pipeline(recorder, ApprovalPolicy.YOLO)
        for (tool in all) assertEquals(tool.name, "ok", p.execute(call(tool.name)).status)
        assertTrue("YOLO 下付款、删除、发送也不弹卡", recorder.requests.isEmpty())
        assertTrue(all.all { it.executed == 1 })
    }

    @Test
    fun manual_alwaysAsksHighSensitive_only() {
        val recorder = Recorder(ApprovalDecision.Declined)
        val p = pipeline(recorder, ApprovalPolicy.MANUAL_BUILT_IN)
        for (tool in listOf(pay, delete, send, password)) {
            assertEquals(tool.name, "USER_DECLINED", p.execute(call(tool.name)).errorCode)
            assertEquals(0, tool.executed)
        }
        for (tool in listOf(plain, system, outbound, inBank)) {
            assertEquals("没加规则的类别和应用不问：${tool.name}", "ok", p.execute(call(tool.name)).status)
        }
        assertEquals(
            listOf(ApprovalCategory.PAYMENT, ApprovalCategory.DELETE, ApprovalCategory.SEND, ApprovalCategory.PASSWORD),
            recorder.requests.map { it.category },
        )
    }

    @Test
    fun manual_asksForCategoriesAndAppsTheUserAdded() {
        val recorder = Recorder(ApprovalDecision.Declined)
        val policy = ApprovalPolicy(
            mode = PermissionMode.MANUAL,
            categories = setOf(ApprovalCategory.SYSTEM),
            apps = setOf("com.example.bank"),
        )
        val p = pipeline(recorder, policy)
        assertEquals("USER_DECLINED", p.execute(call("fake_setting")).errorCode)
        assertEquals("USER_DECLINED", p.execute(call("fake_tap_bank")).errorCode)
        assertEquals("ok", p.execute(call("fake_upload")).status)
        assertEquals("ok", p.execute(call("fake_tap_chat")).status)
        assertEquals(2, recorder.requests.size)
        assertNull("应用规则命中时类别为空", recorder.requests[1].category)
    }

    @Test
    fun card_isReadable_andExplainsWhy() {
        val recorder = Recorder(ApprovalDecision.Declined)
        val policy = ApprovalPolicy(mode = PermissionMode.MANUAL, categories = setOf(ApprovalCategory.OUTBOUND))
        val p = pipeline(recorder, policy)
        p.execute(call("fake_pay", """{"text":"hello"}"""))
        p.execute(call("fake_upload", """{"text":"hello"}"""))
        val (payCard, uploadCard) = recorder.requests
        assertFalse("确认卡不贴原始 JSON", payCard.detail.contains("{"))
        assertEquals("手动审批时，付款、转账都会先问你。", payCard.reason)
        assertEquals("你设了「把内容发到外部」先问你。", uploadCard.reason)
        assertFalse("原因单独一行，不拼进正文", uploadCard.detail.contains("先问你"))
    }

    @Test
    fun approvedOnce_isAskedAgainNextTime() {
        val recorder = Recorder(ApprovalDecision.Approved)
        val p = pipeline(recorder, ApprovalPolicy.MANUAL_BUILT_IN)
        assertEquals("ok", p.execute(call("fake_pay")).status)
        assertEquals("ok", p.execute(call("fake_pay")).status)
        assertEquals("卡片没有「记住」，每次都问", 2, recorder.requests.size)
        assertEquals(2, pay.executed)
    }

    @Test
    fun noOneToAnswer_returnsImmediatelyWithoutWaiting() {
        val recorder = Recorder(ApprovalDecision.Approved, available = false)
        val p = pipeline(recorder, ApprovalPolicy.MANUAL_BUILT_IN)
        val r = p.execute(call("fake_pay"))
        assertEquals(ToolErrorCode.UNSUPPORTED.name, r.errorCode)
        assertTrue(recorder.requests.isEmpty())
        assertEquals(0, pay.executed)
    }

    @Test
    fun policy_ignoresHighSensitiveInUserRules_andYoloIgnoresRules() {
        val yoloWithRules = ApprovalPolicy(
            mode = PermissionMode.YOLO,
            categories = setOf(ApprovalCategory.SYSTEM),
            apps = setOf("com.example.bank"),
        )
        assertFalse(yoloWithRules.shouldAsk(ApprovalCategory.SYSTEM, null))
        assertFalse(yoloWithRules.shouldAsk(null, "com.example.bank"))
        assertFalse(yoloWithRules.shouldAsk(ApprovalCategory.PAYMENT, null))
        assertFalse(ApprovalPolicy.MANUAL_BUILT_IN.shouldAsk(null, null))
        assertEquals(ApprovalCategory.entries.filterNot { it.highSensitive }, ApprovalCategory.optional)
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
