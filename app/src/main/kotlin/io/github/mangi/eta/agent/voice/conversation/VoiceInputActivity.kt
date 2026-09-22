package io.github.mangi.eta.agent.voice.conversation

/** Only an early veto of turn submission; cloud ASR remains the authority on speech and text.
 * Fixed 20 ms windows make the result independent of native callback buffer sizes. No PCM retained.
 */
internal class VoiceInputActivity {
    private var samples = 0
    private var squareSum = 0L
    private var peak = 0
    private var activeFrames = 0
    private var lastEmittedAt = Long.MIN_VALUE

    fun accept(pcm: ByteArray, size: Int = pcm.size, now: Long): Boolean {
        var sustained = false
        for (i in 0 until size.coerceAtMost(pcm.size) - 1 step 2) {
            val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            squareSum += sample.toLong() * sample
            peak = maxOf(peak, kotlin.math.abs(sample))
            if (++samples == 320) {
                activeFrames = if (squareSum >= 320L * 180 * 180 && peak >= 600) activeFrames + 1 else 0
                sustained = sustained || activeFrames >= 2
                samples = 0
                squareSum = 0
                peak = 0
            }
        }
        if (!sustained || (lastEmittedAt != Long.MIN_VALUE && now - lastEmittedAt < 100)) return false
        lastEmittedAt = now
        return true
    }
}

/** Do not commit a half sentence while locally observed audio is waiting for a cloud ASR round. */
internal class VoiceCommitGate {
    enum class Decision { WAIT, COMMIT, EXPIRE }
    private var endpointAt = 0L
    private var awaitingRecognition = false
    private var latestActivity = Long.MIN_VALUE

    fun activity(at: Long) { latestActivity = maxOf(latestActivity, at) }
    fun endpoint(at: Long) { endpointAt = at; awaitingRecognition = false }
    fun decision(now: Long): Decision {
        // Include sound just before a delayed endpoint event reaches the main thread.
        if (latestActivity >= endpointAt - 150) awaitingRecognition = true
        return when {
            awaitingRecognition && now - endpointAt >= 6_000 -> Decision.EXPIRE
            awaitingRecognition || now - endpointAt < 650 -> Decision.WAIT
            else -> Decision.COMMIT
        }
    }
}
