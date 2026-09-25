package io.github.mangi.eta.ui.screens.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.diagnostics.EventTone
import io.github.mangi.eta.diagnostics.SystemEvent
import io.github.mangi.eta.diagnostics.SystemEventKind
import io.github.mangi.eta.diagnostics.TraceStatus
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoRadius
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoSpacing
import io.github.mangi.eta.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/*
 * 运行日志页面的组件，对应 Figma「设计规范 v1 → 08 · 组件」：
 * TopBar/Secondary、Settings/Row（Value）、Chip/Filter、Run/Summary、Log/TimeBreakdown、Work/Step、Run/StepDetail。
 */

/** 二级页骨架：居中标题的 56 顶栏 + 画布底色上的列表，内容按 20 的页边线排布。 */
@Composable
internal fun LogPage(
    title: String,
    onBack: () -> Unit,
    action: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MovoColors.bgCanvas)
            .statusBarsPadding(),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(56.dp)) {
            TopBarIcon(Icons.Rounded.ChevronLeft, "返回", onBack, Modifier.align(Alignment.CenterStart).padding(start = 4.dp))
            Text(
                text = title,
                style = MovoTypography.bodyStrong,
                color = MovoColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 64.dp)
                    .semantics { heading() },
            )
            if (action != null) Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)) { action() }
        }
        val navigation = WindowInsets.navigationBars.asPaddingValues()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = MovoSpacing.pageEdge,
                end = MovoSpacing.pageEdge,
                top = MovoSpacing.sm,
                bottom = navigation.calculateBottomPadding() + MovoSpacing.section,
            ),
            content = content,
        )
    }
}

@Composable
internal fun TopBarIcon(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MovoColors.textPrimary)
    }
}

/** 页面说明，与卡片左边对齐。 */
@Composable
internal fun PageDescription(text: String) {
    Text(
        text,
        style = MovoTypography.labelRegular.copy(fontSize = MovoTypography.bodyRegular.fontSize, lineHeight = MovoTypography.bodyRegular.lineHeight),
        color = MovoColors.textSecondary,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 分组标题：上 24、下 8，文字对齐内容线 36。 */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MovoTypography.labelMedium,
        color = MovoColors.textSecondary,
        modifier = Modifier
            .padding(start = MovoSpacing.lg, top = MovoSpacing.xxl, bottom = MovoSpacing.sm)
            .semantics { heading() },
    )
}

/** 列表页卡片：白底、28 圆角、0.5 发丝线、无阴影。 */
@Composable
internal fun LogCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(MovoRadius.xl)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MovoColors.bgSurface)
            .border(MovoSize.hairline, MovoColors.borderHairline, shape),
        content = content,
    )
}

/** 卡片下方的补充说明，对齐内容线。 */
@Composable
internal fun CardNote(text: String) {
    Text(
        text,
        style = MovoTypography.labelRegular,
        color = MovoColors.textTertiary,
        modifier = Modifier.padding(start = MovoSpacing.lg, end = MovoSpacing.lg, top = MovoSpacing.sm),
    )
}

/** Chip/Filter：40 高、全圆角；选中为 Indigo 浅底 + Indigo 字，未选中为描边。 */
@Composable
internal fun LogFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = Modifier
            .height(MovoSize.controlMedium)
            .clip(shape)
            .then(if (selected) Modifier.background(MovoColors.indigoBg) else Modifier.border(1.dp, MovoColors.borderStrong, shape))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = MovoSpacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MovoTypography.labelMedium, color = if (selected) MovoColors.indigoFg else MovoColors.textSecondary)
    }
}

/** Button/Pill：32 高的次要按钮。 */
@Composable
internal fun LogPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(MovoSize.controlSmall)
            .clip(RoundedCornerShape(percent = 50))
            .background(MovoColors.bgSurfaceMuted)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MovoTypography.labelMedium, color = MovoColors.textPrimary, maxLines = 1)
    }
}

/** 状态图标：颜色之外同时用形状区分，颜色不是唯一信号。 */
@Composable
internal fun StatusIcon(status: TraceStatus, size: Dp = MovoSize.iconMedium) {
    val label = when (status) {
        TraceStatus.RUNNING -> "进行中"
        TraceStatus.SUCCEEDED -> "完成"
        TraceStatus.FAILED -> "失败"
        TraceStatus.CANCELLED -> "已停止"
        TraceStatus.INTERRUPTED -> "中断"
    }
    val modifier = Modifier.size(size).semantics { contentDescription = label }
    when (status) {
        TraceStatus.RUNNING -> CircularProgressIndicator(
            modifier = modifier.padding(size / 10),
            color = MovoColors.indigoFg,
            strokeWidth = 1.6.dp,
        )
        TraceStatus.SUCCEEDED -> Icon(Icons.Rounded.Check, null, modifier, tint = MovoColors.greenFg)
        TraceStatus.FAILED -> Icon(Icons.Rounded.Close, null, modifier, tint = MovoColors.roseFg)
        TraceStatus.CANCELLED -> Icon(Icons.Rounded.Stop, null, modifier, tint = MovoColors.textSecondary)
        TraceStatus.INTERRUPTED -> Icon(Icons.Rounded.ErrorOutline, null, modifier, tint = MovoColors.amberFg)
    }
}

