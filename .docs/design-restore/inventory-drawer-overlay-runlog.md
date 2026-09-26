# 设计还原盘点：侧边栏 / App 外浮窗 / 运行日志 / 执行详情数据

- 日期：2026-09-25；分支 `feat/design-v1-restore`；只读盘点，未修改源码。
- 对照规范：`docs/DESIGN_SYSTEM.md` §8.1（Overlay/Orb、Overlay/Panel、展开卡语音模式、屏幕边缘光晕、点击指示）、§8.4/8.5（补充与语音）、§8.6、§8.8、§8.9、§8.10、§9.5、§9.10。
- 类型说明：**纯 UI** = 只改外观/动效，不改数据与流程；**行为变更** = 改交互流程、状态机、数据或协议，需要额外测试与产品确认。

## 0. 总览：关键差距与风险

| 优先级 | 区域 | 问题 | 类型 |
|---|---|---|---|
| P0 | B | 任务结束时 `enterFinalState` 自动打开结果对话，并在 READY 后移除全部浮窗、`stopSelf`（`AgentRuntimeService.kt:1126-1157`）；规范要求悬浮球常驻、完成态 ✓ 等用户点开。涉及服务保活、进程重启恢复、结果交付主路径 | 行为变更（大） |
| P0 | B | **现有 Bug**：暂停后到达的事件经 `applyEvent` 把 phase 写回 RUNNING（`AgentOverlayState.kt:30-148`），UI 显示运行中，控制器实际仍暂停 | Bug |
| P0 | B/D | Wire 协议没有 PAUSE/RESUME/STEER 消息，也没有 RunPaused 事件（`AgentRuntimeWire.kt:32-82`）→ App 内看不到「已暂停」，也不能把补充交给当前任务（§8.4） | 行为变更 |
| P0 | A | 删除 `PaneDock` 后**权限页 `AppRoute.Permissions` 失去常规入口**（设置页没有），必须先在设置页补入口 | 行为变更 |
| P1 | B | 浮窗语音模式完全缺失；执行中语音被准入层判 BUSY 拒绝（`AgentRuntimeAdmission.kt:9`），与「语音作为补充」冲突 | 行为变更（大） |
| P1 | B | 悬浮球无拖动/吸附/移除区/长按；展开卡缺步骤序号、计时、最近 3 步（overlay 状态中无这些数据） | 行为 + 数据 |
| P1 | D | 工具步骤无时间戳、运行状态与目标 App 不持久化、无「已采纳」事件、无 `AppRoute.RunDetail` | 数据 + 路由 |
| P1 | C | 对话中失败卡缺原因卡与「重试」；进行中底部栏应在 30s 无数据才出现 | 行为 + UI |
| P2 | A | 搜索命中片段需改 `contentMatches` 返回类型；分组日期格式与共用 `ConversationTimeLabels` 冲突 | 行为（小） |
| P2 | B | 所有浮窗硬 `removeView`，退场动画从未执行；光晕压暗 31% + 彩虹、每帧新建 Paint；手势指示颜色/曲线不符 | 纯 UI |
| P2 | C | 分组标题/页脚放进卡片、「·」无空格、时长格式（会改 `DiagnosticsFormatTest` 断言） | 纯 UI |

## A. 侧边栏 Drawer

> 基准路径 `app/src/main/kotlin/io/github/mangi/eta/`。简称：`SPS` = `ui/components/ConversationSidePaneScaffold.kt`，`Root` = `ui/app/AgentAppRoot.kt`，`Shell` = `ui/app/AgentAppShell.kt`，`State` = `ui/app/AgentAppState.kt`。

### A.1 元素与交互清单

| 元素 | file:line | 回调 / 行为 | 数据来源 |
|---|---|---|---|
| 挂载条件 | Shell:134-157 | 只在 `currentRoute is AppRoute.Home` 时包一层 `ConversationSidePaneScaffold`；`AppRoute.Chat` 进入后立刻 `popToHome()`（Root:351-354），Home 即唯一会话页 | `agentState.conversationPaneState`（Root:260） |
| 打开入口 | Shell:178-185 | 首页顶栏左上 `Icons.Rounded.Menu` → `onOpenConversationPane` → `conversationPaneOpen = true`（Root:263） | Root:148 `conversationPaneOpen`（`remember`，不持久化） |
| 推移结构与宽度 | SPS:185、120-122 | 宽 = `min(maxWidth×0.84, 340dp)`；前景 `offset` 右移（SPS:290-297） | — |
| 拖动开 / 关 | SPS:193-212、325-331 | 整个前景横向 `anchoredDraggable`（不限左缘）；`enabled = backHandlerEnabled`；阈值 50%，`spring(1f, 146f)`（SPS:126-129） | `paneDragState` |
| 状态同步 | SPS:230-244 | `visible` 变化 `animateTo`；拖动停下回调 `onOpen` / `onDismiss` | — |
| 返回键关闭 | SPS:248-254 | `NavigationBackHandler`，只在 `onBackCompleted` 关闭，不跟手 | — |
| 点露出区关闭 | SPS:335-341 | 打开时前景上盖全屏透明 `clickable(onDismiss)` | — |
| 前景圆角与阴影 | SPS:222-224、298-323 | 左侧圆角 `rememberNavSystemCornerRadius()`，回退 24dp；`dropShadow` 半径 12、alpha 0.12 | — |
| 侧栏自身压暗层 | SPS:273-288 | 侧栏上 `windowDimming`，随打开进度 alpha 1→0（视差） | — |
| 顶部毛玻璃 + 分隔线 | SPS:428-450、487-528 | `PaneFrostRegion` `textureBlur` 半径 25、表面 alpha 0.78（SPS:532-533）；`canScrollBackward` 时淡入 0.5 分隔线（SPS:382，140ms 裸写 SPS:517-518） | — |
| 搜索框 | SPS:535-561 | Miuix `SearchBar` + `InputField`，占位 `conversation_search_hint`「搜索对话」；每次输入 → `agentState.updateSearchQuery`（Root:265 → State:860-863 → `refreshConversationSummaries`） | `ConversationPaneUiState.searchQuery` |
| 搜索实现 | State:2694-2707、2711-2722；`ui/model/ConversationSearch.kt:9-47` | 标题 ∪ 预览 ∪ 全文，大小写不敏感；全文含用户消息、文件引用名/路径、助手正文、思考、工具名/命令/参数摘要/结果摘要、工具汇总、系统提示；按（查询词, 状态引用）缓存。**只返回过滤后列表，无命中片段** | `conversationsById` |
| 分组标题 | SPS:563-597；分组逻辑 SPS:848-876 | 时钟图标 14 + 标题（footnote1 SemiBold）+ **条数**；置顶 → 今天（`isActiveRun`/当天/时间戳≤0）→ 其他按 `timeLabel` 相邻合并 | `ConversationTimeLabels.label`（`ui/app/ConversationTimeLabels.kt:13-44`：昨天 / 2-6 天前「周X」/ 同年「M月d日」/ 跨年完整日期） |
| 「置顶」分组 | SPS:836、864 | 死分支：生产代码从不设置 `isPinned`（仅 `ui/preview/FakeAgentUiStates.kt:34,43`） | — |
| 会话行 | SPS:599-666 | 最小高 48、圆角 12、内边距 12/12；标题空时退回预览；**当前行** `surfaceContainerHigh` 底 + 标题 **primary 色** + SemiBold（SPS:618-624、642-648） | `ConversationSummaryUi`（`ui/model/ConversationUiState.kt:13-23`） |
| 点击行 | SPS:419、626 → Root:289、236-240 | `selectConversation`：清焦点 → `agentState.selectConversation`（State:865-881）→ 关闭侧栏 | — |
| 角色扮演第二行 | SPS:652-655 | `characterName` 与标题不同时才显示，只写角色名 | State:2690 `state.roleplay?.characterName` |
| 运行中指示 | SPS:657-665 | 右侧 6dp **primary 圆点** | State:2691 `isActiveRun = state.isStreaming`（State:631/686 置位，661/709/718 复位） |
| 长按 | SPS:627-630 | `HapticFeedbackType.LongPress` → `WindowListPopup`（SPS:668-673，`ContextMenuPositionProvider` + `Align.BottomEnd`） | — |
| 菜单 · 重命名 | SPS:677-688、720-729 → Root:290-292、689-716 | `WindowDialog` + `TextField`，空内容禁用保存 → `renameConversation`（State:957-964：trim、写 titles、**刷新 updatedAt**、持久化） | `conversationTitles` |
| 菜单 · 导出 | SPS:689-700、730-739 → Root:293-301、152-182 | SAF `CreateDocument("text/markdown")`，文件名 `ConversationMarkdownExporter.defaultFileName` → `exportConversationMarkdown`（State:966-）→ IO 写入 → Toast | `state.messages` |
| 菜单 · 删除 | SPS:701-718、740-750 → Root:302-304、718-735 | error 色 → 二次确认 `WindowDialog`（destructive）→ `deleteConversation`（State:932-955：结束该会话的语音会话、清 `queuedTextSubmission` 与草稿、删当前会话时切到第一个剩余会话或新建、持久化） | — |
| 空状态 / 无结果 | SPS:403-406、756-770 | 一行 `conversation_empty`「还没有对话」/ `conversation_no_results`「没有匹配的对话」，body2 Medium | — |
| 底部 `PaneDock` | SPS:451-478、772-828 | 2×3 灰底块（`surfaceContainer`，圆角 12，最小高 48）：设置 → `AppRoute.Settings`；模型 → `ModelProviders`；工具 → `Tools`；Skills（**硬编码英文** SPS:788）→ `Skills`；权限 → `Permissions`；角色 → `Characters`（Root:305-310）。`pushRoute` 保留侧栏打开状态，返回时侧栏仍开（Root:208-214） | — |
| 底部分隔线 | SPS:383、451-458 | `canScrollForward` 时顶边出现 0.5 分隔线 | — |
| 新建对话 | Shell:190-202 | **不在侧栏**，在首页顶栏右侧溢出菜单 `TopBarOverflowMenu` → `createConversation`（Root:242-246 → State:883-894，同时清空 searchQuery） | — |

