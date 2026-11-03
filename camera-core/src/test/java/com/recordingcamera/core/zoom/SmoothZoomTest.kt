package com.recordingcamera.core.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothZoomTest {

    @Test
    fun `snapTo clamps into the supported range`() {
        val zoom = SmoothZoom()
        zoom.setRange(0.6f, 5f)

        zoom.snapTo(9f)
        assertEquals(5f, zoom.current, 1e-4f)
        assertEquals(5f, zoom.target, 1e-4f)

        zoom.snapTo(0.1f)
        assertEquals(0.6f, zoom.current, 1e-4f)
    }

    @Test
    fun `scaleBy accumulates on the target and stays clamped`() {
        val zoom = SmoothZoom().apply {
            setRange(1f, 4f)
            snapTo(2f)
        }

        zoom.scaleBy(1.5f)
        assertEquals(3f, zoom.target, 1e-4f)

        zoom.scaleBy(10f)
        assertEquals(4f, zoom.target, 1e-4f)
    }

    @Test
    fun `advance moves towards the target and settles`() {
        val zoom = SmoothZoom().apply {
            setRange(1f, 8f)
            snapTo(1f)
        }
        zoom.scaleBy(2f)

        val first = zoom.advance(16f)
        assertTrue("first step should move", first > 1f)
        assertTrue("first step should not snap", first < 2f)

        repeat(120) { zoom.advance(16f) }
        assertEquals(2f, zoom.current, 0.05f)
        assertFalse(zoom.isAnimating)
    }

    @Test
    fun `advance caps the step so a stalled frame cannot jump`() {
        val capped = SmoothZoom().apply {
            setRange(1f, 8f)
            snapTo(1f)
            scaleBy(2f)
        }
        val singleStep = SmoothZoom().apply {
            setRange(1f, 8f)
            snapTo(1f)
            scaleBy(2f)
        }

        val afterLongFrame = capped.advance(5_000f)
        val afterCappedFrame = singleStep.advance(SmoothZoom.MAX_STEP_MILLIS)

        assertEquals(afterCappedFrame, afterLongFrame, 1e-4f)
    }

    @Test
    fun `advance is independent of the frame cadence`() {
        val fast = SmoothZoom().apply {
            setRange(1f, 8f)
            snapTo(1f)
            scaleBy(3f)
        }
        val slow = SmoothZoom().apply {
            setRange(1f, 8f)
            snapTo(1f)
            scaleBy(3f)
        }

        repeat(10) { fast.advance(16f) }
        repeat(5) { slow.advance(32f) }

        assertEquals(slow.current, fast.current, 0.02f)
    }

    @Test
    fun `setRange reports whether the value had to move`() {
        val zoom = SmoothZoom().apply {
            setRange(1f, 8f)
            snapTo(4f)
        }

        assertFalse("range unchanged", zoom.setRange(1f, 8f))
        assertEquals(4f, zoom.current, 1e-4f)

        assertTrue("value had to be clamped", zoom.setRange(0.5f, 2f))
        assertEquals(2f, zoom.current, 1e-4f)
    }

    @Test
    fun `cancelAnimation freezes the current ratio`() {
        val zoom = SmoothZoom().apply {
            setRange(1f, 8f)
            snapTo(1f)
        }
        zoom.scaleBy(4f)
        repeat(5) { zoom.advance(16f) }

        zoom.cancelAnimation()

        assertFalse(zoom.isAnimating)
        assertEquals(zoom.current, zoom.target, 1e-4f)
    }
}
