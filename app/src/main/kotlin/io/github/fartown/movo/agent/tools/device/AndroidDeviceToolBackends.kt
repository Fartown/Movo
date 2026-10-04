package io.github.fartown.movo.agent.tools.device

import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.provider.Settings
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.device.RootAccess
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** 把 Root 执行器结果映射成 [ToggleDispatch]。 */
private fun BoundedRootCommandExecutor.Result.toDispatch(): ToggleDispatch = when {
    ok -> ToggleDispatch.OK
    errorCode == "ROOT_REQUIRED" || errorCode == "ROOT_UNAVAILABLE" -> ToggleDispatch.ROOT_REQUIRED
    else -> ToggleDispatch.FAILED
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/**
 * device_toggle 真实后端。flashlight 走 CameraManager torch（不需 Root，回读靠 torch 回调跟踪）；
 * wifi/bluetooth 走 Root `cmd` 命令，回读走 WifiManager / BluetoothAdapter。
 */
internal class AndroidDeviceToggleBackend(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
) : DeviceToggleBackend {

    // torch 无直接 getter，用回调跟踪每个闪光灯摄像头的开关状态。
    private val torchStates = ConcurrentHashMap<String, Boolean>()
    private var torchCallbackRegistered = false

    private val cameraManager: CameraManager?
        get() = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private fun ensureTorchCallback() {
        if (torchCallbackRegistered) return
        val manager = cameraManager ?: return
        runCatching {
            manager.registerTorchCallback(
                object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        torchStates[cameraId] = enabled
                    }

                    override fun onTorchModeUnavailable(cameraId: String) {
                        torchStates.remove(cameraId)
                    }
                },
                null,
            )
            torchCallbackRegistered = true
        }
    }

    private fun torchCameraId(): String? = runCatching {
        cameraManager?.cameraIdList?.firstOrNull { id ->
            cameraManager?.getCameraCharacteristics(id)
                ?.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    override fun setFlashlight(enabled: Boolean): ToggleDispatch {
        val manager = cameraManager ?: return ToggleDispatch.FAILED
        ensureTorchCallback()
        val id = torchCameraId() ?: return ToggleDispatch.FAILED
        return runCatching {
            manager.setTorchMode(id, enabled)
            ToggleDispatch.OK
        }.getOrDefault(ToggleDispatch.FAILED)
    }

    override fun setRadio(target: ToggleTarget, enabled: Boolean): ToggleDispatch {
        val command = when (target) {
            ToggleTarget.WIFI -> "cmd wifi set-wifi-enabled ${if (enabled) "enabled" else "disabled"}"
            ToggleTarget.BLUETOOTH -> "cmd bluetooth_manager ${if (enabled) "enable" else "disable"}"
            ToggleTarget.FLASHLIGHT -> return setFlashlight(enabled)
        }
        return root.execute(command).toDispatch()
    }

    @Suppress("DEPRECATION")
    override fun readState(target: ToggleTarget): Boolean? = when (target) {
        ToggleTarget.FLASHLIGHT -> {
            ensureTorchCallback()
            val id = torchCameraId()
            if (id == null) null else torchStates[id]
        }
        ToggleTarget.WIFI -> runCatching {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
        }.getOrNull()
        ToggleTarget.BLUETOOTH -> runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled
        }.getOrNull()
    }
}

/** setting_read + setting_write 的真实后端。读走 Settings API（失败回退 Root），写走 Root。 */
internal class AndroidSettingBackend(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
) : SettingReadBackend, SettingWriteBackend {

    override fun read(namespace: SettingNamespace, key: String): String? {
        val publicValue = runCatching {
            when (namespace) {
                SettingNamespace.SYSTEM -> Settings.System.getString(context.contentResolver, key)
                SettingNamespace.SECURE -> Settings.Secure.getString(context.contentResolver, key)
                SettingNamespace.GLOBAL -> Settings.Global.getString(context.contentResolver, key)
            }
        }.getOrNull()
        if (publicValue != null) return publicValue
        if (!rootAvailable()) return null
        val ns = namespace.name.lowercase(Locale.ROOT)
        return root.execute("settings --user current get ${shellQuote(ns)} ${shellQuote(key)}")
            .takeIf { it.ok }
            ?.stdout
            ?.trim()
            ?.takeUnless { it == "null" || it.isEmpty() }
    }

    override fun write(namespace: SettingNamespace, key: String, value: String): ToggleDispatch {
        val ns = namespace.name.lowercase(Locale.ROOT)
        return root.execute(
            "settings --user current put ${shellQuote(ns)} ${shellQuote(key)} ${shellQuote(value)}",
        ).toDispatch()
    }
}

