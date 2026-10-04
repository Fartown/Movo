package io.github.fartown.movo.ui.app

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.components.movo.rememberLastNonNull
import io.github.fartown.movo.ui.components.movo.MovoFailureDialog
import io.github.fartown.movo.ui.components.movo.AgentInteractionCard
import io.github.fartown.movo.ui.components.movo.MovoConfirmDialog
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fartown.movo.MovoApp
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.device.DeviceLocationProvider
import io.github.fartown.movo.agent.device.RootAccess
import io.github.fartown.movo.agent.runtime.AgentConversationHandoff
import io.github.fartown.movo.core.AndroidAgentLogger
import io.github.fartown.movo.data.repository.RuntimeConfigRepository
import io.github.fartown.movo.ui.AppearanceSettingsScreen
import io.github.fartown.movo.ui.screens.settings.KimiWebEntry
import io.github.fartown.movo.ui.screens.settings.attentionLabel
import io.github.fartown.movo.ui.screens.settings.SettingsScreen
import io.github.fartown.movo.ui.screens.settings.SystemAssistantScreen
import io.github.fartown.movo.ui.screens.settings.ToolSettingsScreen
import io.github.fartown.movo.ui.model.AgentMemoryAction
import io.github.fartown.movo.ui.model.AgentSkillsAction
import io.github.fartown.movo.ui.model.AgentSystemEnhanceAction
import io.github.fartown.movo.ui.model.AgentToolsAction
import io.github.fartown.movo.ui.model.ConversationSummaryUi
import io.github.fartown.movo.ui.model.PermissionHealthAction
import io.github.fartown.movo.ui.navigation.AgentNavigator
import io.github.fartown.movo.ui.navigation.AppRoute
import io.github.fartown.movo.ui.screens.diagnostics.LocalRunLogOpener
import io.github.fartown.movo.ui.components.LocalOpenCapabilities
import io.github.fartown.movo.ui.pages.providers.ModelProviderDetailScreen
import io.github.fartown.movo.ui.pages.providers.ModelProviderListScreen
import io.github.fartown.movo.ui.pages.providers.ModelProviderMoreScreen
import io.github.fartown.movo.ui.screens.backup.DataBackupScreen
import io.github.fartown.movo.ui.screens.browser.AgentBrowserScreen
import io.github.fartown.movo.ui.screens.characters.CharacterLibraryScreen
import io.github.fartown.movo.ui.screens.characters.CharacterDetailScreen
import io.github.fartown.movo.ui.screens.characters.CharacterEditorScreen
import io.github.fartown.movo.ui.screens.characters.CharacterPersonaScreen
import io.github.fartown.movo.ui.screens.characters.CharacterMemoryScreen
import io.github.fartown.movo.ui.screens.enhance.SystemEnhanceScreen
import io.github.fartown.movo.ui.screens.mcp.McpServerDetailScreen
import io.github.fartown.movo.ui.screens.mcp.McpServersScreen
import io.github.fartown.movo.ui.screens.memory.AgentMemoryScreen
import io.github.fartown.movo.ui.screens.permissions.PermissionHealthScreen
import io.github.fartown.movo.ui.screens.skills.AgentSkillsScreen
import io.github.fartown.movo.ui.screens.terminal.LinuxEnvironmentScreen
import io.github.fartown.movo.ui.screens.terminal.LinuxFilesScreen
import io.github.fartown.movo.ui.screens.terminal.SharedFoldersScreen
import io.github.fartown.movo.ui.screens.terminal.TerminalEntryScreen
import io.github.fartown.movo.ui.screens.terminal.WorkspaceScreen
import io.github.fartown.movo.ui.screens.tools.AgentToolsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import io.github.fartown.movo.ui.components.movo.TextField
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

/**
 * Agent App 根组件：持有本地导航栈，并把 Screen actions 交给 [AgentAppState]。
 */
