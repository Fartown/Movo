# 对话模式与 Agent 模式切换：调研与实施方案

- 状态：2026-10-21 方案已 review、全部拍板。设计稿定稿 1-1 + 2-1；联网开关定稿 1-1 + 2-2 + 3-1（Figma「25」）。
- 设计稿：Figma「候选 · 对话模式与 Agent 模式（待选）」页（文件 RDCD9OcgkFDkD45kyxtIoM，页面 791:12547）。
- 竞品：[竞品调研.md](竞品调研.md)。
- 路径前缀：`M/` = `app/src/main/kotlin/io/github/fartown/movo/`，`P/` = `app/src/phone/kotlin/io/github/fartown/movo/`。

## 1. 要做什么

- **Agent 模式（默认）**：就是现在的 Movo，带工具、能操作手机、有执行卡。
- **对话模式**：最干净的模型调用。不带工具，不带 Movo 的系统提示词、记忆、技能、环境信息，只把对话发给当前选中的模型。
- **同一个对话里可以随时切换**，从下一条消息生效，之前的内容都保留。
- **新建对话一律从 Agent 开始。**

不在这次范围内：
- 自动判断该用哪种模式（Claude 那种「不设模式」的做法）。
- 对话模式里的只读工具（联网搜索只用服务商自带的 web_search，由对话里的「联网」开关控制）。
- 电视端切换。

## 2. 现状

| 关注点 | 现状 | 出处 |
|---|---|---|
| 请求链路 | 输入框 → `AgentAppState.sendCurrentMessage` → `launchConversationRun` → 跨进程 `AgentRuntimeClient.run` → `AgentRuntimeRunExecutor.execute`（依次加载 skills、记忆、角色、MCP、工具子系统）→ `AgentModelClient.complete` → `AgentLoop.run` → `ProviderClientFactory` 选三种协议之一 | `M/ui/app/AgentAppState.kt:1337,1683`；`M/agent/runtime/AgentRuntimeRunExecutor.kt:64-260`；`M/agent/model/AgentModelClient.kt:84` |
| 系统消息 | 服务商 systemPrompt（为空时用默认文案，默认文案里写着「通过当前可用的工具」）、Movo 身份与设备规则、工具用法、角色、记忆、skills 索引、环境（时间 + 最近应用）、语音段 | `M/agent/model/AgentPromptBuilder.kt:51-150`；`M/data/provider/BuiltinProviders.kt:10-12`；`M/data/repository/RuntimeConfigRepository.kt:91-94` |
| 无工具的现有用途 | `ProviderRequestPurpose` 有 COMPACTION / REPLY_REWRITE / FOLLOW_UP_SUGGESTIONS。只要不是 CHAT，就会**同时**关掉 web_search，并清空 extraBody、customBody | `M/agent/model/AgentProvider.kt:35-54` |
| 没有现成的「干净聊天」 | REPLY_REWRITE 仍带系统提示、记忆、skills，执行器照样建工具子系统；追问建议不走 Runtime、不流式。两者都会丢掉用户的自定义请求体，而不少服务商的思考开关就配在这里面 | `M/agent/model/AgentLoop.kt:111-114`；`M/agent/model/AgentFollowUpSuggester.kt:39-121` |
| 历史存储 | 模型消息一条一行（Room v24 `conversation_model_messages`，分 history / journal / run / snapshot 几个 log），原样存 tool_calls、tool 结果、reasoning、Responses 原始输出 | `M/data/db/ConversationEntities.kt:119-160`；`M/agent/model/AgentConversationCodec.kt:210-213` |
| 历史回放 | 三种协议都原样回放工具调用：Chat 用 tool_calls / tool，Responses 用 function_call / function_call_output，Anthropic 用 tool_use / tool_result | `M/agent/model/OpenAiRequestMessages.kt:14-117` |
| 纯文本投影 | `toLegacyHistory` 只取界面行里 user / assistant 的正文，可以参考它的做法 | `M/ui/app/ConversationRows.kt:273-290` |
| 对话级设置 | 只有思考强度和角色；模型、服务商是全局设置。`ConversationModeUi` 枚举存在，但写死为 Chat，界面也没用到 | `M/ui/model/ConversationUiState.kt:28-33`；`M/ui/app/AgentAppState.kt:1009-1021` |
| 数据库 | Room 24，开了 `fallbackToDestructiveMigration(dropAllTables=true)`：**漏写迁移会清空全部数据** | `M/data/db/MovoDatabase.kt:30,76` |
| 执行中再发消息 | 纯文字作为补充发给当前任务（steer），其他情况排成「下一条」 | `M/ui/app/AgentAppState.kt:1373-1385` |
| 追问建议 | 普通对话、非语音、非角色、非监听触发时才出 | `M/ui/app/AgentAppState.kt:1803-1807` |
| 悬浮球 | 用户发起的任何一轮（包括不操作其他 App 的）都会把常驻球切到执行中，结束显示 ✓。这是按真机反馈特意做的 | `M/agent/runtime/AgentRuntimeService.kt:822-836` |

