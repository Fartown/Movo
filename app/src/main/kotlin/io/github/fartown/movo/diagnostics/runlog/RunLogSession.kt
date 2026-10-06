package io.github.fartown.movo.diagnostics.runlog

import java.util.concurrent.ConcurrentHashMap

/**
 * 一次任务的完整日志（方案 §5.1）：`filesDir/run-log/<dirName>/`。
 *
 * 运行线程只调用 [record]，把记录交给写线程；编号、图片落盘都在写线程上做。
 * 时刻在事件发生处取：调用方不传时用调用这一刻。
 */
internal class RunLogSession internal constructor(
    val run: String,
    val dirName: String,
    /** 任务开始时的单调时钟（毫秒），`el` 都相对它。 */
    val startElapsed: Long,
    private val store: RunLogStore,
    private val elapsedClock: () -> Long,
    private val wallClock: () -> Long,
) {
    /** 写入 run_end 之后的记录一律丢弃。 */
    @Volatile var closed: Boolean = false
        internal set

    /** 写线程写盘出错后置位：之后的内容不再排队，控制记录照写。 */
    @Volatile internal var writeFailed: Boolean = false

    /** 本轮 ToolPipeline 已经加载的权限档位（不为日志单独读取）。 */
    @Volatile var permissionMode: String? = null

    /** 执行器看到的结束状态；run_end 在 withRun 收尾时写，排在最后。 */
    @Volatile internal var endFields: Map<String, Any?>? = null

    /** 排队侧的锁：编排丢弃记号与关闭。 */
    internal val lock = Any()

    /** 排队侧最近一次连续丢弃的记号；写线程处理后封口。 */
    internal var openDrop: RunLogStore.DropMarker? = null

    /** 写线程的状态，只由写线程访问。 */
    internal val writer = RunLogStore.WriterState()

    /** 工具开始的时刻（相对任务开始），结束时算耗时。 */
    internal val toolStarts = ConcurrentHashMap<String, Long>()

    /** 每次尝试已经发出几次请求（服务商内部的重发也算一次）。 */
    internal val sends = ConcurrentHashMap<String, Int>()

    /** 删除对话时任务还在进行：结束后连同目录一起删。 */
    @Volatile internal var deleteOnClose: Boolean = false

    fun elapsed(): Long = elapsedClock() - startElapsed

    fun wall(): Long = wallClock()

    fun record(
        type: String,
        fields: Map<String, Any?>,
        at: Long = wall(),
        el: Long = elapsed(),
        control: Boolean = false,
    ) {
        if (closed) return
        store.submit(this, RunLogRecord(type, at, el, fields, control))
    }
}
