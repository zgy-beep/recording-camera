package com.recordingcamera.core.photo

import com.recordingcamera.core.AspectRatio
import com.recordingcamera.core.PhotoQuality

/** Camera independent pixel size so target selection stays unit testable. */
internal data class PixelSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height.toLong()
    val longEdge: Int get() = maxOf(width, height)
}

/**
 * Picks a capture target out of the sizes the sensor reports.
 *
 * The rule is the one that satisfies users in practice: keep the sensor's aspect ratio, take the
 * largest size that still fits under the hardware JPEG limit (so the encoder can actually produce
 * it), and never invent a size the camera did not advertise.
 */
internal object PhotoTargetSize {

    /** Tolerance used when matching 4:3 / 16:9: real sensors report e.g. 4032x3024, 4000x3000. */
    private const val RATIO_TOLERANCE = 0.03f

    fun matchesAspect(width: Int, height: Int, aspect: AspectRatio): Boolean {
        if (width <= 0 || height <= 0) return false
        val longEdge = maxOf(width, height).toFloat()
        val shortEdge = minOf(width, height).toFloat()
        val ratio = longEdge / shortEdge
        return kotlin.math.abs(ratio - aspect.ratio) <= RATIO_TOLERANCE * aspect.ratio
    }

    /**
     * The size the still capture should ask the sensor for.
     *
     * Deliberately capped at 12 MP: on 48/108 MP quad-bayer sensors the full-resolution mode is the
     * *non*-binned readout, which is noisier, softer per-pixel and several times slower to encode
     * than the 12 MP binned output the sensor produces by default. Nothing above 12 MP improves a
     * phone-class JPEG; it only costs shutter latency.
     *
     * Speed priority drops to 8 MP because that is the point of the mode: a burst of 12 MP JPEGs
     * cannot be encoded as fast as the user can tap.
     */
    fun captureTarget(aspect: AspectRatio, quality: PhotoQuality): PixelSize = when {
        aspect == AspectRatio.RATIO_4_3 && quality == PhotoQuality.SPEED -> PixelSize(3264, 2448)
        aspect == AspectRatio.RATIO_4_3 -> PixelSize(4032, 3024)
        quality == PhotoQuality.SPEED -> PixelSize(3264, 1836)
        else -> PixelSize(3840, 2160)
    }

    fun largestForAspect(candidates: List<PixelSize>, aspect: AspectRatio): PixelSize? =
        candidates
            .filter { matchesAspect(it.width, it.height, aspect) }
            .maxByOrNull { it.pixels }

    fun clampToHardware(candidate: PixelSize, maxHardwarePixels: Long?): PixelSize {
        if (maxHardwarePixels == null || maxHardwarePixels <= 0L) return candidate
        if (candidate.pixels <= maxHardwarePixels) return candidate
        // Scale down uniformly so the aspect ratio survives; round to even numbers because most
        // JPEG encoders reject odd dimensions on some hardware paths.
        val scale = kotlin.math.sqrt(maxHardwarePixels.toDouble() / candidate.pixels.toDouble())
        val width = ((candidate.width * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val height = ((candidate.height * scale).toInt() / 2 * 2).coerceAtLeast(2)
        return PixelSize(width, height)
    }

    /**
     * Decides whether the configured quality is reachable without a software encoder.
     * Returns false when the sensor's hardware JPEG path cannot reach the requested size, which is
     * the case where enabling the software encoder is the only way to keep the resolution.
     */
    fun fitsHardwareJpeg(target: PixelSize, maxHardwarePixels: Long?): Boolean =
        maxHardwarePixels == null || maxHardwarePixels <= 0L || target.pixels <= maxHardwarePixels
}
