# 改版 D · Linux / 终端相关二级页

依据：`.docs/design-restore/component-guide.md`、`docs/DESIGN_SYSTEM.md`（§2、§4、§8、§8.7、§8.11、§10.1）。
范围：`ui/screens/terminal/` 下 9 个文件，以及三份 `strings_movo.xml` 里追加的 4 条 `movo_*` 文案。
没改：存储 key、回调、路由、Store/Repository/Installer 调用、协程与前台执行逻辑。

## 0. 公共改动（`LinuxEnvironmentSections.kt`，终端包内共用）

| 组件 | 用途 |
|---|---|
| `TerminalSpinner` | Lucide `loader-circle` 匀速旋转（`MovoMotion.SPINNER_PERIOD`），减少动画时静止；替代 Miuix `InfiniteProgressIndicator`（终端内容区里的除外） |
| `LinuxChoiceDialog` | 与 `MovoChoiceDialog` 同一写法（`MovoDialogHost`、行按压、Indigo ✓、停留 160ms 再关闭），多了**每项说明**和**禁用项**，用来保留原 `WindowSpinnerPreference` 的 summary 与 `enabled` |
| `Modifier.movoCardSegment(first, last)` + `CardGap` | 分段卡片：一张卡拆成多个 LazyColumn item，外观和 `MovoCard` 一样（圆角 28、0.5 发丝线、下内边距 4）。Linux 文件目录可能有上千项，这样仍然是惰性加载。用到它的页面把 `MovoListPage.itemSpacing` 设为 0，卡片之间放 `CardGap()`（16） |
| `CardNotice(text, error)` | 卡内就地反馈：错误 = Rose `circle-alert` + 主色文字；普通结果 = ⓘ + 次要色文字 |
| `CardStateText` | 卡内状态说明（未安装、为空、读取失败），左右和上下各 16 |
| `TerminalEnvironmentChip`（在 `UserTerminalScreen.kt`） | 环境切换胶囊：高 32、圆角 16，选中 = Indigo 浅底 + Indigo Medium 文字 |
| `TerminalMaterialIconButton`（在 `UserTerminalScreen.kt`） | 缺 Lucide 图标时的过渡按钮：热区 44、按压态和 `MovoIconButton` 一致，里面放 Material 图标 |
| `TerminalDialogTitle` / `TerminalDialogEmpty`（在 `DaemonTasksDialog.kt`） | 终端对话框的标题与空状态（间距同 `MovoChoiceDialog`：左右 24、上 24） |

## 1. Linux 工具环境 `LinuxEnvironmentScreen` + `LinuxEnvironmentSections`

骨架：`MiuixScaffoldPage` → `MovoListPage`（居中顶栏，卡片之间 16，顶栏到第一张卡 12，页面左右 20）。卡外的 `SmallTitle` 都改成卡内 `CardTitle`。

