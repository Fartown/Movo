package io.github.mangi.eta.agent.voice.asr

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class SaucAudioStreamTest {
    @Test fun stoppingDuringAnInFlightWriteKeepsSequencesContiguous() {
        val writes = mutableListOf<Int>()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val stream = SaucAudioStream { frame ->
            val sequence = ByteBuffer.wrap(frame, 4, 4).int
            if (sequence == 2) {
                entered.countDown()
                check(unblock.await(2, TimeUnit.SECONDS))
            }
            writes += sequence
            true
        }
        assertTrue(stream.start())
        val writer = thread { stream.audio(byteArrayOf(1, 2)) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val stopper = thread { stream.finish() }
        unblock.countDown()
        writer.join(2_000)
        stopper.join(2_000)
        assertFalse(writer.isAlive)
        assertFalse(stopper.isAlive)
        // A late capture read and a repeated tap must not write after the terminal frame.
        stream.audio(byteArrayOf(3, 4))
        stream.finish()
        assertEquals(listOf(1, 2, -3), writes)
    }

    @Test fun cancelDropsPendingAudioAndDoesNotSendATerminalFrame() {
        val writes = mutableListOf<Int>()
        val stream = SaucAudioStream { writes += ByteBuffer.wrap(it, 4, 4).int; true }
        stream.start()
        stream.cancel()
        stream.audio(byteArrayOf(1))
        stream.finish()
        assertEquals(listOf(1), writes)
    }

    @Test fun finishingBeforeConnectionPreventsALaterStart() {
        val stream = SaucAudioStream { fail("Unexpected frame after stop"); true }
        assertFalse(stream.finish())
        assertFalse(stream.start())
    }
}
