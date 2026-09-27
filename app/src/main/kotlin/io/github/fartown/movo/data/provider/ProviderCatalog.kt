package io.github.fartown.movo.data.provider

import io.github.fartown.movo.data.model.Model
import io.github.fartown.movo.data.model.ProviderSetting
import io.github.fartown.movo.data.model.ProviderSourceTypes

/**
 * 服务商页（设置 → 模型，Figma「15」）的归类规则，纯函数便于单测：
 * - ChatGPT 记录只承载订阅登录后的模型，单独一张卡，不进「我的服务商」「可以添加」；
 * - 「我的服务商」= 自己添加的（含 .env 打包的）+ 填了 API Key 的内置预设；没填 Key 的内置预设只是模板占位；
 * - 「可以添加」= 还没配置过的模板。
 */
internal object ProviderCatalog {
    fun isChatGpt(provider: ProviderSetting): Boolean =
        provider.id == BuiltinProviders.CHATGPT_ID

    fun isPackaged(provider: ProviderSetting): Boolean =
        provider.id == PackagedModelDefaults.PROVIDER_ID

    /** 出现在「我的服务商」：不是 ChatGPT；内置预设须填了 API Key，其余（自己新建的、.env 打包的）都算。 */
    fun isConfigured(provider: ProviderSetting): Boolean =
        !isChatGpt(provider) &&
            (!BuiltinProviders.isPresetId(provider.id) || provider.apiKey.isNotBlank())

    fun configuredProviders(providers: List<ProviderSetting>): List<ProviderSetting> =
        providers.filter(::isConfigured)

    /** 还能添加的模板（按模板顺序）：已配置过的（同 id 且有 Key）不再出现。 */
    fun availableTemplates(providers: List<ProviderSetting>): List<ProviderSetting> {
        val configuredIds = providers.asSequence().filter(::isConfigured).mapTo(mutableSetOf()) { it.id }
        return BuiltinProviders.TEMPLATES.filterNot { it.id in configuredIds }
    }

    /** 能发请求：已启用，且有 API Key 或是已登录的 ChatGPT（与输入框模型菜单的过滤一致）。 */
    fun isUsable(provider: ProviderSetting, chatGptLoggedIn: Boolean): Boolean =
        provider.isEnabled && (
            provider.apiKey.isNotBlank() ||
                (chatGptLoggedIn && ProviderSourceRegistry.resolve(provider) == ProviderSourceTypes.CHATGPT)
            )

    /** 当前选中无效时的替补：可用且有已启用模型的优先，其次任意可用的；都没有返回 null（不选）。 */
    fun fallbackSelection(providers: List<ProviderSetting>, chatGptLoggedIn: Boolean): ProviderSetting? {
        val usable = providers.sortedBy(ProviderSetting::sortOrder).filter { isUsable(it, chatGptLoggedIn) }
        return usable.firstOrNull { provider -> provider.models.any(Model::isEnabled) } ?: usable.firstOrNull()
    }

    /**
     * 「预设改模板」迁移要删的记录：内置预设、不是 ChatGPT、没有 API Key、也不是当前选中的。
     * 有 Key 的预设就是用户自己添加的，保留。
     */
    fun isPresetPlaceholder(provider: ProviderSetting, selectedProviderId: String?): Boolean =
        BuiltinProviders.isPresetId(provider.id) &&
            !isChatGpt(provider) &&
            provider.apiKey.isBlank() &&
            provider.id != selectedProviderId
}
