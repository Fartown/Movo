package io.github.fartown.movo.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.model.AgentContextBudget
import io.github.fartown.movo.ui.components.movo.MovoDivider
import io.github.fartown.movo.ui.components.movo.MovoPopover
import io.github.fartown.movo.ui.components.movo.MovoPopoverGroupHeader
import io.github.fartown.movo.ui.components.movo.MovoPopoverItem
import io.github.fartown.movo.ui.components.movo.MovoSpinner
import io.github.fartown.movo.ui.components.movo.PressKind
import io.github.fartown.movo.ui.components.movo.movoClickable
import io.github.fartown.movo.ui.model.AgentContextUsageUi
import io.github.fartown.movo.ui.model.AgentModelPickerUiState
import io.github.fartown.movo.ui.model.defaultExpandedModelProviderIds
import io.github.fartown.movo.ui.model.formatContextTokenCount
import io.github.fartown.movo.ui.model.formatContextUsage
import io.github.fartown.movo.ui.model.formatContextUsagePercent
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

/**
 * 模型按钮（规范 8 组件表）：40 浅底圆，内放 22 品牌 Logo（圆形裁切 + 0.5 描边），不显示模型名；
 * 点击弹出按服务商分组的模型列表（`Popover/Menu`，出现在输入框上方）。
 * 选中：新 ✓ 先出现，停留 160ms 再关闭菜单（9.3.1「单选」，同 `MovoChoiceDialog`）；
 * 关闭后按钮里的 Logo 交叉淡化 + 缩放 0.72 → 1，`fast`（9.3.1「模型切换」）。
 */
@Composable
internal fun AgentModelPickerButton(
    state: AgentModelPickerUiState,
    isStreaming: Boolean,
    popupAnchorTopPx: Int,
    popupMaxHeight: Dp,
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPopup by remember { mutableStateOf(false) }
    var expandedProviderIds by remember { mutableStateOf(emptySet<String>()) }
    // 刚点选、还没交给业务层的模型：✓ 先落到它上面，停留后再关菜单并切换。
    var pendingModelId by remember { mutableStateOf<String?>(null) }
    val selected = state.selectedModel
    val enabled = !isStreaming && !state.isChanging && state.providerGroups.isNotEmpty()
    val reduced = LocalReducedMotion.current
    LaunchedEffect(enabled) {
        if (!enabled) showPopup = false
    }
    LaunchedEffect(pendingModelId) {
        val chosen = pendingModelId ?: return@LaunchedEffect
        delay(MovoMotion.FAST.toLong() + MovoMotion.MENU_CLOSE_DELAY)
        showPopup = false
        onModelSelected(chosen)
    }
    val currentModel = selected?.displayName ?: stringResource(R.string.model_not_selected)
    val switchModelDescription = stringResource(R.string.model_switch_current, currentModel)
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(ChatInputActionSize)
                .movoClickable(
                    PressKind.Solid,
                    shape = CircleShape,
                    enabled = enabled,
                    onClick = {
                        // 菜单开着时再点按钮是关闭（点按钮本身不算「点外面」，菜单不会自己关）。
                        if (!showPopup) {
                            pendingModelId = null
                            expandedProviderIds = defaultExpandedModelProviderIds(state.selectedModel)
                        }
                        showPopup = !showPopup
                    },
                )
                // 没有可用模型（7.3）：按禁用态整体 40%，由输入框上方的提示条引导去配置。
                .graphicsLayer { alpha = if (state.missingModel) 0.4f else 1f }
                .clip(CircleShape)
                .background(MovoColors.bgSurfaceMuted)
                .semantics { contentDescription = switchModelDescription },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = selected?.let { it.modelId to it.providerSourceType },
                transitionSpec = {
                    if (reduced) {
                        fadeIn(MovoMotion.fast()) togetherWith fadeOut(MovoMotion.fastExit())
                    } else {
                        (fadeIn(MovoMotion.fast()) + scaleIn(MovoMotion.fast(), initialScale = 0.72f)) togetherWith
                            (fadeOut(MovoMotion.fastExit()) + scaleOut(MovoMotion.fastExit(), targetScale = 0.72f))
                    }.using(SizeTransform(clip = false))
                },
                contentAlignment = Alignment.Center,
                label = "modelLogo",
            ) { model ->
                ModelBrandMark(
                    modelId = model?.first,
                    sourceType = model?.second,
                    size = 22.dp,
                )
            }
        }

        MovoPopover(
            show = showPopup && popupAnchorTopPx > 0,
            onDismiss = { showPopup = false },
            aboveYPx = popupAnchorTopPx,
            alignEnd = true,
            maxHeight = popupMaxHeight,
            width = rememberModelPopoverWidth(state),
        ) {
            ModelPickerPopupContent(
                state = state,
                selectedModelId = pendingModelId ?: state.selectedModel?.id,
                expandedProviderIds = expandedProviderIds,
                onProviderExpandedChange = { providerId, expanded ->
                    expandedProviderIds = if (expanded) {
                        expandedProviderIds + providerId
                    } else {
                        expandedProviderIds - providerId
                    }
                },
                onModelSelected = { modelId -> if (pendingModelId == null) pendingModelId = modelId },
            )
        }
    }
}

