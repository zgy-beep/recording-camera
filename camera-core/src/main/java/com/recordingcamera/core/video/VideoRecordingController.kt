package com.recordingcamera.core.video

import android.content.Context
import android.os.Build
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.OutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import com.recordingcamera.core.AutoStopReason
import com.recordingcamera.core.CameraCoreEvent
import com.recordingcamera.core.CameraError
import com.recordingcamera.core.RotationSource
import com.recordingcamera.core.SavedVideo
import com.recordingcamera.core.VideoConfig
import com.recordingcamera.core.VideoState
import com.recordingcamera.core.internal.CoreStateSink
import com.recordingcamera.core.util.MediaNaming
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the recording state machine: preparing → recording ⇄ paused → finalising → idle.
 *
 * The controller is written defensively around the finaliser because that is where camera apps
 * usually break: a `Finalize` event can arrive after a longer delay than the user expects, may carry
 * an error even though recording looked healthy, and is the only place where the final URI and the
 * recorded size are known. Every path through [reset] is therefore guaranteed to clear the state.
 */
internal class VideoRecordingController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mainExecutor: Executor,
    private val stateSink: CoreStateSink,
    private val configProvider: () -> VideoConfig,
    private val videoCaptureProvider: () -> VideoCapture<Recorder>?,
    private val decisionProvider: () -> VideoStabilizationPolicy.Decision,
    @Suppress("unused") private val rotationSource: RotationSource,
) {

    private val lock = Any()
    private var recording: Recording? = null
    private var output: AllocatedVideoOutput? = null
    private var finalizerWatchdog: Job? = null
    private var autoStop: AutoStopReason? = null
    private var reportedDurationMillis = 0L
    private var reportedBytes = 0L

    val isActive: Boolean get() = synchronized(lock) { recording != null }

    fun start(): Result<Unit> {
        synchronized(lock) {
            if (recording != null) {
                return Result.failure(CameraError.InvalidState("a recording is already in progress"))
            }
        }
        val capture = videoCaptureProvider()
            ?: return Result.failure(CameraError.NotReady("video use case is not bound"))
        val config = configProvider()
        val decision = decisionProvider()
        if (decision.unsatisfied) {
            return Result.failure(CameraError.Unsupported("video stabilisation is requested but not reported by this device"))
        }

        val timestamp = System.currentTimeMillis()
        val displayName = MediaNaming.videoName(timestamp)
        val allocated = try {
            VideoOutputFactory.allocate(context, config.destination, displayName)
        } catch (error: Throwable) {
            return Result.failure(CameraError.StorageFailed(displayName, error))
        }

        val prepared = prepare(capture.output, allocated.options)
            ?: run {
                allocated.finalize(false, null)
                return Result.failure(
                    CameraError.Unsupported(
                        "unsupported video output options: ${allocated.options.javaClass.simpleName}",
                    ),
                )
            }
        var pending = prepared
        if (config.audioEnabled) {
            pending = try {
                pending.withAudioEnabled()
            } catch (error: SecurityException) {
                // No microphone permission: record silently rather than failing the whole take.
                pending
            } catch (error: IllegalStateException) {
                // Devices that report audio support but refuse to enable it (or an audio track that
                // could not be created) throw here; a silent take beats a dead shutter.
                pending
            }
        }

        stateSink.update { it.copy(video = VideoState.Preparing) }
        reportedDurationMillis = 0L
        reportedBytes = 0L
        autoStop = null

        val started = try {
            pending.start(mainExecutor) { event -> onEvent(event) }
        } catch (error: Throwable) {
            allocated.finalize(false, null)
            stateSink.update { it.copy(video = VideoState.Idle) }
            return Result.failure(CameraError.RecordingFailed(error))
        }

        synchronized(lock) {
            recording = started
            output = allocated
        }
        return Result.success(Unit)
    }

    /**
     * `Recorder.prepareRecording` is overloaded per output-options type, so the allocation has to be
     * narrowed here instead of being passed as the abstract [OutputOptions].
     */
    private fun prepare(recorder: Recorder, options: OutputOptions): PendingRecording? = when {
        options is MediaStoreOutputOptions -> recorder.prepareRecording(context, options)
        // FileDescriptorOutputOptions is API 26+: SAF recording is unavailable below that.
        options is FileDescriptorOutputOptions && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
            recorder.prepareRecording(context, options)
        // MediaStoreOutputOptions is a FileOutputOptions subclass and is matched above, so this
        // branch only sees plain file destinations.
        options is FileOutputOptions -> recorder.prepareRecording(context, options)
        else -> null
    }

    fun stop() {
        val active = synchronized(lock) { recording }
        if (active == null) {
            // Nothing to stop; make sure a dangling Preparing state cannot stick.
            stateSink.update { current -> if (current.video is VideoState.Preparing) current.copy(video = VideoState.Idle) else current }
            return
        }
        stateSink.update { it.copy(video = currentVideo(it) ?: VideoState.Idle) }
        runCatching { active.stop() }
        armFinalizerWatchdog()
    }

    fun pause(): Boolean {
        val active = synchronized(lock) { recording } ?: return false
        return runCatching { active.pause() }.isSuccess
    }

    fun resume(): Boolean {
        val active = synchronized(lock) { recording } ?: return false
        return runCatching { active.resume() }.isSuccess
    }

    /** Called when the whole camera session is being torn down. */
    fun close() {
        finalizerWatchdog?.cancel()
        val active = synchronized(lock) { recording }
        runCatching { active?.close() }
        synchronized(lock) {
            recording = null
            output = null
        }
    }

    private fun onEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                stateSink.update {
                    it.copy(video = VideoState.Recording(elapsedMillis = 0L, bytesRecorded = 0L, paused = false))
                }
            }

            is VideoRecordEvent.Status -> onStatus(event)
            is VideoRecordEvent.Pause -> withdrawElapsed()
            is VideoRecordEvent.Resume -> recordResumed()
            is VideoRecordEvent.Finalize -> onFinalize(event)
        }
    }

    private fun onStatus(event: VideoRecordEvent.Status) {
        val stats = event.recordingStats
        reportedDurationMillis = stats.recordedDurationNanos / 1_000_000L
        reportedBytes = stats.numBytesRecorded
        val config = configProvider()
        val durationLimit = config.maxDurationMillis
        val sizeLimit = config.maxFileBytes

        val hitDuration = durationLimit != null && durationLimit > 0 && reportedDurationMillis >= durationLimit
        val hitSize = sizeLimit != null && sizeLimit > 0 && reportedBytes >= sizeLimit

        stateSink.update { current ->
            val video = current.video
            if (video is VideoState.Recording) {
                current.copy(video = video.copy(elapsedMillis = reportedDurationMillis, bytesRecorded = reportedBytes))
            } else {
                current
            }
        }

        if ((hitDuration || hitSize) && isActive) {
            autoStop = if (hitDuration) AutoStopReason.MAX_DURATION else AutoStopReason.MAX_FILE_SIZE
            stop()
        }
    }

    /** Pausing keeps the elapsed counter frozen, matching what the recorder reports on resume. */
    private fun withdrawElapsed() {
        stateSink.update { current ->
            val video = current.video
            if (video is VideoState.Recording) current.copy(video = video.copy(paused = true)) else current
        }
    }

    private fun recordResumed() {
        stateSink.update { current ->
            val video = current.video
            if (video is VideoState.Recording) current.copy(video = video.copy(paused = false)) else current
        }
    }

    private fun onFinalize(event: VideoRecordEvent.Finalize) {
        finalizerWatchdog?.cancel()
        finalizerWatchdog = null

        val allocated = synchronized(lock) {
            recording = null
            output.also { output = null }
        }
        val failed = event.hasError()
        val resolvedUri = runCatching { event.outputResults.outputUri }.getOrNull()
        allocated?.finalize(!failed, resolvedUri)

        val durationMillis = event.recordingStats.recordedDurationNanos / 1_000_000L
        val bytes = event.recordingStats.numBytesRecorded

        stateSink.update { it.copy(video = VideoState.Idle) }

        if (failed) {
            val cause = event.cause
            stateSink.emit(CameraCoreEvent.VideoFailed(CameraError.RecordingFailed(cause)))
            return
        }

        val stopped = autoStop
        autoStop = null
        val finalUri = allocated?.expectedUri ?: resolvedUri
        if (finalUri == null) {
            stateSink.emit(
                CameraCoreEvent.VideoFailed(
                    CameraError.StorageFailed(allocated?.displayName, null),
                ),
            )
            return
        }

        val saved = SavedVideo(
            uri = finalUri,
            displayName = allocated?.displayName.orEmpty(),
            sizeBytes = bytes,
            durationMillis = durationMillis,
            savedAtMillis = System.currentTimeMillis(),
        )
        stateSink.emit(CameraCoreEvent.VideoSaved(saved))
        if (stopped != null) stateSink.emit(CameraCoreEvent.VideoAutoStopped(stopped))
    }

    /**
     * A missing finaliser would leave the UI stuck in "finalising" forever and keep the camera open.
     * The watchdog forces the state back to idle and reports the timeout instead of hiding it.
     */
    private fun armFinalizerWatchdog() {
        finalizerWatchdog?.cancel()
        finalizerWatchdog = scope.launch {
            delay(FINALIZER_TIMEOUT_MILLIS)
            val stillPending = synchronized(lock) { recording != null }
            if (stillPending) {
                stateSink.update { it.copy(video = VideoState.Idle) }
                stateSink.emit(CameraCoreEvent.VideoFailed(CameraError.Timeout("finalize recording", FINALIZER_TIMEOUT_MILLIS)))
            }
        }
    }

    private fun currentVideo(state: com.recordingcamera.core.CameraCoreState): VideoState? {
        val video = state.video
        return if (video is VideoState.Recording) VideoState.Finalizing(video.elapsedMillis) else null
    }

    companion object {
        const val FINALIZER_TIMEOUT_MILLIS = 15_000L
    }
}
