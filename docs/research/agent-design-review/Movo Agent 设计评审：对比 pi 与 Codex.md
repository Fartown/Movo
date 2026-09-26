# Movo Agent 设计评审：对比 pi 与 Codex

| 项目 | 内容 |
|---|---|
| 日期 | 2026-09-24 |
| Movo 快照 | `59535ed` |
| pi 快照 | earendil-works/pi `898ab80`（0.87.1），本地 `reference/pi` |
| Codex 快照 | openai/codex `0a2eb46`，本地 `reference/codex` |
| Aether 快照 | Zhou-Shilin/Aether `b59e3b5`，本地 `reference/aether`（2026-09-24 补充对比） |
| DeepSeek Harness 快照 | deepseek-ai/deepseek-harness `477b4f4`（dsh-v0.1.7-rc.2），本地 `reference/deepseek-harness`（2026-09-25 补充对比） |
| 方法 | 静态阅读源码，与 pi、Codex、Aether、DeepSeek Harness 的实现对照；未调用真实接口，未运行 Aether；dsh 只用 `--dump-config` 核对过默认插件树 |

## 结论

Movo 的 Agent Loop 本身设计扎实。问题集中在五个方面：
- 模型接口适配
- 安全边界
- 上下文成本
- 上下文连续性
- 工程形态

其中有 3 处协议 bug 很可能导致请求失败，建议优先修复并做真实接口实测。

做得好的地方（与 pi/Codex 一致或更严格）：

- 输出被截断（`length`）时整批拒绝执行工具调用；在未知终止原因下返回的工具调用一律拒绝。
- 执行前按本轮下发的 JSON Schema 校验参数；校验失败以结构化结果回喂给模型。
- GUI 操作用 `observation_id` 拦截过期节点；动作结果不确定时要求先重新观察。
- 截图只在消费它的那一轮模型请求中保留。
- 中断后为未完成的工具调用补上 `TOOL_INTERRUPTED` 结果，不会自动重放。
- 重试等待可以取消；托管工具已开始执行后不再重试。
- Skills 采用渐进披露：系统提示里只放索引，正文按需读取。

五个问题域：

1. **协议正确性（P0）**：Responses 推理条目回放、Anthropic thinking 回放、Anthropic `max_tokens=4096`。
2. **安全边界（P1）**：工具全部默认开启、执行前没有逐次确认、存在持久化注入通道。
3. **上下文成本（P1）**：有 Root 的 ColorOS 设备上暴露 81 个工具（schema 约 14.5k token），外加约 2.8k token 的系统提示；Anthropic 请求没有显式缓存断点。
4. **连续性（P2）**：MCP 和敏感工具的结果在跨轮历史和上下文压缩中被整体抹除。
5. **工程形态（P3）**：内部消息没有类型，持久化状态源过多。

## 设计对比

四者都不设回合上限，也都不会因用户插话而中断当前工具批次。主要差异在安全、缓存、工具数量和消息模型。DeepSeek Harness（dsh）一列为 2026-09-25 补充，详见后文「补充对比：DeepSeek Harness」和[DeepSeek Harness 技术分析报告](DeepSeek%20Harness%20技术分析报告.md)。

