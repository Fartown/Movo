package io.github.fartown.movo.ui.screens.browser

import android.content.Intent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.browser.AgentBrowserSession
import io.github.fartown.movo.agent.browser.BrowserSessionSnapshot
import io.github.fartown.movo.ui.components.movo.MovoConfirmDialog
import io.github.fartown.movo.ui.components.movo.MovoDivider
import io.github.fartown.movo.ui.components.movo.MovoIconButton
import io.github.fartown.movo.ui.components.movo.MovoPillButton
import io.github.fartown.movo.ui.components.movo.MovoSpinner
import io.github.fartown.movo.ui.components.movo.TextField
import io.github.fartown.movo.ui.components.movo.movoSurface
import io.github.fartown.movo.ui.components.movo.rememberLastNonNull
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIconData
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text

/**
 * Agent 与用户共享的浏览器会话。
 *
 * 浏览器通常在后台由模型驱动；进入本页后挂载的是同一个 WebView，用户可以直接接管，
 * 不会新建一份与 Agent 状态脱节的预览。
 *
 * 界面按规范（D10）：左右边距线 20；地址栏 → 12 → 状态条（有提示时）→ 浏览器窗口卡片（`movoSurface`：圆角 28 + 发丝描边）；
 * 工具栏为 44 热区的 Lucide 图标按钮，禁用态整体 40%；结果与失败都写在顶部状态条里，不用 Toast（8.11）。
 */
@Composable
internal fun AgentBrowserScreen(
    modifier: Modifier = Modifier,
    initialUrl: String? = null,
    onInitialUrlConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val noExternalAppMessage = stringResource(R.string.browser_no_external_app)
    val snapshot by AgentBrowserSession.snapshots.collectAsState()
    var address by remember { mutableStateOf("") }
    var addressFocused by remember { mutableStateOf(false) }
    var actionPending by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    // 就地失败提示（如没有外部应用能打开网页）：写在顶部状态条，下一次操作时清除。
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(context.applicationContext) {
        AgentBrowserSession.initialize(context.applicationContext)
    }
    LaunchedEffect(snapshot.displayUrl, addressFocused) {
        if (!addressFocused) {
            address = snapshot.displayUrl
        }
    }

    fun launchBrowserAction(action: () -> Unit) {
        if (actionPending) return
        notice = null
        actionPending = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } finally {
                actionPending = false
            }
        }
    }

    fun navigate() {
        if (actionPending) return
        val target = if (address == snapshot.displayUrl) {
            snapshot.url
        } else {
            address.trim()
        }
        if (target.isBlank()) return
        focusManager.clearFocus()
        keyboard?.hide()
        launchBrowserAction {
            AgentBrowserSession.navigateFromUser(context.applicationContext, target)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MovoColors.bgCanvas)
            .padding(horizontal = MovoSpacing.pageEdge)
            .padding(bottom = MovoSpacing.md)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        val canGo = address.isNotBlank() && !actionPending
        TextField(
            value = address,
            onValueChange = {
                address = it
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state -> addressFocused = state.isFocused },
            label = stringResource(R.string.ui_url_or_domain_name_3ee97a),
            useLabelAsPlaceholder = true,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { navigate() }),
            leadingIcon = {
                MovoIcon(
                    if (snapshot.url.startsWith("https://")) MovoIcons.Lock else MovoIcons.Globe,
                    contentDescription = null,
                    size = MovoSize.iconSmall,
                    tint = MovoColors.textSecondary,
                    modifier = Modifier.padding(start = MovoSpacing.md),
                )
            },
            trailingIcon = {
                MovoIconButton(
                    icon = MovoIcons.ArrowRight,
                    contentDescription = stringResource(R.string.ui_access_7f5641),
                    onClick = ::navigate,
                    enabled = canGo,
                    iconSize = MovoSize.iconMedium,
                    modifier = Modifier.padding(end = MovoSpacing.xs),
                )
            },
        )

        Spacer(modifier = Modifier.height(MovoSpacing.md))
        BrowserStatusBanner(snapshot, notice)

        BrowserWindow(
            snapshot = snapshot,
            initialUrl = initialUrl,
            onInitialUrlConsumed = onInitialUrlConsumed,
            actionPending = actionPending,
            onBack = { launchBrowserAction { AgentBrowserSession.goBackFromUser() } },
            onForward = { launchBrowserAction { AgentBrowserSession.goForwardFromUser() } },
            onRefresh = {
                notice = null
                if (snapshot.isLoading) {
                    scope.launch(Dispatchers.IO) {
                        AgentBrowserSession.stopFromUser()
                    }
                } else {
                    launchBrowserAction {
                        AgentBrowserSession.reloadFromUser()
                    }
                }
            },
            onOpenExternal = {
                val currentUrl = snapshot.url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                if (currentUrl != null) {
                    notice = null
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, currentUrl.toUri()))
                    }.onFailure {
                        notice = noExternalAppMessage
                    }
                }
            },
            onReset = { showResetDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }

    // 重置会话会清除 Cookie 与站点数据（C7）：危险确认（标题前 Rose 警示图标 + bg/inverse 白字确认）。
    MovoConfirmDialog(
        show = showResetDialog,
        title = stringResource(R.string.ui_reset_browser_session_791b36),
        message = stringResource(R.string.ui_this_will_close_the_current_page_and_clear_movo_brows_1cd331),
        confirmText = stringResource(R.string.browser_reset),
        destructive = true,
        confirmEnabled = !actionPending,
        onDismissRequest = { showResetDialog = false },
        onConfirm = {
            showResetDialog = false
            address = ""
            launchBrowserAction { AgentBrowserSession.resetFromUser() }
        },
    )
}

