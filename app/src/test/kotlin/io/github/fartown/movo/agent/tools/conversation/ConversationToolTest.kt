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

/** conversation_read 的管线验证：只读、按会话绑定可用、渲染「角色: 文本」、游标失效。 */
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
        assertEquals("user: 帮我订明天的闹钟", first.getString("text"))
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
    fun staleCursor_whenHistorySizeChanged() {
        val p = pipeline(bound = true)
        // 游标声明 snapshotSize=5，但当前只有 3 条 → 失效
        val r = p.execute(call("""{"cursor":"1:5"}"""))
        assertEquals("STALE_OBSERVATION", r.errorCode)
    }
}
