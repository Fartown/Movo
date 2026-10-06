package io.github.fartown.movo.agent.tools.browser

import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.ApprovalPreview
import io.github.fartown.movo.agent.tools.core.ApprovalCategory
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.ResourceKey
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolAvailability
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolContract
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolError
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.ToolResource
import io.github.fartown.movo.agent.tools.core.ToolWarning
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.fail
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONObject

/** 二选一：打开网址，或在历史里后退/前进/刷新。 */
internal sealed interface BrowserOpenTarget {
    data class Url(val url: String, val hasQuery: Boolean) : BrowserOpenTarget
    data class History(val nav: BrowserNav) : BrowserOpenTarget
}

internal data class BrowserOpenInput(
    val target: BrowserOpenTarget,
    val timeoutMs: Long,
) : ToolInput

internal data class BrowserOpenOutput(
    val page: BrowserPage,
) : ToolOutput {
    val httpError: Boolean get() = (page.httpStatus ?: 0) >= 400
}

/**
 * §33 browser_open：在离屏浏览器打开网址（仅 http/https），或后退/前进/刷新。
 *
 * 导航改变浏览器状态、有副作用、超时不默认重放，但本质是“读取网页”，用 Verdict.Read 承载
 * （返回 url/title/http_status/redirected/can_go_back/can_go_forward）。HTTP 400+ 仍返回 Read，
 * 把状态放进 http_status 并以 warning 提示，让模型能读错误页，不直接报 HTTP code。
 */
