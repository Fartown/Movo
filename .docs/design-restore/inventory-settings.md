# 设置区功能清单（设计还原前基线）

- 范围：`SettingsScreen.kt`、`AppearanceSettingsScreen.kt` 以及从设置可达的全部页面（工具、权限、系统增强、语音、备份、记忆、Skills、MCP、角色、模型提供商、运行日志、语言、关于）。
- 目标结构：`docs/DESIGN_SYSTEM.md` §8.7（L435–462），以及 §8.6 侧边栏 L430/L433（侧边栏 `PaneDock` 的 6 个入口统一收进设置页）。
- 以下路径省略前缀 `app/src/main/kotlin/io/github/mangi/eta/`，行号基于分支 `feat/design-v1-restore` 当前工作区。
- 本文只做清单，不改代码。

---

## 0. 入口与导航接线

| 项 | 现状 | 位置 |
|---|---|---|
| 路由定义 | `AppRoute`：Settings、Tools、Permissions、SystemEnhance、VoiceSettings、AppearanceSettings、DataBackup、Memory、Skills、McpServers(+Detail)、Characters(+Detail/Editor/Persona/Memory)、ModelProviders(+Detail/New)、Diagnostics(+Run/System)、LinuxEnvironment、SharedFolders、Workspace、LinuxFiles | `ui/navigation/AppRoute.kt:6-98` |
| 导航器 | `AgentNavigator.push` 已在栈中则不重复压入；`pop` 栈只剩 1 个时返回 false，此时 `popRoute` 会 `finish()` Activity | `ui/navigation/AgentNavigator.kt:9-13,32-36`；`ui/app/AgentAppRoot.kt:230-234` |
| 设置页入口 | 目前只有侧边栏 `PaneDock` 的「设置」 | `ui/components/ConversationSidePaneScaffold.kt:783`；回调 `AgentAppRoot.kt:309` |
| 侧边栏另外 5 个入口 | 模型 → ModelProviders，工具 → Tools（工具能力目录），Skills，权限 → Permissions（权限健康页），角色 | `ConversationSidePaneScaffold.kt:784-790`；`AgentAppRoot.kt:305-310` |
| 设置页挂载 | `entry<AppRoute.Settings>` → `SettingsScreen(context, onNavigate = pushRoute, onBack = popRoute)` | `AgentAppRoot.kt:568-574` |
| 壳层 | 只有 Home/Browser/Terminal 包了 `RoutedShell`（`AgentAppShell`）；设置和所有设置子页**不经过壳层**，顶栏由页面自己的 `MiuixScaffoldPage` 画。`AgentAppShell.kt:101` 的 `currentRoute !is AppRoute.Settings` 判断实际走不到（死分支） | `AgentAppRoot.kt:252-323,341-369`；`ui/app/AgentAppShell.kt:101` |
| 滑动返回 | 所有 entry 都用 `swipeDismiss`，由外观里的「滑动返回」开关控制（`LocalAppearanceSettings.swipeDismissEnabled`） | `AgentAppRoot.kt:325-332` |
| 其他直达入口 | 快捷设置磁贴长按 → VoiceSettings（`MainActivity.kt:117-120` → `AgentAppRoot.kt:223-228`）；对话中失败/卡住的任务 → Diagnostics/DiagnosticsRun（`LocalRunLogOpener`，`AgentAppRoot.kt:248-250,318`）；运行日志任务详情「去模型设置」→ ModelProviders（`AgentAppRoot.kt:592`）；Linux 环境需 Root → SystemEnhance（`screens/terminal/LinuxEnvironmentScreen.kt:339`）；权限健康页 root 行 → SystemEnhance（`AgentAppRoot.kt:547`）；工具卡片 → Browser/SystemEnhance/Permissions（`AgentAppRoot.kt:370-381`） | — |

---

## 1. 设置主页 `SettingsScreen` 逐项清单

文件：`ui/SettingsScreen.kt`（869 行）。页面骨架 `MiuixScaffoldPage(title = "设置")`（L196-199）。

### 1.1 页面级状态与副作用（重排时不能丢）

| 状态 / 副作用 | 说明 | 行 |
|---|---|---|
| `capabilities = rememberDeviceCapabilities()` | Root 状态、Xposed 连接、工具能力；ON_RESUME 时刷新 Root 与工具能力 | L99；`ui/app/DeviceCapabilitiesUi.kt:33-60` |
| `EnhancementSettingsHistory` | 框架断开后仍显示上次连接时的开关值（快照，只读展示），并记住「曾连接过框架」「曾用过系统化」 | L100-102；`ui/app/EnhancementSettingsHistory.kt:9-40` |
| `hasConnectedFramework` / `hasUsedSystemizer` | 决定框架相关分组是否**可见**（见下文可见性条件） | L101-102 |
| `overlayGranted` / `accessibilityGranted` / `accessibilityProtectionEnabled` | ON_RESUME 时重新读取（从系统设置返回后刷新状态） | L107-115, L125-137 |
| Provider / Model 摘要 | `ProviderRepository.providersFlow()` + `RuntimeConfigRepository.selectedProviderIdFlow()/selectedModelIdFlow()` → 「提供商名 / 模型名」，未选模型显示「未选择模型」，没有提供商显示「未配置」 | L140-153 |
| `prefs`（RemotePreferences） | `Prefs.remotePreferencesForUi(EtaApp.serviceInstance)`；为 null 时 Hook 开关**置灰**但仍显示快照值 | L157 |
| `agentPrefs`（本地） | `Prefs.localAgentPreferences()`，Agent 运行时开关的事实来源，不依赖框架 | L158 |
| 服务状态监听 | `EtaApp.addServiceStateListener`：服务到达时切到 RemotePreferences、`enhancementHistory.captureConnected`、`Prefs.reconcileAgentPreferences(service)`、`RuntimeConfigRepository.ensureDefaults(service)` | L174-190 |
| 电源键目标监听 | 远端 `POWER_KEY_ASSISTANT_TARGET` / 旧的 `POWER_KEY_TAKEOVER` 变化时同步到 UI | L159-173 |
| 系统化安装流程 | `showSystemizerDialog` / `installingSystemizer`，确认后在 IO 线程执行 `GoogleAppSystemizerInstaller.install()`，结果用 Toast 提示 | L103-104, L688-719, L851-869 |

