package io.github.fartown.movo.agent.accessibility

/** ui_input 写入前后的判断：输入框里原有什么、要写成什么、怎么写、写进去的对不对。 */
internal object TextEditPlanner {
    /**
     * 追加前输入框里实际已有的文字。显示的是提示文字（isShowingHintText）时为空；
     * 读不到文字（null）但没有光标或光标在开头时按空输入框处理——空的输入框常这样报（真机：设置搜索框）；
     * 读不到文字而光标在后面，说明有内容但读不出，返回 null（不追加，免得覆盖原有内容）。
     */
    fun existingText(text: String?, showingHint: Boolean, selectionStart: Int, selectionEnd: Int): String? = when {
        showingHint -> ""
        text != null -> text
        selectionStart <= 0 && selectionEnd <= 0 -> ""
        else -> null
    }

    /** 写完后输入框里应有的全部文字：追加时接在 [existing] 末尾，读不到原文时返回 null；替换时就是 [text]。 */
    fun target(existing: String?, text: String, append: Boolean): String? =
        if (append) existing?.let { it + text } else text

    /**
     * 读回的和要写的算不算一样：开头结尾的空白不算（真机：小米笔记的编辑器读回来总在最前面多一个换行，
     * 写「AAA」读回「\nAAA」）。
     */
    fun sameText(wanted: String, readback: String?): Boolean =
        readback != null && (readback == wanted || readback.trim() == wanted.trim())

    /**
     * 输入框不接受直接写入时能不能改用粘贴。网页里的不行：真机上网页粘贴出来的可能是更早的剪贴板内容
     * （`.docs/input-text-investigation/probe/`，小米浏览器），而这时也没法用直接写入改回来。
     */
    fun canPasteWhenRejected(inWebView: Boolean, supportsPaste: Boolean): Boolean = supportsPaste && !inWebView

    /**
     * 直接写入后换行丢了：编辑器把换行改成了空格或吞掉（读回的只差在空白）。原生单行框本来就不收换行，不算；
     * 网页里的框报不准是不是多行（真机：小米笔记），都算。原生多行框会改用粘贴补回，其他的在结果里说明。
     */
    fun lostLineBreaks(wanted: String, readback: String?, multiLine: Boolean, inWebView: Boolean): Boolean {
        if (!(multiLine || inWebView) || readback == null || sameText(wanted, readback) || '\n' !in wanted) return false
        return withoutWhitespace(readback) == withoutWhitespace(wanted)
    }

    private fun withoutWhitespace(text: String): String = text.replace(WHITESPACE, "")

    private val WHITESPACE = Regex("\\s+")
}
