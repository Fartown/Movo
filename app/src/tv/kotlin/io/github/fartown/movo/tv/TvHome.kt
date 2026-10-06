package io.github.fartown.movo.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fartown.movo.agent.voice.session.VoiceSessionManager
import io.github.fartown.movo.ui.app.AgentAppState
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.SystemNoticeMessageUi
import io.github.fartown.movo.ui.model.UserMessageUi

@Composable internal fun TvHome(
    app: AgentAppState,
    notice: String,
    startVoice: () -> Unit,
    showNotice: (String) -> Unit,
    requestedPage: String? = null,
    consumePage: () -> Unit = {},
) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf("home") }
    LaunchedEffect(requestedPage) { requestedPage?.let { page = it; consumePage() } }
    val voice by VoiceSessionManager.state.collectAsState()
    val startFocus = remember { FocusRequester() }
    BackHandler(page != "home" && !voice.active && !app.voiceRuntimeBusy) { page = "home" }
    BackHandler(voice.active || app.voiceRuntimeBusy) { TvBackHandler.cancel() }
    if (page == "settings") {
        TvSettings(onBack = { page = "home" }, showNotice = showNotice)
        return
    }
    if (page == "text") {
        var input by rememberSaveable { mutableStateOf("") }
        val inputFocus = remember { FocusRequester() }
        val sendFocus = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        LaunchedEffect(Unit) { inputFocus.requestFocus() }
        Column(Modifier.fillMaxSize().background(TvTokens.canvas).padding(64.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            TvTitle("文字对话")
            TvBody("输入问题或电视操作指令，回答显示在对话中。", secondary = true)
            OutlinedTextField(input, { input = it },
                modifier = Modifier.fillMaxWidth().focusRequester(inputFocus).onPreviewKeyEvent {
                    if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown) {
                        if (input.isNotBlank()) sendFocus.requestFocus()
                        else focusManager.moveFocus(FocusDirection.Down)
                        true
                    } else false
                },
                label = { TvHint("输入内容") },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 24.sp),
                singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton("发送", Modifier.focusRequester(sendFocus), primary = true, enabled = input.isNotBlank()) {
                    VoiceSessionManager.end()
                    app.sendCurrentMessage(input)
                    input = ""
                    page = "home"
                }
                TvButton("返回") { page = "home" }
            }
        }
        return
    }
    if (page == "history") {
        Column(Modifier.fillMaxSize().background(TvTokens.canvas).padding(horizontal = 64.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            TvTitle("最近对话")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(app.conversationPaneState.conversations, key = { it.id }) { conversation ->
                    TvButton(conversation.title, Modifier.fillMaxWidth()) { app.selectConversation(conversation.id); page = "home" }
                }
            }
            TvButton("返回首页") { page = "home" }
        }
        return
    }
    if (page == "home") {
        TvStandby(app, voice, notice, startVoice, onOpen = { page = it })
        return
    }
    val messages = app.homeState.messages
    val scroll = rememberLazyListState()
    LaunchedEffect(page) { startFocus.requestFocus() }
    LaunchedEffect(messages.size, voice.transcript) {
        if (scroll.layoutInfo.totalItemsCount > 0) scroll.scrollToItem(scroll.layoutInfo.totalItemsCount - 1)
    }
    Column(Modifier.fillMaxSize().background(TvTokens.canvas).padding(horizontal = 64.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        TvTitle(if (voice.active) voice.statusText.ifBlank { "正在连接语音" } else "Movo")
        LazyColumn(Modifier.weight(1f).fillMaxWidth().background(Color.White, RoundedCornerShape(28.dp)).padding(32.dp),
            state = scroll, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (messages.isEmpty()) item {
                TvTitle("想聊点什么，或让我帮你操作电视？")
                TvBody("按确认键开始说话。可以问问题，也可以打开应用、调节音量。", secondary = true)
            }
            items(messages, key = { it.id }) { message ->
                when (message) {
                    is UserMessageUi -> { TvHint("你说"); TvBody(message.content) }
                    is AgentMessageUi -> { TvHint("Movo"); TvBody(message.content) }
                    is SystemNoticeMessageUi -> TvBody(message.detail ?: message.code.name, secondary = true)
                    else -> Unit
                }
            }
            if (voice.active && voice.transcript.isNotBlank()) item { TvHint("正在识别"); TvBody(voice.transcript) }
        }
        val currentNotice = notice.ifBlank { voice.notice.orEmpty() }
        if (currentNotice.isNotBlank()) TvBody(currentNotice, secondary = true)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton(if (voice.active) "结束语音" else "开始语音", Modifier.focusRequester(startFocus), primary = true, onClick = startVoice)
            TvButton("文字输入") {
                if (TvConversationOverlay.show(context, autoListen = false)) {
                    (context as? android.app.Activity)?.moveTaskToBack(true)
                } else page = "text"
            }
            if (app.voiceRuntimeBusy) TvButton("停止任务") { TvBackHandler.cancel() }
            else TvButton("新对话") { VoiceSessionManager.end(); app.createConversation() }
            TvButton("历史") { page = "history" }
            TvButton("首页") { page = "home" }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TvButton("悬浮助手") {
                if (TvConversationOverlay.show(context, autoListen = false)) {
                    (context as? android.app.Activity)?.moveTaskToBack(true)
                } else showNotice("请先开启 Movo 无障碍连接，再打开悬浮助手。")
            }
            TvHint(if (voice.active) "返回键取消本轮" else "方向键选择 · 确认键进入")
        }
    }
}

