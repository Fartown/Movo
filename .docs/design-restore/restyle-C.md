# 改版 C：模型提供商页 + 工具能力目录

范围：`ui/pages/providers/*`（列表、详情「配置 / 模型」、ChatGPT 账号、请求头、模型参数弹窗）与 `ui/screens/tools/AgentToolsScreen.kt`、`ToolCard.kt`（设置 · 工具 · 全部工具）。
依据：`docs/DESIGN_SYSTEM.md` §2、§4.2、§8.7、§8.11、§9.3.1、§10.1，`.docs/design-restore/component-guide.md`。
存储 key、Repository 调用、路由、回调、`AgentToolsAction` 全部不变；三个对外入口 `ModelProviderListScreen`、`ModelProviderDetailScreen`、`AgentToolsScreen` 的签名不变（`AgentAppRoot.kt` 不用改）。

新增文件：`ui/pages/providers/ProviderControls.kt`，放本次用到、`components/movo` 里还没有的控件：分段切换 `MovoSegmentedTabs`、搜索框 `MovoSearchField`、把长列表卡片拆成多个 Lazy 条目的 `movoCardSegment`、复选框 `MovoCheckbox`、单选标记 `MovoRadioMark`。工具目录页也 import 了其中的 `MovoSegmentedTabs`。**建议主流程把这个文件移到 `ui/components/movo/`**（我不能改别人的目录）。

---

## A. 模型提供商列表 `ModelProviderListScreen`

骨架：`MiuixScaffoldPage` → `MovoListPage`（居中顶栏、Q7 滚动态、卡片间距 16、顶栏到首项 12、左右 20）。

| # | 改前功能 | 改后位置 / 写法 | 行为 |
|---|---|---|---|
| A1 | 进入页面时 `RuntimeConfigRepository.ensureDefaults` | 原样 `LaunchedEffect(Unit)` | 不变 |
| A2 | 搜索框（Miuix `InputField`），按名称 / Base URL / 类型过滤 | 第一项 `MovoSearchField`（48 高白底胶囊 + 0.5 描边，图标落在内容线 36），有内容时右侧出现 ✕ 清空 | 过滤逻辑原样 |
| A3 | 分组「新增提供商」卡外标题 | 卡内 `CardTitle` | — |
| A4 | 新增 OpenAI 兼容（OpenAI Logo + 说明） | `SettingsRow` + `RowLeading.Custom`（20 Logo，圆形裁切 + 0.5 描边）+ 箭头 | `ModelProviderNew(OpenAiCompatible)` |
| A5 | 新增 Anthropic（Anthropic Logo + 说明） | 同上 | `ModelProviderNew(Anthropic)` |
| A6 | 分组「已配置 N 个」卡外标题（复数） | 卡内 `CardTitle`，同一 plurals | — |
| A7 | 空态：无提供商 / 无匹配 两种文案 | 卡内一行 13 次要色 | 条件原样 |
| A8 | 提供商行：Logo（或按类型的通用图标）、名称、Base URL、「类型 · N 个模型 · 内置」、「已停用」 | 自绘 `icon=true` 行：20 Logo → 12 → 名称 15 Medium / Base URL 13 次要色 / 元信息 13 三级色；「已停用」并入元信息末尾 | — |
| A9 | 停用的提供商 60% 不透明 | 保留（Logo 与文字列 60%，右侧单选不变淡） | — |
| A10 | 点行进入详情 | `movoClickable(PressKind.Row)` | `ModelProviderDetail(id)` |
| A11 | 长按删除（仅自定义；内置不响应长按） | 同上 `onLongClick`，内置传 null | 不变 |
| A12 | 右侧单选：✓ / 空心圆，设为当前 | 44 热区：选中 Lucide `CircleCheck` Indigo，未选中 20 线条圆三级色；读屏文案「已选中 / 设为当前」 | `setSelectedProviderId` + `syncToRemotePreferences` 原样 |
| A13 | 删除确认（`OverlayDialog` + destructive） | `MovoConfirmDialog(destructive = true)`；退场动画期间保留提供商名称 | `deleteProvider` + 同步，原样 |

