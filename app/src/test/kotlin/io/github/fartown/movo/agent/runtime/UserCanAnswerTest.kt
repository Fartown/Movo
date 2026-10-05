package io.github.fartown.movo.agent.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 真机 T1-E5：用户发起任务后息屏，审批立即按「无法确认」处理，亮屏后也看不到解锁提示。 */
class UserCanAnswerTest {
    @Test
    fun userRun_waitsEvenWhenScreenIsOff() {
        assertTrue(userCanAnswer(isMonitorOrigin = false) { false })
        assertTrue(userCanAnswer(isMonitorOrigin = false) { true })
    }

    @Test
    fun monitorRun_failsFastOnlyWhenScreenIsOff() {
        assertFalse(userCanAnswer(isMonitorOrigin = true) { false })
        assertTrue(userCanAnswer(isMonitorOrigin = true) { true })
    }
}
