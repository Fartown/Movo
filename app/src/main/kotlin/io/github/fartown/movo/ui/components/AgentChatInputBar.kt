package io.github.fartown.movo.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.layout
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.draw.drawWithCache
import io.github.fartown.movo.ui.components.movo.rememberSmoothedLevelState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fartown.movo.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.fartown.movo.ui.components.movo.MovoCircleButton
import io.github.fartown.movo.ui.components.movo.PressKind
import io.github.fartown.movo.ui.components.movo.movoClickable
import io.github.fartown.movo.ui.components.movo.movoSurface
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import io.github.fartown.movo.agent.voice.session.VoiceSessionUiState
import io.github.fartown.movo.data.model.ReasoningEffort
import io.github.fartown.movo.ui.model.AgentContextUsageUi
import io.github.fartown.movo.ui.model.AgentModelPickerUiState
import io.github.fartown.movo.ui.model.PendingFileReferenceUi
import io.github.fartown.movo.ui.model.PendingImageUi
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val SendButtonVisualSize = ChatInputActionIconSize
private val SendIconSize = 16.dp
private val StopIconSize = 10.dp
private val ThinkingIconSize = 21.dp
private val InputContainerShape = RoundedCornerShape(20.dp)

/**
 * Agent 输入器始终保持同一空间结构，聚焦、输入和执行过程只改变状态，不搬动操作入口。
 */
