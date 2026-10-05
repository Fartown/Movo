package io.github.fartown.movo.agent.tools.device

import android.app.usage.StorageStatsManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
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
    private val context: Context,
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
        // 设备总容量/可用：优先用免 Root 的 StorageStatsManager（拿不到再用 StatFs），作为 summary 一并返回。
        val summary = storageSummary()
        // 按应用的存储明细仍需 Root `dumpsys diskstats`；解析走纯函数 [DumpsysDiskstatsParser]（见其单测）。
        val result = root.execute("dumpsys diskstats", timeoutMillis = 20_000L, maxOutputBytes = 2 * 1024 * 1024)
        if (!result.ok) {
            // 明细拿不到：若设备级 summary 可用（免 Root），仍回传 summary；否则按原错误码上报。
            return if (summary != null) {
                DiagnosticsResult.Ok(JSONObject().put("summary", summary).put("items", JSONArray()), false)
            } else {
                result.toFail()
            }
        }
        val parsed = DumpsysDiskstatsParser.parse(result.stdout)
            ?: return if (summary != null) {
                DiagnosticsResult.Ok(JSONObject().put("summary", summary).put("items", JSONArray()), false)
            } else {
                DiagnosticsResult.Fail(ToolErrorCode.SOURCE_UNAVAILABLE, "系统未返回可解析的应用存储统计")
            }
        val array = JSONArray()
        parsed.sortedByDescending { it.totalBytes }
            .take(limit)
            .forEach {
                array.put(
                    JSONObject()
                        .put("package_name", it.packageName)
                        .put("total_bytes", it.totalBytes)
                        .put("app_bytes", it.appBytes)
                        .put("data_bytes", it.dataBytes)
                        .put("cache_bytes", it.cacheBytes),
                )
            }
        val data = JSONObject().put("items", array)
        if (summary != null) data.put("summary", summary)
        return DiagnosticsResult.Ok(data, false)
    }

    /**
     * 设备级存储总量/可用。优先 [StorageStatsManager]（免 Root，API 26+，内置主存储 UUID），
     * 失败回退 [StatFs]。两者都拿不到返回 null（不冒领）。
     */
    private fun storageSummary(): JSONObject? {
        val viaStats = runCatching {
            val ssm = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val uuid = StorageManager.UUID_DEFAULT
            val total = ssm.getTotalBytes(uuid)
            val free = ssm.getFreeBytes(uuid)
            JSONObject()
                .put("total_bytes", total)
                .put("available_bytes", free)
                .put("source", "storage_stats_manager")
        }.getOrNull()
        if (viaStats != null) return viaStats
        return runCatching {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            JSONObject()
                .put("total_bytes", stat.totalBytes)
                .put("available_bytes", stat.availableBytes)
                .put("source", "statfs")
        }.getOrNull()
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
}
