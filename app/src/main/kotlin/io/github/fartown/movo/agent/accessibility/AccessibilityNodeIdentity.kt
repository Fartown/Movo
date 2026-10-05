package io.github.fartown.movo.agent.accessibility

/** 可在 JVM 中验证的节点身份指纹；viewId 不是列表项唯一标识。 */
internal data class AccessibilityNodeIdentity(
    val uniqueId: String,
    val windowId: Int,
    val packageName: String,
    val className: String,
    val viewId: String,
    val text: String,
    val description: String,
    val password: Boolean,
) {
    val strong: Boolean
        get() = uniqueId.isNotBlank() || text.isNotBlank() || description.isNotBlank()

    fun matches(refreshed: AccessibilityNodeIdentity): Boolean {
        if (windowId != refreshed.windowId) return false
        if (packageName != refreshed.packageName) return false
        if (className != refreshed.className) return false
        if (password != refreshed.password) return false
        if (uniqueId != refreshed.uniqueId) return false
        if (viewId.isNotBlank() && viewId != refreshed.viewId) return false
        // uniqueId 只证明还是同一个虚拟节点，不证明它仍表达模型观察时的动作语义。
        if (text != refreshed.text) return false
        if (description != refreshed.description) return false
        return true
    }
}

/**
 * 窗口内容变化后，只有真正稳定且在观察范围内可证明唯一的身份才能继续使用。
 * 截断快照无法证明 text/desc 指纹在窗口其余部分不存在重复项；这时用 [liveIdentityMatchCount]
 * （动作前在当前整个窗口里按文字查到的同身份节点数）补证：整窗只有一个，才算唯一。
 * 真机：设置首页很长、观察总被截断，页面上又有东西一直在变（轮播的搜索提示等），
 * 每次点按都判过期，Agent 在首页原地转了 62 步。
 */
internal object AccessibilityIdentityFreshnessPolicy {
    fun canBypassContentChange(
        hasUniqueId: Boolean,
        snapshotTruncated: Boolean,
        identityMatchCount: Int,
        liveIdentityMatchCount: () -> Int? = { null },
    ): Boolean =
        identityMatchCount == 1 && (hasUniqueId || !snapshotTruncated || liveIdentityMatchCount() == 1)
}
