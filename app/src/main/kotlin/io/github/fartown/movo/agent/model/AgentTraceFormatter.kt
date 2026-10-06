package io.github.fartown.movo.agent.model

import java.net.URI
import java.util.Locale
import org.json.JSONObject

/** 工具摘要面向用户展示，不包含敏感参数；终端命令通过独立字段提供给用户核对。 */
internal class AgentTraceFormatter {
    fun summarizeArguments(toolCall: AgentModelClient.ToolCall): String =
        when (toolCall.name) {
            BROWSER_TOOL_NAME -> summarizeBrowserArguments(toolCall.argumentsJson)
            "open_uri" -> summarizeOpenUriArguments(toolCall.argumentsJson)
            "terminal" -> summarizeTerminalArguments(toolCall.argumentsJson)
            "run_command" -> "执行命令 · Android · root"
            "write_file" -> summarizeTextLength("写入文件", toolCall.argumentsJson, "content")
            "read_file" -> "读取文件"
            "list_directory" -> "列出目录"
            "input_text" -> summarizeTextLength("输入文本", toolCall.argumentsJson, "text")
            "replace_text" -> summarizeTextLength("替换文本", toolCall.argumentsJson, "text")
            "paste_text", "set_clipboard" ->
                summarizeTextLength("粘贴文本", toolCall.argumentsJson, "text")
            "clear_text" -> "清空文本"
            "get_clipboard" -> "读取剪贴板"
            "search_apps" -> summarizeQueryArguments("搜索应用", toolCall.argumentsJson)
            "launch_app" -> "打开应用"
            "get_current_context" -> "读取当前上下文"
            "observe_screen" -> summarizeObservationArguments(toolCall.argumentsJson)
            "tap" -> summarizePointArguments("点击屏幕", toolCall.argumentsJson)
            "long_press" -> summarizePointArguments("长按屏幕", toolCall.argumentsJson)
            "tap_area" -> "点击区域"
            "tap_element" -> summarizeElementArguments("点击元素", toolCall.argumentsJson)
            "long_press_element" -> summarizeElementArguments("长按元素", toolCall.argumentsJson)
            "swipe" -> "滑动屏幕"
            "scroll" -> summarizeScrollArguments("滚动屏幕", toolCall.argumentsJson)
            "scroll_element" ->
                summarizeScrollArguments("滚动元素", toolCall.argumentsJson, withIndex = true)
            "press_key" -> summarizePressKeyArguments(toolCall.argumentsJson)
            "wait" -> summarizeWaitArguments(toolCall.argumentsJson)
            "wait_for_text" -> "等待文本出现"
            "wait_for_package" -> "等待应用就绪"
            "open_system_panel" -> "打开系统面板"
            "read_image" -> "查看图片"
            AgentConversationToolCatalog.READ_HISTORY -> "读取当前会话历史"
            "memory_get", "character_memory_get", "memory_read" -> summarizeMemoryGetArguments(toolCall.argumentsJson)
            "memory_write", "character_memory_write" -> summarizeMemoryWriteArguments(toolCall.argumentsJson)
            "skills_list" -> "查看技能列表"
            "skills_read" -> "读取技能"
            "skills_read_resource" -> "读取技能资源"
            "skills_list_curated" -> "浏览精选技能"
            "skills_inspect_github" -> "查看技能详情"
            "skills_install_from_github" -> "安装技能"
            // 后台监听与通知：步骤标题带上监听名字 / 通知标题（规范 8.12）。
            "monitor_start" -> summarizeMonitorStartArguments(toolCall.argumentsJson)
            "monitor_stop" -> runCatching {
                io.github.fartown.movo.agent.monitor.MonitorRegistry
                    .find(JSONObject(toolCall.argumentsJson).optString("task_id"))?.name
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { "停止监听·$it" } ?: "停止监听"
            "monitor_list" -> "查看后台监听"
            "notify_user" -> labelWithArgument("发送通知", toolCall.argumentsJson, "title")
            // 类型化工具：步骤标题带上一个关键参数。
            "ui_scroll" -> summarizeScrollArguments("滚动屏幕", toolCall.argumentsJson)
            "ui_input" -> summarizeTextLength("输入文本", toolCall.argumentsJson, "text")
            "app_open" -> labelWithArgument("打开应用", toolCall.argumentsJson, "name")
            "browser_open" -> runCatching {
                safeHttpHost(JSONObject(toolCall.argumentsJson).optString("url"))?.let { "打开网页 · $it" }
            }.getOrNull() ?: "打开网页"
            else -> {
                val label = DEVICE_ACTION_LABELS[toolCall.name] ?: TypedToolLabels.of(toolCall.name)
                when {
                    label == null -> "准备执行"
                    toolCall.name.startsWith("search_") || toolCall.name.endsWith("_search") ->
                        summarizeQueryArguments(label, toolCall.argumentsJson)
                    else -> label
                }
            }
        }

    /** 命令以脱敏后的用户可见投影进入运行轨迹；日志仍只记录长度。 */
    fun displayCommand(toolCall: AgentModelClient.ToolCall): String? =
        if (toolCall.name in COMMAND_TOOLS) {
            runCatching {
                JSONObject(toolCall.argumentsJson)
                    .optString("command")
                    .trim()
                    .takeIf { it.isNotBlank() && it.length <= MAX_DISPLAY_COMMAND_CHARS }
                    ?.redactDisplaySecrets()
            }.getOrNull()
        } else {
            null
        }

    private fun String.redactDisplaySecrets(): String =
        replace(SENSITIVE_ASSIGNMENT) { match ->
            "${match.groupValues[1]}=<已隐藏>"
        }
            .replace(SENSITIVE_FLAG) { match ->
                "${match.groupValues[1]}<已隐藏>"
            }
            .replace(SENSITIVE_HEADER) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}<已隐藏>"
            }

    /**
     * 「开始监听·喝水提醒 · 最长 30 分钟」：名字 + 实际生效的时长（规范 8.12）。实际时长 = 请求值（没写用默认值）
     * 与设置里的上限取小，和注册表启动时的算法一致（真机：请求 1 小时、上限 30 分钟，标题曾写成 1 小时）。
     */
    private fun summarizeMonitorStartArguments(argumentsJson: String): String {
        val base = labelWithArgument("开始监听", argumentsJson, "description")
        val requested = runCatching { JSONObject(argumentsJson).optLong("timeout_ms", 0L) }.getOrDefault(0L)
            .takeIf { it > 0L } ?: io.github.fartown.movo.agent.monitor.MonitorSettings.DEFAULT_TIMEOUT_MS
        val cap = io.github.fartown.movo.agent.runtime.AgentAppContext.resolve()
            ?.let { runCatching { io.github.fartown.movo.agent.monitor.MonitorSettings.maxDurationMs(it) }.getOrNull() }
        val effective = cap?.let { minOf(requested, it) } ?: requested
        return "$base · 最长 ${io.github.fartown.movo.agent.monitor.MonitorEventFormatter.durationLabel(effective)}"
    }

    /** 「开始监听·喝水提醒」：标题后接一个短参数（名字 / 标题），取不到时只写标题。 */
    private fun labelWithArgument(label: String, argumentsJson: String, key: String): String =
        runCatching { JSONObject(argumentsJson).optString(key).trim().take(24) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { "$label·$it" } ?: label

    /** 外部 URI 摘要不记录 path、query、fragment 或用户信息。 */
    fun summarizeOpenUriArguments(argumentsJson: String): String =
        runCatching {
            val raw = JSONObject(argumentsJson).optString("uri").trim()
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase()?.take(24)
            val host = uri.host?.lowercase()?.take(160)
            listOfNotNull("交给外部应用", scheme, host).joinToString(" · ")
        }.getOrDefault("交给外部应用")

    /** browser_use 摘要只暴露动作和安全提取的 host。 */
    fun summarizeBrowserArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val action = arguments.optString("action").browserActionLabel()
            val host = safeHttpHost(arguments.optString("url"))
            listOfNotNull(action, host).joinToString(" · ")
        }.getOrElse { "浏览器操作" }

    private fun summarizeTerminalArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val action = arguments.optString("action").terminalActionLabel()
            val environment = arguments.optString("environment", "android")
                .terminalEnvironmentLabel()
            val identity = arguments.optString("identity", "root")
                .takeIf { it == "root" || it == "user" }
            buildList {
                add("终端")
                add(action)
                add(environment)
                identity?.let(::add)
                if (arguments.optBoolean("async", false)) add("后台")
            }.joinToString(" · ")
        }.getOrDefault("终端")

    private fun summarizeTextLength(
        label: String,
        argumentsJson: String,
        key: String,
    ): String =
        runCatching {
            val chars = JSONObject(argumentsJson).optString(key).length
            "$label · $chars 字符"
        }.getOrDefault(label)

    /** 搜索关键词是用户自己发起的查询，直接展示；仍做单行化与长度截断。 */
    private fun summarizeQueryArguments(label: String, argumentsJson: String): String =
        runCatching {
            val query = sanitizeSummaryValue(
                JSONObject(argumentsJson).optString("query"),
                MAX_QUERY_SUMMARY_CHARS,
            )
            if (query.isNotBlank()) "$label · $query" else label
        }.getOrDefault(label)

    private fun summarizePointArguments(label: String, argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            "$label · (${arguments.optInt("x")}, ${arguments.optInt("y")})"
        }.getOrDefault(label)

    private fun summarizeElementArguments(label: String, argumentsJson: String): String =
        runCatching {
            val index = JSONObject(argumentsJson).optInt("index", -1)
            if (index >= 0) "$label · #$index" else label
        }.getOrDefault(label)

    private fun summarizeScrollArguments(
        label: String,
        argumentsJson: String,
        withIndex: Boolean = false,
    ): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            buildList {
                add(label)
                if (withIndex) {
                    arguments.optInt("index", -1).takeIf { it >= 0 }?.let { add("#$it") }
                }
                arguments.optString("direction").scrollDirectionLabel()?.let(::add)
            }.joinToString(" · ")
        }.getOrDefault(label)

    private fun summarizePressKeyArguments(argumentsJson: String): String =
        runCatching {
            val button = JSONObject(argumentsJson).optString("button").pressKeyLabel()
            listOfNotNull("按键", button).joinToString(" · ")
        }.getOrDefault("按键")

    private fun summarizeWaitArguments(argumentsJson: String): String =
        runCatching {
            val durationMs = JSONObject(argumentsJson).optInt("duration_ms", 1_000)
                .coerceAtLeast(0)
            val duration = if (durationMs >= 1_000) {
                String.format(Locale.US, "%.1f", durationMs / 1_000f)
                    .trimEnd('0').trimEnd('.') + " 秒"
            } else {
                "$durationMs 毫秒"
            }
            "等待 · $duration"
        }.getOrDefault("等待")

    private fun summarizeObservationArguments(argumentsJson: String): String =
        runCatching {
            val options = AgentScreenObservationContract.resolve(JSONObject(argumentsJson))
            buildList {
                add("观察屏幕")
                if (options.includeScreenshot) add("含截图")
                if (options.includeUiTree) add("含界面树")
            }.joinToString(" · ")
        }.getOrDefault("观察屏幕")

    private fun summarizeMemoryGetArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            if (arguments.optString("query").isNotBlank()) "检索记忆" else "读取记忆"
        }.getOrDefault("读取记忆")

    private fun summarizeMemoryWriteArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val mode = when (arguments.optString("mode")) {
                "replace_range", "replace" -> "替换片段"
                "append" -> "追加"
                "clear" -> "清空"
                else -> null
            }
            if (arguments.optString("mode") == "clear") return@runCatching "更新记忆 · 清空"
            // 新 schema 用 new_text，旧 schema 用 content。
            val content = arguments.optString("new_text").ifEmpty { arguments.optString("content") }
            val lines = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
            buildList {
                add("更新记忆")
                mode?.let(::add)
                add("$lines 行")
                add("${content.toByteArray(Charsets.UTF_8).size} 字节")
            }.joinToString(" · ")
        }.getOrDefault("更新记忆")

    /**
     * 结果成败供事件与 UI 状态使用。类型化工具看结构化结果：status 不是 ok（error、unknown）算失败，
     * 终端命令退出码不是 0 也算失败；旧格式结果才看 JSON 里的 ok 字段。
     */
    fun isSuccessResult(result: AgentModelClient.ToolResult): Boolean {
        result.outcome?.let { outcome ->
            if (outcome.status != io.github.fartown.movo.agent.tools.core.ToolStatus.OK) return false
            return terminalExitCode(outcome.textBody)?.let { it == 0 } ?: true
        }
        if (result.status != "ok") return false
        return parseResultJson(result)?.optBoolean("ok", true) ?: true
    }

    fun summarizeResult(
        toolName: String,
        result: AgentModelClient.ToolResult,
    ): String {
        result.outcome?.let { return summarizeTypedResult(toolName, result, it) }
        val json = parseResultJson(result)
        // 终端 exit_code != 0 时 ok=false 但没有 code 字段，必须走专用分支保留退出码与输出
        if (toolName == "terminal" || toolName == "run_command") {
            return summarizeTerminalResult(json)
        }
        if (!isSuccessResult(result)) return summarizeFailure(json)
        return when (toolName) {
            BROWSER_TOOL_NAME -> json?.let(::summarizeBrowserResult) ?: "浏览器操作完成"
            AgentConversationToolCatalog.READ_HISTORY -> "已读取历史分页"
            "memory_get", "memory_write", "character_memory_get", "character_memory_write" ->
                json?.let { summarizeMemoryResult(toolName, it) } ?: "完成"
            "search_apps" -> json?.let(::summarizeSearchAppsResult) ?: "完成"
            "launch_app" -> json?.let(::summarizeLaunchAppResult) ?: "已打开"
            else -> json?.let { summarizeGenericResult(it, result) } ?: "完成"
        }
    }

    private fun parseResultJson(result: AgentModelClient.ToolResult): JSONObject? =
        runCatching { JSONObject(result.content) }.getOrNull()

    /** 类型化工具的结果摘要：失败写原因和错误码，终端写退出码和输出开头，其余写「完成」加一个关键信息。 */
    private fun summarizeTypedResult(
        toolName: String,
        result: AgentModelClient.ToolResult,
        outcome: io.github.fartown.movo.agent.tools.core.ToolOutcome,
    ): String {
        val error = outcome.error
        if (outcome.status != io.github.fartown.movo.agent.tools.core.ToolStatus.OK && error != null) {
            val head = if (outcome.status == io.github.fartown.movo.agent.tools.core.ToolStatus.UNKNOWN) "未确认" else "失败"
            return buildList {
                add(head)
                sanitizeSummaryValue(error.message).takeIf { it.isNotBlank() }?.let(::add)
                add("code=${error.code.name}")
            }.joinToString(" · ")
        }
        // 工具给了界面视图的，用它的摘要（一个关键结果）。
        outcome.view?.summary?.takeIf { it.isNotBlank() }?.let { return it }
        val data = outcome.data
        if (toolName == "terminal_run" || toolName == "terminal_job") {
            if (data?.optBoolean("running", false) == true && data.has("job_id")) return "已转入后台"
            return summarizeTypedTerminal(outcome.textBody)
        }
        val detail: String? = when (toolName) {
            "app_open" -> data?.optString("app_name")?.let(::sanitizeSummaryValue)?.takeIf { it.isNotBlank() }
                ?.let { "已打开 · $it" } ?: "已打开"
            "browser_open" -> data?.optString("title")?.let(::sanitizeSummaryValue)?.takeIf { it.isNotBlank() }
                ?.let { "已打开 · 《$it》" } ?: "已打开网页"
            "browser_read" -> "已读取网页"
            "memory_read" -> "已读取记忆"
            "memory_write" -> "已更新记忆"
            "clipboard_write" -> "已复制"
            "notify_user" -> "已发送通知"
            else -> null
        }
        if (detail != null) return detail
        val count = data?.let { json ->
            sequenceOf("apps", "results", "records", "items", "files", "entries", "alarms", "timers", "nodes")
                .mapNotNull { key -> json.optJSONArray(key)?.length() }
                .firstOrNull()
        }
        return buildList {
            add("完成")
            count?.let { add("$it 条") }
            if (result.images.isNotEmpty()) add("${result.images.size} 张图片")
        }.joinToString(" · ")
    }

    /** terminal_run 的文本结果头部有 `exit_code: N`。 */
    private fun terminalExitCode(textBody: String?): Int? =
        textBody?.lineSequence()?.take(8)
            ?.firstOrNull { it.startsWith("exit_code:") }
            ?.substringAfter(':')?.trim()?.toIntOrNull()

    private fun summarizeTypedTerminal(textBody: String?): String {
        val exitCode = terminalExitCode(textBody) ?: return "完成"
        val stdout = textBody.orEmpty().substringAfter("--- stdout ---\n", "").substringBefore("\n--- stderr ---")
        val stderr = textBody.orEmpty().substringAfter("--- stderr ---\n", "")
        val status = if (exitCode == 0) "执行完成" else "失败 · 退出码 $exitCode"
        val output = if (exitCode == 0) stdout else stderr.ifBlank { stdout }
        val preview = terminalOutputPreview(output, truncated = output.contains("…(std")) ?: return status
        return "$status\n$preview"
    }

    /** 失败摘要保留 code= 标记，供运行日志提取稳定错误码；message 是工具侧给出的中文原因。 */
    private fun summarizeFailure(json: JSONObject?): String {
        val code = json?.optString("code")?.takeIf { it.isNotBlank() }
        val reason = json?.optString("message")
            ?.let(::sanitizeSummaryValue)
            ?.takeIf { it.isNotBlank() }
        return buildList {
            add("失败")
            reason?.let(::add)
            code?.let { add("code=$it") }
        }.joinToString(" · ")
    }

    private fun summarizeMemoryResult(toolName: String, json: JSONObject): String =
        buildList {
            add(if (toolName.endsWith("memory_get")) "已读取记忆" else "已更新记忆")
            if (json.has("line_count")) add("${json.optInt("line_count")} 行")
            if (json.has("bytes")) add("${json.optInt("bytes")} 字节")
        }.joinToString(" · ")

    private fun summarizeGenericResult(
        json: JSONObject,
        result: AgentModelClient.ToolResult,
    ): String =
        buildList {
            add("完成")
            json.optJSONArray("apps")?.let { add("找到 ${it.length()} 个应用") }
            json.optJSONArray("candidates")?.let { add("${it.length()} 个候选") }
            if (result.images.isNotEmpty()) add("${result.images.size} 张图片")
        }.joinToString(" · ")

    private fun summarizeSearchAppsResult(json: JSONObject): String {
        val apps = json.optJSONArray("apps") ?: return "未找到匹配应用"
        val total = apps.length()
        if (total == 0) return "未找到匹配应用"
        val names = (0 until total).mapNotNull { index ->
            apps.optJSONObject(index)?.optString("app_name")
                ?.let(::sanitizeSummaryValue)
                ?.takeIf { it.isNotBlank() }
        }
        return buildString {
            append("已找到 $total 个应用")
            val shown = names.take(MAX_LISTED_APP_NAMES)
            if (shown.isNotEmpty()) {
                append(" · ").append(shown.joinToString("、"))
                if (total > shown.size) append(" 等")
            }
        }
    }

    private fun summarizeLaunchAppResult(json: JSONObject): String {
        val appName = sanitizeSummaryValue(json.optString("app_name"))
        return if (appName.isNotBlank()) "已打开 · $appName" else "已打开"
    }

    /**
     * 终端结果面向用户展示退出状态与输出预览；输出可能很长，
     * 只保留开头几行，截断时追加省略标记。
     */
    private fun summarizeTerminalResult(json: JSONObject?): String {
        if (json == null) return "终端"
        if (json.optString("code").isNotBlank()) return summarizeFailure(json)
        if (!json.has("exit_code") || json.isNull("exit_code")) {
            val action = json.optString("action").terminalActionLabel()
            return if (json.optBoolean("ok", true)) "终端 · $action" else "失败 · $action"
        }
        val exitCode = json.optInt("exit_code")
        val timedOut = json.optBoolean("timed_out", false)
        val status = when {
            timedOut -> "失败 · 执行超时"
            exitCode == 0 -> "执行完成"
            else -> "失败 · 退出码 $exitCode"
        }
        val output = if (exitCode == 0) {
            json.optString("stdout")
        } else {
            json.optString("stderr").ifBlank { json.optString("stdout") }
        }
        val truncated = json.optBoolean("stdout_truncated", false) ||
            json.optBoolean("stderr_truncated", false)
        val preview = terminalOutputPreview(output, truncated) ?: return status
        return "$status\n$preview"
    }

    private fun terminalOutputPreview(output: String, truncated: Boolean): String? {
        val normalized = output.trim()
        if (normalized.isEmpty()) return null
        val allLines = normalized.lines()
        var preview = allLines.take(MAX_TERMINAL_PREVIEW_LINES).joinToString("\n")
        var capped = allLines.size > MAX_TERMINAL_PREVIEW_LINES || truncated
        if (preview.length > MAX_TERMINAL_PREVIEW_CHARS) {
            preview = preview.take(MAX_TERMINAL_PREVIEW_CHARS)
            capped = true
        }
        return if (capped) "$preview\n…" else preview
    }

    private fun summarizeBrowserResult(json: JSONObject): String {
        val page = json.optJSONObject("page")
            ?: json.optJSONObject("page_info")
            ?: json.optJSONObject("pageInfo")
        val action = json.optString("action")
            .takeIf { it in BROWSER_ACTIONS }
            ?: "unknown"
        val host = sequenceOf(json, page)
            .filterNotNull()
            .flatMap { source ->
                sequenceOf("url", "current_url", "currentUrl", "final_url", "finalUrl")
                    .map(source::optString)
            }
            .mapNotNull(::safeHttpHost)
            .firstOrNull()
        val title = sequenceOf(json, page)
            .filterNotNull()
            .map { it.opt("title") }
            .filterIsInstance<String>()
            .map(::sanitizeSummaryValue)
            .firstOrNull { it.isNotBlank() }
        val textChars = sequenceOf(json, page)
            .filterNotNull()
            .mapNotNull { source ->
                source.firstNonNegativeInt("text_length", "textLength", "text_chars", "textChars")
            }
            .firstOrNull()
            ?: sequenceOf(json, page)
                .filterNotNull()
                .flatMap { source -> sequenceOf("text", "readable", "content").map(source::opt) }
                .filterIsInstance<String>()
                .map(String::length)
                .firstOrNull()
        val elementCount = json.firstNonNegativeInt("element_count", "elementCount", "elements_count")
            ?: json.optJSONArray("elements")?.length()

        return buildList {
            add(action.browserSuccessLabel())
            host?.let(::add)
            title?.let { add("《$it》") }
            if (action in BROWSER_TEXT_ACTIONS) {
                textChars?.let { add("约 ${formatCharCount(it)}") }
            }
            elementCount?.let { add("$it 个元素") }
            if (json.optBoolean("truncated", false)) add("已截断")
        }.joinToString(" · ")
    }

    private fun formatCharCount(chars: Int): String =
        if (chars >= 10_000) {
            String.format(Locale.US, "%.1f", chars / 10_000f).trimEnd('0').trimEnd('.') + " 万字"
        } else {
            "$chars 字"
        }

    private fun JSONObject.firstNonNegativeInt(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            if (!has(key)) return@firstNotNullOfOrNull null
            optInt(key, -1).takeIf { it >= 0 }
        }

    private fun sanitizeSummaryValue(value: String, maxChars: Int = 80): String =
        value.replace(Regex("\\s+"), " ")
            .trim()
            .replace(',', '，')
            .replace('=', '＝')
            .let { if (it.length <= maxChars) it else it.take(maxChars) + "..." }

    private fun safeHttpHost(rawUrl: String): String? =
        rawUrl.trim()
            .takeIf(String::isNotEmpty)
            ?.let { value ->
                runCatching {
                    val uri = URI(value)
                    uri.host
                        ?.takeIf {
                            uri.scheme.equals("http", ignoreCase = true) ||
                                uri.scheme.equals("https", ignoreCase = true)
                        }
                        ?.lowercase()
                        ?.take(160)
                }.getOrNull()
            }

    private fun String.browserActionLabel(): String = when (this) {
        "navigate" -> "打开网页"
        "get_readable" -> "提取正文"
        "get_text" -> "读取文本"
        "find_elements" -> "查找元素"
        "click" -> "点击网页"
        "type" -> "输入内容"
        "scroll" -> "滚动网页"
        "screenshot" -> "网页截图"
        "get_page_info" -> "查看网页信息"
        "go_back" -> "网页后退"
        "go_forward" -> "网页前进"
        "reload" -> "刷新网页"
        "wait_for_selector" -> "等待网页元素"
        else -> "浏览器操作"
    }

    private fun String.browserSuccessLabel(): String = when (this) {
        "navigate" -> "已打开"
        "get_readable" -> "已提取正文"
        "get_text" -> "已读取文本"
        "find_elements" -> "已找到元素"
        "click" -> "已点击网页"
        "type" -> "已输入内容"
        "scroll" -> "已滚动网页"
        "screenshot" -> "已截图"
        "get_page_info" -> "已读取页面信息"
        "go_back" -> "已后退"
        "go_forward" -> "已前进"
        "reload" -> "已刷新"
        "wait_for_selector" -> "已等到目标元素"
        else -> "浏览器操作完成"
    }

    private fun String.scrollDirectionLabel(): String? = when (lowercase(Locale.US)) {
        "up" -> "向上"
        "down" -> "向下"
        "left" -> "向左"
        "right" -> "向右"
        else -> null
    }

    private fun String.pressKeyLabel(): String? = when (lowercase(Locale.US)) {
        "back" -> "返回"
        "home" -> "主页"
        "recents", "recent" -> "最近任务"
        "notifications" -> "通知栏"
        "quick_settings" -> "控制中心"
        "power" -> "电源"
        "volume_up" -> "音量加"
        "volume_down" -> "音量减"
        "mute" -> "静音"
        else -> null
    }

    private fun String.terminalActionLabel(): String = when (this) {
        "open" -> "创建会话"
        "exec" -> "执行命令"
        "open_and_exec" -> "单次执行"
        "read_async_result" -> "读取后台输出"
        "close" -> "关闭终端"
        "daemon_start" -> "启动守护任务"
        "daemon_list" -> "守护任务列表"
        "daemon_logs" -> "查看守护日志"
        "daemon_stop" -> "停止守护任务"
        else -> "终端操作"
    }

    private fun String.terminalEnvironmentLabel(): String = when (this) {
        "linux" -> "Linux"
        "alpine" -> "Alpine"
        "debian" -> "Debian"
        else -> "Android"
    }

    private companion object {
        const val BROWSER_TOOL_NAME = "browser_use"
        const val MAX_DISPLAY_COMMAND_CHARS = 4_000
        const val MAX_QUERY_SUMMARY_CHARS = 30
        const val MAX_LISTED_APP_NAMES = 3
        const val MAX_TERMINAL_PREVIEW_LINES = 3
        const val MAX_TERMINAL_PREVIEW_CHARS = 240
        val SENSITIVE_ASSIGNMENT = Regex(
            """(?i)\b([A-Z0-9_]*(?:API[_-]?KEY|ACCESS[_-]?TOKEN|AUTH[_-]?TOKEN|TOKEN|PASSWORD|PASSWD|SECRET)[A-Z0-9_]*)\s*=\s*(?:"[^"]*"|'[^']*'|[^\s;&|]+)"""
        )
        val SENSITIVE_FLAG = Regex(
            """(?i)(--?(?:password|passwd|token|api[-_]?key|secret)(?:\s*=\s*|\s+))(?:"[^"]*"|'[^']*'|[^\s;&|]+)"""
        )
        val SENSITIVE_HEADER = Regex(
            """(?i)\b(Authorization|Proxy-Authorization|X-Api-Key)(\s*:\s*)[^'"\r\n;&|]+"""
        )
        val BROWSER_ACTIONS = setOf(
            "navigate",
            "get_readable",
            "get_text",
            "find_elements",
            "click",
            "type",
            "scroll",
            "screenshot",
            "get_page_info",
            "go_back",
            "go_forward",
            "reload",
            "wait_for_selector",
        )
        val BROWSER_TEXT_ACTIONS = setOf("get_readable", "get_text")

        /** 会把命令原文作为可核对字段展示给用户的工具。 */
        val COMMAND_TOOLS = setOf("terminal", "run_command", "terminal_run", "monitor_start")


        /** 结构化设备工具只展示动作标签，不暴露任何参数。 */
        val DEVICE_ACTION_LABELS = mapOf(
            "set_alarm" to "设置闹钟",
            "set_timer" to "设置计时器",
            "device_status" to "查看设备状态",
            "network_info" to "查看网络信息",
            "top_memory_apps" to "查看内存占用排行",
            "top_storage_apps" to "查看存储占用排行",
            "media_control" to "控制媒体播放",
            "set_volume" to "调整音量",
            "get_setting" to "读取系统设置",
            "wifi_credentials" to "读取 Wi-Fi 密码",
            "recent_notifications" to "读取最近通知",
            "search_notification_history" to "搜索通知历史",
            "recent_app_activity" to "查看应用活动",
            "app_usage_summary" to "查看应用使用统计",
            "get_current_location" to "获取当前位置",
            "get_device_environment" to "查看设备环境",
            "list_alarms" to "查看闹钟列表",
            "list_active_timers" to "查看计时器",
            "search_clipboard_history" to "搜索剪贴板历史",
            "get_health_summary" to "查看健康摘要",
            "read_sms_code" to "读取短信验证码",
            "get_logcat" to "读取系统日志",
            "search_media" to "搜索媒体文件",
            "search_audio" to "搜索音频",
            "search_recordings" to "搜索录音",
            "search_files" to "搜索文件",
            "search_calendar_events" to "搜索日程",
            "search_contacts" to "搜索联系人",
            "search_call_history" to "搜索通话记录",
            "search_messages" to "搜索短信",
            "search_downloads" to "搜索下载内容",
            "search_coloros_notes" to "搜索便签",
            "search_coloros_recordings" to "搜索录音机",
            "search_recording_summaries" to "搜索录音摘要",
            "search_coloros_memories" to "搜索小布记忆",
            "search_saved_places" to "搜索收藏地点",
            "search_personal_orders" to "搜索个人订单",
            "search_qq_chat_images" to "搜索 QQ 聊天图片",
            "search_wechat_chat_images" to "搜索微信聊天图片",
            "set_setting" to "修改系统设置",
            "set_device_state" to "修改设备状态",
            "app_state_control" to "管理应用状态",
        )
    }
}

