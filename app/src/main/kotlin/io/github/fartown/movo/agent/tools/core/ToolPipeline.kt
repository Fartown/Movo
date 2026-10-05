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
 * 统一执行管线：查找工具 → 复核可用性 → 守卫 → 审批 → 执行 → 结果后处理（敏感度、污点、投影）。
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
    private val approvalRules: ApprovalRuleStore = ApprovalRuleStore.IN_MEMORY,
    private val guards: List<ToolGuard> = emptyList(),
    private val approvalTimeoutMs: Long = DEFAULT_APPROVAL_TIMEOUT_MS,
) : AgentModelClient.ToolExecutor, ToolConcurrencyOracle, AutoCloseable {
    val taint = TaintTracker()
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
            taint = taint,
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
        if (outcome.status == ToolStatus.OK) {
            runCatching { tool.taintKinds(args, outcome) }.getOrNull()?.forEach { kind -> taint.mark(kind, tool.name) }
        }
        val sensitivity = maxOf(declaredSensitivity, outcome.sensitivity ?: Sensitivity.NORMAL)
        return result(toolCall, tool, outcome, sensitivity)
    }

    /**
     * 「本次任务内，这类操作都允许」记下的范围（实施方案 5.4：同一个工具、同一个目标在本次运行内不再询问）。
     * 管线一次运行一个实例，所以存在这里，任务结束自然失效。
     */
    private val allowedThisTask = ConcurrentHashMap.newKeySet<String>()

    /** 返回 null 表示放行；否则返回拒绝结果。 */
    private fun approve(tool: AgentTool, args: ToolArgs, ctx: ToolContext): ToolOutcome? {
        val need = tool.approval(args, ctx) ?: defaultNeed(tool, args) ?: return null
        val taskKey = if (need.allowTaskScope) "${tool.name}|${need.reason}|${need.taskScope.orEmpty()}" else null
        if (taskKey != null && taskKey in allowedThisTask) return null
        val scopeKey = need.scopeKey
        // 两类污点同时成立时，一直允许的规则失效（实施方案 5.4）。
        if (scopeKey != null && !taint.tainted && approvalRules.isAllowed(tool.name, scopeKey)) return null
        if (!interaction.available) {
            return ToolOutcome.error(
                ToolErrorCode.UNSUPPORTED,
                "这一步需要用户确认，但现在没人能确认（屏幕关着或入口不支持）；本次未执行",
                hint = "在最终回复中说明需要用户确认的动作，不要换方式绕过",
                detail = "no_interactive_surface",
            )
        }
        val rememberLabel = when {
            scopeKey != null -> need.scopeLabel ?: "以后不再询问"
            taskKey != null -> TASK_SCOPE_LABEL
            else -> null
        }
        val decision = interaction.approve(
            ApprovalRequest(
                toolName = tool.name,
                title = need.title,
                detail = need.detail,
                rememberScope = rememberLabel,
                reason = need.reason,
            ),
            approvalTimeoutMs,
        )
        ctx.checkCancelled()
        return when (decision) {
            is ApprovalDecision.Approved -> {
                if (decision.remember) {
                    when {
                        scopeKey != null -> approvalRules.allow(tool.name, scopeKey)
                        taskKey != null -> allowedThisTask += taskKey
                    }
                }
                null
            }
            ApprovalDecision.Declined -> ToolOutcome.error(
                ToolErrorCode.USER_DECLINED,
                "用户拒绝了这一步",
                hint = "不要换方式重试；询问用户下一步怎么做",
            )
            ApprovalDecision.TimedOut -> ToolOutcome.error(
                ToolErrorCode.APPROVAL_TIMEOUT,
                "用户没有确认，本次未执行",
                hint = "结束本轮并说明需要用户确认的动作",
            )
            ApprovalDecision.Unavailable -> ToolOutcome.error(
                ToolErrorCode.UNSUPPORTED,
                "这一步需要用户确认，但当前无法显示确认；本次未执行",
                hint = "在最终回复中说明需要用户确认的动作",
                detail = "no_interactive_surface",
            )
        }
    }

    /** 非合同工具（tool_search、ask_user 之外的直接实现）风险为 EXTERNAL 时的兜底确认。 */
    private fun defaultNeed(tool: AgentTool, args: ToolArgs): ApprovalNeed? {
        if (tool.risk(args, currentEnvironment) != Risk.EXTERNAL) return null
        val action = io.github.fartown.movo.agent.model.TypedToolLabels.of(tool.name) ?: tool.approvalTitle(args)
        return ApprovalNeed(
            reason = ApprovalReason.EXTERNAL_EFFECT,
            title = "允许 Movo「$action」？",
            detail = "$action\n这一步会改动系统，或者做了就撤销不了。",
        )
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

        /** 定稿 16-01 的勾选框文案。 */
        const val TASK_SCOPE_LABEL = "本次任务内，这类操作都允许"

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
