package io.github.fartown.movo.agent.voice.wake

import io.github.fartown.movo.agent.voice.wake.WakeEnergyGate.Action
import org.junit.Assert.*
import org.junit.Test

class WakeEnergyGateTest {
    private val size = 1_600

    // Constant amplitude a has RMS a: 1 → -90 dBFS, 10 → -70, 20 → -64, 1000 → -30.
    private fun chunk(amplitude: Int, marker: Short = 0): ShortArray =
        ShortArray(size) { amplitude.toShort() }.also { it[0] = marker }

    private fun WakeEnergyGate.offer(chunk: ShortArray) = offer(chunk, chunk.size)

    @Test fun silenceNeverReachesTheModel() {
        val gate = WakeEnergyGate(size)
        repeat(600) { assertEquals(Action.Skip, gate.offer(chunk(10))) }
        assertFalse(gate.isOpen)
    }

    @Test fun speechOpensTheGateAndReplaysPreRollOldestFirst() {
        val gate = WakeEnergyGate(size, preRollChunks = 3)
        repeat(20) { assertEquals(Action.Skip, gate.offer(chunk(10))) }
        for (marker in 1..5) gate.offer(chunk(10, marker.toShort()))

        assertEquals(Action.Opened, gate.offer(chunk(1_000)))
        val replayed = mutableListOf<Short>()
        gate.drainPreRoll { samples, length ->
            assertEquals(size, length)
            replayed += samples[0]
        }
        assertEquals(listOf<Short>(3, 4, 5), replayed)

        var drainedAgain = 0
        gate.drainPreRoll { _, _ -> drainedAgain++ }
        assertEquals(0, drainedAgain)
        assertEquals(Action.Feed, gate.offer(chunk(1_000)))
    }

    @Test fun gateStaysOpenThroughHangoverThenCloses() {
        val gate = WakeEnergyGate(size, hangoverChunks = 4)
        repeat(20) { gate.offer(chunk(10)) }
        assertEquals(Action.Opened, gate.offer(chunk(1_000)))
        repeat(4) { assertEquals(Action.Feed, gate.offer(chunk(10))) }
        assertEquals(Action.Closed, gate.offer(chunk(10)))
        assertFalse(gate.isOpen)
        assertEquals(Action.Skip, gate.offer(chunk(10)))
    }

    @Test fun speechDuringHangoverKeepsTheGateOpen() {
        val gate = WakeEnergyGate(size, hangoverChunks = 4)
        repeat(20) { gate.offer(chunk(10)) }
        gate.offer(chunk(1_000))
        repeat(3) { gate.offer(chunk(10)) }
        assertEquals(Action.Feed, gate.offer(chunk(1_000)))
        repeat(4) { assertEquals(Action.Feed, gate.offer(chunk(10))) }
        assertEquals(Action.Closed, gate.offer(chunk(10)))
    }

    @Test fun steadyModerateNoiseRaisesTheFloorUntilTheGateCloses() {
        val gate = WakeEnergyGate(size)
        repeat(20) { gate.offer(chunk(3)) }
        assertEquals(Action.Opened, gate.offer(chunk(30)))
        // -81 → -61 dBFS, 6 dB margin, 0.1 dB rise per 100 ms chunk: about 14 s, plus hangover.
        val actions = List(600) { gate.offer(chunk(30)) }
        assertTrue(Action.Closed in actions)
        assertEquals(Action.Skip, actions.last())
        assertFalse(gate.isOpen)
        assertTrue(gate.noiseFloorDb < -50.0)
    }

    @Test fun loudEnvironmentKeepsEveryChunkFlowingToTheModel() {
        val gate = WakeEnergyGate(size)
        repeat(20) { gate.offer(chunk(10)) }
        assertEquals(Action.Opened, gate.offer(chunk(1_000)))
        // Speech in -30 dBFS noise is only a few dB louder, so the gate must not close there.
        val actions = List(1_000) { gate.offer(chunk(1_000)) }
        assertTrue(actions.all { it == Action.Feed })
        assertTrue(gate.noiseFloorDb >= -50.0)
    }

    @Test fun gatingResumesWhenALoudEnvironmentTurnsQuiet() {
        val gate = WakeEnergyGate(size, hangoverChunks = 20)
        repeat(20) { gate.offer(chunk(10)) }
        gate.offer(chunk(1_000))
        repeat(400) { gate.offer(chunk(1_000)) }
        val actions = List(30) { gate.offer(chunk(10)) }
        assertEquals(Action.Closed, actions.firstOrNull { it != Action.Feed })
        assertTrue(actions.indexOf(Action.Closed) <= 25)
        assertEquals(Action.Skip, actions.last())
    }

    @Test fun faintSoundsBelowAbsoluteMinimumStayGated() {
        val gate = WakeEnergyGate(size)
        repeat(20) { gate.offer(chunk(1)) }
        // -64 dBFS is 26 dB above this floor, but below the -62 dBFS opening minimum.
        repeat(5) { assertEquals(Action.Skip, gate.offer(chunk(20))) }
    }

    @Test fun levelMatchesRmsInDbfs() {
        assertEquals(-30.3, WakeEnergyGate.levelDb(chunk(1_000), size), 0.1)
        assertEquals(-100.0, WakeEnergyGate.levelDb(ShortArray(size), size), 0.0)
        assertEquals(-100.0, WakeEnergyGate.levelDb(ShortArray(0), 0), 0.0)
    }
}
