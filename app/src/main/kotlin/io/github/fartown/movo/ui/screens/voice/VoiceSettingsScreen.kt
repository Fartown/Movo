package io.github.fartown.movo.ui.screens.voice

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.voice.MovoWakeWordController
import io.github.fartown.movo.agent.voice.MovoWakeWordService
import io.github.fartown.movo.agent.voice.WakeListeningState
import io.github.fartown.movo.agent.voice.asr.DoubaoConnectionProbe
import io.github.fartown.movo.agent.voice.wake.WakeKeywordEncoder
import io.github.fartown.movo.data.model.DoubaoCredentialRules
import io.github.fartown.movo.data.model.DoubaoSpeechCredentials
import io.github.fartown.movo.data.model.WakeListenScope
import io.github.fartown.movo.data.model.WakePhraseRules
import io.github.fartown.movo.data.model.WakeSensitivity
import io.github.fartown.movo.data.repository.VoiceSettingsRepository
import io.github.fartown.movo.ui.components.movo.CardFooter
import io.github.fartown.movo.ui.components.movo.CardTitle
import io.github.fartown.movo.ui.components.movo.MovoCard
import io.github.fartown.movo.ui.components.movo.MovoChoiceDialog
import io.github.fartown.movo.ui.components.movo.MovoConfirmDialog
import io.github.fartown.movo.ui.components.movo.MovoListPage
import io.github.fartown.movo.ui.components.movo.MovoPillButton
import io.github.fartown.movo.ui.components.movo.RowTrailing
import io.github.fartown.movo.ui.components.movo.SettingsRow
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

private enum class VoiceEditor { Phrase, Credentials }

/**
 * 设置 · 语音与唤醒词（规范 8.7 二级页）：唤醒词卡（开关、麦克风、唤醒词、监听范围、灵敏度）、
 * 系统入口卡（缺通知 / 悬浮窗 / 默认助理时才显示）、语音服务卡（豆包配置、连通测试、清除凭证，页脚写隐私说明）。
 * 结果就地显示在对应行的说明里，不用 Toast（规范 8.11）。
 */
