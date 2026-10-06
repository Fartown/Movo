package io.github.fartown.movo.tv

import android.app.Activity
import android.content.Context
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

    /**
     * 语音唤起只出现右下角胶囊（v2：优先语音、不挡节目）；不带语音的唤起（菜单键）才打开可打字的大浮窗。
     * 无障碍没开时返回 false，由调用方退回 Movo 自己的页面。
     */
    override fun showAssistant(context: Context, autoListen: Boolean): Boolean {
        if (!autoListen) return TvConversationOverlay.show(context, false)
        if (io.github.fartown.movo.agent.accessibility.AgentAccessibilityService.current() == null) return false
        if (!io.github.fartown.movo.agent.voice.session.VoiceSessionManager.active) {
            io.github.fartown.movo.agent.voice.session.VoiceEntry.startInPlace(context)
        }
        TvVoicePanel.refresh()
        return true
    }

    /** 打开 Movo 的阅读页看完整回答（「看全文」）；按返回回到节目。 */
    fun openReading(context: Context): Boolean = runCatching {
        context.startActivity(android.content.Intent(context, TvMainActivity::class.java)
            .putExtra(TvMainActivity.EXTRA_PAGE, TvMainActivity.PAGE_READING)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }.isSuccess

    override fun isConversationVisible(target: AgentConversationTarget?): Boolean = visible || TvConversationOverlay.expanded

    override fun hideConversationForDeviceOperation(): Boolean {
        if (!TvConversationOverlay.yieldToDevice()) return false
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
