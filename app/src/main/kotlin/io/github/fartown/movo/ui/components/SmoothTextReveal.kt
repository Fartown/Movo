package io.github.fartown.movo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.ui.theme.MovoMotion
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

/**
 * 一条回答只使用一个显现时钟（规范 9.4「回答流式输出」、9.3.2 Q2）：**按句追加，不逐字**。
 *
 * 每个 Markdown 块把收到的文字缓冲到句末标点或换行，或 300ms 没有新字、或后面已经出现新的块、
 * 或整条回答结束时，把缓冲的文字作为一块提交；提交的这一块淡入 + 模糊 4 → 0，`fast` + `enter`，
 * 显现完即撤掉模糊（不长期挂模糊效果），不位移。
 *
 * 解析和文本排版仅在目标文本变化时发生；帧间推进只更新普通字段并调用 [invalidateDraw]。
 * 只有提交的块跨入新行时才额外请求一次测量以增长消息高度，全程不写 Compose State。
 */
@Stable
internal class SmoothTextRevealCoordinator {
    private val records = sortedMapOf<RevealBlockKey, RevealRecord>()
    private val wakeups = Channel<Unit>(capacity = Channel.CONFLATED)
    private val drainedState = MutableStateFlow(true)
    private val startedState = MutableStateFlow<Set<RevealBlockKey>>(emptySet())
    private var animationsPaused = false
    /** 整条回答已结束：剩余缓冲不再等句末或 300ms，下一帧直接提交。 */
    private var streamComplete = false

    val drained: StateFlow<Boolean> = drainedState
    /** 已经开始显现的块，用于让列表 marker 与正文保持同一生命周期。 */
    val started: StateFlow<Set<RevealBlockKey>> = startedState

    val isAnimationPaused: Boolean
        get() = animationsPaused

    /**
     * 页面不可见时帧时钟会停，但 Runtime 仍可能继续追加文本。此时直接追平当前目标，
     * 并让后续排版结果同样立即完成，避免回到页面后补播后台积压的显现动画。
     */
    fun pauseAnimationsAndCatchUp() {
        animationsPaused = true
        records.values.forEach(::completeRecord)
        updateDrainedState()
        wakeups.trySend(Unit)
    }

    fun resumeAnimationsAfterCatchUp() {
        records.values.forEach(::completeRecord)
        updateDrainedState()
        animationsPaused = false
        wakeups.trySend(Unit)
    }

    /** 回答是否仍在流式输出；结束后把各块剩余的缓冲一次提交（仍按 Q2 显现）。 */
    fun setStreaming(streaming: Boolean) {
        if (streamComplete == !streaming) return
        streamComplete = !streaming
        wakeups.trySend(Unit)
    }

    fun retainBlocks(activeBlocks: Set<RevealBlockKey>) {
        val iterator = records.iterator()
        var removedPendingBlock = false
        while (iterator.hasNext()) {
            val (_, record) = iterator.next()
            if (record.key !in activeBlocks) {
                removedPendingBlock = removedPendingBlock || record.progress < record.targetCount
                iterator.remove()
            }
        }
        val retainedStarted = startedState.value.intersect(activeBlocks)
        if (retainedStarted != startedState.value) {
            startedState.value = retainedStarted
        }
        if (removedPendingBlock || records.none { (_, record) -> record.progress < record.targetCount }) {
            updateDrainedState()
        }
        wakeups.trySend(Unit)
    }

    fun attach(
        key: RevealBlockKey,
        node: SmoothTextRevealNode,
        text: String?,
        layoutResult: TextLayoutResult?,
    ) {
        val record = records.getOrPut(key) { RevealRecord(key) }
        record.node = node
        if (text != null && layoutResult != null) {
            updateRecord(record, text, layoutResult)
        }
        wakeups.trySend(Unit)
    }

    fun detach(key: RevealBlockKey, node: SmoothTextRevealNode) {
        val record = records[key]?.takeIf { it.node === node } ?: return
        record.node = null
        // 已离开组合的块不再消费帧时钟；保留完成进度，重挂载时只显现后续新增文本。
        completeRecord(record)
        updateDrainedState()
        wakeups.trySend(Unit)
    }

