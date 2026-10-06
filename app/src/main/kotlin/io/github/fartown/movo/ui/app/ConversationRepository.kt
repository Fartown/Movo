package io.github.fartown.movo.ui.app

import android.content.Context
import io.github.fartown.movo.agent.model.AgentConversationCodec
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.roleplay.RoleplayBinding
import io.github.fartown.movo.agent.roleplay.RoleplayMessageState
import io.github.fartown.movo.data.db.ConversationDao
import io.github.fartown.movo.data.db.ConversationEntity
import io.github.fartown.movo.data.db.ConversationModelMessageEntity
import io.github.fartown.movo.data.db.ConversationStateEntity
import io.github.fartown.movo.data.db.MovoDatabase
import io.github.fartown.movo.data.model.ReasoningEffort
import io.github.fartown.movo.ui.model.AgentChatHomeUiState
import io.github.fartown.movo.ui.model.AgentChatMessageUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 对话存储（docs/solutions/conversation-storage）：数据库是唯一的真身。
 *
 * - 写：一件事写一行（或一个对话的一段），所有写按调用顺序在一条写线程上依次执行，不挡调用方；
 *   需要“先落库再继续”时 await 返回的 [Deferred]。
 * - 读：用到什么读什么。列表只读对话目录和每个对话的最后一行，打开一个对话才读它的全部内容。
 */