| 改前功能 | 改后位置 |
|---|---|
| 状态卡：发行版 + 版本、运行方式 | 状态卡（`MovoCard`，内边距 16）：Graphite 图标底块（Terminal）+ 标题 `Title/Section` + 运行方式 `Label/Regular` 次要色 |
| 状态说明（需要 Root / Kimi 启动中 / 安装进度 / 工具已就绪 / 基础已就绪 / 未安装 + 条件说明） | 同一张卡的说明行，判断顺序和文案不变；「未安装」和条件说明按原来的 `\n` 分成两行 |
| 忙碌时转圈（`busyTarget != null` 或 Kimi 启动中） | 说明行前的 `TerminalSpinner` |
| 结果消息 `resultMessage`（安装成功或失败、Kimi 启动失败、前台服务不可用） | 卡内 `Label/Regular` 主色文字，就地显示（原来也不是 Toast） |
| 主按钮：去「Root 与系统增强」/ 安装中 / 安装基础工具 / 安装基础环境；工具就绪后隐藏；忙碌时禁用 | `MovoBlockButton`（48 主操作），文案、禁用条件和点击分支（`AppRoute.SystemEnhance` / `installTools` / `installBase`）都不变 |
| 「环境配置」分组标题 | 卡内 `CardTitle` |
| 发行版单选（Alpine / Debian，带说明，忙碌时禁用） | `SettingsRow` + `Arrow(当前值)` → `LinuxChoiceDialog`（带每项说明）；`enabled` 不变；选择回调的守卫条件不变 |
| 运行方式单选（有 Root 或当前是 chroot 时显示；chroot 项没有 Root 时禁用） | 同上，chroot 项在对话框里禁用（40% 不透明，点不了）；回调守卫不变 |
| 没有 Root 且不是 chroot：运行方式只读显示 | `SettingsRow` + `RowTrailing.Value(当前值)`，不能点 |
| 「文件与目录」分组标题 | 卡内 `CardTitle` |
| 工作区入口（总是显示） | `SettingsRow`（标题 + 说明 + 箭头）→ `AppRoute.Workspace` |
| 共享文件夹、Linux 文件入口（基础环境就绪后显示） | 同一张卡里的两行，条件不变 → `AppRoute.SharedFolders` / `AppRoute.LinuxFiles(wireName)` |
| 「可选工具」卡（工具就绪后显示） | 卡内 `CardTitle`，显示条件不变 |
| Python / Node / SSH / Kimi：说明随状态变化（进度 / 已就绪 / Debian 专用文案） | `SettingsRow.subtitle`，逻辑原样搬过来 |
| 可选工具的「安装 / 安装中」按钮（忙碌、需要 Root 时禁用） | 行尾 `MovoPillButton`，禁用条件不变，点击走原来的 `launchInstallation` |
| 已安装：禁用的「已安装」按钮 | 行尾值 `RowTrailing.Value("已安装")`（见有意改动 1） |
| Kimi 就绪：「启动 Web UI / 打开 / 启动中」按钮 → `launchKimiWeb()` | 行尾主操作胶囊（`primary`），文案和禁用条件不变 |
| Kimi 运行中：「停止」按钮 → `kimiWebLauncher.stop` | 行尾次要胶囊，在主按钮左边，条件不变 |
| Kimi 运行状态探测 `LaunchedEffect(selectedDistribution, backend, kimiWebLaunching)` | 没改 |
| APK 分析行：说明（进度 / 已就绪 / 简介）、安装按钮 | `SettingsRow`：说明逻辑不变；没装时是 `MovoPillButton`，装好后是值「已安装」 |
| 需要 Root 时所有安装按钮禁用 | 不变 |
| PRoot 时申请通知、走前台执行；拿不到前台服务时显示提示 | 没改 |

## 2. 工作区 `WorkspaceScreen`

骨架：`MovoListPage(itemSpacing = 0)` + 分段卡片。

| 改前功能 | 改后位置 |
|---|---|
| 卡外的「私有工作区」说明块（标题 + 说明） | 第一张卡的 `CardTitle`「私有工作区」；说明改写成页脚 `CardFooter`（新文案 `movo_workspace_footer`） |
| 导入文件（多选，忙碌时禁用） | 卡内 `SettingsRow` + 箭头，`enabled = !busy` 不变 |
| 公共目录访问（说明区分已授权 / 未授权），跳到系统「所有文件访问」设置；没有这个设置页时显示失败 | 卡内 `SettingsRow`（说明不变，行尾换成外链图标 `External`，因为会跳到系统设置），异常处理不变 |
| 回到页面后刷新授权状态 | 没改（`accessLauncher` 回调） |
| 结果消息（已导入 / 部分导入 / 已导出 / 失败），原来是卡外的 `BasicComponent` | 第一张卡内的 `CardNotice`（失败用 Rose 警示样式，其余用 ⓘ），就地显示 |
| 卡外路径标题（根目录显示「工作区文件」） | 文件卡首段的 `CardTitle`（根目录显示「工作区文件」，其他显示路径） |
| 返回上级目录（不在根目录时显示） | 文件卡的一行，条件和行为不变 |
| 空状态 `ListEmptyState`（暂无文件 + 说明） | 文件卡末段的一行（标题 + 说明，不能点） |
| 条目：目录 → 进入；文件 → 导出（`CreateDocument`）；说明是「文件夹」或「大小 · 点按导出」；忙碌时禁用 | 每个条目一段：目录行尾是箭头，文件行尾是 16 下载图标；点击行为、说明和禁用条件不变 |
| 目录 / 文件图标 | `RowLeading.Icon(Folder / File)`，换成 Lucide |
| 导入后跳到 `imports`、`revision++` 刷新 | 没改 |

## 3. Linux 文件 `LinuxFilesScreen`

骨架：`MovoListPage(itemSpacing = 0)` + 分段卡片。

