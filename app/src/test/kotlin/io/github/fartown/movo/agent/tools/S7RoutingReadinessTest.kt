package io.github.fartown.movo.agent.tools

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.tool.AgentToolCapabilities
import io.github.fartown.movo.agent.tools.core.ModelInput
import io.github.fartown.movo.agent.tools.core.MemoryScope
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolSwitches
import io.github.fartown.movo.core.AndroidAgentLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S7 离线选路就绪度（静态部分）：不接模型、不接设备，校验目录本身对「模型选对工具」友好——
 * 描述非空且不过长、无重复描述、命名规范、schema 合法；外加 S4 能力→环境映射正确性。
 * 真正的模型在环（给请求看模型是否选对工具）与真机任务评测另需模型端点与小米设备。
 */
@RunWith(RobolectricTestRunner::class)
class S7RoutingReadinessTest {

    private val capableEnv = ToolEnvironment(
        rootAvailable = true, accessibilityAvailable = true, notificationAccess = true,
        usageAccess = true, locationAccess = true, colorOs = true, linuxReady = true,
        conversationBound = true, memoryScope = MemoryScope.REAL, interactive = true,
    )

    private fun tools() = AgentToolSubsystem(
        services = ToolServices(
            context = ApplicationProvider.getApplicationContext(),
            logger = AndroidAgentLogger,
            runId = "run-s7",
            rootAvailable = { true },
        ),
        environment = { capableEnv },
    ).pipeline.registryView.tools

    @Test
    fun everyTool_hasConciseNonBlankDescription() {
        tools().forEach { tool ->
            assertTrue("${tool.name} 描述不能为空", tool.description.isNotBlank())
            // 选路友好：描述不宜过长（合同要求 summary ≤160，这里留宽到 200 作硬上限）
            assertTrue("${tool.name} 描述过长(${tool.description.length})", tool.description.length <= 200)
        }
    }

    @Test
    fun descriptions_areDistinct() {
        val descs = tools().map { it.description }
        assertEquals("工具描述不应重复（会让模型选路混淆）", descs.size, descs.toSet().size)
    }

    @Test
    fun names_areSnakeCaseAndUnique() {
        val names = tools().map { it.name }
        assertEquals(names.size, names.toSet().size)
        val re = Regex("^[a-z][a-z0-9_]*$")
        names.forEach { assertTrue("$it 命名应为 snake_case", re.matches(it)) }
    }

    @Test
    fun schemas_areWellFormed() {
        tools().forEach { tool ->
            val schema = tool.parameters(capableEnv)
            assertEquals("${tool.name} 顶层类型应为 object", "object", schema.getString("type"))
            assertTrue("${tool.name} 应有 properties", schema.has("properties"))
            if (schema.has("required")) {
                val required = schema.getJSONArray("required")
                val props = schema.getJSONObject("properties")
                for (i in 0 until required.length()) {
                    val key = required.getString(i)
                    assertTrue("${tool.name} required 字段 $key 必须在 properties 中", props.has(key))
                }
            }
        }
    }

    @Test
    fun capabilityMapping_isFaithful() {
        val caps = AgentToolCapabilities(
            rootAvailable = true,
            lsposedAvailable = true,
            accessibilityAvailable = false,
            accessibilityRecoveryAvailable = true,
            notificationsAllowed = false,
            usageAllowed = true,
            locationAllowed = false,
            colorOs = true,
        )
        val env = caps.toToolEnvironment(
            switches = ToolSwitches(),
            linuxReady = true,
            memoryScope = MemoryScope.CHARACTER,
            conversationBound = true,
            interactive = false,
            modelInputs = setOf(ModelInput.TEXT, ModelInput.IMAGE, ModelInput.PDF),
        )
        assertTrue(env.rootAvailable)
        assertTrue(env.lsposedAvailable)
        assertTrue(!env.accessibilityAvailable)
        assertTrue(env.accessibilityRecoverable)
        assertTrue(env.accessibilityUsable) // 可恢复也算可用
        assertTrue(!env.notificationAccess)
        assertTrue(env.usageAccess)
        assertTrue(!env.locationAccess)
        assertTrue(env.colorOs)
        assertTrue(env.linuxReady)
        assertEquals(MemoryScope.CHARACTER, env.memoryScope)
        assertTrue(env.conversationBound)
        assertTrue(!env.interactive)
        assertTrue(ModelInput.PDF in env.modelInputs)
    }
}