### 1.2 通用开关组件 `SwitchPref`（L760-811）的行为

- 初始值：`prefs?.getBoolean(key, default) ?: history.checked(key, default)`；默认值来自 `Prefs.Keys.BOOLEAN_DEFAULTS`（`config/Prefs.kt:49-65`）。
- 监听 `OnSharedPreferenceChangeListener`，外部改动也会同步到 UI（L774-783）。
- 写入：`putBooleanSync` 同步 `commit()`，**失败时不改 UI**，并 Toast「无法保存设置」（L788-804, L818-823）。
- 成功后：`history.recordCommittedBoolean`（Hook key 写快照）；属于 `LOCAL_AGENT_KEYS` 的 key 调 `Prefs.reconcileAgentPreferences` 回写远端（L793-797）。
- `enabled = prefs != null`；图标同步置灰（L768, L806-809）。

### 1.3 条目表

图例：类型 = nav（进入页面）/ switch / value（右侧显示值）/ spinner（弹出选择）/ dropdown / action（外部跳转或执行）/ dialog。
「目标位置」按 §8.7 的 5 组；⚠ = 目标结构里没有明确位置或行为有冲突，需要决定。

可见性缩写：**FW** = `prefs != null || hasConnectedFramework`（框架已连接，或曾经连接过）；**FW-live** = `prefs != null`（框架当前在线）。

