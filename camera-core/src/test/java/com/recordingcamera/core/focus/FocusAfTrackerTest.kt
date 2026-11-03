package com.recordingcamera.core.focus

import com.recordingcamera.core.focus.FocusAfTracker.Companion.AF_STATE_ACTIVE_SCAN
import com.recordingcamera.core.focus.FocusAfTracker.Companion.AF_STATE_FOCUSED_LOCKED
import com.recordingcamera.core.focus.FocusAfTracker.Companion.AF_STATE_INACTIVE
import com.recordingcamera.core.focus.FocusAfTracker.Companion.AF_STATE_NOT_FOCUSED_LOCKED
import com.recordingcamera.core.focus.FocusAfTracker.Companion.AF_STATE_PASSIVE_FOCUSED
import com.recordingcamera.core.focus.FocusAfTracker.Companion.AF_STATE_PASSIVE_SCAN
import com.recordingcamera.core.focus.FocusAfTracker.Companion.AF_STATE_PASSIVE_UNFOCUSED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusAfTrackerTest {

    @Test
    fun `non terminal states are ignored`() {
        val tracker = FocusAfTracker()

        assertNull(tracker.onAfState(AF_STATE_INACTIVE))
        assertNull(tracker.onAfState(AF_STATE_PASSIVE_FOCUSED))
        assertNull(tracker.onAfState(AF_STATE_PASSIVE_UNFOCUSED))
    }

    @Test
    fun `a scan followed by a lock reports a lock`() {
        val tracker = FocusAfTracker()

        assertNull(tracker.onAfState(AF_STATE_ACTIVE_SCAN))
        assertEquals(FocusAfTracker.AfOutcome.LOCKED, tracker.onAfState(AF_STATE_FOCUSED_LOCKED))
    }

    @Test
    fun `a replayed lock from the previous attempt is rejected`() {
        val tracker = FocusAfTracker()

        assertEquals(FocusAfTracker.AfOutcome.LOCKED, tracker.onAfState(AF_STATE_FOCUSED_LOCKED))
        // Camera2 keeps repeating the last AF state in every capture result; the same value must not
        // be mistaken for the outcome of a new tap.
        assertNull(tracker.onAfState(AF_STATE_FOCUSED_LOCKED))
    }

    @Test
    fun `a different terminal state is accepted without a scan`() {
        val tracker = FocusAfTracker()

        assertNull(tracker.onAfState(AF_STATE_PASSIVE_SCAN))
        assertEquals(FocusAfTracker.AfOutcome.LOCKED, tracker.onAfState(AF_STATE_FOCUSED_LOCKED))
        assertEquals(FocusAfTracker.AfOutcome.NOT_FOCUSED, tracker.onAfState(AF_STATE_NOT_FOCUSED_LOCKED))
    }

    @Test
    fun `reset keeps the previous terminal state as the baseline`() {
        val tracker = FocusAfTracker()

        assertNull(tracker.onAfState(AF_STATE_PASSIVE_SCAN))
        assertEquals(FocusAfTracker.AfOutcome.LOCKED, tracker.onAfState(AF_STATE_FOCUSED_LOCKED))

        // A new tap starts here. The driver is still replaying FOCUSED_LOCKED from the previous
        // attempt, so the first result of the new attempt must not be trusted on its own.
        tracker.reset()
        assertNull(tracker.onAfState(AF_STATE_FOCUSED_LOCKED))

        // Once the new attempt actually scans, the next terminal state is trustworthy again.
        assertNull(tracker.onAfState(AF_STATE_PASSIVE_SCAN))
        assertEquals(FocusAfTracker.AfOutcome.LOCKED, tracker.onAfState(AF_STATE_FOCUSED_LOCKED))
    }

    @Test
    fun `a transition out of idle into a terminal state is accepted`() {
        val tracker = FocusAfTracker()

        tracker.reset()
        assertNull(tracker.onAfState(AF_STATE_INACTIVE))
        assertEquals(FocusAfTracker.AfOutcome.LOCKED, tracker.onAfState(AF_STATE_FOCUSED_LOCKED))
    }

    @Test
    fun `a failed lock is reported as not focused`() {
        val tracker = FocusAfTracker()

        assertNull(tracker.onAfState(AF_STATE_PASSIVE_SCAN))
        assertEquals(
            FocusAfTracker.AfOutcome.NOT_FOCUSED,
            tracker.onAfState(AF_STATE_NOT_FOCUSED_LOCKED),
        )
    }
}
