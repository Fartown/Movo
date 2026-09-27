package io.github.fartown.movo.agent.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 后台发起 Activity 时临时挂一个 1px 透明浮窗。
 *
 * 系统对「有可见窗口」的应用放行后台启动（logcat 里记为 BAL_ALLOW_VISIBLE_WINDOW），小米「后台弹出界面」
 * 未授权时也认这一条（真机：常驻悬浮球在时放行，悬浮球不在时 `Abort background activity starts`）。
 * 悬浮球关掉或还没出现时，用这个锚点窗口补上；发起后立即移除。优先用无障碍浮层（免悬浮窗权限），
 * 没有无障碍时退回应用浮窗；两者都没有就不挂，照常发起，由调用方的确认逻辑兜底。
 */
internal object BackgroundStartAnchor {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun <T> withVisibleWindow(context: Context, block: () -> T): T {
        val anchor = attach(context)
        try {
            return block()
        } finally {
            anchor?.let { detach(it) }
        }
    }

    private class Anchor(val windowManager: WindowManager, val view: View)

    private fun attach(context: Context): Anchor? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val service = AgentAccessibilityService.current()
        val overlayContext: Context
        val type: Int
        when {
            service != null -> {
                overlayContext = service
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            }
            Settings.canDrawOverlays(context) -> {
                overlayContext = context.applicationContext
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }
            else -> return null
        }
        val windowManager = overlayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return null
        val shown = CountDownLatch(1)
        val anchor = AtomicReference<Anchor?>(null)
        val abandoned = AtomicBoolean(false)
        mainHandler.post {
            if (abandoned.get()) return@post
            val view = View(overlayContext)
            val params = WindowManager.LayoutParams(
                1,
                1,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "MovoStartAnchor"
            }
            runCatching { windowManager.addView(view, params) }
                .onSuccess {
                    val attached = Anchor(windowManager, view)
                    anchor.set(attached)
                    // 等待超时后才挂上的，调用方已经不再持有，立即撤掉。
                    if (abandoned.get()) {
                        detach(attached)
                        return@onSuccess
                    }
                    // 首帧提交后窗口才算可见；系统再同步一次可见状态，留一点余量。
                    view.viewTreeObserver.registerFrameCommitCallback {
                        mainHandler.postDelayed({ shown.countDown() }, VISIBLE_SETTLE_MS)
                    }
                    view.invalidate()
                }
                .onFailure { shown.countDown() }
        }
        if (!shown.await(ATTACH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            // 已挂上但没等到首帧回调：窗口多半已可见，照样交给调用方用完再撤；还没挂上的由主线程自行撤掉。
            abandoned.set(true)
            return anchor.getAndSet(null)
        }
        return anchor.get()
    }

    private fun detach(anchor: Anchor) {
        mainHandler.post { runCatching { anchor.windowManager.removeView(anchor.view) } }
    }

    private const val VISIBLE_SETTLE_MS = 80L
    private const val ATTACH_TIMEOUT_MS = 600L
}
