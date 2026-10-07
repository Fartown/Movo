package io.github.fartown.movo.ui.app

import androidx.compose.material.icons.Icons
import io.github.fartown.movo.agent.tool.RootRequirement
import io.github.fartown.movo.agent.tools.AgentToolSubsystem
import io.github.fartown.movo.agent.tools.ToolServices
import io.github.fartown.movo.agent.tools.core.MemoryScope
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.core.AndroidAgentLogger
import io.github.fartown.movo.ui.components.toolIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.model.projectToolGroups
import io.github.fartown.movo.ui.model.toolCardRequirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ToolCatalogUiTest {
    @Test
    fun everyRuntimeToolAndDisplayedCardHasASpecificIcon() {
        // 运行时工具名取手机版的完整目录（全部能力都可用），工具卡上不能出现通用扳手图标。
        val env = ToolEnvironment(
            rootAvailable = true, accessibilityAvailable = true, notificationAccess = true,
            usageAccess = true, locationAccess = true, colorOs = true, linuxReady = true,
            conversationBound = true, memoryScope = MemoryScope.REAL, interactive = true,
        )
        val runtimeNames = AgentToolSubsystem(
            services = ToolServices(
                context = RuntimeEnvironment.getApplication(),
                logger = AndroidAgentLogger,
                runId = "run-icons",
                rootAvailable = { true },
            ),
            environment = { env },
        ).pipeline.registryView.tools.map { it.name }
            // ui_focus 是电视遥控焦点工具，触屏设备上不可用，手机界面不会出现。
            .filter { it != "ui_focus" }
        val cardIds = buildToolsState(RuntimeEnvironment.getApplication()).groups
            .flatMap { it.tools }.map { it.id }
        assertTrue(runtimeNames.isNotEmpty())
        val generic = (runtimeNames + cardIds).distinct().filter { toolIcon(it) == MovoIcons.Wrench }
        assertEquals(emptyList<String>(), generic)
    }

    @Test
    fun lucideToolIconsKeepTheSameSemanticsAsTheLegacyIcons() {
        assertEquals(MovoIcons.Telescope, toolIcon("网页搜索"))
        assertEquals(toolIcon("网页搜索"), toolIcon("web_search"))
        assertEquals(MovoIcons.Globe, toolIcon("browser_use"))
        assertEquals(MovoIcons.Plug, toolIcon("mcp_server_search_012345"))
        assertEquals(MovoIcons.Wrench, toolIcon("unknown_tool"))
    }

    @Test
    fun everyDisplayedCardHasExplicitMetadataAndKeepsItsOriginalOrder() {
        val groups = buildToolsState(RuntimeEnvironment.getApplication()).groups
        val allCards = groups.flatMap { it.tools }
        allCards.forEach { toolCardRequirement(it.id) }
        assertEquals(allCards.size, allCards.map { it.id }.distinct().size)
        assertEquals(groups, projectToolGroups(groups, true, false, false))
        assertEquals(groups, projectToolGroups(groups, false, true, true))

        val ordinaryCards = projectToolGroups(groups, false, false, false).flatMap { it.tools }
        assertTrue(ordinaryCards.isNotEmpty())
        assertEquals(allCards.filter { card ->
            val requirement = toolCardRequirement(card.id)
            requirement.rootRequirement != RootRequirement.REQUIRED && !requirement.colorOs
        }, ordinaryCards)
    }
}
