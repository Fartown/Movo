# 运行日志

入口：**设置 → 运行日志**。重试提示会显示错误码，并提示这一入口。

运行日志有两层：

1. **运行记录（元数据）**：任务的阶段、耗时、错误码和设备状态，就是运行日志页显示的内容。主进程内存里最多 20,000 条、8 MiB，单条最多 4 KiB；同时逐条追加到 `filesDir/diagnostics/events.log`，保留最近 20 个任务的全部记录和 7 天内任务之外的记录，进程重启后读回来（上次没结束的任务补一条 `run.interrupted`）。只记元数据，不含对话、工具参数和结果。
2. **完整运行日志**：每次任务另存一份原样记录，包含对话、思考、模型实际收到的请求和截图、工具的参数和结果，见下文「完整运行日志」。

UI 与 Agent Runtime 在同一进程，关闭日志页、切到桌面仍会记录。

## 排查一次重试

1. 出现提示后直接打开运行日志，先点「失败」筛选，再点开这次任务。
2. 点开 `模型请求失败` 或 `即将重试模型请求`，查看 `code`、`stage`、`http_status`、`causes`。
3. 点“查看该请求”查看同一个 `Q` 编号的完整链路；搜索 `R` 编号查看整个任务。编号只在当前进程有意义，精确匹配，不会把 Q1 和 Q10 混在一起。
4. 查看失败时的 `foreground`、`execution_service`、`network_validated`、`screen_on`、`device_idle`，并与同一时间附近的环境事件对照。仅时间重合不能证明后台状态就是故障原因。
5. 复制这条或最近 80 条匹配记录用于反馈；复制到剪贴板不会在 App 中创建日志文件。

| 字段 | 用途 |
| --- | --- |
| `purpose` | CHAT 普通请求、COMPACTION 上下文摘要、REPLY_REWRITE 改写 |
| `round` / `attempt` | 模型轮次、该次调用中的尝试序号（首次为 1） |
| `stage` | preparing 本地准备；dns/connecting/tls 连接；sending_body 写请求；awaiting_headers 等响应头；awaiting_body 等首批数据；reading_body 已收到过数据 |
| `duration_ms` | 该请求尝试的累计时长，包含设备休眠时间 |
| `last_byte_ago_ms` | 距最后收到响应数据的时长；unknown 表示尚未收到 |
| `last_sse_event` / `last_sse_ago_ms` | 最近完整 SSE 事件类型与距今时长，不保存事件正文 |
| `server_request_id` | 受控响应头里的服务商请求 ID，可用于服务商侧查日志 |
| `retry_after` | 服务商返回的 Retry-After；只是记录，不改变现有 2/4/8 秒重试策略 |
| `provider_error_code` / `provider_error_type` | 服务商结构化错误码；错误正文只在完整运行日志里（`attempt_end.response_body`） |
| `causes` | 最多八层异常类型，包括底层 SocketTimeoutException 等；不保留异常 message。Release 的自定义类名可能已混淆，需结合稳定的 code/status 判断 |
| `heap_used_mib` / `heap_max_mib` | 采样时的 Java 堆占用与上限，不含全部原生内存 |

`MODEL_TIMEOUT` 可能发生在连接、写入或读取阶段，需同时看 stage。读取等待上限为 300 秒，不是整个任务的总时限。`STREAM_INCOMPLETE` 表示缺少协议终态；例如 Responses 的 `[DONE]` 不能代替 `response.completed`，日志会分别记录这两种事件。`HTTP_429`、`HTTP_503` 等表示服务商返回了对应状态。

## 已完成却没有正文

Responses 请求额外记录“模型响应内容检查”（`response.output`），仍只保存类型、计数与编号：

- `stream_text_chars` / `stream_text_done_chars`：正文增量及完成事件里的字符数。
- `terminal_item_types` / `terminal_part_types`：最终输出项与内容类型计数；未知类型统一记为 other，不保留任意服务端文本。
- `terminal_text_chars` / `parsed_text_chars` / `parsed_tool_calls`：终态原始正文长度与实际解析结果。
- `response_id` / `server_request_id` / `incomplete_reason`：关联服务商日志与协议终止原因。

`MODEL_EMPTY_RESPONSE` 表示服务端发了完成事件，但既没有正文，也没有本地工具调用；不是网络超时。它在模型请求边界内失败，不再先记为成功、再由任务循环报“返回为空”。只有可安全重放时才沿用最多3次、2/4/8秒重试；已经启动托管工具的请求不自动重放。失败尝试不加入成功会话上下文，之前的工具结果保留。

