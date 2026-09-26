package io.github.fartown.movo.agent.voice

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MovoWakeWordServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun staleNotification(): NotificationManager {
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("movo_wake_word", "Wake", 2))
        manager.notify(1108, Notification.Builder(context, "movo_wake_word")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentText("正在启动本地唤醒").build())
        assertNotNull(shadowOf(manager).getNotification(1108))
        return manager
    }

    @Test fun coldBackgroundEntryDefersWakeServiceUntilAnActivityIsVisible() {
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        MovoWakeWordService.start(context)
        assertNull(shadowOf(context).nextStartedService)
        val activity = android.app.Activity()
        io.github.fartown.movo.agent.voice.session.VoiceSurfaceTracker.onActivityResumed(activity)
        try {
            MovoWakeWordService.start(context)
            assertEquals(MovoWakeWordService::class.java.name,
                shadowOf(context).nextStartedService?.component?.className)
        } finally {
            io.github.fartown.movo.agent.voice.session.VoiceSurfaceTracker.onActivityPaused(activity)
        }
    }

    @Test fun restartWithoutMicrophonePermissionStopsAndRemovesStaleNotification() {
        val manager = staleNotification()
        val controller = Robolectric.buildService(MovoWakeWordService::class.java).create()
        val service = controller.get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(shadowOf(manager).getNotification(1108))
        controller.destroy()
        assertFalse(MovoWakeWordService.isRunning())
    }

    @Test fun settingsSyncCleansAnOrphanNotificationWithoutStartingAService() {
        val manager = staleNotification()
        MovoWakeWordService.syncFromSettings(context, enabled = true, micGranted = false)
        assertNull(shadowOf(manager).getNotification(1108))
        assertNull(shadowOf(context).nextStartedService)
    }
}
