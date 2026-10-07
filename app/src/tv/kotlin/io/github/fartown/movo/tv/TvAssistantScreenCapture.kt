package io.github.fartown.movo.tv

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import io.github.fartown.movo.platform.DeviceScreenCapture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android 9 supports assistant screenshots even when the OEM removed MediaProjection's UI.
 * The user must select Movo as the system assistant and allow access to screen content.
 * Each request creates a fresh, UI-less assistant session; no microphone or speech is started.
 */
internal object TvAssistantScreenCapture : DeviceScreenCapture {
    // 用到时才创建：纯 JVM 单测加载 FlavorModule 时没有主线程 Looper。
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val nextId = AtomicLong()
    private val requestLock = Any()
    private val stateLock = Any()
    @Volatile private var service: TvScreenAssistantService? = null
    private val connection = MutableStateFlow(false)
    val connected = connection.asStateFlow()
    private var pending: Request? = null

    private class Request(val id: Long) {
        val done = CountDownLatch(1)
        var result = DeviceScreenCapture.Result(failure = "ASSIST_SCREENSHOT_TIMEOUT")
        var session: TvScreenAssistantSession? = null
    }

    override val available: Boolean
        get() = service?.let { isSelected(it) && screenContentAllowed(it) } == true

    /**
     * 截图前先撤下胶囊和对话浮层（[TvVoicePanel.suppressForScreenshot]），并等前台换成别的应用（[prepareObservation]），
     * 所以要求排除 Movo 自己时也能用。语音发起的任务第一张截图会要求排除 Movo；
     * 原来因此绕开这条路线、走电视上没有的无障碍截图，10-07 语音任务里截图全部失败。
     */
    override fun excludes(packages: Set<String>): Boolean = packages.all { it == io.github.fartown.movo.BuildConfig.APPLICATION_ID }

    fun isSelected(context: Context): Boolean = VoiceInteractionService.isActiveService(
        context, ComponentName(context, TvScreenAssistantService::class.java),
    )

    fun screenContentAllowed(context: Context): Boolean =
        Settings.Secure.getInt(context.contentResolver, "assist_structure_enabled", 1) != 0 &&
            Settings.Secure.getInt(context.contentResolver, "assist_screenshot_enabled", 1) != 0

    fun status(context: Context): String = when {
        !isSelected(context) -> "请先将 Movo 设为默认数字助理，并允许读取屏幕内容"
        !screenContentAllowed(context) -> "默认助理已选择，请在系统助理设置中允许屏幕内容和截图"
        service == null -> "系统正在连接 Movo 数字助理"
        else -> "系统助理已连接，可以验证截图"
    }

    fun attach(value: TvScreenAssistantService) {
        service = value
        connection.value = true
        MemoryDiagnostics.record("tv.capture", "assistant.ready")
    }

    fun detach(value: TvScreenAssistantService) {
        if (service !== value) return
        service = null
        connection.value = false
        synchronized(stateLock) { pending?.id }?.let {
            complete(it, DeviceScreenCapture.Result(failure = "ASSISTANT_DISCONNECTED"))
        }
    }

