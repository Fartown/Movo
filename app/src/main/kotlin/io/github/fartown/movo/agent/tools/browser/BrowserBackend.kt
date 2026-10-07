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

    /**
     * 读取类方法也带 [BrowserCall]：成功后聊天里「去浏览器」入口挂到这一步，按停止时也能打断这一步。
     * [offset] 非空时从正文第 offset 个字读起（跳读），否则按 [cursor] 续读。
     */
    fun readReadable(maxChars: Int, cursor: String?, offset: Int?, call: BrowserCall): BrowserTextRead

    /** selector 为空时读整页纯文本。 */
    fun readText(selector: String?, maxChars: Int, cursor: String?, offset: Int?, call: BrowserCall): BrowserTextRead

    fun readElements(selector: String?, cursor: String?, call: BrowserCall): BrowserElementsRead

    fun screenshot(call: BrowserCall): BrowserScreenshot

    /** 页面信息：视口与内容尺寸、滚动位置、语言等，另含 is_loading、can_go_back、can_go_forward、http_status。 */
    fun pageInfo(call: BrowserCall): JSONObject

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
    /** 抽到的正文总长度（字）。 */
    val textLength: Int? = null,
    /** 这一段从正文第几个字开始。 */
    val offset: Int = 0,
    /** 页面太大或太慢，抽正文时就截断了：text_length 只是抽到的那部分。 */
    val sourceTruncated: Boolean = false,
)

/** 可交互元素。bounds 与 browser_act 的 x/y 同一单位：截图像素。 */
internal data class BrowserElement(
    val ref: String,
    val role: String,
    val text: String,
    val selector: String,
    val editable: Boolean,
    val href: String?,
    val bounds: JSONObject?,
    val tag: String? = null,
    val type: String? = null,
    val ariaLabel: String? = null,
    val placeholder: String? = null,
)

internal data class BrowserElementsRead(
    val elements: List<BrowserElement>,
    val nextCursor: String?,
    /** 匹配的元素比列出的多（最多列 16 个）。 */
    val truncated: Boolean = false,
    /** 选择器匹配到的元素总数。 */
    val matchCount: Int? = null,
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
    val tag: String = "",
    val type: String = "",
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
    /** scroll：滚动前后的位置（CSS 像素）；两者相等说明没滚动（到底或到顶了）。 */
    val scrollBefore: Int? = null,
    val scrollAfter: Int? = null,
    /** type：输入了几个字、是否提交了。 */
    val typedChars: Int? = null,
    val submitted: Boolean? = null,
)

/** 后端失败：携带已映射的统一错误码。 */
internal class BrowserException(
    val code: ToolErrorCode,
    override val message: String,
    val hint: String? = null,
    val detail: String? = null,
) : RuntimeException(message)

/** 执行卡上写的网址：只写域名（没有协议、路径和参数）。 */
internal fun uiHost(url: String): String =
    runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url.take(40)

