package io.github.fartown.movo.flavor

import android.app.Activity
import android.graphics.Rect
import io.github.fartown.movo.agent.runtime.AgentConversationTarget
import io.github.fartown.movo.platform.AppSurfaces
import io.github.fartown.movo.ui.AgentConversationSheetActivity
import io.github.fartown.movo.ui.MainActivity

/** 手机：主界面 + 对话浮层。 */
internal object PhoneAppSurfaces : AppSurfaces {
    override val mainActivity: Class<out Activity> = MainActivity::class.java
    override val conversationActivity: Class<out Activity> = AgentConversationSheetActivity::class.java
    override val assistantAction: String = AgentConversationSheetActivity.ACTION_ASSISTANT

    override fun isConversationVisible(target: AgentConversationTarget?): Boolean =
        AgentConversationSheetActivity.isConversationVisible(target)

    override fun hideConversationForDeviceOperation(): Boolean =
        AgentConversationSheetActivity.hideForDeviceOperation()

    override fun expandConversationFromOrb(orb: Rect) {
        AgentConversationSheetActivity.expandFromOrb(orb)
    }
}
