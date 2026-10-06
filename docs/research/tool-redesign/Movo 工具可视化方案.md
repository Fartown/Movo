# Movo 工具可视化方案（待 review）

日期：2026-10-06
范围：对话里执行卡的每一步（工具调用）怎么展示；运行日志页复用同一份数据。

## 1. 现状（代码核实）

| 层 | 现在的做法 | 问题 |
|---|---|---|
| 工具输出 | 每个工具有类型化输出 `O`（字段见附录），只投影给模型（`renderForModel`） | 给界面的投影 `renderForUi` 是空占位（返回 `{}`），从没被调用 |
| 运行事件 | `ToolStarted` 只带一句参数摘要；`ToolFinished` 只带一句结果摘要字符串、图片数、成败 | 结构化结果在运行时就丢了，界面拿不到 |
| 步骤标题 | `AgentTraceFormatter.summarizeArguments`：48 个工具里只有 13 个带上一个参数（滚动方向、输入字数、应用名、网页域名、搜索词、记忆、监听名、通知标题），终端另有命令块，其余只有固定动词（「点击屏幕」「查看屏幕」） | 看不出点了哪、看到了什么；大量分支还是旧工具名（tap_element、run_command…），已是死代码 |
| 步骤第二行 | 结果摘要第一行：多数是「完成」「完成·N 条」，打开类是「已打开·X」，失败写原因 | 成功时几乎没有信息 |
| 展开 | 终端：命令块 + 输出开头；浏览器：实时页面预览；其余：再显示一遍同一句摘要 | 展开基本没有新信息 |
| 截图 | `ui_observe` 截的图只发给模型，界面只显示「1 张图片」 | 看不到 Movo 看到的画面 |
| 调用记录 | 实施方案 §7 的 `ToolCallRecord`（耗时、执行方式、是否回读证实、确认等待…）没实现 | 运行日志页也只能显示摘要 |

结论：工具重构只做了「给模型的投影」，「给界面的投影」一直没接。

## 2. 参考

- Claude Code：每一步一行「动作(对象)」，下面一行结果（「Read 120 lines」「Found 3 files」），输出默认折叠前几行、可展开；改文件显示 diff；写文件显示内容开头。
- Codex：命令 + 输出折叠块；改动显示 diff。
- 手机 Agent（豆包、AutoGLM）：步骤写「点击「xxx」」，配当前屏幕画面。

共同点：**标题写清对象，结果写一个关键数字或状态，原文默认折叠，改动类给前后对比。**

## 3. 分三级

| 级别 | 什么时候用 | 收起时 | 展开后 |
|---|---|---|---|
| A 额外透出 | 结果本身就是用户要的东西，或改了东西、用户需要核对 | 标题（动作+对象）+ 一个关键结果 | 结构化内容：列表 / 键值 / 输出块 / 前后对比 / 图片 / 内容预览 |
| B 原本信息 | 动作本身一句话说得清 | 标题（动作+对象）+ 结果状态 | 参数原文与结果原文（人话格式，不是 JSON） |
| C 不透出 | 模型内部的元操作，对用户没意义 | 不单独成行，并入相邻步骤或只在运行日志里出现 | — |

机密内容（验证码、Wi‑Fi 密码、密码框输入）任何一级都不显示原文，只写「已读取 N 个验证码」「在密码框输入 8 个字符」。

## 4. 逐个工具（48 个）

### A 额外透出

