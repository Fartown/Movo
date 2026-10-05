package io.github.fartown.movo.agent.tools

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.tools.core.MemoryScope
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.core.AndroidAgentLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 工具子系统总装验证：全部领域 Provider 能装配成一个 ToolPipeline，42 个工具齐备、无重名、目录与分节可生成。 */
@RunWith(RobolectricTestRunner::class)
class AgentToolSubsystemTest {

    /** 全能力环境：Root、无障碍、各权限、ColorOS、Linux、绑定会话、真实记忆，使所有工具都通过可用性门禁。 */
    private val capableEnv = ToolEnvironment(
        rootAvailable = true,
        accessibilityAvailable = true,
        notificationAccess = true,
        usageAccess = true,
        locationAccess = true,
        colorOs = true,
        linuxReady = true,
        conversationBound = true,
        conversationId = "conversation-test",
        memoryScope = MemoryScope.REAL,
        interactive = true,
    )

    private fun subsystem() = AgentToolSubsystem(
        services = ToolServices(
            context = ApplicationProvider.getApplicationContext(),
            logger = AndroidAgentLogger,
            runId = "run-subsystem",
            rootAvailable = { true },
        ),
        environment = { capableEnv },
    )

    /** 定义清单总表的 42 个工具名（mcp 行以固定的 mcp_call/mcp_find 代表；目录为空时它们不下发，这里只验证非 mcp 的常驻集合）。 */
    private val expectedResident = setOf(
        // A 设备 / B 应用
        "device_read", "device_toggle", "setting_read", "setting_write", "device_diagnostics",
        "app_search", "app_open", "app_control",
        // C 屏幕
        "ui_observe", "ui_tap", "ui_scroll", "ui_swipe", "ui_input", "ui_key", "ui_wait",
        "clipboard_read", "clipboard_write",
        // D 时钟音频
        "clock_create", "clock_read", "media_control", "volume_set",
        // E 个人数据
        "personal_search", "sms_code_read", "usage_read", "health_read", "wifi_password_read",
        // F 文件 / G 终端
        "file_search", "file_read", "file_write", "file_list", "terminal_run", "terminal_job",
        // H 网页
        "browser_open", "browser_read", "browser_act",
        // I 记忆/技能/会话
        "memory_read", "memory_write", "skill_read", "skill_install", "conversation_read",
        // J 交互/元工具
        "ask_user", "tool_search",
        // K 后台监听与通知
        "monitor_start", "monitor_stop", "monitor_list", "notify_user",
    )

    @Test
    fun assembles_allToolsRegistered_noDuplicates() {
        subsystem().use { sub ->
            val names = sub.pipeline.registryView.tools.map { it.name }
            // 无重名（ToolRegistry 构造时已 check，这里再确认一次）
            assertEquals(names.size, names.toSet().size)
            val present = names.toSet()
            val missing = expectedResident - present
            assertTrue("缺少工具：$missing", missing.isEmpty())
        }
    }

    @Test
    fun catalog_underCapableEnv_coversEveryDomain() {
        subsystem().use { sub ->
            val catalog = sub.pipeline.catalog()
            val catalogNames = (0 until catalog.length()).map {
                catalog.getJSONObject(it).getJSONObject("function").getString("name")
            }.toSet()
            // 全能力环境下常驻工具都应进目录；tool_search 仅在存在未加载按需工具时可用，
            // 本版 42 工具全常驻（无 DEFERRED），故 tool_search 正确地不进目录。
            val missing = expectedResident - "tool_search" - catalogNames
            assertTrue("目录缺少：$missing", missing.isEmpty())
            assertTrue("无可加载按需工具时 tool_search 不应进目录", "tool_search" !in catalogNames)
        }
    }

    @Test
    fun everyTool_producesObjectSchema() {
        subsystem().use { sub ->
            sub.pipeline.registryView.tools.forEach { tool ->
                val schema = tool.parameters(capableEnv)
                assertEquals("${tool.name} 的 schema 顶层应为 object", "object", schema.getString("type"))
            }
        }
    }

    @Test
    fun promptSections_presentForAvailableDomains() {
        subsystem().use { sub ->
            val sections = sub.pipeline.promptSections()
            assertTrue("应至少有若干领域系统提示分节", sections.size >= 3)
        }
    }

    @Test
    fun rootDisabledEnv_hidesRootOnlyTools() {
        subsystem().use { sub ->
            val noRoot = capableEnv.copy(rootAvailable = false, colorOs = false)
            val catalog = sub.pipeline.registryView.catalog(noRoot, loadedDeferred = emptySet())
            val names = (0 until catalog.length()).map {
                catalog.getJSONObject(it).getJSONObject("function").getString("name")
            }.toSet()
            // 需 Root 的工具应被目录隐藏
            assertTrue("wifi_password_read 需 Root，应隐藏", "wifi_password_read" !in names)
            // 不需 Root 的工具仍在
            assertTrue("device_read 不需 Root，应保留", "device_read" in names)
        }
    }
}
