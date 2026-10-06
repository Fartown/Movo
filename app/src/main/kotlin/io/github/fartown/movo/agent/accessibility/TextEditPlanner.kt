package io.github.fartown.movo.agent.accessibility

/** 按 Android 文本选区（UTF-16 offset）生成一次真实“键入”后的内容与光标位置。 */
internal object TextEditPlanner {
    data class Plan(
        val text: String,
        val cursor: Int,
    )

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

    fun canSafelyReconstruct(
        password: Boolean,
        textAvailable: Boolean,
        textLength: Int,
        selectionStart: Int,
        selectionEnd: Int,
    ): Boolean {
        if (password || !textAvailable || textLength < 0) return false
        if (selectionStart in 0..textLength && selectionEnd in 0..textLength) return true
        // 部分空输入控件以 -1 表示尚未创建光标；空文本仍只有插入点 0。
        return textLength == 0 && selectionStart <= 0 && selectionEnd <= 0
    }

    fun insertAtSelection(
        currentText: String,
        insertedText: String,
        selectionStart: Int,
        selectionEnd: Int,
    ): Plan? {
        val selectionIsValid =
            selectionStart in 0..currentText.length && selectionEnd in 0..currentText.length
        if (!selectionIsValid && currentText.isNotEmpty()) return null
        val start = if (selectionIsValid) selectionStart else 0
        val end = if (selectionIsValid) selectionEnd else 0
        val lower = minOf(start, end)
        val upper = maxOf(start, end)
        return Plan(
            text = currentText.substring(0, lower) + insertedText + currentText.substring(upper),
            cursor = lower + insertedText.length,
        )
    }
}
