package io.github.fartown.movo.agent.accessibility

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentAppTrackerTest {
    private val ignored = setOf("io.github.fartown.movo", "com.android.systemui", "com.sohu.inputmethod.sogou.xiaomi")

    @After
    fun tearDown() = RecentAppTracker.resetForTest()

    @Test
    fun movoSystemUiAndKeyboardDoNotReplaceTheAppTheUserWasIn() {
        RecentAppTracker.record("com.android.browser", 1_000, ignored::contains)
        RecentAppTracker.record("io.github.fartown.movo", 2_000, ignored::contains)
        RecentAppTracker.record("com.sohu.inputmethod.sogou.xiaomi", 3_000, ignored::contains)
        RecentAppTracker.record("com.android.systemui", 4_000, ignored::contains)

        assertEquals(
            "用户最近在用的其他应用：小米浏览器（com.android.browser，3 分钟前在前台）",
            RecentAppTracker.environmentLine(nowElapsedMillis = 181_000, maxAgeMillis = 30 * 60_000L) { "小米浏览器" },
        )
    }

    @Test
    fun theLatestOtherAppWins() {
        RecentAppTracker.record("com.android.browser", 1_000, ignored::contains)
        RecentAppTracker.record("com.miui.notes", 2_000, ignored::contains)

        assertEquals(
            "用户最近在用的其他应用：com.miui.notes（不到 1 分钟前在前台）",
            RecentAppTracker.environmentLine(nowElapsedMillis = 30_000, maxAgeMillis = 30 * 60_000L) { null },
        )
    }

    @Test
    fun anOldAppOrNoneIsLeftOut() {
        assertEquals("", RecentAppTracker.environmentLine(nowElapsedMillis = 1_000, maxAgeMillis = 60_000L) { "x" })
        RecentAppTracker.record("com.android.browser", 1_000, ignored::contains)
        assertEquals("", RecentAppTracker.environmentLine(nowElapsedMillis = 2_000_000, maxAgeMillis = 60_000L) { "x" })
    }
}
