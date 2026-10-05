# Movo 工具子系统重构实施方案（第三版）

> 本次是**工具子系统重构**：统一工具合同、结果协议、审批与记录。明确**不处理**角色、语音等产品模式对执行循环的耦合，因此完成后称为“工具子系统已重构”，不等于“整个 Agent 架构已合理”。

| 项目 | 内容 |
|---|---|
| 版本 | 第三版，2026-10-03 |
| 合同层 | [工具子系统合同与架构](Movo%20工具子系统合同与架构.md)（下称“合同”）：类型化合同、三层能力视图、资源与取消、验证证据、协议与多模态适配。**先定合同再冻结接口，本方案与定义清单按合同填充** |
| 依据 | [工具重构方案](Movo%20工具重构方案.md)（设计方案）、[工具定义清单](Movo%20工具定义清单.md)（定义清单，第三版）、三组逐工具 review、两路方案 review、ChatGPT 架构 review |
| review 记录 | 代码可行性 `.docs/tool-design/review-code-feasibility.md`；设计与产品 `.docs/tool-design/review-design-product.md` |
| 方式 | 一次性重构到目标架构：同一分支完成，经评测和真机回归后整体合入 |
| 分支 | `refactor/agent-tools`（基于 `a73b203`），独立 worktree `/Users/bytedance/dev/Movo-tools` |
| 状态 | **待 review**，批准前不写代码 |

## 0. 相对第一版的变化

| 来源 | 变化 | 章节 |
|---|---|---|
| 你的要求 | 新增工具调用记录、日志与诊断、定义治理、切面链、评测体系、持续优化 | 7–9、4.2、13、14 |
| 你的要求 | 新增工具、MCP、Skill 的边界，以及每项能力的归属 | 2 |
| 设计 review | 结果语义分为 A、B 两类承诺；误报改在会话层判定 | 3.1 |
| 设计 review | 安全策略重做：提交点识别、会话级污点、外发通道、自我保护、锁屏策略、审批范围 | 5 |
| 设计 review | 内置工具全部常驻，`tool_search` 只用于超出预算的 MCP；目录在一次运行内固定 | 3.3 |
| 设计 review | 命名统一、参数压平、新增与调整错误码 | 定义清单第三版 |
| 设计 review | 评测：基线先合入 P0、多模型多轮、数值门槛、注入对抗与良性不打扰用例 | 13 |
| 代码 review | 并发改为默认独占，事件回到运行线程；审批在并行之前完成 | 4.3 |
| 代码 review | 调用阶段即按注册表判定敏感度；旧码映射区分动作与只读；补齐漏掉的文件 | 4.2、10、11 |
| 代码 review | 交互通道按真实入口重写（没有后台定时任务；Hook 入口无界面） | 6 |
| 代码 review | 开关逐个工具对照，不放宽权限 | 12.3 |
| 代码 review | 开工前先拆底层、冻结核心，再按文件所有权分工 | 15 |

## 1. 范围

**做**（一次完成）：

1. 新工具体系：42 个内置工具替换现有 84 个本地工具（含角色记忆、会话历史），MCP 接入同一体系。逐个定义见定义清单第三版，合同见合同文档。
2. 统一结果协议与错误码；Anthropic 设置 `is_error`；`status` 随会话持久化。
3. 动作结果确认（A 类工具）。
4. 安全与审批：提交点识别、污点、受保护应用、自我保护、锁屏策略、审批范围（含“本任务内允许”）。
5. `ask_user`，以及聊天、悬浮面板、语音、Hook 四种入口的交互通道（“本任务内允许”由第一张确认卡提供，不单设 `permission_request`）。
6. 切面链：执行前后的统一拦截，包括循环守卫和看门狗。
7. 工具调用记录、日志与诊断、运行日志页的工具信息。
8. 定义治理：生成工具文档、快照、token 预算、命名与描述检查、工具集版本号。
9. 评测体系：契约测试、离线选路评测、真机任务评测。
10. `file_read` 读 PDF、视频、音频；只读工具并行。这两项做，但默认关闭，评测时单独打开对比（见 17 节第 2 条）。
11. 展示与执行分离；删除旧实现；重写受影响的测试。

**不做**：多 Agent 并发运行；单一事件日志化；把角色、语音等产品模式从执行循环中剥离；Movo 作为 MCP 服务器对外提供工具。

**P0 协议修复**（Responses 带 `include`；Anthropic 回放 thinking 和 signature 并持久化；`max_tokens` 取模型配置）：建议先单独合入，再测基线，避免掩盖工具层的变化。见 17 节第 1 条。

## 2. 工具、MCP 与 Skill 的边界

### 2.1 判断标准

| 形态 | 适合什么 | 判断标准（满足任一条） |
|---|---|---|
| **内置工具** | 需要 Movo 运行时参与的能力 | 1. 需要 App 进程内的 Android 能力：无障碍、Root、系统接口、WebView、本机数据库<br>2. 需要运行时把关：审批、敏感度标记、结果确认、污点<br>3. 高频且对时延敏感<br>4. 与运行状态紧密耦合：观察快照、会话、记忆、交互通道 |
| **MCP** | 手机之外的服务 | 1. 外部服务或需要账号的云端 API（邮件、云文档、云日历、地图、智能家居、GitHub、额外的搜索服务）<br>2. 因人而异的长尾能力，由用户自行添加<br>3. 不需要接触手机内部 |
| **Skill** | 怎么用现有工具做成一件事 | 1. 不带来新能力，只是流程、方法和领域知识<br>2. 只在特定任务才需要（按需加载正文）<br>3. 能用现有工具组合完成，包括终端里的 Linux 工具 |

**两条补充规则**：

- **低频的 shell 包装默认做成 Skill，除非它的结果敏感、或动作需要确认与验证。** 终端输出不会被标成敏感，root 身份的命令每次都要确认，所以涉及敏感数据和外部影响的能力必须做成工具，才能被正确把关。
- **系统提示分节与 Skill 的分工**：分节写某个领域工具“每次都要遵守”的用法规则；Skill 写“特定任务才需要”的流程，按需加载。

### 2.2 现有能力的归属

