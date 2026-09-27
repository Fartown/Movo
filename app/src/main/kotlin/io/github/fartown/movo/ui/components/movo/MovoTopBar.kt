package io.github.fartown.movo.ui.components.movo

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
import androidx.compose.runtime.mutableStateOf
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
import io.github.fartown.movo.R
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlurEffect

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
    val showBlur by remember { derivedStateOf { chrome > 0f } }
    Box(modifier = modifier.fillMaxWidth()) {
        // 滚动态底色：有模糊能力时用顶栏背景模糊，否则 bg/canvas 90%。
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = chrome },
        ) {
            // 顶栏背景模糊只在滚动态（底色可见）时组合：未滚动时它整层透明却仍要组合、录制和准备模糊，
            // 每次进二级页的第一帧都白付这份开销。
            if (backdrop != null && showBlur) {
                Box(
                    Modifier.matchParentSize().movoTopBarBlur(backdrop),
                )
            }
            // Q7 的底色是整条 90%：渐进模糊的混合色随模糊一起向下变淡，单靠它顶栏下半部分几乎透明，
            // 滚上来的内容会和标题叠在一起。
            Box(Modifier.matchParentSize().background(MovoColors.bgCanvas.copy(alpha = 0.9f)))
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
 * 累计量是普通字段，只有越过阈值时才写一次可观察的布尔值：滚动中读 [scrolled] 的界面不会逐帧重组。
 */
internal class ScrolledDetector : NestedScrollConnection {
    private var offset = 0f
    private val scrolledState = mutableStateOf(false)
    val scrolled: Boolean get() = scrolledState.value

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        offset = (offset + consumed.y).coerceAtMost(0f)
        val now = offset < -0.5f
        if (scrolledState.value != now) scrolledState.value = now
        return Offset.Zero
    }
}

/**
 * 顶栏滚动态的背景模糊（规范 Q7「渐进模糊 上 16 → 下 0」）。
 *
 * 用 `drawBackdrop` 单次降采样的渐进模糊，不传 `progressiveGradient`：Miuix 的 `progressiveTextureBlur`
 * 会走多级合成，并为了让清晰端像素级锐利每帧再补一个全分辨率覆盖 pass。顶栏上面还盖着整条 90% 底色，
 * 清晰端锐不锐利看不出来，这份开销却是每帧都付——流式回答时内容一直在动，真机 A/B：模糊开 GPU p50 4–5ms，
 * 关掉 2ms，卡顿帧翻倍。
 * 不在模糊里再混 90% 底色：单次渐进模糊的混色是整条均匀的（多级合成里它随模糊淡出），再叠上方那层 90% 底色就成了
 * 99% 遮盖，下面的内容完全看不出来（真机对照 fix25）。底色只由调用方那层 90% 提供，与原来观感一致。
 */
internal fun Modifier.movoTopBarBlur(backdrop: LayerBackdrop): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { RectangleShape },
    effects = {
        progressiveTextureBlurEffect(
            blurRadiusX = 16f,
            gradient = ProgressiveBlur.Top,
        )
    },
)
