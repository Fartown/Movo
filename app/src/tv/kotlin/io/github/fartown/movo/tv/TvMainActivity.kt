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
    private var requestedPage by mutableStateOf<String?>(null)
    private var autoListen = false
    internal var afterHidden: (() -> Unit)? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startVoice()
        else notice = "权限未授予，暂时不能录音。可以重新点击开始语音授权。"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        // 进程被杀后重建时，intent 仍是这个任务最初那次启动的：一次性的启动参数只在全新创建时处理。
        // 10-07 电视上，别人再拉起 Movo 时重放了几十分钟前的调试文字，自己跑了 32 轮任务。
        val fresh = savedInstanceState == null
        if (fresh) debugProfile(intent)
        val createdAt = android.os.SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        TvAppSurfaces.attach(this)
        TvBackHandler.init(this)
        autoListen = fresh && intent.getBooleanExtra(MovoAssistantVoiceService.EXTRA_AUTO_LISTEN, false)
        requestedPage = if (fresh) intent.getStringExtra(EXTRA_PAGE) else null
        val app = AgentAppSession.get(this)
        if (fresh) debugText(intent)
        setContent { TvTheme { TvHome(app, notice, { notice = it }, requestedPage) { requestedPage = null } } }
        // 打开 App 到画出第一帧（黑屏时长）。
        val decor = window.decorView
        decor.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                decor.viewTreeObserver.removeOnPreDrawListener(this)
                decor.post {
                    io.github.fartown.movo.diagnostics.MemoryDiagnostics.record("tv.ui", "first_frame", fields = mapOf(
                        "duration_ms" to android.os.SystemClock.elapsedRealtime() - createdAt))
                }
                return true
            }
        })
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
        intent.getStringExtra(EXTRA_PAGE)?.let { requestedPage = it }
        debugText(intent)
        if (intent.getBooleanExtra(MovoAssistantVoiceService.EXTRA_AUTO_LISTEN, false)) ensureVoiceStarted()
    }
    /**
     * 只在 debug 包里：`am start -n <pkg>/io.github.fartown.movo.tv.TvMainActivity --es io.github.fartown.movo.tv.TEXT "在腾讯视频搜狂飙"`
     * 直接把一句文字交给 Movo 执行，然后退到后台让它操作电视——测工具功能不用对着电视说话。
     */
    private fun debugText(intent: Intent) {
        if (!io.github.fartown.movo.BuildConfig.DEBUG) return
        val text = intent.getStringExtra(EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        intent.removeExtra(EXTRA_TEXT)
        val app = AgentAppSession.get(this)
        if (intent.getBooleanExtra(EXTRA_NEW_CONVERSATION, true)) app.createConversation()
        app.sendCurrentMessage(text)
        io.github.fartown.movo.diagnostics.MemoryDiagnostics.record("tv.debug", "text.sent", fields = mapOf("chars" to text.length))
        moveTaskToBack(true)
    }

    /**
     * 只在 debug 包里：`am start … --ez io.github.fartown.movo.tv.PROFILE true` 从创建界面起采样 8 秒，
     * 写到 files/start.trace（系统 am profile 在电视上被 SELinux 拦住，写不了文件）。
     */
    private fun debugProfile(intent: Intent) {
        if (!io.github.fartown.movo.BuildConfig.DEBUG || !intent.getBooleanExtra(EXTRA_PROFILE, false)) return
        val trace = java.io.File(filesDir, "start.trace").apply { delete() }
        android.os.Debug.startMethodTracingSampling(trace.path, 64 * 1024 * 1024, 1000)
        android.os.Handler(mainLooper).postDelayed({ android.os.Debug.stopMethodTracing() }, 8000)
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
    companion object {
        const val ACTION_ASSISTANT = "io.github.fartown.movo.tv.ASSISTANT"
        /** 打开时直接进入的页面：「看全文」进阅读页。 */
        const val EXTRA_PAGE = "io.github.fartown.movo.tv.PAGE"
        const val PAGE_READING = "reading"
        /** debug 包测试用：直接发一句文字指令。 */
        const val EXTRA_TEXT = "io.github.fartown.movo.tv.TEXT"
        const val EXTRA_NEW_CONVERSATION = "io.github.fartown.movo.tv.NEW_CONVERSATION"
        /** debug 包测试用：采样打开界面的过程。 */
        const val EXTRA_PROFILE = "io.github.fartown.movo.tv.PROFILE"
    }
}
