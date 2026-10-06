package io.github.fartown.movo.ui.app

import io.github.fartown.movo.ui.components.isRunSupplement
import io.github.fartown.movo.agent.model.AgentContextSnapshot
import io.github.fartown.movo.agent.voice.session.VoiceConversationHost
import io.github.fartown.movo.agent.voice.session.VoiceSessionOwner
import io.github.fartown.movo.agent.voice.session.VoiceSessionManager
import io.github.fartown.movo.ui.components.AgentConversationDraftStore
import io.github.fartown.movo.ui.components.ComposerNotice
import io.github.fartown.movo.ui.components.ComposerNotices
import io.github.fartown.movo.ui.theme.MovoIconData
import io.github.fartown.movo.ui.theme.MovoIcons

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshotFlow
import io.github.fartown.movo.MovoApp
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import io.github.fartown.movo.agent.device.AgentFileReferenceGateway
import io.github.fartown.movo.agent.device.DeviceLocationProvider
import io.github.fartown.movo.agent.device.RootAccess
import io.github.fartown.movo.agent.media.AgentImageCodec
import io.github.fartown.movo.agent.memory.AgentMemoryContextBuilder
import io.github.fartown.movo.agent.model.AgentFileReference
import io.github.fartown.movo.agent.model.AgentFileReferenceKind
import io.github.fartown.movo.agent.model.AgentFileReferencePolicy
import io.github.fartown.movo.agent.model.AgentFileReferencePromptCodec
import io.github.fartown.movo.agent.model.AgentFollowUpSuggester
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.roleplay.RoleplayBinding
import io.github.fartown.movo.agent.roleplay.CharacterMacros
import io.github.fartown.movo.agent.roleplay.CharacterCardCodec
import io.github.fartown.movo.agent.roleplay.RoleplayMessageLink
import io.github.fartown.movo.agent.roleplay.RoleplayMessageState
import io.github.fartown.movo.data.auth.ChatGptAuth
import io.github.fartown.movo.data.repository.CharacterRepository
import io.github.fartown.movo.agent.runtime.AgentEvent
import io.github.fartown.movo.agent.tools.interaction.InteractionReply
import io.github.fartown.movo.agent.runtime.AgentConversationTarget
import io.github.fartown.movo.agent.runtime.AgentExecutionService
import io.github.fartown.movo.agent.runtime.AgentExternalArchivePayload
import io.github.fartown.movo.agent.runtime.AgentRunArchiveStore
import io.github.fartown.movo.agent.runtime.AgentRunCheckpointStore
import io.github.fartown.movo.agent.runtime.AgentRuntimeClient
import io.github.fartown.movo.agent.runtime.AgentRuntimeWire
import io.github.fartown.movo.agent.runtime.AgentTokenUsage
import io.github.fartown.movo.agent.runtime.AgentUiHandoffPayload
import io.github.fartown.movo.agent.skill.SkillRuntime
import io.github.fartown.movo.config.Prefs
import io.github.fartown.movo.core.AndroidAgentLogger
import io.github.fartown.movo.core.safeLogType
import io.github.fartown.movo.data.model.ModelReasoningCapabilities
import io.github.fartown.movo.data.db.ConversationModelMessageEntity
import io.github.fartown.movo.data.model.ReasoningEffort
import io.github.fartown.movo.data.repository.AgentMemoryRepository
import io.github.fartown.movo.data.repository.MovoBackupRepository
import io.github.fartown.movo.data.repository.MovoBackupSummary
import io.github.fartown.movo.data.repository.ProviderRepository
import io.github.fartown.movo.data.repository.RuntimeConfigRepository
import io.github.fartown.movo.ui.model.AgentChatHomeUiState
import io.github.fartown.movo.ui.model.AgentChatMessageUi
import io.github.fartown.movo.ui.model.AgentInteractionUiState
import io.github.fartown.movo.ui.model.AgentMemoryUiState
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.AgentModelPickerProjector
import io.github.fartown.movo.ui.model.AgentModelPickerUiState
import io.github.fartown.movo.ui.model.AgentSkillsUiState
import io.github.fartown.movo.ui.model.AgentToolsUiState
import io.github.fartown.movo.ui.model.ConversationModeUi
import io.github.fartown.movo.ui.model.ConversationPaneUiState
import io.github.fartown.movo.ui.model.ConversationSummaryUi
import io.github.fartown.movo.ui.model.MessageEditUiState
import io.github.fartown.movo.ui.model.PendingFileReferenceUi
import io.github.fartown.movo.ui.model.PendingImageUi
import io.github.fartown.movo.ui.model.PermissionHealthItemUi
import io.github.fartown.movo.ui.model.PermissionHealthUiState
import io.github.fartown.movo.ui.model.PermissionStatusUi
import io.github.fartown.movo.ui.model.SkillItemUi
import io.github.fartown.movo.ui.model.SkillNoticeUi
import io.github.fartown.movo.ui.model.SkillReplacementUi
import io.github.fartown.movo.ui.model.SystemNoticeCode
import io.github.fartown.movo.ui.model.SystemNoticeMessageUi
import io.github.fartown.movo.ui.model.ThinkingMessageUi
import io.github.fartown.movo.ui.model.TokenUsageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolGroupUi
import io.github.fartown.movo.ui.model.ToolItemUi
import io.github.fartown.movo.ui.model.UserMessageUi
import io.github.fartown.movo.ui.model.canDeleteUserSkill
import io.github.fartown.movo.ui.model.contentMatchSnippet
import io.github.fartown.movo.ui.model.contentMatches
import io.github.fartown.movo.ui.model.matchExcerpt
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import io.github.fartown.movo.agent.monitor.MonitorDeliveryQueue
import io.github.fartown.movo.agent.monitor.MonitorEndReason
import io.github.fartown.movo.agent.monitor.MonitorEventFormatter
import io.github.fartown.movo.agent.monitor.MonitorNotice
import io.github.fartown.movo.agent.monitor.MonitorNoticeSink
import io.github.fartown.movo.agent.monitor.MonitorRegistry
import io.github.fartown.movo.agent.monitor.MonitorWakePolicy
import io.github.fartown.movo.agent.runtime.AgentRuntimeService
import io.github.fartown.movo.ui.components.MonitorRowLabels
import io.github.fartown.movo.ui.model.MonitorEventKindUi
import io.github.fartown.movo.ui.model.MonitorEventMessageUi
import io.github.fartown.movo.ui.model.isTurnStart
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 把一句补充交给正在执行的 run，结果在主线程回调（规范 8.4）。测试可注入。 */
internal fun interface RuntimeSteerer {
    fun steer(runId: String, text: String, onResult: (accepted: Boolean) -> Unit)
}

