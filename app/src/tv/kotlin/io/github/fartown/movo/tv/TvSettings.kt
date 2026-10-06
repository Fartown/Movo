package io.github.fartown.movo.tv

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fartown.movo.MovoApp
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import io.github.fartown.movo.data.model.*
import io.github.fartown.movo.data.repository.ProviderRepository
import io.github.fartown.movo.data.repository.RuntimeConfigRepository
import io.github.fartown.movo.data.repository.VoiceSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

@Composable internal fun TvSettings(onBack: () -> Unit, showNotice: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf("menu") }
    var notice by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var base by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var voiceKey by remember { mutableStateOf("") }
    var voiceApp by remember { mutableStateOf("") }
    var voiceAccess by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf(OpenAiEndpointMode.CHAT_COMPLETIONS) }
    var provider by remember { mutableStateOf<OpenAiCompatibleProviderSetting?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        notice = if (grants.values.all { it }) "录音权限已授予" else "录音权限未全部授予，可以重试"
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val selected = RuntimeConfigRepository.selectedProvider()
            val settings = ProviderRepository.settings()
            val current = selected as? OpenAiCompatibleProviderSetting
            val selectedModel = selected?.models?.firstOrNull { it.id == settings.selectedModelId }
            val speech = VoiceSettingsRepository.loadDoubaoCredentials()
            withContext(Dispatchers.Main) {
                provider = current
                base = current?.baseUrl.orEmpty()
                key = current?.apiKey.orEmpty()
                model = selectedModel?.modelId.orEmpty()
                endpoint = current?.endpointMode ?: OpenAiEndpointMode.CHAT_COMPLETIONS
                voiceKey = speech.apiKey; voiceApp = speech.appKey; voiceAccess = speech.accessKey
                loaded = true
            }
        }
    }
    fun back() { if (page == "menu") onBack() else { page = "menu"; notice = "" } }
    BackHandler { back() }
    Column(Modifier.fillMaxSize().background(TvTokens.canvas).padding(horizontal = 64.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TvTitle(when (page) { "model" -> "配置模型"; "voice" -> "配置豆包语音"; "record" -> "录音检测"; "screen" -> "屏幕读取权限"; else -> "设置" })
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (page) {
                "record" -> TvRecordingTestPage(context)
                "screen" -> TvScreenPermissionPage()
                "model" -> {
                    TvHint("OpenAI 兼容接口。修改后保存到此电视；现有其他服务商会保留。")
                    TvField("服务地址", base) { base = it }
                    TvField("模型 ID", model) { model = it }
                    TvField("访问密钥", key, secret = true) { key = it }
                    TvButton(if (endpoint == OpenAiEndpointMode.RESPONSES) "接口：Responses" else "接口：Chat Completions") {
                        endpoint = if (endpoint == OpenAiEndpointMode.RESPONSES) OpenAiEndpointMode.CHAT_COMPLETIONS else OpenAiEndpointMode.RESPONSES
                    }
                    TvButton(if (saving) "正在保存" else "保存模型", primary = true, enabled = loaded && !saving) {
                        scope.launch {
                            saving = true
                            try {
                                val url = URI(base.trim())
                                require(url.scheme in listOf("http", "https") && !url.host.isNullOrBlank() && url.userInfo == null) { "请填写有效的服务地址" }
                                require(model.isNotBlank() && key.isNotBlank()) { "请填写模型 ID 和访问密钥" }
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
                                    withContext(Dispatchers.Main) { provider = next }
                                }
                                notice = "模型配置已保存"
                            } catch (_: Exception) { notice = "保存失败，请检查服务地址、模型 ID 和密钥；原配置仍可检查。" }
                            finally { saving = false }
                        }
                    }
                }
                "voice" -> {
                    TvHint("填写 API Key，或填写 App Key 与 Access Key；API Key 优先。")
                    TvField("API Key", voiceKey, secret = true) { voiceKey = it }
                    TvField("App Key", voiceApp, secret = true) { voiceApp = it }
                    TvField("Access Key", voiceAccess, secret = true) { voiceAccess = it }
                    TvButton("保存语音配置", primary = true, enabled = loaded && !saving) {
                        val credentials = DoubaoSpeechCredentials(apiKey = voiceKey, appKey = voiceApp, accessKey = voiceAccess).normalized()
                        if (!credentials.hasUsableAuth()) notice = "请填写 API Key，或完整的 App Key 与 Access Key"
                        else scope.launch {
                            saving = true
                            notice = withContext(Dispatchers.IO) { runCatching { VoiceSettingsRepository.saveDoubaoCredentials(credentials); "语音配置已保存" }.getOrDefault("保存失败，请重试") }
                            saving = false
                        }
                    }
                }
                else -> {
                    TvButton("配置模型", primary = true) { page = "model" }
                    TvButton("配置豆包语音") { page = "voice" }
                    TvButton("屏幕读取权限") { page = "screen" }
                    TvButton("录音检测") { page = "record" }
                    TvHint(if (TclPcmInput.supported(context)) "拾音来源：TCL 内置远场麦克风" else "拾音来源：系统麦克风")
                    TvButton("授权录音") {
                        permissions.launch(if (TclPcmInput.supported(context)) arrayOf(Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            else arrayOf(Manifest.permission.RECORD_AUDIO))
                    }
                    TvButton(if (TclWakeService.enabled(context)) "关闭小T唤醒接管" else "开启小T唤醒接管") {
                        notice = TclWakeService.toggle(context)
                    }
                    TvHint(TclWakeService.status(context))
                    TvButton("无障碍连接") {
                        notice = if (AgentAccessibilityService.isAvailable()) "无障碍已连接" else {
                            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                                .fold({ "请在系统设置中启用 Movo 无障碍服务" }, { "本机缺少标准无障碍设置页。TCL 还需要允许 Movo 自启动，可使用已授权的 ADB 辅助配置。" })
                        }
                    }
                }
            }
            if (notice.isNotBlank()) TvBody(notice, secondary = true)
        }
        TvButton("返回") { back() }
        TvHint("密钥仅保存在此设备，默认隐藏。")
    }
}

@Composable private fun TvField(label: String, value: String, secret: Boolean = false, changed: (String) -> Unit) {
    OutlinedTextField(value, changed, modifier = Modifier.fillMaxWidth(), label = { TvHint(label) }, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 24.sp),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
}
