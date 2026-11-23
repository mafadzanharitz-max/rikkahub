package me.rerere.common.http

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CallAwaitCancellationTest {
    @Test
    fun `cancelling awaiting coroutine cancels underlying call`() = runBlocking {
        val interceptorStarted = CountDownLatch(1)
        val releaseInterceptor = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                interceptorStarted.countDown()
                releaseInterceptor.await(5, TimeUnit.SECONDS)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("ok".toResponseBody())
                    .build()
            }
            .build()
        val call = client.newCall(
            Request.Builder()
                .url("https://example.invalid/")
                .build()
        )

        val awaiting = launch {
            call.await().close()
        }

        try {
            assertTrue(interceptorStarted.await(5, TimeUnit.SECONDS))
            awaiting.cancelAndJoin()
            assertTrue("Coroutine cancellation must cancel the OkHttp call", call.isCanceled())
        } finally {
            releaseInterceptor.countDown()
            awaiting.cancelAndJoin()
            client.dispatcher.executorService.shutdown()
        }
    }
}