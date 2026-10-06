package io.github.fartown.movo.tv

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用扬声器参考消掉节目声（.docs/tv-audio-aec）。 */
class TvEchoCancellerTest {
    private val rate = 16000
    private val block = TvEchoCanceller.BLOCK

    @Test
    fun fftRoundTripRestoresTheSignal() {
        val random = Random(1)
        val original = FloatArray(512) { random.nextFloat() - 0.5f }
        val re = original.copyOf(); val im = FloatArray(512)
        TvEchoCanceller.fft(re, im, inverse = false)
        TvEchoCanceller.fft(re, im, inverse = true)
        for (i in original.indices) assertEquals(original[i], re[i] / 512, 1e-5f)
    }

    @Test
    fun programEchoIsSuppressedWhileTheUserStaysAudible() {
        val n = rate * 12
        val program = speechLike(n, seed = 2, level = 0.3f)
        val echo = roomEcho(program)
        val user = FloatArray(n).also { speechLike(rate * 2, seed = 3, level = 0.2f).copyInto(it, rate * 8) }
        val noise = Random(4)
        val mic = FloatArray(n) { echo[it] + user[it] + (noise.nextFloat() - 0.5f) * 0.004f }

        val out = cancel(mic, program)

        val programOnly = (rate * 4) until (rate * 8)
        val talking = (rate * 8) until (rate * 10)
        val suppressed = db(mic, programOnly) - db(out, programOnly)
        assertTrue("节目声应压掉至少 18 dB，实际 $suppressed", suppressed >= 18)
        val kept = correlation(out, user, talking)
        assertTrue("人声应基本保留，相关 $kept", kept >= 0.7)
    }

    @Test
    fun silentReferenceLeavesTheMicrophoneAlone() {
        val n = rate * 2
        val mic = speechLike(n, seed = 5, level = 0.2f)
        val out = cancel(mic, FloatArray(n))
        val range = (rate / 2) until (n - block)
        assertTrue("没有参考时人声不应受损", correlation(out, mic, range) > 0.95)
    }

    @Test
    fun oneMinuteOfAudioProcessesWellWithinRealTime() {
        val n = rate * 60
        val program = speechLike(n, seed = 6, level = 0.3f)
        val started = System.nanoTime()
        cancel(roomEcho(program), program)
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("60 秒音频处理了 $seconds 秒", seconds < 10)
    }

    @Test
    fun micAndReferenceAreSplitFromTaggedFrames() {
        val raw = frames(listOf(intArrayOf(1000, 0, 0, 0, 400, 200), intArrayOf(-2000, 0, 0, 0, -400, 0)))
        val (mic, ref) = TclWavPcm.micAndReference(raw)
        assertArrayEquals(floatArrayOf(1000 / 32768f, -2000 / 32768f), mic, 1e-7f)
        assertArrayEquals(floatArrayOf(600 / 65536f, -400 / 65536f), ref!!, 1e-7f)

        val untagged = raw.copyOf().also { it[17] = 9 }
        assertNull(TclWavPcm.micAndReference(untagged).second)
    }

    @Test
    fun withoutReferenceThePipelineFallsBackToChannelZero() {
        val raw = frames(List(300) { intArrayOf(it * 10, 0, 0, 0, 0, 0) }).also { bytes ->
            for (frame in 0 until 300) bytes[frame * 24 + 17] = 9   // 参考通道标记对不上
        }
        val pipeline = TclEchoPipeline(gain = 16)
        assertArrayEquals(TclWavPcm.channelZero(raw, 16), pipeline.process(raw))
        assertEquals(false, pipeline.echoCancelling)
    }

    private fun cancel(mic: FloatArray, ref: FloatArray): FloatArray {
        val canceller = TvEchoCanceller()
        val out = FloatArray(mic.size)
        var i = 0
        while (i + block <= mic.size) {
            // 输出晚一块：把它放回对应的位置。
            val result = canceller.process(mic.copyOfRange(i, i + block), ref.copyOfRange(i, i + block))
            if (i >= block) result.copyInto(out, i - block)
            i += block
        }
        return out
    }

    /** 带包络起伏的类语音噪声。 */
    private fun speechLike(n: Int, seed: Int, level: Float): FloatArray {
        val random = Random(seed)
        val white = FloatArray(n) { random.nextFloat() - 0.5f }
        return FloatArray(n) { i ->
            var smooth = 0f
            for (k in 0 until 8) smooth += white[(i - k).coerceAtLeast(0)]
            val gate = if (sin(i * 40.0 / n) > 0.2) 1f else 0f
            smooth / 8 * gate * (0.5f + 0.5f * abs(sin(i * 300.0 / n)).toFloat()) * level * 4
        }
    }

    /** 直达声（10 ms）加指数衰减的混响。 */
    private fun roomEcho(source: FloatArray): FloatArray {
        val random = Random(7)
        val rir = FloatArray(2400).also { it[160] = 0.6f; for (k in 400 until 2400) it[k] = ((random.nextFloat() - 0.5f) * 0.1f * exp(-(k - 400) / 400.0)).toFloat() }
        return FloatArray(source.size) { i ->
            var sum = 0f
            for (k in rir.indices) if (rir[k] != 0f && i - k >= 0) sum += rir[k] * source[i - k]
            sum
        }
    }

    private fun db(x: FloatArray, range: IntRange): Double =
        10 * log10(range.sumOf { (x[it] * x[it]).toDouble() } / range.count() + 1e-12)

    private fun correlation(a: FloatArray, b: FloatArray, range: IntRange): Double {
        val ma = range.sumOf { a[it].toDouble() } / range.count(); val mb = range.sumOf { b[it].toDouble() } / range.count()
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in range) { val x = a[i] - ma; val y = b[i] - mb; ab += x * y; aa += x * x; bb += y * y }
        return ab / sqrt(aa * bb + 1e-12)
    }

    /** 造原始 6 路帧：高 16 位是声音，低 16 位是通道标记（0x100、0x200、0、0、0x300、0x700）。 */
    private fun frames(values: List<IntArray>): ByteArray {
        val tags = intArrayOf(0x100, 0x200, 0, 0, 0x300, 0x700)
        val out = ByteArray(values.size * 24)
        values.forEachIndexed { frame, channels ->
            for (c in 0 until 6) {
                val word = (channels[c] shl 16) or tags[c]
                for (b in 0 until 4) out[frame * 24 + c * 4 + b] = (word shr (8 * b)).toByte()
            }
        }
        return out
    }
}
