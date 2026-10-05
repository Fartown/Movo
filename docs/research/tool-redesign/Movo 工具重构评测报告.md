# Movo 工具重构评测报告

本次已实际调用 **Seed 2.1 Pro、DeepSeek v4.1 Flash**，完成 40 个场景 × 新旧目录 × 两模型 × 两次预定重复，共 **320 次主评测请求**；另有 2 次工具调用连通性检查和 2 次无工具 token 基线，共 324 次真实模型请求。还编译了重构 worktree 的真实 Kotlin 核心，在隔离 JVM 中执行 32 项探针。

**结论：工具收敛确实降低输入成本，但当前证据不能证明新目录整体更准确，也不能证明重构架构已经达到最优。可以保留 Registry/Provider/Pipeline 的方向；正式冻结接口与验收前，必须补齐工具合同、执行阶段和调用视图。**

完整的[交互测试报告](../../../tmp/tasks/2026-10-03-tool-redesign-eval/report.html)包含 326 个独立配置/轮次、966 份内嵌证据，已通过报告校验；它记录模型决策和受控核心探针，不能替代手机任务验收。本文负责解释指标、人工复核和架构含义。

## 1. 测了什么，输入如何固定

| 项目 | 本轮实际输入与边界 |
|---|---|
| 旧目录 | 从当前 `AgentToolCatalog` 及实际 Kotlin helpers 独立编译导出；全能力、普通会话条件，共 82 项，包含 `conversation_history`。前 81 项与已有 dump 结构化逐项一致 |
| 新目录 | 评测期间定义从第二版更新到第三版。第三版快照只有 138 行概述，未提供逐工具参数正文。因此以第二版完整定义加第三版明确变化构造候选；无 MCP 时为 41 项，另一个条件工具为 `mcp_call` |
| MCP 场景 | 41 项加 `mcp_find`、`mcp_call`，共 43 项；两项参数缺正式合同，明确标为实验草案，只作诊断 |
| 共同能力 | 36 个场景，每模型、每目录 72 次请求；包括设备、GUI、个人记录、文件、终端、浏览器、技能、记忆、历史、自由提问和普通问答 |
| 单列场景 | 交互终端写入、PDF 指定页、音频转写、MCP 发现，共 4 个；旧侧缺能力或新侧缺规格，不进入共同能力比较分母 |
| 模型 | `doubao-seed-2-1-pro-260915`、`deepseek-v4-1-flash-260910`，本机已有 Agent Plan，真实数据面调用 |
| 请求 | `temperature=0`、`tool_choice=auto`，每次独立单轮；固定 seed 打乱配置顺序，并发 4。两次重复预先安排，没有失败后重试或挑选最好的一次 |
| 提示 | 两侧使用共同语义规则和等价状态 fixture，保留先取证、可靠目标、不同终端寿命、网页/外部 URI 分工等规则；没有把期望答案传给模型 |
| 参数校验 | 编译并运行真实 `AgentToolCallValidator`，逐调用检查实际模型返回的 arguments。另按用户意图检查模式、来源、目标及时间单位 |
| 骨架 | `Movo-tools` 冻结的 core/meta 源码；Android Context、Logger、ModelClient 等外部类型使用最小 stub，工具效果以合成计数器观察 |

本轮请求保存的是 **arkcli 的原始 JSON stdout**，其中含 response ID、function calls、usage 和文本；不是服务端原生 `output[]` wire 响应。请求证据保存实际脱敏 argv 和工具文件快照。

输入来源、转录和未定字段见[目录说明](../../../tmp/tasks/2026-10-03-tool-redesign-eval/catalogs/README.md)、[第二版快照](../../../tmp/tasks/2026-10-03-tool-redesign-eval/catalogs/design-v2-source.md)、[第三版快照](../../../tmp/tasks/2026-10-03-tool-redesign-eval/catalogs/design-v3-source.md)及[逐工具来源](../../../tmp/tasks/2026-10-03-tool-redesign-eval/catalogs/design-v3-provenance.json)。用例在主矩阵开始前冻结，SHA-256 为 `aedeeb1a9753281910bd39d8b8f5e9bc53f8dfcbe4cfef98ad18aa5b5eaa8c3f`；两份起跑前修订草稿也保留，没有改动已执行轮次的预期。

## 2. 成本收益已确认

