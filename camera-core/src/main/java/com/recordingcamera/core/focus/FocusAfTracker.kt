package com.recordingcamera.core.focus

/**
 * Interprets the Camera2 `CONTROL_AF_STATE` stream and decides when the *current* focus attempt has
 * produced a trustworthy terminal result.
 *
 * The trap this solves: Camera2 keeps replaying the last AF state in every capture result. A new
 * tap would immediately see the previous request's `FOCUSED_LOCKED` and report a lock that never
 * happened. A terminal state is therefore only accepted when the current attempt has actually been
 * seen scanning, or when the terminal state differs from the last state observed in this attempt.
 *
 * Pure Kotlin so the state machine can be unit-tested without a device.
 */
internal class FocusAfTracker {

    private var sawScan = false
    private var lastState = AF_STATE_INACTIVE

    /**
     * Starts a new attempt: the next terminal state must be justified by a state change.
     *
     * [lastState] is deliberately kept: the last value seen belongs to the attempt that just ended,
     * so a terminal state replayed from the driver's ring buffer is still rejected until this
     * attempt reports something new.
     */
    fun reset() {
        sawScan = false
    }

    /** Feeds one `CONTROL_AF_STATE` value; returns a terminal outcome once one is trustworthy. */
    fun onAfState(state: Int): AfOutcome? {
        val terminal = when (state) {
            AF_STATE_FOCUSED_LOCKED -> true
            AF_STATE_NOT_FOCUSED_LOCKED -> true
            else -> false
        }
        if (!terminal) {
            if (state == AF_STATE_ACTIVE_SCAN || state == AF_STATE_PASSIVE_SCAN) sawScan = true
            lastState = state
            return null
        }
        // Accept a terminal state only when this attempt really moved, so a replayed result from the
        // previous tap cannot fake a lock.
        val trustworthy = sawScan || state != lastState
        if (!trustworthy) return null
        sawScan = false
        lastState = state
        return if (state == AF_STATE_FOCUSED_LOCKED) AfOutcome.LOCKED else AfOutcome.NOT_FOCUSED
    }

    internal enum class AfOutcome { LOCKED, NOT_FOCUSED }

    companion object {
        // Values mirror android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_*.
        const val AF_STATE_INACTIVE = 0
        const val AF_STATE_PASSIVE_SCAN = 1
        const val AF_STATE_PASSIVE_FOCUSED = 2
        const val AF_STATE_ACTIVE_SCAN = 3
        const val AF_STATE_FOCUSED_LOCKED = 4
        const val AF_STATE_NOT_FOCUSED_LOCKED = 5
        const val AF_STATE_PASSIVE_UNFOCUSED = 6
    }
}