/** 类型化工具子系统的工具名 → 中文动作名（执行卡步骤标题、审批卡标题共用；只写动作，不暴露参数）。 */
internal object TypedToolLabels {
    private val labels = mapOf(
        "device_read" to "查看设备状态",
        "device_toggle" to "切换设备开关",
        "setting_read" to "读取系统设置",
        "setting_write" to "修改系统设置",
        "device_diagnostics" to "设备诊断",
        "app_search" to "搜索应用",
        "app_open" to "打开应用",
        "app_control" to "管理应用",
        "ui_observe" to "查看屏幕",
        "ui_tap" to "点击屏幕",
        "ui_scroll" to "滚动屏幕",
        "ui_swipe" to "滑动屏幕",
        "ui_input" to "输入文本",
        "ui_key" to "按键",
        "ui_wait" to "等待界面",
        "clipboard_read" to "读取剪贴板",
        "clipboard_write" to "写入剪贴板",
        "clock_create" to "设置闹钟或计时器",
        "clock_read" to "查看闹钟和计时器",
        "volume_set" to "调整音量",
        "personal_search" to "搜索个人数据",
        "sms_code_read" to "读取验证码",
        "usage_read" to "查看应用使用情况",
        "health_read" to "读取健康数据",
        "wifi_password_read" to "读取 Wi-Fi 密码",
        "file_search" to "搜索文件",
        "file_read" to "读取文件",
        "file_write" to "写入文件",
        "file_list" to "列出目录",
        "terminal_run" to "执行命令",
        "terminal_job" to "管理后台命令",
        "browser_open" to "打开网页",
        "browser_read" to "读取网页",
        "browser_act" to "操作网页",
        "memory_read" to "读取记忆",
        "skill_read" to "读取技能",
        "skill_install" to "安装技能",
        "conversation_read" to "读取会话历史",
        "ask_user" to "询问你",
        "tool_search" to "查找工具",
        "mcp_call" to "调用 MCP 工具",
        "mcp_find" to "查找 MCP 工具",
        "memory_write" to "更新记忆",
        "media_control" to "控制媒体播放",
        "monitor_start" to "开始后台监听",
        "monitor_stop" to "停止后台监听",
        "monitor_list" to "查看后台监听",
        "notify_user" to "发送通知",
    )

    /** 类型化工具的动作名；MCP 直连工具（mcp_<服务器>_<工具>）统一叫「调用 MCP 工具」。 */
    fun of(name: String): String? = labels[name] ?: if (name.startsWith("mcp_")) "调用 MCP 工具" else null
}
