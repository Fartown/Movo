package io.github.fartown.movo.ui.components

import io.github.fartown.movo.agent.model.InteractionMode
import io.github.fartown.movo.agent.model.OpenAiResponsesProvider
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对话模式开着联网、这一轮只搜了网：摘要条写「已搜索 N 次」（定稿 25-12）。 */
class ChatWebSearchSummaryTest {
    private fun step(n: Int, name: String) = ToolActivityMessageUi(
        id = "run-tool-1-ws$n", toolName = name, status = ToolActivityStatusUi.Success,
        argumentsSummary = "", startedAtMillis = 1_000L + n * 100, finishedAtMillis = 1_050L + n * 100,
    )

    private fun summaryOf(user: UserMessageUi, vararg steps: ToolActivityMessageUi): AgentTimelineEntry.TurnSummary {
        val messages = listOf(user) + steps + AgentMessageUi("assistant-run-1-1", "今天杭州多云，18～24 度。")
        return messages.toTimelineEntries(isStreaming = false).filterIsInstance<AgentTimelineEntry.TurnSummary>().single()
    }

    private val chatUser = UserMessageUi(
        "u1", "今天杭州天气怎么样", interactionMode = InteractionMode.CHAT, webSearch = true,
        runStartedAtMillis = 500L, runFinishedAtMillis = 6_500L,
    )

    @Test
    fun chatTurnThatOnlySearchedIsASearchSummary() {
        val summary = summaryOf(
            chatUser,
            step(1, OpenAiResponsesProvider.WEB_SEARCH_STEP_NAME),
            step(2, OpenAiResponsesProvider.WEB_SEARCH_STEP_NAME),
        )
        assertTrue(summary.searchOnly)
        assertEquals(2, summary.toolCount)
    }

    @Test
    fun agentTurnWithSearchStepsStaysASteps() {
        val summary = summaryOf(
            chatUser.copy(interactionMode = InteractionMode.AGENT, webSearch = false),
            step(1, OpenAiResponsesProvider.WEB_SEARCH_STEP_NAME),
        )
        assertFalse(summary.searchOnly)
    }

    @Test
    fun anyOtherStepMakesItASteps() {
        val summary = summaryOf(chatUser, step(1, OpenAiResponsesProvider.WEB_SEARCH_STEP_NAME), step(2, "device_read"))
        assertFalse(summary.searchOnly)
    }
}
