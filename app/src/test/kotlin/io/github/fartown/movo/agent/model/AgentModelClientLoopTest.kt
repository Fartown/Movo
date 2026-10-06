package io.github.fartown.movo.agent.model

import io.github.fartown.movo.agent.runtime.AgentEvent
import io.github.fartown.movo.agent.runtime.AgentRunController
import io.github.fartown.movo.agent.tool.AgentToolCapabilities
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelClientLoopTest {
    @Test
    fun eachRoundUsesOneCapabilitySnapshotForDeclarationValidationAndPrompt() {
        var root = true
        var captures = 0
        val executed = mutableListOf<String>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                assertTrue(request.tools.toString().contains("set_setting"))
                assertTrue(request.messages.toString().contains("相关应用私有文件与数据库"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("first", "get_current_context", "{}")))
            },
            { request, _ ->
                assertFalse(request.tools.toString().contains("set_setting"))
                assertFalse(request.messages.toString().contains("相关应用私有文件与数据库"))
                assertTrue(request.messages.toString().contains("identity=user"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    toolCall("stale", "terminal", "{\"action\":\"open\",\"identity\":\"root\"}"),
                ))
            },
            { request, _ ->
                assertTrue(request.messages.toString().contains("INVALID_TOOL_ARGUMENTS"))
                assistant(content = "完成", finishReason = "stop")
            },
        ))
        AgentModelClient.complete(
            config = modelConfig().copy(terminalTools = true, deviceSensitiveActionTools = true),
            prompt = "开始",
            provider = provider,
            capabilitiesProvider = {
                captures++
                AgentToolCapabilities(rootAvailable = root)
            },
            toolExecutor = AgentModelClient.ToolExecutor {
                executed += it.id
                root = false
                AgentModelClient.ToolResult("{\"ok\":true}")
            },
        )
        assertEquals(listOf("first"), executed)
        assertEquals(4, captures)
    }

    @Test
    fun textOnlyRunReturnsIncrementalTranscript() {
        val provider = ScriptedProvider(
            assistant(content = "完成", finishReason = "stop")
        )

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "当前问题",
            history = listOf(
                AgentModelClient.ConversationMessage(role = "user", content = "旧问题"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "旧回答"),
            ),
            toolExecutor = AgentModelClient.ToolExecutor { error("不应调用工具") },
            provider = provider,
        )

        assertEquals("完成", result.content)
        assertEquals(listOf("assistant"), result.transcript.map { it.role })
        assertEquals("完成", result.transcript.single().content)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun toolBatchFeedsResultsBackInSourceOrder() {
        val provider = ScriptedProvider(
            assistant(
                content = "先执行",
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall("call-1", "get_current_context", "{}"),
                    toolCall("call-2", "get_current_context", "{}"),
                ),
                reasoning = "需要两个结果",
            ),
            assistant(content = "已完成", finishReason = "stop"),
        )
        val executed = mutableListOf<String>()

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "开始",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id
                AgentModelClient.ToolResult(
                    JSONObject()
                        .put("ok", true)
                        .put("call", call.id)
                        .toString()
                )
            },
            provider = provider,
        )

        assertEquals(listOf("call-1", "call-2"), executed)
        assertEquals("需要两个结果", result.reasoningContent)
        assertEquals(
            listOf("assistant", "tool", "tool", "assistant"),
            result.transcript.map { it.role },
        )
        assertEquals(
            listOf("assistant", "tool", "tool"),
            provider.requests[1].roleSuffix(3),
        )
        assertEquals("call-1", provider.requests[1].getJSONObjectFromEnd(2).getString("tool_call_id"))
        assertEquals("call-2", provider.requests[1].getJSONObjectFromEnd(1).getString("tool_call_id"))
    }

    @Test
    fun steeringWaitsForWholeToolBatchWithoutCancellingResources() {
        val controller = AgentRunController()
        val cancelledResources = AtomicInteger(0)
        controller.register { cancelledResources.incrementAndGet() }
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall("call-1", "get_current_context", "{}"),
                    toolCall("call-2", "get_current_context", "{}"),
                ),
            ),
            assistant(content = "已按补充完成", finishReason = "stop"),
        )
        val executed = mutableListOf<String>()

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "开始",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id
                if (call.id == "call-1") controller.steer("改用第二种方案")
                AgentModelClient.ToolResult(JSONObject().put("ok", true).toString())
            },
            provider = provider,
            runController = controller,
        )

        assertEquals(listOf("call-1", "call-2"), executed)
        assertEquals(0, cancelledResources.get())
        assertFalse(controller.hasPendingSteering)
        assertEquals("已按补充完成", result.content)
        assertEquals(
            listOf("assistant", "tool", "tool", "user"),
            provider.requests[1].roleSuffix(4),
        )
        assertTrue(
            provider.requests[1]
                .getJSONObjectFromEnd(1)
                .getString("content")
                .contains("改用第二种方案")
        )
    }

    @Test
    fun steeringAfterTextResponsePreservesThatAssistantTurn() {
        val controller = AgentRunController()
        val provider = ScriptedProvider(
            responses = listOf(
                { _, _ ->
                    controller.steer("再补充一项")
                    assistant(content = "第一段回答", finishReason = "stop")
                },
                { _, _ -> assistant(content = "最终回答", finishReason = "stop") },
            )
        )

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "开始",
            toolExecutor = AgentModelClient.ToolExecutor { error("不应调用工具") },
            provider = provider,
            runController = controller,
        )

        assertEquals("最终回答", result.content)
        assertEquals(
            listOf("assistant", "user", "assistant"),
            result.transcript.map { it.role },
        )
        assertEquals("第一段回答", result.transcript.first().content)
    }

    @Test
    fun truncatedToolCallIsReportedWithoutExecution() {
        listOf("length", "max_tokens").forEach { finishReason ->
            val provider = ScriptedProvider(
                assistant(
                    finishReason = finishReason,
                    toolCalls = listOf(toolCall("call-1", "terminal", "{\"command\":\"rm -")),
                ),
                assistant(content = "已重新规划", finishReason = "stop"),
            )
            var executed = false
            val events = mutableListOf<AgentEvent>()

            val result = AgentModelClient.complete(
                config = modelConfig(),
                prompt = "执行任务",
                toolExecutor = AgentModelClient.ToolExecutor {
                    executed = true
                    AgentModelClient.ToolResult("unexpected")
                },
                provider = provider,
                onEvent = events::add,
            )

            assertFalse(executed)
            assertEquals("已重新规划", result.content)
            val toolResult = provider.requests[1].getJSONObjectFromEnd(1)
            assertEquals("tool", toolResult.getString("role"))
            assertTrue(toolResult.getString("content").contains("TRUNCATED_TOOL_CALL"))
            val toolStarted = events.filterIsInstance<AgentEvent.ToolStarted>().single()
            assertFalse(toolStarted.argsPreview.contains("rm -"))
        }
    }

    @Test
    fun malformedAndDuplicateToolCallsReceiveStableTerminalResults() {
        val malformedCalls = JSONArray()
            .put(toolCall("duplicate", "get_current_context", "{}"))
            .put(toolCall("duplicate", "get_current_context", "{}"))
            .put("not-an-object")
        val firstResponse = assistant(content = "", finishReason = "tool_calls")
            .put("tool_calls", malformedCalls)
        val provider = ScriptedProvider(
            firstResponse,
            assistant(content = "recovered", finishReason = "stop"),
        )
        val executed = mutableListOf<Pair<String, String>>()

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "开始",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id to call.name
                AgentModelClient.ToolResult(JSONObject().put("ok", false).toString())
            },
            provider = provider,
        )

        assertEquals(
            listOf(
                "duplicate" to "get_current_context",
                "duplicate_1" to "get_current_context",
            ),
            executed,
        )
        val secondRequest = provider.requests[1]
        assertEquals(
            listOf("duplicate", "duplicate_1", "tool_call_2"),
            secondRequest
                .getJSONObject(secondRequest.length() - 4)
                .getJSONArray("tool_calls")
                .let { calls -> (0 until calls.length()).map { calls.getJSONObject(it).getString("id") } },
        )
        assertEquals(
            listOf("duplicate", "duplicate_1", "tool_call_2"),
            (3 downTo 1).map { offset ->
                secondRequest.getJSONObjectFromEnd(offset).getString("tool_call_id")
            },
        )
    }

    @Test
    fun imageObservationsFollowEveryToolResultInTheBatch() {
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall("call-1", "observe_screen", "{}"),
                    toolCall("call-2", "get_current_context", "{}"),
                ),
            ),
            assistant(content = "看到了", finishReason = "stop"),
        )

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "观察",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                AgentModelClient.ToolResult(
                    content = JSONObject().put("ok", true).toString(),
                    images = if (call.id == "call-1") {
                        listOf(
                            AgentModelClient.ModelImage(
                                reference = "data:image/png;base64,AA==",
                                mimeType = "image/png",
                                bytes = 1,
                            )
                        )
                    } else {
                        emptyList()
                    },
                )
            },
            provider = provider,
        )

        assertEquals(
            listOf("assistant", "tool", "tool", "user"),
            provider.requests[1].roleSuffix(4),
        )
        assertFalse(result.transcript.any { it.contentJson.contains("base64") })
        assertFalse(result.transcript.any { it.contentJson.contains("未写入持久会话") })
    }

    @Test
    fun toolScreenshotIsConsumedByExactlyOneModelRequest() {
        val screenshot = "data:image/png;base64,c2NyZWVu"
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("observe", "observe_screen", "{}")),
            ),
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("tap", "tap", "{\"x\":10,\"y\":20}")),
            ),
            assistant(content = "完成", finishReason = "stop"),
        )

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "观察后点击",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                AgentModelClient.ToolResult(
                    content = JSONObject().put("ok", true).toString(),
                    images = if (call.name == "observe_screen") {
                        listOf(
                            AgentModelClient.ModelImage(
                                reference = screenshot,
                                mimeType = "image/png",
                                bytes = 6,
                                source = "screen",
                            )
                        )
                    } else {
                        emptyList()
                    },
                )
            },
            provider = provider,
        )

        assertFalse(provider.requests[0].toString().contains(screenshot))
        assertTrue(provider.requests[1].toString().contains(screenshot))
        assertFalse(provider.requests[2].toString().contains(screenshot))
        assertFalse(result.transcript.any { it.contentJson.contains(screenshot) })
    }

    @Test
    fun newerToolImageReplacesThePreviousTransientObservation() {
        val firstImage = "data:image/png;base64,Zmlyc3Q="
        val secondImage = "data:image/png;base64,c2Vjb25k"
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("observe-1", "observe_screen", "{}")),
            ),
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("observe-2", "observe_screen", "{}")),
            ),
            assistant(content = "完成", finishReason = "stop"),
        )
        var observationIndex = 0

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "连续观察",
            toolExecutor = AgentModelClient.ToolExecutor {
                val reference = if (observationIndex++ == 0) firstImage else secondImage
                AgentModelClient.ToolResult(
                    content = JSONObject().put("ok", true).toString(),
                    images = listOf(
                        AgentModelClient.ModelImage(
                            reference = reference,
                            mimeType = "image/png",
                            bytes = 6,
                            source = "screen",
                        )
                    ),
                )
            },
            provider = provider,
        )

        assertTrue(provider.requests[1].toString().contains(firstImage))
        assertFalse(provider.requests[2].toString().contains(firstImage))
        assertTrue(provider.requests[2].toString().contains(secondImage))
    }

    @Test
    fun missingRequiredArgumentsNeverReachDeviceExecutor() {
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("call-1", "tap", "{}")),
            ),
            assistant(content = "已修正", finishReason = "stop"),
        )
        var executed = false

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "点击",
            toolExecutor = AgentModelClient.ToolExecutor {
                executed = true
                AgentModelClient.ToolResult("unexpected")
            },
            provider = provider,
        )

        assertFalse(executed)
        assertTrue(
            provider.requests[1]
                .getJSONObjectFromEnd(1)
                .getString("content")
                .contains("INVALID_TOOL_ARGUMENTS")
        )
    }

    @Test
    fun contradictoryStopReasonNeverExecutesToolCalls() {
        listOf("stop", "content_filter", "refusal").forEach { finishReason ->
            val provider = ScriptedProvider(
                assistant(
                    finishReason = finishReason,
                    toolCalls = listOf(toolCall("call-1", "tap", "{\"x\":1,\"y\":2}")),
                ),
                assistant(content = "已安全结束", finishReason = "stop"),
            )
            var executed = false

            AgentModelClient.complete(
                config = modelConfig(),
                prompt = "开始",
                toolExecutor = AgentModelClient.ToolExecutor {
                    executed = true
                    AgentModelClient.ToolResult("unexpected")
                },
                provider = provider,
            )

            assertFalse(executed)
            assertTrue(
                provider.requests[1]
                    .getJSONObjectFromEnd(1)
                    .getString("content")
                    .contains("UNEXPECTED_TOOL_CALL")
            )
        }
    }

    @Test
    fun normalToolStopAliasesExecuteValidatedCalls() {
        listOf("tool_calls", "tool_use").forEach { finishReason ->
            val provider = ScriptedProvider(
                assistant(
                    finishReason = finishReason,
                    toolCalls = listOf(toolCall("call-1", "get_current_context", "{}")),
                ),
                assistant(content = "完成", finishReason = "stop"),
            )
            var executions = 0

            AgentModelClient.complete(
                config = modelConfig(),
                prompt = "开始",
                toolExecutor = AgentModelClient.ToolExecutor {
                    executions += 1
                    AgentModelClient.ToolResult("{\"ok\":true}")
                },
                provider = provider,
            )

            assertEquals(1, executions)
        }
    }

    @Test
    fun providerFailureCarriesCompletedToolTranscriptForSafeRecovery() {
        val provider = ScriptedProvider(
            responses = listOf(
                { _, _ ->
                    assistant(
                        finishReason = "tool_calls",
                        reasoning = "先检查状态",
                        toolCalls = listOf(
                            toolCall("call-1", "get_current_context", "{}")
                        ),
                    )
                },
                { _, _ -> error("provider disconnected") },
            )
        )

        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(
                config = modelConfig(),
                prompt = "开始",
                toolExecutor = AgentModelClient.ToolExecutor {
                    AgentModelClient.ToolResult("{\"ok\":true}")
                },
                provider = provider,
            )
        }

        assertEquals(listOf("assistant", "tool"), failure.transcript.map { it.role })
        assertEquals("先检查状态", failure.reasoningContent)
    }

    @Test
    fun loopContinuesPastFormerLocalLimitsUntilProviderFinishes() {
        val toolRounds = 257
        val responses = List<(ProviderRequest, AgentRunController) -> JSONObject>(toolRounds) { index ->
            { _, _ ->
                assistant(
                    finishReason = "tool_calls",
                    toolCalls = listOf(toolCall("call-$index", "get_current_context", "{}")),
                )
            }
        } + listOf<(ProviderRequest, AgentRunController) -> JSONObject>(
            { _, _ -> assistant(content = "完成", finishReason = "stop") }
        )
        val provider = ScriptedProvider(responses)
        var executions = 0
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("开始"))

        val result = AgentLoop(
            config = modelConfig(),
            messages = messages,
            tools = AgentToolCatalog.build(terminalTools = false, browserTools = false),
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor {
                executions += 1
                AgentModelClient.ToolResult(JSONObject().put("ok", true).toString())
            },
            runController = AgentRunController(),
            traceFormatter = AgentTraceFormatter(),
            onEvent = {},
        ).run()

        assertEquals("完成", result.content)
        assertEquals(toolRounds, executions)
        assertEquals(toolRounds + 1, provider.requests.size)
    }

    @Test
    fun retryPreservesToolResultsAndImagesWithoutReplayingToolsOrFailedReasoning() {
        val requests = mutableListOf<String>()
        val sessions = mutableListOf<String>()
        val events = mutableListOf<AgentEvent>()
        var executions = 0
        val provider = object : AgentProviderClient by ScriptedProvider(emptyList()) {
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                requests += request.messages.toString()
                sessions += request.sessionId
                onEvent(ProviderEvent.RequestStarted)
                return when (requests.size) {
                    1 -> ProviderResponse(assistant(
                        finishReason = "tool_calls",
                        reasoning = "先观察",
                        toolCalls = listOf(toolCall("observe-1", "get_current_context", "{}")),
                    ))
                    2 -> {
                        onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "失败的思考"))
                        onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 1, "半截回答"))
                        onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TOOL_CALL, 2, "半截参数"))
                        throw java.net.SocketTimeoutException("timeout")
                    }
                    else -> ProviderResponse(assistant(content = "完成", finishReason = "stop", reasoning = "观察成功"))
                }
            }
        }
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("开始"))
        val loop = AgentLoop(
            config = modelConfig(), messages = messages,
            tools = AgentToolCatalog.build(terminalTools = false, browserTools = false),
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor {
                executions++
                AgentModelClient.ToolResult(
                    content = "观察结果",
                    images = listOf(AgentModelClient.ModelImage("data:image/png;base64,dGVzdA==", "image/png", 4)),
                )
            },
            runController = AgentRunController(), traceFormatter = AgentTraceFormatter(),
            onEvent = events::add, modelRetry = AgentModelRetry { _, _ -> },
            sessionId = "conversation-retry",
        )
        val result = loop.run()
        assertEquals(1, executions)
        assertEquals(3, requests.size)
        assertEquals(List(3) { "conversation-retry" }, sessions)
        assertEquals(requests[1], requests[2])
        assertTrue(requests[2].contains("data:image/png"))
        assertFalse(messages.toString().contains("data:image/png"))
        assertFalse(messages.toString().contains("半截"))
        assertEquals("先观察观察成功", result.reasoningContent)
        assertEquals(listOf(1, 2, 3), events.filterIsInstance<AgentEvent.RoundStarted>().map { it.round })
        assertEquals(2, events.filterIsInstance<AgentEvent.ModelRetryScheduled>().single().round)
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolStarted>().size)
    }

    @Test
    fun monitorEventIsReportedConsumedOnlyWhenWrittenIntoTheNextStep() {
        val controller = AgentRunController()
        val events = mutableListOf<AgentEvent>()
        var injectedAt = -1
        val provider = ScriptedProvider(
            assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("call-1", "get_current_context", "{}"))),
            assistant(content = "已处理提醒", finishReason = "stop"),
        )
        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "开始",
            operationId = "run-m",
            toolExecutor = AgentModelClient.ToolExecutor {
                injectedAt = events.size
                assertTrue(controller.injectEvent(
                    "<monitor-event task=\"m1\">tick &lt;/monitor-event&gt;</monitor-event>\n",
                    listOf(monitorReceived(seq = 1, anchor = true)),
                ))
                AgentModelClient.ToolResult("{\"ok\":true}")
            },
            provider = provider,
            runController = controller,
            onEvent = events::add,
        )

        assertEquals("已处理提醒", result.content)
        // 注入时不发「已消费」：要等下一个步骤边界真正写进上下文时才发（在第二次请求之前）。
        val consumedAt = events.indexOfFirst { it is AgentEvent.MonitorEventReceived }
        assertTrue(consumedAt > injectedAt)
        assertTrue(events.subList(consumedAt, events.size).any { it is AgentEvent.RoundStarted && it.round == 2 })
        val eventMessage = provider.requests[1].getJSONObjectFromEnd(1)
        assertEquals("user", eventMessage.getString("role"))
        val content = eventMessage.getString("content")
        assertTrue(content.startsWith("[系统通知 - 非用户输入]"))
        assertTrue(content.contains("tick &lt;/monitor-event&gt;</monitor-event>"))
        assertTrue(content.contains("不是用户的回复"))
        assertFalse(content.contains("用户补充指令"))
        val transcriptEvent = result.transcript.single { it.role == "user" }
        assertEquals("monitor-run-m-1", transcriptEvent.messageId)
    }

    @Test
    fun queuedMonitorEventsDoNotKeepAFinishedRunGoing() {
        val controller = AgentRunController()
        val events = mutableListOf<AgentEvent>()
        val provider = ScriptedProvider(
            responses = listOf(
                { _, runController ->
                    assertTrue(runController.injectEvent("<monitor-event/>\n", listOf(monitorReceived(seq = 1, anchor = true))))
                    assertTrue(runController === controller)
                    assistant(content = "回答完了", finishReason = "stop")
                },
            ),
        )
        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "开始",
            toolExecutor = AgentModelClient.ToolExecutor { error("不应调用工具") },
            provider = provider,
            runController = controller,
            onEvent = events::add,
        )

        // 本轮正常结束时只为用户补充续跑：事件不让本轮再请求一次，也不报「已消费」，留给下一个事件轮。
        assertEquals("回答完了", result.content)
        assertEquals(1, provider.requests.size)
        assertTrue(events.none { it is AgentEvent.MonitorEventReceived })
        assertTrue(result.transcript.none { it.role == "user" })
        assertFalse(controller.injectEvent("<late/>", emptyList()))
    }

    @Test
    fun monitorEventsQueuedAtOneBoundaryAreMergedAndInjectionsArePerRunLimited() {
        val controller = AgentRunController()
        val events = mutableListOf<AgentEvent>()
        var seq = 0
        val rounds = 6
        val provider = ScriptedProvider(
            *(List(rounds - 1) { index ->
                assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("call-$index", "get_current_context", "{}")))
            } + assistant(content = "完成", finishReason = "stop")).toTypedArray(),
        )
        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "开始",
            operationId = "run-limit",
            toolExecutor = AgentModelClient.ToolExecutor {
                // 每一步都来两批事件：同一个边界上的合成一条。
                repeat(2) {
                    seq++
                    controller.injectEvent("<monitor-event seq=\"$seq\"/>\n", listOf(monitorReceived(seq = seq, anchor = true)))
                }
                AgentModelClient.ToolResult("{\"ok\":true}")
            },
            provider = provider,
            runController = controller,
            onEvent = events::add,
        )

        assertEquals("完成", result.content)
        val consumed = events.filterIsInstance<AgentEvent.MonitorEventReceived>()
        // 每轮最多并入 AgentLoop.MAX_EVENT_INJECTIONS 次，每次两批合成一条、只有第一条是锚点。
        assertEquals(AgentLoop.MAX_EVENT_INJECTIONS * 2, consumed.size)
        assertEquals(List(AgentLoop.MAX_EVENT_INJECTIONS) { listOf(true, false) }.flatten(), consumed.map { it.anchor })
        val eventEntries = result.transcript.filter { it.role == "user" }
        assertEquals((1..AgentLoop.MAX_EVENT_INJECTIONS).map { "monitor-run-limit-$it" }, eventEntries.map { it.messageId })
        assertTrue(eventEntries.first().content.contains("seq=\"1\"") && eventEntries.first().content.contains("seq=\"2\""))
        assertEquals(1, eventEntries.first().content.split("[系统通知 - 非用户输入]").size - 1)
    }

    @Test
    fun finishingToolWithReplyEndsTheRunWithoutAnotherModelRound() {
        var requestTools: JSONArray? = null
        val provider = ScriptedProvider(listOf { request, _ ->
            requestTools = request.tools
            assistant(finishReason = "tool_calls", toolCalls = listOf(
                toolCall("call-open", "app_open", """{"name":"哔哩哔哩","reply":"已打开哔哩哔哩"}"""),
            ))
        })
        val executedArgs = mutableListOf<String>()
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("打开哔哩哔哩"))

        val result = finishingLoop(messages, provider) { call ->
            executedArgs += call.argumentsJson
            AgentModelClient.ToolResult(JSONObject().put("ok", true).toString())
        }.run()

        assertEquals("已打开哔哩哔哩", result.content)
        assertEquals(1, provider.requests.size)
        assertEquals("工具拿不到 reply", listOf("""{"name":"哔哩哔哩"}"""), executedArgs)
        assertEquals("assistant", messages.getJSONObjectFromEnd(1).getString("role"))
        assertEquals("已打开哔哩哔哩", messages.getJSONObjectFromEnd(1).getString("content"))
        val tools = (0 until requestTools!!.length()).associate { index ->
            val function = requestTools!!.getJSONObject(index).getJSONObject("function")
            function.getString("name") to function.getJSONObject("parameters").getJSONObject("properties")
        }
        assertTrue(tools.getValue("app_open").has(AgentLoop.FINISH_REPLY_ARG))
        assertFalse(tools.getValue("ui_observe").has(AgentLoop.FINISH_REPLY_ARG))
    }

    @Test
    fun finishingToolWithoutReplyKeepsGoing() {
        val provider = ScriptedProvider(
            assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("call-open", "app_open", """{"name":"哔哩哔哩"}"""))),
            assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("call-look", "ui_observe", "{}"))),
            assistant(content = "已搜到罗翔", finishReason = "stop"),
        )
        val result = finishingLoop(JSONArray().put(AgentConversationCodec.userTextMessage("打开哔哩哔哩搜罗翔")), provider) {
            AgentModelClient.ToolResult(JSONObject().put("ok", true).toString())
        }.run()

        assertEquals("已搜到罗翔", result.content)
        assertEquals(3, provider.requests.size)
    }

    @Test
    fun failedFinishingToolLetsTheModelHandleIt() {
        val provider = ScriptedProvider(
            assistant(finishReason = "tool_calls", toolCalls = listOf(
                toolCall("call-open", "app_open", """{"name":"芒果TV","reply":"已打开芒果TV"}"""),
            )),
            assistant(content = "电视上没有装芒果TV", finishReason = "stop"),
        )
        val result = finishingLoop(JSONArray().put(AgentConversationCodec.userTextMessage("打开芒果TV")), provider) {
            AgentModelClient.ToolResult(JSONObject().put("ok", false).put("message", "没有安装").toString(), status = "error")
        }.run()

        assertEquals("电视上没有装芒果TV", result.content)
        assertEquals(2, provider.requests.size)
    }

    @Test
    fun finishingToolThatDeclaresItsOwnReplyKeepsItAndEndsTheRunWithIt() {
        var requestTools: JSONArray? = null
        val provider = ScriptedProvider(listOf { request, _ ->
            requestTools = request.tools
            assistant(finishReason = "tool_calls", toolCalls = listOf(
                toolCall("call-end", "end_call", """{"reply":"好的，有事再叫我"}"""),
            ))
        })
        val ownReply = JSONObject().put("type", "string").put("description", "告别语")
        val endCall = functionTool("end_call", JSONObject().put(AgentLoop.FINISH_REPLY_ARG, ownReply))
        endCall.getJSONObject("function").getJSONObject("parameters").put("required", JSONArray().put(AgentLoop.FINISH_REPLY_ARG))
        val executedArgs = mutableListOf<String>()
        val result = AgentLoop(
            config = modelConfig(),
            messages = JSONArray().put(AgentConversationCodec.userTextMessage("退下")),
            tools = JSONArray().put(endCall),
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executedArgs += call.argumentsJson
                AgentModelClient.ToolResult(JSONObject().put("ok", true).toString())
            },
            runController = AgentRunController(),
            traceFormatter = AgentTraceFormatter(),
            onEvent = {},
            finishingTools = setOf("end_call"),
        ).run()

        assertEquals("好的，有事再叫我", result.content)
        assertEquals(1, provider.requests.size)
        assertEquals(listOf("{}"), executedArgs)
        val parameters = requestTools!!.getJSONObject(0).getJSONObject("function").getJSONObject("parameters")
        assertEquals("告别语", parameters.getJSONObject("properties").getJSONObject(AgentLoop.FINISH_REPLY_ARG).getString("description"))
        assertEquals(AgentLoop.FINISH_REPLY_ARG, parameters.getJSONArray("required").getString(0))
    }

    private fun finishingLoop(
        messages: JSONArray,
        provider: ScriptedProvider,
        execute: (AgentModelClient.ToolCall) -> AgentModelClient.ToolResult,
    ) = AgentLoop(
        config = modelConfig(),
        messages = messages,
        tools = JSONArray()
            .put(functionTool("app_open", JSONObject().put("name", JSONObject().put("type", "string"))))
            .put(functionTool("ui_observe", JSONObject())),
        provider = provider,
        toolExecutor = AgentModelClient.ToolExecutor { call -> execute(call) },
        runController = AgentRunController(),
        traceFormatter = AgentTraceFormatter(),
        onEvent = {},
        finishingTools = setOf("app_open"),
    )

    private fun functionTool(name: String, properties: JSONObject) = JSONObject()
        .put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", name)
            .put("parameters", JSONObject().put("type", "object").put("properties", properties)))

    private fun monitorReceived(seq: Int, anchor: Boolean) = AgentEvent.MonitorEventReceived(
        taskId = "m1", name = "喝水提醒", kind = "event", seq = seq, atMillis = 0L, text = "tick", anchor = anchor,
    )

    private class ScriptedProvider(
        private val responses: List<(ProviderRequest, AgentRunController) -> JSONObject>,
    ) : AgentProviderClient {
        constructor(vararg responses: JSONObject) : this(
            responses.map { response -> { _, _ -> response } }
        )

        override val id: String = "scripted"
        override val capabilities: ProviderCapabilities = ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false,
        )

        val requests = mutableListOf<JSONArray>()
        private var index = 0

        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse {
            requests += JSONArray(request.messages.toString())
            val response = responses.getOrNull(index)
                ?: error("缺少第 ${index + 1} 个 scripted response")
            index += 1
            return ProviderResponse(response(request, runController))
        }
    }

    private fun modelConfig(): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
            browserTools = false,
        )

    private fun assistant(
        content: String = "",
        finishReason: String,
        toolCalls: List<JSONObject> = emptyList(),
        reasoning: String = "",
    ): JSONObject =
        JSONObject()
            .put("role", "assistant")
            .put("content", content)
            .put("reasoning_content", reasoning)
            .put("finish_reason", finishReason)
            .also { message ->
                if (toolCalls.isNotEmpty()) {
                    message.put("tool_calls", JSONArray(toolCalls))
                }
            }

    private fun toolCall(
        id: String,
        name: String,
        arguments: String,
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("arguments", arguments),
            )

    private fun JSONArray.roleSuffix(count: Int): List<String> =
        ((length() - count) until length()).map { index ->
            getJSONObject(index).getString("role")
        }

    private fun JSONArray.getJSONObjectFromEnd(offset: Int): JSONObject =
        getJSONObject(length() - offset)
}
