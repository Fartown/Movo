package io.github.fartown.movo.tv

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 用电视的扬声器参考信号消掉麦克风里的节目声（.docs/tv-audio-aec）。
 *
 * TCL walleve 的原始 6 路 = 2 路麦克风 + 2 路空 + 2 路参考（参考取自音量调节之后，就是扬声器放的声音）。
 * 这里是分块频域自适应滤波（与 Speex MDF 同类：每块只对一个分块做时域约束、轮流进行）加残余回声抑制，
 * 算法与 .docs/tv-audio-aec/scripts/aec_rr.py 一一对应；在真实录音上节目声压掉约 23 dB，人声比节目高出约 21 dB。
 *
 * 输入输出都是 [-1, 1] 的浮点样本，每次 [BLOCK] 个；输出比输入晚一个块（16 ms）。
 */
internal class TvEchoCanceller(
    private val partitions: Int = 16,
    private val mu: Float = 0.5f,
    private val overSuppress: Float = 1.5f,
    private val floorGain: Float = 0.08f,
) {
    private val bins = BLOCK + 1
    // 各分块滤波器与参考历史（频域，实部 / 虚部分开）。
    private val wRe = Array(partitions) { FloatArray(bins) }
    private val wIm = Array(partitions) { FloatArray(bins) }
    private val xRe = Array(partitions) { FloatArray(bins) }
    private val xIm = Array(partitions) { FloatArray(bins) }
    private val power = FloatArray(bins) { 1e-3f }
    private val previousRef = FloatArray(BLOCK)
    private var turn = 0

    // 残余抑制的分析窗口（最近两块的误差与回声估计）与重叠相加缓冲。
    private val errorHistory = FloatArray(FFT_SIZE)
    private val echoHistory = FloatArray(FFT_SIZE)
    private val overlap = FloatArray(FFT_SIZE)

    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val eRe = FloatArray(bins)
    private val eIm = FloatArray(bins)
    private val yRe = FloatArray(bins)
    private val yIm = FloatArray(bins)

    /** 新会话开始：清掉上一段的信号缓存，保留已学到的回声路径（滤波器系数和参考功率）。 */
    fun resetStreams() {
        for (p in 0 until partitions) { xRe[p].fill(0f); xIm[p].fill(0f) }
        previousRef.fill(0f); errorHistory.fill(0f); echoHistory.fill(0f); overlap.fill(0f)
    }

    /** 消一块：[mic]、[ref] 各 [BLOCK] 个样本，返回同样长度的输出（上一块的结果）。 */
    fun process(mic: FloatArray, ref: FloatArray): FloatArray {
        require(mic.size == BLOCK && ref.size == BLOCK)
        // 参考：最近两块拼起来做 FFT，推进分块历史。
        for (i in 0 until BLOCK) { re[i] = previousRef[i]; re[BLOCK + i] = ref[i]; im[i] = 0f; im[BLOCK + i] = 0f }
        ref.copyInto(previousRef)
        fft(re, im, inverse = false)
        val oldestRe = xRe[partitions - 1]; val oldestIm = xIm[partitions - 1]
        for (p in partitions - 1 downTo 1) { xRe[p] = xRe[p - 1]; xIm[p] = xIm[p - 1] }
        xRe[0] = oldestRe; xIm[0] = oldestIm
        for (k in 0 until bins) { xRe[0][k] = re[k]; xIm[0][k] = im[k] }

        // 回声估计 y = Σ W·X，取后半段。
        for (k in 0 until bins) {
            var sr = 0f; var si = 0f
            for (p in 0 until partitions) {
                val a = wRe[p][k]; val b = wIm[p][k]; val c = xRe[p][k]; val d = xIm[p][k]
                sr += a * c - b * d; si += a * d + b * c
            }
            yRe[k] = sr; yIm[k] = si
        }
        val echo = inverseReal(yRe, yIm)
        val error = FloatArray(BLOCK) { mic[it] - echo[BLOCK + it] }

        // 误差频谱（前半补零），按参考功率归一化后更新全部分块。
        for (i in 0 until BLOCK) { re[i] = 0f; re[BLOCK + i] = error[i]; im[i] = 0f; im[BLOCK + i] = 0f }
        fft(re, im, inverse = false)
        for (k in 0 until bins) {
            eRe[k] = re[k]; eIm[k] = im[k]
            val x2 = xRe[0][k] * xRe[0][k] + xIm[0][k] * xIm[0][k]
            power[k] = 0.9f * power[k] + 0.1f * x2
        }
        for (k in 0 until bins) {
            val step = mu / (power[k] * partitions + 1e-6f)
            val gr = eRe[k] * step; val gi = eIm[k] * step
            for (p in 0 until partitions) {
                // W += conj(X)·E·step
                val c = xRe[p][k]; val d = xIm[p][k]
                wRe[p][k] += c * gr + d * gi
                wIm[p][k] += c * gi - d * gr
            }
        }
        constrain(turn)
        turn = (turn + 1) % partitions

        return suppress(error, echo.copyOfRange(BLOCK, FFT_SIZE))
    }

    /** 时域约束：滤波器只保留前 [BLOCK] 个系数（轮流对一个分块做，Speex MDF 同法）。 */
    private fun constrain(p: Int) {
        val w = inverseReal(wRe[p], wIm[p])
        for (i in 0 until FFT_SIZE) { re[i] = if (i < BLOCK) w[i] else 0f; im[i] = 0f }
        fft(re, im, inverse = false)
        for (k in 0 until bins) { wRe[p][k] = re[k]; wIm[p][k] = im[k] }
    }

    /** 残余回声抑制：按回声估计谱做维纳式增益，50% 重叠相加，输出晚一块。 */
    private fun suppress(error: FloatArray, echo: FloatArray): FloatArray {
        errorHistory.copyInto(errorHistory, 0, BLOCK, FFT_SIZE); error.copyInto(errorHistory, BLOCK)
        echoHistory.copyInto(echoHistory, 0, BLOCK, FFT_SIZE); echo.copyInto(echoHistory, BLOCK)
        for (i in 0 until FFT_SIZE) { re[i] = echoHistory[i] * WINDOW[i]; im[i] = 0f }
        fft(re, im, inverse = false)
        for (k in 0 until bins) { yRe[k] = re[k]; yIm[k] = im[k] }
        for (i in 0 until FFT_SIZE) { re[i] = errorHistory[i] * WINDOW[i]; im[i] = 0f }
        fft(re, im, inverse = false)
        for (k in 0 until bins) {
            val e2 = re[k] * re[k] + im[k] * im[k]
            val y2 = yRe[k] * yRe[k] + yIm[k] * yIm[k]
            val gain = (1f - overSuppress * y2 / (e2 + 1e-9f)).coerceIn(floorGain, 1f)
            eRe[k] = re[k] * gain; eIm[k] = im[k] * gain
        }
        val cleaned = inverseReal(eRe, eIm)
        for (i in 0 until FFT_SIZE) overlap[i] += cleaned[i] * WINDOW[i] / 1.5f
        val out = overlap.copyOfRange(0, BLOCK)
        overlap.copyInto(overlap, 0, BLOCK, FFT_SIZE)
        overlap.fill(0f, BLOCK, FFT_SIZE)
        return out
    }

    /** 由一半频谱（0..N/2）还原实信号，带 1/N（与 numpy irfft 一致）。 */
    private fun inverseReal(specRe: FloatArray, specIm: FloatArray): FloatArray {
        for (k in 0 until bins) { re[k] = specRe[k]; im[k] = specIm[k] }
        for (k in 1 until BLOCK) { re[FFT_SIZE - k] = specRe[k]; im[FFT_SIZE - k] = -specIm[k] }
        fft(re, im, inverse = true)
        return FloatArray(FFT_SIZE) { re[it] / FFT_SIZE }
    }

    companion object {
        /** 每块样本数：16 kHz 下 16 ms。 */
        const val BLOCK = 256
        private const val FFT_SIZE = 2 * BLOCK
        private const val LOG2 = 9

        private val WINDOW = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1))).toFloat() }
        private val COS = FloatArray(FFT_SIZE / 2) { cos(2 * PI * it / FFT_SIZE).toFloat() }
        private val SIN = FloatArray(FFT_SIZE / 2) { sin(2 * PI * it / FFT_SIZE).toFloat() }
        private val REVERSED = IntArray(FFT_SIZE) { Integer.reverse(it) ushr (32 - LOG2) }

        /** 原地基 2 FFT（正变换不缩放，逆变换不缩放，由调用方除以 N）。 */
        internal fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
            for (i in 0 until FFT_SIZE) {
                val j = REVERSED[i]
                if (j > i) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var size = 2
            while (size <= FFT_SIZE) {
                val half = size / 2
                val stride = FFT_SIZE / size
                var start = 0
                while (start < FFT_SIZE) {
                    for (k in 0 until half) {
                        val c = COS[k * stride]
                        val s = if (inverse) SIN[k * stride] else -SIN[k * stride]
                        val a = start + k; val b = a + half
                        val tr = re[b] * c - im[b] * s
                        val ti = re[b] * s + im[b] * c
                        re[b] = re[a] - tr; im[b] = im[a] - ti
                        re[a] += tr; im[a] += ti
                    }
                    start += size
                }
                size *= 2
            }
        }
    }
}