| 工具 | 标题 | 收起时的结果 | 展开 |
|---|---|---|---|
| ui_observe | 查看屏幕 ·「设置」 | 28 个元素 · 1 张截图 | 截图缩略图（点开看大图）；没截图时列出看到的主要文字 |
| terminal_run | 运行命令（命令首行） | 退出码 0 · 输出 12 行 | 命令原文 + 输出（等宽、默认 8 行、可展开全部），stderr 分开 |
| terminal_job | 后台命令 · 查看 / 停止 | 运行中 / 退出码 N | 同上 |
| file_read | 读取文件 · 文件名 | 120 行 / 1 张图片 | 文本前若干行；图片缩略图；路径 |
| file_write | 写入文件 · 文件名 | 新建 · 2.1 KB / 追加 · 3 行 | 写入内容预览（追加只显示新增部分）；路径 |
| memory_read | 读取记忆 | 12 行 | 读到的记忆内容 |
| memory_write | 记住 / 修改记忆 / 清空记忆 | 新增 1 行 | 「记住：…」或「原文 → 新文」对比；可撤销提示 |
| personal_search | 搜索短信 / 通讯录 / 日程…（来源 + 关键词） | 找到 5 条 | 条目列表（标题 + 时间）；正文里的验证码打码 |
| browser_read | 读取网页 · 标题 | 3,200 字 / N 个元素 | 页面标题与地址、正文开头；有截图时显示缩略图 |
| browser_open | 打开网页 · 域名 | 《页面标题》 | 实时页面预览（已有）+ 地址 |
| mcp_call | 调用 MCP · 服务 / 工具 | 完成 / 已送达 | 参数原文（人话键值）+ 返回内容开头；图片缩略图 |
| app_search | 搜索应用 · 关键词 | 找到 3 个 | 应用列表（图标 + 名称） |
| file_list / file_search | 列出目录 · 路径 / 搜索文件 · 关键词 | 24 项 | 文件列表（名称、大小、时间） |
| clock_read | 查看闹钟 / 计时器 | 3 个闹钟 | 列表（时间、重复、开关） |
| usage_read | 查看应用使用时长 | 今天 3 小时 12 分 | 前几名应用列表 |
| health_read | 查看健康数据 | 今天 6,532 步 | 按天的数值 |
| device_read | 查看设备状态 | 电量 80% · Wi‑Fi | 读到的各项键值（电量、网络、存储、位置…） |
| device_diagnostics | 读取诊断信息 · logcat / 进程 | 200 行 | 输出块（等宽、折叠） |
| skill_install | 安装技能 · 仓库 | 已装 2 个 | 装上的技能列表；查看仓库时列出可选目录 |
| conversation_read | 读取会话历史 | 第 1–20 条 | 读到的消息范围（只列条目，不重复正文） |

### B 原本信息（一句话 + 展开看原文）

| 工具 | 标题 | 结果 |
|---|---|---|
| ui_tap | 点按「搜索系统设置项」（读不到文字时写「点按屏幕 (x, y)」） | 已送达 / 已确认；长按写「长按」 |
| ui_input | 输入「蓝牙」（密码框：在密码框输入 8 个字符） | 已写入 · 回读一致 / 已送达；带提交写「并提交」 |
| ui_swipe | 滑动 · 方向 | 已送达 |
| ui_scroll | 向下滚动 | 已滚动 / 到底了 |
| ui_key | 按「返回」 | 已送达 |
| ui_wait | 等待「xx」出现 / 等 2 秒 | 出现了 · 1.2 秒 / 超时 |
| browser_act | 网页上点按「登录」/ 输入 / 选择 | 已送达 · 跳到《页面标题》；提交前的确认沿用审批卡 |
| app_open | 打开应用 · 名称 | 已打开（前台确认） |
| app_control | 停止 / 冻结 / 解冻 · 应用 | 已停止（回读） |
| setting_read | 读取设置 · 项 | 值 |
| setting_write | 修改设置 · 项 | 原值 → 新值 |
| device_toggle | 打开 / 关闭 Wi‑Fi | 已打开（回读） |
| volume_set | 音量 · 媒体 | 40% → 70% |
| media_control | 播放 / 暂停 / 下一首 | 当前：歌名 |
| clock_create | 新建闹钟 / 计时器 · 时间 | 已创建（回读） |
| clipboard_write | 复制到剪贴板 | 12 个字 |
| clipboard_read | 读取剪贴板 | 12 个字（标了敏感的只写字数） |
| sms_code_read | 读取验证码 | 找到 1 个（不显示验证码） |
| wifi_password_read | 读取 Wi‑Fi 密码 · 网络名 | 已读取（不显示密码） |
| notify_user | 发送通知 · 标题 | 已发送 |
| skill_read | 读取技能 · 名称 | 已读取 |
| mcp_find | 查找 MCP 工具 · 关键词 | 找到 3 个 |
| monitor_start / monitor_stop / monitor_list | 已有专用呈现（定稿 18），保持不变 | — |
| ask_user | 已有提问卡（定稿 16），步骤里只写「问了你」+ 你的回答 | — |

