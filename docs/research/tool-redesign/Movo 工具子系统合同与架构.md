# Movo 工具子系统合同与架构

| 项目 | 内容 |
|---|---|
| 版本 | 第一版，2026-10-03 |
| 定位 | 工具重构的**合同层**。先定合同再冻结接口，工具清单（[定义清单](Movo%20工具定义清单.md)）按本文填充 |
| 范围声明 | 本次是**工具子系统重构**，不处理角色、语音等产品模式对执行循环的耦合；完成后不等于“整个 Agent 架构已合理” |
| 依据 | 三组逐工具 review（`.docs/tool-design/review-tools-*.md`）、两路方案 review、ChatGPT 的架构 review；参照 pi、Codex、DeepSeek Harness、Aether 固定版本源码 |
| 参考 | Anthropic《Writing tools for agents》；OpenAI function-calling strict 模式；Android `PdfRenderer.Page#getTextContents()` |

## 0. 要解决的 8 + 2 个问题

| # | 问题 | 本文章节 |
|---|---|---|
| 1 | 注册表统一了入口，没统一合同：仍是通用 JSON 进出 | 1 类型化合同 |
| 2 | “运行内固定目录”与按需加载冲突：能力全集 / 请求视图 / 实时权限没分层 | 2 三层能力视图 |
| 3 | 执行状态与资源归属没闭合：超时返回后原调用跨 run 继续 | 3 资源与取消模型 |
| 4 | A/B 承诺要细到动作与验证证据 | 4 验证证据规范 |
| 5 | UI 引用简化弱化保护：要绑定观察代际与坐标空间 | 5 观察代际与坐标空间 |
| 6 | 合并工具要准确表达来源差异；参数不该静默忽略；cursor 要版本与失效 | 6 能力描述与分页 |
| 7 | 协议与多模态缺适配层：strict 模式、附件索引、PDF API 34 降级 | 7 协议与多模态适配 |
| 8 | 42 个全常驻只是候选，工具数与粒度要评测决定 | 8 成本与评测 |
| 安全 A | `file_write` 在 Root 路径、工作区外写入未确认 | 4.4、附录 |
| 安全 B | `browser_open` 未拦截 file、content、intent、javascript 协议 | 7.3 |

## 1. 类型化合同

### 1.1 为什么

现状（骨架）里一个工具只有一段 schema 加一个读 JSON 的 `execute`。模型看到的 schema、执行器取的参数、返回的字段、UI 读的字段、文档写的字段，各写一份，容易漂移。pi、Codex、dsh 的共同做法是：**一个工具一份权威的类型化合同**，schema、取参、校验、结果投影、文档都从它派生。

### 1.2 合同的组成

```kotlin
interface ToolContract<I : ToolInput, O : ToolOutput> {
    val name: String
    val domain: ToolDomain
    val summary: String                       // 给模型的描述，≤160 字，只写契约
    val input: InputSpec<I>                    // 类型 + schema + 解析 + 约束
    val output: OutputSpec<O>                  // 类型 + 给模型的投影 + 给 UI 的投影
    fun classify(i: I): CallClass              // 风险、敏感度、并发资源、是否回读型；可随参数变化
    fun availability(i: I, env: Capability): Availability
    fun approval(i: I, env: Capability, taint: Taint): ApprovalNeed?
    fun execute(i: I, ctx: ToolContext): Verdict<O>   // 见 §4
}
```

- **`InputSpec`** 同时承载：数据类 `I`、由 `I` 生成的 JSON schema（§7.1 按协议特化）、从 JSON 到 `I` 的解析、以及**条件约束**（例如“三选一”“root 身份时命令必须可解析”）。约束失败统一产出 `INVALID_ARGUMENTS`，并写清是哪个字段、为什么。
- **`OutputSpec`** 同时承载：数据类 `O`、给模型的投影（紧凑 JSON 或纯文本）、给 UI 的投影（结构化）、以及 §4 的成功谓词。模型投影和 UI 投影都从 `O` 派生，不各写一份。
- **文档、展示登记、契约测试**都从合同读取：§8 的工具文档由 `summary` + schema 生成；UI 展示登记的字段必须是 `O` 的字段；契约测试断言 schema 快照和每个错误码。

