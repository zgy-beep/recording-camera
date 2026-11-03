package com.recordingcamera.core

/**
 * Typed failures of the camera core.
 *
 * Every public API that can fail returns `Result<T>` carrying one of these instead of a formatted
 * string. That keeps error handling exhaustive for callers and makes the messages stable for logs.
 */
sealed class CameraError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The session has not reached [CameraStatus.READY] yet, or is currently rebinding. */
    class NotReady(val detail: String = "camera session is not ready") : CameraError(detail)

    /** A runtime permission required for the requested operation is missing. */
    class PermissionMissing(val permission: String) :
        CameraError("permission $permission is not granted")

    /** Another app (or another [CameraCore]) owns the camera right now. */
    class InUse(val lens: CameraLens? = null, cause: Throwable? = null) : CameraError(
        "camera${lens?.let { " for lens ${it.name}" } ?: ""} is in use by another client",
        cause,
    )

    /** The camera disappeared mid-session (USB/removable camera, device handoff, thermal shutdown). */
    class Disconnected(cause: Throwable? = null) :
        CameraError("camera disconnected", cause)

    /** The destination cannot hold the capture (free space, quota, or a revoked SAF grant). */
    class StorageInsufficient(val displayName: String? = null, cause: Throwable? = null) :
        CameraError("not enough space to store ${displayName ?: "media"}", cause)

    /** No camera exists for the requested lens facing. */
    class CameraUnavailable(val lens: CameraLens, cause: Throwable? = null) :
        CameraError("no camera available for lens ${lens.name}", cause)

    /** CameraX refused every use-case combination we tried. */
    class BindFailed(val attempts: Int, cause: Throwable? = null) :
        CameraError("camera binding failed after $attempts attempt(s)", cause)

    /** The session was closed and cannot serve requests anymore. */
    class SessionClosed : CameraError("camera session is closed")

    /** `takePicture` reported an error. */
    class CaptureFailed(cause: Throwable? = null) :
        CameraError("still capture failed${cause?.let { ": ${it.message}" } ?: ""}", cause)

    /** Too many stills are pending or their combined size exceeds the configured budget. */
    class QueueSaturated(val pending: Int, val bytes: Long) :
        CameraError("still capture queue is saturated ($pending pending, $bytes bytes)")

    /** Writing to the configured destination failed. */
    class StorageFailed(val displayName: String?, cause: Throwable? = null) :
        CameraError("failed to store ${displayName ?: "media"}", cause)

    /** Recording could not start, or terminated with an error. */
    class RecordingFailed(cause: Throwable? = null) :
        CameraError("video recording failed${cause?.let { ": ${it.message}" } ?: ""}", cause)

    /** An awaited platform future did not complete in time. */
    class Timeout(
        val operation: String,
        val millis: Long,
        cause: Throwable? = null,
    ) : CameraError("$operation timed out after $millis ms", cause)

    /** The device cannot do what was asked (e.g. torch on a camera without a flash unit). */
    class Unsupported(val detail: String) : CameraError(detail)

    /** Requested operation is illegal in the current state (e.g. capture while closed). */
    class InvalidState(val detail: String) : CameraError(detail)
}

/**
 * Whether retrying the same call can plausibly succeed without the user doing anything.
 *
 * Transient platform conditions (another app holding the camera, a stalled future, a full disk that
 * just got cleaned up) are recoverable; a missing permission, a closed session or a device without
 * the hardware are not. Hosts use this to decide between a "retry" button and an explanation.
 */
val CameraError.isRecoverable: Boolean
    get() = when (this) {
        is CameraError.NotReady,
        is CameraError.InUse,
        is CameraError.Disconnected,
        is CameraError.BindFailed,
        is CameraError.CaptureFailed,
        is CameraError.RecordingFailed,
        is CameraError.QueueSaturated,
        is CameraError.StorageFailed,
        is CameraError.StorageInsufficient,
        is CameraError.Timeout,
        -> true

        is CameraError.PermissionMissing,
        is CameraError.SessionClosed,
        is CameraError.CameraUnavailable,
        is CameraError.Unsupported,
        is CameraError.InvalidState,
        -> false
    }