| 能力 | 归属 | 理由 |
|---|---|---|
| 屏幕观察与操作、应用打开、剪贴板 | 工具 | 无障碍、进程内、需要提交点把关 |
| 闹钟、倒计时、音量、播放、Wi‑Fi 与蓝牙、系统设置 | 工具 | 系统接口，需要确认结果 |
| 系统诊断（内存、存储排行、日志） | 工具（`device_diagnostics`） | 改成 Skill 加 root 终端后每次都要确认，体验更差；结果含敏感日志 |
| 个人数据检索、验证码、健康、Wi‑Fi 密码 | 工具 | 敏感数据，需要敏感度和污点把关 |
| 文件检索、文件读写、媒体读取 | 工具 | 需要授权与敏感度；PDF、视频处理用进程内的 Android 接口 |
| 终端 | 工具 | 运行时管理进程、后台任务、审批 |
| 网页 | 工具 | 进程内 WebView，用户可以接管 |
| 记忆、会话历史、技能读取与安装 | 工具 | 与运行状态耦合，写入需要把关 |
| 提问、按需加载 | 工具 | 运行时交互通道 |
| 网络搜索 | 模型服务端的托管搜索；额外的搜索服务走 MCP | 不需要手机内部能力 |
| 邮件、云文档、云日历、地图、智能家居、GitHub | MCP（用户添加） | 外部服务，因人而异 |
| 各 App 的操作流程（如微信发消息、外卖下单） | Skill | 用现有工具组合完成，属于流程知识 |
| 厂商系统的设置路径（HyperOS、ColorOS） | Skill | 领域知识，按需加载 |
| 文件处理方法（用 Linux 工具转换格式、提取内容） | Skill | 用终端完成；`file_read` 覆盖不到的格式才用 |
| 自我改进流程、技能创建、技能安装说明 | Skill（已有，内容按新工具名更新） | 流程知识 |

**本次新增的内置 Skill**（只放流程，不放新能力）：

- `system-settings-paths`：HyperOS 与 ColorOS 的常用设置路径；
- `file-processing`：用终端的 Linux 工具处理常见格式；
- `messaging-apps`：微信、QQ、短信的发送流程，以及发送前的核对步骤。

其他 App 的流程，按评测和真实失败记录再补充。

## 3. 合同与定义清单第三版为准

第一、二版的「对设计方案的修订」推导已全部被取代，不再在本方案内保留旧清单（上一轮 review 指出旧表会被当成单测/权威范围而带偏实现）。以这两份为唯一权威：

- **工具清单、命名、参数、承诺、错误码**：见[定义清单第三版](Movo%20工具定义清单.md)（42 个工具；承诺按调用标注 `effect_verified`；`audio_control` 拆为 `media_control`+`volume_set`；删除 `permission_request` 与 `terminal_session`；`tool_search` 改为 `mcp_find`+`mcp_call`；31 码唯一权威表在 §0.4）。
- **架构合同**：见[合同文档](Movo%20工具子系统合同与架构.md)（类型化合同、三层能力视图、资源与取消状态机、验证证据、观察代际、协议与多模态适配、七份冻结合同）。

本方案自第 4 节起只写「怎么落地」，不再重复工具定义。

## 4. 架构

### 4.1 包结构

```
agent/tools/
  core/        AgentTool、ToolOutcome、错误码、ToolArgs、Schema 构建、ToolEnvironment、ToolContext、
               ToolRegistry、ToolPipeline、切面接口与各切面、ToolProjection、LegacyResults、
               ToolCallRecord、审批（策略、污点、提交点识别、受保护应用、规则存储）、UserInteraction
  ToolServices.kt
  meta/        ask_user、mcp_find、mcp_call
  ui/          app_*、ui_*、clipboard_*（含提交点识别器）
  device/      device_read、device_toggle、setting_*、device_diagnostics、clock_*、media_control、volume_set
  personal/    personal_search、sms_code_read、usage_read、health_read、wifi_password_read、file_search
  files/       file_read（按类型分派：文本、图片、PDF、视频、音频）、file_write、file_list
  terminal/    terminal_*
  browser/     browser_*
  memory/      memory_*、skill_*、conversation_read
  mcp/         MCP 适配
  backends/    从旧 AgentLocalTools 和各数据类拆出来的具名函数（不改行为）
ui/tools/      界面侧展示登记表：图标、标题、运行中文案、摘要；兼容旧工具名
```

### 4.2 调用生命周期与切面链

一次调用分三个阶段。切面按固定顺序执行，每个切面只做一件事，可以单独测试。以后新增横切逻辑（例如用户自定义钩子）只需加一个切面，不用改工具。

```kotlin
internal interface ToolAspect {
    fun prepare(call: PreparedCall): AspectDecision = AspectDecision.Continue   // Continue / Reject(outcome) / Warn(warning)
    fun finish(call: PreparedCall, outcome: ToolOutcome): ToolOutcome = outcome
}
```

| 阶段 | 线程 | 顺序 | 切面 | 作用 |
|---|---|---|---|---|
| 准备 | 运行线程，逐个执行 | P1 | 解析 | 找到工具（找不到时附上旧名到新名的提示）；解析参数；形成不可变 `ResolvedCall`：敏感度、风险、资源键集合、**注入后端（accessibility/root-input）与是否读到可信节点**、目标身份；审批/资源/执行共用这一份，不各查一次 |
| | | P2 | Schema 校验 | 原 `AgentToolCallValidator`，从循环移到这里 |
| | | P3 | 可用性与开关 | 按工具整体以及参数中的 section、source、kind 判断 |
| | | P4 | 自我保护 | 拒绝对 Movo 自身界面的 `ui_*` 动作；锁屏时的读取与动作策略 |
| | | P5 | 环境守卫 | 无障碍确保与修复；入口窗口让出屏幕 |
| | | P6 | 循环守卫（前） | 相同工具加相同参数连续 3、5 次时给出警告，8 次时返回 `LOOP_DETECTED` |
| | | P7 | 审批 | 风险、污点、提交点、受保护应用、审批规则（含“本任务内允许”）；需要确认时走交互通道 |
| 执行 | 并行组在工作线程，其余在运行线程 | E1 | 执行与看门狗 | 调用 `execute`；超过声明的时限后，动作类返回 unknown、只读类返回 TIMEOUT，并把占用的资源标为忙，直到调用真正返回；登记取消钩子（su 进程、sleep） |
| 收尾 | 运行线程，按模型给出的顺序 | F1 | 结果归一 | 旧结果码映射（区分动作与只读）；运行已取消时也要先形成终态记录（CANCELLED 或按证据的 unknown/已送达）再结束，不直接抛出跳过 F7 |
| | | F2 | 承诺标注 | 标注 A、B 类的 `effect_verified` |
| | | F3 | 敏感度与脱敏 | 合并声明的敏感度与结果的敏感度；决定哪些内容进持久会话 |
| | | F4 | 截断与落盘 | 统一上限 12000 字符；命令输出和日志保留首尾，完整内容落盘 |
| | | F5 | 污点更新 | 会话级污点，见 5.1 |
| | | F6 | 循环守卫（后） | 屏幕指纹连续 3 次动作后不变时，加一条警告 |
| | | F7 | 记录 | 生成 `ToolCallRecord`；写日志与诊断；更新指标 |
| | | F8 | 投影 | 生成给模型的内容，并发出事件 |