### 1.3 约束：不静默忽略不适用参数

合并类工具（`file_read`、`personal_search`、`terminal_run` 等）按 `action`/`kind`/`source` 分支。**不适用的参数不是忽略，而是报 `INVALID_ARGUMENTS`**，提示“参数 X 只在 Y 时有效”。schema 层用 `anyOf`-分支（仅在能表达的协议上），运行时用约束兜底。

## 2. 三层能力视图

把“目录固定”和“按需/撤权”分成三层，互不矛盾：

| 层 | 粒度 | 何时确定 | 用途 |
|---|---|---|---|
| **能力全集** | 本次运行 | run 开始时按设备条件、用户开关、会话类型冻结 | 决定哪些工具“存在” |
| **请求工具视图** | 每次模型请求 | 从能力全集投影，**带版本号**，在一次请求内不可变 | 下发给模型的目录；同一请求的 Provider、校验器、路由器必须用同一版本 |
| **执行时权限** | 每次调用 | 调用发生的瞬间实时判断 | 用户中途撤权、锁屏、无障碍断开立即生效 |

规则：

- **目录在一次请求内固定**：请求视图一旦生成就不改，保住提示缓存。
- **撤权立即生效**：工具仍在目录里，但执行时 `availability` 实时判为不可用，返回 `DISABLED`/`PERMISSION_REQUIRED`，不改目录。
- **MCP 按需**：MCP 工具数在预算内时逐个进请求视图；超预算时请求视图里放固定的 `mcp_find` + `mcp_call`（schema 固定，不随加载变化），因此视图版本不因“加载”而变——不存在“下一轮改目录”。
- **版本号**：请求视图带 `view_version`。校验器用这一版的 schema，路由器用这一版的工具集。模型若调用了不在本版视图里的工具，返回 `UNKNOWN_TOOL`。

## 3. 资源与取消模型

解决“超时先返回、原调用继续跑会跨 run”的闭合问题。

### 3.1 调用的生命周期状态机

```
PREPARING → (拒绝) → REJECTED
PREPARING → (取消，未派发) → CANCELLED                 # 确认未执行，错误码 CANCELLED
PREPARING → DISPATCHED → RUNNING → COMPLETED
                               └→ (看门狗到时) → DETACHED
RUNNING/DETACHED → (取消，已派发) → CANCELLING → 终态按证据：unknown / 已送达
                                                   # 不用 CANCELLED（那只表示确认未派发）
```

- **DISPATCHED**：已交给底层执行，分配了资源所有者。
- **看门狗到时**：不强杀。回读型返回 `unknown`，送达型按证据判定；调用转为 **DETACHED**，其占用的资源标记为“忙，归属该调用”。
- **DETACHED 的归属**：绑定到**运行**而不是单次调用。run 结束时，关闭顺序为：停止接收新调用 → 取消所有 DETACHED/RUNNING 调用 → 等待取消回执（有超时）→ **已确认停稳者释放资源、未停稳者移交隔离态（见 §12）** → 落盘。不凭“到时”无条件释放。
- **晚到结果**：DETACHED 调用在被模型读到结果之前就被 run 结束取消的，不再投递给模型；只写诊断。只有**显式声明可跨 run 存活**的资源（`keep_alive` 终端任务）例外，见 3.3。

### 3.2 资源所有权

| 资源 | 所有者 | 独占 |
|---|---|---|
| 屏幕、无障碍 | 运行 | 是，GUI 动作串行 |
| 剪贴板 | 运行 | 是 |
| 终端会话、终端 job | 运行（keep_alive job 归 App） | 按会话/任务 |
| 浏览器 | 运行 | 是 |
| 记忆、技能库 | 运行 | 是 |
| 每个 MCP 服务器 | 运行 | 按服务器 |
| 对话（ask_user） | 运行 | 是 |

