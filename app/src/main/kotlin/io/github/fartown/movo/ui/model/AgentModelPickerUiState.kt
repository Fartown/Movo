package io.github.fartown.movo.ui.model

import androidx.compose.runtime.Immutable
import io.github.fartown.movo.data.model.Model
import io.github.fartown.movo.data.model.ProviderSetting
import io.github.fartown.movo.data.model.ProviderSourceTypes
import io.github.fartown.movo.data.provider.ProviderSourceRegistry
import java.text.NumberFormat
import java.util.Locale

@Immutable
internal data class AgentModelPickerUiState(
    val providerGroups: List<AgentModelProviderGroupUi> = emptyList(),
    val selectedModel: AgentModelOptionUi? = null,
    val isChanging: Boolean = false,
    /** 已按模型设置算过一次；首次加载前为 false，避免「未配置模型」提示在启动时闪一下。 */
    val loaded: Boolean = false,
) {
    /** 没有任何可用模型（没有已启用、带凭据、含已启用模型的服务商）：输入框上方提示去配置（7.3），模型按钮置灰。 */
    val missingModel: Boolean
        get() = loaded && providerGroups.isEmpty()
}

@Immutable
internal data class AgentModelProviderGroupUi(
    val providerId: String,
    val providerName: String,
    val providerSourceType: String,
    val models: List<AgentModelOptionUi>,
)

@Immutable
internal data class AgentModelOptionUi(
    val id: String,
    val providerId: String,
    val providerName: String,
    val providerSourceType: String,
    val modelId: String,
    val displayName: String,
    val contextWindow: Int?,
)

@Immutable
internal data class AgentContextUsageUi(
    val contextTokens: Int?,
    val contextWindow: Int?,
    val estimated: Boolean = false,
) {
    val progress: Float?
        get() = contextUsageProgress(contextTokens, contextWindow)
}

internal object AgentModelPickerProjector {
    fun project(
        providers: List<ProviderSetting>,
        selectedProviderId: String?,
        selectedModelId: String?,
        chatGptLoggedIn: Boolean = false,
    ): AgentModelPickerUiState {
        val enabledProviders = providers
            .asSequence()
            .filter(ProviderSetting::isEnabled)
            .sortedBy(ProviderSetting::sortOrder)
            .toList()
        val selectedProvider = enabledProviders.firstOrNull { it.id == selectedProviderId }
        val selectedModel = selectedProvider
            ?.models
            ?.firstOrNull { it.id == selectedModelId && it.isEnabled }
            ?.let { model -> selectedProvider.toOption(model) }
            ?: enabledProviders.asSequence()
                .flatMap { provider ->
                    provider.models.asSequence()
                        .filter { it.isEnabled }
                        .map { model -> provider.toOption(model) }
                }
                .firstOrNull { it.id == selectedModelId }
        val groups = enabledProviders
            .asSequence()
            .filter { provider ->
                provider.apiKey.isNotBlank() ||
                    (chatGptLoggedIn && ProviderSourceRegistry.resolve(provider) == ProviderSourceTypes.CHATGPT)
            }
            .mapNotNull { provider ->
                val sourceType = ProviderSourceRegistry.resolve(provider)
                val models = provider.models
                    .asSequence()
                    .filter { it.isEnabled }
                    .sortedBy { it.sortOrder }
                    .map { model -> provider.toOption(model) }
                    .toList()
                models.takeIf(List<*>::isNotEmpty)?.let {
                    AgentModelProviderGroupUi(
                        providerId = provider.id,
                        providerName = provider.name,
                        providerSourceType = sourceType,
                        models = models,
                    )
                }
            }
            .toList()
        return AgentModelPickerUiState(
            providerGroups = groups,
            selectedModel = selectedModel,
            loaded = true,
        )
    }

    private fun ProviderSetting.toOption(model: Model): AgentModelOptionUi =
        AgentModelOptionUi(
            id = model.id,
            providerId = id,
            providerName = name,
            providerSourceType = ProviderSourceRegistry.resolve(this),
            modelId = model.modelId,
            displayName = model.displayName.ifBlank { model.modelId },
            contextWindow = model.effectiveContextWindow,
        )
}

