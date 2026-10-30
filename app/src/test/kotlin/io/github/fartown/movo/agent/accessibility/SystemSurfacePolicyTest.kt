package io.github.fartown.movo.agent.accessibility

import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拉下通知栏、控制中心时悬浮审批卡 / 提问卡先藏起来（定稿 26-5）：只认铺过半屏的系统界面窗口。 */
class SystemSurfacePolicyTest {
    private val screen = 2608
    private val statusBar = SystemSurfacePolicy.Window(AccessibilityWindowInfo.TYPE_SYSTEM, 110, null)
    private val navBar = SystemSurfacePolicy.Window(AccessibilityWindowInfo.TYPE_SYSTEM, 48, null)
    private val app = SystemSurfacePolicy.Window(AccessibilityWindowInfo.TYPE_APPLICATION, screen, null)

    @Test
    fun aPulledDownShadeOrControlCenterCovers() {
        val shade = SystemSurfacePolicy.Window(AccessibilityWindowInfo.TYPE_SYSTEM, screen, "com.android.systemui")
        val controlCenter = SystemSurfacePolicy.Window(AccessibilityWindowInfo.TYPE_SYSTEM, screen, "miui.systemui.plugin")
        assertTrue(SystemSurfacePolicy.covers(listOf(app, statusBar, shade), screen))
        assertTrue(SystemSurfacePolicy.covers(listOf(app, statusBar, controlCenter), screen))
    }

    @Test
    fun barsAppsAndOtherAppsOverlaysDoNotCover() {
        assertFalse(SystemSurfacePolicy.covers(listOf(app, statusBar, navBar), screen))
        // 别的应用铺满屏幕的悬浮窗（护眼滤镜之类）也是系统类型，但不是系统界面。
        val otherOverlay = SystemSurfacePolicy.Window(AccessibilityWindowInfo.TYPE_SYSTEM, screen, "com.example.filter")
        assertFalse(SystemSurfacePolicy.covers(listOf(app, otherOverlay), screen))
        // 拿不到屏幕高度时不藏。
        val shade = SystemSurfacePolicy.Window(AccessibilityWindowInfo.TYPE_SYSTEM, screen, "com.android.systemui")
        assertFalse(SystemSurfacePolicy.covers(listOf(shade), 0))
    }
}