/**
 * 统一的浏览器窗口：工具栏、进度条与网页内容收进同一张卡片（圆角 28 + 发丝描边，E0），
 * 进度条悬浮在内容顶部，加载时不再挤压布局。卡片用普通圆角裁剪（硬件轮廓裁剪，对 WebView 安全；
 * 不能用着色器裁剪，会强制离屏合成导致 WebView 闪烁）。
 */
@Composable
private fun BrowserWindow(
    snapshot: BrowserSessionSnapshot,
    initialUrl: String?,
    onInitialUrlConsumed: () -> Unit,
    actionPending: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onOpenExternal: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.movoSurface()) {
        BrowserToolbar(
            snapshot = snapshot,
            actionPending = actionPending,
            onBack = onBack,
            onForward = onForward,
            onRefresh = onRefresh,
            onOpenExternal = onOpenExternal,
            onReset = onReset,
        )
        MovoDivider(end = 0.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            BrowserWebViewHost(
                modifier = Modifier.fillMaxSize(),
                initialUrl = initialUrl,
                onInitialUrlConsumed = onInitialUrlConsumed,
            )

            BrowserLoadingProgress(snapshot)

            BrowserStateOverlay(
                snapshot = snapshot,
                onRetry = onRefresh,
            )
        }
    }
}