取消一个运行时，按所有权逐个释放；每个资源的取消都要有确认回执或超时，不能 fire-and-forget。

**与现有运行时的映射**（实现对接点）：

| 合同概念 | 现有落点 | 需要补什么 |
|---|---|---|
| 资源 lease（owner+代际+实例键） | `AgentExecutionService` 的 `ExecutionLeaseRegistry`（attachOwner/drainOwner/acquire/release） | 从“服务级前台租约”抽象到“资源级租约”；并发 oracle 的 `exclusiveResource: String?` 改 `Set<ResourceKey>` |
| 调用状态机 PREPARING…DETACHED | `AgentRunController`（现仅 `cancelled` 布尔 + `register(cancel)`） | 加状态字段、run 级归属；DETACHED 标忙直到**确认停稳**才释放，未停稳移交隔离态（非“到时释放”） |
| 取消确认回执 | `AgentRunController.register` 返回的 `ResourceBinding.close()` | su 子进程用进程组 `kill -- -pgid`（`destroyForcibly` 杀不到 root 子进程）；裸 `Thread.sleep` 改可中断等待并 register |
| 晚到结果归档 | `AgentResultMailbox`（单槽 IPC 信箱，现直接丢弃晚到结果） | 另建“按 run 归档 DETACHED 结果”，不进新 run 上下文 |
| run 结束清理 | `AgentRuntimeRunExecutor` 的 finally（粗粒度 close） | 改为“停收→取消 DETACHED/RUNNING→等回执→停稳者释放/未停稳移交隔离→落盘”的分级 |

S2b 实现时按实施方案“默认串行”原则保持最小，不先把 lease 全套类建出来。

### 3.3 终端自动转后台的状态机

```
FG_RUNNING → (wait_ms 到) → BG_RUNNING(job_id) → EXITED(exit_code) → (本次任务结束) 清理
                                                     keep_alive: 跨任务存活，归 App
```

- 转后台时记录 `job_id`、`description`、`environment`、`identity`、`started_at`。
- 退出时写 `exit_code` 到任务记录（现状守护任务不记退出码，要补一个退出码文件）。
- **通知投递**：任务退出后，下一次模型请求前，以**系统身份**（不是“用户补充指令”）注入一条“任务 job_id 已结束，exit=N”的消息。复用 AgentLoop 的注入点，但用系统角色文案。
- 非 `keep_alive` 任务在本次任务结束时停止；`keep_alive` 任务跨任务存活，归 App 所有，用户可在终端页查看和停止。

## 4. 验证证据规范

A/B 承诺保留，但**每次调用必须给出验证证据**，证据必须证明的是“这次这个动作”，不能被已有状态蒙混。

### 4.1 Verdict

```kotlin
sealed interface Verdict<out O> {
    data class Done<O>(val output: O, val evidence: Evidence) : Verdict<O>  // status=ok, effect_verified=true
    data class Dispatched<O>(val output: O) : Verdict<O>                     // status=ok, effect_verified=false（送达型）
    data class Unknown(val reason: String, val next: String) : Verdict<Nothing>  // status=unknown；回读型未证实也走这里
    data class Failed(val error: ToolError) : Verdict<Nothing>                   // status=error
}
```

### 4.2 回读型的证据必须可归因到本次动作

