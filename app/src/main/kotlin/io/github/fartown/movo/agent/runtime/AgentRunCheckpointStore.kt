package io.github.fartown.movo.agent.runtime

import android.content.Context
import io.github.fartown.movo.agent.model.AgentContextSnapshot
import io.github.fartown.movo.agent.model.AgentConversationCodec
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.model.AgentToolBatchRecovery
import io.github.fartown.movo.data.db.MovoDatabase
import io.github.fartown.movo.data.db.RuntimeInFlightRunEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * App 发起的、还没确认完成的 run 的登记（一轮一行）。进程被杀后据此按中断处理。
 * 这一轮产生的模型消息与上下文快照在对话的进行中记录里（ConversationRepository.runTranscript / runSnapshot），
 * 界面行在执行中已经写进对话（docs/solutions/conversation-storage 第 2 步）；
 * [Checkpoint.events] 只剩升级前留下的旧日志。
 */
internal object AgentRunCheckpointStore {
    data class Checkpoint(
        val runId: String,
        val ownerInstanceId: String,
        val handoff: AgentRuntimeWire.EntryHandoff,
        val events: List<AgentEvent>,
        val createdAt: Long,
        val updatedAt: Long,
        val contextSnapshot: AgentContextSnapshot? = null,
        val operation: String = AgentRuntimeWire.OP_CHAT,
        val transcript: List<AgentModelClient.ConversationMessage> = emptyList(),
        val rewriteTargetMessageId: String? = null,
    )

    fun start(
        context: Context,
        request: AgentRuntimeWire.RunRequest,
        ownerInstanceId: String = AgentRuntimeProcessIdentity.id,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val handoff = request.handoff ?: return false
        if (handoff.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return false
        val runId = request.runId.takeIf(String::isNotBlank) ?: return false
        runBlocking(Dispatchers.IO) {
            MovoDatabase.get(context.applicationContext).runtimeRunDao().replaceInFlightRun(
                RuntimeInFlightRunEntity(
                    runId = runId,
                    ownerInstanceId = ownerInstanceId,
                    operation = request.operation,
                    rewriteTargetMessageId = request.rewriteTargetMessageId,
                    handoffId = handoff.id,
                    handoffSource = handoff.source,
                    handoffPayload = handoff.payload,
                    dismissEntrySurface = handoff.dismissEntrySurfaceOnForegroundOperation,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
        return true
    }

    /** 返回所有未确认 run；是否 active 或已完成由恢复协调器结合 Runtime 状态判断。 */
    fun list(context: Context): List<Checkpoint> =
        runBlocking(Dispatchers.IO) {
            MovoDatabase.get(context.applicationContext)
                .runtimeRunDao()
                .inFlightRuns()
                .map { stored ->
                    val conversationId = runCatching { AgentUiHandoffPayload.from(stored.run.handoffPayload).conversationId }
                        .getOrNull().orEmpty()
                    val repository = io.github.fartown.movo.ui.app.ConversationRepository.get(context)
                    // 新的在对话的进行中记录里；升级前在途的还在旧列里。
                    val transcript = conversationId.takeIf(String::isNotBlank)
                        ?.let { repository.runTranscript(it, stored.run.runId) }.orEmpty()
                        .ifEmpty { AgentConversationCodec.decodeTranscript(stored.run.transcriptJson) }
                    val snapshot = conversationId.takeIf(String::isNotBlank)
                        ?.let { repository.runSnapshot(it, stored.run.runId) }
                        ?: stored.run.contextSnapshotJson
                    Checkpoint(
                        runId = stored.run.runId,
                        ownerInstanceId = stored.run.ownerInstanceId,
                        contextSnapshot = AgentContextSnapshot.decode(snapshot),
                        operation = stored.run.operation,
                        rewriteTargetMessageId = stored.run.rewriteTargetMessageId,
                        transcript = AgentToolBatchRecovery.completeInterrupted(transcript),
                        handoff = AgentRuntimeWire.EntryHandoff(
                            id = stored.run.handoffId,
                            source = stored.run.handoffSource,
                            payload = stored.run.handoffPayload,
                            dismissEntrySurfaceOnForegroundOperation =
                                stored.run.dismissEntrySurface,
                        ),
                        events = stored.events
                            .sortedBy { it.sortIndex }
                            .mapNotNull { AgentEventJsonCodec.decode(it.eventJson) },
                        createdAt = stored.run.createdAt,
                        updatedAt = stored.run.updatedAt,
                    )
                }
                .toList()
        }

    /** 这一轮已经处理完（结果已并进对话，或已按中断处理）：删登记和它的进行中记录。 */
    fun remove(context: Context, runId: String) {
        if (runId.isBlank()) return
        io.github.fartown.movo.ui.app.ConversationRepository.get(context).clearRunLogs(runId)
        runBlocking(Dispatchers.IO) {
            MovoDatabase.get(context.applicationContext)
                .runtimeRunDao()
                .deleteInFlightRun(runId)
        }
    }
}
