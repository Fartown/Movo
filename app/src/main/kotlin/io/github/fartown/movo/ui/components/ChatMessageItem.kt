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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import io.github.fartown.movo.ui.components.movo.trackVisibleHeightCap
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
import io.github.fartown.movo.ui.components.movo.movoSurface
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
import kotlinx.coroutines.launch
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
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
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
internal fun WaitingOrb(leaving: Boolean = false) {
    val chatFlight = LocalChatFlight.current
    androidx.compose.runtime.DisposableEffect(chatFlight) { onDispose { chatFlight?.reportWaitingOrb(null) } }
    // 首个事件到达：自己淡出 120ms（Q6「小光球淡出 120ms」），不依赖列表的退场动画（列表退场项会残留在原位）。
    // 离场期间不占高度，新出现的执行卡 / 回答就在它原来的位置出现，两者交叠淡入淡出。
    val fade = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(leaving) {
        if (leaving) {
            chatFlight?.reportWaitingOrb(null)
            fade.animateTo(0f, io.github.fartown.movo.ui.theme.MovoMotion.fastExit())
        }
    }
    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, if (leaving) 0 else placeable.height) { placeable.place(0, 0) }
            }
            .graphicsLayer { alpha = fade.value }
            .padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
    ) {
        io.github.fartown.movo.ui.components.movo.MovoOrb(
            size = 16.dp,
            modifier = Modifier
                .onGloballyPositioned { if (!leaving) chatFlight?.reportWaitingOrb(it.windowRect()) }
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
            // 保存 State，在 graphicsLayer 里读：动画每帧只重画，不重组。
            val alpha = infiniteTransition.animateFloat(
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
                    .graphicsLayer { this.alpha = alpha.value }
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
): androidx.compose.runtime.State<Float> {
    if (!active) return StaticPulse
    val transition = rememberInfiniteTransition(label = label)
    // 返回 State，由调用方在 graphicsLayer 里读取：脉冲每帧只重画，不重组整行。
    return transition.animateFloat(
        initialValue = 0.58f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(820, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "${label}_alpha",
    )
}

private object StaticPulse : androidx.compose.runtime.State<Float> {
    override val value: Float = 1f
}

/**
 * 3.3「模型请求重试中」`Notice/Retry`：执行卡下方一行 `Label/Regular` 次要色（左右 20，与卡间距 12），
 * 前面 16 加载圈、间距 8：「模型请求重试·连接超时，正在重试（第 2 次）」。重试恢复后这一行和失败那轮的半截回答
 * 一起隐藏（见 [arrangeTurnsForTimeline]）；最终没恢复时留在失败卡上方，加载圈换成静态图标、不再写「正在重试」。
 */
@Composable
private fun ModelRetryNotice(
    message: SystemNoticeMessageUi,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val retry = remember(message.detail) { parseModelRetry(message.detail) }
    val text = if (retry == null) {
        stringResource(R.string.system_notice_model_retry)
    } else {
        stringResource(
            if (active) R.string.movo_retry_notice_active else R.string.movo_retry_notice_done,
            stringResource(retry.reasonRes),
            retry.attempt,
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (active) {
                io.github.fartown.movo.ui.components.movo.MovoSpinner(color = io.github.fartown.movo.ui.theme.MovoColors.textSecondary)
            } else {
                io.github.fartown.movo.ui.theme.MovoIcon(
                    io.github.fartown.movo.ui.theme.MovoIcons.RotateCw, null, size = 16.dp,
                    tint = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = io.github.fartown.movo.ui.theme.MovoTypography.labelRegular,
            color = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
        )
    }
}

/**
 * 5.7「已停止」：有执行卡时停止方块在摘要条上（「已停止·已执行 3 步」），下方照常是正文「已停止」；
 * 这一轮还没有执行卡（等首个事件或纯回答时停止）就由这一行自己带次要色停止方块 +「已停止」。
 */
@Composable
private fun StoppedNotice(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        io.github.fartown.movo.ui.theme.MovoIcon(
            io.github.fartown.movo.ui.theme.MovoIcons.Square, null, size = 16.dp,
            tint = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.system_notice_stopped),
            style = io.github.fartown.movo.ui.theme.MovoTypography.labelMedium,
            color = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
        )
    }
}

/** 模型重试提示里能读出的信息：第几次重试与原因（原文见 [io.github.fartown.movo.agent.runtime.AgentEvent.ModelRetryScheduled]）。 */
internal data class ModelRetryInfo(val attempt: Int, @androidx.annotation.StringRes val reasonRes: Int)

private val RETRY_ATTEMPT = Regex("（(\\d+)/\\d+）")
private val RETRY_REASON_CODE = Regex("原因：.*（([^（）]+)）")

/** 从持久化的重试提示原文里取出次数和原因码；旧格式读不出次数时为 null（只显示「模型请求重试」）。 */
internal fun parseModelRetry(detail: String?): ModelRetryInfo? {
    if (detail.isNullOrBlank()) return null
    val attempt = RETRY_ATTEMPT.find(detail)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val code = RETRY_REASON_CODE.find(detail)?.groupValues?.get(1)
    return ModelRetryInfo(attempt, modelRetryReasonRes(code))
}

internal fun modelRetryReasonRes(code: String?): Int = when {
    code == "MODEL_TIMEOUT" -> R.string.movo_retry_reason_timeout
    code == "MODEL_CONNECTION_FAILED" -> R.string.movo_retry_reason_connection
    code == "MODEL_EMPTY_RESPONSE" -> R.string.movo_retry_reason_empty
    code == "STREAM_INCOMPLETE" || code == "MODEL_OUTPUT_INCOMPLETE" -> R.string.movo_retry_reason_incomplete
    code?.contains("429") == true -> R.string.movo_retry_reason_rate_limit
    code?.removePrefix("HTTP_")?.toIntOrNull()?.let { it in 500..599 } == true -> R.string.movo_retry_reason_server
    else -> R.string.movo_retry_reason_other
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
    /** 这一轮有用户原话可以重来：后台监听唤醒的事件轮没有，回答上不显示「重新生成」、失败卡不显示「重试」。 */
    canRegenerate: Boolean = true,
    onSelectReplyCandidate: (String, Int) -> Unit = { _, _ -> },
    /** 模型重试提示：本轮仍在进行（还在重试）。 */
    noticeActive: Boolean = false,
    /** 「已停止」所在这一轮没有执行卡（没有摘要条上的停止方块），提示自己带停止方块。 */
    stoppedWithoutWork: Boolean = false,
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
            canRegenerate = canRegenerate,
            onEdit = { onEditMessage(message.id) },
            onSelectCandidate = { onSelectReplyCandidate(message.id, it) },
            modifier = modifier,
        )
        is SystemNoticeMessageUi -> if (message.code == SystemNoticeCode.ContextCompaction) {
            ContextCompactionMarker(message = message, modifier = modifier)
        } else if (message.code == SystemNoticeCode.ModelRetry) {
            ModelRetryNotice(message = message, active = noticeActive, modifier = modifier)
        } else if (message.code == SystemNoticeCode.Stopped && stoppedWithoutWork) {
            StoppedNotice(modifier = modifier)
        } else if (message.code == SystemNoticeCode.RuntimeFailed || message.code == SystemNoticeCode.Interrupted) {
            RunFailureCard(
                message = message,
                actionsEnabled = messageActionsEnabled,
                onRetry = { onRegenerateMessage(message.id) },
                canRetry = canRegenerate,
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
                canRegenerate = canRegenerate,
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
        is io.github.fartown.movo.ui.model.MonitorEventMessageUi -> MonitorEventRow(message = message, modifier = modifier)
    }
}

/**
 * 工作过程卡（规范 8.1「工作过程」、Figma「02 · 执行任务」05–05g，方案 B）：一次执行的思考与工具调用收束在一张卡里，
 * 完整记录在对话里原地展开，没有底部栏、执行条和执行详情页。
 * 展开：圆角 28、白底 + 发丝描边、无阴影；头部 48（执行中 16 小光球 +「正在执行·第 N 步」Q3 光带 + 收起箭头）
 * → 0.5 分隔线（左右内缩 16）→ 步骤时间线（上下 6）。执行中自动展开，超过 6 步时只显示最近 4 步，上面一行「前面 N 步」；
 * 回答开始（[answerStarted]）或本轮结束时收成 48 高的摘要条一次（✓ +「已完成 N 个步骤」+ 用时 + ⌄），之后不再自动变化；
 * 点摘要条原地展开全部步骤，末尾一行起止时间。暂停时的「结束任务」在输入框上方的提示条里（`Composer/Notice`）。
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
    answerStarted: Boolean = false,
    /** 这一轮的最后一张执行卡：头部严格按「看最后一步」显示（规范 8.8），回答之后也不当作已绕过。 */
    lastCardOfTurn: Boolean = true,
    stepOffset: Int = 0,
    outcome: WorkOutcome? = null,
    turnSpan: WorkTurnSpan? = null,
) {
    // 只有思考、没有执行步骤（纯问答）：不出执行卡，一行「✦ 已思考 N 秒 ⌄」（2026-09-27 定稿方案 2）。
    // 之后出现工具步骤时换成执行卡，思考成为卡里第一步。
    if (messages.isNotEmpty() && messages.all { it is ThinkingMessageUi }) {
        ThinkingOnlyRow(
            id = id,
            messages = messages.filterIsInstance<ThinkingMessageUi>(),
            retainedStreamingStates = retainedStreamingStates,
            modifier = modifier,
        )
        return
    }
    val runUnfinished = outcome?.kind == WorkOutcome.Kind.Unfinished
    val runStopped = outcome?.kind == WorkOutcome.Kind.Stopped
    val turnSteps = outcome?.steps ?: 0
    val runControls = LocalRunControls.current
    val paused = runActive && runControls.isPaused
    val stepRunning = messages.any { message ->
        (message is ThinkingMessageUi && message.isStreaming) ||
            (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running)
    }
    // 执行中 = 有步骤在进行，或本轮仍在进行且未暂停（两步之间模型在思考）；回答开始后这张卡的步骤已经结束。
    val running = !paused && (stepRunning || (runActive && !answerStarted))
    // 计时每秒重组一次卡片：步骤列表只在消息变化时重算。
    val tools = remember(messages) { messages.filterIsInstance<ToolActivityMessageUi>() }
    val toolCount = tools.size
    // 一轮里回答把执行分成几张卡时，中间那张后面接着过渡回答、后面还有步骤：它的失败已被绕过，按完成显示（该步自己仍是 ✕）。
    // 这一轮的最后一张卡照设计「看最后一步」：最后一步失败就是「第 N 步未完成」，后面的回答只是在解释做不了
    // （真机：无障碍关着，各步 ✕，卡片头却显示绿色「已完成」）。
    val failedIndex = if (answerStarted && outcome == null && !lastCardOfTurn) -1 else unrecoveredFailedStep(tools)
    var expanded by rememberSaveable(id) { mutableStateOf(running) }
    var manuallyExpanded by rememberSaveable(id) { mutableStateOf(false) }

    // 执行中自动展开；回答开始时立即收成摘要条，没有回答（失败、停止）时在本轮结束后停留 600ms 再收起
    // （9.4「执行卡 · 完成 → 摘要条」）。只自动收起这一次，之后高度固定；用户手动操作过则不动。
    var autoCollapsed by rememberSaveable(id) { mutableStateOf(false) }
    LaunchedEffect(running, paused, answerStarted) {
        if (manuallyExpanded || autoCollapsed) return@LaunchedEffect
        if (running || paused) {
            expanded = true
        } else if (expanded) {
            if (!answerStarted) kotlinx.coroutines.delay(io.github.fartown.movo.ui.theme.MovoMotion.WORK_CARD_COLLAPSE_DELAY.toLong())
            if (!manuallyExpanded) {
                expanded = false
                autoCollapsed = true
            }
        }
    }
    // 步骤多时（> 6）执行中只显示最近 4 步；点「前面 N 步」或完成后手动展开时显示全部。
    var showAllSteps by rememberSaveable(id) { mutableStateOf(false) }
    // 从摘要条一次铺开几十步（真机 63 步）要 50ms+ 组合与测量：展开第一帧掉帧、直接画出终态，看起来是一帧展开。
    // 先排够一屏的前几步，展开过渡结束后再补上其余步骤（在屏幕外，可见部分不变）。
    var stepLimit by remember(id) { androidx.compose.runtime.mutableIntStateOf(Int.MAX_VALUE) }
    LaunchedEffect(stepLimit) {
        if (stepLimit == Int.MAX_VALUE) return@LaunchedEffect
        kotlinx.coroutines.delay(io.github.fartown.movo.ui.theme.MovoMotion.STANDARD.toLong() + WORK_CARD_RESIZE_SLACK_MS)
        stepLimit = Int.MAX_VALUE
    }
    // 回答开始的这一帧就按收起显示：等上面的副作用下一帧再收，会先把全部步骤展开一下再收（真机跳一下）。
    val shownExpanded = expanded && !(answerStarted && !manuallyExpanded)

    val collapsedSummary = !shownExpanded && !running && !paused
    // 圆角只在绘制阶段读取（graphicsLayer 裁剪 + drawWithContent 画底色与描边）：收成摘要条的圆角过渡期间不重组整张卡。
    val corner = androidx.compose.animation.core.animateDpAsState(
        targetValue = if (collapsedSummary) io.github.fartown.movo.ui.theme.MovoRadius.pillLg else io.github.fartown.movo.ui.theme.MovoRadius.xl,
        animationSpec = io.github.fartown.movo.ui.theme.MovoMotion.standard(),
        label = "workCorner",
    )
    // Q8：本次在屏幕上看着它从执行中变为完成，且任务用时 ≥ 10 秒时播一次。
    var sawRunning by rememberSaveable(id) { mutableStateOf(running) }
    if (running) sawRunning = true
    val runMillis = remember(tools) {
        tools.mapNotNull { it.finishedAtMillis }.maxOrNull()?.let { end ->
            tools.mapNotNull { it.startedAtMillis }.minOrNull()?.let { end - it }
        } ?: 0L
    }
    val glint = sawRunning && !running && !paused && failedIndex < 0 && outcome == null && runMillis >= 10_000L
    val innerResize = remember { WorkCardInnerResize() }
    val stepsCap = io.github.fartown.movo.ui.components.movo.rememberVisibleHeightCap()
    // 从末尾「收起」（2026-09-27 定稿方案 1）：卡片底边钉在原处。收起高度由这里逐帧驱动，每帧同时把列表往回滚同样的距离，
    // 高度与滚动在同一次布局里生效，卡片底边和下面的回答不动。收起完成后保持钳制，直到下次展开。
    val footerCollapse = remember { androidx.compose.animation.core.Animatable(0f) }
    val footerFade = remember { androidx.compose.animation.core.Animatable(1f) }
    var footerCollapsing by remember { mutableStateOf(false) }
    // 钳制时内容的对齐：从末尾「收起」贴底边（手指下的末尾内容原地淡出）；回答开始自动收起贴顶（向头部收拢）。
    var clampAlignBottom by remember { mutableStateOf(true) }
    val stepsFullHeight = remember { intArrayOf(0) }
    val listScroll = LocalChatListScroll.current
    val cardScope = androidx.compose.runtime.rememberCoroutineScope()
    val reducedMotion = io.github.fartown.movo.ui.theme.LocalReducedMotion.current
    fun collapseFromFooter() {
        if (footerCollapsing) return
        manuallyExpanded = true
        val full = stepsFullHeight[0].toFloat()
        if (listScroll == null || reducedMotion || full <= 0f) {
            expanded = false
            return
        }
        footerCollapsing = true
        clampAlignBottom = true
        cardScope.launch {
            // 屏幕顶以上的那段先一次收掉（看不见），可见的部分再按 `standard` 收：与展开按可见高度封顶同一做法。
            val start = minOf(full, (stepsCap.bottomPx ?: full.toInt()).toFloat().coerceAtLeast(0f))
            footerCollapse.snapTo(start)
            listScroll(-(full - start))
            launch { footerFade.animateTo(0f, io.github.fartown.movo.ui.theme.MovoMotion.fastExit()) }
            var previous = start
            footerCollapse.animateTo(
                0f,
                tween(io.github.fartown.movo.ui.theme.MovoMotion.STANDARD, easing = io.github.fartown.movo.ui.theme.MovoMotion.EasingStandard),
            ) {
                listScroll(-(previous - value))
                previous = value
            }
            expanded = false
        }
    }
    fun resetFooterCollapse() {
        if (!footerCollapsing) return
        footerCollapsing = false
        clampAlignBottom = true
        cardScope.launch {
            footerCollapse.snapTo(0f)
            footerFade.snapTo(1f)
        }
    }
    // 回答开始、本轮执行卡自动收起（8.1「回答开始后收成一行」）：同样由这里逐帧驱动高度（内容贴顶、从下往上收），
    // 每帧收起多少就在列表末尾补多少留白（[ChatBottomReserve]）。列表停在底部时内容总高不变，
    // 不会先退回露出上一轮、再随回答往下滚（真机 turntime case4）。
    val bottomReserve = LocalChatBottomReserve.current
    val lastShownExpanded = remember { booleanArrayOf(shownExpanded) }
    val autoCollapseStarting = lastShownExpanded[0] && !shownExpanded && answerStarted && !manuallyExpanded &&
        runActive && bottomReserve != null && !reducedMotion && !footerCollapsing
    var autoCollapsing by remember { mutableStateOf(false) }
    SideEffect {
        if (autoCollapseStarting) autoCollapsing = true
        lastShownExpanded[0] = shownExpanded
    }
    LaunchedEffect(autoCollapsing) {
        if (!autoCollapsing || bottomReserve == null) return@LaunchedEffect
        val full = stepsFullHeight[0].toFloat()
        if (full <= 0f) {
            autoCollapsing = false
            return@LaunchedEffect
        }
        // 可见区以外那段先一次收掉（看不见），留白同步补上；可见的部分再按 `standard` 收。
        val start = minOf(full, stepsCap.remainingPx().toFloat())
        clampAlignBottom = false
        footerCollapse.snapTo(start)
        footerFade.snapTo(1f)
        footerCollapsing = true
        bottomReserve.px += (full - start).toInt()
        launch { footerFade.animateTo(0f, io.github.fartown.movo.ui.theme.MovoMotion.fastExit()) }
        var previous = start
        footerCollapse.animateTo(
            0f,
            tween(io.github.fartown.movo.ui.theme.MovoMotion.STANDARD, easing = io.github.fartown.movo.ui.theme.MovoMotion.EasingStandard),
        ) {
            val shrunk = (previous - value).toInt()
            if (shrunk > 0) {
                bottomReserve.px += shrunk
                previous -= shrunk
            }
        }
        autoCollapsing = false
    }
    // 执行中卡片高度只增不减（多出的留白在卡片下方、列表末尾）：较早步骤收进「前面 N 步」时新步骤往往还很矮，
    // 卡片先变矮、列表退回，等新步骤长出来又滚回去（真机 stepfold2：先下移约 65px 再回来）。留白由新内容先填上；
    // 执行结束时把剩下的留白原样交给列表末尾留白（[ChatBottomReserve]），由回答接着填，不在结束那一下退回。
    val ratchetOn = (running || paused) && bottomReserve != null && !reducedMotion
    val heightFloor = remember { intArrayOf(0, 0) } // [0] 最高高度，[1] 上一帧内容高度
    SideEffect {
        if (!ratchetOn && heightFloor[0] > 0) {
            val extra = heightFloor[0] - heightFloor[1]
            if (extra > 0) bottomReserve?.let { it.px += extra }
            heightFloor[0] = 0
        }
    }
    Column(
        modifier = modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                heightFloor[1] = placeable.height
                val height = if (ratchetOn) {
                    maxOf(placeable.height, heightFloor[0]).also { heightFloor[0] = it }
                } else {
                    placeable.height
                }
                layout(placeable.width, height) { placeable.place(0, 0) }
            }
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .completionGlint(glint) { corner.value }
            .workCardSurface { corner.value }
            // 只在执行中（步骤不断增加）时让卡片高度跟着过渡；收起 / 展开由下面的 AnimatedVisibility 直接驱动高度，
            // 两个一起用时外层过渡总慢半拍，收起后下面拖着一段空白（真机）。
            // 卡里有一层自己在做高度过渡（点开步骤结果、思考步骤展开、执行中手动收起卡片）时，外层不再叠一层过渡：
            // 直接跟着里层每帧的高度走（snap），整张卡只有一层高度动画，也不会每帧两层都重新测量、外层落后半拍。
            .then(
                if (running || paused) {
                    Modifier.animateContentSize(
                        if (innerResize.active) androidx.compose.animation.core.snap() else io.github.fartown.movo.ui.theme.MovoMotion.standard(),
                    )
                } else {
                    Modifier
                },
            ),
    ) {
    androidx.compose.runtime.CompositionLocalProvider(LocalWorkCardInnerResize provides innerResize) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .movoClickableRow {
                    // 摘要条整条可点，原地展开完整记录（⌄ / ⌃）；执行中点头部收起 / 展开。
                    manuallyExpanded = true
                    resetFooterCollapse()
                    if (collapsedSummary) {
                        showAllSteps = true
                        if (messages.size > WORK_FIRST_BATCH_STEPS) stepLimit = WORK_FIRST_BATCH_STEPS
                    }
                    expanded = !shownExpanded
                }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 规范 9.4「执行卡 · 完成 → 摘要条」：小光球 → ✓ 等图标交叉淡化 + 缩放 0.72 ↔ 1（`fast`）。
            WorkStatusIcon(
                kind = when {
                    paused -> WorkStatusIconKind.Paused
                    running -> WorkStatusIconKind.Running
                    // 用户主动停止不是出错：次要色停止方块。
                    runStopped -> WorkStatusIconKind.Stopped
                    failedIndex >= 0 || runUnfinished -> WorkStatusIconKind.Failed
                    else -> WorkStatusIconKind.Done
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            // 计时：执行中「00:18」每秒直接换数字（9.0 规则 5，不滚动不闪）；结束后「用时 18 秒」。
            // 失败 / 停止的一轮可能被回答分成几张卡，用时与步数一样按整轮算。
            // 按整轮任务计时（与运行日志一致）；旧数据没有整轮时刻时按步骤时间。
            val firstStart = turnSpan?.startedAt ?: outcome?.startedAt ?: tools.mapNotNull { it.startedAtMillis }.minOrNull()
            val lastFinish = turnSpan?.finishedAt ?: outcome?.finishedAt ?: tools.mapNotNull { it.finishedAtMillis }.maxOrNull()
            // 步骤做完、回答还在输出：这一轮还没结束，「用时」继续走，结束时停在整轮用时，不先停住再跳（真机 turntime：26 秒 → 31 秒）。
            val answering = !running && !paused && runActive && turnSpan != null && turnSpan.finishedAt == null
            val ticking = running || answering
            val now by androidx.compose.runtime.produceState(System.currentTimeMillis(), ticking, firstStart) {
                while (ticking && firstStart != null) {
                    value = System.currentTimeMillis()
                    kotlinx.coroutines.delay(1_000)
                }
                value = System.currentTimeMillis()
            }
            // 看着它跑完的：记下它停止执行的时刻（回答开始或本轮结束），结束后的用时按这个算，与执行中的计时接得上
            // （最后一步之后模型还在想，按最后一步结束算会往回跳）。按卡片 key 存，滚出屏幕再回来不丢；
            // 重进会话后没有这个时刻，按最后一步结束算。
            var endedAt by rememberSaveable(id) { androidx.compose.runtime.mutableLongStateOf(0L) }
            if (sawRunning && !running && !paused && endedAt == 0L) {
                SideEffect { if (endedAt == 0L) endedAt = System.currentTimeMillis() }
            }
            val elapsed = firstStart?.let { start ->
                if (ticking) now - start else (turnSpan?.finishedAt ?: endedAt.takeIf { it > 0L } ?: lastFinish)?.let { it - start }
            }
            val timerText = elapsed?.let { if (running) formatClock(it) else formatElapsed(it) }
            // 放不下完整计时时退成「1:06」，状态文字不让位（规范：摘要条状态优先完整显示）。
            val compactTimer = elapsed?.takeIf { !running }?.let(::formatCompactElapsed)
            val phase = when {
                paused -> WorkPhase.Paused
                running -> WorkPhase.Running
                runStopped -> WorkPhase.Stopped
                failedIndex >= 0 -> WorkPhase.Failed
                runUnfinished -> WorkPhase.Unfinished
                else -> WorkPhase.Done
            }
            // 「第 N 步」按整轮计（回答把一轮分成几张卡时接着前面的数），与悬浮球展开卡一致。
            val phaseText: @Composable (WorkPhase) -> String = { shownPhase ->
                when (shownPhase) {
                    WorkPhase.Paused -> if (toolCount > 0) stringResource(R.string.movo_work_paused_step, stepOffset + toolCount) else stringResource(R.string.movo_work_paused)
                    WorkPhase.Running -> if (toolCount > 0) stringResource(R.string.movo_work_running_step, stepOffset + toolCount) else stringResource(R.string.movo_work_analyzing)
                    WorkPhase.Stopped -> stringResource(R.string.movo_work_stopped_steps, turnSteps)
                    WorkPhase.Failed -> stringResource(R.string.movo_work_failed_step, failedIndex + 1)
                    WorkPhase.Unfinished -> stringResource(R.string.movo_work_unfinished_steps, turnSteps)
                    WorkPhase.Done -> if (toolCount > 0) stringResource(R.string.movo_work_done_steps, toolCount) else stringResource(R.string.movo_work_done)
                }
            }
            val targetPhaseText = phaseText(phase)
            StatusWithTimer(
                // 计时写法按将要显示的状态文字决定：交叉淡化时新旧两段同时在测量里，按较宽的算会让计时
                // 在切换那一下退成「0:41」再换回「用时 41 秒」（真机 reserve）。
                statusForWidth = {
                    Text(targetPhaseText, style = io.github.fartown.movo.ui.theme.MovoTypography.labelMedium, maxLines = 1, softWrap = false)
                },
                status = {
                    // 状态切换（执行中 → 已完成等）交叉淡化 `fast`；同一状态里的「第 N 步」直接换数字，不做过渡。
                    WorkPhaseCrossfade(phase) { shownPhase ->
                        io.github.fartown.movo.ui.components.movo.MovoShimmerText(
                            text = phaseText(shownPhase),
                            style = io.github.fartown.movo.ui.theme.MovoTypography.labelMedium,
                            color = if (shownPhase == WorkPhase.Running || shownPhase == WorkPhase.Paused) {
                                io.github.fartown.movo.ui.theme.MovoColors.textPrimary
                            } else {
                                io.github.fartown.movo.ui.theme.MovoColors.textSecondary
                            },
                            active = shownPhase == WorkPhase.Running,
                        )
                    }
                },
                timer = timerText,
                compactTimer = compactTimer,
                timerKind = running,
                timerColor = if (running) io.github.fartown.movo.ui.theme.MovoColors.textSecondary else io.github.fartown.movo.ui.theme.MovoColors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            val rotation = androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (shownExpanded) 180f else 0f,
                animationSpec = io.github.fartown.movo.ui.theme.MovoMotion.fast(),
                label = "workChevron",
            )
            // 展开 / 收起：箭头 ⌄ ↔ ⌃ 旋转（`fast`）。
            io.github.fartown.movo.ui.theme.MovoIcon(
                io.github.fartown.movo.ui.theme.MovoIcons.ChevronDown,
                contentDescription = stringResource(if (shownExpanded) R.string.movo_collapse else R.string.movo_expand),
                size = 16.dp,
                tint = io.github.fartown.movo.ui.theme.MovoColors.textTertiary,
                modifier = Modifier.graphicsLayer { rotationZ = rotation.value },
            )
        }

        // 展开：高度 `standard`，内容与高度同时开始淡入 `fast`（不等待，第一帧就有内容）（规范 9.3「展开 / 收起」）。
        AnimatedVisibility(
            visible = shownExpanded || autoCollapsing || autoCollapseStarting,
            modifier = Modifier.trackVisibleHeightCap(stepsCap),
            enter = fadeIn(
                tween(
                    io.github.fartown.movo.ui.theme.MovoMotion.FAST,
                    easing = io.github.fartown.movo.ui.theme.MovoMotion.EasingStandard,
                ),
            ) + expandVertically(io.github.fartown.movo.ui.components.movo.rememberViewportCappedStandard(stepsCap), expandFrom = Alignment.Top),
            // 收起：内容淡出与高度收起同时进行、同样时长，卡片不会先空成一块白再缩（真机 fix12）。
            // 全部步骤可能有几屏高：高度只按可见区以内的部分过渡（否则第一帧就越过可见区域，看起来是一帧展开）。
            exit = fadeOut(tween(io.github.fartown.movo.ui.theme.MovoMotion.STANDARD, easing = io.github.fartown.movo.ui.theme.MovoMotion.EasingExit)) +
                shrinkVertically(io.github.fartown.movo.ui.components.movo.rememberViewportCappedStandard(stepsCap), shrinkTowards = Alignment.Top),
        ) {
            ReportWorkCardInnerResize()
            Column(
                modifier = Modifier
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        stepsFullHeight[0] = placeable.height
                        val height = if (footerCollapsing) {
                            footerCollapse.value.toInt().coerceIn(0, placeable.height)
                        } else {
                            placeable.height
                        }
                        // 从末尾收起时内容贴着底边：手指下的末尾内容原地淡出，只有卡片顶边往下收；
                        // 顶对齐时，屏幕外那段一次收掉的那一帧，可见内容会从末尾换成开头（真机 footer2）。
                        val y = if (footerCollapsing && clampAlignBottom) height - placeable.height else 0
                        layout(placeable.width, height) { placeable.place(0, y) }
                    }
                    .graphicsLayer { alpha = if (footerCollapsing) footerFade.value else 1f },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(io.github.fartown.movo.ui.theme.MovoSize.hairline)
                        .background(io.github.fartown.movo.ui.theme.MovoColors.borderHairline),
                )
                // 不看是否执行中：自动收起的过程中仍保持折叠，不在收起前把全部步骤铺开。
                val folded = !showAllSteps && messages.size > WORK_FOLD_THRESHOLD
                // 「前面 N 步」第一次出现（步骤刚超过阈值）时从顶部展开并淡入，与被收进去的行同时进行；
                // 直接插在顶部会把下面的步骤一帧往下推一行（真机 stepfold3：106px）。
                val hidden = messages.dropLast(WORK_FOLD_VISIBLE)
                // 「前面 N 步」只数被折叠的工具步骤，与头部「第 N 步」同一口径（思考不算一步）。
                val hiddenCount = hidden.count { it is ToolActivityMessageUi }.takeIf { it > 0 } ?: hidden.size
                val lastHiddenCount = remember { intArrayOf(hiddenCount) }
                if (folded) lastHiddenCount[0] = hiddenCount
                AnimatedVisibility(
                    visible = folded,
                    enter = fadeIn(io.github.fartown.movo.ui.theme.MovoMotion.fast()) +
                        expandVertically(io.github.fartown.movo.ui.theme.MovoMotion.standard(), expandFrom = Alignment.Top),
                    exit = fadeOut(io.github.fartown.movo.ui.theme.MovoMotion.fastExit()) +
                        shrinkVertically(io.github.fartown.movo.ui.theme.MovoMotion.standard(), shrinkTowards = Alignment.Top),
                ) {
                    WorkEarlierSteps(
                        count = if (folded) hiddenCount else lastHiddenCount[0],
                        onClick = { showAllSteps = true },
                    )
                }
                val stepsTopPadding by androidx.compose.animation.core.animateDpAsState(
                    targetValue = if (folded) 0.dp else 6.dp,
                    animationSpec = io.github.fartown.movo.ui.theme.MovoMotion.standard(),
                    label = "workStepsTop",
                )
                WorkSteps(
                    messages = (if (folded) messages.takeLast(WORK_FOLD_VISIBLE) else messages).take(stepLimit),
                    running = running,
                    onOpenBrowser = onOpenBrowser,
                    currentBrowserMessageId = currentBrowserMessageId,
                    retainedStreamingStates = retainedStreamingStates,
                    actionsEnabled = actionsEnabled,
                    onEditMessage = onEditMessage,
                    onDeleteMessage = onDeleteMessage,
                    modifier = Modifier.padding(top = stepsTopPadding, bottom = 6.dp),
                )
                // 结束后点开：末尾一行，左侧起止时间「15:02 开始·15:03 结束」（Figma「05e」），右侧「日志」「收起 ⌃」
                // （2026-09-27 定稿方案 1）：长记录滑到末尾不用回到头部就能收起；执行中不出这一行（头部即可收起）。
                if (!running && !paused) {
                    WorkCardFooter(
                        span = workTimeSpan(tools, outcome, turnSpan),
                        logMessageId = tools.firstOrNull()?.id ?: messages.firstOrNull()?.id,
                        onCollapse = ::collapseFromFooter,
                    )
                }
            }
        }
    }
    }
}