## 3. 方案

### 3.1 模式存哪、什么时候生效（2026-10-21 实施时调整：不改表结构）

- **新增枚举 `InteractionMode { AGENT, CHAT }`**（`M/agent/model/InteractionMode.kt`）。不复用 `ConversationModeUi`：它的 `Chat` 指的就是现在的 Agent 对话，含义冲突。
- **逐条记录**：用户消息行借用 `tools_json` 列记模式（对话模式写 `{"interaction_mode":"chat"}`，Agent 照旧 `[]`），和「工具前说明」「监听行」借列存附加信息是同一个做法。**不改表结构，没有数据库迁移**；旧版本读到的仍是普通用户消息；备份恢复自然带上。
- **对话的当前模式**：只在界面状态里（`AgentChatUiState.interactionMode`），不单独落库。打开旧对话时取最后一条用户消息的模式，正好就是「恢复它最后用的模式」；新对话是 AGENT。只点了切换、没发消息就离开，下次打开回到最后发消息时的模式。
- **说明行**：按相邻两条用户消息的模式是否不同推导（`modeSwitchesOf`），画在那条用户消息的气泡上方、同一个列表项里，删轮、编辑、重新生成都不会错位。
- **生效粒度**：模式随这一轮的 RunRequest 发出（`AgentRuntimeWire` 加 `interaction_mode`，旧请求没有这一项时按 Agent）。回答中不能切换，标签去掉 ✕、「+」菜单里不出模式两行。重新生成、编辑沿用那条消息当时的模式。
- **联网开关**（拍板 4）：和模式同一个做法。对话模式的用户消息开着联网时写 `{"interaction_mode":"chat","web_search":true}`；对话的开关只在界面状态里（`chatWebSearch`），打开旧对话时取最后一条对话模式消息的开关（中间切回过 Agent 也记得），新对话为关。是否出现看当前模型的服务商是否支持网页搜索（`ModelConfig.supportsHostedWebSearch()`：OpenAI 兼容 + Responses 接口，和服务商详情页「服务端网页搜索」开关出现的条件一致）。发送时这一轮的 `hostedWebSearchEnabled` 在对话模式下改用这条消息的开关，Agent 模式和事件轮照旧用服务商设置；重新生成、编辑沿用那条消息当时的开关。摘要条：对话模式的这一轮步骤全是「网页搜索」（`OpenAiResponsesProvider.WEB_SEARCH_STEP_NAME`）时写「已搜索 N 次 · 用时」。

### 3.2 对话模式的请求：带什么、不带什么

在 `ProviderRequestPurpose` 里新增 `PLAIN_CHAT`，拆开现在合在一起的两个开关：`allowsTools` 为 false，`keepsProviderBody` 为 true。

