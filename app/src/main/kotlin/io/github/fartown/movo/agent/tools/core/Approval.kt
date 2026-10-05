package io.github.fartown.movo.agent.tools.core

import java.util.concurrent.ConcurrentHashMap

/** 污点的两类（实施方案 5.1）。 */
internal enum class TaintKind {
    /** 不可信内容：网页、其他应用的屏幕内容、通知、MCP 结果、后台监听送来的命令输出。 */
    UNTRUSTED,
    /** 个人数据：个人记录（短信、通话、通讯录、相册等）、验证码、剪贴板、文件、健康、应用用量、位置、Wi‑Fi 密码。 */
    PERSONAL,
}

/**
 * 本轮运行的污点状态（实施方案 5.1）：分「不可信内容」「个人数据」两类，只增不减，跟随单次运行。
 * 只有两类**同时成立**时，会把内容发出去的动作才需要确认——针对「个人数据被注入的指令发出去」这一威胁；
 * 只读了其中一类不触发（用户 2026-10-04 定）。工具自己的输出（终端、写记忆）不算污点来源。
 */
internal class TaintTracker {
    private val sources = ConcurrentHashMap<TaintKind, MutableSet<String>>()

    val untrusted: Boolean get() = !sources[TaintKind.UNTRUSTED].isNullOrEmpty()
    val personal: Boolean get() = !sources[TaintKind.PERSONAL].isNullOrEmpty()

    /** 两类同时成立。 */
    val tainted: Boolean get() = untrusted && personal

    val snapshot: Map<TaintKind, Set<String>> get() = sources.mapValues { it.value.toSet() }

    fun mark(kind: TaintKind, source: String) {
        sources.getOrPut(kind) { ConcurrentHashMap.newKeySet() } += source
    }
}

/** 工具对审批的诉求：由工具按参数和上下文给出，管线再叠加通用规则。 */
internal data class ApprovalNeed(
    val reason: ApprovalReason,
    val title: String,
    val detail: String,
    /** 一直允许的键（工具名之外的范围），例如「首次读取短信」；为空则不能一直允许。 */
    val scopeKey: String? = null,
    /** 一直允许那一行的完整文案，例如「以后读取短信不再询问」。 */
    val scopeLabel: String? = null,
    /**
     * 「本次任务内」的目标范围（实施方案 5.4：同一个工具、同一个目标在本次运行内不再询问），例如包名、设置项、域名。
     * 为空时按工具算。
     */
    val taskScope: String? = null,
    /** 能否勾选「本次任务内，这类操作都允许」；支付、转账、删除不能（定稿 16-02）。 */
    val allowTaskScope: Boolean = true,
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
 * 受保护应用：用户自己指定的应用，Movo 在里面的点击、滑动、输入每一步都要确认。
 * **默认为空**，只有用户在「设置 → 工具 → 受保护应用」里加了才生效（2026-10-05 用户要求：可以留着，但要能管理，默认不应该有）。
 */
internal object ProtectedApps {
    private const val PREFS = "movo_protected_apps"
    private const val KEY = "packages"

    @Volatile private var cache: Set<String>? = null

    private fun prefs(context: android.content.Context) =
        context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    fun packages(context: android.content.Context): Set<String> =
        cache ?: prefs(context).getStringSet(KEY, emptySet()).orEmpty().toSet().also { cache = it }

    fun setPackages(context: android.content.Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY, packages.toSet()).apply()
        cache = packages.toSet()
    }

    fun add(context: android.content.Context, pkg: String) = setPackages(context, packages(context) + pkg)

    fun remove(context: android.content.Context, pkg: String) = setPackages(context, packages(context) - pkg)

    /** 工具解析时调用（没有 Context 入参）：取进程的 Application；取不到时按未保护处理。 */
    fun isProtected(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val context = io.github.fartown.movo.agent.runtime.AgentAppContext.resolve() ?: return false
        return packageName in packages(context)
    }

    /** 用户是否设过受保护应用。没设过时，认不出前台应用也不用确认（无从判断「是不是在受保护应用里」）。 */
    fun anyConfigured(): Boolean {
        val context = io.github.fartown.movo.agent.runtime.AgentAppContext.resolve() ?: return false
        return packages(context).isNotEmpty()
    }
}
