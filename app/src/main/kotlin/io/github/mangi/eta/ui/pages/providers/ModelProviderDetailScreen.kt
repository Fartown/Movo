@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package io.github.mangi.eta.ui.pages.providers

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.withId
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RemoteModelFetcher
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.movo.CardFooter
import io.github.mangi.eta.ui.components.movo.MovoBlockButton
import io.github.mangi.eta.ui.components.movo.MovoCard
import io.github.mangi.eta.ui.components.movo.MovoChoiceDialog
import io.github.mangi.eta.ui.components.movo.MovoConfirmDialog
import io.github.mangi.eta.ui.components.movo.MovoDivider
import io.github.mangi.eta.ui.components.movo.MovoPage
import io.github.mangi.eta.ui.components.movo.MovoPillButton
import io.github.mangi.eta.ui.components.movo.RowLeading
import io.github.mangi.eta.ui.components.movo.RowTrailing
import io.github.mangi.eta.ui.components.movo.SettingsRow
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.navigation.NewProviderType
import io.github.mangi.eta.ui.theme.LocalReducedMotion
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoIcon
import io.github.mangi.eta.ui.theme.MovoIcons
import io.github.mangi.eta.ui.theme.MovoMotion
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoSpacing
import io.github.mangi.eta.ui.theme.MovoTypography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 提供商详情 / 新建（规范 8.7 二级页）：居中顶栏标题为提供商名称；已有提供商在顶栏下方用分段切换「配置 / 模型」
 * （9.3.1「分段 / 标签」，下方内容交叉淡化 `fast`）；新建时只有配置。
 */
