# Movo 工具重构第四版复审

**结论：重构方向可以保留，七份合同目前还不宜冻结。** 最新文本已吸收类型化合同、三层能力视图、执行阶段、资源租约、观察代际与数据所有权，但若干关键段落仍停留在原则和待办清单；逐工具正文、执行切面和验收表之间也存在会影响实现的冲突。下一步应补齐下面的合同分支并统一三份文本，再进入批量领域实现。

本次审查对应用户称为“第四版”的修订。2026-10-03 16:44:45 +08 保存了三份完整快照，共 1,382 行；快照标题/版本栏仍为合同第一版、定义第三版、实施第三版，不以标题代替实际内容。文档身份与 SHA-256 见[输入清单](../../../tmp/tasks/2026-10-03-tool-redesign-v4-review/input-manifest.json)。下面所有行号指该快照；对应原文件在本次写作时未作修改。

本轮通读三份文本，并分别复核执行合同、逐工具与后端、Provider/MCP/实施验收。核对相关当前源码及官方协议；执行了两个自有本地文件实验。**没有调用模型、操作手机或运行新工具 E2E，不把“尚未实现”算作设计缺陷，也不将上一轮候选目录评测追认为最新版的成绩。**

## 1. 总体判断与范围

保留 `Registry / Provider / Pipeline`、内置能力按领域注册、展示与执行分离、默认串行，以及工具/MCP/Skill 的职责划分。当前最缺的是：同一调用的身份、授权对象、派发阶段、效果证据、恢复记录和资源所有权之间的可执行约束。

三份文本已明确本次不处理角色、语音等产品模式对 AgentLoop 的耦合。这是诚实的范围声明，但意味着本次只能验收“工具子系统合理”，尚不能达到用户最初提出的“整个 Agent 架构最合理”。本次至少应明确并落实工具、记忆、交互与资源经宿主绑定的接口，避免新工具核心继续依赖具体产品模式；完整的 Core/Host 分层仍需单独验收。没有证据要求增加 Planner、Judge 或多 Agent。

以下“冻结前”表示会改变接口或成功/失败语义；“实施/合入前”表示设计可以先收敛，但必须落实到执行顺序或验收。它们是设计问题，不代表当前 App 已发生对应故障。

## 2. 冻结前必须补齐的合同

### F01：七份合同仍是栏目清单，解析后的调用没有进入完整接口和时序

**位置：**合同 34–54、138–140、276–288；实施 131–155、500–501。

合同 §9 列了七份应冻结的内容，但没有给出完整的 `ResolvedCall/PreparedCall` 结构、字段生成者、持久身份与状态转移表。`classify(i)` 只在类型化入参层声明，七份表又要求绑定真实目标、会话身份、初始状态和资源；两者之间谁负责解析仍未定。

例如 `terminal_job.write(job_id, input)` 的执行身份来自已有 job，`ui_input` 的敏感度来自实际输入框，文件权限来自真实文件来源与目标身份。不能让审批、资源、执行分别再查一次并得到不同对象。实施方案也没规定默认串行时，是整批先 prepare/审批再 execute，还是逐调用准备；若整批先审批，`ui_input → ui_tap(提交)` 的卡片可能展示输入前内容。

**最小修订：**七份合同写成实际章节或附件，统一调用 ID、请求视图、目标身份、版本、资源键、执行阶段与恢复 ID。默认串行逐调用完成准备、审批、资源获取、临派发复核、执行、收尾。资源等待或前一个调用改变状态后，后续调用必须复核；目标或审批摘要变化则拒绝或重新审批。参数合法性与审批策略分开：合同 48 行“root 命令必须可解析，否则 INVALID_ARGUMENTS”与定义 432 行“解析不了仍可确认”也要统一。

### F02：到期强制回收与“未停稳不释放”相冲突

**位置：**合同 88、103、109–115、264–273；实施 169–171。

合同既规定 DETACHED 标忙超过上界“强制回收”，又规定超时不能释放仍被使用的设备资源。旧 Root 进程或不可中断的系统调用未停稳时，释放 lease 后允许新 run 获取同一资源，旧执行仍可能继续写文件或影响屏幕。丢弃晚到结果只能隔离消息，不能隔离副作用。

**最小修订：**区分“停止等待”与“确认停稳”。没有停稳证据时移交宿主隔离状态，继续保持 BUSY；不能仅到时释放。只有可证明底层已停止，或副作用入口确有代际约束时才能回收。定义 App/服务重启后的隔离与恢复。默认串行也不能消除这类跨 run 并存。

### F03：取消、派发和终态记录之间仍有冲突