/** 执行中步骤超过这个数时折叠较早的步骤（规范 8.1「工作过程 · 展开」）。 */
private const val WORK_FOLD_THRESHOLD = 6

/** 折叠时保留的最近步骤数。 */
private const val WORK_FOLD_VISIBLE = 4

/** 时间线最上面一行「前面 N 步 ⌄」：`Label/Medium` 次要色，文字对齐步骤标题（左 44），上下 8，整行可点展开。 */
/**
 * 执行卡展开后的末尾一行（规范 8.1「工作过程 · 收起」，2026-09-27 定稿）：左侧起止时间 `Label/Regular` 次要色；
 * 右侧两个文字按钮「日志」「收起 ⌃」，13 Regular 次要色、无底色，按压文字变淡（Link）。
 * 「日志」打开这次任务的运行日志任务详情（与失败卡「查看日志」同一页）；没有日志入口的宿主不显示。
 */
@Composable
private fun WorkCardFooter(
    span: WorkTimeSpan?,
    logMessageId: String?,
    onCollapse: () -> Unit,
) {
    val openLog = io.github.fartown.movo.ui.screens.diagnostics.LocalRunLogOpener.current
    // 这次任务的日志已不在（运行日志只保留最近的任务）：左侧时间原地换成「找不到这次任务的日志」，停留后换回（就地反馈，不用 Toast）。
    var logMissingAt by remember { mutableStateOf(0L) }
    LaunchedEffect(logMissingAt) {
        if (logMissingAt == 0L) return@LaunchedEffect
        kotlinx.coroutines.delay(io.github.fartown.movo.ui.theme.MovoMotion.SUPPLEMENT_ACK_HOLD.toLong())
        logMissingAt = 0L
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.animation.Crossfade(
            targetState = logMissingAt != 0L,
            animationSpec = io.github.fartown.movo.ui.theme.MovoMotion.fast(),
            modifier = Modifier.weight(1f),
            label = "workFooterNotice",
        ) { missing ->
        if (missing) {
            Text(
                text = stringResource(R.string.movo_work_log_missing),
                style = io.github.fartown.movo.ui.theme.MovoTypography.labelRegular,
                color = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        } else
        // 起止时间完整放得下用完整写法，放不下（大字号、窄屏）退成「15:02–15:03」，不截成省略号。
        androidx.compose.ui.layout.Layout(
            content = {
                val style = io.github.fartown.movo.ui.theme.MovoTypography.labelRegular
                val color = io.github.fartown.movo.ui.theme.MovoColors.textSecondary
                Text(span?.full.orEmpty(), style = style, color = color, maxLines = 1, softWrap = false)
                Text(span?.compact.orEmpty(), style = style, color = color, maxLines = 1, softWrap = false)
            },
            modifier = Modifier.fillMaxWidth().clipToBounds(),
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0)
            val full = measurables[0]
            val chosen = if (full.maxIntrinsicWidth(constraints.maxHeight) <= constraints.maxWidth) full else measurables[1]
            val placeable = chosen.measure(loose)
            layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
        }
        }
        if (openLog != null && logMessageId != null) {
            WorkCardFooterAction(label = stringResource(R.string.movo_work_log)) {
                val runId = io.github.fartown.movo.ui.screens.diagnostics.DiagnosticsLinks.runForMessage(logMessageId)
                if (runId != null) openLog(runId) else logMissingAt = System.currentTimeMillis()
            }
            Spacer(Modifier.width(4.dp))
        }
        WorkCardFooterAction(label = stringResource(R.string.movo_collapse), showChevron = true, onClick = onCollapse)
    }
}

