package io.github.fartown.movo.agent.tools

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.ApprovalCategory
import io.github.fartown.movo.agent.tools.core.ApprovalDecision
import io.github.fartown.movo.agent.tools.core.ApprovalPolicy
import io.github.fartown.movo.agent.tools.core.ApprovalRequest
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.Evidence
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolContract
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.ToolPipeline
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.agent.tools.core.UserAnswer
import io.github.fartown.movo.agent.tools.core.UserInteraction
import io.github.fartown.movo.agent.tools.core.UserQuestion
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.device.DeviceReadBackend
import io.github.fartown.movo.agent.tools.device.DeviceReadTool
import io.github.fartown.movo.agent.tools.device.DeviceSection
import io.github.fartown.movo.core.AndroidAgentLogger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 纵向链路第一验证：device_read 真工具 + 一个 external 假工具，通过 ToolPipeline 跑通。 */
@RunWith(RobolectricTestRunner::class)
class ContractPlumbingTest {

    private val fakeBackend = object : DeviceReadBackend {
        override fun read(section: DeviceSection, env: ToolEnvironment): JSONObject? = when (section) {
            DeviceSection.BATTERY -> JSONObject().put("percent", 62).put("charging", false)
            DeviceSection.NETWORK -> JSONObject().put("ssid", "Home-5G")
            else -> null
        }
    }

    private fun pipeline(
        providers: List<ToolProvider>,
        env: ToolEnvironment,
        interaction: UserInteraction = UserInteraction.NONE,
    ) = ToolPipeline(
        registry = ToolRegistry(providers),
        environment = { env },
        appContext = ApplicationProvider.getApplicationContext(),
        logger = AndroidAgentLogger,
        runId = "run1",
        cancelled = { false },
        interaction = interaction,
    ).also { it.catalog() }

    private fun call(name: String, args: String) = AgentModelClient.ToolCall("c1", name, args)

    @Test
    fun deviceRead_readonly_returnsOkWithDataNoApproval() {
        val p = pipeline(listOf(deviceProvider()), ToolEnvironment())
        val result = p.execute(call("device_read", """{"sections":["battery"]}"""))
        val json = JSONObject(result.content)
        assertEquals("ok", json.getString("status"))
        assertEquals(62, json.getJSONObject("data").getJSONObject("battery").getInt("percent"))
        // 只读：不标 effect_verified，不敏感
        assertFalse(json.has("effect_verified"))
        assertFalse(result.sensitive)
    }

    @Test
    fun deviceRead_network_marksSensitive() {
        val p = pipeline(listOf(deviceProvider()), ToolEnvironment())
        val result = p.execute(call("device_read", """{"sections":["network"]}"""))
        assertTrue(result.sensitive)
    }

    @Test
    fun categorizedTool_manualMode_declinedBlocksExecution() {
        var executed = false
        val external = object : ToolProvider {
            override val tools = listOf(ContractTool(ExternalFake { executed = true }))
        }
        val decline = object : UserInteraction {
            override val available = true
            override fun ask(question: UserQuestion, timeoutMs: Long) = UserAnswer.Declined
            override fun approve(request: ApprovalRequest, timeoutMs: Long) = ApprovalDecision.Declined
        }
        // YOLO（默认）：直接执行。
        assertEquals("ok", pipeline(listOf(external), ToolEnvironment(), decline).execute(call("ext_fake", "{}")).status)
        assertTrue(executed)
        executed = false
        // 手动审批：删东西固定会问 → 用户拒绝 → 不执行。
        val manual = ToolEnvironment(approvalPolicy = ApprovalPolicy.MANUAL_BUILT_IN)
        val result = pipeline(listOf(external), manual, decline).execute(call("ext_fake", "{}"))
        assertEquals("error", result.status)
        assertEquals("USER_DECLINED", result.errorCode)
        assertFalse("有后果的工具在未确认时不得执行", executed)
    }

    private fun deviceProvider() = object : ToolProvider {
        override val tools = listOf(ContractTool(DeviceReadTool(fakeBackend)))
    }

    // 一个最小 external 假工具：验证审批中央判定在管线里真的强制
    private class ExtIn : ToolInput
    private class ExtOut : ToolOutput
    private inner class ExternalFake(val onExec: () -> Unit) : ToolContract<ExtIn, ExtOut> {
        override val name = "ext_fake"
        override val domain = ToolDomain.DEVICE
        override val summary = "external 假工具"
        override fun schema(env: ToolEnvironment) = JSONObject().put("type", "object")
        override fun parse(args: ToolArgs, env: ToolEnvironment) = ExtIn()
        override fun resolve(input: ExtIn, env: ToolEnvironment) =
            CallResolution(
                risk = Risk.EXTERNAL, sensitivity = Sensitivity.NORMAL, resources = emptySet(),
                category = ApprovalCategory.DELETE,
            )
        override fun execute(input: ExtIn, resolution: CallResolution, ctx: ToolContext): Verdict<ExtOut> {
            onExec()
            return Verdict.Done(ExtOut(), Evidence.ReadBack("x"))
        }
        override fun renderForModel(output: ExtOut) = ModelContent.Json(JSONObject())
    }
}
