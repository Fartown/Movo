package io.github.fartown.movo.diagnostics.runlog

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导出完整日志（方案 §5.6）：把一次已经结束的任务目录原样打包成 zip，附一份说明 `README.txt`
 * （英文文件名：macOS 自带的 unzip 不认 zip 里的 UTF-8 文件名，中文名会显示成乱码）。
 * 说明是导出时读日志算出来的事实（有几行 gap、几处截断、哪些图片和请求体没存），不是日志本身。
 */
internal object RunLogExporter {
    const val NOTE_FILE = "README.txt"

    /** 只能导出已经结束、写盘没出过错的任务；不能导出时返回原因（定稿 23：写在菜单项的说明里）。 */
    fun refusal(info: RunLogStore.DirInfo?): String? = when {
        info == null -> "这次任务没有完整日志"
        info.active || !info.ended -> "任务结束后才能导出"
        info.writeFailed -> "完整日志不全，不能导出"
        else -> null
    }

    /** 默认文件名：目录 `R57-20261006-145400` → `Movo-R57-20261006-1454.zip`。 */
    fun fileName(dir: String): String = "Movo-${if (dir.length > 2) dir.dropLast(2) else dir}.zip"

    /** 写 zip：`<目录名>/log.jsonl`、`req/`、`img/` 原样，加 `README.txt`。 */
    fun export(runDir: File, output: OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            val files = runDir.walkTopDown()
                .filter { it.isFile && !it.name.endsWith(RunLogStore.TEMP_SUFFIX) }
                .sortedBy { it.relativeTo(runDir).path }
                .toList()
            files.forEach { file ->
                zip.putNextEntry(ZipEntry("${runDir.name}/${file.relativeTo(runDir).path.replace(File.separatorChar, '/')}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("${runDir.name}/$NOTE_FILE"))
            zip.write(describe(runDir).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    /** 说明：包含哪些内容，以及日志里记下的缺失（gap、截断、没存的图片和请求体）。 */
    fun describe(runDir: File): String {
        var lines = 0
        var gaps = 0
        val gapRanges = ArrayList<String>()
        var truncated = 0
        var droppedImages = 0
        var droppedBodies = 0
        var requests = 0
        var runEnd: String? = null
        File(runDir, RunLogStore.LOG_FILE).takeIf { it.isFile }?.bufferedReader(Charsets.UTF_8)?.useLines { sequence ->
            sequence.forEach { line ->
                lines++
                if (line.contains(TRUNCATED_MARK)) truncated += Regex(Regex.escape(TRUNCATED_MARK)).findAll(line).count()
                val record = runCatching { RunLogJson.parse(line) as? Map<*, *> }.getOrNull() ?: return@forEach
                when (record["t"]) {
                    "gap" -> {
                        gaps++
                        if (gapRanges.size < 20) gapRanges += "${record["from_seq"]}–${record["to_seq"]}（${record["reason"]}）"
                    }
                    "request" -> {
                        requests++
                        if (record["dropped"] != null) droppedBodies++
                    }
                    "run_end" -> runEnd = record["status"]?.toString()
                }
                droppedImages += countDroppedImages(record)
            }
        }
        return buildString {
            appendLine("Movo 完整运行日志：${runDir.name}")
            appendLine()
            appendLine("内容：")
            appendLine("- log.jsonl：按时间顺序一行一件事（run_start、每次尝试的思考与输出、工具的参数与结果、用户与系统事件、run_end）。")
            appendLine("- req/：每次真正发出去的请求体（gzip），其中的图片已换成 img/ 下的文件名。")
            appendLine("- img/：模型收到的图片（截图、附图），按内容命名。")
            appendLine("- 内容原样记录，没有遮挡：包括对话、思考、系统提示词与长期记忆、屏幕文字和截图、个人数据查询结果、输入的文字（含密码框输入）。")
            appendLine()
            appendLine("记录情况：")
            appendLine("- 共 $lines 行，$requests 次请求；结束状态：${runEnd ?: "没有 run_end"}")
            appendLine("- 丢掉的行（gap）：$gaps 处" + if (gapRanges.isEmpty()) "" else "，序号 " + gapRanges.joinToString("、"))
            appendLine("- 截断的字段：$truncated 处")
            appendLine("- 没存的图片：$droppedImages 张；没存的请求体：$droppedBodies 份")
        }
    }

    /** 图片的描述总带 mime；没存的带 dropped。 */
    private fun countDroppedImages(value: Any?): Int = when (value) {
        is Map<*, *> -> (if (value.containsKey("mime") && value.containsKey("dropped")) 1 else 0) +
            value.values.sumOf { countDroppedImages(it) }
        is List<*> -> value.sumOf { countDroppedImages(it) }
        else -> 0
    }

    private const val TRUNCATED_MARK = "…[已截断，原长"
}