## B. 提供商详情 · 配置 `ModelProviderDetailScreen` / `ChatGptAccountSection` / `ProviderHeadersEditor`

骨架：`MiuixScaffold` → `MovoPage`；标题居中（新建时「新建提供商」，否则提供商名称）。

| # | 改前功能 | 改后位置 / 写法 | 行为 |
|---|---|---|---|
| B1 | 提供商不存在：提示 + 「返回」按钮 | `MovoPage` 居中：说明 15 次要色 + `MovoPillButton`「返回」 | `onBack` |
| B2 | `ensureDefaults` | 原样 | — |
| B3 | 顶部 `TabRow`「配置 / 模型」（新建时不显示） | 顶栏下 12：`MovoSegmentedTabs`（40 高，选中 Indigo 浅底块滑动 `standard`，文字颜色 `fast`）；下方内容 `Crossfade` `fast`，减少动画时直接切换 | `currentTab` 原样 |
| B4 | 草稿 `rememberSaveable(ProviderConfigDraftSaver)` | 原样（切 tab、重建都保留未保存内容） | — |
| B5 | 卡「连接配置」：名称、Base URL | 卡内 `CardTitle` + Miuix `TextField`（颜色已映射） | 写草稿 |
| B6 | API Key（非 ChatGPT）+ 显隐切换 | 同上；显隐按钮暂用 Material 图标（缺 Lucide `eye-off`） | 不变 |
| B7 | anthropic-version（仅 Anthropic） | 同上 | 不变 |
| B8 | ChatGPT：已登录显示「账号 + 邮箱·套餐」，「退出登录」 | 连接卡内两行 `SettingsRow`；退出登录进行中置灰 | `ChatGptAuth.logout()`，结果写入测试结果位 |
| B9 | ChatGPT：未登录「登录」行（交换中改说明、进行中置灰） | `SettingsRow` + 外链图标（会打开浏览器） | `ChatGptLoginManager.start` → Custom Tabs；打不开浏览器时 cancel 并报错，原样 |
| B10 | ChatGPT：等待浏览器回跳弹窗（端口占用时换文案）、粘贴回跳地址、提交 / 取消 | `MovoConfirmDialog(extraContent = TextField)`；确认按钮「提交」在输入为空时禁用；取消 / 点遮罩 = `cancel()` | `submitManualInput` / `cancel` 原样；登录成功 / 失败的 `LaunchedEffect(login)` 原样 |
| B11 | Endpoint 模式（Chat Completions / Responses，`WindowSpinnerPreference`，非 Anthropic 且非 ChatGPT） | `SettingsRow`：说明写当前模式的含义，右侧值「Chat Completions / Responses」+ 箭头 → `MovoChoiceDialog` | 改草稿 `endpointMode`，原样 |
| B12 | 托管网页搜索开关（Responses 模式时） | `SettingsRow(RowTrailing.Switch)` | 改草稿，原样 |
| B13 | 测试连接：先校验草稿，进行中置灰，结果显示在行说明 | 连接卡最后一行；结果移到行下方就地结果行（失败 = Rose 警示图标 + 主色文字，成功 = Green ✓ + 次要色） | `validateProviderDraft` → `RemoteModelFetcher.fetch`，原样 |
| B14 | 卡「自定义请求头」：折叠行「未设置 / 已设置 N 项」+ 说明 + 展开箭头 | 卡内标题；行「请求头」，右侧值「未设置 / N 项」+ 16 下箭头（展开时旋转 180°，`fast`）；说明改成卡片页脚两句 | 展开状态 `rememberSaveable` 原样 |
| B15 | 展开后：每个请求头名称 / 值（值默认隐藏可切换）+ 删除；「添加请求头」 | 每项两行输入框 + 右侧 Lucide `Trash2`；末行「添加请求头」（`Plus` 图标行） | 增删改草稿，原样 |
| B16 | 卡「偏好与策略」：启用此 Provider 开关 | `SettingsRow` Switch | 改草稿 |
| B17 | 系统提示词多行输入 + 「留空使用默认…」说明 | 输入框留在卡内，说明改为卡片页脚 | 改草稿 |
| B18 | 保存 / 创建按钮（保存中 / 已创建 / 创建 / 保存配置，四种文案与禁用条件） | `MovoBlockButton` 主操作（48 高浅 Indigo） | 校验 → add / update → 选中 → 同步，原样；新建成功后 `onCreated` 切到详情 |
| B19 | 保存结果文字（按「失败」前缀区分红 / 绿） | 按钮下就地结果行（同 B13 样式，判断逻辑原样） | — |
| B20 | 危险区：内置 =「重置内置配置」，自定义 =「删除提供商」；进行中不可点 | 单独一张卡：一行，前导 20 Rose 图标（重置 `RotateCcw` / 删除 `Trash2`），标题主色 | 打开对应确认 |
| B21 | 删除确认（进行中禁用两按钮、文案「删除中…」） | `MovoConfirmDialog(destructive = true, confirmEnabled / cancelEnabled)` | 删除 → 同步 → 返回；失败写结果行，原样 |
| B22 | 重置确认（非危险样式） | `MovoConfirmDialog`（普通确认） | `resetBuiltIn` → 同步 → 结果行，原样 |
| B23 | 输入法弹出时列表让位（`imePadding`） | 原样 | — |

