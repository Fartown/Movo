package io.github.fartown.movo.ui.screens.terminal

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.terminal.LinuxDistribution
import io.github.fartown.movo.agent.terminal.LinuxExecutionBackend
import io.github.fartown.movo.ui.components.movo.BlockTone
import io.github.fartown.movo.ui.components.movo.CardTitle
import io.github.fartown.movo.ui.components.movo.MovoBlockButton
import io.github.fartown.movo.ui.components.movo.MovoCard
import io.github.fartown.movo.ui.components.movo.MovoDialogHost
import io.github.fartown.movo.ui.components.movo.PressKind
import io.github.fartown.movo.ui.components.movo.RowTrailing
import io.github.fartown.movo.ui.components.movo.SettingsRow
import io.github.fartown.movo.ui.components.movo.movoClickable
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

/**
 * 状态卡：Graphite 图标底块（终端类别色）+ 发行版与版本 + 运行方式；进度 / 结果就地显示；
 * 需要操作时底部一个 48 整行主按钮（安装、继续安装工具、去系统增强）。
 */
@Composable
internal fun LinuxEnvironmentStatusCard(
    title: String,
    mode: String,
    summary: String,
    busy: Boolean,
    message: String?,
    actionText: String?,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    MovoCard(bottomPadding = 0.dp) {
        Column(
            modifier = Modifier.padding(MovoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MovoSpacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(MovoSize.iconTile)
                        .clip(RoundedCornerShape(MovoRadius.sm))
                        .background(MovoColors.graphiteBg),
                    contentAlignment = Alignment.Center,
                ) {
                    MovoIcon(MovoIcons.Terminal, null, size = MovoSize.iconMedium, tint = MovoColors.graphiteFg)
                }
                Spacer(Modifier.width(MovoSpacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MovoTypography.titleSection, color = MovoColors.textPrimary)
                    Text(text = mode, style = MovoTypography.labelRegular, color = MovoColors.textSecondary)
                }
            }
            Row(verticalAlignment = Alignment.Top) {
                if (busy) {
                    Box(modifier = Modifier.height(22.dp), contentAlignment = Alignment.Center) {
                        TerminalSpinner()
                    }
                    Spacer(Modifier.width(MovoSpacing.sm))
                }
                Column(modifier = Modifier.weight(1f)) {
                    summary.lines().forEach { line ->
                        Text(text = line, style = MovoTypography.bodyRegular, color = MovoColors.textSecondary)
                    }
                }
            }
            message?.let {
                Text(text = it, style = MovoTypography.labelRegular, color = MovoColors.textPrimary)
            }
            actionText?.let {
                MovoBlockButton(
                    label = it,
                    onClick = onAction,
                    tone = BlockTone.Primary,
                    enabled = actionEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 环境配置卡：发行版、运行方式两行，点按弹出带说明的单选对话框；
 * 未授予 Root 且当前不是 chroot 时运行方式只显示当前值（无法切换）。
 */
@Composable
internal fun LinuxEnvironmentConfiguration(
    distribution: LinuxDistribution,
    backend: LinuxExecutionBackend,
    rootGranted: Boolean,
    enabled: Boolean,
    onDistributionSelected: (LinuxDistribution) -> Unit,
    onBackendSelected: (LinuxExecutionBackend) -> Unit,
) {
    val distributions = LinuxDistribution.entries
    val backends = listOf(LinuxExecutionBackend.PROOT, LinuxExecutionBackend.CHROOT)
    var showDistributionDialog by remember { mutableStateOf(false) }
    var showBackendDialog by remember { mutableStateOf(false) }
    val backendSelectable = rootGranted || backend == LinuxExecutionBackend.CHROOT

    MovoCard {
        CardTitle(stringResource(R.string.linux_environment_configuration))
        SettingsRow(
            title = stringResource(R.string.linux_distribution_title),
            trailing = RowTrailing.Arrow(distribution.displayName()),
            enabled = enabled,
            onClick = { showDistributionDialog = true },
        )
        if (backendSelectable) {
            SettingsRow(
                title = stringResource(R.string.capability_linux_backend),
                trailing = RowTrailing.Arrow(backend.displayName()),
                enabled = enabled,
                showDivider = false,
                onClick = { showBackendDialog = true },
            )
        } else {
            SettingsRow(
                title = stringResource(R.string.capability_linux_backend),
                trailing = RowTrailing.Value(backend.displayName()),
                showDivider = false,
            )
        }
    }

    LinuxChoiceDialog(
        show = showDistributionDialog,
        title = stringResource(R.string.linux_distribution_title),
        options = distributions.map {
            LinuxChoiceOption(
                title = it.displayName(),
                summary = stringResource(
                    when (it) {
                        LinuxDistribution.ALPINE -> R.string.linux_distribution_alpine_summary
                        LinuxDistribution.DEBIAN -> R.string.linux_distribution_debian_summary
                    },
                ),
            )
        },
        selectedIndex = distributions.indexOf(distribution),
        onSelect = { onDistributionSelected(distributions[it]) },
        onDismissRequest = { showDistributionDialog = false },
    )
    LinuxChoiceDialog(
        show = showBackendDialog && backendSelectable,
        title = stringResource(R.string.capability_linux_backend),
        options = backends.map {
            LinuxChoiceOption(
                title = it.displayName(),
                summary = stringResource(
                    when (it) {
                        LinuxExecutionBackend.PROOT -> R.string.capability_linux_proot_summary
                        LinuxExecutionBackend.CHROOT -> R.string.capability_linux_chroot_summary
                    },
                ),
                enabled = it == LinuxExecutionBackend.PROOT || rootGranted,
            )
        },
        selectedIndex = backends.indexOf(backend),
        onSelect = { onBackendSelected(backends[it]) },
        onDismissRequest = { showBackendDialog = false },
    )
}

internal data class LinuxChoiceOption(
    val title: String,
    val summary: String?,
    val enabled: Boolean = true,
)

/**
 * 带说明的单选对话框：与 `MovoChoiceDialog` 同一写法（行按压态、✓ 为 Indigo、选中后停留 160ms 再关闭），
 * 额外支持每项说明与禁用项（原 `WindowSpinnerPreference` 的 summary / enabled）。
 */
@Composable
internal fun LinuxChoiceDialog(
    show: Boolean,
    title: String,
    options: List<LinuxChoiceOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var pending by remember(show) { mutableIntStateOf(-1) }
    LaunchedEffect(pending) {
        if (pending >= 0) {
            delay(MovoMotion.FAST.toLong() + MovoMotion.MENU_CLOSE_DELAY)
            onSelect(pending)
            onDismissRequest()
        }
    }
    MovoDialogHost(show = show, onDismissRequest = onDismissRequest) {
        Text(
            title,
            style = MovoTypography.titleSection,
            color = MovoColors.textPrimary,
            modifier = Modifier.padding(start = MovoSpacing.xxl, end = MovoSpacing.xxl, top = MovoSpacing.xxl, bottom = MovoSpacing.sm),
        )
        Column(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MovoSpacing.sm)
                .padding(bottom = MovoSpacing.sm),
        ) {
            options.forEachIndexed { index, option ->
                val selected = if (pending >= 0) index == pending else index == selectedIndex
                val shape = RoundedCornerShape(MovoRadius.sm)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MovoSize.controlLarge)
                        .clip(shape)
                        .movoClickable(PressKind.Row, shape = shape, enabled = option.enabled, role = Role.RadioButton) {
                            if (pending < 0) pending = index
                        }
                        .padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            option.title,
                            style = if (selected) MovoTypography.bodyStrong else MovoTypography.bodyRegular,
                            color = MovoColors.textPrimary,
                        )
                        option.summary?.let {
                            Text(it, style = MovoTypography.labelRegular, color = MovoColors.textSecondary)
                        }
                    }
                    if (selected) {
                        Spacer(Modifier.width(MovoSpacing.sm))
                        MovoIcon(MovoIcons.Check, null, size = MovoSize.iconMedium, tint = MovoColors.indigoFg)
                    }
                }
            }
        }
    }
}

