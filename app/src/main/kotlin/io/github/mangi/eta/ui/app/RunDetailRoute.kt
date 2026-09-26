package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.AgentChatInputBar
import io.github.mangi.eta.ui.components.AgentConversationDraftStore
import io.github.mangi.eta.ui.components.AgentTimelineEntry
import io.github.mangi.eta.ui.components.LocalConversationComposer
import io.github.mangi.eta.ui.components.toTimelineEntries
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.screens.run.RunDetailScreen

/**
 * 执行详情页的装配：从当前会话里找出 [workKey] 对应的执行卡步骤；执行中底部挂同一个输入框
 * （发送 = 补充当前任务，空时 = 停止 ■，规范 8.8「底部」）。
 */
@Composable
internal fun RunDetailRoute(
    agentState: AgentAppState,
    workKey: String,
    onBack: () -> Unit,
    onOpenBrowser: () -> Unit,
    onSendMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val state = agentState.homeState
    val steps = state.messages.toTimelineEntries()
        .filterIsInstance<AgentTimelineEntry.WorkProcess>()
        .firstOrNull { it.key == workKey }
        ?.messages
    val conversationId = agentState.conversationPaneState.selectedConversationId
    val unnamed = stringResource(R.string.conversation_unnamed)
    val title = agentState.conversationPaneState.conversations
        .firstOrNull { it.id == conversationId }
        ?.title
        ?.takeIf { it.isNotBlank() && it != unnamed }
        ?: stringResource(R.string.movo_run_detail_title)
    RunDetailScreen(
        title = title,
        steps = steps,
        onBack = onBack,
        onOpenBrowser = onOpenBrowser,
        onSwitchToApp = { label -> launchAppByLabel(context, label) },
        composer = {
            CompositionLocalProvider(
                LocalConversationComposer provides AgentConversationDraftStore.shared.get(conversationId, state.input),
            ) {
                AgentChatInputBar(
                    input = state.input,
                    modelPickerState = agentState.modelPickerState,
                    isCompacting = state.isCompacting,
                    contextUsage = AgentContextUsageUi(null, null),
                    showContextUsage = false,
                    isStreaming = state.isStreaming,
                    reasoningEffort = state.reasoningEffort,
                    availableReasoningEfforts = state.availableReasoningEfforts,
                    pendingImages = state.pendingImages,
                    pendingFileReferences = state.pendingFileReferences,
                    isEditingMessage = false,
                    editHasLaterTurns = false,
                    preserveFollowingMessages = false,
                    onReasoningEffortChange = agentState::updateReasoningEffort,
                    onCompactContext = {},
                    canCompactContext = false,
                    onModelSelected = agentState::selectModel,
                    onSubmit = onSendMessage,
                    onStop = agentState::stopCurrentRun,
                    onAttachImage = agentState::attachImage,
                    onRemoveImage = agentState::removePendingImage,
                    onAttachFiles = agentState::attachFiles,
                    onAttachFolder = agentState::attachFolder,
                    onAttachFilePath = agentState::attachFilePath,
                    onRemoveFileReference = agentState::removePendingFileReference,
                    onCancelMessageEdit = agentState::cancelMessageEdit,
                )
            }
        },
    )
}

/** 「切过去看」：按 App 名找到可启动的包并切到前台（与 launch_app 同样用 getLaunchIntentForPackage）。 */
private fun launchAppByLabel(context: Context, label: String) {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val packageName = pm.queryIntentActivities(launcher, 0)
        .firstOrNull { it.loadLabel(pm).toString() == label }
        ?.activityInfo?.packageName ?: return
    val intent = pm.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
    runCatching { context.startActivity(intent) }
}
