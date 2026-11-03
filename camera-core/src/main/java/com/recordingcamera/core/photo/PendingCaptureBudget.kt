package com.recordingcamera.core.photo

/**
 * Admits or rejects new captures so the pending queue cannot grow without bound.
 *
 * Two independent limits are tracked:
 *  - items, because a burst of taps would otherwise fire more `takePicture` calls than the queue can
 *    drain before the user leaves the screen;
 *  - bytes, because a 50 MP JPEG is ~15 MB and eight of them already fill a modest heap of storage.
 */
internal class PendingCaptureBudget(
    private val maxItems: Int,
    private val maxBytes: Long,
) {

    private val lock = Any()
    private var items = 0
    private var bytes = 0L

    val pending: Int get() = synchronized(lock) { items }
    val pendingBytes: Long get() = synchronized(lock) { bytes }

    fun canAccept(): Boolean = synchronized(lock) {
        items < maxItems.coerceAtLeast(1) && bytes < maxBytes.coerceAtLeast(1L)
    }

    /** Reserves one slot; call [release] once the capture is stored or dropped. */
    fun tryAcquire(): Boolean = synchronized(lock) {
        if (items >= maxItems.coerceAtLeast(1)) return false
        if (bytes >= maxBytes.coerceAtLeast(1L)) return false
        items++
        true
    }

    /** Accounts the size of the captured file. Returns false when the byte budget is exceeded. */
    fun addBytes(delta: Long): Boolean = synchronized(lock) {
        if (delta > 0) bytes += delta
        bytes <= maxBytes.coerceAtLeast(1L)
    }

    /** Releases a slot and the bytes it held; returns the remaining item count. */
    fun release(bytesToRelease: Long = 0L): Int = synchronized(lock) {
        if (items > 0) items--
        bytes = (bytes - bytesToRelease.coerceAtLeast(0L)).coerceAtLeast(0L)
        items
    }
}
