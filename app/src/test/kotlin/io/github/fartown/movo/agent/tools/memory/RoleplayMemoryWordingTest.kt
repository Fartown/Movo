package io.github.fartown.movo.agent.tools.memory

import io.github.fartown.movo.agent.memory.AgentMemoryContext
import io.github.fartown.movo.agent.roleplay.CharacterCardCodec
import io.github.fartown.movo.agent.roleplay.RoleplayRunContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 角色会话的记忆用法和确认卡要说「剧情记忆」，不能再叫已删除的 character_memory_*（重构差异 T1-6）。 */
class RoleplayMemoryWordingTest {
    private val unused = object : MemoryBackend {
        override fun load(scope: MemoryScopeArg): MemoryLoad = MemoryLoad.Unavailable
        override fun write(scope: MemoryScopeArg, content: String, expectedRevision: String): MemoryCas = MemoryCas.Unavailable
    }

    @Test
    fun personaPrompt_pointsAtTheToolsThatExist() {
        val memory = AgentMemoryContext(
            enabled = true,
            revision = "r".repeat(64),
            byteSize = 20,
            coreContent = "# 核心记忆\n两人在钟楼相识",
            coreTruncated = false,
            headingIndex = "# 核心记忆",
            coreBudgetChars = 4_000,
        )
        val persona = RoleplayRunContext("fixture", CharacterCardCodec.create("林舟"), "旅伴", "", memory = memory)
            .personaMessage().getString("content")
        assertFalse(persona.contains("character_memory_write"))
        assertFalse(persona.contains("character_memory_get"))
        assertTrue(persona.contains("memory_write"))
        assertTrue(persona.contains("memory_read（scope=character）"))
        assertTrue(persona.contains("不能写入现实 MEMORY.md"))
    }

    @Test
    fun approvalCards_inRoleplayTalkAboutTheCharactersStoryMemory() {
        val tool = MemoryWriteTool(unused)
        val clear = tool.approvalPreview(MemoryWriteInput(MemoryScopeArg.CHARACTER, MemoryWriteMode.CLEAR, null, null, "rev"))
        assertTrue(clear.title.contains("剧情记忆"))
        assertFalse(clear.title.contains("长期记忆") || clear.detail.contains("长期记忆"))
        assertTrue(clear.detail.contains("你的现实记忆不受影响"))
        val append = tool.approvalPreview(MemoryWriteInput(MemoryScopeArg.CHARACTER, MemoryWriteMode.APPEND, "在钟楼重逢", null, null))
        assertTrue(append.title.contains("剧情记忆"))

        val real = tool.approvalPreview(MemoryWriteInput(MemoryScopeArg.USER, MemoryWriteMode.CLEAR, null, null, "rev"))
        assertTrue(real.title.contains("长期记忆"))
    }

    @Test
    fun description_saysRoleplayWritesStoryMemoryNotRealMemory() {
        val summary = MemoryWriteTool(unused).summary
        assertTrue(summary.contains("角色会话写本角色的剧情记忆，不写现实记忆"))
    }
}
