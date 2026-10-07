package io.github.fartown.movo.agent.tools.ui

import android.content.Context
import io.github.fartown.movo.agent.overlay.AgentHapticFeedback
import io.github.fartown.movo.agent.overlay.GestureIndicator

/**
 * 屏幕动作成功后的手势指示与触感（规范 9.5「点击指示」「长按指示」「滑动指示」）。
 * 重构方案：触感和指示改为执行成功后再播放，所以由工具在送达后调用，派发失败、结果不确定时都不播。
 * 指示画在真实屏幕坐标上（[UiTouch]），不是模型给的 coord_space 坐标。输入文字指示归 ui_input，不在这里。
 */
internal object UiTouchFeedback {
    fun show(context: Context, touch: UiTouch) {
        // 指示和触感只是给用户看的，出错也不影响这一步的结果。
        runCatching {
            when (touch) {
                is UiTouch.Press -> if (touch.holdMs > 0) {
                    AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.LONG_PRESS)
                    GestureIndicator.showLongPress(context, touch.x, touch.y, touch.holdMs)
                } else {
                    AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
                    GestureIndicator.showTap(context, touch.x, touch.y)
                }
                is UiTouch.Drag -> {
                    AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.SWIPE)
                    GestureIndicator.showSwipe(context, touch.x1, touch.y1, touch.x2, touch.y2, touch.durationMs)
                }
            }
        }
    }
}