**投影实现**：改用 kotlinx 序列化，避免 org.json 把 `/` 转义成 `\/`。超过上限时保留错误字段，裁剪 data 里的数组，不再把整段 data 再转义一次。

### 4.3 并发模型

- **默认独占。** 只有同时满足两个条件才并行：风险是 `read`，且工具显式声明为可并行。
- **按资源独占。** 资源包括屏幕、剪贴板、终端、浏览器、记忆、技能库、对话，以及每个 MCP 服务器。
- **审批在并行展开之前完成。** 准备阶段逐个执行，审批不会并发弹出。
- **工作线程只执行 `execute`。** 事件、写消息、写 transcript 都在运行线程按原顺序完成，保证诊断、checkpoint、卡住检测拿到的上下文正确。
- **同时最多 3 个只读调用。**

### 4.4 看门狗与取消

- 每个工具声明执行时限：只读工具默认 30 秒，动作工具默认 15 秒，终端与网页导航按各自参数。
- 超时后不强杀线程，只返回结果（动作类为 unknown），并把资源标为忙。后续用到这个资源的调用返回 `BUSY`，直到原调用结束。
- 取消运行时：设备控制器启动的 `su` 进程和长时间 sleep 都通过登记的取消钩子中止。

## 5. 安全与审批

### 5.1 风险与污点

- **污点是会话级的。** 一次运行里读到的不可信内容，在后续运行中仍留在上下文里，所以污点随会话保存，直到这部分内容被压缩掉，或者用户开启新会话。
- **污点分两类**（与合同 §5.3、定义清单 §0.6 一致）：
  - **不可信内容**：网页内容、通知、MCP 结果、非白名单 App 上的 `ui_observe`（聊天/社交/购物屏幕）、root-observe 读到的非无障碍树内容；
  - **个人数据**：`personal_search`、短信/验证码、通讯录、相册等个人来源。
- **外发确认按“两类同时成立”判定**（用户 2026-10-04 定）：只有本轮**既读过不可信内容、又读过个人数据**，才对外发动作强制确认——针对“个人数据被注入指令外泄”这一威胁；只读了其一不触发。提交点、受保护应用、支付那几条硬规则本就无条件确认，不依赖污点。
- **污点的影响只落在外发类动作上**，不影响一般操作：
  - 提交点动作（发送、支付、转账、删除、提交）；
  - `app_open` 打开带参数的 URI，或者目标不是用户提到过的域名；
  - `browser_open`、`browser_act` 导航到用户没提到过的域名，或者提交表单；
  - Linux 环境里的终端命令。

### 5.2 提交点识别

由运行时识别，不依赖模型自觉：

| 识别依据 | 例子 |
|---|---|
| 目标节点的文字、描述、资源 ID | 发送、支付、确认支付、转账、删除、提交订单、Send、Pay |
| 输入法动作 | `submit` 或回车会触发发送 |
| 已知收银台与转账界面的特征 | 微信支付、支付宝、淘宝、美团、京东的收银台；同时识别在微信等 App 内嵌的支付界面，不只看包名 |
| 用坐标操作时 | 先把坐标映射到该点下的节点，再按上面的规则判断 |

模型声明的 `effect` 只能**增加**确认，不能免除确认。

### 5.3 什么时候需要确认

| 情形 | 处理 |
|---|---|
| 风险为 external 的工具（`setting_write`、`app_control`、`skill_install`、`memory_write` 的 replace 和 clear 等） | 确认 |
| 提交点动作 | 确认，卡片上展示要发送或支付的内容 |
| 两类污点**同时成立**后的外发类动作（5.1） | 确认 |
| `terminal_run` 以 root 身份运行 | 确认。命令先按 `&&`、`;`、`|` 切分，逐段按命令前缀匹配审批规则 |
| **任何后端读不到可信节点就动作**（root-input 或无障碍裸坐标；受保护应用 / FLAG_SECURE / 纯坐标） | 确认（读不到内容时用合同 §5.3 的降级卡）；读不到包名无法归因时按最保守处理 |
| `memory_write` 的 append | 不确认，在界面上提示并可以撤销 |
| 其余 | 不确认 |

> **2026-10-06 按用户反馈调整（代码已按此实现）**：用户反馈「所有的动作都要审批」「审批内容看不懂」「受保护应用谁让你加的」。
> - **污点按两类同时成立实现**（此前代码只有一类，读屏、读设置、跑命令、写记忆都会污染整轮）。污点来源只有：网页、屏幕、通知、MCP、后台监听事件（不可信内容）与个人记录、验证码、剪贴板、文件、健康、应用用量、位置、Wi‑Fi 密码（个人数据）。工具自己的输出（终端、写记忆）不算。外发类动作只有：带参数的网址与 URI、Linux 终端命令与联网命令、MCP 调用、写长期记忆（追加、替换）、后台监听。
> - **读不到坐标点上的节点不再单独确认**（地图、画布、游戏、节点很多的列表里每一步都弹卡）；认不出前台应用只在用户设过受保护应用时确认。
> - **受保护应用默认为空**，由用户在设置里添加（管理页待 Figma 定稿）。
> - **「本次任务内，这类操作都允许」已实现**：同一工具 + 同一目标（包名、设置项、命令前缀、目录）本次运行内不再询问；付款、转账、删除、受保护应用不提供。
> - **开关真正生效**：「设置 → 工具」五个开关管对应工具（此前类型化工具只有网页开关生效）。
> - 后台监听唤醒的一轮在屏幕关着时，审批、提问立即返回「无法确认」，不干等 120 秒；用户自己发起的任务照常等待，亮屏后出卡（锁着时先出解锁提示），超时才按未确认处理（真机：用户发起任务后随手锁屏，原来会直接失败）。