### A.2 与 §8.6 的差距

| 差距 | 规范 | 现状（file:line） | 类型 | 风险 |
|---|---|---|---|---|
| 分组标题去图标和条数 | 只有文字，`Label/Medium` 三级色，上 16 / 下 4（首组上 8） | 时钟图标 + 条数，上 8 / 下 10（SPS:576-595、135-136） | 纯 UI | 低 |
| 分组「具体日期」 | 今天 / 昨天 / 「9月20日」 | 2-6 天前显示「周一」（`ConversationTimeLabels.kt:37`） | 行为（格式化） | 共用函数，`ConversationTimeLabelsTest.labelUsesRelativeDayForRecentHistory` 断言「周一」→ 需侧栏专用分组格式化 |
| 置顶分组 | 无置顶 | `Pinned` 死分支（SPS:836、864） | 纯 UI | 低 |
| 行尺寸 | 宽 300，高 44，左右内边距 16；角色扮演两行高 60 | 最小高 48，内边距 12/12，行间距 4（SPS:140-144、400） | 纯 UI | 低 |
| 当前行样式 | `bg/surface-muted` + Medium，不用 Indigo | primary + SemiBold（SPS:642-648） | 纯 UI | 低 |
| 运行中指示 | 16 Indigo 加载圈，可与「当前」叠加 | 6dp primary 圆点（SPS:657-665） | 纯 UI | 低，数据源 `isStreaming` 不变 |
| 角色扮演第二行 | 「角色名·角色扮演」`Label/Regular` 次要色 | 只写角色名，与标题相同时隐藏（SPS:652-655） | 纯 UI + 文案 | 行高固定 60 |
| 长按菜单 | 行下方 4、左缘对齐；删除 Rose；菜单显示期间行保持按压 | `ContextMenuPositionProvider` + `BottomEnd`（SPS:670-671）；error 色；无按压保持 | 纯 UI | 中，需自定义 PositionProvider + 按压状态 |
| 顶部「+」新建 | 搜索框右侧，热区 44 | 侧栏无；只在顶栏溢出菜单（Shell:192-201） | 行为（新增入口） | 低，复用 `createConversation()`（会关侧栏、清搜索） |
| 搜索框规格 | 高 40、圆角 20、`bg/surface-muted`；有文字时 ✕ 清空 | Miuix `SearchBar` 默认，无清空（SPS:544-559） | 纯 UI（清空为小行为） | 低 |
| 搜索结果不分组 + 命中片段 | 有搜索词时不分组；两行高 60：标题 + 命中片段（命中词 Medium 主色）+ 右上时间 | 搜索时仍分组（SPS:365、408）；`contentMatches` 只返回 Boolean（`ConversationSearch.kt:9-15`） | **行为 / 数据** | 中：`ConversationSummaryUi` 加 `matchSnippet`+命中区间；保留缓存与 7 个测试语义 |
| 空状态 | 「还没有对话」`Body/Strong` + 说明一行 | 一行 body2 Medium（SPS:756-770） | 纯 UI + 新字符串（三套语言） | 低 |
| 底部改一行「设置」 | 60 高：图标底块 40 +「设置」+「模型、语音、工具与权限」+ 箭头；权限缺失改具体问题 + Rose 点 | 2×3 `PaneDock`（SPS:772-828） | **行为变更**：删 5 入口 + 新权限状态源 | **高**，见 A.4 |
| 右侧对齐线 | 右边距线 = 宽 − 20 | 左右 16（SPS:130） | 纯 UI | 低 |
| 顶/底毛玻璃 | `bg/surface` 90% + 模糊 | alpha 0.78（SPS:533） | 纯 UI | 低 |
| 不加遮罩 | 主页面无遮罩 | 符合；侧栏自身有视差压暗（SPS:273-288） | 纯 UI | 低 |
| 从左缘拖动打开 | 左缘右拖 | 整个前景可拖（SPS:325-331） | 行为 | 中：限制起点需自判手势 |
| 返回手势跟手 | §9.10 要改 | 只 `onBackCompleted`（SPS:248-254） | 行为 | 中：接 progress 且不能抢二级页首次返回（SPS:246-247 注释） |
| 前景圆角回退 | 屏幕圆角（稿 36） | 回退 24（SPS:123） | 纯 UI | 低 |
| 动效 Token | 统一 Token | 140ms（SPS:517-518）、`spring(1f,146f)`（SPS:126-127） | 纯 UI | 低 |
| 硬编码文案 | 多语言 | 「Skills」（SPS:788） | 纯 UI | 随 Dock 删除消失 |

### A.3 现有测试（`app/src/test/kotlin/io/github/mangi/eta/`）

- `ui/model/ConversationSearchTest.kt`：全文搜索匹配规则 7 例（用户/助手文本大小写 :31、文件引用名与路径 :43、思考 :59、工具字段 :69、工具汇总与系统提示 :89、非内容消息不命中 :104、空查询 :114）。
- `ui/app/ConversationTimeLabelsTest.kt`：分组标签来源（当天时钟 :14、昨天/「周一」:22-27、同年/跨年 :30-35、无效时间戳 :38、英文 12 小时制 :43、夏令时 :69）。
- `ui/app/ConversationMarkdownExporterTest.kt`：导出顺序、文件引用、图片不嵌 dataURL、跳过空白/流式消息、工具状态、多行标题清洗、默认文件名清洗/回退/截断。
- `ui/app/AgentConversationStoreTest.kt`：titles / updatedAt 持久化（:95、:165-174）、空会话不写库（:392、:426）、空快照清库（:447）。
- `agent/voice/session/VoiceSessionLifecycleTest.kt`：删除语音中的会话会结束麦克风会话（:117-122）；切换会话保留草稿并结束语音（:105-115）。
- **缺口**：`groupForDrawer` / `drawerSection`（SPS private）、`refreshConversationSummaries` 过滤/排序/`isActiveRun`、`renameConversation`（trim/空值/刷新时间）、`deleteConversation` 切换选中项均无测试；`app/src/androidTest` 无侧栏 UI 测试。

### A.4 风险点

