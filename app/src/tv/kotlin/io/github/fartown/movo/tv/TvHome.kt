package io.github.fartown.movo.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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

@Composable internal fun TvHome(app: AgentAppState, notice: String, startVoice: () -> Unit, showNotice: (String) -> Unit) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf("home") }
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
            TvButton("设置") { page = "settings" }
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
