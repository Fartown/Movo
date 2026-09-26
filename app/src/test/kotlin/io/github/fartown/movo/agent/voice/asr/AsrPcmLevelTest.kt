package io.github.fartown.movo.agent.voice.asr

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class AsrPcmLevelTest {
    private fun sine(amplitude: Double, samples: Int = 800): ByteArray {
        val bytes = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val value = (amplitude * Short.MAX_VALUE * sin(2 * PI * 440 * i / 16_000)).toInt().toShort().toInt()
            bytes[i * 2] = (value and 0xFF).toByte()
            bytes[i * 2 + 1] = (value shr 8).toByte()
        }
        return bytes
    }

    @Test
    fun silenceIsZero() {
        assertEquals(0f, AsrPcmCapture.pcmLevel(ByteArray(1600), 0, 1600), 0f)
    }

    @Test
    fun levelMapsMinus50To0AndMinus10To1Linearly() {
        // 规范 9.6：−50 dB → 0、−10 dB → 1。正弦波 RMS = 振幅 / √2。
        val rmsMinus30 = 10.0.pow(-30.0 / 20)
        assertEquals(0.5f, AsrPcmCapture.pcmLevel(sine(rmsMinus30 * kotlin.math.sqrt(2.0)), 0, 1600), 0.02f)
        assertEquals(1f, AsrPcmCapture.pcmLevel(sine(0.9), 0, 1600), 0f)
        assertEquals(0f, AsrPcmCapture.pcmLevel(sine(10.0.pow(-60.0 / 20)), 0, 1600), 0f)
    }

    @Test
    fun levelReadsOnlyTheRequestedSlice() {
        val loud = sine(0.9)
        val buffer = ByteArray(loud.size * 2)
        loud.copyInto(buffer, destinationOffset = loud.size)
        assertEquals(0f, AsrPcmCapture.pcmLevel(buffer, 0, loud.size), 0f)
        assertEquals(1f, AsrPcmCapture.pcmLevel(buffer, loud.size, loud.size), 0f)
    }
}
