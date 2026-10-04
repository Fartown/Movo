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
 * 留 TODO 的真实能力（签名完整、返回合理占位），已在返回报告列出：
 *  - navigationGeneration 未对外暴露，cursor 的失效（STALE_OBSERVATION）尚不能严格绑定代际；
 *  - data-movo-ref 在旧 DOM 脚本里还没写入，elements 用 selector 充当 ref；
 *  - 旧 DOM 脚本没有 select / key / 提交点识别脚本，select/key 暂报 UNSUPPORTED，提交点探测返回保守值；
 *  - 截图缩放比例 scale 旧实现未暴露，暂记 1.0（坐标换算按 1:1）；
 *  - “页面内跳转”的 file/content/intent/javascript 拦截需在 BrowserClient 补 shouldOverrideUrlLoading。
 */
internal class RealBrowserBackend(
    private val context: Context,
) : BrowserBackend {

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
            // TODO(browser)：navigationGeneration 未对外暴露，cursor 代际绑定暂缺。
            navigationGeneration = 0L,
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
        val json = run(
            JSONObject().put("action", "get_readable").put("max_chars", maxChars)
                .put("offset", cursor?.toIntOrNull() ?: 0),
            NO_CALL,
        )
        return textRead(json, defaultFormat = "markdown")
    }

    override fun readText(selector: String?, maxChars: Int, cursor: String?): BrowserTextRead {
        val args = JSONObject().put("action", "get_text").put("max_chars", maxChars)
            .put("offset", cursor?.toIntOrNull() ?: 0)
        selector?.let { args.put("selector", it) }
        return textRead(run(args, NO_CALL), defaultFormat = "text")
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
        return BrowserScreenshot(
            dataUrl = image.dataUrl,
            mimeType = image.mimeType,
            bytes = image.bytes,
            width = image.width,
            height = image.height,
            // TODO(browser)：旧 captureViewport 的缩放比例未暴露，暂按 1:1。
            scale = 1.0,
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
        if (selector == null) {
            // 坐标目标无法只读探测，保守放行（存在且可见），交给 performAct 兜底。
            return BrowserActTarget(
                exists = true, stale = false, visible = true, editable = true,
                summary = "(${request.target.x},${request.target.y})",
                submitPoint = false, searchRole = false,
            )
        }
        val json = probeSelector(selector, 500L)
        return BrowserActTarget(
            exists = json.optBoolean("found", false),
            // TODO(browser)：没有 navigationGeneration 对照，ref 失效无法区分“找不到”和“过期”，暂记 false。
            stale = false,
            visible = json.optBoolean("visible", false),
            // TODO(browser)：enabled 近似 editable；精确可编辑/提交点识别需补 DOM 脚本。
            editable = json.optBoolean("enabled", true),
            summary = selector,
            // TODO(browser)：提交点识别（密码/支付/method=post/按钮文字）需补 DOM 脚本，暂报非提交点。
            submitPoint = false,
            searchRole = false,
        )
    }

    override fun performAct(request: BrowserActRequest, call: BrowserCall): BrowserActResult {
        val args = JSONObject()
        val selector = request.target.ref ?: request.target.selector
        selector?.let { args.put("selector", it) }
        request.target.x?.let { args.put("coordinate_x", it) }   // TODO(browser)：截图像素→CSS 像素换算（scale 暂 1:1）
        request.target.y?.let { args.put("coordinate_y", it) }
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
                // TODO(browser)：旧 DOM 脚本无 select，待补充后接线。
                throw BrowserException(ToolErrorCode.UNSUPPORTED, "下拉选择暂未实现", detail = "browser_select_dom_todo")
            BrowserActionType.KEY ->
                // TODO(browser)：旧 DOM 脚本无独立按键派发（仅 type+submit 的 Enter），待补充。
                throw BrowserException(ToolErrorCode.UNSUPPORTED, "按键暂未实现", detail = "browser_key_dom_todo")
        }
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
        return BrowserActResult(
            navigated = false, // TODO(browser)：旧结果不含 navigated；跳转/标题检测待接线。
            url = json.optString("url"),
            title = null,
            targetSummary = summary,
            loadTimedOut = false,
        )
    }

    override fun inPageSchemeGuardInstalled(): Boolean =
        // TODO(browser)：需在 BrowserClient 补 shouldOverrideUrlLoading 拦截 file/content/intent/javascript。
        false

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

    private fun textRead(json: JSONObject, defaultFormat: String): BrowserTextRead {
        val text = json.optString("text")
        val truncated = json.optBoolean("truncated", false)
        return BrowserTextRead(
            text = text,
            format = json.optString("content_format", defaultFormat),
            language = json.optString("language").ifBlank { null },
            canonicalUrl = json.optString("canonical_url").ifBlank { null },
            returnedChars = json.optInt("returned_chars", text.length),
            nextCursor = if (truncated) json.optInt("next_offset").toString() else null,
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
