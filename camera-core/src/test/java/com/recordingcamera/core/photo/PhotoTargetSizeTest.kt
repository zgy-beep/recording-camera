package com.recordingcamera.core.photo

import com.recordingcamera.core.AspectRatio
import com.recordingcamera.core.PhotoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoTargetSizeTest {

    private val fourByThree = listOf(
        PixelSize(4032, 3024),
        PixelSize(4000, 3000),
        PixelSize(1920, 1080),
        PixelSize(1440, 1080),
    )

    @Test
    fun `aspect matching tolerates the sizes sensors actually report`() {
        assertTrue(PhotoTargetSize.matchesAspect(4032, 3024, AspectRatio.RATIO_4_3))
        assertTrue(PhotoTargetSize.matchesAspect(4000, 3000, AspectRatio.RATIO_4_3))
        assertFalse(PhotoTargetSize.matchesAspect(1920, 1080, AspectRatio.RATIO_4_3))
        assertTrue(PhotoTargetSize.matchesAspect(1920, 1080, AspectRatio.RATIO_16_9))
    }

    @Test
    fun `largest size for the aspect ratio wins`() {
        val target = PhotoTargetSize.largestForAspect(fourByThree, AspectRatio.RATIO_4_3)

        assertEquals(PixelSize(4032, 3024), target)
    }

    @Test
    fun `no candidate yields null instead of inventing a size`() {
        assertNull(PhotoTargetSize.largestForAspect(emptyList(), AspectRatio.RATIO_4_3))
        assertNull(
            PhotoTargetSize.largestForAspect(listOf(PixelSize(1920, 1080)), AspectRatio.RATIO_4_3),
        )
    }

    @Test
    fun `clamping preserves the aspect ratio and even dimensions`() {
        val clamped = PhotoTargetSize.clampToHardware(PixelSize(8000, 6000), maxHardwarePixels = 12_000_000L)

        assertTrue(clamped.pixels <= 12_000_000L)
        assertEquals(0, clamped.width % 2)
        assertEquals(0, clamped.height % 2)
        assertEquals(4f / 3f, clamped.width.toFloat() / clamped.height.toFloat(), 0.01f)
    }

    @Test
    fun `a target inside the hardware limit is left untouched`() {
        val target = PixelSize(4032, 3024)

        assertEquals(target, PhotoTargetSize.clampToHardware(target, maxHardwarePixels = 50_000_000L))
    }

    @Test
    fun `capture target stays on the binned resolution`() {
        // Sensors report 48/64/108 MP, but those are quad bayer mosaics: the unbinned readout is
        // noisier, slower and bigger for no extra detail. 12 MP is the honest still size.
        assertEquals(PixelSize(4032, 3024), PhotoTargetSize.captureTarget(AspectRatio.RATIO_4_3, PhotoQuality.BALANCED))
        assertEquals(PixelSize(4032, 3024), PhotoTargetSize.captureTarget(AspectRatio.RATIO_4_3, PhotoQuality.QUALITY))
        assertEquals(PixelSize(3840, 2160), PhotoTargetSize.captureTarget(AspectRatio.RATIO_16_9, PhotoQuality.BALANCED))
    }

    @Test
    fun `speed mode asks for the smaller binned size`() {
        val target = PhotoTargetSize.captureTarget(AspectRatio.RATIO_4_3, PhotoQuality.SPEED)

        assertEquals(PixelSize(3264, 2448), target)
        assertEquals(4f / 3f, target.width.toFloat() / target.height.toFloat(), 0.01f)
        assertTrue(target.pixels < 12_000_000L)
    }

    @Test
    fun `speed mode keeps the wide aspect ratio instead of squashing it`() {
        val target = PhotoTargetSize.captureTarget(AspectRatio.RATIO_16_9, PhotoQuality.SPEED)

        assertEquals(PixelSize(3264, 1836), target)
        assertEquals(16f / 9f, target.width.toFloat() / target.height.toFloat(), 0.01f)
    }

    @Test
    fun `unknown hardware limit means the target fits`() {
        val target = PixelSize(4032, 3024)

        assertTrue(PhotoTargetSize.fitsHardwareJpeg(target, null))
        assertTrue(PhotoTargetSize.fitsHardwareJpeg(target, 0L))
        assertFalse(PhotoTargetSize.fitsHardwareJpeg(target, 1_000_000L))
    }
}
