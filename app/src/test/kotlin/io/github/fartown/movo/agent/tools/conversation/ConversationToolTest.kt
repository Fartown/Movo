package io.github.fartown.movo.agent.tools.conversation

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolPipeline
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.core.AndroidAgentLogger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** conversation_read 的管线验证：只读、按会话绑定可用、工具调用不丢、长消息接着读、游标失效。 */
@RunWith(RobolectricTestRunner::class)
class ConversationToolTest {

    private val history = listOf(
        ConversationEntryView(0, "user", "帮我订明天的闹钟"),
        ConversationEntryView(1, "assistant", "好的，已设置"),
        ConversationEntryView(2, "user", "再查下天气"),
    )

    private fun backendOf(entries: List<ConversationEntryView>) = object : ConversationBackend {
        override fun load() = entries
    }

    private fun pipeline(
        bound: Boolean,
        backend: ConversationBackend = backendOf(history),
    ): ToolPipeline {
        val provider = object : ToolProvider {
            override val tools = listOf(ContractTool(ConversationReadTool(backend)))
        }
        return ToolPipeline(
            registry = ToolRegistry(listOf(provider)),
            environment = { ToolEnvironment(conversationBound = bound) },
            appContext = ApplicationProvider.getApplicationContext(),
            logger = AndroidAgentLogger,
            runId = "run1",
            cancelled = { false },
        ).also { it.catalog() }
    }

    private fun call(args: String) = AgentModelClient.ToolCall("c1", "conversation_read", args)

    @Test
    fun read_isReadOnly_private_rendersRoleAndText() {
        val p = pipeline(bound = true)
        val r = p.execute(call("{}"))
        val json = JSONObject(r.content)
        assertEquals("ok", json.getString("status"))
        assertFalse(json.has("effect_verified"))
        assertTrue(r.sensitive) // PRIVATE
        val data = json.getJSONObject("data")
        assertEquals(3, data.getInt("total_messages"))
        val first = data.getJSONArray("entries").getJSONObject(0)
        assertEquals("user", first.getString("role"))
        assertEquals("帮我订明天的闹钟", first.getString("text"))
    }

    @Test
    fun read_query_filters() {
        val p = pipeline(bound = true)
        val r = p.execute(call("""{"query":"天气"}"""))
        val entries = JSONObject(r.content).getJSONObject("data").getJSONArray("entries")
        assertEquals(1, entries.length())
        assertTrue(entries.getJSONObject(0).getString("text").contains("天气"))
    }

    @Test
    fun notBound_unavailable() {
        val p = pipeline(bound = false)
        val r = p.execute(call("{}"))
        assertEquals("error", r.status)
        assertEquals("DISABLED", r.errorCode)
    }

    @Test
    fun staleCursor_whenHistoryShrank() {
        val p = pipeline(bound = true)
        // 游标声明第一次读时有 5 条，但当前只有 3 条（对话被改过）→ 失效
        val r = p.execute(call("""{"cursor":"1:0:5"}"""))
        assertEquals("STALE_OBSERVATION", r.errorCode)
    }

    @Test
    fun longMessage_continuesInsideTheSameMessage() {
        val long = "开头" + "中".repeat(9_000) + "结尾"
        val p = pipeline(bound = true, backend = backendOf(listOf(ConversationEntryView(0, "tool", long), ConversationEntryView(1, "user", "下一条"))))
        val first = JSONObject(p.execute(call("""{"max_chars":8000}""")).content).getJSONObject("data")
        val firstEntry = first.getJSONArray("entries").getJSONObject(0)
        assertFalse(firstEntry.getBoolean("complete"))
        assertEquals(8000, firstEntry.getString("text").length)
        val cursor = first.getString("next_cursor")
        assertEquals("0:8000:2", cursor)
        val second = JSONObject(p.execute(call("""{"cursor":"$cursor"}""")).content).getJSONObject("data")
        val rest = second.getJSONArray("entries").getJSONObject(0)
        assertEquals(0, rest.getInt("index"))
        assertEquals(8000, rest.getInt("offset"))
        assertTrue(rest.getString("text").endsWith("结尾"))
        assertEquals("下一条", second.getJSONArray("entries").getJSONObject(1).getString("text"))
        assertFalse(second.getBoolean("has_more"))
    }

    @Test
    fun cursor_staysOnFirstSnapshot_whileTheRunAppendsSteps() {
        val entries = (0 until 30).map { ConversationEntryView(it, "assistant", "第 $it 条") }.toMutableList()
        val p = pipeline(bound = true, backend = object : ConversationBackend {
            override fun load() = entries.toList()
        })
        val first = JSONObject(p.execute(call("{}")).content).getJSONObject("data")
        assertEquals(20, first.getJSONArray("entries").length())
        // 这一轮又做了几步：续读不失效，也不追着读新产生的步骤。
        entries += ConversationEntryView(30, "assistant", "[调用工具 conversation_read（调用 id c1）] {}")
        entries += ConversationEntryView(31, "tool", "[conversation_read 的结果（调用 id c1）] …")
        val second = JSONObject(p.execute(call("""{"cursor":"${first.getString("next_cursor")}"}""")).content).getJSONObject("data")
        assertEquals(30, second.getInt("total_messages"))
        assertEquals(10, second.getJSONArray("entries").length())
        assertFalse(second.getBoolean("has_more"))
        // 不带 cursor 重新读就能看到新步骤。
        assertEquals(32, JSONObject(p.execute(call("{}")).content).getJSONObject("data").getInt("total_messages"))
    }

    @Test
    fun index_jumpsToAMessage() {
        val p = pipeline(bound = true)
        val entries = JSONObject(p.execute(call("""{"index":2}""")).content).getJSONObject("data").getJSONArray("entries")
        assertEquals(1, entries.length())
        assertEquals(2, entries.getJSONObject(0).getInt("index"))
    }

    @Test
    fun runtimeBackend_keepsToolCallsAndNamesToolResults() {
        val messages = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "帮我在京东搜耳机"),
            AgentModelClient.ConversationMessage(
                role = "assistant",
                content = "我先打开京东。",
                reasoningContent = "想一想",
                toolCallsJson = """[{"id":"call_1","type":"function","function":{"name":"app_open","arguments":"{\"app\":\"京东\"}"}}]""",
            ),
            AgentModelClient.ConversationMessage(role = "tool", content = "{\"status\":\"ok\"}", toolCallId = "call_1"),
            AgentModelClient.ConversationMessage(
                role = "user",
                contentJson = """[{"type":"text","text":"看这张图"},{"type":"text","text":"[图片已省略]"}]""",
            ),
        )
        val view = RuntimeConversationBackend { messages }.load()
        val assistant = view[1].text
        assertTrue(assistant.contains("我先打开京东。"))
        assertTrue(assistant.contains("[调用工具 app_open（调用 id call_1）] {\"app\":\"京东\"}"))
        assertFalse("有正文和工具调用时不给推理", assistant.contains("想一想"))
        assertTrue(view[2].text.startsWith("[app_open 的结果（调用 id call_1）]"))
        assertTrue(view[2].text.contains("\"status\":\"ok\""))
        assertEquals("看这张图\n[图片已省略]", view[3].text)
    }
}
