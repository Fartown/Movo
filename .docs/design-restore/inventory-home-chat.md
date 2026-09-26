# 首页 / 对话页 功能盘点与设计规范差距（DESIGN_SYSTEM §8.0–8.4）

> 目的：按 `docs/DESIGN_SYSTEM.md` §8.0 首页、§8.1 对话与执行、§8.2 输入与主按钮状态机、§8.2.1 执行条、§8.3 语音模式、§8.4 执行中补充 还原界面时，**不丢失、不破坏现有功能**。
> 只读盘点，未改任何源码。路径前缀 `eta/` = `app/src/main/kotlin/io/github/mangi/eta/`。行号基于分支 `feat/design-v1-restore`（HEAD 15128f6）。

---

## 0. 先要知道的结构事实

| 事实 | 证据 |
|---|---|
| **首页和对话页是同一个页面**。`AppRoute.Home` 渲染 `AgentConversationContent`；`AppRoute.Chat` 只做 `popToHome()`（兼容旧的导航存档） | `eta/ui/app/AgentAppRoot.kt:341-354` |
| 实际渲染链：`AgentConversationContent` → `AgentChatScreen` → `AgentChatBody`。空会话时 `AgentChatBody` 显示 `EmptyChatState`（即现在的「首页」），有消息时显示消息列表 | `eta/ui/app/AgentConversationContent.kt:56`；`eta/ui/components/AgentChatBody.kt:362-393` |
| `AgentHomeScreen`（及 `AgentHomeAction`）**是死代码**，没有任何调用方 | `eta/ui/screens/home/AgentHomeScreen.kt:22`；grep `AgentHomeScreen(` 只有定义 |
| `AgentStatusCard`、`ActiveRunSummaryUi`、`RunSummaryUi`、`RunStatusUi` **是死代码** | `eta/ui/components/AgentStatusCard.kt:33`；`eta/ui/model/AgentHomeUiState.kt:12-36` |
| `RunTraceMessageUi`、`SuggestionChipsMessageUi` 从未被构造（渲染分支存在但走不到）；`ToolSummaryMessageUi` 只会从旧存档恢复 | `eta/ui/components/ChatMessageItem.kt:337,346`；`eta/ui/app/AgentConversationStore.kt:318` |
| 同一个 `AgentConversationContent` 还用于 **App 外对话浮层** `AgentConversationSheetActivity`，改输入框/消息组件会同时影响浮层 | `eta/ui/AgentConversationSheetActivity.kt:170` |
| 顶栏由壳层 `AgentAppShell` 统一提供，页面本身不画顶栏 | `eta/ui/app/AgentAppShell.kt:100-120` |

---

## 1. 首页 / 对话页现有的全部可见元素与交互

### 1.1 顶栏（`AgentAppShell` → `AgentTopBar`）

| 元素 | 位置 | 行为 / 回调 |
|---|---|---|
| 左：菜单（`Icons.Rounded.Menu`，描述「对话历史」） | `AgentAppShell.kt:179-185` | `onOpenConversationPane` → `conversationPaneOpen = true` 打开侧边栏（`AgentAppRoot.kt:263`） |
| 非首页路由左侧返回 | `AgentAppShell.kt:186-188` | `onBack` → `popRoute()`（`AgentAppRoot.kt:230-234`） |
| 中：标题 | `AgentAppShell.kt:207-213`、`titleForRoute` `:336-337` | 首页固定为 **空字符串**，**不显示会话标题**，也没有后台任务胶囊 |
| 右：更多（`MoreVert`） | `AgentAppShell.kt:244-249` | 点击时先 `onRefreshKimiWeb()` 再展开菜单 |
| 更多菜单 · 新建对话 | `AgentAppShell.kt:269-278, 321` | `createConversation()` → `agentState.createConversation()`（`AgentAppRoot.kt:242-246`） |
| 更多菜单 · 打开终端 | `:279-288, 322` | `pushRoute(AppRoute.Terminal)`（`AgentAppRoot.kt:267`） |
| 更多菜单 · Kimi Web（文字随状态变） | `:289-298, 323` | 未安装 → `pushRoute(LinuxEnvironment)`；否则 `appViewModel.launchKimiWeb`，失败 Toast（`AgentAppRoot.kt:268-283`） |
| 更多菜单 · 打开浏览器 | `:299-308, 324` | `pushRoute(AppRoute.Browser)`（`AgentAppRoot.kt:288`） |
| 更多菜单 · 停止 Kimi Web（仅 `canStopKimiWeb`） | `:309, 325` | `appViewModel::stopKimiWeb` |
| 顶栏背景模糊 | `TopBarBackdrop`，`AgentAppShell.kt:92-102,125` | 纯视觉 |
| 侧边栏（仅首页路由挂载） | `AgentAppShell.kt:134-154` | 搜索会话 `updateSearchQuery`、选择/重命名/导出/删除会话、设置、模型服务、工具、技能、角色、权限（`AgentAppRoot.kt:289-310`）。**会话搜索在侧边栏里，不在首页** |

### 1.2 空会话态（当前的「首页」）`EmptyChatState`

| 元素 | 位置 | 行为 |
|---|---|---|
| 普通会话标题「有什么可以帮你？」（headline1，居中） | `AgentChatBody.kt:1074-1079` | 无 |
| 角色会话：角色名 + 「故事从这里开始」 | `AgentChatBody.kt:1061-1072` | 角色会话不显示建议卡 |
| 4 张建议卡（2×2 网格）：分析当前屏幕 / 打开微信 / 浏览网页 / 查看内存压力 | 定义 `AgentChatBody.kt:1031-1052`，卡片 `SuggestionCard` `:1122-1154` | 点击 → `onSuggestionClick(prompt)` → `AgentChatAction.SubmitMessage(prompt)`（`AgentChatScreen.kt:62-64`）→ 直接发送 |
| 建议卡在键盘弹出时隐藏 | `showEmptySuggestions = !isKeyboardVisible`（`AgentChatBody.kt:236`），动画 `:1083-1100` | — |
| **不存在**：光球、日期、分时段问候、「为你留意」卡、「试试让 Movo」横滑能力卡、后台任务胶囊 | — | 见 §4 |

### 1.3 消息列表 `AgentConversationMessages`（`AgentChatBody.kt:399-723`）

