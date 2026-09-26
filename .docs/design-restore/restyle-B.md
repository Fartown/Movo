# 设计还原 · restyle-B（Skills、MCP 服务器、角色）

依据：`docs/DESIGN_SYSTEM.md` §2、§8.6（搜索框）、§8.7、§8.11、§9.3（加载圈）、§10.1；`.docs/design-restore/component-guide.md`；功能基线 `.docs/design-restore/inventory-settings.md` §3.5。

改动文件（路径前缀 `app/src/main/kotlin/io/github/mangi/eta/ui/screens/`）：

- `skills/AgentSkillsScreen.kt`、`skills/SkillSwitchRow.kt`
- `mcp/McpServersScreen.kt`（列表 + 详情）
- `characters/CharacterLibraryScreen.kt`、`CharacterDetailScreen.kt`、`CharacterEditorScreen.kt`、`CharacterContextScreens.kt`、`CharacterWorldbookEditor.kt`
- `app/src/main/res/values*/strings_movo.xml`（三份各追加 4 条，段落注释「restyle-B」）：`movo_character_group_library`（角色库）、`movo_character_group_persona`（人设）、`movo_character_group_details`（角色细节）、`movo_action_clear_search`（清空搜索）

共同改法：骨架 `MiuixScaffoldPage` → `MovoListPage`（居中顶栏、左右 20、卡片间距 16、顶栏到首卡 12）；卡外 `SmallTitle` → 卡内 `CardTitle`；页面说明段落 → `CardFooter`（一句一行）；行去掉 `PreferenceIcon`（二级页 icon=false）；Miuix `Switch` → `MovoSwitch`（整行点击切换）；`WindowDialog + MiuixDialogActions` → `MovoConfirmDialog`（删除类 `destructive = true`）；Miuix `TextButton` → `MovoBlockButton` / `MovoPillButton`；顶栏 `IconButton + Material 图标` → `MovoIconButton + MovoIcons`；Miuix `TextField` 保留，外层换成 `MovoCard`。函数签名、路由、Store / Repository / Action 调用、存储 key 全部未改。

---

## 1. Skills `AgentSkillsScreen` + `SkillSwitchRow`

| # | 改前 | 改后位置 | 说明 |
|---|---|---|---|
| S1 | 顶栏：标题「Skills」、返回 → `AgentSkillsAction.NavigateBack` | `MovoListPage` 顶栏 | 不变 |
| S2 | 卡外「安装」+ 卡片：从 ZIP 导入（标题 / 说明随 `isImporting` 切换，导入中左侧转圈，否则 FolderZip 图标；`enabled = !operationPending`；`onClickLabel` = 选择 ZIP Skill 包） | 「安装」卡（`CardTitle`）· 从 ZIP 导入行 | 标题 / 说明切换不变；左侧图标去掉；右侧：导入中 = 16 Indigo 加载圈，否则箭头；`onClickLabel` 保留；OpenDocument MIME 列表不变 |
| S3 | 卡外「内置」+ 卡片：每个内置 Skill 一行（图标、名称、描述 2 行、⋯ 菜单、开关） | 「内置」卡 · `SkillSwitchRow` | 图标去掉；**整行点击切换**（原来只能点开关）；开关、⋯ 菜单保留；描述仍最多 2 行；`enabled = !operationPending` |
| S4 | ⋯ 菜单「查看说明」→ `ItemDescriptionDialog` | 同一菜单（Miuix `OverlayIconDropdownMenu`，触发图标换 `MovoIcons.Ellipsis` 20 次要色） → `SkillTextDialog`（`MovoDialogHost`：标题 + 可滚动正文 + 整行「关闭」） | 无描述时显示「无描述」不变 |
| S5 | 卡外「用户安装」+ 卡片：同 S3，⋯ 菜单多「删除」（`enabled` 控制） | 「用户安装」卡 | 删除项的 enabled 判断不变 |
| S6 | 卡外「已移除」+ 卡片：名称 +「点击重新安装」，点击 → `ReinstallBuiltin` | 「已移除」卡 · 行 | 图标去掉；右侧 16 下载图标（三级色）提示可重新安装；`enabled` 不变 |
| S7 | 空状态（无 Skill 且非加载中）：标题 + 说明 + 「从 ZIP 导入」按钮 | 卡内空状态块 + 主色胶囊「从 ZIP 导入」 | 条件与禁用条件不变 |
| S8 | 替换确认 `state.replacement`：「替换 Skill？」+ 摘要；确认 `ConfirmZipReplacement`（`!operationPending`），取消 / 外部点击 `CancelZipReplacement` | `MovoConfirmDialog`（普通确认） | 行为不变 |
| S9 | 删除确认：「删除 Skill」+ 摘要；确认先清 `deleteTarget` 再 `DeleteSkill` | `MovoConfirmDialog(destructive = true)` | 行为不变 |
| S10 | 结果通知 `state.notice`：标题 + 正文 +「知道了」（错误用普通色，否则主色）→ `DismissNotice` | `SkillTextDialog`：错误时标题前 20 Rose `circle-alert`，按钮次要色；否则主色按钮 | 行为不变 |
| S11 | 忙碌：`isImporting || busySkillId != null` 时所有行 / 按钮禁用 | 同 | 禁用行整行 40% 不透明（`movoClickable`） |

