package me.rerere.rikkahub.data.ai

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestLogRedactionTest {
    @Test
    fun `redacts standard and custom credential headers`() {
        val headers = Headers.headersOf(
            "Authorization", "Bearer top-secret",
            "X-Vendor-Session-Token", "session-secret",
            "Anthropic-Api-Key", "provider-secret",
            "Content-Type", "application/json",
        )

        val redacted = RequestLogRedaction.redactedMap(headers)

        assertEquals("[REDACTED]", redacted["Authorization"])
        assertEquals("[REDACTED]", redacted["X-Vendor-Session-Token"])
        assertEquals("[REDACTED]", redacted["Anthropic-Api-Key"])
        assertEquals("application/json", redacted["Content-Type"])
        assertFalse(redacted.values.any { it.contains("secret") })
    }

    @Test
    fun `redacts URL user info and removes sensitive query parameters`() {
        val url = "https://user:pass@example.com/v1/chat?api_key=key-secret&X-Session-Token=session-secret&model=test"
            .toHttpUrl()

        val redacted = RequestLogRedaction.redactedUrl(url)

        val redactedUrl = redacted.toHttpUrl()
        assertEquals("[REDACTED]", redactedUrl.username)
        assertEquals("[REDACTED]", redactedUrl.password)
        assertTrue(redactedUrl.host == "example.com")
        assertTrue(redacted.contains("model=test"))
        assertFalse(redacted.contains("user"))
        assertFalse(redacted.contains("pass"))
        assertFalse(redacted.contains("key-secret"))
        assertFalse(redacted.contains("session-secret"))
        assertFalse(redacted.contains("api_key"))
        assertFalse(redacted.contains("Session-Token"))
    }

    @Test
    fun `preserves non-sensitive URL query parameters`() {
        val url = "https://example.com/v1/models?limit=20&region=us".toHttpUrl()

        assertEquals(url.toString(), RequestLogRedaction.redactedUrl(url))
    }
}