@Composable internal fun TvTitle(text: String) = Text(text, color = TvTokens.text, fontSize = 36.sp,
    lineHeight = 48.sp, fontWeight = FontWeight.Medium)
@Composable internal fun TvBody(text: String, secondary: Boolean = false) = Text(text,
    color = if (secondary) TvTokens.secondary else TvTokens.text, fontSize = 24.sp, lineHeight = 36.sp)
@Composable internal fun TvHint(text: String) = Text(text, color = TvTokens.secondary, fontSize = 20.sp, lineHeight = 32.sp)


private val examples = listOf("明天要不要带伞", "打开奇异果", "音量调到 20")

/**
 * 首页（Figma「Movo TV」候选 v2 · E1）：电视默认是语音，首页只是「随时可以说」的待机页。
 * 默认焦点在光球上，按确认开始说话；示例说法按确认直接发出；对话内容在「对话记录」里看。
 */
@Composable private fun TvStandby(
    app: AgentAppState,
    voice: io.github.fartown.movo.agent.voice.session.VoiceSessionUiState,
    notice: String,
    startVoice: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val orbFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { orbFocus.requestFocus() }
    val answer = app.homeState.messages.lastOrNull { it is AgentMessageUi } as? AgentMessageUi
    val ring = when (voice.channel) {
        io.github.fartown.movo.agent.voice.session.VoiceChannel.Off -> TvOrbRing.None
        io.github.fartown.movo.agent.voice.session.VoiceChannel.Thinking -> TvOrbRing.Working
        io.github.fartown.movo.agent.voice.session.VoiceChannel.Speaking -> TvOrbRing.Speaking
        else -> TvOrbRing.Listening
    }
    Box(Modifier.fillMaxSize().background(TvTokens.canvas).padding(horizontal = 64.dp, vertical = 36.dp)) {
        Column(Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 56.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            TvOrbButton(144.dp, ring, Modifier.focusRequester(orbFocus), onClick = startVoice)
            TvTitle(if (voice.active) voice.statusText.ifBlank { "我在，请说" } else "说「小T小T」，或按确认键开始")
            val line = when {
                voice.active && voice.transcript.isNotBlank() -> voice.transcript
                voice.active -> answer?.content?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
                    ?: "可以问问题，也可以让我操作电视"
                else -> "可以问问题，也可以让我操作电视"
            }
            TvBody(line, secondary = true)
            if (!voice.active) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                examples.forEach { example ->
                    TvButton("「$example」") { VoiceSessionManager.end(); app.sendCurrentMessage(example); onOpen("conversation") }
                }
            }
            val current = notice.ifBlank { voice.notice.orEmpty() }
            if (current.isNotBlank()) TvBody(current, secondary = true)
        }
        Row(Modifier.align(androidx.compose.ui.Alignment.BottomStart), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton("对话记录") { onOpen("conversation") }
            TvButton("设置") { onOpen("settings") }
        }
        val connected = io.github.fartown.movo.agent.accessibility.AgentAccessibilityService.isAvailable()
        Box(Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(bottom = 16.dp)) {
            TvHint(if (connected) "操作其他应用：已开通" else "操作其他应用：未开通，可在设置里开通")
        }
    }
}

/** 可获焦的光球按钮：获焦时靛蓝外圈（与电视按钮的焦点态一致）。 */
@Composable internal fun TvOrbButton(size: androidx.compose.ui.unit.Dp, ring: TvOrbRing, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val sweep = if (ring == TvOrbRing.Working) {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "orb").animateFloat(
            0f, 360f, androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.LinearEasing)), label = "sweep").value
    } else 0f
    // 焦点只用光球自己的靛蓝外圈表示；默认点击效果会在整块方形区域上盖一层底色，和圆形光球不搭。
    androidx.compose.foundation.Canvas(modifier.size(size + 24.dp)
        .onFocusChanged { focused = it.isFocused }
        .clip(androidx.compose.foundation.shape.CircleShape)
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null, onClick = onClick)) {
        drawIntoCanvas { canvas ->
            TvOrbPainter.draw(canvas.nativeCanvas, center.x, center.y, size.toPx(),
                if (focused) TvOrbRing.Focused else ring, 6.dp.toPx(), sweep, density)
        }
    }
}
