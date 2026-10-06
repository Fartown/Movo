package io.github.fartown.movo.agent.voice

import io.github.fartown.movo.agent.voice.conversation.PcmWarmupBuffer
import org.junit.Assert.*
import org.junit.Test

class PcmWarmupBufferTest {
    @Test fun retainsFirstWordsAndCopiesReusableSourceBuffers() {
        val buffer = PcmWarmupBuffer(4)
        val packet = byteArrayOf(1, 2)
        assertTrue(buffer.append(packet)); packet[0] = 9
        assertTrue(buffer.append(byteArrayOf(3, 4)))
        assertFalse(buffer.append(byteArrayOf(5, 6)))
        val output = buffer.drain()
        assertArrayEquals(byteArrayOf(1, 2), output[0]); assertArrayEquals(byteArrayOf(3, 4), output[1])
        assertTrue(buffer.drain().isEmpty())
        assertTrue(buffer.append(byteArrayOf(5, 6)))
        buffer.clear(); assertTrue(buffer.drain().isEmpty())
    }
}
