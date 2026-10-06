package io.github.fartown.movo.platform

import android.content.Context
import android.content.Intent
import io.github.fartown.movo.MovoApp
import io.github.fartown.movo.agent.tools.ToolProviderInputs
import io.github.fartown.movo.agent.tools.core.ApprovalMode
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

    /** 需要用户确认的工具调用怎么处理（手机：弹审批卡；电视：免审）。 */
    val approvalMode: ApprovalMode

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

    /** 主进程 Application.onCreate 里，在共享初始化之后依次执行。 */
    val initializers: List<(MovoApp) -> Unit>
}
