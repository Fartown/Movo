package io.github.fartown.movo.ui.app

import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.SuggestionChipsMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFollowUpSuggestionsTest {
    private val user = UserMessageUi(id = "user-run-1", content = "查一下我的快递")
    private val tool = ToolActivityMessageUi(
        id = "run-1-tool-1", toolName = "open_app", status = ToolActivityStatusUi.Success, argumentsSummary = "",
    )
    private val supplement = UserMessageUi(id = "user-run-1-supplement-1", content = "顺便看看菜鸟")
    private val answer = AgentMessageUi(id = "assistant-run-1-2", content = "取件码 3-2-1106")

    @Test
    fun targetIsTheRunsFinalAnswerWithTheTurnsUserText() {
        val target = AgentFollowUpSuggestions.target("run-1", listOf(user, tool, supplement, answer))!!
        assertEquals(answer.id, target.answerMessageId)
        assertEquals("查一下我的快递", target.userText)
        assertTrue(target.usedTools)

        assertNull(AgentFollowUpSuggestions.target("run-2", listOf(user, answer)))
        assertNull(AgentFollowUpSuggestions.target("run-1", listOf(user, answer.copy(content = " "))))
        assertNull(AgentFollowUpSuggestions.target("run-1", listOf(user, answer, tool)))
        assertFalse(AgentFollowUpSuggestions.target("run-1", listOf(user, answer))!!.usedTools)
    }

    @Test
    fun attachesOnlyWhileTheAnswerIsStillLast() {
        val attached = AgentFollowUpSuggestions.attach(listOf(user, answer), answer.id, listOf("设置取件提醒"))!!
        assertEquals(SuggestionChipsMessageUi("suggestions-${answer.id}", listOf("设置取件提醒")), attached.last())

        assertNull(AgentFollowUpSuggestions.attach(listOf(user, answer), answer.id, emptyList()))
        val nextTurn = listOf(user, answer, UserMessageUi(id = "user-run-2", content = "再查一个"))
        assertNull(AgentFollowUpSuggestions.attach(nextTurn, answer.id, listOf("设置取件提醒")))
    }

    @Test
    fun newTurnStripsSuggestionsAndOnlyTheTailIsVisible() {
        val chips = SuggestionChipsMessageUi("suggestions-${answer.id}", listOf("设置取件提醒"))
        val next = UserMessageUi(id = "user-run-2", content = "设置取件提醒")
        assertEquals(listOf(user, answer, next), AgentFollowUpSuggestions.strip(listOf(user, answer, chips, next)))

        assertEquals(listOf(user, answer, chips), AgentFollowUpSuggestions.visible(listOf(user, answer, chips), isStreaming = false))
        assertEquals(listOf(user, answer), AgentFollowUpSuggestions.visible(listOf(user, answer, chips), isStreaming = true))
        assertEquals(listOf(user, answer, next), AgentFollowUpSuggestions.visible(listOf(user, answer, chips, next), isStreaming = false))
    }
}
