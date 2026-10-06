package io.github.fartown.movo.tv

import android.app.Activity
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import io.github.fartown.movo.agent.runtime.AgentConversationTarget
import io.github.fartown.movo.platform.AppSurfaces

/** The TV conversation yields to the controlled application while voice stays in its service. */
internal object TvAppSurfaces : AppSurfaces {
    @Volatile var visible = false
    private var activity = WeakReference<TvMainActivity>(null)
    fun attach(value: TvMainActivity) { activity = WeakReference(value) }
    fun detach(value: TvMainActivity) { if (activity.get() === value) activity.clear() }
    override val mainActivity: Class<out Activity> = TvMainActivity::class.java
    override val conversationActivity: Class<out Activity> = TvMainActivity::class.java
    override val assistantAction: String = TvMainActivity.ACTION_ASSISTANT

    override fun isConversationVisible(target: AgentConversationTarget?): Boolean = visible

    override fun hideConversationForDeviceOperation(): Boolean {
        val current = activity.get() ?: return true
        if (!visible || current.isFinishing) return true
        if (Looper.myLooper() == Looper.getMainLooper()) return current.moveTaskToBack(true)
        val hidden = CountDownLatch(1)
        val success = AtomicBoolean(false)
        Handler(Looper.getMainLooper()).post {
            if (!visible || current.isFinishing) { success.set(true); hidden.countDown() }
            else {
                current.afterHidden = { success.set(true); hidden.countDown() }
                if (!current.moveTaskToBack(true)) { current.afterHidden = null; hidden.countDown() }
            }
        }
        return try { hidden.await(3, TimeUnit.SECONDS) && success.get() }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    }

    override fun expandConversationFromOrb(orb: Rect) = Unit
}
