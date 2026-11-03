package com.recordingcamera.core.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomMathTest {

    @Test
    fun `the ends of the slider are the ends of the range`() {
        assertEquals(0.5f, ZoomMath.ratioForFraction(0.5f, 8f, 0f), 1e-4f)
        assertEquals(8f, ZoomMath.ratioForFraction(0.5f, 8f, 1f), 1e-4f)
    }

    @Test
    fun `out of range fractions are clamped instead of extrapolating`() {
        assertEquals(0.5f, ZoomMath.ratioForFraction(0.5f, 8f, -3f), 1e-4f)
        assertEquals(8f, ZoomMath.ratioForFraction(0.5f, 8f, 4f), 1e-4f)
    }

    @Test
    fun `the mapping is strictly increasing`() {
        var previous = 0f
        for (step in 0..40) {
            val ratio = ZoomMath.ratioForFraction(0.6f, 10f, step / 40f)
            assertTrue("ratio must grow with the slider", ratio > previous)
            previous = ratio
        }
    }

    @Test
    fun `one times zoom sits at a stable slider position`() {
        // Log mapping: 1x lands where it does for a 0.5..8 device, and does not jump around when the
        // telephoto factor changes the maximum.
        val narrow = ZoomMath.fractionForRatio(0.5f, 4f, 1f)
        val wide = ZoomMath.fractionForRatio(0.5f, 8f, 1f)

        assertTrue("1x must not sit at the far left", narrow > 0.1f)
        assertTrue("1x must not sit at the far right", narrow < 0.9f)
        assertTrue("a longer range pushes 1x left", wide < narrow)
    }

    @Test
    fun `fraction and ratio are inverses`() {
        val ratios = listOf(0.5f, 0.8f, 1f, 2f, 3.5f, 8f)
        ratios.forEach { ratio ->
            val fraction = ZoomMath.fractionForRatio(0.5f, 8f, ratio)
            assertEquals(ratio, ZoomMath.ratioForFraction(0.5f, 8f, fraction), 1e-3f)
        }
    }

    @Test
    fun `a degenerate range does not divide by zero`() {
        assertEquals(1f, ZoomMath.ratioForFraction(0f, 0f, 0.5f), 0f)
        // A fixed 2x lens has exactly one reachable ratio; the slider position is irrelevant.
        assertEquals(2f, ZoomMath.ratioForFraction(2f, 2f, 0.5f), 0f)
        assertEquals(2f, ZoomMath.ratioForFraction(2f, 1f, 0.5f), 0f)
        assertEquals(0f, ZoomMath.fractionForRatio(2f, 2f, 2f), 0f)
        assertEquals(0f, ZoomMath.fractionForRatio(0f, 8f, 2f), 0f)
    }
}