@Composable
internal fun AgentChatInputBar(
    input: String,
    modelPickerState: AgentModelPickerUiState,
    isCompacting: Boolean,
    contextUsage: AgentContextUsageUi,
    showContextUsage: Boolean,
    isStreaming: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    isEditingMessage: Boolean,
    editHasLaterTurns: Boolean,
    preserveFollowingMessages: Boolean,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCompactContext: () -> Unit,
    canCompactContext: Boolean,
    onModelSelected: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    isListening: Boolean = false,
    onToggleListen: (() -> Unit)? = null,
    dictationText: String? = null,
    voice: VoiceSessionUiState = VoiceSessionUiState(),
    onStopSpeaking: () -> Unit = {},
    onEndVoice: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val queued = LocalQueuedConversationInput.current
    // 语音模式下文字行不在组合里：切回文字后等输入框重新出现再聚焦、弹键盘。
    var focusAfterVoice by remember { mutableStateOf(false) }
    fun enterText() {
        onEndVoice()
        if (voice.active) {
            focusAfterVoice = true
        } else {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    // 外部请求获焦（分享到 Movo 打开时，规范 8.9.1）：只处理刚发出的请求，旧请求不会在之后的输入框上重放。
    LaunchedEffect(ComposerFocusRequest.generation) {
        if (!voice.active && ComposerFocusRequest.consume()) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }
    LaunchedEffect(voice.active, focusAfterVoice) {
        if (!voice.active && focusAfterVoice) {
            focusAfterVoice = false
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }
    val conversationComposer = LocalConversationComposer.current
    val reducedMotion = io.github.fartown.movo.ui.theme.LocalReducedMotion.current
    // 最近一次是否从语音模式切回（用于工具栏按钮原地淡入）。
    var cameFromVoice by remember { mutableStateOf(false) }
    LaunchedEffect(voice.active) { if (voice.active) cameFromVoice = true }
    // Q1：发送时输入框文字从这里起飞（窗口坐标）。
    val chatFlight = LocalChatFlight.current
    var textLineRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val runControls = LocalRunControls.current
    val textFieldState = conversationComposer ?: rememberTextFieldState(initialText = input)
    var wasEditingMessage by remember { mutableStateOf(isEditingMessage) }
    LaunchedEffect(dictationText) {
        // Dictation is an external edit; initialText alone does not update an existing field.
        // Null means no new transcript, so errors/cancellation retain the editable draft.
        dictationText?.let { textFieldState.setTextAndPlaceCursorAtEnd(it) }
    }
    // 语音输入（麦克风）：说的话写进这个输入框（规范 8.2）。
    val dictation = rememberComposerDictation(textFieldState)
    val textScroll = androidx.compose.foundation.rememberScrollState()
    var textLayout by remember { mutableStateOf<(() -> androidx.compose.ui.text.TextLayoutResult?)?>(null) }
    val confirmProgress = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(dictation.confirmGeneration) {
        if (dictation.confirmGeneration > 0 && !reducedMotion) {
            confirmProgress.snapTo(0f)
            confirmProgress.animateTo(1f, MovoMotion.fast())
        }
    }
    val toggleDictation = rememberDictationToggle(dictation)
    LaunchedEffect(voice.active) { if (voice.active) dictation.cancel() }
    val canSend = textFieldState.text.isNotBlank() ||
        pendingImages.isNotEmpty() ||
        pendingFileReferences.isNotEmpty()
    val density = LocalDensity.current
    val statusBarTopPx = WindowInsets.statusBars.getTop(density)
    var inputContainerTopPx by remember { mutableIntStateOf(0) }
    val thinkingPopupMaxHeight = with(density) {
        (inputContainerTopPx - statusBarTopPx).coerceAtLeast(0).toDp()
    }.minus(ChatInputPopupMargin * 2)
        .coerceAtLeast(MovoSize.touchTarget + MovoSpacing.lg)

    // 进 / 出编辑时输入框文字整段替换（规范 9.3「值变化」先出后进）：旧文字（或占位文字）先淡出 120ms，新内容再淡入 `fast`，
    // 不一帧硬切。对话页的文字由 App 状态直接改写，这里拿不到「改之前」的时机，所以在切换那次组合里记下上一次组合时
    // 显示的文字，作为残影盖在原位淡出；新内容在切换那一帧起就是透明的。
    val composedText = textFieldState.text.toString()
    val lastComposedText = remember { arrayOf(composedText) }
    SideEffect { lastComposedText[0] = composedText }
    val swapGhost = remember(isEditingMessage) { lastComposedText[0] }
    var settledEditing by remember { mutableStateOf(isEditingMessage) }
    val swapProgress = remember { androidx.compose.animation.core.Animatable(1f) }
    val swapTotalMs = MovoMotion.FAST_EXIT + MovoMotion.FAST
    // 切换那一帧效果还没开始：按进度 0 画（残影不透明、新内容透明）。
    val swapElapsedMs: () -> Float = {
        if (settledEditing != isEditingMessage) 0f else swapProgress.value * swapTotalMs
    }
    val ghostAlpha: () -> Float = {
        1f - MovoMotion.EasingExit.transform((swapElapsedMs() / MovoMotion.FAST_EXIT).coerceIn(0f, 1f))
    }
    val swappedContentAlpha: () -> Float = {
        MovoMotion.EasingStandard.transform(((swapElapsedMs() - MovoMotion.FAST_EXIT) / MovoMotion.FAST).coerceIn(0f, 1f))
    }
    LaunchedEffect(isEditingMessage) {
        if (isEditingMessage) {
            if (voice.active) onEndVoice()
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
        // 编辑态由外部业务状态驱动；普通输入只保留在本地，避免每个字符把聊天舞台
        // 的消息流、滚动和 Markdown 一起带入重组。
        val swapText = conversationComposer == null && (isEditingMessage || wasEditingMessage)
        wasEditingMessage = isEditingMessage
        if (swapText && textFieldState.text.toString() != input) {
            textFieldState.setTextAndPlaceCursorAtEnd(input)
        }
        if (settledEditing == isEditingMessage) return@LaunchedEffect
        swapProgress.snapTo(0f)
        settledEditing = isEditingMessage
        if (reducedMotion) {
            swapProgress.snapTo(1f)
        } else {
            swapProgress.animateTo(1f, tween(swapTotalMs, easing = androidx.compose.animation.core.LinearEasing))
        }
    }

    LaunchedEffect(isStreaming, isCompacting) {
        if (conversationComposer == null && isStreaming && !isCompacting) {
            // 发送按钮、建议词和外部恢复都可能启动流式任务，统一清掉本地草稿。
            textFieldState.clearText()
        }
    }

    // 输入框上方的附件条、排队条、提示条（规范 9.3「展开 / 收起」「列表增删」）：高度展开 `standard`，内容与高度同时
    // 淡入 `fast`；消失时内容淡出 120ms、高度同时收起 `standard`。减少动画时只淡入淡出、高度直接到位（9.8）。
    val aboveEnter = if (reducedMotion) {
        fadeIn(MovoMotion.fast())
    } else {
        fadeIn(tween(MovoMotion.FAST, easing = MovoMotion.EasingStandard)) +
            expandVertically(MovoMotion.standard())
    }
    val aboveExit = if (reducedMotion) {
        fadeOut(MovoMotion.fastExit())
    } else {
        fadeOut(MovoMotion.fastExit()) + shrinkVertically(MovoMotion.standard())
    }

    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        // 收起动画期间列表已被清空，沿用最后一次非空的内容，避免条先变空再收起。
        val shownReferences = rememberLastNonEmpty(pendingFileReferences)
        AnimatedVisibility(
            visible = pendingFileReferences.isNotEmpty(),
            enter = aboveEnter,
            exit = aboveExit,
        ) {
            PendingFileReferenceStrip(
                references = shownReferences,
                onRemoveReference = onRemoveFileReference,
                modifier = Modifier.padding(bottom = MovoSpacing.sm),
            )
        }

        val shownImages = rememberLastNonEmpty(pendingImages)
        AnimatedVisibility(
            visible = pendingImages.isNotEmpty(),
            enter = aboveEnter,
            exit = aboveExit,
        ) {
            PendingImageStrip(
                images = shownImages,
                onRemoveImage = onRemoveImage,
                modifier = Modifier.padding(bottom = MovoSpacing.sm),
            )
        }

        // 「下一条」排队条（`Composer/Notice` 写法）：右侧「编辑」「撤回」两个 `Button/Pill`。
        var lastQueuedText by remember { mutableStateOf("") }
        queued.text?.let { if (it != lastQueuedText) lastQueuedText = it }
        AnimatedVisibility(
            visible = queued.text != null,
            enter = aboveEnter,
            exit = aboveExit,
        ) {
            QueuedMessageNotice(
                text = lastQueuedText,
                onEdit = { queued.edit(); enterText() },
                onWithdraw = queued.discard,
                modifier = Modifier.padding(bottom = MovoSpacing.sm),
            )
        }
        // 退出动画期间 notice 已被清空，沿用最后一条文案，避免提示条空白收起。
        var lastVoiceNotice by remember { mutableStateOf("") }
        voice.notice?.let { if (it != lastVoiceNotice) lastVoiceNotice = it }
        AnimatedVisibility(
            visible = !voice.active && voice.notice != null,
            enter = aboveEnter,
            exit = aboveExit,
        ) {
            AgentVoiceNotice(text = lastVoiceNotice, modifier = Modifier.padding(bottom = MovoSpacing.sm))
        }

        // `Composer/Notice`：输入框上方 8，同时最多一条。点麦克风时的语音异常（本地）优先；其次是 App 层交来的
        // 输入相关反馈（取代 Toast，规范 8.11，由 `AgentAppState` 设置并到时清除）。
        val appNotice = ComposerNotices.current
        val activeNotice = dictation.notice ?: appNotice

        // 任务已暂停（规范 8.1「工作过程 · 已暂停」，方案 B）：中性图标底 +「已完成 N 步，点 ▶ 继续」+「结束任务」
        // （直接结束，不再确认，2026-09-27 定）；主按钮为 ▶。临时提示出现时让位，关掉后再回来。
        val runControls = LocalRunControls.current
        var lastPausedSteps by remember { mutableStateOf(0) }
        if (runControls.isPaused) lastPausedSteps = runControls.completedSteps
        AnimatedVisibility(
            visible = runControls.isPaused && activeNotice == null,
            enter = aboveEnter,
            exit = aboveExit,
        ) {
            ComposerNoticeLayout(
                icon = MovoIcons.Pause,
                title = stringResource(R.string.movo_paused_notice_title),
                description = if (lastPausedSteps > 0) {
                    stringResource(R.string.movo_paused_notice_message, lastPausedSteps)
                } else {
                    stringResource(R.string.movo_paused_notice_message_none)
                },
                iconBackground = MovoColors.bgSurfaceMuted,
                iconTint = MovoColors.textSecondary,
                modifier = Modifier.padding(bottom = MovoSpacing.sm),
            ) {
                Spacer(Modifier.width(MovoSpacing.sm))
                io.github.fartown.movo.ui.components.movo.MovoPillButton(
                    label = stringResource(R.string.movo_work_end_task),
                    onClick = runControls.onEndTask,
                    modifier = Modifier.padding(end = MovoSpacing.xs),
                )
            }
        }
        var lastNotice by remember { mutableStateOf<ComposerNotice?>(null) }
        activeNotice?.let { if (it != lastNotice) lastNotice = it }
        AnimatedVisibility(
            visible = activeNotice != null,
            enter = aboveEnter,
            exit = aboveExit,
        ) {
            lastNotice?.let { notice ->
                ComposerNoticeBar(
                    notice = notice,
                    onDismiss = {
                        if (dictation.notice?.id == notice.id) dictation.notice = null else ComposerNotices.dismiss(notice.id)
                    },
                    modifier = Modifier.padding(bottom = MovoSpacing.sm),
                )
            }
        }

        // 7.3 未配置模型（提前提示）：没有可用模型时常驻，「去配置」打开设置里的模型页；配好后自动消失。
        // 与其他提示同一位置，同时最多一条（语音 / 轻提示是用户刚触发的，优先；已暂停提示也优先）。
        val openModelSettings = LocalOpenModelSettings.current
        val modelNotice = openModelSettings?.let { open ->
            ComposerNotice(
                icon = MovoIcons.Cpu,
                title = stringResource(R.string.movo_notice_model_title),
                description = stringResource(R.string.movo_notice_model_desc),
                actionLabel = stringResource(R.string.movo_notice_model_action),
                action = open,
            )
        }
        AnimatedVisibility(
            visible = modelNotice != null && modelPickerState.missingModel && activeNotice == null &&
                !runControls.isPaused && !voice.active,
            enter = aboveEnter,
            exit = aboveExit,
        ) {
            modelNotice?.let { notice ->
                ComposerNoticeBar(
                    notice = notice,
                    onDismiss = {},
                    showClose = false,
                    modifier = Modifier.padding(bottom = MovoSpacing.sm),
                )
            }
        }

        AnimatedVisibility(
            visible = isEditingMessage,
            enter = aboveEnter,
            exit = aboveExit,
        ) {
            Text(
                text = if (preserveFollowingMessages) {
                    "保存后原位更新这条消息，并保留后续对话"
                } else if (editHasLaterTurns) {
                    stringResource(R.string.chat_edit_replace_later)
                } else {
                    stringResource(R.string.chat_edit_replace_message)
                },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
            )
        }

        // 输入框（规范 8.2）：外边距由父级给出 20；圆角 28、内边距上 12 / 其余 8；文字行高 32，右端麦克风 / 声波；
        // 行间距 8；工具栏 40：左 附件、思考，右（会话中）上下文用量、模型、主按钮；总高 100。
        // 执行中不可改的控件（思考、模型、上下文用量）直接隐藏，按钮原地替换、位置不跳（8.2 规则 3）。
        val composerShape = RoundedCornerShape(MovoRadius.xl)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    inputContainerTopPx = coordinates.positionInWindow().y.roundToInt()
                }
                .composerShadow(composerShape)
                .movoSurface(composerShape)
                // 输入框变高（打字换行、字幕变化）`standard`；到 6 行后高度固定、框内滚动（规范 9.4）。
                // 底部对齐：输入框向上长高，工具栏不随高度过渡先向下错位。
                .animateContentSize(if (reducedMotion) snap() else MovoMotion.standard(), alignment = Alignment.BottomStart)
                .padding(start = MovoSpacing.sm, end = MovoSpacing.sm, top = MovoSpacing.md, bottom = MovoSpacing.sm),
        ) {
            // 语音对话中（`Composer/Voice`，规范 8.3）：骨架与文字模式相同，上行换成指示 + 字幕，按钮位置不跳；
            // 文字行与语音行交叉淡化 `standard`（规范 9.6）。先给工具栏留位：半屏窗口 + 键盘时，多行文字在剩余空间里滚动。
            androidx.compose.animation.Crossfade(
                targetState = voice.active,
                animationSpec = MovoMotion.standard(),
                modifier = Modifier.weight(1f, fill = false),
                label = "composerLine",
            ) { voiceLine ->
            if (voiceLine) {
                VoiceLine(voice = voice, onExit = ::enterText)
            } else Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = MovoSize.controlSmall)
                        .padding(start = MovoSpacing.sm, top = MovoSpacing.xs, bottom = MovoSpacing.xs),
                    contentAlignment = Alignment.TopStart,
                ) {
                    val placeholderText = stringResource(
                        if (isStreaming) R.string.movo_composer_placeholder_running else R.string.movo_composer_placeholder,
                    )
                    // 编辑切换的残影：不参与测量（输入框高度按新内容走），超出部分裁掉。
                    if (settledEditing != isEditingMessage || swapProgress.isRunning) {
                        Text(
                            text = swapGhost.ifBlank { placeholderText },
                            style = if (swapGhost.isBlank()) {
                                MovoTypography.inputPlaceholder
                            } else {
                                MovoTypography.inputPlaceholder.copy(color = MovoColors.textPrimary)
                            },
                            color = if (swapGhost.isBlank()) MovoColors.textTertiary else MovoColors.textPrimary,
                            maxLines = if (swapGhost.isBlank()) 1 else 6,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier
                                .matchParentSize()
                                .clipToBounds()
                                .graphicsLayer { alpha = ghostAlpha() },
                        )
                    }
                    if (textFieldState.text.isBlank()) {
                        Text(
                            text = placeholderText,
                            style = MovoTypography.inputPlaceholder,
                            color = MovoColors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.graphicsLayer { alpha = swappedContentAlpha() },
                        )
                    }
                    BasicTextField(
                        state = textFieldState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { alpha = swappedContentAlpha() }
                            .focusRequester(focusRequester)
                            .onGloballyPositioned { textLineRect = it.windowRect() }
                            .dictationConfirmBlur(
                                range = { dictation.confirmingRange },
                                progress = { confirmProgress.value },
                                layout = { textLayout?.invoke() },
                                scroll = { textScroll.value },
                            )
                            .onFocusChanged { if (it.isFocused && voice.active) onEndVoice() },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        textStyle = MovoTypography.inputPlaceholder.copy(color = MovoColors.textPrimary),
                        // 语音输入中未确认的部分三级色（规范 8.2 `Composer/Dictation`）；刚确认的一段三级色 → 主色的 `fast` 过渡
                        // 在绘制阶段完成（`dictationConfirmBlur`），这里只随区间变化，不随进度逐帧重建。
                        outputTransformation = dictation.pendingRange?.let { pending ->
                            remember(pending) { dictationStyle(pending) }
                        },
                        scrollState = textScroll,
                        onTextLayout = { getResult -> textLayout = getResult },
                        cursorBrush = SolidColor(MovoColors.indigoFg),
                        lineLimits = TextFieldLineLimits.MultiLine(
                            minHeightInLines = 1,
                            maxHeightInLines = 6,
                        ),
                    )
                }
                // 文字行右端：「怎么输入」。麦克风 = 语音输入（写进输入框）；声波 = 语音对话（有文字时隐藏，避免误触丢掉已写的内容）。
                if (!voice.active) {
                    Spacer(Modifier.width(MovoSpacing.xs))
                    ComposerLineButton(
                        icon = MovoIcons.Mic,
                        contentDescription = stringResource(
                            if (dictation.listening) R.string.movo_dictation_stop else R.string.movo_voice_input,
                        ),
                        active = dictation.listening,
                        onClick = toggleDictation,
                        // 电平只在按钮的平滑协程里读：在这里读会让整个输入框随每次电平回调重组。
                        level = { if (dictation.listening) dictation.level else 0f },
                        // 右侧紧挨声波（中心相距 40）：向右只扩 4，向左扩 8，两者热区不重叠。
                        touchStart = 8.dp,
                        touchEnd = 4.dp,
                    )
                }
                if (!isEditingMessage && onToggleListen != null) {
                    Spacer(Modifier.width(MovoSpacing.sm))
                    AnimatedVisibility(
                        visible = (textFieldState.text.isEmpty() && !dictation.listening) || voice.active,
                        enter = fadeIn(MovoMotion.fast()),
                        exit = fadeOut(MovoMotion.fastExit()),
                    ) {
                        ComposerLineButton(
                            icon = if (isListening) MovoIcons.Keyboard else MovoIcons.AudioLines,
                            contentDescription = if (isListening) "切回文字输入" else stringResource(R.string.movo_voice_conversation),
                            active = isListening,
                            onClick = {
                                if (voice.active) enterText() else {
                                    focusManager.clearFocus(); keyboard?.hide(); onToggleListen()
                                }
                            },
                            touchStart = 4.dp,
                            touchEnd = 8.dp,
                        )
                    }
                }
            }

            }

            Spacer(Modifier.height(MovoSpacing.sm))

            Row(
                modifier = Modifier.fillMaxWidth().height(MovoSize.controlMedium),
                horizontalArrangement = Arrangement.spacedBy(MovoSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (voice.active) {
                    // 切回文字（键盘 20，占附件「+」的位置）+ 一行提示（Micro/Medium 三级色）；切换时原地淡入 `fast`。
                    io.github.fartown.movo.ui.components.movo.MovoEntrance(shift = 0.dp, durationMillis = MovoMotion.FAST) {
                        MovoCircleButton(
                            icon = MovoIcons.Keyboard,
                            contentDescription = stringResource(R.string.movo_voice_back_to_text),
                            onClick = ::enterText,
                        )
                    }
                    io.github.fartown.movo.ui.components.movo.MovoEntrance(
                        shift = 0.dp,
                        durationMillis = MovoMotion.FAST,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = voice.hint(isStreaming),
                            style = MovoTypography.microMedium,
                            color = MovoColors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    // 编辑消息进出：左侧「+」↔「×」按图标切换（9.3「图标切换」）：旧的缩到 0.72 淡出、新的从 0.72 放大淡入，
                    // 同时进行，不会先空几帧再跳出来。
                    androidx.compose.animation.AnimatedContent(
                        targetState = isEditingMessage,
                        transitionSpec = {
                            if (reducedMotion) {
                                fadeIn(MovoMotion.fast()) togetherWith fadeOut(MovoMotion.fastExit())
                            } else {
                                (fadeIn(MovoMotion.fast()) + scaleIn(MovoMotion.fast(), initialScale = 0.72f)) togetherWith
                                    (fadeOut(MovoMotion.fastExit()) + scaleOut(MovoMotion.fastExit(), targetScale = 0.72f))
                            }.using(androidx.compose.animation.SizeTransform(clip = false))
                        },
                        contentAlignment = Alignment.Center,
                        label = "composerLeading",
                    ) { editing ->
                        if (editing) {
                            MovoCircleButton(
                                icon = MovoIcons.X,
                                contentDescription = stringResource(R.string.ui_cancel_edit_c698df),
                                onClick = onCancelMessageEdit,
                            )
                        } else {
                            // 从语音切回文字时附件「+」原地淡入（首次出现时直接显示）。
                            io.github.fartown.movo.ui.components.movo.MovoEntrance(play = cameFromVoice, shift = 0.dp, durationMillis = MovoMotion.FAST) {
                                AgentAttachmentPickerButton(
                                    popupAnchorTopPx = inputContainerTopPx,
                                    popupMaxHeight = thinkingPopupMaxHeight,
                                    onAttachImage = onAttachImage,
                                    onAttachFiles = onAttachFiles,
                                    onAttachFolder = onAttachFolder,
                                    onAttachFilePath = onAttachFilePath,
                                )
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = !isEditingMessage && availableReasoningEfforts.isNotEmpty() && !isStreaming,
                        enter = fadeIn(MovoMotion.fast()),
                        exit = fadeOut(MovoMotion.fastExit()),
                    ) {
                        ThinkingEffortChip(
                            effort = reasoningEffort,
                            options = availableReasoningEfforts,
                            enabled = true,
                            popupAnchorTopPx = inputContainerTopPx,
                            popupMaxHeight = thinkingPopupMaxHeight,
                            onEffortChange = onReasoningEffortChange,
                        )
                    }
                }

                if (!voice.active) Spacer(modifier = Modifier.weight(1f))

                // 手动压缩也是一轮执行：压缩中留着用量环，浮层里显示「正在压缩…」。
                if (showContextUsage && (!isStreaming || isCompacting) && !voice.active) {
                    AgentContextUsageButton(
                        usage = contextUsage,
                        onCompact = onCompactContext,
                        canCompact = canCompactContext,
                        isCompacting = isCompacting,
                        popupAnchorTopPx = inputContainerTopPx,
                    )
                }
                if (!isStreaming && !voice.active) {
                    AgentModelPickerButton(
                        state = modelPickerState,
                        isStreaming = false,
                        popupAnchorTopPx = inputContainerTopPx,
                        popupMaxHeight = thinkingPopupMaxHeight,
                        onModelSelected = onModelSelected,
                    )
                }
                // 语音模式下同一个状态机：Agent 忙（执行 / 回答播报）= 停止 ■；播报时 ■ 即停止这次回答。
                val voiceBusy = voice.active && (isStreaming || voice.speaking)
                if (voice.active) AutoSendRing(pending = voice.autoSendPending, generation = voice.autoSendGeneration) {
                    MainActionButton(
                        running = voiceBusy,
                        hasContent = false,
                        contentDescription = stringResource(
                            if (isStreaming) R.string.movo_main_stop else R.string.movo_main_stop_speaking,
                        ),
                        onClick = {
                            if (isStreaming) onStop()
                            if (voice.speaking) onStopSpeaking()
                        },
                    )
                } else MainActionButton(
                    running = isStreaming,
                    hasContent = canSend,
                    paused = runControls.isPaused,
                    contentDescription = when {
                        runControls.isPaused && !canSend -> stringResource(R.string.movo_main_resume)
                        isStreaming && !canSend -> stringResource(R.string.movo_main_stop)
                        isStreaming || queued.busy -> stringResource(R.string.movo_main_supplement)
                        isEditingMessage && preserveFollowingMessages -> "保存消息"
                        else -> stringResource(R.string.movo_main_send)
                    },
                    onClick = {
                        // 正在听时直接点发送 = 先停止再发送（已识别的文字随这次发送）。
                        if (dictation.listening) dictation.cancel()
                        when {
                            canSend -> {
                                val text = textFieldState.text.toString()
                                // 新的一轮（不是执行中的补充、编辑重发或带附件）：文字按 Q1 飞成用户气泡。
                                val start = textLineRect
                                if (chatFlight != null && start != null && !isStreaming && !queued.busy && !isEditingMessage &&
                                    pendingImages.isEmpty() && pendingFileReferences.isEmpty()
                                ) {
                                    chatFlight.launch(text, start, filled = false)
                                }
                                onSubmit(text)
                            }
                            runControls.isPaused -> runControls.onResume()
                            isStreaming -> onStop()
                        }
                    },
                )
            }
        }
    }

}

/**
 * 文字行右端 32 按钮（麦克风 / 声波），图标 20；正在听时 Indigo 浅底。
 * 热区向外扩到 44（规范 2.3）：上下各 6；左右共 12，按 [touchStart] / [touchEnd] 分配——麦克风与声波中心相距 40，
 * 各自向对方只扩 4、向外扩 8，两者热区不重叠。视觉尺寸与布局位置不变。
 */
@Composable
private fun ComposerLineButton(
    icon: io.github.fartown.movo.ui.theme.MovoIconData,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    level: () -> Float = { 0f },
    touchStart: Dp = LineButtonTouchInset,
    touchEnd: Dp = LineButtonTouchInset,
) {
    // 语音输入中：Indigo 浅底 `fast` 过渡 + 外圈随音量脉动（最大外扩 6，规范 9.6）。
    val background by animateColorAsState(
        if (active) MovoColors.indigoBg else Color.Transparent,
        MovoMotion.fast(),
        label = "lineButtonBg",
    )
    // 电平每帧变化：原始电平在平滑协程里读，平滑值只在绘制阶段读，按钮与输入框都不随电平重组。
    val currentActive by rememberUpdatedState(active)
    val pulseState = rememberSmoothedLevelState { if (currentActive) level() else 0f }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val vertical = (MovoSize.touchTarget - MovoSize.controlSmall) / 2
    Box(
        modifier = Modifier
            .expandedTouchArea(start = touchStart, end = touchEnd, vertical = vertical)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription }
            .padding(start = touchStart, end = touchEnd, top = vertical, bottom = vertical),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(MovoSize.controlSmall)
                .drawBehind {
                    val pulse = pulseState.value
                    if (pulse > 0.01f) {
                        drawCircle(
                            color = MovoColors.indigoFg.copy(alpha = 0.12f * pulse + 0.04f),
                            radius = size.minDimension / 2 + 6.dp.toPx() * pulse,
                        )
                    }
                }
                .iconPressOverlay(interaction)
                .clip(CircleShape)
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            MovoIcon(icon, null, size = MovoSize.iconMedium, tint = if (active) MovoColors.indigoFg else MovoColors.textPrimary)
        }
    }
}

