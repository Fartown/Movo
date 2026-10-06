package io.github.fartown.movo.agent.tools.device

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.ApprovalDecision
import io.github.fartown.movo.agent.tools.core.ApprovalRequest
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.ToolContract
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.ToolPipeline
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.agent.tools.core.UserAnswer
import io.github.fartown.movo.agent.tools.core.UserInteraction
import io.github.fartown.movo.agent.tools.core.UserQuestion
import io.github.fartown.movo.core.AndroidAgentLogger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 设备/应用领域 7 个工具的合同验证：只读/回读 Done/无法确认 Unknown/错误码/敏感度/确认。 */
@RunWith(RobolectricTestRunner::class)
class DeviceToolContractTest {

    private val approveAll = object : UserInteraction {
        override val available = true
        override fun ask(question: UserQuestion, timeoutMs: Long) = UserAnswer.Declined
        override fun approve(request: ApprovalRequest, timeoutMs: Long) = ApprovalDecision.Approved
    }

    private fun <I : ToolInput, O : ToolOutput> pipeline(
        tool: ToolContract<I, O>,
        env: ToolEnvironment,
        interaction: UserInteraction = UserInteraction.NONE,
    ): ToolPipeline {
        val provider = object : ToolProvider {
            override val tools = listOf(ContractTool(tool))
        }
        return ToolPipeline(
            registry = ToolRegistry(listOf(provider)),
            environment = { env },
            appContext = ApplicationProvider.getApplicationContext(),
            logger = AndroidAgentLogger,
            runId = "run1",
            cancelled = { false },
            interaction = interaction,
        ).also { it.catalog() }
    }

    private fun ToolPipeline.run(name: String, args: String): JSONObject {
        val result = execute(AgentModelClient.ToolCall("c1", name, args))
        return JSONObject(result.content).also { it.put("__sensitive", result.sensitive) }
    }

    // ---- device_toggle ----

    @Test
    fun deviceToggle_flashlight_readbackOk_isDone() {
        val backend = object : DeviceToggleBackend {
            override fun setFlashlight(enabled: Boolean) = ToggleDispatch.OK
            override fun setRadio(target: ToggleTarget, enabled: Boolean) = ToggleDispatch.OK
            override fun readState(target: ToggleTarget) = true
        }
        val json = pipeline(DeviceToggleTool(backend), ToolEnvironment())
            .run("device_toggle", """{"target":"flashlight","enabled":true}""")
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
        assertTrue(json.getJSONObject("data").getBoolean("enabled"))
    }

    @Test
    fun deviceToggle_wifi_noRoot_isRootRequired() {
        val backend = object : DeviceToggleBackend {
            override fun setFlashlight(enabled: Boolean) = ToggleDispatch.OK
            override fun setRadio(target: ToggleTarget, enabled: Boolean) = ToggleDispatch.OK
            override fun readState(target: ToggleTarget) = true
        }
        val json = pipeline(DeviceToggleTool(backend), ToolEnvironment(rootAvailable = false))
            .run("device_toggle", """{"target":"wifi","enabled":true}""")
        assertEquals("error", json.getString("status"))
        assertEquals("ROOT_REQUIRED", json.getString("code"))
    }

    @Test
    fun deviceToggle_readbackMismatch_isUnknown() {
        val backend = object : DeviceToggleBackend {
            override fun setFlashlight(enabled: Boolean) = ToggleDispatch.OK
            override fun setRadio(target: ToggleTarget, enabled: Boolean) = ToggleDispatch.OK
            override fun readState(target: ToggleTarget) = false // 请求 true 但始终回读 false
        }
        val json = pipeline(DeviceToggleTool(backend), ToolEnvironment())
            .run("device_toggle", """{"target":"flashlight","enabled":true}""")
        assertEquals("unknown", json.getString("status"))
        assertEquals("OUTCOME_UNKNOWN", json.getString("code"))
    }

    // ---- setting_read ----

