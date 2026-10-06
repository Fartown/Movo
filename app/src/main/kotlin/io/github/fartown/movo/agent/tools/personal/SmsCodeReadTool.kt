package io.github.fartown.movo.agent.tools.personal

import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.uiDuration
import io.github.fartown.movo.agent.tools.core.uiText
import io.github.fartown.movo.agent.tools.core.uiTime
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * 短信验证码正文里的 secret 识别规则。personal_search 用它给通知/短信正文打码，
 * sms_code_read 用它从短信里抽取验证码，确保个人数据检索绕不开本工具的保密。
 */
internal object PersonalSecretPatterns {
    /** 连续 4–8 位数字，前后不接别的数字。 */
    val OTP = Regex("""(?<!\d)(\d{4,8})(?!\d)""")

    /** 验证码语境词：出现这些词时才把邻近的数字视为验证码。 */
    val OTP_CONTEXT = Regex(
        """验证码|校验码|动态码|确认码|一次性密码|verification\s*code|one[- ]time\s*(?:code|password)|\botp\b""",
        RegexOption.IGNORE_CASE,
    )

    /** 把疑似验证码的数字替换成同长度的星号；非验证码语境原样返回。 */
    fun mask(text: String?): String? {
        if (text.isNullOrEmpty() || !OTP_CONTEXT.containsMatchIn(text)) return text
        return OTP.replace(text) { "*".repeat(it.value.length) }
    }

    /**
     * 从一段短信/通知文本里抽取最可能的验证码：出现验证码语境词时，取离语境词最近的 4–8 位数字。
     * 没有语境词或没有合适数字时返回 null。Root 读短信正文与无 Root 读短信通知两条路径共用此纯逻辑。
     */
    fun extractOtp(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        val context = OTP_CONTEXT.find(text) ?: return null
        return OTP.findAll(text)
            .minByOrNull { kotlin.math.abs(it.range.first - context.range.first) }
            ?.groupValues?.get(1)
    }
}

internal data class SmsCodeReadInput(val maxAgeMinutes: Int) : ToolInput

/** 一条验证码记录：只给验证码、发送方、时间，绝不返回正文。 */
internal data class SmsCode(val code: String, val from: String?, val timeMillis: Long)

internal data class SmsCodeReadOutput(val codes: List<SmsCode>) : ToolOutput

/**
 * 可测后端：从最近短信里抽取验证码。真实实现走 Root content query（有 Root），
 * 也可从通知历史里的短信通知抽取（无 Root）；拿不准的无 Root 路径留 TODO。
 */
internal interface SmsCodeBackend {
    /** 当前环境能否读到短信来源（Root 或短信通知）。 */
    fun available(env: ToolEnvironment): Boolean

    /** 返回 [cutoffMillis] 之后的验证码；读不到来源抛异常或返回 null，由工具转错误码。 */
    fun readCodes(cutoffMillis: Long, env: ToolEnvironment): List<SmsCode>?
}

/**
 * sms_code_read（只读，secret）：只抽取最近短信里的 4–8 位验证码、发送方、时间，不返回正文。
 * max_age_minutes 1–60 默认 10。无可用来源时整体 Unavailable（目录层隐藏）。
 */
internal class SmsCodeReadTool(
    private val backend: SmsCodeBackend,
) : ToolContract<SmsCodeReadInput, SmsCodeReadOutput> {
    override val name = "sms_code_read"
    override val domain = ToolDomain.PERSONAL
    override val summary =
        "只提取最近短信里的验证码（4–8 位）、发送方和时间，不返回正文。" +
            "max_age_minutes 1–60，默认 10。没有验证码时返回空数组。"

    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (backend.available(env)) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(
                ToolErrorCode.PERMISSION_REQUIRED,
                "读取短信验证码需要 Root 授权或通知使用权",
            )
        }

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        integer("max_age_minutes", "只看最近多少分钟内的短信，1–60，默认 10", min = 1, max = 60)
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): SmsCodeReadInput =
        SmsCodeReadInput(maxAgeMinutes = args.int("max_age_minutes", DEFAULT_AGE_MIN, 1..MAX_AGE_MIN))

    override fun resolve(input: SmsCodeReadInput, env: ToolEnvironment): CallResolution =
        CallResolution(
            risk = Risk.READ,
            sensitivity = Sensitivity.SECRET,
            resources = emptySet(),
        )

    override fun execute(
        input: SmsCodeReadInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<SmsCodeReadOutput> {
        if (!backend.available(ctx.env)) {
            return Verdict.Failed(
                ToolError(ToolErrorCode.PERMISSION_REQUIRED, "没有可用的短信来源，无法读取验证码"),
            )
        }
        val cutoff = System.currentTimeMillis() - input.maxAgeMinutes * 60_000L
        val codes = runCatching { backend.readCodes(cutoff, ctx.env) }.getOrNull()
            ?: return Verdict.Failed(
                ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "短信来源暂时读不到"),
            )
        return Verdict.Read(SmsCodeReadOutput(codes))
    }

    override fun uiTitle(input: SmsCodeReadInput): String = "读取验证码"

    /** 验证码是机密：只写找到几个和来自谁，不写验证码本身。 */
    override fun renderForUi(input: SmsCodeReadInput, output: SmsCodeReadOutput): ToolUiView = ToolUiView(
        summary = when {
            output.codes.isEmpty() -> "没找到"
            output.codes.size == 1 -> "找到 1 个" + output.codes.single().from?.takeIf { it.isNotBlank() }?.let { " · 来自 $it" }.orEmpty()
            else -> "找到 ${output.codes.size} 个"
        },
        transient = true,
    )

    override fun renderForModel(output: SmsCodeReadOutput): ModelContent {
        val array = JSONArray()
        output.codes.forEach { code ->
            array.put(
                JSONObject()
                    .put("code", code.code)
                    .put("from", code.from ?: JSONObject.NULL)
                    .put("time", isoOf(code.timeMillis)),
            )
        }
        return ModelContent.Json(JSONObject().put("codes", array).put("count", output.codes.size))
    }

    private companion object {
        const val DEFAULT_AGE_MIN = 10
        const val MAX_AGE_MIN = 60
    }
}