/** 32 按钮的热区左右共扩 12（到 44）；默认两侧各 6。 */
private val LineButtonTouchInset = 6.dp

/**
 * 只扩热区、不占布局：内层按 [start] / [end] / [vertical] 加大后测量与响应点击，对外只报告原来的尺寸，
 * 多出来的部分向四周溢出（外层不裁切，点击命中照常落到内层）。
 */
internal fun Modifier.expandedTouchArea(start: Dp, end: Dp, vertical: Dp): Modifier = layout { measurable, constraints ->
    val s = start.roundToPx()
    val e = end.roundToPx()
    val v = vertical.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = 0,
            minHeight = 0,
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + s + e else constraints.maxWidth,
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + 2 * v else constraints.maxHeight,
        ),
    )
    val width = (placeable.width - s - e).coerceAtLeast(0)
    val height = (placeable.height - 2 * v).coerceAtLeast(0)
    layout(width, height) { placeable.place(-s, -v) }
}

/**
 * 无底色图标按钮的按压反馈（规范 9.3.1）：图标不缩放，40 圆形 `overlay/pressed` 从 0.8 放大到 1 并淡入（`instant`），
 * 松手淡出 `fast`。点击由外层（扩大后的热区）处理，这里只读同一个 [interaction] 画反馈。
 */
