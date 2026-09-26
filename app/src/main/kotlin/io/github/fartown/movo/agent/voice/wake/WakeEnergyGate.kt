package io.github.fartown.movo.agent.voice.wake

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Decides which microphone chunks reach KWS. Quiet audio skips model inference and only
 * stays in a short pre-roll; once a chunk rises clearly above the adaptive noise floor the
 * gate opens and the pre-roll is replayed first so the start of the wake phrase survives.
 * In loud environments speech barely rises above the floor, so the gate stays open there.
 * The hangover must outlast the KWS emission delay (about 1.3 s after the phrase peak).
 * Pure logic, no Android dependency.
 */
internal class WakeEnergyGate(
    chunkCapacity: Int,
    private val preRollChunks: Int = 10,
    private val hangoverChunks: Int = 20,
    private val marginDb: Double = 6.0,
    private val minOpenDb: Double = -62.0,
    private val floorRiseDb: Double = 0.1,
    private val bypassFloorDb: Double = -50.0,
) {
    enum class Action { Skip, Opened, Feed, Closed }

    private val ring = Array(preRollChunks) { ShortArray(chunkCapacity) }
    private val ringLengths = IntArray(preRollChunks)
    private var ringStart = 0
    private var ringSize = 0
    private var floorDb = Double.NaN
    private var quietChunks = 0
    var isOpen = false
        private set

    /** Adaptive noise floor in dBFS; NaN before the first chunk. */
    val noiseFloorDb: Double
        get() = floorDb

    init {
        require(chunkCapacity > 0 && preRollChunks > 0 && hangoverChunks >= 0)
    }

    /** After [Action.Opened], call [drainPreRoll] before feeding [samples]. */
    fun offer(samples: ShortArray, count: Int): Action {
        val level = levelDb(samples, count)
        val loud = !floorDb.isNaN() &&
            (floorDb >= bypassFloorDb || level >= max(floorDb + marginDb, minOpenDb))
        trackFloor(level)
        if (isOpen) {
            quietChunks = if (loud) 0 else quietChunks + 1
            if (quietChunks <= hangoverChunks) return Action.Feed
            isOpen = false
            remember(samples, count)
            return Action.Closed
        }
        if (!loud) {
            remember(samples, count)
            return Action.Skip
        }
        isOpen = true
        quietChunks = 0
        return Action.Opened
    }

    /** Hands out buffered chunks oldest first, then clears them. Arrays are reused afterwards. */
    fun drainPreRoll(consumer: (ShortArray, Int) -> Unit) {
        repeat(ringSize) { index ->
            val slot = (ringStart + index) % preRollChunks
            consumer(ring[slot], ringLengths[slot])
        }
        ringStart = 0
        ringSize = 0
    }

    // Falls quickly toward quieter audio and rises slowly, so steady moderate noise eventually
    // closes the gate while short speech barely moves the floor.
    private fun trackFloor(level: Double) {
        floorDb = when {
            floorDb.isNaN() -> level
            level < floorDb -> floorDb + (level - floorDb) * 0.5
            else -> floorDb + min(level - floorDb, floorRiseDb)
        }
    }

    private fun remember(samples: ShortArray, count: Int) {
        val slot: Int
        if (ringSize == preRollChunks) {
            slot = ringStart
            ringStart = (ringStart + 1) % preRollChunks
        } else {
            slot = (ringStart + ringSize) % preRollChunks
            ringSize++
        }
        val length = min(count, ring[slot].size)
        samples.copyInto(ring[slot], 0, 0, length)
        ringLengths[slot] = length
    }

    companion object {
        private const val SILENCE_DB = -100.0

        fun levelDb(samples: ShortArray, count: Int): Double {
            if (count <= 0) return SILENCE_DB
            var sum = 0.0
            for (index in 0 until count) {
                val sample = samples[index].toDouble()
                sum += sample * sample
            }
            val rms = sqrt(sum / count) / 32_768.0
            return if (rms <= 0.0) SILENCE_DB else max(SILENCE_DB, 20 * log10(rms))
        }
    }
}
