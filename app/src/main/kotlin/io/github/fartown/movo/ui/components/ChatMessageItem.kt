package io.github.fartown.movo.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownA11yLabels
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHeader
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.m3.Markdown
import io.github.fartown.movo.ui.markdown.rememberCitationMarkdownAnnotator
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.offset
import io.github.fartown.movo.R
import io.github.fartown.movo.ui.components.movo.movoClickable
import io.github.fartown.movo.ui.components.movo.completionGlint
import io.github.fartown.movo.agent.browser.AgentBrowserSession
import io.github.fartown.movo.agent.browser.BrowserSessionSnapshot
import io.github.fartown.movo.agent.model.AgentFileReferencePromptCodec
import io.github.fartown.movo.agent.overlay.toolDisplayName
import io.github.fartown.movo.ui.model.AgentChatMessageUi
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.RunTraceMessageUi
import io.github.fartown.movo.ui.model.SuggestionChipsMessageUi
import io.github.fartown.movo.ui.model.SystemNoticeCode
import io.github.fartown.movo.ui.model.SystemNoticeMessageUi
import io.github.fartown.movo.ui.model.ThinkingMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.ToolSummaryMessageUi
import io.github.fartown.movo.ui.model.UserMessageUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMElementTypes.TABLE
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CHECK_BOX
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.RichTooltip
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.basic.TooltipDefaults
import top.yukonga.miuix.kmp.basic.rememberTooltipState
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun rememberDataUrlBitmap(dataUrl: String) = remember(dataUrl) {
    decodeDataUrlBitmap(dataUrl)
}

private fun decodeDataUrlBitmap(dataUrl: String): ImageBitmap? {
    val base64 = dataUrl.substringAfter("base64,", "")
    if (base64.isBlank()) return null
    return runCatching {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

/**
 * 等待首个文本片段时的轻量反馈。
 */
/**
 * 等待首个事件时的「正在处理」指示（规范 9.3.2 Q6）：16 小光球，位置与执行卡标题图标对齐（左 36）；
 * 从首页发出第一句时，首页光球由飞行层缩小飞到这里，落地前这里先隐藏。首个事件到达后它随占位一起消失。
 */
@Composable
internal fun WaitingOrb() {
    val chatFlight = LocalChatFlight.current
    androidx.compose.runtime.DisposableEffect(chatFlight) { onDispose { chatFlight?.reportWaitingOrb(null) } }
    Box(modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp)) {
        io.github.fartown.movo.ui.components.movo.MovoOrb(
            size = 16.dp,
            modifier = Modifier
                .onGloballyPositioned { chatFlight?.reportWaitingOrb(it.windowRect()) }
                .graphicsLayer { alpha = if (chatFlight?.hidesWaitingOrb() == true) 0f else 1f },
        )
    }
}

@Composable
fun AITypingIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val delay = index * 150
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = delay, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .graphicsLayer(alpha = alpha)
                    .background(MiuixTheme.colorScheme.onSurfaceVariantSummary, CircleShape)
            )
        }
    }
}

/**
 * 只有正在执行的状态才持有无限动画。历史思考和工具条目保持静态，避免长会话里
 * 每个已完成节点都持续产生帧时钟与状态更新。
 */