@Composable
private fun WorkCardFooterAction(label: String, showChevron: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(io.github.fartown.movo.ui.theme.MovoSize.touchTarget)
            .movoClickable(io.github.fartown.movo.ui.components.movo.PressKind.Link, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = io.github.fartown.movo.ui.theme.MovoTypography.labelRegular,
            color = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
        )
        if (showChevron) {
            Spacer(Modifier.width(2.dp))
            io.github.fartown.movo.ui.theme.MovoIcon(
                io.github.fartown.movo.ui.theme.MovoIcons.ChevronDown,
                contentDescription = null,
                size = 14.dp,
                tint = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                modifier = Modifier.graphicsLayer { rotationZ = 180f },
            )
        }
    }
}

@Composable
private fun WorkEarlierSteps(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .movoClickableRow(onClick)
            .padding(start = 44.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.movo_work_earlier_steps, count),
            style = io.github.fartown.movo.ui.theme.MovoTypography.labelMedium,
            color = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
        )
        Spacer(Modifier.width(4.dp))
        io.github.fartown.movo.ui.theme.MovoIcon(
            io.github.fartown.movo.ui.theme.MovoIcons.ChevronDown,
            contentDescription = null,
            size = 16.dp,
            tint = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
        )
    }
}

/** 起止时间：完整「15:02 开始·15:03 结束」，放不下时用紧凑「15:02–15:03」。 */
private class WorkTimeSpan(val full: String, val compact: String)

