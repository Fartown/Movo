@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package io.github.fartown.movo.ui.pages.providers

import io.github.fartown.movo.ui.components.movo.movoAnimateItem
import io.github.fartown.movo.ui.components.movo.rememberIsScrolled
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.MovoApp
import io.github.fartown.movo.R
import io.github.fartown.movo.data.model.AnthropicProviderSetting
import io.github.fartown.movo.data.model.CustomProviderSetting
import io.github.fartown.movo.data.model.OpenAiEndpointMode
import io.github.fartown.movo.data.model.ProviderSetting
import io.github.fartown.movo.data.model.ProviderSourceTypes
import io.github.fartown.movo.data.model.withId
import io.github.fartown.movo.data.provider.ProviderSourceRegistry
import io.github.fartown.movo.data.repository.ProviderRepository
import io.github.fartown.movo.data.repository.RemoteModelFetcher
import io.github.fartown.movo.data.repository.RuntimeConfigRepository
import io.github.fartown.movo.ui.components.movo.CardFooter
import io.github.fartown.movo.ui.components.movo.MovoBlockButton
import io.github.fartown.movo.ui.components.movo.MovoCard
import io.github.fartown.movo.ui.components.movo.MovoChoiceDialog
import io.github.fartown.movo.ui.components.movo.MovoConfirmDialog
import io.github.fartown.movo.ui.components.movo.MovoDivider
import io.github.fartown.movo.ui.components.movo.MovoPage
import io.github.fartown.movo.ui.components.movo.MovoPillButton
import io.github.fartown.movo.ui.components.movo.RowLeading
import io.github.fartown.movo.ui.components.movo.RowTrailing
import io.github.fartown.movo.ui.components.movo.SettingsRow
import io.github.fartown.movo.ui.layout.horizontalCutoutPadding
import io.github.fartown.movo.ui.navigation.NewProviderType
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import io.github.fartown.movo.ui.components.movo.TextField
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 提供商详情 / 新建（规范 8.7 二级页）：居中顶栏标题为提供商名称；已有提供商在顶栏下方用分段切换「配置 / 模型」
 * （9.3.1「分段 / 标签」，下方内容沿选择方向横向切换）；新建时只有配置。
 */
