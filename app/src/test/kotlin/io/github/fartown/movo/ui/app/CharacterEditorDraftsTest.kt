package io.github.fartown.movo.ui.app

import io.github.fartown.movo.agent.roleplay.CharacterCardCodec
import io.github.fartown.movo.agent.roleplay.CharacterProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CharacterEditorDraftsTest {
    @Test
    fun newCharacterDraftSurvivesLeaveAndReentryUntilExplicitDiscard() {
        val drafts = CharacterEditorDrafts()
        val card = CharacterCardCodec.create("新角色").withEdits(description = "外貌和经历", firstMessage = "你好")
        drafts.remember(null, CharacterEditorSession(null, card, "MovoAuditRoleDraft"))

        assertEquals("MovoAuditRoleDraft", drafts.find(null)?.name)
        assertEquals("外貌和经历", drafts.find(null)?.card?.description)
        assertEquals("你好", drafts.find(null)?.card?.firstMessage)

        drafts.discard(null)
        assertNull(drafts.find(null))
    }

    @Test
    fun editingAnotherCharacterDoesNotReplaceOriginalOrFirstDraft() {
        val drafts = CharacterEditorDrafts()
        val firstOriginal = profile("first")
        val secondOriginal = profile("second")
        drafts.remember("first", CharacterEditorSession(firstOriginal, firstOriginal.card.withEdits(scenario = "未保存的故事"), "修改名称"))
        drafts.remember("second", CharacterEditorSession(secondOriginal, secondOriginal.card, secondOriginal.card.name))

        assertEquals("first", drafts.find("first")?.original?.id)
        assertEquals("未保存的故事", drafts.find("first")?.card?.scenario)
        assertEquals("修改名称", drafts.find("first")?.name)
        assertEquals("second", drafts.find("second")?.original?.id)

        drafts.discard("second")
        assertEquals("未保存的故事", drafts.find("first")?.card?.scenario)
        assertNull(drafts.find("second"))
    }

    private fun profile(id: String) = CharacterProfile(
        id = id,
        card = CharacterCardCodec.create(id),
        createdAt = 1L,
        updatedAt = 1L,
    )
}