@Composable
private fun Modifier.iconPressOverlay(interaction: androidx.compose.foundation.interaction.InteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val overlay by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (pressed) MovoMotion.instant() else MovoMotion.fast(),
        label = "lineButtonPress",
    )
    return drawWithContent {
        drawContent()
        if (overlay > 0f) {
            drawCircle(MovoColors.overlayPressed, radius = 20.dp.toPx() * (0.8f + 0.2f * overlay), alpha = overlay)
        }
    }
}

/** 列表清空后仍返回最后一次非空的内容，给收起动画使用。 */
@Composable
private fun <T> rememberLastNonEmpty(items: List<T>): List<T> {
    var last by remember { mutableStateOf(items) }
    if (items.isNotEmpty() && items != last) last = items
    return if (items.isNotEmpty()) items else last
}

/**
 * E2 `Elevation/Composer` 的硬件阴影（规范 7，暖灰 #2B2419）：由 RenderThread 按轮廓绘制，输入框高度动画时只更新轮廓，
 * 不像 `dropShadow` 那样逐帧重建 40 的模糊。环境光阴影近似 `0 1 3 / 6%`，投影近似 `0 16 40 −12 / 12%`
 * （最终不透明度 = 颜色 alpha × 主题 spotShadowAlpha，需真机对照 E2 调校）。
 */
