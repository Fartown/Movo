package io.github.fartown.movo.ui.components.movo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIconData
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

/*
 * 就地反馈（规范 8.11「轻提示」不用 Toast / Snackbar）：成功 = 原地交叉淡化为 ✓（9.3「复制」：`fast`，停留 1400ms 后换回），
 * 失败 = Rose 图标 + 主色文字（4.2 规则 2：类别色不用于文字，颜色不是唯一信号）。
 */

/**
 * 输入框下方 / 卡内的错误说明：Rose 警示图标 14 + 6 + Label/Regular 主色文字（与 `Card/Footer` 的 ⓘ 行同一排法）。
 */
@Composable
internal fun MovoInlineError(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier) {
        Box(modifier = Modifier.height(18.dp), contentAlignment = Alignment.Center) {
            MovoIcon(MovoIcons.CircleAlert, contentDescription = null, size = MovoSize.iconLabel, tint = MovoColors.roseFg)
        }
        Spacer(Modifier.width(6.dp))
        Text(text, style = MovoTypography.labelRegular, color = MovoColors.textPrimary)
    }
}

/** 「完成 ✓」的短暂状态：[trigger] 后 [active] 为 true，停留 1400ms（9.1「已复制 ✓ 停留」）后自动换回。 */
@Stable
internal class DoneFlashState {
    var active by mutableStateOf(false)
        internal set
    internal var token by mutableIntStateOf(0)

    fun trigger() {
        active = true
        token++
    }

    fun reset() {
        active = false
    }
}

@Composable
internal fun rememberDoneFlash(): DoneFlashState {
    val state = remember { DoneFlashState() }
    LaunchedEffect(state.token) {
        if (state.token == 0) return@LaunchedEffect
        delay(MovoMotion.COPIED_HOLD.toLong())
        state.active = false
    }
    return state
}

/**
 * 图标状态切换（9.3.1「图标状态切换」：交叉淡化 + 缩放 0.72 ↔ 1，`fast`；减少动画时只淡入淡出）。
 * [done] 为 true 时显示 Green ✓（4.2「完成✓」）。
 */
@Composable
internal fun MovoDoneSwapIcon(
    icon: MovoIconData,
    done: Boolean,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = done,
        modifier = modifier,
        transitionSpec = {
            if (reduced) {
                fadeIn(MovoMotion.fast()) togetherWith fadeOut(MovoMotion.fastExit())
            } else {
                (fadeIn(MovoMotion.fast()) + scaleIn(MovoMotion.fast(), initialScale = 0.72f)) togetherWith
                    (fadeOut(MovoMotion.fastExit()) + scaleOut(MovoMotion.fastExit(), targetScale = 0.72f))
            }
        },
        contentAlignment = Alignment.Center,
        label = "doneSwapIcon",
    ) { showDone ->
        if (showDone) {
            MovoIcon(MovoIcons.Check, contentDescription = null, size = size, tint = MovoColors.greenFg)
        } else {
            MovoIcon(icon, contentDescription = null, size = size, tint = tint)
        }
    }
}

/** 无底色图标按钮（热区 44、图标 24、按压 40 圆形叠加层），完成后图标原地换成 ✓（导出、复制）。 */
@Composable
internal fun MovoDoneIconButton(
    icon: MovoIconData,
    done: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = MovoSize.iconLarge,
    tint: Color = MovoColors.textPrimary,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(MovoSize.touchTarget)
            .movoClickable(PressKind.Icon, enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        MovoDoneSwapIcon(icon = icon, done = done, size = iconSize, tint = tint)
    }
}

/**
 * `Button/Block` 整行按钮（与 [MovoBlockButton] 同一规格：高 48、圆角 24；主 = action/primary，次 = bg/surface-muted），
 * 完成时文字原地交叉淡化为「✓ + 完成文案」（8.11 结果就地反馈），宽度变化 `standard`。
 */
@Composable
internal fun MovoDoneBlockButton(
    label: String,
    doneLabel: String,
    done: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: BlockTone = BlockTone.Primary,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(MovoRadius.pillLg)
    val (bg, fg) = when (tone) {
        BlockTone.Primary -> MovoColors.actionPrimaryBg to MovoColors.actionPrimaryFg
        BlockTone.Secondary -> MovoColors.bgSurfaceMuted to MovoColors.textPrimary
        BlockTone.Destructive -> MovoColors.bgInverse to MovoColors.textOnInverse
    }
    val reduced = LocalReducedMotion.current
    Box(
        modifier = modifier
            .heightIn(min = MovoSize.controlLarge)
            // 显示 ✓ 期间不重复执行，但不按禁用态变淡。
            .movoClickable(PressKind.Solid, shape = shape, enabled = enabled, onClick = { if (!done) onClick() })
            .clip(shape)
            .background(bg)
            .padding(horizontal = MovoSpacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = done,
            transitionSpec = {
                val enter = if (reduced) {
                    fadeIn(MovoMotion.fast())
                } else {
                    fadeIn(MovoMotion.fast()) + scaleIn(MovoMotion.fast(), initialScale = 0.72f)
                }
                (enter togetherWith fadeOut(MovoMotion.fastExit()))
                    .using(SizeTransform(clip = false) { _, _ -> if (reduced) tween(0) else MovoMotion.standard() })
            },
            contentAlignment = Alignment.Center,
            label = "doneBlockButton",
        ) { showDone ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showDone) {
                    MovoIcon(MovoIcons.Check, contentDescription = null, size = MovoSize.iconMedium, tint = fg)
                    Spacer(Modifier.width(MovoSpacing.sm))
                }
                Text(
                    if (showDone) doneLabel else label,
                    style = MovoTypography.bodyStrong,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
