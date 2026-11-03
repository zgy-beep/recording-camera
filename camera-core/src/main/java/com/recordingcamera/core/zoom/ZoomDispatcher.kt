package com.recordingcamera.core.zoom

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serialises zoom writes to the camera.
 *
 * Why not just call `setZoomRatio` from the animation ticker? Because the platform future can take
 * longer than a frame; firing a new request every 16 ms queues work the driver cannot consume and
 * the lens lags behind the finger. This dispatcher keeps **one** request in flight and buffers only
 * the newest value, so the hardware always converges to the latest target and never to a backlog.
 *
 * A watchdog cancels a request that the camera never answers; without it a single stuck future
 * would freeze zoom until the session is rebound.
 *
 * Pure coroutines: the platform call is injected as [applyZoom], which makes the coalescing
 * behaviour testable on the JVM.
 */
internal class ZoomDispatcher(
    private val scope: CoroutineScope,
    private val watchdogMillis: Long = DEFAULT_WATCHDOG_MILLIS,
    private val applyZoom: suspend (Float) -> Unit,
) {
    private val requests = Channel<Float>(Channel.CONFLATED)
    private var worker: Job? = null

    @Volatile
    private var closed = false

    /** Number of values handed to [applyZoom]; exposed for diagnostics/tests. */
    @Volatile
    var appliedCount: Int = 0
        private set

    /** Number of requests abandoned because the platform future hit the watchdog. */
    @Volatile
    var timedOutCount: Int = 0
        private set

    /**
     * Queues [ratio]. Consecutive calls collapse: only the most recent ratio survives until the
     * worker is free.
     */
    fun submit(ratio: Float) {
        if (closed || !ratio.isFinite()) return
        requests.trySend(ratio)
        if (worker?.isActive != true) {
            worker = scope.launch { drain() }
        }
    }

    private suspend fun drain() {
        for (ratio in requests) {
            if (closed) return
            val completed = withTimeoutOrNull(watchdogMillis) {
                applyZoom(ratio)
                true
            } ?: false
            if (completed) {
                appliedCount++
            } else {
                timedOutCount++
            }
        }
    }

    fun close() {
        closed = true
        requests.close()
        worker?.cancel()
        worker = null
    }

    companion object {
        /**
         * Hardware zoom round-trip budget. Longer than a frame so a healthy device is never
         * interrupted, short enough that a stuck request cannot strand the animation.
         */
        const val DEFAULT_WATCHDOG_MILLIS: Long = 150L
    }
}