1. **收掉 `PaneDock` 后各目的地可达性**（设置页 `ui/SettingsScreen.kt`）：模型 `ModelProviders`（SettingsScreen:212）、Skills（:261）、角色 `Characters`（:281）、工具 `Tools`（:293）设置页都有；**权限 `Permissions` 设置页没有入口**——设置页「权限」分组只有悬浮窗一条且直接跳系统设置（:547-580），除侧栏外唯一路径是工具页 ToolCard 在缺权限时出现（`ui/model/ToolCapabilityProjection.kt:41` → Root:378）；`PermissionHealthCard`（`ui/components/PermissionHealthCard.kt:31`）已定义但无调用。**删 Dock 前必须先在设置页补无条件的「权限」入口。**
2. 底部「设置」行的权限缺失说明需要共享权限健康状态源（无障碍、悬浮窗、通知等），目前只有 `SettingsScreen` 在 ON_RESUME 刷新 `canDrawOverlays`（SettingsScreen:106-129）。
3. `pushRoute` 记住侧栏打开状态（Root:208-214），改为单一「设置」入口后需保留「返回后侧栏仍开」。
4. 命中片段需改 `contentMatches` 返回类型，保持 7 个测试语义和 `contentMatchCache`（State:2710-2722）。
5. 分组日期若直接改 `ConversationTimeLabels` 会影响行时间与现有测试，应新建侧栏专用函数。
6. 「运行中」只反映 `isStreaming`（State:631/686），换加载圈时不改数据源；App 外任务与会话的对应也依赖它。
7. 顶部「+」与顶栏溢出菜单同调 `createConversation()`，两个入口可共存，保留原入口避免回归。

## B. App 外浮窗 Overlay（悬浮球 / 展开卡 / 光晕 / 手势指示 / 对话浮层）

> 代码根：`app/src/main/kotlin/io/github/mangi/eta/`。下文 `Svc` = `agent/runtime/AgentRuntimeService.kt`，`OC` = `agent/overlay/AgentOverlayContent.kt`，`Sheet` = `ui/AgentConversationSheetActivity.kt`。

### B.1 窗口与生命周期全景

**进程/服务**：`AgentRuntimeService` 是 bound + started 混合服务（Manifest `AndroidManifest.xml:171`，与 App 同进程，`exported=true`，调用方 uid 白名单 `Svc:1159`）。窗口全部由它通过 `WindowManager.addView/removeView` 直接增删；窗口类型在无障碍服务可用时为 `TYPE_ACCESSIBILITY_OVERLAY`（截图时被 `takeScreenshotOfWindow` 逐窗口过滤），否则回退 `TYPE_APPLICATION_OVERLAY` 并要求悬浮窗权限（`Svc:868`, `Svc:1009-1018`）。

| 窗口 | 创建 | 布局参数 | 内容 | 移除 |
|---|---|---|---|---|
| 光晕 glow | `showOverlay()` `Svc:873-883`（与悬浮球同时创建） | 全屏 MATCH_PARENT × 真实屏高，`NOT_FOCUSABLE|NOT_TOUCHABLE` `Svc:1021-1046` | `AgentOverlayGlow` `OC:121-169` | `removeAmbientWindows()` `Svc:1140` / `onDestroy` `Svc:176` |
| 悬浮球 orb | `showOverlay()` `Svc:886-901`，`orbView != null` 时直接返回（幂等） | WRAP_CONTENT，`END|TOP`，x=8dp，y=屏高×0.6 `Svc:973-988`；`NOT_FOCUSABLE` | `AgentOverlayOrb` `OC:175-204` → `AssistantOrb` 56dp `OC:215-266` | 同上 `Svc:1141`, `Svc:175` |
| 展开卡 bubble | `showBubble()` `Svc:921-943`，由 `toggleCollapse()` `Svc:909-919` 或 `showOverlay` 中 `!collapsed` 时创建 | WRAP_CONTENT，`END|TOP`，x=72dp，y=屏高×0.6，`NOT_FOCUSABLE|NOT_TOUCH_MODAL`，`windowAnimations=0` `Svc:990-1007` | `AgentOverlayBubble` `OC:272-452` | `toggleCollapse()` 收起时 `Svc:913`；`removeAmbientWindows` `Svc:1142` |
| 手势指示 | `GestureIndicator.showTap/showLongPress/showSwipe`（`agent/overlay/GestureIndicator.kt:25-106`），由 `agent/tool/AgentLocalTools.kt:1304-1314` 在执行 tap/long_press/swipe 时调用 | 点按 60dp 方窗居中于落点；滑动为全屏窗；均 `NOT_TOUCHABLE` | `PressIndicatorView` `GestureIndicator.kt:156-223` / `SwipeIndicatorView` `:225-306` | 动画结束回调 `finishIndicator()` `:128-134`；新指示出现时先移除旧的 `:113` |
| 对话浮层 Sheet | Activity（非 WindowManager 窗口）：`Svc.openResultConversation()` 通过 PendingIntent 启动 `Svc:1066-1108`；或语音助手入口 `agent/voice/EtaAssistantVoiceService.kt:141-142`（`ACTION_ASSISTANT`） | `singleTask`、独立 taskAffinity、`excludeFromRecents`（`AndroidManifest.xml:86-93`）；窗口底部对齐、高 = 屏高×0.55（`AgentResultSheetSizing.kt:35`），`FLAG_NOT_TOUCH_MODAL`、无 dim `Sheet:98-105` | `AgentConversationSheet`（把手+标题+展开+关闭）`agent/overlay/AgentConversationSheet.kt:44-127` + 原 App 会话 `AgentConversationContent` `Sheet:170-175` | `finish()`：关闭/返回/展开到 App 成功/加载失败 |

**状态模型**：`AgentOverlayState(phase, round, status, detailText)` `agent/overlay/AgentOverlayState.kt:13-22`；`phase ∈ RUNNING / PAUSED / FINISHED / FAILED` `:7`；`status` 为 26 种语义状态 `AgentOverlayText.kt:12-38`，渲染时本地化 `:41-78`。状态由 `applyEvent(AgentEvent)` 折叠 `AgentOverlayState.kt:30-148`，**注意每个事件分支都会把 phase 写回 RUNNING**（见 B.4 风险 1）。另有 `collapsed`（展开卡是否显示）`Svc:111`、`hasExecutedForegroundTool` `Svc:112`、`isResultConversation` `Svc:108`。
代码里**没有**：待命态（idle）、聆听态、步骤序号 N、步骤列表、计时、目标 App、拖动位置。

**时序（一次任务）**

1. **收到请求** `MSG_START_RUN` → `ingestRunRequest` `Svc:261-359`：准入判断 `AgentRuntimeAdmission.decide`（`AgentRuntimeAdmission.kt:6-11`：同 runId → ATTACH；有任务在跑且任一方是语音 → BUSY「当前任务仍在执行，请等完成后再说」；文本任务会替换正在准备的文本任务）。异步准备（图片、运行配置）后 `startRun` `Svc:361-421`：重置 `state=Initial`、`collapsed=true`、清空补充列表（若来自 App handoff 则继承 payload 中已有补充 `Svc:403-411`），启动前台执行服务 `AgentExecutionService.acquire` `Svc:380`，工作线程 `executeRun` `Svc:423-450`。**此时不显示任何窗口**（除非 `fromResultCard`，即从结果继续 `Svc:413`）。
2. **首次操作前台 App** → `handleAcceptedRunEvent` `Svc:452-493`：`AgentOverlayVisibilityPolicy.shouldRevealFor(event)`（`AgentOverlayVisibilityPolicy.kt:14-26`，只有 launch_app/open_uri/tap/swipe/input 等驱动类工具及 observe_screen 完成）且入口界面已收起（`EntrySurfaceGuard.dismissOnce()`；若结果浮层可见则 `Sheet.hideForDeviceOperation()` 先把 Sheet `moveTaskToBack` 并等待 onStop，最多 3s `Sheet:350-373`）→ 首次出现时 `RUN_STARTED` 触感 `Svc:479-484` → `ensureOverlayVisible()` 一次性 addView 光晕 + 悬浮球（展开卡默认收起）。之后所有事件只更新 `state`，Compose 自动重组。
3. **执行中**：光晕在 RUNNING 时绘制压暗 31% + 彩虹 SweepGradient 5s 一圈 `OC:121-169`；悬浮球 56dp 径向光晕，RUNNING 时呼吸 1400ms `OC:215-266`；工具执行时出现手势指示。
4. **点悬浮球** → `onToggleCollapse` → `Svc.toggleCollapse()`：展开时 addView 展开卡，收起时 removeView（硬切，`AnimatedVisibility` 的 exit 永远不会执行）。没有长按、没有拖动（`handleDrag` `Svc:963-971` 标注 `@Suppress("unused")`，无任何调用），没有自动收回。
5. **暂停** 展开卡 ‖ → `requestPause()` `Svc:787-793`：`controller.pause()`（只置标志，工作线程在下一个 `throwIfCancelled` 检查点阻塞 `AgentRunController.kt:75-105`）+ `state.phase=PAUSED, status=Paused`。光晕在非 RUNNING 时**完全不绘制**（`OC:124`），窗口仍在。悬浮球变琥珀色 `OC:97`。
6. **继续** ▶ → `requestResume()` `Svc:795-801`：`controller.resume()` + `phase=RUNNING, status=Continuing`。
7. **停止** ■（运行中和暂停中都显示）→ `requestStop()` `Svc:749-756` → `cancelRun()` `Svc:758-785`：`controller.cancel()` + `status=Stopping`；无活动会话时直接 `dismissAndStop()`。
8. **补充（打字）**：展开卡「✎」→ `enterSupplementMode()` → `onSupplementModeChange(true)` → `Svc.setBubbleInputMode(focusable=true)` 清除 `FLAG_NOT_FOCUSABLE` 使窗口可获焦弹键盘 `Svc:1048-1064`；输入框 `SupplementInput` `OC:491-571`（最多 4 行、取消/发送）。发送 → 收键盘、80ms 后 `onSupplement(text)` → `Svc.requestSupplement()` `Svc:803-828`：
   - 有活动会话 → `session.steer(text){ recordSupplementEvent }`（`AgentRuntimeSession.kt:96-106`，仅 `State.RUNNING` 且 `OP_CHAT` 接收）→ `controller.steer` 入队 `AgentRunController.kt:43-51`，由 `AgentLoop` 在当前回合结束后 `pollSteeringMessage()` 注入为用户消息 `agent/model/AgentLoop.kt:234-251`；同时生成 `AgentEvent.UserSupplementReceived(index,text)`，经 replay/订阅发给 App，`AgentAppState.kt:2260-2261` 插入「补充」消息并持久化；补充也写入 handoff payload（`withActiveSupplements` `Svc:1178-1189`）。暂停中也可补充，且不会恢复（`AgentRunControllerTest.steeringDoesNotResumeAPausedRun`）。
   - 会话已密封（loop 正在收尾）→ `status=Finishing`，**补充被丢弃**，无提示 `Svc:811-817`。
   - 无活动会话（已结束、浮窗仍在）→ `continueFromResult()` `Svc:830-845`：用 `AgentContinuationBuilder` 基于上次请求+回答新开一轮 `startRun(fromResultCard=true)`；非 chat 操作显示 `ContinuationUnavailable`。
