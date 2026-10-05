package io.github.fartown.movo.agent.voice.session

import android.app.Activity
import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Movo 自己的页面是否还挡在前面（App 内语音轮次要操作其他 App 前据此决定：用 moveTaskToBack 收起，或视为入口已关）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VoiceSurfaceTrackerTest {
    private val main = Activity()
    private val sheet = Activity()

    @After
    fun cleanup() {
        listOf(main, sheet).forEach(VoiceSurfaceTracker::onActivityStopped)
    }

    @Test
    fun startedPagesAreVisibleUntilTheyStop() {
        VoiceSurfaceTracker.onActivityStarted(main)
        VoiceSurfaceTracker.onActivityStarted(sheet)
        assertEquals(listOf(main, sheet), VoiceSurfaceTracker.visibleActivities())

        // 暂停（例如被半透明页面盖住、正在退场）仍算可见：只有 onStop 后才不挡屏幕。
        VoiceSurfaceTracker.onActivityPaused(sheet)
        assertEquals(listOf(main, sheet), VoiceSurfaceTracker.visibleActivities())

        VoiceSurfaceTracker.onActivityStopped(sheet)
        assertEquals(listOf(main), VoiceSurfaceTracker.visibleActivities())
        VoiceSurfaceTracker.onActivityDestroyed(main)
        assertTrue(VoiceSurfaceTracker.visibleActivities().isEmpty())
    }

    @Test
    fun restartingAPageDoesNotListItTwice() {
        VoiceSurfaceTracker.onActivityStarted(main)
        VoiceSurfaceTracker.onActivityStarted(main)
        assertEquals(listOf(main), VoiceSurfaceTracker.visibleActivities())
    }
}
