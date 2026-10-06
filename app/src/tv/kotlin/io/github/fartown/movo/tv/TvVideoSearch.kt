package io.github.fartown.movo.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import io.github.fartown.movo.agent.tools.core.*
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import io.github.fartown.movo.core.getApplicationInfoCompat
import org.json.JSONObject

/**
 * 只收录在 TCL 55F295C（Android 9）上实测过的直达搜索（2026-10-06，`.docs/tv-video-deeplink/report.md`）。
 * 哔哩哔哩（云视听小电视）、芒果 TV 没有可用的搜索深链，不收录，由模型打开 App 后在界面里搜。
 */
internal enum class TvVideoApp(val id: String, val label: String, val packageName: String, val prefillOnly: Boolean = false) {
    /** 冷启动、后台都能直达搜索结果页；必须带 qqlivetv.open 动作，action=59 是结果页。 */
    TENCENT("tencent", "腾讯视频（云视听极光）", "com.ktcp.csvideo"),
    /** App 已在运行时能直达结果页；冷启动时 LoadingActivity 会自行退出。 */
    IQIYI("iqiyi", "爱奇艺（奇异果）", "com.tcl.qiyiguo"),
    /** 只能把片名填进搜索框，结果需要在界面里确认。 */
    YOUKU("youku", "优酷（CIBN 酷喵）", "com.cibn.tv", prefillOnly = true);

    fun searchIntent(title: String): Intent = when (this) {
        TENCENT -> Intent("com.tencent.qqlivetv.open", Uri.parse("tenvideo2://?action=59&search_keyword=" + Uri.encode(title)))
            .setPackage(packageName)
        IQIYI -> Intent("com.gitvdemo.video.action.ACTION_SEARCHRESULT")
            .setComponent(ComponentName(packageName, "com.gala.video.app.epg.LoadingActivity"))
            .putExtra("keyword", title).putExtra("customer", "movo")
        YOUKU -> Intent(Intent.ACTION_VIEW, Uri.parse("cibntv_yingshi://search?keyword=" + Uri.encode(title)))
            .setPackage(packageName)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        fun fromId(id: String?): TvVideoApp? = entries.firstOrNull { it.id == id }
    }
}

internal data class TvVideoSearchInput(val title: String, val app: TvVideoApp?) : ToolInput
internal data class TvVideoSearchOutput(val json: JSONObject) : ToolOutput

/** 按片名直达视频 App 的搜索结果页，并用无障碍确认已到达；选片播放交给界面工具。 */
internal class TvVideoSearchTool(private val context: Context) : ToolContract<TvVideoSearchInput, TvVideoSearchOutput> {
    override val name = "video_search"
    override val domain = ToolDomain.APP
    override val summary = "按片名直达视频 App 的搜索结果页（腾讯视频、爱奇艺、优酷）并确认已到达；之后用界面工具选片播放。" +
        "其他 App 或返回失败时，用 app_open 打开后在界面里搜索。"

    private fun installed(app: TvVideoApp) = runCatching {
        context.packageManager.getApplicationInfoCompat(app.packageName); true
    }.getOrDefault(false)

    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (TvVideoApp.entries.any(::installed)) ToolAvailability.Available
        else ToolAvailability.Unavailable(ToolErrorCode.SOURCE_UNAVAILABLE, "电视上没有支持直达搜索的视频 App")

    override fun schema(env: ToolEnvironment) = objectSchema {
        string("title", "片名，不带书名号", required = true)
        string("app", "指定 App；不填按已安装的腾讯视频、爱奇艺、优酷顺序选", enum = TvVideoApp.entries.map { it.id })
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): TvVideoSearchInput {
        val title = cleanTitle(args.string("title"))
        if (title.isEmpty()) invalidArgs("片名不能为空")
        return TvVideoSearchInput(title, TvVideoApp.fromId(args.stringOrNull("app")))
    }

