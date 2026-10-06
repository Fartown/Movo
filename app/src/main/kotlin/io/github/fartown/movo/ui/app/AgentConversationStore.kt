package io.github.fartown.movo.ui.app

import android.content.Context
import kotlinx.coroutines.async
import io.github.fartown.movo.agent.model.AgentConversationCodec
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.roleplay.RoleplayBinding
import io.github.fartown.movo.agent.roleplay.RoleplayMessageState
import io.github.fartown.movo.data.db.ConversationContextCheckpointEntity
import io.github.fartown.movo.data.db.ConversationEntity
import io.github.fartown.movo.data.db.ConversationMetadata
import io.github.fartown.movo.data.db.ConversationMessageEntity
import io.github.fartown.movo.data.db.ConversationStateEntity
import io.github.fartown.movo.data.db.MovoDatabase
import io.github.fartown.movo.data.model.ReasoningEffort
import io.github.fartown.movo.ui.model.AgentChatHomeUiState
import io.github.fartown.movo.ui.model.AgentChatMessageUi
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.MonitorEventKindUi
import io.github.fartown.movo.ui.model.MonitorEventMessageUi
import io.github.fartown.movo.ui.model.SuggestionChipsMessageUi
import io.github.fartown.movo.ui.model.ThinkingMessageUi
import io.github.fartown.movo.ui.model.SystemNoticeCode
import io.github.fartown.movo.ui.model.SystemNoticeMessageUi
import io.github.fartown.movo.ui.model.TokenUsageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.ToolSummaryMessageUi
import io.github.fartown.movo.ui.model.UserMessageUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray

internal object AgentConversationStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    data class Snapshot(
        val selectedConversationId: String?,
        val conversationsById: Map<String, AgentChatHomeUiState>,
        val titles: Map<String, String>,
        val updatedAt: Map<String, Long>,
    )

    private val saveMutex = Mutex()

    /** 一次保存请求：登记时拍下的整份会话状态。 */
    class SaveRequest internal constructor(
        internal val generation: Long,
        internal val selectedConversationId: String?,
        internal val conversationsById: Map<String, AgentChatHomeUiState>,
        internal val titles: Map<String, String>,
        internal val updatedAt: Map<String, Long>,
    )

    private val requestLock = Any()
    private var requestGeneration = 0L
    private var latestRequest: SaveRequest? = null
    /** 已写进数据库的最新一份（由 [saveMutex] 保护）。 */
    private var writtenGeneration = 0L

    /** [preload] 在后台读好的一份，只给下一次 [load] 用一次；写过库就作废。 */
    @Volatile private var preloaded: kotlinx.coroutines.Deferred<Snapshot>? = null

    /**
     * 提前在后台读出全部会话。电视进程常驻，开机就预读，第一次打开界面、第一次唤醒都不用在主线程上等数据库
     * （电视上整库解码要 1–2 s）。
     */
    fun preload(context: Context, scope: kotlinx.coroutines.CoroutineScope): kotlinx.coroutines.Deferred<Snapshot> =
        synchronized(requestLock) {
            preloaded ?: scope.async(Dispatchers.IO) { loadSnapshot(context.applicationContext) }.also { preloaded = it }
        }

    fun load(context: Context): Snapshot {
        val pending = synchronized(requestLock) { preloaded.also { preloaded = null } }
        return runBlocking(Dispatchers.IO) {
            pending?.let { runCatching { it.await() }.getOrNull() } ?: loadSnapshot(context.applicationContext)
        }
    }

    suspend fun save(
        context: Context,
        selectedConversationId: String?,
        conversationsById: Map<String, AgentChatHomeUiState>,
        titles: Map<String, String>,
        updatedAt: Map<String, Long>,
    ) = commit(context, request(selectedConversationId, conversationsById, titles, updatedAt))

    /**
     * 登记一份要保存的状态（调用方按时间先后登记，后登记的就是更新的状态）。真正写入在 [commit]。
     */
    fun request(
        selectedConversationId: String?,
        conversationsById: Map<String, AgentChatHomeUiState>,
        titles: Map<String, String>,
        updatedAt: Map<String, Long>,
    ): SaveRequest = synchronized(requestLock) {
        SaveRequest(++requestGeneration, selectedConversationId, conversationsById, titles, updatedAt)
            .also { latestRequest = it }
    }

    /**
     * 保证 [request] 这一份（或比它更新的一份）已经写进数据库后返回。
     * 合并写入：排着队的多次保存只写当时最新登记的那份，其余直接返回——后台监听每来一个事件、
     * 每段流式输出都要保存时，不再每次把所有会话整库重写一遍。
     */
    suspend fun commit(context: Context, request: SaveRequest) {
        synchronized(requestLock) { preloaded = null }
        val appContext = context.applicationContext
        saveMutex.withLock {
            if (writtenGeneration >= request.generation) return
            val latest = synchronized(requestLock) { latestRequest }
                ?.takeIf { it.generation >= request.generation }
                ?: request
            write(appContext, latest)
            writtenGeneration = latest.generation
        }
    }

    private suspend fun write(appContext: Context, request: SaveRequest) {
        val selectedConversationId = request.selectedConversationId
        val conversationsById = request.conversationsById
        val titles = request.titles
        val updatedAt = request.updatedAt
        run {
            withContext(Dispatchers.IO) {
                val sorted = conversationsById.entries
                    .sortedByDescending { (id, _) -> updatedAt[id] ?: 0L }

                val storedIds = sorted.mapTo(mutableSetOf()) { it.key }
                val selected = selectedConversationId
                    ?.takeIf { it in storedIds }
                    ?: sorted.firstOrNull()?.key
                val now = System.currentTimeMillis()
                val conversations = sorted.map { (id, state) ->
                    ConversationEntity(
                        id = id,
                        title = titles[id].orEmpty(),
                        thinkingEnabled = state.reasoningEffort.enablesReasoning,
                        reasoningEffort = state.reasoningEffort.wireValue,
                        appliedRuntimeRunIdsJson = json.encodeToString(state.appliedRuntimeRunIds),
                        roleplayJson = state.roleplay?.let { json.encodeToString(it) }.orEmpty(),
                        revisionsJson = if (state.roleplay == null) "" else json.encodeToString(state.roleplayMessages),
                        createdAt = updatedAt[id] ?: now,
                        updatedAt = updatedAt[id] ?: now,
                    )
                }
                val messages = sorted.flatMap { (conversationId, state) ->
                    state.messages
                        .mapIndexedNotNull { index, message ->
                            message.toConversationRow(conversationId, index.toLong())
                        }
                }
                val contextCheckpoints = sorted.map { (conversationId, state) ->
                    ConversationContextCheckpointEntity(
                        conversationId = conversationId,
                        historyJson = AgentConversationCodec.encodeConversationCheckpoint(state.history),
                        journalJson = AgentConversationCodec.encodeTranscriptForStorage(state.journal.ifEmpty { state.history }),
                    )
                }
                MovoDatabase.get(appContext)
                    .conversationDao()
                    .replaceAll(
                        conversations = conversations,
                        messages = messages,
                        contextCheckpoints = contextCheckpoints,
                        state = selected?.let { ConversationStateEntity(selectedConversationId = it) },
                    )
            }
        }
    }

    private suspend fun loadSnapshot(context: Context): Snapshot {
        val dao = MovoDatabase.get(context).conversationDao()
        val conversations = dao.conversations()
        if (conversations.isEmpty()) {
            return Snapshot(
                selectedConversationId = null,
                conversationsById = emptyMap(),
                titles = emptyMap(),
                updatedAt = emptyMap(),
            )
        }

        val messagesByConversation = conversations.associate { conversation ->
            conversation.id to buildList {
                var offset = 0
                while (true) {
                    val page = dao.messagesPage(
                        conversationId = conversation.id,
                        limit = MESSAGE_LOAD_PAGE_SIZE,
                        offset = offset,
                    )
                    addAll(page)
                    if (page.size < MESSAGE_LOAD_PAGE_SIZE) break
                    offset += page.size
                }
            }
        }
        val states = linkedMapOf<String, AgentChatHomeUiState>()
        val titles = mutableMapOf<String, String>()
        val updatedAt = mutableMapOf<String, Long>()

        conversations.forEach { conversation ->
            val checkpoint = dao.contextCheckpoint(conversation.id)
            states[conversation.id] = AgentChatHomeUiState(
                roleplay = conversation.roleplayJson.takeIf(String::isNotBlank)?.let { json.decodeFromString<RoleplayBinding>(it) },
                roleplayMessages = conversation.revisionsJson.takeIf(String::isNotBlank)?.let {
                    json.decodeFromString<RoleplayMessageState>(it)
                } ?: RoleplayMessageState(),
                journal = AgentConversationCodec.decodeTranscript(checkpoint?.journalJson),
                messages = messagesByConversation[conversation.id]
                    .orEmpty()
                    .sortedBy { it.sortIndex }
                    .mapNotNull { it.toChatMessage() },
                history = AgentConversationCodec.decodeTranscript(
                    checkpoint?.historyJson
                )
                    .ifEmpty {
                        messagesByConversation[conversation.id]
                            .orEmpty()
                            .sortedBy { it.sortIndex }
                            .toLegacyHistory()
                    },
                appliedRuntimeRunIds = conversation.appliedRuntimeRunIdsJson.toStringList(),
                input = "",
                isStreaming = false,
                thinkingEnabled = conversation.reasoningEffortValue.enablesReasoning,
                reasoningEffort = conversation.reasoningEffortValue,
            ).let(RoleplayConversationReducer::decorate)
            titles[conversation.id] = conversation.title.takeUnless { it == LEGACY_UNNAMED_CONVERSATION_TITLE }.orEmpty()
            updatedAt[conversation.id] = conversation.updatedAt
        }

        val selected = dao.state()?.selectedConversationId
            ?.takeIf { it in states }
            ?: states.keys.first()

        return Snapshot(
            selectedConversationId = selected,
            conversationsById = states,
            titles = titles,
            updatedAt = updatedAt,
        )
    }

    private val ConversationMetadata.reasoningEffortValue: ReasoningEffort
        get() = ReasoningEffort.fromWireValue(reasoningEffort) ?: ReasoningEffort.DEFAULT

    private const val MESSAGE_LOAD_PAGE_SIZE = 128
}