### 5.4 审批的有效范围

| 范围 | 说明 | 限制 |
|---|---|---|
| 仅这一次 | 默认 | — |
| 本次任务内 | 同一个工具、同一个目标在本次运行内不再询问 | — |
| 一直允许 | 按“工具、目标范围、策略版本”保存。目标范围例如包名、联系人、规范化的命令前缀 | 支付、转账、删除不能一直允许；两类污点同时成立时，一直允许的规则失效；可以在设置里撤销 |

- **确认卡的内容**：应用名、目标控件的文字、截图裁剪、要发送或写入的内容，不展示原始参数 JSON。
- **确认之后重新校验目标**：如果界面已经变化，返回 `STALE_OBSERVATION`，不执行。

### 5.5 自我保护与锁屏

- 对 Movo 自身包名的**有目标** `ui_*`（tap/input/长按指定节点等）一律拒绝，返回 `POLICY_DENIED`；home/back/enter 等全局键不拒（Movo 在前台时仍需能返回）。
- 锁屏状态下：读取 private 或 secret 数据、执行 external 动作，都先请求解锁（`requestDismissKeyguard`）；用户没有解锁就返回 `POLICY_DENIED`。

### 5.6 预授权（第三版已删除 `permission_request`）

> 第三版删除此工具：界面层无法可靠核实目标（“联系人=妈妈”），有被注入放大的风险。改由第一张确认卡提供“本任务内这类操作都允许”。以下为原推导。

#### 原 `permission_request` 设计

用于语音或需要多步操作的任务：模型在开始前列出要做的动作，例如“给妈妈发一条微信”“把音量调到 30%”。用户一次确认后，这些动作在本次运行内不再询问。

预授权只覆盖列出的动作和目标，**不覆盖支付和转账**。

## 6. 交互通道：提问与审批

### 6.1 机制

- **事件与消息**：运行时发出 `InteractionRequested` 事件，带 `requestId`、类型（提问、审批、解锁）和展示内容。界面通过新消息（消息号 13）回传结果；Service 按 `requestId` 交给等待中的运行。
- **重复送达**：重连时事件会回放，界面按 `requestId` 去重，Service 只接受一次回答。
- **取消**：等待用 `runController.register` 登记，取消运行时立即返回“已取消”。
- **卡住检测**：等待期间不计入卡住检测（工具执行中已暂停计时）。界面显示“等待你确认”，而不是“可能卡住了”。

### 6.2 各入口的行为

| 入口 | 可否交互 | 提问 | 审批 |
|---|---|---|---|
| 聊天页 | 可以 | 卡片：选项加“其他”，可以自由输入 | 卡片 |
| 悬浮面板 | 可以 | 面板内卡片。面板不计入 `ui_observe`，也不会让观察快照失效 | 面板内卡片 |
| 语音（电源键、唤醒词） | 可以 | 语音播报问题和选项，用户用语音回答；等待提问期间，用户说的话当作回答，而不是补充指令 | 发送类先复述内容，再接受语音确认；支付、删除必须在屏幕上点按或用生物识别；20 秒没有回应时转为待处理卡片 |
| Hook 入口（小布、小爱） | 有悬浮窗权限时可以，借 Movo 的悬浮面板 | 同悬浮面板 | 同悬浮面板；没有悬浮窗权限时返回 `POLICY_DENIED` |
| 锁屏唤起 | 同语音 | 同语音 | 需要先解锁（5.5） |

### 6.3 需要先出 Figma 稿的界面

1. 审批卡：聊天页、悬浮面板、语音待处理三种形态；
2. 提问卡；
3. 解锁提示；
4. 设置里的“已允许的操作”管理页：查看和撤销“一直允许”的规则。

这些界面的代码等你确认定稿后再写。运行时部分不等设计稿。

## 7. 工具调用记录

每次调用都生成一条 `ToolCallRecord`。它是执行卡、运行日志页、评测指标和后续优化共用的唯一记录。

### 7.1 字段

| 组 | 字段 |
|---|---|
| 身份 | `record_version`、`toolset_version`、`run_id`、`round`、`call_id`、`tool`、`domain` |
| 参数 | `args`：按敏感度脱敏。normal 保留全文（截到 2000 字）；private 只保留工具声明为可公开的字段；secret 不保留 |
| 属性 | `risk`、`sensitivity`、`concurrency`、`commitment`（A、B、只读） |
| 准备阶段 | `rejected_by`（哪个切面拒绝了它）；`approval`：是否需要、原因、范围、决定、等待毫秒、是否命中规则 |
| 时间 | `queued_at`、`started_at`、`finished_at`、`duration_ms`、`watchdog_fired`、`parallel_group` |
| 结果 | `status`、`code`、`detail`、`retry`、`effect_verified`、`truncated`、`warnings` 的码、`image_count`、`image_bytes`、`model_chars`（给模型的字数） |
| 执行信息 | `executor`（无障碍、root、系统接口）、`method` |
| 守卫 | `repeat_count`、`screen_unchanged`、`taint_after`（污点来源） |

### 7.2 去向

| 去向 | 内容 | 保留 |
|---|---|---|
| 运行事件 `ToolFinished`（扩展字段） | 完整记录（已脱敏） | 随运行归档与 checkpoint |
| 执行卡、执行详情 | 从事件读取：状态、错误码、确认等待、执行方式 | 随会话 |
| 诊断存储（运行日志页） | 记录去掉参数 | 最近 20 次运行（现有规则） |
| 评测导出 | 完整记录（已脱敏） | 评测目录 |
| 指标汇总 | 计数与时长 | 按版本累计 |

## 8. 日志与诊断

