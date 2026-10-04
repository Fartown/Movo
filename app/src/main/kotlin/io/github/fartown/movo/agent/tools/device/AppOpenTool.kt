package io.github.fartown.movo.agent.tools.device

import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.Evidence
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolContract
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolError
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.invalidArgs
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONArray
import org.json.JSONObject

/** app_open 的三选一目标。 */
internal sealed interface AppOpenTarget {
    data class ByPackage(val packageName: String) : AppOpenTarget
    data class ByName(val name: String) : AppOpenTarget
    data class ByUri(val uri: String) : AppOpenTarget
}

internal data class AppOpenInput(val target: AppOpenTarget, val waitMs: Long) : ToolInput

internal data class AppOpenOutput(
    val targetPackage: String?,
    val foregroundPackage: String?,
    val foreground: Boolean,
) : ToolOutput

/** URI 派发结果。 */
internal sealed interface UriDispatch {
    /** 已派发；[targetPackage] 为可确定的唯一处理应用，null 表示由系统选择器处理。 */
    data class Ok(val targetPackage: String?) : UriDispatch
    data object NoActivity : UriDispatch
    data object InvalidScheme : UriDispatch
}

/** 前台确认结果：排除 Movo 浮层后观察到的前台。 */
internal data class ForegroundOutcome(
    val foregroundPackage: String?,
    val matched: Boolean,
    /** 前台是系统应用选择器（resolver）。 */
    val resolverShown: Boolean = false,
    /** 疑似后台启动被静默拦截（小米等，无异常抛出）。 */
    val backgroundBlocked: Boolean = false,
)

/**
 * 可测后端：解析目标、启动、再回读前台。
 */
internal interface AppOpenBackend {
    fun resolvePackage(packageName: String): AppMatch?
    fun searchByName(name: String): List<AppMatch>
    /** 启动应用主入口；false 表示不可启动或未安装。 */
    fun launchPackage(packageName: String): Boolean
    fun launchUri(uri: String): UriDispatch
    /** 轮询前台包名直到匹配或超时；[targetPackage] 为 null 时无法判定匹配。 */
    fun awaitForeground(targetPackage: String?, waitMs: Long): ForegroundOutcome
}

/**
 * app_open（回读型，local）：打开应用或把 URI 交给对应应用，并确认是否到前台。
 * 前台匹配 → Done(ReadBack)；出现选择器 / 后台启动被拦 / 未确认到前台 → Unknown；不用于读网页。
 */
