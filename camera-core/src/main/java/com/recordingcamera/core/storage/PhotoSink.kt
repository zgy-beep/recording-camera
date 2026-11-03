package com.recordingcamera.core.storage

import android.content.Context
import android.net.Uri
import com.recordingcamera.core.CameraLens
import com.recordingcamera.core.MediaDestination
import com.recordingcamera.core.SavedPhoto
import java.io.File

/**
 * Destination for still images.
 *
 * Implement this to plug the core into an app specific library (encrypted vault, cloud upload, ...)
 * without touching the capture pipeline. Implementations must be safe to call from a background
 * thread and must move (or copy and delete) [source]: the core deletes the temp file after a
 * successful [store].
 */
interface PhotoSink {
    suspend fun store(
        source: File,
        displayName: String,
        lens: CameraLens,
        capturedAtMillis: Long,
    ): SavedPhoto

    /** Best effort removal of a previously stored image. */
    suspend fun discard(photo: SavedPhoto) = Unit
}

/** Builds the sink for a [MediaDestination]; unknown document trees fall back to app storage. */
internal object PhotoSinks {
    fun create(context: Context, destination: MediaDestination): PhotoSink = when (destination) {
        MediaDestination.AppPrivate -> InternalPhotoSink(context)
        is MediaDestination.Gallery -> GalleryPhotoSink(context, destination.relativePath)
        is MediaDestination.DocumentTree -> DocumentTreePhotoSink(context, destination)
    }
}

/** Directory used for captures that are not persisted yet. */
internal fun pendingCaptureDirectory(context: Context): File =
    File(context.filesDir, "camera-core/pending").apply { mkdirs() }

/** Reads the pixel dimensions of a JPEG without decoding it. */
internal object MediaProbe {
    fun imageSize(file: File): Pair<Int, Int> {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        val width = options.outWidth.coerceAtLeast(0)
        val height = options.outHeight.coerceAtLeast(0)
        return width to height
    }
}

internal fun fileUri(file: File): Uri = Uri.fromFile(file)