/**
 * 模型菜单宽度按所有模型名（含折叠分组里的）中最宽的一项定，展开 / 收起分组时菜单不跳宽；
 * 最小 236（原列表最小宽），最大 320，更长的名字一行省略。
 */
@Composable
private fun rememberModelPopoverWidth(state: AgentModelPickerUiState): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(state.providerGroups, density) {
        val names = state.providerGroups.flatMap { group -> group.models.map { it.displayName } }
        // 首页每次重建（从设置返回、切换会话）都会走到这里；服务商拉取后可能有上百个模型名，
        // 逐个排版量宽要几毫秒（真机 trace：返回首页那一帧里占 3ms+）。同一批名字在进程内只量一次。
        val key = ModelPopoverWidthKey(names, density.density, density.fontScale)
        modelPopoverWidthCache?.takeIf { it.first == key }?.second ?: run {
            val widestPx = names.maxOfOrNull { name ->
                measurer.measure(name, MovoTypography.bodyRegular, maxLines = 1).size.width
            } ?: 0
            // 文字 + 行内边距 12 × 2 + ✓ 20 与间距 8 + 菜单内边距 8 × 2。
            val chrome = MovoSpacing.md * 2 + MovoSize.iconMedium + MovoSpacing.sm + MovoSpacing.sm * 2
            (with(density) { widestPx.toDp() } + chrome).coerceIn(ModelPopoverMinWidth, ModelPopoverMaxWidth)
                .also { modelPopoverWidthCache = key to it }
        }
    }
}

private data class ModelPopoverWidthKey(val names: List<String>, val density: Float, val fontScale: Float)

/** 上一次量出的模型菜单宽度（主线程读写）。 */
private var modelPopoverWidthCache: Pair<ModelPopoverWidthKey, Dp>? = null

private val ModelPopoverMinWidth = 236.dp
private val ModelPopoverMaxWidth = 320.dp

@Composable
private fun ModelPickerPopupContent(
    state: AgentModelPickerUiState,
    selectedModelId: String?,
    expandedProviderIds: Set<String>,
    onProviderExpandedChange: (String, Boolean) -> Unit,
    onModelSelected: (String) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    state.providerGroups.forEachIndexed { groupIndex, group ->
        if (groupIndex > 0) {
            MovoDivider(
                start = MovoSpacing.md,
                end = MovoSpacing.md,
                modifier = Modifier.padding(vertical = MovoSpacing.xs),
            )
        }
        val expanded = group.providerId in expandedProviderIds
        MovoPopoverGroupHeader(
            title = group.providerName,
            expanded = expanded,
            onToggle = { onProviderExpandedChange(group.providerId, !expanded) },
            toggleDescription = if (expanded) {
                stringResource(R.string.model_collapse_provider, group.providerName)
            } else {
                stringResource(R.string.model_expand_provider, group.providerName)
            },
        )
        // 分组展开 / 收起（规范 9.3）：高度 `standard`，内容在高度开始 40ms 后淡入 `fast`；收起时淡出 120ms、高度同时收起。
        AnimatedVisibility(
            visible = expanded,
            enter = if (reduced) {
                fadeIn(MovoMotion.fast())
            } else {
                fadeIn(tween(MovoMotion.FAST, delayMillis = MovoMotion.STAGGER, easing = MovoMotion.EasingStandard)) +
                    expandVertically(MovoMotion.standard())
            },
            exit = if (reduced) {
                fadeOut(MovoMotion.fastExit())
            } else {
                fadeOut(MovoMotion.fastExit()) + shrinkVertically(MovoMotion.standard())
            },
        ) {
            Column {
                group.models.forEach { model ->
                    MovoPopoverItem(
                        label = model.displayName,
                        selected = model.id == selectedModelId,
                        onClick = { onModelSelected(model.id) },
                    )
                }
            }
        }
    }
}