### C 不透出

| 工具 | 处理 |
|---|---|
| tool_search | 模型给自己加载工具，不显示；运行日志里保留 |
| （同一屏连续的 ui_observe） | 紧挨着、没有动作的重复观察合并成一步「查看屏幕 ×2」 |

## 5. 怎么做

### 5.1 数据通路（不动界面外观，可先做）

1. 新增结构化界面视图 `ToolUiView`（替换空的 `renderForUi`）：几种块——键值、列表、输出块、内容预览、前后对比、图片引用、链接。每个工具从自己的输出 `O` 派生，不另写一份（合同 §1「模型投影和 UI 投影都从 `O` 派生」）。
2. 新增 `uiTitle(input)`：动作 + 对象。界面操作复用审批卡已有的「点按「转账」」「输入「明天见」」逻辑（`buildUiActionResolution` 的 `action`），两处说法一致。
3. `ToolStarted` 带标题，`ToolFinished` 带 `ToolUiView`、耗时、是否回读证实、执行方式、确认等待（实施方案 §7 `ToolCallRecord` 里界面用到的部分）。
4. 大小上限：单步视图 ≤ 16 KB，列表 ≤ 20 项、输出 ≤ 200 行，超出写「还有 N 项」；截图只存缩略图。
5. 敏感度：SECRET 的视图只留「已读取 N 个」；PRIVATE 的视图见第 6 节决策。
6. 删掉 `AgentTraceFormatter` 里旧工具名的死分支，标题与结果都改从新视图生成；运行日志页、导出 Markdown 用同一份。

### 5.2 Figma（先出候选，你定稿后再写界面）

执行卡里一步的几种形态：一行（B 级）、键值、列表、输出块、前后对比、截图缩略图、内容预览；失败、未确认、等待确认三种状态；长输出的折叠和「还有 N 项」。放「候选 · 工具步骤可视化」页，做成带编号的决策稿。

### 5.3 界面实现与验证

按定稿改执行卡与运行日志页；单测覆盖每个工具的标题与视图；小米真机跑一遍典型任务（设置里搜索、终端、读文件、写记忆、搜短信、浏览器），逐步截图核对。

## 6. 要你定的事

1. **截图留不留在对话记录里**：A）只在本次运行中显示，重启后只剩「看了屏幕」；B）保留缩略图（约 30 KB/张），原图不留。截图里可能有别的应用的内容（银行、聊天），我倾向 A。
2. **个人数据结果能不能展开看条目**（短信、通讯录、日程、通知）：A）能看标题和时间，正文不显示；B）只显示条数。数据本来就在你手机上，我倾向 A。验证码、密码类无论如何只显示「已读取」。
3. **tool_search 是否完全不显示**：我倾向不显示，运行日志里保留。

## 附录：各工具的结构化输出字段（代码里已有）

MonitorStart: info, requestedTimeoutMs, maxTimeoutMs · MonitorStop: name, eventCount · MonitorList: monitors · NotifyUser: posted ·
BrowserAct: result · BrowserOpen: page · BrowserRead: data, screenshot · ClockCreate: type, verified, matchedTriggerAtMs · ClockRead: items ·
MediaControl: action, session · VolumeSet: stream, requestedPercent, actualPercent, clamped · ConversationRead: totalMessages, entries, hasMore ·
AppControl: packageName, action, state · AppOpen: targetPackage, foregroundPackage, foreground · AppSearch: apps ·
DeviceDiagnostics: kind, data, truncated · DeviceRead: data, failed · DeviceToggle: target, enabled · SettingRead: namespace, values ·
SettingWrite: namespace, key, previous, value · FileList: path, entries · FileRead: data, imageAttached · FileSearch: items, total ·
FileWrite: path, bytesWritten, created · McpTool: server, tool, content, structured, truncated, images · McpFind: matches, total ·
MemoryRead: content, lineCount, matchedLines · MemoryWrite: mode, before/afterRevision, byteSize, lineCount · HealthRead: days, summary ·
PersonalSearch: source, items · SmsCodeRead: codes · UsageRead: view, items · WifiPasswordRead: items · SkillInstall: action, discover, installed ·
SkillRead: skill, content, files · TerminalJob / TerminalRun: textBody · ClipboardRead: text, truncated, sensitive · ClipboardWrite: chars ·
UiInput: method, textVerified, readbackLength, submitted · UiObserve: observed · UiScroll: moved, atBoundary · UiWait: matched, elapsedMs, node。
ui_tap / ui_swipe / ui_key 输出是送达结果与前后前台应用。

