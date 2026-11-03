package com.recordingcamera.core.util

import com.google.common.util.concurrent.ListenableFuture
import com.recordingcamera.core.CameraError
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val INLINE_EXECUTOR = Executor { it.run() }

/**
 * Awaits a platform [ListenableFuture] and fails with [CameraError.Timeout] when it does not
 * complete within [timeoutMillis].
 *
 * Cancelling the awaiting coroutine cancels the future, which is what we want for speculative work
 * such as a zoom step.
 */
internal suspend fun <T> ListenableFuture<T>.awaitValue(
    timeoutMillis: Long,
    operation: String,
): T {
    val value = withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            addListener({
                if (!continuation.isActive) return@addListener
                try {
                    continuation.resume(get())
                } catch (cancelled: CancellationException) {
                    continuation.cancel(cancelled)
                } catch (error: Throwable) {
                    continuation.resumeWithException(error)
                }
            }, INLINE_EXECUTOR)
            continuation.invokeOnCancellation { cancel(true) }
        }
    }
    return value ?: throw CameraError.Timeout(operation, timeoutMillis)
}

/** Fires [onSuccess]/[onFailure] when the future completes; never throws from the listener. */
internal inline fun <T> ListenableFuture<T>.onResult(
    crossinline onSuccess: (T) -> Unit,
    crossinline onFailure: (Throwable) -> Unit,
) {
    addListener(
        {
            try {
                onSuccess(get())
            } catch (error: Throwable) {
                onFailure(error)
            }
        },
        INLINE_EXECUTOR,
    )
}
