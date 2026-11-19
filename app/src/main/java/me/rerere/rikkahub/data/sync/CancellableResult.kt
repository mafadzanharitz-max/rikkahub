package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.CancellationException

/**
 * Converts ordinary operation failures to Result while preserving structured cancellation.
 * Kotlin's standard runCatching catches CancellationException as well, which can leave callers
 * believing a cancelled network operation completed normally.
 */
internal suspend inline fun <T> runCatchingCancellable(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
