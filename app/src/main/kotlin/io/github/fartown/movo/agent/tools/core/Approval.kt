package io.github.fartown.movo.agent.tools.core

/**
 * 权限模式（2026-10-06 用户定，方案见 docs/research/tool-redesign/Movo 权限模式方案.md）。默认 YOLO。
 */
internal enum class PermissionMode(val wire: String) {
    /** 全部直接做，不弹卡。 */
    YOLO("yolo"),
    /** 高敏动作和用户加的规则命中的动作弹卡，其余直接做。 */
    MANUAL("manual");

    companion object {
        fun fromWire(value: String?): PermissionMode = entries.firstOrNull { it.wire == value } ?: YOLO
    }
}

/** 有后果的动作分几类。手动审批时 [highSensitive] 的固定会问，其余由用户在规则里勾选。 */
internal enum class ApprovalCategory(val wire: String, val label: String, val highSensitive: Boolean = false) {
    PAYMENT("payment", "付款、转账", highSensitive = true),
    PASSWORD("password", "输入密码", highSensitive = true),
    DELETE("delete", "删东西", highSensitive = true),
    SEND("send", "发消息和提交表单", highSensitive = true),
    SYSTEM("system", "改系统设置"),
    ROOT("root", "Root 命令"),
    INSTALL("install", "安装技能"),
    OUTBOUND("outbound", "把内容发到外部"),
    FILES("files", "写工作区以外的文件");

    companion object {
        fun fromWire(value: String?): ApprovalCategory? = entries.firstOrNull { it.wire == value }

        /** 用户可以勾选的规则（高敏的固定会问，不出现在可选列表里）。 */
        val optional: List<ApprovalCategory> get() = entries.filterNot { it.highSensitive }
    }
}

/**
 * 审批设置：模式、用户勾选的动作类别、用户选的应用。默认 YOLO、没有规则。存储见 [ApprovalSettings]。
 */
internal data class ApprovalPolicy(
    val mode: PermissionMode = PermissionMode.YOLO,
    val categories: Set<ApprovalCategory> = emptySet(),
    val apps: Set<String> = emptySet(),
) {
    /** 这一步要不要问用户：只在手动审批时问；高敏、勾选的类别、选中的应用里的操作命中任意一条就问。 */
    fun shouldAsk(category: ApprovalCategory?, appPackage: String?): Boolean {
        if (mode != PermissionMode.MANUAL) return false
        if (category != null && (category.highSensitive || category in categories)) return true
        return appPackage != null && appPackage in apps
    }

    companion object {
        val YOLO = ApprovalPolicy()
        val MANUAL_BUILT_IN = ApprovalPolicy(mode = PermissionMode.MANUAL)
    }
}

/**
 * 一个可能要问用户的动作：[category] 是它属于哪类有后果的动作（没有就是普通操作），
 * [appPackage] 是界面操作所在的应用（用于「某个应用里先问我」）。
 * [title] 是卡片大标题，[detail] 是灰底块里这一步要做什么，[reason] 是灰底块下面一行「为什么问你」，
 * 留空时按类别补（[reasonSentence]）。
 */
internal data class ApprovalNeed(
    val category: ApprovalCategory?,
    val title: String,
    val detail: String,
    val appPackage: String? = null,
    val reason: String = "",
)

/** 卡片上「为什么问你」：高敏的写「手动审批时都会先问你」，用户勾选的写「你设了先问你」，应用规则写应用。 */
internal fun reasonSentence(category: ApprovalCategory?): String = when {
    category == null -> "你设了在这个应用里每一步都先问你。"
    category.highSensitive -> "手动审批时，${category.label}都会先问你。"
    else -> "你设了「${category.label}」先问你。"
}

/**
 * 按审批设置处理一个动作：命中规则就弹卡等用户作答，否则直接放行。
 * 返回 null 表示放行，否则是不执行的原因（作为工具结果回给模型）。
 */
internal fun ToolContext.confirmConsequence(
    toolName: String,
    need: ApprovalNeed,
    timeoutMs: Long,
): ToolError? {
    if (!env.approvalPolicy.shouldAsk(need.category, need.appPackage)) return null
    if (!interaction.available) {
        return ToolError(
            ToolErrorCode.UNSUPPORTED,
            "这一步要用户确认，但现在没人能确认；本次未执行",
            hint = "在最终回复中说明需要用户确认的动作，不要换方式绕过",
            detail = "no_interactive_surface",
        )
    }
    val request = ApprovalRequest(
        toolName = toolName,
        title = need.title,
        detail = need.detail,
        category = need.category,
        reason = need.reason.ifBlank { reasonSentence(need.category) },
    )
    val decision = interaction.approve(request, timeoutMs)
    checkCancelled()
    return when (decision) {
        ApprovalDecision.Approved -> null
        ApprovalDecision.Declined -> ToolError(
            ToolErrorCode.USER_DECLINED,
            "用户拒绝了这一步",
            hint = "不要换方式重试；询问用户下一步怎么做",
        )
        ApprovalDecision.TimedOut -> ToolError(
            ToolErrorCode.APPROVAL_TIMEOUT,
            "用户没有确认，本次未执行",
            hint = "结束本轮并说明需要用户确认的动作",
        )
        ApprovalDecision.Unavailable -> ToolError(
            ToolErrorCode.UNSUPPORTED,
            "这一步要用户确认，但当前无法显示确认；本次未执行",
            hint = "在最终回复中说明需要用户确认的动作",
            detail = "no_interactive_surface",
        )
    }
}
