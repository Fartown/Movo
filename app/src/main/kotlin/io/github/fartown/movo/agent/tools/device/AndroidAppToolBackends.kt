package io.github.fartown.movo.agent.tools.device

import io.github.fartown.movo.core.getApplicationInfoCompat
import io.github.fartown.movo.core.resolveActivityCompat
import io.github.fartown.movo.core.queryIntentActivitiesCompat
import io.github.fartown.movo.BuildConfig
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.device.RootAccess
import java.util.Locale

/** 桌面图标应用的只读索引，app_search / app_open 共用。 */
internal class LauncherAppIndex(private val context: Context) {

    fun installed(): List<AppMatch> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = runCatching {
            pm.queryIntentActivitiesCompat(intent)
        }.getOrDefault(emptyList())
        val apps = LinkedHashMap<String, AppMatch>()
        resolveInfos.forEach { info ->
            val appInfo = info.activityInfo?.applicationInfo ?: return@forEach
            val pkg = appInfo.packageName ?: return@forEach
            val label = info.loadLabel(pm).toString().trim().ifBlank { pkg }
            apps.putIfAbsent(
                pkg,
                AppMatch(
                    name = label,
                    packageName = pkg,
                    isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                ),
            )
        }
        return apps.values.toList()
    }

    fun search(query: String, limit: Int): List<AppMatch> {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        return installed().asSequence()
            .mapNotNull { app ->
                val score = matchScore(app, query, normalizedQuery)
                if (score == Int.MAX_VALUE) null else score to app
            }
            .sortedWith(compareBy<Pair<Int, AppMatch>> { it.first }.thenBy { it.second.name })
            .map { it.second }
            .take(limit)
            .toList()
    }

    fun byPackage(packageName: String): AppMatch? = installed().firstOrNull { it.packageName == packageName }

    private fun matchScore(app: AppMatch, rawQuery: String, normalizedQuery: String): Int {
        val normalizedName = app.name.trim().lowercase(Locale.ROOT)
        val normalizedPackage = app.packageName.lowercase(Locale.ROOT)
        return when {
            app.packageName.equals(rawQuery, ignoreCase = true) -> 0
            app.name.equals(rawQuery, ignoreCase = true) -> 1
            normalizedName == normalizedQuery -> 2
            normalizedPackage.contains(normalizedQuery) -> 3
            normalizedName.contains(normalizedQuery) -> 4
            else -> Int.MAX_VALUE
        }
    }
}

/** app_search 真实后端（含系统应用）。 */
internal class AndroidAppSearchBackend(context: Context) : AppSearchBackend {
    private val index = LauncherAppIndex(context)
    override fun search(query: String, limit: Int): List<AppMatch> = index.search(query, limit)
}