@Composable
internal fun ModelProviderDetailScreen(
    providerId: String? = null,
    newType: NewProviderType? = null,
    templateId: String? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // flow 只建一次；初始值 null = 还在读取，读到之前不显示「提供商不存在」（B8）。
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = null)
    var createdId by remember { mutableStateOf<String?>(null) }
    val effectiveId = providerId ?: createdId
    val provider = remember(providers, effectiveId) {
        effectiveId?.let { id -> providers?.firstOrNull { it.id == id } }
    }
    val draft = remember(newType, templateId) {
        // 从模板添加：预填名称 / Base URL / 类型 / 官方模型目录，保存（须填 API Key）后才写入数据库。
        if (templateId != null) ProviderRepository.templateDraft(templateId) else when (newType) {
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
        RuntimeConfigRepository.ensureDefaults(MovoApp.serviceInstance)
    }

    if (provider == null && draft == null) {
        MovoPage(
            title = stringResource(R.string.route_provider_details),
            onBack = onBack,
        ) { contentPadding, sidePadding ->
            if (providers == null) return@MovoPage
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
    val fromTemplate = templateId != null
    var currentTab by rememberSaveable { mutableIntStateOf(0) }
    // 两个标签各自的列表状态放在外面（B13）：切回时回到原来的滚动位置；顶栏滚动态跟随当前标签的列表，不残留。
    val configListState = rememberLazyListState()
    val modelsListState = rememberLazyListState()
    val configScrolled by configListState.rememberIsScrolled()
    val modelsScrolled by modelsListState.rememberIsScrolled()
    var configDraft by rememberSaveable(
        initial.id,
        stateSaver = ProviderConfigDraftSaver,
    ) {
        mutableStateOf(ProviderConfigDraft.from(initial))
    }
    val title = if (isNew && !fromTemplate) context.getString(R.string.page_create_new_provider_36cab9) else initial.name
    val reduced = LocalReducedMotion.current
    // 请求与其反馈同属屏幕生命周期；Crossfade 移除配置页签时不能丢失测试状态。
    var connectionStatus by remember(initial.id) { mutableStateOf<ProviderConnectionStatus?>(null) }
    var connectionGeneration by remember(initial.id) { mutableIntStateOf(0) }

    MovoPage(
        title = title,
        onBack = onBack,
        scrolled = if (currentTab == 1 && !isNew) modelsScrolled else configScrolled,
    ) { contentPadding, sidePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .padding(top = contentPadding.calculateTopPadding()),
        ) {
            // 新建完成后出现「配置 / 模型」分段：高度展开 `standard` + 淡入，不硬插入（B13）。
            AnimatedVisibility(
                visible = !isNew,
                enter = if (reduced) {
                    fadeIn(MovoMotion.fast())
                } else {
                    expandVertically(MovoMotion.standard(), expandFrom = Alignment.Top) +
                        fadeIn(tween(MovoMotion.FAST, easing = MovoMotion.EasingStandard))
                },
                exit = if (reduced) fadeOut(MovoMotion.fastExit()) else shrinkVertically(MovoMotion.standard(), shrinkTowards = Alignment.Top) + fadeOut(MovoMotion.fastExit()),
            ) {
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
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    if (reduced) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        val direction = if (targetState > initialState) 1 else -1
                        // Both pages travel one full viewport with the same timing, so their
                        // edges meet without two readable text layers occupying one position.
                        slideInHorizontally(MovoMotion.fast()) { direction * it } togetherWith
                            slideOutHorizontally(MovoMotion.fast()) { -direction * it }
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds().background(MovoColors.bgCanvas),
                label = "providerTab",
            ) { tab ->
                when (tab) {
                    0 -> ProviderConfigTab(
                        listState = configListState,
                        provider = initial,
                        draft = configDraft,
                        onDraftChange = {
                            if (it != configDraft) {
                                configDraft = it
                                connectionGeneration++
                                connectionStatus = null
                            }
                        },
                        scope = scope,
                        isNew = isNew,
                        fromTemplate = fromTemplate,
                        contentSidePadding = sidePadding,
                        onCreated = { id -> createdId = id },
                        onDeleted = onBack,
                        connectionStatus = connectionStatus,
                        onConnectionStatusChange = { connectionStatus = it },
                        connectionGeneration = connectionGeneration,
                        onConnectionResult = { generation, result ->
                            if (generation == connectionGeneration) connectionStatus = result
                        },
                    )
                    1 -> if (!isNew) {
                        ProviderModelsTab(
                            listState = modelsListState,
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
    listState: LazyListState,
    provider: ProviderSetting,
    draft: ProviderConfigDraft,
    onDraftChange: (ProviderConfigDraft) -> Unit,
    scope: CoroutineScope,
    isNew: Boolean,
    fromTemplate: Boolean,
    contentSidePadding: Dp,
    onCreated: (String) -> Unit,
    onDeleted: () -> Unit,
    connectionStatus: ProviderConnectionStatus?,
    onConnectionStatusChange: (ProviderConnectionStatus) -> Unit,
    connectionGeneration: Int,
    onConnectionResult: (Int, ProviderConnectionStatus) -> Unit,
) {
    val context = LocalContext.current
    var headersExpanded by rememberSaveable { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
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
        state = listState,
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
            // 卡内测试结果、字段增删：卡片高度 `standard` 过渡。
            ProviderSection(title = stringResource(R.string.ui_connection_configuration_7d057b), modifier = movoAnimateItem()) {
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
                    ChatGptAccountSection(scope = scope, onStatus = { message ->
                        onConnectionStatusChange(ProviderConnectionStatus(
                            message,
                            if (message.startsWith(failPrefix)) ProviderStatusTone.Failure else ProviderStatusTone.Success,
                        ))
                    })
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
                    enabled = !isWorking && connectionStatus?.tone != ProviderStatusTone.Running,
                    showDivider = connectionStatus != null,
                    onClick = {
                        val validationError = validateProviderDraft(context, draft)
                        if (validationError != null) {
                            onConnectionStatusChange(ProviderConnectionStatus(
                                context.getString(R.string.provider_error, validationError),
                                ProviderStatusTone.Failure,
                            ))
                            return@SettingsRow
                        }
                        onConnectionStatusChange(ProviderConnectionStatus(
                            context.getString(R.string.page_testing_f43705),
                            ProviderStatusTone.Running,
                        ))
                        val generation = connectionGeneration
                        scope.launch {
                            try {
                                onConnectionResult(generation, testConnection(
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
                                    ),
                                ))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            }
                        }
                    },
                )
                connectionStatus?.let { result ->
                    ProviderStatusLine(
                        message = result.message,
                        isError = result.tone == ProviderStatusTone.Failure,
                        tone = result.tone,
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
            ProviderSection(title = stringResource(R.string.ui_preferences_and_strategies_2abd3c), modifier = movoAnimateItem()) {
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
            // 按钮下方的保存 / 测试结果说明出现时整组高度 `standard` 过渡（不是卡片，没有圆角可裁，直接用列表项的高度动画）。
            Column(
                modifier = movoAnimateItem(contentSize = true).fillMaxWidth(),
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
                            ?: context.getString(R.string.provider_api_key_required)
                                .takeIf { isNew && fromTemplate && draft.apiKey.isBlank() }
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
                                    val added = if (fromTemplate) {
                                        // 模板 id 沿用，据此从「可以添加」里去掉。
                                        ProviderRepository.addFromTemplate(built)
                                    } else {
                                        ProviderRepository.addProvider(
                                            built.withId(ProviderRepository.newId())
                                        )
                                    }
                                    if (added.isEnabled) {
                                        RuntimeConfigRepository.setSelectedProviderId(added.id)
                                    }
                                    RuntimeConfigRepository.syncToRemotePreferences(
                                        MovoApp.serviceInstance
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
                                        MovoApp.serviceInstance
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
                // 内置预设已改为模板：添加过的预设（ChatGPT 除外）也能删除，删除后回到「可以添加」。
                val removablePreset = provider.isBuiltIn && !isChatGpt
                MovoCard(modifier = movoAnimateItem()) {
                    if (removablePreset) {
                        SettingsRow(
                            title = context.getString(R.string.page_remove_provider_9f848f),
                            leading = RowLeading.Custom {
                                MovoIcon(
                                    MovoIcons.Trash2,
                                    contentDescription = null,
                                    size = MovoSize.iconMedium,
                                    tint = MovoColors.roseFg,
                                )
                            },
                            trailing = RowTrailing.None,
                            enabled = !isWorking,
                            onClick = { showDeleteDialog = true },
                        )
                    }
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
        confirmText = context.getString(R.string.page_delete_3755f5),
        confirmLoading = isWorking,
        cancelEnabled = !isWorking,
        confirmEnabled = !isWorking,
        destructive = true,
        onDismissRequest = { if (!isWorking) showDeleteDialog = false },
        onConfirm = {
            scope.launch {
                isWorking = true
                try {
                    ProviderRepository.deleteProvider(provider.id)
                    RuntimeConfigRepository.syncToRemotePreferences(MovoApp.serviceInstance)
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
        confirmText = context.getString(R.string.page_reset_3d8134),
        confirmLoading = isWorking,
        // 覆盖为内置配置会丢掉当前修改（C10），按危险确认处理。
        destructive = true,
        cancelEnabled = !isWorking,
        confirmEnabled = !isWorking,
        onDismissRequest = { if (!isWorking) showResetDialog = false },
        onConfirm = {
            scope.launch {
                isWorking = true
                try {
                    ProviderRepository.resetBuiltIn(provider.id)
                    RuntimeConfigRepository.syncToRemotePreferences(MovoApp.serviceInstance)
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
): ProviderConnectionStatus =
    RemoteModelFetcher.fetch(provider)
        .map { ProviderConnectionStatus(
            context.resources.getQuantityString(R.plurals.provider_models_fetched, it.size, it.size),
            ProviderStatusTone.Success,
        ) }
        .getOrElse { throwable ->
            ProviderConnectionStatus(
                context.getString(R.string.provider_error, throwable.message ?: throwable.javaClass.simpleName),
                ProviderStatusTone.Failure,
            )
        }

private data class ProviderConnectionStatus(val message: String, val tone: ProviderStatusTone)
