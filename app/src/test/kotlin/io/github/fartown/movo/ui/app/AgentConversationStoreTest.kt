package io.github.fartown.movo.ui.app

import android.content.Context
import io.github.fartown.movo.agent.model.AgentConversationCodec
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.roleplay.CharacterCardCodec
import io.github.fartown.movo.agent.roleplay.RoleplayBinding
import io.github.fartown.movo.agent.roleplay.RoleplayMessageLink
import io.github.fartown.movo.agent.roleplay.RoleplayMessageState
import io.github.fartown.movo.data.db.ConversationEntity
import io.github.fartown.movo.data.db.ConversationMessageEntity
import io.github.fartown.movo.data.db.ConversationStateEntity
import io.github.fartown.movo.data.db.MovoDatabase
import io.github.fartown.movo.data.model.ReasoningEffort
import io.github.fartown.movo.ui.model.AgentChatHomeUiState
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.SuggestionChipsMessageUi
import io.github.fartown.movo.ui.model.SystemNoticeCode
import io.github.fartown.movo.ui.model.SystemNoticeMessageUi
import io.github.fartown.movo.ui.model.ThinkingMessageUi
import io.github.fartown.movo.ui.model.TokenUsageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentConversationStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        MovoDatabase.closeForTests()
        context.deleteDatabase("movo.db")
    }

    @Test
    fun narrationFlagSurvivesSaveAndLoad() = runBlocking {
        val state = AgentChatHomeUiState(
            messages = listOf(
                UserMessageUi(id = "u1", content = "打开设置看看 Wi‑Fi 连的是哪个网络"),
                AgentMessageUi(id = "assistant-run-1-1", content = "我先查一下网络状态。", isStreaming = false, narration = true),
                AgentMessageUi(id = "assistant-run-2-1", content = "Wi‑Fi 连的是 Xiaomi_5G。", isStreaming = false),
            ),
            input = "", isStreaming = false, thinkingEnabled = false,
        )
        AgentConversationStore.save(context, "c1", mapOf("c1" to state), mapOf("c1" to "Wi‑Fi"), mapOf("c1" to 1L))
        val restored = AgentConversationStore.load(context).conversationsById.getValue("c1").messages
        assertEquals(listOf(true, false), restored.filterIsInstance<AgentMessageUi>().map { it.narration })
    }

    @Test
    fun repeatedSavePreservesRoleBindingRevisionsPendingRewriteAndOriginalJournal() = runBlocking {
        val original = AgentModelClient.ConversationMessage(
            role = "assistant", content = "原始回答", messageId = "assistant-role-1",
        )
        val binding = RoleplayBinding(
            characterId = "character-1",
            cardSnapshotJson = CharacterCardCodec.encodeJson(CharacterCardCodec.create("旅人")),
            characterName = "旅人", userName = "朋友", userDescription = "同行的伙伴",
        )
        val state = AgentChatHomeUiState(
            messages = listOf(AgentMessageUi(id = original.messageId, content = original.content, isStreaming = false)),
            input = "", isStreaming = false, thinkingEnabled = false,
            journal = listOf(original), history = listOf(original), roleplay = binding,
            roleplayMessages = RoleplayMessageState(
                links = mapOf(original.messageId to RoleplayMessageLink(original.messageId)),
                pendingRewrites = mapOf("rewrite-in-flight" to original.messageId),
            ),
        )
        var role = RoleplayConversationReducer.edit(state, original.messageId, "用户修订的回答")!!
        repeat(2) {
            AgentConversationStore.save(
                context, "role", mapOf("role" to role, "ordinary" to AgentChatHomeUiState(
                    messages = listOf(UserMessageUi(id = "ordinary-user", content = "查看电量")),
                    input = "", isStreaming = false, thinkingEnabled = false,
                )), mapOf("role" to "旅人", "ordinary" to "查看电量"), mapOf("role" to 1L, "ordinary" to 2L),
            )
            val restored = AgentConversationStore.load(context)
            role = restored.conversationsById.getValue("role")
            assertEquals(binding, role.roleplay)
            assertEquals(listOf(original), role.journal)
            assertEquals("用户修订的回答", role.history.single().content)
            assertEquals("rewrite-in-flight", role.roleplayMessages.pendingRewrites.keys.single())
            assertEquals(listOf("原始回答", "用户修订的回答"), role.roleplayMessages.revisions.getValue(original.messageId).candidates)
            assertEquals(2, (role.messages.single() as AgentMessageUi).candidateCount)
            assertEquals(null, restored.conversationsById.getValue("ordinary").roleplay)
            assertTrue(restored.conversationsById.getValue("ordinary").roleplayMessages.revisions.isEmpty())
        }
        val switched = RoleplayConversationReducer.select(role, original.messageId, 0)!!
        assertEquals("原始回答", switched.history.single().content)
        assertEquals(listOf(original), switched.journal)
    }

    @Test
    fun saveAndLoadPreservesConversations() {
        val conversation = AgentChatHomeUiState(
            messages = listOf(
                UserMessageUi(
                    id = "user-1",
                    content = "看一下当前屏幕",
                    isEdited = true,
                ),
                ThinkingMessageUi(
                    id = "thinking-1",
                    content = "需要先观察屏幕",
                    isStreaming = false,
                    elapsedSeconds = 3,
                    collapsed = true,
                ),
                ToolActivityMessageUi(
                    id = "tool-1",
                    toolName = "run_command",
                    status = ToolActivityStatusUi.Success,
                    argumentsSummary = "执行命令 · Android · root",
                    command = "pm list packages | head",
                    resultSummary = "ok=true, chars=100",
                    imageCount = 1,
                ),
                AgentMessageUi(
                    id = "assistant-1",
                    content = "| 项目 | 内容 |\n| --- | --- |\n| 电量 | 88% |",
                    isStreaming = false,
                    renderMarkdown = true,
                    usage = TokenUsageUi(
                        contextTokens = 100,
                        inputTokens = 30,
                        outputTokens = 40,
                        reasoningTokens = 20,
                        cachedTokens = 10,
                    ),
                ),
            ),
            history = listOf(
                io.github.fartown.movo.agent.model.AgentModelClient.ConversationMessage(
                    role = "user",
                    content = "看一下当前屏幕",
                ),
                io.github.fartown.movo.agent.model.AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "",
                    reasoningContent = "需要先观察屏幕",
                    toolCallsJson = """[{"id":"toolu_1","type":"function","function":{"name":"observe_screen","arguments":"{}"}}]""",
                ),
                io.github.fartown.movo.agent.model.AgentModelClient.ConversationMessage(
                    role = "tool",
                    content = "{\"ok\":true}",
                    toolCallId = "toolu_1",
                ),
                io.github.fartown.movo.agent.model.AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "| 项目 | 内容 |\n| --- | --- |\n| 电量 | 88% |",
                ),
            ),
            input = "不应该保存草稿",
            isStreaming = true,
            thinkingEnabled = true,
            reasoningEffort = ReasoningEffort.HIGH,
        )

        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-1",
                conversationsById = mapOf("conv-1" to conversation),
                titles = mapOf("conv-1" to "屏幕分析"),
                updatedAt = mapOf("conv-1" to 1234L),
            )
        }

        val snapshot = AgentConversationStore.load(context)

        assertEquals("conv-1", snapshot.selectedConversationId)
        assertEquals("屏幕分析", snapshot.titles.getValue("conv-1"))
        assertEquals(1234L, snapshot.updatedAt.getValue("conv-1"))
        val restored = snapshot.conversationsById.getValue("conv-1")
        assertEquals("", restored.input)
        assertFalse(restored.isStreaming)
        assertTrue(restored.thinkingEnabled)
        assertEquals(ReasoningEffort.HIGH, restored.reasoningEffort)
        assertEquals(conversation.messages, restored.messages)
        assertEquals(conversation.history, restored.history)
    }

    @Test
    fun saveAndLoadPreservesSemanticSystemNoticesWithoutTranslatedContent() {
        val notice = SystemNoticeMessageUi(
            id = "assistant-run-1-1",
            code = SystemNoticeCode.RuntimeFailed,
            detail = "upstream timeout",
        )
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-notice",
                conversationsById = mapOf(
                    "conv-notice" to AgentChatHomeUiState(
                        messages = listOf(notice),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    ),
                ),
                titles = mapOf("conv-notice" to ""),
                updatedAt = mapOf("conv-notice" to 1L),
            )
        }

        val snapshot = AgentConversationStore.load(context)
        assertEquals("", snapshot.titles.getValue("conv-notice"))
        assertEquals(
            notice,
            snapshot.conversationsById.getValue("conv-notice").messages.single(),
        )
    }

    @Test
    fun saveAndLoadPreservesLatestFollowUpSuggestions() {
        val messages = listOf(
            UserMessageUi(id = "user-run-1", content = "查一下我的快递"),
            AgentMessageUi(id = "assistant-run-1-1", content = "取件码 3-2-1106", isStreaming = false),
            SuggestionChipsMessageUi(id = "suggestions-assistant-run-1-1", prompts = listOf("设置取件提醒", "把取件码发给我自己")),
        )
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-follow-up",
                conversationsById = mapOf(
                    "conv-follow-up" to AgentChatHomeUiState(
                        messages = messages,
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    ),
                ),
                titles = mapOf("conv-follow-up" to "快递"),
                updatedAt = mapOf("conv-follow-up" to 1L),
            )
        }

        val restored = AgentConversationStore.load(context).conversationsById.getValue("conv-follow-up").messages
        assertEquals(messages.last(), restored.last())
        assertEquals(3, restored.size)
    }

    @Test
    fun saveAndLoadPreservesTurnStartAndFinishOnUserMessage() {
        val messages = listOf(
            UserMessageUi(
                id = "user-run-2",
                content = "打开设置看看电量",
                runStartedAtMillis = 1_700_000_000_000L,
                runFinishedAtMillis = 1_700_000_157_900L,
            ),
            AgentMessageUi(id = "assistant-run-2-1", content = "电量 82%", isStreaming = false),
        )
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-turn-span",
                conversationsById = mapOf(
                    "conv-turn-span" to AgentChatHomeUiState(
                        messages = messages,
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    ),
                ),
                titles = mapOf("conv-turn-span" to "电量"),
                updatedAt = mapOf("conv-turn-span" to 1L),
            )
        }

        val restored = AgentConversationStore.load(context).conversationsById.getValue("conv-turn-span").messages
        assertEquals(messages.first(), restored.first())
    }

    @Test
    fun unknownStoredEffortFallsBackToDefault() {
        runBlocking {
            MovoDatabase.get(context).conversationDao().replaceAll(
                conversations = listOf(
                    ConversationEntity(
                        id = "conv-unknown",
                        title = "Unknown",
                        thinkingEnabled = false,
                        reasoningEffort = "future_effort",
                        createdAt = 1L,
                        updatedAt = 1L,
                    )
                ),
                messages = emptyList(),
                state = ConversationStateEntity(selectedConversationId = "conv-unknown"),
            )
        }

        val restored = AgentConversationStore.load(context)
            .conversationsById
            .getValue("conv-unknown")

        assertEquals(ReasoningEffort.DEFAULT, restored.reasoningEffort)
        assertTrue(restored.thinkingEnabled)
    }

    @Test
    fun saveAndLoadPreservesAllConversationsAndMessagesWithoutClipping() {
        val longContent = "x".repeat(20_000)
        val primaryMessages = buildList {
            add(UserMessageUi(id = "conv-0-user-long", content = longContent))
            repeat(130) { index ->
                add(
                    AgentMessageUi(
                        id = "conv-0-assistant-$index",
                        content = "assistant-$index",
                        isStreaming = false,
                    )
                )
            }
        }
        val conversations = buildMap {
            put(
                "conv-0",
                AgentChatHomeUiState(
                    messages = primaryMessages,
                    input = "",
                    isStreaming = false,
                    thinkingEnabled = false,
                )
            )
            repeat(59) { index ->
                val id = "conv-${index + 1}"
                put(
                    id,
                    AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "$id-user", content = "message-$id")),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                )
            }
        }
        val titles = conversations.keys.associateWith { id -> "title-$id" }
        val updatedAt = conversations.keys.associateWith { id -> id.removePrefix("conv-").toLong() }

        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-0",
                conversationsById = conversations,
                titles = titles,
                updatedAt = updatedAt,
            )
        }

        val snapshot = AgentConversationStore.load(context)

        assertEquals(60, snapshot.conversationsById.size)
        val restored = snapshot.conversationsById.getValue("conv-0")
        assertEquals(131, restored.messages.size)
        assertEquals(longContent, (restored.messages.first() as UserMessageUi).content)
        assertEquals("assistant-129", (restored.messages.last() as AgentMessageUi).content)
    }

    @Test
    fun savePreservesCompleteContextAndDisplayedMessages() {
        val displayedContent = "展示消息-${"d".repeat(120_000)}"
        val history = buildList {
            repeat(20) { index ->
                add(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "历史-$index-${"h".repeat(20_000)}",
                    )
                )
            }
            add(AgentModelClient.ConversationMessage(role = "user", content = "最新上下文"))
        }

        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-large",
                conversationsById = mapOf(
                    "conv-large" to AgentChatHomeUiState(
                        messages = listOf(
                            UserMessageUi(id = "user-large", content = displayedContent)
                        ),
                        history = history,
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                ),
                titles = mapOf("conv-large" to "长对话"),
                updatedAt = mapOf("conv-large" to 1L),
            )
        }

        val checkpoint = runBlocking {
            MovoDatabase.get(context)
                .conversationDao()
                .contextCheckpoint("conv-large")!!
        }
        val restored = AgentConversationStore.load(context)
            .conversationsById
            .getValue("conv-large")

        assertTrue(checkpoint.historyJson.length > 96_000)
        assertEquals(history, restored.history)
        assertEquals(history, restored.journal)
        assertEquals(displayedContent, (restored.messages.single() as UserMessageUi).content)
        assertEquals("最新上下文", restored.history.last().content)
    }

    @Test
    fun loadIgnoresLegacyHistoryColumnAndFallsBackToMessageRows() {
        runBlocking {
            val dao = MovoDatabase.get(context).conversationDao()
            dao.insertConversations(
                listOf(
                    ConversationEntity(
                        id = "conv-legacy-large",
                        title = "旧长对话",
                        thinkingEnabled = false,
                        historyJson = "x".repeat(2_500_000),
                        createdAt = 1L,
                        updatedAt = 1L,
                    )
                )
            )
            dao.insertMessages(
                listOf(
                    ConversationMessageEntity(
                        id = "legacy-user",
                        conversationId = "conv-legacy-large",
                        sortIndex = 0,
                        type = "user",
                        content = "从消息记录恢复",
                    )
                )
            )
        }

        val restored = AgentConversationStore.load(context)
            .conversationsById
            .getValue("conv-legacy-large")

        assertEquals("从消息记录恢复", restored.history.single().content)
        assertEquals("从消息记录恢复", (restored.messages.single() as UserMessageUi).content)
    }

    @Test
    fun loadKeepsDatabaseEmptyUntilFirstMessageIsSent() {
        val snapshot = AgentConversationStore.load(context)

        assertTrue(snapshot.conversationsById.isEmpty())
        assertEquals(null, snapshot.selectedConversationId)
    }

    @Test
    fun characterGreetingIsLocalAndOrdinaryNewConversationReturnsToMovo() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val state = AgentAppState(context, scope)
            val binding = RoleplayBinding(
                "local-character", CharacterCardCodec.encodeJson(CharacterCardCodec.create("旅人")),
                "旅人", userName = "小林",
            )
            state.startCharacterConversation(binding, "你好，{{user}}，我是{{char}}。")
            assertFalse(state.homeState.isStreaming)
            assertEquals("你好，小林，我是旅人。", (state.homeState.messages.single() as AgentMessageUi).content)
            assertEquals(binding, state.homeState.roleplay)
            assertTrue(state.homeState.appliedRuntimeRunIds.isEmpty())
            assertTrue(state.homeState.roleplayMessages.pendingRewrites.isEmpty())

            state.createConversation()
            assertEquals(null, state.homeState.roleplay)
            assertTrue(state.homeState.history.isEmpty())
            assertTrue(state.homeState.messages.isEmpty())
            assertFalse(state.homeState.isStreaming)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun creatingConversationKeepsEmptyStateOutOfHistoryAndDatabase() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val state = AgentAppState(context, scope)

            state.createConversation()
            state.createConversation()

            assertEquals(null, state.conversationPaneState.selectedConversationId)
            assertTrue(state.conversationPaneState.conversations.isEmpty())
            assertTrue(
                runBlocking {
                    MovoDatabase.get(context).conversationDao().conversations().isEmpty()
                }
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun savingEmptySnapshotClearsPreviouslyPersistedConversations() {
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-1",
                conversationsById = mapOf(
                    "conv-1" to AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "user-1", content = "hello")),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                ),
                titles = mapOf("conv-1" to "hello"),
                updatedAt = mapOf("conv-1" to 1L),
            )
            AgentConversationStore.save(
                context = context,
                selectedConversationId = null,
                conversationsById = emptyMap(),
                titles = emptyMap(),
                updatedAt = emptyMap(),
            )
        }

        val snapshot = AgentConversationStore.load(context)
        assertTrue(snapshot.conversationsById.isEmpty())
        assertEquals(null, snapshot.selectedConversationId)
    }

    @Test
    fun monitorRowsKeepExitCodeLimitAndTailAndLegacyRowsAreNormalized() = runBlocking {
        val rows = listOf(
            io.github.fartown.movo.ui.model.MonitorEventMessageUi(
                id = "monitor-m1-event-1", taskId = "m1", name = "下载", kind = io.github.fartown.movo.ui.model.MonitorEventKindUi.Event,
                seq = 1, atMillis = 10L, text = "50%", startsTurn = true, historyAnchor = true, runStartedAtMillis = 11L, runFinishedAtMillis = 12L,
            ),
            io.github.fartown.movo.ui.model.MonitorEventMessageUi(
                id = "monitor-m1-ended-1", taskId = "m1", name = "下载", kind = io.github.fartown.movo.ui.model.MonitorEventKindUi.Ended,
                seq = 1, atMillis = 20L, text = "DONE\nbye", reason = "EXIT", exitCode = 1,
            ),
            io.github.fartown.movo.ui.model.MonitorEventMessageUi(
                id = "monitor-m2-ended-4", taskId = "m2", name = "喝水", kind = io.github.fartown.movo.ui.model.MonitorEventKindUi.Ended,
                seq = 4, atMillis = 30L, text = "", reason = "TIMEOUT", limitMs = 7_200_000L, startsTurn = true, historyAnchor = true,
            ),
        )
        AgentConversationStore.save(
            context, "conv-monitor",
            mapOf("conv-monitor" to AgentChatHomeUiState(messages = rows, input = "", isStreaming = false, thinkingEnabled = false)),
            mapOf("conv-monitor" to "监听"), mapOf("conv-monitor" to 1L),
        )
        assertEquals(rows, AgentConversationStore.load(context).conversationsById.getValue("conv-monitor").messages)

        // 旧版本：到期行的时长存在正文里；「已停止」「已中断」也被记成了一轮的起点。
        MovoDatabase.get(context).conversationDao().replaceAll(
            conversations = listOf(ConversationEntity(id = "conv-legacy", title = "旧", thinkingEnabled = false, createdAt = 1L, updatedAt = 1L)),
            messages = listOf(
                ConversationMessageEntity(
                    id = "monitor-m3-ended-2", conversationId = "conv-legacy", sortIndex = 0, type = "monitor", content = "1800000",
                    toolName = "m3", argumentsSummary = "提醒", toolStatus = "Ended", resultSummary = "TIMEOUT", elapsedSeconds = 2,
                    startedAt = 5L, toolsJson = "{\"starts_turn\":true,\"history_anchor\":true}",
                ),
                ConversationMessageEntity(
                    id = "monitor-m4-ended-0", conversationId = "conv-legacy", sortIndex = 1, type = "monitor", content = "",
                    toolName = "m4", argumentsSummary = "电量", toolStatus = "Ended", resultSummary = "STOPPED_BY_USER", elapsedSeconds = 0,
                    startedAt = 6L, toolsJson = "{\"starts_turn\":true}",
                ),
            ),
            state = ConversationStateEntity(selectedConversationId = "conv-legacy"),
        )
        val legacy = AgentConversationStore.load(context).conversationsById.getValue("conv-legacy").messages
            .filterIsInstance<io.github.fartown.movo.ui.model.MonitorEventMessageUi>()
        assertEquals(1_800_000L, legacy[0].limitMs)
        assertEquals("", legacy[0].text)
        assertTrue(legacy[0].startsTurn)
        assertFalse(legacy[1].startsTurn)
    }

    @Test
    fun queuedSavesAreCoalescedIntoTheLatestState() = runBlocking {
        fun state(text: String) = mapOf(
            "conv-c" to AgentChatHomeUiState(
                messages = listOf(UserMessageUi(id = "u", content = text)), input = "", isStreaming = false, thinkingEnabled = false,
            ),
        )
        val older = AgentConversationStore.request("conv-c", state("旧"), mapOf("conv-c" to "c"), mapOf("conv-c" to 1L))
        val newer = AgentConversationStore.request("conv-c", state("新"), mapOf("conv-c" to "c"), mapOf("conv-c" to 2L))

        // 先排队的那次提交直接写最新登记的状态；后面那次已经被它覆盖，不再整库重写。
        AgentConversationStore.commit(context, older)
        assertEquals("新", (AgentConversationStore.load(context).conversationsById.getValue("conv-c").messages.single() as UserMessageUi).content)
        MovoDatabase.get(context).conversationDao().replaceAll(conversations = emptyList(), messages = emptyList(), state = null)
        AgentConversationStore.commit(context, newer)
        assertTrue(AgentConversationStore.load(context).conversationsById.isEmpty())
    }

    @Test
    fun toolStepViews_persistUnlessTransient() {
        val kept = io.github.fartown.movo.agent.tools.core.ToolUiView(
            summary = "退出码 0",
            blocks = listOf(io.github.fartown.movo.agent.tools.core.ToolUiBlock.Output("hi", label = "输出")),
        )
        val transient = io.github.fartown.movo.agent.tools.core.ToolUiView(summary = "找到 1 条", transient = true)
        val conversation = AgentChatHomeUiState(
            messages = listOf(
                ToolActivityMessageUi("tool-1", "terminal_run", ToolActivityStatusUi.Success, "运行 · echo hi", resultSummary = "退出码 0", view = kept),
                ToolActivityMessageUi("tool-2", "personal_search", ToolActivityStatusUi.Success, "搜索短信", resultSummary = "找到 1 条", view = transient),
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-v",
                conversationsById = mapOf("conv-v" to conversation),
                titles = mapOf("conv-v" to "视图"),
                updatedAt = mapOf("conv-v" to 1L),
            )
        }
        val restored = AgentConversationStore.load(context).conversationsById.getValue("conv-v").messages
            .filterIsInstance<ToolActivityMessageUi>()
        assertEquals(kept, restored.first { it.id == "tool-1" }.view)
        // 个人数据、截图等临时视图只在本次运行中显示：重启后只剩摘要。
        assertEquals(null, restored.first { it.id == "tool-2" }.view)
        assertEquals("找到 1 条", restored.first { it.id == "tool-2" }.resultSummary)
    }
}