这里统计共同 36 个场景的真实 `usage.prompt_tokens`，包括工具目录和消息，包含缓存 token，不能直接等同于费用。

| 模型 | 旧目录平均总输入 token | 新候选平均总输入 token | 减少 |
|---|---:|---:|---:|
| Seed 2.1 Pro | 12,453.1 | 7,684.3 | **38.29%** |
| DeepSeek v4.1 Flash | 12,060.1 | 7,314.3 | **39.35%** |

还用同一条 C39、同一份消息做了无工具请求，隔离目录带来的输入增量；两次重复的有工具读数相同。

| 模型 | 无工具输入 | 旧目录输入 | 新候选输入 | 相对无工具的旧/新增量 |
|---|---:|---:|---:|---:|
| Seed 2.1 Pro | 479 | 12,434 | 7,665 | 11,955 / **7,186** |
| DeepSeek v4.1 Flash | 454 | 12,041 | 7,295 | 11,587 / **6,841** |

这比“字符除以四”或设计中的估算更有证据：收敛后，这份候选目录确实节约约 4.7k 输入 token。它尚未包含新实现完整的领域提示、后续历史、多媒体和真实 MCP 定义，所以不能将这个数字当作最终产品的总上下文预算。

共同场景的请求耗时中位数：Seed 旧 3.76 秒、新 3.60 秒；DeepSeek 旧 1.61 秒、新 1.54 秒。这里包含 CLI 与网络开销，样本不足以承诺稳定的端到端加速。原始用量与完整性见[接口审计](../../../tmp/tasks/2026-10-03-tool-redesign-eval/models/matrix/audit.json)。

## 3. 选路结果必须结合人工复核看

下表是**冻结自动判据**的原始分数，不是手机任务成功率。“联合符合”要求选中预列路径、Schema 有效、参数满足 fixture 意图，且没有预期之外的附加调用。

| 模型与目录 | 预列选路符合 | Schema 有效 | 联合符合 |
|---|---:|---:|---:|
| Seed · 旧 | 72/72 · 100% | 72/72 · 100% | 71/72 · 98.61% |
| Seed · 新候选 | 70/72 · 97.22% | 70/72 · 97.22% | 67/72 · 93.06% |
| DeepSeek · 旧 | 67/72 · 93.06% | 72/72 · 100% | 64/72 · 88.89% |
| DeepSeek · 新候选 | 71/72 · 98.61% | 72/72 · 100% | 69/72 · 95.83% |

**不能据此直接说 Seed 退步或 DeepSeek 大幅提升。** 17 条自动未通过已经逐条独立复核：8 条是语义等价路径，7 条是预备观察/搜索，2 条是第三版合同缺失带来的候选 Schema 冲突。

- 文件追加走 Shell、在命令中先 `cd /workspace`、显式指定 `--bind 0.0.0.0`，均不能仅凭“不等于唯一字符串”判错。抽取的三条 `printf` 精确参数在本机字节实验中均产生 UTF-8 文本和 `0a` 换行；没有执行模型完整命令、启动服务或写入手机路径。
- 焦点输入框已知时再观察、已有稳定网页目标时再读取元素，会占用额外回合。它们没有完成本条动作，也没有证明任务失败。Linux 环境已安装并不证明 Python 已安装，先检查版本同样可以是合理准备。
- 未知地址的场景中，查保存地点或剪贴板也可能得到真实地址；没有结果时，不能说已填地址，也不应把安全的预备搜索归成能力缺失。

自动读数没有回填成人工通过。复核见[逐条判定](../../../tmp/tasks/2026-10-03-tool-redesign-eval/adjudication/adjudication.json)和[字节实验](../../../tmp/tasks/2026-10-03-tool-redesign-eval/adjudication/printf-fixtures.json)。本轮支持“精简目录不会普遍让模型无法选路”，但**不足以确认整体准确率提升**。两个重复属于同一场景，不能当成 72 条完全独立业务样本。

## 4. 工具合同中已经观察到的实际问题

### 4.1 `terminal_job.write` 只有动作名，没有可执行的输入合同

同一个“在已有交互任务里运行 pwd”的场景，四次新候选返回：

| 配置 | write 载荷 |
|---|---|
| Seed · 第一次 | `text:"pwd\n"` |
| Seed · 第二次 | 仅 `action`、`job_id`，没有命令或输入字节 |
| DeepSeek · 第一次 | `command:"pwd"` |
| DeepSeek · 第二次 | `input:"pwd\n"` |

