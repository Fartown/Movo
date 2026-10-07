package io.github.fartown.movo.agent.tools.browser

import android.content.Context
import io.github.fartown.movo.agent.browser.AgentBrowserSession
import io.github.fartown.movo.agent.tools.core.LegacyResults
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 真实离屏浏览器后端：包装既有的 [AgentBrowserSession]（离屏 WebView、DOM 脚本、navigationGeneration、
 * data-movo-ref）。只做“适配”，不重写浏览逻辑：构造旧的 action 参数、调用 execute、把旧 JSON 信封
 * 和错误码映射到新的类型化结果。
 *
 * 已接线的能力：
 *  - navigationGeneration 对外暴露，续读游标绑代际，跨页面的旧游标报 STALE_OBSERVATION；
 *  - inspect_target 脚本做精确可编辑判定 + 提交点原始信号，分类在 [BrowserSubmitHeuristics]；
 *  - 截图按「截图像素/CSS 像素」给出 scale，performAct/inspect 的坐标据此换算回 CSS 像素；
 *  - performAct 对比动作前后 URL/标题判定 navigated；
 *  - 页面内 file/content/intent/javascript 跳转由 BrowserClient.shouldOverrideUrlLoading 拦截。
 *
 * 仍有的近似：data-movo-ref 未写入 DOM，elements 的 ref 用 selector 充当，故 act 目标的
 * stale（代际过期）暂判 false（找不到即 exists=false，不区分“过期”）。
 */
