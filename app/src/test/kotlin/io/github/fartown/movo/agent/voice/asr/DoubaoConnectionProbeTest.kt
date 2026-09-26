package io.github.fartown.movo.agent.voice.asr

import io.github.fartown.movo.data.model.DoubaoCredentialRules
import io.github.fartown.movo.data.model.DoubaoSpeechCredentials
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class DoubaoConnectionProbeTest {
    @Test fun serverErrorIsNeverReportedAsConnected() {
        val message = "invalid resource".toByteArray()
        val frame = ByteBuffer.allocate(12 + message.size)
            .put(byteArrayOf(0x11, 0xf0.toByte(), 0, 0)).putInt(45000000)
            .putInt(message.size).put(message).array()
        assertEquals(DoubaoConnectionProbe.Result.Rejected(45000000), DoubaoConnectionProbe.classify(frame))
    }

    @Test fun acknowledgementDoesNotFinishUntilFinalResponse() {
        fun response(last: Boolean) = DoubaoSaucProtocol.encodeFrame(
            messageType = DoubaoSaucProtocol.MSG_FULL_SERVER_RESPONSE,
            flags = if (last) DoubaoSaucProtocol.FLAG_NEG_WITH_SEQUENCE else DoubaoSaucProtocol.FLAG_POS_SEQUENCE,
            serialization = DoubaoSaucProtocol.SERIAL_JSON,
            compression = DoubaoSaucProtocol.COMPRESS_NONE,
            sequence = if (last) -2 else 1,
            payload = """{"result":{"text":""}}""".toByteArray(),
        )
        assertNull(DoubaoConnectionProbe.classify(response(false)))
        assertEquals(DoubaoConnectionProbe.Result.Success, DoubaoConnectionProbe.classify(response(true)))
    }

    @Test fun malformedFrameFailsInsteadOfClaimingSuccess() {
        assertEquals(DoubaoConnectionProbe.Result.InvalidResponse, DoubaoConnectionProbe.classify(byteArrayOf(1)))
    }

    @Test fun multilineCredentialIsRejectedBeforeHttpHeaderConstruction() {
        assertTrue(DoubaoCredentialRules.validate(DoubaoSpeechCredentials(apiKey = "first\nsecond")) is DoubaoCredentialRules.Validation.Invalid)
    }
}