四种都通过当前 Schema validator，因为新增动作没有必填输入字段，也未禁止未知字段。这证明 **Schema 有效率 100% 可以掩盖合同无法执行**。第二条连请求的命令都没表达；另外三条依赖实现猜字段名。

应先定清：输入字段、字节/文本、是否自动补换行、Ctrl-C 等控制输入、等待时长、输出位置和结束状态。再从同一声明生成模型 Schema、解码和文档。具体证据见[C27 复核](../../../tmp/tasks/2026-10-03-tool-redesign-eval/adjudication/c27-write.json)。

### 4.2 自由问题与必填 options 的矛盾确实影响调用

未知收件地址场景中，Seed 两次都正确选择提问，却只传 `question`（一次另带 `allow_free_text`），均被候选校验器拒绝：`arguments 缺少必填字段 options`。

这是第二版“options 必填”的可重复冲突；第三版尚无逐项参数正文，而引用的 review 已建议 options 可选。因此本轮将它作为**待定合同问题**，不能说最新正式实现已经回归。应把自由文本提问明确写进最终 Schema，并取消为了满足字段而编造地址选项的要求。

### 4.3 MCP 元工具和媒体场景仍不能作正式通过结论

新候选可让两个模型选到 PDF、音频读取和 MCP 发现入口，但这只证明下一步表达。`mcp_find`/`mcp_call` 的正式字段未定，音频/PDF 后端也没有作为新执行器接入本轮，所以没有测得“正确加载外部工具”“转写成功”或“PDF 页内容准确”。

第三版目前只有概述，也使 `action/mode`、终端参数重命名、音频开关与逐项默认值的最终含义不够确定。**先补完逐工具正文及权威 Schema，再扩大选路评测**，比继续在总表上调整工具数更有价值。

## 5. 核心架构探针复现了哪些偏差

第一轮 28 项探针的分析判定为 26 PASS、1 FAIL、1 INCONCLUSIVE，属于自制原始事实报告。随后 4 项以独立方案判据补测，按 test-workflow 完整记录，结果为 1 PASS、2 FAIL、1 INCONCLUSIVE。它们都针对冻结的旧骨架，不追认成第三版新实现的结果。

| 探针 | 实际观察 | 判定与架构含义 |
|---|---|---|
| 未加载 DEFERRED 工具 | 目录未列出，但直接 `ToolPipeline.execute` 返回 `ok` | 在管线接口边界存在偏差；AgentLoop 上游 validator 能否完整阻止，未运行确认。第三版已提出改用 MCP 包装，此项需随最终调用视图重新定合同 |
| 真正未覆写 concurrency 的 READ/LOCAL 工具 | 两者 `exclusiveResource` 都为 `null`，默认并行 | **FAIL**：与方案默认互斥、显式允许只读并行不一致；未来添加动作工具时容易漏声明资源 |
| 合成动作已产生副作用后抛异常 | effect 计数为 1，返回 `error/INTERNAL_ERROR/retry=later`，提示可再试 | **FAIL**：通用异常分支无法表达执行阶段，可能引导重复动作；不是某个真实手机控制器的运行结论 |
| 权限环境在请求后变化 | 未刷新 catalog 时仍用旧 env；刷新后返回 `PERMISSION_REQUIRED` | 只证明刷新生效，未证明调用时实时复核；需要将请求视图与实时权限分开 |
| 有效 JSON 但必填参数缺失/类型错误，直接进入 Pipeline | 合成 execute 被触发两次 | **INCONCLUSIVE**：管线没有该校验，上游已有 validator；应明确完整链路的唯一校验职责并补集成测试 |
| 30k 字符文本结果 | 返回 30,011 字符 | **INCONCLUSIVE**：24k 常量只约束 JSON，文本预算由工具侧承担。没有用错误判据硬判产品 FAIL |

原始证据见[首轮探针](../../../tmp/tasks/2026-10-03-tool-redesign-eval/runtime/report.md)、[补充报告](../../../tmp/tasks/2026-10-03-tool-redesign-eval/runtime/followup/report.html)及[真实核心快照](../../../tmp/tasks/2026-10-03-tool-redesign-eval/runtime/source-sha256.txt)。

