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
                            message.toEntityOrNull(conversationId, index)
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
                    .mapNotNull { it.toMessageOrNull() },
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
            titles[conversation.id] = conversation.title.takeUnless { it == LEGACY_UNNAMED_TITLE }.orEmpty()
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

    private fun AgentChatMessageUi.toEntityOrNull(
        conversationId: String,
        sortIndex: Int,
    ): ConversationMessageEntity? =
        when (this) {
            is UserMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_USER,
                content = content,
                imagesJson = images.toJsonArrayString(),
                isEdited = isEdited,
                // 用户消息借用步骤时刻两列存这一轮的起止时刻（不改表结构）。
                startedAt = runStartedAtMillis,
                finishedAt = runFinishedAtMillis,
            )

            is AgentMessageUi -> {
                if (content.isBlank() && isStreaming) {
                    null
                } else {
                    ConversationMessageEntity(
                        id = id,
                        conversationId = conversationId,
                        sortIndex = sortIndex,
                        type = TYPE_ASSISTANT,
                        content = content,
                        renderMarkdown = renderMarkdown,
                        // 回答行不用工具列：借它存「工具前说明」标记（定稿 21），不改表结构。
                        toolsJson = if (narration) NARRATION_EXTRA else "[]",
                        contextTokens = usage?.contextTokens,
                        inputTokens = usage?.inputTokens,
                        outputTokens = usage?.outputTokens,
                        reasoningTokens = usage?.reasoningTokens,
                        cachedTokens = usage?.cachedTokens,
                    )
                }
            }

            is SystemNoticeMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_SYSTEM_NOTICE,
                content = code.wireValue,
                resultSummary = detail,
                contextTokens = contextTokens,
                renderMarkdown = false,
            )

            is ThinkingMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_THINKING,
                content = content,
                elapsedSeconds = elapsedSeconds,
            )

            is ToolActivityMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_TOOL,
                content = command.orEmpty(),
                toolName = toolName,
                toolStatus = status.name,
                argumentsSummary = argumentsSummary,
                resultSummary = resultSummary,
                imageCount = imageCount,
                startedAt = startedAtMillis,
                finishedAt = finishedAtMillis,
                // 截图、个人数据等临时视图只在本次运行中显示，不存（工具可视化方案 §6）。
                toolViewJson = view?.takeUnless { it.transient }?.toJson()?.toString(),
            )

            is ToolSummaryMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_TOOL_SUMMARY,
                content = "",
                toolsJson = tools.toJsonArrayString(),
            )

            // 后台监听行复用已有列（不改表结构）：toolName=task id、argumentsSummary=名称、toolStatus=种类、
            // resultSummary=结束原因、elapsedSeconds=序号、startedAt=发生时刻；轮起点与整轮起止时刻存在 toolsJson。
            is MonitorEventMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_MONITOR,
                content = text,
                toolName = taskId,
                argumentsSummary = name,
                toolStatus = kind.name,
                resultSummary = reason,
                elapsedSeconds = seq,
                startedAt = atMillis,
                toolsJson = org.json.JSONObject()
                    .put("starts_turn", startsTurn)
                    .put("history_anchor", historyAnchor)
                    .apply {
                        runStartedAtMillis?.let { put("run_started_at", it) }
                        runFinishedAtMillis?.let { put("run_finished_at", it) }
                        exitCode?.let { put("exit_code", it) }
                        limitMs?.let { put("limit_ms", it) }
                    }
                    .toString(),
                renderMarkdown = false,
            )

            // 推荐追问沿用工具列表字段存文字；旧版本不认识该类型，读取时直接跳过。
            is SuggestionChipsMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_SUGGESTIONS,
                content = "",
                toolsJson = prompts.toJsonArrayString(),
            )

            else -> null
        }

    private fun ConversationMessageEntity.toMessageOrNull(): AgentChatMessageUi? =
        when (type) {
            TYPE_USER -> UserMessageUi(
                id = id,
                content = content,
                images = imagesJson.toStringList(),
                isEdited = isEdited,
                runStartedAtMillis = startedAt,
                runFinishedAtMillis = finishedAt,
            )

            TYPE_ASSISTANT -> AgentMessageUi(
                id = id,
                content = content,
                isStreaming = false,
                renderMarkdown = renderMarkdown ?: true,
                narration = toolsJson == NARRATION_EXTRA,
                usage = TokenUsageUi(
                    contextTokens = contextTokens,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    reasoningTokens = reasoningTokens,
                    cachedTokens = cachedTokens,
                ).takeUnless { it.isEmpty },
            )

            TYPE_SYSTEM_NOTICE -> SystemNoticeCode.fromWireValue(content)?.let { code ->
                SystemNoticeMessageUi(
                    id = id,
                    code = code,
                    detail = resultSummary,
                    contextTokens = contextTokens,
                )
            }

            TYPE_THINKING -> ThinkingMessageUi(
                id = id,
                content = content,
                isStreaming = false,
                elapsedSeconds = elapsedSeconds,
                collapsed = true,
            )

            TYPE_TOOL -> ToolActivityMessageUi(
                id = id,
                toolName = toolName.orEmpty(),
                status = toolStatus.orEmpty().toToolStatus(),
                argumentsSummary = argumentsSummary.orEmpty(),
                command = content.takeIf(String::isNotBlank),
                resultSummary = resultSummary,
                imageCount = imageCount,
                startedAtMillis = startedAt,
                finishedAtMillis = finishedAt,
                view = io.github.fartown.movo.agent.tools.core.ToolUiView.fromJsonString(toolViewJson),
            )

            TYPE_TOOL_SUMMARY -> ToolSummaryMessageUi(
                id = id,
                tools = toolsJson.toStringList(),
            )

            TYPE_SUGGESTIONS -> toolsJson.toStringList().takeIf { it.isNotEmpty() }?.let { prompts ->
                SuggestionChipsMessageUi(id = id, prompts = prompts)
            }

            TYPE_MONITOR -> {
                val extra = runCatching { org.json.JSONObject(toolsJson) }.getOrNull()
                val kind = runCatching { MonitorEventKindUi.valueOf(toolStatus.orEmpty()) }.getOrDefault(MonitorEventKindUi.Event)
                // 旧版本把到期行的时长存在正文里。
                val legacyLimit = if (kind == MonitorEventKindUi.Ended && resultSummary == "TIMEOUT" && extra?.has("limit_ms") != true) {
                    content.toLongOrNull()
                } else {
                    null
                }
                MonitorEventMessageUi(
                    id = id,
                    taskId = toolName.orEmpty(),
                    name = argumentsSummary.orEmpty(),
                    kind = kind,
                    seq = elapsedSeconds ?: 0,
                    atMillis = startedAt ?: 0L,
                    text = if (legacyLimit != null) "" else content,
                    reason = resultSummary,
                    // 旧版本把「已停止」「已中断」也记成了一轮的起点；它们不唤醒 Movo，不是起点。
                    startsTurn = extra?.optBoolean("starts_turn") == true &&
                        !(kind == MonitorEventKindUi.Ended && resultSummary in NON_WAKING_MONITOR_REASONS),
                    historyAnchor = extra?.optBoolean("history_anchor") == true,
                    runStartedAtMillis = extra?.takeIf { it.has("run_started_at") }?.optLong("run_started_at"),
                    runFinishedAtMillis = extra?.takeIf { it.has("run_finished_at") }?.optLong("run_finished_at"),
                    exitCode = extra?.takeIf { it.has("exit_code") }?.optInt("exit_code"),
                    limitMs = extra?.takeIf { it.has("limit_ms") }?.optLong("limit_ms") ?: legacyLimit,
                )
            }

            else -> null
        }

    private fun String.toToolStatus(): ToolActivityStatusUi =
        runCatching { ToolActivityStatusUi.valueOf(this) }.getOrNull()
            ?.let { status ->
                if (status == ToolActivityStatusUi.Running) ToolActivityStatusUi.Unknown else status
            }
            ?: ToolActivityStatusUi.Unknown

    private fun List<String>.toJsonArrayString(): String =
        JSONArray().also { array ->
            forEach { array.put(it) }
        }.toString()

    private fun String.toStringList(): List<String> =
        runCatching {
            val array = JSONArray(this)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())

    private fun List<ConversationMessageEntity>.toLegacyHistory(): List<AgentModelClient.ConversationMessage> =
        mapNotNull { message ->
            when (message.type) {
                TYPE_USER -> AgentModelClient.ConversationMessage(
                    role = "user",
                    content = message.content,
                )
                TYPE_ASSISTANT -> message.content
                    .takeIf { it.isNotBlank() }
                    ?.let { content ->
                        AgentModelClient.ConversationMessage(
                            role = "assistant",
                            content = content,
                        )
                    }
                else -> null
            }
        }

    private const val TYPE_USER = "user"
    private const val TYPE_ASSISTANT = "assistant"
    private const val TYPE_SYSTEM_NOTICE = "system_notice"
    private const val TYPE_THINKING = "thinking"
    private const val NARRATION_EXTRA = "{\"narration\":true}"
    private const val TYPE_TOOL = "tool"
    private const val TYPE_TOOL_SUMMARY = "tool_summary"
    private const val TYPE_SUGGESTIONS = "suggestions"
    private const val TYPE_MONITOR = "monitor"
    private val NON_WAKING_MONITOR_REASONS = setOf("STOPPED_BY_USER", "STOPPED_BY_AGENT", "SESSION_END", "INTERRUPTED")
    private const val MESSAGE_LOAD_PAGE_SIZE = 128
    private const val LEGACY_UNNAMED_TITLE = "新对话"
}
