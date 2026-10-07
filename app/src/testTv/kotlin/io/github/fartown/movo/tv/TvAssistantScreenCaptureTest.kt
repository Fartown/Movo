package io.github.fartown.movo.tv

import io.github.fartown.movo.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvAssistantScreenCaptureTest {
    @Test
    fun theAssistantCaptureCoversExcludingMovoItselfButNoOtherApp() {
        assertTrue(TvAssistantScreenCapture.excludes(setOf(BuildConfig.APPLICATION_ID)))
        assertFalse(TvAssistantScreenCapture.excludes(setOf("com.xiaodianshi.tv.yst")))
        assertFalse(TvAssistantScreenCapture.excludes(setOf(BuildConfig.APPLICATION_ID, "com.tcl.walleve")))
    }
}
