@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package io.github.fartown.movo.ui.pages.providers

import io.github.fartown.movo.ui.components.movo.movoAnimateItem
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.fartown.movo.MovoApp
import io.github.fartown.movo.R
import io.github.fartown.movo.data.model.Model
import io.github.fartown.movo.data.model.ModelReasoningCapabilities
import io.github.fartown.movo.data.model.ProviderSetting
import io.github.fartown.movo.data.model.ReasoningEffort
import io.github.fartown.movo.data.repository.ModelRepository
import io.github.fartown.movo.data.repository.RemoteModelFetcher
import io.github.fartown.movo.data.repository.RuntimeConfigRepository
import io.github.fartown.movo.data.model.ProviderSourceTypes
import io.github.fartown.movo.data.provider.ProviderSourceRegistry
import io.github.fartown.movo.ui.components.movo.BlockTone
import io.github.fartown.movo.ui.components.movo.CardTitle
import io.github.fartown.movo.ui.components.movo.MovoBlockButton
import io.github.fartown.movo.ui.components.movo.MovoButtonRow
import io.github.fartown.movo.ui.components.movo.MovoDialogButtonPadding
import io.github.fartown.movo.ui.components.movo.MovoConfirmDialog
import io.github.fartown.movo.ui.components.movo.MovoDialogHost
import io.github.fartown.movo.ui.components.movo.rememberLastNonNull
import io.github.fartown.movo.ui.components.movo.MovoDivider
import io.github.fartown.movo.ui.components.movo.MovoIconButton
import io.github.fartown.movo.ui.components.movo.MovoPillButton
import io.github.fartown.movo.ui.components.movo.MovoSpinner
import io.github.fartown.movo.ui.components.movo.PressKind
import io.github.fartown.movo.ui.components.movo.RowTrailing
import io.github.fartown.movo.ui.components.movo.SettingsRow
import io.github.fartown.movo.ui.components.movo.movoClickable
import io.github.fartown.movo.ui.components.movo.movoSelectedRow
import io.github.fartown.movo.ui.components.movo.movoSelectedTextColor
import io.github.fartown.movo.ui.components.movo.movoSurface
import io.github.fartown.movo.ui.model.formatCompactTokenCount
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoElevation
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import io.github.fartown.movo.ui.components.movo.TextField
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private val modelSearchSeparators = Regex("""[^\p{L}\p{N}]+""")
private val editableReasoningEfforts = listOf(
    ReasoningEffort.OFF,
    ReasoningEffort.MINIMAL,
    ReasoningEffort.LOW,
    ReasoningEffort.MEDIUM,
    ReasoningEffort.HIGH,
    ReasoningEffort.XHIGH,
    ReasoningEffort.MAX,
)

private val modelDialogBodyMaxHeight = 520.dp
private val modelDialogChromeHeight = 112.dp

private fun Modifier.modelDialogScrollableBody(): Modifier = layout { measurable, constraints ->
    // 从弹窗实际约束中为标题、间距和操作栏让出空间，避免横屏时底部按钮被内容挤出边界。
    val fallbackMaxHeight = modelDialogBodyMaxHeight.roundToPx()
    val reservedHeight = modelDialogChromeHeight.roundToPx()
    val maxHeight = if (constraints.hasBoundedHeight) {
        (constraints.maxHeight - reservedHeight)
            .coerceAtLeast(1)
            .coerceAtMost(fallbackMaxHeight)
    } else {
        fallbackMaxHeight
    }
    val placeable = measurable.measure(
        constraints.copy(
            minHeight = constraints.minHeight.coerceAtMost(maxHeight),
            maxHeight = maxHeight,
        ),
    )
    layout(placeable.width, placeable.height) {
        placeable.place(0, 0)
    }
}

internal fun contextWindowInputError(
    value: String,
    errorMessage: String = "Context window must be a positive integer",
): String? {
    val normalized = value.trim()
    if (normalized.isEmpty()) return null
    return if (normalized.toIntOrNull()?.let { it > 0 } == true) {
        null
    } else {
        errorMessage
    }
}

internal fun filterProviderModels(models: List<Model>, query: String): List<Model> {
    val queryTokens = query.lowercase().split(modelSearchSeparators).filter(String::isNotBlank)
    return models
        .sortedBy { it.sortOrder }
        .filter { model ->
            if (queryTokens.isEmpty()) {
                true
            } else {
                val searchableFields = listOf(model.displayName, model.modelId).map { field ->
                    field.lowercase().filter(Char::isLetterOrDigit)
                }
                queryTokens.all { token ->
                    searchableFields.any { field -> field.containsCharactersInOrder(token) }
                }
            }
        }
}

