package com.recordingcamera.core

import android.net.Uri

/** Lifecycle of one [CameraCore] session. */
enum class CameraStatus {
    /** Nothing has been bound yet. */
    IDLE,

    /** CameraX binding in progress (initial open, lens switch or flash reconfigure). */
    OPENING,

    /** Preview/photo/video use cases are bound and the core accepts requests. */
    READY,

    /** Opening failed; see [CameraCoreState.error]. The core can still be retried with [CameraCore.open]. */
    FAILED,

    /** [CameraCore.close] ran; the instance is unusable. */
    CLOSED,
}

/** Use cases that ended up bound. Devices may not support the requested combination. */
enum class CoreUseCase { PREVIEW, PHOTO, VIDEO }

/** Reason a focus attempt ended without a lock. */
enum class FocusFailure {
    /** The AF algorithm reported a terminal "not focused" state. */
    NOT_FOCUSED,

    /** No terminal AF state arrived before the timeout. */
    TIMEOUT,

    /** A newer request superseded this one, or focus was cancelled. */
    SUPERSEDED,

    /** The camera exposes no auto focus. */
    UNSUPPORTED,
}

/** Result of the most recent tap-to-focus attempt. */
sealed interface FocusState {
    data object Idle : FocusState
    data object Focusing : FocusState
    data object Focused : FocusState
    data class Failed(val reason: FocusFailure) : FocusState
}

/** Still-capture pipeline state. */
sealed interface PhotoState {
    data object Idle : PhotoState

    /**
     * @param pending captures currently being written by CameraX
     * @param queued captures already written and waiting to be persisted by the storage sink
     */
    data class Busy(val pending: Int, val queued: Int) : PhotoState
}

/** Recording state machine, driven by [androidx.camera.video.VideoRecordEvent]s. */
sealed interface VideoState {
    data object Idle : VideoState
    data object Preparing : VideoState

    data class Recording(
        val elapsedMillis: Long,
        val bytesRecorded: Long,
        val paused: Boolean,
    ) : VideoState

    data class Finalizing(val elapsedMillis: Long) : VideoState
}

/** Auto-stop triggers reported by [CameraCoreEvent.VideoAutoStopped]. */
enum class AutoStopReason { MAX_DURATION, MAX_FILE_SIZE }

/** Immutable snapshot of everything a UI needs to render the camera. */
data class CameraCoreState(
    val status: CameraStatus = CameraStatus.IDLE,
    val lens: CameraLens = CameraLens.BACK,
    val capabilities: CameraCapabilities? = null,
    val availableUseCases: Set<CoreUseCase> = emptySet(),
    val zoomRatio: Float = 1f,
    val zoomRatioRange: ClosedFloatingPointRange<Float> = 1f..1f,
    val focus: FocusState = FocusState.Idle,
    val flashMode: FlashMode = FlashMode.OFF,
    val torchEnabled: Boolean = false,
    val activeExtension: ExtensionPreference? = null,
    val photo: PhotoState = PhotoState.Idle,
    val video: VideoState = VideoState.Idle,
    val lastPhoto: SavedPhoto? = null,
    val lastVideo: SavedVideo? = null,
    val error: CameraError? = null,
) {
    val isReady: Boolean get() = status == CameraStatus.READY

    val isRecording: Boolean get() = video is VideoState.Recording || video is VideoState.Preparing

    val canCapturePhoto: Boolean get() = isReady && CoreUseCase.PHOTO in availableUseCases

    /**
     * Zoom as a `0..1` slider position, the inverse of
     * [com.recordingcamera.core.CameraCore.setZoomFraction].
     *
     * Follows both programmatic zoom and pinch gestures, so a slider bound to this property stays in
     * sync without the caller tracking the ratio itself.
     */
    val linearZoom: Float
        get() = com.recordingcamera.core.zoom.ZoomMath.fractionForRatio(
            zoomRatioRange.start,
            zoomRatioRange.endInclusive,
            zoomRatio,
        )

    /** True while the video pipeline is mid-finalize (the file is not on disk yet). */
    val isFinalizing: Boolean get() = video is VideoState.Finalizing
}

/** A still image that has been persisted. */
data class SavedPhoto(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val capturedAtMillis: Long,
    val lens: CameraLens,
    /** Backing file for [MediaDestination.AppPrivate] destinations, otherwise `null`. */
    val file: java.io.File? = null,
)

/** A recording that has been finalised. */
data class SavedVideo(
    val uri: Uri,
    val displayName: String?,
    val sizeBytes: Long,
    val durationMillis: Long,
    val savedAtMillis: Long,
)

/** One-shot notifications for things a state snapshot cannot express. */
sealed interface CameraCoreEvent {
    data class PhotoSaved(val photo: SavedPhoto) : CameraCoreEvent

    /** A still was captured but could not be persisted; [displayName] may be unknown. */
    data class PhotoSaveFailed(val displayName: String?, val error: CameraError) : CameraCoreEvent

    data class VideoSaved(val video: SavedVideo) : CameraCoreEvent
    data class VideoFailed(val error: CameraError) : CameraCoreEvent
    data class VideoAutoStopped(val reason: AutoStopReason) : CameraCoreEvent

    /** Session level failure that is also mirrored into [CameraCoreState.error]. */
    data class SessionFailed(val error: CameraError) : CameraCoreEvent

    /** A still that was captured before the process died has been recovered on open. */
    data class RecoveredPhoto(val photo: SavedPhoto) : CameraCoreEvent
}