## 2. MCP 服务器 `McpServersScreen`

| # | 改前 | 改后位置 | 说明 |
|---|---|---|---|
| M1 | 顶栏「+」（Material Add）→ 打开添加对话框 | `MovoIconButton(MovoIcons.Plus)` | 不变 |
| M2 | 卡外「服务器 · N」+ 卡片：每个服务器一行（名称、「已启用 x/y 个工具」、箭头）→ `McpServerDetail` | 「服务器 · N」卡（`CardTitle`）· `SettingsRow(Arrow)` | 不变 |
| M3 | 空状态：「还没有 MCP 服务器」+ 说明 +「添加服务器」 | 卡内空状态块 + 主色胶囊「添加服务器」 | 不变 |
| M4 | 添加对话框：名称 / 地址（Uri 键盘）/ Bearer Token（密码）三个输入框，最大高 520 可滚动；错误文字（error 色）；确认按钮连接中显示「正在连接…」且禁用；名称为空 / 地址校验失败就地报错；成功后清空并关闭 | `MovoConfirmDialog(extraContent = 三个 TextField + 错误)` | 校验、`discover` → `add` 流程逐行保留；错误文字改 13 Rose |
| M5 | 添加成功 Toast「服务器已添加，发现 N 个工具」 | 「服务器」卡页脚 `CardFooter` | 规范 8.11 不用 Toast，结果就地显示（停留到离开页面） |
| M6 | 详情顶栏：刷新图标（`enabled = server != null && !working`）→ `McpServerManager.refresh` | `MovoIconButton(MovoIcons.RotateCw)`；刷新中换成 16 Indigo 加载圈（不可点） | 规范 9.3.1「结果慢就原地等」 |
| M7 | 刷新结果 Toast（成功「工具列表已刷新 · N 个」/ 失败原因） | 「工具 · N」卡页脚 | 同 M5 |
| M8 | 服务器不存在：一行「该 MCP 服务器已不存在」 | 卡内一行 | 不变 |
| M9 | 卡外「服务器」+ 卡片：启用服务器开关（说明 = URL）；身份认证行（说明 = 已配置 / 未配置）→ Token 对话框 | 「服务器」卡：开关行（整行切换，说明 URL）+ `SettingsRow(Arrow)` | 写库调用不变 |
| M10 | 卡外「工具 · N」+ 卡片：每个工具开关（标题 = title 或 name；说明 = 描述 / 只读工具 / 可能修改数据）；开启非只读工具先弹确认 | 「工具 · N」卡：开关行（整行切换，说明最多 2 行） | `readOnlyHint != true` 时开启先确认，关闭直接写 —— 逻辑不变 |
| M11 | 无工具：「没有兼容工具」+ 提示 | 卡内空状态块 | 不变 |
| M12 | 风险工具确认：「启用这个工具？」+ 摘要，确认按钮 error 色 | `MovoConfirmDialog`（**普通确认**，非 destructive） | 规范 8.11：开启风险开关用普通确认（与「工具」页敏感设备操作一致）；危险样式只给删除 / 清除 |
| M13 | 更新 Token 对话框：说明 + 密码输入框，保存 → 按是否为空写 NONE / BEARER | `MovoConfirmDialog(message = 说明, extraContent = TextField)` | 不变 |
| M14 | 删除卡：「删除服务器」+ 说明 → 删除确认（error 色）→ 删除后返回 | 单独一张卡，标题 Rose；`MovoConfirmDialog(destructive = true)` | 不变 |

## 3. 角色 `CharacterLibraryScreen`

