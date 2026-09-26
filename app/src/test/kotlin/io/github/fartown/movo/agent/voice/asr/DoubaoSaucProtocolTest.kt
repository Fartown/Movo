package io.github.fartown.movo.agent.voice.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubaoSaucProtocolTest {
    @Test
    fun gzipRoundTrip() {
        val raw = "hello-豆包".toByteArray(Charsets.UTF_8)
        val compressed = DoubaoSaucProtocol.gzip(raw)
        assertTrue(compressed.isNotEmpty())
        assertEquals("hello-豆包", DoubaoSaucProtocol.gunzip(compressed).toString(Charsets.UTF_8))
    }

    @Test
    fun encodeFullClientRequest_hasExpectedHeaderAndSequence() {
        val frame = DoubaoSaucProtocol.encodeFullClientRequest(
            jsonPayload = """{"audio":{"format":"pcm"},"request":{"model_name":"bigmodel"}}""",
            sequence = 1,
        )
        assertEquals(0x11.toByte(), frame[0])
        assertEquals(0x11.toByte(), frame[1]) // full client + pos sequence
        assertEquals(0x11.toByte(), frame[2]) // json + gzip
        val sequence = ((frame[4].toInt() and 0xff) shl 24) or
            ((frame[5].toInt() and 0xff) shl 16) or
            ((frame[6].toInt() and 0xff) shl 8) or
            (frame[7].toInt() and 0xff)
        assertEquals(1, sequence)
    }

    @Test
    fun encodeAudioOnly_finalUsesNegativeSequence() {
        val frame = DoubaoSaucProtocol.encodeAudioOnlyRequest(
            pcm = ByteArray(4) { 1 },
            sequence = 7,
            isLast = true,
        )
        assertEquals(0x23.toByte(), frame[1]) // audio + neg with sequence
        val sequence = ((frame[4].toInt() and 0xff) shl 24) or
            ((frame[5].toInt() and 0xff) shl 16) or
            ((frame[6].toInt() and 0xff) shl 8) or
            (frame[7].toInt() and 0xff)
        assertEquals(-7, sequence)
    }

    @Test
    fun parseRecognitionJson_readsTextAndDefinite() {
        val json = """
            {
              "result": {
                "text": "小王同学你好",
                "utterances": [
                  {"text": "小王同学", "definite": true, "start_time": 0, "end_time": 800},
                  {"text": "你好", "definite": false, "start_time": 800, "end_time": 1200}
                ]
              }
            }
        """.trimIndent()
        val result = DoubaoSaucProtocol.parseRecognitionJson(json)
        assertEquals("小王同学你好", result.text)
        assertTrue(result.hasDefinite)
        assertEquals(2, result.utterances.size)
        assertTrue(result.utterances[0].definite)
        assertFalse(result.utterances[1].definite)
    }

    @Test
    fun parseServerFrame_responseRoundTrip() {
        val payloadJson = """{"result":{"text":"你好","utterances":[{"text":"你好","definite":true}]}}"""
        val payload = DoubaoSaucProtocol.gzip(payloadJson.toByteArray(Charsets.UTF_8))
        val frame = DoubaoSaucProtocol.encodeFrame(
            messageType = DoubaoSaucProtocol.MSG_FULL_SERVER_RESPONSE,
            flags = DoubaoSaucProtocol.FLAG_POS_SEQUENCE,
            serialization = DoubaoSaucProtocol.SERIAL_JSON,
            compression = DoubaoSaucProtocol.COMPRESS_GZIP,
            sequence = 2,
            payload = payload,
        )
        val parsed = DoubaoSaucProtocol.parseServerFrame(frame)
        check(parsed is SaucServerFrame.Response)
        assertEquals("你好", parsed.result.text)
        assertTrue(parsed.result.hasDefinite)
        assertEquals(2, parsed.sequence)
    }

    @Test
    fun packetBytes_isAbout200ms() {
        // 16000 Hz * 2 bytes * 0.2 s = 6400
        assertEquals(6400, DoubaoSaucProtocol.PACKET_BYTES)
    }
}
