package io.github.fartown.movo.ui.app

import androidx.compose.material.icons.Icons
import io.github.fartown.movo.agent.tool.RootRequirement
import io.github.fartown.movo.ui.components.toolIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.model.projectToolGroups
import io.github.fartown.movo.ui.model.toolCardRequirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun everyDisplayedCardHasASpecificIcon() {
        val cardIds = buildToolsState(RuntimeEnvironment.getApplication()).groups
            .flatMap { it.tools }.map { it.id }
        cardIds.distinct().forEach { name ->
            assertNotEquals(name, MovoIcons.Wrench, toolIcon(name))
        }
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