@Composable
internal fun VoiceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val settings by VoiceSettingsRepository.wakeSettingsFlow().collectAsState(initial = null)
    val listeningState by MovoWakeWordService.listeningState.collectAsState()
    val roleManager = remember { context.getSystemService(RoleManager::class.java) }
    var assistantRole by remember { mutableStateOf(roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) }
    var overlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var credentials by remember { mutableStateOf<DoubaoSpeechCredentials?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var editor by rememberSaveable { mutableStateOf<VoiceEditor?>(null) }
    var micGranted by remember { mutableStateOf(MovoWakeWordController.hasMicPermission(context)) }
    var notifications by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var requestedMic by rememberSaveable { mutableStateOf(false) }
    var wakeChanging by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<DoubaoConnectionProbe.Result?>(null) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    var showClear by rememberSaveable { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    // 就地提示（替代原来的 Toast）：写在对应行的说明里，下次操作该行时清除。
    var wakeNotice by remember { mutableStateOf<Int?>(null) }
    var phraseSaved by remember { mutableStateOf(false) }
    var scopeFailed by remember { mutableStateOf(false) }
    var sensitivityFailed by remember { mutableStateOf(false) }
    var clearFailed by remember { mutableStateOf(false) }
    var showScopeDialog by remember { mutableStateOf(false) }
    var showSensitivityDialog by remember { mutableStateOf(false) }

    fun openAppDetails() {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
    fun loadCredentials() {
        scope.launch {
            loadError = false
            try {
                credentials = withContext(Dispatchers.IO) { VoiceSettingsRepository.loadDoubaoCredentials() }
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { loadError = true }
        }
    }
    fun setWake(enabled: Boolean) {
        wakeChanging = true
        wakeNotice = null
        scope.launch {
            try {
                VoiceSettingsRepository.setWakeEnabled(enabled)
                MovoWakeWordController.refresh(context)
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { wakeNotice = R.string.page_save_failed_40525a
            } finally { wakeChanging = false }
        }
    }
    fun cancelTest() {
        testJob?.cancel()
        testJob = null
        testing = false
        testResult = null
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        wakeChanging = false
        if (granted) setWake(true) else wakeNotice = R.string.voice_mic_permission_denied
    }
    val assistantLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        assistantRole = roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                micGranted = MovoWakeWordController.hasMicPermission(context)
                notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
                assistantRole = roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
                overlayAllowed = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { loadCredentials() }

    if (editor == VoiceEditor.Phrase && settings != null) {
        WakePhraseEditor(settings!!.effectivePhrase(), onBack = { editor = null }, onSaved = {
            editor = null
            phraseSaved = true
        })
        return
    }
    if (editor == VoiceEditor.Credentials && credentials != null) {
        VoiceCredentialsEditor(credentials!!, onBack = { editor = null }, onSaved = {
            credentials = it
            editor = null
            testResult = null
        })
        return
    }
    val configured = credentials?.hasUsableAuth() == true
    val wakeEditable = settings != null && !wakeChanging
    val scopes = listOf(WakeListenScope.AppOpen, WakeListenScope.ScreenOn)
    val scopeLabels = listOf(R.string.voice_listen_scope_app_open, R.string.voice_listen_scope_screen_on).map { stringResource(it) }
    val scopeIndex = scopes.indexOf(settings?.listenScope ?: WakeListenScope.AppOpen)
    val sensitivities = listOf(WakeSensitivity.Low, WakeSensitivity.Medium, WakeSensitivity.High)
    val sensitivityLabels = listOf(R.string.voice_sensitivity_low, R.string.voice_sensitivity_medium, R.string.voice_sensitivity_high)
        .map { stringResource(it) }
    val sensitivityIndex = sensitivities.indexOf(settings?.sensitivity ?: WakeSensitivity.Medium)
    val showRoleRow = !assistantRole && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
    val showAccessCard = !notifications || !overlayAllowed || showRoleRow

    MovoListPage(title = stringResource(R.string.voice_settings_title), onBack = onBack) {
        item(key = "wake") {
            MovoCard {
                CardTitle(stringResource(R.string.voice_settings_wake_section))
                SettingsRow(
                    title = stringResource(R.string.movo_voice_wake),
                    subtitle = wakeNotice?.let { stringResource(it) } ?: when {
                        settings == null -> stringResource(R.string.voice_settings_loading)
                        settings?.wakeEnabled != true -> stringResource(R.string.voice_settings_state_off)
                        !micGranted -> stringResource(R.string.voice_settings_mic_permission)
                        else -> when (val state = listeningState) {
                            WakeListeningState.Starting -> stringResource(R.string.voice_settings_state_starting)
                            WakeListeningState.Listening -> stringResource(R.string.voice_settings_state_listening)
                            WakeListeningState.Dictating -> stringResource(R.string.voice_settings_state_paused)
                            WakeListeningState.ScreenOff -> stringResource(R.string.voice_settings_state_screen_off)
                            WakeListeningState.AppHidden -> stringResource(R.string.voice_settings_state_app_hidden)
                            WakeListeningState.Stopped -> stringResource(R.string.voice_settings_state_stopped)
                            is WakeListeningState.Failed -> state.message
                        }
                    },
                    enabled = wakeEditable,
                    trailing = RowTrailing.Switch(settings?.wakeEnabled == true) { enabled ->
                        wakeNotice = null
                        if (enabled && !micGranted) {
                            val activity = context as? Activity
                            if (requestedMic && activity != null &&
                                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)) {
                                openAppDetails()
                            } else {
                                requestedMic = true
                                wakeChanging = true
                                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else setWake(enabled)
                    },
                )
                if (!micGranted) {
                    SettingsRow(
                        title = stringResource(R.string.movo_voice_mic),
                        subtitle = stringResource(R.string.voice_settings_mic_permission_hint),
                        trailing = RowTrailing.External(stringResource(R.string.movo_status_off)),
                        attention = true,
                        onClick = { openAppDetails() },
                    )
                }
                SettingsRow(
                    title = stringResource(R.string.voice_settings_wake_phrase),
                    subtitle = if (phraseSaved) stringResource(R.string.voice_settings_phrase_saved) else null,
                    trailing = RowTrailing.Arrow(settings?.effectivePhrase() ?: stringResource(R.string.voice_settings_loading)),
                    enabled = wakeEditable,
                    onClick = {
                        phraseSaved = false
                        editor = VoiceEditor.Phrase
                    },
                )
                SettingsRow(
                    title = stringResource(R.string.voice_settings_listen_scope),
                    subtitle = if (scopeFailed) stringResource(R.string.page_save_failed_40525a) else null,
                    trailing = RowTrailing.Arrow(scopeLabels[scopeIndex]),
                    enabled = wakeEditable,
                    onClick = { showScopeDialog = true },
                )
                SettingsRow(
                    title = stringResource(R.string.voice_settings_sensitivity),
                    subtitle = if (sensitivityFailed) stringResource(R.string.page_save_failed_40525a) else null,
                    trailing = RowTrailing.Arrow(sensitivityLabels[sensitivityIndex]),
                    enabled = wakeEditable,
                    showDivider = false,
                    onClick = { showSensitivityDialog = true },
                )
                CardFooter(
                    listOf(
                        stringResource(R.string.movo_voice_wake_footer_1),
                        stringResource(R.string.movo_voice_wake_footer_2),
                    ),
                )
            }
        }
        if (showAccessCard) {
            item(key = "access") {
                MovoCard {
                    CardTitle(stringResource(R.string.movo_voice_group_access))
                    if (!notifications) {
                        SettingsRow(
                            title = stringResource(R.string.movo_voice_notifications),
                            subtitle = stringResource(R.string.voice_settings_notifications_hint),
                            trailing = RowTrailing.External(stringResource(R.string.movo_status_off)),
                            attention = true,
                            showDivider = !overlayAllowed || showRoleRow,
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                            },
                        )
                    }
                    if (!overlayAllowed) {
                        SettingsRow(
                            title = stringResource(R.string.movo_voice_overlay),
                            subtitle = stringResource(R.string.voice_settings_overlay_hint),
                            trailing = RowTrailing.External(stringResource(R.string.movo_status_off)),
                            attention = true,
                            showDivider = showRoleRow,
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                            },
                        )
                    }
                    if (showRoleRow) {
                        SettingsRow(
                            title = stringResource(R.string.movo_voice_assistant_role),
                            subtitle = stringResource(R.string.voice_settings_assistant_hint),
                            trailing = RowTrailing.Arrow(stringResource(R.string.movo_status_not_set)),
                            showDivider = false,
                            onClick = { assistantLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)) },
                        )
                    }
                }
            }
        }
        item(key = "doubao") {
            MovoCard {
                CardTitle(stringResource(R.string.voice_settings_doubao_section))
                SettingsRow(
                    title = stringResource(R.string.voice_settings_configure),
                    subtitle = when {
                        loadError -> stringResource(R.string.voice_settings_load_failed)
                        credentials == null -> stringResource(R.string.voice_settings_loading)
                        configured -> stringResource(R.string.voice_settings_configured,
                            if (credentials!!.authMode() == DoubaoSpeechCredentials.AuthMode.ApiKey) "Api-Key" else "App-Key + Access-Key")
                        else -> stringResource(R.string.voice_settings_not_configured)
                    },
                    enabled = !clearing && (credentials != null || loadError),
                    onClick = { if (loadError) loadCredentials() else { cancelTest(); editor = VoiceEditor.Credentials } },
                )
                val probeMessage = testResult?.let { result ->
                    when (result) {
                        DoubaoConnectionProbe.Result.Success -> stringResource(R.string.voice_settings_probe_success)
                        DoubaoConnectionProbe.Result.Timeout -> stringResource(R.string.voice_settings_probe_timeout)
                        DoubaoConnectionProbe.Result.NetworkError -> stringResource(R.string.voice_settings_probe_network)
                        DoubaoConnectionProbe.Result.InvalidResponse -> stringResource(R.string.voice_settings_probe_invalid)
                        is DoubaoConnectionProbe.Result.Rejected -> stringResource(R.string.voice_settings_probe_rejected, result.code)
                        is DoubaoConnectionProbe.Result.InvalidCredentials -> result.message
                    }
                }
                SettingsRow(
                    title = stringResource(R.string.voice_settings_test_connection),
                    subtitle = probeMessage ?: stringResource(R.string.movo_voice_probe_desc),
                    showDivider = configured,
                    trailing = RowTrailing.Custom {
                        MovoPillButton(
                            label = stringResource(if (testing) R.string.voice_settings_testing else R.string.movo_voice_test),
                            enabled = configured && !testing && !clearing,
                            onClick = {
                                val saved = credentials ?: return@MovoPillButton
                                testing = true
                                testResult = null
                                testJob = scope.launch {
                                    try { testResult = withContext(Dispatchers.IO) { io.github.fartown.movo.agent.voice.conversation.DialogConnectionProbe.test(context, saved) }
                                    } catch (cancel: CancellationException) { throw cancel
                                    } catch (_: Exception) { testResult = DoubaoConnectionProbe.Result.NetworkError
                                    } finally { testing = false }
                                }
                            },
                        )
                    },
                )
                if (configured) {
                    SettingsRow(
                        title = stringResource(R.string.voice_settings_clear_credentials),
                        trailing = RowTrailing.None,
                        enabled = !clearing && !testing,
                        showDivider = false,
                        onClick = {
                            clearFailed = false
                            showClear = true
                        },
                    )
                }
                CardFooter(
                    listOf(
                        stringResource(R.string.movo_voice_privacy_footer_1),
                        stringResource(R.string.movo_voice_privacy_footer_2),
                    ),
                )
            }
        }
    }

    MovoChoiceDialog(
        show = showScopeDialog,
        title = stringResource(R.string.voice_settings_listen_scope),
        options = scopeLabels,
        selectedIndex = scopeIndex,
        onSelect = { index ->
            scopeFailed = false
            scope.launch {
                try {
                    VoiceSettingsRepository.setWakeListenScope(scopes[index])
                } catch (cancel: CancellationException) { throw cancel
                } catch (_: Exception) { scopeFailed = true }
            }
        },
        onDismissRequest = { showScopeDialog = false },
    )
    MovoChoiceDialog(
        show = showSensitivityDialog,
        title = stringResource(R.string.voice_settings_sensitivity),
        options = sensitivityLabels,
        selectedIndex = sensitivityIndex,
        onSelect = { index ->
            sensitivityFailed = false
            scope.launch {
                try {
                    VoiceSettingsRepository.setWakeSensitivity(sensitivities[index])
                    MovoWakeWordController.refresh(context)
                } catch (cancel: CancellationException) { throw cancel
                } catch (_: Exception) { sensitivityFailed = true }
            }
        },
        onDismissRequest = { showSensitivityDialog = false },
    )
    MovoConfirmDialog(
        show = showClear,
        title = stringResource(R.string.voice_settings_clear_credentials),
        message = stringResource(R.string.voice_settings_clear_confirm),
        confirmText = stringResource(R.string.voice_settings_clear_credentials),
        destructive = true,
        confirmEnabled = !clearing,
        cancelEnabled = !clearing,
        onDismissRequest = { if (!clearing) showClear = false },
        onConfirm = {
            clearing = true
            clearFailed = false
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { VoiceSettingsRepository.clearDoubaoCredentials() }
                    credentials = DoubaoSpeechCredentials()
                    cancelTest()
                    showClear = false
                } catch (cancel: CancellationException) { throw cancel
                } catch (_: Exception) { clearFailed = true
                } finally { clearing = false }
            }
        },
        extraContent = {
            if (clearFailed) {
                ErrorText(stringResource(R.string.page_save_failed_40525a), Modifier.padding(top = MovoSpacing.sm))
            }
        },
    )
}

