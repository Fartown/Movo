package io.github.fartown.movo.tv

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.fartown.movo.BuildConfig
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import io.github.fartown.movo.agent.voice.session.VoiceChannel
import io.github.fartown.movo.agent.voice.session.VoiceSessionManager
import io.github.fartown.movo.ui.app.AgentAppState
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.UserMessageUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 电视 App（Figma「Movo TV」· 电视 App 只做基本配置，2026-10-06 定稿，焦点 F-a）。
 * 做任务都在语音模式：其他 App 在前台，Movo 只是右下角胶囊。App 自己只有配置清单首页、配置二级页和「看全文」阅读页。
 */
@Composable internal fun TvHome(
    app: AgentAppState,
    notice: String,
    showNotice: (String) -> Unit,
    requestedPage: String? = null,
    consumePage: () -> Unit = {},
) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf(PAGE_HOME) }
    var readingFromVoice by rememberSaveable { mutableStateOf(false) }
    var returnTo by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(requestedPage) {
        requestedPage?.let { page = it; readingFromVoice = it == TvMainActivity.PAGE_READING; consumePage() }
    }
    val voice by VoiceSessionManager.state.collectAsState()
    val config = rememberTvConfig()
    val status = rememberTvStatus(config)
    fun open(next: String) { returnTo = page; page = next }
    fun back() {
        when {
            page == TvMainActivity.PAGE_READING && readingFromVoice -> (context as? Activity)?.finish()
            page.startsWith("edit:") -> page = page.removePrefix("edit:").substringBefore('.').let { if (it == "voice") PAGE_VOICE_KEYS else PAGE_MODEL }
            page == PAGE_VOICE_KEYS || page == PAGE_RECORD -> page = PAGE_VOICE
            page == PAGE_SCREEN -> page = PAGE_PERMISSIONS
            else -> { returnTo = page; page = PAGE_HOME }
        }
    }
    BackHandler(page != PAGE_HOME && !voice.active && !app.voiceRuntimeBusy) { back() }
    BackHandler(voice.active || app.voiceRuntimeBusy) { TvBackHandler.cancel() }
    when {
        page == PAGE_HOME -> TvStatusHome(app, voice, notice, status, config, returnTo, ::open)
        page == TvMainActivity.PAGE_READING -> TvReading(app)
        else -> TvSettingsPage(page, config, status, ::open, showNotice)
    }
}

internal const val PAGE_HOME = "home"
internal const val PAGE_VOICE = "voice"
internal const val PAGE_VOICE_KEYS = "voice_keys"
internal const val PAGE_RECORD = "record"
internal const val PAGE_PERMISSIONS = "permissions"
internal const val PAGE_SCREEN = "screen"
internal const val PAGE_MODEL = "model"
internal const val PAGE_ABOUT = "about"

/** 一项还没配好的东西：属于哪一行、对用户意味着什么。 */
internal data class TvIssue(val page: String, val name: String, val impact: String)

/** 首页和二级页共用的就绪状态。系统设置在 App 外修改，回到前台和每隔 2 秒重新读取。 */
internal class TvStatus(
    val mic: Boolean, val accessibility: Boolean, val screen: Boolean, val media: Boolean,
    val wakeSupported: Boolean, val wake: Boolean, val voiceKeys: Boolean, val model: Boolean,
) {
    val missingPermissions: List<String> get() = buildList {
        if (!mic) add("麦克风"); if (!accessibility) add("操作其他应用"); if (!media) add("播放控制"); if (!screen) add("屏幕读取")
    }
    val issues: List<TvIssue> get() = buildList {
        if (!mic) add(TvIssue(PAGE_PERMISSIONS, "麦克风", "暂时听不到你说话"))
        if (!model) add(TvIssue(PAGE_MODEL, "模型", "暂时没法回答问题"))
        if (!voiceKeys) add(TvIssue(PAGE_VOICE, "豆包语音", "暂时听不到你说话"))
        if (wakeSupported && !wake) add(TvIssue(PAGE_VOICE, "「小T小T」", "说「小T小T」暂时叫不到我"))
        if (!accessibility) add(TvIssue(PAGE_PERMISSIONS, "操作其他应用", "帮你操作电视暂时用不了"))
        if (!media) add(TvIssue(PAGE_PERMISSIONS, "播放控制", "暂停、快进暂时用不了"))
        if (!screen) add(TvIssue(PAGE_PERMISSIONS, "屏幕读取", "看画面暂时用不了"))
    }
}

