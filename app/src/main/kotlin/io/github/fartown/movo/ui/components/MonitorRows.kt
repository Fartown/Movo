package io.github.fartown.movo.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import io.github.fartown.movo.ui.components.movo.PressKind
import io.github.fartown.movo.ui.components.movo.movoClickable
import io.github.fartown.movo.ui.components.movo.trackVisibleHeightCap
import io.github.fartown.movo.ui.model.MonitorEventKindUi
import io.github.fartown.movo.ui.model.MonitorEventMessageUi
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 后台监听在对话里的一行（规范 8.12，Figma「18」）：与「已思考」行内一行同一写法，左对齐 20、高 32、无底色。
 * 事件行：琥珀 clock 14 +「监听事件·名称·时间」`Label/Medium` 次要色 + ⌄，点开显示这次事件的原文（左竖线 + 三级色）。
 * 结束行：clock 改三级色、无 ⌄（已停止 / 结束 / 中断）。
 */
@Composable
internal fun MonitorEventRow(
    message: MonitorEventMessageUi,
    modifier: Modifier = Modifier,
) {
    val event = message.kind == MonitorEventKindUi.Event
    val expandable = event && message.text.isNotBlank()
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.atMillis))
    val label = if (event) {
        stringResource(R.string.monitor_row_event, message.name, time)
    } else {
        when (message.reason) {
            "STOPPED_BY_USER" -> stringResource(R.string.monitor_row_stopped, message.name, time)
            "TIMEOUT" -> stringResource(R.string.monitor_row_timeout, message.name, monitorDurationLabel(message.text.toLongOrNull() ?: 0L))
            "RATE_LIMIT" -> stringResource(R.string.monitor_row_rate_limit, message.name)
            "INTERRUPTED" -> stringResource(R.string.monitor_row_interrupted, message.name)
            else -> stringResource(R.string.monitor_row_exit, message.name, time)
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .height(32.dp)
                .then(if (expandable) Modifier.movoClickable(PressKind.Link) { expanded = !expanded } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MovoIcon(
                MovoIcons.Clock,
                contentDescription = null,
                size = 14.dp,
                tint = if (event) MovoColors.amberFg else MovoColors.textTertiary,
            )
            Spacer(Modifier.width(6.dp))
            Text(text = label, style = MovoTypography.labelMedium, color = MovoColors.textSecondary)
            if (expandable) {
                Spacer(Modifier.width(4.dp))
                val rotation = androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = MovoMotion.fast(),
                    label = "monitorEventChevron",
                )
                MovoIcon(
                    MovoIcons.ChevronDown,
                    contentDescription = stringResource(if (expanded) R.string.movo_collapse else R.string.movo_expand),
                    size = 14.dp,
                    tint = MovoColors.textTertiary,
                    modifier = Modifier.graphicsLayer { rotationZ = rotation.value },
                )
            }
        }
        if (expandable) {
            val cap = io.github.fartown.movo.ui.components.movo.rememberVisibleHeightCap()
            AnimatedVisibility(
                visible = expanded,
                modifier = Modifier.trackVisibleHeightCap(cap),
                enter = expandContentEnter(cap),
                exit = expandContentExit(cap),
            ) {
                Text(
                    text = message.text,
                    style = MovoTypography.labelRegular,
                    color = MovoColors.textTertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, top = 4.dp, bottom = 4.dp)
                        .drawBehind {
                            drawLine(
                                color = MovoColors.borderStrong,
                                start = Offset(0.5.dp.toPx(), 0f),
                                end = Offset(0.5.dp.toPx(), size.height),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                        .padding(start = 13.dp),
                )
            }
        }
    }
}

/** 按界面语言写时长：「30 分钟」「2 小时」。 */
@Composable
internal fun monitorDurationLabel(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(1)
    return if (minutes % 60 == 0L) {
        stringResource(R.string.monitor_duration_hours, (minutes / 60).toInt())
    } else {
        stringResource(R.string.monitor_duration_minutes, minutes.toInt())
    }
}

