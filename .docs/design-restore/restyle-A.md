# 设计还原 · restyle-A（外观、语音、记忆、数据备份、Root 与系统增强）

依据：`docs/DESIGN_SYSTEM.md` §2、§8.7、§8.11、§10.1；`.docs/design-restore/component-guide.md`；功能基线 `.docs/design-restore/inventory-settings.md` §3.2–3.5。

改动文件：

- `app/src/main/kotlin/io/github/mangi/eta/ui/AppearanceSettingsScreen.kt`
- `app/src/main/kotlin/io/github/mangi/eta/ui/screens/voice/VoiceSettingsScreen.kt`（`VoiceCredentialDraft.kt` 未改）
- `app/src/main/kotlin/io/github/mangi/eta/ui/screens/memory/AgentMemoryScreen.kt`
- `app/src/main/kotlin/io/github/mangi/eta/ui/screens/backup/DataBackupScreen.kt`
- `app/src/main/kotlin/io/github/mangi/eta/ui/screens/enhance/SystemEnhanceScreen.kt`
- `app/src/main/res/values*/strings_movo.xml`（三份各追加 31 条 `movo_*`，段落注释「restyle-A」）

共同改法：页面骨架换成 `MovoListPage`（记忆页为 `MovoPage`，因为编辑器要固定在底部）；分组标题进卡片（`CardTitle`）；行换 `SettingsRow(icon=false)`，去掉全部 `PreferenceIcon`；卡片外不放文字，说明进 `CardFooter`；`WindowDialog` 确认换 `MovoConfirmDialog`；`WindowSpinnerPreference` / `OverlayDropdownPreference` 换 `SettingsRow + MovoChoiceDialog`；文本输入仍是 Miuix `TextField`。页面函数签名、存储 key、Repository / Action 调用都没改。

---

## 1. 外观 `AppearanceSettingsScreen`

| # | 改前 | 改后位置 | 说明 |
|---|---|---|---|
| A1 | 颜色 · 主题模式（跟随系统 / 浅色 / 深色） | **不显示** | 已确认固定浅色。`AgentAppTheme` 已写死 `isDark = false`，`MainActivity` 固定 `updateApplicationNightMode(LIGHT)`；存储值 `themeMode` 保留不删 |
| A2 | 颜色 · 莫奈取色 | **不显示** | 存储值保留 |
| A3 | 颜色 · 调色板风格（莫奈开启时） | **不显示** | 存储值保留 |
| A4 | 颜色 · 强调色（莫奈开启时） | **不显示** | 存储值保留 |
| A5 | 颜色 · 纯黑背景（莫奈开启时） | **不显示** | 存储值保留 |
| A6 | 界面 · 模糊效果开关，不支持 RuntimeShader 时置灰 | 「视觉效果」卡 · 模糊效果（开关） | `checked = blurEnabled && supported`、`enabled = supported` 不变；说明改为 10.1 写法「让顶栏和输入区透出下方内容」 |
| A7 | 界面 · 模糊样式（高斯 / 渐进，下拉；模糊开启且支持时展开） | 「视觉效果」卡 · 顶栏模糊样式（值 + 箭头 → `MovoChoiceDialog`） | 仍用 `AnimatedVisibility` 展开 / 收起；说明显示当前样式的描述 |
| A8 | 界面 · 横移返回手势 | 「返回手势」卡 · 横移返回手势（开关） | 写 `swipeDismissEnabled` 不变 |
| A9 | 界面 · 预测性返回手势 | 「返回手势」卡 · 预测性返回手势（开关） | 说明改为「返回时先预览上一页」 |
| A10 | 界面 · 界面缩放：右侧百分比 + 下方 Slider（80/90/100/110 关键点、磁吸、步进触感、松手提交）；点击行弹输入框对话框（80–110 才能确认） | 「显示」卡 · 界面缩放（值「95%」+ 箭头）+ 卡内 Slider；点击行弹 `MovoConfirmDialog(extraContent = TextField)` | Slider 参数全部保留；输入校验与 `commitScale` 不变 |

分组从「颜色与主题 / 界面与效果」两组改为「显示 / 返回手势 / 视觉效果」三组（按「用户想改什么」分组）。

## 2. 语音与唤醒词 `VoiceSettingsScreen`

### 2.1 主页面