| # | 当前分组（SmallTitle） | 行标题（zh-Hans） | 类型 | 调用 / 绑定 | 可见 / 可用条件 | 确认 / 副作用 | 行号 | 目标位置 |
|---|---|---|---|---|---|---|---|---|
| 1 | LLM 提供商 | 模型提供商 | nav + summary 值 | `onNavigate(AppRoute.ModelProviders)`；summary =「提供商 / 模型」 | 始终 | — | L204-213（摘要 L140-153） | 模型与对话 · **模型**（图标改为品牌 Logo `components/ProviderBranding.kt`，右侧值改为模型名） |
| 2 | LLM 提供商 | 语音与唤醒词 | nav + summary | `AppRoute.VoiceSettings`；summary「豆包听写、唤醒词、常听开关」 | 始终 | — | L215-222 | 模型与对话 · 语音与唤醒词 |
| 3 | LLM 提供商 | 默认启用思考 | switch | `SwitchPref(agentPrefs, AGENT_THINKING_ENABLED)`，默认 true，本地 key | 始终可用（本地配置） | 成功后 reconcile 到远端 | L224-230 | 模型与对话 · 默认启用思考（开关） |
| 4 | LLM 提供商 | 运行日志 | nav + summary | `AppRoute.Diagnostics`；summary 硬编码「排查任务失败、卡住和变慢 · 保存最近 20 个任务」 | 始终 | — | L231-236 | 通用 · 运行日志 |
| 5 | 上下文与扩展 | 记忆 | nav | `AppRoute.Memory` | 始终 | — | L244-252 | 模型与对话 · 记忆（设计要求右侧值，如「12 条」，当前没有这个数据） |
| 6 | 上下文与扩展 | Skills | nav | `AppRoute.Skills` | 始终 | — | L254-262 | 能力与扩展 · Skills |
| 7 | 上下文与扩展 | MCP 服务器 | nav | `AppRoute.McpServers` | 始终 | — | L264-272 | 能力与扩展 · MCP 服务器 |
| 8 | 上下文与扩展 | 角色（硬编码） | nav | `AppRoute.Characters` | 始终 | — | L274-282 | 能力与扩展 · 角色 |
| 9 | 工具 | 工具列表 | nav | `AppRoute.Tools` → `AgentToolsScreen`（**只读的工具能力目录**，不是开关页，见 §2） | 始终 | — | L290-294 | ⚠ 目标里「工具」二级页只写了 5 个开关和 Linux 工具环境，**能力目录没有位置**。建议：在新「工具」二级页底部加一行「全部工具能力」进入现有 `AppRoute.Tools`（作为三级页保留） |
| 10 | 工具 | 启用网页浏览工具 | switch | `AGENT_BROWSER_TOOLS`，默认 true，本地 | 始终可用 | reconcile | L296-302 | 能力与扩展 · 工具（二级页）开关 1 |
| 11 | 工具 | 启用设备直达工具 | switch | `AGENT_DEVICE_DIRECT_TOOLS`，默认 true，本地 | 始终可用 | reconcile | L304-310 | 工具二级页开关 2 |
| 12 | 工具 | 允许读取敏感设备信息 | switch | `AGENT_DEVICE_SENSITIVE_READ_TOOLS`，默认 true，本地 | 始终可用 | reconcile；⚠ 设计要求风险开关单独成卡，**开启时弹确认**（§8.7 风险开关、§8.11），当前没有确认 | L312-318 | 工具二级页 · 风险卡片 |
| 13 | 工具 | 允许敏感设备操作 | switch | `AGENT_DEVICE_SENSITIVE_ACTION_TOOLS`，默认 true，本地 | 同上 | 同上 | L320-326 | 工具二级页 · 风险卡片 |
| 14 | 工具 | 启用终端/文件工具 | switch | `AGENT_TERMINAL_TOOLS`，默认 true，本地 | 始终可用 | reconcile；是否算风险开关需要确定 | L328-334 | 工具二级页开关 5 |
| 15 | 工具 | Linux 工具环境 | nav | `AppRoute.LinuxEnvironment` | 始终 | — | L336-344 | 工具二级页 · Linux 工具环境 |
| 16 | （无标题的单独卡片） | Root 与系统增强 | nav | `AppRoute.SystemEnhance` | 始终 | — | L348-356 | 系统与权限 · Root 与系统增强 |
| 17 | 系统助手接管 | 数字助理应用 | action | `Settings.ACTION_VOICE_INPUT_SETTINGS`；失败时 Toast「无法打开默认助理设置」 | **始终**（不依赖框架） | — | L117-124, L362-370 | 系统与权限 · 系统助手（二级页），未连接框架时唯一显示的行。⚠ 语音页另有「设为默认助理」（RoleManager），两处行为不同，见 §3.3 |
| 18 | 系统助手接管 | 电源键长按 | spinner（值） | `WindowSpinnerPreference`，选项 系统默认助理 / Gemini / Eta（`PowerAssistantTarget` OEM/GEMINI/ETA）；写 `POWER_KEY_ASSISTANT_TARGET`（字符串，`putStringSync`） | 可见 FW；可用 FW-live | 成功 → `recordCommittedTarget`；失败 Toast「无法保存设置」 | L191-194, L371-403, L832-837 | 系统助手二级页 |
| 19 | 系统助手接管 | 自动设置默认助理 | switch | `ASSISTANT_AUTO_CONFIG`，默认 false，远端 | 可见 FW；可用 FW-live | — | L405-411 | 系统助手二级页 |
| 20 | 小布/小爱兼容入口 | 启用厂商助手自定义模型 | switch | `AGENT_CUSTOM_MODEL`，默认 true，远端 | 整组可见 FW；可用 FW-live | — | L416-438（本行 L421-427） | 系统助手二级页 ·「小布 / 小爱兼容」卡片 |
| 21 | 小布/小爱兼容入口 | 仅以 /agent 前缀接管 | switch | `AGENT_REQUIRE_PREFIX`，默认 false，远端 | 同上 | — | L429-435 | 同上 |
| 22 | Gemini | 息屏后维持 Hey Google 检测 | switch | `HOTWORD_SELF_HEAL`，默认 false，远端 | 组可见：FW **或** Root 已授权 **或** 用过系统化；本行需 FW；可用 FW-live | — | L440-470（本行 L446-452） | 系统助手二级页 · Gemini 卡片 |
| 23 | Gemini | 锁屏唤起自动语音输入 | switch | `LOCKSCREEN_VOICE_COMMAND`，默认 false，远端 | 同上 | — | L454-460 | 同上 |
| 24 | Gemini | 亮屏唤起自动语音输入 | switch | `SCREEN_ON_VOICE_COMMAND`，默认 false，远端 | 同上 | — | L462-468 | 同上 |
| 25 | Gemini | 将 Google App 转为系统应用 | action + dialog | 有 Root：弹 `SystemizerConfirmDialog`（L725-749，确认按钮安装中显示「处理中」，此时不能取消）；没有 Root：跳到 `AppRoute.SystemEnhance`；summary 没有 Root 时显示「需要 Root 授权」；安装中 `enabled=false`，`holdDownState` 跟随弹窗 | 可见：**Root 已授权 或 用过系统化**（**和框架无关**） | 确认后 `recordSystemizerUse()`，IO 执行 `GoogleAppSystemizerInstaller.install()`，按 `SystemizerInstallResult` 分 8 种 Toast（L851-869），需要重启 | L471-490, L688-719 | ⚠ 目标写「未连接框架时只显示数字助理应用」，但这一行只看 Root，不看框架。有 Root、没框架时现在会显示「Gemini」组且只有这一行。建议：放进「Root 与系统增强」页，或在系统助手二级页里按 Root 条件单独显示，不能被「未连接框架」规则藏掉 |
| 26 | 一圈即搜 | 手势条长按触发一圈即搜 | switch | `GESTURE_BAR_CIRCLE_TO_SEARCH`，默认 **true**，远端 | 组可见 FW；可用 FW-live | — | L495-517（本行 L500-506） | 系统助手二级页 · 一圈即搜卡片 |
| 27 | 一圈即搜 | 双指长按触发一圈即搜 | switch | `DOUBLE_FINGER_CIRCLE_TO_SEARCH`，默认 false，远端 | 同上 | — | L508-514 | 同上 |
| 28 | 通用 | 外观与主题 | nav | `AppRoute.AppearanceSettings` | 始终 | — | L523-531 | 通用 · 外观 |
| 29 | 通用 | 语言 | dropdown（值） | `LanguagePreference()`：`OverlayDropdownPreference`，选项「跟随系统」加 `LanguageSettingsRepository.supportedLocales`；`selectLocale(null / locale)`；ON_RESUME 时重新读取 | 始终 | 切换语言会触发 Activity 配置变化 | L533；`ui/components/LanguagePreference.kt:22-62` | 通用 · 语言 |
| 30 | 通用 | 数据备份 | nav | `AppRoute.DataBackup` | 始终 | — | L535-543 | 通用 · 数据备份 |
| 31 | 权限 | 悬浮窗权限 | action + 状态值 | 右侧「已授权 / 未授权」（未授权用 error 色）；**只在未授权时**跳 `ACTION_MANAGE_OVERLAY_PERMISSION`（package:），已授权时点击没有反应 | 始终 | ON_RESUME 刷新 | L551-583 | 系统与权限 · 权限（二级页） |
| 32 | 权限 | 无障碍增强工具 | action + 状态值 | 右侧「已开启 / 未开启」（`accessibilityGranted || AgentAccessibilityService.isAvailable()`，未开启用 primary 色）；点击始终打开 `ACTION_ACCESSIBILITY_SETTINGS` | 始终 | ON_RESUME 刷新 | L585-613, L839-849 | 权限二级页 |
| 33 | 权限 | 强制保持无障碍 | switch（异步） | 不是 `SwitchPref`：`AccessibilityProtectionClient.setEnabled(context, enabled){result}`；`accessibilityProtectionPending` 防止重复点击；回调更新 enabled 与 accessibilityGranted；UNAVAILABLE / REJECTED 两种失败 Toast | 可见 FW；可用 FW-live 且没有进行中的请求 | 异步结果回写 | L614-653 | 权限二级页 |
| 34 | 关于 | 源代码 | action + 值「GitHub」 | `ACTION_VIEW https://github.com/Mangi-11/Eta`（⚠ 没有 `runCatching`，设备上没有浏览器时会崩） | 始终 | — | L662-683 | 关于 · 源代码（设计要求外链图标） |
| — | 关于 | **版本** | — | **当前没有这一行** | — | — | — | 关于 · 版本：新增，可用 `BuildConfig.VERSION_NAME`（已在 `ui/screens/diagnostics/DiagnosticsData.kt:137` 使用） |

