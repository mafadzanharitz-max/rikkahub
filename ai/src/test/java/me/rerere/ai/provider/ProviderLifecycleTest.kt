package me.rerere.ai.provider

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ProviderLifecycleTest {
    @Test
    fun `Google model listing propagates HTTP errors and closes response body`() = runBlocking {
        val closed = AtomicBoolean(false)
        val provider = GoogleProvider(clientReturningError(closed))
        val failure = runCatching {
            provider.listModels(ProviderSetting.Google(apiKey = "test-key"))
        }.exceptionOrNull()

        assertTrue("Expected ProviderHttpException, got $failure", failure is ProviderHttpException)
        assertEquals(401, (failure as ProviderHttpException).statusCode)
        assertTrue("Google response body must be closed on HTTP failure", closed.get())
    }

    @Test
    fun `Claude model listing propagates HTTP errors and closes response body`() = runBlocking {
        val closed = AtomicBoolean(false)
        val provider = ClaudeProvider(clientReturningError(closed))
        val failure = runCatching {
            provider.listModels(ProviderSetting.Claude(apiKey = "test-key"))
        }.exceptionOrNull()

        assertTrue("Expected ProviderHttpException, got $failure", failure is ProviderHttpException)
        assertEquals(401, (failure as ProviderHttpException).statusCode)
        assertTrue("Claude response body must be closed on HTTP failure", closed.get())
    }

    private fun clientReturningError(closed: AtomicBoolean): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body(TrackingResponseBody(closed))
                    .build()
            }
            .build()

    private class TrackingResponseBody(
        private val closed: AtomicBoolean,
    ) : ResponseBody() {
        private val delegate = "redacted".toResponseBody("application/json".toMediaType())

        override fun contentType() = delegate.contentType()

        override fun contentLength() = delegate.contentLength()

        override fun source(): BufferedSource =
            object : ForwardingSource(delegate.source()) {
                override fun close() {
                    closed.set(true)
                    super.close()
                }
            }.buffer()
    }
}