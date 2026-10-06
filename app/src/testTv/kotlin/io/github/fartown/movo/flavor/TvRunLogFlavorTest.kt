package io.github.fartown.movo.flavor

import org.junit.Assert.assertFalse
import org.junit.Test

class TvRunLogFlavorTest {
    /** 电视版没有运行日志页（导出、清空、开关都没有入口），一期不记完整运行日志。 */
    @Test
    fun tvDoesNotRecordFullRunLogs() {
        assertFalse(FlavorModule.fullRunLog)
    }
}