合计：现有 **34 个行**（switch 15 个 = 本地 6 + 远端 8 + 异步 1〔#33〕；nav 12 个；action 5 个〔#17、#25、#31、#32、#34〕；dropdown 1 个〔#29〕；spinner 1 个〔#18〕）+ **1 个确认弹窗**（系统化）+ 11 个分组（10 个带标题 + 1 张无标题卡片）；目标新增 1 行（版本）。

### 1.4 现有 SettingsScreen 用到的全部 Prefs key

| key | 默认 | 存储 | 所在行 | 目标 |
|---|---|---|---|---|
| `agent_thinking_enabled` | true | 本地（`LOCAL_AGENT_KEYS`） | #3 | 主页面 |
| `agent_browser_tools` / `agent_device_direct_tools` / `agent_device_sensitive_read_tools` / `agent_device_sensitive_action_tools` / `agent_terminal_tools` | 都是 true | 本地 | #10–14 | 工具二级页 |
| `power_key_assistant_target`（字符串，兼容旧的 `power_key_takeover`） | 按旧值推导 | 远端 | #18 | 系统助手 |
| `assistant_auto_config` | false | 远端 | #19 | 系统助手 |
| `agent_custom_model` | true | 远端 | #20 | 系统助手 |
| `agent_require_prefix` | false | 远端 | #21 | 系统助手 |
| `hotword_self_heal` / `lockscreen_voice_command` / `screen_on_voice_command` | false | 远端 | #22–24 | 系统助手 |
| `gesture_bar_circle_to_search` | true | 远端 | #26 | 系统助手 |
| `double_finger_circle_to_search` | false | 远端 | #27 | 系统助手 |

定义：`config/Prefs.kt:28-75`；默认值测试：`test/.../config/PrefsDefaultsTest.kt`。

---

## 2. 工具能力页 `AgentToolsScreen`（`AppRoute.Tools`）

文件：`ui/screens/tools/AgentToolsScreen.kt`、`ToolCard.kt`；数据：`ui/app/AgentAppState.kt:2814-2951`（`buildToolsState`）；投影：`ui/model/ToolCapabilityProjection.kt`。入口：设置 #9、侧边栏「工具」、`AgentHomeAction.OpenTools`（没有使用）。

| # | 区块 | 内容 | 类型 | 行为 | 行号 |
|---|---|---|---|---|---|
| T1 | 顶部 | TabRow「当前设备 / 全部」 | tab | `showAll`（rememberSaveable）；两个 tab 分别有自己的 LazyListState | L49-66 |
| T2 | 顶部卡片 | Root 与系统增强（summary「Root 授权与框架状态」） | nav | `AgentToolsAction.OpenEnhancements` → SystemEnhance | L67-75 |
| T3 | 分组 × 10 | 屏幕与控制(6)、文本与剪贴板(5)、网页浏览(4)、应用与系统(6)、设备直达(8)、敏感设备能力(17)、个人数据直达(17)、文件视觉(1)、记忆(4)、终端与文件(5)，共 73 张卡 | 卡片网格（两列；宽度 < 320dp 或字号缩放 ≥ 1.3 时单列） | 「当前设备」过滤掉需要 Root（没有 Root 时）和需要 ColorOS（非 ColorOS 时）的卡：`visibleOnCurrentDevice` | L76-145；`ToolCapabilityProjection.kt:14-28` |
| T4 | 每张卡 `ToolCard` | 图标、标题、说明、条件文字（需要 Root / 无障碍 / 通知使用权 / 使用情况访问 / 位置 / ColorOS / 部分功能需要 Root），底部动作文字 | 卡片点击 | `toolCardAction`：ROOT_REQUIRED / DEVICE_UNSUPPORTED → OpenEnhancements；browser_* → OpenBrowser；缺普通权限 → OpenPermissions；没有动作时弹 `ItemDescriptionDialog`；有动作时右上 ⓘ 也弹说明 | `ToolCard.kt:42-166`；`ToolCapabilityProjection.kt:31-43` |
| T5 | 顶栏 | 标题「工具能力」，返回 | — | `MiuixScaffoldPage` | L53-58 |

