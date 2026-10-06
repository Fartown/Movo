package io.github.fartown.movo.tv

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.fartown.movo.agent.voice.MovoAssistantVoiceService
import io.github.fartown.movo.agent.voice.session.VoiceEntry
import io.github.fartown.movo.agent.voice.session.VoiceSessionManager
import io.github.fartown.movo.ui.app.AgentAppSession

class TvMainActivity : ComponentActivity() {
    private var notice by mutableStateOf("")
    private var autoListen = false
    internal var afterHidden: (() -> Unit)? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startVoice()
        else notice = "权限未授予，暂时不能录音。可以重新点击开始语音授权。"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvAppSurfaces.attach(this)
        TvBackHandler.init(this)
        autoListen = intent.getBooleanExtra(MovoAssistantVoiceService.EXTRA_AUTO_LISTEN, false)
        val app = AgentAppSession.get(this)
        setContent { TvTheme { TvHome(app, notice, ::startVoice, { notice = it }) } }
    }
    override fun onResume() {
        super.onResume()
        TvAssistantPermission.restoreIfEnabled(this)
        TvAppSurfaces.visible = true
        TvVoicePanel.refresh()
        AgentAppSession.get(this).refreshRuntimeResults()
        if (autoListen) { autoListen = false; ensureVoiceStarted() }
    }
    override fun onPause() {
        TvAppSurfaces.visible = false
        TvVoicePanel.refresh()
        super.onPause()
    }
    override fun onStop() {
        super.onStop()
        // onPause fires before the outgoing surface has disappeared. Capture only after onStop.
        afterHidden?.invoke(); afterHidden = null
    }
    override fun onDestroy() { TvAppSurfaces.detach(this); super.onDestroy() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(MovoAssistantVoiceService.EXTRA_AUTO_LISTEN, false)) ensureVoiceStarted()
    }
    private fun requiredPermissions(): Array<String> = if (TclPcmInput.supported(this)) arrayOf(
        Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE,
    ) else arrayOf(Manifest.permission.RECORD_AUDIO)

    private fun startVoice() {
        notice = ""
        if (VoiceSessionManager.active) { VoiceSessionManager.end(); return }
        ensureVoiceStarted()
    }

    private fun ensureVoiceStarted() {
        if (VoiceSessionManager.active) return
        notice = ""
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { permissions.launch(missing.toTypedArray()); return }
        VoiceEntry.noticeFor(VoiceEntry.startInPlace(this))?.let { notice = it }
    }
    companion object { const val ACTION_ASSISTANT = "io.github.fartown.movo.tv.ASSISTANT" }
}
