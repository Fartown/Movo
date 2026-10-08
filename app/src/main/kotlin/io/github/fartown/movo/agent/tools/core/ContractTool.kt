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
    override val thirdPartySchema: Boolean get() = contract.thirdPartySchema

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
        val r = memoResolution ?: return null
        // 审批前已判定拒绝的调用不弹确认卡；execute() 会短路返回该拒绝。
        if (r.reject != null) return null
        val need = r.consequence() ?: return null
        // 按类别 / 应用派生的确认标题与正文为空，这里按工具补全可读文案。工具自带文案保持不动。
        if (need.title.isNotBlank() && need.detail.isNotBlank()) return need
        val action = io.github.fartown.movo.agent.model.TypedToolLabels.of(name) ?: "这一步"
        val preview = memoInput?.let { runCatching { contract.approvalPreview(it) }.getOrNull() }
        return need.copy(
            title = need.title.ifBlank { preview?.title ?: "允许 Movo「$action」？" },
            detail = need.detail.ifBlank { preview?.detail ?: defaultApprovalDetail(action, args) },
        )
    }

    private companion object {
        /** 各工具最能说明「要做什么」的参数，按优先级。 */
        val PRIMARY_ARG_KEYS = listOf(
            "command", "url", "uri", "path", "file", "package", "name", "key", "tool", "skill", "query", "text", "new_text",
        )
    }

    /**
     * 没有工具自带预览时的兜底正文：写动作和它的主要对象（命令、网址、路径、设置项……）。
     * 不贴原始 JSON，也不列英文参数名。
     */
    private fun defaultApprovalDetail(action: String, args: ToolArgs): String {
        val target = PRIMARY_ARG_KEYS.firstNotNullOfOrNull { key ->
            args.raw.optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }
        }?.let { if (it.length > 120) it.take(120) + "…" else it }
        return if (target == null) action else "$action：$target"
    }

    override fun execute(args: ToolArgs, ctx: ToolContext): ToolOutcome {
        compute(args, ctx.env)
        // 解析/resolve 抛出的结构化错误原样回报（保留 code/hint/detail）。
        memoFailure?.let { return it.toOutcome() }
        val input = memoInput ?: return ToolOutcome.error(ToolErrorCode.INVALID_ARGUMENTS, "参数解析失败")
        val r = memoResolution ?: return ToolOutcome.error(ToolErrorCode.INVALID_ARGUMENTS, "参数解析失败")
        // 审批前策略拒绝短路：黑名单/锁屏等不先弹确认卡再拒（走 approval 前）。
        r.reject?.let { return ToolOutcome(status = it.code.status, error = it) }
        return bridge(contract.execute(input, r, ctx), input, ctx)
    }

    /** 只解析参数、不调后端（执行前就要显示），解析失败时没有标题。 */
    override fun stepTitle(args: ToolArgs, env: ToolEnvironment): String? =
        runCatching { contract.uiTitle(contract.parse(args, env)) }.getOrNull()

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

    private fun bridge(verdict: Verdict<O>, input: I, ctx: ToolContext): ToolOutcome = when (verdict) {
        is Verdict.Read -> ok(verdict.output, verified = null, evidence = null, extra = null, input = input, ctx = ctx)
        is Verdict.Done -> ok(verdict.output, verified = true, evidence = describe(verdict.evidence), extra = null, input = input, ctx = ctx)
        is Verdict.Dispatched -> ok(verdict.output, verified = false, evidence = null, extra = null, input = input, ctx = ctx)
        is Verdict.Backgrounded -> ok(
            verdict.output, verified = false, evidence = "job=${verdict.job.jobId}", input = input, ctx = ctx,
            // job_id 必须进模型投影，否则模型无法后续 terminal_job 查询（review B3）
            extra = JSONObject().put("job_id", verdict.job.jobId).put("running", true).put("reason", verdict.job.reason),
        )
        is Verdict.Unknown -> ToolOutcome.error(
            code = ToolErrorCode.OUTCOME_UNKNOWN, message = verdict.reason, hint = verdict.next,
        )
        is Verdict.Failed -> ToolOutcome(status = verdict.error.code.status, error = verdict.error)
    }.copy(verdict = verdictName(verdict))

    private fun verdictName(verdict: Verdict<O>): String = when (verdict) {
        is Verdict.Read -> "read"
        is Verdict.Done -> "done"
        is Verdict.Dispatched -> "dispatched"
        is Verdict.Backgrounded -> "backgrounded"
        is Verdict.Unknown -> "unknown"
        is Verdict.Failed -> "failed"
    }

    private fun ok(
        output: O,
        verified: Boolean?,
        evidence: String?,
        extra: JSONObject?,
        input: I,
        ctx: ToolContext,
    ): ToolOutcome {
        val content = contract.renderForModel(output)
        val data = (content as? ModelContent.Json)?.obj ?: JSONObject()
        extra?.keys()?.forEach { k -> data.put(k, extra.get(k)) }
        val text = (content as? ModelContent.Text)?.text
        val images = contract.images(output)
        return ToolOutcome.ok(
            data = data, textBody = text, warnings = contract.warnings(output), images = images,
            // 回填解析阶段判定的敏感度：决定结果在历史里是否脱敏。
            sensitivity = memoResolution?.sensitivity,
        ).copy(effectVerified = verified, evidence = evidence, view = uiView(input, output, images, ctx))
    }

    /**
     * 执行卡这一步的视图（工具可视化方案 §5.1）：工具给的视图 + 图片（只存进程内缩略图），按敏感度处理——
     * 机密只留摘要；带图的只在本次运行中显示（§6 决策 1）。个人数据由工具自己把视图标为临时（§6 决策 2），
     * 不按 PRIVATE 一刀切：终端输出、记忆、设置这类重启后仍要能展开。
     */
    private fun uiView(
        input: I,
        output: O,
        images: List<io.github.fartown.movo.agent.model.AgentModelClient.ModelImage>,
        ctx: ToolContext,
    ): ToolUiView? {
        val base = runCatching { contract.renderForUi(input, output) }.getOrNull()
        val sensitivity = memoResolution?.sensitivity ?: Sensitivity.NORMAL
        if (sensitivity == Sensitivity.SECRET) return base?.summaryOnly()?.copy(transient = true)?.bounded()
        val imageKeys = images.mapIndexedNotNull { index, image ->
            runCatching { io.github.fartown.movo.agent.media.ToolStepImages.put(ctx.toolCallId, index, image) }.getOrNull()
        }
        if (base == null && imageKeys.isEmpty()) return null
        val view = base ?: ToolUiView()
        val blocks = if (imageKeys.isEmpty()) view.blocks else listOf(ToolUiBlock.Images(imageKeys)) + view.blocks
        return view.copy(
            blocks = blocks,
            transient = view.transient || imageKeys.isNotEmpty(),
        ).bounded()
    }

    private fun describe(evidence: Evidence): String = when (evidence) {
        is Evidence.ReadBack -> "readback:${evidence.what}"
        is Evidence.AttributedNew -> "attributed:${evidence.what}@${evidence.requestMarker}"
        is Evidence.ContentHash -> "${evidence.algorithm}:${evidence.hash}"
    }
}