说明：这个页面**没有任何开关**。设置里的 5 个工具开关（#10–14）只在 SettingsScreen 里。目标的「工具」二级页（5 个开关 + Linux）与这个页面是两回事，路由名 `AppRoute.Tools` 已经被目录页占用，新建开关二级页需要新路由，或者把开关并入这个页面顶部。

---

## 3. 其他会被移动或合并的页面

### 3.1 权限健康页 `PermissionHealthScreen`（`AppRoute.Permissions`）

文件：`ui/screens/permissions/PermissionHealthScreen.kt`；数据：`AgentAppState.kt:2953-3052`（`buildPermissionHealthState`）；点击分派：`AgentAppRoot.kt:445-553`；进入时 `refreshPermissionHealth()`（L446-448）。
入口：侧边栏「权限」（`ConversationSidePaneScaffold.kt:789`）、工具卡片 OpenPermissions。**设置主页没有入口**。

每行都是 `ArrowPreference`，带图标和右侧状态文字（`PermissionStatusUi.label()/color()`，`components/StatusColors.kt:39-52`）。

| # | id | 标题 | 点击动作（`AgentAppRoot.kt`） | 状态来源 |
|---|---|---|---|---|
| P1 | background | 后台运行权限 | Root 且厂商是 oppo/realme/oneplus → root 执行 `am start` 打开 ColorOS 电池页；否则 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`（L474-490） | 是否忽略电池优化 |
| P2 | overlay | 悬浮窗权限 | `ACTION_MANAGE_OVERLAY_PERMISSION`（L461-470） | `canDrawOverlays` |
| P3 | microphone | 麦克风 | `microphonePermissionLauncher.launch(RECORD_AUDIO)`（L471-473） | `RECORD_AUDIO` |
| P4 | app_list | 应用列表读取 | `ACTION_APPLICATION_DETAILS_SETTINGS`（L491-500） | `hasAppListAccess` |
| P5 | location | 位置权限（summary 分 4 种状态） | DENIED → 请求粗略和精确位置；FOREGROUND_ONLY → 应用详情；DISABLED → `ACTION_LOCATION_SOURCE_SETTINGS`；AVAILABLE → 刷新（L501-532） | `DeviceLocationProvider.accessState` |
| P6 | notification_history | 通知使用权 | `ACTION_NOTIFICATION_LISTENER_SETTINGS`（L533-537） | `AgentNotificationHistoryService.isEnabled` |
| P7 | usage_access | 使用情况访问 | `ACTION_USAGE_ACCESS_SETTINGS`（L538-542） | `hasUsageAccess` |
| P8 | accessibility | 无障碍权限 | `ACTION_ACCESSIBILITY_SETTINGS`（L456-460） | 同设置 #32 |
| P9 | notifications | 通知 | `ACTION_APP_NOTIFICATION_SETTINGS`（L543-546；⚠ 没有 runCatching） | `areNotificationsEnabled` |
| P10 | root | Root 与系统增强 | push SystemEnhance（L547） | `RootAccess.isGranted` |

⚠ 目标「权限」二级页只列了悬浮窗、无障碍、强制保持无障碍（= 设置 #31–33），而 §8.6 要去掉侧边栏的「权限」入口。这样 P1、P3–P7、P9（7 项）在设置里就**没有入口**，只能从工具卡片间接进入。建议：新「权限」二级页 = P1–P10 全部行 + 设置 #33「强制保持无障碍」开关（悬浮窗和无障碍两行在两处重复，合并成一份）；主页「权限」行的 Rose 状态点基于这 10 项加 #33 的汇总。另外 `components/PermissionHealthCard.kt` 目前没有地方用（死代码），可以作为汇总逻辑参考。

### 3.2 Root 与系统增强 `SystemEnhanceScreen`（`AppRoute.SystemEnhance`）

文件：`ui/screens/enhance/SystemEnhanceScreen.kt`；动作：`AgentAppRoot.kt:554-564`。

| # | 行 | 类型 | 行为 | 行号 |
|---|---|---|---|---|
| E1 | Root（summary：检查中 / 已授权 / 不可用 / 超时 / 未授权） | 值 + TextButton | 有 su 且没授权 →「请求授权」`RootAccess.request`；否则「刷新」`RootAccess.refresh`；检查中按钮置灰 | L43-64 |
| E2 | 框架通信（已连接 / 未连接） | 只读 | `capabilities.xposedConnected` | L65-72 |
| E3 | 框架帮助 | 只读说明 | — | L75-83 |
| E4–E6 | Root 能力：设备、数据、Linux | 只读说明卡 | — | L84-103 |
| E7–E9 | 系统能力（Hook）：助手、Google、无障碍 | 只读说明卡 | — | L104-123 |

目标：系统与权限 · Root 与系统增强（保持原样；顶栏和卡片样式按新规范）。可以作为 #25「将 Google App 转为系统应用」的备选位置。

### 3.3 语音与唤醒词 `VoiceSettingsScreen`（`AppRoute.VoiceSettings`）

文件：`ui/screens/voice/VoiceSettingsScreen.kt`（563 行），整页保留在「模型与对话 · 语音与唤醒词」。和其他页有重叠的行：

| 行 | 条件 | 行为 | 行号 | 与其他页的关系 |
|---|---|---|---|---|
| 唤醒词开关（summary 显示监听状态） | 始终 | 缺麦克风权限时先请求（被永久拒绝就跳应用详情），再 `setWake` 并 `EtaWakeWordController.refresh` | L181-213 | — |
| 麦克风权限 | 没有麦克风权限时 | 应用详情 | L214-221 | 与权限健康页 P3 重复 |
| 唤醒词（进入 `WakePhraseEditor`） | 始终 | 页内切换编辑器（不是路由） | L222-228, L160-166, L413-440 | — |
| 监听范围 / 灵敏度 | 始终 | WindowSpinner，写 `VoiceSettingsRepository` | L229-265 | — |
| 通知 | 通知关闭时 | 应用通知设置 | L266-276 | 与 P9 重复 |
| 悬浮窗权限 | 没有权限时 | `ACTION_MANAGE_OVERLAY_PERMISSION` | L277-281 | 与设置 #31 / P2 重复 |
| 设为默认助理 | 没有持有 ROLE_ASSISTANT 且系统支持时 | `RoleManager.createRequestRoleIntent` | L282-286 | ⚠ 与设置 #17「数字助理应用」（`ACTION_VOICE_INPUT_SETTINGS`）是两种实现，重排时保留两者或统一成一个，但不能都丢 |
| 豆包配置 / 测试连接 / 清除凭据（确认弹窗，destructive） | — | `VoiceCredentialsEditor`、`DialogConnectionProbe.test`、`clearDoubaoCredentials` | L289-341, L350-, L470-535 | — |
| 隐私说明 | 始终 | `HintText`（本文件私有，L561） | L343-348 | 新规范要求说明放进卡片页脚 |

### 3.4 外观与主题 `AppearanceSettingsScreen`（`AppRoute.AppearanceSettings`）

文件：`ui/AppearanceSettingsScreen.kt`；写入 `AppearanceSettingsRepository.update`（L63-67），读取 `LocalAppearanceSettings`。

| # | 分组 | 行 | 类型 | 条件 | 行号 |
|---|---|---|---|---|---|
| A1 | 颜色 | 主题模式（跟随系统 / 浅色 / 深色） | OverlayDropdown | 始终 | L124-134 |
| A2 | 颜色 | 莫奈取色 | switch | 始终 | L135-142 |
| A3 | 颜色 | 调色板风格（9 种） | OverlayDropdown | 莫奈开启时（AnimatedVisibility） | L149-159 |
| A4 | 颜色 | 强调色（9 种） | OverlayDropdown | 莫奈开启时 | L160-170 |
| A5 | 颜色 | 纯黑背景 | switch | 莫奈开启时 | L171-178 |
| A6 | 界面 | 顶栏模糊 | switch | `isRuntimeShaderSupported()` 为 false 时置灰 | L189-197 |
| A7 | 界面 | 模糊样式（高斯 / 渐进） | OverlayDropdown | 模糊开启且支持时 | L203-213 |
| A8 | 界面 | 滑动返回 | switch | 始终；影响 `AgentAppRoot.kt:330-332` | L215-222 |
| A9 | 界面 | 预测性返回 | switch | 始终 | L223-230 |
| A10 | 界面 | 界面缩放（80–110%，Slider + 点击弹输入框 dialog） | Arrow + Slider + dialog | 始终 | L231-262, L267-294 |

⚠ 新规范用 `MovoColors` 固定配色（§13）。莫奈、调色板、强调色、纯黑、顶栏模糊这些开关在新视觉下是否还生效需要确认；功能不能静默失效。

### 3.5 其余设置子页（整页保留，只换壳层和样式）

| 页面 | 文件 | 主要功能点 | 目标位置 |
|---|---|---|---|
| 模型提供商列表 | `ui/pages/providers/ModelProviderListScreen.kt`（L73 起） | 搜索、新增 OpenAI 兼容 / Anthropic（L89-110）、列表选择 / 编辑 / 删除确认（L111-160）；详情页 `ModelProviderDetailScreen.kt`、ChatGPT 账号 `ChatGptAccountSection.kt`、模型 tab `ProviderModelsTab.kt` | 模型与对话 · 模型 |
| 记忆 | `ui/screens/memory/AgentMemoryScreen.kt`（用低层 `MiuixScaffold`，L53） | 启用记忆开关（L84-90）、注入预算只读（L91-95）、MEMORY.md 编辑 / 保存 / 清空确认（L105-195）、通知弹窗（L199-） | 模型与对话 · 记忆 |
| Skills | `ui/screens/skills/AgentSkillsScreen.kt` | 导入 zip（L73-107）、内置 / 用户 / 已移除分组开关（L109-172）、替换 / 删除 / 通知弹窗（L192-240） | 能力与扩展 · Skills |
| MCP 服务器 | `ui/screens/mcp/McpServersScreen.kt` | 顶栏「+」添加（L76-83, L116-）、列表（L85-110）；详情：启用、更新 Token、工具开关（风险工具开启时确认 L340）、删除确认（L401） | 能力与扩展 · MCP 服务器 |
| 角色 | `ui/screens/characters/CharacterLibraryScreen.kt` | 顶栏导入卡片 / 新建（L61-68）、搜索、列表、「我的人设」（L161-175）；详情 / 编辑 / 剧情记忆 | 能力与扩展 · 角色 |
| Linux 工具环境 | `ui/screens/terminal/LinuxEnvironmentScreen.kt` | 状态卡、配置、文件（Workspace / 共享文件夹 / Linux 文件）、可选工具、APK 分析等 | 工具二级页 → Linux 工具环境 |
| 数据备份 | `ui/screens/backup/DataBackupScreen.kt` | 警告卡（L106-113）、导出（SAF CreateDocument，L119-136）、导入（OpenDocument JSON，L137-150）+ 导入确认弹窗（L155-），导出 / 导入调 `agentState::exportBackup/importBackup`（`AgentAppRoot.kt:602-609`） | 通用 · 数据备份 |
| 运行日志 | `ui/screens/diagnostics/DiagnosticsScreen.kt` | **已经用新规范**：自绘 `LogPage`（`DiagnosticsComponents.kt:82-122`，56 高、标题居中、`MovoColors/MovoTypography`），导出、筛选、任务详情、系统事件 | 通用 · 运行日志（可作为新二级页顶栏的参考实现） |

---

## 4. 目标 5 组映射汇总

| 目标分组 | 行 | 来源（本文编号） | 状态 |
|---|---|---|---|
| 模型与对话 | 模型 | #1 | 改图标（品牌 Logo）+ 右侧值 |
| | 语音与唤醒词 | #2 | 直接迁移 |
| | 默认启用思考（开关） | #3 | 直接迁移 |
| | 记忆 | #5 | 需要新数据（右侧值「N 条」） |
| 能力与扩展 | 工具（二级页） | #10–14 + #15；⚠ 加上 #9 / §2 能力目录入口 | 新二级页（新路由） |
| | Skills | #6 | 迁移（设计示例有右侧计数） |
| | MCP 服务器 | #7 | 迁移（右侧「未添加」/ 数量） |
| | 角色 | #8 | 迁移 |
| 系统与权限 | 权限（二级页） | #31–33；⚠ 建议合并 §3.1 P1–P10 | 新二级页（或改造 `AppRoute.Permissions`） |
| | 系统助手（二级页） | #17–24, #26–27；⚠ #25 视 Root 条件 | 新二级页（新路由） |
| | Root 与系统增强 | #16 / §3.2 | 迁移 |
| 通用 | 外观 | #28 / §3.4 | 迁移 |
| | 语言 | #29 | 迁移 |
| | 数据备份 | #30 | 迁移 |
| | 运行日志 | #4 | 从第一组移过来 |
| 关于 | 版本 | 新增 | `BuildConfig.VERSION_NAME` |
| | 源代码（GitHub） | #34 | 迁移，补 runCatching 与外链图标 |

**目标结构中没有明确位置、必须补位的项：**
1. #9 工具能力目录（`AgentToolsScreen`，约 80 张卡片 + 当前设备 / 全部切换 + 权限 / Root 引导）→ 建议作为「工具」二级页里的一行「全部工具能力」。
2. §3.1 权限健康页的 P1、P3–P7、P9（后台运行、麦克风、应用列表、位置、通知使用权、使用情况访问、通知）→ 建议并入「权限」二级页。侧边栏「权限」入口去掉后，否则就没有常规入口了。
3. #25 将 Google App 转为系统应用（Root 条件，与框架无关）→ 建议在系统助手二级页里按 Root 条件显示，或者放进 Root 与系统增强。
4. 侧边栏 `PaneDock` 的「模型 / 工具 / Skills / 权限 / 角色」直达入口（`ConversationSidePaneScaffold.kt:783-790`）→ 设置里都要有对应行（上表已覆盖，前提是 1、2 被采纳）。
5. 语音页「设为默认助理」（RoleManager）与设置 #17「数字助理应用」（系统语音输入设置）→ 两者都保留，或者统一实现后仍然能覆盖两种系统。

---

## 5. 共用组件与页面骨架

| 组件 | 来源 | 用法 / 位置 |
|---|---|---|
| `MiuixScaffoldPage(title, onBack, actions, listState, content: LazyListScope)` | `ui/components/MiuixScaffoldPage.kt:31-82` | 设置主页和几乎所有子页。内部：Miuix `Scaffold` + `TopBarBackdrop` + `AdaptiveTopAppBar` + `MiuixBackButton`；`MiuixScrollBehavior` 的 nestedScroll；`WidePageContent`（宽屏 ≥ 600dp 时内容最大宽 800 居中，`ui/layout/AdaptivePageLayout.kt:20-50`）；`horizontalCutoutPadding`；`captureForTopBar(backdrop)`（顶栏模糊采样）；`scrollEndHaptic()` + `overScrollVertical()`；末尾自动加 `MiuixPageBottomSpacer`（24dp + 导航栏） |
| `MiuixScaffold(...)` 低层骨架 | 同文件 L88-124 | `AgentMemoryScreen` 等需要自己布局的页面 |
| `AdaptiveTopAppBar` | `ui/components/AdaptiveTopAppBar.kt:15-45` | 手机用 Miuix `TopAppBar`（**可折叠大标题**），宽屏用 `SmallTopAppBar`。⚠ 新规范要求 56 高、标题居中、页面内不放大标题（§8.7 二级页顶栏、§9 Q7），和现在的实现冲突；改这个组件会影响全部 20 个使用 `MiuixScaffoldPage/MiuixScaffold` 的页面 |
| `TopBarBackdrop` / `rememberTopBarBackdrop` / `topBarContainerColor` / `captureForTopBar` | `ui/components/TopBarBackdrop.kt` | 顶栏毛玻璃，受外观 A6/A7 控制 |
| `MiuixBackButton` | `ui/components/MiuixBackButton.kt:15-26` | `Icons.AutoMirrored.Rounded.ArrowBack`（新规范要求 chevron-left 24） |
| `PreferenceIcon(icon, enabled)` | `ui/components/PreferenceIcon.kt:12-25` | 24dp、右边距 6、`onBackground` / 置灰色。新规范：主页面图标 20，二级页不放图标 |
| `SmallTitle`（Miuix） | `top.yukonga.miuix.kmp.basic.SmallTitle` | 卡片外的分组标题；新规范改成卡片内的 `Card/Title`。另有包装 `SectionHeader`（`ui/components/SectionHeader.kt`） |
| `Card`（Miuix） | `top.yukonga.miuix.kmp.basic.Card` | 设置主页统一 `padding(horizontal = 12.dp).padding(bottom = 12.dp)` |
| `ArrowPreference` / `SwitchPreference` / `WindowSpinnerPreference` / `OverlayDropdownPreference`（Miuix preference 包） | `top.yukonga.miuix.kmp.preference.*` | 分别对应旧名 SuperArrow / SuperSwitch / SuperSpinner / SuperDropdown；仓库里**没有** SuperArrow / SuperSwitch |
| `BasicComponent`（Miuix） | — | SystemEnhance、DataBackup 警告、Memory 预算这些只读行 |
| `WindowDialog` + `MiuixDialogActions` | Miuix window；`ui/components/MiuixDialogActions.kt` | 系统化确认、缩放输入、清除凭据、删除等；`destructive` 参数 |
| `LanguagePreference` | `ui/components/LanguagePreference.kt` | 设置 #29 |
| `HintText` | **没有共享组件**：`VoiceSettingsScreen.kt:561`、`LinuxFilesScreen.kt:247` 各自私有 | 新规范改为 `Card/Footer` |
| `ItemDescriptionDialog` | `ui/components/ItemDescriptionDialog.kt` | 工具卡说明 |
| 新规范 Token | `ui/theme/MovoTokens.kt`、`MovoIcons.kt` | 目前只有运行日志页（`LogPage` / `LogCard` / `CardNote` / `SectionLabel` / `TopBarIcon`，`DiagnosticsComponents.kt:82-187`）在用，可以抽成设置二级页的共用骨架 |

Toast 用法（新规范 §8.11 写「不用 Toast」，重排时要为这些失败找到就地反馈）：`SettingsScreen.kt` L122（打开助理设置失败）、L389-393 / L799-803（写入失败）、L638-642（强制保持无障碍失败）、L712-716（系统化结果）；语音页 L111；备份页 L59-83、L189-196。

---

## 6. 相关单元测试（`app/src/test`）

没有针对 SettingsScreen 的 Compose UI 测试或截图测试；`androidTest` 只有 `voice/VoiceAcceptanceInstrumentation.kt`。和设置相关的测试有这些：

| 测试 | 覆盖 | 重排时的影响 |
|---|---|---|
| `config/PrefsDefaultsTest.kt` | `BOOLEAN_DEFAULTS` 全表、`LOCAL_AGENT_KEYS` 集合 | 移动开关不改 key 就不受影响；改默认值会失败 |
| `config/PowerAssistantTargetTest.kt` | 电源键目标解析和旧布尔值兼容 | #18 |
| `ui/app/EnhancementSettingsHistoryTest.kt` | 断连快照只做展示、不回写；系统化入口在失去 Root 后仍然保留（#25 的可见性依据） | #18–27、#25 |
| `ui/model/ToolCapabilityProjectionTest.kt` | 当前设备 / 全部过滤、浏览器卡映射、缺普通权限时先去权限页 | §2 |
| `ui/app/ToolCatalogUiTest.kt` | 每个工具 / 卡片都有图标和元数据，顺序不变 | §2 |
| `ui/navigation/AppRouteSerializationTest.kt` | 路由序列化往返（⚠ 列表里没有 VoiceSettings、Diagnostics*；新增的「工具开关」「系统助手」路由需要加进来） | 新路由 |
| `ui/app/LocaleResourcesTest.kt` | 支持的语言选中对应资源、其他回退英文 | #29 |
| `data/model/AppearanceSettingsTest.kt`、`data/repository/AppearanceSettingsRepositoryTest.kt`、`data/model/SettingsTest.kt` | 外观默认值、非法枚举回退、缩放范围；记忆默认开启 | §3.4、记忆 |
| `data/repository/EtaBackupRepositoryTest.kt` | 备份导入导出 | 数据备份 |
| `hook/system/AssistantBindingTest.kt` | 助手绑定（Hook 侧） | 系统助手相关 |

---

## 7. 主要风险

1. **权限项丢入口**：§8.6 去掉侧边栏「权限」，§8.7「权限」二级页只列 3 项，会让权限健康页的 7 项（后台、麦克风、应用列表、位置、通知使用权、使用情况访问、通知）失去常规入口。
2. **工具能力目录丢入口**：`AppRoute.Tools` 是只读目录，不是开关页；目标「工具」二级页没有给目录留位置，侧边栏入口也会被去掉。
3. **可见性规则被简化**：现在有 3 种条件（FW = 在线或曾连接；FW-live 决定能否编辑；Root 或用过系统化 决定 #25）。「未连接框架时只显示数字助理应用」如果直接照做，会藏掉 #25，也会丢掉「断连后置灰但显示上次值」的行为（`EnhancementSettingsHistory`）。
4. **写入语义**：远端开关是同步 commit，失败时回滚并提示；本地开关写完要 reconcile；强制保持无障碍是异步的，有 pending 锁。换成新的开关组件时这些都要保留。
5. **新增行为**：风险开关开启确认（#12、#13，可能还有 #14）；「权限」行和侧边栏的 Rose 汇总提示；版本行；主页右侧值（记忆条数、Skills / MCP 数量、模型名 + Logo）。这些都需要新的数据源。
6. **全局骨架**：把 `MiuixScaffoldPage` / `AdaptiveTopAppBar` 改成居中小标题，会同时影响 20 个页面，还要处理宽屏、`TopBarBackdrop` 模糊（外观 A6/A7）和滑动返回（A8）。
7. **外观开关和固定 Token 冲突**：莫奈、调色板、强调色、纯黑在 `MovoColors` 下可能静默失效。
8. **两个现存的崩溃点**：源代码行（#34）和权限健康页的通知行（P9）都没有 `runCatching`。
