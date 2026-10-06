package io.github.fartown.movo.tv

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fartown.movo.BuildConfig
import io.github.fartown.movo.MovoApp
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import io.github.fartown.movo.data.model.*
import io.github.fartown.movo.data.repository.ProviderRepository
import io.github.fartown.movo.data.repository.RuntimeConfigRepository
import io.github.fartown.movo.data.repository.VoiceSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/** 模型与豆包语音的当前配置；页面只读它和调用保存，不各自持有副本。 */
@Stable internal class TvConfig {
    var loaded by mutableStateOf(false)
    var providerName by mutableStateOf("")
    var base by mutableStateOf("")
    var model by mutableStateOf("")
    var key by mutableStateOf("")
    var endpoint by mutableStateOf(OpenAiEndpointMode.CHAT_COMPLETIONS)
    var modelUsable by mutableStateOf(false)
    var voiceKey by mutableStateOf("")
    var voiceApp by mutableStateOf("")
    var voiceAccess by mutableStateOf("")
    private var provider: OpenAiCompatibleProviderSetting? = null

    val voiceUsable: Boolean get() = !loaded || credentials().hasUsableAuth()
    val summary: String get() = listOf(providerName, model).filter { it.isNotBlank() }.distinct().joinToString(" · ").ifBlank { "已配置" }

    private fun credentials() = DoubaoSpeechCredentials(apiKey = voiceKey, appKey = voiceApp, accessKey = voiceAccess).normalized()

    suspend fun load() = withContext(Dispatchers.IO) {
        val selected = RuntimeConfigRepository.selectedProvider()
        val settings = ProviderRepository.settings()
        val current = selected as? OpenAiCompatibleProviderSetting
        val selectedModel = selected?.models?.firstOrNull { it.id == settings.selectedModelId }
        val speech = VoiceSettingsRepository.loadDoubaoCredentials()
        val usable = RuntimeConfigRepository.currentRuntimeConfig() != null
        withContext(Dispatchers.Main) {
            provider = current
            providerName = selected?.name.orEmpty()
            base = current?.baseUrl.orEmpty(); key = current?.apiKey.orEmpty()
            model = selectedModel?.modelId.orEmpty()
            endpoint = current?.endpointMode ?: OpenAiEndpointMode.CHAT_COMPLETIONS
            modelUsable = usable
            voiceKey = speech.apiKey; voiceApp = speech.appKey; voiceAccess = speech.accessKey
            loaded = true
        }
    }

    /** 与原设置页同一保存逻辑：OpenAI 兼容接口，更新已选服务商或新建「电视模型」，并选中它。 */
    suspend fun saveModel(): String = try {
        val url = URI(base.trim())
        require(url.scheme in listOf("http", "https") && !url.host.isNullOrBlank() && url.userInfo == null) { "请填写有效的服务地址" }
        require(model.isNotBlank() && key.isNotBlank()) { "还差模型 ID 或访问密钥" }
        withContext(Dispatchers.IO) {
            val old = provider
            val modelId = old?.models?.firstOrNull { it.modelId == model.trim() }?.id ?: ProviderRepository.newId()
            val selected = Model(id = modelId, modelId = model.trim(), displayName = model.trim(), toolCall = true)
            val next = (old ?: OpenAiCompatibleProviderSetting(id = ProviderRepository.newId(), name = "电视模型", baseUrl = ""))
                .copy(baseUrl = base.trim().trimEnd('/'), apiKey = key.trim(), endpointMode = endpoint,
                    models = listOf(selected) + old?.models.orEmpty().filter { it.id != modelId })
            if (old == null) ProviderRepository.addProvider(next) else {
                ProviderRepository.updateProvider(next)
                ProviderRepository.replaceModels(next.id, next.models)
            }
            RuntimeConfigRepository.setSelectedProviderId(next.id)
            RuntimeConfigRepository.setSelectedModelId(modelId)
            RuntimeConfigRepository.syncToRemotePreferences(MovoApp.serviceInstance)
        }
        load()
        "已保存"
    } catch (failure: IllegalArgumentException) { failure.message ?: "保存失败" }
    catch (_: Exception) { "保存失败，请检查服务地址、模型 ID 和访问密钥" }

    suspend fun saveVoice(): String = withContext(Dispatchers.IO) {
        runCatching { VoiceSettingsRepository.saveDoubaoCredentials(credentials()); "已保存" }.getOrDefault("保存失败，请重试")
    }
}

@Composable internal fun rememberTvConfig(): TvConfig {
    val config = remember { TvConfig() }
    LaunchedEffect(Unit) { config.load() }
    return config
}