9. **结束** 工作线程返回 → `postTerminalOverlay` `Svc:510-550` → `enterFinalState` `Svc:1126-1138`：
   - 成功：`phase=FINISHED, status=ResultReady, detailText=结果正文`；失败：`phase=FAILED`，`status = Stopped（error=="已停止"）或 RunFailed`。
   - 若本次**操作过前台 App**（`hasExecutedForegroundTool`）或从结果浮层发起（`isResultConversation`）→ 收起展开卡、确保悬浮球在（Sheet 可见时不补建）、**自动** `openResultConversation()`：PendingIntent（带后台启动豁免）拉起 `AgentConversationSheetActivity`，Sheet 打开对应会话后通过 `ResultReceiver` 回 `RESULT_READY` → `dismissAndStop()`：**移除悬浮球/展开卡/光晕窗口并 `stopSelf()`** `Svc:1152-1157`。10s 无回执或失败 → Toast「无法打开对话，结果仍在这里」并**保留悬浮球**（此时点球展开卡，展开卡可补充=继续对话、停止=关闭浮窗）。
   - 未操作前台 App → 直接 `dismissAndStop()`（从未出现窗口）。
   - 失败（请求格式/配置错误）`finishWithFailure` `Svc:730-747` 同样走 `enterFinalState`。
10. **服务销毁** `onDestroy` `Svc:151-186`：取消会话、硬移除三窗。
11. **Sheet 自身**：高 55%（`AgentResultSheetSizing.kt:35`），把手区拖动实时改窗口高度 `Sheet:258-275`，松手按速度/中点决定：展开 → 直接 `openInApp()`（交给 `MainActivity` 同一会话，回执 READY 后 finish `Sheet:291-326`）；否则 220ms 回到半屏。**把手/标题区单击也会 `openInApp()`**（`AgentConversationSheet.kt:95`）。没有向下拖关闭。键盘弹起时窗口按键盘额外高度抬高 `Sheet:238-251`。锁屏时由 `KeyguardContentGate` 隐藏内容 `Sheet:75,162,169`。助手入口（`ACTION_ASSISTANT`）承载当前语音会话，`EXTRA_AUTO_LISTEN` 时自动开麦（`Sheet:152-157`）。
12. **语音**：浮窗内没有任何语音 UI；执行中唤醒/语音轮次会被准入层拒绝为 BUSY（`AgentRuntimeAdmission.kt:9`，`VoiceRuntimeAdmissionTest`），不会作为补充进入当前任务。

### B.2 元素与交互清单

| 元素 | file:line | 回调 / 行为 |
|---|---|---|
| 边缘光晕 | `OC:121-169` | 仅 RUNNING 绘制：全屏压暗 `alpha 0.31` + 40px 模糊彩虹描边、圆角 30px、5s 旋转；每帧 new `Paint`/`SweepGradient`/`Matrix` |
| 悬浮球 | `OC:175-266` | 56dp，进场 scale 0.5→1 LowBouncy 弹簧 + 200ms 淡入（exit 未被执行）；颜色随 phase：RUNNING=主题 primary、PAUSED=#FF9F0A、FINISHED=#34C759、FAILED=error `OC:95-100`；RUNNING 光晕呼吸 1400ms；`clickable` → `toggleCollapse`（Miuix 默认水波纹） |
| 展开卡容器 | `OC:346-354` | Miuix `Card`，最大宽 136dp，圆角 16，内边距 12/10，`surfaceContainer` 80% 不透明；进场以右中为锚点 scale 0.5→1 |
| 状态行 | `OC:356-374` | 7dp 状态点（RUNNING 900ms 呼吸）+ 状态文字 13sp Medium 两行（`status.localizedText()`） |
| ✎ 补充 | `OC:421-426` | `enterSupplementMode` → 窗口可获焦 + 输入框展开 |
| ‖ 暂停 / ▶ 继续 | `OC:427-441` | `requestPause` / `requestResume`；RUNNING 显示暂停，PAUSED 显示继续，FINISHED/FAILED 都不显示 |
| ■ 停止 | `OC:442-447` | `requestStop`，所有 phase 都显示（error 色） |
| 补充输入框 | `OC:491-571` | 180ms 后自动聚焦弹键盘；「取消」`closeSupplementMode`、「发送」`submitSupplement`（空文本禁用） |
| 点按/长按指示 | `GestureIndicator.kt:156-223` | 颜色 `#2879FB`；半径 20dp 环（2/2.5dp 描边）+ 实心中心点 3/4dp；长按多一圈 25dp；进场 200ms `OvershootInterpolator(2.0)`，保持 100ms（长按 240–620ms），退场 180ms Decelerate 放大 1.06/1.12 |
| 滑动指示 | `GestureIndicator.kt:225-306` | 全屏窗：淡预告线 + 实线轨迹 3dp + 7dp 头点 + 13dp 外环；时长 = 手势时长（≥300ms），然后 160ms 淡出 |
| 触感 | `AgentHapticFeedback.kt:15-63` | 类型 TAP / LONG_PRESS / SWIPE / RUN_STARTED；浮窗里只用到 RUN_STARTED（`Svc:480`） |
| Sheet 把手 + 头部 | `AgentConversationSheet.kt:66-123` | 把手区 20dp（32×4）；标题（无样式参数、最大宽不限）；⤢ `onOpenConversation`、✕ `onClose`；图标 20dp、热区 48；顶部圆角 24 |
| Sheet 拖动 | `AgentConversationSheet.kt:70-105`、`Sheet:258-289` | 只在把手/头部区；上拉 → 全屏即切到 App；下拉最低只到半屏（不能关闭） |
| Sheet 内容 | `Sheet:169-183` | App 内同一个 `AgentConversationContent`；未就绪显示「正在打开对话」/锁屏「解锁后继续」 |

### B.3 与规范（§8.1 / §8.9 / §9.5 / §9.10）差距