| 改前功能 | 改后位置 |
|---|---|
| 发行版参数无效 / 没安装的提示 | 单张卡的 `CardStateText` |
| 路径条（等宽） | 列表卡首段：等宽 13 Medium 次要色，放在 `Card/Title` 的位置，路径太长会换行 |
| `../` 返回上级（不在 `/` 时显示） | 一行（Folder 图标 + 箭头），逻辑不变 |
| 目录条目 → 进入；文件条目（显示大小）→ 打开查看 | 每个条目一段，点击逻辑不变；目录有箭头，文件没有行尾 |
| 列举失败（不是目录 / 读不了） | 列表卡末段的 `CardStateText` |
| 目录为空 | 列表卡末段的 `CardStateText` |
| 查看文件：路径 + 可选中的等宽正文 | 查看卡：路径标题 + `SelectionContainer` 等宽正文（13 等宽，主色） |
| 截断提示 `HintText` | **改成查看卡的页脚 `CardFooter`** |
| 二进制 / 不是文件 / 读不了 | 查看卡里的 `CardStateText` |
| 返回键：查看文件时先回到列表（`NavigationBackHandler`），顶栏返回也一样 | 没改 |
| 关闭页面时结束 root Shell（`beginClosing`） | 没改 |

## 4. 共享文件夹 `SharedFoldersScreen`

骨架：`MovoListPage`，一张卡。

| 改前功能 | 改后位置 |
|---|---|
| 没有挂载时的空状态（标题 + 说明） | 卡内一行（不能点） |
| 挂载项：名称、源路径、挂载点、源目录不存在的标记 | `SettingsRow`：标题是名称，说明是多行（源路径 / 挂载点 · 不存在），和原来一样 |
| 挂载项的「删除」按钮 → 确认 | 行尾 `MovoPillButton` →「删除」确认对话框 |
| 添加共享文件夹：有 Root 或已有全部文件权限时直接打开选择器，否则先去系统授权；授权回来后打开选择器或提示被拒；没有设置页时提示 | 卡内最后一行，行尾是 20 的「+」；分支逻辑不变 |
| 卡外的页脚长段说明 `shared_folders_footer` | 卡片页脚 `CardFooter`，拆成一句一行（新文案 `movo_shared_folders_footer_1/2`） |
| 卡外的提示卡 `notice`（授权被拒、保存失败） | 卡内 `CardNotice`（Rose 错误样式），就地显示 |
| 删除确认 Miuix `WindowDialog` + `MiuixDialogActions(destructive)` | `MovoConfirmDialog(destructive = true)`：Rose 警示图标 + 黑底确认按钮；确认后保存、刷新、`rmdir` 清理，保存失败时提示，逻辑不变；退场动画期间文案用最后一次的目标 |
| 目录选择对话框：路径输入框（按「前往」跳转）、浏览错误、`../` + 子目录列表、「当前选择」、名称输入框（没手动改过时自动跟着目录名）、表单校验错误、取消 / 添加 | `MovoConfirmDialog(message = null, extraContent = …)`：输入框还用 Miuix `TextField`（颜色已映射）；错误用 Rose 图标 + 主色文字；目录行是 44 高的按压行 + Folder 图标；校验顺序和文案不变；保存失败时对话框不关 |
| 进入页面时探测源目录是否存在 | 没改 |

## 5. 守护任务对话框 `DaemonTasksDialog`

| 改前功能 | 改后位置 |
|---|---|
| Miuix `WindowDialog` 标题「守护任务」，点外面关闭 | `MovoDialogHost` + `TerminalDialogTitle`；点外面或返回键关闭 |
| 空状态 | `TerminalDialogEmpty` |
| 任务行：命令（等宽、单行省略）、元信息（环境 · 身份 · 运行中/已退出；运行中用强调色） | 等宽 15 主色命令 + 13 元信息（运行中用 Indigo 文字，文案本身也写了状态） |
| 「日志 / 收起」：展开时按需加载日志，加载中转圈，最高 160 可滚动 | `MovoPillButton`；日志框是 `bg/surface-muted` 圆角 8 + 等宽 13；加载中用 `TerminalSpinner` |
| 「停止」→ `onStop(id)`（已退出的任务也显示） | `MovoPillButton`，条件不变 |
| 列表最高 360 | 不变，行之间加 0.5 分隔线 |
| — | **新增**底部「关闭」整行次要按钮（见有意改动 4） |

## 6. 会话对话框 `SessionListDialog`

| 改前功能 | 改后位置 |
|---|---|
| 标题「会话」、空状态 | `TerminalDialogTitle` / `TerminalDialogEmpty` |
| 点一行切换会话并关闭对话框 | 行按压（`PressKind.Row`，圆角 12），`onSelect` + `onDismiss` 不变 |
| 当前会话：强调色加粗 +「当前」 | Indigo `Body/Strong` +「当前」13 Medium Indigo（原来是 SemiBold，改成 Medium） |
| cwd 副标题（块式终端才有） | 13 等宽次要色 |
| 状态（已退出 / 运行中 / 空闲，运行中用强调色） | 13 文字，运行中用 Indigo |
| 「重启」「关闭」 | 两个 `MovoPillButton` |
| 底部「新建会话」→ `onNew` + 关闭 | 底部按钮行右边的主操作「新建会话」；左边**新增**「关闭」次要按钮 |