    @Test
    fun settingRead_isReadOnlyPrivate_noEffectVerified() {
        val backend = object : SettingReadBackend {
            override fun read(namespace: SettingNamespace, key: String) =
                if (key == "screen_brightness") "120" else null
        }
        val json = pipeline(SettingReadTool(backend), ToolEnvironment())
            .run("setting_read", """{"namespace":"system","keys":["screen_brightness","missing"]}""")
        assertEquals("ok", json.getString("status"))
        assertFalse(json.has("effect_verified"))
        assertTrue(json.getBoolean("__sensitive"))
        val values = json.getJSONObject("data").getJSONObject("values")
        assertEquals("120", values.getString("screen_brightness"))
        assertTrue(values.isNull("missing"))
    }

    // ---- setting_write ----

    @Test
    fun settingWrite_blacklisted_isPolicyDenied() {
        val backend = object : SettingWriteBackend {
            override fun write(namespace: SettingNamespace, key: String, value: String) = ToggleDispatch.OK
            override fun read(namespace: SettingNamespace, key: String): String? = null
        }
        val json = pipeline(SettingWriteTool(backend), ToolEnvironment(rootAvailable = true), approveAll)
            .run("setting_write", """{"namespace":"secure","key":"adb_enabled","value":"1"}""")
        assertEquals("error", json.getString("status"))
        assertEquals("POLICY_DENIED", json.getString("code"))
        assertEquals("policy_denied", json.getString("detail"))
    }

    @Test
    fun settingWrite_readbackOk_isDone() {
        val backend = object : SettingWriteBackend {
            private var stored: String? = "50"
            override fun write(namespace: SettingNamespace, key: String, value: String): ToggleDispatch {
                stored = value
                return ToggleDispatch.OK
            }
            override fun read(namespace: SettingNamespace, key: String): String? = stored
        }
        val json = pipeline(SettingWriteTool(backend), ToolEnvironment(rootAvailable = true), approveAll)
            .run("setting_write", """{"namespace":"system","key":"screen_brightness","value":"100"}""")
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
        assertEquals("100", json.getJSONObject("data").getString("value"))
    }

    // ---- device_diagnostics ----

    @Test
    fun deviceDiagnostics_noRoot_isRootRequired() {
        val backend = object : DeviceDiagnosticsBackend {
            override fun topProcesses(limit: Int) = DiagnosticsResult.Ok(JSONObject(), false)
            override fun appStorage(limit: Int) = DiagnosticsResult.Ok(JSONObject(), false)
            override fun logcat(maxLines: Int, level: String?, packageName: String?, query: String?) =
                DiagnosticsResult.Ok(JSONObject(), false)
        }
        val json = pipeline(DeviceDiagnosticsTool(backend), ToolEnvironment(rootAvailable = false))
            .run("device_diagnostics", """{"kind":"top_processes"}""")
        assertEquals("error", json.getString("status"))
        assertEquals("ROOT_REQUIRED", json.getString("code"))
    }

    @Test
    fun deviceDiagnostics_logcat_isReadPrivate() {
        val backend = object : DeviceDiagnosticsBackend {
            override fun topProcesses(limit: Int) = DiagnosticsResult.Ok(JSONObject(), false)
            override fun appStorage(limit: Int) = DiagnosticsResult.Ok(JSONObject(), false)
            override fun logcat(maxLines: Int, level: String?, packageName: String?, query: String?) =
                DiagnosticsResult.Ok(
                    JSONObject().put("lines", org.json.JSONArray(listOf("line a", "line b"))).put("count", 2),
                    false,
                )
        }
        val json = pipeline(DeviceDiagnosticsTool(backend), ToolEnvironment(rootAvailable = true))
            .run("device_diagnostics", """{"kind":"logcat","max_lines":200}""")
        assertEquals("ok", json.getString("status"))
        assertFalse(json.has("effect_verified"))
        assertTrue(json.getBoolean("__sensitive"))
    }

    // ---- app_search ----

    @Test
    fun appSearch_isReadWithApps() {
        val backend = object : AppSearchBackend {
            override fun search(query: String, limit: Int) =
                listOf(AppMatch("相机", "com.android.camera", isSystem = true))
        }
        val json = pipeline(AppSearchTool(backend), ToolEnvironment())
            .run("app_search", """{"query":"相机"}""")
        assertEquals("ok", json.getString("status"))
        assertFalse(json.has("effect_verified"))
        val apps = json.getJSONObject("data").getJSONArray("apps")
        assertEquals("com.android.camera", apps.getJSONObject(0).getString("package"))
        assertTrue(apps.getJSONObject(0).getBoolean("is_system"))
    }