| # | 差距 | 规范 | 现状 | 类型 | 风险 |
|---|---|---|---|---|---|
| B1 | 悬浮球常驻 + 完成态 | 出现后常驻；完成保持 Green ✓ 直到用户点开；不自动弹对话浮层 | 结束时自动 `openResultConversation` 并在 READY 后移除全部窗口、`stopSelf` `Svc:1126-1157` | **行为变更（大）** | 服务需在无任务时存活（目前靠 run 期间的 `AgentExecutionService` 前台服务保活，结束即释放 `Svc:418`）→ 需新的保活/恢复策略；进程被杀后如何恢复完成态；自动弹结果的现有用户路径被移除，需要保证结果仍能从通知/App 找回 |
| B2 | 待命态 / 聆听态 / 失败 ! / 暂停角标 / 状态环 | 32 玻璃圆 + 22 光球 + 1.5 状态环 + 14 角标，6 种状态 | 56dp 纯色径向球，只有 4 个 phase 配色，无环无角标 | UI（需新增 idle/listening 状态 = 状态模型扩展） | phase 枚举被 Sheet/Service 多处使用，扩展时注意 `applyEvent` 全分支 |
| B3 | 拖动 / 吸附 / 底部「移除」区 / 长按 300ms 语音 | 8px 以上进入拖动、吸附左右边缘、拖入移除区关闭；长按进入语音 | 无拖动（`handleDrag` 未使用 `Svc:963`），无长按，无移除 | 行为变更 | 窗口 `WRAP_CONTENT` + `END|TOP`，左右吸附需改 gravity/x 计算；展开卡位置随球左右翻转；与无障碍手势注入的命中冲突（球挡住目标控件） |
| B4 | 悬浮球位置 | 右边缘 8，球心距底约 1/4 屏高 | x=8dp、y=屏高×0.6（窗口顶）`Svc:986-987` | UI | — |
| B5 | 展开卡内容 | 宽 224、玻璃材质、「第 N 步·动作」+ 计时 + 最近 3 步 + 底部操作行（键盘/声波圆钮 + 状态机胶囊） | 宽 ≤136，一行状态文字 + 3 个圆形图标按钮 | UI + **数据**（overlay state 没有步骤序号/列表/开始时间；需在 `AgentOverlayState` 累积 ToolStarted/ToolFinished，或复用 App 侧 `runMessageProjector`） | 「第 N 步」口径需与 App 内执行卡（8.1）一致 |
| B6 | 停止规则 | 运行中只有「‖ 暂停」；暂停后「结束任务」+「▶ 继续」；同一时刻只有一种「停」 | 运行中同时显示暂停与停止 `OC:427-447` | 行为变更（小） | 去掉运行中停止会改变现有一键停止路径；口令「取消任务」需另行支持（目前不存在） |
| B7 | 暂停态表现 | 标题「已暂停·第 N 步」、计时冻结、光晕降到 40% 并停流动、球灰环去饱和 + ‖ 角标 | 光晕直接不绘制；球变琥珀；**pause 后到达的事件会把 phase 覆盖回 RUNNING**（见风险 1） | UI + Bug 修复 | 需要状态机把「用户暂停」与「事件驱动状态」分离 |
| B8 | 4s 无操作自动收回（暂停态不收） | 有 | 无；`HIDE_DELAY_MS`/`RESULT_REVIEW_DELAY_MS` 常量未使用 `Svc:1193-1194` | 行为（小） | 输入中不能收回 |
| B9 | 展开卡语音模式 | 声波进入、实时字幕、停顿后自动作为补充；球同步聆听态 | 完全没有；执行中语音被准入层判 BUSY 拒绝 `AgentRuntimeAdmission.kt:9` | **行为变更（大）** | 需要把语音会话结果路由到 `session.steer` 而不是新 run；麦克风/唤醒与执行互斥规则；跨界面不断线 |
| B10 | 失败态 | Rose 环 + ! + 抖动；展开卡自动弹出显示原因；看过回待命 | 失败也走自动打开结果对话 + 移除窗口 | 行为变更 | 与 B1 同一处改动 |
| B11 | 光晕样式 | 品牌渐变 2 宽描边 + 10 宽模糊 14 光晕，沿周长 12s linear、亮度 70–100% 往复，**不压暗**；暂停 40%；结束淡出 | 压暗 31% + 彩虹 5s；每帧新建 Paint/Shader | UI（性能改进） | 截图：无障碍模式下被过滤；**root screencap 回退时浮窗会进入截图**（`RootShellDeviceController.kt:788`），去掉压暗反而减轻污染 |
| B12 | 点击指示 | Indigo 环 44（1.5/2 宽）+ 外圈 64 淡光晕（6% 填充 + 20% 描边），无中心点；0.6→1 `fast+enter`，保持 200ms，淡出放大 1.1 120ms `exit` | `#2879FB`、40dp 环 + 中心点、Overshoot、保持 100ms、退场 180ms | UI | 窗口 60dp 需放大到 ≥64dp+余量 |
| B13 | 长按 / 滑动 / 输入文字指示 | 长按进度弧；滑动 44 环沿路径 + 尾迹 120ms 渐隐；输入文字控件描边 | 长按只多一圈；滑动为实线+头点；无输入文字指示（`AgentLocalTools` 只调 tap/longPress/swipe） | UI + 新增（输入指示需拿到控件 bounds） | 输入指示需在 input_text/replace_text 工具中新增调用 |
| B14 | 窗口先退场再移除 | 所有浮窗先播退场再 removeView；不用 windowAnimations | 全部硬 `removeView`（`Svc:913,1141-1143,174-176`），`AnimatedVisibility` exit 从未执行 | UI（窗口管理改造） | `onDestroy` 时无法等动画，需兜底 |
| B15 | Sheet 容器 | 高 560（约 60%），`bg/canvas`，顶部圆角 28，E3 阴影，scrim 32% | 55%，圆角 24，无阴影，`dimAmount=0` 无遮罩 `Sheet:103-104` | UI | 加 scrim 需要把 `FLAG_NOT_TOUCH_MODAL` 与点击遮罩关闭的行为一起设计（现在点外部可直接操作下层 App） |
| B16 | Sheet 头部 | 把手区 16；头部 44；标题 `Body/Strong` 最大宽 300；图标 24 热区 44 | 把手区 20；标题默认样式；图标 20、热区 48 | UI | — |
| B17 | Sheet 手势 | 向上拖到全屏；**向下拖过 1/3 关闭**；⤢ 按 Q4 推满全屏后无缝切到 App | 向下不能关闭；上拉超过中点直接切 App（无推满动画）；**单击把手/标题也会切 App**（`AgentConversationSheet.kt:95`） | 行为变更（小） | 单击把手切 App 是否保留需确认 |
| B18 | Sheet ↔ 悬浮球 Q4 变形 | 需要操作其他 App 时浮层收成悬浮球、从球展开 | `moveTaskToBack` 后另建悬浮球窗口，两者无动画衔接（Activity 与 overlay 窗口跨窗口） | UI（跨窗口共享元素，难） | 实现成本高，可降级为「浮层下滑淡出 + 球原地出现」 |
| B19 | 从待命/完成球点开对话浮层 | 点击 / 长按（语音） | 球点击只切换展开卡 | 行为变更 | 需要 Svc 能在无 run handoff 时打开 Sheet（现有 `ACTION_ASSISTANT` 入口可复用） |
| B20 | 首次出现 / 已常驻时过渡 | 120ms 后从 0.5 放大淡入（`spring/gentle`）；常驻时状态环交叉淡化 | 立即 addView，LowBouncy 弹簧 | UI | — |
| B21 | 减少动画 / Token | `LocalReducedMotion`、`MovoMotion` token | 浮窗内全部裸写时长；无 reduced motion | UI | — |

### B.4 关键风险（不能丢的现有行为）

