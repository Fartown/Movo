# 电视 Agent 实施方案

- 状态：**方案稿，待 review**（2026-10-07）。同意前不改代码。
- 上游：
  - 调研与总体设计：`docs/research/tv-agent/电视端 Agent 设计调研.md`，下称「调研」；
  - 证据：`.docs/tv-agent-design/`；
  - 真机探针报告：`probes/T1`、`T4`、`T4b`、`roundB`、`roundC`、`roundD`、`roundE`、`vision`。
- 原则（用户 10-07 定）：
  - 用通用法：安卓标准接口，加上像人一样按遥控器、看屏幕；
  - 不调各 App 的私有语音接口，不按 App 写特殊逻辑；
  - 不分期，一次做完；
  - 同一个能力用同一个工具名；
  - 测试和验收主要用**哔哩哔哩（云视听小电视）和腾讯视频（云视听极光）**。

---

## 1. 目标与验收指标（B站、腾讯各测）

| 场景 | 现在 | 目标 |
|---|---|---|
| 暂停、继续 | 3–5 轮、9–17 秒；38% 没得到确定成功；单说「继续」会失败（roundC） | 1 轮；说完到生效 ≤2.5 秒；成功率 ≥95% |
| 快进 30 秒、后退一分钟 | 5–8 轮、25–76 秒 | 1 轮。腾讯误差 ≤3 秒（roundC 实测 +27.7 / −59.4 秒，能达到）；B站 ≤10 秒，回答说「大约」 |
| 静音、取消静音、大点声、调到 20 | 静音 = 设 0；「小一点」被调大过 | 1 轮；静音后能恢复原音量；不会反向 |
| 「打开 B站，搜罗翔，放第一个」 | 14 轮失败；隔离重测 3 次只成功 1 次（22 轮、90 秒） | 成功率 ≥90%，≤40 秒（人按遥控器约 20–30 秒） |
| 「腾讯里搜三体，放第一集」 | 没跑通过 | 成功率 ≥80%，≤60 秒 |
| 「选第二个」「往下翻」「返回」 | 靠点节点，页面一变就失败 | 1 轮，焦点按要求移动 |
| 节目台词、旁人说话 | 13 句都被当成指令作答 | 评测集误答率 ≤10%，漏答率 ≤5% |
| 固定提示开销 | 约 7.0k token，其中 1.1–1.3k 是手机内容 | ≤3.5k；没有指向不存在工具的内容 |

## 2. 范围

| 做 | 不做 |
|---|---|
| 按键通道、读屏、焦点导航、屏幕键盘输入、截图找焦点 | 各 App 私有语音接口、私有播放深链、按 App 写死的流程 |
| `ui_observe`、`ui_key`、`ui_focus`、`ui_tap`、`ui_input` 的电视实现 | 切信号源（Q12） |
| `media_control`、`volume_set` 重写 | 语音前端的「是不是对设备说话」分类（先看 `wait_for_user` 的评测结果） |
| 每轮注入电视状态、`wait_for_user`、电视提示词 | |
| 工具目录清理，以及两处通用提示修正 | |

现有的 `video_search` 三家深链、`media_control` 里奇异果专用的动作：通用找片验收达标后，在同一分支删除。

---

## 3. 架构

### 3.1 分层

