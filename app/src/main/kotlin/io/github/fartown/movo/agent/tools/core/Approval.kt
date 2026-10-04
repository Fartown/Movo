package io.github.fartown.movo.agent.tools.core

import java.util.concurrent.ConcurrentHashMap

/**
 * 本轮运行的污点状态：读过网页、通知、MCP 结果或个人数据之后，可能外发的动作一律需要确认。
 * 污点只增不减，跟随单次运行。
 */
internal class TaintTracker {
    private val sources = ConcurrentHashMap.newKeySet<String>()

    val tainted: Boolean get() = sources.isNotEmpty()
    val snapshot: Set<String> get() = sources.toSet()

    fun mark(source: String) {
        sources += source
    }
}

/** 工具对审批的诉求：由工具按参数和上下文给出，管线再叠加通用规则。 */
internal data class ApprovalNeed(
    val reason: ApprovalReason,
    val title: String,
    val detail: String,
    /** 长期允许的键（工具名之外的范围），例如包名、规范化命令前缀；为空则不能长期允许。 */
    val scopeKey: String? = null,
    val scopeLabel: String? = null,
)

/** 用户“一直允许”的规则。键包含策略版本，策略变化后旧授权失效。 */
internal interface ApprovalRuleStore {
    fun isAllowed(toolName: String, scopeKey: String): Boolean
    fun allow(toolName: String, scopeKey: String)

    companion object {
        const val POLICY_VERSION = 1

        fun key(toolName: String, scopeKey: String): String = "v$POLICY_VERSION|$toolName|$scopeKey"

        /** 进程内单例：同一进程里的“一直允许”跨运行保留，直到策略版本变化（review §17.10）。 */
        val IN_MEMORY: ApprovalRuleStore = object : ApprovalRuleStore {
            private val allowed = ConcurrentHashMap.newKeySet<String>()
            override fun isAllowed(toolName: String, scopeKey: String) = key(toolName, scopeKey) in allowed
            override fun allow(toolName: String, scopeKey: String) {
                allowed += key(toolName, scopeKey)
            }
        }
    }
}

/**
 * 受保护应用名单：在这些应用里的点击与输入需要确认。名单只能增加确认，不能免除确认。
 */
internal object ProtectedApps {
    private val packages = setOf(
        // 支付与银行
        "com.eg.android.AlipayGphone",
        "com.unionpay",
        "com.chinamworld.main",
        "com.icbc",
        "cmb.pb",
        "com.android.bankabc",
        "com.chinamworld.bocmbci",
        "com.bankcomm.Bankcomm",
        "com.paypal.android.p2pmobile",
        // 系统设置与安全
        "com.android.settings",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    /** 发送消息类应用：在这些应用里执行声明为 send / submit 的动作需要确认。 */
    private val messaging = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.tencent.tim",
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.ss.android.lark",
        "com.alibaba.android.rimet",
        "com.tencent.wework",
        "org.telegram.messenger",
        "com.whatsapp",
    )

    fun isProtected(packageName: String?): Boolean =
        packageName != null && packageName in packages

    fun isMessaging(packageName: String?): Boolean =
        packageName != null && packageName in messaging
}