| 维度 | Movo | pi | Codex | dsh |
| --- | --- | --- | --- | --- |
| 回合上限 | 无 | 无 | 无；有 token / rollout 预算 | 无；相同调用重复 3/5/8 次时追加提醒 |
| 工具并行 | 全部串行 | 默认并行，写文件按路径加锁 | RwLock：只读工具拿读锁并行，写工具拿写锁独占 | 工具声明并发安全才并行，上限 10；结果按模型顺序提交 |
| 中途插话 | 整批工具结束后注入 | 同左，不跳过工具 | 下次采样前注入；另有 interrupt | step 边界注入；另有不唤醒的 inject |
| 内置工具数 | 约 81 个，另加 MCP 最多 64 个 | 8 个，默认开 4 个 | 少量核心工具 + `tool_search` 延迟暴露 | 目录 65 个，默认模式约 25 个 |
| 审批与沙箱 | 无逐次确认，工具默认全开 | 无，要求用容器隔离 | 四层防护，默认拒绝 | 文件写沙箱，不限读取和网络；只有一次性审批 |
| 系统提示 | 约 2.8k token，一整段拼接 | 约 500 token，XML 分节 | 15–21 KB，按模型选模板；环境信息只注入变化部分 | 插件片段组装，作为日志 0 号节点；首轮未缓存输入约 47.6k token（含工具，Composio 实测） |
| 提示缓存 | Anthropic 无 `cache_control`；OpenAI 系只靠服务端自动缓存，无 `prompt_cache_key` | 自动设 3 个缓存断点 + `prompt_cache_key` | `prompt_cache_key` + 稳定前缀 | 无显式断点；系统提示、工具增删、环境快照都以追加写入，保持前缀不变 |
| 自动压缩 | 窗口 85% 触发，单段摘要指令 | 剩余窗口不足 16k 时触发，七段式结构化模板 | 窗口 90% 触发；只保留 user 消息和摘要 | `min(W×0.8, W−O−64k)` 触发；先剪枝工具结果，再生成 8 节摘要；原文以替换事件保留 |
| 工具输出截断 | 保留开头 16k 字符 | bash 保留结尾，完整输出落盘 | 开头、结尾各留一半 | 超过 12.5k token 落盘，内联头尾预览 |
| 消息模型 | 无类型的 OpenAI Chat JSON | 有类型，记录来源 provider/api/model | 有类型的 `ResponseItem` | 有类型的会话事件（59 种），进入历史的事件标记追加或替换 |
| 持久化 | Room，多份记录加 checkpoint/outbox | 只追加的 JSONL，树状分支 | 只追加的 rollout JSONL，SQLite 仅作索引 | 只追加的事件溯源 JSONL；请求由日志派生 |

## P0：协议正确性

以下 3 处问题很可能导致请求报错，或让模型行为退化。结论来自静态读代码，尚未做真实接口实测。

### 1. Responses API 没有请求加密推理内容

- **现状**：`ResponsesRequestBuilder.kt:25` 固定设置 `store=false`，但没有设置 `include`。`OpenAiResponsesProvider.kt:461` 把完整 output items（含 reasoning）挂在消息上，`ResponsesRequestBuilder.kt:43` 在下一轮原样回放这些 items。
- **对照**：Codex `client.rs:955` 无条件设置 `include=["reasoning.encrypted_content"]`；pi `openai-responses.ts:353` 同样设置。
- **风险**：工具循环第二轮可能报 “Item rs_… not found”。默认配置（内置 OpenAI + Responses + gpt-5.5）正好会走到这条路径。单测的模拟响应自带 `encrypted_content`，因此测不出来。
- **修复**：请求加上 `include`；回放时丢弃不含加密内容的 reasoning item。

### 2. Anthropic 工具循环不回传 thinking 块

- **现状**：`AnthropicMessagesProvider.kt:152-177` 只回传正文和 `tool_use`；整个 model 包都没有保存 `signature`。
- **对照**：pi `transform-messages.ts:101-117` 的做法：
  - 同一模型：原样保留带签名的 thinking；
  - 跨模型：降级为文本；
  - redacted 内容：跨模型时丢弃。
- **风险**：开启 thinking 时，请求可能返回 400；即使不报错，推理连续性也会丢失。
- **修复**：解析响应时保存 thinking、`signature`、`redacted_thinking`；provider 和 model 都相同时原样回放。

### 3. Anthropic `max_tokens` 固定为 4096