## 7. 终端页 `UserTerminalScreen`（块式）/ `ConsoleScreen`（控制台）

**顶栏不在这里**：终端路由由 `ui/app/AgentAppRoot.kt` 的 `RoutedShell(route = AppRoute.Terminal)` 包进 `AgentAppShell`，顶栏是那里的 Miuix `AdaptiveTopAppBar` + `MiuixBackButton`（标题 `route_terminal`）。`AgentAppShell` 不归我，没改；终端顶栏要换成 `MovoTopBar` 得由负责 Shell 的人来做。

| 改前功能 | 改后位置 |
|---|---|
| 页面底色 `MiuixTheme.surface` | `MovoColors.bgCanvas`（同一个颜色，改成直接用 token） |
| 环境切换：Android / Alpine 或 Debian 文字页签（选中是强调色 SemiBold） | `TerminalEnvironmentChip` 胶囊（选中 = Indigo 浅底 + Indigo Medium） |
| cwd（等宽、单行、右对齐） | 13 等宽次要色，其余不变 |
| 会话按钮（Material Layers） | `MovoIconButton(MovoIcons.Layers)`，热区 44、图标 20、次要色 |
| 守护任务按钮（Material Insights），打开前刷新任务 | `TerminalMaterialIconButton(Icons.Rounded.Insights)`，还是 Material 图标（缺 Lucide `activity`），刷新逻辑不变 |
| 切到控制台（PTY 可用时显示，Material Terminal） | `MovoIconButton(MovoIcons.Terminal)`，条件不变 |
| 运行中显示「停止」→ `store.stop` | 主操作胶囊 `MovoPillButton(primary)` |
| 输入框：运行中和空闲的提示不同，最多 4 行，等宽；运行中把输入发给 stdin，否则当新命令执行 | 还用 Miuix `TextField`（颜色已映射），行为不变 |
| 发送按钮（没内容时 34% 不透明且禁用） | `MovoCircleButton(ArrowUp, primary)`：没内容时禁用（40%），有内容 = 发送 ↑ |
| Linux 没就绪的引导：说明 +「打开 Linux 环境」 | 说明 `Body/Regular` 次要色 + 主操作胶囊；ON_RESUME 刷新就绪状态和守护任务，没改 |
| 空状态提示 / 失败消息 | `Body/Regular` 次要色居中 |
| 控制台状态栏：环境切换、会话、守护任务、「简洁模式」 | 和块式终端同一套：胶囊 + 两个 44 图标按钮 +「简洁模式」次要胶囊 |
| 控制台断开或失败的遮罩：说明 +「重新连接」+（失败时）「打开 Linux 环境」 | 遮罩底色改成 `bgCanvas` 92%；「重新连接」是主操作胶囊，另一个是次要胶囊，两个并排 |
| 会话对话框 / 守护任务对话框 | 见第 5、6 节 |
| **终端内容区**：命令块（cwd ❯ 命令、ANSI 输出、运行中、退出码、截断、失败左边红条）、系统块、长按菜单（复制命令 / 复制输出 / 重新输入）、自动跟随到底部、控制台网格（逐行复用、光标反色、隐藏输入框接收键盘、Ctrl 组合键）、键盘栏 Esc/Ctrl/Tab/方向键 | **一处没动**，也没有套卡片 |

## 8. 有意改动

1. 可选工具和 APK 分析装好以后，行尾从「禁用的『已安装』按钮」改成值「已安装」（规范 8.7「有当前值：值」）。两种都点不了，行为一样。
2. Linux 文件：目录加载中（`entries == null`）和打开文件读取中（`fileResult == null`）原来什么都不显示，现在显示 `TerminalSpinner`。只是补了一个加载状态。
3. 共享文件夹「添加」行的行尾放「+」而不是箭头，因为它打开的是对话框，不是下一级页面。工作区「公共目录访问」的行尾用外链图标，因为会跳到系统设置。
4. 守护任务和会话对话框底部各加了一个「关闭」次要按钮（原来只能点外面或按返回键）。会话对话框的「新建会话」放在按钮行右边的主操作位置。
5. 共享文件夹的选择对话框：Movo 对话框居中、不跟着键盘上移，所以键盘弹出时（`WindowInsets.isImeVisible`）目录列表的最大高度从 240 降到 96，保证两个输入框和按钮不被挡住。
6. 页脚文案按规范 10.1 改写成一句一行、句末加句号：共享文件夹拆成 2 句，工作区说明改写成 1 句（新增 4 条 `movo_*` 文案，英文、简体、繁体三份）。
7. 文件列表（工作区、Linux 文件）保留了目录 / 文件图标（`Folder` / `File`）。规范说二级页的设置行不放图标，但文件列表里图标用来区分目录和文件，是有信息量的。