| 改前 | 改后位置 | 说明 |
|---|---|---|
| 唤醒词组 · 开启语音唤醒（开关；summary 显示读取中 / 已关闭 / 麦克风未开启 / 监听状态 7 种） | 「唤醒词」卡 · 语音唤醒（开关，说明 = 同样的状态文字） | 缺麦克风时先请求权限，被永久拒绝跳应用详情；`setWake` + `EtaWakeWordController.refresh`；`enabled = settings != null && !wakeChanging` 全部不变 |
| 麦克风权限未开启（仅无权限时） | 「唤醒词」卡 · 麦克风（值「未开启」+ Rose 点 + 外链图标，说明「前往系统设置授权…」） | 条件与跳转不变；标注为「需要处理」 |
| 唤醒词（summary = 当前唤醒词）→ 页内编辑器 | 「唤醒词」卡 · 唤醒词（值 = 当前唤醒词 + 箭头） | 仍是页内切换编辑器（不是路由） |
| 监听范围（WindowSpinner） | 「唤醒词」卡 · 监听范围（值 + 箭头 → `MovoChoiceDialog`） | 写 `setWakeListenScope` 不变；原 summary「在其他 App 里也能唤醒会更耗电…」移到卡片页脚第 1 行 |
| 唤醒灵敏度（WindowSpinner） | 「唤醒词」卡 · 唤醒灵敏度（值 + 箭头 → `MovoChoiceDialog`） | 写 `setWakeSensitivity` + `refresh` 不变；原 summary 移到页脚第 2 行 |
| 开启唤醒状态通知（仅通知关闭时） | 「系统入口」卡 · 唤醒状态通知（值「未开启」+ Rose 点 + 外链） | 跳 `ACTION_APP_NOTIFICATION_SETTINGS` 不变 |
| 允许显示语音浮窗（仅无权限时） | 「系统入口」卡 · 语音浮窗（值「未开启」+ Rose 点 + 外链） | 跳 `ACTION_MANAGE_OVERLAY_PERMISSION` 不变 |
| 设为默认数字助理（未持有 ROLE_ASSISTANT 且系统支持时） | 「系统入口」卡 · 默认数字助理（值「未设置」+ 箭头） | `RoleManager.createRequestRoleIntent` 不变；三行都不需要时整张卡不显示 |
| ON_RESUME 刷新麦克风 / 通知 / 默认助理 / 悬浮窗 | 不变 | — |
| 语音服务 · 豆包语音配置（summary：读取失败点击重试 / 读取中 / 已配置 · 方式 / 未配置） | 「语音服务」卡 · 豆包语音配置（说明 = 同样的状态） | 读取失败时点击重试，否则取消测试并进入凭证编辑器；`enabled` 条件不变 |
| 提示「验证已保存凭据…」+ 整行 TextButton「连通测试 / 测试中…」+ 结果文字（成功主色 / 失败红色） | 「语音服务」卡 · 连通测试行：说明 = 测试结果（未测试时为缩短后的提示），右侧 `MovoPillButton`「测试 / 测试中…」 | `DialogConnectionProbe.test` 调用、启用条件 `configured && !testing && !clearing` 不变 |
| 清除凭证（仅已配置时）→ `WindowDialog` destructive 确认 | 「语音服务」卡 · 清除凭证（无箭头）→ `MovoConfirmDialog(destructive = true)` | 清除中不能取消 / 确认；成功后重置凭证并取消测试 |
| 隐私说明（卡外标题 + 卡内 HintText） | 「语音服务」卡页脚，两句一行一句 | 卡外不再放文字 |

### 2.2 唤醒词编辑器 `WakePhraseEditor`

| 改前 | 改后位置 |
|---|---|
| 顶栏右侧 TextButton「保存 / 保存中…」（有改动且未保存中可点） | 顶栏右侧 `MovoPillButton(primary)`，文案与启用条件不变 |
| 卡外标题「当前生效：xx」 | 卡内 `CardTitle` |
| TextField、提示（两行）、过短警示、错误文字 | TextField 在卡内；过短警示（次要色）与错误（Rose）在输入框下方；提示两行进 `CardFooter` |
| 卡外整行 TextButton「恢复默认唤醒词」 | 卡内 `SettingsRow`「恢复默认唤醒词」，启用条件不变 |
| 返回键 / 顶栏返回：有改动弹「未保存的更改」 | 不变，对话框换 `MovoConfirmDialog`（放弃修改 / 继续编辑） |

### 2.3 凭证编辑器 `VoiceCredentialsEditor`

