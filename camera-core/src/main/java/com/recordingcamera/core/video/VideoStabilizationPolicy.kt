package com.recordingcamera.core.video

import com.recordingcamera.core.StabilizationMode
import com.recordingcamera.core.VideoQuality

/**
 * Resolves how stabilisation interacts with quality and frame rate.
 *
 * Camera2 only offers stabilisation inside a bounded streaming envelope: on many devices the
 * stabilised modes sit at 1080p/30, and asking for UHD or 60 fps with stabilisation either throws at
 * bind time or silently records unstabilised video. The policy below keeps the promise the caller
 * made ("stabilise this clip") and reports what had to move to keep it.
 */
internal object VideoStabilizationPolicy {

    data class Decision(
        val stabilizationEnabled: Boolean,
        val quality: VideoQuality,
        val frameRate: Int?,
        /** True when the caller asked for something the device cannot stabilise at all. */
        val unsatisfied: Boolean = false,
    )

    const val STABILIZED_AUTO_FRAME_RATE = 30

    fun resolve(
        requested: StabilizationMode,
        deviceSupports: Boolean,
        requestedQuality: VideoQuality,
        requestedFrameRate: Int?,
    ): Decision {
        if (requested == StabilizationMode.OFF) {
            return Decision(false, requestedQuality, requestedFrameRate)
        }
        if (!deviceSupports) {
            // AUTO quietly degrades; REQUIRE is surfaced to the caller as an unsatisfied request.
            return Decision(false, requestedQuality, requestedFrameRate, unsatisfied = requested == StabilizationMode.REQUIRE)
        }

        val withinEnvelope = requestedQuality != VideoQuality.UHD && requestedQuality != VideoQuality.HIGHEST

        // Outside the envelope the stabilised modes simply do not exist, so the resolution has to
        // step down to the highest mode that can actually be stabilised (typically FHD).
        val quality = if (withinEnvelope) requestedQuality else VideoQuality.FHD
        
        // Stabilised modes are usually published at 30 fps; clamping the rate keeps the mode usable
        // instead of forcing a fallback to the unstabilised HIGHEST recording.
        val frameRate = when {
            requestedFrameRate == null -> if (withinEnvelope) null else STABILIZED_AUTO_FRAME_RATE
            requestedFrameRate > STABILIZED_AUTO_FRAME_RATE -> STABILIZED_AUTO_FRAME_RATE
            else -> requestedFrameRate
        }

        return Decision(true, quality, frameRate)
    }

    /** True when stabilisation is on but the selected mode is outside the envelope anyway. */
    fun exceedsEnvelope(quality: VideoQuality, frameRate: Int?): Boolean =
        quality == VideoQuality.UHD || quality == VideoQuality.HIGHEST ||
            (frameRate != null && frameRate > STABILIZED_AUTO_FRAME_RATE)
}
