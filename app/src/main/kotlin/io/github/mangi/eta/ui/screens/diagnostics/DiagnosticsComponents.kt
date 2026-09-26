package io.github.mangi.eta.ui.screens.diagnostics

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.diagnostics.EventTone
import io.github.mangi.eta.diagnostics.SystemEvent
import io.github.mangi.eta.diagnostics.SystemEventKind
import io.github.mangi.eta.diagnostics.TraceStatus
import io.github.mangi.eta.ui.components.movo.CardTitle
import io.github.mangi.eta.ui.components.movo.MovoCard
import io.github.mangi.eta.ui.components.movo.MovoDivider
import io.github.mangi.eta.ui.components.movo.MovoPillButton
import io.github.mangi.eta.ui.components.movo.MovoSpinner
import io.github.mangi.eta.ui.components.movo.PressKind
import io.github.mangi.eta.ui.components.movo.movoClickable
import io.github.mangi.eta.ui.theme.LocalReducedMotion
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoIcon
import io.github.mangi.eta.ui.theme.MovoIconData
import io.github.mangi.eta.ui.theme.MovoIcons
import io.github.mangi.eta.ui.theme.MovoMotion
import io.github.mangi.eta.ui.theme.MovoRadius
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoSpacing
import io.github.mangi.eta.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text

/*
 * 运行日志页面的组件，对应 Figma「定稿 · 设计稿」22–27 与「设计规范 v1 → 08 · 组件」：
 * Chip/Filter、Run/Summary、Log/TimeBreakdown、Work/Step、Run/StepDetail。
 * 页面骨架、卡片、卡内标题、页脚、设置行、行内按钮直接用 ui/components/movo 的公共组件。
 */

/** Chip/Filter：高 40、圆角 20、左右 16；选中 = Indigo 浅底 + Indigo 字，未选中 = 1 宽 border/strong + 次要色字。 */
@Composable
internal fun LogFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(MovoRadius.lg)
    Box(
        modifier = Modifier
            .height(MovoSize.controlMedium)
            .movoClickable(PressKind.Solid, shape = shape, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .clip(shape)
            // 描边 1（Figma Chip/Filter），与发丝线 0.5 不同。
            .then(if (selected) Modifier.background(MovoColors.indigoBg) else Modifier.border(1.dp, MovoColors.borderStrong, shape))
            .padding(horizontal = MovoSpacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MovoTypography.labelMedium, color = if (selected) MovoColors.indigoFg else MovoColors.textSecondary, maxLines = 1)
    }
}

/** 状态图标：颜色之外同时用形状区分，颜色不是唯一信号。列表行 20、时间线 16。 */
@Composable
internal fun StatusIcon(status: TraceStatus, size: Dp = MovoSize.iconMedium) {
    val label = when (status) {
        TraceStatus.RUNNING -> "进行中"
        TraceStatus.SUCCEEDED -> "完成"
        TraceStatus.FAILED -> "失败"
        TraceStatus.CANCELLED -> "已停止"
        TraceStatus.INTERRUPTED -> "中断"
    }
    val modifier = Modifier.semantics { contentDescription = label }
    when (status) {
        TraceStatus.RUNNING -> MovoSpinner(modifier = modifier, size = size, color = MovoColors.indigoFg)
        TraceStatus.SUCCEEDED -> MovoIcon(MovoIcons.Check, null, modifier, size = size, tint = MovoColors.greenFg)
        TraceStatus.FAILED -> MovoIcon(MovoIcons.X, null, modifier, size = size, tint = MovoColors.roseFg)
        TraceStatus.CANCELLED -> MovoIcon(MovoIcons.Square, null, modifier, size = size, tint = MovoColors.textSecondary)
        TraceStatus.INTERRUPTED -> MovoIcon(MovoIcons.CircleAlert, null, modifier, size = size, tint = MovoColors.amberFg)
    }
}

/**
 * 结论卡的状态图标 16：进行中用小光球（规范 8.8 Run/Summary），其余与时间线相同。
 * 这里只需要 16 的静态品牌渐变 + 缓慢旋转；公共组件里还没有小光球，先在本页私有实现。
 */
@Composable
private fun SummaryStatusIcon(status: TraceStatus) {
    if (status == TraceStatus.RUNNING) {
        MiniOrb(MovoSize.iconSmall, Modifier.semantics { contentDescription = "进行中" })
    } else {
        StatusIcon(status, MovoSize.iconSmall)
    }
}