@Composable
internal fun LinuxDistribution.displayName(): String = stringResource(
    when (this) {
        LinuxDistribution.ALPINE -> R.string.linux_distribution_alpine
        LinuxDistribution.DEBIAN -> R.string.linux_distribution_debian
    },
)

@Composable
internal fun LinuxExecutionBackend.displayName(): String = stringResource(
    when (this) {
        LinuxExecutionBackend.PROOT -> R.string.capability_linux_proot
        LinuxExecutionBackend.CHROOT -> R.string.capability_linux_chroot
    },
)

// ---- 终端相关二级页共用的小组件 ----

/** 加载圈：Lucide loader-circle 匀速旋转（周期 `SPINNER_PERIOD`），减少动画时静止。 */
@Composable
internal fun TerminalSpinner(
    size: Dp = MovoSize.iconSmall,
    tint: Color = MovoColors.indigoFg,
) {
    val reduced = LocalReducedMotion.current
    val rotation = if (reduced) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "terminalSpinner")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(MovoMotion.SPINNER_PERIOD, easing = LinearEasing)),
            label = "terminalSpinnerRotation",
        )
        value
    }
    MovoIcon(
        MovoIcons.LoaderCircle,
        contentDescription = null,
        size = size,
        tint = tint,
        modifier = Modifier.graphicsLayer { rotationZ = rotation },
    )
}

