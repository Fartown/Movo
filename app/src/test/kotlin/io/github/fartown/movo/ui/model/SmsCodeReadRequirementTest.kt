package io.github.fartown.movo.ui.model

import io.github.fartown.movo.agent.tool.AgentToolCapabilities
import io.github.fartown.movo.agent.tool.RootRequirement
import io.github.fartown.movo.agent.tool.ToolSystemAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sms_code_read 没有 Root 时从短信通知里取验证码（要通知使用权），设置页不能把它标成「需要 Root」、
 * 在没 Root 的手机上藏起来（重构差异 T1-8）。
 */
class SmsCodeReadRequirementTest {
    @Test
    fun registeredAsRootEnhancedWithNotificationAccess() {
        val requirement = toolCardRequirement("sms_code_read")
        assertEquals(RootRequirement.PARTIAL, requirement.rootRequirement)
        assertEquals(ToolSystemAccess.NOTIFICATIONS, requirement.systemAccess)
        assertTrue("没 Root 的手机上也显示", visibleOnCurrentDevice("sms_code_read", rootGranted = false, colorOs = false))
    }

    @Test
    fun cardAsksForNotificationAccessWithoutRoot_andRootAloneIsEnough() {
        val noRoot = AgentToolCapabilities(rootAvailable = false, notificationsAllowed = false)
        assertEquals("NOTIFICATION_ACCESS_REQUIRED", noRoot.unavailableCode("sms_code_read"))
        assertEquals(AgentToolsAction.OpenPermissions, toolCardAction("sms_code_read", noRoot))

        assertNull(noRoot.copy(notificationsAllowed = true).unavailableCode("sms_code_read"))
        assertNull("有 Root 时读短信库，不要通知使用权", noRoot.copy(rootAvailable = true).unavailableCode("sms_code_read"))
    }
}
