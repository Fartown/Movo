package io.github.fartown.movo.tv

import android.view.KeyEvent
import io.github.fartown.movo.ui.app.AgentAppSession
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 遥控器按键只在 Movo 工作时才先经过 Movo；平时直接交给电视。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TvKeyFilterTest {
    @Test
    fun keysGoThroughMovoOnlyWhileItIsWorking() {
        assertFalse("空闲：直接交给电视", TvKeyFilter.keysNeeded(false, false, false, false))
        assertTrue("语音会话中", TvKeyFilter.keysNeeded(true, false, false, false))
        assertTrue("任务在执行", TvKeyFilter.keysNeeded(false, true, false, false))
        assertTrue("对话浮窗展开", TvKeyFilter.keysNeeded(false, false, true, false))
        assertTrue("选项小卡显示着", TvKeyFilter.keysNeeded(false, false, false, true))
    }

    @Test
    fun accessibilityConfigDoesNotFilterKeysByDefaultButMayRequestIt() {
        val xml = File("src/tv/res/xml/agent_accessibility_service.xml").readText()
        assertFalse(xml.contains("flagRequestFilterKeyEvents"))
        assertTrue(xml.contains("android:canRequestFilterKeyEvents=\"true\""))
    }

    @Test
    fun aKeyWhileMovoIsIdleNeitherIsConsumedNorBuildsTheSession() {
        assertNull(AgentAppSession.peek())

        val back = TvBackHandler.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        val right = TvBackHandler.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))

        assertFalse(back)
        assertFalse(right)
        assertFalse(TvBackHandler.holdingKey)
        assertEquals("不为一个按键去建会话（要读数据库）", null, AgentAppSession.peek())
    }
}
