package io.github.mangi.eta.ui.components.movo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.LocalBlurEnabled
import io.github.mangi.eta.ui.layout.WidePageContent
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoSpacing
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun rememberMovoBackdrop(): LayerBackdrop? {
    if (!LocalBlurEnabled.current || !isRuntimeShaderSupported()) return null
    return rememberLayerBackdrop {
        drawRect(MovoColors.bgCanvas)
        drawContent()
    }
}

internal fun Modifier.captureMovoBackdrop(backdrop: LayerBackdrop?): Modifier =
    if (backdrop == null) this else layerBackdrop(backdrop)

/** 顶栏占用的高度（状态栏 + 56），内容从它下面开始。 */
@Composable
internal fun movoTopBarHeight(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + MovoSize.topBar

/**
 * 列表页骨架（规范 2.2、8.7）：bg/canvas 底色、`TopBar/Secondary` 覆盖在内容上（Q7 滚动态），
 * 顶栏到第一张卡片 12、卡片之间 16、内容对齐边距线 20；宽屏内容居中，保留越界回弹与滚动边界触感。
 * [horizontalPadding] 给尚未改版、自带左右边距的旧内容传 0。
 */
@Composable
internal fun MovoListPage(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    horizontalPadding: Dp = MovoSpacing.pageEdge,
    itemSpacing: Dp = MovoSpacing.lg,
    topGap: Dp = MovoSpacing.md,
    content: LazyListScope.() -> Unit,
) {
    val backdrop = rememberMovoBackdrop()
    val scrolled by listState.rememberIsScrolled()
    val barHeight = movoTopBarHeight()
    val navigation = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(modifier = modifier.fillMaxSize().background(MovoColors.bgCanvas)) {
        WidePageContent { sidePadding ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalCutoutPadding()
                    .captureMovoBackdrop(backdrop)
                    .scrollEndHaptic()
                    .overScrollVertical(),
                contentPadding = PaddingValues(
                    start = sidePadding + horizontalPadding,
                    end = sidePadding + horizontalPadding,
                    top = barHeight + topGap,
                    bottom = navigation + MovoSpacing.section,
                ),
                verticalArrangement = Arrangement.spacedBy(itemSpacing),
                content = content,
            )
        }
        MovoTopBar(title = title, onBack = onBack, scrolled = scrolled, backdrop = backdrop, actions = actions)
    }
}

/**
 * 自定义内容页骨架：调用方拿到顶部内边距（顶栏高度）自行排版；
 * 滚动态由挂在内容外层的 [ScrolledDetector] 判断。
 */
@Composable
internal fun MovoPage(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (contentPadding: PaddingValues, sidePadding: Dp) -> Unit,
) {
    val backdrop = rememberMovoBackdrop()
    val detector = remember { ScrolledDetector() }
    val barHeight = movoTopBarHeight()
    Box(modifier = modifier.fillMaxSize().background(MovoColors.bgCanvas)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .captureMovoBackdrop(backdrop)
                .nestedScroll(detector),
        ) {
            WidePageContent { sidePadding ->
                content(PaddingValues(top = barHeight), sidePadding)
            }
        }
        MovoTopBar(title = title, onBack = onBack, scrolled = detector.scrolled, backdrop = backdrop, actions = actions)
    }
}

/** 页面底部留白（导航栏 + 32）。 */
internal val MovoPageBottomGap: Dp = 32.dp
