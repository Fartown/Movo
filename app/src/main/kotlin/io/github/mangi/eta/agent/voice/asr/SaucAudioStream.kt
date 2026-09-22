package io.github.mangi.eta.agent.voice.asr

/** Serializes sequence allocation with writes, including the terminal frame. */
internal class SaucAudioStream(private val send: (ByteArray) -> Boolean) {
    private var nextSequence = 1
    private var started = false
    private var ended = false

    @Synchronized
    fun start(): Boolean {
        if (ended) return false
        if (started) return true
        started = send(DoubaoSaucProtocol.encodeFullClientRequest(
            DoubaoSaucProtocol.defaultFullClientJson(), nextSequence++,
        ))
        if (!started) ended = true
        return started
    }

    @Synchronized
    fun audio(pcm: ByteArray): Boolean {
        if (ended) return true // A read already in flight may finish after stop/cancel.
        if (!started) return false
        return send(DoubaoSaucProtocol.encodeAudioOnlyRequest(pcm, nextSequence++, false))
    }

    @Synchronized
    fun finish(): Boolean {
        if (ended) return true
        ended = true
        if (!started) return false
        return send(DoubaoSaucProtocol.encodeAudioOnlyRequest(ByteArray(0), nextSequence++, true))
    }

    @Synchronized
    fun cancel() { ended = true }
}