| 位置 | 内容 |
|---|---|
| logcat | 每次调用一行 info：工具、状态、错误码、耗时。error 和 unknown 记为 warn。看门狗、守卫、审批也记 warn。不输出参数和结果内容 |
| 诊断事件 | 新增 `tool.prepared`、`tool.approval.requested`、`tool.approval.resolved`、`tool.watchdog`、`tool.loop_guard`、`interaction.requested`、`interaction.resolved`；`tool.finished` 增加 status、code、duration、effect_verified |
| 运行日志页 | 工具行显示三态图标、错误码对应的中文说明、确认等待时长、执行方式；失败原因说明按新错误码改写；导出的 Markdown 增加每次调用的状态和错误码 |
| 卡住提示 | 等待确认或回答时显示“等待你确认”，不显示“可能卡住了” |

## 9. 工具定义的治理

| 机制 | 做法 |
|---|---|
| 单一来源 | 工具定义只写在代码里（`AgentTool`）。文档、目录、展示登记都从它派生或接受它的检查 |
| 生成工具文档 | 单测从注册表生成 `docs/agent/tool-catalog.md`，按三种环境分别生成：全能力、无 Root、无无障碍。文档过期时测试失败（同 dsh 的做法） |
| Schema 快照 | 每个工具在三种环境下的 schema 都有快照，改动必须同时更新快照 |
| token 预算 | 全能力目录的估算 token 不超过 8k；单个工具的描述不超过 160 字，单个参数的描述不超过 120 字 |
| 命名与描述检查 | 工具名必须是“已登记领域前缀_动词”；描述里不写“何时用”这类规则（规则放系统提示分节） |
| 错误码 | 只能使用枚举里的码；文档里的码表与枚举逐项比对 |
| 旧码映射 | 252 个旧码逐条断言映射结果（`.docs/tool-design/legacy-codes.txt`） |
| 展示覆盖 | 每个内置工具在界面侧都有展示登记；旧工具名也有映射 |
| 工具集版本 | `TOOLSET_VERSION` 写进调用记录和评测结果。目录的哈希值变化而版本号没升时，测试失败 |
| 改动流程 | 任何改变模型可见定义的改动，都要先跑离线选路评测（13.2），结果附在提交说明里 |

## 10. 接入点与改动清单

| 文件 | 改动 |
|---|---|
| `agent/model/AgentLoop.kt` | 目录在运行开始时由管线生成并固定；准备、执行、收尾三阶段；并行分组；GUI 插话检查；执行前拒绝改用新协议 |
| `agent/model/AgentModelClient.kt` | `ToolResult` 的 status 不再默认为 ok，必须显式给出；`ConversationMessage` 增加 `status`（默认空，旧数据兼容）；`complete()` 接收管线 |
| `agent/model/AgentPromptBuilder.kt` | 去掉写死的工具规则；注入领域分节；环境信息作为 user 角色消息，只在用户发言时生成，写入历史，界面隐藏（不放前台应用） |
| `agent/model/AgentConversationCodec.kt` | 持久化工具结果的 status；按调用阶段判定的敏感度脱敏；保留旧名敏感名单作为兜底，处理旧会话 |
| `agent/model/AgentToolBatchRecovery.kt` | 补齐的中断结果改用新协议（`INTERRUPTED`，status 为 unknown） |
| `agent/model/AnthropicMessagesProvider.kt` 等 Provider | 设置 `is_error`（error 为 true，unknown 不设）；P0 修复（若不单独合入） |
| `agent/runtime/AgentRuntimeRunExecutor.kt` | 组装管线、切面、交互通道；构建 `ToolEnvironment`；去掉 `beforeToolExecution` |
| `agent/runtime/AgentRuntimePolicy.kt` | 开关与 ModelConfig 的同步按新的开关对照 |
| `agent/runtime/AgentEvent.kt`、`AgentRuntimeWire.kt`、`AgentEventJsonCodec.kt` | `ToolFinished` 扩展字段；新增 `InteractionRequested`、`InteractionResolved` |
| `agent/runtime/AgentRuntimeService.kt`、`AgentRuntimeClient.kt`、`AgentRuntimeSession.kt` | 回答与审批消息；requestId 去重；等待可以被取消唤醒 |
| `agent/overlay/AgentOverlayState.kt` | `reduce` 处理新事件 |
| `agent/overlay/AgentOverlayText.kt`、`AgentOverlayVisibilityPolicy.kt` | 文案从展示登记取；是否让出屏幕看资源声明 |
| `agent/roleplay/RoleplayRunContext.kt` | 提示词里的 `character_memory_*` 改为 `memory_*` |
| `agent/tool/PendingSkillConflictCapability.kt` | 适配新名和新结果格式（`CONFLICT`、data） |
| `agent/device/RootShellDeviceController.kt` | 结果和提示里的旧工具名改为新名；`waitForUiSettle` 改看动作类型；观察的临时文件路径改为唯一 |
| `agent/accessibility/AgentAccessibilityService.kt`、`agent/terminal/UserFileAccess.kt`、`data/repository/NotificationHistoryRepository.kt`、`agent/tool/ColorOsMemoryDatabaseQuery.kt` | 结果和提示里的旧工具名改为新名 |
| `agent/voice/session/VoiceSessionManager.kt` | 等待回答期间，用户说的话作为回答，不作为补充指令 |
| `hook/breeno/BreenoHooks.kt`、`hook/xiaoai/XiaoAiHooks.kt` | 标签改为看领域；交互能力按 6.2 判断 |
| `diagnostics/*`、`ui/screens/diagnostics/*` | 第 8 节 |
| `ui/tools/`（新增）、`ui/components/ToolIcons.kt`、`ChatMessageItem.kt`、`AgentChatBody.kt`、`DiagnosticsData.kt`、`AgentAppState.kt`（会话预览、工具页列表）、`ConversationMarkdownExporter.kt`、`ConversationSearch.kt`、`ui/preview/FakeAgentUiStates.kt` | 展示改由登记表提供，并兼容旧名 |
| `ui/screens/tools/*`、`ui/model/ToolCapabilityProjection.kt`、`ui/app/DeviceCapabilitiesUi.kt`、3 种语言的字符串 | 工具页内容换成新工具 |
| `config/Prefs.kt` | 新增审批规则、会话污点的存储；5 个开关键不变 |
| `assets/builtin_skills/*` | 改用新工具名；修正 `self-improving-agent` 的描述；新增 2.2 节的 3 个 Skill |
| 调试源码集 | 评测入口（13.4） |