## 7. 进度（2026-10-06）

决策：§6 三项都按 A（截图只在本次运行中显示；个人数据展开只看标题和时间；tool_search 不显示）。

**数据层已完成（分支 feat/tool-visualization，未提交）**
- `ToolUiView` / `ToolUiBlock`（键值、列表、输出、内容预览、前后对比、图片）与上限；`ToolContract.uiTitle` / `renderForUi`；`ToolExecutor.stepTitle`。
- 43 个工具有标题与视图（另有动态的 MCP 直连工具）；monitor_start / monitor_stop / monitor_list、notify_user 沿用已有专用标题（定稿 18）；tool_search 不进执行卡。
- 标题会存进对话记录：密码框输入、网页输入只写字数；记忆、验证码、密码不进标题。
- 敏感度：SECRET 只留摘要；带图的视图和个人数据（短信、通讯录、日程、通知、使用时长、位置、剪贴板、屏幕文字、系统日志，由工具自己标）为临时，发给界面但不进运行归档、不存数据库（DB v23 新列 `tool_view_json` 只存非临时视图）；截图缩略图只在进程内（`ToolStepImages`）。最初按 PRIVATE 一刀切，真机 V9 发现终端输出、记忆重启后展不开，10-06 改为工具自己标。
- 单测：核心视图 6 条，各领域标题 / 视图 10 条，对话记录与执行卡投影 2 条，数据库迁移 1 条；全量 1634 个通过。

**暂缓**：`AgentTraceFormatter` 里旧工具名的分支只剩旧数据与监听类工具会走到，对界面没有影响；删除要重写十多条以旧工具名做隐私脱敏的测试，留待单独清理。

**Figma 定稿 20**（第一页「20 工具步骤可视化」）：决策 1 在步骤下方原地展开；决策 2 截图只在展开后出现。候选页已改名「候选 · 工具步骤可视化（已定稿…）」。

**界面已完成（同分支，未提交）**
- 每一步默认折叠，有详情才可点；同一张执行卡同时只展开一步（`LocalWorkStepExpansion`）。没有详情的步骤（只有一行摘要、验证码等机密结果）点了不展开。
- `ToolStepBlocks` 画 6 种块：截图（120dp 缩略图，点开全屏）、列表（超 20 项写「还有 N 项」）、输出（等宽 12/17，长行折行，超 8 行「展开全部」）、键值、前后对比、内容预览；终端命令仍用命令块。
- 重启后截图已不在内存里：展开显示「截图只在当时显示，重启后不再保留」。
- 设计稿截图 20-01～20-10（`DesignShotsTest.toolSteps`）逐张对过定稿；全量单测 1634 个通过；release arm64 包 29.9MB。
- 与定稿的已知差异：失败步骤第二行沿用现有写法，去掉「失败」前缀只写原因（如「退出码 1」，红色 ✕ 已表示失败），定稿稿面写的是「失败·退出码 1」。
- 真机验证（小米 24129PN74C，Android 15，`.docs/tool-visualization-1006/`）：
  - 第一轮：包里漏了默认模型 Key，作废后重打包重跑，结果见 `device-report.md`。通过的项：默认折叠、只开一步、截图缩略图与全屏、终端长行折行与「展开全部」、写文件、记忆、应用列表、失败步骤。V8 因没有短信权限跳过。V6 亮度那条模型改走终端失败、再用手势调，是模型选工具的问题，与可视化无关。V9 发现终端输出、记忆重启后展不开。
  - 第二轮：修 V9 后复测 R1–R6 全部通过，见 `recheck-report.md`。重启后终端输出、记忆照常展开；截图显示占位文字；屏幕文字只剩摘要。
  - 另记一个原有问题：模型在工具之间插话时，一次任务会拆成多张执行卡，每张卡头都显示整次任务的总用时和起止时间。本次没改。