private fun Modifier.composerShadow(shape: androidx.compose.ui.graphics.Shape): Modifier = shadow(
    elevation = ComposerShadowElevation,
    shape = shape,
    clip = false,
    ambientColor = MovoColors.shadow,
    spotColor = MovoColors.shadow.copy(alpha = ComposerSpotShadowAlpha),
)

private val ComposerShadowElevation = 12.dp
private const val ComposerSpotShadowAlpha = 0.6f

/**
 * 「下一条」排队条（D11）：`Composer/Notice` 写法——图标底 32（中性色，不是异常）+「下一条」+ 排队的原话，
 * 右侧「编辑」「撤回」两个 `Button/Pill`；不自动消失，处理后收起。
 */
@Composable
private fun QueuedMessageNotice(
    text: String,
    onEdit: () -> Unit,
    onWithdraw: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ComposerNoticeLayout(
        icon = MovoIcons.Clock,
        title = stringResource(R.string.movo_queued_title),
        description = text,
        iconBackground = MovoColors.bgSurfaceMuted,
        iconTint = MovoColors.textSecondary,
        modifier = modifier,
    ) {
        Spacer(Modifier.width(MovoSpacing.sm))
        io.github.fartown.movo.ui.components.movo.MovoPillButton(
            label = stringResource(R.string.action_edit),
            onClick = onEdit,
        )
        Spacer(Modifier.width(MovoSpacing.sm))
        io.github.fartown.movo.ui.components.movo.MovoPillButton(
            label = stringResource(R.string.movo_queued_withdraw),
            onClick = onWithdraw,
            modifier = Modifier.padding(end = MovoSpacing.xs),
        )
    }
}

private fun dictationStyle(pending: Pair<Int, Int>) = androidx.compose.foundation.text.input.OutputTransformation {
    val start = pending.first.coerceIn(0, length)
    val end = pending.second.coerceIn(start, length)
    if (end > start) addStyle(androidx.compose.ui.text.SpanStyle(color = MovoColors.textTertiary), start, end)
}