internal fun defaultExpandedModelProviderIds(selectedModel: AgentModelOptionUi?): Set<String> =
    selectedModel?.providerId?.let(::setOf).orEmpty()

internal fun latestContextUsage(
    messages: List<AgentChatMessageUi>,
    selectedModel: AgentModelOptionUi?,
): AgentContextUsageUi {
    val lastUsage = messages.asReversed().asSequence().mapNotNull { message ->
        when (message) {
            // 用量按最近一次请求的输入 token 计：这就是发给模型的上下文大小。total_tokens 还含输出（推理），
            // 推理不会带进下一轮，按它算会在下一轮无故回落。没有输入数时退回 total。
            is AgentMessageUi -> (message.usage?.inputTokens ?: message.usage?.contextTokens)?.let { it to false }
            is SystemNoticeMessageUi -> message.contextTokens?.let { it to true }
            else -> null
        }
    }.firstOrNull()
    return AgentContextUsageUi(lastUsage?.first, selectedModel?.contextWindow, lastUsage?.second ?: false)
}

internal fun contextUsageProgress(contextTokens: Int?, contextWindow: Int?): Float? {
    if (contextTokens == null || contextTokens < 0 || contextWindow == null || contextWindow <= 0) {
        return null
    }
    return (contextTokens.toFloat() / contextWindow.toFloat()).coerceIn(0f, 1f)
}

internal fun formatContextUsage(
    usage: AgentContextUsageUi,
    noUsageText: String = "No usage data yet",
    noLimitText: String = "This model has no context limit",
    locale: Locale = Locale.getDefault(),
): String = when {
    usage.contextTokens == null -> noUsageText
    usage.contextWindow == null || usage.contextWindow <= 0 ->
        "${formatCompactTokenCount(usage.contextTokens, locale)} tokens\n$noLimitText"
    else -> {
        val percent = usage.contextTokens.toDouble() / usage.contextWindow.toDouble() * 100.0
        val percentFormat = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        "${formatCompactTokenCount(usage.contextTokens, locale)} / " +
            "${formatCompactTokenCount(usage.contextWindow, locale)} tokens · " +
            "${percentFormat.format(percent)}%"
    }
}

internal fun formatCompactTokenCount(value: Int, locale: Locale = Locale.getDefault()): String {
    val absolute = kotlin.math.abs(value.toLong())
    val divisor = when {
        absolute >= 1_000_000 -> 1_000_000.0
        absolute >= 1_000 -> 1_000.0
        else -> return NumberFormat.getIntegerInstance(locale).format(value)
    }
    val suffix = if (divisor == 1_000_000.0) "M" else "K"
    val formatted = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
        isGroupingUsed = false
    }.format(value / divisor)
    return "$formatted$suffix"
}

/**
 * 上下文用量浮层里的 token 数（规范 `Popover/ContextUsage`）：1 万以上取整（19K、865K），百万保留最多两位（1.05M），
 * 1 千到 1 万保留一位（8.5K），不写 tokens。取整后到 1000K 的直接进位成 M。
 */
internal fun formatContextTokenCount(value: Int, locale: Locale = Locale.getDefault()): String {
    val absolute = kotlin.math.abs(value.toLong())
    val (divisor, suffix, fractionDigits) = when {
        absolute >= 999_500 -> Triple(1_000_000.0, "M", 2)
        absolute >= 10_000 -> Triple(1_000.0, "K", 0)
        absolute >= 1_000 -> Triple(1_000.0, "K", 1)
        else -> return NumberFormat.getIntegerInstance(locale).format(value)
    }
    val formatted = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = fractionDigits
        isGroupingUsed = false
    }.format(value / divisor)
    return "$formatted$suffix"
}

/** 上下文占用百分比，一位小数（1.8%）；超出窗口时如实显示。没有用量或模型没给上限时为 null。 */
internal fun formatContextUsagePercent(usage: AgentContextUsageUi, locale: Locale = Locale.getDefault()): String? {
    val tokens = usage.contextTokens ?: return null
    val window = usage.contextWindow?.takeIf { it > 0 } ?: return null
    val percentFormat = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    return "${percentFormat.format(tokens.toDouble() / window.toDouble() * 100.0)}%"
}