| # | 改前 | 改后位置 | 说明 |
|---|---|---|---|
| C1 | 顶栏：导入角色卡（FileUpload，`!busy`）→ OpenDocument(png/json/text/octet-stream) → `importCard` → 进入详情 | `MovoIconButton(MovoIcons.Upload)` | 逻辑不变 |
| C2 | 顶栏：创建角色（Add，`!busy`）→ `discardEditor` → `CharacterEditor()` | `MovoIconButton(MovoIcons.Plus)` | 不变 |
| C3 | Miuix `SearchBar`「搜索名称或标签」，输入即过滤 | 自绘搜索框：高 40、圆角 20、`bg/surface-muted`、16 搜索图标次要色、占位 Body/Regular 三级色；有内容时右端 ✕ 清空；键盘「搜索」收起键盘 | 仍写 `store.query`，过滤逻辑在 Store 不变 |
| C4 | 加载中（`busy` 且库为空）：居中转圈 | 居中 24 Indigo 加载圈 | 不变 |
| C5 | 空库：「还没有角色」+ 说明 +「恢复默认角色」（`!busy`）+「创建角色」 | 卡内空状态块 + 两个胶囊（后者主色） | 不变 |
| C6 | 无搜索结果：「没有找到匹配的角色」+ 说明 | 卡内空状态块 | 不变 |
| C7 | 角色列表：每个角色一张卡（名称 + 简介或标签，最多 2 行），`!busy` 时点击进入详情 | 「角色库」卡（`CardTitle`，右侧数量）内一角色一行 + 箭头 | 条件不变；**不再是逐项 lazy item**（整张卡一个 item），见 §7 |
| C8 | 卡外「我的」+「我的人设」行 → `CharacterPersona` | 「我的」卡 · `SettingsRow(Arrow)` | 不变 |

## 4. 角色详情 `CharacterDetailScreen`

| # | 改前 | 改后位置 | 说明 |
|---|---|---|---|
| D1 | 顶栏标题 = 角色名 /「角色详情」 | 同 | 不变 |
| D2 | 未读到：「正在读取角色…」/「无法读取角色，请返回后重试」 | 卡内文字（`CharacterPageMessage`） | 不变 |
| D3 | 资料卡：名称（title2）、标签、设定（6 行）、设定 > 220 字时「阅读全文」→ 预览对话框 | 资料卡：名称 16 Medium、标签 13 次要色、设定 15 Regular 6 行；「阅读全文 ›」文字链接 | 阈值不变 |
| D4 | 「开始新对话」（主色，`!busy`）→ `startConversation` | 整行主按钮 `MovoBlockButton` | 不变 |
| D5 | 人设卡：使用我的人设开关（说明 = 称呼 /「未设置称呼」）；编辑我的人设 → `CharacterPersona` | 「人设」卡：开关行（整行切换）+ 箭头行 | 不变 |
| D6 | 卡外「开场白」+ 单选列表（默认 / 开场白 N，摘要 240 字截断，空时「没有预设开场白，由你先开口」；> 240 字时「阅读全文」） | 「开场白」卡：每条一行，选中项右侧 20 Indigo ✓；`Role.RadioButton` + `selected` 语义；「阅读全文 ›」在摘要下方 | 仍是就地单选（不改成 `MovoChoiceDialog`，原因见 §7） |
| D7 | 卡外「管理」：编辑角色（`!busy`，先 `discardEditor`）、剧情记忆（说明）、复制角色（`!busy`，完成后进入副本）、删除角色（error 色，`!busy`）、导出 PNG / 导出 JSON 两个按钮（`!busy`，CreateDocument + 安全文件名） | 「管理」卡：编辑角色、剧情记忆、复制角色、导出 PNG、导出 JSON 五行；「删除角色」单独一张卡（标题 Rose） | 导出从按钮改为行；逻辑不变 |
| D8 | 删除确认：长说明 + 删除（error 色，`!busy`）→ `store.delete` → 返回 | `MovoConfirmDialog(destructive = true, confirmEnabled = !busy)` | 不变 |
| D9 | 兼容说明（有警告时）：行「N 项内容按兼容范围处理」，点击展开 / 收起警告列表 | 单独一张卡：行 + 右侧 ▾/▴，展开后警告列表在卡内 | 不变 |
| D10 | 预览对话框：标题 + 可滚动正文（最大高 420）+ 关闭 | `CharacterTextDialog`（`MovoDialogHost`） | 不变 |

## 5. 编辑角色 `CharacterEditorScreen` + 世界书 `CharacterWorldbookEditor`