`RESPONSE_OUTPUT_MISMATCH` 表示流中或终态有文字而最终解析为空；`RESPONSE_UNSUPPORTED_OUTPUT` 表示输出结构不受支持；`MODEL_REFUSAL` 表示拒绝，`MODEL_OUTPUT_LIMIT` 表示达到输出上限。这些不会当作临时空答自动重试。结构摘要受原有2,000条/4MiB、单条4KiB限制，不保存正文、思考文本或凭据。

2026-09-22，直接请求同一方舟`deepseek-v4-1-flash-260910`模型，在脱离Android的最小请求中复现了仅reasoning的completed响应：默认思考流式3/3为空，非流式1/3为空；显式`reasoning.effort=none`的3次对照均返回完整短文本。关闭思考是该次验证有效的绕过方式，不代表供应商问题已永久修复；不要仅凭“Off”的UI外观判断已生效，模型能力为未知时默认不发送reasoning参数。精确请求、结构摘要和边界见`tmp/tasks/2026-09-22-empty-response-root-cause/findings.md`。

每次调用都有新的重试预算，因此一个长任务可以在不同 Q 编号下多次出现 1/3。成功的工具结果仍按原行为保留。摘要子请求即使不投递聊天事件，也会进入内存日志。取消单列为 `attempt.cancelled`，不标成模型失败。

## 真机诊断示例

2026-09-22 在 Redmi K90 Pro Max 的正式 Release 上，用独立测试服务在 `response.created` 后停止返回数据，App 切到后台等待。日志实际记录 `MODEL_TIMEOUT`、`stage=reading_body`、`last_byte_ago_ms=300007`、`server_request_id=diag-slow-1`、`foreground=false` 和底层 `SocketTimeoutException`；两秒后，下一请求以 `diag-slow-2` 在后台成功完成。另一个连续返回 503 的用例记录了四次请求、2/4/8 秒重试间隔及服务商结构化错误码。

这些故障由测试服务主动注入，用于验证日志可用性。真实模型的自然长任务没有复现用户原始故障；新版自然熄屏用例还存在执行时序偏差，不能判定完整计划通过，也不能据此认定故障已修复。

## 实现边界

采集入口是独立的内存 sink，不依赖 Release 会裁剪的 DEBUG 日志。HTTP 监听器只记录阶段、计数与少量响应头；SSE 只记录事件类型和时间。不接收 API Key、完整 URL、请求/响应正文、对话文本、工具参数或工具结果。

系统直接杀进程时无法靠本进程内存保留最后现场；如遇此类问题，需要同时取设备进程/系统日志。当前功能不改变后台保活、电池优化、网络超时或重试次数。

背景机制与尚未证实的原因见 [Movo 长任务模型重试排查](research/background-model-retry/Movo%20长任务模型重试排查.md)。本轮真机与定向测试证据保存在 `tmp/tasks/2026-09-21-memory-diagnostics/`，是否复现原始故障以测试报告为准。


## 完整运行日志

方案与取舍见 [运行日志补全实施方案](solutions/run-log/运行日志补全实施方案.md)。只在手机版记录；电视版没有运行日志页，不记。

**记什么**：只记执行中本来就产生的数据，不为记日志额外截屏或读取设备状态；**原样记录，不做任何遮挡**——包括系统提示词和长期记忆、对话、思考、截图和屏幕文字、个人数据查询结果、输入的文字（含密码框输入）。

**放在哪**：`filesDir/run-log/<任务号>-<时间>/`，例如 `R57-20261006-145400/`：

- `log.jsonl`：按时间一行一件事。每行有 `seq`（连续编号，丢掉的行也占号）、`t`（类型）、`at`（墙钟毫秒）、`el`（距任务开始的毫秒）。
- `req/<尝试>-<第几次发送>.json.gz`：每次真正发出去的请求体，在服务商生成最终字符串之后原样保存（ChatGPT 账号在改写之后；401 后重发会多一份）。其中 `data:` 形式的图片抽到 `img/`、换成文件名；Anthropic 请求体里的 base64 图片原样留在请求体中。
- `img/`：截图和附图，按内容命名，同一张只存一份。
- `run-log/index.json`：任务和对话的对应关系，删除对话时用，不进导出。