@Composable
internal fun ModelProviderDetailScreen(
    providerId: String? = null,
    newType: NewProviderType? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    var createdId by remember { mutableStateOf<String?>(null) }
    val effectiveId = providerId ?: createdId
    val provider = remember(providers, effectiveId) {
        effectiveId?.let { id -> providers.firstOrNull { it.id == id } }
    }
    val draft = remember(newType) {
        when (newType) {
            NewProviderType.OpenAiCompatible -> CustomProviderSetting(
                id = "",
                name = "",
                baseUrl = "",
                endpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
            )
            NewProviderType.Anthropic -> AnthropicProviderSetting(
                id = "",
                name = "",
                baseUrl = "https://api.anthropic.com",
            )
            null -> null
        }
    }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    if (provider == null && draft == null) {
        MovoPage(
            title = stringResource(R.string.route_provider_details),
            onBack = onBack,
        ) { contentPadding, sidePadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(horizontal = sidePadding + MovoSpacing.pageEdge, vertical = MovoSpacing.xxl),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.ui_provider_does_not_exist_83cee6),
                    style = MovoTypography.bodyRegular,
                    color = MovoColors.textSecondary,
                )
                Spacer(modifier = Modifier.height(MovoSpacing.md))
                MovoPillButton(label = stringResource(R.string.ui_return_11d024), onClick = onBack)
            }
        }
        return
    }

    val initial = provider ?: draft!!
    val isNew = provider == null
    var currentTab by remember { mutableIntStateOf(0) }
    var configDraft by rememberSaveable(
        initial.id,
        stateSaver = ProviderConfigDraftSaver,
    ) {
        mutableStateOf(ProviderConfigDraft.from(initial))
    }
    val title = if (isNew) context.getString(R.string.page_create_new_provider_36cab9) else initial.name
    val reduced = LocalReducedMotion.current

    MovoPage(title = title, onBack = onBack) { contentPadding, sidePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .padding(top = contentPadding.calculateTopPadding()),
        ) {
            if (!isNew) {
                MovoSegmentedTabs(
                    tabs = listOf(context.getString(R.string.page_configuration_d7d7ce), context.getString(R.string.page_model_98fd0c)),
                    selectedIndex = currentTab,
                    onSelect = { currentTab = it },
                    modifier = Modifier.padding(
                        start = sidePadding + MovoSpacing.pageEdge,
                        end = sidePadding + MovoSpacing.pageEdge,
                        top = MovoSpacing.md,
                    ),
                )
            }
            Crossfade(
                targetState = currentTab,
                animationSpec = if (reduced) snap() else MovoMotion.fast(),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "providerTab",
            ) { tab ->
                when (tab) {
                    0 -> ProviderConfigTab(
                        provider = initial,
                        draft = configDraft,
                        onDraftChange = { configDraft = it },
                        scope = scope,
                        isNew = isNew,
                        contentSidePadding = sidePadding,
                        onCreated = { id -> createdId = id },
                        onDeleted = onBack,
                    )
                    1 -> if (!isNew) {
                        ProviderModelsTab(
                            provider = initial,
                            scope = scope,
                            contentSidePadding = sidePadding,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderConfigTab(
    provider: ProviderSetting,
    draft: ProviderConfigDraft,
    onDraftChange: (ProviderConfigDraft) -> Unit,
    scope: CoroutineScope,
    isNew: Boolean,
    contentSidePadding: Dp,
    onCreated: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    var headersExpanded by rememberSaveable { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showEndpointDialog by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var creationCommitted by remember { mutableStateOf(false) }
    val isChatGpt = ProviderSourceRegistry.resolve(provider) == ProviderSourceTypes.CHATGPT
    val failPrefix = stringResource(R.string.page_fail_3e3c80)
    val navigation = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val endpointOptions = listOf("Chat Completions API", "Responses API")
    val endpointIndex = if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) 1 else 0

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            // MovoPage 只负责把顶栏 Insets 传给调用方，输入法 Insets 由列表自行消费。
            .imePadding()
            .scrollEndHaptic()
            .overScrollVertical(),
        contentPadding = PaddingValues(
            start = contentSidePadding + MovoSpacing.pageEdge,
            end = contentSidePadding + MovoSpacing.pageEdge,
            top = MovoSpacing.md,
            bottom = navigation + MovoSpacing.section,
        ),
        verticalArrangement = Arrangement.spacedBy(MovoSpacing.lg),
        overscrollEffect = null,
    ) {
        item(key = "connection") {
            ProviderSection(title = stringResource(R.string.ui_connection_configuration_7d057b)) {
                Column(
                    modifier = Modifier.padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(MovoSpacing.md),
                ) {
                    TextField(
                        value = draft.name,
                        onValueChange = { onDraftChange(draft.copy(name = it)) },
                        label = stringResource(R.string.ui_name_1be7ae),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = draft.baseUrl,
                        onValueChange = { onDraftChange(draft.copy(baseUrl = it)) },
                        label = "Base URL",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!isChatGpt) {
                        TextField(
                            value = draft.apiKey,
                            onValueChange = { onDraftChange(draft.copy(apiKey = it)) },
                            label = "API Key",
                            singleLine = true,
                            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                // Lucide 没有 eye-off 的生成数据，显隐切换暂用 Material 图标（见 restyle-C.md）。
                                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                    Icon(
                                        imageVector = if (apiKeyVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                        contentDescription = if (apiKeyVisible) context.getString(R.string.page_hide_bb0e7e) else context.getString(R.string.page_show_71b677),
                                        tint = MovoColors.textSecondary,
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (provider is AnthropicProviderSetting) {
                        TextField(
                            value = draft.anthropicVersion,
                            onValueChange = { onDraftChange(draft.copy(anthropicVersion = it)) },
                            label = "anthropic-version",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                MovoDivider(start = MovoSpacing.lg)
                if (isChatGpt) {
                    ChatGptAccountSection(scope = scope, onStatus = { testStatus = it })
                }
                if (provider !is AnthropicProviderSetting) {
                    if (!isChatGpt) {
                        SettingsRow(
                            title = stringResource(R.string.ui_endpoint_mode_3c8546),
                            subtitle = if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) {
                                context.getString(R.string.page_using_typed_items_with_semantic_streaming_events_f9c906)
                            } else {
                                context.getString(R.string.page_use_standard_chat_completions_ee4b1a)
                            },
                            trailing = RowTrailing.Arrow(endpointOptions[endpointIndex].removeSuffix(" API")),
                            onClick = { showEndpointDialog = true },
                        )
                    }
                    if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) {
                        SettingsRow(
                            title = stringResource(R.string.ui_server_side_web_search_ddb8e0),
                            subtitle = stringResource(R.string.ui_allows_the_model_to_call_web_searches_provided_by_th_2f752f),
                            trailing = RowTrailing.Switch(draft.hostedWebSearchEnabled) {
                                onDraftChange(draft.copy(hostedWebSearchEnabled = it))
                            },
                        )
                    }
                }
                SettingsRow(
                    title = stringResource(R.string.ui_test_connection_10b7d8),
                    trailing = RowTrailing.None,
                    enabled = !isWorking,
                    showDivider = testStatus != null,
                    onClick = {
                        val validationError = validateProviderDraft(context, draft)
                        if (validationError != null) {
                            testStatus = context.getString(R.string.provider_error, validationError)
                            return@SettingsRow
                        }
                        scope.launch {
                            isWorking = true
                            testStatus = context.getString(R.string.page_testing_f43705)
                            try {
                                testStatus = testConnection(
                                    context,
                                    buildUpdatedProvider(
                                        source = provider,
                                        name = draft.name,
                                        baseUrl = draft.baseUrl,
                                        apiKey = draft.apiKey,
                                        systemPrompt = draft.systemPrompt,
                                        isEnabled = draft.isEnabled,
                                        endpointMode = draft.endpointMode,
                                        hostedWebSearchEnabled = draft.hostedWebSearchEnabled,
                                        anthropicVersion = draft.anthropicVersion,
                                        customHeaders = draft.headers.map { it.header },
                                    )
                                )
                            } finally {
                                isWorking = false
                            }
                        }
                    },
                )
                testStatus?.let { message ->
                    ProviderStatusLine(
                        message = message,
                        isError = message.startsWith(failPrefix),
                        modifier = Modifier.padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.md),
                    )
                }
            }
        }

        providerHeadersEditor(
            headers = draft.headers,
            expanded = headersExpanded,
            onExpandedChange = { headersExpanded = it },
            onHeadersChange = { onDraftChange(draft.copy(headers = it)) },
        )

        item(key = "preferences_and_prompt") {
            ProviderSection(title = stringResource(R.string.ui_preferences_and_strategies_2abd3c)) {
                SettingsRow(
                    title = stringResource(R.string.ui_enable_this_provider_683a76),
                    trailing = RowTrailing.Switch(draft.isEnabled) { onDraftChange(draft.copy(isEnabled = it)) },
                )
                TextField(
                    value = draft.systemPrompt,
                    onValueChange = { onDraftChange(draft.copy(systemPrompt = it)) },
                    label = stringResource(R.string.ui_system_prompt_word_193981),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.md)
                        .height(120.dp),
                    singleLine = false,
                )
                CardFooter(listOf(stringResource(R.string.ui_leave_blank_to_use_the_default_mobile_agent_prompt_w_21e7c8)))
            }
        }

        item(key = "actions") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MovoSpacing.md),
            ) {
                MovoBlockButton(
                    label = when {
                        isWorking -> context.getString(R.string.page_saving_d70d42)
                        creationCommitted -> context.getString(R.string.page_created_62cfc5)
                        isNew -> context.getString(R.string.page_create_fcbd09)
                        else -> context.getString(R.string.page_save_configuration_817af1)
                    },
                    enabled = !isWorking && !creationCommitted,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val validationError = validateProviderDraft(context, draft)
                        if (validationError != null) {
                            status = context.getString(R.string.provider_error, validationError)
                            return@MovoBlockButton
                        }
                        scope.launch {
                            isWorking = true
                            val built = buildUpdatedProvider(
                                source = provider,
                                name = draft.name,
                                baseUrl = draft.baseUrl,
                                apiKey = draft.apiKey,
                                systemPrompt = draft.systemPrompt,
                                isEnabled = draft.isEnabled,
                                endpointMode = draft.endpointMode,
                                hostedWebSearchEnabled = draft.hostedWebSearchEnabled,
                                anthropicVersion = draft.anthropicVersion,
                                customHeaders = draft.headers.map { it.header },
                            )
                            try {
                                if (isNew) {
                                    val added = ProviderRepository.addProvider(
                                        built.withId(ProviderRepository.newId())
                                    )
                                    if (added.isEnabled) {
                                        RuntimeConfigRepository.setSelectedProviderId(added.id)
                                    }
                                    RuntimeConfigRepository.syncToRemotePreferences(
                                        EtaApp.serviceInstance
                                    )
                                    status = context.getString(R.string.capability_provider_created)
                                    creationCommitted = true
                                    onCreated(added.id)
                                } else {
                                    ProviderRepository.updateProvider(built)
                                    if (built.isEnabled) {
                                        RuntimeConfigRepository.setSelectedProviderId(built.id)
                                    }
                                    RuntimeConfigRepository.syncToRemotePreferences(
                                        EtaApp.serviceInstance
                                    )
                                    status = when {
                                        !built.isEnabled -> context.getString(R.string.page_saved_provider_not_enabled_7afa54)
                                        else -> context.getString(R.string.capability_provider_saved)
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (throwable: Throwable) {
                                status = context.getString(
                                    R.string.provider_error,
                                    throwable.message ?: context.getString(R.string.provider_save_failed),
                                )
                            } finally {
                                isWorking = false
                            }
                        }
                    },
                )
                status?.let { message ->
                    ProviderStatusLine(
                        message = message,
                        isError = message.startsWith(failPrefix),
                        modifier = Modifier.padding(horizontal = MovoSpacing.lg),
                    )
                }
            }
        }

        if (!isNew) {
            item(key = "danger_zone") {
                MovoCard {
                    SettingsRow(
                        title = if (provider.isBuiltIn) {
                            context.getString(R.string.page_reset_built_in_configuration_35b6ec)
                        } else {
                            context.getString(R.string.page_remove_provider_9f848f)
                        },
                        leading = RowLeading.Custom {
                            MovoIcon(
                                if (provider.isBuiltIn) MovoIcons.RotateCcw else MovoIcons.Trash2,
                                contentDescription = null,
                                size = MovoSize.iconMedium,
                                tint = MovoColors.roseFg,
                            )
                        },
                        trailing = RowTrailing.None,
                        enabled = !isWorking,
                        showDivider = false,
                        onClick = {
                            if (provider.isBuiltIn) showResetDialog = true else showDeleteDialog = true
                        },
                    )
                }
            }
        }
    }

    MovoChoiceDialog(
        show = showEndpointDialog,
        title = stringResource(R.string.ui_endpoint_mode_3c8546),
        options = endpointOptions,
        selectedIndex = endpointIndex,
        onSelect = { selectedIndex ->
            onDraftChange(
                draft.copy(
                    endpointMode = if (selectedIndex == 1) {
                        OpenAiEndpointMode.RESPONSES
                    } else {
                        OpenAiEndpointMode.CHAT_COMPLETIONS
                    },
                ),
            )
        },
        onDismissRequest = { showEndpointDialog = false },
    )

    MovoConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.ui_remove_provider_9f848f),
        message = stringResource(R.string.provider_delete_summary, provider.name),
        confirmText = if (isWorking) context.getString(R.string.page_deleting_6f941d) else context.getString(R.string.page_delete_3755f5),
        cancelEnabled = !isWorking,
        confirmEnabled = !isWorking,
        destructive = true,
        onDismissRequest = { if (!isWorking) showDeleteDialog = false },
        onConfirm = {
            scope.launch {
                isWorking = true
                try {
                    ProviderRepository.deleteProvider(provider.id)
                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                    showDeleteDialog = false
                    onDeleted()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    status = context.getString(
                        R.string.provider_error,
                        throwable.message ?: context.getString(R.string.provider_delete_failed),
                    )
                    showDeleteDialog = false
                } finally {
                    isWorking = false
                }
            }
        },
    )

    MovoConfirmDialog(
        show = showResetDialog,
        title = stringResource(R.string.ui_reset_built_in_configuration_35b6ec),
        message = stringResource(R.string.provider_reset_summary, provider.name),
        confirmText = if (isWorking) context.getString(R.string.page_resetting_616090) else context.getString(R.string.page_reset_3d8134),
        cancelEnabled = !isWorking,
        confirmEnabled = !isWorking,
        onDismissRequest = { if (!isWorking) showResetDialog = false },
        onConfirm = {
            scope.launch {
                isWorking = true
                try {
                    ProviderRepository.resetBuiltIn(provider.id)
                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                    status = context.getString(R.string.page_reset_a0cc65)
                    showResetDialog = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    status = context.getString(
                        R.string.provider_error,
                        throwable.message ?: context.getString(R.string.provider_reset_failed),
                    )
                    showResetDialog = false
                } finally {
                    isWorking = false
                }
            }
        },
    )
}

private suspend fun testConnection(
    context: android.content.Context,
    provider: ProviderSetting,
): String =
    RemoteModelFetcher.fetch(provider)
        .map { context.resources.getQuantityString(R.plurals.provider_models_fetched, it.size, it.size) }
        .getOrElse { throwable ->
            context.getString(
                R.string.provider_error,
                throwable.message ?: throwable.javaClass.simpleName,
            )
        }