| 工具 | 不合格的证据（反例） | 合格的证据 |
|---|---|---|
| `clock_create` | 系统“下一次提醒时间” | “下一次提醒”可能来自**已存在的**闹钟，不能证明本次创建。证据必须归因到本次请求的 HH:MM/时长：有 Root 用 `dumpsys alarm` 新出现的 ALARM_ALERT/TIMER_ALERT 匹配本次请求；无 Root 用 `getNextAlarmClock()` 匹配本次请求时刻。匹配到 → Done；匹配不到 → `Unknown`“已提交，未能确认”。**后台/语音/锁屏下保留 1px 透明窗口让派发成功（见 §4.5），透明窗口只负责“真设上”，核实仍由上述证据负责——这是用户 2026-10-03 定的方案 (a)** |
| `volume_set` | — | 回读该通道音量，考虑最低档和取整；一致 → Done，不一致但系统钳制（如免打扰）→ Done + warning + `actual_percent`，拒绝 → Failed |
| `media_control` | 媒体键已派发 | 媒体键已派发**不能**证明目标播放器已暂停 → 归**送达型**，`Dispatched`，描述写明需再查播放状态 |
| `app_open` | `startActivity` 没抛异常 | 回读前台包名等于目标；出现应用选择器（前台是系统 resolver）→ `Unknown`（未打开目标内容）；被后台启动拦截（不抛异常）→ `Unknown` + 权限提示 |
| `device_toggle` | 命令退出码 0 | 回读开关状态（Wi‑Fi/蓝牙的 ENABLING 要轮询到稳定）|
| `setting_write` | 命令退出码 0 | 回读设置值；被系统规范化（亮度钳制等）→ Done + `effect_verified` 基于回读值 |
| `file_write` | 无 | 回读 size 或 hash |

### 4.3 送达型的边界

送达型只承诺“系统已接收派发”，`effect_verified:false`，描述明确写“ok 只代表已送出，需再观察/查询确认”。关键反例：`ui_input` 文字回读一致，**不能**证明随后 `submit` 的消息已发送——`submit` 是独立的送达型动作，发送是否成功仍需观察。

### 4.4 外部影响的确认闭环（含安全 A）

`file_write` 写 Root 才能写的路径、或共享存储以外的路径、或向工作区外追加，一律按 external 确认（防止写入开机自启等持久化位置）。确认卡展示目标路径和内容摘要。

## 5. 观察代际与坐标空间（含安全保护）

### 5.1 代际绑定

- 每次 `ui_observe` 生成 `observation_id` 和**代际号**（gen），gen 绑定窗口指纹 + 内容版本 + 屏幕方向。
- `index` 必须携带它所属观察的 `observation_id`；运行时校验 gen 是否仍然有效。**不允许“默认最近观察”**——这正是弱化保护的地方：同窗口列表重排、旋转、审批期间页面变化，窗口指纹都盖不住，必须靠 gen。
- gen 失效的判定：窗口指纹变、内容版本变、方向变、或距观察超过阈值时间。失效返回 `STALE_OBSERVATION`，retry=observe。
- 动作结果**不返回新的 `observation_id`**，避免模型拿新编号配旧 index。

### 5.2 坐标空间

- `coord_space` 由 `ui_observe` 声明，按服务商固定缩放比例（截图实际发送尺寸），或 0–1000 归一化（按模型配置）。
- 节点 `bounds`、所有坐标参数都在这个空间里。坐标参数也绑定 gen：用坐标点击时，运行时按该 gen 的窗口校验，窗口已变则 `STALE_OBSERVATION`。

### 5.3 注入后端（root 与非 root 不是一条路）

GUI 动作有两种注入后端，是一等合同维度（进 `ResolvedCall` 与 `Verdict`）：

| 后端 | 机制 | 能力 | 验证 |
|---|---|---|---|
| accessibility（非 root 主路径） | `dispatchGesture`/`performAction` | 受系统限制：`FLAG_SECURE` 窗口、密码框、系统权限弹窗、屏蔽无障碍的 App 够不到或读不到 | 能回读节点，验证较强 |
| root-input（root） | `input`/uiautomator 按坐标注入 | **绕过上述限制**，能操作支付确认、系统授权弹窗、安全窗口，且能在**读不到节点**时凭坐标动作 | 坐标注入几乎无法回读，更倾向 `unknown` |

**安全后果与硬规则**：提交点识别和自我保护都依赖读节点树。读不到节点就凭坐标点击，恰好能在最危险的屏幕（`FLAG_SECURE`、支付、系统弹窗）上动作，使这两道防线在最危险处失效。**这不是 root 独有**——非 root 的无障碍 `dispatchGesture(x,y)` 同样能在无节点时按裸坐标派发（代码 `AgentAccessibilityService.gestureTap`）。因此规则**后端无关**：

