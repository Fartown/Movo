# 改版 E：运行日志（Figma 22–27）

- 日期：2026-09-25；分支 `feat/design-v1-restore`
- 依据：`docs/DESIGN_SYSTEM.md` §8.10 / §8.8 / §2 / §10.1、`docs/research/log-page/运行日志功能定义.md`、`.docs/design-restore/inventory-drawer-overlay-runlog.md` C 章；Figma `RDCD9OcgkFDkD45kyxtIoM` 画框 22 `235:4433`、23 `237:4535`、24 `237:4973`、25 `239:5219`、26 `240:4788`、27 `240:4893`、交互说明 `241:4943`（只读）
- 改动文件（都在 `app/src/main/kotlin/io/github/mangi/eta/ui/screens/diagnostics/`）：
  - `DiagnosticsScreen.kt`：三个页面改成 `MovoListPage`，卡内 `CardTitle`，说明写进 `CardFooter`，列表行用 `SettingsRow`
  - `DiagnosticsComponents.kt`：去掉自绘的 `LogPage` / `TopBarIcon` / `PageDescription` / `SectionLabel` / `CardNote` / `LogCard` / `LogRow` / `LogPill`；筛选芯片、结论卡、原因卡、耗时卡、`Work/Step`、`Run/StepDetail` 按定稿重写；图标换成 `MovoIcons`
  - `DiagnosticsFormat.kt`：「·」去掉两边空格；时长规则；重试行文案；卡住提示拆成 `stallHint` / `liveNote`；新增 `chatFailure()`（给 26 用）
  - `DiagnosticsData.kt`：导出菜单改成 `Popover/Menu`；新增 `rememberRunFailure(messageId)`（给 26 用）
  - 测试：`app/src/test/kotlin/io/github/mangi/eta/ui/screens/diagnostics/DiagnosticsFormatTest.kt`
- 没有改：`ui/components/movo/*`、路由、`AgentAppRoot` / `AgentAppShell`、`diagnostics/` 数据层、对话页组件、`strings_movo.xml`（原因见第 4 节）

## 1. 改前功能清单 → 改后位置（逐条对照）

