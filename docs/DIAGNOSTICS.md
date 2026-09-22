# 运行日志

入口：**设置 → 运行日志**。重试提示会显示错误码，并提示这一入口。

日志只保存在 App 主进程内存中，不写文件、数据库或备份。UI 与 Agent Runtime 共用此缓冲区；关闭日志页、切到桌面仍会记录。进程重启后旧记录消失，只显示新进程产生的事件。

最多保留 2,000 条、4 MiB 的日志文本，单条最多 4 KiB，按先入先出淘汰。页面显示条数、文本大小和淘汰数量；这些数字不代表整个 App 的堆内存占用。清空只删除当前记录，不停止任务。暂停刷新只冻结显示，不暂停采集。

## 排查一次重试

1. 出现提示后直接打开运行日志，先点“只看异常”。诊断前不要结束 App 进程，否则内存记录会丢失。
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
| `provider_error_code` / `provider_error_type` | 服务商结构化错误码，不保留错误正文 |
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