## C. 提供商详情 · 模型 `ProviderModelsTab`

| # | 改前功能 | 改后位置 / 写法 | 行为 |
|---|---|---|---|
| C1 | 卡「模型管理」：从远端自动拉取（拉取中改标题、置灰；说明写 /models 地址） | 卡内标题 + `SettingsRow`，右侧 20 `Download` 图标 | `RemoteModelFetcher.fetch` → 只留对话模型 → `syncRemoteModels` → 同步；三种结果文案（未返回可用模型 / 已过滤 N 个 / 拉取 N 个），原样 |
| C2 | 添加自定义模型（打开空白编辑弹窗） | `SettingsRow`，右侧 20 `Plus` 图标 | 原样 |
| C3 | 操作结果文字（按「失败」前缀区分颜色） | 卡内底部就地结果行 | 原样 |
| C4 | 搜索模型（`InputField`，模糊匹配名称与 ID） | `MovoSearchField` | `filterProviderModels` 原样 |
| C5 | 列表标题「模型列表（共 N 个）/ 匹配 M / N」 | 模型列表卡的卡内标题 | 原样 |
| C6 | 空态：无模型 / 无匹配 | 卡内一行 13 次要色 | 原样 |
| C7 | 模型行：显示名、Model ID、能力标签（上下文长度 / 支持思考）、「当前」强调标签 | 显示名 15 Medium、ID 13 次要色、标签 12 Medium 圆角 8（普通浅底次要色，「当前」Indigo 浅底 + Indigo 文字） | — |
| C8 | 点行 = 设为当前；多选模式下 = 勾选 | `movoClickable(PressKind.Row)` | 原样 |
| C9 | 长按进入多选（已在多选时 = 勾选） | 同上 `onLongClick` | 原样 |
| C10 | 右侧「编辑参数」按钮 | Lucide `PenLine` 44 热区（原 Material `Tune`） | 打开编辑弹窗 |
| C11 | 右侧单选（设为当前） | 同 A12 的单选标记 | `setSelectedModelId` + 同步 |
| C12 | 多选模式：行右侧复选框 | `MovoCheckbox`（20 方框，勾选时填 Indigo、✓ 从起点画到终点 `fast`；取消时 ✓ 淡出 120ms） | — |
| C13 | 多选模式：系统返回键先退出多选 | `NavigationBackHandler` 原样 | — |
| C14 | 底部悬浮多选栏：退出、已选 N 个、全选 / 全不选、删除（0 个时禁用） | 白底圆角 28 + E2 阴影、高 56：✕ 图标按钮 → 已选数量 → 两个 32 高行内按钮（删除带 `Trash2` 图标）；滑入 `standard` + enter，滑出 `standardExit` | 原样；列表底部为操作栏预留 88 |
| C15 | 批量删除确认（复数文案） | `MovoConfirmDialog(destructive = true)` | `deleteModels` → 同步 → 退出多选，原样 |
| C16 | 模型编辑弹窗：显示名、Model ID、上下文长度（非法时报错）、覆盖说明、恢复自动、用途说明 | `MovoDialogHost`：标题 16 Medium → 可滚动字段区（按弹窗实际高度让出标题与按钮区，横屏不挤出按钮）→「取消 / 保存」两个 48 整行按钮；「恢复自动」改为 32 行内按钮；错误 = Rose 图标 + 主色文字 | 保存禁用条件（名称、ID 非空、上下文合法、保存中）原样 |
| C17 | 支持思考开关（说明区分自动 / 已覆盖），开启后「默认」（不可改）+ 7 个档位复选 | 字段区内一张白底圆角 20 小卡：开关行 + 档位复选行（`SettingsRow` + `MovoCheckbox`） | `reasoningOverrideActive` 等逻辑原样 |
| C18 | 恢复自动（思考覆盖）、删除模型（仅编辑已有模型） | 两个 32 行内按钮（`RotateCcw` / `Trash2`） | 删除 → 关闭编辑弹窗 → 打开删除确认，原样 |
| C19 | 保存中不能关闭弹窗 | `MovoDialogHost(dismissible = !isSaving)` + 取消按钮禁用 | 原样 |
| C20 | 单个删除确认（删除中…） | `MovoConfirmDialog(destructive = true)`；退场动画期间保留模型名 | `deleteModel` → 同步 → 结果行，原样 |

