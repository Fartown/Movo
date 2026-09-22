package io.github.mangi.eta.agent.voice.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaDictationControllerTest {
    @Test
    fun failureEndsSessionWithoutCreatingAnotherRecognizer() {
        val engine = FakeEngine()
        var creations = 0
        val controller = EtaDictationController { creations++; engine }
        val events = Events()
        controller.start(events)

        engine.fail("豆包语音连接失败")

        assertEquals(1, creations)
        assertEquals(listOf("error:豆包语音连接失败", "ended"), events.values)
        assertFalse(controller.isRunning())
    }

    @Test
    fun stopWithoutSubmitCancelsAndIgnoresQueuedCallbacks() {
        val engine = FakeEngine()
        val controller = EtaDictationController { engine }
        val events = Events()
        controller.start(events)

        controller.stop(submitFinal = false)
        engine.listener.onPartial("旧文本")
        engine.listener.onFinal("旧文本")
        engine.fail("已取消连接")

        assertTrue(engine.cancelled)
        assertTrue(events.values.isEmpty())
        assertFalse(controller.isRunning())
    }

    @Test
    fun previousSessionCannotEndOrOverwriteNewSession() {
        val first = FakeEngine()
        val second = FakeEngine()
        val engines = ArrayDeque(listOf(first, second))
        val controller = EtaDictationController { engines.removeFirst() }
        val events = Events()
        controller.start(events)
        controller.start(events)

        first.listener.onFinal("旧结果")
        first.fail("旧连接失败")
        second.listener.onPartial("新文本")

        assertTrue(first.cancelled)
        assertTrue(controller.isRunning())
        assertEquals(listOf("partial:新文本"), events.values)
    }

    @Test
    fun stopWithSubmitWaitsForFinalResult() {
        val engine = FakeEngine()
        val controller = EtaDictationController { engine }
        val events = Events()
        controller.start(events)

        controller.stop(submitFinal = true)
        engine.listener.onFinal("识别结果")
        engine.listener.onEnded()

        assertTrue(engine.stopRequested)
        assertEquals(listOf("final:识别结果", "ended"), events.values)
        assertFalse(controller.isRunning())
    }

    private class Events : EtaAsrEngine.Listener {
        val values = mutableListOf<String>()
        override fun onPartial(text: String) { values += "partial:$text" }
        override fun onFinal(text: String) { values += "final:$text" }
        override fun onError(message: String) { values += "error:$message" }
        override fun onEnded() { values += "ended" }
    }

    private class FakeEngine : EtaAsrEngine {
        lateinit var listener: EtaAsrEngine.Listener
        var running = false
        var cancelled = false
        var stopRequested = false
        override fun start(listener: EtaAsrEngine.Listener) {
            this.listener = listener
            running = true
        }
        override fun isRunning(): Boolean = running
        override fun stop(submitFinal: Boolean) { stopRequested = submitFinal }
        override fun cancel() { cancelled = true; running = false }
        fun fail(message: String) {
            running = false
            listener.onError(message)
            listener.onEnded()
        }
    }
}