internal fun readTvStatus(context: Context, config: TvConfig): TvStatus {
    val micPermissions = if (TclPcmInput.supported(context)) listOf(Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE) else listOf(Manifest.permission.RECORD_AUDIO)
    return TvStatus(
        mic = micPermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED },
        accessibility = AgentAccessibilityService.isAvailable(),
        screen = TvAssistantScreenCapture.isSelected(context) && TvAssistantScreenCapture.screenContentAllowed(context),
        media = TvMediaAccess.enabled(context),
        wakeSupported = TclPcmInput.supported(context),
        wake = TclWakeService.enabled(context),
        voiceKeys = config.voiceUsable,
        model = !config.loaded || config.modelUsable,
    )
}

@Composable private fun rememberTvStatus(config: TvConfig): TvStatus {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    // 读 tick 让系统设置的变化每 2 秒反映一次；配置字段是快照状态，保存后立即反映。
    check(tick >= 0)
    return readTvStatus(context, config)
}

/** 首页：一句状态 + 一张清单。语音进行中（无障碍未开、胶囊出不来时）状态行显示语音进度。 */
@Composable private fun TvStatusHome(
    app: AgentAppState,
    voice: io.github.fartown.movo.agent.voice.session.VoiceSessionUiState,
    notice: String,
    status: TvStatus,
    config: TvConfig,
    returnTo: String?,
    open: (String) -> Unit,
) {
    val issues = status.issues
    val (line1, line2) = when {
        voice.active -> voiceLine(voice.channel) to voice.transcript.ifBlank { latestAnswer(app)?.let(::firstLine).orEmpty() }
        notice.isNotBlank() -> (if (issues.isEmpty()) "随时可以叫我" else "还差 ${issues.size} 项就能用") to notice
        issues.isEmpty() -> "随时可以叫我" to (if (status.wakeSupported) "看节目时说「小T小T」" else "看节目时按遥控器语音键")
        else -> "还差 ${issues.size} 项就能用" to issues.first().impact
    }
    val focus = remember { mapOf(PAGE_VOICE to FocusRequester(), PAGE_PERMISSIONS to FocusRequester(), PAGE_MODEL to FocusRequester(), PAGE_ABOUT to FocusRequester()) }
    LaunchedEffect(Unit) { runCatching { focus.getValue(returnTo?.let(::homeRowFor) ?: issues.firstOrNull()?.page ?: PAGE_VOICE).requestFocus() } }
    val missing = status.missingPermissions
    val voiceRow = when {
        !status.voiceKeys -> "豆包语音未配置" to true
        status.wakeSupported && !status.wake -> "「小T小T」未开启" to true
        status.wakeSupported -> "「小T小T」已开启" to false
        else -> "已配置" to false
    }
    Box(Modifier.fillMaxSize().background(TvTokens.canvas), contentAlignment = Alignment.Center) {
        Column(Modifier.width(760.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TvOrbLogo(78.dp)
            Spacer(Modifier.height(12.dp))
            Text(line1, fontSize = 44.sp, lineHeight = 60.sp, fontWeight = FontWeight.Medium, color = TvTokens.text,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line2.isNotBlank()) Text(line2, fontSize = 44.sp, lineHeight = 60.sp, fontWeight = FontWeight.Medium, color = TvTokens.tertiary,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(40.dp))
            TvCard(listOf(
                TvRowSpec(R.drawable.tv_ic_mic, "语音与唤醒", value = voiceRow.first, alert = voiceRow.second,
                    focusRequester = focus[PAGE_VOICE]) { open(PAGE_VOICE) },
                TvRowSpec(if (missing.isEmpty()) R.drawable.tv_ic_shield_check else R.drawable.tv_ic_shield_alert, "权限",
                    value = when (missing.size) { 0 -> "全部已开启"; 1 -> "${missing.single()}未开启"; else -> "${missing.size} 项未开启" },
                    alert = missing.isNotEmpty(), focusRequester = focus[PAGE_PERMISSIONS]) { open(PAGE_PERMISSIONS) },
                TvRowSpec(R.drawable.tv_ic_sparkles, "模型", value = if (status.model) config.summary else "未配置",
                    alert = !status.model, focusRequester = focus[PAGE_MODEL]) { open(PAGE_MODEL) },
                TvRowSpec(R.drawable.tv_ic_info, "关于", value = BuildConfig.VERSION_NAME, focusRequester = focus[PAGE_ABOUT]) { open(PAGE_ABOUT) },
            ))
        }
    }
}

private fun homeRowFor(page: String): String? = when (page) {
    PAGE_VOICE, PAGE_VOICE_KEYS, PAGE_RECORD -> PAGE_VOICE
    PAGE_PERMISSIONS, PAGE_SCREEN -> PAGE_PERMISSIONS
    PAGE_MODEL -> PAGE_MODEL
    PAGE_ABOUT -> PAGE_ABOUT
    else -> if (page.startsWith("edit:voice")) PAGE_VOICE else if (page.startsWith("edit:model")) PAGE_MODEL else null
}

private fun voiceLine(channel: VoiceChannel) = when (channel) {
    VoiceChannel.Connecting -> "正在连接…"
    VoiceChannel.Hearing -> "正在听…"
    VoiceChannel.Thinking -> "正在想…"
    VoiceChannel.Speaking -> "正在回答"
    VoiceChannel.Listening, VoiceChannel.Off -> "我在，请说"
}

private fun latestAnswer(app: AgentAppState): AgentMessageUi? = app.homeState.messages.asReversed()
    .takeWhile { it !is UserMessageUi }.firstOrNull { it is AgentMessageUi } as? AgentMessageUi

private fun firstLine(answer: AgentMessageUi): String = answer.content.lines()
    .map { it.trim().trimStart('#', '>', '-', '*', ' ').replace("**", "").replace("`", "").trim() }
    .firstOrNull { it.isNotBlank() }.orEmpty()

private val numbered = Regex("""^\s*(\d{1,2})\s*[.、．)）]\s*(.+)$""")

/** 「看全文」：标题是你的问题，正文按手机回答排版（无气泡、编号次要色），方向键翻看，按返回回到节目。 */
@Composable private fun TvReading(app: AgentAppState) {
    val messages = app.homeState.messages
    val question = (messages.lastOrNull { it is UserMessageUi } as? UserMessageUi)?.content?.lineSequence()?.firstOrNull().orEmpty()
    val answer = latestAnswer(app)?.content.orEmpty()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(TvTokens.canvas)) {
        Box(Modifier.fillMaxWidth().height(84.dp), contentAlignment = Alignment.Center) {
            Text(question.ifBlank { "回答" }, Modifier.widthIn(max = 880.dp), fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium,
                color = TvTokens.text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        Column(Modifier.align(Alignment.CenterHorizontally).width(880.dp).weight(1f)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val step = when (event.key) { Key.DirectionDown -> 240f; Key.DirectionUp -> -240f; else -> return@onPreviewKeyEvent false }
                scope.launch { scroll.animateScrollBy(step) }; true
            }
            .verticalScroll(scroll).padding(top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (answer.isBlank()) Text("还没有回答", fontSize = 24.sp, lineHeight = 39.sp, color = TvTokens.secondary)
            answer.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.forEach { raw ->
                val line = raw.trim().trimStart('#', '>').trim().replace("**", "").replace("`", "")
                val match = numbered.find(line)
                val bullet = line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ")
                when {
                    match != null -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${match.groupValues[1]}.", Modifier.width(27.dp), fontSize = 24.sp, lineHeight = 39.sp, color = TvTokens.secondary)
                        Text(match.groupValues[2], fontSize = 24.sp, lineHeight = 39.sp, color = TvTokens.text)
                    }
                    bullet -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("•", Modifier.width(27.dp), fontSize = 24.sp, lineHeight = 39.sp, color = TvTokens.secondary)
                        Text(line.drop(2), fontSize = 24.sp, lineHeight = 39.sp, color = TvTokens.text)
                    }
                    else -> Text(line, fontSize = 24.sp, lineHeight = 39.sp, color = TvTokens.text)
                }
            }
        }
        Text("方向键上下翻看 · 按返回回到节目", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 36.dp, top = 12.dp),
            fontSize = 20.sp, lineHeight = 30.sp, color = TvTokens.tertiary)
    }
}
