package io.github.fartown.movo.ui.screens.chat

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLatestPositionRequestsTest {
    @Test fun sameConversationRequestWaitsForItsOwnPositioning() = runBlocking {
        val ticket = ChatLatestPositionRequests.request("long-conversation")
        val waiting = async { ticket.awaitReady() }
        assertFalse(waiting.isCompleted)
        ChatLatestPositionRequests.complete(ticket)
        waiting.await()
        assertTrue(waiting.isCompleted)
        assertTrue(ChatLatestPositionRequests.pending == null)
    }

    @Test fun olderRequestCannotAcknowledgeAReplacement() = runBlocking {
        val older = ChatLatestPositionRequests.request("old")
        val current = ChatLatestPositionRequests.request("current")
        ChatLatestPositionRequests.complete(older)
        assertTrue(ChatLatestPositionRequests.pending === current)
        older.cancel()
        assertTrue(ChatLatestPositionRequests.pending === current)
        ChatLatestPositionRequests.complete(current)
        current.awaitReady()
        assertTrue(ChatLatestPositionRequests.pending == null)
    }
}