1. **暂停状态被事件覆盖（现有 Bug）**：`requestPause` 只设标志，正在进行的模型请求/工具仍会发出事件，`applyEvent` 的每个分支都 `copy(phase = RUNNING)`（`AgentOverlayState.kt:33-146`），UI 会退回「运行中」并重新显示「暂停」按钮，而 `AgentRunController` 实际仍处于暂停。新状态机（暂停角标、40% 光晕、计时冻结）必须把 `pausedByUser` 与事件状态分离，并补单测。
2. **暂停/补充只存在于 Service 内存**：`AgentRuntimeWire` 没有 PAUSE / RESUME / STEER 消息（`AgentRuntimeWire.kt:32-82` 只有 START/EVENT/RESULT/CANCEL/ACK/DRAIN/QUERY/ATTACH），也没有 `RunPaused` 事件 → App 内执行卡看不到「已暂停」，App 内也无法补充（规范 §8.4 要求改走 steering）。要做 App 内「已暂停 + 结束任务」、详情页「切过去看」都需要扩展 wire 协议。
3. **补充在收尾窗口被静默丢弃**：`session.steer` 返回 null 且会话未终止时只把状态设为 `Finishing` `Svc:811-817`，原话不回显、不转为续聊。新设计的「下一步生效 / 已采纳」需要明确这一分支的反馈。
4. **常驻悬浮球 vs 服务生命周期**：当前服务只在有 run 或结果未交付时存活；常驻球需要（a）前台服务或无障碍服务托管窗口，（b）进程重启后恢复「完成待查看」状态（`AgentRuntimeResultStore` 已持久化结果，可作为依据），（c）「移除」后下次执行再出现的记忆。无障碍服务断开时 `TYPE_ACCESSIBILITY_OVERLAY` 窗口会失效。
5. **自动打开结果对话是现有的结果交付主路径**：`openResultConversation` 带 `ResultReceiver` 回执和 10s 超时兜底（`Svc:1066-1115`，`AgentConversationHandoffTest` 覆盖），改为「点球才打开」时要保留同一 handoff 协议与失败提示，不能让结果只停在球上而无其它入口。
6. **截图与命中**：浮窗依赖 `TYPE_ACCESSIBILITY_OVERLAY` 被截图过滤；root 回退模式会把浮窗截进去。常驻球还可能遮挡 Agent 要点击的控件（无障碍 `dispatchGesture` 会落到最上层窗口），需考虑执行点击时临时让球 `FLAG_NOT_TOUCHABLE` 或避让。
7. **入口界面收起的同步等待**：`hideForDeviceOperation` 在工具线程上最多阻塞 3s 等 Sheet `onStop`（`Sheet:350-373`），加入「浮层收成球」动画时不能延长这段等待，否则拖慢首个工具。
8. **补充输入需要窗口可获焦**：`setBubbleInputMode` 切换 `FLAG_NOT_FOCUSABLE`（`Svc:1048-1064`）；重做展开卡时必须保留，否则键盘无法弹出；切回不可获焦前要先收键盘（现有 80ms 延迟 `OC:305-324`）。

### B.5 现有测试（app/src/test）

| 测试 | 覆盖 |
|---|---|
| `agent/overlay/AgentOverlayVisibilityPolicyTest.kt` | 何时显示浮窗：纯文本/后台工具不显示；observe_screen 完成后显示；前台驱动工具执行前显示；入口界面收起；闹钟类只收起入口不显示浮窗；前台执行只在入口已收起后记录（6 例） |
| `agent/overlay/AgentResultSheetSizingTest.kt` | Sheet 拖动高度：亚像素累计、键盘抬升、反向拖动、点击不误触、锚点/速度判定（10 例） |
| `agent/runtime/AgentRunControllerTest.kt` | steering 逐条排队不取消资源、最终 poll 密封、取消清队列、**暂停阻塞直到继续、补充不恢复暂停、暂停中取消可唤醒** |
| `agent/runtime/AgentRuntimeSessionTest.kt` | replay/attach 边界、终态只投递一次、取消与完成竞争、compaction 不接受补充 |
| `agent/runtime/EntrySurfaceGuardTest.kt` | 入口界面（小布/小爱/Eta 语音）收起与截图排除 |
| `agent/runtime/VoiceRuntimeAdmissionTest.kt` | 语音不能替换进行中的任务（BUSY），同 runId attach，回执去重 |
| `agent/runtime/AgentConversationHandoffTest.kt` | 结果对话 handoff：原会话、外部来源、畸形 handoff 不开新会话、全屏复用 MainActivity 并在就绪后回执、历史不可用返回失败、助手入口 |
| `agent/runtime/AgentContinuationBuilderTest.kt` | 从结果继续（浮窗补充在无会话时的续聊路径）保留历史/图片/补充 ID |
| `ui/app/AgentPendingResultRecoveryTest.kt` | App 侧待交付结果恢复 |

**无测试**：`AgentOverlayState.applyEvent`（包括暂停被覆盖问题）、`AgentRuntimeService` 窗口生命周期 / `enterFinalState` 分支 / `requestSupplement` 三分支、`AgentOverlayContent` UI、`GestureIndicator`、`AgentConversationSheetActivity` 的 openInApp/拖动收尾。改造前建议先为 `applyEvent` 与一个抽出的「浮窗状态机」（纯 Kotlin）补单测。

## C. 运行日志 RunLog

> 基准路径 `app/src/main/kotlin/io/github/mangi/eta/`；页面文件在 `ui/screens/diagnostics/`（`DiagnosticsScreen.kt` / `DiagnosticsComponents.kt` / `DiagnosticsData.kt` / `DiagnosticsFormat.kt`），数据层在 `diagnostics/`。

### C.1 元素与交互清单

| 元素 | file:line | 回调 / 行为 |
|---|---|---|
| 设置入口「运行日志」 | `ui/SettingsScreen.kt:231-236` | `onNavigate(AppRoute.Diagnostics)`；说明「排查任务失败、卡住和变慢 · 保存最近 20 个任务」 |
| 路由定义 | `ui/navigation/AppRoute.kt:54,58,61` | `Diagnostics`、`DiagnosticsRun(runId)`（诊断编号 R12）、`DiagnosticsSystem` |
| 路由挂载 | `ui/app/AgentAppRoot.kt:581-601` | 列表 `onOpenRun` → DiagnosticsRun、`onOpenSystem` → System；详情 `onOpenModelSettings` → `ModelProviders`、`onOpenConversation` → `selectConversation` + `popToHome` |
| 从对话打开日志 | `AgentAppRoot.kt:248-250,318` | `openRunLog(runId?)` 经 `LocalRunLogOpener` 提供；runId 为 null 打开列表；对话浮层里未提供，入口隐藏（`DiagnosticsData.kt:118-122`） |
| 顶栏标题 | `ui/app/AgentAppShell.kt:352-354` | 运行日志 / 任务详情 / 系统事件 |
| 页面骨架 `LogPage` | `DiagnosticsComponents.kt:81-122` | 自绘 56 高顶栏（返回 44、标题左右内缩 64、右侧 action）+ LazyColumn，左右边距 20 |
| 顶栏「导出」 | `DiagnosticsData.kt:141-175`；列表 `DiagnosticsScreen.kt:69-81`、详情 `:162-175` | M3 `DropdownMenu`：「导出为 Markdown 文件」（`CreateDocument` + Toast）、「复制到剪贴板」 |
| 数据轮询 | `DiagnosticsData.kt:58-77` | 每 1s 取 `MemoryDiagnostics.buffer` 快照，有变化才 `DiagnosticTraceBuilder.build`；同时算 `silenceByRun` |
| 会话标题 | `DiagnosticsData.kt:87-116` | 按 `conversationId` 查；查不到按 `wireRunId` 在 `appliedRuntimeRunIdsJson` 里匹配 |
| 22 顶部说明段落 | `DiagnosticsScreen.kt:83-85` | 「每次任务的模型请求…保存最近 20 个任务，不含对话内容。」 |
| 22 筛选芯片 | `DiagnosticsScreen.kt:87-98`；`DiagnosticsComponents.kt:188-202` | 全部 / 失败 N / 进行中 N；0 不显示；当前分类清空回「全部」（`:53-57`）；失败 N = FAILED + INTERRUPTED |
| 22 空状态 | `DiagnosticsScreen.kt:99-101` | 「还没有任务记录。…」 |
| 22 按天分组 | `DiagnosticsScreen.kt:102-117` | `SectionLabel` 在卡片外，卡内一行一个任务 `LogRow` |
| 22 任务行 `LogRow` | `DiagnosticsComponents.kt:262-301` | 状态图标 20 + 标题 + 副标题 + 右侧数值 + 箭头 16，最小高 64，分隔线从 48；点击 `onOpenRun(run.id)` |
| 行副标题（结论） | `DiagnosticsFormat.kt:124-135` | 「15:02 · 」+ 进行中当前步骤与已等时长 / 失败原因标题 /「已停止」/ 成功「N 次请求 · M 次工具」 |
| 行右侧数值 | `DiagnosticsFormat.kt:137-138` | 结束后总用时 `duration()`；进行中 `timer` 00:18 |
| 状态图标 | `DiagnosticsComponents.kt:222-242` | 进行中 Indigo 加载圈 / 完成 Green ✓ / 失败 Rose ✕ / 已停止 ■ / 中断 Amber ⓘ |
| 22「任务之外」 | `DiagnosticsScreen.kt:118-129` | 组标题在卡外；卡内一行「系统事件」→ `onOpenSystem` |
| 23/24 结论卡 `SummaryCard` | `DiagnosticsScreen.kt:183-193`；`DiagnosticsComponents.kt:305-359` | 状态图标 16 + 状态行 + 右侧计时；第二行「开始 · 结束 · R 编号」（`Format:159-163`）；**进行中始终显示**底部栏：提示（`Format:169-174`）+「回到对话」 |
| 23 原因卡 | `DiagnosticsScreen.kt:194-209`；`DiagnosticsComponents.kt:363-380`；`Format:178-226` | 原因标题 + 说明 + 建议 + `LogPill`；动作 `MODEL_SETTINGS`（模型设置）/ `BACKGROUND_SETTINGS`（系统应用详情） |
| 23 耗时卡 | `DiagnosticsScreen.kt:210-213`；`DiagnosticsComponents.kt:393-434` | 堆叠条 + 图例，最长段加粗；仅结束后 |
| 23 时间线卡 | `DiagnosticsScreen.kt:214-231`，行 `:241-298`；`StepRow` `DiagnosticsComponents.kt:440-494` | 请求 / 工具 / 上下文压缩 / 设备事件；请求行可展开「网络阶段」，同时只展开一行，默认展开最后一次失败或进行中请求（`:146-157`）；工具行不可展开 |
| 23 页脚 | `DiagnosticsScreen.kt:232-237`；`CardNote` `DiagnosticsComponents.kt:177-185` | 卡外、三级色、无 ⓘ、两句拼成一行 |
| 任务不存在 | `DiagnosticsScreen.kt:178-181` | 「这个任务的记录已经不在了…」 |
| 25 系统事件 | `DiagnosticsScreen.kt:301-330` | 顶部说明；按天分组（卡外标题）；每行 `StepRow(first=true,last=true)` 无连接线；页脚只有「系统事件保留最近 7 天。」 |
| 26 对话中失败卡 | `ui/components/ChatMessageItem.kt:327-328`、`:2846-2860` | 仅 `RuntimeFailed` / `Interrupted` 的 SystemNotice 下方一个「查看日志」胶囊 → `DiagnosticsLinks.runForMessage`（`DiagnosticsData.kt:125-132`） |
| 卡住提示 | `ui/components/RunStallNotice.kt:26-49` | 超过 30s 无数据：「已 N 秒没有收到数据，网络可能不稳定 · 查看日志」 |
| 27 导出内容 | `DiagnosticsFormat.kt:305-374` | 设备与版本、每任务结论/原因/建议/耗时/时间线/网络阶段、系统事件、原始事件（过滤 `conversation=`、`wire_run=`，无会话标题） |

