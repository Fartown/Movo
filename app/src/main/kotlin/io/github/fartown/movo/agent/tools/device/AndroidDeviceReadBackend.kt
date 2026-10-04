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
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
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
            is DeviceLocationProvider.Result.Unavailable -> null
        }
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

    private companion object {
        val WIFI_STATUS_SSID = Regex("""\bSSID:\s*([^,\r\n]+)""")
        val WIFI_STATUS_RSSI = Regex("""\bRSSI:\s*(-?\d+)""")
    }
}
