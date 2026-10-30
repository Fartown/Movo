package io.github.fartown.movo.agent.accessibility

import android.view.accessibility.AccessibilityWindowInfo

/**
 * 系统界面（通知栏、控制中心、关机菜单等）是不是铺在屏幕上（定稿 26-5）。悬浮审批卡 / 提问卡是无障碍浮窗，层级比通知栏高，
 * 这时要先藏起来，否则通知栏被卡片和遮罩盖住、点不着。判断：系统界面的窗口（type = TYPE_SYSTEM，包是系统界面）高度过半屏；
 * 状态栏、导航栏只是窄条，不算。锁屏界面也是这样一个窗口，由调用方另外按锁屏处理。
 */
internal object SystemSurfacePolicy {
    data class Window(val type: Int, val height: Int, val packageName: String?)

    /** 系统界面的包：原生 SystemUI，以及小米 HyperOS 控制中心所在的插件包。 */
    val SYSTEM_UI_PACKAGES = setOf("com.android.systemui", "miui.systemui.plugin")

    /** 先按类型和高度筛（不用取窗口根节点），只有过半屏的系统窗口才需要再看包名。 */
    fun isCandidate(type: Int, height: Int, screenHeight: Int): Boolean =
        screenHeight > 0 && type == AccessibilityWindowInfo.TYPE_SYSTEM && height * 2 >= screenHeight

    fun covers(windows: List<Window>, screenHeight: Int): Boolean = windows.any { window ->
        isCandidate(window.type, window.height, screenHeight) && window.packageName in SYSTEM_UI_PACKAGES
    }
}