/** 工具栏：44 热区图标按钮（图标 20），首个图标字形对齐卡内 16（左内边距 4 = 16 − 12）。 */
@Composable
private fun BrowserToolbar(
    snapshot: BrowserSessionSnapshot,
    actionPending: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onOpenExternal: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MovoSpacing.xs, vertical = MovoSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrowserControlButton(
            icon = MovoIcons.ArrowLeft,
            description = stringResource(R.string.browser_back),
            enabled = snapshot.canGoBack && !actionPending,
            onClick = onBack,
        )
        BrowserControlButton(
            icon = MovoIcons.ArrowRight,
            description = stringResource(R.string.browser_forward),
            enabled = snapshot.canGoForward && !actionPending,
            onClick = onForward,
        )
        BrowserControlButton(
            icon = if (snapshot.isLoading) MovoIcons.X else MovoIcons.RotateCw,
            description = if (snapshot.isLoading) stringResource(R.string.browser_stop_loading) else stringResource(R.string.browser_refresh),
            enabled = snapshot.available && (snapshot.isLoading || !actionPending),
            onClick = onRefresh,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = MovoSpacing.sm),
        ) {
            Text(
                text = snapshot.title.ifBlank { stringResource(R.string.browser_title) },
                style = MovoTypography.bodyStrong,
                color = MovoColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (snapshot.host.isNotBlank()) {
                Text(
                    text = snapshot.host,
                    style = MovoTypography.labelRegular,
                    color = MovoColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        BrowserControlButton(
            icon = MovoIcons.ExternalLink,
            description = stringResource(R.string.browser_open_external),
            enabled = snapshot.available,
            onClick = onOpenExternal,
        )
        BrowserControlButton(
            icon = MovoIcons.Trash2,
            description = stringResource(R.string.browser_reset_session),
            enabled = snapshot.available && !actionPending,
            onClick = onReset,
        )
    }
}

/** 无底色图标按钮（规范 8「图标按钮」）：热区 44、图标 20、按压 40 圆形叠加层；禁用整体 40%、不响应。 */
@Composable
private fun BrowserControlButton(
    icon: MovoIconData,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    MovoIconButton(
        icon = icon,
        contentDescription = description,
        onClick = onClick,
        enabled = enabled,
        iconSize = MovoSize.iconMedium,
    )
}

private enum class BrowserOverlay {
    None,
    Empty,
    Loading,
    Failed,
}

/**
 * 加载进度条悬浮在网页顶部，不占布局；提取到 BoxScope 扩展中，避免与外层
 * ColumnScope 的 AnimatedVisibility 重载冲突。
 */
@Composable
private fun BoxScope.BrowserLoadingProgress(snapshot: BrowserSessionSnapshot) {
    AnimatedVisibility(
        visible = snapshot.isLoading && snapshot.available,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = fadeIn(MovoMotion.fast()),
        exit = fadeOut(MovoMotion.fastExit()),
    ) {
        LinearProgressIndicator(
            progress = snapshot.progress
                .takeIf { it in 1..99 }
                ?.let { it / 100f },
            modifier = Modifier.fillMaxWidth(),
            height = 2.5.dp,
        )
    }
}

/**
 * 内容状态浮层。已有提交页面时导航/刷新保持旧页面可见，只显示顶部进度条，
 * 避免每次加载都用占位页盖住当前内容造成闪烁。
 */
@Composable
private fun BoxScope.BrowserStateOverlay(
    snapshot: BrowserSessionSnapshot,
    onRetry: () -> Unit,
) {
    val overlay = when {
        !snapshot.available -> BrowserOverlay.Empty
        !snapshot.hasCommittedPage && snapshot.error != null -> BrowserOverlay.Failed
        !snapshot.hasCommittedPage -> BrowserOverlay.Loading
        else -> BrowserOverlay.None
    }
    Crossfade(
        targetState = overlay,
        animationSpec = MovoMotion.fast(),
        label = "browser_overlay",
        modifier = Modifier.fillMaxSize(),
    ) { state ->
        when (state) {
            BrowserOverlay.Empty -> BrowserEmptyState(modifier = Modifier.fillMaxSize())
            BrowserOverlay.Loading -> BrowserLoadingState(
                host = snapshot.host,
                modifier = Modifier.fillMaxSize(),
            )
            BrowserOverlay.Failed -> BrowserFailedState(
                error = snapshot.error,
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
            BrowserOverlay.None -> Unit
        }
    }
}

/**
 * 顶部状态条：网页错误 / 就地失败提示 = Rose 警示图标 + 主色文字；用户接管 = Indigo 点击图标（Agent 状态）+ 主色文字。
 * 容器同卡片（圆角 28 + 发丝描边），内边距 16 / 12；出现与消失高度展开 `standard` + 淡入淡出。
 */
@Composable
private fun ColumnScope.BrowserStatusBanner(snapshot: BrowserSessionSnapshot, notice: String?) {
    val error = snapshot.error ?: notice
    val message = when {
        error != null -> error
        snapshot.isUserControlling && snapshot.available ->
            stringResource(R.string.browser_user_controlling)
        else -> null
    }
    val shown = rememberLastNonNull(message)
    val isError = error != null
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(MovoMotion.fast()) + expandVertically(MovoMotion.standard()),
        exit = fadeOut(MovoMotion.fastExit()) + shrinkVertically(MovoMotion.standard()),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .movoSurface()
                    .padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MovoIcon(
                    if (isError) MovoIcons.CircleAlert else MovoIcons.MousePointerClick,
                    contentDescription = null,
                    size = MovoSize.iconSmall,
                    tint = if (isError) MovoColors.roseFg else MovoColors.indigoFg,
                )
                Spacer(modifier = Modifier.width(MovoSpacing.sm))
                Text(
                    text = shown.orEmpty(),
                    modifier = Modifier.weight(1f),
                    style = MovoTypography.labelRegular,
                    color = MovoColors.textPrimary,
                )
            }
            Spacer(modifier = Modifier.height(MovoSpacing.md))
        }
    }
}

