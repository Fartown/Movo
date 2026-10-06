package io.github.fartown.movo.agent.tools.mcp

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.mcp.McpHttpStatusException
import io.github.fartown.movo.agent.mcp.McpJsonRpcException
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.ApprovalPolicy
import io.github.fartown.movo.agent.tools.core.ApprovalDecision
import io.github.fartown.movo.agent.tools.core.ApprovalRequest
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolPipeline
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.agent.tools.core.UserAnswer
import io.github.fartown.movo.agent.tools.core.UserInteraction
import io.github.fartown.movo.agent.tools.core.UserQuestion
import io.github.fartown.movo.core.AndroidAgentLogger
import io.github.fartown.movo.data.model.McpServerSetting
import io.github.fartown.movo.data.model.McpToolDefinition
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** MCP 领域（§42）：直接暴露 / find+call / 风险注解 / 错误码拆分 / 命名 / 预算。 */
@RunWith(RobolectricTestRunner::class)
class McpToolsTest {

    private fun server(id: String, name: String) =
        McpServerSetting(id = id, name = name, url = "https://example.com/mcp")

    private fun tool(
        name: String,
        schema: String = "{}",
        readOnly: Boolean? = null,
        destructive: Boolean? = null,
        description: String = "",
    ) = McpToolDefinition(
        name = name,
        description = description,
        inputSchemaJson = schema,
        readOnlyHint = readOnly,
        destructiveHint = destructive,
    )

    private fun catalog(
        raw: List<McpRawTool>,
        budgetTokens: Int = McpCatalog.DEFAULT_BUDGET_TOKENS,
    ) = McpCatalog.build(raw, budgetTokens)

    private fun raw(serverId: String, serverName: String, definition: McpToolDefinition) =
        McpRawTool(server(serverId, serverName), definition, bearerToken = null)

    private class FakeBackend(
        private val responses: Map<String, McpCallOutcome> = emptyMap(),
        private val default: McpCallOutcome = McpCallOutcome.Success(
            content = listOf("done"), structured = null, images = emptyList(),
            truncated = false, remoteError = null,
        ),
    ) : McpBackend {
        var lastTool: String? = null
        var lastArguments: JSONObject? = null

        override fun call(entry: McpToolEntry, arguments: JSONObject): McpCallOutcome {
            lastTool = entry.definition.name
            lastArguments = arguments
            return responses[entry.definition.name] ?: default
        }
    }

