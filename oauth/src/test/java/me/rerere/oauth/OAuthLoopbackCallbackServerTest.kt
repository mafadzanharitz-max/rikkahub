package me.rerere.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class OAuthLoopbackCallbackServerTest {
    private val httpClient = OkHttpClient()

    @Test
    fun `callback is delivered only to matching state`() = runBlocking {
        val server = OAuthLoopbackCallbackServer()
        val session = server.openSession("expected-state")
        try {
            assertTrue(session.redirectUri.startsWith("http://localhost:"))
            execute("${session.redirectUri}?code=wrong-code&state=wrong-state").use { response ->
                assertEquals(400, response.code)
            }

            val callbackAndClose = async(Dispatchers.IO) {
                session.awaitCallback(2.seconds).also { session.close() }
            }
            withContext(Dispatchers.IO) {
                execute(
                    "${session.redirectUri}?code=auth-code&state=expected-state" +
                        "&client_id=oaiapp-issued&scope=openid%20profile&iss=https%3A%2F%2Fauth.example.com"
                ).use { response ->
                    assertEquals(200, response.code)
                    assertTrue(response.body.string().contains("Authorization complete"))
                }
            }

            val callback = callbackAndClose.await()
            assertEquals("auth-code", callback?.code)
            assertEquals("expected-state", callback?.state)
            assertEquals("oaiapp-issued", callback?.clientId)
            assertEquals("openid profile", callback?.scope)
            assertEquals("https://auth.example.com", callback?.issuer)
            assertFalse(callback?.error != null)
        } finally {
            session.close()
        }
    }

    private fun execute(url: String) = httpClient.newCall(
        Request.Builder()
            .url(url)
            .build()
    ).execute()
}
