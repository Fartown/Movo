package io.github.fartown.movo.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityRequestTest {

    @Test
    fun chineseLinesJoinWithoutSpace() {
        assertEquals("打开淘宝找一个知名品牌的平价户外椅子", capabilityRequest("打开淘宝\n找一个知名品牌的平价户外椅子"))
        assertEquals("这页在讲什么？帮我总结一下", capabilityRequest("这页在讲什么？\n帮我总结一下"))
    }

    @Test
    fun digitOrLatinNextToBreakKeepsSpace() {
        assertEquals("明早 7:30 叫我起床", capabilityRequest("明早 7:30\n叫我起床"))
        assertEquals("Wake me up at 7:30 tomorrow", capabilityRequest("Wake me up\nat 7:30 tomorrow"))
    }

    @Test
    fun singleLineIsUnchanged() {
        assertEquals("打开抖音每 10 秒下滑一次视频", capabilityRequest("打开抖音每 10 秒下滑一次视频"))
    }
}