    fun updateLayout(
        key: RevealBlockKey,
        node: SmoothTextRevealNode?,
        text: String,
        layoutResult: TextLayoutResult,
    ) {
        val record = records.getOrPut(key) { RevealRecord(key) }
        if (node != null) record.node = node
        updateRecord(record, text, layoutResult)
        wakeups.trySend(Unit)
    }

    fun drawSnapshot(key: RevealBlockKey): RevealDrawSnapshot? {
        val record = records[key] ?: return null
        if (record.layoutResult == null) return null
        return record.drawSnapshot
    }

    suspend fun runFrameClock() {
        while (currentCoroutineContext().isActive) {
            if (firstPendingRecord() == null) {
                updateDrainedState()
                wakeups.receive()
                continue
            }

            drainedState.value = false
            while (currentCoroutineContext().isActive) {
                if (firstPendingRecord() == null) break
                val frameNanos = withFrameNanos { it }
                advanceFrame(frameNanos)
                updateDrainedState()
            }
        }
    }

    /** 一帧：按源码顺序决定每块提交到哪里，并推进正在显现的块。 */
    internal fun advanceFrame(frameNanos: Long) {
        val newlyStarted = mutableSetOf<RevealBlockKey>()
        val ordered = records.values.toList()
        // 某块之后是否已经出现有内容的块（出现了就说明这一块已经写完）。
        val laterStarted = BooleanArray(ordered.size)
        for (index in ordered.lastIndex - 1 downTo 0) {
            laterStarted[index] = laterStarted[index + 1] || ordered[index + 1].targetCount > 0f
        }
        ordered.forEachIndexed { index, record ->
            if (record.node == null || record.layoutResult == null) return@forEachIndexed
            if (record.textChanged) {
                record.textChanged = false
                record.lastChangeNanos = frameNanos
            }
            val laterBlockStarted = laterStarted[index]
            val idle = frameNanos - record.lastChangeNanos >= IDLE_FLUSH_NANOS
            val commitTo = if (streamComplete || laterBlockStarted || idle) {
                record.targetCount
            } else {
                sentenceCommitCount(record.text, record.boundaries).toFloat()
            }
            if (commitTo > record.progress) {
                val from = floor(record.progress).toInt()
                record.progress = commitTo
                record.chunks += RevealChunk(from, commitTo.toInt(), frameNanos)
                // 同一块里最多两段同时显现；更早的直接完成。
                while (record.chunks.size > MAX_ACTIVE_CHUNKS) record.chunks.removeAt(0)
                if (record.key !in startedState.value) newlyStarted += record.key
            }
            if (record.chunks.isNotEmpty()) {
                record.chunks.forEach { chunk ->
                    chunk.fraction = ((frameNanos - chunk.startNanos) / REVEAL_NANOS).coerceIn(0f, 1f)
                }
                record.chunks.removeAll { it.fraction >= 1f }
            }
            record.node?.onRevealDataChanged()
        }
        if (newlyStarted.isNotEmpty()) startedState.value = startedState.value + newlyStarted
    }

    private fun updateRecord(
        record: RevealRecord,
        text: String,
        layoutResult: TextLayoutResult,
    ) {
        if (text != record.text) {
            // 流式文本只追加不修改，但行内语法闭合（**粗体**、`code`、链接折叠等）会让
            // 渲染文本丢掉标记字符而变短或错位。此时进度只能保持单调前进：一旦回退，
            // 已显现的文字会消失并重新打字，表现为输出反复闪烁。
            record.boundaries = updateGraphemeBoundaries(
                previousText = record.text,
                previousBoundaries = record.boundaries,
                text = text,
            )
            record.text = text
            record.targetCount = record.boundaries.lastIndex.toFloat()
            record.progress = record.progress.coerceAtMost(record.targetCount)
            record.textChanged = true
        }
        if (record.layoutResult !== layoutResult) {
            record.layoutResult = layoutResult
        }
        if (animationsPaused || record.node == null) completeRecord(record)
        updateDrainedState()
        record.node?.onRevealDataChanged()
    }

    private fun completeRecord(record: RevealRecord) {
        record.progress = record.targetCount
        record.chunks.clear()
        if (record.targetCount > 0f && record.key !in startedState.value) {
            startedState.value = startedState.value + record.key
        }
        record.node?.onRevealDataChanged()
    }

    private fun firstPendingRecord(): RevealRecord? = records.values.firstOrNull { record ->
        (record.progress < record.targetCount || record.chunks.isNotEmpty()) &&
            record.node != null && record.layoutResult != null
    }