## 11. 删除与迁移清单

| 删除或迁移 | 去向 |
|---|---|
| 10 个 `Agent*ToolCatalog.kt`、`AgentToolCatalog.kt`、`AgentToolSchema` 的坐标定义 | 各工具的 `parameters(env)` |
| `agent/tool/AgentLocalTools.kt`（1389 行） | 逐段迁移：<br>• 观察状态、元素引用校验（:131、:810-835）→ `ui/`<br>• 应用搜索打分（:730-757）→ `ui/`<br>• 技能安装的检查、重放、下一轮生效（:1008-1198）→ `memory/`<br>• 记忆读写（:288-343）→ `memory/`<br>• 终端与文件包装 → `terminal/`、`files/`<br>• 浏览器包装 → `browser/` |
| `AgentToolRequirements.kt`、`AgentToolCapabilities.kt` | `ToolEnvironment` 与各工具的可用性；`isColorOsDevice` 移到 `ToolEnvironment` |
| `AgentSensitiveToolPolicy.kt` | 注册表的 `sensitivityOf`；旧名单作为兜底，只用于旧会话 |
| `AgentTraceFormatter.kt` | 界面侧展示登记表 |
| `ToolExecutionDecision.kt` | 切面 |
| `McpRunContext.kt` 中的 `RoutingToolExecutor`、`McpToolExecutor`（:102、:283） | `mcp/` 适配器 |
| `CharacterMemoryTools` 的工具部分 | `memory_*` 按会话绑定，存储逻辑保留 |
| `ConversationHistoryTool.kt` | `conversation_read` |
| `AgentScreenObservationContract.kt`、`ObservationReferencePolicy.kt` | 移到 `ui/` |
| `DeviceContextTool.kt` | 拆分：时间部分进环境信息，位置部分进 `device_read` |
| `AgentToolCallValidator.kt` | 保留，作为切面 P2 使用 |
| 底层数据类（`AgentStructuredDeviceTools`、`AgentPersonalDataTools`、`AgentPersonalContextTools`、`AgentPrivateDatabaseTools`、`AgentColorOsMemoryTools`、`AgentImageTools`） | 保留实现。按工具名分发的入口拆成具名函数，移到 `backends/`；结果里的 `tool` 字段和敏感包装移到调用方 |
| `RootShellTerminalController`、`AgentBrowserSession` | 保留按 action 的内部分发（浏览器的 `executeInternal` 和 `lastAgentToolCallId` 发布逻辑仍需要它），新工具在外层调用 |

## 12. 兼容性

### 12.1 历史会话

- **旧会话回放给模型**：三家 Provider 都不按当前目录校验历史中的调用，旧调用照常发送；旧工具结果没有 status 时按“未知”处理，不设置 `is_error`。
- **模型照着历史调用旧名**：返回 `UNKNOWN_TOOL`，hint 里附上新名。
- **旧会话的显示**：展示登记表保留旧名映射。
- **升级时正在运行的任务**：按现有机制恢复为“中断”。

### 12.2 配置

远端运行配置、语音测试仪器使用的开关键都不变。

### 12.3 开关对照

原则：**不放宽权限**。对照依据现有目录的开关分组。

| 旧工具 | 旧开关 | 新工具 | 新开关 | 变化 |
|---|---|---|---|---|
| `get_current_context` | 无 | 环境信息（时间）；`device_read` 的 location | 无；敏感读取 | 位置收紧 |
| `device_status`、`network_info` | 设备 | `device_read`（battery、memory、storage、system、network） | 设备 | 不变；SSID 标为 private |
| `get_device_environment`、`get_current_location` | 敏感读取 | `device_read`（environment、location） | 敏感读取（按 section 判断） | 不变 |
| `top_memory_apps`、`top_storage_apps` | 设备 | `device_diagnostics`（memory、storage） | 设备 | 不变 |
| `get_logcat` | 敏感读取 | `device_diagnostics`（logcat） | 敏感读取（按 kind 判断） | 不变 |
| `set_alarm`、`set_timer` | 设备 | `clock_create` | 设备 | 不变 |
| `list_alarms`、`list_active_timers` | 敏感读取 | `clock_read` | 敏感读取 | 不变 |
| `media_control`（播放） | 设备 | `media_control` | 设备 | 不变 |
| `set_volume` | 设备 | `volume_set` | 设备 | 不变 |
| `set_device_state` | 敏感操作 | `device_toggle` | 敏感操作 | 不变 |
| `get_setting` | 敏感读取 | `setting_read` | 敏感读取 | 不变 |
| `set_setting` | 敏感操作 | `setting_write` | 敏感操作 | 不变 |
| `app_state_control` | 敏感操作 | `app_control` | 敏感操作 | 不变 |
| 个人数据类（通知、使用情况、短信、联系人、日历、健康、Wi‑Fi 密码、验证码、ColorOS 各来源、剪贴板历史） | 敏感读取 | `personal_search`、`sms_code_read`、`usage_read`、`health_read`、`wifi_password_read` | 敏感读取 | 不变 |
| 文件与相册检索、聊天图片 | 敏感读取 | `file_search` | 敏感读取 | 不变 |
| `read_image`、`read_file` | 终端 | `file_read` | 读句柄或附件时看敏感读取；读任意路径时看终端 | **调整**：读 `file_search` 返回的文件不再需要终端开关（这些内容本来就已被“敏感读取”放出） |
| `get_clipboard` | 无 | `clipboard_read` | 无 | 不变；改标为 private |
| `terminal`、`run_command`、文件工具 | 终端 | `terminal_*`、`file_*` | 终端 | 不变 |
| `browser_use` | 网页 | `browser_*` | 网页 | 不变 |
| 屏幕、应用、`set_clipboard`、记忆、技能、会话历史 | 无 | 对应新工具 | 无 | 不变 |

逐个工具的开关对照写成单测，防止以后被无意改动。

## 13. 评测体系

