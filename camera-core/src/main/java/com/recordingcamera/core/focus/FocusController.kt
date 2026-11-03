package com.recordingcamera.core.focus

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPointFactory
import com.recordingcamera.core.FocusFailure
import com.recordingcamera.core.FocusState
import com.recordingcamera.core.internal.CoreStateSink
import com.recordingcamera.core.util.onResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tap-to-focus with three independent completion signals:
 *
 *  1. the `startFocusAndMetering` future (`FocusMeteringResult.isFocusSuccessful`),
 *  2. the Camera2 AF state stream, which reports a failed lock much earlier than the future,
 *  3. a hard timeout, so a device that never answers cannot leave the UI spinning.
 *
 * Whichever signal arrives first wins; later signals from the same generation are ignored, and
 * signals from a superseded generation are dropped. That generation guard is what makes rapid
 * tapping safe.
 */
internal class FocusController(
    private val scope: CoroutineScope,
    private val stateSink: CoreStateSink,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val tracker = FocusAfTracker()

    private var control: CameraControl? = null
    private var meteringFactory: MeteringPointFactory? = null
    private var generation: Int = 0
    private var pending: CompletableDeferred<FocusState>? = null
    private var timeoutJob: Job? = null

    /** Camera2 session callback that feeds the AF tracker; installed on the preview use case. */
    val captureCallback: CameraCaptureSession.CaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: android.hardware.camera2.CaptureRequest,
                result: TotalCaptureResult,
            ) {
                val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
                when (tracker.onAfState(afState)) {
                    FocusAfTracker.AfOutcome.LOCKED -> settle(generation, FocusState.Focused)
                    FocusAfTracker.AfOutcome.NOT_FOCUSED ->
                        settle(generation, FocusState.Failed(FocusFailure.NOT_FOCUSED))
                    null -> Unit
                }
            }
        }

    fun attach(cameraControl: CameraControl?) {
        control = cameraControl
        generation++
        pending?.complete(FocusState.Failed(FocusFailure.SUPERSEDED))
        pending = null
        timeoutJob?.cancel()
        timeoutJob = null
        tracker.reset()
        stateSink.update { it.copy(focus = FocusState.Idle) }
    }

    fun detach() {
        control = null
        generation++
        pending?.complete(FocusState.Failed(FocusFailure.SUPERSEDED))
        pending = null
        timeoutJob?.cancel()
        timeoutJob = null
        tracker.reset()
    }

    /**
     * Supplies the coordinate mapper for tap-to-focus. Apps pass
     * `PreviewView.meteringPointFactory`; headless callers can pass a
     * [androidx.camera.core.SurfaceOrientedMeteringPointFactory].
     */
    fun setMeteringPointFactory(factory: MeteringPointFactory?) {
        meteringFactory = factory
    }

    /** Cancels the current attempt; the state falls back to [FocusState.Idle]. */
    fun cancel() {
        generation++
        pending?.complete(FocusState.Failed(FocusFailure.SUPERSEDED))
        pending = null
        timeoutJob?.cancel()
        timeoutJob = null
        tracker.reset()
        control?.cancelFocusAndMetering()?.onResult({}, {})
        stateSink.update { it.copy(focus = FocusState.Idle) }
    }

    /**
     * Focuses at a point expressed in the coordinate system of the metering point factory (usually
     * view pixels). Suspends until the attempt settles and returns its outcome.
     */
    suspend fun focusAt(x: Float, y: Float, size: Float = 0f): FocusState {
        val cameraControl = control ?: return FocusState.Failed(FocusFailure.UNSUPPORTED)
        val factory = meteringFactory ?: return FocusState.Failed(FocusFailure.UNSUPPORTED)

        val previous = pending
        if (previous != null && !previous.isCompleted) {
            previous.complete(FocusState.Failed(FocusFailure.SUPERSEDED))
        }
        val attempt = ++generation
        val deferred = CompletableDeferred<FocusState>()
        pending = deferred
        tracker.reset()

        stateSink.update { it.copy(focus = FocusState.Focusing) }

        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeoutMillis)
            settle(attempt, FocusState.Failed(FocusFailure.TIMEOUT))
        }

        // Reset any previous metering region first, then request the new one. The cancel is
        // best-effort: startFocusAndMetering supersedes whatever is active anyway.
        cameraControl.cancelFocusAndMetering().onResult({}, {})

        val action = FocusMeteringAction.Builder(
            factory.createPoint(x, y, size),
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        )
            .setAutoCancelDuration(FOCUS_AUTO_CANCEL_SECONDS, TimeUnit.SECONDS)
            .build()

        cameraControl.startFocusAndMetering(action).onResult(
            { result ->
                settle(
                    attempt,
                    if (result.isFocusSuccessful) {
                        FocusState.Focused
                    } else {
                        FocusState.Failed(FocusFailure.NOT_FOCUSED)
                    },
                )
            },
            { settle(attempt, FocusState.Failed(FocusFailure.NOT_FOCUSED)) },
        )

        val outcome = deferred.await()
        if (pending === deferred) pending = null
        return outcome
    }

    private fun settle(attempt: Int, state: FocusState) {
        if (attempt != generation) return
        val deferred = pending ?: return
        if (deferred.isCompleted) return
        timeoutJob?.cancel()
        timeoutJob = null
        deferred.complete(state)
        stateSink.update { it.copy(focus = state) }
    }

    companion object {
        /** Camera2 auto-cancels a metering region after a few seconds; match that window. */
        const val FOCUS_AUTO_CANCEL_SECONDS: Long = 3L

        /** Hard ceiling for one focus attempt. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 2_500L
    }
}
