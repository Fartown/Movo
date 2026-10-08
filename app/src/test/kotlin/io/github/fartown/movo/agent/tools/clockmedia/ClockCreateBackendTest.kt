package io.github.fartown.movo.agent.tools.clockmedia

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.core.AndroidAgentLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * clock_create 真实后端（Robolectric）：派发的是算出来的 ColorOS 时钟 Intent；启动失败不报「没有时钟应用」，
 * 改为打开时钟的闹钟页；派发了但核实不到按已交给时钟报。
 */
@RunWith(RobolectricTestRunner::class)
class ClockCreateBackendTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private val noRoot = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { false })
    private val alarm = ClockCreateInput(type = ClockType.ALARM, hour = 7, minute = 30)

    /** 记下派发的 Intent；[failing] 里的 action 启动时抛异常（模拟后台启动被拦）。 */
    private class RecordingContext(base: Context, private val failing: Set<String> = emptySet()) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            if (intent.action in failing) throw SecurityException("background activity start blocked")
            started += intent
        }
    }

    private fun installClock(packageName: String, vararg actions: String) {
        val appInfo = ApplicationInfo().apply { this.packageName = packageName }
        val activity = ActivityInfo().apply {
            this.packageName = packageName
            name = "$packageName.AlarmClock"
            applicationInfo = appInfo
            exported = true
        }
        val pm = shadowOf(app.packageManager)
        pm.installPackage(PackageInfo().apply { this.packageName = packageName; applicationInfo = appInfo })
        pm.addOrUpdateActivity(activity)
        actions.forEach { action ->
            pm.addIntentFilterForActivity(
                ComponentName(packageName, activity.name),
                IntentFilter(action).apply { addCategory(Intent.CATEGORY_DEFAULT) },
            )
        }
    }

    private fun backend(context: Context) =
        RealClockCreateBackend(context, AndroidAgentLogger, noRoot, rootAvailable = { false }, pollTimeoutMs = 0)

    @Test
    fun colorOsClockIsWhatActuallyGetsTheIntent() {
        installClock("com.android.deskclock", AlarmClock.ACTION_SET_ALARM)
        installClock("com.coloros.alarmclock", AlarmClock.ACTION_SET_ALARM)
        val context = RecordingContext(app)
        val result = backend(context).createAndVerify(alarm, ToolEnvironment())
        assertEquals(ClockCreateResult.NotAttributed, result)
        val sent = context.started.single()
        assertEquals(AlarmClock.ACTION_SET_ALARM, sent.action)
        assertEquals("com.coloros.alarmclock", sent.`package`)
        assertEquals(7, sent.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
    }

    @Test
    fun launchFailure_opensTheAlarmPageInsteadOfSayingThereIsNoClock() {
        installClock("com.android.deskclock", AlarmClock.ACTION_SET_ALARM, AlarmClock.ACTION_SHOW_ALARMS)
        val context = RecordingContext(app, failing = setOf(AlarmClock.ACTION_SET_ALARM))
        val result = backend(context).createAndVerify(alarm, ToolEnvironment())
        assertEquals(ClockCreateResult.LaunchFailed(clockPageOpened = true), result)
        assertEquals(AlarmClock.ACTION_SHOW_ALARMS, context.started.single().action)
    }

    @Test
    fun launchFailure_withNoPageToOpen_isStillNotNoClockApp() {
        installClock("com.android.deskclock", AlarmClock.ACTION_SET_TIMER)
        val context = RecordingContext(app, failing = setOf(AlarmClock.ACTION_SET_TIMER, AlarmClock.ACTION_SHOW_TIMERS))
        val result = backend(context).createAndVerify(
            ClockCreateInput(type = ClockType.TIMER, durationSeconds = 300), ToolEnvironment(),
        )
        assertEquals(ClockCreateResult.LaunchFailed(clockPageOpened = false), result)
    }

    @Test
    fun noClockAtAll_isNoClockApp() {
        val context = RecordingContext(app)
        assertEquals(ClockCreateResult.NoClockApp, backend(context).createAndVerify(alarm, ToolEnvironment()))
        assertTrue(context.started.isEmpty())
    }
}