/**
 * 刚确认的一段（Q2「语音字幕由未确认变确认」，`fast`）：颜色三级色 → 主色、模糊 2 → 0。这段单独放进图层，
 * 用 SrcIn 着色画出过渡中的颜色并模糊，其余文字照常；进度只在绘制阶段读取，不触发重组。
 * 两条裁切路径（这一段 / 其余部分）只随区间、排版与滚动变化，过渡期间逐帧复用，不再每帧求字形区域与路径差集。
 */
private fun Modifier.dictationConfirmBlur(
    range: () -> Pair<Int, Int>?,
    progress: () -> Float,
    layout: () -> androidx.compose.ui.text.TextLayoutResult?,
    scroll: () -> Int,
): Modifier = drawWithCache {
    val layer = obtainGraphicsLayer()
    val blurMax = 2.dp.toPx()
    var cachedKey: Any? = null
    var rangePath: androidx.compose.ui.graphics.Path? = null
    var restPath: androidx.compose.ui.graphics.Path? = null
    onDrawWithContent {
        val p = progress()
        val confirming = range()
        val result = layout()
        if (confirming == null || p >= 1f || result == null) {
            drawContent()
            return@onDrawWithContent
        }
        val length = result.layoutInput.text.length
        val start = confirming.first.coerceIn(0, length)
        val end = confirming.second.coerceIn(start, length)
        if (end <= start) {
            drawContent()
            return@onDrawWithContent
        }
        val offsetY = scroll()
        val key = listOf(start, end, offsetY, result, size)
        if (key != cachedKey) {
            val segment = result.getPathForRange(start, end).apply {
                translate(androidx.compose.ui.geometry.Offset(0f, -offsetY.toFloat()))
            }
            val everything = androidx.compose.ui.graphics.Path().apply {
                addRect(androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset.Zero, size))
            }
            rangePath = segment
            restPath = androidx.compose.ui.graphics.Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, everything, segment)
            cachedKey = key
        }
        val content = this
        clipPath(restPath!!) { content.drawContent() }
        val radius = blurMax * (1f - p)
        layer.renderEffect = if (radius > 0.05f) {
            androidx.compose.ui.graphics.BlurEffect(radius, radius, androidx.compose.ui.graphics.TileMode.Decal)
        } else {
            null
        }
        layer.colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
            androidx.compose.ui.graphics.lerp(MovoColors.textTertiary, MovoColors.textPrimary, p.coerceIn(0f, 1f)),
            androidx.compose.ui.graphics.BlendMode.SrcIn,
        )
        layer.record { content.drawContent() }
        clipPath(rangePath!!) { drawLayer(layer) }
    }
}

/**
 * 输入框主按钮 `Button/MainAction` = Agent 状态机（规范 8.2）：
 * 空闲无内容 = 发送置灰；有内容 = 发送 ↑；运行中无内容 = 停止 ■；运行中有内容 = 发送 ↑（补充）。
 * 图标形状连续变化（Q5，`fast` + `easing/standard`）；置灰 ↔ 可用只过渡颜色（`instant`）。减少动画时直接切换。
 */
@Composable
private fun MainActionButton(
    running: Boolean,
    hasContent: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    paused: Boolean = false,
) {
    val enabled = hasContent || running
    val reduced = LocalReducedMotion.current
    val showStop = running && !hasContent
    val morph by animateFloatAsState(
        targetValue = if (showStop) 1f else 0f,
        animationSpec = if (reduced) snap() else MovoMotion.fast(),
        label = "mainActionMorph",
    )
    // Q5：■ → ▶ 方块右侧两角向右中点收拢成三角；▶ → ■ 反向。
    val resume by animateFloatAsState(
        targetValue = if (showStop && paused) 1f else 0f,
        animationSpec = if (reduced) snap() else MovoMotion.fast(),
        label = "mainActionResume",
    )
    val bg by animateColorAsState(
        if (enabled) MovoColors.actionPrimaryBg else MovoColors.bgSurfaceMuted,
        MovoMotion.instant(),
        label = "mainActionBg",
    )
    val fg by animateColorAsState(
        if (enabled) MovoColors.actionPrimaryFg else MovoColors.textTertiary,
        MovoMotion.instant(),
        label = "mainActionFg",
    )
    Box(
        modifier = Modifier
            .size(MovoSize.controlMedium)
            .movoClickable(PressKind.Solid, shape = CircleShape, enabled = enabled, onClick = onClick)
            .clip(CircleShape)
            .background(bg)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(MovoSize.iconMedium)) {
            drawMainActionGlyph(morph, resume, fg)
        }
    }
}

/** Q5：↑ → ■。竖线从下往上收回，箭头两笔压平下移成方块上沿，方块从上沿向下展开并填充。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMainActionGlyph(progress: Float, resume: Float, color: Color) {
    val u = size.width / 24f
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun lerp(a: Float, b: Float) = a + (b - a) * progress
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
        width = 1.6f * 24f / 20f * u,
        cap = androidx.compose.ui.graphics.StrokeCap.Round,
        join = androidx.compose.ui.graphics.StrokeJoin.Round,
    )
    if (progress < 1f) {
        // 竖线：底端从 19 收到 5（与箭头顶端重合）。
        drawLine(color, p(12f, lerp(5f, 7f)), p(12f, lerp(19f, 7f)), strokeWidth = stroke.width, cap = stroke.cap)
    }
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(lerp(5f, 7f) * u, lerp(12f, 7f) * u)
        lineTo(12f * u, lerp(5f, 7f) * u)
        lineTo(lerp(19f, 17f) * u, lerp(12f, 7f) * u)
    }
    // 箭头两笔压平后与方块上沿重合，逐渐让位给方块，避免圆头线帽在方块两侧露出。
    drawPath(path, color, style = stroke, alpha = 1f - progress)
    if (progress > 0f && resume <= 0f) {
        drawRoundRect(
            color = color,
            topLeft = p(7f, 7f),
            size = androidx.compose.ui.geometry.Size(10f * u, 10f * u * progress),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u),
            alpha = progress,
        )
    } else if (resume > 0f) {
        // 方块 (7,7)-(17,17) → 三角 (8,6)(18,12)(8,18)：右上、右下两角向右中点收拢。
        fun l(a: Float, b: Float) = a + (b - a) * resume
        val shape = androidx.compose.ui.graphics.Path().apply {
            moveTo(l(7f, 8f) * u, l(7f, 6f) * u)
            lineTo(l(17f, 18f) * u, l(7f, 12f) * u)
            lineTo(l(17f, 18f) * u, l(17f, 12f) * u)
            lineTo(l(7f, 8f) * u, l(17f, 18f) * u)
            close()
        }
        drawPath(shape, color)
        drawPath(shape, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f * u, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/**
 * 思考芯片（规范 8 组件表）：高 40；关 = 透明底 + 1 宽 border/strong +「思考」次要色；默认 = Indigo 浅底 +「思考」；
 * 指定档位 = Indigo 浅底，只显示档位名。关 ↔ 开：底色、描边（淡出）、图标与文字颜色 `fast`（9.3.1「芯片开关」）；
 * 换档：文字交叉淡化 `fast`，宽度 `standard`（只由一个 animateContentSize 驱动，9.3.1「芯片换档」）。
 * 点击弹出档位菜单（`Popover/Menu`，出现在输入框上方）；选中后 ✓ 先更新，停留 160ms 再关闭（9.3.1「单选」）。
 */
