package io.github.fartown.movo.ui.model

import androidx.compose.runtime.Immutable
import io.github.fartown.movo.agent.model.AgentFileReference
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.roleplay.RoleplayBinding
import io.github.fartown.movo.agent.roleplay.RoleplayMessageState
import io.github.fartown.movo.data.model.ReasoningEffort

@Immutable
internal data class AgentChatUiState(
    val messages: List<AgentChatMessageUi>,
    val history: List<AgentModelClient.ConversationMessage> = emptyList(),
    // 完整脱敏历史独立于模型投影；摘要替换 history 时不覆盖 journal。
    val journal: List<AgentModelClient.ConversationMessage> = emptyList(),
    val input: String,
    val isStreaming: Boolean,
    val isCompacting: Boolean = false,
    /** 本轮在悬浮球里被暂停（规范 8.2 已暂停：主按钮 ▶，执行卡底部栏「结束任务」）；不持久化。 */
    val isPaused: Boolean = false,
    val thinkingEnabled: Boolean,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.fromLegacy(thinkingEnabled),
    val availableReasoningEfforts: List<ReasoningEffort> = emptyList(),
    val pendingImages: List<PendingImageUi> = emptyList(),
    val pendingFileReferences: List<PendingFileReferenceUi> = emptyList(),
    val appliedRuntimeRunIds: List<String> = emptyList(),
    val messageEdit: MessageEditUiState? = null,
    val roleplay: RoleplayBinding? = null,
    val roleplayMessages: RoleplayMessageState = RoleplayMessageState(),
) {
    val canCompactContext: Boolean get() = !isStreaming && messageEdit == null && history.any {
        !it.contextSummary && (it.role == "assistant" || it.role == "tool")
    }
}

@Immutable
sealed interface AgentChatMessageUi {
    val id: String
}

@Immutable
data class UserMessageUi(
    override val id: String,
    val content: String,
    val images: List<String> = emptyList(),
    val isEdited: Boolean = false,
    /**
     * 这条消息发起的这一轮任务的开始 / 结束时刻（毫秒）：执行卡的计时、用时、起止时间按整轮算，与运行日志一致
     * （2026-09-28 定）。旧数据、恢复的任务没有时为 null，执行卡退回按步骤时间算。
     */
    val runStartedAtMillis: Long? = null,
    val runFinishedAtMillis: Long? = null,
) : AgentChatMessageUi

@Immutable
data class AgentMessageUi(
    override val id: String,
    val content: String,
    val isStreaming: Boolean = false,
    val renderMarkdown: Boolean = true,
    val usage: TokenUsageUi? = null,
    val characterEditable: Boolean = false,
    val candidateCount: Int = 1,
    val selectedCandidate: Int = 0,
) : AgentChatMessageUi

/**
 * 后台监听的一行（规范 8.12）：事件行「监听事件·名称·时间」或结束行（已停止 / 结束 / 中断）。
 * [startsTurn] = 这一行唤醒了新的一轮（事件轮的起点，按「轮」聚合时与用户消息同等对待）；
 * 运行中并入当前轮的事件不是起点，像补充一样留在本轮里。
 */
@Immutable
data class MonitorEventMessageUi(
    override val id: String,
    val taskId: String,
    val name: String,
    val kind: MonitorEventKindUi,
    val seq: Int,
    val atMillis: Long,
    /** 事件原文（展开时显示）；结束行为命令退出前最后的输出（可展开），没有时为空。 */
    val text: String,
    /** 结束原因（MonitorEndReason 名）；事件行为 null。 */
    val reason: String? = null,
    /** 命令自己结束时的退出码（写进结束行）。 */
    val exitCode: Int? = null,
    /** 到期结束时这个监听的最长时长（「到 2 小时上限」）。 */
    val limitMs: Long? = null,
    val startsTurn: Boolean = false,
    /** 模型历史里对应一条 user 条目（事件轮的第一行、运行中每批并入的第一行）。 */
    val historyAnchor: Boolean = false,
    /** 作为事件轮起点时，这一轮的起止时刻（与 [UserMessageUi.runStartedAtMillis] 同义）。 */
    val runStartedAtMillis: Long? = null,
    val runFinishedAtMillis: Long? = null,
) : AgentChatMessageUi