- **任何后端（root-input 或无障碍 dispatchGesture）在“读不到可信节点就动作”** 时（受保护应用 / `FLAG_SECURE` / 纯坐标注入），**一律确认，绝不自动放行**；读不到内容不得声称“这不是提交点”。两种后端的真实差异在**验证强度与是否绕过系统限制**（root-input 还能点到无障碍够不到的安全窗），不在“是否能凭坐标动作”。
- **读不到内容时的确认卡（降级规范）**：不伪造内容摘要，展示“前台包名（或‘无法归因’）+ 坐标/区域 + 后端 + 告警：无法读取该屏幕内容，无法核实此处是否为提交/支付点，请你确认”。
- **自我保护在读不到包名时**：前台包名通常可从焦点窗获得；若覆盖层/多窗/安全窗无法可信归因到包名，有目标的 `ui_*` 按最保守处理（确认或 `POLICY_DENIED`），**不得因读不到包名而默认放行**。
- **root 下 `observe` 多读到的内容**（uiautomator 读非无障碍树）归入“读过不可信内容”这一类污点。外发确认按两类（不可信内容 + 个人数据）**是否同时成立**判定（定义清单 §0.6）。**注意：root-observe 单独不构成外发确认条件**——只读屏、未读个人数据时外发不因此拦截，这类注入靠域名白名单、提交点识别、受保护应用等其它防线兜，不要误以为 root-observe 本身已加固外发。
- **root 兜底必须显式声明**：不能默默从 accessibility 退回 root-input；退回时按本节规则提升确认（ui_observe/ui_tap 正文须对齐本节）。
- 非 root 下系统本身挡掉一部分危险（安全窗无障碍够不到）；root-input 这层没了，所以 root-input 在安全窗上的动作在上述“读不到节点”规则之外，还要额外确认。

## 6. 能力描述与分页

### 6.1 合并工具的来源差异必须准确

- `personal_search`：**不统一承诺所有来源支持 `since`/`until`**。联系人没有与短信同义的事件时间；每个来源在合同里声明自己支持哪些过滤维度、`id` 能否取全文、时间字段的语义。模型请求了来源不支持的过滤 → `INVALID_ARGUMENTS`。
- 通知、订单：跨“当前通知栏”和“历史记录”去重（按 key）；分页要稳定（不能靠“条数==limit”猜测）。

### 6.2 cursor 的版本与失效

所有分页 cursor（文件文本、网页正文、日志、记忆、会话历史、检索）都带：

- **绑定的数据版本**（文件 mtime+size、网页 navigationGeneration、记忆 revision、会话快照 id）；
- **失效规则**：版本变了，旧 cursor 失效，返回 `STALE_OBSERVATION` 或 `CONFLICT`，提示重新读取。

文本读取统一用行号 `offset`/`limit`（参照 pi），比不透明 cursor 更利于模型跳读。

## 7. 协议与多模态适配

### 7.1 schema 不是换壳就三家通用

同一份合同 schema 要**按协议特化**，不能只换外壳就认定 Chat Completions、Responses、Anthropic 行为一致：

- **OpenAI strict 模式**：要求对象所有字段都在 `required` 里（可选字段用 `nullable`）、不支持 `oneOf`、`additionalProperties:false`。本方案的“三选一”和“条件必填”在 strict 下只能表示成“全部可空 + 运行时校验”。
- **Anthropic**：顶层不支持 `oneOf`；`is_error` 要设置。
- **Responses**：不另走原生 tool_search / deferred-tool 的检索与缓存机制；工具目录在一次请求内固定、MCP 超预算时用固定的 `mcp_find`/`mcp_call`，缓存靠“固定前缀”保住，不依赖 Responses 专有特性。
- 适配层：合同声明中立 schema，适配层按目标协议生成具体 schema，并记录每种协议下哪些约束退化成了运行时校验。

### 7.2 多模态附件绑定具体调用

图片、PDF 页、视频帧作为附件发给模型时，必须**绑定产生它的调用和索引**：

