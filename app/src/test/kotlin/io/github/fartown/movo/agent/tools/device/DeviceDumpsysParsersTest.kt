package io.github.fartown.movo.agent.tools.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** dumpsys window / activity / diskstats 纯解析函数的单测：喂真实 dumpsys 样例文本断言解析结果。 */
@RunWith(RobolectricTestRunner::class)
class DeviceDumpsysParsersTest {

    // 取自 `dumpsys window` 的焦点行。
    private val windowDump = """
        WINDOW MANAGER WINDOWS (dumpsys window windows)
          Window #0 Window{a1b2c3 u0 com.tencent.mm/com.tencent.mm.ui.LauncherUI}:
            mOwnerUid=10188 ...
          mCurrentFocus=Window{a1b2c3 u0 com.tencent.mm/com.tencent.mm.ui.LauncherUI}
          mFocusedApp=ActivityRecord{d4e5f6 u0 com.tencent.mm/.ui.LauncherUI t42}
    """.trimIndent()

    // 取自 `dumpsys activity activities`，没有 mCurrentFocus，只有 resumed。
    private val activityDump = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
          Display #0 (activities from top to bottom):
            Stack #1:
              mResumedActivity: ActivityRecord{112233 u0 com.android.settings/.Settings t88}
              topResumedActivity=ActivityRecord{445566 u0 com.android.settings/.Settings}
    """.trimIndent()

    @Test
    fun windowParser_prefersCurrentFocus() {
        assertEquals("com.tencent.mm", DumpsysWindowParser.parseForegroundPackage(windowDump))
    }

    @Test
    fun windowParser_fallsBackToResumedActivity() {
        assertEquals("com.android.settings", DumpsysWindowParser.parseForegroundPackage(activityDump))
    }

    @Test
    fun windowParser_emptyOrNoMatch_returnsNull() {
        assertNull(DumpsysWindowParser.parseForegroundPackage(""))
        assertNull(DumpsysWindowParser.parseForegroundPackage("no focus info here"))
    }

    // 取自 `dumpsys diskstats` 的四条并行数组行。
    private val diskstatsDump = """
        diskstats:
        Latency: 5ms [512K] 10ms [1M] 100ms [8M]
        Recent Disk Write Speed (kB/s) = 12345
        File-based Encryption (FBE): true
        Package Names: ["com.foo.app","com.bar.app"]
        App Sizes: [104857600,52428800]
        App Data Sizes: [20971520,10485760]
        Cache Sizes: [5242880,1048576]
    """.trimIndent()

    @Test
    fun diskstatsParser_alignsParallelArrays() {
        val parsed = DumpsysDiskstatsParser.parse(diskstatsDump)
        assertNotNull(parsed)
        parsed!!
        assertEquals(2, parsed.size)
        val foo = parsed[0]
        assertEquals("com.foo.app", foo.packageName)
        assertEquals(104857600L, foo.appBytes)
        assertEquals(20971520L, foo.dataBytes)
        assertEquals(5242880L, foo.cacheBytes)
        assertEquals(104857600L + 20971520L + 5242880L, foo.totalBytes)
        assertEquals("com.bar.app", parsed[1].packageName)
    }

    @Test
    fun diskstatsParser_missingLines_returnsNull() {
        assertNull(DumpsysDiskstatsParser.parse("diskstats:\nLatency: 5ms"))
        assertNull(DumpsysDiskstatsParser.parse(""))
    }

    @Test
    fun diskstatsParser_skipsBlankPackageNames() {
        val dump = """
            Package Names: ["com.foo.app",""]
            App Sizes: [100,200]
            App Data Sizes: [10,20]
            Cache Sizes: [1,2]
        """.trimIndent()
        val parsed = DumpsysDiskstatsParser.parse(dump)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.size)
        assertTrue(parsed.all { it.packageName.isNotBlank() })
    }
}