internal fun SystemEvent.icon(): ImageVector = when (kind) {
    SystemEventKind.PROCESS, SystemEventKind.POWER -> Icons.Rounded.PowerSettingsNew
    SystemEventKind.APP, SystemEventKind.SCREEN -> Icons.Rounded.PhoneAndroid
    SystemEventKind.NETWORK -> if (title == "网络断开") Icons.Rounded.WifiOff else Icons.Rounded.Wifi
    SystemEventKind.SERVICE -> if (tone == EventTone.ERROR) Icons.Rounded.GppMaybe else Icons.Rounded.Memory
    SystemEventKind.MODEL -> Icons.Rounded.Language
    SystemEventKind.OTHER -> Icons.Rounded.ErrorOutline
}

internal fun EventTone.color(): Color = when (this) {
    EventTone.NORMAL -> MovoColors.textTertiary
    EventTone.WARNING -> MovoColors.amberFg
    EventTone.ERROR -> MovoColors.roseFg
}

internal val CompactionIcon: ImageVector get() = Icons.Rounded.Compress

/** Settings/Row（Value）：图标 + 标题 / 副标题 + 右侧数值 + 箭头。 */
@Composable
internal fun LogRow(
    title: String,
    subtitle: String,
    value: String,
    showDivider: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = MovoSpacing.lg, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(MovoSize.iconMedium), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(MovoSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MovoTypography.bodyRegular, color = MovoColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MovoTypography.labelRegular, color = MovoColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(MovoSpacing.sm))
            Text(value, style = MovoTypography.numericLabel.copy(fontWeight = FontWeight.Normal), color = MovoColors.textSecondary)
            Spacer(Modifier.width(MovoSpacing.xs))
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(MovoSize.iconSmall), tint = MovoColors.textTertiary)
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 48.dp, end = MovoSpacing.lg)
                    .fillMaxWidth()
                    .height(MovoSize.hairline)
                    .background(MovoColors.borderHairline),
            )
        }
    }
}

/** Run/Summary：结论一行 + 起止时间；需要时底部加一行提示和操作。 */
@Composable
internal fun SummaryCard(
    status: TraceStatus,
    statusText: String,
    timer: String,
    meta: String,
    footer: String? = null,
    footerAction: Pair<String, () -> Unit>? = null,
) {
    LogCard {
        Column(modifier = Modifier.padding(MovoSpacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIcon(status, MovoSize.iconSmall)
                Spacer(Modifier.width(MovoSpacing.sm))
                Text(
                    statusText,
                    style = MovoTypography.bodyStrong,
                    color = MovoColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(MovoSpacing.sm))
                Text(timer, style = MovoTypography.numericLabel.copy(fontWeight = FontWeight.Normal), color = MovoColors.textSecondary)
            }
            Text(
                meta,
                style = MovoTypography.labelRegular,
                color = MovoColors.textSecondary,
                modifier = Modifier.padding(start = MovoSize.iconSmall + MovoSpacing.sm),
            )
        }
        if (footer != null) {
            Box(
                Modifier
                    .padding(horizontal = MovoSpacing.lg)
                    .fillMaxWidth()
                    .height(MovoSize.hairline)
                    .background(MovoColors.borderHairline),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(footer, style = MovoTypography.labelRegular, color = MovoColors.textSecondary, modifier = Modifier.weight(1f))
                footerAction?.let { (label, onClick) ->
                    Spacer(Modifier.width(MovoSpacing.md))
                    LogPill(label, onClick)
                }
            }
        }
    }
}

