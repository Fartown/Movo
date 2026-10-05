package io.github.fartown.movo.agent.tools.personal

import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolAvailability
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolContract
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolError
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.objectSchema
import io.github.fartown.movo.agent.tools.core.TaintKind
import org.json.JSONArray
import org.json.JSONObject

internal data class WifiPasswordReadInput(val ssid: String?, val limit: Int) : ToolInput

/** 一条 Wi‑Fi 凭据。开放网络、企业网络（EAP）没有预共享密钥，[password] 为 null。 */
internal data class WifiNetwork(val ssid: String, val password: String?)

internal data class WifiPasswordReadOutput(val items: List<WifiNetwork>) : ToolOutput

/** 可测后端：读已保存的 Wi‑Fi 凭据（需 Root 读 WifiConfigStore.xml）。null 表示读不到。 */
internal interface WifiPasswordReadBackend {
    fun read(ssidFilter: String?, limit: Int): List<WifiNetwork>?
}

/**
 * wifi_password_read（只读，secret，需 Root）：读已保存的 Wi‑Fi 密码，可按 SSID 过滤。
 * 无 Root 时目录层隐藏（ROOT_REQUIRED）；开放/企业网络 password=null。
 */
internal class WifiPasswordReadTool(
    private val backend: WifiPasswordReadBackend,
) : ToolContract<WifiPasswordReadInput, WifiPasswordReadOutput> {
    override val name = "wifi_password_read"
    override val domain = ToolDomain.PERSONAL
    override val summary =
        "读已保存的 Wi‑Fi 密码（需 Root）。问“Wi‑Fi/无线网/wifi 密码”就用它，不是 device_read。" +
            "可选 ssid 过滤；limit 默认 20。开放或企业网络没有密码，password 为 null。"

    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.rootAvailable) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(
                ToolErrorCode.ROOT_REQUIRED,
                "读取已保存的 Wi‑Fi 密码需要 Root 授权",
            )
        }

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("ssid", "只看某个 SSID（可选）")
        integer("limit", "返回条数，1–50，默认 20", min = 1, max = MAX_LIMIT.toLong())
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): WifiPasswordReadInput =
        WifiPasswordReadInput(
            ssid = args.stringOrNull("ssid")?.trim()?.trim('"')?.takeIf { it.isNotEmpty() },
            limit = args.int("limit", DEFAULT_LIMIT, 1..MAX_LIMIT),
        )

    override fun resolve(input: WifiPasswordReadInput, env: ToolEnvironment): CallResolution =
        CallResolution(risk = Risk.READ, sensitivity = Sensitivity.SECRET, resources = emptySet())

    override fun execute(
        input: WifiPasswordReadInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<WifiPasswordReadOutput> {
        if (!ctx.env.rootAvailable) {
            return Verdict.Failed(ToolError(ToolErrorCode.ROOT_REQUIRED, "读取 Wi‑Fi 密码需要 Root 授权，本次未执行"))
        }
        ctx.checkCancelled()
        val items = runCatching { backend.read(input.ssid, input.limit) }.getOrNull()
            ?: return Verdict.Failed(ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "读不到已保存的 Wi‑Fi 配置"))
        return Verdict.Read(WifiPasswordReadOutput(items))
    }

    override fun taintKinds(input: WifiPasswordReadInput): Set<TaintKind> = setOf(TaintKind.PERSONAL)

    override fun renderForModel(output: WifiPasswordReadOutput): ModelContent {
        val array = JSONArray()
        output.items.forEach { network ->
            array.put(
                JSONObject()
                    .put("ssid", network.ssid)
                    .put("password", network.password ?: JSONObject.NULL),
            )
        }
        return ModelContent.Json(JSONObject().put("items", array).put("count", output.items.size))
    }

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
    }
}
