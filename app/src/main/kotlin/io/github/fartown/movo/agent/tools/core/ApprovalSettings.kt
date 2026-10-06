package io.github.fartown.movo.agent.tools.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 审批设置的存储：权限模式、用户勾选的动作类别、用户选的应用。默认 YOLO、没有规则。
 * 运行时每次取工具环境时读一次，设置页改完对正在跑的任务立即生效；设置页订阅 [state] 跟着刷新。
 * 运行时与界面在同一进程（AgentRuntimeService 没有单独进程），共用这一份内存状态。
 */
internal object ApprovalSettings {
    private const val PREFS = "movo_approval_settings"
    private const val KEY_MODE = "mode"
    private const val KEY_CATEGORIES = "categories"
    private const val KEY_APPS = "apps"

    @Volatile
    private var flow: MutableStateFlow<ApprovalPolicy>? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun state(context: Context): StateFlow<ApprovalPolicy> = mutableState(context)

    fun load(context: Context): ApprovalPolicy = mutableState(context).value

    fun update(context: Context, transform: (ApprovalPolicy) -> ApprovalPolicy) {
        val state = mutableState(context)
        synchronized(this) {
            val next = normalize(transform(state.value))
            prefs(context).edit()
                .putString(KEY_MODE, next.mode.wire)
                .putStringSet(KEY_CATEGORIES, next.categories.map { it.wire }.toSet())
                .putStringSet(KEY_APPS, next.apps)
                .apply()
            state.value = next
        }
    }

    fun setMode(context: Context, mode: PermissionMode) = update(context) { it.copy(mode = mode) }

    fun setCategory(context: Context, category: ApprovalCategory, ask: Boolean) =
        update(context) { it.copy(categories = if (ask) it.categories + category else it.categories - category) }

    fun addApp(context: Context, packageName: String) = update(context) { it.copy(apps = it.apps + packageName) }

    fun removeApp(context: Context, packageName: String) = update(context) { it.copy(apps = it.apps - packageName) }

    private fun mutableState(context: Context): MutableStateFlow<ApprovalPolicy> =
        flow ?: synchronized(this) { flow ?: MutableStateFlow(read(context)).also { flow = it } }

    private fun read(context: Context): ApprovalPolicy {
        val prefs = prefs(context)
        return normalize(
            ApprovalPolicy(
                mode = PermissionMode.fromWire(prefs.getString(KEY_MODE, null)),
                categories = prefs.getStringSet(KEY_CATEGORIES, emptySet()).orEmpty()
                    .mapNotNull(ApprovalCategory::fromWire)
                    .toSet(),
                apps = prefs.getStringSet(KEY_APPS, emptySet()).orEmpty().toSet(),
            ),
        )
    }

    /** 高敏类别固定会问，不存；空包名丢掉。 */
    private fun normalize(policy: ApprovalPolicy): ApprovalPolicy = policy.copy(
        categories = policy.categories.filterNot { it.highSensitive }.toSet(),
        apps = policy.apps.filter { it.isNotBlank() }.toSet(),
    )
}