/** 失败原因卡：原因（人话 + 错误码）、说明、建议与一个操作。 */
@Composable
internal fun ReasonCard(explanation: FailureExplanation, onAction: (FailureAction) -> Unit) {
    LogCard {
        Column(
            modifier = Modifier.padding(horizontal = MovoSpacing.xl, vertical = MovoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MovoSpacing.xs),
        ) {
            Text(explanation.title, style = MovoTypography.bodyStrong, color = MovoColors.textPrimary)
            Text(explanation.detail, style = MovoTypography.labelRegular, color = MovoColors.textSecondary)
            Row(modifier = Modifier.padding(top = MovoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Text(explanation.advice, style = MovoTypography.bodyRegular, color = MovoColors.textPrimary, modifier = Modifier.weight(1f))
                explanation.action?.let { action ->
                    Spacer(Modifier.width(MovoSpacing.md))
                    LogPill(action.label) { onAction(action) }
                }
            }
        }
    }
}

private fun BreakdownKind.color(): Color = when (this) {
    BreakdownKind.WAITING -> MovoColors.blueFg
    BreakdownKind.RETRY -> MovoColors.amberFg
    BreakdownKind.RECEIVING -> MovoColors.indigoFg
    BreakdownKind.TOOL -> MovoColors.graphiteFg
    BreakdownKind.COMPACTION -> MovoColors.greenFg
    BreakdownKind.OTHER -> MovoColors.textTertiary
}

/** Log/TimeBreakdown：分段条 + 每段一行；最久的一段加粗。 */
@Composable
internal fun BreakdownCard(parts: List<BreakdownPart>, format: DiagnosticsFormat) {
    val longest = parts.maxByOrNull { it.ms }
    val total = parts.sumOf { it.ms }.coerceAtLeast(1L)
    LogCard {
        Column(
            modifier = Modifier.padding(horizontal = MovoSpacing.xl, vertical = MovoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MovoSpacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                parts.forEach { part ->
                    Box(
                        Modifier
                            .weight((part.ms.toFloat() / total).coerceAtLeast(0.012f))
                            .fillMaxHeight()
                            .background(part.kind.color()),
                    )
                }
            }
            parts.forEach { part ->
                val strong = part == longest
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(part.kind.color()))
                    Spacer(Modifier.width(MovoSpacing.sm))
                    Text(
                        part.label,
                        style = if (strong) MovoTypography.labelMedium else MovoTypography.labelRegular,
                        color = if (strong) MovoColors.textPrimary else MovoColors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        format.compact(part.ms),
                        style = MovoTypography.numericLabel.copy(fontWeight = if (strong) FontWeight.Medium else FontWeight.Normal),
                        color = if (strong) MovoColors.textPrimary else MovoColors.textSecondary,
                    )
                }
            }
        }
    }
}

/**
 * Work/Step：时间线的一行。左侧 16 的图标，图标之间用 1 宽的连接线串起来；
 * [detail] 不为空时在行内展开 Run/StepDetail。
 */
@Composable
internal fun StepRow(
    title: String,
    subtitle: String,
    duration: String,
    first: Boolean,
    last: Boolean,
    muted: Boolean = false,
    detailLabel: String? = null,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
    icon: @Composable () -> Unit,
) {
    val lineColor = MovoColors.borderStrong
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .drawBehind {
                val x = 24.dp.toPx()
                val stroke = 1.dp.toPx()
                if (!first) drawLine(lineColor, Offset(x, 0f), Offset(x, 7.dp.toPx()), strokeWidth = stroke)
                if (!last) drawLine(lineColor, Offset(x, 31.dp.toPx()), Offset(x, size.height), strokeWidth = stroke)
            }
            .padding(start = MovoSpacing.lg, end = MovoSpacing.lg, top = 10.dp, bottom = 10.dp),
    ) {
        Row {
            Box(modifier = Modifier.padding(top = 1.dp).size(MovoSize.iconSmall), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(MovoSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MovoTypography.labelMedium, color = if (muted) MovoColors.textSecondary else MovoColors.textPrimary)
                if (subtitle.isNotEmpty()) Text(subtitle, style = MovoTypography.labelRegular, color = MovoColors.textSecondary)
            }
            if (duration.isNotEmpty()) {
                Spacer(Modifier.width(MovoSpacing.sm))
                Text(duration, style = MovoTypography.numericLabel.copy(fontWeight = FontWeight.Normal), color = MovoColors.textSecondary)
            }
        }
        if (detail != null) {
            Column(
                modifier = Modifier
                    .padding(start = MovoSize.iconSmall + MovoSpacing.md, top = MovoSpacing.sm)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(MovoRadius.md))
                    .background(MovoColors.bgSurfaceMuted)
                    .padding(MovoSpacing.md),
            ) {
                detailLabel?.let { Text(it, style = MovoTypography.labelRegular, color = MovoColors.textSecondary) }
                SelectionContainer {
                    Text(detail, style = MovoTypography.labelRegular, color = MovoColors.textPrimary)
                }
            }
        }
    }
}

@Composable
internal fun StepIcon(icon: ImageVector, tint: Color) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(MovoSize.iconSmall), tint = tint)
}

/** 空状态：一句话说明，居中。 */
@Composable
internal fun EmptyHint(text: String) {
    Text(
        text,
        style = MovoTypography.labelRegular,
        color = MovoColors.textTertiary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
    )
}
