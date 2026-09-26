package io.github.fartown.movo.agent.runtime

import java.io.File
import java.security.MessageDigest

/** Durable admission receipt, written before executing voice side effects. Contains no prompt or key.
 * A replay after process death is reconciled; it is never silently executed again.
 */
internal class VoiceRunReceiptStore(private val directory: File) {
    fun claim(runId: String): Boolean {
        require(runId.isNotBlank())
        check(directory.isDirectory || directory.mkdirs()) { "无法记录语音任务，请检查存储空间" }
        val name = MessageDigest.getInstance("SHA-256").digest(runId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$name.accepted").createNewFile()
    }
}

internal class DuplicateVoiceRunException : IllegalStateException()
