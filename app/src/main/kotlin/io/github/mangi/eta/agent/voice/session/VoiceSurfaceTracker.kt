package io.github.mangi.eta.agent.voice.session

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Movo 自己的界面是不是正在前台。
 *
 * 唤醒词命中时据此决定：已经在 Movo 里就地切语音模态，不再盖一层浮层；
 * 不在 Movo 里才打开浮层 Sheet。
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
        io.github.mangi.eta.agent.voice.EtaWakeWordController.refresh(activity)
    }
    override fun onActivityPaused(activity: Activity) { resumed.updateAndGet { (it - 1).coerceAtLeast(0) } }
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
