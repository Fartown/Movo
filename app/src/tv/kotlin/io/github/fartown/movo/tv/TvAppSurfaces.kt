package io.github.fartown.movo.tv

import android.app.Activity
import android.graphics.Rect
import io.github.fartown.movo.agent.runtime.AgentConversationTarget
import io.github.fartown.movo.platform.AppSurfaces

/** 电视：P1 只有占位首页；语音面板在 P2 实现（实施方案 §5.8）。 */
internal object TvAppSurfaces : AppSurfaces {
    override val mainActivity: Class<out Activity> = TvMainActivity::class.java
    override val conversationActivity: Class<out Activity> = TvMainActivity::class.java
    override val assistantAction: String = TvMainActivity.ACTION_ASSISTANT

    override fun isConversationVisible(target: AgentConversationTarget?): Boolean = false

    /** 电视没有需要让出的会话浮层。 */
    override fun hideConversationForDeviceOperation(): Boolean = true

    override fun expandConversationFromOrb(orb: Rect) = Unit
}
