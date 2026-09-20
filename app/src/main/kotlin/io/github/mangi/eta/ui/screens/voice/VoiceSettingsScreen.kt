package io.github.mangi.eta.ui.screens.voice

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.voice.EtaWakeWordController
import io.github.mangi.eta.agent.voice.asr.DoubaoSaucProtocol
import io.github.mangi.eta.data.model.DoubaoCredentialRules
import io.github.mangi.eta.data.model.DoubaoSpeechCredentials
import io.github.mangi.eta.data.model.WakePhraseRules
import io.github.mangi.eta.data.model.WakeSensitivity
import io.github.mangi.eta.data.repository.VoiceSettingsRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.PreferenceIcon
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun VoiceSettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wakeSettings by VoiceSettingsRepository.wakeSettingsFlow()
        .collectAsState(initial = null)
    var appKey by remember { mutableStateOf("") }
    var accessKey by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var resourceId by remember { mutableStateOf(DoubaoSpeechCredentials.DEFAULT_RESOURCE_ID) }
    var phraseDraft by remember { mutableStateOf(WakePhraseRules.DEFAULT) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var shortWarn by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            EtaWakeWordController.refresh(context)
        } else {
            Toast.makeText(context, context.getString(R.string.voice_mic_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        val creds = withContext(Dispatchers.IO) { VoiceSettingsRepository.loadDoubaoCredentials() }
        appKey = creds.appKey
        accessKey = creds.accessKey
        apiKey = creds.apiKey
        resourceId = creds.resourceId
        phraseDraft = VoiceSettingsRepository.wakeSettings().effectivePhrase()
    }

    val settings = wakeSettings
    MiuixScaffoldPage(
        title = stringResource(R.string.voice_settings_title),
        onBack = onBack,
    ) {
        item(key = "wake") {
            SmallTitle(stringResource(R.string.voice_settings_wake_section))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.voice_settings_wake_enabled),
                    summary = stringResource(R.string.voice_settings_wake_enabled_summary),
                    checked = settings?.wakeEnabled == true,
                    startAction = { PreferenceIcon(icon = Icons.Rounded.RecordVoiceOver) },
                    onCheckedChange = { enabled ->
                        scope.launch {
                            if (enabled) {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!granted) {
                                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    return@launch
                                }
                            }
                            VoiceSettingsRepository.setWakeEnabled(enabled)
                            EtaWakeWordController.refresh(context)
                        }
                    },
                )

                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.voice_settings_wake_phrase),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    TextField(
                        value = phraseDraft,
                        onValueChange = {
                            phraseDraft = it
                            shortWarn = WakePhraseRules.isShortPhraseWarning(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        singleLine = true,
                    )
                    if (shortWarn) {
                        Text(
                            text = stringResource(R.string.voice_settings_phrase_short_warn),
                            color = MiuixTheme.colorScheme.error,
                            style = MiuixTheme.textStyles.body2,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                val saved = VoiceSettingsRepository.setWakePhrase(phraseDraft)
                                phraseDraft = saved
                                if (phraseDraft.trim() != saved && phraseDraft.trim().isNotEmpty()) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.voice_settings_phrase_invalid_fallback),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                EtaWakeWordController.refresh(context)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.voice_settings_save_phrase))
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                phraseDraft = VoiceSettingsRepository.restoreDefaultWakePhrase()
                                shortWarn = false
                                EtaWakeWordController.refresh(context)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.voice_settings_restore_default_phrase))
                    }
                }

                val sensitivityItems = listOf(
                    DropdownItem(text = stringResource(R.string.voice_sensitivity_low)),
                    DropdownItem(text = stringResource(R.string.voice_sensitivity_medium)),
                    DropdownItem(text = stringResource(R.string.voice_sensitivity_high)),
                )
                val selectedIndex = when (settings?.sensitivity) {
                    WakeSensitivity.Low -> 0
                    WakeSensitivity.High -> 2
                    else -> 1
                }
                WindowSpinnerPreference(
                    title = stringResource(R.string.voice_settings_sensitivity),
                    items = sensitivityItems,
                    selectedIndex = selectedIndex,
                    startAction = { PreferenceIcon(icon = Icons.Rounded.GraphicEq) },
                    onSelectedIndexChange = { index ->
                        val value = when (index) {
                            0 -> WakeSensitivity.Low
                            2 -> WakeSensitivity.High
                            else -> WakeSensitivity.Medium
                        }
                        scope.launch {
                            VoiceSettingsRepository.setWakeSensitivity(value)
                            EtaWakeWordController.refresh(context)
                        }
                    },
                )
            }
        }

        item(key = "doubao") {
            SmallTitle(stringResource(R.string.voice_settings_doubao_section))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.voice_settings_doubao_summary),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    TextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = stringResource(R.string.voice_settings_api_key),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = appKey,
                        onValueChange = { appKey = it },
                        label = stringResource(R.string.voice_settings_app_key),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = accessKey,
                        onValueChange = { accessKey = it },
                        label = stringResource(R.string.voice_settings_access_key),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = resourceId,
                        onValueChange = { resourceId = it },
                        label = stringResource(R.string.voice_settings_resource_id),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                val creds = DoubaoSpeechCredentials(
                                    appKey = appKey,
                                    accessKey = accessKey,
                                    apiKey = apiKey,
                                    resourceId = resourceId,
                                )
                                when (val validation = DoubaoCredentialRules.validate(creds)) {
                                    is DoubaoCredentialRules.Validation.Invalid -> {
                                        Toast.makeText(context, validation.message, Toast.LENGTH_SHORT).show()
                                    }
                                    DoubaoCredentialRules.Validation.Ok -> {
                                        withContext(Dispatchers.IO) {
                                            VoiceSettingsRepository.saveDoubaoCredentials(creds)
                                        }
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.voice_settings_credentials_saved),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.voice_settings_save_credentials))
                    }
                    Button(
                        onClick = {
                            if (testing) return@Button
                            scope.launch {
                                testing = true
                                testResult = null
                                val creds = DoubaoSpeechCredentials(
                                    appKey = appKey,
                                    accessKey = accessKey,
                                    apiKey = apiKey,
                                    resourceId = resourceId,
                                ).normalized()
                                val result = withContext(Dispatchers.IO) {
                                    testDoubaoConnection(creds)
                                }
                                testing = false
                                testResult = result
                            }
                        },
                        enabled = !testing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (testing) {
                                stringResource(R.string.voice_settings_testing)
                            } else {
                                stringResource(R.string.voice_settings_test_connection)
                            },
                        )
                    }
                    testResult?.let { message ->
                        Text(
                            text = message,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    ArrowPreference(
                        title = stringResource(R.string.voice_settings_clear_credentials),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.Restore) },
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    VoiceSettingsRepository.clearDoubaoCredentials()
                                }
                                appKey = ""
                                accessKey = ""
                                apiKey = ""
                                resourceId = DoubaoSpeechCredentials.DEFAULT_RESOURCE_ID
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.voice_settings_credentials_cleared),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }
            }
        }

        item(key = "privacy") {
            SmallTitle(stringResource(R.string.voice_settings_privacy_section))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
                Text(
                    text = stringResource(R.string.voice_settings_privacy_body),
                    modifier = Modifier.padding(16.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

private suspend fun testDoubaoConnection(credentials: DoubaoSpeechCredentials): String {
    when (val validation = DoubaoCredentialRules.validate(credentials)) {
        is DoubaoCredentialRules.Validation.Invalid -> return validation.message
        DoubaoCredentialRules.Validation.Ok -> Unit
    }
    val connectId = UUID.randomUUID().toString()
    val builder = Request.Builder()
        .url(credentials.endpoint)
        .header("X-Api-Resource-Id", credentials.resourceId)
        .header("X-Api-Connect-Id", connectId)
        .header("X-Api-Request-Id", connectId)
        .header("X-Api-Sequence", "-1")
    when (credentials.authMode()) {
        DoubaoSpeechCredentials.AuthMode.ApiKey ->
            builder.header("X-Api-Key", credentials.apiKey)
        DoubaoSpeechCredentials.AuthMode.AppAccessKey -> {
            builder.header("X-Api-App-Key", credentials.appKey)
            builder.header("X-Api-Access-Key", credentials.accessKey)
        }
        DoubaoSpeechCredentials.AuthMode.Missing ->
            return "缺少凭证"
    }
    return withTimeoutOrNull(8_000L) {
        suspendCancellableCoroutine { cont ->
            val socket = AgentHttpClient.client.newWebSocket(
                builder.build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val frame = DoubaoSaucProtocol.encodeFullClientRequest(
                            DoubaoSaucProtocol.defaultFullClientJson(),
                        )
                        webSocket.send(frame.toByteString())
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                        if (cont.isActive) {
                            cont.resume("连通成功（已收到服务端帧）")
                        }
                        webSocket.close(1000, "ok")
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (cont.isActive) {
                            cont.resume("连接失败（请检查凭证/网络）")
                        }
                    }
                },
            )
            cont.invokeOnCancellation { socket.cancel() }
        }
    } ?: "连接超时"
}
