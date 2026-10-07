package io.github.fartown.movo.diagnostics.runlog

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream

/**
 * 运行日志的写线程（方案 §5.5）。运行线程只排队；编号、图片落盘都在这一个线程上做。
 *
 * - 普通记录按字节计入 [Limits.queueBytes]，排不下就丢；丢掉的行照样占号，并补一行 `gap`。
 * - 控制记录（run_start、run_end、gap）不受上限约束。
 * - 单次任务到 [Limits.runImageBytes] 停存图片，到 [Limits.runContentBytes] 停存内容。
 * - 写盘出错时这次任务停止记录内容，并在现有运行日志里记一条。不查询磁盘剩余空间。
 * - 每行直接写进文件（不留缓冲），进程被杀时已经写下的行都在。
 */
internal class RunLogStore(
    val root: File,
    private val limits: Limits = Limits(),
    private val elapsedClock: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** 写盘出错时在现有运行日志里记一条（run, 事件名, 异常）。 */
    private val reportFailure: (String, String, Throwable) -> Unit = { _, _, _ -> },
) {
    data class Limits(
        val queueBytes: Long = 8L shl 20,
        /** 排队中被引用的大块数据（图片的 data URL、请求体）合计字符数。 */
        val referenceChars: Long = 64L shl 20,
        val runContentBytes: Long = 50L shl 20,
        val runImageBytes: Long = 40L shl 20,
        val totalBytes: Long = 200L shl 20,
        val maxRuns: Int = 20,
    )

    /** 排队侧连续丢弃的记号；写线程处理时封口，之后的丢弃另起一个。 */
    class DropMarker(val reason: String) {
        var count: Int = 1
        var sealed: Boolean = false
    }

    /** 每次任务的写线程状态。 */
    class WriterState {
        internal var dir: File? = null
        internal var out: FileOutputStream? = null
        internal var nextSeq = 1L
        internal var bytes = 0L
        internal var imageBytes = 0L
        internal var gapFrom = 0L
        internal var gapTo = 0L
        internal var gapReason: String? = null
        internal var ended = false
    }

    /** 目录账本：开始任务时据此淘汰，导出和清空时据此判断。写线程与 [open] 都在 [dirsLock] 下读写。 */
    data class DirInfo(
        val dir: String,
        val run: String,
        var bytes: Long = 0,
        var ended: Boolean = false,
        var conversation: String? = null,
        var writeFailed: Boolean = false,
        var active: Boolean = false,
    )

    private sealed class Op(val bytes: Long = 0, val references: Long = 0)
    private class Startup : Op()
    private class Open(val session: RunLogSession) : Op()
    private class Write(val session: RunLogSession, val record: RunLogRecord) :
        Op(record.estimatedBytes, record.referenceChars)
    private class Dropped(val session: RunLogSession, val marker: DropMarker) : Op()
    private class Request(val session: RunLogSession, val record: RunLogRecord, val body: String?, val file: String) :
        Op(record.estimatedBytes, body?.length?.toLong() ?: 0L)
    private class Bind(val session: RunLogSession, val conversation: String?) : Op()
    private class Close(val session: RunLogSession, val record: RunLogRecord) : Op()
    private class Delete(val dirs: List<String>) : Op()
    private class DeleteConversation(val conversation: String) : Op()
    private class Clear(val done: (Int) -> Unit) : Op()
    private class Barrier(val latch: CountDownLatch) : Op()
    private object Stop : Op()

    private val queue = LinkedBlockingQueue<Op>()
    private val queuedBytes = AtomicLong()
    private val queuedReferences = AtomicLong()
    private val dirsLock = Any()
    private val dirs = LinkedHashMap<String, DirInfo>()
    private val sessions = HashMap<String, RunLogSession>() // 写线程：目录名 → 进行中的任务
    private val pinned = HashSet<String>()
    /** 导出期间要删的（删除了对话）：导出结束再删。 */
    private val deleteAfterUnpin = HashSet<String>()
    private val thread = Thread(::loop, "movo-runlog").apply { isDaemon = true }

    init {
        queue.put(Startup())
        thread.start()
    }

    // ---- 排队侧（任意线程）----

    /**
     * 开始一次任务：先按目录账本淘汰最旧的已结束任务；删完总量仍超上限时这一次只记元数据，返回 null。
     * 只用内存里的账本，不读磁盘。
     */
    fun open(run: String): RunLogSession? {
        val dirName = "$run-${DIR_TIME.get()!!.format(Date(wallClock()))}"
        synchronized(dirsLock) {
            val evict = ArrayList<String>()
            var total = dirs.values.sumOf { it.bytes }
            var count = dirs.size + 1
            for (info in dirs.values.filter { it.ended && !it.active && it.dir !in pinned }.sortedWith(AGE_ORDER)) {
                if (count <= limits.maxRuns && total <= limits.totalBytes) break
                evict += info.dir
                total -= info.bytes
                count--
            }
            evict.forEach(dirs::remove)
            if (evict.isNotEmpty()) queue.put(Delete(evict))
            if (total > limits.totalBytes || dirName in dirs) return null
            dirs[dirName] = DirInfo(dirName, run, active = true)
        }
        val session = RunLogSession(run, dirName, elapsedClock(), this, elapsedClock, wallClock)
        queue.put(Open(session))
        return session
    }

    fun submit(session: RunLogSession, record: RunLogRecord) {
        synchronized(session.lock) {
            if (session.closed) return
            if (!record.control) {
                val reason = when {
                    session.writeFailed -> "write_failed"
                    queuedBytes.get() + record.estimatedBytes > limits.queueBytes -> "queue_full"
                    else -> null
                }
                if (reason != null) {
                    val marker = session.openDrop
                    if (marker != null && !marker.sealed && marker.reason == reason) {
                        marker.count++
                    } else {
                        val next = DropMarker(reason)
                        session.openDrop = next
                        queue.put(Dropped(session, next))
                    }
                    return
                }
            }
            session.openDrop = null
            // 图片只是引用：排不下时这一行照写，图片记成没存。
            val accepted = if (record.referenceChars > 0 &&
                queuedReferences.get() + record.referenceChars > limits.referenceChars
            ) record.withoutImages("queue_full") else record
            queuedBytes.addAndGet(accepted.estimatedBytes)
            queuedReferences.addAndGet(accepted.referenceChars)
            queue.put(Write(session, accepted))
        }
    }

    /**
     * 一次真正发出去的请求（方案 §5.3）：只交出最终字符串的引用，解析、抽图、压缩都在写线程上做。
     * 排队中的请求体与图片合计超过 [Limits.referenceChars] 时，这一份请求体不记，`request` 行照写并写明。
     */
    fun submitRequest(session: RunLogSession, record: RunLogRecord, body: String, file: String) {
        synchronized(session.lock) {
            if (session.closed) return
            session.openDrop = null
            val fits = queuedReferences.get() + body.length <= limits.referenceChars
            val op = if (fits) Request(session, record, body, file) else Request(
                session,
                RunLogRecord(record.type, record.at, record.el, record.fields + mapOf("chars" to body.length, "dropped" to "queue_full")),
                null,
                file,
            )
            queuedBytes.addAndGet(op.bytes)
            queuedReferences.addAndGet(op.references)
            queue.put(op)
        }
    }

    fun bind(session: RunLogSession, conversation: String?) {
        synchronized(session.lock) {
            if (session.closed) return
            queue.put(Bind(session, conversation))
        }
    }

    /** 写入 run_end 并关闭；之后这次任务的记录一律丢弃。 */
    fun close(session: RunLogSession, runEnd: RunLogRecord) {
        synchronized(session.lock) {
            if (session.closed) return
            session.closed = true
            queue.put(Close(session, runEnd))
        }
    }

    /** 删除一个对话的全部任务日志；进行中的任务结束后再删。 */
    fun deleteConversation(conversationId: String) {
        if (conversationId.isNotBlank()) queue.put(DeleteConversation(conversationId))
    }

    /** 清空：保留进行中和正在导出的任务。[done] 在写线程上回调，参数是删掉的任务数。 */
    fun clear(done: (Int) -> Unit = {}) {
        queue.put(Clear(done))
    }

    /** 导出期间不淘汰、不清空这个任务。 */
    fun pin(dir: String) = synchronized(dirsLock) { pinned += dir }

    fun unpin(dir: String) {
        val delete = synchronized(dirsLock) {
            pinned -= dir
            deleteAfterUnpin.remove(dir)
        }
        if (delete) queue.put(Delete(listOf(dir)))
    }

    fun dirInfo(dir: String): DirInfo? = synchronized(dirsLock) { dirs[dir]?.copy() }

    fun dirInfos(): List<DirInfo> = synchronized(dirsLock) { dirs.values.map { it.copy() } }

    /** 等写线程处理完此前排队的全部记录（测试与导出前用）。 */
    fun awaitIdle(timeoutMs: Long = 10_000): Boolean {
        val latch = CountDownLatch(1)
        queue.put(Barrier(latch))
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** 停止写线程（只在测试里用）。 */
    fun shutdown() {
        queue.put(Stop)
        thread.join(5_000)
    }

    // ---- 写线程 ----

    private fun loop() {
        while (true) {
            val op = queue.take()
            if (op === Stop) break
            if (op.bytes > 0) queuedBytes.addAndGet(-op.bytes)
            if (op.references > 0) queuedReferences.addAndGet(-op.references)
            runCatching { handle(op) }
        }
    }

    private fun handle(op: Op) {
        when (op) {
            is Startup -> startup()
            is Open -> openDir(op.session)
            is Write -> write(op.session, op.record)
            is Dropped -> {
                val count = synchronized(op.session.lock) {
                    op.marker.sealed = true
                    op.marker.count
                }
                repeat(count) { extendGap(op.session.writer, op.marker.reason) }
            }
            is Request -> writeRequest(op.session, op.record, op.body, op.file)
            is Bind -> {
                updateDir(op.session.dirName) { it.conversation = op.conversation }
                saveIndex()
            }
            is Close -> closeRun(op.session, op.record)
            is Delete -> {
                op.dirs.forEach(::deleteDir)
                saveIndex()
            }
            is DeleteConversation -> deleteConversationDirs(op.conversation)
            is Clear -> {
                val removable = synchronized(dirsLock) {
                    dirs.values.filter { !it.active && it.dir !in pinned }.map { it.dir }
                }
                removable.forEach(::deleteDir)
                saveIndex()
                runCatching { op.done(removable.size) }
            }
            is Barrier -> op.latch.countDown()
            Stop -> Unit
        }
    }

    private fun openDir(session: RunLogSession) {
        val dir = File(root, session.dirName)
        session.writer.dir = dir
        sessions[session.dirName] = session
        if (!(dir.isDirectory || dir.mkdirs())) markWriteFailed(session, IOException("mkdirs failed"))
    }

    private fun write(session: RunLogSession, record: RunLogRecord) {
        val w = session.writer
        if (w.ended) return
        if (!record.control) {
            if (session.writeFailed) return extendGap(w, "write_failed")
            if (w.bytes >= limits.runContentBytes) return extendGap(w, "size_cap")
        }
        writeLine(session, record.type, record.at, record.el, materialize(session, record.fields))
    }

    private fun writeLine(session: RunLogSession, type: String, at: Long, el: Long, fields: Map<String, Any?>) {
        val w = session.writer
        flushGap(session)
        val seq = w.nextSeq++
        val line = LinkedHashMap<String, Any?>(fields.size + 4)
        line["seq"] = seq
        line["t"] = type
        line["at"] = at
        line["el"] = el
        line.putAll(fields)
        if (!append(session, RunLogJson.write(line))) {
            // 这一行没写下来：它占的号记进 gap。
            if (w.gapReason == null) {
                w.gapFrom = seq
                w.gapTo = seq
                w.gapReason = "write_failed"
            } else {
                w.gapTo = seq
            }
        }
    }

    /** 丢掉的记录也占一个号，连续的合成一行 gap。 */
    private fun extendGap(w: WriterState, reason: String) {
        val seq = w.nextSeq++
        if (w.gapReason == null) {
            w.gapFrom = seq
            w.gapTo = seq
            w.gapReason = reason
        } else {
            w.gapTo = seq
        }
    }

    private fun flushGap(session: RunLogSession) {
        val w = session.writer
        val reason = w.gapReason ?: return
        w.gapReason = null
        val line = linkedMapOf<String, Any?>(
            "seq" to w.nextSeq++, "t" to "gap", "at" to session.wall(), "el" to session.elapsed(),
            "from_seq" to w.gapFrom, "to_seq" to w.gapTo, "reason" to reason,
        )
        append(session, RunLogJson.write(line))
    }

    /** 写一行；出错时这次任务停止记录内容。 */
    private fun append(session: RunLogSession, line: String): Boolean {
        val w = session.writer
        val dir = w.dir ?: return false
        return try {
            val out = w.out ?: FileOutputStream(File(dir, LOG_FILE), true).also { w.out = it }
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            out.write(bytes)
            w.bytes += bytes.size
            updateDir(session.dirName) { it.bytes += bytes.size }
            true
        } catch (failure: IOException) {
            markWriteFailed(session, failure)
            false
        }
    }

    private fun markWriteFailed(session: RunLogSession, failure: Throwable) {
        if (session.writeFailed) return
        session.writeFailed = true
        updateDir(session.dirName) { it.writeFailed = true }
        runCatching { reportFailure(session.run, "run_log.write_failed", failure) }
    }

    /** 把记录里的图片落进 `img/`（按内容命名，同一张只存一次），换成文件名；远程 URL 原样。 */
    private fun materialize(session: RunLogSession, fields: Map<String, Any?>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return materializeValue(session, fields) as Map<String, Any?>
    }

    private fun materializeValue(session: RunLogSession, value: Any?): Any? = when (value) {
        is RunLogImage -> storeImage(session, value)
        is Map<*, *> -> LinkedHashMap<String, Any?>(value.size).also { out ->
            value.forEach { (key, item) -> out[key.toString()] = materializeValue(session, item) }
        }
        is List<*> -> value.map { materializeValue(session, it) }
        else -> value
    }

    private fun storeImage(session: RunLogSession, image: RunLogImage): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        result["mime"] = image.mimeType
        result["bytes"] = image.bytes
        image.width?.let { result["width"] = it }
        image.height?.let { result["height"] = it }
        image.source?.takeIf { it.isNotBlank() }?.let { result["source"] = it }
        val reference = image.reference
        when {
            image.dropReason != null -> result["dropped"] = image.dropReason
            reference.startsWith("data:", ignoreCase = true) -> {
                val data = decodeDataUrl(reference)
                val saved = if (data == null) "undecodable" else saveImage(session, data, image.mimeType)
                if (saved.startsWith("img/")) result["file"] = saved else result["dropped"] = saved
            }
            reference.startsWith("http://", ignoreCase = true) || reference.startsWith("https://", ignoreCase = true) ->
                result["url"] = reference
            else -> result["ref"] = reference
        }
        return result
    }

    /** 按内容存一张图（同一张只存一次）。返回 `img/<名字>`，没存时返回原因。 */
    private fun saveImage(session: RunLogSession, data: ByteArray, mimeType: String): String {
        val w = session.writer
        val dir = w.dir ?: return "write_failed"
        if (session.writeFailed) return "write_failed"
        val name = "img/${sha256(data).take(32)}.${extension(mimeType)}"
        val file = File(dir, name)
        // 同一张图第二次出现：不再写，也不再占空间。
        if (file.exists()) return name
        if (w.imageBytes >= limits.runImageBytes || w.bytes >= limits.runContentBytes) return "size_cap"
        if (!writeAtomically(file, data)) {
            markWriteFailed(session, IOException("image write failed"))
            return "write_failed"
        }
        w.imageBytes += data.size
        w.bytes += data.size
        updateDir(session.dirName) { it.bytes += data.size }
        return name
    }

    /**
     * 写一次请求：请求体里的 data URL 存进 `img/`、换成文件名，其余原样，压缩存成 `req/<file>`；
     * 图片和请求体都写好之后再写引用它们的 `request` 行。
     */
    private fun writeRequest(session: RunLogSession, record: RunLogRecord, body: String?, file: String) {
        val w = session.writer
        if (w.ended) return
        if (session.writeFailed) return extendGap(w, "write_failed")
        val fields = LinkedHashMap(record.fields)
        if (body != null) {
            fields["bytes"] = utf8Length(body)
            when {
                w.bytes >= limits.runContentBytes -> fields["dropped"] = "size_cap"
                else -> {
                    val dir = w.dir
                    val images = ArrayList<Map<String, Any?>>()
                    val target = if (dir == null) null else File(dir, "req/$file")
                    val written = target != null && writeRequestBody(session, body, target, images)
                    if (written) {
                        fields["body"] = "req/$file"
                        val size = target!!.length()
                        w.bytes += size
                        updateDir(session.dirName) { it.bytes += size }
                    } else {
                        markWriteFailed(session, IOException("request body write failed"))
                        fields["dropped"] = "write_failed"
                    }
                    if (images.isNotEmpty()) fields["images"] = images
                }
            }
        }
        writeLine(session, record.type, record.at, record.el, fields)
    }

    /** 逐个扫描 JSON 字符串：值以 `data:` 开头且是 base64 的抽出去存图，其余字符原样写进压缩文件。 */
    private fun writeRequestBody(
        session: RunLogSession,
        body: String,
        target: File,
        images: MutableList<Map<String, Any?>>,
    ): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        val temp = File(parent, target.name + TEMP_SUFFIX)
        return try {
            GZIPOutputStream(FileOutputStream(temp)).bufferedWriter(Charsets.UTF_8).use { out ->
                var copyFrom = 0
                var index = 0
                val length = body.length
                while (index < length) {
                    if (body[index] != '"') {
                        index++
                        continue
                    }
                    val start = index
                    index++
                    while (index < length && body[index] != '"') index += if (body[index] == '\\') 2 else 1
                    val end = index.coerceAtMost(length)
                    if (end < length && end - start > MIN_DATA_URL_CHARS && body.startsWith("data:", start + 1, ignoreCase = true)) {
                        replacement(session, body.substring(start + 1, end), images)?.let { value ->
                            out.write(body, copyFrom, start - copyFrom)
                            out.write(RunLogJson.write(value))
                            copyFrom = end + 1
                        }
                    }
                    index = end + 1
                }
                if (copyFrom < length) out.write(body, copyFrom, length - copyFrom)
            }
            temp.renameTo(target).also { renamed -> if (!renamed) temp.delete() }
        } catch (failure: IOException) {
            temp.delete()
            false
        }
    }

    /** 请求体里的一个 data URL → 存图后的替换值（`img/<名字>` 或写明没存的原因）；不是 base64 数据时返回 null，原样保留。 */
    private fun replacement(session: RunLogSession, raw: String, images: MutableList<Map<String, Any?>>): String? {
        val dataUrl = (runCatching { RunLogJson.parse("\"$raw\"") }.getOrNull() as? String) ?: return null
        val comma = dataUrl.indexOf(',')
        if (comma < 0 || !dataUrl.substring(0, comma).endsWith(";base64", ignoreCase = true)) return null
        val mimeType = dataUrl.substring(5, comma).substringBefore(';')
        val data = decodeDataUrl(dataUrl) ?: return null
        val saved = saveImage(session, data, mimeType)
        val descriptor = linkedMapOf<String, Any?>("mime" to mimeType, "bytes" to data.size)
        return if (saved.startsWith("img/")) {
            descriptor["file"] = saved
            images += descriptor
            saved
        } else {
            descriptor["dropped"] = saved
            images += descriptor
            "[图片未保存：$saved]"
        }
    }

    private fun utf8Length(text: String): Long {
        var bytes = 0L
        var index = 0
        while (index < text.length) {
            val char = text[index]
            bytes += when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                Character.isHighSurrogate(char) && index + 1 < text.length && Character.isLowSurrogate(text[index + 1]) -> {
                    index++
                    4
                }
                else -> 3
            }
            index++
        }
        return bytes
    }

    /** 先写临时文件再改名；改名失败时删掉临时文件，原有文件不动。 */
    private fun writeAtomically(file: File, data: ByteArray): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        val temp = File(parent, file.name + TEMP_SUFFIX)
        return try {
            FileOutputStream(temp).use { it.write(data) }
            temp.renameTo(file).also { renamed -> if (!renamed) temp.delete() }
        } catch (failure: IOException) {
            temp.delete()
            false
        }
    }

    private fun closeRun(session: RunLogSession, record: RunLogRecord) {
        val w = session.writer
        if (!w.ended) {
            flushGap(session)
            writeLine(session, record.type, record.at, record.el, materialize(session, record.fields))
            w.ended = true
        }
        runCatching { w.out?.close() }
        w.out = null
        sessions.remove(session.dirName)
        updateDir(session.dirName) {
            it.ended = true
            it.active = false
            it.writeFailed = session.writeFailed
        }
        if (session.deleteOnClose) deleteDir(session.dirName)
        saveIndex()
    }

    private fun deleteConversationDirs(conversation: String) {
        val targets = synchronized(dirsLock) {
            val all = dirs.values.filter { it.conversation == conversation }.map { it.dir }
            // 正在导出的等导出结束再删。
            all.filter { it in pinned }.forEach { deleteAfterUnpin += it }
            all.filterNot { it in pinned }
        }
        targets.forEach(::deleteDir)
        saveIndex()
    }

    /** 删一个任务目录；任务还在进行时，等它结束再删。 */
    private fun deleteDir(dir: String) {
        val active = sessions[dir]
        if (active != null) {
            active.deleteOnClose = true
            return
        }
        File(root, dir).deleteRecursively()
        synchronized(dirsLock) { dirs.remove(dir) }
    }

    private fun updateDir(dir: String, change: (DirInfo) -> Unit) {
        synchronized(dirsLock) { dirs[dir]?.let(change) }
    }

    /**
     * 启动检查（方案 §5.5，10-07 改）：进程被杀的任务没有 run_end，补一条 `interrupted` 后保留；
     * 一条完整记录都没有的目录和残留的临时文件删掉；按目录重建账本，再按条数和总量淘汰最旧的。
     */
    private fun startup() {
        if (!root.isDirectory) {
            root.mkdirs()
            return
        }
        val index = loadIndex()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(TEMP_SUFFIX) }.toList().forEach { it.delete() }
        val found = ArrayList<DirInfo>()
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val match = DIR_NAME.matchEntire(dir.name)
            val log = File(dir, LOG_FILE)
            if (match == null || !(endsWithRunEnd(log) || closeInterrupted(log))) {
                dir.deleteRecursively()
                return@forEach
            }
            val saved = index[dir.name]
            found += DirInfo(
                dir = dir.name,
                run = match.groupValues[1],
                bytes = dirSize(dir),
                ended = true,
                conversation = saved?.conversation,
                writeFailed = saved?.writeFailed ?: false,
            )
        }
        val evict = ArrayList<String>()
        synchronized(dirsLock) {
            found.sortedWith(AGE_ORDER).forEach { dirs[it.dir] = it }
            var total = dirs.values.sumOf { it.bytes }
            var count = dirs.size
            for (info in dirs.values.filter { !it.active }.sortedWith(AGE_ORDER)) {
                if (count <= limits.maxRuns && total <= limits.totalBytes) break
                evict += info.dir
                total -= info.bytes
                count--
            }
        }
        evict.forEach(::deleteDir)
        saveIndex()
    }

    private fun endsWithRunEnd(log: File): Boolean {
        if (!log.isFile) return false
        return runCatching {
            RandomAccessFile(log, "r").use { file ->
                val length = file.length()
                if (length == 0L) return false
                val size = minOf(length, TAIL_BYTES).toInt()
                val buffer = ByteArray(size)
                file.seek(length - size)
                file.readFully(buffer)
                String(buffer, Charsets.UTF_8).trimEnd('\n').substringAfterLast('\n').contains("\"t\":\"run_end\"")
            }
        }.getOrDefault(false)
    }

    /**
     * 被杀的任务收尾：去掉写了一半的最后一行，补一条 run_end（`status=interrupted`，时刻沿用最后一条记录）。
     * 一条完整记录都没有时返回 false。
     */
    private fun closeInterrupted(log: File): Boolean = runCatching {
        if (!log.isFile) return false
        RandomAccessFile(log, "rw").use { file ->
            val cut = lastNewlineBefore(file, file.length())
            if (cut < 0) return false
            if (file.length() > cut + 1) file.setLength(cut + 1)
            val start = lastNewlineBefore(file, cut) + 1
            val head = ByteArray(minOf(cut - start, HEAD_BYTES).toInt())
            file.seek(start)
            file.readFully(head)
            val text = String(head, Charsets.UTF_8)
            if (text.contains("\"t\":\"run_end\"")) return true
            fun number(name: String) = Regex("\"$name\":(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()
            val line = linkedMapOf<String, Any?>(
                "seq" to number("seq")?.plus(1), "t" to "run_end", "at" to number("at"), "el" to number("el"),
                "status" to "interrupted",
            ).filterValues { it != null }
            file.seek(file.length())
            file.write((RunLogJson.write(line) + "\n").toByteArray(Charsets.UTF_8))
        }
        true
    }.getOrDefault(false)

    /** [before] 之前最后一个换行符的位置；没有返回 -1。 */
    private fun lastNewlineBefore(file: RandomAccessFile, before: Long): Long {
        var end = before
        val buffer = ByteArray(SCAN_BYTES)
        while (end > 0) {
            val size = minOf(end, SCAN_BYTES.toLong()).toInt()
            file.seek(end - size)
            file.readFully(buffer, 0, size)
            for (index in size - 1 downTo 0) if (buffer[index] == '\n'.code.toByte()) return end - size + index
            end -= size
        }
        return -1
    }

    private fun dirSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private class SavedEntry(val conversation: String?, val writeFailed: Boolean)

    private fun loadIndex(): Map<String, SavedEntry> = runCatching {
        val file = File(root, INDEX_FILE)
        if (!file.isFile) return emptyMap()
        val parsed = RunLogJson.parse(file.readText(Charsets.UTF_8)) as? Map<*, *> ?: return emptyMap()
        (parsed["runs"] as? List<*>).orEmpty().mapNotNull { item ->
            val entry = item as? Map<*, *> ?: return@mapNotNull null
            val dir = entry["dir"] as? String ?: return@mapNotNull null
            dir to SavedEntry(conversation = entry["conversation"] as? String, writeFailed = entry["write_failed"] == true)
        }.toMap()
    }.getOrDefault(emptyMap())

    /** `index.json`：任务 → 对话的对应关系（删除对话时用），不进导出。 */
    private fun saveIndex() {
        val runs = synchronized(dirsLock) {
            dirs.values.map { info ->
                linkedMapOf<String, Any?>("dir" to info.dir, "run" to info.run).apply {
                    info.conversation?.let { put("conversation", it) }
                    if (info.ended) put("ended", true)
                    if (info.writeFailed) put("write_failed", true)
                }
            }
        }
        val text = RunLogJson.write(linkedMapOf("v" to 1, "runs" to runs))
        runCatching {
            if (!root.isDirectory) root.mkdirs()
            writeAtomically(File(root, INDEX_FILE), text.toByteArray(Charsets.UTF_8))
        }
    }

    private fun decodeDataUrl(reference: String): ByteArray? = runCatching {
        val comma = reference.indexOf(',')
        if (comma < 0) return null
        val meta = reference.substring(5, comma)
        val payload = reference.substring(comma + 1)
        if (meta.endsWith(";base64", ignoreCase = true)) {
            Base64.getMimeDecoder().decode(payload)
        } else {
            java.net.URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
        }
    }.getOrNull()

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun extension(mimeType: String): String = when (mimeType.lowercase(Locale.ROOT).substringBefore(';').trim()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic" -> "heic"
        else -> "bin"
    }

    companion object {
        const val LOG_FILE = "log.jsonl"
        const val INDEX_FILE = "index.json"
        const val TEMP_SUFFIX = ".tmp"
        private const val TAIL_BYTES = 64L * 1024
        private const val SCAN_BYTES = 64 * 1024
        /** 读最后一行开头这么多字节，够取出 seq、t、at、el。 */
        private const val HEAD_BYTES = 256L
        /** 短于这个长度的 data URL 不值得抽出去。 */
        private const val MIN_DATA_URL_CHARS = 64
        private val DIR_NAME = Regex("""(R\d+)-(\d{8}-\d{6})""")
        private val DIR_TIME = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT) }

        /** 旧的在前：先按目录名里的时间，再按任务号。 */
        private val AGE_ORDER = compareBy<DirInfo>(
            { DIR_NAME.matchEntire(it.dir)?.groupValues?.get(2) ?: "" },
            { it.run.removePrefix("R").toLongOrNull() ?: 0L },
        )
    }
}