/** 二级页（Figma 定稿「二级页」：顶栏居中标题、一张卡片、后果写在页脚）。 */
@Composable internal fun TvSettingsPage(page: String, config: TvConfig, status: TvStatus, open: (String) -> Unit, showNotice: (String) -> Unit) {
    when {
        page == PAGE_VOICE -> TvVoicePage(config, status, open)
        page == PAGE_VOICE_KEYS -> TvPage("豆包语音") {
            LaunchedEffect(Unit) { config.load() }
            fun filled(v: String) = if (v.isBlank()) "未填写" else "已填写"
            TvCard(listOf(
                TvRowSpec(R.drawable.tv_ic_key_round, "API Key", value = filled(config.voiceKey)) { open("edit:voice.api") },
                TvRowSpec(R.drawable.tv_ic_key_round, "App Key", value = filled(config.voiceApp)) { open("edit:voice.app") },
                TvRowSpec(R.drawable.tv_ic_key_round, "Access Key", value = filled(config.voiceAccess)) { open("edit:voice.access") },
            ))
            TvFooter("填 API Key，或同时填 App Key 与 Access Key；只保存在这台电视上。")
        }
        page == PAGE_RECORD -> TvPage("录音检测") { TvRecordingTestPage(LocalContext.current) }
        page == PAGE_PERMISSIONS -> TvPermissionsPage(status, open)
        page == PAGE_SCREEN -> TvPage("屏幕读取") { TvScreenPermissionPage() }
        page == PAGE_MODEL -> TvModelPage(config, open)
        page == PAGE_ABOUT -> TvPage("关于") {
            TvCard(listOf(
                TvRowSpec(R.drawable.tv_ic_info, "版本", value = BuildConfig.VERSION_NAME),
                TvRowSpec(R.drawable.tv_ic_tv, "设备", value = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"),
            ))
        }
        page.startsWith("edit:") -> TvEditPage(page.removePrefix("edit:"), config, open)
        else -> LaunchedEffect(page) { open(PAGE_HOME) }
    }
}

@Composable private fun TvVoicePage(config: TvConfig, status: TvStatus, open: (String) -> Unit) {
    val context = LocalContext.current
    var wake by remember { mutableStateOf(TclWakeService.enabled(context)) }
    var notice by remember { mutableStateOf("") }
    TvPage("语音与唤醒") {
        TvCard(listOf(
            TvRowSpec(R.drawable.tv_ic_mic, "「小T小T」唤醒",
                subtitle = if (status.wakeSupported) "开启后由 Movo 回答，电视自带的小T不再响应" else "这台电视不支持",
                switch = wake) { notice = TclWakeService.toggle(context); wake = TclWakeService.enabled(context) },
            TvRowSpec(R.drawable.tv_ic_audio_lines, "豆包语音", value = if (config.voiceUsable) "已配置" else "未配置",
                alert = !config.voiceUsable) { open(PAGE_VOICE_KEYS) },
            TvRowSpec(R.drawable.tv_ic_activity, "录音检测") { open(PAGE_RECORD) },
        ))
        if (notice.isNotBlank()) TvFooter(notice)
    }
}

@Composable private fun TvPermissionsPage(status: TvStatus, open: (String) -> Unit) {
    val context = LocalContext.current
    var notice by remember { mutableStateOf("") }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        notice = if (grants.values.all { it }) "麦克风已允许" else "麦克风未允许，可以再试一次"
    }
    fun openSettings(vararg actions: String, fallback: String, success: String) {
        notice = if (actions.any { action -> runCatching { context.startActivity(Intent(action)); true }.getOrDefault(false) }) success else fallback
    }
    fun on(value: Boolean, yes: String = "已开启", no: String = "未开启") = if (value) yes else no
    TvPage("权限") {
        TvCard(title = "让 Movo 能操作电视", rows = listOf(
            TvRowSpec(R.drawable.tv_ic_pointer, "操作其他应用", subtitle = "读懂界面、帮你点选；系统里叫「无障碍」",
                value = on(status.accessibility), alert = !status.accessibility) {
                if (AgentAccessibilityService.isAvailable()) notice = "已开启"
                else openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS, success = "在系统设置里打开「Movo」",
                    fallback = "这台电视没有无障碍设置页，需要用电脑授权一次")
            },
            TvRowSpec(R.drawable.tv_ic_scan_eye, "屏幕读取", subtitle = "看懂电视画面，回答「这是什么」",
                value = on(status.screen), alert = !status.screen) {
                when {
                    status.screen -> open(PAGE_SCREEN)
                    TvAssistantPermission.canConfigure(context) -> {
                        if (!TvAssistantPermission.enabled(context)) TvAssistantPermission.enable(context)
                        notice = TvAssistantPermission.allowScreenContent(context)
                    }
                    else -> openSettings(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
                        success = "把 Movo 设为默认助理，并允许读取屏幕内容", fallback = "这台电视没有默认助理设置页，需要用电脑授权一次")
                }
            },
            TvRowSpec(R.drawable.tv_ic_circle_play, "播放控制", subtitle = "暂停、快进、换集，并确认是否生效",
                value = on(status.media), alert = !status.media) {
                openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, success = "在列表里打开「Movo 播放控制」",
                    fallback = "这台电视没有这个授权页，需要用电脑授权一次")
            },
            TvRowSpec(R.drawable.tv_ic_mic, "麦克风", subtitle = "听你说话", value = on(status.mic, "已允许", "未允许"), alert = !status.mic) {
                micLauncher.launch(if (TclPcmInput.supported(context)) arrayOf(Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    else arrayOf(Manifest.permission.RECORD_AUDIO))
            },
        ))
        TvFooter(notice.ifBlank { if (TclPcmInput.supported(context)) "这台电视缺少部分系统授权页，首次需要用电脑授权一次；之后日常使用不需要电脑。" else "" })
    }
}