## D. 工具能力目录 `AgentToolsScreen` / `ToolCard`（设置 · 工具 · 全部工具）

骨架：`MiuixScaffoldPage` → `MovoListPage`，标题仍为「工具能力」。

| # | 改前功能 | 改后位置 / 写法 | 行为 |
|---|---|---|---|
| D1 | 顶栏返回 | `MovoListPage(onBack)` | `AgentToolsAction.NavigateBack` |
| D2 | `TabRow`「当前设备 / 全部能力」，两个 tab 各自保留滚动位置 | 第一项 `MovoSegmentedTabs`（按 §9.3.1）；两个 `LazyListState` 原样传给 `MovoListPage`；切换后分组卡片淡入 `fast`（减少动画时直接显示） | `showAll`（`rememberSaveable`）原样 |
| D3 | 「Root 与系统增强」入口卡（说明「Root 授权与框架状态」） | 单行卡：`SettingsRow` + 箭头 | `OpenEnhancements` |
| D4 | 10 个分组，卡外 `SmallTitle` | 每组一张 `MovoCard`，卡内标题 = 分组名，右侧三级色写数量 | 分组与顺序来自 `buildToolsState`，未改 |
| D5 | 「当前设备」过滤（无 Root 时隐藏需要 Root 的卡，非 ColorOS 时隐藏 ColorOS 卡） | `projectToolGroups` 原样；空分组整组不显示 | 原样 |
| D6 | 两列网格，宽度 < 320 或字号 ≥ 1.3 时单列；同一行两张卡等高 | 卡内网格（卡内缩 8 + 格子内缩 8 = 内容线 16），两列间距 4；判断条件原样；两列时同一行等高、动作行贴底 | 原样 |
| D7 | 卡片：工具图标 | 40 图标底块、圆角 12、20 图标，按分组的类别色（见下「类别色」） | — |
| D8 | 标题（≤ 2 行）、说明（≤ 3 行；无 Root 时终端换普通说明） | 标题 `Body/Strong` 主色，说明 `Label/Regular` 次要色 | 原样 |
| D9 | 条件文字（需要 Root / 无障碍 / 通知使用权 / 使用情况访问 / 位置 / ColorOS / 部分功能需要 Root） | 14 `ShieldAlert` + 13 次要色 | `toolRequirementText` 原样 |
| D10 | 底部动作文字 + 箭头（去浏览器 / 管理权限 / 查看系统增强），无动作时灰色「查看说明」 | 有动作：13 Medium 主色 + 16 `ArrowRight`；无动作：13 Regular 三级色 | — |
| D11 | 点卡片：有动作执行动作，没有动作弹说明 | 格子 `movoClickable(PressKind.Card)`（缩放 0.98 + 叠加层，圆角 20 = 28 − 8 同心） | `toolCardAction` 原样 |
| D12 | 有动作时右上 ⓘ 弹说明 | 右上 `MovoIconButton(Info)`，44 热区，读屏「查看 xx 的说明」 | 原样 |
| D13 | 说明弹窗（`ItemDescriptionDialog`：说明 + 条件，可滚动，关闭按钮） | 本文件私有 `ToolDescriptionDialog`（`MovoDialogHost`：标题 → 说明 15 次要色，最高 360 可滚动 → 整行「关闭」） | 内容拼接逻辑原样 |