@Composable
private fun rememberActivePulse(
    active: Boolean,
    label: String,
): Float {
    if (!active) return 1f
    val transition = rememberInfiniteTransition(label = label)
    val alpha by transition.animateFloat(
        initialValue = 0.58f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(820, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "${label}_alpha",
    )
    return alpha
}

@Composable
internal fun ChatMessageItem(
    message: AgentChatMessageUi,
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    showBrowserShortcut: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    retainedStreamingState: StreamingMarkdownState? = null,
    showCopyAction: Boolean = true,
    showMessageActions: Boolean = false,
    messageActionsEnabled: Boolean = true,
    isEditing: Boolean = false,
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {},
    onSelectReplyCandidate: (String, Int) -> Unit = { _, _ -> },
) {
    when (message) {
        is UserMessageUi -> UserMessageBubble(
            message = message,
            actionsEnabled = messageActionsEnabled,
            isEditing = isEditing,
            onEdit = { onEditMessage(message.id) },
            onDelete = { onDeleteMessage(message.id) },
            modifier = modifier,
        )
        is AgentMessageUi -> AgentMessageBlock(
            message = message,
            retainedStreamingState = retainedStreamingState,
            showCopyAction = showCopyAction,
            showMessageActions = showMessageActions,
            messageActionsEnabled = messageActionsEnabled,
            onDelete = { onDeleteMessage(message.id) },
            onRegenerate = { onRegenerateMessage(message.id) },
            onEdit = { onEditMessage(message.id) },
            onSelectCandidate = { onSelectReplyCandidate(message.id, it) },
            modifier = modifier,
        )
        is SystemNoticeMessageUi -> if (message.code == SystemNoticeCode.ContextCompaction) {
            ContextCompactionMarker(message = message, modifier = modifier)
        } else if (message.code == SystemNoticeCode.RuntimeFailed || message.code == SystemNoticeCode.Interrupted) {
            RunFailureCard(
                message = message,
                actionsEnabled = messageActionsEnabled,
                onRetry = { onRegenerateMessage(message.id) },
                onDelete = { onDeleteMessage(message.id) },
                modifier = modifier,
            )
        } else Column(modifier = modifier) {
            AgentMessageBlock(
                message = AgentMessageUi(
                    id = message.id,
                    content = buildString {
                        append(
                            stringResource(
                                when (message.code) {
                                    SystemNoticeCode.Stopped -> R.string.system_notice_stopped
                                    SystemNoticeCode.EmptyResult -> R.string.system_notice_empty_result
                                    SystemNoticeCode.ContextCompaction -> R.string.context_compaction
                                    SystemNoticeCode.ModelRetry -> R.string.system_notice_model_retry
                                    SystemNoticeCode.RuntimeFailed -> R.string.system_notice_runtime_failed
                                    SystemNoticeCode.Interrupted -> R.string.system_notice_interrupted
                                },
                            ),
                        )
                        message.detail?.takeIf(String::isNotBlank)?.let { detail ->
                            append("\n\n")
                            append(detail)
                        }
                    },
                    renderMarkdown = false,
                ),
                retainedStreamingState = null,
                showCopyAction = showCopyAction,
                showMessageActions = showMessageActions,
                messageActionsEnabled = messageActionsEnabled,
                onDelete = { onDeleteMessage(message.id) },
                onRegenerate = { onRegenerateMessage(message.id) },
            )
            if (message.code == SystemNoticeCode.RuntimeFailed || message.code == SystemNoticeCode.Interrupted) {
                RunLogLink(messageId = message.id)
            }
        }
        is ThinkingMessageUi -> ThinkingRow(
            message = message,
            retainedStreamingState = retainedStreamingState,
            modifier = modifier,
            compact = compact,
        )
        is RunTraceMessageUi -> RunTraceRow(message = message, onClick = onRunTraceClick, modifier = modifier)
        is ToolActivityMessageUi -> ToolActivityInline(
            message = message,
            onOpenBrowser = onOpenBrowser,
            showBrowserShortcut = showBrowserShortcut,
            modifier = modifier,
            compact = compact,
        )
        is ToolSummaryMessageUi -> ToolSummaryInline(message = message, modifier = modifier, compact = compact)
        is SuggestionChipsMessageUi -> SuggestionChipsRow(message = message, onSuggestionClick = onSuggestionClick, modifier = modifier)
    }
}

/** 打开执行详情页（规范 8.8）；参数是执行卡的键。对话浮层等没有详情页的宿主为 null，卡片仍在原地展开。 */
internal val LocalOpenRunDetail = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

/** 执行中正在操作的 App：取最近一次成功的 launch_app 结果里的 App 名（「已打开 · 美团」）。 */
internal fun List<AgentChatMessageUi>.operatingAppName(): String? =
    filterIsInstance<ToolActivityMessageUi>()
        .lastOrNull { it.toolName == "launch_app" && it.status == ToolActivityStatusUi.Success }
        ?.resultSummary
        ?.substringAfter("·", missingDelimiterValue = "")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

/**
 * 工作过程卡（规范 8.1「工作过程」、Figma「05 · 执行中」）：一次执行的思考与工具调用收束在一张卡里。
 * 展开：圆角 28、白底 + 发丝描边、无阴影；头部 48（执行中 16 小光球 +「正在执行·第 N 步」Q3 光带 + 收起箭头）
 * → 0.5 分隔线（左右内缩 16）→ 步骤时间线（上下 6）。执行中自动展开；完成后收成 48 高的摘要条
 * （✓ +「已完成 N 个步骤」+ 箭头），用户点过头部则不再自动收起。执行详情页上线前，摘要条点开仍在卡内展开步骤。
 */
@Composable
internal fun AgentWorkProcess(
    id: String,
    messages: List<AgentChatMessageUi>,
    onOpenBrowser: () -> Unit,
    currentBrowserMessageId: String?,
    retainedStreamingStates: Map<String, StreamingMarkdownState>,
    modifier: Modifier = Modifier,
    actionsEnabled: Boolean = false,
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    runActive: Boolean = false,
    outcome: WorkOutcome? = null,
) {
    val runUnfinished = outcome == WorkOutcome.Unfinished
    val runStopped = outcome == WorkOutcome.Stopped
    val runControls = LocalRunControls.current
    val paused = runActive && runControls.isPaused
    val stepRunning = messages.any { message ->
        (message is ThinkingMessageUi && message.isStreaming) ||
            (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running)
    }
    // 执行中 = 有步骤在进行，或本轮仍在进行且未暂停（两步之间模型在思考）。
    val running = !paused && (stepRunning || runActive)
    var confirmEndTask by remember(id) { mutableStateOf(false) }
    val tools = messages.filterIsInstance<ToolActivityMessageUi>()
    val toolCount = tools.size
    val failedIndex = unrecoveredFailedStep(tools)
    var expanded by rememberSaveable(id) { mutableStateOf(running) }
    var manuallyExpanded by rememberSaveable(id) { mutableStateOf(false) }

    // 执行中自动展开；完成后停留 600ms 再收起（9.4「执行卡 · 完成 → 摘要条」），用户手动操作过则不动。
    LaunchedEffect(running, paused) {
        if ((running || paused) && !manuallyExpanded) {
            expanded = true
        } else if (!running && !paused && !manuallyExpanded && expanded) {
            kotlinx.coroutines.delay(io.github.fartown.movo.ui.theme.MovoMotion.WORK_CARD_COLLAPSE_DELAY.toLong())
            if (!manuallyExpanded) expanded = false
        }
    }

    val collapsedSummary = !expanded && !running && !paused
    val openRunDetail = LocalOpenRunDetail.current
    val corner by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (collapsedSummary) io.github.fartown.movo.ui.theme.MovoRadius.pillLg else io.github.fartown.movo.ui.theme.MovoRadius.xl,
        animationSpec = io.github.fartown.movo.ui.theme.MovoMotion.standard(),
        label = "workCorner",
    )
    val shape = RoundedCornerShape(corner)
    // Q8：本次在屏幕上看着它从执行中变为完成，且任务用时 ≥ 10 秒时播一次。
    var sawRunning by remember(id) { mutableStateOf(running) }
    if (running) sawRunning = true
    val finishedTools = messages.filterIsInstance<ToolActivityMessageUi>()
    val runMillis = finishedTools.mapNotNull { it.finishedAtMillis }.maxOrNull()?.let { end ->
        finishedTools.mapNotNull { it.startedAtMillis }.minOrNull()?.let { end - it }
    } ?: 0L
    val glint = sawRunning && !running && !paused && failedIndex < 0 && outcome == null && runMillis >= 10_000L
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .onGloballyPositioned { io.github.fartown.movo.ui.components.movo.RunDetailMorph.report(id, it.windowRect()) }
            .completionGlint(glint, corner)
            .clip(shape)
            .background(io.github.fartown.movo.ui.theme.MovoColors.bgSurface)
            .border(io.github.fartown.movo.ui.theme.MovoSize.hairline, io.github.fartown.movo.ui.theme.MovoColors.borderHairline, shape)
            .animateContentSize(io.github.fartown.movo.ui.theme.MovoMotion.standard()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .movoClickableRow {
                    // 摘要条整条可点，打开执行详情页（箭头 › 表示跳转）；没有详情页的宿主退回原地展开。
                    val openDetail = openRunDetail
                    if (collapsedSummary && openDetail != null) {
                        openDetail(id)
                    } else {
                        manuallyExpanded = true
                        expanded = !expanded
                    }
                }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                when {
                    paused -> io.github.fartown.movo.ui.theme.MovoIcon(
                        io.github.fartown.movo.ui.theme.MovoIcons.Pause, null, size = 16.dp, tint = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                    )
                    running -> io.github.fartown.movo.ui.components.movo.MovoOrb(size = 16.dp)
                    // 用户主动停止不是出错：次要色停止方块。
                    runStopped -> io.github.fartown.movo.ui.theme.MovoIcon(
                        io.github.fartown.movo.ui.theme.MovoIcons.Square, null, size = 16.dp, tint = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                    )
                    failedIndex >= 0 || runUnfinished -> io.github.fartown.movo.ui.theme.MovoIcon(
                        io.github.fartown.movo.ui.theme.MovoIcons.X, null, size = 16.dp, tint = io.github.fartown.movo.ui.theme.MovoColors.roseFg,
                    )
                    else -> io.github.fartown.movo.ui.theme.MovoIcon(
                        io.github.fartown.movo.ui.theme.MovoIcons.Check, null, size = 16.dp, tint = io.github.fartown.movo.ui.theme.MovoColors.greenFg,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            io.github.fartown.movo.ui.components.movo.MovoShimmerText(
                text = when {
                    paused && toolCount > 0 -> stringResource(R.string.movo_work_paused_step, toolCount)
                    paused -> stringResource(R.string.movo_work_paused)
                    running && toolCount > 0 -> stringResource(R.string.movo_work_running_step, toolCount)
                    running -> stringResource(R.string.movo_work_analyzing)
                    runStopped -> stringResource(R.string.movo_work_stopped_steps, toolCount)
                    failedIndex >= 0 -> stringResource(R.string.movo_work_failed_step, failedIndex + 1)
                    runUnfinished -> stringResource(R.string.movo_work_unfinished_steps, toolCount)
                    toolCount > 0 -> stringResource(R.string.movo_work_done_steps, toolCount)
                    else -> stringResource(R.string.movo_work_done)
                },
                style = io.github.fartown.movo.ui.theme.MovoTypography.labelMedium,
                color = if (running || paused) io.github.fartown.movo.ui.theme.MovoColors.textPrimary else io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                active = running,
                modifier = Modifier.weight(1f),
            )
            // 计时：执行中「00:18」每秒直接换数字（9.0 规则 5，不滚动不闪）；结束后「用时 18 秒」。
            val firstStart = tools.mapNotNull { it.startedAtMillis }.minOrNull()
            val lastFinish = tools.mapNotNull { it.finishedAtMillis }.maxOrNull()
            if (firstStart != null) {
                val now by androidx.compose.runtime.produceState(System.currentTimeMillis(), running) {
                    while (running) {
                        value = System.currentTimeMillis()
                        kotlinx.coroutines.delay(1_000)
                    }
                    value = System.currentTimeMillis()
                }
                val timerText = if (running) {
                    formatClock(now - firstStart)
                } else {
                    lastFinish?.let { formatElapsed(it - firstStart) }
                }
                if (timerText != null) {
                    Text(
                        text = timerText,
                        style = io.github.fartown.movo.ui.theme.MovoTypography.numericLabel,
                        color = if (running) io.github.fartown.movo.ui.theme.MovoColors.textSecondary else io.github.fartown.movo.ui.theme.MovoColors.textTertiary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
            val rotation by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = io.github.fartown.movo.ui.theme.MovoMotion.fast(),
                label = "workChevron",
            )
            io.github.fartown.movo.ui.theme.MovoIcon(
                if (collapsedSummary && openRunDetail != null) io.github.fartown.movo.ui.theme.MovoIcons.ChevronRight
                else io.github.fartown.movo.ui.theme.MovoIcons.ChevronDown,
                contentDescription = stringResource(if (expanded) R.string.movo_collapse else R.string.movo_expand),
                size = 16.dp,
                tint = io.github.fartown.movo.ui.theme.MovoColors.textTertiary,
                modifier = Modifier.graphicsLayer {
                    rotationZ = if (collapsedSummary && openRunDetail != null) 0f else rotation
                },
            )
        }

        // 展开：高度 `standard`，内容在高度过渡开始 40ms 后淡入 `fast`（规范 9.3「展开 / 收起」）。
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(
                tween(
                    io.github.fartown.movo.ui.theme.MovoMotion.FAST,
                    delayMillis = io.github.fartown.movo.ui.theme.MovoMotion.STAGGER,
                    easing = io.github.fartown.movo.ui.theme.MovoMotion.EasingStandard,
                ),
            ) + expandVertically(io.github.fartown.movo.ui.theme.MovoMotion.standard()),
            exit = fadeOut(io.github.fartown.movo.ui.theme.MovoMotion.fastExit()) +
                shrinkVertically(io.github.fartown.movo.ui.theme.MovoMotion.standard()),
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(io.github.fartown.movo.ui.theme.MovoSize.hairline)
                        .background(io.github.fartown.movo.ui.theme.MovoColors.borderHairline),
                )
                WorkSteps(
                    messages = messages,
                    running = running,
                    onOpenBrowser = onOpenBrowser,
                    currentBrowserMessageId = currentBrowserMessageId,
                    retainedStreamingStates = retainedStreamingStates,
                    actionsEnabled = actionsEnabled,
                    onEditMessage = onEditMessage,
                    onDeleteMessage = onDeleteMessage,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                // 底部栏（执行中）：说明 +「查看」（打开执行详情页）；不放停止（停止在输入框主按钮 ■）。内边距 12。
                if ((running || paused) && openRunDetail != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(io.github.fartown.movo.ui.theme.MovoSize.hairline)
                            .background(io.github.fartown.movo.ui.theme.MovoColors.borderHairline),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(start = 16.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val app = messages.operatingAppName()
                        Text(
                            text = when {
                                paused -> stringResource(R.string.movo_work_paused)
                                app != null -> stringResource(R.string.movo_work_operating_app, app)
                                else -> stringResource(R.string.movo_work_operating)
                            },
                            style = io.github.fartown.movo.ui.theme.MovoTypography.labelRegular,
                            color = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (paused) {
                            // 已暂停：「结束任务」（二次确认，说明已完成的步骤会保留）+「查看」。
                            io.github.fartown.movo.ui.components.movo.MovoPillButton(
                                label = stringResource(R.string.movo_work_end_task),
                                onClick = { confirmEndTask = true },
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        io.github.fartown.movo.ui.components.movo.MovoPillButton(
                            label = stringResource(R.string.movo_work_view),
                            onClick = { openRunDetail(id) },
                        )
                    }
                }
            }
        }
    }
    io.github.fartown.movo.ui.components.movo.MovoConfirmDialog(
        show = confirmEndTask,
        title = stringResource(R.string.movo_end_task_title),
        message = stringResource(R.string.movo_end_task_message),
        confirmText = stringResource(R.string.movo_work_end_task),
        destructive = true,
        onConfirm = {
            confirmEndTask = false
            runControls.onEndTask()
        },
        onDismissRequest = { confirmEndTask = false },
    )
}

/**
 * 步骤时间线（`Work/Step` 列表 + 连接线）：执行卡与执行详情页共用同一组件（规范 8.8「行即 Work/Step」）。
 */
@Composable
internal fun WorkSteps(
    messages: List<AgentChatMessageUi>,
    running: Boolean,
    onOpenBrowser: () -> Unit,
    currentBrowserMessageId: String?,
    retainedStreamingStates: Map<String, StreamingMarkdownState>,
    modifier: Modifier = Modifier,
    actionsEnabled: Boolean = false,
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
) {
    // 规范 9.4「执行卡 · 新步骤」：执行中新出现的行淡入上移 6（`fast`），连接线从上一个图标向下生长（`fast`）。
    // 已经出现过的步骤（历史记录、滚出屏幕再回来）不重播。
    val seenSteps = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.runtime.saveable.listSaver<MutableSet<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableSet() },
        ),
    ) { mutableSetOf() }
    val reduced = io.github.fartown.movo.ui.theme.LocalReducedMotion.current
    Column(modifier = modifier) {
        messages.forEachIndexed { index, message ->
            androidx.compose.runtime.key(message.id) {
            val playEntrance = remember { running && message.id !in seenSteps }
            SideEffect { seenSteps += message.id }
            val hasNext = index < messages.lastIndex
            val connector = remember { androidx.compose.animation.core.Animatable(if (hasNext || !playEntrance && !running) 1f else 0f) }
            LaunchedEffect(hasNext) {
                when {
                    !hasNext -> connector.snapTo(0f)
                    reduced -> connector.snapTo(1f)
                    else -> connector.animateTo(1f, io.github.fartown.movo.ui.theme.MovoMotion.fast())
                }
            }
            io.github.fartown.movo.ui.components.movo.MovoEntrance(
                play = playEntrance,
                shift = 6.dp,
                durationMillis = io.github.fartown.movo.ui.theme.MovoMotion.FAST,
            ) {
            Box(
                modifier = if (hasNext) {
                    Modifier.workStepConnector { connector.value }
                } else {
                    Modifier
                },
            ) {
                if (message is UserMessageUi) {
                    // 补充在其后出现新的思考 / 工具步骤（下一步已开始）或本轮结束时即已采纳。
                    val applied = !running || messages.drop(index + 1).any {
                        it is ThinkingMessageUi || it is ToolActivityMessageUi
                    }
                    SupplementStep(
                        message = message,
                        applied = applied,
                        actionsEnabled = actionsEnabled,
                        onEdit = { onEditMessage(message.id) },
                        onDelete = { onDeleteMessage(message.id) },
                    )
                } else {
                    ChatMessageItem(
                        message = message,
                        onSuggestionClick = {},
                        onRunTraceClick = {},
                        onOpenBrowser = onOpenBrowser,
                        showBrowserShortcut = message.id == currentBrowserMessageId,
                        retainedStreamingState = retainedStreamingStates[message.id],
                        compact = true,
                    )
                }
            }
            }
            }
        }
    }
}

/** 整行可点的按压反馈（列表行：只叠加、不缩放，规范 9.3.1）。 */
private fun Modifier.movoClickableRow(onClick: () -> Unit): Modifier =
    movoClickable(io.github.fartown.movo.ui.components.movo.PressKind.Row, onClick = onClick)

/** 执行中计时：mm:ss（等宽数字）。 */
private fun formatClock(elapsedMillis: Long): String {
    val seconds = (elapsedMillis / 1000).coerceAtLeast(0)
    return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

/** 结束后的总用时：「用时 18 秒」「用时 2 分 5 秒」。 */
@Composable
private fun formatElapsed(elapsedMillis: Long): String {
    val seconds = ((elapsedMillis + 500) / 1000).coerceAtLeast(1).toInt()
    return if (seconds < 60) {
        stringResource(R.string.movo_work_elapsed_seconds, seconds)
    } else {
        stringResource(R.string.movo_work_elapsed_minutes, seconds / 60, seconds % 60)
    }
}

/** 每步用时（Figma「0.8s」「2.1s」）：一位小数的秒，超过一分钟写 m:ss。 */
private fun formatStepDuration(elapsedMillis: Long): String {
    val ms = elapsedMillis.coerceAtLeast(0)
    return if (ms < 60_000) {
        String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0)
    } else {
        String.format(java.util.Locale.ROOT, "%d:%02d", ms / 60_000, (ms / 1000) % 60)
    }
}

/**
 * 步骤之间的连接线：1 宽 border/strong，从本步图标下方 4 到下一步图标上方 4（图标中心在卡内 24）。
 * 步骤行内边距上 10，图标 16 在 18 高的标题行里垂直居中 → 图标上沿在行内 11、下沿 27。
 */
private fun Modifier.workStepConnector(progress: () -> Float = { 1f }): Modifier = this.drawBehind {
    val x = 24.dp.toPx()
    val top = (27 + 4).dp.toPx()
    val fullBottom = size.height + (11 - 4).dp.toPx()
    val bottom = top + (fullBottom - top) * progress().coerceIn(0f, 1f)
    if (bottom > top) {
        drawLine(
            color = io.github.fartown.movo.ui.theme.MovoColors.borderStrong,
            start = Offset(x, top),
            end = Offset(x, bottom),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

// ── 用户消息：轻盈美观气泡 ──────────────────────────────────────────────

@Composable
private fun UserMessageBubble(
    message: UserMessageUi,
    actionsEnabled: Boolean,
    isEditing: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val tooltipState = rememberTooltipState(isPersistent = true)
    LaunchedEffect(actionsEnabled) {
        if (!actionsEnabled) tooltipState.dismiss()
    }
    val visiblePrompt = remember(message.content) {
        AgentFileReferencePromptCodec.parse(message.content)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                positioning = TooltipAnchorPosition.Below,
            ),
            tooltip = {
                RichTooltip(insideMargin = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        MessageTooltipAction(
                            icon = Icons.Rounded.ContentCopy,
                            label = stringResource(R.string.ui_copy_4edd1d),
                            onClick = {
                                @Suppress("DEPRECATION")
                                clipboardManager.setText(AnnotatedString(message.content))
                                tooltipState.dismiss()
                            },
                        )
                        MessageTooltipAction(
                            icon = Icons.Rounded.Edit,
                            label = stringResource(R.string.ui_edit_a7f814),
                            onClick = {
                                tooltipState.dismiss()
                                onEdit()
                            },
                        )
                        MessageTooltipAction(
                            icon = Icons.Rounded.Delete,
                            label = stringResource(R.string.ui_delete_3755f5),
                            onClick = {
                                tooltipState.dismiss()
                                onDelete()
                            },
                        )
                    }
                }
            },
            state = tooltipState,
            focusable = true,
            enableUserInput = actionsEnabled,
        ) {
            // `Message/User`（规范 8.1）：右对齐到 392，最大宽 296；bg/surface + 0.5 描边；圆角 20、右下 8；内边距 16 / 11。
            val bubbleShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 8.dp, bottomStart = 20.dp)
            // Q1：刚发出的这条由飞行层从输入框飞到位，落地前自己先隐藏。
            val chatFlight = LocalChatFlight.current
            Column(
                modifier = Modifier
                    .widthIn(max = 296.dp)
                    .onGloballyPositioned { chatFlight?.reportBubble(message.id, visiblePrompt.request, it.windowRect()) }
                    .graphicsLayer {
                        alpha = if (chatFlight?.hidesBubble(message.id, visiblePrompt.request) == true) 0f else 1f
                    }
                    .clip(bubbleShape)
                    .background(io.github.fartown.movo.ui.theme.MovoColors.bgSurface)
                    .border(
                        width = if (isEditing) 1.dp else io.github.fartown.movo.ui.theme.MovoSize.hairline,
                        color = if (isEditing) io.github.fartown.movo.ui.theme.MovoColors.indigoFg else io.github.fartown.movo.ui.theme.MovoColors.borderHairline,
                        shape = bubbleShape,
                    )
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                if (message.images.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        message.images.forEach { dataUrl ->
                            val bitmap = rememberDataUrlBitmap(dataUrl)
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                    }
                }
                if (visiblePrompt.references.isNotEmpty()) {
                    SentFileReferenceFlow(
                        references = visiblePrompt.references,
                        modifier = Modifier.padding(
                            bottom = if (visiblePrompt.request.isNotBlank()) 8.dp else 0.dp
                        ),
                    )
                }
                if (visiblePrompt.request.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = visiblePrompt.request,
                            style = io.github.fartown.movo.ui.theme.MovoTypography.bodyReading,
                            color = io.github.fartown.movo.ui.theme.MovoColors.textPrimary,
                        )
                    }
                }
                if (message.isEdited) {
                    Text(
                        text = stringResource(R.string.ui_edited_c36776),
                        style = io.github.fartown.movo.ui.theme.MovoTypography.labelRegular,
                        color = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageTooltipAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(16.dp),
            tint = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

// ── 上下文压缩：时间线中的轻量胶囊标记 ─────────────────────────────────

/**
 * 压缩不是一轮对话结果，而是上下文维护事件；用居中胶囊标记与助手正文区分，
 * 进行中通过图标脉冲反馈，结束后保留压缩前后的 token 信息。
 */
@Composable
private fun ContextCompactionMarker(
    message: SystemNoticeMessageUi,
    modifier: Modifier = Modifier,
) {
    val pulseAlpha = rememberActivePulse(active = message.running, label = "compaction_pulse")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(MiuixTheme.colorScheme.surface)
                .border(
                    0.5.dp,
                    MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                    RoundedCornerShape(percent = 50),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Compress,
                contentDescription = null,
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer(alpha = if (message.running) pulseAlpha else 1f),
                tint = if (message.running) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = message.detail?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.context_compaction),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Agent 结果 ───────────────────────────────────────────────────────

@Composable
private fun AgentMessageBlock(
    message: AgentMessageUi,
    retainedStreamingState: StreamingMarkdownState?,
    showCopyAction: Boolean,
    showMessageActions: Boolean,
    messageActionsEnabled: Boolean,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit = {},
    onSelectCandidate: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember(message.id) { mutableStateOf(false) }
    val keepStreamingMarkdown = remember(message.id) { message.isStreaming }
    var streamingRevealComplete by remember(message.id) {
        mutableStateOf(!keepStreamingMarkdown)
    }
    // 渲染会话由列表层按 message.id 持有，item 滚出视口被销毁后滑回时复用同一
    // 会话；没有外部持有者时（如嵌套条目）退回组合内 remember，行为与之前一致。
    val streamingState = if (keepStreamingMarkdown) {
        retainedStreamingState ?: remember(message.id) { StreamingMarkdownState() }
    } else {
        null
    }
    val completedMarkdownState = (streamingState ?: retainedStreamingState)
        ?.snapshot?.completedStateFor(message.content)
    val revealComplete = streamingRevealComplete && !message.isStreaming &&
        (streamingState == null || completedMarkdownState != null)
    LaunchedEffect(retainedStreamingState, revealComplete, message.content) {
        retainedStreamingState?.revealedContent = message.content.takeIf { revealComplete }
    }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 7.dp),
    ) {
        when {
            message.content.isBlank() && message.isStreaming -> {
                AITypingIndicator(
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            streamingState != null && !revealComplete -> {
                StreamingMarkdown(
                    state = streamingState,
                    content = message.content,
                    isStreaming = message.isStreaming,
                    onRevealCompleteChange = { streamingRevealComplete = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            message.renderMarkdown -> {
                SelectionContainer {
                    StableMarkdown(
                        content = message.content,
                        parsedState = completedMarkdownState,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            message.content.isNotBlank() -> {
                SelectionContainer {
                    Text(
                        text = message.content,
                        style = io.github.fartown.movo.ui.theme.MovoTypography.bodyReading,
                        color = io.github.fartown.movo.ui.theme.MovoColors.textPrimary,
                    )
                }
            }
        }

        if (
            showCopyAction &&
            !message.isStreaming &&
            message.content.isNotBlank() &&
            revealComplete
        ) {
            // 消息操作（规范 8.1）：复制、重新生成、更多；按钮 32、图标 16 次要色；首个图标字形对齐 20。
            // 删除与「编辑角色回复」收进「更多」菜单；角色候选切换保留在右侧。
            // 回答刚输出完时淡入并上移 8（规范 9.4「回答完成」，推荐追问随后 40ms）；历史消息直接显示。
            io.github.fartown.movo.ui.components.movo.MovoEntrance(play = keepStreamingMarkdown, step = 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .offset(x = (-8).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MessageActionButton(
                    icon = if (copied) io.github.fartown.movo.ui.theme.MovoIcons.Check else io.github.fartown.movo.ui.theme.MovoIcons.Copy,
                    contentDescription = stringResource(if (copied) R.string.copy_copied else R.string.copy_answer),
                    tint = if (copied) io.github.fartown.movo.ui.theme.MovoColors.greenFg else io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                    onClick = {
                        @Suppress("DEPRECATION")
                        clipboardManager.setText(AnnotatedString(message.content))
                        copied = true
                    },
                )
                if (showMessageActions) {
                    MessageActionButton(
                        icon = io.github.fartown.movo.ui.theme.MovoIcons.RotateCcw,
                        contentDescription = stringResource(R.string.ui_regenerate_reply_84a7d9),
                        enabled = messageActionsEnabled,
                        onClick = onRegenerate,
                    )
                    var showMore by remember(message.id) { mutableStateOf(false) }
                    Box {
                        MessageActionButton(
                            icon = io.github.fartown.movo.ui.theme.MovoIcons.Ellipsis,
                            contentDescription = stringResource(R.string.action_more),
                            enabled = messageActionsEnabled,
                            onClick = { showMore = true },
                        )
                        io.github.fartown.movo.ui.components.movo.MovoPopoverMenu(
                            show = showMore && messageActionsEnabled,
                            onDismiss = { showMore = false },
                            items = buildList {
                                if (message.characterEditable) {
                                    add(
                                        io.github.fartown.movo.ui.components.movo.MovoMenuItem(
                                            io.github.fartown.movo.ui.theme.MovoIcons.PenLine, "编辑角色回复", onClick = onEdit,
                                        ),
                                    )
                                }
                                add(
                                    io.github.fartown.movo.ui.components.movo.MovoMenuItem(
                                        io.github.fartown.movo.ui.theme.MovoIcons.Trash2,
                                        stringResource(R.string.ui_delete_3755f5),
                                        destructive = true,
                                        onClick = onDelete,
                                    ),
                                )
                            },
                        )
                    }
                    if (message.characterEditable && message.candidateCount > 1) {
                        Spacer(Modifier.weight(1f))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 3.dp, vertical = 2.dp),
                        ) {
                            IconButton(
                                onClick = { onSelectCandidate(message.selectedCandidate - 1) },
                                enabled = messageActionsEnabled && message.selectedCandidate > 0,
                                minWidth = 28.dp, minHeight = 28.dp,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ChevronLeft,
                                    contentDescription = "上一条候选回复",
                                    modifier = Modifier.size(16.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Text(
                                text = "${message.selectedCandidate + 1}/${message.candidateCount}",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(min = 30.dp),
                            )
                            IconButton(
                                onClick = { onSelectCandidate(message.selectedCandidate + 1) },
                                enabled = messageActionsEnabled && message.selectedCandidate < message.candidateCount - 1,
                                minWidth = 28.dp, minHeight = 28.dp,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = "下一条候选回复",
                                    modifier = Modifier.size(16.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

/** 消息操作按钮：32 热区（视觉），图标 16；复制 ↔ ✓ 交叉淡化并缩放 0.72 ↔ 1（9.3.1「图标状态切换」）。 */
@Composable
private fun MessageActionButton(
    icon: io.github.fartown.movo.ui.theme.MovoIconData,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .movoClickable(io.github.fartown.movo.ui.components.movo.PressKind.Icon, enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.AnimatedContent(
            targetState = icon,
            transitionSpec = {
                (fadeIn(io.github.fartown.movo.ui.theme.MovoMotion.fast()) +
                    scaleIn(io.github.fartown.movo.ui.theme.MovoMotion.fast(), initialScale = 0.72f))
                    .togetherWith(
                        fadeOut(io.github.fartown.movo.ui.theme.MovoMotion.fastExit()) +
                            scaleOut(io.github.fartown.movo.ui.theme.MovoMotion.fastExit(), targetScale = 0.72f),
                    )
            },
            label = "messageAction",
        ) { current ->
            io.github.fartown.movo.ui.theme.MovoIcon(current, null, size = 16.dp, tint = tint)
        }
    }
}

@Composable
private fun StableMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    tone: ChatMarkdownTone = ChatMarkdownTone.Answer,
    markdownState: MarkdownState? = null,
    parsedState: State.Success? = null,
) {
    // 流式终态已有完整 AST，直接复用，避免新解析器的 Loading 原文先撑高页面再缩回。
    val state = parsedState ?: (markdownState ?: rememberMarkdownState(
        content = content,
        retainState = true,
    )).state.collectAsState().value
    val components = remember { chatMarkdownComponents() }
    Markdown(
        state = state,
        annotator = rememberCitationMarkdownAnnotator(),
        colors = chatMarkdownColors(tone),
        typography = chatMarkdownTypography(tone),
        padding = chatMarkdownPadding(),
        dimens = chatMarkdownDimens(),
        components = components,
        modifier = modifier,
        loading = {
            // 保留与最终正文接近的高度，避免历史消息异步解析完成后越界绘制。
            Text(
                text = content,
                style = chatMarkdownBodyStyle(tone),
                color = chatMarkdownTextColor(tone),
                modifier = it,
            )
        },
        error = {
            Text(
                text = content,
                style = chatMarkdownBodyStyle(tone),
                color = chatMarkdownTextColor(tone),
                modifier = it,
            )
        },
        success = { state, successComponents, successModifier ->
            ChatMarkdownDocument(
                root = state.node,
                content = state.content,
                components = successComponents,
                modifier = successModifier,
            )
        },
    )
}

@Composable
private fun StreamingMarkdown(
    state: StreamingMarkdownState,
    content: String,
    isStreaming: Boolean,
    onRevealCompleteChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    tone: ChatMarkdownTone = ChatMarkdownTone.Answer,
) {
    val revealCoordinator = state.revealCoordinator
    // 思考紧跟已收到的增量，不按句缓冲。
    // 规范 9.8：减少动画时回答直接出现，不做按句模糊显现（显现时钟本身不受系统动画缩放影响，需要显式判断）。
    val animateReveal = tone == ChatMarkdownTone.Answer && !io.github.fartown.movo.ui.theme.LocalReducedMotion.current
    val components = remember(revealCoordinator, isStreaming, animateReveal) {
        chatMarkdownComponents(
            revealCoordinator = revealCoordinator.takeIf { animateReveal },
            suppressEmptyListMarkers = isStreaming && animateReveal,
        )
    }
    val parseTargets = state.parseTargets
    val currentRevealCompleteCallback by rememberUpdatedState(onRevealCompleteChange)
    val snapshot = state.snapshot
    val currentContent by rememberUpdatedState(content)
    val currentIsStreaming by rememberUpdatedState(isStreaming)
    val restoreGeneration = state.restoreState.generation

    LifecycleResumeEffect(state) {
        revealCoordinator.pauseAnimationsAndCatchUp()
        state.restoreState.begin(currentContent)
        onPauseOrDispose {
            state.restoreState.pause()
            revealCoordinator.pauseAnimationsAndCatchUp()
        }
    }

    LaunchedEffect(revealCoordinator, animateReveal) {
        if (animateReveal) revealCoordinator.runFrameClock()
    }

    LaunchedEffect(content, isStreaming) {
        // 回答结束时剩余的缓冲不再等句末或 300ms（规范 9.4「回答流式输出」）。
        revealCoordinator.setStreaming(isStreaming)
        parseTargets.trySend(
            StreamingMarkdownTarget(
                content = content,
                isStreaming = isStreaming,
            )
        )
        if (isStreaming) currentRevealCompleteCallback(false)
    }

    LaunchedEffect(state) {
        state.parseUpdates()
    }

    LaunchedEffect(content, isStreaming, snapshot?.originalSource, snapshot?.isComplete, revealCoordinator) {
        val currentSnapshot = snapshot
        if (!isStreamingMarkdownTargetComplete(
                content = content,
                isStreaming = isStreaming,
                snapshotContent = currentSnapshot?.originalSource,
                snapshotComplete = currentSnapshot?.isComplete == true,
            )
        ) {
            currentRevealCompleteCallback(false)
            return@LaunchedEffect
        }

        // 等这一版 AST 完成组合与排版后，再等待尾部字符的透明度动画收口。
        withFrameNanos { }
        if (!revealCoordinator.drained.value) {
            revealCoordinator.drained.filter { it }.first()
        }
        if (isStreamingMarkdownTargetComplete(
                content = currentContent,
                isStreaming = currentIsStreaming,
                snapshotContent = currentSnapshot?.originalSource,
                snapshotComplete = currentSnapshot?.isComplete == true,
            )
        ) {
            currentRevealCompleteCallback(true)
        }
    }

    snapshot?.let { parsed ->
        Markdown(
            annotator = rememberCitationMarkdownAnnotator(),
            state = parsed.state,
            colors = chatMarkdownColors(tone),
            typography = chatMarkdownTypography(tone),
            padding = chatMarkdownPadding(),
            dimens = chatMarkdownDimens(),
            components = components,
            animations = markdownAnimations(animateTextSize = { this }),
            modifier = modifier.onGloballyPositioned {
                // 恢复基线对应的 AST 真正排版后才开放增量动画，解析耗时不受帧数限制。
                if (state.restoreState.completeLayout(
                        generation = restoreGeneration,
                        renderedContent = parsed.originalSource,
                        currentContent = currentContent,
                    )
                ) {
                    revealCoordinator.resumeAnimationsAfterCatchUp()
                }
            },
            success = { state, successComponents, successModifier ->
                StreamingGfmSuccess(
                    state = state,
                    components = successComponents,
                    revealCoordinator = revealCoordinator,
                    modifier = successModifier,
                )
            },
        )
    }
}

/**
 * 顶层节点以源码位置和语法类型作为稳定身份。完整重解析只替换真正发生类型变化的
 * 当前块，前面已经稳定的段落、表格和代码块不会因新 chunk 到达而重新挂载。
 */
@Composable
private fun StreamingGfmSuccess(
    state: State.Success,
    components: MarkdownComponents,
    revealCoordinator: SmoothTextRevealCoordinator,
    modifier: Modifier = Modifier,
) {
    val activeRevealBlocks = remember(state.node) {
        state.revealBlockKeys()
    }
    SideEffect {
        revealCoordinator.retainBlocks(activeRevealBlocks)
    }

    ChatMarkdownDocument(
        root = state.node,
        content = state.content,
        components = components,
        modifier = modifier,
    )
}

/**
 * 空行只负责切分 Markdown 块，不直接占据布局高度；可见块之间按语义分配留白，
 * 避免统一 block padding 让标题、正文、列表和表格失去层级。
 */
@Composable
private fun ChatMarkdownDocument(
    root: ASTNode,
    content: String,
    components: MarkdownComponents,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(root) { topLevelMarkdownBlocks(root) }
    val density = LocalDensity.current
    Column(modifier) {
        blocks.forEachIndexed { index, node ->
            val previousType = blocks.getOrNull(index - 1)?.type
            val gap = with(density) {
                markdownBlockSpacing(previousType, node.type).toDp()
            }
            if (gap > 0.dp) Spacer(Modifier.height(gap))
            key(node.startOffset, node.type.name) {
                MarkdownElement(
                    node = node,
                    components = components,
                    content = content,
                    includeSpacer = false,
                )
            }
        }
    }
}

internal fun topLevelMarkdownBlocks(root: ASTNode): List<ASTNode> =
    root.children.filterNot { node -> node.type == MarkdownTokenTypes.EOL }

internal fun markdownBlockSpacing(previous: IElementType?, current: IElementType): TextUnit {
    if (previous == null) return 0.sp
    if (previous.isMarkdownHeading() && current.isMarkdownHeading()) return 12.sp
    if (current.isMarkdownHeading()) {
        return if (current == MarkdownElementTypes.ATX_1 ||
            current == MarkdownElementTypes.SETEXT_1 ||
            current == MarkdownElementTypes.ATX_2 ||
            current == MarkdownElementTypes.SETEXT_2
        ) {
            24.sp
        } else {
            20.sp
        }
    }
    if (previous.isMarkdownHeading()) return 10.sp
    if (previous.isMarkdownParagraph() && current.isMarkdownParagraph()) return 16.sp
    if (previous.isMarkdownStructuredBlock() || current.isMarkdownStructuredBlock()) return 16.sp
    return 14.sp
}

private fun IElementType.isMarkdownHeading(): Boolean = when (this) {
    MarkdownElementTypes.ATX_1,
    MarkdownElementTypes.ATX_2,
    MarkdownElementTypes.ATX_3,
    MarkdownElementTypes.ATX_4,
    MarkdownElementTypes.ATX_5,
    MarkdownElementTypes.ATX_6,
    MarkdownElementTypes.SETEXT_1,
    MarkdownElementTypes.SETEXT_2,
    -> true

    else -> false
}

private fun IElementType.isMarkdownParagraph(): Boolean =
    this == MarkdownElementTypes.PARAGRAPH || this == MarkdownTokenTypes.TEXT

private fun IElementType.isMarkdownStructuredBlock(): Boolean = when (this) {
    MarkdownElementTypes.ORDERED_LIST,
    MarkdownElementTypes.UNORDERED_LIST,
    MarkdownElementTypes.BLOCK_QUOTE,
    MarkdownElementTypes.CODE_BLOCK,
    MarkdownElementTypes.CODE_FENCE,
    MarkdownElementTypes.IMAGE,
    MarkdownTokenTypes.HORIZONTAL_RULE,
    TABLE,
    -> true

    else -> false
}

internal fun streamingMarkdownBatchSize(backlogChars: Int): Int = when {
    backlogChars >= 384 -> 96
    backlogChars >= 160 -> 64
    backlogChars >= 64 -> 40
    else -> 24
}

internal fun streamingMarkdownBatchEnd(
    content: String,
    start: Int,
    maxGraphemes: Int,
): Int {
    return AppendOnlyGraphemeIndex().apply { update(content) }.endAfter(start, maxGraphemes)
}

// ── Markdown 样式：克制的聊天排版，标题只作强调不作页面标题 ─────────────

private enum class ChatMarkdownTone {
    Answer,
    Thinking,
}

@Composable
private fun chatMarkdownTypography(tone: ChatMarkdownTone) = markdownTypography(
    h1 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 21.sp else 17.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 29.sp else 25.sp,
        fontWeight = FontWeight.Medium,
    ),
    h2 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 19.sp else 16.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 27.sp else 24.sp,
        fontWeight = FontWeight.Medium,
    ),
    h3 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 18.sp else 15.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 26.sp else 23.sp,
        fontWeight = FontWeight.Medium,
    ),
    h4 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 17.sp else 14.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 25.sp else 22.sp,
        fontWeight = FontWeight.Medium,
    ),
    h5 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 16.sp else 14.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 24.sp else 22.sp,
        fontWeight = FontWeight.Medium,
    ),
    h6 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 15.sp else 14.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 23.sp else 22.sp,
        fontWeight = FontWeight.Medium,
    ),
    text = chatMarkdownBodyStyle(tone),
    paragraph = chatMarkdownBodyStyle(tone),
    ordered = chatMarkdownBodyStyle(tone),
    bullet = chatMarkdownBodyStyle(tone),
    list = chatMarkdownBodyStyle(tone),
    quote = MiuixTheme.textStyles.body2.copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 15.sp else 14.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 24.sp else 22.sp,
        color = chatMarkdownTextColor(ChatMarkdownTone.Thinking),
    ),
    code = TextStyle(
        fontSize = 13.sp,
        lineHeight = 20.sp,
        fontFamily = FontFamily.Monospace,
        color = chatMarkdownTextColor(tone),
    ),
    inlineCode = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 14.sp else 13.sp,
        fontFamily = FontFamily.Monospace,
    ),
    table = MiuixTheme.textStyles.body2.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = chatMarkdownTextColor(tone),
    ),
    textLink = TextLinkStyles(
        style = SpanStyle(
            color = io.github.fartown.movo.ui.theme.MovoColors.indigoFg,
            fontWeight = FontWeight.Medium,
            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
        ),
    ),
)

@Composable
private fun chatMarkdownBodyStyle(tone: ChatMarkdownTone) =
    if (tone == ChatMarkdownTone.Answer) {
        MiuixTheme.textStyles.body1.copy(
            fontSize = 16.sp,
            lineHeight = 26.sp,
            color = chatMarkdownTextColor(tone),
        )
    } else {
        // 思考内容：与步骤说明同一字号（Label/Regular 13），行高放宽便于阅读长段落。
        MiuixTheme.textStyles.body2.copy(
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = chatMarkdownTextColor(tone),
        )
    }

@Composable
private fun chatMarkdownTextColor(tone: ChatMarkdownTone): Color =
    if (tone == ChatMarkdownTone.Answer) {
        MiuixTheme.colorScheme.onSurface
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

@Composable
private fun chatMarkdownColors(tone: ChatMarkdownTone) = markdownColor(
    text = chatMarkdownTextColor(tone),
    // 代码块与表格的底色、描边由自定义组件绘制，这里只保留行内代码底色与分隔线。
    codeBackground = MiuixTheme.colorScheme.surface,
    inlineCodeBackground = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
    dividerColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
    tableBackground = Color.Transparent,
)

@Composable
private fun chatMarkdownDimens() = markdownDimens(
    dividerThickness = 0.5.dp,
    codeBackgroundCornerSize = 10.dp,
    blockQuoteThickness = 3.dp,
)

@Composable
private fun chatMarkdownPadding() = markdownPadding(
    // 顶层块由 ChatMarkdownDocument 按语义分配留白，库的统一前置间距保持关闭。
    block = 0.dp,
    list = 3.dp,
    listItemTop = 3.dp,
    listItemBottom = 3.dp,
    listIndent = 14.dp,
    codeBlock = PaddingValues(horizontal = 13.dp, vertical = 11.dp),
    blockQuote = PaddingValues(horizontal = 12.dp),
    blockQuoteText = PaddingValues(vertical = 3.dp),
    blockQuoteBar = PaddingValues.Absolute(left = 2.dp, top = 3.dp, right = 0.dp, bottom = 3.dp),
)

private fun chatMarkdownComponents(
    revealCoordinator: SmoothTextRevealCoordinator? = null,
    suppressEmptyListMarkers: Boolean = false,
) = markdownComponents(
    text = { model ->
        if (revealCoordinator == null) {
            MarkdownText(
                content = model.node.getUnescapedTextInNode(model.content),
                node = model.node,
                style = model.typography.text,
            )
        } else {
            ChatRevealRawText(model, revealCoordinator)
        }
    },
    paragraph = { model ->
        if (revealCoordinator == null || model.node.containsMarkdownImage()) {
            MarkdownParagraph(
                content = model.content,
                node = model.node,
                style = model.typography.paragraph,
            )
        } else {
            ChatRevealMarkdownText(
                model = model,
                style = model.typography.paragraph,
                revealCoordinator = revealCoordinator,
            )
        }
    },
    orderedList = { model ->
        ChatMarkdownList(
            model = model,
            ordered = true,
            revealCoordinator = revealCoordinator,
            suppressEmptyMarker = suppressEmptyListMarkers,
        )
    },
    unorderedList = { model ->
        ChatMarkdownList(
            model = model,
            ordered = false,
            revealCoordinator = revealCoordinator,
            suppressEmptyMarker = suppressEmptyListMarkers,
        )
    },
    heading1 = { ChatHeadingBlock(it, it.typography.h1, revealCoordinator = revealCoordinator) },
    heading2 = { ChatHeadingBlock(it, it.typography.h2, revealCoordinator = revealCoordinator) },
    heading3 = { ChatHeadingBlock(it, it.typography.h3, revealCoordinator = revealCoordinator) },
    heading4 = { ChatHeadingBlock(it, it.typography.h4, revealCoordinator = revealCoordinator) },
    heading5 = { ChatHeadingBlock(it, it.typography.h5, revealCoordinator = revealCoordinator) },
    heading6 = { ChatHeadingBlock(it, it.typography.h6, revealCoordinator = revealCoordinator) },
    setextHeading1 = {
        ChatHeadingBlock(
            it,
            it.typography.h1,
            setext = true,
            revealCoordinator = revealCoordinator,
        )
    },
    setextHeading2 = {
        ChatHeadingBlock(
            it,
            it.typography.h2,
            setext = true,
            revealCoordinator = revealCoordinator,
        )
    },
    codeFence = { model ->
        val revealState = if (revealCoordinator != null) {
            rememberSmoothTextRevealState(
                key = RevealBlockKey(model.node.startOffset),
                coordinator = revealCoordinator,
            )
        } else {
            null
        }
        MarkdownCodeFence(model.content, model.node, style = model.typography.code) { code, language, style ->
            ChatCodeBlock(
                code = code,
                language = language,
                style = style,
                revealState = revealState,
            )
        }
    },
    codeBlock = { model ->
        val revealState = if (revealCoordinator != null) {
            rememberSmoothTextRevealState(
                key = RevealBlockKey(model.node.startOffset),
                coordinator = revealCoordinator,
            )
        } else {
            null
        }
        MarkdownCodeBlock(model.content, model.node, style = model.typography.code) { code, language, style ->
            ChatCodeBlock(
                code = code,
                language = language,
                style = style,
                revealState = revealState,
            )
        }
    },
    table = { model ->
        ChatMarkdownTable(
            content = model.content,
            node = model.node,
            style = model.typography.table,
            revealCoordinator = revealCoordinator,
        )
    },
    blockQuote = { model ->
        ChatBlockQuote(model)
    },
)

/**
 * 流式列表不能直接使用库的默认实现：默认实现会立即绘制 marker，而正文还在显现动画中。
 * 这里把每一项作为稳定的组合单元，并让 marker 与该项首个正文块共享开始时机。
 */
@Composable
private fun ChatMarkdownList(
    model: MarkdownComponentModel,
    ordered: Boolean,
    revealCoordinator: SmoothTextRevealCoordinator?,
    suppressEmptyMarker: Boolean,
    depth: Int = model.listDepth,
) {
    val components = LocalMarkdownComponents.current
    val padding = LocalMarkdownPadding.current
    val items = remember(model.node) {
        model.node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
    }
    if (items.isEmpty()) return

    val startedRevealKeys = rememberStartedRevealKeys(revealCoordinator)
    val initialListNumber = items.first()
        .getUnescapedTextInNode(model.content)
        .takeWhile(Char::isDigit)
        .toIntOrNull()
        ?: 1

    Column(
        modifier = Modifier.padding(
            start = padding.listIndent * depth,
            top = padding.list,
            bottom = padding.list,
        ),
    ) {
        items.forEachIndexed { index, item ->
            key(item.startOffset, item.type.name) {
                val firstRevealKey = remember(item) { item.firstRevealBlockKey() }
                val checkboxNode = remember(item) {
                    item.children.firstOrNull { child -> child.type == CHECK_BOX }
                }
                val markerVisible = streamingListMarkerVisible(
                    coordinatorActive = suppressEmptyMarker,
                    firstRevealKey = firstRevealKey,
                    startedRevealKeys = startedRevealKeys,
                    containsImage = item.containsMarkdownImage(),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { isTraversalGroup = true }
                        .padding(
                            top = padding.listItemTop,
                            bottom = padding.listItemBottom,
                        ),
                ) {
                    Box(
                        modifier = Modifier.graphicsLayer(
                            // 隐藏 marker 但保留它的测量宽度，避免正文横向跳动。
                            alpha = if (markerVisible) 1f else 0f,
                        ),
                    ) {
                        if (checkboxNode != null) {
                            components.checkbox(
                                MarkdownComponentModel(
                                    content = model.content,
                                    node = checkboxNode,
                                    typography = model.typography,
                                ),
                            )
                        } else if (ordered) {
                            Text(
                                text = "${initialListNumber + index}.",
                                style = model.typography.ordered.copy(
                                    color = MiuixTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                        } else {
                            // Compose 单行 Text 在默认 Trim.Both 下忽略 lineHeight，行框即字体自然行高；
                            // marker 必须与正文同 fontSize/lineHeight 才能共享度规对齐，
                            // 层级差异只通过字形与颜色表达。
                            val bulletDepth = depth % 3
                            Text(
                                text = when (bulletDepth) {
                                    0 -> "•"
                                    1 -> "◦"
                                    else -> "▪"
                                },
                                style = model.typography.bullet.copy(
                                    color = if (bulletDepth == 2) {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    } else {
                                        MiuixTheme.colorScheme.primary
                                    },
                                ),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Column {
                        item.children.forEach { child ->
                            when (child.type) {
                                MarkdownElementTypes.ORDERED_LIST -> {
                                    ChatMarkdownList(
                                        model = MarkdownComponentModel(
                                            content = model.content,
                                            node = child,
                                            typography = model.typography,
                                        ),
                                        ordered = true,
                                        revealCoordinator = revealCoordinator,
                                        suppressEmptyMarker = suppressEmptyMarker,
                                        depth = depth + 1,
                                    )
                                }

                                MarkdownElementTypes.UNORDERED_LIST -> {
                                    ChatMarkdownList(
                                        model = MarkdownComponentModel(
                                            content = model.content,
                                            node = child,
                                            typography = model.typography,
                                        ),
                                        ordered = false,
                                        revealCoordinator = revealCoordinator,
                                        suppressEmptyMarker = suppressEmptyMarker,
                                        depth = depth + 1,
                                    )
                                }

                                else -> MarkdownElement(
                                    node = child,
                                    components = components,
                                    content = model.content,
                                    includeSpacer = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberStartedRevealKeys(
    coordinator: SmoothTextRevealCoordinator?,
): Set<RevealBlockKey> = if (coordinator == null) {
    emptySet()
} else {
    coordinator.started.collectAsState().value
}

internal fun streamingListMarkerVisible(
    coordinatorActive: Boolean,
    firstRevealKey: RevealBlockKey?,
    startedRevealKeys: Set<RevealBlockKey>,
    containsImage: Boolean,
): Boolean = !coordinatorActive ||
    firstRevealKey?.let(startedRevealKeys::contains) == true ||
    (firstRevealKey == null && containsImage)

@Composable
private fun ChatRevealRawText(
    model: MarkdownComponentModel,
    revealCoordinator: SmoothTextRevealCoordinator,
) {
    val text = remember(model.content, model.node) {
        AnnotatedString(model.node.getUnescapedTextInNode(model.content))
    }
    ChatRevealAnnotatedText(
        text = text,
        node = model.node,
        sourceContent = model.content,
        style = model.typography.text,
        revealCoordinator = revealCoordinator,
    )
}

@Composable
private fun ChatRevealMarkdownText(
    model: MarkdownComponentModel,
    style: TextStyle,
    revealCoordinator: SmoothTextRevealCoordinator,
    modifier: Modifier = Modifier,
    contentChildType: IElementType? = null,
) {
    val annotatorSettings = annotatorSettings()
    val contentNode = remember(model.node, contentChildType) {
        contentChildType?.let(model.node::findChildOfType) ?: model.node
    }
    val text = remember(model.content, contentNode, style, annotatorSettings) {
        buildAnnotatedString {
            pushStyle(style.toSpanStyle())
            buildMarkdownAnnotatedString(
                content = model.content,
                node = contentNode,
                annotatorSettings = annotatorSettings,
            )
            pop()
        }
    }
    ChatRevealAnnotatedText(
        text = text,
        node = model.node,
        sourceContent = model.content,
        style = style,
        revealCoordinator = revealCoordinator,
        modifier = modifier,
    )
}

@Composable
private fun ChatRevealAnnotatedText(
    text: AnnotatedString,
    node: ASTNode,
    sourceContent: String,
    style: TextStyle,
    revealCoordinator: SmoothTextRevealCoordinator,
    modifier: Modifier = Modifier,
) {
    val revealState = rememberSmoothTextRevealState(
        key = RevealBlockKey(node.startOffset),
        coordinator = revealCoordinator,
    )
    MarkdownText(
        content = text,
        node = node,
        modifier = modifier.smoothTextReveal(revealState),
        style = style.copy(textMotion = TextMotion.Animated),
        onTextLayout = { layoutResult, _ ->
            revealState.onTextLayout(text.text, layoutResult)
        },
        sourceContent = sourceContent,
    )
}

/**
 * 标题自身只负责文字样式；与相邻块的距离由文档级排版统一决定。
 */
@Composable
private fun ChatHeadingBlock(
    model: MarkdownComponentModel,
    style: TextStyle,
    setext: Boolean = false,
    revealCoordinator: SmoothTextRevealCoordinator? = null,
) {
    val contentChildType = if (setext) {
        MarkdownTokenTypes.SETEXT_CONTENT
    } else {
        MarkdownTokenTypes.ATX_CONTENT
    }
    if (revealCoordinator == null || model.node.containsMarkdownImage()) {
        MarkdownHeader(
            content = model.content,
            node = model.node,
            style = style,
            contentChildType = contentChildType,
        )
    } else {
        ChatRevealMarkdownText(
            model = model,
            style = style,
            revealCoordinator = revealCoordinator,
            contentChildType = contentChildType,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/**
 * 代码块：顶栏显示语言标签并提供一键复制，正文等宽字体、超出横向滚动。
 */
@Composable
private fun ChatCodeBlock(
    code: String,
    language: String?,
    style: TextStyle,
    revealState: SmoothTextRevealState? = null,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surface)
            .border(
                0.5.dp,
                MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                RoundedCornerShape(10.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 13.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language?.takeIf { it.isNotBlank() } ?: "code",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    @Suppress("DEPRECATION")
                    clipboardManager.setText(AnnotatedString(code))
                    copied = true
                },
                minWidth = 28.dp,
                minHeight = 28.dp,
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check
                        else Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(
                        if (copied) R.string.copy_copied else R.string.copy_code,
                    ),
                    modifier = Modifier.size(13.dp),
                    tint = if (copied) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f)
                    },
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp)
                .height(0.5.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
        )
        val codeModifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 13.dp, vertical = 11.dp)
            .let { base ->
                if (revealState != null) base.smoothTextReveal(revealState) else base
            }
        Text(
            text = code,
            style = if (revealState != null) {
                style.copy(textMotion = TextMotion.Animated)
            } else {
                style
            },
            color = MiuixTheme.colorScheme.onSurface,
            modifier = codeModifier,
            onTextLayout = revealState?.let { state ->
                { layoutResult -> state.onTextLayout(code, layoutResult) }
            },
        )
    }
}

private val ChatTableCellWidth = 112.dp

/**
 * 表格：细描边容器 + 表头浅底加粗 + 行间发丝分隔线；列宽不足时整体横向滚动。
 */
@Composable
private fun ChatMarkdownTable(
    content: String,
    node: ASTNode,
    style: TextStyle,
    revealCoordinator: SmoothTextRevealCoordinator? = null,
) {
    val headerCells = remember(node) {
        node.findChildOfType(HEADER)?.children?.filter { it.type == CELL }.orEmpty()
    }
    val bodyRows = remember(node) {
        node.children.filter { it.type == ROW }
            .map { row -> row.children.filter { it.type == CELL } }
    }
    if (headerCells.isEmpty()) return

    val borderColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        val tableWidth = ChatTableCellWidth * headerCells.size
        val scrollable = maxWidth <= tableWidth
        Column(
            modifier = (if (scrollable) {
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .requiredWidth(tableWidth)
            } else {
                Modifier.fillMaxWidth()
            })
                .clip(RoundedCornerShape(10.dp))
                .border(0.5.dp, borderColor, RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f))
                    .height(IntrinsicSize.Max),
            ) {
                headerCells.forEach { cell ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        ChatMarkdownTableCell(
                            content = content,
                            cell = cell,
                            style = style.copy(fontWeight = FontWeight.Medium),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            revealCoordinator = revealCoordinator,
                        )
                    }
                }
            }
            bodyRows.forEach { rowCells ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(borderColor.copy(alpha = 0.6f)),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    rowCells.forEach { cell ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            ChatMarkdownTableCell(
                                content = content,
                                cell = cell,
                                style = style,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                                revealCoordinator = revealCoordinator,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMarkdownTableCell(
    content: String,
    cell: ASTNode,
    style: TextStyle,
    maxLines: Int,
    overflow: TextOverflow,
    revealCoordinator: SmoothTextRevealCoordinator?,
) {
    if (revealCoordinator == null || cell.containsMarkdownImage()) {
        MarkdownTableBasicText(
            content = content,
            cell = cell,
            style = style,
            maxLines = maxLines,
            overflow = overflow,
        )
        return
    }

    val annotatorSettings = annotatorSettings()
    val text = remember(content, cell, style, annotatorSettings) {
        buildAnnotatedString {
            pushStyle(style.toSpanStyle())
            buildMarkdownAnnotatedString(
                content = content,
                node = cell,
                annotatorSettings = annotatorSettings,
            )
            pop()
        }
    }
    val revealState = rememberSmoothTextRevealState(
        key = RevealBlockKey(cell.startOffset),
        coordinator = revealCoordinator,
    )
    Text(
        text = text,
        style = style.copy(textMotion = TextMotion.Animated),
        color = MiuixTheme.colorScheme.onSurface,
        maxLines = maxLines,
        overflow = overflow,
        modifier = Modifier.smoothTextReveal(revealState),
        onTextLayout = { layoutResult ->
            revealState.onTextLayout(text.text, layoutResult)
        },
    )
}

/**
 * 引用块：圆角浅色竖条 + 弱化文字。
 * 库默认实现把竖条颜色绑死在 quote 文字颜色上，无法分别控制，因此竖条自绘；
 * 子节点仍交给 ambient components，流式显现与嵌套引用行为不变。
 */
@Composable
private fun ChatBlockQuote(model: MarkdownComponentModel) {
    val components = LocalMarkdownComponents.current
    val padding = LocalMarkdownPadding.current
    val dimens = LocalMarkdownDimens.current
    val a11yLabels = LocalMarkdownA11yLabels.current
    val barColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.4f)
    val emptyLineHeight = with(LocalDensity.current) {
        model.typography.quote.lineHeight.takeOrElse { 22.sp }.toDp()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = a11yLabels.blockquote }
            .drawBehind {
                val thickness = dimens.blockQuoteThickness.toPx()
                val x = padding.blockQuoteBar
                    .calculateStartPadding(LayoutDirection.Ltr).toPx() + thickness / 2
                drawLine(
                    color = barColor,
                    strokeWidth = thickness,
                    start = Offset(x, padding.blockQuoteBar.calculateTopPadding().toPx()),
                    end = Offset(
                        x,
                        size.height - padding.blockQuoteBar.calculateBottomPadding().toPx(),
                    ),
                    cap = StrokeCap.Round,
                )
            }
            .padding(padding.blockQuote),
    ) {
        model.node.children.forEach { child ->
            key(child.startOffset) {
                when (child.type) {
                    MarkdownElementTypes.BLOCK_QUOTE -> ChatBlockQuote(
                        MarkdownComponentModel(
                            content = model.content,
                            node = child,
                            typography = model.typography,
                        ),
                    )

                    MarkdownTokenTypes.EOL -> Spacer(Modifier.height(emptyLineHeight))

                    else -> MarkdownElement(
                        node = child,
                        components = components,
                        content = model.content,
                        includeSpacer = false,
                    )
                }
            }
        }
    }
}

private fun ASTNode.containsMarkdownImage(): Boolean =
    type == MarkdownElementTypes.IMAGE || children.any { child -> child.containsMarkdownImage() }

/** 找到列表项中首个会被显现协调器管理的块，marker 以它作为显示时机。 */
private fun ASTNode.firstRevealBlockKey(): RevealBlockKey? = when (type) {
    MarkdownTokenTypes.TEXT -> RevealBlockKey(startOffset)

    MarkdownElementTypes.PARAGRAPH,
    MarkdownElementTypes.ATX_1,
    MarkdownElementTypes.ATX_2,
    MarkdownElementTypes.ATX_3,
    MarkdownElementTypes.ATX_4,
    MarkdownElementTypes.ATX_5,
    MarkdownElementTypes.ATX_6,
    MarkdownElementTypes.SETEXT_1,
    MarkdownElementTypes.SETEXT_2,
    -> if (!containsMarkdownImage()) RevealBlockKey(startOffset) else null

    MarkdownElementTypes.CODE_FENCE ->
        if (children.size >= 3) RevealBlockKey(startOffset) else null

    MarkdownElementTypes.CODE_BLOCK ->
        if (children.isNotEmpty()) RevealBlockKey(startOffset) else null

    TABLE -> children.asSequence()
        .flatMap { it.depthFirstSequence() }
        .firstOrNull { it.type == CELL && !it.containsMarkdownImage() }
        ?.let { RevealBlockKey(it.startOffset) }

    MarkdownElementTypes.IMAGE,
    MarkdownTokenTypes.EOL,
    MarkdownTokenTypes.HORIZONTAL_RULE,
    -> null

    else -> children.asSequence().mapNotNull(ASTNode::firstRevealBlockKey).firstOrNull()
}

private fun ASTNode.depthFirstSequence(): Sequence<ASTNode> = sequence {
    yield(this@depthFirstSequence)
    children.forEach { child -> yieldAll(child.depthFirstSequence()) }
}

private fun State.Success.revealBlockKeys(): Set<RevealBlockKey> = buildSet {
    node.children.forEach { child -> collectRevealBlockKeys(child) }
}

private fun MutableSet<RevealBlockKey>.collectRevealBlockKeys(node: ASTNode) {
    when (node.type) {
        MarkdownTokenTypes.TEXT -> add(RevealBlockKey(node.startOffset))

        MarkdownElementTypes.PARAGRAPH,
        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5,
        MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.SETEXT_1,
        MarkdownElementTypes.SETEXT_2,
        -> if (!node.containsMarkdownImage()) add(RevealBlockKey(node.startOffset))

        MarkdownElementTypes.CODE_FENCE -> {
            if (node.children.size >= 3) add(RevealBlockKey(node.startOffset))
        }

        MarkdownElementTypes.CODE_BLOCK -> {
            if (node.children.isNotEmpty()) add(RevealBlockKey(node.startOffset))
        }

        TABLE -> collectTableCellRevealKeys(node)

        MarkdownElementTypes.IMAGE,
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.HORIZONTAL_RULE,
        -> Unit

        else -> node.children.forEach { child -> collectRevealBlockKeys(child) }
    }
}

private fun MutableSet<RevealBlockKey>.collectTableCellRevealKeys(node: ASTNode) {
    if (node.type == CELL) {
        if (!node.containsMarkdownImage()) add(RevealBlockKey(node.startOffset))
        return
    }
    node.children.forEach { child -> collectTableCellRevealKeys(child) }
}

// ── 思考过程 ─────────────────────────────────────────────────────────

@Composable
private fun ThinkingRow(
    message: ThinkingMessageUi,
    retainedStreamingState: StreamingMarkdownState?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(!message.collapsed) }
    var manuallyExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val keepStreamingMarkdown = remember(message.id) { message.isStreaming }
    val streamingState = if (keepStreamingMarkdown) {
        retainedStreamingState ?: remember(message.id) { StreamingMarkdownState() }
    } else {
        null
    }
    val completedMarkdownState = (streamingState ?: retainedStreamingState)
        ?.snapshot?.completedStateFor(message.content)
    LaunchedEffect(message.isStreaming) {
        if (message.isStreaming && !manuallyExpanded) expanded = true
    }

    // Markdown 状态在行级提前创建：行进入组合（工作过程展开或滚动到可视区）时就开始
    // 后台解析，而不是等到首次点击展开。否则首帧只能测量 loading fallback 的纯文本高度，
    // 解析完成后正文高度会再次变化；状态挂在行级还能在收起/展开循环中存活，
    // 避免每次展开都重新走一遍异步解析。
    val stableMarkdownState = if (streamingState == null && completedMarkdownState == null) {
        rememberMarkdownState(
            content = message.content,
            retainState = true,
        )
    } else {
        null
    }

    if (compact) {
        WorkThinkingStep(
            message = message,
            expanded = expanded,
            onToggle = {
                manuallyExpanded = true
                expanded = !expanded
            },
            streamingState = streamingState,
            completedMarkdownState = completedMarkdownState,
            stableMarkdownState = stableMarkdownState,
            modifier = modifier,
        )
        return
    }

    val pulseAlpha = rememberActivePulse(
        active = message.isStreaming,
        label = "thinking_pulse",
    )

    // compact 模式渲染在工作过程卡片内部，不再携带自己的卡片外壳，避免卡中卡。
    val containerModifier = if (compact) {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
    } else {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .squircleSurface(
                color = MiuixTheme.colorScheme.surface,
                cornerRadius = 14.dp,
            )
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.50f),
                cornerRadius = 14.dp,
            )
    }

    Column(modifier = containerModifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                    manuallyExpanded = true
                    expanded = !expanded
                }
                .padding(horizontal = if (compact) 4.dp else 13.dp, vertical = if (compact) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Lightbulb,
                contentDescription = null,
                modifier = Modifier
                    .size(15.dp)
                    .graphicsLayer(alpha = if (message.isStreaming) pulseAlpha else 1f),
                tint = if (message.isStreaming) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (message.isStreaming) {
                    stringResource(R.string.reasoning_in_progress)
                } else {
                    message.elapsedSeconds?.takeIf { it > 0 }?.let { seconds ->
                        pluralStringResource(
                            R.plurals.reasoning_completed_seconds,
                            seconds,
                            seconds,
                        )
                    } ?: stringResource(R.string.reasoning_completed)
                },
                style = MiuixTheme.textStyles.body2,
                color = if (message.isStreaming) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandMore
                    else Icons.Rounded.ChevronRight,
                contentDescription = stringResource(
                    if (expanded) R.string.reasoning_collapse else R.string.reasoning_expand,
                ),
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
            )
        }

        AnimatedVisibility(visible = expanded && message.content.isNotBlank()) {
            Column {
                if (!compact) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 13.dp)
                            .height(0.5.dp)
                            .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
                    )
                }
                val contentModifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (compact) 27.dp else 13.dp,
                        end = 13.dp,
                        top = if (compact) 2.dp else 8.dp,
                        bottom = if (compact) 8.dp else 12.dp,
                    )
                if (streamingState != null && (message.isStreaming || completedMarkdownState == null)) {
                    StreamingMarkdown(
                        state = streamingState,
                        content = message.content,
                        isStreaming = message.isStreaming,
                        onRevealCompleteChange = {},
                        tone = ChatMarkdownTone.Thinking,
                        modifier = contentModifier,
                    )
                } else {
                    StableMarkdown(
                        content = message.content,
                        tone = ChatMarkdownTone.Thinking,
                        markdownState = stableMarkdownState,
                        parsedState = completedMarkdownState,
                        modifier = contentModifier,
                    )
                }
            }
        }
    }
}

// ── 工具调用：优雅极简时间线 ─────────────────────────────────────────

@Composable
private fun ToolActivityInline(
    message: ToolActivityMessageUi,
    onOpenBrowser: () -> Unit,
    showBrowserShortcut: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var isExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    // 只有「当前浏览器」卡片订阅实时会话快照，避免每个工具行都跟随快照重组
    val browserSnapshot = if (showBrowserShortcut) {
        AgentBrowserSession.snapshots.collectAsState().value
    } else {
        null
    }

    val pulseAlpha = rememberActivePulse(
        active = message.status == ToolActivityStatusUi.Running,
        label = "tool_pulse",
    )

    val title = message.argumentsSummary.ifBlank { toolDisplayName(message.toolName) }
    val browserSubtitle = browserSnapshot?.let { snapshot ->
        when {
            snapshot.isLoading ->
                stringResource(R.string.tool_browser_loading, snapshot.progress)
            snapshot.host.isNotBlank() && snapshot.title.isNotBlank() ->
                "${snapshot.host} · ${snapshot.title}"
            snapshot.host.isNotBlank() -> snapshot.host
            else -> null
        }
    }
    // 失败原因直接显示在折叠行，不必展开卡片；剥离去重「失败」前缀与日志用的 code= 尾巴
    val failureSubtitle = if (message.status == ToolActivityStatusUi.Failed) {
        message.resultSummary
            ?.lineSequence()?.firstOrNull()
            ?.removePrefix("失败 · ")
            ?.substringBefore(" · code=")
            ?.takeIf { it.isNotBlank() && it != "失败" }
    } else {
        null
    }

    if (compact) {
        WorkToolStep(
            message = message,
            title = title,
            // 规范 5：并列分隔用不带空格的「·」；运行时摘要沿用旧写法「 · 」，显示时统一。
            subtitle = (failureSubtitle ?: browserSubtitle ?: message.stepSummary())?.replace(" · ", "·"),
            expanded = isExpanded,
            onToggle = { isExpanded = !isExpanded },
            showBrowserShortcut = showBrowserShortcut,
            browserSnapshot = browserSnapshot,
            onOpenBrowser = onOpenBrowser,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { isExpanded = !isExpanded }
            .padding(horizontal = if (compact) 10.dp else 20.dp, vertical = 3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 5.dp),
        ) {
            // 工具图标与思考行的灯泡共用同一前导槽位，保证卡片内左边缘对齐。
            Icon(
                imageVector = iconForTool(message.toolName),
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = when (message.status) {
                    ToolActivityStatusUi.Running -> MiuixTheme.colorScheme.primary
                    ToolActivityStatusUi.Failed -> StatusError
                    ToolActivityStatusUi.Unknown -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    ToolActivityStatusUi.Success ->
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f)
                }
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body2,
                    color = if (message.status == ToolActivityStatusUi.Running) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = failureSubtitle ?: browserSubtitle
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (failureSubtitle != null) {
                            StatusError
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                AnimatedContent(
                    targetState = message.status,
                    transitionSpec = {
                        (fadeIn(tween(150)) + scaleIn(tween(170), initialScale = 0.86f))
                            .togetherWith(
                                fadeOut(tween(90)) + scaleOut(tween(110), targetScale = 0.86f)
                            )
                    },
                    label = "tool_status",
                ) { status ->
                    // 成功是常态，只留低饱和度对勾；运行中与失败才占用视觉注意力
                    if (status == ToolActivityStatusUi.Success) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = stringResource(R.string.tool_status_success),
                            modifier = Modifier.size(13.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.graphicsLayer(
                                alpha = if (status == ToolActivityStatusUi.Running) pulseAlpha else 1f
                            ),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(status.statusColor())
                            )
                            Text(
                                text = status.statusLabel(),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Rounded.ExpandMore
                        else Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                )
            }
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 27.dp, top = 2.dp, bottom = 6.dp)
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.surfaceContainer,
                        cornerRadius = 10.dp,
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (!message.command.isNullOrBlank()) {
                    ToolCommandBlock(
                        command = message.command,
                        context = message.argumentsSummary,
                        modifier = Modifier.padding(
                            bottom = if (message.resultSummary.isNullOrBlank()) 0.dp else 10.dp,
                        ),
                    )
                }
                if (message.resultSummary != null && message.resultSummary.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.ui_result_0a2c91),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    Text(
                        text = message.resultSummary,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showBrowserShortcut) {
                    browserSnapshot?.takeIf { it.available }?.let { snapshot ->
                        BrowserPagePreview(
                            snapshot = snapshot,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            text = stringResource(R.string.ui_open_current_browser_58358e),
                            onClick = onOpenBrowser,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            minHeight = 36.dp,
                            textStyle = MiuixTheme.textStyles.body2,
                        )
                    }
                }
            }
        }
    }
}

/** 工具步骤第二行：成功时取结果第一行（如包名、门店信息），运行中与没有结果时不显示。 */
private fun ToolActivityMessageUi.stepSummary(): String? = when (status) {
    ToolActivityStatusUi.Success -> resultSummary?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
    else -> null
}

/**
 * `Work/Step` Supplement（规范 8.1、8.4）：Indigo 转折箭头 16 +「你的补充」+ 浅底引用块（bg/surface-muted，圆角 12，
 * 内边距 10 / 6）显示原话 + 右侧状态「下一步生效」→「已采纳」（交叉淡化 `fast`）。
 * 长按保留原用户消息的复制 / 编辑 / 删除（执行中不可用）。
 */
@Composable
private fun SupplementStep(
    message: UserMessageUi,
    applied: Boolean,
    actionsEnabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val tooltipState = rememberTooltipState(isPersistent = true)
    LaunchedEffect(actionsEnabled) { if (!actionsEnabled) tooltipState.dismiss() }
    val text = remember(message.content) { AgentFileReferencePromptCodec.parse(message.content).request }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(positioning = TooltipAnchorPosition.Below),
        tooltip = {
            RichTooltip(insideMargin = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    MessageTooltipAction(Icons.Rounded.ContentCopy, stringResource(R.string.ui_copy_4edd1d)) {
                        @Suppress("DEPRECATION")
                        clipboardManager.setText(AnnotatedString(text))
                        tooltipState.dismiss()
                    }
                    MessageTooltipAction(Icons.Rounded.Edit, stringResource(R.string.ui_edit_a7f814)) {
                        tooltipState.dismiss(); onEdit()
                    }
                    MessageTooltipAction(Icons.Rounded.Delete, stringResource(R.string.ui_delete_3755f5)) {
                        tooltipState.dismiss(); onDelete()
                    }
                }
            }
        },
        state = tooltipState,
        focusable = true,
        enableUserInput = actionsEnabled,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    io.github.fartown.movo.ui.theme.MovoIcon(
                        io.github.fartown.movo.ui.theme.MovoIcons.CornerDownRight, null, size = 16.dp,
                        tint = io.github.fartown.movo.ui.theme.MovoColors.indigoFg,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.movo_supplement_title),
                    style = io.github.fartown.movo.ui.theme.MovoTypography.labelMedium,
                    color = io.github.fartown.movo.ui.theme.MovoColors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.animation.Crossfade(
                    targetState = applied,
                    animationSpec = io.github.fartown.movo.ui.theme.MovoMotion.fast(),
                    label = "supplementStatus",
                ) { isApplied ->
                    Text(
                        stringResource(if (isApplied) R.string.movo_supplement_applied else R.string.movo_supplement_pending),
                        style = io.github.fartown.movo.ui.theme.MovoTypography.labelRegular,
                        color = io.github.fartown.movo.ui.theme.MovoColors.textTertiary,
                    )
                }
            }
            Text(
                text = text,
                style = io.github.fartown.movo.ui.theme.MovoTypography.bodyRegular,
                color = io.github.fartown.movo.ui.theme.MovoColors.textPrimary,
                modifier = Modifier
                    .padding(start = 28.dp, top = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(io.github.fartown.movo.ui.theme.MovoColors.bgSurfaceMuted)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * `Work/Step` 思考行：sparkle 16 次要色 →「思考中」（Q3 光带）/「思考·N 秒」→ 收起时两行摘要；
 * 整行可点展开完整思考内容（规范 8.1、8.8）。
 */
@Composable
private fun WorkThinkingStep(
    message: ThinkingMessageUi,
    expanded: Boolean,
    onToggle: () -> Unit,
    streamingState: StreamingMarkdownState?,
    completedMarkdownState: State.Success?,
    stableMarkdownState: MarkdownState?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .movoClickableRow(onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                io.github.fartown.movo.ui.theme.MovoIcon(
                    io.github.fartown.movo.ui.theme.MovoIcons.Sparkle, null, size = 16.dp,
                    tint = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                )
            }
            Spacer(Modifier.width(12.dp))
            io.github.fartown.movo.ui.components.movo.MovoShimmerText(
                text = when {
                    message.isStreaming -> stringResource(R.string.movo_thinking_in_progress)
                    (message.elapsedSeconds ?: 0) > 0 -> stringResource(R.string.movo_thinking_seconds, message.elapsedSeconds ?: 0)
                    else -> stringResource(R.string.movo_thinking_done)
                },
                style = io.github.fartown.movo.ui.theme.MovoTypography.labelMedium,
                color = io.github.fartown.movo.ui.theme.MovoColors.textPrimary,
                active = message.isStreaming,
                modifier = Modifier.weight(1f),
            )
        }
        if (message.content.isNotBlank()) {
            val contentModifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 2.dp)
            if (!expanded) {
                Text(
                    text = message.content.plainPreview(),
                    style = io.github.fartown.movo.ui.theme.MovoTypography.labelRegular,
                    color = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = contentModifier,
                )
            } else if (streamingState != null && (message.isStreaming || completedMarkdownState == null)) {
                StreamingMarkdown(
                    state = streamingState,
                    content = message.content,
                    isStreaming = message.isStreaming,
                    onRevealCompleteChange = {},
                    tone = ChatMarkdownTone.Thinking,
                    modifier = contentModifier,
                )
            } else {
                StableMarkdown(
                    content = message.content,
                    tone = ChatMarkdownTone.Thinking,
                    markdownState = stableMarkdownState,
                    parsedState = completedMarkdownState,
                    modifier = contentModifier,
                )
            }
        }
    }
}

/** 思考摘要：去掉常见 Markdown 标记后折叠空白。 */
private fun String.plainPreview(): String =
    replace(Regex("[*_`#>]+"), "").replace(Regex("\\s+"), " ").trim()

/**
 * `Work/Step` 工具行：状态图标 16（完成 Green ✓ / 进行中 Indigo 加载圈 / 失败 Rose ✕ / 中断 次要色 !）
 * → 标题 Label/Medium + 说明 Label/Regular 次要色；整行可点展开 `Run/StepDetail`
 * （bg/surface-muted 圆角 12 内边距 12：命令块、结果、浏览器预览与「打开当前浏览器」）。
 */
@Composable
private fun WorkToolStep(
    message: ToolActivityMessageUi,
    title: String,
    subtitle: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    showBrowserShortcut: Boolean,
    browserSnapshot: BrowserSessionSnapshot?,
    onOpenBrowser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .movoClickableRow(onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.height(18.dp).width(16.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.Crossfade(
                    targetState = message.status,
                    animationSpec = io.github.fartown.movo.ui.theme.MovoMotion.fast(),
                    label = "stepStatus",
                ) { status ->
                    when (status) {
                        ToolActivityStatusUi.Running -> io.github.fartown.movo.ui.components.movo.MovoSpinner()
                        ToolActivityStatusUi.Success -> io.github.fartown.movo.ui.theme.MovoIcon(
                            io.github.fartown.movo.ui.theme.MovoIcons.Check, stringResource(R.string.tool_status_success),
                            size = 16.dp, tint = io.github.fartown.movo.ui.theme.MovoColors.greenFg,
                        )
                        ToolActivityStatusUi.Failed -> io.github.fartown.movo.ui.theme.MovoIcon(
                            io.github.fartown.movo.ui.theme.MovoIcons.X, status.statusLabel(),
                            size = 16.dp, tint = io.github.fartown.movo.ui.theme.MovoColors.roseFg,
                        )
                        ToolActivityStatusUi.Unknown -> io.github.fartown.movo.ui.theme.MovoIcon(
                            io.github.fartown.movo.ui.theme.MovoIcons.CircleAlert, stringResource(R.string.movo_step_interrupted),
                            size = 16.dp, tint = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = io.github.fartown.movo.ui.theme.MovoTypography.labelMedium,
                    color = io.github.fartown.movo.ui.theme.MovoColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = io.github.fartown.movo.ui.theme.MovoTypography.labelRegular,
                        color = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val started = message.startedAtMillis
            val finished = message.finishedAtMillis
            if (started != null && finished != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatStepDuration(finished - started),
                    style = io.github.fartown.movo.ui.theme.MovoTypography.numericLabel,
                    color = io.github.fartown.movo.ui.theme.MovoColors.textTertiary,
                    maxLines = 1,
                )
            }
        }
        // 展开：高度 `standard`，内容在高度过渡开始 40ms 后淡入 `fast`（规范 9.3「展开 / 收起」）。
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(
                tween(
                    io.github.fartown.movo.ui.theme.MovoMotion.FAST,
                    delayMillis = io.github.fartown.movo.ui.theme.MovoMotion.STAGGER,
                    easing = io.github.fartown.movo.ui.theme.MovoMotion.EasingStandard,
                ),
            ) + expandVertically(io.github.fartown.movo.ui.theme.MovoMotion.standard()),
            exit = fadeOut(io.github.fartown.movo.ui.theme.MovoMotion.fastExit()) +
                shrinkVertically(io.github.fartown.movo.ui.theme.MovoMotion.standard()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, top = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(io.github.fartown.movo.ui.theme.MovoColors.bgSurfaceMuted)
                    .padding(12.dp),
            ) {
                if (!message.command.isNullOrBlank()) {
                    ToolCommandBlock(
                        command = message.command,
                        context = message.argumentsSummary,
                        modifier = Modifier.padding(bottom = if (message.resultSummary.isNullOrBlank()) 0.dp else 10.dp),
                    )
                }
                if (!message.resultSummary.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.ui_result_0a2c91),
                        style = io.github.fartown.movo.ui.theme.MovoTypography.labelMedium,
                        color = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                    )
                    Text(
                        text = message.resultSummary,
                        style = io.github.fartown.movo.ui.theme.MovoTypography.labelRegular,
                        color = io.github.fartown.movo.ui.theme.MovoColors.textPrimary,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showBrowserShortcut) {
                    browserSnapshot?.takeIf { it.available }?.let { snapshot ->
                        BrowserPagePreview(snapshot = snapshot, modifier = Modifier.padding(top = 8.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        io.github.fartown.movo.ui.components.movo.MovoPillButton(
                            label = stringResource(R.string.ui_open_current_browser_58358e),
                            onClick = onOpenBrowser,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 浏览器工具的实时页面预览：迷你地址条 + 当前视口截图。
 *
 * 截图只在页面加载中或内容稳定后的低频节拍刷新；组合销毁即停止，
 * 不做后台轮询。截图不可用时退化为图标占位。
 */
@Composable
private fun BrowserPagePreview(
    snapshot: BrowserSessionSnapshot,
    modifier: Modifier = Modifier,
) {
    var preview by remember(snapshot.url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(snapshot.url, snapshot.isLoading) {
        while (true) {
            val image = withContext(Dispatchers.IO) {
                AgentBrowserSession.capturePreview()?.let { decodeDataUrlBitmap(it.dataUrl) }
            }
            if (image != null) preview = image
            delay(if (snapshot.isLoading) 1_200L else 4_000L)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .squircleSurface(
                color = MiuixTheme.colorScheme.surfaceContainer,
                cornerRadius = 10.dp,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (snapshot.isLoading) StatusRunning else StatusSuccess),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = snapshot.host.ifBlank { snapshot.displayUrl },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val image = preview
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.tool_browser_preview),
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Language,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.outline,
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (snapshot.title.isNotBlank()) {
                Text(
                    text = snapshot.title,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (snapshot.displayUrl.isNotBlank()) {
                Text(
                    text = snapshot.displayUrl,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ToolCommandBlock(
    command: String,
    context: String,
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember(command) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .squircleSurface(
                color = MiuixTheme.colorScheme.surface,
                cornerRadius = 10.dp,
            )
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                cornerRadius = 10.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 5.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.ifBlank { stringResource(R.string.shell_command) },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    @Suppress("DEPRECATION")
                    clipboardManager.setText(AnnotatedString(command))
                    copied = true
                },
                minWidth = 28.dp,
                minHeight = 28.dp,
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check
                        else Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(
                        if (copied) R.string.copy_copied else R.string.copy_command,
                    ),
                    modifier = Modifier.size(13.dp),
                    tint = if (copied) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f)
                    },
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .height(0.5.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
        )
        SelectionContainer {
            Text(
                text = command,
                style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

// ── Run trace：轻量入口行 ─────────────────────────────────────────────

@Composable
private fun RunTraceRow(
    message: RunTraceMessageUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surface)
            .border(
                0.5.dp,
                MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.ui_available_capacity_743337),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
        )
    }
}

// ── 工具摘要 ──────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolSummaryInline(
    message: ToolSummaryMessageUi,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 10.dp else 20.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        message.tools.forEach { tool ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surface)
                    .border(
                        0.5.dp,
                        MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = iconForTool(tool),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = toolDisplayName(tool),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

// ── 建议语 ────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionChipsRow(
    message: SuggestionChipsMessageUi,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 推荐追问 `Chip/Suggestion`（规范 8.1）：高 32、圆角 16、bg/surface + 0.5 描边；sparkle 14 Indigo + Label/Medium，
    // 左 12 / 右 14、间距 6；自动换行，间距 8。随回答完成在操作行之后依次淡入上移 8（规范 9.4「回答完成」）。
    var played by androidx.compose.runtime.saveable.rememberSaveable(message.id) { mutableStateOf(false) }
    val play = remember(message.id) { !played }
    LaunchedEffect(message.id) { played = true }
    io.github.fartown.movo.ui.components.movo.MovoEntrance(play = play, step = 1, modifier = modifier) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            message.prompts.forEach { prompt ->
                val shape = RoundedCornerShape(16.dp)
                Row(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(shape)
                        .background(io.github.fartown.movo.ui.theme.MovoColors.bgSurface)
                        .border(0.5.dp, io.github.fartown.movo.ui.theme.MovoColors.borderHairline, shape)
                        .movoClickable(io.github.fartown.movo.ui.components.movo.PressKind.Card, shape = shape) { onSuggestionClick(prompt) }
                        .padding(start = 12.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    io.github.fartown.movo.ui.theme.MovoIcon(
                        io.github.fartown.movo.ui.theme.MovoIcons.Sparkle, null, size = 14.dp,
                        tint = io.github.fartown.movo.ui.theme.MovoColors.indigoFg,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = prompt,
                        style = io.github.fartown.movo.ui.theme.MovoTypography.labelMedium,
                        color = io.github.fartown.movo.ui.theme.MovoColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ── 辅助 ──────────────────────────────────────────────────────────────

@Composable
private fun ToolActivityStatusUi.statusColor() = when (this) {
    ToolActivityStatusUi.Running -> StatusRunning
    ToolActivityStatusUi.Success -> StatusSuccess
    ToolActivityStatusUi.Failed -> StatusError
    ToolActivityStatusUi.Unknown -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

@Composable
private fun ToolActivityStatusUi.statusLabel(): String = when (this) {
    ToolActivityStatusUi.Running -> stringResource(R.string.tool_status_running)
    ToolActivityStatusUi.Success -> stringResource(R.string.tool_status_success)
    ToolActivityStatusUi.Failed -> stringResource(R.string.tool_status_failed)
    ToolActivityStatusUi.Unknown -> stringResource(R.string.tool_status_unknown)
}

/** 任务失败或中断时，直达这次任务的运行日志（运行日志功能定义 D3）；对话浮层里没有入口。 */
/**
 * 对话中的失败任务卡（Figma「26 · 对话中 · 任务失败」、规范 8.10）：失败摘要条（Rose ✕ +「任务没有完成」+ 用时 ›，
 * 点开运行日志任务详情）→ 原因卡（原因标题 + 说明 +「重试」「查看日志」）。原来的复制、删除收进「更多」。
 */
@Composable
private fun RunFailureCard(
    message: SystemNoticeMessageUi,
    actionsEnabled: Boolean,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openLog = io.github.fartown.movo.ui.screens.diagnostics.LocalRunLogOpener.current
    val failure = io.github.fartown.movo.ui.screens.diagnostics.rememberRunFailure(message.id)
    val noticeText = stringResource(
        if (message.code == SystemNoticeCode.Interrupted) R.string.system_notice_interrupted else R.string.system_notice_runtime_failed,
    )
    val title = failure?.failure?.title ?: noticeText
    // 网络 / TLS 原始报错换成可操作的说明，原文留在运行日志。
    val detail = io.github.fartown.movo.agent.model.UserFacingFailure.message(
        failure?.failure?.message ?: message.detail?.takeIf(String::isNotBlank),
        stringResource(R.string.movo_failure_network),
    )
    val openRunLog = openLog?.let { open -> { open(failure?.runId ?: io.github.fartown.movo.ui.screens.diagnostics.DiagnosticsLinks.runForMessage(message.id)) } }
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val pillShape = RoundedCornerShape(io.github.fartown.movo.ui.theme.MovoRadius.pillLg)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(pillShape)
                .background(io.github.fartown.movo.ui.theme.MovoColors.bgSurface)
                .border(io.github.fartown.movo.ui.theme.MovoSize.hairline, io.github.fartown.movo.ui.theme.MovoColors.borderHairline, pillShape)
                .then(if (openRunLog != null) Modifier.movoClickableRow(openRunLog) else Modifier)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            io.github.fartown.movo.ui.theme.MovoIcon(io.github.fartown.movo.ui.theme.MovoIcons.X, null, size = 16.dp, tint = io.github.fartown.movo.ui.theme.MovoColors.roseFg)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.movo_run_failed),
                style = io.github.fartown.movo.ui.theme.MovoTypography.labelMedium,
                color = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            failure?.failure?.duration?.let { duration ->
                Text(duration, style = io.github.fartown.movo.ui.theme.MovoTypography.labelRegular, color = io.github.fartown.movo.ui.theme.MovoColors.textTertiary)
                Spacer(Modifier.width(8.dp))
            }
            if (openRunLog != null) {
                io.github.fartown.movo.ui.theme.MovoIcon(io.github.fartown.movo.ui.theme.MovoIcons.ChevronRight, null, size = 16.dp, tint = io.github.fartown.movo.ui.theme.MovoColors.textTertiary)
            }
        }
        val cardShape = RoundedCornerShape(io.github.fartown.movo.ui.theme.MovoRadius.xl)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(io.github.fartown.movo.ui.theme.MovoColors.bgSurface)
                .border(io.github.fartown.movo.ui.theme.MovoSize.hairline, io.github.fartown.movo.ui.theme.MovoColors.borderHairline, cardShape)
                .padding(16.dp),
        ) {
            Text(title, style = io.github.fartown.movo.ui.theme.MovoTypography.bodyStrong, color = io.github.fartown.movo.ui.theme.MovoColors.textPrimary)
            if (detail != null) {
                Text(detail, style = io.github.fartown.movo.ui.theme.MovoTypography.labelRegular, color = io.github.fartown.movo.ui.theme.MovoColors.textSecondary)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                io.github.fartown.movo.ui.components.movo.MovoPillButton(
                    label = stringResource(R.string.movo_run_retry),
                    onClick = onRetry,
                    enabled = actionsEnabled,
                )
                if (openRunLog != null) {
                    Spacer(Modifier.width(8.dp))
                    io.github.fartown.movo.ui.components.movo.MovoPillButton(
                        label = stringResource(R.string.movo_run_view_log),
                        onClick = openRunLog,
                    )
                }
                Spacer(Modifier.weight(1f))
                var showMore by remember(message.id) { mutableStateOf(false) }
                Box {
                    MessageActionButton(
                        icon = io.github.fartown.movo.ui.theme.MovoIcons.Ellipsis,
                        contentDescription = stringResource(R.string.action_more),
                        enabled = actionsEnabled,
                        onClick = { showMore = true },
                    )
                    io.github.fartown.movo.ui.components.movo.MovoPopoverMenu(
                        show = showMore && actionsEnabled,
                        onDismiss = { showMore = false },
                        alignEnd = true,
                        items = listOf(
                            io.github.fartown.movo.ui.components.movo.MovoMenuItem(
                                io.github.fartown.movo.ui.theme.MovoIcons.Copy, stringResource(R.string.movo_copy),
                            ) {
                                @Suppress("DEPRECATION")
                                clipboardManager.setText(AnnotatedString(listOfNotNull(title, detail).joinToString("\n")))
                            },
                            io.github.fartown.movo.ui.components.movo.MovoMenuItem(
                                io.github.fartown.movo.ui.theme.MovoIcons.Trash2, stringResource(R.string.ui_delete_3755f5),
                                destructive = true, onClick = onDelete,
                            ),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun RunLogLink(messageId: String) {
    val open = io.github.fartown.movo.ui.screens.diagnostics.LocalRunLogOpener.current ?: return
    Box(
        modifier = Modifier
            .padding(start = 20.dp, top = 4.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .clickable { open(io.github.fartown.movo.ui.screens.diagnostics.DiagnosticsLinks.runForMessage(messageId)) }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "查看日志", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
    }
}

/**
 * 执行卡 / 执行详情的「第 N 步未完成」：只看**没有被补救**的失败——最后一个工具步骤失败才算。
 * 中途某步失败、随后换了方式成功继续（真机：粘贴文本失败后改用替换文本完成），整张卡按完成显示；
 * 失败的那一步在时间线里仍标 ✕。返回失败步的下标，没有则为 -1。
 */
internal fun unrecoveredFailedStep(tools: List<ToolActivityMessageUi>): Int {
    val last = tools.lastIndex
    return if (last >= 0 && tools[last].status == ToolActivityStatusUi.Failed) last else -1
}
