package io.github.mangi.eta.ui.screens.voice

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.RecordVoiceOver
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
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.EtaWakeWordController
import io.github.mangi.eta.agent.voice.EtaWakeWordService
import io.github.mangi.eta.agent.voice.WakeListeningState
import io.github.mangi.eta.agent.voice.asr.DoubaoConnectionProbe
import io.github.mangi.eta.agent.voice.wake.WakeKeywordEncoder
import io.github.mangi.eta.data.model.DoubaoCredentialRules
import io.github.mangi.eta.data.model.DoubaoSpeechCredentials
import io.github.mangi.eta.data.model.WakePhraseRules
import io.github.mangi.eta.data.model.WakeSensitivity
import io.github.mangi.eta.data.repository.VoiceSettingsRepository
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.PreferenceIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private enum class VoiceEditor { Phrase, Credentials }

@Composable
internal fun VoiceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val settings by VoiceSettingsRepository.wakeSettingsFlow().collectAsState(initial = null)
    val listeningState by EtaWakeWordService.listeningState.collectAsState()
    val roleManager = remember { context.getSystemService(RoleManager::class.java) }
    var assistantRole by remember { mutableStateOf(roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) }
    var overlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var credentials by remember { mutableStateOf<DoubaoSpeechCredentials?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var editor by rememberSaveable { mutableStateOf<VoiceEditor?>(null) }
    var micGranted by remember { mutableStateOf(EtaWakeWordController.hasMicPermission(context)) }
    var notifications by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var requestedMic by rememberSaveable { mutableStateOf(false) }
    var wakeChanging by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<DoubaoConnectionProbe.Result?>(null) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    var showClear by rememberSaveable { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }

    fun toast(resource: Int) = Toast.makeText(context, context.getString(resource), Toast.LENGTH_SHORT).show()
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
        scope.launch {
            try {
                VoiceSettingsRepository.setWakeEnabled(enabled)
                EtaWakeWordController.refresh(context)
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { toast(R.string.page_save_failed_40525a)
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
        if (granted) setWake(true) else toast(R.string.voice_mic_permission_denied)
    }
    val assistantLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        assistantRole = roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                micGranted = EtaWakeWordController.hasMicPermission(context)
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
            toast(R.string.voice_settings_phrase_saved)
        })
        return
    }
    if (editor == VoiceEditor.Credentials && credentials != null) {
        VoiceCredentialsEditor(credentials!!, onBack = { editor = null }, onSaved = {
            credentials = it
            editor = null
            testResult = null
            toast(R.string.voice_settings_credentials_saved)
        })
        return
    }
    val configured = credentials?.hasUsableAuth() == true
    MiuixScaffoldPage(title = stringResource(R.string.voice_settings_title), onBack = onBack) {
        item(key = "wake") {
            SmallTitle(stringResource(R.string.voice_settings_wake_section))
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.voice_settings_wake_enabled),
                    summary = when {
                        settings == null -> stringResource(R.string.voice_settings_loading)
                        settings?.wakeEnabled != true -> stringResource(R.string.voice_settings_state_off)
                        !micGranted -> stringResource(R.string.voice_settings_mic_permission)
                        else -> when (val state = listeningState) {
                            WakeListeningState.Starting -> stringResource(R.string.voice_settings_state_starting)
                            WakeListeningState.Listening -> stringResource(R.string.voice_settings_state_listening)
                            WakeListeningState.Dictating -> stringResource(R.string.voice_settings_state_paused)
                            WakeListeningState.Stopped -> stringResource(R.string.voice_settings_state_stopped)
                            is WakeListeningState.Failed -> state.message
                        }
                    },
                    checked = settings?.wakeEnabled == true,
                    enabled = settings != null && !wakeChanging,
                    startAction = { PreferenceIcon(Icons.Rounded.RecordVoiceOver) },
                    onCheckedChange = { enabled ->
                        if (enabled && !micGranted) {
                            val activity = context as? Activity
                            if (requestedMic && activity != null &&
                                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)) {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                            } else {
                                requestedMic = true
                                wakeChanging = true
                                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else setWake(enabled)
                    },
                )
                if (!micGranted) {
                    ArrowPreference(
                        title = stringResource(R.string.voice_settings_mic_permission),
                        summary = stringResource(R.string.voice_settings_mic_permission_hint),
                        startAction = { PreferenceIcon(Icons.Rounded.Mic) },
                        onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
                    )
                }
                ArrowPreference(
                    title = stringResource(R.string.voice_settings_wake_phrase),
                    summary = settings?.effectivePhrase() ?: stringResource(R.string.voice_settings_loading),
                    enabled = settings != null && !wakeChanging,
                    startAction = { PreferenceIcon(Icons.Rounded.Edit) },
                    onClick = { editor = VoiceEditor.Phrase },
                )
                val sensitivities = listOf(WakeSensitivity.Low, WakeSensitivity.Medium, WakeSensitivity.High)
                WindowSpinnerPreference(
                    title = stringResource(R.string.voice_settings_sensitivity),
                    summary = stringResource(R.string.voice_settings_sensitivity_hint),
                    items = listOf(R.string.voice_sensitivity_low, R.string.voice_sensitivity_medium, R.string.voice_sensitivity_high)
                        .map { DropdownItem(text = stringResource(it)) },
                    selectedIndex = sensitivities.indexOf(settings?.sensitivity ?: WakeSensitivity.Medium),
                    enabled = settings != null && !wakeChanging,
                    startAction = { PreferenceIcon(Icons.Rounded.GraphicEq) },
                    onSelectedIndexChange = { index ->
                        scope.launch {
                            try {
                                VoiceSettingsRepository.setWakeSensitivity(sensitivities[index])
                                EtaWakeWordController.refresh(context)
                            } catch (cancel: CancellationException) { throw cancel
                            } catch (_: Exception) { toast(R.string.page_save_failed_40525a) }
                        }
                    },
                )
                if (!notifications) {
                    ArrowPreference(
                        title = stringResource(R.string.voice_settings_notifications),
                        summary = stringResource(R.string.voice_settings_notifications_hint),
                        startAction = { PreferenceIcon(Icons.Rounded.Notifications) },
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        },
                    )
                }
                if (!overlayAllowed) ArrowPreference(
                    title = stringResource(R.string.voice_settings_overlay_permission),
                    summary = stringResource(R.string.voice_settings_overlay_hint),
                    onClick = { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) },
                )
                if (!assistantRole && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) ArrowPreference(
                    title = stringResource(R.string.voice_settings_assistant_role),
                    summary = stringResource(R.string.voice_settings_assistant_hint),
                    onClick = { assistantLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)) },
                )
            }
        }
        item(key = "doubao") {
            SmallTitle(stringResource(R.string.voice_settings_doubao_section))
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.voice_settings_configure),
                    summary = when {
                        loadError -> stringResource(R.string.voice_settings_load_failed)
                        credentials == null -> stringResource(R.string.voice_settings_loading)
                        configured -> stringResource(R.string.voice_settings_configured,
                            if (credentials!!.authMode() == DoubaoSpeechCredentials.AuthMode.ApiKey) "Api-Key" else "App-Key + Access-Key")
                        else -> stringResource(R.string.voice_settings_not_configured)
                    },
                    enabled = !clearing && (credentials != null || loadError),
                    startAction = { PreferenceIcon(Icons.Rounded.Key) },
                    onClick = { if (loadError) loadCredentials() else { cancelTest(); editor = VoiceEditor.Credentials } },
                )
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HintText(stringResource(R.string.voice_settings_probe_hint))
                    TextButton(
                        text = stringResource(if (testing) R.string.voice_settings_testing else R.string.voice_settings_test_connection),
                        enabled = configured && !testing && !clearing,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val saved = credentials ?: return@TextButton
                            testing = true
                            testResult = null
                            testJob = scope.launch {
                                try { testResult = withContext(Dispatchers.IO) { io.github.mangi.eta.agent.voice.conversation.DialogConnectionProbe.test(context, saved) }
                                } catch (cancel: CancellationException) { throw cancel
                                } catch (_: Exception) { testResult = DoubaoConnectionProbe.Result.NetworkError
                                } finally { testing = false }
                            }
                        },
                    )
                    testResult?.let { result ->
                        val message = when (result) {
                            DoubaoConnectionProbe.Result.Success -> stringResource(R.string.voice_settings_probe_success)
                            DoubaoConnectionProbe.Result.Timeout -> stringResource(R.string.voice_settings_probe_timeout)
                            DoubaoConnectionProbe.Result.NetworkError -> stringResource(R.string.voice_settings_probe_network)
                            DoubaoConnectionProbe.Result.InvalidResponse -> stringResource(R.string.voice_settings_probe_invalid)
                            is DoubaoConnectionProbe.Result.Rejected -> stringResource(R.string.voice_settings_probe_rejected, result.code)
                            is DoubaoConnectionProbe.Result.InvalidCredentials -> result.message
                        }
                        Text(message, style = MiuixTheme.textStyles.body2,
                            color = if (result == DoubaoConnectionProbe.Result.Success) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error)
                    }
                }
                if (configured) ArrowPreference(
                    title = stringResource(R.string.voice_settings_clear_credentials),
                    enabled = !clearing && !testing,
                    onClick = { showClear = true },
                )
            }
        }
        item(key = "privacy") {
            SmallTitle(stringResource(R.string.voice_settings_privacy_section))
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                HintText(stringResource(R.string.voice_settings_privacy_body), Modifier.padding(16.dp))
            }
        }
    }
    if (showClear) WindowDialog(
        show = true,
        title = stringResource(R.string.voice_settings_clear_credentials),
        summary = stringResource(R.string.voice_settings_clear_confirm),
        onDismissRequest = { if (!clearing) showClear = false },
    ) {
        MiuixDialogActions(
            confirmText = stringResource(R.string.voice_settings_clear_credentials),
            confirmEnabled = !clearing, cancelEnabled = !clearing, destructive = true,
            onCancel = { showClear = false },
            onConfirm = {
                clearing = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { VoiceSettingsRepository.clearDoubaoCredentials() }
                        credentials = DoubaoSpeechCredentials()
                        cancelTest()
                        showClear = false
                        toast(R.string.voice_settings_credentials_cleared)
                    } catch (cancel: CancellationException) { throw cancel
                    } catch (_: Exception) { toast(R.string.page_save_failed_40525a)
                    } finally { clearing = false }
                }
            },
        )
    }
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
                EtaWakeWordController.refresh(context)
                onSaved()
            } catch (cancel: CancellationException) { throw cancel
            } catch (failure: Exception) { error = failure.message ?: context.getString(R.string.page_save_failed_40525a)
            } finally { saving = false }
        }
    }
    BackHandler(onBack = { back() })
    MiuixScaffoldPage(
        title = stringResource(R.string.voice_settings_wake_phrase), onBack = { back() },
        modifier = Modifier.imePadding(),
        actions = {
            TextButton(text = stringResource(if (saving) R.string.voice_settings_saving else R.string.action_save),
                enabled = changed && !saving, onClick = { save(draft.trim()) })
        },
    ) {
        item {
            SmallTitle(stringResource(R.string.voice_settings_current_phrase, savedPhrase))
            Card(Modifier.padding(horizontal = 12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(value = draft, onValueChange = { draft = it; error = null },
                        label = stringResource(R.string.voice_settings_wake_phrase), singleLine = true,
                        enabled = !saving, modifier = Modifier.fillMaxWidth())
                    HintText(stringResource(R.string.voice_settings_phrase_hint))
                    if (WakePhraseRules.isShortPhraseWarning(draft)) HintText(stringResource(R.string.voice_settings_phrase_short_warn))
                    error?.let { Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2) }
                }
            }
        }
        item {
            TextButton(text = stringResource(R.string.voice_settings_restore_default_phrase),
                enabled = !saving && (changed || savedPhrase != WakePhraseRules.DEFAULT),
                onClick = { save(WakePhraseRules.DEFAULT) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp))
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
    val draft = voiceCredentialDraft(useApiKey, apiKey, appKey, accessKey, resourceId, saved.endpoint)
    val original = voiceCredentialDraft(saved.authMode() != DoubaoSpeechCredentials.AuthMode.AppAccessKey,
        saved.apiKey, saved.appKey, saved.accessKey, saved.resourceId, saved.endpoint)
    val changed = draft != original
    fun back() {
        if (!saving) {
            focus.clearFocus()
            val current = voiceCredentialDraft(useApiKey, apiKey, appKey, accessKey, resourceId, saved.endpoint)
            if (current != original) discard = true else onBack()
        }
    }
    BackHandler(onBack = { back() })
    MiuixScaffoldPage(
        title = stringResource(R.string.voice_settings_configure), onBack = { back() },
        modifier = Modifier.imePadding(),
        actions = {
            TextButton(text = stringResource(if (saving) R.string.voice_settings_saving else R.string.action_save),
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
                })
        },
    ) {
        item {
            SmallTitle(stringResource(R.string.voice_settings_auth_mode))
            Card(Modifier.padding(horizontal = 12.dp)) {
                WindowSpinnerPreference(title = stringResource(R.string.voice_settings_auth_mode),
                    items = listOf(DropdownItem("Api-Key"), DropdownItem("App-Key + Access-Key")),
                    selectedIndex = if (useApiKey) 0 else 1, enabled = !saving,
                    onSelectedIndexChange = { useApiKey = it == 0; error = null })
            }
            HintText(stringResource(R.string.voice_settings_auth_hint), Modifier.padding(16.dp))
        }
        if (useApiKey) {
            item(key = "api-key") { VoiceSecretField("Api-Key", apiKey, !saving) { apiKey = it; error = null } }
        } else {
            item(key = "app-key") { VoiceSecretField("App-Key", appKey, !saving) { appKey = it; error = null } }
            item(key = "access-key") { VoiceSecretField("Access-Key", accessKey, !saving) { accessKey = it; error = null } }
        }
        item {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                SwitchPreference(title = stringResource(R.string.voice_settings_advanced),
                    summary = stringResource(R.string.voice_settings_advanced_hint), checked = advanced,
                    onCheckedChange = { advanced = it }, enabled = !saving)
            }
        }
        if (advanced) item {
            Card(Modifier.padding(horizontal = 12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(value = resourceId, onValueChange = { resourceId = it; error = null }, label = "Resource-Id",
                        singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false))
                    HintText(stringResource(R.string.voice_settings_resource_hint, DoubaoSpeechCredentials.DEFAULT_RESOURCE_ID))
                }
            }
        }
        error?.let { message -> item {
            Text(message, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2, modifier = Modifier.padding(16.dp))
        } }
    }
    DiscardVoiceEditsDialog(discard, onKeep = { discard = false }, onDiscard = onBack)
}

@Composable
private fun VoiceSecretField(label: String, value: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    Card(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        TextField(value = value, onValueChange = onValueChange, label = label, singleLine = true, enabled = enabled,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(enabled = enabled, onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(if (visible) R.string.page_hide_bb0e7e else R.string.page_show_71b677))
                }
            }, modifier = Modifier.fillMaxWidth().padding(12.dp))
    }
}

@Composable
private fun DiscardVoiceEditsDialog(show: Boolean, onKeep: () -> Unit, onDiscard: () -> Unit) {
    if (show) WindowDialog(show = true, title = stringResource(R.string.page_unsaved_changes_376474),
        summary = stringResource(R.string.voice_settings_discard_hint), onDismissRequest = onKeep) {
        MiuixDialogActions(confirmText = stringResource(R.string.voice_settings_discard),
            cancelText = stringResource(R.string.voice_settings_keep_editing), onCancel = onKeep, onConfirm = onDiscard)
    }
}

@Composable
private fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}
