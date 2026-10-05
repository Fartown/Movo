package io.github.fartown.movo.agent.overlay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.monitor.MonitorToolProvider
import io.github.fartown.movo.agent.tools.AgentToolSubsystem
import io.github.fartown.movo.agent.tools.ToolServices
import io.github.fartown.movo.agent.tools.core.MemoryScope
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.core.AndroidAgentLogger
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 悬浮层「正在执行：xx」「准备：xx」的工具显示名（2026-10-05 审查：新工具名在悬浮层显示英文原名）。
 * 名单取自真实的工具注册表，以后新增工具没登记显示名时这里会失败。
 */
@RunWith(RobolectricTestRunner::class)
class AgentOverlayTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyTypedAndMonitorToolHasAnOverlayLabel() {
        val names = registeredToolNames() + MCP_BUDGET_TOOLS
        assertTrue("registry should expose the typed tools and monitor tools: $names", names.size >= 46)
        assertTrue(names.containsAll(MonitorToolProvider(context).tools.map { it.name }))
        val missing = names.filter { toolDisplayNameResource(it) == null }
        assertEquals("tools shown with their raw English name on the overlay", emptyList<String>(), missing)
    }

    @Test
    fun directMcpToolsShowAGenericLabelInsteadOfTheirInternalName() {
        assertEquals(toolDisplayNameResource("mcp_call"), toolDisplayNameResource("mcp_github_search_issues"))
    }

    @Test
    fun everyOverlayLabelIsTranslatedForBothChineseLocales() {
        val resources = (registeredToolNames() + MCP_BUDGET_TOOLS)
            .mapNotNull(::toolDisplayNameResource)
            .map(context.resources::getResourceEntryName)
            .toSet()
        listOf("values-b+zh+Hans", "values-b+zh+Hant").forEach { locale ->
            val translated = translatedStringNames(locale)
            val untranslated = resources.filterNot(translated::contains)
            assertEquals("$locale is missing overlay tool labels", emptyList<String>(), untranslated)
        }
    }

    @Test
    fun unlockPromptCopyIsTranslatedForBothChineseLocales() {
        val names = listOf("overlay_unlock_header", "overlay_unlock_title", "overlay_unlock_message", "overlay_unlock_action")
        listOf("values-b+zh+Hans", "values-b+zh+Hant").forEach { locale ->
            val translated = translatedStringNames(locale)
            assertEquals(locale, emptyList<String>(), names.filterNot(translated::contains))
        }
        assertNotNull(context.getString(io.github.fartown.movo.R.string.overlay_unlock_title))
    }

    private fun registeredToolNames(): Set<String> {
        val env = ToolEnvironment(
            rootAvailable = true, accessibilityAvailable = true, notificationAccess = true,
            usageAccess = true, locationAccess = true, colorOs = true, linuxReady = true,
            conversationBound = true, memoryScope = MemoryScope.REAL, interactive = true,
        )
        return AgentToolSubsystem(
            services = ToolServices(context = context, logger = AndroidAgentLogger, runId = "labels", rootAvailable = { true }),
            environment = { env },
        ).use { subsystem -> subsystem.pipeline.registryView.tools.map { it.name }.toSet() }
    }

    private fun translatedStringNames(locale: String): Set<String> {
        val dir = listOf(File("src/main/res/$locale"), File("app/src/main/res/$locale")).first { it.isDirectory }
        return dir.listFiles { file -> file.name.endsWith(".xml") }.orEmpty()
            .flatMap { file -> STRING_NAME.findAll(file.readText()).map { it.groupValues[1] }.toList() }
            .toSet()
    }

    private companion object {
        /** 超预算时才暴露的两个固定 MCP 工具（空目录下注册表里没有）。 */
        val MCP_BUDGET_TOOLS = setOf("mcp_call", "mcp_find")
        val STRING_NAME = Regex("""<string name="([a-z0-9_]+)"""")
    }
}