**位置：**定义 67–68、95；合同 79–89、254–257；实施 147–155、239–241。

错误码将 `CANCELLED` 定义为“确定未执行”，生命周期又允许 RUNNING/DETACHED 取消。已派发动作被取消，不能因此追认成未执行。实施 F1 规定“运行已取消时直接抛出”，而合同要求取消与 prepare 拒绝均形成终态记录；无条件抛出可能跳过 F7。

**最小修订：**明确未派发、派发中、已派发的取消结果；取消与派发许可、取消句柄登记有同一个可验证边界。前置 epoch 检查不能让不可中断 Android API 自动失去副作用能力，越过边界后按证据给未知/已送达/已验证。所有退出路径保留终态记录，记录取消原因与原动作效果，而不是用取消覆盖效果事实。

### F04：恢复原则已写，逐动作恢复与最小持久回执尚未定义

**位置：**合同 243–257、283；定义 410–415、481–500、516–521；实施 147–155、450。

最新合同已正确说明“后处理失败不能反推没执行”，问题是正文尚未给 `file_write.append`、`skill_install.replace`、网页提交和动态 MCP 动作规定恢复方式。`memory_write` 返回 mutation_id，但没定义它何时持久生成、用什么入口查询、保留多久。只返回 `retry=observe` 并不保证恢复时有可用观察。

另一处窗口是动作已验证，F4/F7/F8 落盘或投影失败。规范 outcome 被定义为短生命周期，实施却直到 F7 才生成记录，没有说明如何保住或重建已验证事实。

**最小修订：**逐 `tool + action/mode` 冻结重放资格、操作 ID、查询方法、证据与保留期。派发前保存必要身份，完成后先保存最小回执，再生成各投影；投影失败可降级或重建。不需要引入全量事件日志，但必须有明确事务/轻量 journal 边界。对没有幂等或可查询证据的外部动作，保留 unknown 并禁止自动重放；本地 journal 不能让远端副作用天然获得 exactly-once 保证。

### F05：GUI 观察合同与参数正文不一致，输入失败还会改变请求语义

**位置：**合同 168–176；定义 99–104、258–282。

架构要求节点和坐标均绑定观察代际，定义的公共约定仍允许默认最近观察；`ui_tap` 只规定 index 时 observation_id 必填，`ui_swipe` 没有该字段且删掉 STALE_OBSERVATION。裸坐标无法证明模型依据哪一次截图。`ui_scroll/ui_input` 使用 index 时的条件必填也未统一。

`ui_input` 仍写“失败时改用 replace”。用户请求在已有文字的光标位置插入，执行失败后替换全部文字，是另一种编辑动作；不能以最终新文字回读一致证明原 append 成功。

**最小修订：**给所有基于观察的节点/坐标动作同一套引用参数与失效规则；正文、公共约定和错误码同步。运行时可换 set_text/paste 实现方式，但不得改变 append/replace 的目标结果。保留文字/selection 语义，不能确认时返回诊断结果，由模型显式决定下一步。submit 的派发与发送效果继续分开表示，最新版已有这一正确原则。

### F06：无 Root 闹钟验证仍可误报，倒计时缺对应证据

**位置：**合同 148、231–235；定义 310–315；实施 447、535。