    // ---- app_open ----

    @Test
    fun appOpen_foregroundMatched_isDone() {
        val backend = fakeOpenBackend(foreground = ForegroundOutcome("com.example.app", matched = true))
        val json = pipeline(AppOpenTool(backend), ToolEnvironment())
            .run("app_open", """{"package":"com.example.app"}""")
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
        assertTrue(json.getJSONObject("data").getBoolean("foreground"))
    }

    @Test
    fun appOpen_notConfirmed_isUnknown() {
        val backend = fakeOpenBackend(foreground = ForegroundOutcome("com.launcher", matched = false))
        val json = pipeline(AppOpenTool(backend), ToolEnvironment())
            .run("app_open", """{"package":"com.example.app","wait_ms":1000}""")
        assertEquals("unknown", json.getString("status"))
        assertEquals("OUTCOME_UNKNOWN", json.getString("code"))
    }

    @Test
    fun appOpen_ambiguousName_isAmbiguous() {
        val backend = fakeOpenBackend(
            matches = listOf(AppMatch("微信", "com.tencent.mm", false), AppMatch("微信读书", "com.tencent.weread", false)),
        )
        val json = pipeline(AppOpenTool(backend), ToolEnvironment())
            .run("app_open", """{"name":"微"}""")
        assertEquals("error", json.getString("status"))
        assertEquals("AMBIGUOUS", json.getString("code"))
    }

    @Test
    fun appOpen_uriWithoutScheme_isInvalidArguments() {
        val backend = fakeOpenBackend()
        // 用 approveAll：解析失败时中央 fail-closed 会保守要求确认，批准后才暴露 INVALID_ARGUMENTS。
        val json = pipeline(AppOpenTool(backend), ToolEnvironment(), approveAll)
            .run("app_open", """{"uri":"example.com"}""")
        assertEquals("error", json.getString("status"))
        assertEquals("INVALID_ARGUMENTS", json.getString("code"))
    }

    // ---- app_control ----

    @Test
    fun appControl_selfProtected_isPolicyDenied() {
        val backend = fakeControlBackend()
        val json = pipeline(AppControlTool(backend), ToolEnvironment(rootAvailable = true), approveAll)
            .run("app_control", """{"package":"com.android.systemui","action":"force_stop"}""")
        assertEquals("error", json.getString("status"))
        assertEquals("POLICY_DENIED", json.getString("code"))
        assertEquals("policy_denied", json.getString("detail"))
    }

    @Test
    fun appControl_forceStop_readbackOk_isDone() {
        val backend = fakeControlBackend(stopped = true)
        val json = pipeline(AppControlTool(backend), ToolEnvironment(rootAvailable = true), approveAll)
            .run("app_control", """{"package":"com.example.app","action":"force_stop"}""")
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
        assertEquals("stopped", json.getJSONObject("data").getString("state"))
    }

    @Test
    fun appControl_forceStop_noReadback_isUnknown() {
        val backend = fakeControlBackend(stopped = null)
        val json = pipeline(AppControlTool(backend), ToolEnvironment(rootAvailable = true), approveAll)
            .run("app_control", """{"package":"com.example.app","action":"force_stop"}""")
        assertEquals("unknown", json.getString("status"))
        assertEquals("OUTCOME_UNKNOWN", json.getString("code"))
    }

    // ---- fakes ----

    private fun fakeOpenBackend(
        foreground: ForegroundOutcome = ForegroundOutcome(null, matched = false),
        matches: List<AppMatch> = emptyList(),
    ) = object : AppOpenBackend {
        override fun resolvePackage(packageName: String) = AppMatch(packageName, packageName, false)
        override fun searchByName(name: String) = matches
        override fun launchPackage(packageName: String) = true
        override fun launchUri(uri: String) = UriDispatch.Ok("com.example.app")
        override fun awaitForeground(targetPackage: String?, waitMs: Long) = foreground
    }

    private fun fakeControlBackend(stopped: Boolean? = false, frozen: Boolean? = false) =
        object : AppControlBackend {
            override fun exists(packageName: String) = true
            override fun run(packageName: String, action: AppControlAction) = ToggleDispatch.OK
            override fun readState(packageName: String, action: AppControlAction) =
                AppControlState(stopped = stopped, frozen = frozen)
        }
}