/** 本轮起止时间（本地时区 HH:mm）；拿不到开始或结束时间时为 null。 */
@Composable
private fun workTimeSpan(tools: List<ToolActivityMessageUi>, outcome: WorkOutcome?, turnSpan: WorkTurnSpan?): WorkTimeSpan? {
    val start = turnSpan?.startedAt ?: outcome?.startedAt ?: tools.mapNotNull { it.startedAtMillis }.minOrNull() ?: return null
    val end = turnSpan?.finishedAt ?: outcome?.finishedAt ?: tools.mapNotNull { it.finishedAtMillis }.maxOrNull() ?: return null
    val format = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    val from = format.format(java.util.Date(start))
    val to = format.format(java.util.Date(end))
    return WorkTimeSpan(stringResource(R.string.movo_run_detail_span, from, to), "$from–$to")
}

/**
 * 思考的状态图标（规范 8.1，Figma「14-0」，2026-09-27 定稿方案 3）：思考中是小光球（与执行卡「正在执行」同一个
 * 「Movo 在工作」信号，渐变转动 + 呼吸），思考完交叉淡化成次要色 sparkle（9.3.1 图标切换）。
 */
@Composable
private fun ThinkingStatusIcon(streaming: Boolean, size: androidx.compose.ui.unit.Dp) {
    val reduced = io.github.fartown.movo.ui.theme.LocalReducedMotion.current
    AnimatedContent(
        targetState = streaming,
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
        transitionSpec = { movoIconSwap(reduced) },
        label = "thinkingStatusIcon",
    ) { thinking ->
        if (thinking) {
            io.github.fartown.movo.ui.components.movo.MovoOrb(size = size)
        } else {
            io.github.fartown.movo.ui.theme.MovoIcon(
                io.github.fartown.movo.ui.theme.MovoIcons.Sparkle, null, size = size,
                tint = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
            )
        }
    }
}

/**
 * 「思考中」后面的实时秒数（方案 3）：从这段思考开始计，每秒 +1，数字直接换（9.0 规则 5）；`Label/Regular` 三级色。
 * 开始时刻按 [key] 存，滚出屏幕再回来不重新计。模型两段输出之间停顿时，数字仍在走，看得出没有卡住。
 */
@Composable
private fun ThinkingLiveSeconds(key: String) {
    val startedAt by rememberSaveable(key) { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    val seconds by androidx.compose.runtime.produceState(0L, startedAt) {
        while (true) {
            value = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
            kotlinx.coroutines.delay(1_000L - (System.currentTimeMillis() - startedAt) % 1_000L)
        }
    }
    Spacer(Modifier.width(6.dp))
    Text(
        text = stringResource(R.string.movo_thinking_live_seconds, seconds.toInt()),
        style = io.github.fartown.movo.ui.theme.MovoTypography.labelRegular,
        color = io.github.fartown.movo.ui.theme.MovoColors.textTertiary,
        maxLines = 1,
    )
}

/**
 * 思考中的滚动预览（规范 8.1，Figma「14-0」）：高度固定两行（`Label/Regular`，行高与思考正文同为 20 × 2），显示最新写出的内容：
 * 文字按底部对齐排版、超出的往上推出视口，上沿 14 渐隐；新字只会让文字上移，高度不变，不随段落切换跳动。
 */
@Composable
private fun ThinkingTicker(content: String, modifier: Modifier = Modifier) {
    // 与思考正文同一行高（13 / 20）：思考结束换成全文前两行时高度不变（C3）。
    val style = io.github.fartown.movo.ui.theme.MovoTypography.labelRegular.copy(lineHeight = THINKING_BODY_LINE_HEIGHT)
    // 只排最后一段文字：预览只露两行，全文重排没有意义。
    val tail = remember(content) { content.takeLast(THINKING_TICKER_CHARS).plainPreview() }
    val fadePx = with(androidx.compose.ui.platform.LocalDensity.current) { 14.dp.toPx() }
    Box(
        modifier = modifier
            .height(with(androidx.compose.ui.platform.LocalDensity.current) { (style.lineHeight * 2).toDp() })
            .clipToBounds()
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to androidx.compose.ui.graphics.Color.Transparent,
                        (fadePx / size.height).coerceIn(0f, 1f) to androidx.compose.ui.graphics.Color.Black,
                    ),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            },
    ) {
        Text(
            text = tail,
            style = style,
            color = io.github.fartown.movo.ui.theme.MovoColors.textTertiary,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .wrapContentHeight(align = Alignment.Bottom, unbounded = true),
        )
    }
}

private const val THINKING_TICKER_CHARS = 240

/**
 * 只有思考的一轮（规范 8.1「思考 · 行内」，Figma「14」）：没有卡片与描边，左对齐 20 的一行 sparkle 14 次要色 +
 *「思考中」（Q3 光带）/「已思考 N 秒」`Label/Medium` 次要色 + ⌄，高 32；点开在下面展开思考内容：左侧 1 宽
 * `border/strong` 竖线，文字三级色（思考正文字号）。默认收起。
 */