| 内容 | 对话模式 | 说明 |
|---|---|---|
| tools、tool_choice、Responses 的 tools 字段 | 不带 | 和 Chat 删 tools 字段的现有做法一致 |
| Movo 身份 / 设备规则、工具用法、skills、记忆、环境信息、MCP | 不带 | 执行器直接跳过加载，顺带省掉准备耗时 |
| 服务商 systemPrompt | 只带用户自己填写的 | 不带默认文案（默认文案里写着「工具」）；2026-10-21 拍板 |
| 角色设定 | 带人格、世界书、补充设定、剧情核心记忆（只读） | 去掉工具 / 记忆写入的说明；2026-10-21 拍板 |
| 语音简短回复段 | 语音对话时带 | 2026-10-21 拍板 |
| 模型、stream、思考参数、自定义 header、customBody、extraBody、anthropic-version、ChatGPT 订阅改写 | 带 | 这些属于「怎么调这个服务商」，去掉会让思考开关、鉴权等失效 |
| 服务商自带联网搜索（web_search） | 按对话里的「联网」开关（默认关） | 2026-10-21 拍板；Agent 模式仍按服务商设置 |
| 图片 | 带 | 沿用现有的图片能力判断 |
| 文件引用 | 不带 | 文件要靠终端工具读，界面上不让加，见 §3.5 |

`AgentLoop` 在对话模式下只跑一轮：没有工具调用，也没有收尾的 reply 参数和补充消息。模型如果仍然吐出工具调用（理论上不会），只取其中的文本，并在 RunLog 里记一条。

### 3.3 中途切换：历史怎么发给模型

数据库里的历史不变，仍然原样存。**只在组装请求时，按这一轮的模式做投影**，入口在 `AgentModelClient.complete` 组装消息之前。

- **Agent → 对话**：把 history log 投影成纯文本对话。
  - user 消息保留正文。图片按现有方式用占位文字。监听事件、补充消息等注入的 user 消息也保留正文。
  - assistant 消息只保留正文，去掉 `tool_calls` 和 `reasoningContent`，也不回放 `responsesOutputJson`。
  - 丢掉 tool 角色消息。
  - 正文为空的 assistant 消息（只有工具调用的轮次）整条去掉。中途停掉的 Agent 轮次去掉后会出现相邻的两条用户消息：2026-10-21 用 Agent Plan 实测 Chat Completions 和 Anthropic 接口都接受，不做合并。
  - 压缩产生的摘要本身就在 history log 里，正常保留。
  - **为什么必须投影**：请求里不带 tools 时，带着 tool_use / tool_result 的历史在部分协议上会被拒绝。Anthropic 要求出现这两类块时必须定义 tools，这一点要在实施时用真实后端确认。另外 Responses 的 reasoning 项是绑定上一次请求形态的。投影之后三种协议都不用碰这些问题。
- **对话 → Agent**：对话模式产生的 user / assistant 都是普通文本，原样留在 history 里，Agent 直接接着用，不需要特殊处理。
- **提示缓存**：两种模式的系统前缀不同，切换后那一轮会缓存未命中，这是预期的。同一模式里连续发消息，前缀稳定（对话模式没有系统前缀，只有历史）。

### 3.4 执行路径

对话模式**照样走 Runtime**，不在 App 进程里直连。这样停止、执行服务、后台网络保活、悬浮球、RunLog、断点恢复都和 Agent 一致，也不会重复出现上次「Movo 在后台时连接中断」那类问题。

具体改动：
- `AgentRuntimeRunExecutor.execute` 在开头按模式分支：CHAT 跳过 skills、记忆、MCP、工具子系统，直接调用 `AgentModelClient`，传 PLAIN_CHAT 和投影后的历史。
- 悬浮球保持现状：用户发起的一轮就显示执行中，结束显示 ✓。这是之前按真机反馈定下的，对话模式不例外。

### 3.5 周边行为