| 元素 | 位置 | 行为 / 回调 |
|---|---|---|
| 时间线分组：连续的 Thinking / ToolActivity / ToolSummary 合并为一个「工作过程」卡，其余每条消息独立 | `toTimelineEntries` `AgentChatBody.kt:785-811` | 见 §3 |
| 编辑模式下隐藏被编辑用户消息之后的消息（非「保留后续」编辑） | `AgentChatBody.kt:172-179` | `AgentConversationRevisionReducer.visibleMessagesForEdit` |
| 首次打开跳到最新 | `AgentChatBody.kt:182-186` | 仅 `initiallyShowLatestMessage`（浮层用） |
| 流式跟底（帧驱动平滑跟随，用户拖动即停） | `AgentChatBody.kt:435-603`；纯函数 `resolveKeepBottomAnchored` `:1000`、`resolveBottomFollowEnabled` `:1010`、`shouldRequestInitialBottom` `:1017`、`resolveBottomFollowDecision` `:738`、`smoothBottomFollowStep` `:756` | 有单测（§5） |
| 发送即重新锚定底部 | `AgentChatBody.kt:240-246` | — |
| 回到底部按钮（40 圆，`ArrowDownward`） | `AgentChatBody.kt:695-721` | 条件 `!keepBottomAnchored && !isAtBottom`；点击 `onBottomAnchorChanged(true)` + `animateScrollToItem(bottom)` |
| 卡住提示 `RunStallNotice`（仅 `isStreaming`） | 挂载 `AgentChatBody.kt:683-685`；组件 `RunStallNotice.kt:27-49` | 每秒轮询 `MemoryDiagnostics`，≥ `STALL_MS` 未收到数据显示「已 N 秒没有收到数据，网络可能不稳定 · 查看日志」，点击 → `LocalRunLogOpener` → `AppRoute.DiagnosticsRun(runId)`（`AgentAppRoot.kt:248-250,318`） |
| 流式发送后收键盘 | `AgentChatBody.kt:209-214` | 只在从输入框发送时 |
| 抽屉打开收键盘 | `AgentChatBody.kt:216-220` | — |
| 底部模糊渐隐（有消息时真模糊，无消息时 16dp 渐变） | `AgentChatBody.kt:881-930` | 纯视觉 |
| 当前浏览器快捷入口：只给最后一个 `browser_use` 工具行 | `AgentChatBody.kt:187-205, 647-649` | 见 1.5 |
| 「最终结果」判定：每轮最后一条 AgentMessage 才有复制/操作；流式中当前轮不标记 | `resolveFinalResultMessageIds` `AgentChatBody.kt:818-838` | 有单测 |
| 消息操作可用条件 `messageActionsEnabled = !isStreaming && messageEdit == null` | `AgentChatBody.kt:386` | — |

### 1.4 单条消息 `ChatMessageItem`（`ChatMessageItem.kt:255-348`）

**用户消息 `UserMessageBubble`（`:504-638`）**

| 元素 | 位置 | 行为 |
|---|---|---|
| 右对齐气泡，最大宽 320，圆角 20/右下 6，`surfaceContainerHigh` | `:567-588` | 编辑中加 1dp primary 描边 `:577-587` |
| 图片缩略图 100×100 | `:590-609` | — |
| 文件引用（`SentFileReferenceFlow`） | `:610-617`，组件 `AgentChatFileAttachments.kt:266` | 解析 `AgentFileReferencePromptCodec.parse` `:518-520` |
| 可选择文本 | `:618-626` | — |
| 「已编辑」标记 | `:627-634` | — |
| **长按 Tooltip 菜单**：复制 / 编辑 / 删除 | `:528-566` | 复制 → 剪贴板；编辑 → `onEditMessage(id)` → `agentState.beginMessageEdit`；删除 → `onDeleteMessage(id)` → 计算影响后弹确认框（`AgentConversationContent.kt:81-85,99-114`）→ `deleteMessageTurn`。`enableUserInput = actionsEnabled`（流式/编辑中禁用） |

**助手回答 `AgentMessageBlock`（`:725-927`）**

| 元素 | 位置 | 行为 |
|---|---|---|
| 空内容流式 → 打字指示 `AITypingIndicator` | `:771-775`，`:203` | — |
| 流式 Markdown（逐字显现，`SmoothTextReveal`） | `:776-784`；`StreamingMarkdown` `:981`；显现时钟 `SmoothTextReveal.kt:36-44` | **逐字/字素**显现，规范 9.4 要求「按句追加 + Q2 模糊」 |
| 完成后稳定 Markdown（可选择） | `:785-793`；代码块复制 `ChatCodeBlock` `:1743-1800`；表格 `:1843`；引用 `:1986`；列表 `:1471` | 代码块右上复制，1.4s 显示 ✓ |
| 非 Markdown 纯文本 | `:794-802` | — |
| 复制（仅最终结果/角色可编辑回复，且显现完成） | `:805-839` | 剪贴板 + 1.4s ✓ |
| 编辑角色回复（仅 `characterEditable`） | `:841-850` | `onEditMessage` → `beginMessageEdit`（保留后续的原位编辑） |
| 重新生成（Tooltip） | `:851-865` | `onRegenerateMessage` → 角色会话或无后续轮直接 `regenerateMessage`，否则确认框（`AgentConversationContent.kt:86-93,115-130`） |
| 删除（Tooltip） | `:866-880` | 同用户消息删除 |
| 角色候选回复切换 `‹ n/N ›` | `:881-922` | `onSelectReplyCandidate(id, index)` → `agentState.selectReplyCandidate` |
| **不存在**：「更多」菜单、推荐追问芯片（`SuggestionChipsMessageUi` 从未生成） | — | — |

**系统通知 `SystemNoticeMessageUi`（`:294-330`）**

| 元素 | 行为 |
|---|---|
| 上下文压缩胶囊 `ContextCompactionMarker`（`:674-720`），进行中脉冲 | 无交互 |
| 已停止 / 空结果 / 模型重试 / 运行失败 / 中断：按普通回答块显示（可复制、可重新生成、可删除） | `:296-326` |
| 运行失败 / 中断 → 「查看日志」胶囊 `RunLogLink` | `:327-329`，`:2848-2862`，→ `DiagnosticsLinks.runForMessage(id)` |

### 1.5 工作过程 / 思考 / 工具行（详见 §3）

| 元素 | 位置 | 行为 |
|---|---|---|
| 工作过程卡 `AgentWorkProcess` | `ChatMessageItem.kt:354-499` | 头部整行点击展开/收起；运行中自动展开（除非用户手动操作过） |
| 思考行 `ThinkingRow` | `:2133-2287` | 点击展开/收起；流式时自动展开；完成后「思考已完成 · 用时 N 秒」 |
| 工具行 `ToolActivityInline` | `:2292-2505` | 点击展开详情（命令块 + 结果摘要 ≤10 行 + 浏览器预览 + 「打开当前浏览器」） |
| 命令块复制 `ToolCommandBlock` | `:2603-2688` | 复制命令 |
| 浏览器预览 `BrowserPagePreview` | `:2514-2600` | 1.2s / 4s 轮询截图 |
| 「打开当前浏览器」按钮 | `:2493-2499` | `onOpenBrowser` → `pushRoute(AppRoute.Browser)` |
| 旧工具摘要 `ToolSummaryInline` | `:2739-2779` | 仅旧存档 |

