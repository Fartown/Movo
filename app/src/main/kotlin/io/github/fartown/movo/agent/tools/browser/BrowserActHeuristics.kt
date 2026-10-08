package io.github.fartown.movo.agent.tools.browser

import org.json.JSONObject

/**
 * 网页动作的纯逻辑：截图像素↔CSS 像素换算、提交点判定、跳转判定、续读游标编解码。
 *
 * 全部不依赖真实 WebView，只吃 DOM 脚本抓来的信号 / 数值，便于用假数据做 JUnit 单测。
 * 运行时行为（真实 DOM、真实渲染）仍须真机验证。
 */

/** 截图像素 ↔ 页面 CSS 像素换算。[scale] = 截图像素 / CSS 像素 = captureScale × devicePixelRatio。 */
internal object BrowserCoordinates {

    /**
     * 由截图宽（像素）与视口 CSS 宽算出缩放比。
     * 等价于 captureScale × devicePixelRatio，但直接用两个可测量值相除，最稳。
     * 任一值非法时回退 1.0（按 1:1）。
     */
    fun scale(imageWidthPx: Int, viewportCssWidth: Double): Double =
        if (imageWidthPx > 0 && viewportCssWidth > 0) imageWidthPx / viewportCssWidth else 1.0

    /** 把截图像素坐标换算回 CSS 像素（供 document.elementFromPoint 使用）。 */
    fun toCssPixel(screenshotPx: Int, scale: Double): Int =
        if (scale > 0.0 && scale.isFinite()) Math.round(screenshotPx / scale).toInt() else screenshotPx

    /**
     * 元素位置（DOM 脚本给的 CSS 像素 x/y/width/height）换成截图像素，和 browser_act 的 x/y 同一单位；
     * 另给中心点 center_x/center_y，模型按坐标点时直接用它。
     */
    fun toScreenshotBounds(cssBounds: JSONObject, scale: Double): JSONObject {
        val factor = if (scale > 0.0 && scale.isFinite()) scale else 1.0
        fun px(key: String) = Math.round(cssBounds.optDouble(key, 0.0) * factor).toInt()
        val x = px("x")
        val y = px("y")
        val width = px("width")
        val height = px("height")
        return JSONObject()
            .put("x", x).put("y", y).put("width", width).put("height", height)
            .put("center_x", x + width / 2).put("center_y", y + height / 2)
    }
}

/** inspectActTarget 从 DOM 抓到的目标信号（纯数据，方便单测分类规则）。 */
internal data class BrowserTargetSignals(
    val tag: String,
    val type: String,
    val role: String,
    val formPresent: Boolean,
    val formMethod: String,
    val formRole: String,
    val inSearch: Boolean,
    val isPassword: Boolean,
    val isSubmitControl: Boolean,
    val buttonText: String,
)

internal data class BrowserSubmitClassification(
    /** 表单含密码/提交控件、按钮文字命中提交点 → 归为「发消息和提交表单」，手动审批时问用户。 */
    val submitPoint: Boolean,
    /** role=search 或 GET 表单：是搜索类，不确认（即使命中 submitPoint）。 */
    val searchRole: Boolean,
)

/**
 * 提交点判定规则（精确可编辑判定在 DOM 脚本 editable() 里完成，这里判「是否提交点」）：
 *  - 密码字段（input[type=password]）→ 提交点；
 *  - 提交控件（button[type=submit]、表单内无 type 的 button、input[type=submit|image]）→ 提交点；
 *  - 按钮 / 链接 / role=button，且可见文字命中「提交/支付/购买/确认/登录…」关键字 → 提交点。
 * search_role（不确认）：role=search、祖先含 role=search、input[type=search]，或表单 method≠post（GET 视作搜索）。
 */
internal object BrowserSubmitHeuristics {

    /** 提交点文字关键字（小写、含中英）。匹配按「包含」判断。 */
    val SUBMIT_KEYWORDS: List<String> = listOf(
        "提交", "支付", "付款", "购买", "下单", "结算", "确认", "确定", "登录", "登陆",
        "注册", "立即", "发送", "下一步",
        "submit", "pay", "buy", "login", "log in", "sign in", "sign up",
        "checkout", "place order", "purchase", "register", "confirm", "send", "order",
    )

    fun classify(signals: BrowserTargetSignals): BrowserSubmitClassification {
        val text = signals.buttonText.lowercase()
        val keywordHit = SUBMIT_KEYWORDS.any { text.contains(it) }
        val clickableTag = signals.tag in CLICKABLE_TAGS ||
            signals.role == "button" ||
            (signals.tag == "input" && signals.type in setOf("button", "submit", "image"))

        // 密码字段：显式标记，或 input[type=password]（DOM 探测未必单独置标记时也要认出来）。
        val isPassword = signals.isPassword ||
            (signals.tag == "input" && signals.type.equals("password", ignoreCase = true))

        val submitPoint = isPassword ||
            signals.isSubmitControl ||
            (clickableTag && keywordHit)

        val searchRole = when {
            isPassword -> false
            signals.role == "search" -> true
            signals.inSearch -> true
            signals.formRole == "search" -> true
            signals.tag == "input" && signals.type == "search" -> true
            // GET 表单（或未写 method，HTML 默认 GET）视作搜索类，不确认。
            signals.formPresent && signals.formMethod != "post" -> true
            else -> false
        }
        return BrowserSubmitClassification(submitPoint = submitPoint, searchRole = searchRole)
    }

    private val CLICKABLE_TAGS = setOf("button", "a")
}

/** 动作后是否发生跳转：对比动作前后 URL / 标题。 */
internal object BrowserNavigation {

    data class Result(val navigated: Boolean, val url: String, val title: String?)

    /**
     * URL 变化（非空且不同）或标题变化（非空且不同）都算跳转。
     * 跳转时带回新 url / title；未跳转 title 为 null。
     */
    fun detect(beforeUrl: String, beforeTitle: String, afterUrl: String, afterTitle: String): Result {
        val urlChanged = afterUrl.isNotBlank() && afterUrl != beforeUrl
        val titleChanged = afterTitle.isNotBlank() && afterTitle != beforeTitle
        val navigated = urlChanged || titleChanged
        val url = afterUrl.ifBlank { beforeUrl }
        return Result(
            navigated = navigated,
            url = url,
            title = if (navigated) afterTitle.ifBlank { null } else null,
        )
    }
}

internal sealed interface CursorResult {
    data class Offset(val value: Int) : CursorResult

    /** 游标代际与当前页面不符：页面已变化，续读失效。 */
    data object Stale : CursorResult
}

/**
 * 续读游标编解码：把 navigationGeneration 绑进游标（`gen:offset`），
 * 续读时代际不符即 STALE_OBSERVATION。兼容旧的纯数字游标（按 offset 处理）。
 */
internal object BrowserReadCursor {

    fun encode(generation: Long, offset: Int): String = "$generation:$offset"

    fun decode(cursor: String?, currentGeneration: Long): CursorResult {
        if (cursor.isNullOrBlank()) return CursorResult.Offset(0)
        val parts = cursor.split(":", limit = 2)
        if (parts.size == 2) {
            val gen = parts[0].toLongOrNull()
            val off = parts[1].toIntOrNull()
            if (gen != null && off != null) {
                return if (gen != currentGeneration) {
                    CursorResult.Stale
                } else {
                    CursorResult.Offset(off.coerceAtLeast(0))
                }
            }
        }
        // 兼容旧纯数字游标。
        val plain = cursor.toIntOrNull()
        return if (plain != null) CursorResult.Offset(plain.coerceAtLeast(0)) else CursorResult.Offset(0)
    }
}