| 功能 | 对话模式下 |
|---|---|
| 回答中再打字 | 不转成补充，排成「下一条」，回答完再发。`sendCurrentMessage` 里的 steer 条件要加上「当前这一轮是 Agent」 |
| 监听 | 照常运行。事件触发的一轮固定按 Agent 跑（origin = MONITOR 时强制 AGENT），不影响对话的当前模式。「监听」胶囊照常显示 |
| 追问建议 | 不出。现有条件里加 `mode == AGENT` |
| 上下文压缩 | 照常按预算触发，复用现有压缩器。摘要提示词是「继续任务」的口吻，对纯聊天影响不大，先不改 |
| 语音输入 / 语音对话 | 照常使用当前模式；语音对话时带简短口语回复段 |
| App 外对话浮层 | 和 App 内是同一个 `AgentAppState`、同一套输入框，切换方式相同 |
| 悬浮球展开卡、小爱 / 小布、电视 | 只有 Agent。它们直接构造 RunRequest，固定传 AGENT |
| 附件 | 「+」菜单只留图片。输入框里已经有文件时切到对话，提示「对话模式读不了文件」，由用户决定移除还是留在 Agent |
| 编辑、重新生成 | 沿用那一条用户消息记录的模式 |
| 标题 | 不变，仍取首行 |
| RunLog | `startFields` 加 `mode`；每次请求的 purpose 会显示 PLAIN_CHAT |
| 审批 / 提问 | 没有工具就不会出现 |

### 3.6 界面（等设计稿两个决策定了再细化）

- **决策 1（入口）**：
  - 1-1：「+」菜单顶部加两行单选，对话模式时在输入框的文字行左侧显示「对话 ×」标签。改 `AgentChatFileAttachments.kt`、`AgentChatInputBar.kt`。
  - 1-2：顶栏标题下方加一行模式。改 `AgentAppShell.kt` 里的 `HomeTopBar`。
- **决策 2（中途切换怎么呈现）**：
  - 2-1：在 `ChatMessageItem` / `AgentChatBody` 里按相邻两轮的模式推导，插入一行说明。样式同「监听事件」那类小字行。
  - 2-2：在用户消息下方加标记。
- **固定项**：
  - 占位文字：Agent「交给 Movo 去完成…」；对话「问点什么…」（1-1）或「只聊天，不会操作手机…」（1-2）；对话模式回答中「等回答完再发下一条…」。
  - 回答中隐藏标签的 ×。
  - 文案三语：简体、繁体、英文。
- 定稿后同步更新 `docs/DESIGN_SYSTEM.md` 8.2，以及 Figma 定稿页的新分区。

## 4. 改动清单

- 模型层：
  - `AgentProvider.kt`：新增 PLAIN_CHAT，拆分 allowsTools 和 keepsProviderBody。
  - `AgentModelClient.kt`：按模式组装消息。
  - `AgentPromptBuilder.kt`：对话模式下的系统消息。
  - `AgentLoop.kt`：对话模式只跑一轮。
  - 新增历史投影器 `ChatModeHistoryProjection`，配三种协议的单测。
  - 三个 Provider：确认 PLAIN_CHAT 下不写 tools 字段。
- Runtime：
  - `AgentRuntimeWire.kt`：RunRequest 加字段、序列化、白名单。
  - `AgentRuntimeRunExecutor.kt`：执行分支。
  - 监听入口强制 AGENT。
- App 状态和存储：
  - `AgentAppState.kt`：切换、发送、steer 条件、追问条件、重新生成。
  - `AgentChatUiState.kt`：模式状态。
  - `ConversationRows.kt`：用户消息行借 `tools_json` 记模式和联网开关（不改表结构）。
  - `ConversationRepository.kt`：打开对话时按最后一条用户消息恢复模式、按最后一条对话模式消息恢复联网开关。
- 诊断：`RunLogRecorder.kt` 记录 mode。
- 界面：
  - 输入框、「+」菜单（或顶栏）、消息列表的说明行或标记。
  - `AgentChatUiActions` 加切换动作。
  - 字符串三语。
- 文档：`docs/DESIGN_SYSTEM.md`、本方案的定稿版。

## 5. 拍板结果（2026-10-21）

