package io.github.fartown.movo.ui.design

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import io.github.fartown.movo.data.model.AppearanceSettings
import io.github.fartown.movo.ui.app.AgentAppTheme
import io.github.fartown.movo.ui.model.PermissionHealthItemUi
import io.github.fartown.movo.ui.model.PermissionHealthUiState
import io.github.fartown.movo.ui.model.PermissionStatusUi
import io.github.fartown.movo.ui.screens.permissions.PermissionHealthScreen
import io.github.fartown.movo.ui.screens.settings.KimiWebEntry
import io.github.fartown.movo.ui.screens.settings.SettingsScreen
import io.github.fartown.movo.ui.screens.settings.SystemAssistantScreen
import io.github.fartown.movo.ui.screens.settings.ToolSettingsScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.data.model.ReasoningEffort
import io.github.fartown.movo.ui.app.AgentAppShell
import io.github.fartown.movo.ui.components.AgentChatInputBar
import io.github.fartown.movo.ui.components.AgentWorkProcess
import io.github.fartown.movo.ui.components.ChatMessageItem
import io.github.fartown.movo.ui.components.MovoHomeContent
import io.github.fartown.movo.ui.model.AgentContextUsageUi
import io.github.fartown.movo.ui.model.AgentModelPickerUiState
import io.github.fartown.movo.ui.model.ConversationModeUi
import io.github.fartown.movo.ui.model.ConversationPaneUiState
import io.github.fartown.movo.ui.model.ConversationSummaryUi
import io.github.fartown.movo.ui.model.ThinkingMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.UserMessageUi
import io.github.fartown.movo.ui.navigation.AppRoute
import io.github.fartown.movo.ui.theme.MovoColors
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 设计还原截图：在 412 × 917dp 的画布上渲染改版后的真实页面，与 Figma 定稿对照。
 * 默认跳过；`./gradlew :app:testDebugUnitTest -PmovoShots=true --tests '*DesignShotsTest*'` 生成到 .docs/design-restore/shots/local/。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "zh-rCN-w412dp-h917dp-xxhdpi")
class DesignShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun enabled() {
        assumeTrue(System.getProperty("movo.shots") == "true")
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            AgentAppTheme(appearance = AppearanceSettings(), applyInterfaceScale = false) { content() }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(System.getProperty("movo.shots.dir") ?: "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val permissions = PermissionHealthUiState(
        listOf(
            PermissionHealthItemUi("background", "后台运行权限", "", PermissionStatusUi.Available, null),
            PermissionHealthItemUi("overlay", "悬浮窗权限", "", PermissionStatusUi.Missing, "去授权"),
            PermissionHealthItemUi("microphone", "麦克风", "语音输入与唤醒词需要", PermissionStatusUi.Available, null),
            PermissionHealthItemUi("app_list", "应用列表读取", "", PermissionStatusUi.Available, null),
            PermissionHealthItemUi("location", "位置权限", "仅在 Agent 调用工具时读取", PermissionStatusUi.Available, null),
            PermissionHealthItemUi("notification_history", "通知使用权", "", PermissionStatusUi.Missing, "去授权"),
            PermissionHealthItemUi("usage_access", "使用情况访问", "", PermissionStatusUi.Missing, "去授权"),
            PermissionHealthItemUi("accessibility", "无障碍权限", "", PermissionStatusUi.Available, null),
            PermissionHealthItemUi("notifications", "通知", "", PermissionStatusUi.Available, "去设置"),
            PermissionHealthItemUi("root", "Root 与系统增强", "可选", PermissionStatusUi.Disabled, "去开启"),
        ),
    )

    @Test
    fun settings() = shot("18-settings") {
        SettingsScreen(
            onNavigate = {},
            onBack = {},
            permissionHealth = permissions,
            onRefreshPermissions = {},
            kimiWeb = KimiWebEntry(label = "启动", canStop = false, onLaunch = {}, onStop = {}, onRefresh = {}),
        )
    }

    @Test
    fun toolSettings() = shot("19-settings-tools") { ToolSettingsScreen(onNavigate = {}, onBack = {}) }

    @Test
    fun systemAssistant() = shot("settings-system-assistant") { SystemAssistantScreen(onNavigate = {}, onBack = {}) }

    @Test
    fun permissions() = shot("settings-permissions") { PermissionHealthScreen(state = permissions, onAction = {}) }