/**
 * TCL 原始帧 → 消掉节目声 → 放大 → 16 位单声道（一个语音会话一份，滤波器跨采音分段保留）。
 * 参考通道对不上时整段会话退回只取第 0 路（与之前一致）。
 */
internal class TclEchoPipeline(private val gain: Int, private val canceller: TvEchoCanceller = TvEchoCanceller()) {
    private val micBlock = FloatArray(TvEchoCanceller.BLOCK)
    private val refBlock = FloatArray(TvEchoCanceller.BLOCK)
    private var filled = 0
    // 每秒一条电平诊断：节目参考声、麦克风、消除后（验收和排查用）。
    private var refEnergy = 0.0
    private var micEnergy = 0.0
    private var outEnergy = 0.0
    private var levelSamples = 0

    /** null = 还没收到数据；true = 在消回声；false = 没有参考，退回原样。 */
    var echoCancelling: Boolean? = null
        private set

    fun process(raw: ByteArray): ByteArray {
        val (mic, ref) = TclWavPcm.micAndReference(raw)
        if (echoCancelling == null) echoCancelling = ref != null
        if (echoCancelling != true || ref == null) return TclWavPcm.channelZero(raw, gain)
        val out = java.io.ByteArrayOutputStream(mic.size * 2)
        for (i in mic.indices) {
            micBlock[filled] = mic[i]; refBlock[filled] = ref[i]
            if (++filled < TvEchoCanceller.BLOCK) continue
            filled = 0
            for (k in 0 until TvEchoCanceller.BLOCK) {
                refEnergy += (refBlock[k] * refBlock[k]).toDouble(); micEnergy += (micBlock[k] * micBlock[k]).toDouble()
            }
            for (sample in canceller.process(micBlock, refBlock)) {
                outEnergy += (sample * sample).toDouble()
                val value = (sample * 32768f * gain).toInt().coerceIn(-32768, 32767)
                out.write(value and 255); out.write((value shr 8) and 255)
            }
            levelSamples += TvEchoCanceller.BLOCK
            if (levelSamples >= LEVEL_WINDOW) reportLevels()
        }
        return out.toByteArray()
    }

    private fun reportLevels() {
        fun db(energy: Double) = if (energy <= 0.0) -120.0 else Math.round(100 * Math.log10(energy / levelSamples)) / 10.0
        io.github.fartown.movo.diagnostics.MemoryDiagnostics.record("tv.voice", "echo.level", fields = mapOf(
            "ref_db" to db(refEnergy), "mic_db" to db(micEnergy), "out_db" to db(outEnergy)))
        refEnergy = 0.0; micEnergy = 0.0; outEnergy = 0.0; levelSamples = 0
    }

    private companion object {
        /** 16 kHz 下 1 秒。 */
        const val LEVEL_WINDOW = 16_000
    }
}