| 改前 | 改后位置 |
|---|---|
| 顶栏「保存」：先 `DoubaoCredentialRules.validate`，不合法显示错误，否则保存 | 顶栏 `MovoPillButton(primary)`，逻辑不变 |
| 认证方式（WindowSpinner：Api-Key / App-Key + Access-Key）+ 卡外 HintText | 「认证方式」卡：值 + 箭头 → `MovoChoiceDialog`；提示拆两句进页脚 |
| Api-Key 或 App-Key + Access-Key 密码输入框（显示 / 隐藏切换） | 「凭证」卡内的 TextField，切换按钮保留；保存错误显示在这张卡内（Rose） |
| 听写高级设置开关 + Resource-Id 输入框与提示 | 「高级」卡：开关行；打开后卡内出现 Resource-Id 输入框，提示进页脚 |
| 卡外错误文字 | 移到「凭证」卡内 |
| 离开时未保存确认 | 不变（`MovoConfirmDialog`） |

## 3. 记忆 `AgentMemoryScreen`

| 改前 | 改后位置 | 说明 |
|---|---|---|
| 卡外标题「记忆」 | 状态卡 `CardTitle`「记忆」 | — |
| 启用记忆开关（加载中置灰），summary「关闭后不注入记忆…」 | 状态卡 · 长期记忆（开关，说明「每轮带上核心记忆，并允许读写」）；原 summary 拆两句进页脚 | 仍发 `ToggleEnabled`；标题按 8.7 改名词，不写「启用 xx」 |
| 核心记忆注入预算（只读） | 状态卡 · 核心记忆注入预算（只读行，说明不变） | — |
| 卡外标题「MEMORY.md」+ 编辑卡：TextField（6–12 行、等宽）、超限 / 未保存提示、字节数、清空、保存 | 编辑卡 `CardTitle`「MEMORY.md」+ 同样的 TextField、状态行（超限时 Rose）、`MovoBlockButton` 清空（次）/ 保存（主） | 启用条件、`DraftChanged` / `Save` 不变 |
| 布局：状态区可滚动（weight fill=false），编辑器固定在底部并随键盘上移 | 不变（`MovoPage` 自定义内容 + `imePadding` + `navigationBarsPadding`） | 顶栏 Q7 滚动态由 `MovoPage` 的滚动检测驱动 |
| 清空确认（destructive，保存中不能确认） | `MovoConfirmDialog(destructive = true)` | 确认后发 `Clear` |
| 通知弹窗（`state.notice`，单按钮「知道了」，点外部也关闭） | `MovoDialogHost` + 单个 `MovoBlockButton`「知道了」 | 发 `DismissNotice` 不变；退场动画期间保留上一条内容 |

## 4. 数据备份 `DataBackupScreen`

| 改前 | 改后位置 | 说明 |
|---|---|---|
| 顶部警告卡「请妥善保管备份文件」+ 说明 | 「备份」卡页脚两句 | 卡片外 / 顶部不再放说明 |
| 卡外标题「备份」 | `CardTitle`「备份」 | — |
| 导出数据（SAF CreateDocument，默认文件名 `Eta-backup-时间.eta-backup.json`），忙时置灰、图标换转圈 | 「备份」卡 · 导出数据（箭头；进行中说明「处理中…」、右侧转圈） | `onExport` 调用不变 |
| 导入数据（OpenDocument json / text）→ 导入确认（destructive，忙时不能取消） | 「备份」卡 · 导入数据 → `MovoConfirmDialog(destructive = true)` | `onImport` 调用不变 |
| 成功 / 失败 Toast | **就地显示**在对应行的说明里 | 见 §6 |

## 5. Root 与系统增强 `SystemEnhanceScreen`

| # | 改前 | 改后位置 | 说明 |
|---|---|---|---|
| E1 | Root 状态 summary + TextButton「授权 / 重新检测」，检查中置灰 | 「连接状态」卡 · Root：说明 = 状态，右侧 `MovoPillButton`（能请求授权时为主色「授权」，否则「重新检测」） | `RequestRoot` / `RefreshRoot` 分派与置灰条件不变 |
| E2 | 框架通信（已连接 / 未连接说明） | 「连接状态」卡 · 框架通信（只读行） | — |
| E3 | 单独一张卡：模块启用与作用域（说明） | 「连接状态」卡页脚两句 | 原说明三个分句合并为两句：「在 LSPosed 中启用 Eta 并检查作用域；关闭模块后连接仍可能保留。」「是否生效取决于目标进程是否加载 Hook。」（「服务连接不代表模块已启用」已在框架通信行的已连接说明里） |
| E4–E6 | 卡外标题「Root 能力」+ 3 行只读说明 | 「Root 能力」卡 · 3 行只读说明 | 去掉图标 |
| E7–E9 | 卡外标题「系统助手与增强」+ 3 行只读说明 | 「系统助手与增强」卡 · 3 行只读说明 | 去掉图标 |