| # | 改前（元素 / 交互） | 改后 | 状态 |
|---|---|---|---|
| 1 | 路由 `Diagnostics` / `DiagnosticsRun(runId)` / `DiagnosticsSystem`；入口：设置、对话 `LocalRunLogOpener` | 三个 Composable 的签名没变，`AgentAppRoot` 里的挂载代码不用改 | 保留 |
| 2 | 自绘 56 顶栏 + 返回 | `MovoListPage`：居中标题、返回（chevron-left）、Q7 滚动态、边距 20、顶栏到第一张卡 12、卡片间距 16 | 保留，换组件 |
| 3 | 顶栏「导出」→ 下拉菜单：「导出为 Markdown 文件」（`CreateDocument`）/「复制到剪贴板」 | `MovoIconButton(MovoIcons.Download)` → `Popover/Menu`（圆角 20、内边距 8、项高 44、圆角 12、E3，从锚点缩放 0.96 → 1 并淡入 `fast`，退场淡出 120ms），两项的行为、文件名、导出内容都不变 | 保留 |
| 4 | 导出结果 Toast「已导出 / 导出失败 / 已复制」 | 没改（见第 3 节） | 保留 |
| 5 | 每秒轮询 `MemoryDiagnostics`，记录没变就不重建视图 | 没改 | 保留 |
| 6 | 会话标题（本机显示，导出不带） | 没改 | 保留 |
| 7 | 22 顶部说明段落 | 删除；「保存最近 20 个任务，不含对话内容。」移到「任务之外」卡的页脚；第一句「每次任务的模型请求…」按规范不再显示 | 按规范改 |
| 8 | 22 筛选芯片：全部 / 失败 N / 进行中 N；计数为 0 的不显示；正在看的分类清空后回到「全部」；失败 N 包含 FAILED 和 INTERRUPTED | 逻辑没改；位置改为顶栏下 12；样式按 `Chip/Filter`（40 高、圆角 20、描边 1 border/strong、选中 Indigo 浅底），`movoClickable(Solid)`，读屏角色 RadioButton + selected | 保留 |
| 9 | 22 空状态「还没有任务记录…」 | 放进一张卡片里（卡片外不放文字），两句分两行 | 保留 |
| 10 | 22 按天分组（卡外标题） | 按天一张卡片，卡内 `CardTitle`「今天 / 昨天 / M月d日」 | 保留，换位置 |
| 11 | 22 任务行：状态图标、标题、结论、右侧用时 / 计时、箭头，点击 → 任务详情 | `SettingsRow`（icon=true，68 高，分隔线从 48 开始，最后一行不画）；`RowLeading.Custom` 放状态图标 20；`RowTrailing.Arrow(用时)`；点击 `onOpenRun(run.id)` | 保留 |
| 12 | 行结论：开始时刻 + 进行中写步骤与已等时长 / 失败写原因 / 已停止 / 成功写 N 次请求·M 次工具 | 逻辑没改；「·」去掉空格（Figma 22 保留开头的时刻，这里跟着 Figma，没有去掉） | 保留 |
| 13 | 状态图标：进行中加载圈 / 完成 ✓ / 失败 ✕ / 已停止 ■ / 中断 ⓘ，带读屏文字 | `MovoSpinner` / `Check` Green / `X` Rose / `Square` 次要色 / `CircleAlert` Amber；读屏文字不变 | 保留 |
| 14 | 22「任务之外」卡外标题 + 「系统事件」行（N 条）→ 系统事件页 | 卡内 `CardTitle`「任务之外」+ `SettingsRow`（file-text 图标、说明「进程启动、网络变化、后台服务」、值「N 条」+ 箭头）+ 页脚 | 保留 |
| 15 | 23/24 标题 = 会话标题，没有时写「任务 R12」；加载中「任务详情」 | 没改 | 保留 |
| 16 | 23/24 单任务导出（只含本任务 + 本任务原始事件） | 没改 | 保留 |
| 17 | 任务不存在：「这个任务的记录已经不在了…」 | 放进卡片，两句分两行 | 保留 |
| 18 | 结论卡：状态图标 16 + 状态 + 计时；第二行「开始·结束·R 编号」 | `Run/Summary`：图标到文字 12、计时 `Numeric/Label`、第二行对齐卡内 44；进行中的状态图标换成 16 的小光球（本页私有实现，减少动画时静止） | 保留 |
| 19 | 结论卡底部栏：进行中一直显示（提示 +「回到对话」） | **只在进行中且 30 秒没收到数据时显示**（规范 8.10）：「已 N 秒没有收到数据，网络可能不稳定」+「回到对话」（有会话 ID 才出按钮，点了选中会话并回到首页，同原逻辑） | 按规范改 |
| 20 | 底部栏里没卡住时的实时状态（「N 秒前收到过数据，一切正常」/「正在收到数据」/ 执行工具时「步骤，已 N 秒」） | 移到「时间线」卡标题右侧（`CardTitle` 的右侧补充，三级色）：「N 秒前收到过数据」/「正在收到数据」/「执行工具·xx·已 N 秒」；卡住时这里不显示，由底部栏说明 | 保留，换位置 |
| 21 | 原因卡（卡外标题）：原因标题、说明、建议 + 按钮「去模型设置」（→ `ModelProviders`）/「去后台设置」（→ 系统应用详情页） | 卡内 `CardTitle`「原因」；间距按 Figma（说明到建议 16）；按钮换 `MovoPillButton`，两个动作不变 | 保留 |
| 22 | 耗时卡（结束后）：堆叠条 + 图例，最长一段加粗 | 卡内 `CardTitle`「耗时」；最长一段 `Label/Medium` 主色（不是加粗，字重只用 Regular / Medium）；数字全部 `Numeric/Label`；上下文压缩改为 Graphite（Figma 组件说明） | 保留 |
| 23 | 时间线：请求 / 工具 / 上下文压缩 / 设备事件；连接线；请求行点击展开「网络阶段」，同时只展开一行；默认展开最后一次失败的请求或进行中的请求；网络阶段文字可选择复制 | 卡内 `CardTitle`「时间线」；`Work/Step`（标题与说明间距 2、时长三级色）；展开块 `Run/StepDetail`（圆角 12、内边距 12、标签 `Label/Medium`，上方 10）；展开规则、默认展开、`SelectionContainer` 都不变；行点击改用 `movoClickable(Row)` | 保留 |
| 24 | 时间线为空：「还没有记录到请求」 | 在时间线卡里，不变 | 保留 |
| 25 | 时间线页脚（卡外、三级色、两句合成一句） | 卡内 `CardFooter`，一句一行：失败 / 结束「只记录请求阶段、工具名和设备状态。」「不含对话内容、工具结果和 API Key。」；进行中「进行中的任务每秒刷新。」「结束后这里会写明结果和耗时构成。」 | 按规范改 |
| 26 | 25 系统事件：顶部说明；按天分组（卡外标题）；每行图标 + 标题 + 说明 + 右侧时刻，没有连接线；页脚「系统事件保留最近 7 天。」 | 顶部说明删除；按天一张卡、卡内标题；行不变；最后一张卡片页脚两句：「任务中发生的事件，也记在该任务的时间线里。」「系统事件保留最近 7 天。」；没有事件时，空状态和页脚放在同一张卡里 | 按规范改 |
| 27 | 设备事件图标（Material） | `Power` / `Smartphone` / `Globe`（网络，同 Figma）/ `ShieldAlert`（服务被拒）/ `Cpu` / `CircleAlert`；颜色按 tone：普通为次要色，警告 Amber，错误 Rose | 保留，换图标 |
| 28 | 导出 Markdown 内容：设备与版本、每个任务的结论 / 原因 / 建议 / 耗时 / 时间线 / 网络阶段、系统事件、原始事件；过滤 `conversation=` 和 `wire_run=`，不带会话标题 | 内容与过滤规则不变，只是「·」去掉空格、时长按新规则写 | 保留 |
| 29 | 对外 API：`LocalRunLogOpener`、`DiagnosticsLinks.runForMessage`、`DiagnosticsFormat.STALL_MS`（`RunStallNotice`、`ChatMessageItem` 在用） | 没改 | 保留 |

