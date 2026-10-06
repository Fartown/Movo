package io.github.fartown.movo.agent.runtime

import android.content.Context
import io.github.fartown.movo.agent.model.AgentContextSnapshot
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.data.db.MovoDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentContextPersistenceTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        MovoDatabase.closeForTests()
        context.deleteDatabase("movo.db")
    }

    @After fun close() {
        AgentRunArchiveStore.remove(context, "context-persist")
        MovoDatabase.closeForTests()
        context.deleteDatabase("movo.db")
    }

    @Test
    fun committedSnapshotSurvivesDatabaseReopenAndOutboxAcknowledgement() {
        val request = AgentRuntimeWire.RunRequest(
            "context-persist", "", AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""),
            emptyList(), handoff = AgentRuntimeWire.EntryHandoff("context-persist", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conversation"),
            operation = AgentRuntimeWire.OP_COMPACT,
        )
        val summary = AgentModelClient.ConversationMessage("assistant", "已完成前序操作。",
            contextSummary = true, compactedUserTurns = 2, summaryThroughUserTurn = 2)
        val snapshot = AgentContextSnapshot(operationId = request.runId, messages = listOf(summary),
            coveredUserTurns = 2, consumedUserTurns = 2)
        val repository = io.github.fartown.movo.ui.app.ConversationRepository.get(context)
        kotlinx.coroutines.runBlocking {
            repository.saveConversation("conversation", io.github.fartown.movo.ui.model.AgentChatHomeUiState(
                messages = emptyList(), input = "", isStreaming = false, thinkingEnabled = false), "", 1).await()
        }
        assertTrue(AgentRunCheckpointStore.start(context, request))
        // 压缩出的快照存在对话的进行中记录里（第 2 步）；界面事件不再另存。
        repository.saveRunSnapshot("conversation", request.runId, snapshot.encode())
        val event = AgentEvent.ContextCompaction("op", "completed", 30_000, 2_000)
        MovoDatabase.closeForTests()
        val checkpoint = AgentRunCheckpointStore.list(context).single()
        assertEquals(snapshot, checkpoint.contextSnapshot)
        assertEquals(AgentRuntimeWire.OP_COMPACT, checkpoint.operation)
        assertTrue(checkpoint.events.isEmpty())
        val result = AgentRuntimeWire.RunResult(request.runId, true, "", contextSnapshot = snapshot,
            operation = AgentRuntimeWire.OP_COMPACT)
        val completed = AgentRuntimeWire.CompletedRun(request.handoff!!, result, System.currentTimeMillis())
        assertTrue(AgentRuntimeResultStore.add(context, completed))
        AgentRunArchiveStore.add(context, AgentRunArchiveStore.ArchivedRun(completed.handoff, listOf(event), result, completed.createdAt))
        MovoDatabase.closeForTests()
        assertEquals(result, AgentRuntimeResultStore.list(context).single().result)
        assertEquals(result, AgentRunArchiveStore.list(context).single().result)
        AgentRuntimeResultStore.remove(context, request.runId)
        assertTrue(AgentRuntimeResultStore.list(context).isEmpty())
        assertTrue(AgentRunCheckpointStore.list(context).isEmpty())
        // 确认结果时，这一轮的进行中记录一起删掉。
        assertEquals(null, kotlinx.coroutines.runBlocking { repository.runSnapshot("conversation", request.runId) })
        assertFalse(AgentRuntimeResultStore.add(context, completed))
    }
}