这些观察支持上一轮的结构判断：注册表统一名称与入口还不够。合理的核心合同至少需要：

1. **一次调用共享一份解析结果**：类型化参数、目标/会话身份、版本、风险、敏感度和资源需求；避免模型 Schema、审批、执行、展示分别解释同一字段。
2. **请求视图与执行复核分开**：本轮模型、validator、router 使用同一工具视图；调用开始及审批后重新检查实际权限和目标。MCP 绑定和资源身份有明确生命周期。
3. **按阶段产生结果**：未派发、已派发、已返回、已验证、已记录，分别决定 `error/unknown` 与重试。资源仍在运行时，不能只因 run 返回就释放或丢弃所有权。
4. **默认资源规则可执行**：动作默认互斥；显式只读并行；多资源/实例键和跨 run 任务由宿主维护，不能依赖每个工具记住覆写默认值。

这轮没有证据要求增加 Planner、Judge 或多 Agent；优先把以上合同做完整。

## 6. 现在距离目标还差什么

| 优先级 | 必须补齐的内容 | 下一轮可验收的事实 |
|---|---|---|
| P0 | 完整第三版工具定义、单一 Schema/解码/默认值来源；补 `write`、自由提问、MCP 元工具 | 真正导出新 Registry 目录；未知字段、动作必填、时间单位与文档一致，模型不会依赖猜字段 |
| P0 | 调用视图、实时权限、执行阶段和异常恢复 | 经真实 AgentLoop 调用：撤权、未展示工具、审批等待后目标变化都按约定处理；产生副作用后异常不会指导无条件重放 |
| P0 | 并发默认值、资源锁和跨 run 生命周期 | 超时/取消后旧执行停稳或移交；下一 run 无资源重叠，后台任务身份和输出可恢复 |
| P1 | 领域提示与三家 Provider 接入 | 用最终新旧导出和真实生产提示复测；Anthropic/Responses/Chat 的错误、附件、历史回放语义一致 |
| P1 | 新旧同任务真机回归 | 打开应用、闹钟、媒体、输入提交、文件、终端服务等用设备状态核对；记录误报完成和重复副作用 |

本轮是 **40 场景的先导评测**，尚未达到方案约 120 条离线指令、约 40 个完整真机任务的合入评测规模。没有执行新工具手机 E2E：截至本轮检查，`Movo-tools` 仍是 core/meta 骨架，没有完整的新领域执行器。现有旧 App 的真机通过也不能验收这份新设计。

能够确认的是输入成本收益和若干具体合同问题；尚未确认的是最终架构最优、完整任务成功率、状态准确性、三家协议一致性和真实设备效果。**下一步应先冻结能执行的合同，修正骨架，然后按最终产物复测，而不是按本轮候选目录成绩直接合入。**

## 7. 证据与复现

- [冻结用例与判据](../../../tmp/tasks/2026-10-03-tool-redesign-eval/cases/decision-cases.json)、[输入 manifest](../../../tmp/tasks/2026-10-03-tool-redesign-eval/cases/manifest.json)。
- [旧目录实际导出](../../../tmp/tasks/2026-10-03-tool-redesign-eval/catalogs/old-tools.json)、[新候选](../../../tmp/tasks/2026-10-03-tool-redesign-eval/catalogs/new-tools.json)、[旧目录编译脚本](../../../tmp/tasks/2026-10-03-tool-redesign-eval/catalogs/compile-old.sh)。
- [全部自动读数](../../../tmp/tasks/2026-10-03-tool-redesign-eval/analysis/scored-records.json)、[聚合指标](../../../tmp/tasks/2026-10-03-tool-redesign-eval/analysis/summary.json)、[人工复核](../../../tmp/tasks/2026-10-03-tool-redesign-eval/adjudication/adjudication.json)。
- [评分脚本](../../../tmp/tasks/2026-10-03-tool-redesign-eval/score_matrix.py)：只重读冻结响应并运行真实 Kotlin validator，不重新调用模型、不执行手机工具。
- [HTML 与证据完整性校验](../../../tmp/tasks/2026-10-03-tool-redesign-eval/analysis/validate-report.log)：326 配置/轮次、966 份证据、`valid:true`。没有打开浏览器；不将报告脚本校验视为产品验收。

本次新增评测脚本、快照、用例、证据和本报告，没有修改生产代码、原方案或重构 worktree。