## 2. 与 Figma 的差异

### 有意的差异

| 位置 | Figma | 实现 | 原因 |
|---|---|---|---|
| 时间线 / 耗时里小于 1 秒的时长 | 「0.4s」「0.6s」 | 「400ms」「600ms」 | 规范 8.10 和任务要求「小于 1 秒写毫秒」；以规范为准 |
| 重试等待 | 「3s 后重试」 | 「3.0s 后重试」（小于 1 秒写「500ms 后重试」） | 同上，一位小数的秒；原来是整数秒截断（500ms 会显示成「0s」） |
| 22 行结论开头的时刻 | 保留「15:10·…」 | 保留 | 盘点 C.2 建议去掉，但定稿 22 有，跟着定稿 |
| 26 用时 | 「用时 18 秒」 | `chatFailure` 给出「用时 18.4 秒」 | 按统一的时长规则 |
| 24 没卡住时 | 结论卡没有底部栏 | 同 Figma；另外在「时间线」卡标题右侧补一个三级色的实时状态 | 功能定义 Q3 要求能看到「多久没收到数据」，底部栏去掉后这条信息要有地方显示；用的是 `Card/Title` 规范允许的右侧补充 |
| 耗时卡「准备与本地处理」 | 没有这一段 | 保留（三级色） | 数据层有这一段，去掉会让各段之和对不上总用时 |
| 网络阶段第三行 | 「接口：codex/responses」 | 「服务：openai_responses·收到 12.3 KB」 | 数据层记录的是服务商 ID 和接收大小，没有接口路径；文案没改 |
| 设备事件图标颜色 | 灰 | 普通事件 `text/secondary`（原来是三级色） | 与 Figma 的灰度更接近 |

