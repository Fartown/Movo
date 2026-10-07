package io.github.fartown.movo.agent.tools.mcp

import android.util.Base64
import io.github.fartown.movo.agent.mcp.McpHttpClient
import io.github.fartown.movo.agent.mcp.McpHttpStatusException
import io.github.fartown.movo.agent.mcp.McpJsonRpcException
import io.github.fartown.movo.agent.mcp.McpServerManager
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.Retry
import io.github.fartown.movo.agent.tools.core.ToolError
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.data.model.McpProtocolMode
import io.github.fartown.movo.data.repository.McpServerRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 调用后端（可测）。真实实现复用旧 [McpHttpClient]；测试用假实现直接返回 [McpCallOutcome]。
 */
internal interface McpBackend : AutoCloseable {
    fun call(entry: McpToolEntry, arguments: JSONObject): McpCallOutcome

    override fun close() = Unit
}

/** 调用结果：成功（规范化后的 content/structured/图片）或失败（已映射错误码）。 */
internal sealed interface McpCallOutcome {
    data class Success(
        val content: List<String>,
        /** structuredContent：对象或数组（任意 JSON 值都保留，和重构前一样）。 */
        val structured: Any?,
        val images: List<AgentModelClient.ModelImage>,
        val truncated: Boolean,
        /** 非空表示 MCP 工具自身返回了 isError（取对方原文），由调用方映射成 EXTERNAL_ERROR。 */
        val remoteError: String?,
    ) : McpCallOutcome

    data class Failure(val error: ToolError) : McpCallOutcome
}

/**
 * 传输/协议异常 → 结构化错误码（定义清单 §42，拆开旧的 MCP_CALL_FAILED 折叠）。
 * - >1MiB（McpHttpClient:473 的大小限制）→ TOO_LARGE
 * - 401/403 → PERMISSION_REQUIRED(retry=user)
 * - JSON-RPC -32602 → INVALID_ARGUMENTS
 * - 其余 → EXTERNAL_ERROR（取对方原文进 detail）
 */
internal object McpErrorMapping {
    fun fromThrowable(throwable: Throwable): ToolError {
        val message = throwable.message.orEmpty()
        if (message.contains("超过大小限制")) {
            return ToolError(
                code = ToolErrorCode.TOO_LARGE,
                message = "MCP 返回内容超过 1MiB 上限",
                hint = "让该工具缩小返回，或分页/按条目取",
                detail = message,
            )
        }
        return when (throwable) {
            is McpHttpStatusException -> when (throwable.code) {
                401, 403 -> ToolError(
                    code = ToolErrorCode.PERMISSION_REQUIRED,
                    message = "MCP 服务器要求授权",
                    hint = "在设置里为该 MCP 服务器补充访问令牌后重试",
                    detail = message,
                    retry = Retry.USER,
                )
                else -> ToolError(
                    code = ToolErrorCode.EXTERNAL_ERROR,
                    message = "MCP 服务器返回错误",
                    detail = message,
                )
            }
            is McpJsonRpcException -> when (throwable.code) {
                -32602 -> ToolError(
                    code = ToolErrorCode.INVALID_ARGUMENTS,
                    message = "MCP 工具参数不合法",
                    detail = message,
                )
                else -> ToolError(
                    code = ToolErrorCode.EXTERNAL_ERROR,
                    message = "MCP 协议错误",
                    detail = message,
                )
            }
            else -> ToolError(
                code = ToolErrorCode.EXTERNAL_ERROR,
                message = "MCP 工具调用失败",
                detail = message.ifBlank { throwable.javaClass.simpleName },
            )
        }
    }
}

/**
 * 真实后端：按服务器复用一个 [McpHttpClient]，调用并把原始结果规范化为 [McpCallOutcome]。
 * content 文本/结构化/图片的抽取与大小上限沿用旧 McpRunContext.adaptResult 的策略。
 */
internal class RealMcpBackend(
    @Suppress("unused") private val catalog: McpCatalog,
) : McpBackend {
    private val lifecycleLock = Any()
    private val clients = mutableMapOf<String, McpHttpClient>()
    private var closed = false

    override fun call(entry: McpToolEntry, arguments: JSONObject): McpCallOutcome {
        val client = synchronized(lifecycleLock) {
            if (closed) {
                return McpCallOutcome.Failure(
                    ToolError(ToolErrorCode.INTERNAL_ERROR, "MCP 执行器已关闭"),
                )
            }
            clients.getOrPut(entry.server.id) {
                McpHttpClient(entry.server, entry.bearerToken)
            }
        }
        return try {
            McpResultAdapter.adapt(entry, client.callTool(entry.definition, arguments))
        } catch (throwable: Throwable) {
            McpCallOutcome.Failure(McpErrorMapping.fromThrowable(throwable))
        }
    }

    override fun close() {
        val closing = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            clients.values.toList().also { clients.clear() }
        }
        closing.forEach { runCatching { it.close() } }
    }
}

/**
 * 把 tools/call 的原始结果规范化为 [McpCallOutcome]（沿用旧 McpRunContext.adaptResult 的抽取与大小上限）：
 * 文本 64KB、结构化 64KB、图片 2MB；input_required 与新协议下未完成的结果按不支持返回。
 */