- **现状**：`AnthropicMessagesProvider.kt:13,127`；只有推理强度为 XHIGH/MAX 时才提到 65536（`ProviderReasoning.kt:110`）。
- **风险**：思考、正文和工具参数共用 4096 token。写长文件或给出长回答时会触发 `OUTPUT_LIMIT`，整批工具调用被拒绝；模型重试后很可能再次超限。
- **修复**：按模型配置的最大输出设置（pi 用 `model.maxTokens`）。

## P1：安全与信任边界

这是与 Codex 差距最大的一项。Movo 运行在装有短信、支付和聊天 App 的个人手机上，执行动作前却没有任何确认。

### 4. 默认全开，没有逐次审批

- 所有工具开关默认为 true（`Prefs.Keys.BOOLEAN_DEFAULTS`），包括终端、浏览器、敏感读取和敏感操作。
- 执行链路上没有审批环节；`AGENT_RUNTIME.md:49` 明确说明不维护黑名单。
- 系统提示进一步削弱了确认：
    - 发消息时“不追加二次确认”（`AgentPromptBuilder.kt:93`）
    - 主动翻查相册、短信、通话记录和聊天图片（`:60-62`）
    - “不要重复询问授权”（`:56`）
- 同一上下文里凑齐了“致命三要素”（lethal trifecta）：
    - 私密数据：短信验证码、Wi‑Fi 密码、通知
    - 不可信输入：网页、任意 App 的屏幕文字、MCP 结果、GitHub 上的 Skill
    - 外发通道：浏览器导航、终端联网、通过 GUI 发送
- 屏幕截图以 user 角色注入（`AgentLoop.kt:360`），屏幕上的文字更容易被模型当成用户指令。

对照：pi 同样不做审批，但它运行在开发机上，文档明确要求用容器隔离。Codex 有四层防护：
1. 策略默认拒绝；
2. 危险命令判定；
3. OS 沙箱；
4. 失败后升级审批（`ReviewDecision::default()==Denied`）。

建议：

- 按“是否改变外部状态、是否可逆”给动作分级。发送、支付、删除、改设置、安装、外发网络请求必须经用户确认；确认超时或失败时默认拒绝。
- 引入污点标记：本轮读过网页、通知或 MCP 结果后，所有外发类动作都强制确认。
- 对支付、银行和系统设置类包名单独加保护。
- 屏幕观察不再用 user 角色注入，改为工具结果。

### 5. 持久化注入通道

- `skills_install_from_github` 始终对模型可用（`AgentModelClient.kt` 的 `toolsFor` 中硬编码为 true）。装好的 Skill 默认启用，其 description 会进入之后每次运行的系统提示。
- `memory_write` 不需要确认，而核心记忆每轮都会注入。
- 后果：一次成功的提示注入就能永久污染之后的所有运行。
- 对照：Codex 对 Skill 有单独的审批开关（Granular `skill_approval`）；记忆由隔离的整合子 agent 生成，该子 agent 没有网络，只能写本地。
- 建议：安装 Skill 和新增记忆章节都需要用户确认，确认时展示变更 diff。

## P1：上下文成本与提示词结构

每轮固定开销约 17k token。其中工具 schema 约 14.5k（81 个工具，JSON 35,296 字符），系统提示约 2.8k（3,789 字符）。这些内容每轮全量重发。

数字来自 2026-09-24 的一次临时单测：导出 `AgentToolCatalog.build` 的真实输出，再用 Movo 自带的 `AgentContextBudget.textTokens` 估算 token 数。实际 token 数取决于各模型的分词器。系统提示的统计不含 Provider 提示词、记忆和 Skills。

不同设备条件下，实际暴露的工具数如下：

| 设备条件（开关全开） | 工具数 | 估算 token |
| --- | --- | --- |
| Root + ColorOS | 81 | 约 14.5k |
| Root，非 ColorOS | 76 | 约 13.9k |
| 无 Root，权限齐全 | 53 | 约 10.8k |
| 无 Root，无无障碍和系统权限 | 30 | 约 6.3k |

### 6. Anthropic 没有缓存断点，OpenAI 系缺少缓存路由键