@Composable
private fun WakePhraseEditor(savedPhrase: String, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable { mutableStateOf(savedPhrase) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val changed = draft.trim() != savedPhrase
    fun back() {
        if (!saving) {
            focus.clearFocus()
            // Read the current state when invoked, including by a retained toolbar callback.
            if (draft.trim() != savedPhrase) discard = true else onBack()
        }
    }
    fun save(value: String) {
        if (saving) return
        saving = true
        focus.clearFocus()
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    WakeKeywordEncoder.load(context).encode(value)
                    VoiceSettingsRepository.setWakePhrase(value)
                }
                MovoWakeWordController.refresh(context)
                onSaved()
            } catch (cancel: CancellationException) { throw cancel
            } catch (failure: Exception) { error = failure.message ?: context.getString(R.string.page_save_failed_40525a)
            } finally { saving = false }
        }
    }
    BackHandler(onBack = { back() })
    MovoListPage(
        title = stringResource(R.string.voice_settings_wake_phrase),
        onBack = { back() },
        modifier = Modifier.imePadding(),
        actions = {
            SaveAction(saving = saving, enabled = changed && !saving, onClick = { save(draft.trim()) })
        },
    ) {
        item(key = "phrase") {
            MovoCard {
                CardTitle(stringResource(R.string.voice_settings_current_phrase, savedPhrase))
                Column(
                    Modifier.padding(start = MovoSpacing.lg, end = MovoSpacing.lg, top = MovoSpacing.sm, bottom = MovoSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(MovoSpacing.sm),
                ) {
                    TextField(value = draft, onValueChange = { draft = it; error = null },
                        label = stringResource(R.string.voice_settings_wake_phrase), singleLine = true,
                        enabled = !saving, modifier = Modifier.fillMaxWidth())
                    if (WakePhraseRules.isShortPhraseWarning(draft)) {
                        ErrorText(stringResource(R.string.voice_settings_phrase_short_warn), warning = true)
                    }
                    error?.let { ErrorText(it) }
                }
                SettingsRow(
                    title = stringResource(R.string.voice_settings_restore_default_phrase),
                    trailing = RowTrailing.None,
                    enabled = !saving && (changed || savedPhrase != WakePhraseRules.DEFAULT),
                    showDivider = false,
                    onClick = { save(WakePhraseRules.DEFAULT) },
                )
                CardFooter(stringResource(R.string.voice_settings_phrase_hint).lines().filter { it.isNotBlank() })
            }
        }
    }
    DiscardVoiceEditsDialog(discard, onKeep = { discard = false }, onDiscard = onBack)
}

