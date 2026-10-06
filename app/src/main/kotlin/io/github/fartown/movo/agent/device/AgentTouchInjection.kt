package io.github.fartown.movo.agent.device

import java.util.concurrent.atomic.AtomicInteger

/**
 * Agent 往屏幕上注入触摸（无障碍手势、Root `input`）。
 * - 按下前先让 Movo 自己挡在那个点上的展开卡、悬浮球让开（[overlayYield]），免得这一下被它们收走、或按到卡片上的按钮；
 * - 执行期间标记为 Agent 注入（[active]），落在 Movo 浮层上的触摸据此认出不是用户的手指（规范 8.1）。
 */
internal object AgentTouchInjection {
    private val running = AtomicInteger()

    /**
     * 悬浮层服务注册：Agent 要从 (x, y) 按下时，挡在那里的浮层先让开（展开卡收起、悬浮球暂不接触摸），等变化生效后返回；
     * 返回值是按完之后要做的恢复（例如悬浮球重新接触摸），没有就是 null。在注入线程上调用，会短暂阻塞。
     */
    @Volatile
    var overlayYield: ((x: Float, y: Float) -> (() -> Unit)?)? = null

    val active: Boolean
        get() = running.get() > 0

    /** 注入一次从 ([x], [y]) 按下的触摸：先让挡在那里的浮层让开，再执行 [block]（执行期间标记为 Agent 注入），按完恢复。 */
    fun <T> touchAt(x: Float, y: Float, block: () -> T): T {
        val restore = overlayYield?.let { yieldAt -> runCatching { yieldAt(x, y) }.getOrNull() }
        try {
            return during(block)
        } finally {
            restore?.let { runCatching { it() } }
        }
    }

    fun <T> during(block: () -> T): T {
        running.incrementAndGet()
        try {
            return block()
        } finally {
            running.decrementAndGet()
        }
    }
}
