# 电视端语音 App 改造调研（Android 9 起）

> 实测补充：TCL Android 9 的录音、唤醒原型和性能数据见 [P0 历史记录](P0真机验证-TCL-ak30a5.md)。正式产品已采用无障碍浮窗、系统助理截图与媒体会话，日常运行不依赖 ADB；当前权限要求及真机结果见[实施方案](../../solutions/tv-voice-app/电视端语音App实施方案.md#当前交付与验证2026-10-06)。本文中的早期路线比较不代表当前产品实现。

> 修订记录：2026-10-05 首版按 Android 5（API 21）调研；同日用户更正目标为 **Android 9（API 28）**，全文按 API 28 重写。首版里“依赖要锁旧版”“唤醒词原生库加载不了”“需要打包 Conscrypt”等结论在 Android 9 上**不再成立**，已删除。

| 项目 | 内容 |
|---|---|
| 对象与目的 | 把 Movo 改造成电视端语音 App：用遥控器操作、用语音下指令、由 Agent 控制电视；最低兼容 Android 9（API 28） |
| 类型 | 可行性调研 + 路线建议（不含实施方案细节，不改产品代码） |
| 范围内 | 现有代码可复用度、降到 minSdk 28 的改动量、遥控器适配、电视端语音输入、第三方 App 控制电视的能力边界、推荐工程结构与分期 |
| 范围外 | 具体界面设计（按规范先出 Figma 稿）、工作量排期、其他品牌电视的逐款适配 |
| 代码快照 | §4.1 lint 和工具路径基于 main `cec6f88`（已含工具重构）；§3、§5 的代码量和界面行号基于 `a73b203`（fix/ui-review），2026-10-05 |
| 证据等级 | **实测**：本轮在本机执行验证；**源码/文档**：附链接或 AOSP 行号；**未验证**：只有社区资料或推断，必须上真机确认 |

## 1. 结论先行

1. **Android 9 下工具链和依赖基本不用动。**
   - AndroidX 目前统一要求 minSdk 23，2026-07 起预告提到 24，都低于 28。Compose、Room、DataStore、lifecycle、activity 用现在的版本就行。
   - 唯一卡住的是 miuix-blur（minSdk 33），而电视界面本来也不会用 miuix。
   - `java.time`、`java.nio.file`、`java.util.Base64` 都是 API 26 就有的，不需要脱糖。
   - Let's Encrypt 根证书 Android 7.1.1 起就受信任，不需要额外打包 Conscrypt。
2. **本地唤醒词的原生库在 Android 9 上能加载（实测 ELF）。** sherpa-onnx 自带的 onnxruntime 是按 API 27 编译的，低于 28。前提是电视上有能常开的麦克风，见 §6。
3. **降到 minSdk 28，代码改动量不大（在 main 上 lint 实测）。** 一共 147 处 NewApi 错误：
   - 50 处在手机专属代码里（界面、Xposed、终端等），电视版用不上，不用改。
   - 电视版要用的共享代码里有 97 处，大多是机械替换：`getParcelable` 换 `BundleCompat`，前台服务换 `ServiceCompat`，`PackageManager` 用旧版重载，无障碍动作按版本过滤。
   - 真正有功能差异的只有无障碍截图（API 30/34），见 §4.1。
4. **工程结构推荐用同一模块的 product flavor（`phone` / `tv`），不推荐拆模块。** 原因：
   - 全仓有 1203 处 Kotlin `internal` 声明，分布在 457 个文件里（实测）。`internal` 只在模块内可见，拆模块要大面积改可见性。
   - flavor 可以分别设置 minSdk：`phone` 保持 34，`tv` 设为 28。手机专属代码放进 `src/phone`，就不用为 28 做兼容。
5. **界面必须重写。** 现有 `ui/` 和悬浮层约 4.8 万行，深度依赖触摸，全仓没有任何方向键或焦点处理。电视界面用最新版 Compose + `androidx.tv:tv-material`。
6. **遥控器语音键，第三方能接一部分（AOSP 9 源码实证）。**
   - `KEYCODE_VOICE_ASSIST`：系统会 `startActivity(RecognizerIntent.ACTION_WEB_SEARCH)`，第三方注册这个 Intent 就能被拉起。
   - `KEYCODE_ASSIST`：在电视上走 `launchLegacyAssist`，只查找**系统应用**的 VoiceInteractionService，第三方接管不了。
   - 小米是否改过这套分发、语音键实际发的是哪个键码，都要真机验证。
7. **最大的未知仍是麦克风。** 遥控器麦克风通常只在按下语音键时由系统开麦，第三方能不能录到，没有查到资料。Android 9 的限制有两条：
   - 录音还是独占的（Android 10 才允许共享）。
   - App 在后台时不能用麦克风，必须开前台服务。
8. **控制电视：Android 9 比 Android 5 多了手势和截图，但方向键还是要靠 ADB。**
   - 无障碍服务在 28 上可以做手势（API 24），但注入不了方向键和 OK（要 API 33），也不能直接截图（要 API 30）。
   - 截图可以用 MediaProjection，需要用户在弹窗里确认。
   - 完整的按键能力要走 scrcpy 的做法：用户一次性打开 ADB 调试，App 在本机自连 ADB，拉起一个 shell 身份的常驻进程。
   - Shizuku 在 Android 9 上只能接电脑启动（官方文档），所以要自己实现本机自连。
   - Android 9 没有后台启动 Activity 的限制（Android 10 才有），也没有包可见性限制（Android 11 才有），从后台打开应用、列出已装应用都比手机上容易。
9. **下一步：先拿一台 Android 9 的小米电视做真机探针（§10），再写实施方案。**

## 2. 目标拆解

| 用户诉求 | 拆成的技术问题 | 本文位置 |
|---|---|---|
| 电视端语音 App | 声音从哪来、按哪个键开始说、识别和播报用什么 | §6 |
| 适配电视遥控器 | 只用方向键 + OK + 返回就能完成所有操作；焦点要清楚可见；文字输入的替代方案 | §5 |
| 对电视进行控制 | 第三方 App 在 API 28 上能调哪些接口，各需要什么授权 | §7 |
| 兼容 Android 9 | 依赖、原生库、高版本 API 调用怎么兼容 | §4 |

## 3. 现有代码能带走多少

主代码共 110,066 行（`wc -l` 实测）。

| 类别 | 范围 | 行数 | 说明 |
|---|---|---|---|
| **复用** | `agent/model`（AgentLoop、三种协议的 Provider、SSE、JSON Schema 校验） | 8,078 | 没有一个文件 import android |
| | runtime、MCP、工具框架、存储（Room / DataStore） | 约 2 万 | 依赖版本不用动，只需给少量高版本 API 加分支 |
| | 语音：豆包流式 ASR、Dialog SDK 播报、sherpa 唤醒 | 约 4,000 | 原生库在 28 上都能加载；前台服务类型（API 29）等要加分支 |
| **改造** | 设备和界面工具（main 上在 `agent/tools/device`、`agent/tools/ui`） | — | 应用列表只查 `CATEGORY_LAUNCHER`，要补 LEANBACK_LAUNCHER（`agent/tools/device/AndroidAppToolBackends.kt:18-20`）；按键只能发 BACK/HOME/RECENTS/ENTER/NOTIFICATIONS/QUICK_SETTINGS（`agent/tools/ui/UiRealBackends.kt:294-306`），没有方向键 |
| | `agent/accessibility`、`agent/device` | 约 5,500 | 截图用了 API 34 的 `takeScreenshotOfWindow`（`AgentAccessibilityService.kt:1068`）；要加焦点导航 |
| | 会话层和界面的耦合 | — | `VoiceSessionManager` 依赖 `ui.app.AgentAppSession`（`agent/voice/session/VoiceSessionManager.kt:5,12-13`）；`AgentRuntimeService` 混着 Compose 悬浮窗 |
| **重写** | `ui/`、`agent/overlay` | 48,037 | Compose + miuix，全仓 DPAD/`focusable`/`onKeyEvent` 为 0 处 |
| **电视版不带** | hook 9,224、terminal 等约 10,000、browser 2,555、Root 控制 1,703、个人数据工具、systemizer 等 | 约 26,600 | 手机或特定 ROM 专属，放进 `src/phone` |
| **新写** | 换台、切信号源、遥控按键注入、视频 App 深链、焦点导航 GUI Agent | — | 全仓目前 0 处 |

现有工具在电视上的去留（工具名以 main 的新工具体系为准）：

| 去留 | 工具 |
|---|---|
| **直接可用** | `volume_set`、`media_control`、`ui_wait`、`device_read`、`memory_read` / `memory_write`、`mcp_find` / `mcp_call`、`ask_user`、`tool_search`、`conversation_read` |
| **需改造** | `app_open` / `app_search`（补 LEANBACK）、`ui_key`（补方向键等电视键）、`ui_observe`（截图换实现）、`ui_tap` / `ui_swipe` / `ui_scroll`（`dispatchGesture` 在 28 上可用，但电视 App 对触摸的响应要实测）、`ui_input` |
| **无用** | `personal_search`、`sms_code_read`、`health_read`、`usage_read`、`wifi_password_read`、`terminal_*`、`browser_*`、`file_*`、`skill_*`、`clock_create`（电视上一般没有闹钟应用） |

## 4. 兼容 Android 9 要改什么

### 4.1 高版本 API 调用（lint 实测）

测法：基于 main `cec6f88` 建临时工作树（不碰任何在途分支），把 minSdk 改成 28，跑 `./gradlew :app:lintDebug`，只开 `NewApi` 和 `InlinedApi` 两项检查。为了能完成清单合并，临时给 miuix-blur 加了 `tools:overrideLibrary`。

结果：**147 处 NewApi 错误**，另有 43 处 InlinedApi 警告，原始报告见同目录的 [lint-newapi-minSdk28.txt](lint-newapi-minSdk28.txt)。InlinedApi 是编译期内联的常量，通常不会崩溃。按电视版是否需要这部分代码来分：

| 归属 | 模块 | NewApi 数 | 处理 |
|---|---|---|---|
| **电视版也要用（共 97）** | agent/accessibility | 36 | 见下表 |
| | agent/voice | 18 | |
| | agent/runtime | 16 | |
| | data | 7 | |
| | agent/tools/device | 6 | |
| | res（styles.xml） | 6 | |
| | agent/tool（个人数据和旧设备工具） | 4 | 个人数据部分可以挪进手机 flavor |
| | agent/tools/personal | 2 | 同上 |
| | agent/device、agent/tools/clockmedia | 各 1 | |
| **只给手机（共 50）** | ui 27、hook 11、agent/overlay 7、agent/terminal 3、systemizer 2 | 50 | 挪进 `src/phone`，不用改 |

共享代码的 97 处大多是机械替换（行号以 main `cec6f88` 为准）：

| 类型 | 处数 | 典型位置 | 改法 |
|---|---|---|---|
| `Bundle`/`Intent.getParcelable(key, Class)`（33） | 10 | `agent/runtime/AgentRuntimeWire.kt:358`、`AgentWireText.kt:40`、`AgentConversationHandoff.kt:68` | 换成 `BundleCompat` / `IntentCompat` |
| `PackageManager` 带 `XxxFlags.of()` 的重载（33） | 14（lint 把同一次调用算两处） | `agent/tools/device/AndroidAppToolBackends.kt:20,97,157`、`agent/voice/SystemSpeechRecognizer.kt:30-32` | 低版本用 int flags 的旧重载 |
| 带类型的 `startForeground`（29） | 3 | `AgentExecutionService.kt:44`、`MovoWakeWordService.kt:187`、`MovoAssistantVoiceService.kt:91` | `ServiceCompat.startForeground` |
| `AccessibilityNodeInfo.getUniqueId` / `getSource`（33） | 9 | `AgentAccessibilityService.kt:320,340,1480,...` | 低版本用自己生成的节点 ID |
| 无障碍动作常量（29/30/34） | 16 | `AgentAccessibilityService.kt:880,1352,1418,1545-1548,2354-2370` | 按版本过滤可用动作 |
| 无障碍截图（30/34） | 6 | `AgentAccessibilityService.kt:1075,1163-1164` | 低版本走 MediaProjection 或 C 档常驻进程（§7） |
| 后台启动 Activity 的选项（34） | 6 | `AgentRuntimeService.kt:1854-1858`、`MovoAssistantVoiceService.kt:134-136` | 加版本分支；28 本来就没有这个限制 |
| `LocaleManager` / `LocaleConfig`（33） | 7 | `data/repository/LanguageSettingsRepository.kt:11-27` | 低版本改用 AppCompat 的应用语言接口，或自己保存 |
| 语音识别相关（31/33/34） | 11 | `MovoRecognitionService.kt:114-123`、`SystemSpeechRecognizer.kt:18-19`、`OnDeviceRecognitionWakeEngine.kt`（已是死代码）、`MovoWakeTileService.kt`（电视用不上） | 加分支，或只放进手机 flavor |
| 启动页 / 深色主题属性（29/31/33） | 6 | `res/values/styles.xml:6-26` | 挪到 `values-v31` 等资源目录 |
| 其他 | 9 | `AccessibilityProtectionClient.kt:116-119`（34）、`AgentAccessibilityService.kt:300`（30）、`AgentConversationHandoff.kt:89,108`（34/29）、`AgentFileReferenceGateway.kt:279`（29）、`ClockMediaBackends.kt:245`（29） | 加分支 |

注意：lint 只能发现编译期能看到的调用，反射和清单属性要靠真机回归兜底。实施时 main 还会继续变，动手前要在最新 main 上重跑一遍。

### 4.2 依赖

| 依赖 | 当前 | AAR minSdk | 28 上可用？ |
|---|---|---|---|
| Compose（随依赖传递 1.12.0-rc01）、activity 1.13.0、lifecycle 2.11.0、Room 2.8.4、DataStore 1.2.1、navigationevent 1.1.2、core 1.18.0 | — | 23 | 可用 |
| miuix ui / nav / preference | 0.9.4-rc01 | 24 | 可用 |
| **miuix-blur** | 0.9.4-rc01 | **33** | **不可用**。手机 flavor 照用；电视 flavor 不依赖它 |
| libxposed api / service | 102.0.0 | 26 | 可用，但电视版不需要 |
| OkHttp | 5.4.0 | 21 | 可用 |
| tv-material | 未用 | 最新版 23 | 可用 |
| sherpa-onnx | 1.13.8 | 21（.so 按 27 编） | 可用 |
| 豆包 speechengine_tob | 0.0.15.0 | 18（arm64 的 audioeffect 需要 24） | 可用 |

AndroidX 在 2025-08 前后统一提到 minSdk 23（b/380448311），并预告 2026-07 起多数库提到 24（[版本总表](https://developer.android.com/jetpack/androidx/versions/all-channel)），对 28 都没有影响。

### 4.3 原生库（本轮用 NDK llvm-readelf 实测）

| 库 | 编译 API | API 28 上能否加载 |
|---|---|---|
| `libonnxruntime.so`（v7a、arm64） | 27（`.note.android.ident` = 0x1b） | 能 |
| `libsherpa-onnx-jni.so` | 21 | 能 |
| `libspeechengine.so`、`libaudioeffect.so` | 18 / 21（arm64 的 audioeffect 引用了 API 24 的 `__read_chk`） | 能 |
| `libmovo_pty.so`、`libproot_*`（终端） | 34 | 不能。终端只放进手机 flavor |

### 4.4 Android 9 自身的行为限制

| 限制 | 影响 | 来源 |
|---|---|---|
| App 在后台时不能用麦克风和摄像头 | 常驻录音（唤醒词）必须开前台服务。现有 `MovoWakeWordService` 已经是前台服务 | [Android 9 行为变更](https://developer.android.com/about/versions/pie/android-9.0-changes-all) |
| 录音独占（Android 10 起才允许共享） | 小爱同学占着麦克风时我们录不到；我们常驻录音时，系统语音也录不到 | [共享音频输入](https://developer.android.com/media/platform/sharing-audio-input) |
| targetSdk ≥ 28 默认禁止明文 HTTP | 调用小米 6095 本机接口时，要在 Network Security Config 里放行 `127.0.0.1` | Network Security Config（API 24 起） |
| 非 SDK 接口限制（从 Android 9 开始） | 现有 `hiddenapibypass` 能用 | — |
| **没有**后台启动 Activity 的限制（Android 10 才有） | 从后台服务直接打开其他 App 是可行的，比手机上简单 | [后台启动限制](https://developer.android.com/guide/components/activities/background-starts) |
| **没有**包可见性限制（Android 11 才有） | 不需要 `QUERY_ALL_PACKAGES` 就能列出所有已装应用 | — |
| 没有 RoleManager（API 29） | 不能用系统接口申请成为默认助理，见 §6.2 | — |

## 5. 遥控器适配

官方 TV 规范（[TV 入门](https://developer.android.com/training/tv/start/start)、[控制器](https://developer.android.com/training/tv/get-started/controllers)）：

- **Manifest**：
  - 声明 `LEANBACK_LAUNCHER` intent-filter。
  - `android.software.leanback` 和 `android.hardware.touchscreen` 都设 `required=false`。
  - banner 图 320×180，上面要带文字。
- **操作**：只用上下左右、OK、返回、Home 就能完成所有操作。OK 键要同时处理 `DPAD_CENTER` 和 `ENTER`。
- **返回**：返回键不能当开关用，连续按返回最终要回到主页。
- **焦点**：每个可操作元素都要有清楚的焦点态（放大、描边、提亮）。焦点顺序可预期，进入页面时要有默认焦点，不能出现焦点丢失。

Movo 特有的问题：

| 现状 | 电视上的问题 | 方向 |
|---|---|---|
| 会话列表靠横向拖出（`ConversationSidePaneScaffold.kt:192`） | 遥控器没有拖拽 | 改成左侧常驻导航，或用菜单键打开 |
| 消息菜单只能长按（`ChatMessageItem.kt:1781`） | 长按 OK 可以用，但用户很难发现 | 改成焦点进入消息后，按菜单键或显示操作按钮 |
| 页面滑动返回、点遮罩关闭 Sheet | 遥控器做不了 | 统一用返回键 |
| 设置里要输入 API Key、Endpoint | 用遥控器打字非常痛苦 | 建议电视显示二维码，手机扫码打开电视本地网页填写；或者从手机版 Movo 推送配置 |
| 悬浮窗：无障碍开启时用 `TYPE_ACCESSIBILITY_OVERLAY`，否则用 `TYPE_APPLICATION_OVERLAY`（`AgentRuntimeService.kt:1658-1664`） | 两种在 28 上都能用。但电视的设置里可能没有“显示在其他应用上层”的授权入口 | 优先走无障碍悬浮窗；授权入口在 §10 里验证 |

UI 技术：电视 flavor 用当前版本的 Compose + `androidx.tv:tv-material`。Leanback 已经 deprecated，而且偏向“海报墙”式浏览，不适合对话界面。

可以和手机共用的界面部分：消息的 Markdown 渲染、对话数据模型和 ViewModel 逻辑。页面骨架、导航和交互组件都要按焦点体系重做。按设计规范，电视界面要先在 Figma 出稿评审，再写代码。

## 6. 语音输入

### 6.1 拾音来源

| 来源 | 可行性 | 证据 / 说明 |
|---|---|---|
| 遥控器麦克风 | **未验证** | 官方说 TV “完全支持遥控器麦克风”（[硬件](https://developer.android.com/training/tv/get-started/hardware)）。小米蓝牙遥控器走 ATVV 协议，主机要在按下语音键后才发 MIC_OPEN 开麦（[ATVV 示例](https://github.com/wenchenxi/tv-remote-voice-keyboard)）。第三方 AudioRecord 能否触发开麦、录到声音，**未查到** |
| 电视远场麦克风 | 部分机型有 | 小米 2019 年起有远场机型（如电视 5 系列），具体是否跑 Android 9 未查到。有远场麦克风才谈得上唤醒词 |
| USB 麦克风 | 可行 | Android 支持 USB 音频录音（[AOSP USB 音频](https://source.android.com/docs/core/audio/usb)） |
| 手机当麦克风 | 可行 | 两种做法：手机版 Movo 录音后通过局域网推给电视；或者手机直接识别成文字再发给电视（更简单） |

### 6.2 怎么开始说话（AOSP `android-9.0.0_r1` 源码实证）

| 按键 | 系统行为 | 第三方能否接管 |
|---|---|---|
| `KEYCODE_VOICE_ASSIST`（231） | 系统消费这个键，抬起时 `startActivity(new Intent(RecognizerIntent.ACTION_WEB_SEARCH))`（`PhoneWindowManager.java:6377-6385`、`:6663-6681`） | **能**：注册 `android.speech.action.WEB_SEARCH` 的 Activity 即可。有多个处理者时由用户选默认 |
| `KEYCODE_ASSIST`（219） | 电视上走 `SearchManager.launchLegacyAssist`（`PhoneWindowManager.java:4288-4291`），只查找带 `MATCH_SYSTEM_ONLY` 的 VoiceInteractionService（`SearchManagerService.java:275-301`） | **不能**：只认系统应用。即使用 ADB 把我们设成默认助理也没用 |
| `KEYCODE_SEARCH`（84） | 下发给前台应用（`PhoneWindowManager.java:3742-3755` 返回 0） | 我们在前台时能收到 |
| 任意键（如长按菜单） | 无障碍服务开启按键过滤（`canRequestFilterKeyEvents`） | 能在全局拦截，但不要影响其他 App 的正常按键 |

以上是 AOSP 的行为，小米 ROM 可能改过。例如语音键实际发的键码、是否被小爱直接接管，都要真机验证。

### 6.3 识别、播报与唤醒

- **ASR**：直接复用豆包 SAUC 双向流式 WebSocket（`agent/voice/asr/DoubaoBidirectionalAsrEngine.kt`）。
- **播报 / 连续对话**：直接复用豆包 Dialog SDK（`agent/voice/conversation/DoubaoDialogEngine.kt`）。电视用外放，插话打断依赖 SDK 自带的回声消除，效果要实测。
- **唤醒词**：sherpa-onnx 在 28 上能跑。但要满足两个条件：电视上有能常开的麦克风（远场或 USB）；而且我们常驻录音会把系统语音（小爱）的麦克风占掉。首期建议按键说话，唤醒放到后面做成可选项。

## 7. 控制电视：能力分层

第三方 App，不 root、没有系统签名，在 API 28 上能拿到的能力，按需要用户授权的程度分四档：

| 档位 | 前提 | 能做的 | 做不了的 |
|---|---|---|---|
| **A. 免授权** | 无 | 打开应用（`getLeanbackLaunchIntentForPackage`，拿不到时退回 `getLaunchIntentForPackage`；28 上从后台也能启动）；音量（`adjustStreamVolume`，部分盒子是固定音量，设了不生效）；媒体键（`dispatchMediaKeyEvent`）；视频 App 深链（腾讯 `tenvideo2://?action=9&search_key=`、酷喵 `ykott://tv/search`）；切 HDMI（`TvContract.buildChannelUriForPassthroughInput`，要厂商实现了 TV Input Framework） | 方向键 / OK、换台、操作其他 App 的界面 |
| **B. 用户开无障碍** | 在设置里手动开启 | 读其他 App 的界面节点树、找到当前焦点、对节点执行 CLICK / FOCUS / SCROLL；全局返回 / 主页 / 最近任务 / 锁屏（锁屏是 API 28）；**手势 `dispatchGesture`**（API 24） | **注入方向键 / OK**（`GLOBAL_ACTION_DPAD_*` 到 API 33 才有，AOSP 9 源码里没有）；直接截图给 App（`takeScreenshot` 是 API 30；28 的 `GLOBAL_ACTION_TAKE_SCREENSHOT` 只会存进相册） |
| **B+. 用户同意录屏** | MediaProjection（API 21），系统弹窗确认 | 截图 / 录屏，给 GUI Agent 当“眼睛” | 电视 ROM 可能没有这个确认弹窗，要验证 |
| **C. 用户一次性打开 ADB 调试** | 开发者模式里打开 ADB 调试，第一次连接时在电视上点“允许” | App 用 ADB 客户端库（libadb-android，或 dadb；dadb 需要 API 26，28 上能用）连本机 `127.0.0.1:5555`，拉起一个 shell 身份的常驻进程，做法和 scrcpy 一样：用 `app_process` 启动，通过隐藏 API `InputManager.injectInputEvent()` 注入按键。这样**任意按键都能发**（方向、OK、换台、信号源、菜单），也能截图。还可以 `pm grant WRITE_SECURE_SETTINGS`，让 App 自己打开无障碍 | 电视重启后常驻进程会消失，要自动重连拉起。Shizuku 在 Android 9 上只能接电脑启动（[官方](https://shizuku.rikka.app/guide/setup/)：无线调试要 Android 11），所以不能直接依赖它 |
| **D. 小米专属** | 看机型和固件 | 6095 端口 HTTP 接口（`/controller?action=keyevent&keycode=…`）；`com.xiaomi.mitv.tvplayer/.ExternalSourceActivity --ei input 23/24/25` 切 HDMI 1/2/3 | **本机能否调 6095、这个 Activity 是否对外开放，都未验证** |
| **做不到** | — | — | HDMI-CEC（`HdmiControlManager` 需要系统签名）、读取全部频道 EPG（`ACCESS_ALL_EPG_DATA` 是系统权限）、系统级注入（`INJECT_EVENTS`） |

另一条注入路线：做一个自带的输入法，用 `InputMethodService` 发按键（Key Mapper 在 Android 11 以下就是这么做的）。但它要求用户把我们设为默认输入法，而且按键只发给前台焦点窗口。只能作为 C 档拿不到时的退路。

**C 档的依据（本轮核实）**：
- scrcpy README 原文：“The Android device requires at least API 21 (Android 5.0).”
- scrcpy develop.md 原文：服务端“executed as `shell` on the Android device”，用“the hidden method `InputManager.injectInputEvent()`”注入事件。

**电视上的 GUI Agent 要换思路**：
- 手机上是“看截图 → 点坐标”；电视 App 大多靠焦点驱动，而且是自绘界面，应该改成“读节点树 + 当前焦点 → 发方向键移动焦点 → 按 OK”。
- 动作执行：节点可以点击时，直接对节点 ACTION_CLICK（B 档）；否则发方向键（C 档）。手势点击（B 档）只作为补充，因为很多电视 App 不处理触摸，要实测。
- 观察：截图走 MediaProjection（B+ 档）或 C 档。
- 第三方 TV App 的无障碍节点质量（尤其是自绘界面和 WebView）没有资料，要实测。

**工具清单的方向**：
- `ui_key` 扩成电视键集：方向键、OK、返回、主页、菜单、频道 ±、音量 ±、静音、信号源、媒体键。
- 新增：
  - `tv_input_switch`：切信号源。
  - `video_play`：按片名调视频 App 的深链，搜索或直接播放。
  - 焦点导航：可以做成 `ui_key` + `ui_observe` 的组合用法，也可以单独做一个工具，留到方案阶段定。
- `app_open` / `app_search` 补上 LEANBACK。

## 8. 推荐工程结构

```
:app（单模块，两个 product flavor）
├─ src/main     共用：agent/model、runtime、工具框架、语音、存储、MCP、对话逻辑
│               └─ 必须兼容 API 28：高版本调用加 SDK_INT 分支
├─ src/phone    minSdk 34：现有 miuix 界面、Xposed hook、终端、浏览器、Root、个人数据工具
└─ src/tv       minSdk 28：Compose + tv-material 焦点界面、LEANBACK 入口、电视工具（A/B/C/D 四档）、扫码配置
```

选这个方案的原因：
- **不拆模块**：全仓 1203 处 `internal` 声明（457 个文件），拆模块要大面积改可见性；flavor 在同一模块内，不受影响。
- **手机版不受影响**：`phone` flavor 的 minSdk 仍是 34，手机专属代码不用做任何兼容。
- **不要单 APK 同时跑手机和电视**：那样手机专属代码也得兼容 28，电视包里还会带上 Xposed、终端这些无用代码。
- **两边接口不同的地方**（如 `VoiceSessionManager` 依赖的 `AgentAppSession`）：在 main 里抽一个接口，两个 flavor 各自实现。

基线：工具重构已经在 main 合入（`cec6f88`），工具代码在 `agent/tools/` 下。实施时以 main 为基线，先把在途分支（如 `fix/ui-review`）合进去，再挪文件、抽接口，避免大面积冲突。

## 9. 分期路线

| 阶段 | 内容 | 出口 |
|---|---|---|
| **P0 真机探针** | 做一个 minSdk 28 的探针 APK，在 Android 9 小米电视上逐项验证 §10 | 每一项给出“可行 / 不可行 / 有条件”，据此决定拾音和控制的主路径 |
| **P1 工程拆分** | 加 `phone` / `tv` 两个 flavor；手机专属代码挪进 `src/phone`；main 里的高版本调用加分支；把界面耦合抽成接口 | 手机版单测和真机回归全部通过；`tv` 变体能编译、lint 无 NewApi 错误 |
| **P2 电视 MVP** | `tv` flavor：首页 + 语音面板 + 对话 + 设置（扫码配置），按键说话，豆包 ASR/TTS；A 档和 B 档工具 | 能说出“打开云视听极光”“音量调到 20”“返回主页”并执行成功 |
| **P3 深度控制** | C 档常驻进程（按键 + 截图）、焦点导航 GUI Agent、视频 App 搜片播放、切信号源、小米专属适配 | 能说出“在腾讯视频里搜狂飙并播放”“切到 HDMI 1”并完成 |
| **P4 可选** | 唤醒词（有远场或 USB 麦克风时）、手机当麦克风、新系统上走更好的接口（API 33 的 DPAD 全局动作、API 30 的截图） | 视 P0 结论决定 |

每个阶段动手前，都要先出实施方案给你评审。

## 10. 待真机验证清单（P0 探针）

| # | 验证项 | 方法 | 决定什么 |
|---|---|---|---|
| 1 | 遥控器语音键的键码，以及系统会发出什么 | `getevent`、`dumpsys input`；探针注册 `ACTION_WEB_SEARCH`，看按语音键能否被拉起；无障碍 `onKeyEvent` 记日志 | 能否用语音键唤起我们 |
| 2 | 第三方 AudioRecord 能否录到遥控器麦克风 | 被语音键拉起后马上录音并回放；对比 VOICE_RECOGNITION、MIC 两种音源 | 拾音主路径是遥控器，还是 USB / 手机 |
| 3 | 本机 ADB 自连 | 探针内置 ADB 客户端连 `127.0.0.1:5555`，看授权弹窗，执行 `input keyevent` 和 `screencap` | C 档能否成立 |
| 4 | shell 常驻进程注入按键的延迟和稳定性 | 参考 scrcpy 用 `app_process` 起一个 dex，循环注入 DPAD，计时；再测重启后能否自动重新拉起 | C 档的体验 |
| 5 | 小米 6095 接口能否在本机调用 | `curl 127.0.0.1:6095/controller?action=keyevent&keycode=down` | D 档能否作为免 ADB 的按键通道 |
| 6 | 切 HDMI | `TvInputManager.getTvInputList()` 列出直通输入，再 ACTION_VIEW；同时试 `ExternalSourceActivity` | `tv_input_switch` 的实现 |
| 7 | 无障碍节点质量 | 在 PatchWall 桌面、云视听极光、银河奇异果、设置里各 dump 一次节点树和焦点 | 焦点导航 GUI Agent 能否只靠节点树 |
| 8 | `dispatchGesture` 在电视 App 上是否生效 | 对桌面和视频 App 的按钮坐标发点击手势 | 手势能否作为 B 档补充 |
| 9 | MediaProjection 的确认弹窗、悬浮窗授权、通知使用权、无障碍设置入口是否存在 | 逐个调起系统授权页 | 哪些授权要靠 ADB `pm grant` / `appops` 代劳 |
| 10 | 音量是否固定 | `adjustStreamVolume` 前后读一次音量，同时听实际声音 | `volume_set` 是否可靠 |
| 11 | 录音独占冲突 | 小爱在听的时候我们开录音，反过来再试一次 | 唤醒词能否常驻 |
| 12 | Compose + tv-material 的性能 | 放一个列表页加焦点动画，记录帧耗时和内存 | 低端电视上的流畅度 |

## 11. 需要你拍板的决策

1. **工程结构。** 推荐同一模块加 `phone` / `tv` 两个 flavor（§8），手机版保持不动。如果你打算彻底只做电视，也可以直接把 `phone` 删掉。
2. **真机。** 需要一台 Android 9 的小米电视或盒子来做 P0。请告诉我具体型号和系统版本，以及是不是蓝牙语音遥控器、有没有远场麦克风。
3. **拾音退路。** 如果遥控器麦克风拿不到，你接受哪种方式：USB 麦克风、手机当麦克风，还是两种都做？
4. **控制深度。** 是否接受“用户要先打开 ADB 调试”这个一次性门槛，来换取任意按键和截图（C 档）？不接受的话，Android 9 上只能做到开应用、调音量、点节点、发手势，在其他 App 里没法用方向键移动焦点。

## 12. 主要来源

- Android TV：[入门](https://developer.android.com/training/tv/start/start) · [控制器](https://developer.android.com/training/tv/get-started/controllers) · [硬件](https://developer.android.com/training/tv/get-started/hardware) · [共享音频输入](https://developer.android.com/media/platform/sharing-audio-input)
- Android 9：[行为变更](https://developer.android.com/about/versions/pie/android-9.0-changes-all) · [后台启动 Activity](https://developer.android.com/guide/components/activities/background-starts)
- AOSP `android-9.0.0_r1` 源码：[PhoneWindowManager](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r1/services/core/java/com/android/server/policy/PhoneWindowManager.java) · [SearchManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r1/services/core/java/com/android/server/search/SearchManagerService.java) · [AccessibilityService](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r1/core/java/android/accessibilityservice/AccessibilityService.java) · [AccessibilityService 13（DPAD 动作）](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/core/java/android/accessibilityservice/AccessibilityService.java) · [TV 架构](https://source.android.com/docs/devices/tv) · [HDMI-CEC](https://source.android.com/docs/devices/tv/hdmi-cec)
- 按键注入参考：[scrcpy README](https://github.com/Genymobile/scrcpy/blob/master/README.md) · [scrcpy develop.md](https://github.com/Genymobile/scrcpy/blob/master/doc/develop.md) · [libadb-android](https://github.com/MuntashirAkon/libadb-android) · [dadb](https://github.com/mobile-dev-inc/dadb) · [Shizuku 启动方式](https://shizuku.rikka.app/guide/setup/) · [Key Mapper](https://keymapper.app/user-guide/actions/)
- 小米：[6095 接口整理](https://sumygg.com/2022/08/05/xiaomi-tv-6095-port-http-api/) · [2026 年接口整理](https://gddhy.net/2026/xiao-mi-dian-shi-api/) · [视频 App 深链](https://github.com/zj1wang/xiaomi_tv) · [遥控器键值](https://dev.mi.com/docs/gameentry/TV&%E7%9B%92%E5%AD%90%E6%B8%B8%E6%88%8F%E6%8E%A5%E5%85%A5%E6%96%87%E6%A1%A3/TV&%E7%9B%92%E5%AD%90%E9%81%A5%E6%8E%A7%E5%99%A8%E9%94%AE%E5%80%BC%E5%AE%9A%E4%B9%89/) · [TCL 切 HDMI 示例](https://github.com/ian20040409/TCL-Android-TV-HDMI)
- 依赖：[AndroidX 版本总表](https://developer.android.com/jetpack/androidx/versions/all-channel)
