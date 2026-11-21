package me.rerere.rikkahub.data.ai

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestLogRedactorTest {
    @Test
    fun `redacts credentials and cookies from headers`() {
        val headers = Headers.Builder()
            .add("Authorization", "Bearer access-secret")
            .add("X-API-Key", "provider-secret")
            .add("Cookie", "session=session-secret")
            .add("X-Client-Secret", "client-secret")
            .add("Content-Type", "application/json")
            .build()

        val redacted = RequestLogRedactor.redactHeaders(headers)

        assertEquals(RequestLogRedactor.REDACTED, redacted["Authorization"])
        assertEquals(RequestLogRedactor.REDACTED, redacted["X-API-Key"])
        assertEquals(RequestLogRedactor.REDACTED, redacted["Cookie"])
        assertEquals(RequestLogRedactor.REDACTED, redacted["X-Client-Secret"])
        assertEquals("application/json", redacted["Content-Type"])
        assertFalse(redacted.values.any { it.contains("secret") })
    }

    @Test
    fun `redacts user info and sensitive query parameters`() {
        val url = "https://user:password@example.com/v1/chat?api_key=api-secret&access_token=token-secret&key=key-secret&X-Amz-Signature=signature-secret&session_id=session-secret&model=gpt"
            .toHttpUrl()

        val redacted = RequestLogRedactor.redactUrl(url).toHttpUrl()

        assertEquals("", redacted.username)
        assertEquals("", redacted.password)
        assertEquals(RequestLogRedactor.REDACTED, redacted.queryParameter("api_key"))
        assertEquals(RequestLogRedactor.REDACTED, redacted.queryParameter("access_token"))
        assertEquals(RequestLogRedactor.REDACTED, redacted.queryParameter("key"))
        assertEquals(RequestLogRedactor.REDACTED, redacted.queryParameter("X-Amz-Signature"))
        assertEquals(RequestLogRedactor.REDACTED, redacted.queryParameter("session_id"))
        assertEquals("gpt", redacted.queryParameter("model"))
        assertFalse(redacted.toString().contains("password"))
        assertFalse(redacted.toString().contains("api-secret"))
        assertFalse(redacted.toString().contains("token-secret"))
        assertFalse(redacted.toString().contains("key-secret"))
        assertFalse(redacted.toString().contains("signature-secret"))
        assertFalse(redacted.toString().contains("session-secret"))
    }

    @Test
    fun `request body logging uses a fixed omission marker`() {
        assertTrue(RequestLogRedactor.REDACTED_BODY.contains("omitted"))
        assertFalse(RequestLogRedactor.REDACTED_BODY.contains("conversation"))
    }
}