@Composable
private fun ThinkingOnlyRow(
    id: String,
    messages: List<ThinkingMessageUi>,
    retainedStreamingStates: Map<String, StreamingMarkdownState>,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val streaming = messages.any { it.isStreaming }
    val seconds = messages.sumOf { it.elapsedSeconds ?: 0 }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .height(32.dp)
                .movoClickable(io.github.fartown.movo.ui.components.movo.PressKind.Link) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThinkingStatusIcon(streaming = streaming, size = 14.dp)
            Spacer(Modifier.width(6.dp))
            io.github.fartown.movo.ui.components.movo.MovoShimmerText(
                text = when {
                    streaming -> stringResource(R.string.movo_thinking_in_progress)
                    seconds > 0 -> stringResource(R.string.movo_thought_seconds, seconds)
                    else -> stringResource(R.string.movo_thought)
                },
                style = io.github.fartown.movo.ui.theme.MovoTypography.labelMedium,
                color = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                active = streaming,
            )
            if (streaming) ThinkingLiveSeconds(key = id)
            Spacer(Modifier.width(4.dp))
            val rotation = androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = io.github.fartown.movo.ui.theme.MovoMotion.fast(),
                label = "thinkingOnlyChevron",
            )
            io.github.fartown.movo.ui.theme.MovoIcon(
                io.github.fartown.movo.ui.theme.MovoIcons.ChevronDown,
                contentDescription = stringResource(if (expanded) R.string.movo_collapse else R.string.movo_expand),
                size = 14.dp,
                tint = io.github.fartown.movo.ui.theme.MovoColors.textTertiary,
                modifier = Modifier.graphicsLayer { rotationZ = rotation.value },
            )
        }
        // 思考中（没点开）：行下方固定两行的滚动预览，左缩进 20 与文字对齐；思考结束后收起一次，只剩这一行。
        val latest = messages.lastOrNull { it.content.isNotBlank() }?.content
        val tickerCap = io.github.fartown.movo.ui.components.movo.rememberVisibleHeightCap()
        AnimatedVisibility(
            visible = streaming && !expanded && latest != null,
            modifier = Modifier.trackVisibleHeightCap(tickerCap),
            enter = expandContentEnter(tickerCap),
            exit = expandContentExit(tickerCap),
        ) {
            ThinkingTicker(latest.orEmpty(), modifier = Modifier.fillMaxWidth().padding(start = 20.dp, bottom = 4.dp))
        }
        // 思考正文在折叠时就开始解析：展开过渡开始时高度已是最终值。展开时才解析的话，Markdown 在过渡途中解析完、
        // 高度改变，过渡被打断改用默认弹簧，按全高直冲出可见区（真机 verify4：3 帧就把回答推出屏幕）。
        val parsedBodies = messages.filter { it.content.isNotBlank() && !it.isStreaming }.associate { message ->
            message.id to androidx.compose.runtime.key(message.id) {
                rememberMarkdownState(
                    content = io.github.fartown.movo.ui.markdown.CjkEmphasis.normalize(message.content),
                    retainState = true,
                )
            }
        }
        val bodyCap = io.github.fartown.movo.ui.components.movo.rememberVisibleHeightCap()
        AnimatedVisibility(
            visible = expanded,
            modifier = Modifier.trackVisibleHeightCap(bodyCap),
            enter = expandContentEnter(bodyCap),
            exit = expandContentExit(bodyCap),
        ) {
            // 左侧竖线画在绘制阶段：不用固有高度测量（展开动画与流式思考时每帧都要对 Markdown 做一次固有测量）。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, top = 4.dp, bottom = 4.dp)
                    .drawBehind {
                        drawLine(
                            color = io.github.fartown.movo.ui.theme.MovoColors.borderStrong,
                            start = Offset(0.5.dp.toPx(), 0f),
                            end = Offset(0.5.dp.toPx(), size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    .padding(start = 13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                run {
                    messages.filter { it.content.isNotBlank() }.forEach { message ->
                        val streamingState = retainedStreamingStates[message.id]
                        if (message.isStreaming && streamingState != null) {
                            StreamingMarkdown(
                                state = streamingState,
                                content = message.content,
                                isStreaming = true,
                                onRevealCompleteChange = {},
                                tone = ChatMarkdownTone.Thinking,
                            )
                        } else {
                            StableMarkdown(content = message.content, tone = ChatMarkdownTone.Thinking, markdownState = parsedBodies[message.id])
                        }
                    }
                }
            }
        }
    }
}

/** 执行卡 / 执行条 / 执行详情概要卡的状态图标（规范 8.1、8.8）。 */
internal enum class WorkStatusIconKind { Running, Paused, Stopped, Failed, Done }

/**
 * 状态图标槽 16：执行中 = 小光球（Q6）/ 暂停 = 次要色 ‖ / 停止 = 次要色方块 / 失败 = Rose ✕ / 完成 = Green ✓。
 * 切换时交叉淡化 + 缩放 0.72 ↔ 1，`fast`（规范 9.2「交叉淡化」、9.4「执行卡 · 完成 → 摘要条」）；减少动画时只淡入淡出。
 */
@Composable
internal fun WorkStatusIcon(kind: WorkStatusIconKind, modifier: Modifier = Modifier) {
    val reduced = io.github.fartown.movo.ui.theme.LocalReducedMotion.current
    AnimatedContent(
        targetState = kind,
        modifier = modifier.size(16.dp),
        contentAlignment = Alignment.Center,
        transitionSpec = { movoIconSwap(reduced) },
        label = "workStatusIcon",
    ) { shown ->
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            when (shown) {
                WorkStatusIconKind.Running -> io.github.fartown.movo.ui.components.movo.MovoOrb(size = 16.dp)
                WorkStatusIconKind.Paused -> io.github.fartown.movo.ui.theme.MovoIcon(
                    io.github.fartown.movo.ui.theme.MovoIcons.Pause, null, size = 16.dp, tint = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                )
                WorkStatusIconKind.Stopped -> io.github.fartown.movo.ui.theme.MovoIcon(
                    io.github.fartown.movo.ui.theme.MovoIcons.Square, null, size = 16.dp, tint = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                )
                WorkStatusIconKind.Failed -> io.github.fartown.movo.ui.theme.MovoIcon(
                    io.github.fartown.movo.ui.theme.MovoIcons.X, null, size = 16.dp, tint = io.github.fartown.movo.ui.theme.MovoColors.roseFg,
                )
                WorkStatusIconKind.Done -> io.github.fartown.movo.ui.theme.MovoIcon(
                    io.github.fartown.movo.ui.theme.MovoIcons.Check, null, size = 16.dp, tint = io.github.fartown.movo.ui.theme.MovoColors.greenFg,
                )
            }
        }
    }
}

/** 图标状态切换（规范 9.3.1）：交叉淡化 + 缩放 0.72 ↔ 1，`fast`；减少动画时只淡入淡出（9.8）。 */
internal fun <S> androidx.compose.animation.AnimatedContentTransitionScope<S>.movoIconSwap(
    reduced: Boolean,
): androidx.compose.animation.ContentTransform {
    val fast = io.github.fartown.movo.ui.theme.MovoMotion.fast<Float>()
    val fastExit = io.github.fartown.movo.ui.theme.MovoMotion.fastExit<Float>()
    return if (reduced) {
        fadeIn(fast).togetherWith(fadeOut(fastExit)).using(null)
    } else {
        (fadeIn(fast) + scaleIn(fast, initialScale = 0.72f))
            .togetherWith(fadeOut(fastExit) + scaleOut(fastExit, targetScale = 0.72f))
            .using(null)
    }
}

/** 执行卡标题行的状态（不含「第 N 步」这类计数：计数变化直接换数字）。 */
internal enum class WorkPhase { Running, Paused, Stopped, Failed, Unfinished, Done }

/**
 * 状态文字交叉淡化（规范 9.3「值变化」、9.4）：新状态淡入 `fast`、旧状态淡出 120ms，宽度变化 `standard`。
 * 只在 [phase] 变化时过渡；同一状态里文字（步数）变化直接换。
 */
@Composable
internal fun WorkPhaseCrossfade(
    phase: WorkPhase,
    modifier: Modifier = Modifier,
    content: @Composable (WorkPhase) -> Unit,
) {
    AnimatedContent(
        targetState = phase,
        modifier = modifier,
        contentAlignment = Alignment.CenterStart,
        transitionSpec = {
            fadeIn(io.github.fartown.movo.ui.theme.MovoMotion.fast())
                .togetherWith(fadeOut(io.github.fartown.movo.ui.theme.MovoMotion.fastExit()))
                .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> io.github.fartown.movo.ui.theme.MovoMotion.standard() })
        },
        label = "workPhase",
    ) { shown -> content(shown) }
}

/**
 * 执行卡外壳：白底 + 0.5 发丝描边，按 [corner] 圆角裁剪。圆角只在绘制阶段读取（graphicsLayer 的 shape、
 * drawWithContent），圆角动画期间不重组、不重建裁剪与描边的修饰符链。
 */
