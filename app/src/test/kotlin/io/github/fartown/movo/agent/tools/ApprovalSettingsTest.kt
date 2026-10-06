package io.github.fartown.movo.agent.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.tools.core.ApprovalCategory
import io.github.fartown.movo.agent.tools.core.ApprovalPolicy
import io.github.fartown.movo.agent.tools.core.ApprovalSettings
import io.github.fartown.movo.agent.tools.core.PermissionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 审批设置存储：默认 YOLO、没有规则；改完立即反映到运行时读取；高敏类别不存。 */
@RunWith(RobolectricTestRunner::class)
class ApprovalSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun defaultsAndUpdates() {
        ApprovalSettings.update(context) { ApprovalPolicy() }
        assertEquals(ApprovalPolicy.YOLO, ApprovalSettings.load(context))

        ApprovalSettings.setMode(context, PermissionMode.MANUAL)
        ApprovalSettings.setCategory(context, ApprovalCategory.SYSTEM, ask = true)
        ApprovalSettings.setCategory(context, ApprovalCategory.PAYMENT, ask = true)
        ApprovalSettings.addApp(context, "com.example.bank")
        ApprovalSettings.addApp(context, " ")
        val policy = ApprovalSettings.load(context)
        assertEquals(PermissionMode.MANUAL, policy.mode)
        assertEquals("高敏类别固定会问，不存进规则", setOf(ApprovalCategory.SYSTEM), policy.categories)
        assertEquals(setOf("com.example.bank"), policy.apps)
        assertEquals(policy, ApprovalSettings.state(context).value)

        val prefs = context.getSharedPreferences("movo_approval_settings", Context.MODE_PRIVATE)
        assertEquals("manual", prefs.getString("mode", null))
        assertEquals(setOf("system"), prefs.getStringSet("categories", null))

        ApprovalSettings.setCategory(context, ApprovalCategory.SYSTEM, ask = false)
        ApprovalSettings.removeApp(context, "com.example.bank")
        ApprovalSettings.setMode(context, PermissionMode.YOLO)
        assertEquals(ApprovalPolicy.YOLO, ApprovalSettings.load(context))
        assertTrue(prefs.getStringSet("apps", null).orEmpty().isEmpty())
    }
}
