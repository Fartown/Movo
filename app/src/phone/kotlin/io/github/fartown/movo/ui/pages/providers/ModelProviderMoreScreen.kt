package io.github.fartown.movo.ui.pages.providers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import io.github.fartown.movo.data.provider.ProviderCatalog
import io.github.fartown.movo.data.repository.ProviderRepository
import io.github.fartown.movo.ui.components.movo.CardTitle
import io.github.fartown.movo.ui.components.movo.MovoCard
import io.github.fartown.movo.ui.components.movo.MovoListPage
import io.github.fartown.movo.ui.components.movo.movoAnimateItem
import io.github.fartown.movo.ui.navigation.AppRoute
import io.github.fartown.movo.ui.navigation.NewProviderType
import io.github.fartown.movo.ui.theme.MovoIcons

/**
 * 设置 · 模型 · 更多服务商（Figma「15-4」）：「常用服务商」卡列出全部还没添加的模板（点行进模板详情，填 API Key 即可使用）；
 * 「自定义接口」卡新建 OpenAI / Anthropic 兼容接口（填写 Base URL、API Key 和模型）。
 */
@Composable
internal fun ModelProviderMoreScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val loadedProviders by remember { ProviderRepository.providersFlow() }.collectAsState(initial = null)
    val providers = loadedProviders.orEmpty()
    val templates = remember(providers) { ProviderCatalog.availableTemplates(providers) }

    MovoListPage(title = stringResource(R.string.provider_more_title), onBack = onBack) {
        // 读到之前不先把全部模板闪出来（已添加的随后又消失）。
        if (loadedProviders != null && templates.isNotEmpty()) {
            item(key = "common") {
                MovoCard(modifier = movoAnimateItem()) {
                    CardTitle(
                        text = stringResource(R.string.provider_common_title),
                        trailing = stringResource(R.string.provider_common_hint),
                    )
                    templates.forEachIndexed { index, template ->
                        key(template.id) {
                            TemplateProviderRow(
                                template = template,
                                showDivider = index != templates.lastIndex,
                                onOpen = { onNavigate(templateRoute(template.id, providers)) },
                            )
                        }
                    }
                }
            }
        }

        item(key = "custom") {
            MovoCard(modifier = movoAnimateItem()) {
                CardTitle(text = stringResource(R.string.provider_custom_title))
                ProviderPageRow(
                    title = stringResource(R.string.provider_custom_openai),
                    subtitle = stringResource(R.string.provider_custom_summary),
                    leading = { ProviderIconTile(MovoIcons.Plug, size = 28.dp) },
                    showDivider = true,
                    onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.OpenAiCompatible)) },
                )
                ProviderPageRow(
                    title = stringResource(R.string.provider_custom_anthropic),
                    subtitle = stringResource(R.string.provider_custom_summary),
                    leading = { ProviderIconTile(MovoIcons.CodeXml, size = 28.dp) },
                    showDivider = false,
                    onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.Anthropic)) },
                )
            }
        }
    }
}