- Anthropic 请求没有 `cache_control`；OpenAI 请求没有 `prompt_cache_key`。
- `toolsForRound` 每轮都会重算工具列表，但生成过程是确定的。设备条件（Root、无障碍、权限）不变时，每轮内容完全相同，不影响缓存。只有这些条件在运行中途变化时，工具列表才会变；Root 状态变化还会改写 system 消息。这两种情况都会使前缀缓存失效。
- OpenAI 以及多数 OpenAI 兼容服务会自动缓存相同的前缀，所以缺少 `prompt_cache_key` 只影响命中率。真正吃亏的是 Anthropic：它需要显式设置 `cache_control`，否则这 17k token 每轮都按全价计费。
- 对照：pi 自动设置 3 个缓存断点（系统提示、最后一个工具、最后一条 user 消息）；Codex 使用 `prompt_cache_key` 并保持前缀稳定。

### 7. 工具面过大且语义重叠

- 实测最多暴露 81 个本地工具（Root + ColorOS），MCP 最多再加 64 个。工具总表登记了 83 个，其中 2 个角色记忆工具只在角色会话中出现。
- 重叠的例子：
    - `terminal` 与 `run_command`、`read_file`、`write_file`、`list_directory`
    - `tap`、`tap_area`、`tap_element`
    - `input_text`、`replace_text`、`paste_text`
    - 20 多个 `search_*` 个人数据工具
- 对照：pi 默认只开 4 个工具；Codex 用 `ToolExposure::Deferred` 加 `tool_search`，在需要时才暴露低频工具。
- 建议：
    - `search_*` 合并为 `personal_search(source=…)`
    - 下线与 `terminal` 重复的旧工具
    - ColorOS、APK 分析、Skill 安装等低频工具改为按需暴露

### 8. 系统提示是一整段拼接字符串

- `AgentPromptBuilder.kt:42-100` 把身份、隐私立场、GUI 操作协议、Markdown 排版和错误码处理拼成一段，没有分节。没有无障碍工具时，GUI 规则也照样注入。
- 当前时间要先调用 `get_current_context` 才能拿到（`:54`）。“明天 7 点叫我”这类高频请求因此多一轮模型往返。
- 对照：pi 用 XML 分节，并按当前启用的工具生成规则；Codex 把 `current_date` 和 `timezone` 放进 environment_context，只注入变化部分。
- 建议：
    - 每轮追加一条环境消息（时间、时区、语言、前台 App、电量），放在可缓存前缀之后
    - GUI 协议移到 GUI 工具的描述里，或做成按需加载的片段

## P2：Loop 语义与上下文连续性

这一组问题不会直接让请求失败，但会拖慢响应、增加费用，或让模型在用户追问时拿不到之前的数据。

| # | 问题 | Movo 证据 | 对照与建议 |
| --- | --- | --- | --- |
| 9 | 只读工具也严格串行 | `AgentLoop.kt:186` | Codex 用 RwLock：只读工具并行，GUI/终端工具独占 |
| 10 | 没有卡死检测，也没有费用预算 | `AgentLoop.kt:14` | Codex 有 token/rollout 预算。建议检测“同一调用反复出现”和“观察结果不变”，并设可由用户确认放行的软预算 |
| 11 | 最终回复文本为空时，整个任务报错 | `AgentLoop.kt:217-219` | 副作用已经发生（例如闹钟已设好），任务却被判为失败。pi 和 Codex 都接受空回复 |
| 12 | 同一批 GUI 动作执行期间无法插话纠正 | `AgentPromptBuilder.kt:59,94` | 提示词鼓励把多个 GUI 动作放在同一批里盲执行。建议在 GUI 动作之间检查插话，命中就跳过剩余动作 |
| 13 | MCP 和敏感工具的结果被整体抹除 | `AgentSensitiveToolPolicy.kt:6`、`AgentConversationCodec.kt:212`、`AgentContextCompactor.kt:36` | 用户追问或上下文压缩后，模型拿不到这些数据。建议 MCP 结果不再整体抹除；敏感结果放进会话级、带过期时间的内存缓存 |
| 14 | 压缩摘要提示过简 | `AgentContextCompactor.kt:143-150` | pi 用七段式结构化模板，先把对话序列化成文本，工具结果截到 2000 字符。另外，Movo 的估算校准系数下限是 1.0（`AgentContextBudget.kt:16`），估算偏高时无法向下修正 |
| 15 | 终端输出只保留开头，报错被截掉 | `RootShellTerminalController.kt:751-757,1035` | stderr 拼接在 stdout 之后，输出一长就被截掉。pi 保留结尾并把完整输出落盘；Codex 开头、结尾各留一半。另外 `read_file` 用 `dd bs=1` 逐字节读，并可能从 UTF-8 多字节字符中间切断 |

