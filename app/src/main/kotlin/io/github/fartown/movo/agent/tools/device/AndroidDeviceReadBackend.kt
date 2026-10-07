package io.github.fartown.movo.agent.tools.device

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.device.DeviceLocationProvider
import io.github.fartown.movo.agent.device.RootAccess
import io.github.fartown.movo.agent.tools.core.Retry
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import org.json.JSONArray
import org.json.JSONObject

/**
 * device_read 的真实后端：组合 deviceStatus / networkInfo / deviceEnvironment / currentLocation 的读法。
 * 迁移自 AgentStructuredDeviceTools（SDT:211-270）与 AgentPersonalContextTools（PCT:106-145）。
 */
internal class AndroidDeviceReadBackend(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
) : DeviceReadBackend {

    @Suppress("DEPRECATION")
    override fun read(section: DeviceSection, env: ToolEnvironment): JSONObject? = when (section) {
        DeviceSection.BATTERY -> {
            val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            JSONObject()
                .put("percent", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                .put("charging", battery.isCharging)
        }
        DeviceSection.MEMORY -> {
            val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memory = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
            JSONObject()
                .put("available_bytes", memory.availMem)
                .put("total_bytes", memory.totalMem)
                .put("low", memory.lowMemory)
        }
        DeviceSection.STORAGE -> {
            val storage = StatFs(Environment.getDataDirectory().absolutePath)
            JSONObject()
                .put("available_bytes", storage.availableBytes)
                .put("total_bytes", storage.totalBytes)
        }
        DeviceSection.SYSTEM -> JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("android_version", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("security_patch", Build.VERSION.SECURITY_PATCH)
            .put("uptime_ms", SystemClock.elapsedRealtime())
        DeviceSection.NETWORK -> networkInfo()
        DeviceSection.ENVIRONMENT -> deviceEnvironment()
        DeviceSection.LOCATION -> when (val result = DeviceLocationProvider.latest(context)) {
            is DeviceLocationProvider.Result.Available -> JSONObject()
                .put("latitude", result.latitude)
                .put("longitude", result.longitude)
                .put("accuracy_m", result.accuracyMeters ?: JSONObject.NULL)
                .put("age_seconds", result.ageMillis / 1000L)
            is DeviceLocationProvider.Result.Unavailable -> throw locationUnavailable(result.status)
        }
        DeviceSection.TIME -> deviceTime(java.time.ZonedDateTime.now())
    }

    @Suppress("DEPRECATION")
    private fun networkInfo(): JSONObject {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        val transports = JSONArray().apply {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) put("wifi")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) put("cellular")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) put("ethernet")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) put("vpn")
        }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = runCatching { wifi.connectionInfo }.getOrNull()
        val rootWifiStatus = if (rootAvailable()) {
            root.execute("cmd wifi status", maxOutputBytes = 32 * 1024).takeIf { it.ok }?.stdout.orEmpty()
        } else {
            ""
        }
        val fallbackSsid = WIFI_STATUS_SSID.find(rootWifiStatus)?.groupValues?.get(1)?.trim()?.trim('"')
        val fallbackRssi = WIFI_STATUS_RSSI.find(rootWifiStatus)?.groupValues?.get(1)?.toIntOrNull()
        val json = JSONObject()
            .put("connected", capabilities != null)
            .put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("metered", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true)
            .put("transports", transports)
            .put("wifi_enabled", wifi.isWifiEnabled)
        val ssid = info?.ssid?.takeUnless { it == WifiManager.UNKNOWN_SSID }?.trim('"') ?: fallbackSsid
        val rssi = info?.rssi?.takeUnless { it == -127 } ?: fallbackRssi
        ssid?.let { json.put("ssid", it) }
        rssi?.let { json.put("rssi_dbm", it) }
        return json
    }

    private fun deviceEnvironment(): JSONObject {
        val audio = context.getSystemService(AudioManager::class.java)
        val displays = context.getSystemService(DisplayManager::class.java)?.displays.orEmpty()
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val notification = context.getSystemService(NotificationManager::class.java)
        val routes = JSONArray()
        audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.forEach { device ->
            if (device.isSink) {
                routes.put(
                    JSONObject()
                        .put("type", device.type)
                        .put("product_name", device.productName?.toString().orEmpty()),
                )
            }
        }
        return JSONObject()
            .put("interactive", power?.isInteractive ?: JSONObject.NULL)
            .put("device_locked", keyguard?.isDeviceLocked ?: JSONObject.NULL)
            .put("ringer_mode", ringerMode(audio?.ringerMode))
            .put("dnd_filter", interruptionFilter(notification?.currentInterruptionFilter))
            .put("audio_outputs", routes)
            .put("display_count", displays.size)
    }

    private fun ringerMode(mode: Int?): Any = when (mode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        AudioManager.RINGER_MODE_NORMAL -> "normal"
        else -> JSONObject.NULL
    }

    private fun interruptionFilter(filter: Int?): Any = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "all"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
        else -> JSONObject.NULL
    }

    internal companion object {
        val WIFI_STATUS_SSID = Regex("""\bSSID:\s*([^,\r\n]+)""")
        val WIFI_STATUS_RSSI = Regex("""\bRSSI:\s*(-?\d+)""")

        /**
         * 位置读不到的原因 → 给模型的错误（[DeviceLocationProvider.Result.Unavailable.status]）：
         * 没授权、只授权了「使用时允许」都是用户要去开（retry=user）；系统定位开关关着也是用户要去开；
         * 只有开关开着、权限也有，但系统还没有最近位置时才是「稍后再试」。
         */
        fun locationUnavailable(status: String): DeviceSectionUnavailable = when (status) {
            "permission_required" -> DeviceSectionUnavailable(
                code = ToolErrorCode.PERMISSION_REQUIRED,
                message = "Movo 没有位置权限",
                hint = "请用户给 Movo 打开位置权限并选「始终允许」（系统设置 → 应用 → Movo → 权限 → 位置）；授权前重试也读不到",
                detail = status,
            )
            "background_permission_required" -> DeviceSectionUnavailable(
                code = ToolErrorCode.PERMISSION_REQUIRED,
                message = "位置权限只允许了「使用时」，Movo 在后台执行时读不到",
                hint = "请用户在系统设置 → 应用 → Movo → 权限 → 位置里改成「始终允许」；改之前重试也读不到",
                detail = status,
            )
            "location_disabled" -> DeviceSectionUnavailable(
                code = ToolErrorCode.SOURCE_UNAVAILABLE,
                message = "系统定位开关关着",
                hint = "请用户在下拉快捷开关或系统设置里打开「位置信息」；打开前重试也读不到",
                retry = Retry.USER,
                detail = status,
            )
            else -> DeviceSectionUnavailable(
                code = ToolErrorCode.SOURCE_UNAVAILABLE,
                message = "定位开关和权限都正常，但系统暂时还没有最近的位置（只读系统已有的位置，不主动开 GPS）",
                hint = "稍后再读一次；还是没有时，可以请用户打开地图类应用定位一下再读",
                detail = status,
            )
        }
    }
}