---

## 2. 输入框（`AgentChatInputBar`，`eta/ui/components/AgentChatInputBar.kt`）

### 2.1 结构（自上而下）

| 区块 | 位置 | 条件 / 行为 |
|---|---|---|
| 待发送文件引用条 `PendingFileReferenceStrip` | `:194-204`；组件 `AgentChatFileAttachments.kt:195` | 每项可移除 → `onRemoveFileReference` → `removePendingFileReference` |
| 待发送图片条 `PendingImageStrip`（60×60，右上 ✕） | `:206-216`，`:555-602` | → `removePendingImage` |
| **排队消息条**「下一条：xxx」+「编辑」「撤回」 | `:218-226` | 来自 `LocalQueuedConversationInput`（`QueuedConversationInput.kt:6-12`，由 `AgentConversationContent.kt:50-55` 提供）。编辑 → `withdrawQueuedText(edit=true)` + 聚焦输入框；撤回 → `withdrawQueuedText(edit=false)` |
| 语音状态条 `AgentVoiceStatusStrip`（`voice.active`） | `:227-238`；组件 `AgentVoiceStatusStrip.kt:40-103` | 状态点 + `statusText` + 「停止播报」（仅 speaking）+ 「切回文字」+ 字幕（≤3 行）+ 提示「也可以直接说：别念了 / 取消任务 / 结束对话」 |
| 语音结束提示 `AgentVoiceNotice` | `:239-248`；`AgentVoiceStatusStrip.kt:107-122` | `!voice.active && voice.notice != null`，liveRegion |
| 编辑提示文字 | `:250-267` | 三种文案：保留后续 / 替换后续 / 替换本条 |
| 输入容器（圆角 20，阴影，0.5 描边，内边距 10/8） | `:269-297`，`InputContainerShape` `:101` | — |
| 文字区：占位 + `BasicTextField`（1–6 行） | `:298-334` | 占位：空闲「交给 Eta 去完成」，流式「Eta 正在执行…」（`:311`）；聚焦时若语音中则 `onEndVoice()`（`:321`） |
| 工具栏 | `:336-475` | 见下 |

### 2.2 工具栏按钮与条件

| 按钮 | 位置 | 显示条件 | 可用条件 | 回调 |
|---|---|---|---|---|
| 取消编辑 ✕（占附件位） | `:340-352` | `isEditingMessage` | 始终 | `onCancelMessageEdit` → `cancelMessageEdit()` |
| 附件「+」`AgentAttachmentPickerButton` | `:354-361`；`AgentChatFileAttachments.kt:70-190` | 非编辑 | **流式中也可用** | 弹出菜单：图片（PhotoPicker）/ 文件（OpenDocument）/ 文件夹（OpenDocumentTree）/ 输入路径（对话框 `:165-190`）→ `attachImage / attachFiles / attachFolder / attachFilePath` |
| 麦克风（语音对话中变键盘图标，Indigo） | `:363-387` | 非编辑且 `onToggleListen != null` | 始终（流式中也可） | 语音中 → `enterText()`（`switchToText` + 聚焦 + 弹键盘）；否则收键盘 + `onToggleListen()` → 有麦克风权限 `VoiceEntry.startInPlace` 否则请求权限（`AgentChatBody.kt:970-981,873-875`）。**进入的是语音对话，不是语音输入** |
| 思考强度 `ThinkingEffortChip`（atom 图标，仅图标色表达） | `:391-400`，`:484-549` | `availableReasoningEfforts` 非空 | `!isStreaming && options.size > 1` | 弹出列表 → `onReasoningEffortChange` → `updateReasoningEffort` |
| 上下文用量 `AgentContextUsageButton`（圆形进度） | `:405-409`；`AgentChatModelControls.kt:262-366` | `showContextUsage = hasMessages`（`AgentChatBody.kt:338`） | 点击始终可弹 Tooltip；Tooltip 内「压缩上下文」仅 `canCompactContext && !isStreaming` | ≥80% Warning，≥95% Error（`:271-276`）；压缩 → `compactCurrentContext()` |
| 模型 `AgentModelPickerButton`（品牌 Logo） | `:411-417`；`AgentChatModelControls.kt:72-142` | 始终 | `!isStreaming && !isChanging && providerGroups 非空` | 弹出按服务商分组的列表（默认展开当前服务商）→ `selectModel` |
| **停止 ■（独立按钮，10dp 图标，「取消当前任务」）** | `:419-423` | `isStreaming` | 始终 | `onStop` → `AgentChatAction.StopRun` → `agentState.stopCurrentRun()` |
| **发送 ↑** | `:424-474` | 始终 | `canSend = 文本非空 || 有图片 || 有文件引用`（`:159-161`） | `onSubmit(text)`；语音中先 `switchToText()`（`AgentChatBody.kt:952-956`）→ `SubmitMessage` → `requestNotifications()` + `sendCurrentMessage(text)`（`AgentConversationContent.kt:69-72`）。无障碍描述：`queued.busy` 时「排队发送」，保留编辑时「保存消息」，否则「发送」（`:459-463`）；图标始终是 ↑ |

### 2.3 「停止」现状

- 入口：输入框里的独立 ■（`AgentChatInputBar.kt:419-423`），与 ↑ **同时显示**。
- 调用链：`stopCurrentRun()`（`AgentAppState.kt:1765-1771`）→ 记入 `stopRequestedRunIds` → IO 线程 `AgentRuntimeClient.cancelRun(runId)`（Messenger `MSG_CANCEL`，`AgentRuntimeClient.kt:117-125`）→ 服务端 `AgentRunController.cancel()`。如 Runtime 还没开始，`launchConversationRun` 在准备完成后检查 `stopRequestedRunIds` 直接出「已停止」结果（`AgentAppState.kt:1506-1511`）；`RunStarted` 事件到达时再补一次 cancel（`:2339-2343`）。
- 语音口令「取消任务」→ `VoiceSessionManager.cancelTask()`（`VoiceSessionManager.kt:147`）→ 同一 `stopCurrentRun`。
- 旧 `AgentStatusCard` 里也有「停止」但未挂载。