/**
 * 上下文用量 `Button/ContextUsage`（规范 8.1）：40 浅底圆，内含 20 用量表（外圈 1.5 描边 + 扇形表示已用比例）；
 * ≥ 80% 改为 Amber。弧长过渡 `standard`（9.3「进度数值」）。
 *
 * 点击弹出 `Popover/ContextUsage`（2026-09-26 定稿方案 1「菜单行」）：宽 280，与 `Popover/Menu` 同一表面，出现在输入框上方、
 * 右缘对齐。信息区：「上下文用量」→ 大号百分比（只出现这一次）+「已用 19K / 1.05M」基线对齐 → 6 高用量条（最少 6 宽圆头，
 * 颜色与外面的用量表一致）→ 提示句（自动压缩阈值取 [AgentContextBudget.TRIGGER_RATIO]；≥ 80% 改为 Amber「快满了」）。
 * 可压缩时下面是分隔线 + 44 高菜单行「立即压缩」；点了不关浮层，[isCompacting] 期间菜单行换成转圈「正在压缩…」、不可点，
 * 提示改为「正在把较早的对话整理成摘要…」。
 * 没有用量数据时只写「完成第一次回复后显示用量」，没有分隔线和压缩行；模型没给上限时百分比位置显示已用量，不画用量条。
 */
@Composable
internal fun AgentContextUsageButton(
    usage: AgentContextUsageUi,
    onCompact: () -> Unit = {},
    canCompact: Boolean = false,
    isCompacting: Boolean = false,
    modifier: Modifier = Modifier,
    popupAnchorTopPx: Int = 0,
) {
    var showPopover by remember { mutableStateOf(false) }
    val progress = usage.progress
    val warn = (progress ?: 0f) >= ContextUsageWarnThreshold
    val meterColor = if (warn) MovoColors.amberFg else MovoColors.textPrimary
    val locale = LocalConfiguration.current.locales[0]
    val summary = formatContextUsage(
        usage = usage,
        noUsageText = stringResource(R.string.context_no_previous_usage),
        noLimitText = stringResource(R.string.context_no_model_limit),
        locale = locale,
    )
    val usageDescription = stringResource(
        R.string.context_usage_description,
        summary.replace('\n', ' '),
    )
    val animatedProgress by animateFloatAsState(
        targetValue = (progress ?: 0f).coerceIn(0f, 1f),
        animationSpec = MovoMotion.standard(),
        label = "contextUsage",
    )
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(ChatInputActionSize)
                .movoClickable(
                    PressKind.Solid,
                    shape = CircleShape,
                    onClick = { showPopover = !showPopover },
                )
                .clip(CircleShape)
                .background(MovoColors.bgSurfaceMuted)
                .semantics { contentDescription = usageDescription },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(MovoSize.iconMedium)) {
                val stroke = 1.5.dp.toPx()
                val radius = size.minDimension / 2 - stroke / 2
                drawCircle(meterColor, radius = radius, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                val inner = radius - stroke - 1.dp.toPx()
                drawArc(
                    color = meterColor,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedProgress,
                    useCenter = true,
                    topLeft = androidx.compose.ui.geometry.Offset(center.x - inner, center.y - inner),
                    size = androidx.compose.ui.geometry.Size(inner * 2, inner * 2),
                )
            }
        }

        MovoPopover(
            show = showPopover,
            onDismiss = { showPopover = false },
            aboveYPx = popupAnchorTopPx,
            alignEnd = true,
            width = ContextPopoverWidth,
        ) {
            val tokens = usage.contextTokens
            // 没有用量数据时不给压缩入口；压缩中保留这一行（转圈），完成后按 canCompact 决定。
            val showCompactRow = tokens != null && (isCompacting || canCompact)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = MovoSpacing.md,
                        end = MovoSpacing.md,
                        top = MovoSpacing.md,
                        bottom = if (showCompactRow) MovoSpacing.sm else MovoSpacing.md,
                    ),
                verticalArrangement = Arrangement.spacedBy(MovoSpacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.ui_contextual_usage_d12810),
                    style = MovoTypography.labelMedium,
                    color = MovoColors.textSecondary,
                )
                if (tokens == null) {
                    Text(
                        text = stringResource(R.string.context_usage_after_response),
                        style = MovoTypography.bodyRegular,
                        color = MovoColors.textTertiary,
                    )
                } else {
                    val percent = formatContextUsagePercent(usage, locale)
                    Row {
                        // 模型没给上限：百分比的位置显示已用量。
                        Text(
                            text = percent ?: formatContextTokenCount(tokens, locale),
                            style = ContextPercentStyle,
                            color = if (percent != null) meterColor else MovoColors.textPrimary,
                            modifier = Modifier.alignByBaseline(),
                        )
                        val window = usage.contextWindow
                        if (percent != null && window != null) {
                            Spacer(Modifier.width(MovoSpacing.sm))
                            Text(
                                text = stringResource(
                                    R.string.context_used_of_limit,
                                    formatContextTokenCount(tokens, locale),
                                    formatContextTokenCount(window, locale),
                                ),
                                style = MovoTypography.labelRegular,
                                color = MovoColors.textSecondary,
                                maxLines = 1,
                                modifier = Modifier.alignByBaseline(),
                            )
                        }
                    }
                    if (progress != null) ContextUsageBar(animatedProgress, meterColor)
                    val triggerPercent = (AgentContextBudget.TRIGGER_RATIO * 100).roundToInt()
                    Text(
                        text = when {
                            isCompacting -> stringResource(R.string.context_compacting_hint)
                            progress == null -> stringResource(R.string.context_no_model_limit)
                            warn -> stringResource(R.string.context_auto_compact_hint_warn, triggerPercent)
                            else -> stringResource(R.string.context_auto_compact_hint, triggerPercent)
                        },
                        style = MovoTypography.labelRegular,
                        color = if (warn && !isCompacting) MovoColors.amberFg else MovoColors.textTertiary,
                    )
                }
            }
            if (showCompactRow) {
                MovoDivider(
                    modifier = Modifier.padding(vertical = MovoSpacing.xs),
                    start = MovoSpacing.md,
                    end = MovoSpacing.md,
                )
                if (isCompacting) {
                    ContextCompactingRow()
                } else {
                    // 不关浮层：压缩开始后同一处换成「正在压缩…」。
                    MovoPopoverItem(
                        label = stringResource(R.string.context_compact_action),
                        icon = MovoIcons.FoldVertical,
                        onClick = onCompact,
                    )
                }
            }
        }
    }
}