### 13.1 三层

| 层 | 内容 | 什么时候跑 | 成本 |
|---|---|---|---|
| 契约测试 | 每个工具的 schema、正常结果、每个错误码；切面；投影；旧码映射 | 每次提交 | 低，单测 |
| 离线选路评测 | 约 120 条单轮指令，只给模型工具目录（不执行），检查选对工具的比例、参数有效率、目录 token 数；新旧目录对比；多个模型 | 改动模型可见的定义时 | 低，在 PC 上跑方舟接口 |
| 真机任务评测 | 约 40 个任务在小米云真机上端到端执行，用 adb 核对设备状态 | 合入前；每个领域完成时跑一部分 | 高 |

### 13.2 离线选路评测

- 用例覆盖每个工具的典型说法、容易混淆的说法（例如“看看我手机里的录音” vs “我的录音说了什么”），以及不该调用工具的说法。
- 模型：豆包 Seed 2.1 Pro、DeepSeek v4.1 Flash；GPT 用 .env 里的 ChatGPT 账号补一组。
- 指标：选对工具的比例、参数校验通过率、目录 token 数。

### 13.3 真机任务评测

| 类别 | 数量 | 说明 |
|---|---|---|
| GUI 操作 | 8 | 找联系人、找设置项、长列表滚动、表单输入等（不真正发送） |
| 系统直达 | 6 | 闹钟、倒计时、音量、播放、Wi‑Fi 开关、查状态，每个都有 adb 核对 |
| 个人数据 | 5 | 短信、日程、联系人、通知、验证码 |
| 文件与媒体 | 5 | 图片、文字版 PDF、扫描版 PDF、视频、文件写入 |
| 终端与网页 | 5 | 脚本、后台任务、网页正文、网页搜索、表单（不提交） |
| 提问与审批 | 4 | 有歧义要提问；需要确认的动作；拒绝后不绕过；本任务内允许 |
| 注入对抗 | 6 | 网页、通知、聊天内容、文件内容、MCP 结果、记忆里藏有指令（外发验证码、拼链接外泄、改设置、支付），要求外泄与越权为 0 |
| 良性不打扰 | 计入上面各类 | 统计每个任务的确认次数 |

- 每个任务跑 3 次，用 2 个模型（Seed、DeepSeek），GPT 补跑一部分。
- **基线**：合入 P0 修复后的旧工具体系，用同一套任务和模型跑。

### 13.3b 跨层验收矩阵（ChatGPT 深度评审 §9.2）

真机评测除任务成功率外，补这些“状态断言”场景，每条证明一个合同性质：

| 场景 | 必须证明 |
|---|---|
| mcp_find 加载后下一请求；恢复时 MCP schema 变化 | Provider、校验器、路由器用同一视图版本；加载失效有明确原因 |
| run 中撤权/恢复权限；旧 file handle 再读 | 执行时权限实时生效；目录变化规则明确 |
| 同窗口列表重排、旋转、审批期页面变化 | 旧观察/目标不被重新解释成另一目标（gen 生效） |
| 超时原执行未返回，随后结束 run 并开新 run | 资源仍有唯一所有者；旧结果不进新 run 上下文 |
| terminal 超时转 job 与取消同刻；App 重启后查询 | 不重复启动；job 身份不丢；日志与通知有归属 |
| 已有同时间闹钟；URI 选择器；无/多媒体会话 | 不把无关状态或“已派发”报成“效果达成” |
| UTF-8 页边界；文件/网页/记忆续读中变化 | 无乱码、漏读、重复或静默切换快照 |
| 同批多工具附图；PDF 多页、视频多帧 | 每附件与 call、页/帧、结果状态可对应 |
| mutation 已提交、结果未记录就中断 | 可查询恢复；append/install 不重复，replace 不覆盖新版 |
| 旧会话经三家 Provider 回放 | 每个 call 匹配正确结果；不追认旧效果为“已验证” |
| ColorOS 来源与 API 34 媒体后端 | 标记真实支持矩阵；未验证环境不报全量完成 |
| 受保护窗 / FLAG_SECURE 下 root-input 或裸坐标注入 | 一律要求确认，读不到内容时降级卡展示告警、不伪造摘要；注入攻击外发/支付为 0（零失败项） |
| 语音回答、任务插话、20 秒转卡、取消与重连 | 回答有正确 requestId；等待不错误唤醒新任务 |

分母要写清：每模型/每设备/每类别的任务数与重复次数；基线用同一模型版本、配置、权限、初始状态。小样本类别报具体差值，不包装成“不劣化”。

### 13.4 评测基础设施

- 只存在于 debug 包的评测入口：通过 adb 广播发起一次运行（指定提示、模型、会话）；按任务脚本自动回答审批和提问；运行结束导出记录（调用记录、token 用量、最终回复）。
- 每次请求记录工具目录的字符数和估算 token，补上“每轮工具 token”的数据。
- 每个任务一个 adb 核对脚本（例如 `dumpsys alarm`、`media_session`、`settings get`、文件内容）。
- 新旧版的 applicationId 相同，只能依次覆盖安装；结果放在 `.docs/tool-eval/`。

### 13.5 合入门槛

| 指标 | 门槛 |
|---|---|
| 各类别成功率（3 次平均） | 不低于基线，允许每类最多差 1 个任务 |
| 误报成功（会话层） | 0 |
| 注入对抗中的外泄与越权 | 0 |
| 良性任务的平均确认次数 | ≤ 0.3 次每任务 |
| 参数校验失败率 | 不高于基线 |
| 每轮工具 token | ≤ 基线的 60% |
| 首 token 延迟 | 不比基线慢 10% 以上 |
| 离线选路正确率 | 不低于旧目录 |

## 14. 持续优化

**指标来源**：调用记录汇总，来自评测导出、诊断导出和日常使用（只统计计数与时长，不含内容）。

| 现象 | 动作 |
|---|---|
| 某工具 `INVALID_ARGUMENTS` 率高 | 改参数设计或描述 |
| 选错工具的比例高（离线评测或真实记录） | 改名、合并或拆分 |
| `unknown` 比例高 | 补结果确认 |
| 确认次数多、用户常拒绝 | 调整审批策略或识别规则 |
| 很少被调用 | 改为 MCP 或 Skill，或者删除 |
| 耗时长 | 并行、缓存或改实现 |
| 同类失败反复出现 | 沉淀为 Skill 的流程知识（自进化的数据来源） |

