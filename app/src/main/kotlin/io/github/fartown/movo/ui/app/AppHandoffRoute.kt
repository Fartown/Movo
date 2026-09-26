package io.github.fartown.movo.ui.app

import io.github.fartown.movo.ui.navigation.AppRoute

/**
 * 对话浮层「展开到 App」之后要接着打开的页面（规范 8.9「与 App 一致」：浮层里点「查看日志」时浮层展开到 App，
 * 再打开运行日志）。浮层与 App 在同一进程，由浮层在展开前登记，App 在切到同一会话后取走；过期作废，
 * 避免一次没走完的展开把页面带到之后的普通展开里。
 */
internal object AppHandoffRoute {
    private const val VALID_MS = 15_000L

    private var pending: AppRoute? = null
    private var requestedAt = 0L

    @Synchronized
    fun request(route: AppRoute) {
        pending = route
        requestedAt = android.os.SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun consume(): AppRoute? {
        val route = pending?.takeIf { android.os.SystemClock.elapsedRealtime() - requestedAt <= VALID_MS }
        pending = null
        return route
    }
}