/** 占位状态的图标块：40、圆角 12、类别色浅底 + 深色图标 20（规范 2.3、4.2）。 */
@Composable
private fun BrowserOverlayIcon(
    icon: MovoIconData,
    background: Color,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(MovoSize.iconTile)
            .clip(RoundedCornerShape(MovoRadius.sm))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        MovoIcon(icon, contentDescription = null, size = MovoSize.iconMedium, tint = tint)
    }
}

/**
 * 占位状态覆盖在 WebView 之上，拦截触摸，避免用户点到尚未完成渲染的页面。
 */
private fun Modifier.consumeTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { change ->
                change.consume()
            }
        }
    }
}

@Composable
private fun BrowserEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .consumeTouches()
            .background(MovoColors.bgSurface)
            .padding(MovoSpacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 浏览器 = Blue（4.2「信息与网络」）。
        BrowserOverlayIcon(icon = MovoIcons.Globe, background = MovoColors.blueBg, tint = MovoColors.blueFg)
        Spacer(modifier = Modifier.height(MovoSpacing.lg))
        Text(
            text = stringResource(R.string.ui_the_browser_has_not_opened_the_web_page_yet_31e095),
            style = MovoTypography.bodyStrong,
            color = MovoColors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.ui_enter_the_url_in_the_address_bar_or_let_the_agent_br_e2ae90),
            style = MovoTypography.labelRegular,
            color = MovoColors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BrowserLoadingState(
    host: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .consumeTouches()
            .background(MovoColors.bgSurface)
            .padding(MovoSpacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MovoSpinner(size = MovoSize.iconLarge)
        Spacer(modifier = Modifier.height(MovoSpacing.lg))
        Text(
            text = if (host.isBlank()) stringResource(R.string.browser_opening) else stringResource(R.string.browser_opening_host, host),
            style = MovoTypography.labelRegular,
            color = MovoColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BrowserFailedState(
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .consumeTouches()
            .background(MovoColors.bgSurface)
            .padding(MovoSpacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrowserOverlayIcon(icon = MovoIcons.CircleAlert, background = MovoColors.roseBg, tint = MovoColors.roseFg)
        Spacer(modifier = Modifier.height(MovoSpacing.lg))
        Text(
            text = stringResource(R.string.ui_the_webpage_cannot_be_opened_3db06d),
            style = MovoTypography.bodyStrong,
            color = MovoColors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                style = MovoTypography.labelRegular,
                color = MovoColors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(MovoSpacing.lg))
        // 主操作：浅 Indigo 底 + 深 Indigo 字（D6，不用饱和主色实底）。
        MovoPillButton(
            label = stringResource(R.string.ui_reload_5982c4),
            onClick = onRetry,
            primary = true,
        )
    }
}

@Composable
private fun BrowserWebViewHost(
    modifier: Modifier = Modifier,
    initialUrl: String?,
    onInitialUrlConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backgroundColor = MovoColors.bgSurface.toArgb()
    val container = remember(context) {
        FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(backgroundColor)
        }
    }
    DisposableEffect(container, context) {
        AgentBrowserSession.attachTo(container, context)
        onDispose { AgentBrowserSession.detachFrom(container) }
    }
    LaunchedEffect(container, initialUrl) {
        val url = initialUrl ?: return@LaunchedEffect
        // attachTo takes control and cancels the previous Agent operation. Only load
        // the clicked source after that cancellation, otherwise first entry is blank.
        onInitialUrlConsumed()
        if (url.isBlank()) return@LaunchedEffect
        // Clearing the pending URL cancels this effect, not the host's loading job.
        scope.launch(Dispatchers.IO) { AgentBrowserSession.navigateFromUser(context, url) }
    }
    AndroidView(
        factory = { container },
        update = { view -> view.setBackgroundColor(backgroundColor) },
        modifier = modifier,
    )
}
