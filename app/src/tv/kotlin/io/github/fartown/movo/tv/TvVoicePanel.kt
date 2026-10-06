package io.github.fartown.movo.tv

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import io.github.fartown.movo.agent.voice.session.VoiceSessionManager
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Noninteractive TV subtitles: the foreground application keeps every remote key except the
 * explicitly intercepted cancellation key. Uses the already-authorized accessibility window. */
internal object TvVoicePanel {
    private val main = Handler(Looper.getMainLooper())
    private var initialized = false
    private var owner: AgentAccessibilityService? = null
    private var window: LinearLayout? = null
    private var title: TextView? = null
    private var subtitle: TextView? = null
    private var screenshotSuppressed = false

    fun suppressForScreenshot(suppress: Boolean) {
        screenshotSuppressed = suppress
        refresh()
    }

    fun init() {
        if (initialized) return
        initialized = true
        AgentAccessibilityService.addInstanceListener(::refresh)
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            VoiceSessionManager.state.collect { refresh() }
        }
    }

    fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(::refresh); return }
        val state = VoiceSessionManager.state.value
        val service = AgentAccessibilityService.current()
        if (screenshotSuppressed || TvAppSurfaces.visible || (!state.active && state.notice.isNullOrBlank()) || service == null) { remove(); return }
        if (owner !== service) remove()
        if (window == null) {
            val density = service.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val view = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(16))
                background = GradientDrawable().apply { setColor(0xFFF4F3EF.toInt()); cornerRadius = dp(20).toFloat(); setStroke(dp(2), 0xFF454CD2.toInt()) }
                elevation = dp(8).toFloat()
            }
            title = TextView(service).apply { textSize = 24f; setTextColor(0xFF151515.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            subtitle = TextView(service).apply { textSize = 20f; setTextColor(0xFF6B6964.toInt()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            view.addView(title); view.addView(subtitle)
            val params = WindowManager.LayoutParams(
                minOf(dp(960), service.resources.displayMetrics.widthPixels - dp(128)), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(40); setTitle("Movo TV 语音状态") }
            try {
                service.getSystemService(WindowManager::class.java).addView(view, params)
                window = view; owner = service
            } catch (_: Exception) {
                title = null; subtitle = null
                MemoryDiagnostics.record("tv.voice", "panel.unavailable")
                return
            }
        }
        title?.text = if (state.active) "Movo · ${state.statusText}" else "Movo · ${state.notice.orEmpty()}"
        subtitle?.text = state.transcript.ifBlank { if (state.active) "返回键取消本轮" else "再次说小T小T，或打开 Movo 继续" }
    }

    private fun remove() {
        val view = window
        if (view != null) runCatching { owner?.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
        window = null; owner = null; title = null; subtitle = null
    }
}
