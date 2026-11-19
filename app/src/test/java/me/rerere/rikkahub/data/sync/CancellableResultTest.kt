package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableResultTest {
    @Test
    fun successfulOperationReturnsSuccess() = runBlocking {
        val result = runCatchingCancellable { "ok" }
        assertEquals("ok", result.getOrNull())
    }

    @Test
    fun ordinaryFailureReturnsFailure() = runBlocking {
        val failure = IllegalStateException("network failed")
        val result = runCatchingCancellable<String> { throw failure }
        assertSame(failure, result.exceptionOrNull())
    }

    @Test
    fun cancellationIsRethrown() = runBlocking {
        val cancellation = CancellationException("cancelled")
        try {
            runCatchingCancellable<String> { throw cancellation }
            throw AssertionError("CancellationException was swallowed")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }
}