private fun String.containsCharactersInOrder(query: String): Boolean {
    var queryIndex = 0
    for (character in this) {
        if (character == query[queryIndex]) {
            queryIndex++
            if (queryIndex == query.length) return true
        }
    }
    return false
}

/**
 * 提供商详情 ·「模型」（规范 8.7 列表页）：「模型管理」卡（从远端拉取、添加自定义模型、就地结果）→ 搜索框 →
 * 模型列表卡（卡内标题写数量；点行设为当前，长按进入多选，右侧编辑与单选）。模型数量可能上百，
 * 列表卡拆成多个 Lazy 条目，由 [movoCardSegment] 画出同一张卡片。
 */
@Composable
internal fun ProviderModelsTab(
    listState: LazyListState,
    provider: ProviderSetting,
    scope: CoroutineScope,
    contentSidePadding: Dp,
) {
    val context = LocalContext.current
    val selectedModelId by remember { RuntimeConfigRepository.selectedModelIdFlow() }.collectAsState(initial = null)
    var isFetching by remember { mutableStateOf(false) }
    var isMutatingModel by remember { mutableStateOf(false) }
    // 拉取结果写在「从远端自动拉取」行的说明位，增删改结果写在模型列表标题右侧：都在原位换字，卡片高度不变（C6，防跳闪）。
    var fetchStatus by remember { mutableStateOf<String?>(null) }
    var listStatus by remember { mutableStateOf<String?>(null) }
    var editingModel by remember { mutableStateOf<Model?>(null) }
    var isCreatingModel by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var modelPendingDelete by remember { mutableStateOf<Model?>(null) }
    var lastModelPendingDelete by remember { mutableStateOf<Model?>(null) }
    if (modelPendingDelete != null) lastModelPendingDelete = modelPendingDelete
    var selectionMode by remember(provider.id) { mutableStateOf(false) }
    var selectedModelIds by remember(provider.id) { mutableStateOf(setOf<String>()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var modelSearchQuery by remember(provider.id) { mutableStateOf("") }
    val normalizedModelSearchQuery = modelSearchQuery.trim()
    val filteredModels = remember(provider.models, modelSearchQuery) {
        filterProviderModels(provider.models, modelSearchQuery)
    }
    val failPrefix = stringResource(R.string.page_fail_3e3c80)
    val navigation = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 拉取期间不置灰整页：远端很快返回时整列表先变灰再恢复，看起来就是一闪；拉取行自身换加载圈并忽略重复点击，
    // 模型的增删改与同步都经 ModelRepository 的互斥锁串行，不会互相覆盖。
    val actionsEnabled = !isMutatingModel

    val selectionBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = selectionBackState,
        isBackEnabled = selectionMode,
        onBackCompleted = {
            selectionMode = false
            selectedModelIds = emptySet()
        },
    )

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical(),
            contentPadding = PaddingValues(
                start = contentSidePadding + MovoSpacing.pageEdge,
                end = contentSidePadding + MovoSpacing.pageEdge,
                top = MovoSpacing.md,
                // 多选操作栏悬浮在底部时，预留高度避免遮挡最后一个列表项。
                bottom = navigation + if (selectionMode) ModelSelectionBarReserve else MovoSpacing.section,
            ),
            overscrollEffect = null,
        ) {
            item(key = "actions", contentType = "section") {
                // 「模型管理」卡片高度全程不变（规范「服务商详情 · 从远端拉取模型」）：拉取状态与结果都在这一行的说明位原位替换。
                ProviderSection(
                    title = stringResource(R.string.ui_model_management_183414),
                    modifier = movoAnimateItem(),
                ) {
                    val endpointSummary = stringResource(R.string.provider_models_endpoint_summary, provider.baseUrl)
                    val fetchingText = stringResource(R.string.provider_models_fetching)
                    SettingsRow(
                        title = context.getString(R.string.page_automatically_pull_from_remote_f883d0),
                        subtitleContent = {
                            FetchStatusText(
                                text = if (isFetching) fetchingText else fetchStatus ?: endpointSummary,
                                isError = !isFetching && fetchStatus?.startsWith(failPrefix) == true,
                            )
                        },
                        enabled = actionsEnabled,
                        trailing = RowTrailing.Custom {
                            // 下载图标 ↔ 加载圈交叉淡化 `fast`；标题与行状态不变，避免整行闪。
                            androidx.compose.animation.Crossfade(
                                targetState = isFetching,
                                animationSpec = MovoMotion.fast(),
                                label = "fetchIndicator",
                            ) { fetching ->
                                Box(Modifier.size(MovoSize.iconMedium), contentAlignment = Alignment.Center) {
                                    if (fetching) {
                                        MovoSpinner(size = MovoSize.iconMedium, color = MovoColors.textSecondary)
                                    } else {
                                        MovoIcon(MovoIcons.Download, null, size = MovoSize.iconMedium, tint = MovoColors.textSecondary)
                                    }
                                }
                            }
                        },
                        onClick = onClick@{
                            if (isFetching) return@onClick
                            scope.launch {
                                isFetching = true
                                try {
                                    val models = RemoteModelFetcher.fetch(provider).getOrElse { throwable ->
                                        fetchStatus = context.getString(
                                            R.string.provider_error,
                                            throwable.message ?: throwable.javaClass.simpleName,
                                        )
                                        return@launch
                                    }
                                    val chatModels = models.filter(RemoteModelFetcher::isChatCapableModel)
                                    val sync = ModelRepository.syncRemoteModels(
                                        provider.id,
                                        chatModels,
                                        authoritative = ProviderSourceRegistry.resolve(provider) == ProviderSourceTypes.CHATGPT,
                                    )
                                    if (sync.applied) {
                                        RuntimeConfigRepository.syncToRemotePreferences(MovoApp.serviceInstance)
                                    }
                                    val filteredCount = models.size - chatModels.size
                                    fetchStatus = if (!sync.applied) {
                                        context.getString(R.string.page_the_remote_end_did_not_return_a_usable_conversation__781487)
                                    } else if (filteredCount > 0) {
                                        context.getString(
                                            R.string.provider_models_fetched_filtered,
                                            chatModels.size,
                                            filteredCount,
                                        )
                                    } else {
                                        context.resources.getQuantityString(
                                            R.plurals.provider_models_fetched,
                                            chatModels.size,
                                            chatModels.size,
                                        )
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (throwable: Throwable) {
                                    fetchStatus = context.getString(
                                        R.string.provider_error,
                                        throwable.message ?: context.getString(R.string.provider_sync_failed),
                                    )
                                } finally {
                                    isFetching = false
                                }
                            }
                        },
                    )
                    SettingsRow(
                        title = stringResource(R.string.ui_add_custom_model_a5ddc0),
                        subtitle = stringResource(R.string.ui_manually_fill_in_the_display_name_and_model_id_077a7b),
                        enabled = actionsEnabled,
                        showDivider = false,
                        trailing = RowTrailing.Custom {
                            MovoIcon(MovoIcons.Plus, null, size = MovoSize.iconMedium, tint = MovoColors.textSecondary)
                        },
                        onClick = {
                            editorError = null
                            isCreatingModel = true
                            editingModel = Model(
                                id = "",
                                modelId = "",
                                displayName = context.getString(R.string.page_custom_model_25be0f),
                            )
                        },
                    )
                }
            }

            item(key = "model_search", contentType = "search") {
                MovoSearchField(
                    query = modelSearchQuery,
                    onQueryChange = { modelSearchQuery = it },
                    placeholder = stringResource(R.string.ui_search_model_df5586),
                    modifier = movoAnimateItem().padding(top = MovoSpacing.lg),
                )
            }

            val modelListTitle = if (normalizedModelSearchQuery.isBlank()) {
                context.getString(R.string.provider_model_list_count, provider.models.size)
            } else {
                context.getString(
                    R.string.provider_model_list_matches,
                    filteredModels.size,
                    provider.models.size,
                )
            }
            if (provider.models.isEmpty() || filteredModels.isEmpty()) {
                item(key = "models_empty", contentType = "empty") {
                    ProviderSection(
                        title = modelListTitle,
                        trailing = listStatus,
                        modifier = movoAnimateItem().padding(top = MovoSpacing.lg),
                    ) {
                        Text(
                            text = if (provider.models.isEmpty()) {
                                context.getString(R.string.page_there_is_no_model_yet_please_pull_it_from_the_remote_ced865)
                            } else {
                                context.getString(R.string.page_no_matching_model_found_ae7e96)
                            },
                            style = MovoTypography.labelRegular,
                            color = MovoColors.textSecondary,
                            modifier = Modifier.padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.md),
                        )
                    }
                }
            } else {
                item(key = "models_title", contentType = "section_title") {
                    CardTitle(
                        text = modelListTitle,
                        trailing = listStatus,
                        modifier = movoAnimateItem()
                            .padding(top = MovoSpacing.lg)
                            .movoCardSegment(CardSegment.Top),
                    )
                }
                itemsIndexed(
                    items = filteredModels,
                    key = { _, model -> "model:${model.id}" },
                    contentType = { _, _ -> "model" },
                ) { index, model ->
                    val isLast = index == filteredModels.lastIndex
                    ModelListItem(
                        model = model,
                        enabled = actionsEnabled,
                        isSelected = model.id == selectedModelId,
                        selectionMode = selectionMode,
                        checked = model.id in selectedModelIds,
                        showDivider = !isLast,
                        onToggleChecked = {
                            selectedModelIds = if (model.id in selectedModelIds) {
                                selectedModelIds - model.id
                            } else {
                                selectedModelIds + model.id
                            }
                        },
                        onEnterSelection = {
                            selectionMode = true
                            selectedModelIds = setOf(model.id)
                        },
                        onEdit = {
                            editorError = null
                            isCreatingModel = false
                            editingModel = model
                        },
                        onSetCurrent = {
                            scope.launch {
                                RuntimeConfigRepository.setSelectedModelId(model.id)
                                RuntimeConfigRepository.syncToRemotePreferences(MovoApp.serviceInstance)
                            }
                        },
                        // 列表增删（B7）：新增淡入、删除淡出，其余行移位 `standard`。
                        modifier = movoAnimateItem()
                            .movoCardSegment(if (isLast) CardSegment.Bottom else CardSegment.Middle)
                            .padding(bottom = if (isLast) MovoSpacing.xs else 0.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = selectionMode,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(MovoMotion.standard(MovoMotion.EasingEnter)) { it } + fadeIn(MovoMotion.standard()),
            exit = slideOutVertically(MovoMotion.standardExit()) { it } + fadeOut(MovoMotion.standardExit()),
        ) {
            ModelSelectionBar(
                selectedCount = selectedModelIds.size,
                totalCount = provider.models.size,
                enabled = actionsEnabled,
                sidePadding = contentSidePadding + MovoSpacing.pageEdge,
                onToggleAll = {
                    selectedModelIds = if (selectedModelIds.size == provider.models.size) {
                        emptySet()
                    } else {
                        provider.models.mapTo(mutableSetOf()) { it.id }
                    }
                },
                onDelete = { showBatchDeleteDialog = true },
                onExit = {
                    selectionMode = false
                    selectedModelIds = emptySet()
                },
            )
        }
    }

    // 保存 / 取消后先播退场动画再移除：动画期间保留最后一次编辑的模型。
    rememberLastNonNull(editingModel)?.let { model ->
        ModelEditDialog(
            show = editingModel != null,
            model = model,
            isNew = isCreatingModel,
            isSaving = isMutatingModel,
            error = editorError,
            onDismiss = {
                if (!isMutatingModel) editingModel = null
            },
            onSubmit = { updated ->
                if (isMutatingModel) return@ModelEditDialog
                scope.launch {
                    isMutatingModel = true
                    editorError = null
                    try {
                        val saved = ModelRepository.saveModel(provider.id, updated)
                        RuntimeConfigRepository.syncToRemotePreferences(MovoApp.serviceInstance)
                        editingModel = null
                        listStatus = context.getString(R.string.provider_model_saved, saved.displayName)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        editorError = throwable.message ?: context.getString(R.string.page_save_failed_40525a)
                    } finally {
                        isMutatingModel = false
                    }
                }
            },
            onDelete = if (isCreatingModel) null else {
                {
                    modelPendingDelete = model
                    editingModel = null
                }
            },
        )
    }

    MovoConfirmDialog(
        show = modelPendingDelete != null,
        title = stringResource(R.string.ui_delete_model_cf24da),
        message = stringResource(R.string.provider_model_delete_summary, lastModelPendingDelete?.displayName.orEmpty()),
        confirmText = context.getString(R.string.page_delete_3755f5),
        confirmLoading = isMutatingModel,
        cancelEnabled = !isMutatingModel,
        confirmEnabled = !isMutatingModel,
        destructive = true,
        onDismissRequest = { if (!isMutatingModel) modelPendingDelete = null },
        onConfirm = {
            val model = modelPendingDelete ?: return@MovoConfirmDialog
            scope.launch {
                isMutatingModel = true
                try {
                    ModelRepository.deleteModel(provider.id, model.id)
                    RuntimeConfigRepository.syncToRemotePreferences(MovoApp.serviceInstance)
                    listStatus = context.getString(R.string.provider_model_deleted, model.displayName)
                    modelPendingDelete = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    listStatus = context.getString(
                        R.string.provider_error,
                        throwable.message ?: context.getString(R.string.provider_delete_failed),
                    )
                    modelPendingDelete = null
                } finally {
                    isMutatingModel = false
                }
            }
        },
    )

    MovoConfirmDialog(
        show = showBatchDeleteDialog,
        title = stringResource(R.string.ui_delete_model_cf24da),
        message = pluralStringResource(
            R.plurals.provider_selected_delete_summary,
            selectedModelIds.size,
            selectedModelIds.size,
        ),
        confirmText = context.getString(R.string.page_delete_3755f5),
        confirmLoading = isMutatingModel,
        cancelEnabled = !isMutatingModel,
        confirmEnabled = !isMutatingModel,
        destructive = true,
        onDismissRequest = { if (!isMutatingModel) showBatchDeleteDialog = false },
        onConfirm = {
            scope.launch {
                val deletedCount = selectedModelIds.size
                isMutatingModel = true
                try {
                    ModelRepository.deleteModels(provider.id, selectedModelIds)
                    RuntimeConfigRepository.syncToRemotePreferences(MovoApp.serviceInstance)
                    listStatus = context.resources.getQuantityString(
                        R.plurals.provider_models_deleted,
                        deletedCount,
                        deletedCount,
                    )
                    showBatchDeleteDialog = false
                    selectionMode = false
                    selectedModelIds = emptySet()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    listStatus = context.getString(
                        R.string.provider_error,
                        throwable.message ?: context.getString(R.string.provider_delete_failed),
                    )
                    showBatchDeleteDialog = false
                } finally {
                    isMutatingModel = false
                }
            }
        },
    )
}

/** 多选操作栏高 56（32 按钮上下各 12）+ 下方 12，列表为它预留的底部空间。 */
private val ModelSelectionBarReserve = 88.dp

/**
 * 多选模式底部悬浮操作栏：白底圆角 28、E2 阴影，高 56；退出在左，已选数量其次，全选与删除在右（行内按钮 32 高）。
 */
@Composable
private fun ModelSelectionBar(
    selectedCount: Int,
    totalCount: Int,
    enabled: Boolean,
    sidePadding: Dp,
    onToggleAll: () -> Unit,
    onDelete: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = sidePadding, end = sidePadding, bottom = MovoSpacing.md)
            .movoSurface(RoundedCornerShape(MovoRadius.xl), elevation = MovoElevation.Composer)
            .heightIn(min = 56.dp)
            .padding(start = MovoSpacing.xxs, end = MovoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MovoSpacing.sm),
    ) {
        MovoIconButton(
            icon = MovoIcons.X,
            contentDescription = stringResource(R.string.ui_exit_multiple_selection_c194fd),
            onClick = onExit,
            enabled = enabled,
            tint = MovoColors.textSecondary,
        )
        Text(
            text = pluralStringResource(
                R.plurals.provider_models_selected,
                selectedCount,
                selectedCount,
            ),
            style = MovoTypography.bodyStrong,
            color = MovoColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        MovoPillButton(
            label = if (selectedCount == totalCount) context.getString(R.string.page_select_none_ba20eb) else context.getString(R.string.page_select_all_3e44b2),
            enabled = enabled,
            onClick = onToggleAll,
        )
        MovoPillButton(
            label = stringResource(R.string.ui_delete_3755f5),
            icon = MovoIcons.Trash2,
            enabled = selectedCount > 0 && enabled,
            onClick = onDelete,
        )
    }
}

/**
 * 模型行：显示名（Body/Strong）、Model ID（Label 次要色）、能力标签；右侧编辑（笔），多选模式换成复选框。
 * 当前模型整行浅紫（Figma「10 · 单选样式」，不放单选按钮）；点行 = 设为当前（多选模式下 = 勾选），长按进入多选。
 */
@Composable
private fun ModelListItem(
    model: Model,
    enabled: Boolean,
    isSelected: Boolean,
    selectionMode: Boolean,
    checked: Boolean,
    showDivider: Boolean,
    onToggleChecked: () -> Unit,
    onEnterSelection: () -> Unit,
    onEdit: () -> Unit,
    onSetCurrent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val setCurrentDescription = if (isSelected) {
        context.getString(R.string.page_current_model_a0af8f)
    } else {
        context.getString(R.string.page_set_as_current_model_183d7d)
    }
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (selectionMode) Modifier else Modifier.movoSelectedRow(isSelected))
                .movoClickable(
                    kind = PressKind.Row,
                    enabled = enabled,
                    role = if (selectionMode) Role.Checkbox else Role.RadioButton,
                    onLongClick = {
                        if (selectionMode) onToggleChecked() else onEnterSelection()
                    },
                    onClick = if (selectionMode) onToggleChecked else onSetCurrent,
                )
                .semantics {
                    if (!selectionMode) {
                        selected = isSelected
                        stateDescription = setCurrentDescription
                    }
                }
                .heightIn(min = 68.dp)
                .padding(start = MovoSpacing.lg, top = MovoSpacing.md, bottom = MovoSpacing.md, end = MovoSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.displayName,
                    style = MovoTypography.bodyStrong,
                    color = movoSelectedTextColor(isSelected && !selectionMode),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = model.modelId,
                    style = MovoTypography.labelRegular,
                    color = MovoColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 标签放不下一行时换行（真机：「1.05M 上下文」「支持思考」之后的「当前」被挤成空胶囊）。
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = MovoSpacing.sm),
                ) {
                    capabilityTags(model).forEach { tag ->
                        TagChip(text = tag)
                    }
                    if (isSelected) {
                        TagChip(text = stringResource(R.string.ui_current_25e74d), tone = TagChipTone.Emphasized)
                    }
                }
            }
            if (selectionMode) {
                Box(modifier = Modifier.size(MovoSize.touchTarget), contentAlignment = Alignment.Center) {
                    MovoCheckbox(checked = checked, enabled = enabled)
                }
            } else {
                MovoIconButton(
                    icon = MovoIcons.PenLine,
                    contentDescription = stringResource(R.string.ui_edit_model_parameters_ba4864),
                    onClick = onEdit,
                    enabled = enabled,
                    iconSize = MovoSize.iconMedium,
                    tint = MovoColors.textSecondary,
                )
            }
        }
        if (showDivider) {
            MovoDivider(modifier = Modifier.align(Alignment.BottomStart), start = MovoSpacing.lg)
        }
    }
}

