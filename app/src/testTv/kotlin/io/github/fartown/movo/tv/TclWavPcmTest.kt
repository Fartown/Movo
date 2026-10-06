package io.github.fartown.movo.tv

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class TclWavPcmTest {
    @Test fun rawContinuousCaptureHonorsPartialFramesAndValidatesChannelTags() = withFile { file, input ->
        val raw = frames(42, -42).apply {
            for (offset in indices step 24) { this[offset] = 0; this[offset + 1] = 1; this[offset + 4] = 0; this[offset + 5] = 2 }
        }
        file.writeBytes(raw.copyOf(25))
        val reader = TclWavPcm(input, raw = true)
        assertArrayEquals(pcm(42), reader.read())
        assertNull(reader.read())
        file.appendBytes(raw.copyOfRange(25, raw.size))
        assertArrayEquals(pcm(-42), reader.read())
    }

    @Test fun rejectsUnrecognizedRawChannelTags() = withFile { file, input ->
        file.writeBytes(frames(1))
        assertThrows(IllegalStateException::class.java) { TclWavPcm(input, raw = true).read() }
    }
    @Test fun readsGrowingHeaderAndPartialFramesWithoutDuplicatingSamples() = withFile { file, input ->
        val header = header()
        file.writeBytes(header.copyOf(9))
        val reader = TclWavPcm(input)
        assertNull(reader.read())
        file.appendBytes(header.copyOfRange(9, header.size))
        assertNull(reader.read())
        val audio = frames(-32768, -1, 0, 32767)
        file.appendBytes(audio.copyOf(31))
        assertArrayEquals(pcm(-32768), reader.read())
        assertNull(reader.read())
        file.appendBytes(audio.copyOfRange(31, audio.size))
        assertArrayEquals(pcm(-1, 0), reader.read(2))
        assertArrayEquals(pcm(32767), reader.read())
        assertNull(reader.read())
    }

    @Test fun ignoresOtherChannelsAndLowWordTags() {
        val raw = frames(0, 0)
        assertArrayEquals(ByteArray(4), TclWavPcm.channelZero(raw))
    }

    @Test fun amplificationSaturatesWithoutWrappingOrAmplifyingTags() {
        assertArrayEquals(pcm(0, 1600, -1600, 32767, -32768),
            TclWavPcm.channelZero(frames(0, 100, -100, 30000, -30000), gain = 16))
    }

    @Test fun rejectsChangedFormatBeforeEmittingAudio() = withFile { file, input ->
        file.writeBytes(header(channels = 2) + frames(123))
        assertThrows(IllegalStateException::class.java) { TclWavPcm(input).read() }
    }

    @Test fun skipsPaddedUnknownChunks() = withFile { file, input ->
        val h = header()
        val junk = byteArrayOf(74, 85, 78, 75, 1, 0, 0, 0, 99, 0)
        file.writeBytes(h.copyOfRange(0, 12) + junk + h.copyOfRange(12, h.size) + frames(42))
        assertArrayEquals(pcm(42), TclWavPcm(input).read())
    }

    @Test fun rejectsTruncatedLiveFileRatherThanWaitingForMoreAudio() = withFile { file, input ->
        file.writeBytes(header() + frames(1))
        val reader = TclWavPcm(input)
        assertArrayEquals(pcm(1), reader.read())
        file.writeBytes(header())
        assertThrows(IllegalStateException::class.java) { reader.read() }
    }

    private fun header(channels: Int = 6): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(0).put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(channels.toShort()).putInt(16000).putInt(384000).putShort(24)
        .putShort(32).put("data".toByteArray()).putInt(0).array()

    private fun frames(vararg samples: Int): ByteArray = ByteBuffer.allocate(samples.size * 24)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach { sample ->
                putInt((sample shl 16) or 0x1234)
                repeat(5) { putInt(0x7fffffff) }
            }
        }.array()
    private fun pcm(vararg samples: Int): ByteArray = ByteBuffer.allocate(samples.size * 2)
        .order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it.toShort()) } }.array()
    private fun withFile(block: (File, RandomAccessFile) -> Unit) {
        val file = File.createTempFile("tcl-growing-", ".wav")
        try { RandomAccessFile(file, "r").use { block(file, it) } } finally { file.delete() }
    }
}