### C.2 差距（对照 §8.10）

| 差距 | 规范 | 现状 file:line | 类型 | 风险 |
|---|---|---|---|---|
| 删顶部说明段落 | 22、25 不放顶部说明 | `DiagnosticsScreen.kt:83-85`、`:306-308` | 纯 UI | 低，文案挪到页脚 |
| 分组标题放进卡片 | 按天一张卡，`Card/Title` 在卡内；「任务之外」「原因」「耗时」「时间线」同 | `SectionLabel` 在卡外：`:103,118,195,211,214,313` | 纯 UI | 低；全仓尚无 `Card/Title` 组件 |
| 页脚改 `Card/Footer` | 卡内，ⓘ 14 + 13 次要色，一句一行 | `CardNote` 卡外、三级色、无 ⓘ（`DiagnosticsComponents.kt:177-185`）；两句一行（`DiagnosticsScreen.kt:234-235`） | 纯 UI | 低 |
| 列表页保存策略页脚 | 「保存最近 20 个任务，不含对话内容。」 | 在顶部说明里 | 纯 UI | 低 |
| 系统事件页页脚 | 两句 | 只有第二句（`:328`） | 纯 UI | 低 |
| 失败时间线页脚 | 两句分行 | 合成一句（`:235`） | 纯 UI | 低 |
| 芯片间距 | 顶栏下 12 | contentPadding 8 + 说明 + 上 24（`:90`） | 纯 UI | 低 |
| 进行中底部栏出现时机 | **30 秒无数据**才出提示和「回到对话」 | 进行中一直显示（`:190-191`，`Format:169-174`） | 行为（小） | 中：改后正常运行时无「回到对话」 |
| 「·」不带空格 | 「·」 | 到处 `" · "`：`Format:124,131,146,159,277` 等，含导出 | 纯 UI（文案） | 中：`DiagnosticsFormatTest` 断言要同步 |
| 时长格式 | < 1s 毫秒，≥ 1s 一位小数秒 | `duration()` ≥60s「N 分 M 秒」、`compact()`「1m2s」（`Format:73-83`） | 纯 UI | 中：`DiagnosticsFormatTest.unitsFollowListAndTimelineConventions` |
| 行结论前多了时刻 | 只写结论 | 「15:02 · 」前缀（`Format:124`） | 纯 UI | 低 |
| 结论卡第二行 | 「开始·结束·R 编号」，对齐卡内 44 | 缩进 24（`DiagnosticsComponents.kt:333`） | 纯 UI | 低 |
| 统一组件 | `Settings/Row`、`TopBar/Secondary`、Q7 顶栏 | 自绘 `LogRow`、`LogPage`；顶栏不随滚动变化 | 纯 UI | 中：待设置页组件落地后替换 |
| 导出菜单 | `Popover/Menu` | M3 `DropdownMenu` 圆角 20（`DiagnosticsData.kt:159-173`） | 纯 UI | 低 |
| 系统事件行 | `Work/Step` 时间线 | first=last=true，无连接线 | 纯 UI | 低 |
| **26 对话中失败卡** | Rose ✕ +「任务没有完成」+ 用时 ›；原因卡 + 「重试」「查看日志」 | 仅「查看日志」胶囊（`ChatMessageItem.kt:2846-2860`）；无原因卡；重试在消息操作里 | 行为 + UI | **高**：需把 `DiagnosticsFormat.explain` 挪到对话侧复用，重试接 `onRegenerateMessage`；与 §8.1 执行卡改造重叠 |
| 浮层内无日志入口 | 未写明 | `LocalRunLogOpener` 在浮层为 null（`DiagnosticsData.kt:118-122`） | 待确认 | 低 |
| 卡住提示样式 | 执行卡底部栏 / 提示条 | 一行 Miuix 文字（`RunStallNotice.kt:40-48`） | 纯 UI | 低 |
| 暂停不进记录 | 暂停是一种状态 | `TraceStatus` 无 PAUSED（`diagnostics/DiagnosticTrace.kt:10`）；暂停/继续不写诊断事件（`AgentRuntimeService.kt:787-800`）；暂停时长计入总用时并归「其他」 | 行为 | 中：耗时构成失真 |

### C.3 测试（`app/src/test/kotlin/io/github/mangi/eta/`）

- `ui/screens/diagnostics/DiagnosticsFormatTest.kt`：`unitsFollowListAndTimelineConventions`（:18）、`failedRunExplainsReasonAdviceAndExportsWithoutConversationIds`（:31）——改「·」与时长格式时必动。
- `diagnostics/DiagnosticTraceTest.kt`：成功任务耗时构成与时间线排序（:38）、失败重试链与设备事件（:77）、进行中当前步骤（:113）、保留规则与中断补齐（:128）、存储往返（:150）。
- `diagnostics/DiagnosticBufferTest.kt`：FIFO 淘汰、字节上限、并发、脱敏、R1/R10 不混淆。
- `diagnostics/RunProgressTest.kt:18`：沉默时长跨重试累计，执行工具时不计。
- `agent/runtime/AgentDiagnosticEventsTest.kt:8`：事件白名单，不含对话与工具内容。
- **无测试**：Compose 页面（筛选、展开、导出菜单）、`DiagnosticsLinks.runForMessage`、`RunStallNotice`、路由跳转。

## D. 执行详情页 §8.8 数据盘点