@Composable private fun TvModelPage(config: TvConfig, open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { config.load() }
    TvPage("模型") {
        TvCard(listOf(
            TvRowSpec(R.drawable.tv_ic_link, "服务地址", value = runCatching { URI(config.base).host }.getOrNull() ?: config.base.ifBlank { "未填写" }) { open("edit:model.base") },
            TvRowSpec(R.drawable.tv_ic_sparkles, "模型", value = config.model.ifBlank { "未填写" }) { open("edit:model.id") },
            TvRowSpec(R.drawable.tv_ic_key_round, "访问密钥", value = if (config.key.isBlank()) "未填写" else "已填写") { open("edit:model.key") },
            TvRowSpec(R.drawable.tv_ic_arrows, "接口", value = if (config.endpoint == OpenAiEndpointMode.RESPONSES) "Responses" else "Chat Completions") {
                config.endpoint = if (config.endpoint == OpenAiEndpointMode.RESPONSES) OpenAiEndpointMode.CHAT_COMPLETIONS else OpenAiEndpointMode.RESPONSES
                scope.launch { notice = config.saveModel() }
            },
        ))
        TvFooter(notice.ifBlank { "只保存在这台电视上，密钥默认隐藏。" })
    }
}

/** 单项编辑：一个输入框 + 保存；返回键放弃修改。 */
@Composable private fun TvEditPage(id: String, config: TvConfig, open: (String) -> Unit) {
    data class Field(val title: String, val initial: String, val secret: Boolean, val apply: (String) -> Unit)
    val field = when (id) {
        "model.base" -> Field("服务地址", config.base, false) { config.base = it }
        "model.id" -> Field("模型 ID", config.model, false) { config.model = it }
        "model.key" -> Field("访问密钥", config.key, true) { config.key = it }
        "voice.api" -> Field("API Key", config.voiceKey, true) { config.voiceKey = it }
        "voice.app" -> Field("App Key", config.voiceApp, true) { config.voiceApp = it }
        else -> Field("Access Key", config.voiceAccess, true) { config.voiceAccess = it }
    }
    val parent = if (id.startsWith("voice")) PAGE_VOICE_KEYS else PAGE_MODEL
    val scope = rememberCoroutineScope()
    var text by remember(id) { mutableStateOf(field.initial) }
    var notice by remember { mutableStateOf("") }
    val input = remember { FocusRequester() }
    val save = remember { FocusRequester() }
    LaunchedEffect(id) { runCatching { input.requestFocus() } }
    TvPage(field.title) {
        val shape = RoundedCornerShape(42.dp)
        Box(Modifier.fillMaxWidth().background(TvTokens.surface, shape).border(1.dp, TvTokens.hairline, shape).padding(horizontal = 30.dp, vertical = 24.dp)) {
            if (text.isEmpty()) Text("未填写", fontSize = 24.sp, lineHeight = 36.sp, color = TvTokens.tertiary)
            BasicTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(input).onPreviewKeyEvent {
                if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown) { save.requestFocus(); true } else false
            }, singleLine = true, textStyle = TextStyle(fontSize = 24.sp, lineHeight = 36.sp, color = TvTokens.text),
                cursorBrush = SolidColor(TvTokens.ink),
                visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None)
        }
        if (notice.isNotBlank()) TvFooter(notice)
        Row { TvButton("保存", Modifier.focusRequester(save), primary = true) {
            field.apply(text.trim())
            scope.launch {
                val result = if (parent == PAGE_VOICE_KEYS) config.saveVoice() else config.saveModel()
                if (result == "已保存") open(parent) else notice = result
            }
        } }
    }
}
