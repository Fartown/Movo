package io.github.fartown.movo.agent.runtime

/** 用户任务的运行引用；通知停止只消费这里登记的任务，不接管 Root daemon。 */
internal class ExecutionLeaseRegistry {
    private data class Lease(
        val owner: Long?,
        val allowBoundFallback: Boolean,
        val label: Int?,
        val task: String,
        val onStop: () -> Unit,
    )
    private val leases = linkedMapOf<String, Lease>()
    private var activeOwner: Long? = null

    @Synchronized fun acquire(
        id: String,
        allowBoundFallback: Boolean = false,
        label: Int? = null,
        /** 同一个用户任务的多段引用（App 里的准备阶段、运行时服务里的执行阶段）用同一个键，通知里只算一项。 */
        task: String = id,
        onStop: () -> Unit,
    ): Boolean {
        require(id.isNotBlank())
        if (id in leases) return false
        leases[id] = Lease(activeOwner, allowBoundFallback, label, task, onStop)
        return true
    }

    @Synchronized fun release(id: String) { leases.remove(id) }
    @Synchronized fun count(): Int = leases.size

    /** 通知里显示的任务数：同一任务的多段引用只算一项。 */
    @Synchronized fun taskCount(): Int = leases.values.distinctBy { it.task }.size

    /** 所有引用都是同一种非任务用途（登录、后台命令、终端）时返回它的说明文案，否则按任务计数显示。 */
    @Synchronized fun sharedLabel(): Int? = leases.values.map { it.label }.distinct().singleOrNull()

    @Synchronized fun attachOwner(owner: Long) {
        activeOwner = owner
        leases.replaceAll { _, lease -> if (lease.owner == null) lease.copy(owner = owner) else lease }
    }

    @Synchronized fun closeOwnerIfIdle(owner: Long): Boolean {
        if (leases.isNotEmpty()) return false
        if (activeOwner == owner) activeOwner = null
        return true
    }

    /** 旧服务销毁时，不能取消在其 stopSelf 之后为下一次启动登记的任务。 */
    @Synchronized fun drainOwner(owner: Long): List<() -> Unit> = drainOwnerTasks(owner).map { it.second }

    /** 同 [drainOwner]，同时给出每个停止回调所属的任务（运行日志记停止来源用）。 */
    @Synchronized fun drainOwnerTasks(owner: Long): List<Pair<String, () -> Unit>> {
        if (activeOwner == owner) activeOwner = null
        val owned = leases.filterValues { it.owner == owner }
        owned.keys.forEach(leases::remove)
        return owned.values.map { it.task to it.onStop }
    }

    @Synchronized fun drain(startFailed: Boolean = false): List<() -> Unit> = drainTasks(startFailed).map { it.second }

    /** 同 [drain]，同时给出每个停止回调所属的任务。 */
    @Synchronized fun drainTasks(startFailed: Boolean = false): List<Pair<String, () -> Unit>> = leases.values
        .filterNot { startFailed && it.allowBoundFallback }
        .map { it.task to it.onStop }
        .also { leases.clear() }
}
