package io.github.fartown.movo.agent.tools.memory

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.ApprovalDecision
import io.github.fartown.movo.agent.tools.core.ApprovalPolicy
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
import io.github.fartown.movo.data.repository.AgentMemoryRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** memory_read / memory_write 的管线验证：只读、回读 Done、冲突、唯一匹配、手动审批时清空算删东西要问。 */
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
        override fun approve(request: ApprovalRequest, timeoutMs: Long) = ApprovalDecision.Approved
    }
    private val decline = object : UserInteraction {
        override val available = true
        override fun ask(question: UserQuestion, timeoutMs: Long) = UserAnswer.Declined
        override fun approve(request: ApprovalRequest, timeoutMs: Long) = ApprovalDecision.Declined
    }

    private fun pipeline(
        backend: MemoryBackend,
        interaction: UserInteraction = approve,
        policy: ApprovalPolicy = ApprovalPolicy.MANUAL_BUILT_IN,
    ): ToolPipeline {
        val provider = object : ToolProvider {
            override val tools = listOf(
                ContractTool(MemoryReadTool(backend)),
                ContractTool(MemoryWriteTool(backend)),
            )
        }
        return ToolPipeline(
            registry = ToolRegistry(listOf(provider)),
            environment = { ToolEnvironment(memoryScope = MemoryScope.REAL, approvalPolicy = policy) },
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
        val segment = data.getJSONArray("segments").getJSONObject(0)
        assertTrue(segment.getString("text").contains("target"))
        assertEquals(1, segment.getInt("start_line"))
        assertEquals(1, data.getInt("matched_lines"))
    }

    @Test
    fun read_query_segmentsCarryLineNumbers() {
        val lines = (1..40).map { if (it == 10 || it == 30) "第 $it 行 咖啡" else "第 $it 行" }
        val p = pipeline(FakeMemory(lines.joinToString("\n")))
        val data = JSONObject(p.execute(call("memory_read", """{"query":"咖啡"}""")).content).getJSONObject("data")
        val segments = data.getJSONArray("segments")
        assertEquals(2, segments.length())
        // 命中行前后各带一行：第 9–11 行、第 29–31 行。
        assertEquals(9, segments.getJSONObject(0).getInt("start_line"))
        assertEquals("第 9 行\n第 10 行 咖啡\n第 11 行", segments.getJSONObject(0).getString("text"))
        assertEquals(29, segments.getJSONObject(1).getInt("start_line"))
        assertEquals(2, data.getInt("matched_lines"))
        assertFalse(data.getBoolean("has_more"))
    }

    @Test
    fun read_query_overBudget_continuesWithNextOffset() {
        val lines = (1..300).map { "第 $it 行 咖啡 " + "x".repeat(80) }
        val p = pipeline(FakeMemory(lines.joinToString("\n")))
        val first = JSONObject(p.execute(call("memory_read", """{"query":"咖啡","max_chars":2000}""")).content).getJSONObject("data")
        assertTrue(first.getBoolean("has_more"))
        val next = first.getInt("next_offset")
        val shown = first.getJSONArray("segments").getJSONObject(0).getString("text").split('\n').size
        assertEquals(shown + 1, next)
        val second = JSONObject(p.execute(call("memory_read", """{"query":"咖啡","max_chars":2000,"offset":$next}""")).content)
            .getJSONObject("data")
        assertEquals(next, second.getJSONArray("segments").getJSONObject(0).getInt("start_line"))
    }

    @Test
    fun read_maxChars_restoredAndBounded() {
        val lines = (1..2000).map { "第 $it 行的记忆内容" }
        val p = pipeline(FakeMemory(lines.joinToString("\n")))
        val defaultRead = JSONObject(p.execute(call("memory_read", "{}")).content).getJSONObject("data")
        assertTrue(defaultRead.getString("content").length <= 12_000)
        assertTrue(defaultRead.getBoolean("has_more"))
        val bigger = JSONObject(p.execute(call("memory_read", """{"max_chars":20000}""")).content)
        assertEquals("ok", bigger.getString("status"))
        val content = bigger.getJSONObject("data").getString("content")
        assertTrue(content.length > 12_000 && content.length <= 20_000)
        // 接着读：next_offset 正好是下一行。
        val nextOffset = bigger.getJSONObject("data").getInt("next_offset")
        assertEquals(content.split('\n').size + 1, nextOffset)
    }

    @Test
    fun read_escapeHeavyContent_staysUnderResultLimit() {
        // 斜杠、引号、换行转义后变两个字符：整份结果仍要放得进一次工具结果的上限，不被整份降级。
        val lines = (1..1500).map { "\"https://a/b/c/d/$it\"" }
        val p = pipeline(FakeMemory(lines.joinToString("\n")))
        val r = p.execute(call("memory_read", """{"max_chars":20000}"""))
        assertTrue(r.content.length <= io.github.fartown.movo.agent.tools.core.ToolProjection.MAX_MODEL_CHARS)
        val data = JSONObject(r.content).getJSONObject("data")
        assertTrue(data.getBoolean("has_more"))
    }

    @Test
    fun androidBackend_usesSameStoreAsSettingsPage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val backend = AndroidMemoryBackend(context) { null }
        AgentMemoryRepository.replaceAll("旧内容")
        val loaded = backend.load(MemoryScopeArg.USER) as MemoryLoad.Ok
        assertEquals("旧内容", loaded.content)
        // 设置页在工具读完之后保存：工具按读到的旧版本写入要冲突，不能覆盖设置页的改动。
        AgentMemoryRepository.replaceAll("设置页改过")
        assertTrue(backend.write(MemoryScopeArg.USER, "工具写的", loaded.revision) is MemoryCas.Conflict)
        assertEquals("设置页改过", AgentMemoryRepository.snapshot().content)
        val fresh = backend.load(MemoryScopeArg.USER) as MemoryLoad.Ok
        assertTrue(backend.write(MemoryScopeArg.USER, "工具写的", fresh.revision) is MemoryCas.Ok)
        assertEquals("工具写的", AgentMemoryRepository.snapshot().content)
    }

    @Test
    fun read_query_manySmallSegments_staysUnderResultLimit() {
        // 命中行彼此隔开：每段只有一两行，段数很多时字段开销也要算进去。
        val lines = (1..6000).map { if (it % 4 == 0) "咖啡$it" else "x" }
        val p = pipeline(FakeMemory(lines.joinToString("\n")))
        val r = p.execute(call("memory_read", """{"query":"咖啡","max_chars":20000}"""))
        assertTrue(r.content.length <= io.github.fartown.movo.agent.tools.core.ToolProjection.MAX_MODEL_CHARS)
        assertTrue(JSONObject(r.content).getJSONObject("data").getBoolean("has_more"))
    }

    @Test
    fun read_longSingleLine_isClipped() {
        val p = pipeline(FakeMemory("a".repeat(30_000) + "\n第二行"))
        val data = JSONObject(p.execute(call("memory_read", "{}")).content).getJSONObject("data")
        assertTrue(data.getString("content").length < 13_000)
        assertEquals(2, data.getInt("next_offset"))
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
    fun clear_yolo_noCard() {
        val p = pipeline(FakeMemory("something"), decline, policy = ApprovalPolicy.YOLO)
        assertEquals("ok", p.execute(call("memory_write", """{"mode":"clear","revision":"rev0"}""")).status)
    }

    @Test
    fun append_manualMode_noCard() {
        val p = pipeline(FakeMemory("x"), decline)
        assertEquals("ok", p.execute(call("memory_write", """{"mode":"append","new_text":"y"}""")).status)
    }

    // ---- 执行卡：标题不写记忆内容（标题会存进对话记录），内容只在展开里 ----

    @Test
    fun stepTitle_memoryWriteHasNoContent_viewHasIt() {
        val p = pipeline(FakeMemory("x"))
        val append = io.github.fartown.movo.agent.model.AgentModelClient.ToolCall(
            "c1", "memory_write", """{"mode":"append","new_text":"我对花生过敏"}""",
        )
        assertEquals("记住一条", p.stepTitle(append))
        val view = p.execute(append).outcome!!.view!!
        assertEquals("已记住", view.summary)
        assertEquals("我对花生过敏", (view.blocks.single() as io.github.fartown.movo.agent.tools.core.ToolUiBlock.Preview).text)
        // 记忆是用户自己说给 Movo 的（原话本来就在对话里），展开内容重启后仍在；标题不写内容，折叠时看不到。
        assertFalse(view.transient)
    }
}
