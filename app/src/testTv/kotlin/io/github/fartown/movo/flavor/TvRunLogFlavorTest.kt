package io.github.fartown.movo.flavor

import org.junit.Assert.assertTrue
import org.junit.Test

class TvRunLogFlavorTest {
    /** 电视也记完整运行日志，排查执行问题要靠它（上限 200 MB、20 次执行）。 */
    @Test
    fun tvRecordsFullRunLogs() {
        assertTrue(FlavorModule.fullRunLog)
    }
}