@Composable
private fun MiniOrb(size: Dp, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val rotation = if (reduced) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "miniOrb")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(MovoMotion.MINI_ORB_GRADIENT_PERIOD, easing = MovoMotion.EasingLinear)),
            label = "miniOrbRotation",
        )
        value
    }
    val colors = MovoColors.brandGradient + MovoColors.brandGradient.first()
    Canvas(modifier = modifier.size(size)) {
        rotate(rotation) { drawCircle(Brush.sweepGradient(colors)) }
        // 左上高光，让它读作球体而不是色环。
        drawCircle(
            Brush.radialGradient(
                listOf(MovoColors.bgSurface.copy(alpha = 0.6f), Color.Transparent),
                center = Offset(this.size.width * 0.35f, this.size.height * 0.3f),
                radius = this.size.minDimension * 0.5f,
            ),
        )
    }
}

internal fun SystemEvent.icon(): MovoIconData = when (kind) {
    SystemEventKind.PROCESS, SystemEventKind.POWER -> MovoIcons.Power
    SystemEventKind.APP, SystemEventKind.SCREEN -> MovoIcons.Smartphone
    SystemEventKind.NETWORK -> MovoIcons.Globe
    SystemEventKind.SERVICE -> if (tone == EventTone.ERROR) MovoIcons.ShieldAlert else MovoIcons.Cpu
    SystemEventKind.MODEL -> MovoIcons.Globe
    SystemEventKind.OTHER -> MovoIcons.CircleAlert
}

internal fun EventTone.color(): Color = when (this) {
    EventTone.NORMAL -> MovoColors.textSecondary
    EventTone.WARNING -> MovoColors.amberFg
    EventTone.ERROR -> MovoColors.roseFg
}

/** 上下文压缩的图标（Lucide 没有现成的 shrink，暂用 layers）。 */
internal val CompactionIcon: MovoIconData get() = MovoIcons.Layers

/**
 * Run/Summary：状态图标 16 + 12 + 结论 Body/Strong + 右侧计时 Numeric/Label；第二行对齐卡内 44（开始·结束·R 编号）。
 * [footer] 只在进行中且 30 秒没有收到数据时传入（规范 8.10）：0.5 分隔线 + 提示 + 「回到对话」。
 */
@Composable
internal fun SummaryCard(
    status: TraceStatus,
    statusText: String,
    timer: String,
    meta: String,
    footer: String? = null,
    footerAction: Pair<String, () -> Unit>? = null,
) {
    MovoCard(bottomPadding = 0.dp) {
        Column(
            modifier = Modifier.padding(MovoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MovoSpacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SummaryStatusIcon(status)
                Spacer(Modifier.width(MovoSpacing.md))
                Text(
                    statusText,
                    style = MovoTypography.bodyStrong,
                    color = MovoColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(MovoSpacing.md))
                Text(timer, style = MovoTypography.numericLabel, color = MovoColors.textSecondary, maxLines = 1)
            }
            Text(
                meta,
                style = MovoTypography.labelRegular,
                color = MovoColors.textSecondary,
                modifier = Modifier.padding(start = MovoSize.iconSmall + MovoSpacing.md),
            )
        }
        if (footer != null) {
            MovoDivider(start = MovoSpacing.lg)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = MovoSpacing.lg, end = MovoSpacing.md, top = MovoSpacing.md, bottom = MovoSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(footer, style = MovoTypography.labelRegular, color = MovoColors.textSecondary, modifier = Modifier.weight(1f))
                footerAction?.let { (label, onClick) ->
                    Spacer(Modifier.width(MovoSpacing.md))
                    MovoPillButton(label = label, onClick = onClick)
                }
            }
        }
    }
}