    private fun pipeline(
        providers: List<ToolProvider>,
        interaction: UserInteraction = UserInteraction.NONE,
        env: ToolEnvironment = ToolEnvironment(),
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

    private val decline = object : UserInteraction {
        override val available = true
        override fun ask(question: UserQuestion, timeoutMs: Long) = UserAnswer.Declined
        override fun approve(request: ApprovalRequest, timeoutMs: Long) = ApprovalDecision.Declined
    }

    @Test
    fun directReadOnlyTool_returnsReadNoEffectVerified_sensitive() {
        val cat = catalog(listOf(raw("s1", "Search", tool("lookup", readOnly = true))))
        assertFalse(cat.overBudget)
        val name = cat.entries.single().shortName
        val result = pipeline(listOf(McpToolProvider(cat, FakeBackend()))).execute(call(name, "{}"))
        val json = JSONObject(result.content)
        assertEquals("ok", json.getString("status"))
        // 只读：不标 effect_verified
        assertFalse(json.has("effect_verified"))
        // MCP 结果 PRIVATE → 敏感
        assertTrue(result.sensitive)
    }

    @Test
    fun directWriteTool_returnsDispatchedNotVerified() {
        // 无注解 → LOCAL（不再一律 external），不需确认；写工具成功无回读 → Dispatched
        val cat = catalog(listOf(raw("s1", "Jira", tool("create_issue"))))
        val name = cat.entries.single().shortName
        val result = pipeline(listOf(McpToolProvider(cat, FakeBackend()))).execute(call(name, "{}"))
        val json = JSONObject(result.content)
        assertEquals("ok", json.getString("status"))
        assertFalse(json.getBoolean("effect_verified"))
    }

    @Test
    fun directDestructiveTool_manualMode_declineBlocks() {
        val backend = FakeBackend()
        val cat = catalog(listOf(raw("s1", "Admin", tool("delete_all", destructive = true))))
        val name = cat.entries.single().shortName
        val manual = ToolEnvironment(approvalPolicy = ApprovalPolicy.MANUAL_BUILT_IN)
        val result = pipeline(listOf(McpToolProvider(cat, backend)), decline, manual).execute(call(name, "{}"))
        assertEquals("error", result.status)
        assertEquals("USER_DECLINED", result.errorCode)
        assertNull("external 工具未确认不得执行", backend.lastTool)
    }

    @Test
    fun overBudget_exposesFindAndCall() {
        val cat = catalog(
            listOf(
                raw("s1", "Search", tool("lookup", readOnly = true)),
                raw("s1", "Search", tool("fetch", readOnly = true)),
            ),
            budgetTokens = 0,
        )
        assertTrue(cat.overBudget)
        val names = McpToolProvider(cat, FakeBackend()).tools.map { it.name }.toSet()
        assertEquals(setOf("mcp_find", "mcp_call"), names)
    }

    @Test
    fun mcpCall_unknownTool_returnsUnknownTool() {
        val cat = catalog(listOf(raw("s1", "Search", tool("lookup", readOnly = true))), budgetTokens = 0)
        val result = pipeline(listOf(McpToolProvider(cat, FakeBackend())))
            .execute(call("mcp_call", """{"tool":"nope","arguments":{}}"""))
        assertEquals("error", result.status)
        assertEquals("UNKNOWN_TOOL", result.errorCode)
    }

    @Test
    fun mcpCall_remoteError_returnsExternalErrorWithDetail() {
        val cat = catalog(listOf(raw("s1", "Search", tool("lookup", readOnly = true))), budgetTokens = 0)
        val name = cat.entries.single().shortName
        val backend = FakeBackend(
            responses = mapOf(
                "lookup" to McpCallOutcome.Success(
                    content = listOf("boom from server"), structured = null,
                    images = emptyList(), truncated = false, remoteError = "boom from server",
                ),
            ),
        )
        val result = pipeline(listOf(McpToolProvider(cat, backend)))
            .execute(call("mcp_call", """{"tool":"$name","arguments":{}}"""))
        assertEquals("error", result.status)
        assertEquals("EXTERNAL_ERROR", result.errorCode)
        assertTrue(JSONObject(result.content).getString("detail").contains("boom"))
    }

    @Test
    fun mcpFind_returnsMatchesByKeyword() {
        val cat = catalog(
            listOf(
                raw("s1", "Search", tool("web_lookup", readOnly = true, description = "查网页")),
                raw("s1", "Search", tool("fetch_file", readOnly = true)),
            ),
            budgetTokens = 0,
        )
        val result = pipeline(listOf(McpToolProvider(cat, FakeBackend())))
            .execute(call("mcp_find", """{"query":"lookup"}"""))
        val json = JSONObject(result.content)
        assertEquals("ok", json.getString("status"))
        val matches = json.getJSONObject("data").getJSONArray("matches")
        assertEquals(1, matches.length())
        assertTrue(matches.getJSONObject(0).getString("name").contains("web_lookup"))
    }

    @Test
    fun shortName_chineseServer_fallsBackToSequential_andCollisionHashes() {
        val cat = catalog(
            listOf(
                raw("id-a", "搜索", tool("lookup", readOnly = true)),
                raw("id-b", "工具", tool("lookup", readOnly = true)),
            ),
        )
        val names = cat.entries.map { it.shortName }
        // 中文清洗为空 → s1 / s2
        assertTrue(names.any { it.startsWith("mcp_s1_") })
        assertTrue(names.any { it.startsWith("mcp_s2_") })
        // 两个工具短名不冲突
        assertEquals(2, names.toSet().size)
    }

    @Test
    fun errorMapping_splitsCodes() {
        assertEquals(
            ToolErrorCode.PERMISSION_REQUIRED,
            McpErrorMapping.fromThrowable(McpHttpStatusException(401, "unauthorized")).code,
        )
        assertEquals(
            ToolErrorCode.INVALID_ARGUMENTS,
            McpErrorMapping.fromThrowable(McpJsonRpcException(-32602, "bad params")).code,
        )
        assertEquals(
            ToolErrorCode.TOO_LARGE,
            McpErrorMapping.fromThrowable(IllegalStateException("MCP 响应超过大小限制")).code,
        )
        assertEquals(
            ToolErrorCode.EXTERNAL_ERROR,
            McpErrorMapping.fromThrowable(McpJsonRpcException(-32000, "server oops")).code,
        )
    }

    @Test
    fun directTool_missingRequiredArgument_isInvalidArguments() {
        val schema = """{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}"""
        val cat = catalog(listOf(raw("s1", "Search", tool("lookup", schema = schema, readOnly = true))))
        val name = cat.entries.single().shortName
        val result = pipeline(listOf(McpToolProvider(cat, FakeBackend()))).execute(call(name, "{}"))
        assertEquals("error", result.status)
        assertEquals("INVALID_ARGUMENTS", result.errorCode)
    }
}
