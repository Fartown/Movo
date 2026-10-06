package io.github.fartown.movo.ui.components

import android.content.Context
import android.content.res.Resources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.monitor.MonitorEndReason
import io.github.fartown.movo.agent.monitor.MonitorRegistry
import io.github.fartown.movo.agent.monitor.MonitorTime
import io.github.fartown.movo.ui.components.movo.PressKind
import io.github.fartown.movo.ui.components.movo.movoClickable
import io.github.fartown.movo.ui.components.movo.trackVisibleHeightCap
import io.github.fartown.movo.ui.model.MonitorEventKindUi
import io.github.fartown.movo.ui.model.MonitorEventMessageUi
import io.github.fartown.movo.ui.model.ThinkingMessageUi
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoTypography

/**
 * 监听行的上下留白（规范 8.12）：默认上下各 4（与「只有思考」的行内一行相同）；
 * 接在另一条监听行后面时上面不留（叠放间距 4），后面接执行卡时下面不留（执行卡自带 8，间距 8）。
 */
internal data class MonitorRowSpacing(val top: Dp = 4.dp, val bottom: Dp = 4.dp)

/** 对话列表里各监听行的留白（按 id），由 [AgentChatBody] 按前后条目算好提供。 */
internal val LocalMonitorRowSpacings = androidx.compose.runtime.staticCompositionLocalOf<Map<String, MonitorRowSpacing>> { emptyMap() }

/** 按时间线前后条目算每条监听行的留白：只有和默认不同的才在结果里。 */
internal fun monitorRowSpacings(entries: List<AgentTimelineEntry>): Map<String, MonitorRowSpacing> {
    val result = HashMap<String, MonitorRowSpacing>()
    entries.forEachIndexed { index, entry ->
        val row = (entry as? AgentTimelineEntry.Message)?.message as? MonitorEventMessageUi ?: return@forEachIndexed
        val previous = entries.getOrNull(index - 1)
        val next = entries.getOrNull(index + 1)
        val stacked = (previous as? AgentTimelineEntry.Message)?.message is MonitorEventMessageUi
        val beforeCard = next is AgentTimelineEntry.WorkProcess && !next.messages.all { it is ThinkingMessageUi }
        if (stacked || beforeCard) {
            result[row.id] = MonitorRowSpacing(top = if (stacked) 0.dp else 4.dp, bottom = if (beforeCard) 0.dp else 4.dp)
        }
    }
    return result
}

/**
 * 后台监听在对话里的一行（规范 8.12，Figma「18」）：与「已思考」行内一行同一写法，左对齐 20、高 32、无底色。
 * 事件行：琥珀 clock 14 +「监听事件·名称·时间」`Label/Medium` 次要色 + 6 + ⌄，点开显示这次事件的原文（左竖线 + 三级色）。
 * 结束行：clock 改三级色（已停止 / 结束 / 中断）；命令自己结束时写退出码，最后的输出可展开。
 * 「已结束任务」行在撤销期内后面跟「撤销」（Indigo `Label/Medium`），期满消失、这行留下（规范 8.12「撤销」）。
 */
@Composable
internal fun MonitorEventRow(
    message: MonitorEventMessageUi,
    modifier: Modifier = Modifier,
) {
    val event = message.kind == MonitorEventKindUi.Event
    val expandable = message.text.isNotBlank()
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    // 读一下配置：系统语言、12 / 24 小时制变化时重新组合。
    LocalConfiguration.current
    val label = MonitorRowLabels.label(LocalContext.current, message)
    val spacing = LocalMonitorRowSpacings.current[message.id] ?: MonitorRowSpacing()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = spacing.top, bottom = spacing.bottom),
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
            if (message.reason == MonitorEndReason.ENDED_WITH_TASK.name) {
                val endings by MonitorRegistry.endings.collectAsState()
                androidx.compose.animation.AnimatedVisibility(
                    visible = endings.any { it.id == message.taskId },
                    enter = androidx.compose.animation.fadeIn(MovoMotion.fast()),
                    exit = androidx.compose.animation.fadeOut(MovoMotion.fastExit()),
                ) {
                    Text(
                        text = stringResource(R.string.monitor_undo),
                        style = MovoTypography.labelMedium,
                        color = MovoColors.indigoFg,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .movoClickable(PressKind.Link) {
                                io.github.fartown.movo.core.AndroidAgentLogger.info("Monitor ending undo: source=conversation_row")
                                MonitorRegistry.undoEnding(message.taskId)
                            },
                    )
                }
            }
            if (expandable) {
                Spacer(Modifier.width(6.dp))
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

/** 监听行的文案（对话里的行、会话列表的预览共用）。 */

/** 按界面语言写时长：「45 秒」「30 分钟」「2 小时」。 */
@Composable
internal fun monitorDurationLabel(ms: Long): String {
    LocalConfiguration.current
    return MonitorRowLabels.durationLabel(LocalContext.current.resources, ms)
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
    val all by MonitorRegistry.active.collectAsState()
    val monitors = all.filter { it.conversationId == conversationId }
    androidx.compose.animation.AnimatedVisibility(
        visible = monitors.isNotEmpty(),
        enter = androidx.compose.animation.fadeIn(MovoMotion.fast()),
        exit = androidx.compose.animation.fadeOut(MovoMotion.fastExit()),
        modifier = modifier,
    ) {
        var showList by remember { mutableStateOf(false) }
        val context = LocalContext.current
        LocalConfiguration.current
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
                                text = pluralStringResource(
                                    R.plurals.monitor_list_item_detail,
                                    info.eventCount,
                                    info.eventCount,
                                    MonitorTime.clock(context, info.deadlineAtMillis),
                                ),
                                style = MovoTypography.labelRegular,
                                color = MovoColors.textSecondary,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        io.github.fartown.movo.ui.components.movo.MovoPillButton(
                            label = stringResource(R.string.monitor_list_stop),
                            onClick = { stopMonitors(listOf(info.id)) },
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
                                stopMonitors(monitors.map { it.id })
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

/** 停止只做登记，结束进程在注册表自己的线程池里，不会卡住主线程。 */
private fun stopMonitors(ids: List<String>) {
    ids.forEach { MonitorRegistry.stop(it, MonitorEndReason.STOPPED_BY_USER) }
}
