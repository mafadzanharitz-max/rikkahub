package me.rerere.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHttpExceptionTest {
    @Test
    fun `authentication failures never qualify for fallback`() {
        listOf(401, 403).forEach { status ->
            val error = ProviderHttpException(status)
            assertEquals(ProviderHttpException.Kind.AUTHENTICATION, error.kind)
            assertFalse(error.retryEligible)
            assertFalse(error.fallbackEligible)
        }
    }

    @Test
    fun `rate limits are kept distinct from transient server failures`() {
        listOf(402, 429).forEach { status ->
            val error = ProviderHttpException(status)
            assertEquals(ProviderHttpException.Kind.RATE_LIMITED, error.kind)
            assertFalse(error.fallbackEligible)
        }
    }

    @Test
    fun `transient HTTP failures qualify for router consideration`() {
        listOf(408, 425, 500, 502, 503, 504).forEach { status ->
            val error = ProviderHttpException(status)
            assertEquals(ProviderHttpException.Kind.TRANSIENT_SERVER, error.kind)
            assertTrue(error.retryEligible)
            assertTrue(error.fallbackEligible)
        }
    }

    @Test
    fun `ordinary client errors do not qualify for fallback`() {
        listOf(400, 404, 405, 413, 422).forEach { status ->
            val error = ProviderHttpException(status)
            assertEquals(ProviderHttpException.Kind.INVALID_REQUEST, error.kind)
            assertFalse(error.fallbackEligible)
        }
    }

    @Test
    fun `error message does not include response payload or credentials`() {
        val error = ProviderHttpException(401, IllegalStateException("secret response payload"))
        assertEquals("Provider request failed (HTTP 401)", error.message)
    }
}
