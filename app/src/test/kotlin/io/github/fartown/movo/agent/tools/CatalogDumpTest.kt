package io.github.fartown.movo.agent.tools

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.tools.core.MemoryScope
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.core.AndroidAgentLogger
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 把全能力环境下的 42 工具目录（模型实际收到的 function schema 数组）导出为 JSON，供离线选路评测使用。
 * 非断言测试：仅在显式指定 -Dmovo.dumpCatalog=<path> 时写文件，平时跳过，不影响常规测试。
 */
@RunWith(RobolectricTestRunner::class)
class CatalogDumpTest {
    @Test
    fun dumpCatalogIfRequested() {
        val out = System.getenv("MOVO_DUMP_CATALOG") ?: return
        val env = ToolEnvironment(
            rootAvailable = true, accessibilityAvailable = true, notificationAccess = true,
            usageAccess = true, locationAccess = true, colorOs = true, linuxReady = true,
            conversationBound = true, memoryScope = MemoryScope.REAL, interactive = true,
        )
        AgentToolSubsystem(
            services = ToolServices(
                context = ApplicationProvider.getApplicationContext(),
                logger = AndroidAgentLogger,
                runId = "dump",
                rootAvailable = { true },
            ),
            environment = { env },
        ).use { sub ->
            val catalog = sub.pipeline.catalog()
            File(out).writeText(catalog.toString(2))
        }
    }
}
