package io.github.fartown.movo.ui.pages.providers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.MovoApp
import io.github.fartown.movo.R
import io.github.fartown.movo.data.model.ProviderSetting
import io.github.fartown.movo.data.model.ProviderSourceTypes
import io.github.fartown.movo.data.model.typeLabel
import io.github.fartown.movo.data.repository.ProviderRepository
import io.github.fartown.movo.data.repository.RuntimeConfigRepository
import io.github.fartown.movo.ui.components.movo.MovoConfirmDialog
import io.github.fartown.movo.ui.components.movo.MovoDivider
import io.github.fartown.movo.ui.components.movo.MovoListPage
import io.github.fartown.movo.ui.components.movo.PressKind
import io.github.fartown.movo.ui.components.movo.RowLeading
import io.github.fartown.movo.ui.components.movo.SettingsRow
import io.github.fartown.movo.ui.components.movo.movoClickable
import io.github.fartown.movo.ui.navigation.AppRoute
import io.github.fartown.movo.ui.navigation.NewProviderType
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text

/**
 * 设置 · 模型（规范 8.7 列表页）：搜索框 → 「新增提供商」卡（OpenAI 兼容 / Anthropic）→ 「已配置」卡（点行进入详情、
 * 长按删除自定义提供商、右侧单选设为当前）。品牌 Logo 20、圆形裁切 + 0.5 描边。
 */
@Composable
internal fun ModelProviderListScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val selectedProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    var searchQuery by remember { mutableStateOf("") }
    var providerToDelete by remember { mutableStateOf<ProviderSetting?>(null) }
    // 对话框退场动画期间仍显示被删除的提供商名称。
    var lastProviderToDelete by remember { mutableStateOf<ProviderSetting?>(null) }
    if (providerToDelete != null) lastProviderToDelete = providerToDelete

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(MovoApp.serviceInstance)
    }

    val filteredProviders = remember(providers, searchQuery) {
        val query = searchQuery.trim()
        providers.filter { provider ->
            query.isBlank() ||
                provider.name.contains(query, ignoreCase = true) ||
                provider.baseUrl.contains(query, ignoreCase = true) ||
                provider.typeLabel.contains(query, ignoreCase = true)
        }
    }

    MovoListPage(title = stringResource(R.string.ui_model_provider_e8c7f5), onBack = onBack) {
        item(key = "search") {
            MovoSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = stringResource(R.string.ui_search_provider_74e049),
            )
        }

        item(key = "create_section") {
            ProviderSection(title = stringResource(R.string.ui_add_new_provider_74df54)) {
                SettingsRow(
                    title = stringResource(R.string.ui_added_openai_compatible_6bd471),
                    subtitle = stringResource(R.string.ui_support_chatgpt_deepseek_kimi_glm_qwen_etc_b31d02),
                    leading = RowLeading.Custom { ProviderBrandIcon(ProviderSourceTypes.OPENAI) },
                    onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.OpenAiCompatible)) },
                )
                SettingsRow(
                    title = stringResource(R.string.ui_new_anthropic_db6098),
                    subtitle = stringResource(R.string.ui_support_anthropic_claude_official_or_compatible_api_de3f80),
                    leading = RowLeading.Custom { ProviderBrandIcon(ProviderSourceTypes.ANTHROPIC) },
                    showDivider = false,
                    onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.Anthropic)) },
                )
            }
        }

        item(key = "list_section") {
            ProviderSection(
                title = pluralStringResource(R.plurals.provider_configured_count, filteredProviders.size, filteredProviders.size),
            ) {
                if (filteredProviders.isEmpty()) {
                    Text(
                        text = if (searchQuery.isBlank()) {
                            stringResource(R.string.provider_empty)
                        } else {
                            stringResource(R.string.provider_no_matches)
                        },
                        style = MovoTypography.labelRegular,
                        color = MovoColors.textSecondary,
                        modifier = Modifier.padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.md),
                    )
                } else {
                    filteredProviders.forEachIndexed { index, provider ->
                        ProviderListItem(
                            provider = provider,
                            isSelected = provider.id == selectedProviderId,
                            showDivider = index != filteredProviders.lastIndex,
                            onOpen = { onNavigate(AppRoute.ModelProviderDetail(provider.id)) },
                            onDelete = if (!provider.isBuiltIn) {
                                { providerToDelete = provider }
                            } else {
                                null
                            },
                            onSelect = {
                                scope.launch {
                                    RuntimeConfigRepository.setSelectedProviderId(provider.id)
                                    RuntimeConfigRepository.syncToRemotePreferences(MovoApp.serviceInstance)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    MovoConfirmDialog(
        show = providerToDelete != null,
        title = stringResource(R.string.ui_remove_provider_9f848f),
        message = stringResource(R.string.provider_delete_summary, lastProviderToDelete?.name.orEmpty()),
        confirmText = stringResource(R.string.ui_delete_3755f5),
        destructive = true,
        onDismissRequest = { providerToDelete = null },
        onConfirm = {
            scope.launch {
                providerToDelete?.let { provider ->
                    ProviderRepository.deleteProvider(provider.id)
                    RuntimeConfigRepository.syncToRemotePreferences(MovoApp.serviceInstance)
                }
                providerToDelete = null
            }
        },
    )
}

/**
 * 提供商行（`icon=true` 行，规范 8.7）：20 Logo → 12 → 名称 / Base URL / 类型 · 模型数 · 内置（/ 已停用）→ 单选按钮。
 * 点行进入详情，长按删除（仅自定义），停用的提供商整体降为 60% 不透明度。
 */
@Composable
private fun ProviderListItem(
    provider: ProviderSetting,
    isSelected: Boolean,
    showDivider: Boolean,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
    onSelect: () -> Unit,
) {
    val opacity = if (provider.isEnabled) 1f else 0.6f
    val selectDescription = if (isSelected) {
        stringResource(R.string.provider_selected)
    } else {
        stringResource(R.string.provider_set_current)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .movoClickable(kind = PressKind.Row, onLongClick = onDelete, onClick = onOpen),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(start = MovoSpacing.lg, top = MovoSpacing.md, bottom = MovoSpacing.md, end = MovoSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(MovoSize.iconMedium).graphicsLayer { alpha = opacity },
                contentAlignment = Alignment.Center,
            ) {
                ProviderIcon(provider)
            }
            Spacer(Modifier.width(MovoSpacing.md))
            Column(modifier = Modifier.weight(1f).graphicsLayer { alpha = opacity }) {
                Text(
                    text = provider.name,
                    style = MovoTypography.bodyStrong,
                    color = MovoColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = provider.baseUrl,
                    style = MovoTypography.labelRegular,
                    color = MovoColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        provider.typeLabel,
                        pluralStringResource(R.plurals.provider_models_count, provider.models.size, provider.models.size),
                        stringResource(R.string.ui_built_in_09ceea).takeIf { provider.isBuiltIn },
                        stringResource(R.string.ui_disabled_0fe5a9).takeIf { !provider.isEnabled },
                    ).joinToString("·"),
                    style = MovoTypography.labelRegular,
                    color = MovoColors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .size(MovoSize.touchTarget)
                    .semantics { contentDescription = selectDescription }
                    .movoClickable(PressKind.Icon, role = Role.RadioButton, onClick = onSelect),
                contentAlignment = Alignment.Center,
            ) {
                MovoRadioMark(selected = isSelected)
            }
        }
        if (showDivider) {
            MovoDivider(modifier = Modifier.align(Alignment.BottomStart), start = 48.dp)
        }
    }
}