| 问题 | 结论 | 证据 file:line |
|---|---|---|
| AgentEvent 定义在哪 | `agent/runtime/AgentEvent.kt:5` `sealed interface AgentEvent`；Bundle 编解码 `AgentRuntimeWire.kt:~600-760`、`:845-890`；JSON 编解码 `AgentEventJsonCodec.kt:8` | — |
| 事件类型与字段 | `ContextCompaction(operationId, phase, tokensBefore, tokensAfter?, reasonCode)` :14<br>`RunStarted(initialImages, initialImageBytes, toolCount, terminalTools)` :38<br>`RoundStarted(round, messageCount)` :48<br>`ModelRetryScheduled(round, attempt, maxAttempts, delayMs, reasonCode, …, displayMessage)` :56<br>`ProviderRequestStarted(round)` :72<br>`ProviderResponseStarted(round, httpCode)` :79<br>`AssistantBlockStart(round, kind, index, blockId?, name?)` :87<br>`AssistantBlockDelta(round, kind, index, deltaChars, delta)` :99<br>`AssistantBlockEnd(round, kind, index, blockId?, name?, contentChars, replacementContent?)` :110<br>`AssistantReceived(round, contentChars, reasoningContent, toolNames)` :124<br>`UsageReceived(round, usage)` :136<br>`UserSupplementReceived(index, text)` :144<br>`ToolStarted(round, toolCallId, name, argsPreview, command?)` :152<br>`ToolFinished(round, toolCallId, name, resultSummary, imageCount, imageBytes, success?)` :164<br>`HostedToolStarted(round, toolCallId, name)` :179<br>`HostedToolFinished(round, toolCallId, name, success)` :188<br>`ToolImagesAttached(round, toolName, imageCount, imageBytes)` :198<br>`RunFinished(round, contentChars)` :209<br>`RunFailed(reason)` :217<br>**没有**：RunPaused / RunResumed / RunCancelled / SupplementAdopted / 目标 App 变化 | `AgentEvent.kt` 各行 |
| 每个工具步骤有无开始/结束时间 | **AgentEvent 上没有**：所有事件不带时间戳，Bundle/JSON 也不写（`AgentRuntimeWire.kt:702-719`）；聊天侧 `ToolActivityMessageUi` 无时间字段（`ui/model/AgentChatUiState.kt:135-143`）；落库 `ConversationMessageEntity` 无 started/ended/created_at（`data/db/ConversationEntities.kt:85-106`）。只有「思考」有 `elapsed_seconds`（:99，Projector 用 `elapsedRealtime` 算 `AgentRunMessageProjector.kt:15-17,423-429`）。<br>**诊断日志有**：每条记录带 `timeMillis`/`elapsedMillis`（`MemoryDiagnostics.kt:184-197`）；`tool.started/finished`（`AgentDiagnosticEvents.kt:11-14`）；`ToolTrace(name, round, startedAt, startElapsed, durationMs, success)`（`DiagnosticTrace.kt:41-48`）。但**不带 toolCallId**，按「工具名+round」配对（`DiagnosticTrace.kt:274-298`），同轮同名工具多次调用会配错 | 如左 |
| 运行状态是否持久化 | **只在服务内存**：`AgentOverlayPhase`/`AgentOverlayState`（`agent/overlay/AgentOverlayState.kt:7,13-18`）；暂停/继续只改内存（`AgentRuntimeService.kt:787-800`，`AgentRunController.kt:25,75-86`）。<br>间接：诊断日志 `filesDir/diagnostics/events.log`（`DiagnosticsEnvironment.kt:28-35`）有 run.started/completed/cancelled/failed/ended/interrupted → `TraceStatus`（`DiagnosticTrace.kt:219-229`），**无 PAUSED**，且只留最近 20 个任务（`DiagnosticStore.kt:71`）。对话只落终态提示 `SystemNoticeCode`（Stopped/RuntimeFailed/Interrupted，`AgentChatUiState.kt:59-65`）；`running` 标记明确不持久化（:81-82） | 如左 |
| 目标 App 包名/名称是否持久化 | **没有**。只有无障碍实时 `currentPackageName()`（`agent/accessibility/AgentAccessibilityService.kt:179`）与 `launch_app` 结果摘要文字（`agent/model/AgentTraceFormatter.kt:246`）。`EntrySurfaceGuard.targetPackageName`（`EntrySurfaceGuard.kt:14`）是**发起入口**包名，不可挪用 | 如左 |
| 聊天消息与工作步骤存哪 | Room `conversation_messages`，工具步骤 `type=TYPE_TOOL`，列 tool_name/tool_status/arguments_summary/result_summary/image_count，command 存 content（`AgentConversationStore.kt:243-254,308-316`）；恢复时未结束的 Running → Unknown（:326-331）；会话表 `applied_runtime_run_ids_json`（`ConversationEntities.kt:20`）。外部入口任务另存 `runtime_archive_runs/events`（AgentEvent JSON，无时间，`data/db/RuntimeRunEntities.kt:42-91`、`AgentRunArchiveStore.kt:33-44,87-93`）；在途检查点 `runtime_inflight_*`（:93-138） | 如左 |
| 诊断里有无整任务时间 | 有：`RunTrace.startedAt/startElapsed/durationMs/status/tools/timeline`（`DiagnosticTrace.kt:120-132`），`durationMs` 取自 run.ended（:234）。缺点：仅 20 个任务、含暂停时间、只有元数据无结果摘要 | 如左 |
| 有无 runId 连接消息 ↔ 诊断 | 间接：wireRunId 嵌在消息 ID（`"$runId-tool-$round-$callId"` 等，`AgentRunMessageProjector.kt:493-509,561-573`；补充 `user-$runId-supplement-$i`，`AgentPendingResultRecovery.kt:214-215`）；诊断 `bindRun(wireRunId, conversationId)` 写 `run.bound`（`MemoryDiagnostics.kt:128-134`，调用 `AgentRuntimeRunExecutor.kt:75-82`）；反查 `DiagnosticsLinks.runForMessage` 为 `messageId.contains(wire)` 子串匹配（`DiagnosticsData.kt:125-132`） | 如左 |
| 补充「下一步生效 / 已采纳」 | `UserSupplementReceived` 在服务**收到**时发（`AgentRuntimeService.kt:847-859`），不是采纳时；采纳在 `AgentLoop.kt:234,241` `pollSteeringMessage/pollSteeringOrSeal`，不发事件 → 区分不出「已采纳」 | 如左 |

### D.1 需要新增的数据和路由

1. `ToolStarted` / `ToolFinished` / `HostedTool*` 加 `atMillis`（必要时加 elapsed），写进 Bundle、JSON、检查点、归档；诊断事件补 `tool_call_id`。
2. 新事件：`RunPaused`、`RunResumed`、cancelled 终态、`SupplementAdopted(index)`（AgentLoop 采纳时发）。
3. 运行记录：runId(wire)、conversationId、`startedAt`、`endedAt`、`status`（含 PAUSED）、`targetPackage`、`targetLabel`、暂停累计时长；新表或在 `conversation_messages` 加列。目标 App 在 launch_app 成功或无障碍检测到前台包变化时写入。
4. `AppRoute.RunDetail(runId)` 挂到 `AgentAppRoot`；执行卡「查看」与摘要条进入；「切过去看」复用 `getLaunchIntentForPackage`。
5. `ToolActivityMessageUi` 加 startedAt/endedAt 并落库（Room migration），执行卡「用时 N 秒」与每步时长才能从持久化数据算出。
6. App → Runtime 需要新的 wire 消息（PAUSE / RESUME / STEER），见 B.4-2。

### D.2 可从诊断模块复用

- `DiagnosticsComponents` 的 `SummaryCard`（Run/Summary）、`StepRow`（Work/Step，含展开块与连接线）、`StatusIcon`、`LogPill`、`LogCard`，按 §8.8 调整（16 小光球、第二行对齐 44、同时只展开一行）后可作为详情页基础组件。
- `DiagnosticsFormat` 的 `timer`、`clock`、`dayClock`、`summaryMeta` 的「开始·结束」写法。
- `RunTrace.startedAt/durationMs`、`ToolTrace.durationMs` 可在过渡期**兜底**显示时长（经 `run.bound` 对上 wire_run），但 20 个任务上限、无 toolCallId、无暂停，不能作为主数据源。
- 「回到对话」链路（`selectConversation` + `popToHome`，`AgentAppRoot.kt:593-596`）可沿用。

### D.3 相关测试

- `agent/runtime/AgentRuntimeWireTest.kt`：`eventBundleRoundTripPreservesReasoningAndUsage`（:464）、`retryEventSurvivesIpcAndArchiveJson`（:104）——事件加字段需补往返断言。
- `agent/runtime/AgentRunArchiveStoreTest.kt:27`：`saveAndLoadPreservesHandoffEventsAndResult`。
- `agent/runtime/AgentRunControllerTest.kt`：`pauseBlocksUntilResume`（:107）、`steeringDoesNotResumeAPausedRun`（:133）、`cancelWhilePausedWakesWorkerWithCancellation`（:158）、steering 队列 :43、:59。
- `ui/app/AgentRunMessageProjectorTest.kt`：工具按 round+toolCallId 投影（:199）、中断工具变 Unknown（:401）、补充消息重放（:92）。
- `agent/runtime/AgentContextPersistenceTest.kt`、`AgentRunCheckpointStoreTest.kt`：Room 迁移时一并关注。
