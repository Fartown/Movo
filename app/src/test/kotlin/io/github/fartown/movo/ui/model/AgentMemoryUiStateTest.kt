package io.github.fartown.movo.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryUiStateTest {
    @Test
    fun unsavedDraftSurvivesPageReloadAndCanStillBeSaved() {
        val typed = "本轮未保存的记忆"
        val editing = AgentMemoryUiState(
            isLoading = false,
            draft = typed,
            savedContent = "",
            draftBytes = typed.toByteArray(Charsets.UTF_8).size,
        )

        val reopened = editing.withLoadedSnapshot(content = "", enabled = true, coreBudgetChars = 8_000)

        assertEquals(typed, reopened.draft)
        assertEquals(typed.toByteArray(Charsets.UTF_8).size, reopened.draftBytes)
        assertTrue(reopened.hasUnsavedChanges)
        assertTrue(reopened.canSave)
    }

    @Test
    fun cleanEditorLoadsLatestRepositoryContent() {
        val previouslySaved = AgentMemoryUiState(
            isLoading = true,
            draft = "旧内容",
            savedContent = "旧内容",
        )

        val reopened = previouslySaved.withLoadedSnapshot(
            content = "更新后的内容",
            enabled = false,
            coreBudgetChars = 4_000,
        )

        assertEquals("更新后的内容", reopened.draft)
        assertEquals(reopened.draft, reopened.savedContent)
        assertFalse(reopened.hasUnsavedChanges)
        assertFalse(reopened.enabled)
        assertEquals(4_000, reopened.coreBudgetChars)
    }
}