internal class ConversationRepository(
    private val dao: ConversationDao,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Summary(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val characterName: String?,
        val lastMessage: AgentChatMessageUi?,
    )

    data class Loaded(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val state: AgentChatHomeUiState,
    )

    private class Write(val block: suspend () -> Unit) {
        val done = CompletableDeferred<Unit>()
    }

    private val writes = Channel<Write>(Channel.UNLIMITED)

    // 以下缓存只在写线程上读写：每个对话已落库的排序键、每段模型消息已落库的条数。写失败就清掉，下次从库里重读。
    private val sortKeys = HashMap<String, MutableMap<String, Long>>()
    private val modelCounts = HashMap<String, Int>()

    init {
        scope.launch(Dispatchers.IO) {
            for (write in writes) {
                try {
                    write.block()
                    write.done.complete(Unit)
                } catch (cancelled: CancellationException) {
                    write.done.completeExceptionally(cancelled)
                    throw cancelled
                } catch (failure: Throwable) {
                    sortKeys.clear()
                    modelCounts.clear()
                    write.done.completeExceptionally(failure)
                }
            }
        }
    }

    private fun enqueue(block: suspend () -> Unit): Deferred<Unit> =
        Write(block).also { check(writes.trySend(it).isSuccess) { "对话写入队列已关闭" } }.done

    /** 等前面排着的写全部完成。 */
    suspend fun flush() = enqueue {}.await()

    // —— 读 ——

    suspend fun summaries(limit: Int, offset: Int = 0): List<Summary> {
        val rows = dao.conversationsPage(limit, offset)
        if (rows.isEmpty()) return emptyList()
        val last = dao.lastMessages(rows.map { it.id }).associateBy { it.conversationId }
        return rows.map { row ->
            Summary(
                id = row.id,
                title = row.title.takeUnless { it == LEGACY_UNNAMED_CONVERSATION_TITLE }.orEmpty(),
                updatedAt = row.updatedAt,
                characterName = row.roleplayJson.takeIf(String::isNotBlank)
                    ?.let { runCatching { json.decodeFromString<RoleplayBinding>(it).characterName }.getOrNull() },
                lastMessage = last[row.id]?.toChatMessage(),
            )
        }
    }

    suspend fun exists(conversationId: String): Boolean = dao.conversationExists(conversationId)

    suspend fun selectedConversationId(): String? = dao.state()?.selectedConversationId

    /** 一个对话的全部内容。模型历史还没拆成行的旧数据（v24 迁移前、或迁移时解析失败）退回旧检查点，再退回按显示内容重建。 */
    suspend fun load(conversationId: String): Loaded? {
        val entity = dao.conversationEntity(conversationId) ?: return null
        val rows = dao.messagesOf(conversationId)
        val storedHistory = modelLog(conversationId, ConversationModelMessageEntity.LOG_HISTORY)
        val storedJournal = modelLog(conversationId, ConversationModelMessageEntity.LOG_JOURNAL)
        val legacy = if (storedHistory == null || storedJournal == null) dao.contextCheckpoint(conversationId) else null
        val history = storedHistory
            ?: AgentConversationCodec.decodeTranscript(legacy?.historyJson).ifEmpty { rows.toLegacyHistory() }
        val journal = storedJournal
            ?: AgentConversationCodec.decodeTranscript(legacy?.journalJson).ifEmpty { history }
        val effort = ReasoningEffort.fromWireValue(entity.reasoningEffort) ?: ReasoningEffort.DEFAULT
        val state = AgentChatHomeUiState(
            roleplay = entity.roleplayJson.takeIf(String::isNotBlank)?.let { json.decodeFromString<RoleplayBinding>(it) },
            roleplayMessages = entity.revisionsJson.takeIf(String::isNotBlank)
                ?.let { json.decodeFromString<RoleplayMessageState>(it) } ?: RoleplayMessageState(),
            journal = journal,
            messages = rows.mapNotNull { it.toChatMessage() },
            history = history,
            appliedRuntimeRunIds = entity.appliedRuntimeRunIdsJson.toStringList(),
            input = "",
            isStreaming = false,
            thinkingEnabled = effort.enablesReasoning,
            reasoningEffort = effort,
        ).let(RoleplayConversationReducer::decorate)
        return Loaded(
            id = conversationId,
            title = entity.title.takeUnless { it == LEGACY_UNNAMED_CONVERSATION_TITLE }.orEmpty(),
            updatedAt = entity.updatedAt,
            state = state,
        )
    }

    /** 这一段还没有任何行时返回 null（交给调用方退回旧数据）。 */
    private suspend fun modelLog(conversationId: String, log: String): List<AgentModelClient.ConversationMessage>? {
        if (dao.modelMessageCount(conversationId, log) == 0) return null
        // 解析不了的单条（旧数据里的裸字符串等）跳过，不让整个对话打不开。
        return dao.modelLog(conversationId, log).mapNotNull { runCatching { AgentConversationCodec.decodeStoredMessage(it) }.getOrNull() }
    }

    /** 标题、正文、工具名与摘要里出现 [query] 的对话 id。 */
    suspend fun search(query: String): Set<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptySet()
        val escaped = trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return dao.searchConversationIds("%$escaped%").toSet()
    }

    // —— 写 ——

    /** 对话目录这一行：标题、更新时间、推理强度、已应用的运行、角色绑定。创建时间只在第一次写入时定下。 */
    fun saveConversation(conversationId: String, state: AgentChatHomeUiState, title: String, updatedAt: Long): Deferred<Unit> {
        val row = ConversationEntity(
            id = conversationId,
            title = title,
            thinkingEnabled = state.reasoningEffort.enablesReasoning,
            reasoningEffort = state.reasoningEffort.wireValue,
            appliedRuntimeRunIdsJson = json.encodeToString(state.appliedRuntimeRunIds),
            roleplayJson = state.roleplay?.let { json.encodeToString(it) }.orEmpty(),
            revisionsJson = if (state.roleplay == null) "" else json.encodeToString(state.roleplayMessages),
            createdAt = updatedAt,
            updatedAt = updatedAt,
        )
        return enqueue {
            val createdAt = dao.conversationEntityRow(conversationId)?.createdAt ?: row.createdAt
            dao.insertConversations(listOf(row.copy(createdAt = createdAt)))
        }
    }

    /**
     * 这个对话的界面消息从 [before] 变成 [after]（都是完整列表）：删掉没了的行，只写新增和内容变了的行
     * （按对象引用判断，界面状态不可变，变了就是新对象）。排序键按间隔分配，中间插入不挪后面的行；
     * 次序被打乱或没有空隙时才给这个对话重新编号。
     */
    fun syncMessages(conversationId: String, before: List<AgentChatMessageUi>, after: List<AgentChatMessageUi>): Deferred<Unit> =
        enqueue {
            val keys = sortKeys.getOrPut(conversationId) {
                dao.messageSortKeys(conversationId).associateTo(HashMap()) { it.id to it.sortIndex }
            }
            val previous = before.associateBy { it.id }
            val persisted = after.filter { it.toConversationRow(conversationId, 0) != null }
            val ids = persisted.mapTo(HashSet()) { it.id }
            val removed = keys.keys.filter { it !in ids }
            if (removed.isNotEmpty()) {
                removed.chunked(500).forEach { dao.deleteMessageRows(it) }
                removed.forEach(keys::remove)
            }
            val targets = SortKeys.assign(persisted.map { keys[it.id] })
            val renumbered = targets.renumbered
            if (renumbered) {
                // 先挪到负数临时键，避开 (conversation_id, sort_index) 唯一索引的中间冲突。
                persisted.forEachIndexed { index, message ->
                    if (keys[message.id] != null) dao.updateMessageSortIndex(message.id, -(index + 1).toLong())
                }
            }
            val rows = persisted.mapIndexedNotNull { index, message ->
                val key = targets.keys[index]
                val changed = renumbered || keys[message.id] != key || previous[message.id] !== message
                if (changed) message.toConversationRow(conversationId, key) else null
            }
            if (rows.isNotEmpty()) dao.insertMessages(rows)
            persisted.forEachIndexed { index, message -> keys[message.id] = targets.keys[index] }
        }

    /**
     * 一段模型消息从 [before] 变成 [after]：能接上就只追加新的几条（每轮执行的常态），
     * 接不上（压缩、删轮、编辑、重新生成）就只重写这一个对话的这一段。
     */
    fun syncModelLog(
        conversationId: String,
        log: String,
        before: List<AgentModelClient.ConversationMessage>,
        after: List<AgentModelClient.ConversationMessage>,
        runId: String? = null,
    ): Deferred<Unit> = enqueue {
        val cacheKey = "$conversationId/$log"
        val stored = modelCounts[cacheKey] ?: dao.modelMessageCount(conversationId, log)
        val appendable = stored == before.size && after.size >= before.size &&
            before.indices.all { before[it] === after[it] || before[it] == after[it] }
        if (appendable) {
            val added = after.subList(before.size, after.size)
            if (added.isNotEmpty()) {
                dao.appendModelMessages(conversationId, log, stored, runId, added.map(AgentConversationCodec::encodeMessageForStorage))
            }
        } else {
            dao.replaceModelLog(conversationId, log, after.map(AgentConversationCodec::encodeMessageForStorage))
        }
        modelCounts[cacheKey] = after.size
    }

    /** 删掉这个对话：消息、模型消息、分块随外键与触发器一起删。 */
    fun deleteConversation(conversationId: String): Deferred<Unit> = enqueue {
        dao.deleteConversationRow(conversationId)
        sortKeys.remove(conversationId)
        modelCounts.keys.removeAll { it.startsWith("$conversationId/") }
    }

    fun select(conversationId: String?): Deferred<Unit> = enqueue {
        if (conversationId == null) dao.deleteState() else dao.insertState(ConversationStateEntity(selectedConversationId = conversationId))
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        @Volatile private var instance: ConversationRepository? = null

        fun get(context: Context): ConversationRepository = instance ?: synchronized(this) {
            instance ?: ConversationRepository(
                MovoDatabase.get(context.applicationContext).conversationDao(),
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ).also { instance = it }
        }
    }
}

