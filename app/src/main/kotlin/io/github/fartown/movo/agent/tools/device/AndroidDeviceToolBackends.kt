package io.github.fartown.movo.agent.tools.device

import android.app.usage.StorageStatsManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.device.RootAccess
import io.github.fartown.movo.agent.tools.core.ToolError
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
 * 手电筒状态跟踪。torch 没有直接的 getter，只能靠 [CameraManager.TorchCallback]：注册时系统先报一次当前状态，
 * 之后每次开关再报。进程内只注册一次（工具后端每次运行都会新建，按实例注册会越积越多），
 * 回调投到主线程：工具线程没有 Looper，传 null Handler 会抛 IllegalArgumentException
 *（真机上就是这样被 runCatching 吞掉，一直读不到，开关生效却报 OUTCOME_UNKNOWN）。
 */
internal object TorchStateTracker {
    private val states = ConcurrentHashMap<String, Boolean>()
    private var registeredOn: CameraManager? = null
    private var callback: CameraManager.TorchCallback? = null

    /** 确保已在 [manager] 上注册回调；换了 CameraManager 实例（测试里每次新建应用）时重新注册。 */
    @Synchronized
    fun ensureRegistered(manager: CameraManager): Boolean {
        if (registeredOn === manager) return true
        callback?.let { old -> runCatching { registeredOn?.unregisterTorchCallback(old) } }
        registeredOn = null
        callback = null
        states.clear()
        val fresh = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                states[cameraId] = enabled
            }

            override fun onTorchModeUnavailable(cameraId: String) {
                states.remove(cameraId)
            }
        }
        return runCatching { manager.registerTorchCallback(fresh, Handler(Looper.getMainLooper())) }
            .onSuccess {
                registeredOn = manager
                callback = fresh
            }
            .isSuccess
    }

    /** 回调报过的开关状态；还没报（或相机被占用、不可用）为 null。 */
    fun state(cameraId: String): Boolean? = states[cameraId]
}

/**
 * device_toggle 真实后端。flashlight 走 CameraManager torch（不需 Root，回读靠 [TorchStateTracker]）；
 * wifi/bluetooth 走 Root `cmd` 命令，回读走 WifiManager / BluetoothAdapter（两者读开关都不需要额外权限）。
 */
internal class AndroidDeviceToggleBackend(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
) : DeviceToggleBackend {

    private val cameraManager: CameraManager?
        get() = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private fun ensureTorchCallback() {
        cameraManager?.let { TorchStateTracker.ensureRegistered(it) }
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
            torchCameraId()?.let { TorchStateTracker.state(it) }
        }
        ToggleTarget.WIFI -> runCatching {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
        }.getOrNull()
        ToggleTarget.BLUETOOTH -> runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled
        }.getOrNull()
    }
}

/**
 * setting_read + setting_write 的真实后端。读走 Settings API（读不到再回退 Root），写走 Root。
 * 公开接口抛 SecurityException（Android 12 起非公开键只对系统应用开放）记为「没权限读」，不再吞成 null。
 */
internal class AndroidSettingBackend(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
    /** 公开 Settings 接口；测试替换成会抛 SecurityException 的实现。 */
    private val publicRead: (SettingNamespace, String) -> String? = { namespace, key ->
        when (namespace) {
            SettingNamespace.SYSTEM -> Settings.System.getString(context.contentResolver, key)
            SettingNamespace.SECURE -> Settings.Secure.getString(context.contentResolver, key)
            SettingNamespace.GLOBAL -> Settings.Global.getString(context.contentResolver, key)
        }
    },
) : SettingReadBackend, SettingWriteBackend {

    override fun readValue(namespace: SettingNamespace, key: String): SettingValue {
        val public: SettingValue = try {
            publicRead(namespace, key)?.let { SettingValue.Value(it) } ?: SettingValue.Unset
        } catch (_: SecurityException) {
            SettingValue.Unreadable(
                if (rootAvailable()) {
                    ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "系统不允许普通应用读取这个设置，Root 读取也失败了")
                } else {
                    ToolError(
                        ToolErrorCode.ROOT_REQUIRED,
                        "系统不允许普通应用读取这个设置（不是没值），需要 Root",
                        hint = "如实告诉用户这一项读不到；不要说它没设置",
                    )
                },
            )
        } catch (_: RuntimeException) {
            SettingValue.Unreadable(ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "设置服务暂时读不到"))
        }
        if (public is SettingValue.Value || !rootAvailable()) return public
        val ns = namespace.name.lowercase(Locale.ROOT)
        val result = root.execute("settings --user current get ${shellQuote(ns)} ${shellQuote(key)}")
        if (!result.ok) return public
        val value = result.stdout.trim().takeUnless { it == "null" || it.isEmpty() }
        return value?.let { SettingValue.Value(it) } ?: SettingValue.Unset
    }

    /** setting_write 写前写后回读：只要值，读不了按读不到（null）处理。 */
    override fun read(namespace: SettingNamespace, key: String): String? =
        (readValue(namespace, key) as? SettingValue.Value)?.value

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