1. **服务商 systemPrompt**：按建议，只带用户自己填写的，不带默认文案。
2. **角色对话**：按建议，对话模式下保持角色（人设、世界书、补充设定、剧情核心记忆只读），去掉讲工具和写入记忆的话。现状说明见 §5.1。
3. **语音对话**：语音对话时加简短口语回复段。
4. **服务商自带联网搜索**：对话模式加一个「联网」开关（工具栏「思考」右边，只在服务商支持网页搜索时出现），每个对话默认关、记住自己的开关；开着并且搜了网时，搜索进执行卡、摘要条写「已搜索 N 次」。Agent 模式仍跟随服务商设置。
5. **设计稿**：定稿 1-1（「+」菜单切换 + 输入框「对话 ×」标签）+ 2-1（切换处插一行说明），固定方案 F1–F3 照稿。

### 5.1 角色现在怎么用的

- **入口**：设置 → 角色。可以新建角色，也可以导入角色卡（兼容 SillyTavern 的 PNG / JSON 卡）。卡里有人物描述、性格、场景、开场白、对话示例、世界书、深度提示、补充设定（`M/agent/roleplay/CharacterCard*.kt`、`P/ui/screens/settings/SettingsScreen.kt:225-229`）。
- **开始**：在角色详情里开始对话，会新建一个绑定这个角色的对话，标题是角色名，开场白作为第一条回复（`M/ui/app/AgentAppState.kt:1102-1131` `startCharacterConversation`）。
- **请求里有什么**：仍然走 Agent。Movo 的身份说明换成「本会话通过 Movo Agent Runtime 运行角色人格…现实工具操作仍由 Movo 完成」，再加一条角色人格系统消息，内容是人物、性格、场景、示例、世界书、用户人设（`AgentPromptBuilder.kt:64-75,133`；`RoleplayRunContext.kt` `personaPrompt`）。每次请求还会按对话内容匹配世界书，并插入深度提示和补充设定（`projectMessages`）。
- **角色专属**：
  - 工具照常可用。
  - 有角色剧情记忆：模型用 memory_write 写入这个角色的虚构经历，不写现实记忆。
  - 回复可以有多个候选来回切换。
  - 可以「改写回复」（REPLY_REWRITE）。
- **对话模式下怎么处理（建议）**：
  - 带上角色人格、世界书、深度提示和补充设定，因为这些就是用户为这个对话选的“系统提示词”。人格里讲工具和记忆权限的几句，对话模式下去掉。
  - 已有的剧情核心记忆作为只读设定带上。对话模式没有工具，不会写入新的剧情记忆，切回 Agent 后照常写。
  - 不带 Movo 的身份说明、工具用法、现实记忆、skills。
  - 备选：完全不带角色，这样角色对话切到对话模式后就变成和裸模型聊天，开场白之后人物就“没了”。

## 6. 验证计划

- **单测**：
  - 投影器：三种协议 × 历史里有工具调用、中断的工具批次、只有工具调用没有正文、压缩摘要、图片。
  - PLAIN_CHAT 请求体：没有 tools，没有 Movo 系统消息，customBody / extraBody 保留，web_search 按对话里的「联网」开关。
  - steer / 排队条件、追问条件、监听强制 Agent。
- **真实后端**：用仓库根 `.env` 里的凭据，至少测 Ark Agent Plan（Responses）、一个 Chat Completions、一个 Anthropic 兼容。每个跑一遍「Agent 带工具调用 → 切对话 → 切回 Agent」，确认没有 400、上下文接得上。
- **真机**：只用小米，Dora 云真机要独占。
  - 场景：设计稿上的三步（查快递 → 问常识 → 设提醒）、对话模式回答中再打字、有监听时切换、App 外浮层切换、语音对话。
  - 在对话页、App 外浮层、悬浮球交接时检查有没有跳动。
  - 检查 RunLog 里有 mode 和 PLAIN_CHAT。
- 完整回归按惯例：提 PR 前 rebase 到最新 main，跑全量单测。

## 7. 风险

- ~~迁移漏写会清库~~：实施时改为借列存模式，不改表结构，这条风险没有了。
- **投影把依赖工具结果的上下文丢了**：对话模式看不到工具过程，只能看到当时的文字回答。这是「最干净」本身的取舍，设计稿说明里已经写明。
- **对话模式下让它操作手机**：模型只能用文字回答，可能会说「我没法操作」。这是预期行为，不做意图识别。