| `t` | 内容 |
| --- | --- |
| `run_start` | 用户原话、附图、模型、服务商、接口地址、思考档位、是否语音、是否后台监听唤醒、App 版本 |
| `attempt_start` / `attempt_end` | 每次模型请求尝试：轮次、`retry_of`、用途、当轮权限档位；结束时状态（ok / failed / cancelled）、错误码、异常类名和原文、服务商错误原文、剩余的思考（带服务商原始类型）和正文、模型给的全部工具调用、`finish_reason`、用量、本机开始和结束接收的时刻 |
| `attempt_delta` | 思考和正文，每满 16K 字或 2 秒写一批 |
| `request` | 每次发送：尝试号、第几次、请求体文件、字节数、抽出的图片；没存时写 `dropped` |
| `tool_start` / `tool_end` | 工具开始（标题、实际执行的参数与模型给的不同时记 `args_exec`）和结束（状态、合同工具的原始结果类型 `outcome`、错误码、说明、交给模型的原文、图片、耗时）。有开始没结束的，就是执行中被停 |
| `user` | 停止（带来源：`app.stop`、`app.voice`、`app.yield_to_user`、`overlay`、`notification.end`、`runtime.replaced`、`external:<uid>` 等）、暂停、继续、补充、确认卡和提问及作答 |
| `event` | 上下文压缩、后台监听事件并入、服务商托管工具 |
| `system` / `meta` | 运行记录（元数据）的同步：不属于任务的环境事件写进当时所有进行中的任务；属于这次任务的原样同步（去掉两个本机内部 ID） |
| `gap` | 丢掉的行：`from_seq`–`to_seq` 和原因（queue_full / size_cap / write_failed） |
| `run_end` | 结束状态（completed / failed / cancelled）、错误码、异常类名和原文；永远是最后一行 |

**上限与生命周期**：普通记录排队最多 8 MB；图片和请求体按引用排队，合计最多 64M 字符，排不下时这一行照写、写明没存。单次任务到 40 MB 停存图片、50 MB 停存内容（控制记录照写）。保留最近 20 个任务、合计 200 MB，开始新任务前先删最旧的已结束任务。进程被杀的任务（没有 `run_end`）下次启动整个删除。删除对话时一并删除它的日志。写盘出错时这次任务停止记录内容，在运行记录里记 `run_log.write_failed`。备份和换机迁移都不带 `files/run-log`。

**实测（2026-10-06，REDMI K80 Pro，HyperOS 2.0）**：查看系统版本，5 次请求、9 秒，日志 63 KB + 请求 78 KB；查看电池耗电（被停止），32 次请求、70 秒，日志 370 KB + 请求 710 KB + 截图 1 张 252 KB。

**界面**（Figma 定稿 23，规范 8.10）：

- **导出**：运行日志 → 点开任务 → 右上角「导出」→「导出完整日志」，在系统文件选择器里选位置（默认 `Movo-R57-20261006-1454.zip`；选「下载」后可用 `adb pull /sdcard/Download/<文件名>` 拉下来）。zip 里是这次任务目录原样的 `log.jsonl`、`req/`、`img/`，外加导出时算出的说明 `README.txt`（gap、截断、没存的图片和请求体；用英文文件名，macOS 自带的 unzip 不认 zip 里的中文文件名）。只有已经结束、写盘没出过错的任务能导出：进行中写「任务结束后才能导出」；老任务和关着开关时跑的写「这次任务没有完整日志」。导出中图标转圈，转屏、离开页面都不打断。同一个菜单里「导出为 Markdown 文件」「复制到剪贴板」还是原来的元数据。
- **开关**：运行日志页最下面「完整日志」卡的「记录完整日志」，默认开，只存本地配置，下一次任务开始时生效；关上时问要不要同时清空已记下的。
- **清空**：同一张卡的「清空运行日志」（右侧是现在占用的空间），确认后删除已结束任务的完整日志和运行记录，进行中的任务和任务之外的系统事件保留，已导出的文件不受影响。

**取出来看（debug 包，不经过界面）**：`adb exec-out run-as io.github.fartown.movo tar -cf - -C files run-log > run-log.tar`。

**风险**：Agent 能用读文件、终端等工具读到这个目录，网页里的提示注入可能借此把内容带出去；按用户决定不加拦截，不想记录时可以关掉。