/** 界面消息的排序键：按间隔分配，追加取最大值 + [GAP]，中间插入取前后两行之间均分的值。 */
internal object SortKeys {
    const val GAP = 1024L

    class Assignment(val keys: List<Long>, val renumbered: Boolean)

    /** [existing] 是按新次序排好的每一行已有的键（新行为 null）。已有的键不再递增、或插入处没有空隙时整段重新编号。 */
    fun assign(existing: List<Long?>): Assignment {
        val kept = existing.filterNotNull()
        if (kept.zipWithNext().any { (a, b) -> a >= b }) return renumber(existing.size)
        val keys = LongArray(existing.size)
        var index = 0
        var lower = 0L
        while (index < existing.size) {
            val key = existing[index]
            if (key != null) {
                keys[index] = key
                lower = key
                index++
                continue
            }
            var end = index
            while (end < existing.size && existing[end] == null) end++
            val upper = existing.getOrNull(end)
            val count = end - index
            if (upper == null) {
                for (offset in 0 until count) keys[index + offset] = lower + GAP * (offset + 1)
            } else {
                val step = (upper - lower) / (count + 1)
                if (step < 1) return renumber(existing.size)
                for (offset in 0 until count) keys[index + offset] = lower + step * (offset + 1)
            }
            index = end
        }
        return Assignment(keys.toList(), renumbered = false)
    }

    private fun renumber(size: Int) = Assignment(List(size) { (it + 1) * GAP }, renumbered = true)
}
