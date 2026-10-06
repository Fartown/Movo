package io.github.fartown.movo.flavor

import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRunLogFlavorTest {
    /** 手机版有运行日志页，记录完整运行日志。 */
    @Test
    fun phoneRecordsFullRunLogs() {
        assertTrue(FlavorModule.fullRunLog)
    }
}
