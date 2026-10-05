package io.github.fartown.movo.agent.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityNodeIdentityTest {
    @Test
    fun `same view id with recycled row text is not the same node`() {
        val original = identity(text = "第一行")
        val recycled = identity(text = "插入后的新行")

        assertFalse(original.matches(recycled))
    }

    @Test
    fun `unique id cannot hide changed action semantics`() {
        val original = identity(uniqueId = "virtual-42", text = "旧状态")
        val refreshed = identity(uniqueId = "virtual-42", text = "新状态")

        assertFalse(original.matches(refreshed))
        assertTrue(original.matches(identity(uniqueId = "virtual-42", text = "旧状态")))
    }

    @Test
    fun `blank semantic node is weak when window content changes`() {
        assertFalse(identity(text = "", description = "").strong)
        assertTrue(identity(text = "按钮").strong)
    }

    @Test
    fun `identity gaining a unique id is treated as replacement`() {
        assertFalse(identity(uniqueId = "").matches(identity(uniqueId = "virtual-42")))
    }

    @Test
    fun `truncated snapshot cannot promote semantic identity to globally unique`() {
        assertFalse(
            AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = false,
                snapshotTruncated = true,
                identityMatchCount = 1,
            ),
        )
        assertTrue(
            AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = true,
                snapshotTruncated = true,
                identityMatchCount = 1,
            ),
        )
    }

    /** 真机：设置首页观察总被截断，页面又一直在变，每次点按都判过期，原地转了 62 步。 */
    @Test
    fun `truncated snapshot is unique when the live window has exactly one match`() {
        assertTrue(
            AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = false,
                snapshotTruncated = true,
                identityMatchCount = 1,
                liveIdentityMatchCount = { 1 },
            ),
        )
        // 整窗还有同身份的节点（例如每行一个「删除」），不能证明点到的还是原来那一个。
        assertFalse(
            AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = false,
                snapshotTruncated = true,
                identityMatchCount = 1,
                liveIdentityMatchCount = { 2 },
            ),
        )
        // 查不了（没有文字、窗口不可访问）按原规则处理。
        assertFalse(
            AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = false,
                snapshotTruncated = true,
                identityMatchCount = 1,
                liveIdentityMatchCount = { null },
            ),
        )
        // 快照里已经重复就不再去查整窗。
        var queried = false
        assertFalse(
            AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = false,
                snapshotTruncated = true,
                identityMatchCount = 2,
                liveIdentityMatchCount = { queried = true; 1 },
            ),
        )
        assertFalse(queried)
    }

    @Test
    fun `complete snapshot requires exactly one semantic identity match`() {
        assertTrue(
            AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = false,
                snapshotTruncated = false,
                identityMatchCount = 1,
            ),
        )
        assertFalse(
            AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = false,
                snapshotTruncated = false,
                identityMatchCount = 2,
            ),
        )
    }

    private fun identity(
        uniqueId: String = "",
        text: String = "条目",
        description: String = "",
    ): AccessibilityNodeIdentity = AccessibilityNodeIdentity(
        uniqueId = uniqueId,
        windowId = 7,
        packageName = "example.app",
        className = "android.widget.TextView",
        viewId = "example.app:id/row",
        text = text,
        description = description,
        password = false,
    )
}
