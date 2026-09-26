package io.github.fartown.movo.data.auth

import java.security.MessageDigest
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptOAuthTest {
    @Test
    fun pkceChallengeIsS256OfVerifier() {
        val pkce = ChatGptOAuth.createPkce()
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(pkce.verifier.toByteArray(Charsets.US_ASCII)),
        )
        assertEquals(expected, pkce.challenge)
        assertTrue(pkce.verifier.matches(Regex("[A-Za-z0-9_-]{43}")))
    }

    @Test
    fun authorizationUrlCarriesCodexParameters() {
        val pkce = ChatGptOAuth.Pkce("verifier", "challenge")
        val url = ChatGptOAuth.createAuthorizationRequest(pkce, state = "abc").url.toHttpUrl()

        assertEquals("auth.openai.com", url.host)
        assertEquals("/oauth/authorize", url.encodedPath)
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals(ChatGptOAuth.CLIENT_ID, url.queryParameter("client_id"))
        assertEquals("http://localhost:1455/auth/callback", url.queryParameter("redirect_uri"))
        assertEquals("openid profile email offline_access", url.queryParameter("scope"))
        assertEquals("challenge", url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("abc", url.queryParameter("state"))
        assertEquals("true", url.queryParameter("codex_cli_simplified_flow"))
        assertEquals(ChatGptOAuth.ORIGINATOR, url.queryParameter("originator"))
    }

    @Test
    fun manualInputAcceptsRedirectQueryHashAndBareCode() {
        val redirect = ChatGptOAuth.parseAuthorizationInput(
            "http://localhost:1455/auth/callback?code=ac%2Fx&scope=openid&state=s1",
        )
        assertEquals("ac/x", redirect.code)
        assertEquals("s1", redirect.state)

        val query = ChatGptOAuth.parseAuthorizationInput("code=c2&state=s2")
        assertEquals("c2", query.code)
        assertEquals("s2", query.state)

        val hash = ChatGptOAuth.parseAuthorizationInput("c3#s3")
        assertEquals("c3", hash.code)
        assertEquals("s3", hash.state)

        val bare = ChatGptOAuth.parseAuthorizationInput("  c4 ")
        assertEquals("c4", bare.code)
        assertNull(bare.state)
    }

    @Test
    fun jwtPayloadDecodesChatGptAccountClaim() {
        val payload = JSONObject().put(
            "https://api.openai.com/auth",
            JSONObject().put("chatgpt_account_id", "acct_1").put("chatgpt_plan_type", "plus"),
        )
        val token = listOf(
            "e30",
            Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray()),
            "sig",
        ).joinToString(".")

        val decoded = ChatGptOAuth.decodeJwtPayload(token)
        assertEquals("acct_1", decoded?.getJSONObject("https://api.openai.com/auth")?.getString("chatgpt_account_id"))
        assertNull(ChatGptOAuth.decodeJwtPayload("not-a-jwt"))
    }

    @Test
    fun manualInputWithForeignStateIsRejectedBeforeTokenExchange() {
        val session = ChatGptOAuth.LoginSession(
            ChatGptOAuth.createAuthorizationRequest(ChatGptOAuth.Pkce("v", "c"), state = "expected"),
        )
        session.submitManualInput("http://localhost:1455/auth/callback?code=x&state=other")

        val failure = assertThrows(ChatGptAuthException::class.java) { session.awaitCode(timeoutMinutes = 1) }
        assertTrue(failure.message.orEmpty().contains("状态不匹配"))
    }

    @Test
    fun cancelledLoginReleasesWaiter() {
        val session = ChatGptOAuth.LoginSession(ChatGptOAuth.createAuthorizationRequest())
        session.cancel()

        val failure = assertThrows(ChatGptAuthException::class.java) { session.awaitCode(timeoutMinutes = 1) }
        assertTrue(failure.message.orEmpty().contains("取消"))
    }
}
