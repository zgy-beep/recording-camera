package com.recordingcamera.core.video

import com.recordingcamera.core.StabilizationMode
import com.recordingcamera.core.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStabilizationPolicyTest {

    @Test
    fun `turning stabilisation off leaves the requested mode untouched`() {
        val decision = VideoStabilizationPolicy.resolve(
            requested = StabilizationMode.OFF,
            deviceSupports = true,
            requestedQuality = VideoQuality.UHD,
            requestedFrameRate = 60,
        )

        assertFalse(decision.stabilizationEnabled)
        assertEquals(VideoQuality.UHD, decision.quality)
        assertEquals(60, decision.frameRate)
        assertFalse(decision.unsatisfied)
    }

    @Test
    fun `an unsupported device degrades quietly for auto and reports require`() {
        val auto = VideoStabilizationPolicy.resolve(
            StabilizationMode.AUTO, deviceSupports = false, requestedQuality = VideoQuality.FHD, requestedFrameRate = null,
        )
        val required = VideoStabilizationPolicy.resolve(
            StabilizationMode.REQUIRE, deviceSupports = false, requestedQuality = VideoQuality.FHD, requestedFrameRate = null,
        )

        assertFalse(auto.stabilizationEnabled)
        assertFalse(auto.unsatisfied)
        assertTrue(required.unsatisfied)
    }

    @Test
    fun `uhd is stepped down to fhd because stabilised uhd usually does not exist`() {
        val decision = VideoStabilizationPolicy.resolve(
            StabilizationMode.AUTO, deviceSupports = true, requestedQuality = VideoQuality.UHD, requestedFrameRate = null,
        )

        assertTrue(decision.stabilizationEnabled)
        assertEquals(VideoQuality.FHD, decision.quality)
        assertEquals(VideoStabilizationPolicy.STABILIZED_AUTO_FRAME_RATE, decision.frameRate)
    }

    @Test
    fun `high frame rates are clamped into the stabilised envelope`() {
        val decision = VideoStabilizationPolicy.resolve(
            StabilizationMode.REQUIRE, deviceSupports = true, requestedQuality = VideoQuality.FHD, requestedFrameRate = 60,
        )

        assertEquals(30, decision.frameRate)
    }

    @Test
    fun `an in-envelope request keeps its frame rate`() {
        val decision = VideoStabilizationPolicy.resolve(
            StabilizationMode.AUTO, deviceSupports = true, requestedQuality = VideoQuality.HD, requestedFrameRate = 24,
        )

        assertEquals(VideoQuality.HD, decision.quality)
        assertEquals(24, decision.frameRate)
        assertNull(null)
    }

    @Test
    fun `envelope check flags the modes that cannot be stabilised`() {
        assertTrue(VideoStabilizationPolicy.exceedsEnvelope(VideoQuality.UHD, null))
        assertTrue(VideoStabilizationPolicy.exceedsEnvelope(VideoQuality.HIGHEST, null))
        assertTrue(VideoStabilizationPolicy.exceedsEnvelope(VideoQuality.FHD, 60))
        assertFalse(VideoStabilizationPolicy.exceedsEnvelope(VideoQuality.FHD, 30))
        assertFalse(VideoStabilizationPolicy.exceedsEnvelope(VideoQuality.SD, null))
    }
}