```
┌────────────────────────── 运行时（main，手机电视共用） ──────────────────────────┐
│ AgentLoop（多一个「静默结束」） · AgentPromptBuilder（基础段可由设备整段替换）     │
│ AgentModelClient（环境信息末尾拼设备状态） · ToolPipeline / ContractTool（不变）   │
└───────────────▲─────────────────────────────────────────────▲─────────────────────┘
                │ Flavor 接口（basePrompt、environmentExtras、    │ ToolContract（不变）
                │ finishingTools、silentFinishTools、toolProviders）│
┌───────────────┴──────────────── 电视（src/tv，只编进电视包） ─┴─────────────────────┐
│ ① 工具层（给模型）                                                                 │
│    ui_observe · ui_key · ui_focus · ui_tap · ui_input                               │
│    media_control · volume_set · wait_for_user · app_open（共用） · end_call          │
│ ② 能力层（电视的「手和眼」，不直接给模型）                                          │
│    TvScreen：读屏快照、页面判断、焦点四邻、付费入口标记                             │
│    TvFocusTracker：找焦点（节点；截图差异 + 视觉校正）                              │
│    TvNavigator：把焦点走到目标（闭环：按一下 → 读焦点 → 纠偏）                      │
│    TvKeyboard：识别屏幕键盘，逐字走键                                               │
│    TvPlayback：选播放器、判断进度可信、选通道、按意图回读                           │
│    TvAudio：音量、静音                                                              │
│ ③ 通道层（和系统打交道）                                                           │
│    TvKeyChannel：LanKeyTransport（6553）→ AdbKeyTransport（本机 ADB）               │
│    ScreenSource：AgentAccessibilityService 节点 · TvAssistantScreenCapture 截图     │
│    VisionClient：用当前配置的模型看图（关思考、缩图），只给 TvFocusTracker、TvKeyboard │
│    MediaSessions（TvMediaAccess） · AudioManager                                    │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

- **工具层**只做三件事：参数解析、组装结果、写清错误。逻辑都在能力层。
- **能力层**是电视的通用操作能力，不认具体 App：
  - `TvNavigator` 被 `ui_focus`、`ui_tap`、`ui_input`、`media_control` 的选集共用；
  - `TvScreen` 被所有工具共用，用来给结果附屏幕摘要。
- **通道层**每个组件都可替换、可模拟：
  - 单测用假的按键通道、假屏幕、假会话；
  - 换别的电视时，只需要加一个新的 `Transport`。

### 3.2 和手机怎么区分

| 层面 | 做法 |
|---|---|
| 工具名 | 同一个能力同一个名字（`ui_observe`、`ui_key`、`ui_focus`、`ui_tap`、`ui_input`、`media_control`、`volume_set`）。模型、历史、评测在两端对得上 |
| 工具实现 | 电视的工具类放在 `app/src/tv/kotlin/.../tv/tools/`，实现同一个 `ToolContract`，只编进电视包。手机的 `UiToolProvider` 和各工具类不动，电视不再装配它们 |
| 装配 | `FlavorModule.toolProviders`（白名单）装电视的 Provider；手机的 `FlavorModule` 不变。两边同名工具不会同时出现 |
| 描述和 schema | 各自一份：描述只写本设备的契约，电视不出现坐标、滑动；手机不出现遥控键。用法分节随工具注入（`PromptSection`） |
| 提示词 | 共用 `AgentPromptBuilder`。电视通过 `PromptProfile.basePrompt` 整段替换基础段；语音段、记忆、环境信息共用 |
| 运行时差异 | 只通过 `Flavor` 接口上的扩展点，共用代码里不写 `if (电视)`。新扩展点都有默认值，手机行为不变 |
| 共用代码的清理 | 现在手机代码里为电视加的分支删掉：`UiToolProvider(includeTouchscreenTools)`、`UiFocusTool`、`UiKeyTool` 里的无触屏分支、`ToolEnvironment.touchscreen` 在 UI 工具里的分支。手机代码回到只管手机 |
| 测试 | 电视单测放 `app/src/testTv/`；手机单测不变。加一个提示词快照测试：手机的系统提示只允许在 Skill 索引、ask_user 两处变化 |

### 3.3 新增的运行时扩展点（main）

| 扩展点 | 位置 | 默认（手机） | 电视 |
|---|---|---|---|
| `PromptProfile.basePrompt: String?` | `platform/PromptProfile.kt`；`AgentPromptBuilder.kt:67-125` 使用 | `null`：沿用现有基础段加 `guidance` | 电视完整的基础段（§5） |
| `Flavor.environmentExtras(context): String` | `platform/Flavor.kt`；`AgentModelClient.kt:116` 拼在环境信息末尾 | `""` | 电视状态（§4.10） |
| `Flavor.silentFinishTools: Set<String>` | `AgentLoop`：这些工具成功后以空回答结束本轮 | 空 | `wait_for_user` |
| `ToolProviderInputs.modelConfig: () -> ModelConfig?` | `AgentToolSubsystem.kt:72` | 不使用 | `VisionClient` 用当前模型看图 |
| 空回答不播报 | `agent/voice/conversation/VoiceConversationController` | 现有行为 | 空回答：不念、不显示、继续听 |

`finishingTools` 已有，电视加上 `media_control`、`volume_set`、`ui_key`、`ui_focus`、`ui_tap`。

### 3.4 一次请求怎么走：「打开 B站，搜罗翔，放第一个」

```
模型 → app_open(B站)                                   回读：前台 = 云视听小电视
模型 → ui_observe                                        节点：顶部导航（首页 4 秒会切预览，工具先按一下上键唤出导航）
模型 → ui_tap(text="搜索")                               TvNavigator 走到「搜索」并确认；结果带回：页面 = 搜索，识别到全键盘
模型 → ui_input(text="LX", stop_when="罗翔")             TvKeyboard 逐字走键；每输一个字读一次结果；结果出现「罗翔说刑法」就停
模型 → ui_tap(text="罗翔说刑法")                         走到结果并确认 → 进入播放页
模型 → media_control(status)（或者看环境里的状态）       确认在播 → 回答「在放罗翔说刑法」
```

约 6 轮模型。每轮 2–3 秒，工具内部的按键和读屏另计，目标 ≤40 秒（人按遥控器约 20–30 秒）。

### 3.5 资源与并发

- 读屏、按键、焦点导航都占 `SCREEN` 资源，串行执行；播放和音量占 `AUDIO`。
- `TvKeyChannel` 是单例，一条长连接，发键串行。按键间隔 ≥50 毫秒；焦点动画多的页面用 100–300 毫秒，由 `TvNavigator` 自适应（roundD）。
- 发键时 Movo 自己的窗口不能有焦点：`TvMainActivity` 在前台时，先把它退到后台（现有的 `hideConversationForDeviceOperation`）；语音胶囊保持 `FLAG_NOT_FOCUSABLE`。
- 6553 和 ADB 注入的键都不会触发 Movo 按键过滤的「你来操作」（roundB），按键过滤不用改。

### 3.6 诊断记录（`MemoryDiagnostics`，只记原始执行，不做汇总）

| 事件 | 字段 |
|---|---|
| `tv.key` | 键、通道、发送耗时 |
| `tv.nav` | 目标、每步方向、焦点前后、是否纠偏、总步数、结果 |
| `tv.vision` | 用途（布局 / 焦点 / 核对）、图尺寸、耗时、token |
| `tv.screen` | 来源（节点 / 截图）、节点数、页面类型 |
| `tv.media` | 动作、通道、前后状态、判定 |

---

## 4. 工具详细设计

通用约定（沿用项目现有合同 `ToolContract`、`docs/research/tool-redesign/Movo 工具重构方案.md`）：

- **返回外壳不变**：`{"status": ok|error|unknown, "data": {...}}`；错误带 `code`、`message`、`retry`、`hint`。错误码用现有的 `ToolErrorCode`，不新增。
- **描述**只写契约（≤160 字）；何时用、何时不用写进用法分节。
- **`data` 只放模型用得上的字段**：用中文可读的值，不放位掩码、代际号、坐标空间。
- **屏幕摘要**：所有会改变屏幕的工具（`ui_key`、`ui_focus`、`ui_tap`、`ui_input`），结果里都附一份 `screen`（格式同 `ui_observe`，只含焦点四邻和变化），模型不必再单独读一次。
- 下面的入参用 JSON Schema 的写法，描述原文就是要放进 schema 的文字（措辞最终按评测定）。

### 4.1 `ui_observe`（读屏）

**描述**：读取当前电视屏幕：前台应用、页面类型、焦点和它上下左右的项、按行编号的可选项；读不到文字时附截图。

**入参**

| 字段 | 类型 | 必填 | 说明（schema 原文） |
|---|---|---|---|
| `screenshot` | boolean | 否 | 「附上截图，默认不附；读不到文字时会自动附上」 |

手机上的 `nodes`、`max_nodes`、`query` 在电视上不提供：电视页面可选项少，一次全给；`query` 在手机上也没实现，见调研 P2。

**出参 `data`**

```json
{
  "observation_id": "t17",
  "app": "云视听小电视",
  "page": "首页",
  "source": "节点",
  "focus": {"id": 7, "text": "搜索"},
  "neighbors": {"up": null, "down": {"id": 12, "text": "推荐"}, "left": {"id": 6, "text": "我的"}, "right": {"id": 8, "text": "年卡限时加赠", "paid": true}},
  "rows": [
    [{"id": 6, "text": "我的"}, {"id": 7, "text": "搜索", "focused": true}, {"id": 8, "text": "年卡限时加赠", "paid": true}],
    [{"id": 12, "text": "推荐"}, {"id": 13, "text": "动画"}, {"id": 14, "text": "影视"}]
  ],
  "keyboard": null,
  "playback": null,
  "screenshot": {"attached": false}
}
```

- `page`：`桌面 / 播放 / 搜索 / 弹窗`，能判断才给，判断不了就不写这个字段。判断规则都是通用的：
  - 前台是桌面包 → 桌面；
  - 前台 App 的会话在播、画面上几乎没有可选项 → 播放；
  - 识别出屏幕键盘 → 搜索；
  - 有对话框窗口 → 弹窗。
- `source`：`节点`或`截图`。只有截图时，`rows` 和 `focus` 由视觉模型识别，并附上截图本身（长边 1280，焦点框画出来）。
- `paid`：文字含「开通 / VIP / 会员 / 购买 / 年卡 / 支付」时标出。
- `keyboard`：识别到屏幕键盘时给出 `{"layout": "全键盘" | "九宫格" | "字母方阵", "box": "当前框里的文字"}`。
- `playback`：前台 App 的会话在播时给出 `{"title", "position", "duration", "state"}`。

**实现**

1. **读节点**：`AgentAccessibilityService`。
   - 读活动窗口和对话框窗口；
   - 可点的容器没有文字时，用子节点的文字作标题；
   - 去掉父子同名；
   - 只保留可聚焦、可点、有文字的项。
2. **排行**：按项的中心纵坐标聚类成行，行内从左到右编号。编号在本次观察内稳定。
3. **焦点**：`findFocus(FOCUS_INPUT)`。没有就看有 `isFocused` 或 `isSelected` 的项。
4. **四邻**：按 AOSP `FocusFinder` 的规则从当前焦点算出上下左右最近的项。这只是参考，真实走向以按键后的回读为准。
5. **截图**：有文字的项少于 5 个，或者找不到焦点时，用 `TvAssistantScreenCapture`（实测 241–359 毫秒）截图，交给 `TvFocusTracker` 识别。
6. **B站首页**：读之前如果只读到 1–2 个节点，而且前台是视频 App 的首页，先按一次上键唤出导航，再读。这条规则不认 App，只看节点数和焦点。
7. **登记**：本次观察登记到 `TvObservationRegistry`（id → 文字、位置、代际），给 `ui_focus`、`ui_tap` 用。

**错误**：

- 无障碍不可用 → `PERMISSION_REQUIRED`；
- 节点和截图都拿不到 → `SOURCE_UNAVAILABLE`，hint 写明原因（没设为默认助理等）。

**资源与风险**：`SCREEN`；`Risk.READ`；`Sensitivity.PRIVATE`（屏幕内容不进持久会话，同手机）。

**用法分节要点**：

- 需要知道屏幕上有什么、选哪一个时用；
- 按键、选择的结果里已带回屏幕变化，不必每步再读；
- 截图里视频画面可能是黑的，不代表没在播，播放状态以 `playback` 或环境里的状态为准。

### 4.2 `ui_key`（按遥控器）

**描述**：按遥控器上的一个键，可以连按几次；返回按完后焦点和屏幕的变化。

**入参**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `key` | string，枚举 `up / down / left / right / ok / back / home / menu / 0`–`9` | 是 | 「要按的键：上下左右、确认、返回、主页、菜单、数字」 |
| `times` | integer 1–10 | 否 | 「连按次数，默认 1；每按一下都会看屏幕，没变化就停下」 |

不提供播放、暂停、快进、音量、静音、电源、信号源键：播放用 `media_control`，音量用 `volume_set`，这样模型没有机会用错。

**出参 `data`**

```json
{
  "key": "down", "pressed": 2, "requested": 3,
  "stopped": "第 3 下屏幕没有变化",
  "screen": {"app": "云视听小电视", "page": "首页", "focus": {"id": 12, "text": "推荐"}, "neighbors": {"...": "..."}, "changes": ["焦点从「搜索」移到「推荐」"]}
}
```

**实现**：

1. 从 `TvKeyChannel` 逐个发键，每发一个等界面稳定（无障碍事件，或最多 400 毫秒）。
2. 读焦点（`TvFocusTracker`），焦点和屏幕都没变就停下，剩下的标「未执行」。
3. 只有截图可用时，用截图差异判断变化（§4.9）。
4. 按 `ok` 前，焦点在标了 `paid` 的项上时，把这一步的 `CallResolution.category` 设为「付款」：手动审批模式会照例问用户，默认模式直接执行。不另加拦截。

**错误**：

- 按键通道都不可用 → `PERMISSION_REQUIRED`，hint：「在 Movo 设置里打开遥控能力」；
- 全部按完都没有变化 → `status=unknown`、`OUTCOME_UNKNOWN`，hint：「换个键，或先读屏」。

**资源与风险**：`SCREEN`；`Risk.LOCAL`。

**用法分节要点**：

- 只用于在屏幕上移动和选择：往下翻、返回上一页、回主页；
- 播放、快进、音量不要用它；
- 没有变化时换个键或先读屏，不要反复按同一个键。

### 4.3 `ui_focus`（把焦点移到某一项）

**描述**：把遥控器焦点移到屏幕上的某一项。返回最终焦点；没移到时说明停在哪、为什么。

**入参**（`id` 和 `text` 二选一）

| 字段 | 类型 | 说明 |
|---|---|---|
| `observation_id` | string | 「`ui_observe` 返回的 observation_id，与 id 一起用」 |
| `id` | integer | 「目标在那次观察里的编号」 |
| `text` | string | 「目标上的文字，如『搜索』『第3集』『S』；没有编号时用」 |

**出参 `data`**

```json
{"reached": true, "steps": 3, "path": ["右", "右", "下"], "screen": {"focus": {"id": 14, "text": "影视"}, "...": "..."}}
```

```json
{"reached": false, "steps": 6, "stopped_at": {"text": "我的"}, "reason": "往左按了 2 次焦点没动，目标可能在另一个区域", "screen": {"...": "..."}}
```

**实现（`TvNavigator`）**：

1. **找目标**：
   - 观察的代际没变时，用 `id`；
   - 页面刷新过（B站首页会自动切预览），就重新读屏，按文字匹配：完全相同优先，然后是包含；
   - 找不到 → `NOT_FOUND`；多于一个 → `AMBIGUOUS`，列出候选。
2. **规划**：比较焦点和目标的位置，先走主方向。同一行的左右、同一列的上下，按格数估计次数。
3. **闭环**：每按一下就读焦点；偏了就按新位置重新规划。上限 20 步、15 秒。
4. **卡住处理**：同一方向连续 2 下焦点没动，就换副方向绕一下；还不行就停下，报告停在哪。不反复按同一个无效键（TVWorld 的失败模式）。
5. **只有截图时（腾讯）**：见 §4.9。

**错误**：

- 目标不在屏幕上 → `NOT_FOUND`，hint：「先 `ui_key` 翻页或读屏」；
- 没走到 → `status=unknown`（`OUTCOME_UNKNOWN`），附停下的位置。

**资源与风险**：`SCREEN`；`Risk.LOCAL`。

### 4.4 `ui_tap`（移过去并确认）

**描述**：把遥控器焦点移到屏幕上的某一项并按确认，用来打开、选中，或在屏幕键盘上点这个键。返回按完后的屏幕。

**入参**：同 `ui_focus`，另加：

| 字段 | 类型 | 说明 |
|---|---|---|
| `effect` | string，枚举 `pay` | 「这一下是付费、开会员时声明」；手机的 `send / delete / submit / transfer` 在电视上不提供 |

**出参 `data`**：

```json
{"reached": true, "confirmed": true, "steps": 3, "screen": {"app": "云视听小电视", "page": "搜索", "keyboard": {"layout": "全键盘", "box": ""}, "changes": ["进入搜索页"]}}
```

**实现**：

1. 先走 `ui_focus` 的流程；
2. 走到后核对一次焦点确实在目标上（只有截图时请视觉模型核对，§4.9）；
3. 按 `ok`，读屏，报告变化。

目标是付费入口（`paid`），或者模型声明了 `effect=pay`：按现有权限两档处理，手动模式会问用户。

**错误**：

- 没走到就不按确认，返回 `OUTCOME_UNKNOWN`；
- 按了确认但屏幕没变化 → `status=unknown`，hint：「先读屏确认」。

### 4.5 `ui_input`（在搜索框里输入）

**描述**：在当前的搜索框里输入字母或数字，返回框里实际的文字和结果区前几项。

**入参**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `text` | string | 是 | 「要输入的字母或数字；中文片名传拼音首字母，如『三体』传 ST」 |
| `clear` | boolean | 否 | 「先清空框里原有的字，默认 true」 |
| `stop_when` | string | 否 | 「结果里出现这段文字就停止输入，如片名」 |

**出参 `data`**

```json
{"typed": "LX", "box": "LX", "stopped_early": true, "results": [{"id": 31, "text": "罗翔说刑法"}, {"id": 32, "text": "罗翔"}], "screen": {"...": "..."}}
```

**实现（`TvKeyboard`）**：

1. **先试直接写入**：焦点框是可编辑节点时，用 `ACTION_SET_TEXT` 写入，回读框里的文字和结果是否刷新。B站的搜索框是 EditText，但焦点在键盘上，这条路还没实测过，§8。
2. **不行就在屏幕键盘上逐字走键**：
   - 识别键盘：节点里有约 26 个单字母可点项且排成网格（B站），或者截图交给视觉模型识别一次布局（腾讯，§4.9）；
   - 得到「字母 → 位置」表；
   - `clear=true` 时，先走到「清空」或「删除」键清掉原有的字；
   - 每个字母用 `TvNavigator` 走过去，按确认，确认框里的文字多了这个字母；
   - 每输一个字母读一次结果区，出现 `stop_when` 就停。
3. **不用**：6553 的文字包、ADB 输入文字（roundB 实测写不进 B站）。

**错误**：

- 没识别出键盘 → `NOT_FOUND`，hint：「先 `ui_tap` 进入搜索页」；
- 某个字母没打进去 → `OUTCOME_UNKNOWN`，附已输入的部分。

### 4.6 `media_control`（播放控制）

**描述**：控制前台正在播放的视频：暂停、继续、快进或后退若干秒、跳到某时间、从头播、上一集、下一集，或读取播放状态。返回操作前后的片名和进度，以及是否确认生效。

**入参**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `action` | 枚举 `status / pause / resume / forward / backward / seek_to / restart / next / previous` | 是 | 「要做的操作；status 只读状态」 |
| `seconds` | integer 1–3600 | 否 | 「快进或后退的秒数，默认 30」 |
| `position` | string | 否 | 「跳到的时间，如 20:00 或 1:05:30」 |

去掉 `toggle`；`play` 改名为 `resume`。

**出参 `data`**

```json
{"action": "forward", "confirmed": true, "channel": "媒体会话",
 "before": {"title": "三体", "position": "12:10", "duration": "45:00", "state": "播放中"},
 "after": {"title": "三体", "position": "12:41", "duration": "45:00", "state": "播放中"}}
