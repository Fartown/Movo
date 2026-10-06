package io.github.fartown.movo.tv

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import io.github.fartown.movo.diagnostics.MemoryDiagnostics

/** Some TCL ROMs omit the assistant chooser and reset its setting during package/boot setup. */
internal object TvAssistantPermission {
    private const val PREFS = "tv_screen_assistant"
    private const val ENABLED = "enabled"
    private const val SERVICE = "voice_interaction_service"
    private const val ASSISTANT = "assistant"

    fun canConfigure(context: Context) = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
        PackageManager.PERMISSION_GRANTED

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun enable(context: Context): String {
        if (!canConfigure(context)) return "此固件缺少设置入口，尚未授予本机助理配置权限"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!enabled(context)) prefs.edit()
            .putString("previous_service", read(context, SERVICE))
            .putString("previous_assistant", read(context, ASSISTANT)).commit()
        return runCatching {
            check(Settings.Secure.putString(context.contentResolver, SERVICE, component(context)))
            check(Settings.Secure.putString(context.contentResolver, ASSISTANT, component(context)))
            check(prefs.edit().putBoolean(ENABLED, true).commit())
            "已选择 Movo，等待系统助理连接"
        }.getOrElse { "配置失败，系统未接受默认助理设置" }
    }

    fun disable(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(ENABLED, false).commit()
        return runCatching {
            for ((key, previous) in listOf(SERVICE to "previous_service", ASSISTANT to "previous_assistant")) {
                if (owns(context, read(context, key))) {
                    check(Settings.Secure.putString(context.contentResolver, key, prefs.getString(previous, null)))
                }
            }
            "已关闭 Movo 屏幕助理"
        }.getOrElse { "自动恢复已关闭；系统未接受撤销设置，请在系统设置中更换默认助理" }
    }

    fun allowScreenContent(context: Context): String {
        if (!canConfigure(context) || !TvAssistantScreenCapture.isSelected(context)) {
            return "请先启用 Movo 屏幕助理"
        }
        return runCatching {
            check(Settings.Secure.putInt(context.contentResolver, "assist_structure_enabled", 1))
            check(Settings.Secure.putInt(context.contentResolver, "assist_screenshot_enabled", 1))
            "已允许默认助理读取屏幕内容和截图"
        }.getOrElse { "系统未接受屏幕内容授权" }
    }

    fun restoreIfEnabled(context: Context) {
        if (!enabled(context) || !canConfigure(context)) return
        val current = read(context, SERVICE)
        // A nonempty selection of another assistant is an intentional choice; preserve it.
        if (!current.isNullOrBlank()) return
        val restored = runCatching {
            val result = Settings.Secure.putString(context.contentResolver, SERVICE, component(context))
            if (read(context, ASSISTANT).isNullOrBlank()) {
                Settings.Secure.putString(context.contentResolver, ASSISTANT, component(context))
            }
            result
        }.getOrDefault(false)
        MemoryDiagnostics.record("tv.capture", "assistant.restore", fields = mapOf("accepted" to restored))
    }

    private fun component(context: Context) = ComponentName(context, TvScreenAssistantService::class.java).flattenToString()
    private fun read(context: Context, key: String): String? = Settings.Secure.getString(context.contentResolver, key)
    private fun owns(context: Context, value: String?) = value != null &&
        ComponentName.unflattenFromString(value) == ComponentName(context, TvScreenAssistantService::class.java)
}

class TvAssistantRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            TvAssistantPermission.restoreIfEnabled(context)
            TclWakeService.restoreIfEnabled(context)
        }
    }
}