internal object McpResultAdapter {
    fun adapt(entry: McpToolEntry, result: JSONObject): McpCallOutcome {
        // 执行中要求补充输入（elicitation）、或新协议下没完成：不能当成功，否则模型拿个空结果以为做完了。
        val resultType = result.optString("resultType")
        if (resultType == "input_required") {
            return McpCallOutcome.Failure(
                ToolError(
                    ToolErrorCode.UNSUPPORTED,
                    "这个 MCP 工具在执行中要求补充输入，Movo 暂不支持",
                    hint = "换个参数把信息一次给全，或告诉用户需要在别处完成",
                    detail = "input_required",
                ),
            )
        }
        if (entry.server.lastProtocolVersion == McpProtocolMode.LATEST && resultType != "complete") {
            return McpCallOutcome.Failure(
                ToolError(
                    ToolErrorCode.UNSUPPORTED,
                    "MCP 工具返回了不支持的结果类型（没有完成）",
                    detail = "resultType=${resultType.ifBlank { "缺失" }}",
                ),
            )
        }
        val content = result.optJSONArray("content") ?: JSONArray()
        val textItems = mutableListOf<String>()
        val images = mutableListOf<AgentModelClient.ModelImage>()
        var textBytes = 0
        var imageBytes = 0
        var truncated = false

        fun appendText(text: String) {
            val remaining = MAX_TEXT_BYTES - textBytes
            if (remaining <= 0) {
                if (text.isNotEmpty()) truncated = true
                return
            }
            val bounded = text.takeUtf8Bytes(remaining)
            if (bounded.isNotEmpty()) {
                textItems += bounded
                textBytes += bounded.toByteArray().size
            }
            if (bounded.length < text.length) truncated = true
        }

        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            when (item.optString("type")) {
                "text" -> appendText(item.optString("text"))
                "image" -> {
                    val mimeType = item.optString("mimeType")
                    val data = item.optString("data")
                    if (!mimeType.startsWith("image/") || data.isBlank()) continue
                    val bytes = runCatching { Base64.decode(data, Base64.DEFAULT).size }.getOrNull()
                    if (bytes == null || bytes <= 0 || imageBytes + bytes > MAX_IMAGE_BYTES) {
                        truncated = true
                        continue
                    }
                    images += AgentModelClient.ModelImage(
                        reference = "data:$mimeType;base64,$data",
                        mimeType = mimeType,
                        bytes = bytes,
                        source = "mcp",
                    )
                    imageBytes += bytes
                }
                "resource" -> {
                    val resource = item.optJSONObject("resource")
                    val resourceText = resource?.optString("text").orEmpty()
                    if (resourceText.isNotBlank()) appendText(resourceText) else truncated = true
                }
                "resource_link" -> {
                    val uri = item.optString("uri")
                    if (uri.isNotBlank()) {
                        val label = item.optString("name").ifBlank { item.optString("title") }
                        appendText(if (label.isBlank()) uri else "$label: $uri")
                    }
                }
                else -> truncated = true
            }
        }

        val structured = result.opt("structuredContent")?.takeUnless { it == JSONObject.NULL }
        val boundedStructured = structured?.let {
            if (it.toString().toByteArray().size <= MAX_STRUCTURED_BYTES) {
                it
            } else {
                truncated = true
                null
            }
        }

        val isError = result.optBoolean("isError", false)
        val remoteError = if (isError) {
            // 对方原文进 detail：截到 4000 字，免得错误结果本身超长、连错误码都给不到模型。
            textItems.joinToString("\n").ifBlank { "MCP 工具返回了错误但未附说明" }.take(MAX_REMOTE_ERROR_CHARS)
        } else {
            null
        }

        return McpCallOutcome.Success(
            content = textItems,
            structured = boundedStructured,
            images = images,
            truncated = truncated,
            remoteError = remoteError,
        )
    }

    private fun String.takeUtf8Bytes(maxBytes: Int): String {
        if (toByteArray().size <= maxBytes) return this
        var low = 0
        var high = length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (substring(0, middle).toByteArray().size <= maxBytes) low = middle else high = middle - 1
        }
        return substring(0, low)
    }

    private const val MAX_TEXT_BYTES = 64 * 1024
    private const val MAX_STRUCTURED_BYTES = 64 * 1024
    private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    private const val MAX_REMOTE_ERROR_CHARS = 4_000
}

/**
 * 任务开始时加载冻结目录（suspend，复用旧仓库与发现逻辑）。主流程在 run 开始调用一次，
 * 再把结果传给 [McpToolProvider]。为空表示用户未添加可用 MCP，provider 不暴露任何工具。
 */
internal object McpCatalogLoader {
    suspend fun load(budgetTokens: Int = McpCatalog.DEFAULT_BUDGET_TOKENS): McpCatalog {
        val now = System.currentTimeMillis()
        val raw = mutableListOf<McpRawTool>()
        McpServerRepository.enabledServers().forEach { configured ->
            val bearerToken = McpServerRepository.bearerToken(configured.id)
            val needsRefresh = configured.lastProtocolVersion == null ||
                configured.lastProtocolVersion == McpProtocolMode.LATEST &&
                (configured.toolsExpireAt == null || configured.toolsExpireAt <= now)
            val server = if (needsRefresh) {
                runCatching {
                    McpServerManager.discover(configured, bearerToken).also {
                        McpServerRepository.update(it)
                    }
                }.getOrNull() ?: return@forEach
            } else {
                configured
            }
            server.activeTools.forEach { tool ->
                raw += McpRawTool(server, tool, bearerToken)
            }
        }
        return McpCatalog.build(raw, budgetTokens)
    }
}
