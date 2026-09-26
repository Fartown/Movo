package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.movo.MovoListPage
import io.github.mangi.eta.ui.components.movo.MovoPage
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBarState

/**
 * 二级列表页的统一骨架，已切到设计规范 v1 的 `TopBar/Secondary`（56 高、标题居中、Q7 滚动态）与 bg/canvas 底色；
 * 宽屏居中、横屏安全区、滚动边界触感与越界回弹沿用。尚未改版的页面内容自带左右边距，这里不再额外加边距。
 */
@Composable
fun MiuixScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    MovoListPage(
        title = title,
        onBack = onBack,
        modifier = modifier,
        actions = actions,
        listState = listState,
        horizontalPadding = 0.dp,
        itemSpacing = 0.dp,
        topGap = 0.dp,
        content = content,
    )
}

/**
 * 自定义内容二级页的低层骨架。调用方负责把顶部 padding、横向安全区接入自己的内容；
 * [sidePadding] 用于在宽屏限制实际内容宽度。顶栏固定不折叠，[ScrollBehavior] 只为兼容旧调用保留，不消费滚动。
 */
@Composable
fun MiuixScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (
        paddingValues: PaddingValues,
        scrollBehavior: ScrollBehavior,
        sidePadding: Dp,
    ) -> Unit,
) {
    val scrollBehavior = remember { PinnedScrollBehavior() }
    MovoPage(title = title, onBack = onBack, modifier = modifier, actions = actions) { padding, sidePadding ->
        content(padding, scrollBehavior, sidePadding)
    }
}

/** 固定顶栏：不折叠、不消费嵌套滚动。 */
private class PinnedScrollBehavior : ScrollBehavior {
    override val state: TopAppBarState = TopAppBarState(0f, 0f, 0f)
    override val isPinned: Boolean = true
    override val snapAnimationSpec: AnimationSpec<Float>? = null
    override val flingAnimationSpec: DecayAnimationSpec<Float>? = null
    override val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {}
}

@Composable
fun MiuixPageBottomSpacer(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .height(24.dp)
            .navigationBarsPadding(),
    )
}