@Composable
private fun VoiceCredentialsEditor(saved: DoubaoSpeechCredentials, onBack: () -> Unit, onSaved: (DoubaoSpeechCredentials) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var useApiKey by rememberSaveable { mutableStateOf(saved.authMode() != DoubaoSpeechCredentials.AuthMode.AppAccessKey) }
    var apiKey by rememberSaveable { mutableStateOf(saved.apiKey) }
    var appKey by rememberSaveable { mutableStateOf(saved.appKey) }
    var accessKey by rememberSaveable { mutableStateOf(saved.accessKey) }
    var resourceId by rememberSaveable { mutableStateOf(saved.resourceId) }
    var advanced by rememberSaveable { mutableStateOf(saved.resourceId != DoubaoSpeechCredentials.DEFAULT_RESOURCE_ID) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var showAuthDialog by remember { mutableStateOf(false) }
    val draft = voiceCredentialDraft(useApiKey, apiKey, appKey, accessKey, resourceId, saved.endpoint)
    val original = voiceCredentialDraft(saved.authMode() != DoubaoSpeechCredentials.AuthMode.AppAccessKey,
        saved.apiKey, saved.appKey, saved.accessKey, saved.resourceId, saved.endpoint)
    val changed = draft != original
    val authModes = listOf("Api-Key", "App-Key + Access-Key")
    fun back() {
        if (!saving) {
            focus.clearFocus()
            val current = voiceCredentialDraft(useApiKey, apiKey, appKey, accessKey, resourceId, saved.endpoint)
            if (current != original) discard = true else onBack()
        }
    }
    BackHandler(onBack = { back() })
    MovoListPage(
        title = stringResource(R.string.voice_settings_configure),
        onBack = { back() },
        modifier = Modifier.imePadding(),
        actions = {
            SaveAction(
                saving = saving,
                enabled = changed && !saving,
                onClick = {
                    focus.clearFocus()
                    val current = voiceCredentialDraft(useApiKey, apiKey, appKey, accessKey, resourceId, saved.endpoint)
                    val validation = DoubaoCredentialRules.validate(current)
                    if (validation is DoubaoCredentialRules.Validation.Invalid) error = validation.message
                    else {
                        saving = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { VoiceSettingsRepository.saveDoubaoCredentials(current) }
                                onSaved(current)
                            } catch (cancel: CancellationException) { throw cancel
                            } catch (_: Exception) { error = context.getString(R.string.page_save_failed_40525a)
                            } finally { saving = false }
                        }
                    }
                },
            )
        },
    ) {
        item(key = "auth-mode") {
            MovoCard {
                CardTitle(stringResource(R.string.voice_settings_auth_mode))
                SettingsRow(
                    title = stringResource(R.string.voice_settings_auth_mode),
                    trailing = RowTrailing.Arrow(authModes[if (useApiKey) 0 else 1]),
                    enabled = !saving,
                    showDivider = false,
                    onClick = { showAuthDialog = true },
                )
                CardFooter(
                    listOf(
                        stringResource(R.string.movo_voice_auth_footer_1),
                        stringResource(R.string.movo_voice_auth_footer_2),
                    ),
                )
            }
        }
        item(key = "credentials") {
            MovoCard {
                CardTitle(stringResource(R.string.movo_voice_group_credentials))
                Column(
                    Modifier.padding(start = MovoSpacing.lg, end = MovoSpacing.lg, top = MovoSpacing.sm, bottom = MovoSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(MovoSpacing.sm),
                ) {
                    if (useApiKey) {
                        VoiceSecretField("Api-Key", apiKey, !saving) { apiKey = it; error = null }
                    } else {
                        VoiceSecretField("App-Key", appKey, !saving) { appKey = it; error = null }
                        VoiceSecretField("Access-Key", accessKey, !saving) { accessKey = it; error = null }
                    }
                    error?.let { ErrorText(it) }
                }
            }
        }
        item(key = "advanced") {
            MovoCard {
                CardTitle(stringResource(R.string.movo_voice_group_advanced))
                SettingsRow(
                    title = stringResource(R.string.voice_settings_advanced),
                    subtitle = stringResource(R.string.voice_settings_advanced_hint),
                    trailing = RowTrailing.Switch(advanced) { advanced = it },
                    enabled = !saving,
                    showDivider = false,
                )
                if (advanced) {
                    TextField(value = resourceId, onValueChange = { resourceId = it; error = null }, label = "Resource-Id",
                        singleLine = true, enabled = !saving,
                        modifier = Modifier.fillMaxWidth().padding(start = MovoSpacing.lg, end = MovoSpacing.lg, bottom = MovoSpacing.md),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false))
                    CardFooter(listOf(stringResource(R.string.voice_settings_resource_hint, DoubaoSpeechCredentials.DEFAULT_RESOURCE_ID)))
                }
            }
        }
    }
    MovoChoiceDialog(
        show = showAuthDialog,
        title = stringResource(R.string.voice_settings_auth_mode),
        options = authModes,
        selectedIndex = if (useApiKey) 0 else 1,
        onSelect = { useApiKey = it == 0; error = null },
        onDismissRequest = { showAuthDialog = false },
    )
    DiscardVoiceEditsDialog(discard, onKeep = { discard = false }, onDiscard = onBack)
}