## P3：工程形态

P0 前两条的根因是内部消息模型没有类型，也不记录消息来源。

- **16. 内部消息模型**：采用 org.json 表示的 OpenAI Chat 格式，外加 `_movo_*` 私有字段。消息不记录 provider/api/model，thinking 和 signature 也不是一等块，因此无法像 pi 那样“同一模型原样回放、跨模型降级”。建议逐步迁移到带来源信息的 typed message，参考 pi 的 `transform-messages.ts`。
- **17. 内置 Skill 描述失真**：`self-improving-agent/SKILL.md` 声称自己会被固定注入（fixed-injected），失败时还会自动写入 `data/ERRORS.md`。代码中没有任何对应实现，这段描述会误导模型。
- **18. 状态源过多**：状态分散在 journal、history、展示消息、checkpoint、outbox、context snapshot 和归档 7 处，跨进程还要通过 Binder 传递文件描述符（FD）。`AgentAppState.kt` 有 3108 行，`AgentRuntimeService.kt` 有 1204 行。pi 和 Codex 都以单一的只追加 JSONL 作为权威数据，其余都是投影。建议长期收敛为每个会话一条只追加的事件日志（Room 表）加投影。
- **19. 待验证**：上下文压缩后，第一条消息是 assistant 角色的摘要。需要确认 Anthropic 端是否接受这种开头。

## 补充对比：Aether（扶摇）

在 Agent 内核这一层 Aether 更好，在手机助手能力这一层 Movo 更好。Aether 直接复用了 pi 内核，所以 Movo 自研 loop 踩过的协议坑它都没有；但它也没有做系统级助手。

### Aether 的做法

- **内核直接用 pi，没有自研。** `pi-bridge/package.json` 把 `@earendil-works/pi-agent-core`、`pi-ai`、`pi-coding-agent` 锁定在 0.87.1，与本文分析的 pi 是同一版本。`pi-bridge/src/bridge.ts:2512` 直接调用 pi 的 `createAgentSession`。
- **pi 运行在手机上的 Linux 环境里。** Android 上，Node 在 Alpine（proot）中运行 `bridge.mjs`，Kotlin 端通过 stdio 帧协议与它通信，只负责 UI 和宿主工具。Alpine 安装就绪前 agent 不可用（`AetherViewModel.kt:725` 起的初始化流程）。
- **工具很少：**
    - pi 自带的 7 个：read、bash、edit、write、grep、find、ls，路由到 Alpine 或 Termux 执行（`bridge.ts:2122`）。
    - 1 个 `agent_display`：通过 Shizuku 在**隔离的虚拟屏**上操作 App，只靠截图和 0–1000 归一化坐标，不读无障碍树（`AetherToolExecutor.kt:223`）。
    - 一组 `aether_*` 自管理工具。
    - 浏览器、MCP、子 agent 都以 pi 扩展的形式提供。
- **系统提示约 1k 字符**（`PiAgentPrompt.kt`）。当前时间只有在用户的自定义提示里写了 `{{current_datetime}}` 等占位符时才会注入。
- **没有逐次审批。** 内核沿用 pi 的 YOLO 模式，`confirm` 只提供给扩展 UI 使用。

