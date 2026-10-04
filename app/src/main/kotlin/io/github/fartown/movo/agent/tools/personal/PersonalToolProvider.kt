package io.github.fartown.movo.agent.tools.personal

import android.content.Context
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.device.RootAccess
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.PromptSection
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolProvider

/**
 * 个人数据领域（domain=PERSONAL）的工具集合：
 * personal_search、sms_code_read、usage_read、health_read、wifi_password_read。
 *
 * 真实后端在此装配：Root content query / ColorOS Provider / 系统服务。Root 执行器由调用方
 * （ToolServices）提供，关闭由其统一负责。各工具按环境（Root / ColorOS / 通知权 / 使用情况权）
 * 用 availability(env) 在目录层自隐藏。
 */
internal class PersonalToolProvider(
    context: Context,
    rootExecutor: BoundedRootCommandExecutor,
    rootAvailable: () -> Boolean = { RootAccess.isGranted },
) : ToolProvider {

    override val tools: List<AgentTool> = listOf(
        ContractTool(PersonalSearchTool(AndroidPersonalSearchBackend(context, rootExecutor, rootAvailable))),
        ContractTool(SmsCodeReadTool(AndroidSmsCodeBackend(rootExecutor, rootAvailable))),
        ContractTool(UsageReadTool(AndroidUsageReadBackend(context))),
        ContractTool(HealthReadTool(AndroidHealthReadBackend(context, rootExecutor, rootAvailable))),
        ContractTool(WifiPasswordReadTool(AndroidWifiPasswordReadBackend(rootExecutor))),
    )

    override val promptSection = PromptSection(
        id = "personal",
        domain = ToolDomain.PERSONAL,
        text = "涉及用户自己的数据先查本机来源：个人记录用 personal_search（source 必选）；" +
            "只取验证码用 sms_code_read；应用使用情况用 usage_read；健康汇总用 health_read；" +
            "Wi‑Fi 密码用 wifi_password_read。只取任务需要的字段，不把个人数据发到外部网页或第三方。",
    )
}
