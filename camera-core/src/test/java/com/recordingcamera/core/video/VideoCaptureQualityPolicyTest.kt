package com.recordingcamera.core.video

import com.recordingcamera.core.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two knobs that decide whether a moving subject comes out crisp: how many bits per
 * second the encoder is allowed, and the floor on the exposure time.
 */
class VideoCaptureQualityPolicyTest {

    @Test
    fun `bit rate grows with the requested resolution`() {
        val bitRate = VideoQuality.entries.associateWith(VideoOutputFactory::defaultBitRate)

        assertEquals(4_000_000, bitRate.getValue(VideoQuality.LOWEST))
        assertEquals(20_000_000, bitRate.getValue(VideoQuality.FHD))
        assertEquals(45_000_000, bitRate.getValue(VideoQuality.UHD))
        assertTrue(bitRate.getValue(VideoQuality.SD) < bitRate.getValue(VideoQuality.HD))
        assertTrue(bitRate.getValue(VideoQuality.HD) < bitRate.getValue(VideoQuality.FHD))
        assertTrue(bitRate.getValue(VideoQuality.FHD) < bitRate.getValue(VideoQuality.UHD))
    }

    @Test
    fun `every quality gets a bit rate well above the messenger default`() {
        // CameraX picks a bit rate tuned for sharing, which falls apart on texture (foliage, water,
        // detail in motion) at UHD. Anything below ~12 Mbps at 1080p is visibly soft.
        VideoQuality.entries.forEach { quality ->
            assertTrue(
                "$quality bit rate is too low",
                VideoOutputFactory.defaultBitRate(quality) >= 4_000_000,
            )
        }
        assertTrue(VideoOutputFactory.defaultBitRate(VideoQuality.HD) >= 12_000_000)
    }

    @Test
    fun `a range that cannot hold the floor is dropped`() {
        assertNull(VideoOutputFactory.motionFrameRateRange(emptyList()))
        assertNull(VideoOutputFactory.motionFrameRateRange(listOf(15..30, 24..24)))
        assertNull(VideoOutputFactory.motionFrameRateRange(listOf(7..30)))
    }

    @Test
    fun `a higher ceiling wins so the preview stays smooth`() {
        val range = VideoOutputFactory.motionFrameRateRange(listOf(30..30, 30..60))

        assertEquals(30..60, range)
    }

    @Test
    fun `a tie on the ceiling keeps the narrowest range`() {
        // Same ceiling means the same worst-case preview smoothness, so the narrower range is the
        // safer pick: the sensor gets less room to stretch the exposure time.
        assertEquals(60..60, VideoOutputFactory.motionFrameRateRange(listOf(30..60, 60..60)))
        assertEquals(45..45, VideoOutputFactory.motionFrameRateRange(listOf(30..45, 45..45)))
    }

    @Test
    fun `a supported frame rate is requested exactly`() {
        assertEquals(30..30, VideoOutputFactory.clampToSupported(30, listOf(15..30, 30..30)))
        assertEquals(60..60, VideoOutputFactory.clampToSupported(60, listOf(30..60)))
    }

    @Test
    fun `an unsupported frame rate is clamped instead of throwing`() {
        // `setTargetFrameRate` throws for these; the whole recording used to die with it.
        assertEquals(30..30, VideoOutputFactory.clampToSupported(45, listOf(30..30, 60..60)))
        assertEquals(30..30, VideoOutputFactory.clampToSupported(24, listOf(30..30)))
        assertNull(VideoOutputFactory.clampToSupported(30, emptyList()))
        assertNull(VideoOutputFactory.clampToSupported(0, listOf(30..30)))
    }
}
