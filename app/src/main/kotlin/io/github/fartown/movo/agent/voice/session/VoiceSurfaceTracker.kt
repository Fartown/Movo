package io.github.fartown.movo.agent.voice.session

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Movo 的聊天界面是不是正在屏幕上。
 *
 * 系统入口据此决定：聊天页（App 首页会话或浮层 Sheet）可见就地切语音模态，不再盖一层浮层；
 * 否则（包括 Movo 的设置等非聊天页面）打开浮层 Sheet。[appVisible] 只表示有 Activity 处于 resumed。
 */
internal object VoiceSurfaceTracker : Application.ActivityLifecycleCallbacks {
    private val resumed = AtomicInteger(0)

    private val chatHosts = mutableSetOf<Any>()
    fun setChatVisible(owner: Any, visible: Boolean) {
        if (visible) chatHosts.add(owner) else chatHosts.remove(owner)
    }
    val chatVisible: Boolean get() = appVisible && chatHosts.isNotEmpty()

    val appVisible: Boolean get() = resumed.get() > 0

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) { resumed.incrementAndGet() }
    override fun onActivityPostResumed(activity: Activity) {
        // Includes the assistant Sheet, not only MainActivity. A background process start
        // must never acquire the microphone before this visible lifecycle boundary.
        io.github.fartown.movo.agent.voice.MovoWakeWordController.refresh(activity)
    }
    override fun onActivityPaused(activity: Activity) { resumed.updateAndGet { (it - 1).coerceAtLeast(0) } }
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
