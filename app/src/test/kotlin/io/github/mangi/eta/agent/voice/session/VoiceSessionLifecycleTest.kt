package io.github.mangi.eta.agent.voice.session

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.voice.conversation.VoiceConversationController
import io.github.mangi.eta.agent.voice.conversation.VoiceConversationSession
import io.github.mangi.eta.agent.voice.conversation.VoiceTurnCoordinator
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.app.AgentAppState
import io.github.mangi.eta.ui.components.AgentConversationDraftStore
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the real session owner + AppState; only audio/network scheduling is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VoiceSessionLifecycleTest {
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var owner: VoiceSessionOwner
    private lateinit var audio: FakeAudio
    private val noticeExpiries = mutableListOf<() -> Unit>()
    private val service = object : VoiceSessionOwner.ServiceHost {
        override fun onVoiceState(state: VoiceSessionUiState) = Unit
        override fun onVoiceClosed() = Unit
    }

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        AgentConversationDraftStore.shared.clear()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        owner = VoiceSessionOwner(scope, expireNotice = { noticeExpiries += it }) { _, host -> FakeAudio(host).also { audio = it } }
    }
    @After fun cleanup() { scope.cancel(); AgentConversationDraftStore.shared.clear(); EtaDatabase.closeForTests() }

    private fun app(): AgentAppState {
        // Admission and UI state are real. No model request escapes the test.
        val stopped = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).apply { cancel() }
        return AgentAppState(
            context,
            stopped,
            voiceSession = owner,
            // 运行时 steering 通道：默认模拟「不可用」，执行中的文字退回排队（旧行为的兜底）。
            runtimeSteerer = { runId, text, onResult ->
                steered += runId to text
                onResult(steerAccepted)
            },
        ).also { owner.attach(service, it) }
    }
    private val steered = mutableListOf<Pair<String, String>>()
    private var steerAccepted = false
    private fun begin(host: VoiceConversationHost) { owner.attach(service, host); assertTrue(owner.beginSession(context)) }
    private fun submit(text: String = "第二句话") = audio.host.submit(VoiceTurnCoordinator.Turn(1, text), "session")

    @Test fun firstVoiceSessionMigratesTheActualDraftIncludingSelection() {
        val state = app()
        val draft = AgentConversationDraftStore.shared.get(null, "写到一半")
        draft.edit { selection = TextRange(1, 3) }
        owner.beginSession(context)
        val id = state.voiceSelectedConversationId!!
        assertSame(draft, AgentConversationDraftStore.shared.get(id))
        assertEquals("写到一半", state.homeState.input)
        assertEquals(TextRange(1, 3), draft.selection)
    }

    @Test fun sendingTypedSnapshotPreservesPartialSpeechAppendedWhileSwitchingModes() {
        val state = app()
        AgentConversationDraftStore.shared.get(null, "手打正文")
        owner.beginSession(context)
        audio.transcript = "口述到一半"
        owner.switchToText()
        state.sendCurrentMessage("手打正文")
        val id = state.voiceSelectedConversationId!!
        assertEquals("口述到一半", AgentConversationDraftStore.shared.get(id).text.toString())
        assertEquals("口述到一半", state.homeState.input)
        assertEquals("手打正文", (state.homeState.messages.first() as UserMessageUi).content)
    }

    @Test fun createConversationEndsVoiceBeforeChangingItsOwner() {
        val state = app(); owner.beginSession(context)
        val original = state.voiceSelectedConversationId!!
        audio.transcript = "留下这半句"
        state.createConversation()
        assertFalse(owner.active)
        assertEquals(1, audio.ends)
        assertNull(state.voiceSelectedConversationId)
        assertEquals("留下这半句", AgentConversationDraftStore.shared.get(original).text.toString())
        submit("不能发送到旧会话")
        assertTrue(state.homeState.messages.isEmpty())
    }

    @Test fun switchingToHistoryClosesVoiceBoundToPreviousConversation() {
        val state = app()
        val first = state.voiceConversationId()
        state.createConversation()
        owner.beginSession(context)
        val second = state.voiceSelectedConversationId!!
        audio.transcript = "第二会话草稿"
        state.selectConversation(first)
        assertEquals(first, state.voiceSelectedConversationId)
        assertFalse(owner.active)
        assertEquals("第二会话草稿", AgentConversationDraftStore.shared.get(second).text.toString())
    }

    @Test fun deleteActiveConversationEndsItsMicrophoneSession() {
        val state = app(); owner.beginSession(context)
        state.deleteConversation(state.voiceSelectedConversationId!!)
        assertFalse(owner.active)
        assertEquals(1, audio.ends)
    }

    @Test fun endRevokesQueuedDispatchBeforeAsynchronousAudioClose() {
        val host = FakeConversations(); host.running = true; begin(host)
        submit()
        owner.end()
        host.running = false; Snapshot.sendApplyNotifications()
        submit("迟到SDK事件")
        assertTrue(host.sent.isEmpty())
        assertEquals(listOf("第二句话"), host.retained)
        assertTrue(owner.busy) // Audio has not closed yet.
        audio.host.onClosed()
        assertFalse(owner.busy)
    }

    @Test fun cancellingTaskAlsoRevokesVoiceWaitingBehindTheCurrentTextRun() {
        val host = FakeConversations(); host.running = true; begin(host)
        submit("不应在取消后执行")
        audio.host.cancelTask()
        host.running = false; Snapshot.sendApplyNotifications()
        assertTrue(host.sent.isEmpty())
        assertEquals(1, host.stops)
        assertEquals(1, audio.results)
        assertTrue(owner.active)
    }

    @Test fun repeatedInactiveEventsAndLateResultsRetainTranscriptOnlyOnce() {
        val host = FakeConversations(); begin(host)
        submit("已发送正文")
        audio.transcript = "还没说完"
        owner.end()
        audio.host.onState("result", false, "", "还没说完")
        host.sent.single().result(AgentRuntimeWire.RunResult("run", true, "迟到的回答", null))
        owner.end()
        assertEquals(listOf("还没说完"), host.retained)
        assertEquals(0, audio.results)
    }

    @Test fun rejectionRestoresExactImagesAndTextWithoutAutomaticSideEffects() {
        val host = FakeConversations()
        val image = PendingImageUi("image", "content://image", "data:image/png;base64,YQ==", "image/png")
        host.images = listOf(image); begin(host); submit()
        val request = host.sent.single()
        assertEquals(listOf(image), request.images)
        host.images = emptyList() // Simulate admission consuming attachments before Runtime rejection.
        val rejected = AgentRuntimeWire.RunResult("run", false, "", "busy", resultKind = "rejected")
        request.result(rejected); request.result(rejected)
        Snapshot.sendApplyNotifications()
        assertEquals(listOf("第二句话"), host.retained)
        assertEquals(listOf(image), host.images)
        assertEquals(1, host.sent.size)
        assertFalse(owner.active)
        assertEquals(0, audio.results) // Must not drain the controller's next utterance on rejection.
    }

    @Test fun oldSessionCallbacksCannotCloseOrSpeakInTheNewSession() {
        val host = FakeConversations(); begin(host); submit()
        val oldAudio = audio
        owner.end(); oldAudio.host.onClosed()
        assertTrue(owner.beginSession(context))
        host.sent.single().result(AgentRuntimeWire.RunResult("run", true, "旧回复", null))
        oldAudio.host.onClosed()
        oldAudio.host.onState("ended", false, "", "旧字幕")
        assertTrue(owner.active)
        assertEquals(0, audio.results)
        assertTrue(host.retained.isEmpty())
    }

    @Test fun appAdmissionRejectionDoesNotConsumePendingImage() {
        val state = app(); val id = state.voiceConversationId()
        state.sendCurrentMessage("第一条")
        state.retainVoiceDraft(id, "草稿", listOf(PendingImageUi("image", "content://image", "data:image/png;base64,YQ==", "image/png")))
        val before = state.pendingVoiceImages(id)
        var result: AgentRuntimeWire.RunResult? = null
        state.sendVoiceMessage(id, "second", "第二条", before, "session", {}, { result = it })
        assertEquals("rejected", result?.resultKind)
        assertEquals(before, state.pendingVoiceImages(id))
    }

    @Test fun busyTextSubmissionIsQueuedAndEditRestoresDraftAndAttachments() {
        val state = app(); val id = state.voiceConversationId()
        state.sendCurrentMessage("正在执行")
        state.retainVoiceDraft(id, "临时", listOf(PendingImageUi("image", "content://image", "preview", "image/png")))
        val image = state.homeState.pendingImages.single()
        val draft = AgentConversationDraftStore.shared.get(id)
        draft.edit { replace(0, length, "下一条") }
        state.sendCurrentMessage("下一条")
        assertEquals("下一条", state.queuedTextSubmission?.text)
        assertTrue(state.homeState.pendingImages.isEmpty())
        assertEquals(1, state.homeState.messages.filterIsInstance<UserMessageUi>().size)
        draft.edit { replace(0, length, "后续输入") }
        state.sendCurrentMessage("后续输入")
        assertEquals("下一条", state.queuedTextSubmission?.text)
        assertEquals("后续输入", draft.text.toString())
        state.withdrawQueuedText(edit = true)
        assertNull(state.queuedTextSubmission)
        assertEquals("下一条\n后续输入", draft.text.toString())
        assertEquals(listOf(image), state.homeState.pendingImages)
        assertTrue(state.voiceRuntimeBusy)
    }

    @Test fun busyTextBecomesASupplementWhenTheRuntimeAcceptsIt() {
        // 规范 8.4：执行中再发的话作为补充交给当前任务，不排队、不开新一轮。
        steerAccepted = true
        val state = app(); val id = state.voiceConversationId()
        state.sendCurrentMessage("正在执行")
        val runId = state.homeState.messages.filterIsInstance<UserMessageUi>().single().id.removePrefix("user-")
        val draft = AgentConversationDraftStore.shared.get(id)
        draft.edit { replace(0, length, "改成大杯") }
        state.sendCurrentMessage("改成大杯")
        assertEquals(listOf(runId to "改成大杯"), steered)
        assertNull(state.queuedTextSubmission)
        assertEquals("", draft.text.toString())
        assertEquals(1, state.homeState.messages.filterIsInstance<UserMessageUi>().size)
        assertTrue(state.voiceRuntimeBusy)
    }

    @Test fun voiceTurnDuringARunBecomesASupplementWhenAccepted() {
        // 规范 8.5：执行中说的话作为补充交给当前任务，语音立即回到聆听，不排队、不开新一轮。
        val host = FakeConversations().apply { running = true; steerAccepts = true }
        begin(host)
        submit("改成大杯")
        assertEquals(listOf("改成大杯"), host.steered)
        assertTrue(host.sent.isEmpty())
        assertEquals("已补充到当前任务", owner.state.value.statusText)
        assertEquals(1, owner.state.value.supplements)
        assertTrue(owner.state.value.active)
    }

    @Test fun withdrawingQueuedTextDoesNotCancelTheRunningTask() {
        val state = app(); state.sendCurrentMessage("正在执行")
        state.sendCurrentMessage("排队消息")
        state.withdrawQueuedText(edit = false)
        assertNull(state.queuedTextSubmission)
        assertTrue(state.voiceRuntimeBusy)
        assertEquals(1, state.homeState.messages.filterIsInstance<UserMessageUi>().size)
    }

    @Test fun queuedTextDrainsToItsOriginalConversationAfterTheRunCompletes() {
        val state = app(); state.sendCurrentMessage("第一条")
        val original = state.voiceSelectedConversationId!!
        val firstRun = state.homeState.messages.filterIsInstance<UserMessageUi>().single().id.removePrefix("user-")
        state.sendCurrentMessage("第二条")
        state.createConversation()
        val other = state.voiceConversationId()
        val applyResult = AgentAppState::class.java.declaredMethods.single { it.name == "applyRunResult" }
        applyResult.isAccessible = true
        applyResult.invoke(state, firstRun, AgentRuntimeWire.RunResult(firstRun, true, "完成", null), false, null)
        assertFalse(state.voiceRuntimeBusy)
        state.drainQueuedText()
        assertEquals(other, state.voiceSelectedConversationId)
        assertTrue(state.homeState.messages.isEmpty())
        assertNull(state.queuedTextSubmission)
        state.selectConversation(original)
        assertEquals(listOf("第一条", "第二条"), state.homeState.messages.filterIsInstance<UserMessageUi>().map { it.content })
        assertTrue(state.voiceRuntimeBusy)
    }

    @Test fun recoveredTerminalResultNotifiesQueueObserversAfterUnconfirmedRun() {
        val state = app(); val id = state.voiceConversationId()
        state.sendVoiceMessage(id, "recover-run", "第一条", emptyList(), "session", {}, {})
        val applyResult = AgentAppState::class.java.declaredMethods.single { it.name == "applyRunResult" }
        applyResult.isAccessible = true
        applyResult.invoke(state, "recover-run", AgentRuntimeWire.RunResult("recover-run", false, "", "pending", resultKind = "unconfirmed"), false, null)
        val observed = mutableListOf<Boolean>()
        val observer = scope.launch { snapshotFlow { state.voiceRuntimeBusy }.collect { observed += it } }
        assertEquals(listOf(true), observed)
        applyResult.invoke(state, "recover-run", AgentRuntimeWire.RunResult("recover-run", true, "已恢复", null), false, null)
        Snapshot.sendApplyNotifications()
        assertEquals(listOf(true, false), observed)
        observer.cancel()
    }

    @Test fun endReasonOutlivesTheStatusStripThenExpires() {
        val host = FakeConversations(); begin(host)
        audio.end("暂时没有听到说话，语音已结束，可再次唤醒") // Controller-owned timeout, not a user action.
        assertFalse(owner.state.value.active)
        assertEquals("暂时没有听到说话，语音已结束，可再次唤醒", owner.state.value.notice)
        noticeExpiries.last().invoke()
        assertNull(owner.state.value.notice)
    }

    @Test fun closingPublishesKeepTheFinalReasonNotTheTransientStatus() {
        val host = FakeConversations(); begin(host)
        // A spoken "结束对话" publishes the stale endpoint status first, then the final one.
        audio.active = false
        audio.host.onState("ended", false, "听到了，可以继续补充…", "")
        audio.host.onState("ended", false, "语音对话已结束", "")
        assertEquals("语音对话已结束", owner.state.value.notice)
        audio.host.onClosed()
        audio.host.onState("result", false, "迟到状态", "")
        assertEquals("语音对话已结束", owner.state.value.notice)
    }

    @Test fun switchingToTextByHandShowsNoEndNotice() {
        val host = FakeConversations(); begin(host)
        owner.switchToText()
        assertFalse(owner.state.value.active)
        assertNull(owner.state.value.notice)
    }

    @Test fun startFailureReasonIsVisible() {
        val host = FakeConversations(); host.failure = "请等上下文整理和模型切换完成后再开始语音"
        owner.attach(service, host)
        assertFalse(owner.beginSession(context))
        assertEquals("请等上下文整理和模型切换完成后再开始语音", owner.state.value.notice)
    }

    @Test fun newSessionClearsTheNoticeAndAStaleExpiryCannotClearTheNextOne() {
        val host = FakeConversations(); begin(host)
        audio.end("网络连接失败")
        val firstExpiry = noticeExpiries.last()
        audio.host.onClosed()
        assertTrue(owner.beginSession(context))
        assertNull(owner.state.value.notice)
        audio.end("鉴权失败")
        firstExpiry()
        assertEquals("鉴权失败", owner.state.value.notice)
    }

    private class FakeAudio(val host: VoiceConversationController.Host) : VoiceConversationSession {
        override var active = false
        override val busy get() = active
        var transcript = ""
        var ends = 0
        var results = 0
        override fun start() { active = true; host.onState("listening", true, "我在听", "") }
        override fun end(message: String) { ends++; active = false; host.onState("ended", false, message, transcript) }
        override fun stopSpeaking() = Unit
        override fun result(turn: Long, answer: String, confirmed: Boolean, failure: String?) { results++ }
        override fun runtimeEvent(turn: Long, event: AgentEvent) = Unit
    }
    private class FakeConversations : VoiceConversationHost {
        override val voiceSelectedConversationId = "conversation"
        var running by mutableStateOf(false)
        override val voiceRuntimeBusy get() = running
        var images = emptyList<PendingImageUi>()
        val retained = mutableListOf<String>()
        data class Request(val images: List<PendingImageUi>, val result: (AgentRuntimeWire.RunResult) -> Unit)
        val sent = mutableListOf<Request>()
        var stops = 0
        var failure: String? = null
        override fun voiceConversationId() = failure?.let { throw IllegalStateException(it) } ?: voiceSelectedConversationId
        override fun retainVoiceDraft(conversationId: String, text: String, images: List<PendingImageUi>) {
            retained += text
            this.images = (this.images + images).distinctBy { it.id }
        }
        override fun pendingVoiceImages(conversationId: String) = images
        override fun sendVoiceMessage(conversationId: String, runId: String, prompt: String, images: List<PendingImageUi>, voiceSessionId: String, onEvent: (AgentEvent) -> Unit, onResult: (AgentRuntimeWire.RunResult) -> Unit) { sent += Request(images, onResult) }
        override fun cancelVoiceRun(runId: String) = Unit
        override fun stopCurrentRun() { stops++ }
        var steerAccepts = false
        val steered = mutableListOf<String>()
        override fun steerVoiceTurn(conversationId: String, text: String, onResult: (Boolean) -> Unit) {
            steered += text
            onResult(steerAccepts)
        }
    }
}