| # | 改前 | 改后位置 | 说明 |
|---|---|---|---|
| E1 | 顶栏：标题「创建角色」/「编辑角色」；✓ 保存（`!busy && 名称非空`）；`imePadding` | `MovoIconButton(MovoIcons.Check)`；`MovoListPage(modifier = imePadding)` | 不变 |
| E2 | 草稿未就绪：「正在读取…」/「无法读取角色，请返回重试」 | 卡内文字 | 不变 |
| E3 | 名称输入框（卡外） | 「角色设定」卡第一项 | 与设定合为一张卡 |
| E4 | 卡外「角色设定」+ 外貌性格经历（5 行） | 「角色设定」卡 | 不变 |
| E5 | 卡外「开场白」+ 默认开场白 + 备用开场白（每条带 ✕ 移除）+「添加备用开场白」 | 「开场白」卡：输入框、✕ 为 `MovoIconButton(X)`、底部胶囊「+ 添加备用开场白」 | 不变 |
| E6 | 「高级设置」卡：点击展开 / 收起 | 行 + ▾/▴ | 不变 |
| E7 | 展开后：性格与说话风格、故事背景、示例对话（无分组标题） | 「角色细节」卡（新标题） | 字段不变 |
| E8 | 卡外「提示词」：系统提示词、对话后置指令 | 「提示词」卡 | 不变 |
| E9 | 卡外「作者信息」：作者备注、标签（每行一个）、作者、角色版本 | 「作者信息」卡 | 不变 |
| E10 | 卡外说明「角色卡中的第三方脚本与扩展界面不会执行，相关数据会保留在导出的角色卡中。」（在最底部） | 「高级设置」卡页脚（展开时显示，两句两行） | 位置上移到高级设置卡内 |
| E11 | 底部「保存角色」/「正在处理…」（同 E1 条件） | 整行主按钮 | 不变 |
| W1 | 卡外「世界书」+「内嵌世界书」行（N 个条目 · 展开编辑 / 收起） | 「世界书」卡：行 + ▾/▴ | 不变 |
| W2 | 展开后：世界书名称、扫描最近消息数、Token 预算（非负整数或空）、递归匹配开关 | 同一张「世界书」卡内 | 校验 `optionalNonNegativeInt` 不变 |
| W3 | 卡外「条目」+ 每个条目一张卡：标题 / 状态说明（已停用 / 已跳过 / 始终参与 / 关键词）、右侧开关启停、点击展开编辑 | 「条目」卡（右侧数量）：每条一行，行点击展开、`MovoSwitch` 单独可点 | 不变（此行不是「整行切换」，因为点击要展开编辑器） |
| W4 | 条目编辑器：暂不参与匹配提示；内容（标题、内容）；触发（主 / 次关键词、同时匹配次级关键词）；插入（常驻上下文、放在角色设定之前、插入顺序）；移除条目 | 展开在该行下方、同一张卡内；小标题用 `CardTitle` 样式；开关用 `SettingsRow(Switch)`；「移除条目」胶囊（垃圾桶图标） | 不变 |
| W5 | 「添加条目」→ 新增并展开 | 「条目」卡底部胶囊「+ 添加条目」 | 不变 |

## 6. 我的人设 / 剧情记忆 `CharacterContextScreens`

| # | 改前 | 改后位置 | 说明 |
|---|---|---|---|
| P1 | 页面说明「设置你在故事中的身份。开始对话时可以选择是否使用；已开始的故事保留当时的人设。」 | 输入卡页脚两行：「开始对话时可以选择是否使用。」「已开始的故事保留当时的人设。」 | 首句与页面标题重复，删去（页脚最多 2 句） |
| P2 | 称呼（单行）、身份与关系（6 行），`!busy` | 同一张输入卡 | 不变；`imePadding` 保留 |
| P3 | 「保存人设」（主色）→ `savePersona(onBack)` | 整行主按钮 | 不变 |
| R1 | 页面说明「记录这个角色的重要经历、关系与约定。它由此角色的各次对话共享，受记忆总开关控制。」 | 输入卡页脚两行 | 第二句去掉「它」 |
| R2 | 剧情与关系（10 行） | 同一张输入卡 | 不变 |
| R3 | 「重新载入」（`loadMemory(force = true)`）+「保存记忆」（主色） | 同一行两个 48 整行按钮（次要 / 主色） | 不变 |

---

## 7. 有意改动与未做到的点

有意改动：

1. **Toast → 就地结果**（M5、M7）：MCP 添加成功与刷新结果写进对应卡片页脚。
2. **添加 MCP 对话框连接中「取消」置灰**（M4）：`MovoConfirmDialog` 的 `cancelEnabled` 同时控制外部点击与取消按钮。原来连接中外部点击已屏蔽，但取消按钮可点（只关窗，请求仍在后台跑，成功后才弹 Toast）；现在连接中两者都不可用，结果（成功关窗 / 失败报错）总能看到。
3. **风险工具确认改为普通确认**（M12），依据规范 8.11。
4. **开关行整行可点**（S3、M9、M10、D5）：按 `Settings/Row` 规范；开关本身不再单独接收点击，避免一次点击切两次。
5. **删除行标题 Rose**（M14、D7）：原 MCP 删除行是普通色，统一成 Rose 并单独一张卡。
6. **角色列表改为一张卡**（C7）：卡片外不放文字、分组标题在卡内，所以不再是一角色一卡；整张卡是一个 lazy item，角色非常多时首屏组合成本高于原来（一般几十个以内没有影响）。世界书条目同理（W3）。
7. **编辑页字段分组合并**（E3、E7、E10），见上表。
8. **错误通知**（S10）：标题前加 Rose `circle-alert`。