/**
 * 模型参数对话框（规范 8.11 对话框容器）：标题 → 可滚动的字段区（显示名、Model ID、上下文长度、思考能力与档位）→
 * 取消 / 保存两个整行按钮。字段区按弹窗实际高度让出标题与按钮区，横屏时按钮不会被挤出。
 */
@Composable
private fun ModelEditDialog(
    show: Boolean,
    model: Model,
    isNew: Boolean,
    isSaving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (Model) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val context = LocalContext.current
    var displayName by remember(model.id, isNew) { mutableStateOf(model.displayName) }
    var modelId by remember(model.id, isNew) { mutableStateOf(model.modelId) }
    var contextWindowOverrideText by remember(model.id, isNew) {
        mutableStateOf(model.contextWindowOverride?.toString().orEmpty())
    }
    var reasoningOverrideActive by remember(model.id, isNew) {
        mutableStateOf(
            model.reasoningOverride != null || model.reasoningCapabilitiesOverride != null
        )
    }
    var reasoningEnabled by remember(model.id, isNew) {
        mutableStateOf(model.supportsReasoning)
    }
    var selectedReasoningEfforts by remember(model.id, isNew) {
        mutableStateOf(
            model.effectiveReasoningCapabilities
                ?.selectableEfforts
                ?.toSet()
                .orEmpty() + ReasoningEffort.DEFAULT
        )
    }
    val contextError = contextWindowInputError(
        contextWindowOverrideText,
        context.getString(R.string.page_the_context_length_must_be_a_positive_integer_06ca7a),
    )

    fun resetAutomaticReasoning() {
        reasoningOverrideActive = false
        reasoningEnabled = model.reasoning == true
        selectedReasoningEfforts = model.reasoningCapabilities
            ?.selectableEfforts
            ?.toSet()
            .orEmpty() + ReasoningEffort.DEFAULT
    }

    fun updated(): Model = model.copy(
        displayName = displayName.trim(),
        modelId = modelId.trim(),
        contextWindowOverride = contextWindowOverrideText.trim()
            .takeIf(String::isNotEmpty)
            ?.toInt(),
        reasoningOverride = reasoningEnabled.takeIf { reasoningOverrideActive },
        reasoningCapabilitiesOverride = if (reasoningOverrideActive && reasoningEnabled) {
            val canDisable = ReasoningEffort.OFF in selectedReasoningEfforts
            (model.effectiveReasoningCapabilities ?: ModelReasoningCapabilities()).copy(
                supportedEfforts = editableReasoningEfforts.filter { effort ->
                    effort != ReasoningEffort.OFF && effort in selectedReasoningEfforts
                },
                defaultEffort = model.effectiveReasoningCapabilities
                    ?.defaultEffort
                    ?.takeIf { it in selectedReasoningEfforts },
                defaultEnabled = true,
                mandatory = !canDisable,
                canDisable = canDisable,
            )
        } else {
            null
        },
    )

    MovoDialogHost(
        show = show,
        onDismissRequest = { if (!isSaving) onDismiss() },
        dismissible = !isSaving,
    ) {
        Text(
            text = if (isNew) context.getString(R.string.page_add_model_532a64) else context.getString(R.string.page_edit_model_29e31e),
            style = MovoTypography.titleSection,
            color = MovoColors.textPrimary,
            modifier = Modifier.padding(start = MovoSpacing.xxl, end = MovoSpacing.xxl, top = MovoSpacing.xxl, bottom = MovoSpacing.sm),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .modelDialogScrollableBody()
                .scrollEndHaptic()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MovoSpacing.xxl),
        ) {
            TextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = stringResource(R.string.ui_display_name_ed16be),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(MovoSpacing.md))
            TextField(
                value = modelId,
                onValueChange = { modelId = it },
                label = "Model ID",
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(MovoSpacing.md))
            TextField(
                value = contextWindowOverrideText,
                onValueChange = { contextWindowOverrideText = it },
                label = stringResource(R.string.ui_context_length_tokens_227860),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = MovoSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        contextWindowOverrideText.isNotBlank() -> context.getString(R.string.page_overwritten_will_take_precedence_over_remote_metadat_59934d)
                        model.contextWindow != null ->
                            stringResource(
                                R.string.provider_auto_context,
                                formatCompactTokenCount(model.contextWindow),
                            )
                        else -> context.getString(R.string.page_automatic_no_context_cap_was_provided_by_the_remote__db027f)
                    },
                    style = MovoTypography.labelRegular,
                    color = MovoColors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (contextWindowOverrideText.isNotBlank()) {
                    Spacer(Modifier.width(MovoSpacing.sm))
                    MovoPillButton(
                        label = stringResource(R.string.ui_restore_automatic_8d4e1e),
                        enabled = !isSaving,
                        onClick = { contextWindowOverrideText = "" },
                    )
                }
            }
            contextError?.let { validationError ->
                ProviderStatusLine(
                    message = validationError,
                    isError = true,
                    modifier = Modifier.padding(top = MovoSpacing.xs),
                )
            }
            Text(
                text = stringResource(R.string.ui_this_value_is_used_for_session_clipping_and_context__c3f9e7),
                style = MovoTypography.labelRegular,
                color = MovoColors.textSecondary,
                modifier = Modifier.padding(top = MovoSpacing.xs, bottom = MovoSpacing.md),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .movoSurface(RoundedCornerShape(MovoRadius.lg)),
            ) {
                SettingsRow(
                    title = stringResource(R.string.ui_support_thinking_5b9e4c),
                    subtitle = if (reasoningOverrideActive) {
                        context.getString(R.string.page_covered_model_automatic_capabilities_3fa7d4)
                    } else {
                        stringResource(
                            if (model.reasoning == true) {
                                R.string.provider_auto_reasoning_supported
                            } else {
                                R.string.provider_auto_reasoning_unknown
                            },
                        )
                    },
                    trailing = RowTrailing.Switch(reasoningEnabled) { enabled ->
                        reasoningOverrideActive = true
                        reasoningEnabled = enabled
                    },
                    enabled = !isSaving,
                    showDivider = reasoningEnabled,
                )
                if (reasoningEnabled) {
                    ReasoningEffortRow(
                        title = ReasoningEffort.DEFAULT.displayName,
                        summary = stringResource(R.string.ui_determined_by_model_or_provider_06c326),
                        checked = true,
                        enabled = false,
                        showDivider = true,
                        onToggle = null,
                    )
                    editableReasoningEfforts.forEachIndexed { index, effort ->
                        ReasoningEffortRow(
                            title = effort.displayName,
                            summary = if (effort == ReasoningEffort.OFF) {
                                context.getString(R.string.page_allow_thinking_to_be_turned_off_during_conversations_5a32a9)
                            } else {
                                null
                            },
                            checked = effort in selectedReasoningEfforts,
                            enabled = !isSaving,
                            showDivider = index != editableReasoningEfforts.lastIndex,
                            onToggle = {
                                reasoningOverrideActive = true
                                selectedReasoningEfforts = if (effort in selectedReasoningEfforts) {
                                    selectedReasoningEfforts - effort
                                } else {
                                    selectedReasoningEfforts + effort
                                }
                            },
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.ui_only_check_the_ranges_actually_supported_by_the_mode_2c343d),
                style = MovoTypography.labelRegular,
                color = MovoColors.textSecondary,
                modifier = Modifier.padding(top = MovoSpacing.sm),
            )
            error?.let { message ->
                ProviderStatusLine(
                    message = message,
                    isError = true,
                    modifier = Modifier.padding(top = MovoSpacing.sm),
                )
            }
            if (reasoningOverrideActive || onDelete != null) {
                Row(
                    modifier = Modifier.padding(top = MovoSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(MovoSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (reasoningOverrideActive) {
                        MovoPillButton(
                            label = stringResource(R.string.ui_restore_automatic_8d4e1e),
                            icon = MovoIcons.RotateCcw,
                            enabled = !isSaving,
                            onClick = ::resetAutomaticReasoning,
                        )
                    }
                    onDelete?.let { delete ->
                        MovoPillButton(
                            label = stringResource(R.string.ui_delete_model_cf24da),
                            icon = MovoIcons.Trash2,
                            enabled = !isSaving,
                            onClick = delete,
                        )
                    }
                }
            }
            Spacer(Modifier.height(MovoSpacing.lg))
        }
        MovoButtonRow(modifier = Modifier.padding(MovoDialogButtonPadding)) {
            MovoBlockButton(
                label = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                tone = BlockTone.Secondary,
                enabled = !isSaving,
            )
            MovoBlockButton(
                // 保存中文字原位换成加载圈，按钮宽度不变，按钮区不会从并排跳成上下排。
                label = context.getString(R.string.page_save_fadf24),
                loading = isSaving,
                onClick = { onSubmit(updated()) },
                enabled = !isSaving &&
                    displayName.isNotBlank() &&
                    modelId.isNotBlank() &&
                    contextError == null,
            )
        }
    }
}

/** 思考档位复选行：标题 15 Medium + 可选说明 13 次要色，右侧 [MovoCheckbox]；整行可点。 */
@Composable
private fun ReasoningEffortRow(
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean,
    showDivider: Boolean,
    onToggle: (() -> Unit)?,
) {
    SettingsRow(
        title = title,
        subtitle = summary,
        trailing = RowTrailing.Custom {
            // 可点的行由整行负责 40% 禁用态；不可点的「默认」行只让复选框自身变淡。
            MovoCheckbox(checked = checked, enabled = onToggle != null || enabled)
        },
        enabled = enabled,
        showDivider = showDivider,
        onClick = onToggle,
    )
}

@Composable
private fun capabilityTags(model: Model): List<String> {
    val context = LocalContext.current
    return buildList {
    add(
        model.effectiveContextWindow?.let { contextWindow ->
            stringResource(
                R.string.provider_context_tag,
                formatCompactTokenCount(contextWindow),
            )
        } ?: context.getString(R.string.page_context_unknown_b6ae7b)
    )
    if (model.supportsReasoning) add(context.getString(R.string.page_support_thinking_5b9e4c))
    }
}

/** 「从远端自动拉取」行的说明：单行过长省略，内容变化时原位换字（旧字 120 快速淡出，新字 40ms 后 `fast` 淡入）。 */
@Composable
private fun FetchStatusText(text: String, isError: Boolean) {
    androidx.compose.animation.AnimatedContent(
        targetState = text to isError,
        transitionSpec = {
            // 旧字一开始就快速淡下去（standard），新字稍后淡入：两段文字几乎不同时可见，也不留空档。
            fadeIn(tween(MovoMotion.FAST, delayMillis = MovoMotion.FAST_EXIT / 3, easing = MovoMotion.EasingEnter)) togetherWith
                fadeOut(tween(MovoMotion.FAST_EXIT, easing = MovoMotion.EasingStandard))
        },
        contentAlignment = Alignment.CenterStart,
        label = "fetchStatus",
    ) { (value, error) ->
        Text(
            text = value,
            style = MovoTypography.labelRegular,
            color = if (error) MovoColors.textPrimary else MovoColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