    override fun prepareObservation(): Boolean {
        if (!TvAppSurfaces.hideConversationForDeviceOperation()) return false
        val appPackage = service?.packageName ?: return true
        val accessibility = AgentAccessibilityService.current() ?: return true
        val deadline = SystemClock.elapsedRealtime() + 2000
        var stablePackage: String? = null
        var stableSince = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (Thread.currentThread().isInterrupted) return false
            val current = accessibility.currentPackageName()?.takeIf { it.isNotBlank() && it != appPackage }
            if (current != null && current == stablePackage) {
                if (SystemClock.elapsedRealtime() - stableSince >= 250) return true
            } else { stablePackage = current; stableSince = SystemClock.elapsedRealtime() }
            Thread.sleep(50)
        }
        return false
    }

    override fun capture(): DeviceScreenCapture.Result {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return DeviceScreenCapture.Result(failure = "CAPTURE_REQUIRES_WORKER_THREAD")
        }
        return synchronized(requestLock) {
            if (!available) return@synchronized DeviceScreenCapture.Result(failure = "ASSISTANT_PERMISSION_REQUIRED")
            if (!prepareObservation()) {
                return@synchronized DeviceScreenCapture.Result(failure = "ASSISTANT_WINDOW_STILL_VISIBLE")
            }
            val request = Request(nextId.incrementAndGet())
            synchronized(stateLock) { pending = request }
            val startedAt = SystemClock.elapsedRealtime()
            main.post {
                if (synchronized(stateLock) { pending !== request }) return@post
                val active = service
                if (active == null) complete(request.id, DeviceScreenCapture.Result(failure = "ASSISTANT_DISCONNECTED"))
                else runCatching {
                    TvVoicePanel.suppressForScreenshot(true)
                    TvConversationOverlay.suppressForScreenshot(true)
                    // Let the removed overlay surface disappear before the system samples a frame.
                    main.postDelayed({
                        if (synchronized(stateLock) { pending !== request }) return@postDelayed
                        runCatching {
                            active.showSession(Bundle().apply { putLong(REQUEST_ID, request.id) },
                                VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
                        }.onFailure {
                            complete(request.id, DeviceScreenCapture.Result(failure = "ASSISTANT_REQUEST_REJECTED"))
                        }
                    }, 100)
                }.onFailure {
                    complete(request.id, DeviceScreenCapture.Result(failure = "ASSISTANT_REQUEST_REJECTED"))
                }
            }
            try {
                request.done.await(8, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val result = synchronized(stateLock) {
                if (pending === request) {
                    pending = null
                    // A late callback belongs to this request only and must never satisfy the next.
                    request.session?.let { session -> main.post { session.finish() } }
                }
                request.result
            }
            main.post {
                TvConversationOverlay.suppressForScreenshot(false)
                TvVoicePanel.suppressForScreenshot(false)
            }
            MemoryDiagnostics.record("tv.capture", if (result.bitmap == null) "failed" else "completed",
                fields = mapOf("source" to "system_assistant", "request" to request.id,
                    "duration_ms" to SystemClock.elapsedRealtime() - startedAt,
                    "width" to (result.bitmap?.width ?: 0), "height" to (result.bitmap?.height ?: 0),
                    "failure" to result.failure.orEmpty()))
            result
        }
    }

    fun bindSession(id: Long, session: TvScreenAssistantSession): Boolean = synchronized(stateLock) {
        val request = pending?.takeIf { it.id == id } ?: return@synchronized false
        request.session = session
        true
    }

    fun complete(id: Long, result: DeviceScreenCapture.Result) = synchronized(stateLock) {
        val request = pending?.takeIf { it.id == id }
        if (request == null) result.bitmap?.recycle()
        else {
            pending = null
            request.result = result
            request.done.countDown()
        }
    }

    const val REQUEST_ID = "movo_capture_request"
}

/** Kept in the main process so screenshot ownership never crosses an app Binder transaction. */
class TvScreenAssistantService : VoiceInteractionService() {
    override fun onReady() { super.onReady(); TvAssistantScreenCapture.attach(this) }
    override fun onShutdown() { TvAssistantScreenCapture.detach(this); super.onShutdown() }
    override fun onDestroy() { TvAssistantScreenCapture.detach(this); super.onDestroy() }
}

class TvScreenAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        TvScreenAssistantSession(this, args?.getLong(TvAssistantScreenCapture.REQUEST_ID, 0L) ?: 0L)
}

internal class TvScreenAssistantSession(context: Context, private val requestId: Long) : VoiceInteractionSession(context) {
    override fun onCreate() { super.onCreate(); setUiEnabled(false) }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (requestId == 0L) {
            // A normal system assistant invocation opens text UI; it does not start audio.
            io.github.fartown.movo.agent.voice.session.VoiceEntry.startFromSystemEntry(context, autoListen = false)
            finish()
        } else if (!TvAssistantScreenCapture.bindSession(requestId, this)) finish()
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        // Tear down before releasing the caller, ensuring the next capture gets a new session.
        finish()
        TvAssistantScreenCapture.complete(requestId, DeviceScreenCapture.Result(screenshot,
            if (screenshot == null) "SCREENSHOT_BLOCKED_BY_SYSTEM_OR_APP" else null))
    }
}