/** 编辑页顶栏右侧的「保存」：32 高主操作胶囊，右缘对齐边距线 20（顶栏右内边距 6 + 14）。 */
@Composable
private fun SaveAction(saving: Boolean, enabled: Boolean, onClick: () -> Unit) {
    MovoPillButton(
        label = stringResource(if (saving) R.string.voice_settings_saving else R.string.action_save),
        onClick = onClick,
        enabled = enabled,
        primary = true,
        modifier = Modifier.padding(end = MovoSpacing.md + MovoSpacing.xxs),
    )
}

@Composable
private fun VoiceSecretField(label: String, value: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    TextField(value = value, onValueChange = onValueChange, label = label, singleLine = true, enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            // 缺 Lucide eye-off，暂用 Material 图标。
            IconButton(enabled = enabled, onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = stringResource(if (visible) R.string.page_hide_bb0e7e else R.string.page_show_71b677),
                    tint = MovoColors.textSecondary)
            }
        }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun DiscardVoiceEditsDialog(show: Boolean, onKeep: () -> Unit, onDiscard: () -> Unit) {
    MovoConfirmDialog(
        show = show,
        title = stringResource(R.string.page_unsaved_changes_376474),
        message = stringResource(R.string.voice_settings_discard_hint),
        confirmText = stringResource(R.string.voice_settings_discard),
        cancelText = stringResource(R.string.voice_settings_keep_editing),
        onConfirm = onDiscard,
        onDismissRequest = onKeep,
    )
}

/** 就地错误 / 警示：13 Regular，Rose 色（文字本身说明问题，颜色不是唯一信号）。 */
@Composable
private fun ErrorText(text: String, modifier: Modifier = Modifier, warning: Boolean = false) {
    Text(
        text,
        modifier = modifier,
        style = MovoTypography.labelRegular,
        color = if (warning) MovoColors.textSecondary else MovoColors.roseFg,
    )
}
