# Eta 语音输入与自定义唤醒词方案

> **方案范围更新（2026-09-22）**：后续完整语音助手交付以 [Movo 连续语音对话方案](solutions/voice-conversation.md) 为准。用户已明确要求自然停顿自动发送、有声回复、打断与连续多轮，本文件原先将这些能力后置的范围被新方案取代。以下保留历史设计和已实现记录；既有手动停止测试不能作为完整语音对话验收。

> 状态（2026-09-22）：已接入豆包双向流式 ASR 和内置 Sherpa-ONNX 本地唤醒模型；Release 云真机验证了语音输入与唤醒链路，权限撤销/恢复修复已复测。仍观察到识别文字差异及提交后模型空响应，详见本轮测试报告。
> **听写策略更新（2026-09-22）**：语音输入仅使用豆包；未配置凭证时提示前往设置，连接或识别失败时提示错误并结束本次听写，不切换系统识别。
> 调研仓库：当前 cloud checkout（Origin `superchao/genesis`，内容对应 Fartown/Movo / Eta Android 应用）  
> 版本锚点：`applicationId io.github.mangi.eta`，`versionName 3.0.5`，`minSdk 34` / `targetSdk 36`
> 读者：产品负责人（Chao）与后续实现同学  
> **已拍板（2026-09-20）**：① App 打开后即常听（等待唤醒词）② ASR = **豆包语音双向流式 WebSocket（BYOK）**，文档见 [双向流式语音识别 WebSocket](https://docs.volcengine.com/docs/DoubaoVoice/bidirectional-streaming-automatic-speech-recognition-websocket?lang=zh) ③ 默认唤醒词 **「小王同学」**，且允许用户在设置里修改

---

## 2026-09-22 实现更新

当前代码已接通豆包流式听写与内置 Sherpa-ONNX 本地唤醒。后续第 0–7 节保留最初的调研与设计背景；当前行为以本节、代码和实际测试记录为准。

- 豆包请求序号与 WebSocket 发送保持同一顺序；结束前停止采集，发送唯一结束帧并等待服务端 final，错误与取消均结束本次听写。
- 聊天栏实时同步转写，停止后提交一次并清空；浮窗支持唤醒后自动开始听写。
- 唤醒使用固定版本 `com.k2fsa:sherpa-onnx:1.13.8@aar`，中英文 KWS 模型随包提供，路径为 `app/src/main/assets/sherpa-kws/`。来源和校验摘要在 `SOURCE.json`，中文词典生成脚本为 `scripts/generate-wake-lexicon.py`。
- 自定义词支持词典覆盖的中文与英文单词。非法词拒绝保存；修改后重建仅含当前词的关键词图。没有系统识别降级路径。
- 麦克风权限成功后持久化开启状态。缺权限时不启动麦服务；停止或销毁时清理通知；返回 App 时重新检查权限。通知分别表示启动、实际监听、听写暂停和失败。
- Release 3.0.5 的修复构建及本轮云真机证据保存在 `tmp/tasks/2026-09-22-voice-fix-regression/`；`build-r2.log` 对应 30 项语音相关单测与 Release 构建。测试报告区分旧包失败、首次修复、权限状态追加修复。

已在小米 15 / Android 15 用扬声器实际播放、设备麦克风实际采集，验证输入框转写、停止单次提交、默认词、自定义词从桌面唤起、旧词失效与浮窗听写。录音长时间保持开启时观察到额外文字，来源未确定；短时测试仍有只/知等同音字差异。上述测试验证交互链路，不能代表任意语音均逐字正确；长时间息屏、通话中断与其他机型尚未覆盖。

第一版发现撤销麦克风权限后残留启动通知、恢复权限不自动监听；第二版 `versionCode 2026092202` 已在同设备复测通过通知清理、权限恢复与自定义词/短听写。第二版最后一次提交后模型返回为空并显示 Runtime 错误，未得到正常回复；该观察与语音提交成功分别记录。默认词成功证据来自第一版，没有在第二版重复测试。

### 设置页交互更新

本轮再次合入本地 main 工作区新增的 10 个文件改动，源工作区的暂存、未暂存和未跟踪文件保持原状。设置首页整理为监听状态、当前唤醒词、灵敏度与豆包配置入口。词语和凭据分别编辑，未修改时禁用保存；保存失败保留草稿，非法词不覆盖当前词。编辑页处理输入法空间，恢复默认与日常保存分开。

凭据默认遮掩，可主动显示/隐藏；认证方式明确选择 Api-Key 或 App-Key + Access-Key，只保存所选方式，Resource-Id 收起在高级设置中。清除前确认；连通测试仅使用已保存配置，发送短合成静音而不打开麦克风，等待正常最终协议响应，错误帧、拒绝、超时不能显示成功。首页监听状态与服务共享状态流，缺麦权限、通知、悬浮窗或默认助理时提供相应入口。构建、合并保全证明及真机验收位于 `tmp/tasks/2026-09-22-voice-settings-polish/`，结论以各轮报告为准。

第三版 `versionCode 2026092203` 的真机回归发现：系统返回能提示未保存修改，顶栏返回却会直接丢弃草稿，词语和凭据两页均受影响。第四版让返回回调在点击时读取当前字段，并在提示前收起键盘；凭据保存同样读取点击时的字段。第五版 `versionCode 2026092205` 进一步将通知入口直接连到本 App 的系统通知设置，避免权限拒绝后重复申请而没有可见反馈。麦权限缺失时，开关表示已保存的开启意图，状态明确提示缺少权限，仍允许用户关闭；不会把开启意图显示为正在监听。

## 0. 一句话结论

Eta **已经具备「系统助理入口 → 浮窗 → Runtime 对话」整条链路**，但浮窗与聊天栏目前都是**纯文字输入**；`RECORD_AUDIO` 已声明却从未运行时申请或使用。

按已拍板方向，目标体验是：

1. **用户打开 App（并授权麦克风）后，开始常听**，用本地唤醒引擎等待唤醒词；**不是**把整段环境音持续上传云端。  
2. **出厂默认唤醒词为「小王同学」**，**用户可在设置中随时改成自己的短语**；改完热加载到 KWS，无需重装。  
3. **唤醒成功后**进入听写窗口，音频经 **豆包双向流式 ASR WebSocket（BYOK）** 实时上屏，最终文本注入 `submitPrompt` / `sendCurrentMessage`。  
4. 与「Hey Google 自愈」、小布/小爱接管无关；常听 = 新的 `microphone` 前台服务 + 本地 KWS。

**推荐栈：** 唤醒 = **Sherpa-ONNX KWS（主）** / Porcupine（备）；听写仅使用 **豆包双向流式 WebSocket（优先优化版 `bigmodel_async`）**。缺少凭证或识别失败时提示错误并结束听写。**不再以 Flash/文件识别作主 MVP。**

---

## 1. 当前应用语境与约束

### 1.1 产品是什么

Eta 是面向 Android 14+ 的**第三方系统级 AI 助手**（单模块 `:app`）：

- 应用内聊天（Compose + MIUIX）
- 数字助理角色：电源键 / `ACTION_ASSIST` / `VoiceInteractionService` → 全屏浮窗
- 可选 LSPosed：接管小布、超级小爱、Gemini 相关能力
- Agent Runtime 在 App 内跑 Agent Loop；模型 **BYOK**（OpenAI / Anthropic / 百炼 / DeepSeek / Kimi / 硅基流动等）

### 1.2 文字输入 / 助理 / 媒体现在在哪

| 能力 | 现状 | 关键路径（绝对路径前缀 `/workspace/`） |
| --- | --- | --- |
| 应用内聊天输入 | `AgentChatInputBar`：文本框 + 附件 + 发送/停止，**无麦克风** | `app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentChatInputBar.kt` |
| 提交消息 | `SubmitMessage` → `AgentAppState.sendCurrentMessage` | `ui/app/AgentAppRoot.kt`、`AgentAppState.kt` |
| 数字助理浮窗 | `EtaAssistantOverlayService` + `EtaVoicePanel`：**键盘文本** | `agent/voice/EtaAssistantOverlayService.kt`、`EtaVoicePanel.kt` |
| 浮窗提交 | `submitPrompt(text)` → Runtime，`handoff.source = "eta_voice"` | 同上 + `agent/runtime/AgentRuntimeWire.kt` |
| 系统入口 | VIS / ASSIST / 电源键 Hook | `EtaVoiceInteractionService.kt`、`hook/system/` |
| 图片附件 | 相册选图，无拍照 CameraX | `AgentChatFileAttachments.kt` |
| TTS / 朗读 | **无**（文档明确：当前不语音朗读） | `docs/TECHNICAL.md` |
| 厂商语音 | 小爱/小布接管复用**厂商 ASR/TTS**，不是 Eta 自己的麦 | `hook/xiaoai/`、`hook/breeno/` |

**文字 → Agent 数据流（已存在，语音只需接到「文本」这一步）：**

```
用户文本
  ├─ 应用内：AgentChatInputBar.onSubmit
  │     → AgentAppState.sendCurrentMessage
  │     → AgentRuntimeClient.run (handoff: agent UI)
  └─ 浮窗：EtaVoicePanel → submitPrompt
        → AgentRuntimeClient.run (handoff: eta_voice, 可附屏幕截图)
              → AgentRuntimeService → AgentLoop → OkHttp SSE 云端模型
```

### 1.3 现有「语音」相关代码（容易误解，需分清）

| 组件 | 作用 | 与「Eta 听你说话」的关系 |
| --- | --- | --- |
| `RECORD_AUDIO` 权限声明 | Manifest 已声明 | **从未** `requestPermissions` / 权限健康页未列麦克风 |
| `EtaRecognitionService`（`:recognition` 进程） | 满足数字助理角色资格；若系统调用则转发给外部 ASR | **浮窗不调用** |
| `SystemSpeechRecognizer` | 解析系统默认 / 厂商 `RecognitionService`，否则尝试 on-device | **现成可复用的 ASR 工厂**，UI 未接线 |
| `EtaVoiceInteraction*` | 系统助理会话，打开浮窗 | 是「唤醒入口」，不是热词检测 |
| `HotwordSelfHealHooks` | 息屏后恢复 **Google Hey Google** | **与自定义「xx」无关**，勿混用 |
| 字符串 `voice_listening` / `voice_tap_to_speak` 等 | 中英繁已本地化 | UI 文案已备好，逻辑未用 |

官方技术说明原文要点（`docs/TECHNICAL.md`）：

> 当前浮窗不请求麦克风权限……当前不执行语音识别或语音朗读。

### 1.4 栈约束（方案必须遵守）

1. **minSdk 34**：可用较新的 on-device `SpeechRecognizer`、前台服务类型限制更严。
2. **LLM 默认在线**：没有内置本地大模型；ASR 可离线，对话仍依赖用户配置的 API（或局域网 OpenAI-compatible）。
3. **多进程**：`:voice` / `:voice_session` / `:recognition` **不预热** DB/Skills/完整 Runtime；完整 Runtime 在主进程。麦克风常驻服务若放轻量进程，唤醒后必须走已有 `requestSession()` / Overlay / Messenger，把文本交回主进程。
4. **已有 FGS**：`AgentExecutionService` 是 `specialUse`，**不是** `microphone` 类型；常听热词需新增 `FOREGROUND_SERVICE_MICROPHONE`。
5. **中文**：UI 与系统提示默认中文友好；内置多家国内兼容端点；**没有** ASR 语言配置。
6. **隐私口径**：对话、截图会发往用户配置的模型服务；新增 ASR 若走云端，需单独说明与开关。
7. **商店 / 厂商杀后台**：常驻听麦在国产 ROM 上存活成本高；LSPosed 用户群体可接受更多系统级能力，但仍应默认关闭常听。

---

## 2. 两项能力：分开看，再合在一起

### 2.1 能力 A — 语音输入（ASR → 文本进现有聊天）

**目标：** 用户按住或点击麦克风说话，识别结果填入输入框或直接发送，走现有 Agent 管线。

**交互形态（建议产品二选一或并存）：**

| 模式 | 体验 | 实现难度 | 适合 |
| --- | --- | --- | --- |
| 点击说话（PTT / tap-to-talk） | 点一下开始，点一下结束或静音超时结束 | 低 | MVP |
| 按住说话 | 按住录音，松开发送 | 低–中 | 聊天栏直觉 |
| 连续听写（dictation） | 说话过程中流式出字 | 中 | 长指令；依赖引擎 partial 结果 |

**与现有代码的接法：**

1. **聊天页**：在 `AgentChatInputBar` 增加麦克风；最终文本调用已有 `onSubmit(text)` → `SubmitMessage` → `sendCurrentMessage`。不必改 Runtime 协议。
2. **助理浮窗**：在 `EtaVoicePanel` 用已有文案（「点击说话」「正在聆听…」）；识别完成调用 `EtaAssistantOverlayService.submitPrompt(transcript)`（或先写入输入框再确认）。
3. **ASR 引擎封装**：`agent/voice/asr/EtaAsrSessionFactory.kt` 仅创建豆包听写会话；统一回调 `onPartial` / `onFinal` / `onError`，失败后结束会话。
4. **权限**：首次使用前运行时申请 `RECORD_AUDIO`；纳入 Permission Health 列表（当前未列麦克风）。

**不包含（本能力可后置）：** TTS 播报回复、打断说话、多轮自动听。

### 2.2 能力 B — 自定义语音提示词 / 唤醒词（如「xx」）

**目标：** 设备在特定条件下持续听环境音，检测到约定短语后**唤起 Eta 助理**（浮窗或会话），再进入听写或等待用户说话。

**重要澄清：**

| 已有「唤醒」 | 是不是自定义热词？ |
| --- | --- |
| 设为默认数字助理 + 电源键 / 手势 | ❌ 系统入口，不是「xx」 |
| 小布 / 小爱入口接管 | ❌ 厂商热词 + Eta 接棒 |
| Hey Google 自愈 | ❌ 只修 Google，不服务 Eta 品牌词 |
| **「xx」常听** | ✅ 本方案新增 |

**唤醒后做什么（建议默认）：**

```
检测到唤醒词
  →（可选）短震动 / 提示音
  → EtaVoiceInteractionService.requestSession()
     或直接 EtaAssistantOverlayService.show(...)
  → 浮窗出现后自动进入「听写」一小段时间（能力 A）
  → 超时无语音则保持浮窗键盘态
```

`requestSession()` 已存在：仅当 VIS 进程内 `activeService != null` 时成功；失败时应 fallback 到直接 `show` 浮窗或 `ACTION_ASSIST` 桥（与电源键唤起逻辑对齐）。

### 2.3 合在一起的产品故事

```
[常听热词「xx」] ──detect──► [打开浮窗] ──auto listen──► [ASR 文本]
                                                              │
[聊天栏麦克风] ────────────────────────────► [ASR 文本] ──────┤
                                                              ▼
                                                    现有 Runtime / 云端模型
```

两层应**解耦**：

- **WakeWordEngine**：只输出「触发了哪个词」
- **AsrEngine**：只输出文本  
共享同一套麦克风策略（互斥：唤醒听麦时不占满；一旦进入 ASR，可暂停 KWS 或提高阈值）。

---

## 3. Android 方案对比（端侧 vs 云端）

评分说明：★ 越多越利于 Eta 当前产品（中文、自定义词、BYOK 隐私、APK、商店政策）。「自定义词」特指用户可改成任意「xx」类短语。

### 3.1 语音识别（ASR）方案

| 方案 | 延迟 | 隐私 | 中文准确率 | 离线 | APK 增量 | 费用 | 商店/政策 | 对本项目 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **Android `SpeechRecognizer`（系统/厂商）** | 低–中 | 取决于厂商实现（常上传） | 国产机通常好 | 部分机型 on-device | ≈0 | 免费 | 友好 | 已调研；不用于本方案听写 |
| **On-device SpeechRecognizer** | 低 | 较好 | 机型差异大；部分海外 ROM 中文弱 | 是 | ≈0 | 免费 | 友好 | 已调研；不用于本方案听写 |
| **ML Kit Speech** | — | — | — | — | — | — | — | **不推荐作为主力**（能力/语言覆盖不如系统 ASR + 云厂商） |
| **Vosk** | 低 | 优（本地） | 中文模型可用，大指令弱于云 | 是 | 中–大（模型数十 MB+） | 免费 | 友好 | 可选「纯离线听写」档 |
| **Sherpa-ONNX ASR** | 低 | 优 | 中文流式模型成熟 | 是 | 中–大 | 免费 | 友好 | 与 KWS 同栈时可复用；工程量中 |
| **Whisper / faster-whisper 侧模型** | 中–高（端侧） | 优 | 多语好，移动端实时吃力 | 是 | **很大** | 免费 | 友好 | 不适合常听；可做「录音后转写」实验 |
| **讯飞 / 百度 / 阿里云 ASR SDK** | 低 | 云端 | **中文强** | 否（或厂商离线包另购） | 小–中 | 按量/套餐 | 需合规隐私政策 | 国内准确率兜底；与 BYOK 哲学一致（用户钥）时可做成可选 Provider |
| **自建 faster-whisper / FunASR 服务** | 视网络 | 可控 | 可调优 | 服务端 | App 侧≈0 | 自运维 | 友好 | 适合已有服务器的用户；App 只推音频 |

### 3.2 唤醒词（Wake / KWS）方案

| 方案 | 自定义短语 | 中文 | 延迟 | 隐私 | 电量 | APK | 费用 | 风险 | 对本项目 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **Porcupine / Picovoice** | Console 训练 `.ppn`，支持中文模型 | 支持 | 极低 | 本地推理；需 AccessKey | 优 | 小（~1MB 级运行时 + 词文件） | 免费档有限额 / 商用授权 | 依赖第三方账号与授权；敏感词可用性 | **工程成熟首选之一** |
| **Sherpa-ONNX KWS** | **开放词表**，改 keywords 即可（拼音@汉字） | **强（Wenetspeech 等）** | 低 | 优 | 良（模型 ~3M 级仍需常听） | 中（native + onnx） | 免费（Apache + 模型许可自查） | 误唤醒需调阈值；集成 JNI | **中文自定义「xx」强烈推荐评估** |
| **openWakeWord** | 可训练自定义 | 官方偏英文 | 低 | 优 | 良 | 中 | 免费 | 中文需自训数据 | 非首选 |
| **Snowboy 等旧方案** | 有 | 一般 | 低 | 优 | 良 | 小 | — | 项目停滞 | **不推荐** |
| **系统 Assistant / Hotword API** | 基本不可自定义 Eta 品牌词 | — | — | — | — | 0 | — | 权限与 OEM 封闭 | 只能继续用现有 VIS/电源键 |
| **厂商热词（小爱/小布）** | 厂商词 | 强 | 极低 | 厂商云 | 优（硬件通路） | 0 | — | 需 LSPosed；非自有品牌 | 已有接管路径，**不能替代「xx」** |
| **云端流式 ASR 当「伪唤醒」** | 任意 | 强 | 高、贵 | 差 | 差 | 小 | 高 | 商店对常传麦极敏感 | **拒绝作为默认常听** |

### 3.3 维度小结（给决策用）

- **听写选型已确定**：豆包双向流式 WebSocket。
- **要「xx」可改、中文好、可离线、无 AccessKey**：Sherpa-ONNX KWS。  
- **要最少踩坑、体积最小、接受商业授权**：Porcupine。  
- **其他云端 ASR**：仅作为调研对比，不接入本方案听写。
- **不要**把 Whisper 端侧当常听；**不要**用 Google Hotword Hook 冒充 Eta 唤醒。

---

## 4. 推荐架构（针对本仓库）

### 4.1 推荐组合（按已拍板调整）

| 层级 | 目标行为 | 选型 |
| --- | --- | --- |
| 常听起点 | **App 打开并取得麦权后**启动唤醒监听；通知栏可停；设置可关 | `EtaWakeWordService` + `FOREGROUND_SERVICE_MICROPHONE` |
| 自定义热词 | 本地 KWS；**默认词 + 设置可改** | **Sherpa-ONNX KWS（主，改 keywords 即可）** / Porcupine（备，改词需重新导出 `.ppn`） |
| ASR | **豆包双向流式 WebSocket BYOK**；仅听写窗上传 | `DoubaoBidirectionalAsrEngine`（官方 SAUC 协议） |
| 听写失败处理 | 无 Key 提示配置；连接/识别失败提示错误并结束 | 仅使用豆包，不切换识别服务 |
| 系统助理入口 | 电源键 / VIS **保留**，与常听热词并行 | 现有 VIS / Hook 不动 |
| TTS | 未拍板，本方案仍不排期 | — |

**「一直听」语义（本方案采用）：** 一直听的是**本地唤醒词**，不是一直把环境音送云端。云端 BYOK 只在「检测到唤醒 → 听写窗口」内传音频/特征。若产品本意是「全程云端流式听写」，电量/费用/隐私成本高一个数量级，需另开决策（见 §6 残留问题）。

### 4.2 组件划分

```
io.github.mangi.eta.agent.voice/
  asr/
    EtaAsrEngine
    DoubaoBidirectionalAsrEngine  // 主：官方双向流式 WebSocket（SAUC）
  wake/
    WakeWordEngine
    SherpaWakeEngine
  EtaMicSessionCoordinator
  EtaWakeWordService
data/ + ui/settings/
  DoubaoSpeechCredentials     // APP ID+Access Token 或 Api-Key；Resource-Id；加密存
  WakePhrasePreferences       // default=小王同学 + userOverride
```

### 4.2.1 豆包双向流式 ASR（已指定文档）

**权威文档（实现必须对照）：**  
[双向流式语音识别 WebSocket（DoubaoVoice）](https://docs.volcengine.com/docs/DoubaoVoice/bidirectional-streaming-automatic-speech-recognition-websocket?lang=zh)  
（同系内容亦见于火山文档 `6561/1354869`。）

产品侧：设置页「豆包语音」——用户自备控制台凭证，Eta **不内置官方 Key**。

| 端点 | URL | 选用 |
| --- | --- | --- |
| 双向流式 | `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel` | 可用 |
| **双向流式优化版（推荐）** | `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async` | **默认主路径**（结果变化才回包，RTF/首尾字更好） |
| 流式输入 / 非流式结果 | `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream` | 可选「整句更准」模式，不作默认 |

**建连请求头（示例，以文档为准）：**

- `X-Api-App-Key` + `X-Api-Access-Key`（旧）或 `X-Api-Key`（新）  
- `X-Api-Resource-Id`：如 `volc.seedasr.sauc.duration` / `.concurrent`（2.0）或 `volc.bigasr.sauc.*`（1.0）——设置里让用户选/填  
- `X-Api-Connect-Id`：每次连接随机 UUID  

**音频与帧协议（实现要点）：**

- 16 kHz / 16-bit / mono PCM；建议约 **200 ms** 一包  
- 官方**自定义二进制帧**（header + payload size + 可选 gzip JSON/音频），**按官方 Demo 解析，勿半套手搓**  
- 首包：配置 + request；随后音频包；结束发 final/负包  
- 可选：`enable_nonstream` 二遍识别（仅优化版）+ VAD 分句，`definite: true` 作为「一句结束可提交」信号  

**与本 App 的衔接：** OkHttp WebSocket 已可用；`DoubaoBidirectionalAsrEngine` 输出 `onPartial` / `onFinal`（或 `definite`）→ 浮窗上屏 → 静音/用户点停后 `submitPrompt`。

**明确不作为主路径：** 录音文件识别 / Flash REST；仅可在调试或极端弱网时作实验性兜底，不进默认分期。

与 LLM BYOK：豆包语音凭证 **独立**于方舟/DeepSeek 等聊天模型 Key。

### 4.2.2 默认唤醒词 + 用户可改

| 项 | 方案 |
| --- | --- |
| 默认词 | 出厂固定 **「小王同学」**（四字，误触发相对可控）；常量如 `DEFAULT_WAKE_PHRASE` |
| 用户修改 | 设置 →「唤醒词」文本框；保存后校验长度（建议 ≥2 汉字或 ≥2 音节）、写 Prefs、调用 `WakeWordEngine.updateKeywords(...)` |
| 引擎侧 | **优先 Sherpa-ONNX**：开放词表，改拼音/@汉字即可（「小王同学」→ 如 `x iǎo w áng t óng x ué @小王同学`），无需重新训练；Porcupine 每次改词要 Console 出 `.ppn`，与「用户随意设置」摩擦大，故作备选 |
| 空/非法 | 拒绝保存并提示原因，保留已生效的唤醒词；不静默替换为默认词 |
| 「恢复默认」 | 一键写回「小王同学」 |
| 多语言 | 首发中文默认；英文自定义词视 KWS 模型能力（Sherpa 有中英模型可选） |

### 4.3 数据流（拍板后主路径）

**主路径：App 常听 → 唤醒 → 云 ASR → Runtime**

```
MainActivity / EtaApp 进入前台（或用户开启「语音唤醒」）
  → 申请 RECORD_AUDIO + 通知权限
  → startForegroundService(EtaWakeWordService)
  → AudioRecord → 本地 WakeWordEngine（音频不出设备）
  → onDetect("小王同学" 或用户词)
  → 暂停 KWS → Overlay.show / requestSession()
  → DoubaoBidirectionalAsrEngine：
       OkHttp WebSocket → wss://.../sauc/bigmodel_async
       AudioRecord PCM 分包上行 → partial 上屏 → definite/final 文本
  → submitPrompt / sendCurrentMessage
  → 既有 Agent Runtime（模型仍是原有 LLM BYOK）
  → 听写结束 → 恢复 KWS（App 仍视为「打开后常听」且开关开着）
```

**辅路径：聊天栏 / 浮窗手动麦**（不经唤醒词，直接开同一套双向流式 ASR）。

**失败处理：** 未配置豆包凭证 → 引导去设置；连接或识别失败 → 展示错误并结束本次听写。所有听写入口仅使用豆包。

### 4.4 代码钩子（实现时直接改这些文件）

| 优先级 | 文件 | 改动意图 |
| --- | --- | --- |
| P0 | 新建 `agent/voice/EtaWakeWordService.kt` | App 打开后常听 FGS |
| P0 | 新建 `agent/voice/asr/DoubaoBidirectionalAsrEngine.kt` + 凭证 DataStore | 官方双向流式 WebSocket 听写 |
| P0 | `ui/MainActivity.kt` / `EtaApp` 生命周期 | 进入可用态后拉起/停用常听 |
| P0 | `AndroidManifest.xml` | `FOREGROUND_SERVICE_MICROPHONE` + FGS 声明 |
| P0 | Prefs / 设置页 | 豆包语音凭证；**默认唤醒词展示 + 用户编辑**；常听总开关 |
| P0 | `ui/app/AgentAppState.kt` + Permission Health | 麦克风 +「语音唤醒运行中」状态 |
| P0 | `agent/voice/EtaAssistantOverlayService.kt` / `EtaVoicePanel.kt` | 唤醒后自动听写 → `submitPrompt` |
| P1 | `ui/components/AgentChatInputBar.kt` | 手动麦克风（直连豆包 ASR） |
| — | `hook/system/HotwordSelfHealHooks.kt` | **不要**接业务 |

### 4.5 权限与前台服务（常听为默认目标）

| 需求 | Manifest / 运行时 |
| --- | --- |
| App 打开后常听 | **必须** `FOREGROUND_SERVICE_MICROPHONE` + 持续通知「Eta 正在等待唤醒词（当前：xxx）」；通知 Action：停止 |
| 听写上传 | `INTERNET`（已有）+ 用户豆包凭证；**仅听写窗**上传音频，唤醒阶段音频不出设备、不落盘（默认） |
| 首次引导 | 打开 App → 说明常听仅本地检词 + 说话内容经豆包语音识别（用户自己的账号）→ 申请麦权 → 引导填写豆包凭证 |
| Android 14+ | 引导「允许麦克风」「电池不受限/自启动」（国产 ROM）；进程被杀后需用户再次打开 App 恢复常听（除非另做保活，本方案不默认强保活） |
| 音频焦点 | 通话/其他录音时暂停 KWS |

### 4.6 误唤醒（False Wake）缓解

1. **默认词「小王同学」已够长**；用户改成极短词时弹出「可能容易误触发」确认。  
2. **灵敏度可调**：设置页低/中/高。  
3. **场景门控（在「App 打开后常听」之内）**：App 进程被系统杀掉则停；默认 **FGS 下后台继续**；息屏是否继续听仍待确认（§6）。  
4. **冷却**：触发后 N 秒忽略重复检测。  
5. **互斥**：通话中、GUI Agent 操作、豆包 ASR 进行中暂停 KWS。  
6. **可观测**：Debug 只计误触发次数，不记音频。  
7. **一键关闭**：通知栏 + 设置总开关；「恢复默认唤醒词」按钮。

### 4.7 进程与 Runtime / BYOK 注意

- 唤醒在轻量服务；**豆包 ASR 与 Runtime 在主进程**（复用 OkHttp / DataStore）。  
- 豆包凭证加密存储，**禁止**写入 logcat；对齐现有 API Key 处理。  
- 听写文本进会话后，LLM 仍走用户已有模型 BYOK，与豆包语音 Key 独立。  
- 现有日志红线：不记录音频与 Prompt 正文。

---

## 5. 分阶段实施计划（仍为方案，不写代码）

### 已拍板（阶段 0 部分关闭）

| 决策项 | 结论 |
| --- | --- |
| 常听 | **App 打开后一直听**（本地唤醒词；FGS 保活至用户关掉或进程被杀） |
| ASR | **豆包双向流式 WebSocket BYOK**（[文档](https://docs.volcengine.com/docs/DoubaoVoice/bidirectional-streaming-automatic-speech-recognition-websocket?lang=zh)） |
| 唤醒词 | 默认 **「小王同学」**，**用户可在设置中修改** |
| 流式端点默认 | **`bigmodel_async`（优化版）** |

残留开放问题见 §6。

### 阶段 1 — MVP：豆包双向流式听写闭环（可先不常听）

**目的：** 先打通「麦 → 双向流式 WebSocket → partial/final 文本 → Runtime」。

**范围：**

- 设置页：豆包凭证（App-Key/Access-Key 或 Api-Key）+ Resource-Id + 连通测试  
- `DoubaoBidirectionalAsrEngine`：**直接按官方 WebSocket 协议实现**（默认 `bigmodel_async`）  
- 浮窗/聊天栏手动麦克风 + partial 上屏  
- 权限健康、错误态（无凭证 / 鉴权失败 / 网络失败；提示错误并结束听写）
- 最终文本进 `submitPrompt` / `sendCurrentMessage`

| 项 | 评估 |
| --- | --- |
| 工程量 | **中–偏大**（自定义二进制帧 + OkHttp WebSocket + VAD/definite 语义） |
| 风险 | 帧协议实现易踩坑；控制台新旧鉴权；Resource-Id 与计费 SKU 选错 |
| 验收 | 填入凭证后说话可见流式字幕，最终句可进 Agent |

### 阶段 2 — App 打开后常听 + 「小王同学」/可改唤醒词

**范围：**

- Sherpa-ONNX KWS + 出厂「小王同学」+ 设置编辑热更新  
- `EtaWakeWordService`；App 可用后自动常听  
- 唤醒 → 自动打开双向流式听写 → 恢复常听  
- 通知展示当前唤醒词 + 停止 Action

| 项 | 评估 |
| --- | --- |
| 工程量 | **中–大** |
| 风险 | 电量、杀进程、用户改短词误唤醒、APK 体积 |
| 验收 | 说「小王同学」可唤起；改词后新词生效；关开关后停止录音 |

### 阶段 3 — 打磨

- `enable_nonstream` 二遍识别、分句策略、弱网重连  
- 息屏策略、厂商自启动说明  
- 可选豆包 TTS；多唤醒词  

### 阶段关系（更新）

```
阶段1 豆包双向流式 WebSocket 听写  ──必须──►  文本进 Runtime
        │
        └──► 阶段2 App 打开后常听 +「小王同学」/可改热词
                │
                └──► 阶段3 二遍识别 / TTS / 息屏策略
```

---

## 6. 决策台账与仍需 Chao 确认的点

### 6.1 已确认

1. **常听：** App 打开后一直听（本地 KWS，非全程云听写）。  
2. **ASR：** 豆包语音 **双向流式 WebSocket BYOK**（指定官方文档）。  
3. **流式端点默认：** `bigmodel_async`。  
4. **唤醒词：** 默认 **「小王同学」**，用户可设置/修改。
5. **听写仅使用豆包（2026-09-22）：** 无凭证或失败时提示错误，不切换系统识别。

### 6.2 仍需拍板

1. **「一直听」边界**：息屏是否继续？进程被杀后是否接受「再开 App 才恢复」？  
2. **唤醒引擎（已实现）：** 内置 Sherpa-ONNX 1.13.8 与中英文 KWS 模型；不再依赖系统识别回退。
3. **Resource-Id 默认 SKU**（`seedasr` 小时版 vs 并发版；1.0 vs 2.0）。  
4. **TTS**（豆包 TTS 是否同迭代）。  
5. **发布渠道**（GitHub APK vs 商店）。

---

## 7. 附录

### 7.1 关键文件索引

```
/workspace/app/src/main/AndroidManifest.xml
/workspace/docs/TECHNICAL.md
/workspace/app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentChatInputBar.kt
/workspace/app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppState.kt
/workspace/app/src/main/kotlin/io/github/mangi/eta/agent/voice/EtaAssistantOverlayService.kt
/workspace/app/src/main/kotlin/io/github/mangi/eta/agent/voice/EtaVoicePanel.kt
/workspace/app/src/main/kotlin/io/github/mangi/eta/agent/voice/SystemSpeechRecognizer.kt
/workspace/app/src/main/kotlin/io/github/mangi/eta/agent/voice/EtaRecognitionService.kt
/workspace/app/src/main/kotlin/io/github/mangi/eta/agent/voice/EtaVoiceInteractionService.kt
/workspace/app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeClient.kt
/workspace/app/src/main/kotlin/io/github/mangi/eta/hook/system/HotwordSelfHealHooks.kt
```

架构探索底稿（内部）：  
`/cursor/stores/bc-f2e75e0d-81a2-4d30-9ec1-25a92a3ea44e/internal/voice-architecture-exploration.md`

### 7.2 能力对照（现状 → 目标）

| | 现状 | 阶段1（双向流式听写） | 阶段2（拍板目标） |
| --- | --- | --- | --- |
| 聊天/浮窗语音输入 | 无 | 手动麦 → 豆包 WebSocket 流式 | 同左 + 唤醒后自动听 |
| App 打开后常听 | 无 | 无 | **有（本地 KWS + FGS）** |
| 唤醒词 | 无 | 无 | **默认「小王同学」+ 用户可改** |
| ASR | 无 | **双向流式 WebSocket BYOK** | 同左；失败提示错误并结束听写 |
| 系统助理入口 | 有 | 保持 | 保持 |
| TTS | 无 | 无 | 可选（未拍板） |

---

*本文仅方案调研，不包含实现与依赖引入。ASR 已锁定为豆包[双向流式语音识别 WebSocket](https://docs.volcengine.com/docs/DoubaoVoice/bidirectional-streaming-automatic-speech-recognition-websocket?lang=zh)。*
