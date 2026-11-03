package com.recordingcamera.core.internal

import com.recordingcamera.core.FlashMode
import com.recordingcamera.core.PhotoQuality

/**
 * Everything the capture pipeline wants to change about the sensor for one photo configuration.
 * Pure data, so the decision table can be unit tested without a camera.
 */
internal data class CaptureProfile(
    val zeroShutterLag: Boolean = false,
    val qualityPriority: Boolean = false,
    val fastCapture: Boolean = false,
    /** Inclusive frame rate range, lower..upper. */
    val frameRate: IntRange? = null,
    val opticalStabilization: Boolean = false,
    val highQualityNoiseReduction: Boolean = false,
    val highQualityEdge: Boolean = false,
)

/**
 * Chooses the sensor knobs for a still capture.
 *
 * The three priorities are genuinely different pipelines, not cosmetics:
 *  - SPEED keeps latency low (frame rate up, smaller JPEG, OIS on);
 *  - BALANCED keeps the defaults and ZSL when the flash is off;
 *  - QUALITY gives up ZSL and puts the ISP in high quality noise reduction / edge mode.
 * Night/HDR extensions take over the pipeline entirely, so every tuning knob is skipped there.
 */
internal object CaptureProfileFactory {

    fun build(
        quality: PhotoQuality,
        flashMode: FlashMode,
        extensionActive: Boolean,
        zeroShutterLagSupported: Boolean,
        opticalStabilizationSupported: Boolean,
        frameRateRanges: List<IntRange>,
    ): CaptureProfile {
        val qualityPriority = quality == PhotoQuality.QUALITY
        // ZSL captures from a ring buffer; it cannot work while the flash has to pop and it is
        // pointless in quality priority where the ISP is asked for the best single frame.
        val zeroShutterLag = !extensionActive &&
            flashMode == FlashMode.OFF &&
            zeroShutterLagSupported &&
            !qualityPriority
        val fastCapture = quality == PhotoQuality.SPEED && !extensionActive && !qualityPriority
        val targetUpper = if (quality == PhotoQuality.SPEED) 60 else 30
        val frameRate = if (extensionActive || qualityPriority) {
            null
        } else {
            selectFrameRate(frameRateRanges, targetUpper)
        }

        return CaptureProfile(
            zeroShutterLag = zeroShutterLag,
            qualityPriority = qualityPriority,
            fastCapture = fastCapture,
            frameRate = frameRate,
            // OIS is worth it in every mode: a stabilised lens removes the small hand tremor that
            // otherwise eats the sharpness of a 12 MP frame shot at 1/30 s. The latency it costs is
            // in the tens of milliseconds, well below the JPEG encode time it sits behind.
            opticalStabilization = !extensionActive && opticalStabilizationSupported,
            highQualityNoiseReduction = qualityPriority && !extensionActive,
            highQualityEdge = qualityPriority && !extensionActive,
        )
    }

    /**
     * Picks the range whose upper bound sits closest to [targetUpper], ignoring ranges that cannot
     * sustain at least 30 fps (they would make the shutter feel sluggish).
     */
    fun selectFrameRate(ranges: List<IntRange>, targetUpper: Int): IntRange? {
        val usable = ranges.filter { it.first >= MIN_USABLE_FPS }
        if (usable.isEmpty()) return null
        return usable.minWithOrNull(
            compareBy<IntRange> { kotlin.math.abs(it.last - targetUpper) }
                // Same upper bound: the narrower range is the honest one. Asking for 30..60 when the
                // sensor advertises 60..60 lets AE fall back to 30 fps, which defeats "fast".
                .thenBy { it.last - it.first }
                .thenByDescending { it.last },
        )
    }

    const val MIN_USABLE_FPS = 30
}
