package com.recordingcamera.core.zoom

import android.util.Range
import androidx.camera.core.CameraControl
import com.recordingcamera.core.internal.CoreStateSink
import com.recordingcamera.core.util.awaitValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Owns the zoom model for the whole session.
 *
 * One owner means photo and video share the same eased zoom, the same legal range and the same
 * hardware write path; there is no per-mode duplicate state to keep in sync (the original code had
 * two copies of this logic, one per service).
 *
 * Input contract:
 *  * relative input (pinch, scroll) → [zoomBy], animated;
 *  * absolute input (slider, zoom buttons, restoring a saved ratio) → [setZoomRatio], applied
 *    immediately, because easing an absolute position feels laggy and wrong.
 */
internal class ZoomController(
    private val scope: CoroutineScope,
    private val stateSink: CoreStateSink,
    private val tickMillis: Long = TICK_MILLIS,
    private val publishEpsilon: Float = PUBLISH_EPSILON,
    private val submitEpsilon: Float = SUBMIT_EPSILON,
    watchdogMillis: Long = ZoomDispatcher.DEFAULT_WATCHDOG_MILLIS,
) {
    private val model = SmoothZoom()
    private val dispatcher = ZoomDispatcher(scope, watchdogMillis) { ratio -> applyToCamera(ratio) }

    private var control: CameraControl? = null
    private var ticker: Job? = null
    private var lastTickAtNanos: Long = 0L
    private var publishedRatio: Float = Float.NaN
    private var submittedRatio: Float = Float.NaN

    /** Latest eased value; safe to read for capture metadata. */
    val zoomRatio: Float get() = model.current

    val isZooming: Boolean get() = model.isAnimating

    /**
     * Binds the controller to a freshly bound camera and publishes the new legal range.
     *
     * The visible ratio is preserved across rebinds (lens switch, flash reconfigure) and clamped
     * into the new camera's range, so the preview does not jump back to 1x.
     */
    fun attach(cameraControl: CameraControl?, range: Range<Float>) {
        control = cameraControl
        val normalized = normalize(range)
        val clampedCurrent = model.current.coerceIn(normalized)
        val changed = model.setRange(normalized.start, normalized.endInclusive)
        model.snapTo(clampedCurrent)
        publishedRatio = Float.NaN
        submittedRatio = Float.NaN
        publish(force = true)
        // The camera may have a different zoom window than before (lens switch), so push the value
        // even when the model says nothing changed.
        submit(model.current, force = true)
        if (changed) {
            stateSink.update { it.copy(zoomRatioRange = normalized) }
        }
    }

    /** Stops the animation and forgets the camera; used before rebinding or closing. */
    fun detach() {
        ticker?.cancel()
        ticker = null
        control = null
        model.cancelAnimation()
    }

    /** Absolute zoom request. Applied without easing. */
    fun setZoomRatio(ratio: Float) {
        model.snapTo(ratio)
        publish(force = true)
        submit(model.current, force = true)
    }

    /** Relative zoom request (pinch). Eased towards the new target. */
    fun zoomBy(scale: Float) {
        model.scaleBy(scale)
        ensureTicker()
    }

    /** Relative zoom step for discrete buttons. */
    fun stepBy(delta: Float) {
        model.stepBy(delta)
        ensureTicker()
    }

    /** Cancels easing and leaves the visible zoom where it is. */
    fun cancelAnimation() {
        model.cancelAnimation()
        publish(force = true)
    }

    fun close() {
        ticker?.cancel()
        ticker = null
        dispatcher.close()
    }

    /**
     * Point the ticker at the model. The loop stops as soon as the model converges, so no
     * coroutine is left spinning while the user is not touching the screen.
     */
    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        lastTickAtNanos = System.nanoTime()
        ticker = scope.launch {
            while (model.isAnimating) {
                delay(tickMillis)
                val now = System.nanoTime()
                val deltaMillis = ((now - lastTickAtNanos) / 1_000_000f)
                    .coerceIn(MIN_STEP_MILLIS, SmoothZoom.MAX_STEP_MILLIS)
                lastTickAtNanos = now
                val value = model.advance(deltaMillis)
                publish(force = false)
                submit(value, force = false)
            }
            publish(force = true)
            submit(model.current, force = true)
        }
    }

    private fun publish(force: Boolean) {
        val value = model.current
        if (!force && !publishedRatio.isNaN() && abs(value - publishedRatio) <= publishEpsilon) return
        publishedRatio = value
        stateSink.update { state ->
            if (abs(state.zoomRatio - value) <= publishEpsilon && !force) state
            else state.copy(zoomRatio = value)
        }
    }

    private fun submit(value: Float, force: Boolean) {
        if (!force && !submittedRatio.isNaN() && abs(value - submittedRatio) < submitEpsilon) return
        submittedRatio = value
        dispatcher.submit(value)
    }

    private suspend fun applyToCamera(ratio: Float) {
        val cameraControl = control ?: return
        cameraControl.setZoomRatio(ratio).awaitValue(APPLY_TIMEOUT_MILLIS, "setZoomRatio")
    }

    private fun normalize(range: Range<Float>): ClosedFloatingPointRange<Float> {
        val lower = range.lower.toFloat()
        val upper = range.upper.toFloat()
        if (!lower.isFinite() || !upper.isFinite() || upper <= lower) {
            return ZOOM_DISABLED_RANGE
        }
        return lower..upper
    }

    companion object {
        const val TICK_MILLIS: Long = 16L
        const val PUBLISH_EPSILON: Float = 0.002f
        const val SUBMIT_EPSILON: Float = 5e-4f
        const val MIN_STEP_MILLIS: Float = 1f
        const val APPLY_TIMEOUT_MILLIS: Long = 2_000L
        val ZOOM_DISABLED_RANGE: ClosedFloatingPointRange<Float> = 1f..1f
    }
}