/**
 * 分段卡片：让一张 `MovoCard` 拆成多个 LazyColumn item（长文件列表保持惰性加载）。
 * 首段带上圆角、末段带下圆角；发丝描边只画外轮廓，段与段之间不画线（行分隔线由行自己负责）。
 * 使用分段卡片的页面把 `MovoListPage.itemSpacing` 设为 0，卡片之间用 [CardGap]。
 */
internal fun Modifier.movoCardSegment(first: Boolean, last: Boolean): Modifier {
    val radius = MovoRadius.xl
    val shape = RoundedCornerShape(
        topStart = if (first) radius else 0.dp,
        topEnd = if (first) radius else 0.dp,
        bottomStart = if (last) radius else 0.dp,
        bottomEnd = if (last) radius else 0.dp,
    )
    return this
        .fillMaxWidth()
        .clip(shape)
        .background(MovoColors.bgSurface)
        .drawBehind {
            val stroke = MovoSize.hairline.toPx()
            val r = radius.toPx()
            // 非首 / 末段把圆角矩形向外延伸到可见区域之外，只留下左右两条边。
            val extend = r + stroke
            val top = if (first) stroke / 2 else -extend
            val bottom = if (last) size.height - stroke / 2 else size.height + extend
            drawRoundRect(
                color = MovoColors.borderHairline,
                topLeft = Offset(stroke / 2, top),
                size = Size(size.width - stroke, bottom - top),
                cornerRadius = CornerRadius(r - stroke / 2),
                style = Stroke(stroke),
            )
        }
        .padding(bottom = if (last) MovoSpacing.xs else 0.dp)
}

/** 分段卡片页面里卡片之间的 16 间距。 */
@Composable
internal fun CardGap() {
    Spacer(Modifier.height(MovoSpacing.lg))
}

/**
 * 卡内就地反馈（规范 8.11「轻提示」不用 Toast）：左右 16、上下 12；
 * 错误用 Rose 警示图标 + 主色文字（颜色不是唯一信号），普通结果用 ⓘ + 次要色文字。
 */
@Composable
internal fun CardNotice(
    text: String,
    error: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.md),
    ) {
        Box(modifier = Modifier.height(18.dp), contentAlignment = Alignment.Center) {
            MovoIcon(
                if (error) MovoIcons.CircleAlert else MovoIcons.Info,
                contentDescription = null,
                size = MovoSize.iconLabel,
                tint = if (error) MovoColors.roseFg else MovoColors.textSecondary,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MovoTypography.labelRegular,
            color = if (error) MovoColors.textPrimary else MovoColors.textSecondary,
        )
    }
}

/** 卡内状态说明（未安装、为空、读取失败等）：左右 16、上下 16，Body/Regular 次要色。 */
@Composable
internal fun CardStateText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MovoTypography.bodyRegular,
        color = MovoColors.textSecondary,
        modifier = modifier
            .fillMaxWidth()
            .padding(MovoSpacing.lg),
    )
}