private fun Modifier.workCardSurface(corner: () -> androidx.compose.ui.unit.Dp): Modifier = this
    .graphicsLayer {
        shape = RoundedCornerShape(corner())
        clip = true
    }
    .drawWithContent {
        val radius = corner().toPx()
        drawRoundRect(
            color = io.github.fartown.movo.ui.theme.MovoColors.bgSurface,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
        )
        drawContent()
        // 与 Modifier.border 一致：描边画在内容之上，线宽的一半向内。
        val stroke = io.github.fartown.movo.ui.theme.MovoSize.hairline.toPx()
        val inset = stroke / 2
        drawRoundRect(
            color = io.github.fartown.movo.ui.theme.MovoColors.borderHairline,
            topLeft = Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius((radius - inset).coerceAtLeast(0f)),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
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
    // 步骤增删（执行中较早的步骤收进「前面 N 步」、新步骤出现）：离场的行先淡出再收起高度，下面的行跟着平滑上移，
    // 不一帧跳（真机 reserve5：一帧 304px）；执行中新增的行高度从顶部展开（淡入上移 6 仍由 MovoEntrance 做）。
    // 变化期间卡片外框直接跟随里层高度（[WorkCardInnerResize]），不叠两层高度动画。
    val innerResize = LocalWorkCardInnerResize.current
    val stepIds = remember(messages) { messages.map { it.id } }
    val lastStepIds = remember { arrayOf(stepIds) }
    LaunchedEffect(stepIds) {
        val changed = lastStepIds[0] != stepIds
        lastStepIds[0] = stepIds
        if (changed && running) {
            innerResize?.holdFor(
                (io.github.fartown.movo.ui.theme.MovoMotion.FAST_EXIT + io.github.fartown.movo.ui.theme.MovoMotion.STANDARD).toLong() +
                    WORK_CARD_RESIZE_SLACK_MS,
            )
        }
    }
    Column(modifier = modifier) {
        io.github.fartown.movo.ui.components.movo.MovoAnimatedRows(
            items = messages,
            key = { it.id },
            animateEnter = running,
            enter = expandVertically(io.github.fartown.movo.ui.theme.MovoMotion.standard(), expandFrom = Alignment.Top),
            // 较早的步骤收进「前面 N 步」时，底部往往同时出现新步骤：离场行的收起与新行的展开同时开始、同一曲线，
            // 卡片总高基本不变，列表不会先被新行顶上去、再因旧行收起退回来（真机 stepfold：先上移约 120px 再回落）。
            exit = fadeOut(io.github.fartown.movo.ui.theme.MovoMotion.fastExit()) +
                shrinkVertically(io.github.fartown.movo.ui.theme.MovoMotion.standard(), shrinkTowards = Alignment.Top),
        ) { message ->
            val index = messages.indexOfFirst { it.id == message.id }
            val playEntrance = remember { running && message.id !in seenSteps }            // 正在离场的行（已不在列表里）下面仍有行。
            val hasNext = index < 0 || index < messages.lastIndex
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

/** 整行可点的按压反馈（列表行：只叠加、不缩放，规范 9.3.1）。 */
private fun Modifier.movoClickableRow(onClick: () -> Unit): Modifier =
    movoClickable(io.github.fartown.movo.ui.components.movo.PressKind.Row, onClick = onClick)

/**
 * 摘要条的状态 + 右侧计时（含到箭头的间距）：状态文字优先完整显示；剩余宽度放得下完整计时（「用时 1 分 6 秒」）
 * 就放，放不下退成 [compactTimer]（「1:06」，两侧间距缩到 4，大字号窄屏也放得下），再放不下就不显示计时。
 */
@Composable
internal fun StatusWithTimer(
    status: @Composable () -> Unit,
    timer: String?,
    compactTimer: String?,
    timerColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    statusForWidth: (@Composable () -> Unit)? = null,
    timerKind: Any? = null,
) {
    val timerStyle = io.github.fartown.movo.ui.theme.MovoTypography.numericLabel
    // 计时写法换了（执行中「00:51」→ 结束「用时 51 秒」）时与状态文字一起交叉淡化；同一写法里数字变化直接换。
    val timerText: @Composable (String) -> Unit = { text ->
        androidx.compose.animation.AnimatedContent(
            targetState = timerKind to text,
            contentKey = { it.first },
            transitionSpec = {
                // 宽度一次到位、只做淡变：计时靠右摆放，宽度过渡会让文字往左漂（真机 reserve3）。
                fadeIn(io.github.fartown.movo.ui.theme.MovoMotion.fast())
                    .togetherWith(fadeOut(io.github.fartown.movo.ui.theme.MovoMotion.fastExit()))
                    .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.snap() })
            },
            contentAlignment = Alignment.CenterEnd,
            label = "workTimer",
        ) { (_, shown) ->
            Text(shown, style = timerStyle, color = timerColor, maxLines = 1, softWrap = false)
        }
    }
    androidx.compose.ui.layout.Layout(
        contents = listOf(
            status,
            { if (timer != null) timerText(timer) },
            { if (compactTimer != null) timerText(compactTimer) },
            { if (statusForWidth != null) statusForWidth() },
        ),
        modifier = modifier,
    ) { (statusMeasurables, fullMeasurables, compactMeasurables, widthMeasurables), constraints ->
        val gap = 8.dp.roundToPx()
        val tightGap = 4.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0)
        val full = fullMeasurables.firstOrNull()?.measure(loose.copy(maxWidth = androidx.compose.ui.unit.Constraints.Infinity))
        val compact = compactMeasurables.firstOrNull()?.measure(loose.copy(maxWidth = androidx.compose.ui.unit.Constraints.Infinity))
        val statusMeasurable = statusMeasurables.first()
        val statusWanted = (widthMeasurables.firstOrNull() ?: statusMeasurable)
            .maxIntrinsicWidth(constraints.maxHeight).coerceAtMost(constraints.maxWidth)
        val room = constraints.maxWidth - statusWanted
        // 选中的计时与它两侧的间距（状态 → 计时、计时 → 箭头）。
        val (chosen, sideGap) = when {
            full != null && full.width + gap * 2 <= room -> full to gap
            compact != null && compact.width + tightGap * 2 <= room -> compact to tightGap
            else -> null to gap
        }
        val trailing = if (chosen != null) chosen.width + sideGap * 2 else gap
        val statusPlaceable = statusMeasurable.measure(loose.copy(maxWidth = (constraints.maxWidth - trailing).coerceAtLeast(0)))
        val height = maxOf(statusPlaceable.height, chosen?.height ?: 0, constraints.minHeight)
        layout(constraints.maxWidth, height) {
            statusPlaceable.placeRelative(0, (height - statusPlaceable.height) / 2)
            chosen?.placeRelative(constraints.maxWidth - sideGap - chosen.width, (height - chosen.height) / 2)
        }
    }
}

/** 执行中计时：mm:ss（等宽数字）。 */
private fun formatClock(elapsedMillis: Long): String {
    val seconds = (elapsedMillis / 1000).coerceAtLeast(0)
    return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

/** 摘要条放不下完整用时时的紧凑写法：「1:13」「0:09」（分钟不补零）。 */
private fun formatCompactElapsed(elapsedMillis: Long): String {
    val seconds = ((elapsedMillis + 500) / 1000).coerceAtLeast(1)
    return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
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
    // 长按弹出 `Popover/Menu`（规范 8「弹出菜单」、9.3.1「长按」）：复制 / 编辑 / 删除；被按的气泡保持按压态直到菜单关闭。
    var showMenu by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(actionsEnabled) {
        if (!actionsEnabled) showMenu = false
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
        Box {
            // `Message/User`（规范 8.1）：右对齐到 392，最大宽 296；bg/surface + 0.5 描边；圆角 20、右下 8；内边距 16 / 11。
            val bubbleShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 8.dp, bottomStart = 20.dp)
            // Q1：刚发出的这条由飞行层从输入框飞到位，落地前自己先隐藏。
            val chatFlight = LocalChatFlight.current
            val menuShown = showMenu && actionsEnabled
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
                    .pressedWhile(menuShown)
                    .longPressForMenu(enabled = actionsEnabled) { showMenu = true }
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
                    val requestText: @Composable () -> Unit = {
                        Text(
                            text = visiblePrompt.request,
                            style = io.github.fartown.movo.ui.theme.MovoTypography.bodyReading,
                            color = io.github.fartown.movo.ui.theme.MovoColors.textPrimary,
                        )
                    }
                    // 长按只出一个菜单：菜单可用时长按归菜单（菜单里有「复制」），不再同时进系统选字、弹系统工具栏；
                    // 执行中菜单不可用，长按仍可选字复制。
                    if (actionsEnabled) requestText() else SelectionContainer { requestText() }
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
            io.github.fartown.movo.ui.components.movo.MovoPopoverMenu(
                show = menuShown,
                onDismiss = { showMenu = false },
                alignEnd = true,
                items = messageMenuItems(copyText = message.content, onEdit = onEdit, onDelete = onDelete),
            )
        }
    }
}

/**
 * 用户消息 / 「你的补充」的长按菜单项：复制（点后原地换成 ✓ 再关闭，不弹提示，规范 9.3「复制」）、编辑、删除（Rose）。
 */
@Composable
private fun messageMenuItems(
    copyText: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
): List<io.github.fartown.movo.ui.components.movo.MovoMenuItem> {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    return listOf(
        io.github.fartown.movo.ui.components.movo.MovoMenuItem(
            icon = io.github.fartown.movo.ui.theme.MovoIcons.Copy,
            label = stringResource(R.string.ui_copy_4edd1d),
            confirmIcon = io.github.fartown.movo.ui.theme.MovoIcons.Check,
        ) {
            @Suppress("DEPRECATION")
            clipboardManager.setText(AnnotatedString(copyText))
        },
        io.github.fartown.movo.ui.components.movo.MovoMenuItem(
            icon = io.github.fartown.movo.ui.theme.MovoIcons.PenLine,
            label = stringResource(R.string.ui_edit_a7f814),
            onClick = onEdit,
        ),
        io.github.fartown.movo.ui.components.movo.MovoMenuItem(
            icon = io.github.fartown.movo.ui.theme.MovoIcons.Trash2,
            label = stringResource(R.string.ui_delete_3755f5),
            destructive = true,
            onClick = onDelete,
        ),
    )
}

/** 菜单打开期间保持按压态（`overlay/pressed` 叠在内容上，规范 9.3.1「长按」）。 */
private fun Modifier.pressedWhile(pressed: Boolean): Modifier = drawWithContent {
    drawContent()
    if (pressed) drawRect(io.github.fartown.movo.ui.theme.MovoColors.overlayPressed)
}

/**
 * 长按 300ms 触发（规范 9.1「长按」、9.3.1）：长按触感后回调。在 Initial 阶段观察、不消费按下与点击，
 * 气泡里的文字选择、列表滚动照常；移动超过触摸阈值即取消。触发后吃掉这次按住余下的事件，松手不再触发别的手势。
 */
private fun Modifier.longPressForMenu(enabled: Boolean, onLongPress: () -> Unit): Modifier = composed {
    if (!enabled) return@composed Modifier
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            val ended = withTimeoutOrNull(io.github.fartown.movo.ui.theme.MovoMotion.LONG_PRESS.toLong()) {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed || change.isConsumed) break
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
                true
            }
            if (ended == null) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                currentOnLongPress()
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) break
                }
            }
        }
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
    val pulse = rememberActivePulse(active = message.running, label = "compaction_pulse")
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
                    .graphicsLayer { alpha = if (message.running) pulse.value else 1f },
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
    canRegenerate: Boolean = true,
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
            kotlinx.coroutines.delay(io.github.fartown.movo.ui.theme.MovoMotion.COPIED_HOLD.toLong())
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 7.dp),
    ) {
        val typing = message.content.isBlank() && message.isStreaming
        // 从「…」等待指示换成正文时，正文整体淡入 `fast`（规范 9.3「小元素淡入」），不硬切。
        val startedTyping = remember(message.id) { typing }
        val body: @Composable () -> Unit = { when {
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
        } }
        when {
            typing -> AITypingIndicator(modifier = Modifier.padding(top = 4.dp))
            startedTyping -> io.github.fartown.movo.ui.components.movo.MovoEntrance(
                play = true,
                shift = 0.dp,
                durationMillis = io.github.fartown.movo.ui.theme.MovoMotion.FAST,
            ) { body() }
            else -> body()
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
                    if (canRegenerate) {
                        MessageActionButton(
                            icon = io.github.fartown.movo.ui.theme.MovoIcons.RotateCcw,
                            contentDescription = stringResource(R.string.ui_regenerate_reply_84a7d9),
                            enabled = messageActionsEnabled,
                            onClick = onRegenerate,
                        )
                    }
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
                        // 候选切换：视觉 28 不变，点击区扩到 44（规范 2.3）；底色不裁剪子项，扩出的点击区才收得到触摸。
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(MiuixTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(percent = 50))
                                .padding(horizontal = 3.dp, vertical = 2.dp),
                        ) {
                            MessageActionButton(
                                icon = io.github.fartown.movo.ui.theme.MovoIcons.ChevronLeft,
                                contentDescription = "上一条候选回复",
                                onClick = { onSelectCandidate(message.selectedCandidate - 1) },
                                enabled = messageActionsEnabled && message.selectedCandidate > 0,
                                visualSize = 28.dp,
                            )
                            Text(
                                text = "${message.selectedCandidate + 1}/${message.candidateCount}",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(min = 30.dp),
                            )
                            MessageActionButton(
                                icon = io.github.fartown.movo.ui.theme.MovoIcons.ChevronRight,
                                contentDescription = "下一条候选回复",
                                onClick = { onSelectCandidate(message.selectedCandidate + 1) },
                                enabled = messageActionsEnabled && message.selectedCandidate < message.candidateCount - 1,
                                visualSize = 28.dp,
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

/**
 * 消息操作按钮：视觉 [visualSize]（消息操作行 32），图标 16；点击区扩到 44（规范 2.3 最小触控热区），不改变布局与间距。
 * 复制 ↔ ✓ 交叉淡化并缩放 0.72 ↔ 1（9.3.1「图标状态切换」）。
 */
@Composable
private fun MessageActionButton(
    icon: io.github.fartown.movo.ui.theme.MovoIconData,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
    visualSize: androidx.compose.ui.unit.Dp = 32.dp,
) {
    val reduced = io.github.fartown.movo.ui.theme.LocalReducedMotion.current
    Box(
        modifier = Modifier
            .expandedTouchTarget(visualSize)
            .movoClickable(io.github.fartown.movo.ui.components.movo.PressKind.Icon, enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.AnimatedContent(
            targetState = icon to tint,
            transitionSpec = { movoIconSwap(reduced) },
            label = "messageAction",
        ) { (current, currentTint) ->
            io.github.fartown.movo.ui.theme.MovoIcon(current, null, size = 16.dp, tint = currentTint)
        }
    }
}

/**
 * 视觉小于 44 的控件：布局只占 [visual]（间距、对齐不变），可点区域以它为中心扩到 44（规范 2.3）。
 * 之后的修饰符（按压反馈、点击、语义）都作用在 44 的区域上；按压圆 40 仍以控件为中心。
 */
private fun Modifier.expandedTouchTarget(visual: androidx.compose.ui.unit.Dp): Modifier =
    this.layout { measurable, _ ->
        val target = io.github.fartown.movo.ui.theme.MovoSize.touchTarget.roundToPx()
        val visualPx = visual.roundToPx()
        val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(target, target))
        layout(visualPx, visualPx) {
            placeable.place((visualPx - target) / 2, (visualPx - target) / 2)
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
        content = io.github.fartown.movo.ui.markdown.CjkEmphasis.normalize(content),
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
    // 标题不另设字号（规范 5 字体表）：回答里 h1–h2 用 `Title/Section`，h3 及以下用正文加 Medium；
    // 思考内容（Label 13）里各级标题都用思考正文加 Medium，不比正文大。
    h1 = chatMarkdownHeadingStyle(tone, level = 1),
    h2 = chatMarkdownHeadingStyle(tone, level = 2),
    h3 = chatMarkdownHeadingStyle(tone, level = 3),
    h4 = chatMarkdownHeadingStyle(tone, level = 4),
    h5 = chatMarkdownHeadingStyle(tone, level = 5),
    h6 = chatMarkdownHeadingStyle(tone, level = 6),
    text = chatMarkdownBodyStyle(tone),
    paragraph = chatMarkdownBodyStyle(tone),
    ordered = chatMarkdownBodyStyle(tone),
    bullet = chatMarkdownBodyStyle(tone),
    list = chatMarkdownBodyStyle(tone),
    quote = MiuixTheme.textStyles.body2.copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 15.sp else 13.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 24.sp else 20.sp,
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
private fun chatMarkdownHeadingStyle(tone: ChatMarkdownTone, level: Int): TextStyle =
    if (tone == ChatMarkdownTone.Answer && level <= 2) {
        io.github.fartown.movo.ui.theme.MovoTypography.titleSection.copy(color = chatMarkdownTextColor(tone))
    } else {
        chatMarkdownBodyStyle(tone).copy(fontWeight = FontWeight.Medium)
    }

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
        // 思考正文三级色（规范 8.1「思考」；折叠态两行与展开后全文同一份排版，C3）。
        io.github.fartown.movo.ui.theme.MovoColors.textTertiary
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
    codeBackgroundCornerSize = io.github.fartown.movo.ui.theme.MovoRadius.sm,
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
 * 代码块（按规范 8.8「命令块」）：bg/surface-muted、圆角 12、内边距 12；上方一行语言标签（Label/Regular 次要色）+
 * 右上角复制（Lucide 16，热区 44，复制后原地交叉淡化为 ✓ 停留 1400ms，规范 9.3「复制」）；正文等宽 13，超出横向滚动。
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
            kotlinx.coroutines.delay(io.github.fartown.movo.ui.theme.MovoMotion.COPIED_HOLD.toLong())
            copied = false
        }
    }
    val shape = RoundedCornerShape(io.github.fartown.movo.ui.theme.MovoRadius.sm)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(io.github.fartown.movo.ui.theme.MovoColors.bgSurfaceMuted, shape)
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language?.takeIf { it.isNotBlank() } ?: "code",
                style = io.github.fartown.movo.ui.theme.MovoTypography.labelRegular,
                color = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            // 视觉只占图标 16（字形右缘对齐内边距 12），点击区 44。
            MessageActionButton(
                icon = if (copied) io.github.fartown.movo.ui.theme.MovoIcons.Check else io.github.fartown.movo.ui.theme.MovoIcons.Copy,
                contentDescription = stringResource(if (copied) R.string.copy_copied else R.string.copy_code),
                tint = if (copied) io.github.fartown.movo.ui.theme.MovoColors.greenFg else io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                visualSize = 16.dp,
                onClick = {
                    @Suppress("DEPRECATION")
                    clipboardManager.setText(AnnotatedString(code))
                    copied = true
                },
            )
        }
        val codeModifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .horizontalScroll(rememberScrollState())
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
            color = io.github.fartown.movo.ui.theme.MovoColors.textPrimary,
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

    // 表格（审查 D2）：`movoSurface`（白底 + 0.5 发丝描边）、圆角 12；表头 Medium，行间 0.5 发丝分隔线。
    val borderColor = io.github.fartown.movo.ui.theme.MovoColors.borderHairline
    val tableShape = RoundedCornerShape(io.github.fartown.movo.ui.theme.MovoRadius.sm)
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
                .movoSurface(shape = tableShape),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                        .height(io.github.fartown.movo.ui.theme.MovoSize.hairline)
                        .background(borderColor),
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
        color = io.github.fartown.movo.ui.theme.MovoColors.textPrimary,
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
            content = io.github.fartown.movo.ui.markdown.CjkEmphasis.normalize(message.content),
            retainState = true,
        )
    } else {
        null
    }

    if (compact) {
        // 执行卡里的思考（3.2）：默认只显示两行摘要（思考中也是），点这一步才展开全文。
        var stepExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
        WorkThinkingStep(
            message = message,
            expanded = stepExpanded,
            onToggle = { stepExpanded = !stepExpanded },
            streamingState = streamingState,
            completedMarkdownState = completedMarkdownState,
            stableMarkdownState = stableMarkdownState,
            modifier = modifier,
        )
        return
    }

    val pulse = rememberActivePulse(
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
                    .graphicsLayer { alpha = if (message.isStreaming) pulse.value else 1f },
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
            // 展开 / 收起：箭头向右 → 向下旋转 90°，`fast`（规范 9.3，不用换图标代替旋转）。
            val chevronRotation = androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (expanded) 90f else 0f,
                animationSpec = io.github.fartown.movo.ui.theme.MovoMotion.fast(),
                label = "thinkingChevron",
            )
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = stringResource(
                    if (expanded) R.string.reasoning_collapse else R.string.reasoning_expand,
                ),
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = chevronRotation.value },
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
            )
        }

        val contentCap = io.github.fartown.movo.ui.components.movo.rememberVisibleHeightCap()
        AnimatedVisibility(
            visible = expanded && message.content.isNotBlank(),
            modifier = Modifier.trackVisibleHeightCap(contentCap),
            enter = expandContentEnter(contentCap),
            exit = expandContentExit(contentCap),
        ) {
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

    // 执行卡里的工具步骤（compact）不用脉冲，只在旧样式的独立工具行里持有。
    val pulse = rememberActivePulse(
        active = message.status == ToolActivityStatusUi.Running,
        label = "tool_pulse",
    )

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
            io.github.fartown.movo.ui.theme.MovoIcon(
                toolIcon(message.toolName),
                contentDescription = null,
                size = 15.dp,
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
                        // Rose 只用在左侧失败图标上，原因文字用次要色（规范 4.2 规则 2）。
                        color = if (failureSubtitle != null) {
                            io.github.fartown.movo.ui.theme.MovoColors.textSecondary
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
                            modifier = Modifier.graphicsLayer {
                                alpha = if (status == ToolActivityStatusUi.Running) pulse.value else 1f
                            },
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
                val chevronRotation = androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (isExpanded) 90f else 0f,
                    animationSpec = io.github.fartown.movo.ui.theme.MovoMotion.fast(),
                    label = "toolChevron",
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier
                        .size(13.dp)
                        .graphicsLayer { rotationZ = chevronRotation.value },
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                )
            }
        }

        val resultCap = io.github.fartown.movo.ui.components.movo.rememberVisibleHeightCap()
        AnimatedVisibility(
            visible = isExpanded,
            modifier = Modifier.trackVisibleHeightCap(resultCap),
            enter = expandContentEnter(resultCap),
            exit = expandContentExit(resultCap),
        ) {
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
                        // 主操作：浅 Indigo 底 + 深 Indigo 字（规范 4.2 规则 1、8「行内按钮」），不用饱和实底白字。
                        io.github.fartown.movo.ui.components.movo.MovoPillButton(
                            label = stringResource(R.string.ui_open_current_browser_58358e),
                            onClick = onOpenBrowser,
                            primary = true,
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
    var showMenu by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(actionsEnabled) { if (!actionsEnabled) showMenu = false }
    val menuShown = showMenu && actionsEnabled
    val text = remember(message.content) { AgentFileReferencePromptCodec.parse(message.content).request }
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pressedWhile(menuShown)
                .longPressForMenu(enabled = actionsEnabled) { showMenu = true }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
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
        io.github.fartown.movo.ui.components.movo.MovoPopoverMenu(
            show = menuShown,
            onDismiss = { showMenu = false },
            items = messageMenuItems(copyText = text, onEdit = onEdit, onDelete = onDelete),
        )
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
            ThinkingStatusIcon(streaming = message.isStreaming, size = 16.dp)
            Spacer(Modifier.width(12.dp))
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                io.github.fartown.movo.ui.components.movo.MovoShimmerText(
                    text = when {
                        message.isStreaming -> stringResource(R.string.movo_thinking_in_progress)
                        (message.elapsedSeconds ?: 0) > 0 -> stringResource(R.string.movo_thinking_seconds, message.elapsedSeconds ?: 0)
                        else -> stringResource(R.string.movo_thinking_done)
                    },
                    style = io.github.fartown.movo.ui.theme.MovoTypography.labelMedium,
                    color = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                    active = message.isStreaming,
                )
                if (message.isStreaming) ThinkingLiveSeconds(key = message.id)
            }
        }
        if (message.content.isNotBlank()) {
            // 思考中且没点开：固定两行高的滚动预览（最新写出的内容，2026-09-27 定）。
            // 其余情况（思考完、或思考中点开）用同一段思考正文，只裁切可见高度（C3，见 [ThinkingFoldableBody]）；
            // 思考结束时预览换成全文前两行一次（交叉淡化 `fast`，两者同为两行高、同字号，不跳）。
            val tickerMode = message.isStreaming && !expanded
            var tickerShown by remember(message.id) { mutableStateOf(false) }
            if (tickerMode) tickerShown = true
            val contentModifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 2.dp)
            androidx.compose.animation.Crossfade(
                targetState = tickerMode,
                animationSpec = io.github.fartown.movo.ui.theme.MovoMotion.fast(),
                label = "thinkingStepMode",
            ) { ticker ->
                if (ticker) {
                    ThinkingTicker(message.content, modifier = contentModifier)
                } else {
                    ThinkingFoldableBody(
                        expanded = expanded,
                        startCollapsed = tickerShown,
                        modifier = contentModifier,
                    ) {
                        if (streamingState != null && (message.isStreaming || completedMarkdownState == null)) {
                            StreamingMarkdown(
                                state = streamingState,
                                content = message.content,
                                isStreaming = message.isStreaming,
                                onRevealCompleteChange = {},
                                tone = ChatMarkdownTone.Thinking,
                            )
                        } else {
                            StableMarkdown(
                                content = message.content,
                                tone = ChatMarkdownTone.Thinking,
                                markdownState = stableMarkdownState,
                                parsedState = completedMarkdownState,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 思考正文的折叠 / 展开（Figma 候选「动效全集」C3，2026-09-27 定）：折叠态显示的就是同一段正文的前两行，
 * 第二行底部渐隐（不用省略号）；展开 / 收起只改变可见高度——裁切区平滑长高 / 收回 `standard`，
 * 底部渐隐同步淡出 / 淡入。文字始终是同一份排版，不替换、不交叉淡化，所以不会叠影。
 *
 * 正文按不限高度只测量一次，动画期间每帧只在本节点的排版阶段改可见高度，子项不重新测量；
 * 高度变化期间让执行卡外层跟随（[LocalWorkCardInnerResize]），整张卡只有这一层高度动画。
 * [startCollapsed]：从滚动预览切过来时（思考中点开、思考结束）从两行高开始过渡，而不是直接到位。
 */
@Composable
private fun ThinkingFoldableBody(
    expanded: Boolean,
    startCollapsed: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduced = io.github.fartown.movo.ui.theme.LocalReducedMotion.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val collapsedPx = with(density) { (THINKING_BODY_LINE_HEIGHT * 2).roundToPx() }
    val fadePx = with(density) { THINKING_BODY_LINE_HEIGHT.toPx() }
    // 长思考可能超过可见区：只把可见区以内的部分按进度过渡，超出的那段在可见区外一次到位（同 [rememberViewportCappedStandard]）。
    val visibleCap = io.github.fartown.movo.ui.components.movo.rememberVisibleHeightCap()
    val target = if (expanded) 1f else 0f
    val progress = remember { androidx.compose.animation.core.Animatable(if (startCollapsed) 0f else target) }
    val resize = LocalWorkCardInnerResize.current
    val firstRun = remember { booleanArrayOf(true) }
    LaunchedEffect(target) {
        val initial = firstRun[0]
        firstRun[0] = false
        if (progress.value == target) return@LaunchedEffect
        if (reduced) {
            progress.snapTo(target)
            return@LaunchedEffect
        }
        if (!initial || startCollapsed) {
            kotlinx.coroutines.coroutineScope {
                launch { resize?.holdFor(io.github.fartown.movo.ui.theme.MovoMotion.STANDARD.toLong() + WORK_CARD_RESIZE_SLACK_MS) }
                progress.animateTo(target, io.github.fartown.movo.ui.theme.MovoMotion.standard())
            }
        } else {
            progress.snapTo(target)
        }
    }
    // 正文是否超出两行：只有超出时才画底部渐隐（在排版阶段写入，绘制阶段读取）。
    val overflows = remember { booleanArrayOf(false) }
    Box(
        modifier = modifier
            .trackVisibleHeightCap(visibleCap)
            // 裁切与底部渐隐必须在改高度的 layout 外层：外层节点的尺寸才是「可见高度」，
            // 放在里层时它们拿到的是正文全高，文字会溢出压到下面的步骤上（真机 verify-dda65f5）。
            .clipToBounds()
            // 底部渐隐（DstIn）在自己开的图层里做：不依赖外层图层的合成方式。原来按「是否溢出」切换离屏合成，
            // 而溢出是在排版阶段写的普通变量，图层没刷新时 DstIn 直接画到窗口上，成了一条黑色渐变（真机 verify3）。
            .drawWithContent {
                val fade = 1f - progress.value
                if (fade <= 0f || !overflows[0]) {
                    drawContent()
                    return@drawWithContent
                }
                drawContext.canvas.saveLayer(
                    androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height),
                    androidx.compose.ui.graphics.Paint(),
                )
                drawContent()
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to androidx.compose.ui.graphics.Color.Black,
                        1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 1f - fade),
                        startY = size.height - fadePx,
                        endY = size.height,
                    ),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
                drawContext.canvas.restore()
            }
            // 正文按不限高度只测量一次，按进度报出可见高度（动画中子项不重新测量）。
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
                val collapsed = minOf(collapsedPx, placeable.height)
                overflows[0] = placeable.height > collapsedPx
                val value = progress.value
                val height = if (value >= 1f) {
                    placeable.height
                } else {
                    collapsed + ((minOf(placeable.height, visibleCap.remainingPx()) - collapsed).coerceAtLeast(0) * value).toInt()
                }
                layout(placeable.width, height) { placeable.place(0, 0) }
            },
    ) {
        content()
    }
}

/** 从摘要条展开时第一批排版的步骤数：够铺满一屏，其余等展开过渡结束后补上。 */
private const val WORK_FIRST_BATCH_STEPS = 12

/** 思考正文行高（[chatMarkdownBodyStyle] Thinking：13 / 20）；折叠态露出两行。 */
private val THINKING_BODY_LINE_HEIGHT = 20.sp

/**
 * 展开（规范 9.3「展开 / 收起」）：高度 `standard`，内容与高度同时开始淡入 `fast`（不等待，第一帧就有内容）。
 * 思考、步骤结果可能长过可见区：高度只按可见区以内的部分过渡（[rememberViewportCappedStandard]，
 * 调用方在 AnimatedVisibility 上挂 `trackVisibleHeightCap(cap)`）。
 */
@Composable
internal fun expandContentEnter(
    cap: io.github.fartown.movo.ui.components.movo.VisibleHeightCap,
): androidx.compose.animation.EnterTransition = fadeIn(
    tween(
        io.github.fartown.movo.ui.theme.MovoMotion.FAST,
        easing = io.github.fartown.movo.ui.theme.MovoMotion.EasingStandard,
    ),
) + expandVertically(io.github.fartown.movo.ui.components.movo.rememberViewportCappedStandard(cap), expandFrom = Alignment.Top)

/** 收起：内容先淡出 120ms，高度同时收起 `standard`（同样只按可见区以内的部分过渡）。 */
@Composable
internal fun expandContentExit(
    cap: io.github.fartown.movo.ui.components.movo.VisibleHeightCap,
): androidx.compose.animation.ExitTransition =
    fadeOut(io.github.fartown.movo.ui.theme.MovoMotion.fastExit()) +
        shrinkVertically(io.github.fartown.movo.ui.components.movo.rememberViewportCappedStandard(cap), shrinkTowards = Alignment.Top)

/**
 * 执行卡里正在做高度过渡的里层数量（步骤结果展开、思考步骤展开 / 收起、执行中手动收起卡片）。
 * 大于 0 时卡片外层的高度过渡改为直接跟随，避免两层高度动画叠在一起（见 [AgentWorkProcess]）。
 */
@androidx.compose.runtime.Stable
internal class WorkCardInnerResize {
    private var count by androidx.compose.runtime.mutableIntStateOf(0)
    val active: Boolean get() = count > 0

    suspend fun holdFor(millis: Long) {
        count++
        try {
            kotlinx.coroutines.delay(millis)
        } finally {
            count--
        }
    }
}

internal val LocalWorkCardInnerResize = androidx.compose.runtime.staticCompositionLocalOf<WorkCardInnerResize?> { null }

/**
 * 放在执行卡里层 AnimatedVisibility / AnimatedContent 的内容开头：本层的进出目标一变，就在一次高度过渡
 * （`standard`，再多留两帧）期间让卡片外层跟随，不再自己过渡。卡片外（历史消息、思考行）不起作用。
 */
@Composable
private fun androidx.compose.animation.AnimatedVisibilityScope.ReportWorkCardInnerResize() {
    val resize = LocalWorkCardInnerResize.current ?: return
    val target = transition.targetState
    LaunchedEffect(target) { resize.holdFor(io.github.fartown.movo.ui.theme.MovoMotion.STANDARD.toLong() + WORK_CARD_RESIZE_SLACK_MS) }
}

private const val WORK_CARD_RESIZE_SLACK_MS = 32L

/** 思考摘要：去掉常见 Markdown 标记后折叠空白。 */
private fun String.plainPreview(): String =
    replace(MARKDOWN_MARKS, "").replace(WHITESPACE_RUNS, " ").trim()

private val MARKDOWN_MARKS = Regex("[*_`#>]+")
private val WHITESPACE_RUNS = Regex("\\s+")

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
        // 展开：高度 `standard`，内容与高度同时开始淡入 `fast`（不等待，第一帧就有内容）（规范 9.3「展开 / 收起」）。
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(
                tween(
                    io.github.fartown.movo.ui.theme.MovoMotion.FAST,
                    easing = io.github.fartown.movo.ui.theme.MovoMotion.EasingStandard,
                ),
            ) + expandVertically(io.github.fartown.movo.ui.theme.MovoMotion.standard(), expandFrom = Alignment.Top),
            exit = fadeOut(io.github.fartown.movo.ui.theme.MovoMotion.fastExit()) +
                shrinkVertically(io.github.fartown.movo.ui.theme.MovoMotion.standard(), shrinkTowards = Alignment.Top),
        ) {
            ReportWorkCardInnerResize()
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(snapshot.url, snapshot.isLoading, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            AgentBrowserSession.keepActive().use {
                while (true) {
                    val image = withContext(Dispatchers.IO) {
                        AgentBrowserSession.capturePreview()?.let { decodeDataUrlBitmap(it.dataUrl) }
                    }
                    if (image != null) preview = image
                    delay(if (snapshot.isLoading) 1_200L else 4_000L)
                }
            }
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
            kotlinx.coroutines.delay(io.github.fartown.movo.ui.theme.MovoMotion.COPIED_HOLD.toLong())
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
            // 右上角复制（规范 8.8「命令块」）：Lucide 16、热区 44，复制后原地交叉淡化为 ✓（9.3「复制」）。
            MessageActionButton(
                icon = if (copied) io.github.fartown.movo.ui.theme.MovoIcons.Check else io.github.fartown.movo.ui.theme.MovoIcons.Copy,
                contentDescription = stringResource(if (copied) R.string.copy_copied else R.string.copy_command),
                tint = if (copied) io.github.fartown.movo.ui.theme.MovoColors.greenFg else io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
                visualSize = 28.dp,
                onClick = {
                    @Suppress("DEPRECATION")
                    clipboardManager.setText(AnnotatedString(command))
                    copied = true
                },
            )
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
                io.github.fartown.movo.ui.theme.MovoIcon(
                    toolIcon(tool),
                    contentDescription = null,
                    size = 12.dp,
                    tint = MiuixTheme.colorScheme.primary,
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
                SuggestionChip(prompt) { onSuggestionClick(prompt) }
            }
        }
    }
}

