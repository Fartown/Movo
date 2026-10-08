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
    fun shortName_chineseServer_fallsBackToIdHash_stableWhenOthersChange() {
        val a = raw("id-a", "搜索", tool("lookup", readOnly = true))
        val b = raw("id-b", "工具", tool("lookup", readOnly = true))
        val names = catalog(listOf(a, b)).entries.map { it.shortName }
        // 两个工具短名不冲突
        assertEquals(2, names.toSet().size)
        assertTrue(names.all { it.startsWith("mcp_s") && it.endsWith("_lookup") })
        // 停用 A 之后，B 的工具名不变，也不会顶替 A 原来的名字。
        val onlyB = catalog(listOf(b)).entries.single().shortName
        assertEquals(names[1], onlyB)
        assertFalse(onlyB == names[0])
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

    // ---- 重构前后对齐：预算、描述、服务器名搜索、工具名长度、结果类型、大结果 ----

    @Test
    fun budget_typicalServerIsExposedDirectly_over64GoesToFind() {
        val schema = """{"type":"object","properties":{""" +
            (1..8).joinToString(",") { """"p$it":{"type":"string","description":"参数 $it 的说明，写得比较长一点"}""" } + "}}"
        val typical = (1..10).map { raw("s1", "地图", tool("tool_$it", schema = schema, description = "d".repeat(300))) }
        assertFalse(catalog(typical).overBudget)
        val many = (1..65).map { raw("s1", "Many", tool("t$it")) }
        assertTrue(catalog(many).overBudget)
    }

    @Test
    fun directTool_descriptionNotTruncated() {
        val description = "说明".repeat(600)
        val cat = catalog(listOf(raw("s1", "Doc", tool("lookup", description = description))))
        val catalogJson = pipeline(listOf(McpToolProvider(cat, FakeBackend()))).catalog().toString()
        assertTrue(catalogJson.contains(description))
    }

    @Test
    fun mcpFind_matchesServerDisplayName() {
        val cat = catalog(
            listOf(
                raw("s1", "高德地图", tool("maps_geo", readOnly = true)),
                raw("s2", "GitHub", tool("search_repos", readOnly = true)),
            ),
            budgetTokens = 0,
        )
        val data = JSONObject(pipeline(listOf(McpToolProvider(cat, FakeBackend()))).execute(call("mcp_find", """{"query":"高德"}""")).content)
            .getJSONObject("data")
        assertEquals(1, data.getInt("total"))
        assertEquals("高德地图", data.getJSONArray("matches").getJSONObject(0).getString("server"))
    }

    @Test
    fun overBudget_promptListsServers() {
        val cat = catalog(
            listOf(raw("s1", "高德地图", tool("a")), raw("s1", "高德地图", tool("b")), raw("s2", "GitHub", tool("c"))),
            budgetTokens = 0,
        )
        val section = McpToolProvider(cat, FakeBackend()).promptSection!!.text
        assertTrue(section.contains("高德地图（2 个工具）"))
        assertTrue(section.contains("GitHub（1 个工具）"))
    }

    @Test
    fun toolNames_atMost64AndUnique() {
        val longTool = "a_really_long_tool_name_that_goes_on_and_on_and_on_and_on"
        val cat = catalog(
            listOf(
                raw("id-1", "verylongservername", tool(longTool)),
                raw("id-2", "verylongservername", tool(longTool)),
                raw("id-2", "verylongservername", tool("$longTool!")),
                raw("id-3", "verylongservername", tool(longTool)),
            ),
        )
        val names = cat.entries.map { it.shortName }
        assertTrue(names.toString(), names.all { it.length <= McpCatalog.MAX_TOOL_NAME_CHARS })
        assertTrue(names.all { Regex("[A-Za-z0-9_]+").matches(it) })
        assertEquals(4, names.toSet().size)
        // 不超长时名字保持原样。
        assertEquals("mcp_search_lookup", catalog(listOf(raw("s1", "Search", tool("lookup")))).entries.single().shortName)
    }

    private fun entry(protocol: String? = null) = McpToolEntry(
        shortName = "mcp_s_t",
        server = McpServerSetting(id = "s", name = "S", url = "https://example.com/mcp", lastProtocolVersion = protocol),
        definition = tool("t"),
        bearerToken = null,
    )

    @Test
    fun adapter_inputRequiredAndIncomplete_areNotSuccess() {
        val inputRequired = McpResultAdapter.adapt(entry(), JSONObject().put("resultType", "input_required"))
        assertEquals(ToolErrorCode.UNSUPPORTED, (inputRequired as McpCallOutcome.Failure).error.code)
        val incomplete = McpResultAdapter.adapt(
            entry(io.github.fartown.movo.data.model.McpProtocolMode.LATEST),
            JSONObject().put("resultType", "pending").put("content", org.json.JSONArray()),
        )
        assertTrue(incomplete is McpCallOutcome.Failure)
        val complete = McpResultAdapter.adapt(
            entry(io.github.fartown.movo.data.model.McpProtocolMode.LATEST),
            JSONObject().put("resultType", "complete")
                .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "ok"))),
        )
        assertEquals(listOf("ok"), (complete as McpCallOutcome.Success).content)
    }

    @Test
    fun adapter_keepsArrayStructuredContent() {
        val result = McpResultAdapter.adapt(
            entry(),
            JSONObject().put("content", org.json.JSONArray())
                .put("structuredContent", org.json.JSONArray().put(JSONObject().put("id", 1)).put(JSONObject().put("id", 2))),
        ) as McpCallOutcome.Success
        assertTrue(result.structured is org.json.JSONArray)
        val cat = catalog(listOf(raw("s1", "Search", tool("lookup", readOnly = true))))
        val name = cat.entries.single().shortName
        val content = pipeline(listOf(McpToolProvider(cat, FakeBackend(default = result)))).execute(call(name, "{}")).content
        assertEquals(2, JSONObject(content).getJSONObject("data").getJSONArray("structured").length())
    }

    private fun bigResult(text: String, structured: Any?) = McpCallOutcome.Success(
        content = listOf(text), structured = structured, images = emptyList(), truncated = false, remoteError = null,
    )

    private fun runDirect(outcome: McpCallOutcome): JSONObject {
        val cat = catalog(listOf(raw("s1", "Search", tool("lookup", readOnly = true))))
        val content = pipeline(listOf(McpToolProvider(cat, FakeBackend(default = outcome))))
            .execute(call(cat.entries.single().shortName, "{}")).content
        assertTrue(content.length <= io.github.fartown.movo.agent.tools.core.ToolProjection.MAX_MODEL_CHARS)
        return JSONObject(content)
    }

    @Test
    fun largeResult_keepsStructureAndSaysWhatWasCut() {
        val structured = JSONObject().put("items", org.json.JSONArray((1..200).map { "条目 $it" }))
        val json = runDirect(bigResult("正文\n\"引号\" / 斜杠 ".repeat(4000), structured))
        assertEquals("ok", json.getString("status"))
        val data = json.getJSONObject("data")
        assertFalse("不能被整份降级成 data_text", json.has("data_text"))
        assertEquals(200, data.getJSONObject("structured").getJSONArray("items").length())
        assertTrue(data.getBoolean("truncated"))
        assertTrue(data.getInt("shown_chars") < data.getInt("total_chars"))
        assertTrue(json.getJSONArray("warnings").getJSONObject(0).getString("message").contains("只给了开头"))
    }

    @Test
    fun largeStructured_withText_dropsStructuredAndSaysSo() {
        val structured = JSONObject().put("blob", "x".repeat(30_000))
        val data = runDirect(bigResult("同一份数据的文本", structured)).getJSONObject("data")
        assertFalse(data.has("structured"))
        assertEquals("同一份数据的文本", data.getJSONArray("content").getString(0))
        assertTrue(data.getBoolean("truncated"))
    }

    @Test
    fun largeStructured_only_givesItsBeginning() {
        val structured = org.json.JSONArray((1..3000).map { JSONObject().put("id", it).put("name", "名字 $it") })
        val data = runDirect(bigResult("", structured).copy(content = emptyList())).getJSONObject("data")
        assertTrue(data.getString("structured_text").startsWith("[{"))
        assertTrue(data.getBoolean("truncated"))
    }

    @Test
    fun remoteError_longText_keepsCodeAndBoundsDetail() {
        val result = McpResultAdapter.adapt(
            entry(),
            JSONObject().put("isError", true)
                .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "错".repeat(50_000)))),
        ) as McpCallOutcome.Success
        assertTrue(result.remoteError!!.length <= 4_000)
        val cat = catalog(listOf(raw("s1", "Search", tool("lookup", readOnly = true))))
        val json = JSONObject(
            pipeline(listOf(McpToolProvider(cat, FakeBackend(default = result)))).execute(call(cat.entries.single().shortName, "{}")).content,
        )
        assertEquals("EXTERNAL_ERROR", json.getString("code"))
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
