package io.github.fartown.movo.agent.voice.session

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/**
 * Movo 的聊天界面是不是正在屏幕上。
 *
 * 系统入口据此决定：聊天页（App 首页会话或浮层 Sheet）可见就地切语音模态，不再盖一层浮层；
 * 否则（包括 Movo 的设置等非聊天页面）打开浮层 Sheet。[appVisible] 只表示有 Activity 处于 resumed。
 * 进程启动时就开始计数（[install]），比 Runtime 服务等后来者注册的回调更早，不会漏掉服务创建前的那次 resume。
 */
internal object VoiceSurfaceTracker : Application.ActivityLifecycleCallbacks {
    private val resumed = AtomicInteger(0)

    /** started 未 stopped（屏幕上可见，含暂停中、退场动画中）的 Movo 页面；只在主线程读写。 */
    private val visible = mutableListOf<WeakReference<Activity>>()

    private val chatHosts = mutableSetOf<Any>()
    fun setChatVisible(owner: Any, visible: Boolean) {
        if (visible) chatHosts.add(owner) else chatHosts.remove(owner)
    }
    val chatVisible: Boolean get() = appVisible && chatHosts.isNotEmpty()

    val appVisible: Boolean get() = resumed.get() > 0

    /**
     * 当前可见的 Movo 页面（started 且还没 stopped）。前台操作前据此判断 Movo 自己的页面是否还挡在前面：
     * 没有就视为入口已关，有就用 Movo 自己的方式（moveTaskToBack）收起，而不是发全局返回。主线程调用。
     */
    fun visibleActivities(): List<Activity> {
        visible.removeAll { it.get() == null }
        return visible.mapNotNull { it.get() }
    }

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) {
        forget(activity)
        visible += WeakReference(activity)
    }
    override fun onActivityResumed(activity: Activity) { resumed.incrementAndGet() }
    override fun onActivityPostResumed(activity: Activity) {
        // Includes the assistant Sheet, not only MainActivity. A background process start
        // must never acquire the microphone before this visible lifecycle boundary.
        io.github.fartown.movo.agent.voice.MovoWakeWordController.refresh(activity)
    }
    override fun onActivityPaused(activity: Activity) { resumed.updateAndGet { (it - 1).coerceAtLeast(0) } }
    override fun onActivityStopped(activity: Activity) = forget(activity)
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = forget(activity)

    private fun forget(activity: Activity) {
        visible.removeAll { it.get().let { tracked -> tracked == null || tracked === activity } }
    }
}
