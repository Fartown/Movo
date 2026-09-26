package io.github.mangi.eta.ui.components.movo

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.theme.LocalReducedMotion
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoIcons
import io.github.mangi.eta.ui.theme.MovoMotion
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur

/**
 * `TopBar/Secondary` 二级页顶栏（规范 8.7）：高 56；左侧返回（chevron-left 24，热区 44，字形左缘对齐 20，左内边距 1）；
 * 标题居中 Body/Strong，最大宽 280，超出省略；右侧可放图标按钮（右内边距 6）。
 * Q7 顶栏滚动态：[scrolled] 为 true 时底色与 0.5 分隔线淡入 `fast`，回到顶部淡出 120ms；减少动画时直接切换。
 */
@Composable
internal fun MovoTopBar(
    title: String,
    onBack: (() -> Unit)?,
    scrolled: Boolean,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    val chrome by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = when {
            reduced -> snap()
            scrolled -> MovoMotion.fast()
            else -> MovoMotion.fastExit()
        },
        label = "topBarChrome",
    )
    Box(modifier = modifier.fillMaxWidth()) {
        // 滚动态底色：有模糊能力时用顶栏背景模糊，否则 bg/canvas 90%。
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = chrome },
        ) {
            if (backdrop != null) {
                Box(
                    Modifier.matchParentSize().progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        gradient = ProgressiveBlur.Top,
                        blurRadius = 16f,
                        colors = BlurColors(blendColors = listOf(BlendColorEntry(MovoColors.bgCanvas.copy(alpha = 0.9f)))),
                    ),
                )
            } else {
                Box(Modifier.matchParentSize().background(MovoColors.bgCanvas.copy(alpha = 0.9f)))
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(MovoSize.topBar),
        ) {
            if (onBack != null) {
                MovoIconButton(
                    icon = MovoIcons.ChevronLeft,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 1.dp),
                )
            }
            Text(
                text = title,
                style = MovoTypography.bodyStrong,
                color = MovoColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 66.dp)
                    .widthIn(max = 280.dp)
                    // Q4：从设置行进来时，标题由移动中的行标题接上，落地前自己先隐藏。
                    .onGloballyPositioned { TitleMorph.reportTarget(title, it.boundsInWindow()) }
                    .graphicsLayer { alpha = if (TitleMorph.hides(title)) 0f else 1f }
                    .semantics { heading() },
            )
            Row(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(MovoSize.hairline)
                .graphicsLayer { alpha = chrome }
                .background(MovoColors.borderHairline),
        )
    }
}

/** 列表是否离开顶部（Q7 触发条件）。 */
@Composable
internal fun LazyListState.rememberIsScrolled(): State<Boolean> = remember(this) {
    derivedStateOf { firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0 }
}

/**
 * 给不是 LazyList 的自定义内容页用：挂在内容外层，按累计滚动量判断是否离开顶部。
 */
internal class ScrolledDetector : NestedScrollConnection {
    private val offset = mutableFloatStateOf(0f)
    val scrolled: Boolean get() = offset.floatValue < -0.5f

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        offset.floatValue = (offset.floatValue + consumed.y).coerceAtMost(0f)
        return Offset.Zero
    }
}
