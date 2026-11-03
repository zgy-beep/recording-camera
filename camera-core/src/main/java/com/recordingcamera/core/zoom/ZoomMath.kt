package com.recordingcamera.core.zoom

import kotlin.math.ln
import kotlin.math.pow

/**
 * Maps a 0..1 slider position onto a zoom ratio and back.
 *
 * Linear-in-ratio sliders feel wrong on a phone: every millimetre of travel near 1× jumps a whole
 * magnification step, while the telephoto half of the range is compressed into the last few pixels.
 * A logarithmic mapping fixes that, and keeps `1×` at a stable place regardless of how wide the
 * device's zoom range is.
 */
internal object ZoomMath {

    /**
     * Ratio for a slider position in `0..1`; `0` is [minRatio], `1` is [maxRatio].
     *
     * Returns `1f` when the range is unusable, so a host that reads capabilities before the camera
     * is open never divides by zero.
     */
    fun ratioForFraction(minRatio: Float, maxRatio: Float, fraction: Float): Float {
        if (!minRatio.isFinite() || !maxRatio.isFinite() || minRatio <= 0f) return 1f
        if (maxRatio <= minRatio) return minRatio
        val clamped = fraction.coerceIn(0f, 1f)
        val ratio = minRatio * (maxRatio / minRatio).pow(clamped)
        return ratio.coerceIn(minRatio, maxRatio)
    }

    /**
     * Inverse of [ratioForFraction]: where a ratio sits on the slider, in `0..1`.
     *
     * Used to render a slider that follows both programmatic zoom and pinch gestures.
     */
    fun fractionForRatio(minRatio: Float, maxRatio: Float, ratio: Float): Float {
        if (!minRatio.isFinite() || !maxRatio.isFinite() || minRatio <= 0f) return 0f
        if (maxRatio <= minRatio) return 0f
        val clamped = ratio.coerceIn(minRatio, maxRatio).toDouble()
        val span = ln((maxRatio / minRatio).toDouble())
        if (span <= 0.0) return 0f
        return (ln(clamped / minRatio.toDouble()) / span).toFloat().coerceIn(0f, 1f)
    }
}