    override fun resolve(input: TvVideoSearchInput, env: ToolEnvironment) = CallResolution(
        risk = Risk.LOCAL, sensitivity = Sensitivity.NORMAL, resources = emptySet())

    override fun execute(input: TvVideoSearchInput, resolution: CallResolution, ctx: ToolContext): Verdict<TvVideoSearchOutput> {
        ctx.checkCancelled()
        val app = when {
            input.app != null && installed(input.app) -> input.app
            input.app != null -> return Verdict.Failed(ToolError(ToolErrorCode.NOT_FOUND, "${input.app.label}没有安装",
                "换一个已安装的 App，或用 app_search 找"))
            else -> TvVideoApp.entries.firstOrNull(::installed)
                ?: return Verdict.Failed(ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "电视上没有支持直达搜索的视频 App",
                    "用 app_open 打开视频 App 后在界面里搜索"))
        }
        val sent = runCatching { context.startActivity(app.searchIntent(input.title)) }
        if (sent.isFailure) return Verdict.Failed(ToolError(ToolErrorCode.UNSUPPORTED, "${app.label}不接受直达搜索",
            "用 app_open 打开后在界面里搜索"))
        var foreground = false
        var titleVisible = false
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < deadline) {
            ctx.checkCancelled()
            val service = AgentAccessibilityService.current() ?: break
            foreground = service.currentPackageName() == app.packageName
            titleVisible = foreground && runCatching {
                service.rootInActiveWindow?.findAccessibilityNodeInfosByText(input.title)?.isNotEmpty() == true
            }.getOrDefault(false)
            if (titleVisible) break
            Thread.sleep(200)
        }
        val canVerify = AgentAccessibilityService.current() != null
        MemoryDiagnostics.record("tv.tool", "video_search", fields = mapOf("app" to app.id,
            "foreground" to foreground, "title_visible" to titleVisible, "verifiable" to canVerify))
        val json = JSONObject().put("app", app.label).put("package", app.packageName).put("title", input.title)
            .put("foreground", foreground).put("title_visible", titleVisible)
        return when {
            !canVerify -> Verdict.Unknown("已向${app.label}发出搜索「${input.title}」，但无障碍未连接，无法确认是否到达",
                "开启无障碍后用 ui_observe 确认；不行就在界面里搜索")
            titleVisible && app.prefillOnly -> Verdict.Done(TvVideoSearchOutput(json.put("note",
                "片名已填进搜索框；这个 App 的结果需要用 ui_observe 确认，没有结果时在界面里换个说法搜")),
                Evidence.ReadBack("${app.packageName} 前台，界面出现「${input.title}」"))
            titleVisible -> Verdict.Done(TvVideoSearchOutput(json.put("note", "已到达搜索结果页；用 ui_observe 找到要的那一部再用 ui_focus / ui_tap 打开播放")),
                Evidence.ReadBack("${app.packageName} 前台，界面出现「${input.title}」"))
            foreground -> Verdict.Unknown("已打开${app.label}，但界面上还没看到「${input.title}」",
                "先 ui_observe 看当前页面；不是结果页就在界面里搜索，不要重复调用本工具")
            app == TvVideoApp.IQIYI -> Verdict.Failed(ToolError(ToolErrorCode.UNSUPPORTED,
                "爱奇艺没有到前台（它只在已运行时接受直达搜索）",
                "用 app_open 打开爱奇艺，等首页出来后再调用一次；仍不行就在界面里搜索"))
            else -> Verdict.Failed(ToolError(ToolErrorCode.UNSUPPORTED, "${app.label}没有到前台，直达搜索没生效",
                "用 app_open 打开后在界面里搜索"))
        }
    }

    override fun renderForModel(output: TvVideoSearchOutput) = ModelContent.Json(output.json)

    companion object {
        /** 去掉书名号、引号和首尾空白；模型常把片名带着《》传进来。 */
        fun cleanTitle(raw: String): String = raw.trim().trim('《', '》', '「', '」', '"', '\'', '“', '”').trim()
    }
}
