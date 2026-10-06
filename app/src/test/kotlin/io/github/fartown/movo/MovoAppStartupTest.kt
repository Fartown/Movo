package io.github.fartown.movo

import android.content.Context
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 启动采样开关在 attachBaseContext 里运行：那时还没有 base context，不能碰任何依赖它的东西。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MovoAppStartupTest {
    @Test
    fun startupProfileMarkerDoesNotCrashAttachingTheApplication() {
        val context = RuntimeEnvironment.getApplication() as Context
        val marker = File(context.filesDir, "profile-startup").apply { parentFile?.mkdirs(); writeText("") }

        val app = MovoApp()
        MovoApp::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply { isAccessible = true }
            .invoke(app, context)

        assertFalse("标记用掉一次就删", marker.exists())
        android.os.Debug.stopMethodTracing()
    }
}