### 2.4 「排队发送」现状（`queuedTextSubmission`）

- 触发：`sendCurrentMessage` 时 `voiceRuntimeBusy`（= `currentRunId != null || 任一会话 isStreaming`，`AgentAppState.kt:159`）为真 → 不开新 run，存入 `QueuedTextSubmission(conversationId, text, images, files)`（`:1103-1113`，数据类 `:134-139`），并把附件从输入框移走。
- 只能排 **一条**；已有排队或处于编辑时 Toast「已有一条待发送消息…」（`:1104-1107`）。
- 显示：输入框上方「下一条：…」+ 编辑 / 撤回（`AgentChatInputBar.kt:218-226`）。
- 撤回/编辑：`withdrawQueuedText`（`AgentAppState.kt:1188-1202`），编辑会把文本和附件放回输入框草稿。
- 发出：`init` 里监听 `voiceRuntimeBusy` 变为 false 时 `drainQueuedText()`（`:196-200`，`:1204-1223`）→ 以**新一轮 run** 发往原会话（即使用户已切到别的会话）。
- 删除会话时清掉排队（`:934`）。
- 支持**附件**（图片/文件引用）；runtime steering 只接受纯文本（见 2.7），这是改造时必须决策的点。

### 2.5 麦克风现状

- 图标 `Mic`，无障碍「开始语音对话」；实际调用 `VoiceEntry.startInPlace(context)`（`VoiceEntry.kt:18-38`）→ 启动 `EtaAssistantVoiceService` 的**语音对话**（连续多轮、自动发送、TTS 播报）。
- 状态来自进程单例 `VoiceSessionManager.state`（`AgentChatBody.kt:870`），`VoiceSessionUiState` 字段：`channel`（Off/Connecting/Listening/Hearing/Thinking/Speaking）、`statusText`、`transcript`、`conversationId`、`notice`（`VoiceSessionUiState.kt:24-39`）。
- 语音对话中说的话遇到任务在跑：**排队**，提示「已记下这句话，等当前任务完成后发送」（`VoiceSessionManager.kt:201-209`），任务结束后 `drainQueuedTurn`（`:194-199`）；`sendVoiceMessage` 在 busy 时直接 rejected（`AgentAppState.kt:1047-1051`）。
- 缺权限：`AgentChatBody.kt:975-979` 请求 `RECORD_AUDIO`；`VoiceEntry.startInPlace` 再兜底 Toast（`VoiceEntry.kt:22-27`）；未配置豆包等失败原因通过 `voice.notice` 显示。

### 2.6 语音输入（听写进输入框）是否存在

- **App 内输入框没有听写路径。** `AgentChatInputBar` 有 `dictationText: String?` 参数和把它写进输入框的 `LaunchedEffect`（`AgentChatInputBar.kt:136,154-158`），但 `AgentChatBody.kt:938-986` **从不传入**，是悬空接口。
- 可复用的底层：`EtaDictationController`（豆包双向 ASR，`start/stop/cancel`，`onPartial/onFinal/onError`）`eta/agent/voice/asr/EtaAsrSessionFactory.kt:20-60`，**全仓无调用方**；`EtaAsrSessionFactory.create` `:10-15`；麦克风互斥协调 `EtaMicSessionCoordinator.onDictationStarted/Finished`（`EtaMicSessionCoordinator.kt:74-90`）+ `EtaWakeWordService.pauseWake`（`EtaWakeWordService.kt:144-151,381`，当前由语音对话服务 `EtaAssistantVoiceService.kt:64` 调用）。
- 另有系统 `SpeechRecognizer` 封装（`agent/voice/SystemSpeechRecognizer.kt:12`），用于唤醒词/识别服务，不是听写。

### 2.7 运行时 steering（补充）通道

| 层 | 位置 | 说明 |
|---|---|---|
| 控制器 | `eta/agent/runtime/AgentRunController.kt:43-69` | `steer(text)` 入队；`pollSteeringMessage()` 逐条消费；`pollSteeringOrSeal()` 结束前原子封口；`pause()/resume()` `:75-87`，暂停时 steer 不会恢复运行（有单测） |
| 会话 | `eta/agent/runtime/AgentRuntimeSession.kt:90-106` | 仅 `state == RUNNING && operation == OP_CHAT` 接受；带事件工厂的重载会把 `UserSupplementReceived` 记入重放并广播给所有订阅者 |
| 模型循环 | `eta/agent/model/AgentLoop.kt:89,211,233-251` | 每轮开始（工具批次完成后）取一条补充，拼成「用户补充指令：…请基于当前任务上下文继续执行…」的 user 消息；自然结束前 `appendPendingSteeringOrSeal` |
| 服务入口 | `eta/agent/runtime/AgentRuntimeService.kt:803-828` `requestSupplement` | `session.steer(text){ recordSupplementEvent(text) }`（`:847-859`，分配 index、记入 `activeSupplements`）；被拒且任务已终态 → `continueFromResult`（基于上次结果开新续跑，`:830-845`） |
| 唯一调用方 | 悬浮球展开卡「补充」：`AgentOverlayBubble(onSupplement = ::requestSupplement)` `AgentRuntimeService.kt:922-932`；UI `AgentOverlayContent.kt:289-324`（`submitSupplement`）、`SupplementInput` `:492` | 暂停/继续也只有悬浮窗能调（`requestPause/requestResume` `:787-801`） |
| **App 进程到 Runtime 的 IPC 没有 steer / pause / resume 消息** | `AgentRuntimeWire.kt:47-80`（只有 START/EVENT/RESULT/CANCEL/ACK/DRAIN/QUERY/ATTACH/READ_CONTEXT）；`AgentRuntimeClient.kt:42-125` | App 内要改成补充，必须新增 `MSG_STEER`（以及暂停/继续的 `MSG_PAUSE/MSG_RESUME` 和运行状态回传） |
| App 端补充显示 | 事件 `AgentEvent.UserSupplementReceived(index, text)`（`AgentEvent.kt:144-150`）→ `applyRunEvent` → `insertSupplementMessage`（`AgentAppState.kt:2260-2262, 2474-2495`）→ `AgentPendingResultRecovery.mergeSupplements`（`AgentPendingResultRecovery.kt:177-200`） | 以 **`UserMessageUi`（id `user-$runId-supplement-$index`）插在当前流式回答之前**，即显示成普通用户气泡，会把工作过程卡切断；**没有「已采纳」事件**（Loop 消费 steering 时不发事件） |

---

## 3. 工作过程 / 工具调用渲染

### 3.1 分组规则