/** 用量条：高 6、圆角 3，底 `bg/surface-muted`；已用部分最少显示 6 宽圆头（用量很低时也看得见）。 */
@Composable
private fun ContextUsageBar(progress: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(ContextBarHeight)
            .clip(CircleShape)
            .background(MovoColors.bgSurfaceMuted),
    ) {
        Box(
            Modifier
                .widthIn(min = ContextBarHeight)
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(ContextBarHeight)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/** 压缩中的菜单行：与 `Popover/Menu` 项同高同排（44、左右 12、图标 20 + 间距 12），转圈 +「正在压缩…」三级色，不可点。 */
@Composable
private fun ContextCompactingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MovoSize.touchTarget)
            .padding(horizontal = MovoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MovoSpinner(size = MovoSize.iconMedium, color = MovoColors.textTertiary)
        Spacer(Modifier.width(MovoSpacing.md))
        Text(
            text = stringResource(R.string.context_compacting),
            style = MovoTypography.bodyRegular,
            color = MovoColors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 规范 8.1：用量 ≥ 80% 改为 Amber（只有这一档）。 */
private const val ContextUsageWarnThreshold = 0.80f
private val ContextPopoverWidth = 280.dp
private val ContextBarHeight = 6.dp

/** 浮层里的大号百分比：Inter Semi Bold 28 / 36，等宽数字。 */
private val ContextPercentStyle = MovoTypography.numericLabel.copy(
    fontSize = 28.sp,
    lineHeight = 36.sp,
    fontWeight = FontWeight.SemiBold,
)

/** 品牌 Logo（规范 6）：圆形裁切；没有对应 Logo 时为 Indigo 浅底圆 + Cpu 图标（Agent 与 AI）。 */
@Composable
private fun ModelBrandMark(
    modelId: String?,
    sourceType: String?,
    size: Dp,
) {
    val logo = modelOrProviderBrandLogoRes(modelId, sourceType)
    if (logo != null) {
        Image(
            painter = painterResource(logo),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .background(MovoColors.indigoBg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MovoIcon(MovoIcons.Cpu, null, size = MovoSize.iconLabel, tint = MovoColors.indigoFg)
        }
    }
}
