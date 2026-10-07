package io.github.fartown.movo.agent.mcp

import io.github.fartown.movo.data.model.McpServerSetting
import io.github.fartown.movo.data.model.McpToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class McpServerActiveToolsTest {
    @Test
    fun enabledToolIsActiveRegardlessOfSchemaKeywords() {
        val definition = McpToolDefinition(
            name = "create_task",
            inputSchemaJson = """{"type":"object","anyOf":[]}""",
        )
        val server = McpServerSetting(
            id = "server",
            name = "Server",
            url = "https://example.com/mcp",
            tools = listOf(definition),
            enabledToolNames = setOf(definition.name),
        )

        assertEquals(listOf(definition), server.activeTools)
    }
}