- 现状是一批工具结果后统一追加一条“Latest observation image(s)…”消息（AgentLoop:343-386），多张图来自不同调用时对不上。
- 改为：每个附件带 `source_call_id` 和 `index`（PDF 第几页、视频第几帧、图片序号），模型投影里用这个索引引用。

### 7.3 协议与安全兜底（含安全 B）

- `browser_open` 只允许 http、https；在“模型主动导航”和“页面内跳转”两处都拦截 file、content、intent、javascript 协议（WebView 已开 allowFileAccess，必须补 `shouldOverrideUrlLoading`）。

### 7.4 PDF 的 API 34 降级

- `PdfRenderer.Page#getTextContents()` 从 **API 35** 才有；Movo minSdk 34。
- 降级路径：API ≥35 用 `getTextContents()` 取文字层；API 34 上没有文字层 API，整页渲染成图片交给模型看图，结果标 `text_layer:unavailable, rendered:true`，不谎称“提取了文字”。
- 加密 PDF → `UNSUPPORTED`。

## 8. 成本与评测决定工具形态

42 个全常驻、合并粒度、描述长度都是**候选**，由评测决定，不预先拍死：

- 统计总成本：目录 schema token + 领域系统提示 token + 检索（mcp_find）往返 + 追加工具的成本。
- 评测要允许一个任务有**多条正确的工具路径**，不把“用了另一种合理工具组合”判为错。
- 参照 Anthropic《Writing tools for agents》：工具少而正交、描述写清边界、返回对模型有用的结构。
- 工具数、是否再合并（如 `media_control`+`volume_set` 是否该合回、`file_search` 的 type/location 维度）、描述长度，都在离线选路评测里对比后定稿。

## 4.5 clock_create 的后台可用性（方案 a）

- 小米 HyperOS 在 Movo 无可见窗口时，后台 `startActivity` 会被静默丢弃（be8f2e5 实证，code=102）。保留 be8f2e5 的 1px 透明窗口，让后台/语音/锁屏下的创建真正派发。
- 透明窗口不参与“是否成功”的判断，成功只由 §4.2 的归因证据决定；因此不构成“绕过系统又谎报成功”。
- 派发顺序：挂透明窗口 → 派发 → 按证据核实 → 撤窗口。无法核实时报 `unknown`，不报成功。

## 10. 数据产物与所有权

一次调用产出四种产物，职责不重叠（ChatGPT 深度评审 §2.6）：

| 产物 | 内容 | 生命周期 | 谁读 |
|---|---|---|---|
| 规范 outcome | 类型化 `O`，短生命周期 | 本次调用 | 投影的唯一来源 |
| 给模型的内容 | 紧凑 JSON 或纯文本 | 进本轮上下文 | Provider |
| 脱敏历史 | 按敏感度裁剪 | 随会话持久化 | 回放 |
| UI 详情 | 结构化字段 | 随会话 | 界面展示登记（只读外观，不重推断 status） |

- 调用记录（`ToolCallRecord`）只保存**身份、事实状态、指标**，不充当“全部原始结果”或展示内容。
- 每个投影有版本、长度预算、字段裁剪规则；事实状态只有一份，来自调用记录。
- 旧会话无新 status：有旧结果与缺失结果区别处理，不追认旧效果为“已验证”。

## 11. 切面异常与阶段时序

- 调用阶段细分：**未派发 / 已派发 / 工具已返回 / 后置条件已验证 / 结果已记录**。`ok/error/unknown` 必须来自阶段证据，不从异常类型反推“没执行”。
- 远端完成主动作后返回业务错误、网页已导航未加载完成、写入成功但落盘失败，都不能判为“未执行”。
- 可重试由阶段 + 工具恢复合同决定，不由错误码默认 retry 决定；`INTERNAL_ERROR`、`NETWORK_ERROR`、MCP `isError` 不一概“再试一次”。重放安全性、幂等 ID、查询方法由工具声明。
- 切面异常约束：prepare 拒绝**仍生成 terminal 记录**；execute 成功后 finish 崩溃**必须保住已验证结果**；取消后形成 terminal 记录。只有 finish 层能改 status/敏感度/图片，且改动要可审计。

