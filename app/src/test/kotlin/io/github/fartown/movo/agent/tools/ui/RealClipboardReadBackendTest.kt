package io.github.fartown.movo.agent.tools.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RealClipboardReadBackendTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    @Test
    fun nothingReadableInTheBackgroundIsRejectedNotEmpty() {
        // Android 10+ 后台读剪贴板被拒时，系统给的和「空」一模一样：不在前台就按被拒报。
        clipboard.clearPrimaryClip()

        assertEquals(ClipboardReadResult.Rejected, RealClipboardReadBackend(context) { false }.read())
        assertEquals(ClipboardReadResult.Empty, RealClipboardReadBackend(context) { true }.read())
    }

    @Test
    fun textIsReadWhenAllowed() {
        clipboard.setPrimaryClip(ClipData.newPlainText("t", "取件码 1234"))

        val result = RealClipboardReadBackend(context) { false }.read()

        assertEquals(ClipboardReadResult.Text("取件码 1234", truncated = false, sensitive = false), result)
    }
}