### 逐项对比

| 维度 | 更好的一方 | 依据 |
| --- | --- | --- |
| 协议正确性与多 Provider | Aether | 本文 P0 三条在 pi 中均已解决：thinking 签名回放、请求 `include` 加密推理内容、跨模型降级、`max_tokens` 取模型配置。支持 41 家 Provider |
| 上下文与会话 | Aether | 直接继承 pi 的缓存断点、结构化压缩、只追加的 JSONL 会话树和工具并行 |
| 扩展生态 | Aether | 兼容 pi 扩展；MCP、联网搜索、子 agent 都可作为 zip 扩展导入并热重载 |
| 手机系统集成 | Movo | Movo 有系统 API 直达（闹钟、音量等）、个人数据（通知、联系人、短信等）、小布/小爱入口 Hook 和语音唤醒；Aether 都没有 |
| GUI 操作质量 | Movo | Movo 以无障碍树为主，配合 `observation_id` 过期校验和“结果未知先重新观察”；Aether 只能截图加坐标，由一个工具包办所有动作 |
| 启动与依赖 | Movo | Movo 的 agent 是原生 Kotlin，电源键或唤醒词触发后可直接运行；Aether 必须先装好 Alpine 并拉起 Node，proot 进程还容易被系统回收 |
| 安全 | 都不好 | 两者都没有逐次审批。Aether 暴露面更小：没有个人数据工具，GUI 在虚拟屏上执行、不干扰用户当前屏幕。但虚拟屏里的 App 仍登录着用户的真实账号，照样能发消息 |

### 对 Movo 的启示

- **不建议整体迁移到 Aether 的架构。** 以 Node 加 proot 作为必需运行时，与 Movo“电源键、唤醒词即时响应”的系统助手定位冲突。
- **Provider 层应向 pi 看齐。** Movo 的 P0 问题都集中在协议适配。与其逐个修补，不如把 pi 的 `transform-messages.ts`、缓存断点、重试分类等语义移植成 Kotlin，并配合第 16 条的 typed message。Aether 证明了 pi 这套实现在手机上跑得通。
- **两处设计可以直接借鉴：**
    - 长时间的后台 GUI 任务放到虚拟屏上执行，不占用用户当前屏幕；
    - 工具少而精，用 action 枚举的单工具覆盖一类能力，呼应第 7 条的收敛方向。

## 补充对比：DeepSeek Harness（dsh）

DeepSeek Harness 是 DeepSeek 在 2026-08 开源的 agent harness（TypeScript，MIT，Developer Preview）。在「内核工程化」这一层，它是四个参照里最系统的：Movo 在 P3 和《整体架构评估》里提出的几件事，它都有可运行的实现，包括单一事件日志、工具作为自包含单元、执行前后的策略流水线、产品模式与 loop 解耦。但它的安全策略比 Movo 需要的还弱，整体框架也不适合搬到手机上。

完整调研结论见[DeepSeek Harness 技术分析报告](DeepSeek%20Harness%20技术分析报告.md)：第 4 节逐项剖析 dsh 的 8 项优势（机制、代码证据、代价），第 5 节是与 Claude Code、Codex、OpenCode、OpenHands、Pi 的六方横向对比，第 8 节是局限与风险。源码级明细见 `.docs/agent-design-review/dsh-analysis.md`。

### dsh 的做法