    private fun pane(): ConversationPaneUiState {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        fun c(id: String, title: String, ago: Long, running: Boolean = false, character: String? = null) =
            ConversationSummaryUi(id, title, "", "", now - ago, ConversationModeUi.Chat, isActiveRun = running, characterName = character)
        return ConversationPaneUiState(
            conversations = listOf(
                c("1", "点一杯生椰拿铁", 0, running = true),
                c("2", "整理快递取件码", 3_600_000),
                c("3", "设个取件提醒", 7_200_000),
                c("4", "周末去杭州的行程", day),
                c("5", "帮我回复房东的消息", day + 1000),
                c("6", "睡前聊聊天", 5 * day, character = "小满"),
                c("7", "比较两款降噪耳机", 5 * day + 1000),
            ),
            selectedConversationId = "2",
            searchQuery = "",
        )
    }

    @Test
    fun home() = shot("01-home") {
        AgentAppShell(
            currentRoute = AppRoute.Home, isCurrentRoute = true, conversationPaneState = pane().copy(selectedConversationId = "x"),
            isConversationPaneOpen = false, onBack = {}, onOpenConversationPane = {}, onDismissConversationPane = {},
            onSearchConversations = {}, onNewConversation = {}, onSelectConversation = {}, onConversationRename = {},
            onConversationExport = {}, onConversationDelete = {}, onOpenSettings = {},
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                MovoHomeContent(characterName = null, showCapabilities = true, onSend = {}, modifier = Modifier.weight(1f))
                Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) { composer(running = false) }
            }
        }
    }

    @Test
    fun drawer() = shot("16-drawer") {
        AgentAppShell(
            currentRoute = AppRoute.Home, isCurrentRoute = true, conversationPaneState = pane(),
            isConversationPaneOpen = true, onBack = {}, onOpenConversationPane = {}, onDismissConversationPane = {},
            onSearchConversations = {}, onNewConversation = {}, onSelectConversation = {}, onConversationRename = {},
            onConversationExport = {}, onConversationDelete = {}, onOpenSettings = {}, settingsAttention = "悬浮窗未开启",
        ) { padding -> Box(Modifier.fillMaxSize().padding(padding)) }
    }

    @Composable
    private fun composer(running: Boolean, text: String = "") {
        AgentChatInputBar(
            input = text, modelPickerState = AgentModelPickerUiState(), isCompacting = false,
            contextUsage = AgentContextUsageUi(40_000, 128_000), showContextUsage = !running && text.isEmpty(),
            isStreaming = running, reasoningEffort = ReasoningEffort.DEFAULT,
            availableReasoningEfforts = listOf(ReasoningEffort.OFF, ReasoningEffort.DEFAULT, ReasoningEffort.HIGH),
            pendingImages = emptyList(), pendingFileReferences = emptyList(), isEditingMessage = false,
            editHasLaterTurns = false, preserveFollowingMessages = false, onReasoningEffortChange = {},
            onCompactContext = {}, canCompactContext = false, onModelSelected = {}, onSubmit = {}, onStop = {},
            onAttachImage = {}, onRemoveImage = {}, onAttachFiles = {}, onAttachFolder = {}, onAttachFilePath = {},
            onRemoveFileReference = {}, onCancelMessageEdit = {}, onToggleListen = {},
        )
    }

    @Test
    fun running() = shot("05-running") {
        Column(Modifier.fillMaxSize().background(MovoColors.bgCanvas).padding(top = 56.dp), verticalArrangement = Arrangement.Top) {
            ChatMessageItem(
                message = UserMessageUi("u1", "在美团给我点一杯瑞幸生椰拿铁，少冰，送到公司"),
                onSuggestionClick = {}, onRunTraceClick = {}, onOpenBrowser = {}, showBrowserShortcut = false,
            )
            AgentWorkProcess(
                id = "w1",
                messages = listOf(
                    *steps(),
                ),
                onOpenBrowser = {}, currentBrowserMessageId = null, retainedStreamingStates = emptyMap(),
            )
            Box(Modifier.weight(1f))
            Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) { composer(running = true) }
            Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) { composer(running = true, text = "改成大杯") }
        }
    }

    private fun steps(running: Boolean = true): Array<io.github.fartown.movo.ui.model.AgentChatMessageUi> {
        val t = System.currentTimeMillis() - 18_000
        return arrayOf(
            ThinkingMessageUi("t1", "打开美团外卖，搜索瑞幸咖啡，按要求选好规格后加入购物车。", isStreaming = false, elapsedSeconds = 2),
            ToolActivityMessageUi("a1", "launch_app", ToolActivityStatusUi.Success, "打开「美团」", resultSummary = "已打开 · 美团", startedAtMillis = t, finishedAtMillis = t + 800),
            ToolActivityMessageUi("a2", "tap", ToolActivityStatusUi.Success, "搜索「瑞幸 生椰拿铁」", resultSummary = "最近门店 320m·月售 2000+", startedAtMillis = t + 900, finishedAtMillis = t + 3000),
            UserMessageUi("user-run-1-supplement-0", "改成大杯"),
            if (running) {
                ToolActivityMessageUi("a3", "tap", ToolActivityStatusUi.Running, "选择规格", startedAtMillis = t + 3100)
            } else {
                ToolActivityMessageUi("a3", "tap", ToolActivityStatusUi.Success, "选择规格", resultSummary = "大杯·少冰", startedAtMillis = t + 3100, finishedAtMillis = t + 9000)
            },
        )
    }

    @Test
    fun runDetail() = shot("21-run-detail") {
        io.github.fartown.movo.ui.screens.run.RunDetailScreen(
            title = "点一杯生椰拿铁", steps = steps(running = false).toList(), onBack = {}, onOpenBrowser = {},
            onSwitchToApp = null, composer = {},
        )
    }

    @Test
    fun runDetailRunning() = shot("20-run-detail-running") {
        io.github.fartown.movo.ui.screens.run.RunDetailScreen(
            title = "点一杯生椰拿铁", steps = steps().toList(), onBack = {}, onOpenBrowser = {},
            onSwitchToApp = {}, composer = { composer(running = true) },
        )
    }

    private fun overlayState(phase: io.github.fartown.movo.agent.overlay.AgentOverlayPhase): io.github.fartown.movo.agent.overlay.AgentOverlayState {
        val now = System.currentTimeMillis()
        return io.github.fartown.movo.agent.overlay.AgentOverlayState(
            phase = phase,
            status = io.github.fartown.movo.agent.overlay.AgentOverlayStatus.RunningTool("tap"),
            steps = listOf(
                io.github.fartown.movo.agent.overlay.OverlayStep("1", "打开「美团」", io.github.fartown.movo.agent.overlay.OverlayStepStatus.DONE),
                io.github.fartown.movo.agent.overlay.OverlayStep("2", "搜索「瑞幸 生椰拿铁」", io.github.fartown.movo.agent.overlay.OverlayStepStatus.DONE),
                io.github.fartown.movo.agent.overlay.OverlayStep("3", "点击「少冰」", io.github.fartown.movo.agent.overlay.OverlayStepStatus.RUNNING),
                io.github.fartown.movo.agent.overlay.OverlayStep("4", "选择规格", io.github.fartown.movo.agent.overlay.OverlayStepStatus.RUNNING),
            ),
            startedAtMillis = now - 18_000,
            pausedAtMillis = if (phase == io.github.fartown.movo.agent.overlay.AgentOverlayPhase.PAUSED) now else null,
        )
    }

    @Test
    fun overlay() = shot("07-overlay") {
        Column(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFE9E4DC)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                io.github.fartown.movo.agent.overlay.OrbMode.entries.forEach { mode ->
                    io.github.fartown.movo.agent.overlay.AgentOverlayOrb(mode = mode, onTap = {})
                }
            }
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 移除区 + 吸附时的悬浮球（放大 1.1）
                io.github.fartown.movo.agent.overlay.AgentOverlayRemoveZone(visible = true)
                io.github.fartown.movo.agent.overlay.AgentOverlayOrb(mode = io.github.fartown.movo.agent.overlay.OrbMode.STANDBY, onTap = {}, engaged = true)
            }
            io.github.fartown.movo.agent.overlay.AgentOverlayBubble(
                state = overlayState(io.github.fartown.movo.agent.overlay.AgentOverlayPhase.RUNNING),
                onCollapse = {}, onPause = {}, onResume = {}, onStop = {}, onSupplementModeChange = {}, onSupplement = {},
            )
            io.github.fartown.movo.agent.overlay.AgentOverlayBubble(
                state = overlayState(io.github.fartown.movo.agent.overlay.AgentOverlayPhase.PAUSED),
                onCollapse = {}, onPause = {}, onResume = {}, onStop = {}, onSupplementModeChange = {}, onSupplement = {},
            )
            // 语音模式：聆听中、字幕超过 3 行（顶部淡出）
            io.github.fartown.movo.agent.overlay.AgentOverlayBubble(
                state = overlayState(io.github.fartown.movo.agent.overlay.AgentOverlayPhase.RUNNING),
                onCollapse = {}, onPause = {}, onResume = {}, onStop = {}, onSupplementModeChange = {}, onSupplement = {},
                voice = io.github.fartown.movo.agent.voice.session.VoiceSessionUiState(
                    channel = io.github.fartown.movo.agent.voice.session.VoiceChannel.Hearing,
                    transcript = "改成去冰吧，再少一点糖，然后看看有没有满减券可以用，没有的话就直接下单，地址用公司那个，送到前台就行",
                ),
            )
        }
    }
}