@Composable
private fun ThinkingEffortChip(
    effort: ReasoningEffort,
    options: List<ReasoningEffort>,
    enabled: Boolean,
    popupAnchorTopPx: Int,
    popupMaxHeight: Dp,
    onEffortChange: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPopup by remember { mutableStateOf(false) }
    var pendingEffort by remember { mutableStateOf<ReasoningEffort?>(null) }
    val active = effort != ReasoningEffort.OFF
    val menuEnabled = enabled && options.size > 1
    val reduced = LocalReducedMotion.current
    LaunchedEffect(menuEnabled) {
        if (!menuEnabled) showPopup = false
    }
    LaunchedEffect(pendingEffort) {
        val chosen = pendingEffort ?: return@LaunchedEffect
        kotlinx.coroutines.delay(MovoMotion.FAST.toLong() + MovoMotion.MENU_CLOSE_DELAY)
        showPopup = false
        if (chosen != effort) onEffortChange(chosen)
    }
    val contentColor by animateColorAsState(
        targetValue = if (active) MovoColors.indigoFg else MovoColors.textSecondary,
        animationSpec = MovoMotion.fast(),
        label = "thinking_content",
    )
    val chipShape = RoundedCornerShape(MovoRadius.lg)
    val chipBg by animateColorAsState(
        if (active) MovoColors.indigoBg else Color.Transparent,
        MovoMotion.fast(),
        label = "thinking_bg",
    )
    // 关 → 开时描边淡出（颜色过渡到透明），开 → 关时淡入。
    val chipBorder by animateColorAsState(
        if (active) Color.Transparent else MovoColors.borderStrong,
        MovoMotion.fast(),
        label = "thinking_border",
    )
    val chipLabel = effort.chipLabel()
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .height(MovoSize.controlMedium)
                .movoClickable(
                    PressKind.Solid,
                    shape = chipShape,
                    enabled = menuEnabled,
                    onClick = {
                        // 菜单开着时再点芯片是关闭（点芯片本身不算「点外面」，菜单不会自己关）。
                        pendingEffort = null
                        showPopup = !showPopup
                    },
                )
                .clip(chipShape)
                .background(chipBg)
                .border(1.dp, chipBorder, chipShape)
                // 换档时宽度变化 `standard`（规范 9.3「思考芯片」）；减少动画时直接到位。
                .animateContentSize(if (reduced) snap() else MovoMotion.standard())
                .padding(start = 12.dp, end = 16.dp)
                .semantics { contentDescription = "思考强度：${effort.chipLabelPlain()}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MovoIcon(MovoIcons.Atom, null, size = MovoSize.iconSmall, tint = contentColor)
            Spacer(Modifier.width(6.dp))
            AnimatedContent(
                targetState = chipLabel,
                transitionSpec = {
                    (fadeIn(MovoMotion.fast()) togetherWith fadeOut(MovoMotion.fastExit()))
                        // 宽度只由外层 animateContentSize 过渡，这里不再另起一个尺寸动画。
                        .using(null)
                },
                label = "thinking_label",
            ) { label ->
                Text(label, style = MovoTypography.labelMedium, color = contentColor, maxLines = 1)
            }
        }
        io.github.fartown.movo.ui.components.movo.MovoPopover(
            show = showPopup && menuEnabled && popupAnchorTopPx > 0,
            onDismiss = { showPopup = false },
            aboveYPx = popupAnchorTopPx,
            maxHeight = popupMaxHeight,
        ) {
            val selected = pendingEffort ?: effort
            options.forEach { option ->
                io.github.fartown.movo.ui.components.movo.MovoPopoverItem(
                    label = option.displayName,
                    selected = option == selected,
                    onClick = { if (pendingEffort == null) pendingEffort = option },
                )
            }
        }
    }
}

/**
 * 输入框上方的图片条（规范 8.9.1「与『+』添加的相同」）：60 缩略图，圆角 8（`radius/xs` 小缩略图），无水波纹；
 * 右上角删除按钮视觉 20、热区 44（规范 2.3），热区向缩略图外溢出，缩略图本身不裁切热区。
 */
@Composable
private fun PendingImageStrip(
    images: List<PendingImageUi>,
    onRemoveImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbShape = RoundedCornerShape(MovoRadius.xs)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(MovoSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        images.forEach { image ->
            Box(modifier = Modifier.size(PendingThumbnailSize)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(thumbShape)
                        .background(MovoColors.bgSurfaceMuted),
                ) {
                    rememberDataUrlBitmap(image.dataUrl)?.let { bitmap ->
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                PendingRemoveButton(
                    contentDescription = stringResource(R.string.ui_remove_image_089db3),
                    onClick = { onRemoveImage(image.id) },
                    onImage = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(MovoSpacing.xs),
                )
            }
        }
    }
}

private val PendingThumbnailSize = 60.dp

/**
 * 附件条上的删除按钮：视觉 20 圆（✕ 12），热区 44（规范 2.3，向四周溢出、不占布局）；按压为无底色图标按钮的 40 圆形叠加。
 * 文件胶囊上用 `bg/surface-muted` 底 + 次要色 ✕；图片上用 `bg/inverse` 58% 底 + 白色 ✕，保证在任意图片上可见。
 */
@Composable
internal fun PendingRemoveButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onImage: Boolean = false,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val expand = (MovoSize.touchTarget - PendingRemoveVisualSize) / 2
    Box(
        modifier = modifier
            .expandedTouchArea(start = expand, end = expand, vertical = expand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription }
            .padding(expand),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(PendingRemoveVisualSize)
                .iconPressOverlay(interaction)
                .clip(CircleShape)
                .background(if (onImage) MovoColors.bgInverse.copy(alpha = 0.58f) else MovoColors.bgSurfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            MovoIcon(
                MovoIcons.X,
                null,
                size = MovoSize.iconTiny,
                tint = if (onImage) MovoColors.textOnInverse else MovoColors.textSecondary,
            )
        }
    }
}

private val PendingRemoveVisualSize = 20.dp