- `toTimelineEntries`（`AgentChatBody.kt:785-808`）：**连续**的 `ThinkingMessageUi / ToolActivityMessageUi / ToolSummaryMessageUi` 合并为一个 `WorkProcess`，key = `work-<首条id>`；遇到任何其它消息（助手正文、用户消息、补充、系统通知）就 flush。
- 结果：一次 run 中若模型在工具轮之间输出了文字、或插入了补充，会被切成**多张工作过程卡**。规范 8.1 是一次执行一张卡。
- 思考块因为属于 work 类型，总是出现在工作过程卡里（compact 样式，`ChatMessageItem.kt:2171-2189`）。

### 3.2 工作过程卡 `AgentWorkProcess`（`ChatMessageItem.kt:354-499`）

| 项 | 现状 |
|---|---|
| running 判定 | 任一思考 `isStreaming` 或任一工具 `Running`（`:362-365`） |
| 标题 | 运行中且有工具：「正在处理 · 第 N 步 · {当前工具参数摘要或工具名}」；运行中无工具：「正在分析任务」；完成：「已完成 N 个步骤」/「已完成分析」（`:424-438`，N = 工具条数 `:366`） |
| 图标 | 当前工具图标 / 灯泡 / 扳手，运行中脉冲（`:407-422`） |
| 展开 | 运行中自动展开，用户点过则不自动（`:372-379`）；**完成后不自动收起** |
| 形态 | 圆角 14、0.5 描边、左右 20（`:383-396`）；内部逐条 `ChatMessageItem(compact=true)`（`:484-494`） |
| 缺失 | 无计时、无总用时、无底部栏（「正在『X』中操作 · 查看」）、无「结束任务」、无小光球、不跳转详情页 |

### 3.3 每一步的数据（`AgentChatUiState.kt`）

| 类型 | 字段 | 时间信息 |
|---|---|---|
| `ToolActivityMessageUi` `:135-143` | `id`（`…-tool-<round>-<toolCallId>`）、`toolName`、`status`（Running/Success/Failed/Unknown `:145-150`）、`argumentsSummary`、`command`、`resultSummary`、`imageCount` | **无**开始/结束时间、无时长 |
| `ThinkingMessageUi` `:102-108` | `content`、`isStreaming`、`elapsedSeconds`、`collapsed` | 有秒数：投影器内存里 `thinkingStartedAt`（`AgentRunMessageProjector.kt:17,419-432`），完成时写入并持久化（`AgentConversationStore.kt:233-241`） |
| `AgentMessageUi` `:48-57` | `usage`（上下文 token 等） | 无 |
| 补充 | 作为 `UserMessageUi` | `createdAt` 只在 handoff payload（`AgentAppState.kt:2484-2488`），UI 模型不带 |

- 工具状态流转：`startTool` → Running（`AgentRunMessageProjector.kt:303-319`）；`finishTool` 按 `success` 或摘要里 `ok=false` 判定（`:321-351`）；Hosted 工具 `:353-383`；`RunFailed` → 运行中工具置 Failed（`:385-398`）；恢复中断 → Unknown（`:400-413`）。
- 事件本身也无时间戳：`AgentEvent.ToolStarted / ToolFinished`（`AgentEvent.kt:152-177`）只有 round、toolCallId、name、args/command、result、images、success。
- 持久化实体 `ConversationMessageEntity`（Room）工具行只存 command/toolName/status/args/result/imageCount（`AgentConversationStore.kt:243-254`）；加时间字段需要 **Room 迁移**。
- 诊断侧有时间：`MemoryDiagnostics` 记录 `tool.started/finished`（`diagnostics/MemoryDiagnostics.kt:158-160`，`DiagnosticEntry.timeMillis/elapsedMillis` `:10-18`），但只供运行日志用、按条数淘汰，不适合作为对话卡的数据源。
- `AgentRunTiming`（`agent/runtime/AgentRunTiming.kt:7-60`）只记请求/首包耗时到日志。

### 3.4 运行级状态

- App 端只有 `AgentChatUiState.isStreaming` / `isCompacting`（`AgentChatUiState.kt:17-18`）+ `AgentAppState.currentRunId`（`AgentAppState.kt:132`）。**没有 暂停 / 失败 / 完成 的运行状态，也不知道正在操作哪个 App。**
- `RunStatusUi`（Running/Success/Failed/Cancelled，`AgentHomeUiState.kt:31-36`）存在但未使用，且没有 Paused。
- 暂停/继续状态只存在于悬浮窗服务：`AgentOverlayPhase.PAUSED / AgentOverlayStatus.Paused`（`AgentRuntimeService.kt:787-801`）。

---

## 4. 与规范 §8.0–8.4 的差距清单

标记：**UI** = 仅界面（不改数据/调用链）；**行为** = 改业务逻辑、IPC、持久化或交互语义。

### 4.1 §8.0 首页

| 规范项 | 现状 | 需要改 | 类型 |
|---|---|---|---|
| 顶栏：左菜单、右「+」新建对话 | 右侧是 ⋮ 溢出菜单（新建/终端/Kimi/浏览器/停止 Kimi）`AgentAppShell.kt:231-333` | 右侧换成「+」直接 `createConversation`；**终端、Kimi Web（含停止）、浏览器 三个入口必须迁移**（侧边栏或工具页），否则功能丢失；`onRefreshKimiWeb` 触发点要跟着搬 | UI + 信息架构（需确认去向） |
| 顶栏中：后台任务胶囊「N 个任务在后台运行」，点击进入会话 | 无；App 同一时间只跟踪一个 `currentRunId`；外部（语音/浮层）run 通过 `MSG_QUERY_ACTIVE_RUN` 可查 | 新增运行中任务来源（本进程 run + Runtime active run）与点击跳转会话 | 行为（数据源） |
| 问候区：光球 52 + 日期 + 分时段问候 +「今天想交给我什么？」 | 「有什么可以帮你？」`AgentChatBody.kt:1074-1079` | 新组件；角色会话的「角色名 + 故事从这里开始」要保留（规范未覆盖，建议保留分支） | UI |
| 为你留意（通知与日程，行内「整理」「提醒我」→交给 Movo） | 无；有通知监听服务 `agent/device/AgentNotificationHistoryService.kt`，无日历读取 | 新数据源 + 权限判断；无权限/无内容时整卡隐藏；行按钮 = 发送一条指令 | 行为（新功能） |
| 试试让 Movo：5 张能力卡横滑，「全部能力 ›」开工具页 | 4 张 2×2 建议卡 `AgentChatBody.kt:1031-1154`，键盘弹出时隐藏 | 换成 5 类横滑卡；点击仍走 `onSuggestionClick → SubmitMessage`（保留）；「全部能力」→ `pushRoute(AppRoute.Tools)` | UI（点击语义不变） |
| 首页输入框：无上下文用量；占位「交给 Movo 去完成…」 | 空会话已不显示用量（`showContextUsage = hasMessages`）；占位「交给 Eta 去完成」 | 文案改 Movo | UI |
| 首页 → 对话动效（9.4） | 仅 `animateItem` 淡入 | 动效 | UI |