没做到 / 保留：

1. **开场白没有改成 `MovoChoiceDialog`**（D6）：每个选项带长摘要和「阅读全文」，放进对话框会丢掉摘要预览；保留为卡内单选行（选中 = Indigo ✓，与 `MovoChoiceDialog` 的选中样式一致）。
2. **Skill ⋯ 菜单仍用 Miuix `OverlayIconDropdownMenu`**：规范组件库里还没有弹出菜单；颜色已映射到 Movo 色板，只把触发图标换成 Lucide。
3. **输入框仍是 Miuix `TextField`**（按指南允许），外层已换 `MovoCard`。
4. **角色模块文案仍是硬编码中文**（原本就没有进 strings.xml）；本次只把新增的 4 条卡片标题 / 无障碍文案写进 `strings_movo.xml`。拆句后的页脚沿用原文，未进资源文件。
5. **私有组件**（按要求先写在自己文件里，建议后续收进 `components/movo/`）：
   - 带「说明行数限制 / 标题颜色 / 自定义右侧」的二级页行：`SkillRow`、`McpRow`（private）、`CharacterRow`；公共 `SettingsRow` 的说明不限行、没有 `onClickLabel`、不能设标题色。
   - 长文本对话框（标题 + 可滚动正文 + 单按钮）：`SkillTextDialog`、`CharacterTextDialog`。
   - 卡内空状态块：`McpEmptyBlock`（private）、`CharacterEmptyBlock`。
   - 加载圈：`SkillSpinner`、`McpSpinner`（private）、`CharacterSpinner`。另一改版任务正在 `MovoControls.kt` 里加公共 `MovoSpinner`，稳定后可直接替换。
   - 搜索框：`CharacterSearchField`（private，规范 8.6 同款），可与侧边栏搜索框合并为公共 `MovoSearchField`。
   - 对话框退场期间保留上一次内容：`rememberLastNonNull`（private ×2）/ `rememberCharacterLastNonNull`。
   - 以上 skills / characters 包内的是 `internal`（同包多个文件共用），名字带包前缀避免冲突。

## 8. 需要的新图标（Lucide）

| 用途 | 现在用 | 建议 Lucide |
|---|---|---|
| 角色库顶栏「导入角色卡」 | `upload` | `file-up` |
| MCP 详情顶栏「刷新工具」 | `rotate-cw` | `refresh-cw` |
| Skills「已移除」行的重新安装提示 | `download` | `archive-restore`（可选） |

## 9. 验证

见文末「验证记录」。

---

## 验证记录（2026-09-25）

- `./gradlew :app:compileDebugKotlin -q -Pkotlin.compiler.execution.strategy=in-process "-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8"`：**通过**（期间多次因他人文件改到一半或共享守护进程 OOM 失败，等待重试后通过；本任务文件从未出现编译错误）。
- `./gradlew :app:testDebugUnitTest --tests 'io.github.mangi.eta.ui.*' --tests 'io.github.mangi.eta.agent.skill.*' --tests 'io.github.mangi.eta.agent.mcp.*'`（同上参数）：**315 个测试，8 个失败，均与本次改动无关**：
  - `ToolCatalogUiTest.everyRuntimeToolAndDisplayedCardHasASpecificIcon`（工具图标目录，`ToolCatalogUiTest.kt:44`；不涉及本任务文件，属于已知预先失败）。
  - `ui.design.DesignShotsTest` 7 个（drawer / toolSettings / home / permissions / systemAssistant / settings / running）：另一任务新增、尚未提交的截图测试，失败原因是运行环境（`UnsatisfiedLinkError at RenderNode`、`FileSystemAlreadyExistsException`），也不涉及 Skills / MCP / 角色页面。
  - skills、mcp 包下的测试全部通过。
- 行高 56 / 68、上下 14、对话框最大高度、加载圈线宽这几处 dp 与公共 `SettingsRow` / `MovoConfirmDialog` 内部写法一致（组件尺寸推导值），其余间距、颜色、字号全部取 Movo 令牌。
- 未装包、未真机截图（由主流程统一做）。
