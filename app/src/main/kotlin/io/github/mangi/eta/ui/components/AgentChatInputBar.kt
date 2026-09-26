package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.draw.drawWithCache
import io.github.mangi.eta.ui.components.movo.rememberSmoothedLevel
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
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
import io.github.mangi.eta.ui.components.movo.MovoCircleButton
import io.github.mangi.eta.ui.components.movo.PressKind
import io.github.mangi.eta.ui.components.movo.movoClickable
import io.github.mangi.eta.ui.components.movo.movoElevation
import io.github.mangi.eta.ui.components.movo.movoSurface
import io.github.mangi.eta.ui.theme.LocalReducedMotion
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoElevation
import io.github.mangi.eta.ui.theme.MovoIcon
import io.github.mangi.eta.ui.theme.MovoIcons
import io.github.mangi.eta.ui.theme.MovoMotion
import io.github.mangi.eta.ui.theme.MovoRadius
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoSpacing
import io.github.mangi.eta.ui.theme.MovoTypography
import io.github.mangi.eta.agent.voice.session.VoiceSessionUiState
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.overlay.OverlayListPopup

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
    LaunchedEffect(voice.active, focusAfterVoice) {
        if (!voice.active && focusAfterVoice) {
            focusAfterVoice = false
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }
    val conversationComposer = LocalConversationComposer.current
    val reducedMotion = io.github.mangi.eta.ui.theme.LocalReducedMotion.current
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
        .coerceAtLeast(ListPopupDefaults.MinPopupHeight)

    LaunchedEffect(isEditingMessage) {
        // 编辑态由外部业务状态驱动；普通输入只保留在本地，避免每个字符把聊天舞台
        // 的消息流、滚动和 Markdown 一起带入重组。
        if (conversationComposer == null && (isEditingMessage || wasEditingMessage)) {
            textFieldState.setTextAndPlaceCursorAtEnd(input)
        }
        if (isEditingMessage) {
            if (voice.active) onEndVoice()
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
        wasEditingMessage = isEditingMessage
    }

    LaunchedEffect(isStreaming, isCompacting) {
        if (conversationComposer == null && isStreaming && !isCompacting) {
            // 发送按钮、建议词和外部恢复都可能启动流式任务，统一清掉本地草稿。
            textFieldState.clearText()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        AnimatedVisibility(
            visible = pendingFileReferences.isNotEmpty(),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(160)),
        ) {
            PendingFileReferenceStrip(
                references = pendingFileReferences,
                onRemoveReference = onRemoveFileReference,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = pendingImages.isNotEmpty(),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(160)),
        ) {
            PendingImageStrip(
                images = pendingImages,
                onRemoveImage = onRemoveImage,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        queued.text?.let { text ->
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text("下一条：$text", maxLines = 3, style = MiuixTheme.textStyles.body2)
                Row {
                    Text("编辑", modifier = Modifier.clickable { queued.edit(); enterText() }.padding(12.dp))
                    Text("撤回", modifier = Modifier.clickable(onClick = queued.discard).padding(12.dp))
                }
            }
        }
        // 退出动画期间 notice 已被清空，沿用最后一条文案，避免提示条空白收起。
        var lastVoiceNotice by remember { mutableStateOf("") }
        voice.notice?.let { if (it != lastVoiceNotice) lastVoiceNotice = it }
        AnimatedVisibility(
            visible = !voice.active && voice.notice != null,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(160)),
        ) {
            AgentVoiceNotice(text = lastVoiceNotice, modifier = Modifier.padding(bottom = 8.dp))
        }

        // `Composer/Notice`：缺麦克风权限或未配置语音时，输入框上方 8 显示，同时最多一条。
        var lastNotice by remember { mutableStateOf<ComposerNotice?>(null) }
        dictation.notice?.let { if (it != lastNotice) lastNotice = it }
        AnimatedVisibility(
            visible = dictation.notice != null,
            enter = fadeIn(MovoMotion.fast()),
            exit = fadeOut(MovoMotion.fastExit()) + shrinkVertically(MovoMotion.standard()),
        ) {
            lastNotice?.let { notice ->
                ComposerNoticeBar(
                    notice = notice,
                    onDismiss = { dictation.notice = null },
                    modifier = Modifier.padding(bottom = MovoSpacing.sm),
                )
            }
        }

        AnimatedVisibility(
            visible = isEditingMessage,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
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
                .movoElevation(MovoElevation.Composer, composerShape)
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
                    if (textFieldState.text.isBlank()) {
                        Text(
                            text = stringResource(
                                if (isStreaming) R.string.movo_composer_placeholder_running else R.string.movo_composer_placeholder,
                            ),
                            style = MovoTypography.inputPlaceholder,
                            color = MovoColors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        state = textFieldState,
                        modifier = Modifier
                            .fillMaxWidth()
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
                        // 语音输入中未确认的部分三级色；刚确认的一段三级色 → 主色过渡 `fast`（规范 8.2 `Composer/Dictation`、9.6）。
                        outputTransformation = run {
                            val pending = dictation.pendingRange
                            val confirming = dictation.confirmingRange?.takeIf { confirmProgress.value < 1f }
                            if (pending == null && confirming == null) {
                                null
                            } else {
                                val p = confirmProgress.value
                                remember(pending, confirming, p) { dictationStyle(pending, confirming, p) }
                            }
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
                        level = if (dictation.listening) dictation.level else 0f,
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
                    io.github.mangi.eta.ui.components.movo.MovoEntrance(shift = 0.dp, durationMillis = MovoMotion.FAST) {
                        MovoCircleButton(
                            icon = MovoIcons.Keyboard,
                            contentDescription = stringResource(R.string.movo_voice_back_to_text),
                            onClick = ::enterText,
                        )
                    }
                    io.github.mangi.eta.ui.components.movo.MovoEntrance(
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
                } else if (isEditingMessage) {
                    MovoCircleButton(
                        icon = MovoIcons.X,
                        contentDescription = stringResource(R.string.ui_cancel_edit_c698df),
                        onClick = onCancelMessageEdit,
                    )
                } else {
                    // 从语音切回文字时附件「+」原地淡入（首次出现时直接显示）。
                    io.github.mangi.eta.ui.components.movo.MovoEntrance(play = cameFromVoice, shift = 0.dp, durationMillis = MovoMotion.FAST) {
                        AgentAttachmentPickerButton(
                            popupAnchorTopPx = inputContainerTopPx,
                            popupMaxHeight = thinkingPopupMaxHeight,
                            onAttachImage = onAttachImage,
                            onAttachFiles = onAttachFiles,
                            onAttachFolder = onAttachFolder,
                            onAttachFilePath = onAttachFilePath,
                        )
                    }
                    if (availableReasoningEfforts.isNotEmpty() && !isStreaming) {
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

                if (showContextUsage && !isStreaming && !voice.active) {
                    AgentContextUsageButton(usage = contextUsage, onCompact = onCompactContext, canCompact = canCompactContext)
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

/** 文字行右端 32 按钮（麦克风 / 声波），图标 20；正在听时 Indigo 浅底。 */
@Composable
private fun ComposerLineButton(
    icon: io.github.mangi.eta.ui.theme.MovoIconData,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    level: Float = 0f,
) {
    // 语音输入中：Indigo 浅底 `fast` 过渡 + 外圈随音量脉动（最大外扩 6，规范 9.6）。
    val background by animateColorAsState(
        if (active) MovoColors.indigoBg else Color.Transparent,
        MovoMotion.fast(),
        label = "lineButtonBg",
    )
    val pulse = rememberSmoothedLevel(if (active) level else 0f)
    Box(
        modifier = Modifier
            .size(MovoSize.controlSmall)
            .drawBehind {
                if (pulse > 0.01f) {
                    drawCircle(
                        color = MovoColors.indigoFg.copy(alpha = 0.12f * pulse + 0.04f),
                        radius = size.minDimension / 2 + 6.dp.toPx() * pulse,
                    )
                }
            }
            .movoClickable(PressKind.Icon, onClick = onClick)
            .clip(CircleShape)
            .background(background)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        MovoIcon(icon, null, size = MovoSize.iconMedium, tint = if (active) MovoColors.indigoFg else MovoColors.textPrimary)
    }
}

private fun dictationStyle(
    pending: Pair<Int, Int>?,
    confirming: Pair<Int, Int>?,
    progress: Float,
) = androidx.compose.foundation.text.input.OutputTransformation {
    confirming?.let { range ->
        val start = range.first.coerceIn(0, length)
        val end = range.second.coerceIn(start, length)
        val color = androidx.compose.ui.graphics.lerp(MovoColors.textTertiary, MovoColors.textPrimary, progress.coerceIn(0f, 1f))
        if (end > start) addStyle(androidx.compose.ui.text.SpanStyle(color = color), start, end)
    }
    pending?.let { range ->
        val start = range.first.coerceIn(0, length)
        val end = range.second.coerceIn(start, length)
        if (end > start) addStyle(androidx.compose.ui.text.SpanStyle(color = MovoColors.textTertiary), start, end)
    }
}

/** 刚确认的一段模糊 2 → 0（Q2「语音字幕由未确认变确认」）：这段单独放进模糊图层绘制，其余文字照常。 */
private fun Modifier.dictationConfirmBlur(
    range: () -> Pair<Int, Int>?,
    progress: () -> Float,
    layout: () -> androidx.compose.ui.text.TextLayoutResult?,
    scroll: () -> Int,
): Modifier = drawWithCache {
    val layer = obtainGraphicsLayer()
    val blurMax = 2.dp.toPx()
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
        val rangePath = result.getPathForRange(start, end).apply {
            translate(androidx.compose.ui.geometry.Offset(0f, -scroll().toFloat()))
        }
        val everything = androidx.compose.ui.graphics.Path().apply {
            addRect(androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset.Zero, size))
        }
        val rest = androidx.compose.ui.graphics.Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, everything, rangePath)
        val content = this
        clipPath(rest) { content.drawContent() }
        val radius = blurMax * (1f - p)
        layer.renderEffect = if (radius > 0.05f) {
            androidx.compose.ui.graphics.BlurEffect(radius, radius, androidx.compose.ui.graphics.TileMode.Decal)
        } else {
            null
        }
        layer.record { content.drawContent() }
        clipPath(rangePath) { drawLayer(layer) }
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

/** 思考强度选择保持为单一图标，当前状态仅通过图标颜色表达。 */
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
    val active = effort != ReasoningEffort.OFF
    val menuEnabled = enabled && options.size > 1
    LaunchedEffect(menuEnabled) {
        if (!menuEnabled) showPopup = false
    }
    val popupPositionProvider = remember(popupAnchorTopPx) {
        InputPopupPositionProvider(popupAnchorTopPx)
    }
    val contentColor by animateColorAsState(
        targetValue = if (active) MovoColors.indigoFg else MovoColors.textSecondary,
        animationSpec = MovoMotion.fast(),
        label = "thinking_content",
    )
    // 思考芯片（规范 8 组件表）：高 40；关 = 透明底 + 1 宽 border/strong +「思考」次要色；
    // 默认 = Indigo 浅底 +「思考」；指定档位 = Indigo 浅底，只显示档位名。颜色过渡 `fast`，换档宽度 `standard`。
    val chipShape = RoundedCornerShape(MovoRadius.lg)
    val chipBg by animateColorAsState(
        if (active) MovoColors.indigoBg else Color.Transparent,
        MovoMotion.fast(),
        label = "thinking_bg",
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
                    onClick = { showPopup = true },
                )
                .clip(chipShape)
                // 换档时宽度变化 `standard`（规范 9.3「思考芯片」）。
                .animateContentSize(MovoMotion.standard())
                .background(chipBg)
                .then(
                    if (active) Modifier else Modifier.border(1.dp, MovoColors.borderStrong, chipShape),
                )
                .animateContentSize(MovoMotion.standard())
                .padding(start = 12.dp, end = 16.dp)
                .semantics { contentDescription = "思考强度：${effort.chipLabelPlain()}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MovoIcon(MovoIcons.Atom, null, size = MovoSize.iconSmall, tint = contentColor)
            Spacer(Modifier.width(6.dp))
            Text(chipLabel, style = MovoTypography.labelMedium, color = contentColor, maxLines = 1)
        }
        OverlayListPopup(
            show = showPopup && menuEnabled && popupAnchorTopPx > 0,
            popupPositionProvider = popupPositionProvider,
            alignment = PopupPositionProvider.Align.TopStart,
            onDismissRequest = { showPopup = false },
            maxHeight = popupMaxHeight,
        ) {
            val dismiss = LocalDismissState.current
            ListPopupColumn {
                options.forEachIndexed { index, option ->
                    DropdownImpl(
                        text = option.displayName,
                        optionSize = options.size,
                        isSelected = option == effort,
                        index = index,
                        onSelectedIndexChange = {
                            onEffortChange(option)
                            dismiss?.invoke()
                        },
                    )
                }
            }
        }
    }
}

/**
 * 横向跟随 Chip，竖向则避开整个输入面板；默认下拉定位只会避开 Chip 自身。
 */
@Composable
private fun PendingImageStrip(
    images: List<PendingImageUi>,
    onRemoveImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        images.forEach { image ->
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer),
            ) {
                rememberDataUrlBitmap(image.dataUrl)?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.58f))
                        .clickable { onRemoveImage(image.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.ui_remove_image_089db3),
                        modifier = Modifier.size(11.dp),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

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
    channel == io.github.mangi.eta.agent.voice.session.VoiceChannel.Thinking && statusText.isNotBlank() -> statusText
    channel == io.github.mangi.eta.agent.voice.session.VoiceChannel.Connecting -> stringResource(R.string.movo_voice_hint_connecting)
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
                io.github.mangi.eta.agent.voice.session.VoiceChannel.Speaking ->
                    MovoIcon(MovoIcons.Volume2, null, size = MovoSize.iconSmall, tint = MovoColors.indigoFg)
                io.github.mangi.eta.agent.voice.session.VoiceChannel.Connecting ->
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MovoColors.textTertiary))
                else -> VoiceLevelBars(hearing = channel == io.github.mangi.eta.agent.voice.session.VoiceChannel.Hearing)
            }
        }
        Spacer(Modifier.width(MovoSpacing.sm))
        val transcript = voice.transcript.takeIf { it.isNotBlank() && channel != io.github.mangi.eta.agent.voice.session.VoiceChannel.Speaking }
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
                channel == io.github.mangi.eta.agent.voice.session.VoiceChannel.Speaking -> Text(
                    stringResource(R.string.movo_voice_speaking),
                    style = MovoTypography.inputPlaceholder,
                    color = MovoColors.textSecondary,
                    maxLines = 1,
                )
                channel == io.github.mangi.eta.agent.voice.session.VoiceChannel.Connecting -> Text(
                    stringResource(R.string.movo_voice_connecting),
                    style = MovoTypography.inputPlaceholder,
                    color = MovoColors.textSecondary,
                    maxLines = 1,
                )
                else -> io.github.mangi.eta.ui.components.movo.MovoShimmerText(
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
                io.github.mangi.eta.agent.voice.conversation.VoiceCommitGate.AUTO_SEND_WAIT_MS.toInt(),
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