internal class AgentAppState(
    context: Context,
    private val scope: CoroutineScope,
    skillZipImportGateway: SkillZipImportGateway? = null,
    private val voiceSession: VoiceSessionOwner = VoiceSessionManager,
    runtimeSteerer: RuntimeSteerer? = null,
) : VoiceConversationHost {
    private val appContext = context.applicationContext
    private val runtimeSteerer = runtimeSteerer ?: RuntimeSteerer { runId, text, onResult ->
        scope.launch(Dispatchers.IO) {
            val accepted = AgentRuntimeClient(appContext, AndroidAgentLogger).steerRun(runId, text)
            withContext(Dispatchers.Main) { onResult(accepted) }
        }
    }
    private val skillZipImportGateway = skillZipImportGateway ?: CoreSkillZipImportGateway(appContext)
    private val runConversationIds = mutableMapOf<String, String>()
    private val runMessageProjector = AgentRunMessageProjector()
    private val runEventCoalescer = AgentRunEventCoalescer()
    private val runEventFlushJobs = mutableMapOf<String, Job>()
    private data class VoiceRunListener(
        val onEvent: (AgentEvent) -> Unit,
        val onResult: (AgentRuntimeWire.RunResult) -> Unit,
    )
    private val voiceRunListeners = java.util.concurrent.ConcurrentHashMap<String, VoiceRunListener>()
    // 推荐追问：只为本进程发起的普通文字对话记下所用模型，结束后用同一模型另发小请求（见 AgentFollowUpSuggester）。
    private val followUpRunConfigs = java.util.concurrent.ConcurrentHashMap<String, AgentModelClient.ModelConfig>()
    private val followUpJobs = mutableMapOf<String, Job>()
    private var currentRunId: String? by mutableStateOf(null)
    private var currentRunJob: Job? = null
    internal data class QueuedTextSubmission(
        val conversationId: String, val text: String,
        val images: List<PendingImageUi>, val files: List<PendingFileReferenceUi>,
    )
    var queuedTextSubmission by mutableStateOf<QueuedTextSubmission?>(null)
        private set

    /** 对话存储（docs/solutions/conversation-storage）：数据库是唯一的真身，内存里只放用得到的对话。 */
    private val conversationRepository = ConversationRepository.get(appContext)
    private val runtimeRecoveryInProgress = AtomicBoolean(false)
    private val runtimeRecoveryMutex = Mutex()
    private val defaultThinkingEnabled = agentBooleanForUi(Prefs.Keys.AGENT_THINKING_ENABLED)
    // 启动只读对话目录（每个对话一行加最后一条）和选中的那一个的完整内容，不再把全部对话读进内存。
    private val initialConversations = runBlocking(Dispatchers.IO) { conversationRepository.startup() }
    private var skillNoticeSequence = 0L
    private var pendingSkillZipUri: Uri? = null
    private var pendingSkillZipSha256: String? = null
    private var currentReasoningCapabilities: ModelReasoningCapabilities? = null
    private var fileAttachmentOwnerVersion = 0L

    private var selectedConversationId: String? = initialConversations.selectedConversationId
        set(value) {
            if (field != value) voiceSession.onConversationChanging(value)
            field = value
        }
    override val voiceSelectedConversationId: String? get() = selectedConversationId
    override val voiceRuntimeBusy: Boolean get() = currentRunId != null || conversationsById.values.any { it.isStreaming }

    /**
     * 输入框上方当前的提示（`Composer/Notice`，规范 8.5、8.11「不用 Toast」）：发送被拦下、附件没能添加、模型没切换成功等
     * 输入相关的反馈就地显示在输入框上方，[ComposerNotices.AUTO_DISMISS_MS] 后自动消失，也可手动关闭。
     */
    val composerNotice: ComposerNotice? get() = ComposerNotices.current
    private var composerNoticeJob: Job? = null

    private fun showComposerNotice(icon: MovoIconData, title: String, description: String) {
        val notice = ComposerNotice(
            icon = icon,
            title = title,
            description = description,
            actionLabel = null,
            action = null,
            autoDismissMillis = ComposerNotices.AUTO_DISMISS_MS,
        )
        ComposerNotices.show(notice)
        composerNoticeJob?.cancel()
        composerNoticeJob = scope.launch {
            delay(ComposerNotices.AUTO_DISMISS_MS)
            ComposerNotices.dismiss(notice.id)
        }
    }

    /** 已有一条排队的消息时再发送：新内容留在输入框，提示先处理排队的那条。 */
    private fun showQueuedConflictNotice() = showComposerNotice(
        MovoIcons.Clock,
        appContext.getString(R.string.movo_notice_queued_title),
        appContext.getString(R.string.movo_notice_queued_desc),
    )
    /** 已读进内存的对话：选中的、正在执行的、排着队的、语音在用的；其余只在库里，用到时再读（[ensureLoaded]）。 */
    private var conversationsById: Map<String, AgentChatHomeUiState> by mutableStateOf(
        initialConversations.selected?.let { mapOf(it.id to it.state) }.orEmpty()
    )
    /** 全部对话（含没读进内存的）的标题与更新时间，侧栏用。 */
    private var conversationTitles: Map<String, String> = initialConversations.summaries.associate { it.id to it.title }
    private var conversationUpdatedAt: Map<String, Long> = initialConversations.summaries.associate { it.id to it.updatedAt }
    /** 已存进库的对话；没读进内存的那些，侧栏预览与角色名用 [storedPreviews]。 */
    private var storedConversationIds: Set<String> = initialConversations.summaries.mapTo(LinkedHashSet()) { it.id }
    private var storedPreviews: Map<String, ConversationRepository.Summary> = initialConversations.summaries.associateBy { it.id }
    /** 每个已加载对话上次交给存储的样子：保存时只写跟它相比变了的部分。 */
    private val persistedStates = HashMap<String, AgentChatHomeUiState>().apply {
        initialConversations.selected?.let { put(it.id, it.state) }
    }
    private val persistedMeta = HashMap<String, Pair<String, Long>>().apply {
        initialConversations.selected?.let { put(it.id, it.title to it.updatedAt) }
    }
    private var persistedSelection: String? = initialConversations.selectedConversationId

    /** 测试用：现在读进内存的对话。 */
    @androidx.annotation.VisibleForTesting
    internal val loadedConversationIdsForTests: Set<String> get() = conversationsById.keys

    private fun isKnownConversation(conversationId: String) =
        conversationId in conversationsById || conversationId in storedConversationIds

    var homeState by mutableStateOf(
        selectedConversationId?.let(conversationsById::get) ?: emptyChatState(defaultThinkingEnabled)
    )
        private set

    var modelPickerState by mutableStateOf(AgentModelPickerUiState())
        private set

    var conversationPaneState by mutableStateOf(
        ConversationPaneUiState(
            conversations = emptyList(),
            selectedConversationId = selectedConversationId,
            searchQuery = "",
        )
    )
        private set

    var toolsState by mutableStateOf(buildToolsState(appContext))
        private set

    var skillsState by mutableStateOf(AgentSkillsUiState(isLoading = true))
        private set

    var permissionHealthState by mutableStateOf(PermissionHealthUiState(emptyList()))
        private set

    var memoryState by mutableStateOf(AgentMemoryUiState())
        private set

    /** 当前待处理的提问卡 / 确认卡；为空表示没有待交互。由运行时交互事件投影，作答后回传并清空。 */
    var activeInteraction: AgentInteractionUiState? by mutableStateOf(null)
        private set

    // 后台监听的投递状态：必须声明在 init 之前（init 里的收集器会立刻调用 pumpMonitorNotices）。
    /** 还没交给 Movo 的监听通知、并入运行中一轮还没被读到的通知、事件轮的起点批次。 */
    private val monitorQueue = MonitorDeliveryQueue()
    /** 运行中的对话里用户停掉监听：结束行等这一轮结束再补，不把正在执行的一轮切开。 */
    private val deferredMonitorRows = LinkedHashMap<String, MutableList<MonitorEventMessageUi>>()
    /** 事件轮发起前的消息（撤回时连同被收起的推荐追问一起恢复）与它插入的行。 */
    private val monitorTurnLaunches = mutableMapOf<String, MonitorTurnLaunch>()
    /** 已收到 RunStarted 的运行：运行时确实在执行这一轮，才能把事件并入它。 */
    private val startedRunIds = mutableSetOf<String>()
    /** 已经开始干活（模型有输出、调了工具）的运行：事件轮被取消时不再撤回。 */
    private val runsWithModelOutput = mutableSetOf<String>()
    /** 为用户发起的运行让路而撤下的事件轮（不是用户点的停止）。 */
    private val preemptedMonitorRuns = mutableSetOf<String>()
    /** 重放事件期间暂存的监听行：按 id 复用原来的行（带退出码等），只调整位置。 */
    private var replayMonitorRows: Map<String, MonitorEventMessageUi> = emptyMap()
    private var monitorRetryJob: Job? = null
    private var monitorRetryDelayMs = MONITOR_RETRY_MIN_MS
    private var monitorPersistJob: Job? = null

    private data class MonitorTurnLaunch(val rowIds: Set<String>, val preLaunchMessages: List<AgentChatMessageUi>)
    /** 已在对话里插了「已结束任务」那一行、还在等待撤销期满的结束（[MonitorRegistry.endings]）。 */
    private val shownEndings = mutableMapOf<String, io.github.fartown.movo.agent.monitor.MonitorEnding>()
    /** 每一轮发起的时刻：这一轮里开了监听才算「这个监听任务的一轮」（■ 按归属结束）。 */
    private val runLaunchedAtMillis = mutableMapOf<String, Long>()

    init {
        refreshConversationSummaries()
        observeRuntimeSelection()
        scope.launch {
            snapshotFlow { voiceRuntimeBusy }.distinctUntilChanged().collect { busy ->
                if (!busy) {
                    // 用户排队的消息先发，事件轮在它之后。
                    drainQueuedText()
                    pumpMonitorNotices()
                }
            }
        }
        // 后台监听改为事件驱动：编辑结束、排队消息发出、模型切换完成、语音结束时再看一次有没有等着的事件。
        scope.launch {
            snapshotFlow {
                Triple(
                    homeState.messageEdit != null || conversationsById.values.any { it.messageEdit != null },
                    queuedTextSubmission != null,
                    modelPickerState.isChanging,
                )
            }.distinctUntilChanged().collect { pumpMonitorNotices() }
        }
        scope.launch {
            voiceSession.state.map { it.active }.distinctUntilChanged().collect { active ->
                if (!active) pumpMonitorNotices()
            }
        }
        MonitorRegistry.sink = MonitorNoticeSink { notice -> onMonitorNotice(notice) }
        recordInterruptedMonitors()
        scope.launch { MonitorRegistry.endings.collect(::onMonitorEndings) }
        scope.launch {
            RootAccess.state.collectLatest { refreshPermissionHealth() }
        }
        runtimeRecoveryInProgress.set(true)
        scope.launch(Dispatchers.IO) {
            try {
                recoverRuntimeState()
            } finally {
                runtimeRecoveryInProgress.set(false)
            }
        }
    }

    /**
     * 服务商、所选模型或 ChatGPT 登录变化时算好的模型配置，开跑时直接用；输入一变先清空，算好前现读。
     * 现读一次要把服务商表读三遍、再写一次选择，电视上约 0.4 s。
     */
    @Volatile private var runtimeConfigCache: AgentModelClient.ModelConfig? = null

    private fun observeRuntimeSelection() {
        scope.launch(Dispatchers.IO) {
            combine(
                RuntimeConfigRepository.selectedProviderIdFlow(),
                RuntimeConfigRepository.selectedModelIdFlow(),
                ProviderRepository.providersFlow(),
                ChatGptAuth.accountState,
            ) { providerId, modelId, providers, chatGpt ->
                PickerInputs(providerId, modelId, providers, chatGpt.loggedIn)
            }
                .distinctUntilChanged()
                .collectLatest { (providerId, modelId, providers, chatGptLoggedIn) ->
                    runtimeConfigCache = null
                    val pickerState = AgentModelPickerProjector.project(
                        providers = providers,
                        selectedProviderId = providerId,
                        selectedModelId = modelId,
                        chatGptLoggedIn = chatGptLoggedIn,
                    )
                    val runtimeConfig = RuntimeConfigRepository.currentRuntimeConfig()
                    runtimeConfigCache = runtimeConfig
                    val capabilities = runtimeConfig?.reasoningCapabilities
                    withContext(Dispatchers.Main) {
                        modelPickerState = pickerState.copy(
                            isChanging = modelPickerState.isChanging,
                        )
                        applyReasoningCapabilities(capabilities)
                    }
                }
        }
    }

    private fun applyReasoningCapabilities(capabilities: ModelReasoningCapabilities?) {
        currentReasoningCapabilities = capabilities
        val next = homeState.withCurrentReasoningCapabilities()
        val changed = next.reasoningEffort != homeState.reasoningEffort ||
            next.availableReasoningEfforts != homeState.availableReasoningEfforts
        updateCurrentConversation(next)
        if (changed && selectedConversationId != null) persistConversations()
    }

    private fun AgentChatHomeUiState.withCurrentReasoningCapabilities(): AgentChatHomeUiState {
        val normalized = currentReasoningCapabilities?.normalize(reasoningEffort) ?: ReasoningEffort.OFF
        return copy(
            thinkingEnabled = normalized.enablesReasoning,
            reasoningEffort = normalized,
            availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty(),
        )
    }

    fun refreshRuntimeResults() {
        if (!runtimeRecoveryInProgress.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                recoverRuntimeState()
            } finally {
                runtimeRecoveryInProgress.set(false)
            }
        }
    }

    fun refreshMemory() {
        memoryState = memoryState.copy(isLoading = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val snapshot = AgentMemoryRepository.snapshot()
                val enabled = AgentMemoryRepository.isEnabled()
                val contextWindow = RuntimeConfigRepository.currentRuntimeConfig()?.contextWindow
                Triple(snapshot, enabled, AgentMemoryContextBuilder.coreBudgetChars(contextWindow))
            }.fold(
                onSuccess = { (snapshot, enabled, coreBudget) ->
                    withContext(Dispatchers.Main) {
                        // The page reloads on re-entry. Keep an unsaved edit across toolbar,
                        // system, and swipe back instead of replacing it with the disk snapshot.
                        memoryState = memoryState.withLoadedSnapshot(
                            content = snapshot.content,
                            enabled = enabled,
                            coreBudgetChars = coreBudget,
                        )
                    }
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_ui_load_failed") {
                        "Agent memory UI load failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            isLoading = false,
                            notice = appContext.getString(R.string.state_ui_failed_to_read_memory_please_try_again_later_caeaa6),
                        )
                    }
                },
            )
        }
    }

    fun updateMemoryDraft(content: String) {
        memoryState = memoryState.copy(
            draft = content,
            draftBytes = content.toByteArray(Charsets.UTF_8).size,
            notice = null,
        )
    }

    fun setMemoryEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.setEnabled(enabled) }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(enabled = enabled, notice = null)
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_toggle_failed") {
                            "Agent memory setting update failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(notice = appContext.getString(R.string.state_ui_memory_switch_failed_to_save_83b5d6))
                        }
                    },
                )
        }
    }

    fun saveMemory() {
        if (!memoryState.canSave) return
        val target = memoryState.draft
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceAll(target) }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                savedContent = snapshot.content,
                                draft = if (memoryState.draft == target) {
                                    snapshot.content
                                } else {
                                    memoryState.draft
                                },
                                draftBytes = memoryState.draft.toByteArray(Charsets.UTF_8).size,
                                notice = appContext.getString(R.string.state_ui_memory_saved_a2c61c),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_save_failed") {
                            "Agent memory UI save failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_save_failed_1f501e),
                            )
                        }
                    },
                )
        }
    }

    fun clearMemory() {
        if (memoryState.isSaving) return
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceAll("") }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                draft = "",
                                savedContent = "",
                                draftBytes = 0,
                                notice = appContext.getString(R.string.state_ui_memory_cleared_b415bb),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_clear_failed") {
                            "Agent memory UI clear failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_clearing_failed_7f0aba),
                            )
                        }
                    },
                )
        }
    }

    fun dismissMemoryNotice() {
        memoryState = memoryState.copy(notice = null)
    }

    suspend fun exportBackup(output: OutputStream): MovoBackupSummary =
        MovoBackupRepository.export(appContext, output)

    suspend fun importBackup(input: InputStream): MovoBackupSummary {
        val locallyBusy = withContext(Dispatchers.Main.immediate) {
            currentRunId != null || conversationsById.values.any { it.isStreaming }
        }
        if (locallyBusy) {
            throw IllegalStateException("请先停止正在运行的 Agent 任务")
        }

        val activeRunQuery = withContext(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).queryActiveRun()
        }
        when (val active = activeRunQuery) {
            is AgentRuntimeClient.ActiveRunQuery.Known -> {
                if (active.runId != null) {
                    throw IllegalStateException("请先停止正在运行的 Agent 任务")
                }
            }
            AgentRuntimeClient.ActiveRunQuery.Unavailable -> {
                throw IllegalStateException("无法确认 Agent Runtime 状态，请稍后重试")
            }
        }

        conversationRepository.flush()
        val summary = MovoBackupRepository.import(appContext, input)
        reloadConversationsAfterBackup()
        return summary
    }

    private suspend fun reloadConversationsAfterBackup() {
        conversationRepository.invalidateCaches().await()
        val snapshot = withContext(Dispatchers.IO) { conversationRepository.startup() }
        withContext(Dispatchers.Main.immediate) {
            io.github.fartown.movo.ui.components.AgentConversationDraftStore.shared.clear()
            selectedConversationId = snapshot.selectedConversationId
            conversationsById = snapshot.selected?.let { mapOf(it.id to it.state) }.orEmpty()
            conversationTitles = snapshot.summaries.associate { it.id to it.title }
            conversationUpdatedAt = snapshot.summaries.associate { it.id to it.updatedAt }
            storedConversationIds = snapshot.summaries.mapTo(LinkedHashSet()) { it.id }
            storedPreviews = snapshot.summaries.associateBy { it.id }
            persistedStates.clear()
            persistedMeta.clear()
            snapshot.selected?.let { persistedStates[it.id] = it.state; persistedMeta[it.id] = it.title to it.updatedAt }
            persistedSelection = snapshot.selectedConversationId
            contentMatchCache.clear()
            fileAttachmentOwnerVersion += 1
            homeState = selectedConversationId
                ?.let(conversationsById::get)
                ?.withCurrentReasoningCapabilities()
                ?: emptyChatState(defaultThinkingEnabled).withCurrentReasoningCapabilities()
            conversationPaneState = conversationPaneState.copy(
                selectedConversationId = selectedConversationId,
                searchQuery = "",
            )
            refreshConversationSummaries()
            // 导入后不存在的对话：它们的后台监听没人管了，停掉；排着的事件一起丢掉。
            MonitorRegistry.stopOrphans(storedConversationIds)
            monitorQueue.retainConversations(storedConversationIds)
            deferredMonitorRows.keys.retainAll(storedConversationIds)
        }
    }

    private suspend fun recoverRuntimeState() = runtimeRecoveryMutex.withLock {
        recoverRuntimeRuns()
        importArchivedExternalRuns()
    }

    /** 用 checkpoint、终态 outbox 与 active session 一次性对账，避免用进程存活推断 run 状态。 */
    private suspend fun recoverRuntimeRuns() {
        val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
        val checkpoints = withContext(Dispatchers.IO) {
            AgentRunCheckpointStore.list(appContext)
        }
        val initialCompletedQuery = client.queryCompletedRuns()
        if (initialCompletedQuery is AgentRuntimeClient.CompletedRunsQuery.Unavailable) {
            AndroidAgentLogger.warnThrottled("agent_ui_drain_results_failed") {
                "Agent UI pending result recovery failed"
            }
        }
        val initialCompletedRuns =
            (initialCompletedQuery as? AgentRuntimeClient.CompletedRunsQuery.Known)
                ?.runs
                .orEmpty()
        val activeRunQuery = client.queryActiveRun()
        val terminalRaceQuery = if (
            activeRunQuery is AgentRuntimeClient.ActiveRunQuery.Known && checkpoints.isNotEmpty()
        ) {
            client.queryCompletedRuns()
        } else {
            initialCompletedQuery
        }
        val terminalRaceCompletedRuns =
            (terminalRaceQuery as? AgentRuntimeClient.CompletedRunsQuery.Known)
                ?.runs
                .orEmpty()
        val completedRuns = (initialCompletedRuns + terminalRaceCompletedRuns)
            .associateBy { completed ->
                completed.result.runId.ifBlank { completed.handoff.id }
            }
            .values
            .toList()
        val activeStateKnown = activeRunQuery is AgentRuntimeClient.ActiveRunQuery.Known
        val terminalStateKnown = terminalRaceQuery is AgentRuntimeClient.CompletedRunsQuery.Known
        val activeRunId = (activeRunQuery as? AgentRuntimeClient.ActiveRunQuery.Known)?.runId
        val locallyObservedRunId = withContext(Dispatchers.Main) { currentRunId }
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = checkpoints,
            completedRuns = completedRuns,
            activeStateKnown = activeStateKnown,
            terminalStateKnown = terminalStateKnown,
            activeRunId = activeRunId,
            locallyObservedRunId = locallyObservedRunId,
        )
        // 恢复要改的对话可能不在内存里：先读进来。
        ensureLoaded(buildList {
            plan.completed.forEach { add(AgentUiHandoffPayload.from(it.result.handoff.payload).conversationId) }
            plan.interrupted.forEach { add(AgentUiHandoffPayload.from(it.handoff.payload).conversationId) }
            plan.reattach?.let { add(AgentUiHandoffPayload.from(it.handoff.payload).conversationId) }
            if (activeStateKnown && terminalStateKnown) addAll(conversationRepository.conversationIdsWithPendingRewrites())
        })
        val orphanRewrites = if (activeStateKnown && terminalStateKnown) withContext(Dispatchers.Main) {
            val observed = checkpoints.mapTo(mutableSetOf()) { it.runId }.apply {
                addAll(completedRuns.map { it.result.runId })
                activeRunId?.let(::add)
                currentRunId?.let(::add)
            }
            conversationsById.flatMap { (id, state) ->
                state.roleplayMessages.pendingRewrites.keys.filterNot { it in observed }.map { id to it }
            }
        } else emptyList()
        if (
            plan.completed.isEmpty() &&
            plan.interrupted.isEmpty() &&
            plan.reattach == null && orphanRewrites.isEmpty()
        ) {
            return
        }

        val voiceRecovered = mutableMapOf<String, AgentRuntimeWire.RunResult>()
        val acknowledgeAfterSave = mutableListOf<String>()
        val removeAfterSave = mutableListOf<String>()
        val changed = withContext(Dispatchers.Main) {
            var stateChanged = false
            plan.completed.forEach { recoveryPlan ->
                val completedRun = recoveryPlan.result
                val runId = completedRun.result.runId.ifBlank { completedRun.handoff.id }
                val payload = AgentUiHandoffPayload.from(completedRun.handoff.payload)
                val conversationId = payload.conversationId
                val state = conversationsById[conversationId] ?: return@forEach
                recoveryPlan.checkpoint?.let { checkpoint ->
                    stateChanged = restoreCheckpointTrace(
                        checkpoint = checkpoint,
                        interrupted = false,
                    ) || stateChanged
                }
                val result = completedRun.result
                if (voiceRunListeners.containsKey(runId)) voiceRecovered[runId] = result
                val recovery = AgentPendingResultRecovery.apply(
                    state = conversationsById[conversationId] ?: state,
                    runId = runId,
                    result = result,
                    promptSupplement = payload.promptSupplement,
                    supplements = payload.supplements,
                )
                if (recovery.alreadyApplied) {
                    acknowledgeAfterSave += runId
                    return@forEach
                }
                updateConversation(conversationId, recovery.state)
                acknowledgeAfterSave += runId
                stateChanged = true
            }

            plan.interrupted.forEach { checkpoint ->
                removeAfterSave += checkpoint.runId
                if (voiceRunListeners.containsKey(checkpoint.runId)) voiceRecovered[checkpoint.runId] =
                    AgentRuntimeWire.RunResult(checkpoint.runId, false, "", "任务连接已中断，已保留执行记录，请检查已完成的操作")
                stateChanged = restoreCheckpointTrace(
                    checkpoint = checkpoint,
                    interrupted = true,
                ) || stateChanged
            }
            orphanRewrites.forEach { (conversationId, runId) ->
                conversationsById[conversationId]?.let { state ->
                    updateConversation(conversationId, RoleplayConversationReducer.applyRewrite(state, runId,
                        AgentRuntimeWire.RunResult(runId, false, "", "重新生成已中断，原回复已保留。",
                            operation = AgentRuntimeWire.OP_REWRITE_REPLY)))
                    stateChanged = true
                }
            }
            if (stateChanged) refreshConversationSummaries()
            stateChanged || acknowledgeAfterSave.isNotEmpty() || removeAfterSave.isNotEmpty()
        }

        if (changed) {
            val saved = withContext(Dispatchers.Main) { persistConversations() }.await()
            if (saved) {
                acknowledgeAfterSave.forEach(client::ackResult)
                removeAfterSave.forEach { runId ->
                    AgentRunCheckpointStore.remove(appContext, runId)
                }
                withContext(Dispatchers.Main) {
                    voiceRecovered.forEach { (id, result) -> voiceRunListeners.remove(id)?.onResult?.invoke(result) }
                }
            }
        }

        plan.reattach?.let { checkpoint ->
            withContext(Dispatchers.Main) { startReattachedRun(checkpoint) }
        }
    }

    /** 把安全事件恢复为 UI 轨迹；半截回复不进入模型 history，设备工具也不会重放。 */
    private fun restoreCheckpointTrace(
        checkpoint: AgentRunCheckpointStore.Checkpoint,
        interrupted: Boolean,
    ): Boolean {
        val runId = checkpoint.runId
        if (runId.isBlank()) return false
        val conversationId = AgentUiHandoffPayload
            .from(checkpoint.handoff.payload)
            .conversationId
        val existing = conversationsById[conversationId] ?: return false
        if (AgentRuntimeHistoryReducer.wasApplied(existing, runId)) return false
        if (checkpoint.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
            val restored = RoleplayConversationReducer.restorePendingRewrite(existing, runId, checkpoint.rewriteTargetMessageId)
            if (interrupted) updateConversation(conversationId, RoleplayConversationReducer.applyRewrite(
                restored, runId, AgentRuntimeWire.RunResult(runId, false, "", "重新生成已中断，原回复已保留。",
                    operation = AgentRuntimeWire.OP_REWRITE_REPLY, rewriteTargetMessageId = checkpoint.rewriteTargetMessageId),
            )) else updateConversation(conversationId, restored)
            return interrupted || restored != existing
        }

        runConversationIds[runId] = conversationId
        updateConversation(conversationId, existing.copy(isStreaming = true, isCompacting = checkpoint.operation == AgentRuntimeWire.OP_COMPACT))
        restoreRunEvents(runId, checkpoint.events)
        flushPendingRunDelta(runId)
        updateRunTrace(runId) { messages ->
            val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
            val finalizedText = runMessageProjector.finalizeText(runId, finalizedThinking)
            if (interrupted) {
                val interruptedTools = runMessageProjector.interruptRunningTools(
                    reason = appContext.getString(R.string.system_notice_interrupted),
                    messages = runMessageProjector.finishContextCompaction(runId, finalizedText, "上下文压缩已中断"),
                )
                val noticeId = "interrupted-$runId"
                if (interruptedTools.any { it.id == noticeId }) {
                    interruptedTools
                } else {
                    interruptedTools + SystemNoticeMessageUi(
                        id = noticeId,
                        code = SystemNoticeCode.Interrupted,
                    )
                }
            } else {
                runMessageProjector.finalizeRun(runId, finalizedText)
            }
        }
        if (interrupted && (checkpoint.contextSnapshot != null || checkpoint.transcript.isNotEmpty())) {
            applyConversationHistoryResult(runId, checkpoint.transcript, checkpoint.contextSnapshot, true)
        }
        conversationsById[conversationId]?.let { state ->
            updateConversation(conversationId, RoleplayConversationReducer.linkRun(state, runId))
        }
        setConversationStreaming(runId, false)
        settleMonitorDeliveries(runId, checkpoint.transcript)
        runMessageProjector.clearRun(runId)
        runConversationIds.remove(runId)
        conversationUpdatedAt = conversationUpdatedAt +
            (conversationId to checkpoint.updatedAt)
        return true
    }

    private fun startReattachedRun(checkpoint: AgentRunCheckpointStore.Checkpoint) {
        val runId = checkpoint.runId
        val handoff = AgentUiHandoffPayload.from(checkpoint.handoff.payload)
        val conversationId = handoff.conversationId
        val existing = conversationsById[conversationId] ?: return
        if (currentRunId != null || AgentRuntimeHistoryReducer.wasApplied(existing, runId)) return

        runConversationIds[runId] = conversationId
        currentRunId = runId
        val restored = if (checkpoint.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
            RoleplayConversationReducer.restorePendingRewrite(existing, runId, checkpoint.rewriteTargetMessageId)
        } else existing.copy(messages = AgentPendingResultRecovery.restoreHandoffMessages(
            runId = runId,
            promptSupplement = handoff.promptSupplement,
            supplements = handoff.supplements,
            messages = existing.messages,
        ))
        updateConversation(conversationId, restored.copy(isStreaming = true, isCompacting = checkpoint.operation == AgentRuntimeWire.OP_COMPACT))
        refreshConversationSummaries()
        currentRunJob = scope.launch(Dispatchers.IO) {
            val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
            val outcome = client.attachRun(
                runId = runId,
                onReplay = { events -> restoreRunEvents(runId, events) },
                onEvent = { event -> enqueueRunEvent(runId, event) },
            )
            when (outcome) {
                is AgentRuntimeClient.AttachOutcome.Completed -> withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId = runId,
                        result = outcome.result,
                        acknowledgeRuntimeResult = true,
                        recoveredHandoff = handoff,
                    )
                }
                AgentRuntimeClient.AttachOutcome.NotActive -> {
                    withContext(Dispatchers.Main) {
                        if (currentRunId == runId) {
                            currentRunId = null
                            currentRunJob = null
                            setConversationStreaming(runId, false)
                        }
                    }
                    recoverRuntimeState()
                }
                AgentRuntimeClient.AttachOutcome.Unavailable -> withContext(Dispatchers.Main) {
                    if (currentRunId == runId) {
                        currentRunId = null
                        currentRunJob = null
                        setConversationStreaming(runId, false)
                        refreshConversationSummaries()
                    }
                }
            }
        }
    }

    private suspend fun importArchivedExternalRuns() {
        val archivedRuns = withContext(Dispatchers.IO) {
            AgentRunArchiveStore.list(appContext)
                .filter { AgentExternalArchivePayload.from(it.handoff.payload) != null }
        }
        if (archivedRuns.isEmpty()) return
        ensureLoaded(archivedRuns.mapNotNull { archivedRun ->
            AgentExternalArchivePayload.from(archivedRun.handoff.payload)?.let { payload ->
                archiveConversationId(source = archivedRun.handoff.source, conversationKey = payload.conversationKey)
            }
        })

        withContext(Dispatchers.Main) {
            val importedRunIds = archivedRuns.mapNotNull { archivedRun ->
                importExternalRun(archivedRun)
            }
            refreshConversationSummaries()
            persistConversations {
                importedRunIds.forEach { runId ->
                    AgentRunArchiveStore.remove(appContext, runId)
                }
            }
        }
    }

    suspend fun openResultConversation(target: AgentConversationTarget, requiredRunId: String? = null): Boolean {
        if (target.key.isBlank() || target.source.isBlank()) return false
        // Resume and focus recovery can already be in flight. Await it, then include the latest
        // terminal result before selecting the existing chat; never fall back to a new conversation.
        withContext(Dispatchers.IO) { recoverRuntimeState() }
        val conversationId = if (target.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) target.key
            else archiveConversationId(source = target.source, conversationKey = target.key)
        ensureLoaded(listOf(conversationId))
        return withContext(Dispatchers.Main.immediate) {
            val state = conversationsById[conversationId]
            if (state == null || (requiredRunId != null && !AgentRuntimeHistoryReducer.wasApplied(state, requiredRunId))) {
                false
            } else {
                if (selectedConversationId != conversationId) selectConversation(conversationId)
                true
            }
        }
    }

    private fun importExternalRun(archivedRun: AgentRunArchiveStore.ArchivedRun): String? {
        val runId = archivedRun.result.runId.ifBlank { archivedRun.handoff.id }
        if (runId.isBlank()) return null
        val payload = AgentExternalArchivePayload.from(archivedRun.handoff.payload) ?: return null
        val conversationId = archiveConversationId(
            source = archivedRun.handoff.source,
            conversationKey = payload.conversationKey,
        )
        val archivedEffort = payload.reasoningEffort
            ?: payload.thinkingEnabled?.let(ReasoningEffort::fromLegacy)
            ?: ReasoningEffort.fromLegacy(defaultThinkingEnabled)
        val existingState = conversationsById[conversationId] ?: emptyChatState(
            archivedEffort.enablesReasoning
        ).copy(reasoningEffort = archivedEffort)
        val alreadyImported = AgentRuntimeHistoryReducer.wasApplied(existingState, runId) ||
            existingState.messages.any {
                it is AgentMessageUi &&
                    (it.id == "assistant-$runId" || it.id.startsWith("assistant-$runId-")) &&
                    !it.isStreaming
            }
        if (alreadyImported) return runId

        if (conversationTitles[conversationId].isNullOrBlank()) {
            conversationTitles = conversationTitles + (conversationId to payload.title)
        }
        runConversationIds[runId] = conversationId
        updateConversation(
            conversationId,
            existingState.copy(
                input = "",
                isStreaming = true,
                thinkingEnabled = archivedEffort.enablesReasoning,
                reasoningEffort = archivedEffort,
                pendingImages = emptyList(),
                messages = existingState.messages +
                    UserMessageUi(
                        id = "user-$runId",
                        content = payload.userText,
                        images = archivedRun.userImagePreviews,
                    ) +
                    AgentMessageUi(
                        id = "assistant-$runId",
                        content = "",
                        isStreaming = true,
                        renderMarkdown = false,
                    ),
            )
        )
        archivedRun.events.forEach { event -> applyRunEvent(runId, event) }
        applyRunResult(runId, archivedRun.result)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to archivedRun.createdAt)
        return runId
    }

    fun updateThinkingEnabled(enabled: Boolean) {
        updateReasoningEffort(ReasoningEffort.fromLegacy(enabled))
    }

    fun updateReasoningEffort(effort: ReasoningEffort) {
        val normalized = currentReasoningCapabilities?.normalize(effort) ?: ReasoningEffort.OFF
        updateCurrentConversation(
            homeState.copy(
                thinkingEnabled = normalized.enablesReasoning,
                reasoningEffort = normalized,
            )
        )
        if (selectedConversationId != null) persistConversations()
    }

    fun selectModel(modelId: String) {
        if (
            homeState.isStreaming ||
            modelPickerState.isChanging ||
            modelPickerState.selectedModel?.id == modelId
        ) {
            return
        }
        modelPickerState = modelPickerState.copy(isChanging = true)
        scope.launch(Dispatchers.IO) {
            try {
                RuntimeConfigRepository.setSelectedModelId(modelId)
                RuntimeConfigRepository.syncToRemotePreferences(MovoApp.serviceInstance)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                withContext(Dispatchers.Main) {
                    showComposerNotice(
                        MovoIcons.CircleAlert,
                        appContext.getString(R.string.movo_notice_model_failed_title),
                        appContext.getString(R.string.movo_notice_model_failed_desc),
                    )
                }
            } finally {
                withContext(Dispatchers.Main) {
                    modelPickerState = modelPickerState.copy(isChanging = false)
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        conversationPaneState = conversationPaneState.copy(searchQuery = query)
        refreshConversationSummaries()
    }

    fun selectConversation(conversationId: String) {
        if (conversationId !in conversationsById) {
            // 只在库里：读进来再选（几毫秒到几十毫秒），期间界面停在原来的对话。
            if (conversationId in storedConversationIds) scope.launch {
                ensureLoaded(listOf(conversationId))
                if (conversationId in conversationsById) selectConversation(conversationId)
            }
            return
        }
        io.github.fartown.movo.ui.share.ShareIntake.clear()
        if (homeState.messageEdit != null) cancelMessageEdit()
        val state = conversationsById[conversationId] ?: return
        io.github.fartown.movo.ui.components.AgentConversationDraftStore.shared.clearAssignment()
        fileAttachmentOwnerVersion += 1
        selectedConversationId = conversationId
        val normalized = currentReasoningCapabilities?.normalize(state.reasoningEffort)
            ?: ReasoningEffort.OFF
        val resolvedState = state.copy(
            thinkingEnabled = normalized.enablesReasoning,
            reasoningEffort = normalized,
            availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty(),
        )
        conversationsById = conversationsById + (conversationId to resolvedState)
        homeState = resolvedState
        conversationPaneState = conversationPaneState.copy(selectedConversationId = conversationId)
        persistConversations()
        unloadIdleConversations()
    }

    fun createConversation() {
        io.github.fartown.movo.ui.share.ShareIntake.clear()
        if (homeState.messageEdit != null) cancelMessageEdit()
        io.github.fartown.movo.ui.components.AgentConversationDraftStore.shared.remove(null)
        io.github.fartown.movo.ui.components.AgentConversationDraftStore.shared.clearAssignment()
        fileAttachmentOwnerVersion += 1
        selectedConversationId = null
        homeState = emptyChatState(defaultThinkingEnabled).withCurrentReasoningCapabilities()
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = null,
            searchQuery = "",
        )
        refreshConversationSummaries()
    }

    /**
     * 分享到 Movo（规范 8.9.1）：新建会话，文字预填进输入框，图片与文件作为附件挂上，并请求输入框获焦。
     * 必须先新建会话再挂附件：附件解析回来时会校验会话没被切走。
     */
    fun openSharedContent(content: io.github.fartown.movo.ui.share.SharedContent) {
        createConversation()
        updateCurrentConversation(homeState.copy(input = content.text))
        content.imagePaths.forEach(::attachImage)
        content.filePaths.forEach(::attachFilePath)
        io.github.fartown.movo.ui.share.ShareIntake.intro = io.github.fartown.movo.ui.share.ShareIntro.of(content)
        io.github.fartown.movo.ui.components.ComposerFocusRequest.request()
    }

    fun startCharacterConversation(binding: RoleplayBinding, greeting: String) {
        createConversation()
        val id = newConversationId()
        val greetingId = "greeting-$id"
        val text = CharacterMacros.expand(
            text = greeting, card = CharacterCardCodec.decodeJson(binding.cardSnapshotJson),
            userName = binding.userName, userDescription = binding.userDescription,
        )
        val transcript = if (text.isBlank()) emptyList() else listOf(
            AgentModelClient.ConversationMessage(role = "assistant", content = text, messageId = greetingId),
        )
        selectedConversationId = id
        homeState = emptyChatState(defaultThinkingEnabled).withCurrentReasoningCapabilities().copy(
            roleplay = binding,
            history = transcript,
            journal = transcript,
            messages = if (text.isBlank()) emptyList() else listOf(AgentMessageUi(greetingId, text, characterEditable = true)),
            roleplayMessages = RoleplayMessageState(links = if (text.isBlank()) emptyMap() else mapOf(
                greetingId to RoleplayMessageLink(greetingId),
            )),
        )
        conversationTitles = conversationTitles + (id to binding.characterName)
        updateConversation(id, homeState)
        conversationPaneState = conversationPaneState.copy(selectedConversationId = id, searchQuery = "")
        refreshConversationSummaries()
        persistConversations()
    }

    fun selectReplyCandidate(messageId: String, index: Int) {
        if (homeState.isStreaming || homeState.messageEdit != null) return
        val updated = RoleplayConversationReducer.select(homeState, messageId, index) ?: return
        updateCurrentConversation(updated)
        refreshConversationSummaries()
        persistConversations()
    }

    fun deleteConversation(conversationId: String) {
        // 完整运行日志随对话一起删除。
        io.github.fartown.movo.diagnostics.runlog.RunLog.deleteConversation(conversationId)
        forgetMonitorsOf(conversationId)
        if (voiceSession.ownsConversation(conversationId)) voiceSession.end("对话已删除，语音已结束")
        if (queuedTextSubmission?.conversationId == conversationId) queuedTextSubmission = null
        followUpJobs.remove(conversationId)?.cancel()
        io.github.fartown.movo.ui.components.AgentConversationDraftStore.shared.remove(conversationId)
        val wasSelected = selectedConversationId == conversationId
        conversationsById = conversationsById - conversationId
        conversationTitles = conversationTitles - conversationId
        conversationUpdatedAt = conversationUpdatedAt - conversationId
        forgetStoredConversation(conversationId)
        // 删掉选中的对话后打开最近的那个；它不在内存里就先显示空白，读进来后再选上。
        val nextId = if (!wasSelected) null else (conversationsById.keys + storedConversationIds)
            .maxByOrNull { conversationUpdatedAt[it] ?: 0L }
        if (wasSelected) {
            fileAttachmentOwnerVersion += 1
            if (nextId != null && nextId in conversationsById) {
                selectedConversationId = nextId
                homeState = conversationsById.getValue(nextId).withCurrentReasoningCapabilities()
                conversationsById = conversationsById + (nextId to homeState)
            } else {
                selectedConversationId = null
                homeState = emptyChatState(defaultThinkingEnabled).withCurrentReasoningCapabilities()
            }
        }
        conversationPaneState = conversationPaneState.copy(selectedConversationId = selectedConversationId)
        refreshConversationSummaries()
        persistConversations()
        if (nextId != null && nextId !in conversationsById) selectConversation(nextId)
    }

    fun renameConversation(conversationId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        val now = System.currentTimeMillis()
        conversationTitles = conversationTitles + (conversationId to trimmed)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to now)
        refreshConversationSummaries()
        if (conversationId in conversationsById) persistConversations()
        else if (conversationId in storedConversationIds) conversationRepository.renameConversation(conversationId, trimmed, now)
    }

    suspend fun exportConversationMarkdown(conversationId: String): String? {
        ensureLoaded(listOf(conversationId))
        val state = withContext(Dispatchers.Main.immediate) { conversationsById[conversationId] } ?: return null
        val title = conversationTitles[conversationId]?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.conversation_unnamed)
        return ConversationMarkdownExporter.export(
            title = title,
            messages = state.messages,
            labels = ConversationMarkdownExporter.Labels(
                user = appContext.getString(R.string.conversation_export_user),
                assistant = appContext.getString(R.string.conversation_export_assistant),
                thinking = appContext.getString(R.string.conversation_export_thinking),
                toolLineFormat = appContext.getString(R.string.conversation_export_tool_line),
                toolsLineFormat = appContext.getString(R.string.conversation_export_tools_line),
                argumentsFormat = appContext.getString(R.string.conversation_export_tool_arguments),
                resultFormat = appContext.getString(R.string.conversation_export_tool_result),
                imagesFormat = appContext.getString(R.string.conversation_export_images),
                toolStatusRunning = appContext.getString(R.string.tool_status_running),
                toolStatusSuccess = appContext.getString(R.string.tool_status_success),
                toolStatusFailed = appContext.getString(R.string.tool_status_failed),
                toolStatusUnknown = appContext.getString(R.string.tool_status_unknown),
                noticeStopped = noticeText(SystemNoticeCode.Stopped),
                noticeEmptyResult = noticeText(SystemNoticeCode.EmptyResult),
                noticeModelRetry = noticeText(SystemNoticeCode.ModelRetry),
                noticeContextCompaction = noticeText(SystemNoticeCode.ContextCompaction),
                noticeRuntimeFailed = noticeText(SystemNoticeCode.RuntimeFailed),
                noticeInterrupted = noticeText(SystemNoticeCode.Interrupted),
            ),
        )
    }

    private fun noticeText(code: SystemNoticeCode): String = appContext.getString(
        when (code) {
            SystemNoticeCode.Stopped -> R.string.system_notice_stopped
            SystemNoticeCode.EmptyResult -> R.string.system_notice_empty_result
            SystemNoticeCode.ContextCompaction -> R.string.context_compaction
            SystemNoticeCode.ModelRetry -> R.string.system_notice_model_retry
            SystemNoticeCode.RuntimeFailed -> R.string.system_notice_runtime_failed
            SystemNoticeCode.Interrupted -> R.string.system_notice_interrupted
        },
    )

    /** Voice and text share the same persistent conversation, role binding and preparation path. */
    override fun voiceConversationId(): String {
        // 任务通道与语音通道正交：有一轮在跑也允许开语音，说的话会排队等它结束。
        // 只有上下文压缩和模型切换会改写会话本身，必须等它们结束。
        check(!homeState.isCompacting && !modelPickerState.isChanging) {
            "请等上下文整理和模型切换完成后再开始语音"
        }
        return selectedConversationId ?: newConversationId().also { id ->
            val draft = AgentConversationDraftStore.shared.assignConversation(id, homeState.input)
            homeState = homeState.copy(input = draft.text.toString())
            selectedConversationId = id
            conversationsById = conversationsById + (id to homeState)
            conversationPaneState = conversationPaneState.copy(selectedConversationId = id)
            persistConversations()
        }
    }

    override fun voiceFreshConversationId(): String {
        check(!homeState.isCompacting && !modelPickerState.isChanging) {
            "请等上下文整理和模型切换完成后再开始语音"
        }
        if (selectedConversationId != null && homeState.messages.isNotEmpty() && !voiceRuntimeBusy) createConversation()
        return voiceConversationId()
    }

    override fun retainVoiceDraft(conversationId: String, text: String, images: List<PendingImageUi>) {
        if (text.isBlank()) return
        val state = conversationsById[conversationId] ?: return
        val draft = io.github.fartown.movo.ui.components.AgentConversationDraftStore.shared.get(conversationId, state.input)
        draft.edit { replace(length, length, (if (length > 0) "\n" else "") + text) }
        updateConversation(conversationId, state.copy(input = draft.text.toString(),
            pendingImages = (state.pendingImages + images).distinctBy { it.id }))
        persistConversations()
    }

    /** Inspect only. A rejected submission never consumes an attachment. */
    override fun pendingVoiceImages(conversationId: String): List<PendingImageUi> =
        conversationsById[conversationId]?.pendingImages.orEmpty()

    override fun sendVoiceMessage(
        conversationId: String,
        runId: String,
        prompt: String,
        images: List<PendingImageUi>,
        voiceSessionId: String,
        onEvent: (AgentEvent) -> Unit,
        onResult: (AgentRuntimeWire.RunResult) -> Unit,
    ) {
        val state = conversationsById[conversationId]
        if (voiceRuntimeBusy || state == null || state.isCompacting || modelPickerState.isChanging) {
            // 用户说的话优先：还没开始干活的事件轮让路，语音会话在空闲后重发这一句。
            preemptMonitorRunForUser()
            onResult(AgentRuntimeWire.RunResult(runId, false, "", "当前任务仍在执行，请等完成后再说", resultKind = "rejected"))
            return
        }
        voiceRunListeners[runId] = VoiceRunListener(onEvent, onResult)
        if (conversationTitles[conversationId].isNullOrBlank()) {
            conversationTitles = conversationTitles + (conversationId to prompt.defaultConversationTitle())
        }
        val userMessage = UserMessageUi(id = "user-$runId", content = prompt, images = images.map { it.dataUrl })
        launchConversationRun(
            conversationId = conversationId, runId = runId, prompt = prompt, images = images,
            history = state.history,
            userHistoryMessage = AgentModelClient.buildUserHistoryMessage(prompt, images.toHistoryImages()).copy(messageId = userMessage.id),
            messages = state.messages + userMessage,
            state = state.copy(pendingImages = state.pendingImages.filterNot { candidate -> images.any { it.id == candidate.id } }),
            reasoningEffort = state.reasoningEffort, voiceSessionId = voiceSessionId,
        )
    }

    override fun steerVoiceTurn(conversationId: String, text: String, onResult: (accepted: Boolean) -> Unit) {
        val runId = currentRunId?.takeIf { runId ->
            runConversationIds[runId] == conversationId && conversationsById[conversationId]?.isStreaming == true
        }
        if (runId == null || text.isBlank()) {
            onResult(false)
            return
        }
        runtimeSteerer.steer(runId, text.trim(), onResult)
    }

    /**
     * 把输入框里的文字作为补充交给正在执行的 [runId]；接收后运行时会发回 `UserSupplementReceived`，
     * 由事件投影插入「你的补充」。未被接收（本轮正在收尾或服务不可用）时退回排队，文字不会丢。
     */
    private fun steerCurrentRun(runId: String, prompt: String, submittedText: String?) {
        val conversationId = selectedConversationId ?: return
        val remainder = AgentConversationDraftStore.shared.consume(conversationId, submittedText ?: homeState.input)
        updateConversation(conversationId, homeState.copy(input = remainder))
        runtimeSteerer.steer(runId, prompt) { accepted ->
            if (accepted) return@steer
            run {
                val state = conversationsById[conversationId] ?: return@run
                if (queuedTextSubmission == null && voiceRuntimeBusy) {
                    queuedTextSubmission = QueuedTextSubmission(conversationId, prompt, emptyList(), emptyList())
                    persistConversations()
                } else {
                    // 这一轮已经结束，或已有一条排队：放回输入框（与撤回排队同一写法），文字不丢。
                    val draft = AgentConversationDraftStore.shared.get(conversationId, state.input)
                    draft.edit { replace(0, 0, prompt + if (length > 0) "\n" else "") }
                    updateConversation(conversationId, state.copy(input = draft.text.toString()))
                    if (voiceRuntimeBusy) showQueuedConflictNotice()
                }
            }
        }
    }

    /** 继续在悬浮球里暂停的这一轮（App 内主按钮 ▶）。 */
    fun resumeCurrentRun() {
        val runId = currentRunId ?: return
        scope.launch(Dispatchers.IO) { AgentRuntimeClient(appContext, AndroidAgentLogger).resumeRun(runId) }
    }

    fun sendCurrentMessage(submittedText: String? = null) {
        if (homeState.isCompacting || modelPickerState.isChanging) return
        val prompt = (submittedText ?: homeState.input).trim()
        val pendingImages = homeState.pendingImages
        val pendingFileReferences = homeState.pendingFileReferences
        if (
            (prompt.isBlank() && pendingImages.isEmpty() && pendingFileReferences.isEmpty())
        ) {
            return
        }
        homeState.messageEdit?.takeIf { it.preserveFollowingMessages }?.let { edit ->
            val updated = RoleplayConversationReducer.edit(homeState, edit.targetMessageId, prompt) ?: return
            updateCurrentConversation(updated.copy(
                input = edit.previousInput,
                pendingImages = edit.previousImages,
                pendingFileReferences = edit.previousFileReferences,
                messageEdit = null,
            ))
            refreshConversationSummaries()
            persistConversations()
            return
        }
        val fileReferences = pendingFileReferences.map { it.reference }
        if (
            !AgentFileReferencePolicy.canSend(
                references = fileReferences,
                terminalToolsEnabled = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            )
        ) {
            showComposerNotice(
                MovoIcons.Terminal,
                appContext.getString(R.string.movo_notice_file_tools_title),
                appContext.getString(R.string.movo_notice_file_tools_desc),
            )
            return
        }
        // 规范 8.4：执行中再发的话不排队、不开新任务，作为补充交给当前任务（下一步生效）。
        // 只适用于当前会话自己的这一轮、纯文字、不在编辑；带附件或补充未被接收时按原来的排队处理。
        val steerRunId = currentRunId?.takeIf { runId ->
            homeState.isStreaming &&
                runConversationIds[runId] == selectedConversationId &&
                pendingImages.isEmpty() &&
                pendingFileReferences.isEmpty() &&
                homeState.messageEdit == null &&
                prompt.isNotBlank()
        }
        if (steerRunId != null) {
            steerCurrentRun(steerRunId, prompt, submittedText)
            return
        }
        if (voiceRuntimeBusy) {
            if (queuedTextSubmission != null || homeState.messageEdit != null) {
                showQueuedConflictNotice()
                return
            }
            val id = voiceConversationId()
            val remainder = AgentConversationDraftStore.shared.consume(id, submittedText ?: homeState.input)
            queuedTextSubmission = QueuedTextSubmission(id, prompt, pendingImages, pendingFileReferences)
            updateConversation(id, homeState.copy(input = remainder, pendingImages = emptyList(), pendingFileReferences = emptyList()))
            persistConversations()
            // 用户发起的运行优先于事件轮：还没开始干活的事件轮撤回，这条排队的消息先跑。
            preemptMonitorRunForUser()
            return
        }
        val runtimePrompt = AgentFileReferencePromptCodec.format(prompt, fileReferences)
        // 真正发出：分享进来的那条会话已经开始，来源提示与快捷建议不再显示（规范 8.9.1）。
        io.github.fartown.movo.ui.share.ShareIntake.clear()

        val edit = homeState.messageEdit

        val editBoundary = edit?.let {
            AgentConversationRevisionReducer.boundary(homeState, it.targetMessageId)
        }
        if (edit != null && editBoundary == null) {
            cancelMessageEdit()
            return
        }
        val remainingDraft = AgentConversationDraftStore.shared.consume(selectedConversationId, submittedText ?: homeState.input)

        val conversationId = selectedConversationId ?: newConversationId().also {
            AgentConversationDraftStore.shared.assignConversation(it, remainingDraft)
            selectedConversationId = it
        }
        val runId = "run-${UUID.randomUUID()}"
        val userMessage = UserMessageUi(
            id = editBoundary?.userMessage?.id ?: "user-$runId",
            content = runtimePrompt,
            images = pendingImages.map { it.dataUrl },
            isEdited = editBoundary != null,
        )
        val history = editBoundary?.historyPrefix ?: homeState.history
        val messages = if (editBoundary == null) {
            homeState.messages + userMessage
        } else {
            homeState.messages.take(editBoundary.userMessageIndex) + userMessage
        }
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
            text = runtimePrompt,
            images = pendingImages.toHistoryImages(),
        ).copy(messageId = userMessage.id)

        val currentTitle = conversationTitles[conversationId]
        val oldAutoTitle = editBoundary
            ?.takeIf { it.userMessageIndex == 0 }
            ?.userMessage
            ?.content
            ?.defaultConversationTitleFromMessage()
        val nextAutoTitle = defaultConversationTitle(prompt, fileReferences)
        val title = if (
            editBoundary?.userMessageIndex == 0 &&
            (currentTitle == oldAutoTitle || currentTitle.isNullOrBlank())
        ) {
            nextAutoTitle
        } else {
            currentTitle?.takeIf(String::isNotBlank) ?: nextAutoTitle
        }

        conversationTitles = conversationTitles + (conversationId to title)
        conversationPaneState = conversationPaneState.copy(selectedConversationId = conversationId)
        launchConversationRun(
            conversationId = conversationId,
            runId = runId,
            prompt = runtimePrompt,
            images = pendingImages,
            history = history,
            userHistoryMessage = userHistoryMessage,
            messages = messages,
            state = homeState.copy(
                journal = editBoundary?.journalPrefix ?: homeState.journal,
                input = remainingDraft,
                pendingImages = emptyList(),
                pendingFileReferences = emptyList(),
                messageEdit = null,
            ),
            reasoningEffort = homeState.reasoningEffort,
        )
    }

    /** Remove a waiting request; editing restores its text and attachments without touching the active run. */
    fun withdrawQueuedText(edit: Boolean) {
        val queued = queuedTextSubmission ?: return
        queuedTextSubmission = null
        if (edit) {
            val state = conversationsById[queued.conversationId] ?: return
            val draft = AgentConversationDraftStore.shared.get(queued.conversationId, state.input)
            draft.edit { replace(0, 0, queued.text + if (length > 0) "\n" else "") }
            updateConversation(queued.conversationId, state.copy(
                input = draft.text.toString(),
                pendingImages = (queued.images + state.pendingImages).distinctBy { it.id },
                pendingFileReferences = (queued.files + state.pendingFileReferences).distinctBy { it.id },
            ))
        }
        persistConversations()
    }

    internal fun drainQueuedText() {
        if (voiceRuntimeBusy) return
        val queued = queuedTextSubmission ?: return
        val state = conversationsById[queued.conversationId] ?: run { queuedTextSubmission = null; return }
        if (!AgentFileReferencePolicy.canSend(queued.files.map { it.reference },
                agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS))) {
            withdrawQueuedText(edit = true)
            return
        }
        queuedTextSubmission = null
        val prompt = AgentFileReferencePromptCodec.format(queued.text, queued.files.map { it.reference })
        val runId = "run-${UUID.randomUUID()}"
        val message = UserMessageUi("user-$runId", prompt, queued.images.map { it.dataUrl })
        launchConversationRun(
            conversationId = queued.conversationId, runId = runId, prompt = prompt, images = queued.images,
            history = state.history,
            userHistoryMessage = AgentModelClient.buildUserHistoryMessage(prompt, queued.images.toHistoryImages()).copy(messageId = message.id),
            messages = state.messages + message, state = state, reasoningEffort = state.reasoningEffort,
        )
    }

    fun beginMessageEdit(messageId: String) {
        if (homeState.isStreaming || homeState.messageEdit != null) return
        if (homeState.roleplay != null) {
            if (messageId !in homeState.roleplayMessages.links) return
            val content = when (val message = homeState.messages.firstOrNull { it.id == messageId }) {
                is UserMessageUi -> message.content
                is AgentMessageUi -> message.content
                else -> return
            }
            updateCurrentConversation(homeState.copy(
                input = content, pendingImages = emptyList(), pendingFileReferences = emptyList(),
                messageEdit = MessageEditUiState(messageId, io.github.fartown.movo.ui.components.AgentConversationDraftStore.shared.get(selectedConversationId, homeState.input).text.toString(), homeState.pendingImages,
                    homeState.pendingFileReferences, hasLaterTurns = false, preserveFollowingMessages = true),
            ))
            return
        }
        val boundary = AgentConversationRevisionReducer.boundary(homeState, messageId) ?: return
        // 事件轮没有用户原话：不能编辑 / 重新生成。
        val turnMessage = boundary.userMessage ?: return
        val images = turnMessage.images.mapIndexed { index, dataUrl ->
            PendingImageUi(
                id = "edit-${turnMessage.id}-$index",
                uri = dataUrl,
                dataUrl = dataUrl,
                mimeType = dataUrl.imageMimeType(),
            )
        }
        val parsedPrompt = AgentFileReferencePromptCodec.parse(turnMessage.content)
        val fileReferences = parsedPrompt.references.mapIndexed { index, reference ->
            PendingFileReferenceUi(
                id = "edit-${turnMessage.id}-file-$index",
                reference = reference,
            )
        }
        updateCurrentConversation(
            homeState.copy(
                input = parsedPrompt.request,
                pendingImages = images,
                pendingFileReferences = fileReferences,
                messageEdit = MessageEditUiState(
                    targetMessageId = turnMessage.id,
                    previousInput = io.github.fartown.movo.ui.components.AgentConversationDraftStore.shared.get(selectedConversationId, homeState.input).text.toString(),
                    previousImages = homeState.pendingImages,
                    previousFileReferences = homeState.pendingFileReferences,
                    hasLaterTurns = boundary.laterTurnCount > 0,
                ),
            )
        )
    }

    fun cancelMessageEdit() {
        val edit = homeState.messageEdit ?: return
        updateCurrentConversation(
            homeState.copy(
                input = edit.previousInput,
                pendingImages = edit.previousImages,
                pendingFileReferences = edit.previousFileReferences,
                messageEdit = null,
            )
        )
    }

    fun messageRevisionImpact(messageId: String): MessageRevisionImpact? =
        if (homeState.roleplay != null && messageId.startsWith("greeting-")) MessageRevisionImpact(0)
        else AgentConversationRevisionReducer.boundary(homeState, messageId)?.let { boundary ->
            MessageRevisionImpact(laterTurnCount = boundary.laterTurnCount)
        }

    fun deleteMessageTurn(messageId: String) {
        if (homeState.isStreaming || homeState.messageEdit != null) return
        val conversationId = selectedConversationId ?: return
        if (homeState.roleplay != null && messageId.startsWith("greeting-")) {
            val originalId = homeState.roleplayMessages.links[messageId]?.transcriptMessageId ?: return
            val updated = homeState.copy(
                messages = homeState.messages.filterNot { it.id == messageId },
                journal = homeState.journal.filterNot { it.messageId == originalId },
                history = homeState.history.filterNot { it.messageId == originalId || it.contextSummary },
                roleplayMessages = homeState.roleplayMessages.copy(
                    links = homeState.roleplayMessages.links - messageId,
                    revisions = homeState.roleplayMessages.revisions - messageId,
                ),
            )
            updateConversation(conversationId, updated.copy(history = RoleplayConversationReducer.projectJournal(updated)))
            refreshConversationSummaries()
            persistConversations()
            return
        }
        val removed = AgentConversationRevisionReducer.deleteFromTurn(homeState, messageId) ?: return
        val retainedIds = removed.messages.mapTo(mutableSetOf()) { it.id }
        val revised = if (removed.roleplay == null) removed else removed.copy(roleplayMessages = removed.roleplayMessages.copy(
            links = removed.roleplayMessages.links.filterKeys { it in retainedIds },
            revisions = removed.roleplayMessages.revisions.filterKeys { it in retainedIds },
        ))
        if (revised.messages.isEmpty()) {
            // 删光所有轮次 = 对话被移除：它的后台监听一起停掉，不留没人管的监听。
            forgetMonitorsOf(conversationId)
            conversationsById = conversationsById - conversationId
            conversationTitles = conversationTitles - conversationId
            conversationUpdatedAt = conversationUpdatedAt - conversationId
            forgetStoredConversation(conversationId)
            fileAttachmentOwnerVersion += 1
            selectedConversationId = null
            homeState = emptyChatState(defaultThinkingEnabled).withCurrentReasoningCapabilities()
            conversationPaneState = conversationPaneState.copy(selectedConversationId = null)
            refreshConversationSummaries()
            persistConversations()
            return
        }
        updateConversation(conversationId, revised)
        refreshConversationSummaries()
        persistConversations()
    }

    fun regenerateMessage(messageId: String) {
        if (homeState.isStreaming || homeState.messageEdit != null) return
        val conversationId = selectedConversationId ?: return
        if (homeState.roleplay != null) {
            val target = homeState.messages.filterIsInstance<AgentMessageUi>().firstOrNull { it.id == messageId } ?: return
            val prefix = RoleplayConversationReducer.rewriteHistory(homeState, messageId) ?: return
            val runId = "run-${UUID.randomUUID()}"
            launchConversationRun(
                conversationId, runId, target.content, emptyList(), prefix, null, homeState.messages,
                homeState.copy(roleplayMessages = homeState.roleplayMessages.copy(
                    pendingRewrites = homeState.roleplayMessages.pendingRewrites + (runId to messageId),
                )), homeState.reasoningEffort, operation = AgentRuntimeWire.OP_REWRITE_REPLY,
                rewriteTargetMessageId = messageId,
            )
            return
        }
        // 回到这一轮里用户自己说的话（跳过运行中并入的监听事件行）；事件轮没有用户原话，界面上也不提供这个按钮。
        val boundary = AgentConversationRevisionReducer.regenerationBoundary(homeState, messageId) ?: return
        val turnMessage = boundary.userMessage ?: return
        val images = turnMessage.images.mapIndexed { index, dataUrl ->
            PendingImageUi(
                id = "regenerate-${turnMessage.id}-$index",
                uri = dataUrl,
                dataUrl = dataUrl,
                mimeType = dataUrl.imageMimeType(),
            )
        }
        val runId = "run-${UUID.randomUUID()}"
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
            text = turnMessage.content,
            images = images.toHistoryImages(),
        )
        launchConversationRun(
            conversationId = conversationId,
            runId = runId,
            prompt = turnMessage.content,
            images = images,
            history = boundary.historyPrefix,
            userHistoryMessage = userHistoryMessage,
            messages = homeState.messages.take(boundary.userMessageIndex + 1),
            state = homeState.copy(journal = boundary.journalPrefix),
            reasoningEffort = homeState.reasoningEffort,
        )
    }

    fun compactCurrentContext() {
        val conversationId = selectedConversationId ?: return
        if (currentRunId != null || !homeState.canCompactContext || modelPickerState.isChanging) return
        launchConversationRun(
            conversationId = conversationId,
            runId = java.util.UUID.randomUUID().toString(),
            prompt = "", images = emptyList(), history = homeState.history,
            userHistoryMessage = null, messages = homeState.messages, state = homeState,
            reasoningEffort = homeState.reasoningEffort, operation = AgentRuntimeWire.OP_COMPACT,
        )
    }

    private fun launchConversationRun(
        conversationId: String,
        runId: String,
        prompt: String,
        images: List<PendingImageUi>,
        history: List<AgentModelClient.ConversationMessage>,
        userHistoryMessage: AgentModelClient.ConversationMessage?,
        messages: List<AgentChatMessageUi>,
        state: AgentChatHomeUiState,
        reasoningEffort: ReasoningEffort,
        operation: String = AgentRuntimeWire.OP_CHAT,
        rewriteTargetMessageId: String? = null,
        voiceSessionId: String = "",
        origin: String = "",
    ) {
        runConversationIds[runId] = conversationId
        runLaunchedAtMillis[runId] = System.currentTimeMillis()
        currentRunId = runId
        // 新一轮开始：上一轮的推荐追问连同还在路上的请求一起作废。
        followUpJobs.remove(conversationId)?.cancel()

        updateConversation(
            conversationId,
            state.copy(
                isStreaming = true,
                history = if (operation == AgentRuntimeWire.OP_REWRITE_REPLY) state.history else history + listOfNotNull(userHistoryMessage),
                journal = state.journal.ifEmpty { state.history } + listOfNotNull(userHistoryMessage),
                isCompacting = operation == AgentRuntimeWire.OP_COMPACT,
                messages = AgentFollowUpSuggestions.strip(messages).let { stripped ->
                    if (operation == AgentRuntimeWire.OP_CHAT) stampTurnStarted(stripped) else stripped
                },
                messageEdit = null,
            )
        )
        refreshConversationSummaries()
        val persistStartedAt = android.os.SystemClock.elapsedRealtime()
        val initialPersistence = persistConversations()

        val preparationJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            // 先把用户这句话落库再开跑（write-ahead）：只写这一轮新增的几行，几毫秒；进程被杀也不丢已发出的请求。
            val persisted = initialPersistence.await()
            io.github.fartown.movo.diagnostics.MemoryDiagnostics.record("app", "run.persisted", fields = mapOf(
                "duration_ms" to android.os.SystemClock.elapsedRealtime() - persistStartedAt,
                "messages" to state.messages.size, "ok" to persisted))
            if (!persisted) {
                withContext(Dispatchers.Main) {
                    applyRunResult(runId, AgentRuntimeWire.RunResult(runId = runId, ok = false, content = "",
                        error = appContext.getString(R.string.conversation_persistence_failed)))
                }
                return@launch
            }
            state.roleplay?.let { binding ->
                try {
                    CharacterRepository.initialize(appContext)
                    CharacterRepository.get(binding.characterId)?.let { profile ->
                        val updatedBinding = binding.copy(
                            cardSnapshotJson = CharacterCardCodec.encodeJson(profile.card),
                            characterName = profile.card.name, avatarPath = profile.avatarPath,
                        )
                        val saved = withContext(Dispatchers.Main) {
                            conversationsById[conversationId]?.let { current ->
                                updateConversation(conversationId, current.copy(roleplay = updatedBinding))
                            }
                            persistConversations()
                        }.await()
                        check(saved) { "无法保存角色会话设定" }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    AndroidAgentLogger.warnThrottled("character_run_prepare_failed") {
                        "Character preparation failed: type=${failure.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        applyRunResult(runId, AgentRuntimeWire.RunResult(runId, false, "", "无法读取或保存角色设定，请重试。",
                            operation = operation, rewriteTargetMessageId = rewriteTargetMessageId))
                    }
                    return@launch
                }
            }
            val permittedReasoningEffort = if (
                agentBooleanForUi(Prefs.Keys.AGENT_THINKING_ENABLED)
            ) {
                reasoningEffort
            } else {
                ReasoningEffort.OFF
            }
            val config = (runtimeConfigCache ?: RuntimeConfigRepository.currentRuntimeConfig())?.copy(
                terminalTools = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                browserTools = agentBooleanForUi(Prefs.Keys.AGENT_BROWSER_TOOLS),
                deviceDirectTools = agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
                deviceSensitiveReadTools =
                    agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
                deviceSensitiveActionTools =
                    agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
                thinkingEnabled = permittedReasoningEffort.enablesReasoning,
                reasoningEffort = permittedReasoningEffort,
            )
            if (config == null) {
                withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId,
                        AgentRuntimeWire.RunResult(
                            runId = runId,
                            ok = false,
                            content = "",
                            error = appContext.getString(R.string.state_ui_please_configure_the_model_provider_and_model_fi_a36e15),
                        )
                    )
                }
                return@launch
            }
            val modelImages = images.map { p ->
                AgentModelClient.ModelImage(
                    reference = p.uri,
                    mimeType = p.mimeType,
                    bytes = 0,
                    source = "user_attach",
                )
            }
            // 语音对话靠说不靠点、角色扮演是剧情而非设备动作，这两类不出推荐追问。
            if (operation == AgentRuntimeWire.OP_CHAT && voiceSessionId.isBlank() && state.roleplay == null &&
                origin != AgentRuntimeWire.ORIGIN_MONITOR) {
                followUpRunConfigs[runId] = config
            }
            if (withContext(Dispatchers.Main) { runId in stopRequestedRunIds }) {
                withContext(Dispatchers.Main) {
                    applyRunResult(runId, AgentRuntimeWire.RunResult(runId, false, "", "已停止", operation = operation))
                }
                return@launch
            }
            val result = runInterruptible {
                AgentRuntimeClient(appContext, AndroidAgentLogger).run(
                    request = AgentRuntimeWire.RunRequest(
                        operation = operation,
                        rewriteTargetMessageId = rewriteTargetMessageId,
                        runId = runId,
                        prompt = prompt,
                        config = config,
                        voiceSessionId = voiceSessionId,
                        origin = origin,
                        modelSessionId = conversationId,
                        images = modelImages,
                        history = history,
                        handoff = AgentRuntimeWire.EntryHandoff(
                            id = runId,
                            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                            payload = conversationId,
                            dismissEntrySurfaceOnForegroundOperation = voiceSessionId.isNotBlank(),
                        ),
                    ),
                    onEvent = { event -> enqueueRunEvent(runId, event) },
                )
            }
            withContext(Dispatchers.Main) {
                applyRunResult(runId, result, acknowledgeRuntimeResult = true)
            }
        }
        currentRunJob = preparationJob
        if (!RootAccess.isGranted) {
            val leaseId = "prepare:$runId"
            val acquired = AgentExecutionService.acquire(appContext, leaseId, task = runId) {
                scope.launch(Dispatchers.Main.immediate) {
                    if (currentRunId == runId) stopCurrentRun("app.lease_stop")
                }
            }
            if (!acquired) {
                preparationJob.cancel()
                applyRunResult(runId, AgentRuntimeWire.RunResult(
                    runId = runId,
                    ok = false,
                    content = "",
                    error = appContext.getString(R.string.capability_background_failed),
                ))
                return
            }
            preparationJob.invokeOnCompletion { AgentExecutionService.release(leaseId) }
        }
        preparationJob.start()
    }

    private fun List<PendingImageUi>.toHistoryImages(): List<AgentModelClient.ModelImage> =
        map { image ->
            AgentModelClient.ModelImage(
                reference = image.dataUrl,
                mimeType = image.mimeType,
                bytes = image.dataUrl.length,
                source = image.uri,
            )
        }

    private fun String.imageMimeType(): String =
        takeIf { startsWith("data:") }
            ?.substringAfter("data:")
            ?.substringBefore(';')
            ?.takeIf { it.startsWith("image/") }
            ?: "image/jpeg"

    private fun String.defaultConversationTitle(): String =
        lineSequence().firstOrNull().orEmpty().trim().take(MAX_TITLE_CHARS)

    private fun defaultConversationTitle(
        request: String,
        references: List<AgentFileReference>,
    ): String = AgentFileReferencePolicy
        .titleSource(request, references)
        .defaultConversationTitle()

    private fun String.defaultConversationTitleFromMessage(): String {
        val parsed = AgentFileReferencePromptCodec.parse(this)
        return defaultConversationTitle(parsed.request, parsed.references)
    }

    fun attachImage(uri: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val image = AgentImageCodec.fromReference(
                    context = appContext,
                    value = uri,
                    source = "user_attach",
                )
                if (image == null) {
                    withContext(Dispatchers.Main) {
                        showComposerNotice(
                            MovoIcons.Image,
                            appContext.getString(R.string.movo_notice_image_failed_title),
                            appContext.getString(R.string.movo_notice_image_failed_desc),
                        )
                    }
                    return@launch
                }
                val preview = AgentImageCodec.previewFromReference(appContext, image) ?: image
                val pending = PendingImageUi(
                    id = "img-${UUID.randomUUID()}",
                    // 后续发送使用首次读取后的稳定引用，不再依赖 ROM Photo Picker URI 的授权生命周期。
                    uri = image.reference,
                    dataUrl = preview.reference,
                    mimeType = image.mimeType,
                )
                withContext(Dispatchers.Main) {
                    updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages + pending))
                }
            } finally {
                val selectedUri = Uri.parse(uri)
                if (selectedUri.scheme == ContentResolver.SCHEME_CONTENT) {
                    runCatching {
                        appContext.contentResolver.releasePersistableUriPermission(
                            selectedUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            }
        }
    }

    fun removePendingImage(id: String) {
        updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages.filterNot { it.id == id }))
    }

    fun attachFiles(uris: List<String>) {
        if (uris.isEmpty()) return
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger)
            uris.map { uri ->
                gateway.resolveDocumentUri(
                    uri = Uri.parse(uri),
                    expectedKind = AgentFileReferenceKind.File,
                )
            }
        }
    }

    fun attachFolder(uri: String) {
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger)
            listOf(
                gateway.resolveDocumentUri(
                    uri = Uri.parse(uri),
                    expectedKind = AgentFileReferenceKind.Directory,
                )
            )
        }
    }

    fun attachFilePath(path: String) {
        resolveAndAttachFileReferences {
            listOf(AgentFileReferenceGateway(AndroidAgentLogger).resolveAbsolutePath(path))
        }
    }

    fun removePendingFileReference(id: String) {
        updateCurrentConversation(
            homeState.copy(
                pendingFileReferences = homeState.pendingFileReferences.filterNot { it.id == id }
            )
        )
    }

    private fun resolveAndAttachFileReferences(
        resolver: () -> List<AgentFileReferenceGateway.Resolution>,
    ) {
        val ownerVersion = fileAttachmentOwnerVersion
        scope.launch(Dispatchers.IO) {
            val resolutions = resolver()
            val references = resolutions.mapNotNull { resolution ->
                (resolution as? AgentFileReferenceGateway.Resolution.Success)?.reference
            }
            val failures = resolutions.mapNotNull { resolution ->
                (resolution as? AgentFileReferenceGateway.Resolution.Failure)?.error
            }
            withContext(Dispatchers.Main) {
                if (ownerVersion != fileAttachmentOwnerVersion) {
                    showComposerNotice(
                        MovoIcons.Folder,
                        appContext.getString(R.string.movo_notice_path_switched_title),
                        appContext.getString(R.string.movo_notice_path_switched_desc),
                    )
                    return@withContext
                }
                val existingPaths = homeState.pendingFileReferences
                    .mapTo(mutableSetOf()) { it.reference.absolutePath }
                val additions = references
                    .distinctBy { it.absolutePath }
                    .filter { existingPaths.add(it.absolutePath) }
                    .map { reference ->
                        PendingFileReferenceUi(
                            id = "file-${UUID.randomUUID()}",
                            reference = reference,
                        )
                    }
                if (additions.isNotEmpty()) {
                    updateCurrentConversation(
                        homeState.copy(
                            pendingFileReferences = homeState.pendingFileReferences + additions
                        )
                    )
                }
                // 规范 8.11：添加成功的项直接出现在输入框上方的附件条里（已在条里的不重复添加，条本身就是结果）；
                // 没能添加的项用 `Composer/Notice` 说明「有 N 项没能添加」+ 最主要的原因（与 8.9.1 分享跳过项同一写法）。
                if (failures.isNotEmpty()) {
                    showComposerNotice(
                        MovoIcons.File,
                        appContext.resources.getQuantityString(
                            R.plurals.movo_notice_attach_failed_title,
                            failures.size,
                            failures.size,
                        ),
                        failures.groupingBy { it }.eachCount().maxBy { it.value }.key.userMessage,
                    )
                }
            }
        }
    }

    private val AgentFileReferenceGateway.Error.userMessage: String
        get() = when (this) {
            AgentFileReferenceGateway.Error.UnsupportedDocumentProvider ->
                appContext.getString(R.string.capability_import_denied)
            AgentFileReferenceGateway.Error.InvalidPath -> appContext.getString(R.string.state_ui_please_enter_a_valid_absolute_path_6afeb4)
            AgentFileReferenceGateway.Error.PathNotFound -> appContext.getString(R.string.state_ui_the_path_does_not_exist_or_is_no_longer_accessib_a9776e)
            AgentFileReferenceGateway.Error.UnsupportedFileType -> appContext.getString(R.string.state_ui_only_supports_normal_files_and_folders_4adea0)
            AgentFileReferenceGateway.Error.TypeMismatch -> appContext.getString(R.string.state_ui_the_selected_project_type_does_not_match_3a5c49)
            AgentFileReferenceGateway.Error.RootUnavailable -> appContext.getString(R.string.state_ui_root_is_not_available_and_the_path_cannot_be_ver_fc4c81)
            AgentFileReferenceGateway.Error.AccessDenied -> appContext.getString(R.string.capability_import_denied)
            AgentFileReferenceGateway.Error.ImportFailed -> appContext.getString(R.string.capability_import_failed)
            AgentFileReferenceGateway.Error.ImportTooLarge -> appContext.getString(R.string.capability_import_too_large)
            AgentFileReferenceGateway.Error.ValidationTimedOut -> appContext.getString(R.string.state_ui_path_verification_timed_out_please_try_again_703687)
        }

    private val stopRequestedRunIds = mutableSetOf<String>()

    override fun cancelVoiceRun(runId: String) {
        if (currentRunId == runId && voiceRunListeners.containsKey(runId)) {
            stopCurrentRun("app.voice") // Also records cancellation while role preparation is pending.
        } else {
            io.github.fartown.movo.diagnostics.runlog.RunLog.stop(runId, "app.voice")
            scope.launch(Dispatchers.IO) { AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId) }
        }
    }

    override fun stopCurrentRun() = stopCurrentRun("app.stop")

    /** [source] 记进完整运行日志：这次停止是从哪里来的。 */
    private fun stopCurrentRun(source: String) {
        val runId = currentRunId ?: return
        if (!stopRequestedRunIds.add(runId)) return
        io.github.fartown.movo.diagnostics.runlog.RunLog.stop(runId, source)
        endMonitorsOwnedBy(runId)
        scope.launch(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
        }
    }

    private var permissionRefreshJob: Job? = null

    fun refreshPermissionHealth() {
        permissionRefreshJob?.cancel()
        permissionRefreshJob = scope.launch(Dispatchers.IO) {
            val refreshed = buildPermissionHealthState(appContext)
            withContext(Dispatchers.Main) { permissionHealthState = refreshed }
        }
    }

    fun refreshSkills() {
        scope.launch(Dispatchers.IO) {
            val entries = runCatching {
                SkillRuntime.createIndexService(appContext)
                    .listSkillsForManagement(forceRefresh = true)
            }.getOrElse {
                withContext(Dispatchers.Main) {
                    skillsState = skillsState.copy(
                        isLoading = false,
                        notice = skillsState.notice ?: newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_read_skills_599082),
                            message = appContext.getString(R.string.state_the_skill_list_is_temporarily_unavailable_please_try_29b0be),
                            isError = true,
                        ),
                    )
                }
                return@launch
            }
            val items = entries.map { entry ->
                val capabilities = buildList {
                    if (entry.hasScripts) add("scripts")
                    if (entry.hasReferences) add("references")
                    if (entry.hasAssets) add("assets")
                    if (entry.hasEvals) add("evals")
                }
                SkillItemUi(
                    id = entry.id,
                    name = entry.name,
                    description = entry.description,
                    source = entry.source,
                    enabled = entry.enabled,
                    installed = entry.installed,
                    capabilities = capabilities,
                )
            }
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(skills = items, isLoading = false)
            }
        }
    }

    fun toggleSkill(skillId: String, enabled: Boolean) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        skillsState = skillsState.copy(busySkillId = skillId)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).setSkillEnabled(skillId, enabled)
            }.isSuccess
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        skillsState.notice
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_update_skills_04e56c),
                            message = appContext.getString(R.string.state_the_skill_switch_has_not_changed_please_try_again_la_fa262f),
                            isError = true,
                        )
                    },
                )
            }
            refreshSkills()
        }
    }

    fun deleteSkill(skillId: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val skill = skillsState.skills.firstOrNull { it.id == skillId }
            ?.takeIf { it.canDeleteUserSkill }
            ?: return
        val skillName = skill.name.safeSkillDisplayName()
        skillsState = skillsState.copy(busySkillId = skillId, notice = null)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).deleteSkill(skillId)
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_skill_has_been_deleted_34c29b),
                            message = appContext.getString(R.string.skill_deleted_message, skillName),
                            isError = false,
                        )
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_delete_skill_1583c9),
                            message = appContext.getString(R.string.state_deletion_is_not_complete_movo_will_try_to_recover_whe_c4297e),
                            isError = true,
                        )
                    },
                )
            }
            refreshSkills()
        }
    }

    fun importSkillZip(uriValue: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val uri = runCatching { Uri.parse(uriValue) }.getOrNull()
            ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
        if (uri == null) {
            skillsState = skillsState.copy(
                notice = newSkillNotice(
                    title = appContext.getString(R.string.state_unable_to_read_skill_pack_a53563),
                    message = appContext.getString(R.string.state_please_select_the_zip_file_provided_by_the_system_fi_fea145),
                    isError = true,
                ),
            )
            return
        }
        pendingSkillZipUri = uri
        pendingSkillZipSha256 = null
        launchSkillZipImport(
            uri = uri,
            replaceUserSkill = false,
            expectedReplacementId = null,
            expectedArchiveSha256 = null,
        )
    }

    fun confirmSkillZipReplacement() {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val uri = pendingSkillZipUri
        if (uri == null) {
            pendingSkillZipSha256 = null
            skillsState = skillsState.copy(
                replacement = null,
                notice = newSkillNotice(
                    title = appContext.getString(R.string.state_unable_to_continue_installation_136d7c),
                    message = appContext.getString(R.string.state_skill_pack_is_no_longer_available_please_select_the__7cdfb4),
                    isError = true,
                ),
            )
            return
        }
        val replacementId = skillsState.replacement?.id
        val archiveSha256 = pendingSkillZipSha256
        if (replacementId == null || archiveSha256 == null) {
            pendingSkillZipUri = null
            pendingSkillZipSha256 = null
            skillsState = skillsState.copy(
                replacement = null,
                notice = newSkillNotice(
                    title = appContext.getString(R.string.state_unable_to_continue_installation_136d7c),
                    message = appContext.getString(R.string.state_replacement_confirmation_has_expired_please_select_t_fce9f2),
                    isError = true,
                ),
            )
            return
        }
        launchSkillZipImport(
            uri = uri,
            replaceUserSkill = true,
            expectedReplacementId = replacementId,
            expectedArchiveSha256 = archiveSha256,
        )
    }

    fun cancelSkillZipReplacement() {
        if (skillsState.isImporting) return
        pendingSkillZipUri = null
        pendingSkillZipSha256 = null
        skillsState = skillsState.copy(replacement = null)
    }

    private fun launchSkillZipImport(
        uri: Uri,
        replaceUserSkill: Boolean,
        expectedReplacementId: String?,
        expectedArchiveSha256: String?,
    ) {
        skillsState = skillsState.copy(
            isImporting = true,
            replacement = null,
            notice = null,
        )
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                skillZipImportGateway.installLocalZip(
                    openStream = {
                        appContext.contentResolver.openInputStream(uri)
                            ?: error(appContext.getString(R.string.state_ui_unable_to_open_selection_9f0004))
                    },
                    replaceUserSkill = replaceUserSkill,
                    expectedReplacementId = expectedReplacementId,
                    expectedArchiveSha256 = expectedArchiveSha256,
                )
            }.getOrElse {
                SkillZipImportOutcome.Failure(SkillZipImportOutcome.FailureCode.READ_FAILED)
            }
            withContext(Dispatchers.Main) {
                applySkillZipImportOutcome(outcome)
            }
        }
    }

    private fun isReplyRewrite(runId: String): Boolean =
        conversationsById[conversationIdForRun(runId)]?.roleplayMessages?.pendingRewrites?.containsKey(runId) == true

    private fun restoreRunEvents(runId: String, events: List<AgentEvent>) {
        if (isReplyRewrite(runId)) return
        // 恢复是完整快照：先清除同一 run 的旧投影，再一次发布，避免历史增量重复追加
        // 或中途的 Running 状态使已结束的思考重新展开、播放动画。
        // 本轮读到的监听行也按重放重新排位（原来的行对象保留，只换位置），不会跑到重放出来的步骤前面。
        val replayMonitorIds = events.filterIsInstance<AgentEvent.MonitorEventReceived>()
            .mapTo(mutableSetOf()) { monitorRowId(it.taskId, it.seq, it.kind) }
        Snapshot.withMutableSnapshot {
            flushPendingRunDelta(runId)
            updateMessages(runId, updateTimestamp = false) { messages ->
                replayMonitorRows = messages.filterIsInstance<MonitorEventMessageUi>()
                    .filter { it.id in replayMonitorIds && !it.startsTurn }
                    .associateBy { it.id }
                runMessageProjector.resetForReplay(
                    runId = runId,
                    messages = messages,
                    replaySupplementIndexes = events.filterIsInstance<AgentEvent.UserSupplementReceived>()
                        .mapTo(mutableSetOf()) { it.index },
                ).filterNot { it.id in replayMonitorRows }
            }
            try {
                events.forEach { event -> applyRunEvent(runId, event, persistSupplement = false) }
            } finally {
                replayMonitorRows = emptyMap()
            }
        }
    }

    private fun enqueueRunEvent(runId: String, event: AgentEvent) {
        voiceRunListeners[runId]?.onEvent?.invoke(event)
        if (event is AgentEvent.AssistantBlockDelta) {
            if (event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL || event.delta.isEmpty()) return

            runEventCoalescer.append(runId, event)?.let { ready ->
                applyRunEvent(runId, ready)
            }
            scheduleRunDeltaFlush(runId)
            return
        }

        flushPendingRunDelta(runId)
        applyRunEvent(runId, event)
    }

    private fun scheduleRunDeltaFlush(runId: String) {
        if (runEventFlushJobs[runId]?.isActive == true) return
        runEventFlushJobs[runId] = scope.launch {
            delay(STREAM_UI_UPDATE_INTERVAL_MS)
            runEventFlushJobs.remove(runId)
            flushPendingRunDelta(runId)
        }
    }

    private fun flushPendingRunDelta(runId: String) {
        runEventFlushJobs.remove(runId)?.cancel()
        runEventCoalescer.flush(runId)?.let { event ->
            applyRunEvent(runId, event)
        }
    }

    private fun applySkillZipImportOutcome(outcome: SkillZipImportOutcome) {
        when (outcome) {
            is SkillZipImportOutcome.Success -> {
                val installed = outcome.skills.singleOrNull()
                pendingSkillZipUri = null
                pendingSkillZipSha256 = null
                skillsState = skillsState.copy(
                    isImporting = false,
                    replacement = null,
                    notice = if (installed == null) {
                        skillZipFailureNotice(SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS)
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_skill_installed_b07e54),
                            message = appContext.getString(
                                R.string.skill_enabled_message,
                                installed.name.safeSkillDisplayName(),
                            ),
                            isError = false,
                        )
                    },
                )
                if (installed != null) refreshSkills()
            }

            is SkillZipImportOutcome.Conflict -> {
                val conflict = outcome.skills.singleOrNull()
                val archiveSha256 = outcome.archiveSha256
                if (
                    conflict != null &&
                    conflict.source == "user" &&
                    conflict.replaceAllowed &&
                    archiveSha256 != null
                ) {
                    val existingName = skillsState.skills
                        .firstOrNull { it.id == conflict.id && it.installed }
                        ?.name
                        .orEmpty()
                        .ifBlank { conflict.name }
                    pendingSkillZipSha256 = archiveSha256
                    skillsState = skillsState.copy(
                        isImporting = false,
                        replacement = SkillReplacementUi(
                            id = conflict.id,
                            name = existingName.safeSkillDisplayName(),
                        ),
                        notice = null,
                    )
                } else {
                    pendingSkillZipUri = null
                    pendingSkillZipSha256 = null
                    skillsState = skillsState.copy(
                        isImporting = false,
                        replacement = null,
                        notice = skillZipFailureNotice(
                            if (conflict?.source == "builtin") {
                                SkillZipImportOutcome.FailureCode.BUILTIN_CONFLICT
                            } else if (conflict != null && conflict.replaceAllowed) {
                                SkillZipImportOutcome.FailureCode.PACKAGE_CHANGED
                            } else if (conflict != null && !conflict.replaceAllowed) {
                                SkillZipImportOutcome.FailureCode.TARGET_NOT_REPLACEABLE
                            } else {
                                SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS
                            },
                        ),
                    )
                }
            }

            is SkillZipImportOutcome.Failure -> {
                pendingSkillZipUri = null
                pendingSkillZipSha256 = null
                skillsState = skillsState.copy(
                    isImporting = false,
                    replacement = null,
                    notice = skillZipFailureNotice(outcome.code),
                )
                if (outcome.code == SkillZipImportOutcome.FailureCode.RECOVERY_REQUIRED) {
                    refreshSkills()
                }
            }
        }
    }

    private fun skillZipFailureNotice(code: SkillZipImportOutcome.FailureCode): SkillNoticeUi {
        val message = when (code) {
            SkillZipImportOutcome.FailureCode.INVALID_ARCHIVE -> appContext.getString(R.string.state_ui_the_selected_file_is_not_a_valid_zip_package_bff052)
            SkillZipImportOutcome.FailureCode.ARCHIVE_LIMIT_EXCEEDED -> appContext.getString(R.string.state_ui_the_skill_pack_exceeds_the_safe_size_or_file_num_42e151)
            SkillZipImportOutcome.FailureCode.UNSAFE_ARCHIVE -> appContext.getString(R.string.state_ui_the_skill_pack_contains_an_unsafe_file_path_and__d8cfc0)
            SkillZipImportOutcome.FailureCode.NO_SKILL -> appContext.getString(R.string.state_ui_skill_md_not_found_in_zip_a57975)
            SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS -> appContext.getString(R.string.state_ui_the_local_zip_must_contain_only_one_skill_b89daf)
            SkillZipImportOutcome.FailureCode.INVALID_SKILL -> appContext.getString(R.string.state_ui_skill_md_is_missing_required_information_or_is_i_debe6e)
            SkillZipImportOutcome.FailureCode.PACKAGE_CHANGED -> appContext.getString(R.string.state_ui_the_zip_content_has_changed_please_reselect_and__ab8b12)
            SkillZipImportOutcome.FailureCode.BUILTIN_CONFLICT -> appContext.getString(R.string.state_ui_built_in_skills_with_the_same_name_are_protected_446ad3)
            SkillZipImportOutcome.FailureCode.TARGET_NOT_REPLACEABLE ->
                appContext.getString(R.string.state_ui_the_target_with_the_same_name_is_not_a_user_skil_00474b)
            SkillZipImportOutcome.FailureCode.READ_FAILED -> appContext.getString(R.string.state_ui_the_selected_file_cannot_be_read_please_select_a_7265b9)
            SkillZipImportOutcome.FailureCode.STORAGE_FAILED -> appContext.getString(R.string.state_ui_unable_to_save_skills_original_skills_have_been__9d4748)
            SkillZipImportOutcome.FailureCode.RECOVERY_REQUIRED ->
                appContext.getString(R.string.state_ui_the_installation_failed_and_automatic_recovery_d_0d9e01)
        }
        return newSkillNotice(
            title = appContext.getString(R.string.state_unable_to_install_skill_0ec70b),
            message = message,
            isError = true,
        )
    }

    fun reinstallBuiltin(skillId: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        skillsState = skillsState.copy(busySkillId = skillId)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).installBuiltinSkill(skillId)
            }.isSuccess
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        skillsState.notice
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_restore_skills_6b3d23),
                            message = appContext.getString(R.string.state_the_built_in_skills_have_not_changed_please_try_agai_34e5e3),
                            isError = true,
                        )
                    },
                )
            }
            if (succeeded) refreshSkills()
        }
    }

    fun dismissSkillNotice() {
        skillsState = skillsState.copy(notice = null)
    }

    private fun newSkillNotice(
        title: String,
        message: String,
        isError: Boolean,
    ): SkillNoticeUi = SkillNoticeUi(
        id = ++skillNoticeSequence,
        title = title,
        message = message,
        isError = isError,
    )

    private fun String.safeSkillDisplayName(): String =
        lineSequence().firstOrNull().orEmpty().trim().ifBlank { appContext.getString(R.string.state_ui_unnamed_skill_a58008) }.take(80)

    private fun applyRunEvent(
        runId: String,
        event: AgentEvent,
        persistSupplement: Boolean = true,
    ) {
        if (isReplyRewrite(runId)) {
            if (event is AgentEvent.RunStarted && runId in stopRequestedRunIds) {
                io.github.fartown.movo.diagnostics.runlog.RunLog.stop(runId, "app.resend_rewrite")
                scope.launch(Dispatchers.IO) {
                    AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
                }
            }
            return
        }
        // 这一轮已经开始干活：事件轮之后被取消也不再撤回（模型可能已经做了事）。
        when (event) {
            is AgentEvent.AssistantBlockStart, is AgentEvent.AssistantReceived, is AgentEvent.ToolStarted,
            is AgentEvent.HostedToolStarted, is AgentEvent.MonitorEventReceived, is AgentEvent.UserSupplementReceived,
            is AgentEvent.ContextCompaction -> runsWithModelOutput += runId
            else -> Unit
        }
        when (event) {
            is AgentEvent.AssistantBlockStart -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.startAssistantBlock(runId, event, messages)
                }
            }

            is AgentEvent.AssistantBlockDelta -> {
                updateMessages(runId, updateTimestamp = false) { messages ->
                    when (event.kind) {
                        AgentEvent.AssistantBlockKind.TEXT ->
                            runMessageProjector.appendTextDelta(
                                runId,
                                event.round,
                                event.index,
                                event.delta,
                                messages,
                            )

                        AgentEvent.AssistantBlockKind.THINKING ->
                            runMessageProjector.appendReasoningDelta(
                                runId,
                                event.round,
                                event.index,
                                event.delta,
                                messages,
                            )

                        AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                    }
                }
            }

            is AgentEvent.AssistantBlockEnd -> {
                updateRunTrace(runId) { messages ->
                    when (event.kind) {
                        AgentEvent.AssistantBlockKind.TEXT ->
                            runMessageProjector.finalizeTextBlock(
                                runId,
                                event.round,
                                event.index,
                                event.replacementContent,
                                messages,
                            )

                        AgentEvent.AssistantBlockKind.THINKING ->
                            runMessageProjector.finalizeThinkingBlock(
                                runId,
                                event.round,
                                event.index,
                                event.replacementContent,
                                messages,
                            )

                        AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                    }
                }
            }

            is AgentEvent.UsageReceived -> {
                updateAssistantUsage(runId, event.round, event.usage.toUi())
            }

            is AgentEvent.UserSupplementReceived -> {
                insertSupplementMessage(runId, event.index, event.text, persist = persistSupplement)
            }

            is AgentEvent.MonitorEventReceived -> {
                insertMonitorRow(runId, event, persist = persistSupplement)
            }

            AgentEvent.RunPaused, AgentEvent.RunResumed -> {
                val paused = event == AgentEvent.RunPaused
                conversationIdForRun(runId)?.let { id ->
                    conversationsById[id]?.let { state ->
                        if (state.isPaused != paused) updateConversation(id, state.copy(isPaused = paused))
                    }
                }
            }

            is AgentEvent.ToolStarted -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking =
                        runMessageProjector.finalizeThinkingRound(runId, event.round, messages)
                    val finalizedText = runMessageProjector.finalizeTextRound(runId, event.round, finalizedThinking)
                    runMessageProjector.startTool(runId, event, finalizedText)
                }
            }

            is AgentEvent.ToolFinished -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.finishTool(runId, event, messages)
                }
            }

            is AgentEvent.HostedToolStarted -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking =
                        runMessageProjector.finalizeThinkingRound(runId, event.round, messages)
                    val finalizedText = runMessageProjector.finalizeTextRound(runId, event.round, finalizedThinking)
                    runMessageProjector.startHostedTool(runId, event, finalizedText)
                }
            }

            is AgentEvent.HostedToolFinished -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.finishHostedTool(runId, event, messages)
                }
            }

            is AgentEvent.ContextCompaction -> {
                updateMessages(runId) { messages ->
                    val id = "assistant-$runId-compaction-${event.operationId}"
                    messages.filterNot { it.id == id } + SystemNoticeMessageUi(
                        id = id, code = SystemNoticeCode.ContextCompaction, detail = event.displayMessage,
                        contextTokens = event.tokensAfter,
                        running = event.phase == AgentEvent.ContextCompaction.PHASE_STARTED,
                    )
                }
            }

            is AgentEvent.ModelRetryScheduled -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.scheduleModelRetry(runId, event, messages)
                }
            }

            is AgentEvent.RunFailed -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
                    val finalizedText = runMessageProjector.finalizeText(runId, finalizedThinking)
                    stampTurnFinished(runMessageProjector.failRunningTools(event.reason, finalizedText))
                }
            }

            is AgentEvent.AssistantReceived -> {
                if (event.reasoningContent.isNotBlank()) {
                    updateRunTrace(runId) { messages ->
                        runMessageProjector.ensureCompletedThinking(
                            runId = runId,
                            round = event.round,
                            content = event.reasoningContent,
                            messages = messages,
                        )
                    }
                }
            }

            is AgentEvent.RunFinished -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
                    stampTurnFinished(runMessageProjector.finalizeText(runId, finalizedThinking))
                }
            }

            is AgentEvent.RunStarted -> {
                if (runId in stopRequestedRunIds) {
                    io.github.fartown.movo.diagnostics.runlog.RunLog.stop(runId, "app.resend")
                    scope.launch(Dispatchers.IO) {
                        AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
                    }
                }
                // 运行时已在执行这一轮：准备期间到的监听事件现在可以并入了（重放时不触发）。
                startedRunIds += runId
                if (persistSupplement && conversationIdForRun(runId)?.let(monitorQueue::hasPending) == true) {
                    scope.launch { pumpMonitorNotices() }
                }
            }
            is AgentEvent.InteractionRequested -> {
                activeInteraction = AgentInteractionUiState(
                    runId = runId,
                    requestId = event.requestId,
                    isApproval = event.kind == "approval",
                    title = event.title,
                    detail = event.detail,
                    options = event.options,
                    allowFreeText = event.allowFreeText,
                    note = event.note,
                    reason = event.reason,
                )
            }

            is AgentEvent.InteractionResolved -> {
                if (activeInteraction?.requestId == event.requestId) activeInteraction = null
            }

            is AgentEvent.ProviderRequestStarted,
            is AgentEvent.ProviderResponseStarted,
            is AgentEvent.ToolImagesAttached,
            is AgentEvent.RoundStarted,
            -> Unit
        }
        if (event !is AgentEvent.AssistantBlockDelta && event !is AgentEvent.UsageReceived) persistRunProgressSoon()
    }

    private var runProgressPersistJob: Job? = null

    /**
     * 执行中把界面行写进对话（合并保存，只写变了的行）：进程被杀后已经发生的步骤都在库里，
     * Runtime 不再另存一份事件日志。
     */
    private fun persistRunProgressSoon() {
        if (runProgressPersistJob?.isActive == true) return
        runProgressPersistJob = scope.launch {
            delay(RUN_PROGRESS_PERSIST_DEBOUNCE_MS)
            runProgressPersistJob = null
            persistConversations()
        }
    }

    /** 用户在确认卡上选择允许 / 拒绝，回传给等待中的 run 并收起卡片。 */
    fun submitInteractionApproval(approved: Boolean) {
        val interaction = activeInteraction ?: return
        activeInteraction = null
        scope.launch(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger)
                .sendInteractionReply(
                    interaction.runId,
                    interaction.requestId,
                    InteractionReply.Approval(approved),
                )
        }
    }

    /** 用户在提问卡上作答（选了某个选项或填了自由文本），回传给等待中的 run 并收起卡片。 */
    fun submitInteractionAnswer(text: String, optionIndex: Int?) {
        val interaction = activeInteraction ?: return
        activeInteraction = null
        scope.launch(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger)
                .sendInteractionReply(
                    interaction.runId,
                    interaction.requestId,
                    InteractionReply.Answer(text, optionIndex),
                )
        }
    }

    /** 用户取消当前交互（关闭卡片），按取消回传，运行时据此安全侧处理（审批=拒绝、提问=拒绝）。 */
    fun cancelInteraction() {
        val interaction = activeInteraction ?: return
        activeInteraction = null
        scope.launch(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger)
                .sendInteractionReply(
                    interaction.runId,
                    interaction.requestId,
                    InteractionReply.Cancelled,
                )
        }
    }

    /** 这一轮开始：给发起它的用户消息记下开始时刻（执行卡按整轮计时，与运行日志一致）。 */
    private fun stampTurnStarted(messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> {
        val index = messages.indexOfLast { it.isTurnStart() }
        if (index < 0) return messages
        val now = System.currentTimeMillis()
        return messages.mapIndexed { i, message ->
            when {
                i != index -> message
                message is UserMessageUi -> message.copy(runStartedAtMillis = now, runFinishedAtMillis = null)
                message is MonitorEventMessageUi -> message.copy(runStartedAtMillis = now, runFinishedAtMillis = null)
                else -> message
            }
        }
    }

    /** 这一轮结束：给最近一轮已记开始、未记结束的用户消息记下结束时刻。 */
    private fun stampTurnFinished(messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> {
        val index = messages.indexOfLast { it.isTurnStart() }
        val finished = when (val target = messages.getOrNull(index)) {
            is UserMessageUi -> if (target.runStartedAtMillis == null || target.runFinishedAtMillis != null) return messages
                else target.copy(runFinishedAtMillis = System.currentTimeMillis())
            is MonitorEventMessageUi -> if (target.runStartedAtMillis == null || target.runFinishedAtMillis != null) return messages
                else target.copy(runFinishedAtMillis = System.currentTimeMillis())
            else -> return messages
        }
        return messages.mapIndexed { i, message -> if (i == index) finished else message }
    }

    private fun applyRunResult(
        runId: String,
        result: AgentRuntimeWire.RunResult,
        acknowledgeRuntimeResult: Boolean = false,
        recoveredHandoff: AgentUiHandoffPayload? = null,
    ) {
        if (result.resultKind == "unconfirmed" && voiceRunListeners.containsKey(runId)) {
            if (currentRunId == runId) { currentRunId = null; currentRunJob = null }
            voiceRunListeners[runId]?.onResult?.invoke(result)
            refreshRuntimeResults()
            return
        }
        // 后台监听发起的事件轮没真正开始（被拒、被别处的新任务替换、开始前被取消）：撤回，事件稍后再发，不显示失败卡。
        if (monitorQueue.isEventTurn(runId) && shouldRollBackMonitorTurn(runId, result)) {
            rollBackMonitorTurn(runId, result, acknowledgeRuntimeResult)
            return
        }
        flushPendingRunDelta(runId)
        // 停止、失败等不经过 RunFinished 的结束：这里补记这一轮的结束时刻（已记过的不覆盖）。
        if (recoveredHandoff == null) updateMessages(runId) { messages -> stampTurnFinished(messages) }
        // 并入这一轮、模型没读到的监听事件放回队首（只按结果 transcript 认），交给下一个事件轮。
        settleMonitorDeliveries(runId, result.transcript.takeIf { result.contextSnapshotRef.isBlank() })
        val followUpConfig = followUpRunConfigs.remove(runId)
        val rewriting = result.operation == AgentRuntimeWire.OP_REWRITE_REPLY || isReplyRewrite(runId)
        stopRequestedRunIds.remove(runId)
        if (runId == currentRunId) {
            currentRunId = null
            currentRunJob = null
        }
        // 本轮结束后看看有没有等着的监听事件（事件驱动，不轮询）。
        scope.launch { pumpMonitorNotices() }
        if (rewriting) {
            val conversationId = conversationIdForRun(runId)
            val existing = conversationsById[conversationId]
            if (conversationId != null && existing != null && result.contextSnapshotRef.isBlank()) {
                updateConversation(conversationId, RoleplayConversationReducer.applyRewrite(existing, runId, result))
            } else setConversationStreaming(runId, false)
            runMessageProjector.clearRun(runId)
            runConversationIds.remove(runId)
            refreshConversationSummaries()
            persistConversations(onSaved = if (acknowledgeRuntimeResult && result.resultKind == "terminal" && result.contextSnapshotRef.isBlank()) {
                { AgentRuntimeClient(appContext, AndroidAgentLogger).ackResult(runId) }
            } else null)
            return
        }
        updateRunTrace(runId) { messages ->
            runMessageProjector.finishContextCompaction(runId,
                runMessageProjector.finalizeRun(runId, messages),
                if (result.ok) "上下文压缩完成" else result.error ?: "上下文压缩已停止")
        }
        if (recoveredHandoff != null && result.contextSnapshotRef.isBlank()) {
            // 在途重连与终态 outbox 恢复必须合并同一份追问及 history，保存后才能确认消费。
            val conversationId = conversationIdForRun(runId)
            val state = conversationsById[conversationId]
            if (conversationId != null && state != null) {
                updateConversation(conversationId, AgentPendingResultRecovery.applyHandoffHistory(
                    state = state,
                    runId = runId,
                    result = result,
                    promptSupplement = recoveredHandoff.promptSupplement,
                    supplements = recoveredHandoff.supplements,
                ).state)
            }
        } else if (result.contextSnapshotRef.isBlank()) {
            applyConversationHistoryResult(runId, result.transcript, result.contextSnapshot, !result.ok || result.contextSnapshot != null)
        }
        when {
            result.operation == AgentRuntimeWire.OP_COMPACT ||
                conversationsById[conversationIdForRun(runId)]?.isCompacting == true -> updateMessages(runId) { messages ->
                    AgentRunMessageProjector.mergeCompactionResultNotice(
                        runId = runId,
                        messages = messages,
                        ok = result.ok,
                        detail = if (result.ok) "上下文压缩完成" else result.error ?: "上下文压缩失败",
                    )
                }
            result.ok && result.content.isNotBlank() -> completeLatestAssistantMessage(
                runId,
                fallbackContent = result.content,
            )
            result.ok -> replaceLatestAssistantWithNotice(runId, SystemNoticeCode.EmptyResult)
            result.error == LEGACY_STOPPED_ERROR || result.error == SYNTHETIC_STATUS_STOPPED ->
                replaceLatestAssistantWithNotice(runId, SystemNoticeCode.Stopped)
            else -> replaceLatestAssistantWithNotice(
                runId,
                SystemNoticeCode.RuntimeFailed,
                result.error,
            )
        }
        setConversationStreaming(runId, false)
        conversationIdForRun(runId)?.let { id -> conversationsById[id]?.let {
            updateConversation(id, RoleplayConversationReducer.linkRun(it, runId))
        } }
        // 失败、停止、中断、空结果、压缩与恢复出来的结果都不生成追问。
        if (followUpConfig != null && result.ok && result.content.isNotBlank() &&
            result.operation == AgentRuntimeWire.OP_CHAT && recoveredHandoff == null) {
            conversationIdForRun(runId)?.let { id -> requestFollowUpSuggestions(id, runId, followUpConfig) }
        }
        runMessageProjector.clearRun(runId)
        runConversationIds.remove(runId)
        refreshConversationSummaries()
        persistConversations(
            onSaved = if (acknowledgeRuntimeResult && result.resultKind == "terminal" && result.contextSnapshotRef.isBlank()) {
                {
                    AgentRuntimeClient(appContext, AndroidAgentLogger).ackResult(runId)
                }
            } else {
                null
            }
        )
        voiceRunListeners.remove(runId)?.onResult?.invoke(result)
    }

    private fun requestFollowUpSuggestions(
        conversationId: String,
        runId: String,
        config: AgentModelClient.ModelConfig,
    ) {
        val state = conversationsById[conversationId] ?: return
        if (state.isCompacting || state.roleplay != null) return
        val target = AgentFollowUpSuggestions.target(runId, state.messages) ?: return
        if (!AgentFollowUpSuggester.shouldSuggest(target.answer, target.usedTools)) return
        followUpJobs.remove(conversationId)?.cancel()
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val prompts = AgentFollowUpSuggester.suggest(config, target.userText, target.answer)
                val current = conversationsById[conversationId] ?: return@launch
                if (current.isStreaming || current.messageEdit != null) return@launch
                val messages = AgentFollowUpSuggestions.attach(current.messages, target.answerMessageId, prompts)
                    ?: return@launch
                updateConversation(conversationId, current.copy(messages = messages), updateTimestamp = false)
                persistConversations()
            } finally {
                if (followUpJobs[conversationId] === job) followUpJobs.remove(conversationId)
            }
        }
        followUpJobs[conversationId] = job
        job.start()
    }

    private fun updateRunTrace(
        runId: String,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ) {
        updateMessages(runId, transform = transform)
        refreshConversationSummaries()
    }

    private fun updateAssistantUsage(runId: String, round: Int, usage: TokenUsageUi) {
        if (usage.isEmpty) return
        // 只补充 token 用量。不能触碰 isStreaming：Usage 事件紧跟在文本块结束之后，
        // 若把 isStreaming 改回 true，流式渲染会在流式/静态两种视图间反复切换，整段重渲染。
        updateMessages(runId) { messages ->
            val targetIndex = messages.indexOfLast { message ->
                message is AgentMessageUi && isAssistantMessageForRound(message.id, runId, round)
            }
            messages.mapIndexed { index, message ->
                if (index == targetIndex && message is AgentMessageUi) {
                    message.copy(usage = usage)
                } else {
                    message
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // 后台监听（docs/research/agent-monitor.md 第 4 节、规范 8.12）
    // ---------------------------------------------------------------------------

    /**
     * 监听通知到达（主线程）：本对话正在执行、且是 App 订阅着的这一轮，就排进它的下一个步骤边界；空闲就开一轮事件轮；
     * 别处在执行、语音进行中、用户在编辑消息或有排队的消息时先排队，这些情况解除时（事件驱动）一并交给 Movo。
     * 用户手动停止只插「已停止监听」，不唤醒；Movo 自己停止的不插行（执行卡里已有这一步）。
     */
    private fun onMonitorNotice(notice: MonitorNotice) {
        val conversationId = notice.conversationId
        if (!isKnownConversation(conversationId)) {
            // 对话已经不在了（删除、删光轮次、导入备份）：这个监听没人管了，停掉。
            MonitorRegistry.stop(notice.taskId, MonitorEndReason.SESSION_END)
            monitorQueue.dropConversation(conversationId)
            return
        }
        if (conversationId !in conversationsById) {
            // 对话只在库里：读进来再处理这条通知。
            scope.launch {
                ensureLoaded(listOf(conversationId))
                if (conversationId in conversationsById) onMonitorNotice(notice)
            }
            return
        }
        AndroidAgentLogger.info(
            "Monitor notice: kind=${if (notice is MonitorNotice.Ended) "ended:" + notice.reason.name else "event"}, " +
                "streaming=${conversationsById[conversationId]?.isStreaming}",
        )
        if (notice is MonitorNotice.Ended && !notice.reason.wakesAgent) {
            monitorQueue.dropTask(notice.taskId)
            if (notice.reason == MonitorEndReason.STOPPED_BY_USER) {
                // 「已停止」不唤醒 Movo，不是一轮的起点。
                val row = notice.toMonitorRow(startsTurn = false)
                if (conversationsById[conversationId]?.isStreaming == true) {
                    deferredMonitorRows.getOrPut(conversationId) { mutableListOf() } += row
                } else {
                    appendMonitorRows(conversationId, listOf(row))
                }
            }
            return
        }
        monitorQueue.enqueue(notice)
        pumpMonitorNotices()
    }

    /**
     * 把排着的监听通知交出去。由通知到达、本 App 的一轮结束或开始执行、编辑结束、排队消息发出、模型切换完成、
     * 语音结束触发；只有看不到的阻塞（运行时被别的入口占着、语音还在收尾）才按退避间隔重试。
     */
    private fun pumpMonitorNotices() {
        flushDeferredMonitorRows()
        dropMonitorsWithoutConversation()
        var progressed = false
        for (conversationId in monitorQueue.conversations()) {
            val runId = monitorRunInConversation(conversationId)
            if (runId != null) {
                // 上一批还没被读到时先不再交（新到的留在 App 队列里，受每个监听的积压上限约束），语音轮等不并入：等这一轮结束。
                if (!MonitorWakePolicy.canInject(monitorRunFacts(runId, conversationId))) continue
                val batch = monitorQueue.take(conversationId)
                if (batch.isEmpty()) continue
                // 只交给 App 自己订阅着的这一轮：运行时在模型真正读到时发回「已消费」，那时才插行、设锚点。
                if (AgentRuntimeService.injectMonitorEvent(conversationId, runId, MonitorEventFormatter.blocks(batch), batch.map { it.toRuntimeEvent() })) {
                    monitorQueue.markInFlight(runId, conversationId, batch)
                    progressed = true
                } else {
                    monitorQueue.requeueFront(conversationId, batch)
                }
                continue
            }
            if (canStartMonitorRun(conversationId)) {
                val batch = monitorQueue.take(conversationId)
                if (batch.isNotEmpty() && startMonitorEventRun(conversationId, batch)) progressed = true
            }
        }
        if (progressed) monitorRetryDelayMs = MONITOR_RETRY_MIN_MS
        scheduleMonitorRetry()
    }

    /** 本 App 当前订阅着的、属于这个对话的一轮；没有时返回 null。 */
    private fun monitorRunInConversation(conversationId: String): String? =
        currentRunId?.takeIf { runConversationIds[it] == conversationId }

    private fun monitorRunFacts(runId: String, conversationId: String): MonitorWakePolicy.RunFacts {
        val state = conversationsById[conversationId]
        return MonitorWakePolicy.RunFacts(
            sameConversation = state != null && runConversationIds[runId] == conversationId,
            // 收到 RunStarted 才说明运行时在执行这一轮（运行时只按对话核对，准备期间可能是别的运行）。
            started = runId in startedRunIds,
            streaming = state?.isStreaming == true,
            compacting = state?.isCompacting == true,
            roleplay = state?.roleplay != null,
            // 语音轮不并入：播报会变成提醒，等语音结束后的事件轮。
            voiceTurn = voiceRunListeners.containsKey(runId),
            replyRewrite = isReplyRewrite(runId),
            batchInFlight = monitorQueue.hasInFlight(runId),
        )
    }

    private fun monitorIdleFacts(conversationId: String): MonitorWakePolicy.IdleFacts = MonitorWakePolicy.IdleFacts(
        appRunActive = currentRunId != null,
        anyConversationBusy = conversationsById.values.any { it.isStreaming || it.isCompacting },
        userEditing = homeState.messageEdit != null || conversationsById.values.any { it.messageEdit != null },
        userMessageQueued = queuedTextSubmission != null,
        modelChanging = modelPickerState.isChanging,
        voiceActive = voiceSession.active,
        voiceClosing = voiceSession.busy,
        runtimeIdle = AgentRuntimeService.isIdle(),
        roleplay = conversationsById[conversationId]?.roleplay != null,
    )

    /**
     * 事件轮可以开始：本 App 没有在跑的一轮、运行时空闲、没有语音会话，而且不打断用户——
     * 用户没在编辑消息、没有排队等发的消息（用户发起的运行优先于事件轮）、模型不在切换。
     */
    private fun canStartMonitorRun(conversationId: String): Boolean =
        conversationsById[conversationId] != null && MonitorWakePolicy.canStartEventTurn(monitorIdleFacts(conversationId))

    /** 还有事件在等、而阻塞看不到何时解除时，隔一会儿再试（1 秒起，逐次加倍，最长 30 秒）。 */
    private fun scheduleMonitorRetry() {
        val waiting = monitorQueue.hasAnyPending() || deferredMonitorRows.isNotEmpty()
        if (!waiting || MonitorWakePolicy.blockedObservably(monitorIdleFacts(conversationId = ""))) {
            // 能看到的阻塞解除时自己会再触发一次，不需要定时器。
            monitorRetryJob?.cancel()
            monitorRetryJob = null
            monitorRetryDelayMs = MONITOR_RETRY_MIN_MS
            return
        }
        if (monitorRetryJob?.isActive == true) return
        val delayMs = monitorRetryDelayMs
        monitorRetryDelayMs = MonitorWakePolicy.nextRetryDelay(delayMs, MONITOR_RETRY_MIN_MS, MONITOR_RETRY_MAX_MS)
        monitorRetryJob = scope.launch {
            delay(delayMs)
            monitorRetryJob = null
            pumpMonitorNotices()
        }
    }

    /** 事件轮：本轮的起点是事件行（不是用户消息），给模型的是系统通知正文。 */
    private fun startMonitorEventRun(conversationId: String, notices: List<MonitorNotice>): Boolean {
        val state = conversationsById[conversationId] ?: return false
        val existing = state.messages.mapTo(HashSet()) { it.id }
        val fresh = notices.filterNot { MonitorDeliveryQueue.rowKey(it) in existing }
        if (fresh.isEmpty()) return false
        val runId = "run-${UUID.randomUUID()}"
        // 第一行是这一轮的起点，也是模型历史里那条 user 条目（事件正文）的锚点。
        val rows = fresh.mapIndexed { index, notice ->
            notice.toMonitorRow(startsTurn = index == 0).copy(historyAnchor = index == 0)
        }
        val prompt = MonitorEventFormatter.format(fresh)
        monitorQueue.markEventTurn(runId, conversationId, fresh)
        monitorTurnLaunches[runId] = MonitorTurnLaunch(rows.mapTo(HashSet()) { it.id }, state.messages)
        launchConversationRun(
            conversationId = conversationId,
            runId = runId,
            prompt = prompt,
            images = emptyList(),
            history = state.history,
            userHistoryMessage = AgentModelClient.buildUserHistoryMessage(prompt, emptyList()).copy(messageId = "user-$runId"),
            messages = state.messages + rows,
            state = state,
            reasoningEffort = state.reasoningEffort,
            origin = AgentRuntimeWire.ORIGIN_MONITOR,
        )
        return true
    }

    /**
     * 用户发起的运行优先于事件轮：还没开始干活的事件轮撤回（事件放回队首），用户的消息先跑。
     * 已经在干活的事件轮不打断，用户的消息照常排在它后面。
     */
    private fun preemptMonitorRunForUser() {
        val runId = currentRunId ?: return
        if (!monitorQueue.isEventTurn(runId) || runId in runsWithModelOutput) return
        if (!preemptedMonitorRuns.add(runId)) return
        AndroidAgentLogger.info("Monitor event turn yields to a user run")
        // 准备阶段由 stopRequestedRunIds 拦下；已交给运行时的由取消结束，结果按「让路」撤回而不是「已停止」。
        stopRequestedRunIds += runId
        io.github.fartown.movo.diagnostics.runlog.RunLog.stop(runId, "app.yield_to_user")
        scope.launch(Dispatchers.IO) { AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId) }
    }

    /**
     * 事件轮该撤回：没有任何进展（模型没输出、没调工具、transcript 为空），且结束原因是
     * 被拒（运行时忙）、在准备时被新任务替换、或不是用户点的停止（被别的入口顶掉、为用户运行让路）。
     */
    private fun shouldRollBackMonitorTurn(runId: String, result: AgentRuntimeWire.RunResult): Boolean =
        MonitorTurnRollback.shouldRollBack(
            result = result,
            madeProgress = runId in runsWithModelOutput,
            stoppedByUser = runId in stopRequestedRunIds && runId !in preemptedMonitorRuns,
        )

    /** 撤回没真正开始的事件轮：撤掉事件行与历史里的事件正文，事件放回队首，稍后再发；不显示失败卡。 */
    private fun rollBackMonitorTurn(runId: String, result: AgentRuntimeWire.RunResult, acknowledgeRuntimeResult: Boolean) {
        flushPendingRunDelta(runId)
        val conversationId = conversationIdForRun(runId)
        val launch = monitorTurnLaunches.remove(runId)
        runLaunchedAtMillis.remove(runId)
        // 起点那一批（与理论上不会有的在途事件）一起放回队首。
        monitorQueue.settle(runId, consumedGroups = null)
        val requeued = monitorQueue.rollbackEventTurn(runId)
        followUpRunConfigs.remove(runId)
        stopRequestedRunIds.remove(runId)
        preemptedMonitorRuns.remove(runId)
        startedRunIds.remove(runId)
        runsWithModelOutput.remove(runId)
        if (runId == currentRunId) {
            currentRunId = null
            currentRunJob = null
        }
        val state = conversationId?.let(conversationsById::get)
        if (conversationId != null && state != null) {
            updateConversation(
                conversationId,
                MonitorTurnRollback.rollBack(state, runId, launch?.rowIds.orEmpty(), launch?.preLaunchMessages),
                updateTimestamp = false,
            )
        }
        runMessageProjector.clearRun(runId)
        runConversationIds.remove(runId)
        refreshConversationSummaries()
        persistConversations(
            onSaved = if (acknowledgeRuntimeResult && result.resultKind == "terminal" && result.contextSnapshotRef.isBlank()) {
                { AgentRuntimeClient(appContext, AndroidAgentLogger).ackResult(runId) }
            } else {
                null
            },
        )
        AndroidAgentLogger.info("Monitor event turn rolled back: requeued=${requeued.size}, kind=${result.resultKind}")
        scope.launch { pumpMonitorNotices() }
    }

    /**
     * 一轮结束：结算并入这一轮的监听事件。transcript 里实际写进去的事件条目少于界面已插的批次时
     * （停止恰好落在「已消费」与写入上下文之间），多出来的行撤掉；没被读到的通知都放回队首。
     * [transcript] 为 null 表示结果要等恢复，只放回没确认的。
     */
    private fun settleMonitorDeliveries(runId: String, transcript: List<AgentModelClient.ConversationMessage>?) {
        val consumed = transcript?.count { it.role == "user" && it.messageId.startsWith("monitor-$runId-") }
        val settlement = monitorQueue.settle(runId, consumed)
        if (settlement.removeRowKeys.isNotEmpty()) {
            updateMessages(runId, updateTimestamp = false) { messages -> messages.filterNot { it.id in settlement.removeRowKeys } }
        }
        if (settlement.requeue.isNotEmpty()) {
            AndroidAgentLogger.info("Monitor events requeued after run: count=${settlement.requeue.size}")
        }
        monitorQueue.finishEventTurn(runId)
        monitorTurnLaunches.remove(runId)
        runLaunchedAtMillis.remove(runId)
        startedRunIds.remove(runId)
        runsWithModelOutput.remove(runId)
        preemptedMonitorRuns.remove(runId)
    }

    /**
     * 运行时发回「已消费」：模型这一步真正读到了这些事件。这时才插行（插在本轮当前位置之后，不是新一轮的起点），
     * 每批第一行是模型历史里那条 user 条目的锚点。重放时按 id 复用原来的行。
     */
    private fun insertMonitorRow(runId: String, event: AgentEvent.MonitorEventReceived, persist: Boolean) {
        val id = monitorRowId(event.taskId, event.seq, event.kind)
        val notice = monitorQueue.confirm(runId, id, event.anchor)
        val row = (notice?.toMonitorRow(startsTurn = false) ?: replayMonitorRows[id] ?: event.toMonitorRow())
            .copy(startsTurn = false, historyAnchor = event.anchor)
        updateMessages(runId) { messages -> if (messages.any { it.id == row.id }) messages else messages + row }
        if (persist) {
            persistMonitorRowsSoon()
            // 这一批读到了：排着的下一批可以交给这一轮了。
            if (!monitorQueue.hasInFlight(runId) && conversationIdForRun(runId)?.let(monitorQueue::hasPending) == true) {
                scope.launch { pumpMonitorNotices() }
            }
        }
    }

    private fun appendMonitorRows(conversationId: String, rows: List<MonitorEventMessageUi>) {
        val state = conversationsById[conversationId] ?: return
        val fresh = rows.filterNot { row -> state.messages.any { it.id == row.id } }
        AndroidAgentLogger.info("Monitor rows appended: count=${fresh.size}")
        if (fresh.isEmpty()) return
        updateConversation(conversationId, state.copy(messages = state.messages + fresh))
        refreshConversationSummaries()
        persistMonitorRowsSoon()
    }

    private fun flushDeferredMonitorRows() {
        for ((conversationId, rows) in deferredMonitorRows.entries.toList()) {
            if (conversationsById[conversationId]?.isStreaming == true) continue
            deferredMonitorRows.remove(conversationId)
            appendMonitorRows(conversationId, rows)
        }
    }

    /**
     * ■ 按归属结束（规范 8.12「结束」）：正在跑的是这个监听任务的一轮——监听叫醒的一轮，或这一轮里开了监听——
     * 就连这个对话的监听一起结束（5 秒内可撤销）；同一对话里另问的事只停那个回答，监听不动。
     */
    private fun endMonitorsOwnedBy(runId: String) {
        val conversationId = runConversationIds[runId]
        val launchedAt = runLaunchedAtMillis[runId]
        val eventTurn = monitorQueue.isEventTurn(runId)
        val running = MonitorRegistry.active.value.filter { it.conversationId == conversationId }
        val startedInRun = launchedAt != null && running.any { it.startedAtMillis >= launchedAt }
        val ending = if (conversationId != null && (eventTurn || startedInRun)) {
            MonitorRegistry.endLater { it.conversationId == conversationId }
        } else {
            null
        }
        AndroidAgentLogger.info(
            "Run stop: conversation=${conversationId != null}, eventTurn=$eventTurn, startedInRun=$startedInRun, " +
                "monitors=${running.size}, ended=${ending?.monitors?.size ?: 0}",
        )
    }

    /** 语音「结束任务」：这一轮（由语音会话另行取消）和这个对话的监听一起结束（5 秒内可撤销）。返回是否结束了监听。 */
    override fun endVoiceTaskMonitors(conversationId: String): Boolean =
        MonitorRegistry.endLater { it.conversationId == conversationId } != null

    /**
     * 「结束任务」连带结束的监听（等待撤销期满，可能来自悬浮球、常驻通知、语音或 ■）：每次结束在所属对话最后插一行
     * 「已结束任务·名称·时间」，撤销期内这行带「撤销」；撤销了就撤掉这行，期满就留下（规范 8.12「撤销」）。
     */
    private fun onMonitorEndings(endings: List<io.github.fartown.movo.agent.monitor.MonitorEnding>) {
        val current = endings.associateBy { it.id }
        val separator = appContext.getString(R.string.monitor_names_separator)
        for ((id, ending) in current) {
            if (id in shownEndings) continue
            shownEndings[id] = ending
            AndroidAgentLogger.info("Monitor task ended row: monitors=${ending.monitors.size}")
            // 已经排着的事件不再叫醒 Movo（你已经结束了这件事）；撤销后新来的照常处理。
            ending.monitors.forEach { monitorQueue.discardPending(it.id) }
            ending.monitors.groupBy { it.conversationId }.forEach { (conversationId, items) ->
                val row = MonitorEventMessageUi(
                    id = taskEndedRowId(id, conversationId),
                    taskId = id,
                    name = items.joinToString(separator) { it.name },
                    kind = MonitorEventKindUi.Ended,
                    seq = 0,
                    atMillis = ending.atMillis,
                    text = "",
                    reason = MonitorEndReason.ENDED_WITH_TASK.name,
                    startsTurn = false,
                )
                // 这一轮还在停的过程中：等它结束再补，不把正在执行的一轮切开。
                if (conversationsById[conversationId]?.isStreaming == true) {
                    deferredMonitorRows.getOrPut(conversationId) { mutableListOf() } += row
                } else {
                    appendMonitorRows(conversationId, listOf(row))
                }
            }
        }
        for (id in shownEndings.keys - current.keys) {
            val ending = shownEndings.remove(id) ?: continue
            // 撤销了（还有监听在跑）：撤掉这行；期满（都停了）：这行留下，「撤销」随之消失。
            val undone = ending.monitors.any { MonitorRegistry.find(it.id) != null }
            AndroidAgentLogger.info("Monitor task ending settled: undone=$undone")
            if (!undone) continue
            ending.monitors.map { it.conversationId }.distinct().forEach { conversationId ->
                val rowId = taskEndedRowId(id, conversationId)
                deferredMonitorRows[conversationId]?.removeAll { it.id == rowId }
                val state = conversationsById[conversationId] ?: return@forEach
                if (state.messages.none { it.id == rowId }) return@forEach
                updateConversation(conversationId, state.copy(messages = state.messages.filterNot { it.id == rowId }), updateTimestamp = false)
                refreshConversationSummaries()
                persistMonitorRowsSoon()
            }
        }
    }

    private fun taskEndedRowId(endingId: String, conversationId: String) = "monitor-task-ended-$endingId-$conversationId"

    /** 对话不在了、或是角色对话（不提供监听）：停掉它的监听，丢掉排着的事件。 */
    private fun dropMonitorsWithoutConversation() {
        monitorQueue.conversations().forEach { conversationId ->
            val roleplay = conversationsById[conversationId]?.let { it.roleplay != null }
                ?: storedPreviews[conversationId]?.characterName?.let { true }
            if (!isKnownConversation(conversationId) || roleplay == true) forgetMonitorsOf(conversationId)
        }
        deferredMonitorRows.keys.retainAll { isKnownConversation(it) }
    }

    /** 对话被删除 / 移除：停掉它的后台监听（结束进程在注册表自己的线程池里，不卡主线程），丢掉排着的事件。 */
    private fun forgetMonitorsOf(conversationId: String) {
        MonitorRegistry.stopConversation(conversationId, MonitorEndReason.SESSION_END)
        monitorQueue.dropConversation(conversationId)
        deferredMonitorRows.remove(conversationId)
    }

    /** 监听行变化合并保存：一段时间里的多次变化只写一次（每个事件都整库重写太重）。 */
    private fun persistMonitorRowsSoon() {
        if (monitorPersistJob?.isActive == true) return
        monitorPersistJob = scope.launch {
            delay(MONITOR_PERSIST_DEBOUNCE_MS)
            monitorPersistJob = null
            persistConversations()
        }
    }

    /**
     * 上个进程里被系统杀掉时还在运行的监听：在各自对话里补一行「监听已中断」，不唤醒、不是一轮的起点。
     * 时刻用开始时刻与最后一次心跳里较晚的那个（之后进程随时可能被杀；不是恢复的这一刻）。
     */
    private fun recordInterruptedMonitors() {
        val interrupted = MonitorRegistry.takeInterrupted(appContext)
        if (interrupted.isEmpty()) return
        // 会话在构造时已从数据库载入；推到 init 之后再补行。
        scope.launch {
            ensureLoaded(interrupted.map { it.conversationId })
            interrupted.filter { it.conversationId in conversationsById }.groupBy { it.conversationId }.forEach { (conversationId, items) ->
                appendMonitorRows(conversationId, items.map { item ->
                    MonitorEventMessageUi(
                        id = monitorRowId(item.taskId, 0, "interrupted"),
                        taskId = item.taskId,
                        name = item.name,
                        kind = MonitorEventKindUi.Ended,
                        seq = 0,
                        atMillis = item.interruptedAtMillis.takeIf { it > 0 } ?: System.currentTimeMillis(),
                        text = "",
                        reason = MonitorRowLabels.INTERRUPTED,
                        startsTurn = false,
                    )
                })
            }
        }
    }

    private fun monitorRowId(taskId: String, seq: Int, kind: String) = MonitorDeliveryQueue.rowKey(taskId, kind, seq)

    private fun MonitorNotice.toMonitorRow(startsTurn: Boolean): MonitorEventMessageUi = when (this) {
        is MonitorNotice.Event -> MonitorEventMessageUi(
            id = monitorRowId(taskId, seq, "event"),
            taskId = taskId, name = name, kind = MonitorEventKindUi.Event, seq = seq, atMillis = atMillis,
            text = text, startsTurn = startsTurn,
        )
        is MonitorNotice.Ended -> MonitorEventMessageUi(
            id = monitorRowId(taskId, eventCount, "ended"),
            taskId = taskId, name = name, kind = MonitorEventKindUi.Ended, seq = eventCount, atMillis = atMillis,
            // 命令自己结束时最后的输出作为可展开原文，退出码写进结束行；到期行记下时长（显示时按界面语言格式化）。
            text = tail.orEmpty(),
            reason = reason.name, startsTurn = startsTurn,
            exitCode = exitCode.takeIf { reason == MonitorEndReason.EXIT },
            limitMs = timeoutMs.takeIf { reason == MonitorEndReason.TIMEOUT },
        )
    }

    /** 交给运行时的「已消费」事件；退出码、时长附在 reason 后（`EXIT@1`、`TIMEOUT@7200000`），检查点恢复时也能还原结束行。 */
    private fun MonitorNotice.toRuntimeEvent(): AgentEvent.MonitorEventReceived {
        val row = toMonitorRow(startsTurn = false)
        val detail = row.exitCode?.toLong() ?: row.limitMs
        return AgentEvent.MonitorEventReceived(
            taskId = taskId, name = name, kind = if (row.kind == MonitorEventKindUi.Ended) "ended" else "event",
            seq = row.seq, atMillis = atMillis, text = row.text,
            reason = row.reason.orEmpty() + (detail?.let { "@$it" } ?: ""),
        )
    }

    private fun AgentEvent.MonitorEventReceived.toMonitorRow(): MonitorEventMessageUi {
        val reasonName = reason.substringBefore('@').ifBlank { null }
        val detail = reason.substringAfter('@', "").toLongOrNull()
        return MonitorEventMessageUi(
            id = monitorRowId(taskId, seq, kind),
            taskId = taskId,
            name = name,
            kind = if (kind == "ended") MonitorEventKindUi.Ended else MonitorEventKindUi.Event,
            seq = seq,
            atMillis = atMillis,
            text = text,
            reason = reasonName,
            startsTurn = false,
            historyAnchor = anchor,
            exitCode = detail?.toInt()?.takeIf { reasonName == MonitorEndReason.EXIT.name },
            limitMs = detail?.takeIf { reasonName == MonitorEndReason.TIMEOUT.name },
        )
    }

    private fun insertSupplementMessage(
        runId: String,
        index: Int,
        text: String,
        persist: Boolean = true,
    ) {
        updateMessages(runId) { messages ->
            AgentPendingResultRecovery.mergeSupplements(
                runId = runId,
                supplements = listOf(
                    AgentUiHandoffPayload.Supplement(
                        index = index,
                        text = text,
                        createdAt = System.currentTimeMillis(),
                    )
                ),
                messages = messages,
            )
        }
        refreshConversationSummaries()
        if (persist) persistConversations()
    }

    private fun completeLatestAssistantMessage(
        runId: String,
        fallbackContent: String,
    ) {
        updateMessages(runId) { messages ->
            val targetIndex = AgentRunMessageProjector.resultTargetIndex(runId, messages)
            if (targetIndex < 0) {
                messages + AgentMessageUi(
                    id = AgentRunMessageProjector.resultFallbackId(runId, messages),
                    content = fallbackContent,
                    isStreaming = false,
                    renderMarkdown = true,
                )
            } else {
                val targetRound = (messages[targetIndex] as AgentMessageUi).id
                    .assistantRound(runId)
                val sameRoundBlocks = targetRound?.let { round ->
                    messages.count { message ->
                        message is AgentMessageUi && message.id.assistantRound(runId) == round
                    }
                } ?: 0
                messages.mapIndexed { index, message ->
                    if (index == targetIndex && message is AgentMessageUi) {
                        message.copy(
                            content = if (sameRoundBlocks <= 1) {
                                fallbackContent
                            } else {
                                message.content.ifBlank { fallbackContent }
                            },
                            isStreaming = false,
                            renderMarkdown = true,
                        )
                    } else {
                        message
                    }
                }
            }
        }
    }

    private fun replaceLatestAssistantWithNotice(
        runId: String,
        code: SystemNoticeCode,
        detail: String? = null,
    ) {
        updateMessages(runId) { messages ->
            val targetIndex = AgentRunMessageProjector.resultTargetIndex(runId, messages)
            if (targetIndex < 0) {
                messages + SystemNoticeMessageUi(AgentRunMessageProjector.resultFallbackId(runId, messages), code, detail)
            } else {
                messages.mapIndexed { index, message ->
                    if (index == targetIndex && message is AgentMessageUi) {
                        SystemNoticeMessageUi(message.id, code, detail)
                    } else {
                        message
                    }
                }
            }
        }
    }

    private fun assistantMessagePrefix(runId: String): String =
        "assistant-$runId-"

    private fun assistantFallbackMessageId(runId: String): String =
        "${assistantMessagePrefix(runId)}1"

    private fun isAssistantMessageForRound(messageId: String, runId: String, round: Int): Boolean {
        val legacyId = "${assistantMessagePrefix(runId)}$round"
        return messageId == legacyId || messageId.startsWith("$legacyId-")
    }

    private fun String.assistantRound(runId: String): Int? =
        removePrefix(assistantMessagePrefix(runId))
            .takeIf { it != this }
            ?.substringBefore('-')
            ?.toIntOrNull()

    private fun updateMessages(
        runId: String,
        updateTimestamp: Boolean = true,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        updateConversation(
            conversationId = conversationId,
            state = state.copy(messages = transform(state.messages)),
            updateTimestamp = updateTimestamp,
        )
    }

    private fun applyConversationHistoryResult(
        runId: String,
        additions: List<AgentModelClient.ConversationMessage>,
        snapshot: AgentContextSnapshot? = null,
        retainPendingSupplements: Boolean = snapshot != null,
    ) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        val outcome = AgentRuntimeHistoryReducer.apply(state, runId, additions, snapshot, retainPendingSupplements)
        if (!outcome.alreadyApplied) updateConversation(conversationId, outcome.state)
    }

    private fun updateCurrentConversation(state: AgentChatHomeUiState) {
        if (state.input != homeState.input || state.messageEdit != homeState.messageEdit) {
            io.github.fartown.movo.ui.components.AgentConversationDraftStore.shared.get(selectedConversationId).edit {
                replace(0, length, state.input)
                selection = androidx.compose.ui.text.TextRange(state.input.length)
            }
        }
        val conversationId = selectedConversationId
        if (conversationId == null) {
            homeState = state
        } else {
            updateConversation(conversationId, state)
        }
    }

    private fun updateConversation(
        conversationId: String,
        state: AgentChatHomeUiState,
        updateTimestamp: Boolean = true,
    ) {
        conversationsById = conversationsById + (conversationId to state)
        if (updateTimestamp) {
            conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        }
        if (conversationId == selectedConversationId) {
            homeState = state
        }
    }

    private fun setConversationStreaming(runId: String, isStreaming: Boolean) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        updateConversation(conversationId, state.copy(isStreaming = isStreaming, isCompacting = state.isCompacting && isStreaming, isPaused = state.isPaused && isStreaming))
    }

    private fun conversationIdForRun(runId: String): String? = runConversationIds[runId]

    private fun conversationStateForRun(runId: String): AgentChatHomeUiState {
        val conversationId = conversationIdForRun(runId) ?: return emptyChatState(defaultThinkingEnabled)
        return conversationsById[conversationId] ?: emptyChatState(defaultThinkingEnabled)
    }

    private fun refreshConversationSummaries() {
        val summaries = (conversationsById.keys + storedConversationIds)
            .sortedByDescending { id ->
                conversationUpdatedAt[id] ?: 0L
            }
            .map { id ->
                // 没读进内存的对话用库里存的最后一行。
                val state = conversationsById[id]
                val lastMessage = if (state != null) state.messages.lastOrNull() else storedPreviews[id]?.lastMessage
                ConversationSummaryUi(
                    id = id,
                    title = conversationTitles[id].orEmpty().ifBlank {
                        appContext.getString(R.string.conversation_unnamed)
                    },
                    preview = when (lastMessage) {
                        is UserMessageUi -> AgentFileReferencePromptCodec
                            .parse(lastMessage.content)
                            .let { parsed ->
                                AgentFileReferencePolicy.titleSource(
                                    request = parsed.request,
                                    references = parsed.references,
                                )
                            }
                        is AgentMessageUi -> lastMessage.content.ifBlank {
                            appContext.getString(R.string.conversation_preview_reasoning)
                        }
                        is SystemNoticeMessageUi -> noticeText(lastMessage.code)
                        is ThinkingMessageUi -> appContext.getString(R.string.conversation_preview_reasoning)
                        is ToolActivityMessageUi -> appContext.getString(
                            R.string.conversation_preview_tool_call,
                            lastMessage.toolName,
                        )
                        // 最后一条是监听行（已停止、已中断等）：预览写这一行，不是空会话提示。
                        is MonitorEventMessageUi -> MonitorRowLabels.label(appContext, lastMessage)
                        else -> appContext.getString(R.string.conversation_preview_empty)
                    }.take(MAX_PREVIEW_CHARS),
                    timeLabel = if (state?.isStreaming == true) {
                        appContext.getString(R.string.time_now)
                    } else {
                        conversationUpdatedAt[id]?.let { timestamp ->
                            ConversationTimeLabels.label(
                                timestampMillis = timestamp,
                                locale = appContext.resources.configuration.locales[0],
                                use24HourClock = DateFormat.is24HourFormat(appContext),
                                yesterdayLabel = appContext.getString(R.string.time_yesterday),
                                recentLabel = appContext.getString(R.string.time_recent),
                            )
                        } ?: appContext.getString(R.string.time_recent)
                    },
                    updatedAtMillis = conversationUpdatedAt[id] ?: 0L,
                    mode = ConversationModeUi.Chat,
                    characterName = if (state != null) state.roleplay?.characterName else storedPreviews[id]?.characterName,
                    isActiveRun = state?.isStreaming == true,
                )
            }
        val query = conversationPaneState.searchQuery.trim()
        if (query.isBlank()) storedSearch = null
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = selectedConversationId,
            conversations = if (query.isBlank()) {
                summaries
            } else {
                contentMatchCache.keys.retainAll(conversationsById.keys)
                requestStoredSearch(query)
                summaries.mapNotNull { summary ->
                    val titleHit = summary.title.contains(query, ignoreCase = true)
                    val previewHit = summary.preview.contains(query, ignoreCase = true)
                    val content = conversationContentMatch(summary.id, query)
                    if (!titleHit && !previewHit && !content.matches) return@mapNotNull null
                    // 命中片段：优先内容里的命中处，其次预览（规范 8.6「搜索」）。
                    summary.copy(
                        matchSnippet = content.snippet
                            ?: if (previewHit) matchExcerpt(summary.preview, query) else summary.preview,
                    )
                }
            },
        )
    }

    // 内容匹配按（查询词, 会话状态引用）缓存：刷新摘要时未变化的会话不重复全文扫描。
    private val contentMatchCache = mutableMapOf<String, ContentMatchCacheEntry>()

    /** 没读进内存的对话的搜索命中：在库里查到的消息（查询词 → 对话 → 命中的消息）。 */
    private var storedSearch: Pair<String, Map<String, List<AgentChatMessageUi>>>? = null
    private var storedSearchJob: Job? = null

    private fun requestStoredSearch(query: String) {
        if (storedSearch?.first == query || storedSearchJob?.isActive == true && pendingStoredSearch == query) return
        pendingStoredSearch = query
        storedSearchJob?.cancel()
        storedSearchJob = scope.launch {
            val matches = withContext(Dispatchers.IO) { conversationRepository.searchMessages(query) }
            storedSearch = query to matches
            if (conversationPaneState.searchQuery.trim() == query) refreshConversationSummaries()
        }
    }
    private var pendingStoredSearch: String? = null

    private fun conversationContentMatch(conversationId: String, query: String): ContentMatchCacheEntry {
        val state = conversationsById[conversationId] ?: run {
            // 只在库里的对话：用库里查到的那几条消息判断命中、取片段（口径与内存里的一致）。
            val found = storedSearch?.takeIf { it.first == query }?.second?.get(conversationId)
                ?: return ContentMatchCacheEntry(query, null, matches = false, snippet = null)
            val partial = emptyChatState(defaultThinkingEnabled).copy(messages = found)
            val matches = partial.contentMatches(query) { code -> noticeText(code) }
            return ContentMatchCacheEntry(query, null, matches,
                if (matches) partial.contentMatchSnippet(query) { code -> noticeText(code) } else null)
        }
        val cached = contentMatchCache[conversationId]
        if (cached != null && cached.query == query && cached.state === state) {
            return cached
        }
        val matches = state.contentMatches(query) { code -> noticeText(code) }
        val snippet = if (matches) state.contentMatchSnippet(query) { code -> noticeText(code) } else null
        return ContentMatchCacheEntry(query, state, matches, snippet).also { contentMatchCache[conversationId] = it }
    }

    /**
     * 把已加载对话跟上次交给存储时相比变了的部分交给 [ConversationRepository]：对话信息、消息行、模型历史各写各的，
     * 没变的对话和没读进内存的对话都不碰。写入按调用顺序排队；要“先落库再继续”的调用方 await 返回值。
     */
    private fun persistConversations(onSaved: (() -> Unit)? = null): Deferred<Boolean> {
        val writes = mutableListOf<Deferred<Unit>>()
        val now = System.currentTimeMillis()
        conversationsById.forEach { (id, state) ->
            val previous = persistedStates[id]
            val meta = conversationTitles[id].orEmpty() to (conversationUpdatedAt[id] ?: now)
            if (previous === state && persistedMeta[id] == meta) return@forEach
            if (previous == null || persistedMeta[id] != meta || previous.storedMetaDiffers(state)) {
                writes += conversationRepository.saveConversation(id, state, meta.first, meta.second)
            }
            if (previous?.messages !== state.messages) {
                writes += conversationRepository.syncMessages(id, previous?.messages.orEmpty(), state.messages)
            }
            if (previous?.history !== state.history) {
                writes += conversationRepository.syncModelLog(id, ConversationModelMessageEntity.LOG_HISTORY,
                    previous?.history.orEmpty(), state.history)
            }
            val journal = state.journal.ifEmpty { state.history }
            val previousJournal = previous?.let { it.journal.ifEmpty { it.history } }
            if (previousJournal !== journal) {
                writes += conversationRepository.syncModelLog(id, ConversationModelMessageEntity.LOG_JOURNAL,
                    previousJournal.orEmpty(), journal)
            }
            persistedStates[id] = state
            persistedMeta[id] = meta
            storedConversationIds = storedConversationIds + id
        }
        val selection = selectedConversationId?.takeIf { it in storedConversationIds }
        if (selection != persistedSelection) {
            writes += conversationRepository.select(selection)
            persistedSelection = selection
        }
        return scope.async(Dispatchers.IO) {
            try {
                writes.forEach { it.await() }
                onSaved?.invoke()
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                AndroidAgentLogger.error("Agent conversation persistence failed: type=${throwable.safeLogType()}")
                false
            }
        }
    }

    /** 对话目录这一行存的字段（标题、时间另算）是否变了。 */
    private fun AgentChatHomeUiState.storedMetaDiffers(next: AgentChatHomeUiState): Boolean =
        reasoningEffort != next.reasoningEffort || appliedRuntimeRunIds != next.appliedRuntimeRunIds ||
            roleplay != next.roleplay || roleplayMessages != next.roleplayMessages

    /** 把还没读进内存的对话从库里读进来（切换对话、监听事件、运行恢复、导出等用到别的对话时）。 */
    private suspend fun ensureLoaded(conversationIds: Collection<String>) {
        val missing = withContext(Dispatchers.Main.immediate) {
            conversationIds.filter { it !in conversationsById && it in storedConversationIds }.distinct()
        }
        if (missing.isEmpty()) return
        val loaded = withContext(Dispatchers.IO) { missing.mapNotNull { conversationRepository.load(it) } }
        withContext(Dispatchers.Main.immediate) {
            loaded.filter { it.id !in conversationsById }.forEach { conversation ->
                conversationsById = conversationsById + (conversation.id to conversation.state)
                persistedStates[conversation.id] = conversation.state
                persistedMeta[conversation.id] = conversation.title to conversation.updatedAt
            }
        }
    }

    /**
     * 切走之后闲着的对话从内存里卸掉（库里已经是它最新的样子）：不是选中的、没在执行、没排队、没在等推荐追问、
     * 没有挂着的监听行，且上次交给存储的就是现在这份。
     */
    private fun unloadIdleConversations() {
        val keep = buildSet {
            selectedConversationId?.let(::add)
            addAll(runConversationIds.values)
            queuedTextSubmission?.conversationId?.let(::add)
            addAll(followUpJobs.keys)
            addAll(deferredMonitorRows.keys)
            addAll(monitorQueue.conversations())
        }
        val idle = conversationsById.filter { (id, state) ->
            id !in keep && !voiceSession.ownsConversation(id) && !state.isStreaming && !state.isCompacting &&
                state.messageEdit == null && persistedStates[id] === state && id in storedConversationIds
        }
        if (idle.isEmpty()) return
        storedPreviews = storedPreviews + idle.map { (id, state) ->
            id to ConversationRepository.Summary(
                id = id,
                title = conversationTitles[id].orEmpty(),
                updatedAt = conversationUpdatedAt[id] ?: 0L,
                characterName = state.roleplay?.characterName,
                lastMessage = state.messages.lastOrNull(),
            )
        }
        idle.keys.forEach { id ->
            persistedStates.remove(id)
            persistedMeta.remove(id)
            contentMatchCache.remove(id)
        }
        conversationsById = conversationsById - idle.keys
    }

    /** 从库里和内存里一起删掉这个对话。 */
    private fun forgetStoredConversation(conversationId: String) {
        if (conversationId in storedConversationIds) conversationRepository.deleteConversation(conversationId)
        storedConversationIds = storedConversationIds - conversationId
        storedPreviews = storedPreviews - conversationId
        persistedStates.remove(conversationId)
        persistedMeta.remove(conversationId)
        if (persistedSelection == conversationId) persistedSelection = null
    }

    private companion object {
        const val MAX_TITLE_CHARS = 24
        /** 后台监听在看不到的阻塞（运行时被别的入口占着、语音还在收尾）下的重试退避：1 秒起，逐次加倍，最长 30 秒。 */
        const val MONITOR_RETRY_MIN_MS = 1_000L
        const val MONITOR_RETRY_MAX_MS = 30_000L
        /** 监听行（事件行、结束行）合并保存：这段时间里的多次变化只写一次。 */
        const val MONITOR_PERSIST_DEBOUNCE_MS = 800L
        /** 执行中的界面行合并保存：这段时间里的多次变化只写一次。 */
        const val RUN_PROGRESS_PERSIST_DEBOUNCE_MS = 300L
        const val MAX_PREVIEW_CHARS = 48
        const val LEGACY_STOPPED_ERROR = "已停止"
        const val SYNTHETIC_STATUS_STOPPED = "movo_status:stopped"
        // 数据状态以较粗粒度发布，文字显现由独立的帧时钟连续推进。
        // 这与 Kimi 将流式数据和视觉动画分层的做法一致。
        const val STREAM_UI_UPDATE_INTERVAL_MS = 80L

        fun emptyChatState(thinkingEnabled: Boolean): AgentChatHomeUiState =
            AgentChatHomeUiState(
                messages = emptyList(),
                history = emptyList(),
                journal = emptyList(),
                input = "",
                isStreaming = false,
                thinkingEnabled = thinkingEnabled,
            )

        fun newConversationId(): String = "conv-${UUID.randomUUID()}"
    }
}