## 12. 资源模型（多资源、实例键、租约）

- **可并行资格**与**资源访问集合**分开。一个调用可能同时需要多种资源（屏幕 + 剪贴板）。
- 资源按**实例键**上锁，不是按类型全锁：终端按 `session_id`、文件按解析后的文件身份、浏览器与 MCP 按各自实例。把所有终端锁成一个 `TERMINAL` 会过度串行。
- 资源按稳定顺序原子获取，避免死锁。
- 设备级资源由宿主持有 **lease**（owner + 代际）。run 结束时：等执行停稳 → 移交 `keep_alive` 后台任务或保持隔离 → 再销毁服务。
- 看门狗置 DETACHED 后，资源**保持 BUSY 直到“确认停稳”**（底层进程确实结束，或该副作用入口有代际约束能挡住旧执行），不能仅凭到时就释放——否则旧 Root 进程未停稳、新 run 拿到同一资源会并存写文件/操作屏幕。无法确认停稳的，移交宿主**隔离状态**继续占用：隔离态要登记资源键 + owner + 代际，并声明**停稳检测方式**（进程退出回调 / 入口代际失配即视为旧执行已失效）、**恢复入口**（App/服务重启后按登记重建或清理）与**清理上界**（超过上界且检测到入口已换代际则回收，避免永久 BUSY）。晚到结果只进旧调用归档，不进新 run 上下文。“丢弃晚到消息”只隔离消息、不隔离副作用，所以副作用隔离靠停稳证据，不靠丢消息。

## 13. 所有权不变量（硬约束）

- 注册表不读取实时前台；
- 模型协议不拥有工具资源；
- 工具不自行写会话、不改 UI；
- 展示层不重新推断执行状态（只读外观）；
- 超时不释放仍被使用的设备资源（只有确认停稳或入口有代际约束才回收，见 §12）；
- 新的 memory/interaction/tool 绑定归宿主持有，给后续分层留接缝。

## 9. 冻结顺序：先冻结七份合同

| 合同 | 最低要写清楚 |
|---|---|
| 工具输入/输出 | 类型、互斥/条件参数、默认值、结果形状与成功谓词 |
| 能力视图 | run 绑定、每次请求目录、实时权限、tool_search 生效边界 |
| 解析后的调用 | 目标身份、会话身份、初始状态、风险/敏感度与资源需求（不可变解析结果） |
| 执行与恢复 | 派发阶段、取消、超时、幂等/重放、晚到结果与持久化顺序 |
| 资源 | 实例键、多资源、跨 run lease、释放与后台移交 |
| 引用与分页 | observation/ref/file/job/cursor 的版本、生命周期、失效 |
| 数据投影 | 模型/UI/记录/历史/多模态与旧版本的归属 |

顺序：1) 七份合同定稿并经你 review；2) 工具清单按合同填充；3) 离线选路评测定工具数与粒度（42 个常驻只是第一组实验，不是“最合理工具数”）；4) 冻结接口进入实现。

## 附录：安全修复清单

| 安全问题 | 修复 | 章节 |
|---|---|---|
| A `file_write` Root/工作区外写入 | 一律 external 确认，卡片展示路径与内容摘要 | 4.4 |
| B `browser_open` 非网页协议 | 拦截 file/content/intent/javascript，两处拦截 | 7.3 |
| C `personal_search` 验证码落盘 | 识别后打码，或整条升为 secret | 定义清单 22 |
| D Movo 自身界面被操作 | **有目标的** `ui_*`（tap/input/长按指定节点）对 Movo 包名直接 `POLICY_DENIED`；home/back/enter 等全局键不拒 | 定义清单 0.6/§14 |
| E 锁屏读敏感/外发 | 先要求解锁 | 定义清单 0.6 |
| F root 命令切分绕过 | 解析不了的命令视为“不匹配放行规则”，一律确认 | 定义清单 31 |
| G 记忆是持久注入通道 | 有污点时 append/replace 都确认 | 定义清单 37 |
