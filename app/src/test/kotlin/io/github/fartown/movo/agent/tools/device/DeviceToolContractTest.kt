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

    // ---- device_read ----

    private fun locationBackend(status: String) = object : DeviceReadBackend {
        override fun read(section: DeviceSection, env: ToolEnvironment): JSONObject? = when (section) {
            DeviceSection.BATTERY -> JSONObject().put("percent", 100).put("charging", true)
            DeviceSection.LOCATION -> throw AndroidDeviceReadBackend.locationUnavailable(status)
            else -> null
        }
    }

    /** 真机：位置读不到只给「暂时读不到 / 稍后重试」。缺权限是用户要去开，retry=user，不是 later。 */
    @Test
    fun deviceRead_locationWithoutPermission_isPermissionRequiredForUser() {
        for (status in listOf("permission_required", "background_permission_required")) {
            val json = pipeline(DeviceReadTool(locationBackend(status)), ToolEnvironment())
                .run("device_read", """{"sections":["location"]}""")
            assertEquals("error", json.getString("status"))
            assertEquals("PERMISSION_REQUIRED", json.getString("code"))
            assertEquals("user", json.getString("retry"))
            assertTrue(json.getString("hint").contains("始终允许"))
            assertEquals(status, json.getString("detail"))
        }
    }

    @Test
    fun deviceRead_locationSwitchOff_isUserActionNotLater() {
        val json = pipeline(DeviceReadTool(locationBackend("location_disabled")), ToolEnvironment())
            .run("device_read", """{"sections":["location"]}""")
        assertEquals("error", json.getString("status"))
        assertEquals("SOURCE_UNAVAILABLE", json.getString("code"))
        assertEquals("user", json.getString("retry"))
        assertTrue(json.getString("message").contains("定位开关关着"))
    }

    @Test
    fun deviceRead_locationOnButNoFixYet_isRetryLater() {
        val json = pipeline(DeviceReadTool(locationBackend("unavailable")), ToolEnvironment())
            .run("device_read", """{"sections":["location"]}""")
        assertEquals("SOURCE_UNAVAILABLE", json.getString("code"))
        assertEquals("later", json.getString("retry"))
        assertTrue(json.getString("message").contains("暂时还没有最近的位置"))
    }

    /** 一起读多个 section 时整体 ok，位置失败的原因写进 warning（以前只有「location 读取失败」）。 */
    @Test
    fun deviceRead_partialFailure_warningCarriesReason() {
        val json = pipeline(DeviceReadTool(locationBackend("permission_required")), ToolEnvironment())
            .run("device_read", """{"sections":["battery","location"]}""")
        assertEquals("ok", json.getString("status"))
        assertEquals(100, json.getJSONObject("data").getJSONObject("battery").getInt("percent"))
        val warning = json.getJSONArray("warnings").getJSONObject(0)
        assertEquals("PERMISSION_REQUIRED", warning.getString("code"))
        assertTrue(warning.getString("message").contains("location 读取失败：Movo 没有位置权限"))
        assertTrue(warning.getString("message").contains("授权前重试也读不到"))
    }

    @Test
    fun deviceRead_unknownFailure_staysSourceUnavailableLater() {
        val json = pipeline(DeviceReadTool(locationBackend("unavailable")), ToolEnvironment())
            .run("device_read", """{"sections":["memory"]}""")
        assertEquals("SOURCE_UNAVAILABLE", json.getString("code"))
        assertEquals("later", json.getString("retry"))
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
            override fun readValue(namespace: SettingNamespace, key: String) =
                if (key == "screen_brightness") SettingValue.Value("120") else SettingValue.Unset
        }
        val json = pipeline(SettingReadTool(backend), ToolEnvironment())
            .run("setting_read", """{"namespace":"system","keys":["screen_brightness","missing"]}""")
        assertEquals("ok", json.getString("status"))
        assertFalse(json.has("effect_verified"))
        assertTrue(json.getBoolean("__sensitive"))
        val values = json.getJSONObject("data").getJSONObject("values")
        assertEquals("120", values.getString("screen_brightness"))
        assertTrue(values.isNull("missing"))
        assertFalse("全部读到时不该有 unreadable", json.getJSONObject("data").has("unreadable"))
    }

    /** 审计 C7：没权限读（SecurityException）和「没值」分开报——前者进 unreadable + warning，不混进 values 的 null。 */
    @Test
    fun settingRead_deniedKey_reportedApartFromUnset() {
        val backend = object : SettingReadBackend {
            override fun readValue(namespace: SettingNamespace, key: String) = when (key) {
                "screen_brightness" -> SettingValue.Value("8")
                "hidden_key" -> SettingValue.Unreadable(
                    io.github.fartown.movo.agent.tools.core.ToolError(
                        io.github.fartown.movo.agent.tools.core.ToolErrorCode.ROOT_REQUIRED,
                        "系统不允许普通应用读取这个设置（不是没值），需要 Root",
                    ),
                )
                else -> SettingValue.Unset
            }
        }
        val json = pipeline(SettingReadTool(backend), ToolEnvironment())
            .run("setting_read", """{"namespace":"global","keys":["screen_brightness","hidden_key","missing"]}""")
        assertEquals("ok", json.getString("status"))
        val data = json.getJSONObject("data")
        val values = data.getJSONObject("values")
        assertEquals("8", values.getString("screen_brightness"))
        assertTrue(values.isNull("missing"))
        assertFalse("读不了的键不能当成没值放进 values", values.has("hidden_key"))
        assertTrue(data.getJSONObject("unreadable").getString("hidden_key").contains("不是没值"))
        val warning = json.getJSONArray("warnings").getJSONObject(0)
        assertEquals("ROOT_REQUIRED", warning.getString("code"))
        assertTrue(warning.getString("message").startsWith("hidden_key"))
    }

    @Test
    fun settingRead_allDenied_isErrorNotEmptyValues() {
        val backend = object : SettingReadBackend {
            override fun readValue(namespace: SettingNamespace, key: String) = SettingValue.Unreadable(
                io.github.fartown.movo.agent.tools.core.ToolError(
                    io.github.fartown.movo.agent.tools.core.ToolErrorCode.ROOT_REQUIRED,
                    "系统不允许普通应用读取这个设置（不是没值），需要 Root",
                ),
            )
        }
        val json = pipeline(SettingReadTool(backend), ToolEnvironment())
            .run("setting_read", """{"namespace":"global","keys":["torch_enabled"]}""")
        assertEquals("error", json.getString("status"))
        assertEquals("ROOT_REQUIRED", json.getString("code"))
        assertEquals("never", json.getString("retry"))
        assertTrue(json.getString("message").contains("torch_enabled"))
    }

    /** 回复里不该只有原始键名和数字：附人话说明，原值字段不动。 */
    @Test
    fun settingRead_addsMeaningsWithoutTouchingRawValues() {
        val backend = object : SettingReadBackend {
            override fun readValue(namespace: SettingNamespace, key: String) = when (key) {
                "screen_off_timeout" -> SettingValue.Value("2147483647")
                "screen_brightness_mode" -> SettingValue.Value("1")
                "screen_brightness" -> SettingValue.Value("8")
                else -> SettingValue.Value("abc")
            }
        }
        val p = pipeline(SettingReadTool(backend), ToolEnvironment())
        val call = io.github.fartown.movo.agent.model.AgentModelClient.ToolCall(
            "c1", "setting_read",
            """{"namespace":"system","keys":["screen_off_timeout","screen_brightness_mode","screen_brightness","some_vendor_key"]}""",
        )
        val result = p.execute(call)
        val data = JSONObject(result.content).getJSONObject("data")
        val values = data.getJSONObject("values")
        assertEquals("2147483647", values.getString("screen_off_timeout"))
        assertEquals("1", values.getString("screen_brightness_mode"))
        val meanings = data.getJSONObject("meanings")
        assertEquals("自动锁屏时间：永不息屏", meanings.getString("screen_off_timeout"))
        assertTrue(meanings.getString("screen_brightness_mode").startsWith("自动亮度：开"))
        assertTrue(meanings.getString("screen_brightness").contains("不等于亮度条百分比"))
        assertFalse("不认识的键不硬造说明", meanings.has("some_vendor_key"))
        // 执行卡上也显示人话。
        val fields = (result.outcome!!.view!!.blocks.single() as io.github.fartown.movo.agent.tools.core.ToolUiBlock.Fields).rows
        assertEquals("永不息屏", fields.first { it.label == "自动锁屏时间" }.value)
    }

    @Test
    fun settingMeaning_commonValues() {
        assertEquals("无操作 30 秒后息屏", settingMeaning("screen_off_timeout", "30000"))
        assertEquals("无操作 10 分钟后息屏", settingMeaning("screen_off_timeout", "600000"))
        assertEquals("永不息屏", settingMeaning("screen_off_timeout", "2147483647"))
        assertEquals(null, settingMeaning("screen_off_timeout", "-1"))
        assertEquals("关（锁定方向）", settingMeaning("accelerometer_rotation", "0"))
        assertEquals("开（完全静音）", settingMeaning("zen_mode", "2"))
        assertEquals("开", settingMeaning("airplane_mode_on", "1"))
        assertEquals(null, settingMeaning("airplane_mode_on", "x"))
        assertEquals("24 小时制", settingMeaning("time_12_24", "24"))
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
        var called = false
        val backend = object : DeviceDiagnosticsBackend {
            override fun topProcesses(limit: Int) = DiagnosticsResult.Ok(JSONObject(), false).also { called = true }
            override fun appStorage(limit: Int) = DiagnosticsResult.Ok(JSONObject(), false).also { called = true }
            override fun logcat(maxLines: Int, level: String?, packageName: String?, query: String?) =
                DiagnosticsResult.Ok(JSONObject(), false).also { called = true }
        }
        val json = pipeline(DeviceDiagnosticsTool(backend), ToolEnvironment(rootAvailable = false))
            .run("device_diagnostics", """{"kind":"top_processes"}""")
        assertEquals("error", json.getString("status"))
        assertEquals("ROOT_REQUIRED", json.getString("code"))
        assertFalse("没 Root 不该去执行", called)
    }

    /** 没 Root 时三项诊断都必然失败：整个工具（schema 和说明）不进目录；有 Root 才出现。 */
    @Test
    fun deviceDiagnostics_catalogOnlyWithRoot() {
        val backend = object : DeviceDiagnosticsBackend {
            override fun topProcesses(limit: Int) = DiagnosticsResult.Ok(JSONObject(), false)
            override fun appStorage(limit: Int) = DiagnosticsResult.Ok(JSONObject(), false)
            override fun logcat(maxLines: Int, level: String?, packageName: String?, query: String?) =
                DiagnosticsResult.Ok(JSONObject(), false)
        }
        fun names(env: ToolEnvironment): List<String> {
            val catalog = pipeline(DeviceDiagnosticsTool(backend), env).catalog()
            return (0 until catalog.length()).map { catalog.getJSONObject(it).getJSONObject("function").getString("name") }
        }
        assertFalse("device_diagnostics" in names(ToolEnvironment(rootAvailable = false)))
        assertTrue("device_diagnostics" in names(ToolEnvironment(rootAvailable = true)))
        val unavailable = DeviceDiagnosticsTool(backend).availability(ToolEnvironment(rootAvailable = false))
        assertTrue(unavailable is io.github.fartown.movo.agent.tools.core.ToolAvailability.Unavailable)
        assertEquals(
            io.github.fartown.movo.agent.tools.core.ToolErrorCode.ROOT_REQUIRED,
            (unavailable as io.github.fartown.movo.agent.tools.core.ToolAvailability.Unavailable).code,
        )
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

    /** 小米 HyperOS 的计算器装在 /product/data-app、可卸载，不带 FLAG_SYSTEM，以前被标成 is_system:false。 */
    @Test
    fun appSearch_preinstalledDetection() {
        val system = android.content.pm.ApplicationInfo.FLAG_SYSTEM
        val updated = android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        assertTrue(LauncherAppIndex.isPreinstalled(0, "/product/data-app/MIUICalculator/MIUICalculator.apk"))
        assertTrue(LauncherAppIndex.isPreinstalled(0, "/my_stock/del-app/OppoNote2/OppoNote2.apk"))
        assertTrue(LauncherAppIndex.isPreinstalled(system, "/system/priv-app/Settings/Settings.apk"))
        assertTrue(LauncherAppIndex.isPreinstalled(updated, "/data/app/~~x==/com.android.chrome-y==/base.apk"))
        assertFalse(LauncherAppIndex.isPreinstalled(0, "/data/app/~~x==/com.tencent.mm-y==/base.apk"))
        assertFalse(LauncherAppIndex.isPreinstalled(0, null))
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

    // ---- 执行卡视图 ----

    @Test
    fun view_settingWriteShowsBeforeAndAfter() {
        var stored: String? = "100"
        val backend = object : SettingWriteBackend {
            override fun write(namespace: SettingNamespace, key: String, value: String): ToggleDispatch {
                stored = value
                return ToggleDispatch.OK
            }
            override fun read(namespace: SettingNamespace, key: String): String? = stored
        }
        val p = pipeline(SettingWriteTool(backend), ToolEnvironment(rootAvailable = true), approveAll)
        val call = io.github.fartown.movo.agent.model.AgentModelClient.ToolCall(
            "c1", "setting_write", """{"namespace":"system","key":"screen_brightness","value":"80"}""",
        )
        assertEquals("修改设置 · 屏幕亮度", p.stepTitle(call))
        val view = p.execute(call).outcome!!.view!!
        assertEquals("100 → 80", view.summary)
        val change = view.blocks.single() as io.github.fartown.movo.agent.tools.core.ToolUiBlock.Change
        assertEquals("100", change.before)
        assertEquals("80", change.after)
    }
}
