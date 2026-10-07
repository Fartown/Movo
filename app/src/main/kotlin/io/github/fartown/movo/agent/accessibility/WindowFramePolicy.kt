package io.github.fartown.movo.agent.accessibility

import android.view.accessibility.AccessibilityEvent

/**
 * 哪些无障碍事件算「前台窗口换了」（坐标动作的坐标系可能不对了，见 ui_tap / ui_swipe 的坐标绑定）。
 * 只看 TYPE_WINDOW_STATE_CHANGED（新 Activity、对话框、弹出菜单、输入法等窗口出现）；内容刷新、滚动、文字变化
 * 是别的事件，本来就不算。另外两种 TYPE_WINDOW_STATE_CHANGED 也不算：
 * - Movo 自己的窗口（悬浮球、展开卡、手势指示）：它们不改变目标应用的坐标，而且每一步之后都会动；
 * - 同一窗口里的面板变化（contentChangeTypes 带 PANE_*）：和内容刷新一样，窗口本身没换。
 */
internal object WindowFramePolicy {
    private const val PANE_CHANGES =
        AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE or
            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED or
            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED

    /** 一个 TYPE_WINDOW_STATE_CHANGED 事件是否让前台窗口代际 +1。 */
    fun changesFrame(eventPackage: String?, selfPackage: String, contentChangeTypes: Int): Boolean {
        if (eventPackage != null && eventPackage == selfPackage) return false
        return (contentChangeTypes and PANE_CHANGES) == 0
    }
}