保留透明窗口是既定产品选择，本次不否定该选择；问题在验证谓词。已有 07:00 闹钟时，新的 07:00 创建即使未生效，`getNextAlarmClock()` 仍可匹配 07:00，无法证明本次请求已执行，更不能证明 label/repeat/vibrate。该 API 描述的是下一次 alarm-clock event，不是通用倒计时列表。[Android 官方 API](https://developer.android.com/reference/android/app/AlarmManager#getNextAlarmClock())

**最小修订：**拆 alarm/timer、Root/无 Root 的证据表。能绑定本次独有目标或可归因变化才给 Done；复用已有目标则如实报告“目标状态已满足/复用”，不能说新建已确认。无 Root timer 没有查询路径时明确 Unknown。透明窗口改善派发条件，不等于所有入口/机型都一定派发成功，实际可用性仍需设备证明。

### F07：文件效果验证与版本承诺强于当前证据

**位置：**合同 154、185–193；定义 108、399–408、410–415。

“回读 size 或 hash”允许仅凭长度证明内容写对。本地自有实验中，期望 ABCD、实际 WXYZ 均为 4 字节，size 检查通过而内容不符。另一个实验保持同路径、同 inode、同 size、同 mtime 改写内容，文件哈希已变化：mtime+size 不是可靠内容版本；HMAC 防伪造也不能解决版本碰撞。实验见[受控观察](../../../tmp/tasks/2026-10-03-tool-redesign-v4-review/controlled-observations.json)，不是 Movo 执行器测试。

**最小修订：**覆盖验证目标字节/哈希；追加绑定写前版本并验证追加内容，处理并发变化。强一致续读绑定实际快照或足够的内容版本，并同时绑定对象与查询；若只用 mtime+size，则把保证降为尽力检测，不能承诺不会静默拼接两个版本。行号 offset 解决 UTF-8 分页问题，不自动解决源变更。

### F08：终端合并合理，但 TTY 输入、输出与跨会话通知未冻结

**位置：**定义 425–444；合同 117–127。

`terminal_job.write` 已有 input 字段，不能继续沿用旧评测中“四种字段名”作为最新版问题。但必填、编码、是否自动换行、控制输入、write 后等待的结果形状仍未定。真正 PTY 是合流终端字节流；正文的 stdout/stderr 分区，以及只给 keep_alive 标 streams:merged，并不覆盖所有 tty job。当前 ConsoleSessionController 确认它按原样 UTF-8/字节写入并合并输出。

keep_alive 归 App，却规定退出后在“下一次模型请求”前注入状态，没有写原会话归属、消费游标和去重。会话 A 的任务退出时，会话 B 可能成为下一次请求。

**最小修订：**区分 pipe 与 tty 返回；write input 必填且规定精确输入语义，TTY 读出是原始片段、清理后的文本还是屏幕快照。可以先支持行式交互，不要求本次完成所有 TUI。job 保存创建会话/调用、身份与退出事件；只向所属会话投递，其他会话显式查询。通知可使用固定的运行时状态文案，但输出/描述保持数据属性；跨重启消费可恢复。

### F09：固定 MCP 包装缺实际参数合同、版本失效与结果恢复表

**位置：**合同 62–71、200–203、254–256；定义 516–523。

选择固定 mcp_find/mcp_call 是明确的平台取舍，不把没用原生 tool search 当问题。但 `mcp_call(tool, arguments)` 的任意 JSON 对象，不能直接生成 OpenAI strict 下禁止额外属性的固定 schema。当前 Responses 显式 strict:false，不能据此声称现实现已发生 strict 错误；需要冻结的是未来适配的实际形状。[OpenAI strict 要求](https://developers.openai.com/api/docs/guides/function-calling)

内层工具在发现、审批与调用间发生 schema/注解变化时，请求 view_version 只保证本地一致，没定义旧发现结果失效和下一边界重发现。同样，普通 MCP 返回 isError、JSON-RPC 错误、请求派发后断网，不能共享一个“可修参数再试”的效果判断；文本、图像、音频、resource/resource_link 和 structuredContent 的保留/降级也需明确。[MCP 工具规范](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)

**最小修订：**给固定包装一份真实 wire schema/decoder，例如采用 arguments_json 字符串后按被冻结的真实 inputSchema 校验，或明确 non-strict 降级。绑定 server/tool/contract 代际，失效时禁止沿旧合同派发并给重新发现路径。结果逐块投影并说明遗漏；派发后传输故障默认未知，无恢复证据禁止重放。可选 MCP Tasks 扩展不构成本次阻塞，不要求为未协商能力增建任务框架。

### F10：协议投影缺 null 归一化、批次与缺附件回放规则

**位置：**合同 48–54、198–210、243–250；定义 99–104、233、399；实施 319–323、366。

strict 将可选字段变为 required+null，运行时同时要拒绝互斥或不适用字段。例如 app_open(package="x", name=null, uri=null, wait_ms=null)，要按非空目标计数、把可选 null 恢复为缺省/default；不能仅按字段出现判“三个目标”，也不能让 null 绕过真正允许 null 的字段语义。当前只有“按协议特化”原则，没有 canonicalization 表。

附件已有 source_call_id/index，正确解决了来源问题，仍需写它如何进入每家 Provider 的工具结果及缺图历史如何重放。当前 Anthropic 为每条 tool 建一条 user 结果；官方 API 会合并相邻同角色消息，所以不能据此判请求非法。显式按批组织结果与附件，仍比依赖服务端归一化更容易验证。[Messages API](https://platform.claude.com/docs/en/api/messages/create)、[并行工具结果格式](https://platform.claude.com/docs/en/agents-and-tools/tool-use/parallel-tool-use)

**最小修订：**每种协议给参数缺省/null 映射与结果块快照；按同一批次和原 call ID 关联结果，图放所属结果或明确来源的受支持载体。缺附件回放明确“附件不可用，需重新观察”，旧 observation/ref 不可继续使用。旧 validator 可以做其支持范围内的结构检查，权威类型 decoder 负责完整分支语义，不能把旧 validator 当通用 JSON Schema 引擎。

## 3. 实施与合入前仍需落实

### F11：来源分支、内部 CAS 与技能安装版本还需落到具体合同

| 位置 | 剩余问题 | 最小补充 |
|---|---|---|
| 定义 344–364 | notifications 整体支持时间过滤，但 Root 当前栏分支注明没有 posted 时间；统一 items 的 time/from/extra 也未给完整子类型 | 每后端明确事件时间与过滤谓词，无时间证据不混入时间命中；partial warning/不可用规则、排序与快照先定 |
| 定义 483–487 | append/replace 已承诺存储 CAS，模型不传 revision 本身合理；缺的是内部 expected_revision 捕获、审批复核与 mutation 查询边界 | 由 prepare 固定并由同一解析结果带入执行；或要求模型 revision，二者择一定稿；不要仅在 execute 读最新值后自比 |
| 定义 497–500 | inspect 本次任务有效、卡片展示 commit，不等于实际 install 必然安装该 commit | inspect token 绑定 commit、paths 与内容；install 消费这一绑定，漂移时重新 inspect/确认 |

当前 Root 通知响应没有时间字段；listener 具有 postTime 但现响应也未输出；历史表另有 posted_at。这里是能力合同与当前后端的待补映射，不是断言 Android 永远取不到通知时间。当前 memory 存储已有锁内 revision 比较，不把 CAS 当未实现缺陷。

### F12：实施文本仍保留旧规则，且整条链路接入过晚

实施 §3 已规定合同与工具清单权威，能判断总体意图；但后续迁移/单测表仍会带偏实现：

| 冲突 | 位置 |
|---|---|
| 清单要求每请求视图，接入表仍写运行开始固定目录 | 合同 63–71；实施 318 |
| replace 无污点默认不确认，实施表仍列 replace 为 external | 定义 120、485；实施 202 |
| clipboard_read 为敏感读取，开关对照仍写无开关、不变 | 定义 145、300；实施 397 |
| browser_open 会改变页面状态，总表仍标 read | 定义 162、450–456 |
| finish 保住结果并生成终态，F1 仍可直接抛出取消 | 合同 257；实施 148–155 |

**最小修订：**清理执行和测试会消费的旧表，标题/版本/批准状态同步。避免把“唯一权威”当保留互相冲突指令的理由。S2b 后增加代表性纵向链路：只读、GUI 动作、后台 job，经真实 AgentLoop 与 Provider adapter 验证视图、取消、投影，再开始 S3 全领域分工；不必等到 S4 才暴露核心接口不适用的问题。

### F13：状态验收已经列出，合入判定仍有宽限歧义

**位置：**实施 421–475。

跨层矩阵已覆盖许多重要反例，不能重复说“没有取消/恢复测试”。但其“必须证明”应明确优先于类别成功率宽限。提问审批只有 4 个任务：若其中一个在三次中都失败，平均每轮少成功一个任务，按“每类最多差一个任务”可允许 25% 下降。39 个分类任务称约 40 本身不是缺陷；真正缺的是最终 case ID、状态断言分母与各模型判据。

**最小修订：**将错误归因、不可重放副作用、跨 run 资源、失效引用、Provider 请求/回放与附件归属列成逐项零失败的合同门槛；与任务成功率统计分开。单轮预备观察和等价路径不按唯一工具字符串判错；最终任务效果另验。小样本按每模型/设备/类别报告具体差异，不用整体平均掩盖退化。

### F14：最终产物、默认关闭能力和成本指标还需一致

**位置：**实施 45、304–306、411–418、460–474、498–507、525–527；定义 523。

S7 评测后的 rebase/合并冲突可能改变核心、提示或 Provider。当前没有要求最终待合入树、APK、工具目录和配置与证据一致。PDF/视频/音频、并行默认关闭，目录和提示也必须投影真实开启能力，开启实验成绩不能验收关闭的发布配置。

UTF-8 字节除三不是经过证明的保守 token 上界；“固定 schema”也不证明整任务更省钱。每轮工具 token ≤旧版 60% 只衡量声明成本，MCP 检索结果、额外往返、领域提示、缓存及完成任务所需轮数均可改变总成本。

**最小修订：**绑定基线/候选源树、APK hash、目录 hash、模型配置和设备初态；合并后树变化时重跑受影响检查，树相同可复用证据。能力关闭时不向模型承诺该分支；开启/关闭分别记录。token 上界用实际 usage 校准，成本同时报告完成任务的总输入/输出、缓存和回合数；准确性与合同门槛优先于工具数量或单一 token 比例。

## 4. 已解决、排除及待实测

以下不再当作最新版缺陷：

- 第三版缺逐工具正文：最新版已有 42 个工具正文；仍需补的是上述具体分支与机器合同。
- ask_user 强制 options：已改可选，自由文本路径已定。
- 默认并行：实施已决定默认全部串行，骨架需按方案修正，不能把旧探针当最新实现成绩。
- 执行异常一概可重试、finish 失败一概没执行：合同 §11 原则已修；F03/F04 针对剩余执行时序和恢复合同。
- memory append/replace 没有 CAS：正文已承诺，现存储也支持 CAS；F11 只要求明确 expected revision 与恢复 ID。
- 图片没 call 归属、API 34 PDF 无降级、个人数据一律同时间语义：来源索引、降级与 source 能力表均已补。
- 没采用原生 MCP tool search 或没支持 Tasks：属于明确取舍/可选能力，不单独算设计缺陷。
- Anthropic 相邻 user 消息必然 400：官方允许合并，排除此结论；保留具体序列化合同与测试要求。

透明窗口在不同入口与设备的派发行为、Root alarm/timer 证据解析、ColorOS 数据源、PDF/视频结果质量、MCP 与三家 Provider 真请求、取消停稳、最终任务成功率均还需实现后的对应证据。不能以方案写明、源码可编译或旧 App 通过来追认这些结果。

## 5. 下一版应交付的具体内容

1. 七份合同的实际结构/转移表：字段、身份、时序、失效、证据、恢复和持久顺序；F01–F10 的反例有唯一预期结果。
2. 逐工具每分支一行：输入条件 → 真实目标/后端/权限 → 成功与未知证据 → 重放/恢复 → 边界用例；先补 GUI、clock、file_write、terminal、memory、MCP。
3. 清理三份文本权威冲突，补一次代表性纵向集成，再并行迁移领域。
4. 合入验收区分合同零失败、任务效果和成本，并绑定最终产物。

完成这四项后，才适合把合同标为冻结。本轮没有证据支持推倒重写，也没有证据支持将最新版标为“已达到最优”。

## 6. 关键文件与证据

- 三份输入：[合同](../../../tmp/tasks/2026-10-03-tool-redesign-v4-review/input-snapshot/Movo%20工具子系统合同与架构.md)、[定义](../../../tmp/tasks/2026-10-03-tool-redesign-v4-review/input-snapshot/Movo%20工具定义清单.md)、[实施](../../../tmp/tasks/2026-10-03-tool-redesign-v4-review/input-snapshot/Movo%20工具重构实施方案.md)。
- 专项过程：[执行与资源](../../../tmp/tasks/2026-10-03-tool-redesign-v4-review/runtime-contract-findings.md)、[逐工具与后端](../../../tmp/tasks/2026-10-03-tool-redesign-v4-review/domain-tools-findings.md)、[协议与验收](../../../tmp/tasks/2026-10-03-tool-redesign-v4-review/protocol-rollout-findings.md)。这些是过程复核，最终结论以本文的排除与边界为准。
- [本地文件受控观察](../../../tmp/tasks/2026-10-03-tool-redesign-v4-review/controlled-observations.json)：仅操作本任务 fixtures。
- [ConsoleSessionController.kt](../../../app/src/main/kotlin/io/github/fartown/movo/agent/terminal/ConsoleSessionController.kt)：PTY 合流与输入字节。
- [AgentMemoryRepository.kt](../../../app/src/main/kotlin/io/github/fartown/movo/data/repository/AgentMemoryRepository.kt)：锁内 revision CAS。
- [AgentStructuredDeviceTools.kt](../../../app/src/main/kotlin/io/github/fartown/movo/agent/tool/AgentStructuredDeviceTools.kt)：alarm/timer Intent 与当前通知响应。
- [AnthropicMessagesProvider.kt](../../../app/src/main/kotlin/io/github/fartown/movo/agent/model/AnthropicMessagesProvider.kt)、[ResponsesRequestBuilder.kt](../../../app/src/main/kotlin/io/github/fartown/movo/agent/model/ResponsesRequestBuilder.kt)、[AgentToolBatchRecovery.kt](../../../app/src/main/kotlin/io/github/fartown/movo/agent/model/AgentToolBatchRecovery.kt)：当前消息投影、strict:false 与中断补全。
- [上一轮评测](Movo%20工具重构评测报告.md)：其 320 次主请求对应旧冻结候选，不是本轮修订后的模型实测。

原三份方案与生产代码未修改；本次只新增复审、快照、过程记录和自有实验产物。
