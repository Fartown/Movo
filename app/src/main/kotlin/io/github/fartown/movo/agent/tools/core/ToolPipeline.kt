package io.github.fartown.movo.agent.tools.core

import android.content.Context
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.runtime.AgentRunCancelledException
import io.github.fartown.movo.core.AgentLogger
import io.github.fartown.movo.core.safeLogType
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** 执行前的拒绝类守卫：只能拒绝（返回错误结果），不能放行被其他环节拒绝的调用。 */
internal fun interface ToolGuard {
    fun check(tool: AgentTool, args: ToolArgs, ctx: ToolContext): ToolOutcome?
}

/** 循环用来决定同一批工具调用能否并行。返回 null 表示可以与相邻的只读调用并行。 */
internal interface ToolConcurrencyOracle {
    fun exclusiveResource(call: AgentModelClient.ToolCall): String?
}

/**
 * 统一执行管线：查找工具 → 复核可用性 → 守卫 → 审批（按权限模式）→ 执行 → 结果后处理（敏感度、投影）。
 * 任何一层的失败都转成结构化结果，不打断循环；只有取消会向上抛出。
 */
internal class ToolPipeline(
    private val registry: ToolRegistry,
    private val environment: () -> ToolEnvironment,
    private val appContext: Context,
    private val logger: AgentLogger,
    private val runId: String,
    private val cancelled: () -> Boolean,
    private val interaction: UserInteraction = UserInteraction.NONE,
    private val guards: List<ToolGuard> = emptyList(),
    private val approvalTimeoutMs: Long = DEFAULT_APPROVAL_TIMEOUT_MS,
) : AgentModelClient.ToolExecutor, ToolConcurrencyOracle, AutoCloseable {
    private val loadedDeferred = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var currentEnvironment: ToolEnvironment = environment()
        private set

    /** 每轮开始时刷新环境快照并生成目录。 */
    fun catalog(): JSONArray {
        currentEnvironment = environment()
        return registry.catalog(currentEnvironment, loadedDeferred)
    }

    fun promptSections(): List<PromptSection> = registry.promptSections(currentEnvironment)

    val registryView: ToolRegistry get() = registry

    fun loadedDeferredTools(): Set<String> = loadedDeferred.toSet()

    /** tool_search 加载按需工具；只接受当前可用的按需工具名。 */
    fun loadDeferred(names: Collection<String>): List<String> = names.filter { name ->
        val tool = registry.find(name) ?: return@filter false
        tool.exposure == Exposure.DEFERRED && registry.isAvailable(tool, currentEnvironment) &&
            loadedDeferred.add(name)
    }

    override fun exclusiveResource(call: AgentModelClient.ToolCall): String? {
        val tool = registry.find(call.name) ?: return "unknown"
        val args = runCatching { ToolArgs.parse(call.argumentsJson) }.getOrElse { return "invalid" }
        return when (val concurrency = runCatching { tool.concurrency(args, currentEnvironment) }.getOrNull()) {
            Concurrency.Parallel -> null
            is Concurrency.Exclusive -> concurrency.resource.name
            null -> "invalid"
        }
    }

    override fun stepTitle(toolCall: AgentModelClient.ToolCall): String? {
        val tool = registry.find(toolCall.name) ?: return null
        val args = runCatching { ToolArgs.parse(toolCall.argumentsJson) }.getOrNull() ?: return null
        return runCatching { tool.stepTitle(args, currentEnvironment) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val tool = registry.find(toolCall.name)
            ?: return result(
                toolCall,
                null,
                ToolOutcome.error(
                    ToolErrorCode.UNKNOWN_TOOL,
                    "工具 ${toolCall.name} 不存在或未加载",
                    hint = if (registry.hasDeferred(currentEnvironment, loadedDeferredTools())) {
                        "使用目录中的工具；低频工具先用 tool_search 加载"
                    } else {
                        "只能使用目录中的工具"
                    },
                ),
                Sensitivity.NORMAL,
            )
        val args = try {
            ToolArgs.parse(toolCall.argumentsJson)
        } catch (failure: ToolFailure) {
            return result(toolCall, tool, failure.toOutcome(), Sensitivity.NORMAL)
        }
        val env = currentEnvironment
        val declaredSensitivity = runCatching { tool.sensitivity(args, env) }.getOrDefault(Sensitivity.SECRET)
        val ctx = ToolContext(
            appContext = appContext,
            logger = logger,
            runId = runId,
            toolCallId = toolCall.id,
            env = env,
            interaction = interaction,
            cancelled = cancelled,
        )
        val outcome = try {
            ctx.checkCancelled()
            when (val availability = registry.availability(tool, env)) {
                is ToolAvailability.Unavailable -> ToolOutcome.error(
                    availability.code,
                    availability.reason,
                    hint = LegacyResults.defaultHint(availability.code),
                )
                ToolAvailability.Available -> guards.firstNotNullOfOrNull { guard -> guard.check(tool, args, ctx) }
                    ?: approve(tool, args, ctx)
                    ?: tool.execute(args, ctx)
            }
        } catch (cancelledRun: AgentRunCancelledException) {
            throw cancelledRun
        } catch (failure: ToolFailure) {
            failure.toOutcome()
        } catch (throwable: Exception) {
            if (cancelled()) throw AgentRunCancelledException()
            logger.warn("Tool ${tool.name} failed: type=${throwable.safeLogType()}")
            ToolOutcome.error(
                ToolErrorCode.INTERNAL_ERROR,
                throwable.message?.take(300) ?: throwable.javaClass.simpleName,
                hint = "内部错误，可以重试一次；再次失败就告诉用户",
                detail = throwable.javaClass.simpleName,
            )
        }
        val sensitivity = maxOf(declaredSensitivity, outcome.sensitivity ?: Sensitivity.NORMAL)
        return result(toolCall, tool, outcome, sensitivity)
    }

    /**
     * 返回 null 表示放行；否则返回不执行的结果。工具声明了类别或所在应用的动作才走审批设置：
     * YOLO 直接做，手动审批时高敏和规则命中的弹卡（见 [confirmConsequence]）。
     */
    private fun approve(tool: AgentTool, args: ToolArgs, ctx: ToolContext): ToolOutcome? {
        val need = tool.approval(args, ctx) ?: return null
        val refusal = ctx.confirmConsequence(tool.name, need, approvalTimeoutMs) ?: return null
        return ToolOutcome(status = refusal.code.status, error = refusal)
    }

    private fun result(
        call: AgentModelClient.ToolCall,
        tool: AgentTool?,
        outcome: ToolOutcome,
        sensitivity: Sensitivity,
    ): AgentModelClient.ToolResult = AgentModelClient.ToolResult(
        content = ToolProjection.render(outcome),
        images = outcome.images,
        sensitive = sensitivity != Sensitivity.NORMAL,
        status = outcome.status.wire,
        errorCode = outcome.error?.code?.name,
        outcome = outcome,
        domain = tool?.domain?.name,
    )

    override fun close() {
        registry.close()
    }

    companion object {
        const val DEFAULT_APPROVAL_TIMEOUT_MS = 120_000L

        /** 循环在执行前拒绝的调用（参数无效、输出截断等）也用同一协议。 */
        fun rejected(
            call: AgentModelClient.ToolCall,
            code: ToolErrorCode,
            message: String,
            hint: String? = null,
            sensitive: Boolean = false,
        ): AgentModelClient.ToolResult {
            val received = call.argumentsJson.let { if (it.length <= 500) it else it.take(500) + "…" }
            val outcome = ToolOutcome.error(
                code = code,
                message = message,
                hint = hint,
                data = if (code == ToolErrorCode.INVALID_ARGUMENTS && !sensitive) {
                    JSONObject().put("received", received)
                } else {
                    null
                },
            )
            return AgentModelClient.ToolResult(
                content = ToolProjection.render(outcome),
                sensitive = sensitive,
                status = outcome.status.wire,
                errorCode = code.name,
                outcome = outcome,
            )
        }
    }
}