### 4.2 §8.1 对话与执行

| 规范项 | 现状 | 需要改 | 类型 |
|---|---|---|---|
| 用户消息：最大宽 296、`bg/surface`+0.5 描边、圆角 20 右下 8 | 最大宽 320、`surfaceContainerHigh` 无描边、右下 6（`ChatMessageItem.kt:567-588`） | 样式；**长按菜单（复制/编辑/删除）保留** | UI |
| 助手回答：无气泡、Body/Reading、加粗 Medium、列表编号次要色等 | 基本一致，需对 token | 字体/颜色 token | UI |
| 回答流式：按句追加 + Q2 模糊显现 | 字素级逐字显现 `SmoothTextReveal`（有大量单测） | 改显现粒度 → 会影响 `SmoothTextReveal*Test` / `StreamingMarkdown*Test` 的预期 | 行为（渲染逻辑，高风险） |
| 消息操作：复制、重新生成、更多（32 按钮 / 16 图标） | 复制、[编辑角色回复]、重新生成、删除、[候选切换]，30 按钮 / 15 图标（`:811-924`） | **删除、编辑角色回复** 挪进「更多」菜单；候选切换 `‹n/N›` 规范未覆盖，需保留 | UI |
| 推荐追问 `Chip/Suggestion` | 渲染组件在（`SuggestionChipsRow` `:2785`），但从未产生数据 | 若要显示需新增生成逻辑；否则保持不显示 | 行为（新功能，可延后） |
| 工作过程 · 展开：圆角 28、头部 48（小光球 + 「正在执行·第 N 步」+ 计时 + 收起箭头）、时间线（状态图标 16 + 竖线 + 每步耗时） | 圆角 14、头部灯泡/工具图标 +「正在处理 · 第 N 步 · 工具名」，无计时，无竖线，无每步耗时 | 视觉重做；**计时/每步耗时需数据**（见 4.6） | UI + 行为（数据） |
| 工作过程 · 收起：48 胶囊「已完成 N 个步骤 · 用时 9 秒 ›」，整条打开执行详情页 | 完成后仍是可展开卡，点击只展开/收起 | 收起态改胶囊；点击改为 **跳转 `Run/Detail`（路由不存在）**。在详情页落地前，保留当前「展开看步骤」能力，避免步骤详情（命令、结果、浏览器预览）无处可看 | 行为（导航） |
| 完成后自动收起为摘要条（用户手动操作过则不收） | 不收起 | 增加完成→收起逻辑 | UI 状态 |
| 一次执行一张卡 | 被助手中间文字 / 补充切成多张（`AgentChatBody.kt:785-811`） | 改分组：按 runId 聚合，中间文字如何放要定（放卡内还是卡外） | 行为（分组算法） |
| 工作步骤：思考 sparkle / 完成 ✓ / 进行中加载圈 / 失败 ✕；失败原因 | 工具图标按类型；成功小 ✓，运行中/失败/未知为「状态点 + 文字」；失败原因一行已显示（`:2324-2332,2375-2388`） | 视觉；**Unknown（中断）状态规范没有**，需给映射 | UI |
| 工具行展开详情（命令块复制、结果、浏览器预览、打开浏览器） | 在卡内展开（`:2445-2503`） | 规范把详情放到执行详情页；在详情页之前 **不能删除**，否则「打开当前浏览器」入口丢失 | UI（依赖 8.8） |
| 补充 Supplement 行（Indigo 转折箭头 +「你的补充」+ 引用块 + 下一步生效/已采纳） | 补充显示为用户气泡 | 在工作过程卡内按 Work/Step 渲染；「已采纳」需新事件 | 行为（见 4.5） |
| 工作过程底部栏：执行中「正在『美团』中操作」+「查看」；已暂停「已暂停」+「结束任务」+「查看」 | 无 | 需要 目标 App（包名/名称）、运行状态（暂停）、详情页路由、结束任务（二次确认 → 现有 `stopCurrentRun`） | 行为 |
| 思考块：「思考中」/ 结束「思考·2 秒」 | 「思考中…」/「思考已完成 · 用时 N 秒」（`:2216-2227`） | 文案 | UI |
| 上下文用量：20 用量表（外圈描边 + 扇形），≥80% Amber | 圆形进度环，≥80% Warning，≥95% Error | 视觉；**Tooltip 内「压缩上下文」入口必须保留** | UI |
| 顶栏 · 会话中：中间显示会话标题（最大宽 220） | 标题恒为空（`AgentAppShell.kt:337`） | 从 `conversationPaneState.conversations` 取当前标题（浮层已有同样取法 `AgentConversationSheetActivity.kt:161`） | UI（读已有数据） |
| 输入框 · 执行中：占位「补充要求，下一步生效…」；思考、模型、用量隐藏 | 占位「Eta 正在执行…」；思考、模型禁用（不隐藏）；用量仍显示 | 隐藏三者；改占位 | UI（但占位语义依赖 4.5 的补充行为） |
| 卡住提示（规范 8.10 为任务详情底部栏） | 列表末尾一行文字 + 查看日志 | 规范 §8.1 未定义对话内样式，**保留**，只做样式对齐 | UI |
| 回到底部按钮 | 已有 40 圆按钮 | 样式对齐 9.3 | UI |

### 4.3 §8.2 输入框与主按钮状态机