/** device_diagnostics 真实后端：三类都走 Root。迁移自 SDT:389-461、581-598。 */
internal class AndroidDeviceDiagnosticsBackend(
    private val root: BoundedRootCommandExecutor,
) : DeviceDiagnosticsBackend {

    override fun topProcesses(limit: Int): DiagnosticsResult {
        val result = root.execute("ps -A -o PID,RSS,NAME", maxOutputBytes = 512 * 1024)
        if (!result.ok) return result.toFail()
        val items = result.stdout.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 3)
                if (parts.size != 3) return@mapNotNull null
                val pid = parts[0].toIntOrNull() ?: return@mapNotNull null
                val rssKb = parts[1].toLongOrNull() ?: return@mapNotNull null
                Triple(pid, rssKb, parts[2])
            }
            .sortedByDescending { it.second }
            .take(limit)
            .toList()
        val array = JSONArray()
        items.forEach { (pid, rssKb, name) ->
            array.put(JSONObject().put("pid", pid).put("process", name).put("rss_bytes", rssKb * 1024L))
        }
        return DiagnosticsResult.Ok(JSONObject().put("items", array), result.truncated)
    }

    override fun appStorage(limit: Int): DiagnosticsResult {
        // TODO(未核实)：StorageStatsManager 可能免 Root；当前沿用 dumpsys diskstats（需 Root）。
        val result = root.execute("dumpsys diskstats", timeoutMillis = 20_000L, maxOutputBytes = 2 * 1024 * 1024)
        if (!result.ok) return result.toFail()
        val packages = parseJsonArrayLine(result.stdout, "Package Names:")
        val appSizes = parseLongArrayLine(result.stdout, "App Sizes:")
        val dataSizes = parseLongArrayLine(result.stdout, "App Data Sizes:")
        val cacheSizes = parseLongArrayLine(result.stdout, "Cache Sizes:")
        if (packages == null || appSizes == null || dataSizes == null || cacheSizes == null) {
            return DiagnosticsResult.Fail(ToolErrorCode.SOURCE_UNAVAILABLE, "系统未返回可解析的应用存储统计")
        }
        data class Usage(val pkg: String, val app: Long, val data: Long, val cache: Long) {
            val total get() = app + data + cache
        }
        val items = (0 until packages.length())
            .mapNotNull { index ->
                val pkg = packages.optString(index).takeIf(String::isNotBlank) ?: return@mapNotNull null
                Usage(pkg, appSizes.getOrElse(index) { 0L }, dataSizes.getOrElse(index) { 0L }, cacheSizes.getOrElse(index) { 0L })
            }
            .sortedByDescending { it.total }
            .take(limit)
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("package_name", it.pkg)
                    .put("total_bytes", it.total)
                    .put("app_bytes", it.app)
                    .put("data_bytes", it.data)
                    .put("cache_bytes", it.cache),
            )
        }
        return DiagnosticsResult.Ok(JSONObject().put("items", array), false)
    }

    override fun logcat(maxLines: Int, level: String?, packageName: String?, query: String?): DiagnosticsResult {
        val levelArg = level?.trim()?.uppercase(Locale.ROOT)?.firstOrNull()?.let { "*:$it" } ?: ""
        val result = root.execute(
            "logcat -d -v threadtime -t $maxLines $levelArg".trim(),
            maxOutputBytes = 512 * 1024,
        )
        if (!result.ok) return result.toFail()
        val lines = result.stdout.lineSequence()
            .filter { packageName.isNullOrBlank() || it.contains(packageName, ignoreCase = true) }
            .filter { query.isNullOrBlank() || it.contains(query, ignoreCase = true) }
            .take(maxLines)
            .toList()
        return DiagnosticsResult.Ok(
            JSONObject().put("lines", JSONArray(lines)).put("count", lines.size),
            result.truncated,
        )
    }

    private fun BoundedRootCommandExecutor.Result.toFail(): DiagnosticsResult.Fail = when {
        errorCode == "ROOT_REQUIRED" || errorCode == "ROOT_UNAVAILABLE" ->
            DiagnosticsResult.Fail(ToolErrorCode.ROOT_REQUIRED, "需要 Root 授权")
        timedOut -> DiagnosticsResult.Fail(ToolErrorCode.TIMEOUT, "诊断命令超时")
        else -> DiagnosticsResult.Fail(ToolErrorCode.SOURCE_UNAVAILABLE, "系统接口执行失败（exit=$exitCode）")
    }

    private fun parseJsonArrayLine(source: String, prefix: String): JSONArray? =
        source.lineSequence().firstOrNull { it.startsWith(prefix) }
            ?.substringAfter(prefix)?.trim()
            ?.let { runCatching { JSONArray(it) }.getOrNull() }

    private fun parseLongArrayLine(source: String, prefix: String): List<Long>? {
        val array = parseJsonArrayLine(source, prefix) ?: return null
        return (0 until array.length()).map { array.optLong(it) }
    }
}
