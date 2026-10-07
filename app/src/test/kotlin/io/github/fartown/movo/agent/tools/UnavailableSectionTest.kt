package io.github.fartown.movo.agent.tools

import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.ModelContent
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
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.agent.tools.core.Verdict
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnavailableSectionTest {
    private object In : ToolInput
    private object Out : ToolOutput

    private class Fake(
        override val name: String,
        override val summary: String,
        private val availability: ToolAvailability,
    ) : ToolContract<In, Out> {
        override val domain = ToolDomain.DEVICE
        override fun availability(env: ToolEnvironment) = availability
        override fun schema(env: ToolEnvironment) = JSONObject().put("type", "object")
        override fun parse(args: ToolArgs, env: ToolEnvironment) = In
        override fun resolve(input: In, env: ToolEnvironment) =
            CallResolution(risk = Risk.READ, sensitivity = Sensitivity.NORMAL, resources = emptySet())
        override fun execute(input: In, resolution: CallResolution, ctx: ToolContext): Verdict<Out> = Verdict.Read(Out)
        override fun renderForModel(output: Out) = ModelContent.Json(JSONObject())
    }

    private fun registry(vararg tools: Fake) = ToolRegistry(listOf(object : ToolProvider {
        override val tools: List<AgentTool> = tools.map { ContractTool(it) }
    }))

    @Test
    fun toolsMissingAPermissionOrRootAreListedWithWhatTheyDoAndWhatIsMissing() {
        val section = registry(
            Fake("volume_set", "调音量。", ToolAvailability.Available),
            Fake("usage_read", "读应用使用时长：今天、本周。", ToolAvailability.Unavailable(ToolErrorCode.PERMISSION_REQUIRED, "需要「使用情况访问」权限")),
            Fake("app_control", "停止或冻结应用。", ToolAvailability.Unavailable(ToolErrorCode.ROOT_REQUIRED, "停止、冻结应用需要 Root")),
            Fake("ui_focus", "移动遥控焦点。", ToolAvailability.Unavailable(ToolErrorCode.NOT_ACTIONABLE, "触屏设备无需遥控焦点工具")),
        ).unavailableSection(ToolEnvironment())!!

        assertEquals("unavailable", section.id)
        assertTrue(section.text, section.text.contains("- usage_read（读应用使用时长：今天、本周）：需要「使用情况访问」权限"))
        assertTrue(section.text, section.text.contains("- app_control（停止或冻结应用）：停止、冻结应用需要 Root"))
        assertTrue(section.text.contains("告诉他缺什么、去哪里开"))
        // 可用的、和设备形态有关的（触屏不需要遥控焦点）不写。
        assertFalse(section.text.contains("volume_set"))
        assertFalse(section.text.contains("ui_focus"))
    }

    @Test
    fun nothingMissingMeansNoSection() {
        assertNull(registry(Fake("volume_set", "调音量。", ToolAvailability.Available)).unavailableSection(ToolEnvironment()))
    }

    @Test
    fun theSectionComesLastAmongThePromptSections() {
        val sections = registry(
            Fake("volume_set", "调音量。", ToolAvailability.Available),
            Fake("usage_read", "读应用使用时长。", ToolAvailability.Unavailable(ToolErrorCode.PERMISSION_REQUIRED, "缺权限")),
        ).promptSections(ToolEnvironment())
        assertEquals("unavailable", sections.last().id)
    }
}