类别色（§4.2 语义，按分组取色，相邻分组合起来一屏不超过 4 种）：

| 分组 | 色 | 理由 |
|---|---|---|
| 屏幕与控制、文本与剪贴板、记忆 | Indigo | Agent 操作 App / Agent 自身 |
| 网页浏览 | Blue | 信息与网络、浏览器 |
| 应用与系统、设备直达 | Amber | 时间与系统（闹钟、计时、设备状态） |
| 敏感设备能力 | Rose | 通知与风险（短信验证码、通知、系统设置） |
| 个人数据直达、文件视觉 | Green | 感知（读取个人数据、读图） |
| 终端与文件 | Graphite | 开发者与底层 |

---

## 有意改动

1. 所有分组标题进卡片（`CardTitle`），卡片外只剩搜索框、分段切换、保存按钮这类控件；说明性文字改成卡片页脚（请求头说明、系统提示词说明）。
2. 结果反馈统一为就地结果行（失败 = Rose 警示图标 + 主色文字，成功 = Green ✓ + 次要色文字）；判断失败的逻辑（「失败」前缀）没变。类别色不用于文字（§4.2 规则 2），所以不再用红 / 绿文字。
3. 提供商行的「已停用」从单独一行并入元信息行；元信息用「·」连接（两侧不加空格，component-guide §5）。
4. 删除 / 重置入口从「红字居中卡片」改为一行卡片：前导 Rose 图标 + 主色标题（文字不用 Rose）。
5. 测试连接的结果从行说明移到行下方的结果行（能显示多行错误，且带图标）。
6. 搜索框新增 ✕ 清空按钮（原 `InputField` 没有显式清空）。
7. 工具目录：原来 73 张独立卡片改为「每组一张卡片 + 卡内格子」，格子无边框、按压时显示圆角 20 叠加层；这样分组标题可以放进卡片（任务要求二选一，选了「分组标题进卡片」）。
8. 请求头编辑器里原来写死的中文（「自定义请求头」「未设置」「已设置 N 项」「添加请求头」「名称」「值」等）改为 `movo_provider_*` 三语文案。
9. 模型多选栏、编辑弹窗、确认弹窗的按钮都换成 Movo 行内 / 整行按钮；删除类确认用 `bg/inverse` 危险样式（§8.11）；「重置内置配置」保持普通确认（原来也不是危险样式）。

## 没做到的点与原因

