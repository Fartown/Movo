package io.github.fartown.movo.agent.tools.browser

import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import org.json.JSONObject

/**
 * 网页领域（domain=BROWSER）三个工具共享的离屏浏览器后端。
 *
 * 真实实现包装 [io.github.fartown.movo.agent.browser.AgentBrowserSession]（离屏 WebView + DOM 脚本 +
 * navigationGeneration + data-movo-ref）；测试用假实现。所有方法都在工作线程调用（不在主线程）。
 * 失败统一抛 [BrowserException]，由工具层转成 Verdict.Failed，保留映射后的错误码。
 */
internal interface BrowserBackend {
    /** 当前页面快照；无页面时 [BrowserState.available] = false。 */
    fun state(): BrowserState

    /**
     * 模型主动导航到 url（协议已在工具层校验为 http/https）。HTTP 400+ 不当作失败，
     * 在 [BrowserPage.httpStatus] 带回，让模型读错误页。
     */
    fun open(url: String, timeoutMs: Long, call: BrowserCall): BrowserPage

    /** 历史导航：back / forward / reload。无可后退/前进时抛 NOT_FOUND。 */
    fun navigate(nav: BrowserNav, call: BrowserCall): BrowserPage

    fun readReadable(maxChars: Int, cursor: String?): BrowserTextRead

    /** selector 为空时读整页纯文本。 */
    fun readText(selector: String?, maxChars: Int, cursor: String?): BrowserTextRead

    fun readElements(selector: String?, cursor: String?): BrowserElementsRead

    fun screenshot(): BrowserScreenshot

    fun pageInfo(): JSONObject

    /** 轮询等待 selector 出现；返回是否出现。 */
    fun waitForSelector(selector: String, timeoutMs: Long): Boolean

    /**
     * 动作前对目标做一次只读探测：是否存在、是否过期（ref/gen 失效）、是否可见可编辑、
     * 以及提交点识别（表单含密码/支付、method=post、按钮文字命中提交点）。不改变页面。
     */
    fun inspectActTarget(request: BrowserActRequest): BrowserActTarget

    /** 执行动作（送达型）。跳转时在 [BrowserActResult] 附 title；动作后加载超时置 loadTimedOut。 */
    fun performAct(request: BrowserActRequest, call: BrowserCall): BrowserActResult

    /**
     * 页面内跳转是否已拦截 file/content/intent/javascript（安全 B）。模型主动导航在工具层拦，
     * 页面内跳转要靠 WebViewClient.shouldOverrideUrlLoading（BS:1042+，当前旧实现未补）。
     */
    fun inPageSchemeGuardInstalled(): Boolean
}

/** 每次调用的运行身份：真实后端用来把结果归因到本轮工具调用。 */
internal data class BrowserCall(val runId: String, val toolCallId: String)

/** 浏览器当前页面快照。 */
internal data class BrowserState(
    val available: Boolean,
    val url: String,
    val title: String,
    val host: String,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    /** 用户是否正接管浏览器页面（接管期间模型动作应报 BUSY）。 */
    val userControlling: Boolean,
    /** 当前导航代际，cursor 绑定它，用于续读失效判定。 */
    val navigationGeneration: Long,
)

/** 导航 / 历史结果。 */
internal data class BrowserPage(
    val url: String,
    val title: String,
    val httpStatus: Int?,
    val redirected: Boolean,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
)

internal enum class BrowserNav { BACK, FORWARD, RELOAD }

/** 文本读取结果（readable / text）。 */
internal data class BrowserTextRead(
    val text: String,
    /** markdown（readable）或 text。 */
    val format: String,
    val language: String?,
    val canonicalUrl: String?,
    val returnedChars: Int,
    /** 续读游标（绑 navigationGeneration）；无更多为 null。 */
    val nextCursor: String?,
)

/** 可交互元素。bounds 为页面 CSS 像素坐标。 */
internal data class BrowserElement(
    val ref: String,
    val role: String,
    val text: String,
    val selector: String,
    val editable: Boolean,
    val href: String?,
    val bounds: JSONObject?,
)

internal data class BrowserElementsRead(
    val elements: List<BrowserElement>,
    val nextCursor: String?,
)

/**
 * 截图结果。dataUrl 直接作为 ModelImage.reference 附给模型。
 * [scale] = 截图像素 / 页面 CSS 像素；browser_act 的坐标是截图像素，运行时除以 scale 换算回 CSS 像素。
 */
internal data class BrowserScreenshot(
    val dataUrl: String,
    val mimeType: String,
    val bytes: Int,
    val width: Int,
    val height: Int,
    val scale: Double,
)

internal enum class BrowserActionType { CLICK, TYPE, SCROLL, SELECT, KEY }

/** 目标三选一：ref / selector / 坐标（截图像素）。 */
internal data class BrowserTargetSpec(
    val ref: String?,
    val selector: String?,
    val x: Int?,
    val y: Int?,
) {
    val hasCoordinate: Boolean get() = x != null && y != null
    val specifiedCount: Int get() =
        listOf(ref != null, selector != null, hasCoordinate).count { it }
}

internal data class BrowserActRequest(
    val action: BrowserActionType,
    val target: BrowserTargetSpec,
    val text: String?,
    val submit: Boolean,
    val option: String?,
    val key: String?,
    val direction: String?,
    val amount: Int?,
)

/** 动作前的目标分类（提交点识别）。 */
internal data class BrowserActTarget(
    val exists: Boolean,
    /** ref/gen 失效：脱离页面或代际不符。 */
    val stale: Boolean,
    val visible: Boolean,
    val editable: Boolean,
    val summary: String,
    /** 表单含密码/支付、method=post、按钮文字命中提交点。 */
    val submitPoint: Boolean,
    /** role=search 或 GET 表单：不确认。 */
    val searchRole: Boolean,
)

/** 动作结果（送达型）。 */
internal data class BrowserActResult(
    val navigated: Boolean,
    val url: String,
    /** 跳转时附新页面标题。 */
    val title: String?,
    val targetSummary: String,
    /** 动作后页面加载超时：结果无法确认 → OUTCOME_UNKNOWN。 */
    val loadTimedOut: Boolean,
)

/** 后端失败：携带已映射的统一错误码。 */
internal class BrowserException(
    val code: ToolErrorCode,
    override val message: String,
    val hint: String? = null,
    val detail: String? = null,
) : RuntimeException(message)