    private fun updateDrainedState() {
        drainedState.value = records.values.none { record ->
            record.progress < record.targetCount || record.chunks.isNotEmpty()
        }
    }
}

@JvmInline
internal value class RevealBlockKey(val sourceOffset: Int) : Comparable<RevealBlockKey> {
    override fun compareTo(other: RevealBlockKey): Int = sourceOffset.compareTo(other.sourceOffset)
}

@Stable
internal class SmoothTextRevealState(
    val key: RevealBlockKey,
    private val coordinator: SmoothTextRevealCoordinator,
) {
    private var node: SmoothTextRevealNode? = null
    private var text: String? = null
    private var layoutResult: TextLayoutResult? = null

    fun onTextLayout(text: String, layoutResult: TextLayoutResult) {
        this.text = text
        this.layoutResult = layoutResult
        coordinator.updateLayout(key, node, text, layoutResult)
    }

    internal fun attach(node: SmoothTextRevealNode) {
        this.node = node
        coordinator.attach(key, node, text, layoutResult)
    }

    internal fun detach(node: SmoothTextRevealNode) {
        if (this.node === node) this.node = null
        coordinator.detach(key, node)
    }

    internal fun drawSnapshot(): RevealDrawSnapshot? = coordinator.drawSnapshot(key)
}

@Composable
internal fun rememberSmoothTextRevealState(
    key: RevealBlockKey,
    coordinator: SmoothTextRevealCoordinator,
): SmoothTextRevealState = remember(key, coordinator) {
    SmoothTextRevealState(key, coordinator)
}

internal fun Modifier.smoothTextReveal(state: SmoothTextRevealState): Modifier =
    this then SmoothTextRevealElement(state)

private data class SmoothTextRevealElement(
    val state: SmoothTextRevealState,
) : ModifierNodeElement<SmoothTextRevealNode>() {
    override fun create(): SmoothTextRevealNode = SmoothTextRevealNode(state)

    override fun update(node: SmoothTextRevealNode) {
        node.updateState(state)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "smoothTextReveal"
    }
}

internal class SmoothTextRevealNode(
    private var state: SmoothTextRevealState,
) : Modifier.Node(), DrawModifierNode, LayoutModifierNode {
    private var cachedLayoutResult: TextLayoutResult? = null
    private var cachedSettledEnd = -1
    private var cachedSettledPath: Path? = null
    private var cachedVisibleHeight = -1
    /** 正在显现的块各用一个图层承载透明度与模糊（最多 [MAX_ACTIVE_CHUNKS] 个）。 */
    private val chunkLayers = ArrayList<GraphicsLayer>(MAX_ACTIVE_CHUNKS)

    override fun onAttach() {
        state.attach(this)
        onRevealDataChanged()
    }

    override fun onDetach() {
        state.detach(this)
        clearPathCache()
        releaseLayers()
        cachedVisibleHeight = -1
    }

    fun updateState(next: SmoothTextRevealState) {
        if (state === next) return
        if (isAttached) state.detach(this)
        state = next
        clearPathCache()
        cachedVisibleHeight = -1
        if (isAttached) state.attach(this)
    }

    fun onRevealDataChanged() {
        val visibleHeight = state.visibleHeightPx()
        if (visibleHeight != cachedVisibleHeight) {
            cachedVisibleHeight = visibleHeight
            if (isAttached) invalidateMeasurement()
        }
        if (isAttached) invalidateDraw()
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        val visibleHeight = state.visibleHeightPx().coerceAtMost(placeable.height)
        cachedVisibleHeight = visibleHeight
        val measuredHeight = visibleHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(placeable.width, measuredHeight) {
            placeable.place(0, 0)
        }
    }

    override fun ContentDrawScope.draw() {
        val snapshot = state.drawSnapshot() ?: return
        val contentScope = this
        val targetCount = snapshot.boundaries.lastIndex
        val chunks = snapshot.chunks
        if (targetCount <= 0 || (snapshot.progress >= targetCount && chunks.isEmpty())) {
            drawContent()
            return
        }
        val layout = snapshot.layoutResult
        val textLength = layout.layoutInput.text.length
        fun offsetOf(index: Int): Int = snapshot.boundaries[index.coerceIn(0, targetCount)].coerceIn(0, textLength)

        // 已显现完的部分：从开头到第一段正在显现的块（没有则到已提交处）。
        val settledCount = chunks.firstOrNull()?.from ?: floor(snapshot.progress).toInt()
        settledPath(layout, offsetOf(settledCount))?.let { path ->
            clipPath(path) { contentScope.drawContent() }
        }

        // 正在显现的块：淡入 + 模糊 4 → 0（`fast` + `enter`）。
        val blurMax = BLUR_START.toPx()
        chunks.forEachIndexed { index, chunk ->
            val start = offsetOf(chunk.from)
            val end = offsetOf(chunk.to)
            if (end <= start) return@forEachIndexed
            val eased = MovoMotion.EasingEnter.transform(chunk.fraction)
            val layer = chunkLayer(index)
            layer.alpha = eased
            val radius = blurMax * (1f - eased)
            layer.renderEffect = if (radius > 0.05f) BlurEffect(radius, radius, TileMode.Decal) else null
            layer.record { contentScope.drawContent() }
            clipPath(layout.getPathForRange(start, end)) { drawLayer(layer) }
        }
    }

    private fun chunkLayer(index: Int): GraphicsLayer {
        while (chunkLayers.size <= index) chunkLayers += requireGraphicsContext().createGraphicsLayer()
        return chunkLayers[index]
    }

    private fun releaseLayers() {
        if (chunkLayers.isEmpty()) return
        val context = requireGraphicsContext()
        chunkLayers.forEach(context::releaseGraphicsLayer)
        chunkLayers.clear()
    }

    private fun settledPath(layoutResult: TextLayoutResult, end: Int): Path? {
        if (end <= 0) return null
        if (cachedLayoutResult === layoutResult && cachedSettledEnd == end) return cachedSettledPath
        cachedLayoutResult = layoutResult
        cachedSettledEnd = end
        cachedSettledPath = layoutResult.getPathForRange(0, end)
        return cachedSettledPath
    }

    private fun clearPathCache() {
        cachedLayoutResult = null
        cachedSettledEnd = -1
        cachedSettledPath = null
    }
}