- **一切皆插件。** 运行时是一棵 Cordis 插件树，loop、工具注册表、会话日志、沙箱都是可替换的服务。所有注册都是副作用，插件卸载时自动撤销，因此支持热替换。默认 web 配置的插件树有 287 个条目。
- **请求由日志派生。** 每一步先把系统提示和用户消息写进会话日志，再从日志投影出模型历史、冻结后发出（`packages/core/agent-loop/src/agent.ts`）。日志有 59 种事件；进入模型历史的事件标记「追加」或「替换」，替换时用 `sourceEventSeqs` 指回原事件。压缩、剪枝都以替换事件落盘，原文不丢。
- **工具是自包含单元。** `ToolDefinition` 同时携带 schema、`execute`、`isConcurrencySafe`、`timeoutMs`、结果投影和 UI 展示函数，注册即生效、卸载即消失（`packages/core/tools/src/index.ts:223-299`）。
- **执行前后有策略流水线。** 依次经过 `tools/pre-execute`（钩子、权限、沙箱）→ 审批服务 → 只能拒绝的守卫 → `tools/execute`（超时、指标）→ `tools/post-execute`（拦截、替换、追加上下文）。任何一层抛错都转成错误结果，不打断循环。
- **产品差异放在 loop 之外。** 每个会话按 preset 装配一组插件（工具、提示片段、服务），用 `isolate` 防止服务泄漏到全局；loop 本身不认识任何产品模式。
- **缓存靠追加。** 系统提示是日志 0 号节点；时间等环境信息以快照追加；工具增删以增量消息追加（DeepSeek 专用 beta）。
- **安全较弱。** 有 Linux/macOS/Windows 的文件写沙箱，但不限读取和网络；审批只有一次性授权，没有持久规则；社区已报告 4 起沙箱逃逸。

### 逐项对比

| 维度 | 更好的一方 | 依据 |
| --- | --- | --- |
| 会话与可追溯 | dsh | 单一只追加事件日志，请求可从日志重建；Movo 的状态分散在 7 处（第 18 条） |
| 工具封装 | dsh | 一个工具的描述、条件、执行、并发、展示在同一个定义里；Movo 分散在目录、条件表、分发器和组合根（《整体架构评估》5.2） |
| 执行策略挂载点 | dsh | 有现成的执行前后拦截链和审批服务；Movo 执行链上没有审批环节（第 4 条） |
| 产品模式与内核的边界 | dsh | 模式由 preset 装配，loop 不识别；Movo 的角色模式进入了 Loop 和压缩器（《整体架构评估》5.1） |
| 上下文成本 | 都不理想 | dsh 缓存机制最系统，但首轮未缓存输入约 47.6k token；Movo 约 17k，且 Anthropic 无缓存断点 |
| 协议正确性 | dsh | DeepSeek 原生适配回传 thinking；第三方模型走 pi 的 provider 层（`@earendil-works/pi-ai`），继承 pi 的处理 |
| 手机系统集成与 GUI | Movo | dsh 是桌面与服务端 harness，没有 Android、无障碍、个人数据能力 |
| 安全策略 | 都不好 | dsh 有文件写沙箱但不防读取和外发；Movo 没有审批。Movo 的场景需要 Codex 式的分级、默认拒绝与持久规则，dsh 的策略不足以参考 |
| 运行时形态 | Movo | 原生 Kotlin，入口即时响应；dsh 依赖 Node，插件树庞大，处于破坏性变更频繁的预览期 |

### 对 Movo 的启示：按 dsh 调整架构的边界

**结论：适合「部分」按 dsh 重构。借鉴它的数据面和执行管线，不借鉴它的插件框架和安全策略。** 建议借鉴的部分正好对应 Movo 已识别的问题；各项在 dsh 中的实现与代价见报告第 4.1–4.8 节，与其他 harness 的差异见第 5.3 节：

