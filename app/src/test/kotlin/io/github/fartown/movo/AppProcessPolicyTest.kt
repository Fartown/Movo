package io.github.fartown.movo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessPolicyTest {
    @Test
    fun `仅主进程初始化完整 Runtime 依赖`() {
        assertTrue(AppProcessPolicy.shouldInitializeFullRuntime("io.github.fartown.movo", "io.github.fartown.movo"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.fartown.movo:voice", "io.github.fartown.movo"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.fartown.movo:voice_session", "io.github.fartown.movo"))
    }
}