/** 思考芯片上的文字：关与默认都显示「思考」，指定档位只显示档位名。 */
@Composable
private fun ReasoningEffort.chipLabel(): String = when (this) {
    ReasoningEffort.OFF, ReasoningEffort.DEFAULT -> stringResource(R.string.movo_thinking)
    ReasoningEffort.MINIMAL -> stringResource(R.string.movo_effort_minimal)
    ReasoningEffort.LOW -> stringResource(R.string.movo_effort_low)
    ReasoningEffort.MEDIUM -> stringResource(R.string.movo_effort_medium)
    ReasoningEffort.HIGH -> stringResource(R.string.movo_effort_high)
    ReasoningEffort.XHIGH -> stringResource(R.string.movo_effort_xhigh)
    ReasoningEffort.MAX -> stringResource(R.string.movo_effort_max)
}

private fun ReasoningEffort.chipLabelPlain(): String = displayName

/** 语音模式工具栏中间的提示（规范 8.3）；控制器给出的说明（如已记下待发送）优先。 */
@Composable
private fun VoiceSessionUiState.hint(agentBusy: Boolean): String = when {
    channel == io.github.fartown.movo.agent.voice.session.VoiceChannel.Thinking && statusText.isNotBlank() -> statusText
    channel == io.github.fartown.movo.agent.voice.session.VoiceChannel.Connecting -> stringResource(R.string.movo_voice_hint_connecting)
    speaking -> stringResource(R.string.movo_voice_hint_speaking)
    agentBusy -> stringResource(R.string.movo_voice_hint_busy)
    else -> stringResource(R.string.movo_voice_hint_listening)
}

/**
 * 语音模式上行：最小高 32，左 8：指示（聆听 = Indigo 音量条 / 播报 = 扬声器 16 / 连接中 = 灰点）+ 8 +
 * 字幕 Input/Placeholder（已确认主色）；无字幕时「正在聆听…」（Q3 光带）。播报时「正在播报·说话即可打断」。
 * 字幕最多 3 行，指示跟随最新一行（底对齐）。点上行任意位置切回文字（规范 8.3「退出」）。
 */
@Composable
private fun VoiceLine(
    voice: VoiceSessionUiState,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val channel = voice.channel
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MovoSize.controlSmall)
            .movoClickable(PressKind.Row, role = null, onClick = onExit)
            .padding(start = MovoSpacing.sm, top = MovoSpacing.xs, bottom = MovoSpacing.xs),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(modifier = Modifier.size(width = 24.dp, height = 24.dp), contentAlignment = Alignment.Center) {
            when (channel) {
                io.github.fartown.movo.agent.voice.session.VoiceChannel.Speaking ->
                    MovoIcon(MovoIcons.Volume2, null, size = MovoSize.iconSmall, tint = MovoColors.indigoFg)
                io.github.fartown.movo.agent.voice.session.VoiceChannel.Connecting ->
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MovoColors.textTertiary))
                else -> VoiceLevelBars(hearing = channel == io.github.fartown.movo.agent.voice.session.VoiceChannel.Hearing)
            }
        }
        Spacer(Modifier.width(MovoSpacing.sm))
        val transcript = voice.transcript.takeIf { it.isNotBlank() && channel != io.github.fartown.movo.agent.voice.session.VoiceChannel.Speaking }
        androidx.compose.animation.Crossfade(
            targetState = transcript ?: channel.name,
            animationSpec = MovoMotion.fast(),
            modifier = Modifier.weight(1f),
            label = "voiceLine",
        ) { _ ->
            when {
                transcript != null -> Text(
                    text = transcript,
                    style = MovoTypography.inputPlaceholder,
                    color = MovoColors.textPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                channel == io.github.fartown.movo.agent.voice.session.VoiceChannel.Speaking -> Text(
                    stringResource(R.string.movo_voice_speaking),
                    style = MovoTypography.inputPlaceholder,
                    color = MovoColors.textSecondary,
                    maxLines = 1,
                )
                channel == io.github.fartown.movo.agent.voice.session.VoiceChannel.Connecting -> Text(
                    stringResource(R.string.movo_voice_connecting),
                    style = MovoTypography.inputPlaceholder,
                    color = MovoColors.textSecondary,
                    maxLines = 1,
                )
                else -> io.github.fartown.movo.ui.components.movo.MovoShimmerText(
                    text = stringResource(R.string.movo_voice_listening),
                    style = MovoTypography.inputPlaceholder,
                    color = MovoColors.textSecondary,
                    active = true,
                )
            }
        }
    }
}

/**
 * 音量条（规范 9.6）：6 条、宽 2.5、间距 2、最高 16，垂直居中。目前控制器只给出「是否听到说话」，
 * 听到说话时停在设计稿的静态高度（6 / 12 / 16 / 10 / 14 / 8），静音时全部停在 4，不做假动画；
 * 高度变化 `fast` 过渡。0–1 电平接入后按 16 × (0.25 + 0.75 × 音量 × 系数) 计算。
 */
@Composable
private fun VoiceLevelBars(hearing: Boolean) {
    val factors = listOf(0.375f, 0.75f, 1f, 0.625f, 0.875f, 0.5f)
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        factors.forEachIndexed { index, factor ->
            val height by animateDpAsState(
                targetValue = if (hearing) 16.dp * factor else 4.dp,
                animationSpec = MovoMotion.fast(),
                label = "voiceBar$index",
            )
            Box(
                Modifier
                    .size(width = 2.5.dp, height = height)
                    .clip(RoundedCornerShape(1.25.dp))
                    .background(MovoColors.indigoFg),
            )
        }
    }
}

/**
 * 停顿后自动发送的进度环（规范 9.6，Figma「M4」）：直径 44、与主按钮同心、2 宽 Indigo、圆头，从 12 点方向顺时针，
 * 淡入 `fast` 后 `linear` 走完引擎的自动发送等待时长；再开口或已发出时淡出 120ms 并重置。
 * 减少动画时保留（它表达等待时间），改为分 4 段跳变。
 */
@Composable
private fun AutoSendRing(pending: Boolean, generation: Int, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    val alpha by animateFloatAsState(
        if (pending) 1f else 0f,
        if (pending) MovoMotion.fast() else MovoMotion.fastExit(),
        label = "autoSendRingAlpha",
    )
    LaunchedEffect(pending, generation) {
        if (!pending) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(
            1f,
            tween(
                io.github.fartown.movo.agent.voice.conversation.VoiceCommitGate.AUTO_SEND_WAIT_MS.toInt(),
                easing = MovoMotion.EasingLinear,
            ),
        )
    }
    Box(
        modifier = Modifier.drawBehind {
            if (alpha <= 0.01f) return@drawBehind
            val stroke = 2.dp.toPx()
            val diameter = 44.dp.toPx() - stroke
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val value = progress.value.let { if (reduced) kotlin.math.floor(it * 4f) / 4f else it }
            drawArc(
                color = MovoColors.indigoFg.copy(alpha = alpha),
                startAngle = -90f,
                sweepAngle = 360f * value,
                useCenter = false,
                topLeft = topLeft,
                size = androidx.compose.ui.geometry.Size(diameter, diameter),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
