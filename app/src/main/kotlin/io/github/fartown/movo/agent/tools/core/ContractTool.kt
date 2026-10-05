package io.github.fartown.movo.agent.tools.core

import org.json.JSONObject

/**
 * 把类型化合同 [ToolContract] 适配成现有 [AgentTool]，接通注册表与管线（review B1）。
 * 并把 [Verdict] 桥接成 [ToolOutcome]，保住送达/回读的 effect_verified 区别（review B2，投影在 ToolProjection）。
 *
 * - **解析只做一次**：按 (args, env) 记忆 [CallResolution]，审批/风险/敏感度/执行共用同一对象（review A4、合同 §2.3）。
 * - **fail-closed**：解析失败不静默降级成只读/放行，而是按最保守处理（review A3）。
 * - **env 由管线传入**，不再用 ThreadLocal（review A2）。
 * - 并发过渡桥（review I8）：多资源取确定序（按 enum ordinal 最小）退化为单一 Exclusive；多资源原子获取待资源管理器。
 */
internal class ContractTool<I : ToolInput, O : ToolOutput>(
    private val contract: ToolContract<I, O>,
) : AgentTool {
    override val name: String get() = contract.name
    override val domain: ToolDomain get() = contract.domain
    override val description: String get() = contract.summary

    override fun parameters(env: ToolEnvironment): JSONObject = contract.schema(env)

    override fun availability(env: ToolEnvironment): ToolAvailability = contract.availability(env)

    override fun risk(args: ToolArgs, env: ToolEnvironment): Risk {
        compute(args, env)
        // 解析失败或审批前已拒绝的调用都不会产生副作用（execute 直接回报错误）→ READ，避免误触发审批卡。
        if (memoFailure != null || memoResolution?.reject != null) return Risk.READ
        return memoResolution?.risk ?: Risk.EXTERNAL   // 仍可达的空 resolution：fail-closed 最高风险
    }

    override fun sensitivity(args: ToolArgs, env: ToolEnvironment): Sensitivity {
        compute(args, env)
        if (memoFailure != null || memoResolution?.reject != null) return Sensitivity.NORMAL
        return memoResolution?.sensitivity ?: Sensitivity.SECRET
    }

    override fun concurrency(args: ToolArgs, env: ToolEnvironment): Concurrency {
        val res = resolution(args, env)?.resources?.takeIf { it.isNotEmpty() } ?: return Concurrency.Parallel
        // 确定序：按资源 ordinal 取最小，避免 Set 无序导致每次锁不同资源。多资源约束未覆盖（串行基线下可接受）。
        val first = res.minByOrNull { it.resource.ordinal }!!
        return Concurrency.Exclusive(first.resource)
    }

    override fun approval(args: ToolArgs, ctx: ToolContext): ApprovalNeed? {
        compute(args, ctx.env)
        // 解析失败（参数错误等）：execute() 会回报具体错误，不弹确认卡。
        if (memoFailure != null || memoInput == null) return null
        val action = io.github.fartown.movo.agent.model.TypedToolLabels.of(name) ?: "这一步"
        val r = memoResolution ?: return ApprovalNeed(
            ApprovalReason.EXTERNAL_EFFECT,
            title = "允许 Movo「$action」？",
            detail = "$action\nMovo 判断不了这一步的影响，需要你确认。",
        )
        // 审批前已判定拒绝的调用不弹确认卡；execute() 会短路返回该拒绝。
        if (r.reject != null) return null
        val need = r.requiresApproval(ctx.taint.tainted) ?: return null
        val scoped = if (need.taskScope == null) {
            val scope = memoInput?.let { runCatching { contract.approvalScope(it) }.getOrNull() }
            if (scope != null) need.copy(taskScope = scope) else need
        } else {
            need
        }
        // 中央派生的审批（污点 / EXTERNAL）标题与正文为空，这里按工具与原因补全可读文案。工具自带文案保持不动。
        if (scoped.title.isNotBlank() && scoped.detail.isNotBlank()) return scoped
        val preview = memoInput?.let { runCatching { contract.approvalPreview(it) }.getOrNull() }
        return scoped.copy(
            title = scoped.title.ifBlank { preview?.title ?: "允许 Movo「$action」？" },
            detail = scoped.detail.ifBlank {
                // 预览自带两行（做什么 + 为什么）时原样用；只有一行时补上原因。
                preview?.detail?.let { if ('\n' in it) it else "$it\n${reasonSentence(scoped.reason)}" }
                    ?: defaultApprovalDetail(scoped.reason, action, args)
            },
        )
    }

    override fun taintKinds(args: ToolArgs, outcome: ToolOutcome): Set<TaintKind> {
        // 管线在 execute 之后用同一个 args 对象调用，解析结果仍在记忆里。
        val input = memoInput.takeIf { memoArgs === args } ?: return emptySet()
        return runCatching { contract.taintKinds(input) }.getOrDefault(emptySet())
    }

    /** 确认卡灰底块的第二行：为什么要确认（写给用户，不写实现词）。 */
    private fun reasonSentence(reason: ApprovalReason): String = when (reason) {
        ApprovalReason.TAINTED -> "这个任务读过外部内容和你的个人数据，这一步可能把内容发出去。"
        ApprovalReason.EXTERNAL_EFFECT -> "这一步会改动系统，或者做了就撤销不了。"
        ApprovalReason.PROTECTED_APP -> "这一步在你设的受保护应用里。"
        ApprovalReason.DECLARED_EFFECT, ApprovalReason.PAYMENT -> "这一步会发送、提交或付款。"
        ApprovalReason.PERSONAL_DATA -> "要读取你的个人数据。"
    }

    /**
     * 没有工具自带预览时的兜底正文：第一行写动作和它的主要对象（命令、网址、路径、设置项……），
     * 第二行写原因。不贴原始 JSON，也不列英文参数名。
     */
    private fun defaultApprovalDetail(reason: ApprovalReason, action: String, args: ToolArgs): String {
        val target = PRIMARY_ARG_KEYS.firstNotNullOfOrNull { key ->
            args.raw.optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }
        }?.let { if (it.length > 120) it.take(120) + "…" else it }
        val first = if (target == null) action else "$action：$target"
        return "$first\n${reasonSentence(reason)}"
    }

    private companion object {
        /** 各工具最能说明「要做什么」的参数，按优先级。 */
        val PRIMARY_ARG_KEYS = listOf(
            "command", "url", "uri", "path", "file", "package", "name", "key", "tool", "skill", "query", "text", "new_text",
        )
    }

    override fun execute(args: ToolArgs, ctx: ToolContext): ToolOutcome {
        compute(args, ctx.env)
        // 解析/resolve 抛出的结构化错误原样回报（保留 code/hint/detail）。
        memoFailure?.let { return it.toOutcome() }
        val input = memoInput ?: return ToolOutcome.error(ToolErrorCode.INVALID_ARGUMENTS, "参数解析失败")
        val r = memoResolution ?: return ToolOutcome.error(ToolErrorCode.INVALID_ARGUMENTS, "参数解析失败")
        // 审批前策略拒绝短路：黑名单/锁屏等不先弹确认卡再拒（走 approval 前）。
        r.reject?.let { return ToolOutcome(status = it.code.status, error = it) }
        return bridge(contract.execute(input, r, ctx))
    }

    // ---- 单次解析记忆（按引用相等的 args + env 缓存，review A4）----
    private var memoArgs: ToolArgs? = null
    private var memoEnv: ToolEnvironment? = null
    private var memoInput: I? = null
    private var memoResolution: CallResolution? = null
    private var memoFailure: ToolFailure? = null

    private fun compute(args: ToolArgs, env: ToolEnvironment) {
        if (memoArgs === args && memoEnv === env) return
        memoArgs = args; memoEnv = env; memoInput = null; memoResolution = null; memoFailure = null
        try {
            val input = contract.parse(args, env)
            memoInput = input
            memoResolution = contract.resolve(input, env)
        } catch (failure: ToolFailure) {
            memoFailure = failure
        } catch (throwable: Exception) {
            memoFailure = ToolFailure(ToolErrorCode.INVALID_ARGUMENTS, throwable.message ?: "参数解析失败")
        }
    }

    private fun parsed(args: ToolArgs, env: ToolEnvironment): I? { compute(args, env); return memoInput }
    private fun resolution(args: ToolArgs, env: ToolEnvironment): CallResolution? { compute(args, env); return memoResolution }

    private fun bridge(verdict: Verdict<O>): ToolOutcome = when (verdict) {
        is Verdict.Read -> ok(verdict.output, verified = null, evidence = null, extra = null)
        is Verdict.Done -> ok(verdict.output, verified = true, evidence = describe(verdict.evidence), extra = null)
        is Verdict.Dispatched -> ok(verdict.output, verified = false, evidence = null, extra = null)
        is Verdict.Backgrounded -> ok(
            verdict.output, verified = false, evidence = "job=${verdict.job.jobId}",
            // job_id 必须进模型投影，否则模型无法后续 terminal_job 查询（review B3）
            extra = JSONObject().put("job_id", verdict.job.jobId).put("running", true).put("reason", verdict.job.reason),
        )
        is Verdict.Unknown -> ToolOutcome.error(
            code = ToolErrorCode.OUTCOME_UNKNOWN, message = verdict.reason, hint = verdict.next,
        )
        is Verdict.Failed -> ToolOutcome(status = verdict.error.code.status, error = verdict.error)
    }

    private fun ok(output: O, verified: Boolean?, evidence: String?, extra: JSONObject?): ToolOutcome {
        val content = contract.renderForModel(output)
        val data = (content as? ModelContent.Json)?.obj ?: JSONObject()
        extra?.keys()?.forEach { k -> data.put(k, extra.get(k)) }
        val text = (content as? ModelContent.Text)?.text
        return ToolOutcome.ok(
            data = data, textBody = text, warnings = contract.warnings(output), images = contract.images(output),
            // 回填解析阶段判定的敏感度：决定结果在历史里是否脱敏（污点由 taintKinds 单独声明）。
            sensitivity = memoResolution?.sensitivity,
        ).copy(effectVerified = verified, evidence = evidence)
    }

    private fun describe(evidence: Evidence): String = when (evidence) {
        is Evidence.ReadBack -> "readback:${evidence.what}"
        is Evidence.AttributedNew -> "attributed:${evidence.what}@${evidence.requestMarker}"
        is Evidence.ContentHash -> "${evidence.algorithm}:${evidence.hash}"
    }
}
