package io.github.fartown.movo.agent.tools.device

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.tools.core.Retry
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.core.AndroidAgentLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowCameraManager
import org.robolectric.shadows.ShadowDisplayManager

/**
 * 设备领域真实后端（Robolectric）：手电筒回读、位置读不到的原因、设置没权限读、预装应用识别。
 * 工具层的 Verdict / 错误码见 [DeviceToolContractTest]。
 */
@RunWith(RobolectricTestRunner::class)
class DeviceBackendsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val noRoot = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { false })

    // ---- device_toggle：手电筒回读 ----

    private fun addFlashCamera() {
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        shadowOf(characteristics).set(CameraCharacteristics.FLASH_INFO_AVAILABLE, true)
        shadowOf(context.getSystemService(CameraManager::class.java)).addCamera("0", characteristics)
    }

    /** 在没有 Looper 的工具线程上跑（真机工具就在这种线程上执行）。 */
    private fun <T> onWorkerThread(block: () -> T): T {
        var result: Result<T>? = null
        val worker = Thread { result = runCatching(block) }
        worker.start()
        worker.join(10_000)
        return result!!.getOrThrow()
    }

    /**
     * 真机：开关手电筒生效了，回读却一直「读不到」→ OUTCOME_UNKNOWN。根因：在没有 Looper 的工具线程上
     * registerTorchCallback(callback, null) 抛异常被吞，回调从没注册上。[StrictTorchShadowCameraManager] 按真机行为抛。
     */
    @Test
    @Config(shadows = [StrictTorchShadowCameraManager::class])
    fun flashlight_readBackWorksFromThreadWithoutLooper() {
        addFlashCamera()
        val backend = AndroidDeviceToggleBackend(context, noRoot)
        val on = onWorkerThread { backend.setFlashlight(true) to backend.readState(ToggleTarget.FLASHLIGHT) }
        assertEquals(ToggleDispatch.OK, on.first)
        assertEquals(true, on.second)
        // 每次运行会新建后端，回读状态由进程内的跟踪器共享，不重复注册。
        val next = AndroidDeviceToggleBackend(context, noRoot)
        val off = onWorkerThread { next.setFlashlight(false) to next.readState(ToggleTarget.FLASHLIGHT) }
        assertEquals(ToggleDispatch.OK, off.first)
        assertEquals(false, off.second)
    }

    @Test
    @Config(shadows = [StrictTorchShadowCameraManager::class])
    fun flashlight_toolReturnsVerifiedDone() {
        addFlashCamera()
        val tool = DeviceToggleTool(AndroidDeviceToggleBackend(context, noRoot))
        val ctx = io.github.fartown.movo.agent.tools.core.ToolContext(
            appContext = context,
            logger = AndroidAgentLogger,
            runId = "run1",
            toolCallId = "c1",
            env = ToolEnvironment(),
            interaction = io.github.fartown.movo.agent.tools.core.UserInteraction.NONE,
            cancelled = { false },
        )
        val input = DeviceToggleInput(ToggleTarget.FLASHLIGHT, enabled = true)
        val verdict = onWorkerThread { tool.execute(input, tool.resolve(input, ToolEnvironment()), ctx) }
        assertTrue("应回读证实，实际：$verdict", verdict is io.github.fartown.movo.agent.tools.core.Verdict.Done)
    }

    // ---- device_read：位置读不到的原因 ----

    private fun locationFailure(): DeviceSectionUnavailable {
        val backend = AndroidDeviceReadBackend(context, noRoot) { false }
        try {
            backend.read(DeviceSection.LOCATION, ToolEnvironment())
        } catch (unavailable: DeviceSectionUnavailable) {
            return unavailable
        }
        fail("位置读不到时应给出原因")
        throw AssertionError()
    }

    private fun grantLocation(background: Boolean) {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (background) permissions += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        shadowOf(context as Application).grantPermissions(*permissions.toTypedArray())
    }

    @Test
    fun location_noPermission_userMustGrant() {
        val failure = locationFailure()
        assertEquals(ToolErrorCode.PERMISSION_REQUIRED, failure.code)
        assertEquals(Retry.USER, failure.retry)
        assertEquals("permission_required", failure.detail)
    }

    @Test
    fun location_foregroundOnly_userMustAllowAlways() {
        grantLocation(background = false)
        val failure = locationFailure()
        assertEquals(ToolErrorCode.PERMISSION_REQUIRED, failure.code)
        assertEquals(Retry.USER, failure.retry)
        assertEquals("background_permission_required", failure.detail)
    }

    @Test
    fun location_switchOff_userMustTurnOn() {
        grantLocation(background = true)
        shadowOf(context.getSystemService(LocationManager::class.java)).setLocationEnabled(false)
        val failure = locationFailure()
        assertEquals(ToolErrorCode.SOURCE_UNAVAILABLE, failure.code)
        assertEquals(Retry.USER, failure.retry)
        assertEquals("location_disabled", failure.detail)
    }

    @Test
    fun location_onButNoFix_retryLater() {
        grantLocation(background = true)
        shadowOf(context.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        val failure = locationFailure()
        assertEquals(ToolErrorCode.SOURCE_UNAVAILABLE, failure.code)
        assertEquals(Retry.LATER, failure.retry)
    }

    @Test
    fun location_available_returnsCoordinates() {
        grantLocation(background = true)
        val manager = context.getSystemService(LocationManager::class.java)
        shadowOf(manager).setLocationEnabled(true)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).simulateLocation(
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 31.2
                longitude = 121.5
                accuracy = 12f
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            },
        )
        val json = AndroidDeviceReadBackend(context, noRoot) { false }.read(DeviceSection.LOCATION, ToolEnvironment())!!
        assertEquals(31.2, json.getDouble("latitude"), 0.0001)
    }

    // ---- setting_read：没权限读 ≠ 没值 ----

    @Test
    fun settings_securityException_isUnreadableNotUnset() {
        val backend = AndroidSettingBackend(context, noRoot, rootAvailable = { false }) { _, key ->
            if (key == "hidden_key") throw SecurityException("Settings key: <hidden_key> is not readable") else null
        }
        val denied = backend.readValue(SettingNamespace.GLOBAL, "hidden_key")
        assertTrue(denied is SettingValue.Unreadable)
        assertEquals(ToolErrorCode.ROOT_REQUIRED, (denied as SettingValue.Unreadable).error.code)
        assertEquals(SettingValue.Unset, backend.readValue(SettingNamespace.GLOBAL, "missing"))
        // setting_write 的回读接口仍只要值。
        assertNull(backend.read(SettingNamespace.GLOBAL, "hidden_key"))
    }

    @Test
    fun settings_publicValue_isRead() {
        Settings.System.putString(context.contentResolver, "screen_off_timeout", "2147483647")
        val backend = AndroidSettingBackend(context, noRoot, rootAvailable = { false })
        assertEquals(SettingValue.Value("2147483647"), backend.readValue(SettingNamespace.SYSTEM, "screen_off_timeout"))
        assertEquals("2147483647", backend.read(SettingNamespace.SYSTEM, "screen_off_timeout"))
    }

    // ---- device_read：环境（音频输出可读名、外接显示器数）----

    @Test
    fun environment_audioOutputsReadableAndExternalDisplaysCounted() {
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        shadowOf(audio).setOutputDevices(
            listOf(
                AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build(),
                AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build(),
            ),
        )
        ShadowDisplayManager.addDisplay("w1080dp-h1920dp")
        val env = AndroidDeviceReadBackend(context, noRoot) { false }.read(DeviceSection.ENVIRONMENT, ToolEnvironment())!!
        val outputs = env.getJSONArray("audio_outputs")
        assertEquals(listOf("speaker", "bluetooth_audio"), (0 until outputs.length()).map { outputs.getJSONObject(it).getString("type") })
        assertTrue((0 until outputs.length()).all { outputs.getJSONObject(it).has("is_sink") })
        assertEquals(2, env.getInt("display_count"))
        assertEquals(1, env.getInt("external_display_count"))
    }

    @Test
    fun audioDeviceType_namesLikeBeforeTheRefactor() {
        assertEquals("wired_headset", AndroidDeviceReadBackend.audioDeviceType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals("usb_audio", AndroidDeviceReadBackend.audioDeviceType(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals("hdmi", AndroidDeviceReadBackend.audioDeviceType(AudioDeviceInfo.TYPE_HDMI_ARC))
        assertEquals("bluetooth_le_audio", AndroidDeviceReadBackend.audioDeviceType(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals("other_${AudioDeviceInfo.TYPE_TELEPHONY}", AndroidDeviceReadBackend.audioDeviceType(AudioDeviceInfo.TYPE_TELEPHONY))
    }

    // ---- app_search：厂商可卸载预装算系统应用 ----

    private fun installLauncherApp(packageName: String, label: String, flags: Int, sourceDir: String) {
        val appInfo = ApplicationInfo().apply {
            this.packageName = packageName
            this.flags = flags
            this.sourceDir = sourceDir
            publicSourceDir = sourceDir
            nonLocalizedLabel = label
        }
        val activity = ActivityInfo().apply {
            this.packageName = packageName
            name = "$packageName.MainActivity"
            applicationInfo = appInfo
            nonLocalizedLabel = label
            exported = true
        }
        val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = appInfo
        })
        pm.addOrUpdateActivity(activity)
        pm.addIntentFilterForActivity(
            ComponentName(packageName, activity.name),
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    @Test
    fun appIndex_productDataAppIsSystem() {
        installLauncherApp("com.miui.calculator", "计算器", flags = 0, sourceDir = "/product/data-app/MIUICalculator/MIUICalculator.apk")
        installLauncherApp("com.tencent.mm", "微信", flags = 0, sourceDir = "/data/app/~~a==/com.tencent.mm-b==/base.apk")
        val index = LauncherAppIndex(context)
        assertEquals(true, index.byPackage("com.miui.calculator")?.isSystem)
        assertEquals(false, index.byPackage("com.tencent.mm")?.isSystem)
    }
}

/** 按真机 CameraDeviceImpl.checkHandler 的行为：没给 Handler、当前线程又没有 Looper 时抛异常。 */
@Implements(CameraManager::class)
class StrictTorchShadowCameraManager : ShadowCameraManager() {
    @Implementation
    override fun registerTorchCallback(callback: CameraManager.TorchCallback, handler: Handler?) {
        require(handler != null || Looper.myLooper() != null) { "No handler given, and current thread has no looper!" }
        super.registerTorchCallback(callback, handler)
    }
}