/** 推荐追问 `Chip/Suggestion`（规范 8.1）：高 32、圆角 16、bg/surface + 0.5 描边；sparkle 14 Indigo + Label/Medium，左 12 / 右 14、间距 6。 */
@Composable
internal fun SuggestionChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(shape)
            .background(io.github.fartown.movo.ui.theme.MovoColors.bgSurface)
            .border(0.5.dp, io.github.fartown.movo.ui.theme.MovoColors.borderHairline, shape)
            .movoClickable(io.github.fartown.movo.ui.components.movo.PressKind.Card, shape = shape, onClick = onClick)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        io.github.fartown.movo.ui.theme.MovoIcon(
            io.github.fartown.movo.ui.theme.MovoIcons.Sparkle, null, size = 14.dp,
            tint = io.github.fartown.movo.ui.theme.MovoColors.indigoFg,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = io.github.fartown.movo.ui.theme.MovoTypography.labelMedium,
            color = io.github.fartown.movo.ui.theme.MovoColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    canRetry: Boolean = true,
) {
    val openLog = io.github.fartown.movo.ui.screens.diagnostics.LocalRunLogOpener.current
    val failure = io.github.fartown.movo.ui.screens.diagnostics.rememberRunFailure(message.id)
    val noticeText = stringResource(
        if (message.code == SystemNoticeCode.Interrupted) R.string.system_notice_interrupted else R.string.system_notice_runtime_failed,
    )
    val rawDetail = message.detail?.takeIf(String::isNotBlank)
    // 没有可用模型时 Runtime 直接拒绝（诊断里可能没有这次任务）：原因卡与输入框上方的提前提示同一说法。
    val modelUnavailable = failure == null &&
        rawDetail?.contains(io.github.fartown.movo.agent.model.UserFacingFailure.MODEL_UNAVAILABLE) == true
    val title = failure?.failure?.title
        ?: if (modelUnavailable) stringResource(R.string.movo_notice_model_title) else noticeText
    // 网络 / TLS 原始报错换成可操作的说明，原文留在运行日志。
    val detail = if (modelUnavailable) {
        stringResource(R.string.movo_notice_model_desc)
    } else {
        io.github.fartown.movo.agent.model.UserFacingFailure.message(
            failure?.failure?.message ?: rawDetail,
            stringResource(R.string.movo_failure_network),
        )
    }
    // 7.2：原因属于模型配置（API Key 无效、无权限、模型不存在、参数无效、额度不足、未配置模型）时，
    // 主操作是「去模型设置」（重试不会好），其次「查看日志」。
    val openModelSettings = LocalOpenModelSettings.current?.takeIf {
        failure?.failure?.modelConfig == true ||
            (failure == null && io.github.fartown.movo.agent.model.UserFacingFailure.isModelConfigError(rawDetail))
    }
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
                val primaryShown = openModelSettings != null || canRetry
                if (openModelSettings != null) {
                    io.github.fartown.movo.ui.components.movo.MovoPillButton(
                        label = stringResource(R.string.movo_run_model_settings),
                        onClick = openModelSettings,
                        primary = true,
                    )
                } else if (canRetry) {
                    io.github.fartown.movo.ui.components.movo.MovoPillButton(
                        label = stringResource(R.string.movo_run_retry),
                        onClick = onRetry,
                        enabled = actionsEnabled,
                    )
                }
                if (openRunLog != null) {
                    if (primaryShown) Spacer(Modifier.width(8.dp))
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