/** app_open 真实后端：启动 + 回读前台包名（排除 Movo 浮层）。 */
internal class AndroidAppOpenBackend(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
) : AppOpenBackend {
    private val index = LauncherAppIndex(context)

    override fun resolvePackage(packageName: String): AppMatch? = index.byPackage(packageName)

    override fun searchByName(name: String): List<AppMatch> = index.search(name, limit = 20)

    override fun launchPackage(packageName: String): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { context.startActivity(launchIntent) }.isSuccess
    }

    override fun launchUri(uri: String): UriDispatch {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
        if (parsed == null || parsed.scheme.isNullOrBlank()) return UriDispatch.InvalidScheme
        val intent = Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolved = runCatching {
            context.packageManager.resolveActivityCompat(intent)
        }.getOrNull() ?: return UriDispatch.NoActivity
        val targetPackage = resolved.activityInfo?.packageName?.takeUnless { it in RESOLVER_PACKAGES }
        return if (runCatching { context.startActivity(intent) }.isSuccess) {
            UriDispatch.Ok(targetPackage)
        } else {
            UriDispatch.NoActivity
        }
    }

    override fun awaitForeground(targetPackage: String?, waitMs: Long): ForegroundOutcome {
        val deadline = System.currentTimeMillis() + waitMs.coerceIn(500L, 15_000L)
        var lastForeground: String? = null
        while (System.currentTimeMillis() <= deadline) {
            val foreground = currentForeground()
            if (foreground != null && foreground !in MOVO_PACKAGES) {
                lastForeground = foreground
                if (targetPackage != null && foreground == targetPackage) {
                    return ForegroundOutcome(foreground, matched = true)
                }
                if (foreground in RESOLVER_PACKAGES) {
                    return ForegroundOutcome(foreground, matched = false, resolverShown = true)
                }
            }
            Thread.sleep(250L)
        }
        return ForegroundOutcome(lastForeground, matched = false)
    }

    /** 优先用无障碍前台包名，其次用 Root dumpsys 解析前台包。都没有则 null。 */
    private fun currentForeground(): String? {
        AgentAccessibilityService.current()?.currentPackageName()?.takeIf { it.isNotBlank() }?.let { return it }
        if (!rootAvailable()) return null
        // 无障碍不可用但有 Root：读 `dumpsys window` 的当前焦点，拿不到再读 `dumpsys activity activities` 的 resumed。
        // 解析走纯函数 [DumpsysWindowParser]（见其单测）。
        return foregroundViaRoot("dumpsys window") ?: foregroundViaRoot("dumpsys activity activities")
    }

    private fun foregroundViaRoot(command: String): String? {
        val dump = runCatching { root.execute(command, maxOutputBytes = 512 * 1024) }
            .getOrNull()
            ?.takeIf { it.ok }
            ?.stdout
            ?: return null
        return DumpsysWindowParser.parseForegroundPackage(dump)
    }

    private companion object {
        val RESOLVER_PACKAGES = setOf("android", "com.android.internal.app")
        val MOVO_PACKAGES = setOf(BuildConfig.APPLICATION_ID)
    }
}

/** app_control 真实后端：全部走 Root；回读用 dumpsys package / getApplicationEnabledSetting。 */
internal class AndroidAppControlBackend(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
) : AppControlBackend {

    override fun exists(packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfoCompat(packageName)
    }.isSuccess

    override fun run(packageName: String, action: AppControlAction): ToggleDispatch {
        val quoted = shellQuoteApp(packageName)
        val command = when (action) {
            AppControlAction.FORCE_STOP -> "am force-stop --user current $quoted"
            AppControlAction.FREEZE -> "pm disable-user --user current $quoted"
            AppControlAction.UNFREEZE -> "pm enable --user current $quoted"
        }
        return root.execute(command).let { result ->
            when {
                result.ok -> ToggleDispatch.OK
                result.errorCode == "ROOT_REQUIRED" || result.errorCode == "ROOT_UNAVAILABLE" -> ToggleDispatch.ROOT_REQUIRED
                else -> ToggleDispatch.FAILED
            }
        }
    }

    override fun readState(packageName: String, action: AppControlAction): AppControlState = when (action) {
        AppControlAction.FORCE_STOP -> AppControlState(stopped = readStopped(packageName))
        AppControlAction.FREEZE, AppControlAction.UNFREEZE -> AppControlState(frozen = readFrozen(packageName))
    }

    /** dumpsys package 的 stopped 标记；读不到返回 null（不冒领）。 */
    private fun readStopped(packageName: String): Boolean? {
        val quoted = shellQuoteApp(packageName)
        val result = root.execute("dumpsys package $quoted", maxOutputBytes = 256 * 1024)
        if (!result.ok) return null
        val match = STOPPED.find(result.stdout) ?: return null
        return match.groupValues[1].equals("true", ignoreCase = true)
    }

    /** getApplicationEnabledSetting：DISABLED / DISABLED_USER 视为已冻结。 */
    private fun readFrozen(packageName: String): Boolean? = runCatching {
        when (context.packageManager.getApplicationEnabledSetting(packageName)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            -> true
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            -> false
            else -> null
        }
    }.getOrNull()

    private fun shellQuoteApp(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        val STOPPED = Regex("""stopped=(true|false)""", RegexOption.IGNORE_CASE)
    }
}
