package io.github.fartown.movo.agent.tools.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 从短信/通知文本抽验证码的纯逻辑单测：无 Root 走通知历史时复用同一函数。 */
class PersonalOtpExtractionTest {

    @Test
    fun extractsCodeNearChineseContextWord() {
        assertEquals("123456", PersonalSecretPatterns.extractOtp("您的验证码是 123456，请勿泄露"))
    }

    @Test
    fun extractsCodeNearEnglishContextWord() {
        assertEquals("4821", PersonalSecretPatterns.extractOtp("Your verification code is 4821."))
        assertEquals("5566", PersonalSecretPatterns.extractOtp("OTP 5566"))
    }

    @Test
    fun returnsNullWithoutContextWord() {
        assertNull(PersonalSecretPatterns.extractOtp("订单 123456 已发货"))
    }

    @Test
    fun picksCodeClosestToContextAndIgnoresLongNumbers() {
        // 11 位手机号不构成 4–8 位验证码（前后不接别的数字），只会命中 8888。
        assertEquals("8888", PersonalSecretPatterns.extractOtp("验证码 8888，手机 13800138000"))
    }

    @Test
    fun handlesNullAndBlank() {
        assertNull(PersonalSecretPatterns.extractOtp(null))
        assertNull(PersonalSecretPatterns.extractOtp(""))
    }
}