```

```json
{"action": "forward", "confirmed": true, "channel": "按键", "approx": true,
 "before": {"position": "05:33"}, "after": {"position": "06:03"}, "note": "按了 3 下右键并确认"}
```

**实现（`TvPlayback`）**：

1. **选播放器**：只认属于前台 App 的会话（`TvMediaAccess`）。前台不是视频播放 → `NOT_FOUND`「现在没有在播放，前台是 X」，`retry=user`。
2. **读状态，判断进度是否可信**：进度 = 会话进度 + (现在 − 更新时间) × 速度；间隔约 1 秒读两次，播放中应前进约 1 秒（±0.5 秒）。腾讯可信，B站会话为空。
3. **已经是目标状态**：直接 `ok`，`data.note`「已经是暂停状态」。
4. **选通道**：

   | 动作 | 会话可信且声明支持 | 否则：按键（`TvKeyChannel`，每按一下读一次） |
   |---|---|---|
   | 暂停 / 继续 | `transportControls.pause()` / `play()`（roundC：腾讯 2/2） | 媒体键 127 / 126；无效时（B站），在控制栏收起时按确认，回读状态 |
   | 快进 / 后退 / 跳到 / 从头 | `seekTo(目标)`（roundC：腾讯误差 ≤3 秒） | 进度用无障碍读屏幕上的时间文字。① 第一下方向键唤出控制栏；② 每下读一次预览时间，到目标附近停；③ 5 秒内按确认提交（roundE：B站每下 10 秒，误差 ≤0.17 秒）；④ 读进度确认。控制栏收起时不按确认，否则会变成暂停 |
   | 上 / 下一集 | `skipToNext` / `skipToPrevious` | 按下键调出选集栏，`TvNavigator` 走到当前集的下一项并确认 |

5. **按意图回读判成败**：
   - 快进 N 秒 → 进度前进 N±3 秒（按键通道 ±10 秒，`approx=true`），播放还是暂停不管；
   - 暂停 → 状态变成暂停；
   - 换集 → 片名或时长变了，或进度归零。
6. **广告和会员**：
   - 会话 `actions=0`、进度不动 → `status=error`，`SOURCE_UNAVAILABLE`「片头广告中，结束后才能控制」，`retry=later`；
   - 按键后屏幕出现 `paid` 提示 → 按一次返回关掉，`UNSUPPORTED`「这个片子未开会员不能快进」。

**错误汇总**：

| 情况 | 码 | retry |
|---|---|---|
| 前台没在播 | `NOT_FOUND` | user |
| 广告中 | `SOURCE_UNAVAILABLE` | later |
| 两条通道都不支持、会员限制 | `UNSUPPORTED` | never |
| 发出了但回读没确认 | `OUTCOME_UNKNOWN` | observe |

**资源与风险**：`AUDIO` + `SCREEN`（按键通道时）；`Risk.LOCAL`；一轮结束（带 `reply`）。

### 4.7 `volume_set`（音量）

**描述**：设置或调整电视音量，静音或取消静音。返回调整前后的音量和是否静音。

**入参**（三选一）

| 字段 | 类型 | 说明 |
|---|---|---|
| `percent` | integer 0–100 | 「把音量设到这个值」 |
| `change` | 枚举 `up / down` | 「大一点或小一点，每次 5」 |
| `mute` | boolean | 「true 静音，false 取消静音」 |

手机的 `stream` 在电视上不提供，只调媒体音量。

**出参 `data`**：

```json
{"before": {"volume": 16, "muted": false}, "after": {"volume": 21, "muted": false}}
```

**实现（`TvAudio`）**：

- 设值、加减：`setStreamVolume(STREAM_MUSIC, …, FLAG_SHOW_UI)`，回读；
- 静音、取消静音：`adjustStreamVolume(ADJUST_MUTE / ADJUST_UNMUTE, FLAG_SHOW_UI)`。roundC 验证：普通 App 调用生效，重复调用不反转，有 TCL 静音图标，取消后回到原音量；
- 静音状态下调大，会自动取消静音，返回里写明；
- 不发静音键，不用「设 0」代替静音。

**资源与风险**：`AUDIO`；`Risk.LOCAL`；一轮结束。

### 4.8 `wait_for_user`（不回应）

**描述**：这句话不需要回应时调用（节目声音、旁人聊天、不是对你说的话），不出声，结束这一轮。

**入参**：无。**出参**：`{"status": "ok"}`。

**可用性**：只在语音轮出现（`env.spokenReply`）。

**实现**：

- 工具本身什么也不做；
- 运行时把它列入 `Flavor.silentFinishTools`：`AgentLoop` 见到它成功，就以空回答结束本轮，不再请求模型；
- 语音链路不念、不显示，会话继续听；
- 历史里保留这句话和这次调用，后面的对话能看到「上一句被忽略了」。

**用法分节要点**：

- 明显是节目台词、广告，或家里人互相说话时调用；
- 用户在对你说话，哪怕说得不清楚，不要调，简短请他再说一遍。

### 4.9 截图找焦点（`TvFocusTracker` + `VisionClient`，`ui_*` 内部共用）

依据：`probes/vision/report.md`（33 对截图，差异判断 0 判错；视觉模型键盘行列 100%，焦点 94–97%）。

| 步骤 | 做法 | 参数 |
|---|---|---|
| 识别布局（一次） | `VisionClient` 看图，返回可选项的文字、行列和当前焦点（JSON） | 图长边 1280；关思考；约 2.8 秒 |
| 每按一键 | 截图（约 0.3 秒）→ 和上一张做差异 → 找出新出现的平整高亮块，当作新焦点 | 工作宽度 960；像素阈值 40；最短边 ≥40 像素；颜色标准差 ≤22；约 30 毫秒（Mac） |
| 判断 | 变化面积 >35% → 换页，重新识别布局；旧焦点框变化 <4% → 焦点没动（键没生效）；位移不是朝按键方向一格，或者有歧义 → 只问焦点 | 只问焦点：图 960，约 1.7 秒 |
| 确认前 | 在目标上按确认前，请视觉模型核对一次焦点，顺带确认是不是付费入口 | — |

- **分工**：视觉模型只给文字和行列；位置以本地高亮框为准，因为模型给的坐标误差大。
- **截图里会有 Movo 自己的胶囊**：语音胶囊 v5（movo-74，用户已定稿）截图时不再撤下，胶囊在左下角，内容会轮播。处理：
  - 差异判断前，把胶囊**可能占的最大矩形**抹掉，不参与差异。不用窗口矩形：窗口是 696×520dp，里面还包括给反问小卡留的空白，盖得太多。
    - 胶囊左距屏幕 64dp，底距屏幕底 80dp，高 56dp，宽度 56–664dp 随内容变化，换内容时有 200–300 毫秒的宽度动画；
    - 在这台电视上（1920×1080，density 1.5）最大矩形是 x 96–1092、y 876–960 像素；
    - 反问小卡出现时（宽 560dp、最高约 420dp），把它在胶囊上方的区域也盖上；
    - 数值由 `TvVoicePanel` 按 dp 换算后提供，不在别处写死像素；
    - 数据来自 movo-74，已用实际截图核对；
  - 送给视觉模型的图，同样把这块盖上或裁掉，避免模型把胶囊里的字当成屏幕内容；
  - 给主模型看的截图，在 `ui_observe` 的说明里写明「左下角是 Movo 自己的提示，不是电视内容」。
  
  胶囊盖住的那一小块正好是可选项时（少见），`ui_observe` 以节点为准，不受影响。
- **VisionClient 用当前配置的模型**（`deepseek-v4-1-flash` 实测支持图片），通过 `ToolProviderInputs.modelConfig` 拿配置，单独发一次请求，不进对话历史。

### 4.10 每轮注入的电视状态（`environmentExtras`）

```text
电视现在：前台 云视听极光；在播《三体》第 2 集 12:10 / 45:00（已暂停）；音量 16，未静音。
```

- 「在播」只在会话属于前台、而且进度可信时写；否则写「前台在放视频，进度读不到」或省略。
- 只读会话和音量，不读屏幕，预算 ≤50 毫秒，超时省略。
- 依据：roundC 里单说「继续」两次都失败了，因为新对话里模型不知道当前是暂停的。

### 4.11 其余工具

| 工具 | 变化 |
|---|---|
| `app_open` | 并入按名搜索：找不到时返回相近候选；别名补优酷、芒果（`AndroidAppToolBackends.kt:68-79`） |
| `memory_read` / `memory_write` | 不变；用法分节统一读写顺序 |
| `end_call`、`web_search` | 不变；提示词要求语音回答不念来源 |
| `weather`（新增） | 入参：`city`（默认用记忆里的所在城市）、`day`（今天、明天、后天）；出参：天气、气温、降水概率。数据源待定（§8） |
| 移除 | `ui_scroll`、`ui_wait`、`app_search`、`clipboard_*`、`conversation_read`、`setting_read`、`device_read`、Skill 索引 |

---

## 5. 提示词

- **电视基础段**（`TvPromptProfile.basePrompt`，替换手机基础段）要点：
  - 场景：客厅电视，用户离屏幕两三米、用语音说话，你的话会被念出来；
  - 先判断要不要回应（`wait_for_user`）；
  - 做事顺序：
    - 播放、音量、打开应用、天气用专门工具，一次办完；
    - 屏幕上的事（找片、选择、翻页、关弹窗）读屏后用 `ui_*`；
    - 找片的固定顺序：打开 App → 搜索入口 → 输入首字母 → 选结果 → 选集 → 确认在播；
  - 歧义：做最可能的那个，并带出理解到的参数；只有付费才先问；
  - 按键结果以回读为准，没变化不反复按；
  - 慢的工具调用前先说半句；被纠正时先照做，不辩解。
- **语音段**只保留一处长度规则。
- **通用修正**（手机也受益）：
  - Skill 索引只在 `skill_read` 可用时注入；
  - ask_user 那一行的替换条件改为「ask_user 不在目录里」；
  - 记忆读写顺序的措辞统一。
- **固定开销目标** ≤3.5k token。措辞用评测定稿。

---

## 6. 改动清单

| 位置 | 改动 |
|---|---|
| `app/src/tv/.../tv/tools/`（新） | `TvUiToolProvider` 和五个 `ui_*` 工具类、`TvMediaControlTool`（替换 `TvMediaControl.kt`）、`TvVolumeTool`、`TvWaitForUserTool`、`TvWeatherTool` |
| `app/src/tv/.../tv/control/`（新） | `TvScreen`、`TvObservationRegistry`、`TvFocusTracker`、`TvNavigator`、`TvKeyboard`、`TvPlayback`、`TvAudio` |
| `app/src/tv/.../tv/channel/`（新） | `TvKeyChannel`、`LanKeyTransport`、`AdbKeyTransport`、`ScreenSource`、`VisionClient` |
| `app/src/tv/.../tv/TvPromptProfile.kt`、`flavor/FlavorModule.kt` | `basePrompt`；工具白名单换成电视 Provider；`finishingTools`、`silentFinishTools`、`environmentExtras` |
| `app/src/tv/.../tv/TvSettings.kt` | 「遥控能力」状态行：6553 可用 / ADB 可用 / 都不可用时怎么开 |
| `app/src/main/.../platform/PromptProfile.kt`、`Flavor.kt` | 新扩展点（默认值不影响手机） |
| `app/src/main/.../agent/model/` | `AgentPromptBuilder`（基础段替换、Skill 条件）、`AgentModelClient`（环境拼接）、`AgentLoop`（静默结束） |
| `app/src/main/.../agent/tools/AgentToolSubsystem.kt` | `ToolProviderInputs.modelConfig` |
| `app/src/main/.../agent/tools/ui/` | 删除电视专用的分支：`UiFocusTool`、`includeTouchscreenTools`、无触屏分支；修 `ui_wait.gone`、`query`、`until_text` |
| `app/src/main/.../agent/tools/meta/`、`memory/` | 措辞修正 |
| `app/src/main/.../agent/model/AgentLoop.kt` | 旧错误格式统一为 `status / retry` |
| `app/src/main/.../agent/voice/conversation/` | 空回答不念、不显示 |
| `app/src/testTv/` | 见 §7 |

---

## 7. 单测（JVM，不依赖真机）

| 对象 | 用例 |
|---|---|
| `TvKeyChannel` | 长连接、断线重连、键间隔、电源键拒绝、ADB 回退 |
| `TvScreen` | 卡片标题聚合、父子去重、排行、四邻计算、付费标记、页面判断；用真机 dump 的 xml（B站、腾讯、TCL 桌面）做夹具 |
| `TvNavigator` | 用假焦点模型（网格、Leanback 行记忆、边界、丢键、卡住）：走到、绕行、上限、`NOT_FOUND`、`AMBIGUOUS`、页面刷新后按文字重找 |
| `TvKeyboard` | 全键盘和九宫格夹具：字母表、清空、逐字确认、`stop_when` |
| `TvFocusTracker` | 用 `probes/vision` 的 33 对截图做回归：判错数为 0 |
| `TvPlayback` | 假会话：可信、不可信、广告、后台、已是目标状态；按键通道（假控制栏：唤醒、预览、确认、5 秒收起） |
| `TvAudio` | 静音幂等、静音时调大自动取消 |
| 工具层 | 各工具的 schema 快照、`data` 格式、错误码与 retry |
| 运行时 | 静默结束；`finishingTools`；环境拼接 |
| 提示词快照 | 电视一份；手机一份，只允许 Skill、ask_user 两处变化 |

---

## 8. 评测

- **评测集**：约 150 句，放 `.docs/tv-agent-design/eval/`。
  - 播放、音量（含易混说法）；
  - 找片（B站、腾讯各 10 部）；
  - 「选第二个」类；
  - 问正在看的；
  - 环境声（取自真机记录）；
  - 纠正和骂人；
  - 打开应用。
- **模拟设备**：在 `.docs/agent-perf-review/lane2/tv_sim.py` 基础上，加入 B站和腾讯的实测行为：
  - B站：有节点、首页 4 秒切预览、会话为空、控制栏显示时右键 +5 秒；
  - 腾讯：没有节点只有截图、会话可信、左右键不可用；
  - 加上丢键、付费入口。
- **指标**：动作对不对、有没有反向结果、轮数、模型耗时、回答字数、`wait_for_user` 的误答和漏答。
- **门槛**：按 §1。不达标就改措辞或工具重测，结果如实写进本方案。

## 9. 真机验收（B站、腾讯）

- **全链路**：打开 → 搜索 → 选片 → 播放 → 暂停 / 继续 / 快进 / 后退 / 换集 → 静音 / 取消静音 / 调音量 → 返回桌面。每个 App 走 3 遍。
- **单句**：§1 每个场景各 3 次。
- **环境声**：放节目台词，再让家人说一句和电视无关的话。
- **记录**：说完到生效的耗时，和人按遥控器对比。
- **约束**：测试前和语音会话（movo-74）约时间，每次只有一方占用电视；测 Movo 的步骤里不穿插 `uiautomator dump`。

## 10. 风险

| 风险 | 应对 |
|---|---|
| 6553 发键不稳定 | 已查清根因（发键时自己的窗口抢了焦点）；实现上保证发键时 Movo 窗口没有焦点；断网或服务缺失时退到 ADB |
| 腾讯只能靠截图，逐步导航慢 | 布局只识别一次，之后用截图差异定位焦点，视觉模型只做校正；实测每步耗时，超标就把能在节点上做的先做 |
| 换基础提示词让其他能力退化 | 评测集覆盖；提示词快照单测 |
| `wait_for_user` 吞掉用户的真问题 | 漏答门槛 ≤5%；用法分节写明「不清楚就请他再说一遍」 |
| 视频 App 改版 | 只用通用规则，不依赖具体界面；评测和验收定期重跑 |

## 11. 验证状态

| 项 | 状态 |
|---|---|
| 普通 App 经 6553 发键的稳定方式 | **已完成**（roundD）：从没有窗口的服务发键，开 NODELAY，0 丢键 |
| Movo 进程里的助理截图在腾讯能否稳定取到 | **已完成**（roundE）：Movo 是默认助理；文字入口 4/4 带回 1920×1080 截图，每次 241–359 毫秒，描述正确。B站视频层是黑的，只有叠加层。语音任务的第一张截图失败：语音任务要求排除 Movo 自己的包，排除名单不为空时不走助理截图，退到了无障碍截图，而 Android 9 上没有无障碍截图。已在 fix/tv-voice-duck `fdafd24` 修复并经语音验证（截图 339 毫秒），方案实现以该修复为前提 |
| 截图差异定位焦点、视觉模型识别键盘布局的准确率和耗时 | **离线已完成**（`probes/vision/report.md`）：差分 33 对 0 判错（960 宽，跟踪上一步焦点，Mac 上约 30 毫秒）；视觉模型键盘行列 100%，焦点 94–97%（会把青色「正在播」样式当成焦点），只问焦点约 1.7 秒。组合方案每步约 0.72 秒，「ST + 选第 2 项」约 21–25 秒。待上电视补测：差分耗时、按键后界面稳定时间、其他区域的焦点样式 |
| B站完整播放页的按键快进精度 | **已完成**（roundE）：每下 10 秒，要按确认提交，误差 ≤0.17 秒。首页导航唤出的稳定性放到验收时一起看 |
| B站搜索框（EditText）能否用无障碍 `ACTION_SET_TEXT` 直接写入并刷新结果 | 待测（实现时第一个验证） |
| 截图差异在电视上的实际耗时、按键后界面稳定时间、联想列表和选集区的焦点样式 | 待测（实现时验证） |
| `weather` 的数据源（不需要登录、国内可用） | 待选 |


## 12. 提交与合入

- 一个分支，按模块分提交：
  1. 通用修正；
  2. 按键通道；
  3. `ui_observe`、`ui_*`（电视实现）；
  4. `media_control`、`volume_set`；
  5. 状态注入、`wait_for_user`；
  6. 提示词、工具目录；
  7. 评测和验收记录。
- 全部验收达标后一起合入。每次提交前 rebase 到最新 main，并重跑全量单测。
