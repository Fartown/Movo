package io.github.fartown.movo.ui.design

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.fartown.movo.agent.tools.core.ToolUiBlock as B
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.github.fartown.movo.ui.components.AgentChatInputBar
import io.github.fartown.movo.ui.components.AgentConversationMessages
import io.github.fartown.movo.ui.components.ChatBottomReserve
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

    /** 定稿 23-6：运行日志页最下面的「完整日志」卡（开关、清空、保存说明）。 */
    @Test
    fun runLogFullLogCard() {
        val diagnostics = io.github.fartown.movo.diagnostics.MemoryDiagnostics
        diagnostics.buffer.clear()
        fun run(id: String, vararg events: String) = events.forEach { event ->
            diagnostics.record("runtime", event, context = io.github.fartown.movo.diagnostics.DiagnosticContext(run = id))
        }
        run("R801", "run.started", "run.completed", "run.ended")
        run("R802", "run.started", "run.failed", "run.ended")
        val root = java.nio.file.Files.createTempDirectory("run-log-shot").toFile()
        val store = io.github.fartown.movo.diagnostics.runlog.RunLogStore(root, elapsedClock = { 0L })
        io.github.fartown.movo.diagnostics.runlog.RunLog.install(store, "shot") { true }
        try {
            val session = store.open("R801")!!
            session.record("tool_end", mapOf("result" to "x".repeat(40_000)))
            store.close(session, io.github.fartown.movo.diagnostics.runlog.RunLogRecord("run_end", 0, 0, mapOf("status" to "completed"), control = true))
            store.awaitIdle()
            shot("23-06-run-log-full-log-card") {
                io.github.fartown.movo.ui.screens.diagnostics.DiagnosticsScreen(onBack = {}, onOpenRun = {}, onOpenSystem = {})
            }
        } finally {
            io.github.fartown.movo.diagnostics.runlog.RunLog.install(null, "", { false })
            store.shutdown()
            diagnostics.buffer.clear()
        }
    }

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

    /** 定稿 22 · 监听任务：悬浮球监听中 / 暂停只留角标，展开卡监听中（一个、两个）与已结束·撤销。 */
    @Test
    fun overlayMonitoring() = shot("22-overlay-monitoring") {
        val deadline = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 17); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
        val water = io.github.fartown.movo.agent.overlay.OverlayMonitor("喝水提醒", eventCount = 3, deadlineAtMillis = deadline)
        val battery = io.github.fartown.movo.agent.overlay.OverlayMonitor("电量播报", eventCount = 1, deadlineAtMillis = deadline)
        Column(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFE9E4DC)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(
                    io.github.fartown.movo.agent.overlay.OrbMode.RUNNING,
                    io.github.fartown.movo.agent.overlay.OrbMode.PAUSED,
                    io.github.fartown.movo.agent.overlay.OrbMode.MONITORING,
                    io.github.fartown.movo.agent.overlay.OrbMode.FINISHED,
                    io.github.fartown.movo.agent.overlay.OrbMode.STANDBY,
                ).forEach { mode -> io.github.fartown.movo.agent.overlay.AgentOverlayOrb(mode = mode, onTap = {}) }
            }
            listOf(
                io.github.fartown.movo.agent.overlay.OverlayTaskPanel.Monitoring(listOf(water)),
                io.github.fartown.movo.agent.overlay.OverlayTaskPanel.Monitoring(listOf(water, battery)),
                io.github.fartown.movo.agent.overlay.OverlayTaskPanel.Ended("e1", listOf("喝水提醒")),
            ).forEach { panel ->
                io.github.fartown.movo.agent.overlay.AgentOverlayBubble(
                    state = io.github.fartown.movo.agent.overlay.AgentOverlayState.Initial,
                    onCollapse = {}, onPause = {}, onResume = {}, onStop = {}, onSupplementModeChange = {}, onSupplement = {},
                    taskPanel = panel,
                )
            }
        }
    }

    // ---- 定稿 20 · 工具步骤可视化：默认折叠，点一步原地展开 ----

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(System.getProperty("movo.shots.dir") ?: "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun settingsShot(): io.github.fartown.movo.agent.model.AgentModelClient.ModelImage {
        val bmp = Bitmap.createBitmap(390, 844, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.rgb(244, 243, 240))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f; color = android.graphics.Color.rgb(21, 21, 21) }
        val card = android.graphics.Paint().apply { color = android.graphics.Color.WHITE }
        canvas.drawRoundRect(20f, 60f, 320f, 116f, 28f, 28f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(228, 228, 228) })
        canvas.drawText("蓝牙", 70f, 98f, paint)
        canvas.drawRoundRect(20f, 150f, 370f, 710f, 28f, 28f, card)
        listOf("蓝牙", "蓝牙设备黑名单", "显示没有名称的蓝牙设备", "始终保持蓝牙开启", "蓝牙设备解锁").forEachIndexed { i, t ->
            canvas.drawText(t, 40f, 210f + i * 104f, paint)
        }
        val out = java.io.ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return io.github.fartown.movo.agent.model.AgentModelClient.ModelImage(
            reference = "data:image/png;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP),
            mimeType = "image/png", bytes = out.size(), source = "screen",
        )
    }

    /** 定稿 24：正文一律在卡外，思考与工具按顺序成卡（执行中）；一轮结束后回答上方的过程收成摘要条。文案取自 10-07 方舟真实运行。 */
    @Test
    fun turnSegmentsAndFold() {
        val t = System.currentTimeMillis() - 30_000
        fun step(id: String, title: String, summary: String, at: Long) =
            ToolActivityMessageUi(id, "device_read", ToolActivityStatusUi.Success, title, resultSummary = summary, startedAtMillis = t + at, finishedAtMillis = t + at + 300)
        val messages = listOf(
            io.github.fartown.movo.ui.model.UserMessageUi("u1", "查一下电量和存储空间，分两次查", runStartedAtMillis = t, runFinishedAtMillis = t + 18_000),
            ThinkingMessageUi(id = "run-thinking-1-0", content = "Two separate checks: battery first, then storage.", isStreaming = false, elapsedSeconds = 2, collapsed = true),
            io.github.fartown.movo.ui.model.AgentMessageUi(id = "assistant-run-1-1", content = "我先查一下当前电量。", isStreaming = false),
            step("run-tool-1-a", "查看设备状态 · 电池", "电量 100%（充电中）", 3_000),
            ThinkingMessageUi(id = "run-thinking-2-0", content = "Battery done. Now storage.", isStreaming = false, elapsedSeconds = 1, collapsed = true),
            io.github.fartown.movo.ui.model.AgentMessageUi(id = "assistant-run-2-1", content = "电量是 100%，正在充电。接下来查存储。", isStreaming = false),
            step("run-tool-2-a", "查看设备状态 · 存储", "可用约 201 GB，共 256 GB", 6_000),
            ThinkingMessageUi(id = "run-thinking-3-0", content = "Both done. Summarise for the user.", isStreaming = false, elapsedSeconds = 1, collapsed = true),
            io.github.fartown.movo.ui.model.AgentMessageUi(id = "assistant-run-3-1", content = "两项都查好了：\n\n- **电量**：100%，正在充电\n- **存储**：可用约 201 GB（共 256 GB）", isStreaming = false),
        )
        var streaming by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            AgentAppTheme(appearance = AppearanceSettings(), applyInterfaceScale = false) {
                AgentConversationMessages(
                    visibleMessages = messages,
                    scrollState = androidx.compose.foundation.lazy.rememberLazyListState(),
                    bottomReserve = androidx.compose.runtime.remember { ChatBottomReserve() },
                    isStreaming = streaming,
                    bottomInset = 24.dp,
                    keepBottomAnchored = false,
                    onBottomAnchorChanged = {},
                    modifier = Modifier.fillMaxSize().background(MovoColors.bgCanvas),
                )
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        capture("24-04-running-segments")
        streaming = false
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        capture("24-05-folded")
    }

    @Test
    fun toolSteps() {
        io.github.fartown.movo.agent.media.ToolStepImages.clearForTests()
        val key = io.github.fartown.movo.agent.media.ToolStepImages.put("obs-1", 0, settingsShot())!!
        val t = System.currentTimeMillis() - 60_000
        fun step(id: String, tool: String, title: String, summary: String, view: io.github.fartown.movo.agent.tools.core.ToolUiView?, at: Long, ms: Long,
                 status: ToolActivityStatusUi = ToolActivityStatusUi.Success, command: String? = null, images: Int = 0) =
            ToolActivityMessageUi(id, tool, status, title, command = command, resultSummary = summary, imageCount = images,
                startedAtMillis = t + at, finishedAtMillis = t + at + ms, view = view)
        fun view(summary: String, vararg blocks: io.github.fartown.movo.agent.tools.core.ToolUiBlock, transient: Boolean = false) =
            io.github.fartown.movo.agent.tools.core.ToolUiView(summary, blocks.toList(), transient)
        val messages = listOf(
            step("s1", "app_open", "打开「设置」", "已打开「设置」", view("已打开「设置」"), 0, 300),
            step("s2", "ui_observe", "查看屏幕", "「设置」 · 28 个元素 · 截图", view("「设置」 · 28 个元素 · 截图", B.Images(listOf(key)), transient = true), 400, 400, images = 1),
            step("s3", "ui_tap", "点按「搜索系统设置项」", "已点按", view("已点按"), 900, 8000),
            step("s4", "ui_input", "输入「蓝牙」", "已输入", view("已输入"), 9000, 300),
            step("s5", "terminal_run", "运行 · find /sdcard/Download -name '*…", "退出码 0 · 输出 3 行",
                view("退出码 0 · 输出 3 行", B.Output("/sdcard/Download/run-1001.log\n/sdcard/Download/run-1002.log\n/sdcard/Download/crash.log", label = "输出")),
                9400, 1200, command = "find /sdcard/Download -name '*.log' -mtime +7"),
            step("s6", "personal_search", "搜索短信「快递」", "找到 3 条", view("找到 3 条",
                B.Items(listOf(B.Item("【菜鸟驿站】取件通知", "10086 · 今天 14:05"), B.Item("【顺丰】派送中", "95338 · 今天 09:12"), B.Item("【京东】已签收", "10月5日 18:40"))), transient = true), 10700, 600),
            step("s7", "setting_write", "修改设置 · 屏幕亮度", "100 → 80", view("100 → 80", B.Change("100", "80", label = "屏幕亮度")), 11400, 300),
            step("s8", "memory_write", "记住一条", "已记住", view("已记住", B.Preview("我对花生过敏，点外卖时避开含花生的菜。", label = "记住"), transient = true), 11800, 100),
            step("s9", "terminal_run", "运行 · ls /sdcard/no_such_dir", "失败 · 退出码 1",
                view("失败 · 退出码 1", B.Output("ls: /sdcard/no_such_dir: No such file or directory", label = "错误输出")), 12000, 100,
                status = ToolActivityStatusUi.Failed, command = "ls /sdcard/no_such_dir"),
            step("s10", "sms_code_read", "读取验证码", "找到 1 个 · 来自 10086", view("找到 1 个 · 来自 10086", transient = true), 12200, 300),
            step("s11", "ui_observe", "查看屏幕", "「设置」 · 25 个元素 · 截图", null, 12600, 200, images = 1),
        )
        compose.setContent {
            AgentAppTheme(appearance = AppearanceSettings(), applyInterfaceScale = false) {
                Column(Modifier.fillMaxSize().background(MovoColors.bgCanvas).padding(top = 24.dp)) {
                    AgentWorkProcess(id = "w-steps", messages = messages, onOpenBrowser = {}, currentBrowserMessageId = null, retainedStreamingStates = emptyMap())
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        capture("20-01-collapsed-card")
        compose.onAllNodes(androidx.compose.ui.test.hasText("已完成", substring = true))[0].performClick()
        capture("20-02-steps-all-collapsed")
        compose.onAllNodes(androidx.compose.ui.test.hasText("查看屏幕"))[0].performClick()
        capture("20-03-screenshot-expanded")
        compose.onAllNodes(androidx.compose.ui.test.hasText("运行 · find", substring = true))[0].performClick()
        capture("20-04-output-expanded-only-one")
        compose.onAllNodes(androidx.compose.ui.test.hasText("搜索短信「快递」"))[0].performClick()
        capture("20-05-list")
        compose.onAllNodes(androidx.compose.ui.test.hasText("修改设置 · 屏幕亮度"))[0].performClick()
        capture("20-06-change")
        compose.onAllNodes(androidx.compose.ui.test.hasText("记住一条"))[0].performClick()
        capture("20-07-preview")
        compose.onAllNodes(androidx.compose.ui.test.hasText("运行 · ls", substring = true))[0].performClick()
        capture("20-08-failed")
        compose.onAllNodes(androidx.compose.ui.test.hasText("读取验证码"))[0].performClick()
        capture("20-09-secret-not-expandable")
        compose.onAllNodes(androidx.compose.ui.test.hasText("查看屏幕"))[1].performClick()
        capture("20-10-restart-placeholder")
    }
}