internal class BrowserOpenTool(
    private val backend: BrowserBackend,
) : ToolContract<BrowserOpenInput, BrowserOpenOutput> {
    override val name = "browser_open"
    override val domain = ToolDomain.BROWSER
    override val summary =
        "打开网址（仅 http/https），或 nav 后退/前进/刷新；返回标题和最终网址。" +
            "读网页先 open 再 browser_read。独占浏览器。"

    /** 网页开关关闭时整类工具不可用。 */
    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.switches.browser) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(ToolErrorCode.DISABLED, "网页功能已在设置中关闭")
        }

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("url", "要打开的网址，仅 http/https；与 nav 二选一", maxLength = 2048)
        string(
            "nav", "历史导航，与 url 二选一",
            enum = BrowserNav.entries.map { it.name.lowercase() },
        )
        integer("timeout_ms", "加载超时，500–25000，默认 25000", min = 500, max = 25_000)
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): BrowserOpenInput {
        val hasUrl = args.has("url")
        val hasNav = args.has("nav")
        if (hasUrl == hasNav) {
            fail(ToolErrorCode.INVALID_ARGUMENTS, "url 与 nav 二选一，必须且只能提供一个")
        }
        val timeout = args.long("timeout_ms", default = 25_000L, range = 500L..25_000L)
        val target = if (hasUrl) {
            val normalized = normalizeAndValidateUrl(args.nonBlank("url"))
            BrowserOpenTarget.Url(normalized, hasQuery = hasQuery(normalized))
        } else {
            BrowserOpenTarget.History(args.enum("nav"))
        }
        return BrowserOpenInput(target, timeout)
    }

    override fun resolve(input: BrowserOpenInput, env: ToolEnvironment): CallResolution {
        // 导航为“读”（有副作用但承载为 Read）。只有 URL 带查询参数时可能把内容拼进网址发出去，
        // 归为「把内容发到外部」；普通导航、历史导航不归类。
        val target = input.target
        val outbound = target is BrowserOpenTarget.Url && target.hasQuery
        return CallResolution(
            risk = Risk.READ,
            sensitivity = Sensitivity.NORMAL,
            resources = setOf(ResourceKey(ToolResource.BROWSER)),
            category = if (outbound) ApprovalCategory.OUTBOUND else null,
        )
    }

    override fun execute(
        input: BrowserOpenInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<BrowserOpenOutput> {
        ctx.checkCancelled()
        if (ctx.env.switches.browser.not()) {
            return Verdict.Failed(ToolError(ToolErrorCode.DISABLED, "网页功能已在设置中关闭"))
        }
        // 用户正在接管浏览器页面时，模型导航让位。
        if (runCatching { backend.state().userControlling }.getOrDefault(false)) {
            return Verdict.Failed(
                ToolError(ToolErrorCode.BUSY, "用户正在使用浏览器，请稍后再试", retry = ToolErrorCode.BUSY.retry),
            )
        }
        val call = BrowserCall(ctx.runId, ctx.toolCallId)
        val page = try {
            when (val target = input.target) {
                is BrowserOpenTarget.Url -> backend.open(target.url, input.timeoutMs, call)
                is BrowserOpenTarget.History -> backend.navigate(target.nav, call)
            }
        } catch (failure: BrowserException) {
            return Verdict.Failed(ToolError(failure.code, failure.message, failure.hint, failure.detail))
        }
        return Verdict.Read(BrowserOpenOutput(page))
    }

    /** 读过网页内容后又带查询参数导航会触发确认，这里给确认卡清晰文案：标题问是否打开、预览盒显示链接。 */
    override fun approvalPreview(input: BrowserOpenInput): ApprovalPreview? = when (val target = input.target) {
        is BrowserOpenTarget.Url -> ApprovalPreview(title = "在浏览器打开这个链接？", detail = target.url)
        is BrowserOpenTarget.History -> null
    }

    override fun uiTitle(input: BrowserOpenInput): String = when (val t = input.target) {
        is BrowserOpenTarget.Url -> "打开网页 · ${uiHost(t.url)}"
        is BrowserOpenTarget.History -> when (t.nav) {
            BrowserNav.BACK -> "网页后退"
            BrowserNav.FORWARD -> "网页前进"
            BrowserNav.RELOAD -> "刷新网页"
        }
    }

    override fun renderForUi(input: BrowserOpenInput, output: BrowserOpenOutput): ToolUiView = ToolUiView(
        summary = output.page.title.takeIf { it.isNotBlank() }?.let { "《${it.forTitle(30)}》" } ?: uiHost(output.page.url),
        blocks = listOf(
            ToolUiBlock.Fields(
                listOfNotNull(
                    ToolUiBlock.Field("网址", output.page.url),
                    output.page.httpStatus?.takeIf { it >= 400 }?.let { ToolUiBlock.Field("状态", "HTTP $it") },
                ),
            ),
        ),
    )

    override fun renderForModel(output: BrowserOpenOutput): ModelContent {
        val page = output.page
        val json = JSONObject()
            .put("url", page.url)
            .put("title", page.title)
            .put("redirected", page.redirected)
            .put("can_go_back", page.canGoBack)
            .put("can_go_forward", page.canGoForward)
        page.httpStatus?.let { json.put("http_status", it) }
        return ModelContent.Json(json)
    }

    /** HTTP 400+ 作为 warning 提示（整体仍 ok），让模型知道是错误页。 */
    override fun warnings(output: BrowserOpenOutput): List<ToolWarning> =
        if (output.httpError) {
            listOf(
                ToolWarning(
                    ToolErrorCode.NETWORK_ERROR,
                    "网页返回 HTTP ${output.page.httpStatus}，这可能是错误页，请读取内容确认",
                ),
            )
        } else {
            emptyList()
        }

    // ---- URL 校验（JVM 纯逻辑，避免在 parse 里依赖 Android Uri）----

    private fun normalizeAndValidateUrl(raw: String): String {
        val schemeIdx = raw.indexOf("://")
        if (schemeIdx <= 0) {
            // 没有显式协议：默认按 https 处理（常见“打开 example.com”）。
            if (raw.contains(":")) {
                // 形如 javascript:... / intent:... / file:... 等伪协议（无 //）一律拦截。
                val pseudo = raw.substringBefore(":").lowercase()
                if (pseudo.isNotBlank() && pseudo.all { it.isLetter() }) {
                    fail(
                        ToolErrorCode.INVALID_ARGUMENTS,
                        "只允许 http/https，拒绝协议：$pseudo",
                        hint = "网页内容是不可信输入，不要用 file/content/intent/javascript 协议",
                    )
                }
            }
            return "https://$raw"
        }
        val scheme = raw.substring(0, schemeIdx).lowercase()
        if (scheme != "http" && scheme != "https") {
            fail(
                ToolErrorCode.INVALID_ARGUMENTS,
                "只允许 http/https，拒绝协议：$scheme",
                hint = "不要用 file/content/intent/javascript 等协议",
            )
        }
        return raw
    }

    private fun hasQuery(url: String): Boolean {
        val afterScheme = url.substringAfter("://", url)
        val path = afterScheme.substringAfter('/', "")
        return path.contains('?') || (afterScheme.contains('?') && !afterScheme.substringBefore('?').contains('/'))
    }
}