internal class AppOpenTool(
    private val backend: AppOpenBackend,
) : ToolContract<AppOpenInput, AppOpenOutput> {
    override val name = "app_open"
    override val domain = ToolDomain.APP
    override val summary =
        "打开应用或把 URI（https、tel、geo、deep link）交给对应应用，并确认是否到前台。" +
            "package／name／uri 三选一；wait_ms 1000–10000 默认 3000。读网页用 browser_*。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("package", "精确包名（三选一）")
        string("name", "应用名（三选一；匹配多个会返回候选）")
        string("uri", "要交给应用的 URI（三选一；须含 scheme，如 https:// tel: geo:）")
        integer("wait_ms", "确认前台的最长等待毫秒，1000–10000，默认 3000", min = 1000, max = 10000)
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): AppOpenInput {
        val pkg = args.stringOrNull("package")?.trim()?.takeIf { it.isNotEmpty() }
        val name = args.stringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() }
        val uri = args.stringOrNull("uri")?.trim()?.takeIf { it.isNotEmpty() }
        val provided = listOfNotNull(pkg, name, uri)
        if (provided.size != 1) invalidArgs("package、name、uri 必须且只能三选一")
        val target = when {
            pkg != null -> AppOpenTarget.ByPackage(pkg)
            name != null -> AppOpenTarget.ByName(name)
            else -> {
                // uri 须含 scheme。
                if (!uri!!.contains(':') || uri.substringBefore(':').isBlank()) {
                    invalidArgs("uri 缺少 scheme", "示例：https://example.com、tel:10086")
                }
                AppOpenTarget.ByUri(uri)
            }
        }
        return AppOpenInput(target, args.int("wait_ms", default = 3000, range = 1000..10000).toLong())
    }

    override fun resolve(input: AppOpenInput, env: ToolEnvironment): CallResolution =
        // 仅本地切换前台应用，不外发也不持久化未信内容；读屏/读网页后不应被污点逐个拦（exfiltrates=false）。
        // 打开应用后在其中的敏感操作由受保护应用名单 + 声明 effect 兜底。
        CallResolution(
            risk = Risk.LOCAL, sensitivity = Sensitivity.NORMAL, resources = emptySet(), exfiltrates = false,
        )

    override fun execute(
        input: AppOpenInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<AppOpenOutput> {
        ctx.checkCancelled()
        return when (val target = input.target) {
            is AppOpenTarget.ByPackage -> openPackage(target.packageName, input.waitMs, ctx)
            is AppOpenTarget.ByName -> openByName(target.name, input.waitMs, ctx)
            is AppOpenTarget.ByUri -> openUri(target.uri, input.waitMs, ctx)
        }
    }

    private fun openByName(name: String, waitMs: Long, ctx: ToolContext): Verdict<AppOpenOutput> {
        val matches = backend.searchByName(name)
        val exact = matches.filter { it.name.equals(name, ignoreCase = true) }
        val chosen = when {
            exact.size == 1 -> exact.single()
            matches.size == 1 -> matches.single()
            matches.isEmpty() -> return Verdict.Failed(
                ToolError(ToolErrorCode.NOT_FOUND, "未找到应用：$name"),
            )
            else -> return Verdict.Failed(
                ToolError(
                    ToolErrorCode.AMBIGUOUS,
                    "匹配到多个应用，请用 package 指定",
                    detail = candidatesDetail(matches),
                ),
            )
        }
        return openPackage(chosen.packageName, waitMs, ctx)
    }

    private fun openPackage(packageName: String, waitMs: Long, ctx: ToolContext): Verdict<AppOpenOutput> {
        if (!backend.launchPackage(packageName)) {
            return Verdict.Failed(ToolError(ToolErrorCode.NOT_FOUND, "应用不可启动或未安装：$packageName"))
        }
        return confirmForeground(packageName, waitMs, ctx)
    }

    private fun openUri(uri: String, waitMs: Long, ctx: ToolContext): Verdict<AppOpenOutput> {
        return when (val dispatch = backend.launchUri(uri)) {
            UriDispatch.InvalidScheme ->
                Verdict.Failed(ToolError(ToolErrorCode.INVALID_ARGUMENTS, "uri 缺少 scheme"))
            UriDispatch.NoActivity ->
                Verdict.Failed(ToolError(ToolErrorCode.NOT_FOUND, "没有应用可以处理该 URI：$uri"))
            is UriDispatch.Ok -> confirmForeground(dispatch.targetPackage, waitMs, ctx)
        }
    }

    private fun confirmForeground(
        targetPackage: String?,
        waitMs: Long,
        ctx: ToolContext,
    ): Verdict<AppOpenOutput> {
        ctx.checkCancelled()
        val outcome = backend.awaitForeground(targetPackage, waitMs)
        val output = AppOpenOutput(
            targetPackage = targetPackage,
            foregroundPackage = outcome.foregroundPackage,
            foreground = outcome.matched,
        )
        return when {
            outcome.matched -> Verdict.Done(output, Evidence.ReadBack("foreground=${outcome.foregroundPackage}"))
            outcome.resolverShown -> Verdict.Unknown(
                reason = "出现应用选择器，尚未进入目标应用",
                next = "用 ui_observe 看选择器并点选，或改用 package 精确打开",
            )
            outcome.backgroundBlocked -> Verdict.Unknown(
                reason = "启动可能被系统拦截（后台启动限制），未确认到前台",
                next = "提示用户为 Movo 开启“后台弹出界面”权限后重试；不要直接重复",
            )
            targetPackage == null -> Verdict.Unknown(
                reason = "已把 URI 交给系统，但无法确定目标包名，未能确认前台",
                next = "用 ui_observe 确认当前界面",
            )
            else -> Verdict.Unknown(
                reason = "已派发打开请求，但未在 ${waitMs}ms 内确认目标到前台（当前=${outcome.foregroundPackage ?: "未知"}）",
                next = "用 ui_observe 或稍后重新确认，不要直接重复",
            )
        }
    }

    private fun candidatesDetail(matches: List<AppMatch>): String {
        val array = JSONArray()
        matches.take(10).forEach { array.put(JSONObject().put("name", it.name).put("package", it.packageName)) }
        return JSONObject().put("candidates", array).toString()
    }

    override fun renderForModel(output: AppOpenOutput): ModelContent =
        ModelContent.Json(
            JSONObject()
                .put("target_package", output.targetPackage ?: JSONObject.NULL)
                .put("foreground_package", output.foregroundPackage ?: JSONObject.NULL)
                .put("foreground", output.foreground),
        )
}