1. **输入框仍用 Miuix `TextField`**（名称、Base URL、API Key、系统提示词、请求头、模型参数、ChatGPT 回跳地址）：`components/movo` 还没有文本输入组件，component-guide §3 也说输入框先保留 Miuix；颜色已映射到 Movo 色板。搜索框是自绘的 `MovoSearchField`。
2. **分段切换的内容过渡**：详情页用 `Crossfade`（真正的交叉淡化）；工具目录页由于 `LazyColumn` 由 `MovoListPage` 持有，只做了新内容淡入 `fast`（旧内容直接替换），不是严格的交叉淡化。
3. **工具图标仍是 Material**：`iconForTool` 返回 Material `ImageVector`，且 `ToolCatalogUiTest` 断言了具体的 Material 图标；逐个换 Lucide 需要先扩 `MovoIcons` 并同步改测试，不在本次范围。
4. **API Key / 请求头值的显隐按钮仍是 Material `Visibility / VisibilityOff`**：`MovoIcons` 只有 `Eye`，没有 `EyeOff`。
5. **`ProviderControls.kt` 里的通用控件**（分段、搜索框、复选框、单选标记、卡片分段绘制）放在 providers 目录，工具页跨包引用；应并入 `ui/components/movo`。
6. **复选框圆角 6**：圆角表没有适合 20 方框的 token，按尺寸推导（约 0.3）取 6，已在代码注释里写明。
7. 本页范围内没有 Toast / Snackbar，无需保留。
8. 真机截图未做（按要求由主流程统一做，未装包）。

## 需要的新图标（Lucide 名称）

| 用途 | Lucide | 现在用的 |
|---|---|---|
| 密码显隐切换（隐藏态） | `eye-off`（配合已有 `eye`） | Material `Visibility` / `VisibilityOff` |
| 单选未选中 | `circle` | 手绘 20 线条圆（同几何） |
| 未知来源的自定义提供商 | `server` | `Database` |
| 编辑模型参数 | `sliders-horizontal`（可选，现用 `pen-line` 也能表达） | `PenLine` |
| 工具目录 73 个工具 | 建议按工具逐个对应，例如：`scan-eye`（看屏幕）、`pointer`/`mouse-pointer-click`（点击）、`hand`（长按）、`move`（滑动）、`text-cursor-input`（输入）、`clipboard-paste`、`app-window`、`alarm-clock`、`timer`、`battery`、`wifi`、`volume-2`、`message-square-text`（短信）、`bell`、`map-pin`、`contact`、`calendar`、`image`、`file-search`、`brain`（记忆）、`terminal`、`folder` 等 | `iconForTool` 的 Material 图标 |

## 验证

- `./gradlew :app:compileDebugKotlin -q`：见下方「结果」。
- `./gradlew :app:testDebugUnitTest --tests 'io.github.mangi.eta.ui.*' --tests 'io.github.mangi.eta.data.*'`：见下方「结果」。

### 结果

工作区 `app/build` 被几个任务同时编译，反复出现「无法删除 classes 目录」、OOM 等冲突。所以把当前工作区（不含 build、.git）rsync 到 `/tmp/movo-restyleC`，在那份副本里按主流程给的参数跑：`-Pkotlin.compiler.execution.strategy=in-process "-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8"`。

- 编译：`compileDebugKotlin` 退出码 0，本范围文件没有 error 或 warning。
- 单测（`ui.*` + `data.*`）：共 323 个，1 个失败，4 个跳过。
  - `ToolCapabilityProjectionTest` 6/6 通过；`ProviderComponentsTest` 7/7、`ProviderRepositoryTest` 5/5、`BuiltinProvidersTest` 1/1、`AgentModelPickerProjectorTest` 7/7 通过。
  - `ToolCatalogUiTest` 3 个里挂 1 个：`everyRuntimeToolAndDisplayedCardHasASpecificIcon`，报错 `character_memory_get` 拿到的是兜底图标 `Build`。**这个失败本来就有，不是本次引入的**：HEAD 的 `buildToolsState` 里已经有 `character_memory_get` 这张卡，`ui/components/ToolIcons.kt` 与 HEAD 相同，且没给它配图标；本次没改 `iconForTool`、`buildToolsState`，也没改这个测试。修法是在 `ToolIcons.kt` 给 `character_memory_get` / `character_memory_write` 加专属图标（不在本任务范围，没改）。
  - 除此之外没有新增失败（主流程说 main 上预先有 12 个失败，这次在这两个包里只出现上面这 1 个）。