internal class RevealDrawSnapshot(
    private val record: RevealRecord,
) {
    val layoutResult: TextLayoutResult
        get() = checkNotNull(record.layoutResult)
    val boundaries: IntArray
        get() = record.boundaries
    val progress: Float
        get() = record.progress
    /** 正在显现的块（按提交顺序）。 */
    val chunks: List<RevealChunk>
        get() = record.chunks
}

/** 一次提交的一块：字素下标 [from, to)，从 [startNanos] 起显现，[fraction] 为显现进度 0–1。 */
internal class RevealChunk(val from: Int, val to: Int, val startNanos: Long) {
    var fraction: Float = 0f
}

internal class RevealRecord(
    val key: RevealBlockKey,
) {
    val drawSnapshot = RevealDrawSnapshot(this)
    var node: SmoothTextRevealNode? = null
    var text: String = ""
    var layoutResult: TextLayoutResult? = null
    var boundaries: IntArray = intArrayOf(0)
    var progress: Float = 0f
    var targetCount: Float = 0f
    val chunks = ArrayList<RevealChunk>(MAX_ACTIVE_CHUNKS + 1)
    var textChanged = false
    var lastChangeNanos = 0L
}

/**
 * 句末提交点（字素下标）：最后一个句末标点（。！？!?；;…）或换行之后，连同紧跟的右引号 / 右括号；
 * 英文句点只在后面跟空白时算句末（避免把「3.14」「e.g.」拆开）。没有句末时为 0。
 */
internal fun sentenceCommitCount(text: String, boundaries: IntArray): Int {
    var end = -1
    var index = text.length - 1
    while (index >= 0) {
        val char = text[index]
        val isEnd = char in SENTENCE_ENDS ||
            (char == '.' && index + 1 < text.length && text[index + 1].isWhitespace())
        if (isEnd) {
            end = index + 1
            break
        }
        index--
    }
    if (end <= 0) return 0
    while (end < text.length && text[end] in SENTENCE_CLOSERS) end++
    val found = boundaries.binarySearch(end)
    return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
}

private const val SENTENCE_ENDS = "。！？!?；;…\n"
private const val SENTENCE_CLOSERS = "」』”’）)】》\"'"

