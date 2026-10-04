package io.github.fartown.movo.agent.tools.mcp

import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.data.model.McpServerSetting
import io.github.fartown.movo.data.model.McpToolDefinition
import java.security.MessageDigest

/**
 * 一次 run 冻结的 MCP 工具目录（定义清单 §42）。
 *
 * - 任务开始快照一次：目录、短名、预算判定在整个 run 内不变，请求视图固定、不破坏缓存。
 * - 短名不可变 ASCII：由显示名清洗得到，清洗为空回退 `s1`/`s2`，冲突加 hash；
 *   理想应在“添加服务器”时生成并随服务器持久化（否则改显示名会动摇历史回放），
 *   当前在目录构建时按服务器 id 确定性推导，做不到真正跨会话不可变 —— 见返回报告 TODO。
 * - 预算：设备无分词器，按 UTF-8 字节 ÷3 保守估一次。超预算时请求视图只放 mcp_find + mcp_call。
 */
internal data class McpToolEntry(
    /** 固定短名 `mcp_<server>_<tool>`，既是请求视图里的工具名，也是 mcp_find/mcp_call 的标识。 */
    val shortName: String,
    val server: McpServerSetting,
    val definition: McpToolDefinition,
    val bearerToken: String?,
) {
    /** 风险按工具注解动态定：readOnlyHint→READ，destructiveHint→EXTERNAL，其余 LOCAL。 */
    val risk: Risk get() = riskOf(definition)
}

/** 目录构建的原料：从仓库读到的（服务器 + 工具定义 + 令牌）。 */
internal data class McpRawTool(
    val server: McpServerSetting,
    val definition: McpToolDefinition,
    val bearerToken: String?,
)

internal class McpCatalog(
    val entries: List<McpToolEntry>,
    /** true：工具太多/太长，请求视图改放 mcp_find + mcp_call；false：逐个直接暴露。 */
    val overBudget: Boolean,
) {
    private val byShortName = entries.associateBy(McpToolEntry::shortName)

    fun find(shortName: String): McpToolEntry? = byShortName[shortName]

    companion object {
        /** 请求视图里 MCP 工具的保守 token 预算；超出改走 find+call。 */
        const val DEFAULT_BUDGET_TOKENS = 1500

        /** 直接暴露的工具数上限；超出也改走 find+call（与旧 MAX_RUN_TOOLS 同量级）。 */
        const val MAX_DIRECT_TOOLS = 48

        val EMPTY = McpCatalog(emptyList(), overBudget = false)

        fun build(
            raw: List<McpRawTool>,
            budgetTokens: Int = DEFAULT_BUDGET_TOKENS,
        ): McpCatalog {
            if (raw.isEmpty()) return EMPTY

            // 每台服务器一个短名，按首次出现顺序分配，保证同一服务器的工具前缀一致。
            val serversInOrder = raw.map { it.server }.distinctBy { it.id }
            val usedServerNames = mutableSetOf<String>()
            val serverShortById = mutableMapOf<String, String>()
            serversInOrder.forEachIndexed { index, server ->
                val short = assignServerShortName(server, index, usedServerNames)
                usedServerNames += short
                serverShortById[server.id] = short
            }

            val usedFullNames = mutableSetOf<String>()
            val entries = raw.map { rawTool ->
                val serverShort = serverShortById.getValue(rawTool.server.id)
                var name = "mcp_${serverShort}_${toolNamePart(rawTool.definition.name)}"
                if (name in usedFullNames) {
                    name += "_" + hash4("${rawTool.server.id}\u0000${rawTool.definition.name}")
                }
                usedFullNames += name
                McpToolEntry(
                    shortName = name,
                    server = rawTool.server,
                    definition = rawTool.definition,
                    bearerToken = rawTool.bearerToken,
                )
            }

            val totalTokens = entries.sumOf { entry ->
                estimateTokens(
                    entry.shortName + "\n" +
                        entry.definition.description + "\n" +
                        entry.definition.inputSchemaJson,
                )
            }
            val overBudget = entries.size > MAX_DIRECT_TOOLS || totalTokens > budgetTokens
            return McpCatalog(entries, overBudget)
        }

        /** 服务器短名：显示名清洗为 ASCII 小写字母数字；为空回退 s1/s2；冲突加 id hash。 */
        private fun assignServerShortName(
            server: McpServerSetting,
            index: Int,
            used: Set<String>,
        ): String {
            val cleaned = server.name.lowercase()
                .filter { it in 'a'..'z' || it in '0'..'9' }
                .take(12)
            val base = cleaned.ifBlank { "s${index + 1}" }
            if (base !in used) return base
            return "${base}_${hash4(server.id)}"
        }

        private fun toolNamePart(name: String): String =
            name.replace(Regex("[^A-Za-z0-9_]"), "_")
                .trim('_')
                .take(40)
                .ifBlank { "tool" }

        private fun hash4(seed: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(seed.toByteArray())
                .take(4)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        /** 保守 token 估算：UTF-8 字节数 ÷3，向上取整。 */
        fun estimateTokens(text: String): Int = (text.toByteArray(Charsets.UTF_8).size + 2) / 3
    }
}

/** 工具注解 → 风险。用户按服务器/工具覆盖的开关尚未接线（见返回报告 TODO）。 */
internal fun riskOf(definition: McpToolDefinition): Risk = when {
    definition.readOnlyHint == true -> Risk.READ
    definition.destructiveHint == true -> Risk.EXTERNAL
    else -> Risk.LOCAL
}