internal class RealBrowserBackend(
    private val context: Context,
) : BrowserBackend {

    /** 最近一次截图得出的「截图像素 / CSS 像素」缩放比；用于把 browser_act 的坐标换算回 CSS 像素。 */
    @Volatile
    private var lastScreenshotScale: Double = 0.0

    override fun state(): BrowserState {
        val snap = AgentBrowserSession.snapshots.value
        return BrowserState(
            available = snap.available,
            url = snap.url,
            title = snap.title,
            host = snap.host,
            canGoBack = snap.canGoBack,
            canGoForward = snap.canGoForward,
            userControlling = snap.isUserControlling,
            navigationGeneration = AgentBrowserSession.navigationGeneration(),
        )
    }

    override fun open(url: String, timeoutMs: Long, call: BrowserCall): BrowserPage {
        val json = run(
            JSONObject().put("action", "navigate").put("url", url).put("timeout_ms", timeoutMs),
            call,
            // HTTP 400+ 旧实现报 HTTP_<code>，这里当作成功页带回 http_status。
            allowCodes = { it.startsWith("HTTP_") },
        )
        return page(json)
    }

    override fun navigate(nav: BrowserNav, call: BrowserCall): BrowserPage {
        val action = when (nav) {
            BrowserNav.BACK -> "go_back"
            BrowserNav.FORWARD -> "go_forward"
            BrowserNav.RELOAD -> "reload"
        }
        val json = run(JSONObject().put("action", action), call)
        return page(json)
    }

    override fun readReadable(maxChars: Int, cursor: String?): BrowserTextRead {
        val generation = AgentBrowserSession.navigationGeneration()
        val offset = decodeCursor(cursor, generation)
        val json = run(
            JSONObject().put("action", "get_readable").put("max_chars", maxChars).put("offset", offset),
            NO_CALL,
        )
        return textRead(json, generation, defaultFormat = "markdown")
    }

    override fun readText(selector: String?, maxChars: Int, cursor: String?): BrowserTextRead {
        val generation = AgentBrowserSession.navigationGeneration()
        val offset = decodeCursor(cursor, generation)
        val args = JSONObject().put("action", "get_text").put("max_chars", maxChars).put("offset", offset)
        selector?.let { args.put("selector", it) }
        return textRead(run(args, NO_CALL), generation, defaultFormat = "text")
    }

    /** 解析续读游标（绑 navigationGeneration）；代际不符即 STALE_OBSERVATION。 */
    private fun decodeCursor(cursor: String?, generation: Long): Int =
        when (val decoded = BrowserReadCursor.decode(cursor, generation)) {
            is CursorResult.Offset -> decoded.value
            CursorResult.Stale -> throw BrowserException(
                ToolErrorCode.STALE_OBSERVATION,
                "页面已变化，续读游标已失效",
                hint = "重新 browser_read 从头读取",
            )
        }

    override fun readElements(selector: String?, cursor: String?): BrowserElementsRead {
        val args = JSONObject().put("action", "find_elements")
        selector?.let { args.put("selector", it) }
        val json = run(args, NO_CALL)
        val array = json.optJSONArray("elements") ?: JSONArray()
        val elements = (0 until array.length()).map { index ->
            val item = array.optJSONObject(index) ?: JSONObject()
            val sel = item.optString("selector")
            BrowserElement(
                // TODO(browser)：旧脚本未写 data-movo-ref，用 selector 充当 ref（browser_act 按 selector 使用）。
                ref = sel,
                role = item.optString("role").ifBlank { item.optString("tag", "element") },
                text = item.optString("text"),
                selector = sel,
                editable = isEditable(item),
                href = item.optString("href").ifBlank { null },
                bounds = item.optJSONObject("bounds"),
            )
        }
        // find_elements 旧实现不分页（≤16 条），无续读游标。
        return BrowserElementsRead(elements = elements, nextCursor = null)
    }

    override fun screenshot(): BrowserScreenshot {
        val args = JSONObject().put("action", "screenshot").put("read_image", true)
        val result = AgentBrowserSession.execute(context, args, "", "")
        val json = parse(result.content)
        throwIfError(json) { false }
        val image = result.images.firstOrNull()
            ?: throw BrowserException(ToolErrorCode.SOURCE_UNAVAILABLE, "截图失败")
        // 缩放比 = 截图像素宽 / 视口 CSS 宽（= captureScale × devicePixelRatio）。
        // 另取一次 page_info 拿 CSS 视口宽；两次调用串行，离屏视口不变，值稳定。
        val viewportCssWidth = runCatching { pageInfo().optDouble("viewport_width", 0.0) }.getOrDefault(0.0)
        val scale = BrowserCoordinates.scale(image.width, viewportCssWidth)
        lastScreenshotScale = scale
        return BrowserScreenshot(
            dataUrl = image.dataUrl,
            mimeType = image.mimeType,
            bytes = image.bytes,
            width = image.width,
            height = image.height,
            scale = scale,
        )
    }

    override fun pageInfo(): JSONObject {
        val json = run(JSONObject().put("action", "get_page_info"), NO_CALL)
        val info = JSONObject()
        json.keys().forEach { key -> if (key !in ENVELOPE_KEYS) info.put(key, json.get(key)) }
        return info
    }

    override fun waitForSelector(selector: String, timeoutMs: Long): Boolean {
        val json = probeSelector(selector, timeoutMs)
        return json.optBoolean("found", false)
    }

    override fun inspectActTarget(request: BrowserActRequest): BrowserActTarget {
        val selector = request.target.ref ?: request.target.selector
        val args = JSONObject().put("action", "inspect_target")
        val fallbackSummary: String
        if (selector != null) {
            args.put("selector", selector)
            fallbackSummary = selector
        } else {
            // 坐标目标：把截图像素换算回 CSS 像素再探测。
            val x = request.target.x
            val y = request.target.y
            val scale = currentActScale()
            if (x != null && y != null) {
                args.put("coordinate_x", BrowserCoordinates.toCssPixel(x, scale))
                args.put("coordinate_y", BrowserCoordinates.toCssPixel(y, scale))
            }
            fallbackSummary = "($x,$y)"
        }

        val json = run(args, NO_CALL)
        if (!json.optBoolean("found", false)) {
            // ref/selector 没命中：当前页面找不到目标（无 data-movo-ref 代际，不区分“过期”）。
            return BrowserActTarget(
                exists = false, stale = false, visible = false, editable = false,
                summary = fallbackSummary, submitPoint = false, searchRole = false,
            )
        }
        val classification = BrowserSubmitHeuristics.classify(
            BrowserTargetSignals(
                tag = json.optString("tag"),
                type = json.optString("type"),
                role = json.optString("role"),
                formPresent = json.optBoolean("form_present", false),
                formMethod = json.optString("form_method"),
                formRole = json.optString("form_role"),
                inSearch = json.optBoolean("in_search", false),
                isPassword = json.optBoolean("is_password", false),
                isSubmitControl = json.optBoolean("is_submit_control", false),
                buttonText = json.optString("button_text"),
            ),
        )
        return BrowserActTarget(
            exists = true,
            // ref 以 selector 充当（data-movo-ref 未实现），无代际对照 → 不判过期。
            stale = false,
            visible = json.optBoolean("visible", false),
            editable = json.optBoolean("editable", false),
            summary = json.optString("summary").ifBlank { fallbackSummary },
            submitPoint = classification.submitPoint,
            searchRole = classification.searchRole,
        )
    }

    /** 坐标换算用的当前缩放比：优先用最近一次截图得出的值，否则临时取一次视口 dpr。 */
    private fun currentActScale(): Double {
        lastScreenshotScale.takeIf { it > 0.0 }?.let { return it }
        // 无截图在先：退化为 devicePixelRatio（captureScale≈1 时成立），并记录供下次复用。
        val dpr = runCatching { pageInfo().optDouble("device_pixel_ratio", 1.0) }.getOrDefault(1.0)
        return dpr.takeIf { it > 0.0 } ?: 1.0
    }

    override fun performAct(request: BrowserActRequest, call: BrowserCall): BrowserActResult {
        val args = JSONObject()
        val selector = request.target.ref ?: request.target.selector
        selector?.let { args.put("selector", it) }
        // 截图像素 → CSS 像素：坐标来自模型看到的截图，除以缩放比换算回 CSS。
        if (request.target.x != null && request.target.y != null) {
            val scale = currentActScale()
            args.put("coordinate_x", BrowserCoordinates.toCssPixel(request.target.x, scale))
            args.put("coordinate_y", BrowserCoordinates.toCssPixel(request.target.y, scale))
        }
        when (request.action) {
            BrowserActionType.CLICK -> args.put("action", "click")
            BrowserActionType.TYPE -> {
                args.put("action", "type").put("text", request.text.orEmpty()).put("submit", request.submit)
            }
            BrowserActionType.SCROLL -> {
                args.put("action", "scroll").put("direction", request.direction ?: "down")
                    .put("amount", request.amount ?: 600)
            }
            BrowserActionType.SELECT ->
                args.put("action", "select").put(
                    "option",
                    request.option
                        ?: throw BrowserException(ToolErrorCode.INVALID_ARGUMENTS, "select 需要 option（选项值或可见文字）"),
                )
            BrowserActionType.KEY ->
                args.put("action", "key").put(
                    "key",
                    request.key
                        ?: throw BrowserException(ToolErrorCode.INVALID_ARGUMENTS, "key 需要 key（enter、esc、tab、backspace、up、down、left、right）"),
                )
        }
        // 动作前记录 URL/标题，动作+settle 后回读对比，判定是否跳转。
        val before = AgentBrowserSession.snapshots.value
        val result = AgentBrowserSession.execute(context, args, call.runId, call.toolCallId)
        val json = parse(result.content)
        if (!json.optBoolean("ok", false)) {
            val code = json.optString("code")
            if (code == "ACTION_TIMEOUT") {
                // 动作后加载超时：已派发但无法确认 → 让工具层报 OUTCOME_UNKNOWN。
                return BrowserActResult(
                    navigated = false, url = json.optString("url"), title = null,
                    targetSummary = selector.orEmpty(), loadTimedOut = true,
                )
            }
            throw toException(json)
        }
        val matched = json.optJSONObject("matched_element")
        val summary = matched?.let { it.optString("text").ifBlank { it.optString("selector") } }
            ?: selector.orEmpty()
        val navigation = BrowserNavigation.detect(
            beforeUrl = before.url,
            beforeTitle = before.title,
            afterUrl = json.optString("url"),
            afterTitle = json.optString("title"),
        )
        return BrowserActResult(
            navigated = navigation.navigated,
            url = navigation.url,
            title = navigation.title,
            targetSummary = summary,
            loadTimedOut = false,
        )
    }

    override fun inPageSchemeGuardInstalled(): Boolean = AgentBrowserSession.schemeGuardInstalled()

    // ---- 旧信封解析与错误映射 ----

    private fun run(args: JSONObject, call: BrowserCall, allowCodes: (String) -> Boolean = { false }): JSONObject {
        val result = AgentBrowserSession.execute(context, args, call.runId, call.toolCallId)
        val json = parse(result.content)
        throwIfError(json, allowCodes)
        return json
    }

    private fun probeSelector(selector: String, timeoutMs: Long): JSONObject {
        val result = AgentBrowserSession.execute(
            context,
            JSONObject().put("action", "wait_for_selector").put("selector", selector).put("timeout_ms", timeoutMs),
            "", "",
        )
        // not_found 时 ok=false 但仍带 found/visible 字段，直接读取，不抛错。
        return parse(result.content)
    }

    private fun throwIfError(json: JSONObject, allowCodes: (String) -> Boolean) {
        if (json.optBoolean("ok", false)) return
        val code = json.optString("code")
        if (allowCodes(code)) return
        throw toException(json)
    }

    private fun toException(json: JSONObject): BrowserException {
        val code = json.optString("code").ifBlank { "BROWSER_ERROR" }
        val message = json.optString("message").ifBlank { "浏览器操作失败" }
        return BrowserException(mapCode(code), message, detail = code)
    }

    private fun mapCode(legacy: String): ToolErrorCode = when {
        legacy == "NAVIGATION_SUPERSEDED" -> ToolErrorCode.SUPERSEDED
        legacy == "RENDERER_GONE" -> ToolErrorCode.SOURCE_UNAVAILABLE
        legacy == "USER_CONTROL_ACTIVE" -> ToolErrorCode.BUSY
        legacy == "HISTORY_UNAVAILABLE" || legacy == "NO_PAGE" || legacy == "ELEMENT_NOT_FOUND" ->
            ToolErrorCode.NOT_FOUND
        legacy == "CANCELLED" -> ToolErrorCode.CANCELLED
        legacy.startsWith("HTTP_") -> ToolErrorCode.NETWORK_ERROR
        else -> LegacyResults.map(legacy)
    }

    private fun page(json: JSONObject): BrowserPage = BrowserPage(
        url = json.optString("url"),
        title = json.optString("title"),
        httpStatus = if (json.has("http_status")) json.optInt("http_status") else null,
        redirected = json.optBoolean("redirected", false),
        canGoBack = json.optBoolean("can_go_back", false),
        canGoForward = json.optBoolean("can_go_forward", false),
    )

    private fun textRead(json: JSONObject, generation: Long, defaultFormat: String): BrowserTextRead {
        val text = json.optString("text")
        val truncated = json.optBoolean("truncated", false)
        // next_offset 可能为 null（JS 已读完）：没有下一段就不发游标。
        val hasNext = truncated && !json.isNull("next_offset")
        return BrowserTextRead(
            text = text,
            format = json.optString("content_format", defaultFormat),
            language = json.optString("language").ifBlank { null },
            canonicalUrl = json.optString("canonical_url").ifBlank { null },
            returnedChars = json.optInt("returned_chars", text.length),
            nextCursor = if (hasNext) BrowserReadCursor.encode(generation, json.optInt("next_offset")) else null,
        )
    }

    private fun isEditable(item: JSONObject): Boolean {
        val tag = item.optString("tag").lowercase()
        if (tag == "textarea" || tag == "select") return true
        if (tag == "input") {
            val type = item.optString("type").lowercase()
            return type !in setOf("button", "submit", "reset", "checkbox", "radio", "range", "file", "hidden")
        }
        return item.optString("role") == "textbox"
    }

    private fun parse(content: String): JSONObject =
        runCatching { JSONObject(content) }.getOrElse {
            JSONObject().put("ok", false).put("code", "BROWSER_ERROR").put("message", "浏览器结果无法解析")
        }

    private companion object {
        val NO_CALL = BrowserCall("", "")
        val ENVELOPE_KEYS = setOf(
            "ok", "tool", "action", "status", "code", "message",
            "url", "display_url", "host", "title", "is_loading", "can_go_back", "can_go_forward", "http_status",
        )
    }
}