private fun SmoothTextRevealState.visibleHeightPx(): Int {
    val snapshot = drawSnapshot() ?: return 0
    val layoutResult = snapshot.layoutResult
    val targetCount = snapshot.boundaries.lastIndex
    if (targetCount <= 0 || snapshot.progress >= targetCount) {
        return layoutResult.size.height
    }

    val visibleCount = ceil(snapshot.progress).toInt().coerceIn(0, targetCount)
    if (visibleCount == 0) return 0
    val textLength = layoutResult.layoutInput.text.length
    val visibleEnd = snapshot.boundaries[visibleCount].coerceIn(0, textLength)
    if (visibleEnd == 0 || layoutResult.lineCount == 0) return 0
    val line = layoutResult.getLineForOffset((visibleEnd - 1).coerceAtMost(textLength - 1))
    return ceil(layoutResult.getLineBottom(line)).toInt()
        .coerceIn(0, layoutResult.size.height)
}

internal fun graphemeBoundaries(text: String): IntArray {
    if (text.isEmpty()) return intArrayOf(0)

    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val result = ArrayList<Int>(text.length + 1)
    var boundary = iterator.first()
    while (boundary != BreakIterator.DONE) {
        result += boundary
        boundary = iterator.next()
    }
    if (result.lastOrNull() != text.length) result += text.length
    return result.toIntArray()
}

/**
 * 为只追加文本增量维护字素边界。
 *
 * 新内容可能把旧文本的最后一个字素继续延长，例如组合音标、ZWJ emoji、旗帜和 CRLF。
 * 因此保留倒数第二个边界之前的结果，只重算最后一个旧字素和新增后缀，避免每个流式
 * 分片都从头扫描整条回答。
 */
internal fun updateGraphemeBoundaries(
    previousText: String,
    previousBoundaries: IntArray,
    text: String,
): IntArray {
    if (
        previousText.isEmpty() ||
        !text.startsWith(previousText) ||
        previousBoundaries.isEmpty() ||
        previousBoundaries.first() != 0 ||
        previousBoundaries.last() != previousText.length
    ) {
        return graphemeBoundaries(text)
    }
    if (text == previousText) return previousBoundaries

    val restartBoundaryIndex = (previousBoundaries.lastIndex - 1).coerceAtLeast(0)
    val restartOffset = previousBoundaries[restartBoundaryIndex]
    val suffixBoundaries = graphemeBoundaries(text.substring(restartOffset))
    return IntArray(restartBoundaryIndex + suffixBoundaries.size).also { merged ->
        for (index in 0 until restartBoundaryIndex) {
            merged[index] = previousBoundaries[index]
        }
        suffixBoundaries.forEachIndexed { index, boundary ->
            merged[restartBoundaryIndex + index] = restartOffset + boundary
        }
    }
}

internal class AppendOnlyGraphemeIndex {
    private var indexedText = ""
    private var boundaries = intArrayOf(0)

    fun update(text: String) {
        boundaries = updateGraphemeBoundaries(
            previousText = indexedText,
            previousBoundaries = boundaries,
            text = text,
        )
        indexedText = text
    }

    fun endAfter(start: Int, maxGraphemes: Int): Int {
        val clampedStart = start.coerceIn(0, indexedText.length)
        if (clampedStart == indexedText.length || maxGraphemes <= 0) return clampedStart

        val foundIndex = boundaries.binarySearch(clampedStart)
        val firstEndIndex = if (foundIndex >= 0) foundIndex + 1 else -foundIndex - 1
        val endIndex = (firstEndIndex + maxGraphemes - 1).coerceAtMost(boundaries.lastIndex)
        return boundaries[endIndex]
    }
}

internal fun commonUtf16PrefixLength(first: String, second: String): Int {
    val limit = minOf(first.length, second.length)
    var index = 0
    while (index < limit && first[index] == second[index]) index += 1
    if (
        index in 1 until limit &&
        first[index - 1].isHighSurrogate() &&
        first[index].isLowSurrogate()
    ) {
        index -= 1
    }
    return index
}

/** 300ms 没有新字时提交缓冲（规范 9.4）。 */
private const val IDLE_FLUSH_NANOS = 300_000_000L
/** Q2 显现时长 `fast`。 */
private const val REVEAL_NANOS = MovoMotion.FAST * 1_000_000f
private const val MAX_ACTIVE_CHUNKS = 2
private val BLUR_START = 4.dp