internal data class MessageRevisionImpact(
    val laterTurnCount: Int,
)

private data class PickerInputs(
    val providerId: String?,
    val modelId: String?,
    val providers: List<io.github.fartown.movo.data.model.ProviderSetting>,
    val chatGptLoggedIn: Boolean,
)

private data class ContentMatchCacheEntry(
    val query: String,
    val state: AgentChatHomeUiState?,
    val matches: Boolean,
    val snippet: String?,
)

private const val EXTERNAL_ARCHIVE_CONVERSATION_PREFIX = "archive-"

private fun archiveConversationId(source: String, conversationKey: String): String {
    val prefix = if (source == AgentRuntimeWire.MOVO_VOICE_HANDOFF_SOURCE) {
        ASSISTANT_CONVERSATION_PREFIX
    } else {
        EXTERNAL_ARCHIVE_CONVERSATION_PREFIX
    }
    return prefix + stableArchiveId("$source:$conversationKey")
}

private const val ASSISTANT_CONVERSATION_PREFIX = "assistant-"

private fun stableArchiveId(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

/**
 * 「设置 → 全部工具」：列出运行时实际使用的类型化工具（重构后只剩这些名字），按用户能理解的分组。
 * 名称与说明在 strings_tools.xml；与旧工具语义相同的沿用原来的文案。
 */
internal fun buildToolsState(context: Context): AgentToolsUiState {
    // 显式引用资源 id（不用 getIdentifier），避免 release 资源压缩把只按名字查的字符串删掉。
    fun typed(id: String): ToolItemUi {
        val (title, desc) = when (id) {
            "ui_observe" -> R.string.tool_typed_ui_observe_title to R.string.tool_typed_ui_observe_desc
            "ui_tap" -> R.string.tool_typed_ui_tap_title to R.string.tool_typed_ui_tap_desc
            "ui_swipe" -> R.string.tool_typed_ui_swipe_title to R.string.tool_typed_ui_swipe_desc
            "ui_scroll" -> R.string.tool_typed_ui_scroll_title to R.string.tool_typed_ui_scroll_desc
            "ui_key" -> R.string.tool_typed_ui_key_title to R.string.tool_typed_ui_key_desc
            "ui_wait" -> R.string.tool_typed_ui_wait_title to R.string.tool_typed_ui_wait_desc
            "ui_input" -> R.string.tool_typed_ui_input_title to R.string.tool_typed_ui_input_desc
            "clipboard_read" -> R.string.tool_typed_clipboard_read_title to R.string.tool_typed_clipboard_read_desc
            "clipboard_write" -> R.string.tool_typed_clipboard_write_title to R.string.tool_typed_clipboard_write_desc
            "browser_open" -> R.string.tool_typed_browser_open_title to R.string.tool_typed_browser_open_desc
            "browser_read" -> R.string.tool_typed_browser_read_title to R.string.tool_typed_browser_read_desc
            "browser_act" -> R.string.tool_typed_browser_act_title to R.string.tool_typed_browser_act_desc
            "app_search" -> R.string.tool_typed_app_search_title to R.string.tool_typed_app_search_desc
            "app_open" -> R.string.tool_typed_app_open_title to R.string.tool_typed_app_open_desc
            "app_control" -> R.string.tool_typed_app_control_title to R.string.tool_typed_app_control_desc
            "setting_read" -> R.string.tool_typed_setting_read_title to R.string.tool_typed_setting_read_desc
            "setting_write" -> R.string.tool_typed_setting_write_title to R.string.tool_typed_setting_write_desc
            "device_toggle" -> R.string.tool_typed_device_toggle_title to R.string.tool_typed_device_toggle_desc
            "device_read" -> R.string.tool_typed_device_read_title to R.string.tool_typed_device_read_desc
            "clock_create" -> R.string.tool_typed_clock_create_title to R.string.tool_typed_clock_create_desc
            "clock_read" -> R.string.tool_typed_clock_read_title to R.string.tool_typed_clock_read_desc
            "device_diagnostics" -> R.string.tool_typed_device_diagnostics_title to R.string.tool_typed_device_diagnostics_desc
            "personal_search" -> R.string.tool_typed_personal_search_title to R.string.tool_typed_personal_search_desc
            "file_search" -> R.string.tool_typed_file_search_title to R.string.tool_typed_file_search_desc
            "terminal_job" -> R.string.tool_typed_terminal_job_title to R.string.tool_typed_terminal_job_desc
            "conversation_read" -> R.string.tool_typed_conversation_read_title to R.string.tool_typed_conversation_read_desc
            "skill_read" -> R.string.tool_typed_skill_read_title to R.string.tool_typed_skill_read_desc
            "skill_install" -> R.string.tool_typed_skill_install_title to R.string.tool_typed_skill_install_desc
            "monitor_start" -> R.string.tool_typed_monitor_start_title to R.string.tool_typed_monitor_start_desc
            "monitor_stop" -> R.string.tool_typed_monitor_stop_title to R.string.tool_typed_monitor_stop_desc
            "monitor_list" -> R.string.tool_typed_monitor_list_title to R.string.tool_typed_monitor_list_desc
            "notify_user" -> R.string.tool_typed_notify_user_title to R.string.tool_typed_notify_user_desc
            "ask_user" -> R.string.tool_typed_ask_user_title to R.string.tool_typed_ask_user_desc
            "tool_search" -> R.string.tool_typed_tool_search_title to R.string.tool_typed_tool_search_desc
            "mcp_find" -> R.string.tool_typed_mcp_find_title to R.string.tool_typed_mcp_find_desc
            "mcp_call" -> R.string.tool_typed_mcp_call_title to R.string.tool_typed_mcp_call_desc
            else -> error("Unknown typed tool card: $id")
        }
        return ToolItemUi(id, context.getString(title), context.getString(desc))
    }
    fun item(id: String, title: Int, desc: Int) = ToolItemUi(id, context.getString(title), context.getString(desc))
    return AgentToolsUiState(
        groups = listOf(
            ToolGroupUi(
                id = "screen",
                title = context.getString(R.string.state_screens_and_controls_3f095b),
                tools = listOf("ui_observe", "ui_tap", "ui_swipe", "ui_scroll", "ui_key", "ui_wait").map(::typed),
            ),
            ToolGroupUi(
                id = "text",
                title = context.getString(R.string.state_text_and_clipboard_3a7340),
                tools = listOf("ui_input", "clipboard_read", "clipboard_write").map(::typed),
            ),
            ToolGroupUi(
                id = "web",
                title = context.getString(R.string.state_web_browsing_e56105),
                tools = listOf("browser_open", "browser_read", "browser_act").map(::typed),
            ),
            ToolGroupUi(
                id = "app",
                title = context.getString(R.string.state_applications_and_systems_9624e6),
                tools = listOf("app_search", "app_open", "app_control", "setting_read", "setting_write", "device_toggle").map(::typed),
            ),
            ToolGroupUi(
                id = "device_direct",
                title = context.getString(R.string.state_direct_access_to_equipment_eda92c),
                tools = listOf(
                    typed("device_read"),
                    typed("clock_create"),
                    typed("clock_read"),
                    item("media_control", R.string.tool_ui_media_control_585edc, R.string.tool_ui_play_pause_and_switch_songs_without_operating_th_311cb8),
                    item("volume_set", R.string.tool_ui_set_volume_85a691, R.string.tool_ui_set_by_media_alarm_clock_ringtone_and_other_chan_3fcc3e),
                    typed("device_diagnostics"),
                ),
            ),
            ToolGroupUi(
                id = "personal_data",
                title = context.getString(R.string.state_direct_access_to_personal_data_387d7b),
                tools = listOf(
                    typed("personal_search"),
                    item("sms_code_read", R.string.tool_ui_read_verification_code_7d1121, R.string.tool_ui_only_extract_verification_codes_from_recent_sms__0fb8c1),
                    item("usage_read", R.string.tool_ui_app_usage_statistics_ee20d3, R.string.tool_ui_summarize_recent_app_usage_by_foreground_duratio_b346c8),
                    item("health_read", R.string.tool_ui_health_summary_951c0b, R.string.tool_ui_summarize_steps_sleep_exercise_and_body_metrics_6ff66f),
                    item("wifi_password_read", R.string.tool_ui_wi_fi_password_80e9a4, R.string.tool_ui_read_the_network_credentials_saved_by_the_phone_96d43a),
                    typed("file_search"),
                ),
            ),
            ToolGroupUi(
                id = "terminal",
                title = context.getString(R.string.state_terminal_and_files_ae7c54),
                tools = listOf(
                    item("terminal_run", R.string.tool_ui_execute_command_bf1627, R.string.tool_ui_directly_execute_a_single_shell_command_c40cef),
                    typed("terminal_job"),
                    item("file_read", R.string.tool_ui_read_file_dc995c, R.string.tool_ui_read_the_contents_of_mobile_phone_files_bf3066),
                    item("file_write", R.string.tool_ui_write_file_e620fd, R.string.tool_ui_write_or_overwrite_mobile_files_29fae4),
                    item("file_list", R.string.tool_ui_list_directory_96e765, R.string.tool_ui_list_directory_contents_feff30),
                ),
            ),
            ToolGroupUi(
                id = "memory",
                title = context.getString(R.string.state_memory_b55ff5),
                tools = listOf(
                    item("memory_read", R.string.tool_ui_read_memory_979135, R.string.tool_ui_paged_to_read_or_retrieve_long_term_memory_in_me_88afc4),
                    item("memory_write", R.string.tool_ui_organize_memory_2b08eb, R.string.tool_ui_partially_update_append_or_clear_long_term_memor_c1bab6),
                    typed("conversation_read"),
                ),
            ),
            ToolGroupUi(
                id = "skills",
                title = context.getString(R.string.tool_typed_group_skills),
                tools = listOf("skill_read", "skill_install").map(::typed),
            ),
            ToolGroupUi(
                id = "monitor",
                title = context.getString(R.string.tool_typed_group_monitor),
                tools = listOf("monitor_start", "monitor_stop", "monitor_list", "notify_user").map(::typed),
            ),
            ToolGroupUi(
                id = "meta",
                title = context.getString(R.string.tool_typed_group_meta),
                tools = listOf("ask_user", "tool_search", "mcp_find", "mcp_call").map(::typed),
            ),
        )
    )
}

private fun buildPermissionHealthState(context: Context): PermissionHealthUiState {
    val backgroundRunningEnabled = isIgnoringBatteryOptimizations(context)
    val overlayEnabled = Settings.canDrawOverlays(context)
    val appListEnabled = hasAppListAccess(context)
    val accessibilityEnabled = isAgentAccessibilityEnabled(context) || AgentAccessibilityService.isAvailable()
    val rootEnabled = RootAccess.isGranted
    val notificationsEnabled = context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
    val locationAccess = DeviceLocationProvider.accessState(context)
    val notificationHistoryEnabled = io.github.fartown.movo.agent.device.AgentNotificationHistoryService.isEnabled(context)
    val usageAccessEnabled = io.github.fartown.movo.agent.tool.AgentPersonalContextTools.hasUsageAccess(context)
    val microphoneEnabled = androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.RECORD_AUDIO,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    return PermissionHealthUiState(
        items = listOf(
            PermissionHealthItemUi(
                id = "background",
                title = context.getString(R.string.state_background_running_permission_dde21b),
                summary = "",
                status = if (backgroundRunningEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (backgroundRunningEnabled) null else context.getString(R.string.state_ui_to_open_13ec17),
            ),
            PermissionHealthItemUi(
                id = "overlay",
                title = context.getString(R.string.state_floating_window_permissions_076b77),
                summary = "",
                status = if (overlayEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (overlayEnabled) null else context.getString(R.string.state_ui_to_authorize_762ec4),
            ),
            PermissionHealthItemUi(
                id = "microphone",
                title = context.getString(R.string.permission_microphone_title),
                summary = context.getString(R.string.permission_microphone_summary),
                status = if (microphoneEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (microphoneEnabled) null else context.getString(R.string.state_ui_to_authorize_762ec4),
            ),
            PermissionHealthItemUi(
                id = "app_list",
                title = context.getString(R.string.state_application_list_reading_135f16),
                summary = "",
                status = if (appListEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (appListEnabled) null else context.getString(R.string.state_ui_to_open_13ec17),
            ),
            PermissionHealthItemUi(
                id = "location",
                title = context.getString(R.string.state_location_permissions_b53f9c),
                summary = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> context.getString(R.string.state_ui_used_to_understand_the_location_of_mobile_phones_af52e9)
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> context.getString(R.string.capability_location_foreground)
                    DeviceLocationProvider.AccessState.DISABLED -> context.getString(R.string.state_ui_system_location_service_is_turned_off_3902e7)
                    DeviceLocationProvider.AccessState.AVAILABLE -> context.getString(R.string.state_ui_only_read_when_the_agent_calls_the_tool_8cf77b)
                },
                status = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> PermissionStatusUi.Missing
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> PermissionStatusUi.Warning
                    DeviceLocationProvider.AccessState.DISABLED -> PermissionStatusUi.Disabled
                    DeviceLocationProvider.AccessState.AVAILABLE -> PermissionStatusUi.Available
                },
                primaryActionLabel = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> context.getString(R.string.state_ui_to_authorize_762ec4)
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> context.getString(R.string.state_ui_go_to_settings_1f2998)
                    DeviceLocationProvider.AccessState.DISABLED -> context.getString(R.string.state_ui_to_open_13ec17)
                    DeviceLocationProvider.AccessState.AVAILABLE -> null
                },
            ),
            PermissionHealthItemUi(
                id = "notification_history",
                title = context.getString(R.string.state_notice_of_use_rights_1ae29a),
                summary = if (notificationHistoryEnabled) {
                    context.getString(R.string.state_ui_natively_bounded_storage_of_last_7_days_of_notif_ca7f01)
                } else {
                    context.getString(R.string.state_ui_start_logging_searchable_notification_history_af_b36af6)
                },
                status = if (notificationHistoryEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (notificationHistoryEnabled) null else context.getString(R.string.state_ui_to_authorize_762ec4),
            ),
            PermissionHealthItemUi(
                id = "usage_access",
                title = context.getString(R.string.state_usage_access_20f1f8),
                summary = context.getString(R.string.state_used_to_read_recently_opened_applications_and_foregr_73e796),
                status = if (usageAccessEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (usageAccessEnabled) null else context.getString(R.string.state_ui_to_authorize_762ec4),
            ),
            PermissionHealthItemUi(
                id = "accessibility",
                title = context.getString(R.string.state_accessibility_permissions_f80103),
                summary = "",
                status = if (accessibilityEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (accessibilityEnabled) null else context.getString(R.string.state_ui_to_open_13ec17),
            ),
            PermissionHealthItemUi(
                id = "notifications",
                title = context.getString(R.string.capability_notifications_title),
                summary = context.getString(R.string.capability_notifications_summary),
                status = if (notificationsEnabled) PermissionStatusUi.Available else PermissionStatusUi.Disabled,
                primaryActionLabel = context.getString(R.string.state_ui_go_to_settings_1f2998),
            ),
            PermissionHealthItemUi(
                id = "root",
                title = context.getString(R.string.capability_enhancements),
                summary = context.getString(R.string.capability_optional_root),
                status = if (rootEnabled) PermissionStatusUi.Available else PermissionStatusUi.Disabled,
                primaryActionLabel = if (rootEnabled) null else context.getString(R.string.state_ui_to_open_13ec17),
            ),
        )
    )
}

private fun agentBooleanForUi(key: String): Boolean {
    return Prefs.isEnabled(key)
}

private fun AgentTokenUsage.toUi(): TokenUsageUi =
    TokenUsageUi(
        contextTokens = contextTokens,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
        cachedTokens = cachedTokens,
    )

private fun isAgentAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(
        context,
        AgentAccessibilityService::class.java,
    ).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
}

private fun hasAppListAccess(context: Context): Boolean {
    return try {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)
        packages.size > 10
    } catch (e: Exception) {
        false
    }
}
