package io.github.fartown.movo.agent.runtime

import android.content.Context
import io.github.fartown.movo.agent.model.AgentContextSnapshot
import io.github.fartown.movo.agent.model.AgentConversationCodec
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.data.db.MovoDatabase
import io.github.fartown.movo.ui.app.ConversationRepository
import io.github.fartown.movo.ui.model.AgentChatHomeUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 在途登记 + 对话里的进行中记录（docs/solutions/conversation-storage 第 2 步）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRunCheckpointStoreTest {
    private lateinit var context: Context
    private lateinit var repository: ConversationRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        MovoDatabase.closeForTests()
        context.deleteDatabase("movo.db")
        repository = ConversationRepository.get(context)
        runBlocking {
            repository.saveConversation("conversation-1", AgentChatHomeUiState(
                messages = emptyList(), input = "", isStreaming = false, thinkingEnabled = false,
            ), "", updatedAt = 1).await()
        }
    }

    @Test
    fun runLogsCarryTheTranscriptAndSnapshotIntoRecoveryAndAreClearedWithTheRun() = runBlocking {
        val request = request("run-1")
        assertTrue(AgentRunCheckpointStore.start(context, request))
        val first = AgentModelClient.ConversationMessage(role = "assistant", content = "正在打开哔哩哔哩")
        val second = AgentModelClient.ConversationMessage(role = "assistant", content = "已打开")
        // 每轮只追加新的几条，不整份重写。
        repository.syncRunTranscript("conversation-1", request.runId, emptyList(), listOf(first))
        repository.syncRunTranscript("conversation-1", request.runId, listOf(first), listOf(first, second))
        val summary = AgentModelClient.ConversationMessage("assistant", "已完成前序操作。",
            contextSummary = true, compactedUserTurns = 1, summaryThroughUserTurn = 1)
        val snapshot = AgentContextSnapshot(operationId = request.runId, messages = listOf(summary),
            coveredUserTurns = 1, consumedUserTurns = 1)
        repository.saveRunSnapshot("conversation-1", request.runId, snapshot.encode())
        MovoDatabase.closeForTests()

        val restored = AgentRunCheckpointStore.list(context).single()
        assertEquals(listOf(first, second), restored.transcript)
        assertEquals(snapshot, restored.contextSnapshot)
        // 界面行在执行中已经写进对话：不再另存一份事件日志。
        assertTrue(restored.events.isEmpty())

        AgentRunCheckpointStore.remove(context, request.runId)
        assertTrue(AgentRunCheckpointStore.list(context).isEmpty())
        assertEquals(emptyList<AgentModelClient.ConversationMessage>(), repository.runTranscript("conversation-1", request.runId))
        assertNull(repository.runSnapshot("conversation-1", request.runId))
    }

    @Test
    fun checkpointsRemainVisibleToAReplacementUiInTheSameProcess() {
        val request = request("run-2")
        assertTrue(AgentRunCheckpointStore.start(context = context, request = request, ownerInstanceId = "old-process"))

        val restored = AgentRunCheckpointStore.list(context).single()
        assertEquals("run-2", restored.runId)
        assertEquals("old-process", restored.ownerInstanceId)
    }

    @Test
    fun aRunStillInFlightBeforeTheUpgradeKeepsItsOldTranscript() = runBlocking {
        val request = request("run-legacy")
        assertTrue(AgentRunCheckpointStore.start(context, request))
        val legacy = AgentModelClient.ConversationMessage(role = "assistant", content = "升级前的记录")
        MovoDatabase.get(context).runtimeRunDao()
            .updateTranscript(request.runId, AgentConversationCodec.encodeTranscriptForStorage(listOf(legacy)))

        assertEquals(listOf(legacy), AgentRunCheckpointStore.list(context).single().transcript)
    }

    @Test
    fun onlyRunsStartedByTheAppAreRegistered() {
        val external = request("run-external").copy(handoff = AgentRuntimeWire.EntryHandoff(
            id = "run-external", source = "xiaoai", payload = "conversation-1",
        ))

        assertEquals(false, AgentRunCheckpointStore.start(context, external))
        assertTrue(AgentRunCheckpointStore.list(context).isEmpty())
    }

    private fun request(runId: String): AgentRuntimeWire.RunRequest =
        AgentRuntimeWire.RunRequest(
            runId = runId,
            prompt = "测试",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.com/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
            ),
            images = emptyList(),
            handoff = AgentRuntimeWire.EntryHandoff(
                id = runId,
                source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                payload = "conversation-1",
            ),
        )
}
