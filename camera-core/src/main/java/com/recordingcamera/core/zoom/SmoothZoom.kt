package com.recordingcamera.core.zoom

import kotlin.math.abs
import kotlin.math.exp

/**
 * Frame-rate independent zoom easing.
 *
 * The user's pinch/scroll input only moves [target]; the ticker moves [current] exponentially
 * towards it. Because the step is derived from the elapsed wall-clock time instead of "one step per
 * frame", the feel is identical at 30, 60 and 120 Hz.
 *
 * Pure Kotlin: no Android or coroutine dependencies, fully unit-testable.
 */
class SmoothZoom(
    private val timeConstantMillis: Float = DEFAULT_TIME_CONSTANT_MILLIS,
    private val snapEpsilon: Float = DEFAULT_SNAP_EPSILON,
) {
    private var minRatio: Float = 1f
    private var maxRatio: Float = 1f

    private var _target: Float = 1f
    private var _current: Float = 1f

    /** Value the easing is heading to. */
    val target: Float get() = _target

    /** Value the ticker last reported to the camera. */
    val current: Float get() = _current

    val min: Float get() = minRatio
    val max: Float get() = maxRatio

    /** True while the eased value has not converged on [target] yet. */
    val isAnimating: Boolean get() = abs(_target - _current) > snapTolerance()

    /**
     * Updates the legal zoom window. Both the target and the current value are clamped into it; the
     * return value tells the caller whether the visible zoom changed and therefore whether the
     * camera has to be updated.
     */
    fun setRange(newMin: Float, newMax: Float, keepValue: Boolean = true): Boolean {
        require(newMax >= newMin) { "max ($newMax) must be >= min ($newMin)" }
        minRatio = newMin
        maxRatio = newMax
        val clampedCurrent = _current.coerceIn(newMin, newMax)
        val clampedTarget = if (keepValue) _target.coerceIn(newMin, newMax) else clampedCurrent
        val changed = clampedCurrent != _current || clampedTarget != _target
        _current = clampedCurrent
        _target = clampedTarget
        return changed
    }

    /** Jumps to [value] without easing. Absolute inputs (slider, buttons, restore) must use this. */
    fun snapTo(value: Float) {
        val clamped = value.coerceIn(minRatio, maxRatio)
        _current = clamped
        _target = clamped
    }

    /** Multiplies the target by [scale], as produced by a pinch gesture span delta. */
    fun scaleBy(scale: Float) {
        if (!scale.isFinite() || scale <= 0f) return
        _target = (_target * scale).coerceIn(minRatio, maxRatio)
    }

    /** Adds [delta] to the target; convenience for discrete zoom steps. */
    fun stepBy(delta: Float) {
        _target = (_target + delta).coerceIn(minRatio, maxRatio)
    }

    /**
     * Advances the easing by [deltaMillis] and returns the resulting value.
     *
     * The exponential decay is `current += (target - current) * (1 - e^(-dt/tau))`, clamped so a
     * stalled main thread (huge dt) cannot overshoot.
     */
    fun advance(deltaMillis: Float): Float {
        if (!isAnimating) {
            _current = _target
            return _current
        }
        val dt = deltaMillis.coerceIn(0f, MAX_STEP_MILLIS)
        val tau = timeConstantMillis.coerceAtLeast(1f)
        val alpha = 1f - exp(-dt / tau)
        val next = _current + (_target - _current) * alpha
        _current = if (abs(_target - next) <= snapTolerance()) _target else next
        return _current
    }

    /** Stops any easing at the current value without jumping. */
    fun cancelAnimation() {
        _target = _current
    }

    private fun snapTolerance(): Float = snapEpsilon * maxOf(1f, abs(_target))

    companion object {
        /** Easing time constant; ~63 % of the distance is covered in this time. */
        const val DEFAULT_TIME_CONSTANT_MILLIS: Float = 60f

        /** Relative distance below which the eased value snaps onto the target. */
        const val DEFAULT_SNAP_EPSILON: Float = 0.01f

        /** Upper bound for a single integration step, so a long frame cannot overshoot. */
        const val MAX_STEP_MILLIS: Float = 64f
    }
}