| 借鉴点 | 对应问题 | 在 Movo 中的落地形态 |
| --- | --- | --- |
| 会话事件日志作为唯一事实源 | 13、14、16、18 | Room 中一张只追加的事件表（seq、type、追加/替换、`sourceEventSeqs`、payload）。模型历史、展示消息、checkpoint 都改为投影；敏感结果保留在日志，按策略决定投影给模型的内容 |
| 请求由日志派生，并在测试中断言 | 1、2、16 | 发请求前从事件投影历史；单测断言「请求 == 投影」，协议回放类 bug 可以在测试里暴露 |
| 工具作为自包含单元 + 注册表 | 7、《整体架构评估》5.2 | Kotlin 接口同时声明 schema、运行条件、执行、并发安全、超时、副作用等级、展示；按设备条件注册，替代分散的目录与分发 |
| 执行前后的拦截链 + 只能拒绝的守卫 | 4、5、15 | 审批、污点、敏感包名保护挂在执行前；截断落盘、敏感结果处理挂在执行后。结构参考 dsh，审批策略参考 Codex |
| 并发分类 + 按模型顺序提交 | 9 | 只读工具声明并发安全后用协程并行；GUI、终端工具独占 |
| 环境快照追加、前缀稳定 | 6、8 | 每轮在缓存前缀之后追加时间、时区、前台 App 等信息，内容不变不追加；root 状态变化不再改写 system |
| 重复调用提醒守卫 | 10 | 相同调用重复达到阈值时追加提醒，作为卡死检测的第一步 |
| 产品模式策略化 | 《整体架构评估》5.1 | 角色、语音等模式以会话级策略对象注入 loop 的扩展点（请求前改写、消息投影、压缩策略），loop 不再识别 roleplay |

不建议借鉴的部分：

- **Cordis 全插件化、YAML 组装、热重载、运行时自修改。** 手机助手对启动延迟敏感，Kotlin 没有对应的可逆副作用和动态加载基础设施；在装有支付和聊天 App 的手机上，让模型安装插件的风险不可接受。Movo 用普通的接口加注册表就能得到大部分好处。
- **dsh 的安全策略。** 一次性审批、不限读取与网络，满足不了第 4、5 条。
- **DeepSeek 专用的缓存技巧。** `tool_addition` 等只在 DeepSeek 路由下有效；BYOK 多 provider 场景应按 pi 的缓存断点做。
- **大默认工具面。** dsh 默认约 25 个工具、首轮前缀约 47.6k token，恰好是第 7 条要解决的问题。

顺序上，重构不应先于 P0 协议修复。建议按「事件日志与投影 → 工具单元与执行管线 → 产品模式策略化」渐进推进，每一步都能单独交付和回归。

## 改进路线

先修会导致请求失败的协议问题，再补安全和成本，最后做架构收敛。

| 阶段 | 事项 | 对应编号 |
| --- | --- | --- |
| 本周 | 修复 Responses 的 `include`、Anthropic thinking 回放和 `max_tokens`；补充真实接口集成测试（带推理的工具循环） | 1–3 |
| 短期 | 副作用动作需审批（挂载点参考 dsh 执行前拦截链，策略参考 Codex），引入污点标记；Skill 安装和记忆写入需确认；接入提示缓存并稳定前缀；每轮注入环境信息；允许空回复；终端输出改为保留结尾或头尾各半 | 4–6, 8, 11, 15 |
| 中期 | 合并工具并按需暴露；只读工具并行；卡死检测与软预算；GUI 动作之间检查插话；结构化压缩模板；调整 MCP 与敏感结果的保留策略；修正 self-improving-agent 描述 | 7, 9, 10, 12–14, 17 |
| 长期 | 引入 typed message 和跨 provider 转换层；统一为单一事件日志（结构参考 dsh 会话事件与追加/替换语义） | 16, 18 |

## 附：证据文件

| 项 | 位置 |
| --- | --- |
| pi 分析 | `.docs/agent-design-review/pi-analysis.md` |
| Codex 分析 | `.docs/agent-design-review/codex-analysis.md` |
| Movo 取证笔记 | `.docs/agent-design-review/movo-evidence.md` |
| DeepSeek Harness 调研报告 | [DeepSeek Harness 技术分析报告](DeepSeek%20Harness%20技术分析报告.md) |
| DeepSeek Harness 源码明细 | `.docs/agent-design-review/dsh-analysis.md` |
