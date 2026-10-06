package io.github.fartown.movo.agent.voice.conversation

/** Bounded connection-time audio. Overflow rejects the session instead of dropping a command's
 * first words. Used only by external far-field capture, never the SDK microphone path. */
internal class PcmWarmupBuffer(private val maxBytes: Int) {
    private val packets = ArrayDeque<ByteArray>()
    private var size = 0
    fun append(bytes: ByteArray): Boolean {
        if (bytes.size > maxBytes - size) return false
        packets += bytes.copyOf(); size += bytes.size
        return true
    }
    fun drain(): List<ByteArray> = packets.toList().also { clear() }
    fun clear() { packets.clear(); size = 0 }
}
