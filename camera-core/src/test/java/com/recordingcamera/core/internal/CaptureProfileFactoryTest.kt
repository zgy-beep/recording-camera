package com.recordingcamera.core.internal

import com.recordingcamera.core.FlashMode
import com.recordingcamera.core.PhotoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureProfileFactoryTest {

    private val ranges = listOf(15..30, 30..30, 30..60, 60..60)

    private fun build(
        quality: PhotoQuality = PhotoQuality.BALANCED,
        flash: FlashMode = FlashMode.OFF,
        extension: Boolean = false,
        zsl: Boolean = true,
        ois: Boolean = true,
    ) = CaptureProfileFactory.build(
        quality = quality,
        flashMode = flash,
        extensionActive = extension,
        zeroShutterLagSupported = zsl,
        opticalStabilizationSupported = ois,
        frameRateRanges = ranges,
    )

    @Test
    fun `balanced capture keeps zero shutter lag when the flash is off`() {
        val profile = build()

        assertTrue(profile.zeroShutterLag)
        assertFalse(profile.qualityPriority)
        assertFalse(profile.fastCapture)
        assertEquals(30..30, profile.frameRate)
    }

    @Test
    fun `a flash capture cannot use zero shutter lag`() {
        assertFalse(build(flash = FlashMode.ON).zeroShutterLag)
        assertFalse(build(flash = FlashMode.AUTO).zeroShutterLag)
    }

    @Test
    fun `quality priority disables zsl and asks for the best isp modes`() {
        val profile = build(quality = PhotoQuality.QUALITY)

        assertFalse(profile.zeroShutterLag)
        assertTrue(profile.qualityPriority)
        assertTrue(profile.highQualityNoiseReduction)
        assertTrue(profile.highQualityEdge)
        assertNull("frame rate request is dropped in quality mode", profile.frameRate)
    }

    @Test
    fun `speed mode asks for the fastest usable range and enables ois`() {
        val profile = build(quality = PhotoQuality.SPEED)

        assertTrue(profile.fastCapture)
        assertTrue(profile.opticalStabilization)
        assertNotNull(profile.frameRate)
        assertEquals(60, profile.frameRate?.last)
    }

    @Test
    fun `ois is requested in every still mode except extensions`() {
        // Hand tremor at 1/30 s is what eats sharpness on a 12 MP frame, so OIS is not a
        // speed-only feature.
        assertTrue(build(quality = PhotoQuality.BALANCED).opticalStabilization)
        assertTrue(build(quality = PhotoQuality.QUALITY).opticalStabilization)
        assertFalse(build(extension = true).opticalStabilization)
    }

    @Test
    fun `an extension pipeline skips every tuning knob`() {
        val profile = build(extension = true)

        assertFalse(profile.zeroShutterLag)
        assertNull(profile.frameRate)
        assertFalse(profile.highQualityNoiseReduction)
        assertFalse(profile.highQualityEdge)
        assertFalse(profile.opticalStabilization)
    }

    @Test
    fun `unsupported zsl and ois are not requested`() {
        val profile = build(zsl = false, ois = false)

        assertFalse(profile.zeroShutterLag)
        assertFalse(profile.opticalStabilization)
    }

    @Test
    fun `frame rate selection ignores ranges that cannot hold 30 fps`() {
        assertEquals(30..30, CaptureProfileFactory.selectFrameRate(ranges, 30))
        assertEquals(60..60, CaptureProfileFactory.selectFrameRate(ranges, 60))
        assertNull(CaptureProfileFactory.selectFrameRate(listOf(15..30, 24..24), 30))
        assertNull(CaptureProfileFactory.selectFrameRate(emptyList(), 30))
    }
}