## 9. 没做到的点和原因

| 项 | 原因 / 建议 |
|---|---|
| 终端页顶栏还是 Miuix 大标题栏 | 顶栏在 `AgentAppShell.kt`（`RoutedShell`），不归我。建议负责 Shell 的人给 `AppRoute.Terminal`（以及 Browser）换成 `MovoTopBar` |
| 命令块长按菜单还是 Miuix `WindowListPopup` | `components/movo/` 里还没有 `Popover/Menu`（规范 8：圆角 20、内边距 8、项高 44、E3）组件。等有了公共组件再换；它属于终端内容区的交互，这次按要求不动 |
| 终端内容区还在读 `MiuixTheme` 颜色和字号 | 按要求「终端内容区保持原样」。Miuix 主题已经映射到 Movo 色板，不会跑色。键盘栏 `KeyChip` 里的 `FontWeight.SemiBold` 也还在（属于键盘栏） |
| 输入框还是 Miuix `TextField` | 组件指南允许；Movo 还没有输入框组件 |
| 共享文件夹选择对话框关闭时没有退场动画 | 选择器的内部状态（路径、名称、校验）要在每次打开时重置，所以还是 `if (showPicker)` 条件组合，移除时直接消失。删除确认、守护任务、会话三个对话框的退场也一样（后两个由调用方的 `if (showX)` 控制，调用方在 `ConsoleScreen` / `UserTerminalScreen` 里，保持原样） |
| `MovoDialogHost` 不处理键盘遮挡 | 不是我的文件。这次用缩短列表绕开了，建议在 `MovoDialogHost` 里给对话框加 `imePadding()`，或者让对话框跟着键盘上移 |
| 暂无 Toast 需要处理 | 这些页面原来就没有 Toast |

## 10. 需要的新图标（Lucide）

| Lucide 名称 | 用途 | 暂时用的 |
|---|---|---|
| `activity` | 终端状态栏的「守护任务」按钮（块式终端和控制台都有） | Material `Icons.Rounded.Insights`，包在 `TerminalMaterialIconButton` 里 |

其他用到的图标 `MovoIcons` 里都有：Terminal、Layers、Folder、File、Download、Plus、ArrowUp、Check、Info、CircleAlert、LoaderCircle、ChevronRight、ExternalLink。

## 11. 验证

按协调方的要求加了 `-Pkotlin.compiler.execution.strategy=in-process "-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8"`，避免多个任务并行时 Kotlin 守护进程内存不足。

- **编译**：`./gradlew :app:compileDebugKotlin -q …` **通过（exit 0）**，本范围的文件没有报错。前几次失败都不在本范围：别人正在改的 `ChatMessageItem.kt`、`DiagnosticsScreen.kt` 编译报错，以及并行构建把 `build/` 下的 KSP 产物删掉导致的 IO 错误；等了一会儿重试就过了。日志：`.docs/design-restore/restyle-D-logs/compile.log`。
- **单测**：`./gradlew :app:testDebugUnitTest --tests 'io.github.mangi.eta.ui.*' --tests 'io.github.mangi.eta.agent.terminal.*' …` → **357 个测试，10 个失败，1 个跳过**。日志：`.docs/design-restore/restyle-D-logs/test-run.log`。10 个失败都和本范围无关：
  - `DesignShotsTest` × 7（drawer / home / permissions / running / settings / systemAssistant / toolSettings）：别的任务新加的测试，还没进 git（`app/src/test/.../ui/design/`），失败原因是 `UnsatisfiedLinkError`（RenderNode 原生库）和 `FileSystemAlreadyExistsException`，属于运行环境问题。
  - `RootShellTerminalControllerCancellationTest` × 2：测的是 shell 进程和子进程清理的后端逻辑，本次没碰 `agent/terminal` 的代码（`git diff` 里 `agent/terminal` 没有改动）。
  - `ToolCatalogUiTest.everyRuntimeToolAndDisplayedCardHasASpecificIcon`：断言的是 `character_memory_get` 的工具图标，和终端页无关。
  - main 上本来就有 12 个失败；这 3 个非 DesignShots 的失败和终端 UI 没有依赖关系，本次没有新增失败。
- 按要求没有装包到手机，截图由主流程统一做。
