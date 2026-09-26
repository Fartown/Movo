package io.github.fartown.movo.ui.components

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AgentConversationDraftStoreTest {
    @Test
    fun anotherHostRetainsUnsavedTextAndSelectionOfTheSameConversation() {
        val drafts = AgentConversationDraftStore()
        val app = drafts.get("conversation", "initial")
        app.edit { replace(0, length, "unsent draft"); selection = TextRange(2, 7) }

        val sheet = drafts.get("conversation", "stale persisted input")

        assertSame(app, sheet)
        assertEquals("unsent draft", sheet.text.toString())
        assertEquals(TextRange(2, 7), sheet.selection)
        sheet.edit { replace(2, 7, "edited") }
        assertEquals(sheet.text.toString(), app.text.toString())
    }

    @Test
    fun switchingConversationsKeepsEachDraftSeparate() {
        val drafts = AgentConversationDraftStore()
        val first = drafts.get("one", "first")
        val second = drafts.get("two", "second")
        first.edit { selection = TextRange(1) }

        assertNotSame(first, second)
        assertEquals("first", drafts.get("one").text.toString())
        assertEquals(TextRange(1), drafts.get("one").selection)
        assertEquals("second", second.text.toString())
    }

    @Test
    fun deletingAConversationOrStartingANewDraftCannotResurrectOldInput() {
        val drafts = AgentConversationDraftStore()
        drafts.get("deleted", "private old draft")
        val mountedNewConversation = drafts.get(null, "abandoned new chat")
        drafts.get("kept", "retained")
        drafts.remove("deleted")
        drafts.remove(null)

        assertEquals("", drafts.get("deleted").text.toString())
        assertEquals("", drafts.get(null).text.toString())
        assertEquals("", mountedNewConversation.text.toString())
        assertEquals("retained", drafts.get("kept").text.toString())
    }
}
