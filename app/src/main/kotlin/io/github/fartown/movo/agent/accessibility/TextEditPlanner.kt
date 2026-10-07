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
     * 能不能改用粘贴。网页里的输入框不行：真机上网页粘贴出来的是更早的剪贴板内容，不是刚写进去的
     * （`.docs/input-text-investigation/probe/`，小米浏览器），会把用户之前复制的东西贴进去。
     */
    fun canPaste(inWebView: Boolean, supportsPaste: Boolean): Boolean = supportsPaste && !inWebView

    /**
     * 直接设置后要不要改用粘贴重写：多行输入框把换行改成了空格或吞掉（读回的只差在换行）。
     * 单行输入框本来就不收换行，改用粘贴也一样，不重写。
     */
    fun lostLineBreaks(wanted: String, readback: String?, multiLine: Boolean): Boolean {
        if (!multiLine || readback == null || readback == wanted || '\n' !in wanted) return false
        return withoutWhitespace(readback) == withoutWhitespace(wanted)
    }

    private fun withoutWhitespace(text: String): String = text.replace(WHITESPACE, "")

    private val WHITESPACE = Regex("\\s+")
}