@Composable
internal fun AgentAppRoot(
    resultConversationHandoff: AgentConversationHandoff.Request? = null,
    onResultConversationOpened: (AgentConversationHandoff.Request, Boolean) -> Unit = { _, _ -> },
    browserUrl: String? = null,
    onBrowserOpened: () -> Unit = {},
    openVoiceSettings: Boolean = false,
    onVoiceSettingsOpened: () -> Unit = {},
) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val backStack = rememberNavBackStack<AppRoute>(AppRoute.Home)
    val navigator = remember(backStack) { AgentNavigator(backStack) }
    var navigationResetKey by rememberSaveable { mutableIntStateOf(0) }
    val appViewModel = viewModel<AgentAppViewModel>()
    val agentState = appViewModel.state
    val characterStore = viewModel<CharacterLibraryViewModel>().store
    val requestExecutionNotifications = rememberExecutionNotificationRequest()
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        agentState.refreshPermissionHealth()
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        agentState.refreshPermissionHealth()
        io.github.fartown.movo.agent.voice.MovoWakeWordController.refresh(context)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val rootFocusManager = LocalFocusManager.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // 退到后台（例如 Movo 去操作其他 App）时放下输入焦点：回来时系统不会恢复键盘盖住结果。
            if (event == Lifecycle.Event.ON_STOP) rootFocusManager.clearFocus()
            if (event == Lifecycle.Event.ON_RESUME) {
                RootAccess.refresh(context)
                appViewModel.refreshKimiWeb()
                agentState.refreshPermissionHealth()
                agentState.refreshRuntimeResults()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo.isWindowFocused) {
        // 关闭悬浮结果卡只恢复窗口焦点，前台 Activity 不一定再次收到 ON_RESUME。
        if (windowInfo.isWindowFocused) agentState.refreshRuntimeResults()
    }

    var conversationPaneOpen by remember { mutableStateOf(false) }
    // 侧边栏底部「设置」行要同步显示权限缺失（规范 8.6），打开时刷新一次。
    LaunchedEffect(conversationPaneOpen) {
        if (conversationPaneOpen) agentState.refreshPermissionHealth()
    }
    var conversationRenameTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var conversationDeleteTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var conversationExportTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    // 需要用户知情的失败（规范 8.11「不用 Toast」）：`Dialog/Info`，标题写哪件事没成，说明写原因。成功不再提示。
    var failureNotice by remember { mutableStateOf<AppFailureNotice?>(null) }
    val conversationExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        val target = conversationExportTarget
        conversationExportTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        uiScope.launch {
            try {
                val markdown = agentState.exportConversationMarkdown(target.id)
                    ?: error(context.getString(R.string.conversation_export_failed))
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error(context.getString(R.string.conversation_export_failed))
                withContext(Dispatchers.IO) {
                    output.use { it.write(markdown.toByteArray(Charsets.UTF_8)) }
                }
                // 导出成功：文件已写到用户刚选的位置，不另外提示（8.11 轻提示不用 Toast）。
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                failureNotice = AppFailureNotice(
                    title = context.getString(R.string.feedback_conversation_export_failed_title),
                    message = context.getString(R.string.feedback_write_file_failed),
                )
            }
        }
    }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(MovoApp.serviceInstance)
    }

    LaunchedEffect(resultConversationHandoff) {
        val request = resultConversationHandoff ?: return@LaunchedEffect
        // 浮层盖着时切会话：不播切换淡入、停在最新消息处，与浮层全屏时一致。
        io.github.fartown.movo.ui.screens.chat.ConversationSwitchMotion.skipNext()
        // 浮层「展开到 App」（Q4）：一开始就收起侧边栏，并在浮层仍盖着时播完，切过来时看到的就是会话页。
        val paneWasOpen = conversationPaneOpen
        conversationPaneOpen = false
        val opened = try {
            withTimeoutOrNull(8_000) { agentState.openResultConversation(request.target, request.runId) } == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        val then = AppHandoffRoute.consume()
        if (opened) {
            focusManager.clearFocus()
            conversationPaneOpen = false
            navigator.popToHome()
            // 浮层里点的是「查看日志」等：会话页之上接着打开那一页（浮层仍盖着，切过来时已经在目标页）。
            then?.let(navigator::push)
            // Let the original chat load its messages, compose and apply its scroll-to-latest before removing the
            // covering result window (a freshly created activity otherwise shows the conversation's first turn).
            withTimeoutOrNull(1_000) {
                snapshotFlow { agentState.homeState.messages.isNotEmpty() }.first { it }
            }
            withFrameNanos { }
            withFrameNanos { }
            withFrameNanos { }
            if (paneWasOpen) kotlinx.coroutines.delay(io.github.fartown.movo.ui.theme.MovoMotion.SLOW_EXIT.toLong())
        }
        onResultConversationOpened(request, opened)
    }

    fun pushRoute(
        route: AppRoute,
        restoreConversationPaneOnBack: Boolean = conversationPaneOpen,
    ) {
        conversationPaneOpen = restoreConversationPaneOnBack
        // 进入二级页时放下对话输入框的焦点，否则它在下层保持焦点，回到前台或切换语言后会在设置页弹出键盘。
        focusManager.clearFocus()
        navigator.push(route)
    }

    LaunchedEffect(browserUrl) {
        val url = browserUrl ?: return@LaunchedEffect
        focusManager.clearFocus()
        if (backStack.lastOrNull() != AppRoute.Browser) pushRoute(AppRoute.Browser)
        // The browser host consumes the URL only after it has attached its WebView.
    }

    LaunchedEffect(openVoiceSettings) {
        if (!openVoiceSettings) return@LaunchedEffect
        focusManager.clearFocus()
        if (backStack.lastOrNull() != AppRoute.VoiceSettings) pushRoute(AppRoute.VoiceSettings)
        onVoiceSettingsOpened()
    }

    fun popRoute() {
        if (navigator.pop()) return
        // 已经回到首页时不再关闭界面：返回动画期间，正在退场的二级页顶栏返回键仍能接到第二次点击，
        // 真机上「返回后 0.1 秒内再点左上角」会把 MainActivity 关掉（首页的左上角是菜单，同一个位置）。
        // 只有栈里唯一一页不是首页（外部直接打开的二级页）时，返回才关闭界面。
        if (navigator.current() is AppRoute.Home) return
        (context as? Activity)?.finish()
    }

    fun selectConversation(conversationId: String) {
        focusManager.clearFocus()
        agentState.selectConversation(conversationId)
        conversationPaneOpen = false
    }

    fun createConversation() {
        focusManager.clearFocus()
        agentState.createConversation()
        conversationPaneOpen = false
    }

    val openRunLog: (String?) -> Unit = { runId ->
        pushRoute(runId?.let(AppRoute::DiagnosticsRun) ?: AppRoute.Diagnostics)
    }

    val launchKimiWeb: () -> Unit = {
        requestExecutionNotifications()
        if (appViewModel.kimiWebState.phase != KimiWebPhase.NOT_INSTALLED) {
            appViewModel.launchKimiWeb { result ->
                if (result is KimiWebLaunchResult.Failed) {
                    failureNotice = AppFailureNotice(
                        title = context.getString(R.string.linux_kimi_web_failed_start),
                        message = result.message(context),
                    )
                }
            }
        } else {
            pushRoute(AppRoute.LinuxEnvironment)
        }
    }

    @Composable
    fun RoutedShell(
        route: AppRoute,
        content: @Composable () -> Unit,
    ) {
        AgentAppShell(
            currentRoute = route,
            isCurrentRoute = backStack.lastOrNull() == route,
            conversationPaneState = agentState.conversationPaneState,
            // 浮层「展开到 App」交接期间：侧边栏在本帧就按关闭绘制（MainActivity 一回到前台就可见，不能等动画）。
            isConversationPaneOpen = conversationPaneOpen && resultConversationHandoff == null,
            closeConversationPaneInstantly = resultConversationHandoff != null,
            showWelcomeAtmosphere = agentState.homeState.messages.isEmpty(),
            onBack = { popRoute() },
            onOpenConversationPane = { conversationPaneOpen = true },
            onDismissConversationPane = { conversationPaneOpen = false },
            onSearchConversations = { query -> agentState.updateSearchQuery(query) },
            onNewConversation = { createConversation() },
            onSelectConversation = { conversationId -> selectConversation(conversationId) },
            onConversationRename = { conversation ->
                conversationRenameTarget = conversation
            },
            onConversationExport = { conversation ->
                conversationExportTarget = conversation
                conversationExportLauncher.launch(
                    ConversationMarkdownExporter.defaultFileName(
                        title = conversation.title.ifBlank { conversation.preview },
                        fallback = context.getString(R.string.conversation_export_default_name),
                    ),
                )
            },
            onConversationDelete = { conversation ->
                conversationDeleteTarget = conversation
            },
            onOpenSettings = { pushRoute(AppRoute.Settings) },
            settingsAttention = agentState.permissionHealthState.attentionLabel(
                overlayOff = stringResource(R.string.movo_permission_overlay_off),
                accessibilityOff = stringResource(R.string.movo_permission_accessibility_off),
            ),
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 对话里失败或卡住的任务可以直达运行日志。
                CompositionLocalProvider(
                    LocalRunLogOpener provides openRunLog,
                    LocalOpenCapabilities provides { pushRoute(AppRoute.Tools) },
                    io.github.fartown.movo.ui.components.LocalOpenVoiceSettings provides { pushRoute(AppRoute.VoiceSettings) },
                    io.github.fartown.movo.ui.components.LocalOpenModelSettings provides { pushRoute(AppRoute.ModelProviders) },
                ) {
                    content()
                }
            }
        }
    }

    val swipeBackDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        NavSwipeDirection.RightToLeft
    } else {
        NavSwipeDirection.LeftToRight
    }
    val swipeDismiss = swipeBackDirection.takeIf {
        LocalAppearanceSettings.current.swipeDismissEnabled
    }
    key(navigationResetKey) {
    Box(modifier = Modifier.fillMaxSize()) {
        val reducedMotion = io.github.fartown.movo.ui.theme.LocalReducedMotion.current
        NavDisplay(
            backStack = backStack,
            // 系统返回（返回键、返回手势）：栈底时照常关闭界面；顶栏返回按钮走 [popRoute]（栈底为首页时不关闭）。
            onBack = {
                if (!navigator.pop()) (context as? Activity)?.finish()
            },
            transition = remember(reducedMotion) {
                io.github.fartown.movo.ui.components.movo.movoNavTransition(reducedMotion)
            },
            effects = NavDisplayEffects(
                cornerClipRadius = rememberNavSystemCornerRadius(),
            ),
        ) {
            entry<AppRoute.Home>(swipeDismiss = swipeDismiss) {
                RoutedShell(route = AppRoute.Home) {
                    AgentConversationContent(
                        isTopRoute = navigator.current() == AppRoute.Home || navigator.current() == AppRoute.Chat,
                        agentState = agentState,
                        onOpenBrowser = { pushRoute(AppRoute.Browser) },
                        isDrawerOpen = conversationPaneOpen,
                        // 规范 9.3.1「切换会话」：打开会话（冷启动、侧边栏切换、浮层展开到 App）停在最新消息处。
                        initiallyShowLatestMessage = true,
                    )
                }
            }
            entry<AppRoute.RunDetail> {
                // 执行详情页已取消（方案 B，执行记录在对话里原地展开）：恢复旧的导航状态时回到对话。
                LaunchedEffect(Unit) { navigator.popToHome() }
            }
            entry<AppRoute.Chat>(swipeDismiss = swipeDismiss) {
                // Restore old saved navigation into the one canonical conversation page.
                LaunchedEffect(Unit) { navigator.popToHome() }
            }
            entry<AppRoute.Browser>(swipeDismiss = swipeDismiss) {
                RoutedShell(route = AppRoute.Browser) {
                    AgentBrowserScreen(initialUrl = browserUrl, onInitialUrlConsumed = onBrowserOpened)
                }
            }
            entry<AppRoute.Terminal>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(Unit) { requestExecutionNotifications() }
                RoutedShell(route = AppRoute.Terminal) {
                    TerminalEntryScreen(
                        terminalStore = appViewModel.terminalStore,
                        consoleStore = appViewModel.consoleStore,
                        onOpenEnvironment = { pushRoute(AppRoute.LinuxEnvironment) },
                    )
                }
            }
            entry<AppRoute.Tools>(swipeDismiss = swipeDismiss) {
                AgentToolsScreen(
                    state = agentState.toolsState,
                    onAction = { action ->
                        when (action) {
                            AgentToolsAction.NavigateBack -> popRoute()
                            AgentToolsAction.OpenBrowser -> pushRoute(AppRoute.Browser)
                            AgentToolsAction.OpenEnhancements -> pushRoute(AppRoute.SystemEnhance)
                            AgentToolsAction.OpenPermissions -> pushRoute(AppRoute.Permissions)
                        }
                    },
                )
            }
            entry<AppRoute.Skills>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(Unit) {
                    agentState.refreshSkills()
                }
                AgentSkillsScreen(
                    state = agentState.skillsState,
                    onAction = { action ->
                        when (action) {
                            AgentSkillsAction.NavigateBack -> popRoute()
                            is AgentSkillsAction.ImportZip -> agentState.importSkillZip(action.uri)
                            AgentSkillsAction.ConfirmZipReplacement -> agentState.confirmSkillZipReplacement()
                            AgentSkillsAction.CancelZipReplacement -> agentState.cancelSkillZipReplacement()
                            AgentSkillsAction.DismissNotice -> agentState.dismissSkillNotice()
                            is AgentSkillsAction.ToggleSkill -> agentState.toggleSkill(action.skillId, action.enabled)
                            is AgentSkillsAction.DeleteSkill -> agentState.deleteSkill(action.skillId)
                            is AgentSkillsAction.ReinstallBuiltin -> agentState.reinstallBuiltin(action.skillId)
                        }
                    },
                )
            }
            entry<AppRoute.Characters>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(backStack.lastOrNull() == AppRoute.Characters) {
                    if (backStack.lastOrNull() == AppRoute.Characters) characterStore.loadLibrary()
                }
                CharacterLibraryScreen(characterStore, { if (navigator.current() == AppRoute.Characters) pushRoute(it) }, ::popRoute)
            }
            entry<AppRoute.CharacterDetail>(swipeDismiss = swipeDismiss) { route ->
                LaunchedEffect(route.characterId, backStack.lastOrNull() == route) {
                    if (backStack.lastOrNull() == route) characterStore.loadDetail(route.characterId)
                }
                CharacterDetailScreen(route.characterId, characterStore, { if (navigator.current() == route) pushRoute(it) }, ::popRoute) { binding, greeting ->
                    if (navigator.current() == route) {
                        agentState.startCharacterConversation(binding, greeting)
                        conversationPaneOpen = false
                        navigator.popToHome()
                        // 开始新故事直接呈现首页；重置导航呈现态，避免多层退栈扫过角色列表。
                        navigationResetKey++
                    }
                }
            }
            entry<AppRoute.CharacterEditor>(swipeDismiss = swipeDismiss) { route ->
                LaunchedEffect(route.characterId, backStack.lastOrNull() == route) {
                    if (backStack.lastOrNull() == route) characterStore.loadEditor(route.characterId)
                }
                CharacterEditorScreen(route.characterId, characterStore, ::popRoute) { id ->
                    if (navigator.current() == route) navigator.replace(AppRoute.CharacterDetail(id))
                }
            }
            entry<AppRoute.CharacterPersona>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(backStack.lastOrNull() == AppRoute.CharacterPersona) {
                    if (backStack.lastOrNull() == AppRoute.CharacterPersona) characterStore.loadPersona()
                }
                CharacterPersonaScreen(characterStore) {
                    if (navigator.current() == AppRoute.CharacterPersona) popRoute()
                }
            }
            entry<AppRoute.CharacterMemory>(swipeDismiss = swipeDismiss) { route ->
                LaunchedEffect(route.characterId, backStack.lastOrNull() == route) {
                    if (backStack.lastOrNull() == route) characterStore.loadMemory(route.characterId)
                }
                CharacterMemoryScreen(route.characterId, characterStore, ::popRoute)
            }
            entry<AppRoute.Permissions>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(Unit) {
                    agentState.refreshPermissionHealth()
                }
                PermissionHealthScreen(
                    state = agentState.permissionHealthState,
                    onRefresh = agentState::refreshPermissionHealth,
                    onAction = { action ->
                        when (action) {
                            PermissionHealthAction.NavigateBack -> popRoute()
                            is PermissionHealthAction.OpenItemAction -> {
                                when (action.itemId) {
                                    "accessibility" -> {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                        }
                                    }
                                    "overlay" -> {
                                        runCatching {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    Uri.parse("package:${context.packageName}")
                                                )
                                            )
                                        }
                                    }
                                    "microphone" -> {
                                        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                    "background" -> {
                                        if (RootAccess.isGranted && Build.MANUFACTURER.lowercase() in setOf("oppo", "realme", "oneplus")) {
                                            uiScope.launch(Dispatchers.IO) {
                                                BoundedRootCommandExecutor(AndroidAgentLogger).use {
                                                    it.execute(
                                                        "am start --user current -n " +
                                                            "com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerControlActivity " +
                                                            "--es title Movo --es pkgName io.github.fartown.movo --es drainType APP",
                                                    )
                                                }
                                            }
                                        } else {
                                            runCatching {
                                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                            }
                                        }
                                    }
                                    "app_list" -> {
                                        runCatching {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.parse("package:${context.packageName}")
                                                )
                                            )
                                        }
                                    }
                                    "location" -> {
                                        when (DeviceLocationProvider.accessState(context)) {
                                            DeviceLocationProvider.AccessState.DENIED -> {
                                                locationPermissionLauncher.launch(
                                                    arrayOf(
                                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                                    )
                                                )
                                            }
                                            DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(
                                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                            Uri.parse("package:${context.packageName}")
                                                        )
                                                    )
                                                }
                                            }
                                            DeviceLocationProvider.AccessState.DISABLED -> {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                                    )
                                                }
                                            }
                                            DeviceLocationProvider.AccessState.AVAILABLE -> {
                                                agentState.refreshPermissionHealth()
                                            }
                                        }
                                    }
                                    "notification_history" -> {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                        }
                                    }
                                    "usage_access" -> {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                        }
                                    }
                                    "notifications" -> {
                                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                                    }
                                    "root" -> pushRoute(AppRoute.SystemEnhance)
                                }
                            }
                        }
                    },
                )
            }
            entry<AppRoute.SystemEnhance>(swipeDismiss = swipeDismiss) {
                SystemEnhanceScreen(
                    onAction = { action ->
                        when (action) {
                            AgentSystemEnhanceAction.NavigateBack -> popRoute()
                            AgentSystemEnhanceAction.RequestRoot -> { RootAccess.request(context) }
                            AgentSystemEnhanceAction.RefreshRoot -> { RootAccess.refresh(context) }
                        }
                    },
                )
            }
            entry<AppRoute.Workspace>(swipeDismiss = swipeDismiss) {
                WorkspaceScreen(onBack = ::popRoute)
            }
            entry<AppRoute.Settings>(swipeDismiss = swipeDismiss) {
                SettingsScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                    permissionHealth = agentState.permissionHealthState,
                    onRefreshPermissions = agentState::refreshPermissionHealth,
                    kimiWeb = KimiWebEntry(
                        label = appViewModel.kimiWebState.actionLabel(context),
                        canStop = appViewModel.kimiWebState.canStop,
                        onLaunch = launchKimiWeb,
                        onStop = appViewModel::stopKimiWeb,
                        onRefresh = appViewModel::refreshKimiWeb,
                    ),
                )
            }
            entry<AppRoute.ToolSettings>(swipeDismiss = swipeDismiss) {
                ToolSettingsScreen(onNavigate = { route -> pushRoute(route) }, onBack = ::popRoute)
            }
            entry<AppRoute.SystemAssistant>(swipeDismiss = swipeDismiss) {
                SystemAssistantScreen(onNavigate = { route -> pushRoute(route) }, onBack = ::popRoute)
            }
            entry<AppRoute.VoiceSettings>(swipeDismiss = swipeDismiss) {
                io.github.fartown.movo.ui.screens.voice.VoiceSettingsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.AppearanceSettings>(swipeDismiss = swipeDismiss) {
                AppearanceSettingsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.Diagnostics>(swipeDismiss = swipeDismiss) {
                io.github.fartown.movo.ui.screens.diagnostics.DiagnosticsScreen(
                    onBack = ::popRoute,
                    onOpenRun = { runId -> pushRoute(AppRoute.DiagnosticsRun(runId)) },
                    onOpenSystem = { pushRoute(AppRoute.DiagnosticsSystem) },
                )
            }
            entry<AppRoute.DiagnosticsRun>(swipeDismiss = swipeDismiss) { route ->
                io.github.fartown.movo.ui.screens.diagnostics.DiagnosticsRunScreen(
                    runId = route.runId,
                    onBack = ::popRoute,
                    onOpenModelSettings = { pushRoute(AppRoute.ModelProviders) },
                    onOpenConversation = { conversationId ->
                        selectConversation(conversationId)
                        navigator.popToHome()
                    },
                )
            }
            entry<AppRoute.DiagnosticsSystem>(swipeDismiss = swipeDismiss) {
                io.github.fartown.movo.ui.screens.diagnostics.DiagnosticsSystemScreen(onBack = ::popRoute)
            }
            entry<AppRoute.DataBackup>(swipeDismiss = swipeDismiss) {
                DataBackupScreen(
                    context = context,
                    onBack = ::popRoute,
                    onExport = agentState::exportBackup,
                    onImport = agentState::importBackup,
                )
            }
            entry<AppRoute.Memory>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(Unit) {
                    agentState.refreshMemory()
                }
                AgentMemoryScreen(
                    state = agentState.memoryState,
                    onAction = { action ->
                        when (action) {
                            AgentMemoryAction.NavigateBack -> popRoute()
                            is AgentMemoryAction.ToggleEnabled -> agentState.setMemoryEnabled(action.enabled)
                            is AgentMemoryAction.DraftChanged -> agentState.updateMemoryDraft(action.content)
                            AgentMemoryAction.Save -> agentState.saveMemory()
                            AgentMemoryAction.Clear -> agentState.clearMemory()
                            AgentMemoryAction.DismissNotice -> agentState.dismissMemoryNotice()
                        }
                    },
                )
            }
            entry<AppRoute.LinuxEnvironment>(swipeDismiss = swipeDismiss) {
                LinuxEnvironmentScreen(
                    context = context,
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.SharedFolders>(swipeDismiss = swipeDismiss) {
                SharedFoldersScreen(
                    context = context,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.LinuxFiles>(swipeDismiss = swipeDismiss) { route ->
                LinuxFilesScreen(
                    context = context,
                    distribution = route.distribution,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.ModelProviders>(swipeDismiss = swipeDismiss) {
                ModelProviderListScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute
                )
            }
            entry<AppRoute.McpServers>(swipeDismiss = swipeDismiss) {
                McpServersScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.McpServerDetail>(swipeDismiss = swipeDismiss) { route ->
                McpServerDetailScreen(
                    serverId = route.serverId,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.ModelProviderDetail>(swipeDismiss = swipeDismiss) { route ->
                ModelProviderDetailScreen(
                    providerId = route.providerId,
                    onBack = ::popRoute
                )
            }
            entry<AppRoute.ModelProviderNew>(swipeDismiss = swipeDismiss) { route ->
                ModelProviderDetailScreen(
                    newType = route.providerType,
                    onBack = ::popRoute
                )
            }
            entry<AppRoute.ModelProviderTemplate>(swipeDismiss = swipeDismiss) { route ->
                ModelProviderDetailScreen(
                    templateId = route.templateId,
                    onBack = ::popRoute
                )
            }
            entry<AppRoute.ModelProviderMore>(swipeDismiss = swipeDismiss) {
                ModelProviderMoreScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute
                )
            }
        }
    }
    }

    // 角色库的失败（读取、保存、导入、导出等）：`Dialog/Info` + 具体标题；成功由角色页面就地反馈。
    val characterNotice = rememberLastNonNull(characterStore.notice)
    MovoFailureDialog(
        show = characterStore.notice != null,
        title = characterNotice?.title.orEmpty(),
        message = characterNotice?.message.orEmpty(),
        onDismiss = characterStore::dismissNotice,
    )

    val appFailure = rememberLastNonNull(failureNotice)
    MovoFailureDialog(
        show = failureNotice != null,
        title = appFailure?.title.orEmpty(),
        message = appFailure?.message.orEmpty(),
        onDismiss = { failureNotice = null },
    )

    // 重命名（规范 8.11 `Dialog/Confirm` + 输入框）：退场动画期间保留原会话与输入。
    val renaming = rememberLastNonNull(conversationRenameTarget)
    var renameInput by remember(renaming?.id) { mutableStateOf(renaming?.title.orEmpty()) }
    MovoConfirmDialog(
        show = conversationRenameTarget != null,
        title = stringResource(R.string.conversation_rename_title),
        message = null,
        confirmText = stringResource(R.string.action_save),
        confirmEnabled = renameInput.isNotBlank(),
        onDismissRequest = { conversationRenameTarget = null },
        onConfirm = {
            val conversation = conversationRenameTarget ?: return@MovoConfirmDialog
            agentState.renameConversation(conversation.id, renameInput)
            conversationRenameTarget = null
        },
        extraContent = {
            Spacer(Modifier.height(MovoSpacing.lg))
            TextField(
                value = renameInput,
                onValueChange = { renameInput = it },
                label = stringResource(R.string.conversation_rename_hint),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )

    // 删除对话（规范 8.11 举例）：危险确认 = 标题前 Rose 警示图标 + bg/inverse 白字确认按钮。
    MovoConfirmDialog(
        show = conversationDeleteTarget != null,
        title = stringResource(R.string.conversation_delete_title),
        message = stringResource(R.string.conversation_delete_message),
        confirmText = stringResource(R.string.action_delete),
        destructive = true,
        onDismissRequest = { conversationDeleteTarget = null },
        onConfirm = {
            val conversation = conversationDeleteTarget ?: return@MovoConfirmDialog
            agentState.deleteConversation(conversation.id)
            conversationDeleteTarget = null
        },
    )

    // 同步交互卡（提问 / 审批，实施方案 §6.1）：底部锚定、背景静止；作答经 AgentAppState 回传给等待中的 run。
    AgentInteractionCard(
        state = agentState.activeInteraction,
        onApprove = { remember -> agentState.submitInteractionApproval(approved = true, remember = remember) },
        onDecline = { agentState.submitInteractionApproval(approved = false, remember = false) },
        onAnswer = { text, optionIndex -> agentState.submitInteractionAnswer(text, optionIndex) },
        onCancel = { agentState.cancelInteraction() },
    )
}

/** App 根部的失败说明（`Dialog/Info`）。 */
private data class AppFailureNotice(val title: String, val message: String)
