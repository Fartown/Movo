package io.github.fartown.movo.platform

import android.content.Context
import android.content.Intent
import io.github.fartown.movo.MovoApp
import io.github.fartown.movo.agent.tools.ToolProviderInputs
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.voice.session.VoiceConversationHost

/**
 * 手机版与电视版的差异全部经这里装配（docs/solutions/tv-voice-app/电视端语音App实施方案.md §5.5）。
 *
 * `src/phone` 与 `src/tv` 各有一个同包同名的 [io.github.fartown.movo.flavor.FlavorModule] 实现本接口，编译时只会有一份；
 * 它是静态对象，各进程都能直接拿到。共享代码不判断“是不是电视”：两边不同的部分经这些接口取实现，
 * 同一逻辑在两边能力不同的部分按设备能力判断。
 */
internal interface Flavor {
    /** 共享代码要打开的界面（主界面、会话界面）。 */
    val surfaces: AppSurfaces

    /** Agent 运行中浮层窗口里的内容；窗口的创建、类型与位置由运行时服务管理。 */
    val runSurface: RunSurfaceRenderer

    /** 系统提示里与设备有关的描述。 */
    val prompt: PromptProfile

    /** 能否弹出提问 / 审批卡；不能时不提供 ask_user，运行时也不发起交互请求。 */
    val interactionCards: Boolean

    /** 无障碍按键过滤收到的按键；null 表示不拦截。 */
    val keyInterceptor: KeyInterceptor?

    /** Optional screenshot route for devices without accessibility screenshot support. */
    val screenCapture: DeviceScreenCapture? get() = null

    /** Start a user execution service, whose onCreate immediately posts its foreground notification. */
    fun startExecutionService(context: Context, intent: Intent) {
        checkNotNull(context.startForegroundService(intent))
    }

    /** 语音会话的宿主：语音轮次写进哪一份会话状态。 */
    fun voiceHost(context: Context): VoiceConversationHost

    /** null uses the SDK microphone and echo cancellation; alternate inputs supply mono PCM16 at 16 kHz. */
    fun voiceInput(context: Context, onError: (String) -> Unit):
        io.github.fartown.movo.agent.voice.conversation.DoubaoDialogEngine.PcmInput? = null

    /** 本设备装配的工具 Provider（白名单）；元工具由 AgentToolSubsystem 统一追加在最后。 */
    fun toolProviders(inputs: ToolProviderInputs): List<ToolProvider>

    /**
     * 语音会话真正空闲（没人说话、没有任务、没在播报）多久后结束。手机 45 秒；电视 10 秒，会话期间节目是暂停的
     * （连续对话规则与调研依据见 docs/solutions/tv-voice-app/电视端语音App实施方案.md）。
     */
    val voiceIdleTimeoutMs: Long get() = 45_000L

    /** 由设备在本地处理、不交给模型的语音口令（例如电视的「看全文」）。 */
    val voiceLocalCommands: Set<String> get() = emptySet()

    /** 这些工具成功后，本轮回答念完就结束语音会话（例如电视打开了其他 App，不再继续听节目声音）。默认不结束。 */
    val voiceHandoffTools: Set<String> get() = emptySet()

    /**
     * 这些工具的调用可以顺带写好回答（参数 reply）：模型判断这一步做完任务就结束时填写，工具成功后直接用它结束本轮，
     * 省掉只为说一句「已打开」的那一轮模型请求。默认不开。
     */
    val finishingTools: Set<String> get() = emptySet()

    /** 距上次语音结束超过这么久再开语音就开新对话；null 表示一直沿用当前对话（手机）。 */
    val voiceNewConversationAfterMs: Long? get() = null

    /** 处理 [voiceLocalCommands] 里的口令，返回状态文案；null 表示没有处理。 */
    fun onVoiceLocalCommand(context: Context, command: String): String? = null

    /**
     * 是否记录完整运行日志（docs/solutions/run-log/运行日志补全实施方案.md）。只有带运行日志页的版本记：
     * 那里能导出、清空、关掉；没有入口的版本不记。
     */
    val fullRunLog: Boolean get() = false

    /** 主进程 Application.onCreate 里，在共享初始化之后依次执行。 */
    val initializers: List<(MovoApp) -> Unit>
}