enum class MonitorEventKindUi { Event, Ended }

/** 一轮任务的起点：用户发出的消息（不含执行中的补充），或唤醒事件轮的监听事件。 */
fun AgentChatMessageUi.isTurnStart(): Boolean = when (this) {
    is UserMessageUi -> !(id.startsWith("user-") && id.contains("-supplement-"))
    is MonitorEventMessageUi -> startsTurn
    else -> false
}

enum class SystemNoticeCode(val wireValue: String) {
    Stopped("stopped"),
    EmptyResult("empty_result"),
    RuntimeFailed("runtime_failed"),
    ModelRetry("model_retry"),
    ContextCompaction("context_compaction"),
    Interrupted("interrupted");

    companion object {
        fun fromWireValue(value: String): SystemNoticeCode? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

/** Movo 自己生成的消息只保存稳定状态码，展示时再按当前语言解析。 */
@Immutable
data class SystemNoticeMessageUi(
    override val id: String,
    val code: SystemNoticeCode,
    val detail: String? = null,
    val contextTokens: Int? = null,
    /** 仅运行期存在的进行中标记，不随消息持久化；恢复的历史通知始终视为已结束。 */
    val running: Boolean = false,
) : AgentChatMessageUi

@Immutable
data class TokenUsageUi(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
) {
    val isEmpty: Boolean
        get() = contextTokens == null &&
            inputTokens == null &&
            outputTokens == null &&
            reasoningTokens == null &&
            cachedTokens == null
}

@Immutable
data class ThinkingMessageUi(
    override val id: String,
    val content: String,
    val isStreaming: Boolean,
    val elapsedSeconds: Int? = null,
    val collapsed: Boolean = false,
) : AgentChatMessageUi

/**
 * 首页的 Run trace 入口卡片：展示 Agent 当前可调用的能力分组。
 */
@Immutable
data class RunTraceMessageUi(
    override val id: String,
    val capabilities: List<CapabilityUi>,
) : AgentChatMessageUi

@Immutable
data class CapabilityUi(
    val title: String,
    val items: List<String>,
)

/**
 * 工具调用摘要：出现在消息流中，显示当前/最近一步调用了哪些工具。
 */
@Immutable
data class ToolSummaryMessageUi(
    override val id: String,
    val tools: List<String>,
) : AgentChatMessageUi

@Immutable
data class ToolActivityMessageUi(
    override val id: String,
    val toolName: String,
    val status: ToolActivityStatusUi,
    val argumentsSummary: String,
    val command: String? = null,
    val resultSummary: String? = null,
    val imageCount: Int = 0,
    /** 工具开始 / 结束时刻（毫秒）；旧数据或旧版本 Runtime 没有时为 null，界面不显示用时。 */
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    /** 展开后的结构化内容（工具可视化方案）；旧数据、非类型化工具、重启后的临时视图为 null。 */
    val view: io.github.fartown.movo.agent.tools.core.ToolUiView? = null,
) : AgentChatMessageUi

enum class ToolActivityStatusUi {
    Running,
    Success,
    Failed,
    Unknown,
}

/**
 * 建议语 chip 行。
 */
@Immutable
data class SuggestionChipsMessageUi(
    override val id: String,
    val prompts: List<String>,
) : AgentChatMessageUi

@Immutable
data class PendingImageUi(
    val id: String,
    val uri: String,
    val dataUrl: String,
    val mimeType: String,
)

@Immutable
data class PendingFileReferenceUi(
    val id: String,
    val reference: AgentFileReference,
)

@Immutable
data class MessageEditUiState(
    val targetMessageId: String,
    val previousInput: String,
    val previousImages: List<PendingImageUi>,
    val previousFileReferences: List<PendingFileReferenceUi>,
    val hasLaterTurns: Boolean,
    val preserveFollowingMessages: Boolean = false,
)