| 规范项 | 现状 | 需要改 | 类型 |
|---|---|---|---|
| 主按钮 = Agent 状态机：空闲空=置灰 ↑；空闲有内容=↑；运行中空=■；运行中有字=↑（补充）；暂停空=▶；暂停有字=↑（补充，保持暂停） | 运行中 ■ 与 ↑ **同时显示**（`AgentChatInputBar.kt:419-474`）；↑ 在运行中=排队；无暂停态 | 合并为单一按钮；运行中有字的 ↑ 改走 steering；暂停/继续需新状态与 IPC | 行为（核心） |
| 同一屏只有一个停止入口 | 输入框只有一个 ■；但规范要求执行卡/执行条不放停止——现状卡里本来也没有 | 保持 | — |
| 执行中不可用的控件直接隐藏 | 思考/模型为禁用态 | 改隐藏 | UI |
| 工具栏布局：空闲 附件、思考 ｜ 用量、模型、主按钮；运行中/暂停 附件 ｜ 主按钮；语音对话中 切回文字 ｜ 主按钮 | 附件、麦克风、思考 ｜ 用量、模型、[■]、↑ | 麦克风移出工具栏到文字行右端；编辑态的「取消编辑 ✕」规范未覆盖，**需保留**（占附件位即可） | UI |
| 文字行右端：空=麦克风（语音输入）+ 声波（语音对话）；有字=只留麦克风；语音输入中麦克风 Indigo 浅底 | 文字行右端无按钮 | 新增两个 32 按钮；声波 = 现有 `startVoiceConversation` 入口（原麦克风逻辑整体搬过去，含权限申请） | UI（声波）+ 行为（麦克风） |
| 麦克风 = 语音输入（听写写进输入框，未确认三级色，2 秒静默自停，正在听时点发送=先停再发） | **不存在**（见 2.6） | 接 `EtaDictationController` 或系统识别；需与唤醒词/语音对话抢麦协调（`EtaMicSessionCoordinator`）；写入 `AgentConversationDraftStore` 草稿 | 行为（新功能） |
| 输入框尺寸：外边距 20、圆角 28、上 12 其余 8、文字行 32、工具栏 40、总高 100；最多 6 行、顶部 16 淡出 | 外边距 14/底 12（`AgentChatBody.kt:936`）、圆角 20、内边距 10/8；6 行上限已有（`:329-332`），无淡出 | 尺寸与淡出遮罩 | UI |
| 附件在执行中可用 | 已可用 | 执行中带附件发送的去向要定（steering 只收文本） | 行为（决策） |
| `Composer/Notice`（缺麦克风权限 / 未配置豆包：输入框上方提示条 + 去开启/去设置，处理后自动开始听） | 权限走系统弹窗；失败原因走 `AgentVoiceNotice` 纯文字（`AgentChatInputBar.kt:239-248`）或 Toast | 新提示条组件 + 动作按钮（去设置 → `AppRoute.VoiceSettings`，入口已存在 `AgentAppRoot.kt:223-228`） | UI + 少量行为 |

### 4.4 §8.2.1 执行条 `Composer/TaskBar`

| 规范项 | 现状 | 需要改 | 类型 |
|---|---|---|---|
| 执行卡滚出屏幕时，输入框上方 8 出现 372×40 条：小光球 +「正在执行·第 N 步」+ 计时 +「查看」；点条身滚回任务卡 | 不存在 | 新组件；需监听 LazyColumn 中当前运行 WorkProcess 项是否可见（`scrollState.layoutInfo`）；计时同 4.6 数据缺口；「查看」依赖详情页 | UI + 行为（依赖数据/路由） |

### 4.5 §8.3 语音模式 & §8.4 执行中补充

| 规范项 | 现状 | 需要改 | 类型 |
|---|---|---|---|
| 语音对话在输入框**原地**切换（上行字幕/状态，下行 切回文字 + 提示 + 主按钮），骨架与文字模式一致 | 输入框上方额外一张状态条 `AgentVoiceStatusStrip`，输入框本身不变，麦克风图标变键盘 | 重做为输入框内的 `Composer/Voice`；音量条需要 0–1 电平（现 `VoiceSessionUiState` 无电平字段） | UI + 行为（新增电平数据） |
| 不单设「停止播报」：说话打断 / 说「别念了」/ 空闲时点 ■ | 状态条上有「停止播报」按钮（`AgentVoiceStatusStrip.kt:70-77`） | 去掉按钮前确认 ■ 在「回答播报中」能停止播报：当前 ■ 只在 `isStreaming` 时出现且只取消 run，**不停止 TTS**；需要把 ■ 在播报态接到 `VoiceSessionManager.stopSpeaking` | 行为 |
| 语音模式下主按钮：空闲有字幕=发送（立即发，不等停顿）；忙=■ | 无（语音模式与主按钮无关） | 需要「立即提交当前字幕」的控制器接口 | 行为 |
| 执行中语音：停顿后**作为补充**发出，主按钮保持 ■，提示「停顿后补充到当前任务」 | 排队到任务结束（`VoiceSessionManager.kt:206-209`），`sendVoiceMessage` busy 拒绝（`AgentAppState.kt:1048`） | 改为走 steering | 行为（核心） |
| 发送后输入框立即回到聆听，不显示「思考中」 | `channel` 有 Thinking 态并显示在状态条 | UI 映射：Thinking 不单独展示 | UI |
| 连接中最多等 5 秒，超时提示重试 | 未核实超时策略（控制器内部） | 待查 | — |
| 执行中再发的话（打字/语音输入/语音对话）→ 补充当前任务，下一步生效；多句依次采纳；暂停时发出的补充先记下 | 打字=排队新一轮（`queuedTextSubmission`）；语音=排队；悬浮窗「补充」=steering | App 内新增 `MSG_STEER` IPC → `AgentRuntimeService.requestSupplement` 同一路径；删除或降级 `queuedTextSubmission` | 行为（核心） |
| 补充在时间线显示「你的补充」+「下一步生效 → 已采纳」 | 显示为用户气泡；无采纳事件 | 新增 `AgentEvent.UserSupplementApplied(index)`（在 `AgentLoop.appendPendingSteering*` 处发出），UI 模型新增 Supplement 步骤类型 | 行为（事件 + UI 模型 + 持久化） |
| 已暂停：主按钮 ▶ 继续；卡片底部「结束任务」 | App 不知道暂停状态；暂停只能在悬浮窗里 | IPC 回传运行状态 + `MSG_RESUME`；结束任务 = 现有 `stopCurrentRun` | 行为 |

### 4.6 数据缺口汇总（多个 UI 项依赖）

1. 每步开始/结束时间 → 步骤耗时、执行卡计时、「用时 N 秒」、TaskBar 计时。需 `ToolStarted/ToolFinished` 带时间（或 App 收到事件时打点）并持久化（Room 迁移 `ConversationMessageEntity`）。
2. 运行级状态（运行中/暂停/完成/失败）与 `startedAt/endedAt` → 主按钮 ▶、底部栏、摘要条。
3. 正在操作的 App（包名/名称）→ 底部栏「正在『X』中操作」。目前只在 `launch_app` 结果文字里。
4. 补充的「已采纳」事件。
5. 语音电平（音量条、麦克风脉动）。
6. 执行详情页路由 `AppRoute.RunDetail(runId)`（`AppRoute.kt:7` 目前无）。