/** 「原因」卡：原因标题 Body/Strong + 说明 13 次要色 → 16 → 建议 Body/Regular + 行内按钮（如「去模型设置」）。 */
@Composable
internal fun ReasonCard(explanation: FailureExplanation, onAction: (FailureAction) -> Unit) {
    MovoCard {
        CardTitle("原因")
        Column(
            modifier = Modifier.padding(start = MovoSpacing.lg, end = MovoSpacing.lg, top = MovoSpacing.xs, bottom = MovoSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MovoSpacing.xs),
        ) {
            Text(explanation.title, style = MovoTypography.bodyStrong, color = MovoColors.textPrimary)
            Text(explanation.detail, style = MovoTypography.labelRegular, color = MovoColors.textSecondary)
            Row(
                modifier = Modifier.padding(top = MovoSpacing.md).heightIn(min = MovoSize.controlSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(explanation.advice, style = MovoTypography.bodyRegular, color = MovoColors.textPrimary, modifier = Modifier.weight(1f))
                explanation.action?.let { action ->
                    Spacer(Modifier.width(MovoSpacing.md))
                    MovoPillButton(label = action.label, onClick = { onAction(action) })
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
    // Figma Log/TimeBreakdown：上下文压缩同为 Graphite，靠图例文字区分。
    BreakdownKind.COMPACTION -> MovoColors.graphiteFg
    BreakdownKind.OTHER -> MovoColors.textTertiary
}

/** 「耗时」卡（Log/TimeBreakdown）：8 高分段条 + 每段一行（色点、名称、时长）；最久的一段 Medium 主色。 */
@Composable
internal fun BreakdownCard(parts: List<BreakdownPart>, format: DiagnosticsFormat) {
    val longest = parts.maxByOrNull { it.ms }
    val total = parts.sumOf { it.ms }.coerceAtLeast(1L)
    MovoCard {
        CardTitle("耗时")
        Column(
            modifier = Modifier.padding(start = MovoSpacing.xl, end = MovoSpacing.xl, top = MovoSpacing.xs, bottom = MovoSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MovoSpacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(MovoSpacing.sm).clip(RoundedCornerShape(MovoSpacing.xs)),
                horizontalArrangement = Arrangement.spacedBy(MovoSpacing.xxs),
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
                    Box(Modifier.size(MovoSpacing.sm).clip(CircleShape).background(part.kind.color()))
                    Spacer(Modifier.width(MovoSpacing.sm))
                    Text(
                        part.label,
                        style = if (strong) MovoTypography.labelMedium else MovoTypography.labelRegular,
                        color = if (strong) MovoColors.textPrimary else MovoColors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        format.compact(part.ms),
                        style = MovoTypography.numericLabel,
                        color = if (strong) MovoColors.textPrimary else MovoColors.textSecondary,
                    )
                }
            }
        }
    }
}

/**
 * Work/Step：时间线的一行。行内边距 16 / 10，状态图标 16 → 12 → 标题 13 Medium + 说明 13 次要色（间距 2），
 * 右侧时长 Numeric/Label 三级色；图标之间用 1 宽 border/strong 连接线串起来（x = 24，图标上下各留约 4）。
 * [detail] 不为空时在文字列（卡内 44）下方展开 Run/StepDetail。[muted] 用于设备事件：标题也用次要色。
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
            .then(if (onClick != null) Modifier.movoClickable(PressKind.Row, onClick = onClick) else Modifier)
            .drawBehind {
                val x = (MovoSpacing.lg + MovoSize.iconSmall / 2).toPx()
                val stroke = 1.dp.toPx()
                // 图标在行内 11–27：上段画到图标上方 4，下段从图标下方 4 开始（Figma connector）。
                if (!first) drawLine(lineColor, Offset(x, 0f), Offset(x, 7.dp.toPx()), strokeWidth = stroke)
                if (!last) drawLine(lineColor, Offset(x, 31.dp.toPx()), Offset(x, size.height), strokeWidth = stroke)
            }
            .padding(horizontal = MovoSpacing.lg, vertical = 10.dp),
    ) {
        Row {
            Box(modifier = Modifier.padding(top = 1.dp).size(MovoSize.iconSmall), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(MovoSpacing.md))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MovoSpacing.xxs)) {
                Text(title, style = MovoTypography.labelMedium, color = if (muted) MovoColors.textSecondary else MovoColors.textPrimary)
                if (subtitle.isNotEmpty()) Text(subtitle, style = MovoTypography.labelRegular, color = MovoColors.textSecondary)
            }
            if (duration.isNotEmpty()) {
                Spacer(Modifier.width(MovoSpacing.sm))
                Text(duration, style = MovoTypography.numericLabel, color = MovoColors.textTertiary, maxLines = 1)
            }
        }
        if (detail != null) {
            Column(
                modifier = Modifier
                    .padding(start = MovoSize.iconSmall + MovoSpacing.md, top = MovoSpacing.sm + MovoSpacing.xxs)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(MovoRadius.sm))
                    .background(MovoColors.bgSurfaceMuted)
                    .padding(MovoSpacing.md),
                verticalArrangement = Arrangement.spacedBy(MovoSpacing.xs),
            ) {
                detailLabel?.let { Text(it, style = MovoTypography.labelMedium, color = MovoColors.textSecondary) }
                SelectionContainer {
                    Text(detail, style = MovoTypography.labelRegular, color = MovoColors.textPrimary)
                }
            }
        }
    }
}

@Composable
internal fun StepIcon(icon: MovoIconData, tint: Color) {
    MovoIcon(icon, contentDescription = null, size = MovoSize.iconSmall, tint = tint)
}

/** 空状态：放在卡片里的一两句说明，居中（卡片外不放文字）。 */
@Composable
internal fun EmptyHint(vararg lines: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        lines.forEach { line ->
            Text(line, style = MovoTypography.labelRegular, color = MovoColors.textSecondary, textAlign = TextAlign.Center)
        }
    }
}
