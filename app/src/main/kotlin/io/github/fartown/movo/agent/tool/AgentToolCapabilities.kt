package io.github.fartown.movo.agent.tool

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.fartown.movo.MovoApp
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import io.github.fartown.movo.agent.accessibility.AccessibilityProtectionClient
import io.github.fartown.movo.agent.device.AgentNotificationHistoryService
import io.github.fartown.movo.agent.device.RootAccess
import java.util.Locale

/** 每轮冻结的运行条件；不包含用户开关，也不触发授权请求。 */
internal data class AgentToolCapabilities(
    val rootAvailable: Boolean,
    val lsposedAvailable: Boolean = false,
    val accessibilityAvailable: Boolean = true,
    val accessibilityRecoveryAvailable: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val usageAllowed: Boolean = true,
    val locationAllowed: Boolean = true,
    val colorOs: Boolean = true,
    val touchscreen: Boolean = true,
    val screenshotAvailable: Boolean = true,
) {
    fun unavailableCode(name: String): String? {
        val requirement = AgentToolRequirements.find(name) ?: return "UNKNOWN_TOOL"
        if (requirement.rootRequirement == RootRequirement.REQUIRED && !rootAvailable) return "ROOT_REQUIRED"
        if (requirement.lsposedRequirement == LsposedRequirement.REQUIRED && !lsposedAvailable) return "LSPOSED_REQUIRED"
        if (requirement.colorOs && !colorOs) return "DEVICE_UNSUPPORTED"
        if (requirement.accessibility && !accessibilityAvailable && !accessibilityRecoveryAvailable) {
            return "ACCESSIBILITY_UNAVAILABLE"
        }
        return when (requirement.systemAccess) {
            ToolSystemAccess.NONE -> null
            ToolSystemAccess.NOTIFICATIONS -> if (notificationsAllowed ||
                (rootAvailable && (name == "recent_notifications" || name == "sms_code_read" ||
                    (name == "search_personal_orders" && colorOs)))
            ) null else "NOTIFICATION_ACCESS_REQUIRED"
            ToolSystemAccess.USAGE -> if (usageAllowed) null else "APP_USAGE_ACCESS_REQUIRED"
            ToolSystemAccess.LOCATION -> if (locationAllowed) null else "LOCATION_PERMISSION_REQUIRED"
        }
    }

    companion object {
        fun isColorOsDevice(): Boolean = Build.MANUFACTURER.lowercase(Locale.ROOT) in
            setOf("oppo", "oneplus", "realme")

        fun capture(context: Context): AgentToolCapabilities = AgentToolCapabilities(
            rootAvailable = RootAccess.isGranted,
            lsposedAvailable = MovoApp.serviceInstance != null,
            accessibilityAvailable = AgentAccessibilityService.isAvailable(),
            accessibilityRecoveryAvailable = MovoApp.serviceInstance != null &&
                AccessibilityProtectionClient.isEnabled(context),
            notificationsAllowed = AgentNotificationHistoryService.isEnabled(context),
            usageAllowed = AgentPersonalContextTools.hasUsageAccess(context),
            locationAllowed = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED &&
                (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED),
            colorOs = isColorOsDevice(),
            touchscreen = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
            screenshotAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                RootAccess.isGranted || io.github.fartown.movo.flavor.FlavorModule.screenCapture?.available == true,
        )
    }
}
