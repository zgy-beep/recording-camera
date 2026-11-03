package com.recordingcamera.core.photo

import com.recordingcamera.core.PhotoConfig
import com.recordingcamera.core.PhotoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegPolicyTest {

    private fun config(
        quality: PhotoQuality = PhotoQuality.BALANCED,
        override: Int? = null,
        preferHardware: Boolean = true,
    ) = PhotoConfig(quality = quality, jpegQualityOverride = override, preferHardwareJpeg = preferHardware)

    @Test
    fun `quality priority asks for the maximum jpeg quality`() {
        assertEquals(
            JpegPolicy.QUALITY_PRIORITY,
            JpegPolicy.jpegQuality(config(PhotoQuality.QUALITY), zeroShutterLag = false),
        )
    }

    @Test
    fun `zero shutter lag uses a slightly lower quality`() {
        assertEquals(
            JpegPolicy.QUALITY_ZERO_SHUTTER_LAG,
            JpegPolicy.jpegQuality(config(PhotoQuality.BALANCED), zeroShutterLag = true),
        )
        assertEquals(
            JpegPolicy.QUALITY_DEFAULT,
            JpegPolicy.jpegQuality(config(PhotoQuality.BALANCED), zeroShutterLag = false),
        )
    }

    @Test
    fun `an explicit override wins over the preset`() {
        assertEquals(
            88,
            JpegPolicy.jpegQuality(config(PhotoQuality.QUALITY, override = 88), zeroShutterLag = true),
        )
    }

    @Test
    fun `capture mode follows zero shutter lag and quality priority`() {
        assertEquals(JpegPolicy.ZERO_SHUTTER_LAG, JpegPolicy.captureMode(PhotoQuality.BALANCED, true))
        assertEquals(JpegPolicy.MAXIMIZE_QUALITY, JpegPolicy.captureMode(PhotoQuality.QUALITY, false))
        assertEquals(JpegPolicy.MINIMIZE_LATENCY, JpegPolicy.captureMode(PhotoQuality.SPEED, false))
    }

    @Test
    fun `software encoder is only requested when hardware jpeg is refused`() {
        assertFalse(JpegPolicy.shouldUseSoftwareJpegEncoder(config(preferHardware = true)))
        assertTrue(JpegPolicy.shouldUseSoftwareJpegEncoder(config(preferHardware = false)))
    }
}
