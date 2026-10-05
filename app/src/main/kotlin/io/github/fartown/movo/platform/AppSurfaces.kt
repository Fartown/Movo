package io.github.fartown.movo.platform

import android.app.Activity
import android.graphics.Rect
import io.github.fartown.movo.agent.runtime.AgentConversationTarget

/** 共享代码要打开或查询的界面；手机是主界面与对话浮层，电视是电视首页与语音面板。 */
internal interface AppSurfaces {
    /** 主界面：执行通知、唤醒词通知、会话交接、浏览器链接等打开的页面。 */
    val mainActivity: Class<out Activity>

    /** 会话界面：语音助手入口与运行结果交接打开的页面。 */
    val conversationActivity: Class<out Activity>

    /** 语音助手入口打开 [conversationActivity] 时使用的 action。 */
    val assistantAction: String

    /** [target] 对应的会话是否正显示在会话界面上。 */
    fun isConversationVisible(target: AgentConversationTarget?): Boolean

    /** Agent 要操作其他应用前让出会话界面；返回 false 表示还没让出，稍后再试。 */
    fun hideConversationForDeviceOperation(): Boolean

    /** 从悬浮球打开会话界面前登记起点（屏幕坐标），用于展开动画。 */
    fun expandConversationFromOrb(orb: Rect)
}