/** 当前对话 id，供输入框里的「监听」入口筛选本对话的监听。 */
internal val LocalMonitorConversationId = androidx.compose.runtime.staticCompositionLocalOf<String?> { null }

/**
 * 输入框工具栏的「监听」入口（规范 8.12）：本对话有运行中的监听时出现，「监听」/「监听 N」；
 * 点开是后台监听列表，每行可停止，两个及以上有「全部停止」。语音模式下由调用方不放进工具栏。
 */
@Composable
internal fun AgentMonitorChip(
    popupAnchorTopPx: Int,
    modifier: Modifier = Modifier,
) {
    val conversationId = LocalMonitorConversationId.current ?: return
    val all by io.github.fartown.movo.agent.monitor.MonitorRegistry.active.collectAsState()
    val monitors = all.filter { it.conversationId == conversationId }
    androidx.compose.animation.AnimatedVisibility(
        visible = monitors.isNotEmpty(),
        enter = androidx.compose.animation.fadeIn(MovoMotion.fast()),
        exit = androidx.compose.animation.fadeOut(MovoMotion.fastExit()),
        modifier = modifier,
    ) {
        var showList by remember { mutableStateOf(false) }
        androidx.compose.foundation.layout.Box {
            Row(
                modifier = Modifier
                    .height(40.dp)
                    .movoClickable(PressKind.Solid, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) { showList = !showList }
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                    .background(MovoColors.bgSurfaceMuted)
                    .padding(start = 12.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MovoIcon(MovoIcons.Clock, contentDescription = null, size = 16.dp, tint = MovoColors.amberFg)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (monitors.size > 1) {
                        stringResource(R.string.monitor_chip_count, monitors.size)
                    } else {
                        stringResource(R.string.monitor_chip)
                    },
                    style = MovoTypography.labelMedium,
                    color = MovoColors.textSecondary,
                )
            }
            io.github.fartown.movo.ui.components.movo.MovoPopover(
                show = showList && monitors.isNotEmpty(),
                onDismiss = { showList = false },
                aboveYPx = popupAnchorTopPx,
                alignEnd = false,
                width = 300.dp,
            ) {
                Text(
                    text = stringResource(R.string.monitor_list_title),
                    style = MovoTypography.labelMedium,
                    color = MovoColors.textSecondary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                )
                monitors.forEach { info ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(info.name, style = MovoTypography.bodyStrong, color = MovoColors.textPrimary, maxLines = 1)
                            Text(
                                text = stringResource(
                                    R.string.monitor_list_item_detail,
                                    info.eventCount,
                                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(info.deadlineAtMillis)),
                                ),
                                style = MovoTypography.labelRegular,
                                color = MovoColors.textSecondary,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        io.github.fartown.movo.ui.components.movo.MovoPillButton(
                            label = stringResource(R.string.monitor_list_stop),
                            onClick = { stopMonitorsInBackground(listOf(info.id)) },
                        )
                    }
                }
                if (monitors.size > 1) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .height(0.5.dp)
                            .background(MovoColors.borderHairline),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .movoClickable(PressKind.Solid) {
                                showList = false
                                stopMonitorsInBackground(monitors.map { it.id })
                            }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.monitor_stop_all), style = MovoTypography.bodyRegular, color = MovoColors.textPrimary)
                    }
                }
            }
        }
    }
}

/** 停止要结束子进程、等它退出（最长约 3 秒），不放在主线程。 */
private fun stopMonitorsInBackground(ids: List<String>) {
    kotlin.concurrent.thread(name = "movo-monitor-stop") {
        ids.forEach {
            io.github.fartown.movo.agent.monitor.MonitorRegistry.stop(
                it, io.github.fartown.movo.agent.monitor.MonitorEndReason.STOPPED_BY_USER,
            )
        }
    }
}
