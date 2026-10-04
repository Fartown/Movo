package io.github.fartown.movo.agent.tools.memory

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.ApprovalDecision
import io.github.fartown.movo.agent.tools.core.ApprovalRequest
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.MemoryScope
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolPipeline
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.agent.tools.core.UserAnswer
import io.github.fartown.movo.agent.tools.core.UserInteraction
import io.github.fartown.movo.agent.tools.core.UserQuestion
import io.github.fartown.movo.core.AndroidAgentLogger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** memory_read / memory_write 的管线验证：只读、回读 Done、冲突、唯一匹配、clear 外发确认、污点确认。 */
@RunWith(RobolectricTestRunner::class)
class MemoryToolsTest {

    /** 内存假记忆后端：revision 用自增计数，write 做 CAS。 */
    private class FakeMemory(initial: String = "") : MemoryBackend {
        private var content = initial
        private var version = 0
        private fun rev() = "rev$version"
        override fun load(scope: MemoryScopeArg): MemoryLoad =
            MemoryLoad.Ok(rev(), content, if (content.isEmpty()) 0 else content.split('\n').size, content.toByteArray().size)
        override fun write(scope: MemoryScopeArg, content: String, expectedRevision: String): MemoryCas {
            if (expectedRevision != rev()) return MemoryCas.Conflict(rev())
            this.content = content
            version++
            return MemoryCas.Ok(rev(), content.toByteArray().size, if (content.isEmpty()) 0 else content.split('\n').size)
        }
        fun current() = content
    }

    private val approve = object : UserInteraction {
        override val available = true
        override fun ask(question: UserQuestion, timeoutMs: Long) = UserAnswer.Declined
        override fun approve(request: ApprovalRequest, timeoutMs: Long) = ApprovalDecision.Approved(false)
    }
    private val decline = object : UserInteraction {
        override val available = true
        override fun ask(question: UserQuestion, timeoutMs: Long) = UserAnswer.Declined
        override fun approve(request: ApprovalRequest, timeoutMs: Long) = ApprovalDecision.Declined
    }

    private fun pipeline(backend: MemoryBackend, interaction: UserInteraction = approve): ToolPipeline {
        val provider = object : ToolProvider {
            override val tools = listOf(
                ContractTool(MemoryReadTool(backend)),
                ContractTool(MemoryWriteTool(backend)),
            )
        }
        return ToolPipeline(
            registry = ToolRegistry(listOf(provider)),
            environment = { ToolEnvironment(memoryScope = MemoryScope.REAL) },
            appContext = ApplicationProvider.getApplicationContext(),
            logger = AndroidAgentLogger,
            runId = "run1",
            cancelled = { false },
            interaction = interaction,
        ).also { it.catalog() }
    }

    private fun call(name: String, args: String) = AgentModelClient.ToolCall("c1", name, args)

    @Test
    fun read_isReadOnly_private_withRevision() {
        val p = pipeline(FakeMemory("用户叫张三\n喜欢喝茶"))
        val r = p.execute(call("memory_read", "{}"))
        val json = JSONObject(r.content)
        assertEquals("ok", json.getString("status"))
        assertFalse(json.has("effect_verified"))
        assertTrue(r.sensitive) // PRIVATE
        val data = json.getJSONObject("data")
        assertEquals("rev0", data.getString("revision"))
        assertEquals(1, data.getInt("start_line"))
        assertTrue(data.getString("content").contains("张三"))
    }

    @Test
    fun read_query_filtersMatchingLines() {
        val p = pipeline(FakeMemory("line a\nkey target\nline c\nline d"))
        val r = p.execute(call("memory_read", """{"query":"target"}"""))
        val data = JSONObject(r.content).getJSONObject("data")
        assertTrue(data.getString("content").contains("target"))
        assertEquals(1, data.getInt("matched_lines"))
    }

    @Test
    fun append_readBack_returnsDoneEffectVerified() {
        val backend = FakeMemory("已有一行")
        val p = pipeline(backend)
        val r = p.execute(call("memory_write", """{"mode":"append","new_text":"新增一行"}"""))
        val json = JSONObject(r.content)
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
        assertTrue(backend.current().contains("新增一行"))
    }

    @Test
    fun replace_unique_done() {
        val backend = FakeMemory("喜欢喝茶")
        val p = pipeline(backend)
        val r = p.execute(call("memory_write", """{"mode":"replace","old_text":"喝茶","new_text":"喝咖啡"}"""))
        assertEquals("ok", JSONObject(r.content).getString("status"))
        assertEquals("喜欢喝咖啡", backend.current())
    }

    @Test
    fun replace_notFound() {
        val p = pipeline(FakeMemory("abc"))
        val r = p.execute(call("memory_write", """{"mode":"replace","old_text":"zzz","new_text":"x"}"""))
        assertEquals("error", r.status)
        assertEquals("NOT_FOUND", r.errorCode)
    }

    @Test
    fun replace_ambiguous() {
        val p = pipeline(FakeMemory("dup\ndup"))
        val r = p.execute(call("memory_write", """{"mode":"replace","old_text":"dup","new_text":"x"}"""))
        assertEquals("AMBIGUOUS", r.errorCode)
    }

    @Test
    fun clear_isExternal_declineBlocks() {
        val p = pipeline(FakeMemory("something"), decline)
        val r = p.execute(call("memory_write", """{"mode":"clear","revision":"rev0"}"""))
        assertEquals("error", r.status)
        assertEquals("USER_DECLINED", r.errorCode)
    }

    @Test
    fun clear_wrongRevision_conflict() {
        val p = pipeline(FakeMemory("something"), approve)
        val r = p.execute(call("memory_write", """{"mode":"clear","revision":"revX"}"""))
        assertEquals("CONFLICT", r.errorCode)
    }

    @Test
    fun append_whenTainted_requiresApproval() {
        val p = pipeline(FakeMemory("x"), decline)
        p.taint.mark("browser_read") // 本轮读过不可信内容
        val r = p.execute(call("memory_write", """{"mode":"append","new_text":"y"}"""))
        assertEquals("USER_DECLINED", r.errorCode)
    }
}
