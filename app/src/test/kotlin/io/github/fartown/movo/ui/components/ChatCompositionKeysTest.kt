package io.github.fartown.movo.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatCompositionKeysTest {
    @Test
    fun draftThatBecomesAConversationKeepsItsComposition() {
        // 规范 9.4：首页（草稿）发出第一句时不重建聊天舞台，飞行动画与首页淡出才能播完。
        val keys = ChatCompositionKeys()
        val draft = keys.keyFor(null, adoptedFromDraft = false)
        assertEquals(draft, keys.keyFor("c1", adoptedFromDraft = true))
        assertEquals(draft, keys.keyFor("c1", adoptedFromDraft = true))
    }

    @Test
    fun switchingConversationsStillRebuilds() {
        val keys = ChatCompositionKeys()
        val draft = keys.keyFor(null, adoptedFromDraft = false)
        // 从草稿在侧边栏选一条已有会话：不是由草稿变成的，要重建。
        assertNotEquals(draft, keys.keyFor("old", adoptedFromDraft = false))
    }

    @Test
    fun leavingTheAdoptedConversationGetsFreshKeys() {
        val keys = ChatCompositionKeys()
        val firstDraft = keys.keyFor(null, adoptedFromDraft = false)
        keys.keyFor("c1", adoptedFromDraft = true)
        // 新建对话：新的草稿组合，不能复用 c1 的舞台。
        val secondDraft = keys.keyFor(null, adoptedFromDraft = false)
        assertNotEquals(firstDraft, secondDraft)
        // 回到 c1：按会话 id 重建，不再当作草稿（每个会话只沿用一次）。
        assertEquals(chatConversationCompositionKey("c1"), keys.keyFor("c1", adoptedFromDraft = true))
    }
}