### 暂时做不到 / 留给主流程

| 位置 | 情况 | 需要 |
|---|---|---|
| 27 菜单背后的 `overlay/scrim`（16%） | 没做。菜单用锚定在按钮上的 `Popup`，全屏遮罩要把 Popup 铺满窗口，位置换算在不同窗口 inset 下风险大，子任务又不能装机验证 | 以后公共组件里有 `Popover/Menu` 时再统一；点菜单外面仍然会关闭 |
| 结论卡进行中状态文字的 Q3 光带 | 没做（8.8 有，8.10 没写） | 以后公共组件有 shimmer 时再加 |
| 结论卡进行中的小光球 | 公共组件里没有 16 的小光球，本页私有实现 `MiniOrb`（品牌渐变 sweep，12 秒一圈，减少动画时静止） | 公共组件出 `Movo/Orb` 小尺寸后替换 |
| 列表行结论单行省略 | `SettingsRow` 的说明不限行数，很长的失败原因会换成两行（Figma 是一行省略） | 这是公共组件的行为，没改；需要的话在 `SettingsRow` 加 `subtitleMaxLines` |
| `MovoPillButton` 左右内边距 | 公共组件用 12，Figma `Button/Pill` 是 16 | 公共组件的事，没改 |
| 上下文压缩图标 | Lucide 里需要 `shrink`（或 `minimize-2`），图标表里没有 `shrink` | 暂用 `layers`；需要在 `gen-icons.mjs` 里加 `shrink` |

### 26「对话中·任务失败」需要主流程做的事（对话页组件不归本任务）

现状：`ui/components/ChatMessageItem.kt:2846-2860` 的 `RunLogLink` 只在 `RuntimeFailed` / `Interrupted` 系统通知下面放一个「查看日志」胶囊。

定稿 26：
1. 失败任务卡：Rose ✕ +「任务没有完成」+ 右侧「用时 18.4 秒」+ ›，整条点开 → `LocalRunLogOpener.current?.invoke(runId)`。
2. 下面一张原因卡：原因标题（`Body/Strong`）+ 说明与建议一句（`Label/Regular` 次要色）+ 两个 `MovoPillButton`：「重试」（接现有的重新生成回调 `onRegenerateMessage`）和「查看日志」（同上）。

本任务已准备好数据接口，对话侧直接用：

```kotlin
// ui/screens/diagnostics/DiagnosticsData.kt
@Composable internal fun rememberRunFailure(messageId: String): RunFailureLink?
internal data class RunFailureLink(val runId: String, val failure: ChatFailure)
// ChatFailure(title = "模型接口限流（HTTP 429）",
//             message = "连续 2 次请求都失败，自动重试已用完。稍后再试，或换一个模型。",
//             duration = "用时 3.0 秒", action = FailureAction.MODEL_SETTINGS)
```

返回 null（找不到任务，或任务没有失败 / 中断）时，退回现在的「查看日志」胶囊。对话浮层里 `LocalRunLogOpener` 为 null，入口仍然隐藏。另外 `RunStallNotice.kt:40-48` 的卡住提示文案里还是「 · 查看日志」（带空格），需要对话侧改成「·」。

## 3. 保留的 Toast

`DiagnosticsData.kt` 的「已导出 / 导出失败」（系统文件选择器回来后异步写入）和「已复制」。改成就地反馈需要在顶栏按钮上加状态（✓ / 失败）并处理失败提示，牵涉导出流程，这次没改。

## 4. 文案资源

