package io.github.fartown.movo.tv

import java.io.RandomAccessFile

/** Incremental reader for TCL's growing PCM32 six-channel WAV, not a whole-file decoder. */
internal class TclWavPcm(private val file: RandomAccessFile, private val gain: Int = 1, private val raw: Boolean = false) {
    private var dataOffset = if (raw) 0L else -1L
    private var position = dataOffset
    private var rawValidated = false

    fun read(maxFrames: Int = 3200): ByteArray? {
        require(maxFrames > 0)
        if (dataOffset < 0 && !header()) return null
        check(file.length() >= position) { "TCL 采音文件被截断，已停止读取" }
        val available = (file.length() - position) / FRAME_BYTES
        val frames = minOf(available, maxFrames.toLong()).toInt()
        if (frames == 0) return null
        val raw = ByteArray(frames * FRAME_BYTES)
        file.seek(position)
        file.readFully(raw)
        if (this.raw && !rawValidated) {
            // Verified firmware emits per-channel tags in the low 16 bits. Reject changed ABI.
            for (offset in raw.indices step FRAME_BYTES) {
                check(raw[offset] == 0.toByte() && raw[offset + 1] == 1.toByte() &&
                    raw[offset + 4] == 0.toByte() && raw[offset + 5] == 2.toByte()) { "TCL 原始采音格式已变化" }
            }
            rawValidated = true
        }
        position += raw.size
        return channelZero(raw, gain)
    }

    private fun header(): Boolean {
        if (file.length() < 12) return false
        file.seek(0)
        check(file.readInt() == 0x52494646) { "采音文件不是 RIFF" }
        file.skipBytes(4)
        check(file.readInt() == 0x57415645) { "采音文件不是 WAV" }
        var validFormat = false
        while (file.filePointer + 8 <= file.length()) {
            val tag = file.readInt()
            val size = Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()))
            val begin = file.filePointer
            if (tag == 0x64617461) {
                check(validFormat) { "TCL 采音格式未通过校验" }
                dataOffset = begin
                position = begin
                return true
            }
            if (size > file.length() - begin) return false
            if (tag == 0x666d7420) {
                check(size >= 16) { "无效 WAV 格式头" }
                val format = java.lang.Short.reverseBytes(file.readShort()).toInt() and 65535
                val channels = java.lang.Short.reverseBytes(file.readShort()).toInt() and 65535
                val rate = Integer.reverseBytes(file.readInt())
                file.skipBytes(6)
                val bits = java.lang.Short.reverseBytes(file.readShort()).toInt() and 65535
                check(format == 1 && channels == 6 && rate == 16000 && bits == 32) {
                    "不支持的 TCL 采音格式：$format/$channels/$rate/$bits"
                }
                validFormat = true
            }
            file.seek(begin + size + (size and 1))
        }
        return false
    }

    companion object {
        const val FRAME_BYTES = 24
        fun channelZero(raw: ByteArray, gain: Int = 1): ByteArray {
            require(gain in 1..64)
            require(raw.size % FRAME_BYTES == 0)
            return ByteArray(raw.size / FRAME_BYTES * 2).also { pcm ->
                for (frame in 0 until raw.size / FRAME_BYTES) {
                    // Low 16 bits contain vendor channel tags, not microphone energy.
                    val offset = frame * FRAME_BYTES
                    val sample = ((raw[offset + 2].toInt() and 255) or (raw[offset + 3].toInt() shl 8)).toShort().toInt()
                    val amplified = (sample * gain).coerceIn(-32768, 32767)
                    pcm[frame * 2] = amplified.toByte()
                    pcm[frame * 2 + 1] = (amplified shr 8).toByte()
                }
            }
        }
    }
}