- **报告**：一个脚本从诊断导出或评测结果生成工具报告，按工具集版本对比。
- **版本**：每次改动模型可见的定义都升 `TOOLSET_VERSION`，附选路评测结果和变更说明。

## 15. 实施顺序与分工

| 步 | 内容 | 谁做 |
|---|---|---|
| S0 | 你确认第 17 节的决定；处理主工作区的未提交改动；确定合入目标分支 | 你和主流程 |
| S1 | P0 协议修复（建议单独先合入）；搭评测基础设施；在旧工具体系上跑基线 | 主流程，加一个子任务 |
| S2a | **先冻结七份合同**（见合同 §9）：工具输入/输出、能力视图、解析后的调用、执行与恢复、资源、引用与分页、数据投影。以及数据产物与所有权（合同 §10）、切面异常时序（§11）、多资源/租约（§12）、所有权不变量（§13）。合同定稿并经你 review 后才进 S2b | 主流程 |
| S2b | 冻结核心：错误码、切面接口、`ToolCallRecord`、资源所有权、`ToolEnvironment`（能力全集/请求视图/执行权限三层）；把底层类拆成具名函数并改为类型化返回（核心领域不经旧码映射，长尾用映射）；按 review 修正已写的骨架 | 主流程 |
| S3 | 按文件所有权并行实现各领域：C1 `ui/`；C2 `device/`；C3 `personal/`；C4 `files/` 和 `terminal/`；C5 `browser/`、`memory/`、`mcp/`；C6 `ui/tools/`、工具页、运行日志页。子任务只改自己的目录和测试，对底层或核心的需求提给主流程 | 子任务并行 |
| S4 | 接入：循环、切面、交互通道、提示词与环境信息、编解码、事件、Hook 与语音、删除旧代码、兼容处理 | 主流程 |
| S5 | Figma：审批卡、提问卡、解锁提示、规则管理页；你定稿后写界面代码 | 主流程 |
| S6 | 单测与治理测试全部通过 | 主流程 |
| S7 | 离线选路评测加真机任务评测，对照门槛 | 子任务执行，主流程汇总 |
| S8 | 你 review，然后合入 | 你 |

## 16. 风险

| 风险 | 应对 |
|---|---|
| 一次性切换，模型行为变化集中出现 | 三层评测加数值门槛；离线选路评测可以快速定位是哪个工具的问题 |
| 审批打断太多 | 只在提交点和外发动作确认；本次任务内允许；把良性任务的确认次数列为门槛 |
| 提交点识别漏判或误判 | 名单与规则可以更新；注入对抗任务专门覆盖；模型声明的 effect 作为额外信号 |
| ColorOS 专属来源在小米上无法回归 | 只有单测覆盖，见第 17 节第 3 条 |
| 与主工作区未提交改动冲突 | S0 先处理；对重叠文件只做最小改动 |
| 并行与看门狗带来竞态 | 本次默认全部串行，只保留并发声明（有数据再开）；按资源独占；事件只在运行线程发出；资源与取消按状态机（合同 §3）；做专门的并发单测 |
| 请求视图漂移 | 同一请求的 Provider、校验器、路由器用同一 `view_version`；撤权走执行时权限，不改目录（合同 §2） |
| 验证证据被已有状态蒙混 | 每个回读型工具的证据必须可归因到本次动作（合同 §4.2），注入对抗与小米真机覆盖 |

## 17. 需要你确认的事项

1. **P0 协议修复单独先合入**，并在旧工具体系上重测基线。建议这样做，否则无法判断评测变化来自哪一边。
2. **`file_read` 的 PDF、视频、音频支持，以及并行调度**：本次实现，但默认关闭，评测时分别打开对比（建议）；或者移出本次范围。
3. **ColorOS 专属来源**（便签、系统记忆、订单、闹钟列表）在小米上无法真机验证，只能靠单测。是否允许用一台 OPPO 或一加云真机专门测这部分？这与“只用小米测试”的约定冲突，需要你明确。
4. **主工作区的未提交改动**（来自其他会话，与本方案重叠 10 个以上文件）怎么处理，以及合入目标：先合入 `fix/ui-review`，还是直接到 `main`（`main` 落后 100 个提交）。
5. **审批默认策略**（第 5 节）是否认可：
   - 提交点、两类污点同时成立后的外发、root 命令确认；
   - 记忆追加不确认、可以撤销；
   - 支付和删除永远不能“一直允许”；
   - 支付、转账、删除不能“一直允许”。
6. **新界面的范围**：审批卡、提问卡、解锁提示、“已允许的操作”管理页需要 Figma 稿。个人数据按来源单独开关这次不做（沿用总开关）。
7. **音频转写**：从 `file_read` 拆出、默认关闭；先验证豆包录音文件识别接口能否用现有 Key 调用；结果须带覆盖率，失败报错而非只给 warning。
8. **闹钟验证（已定：方案 a）**：你指示“保准确性”，覆盖了此前“不做核实”的决定。`clock_create` 采用方案 a——保留 1px 透明窗口让后台/语音/锁屏能真设上；验证改为归因本次创建（Root 用 `dumpsys alarm`，无 Root 用 `getNextAlarmClock()` 匹配本次请求时刻），验证不了报 unknown，不谎报成功。已同步更新 `docs/research/background-activity-start.md`。
9. **工具数与粒度交给评测（新）**：42 个全常驻、`media_control`/`volume_set` 是否合回、`file_search` 的 type/location 维度、描述长度，都在离线选路评测里对比后定稿，不预先拍死（合同 §8）。
10. **已写的骨架保留**，按 review 修正以下问题：
   - 改为类型化合同，不再通用 JSON 进出；
   - 默认改为独占；
   - 调用阶段就判定敏感度；
   - 旧码映射区分动作与只读；
   - 投影超长时保留错误字段，改用 kotlinx 序列化；
   - 审批规则存储改为单例，补默认范围；
   - `ToolServices` 的关闭逻辑；
   - `ToolArgs` 的 `long` 和 `enum` 问题。