运行日志整个模块（含 `DiagnosticsFormat` 这个纯 JVM 类和它的单测）都是中文硬编码，没有走资源。这次新增和移动的几句页脚都沿用这个做法，没有写进 `strings_movo.xml`：只把几句挪进资源会让英文 / 繁体环境下同一页半中半英。整个模块国际化需要单独做（`DiagnosticsFormat` 要改成注入文案）。

## 5. 测试断言变更（`DiagnosticsFormatTest`）

| 测试 | 改前 | 改后 | 原因 |
|---|---|---|---|
| `unitsFollowListAndTimelineConventions` | `duration(125_000) == "2 分 5 秒"` | `"125.0 秒"` | 大于等于 1 秒一律写一位小数的秒 |
| 同上 | — | 新增 `duration(1_000) == "1.0 秒"`、`compact(125_000) == "125.0s"`、`offset(125_000) == "+125.0s"` | 覆盖边界和原来的「分」分支 |
| `failedRunExplains…` | `listSubtitle(...).endsWith("模型接口限流（HTTP 429）")` | 整句 `"${clock(WALL)}·模型接口限流（HTTP 429）"` | 顺带锁定「·」无空格 |
| 同上 | `requestTitle == "模型请求 · 第 2 轮"` | `"模型请求·第 2 轮"` | 「·」无空格 |
| 同上 | `requestSubtitle == "+0s · HTTP 429 · 2s 后重试"` | `"+0s·HTTP 429·2.0s 后重试"` | 「·」无空格；重试等待用统一时长 |
| 同上 | `requestTitle(retry) == "模型请求 · 第 2 轮 · 重试第 1 次"` | `"第 2 轮·第 2 次尝试"` | 定稿 23 的写法 |
| 同上 | `statusLine == "失败 · 第 2 轮模型请求"` | `"失败·第 2 轮模型请求"` | 「·」无空格 |
| 同上 | `toolTitle == "工具 · 读取当前上下文"` | `"工具·读取当前上下文"` | 「·」无空格 |
| 同上 | — | 新增 `summaryTimer == "用时 3.0 秒"`、`summaryMeta == "<开始> 开始·<结束> 结束·R1"` | 结论卡第二行格式 |
| 同上 | — | 新增 `chatFailure` 的标题 / 说明 / 用时 / 动作 | 给 26 用的新接口 |
| 同上 | `markdown.contains("工具 · 读取当前上下文（get_current_context）")` | `"工具·读取当前上下文（get_current_context）"`；新增标题行 `"## R1·失败·第 2 轮模型请求·3.0 秒"`，并断言导出里不再有「 · 」 | 导出用同一套写法 |
| 新增 `runningFooterOnlyAfterThirtySecondsOfSilence` | — | `stallHint`：null / 29.999 秒为 null，38.4 秒为「已 38 秒没有收到数据，网络可能不稳定」；进行中 `statusLine`、`listSubtitle`、`listValue`；`liveNote` 三种情况；进行中 `chatFailure` 为 null | 规范 8.10 底部栏出现时机 |

## 6. 验证

- `./gradlew :app:compileDebugKotlin -q -Pkotlin.compiler.execution.strategy=in-process "-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8"`：通过（exit 0）。中途几次失败都出在别人正在改的文件（`AgentChatInputBar.kt`、`ChatMessageItem.kt`）或共享守护进程 OOM / KSP 缓存，重试后通过
- `./gradlew :app:testDebugUnitTest --tests 'io.github.mangi.eta.ui.screens.diagnostics.*' --tests 'io.github.mangi.eta.diagnostics.*'`（同上加参数）：通过。DiagnosticsFormatTest 3、DiagnosticTraceTest 5、DiagnosticBufferTest 5、RunProgressTest 1，共 14 个，0 失败
- 按要求没有装机；真机截图由主流程统一做。建议重点看：22 芯片与第一张卡的间距、27 菜单位置（右缘应落在 20 边距线、顶部贴顶栏底）、24 卡住 30 秒前后底部栏的出现与消失。