---

## 5. 需要保持通过的单测（`app/src/test/kotlin/io/github/mangi/eta/`）

| 测试文件 | 断言内容 | 与改造的关系 |
|---|---|---|
| `ui/components/AgentChatFinalResultTest.kt` | `resolveFinalResultMessageIds`：每轮只有最后一条回答是最终结果；流式中当前轮不标记；中断轮保留前一条（7 例） | 消息操作行的显示条件，改「更多」菜单时要保持 |
| `ui/components/AgentChatScrollPolicyTest.kt` | 跟底策略：完成后等渲染收尾、拖动打断、不把读历史的人拉回底部、只滚动溢出距离、哨兵离开视口才 requestIndex 等（13 例） | 改列表 / 加 TaskBar 时不能改动这些纯函数的语义 |
| `ui/components/AgentConversationDraftStoreTest.kt` | 草稿按会话隔离，跨宿主（App/浮层）保留文本与选区，删除会话不复活旧草稿 | 听写写入草稿时须走同一 Store |
| `ui/components/ChatConversationKeyTest.kt` | 会话切换时 composition key 变化 | — |
| `ui/components/InputPopupPositionProviderTest.kt` | 输入框弹层（思考/模型/附件）定位在输入框上方并夹在安全区内 | 改输入框尺寸后仍需满足 |
| `ui/components/SmoothTextRevealCoordinatorTest.kt`、`SmoothTextRevealPolicyTest.kt` | 显现时钟跨块推进、字素边界（emoji/组合字符）、追赶速度、Markdown 批次大小、块间距、流式列表标记 | 若改「按句追加」会直接冲击这批断言 |
| `ui/components/StreamingMarkdownParsingTest.kt`、`StreamingMarkdownRestoreStateTest.kt` | 流式解析合并、过期快照不发布、后台恢复基线 | 同上 |
| `ui/app/AgentRunMessageProjectorTest.kt` | 按 round/toolCallId 投影思考与工具、重放不累积、失败重试分离、中断工具变 Unknown、交错顺序、压缩结果合并、重放只清除可重建的补充（13 例） | 加时间戳 / Supplement 步骤 / 分组改动都在这里 |
| `ui/app/AgentPendingResultRecoveryTest.kt` | 恢复时续跑 prompt 与补充的位置、只提交一次、不覆盖失败/重试通知（11 例，含 `missingSupplementPrecedesAlreadyCompletedAnswer`） | 补充从用户气泡改为步骤行时必须同步修改 `mergeSupplements` 与本测试 |
| `ui/app/AgentRuntimeHistoryReducerTest.kt` | 快照保留已接收未消费的 steering、恢复后只提交一次（5 例） | steering 走 App 后仍须成立 |
| `ui/app/RoleplayConversationReducerTest.kt` | 角色会话编辑/候选/`interruptedPendingSupplementRemainsEditableAfterRecovery` | 角色相关 UI 保留 |
| `ui/app/AgentConversationRevisionReducerTest.kt` | 编辑/删除边界、可见消息截断 | 编辑态 UI 保留 |
| `ui/app/AgentConversationStoreTest.kt` | 保存/加载会话、系统通知语义码、不截断、空会话不入库（11 例） | 加字段需迁移且保证旧数据可读 |
| `ui/app/AgentRunEventCoalescerTest.kt` | 同块增量合并、换块先 flush、多 run 独立 | 新事件类型需走同一合并器 |
| `ui/model/AgentModelPickerProjectorTest.kt` | 模型列表投影、默认展开当前服务商、`latestContextUsage`、用量进度/格式化 | 上下文用量表视觉改动不应改算法 |
| `ui/model/AgentCompactionUiTest.kt` | 手动压缩仅在空闲且有已完成历史时可用；压缩估算替换旧用量 | 用量按钮里的压缩入口保留 |
| `agent/voice/session/VoiceSessionLifecycleTest.kt` | 语音会话生命周期；**`busyTextSubmissionIsQueuedAndEditRestoresDraftAndAttachments`、`withdrawingQueuedTextDoesNotCancelTheRunningTask`、`queuedTextDrainsToItsOriginalConversationAfterTheRunCompletes`、`endRevokesQueuedDispatchBeforeAsynchronousAudioClose`、`cancellingTaskAlsoRevokesVoiceWaitingBehindTheCurrentTextRun`** 直接断言「执行中排队」语义；另有拒绝恢复附件、结束原因提示等（20 例） | **改为 steering 后这 5 例预期要改写**，其余需保持 |
| `agent/voice/VoiceSessionUiStateTest.kt` | 控制器事件 → 通道唯一映射，active/speaking 派生 | 语音模式 UI 依赖此映射 |
| `agent/runtime/AgentRunControllerTest.kt` | steering 逐条排队不取消资源、结束前封口、取消清空、暂停阻塞直到继续、**steering 不恢复已暂停的 run** | App steering 直接复用，须保持 |
| `agent/runtime/AgentRuntimeSessionTest.kt` | 压缩不接收任务指令、重放边界、终态只发一次等（11 例） | 新增 `MSG_STEER` 路径要满足 |
| `agent/runtime/AgentRuntimeWireTest.kt` | IPC 编解码，`replyRewriteDoesNotAcceptSteering` 等（24 例） | 新增 wire 消息要补测 |
| `agent/model/AgentModelClientLoopTest.kt` | `steeringWaitsForWholeToolBatchWithoutCancellingResources`、`steeringAfterTextResponsePreservesThatAssistantTurn` | 「下一步生效」语义来源 |
| `agent/runtime/AgentContinuationBuilderTest.kt`、`agent/model/AgentRoleplayRuntimeTest.kt`、`agent/runtime/AgentConversationHandoffTest.kt`、`agent/runtime/AgentDiagnosticEventsTest.kt` | 续跑与补充 ID 保持、handoff 中补充、诊断事件 | 补充 ID 规则 `user-$runId-supplement-$index` 不能变 |
| `agent/overlay/AgentOverlayVisibilityPolicyTest.kt` | 悬浮窗可见策略 | 悬浮窗补充路径不能被 App 改动影响 |
| `ui/navigation/AppRouteSerializationTest.kt` | 所有路由可序列化往返 | 新增 `RunDetail` 路由须加入 |
| `ui/screens/diagnostics/DiagnosticsFormatTest.kt` | 运行日志格式、`STALL_MS` 等 | 卡住提示依赖 |

仓库内**没有** Compose UI 测试 / androidTest 覆盖首页与对话页，界面层回归只能靠真机（小米）验证。