---

## 6. 有意改动

1. **Toast 改就地提示**（规范 8.11）：
   - 语音：唤醒词保存成功 → 唤醒词行说明「唤醒词已保存，立即生效」（再次进入编辑器时清除）；唤醒开关保存失败 / 麦克风被拒 → 语音唤醒行说明显示「保存失败」/「未授予麦克风权限」（再次切换时清除）；监听范围 / 灵敏度保存失败 → 对应行说明「保存失败」；凭证保存成功 / 清除成功 → 不再弹 Toast，配置行状态本身会变成「已配置 · …」/「尚未配置…」；清除失败 → 确认框内显示 Rose「保存失败」，对话框不关闭（与原来失败时不关闭一致）。
   - 数据备份：导出 / 导入的成功摘要（「已导出 N 个对话和 M 个模型提供商」）与失败原因写在对应行的说明里；进行中只在正在执行的那一行显示「处理中…」和转圈（原来导入时也显示在导出行上）。
2. 外观页隐藏 A1–A5（已确认），只隐藏入口，数据模型与 DataStore 不动。
3. 行标题按 8.7 改为名词：开启语音唤醒 → 语音唤醒；麦克风权限未开启 → 麦克风（值「未开启」）；开启唤醒状态通知 → 唤醒状态通知；允许显示语音浮窗 → 语音浮窗；设为默认数字助理 → 默认数字助理（值「未设置」）；启用记忆 → 长期记忆。
4. 连通测试结果原来成功用主色、失败用红色；现在统一是行说明的次要色，靠文字区分（`SettingsRow` 说明不支持换色；规范也要求颜色不是唯一信号）。
5. 开关行说明按 10.1 重写 3 条（模糊效果、预测性返回、长期记忆）；原来卡外 / 行内的长说明改为卡片页脚，一句一行。
6. 外观页分组改为「显示 / 返回手势 / 视觉效果」；数据备份去掉单独的警告卡；Root 页的「模块启用与作用域」卡并入页脚。

## 7. 没法做到的点

1. **顶栏模糊样式（高斯 / 渐进）对新顶栏不生效**：`MovoTopBar` 固定用渐进模糊（规范 Q7），只有还在用旧 `TopBarBackdrop` 的页面读 `LocalTopBarBlurStyle`。行保留了（按要求），但所有页面改完新顶栏后这一项会失效，届时需要决定删掉还是让 `MovoTopBar` 读它。
2. **模糊效果开关**只控制「是否有背景模糊」：`rememberMovoBackdrop` 读 `LocalBlurEnabled`，关掉后新顶栏滚动态用 `bg/canvas` 90% 实底，功能仍在。
3. **测试结果的颜色区分**见 §6-4。
4. **密码显示 / 隐藏图标**：仓库的 `MovoIcons` 只有 `Eye`，没有 `EyeOff`，所以凭证输入框里的切换按钮暂时还是 Material `Visibility` / `VisibilityOff`（着色改为 `textSecondary`）。
5. 记忆页「长期记忆」行右侧没有放「N 条」之类的值：`AgentMemoryUiState` 没有条目数（inventory 里已记录，需要新数据源）。
6. 数据备份进行中的转圈仍是 Miuix `InfiniteProgressIndicator`（没有 Movo 版加载组件）。

## 8. 需要的新图标（Lucide）

| 用途 | Lucide 名称 |
|---|---|
| 凭证输入框「隐藏」 | `eye-off`（`eye` 已有） |
| 加载转圈（可选，替换 Miuix 进度圈） | `loader-circle` 已有，需要一个带旋转动画的 `MovoSpinner` 组件 |

## 9. 验证

- `./gradlew :app:compileDebugKotlin -q -Pkotlin.compiler.execution.strategy=in-process "-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8"`：**通过**（并行期间出现过的报错都在其他任务的文件里，或者是 KSP 生成文件被并行构建清掉；本任务 5 个文件从未出现报错）。
- 单测 `./gradlew :app:testDebugUnitTest --tests 'io.github.mangi.eta.data.*' --tests 'io.github.mangi.eta.ui.app.*'`（同样加上面两个参数）：180 个用例，1 个失败：`ToolCatalogUiTest.everyRuntimeToolAndDisplayedCardHasASpecificIcon`（`character_memory_get` 工具用的是兜底图标）。这个失败与本任务无关：本任务没有碰工具目录和图标映射。按要求没有去修。
- 未装包到手机；真机截图由主流程统一做。
