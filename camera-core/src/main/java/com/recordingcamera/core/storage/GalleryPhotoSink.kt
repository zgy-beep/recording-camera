package com.recordingcamera.core.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.recordingcamera.core.CameraError
import com.recordingcamera.core.CameraLens
import com.recordingcamera.core.MediaDestination
import com.recordingcamera.core.SavedPhoto
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stores stills in app-private storage: `filesDir/photos`. */
internal class InternalPhotoSink(private val context: Context) : PhotoSink {

    override suspend fun store(
        source: File,
        displayName: String,
        lens: CameraLens,
        capturedAtMillis: Long,
    ): SavedPhoto = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "photos").apply { mkdirs() }
        val target = File(directory, displayName)
        source.moveToAtomically(target)
        val (width, height) = MediaProbe.imageSize(target)
        SavedPhoto(
            uri = fileUri(target),
            displayName = displayName,
            sizeBytes = target.length(),
            width = width,
            height = height,
            capturedAtMillis = capturedAtMillis,
            lens = lens,
            file = target,
        )
    }

    override suspend fun discard(photo: SavedPhoto) {
        withContext(Dispatchers.IO) {
            photo.file?.delete()
            if (photo.uri.scheme == "file") photo.uri.path?.let { File(it).delete() }
        }
    }
}

/**
 * Stores stills in the shared gallery.
 *
 * API 29+: MediaStore insert with `IS_PENDING`, which makes the file invisible to other apps until
 * the bytes are complete. Below 29 the file is written to the public directory and handed to the
 * media scanner, which is the only way to make it appear in the gallery there.
 */
internal class GalleryPhotoSink(
    private val context: Context,
    relativePath: String,
) : PhotoSink {

    private val relativePath = relativePath.trim('/')

    override suspend fun store(
        source: File,
        displayName: String,
        lens: CameraLens,
        capturedAtMillis: Long,
    ): SavedPhoto = withContext(Dispatchers.IO) {
        val (width, height) = MediaProbe.imageSize(source)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            storeViaMediaStore(source, displayName, lens, capturedAtMillis, width, height)
        } else {
            storeViaPublicDirectory(source, displayName, lens, capturedAtMillis, width, height)
        }
    }

    private fun storeViaMediaStore(
        source: File,
        displayName: String,
        lens: CameraLens,
        capturedAtMillis: Long,
        width: Int,
        height: Int,
    ): SavedPhoto {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.DATE_TAKEN, capturedAtMillis)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw CameraError.StorageFailed(displayName, IOException("MediaStore insert returned null"))

        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            } ?: throw IOException("could not open $uri for writing")

            val published = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, published, null, null)
            source.delete()
        } catch (error: Throwable) {
            // Roll the pending row back: a half written pending entry would be invisible forever.
            runCatching { resolver.delete(uri, null, null) }
            throw CameraError.StorageFailed(displayName, error)
        }

        return SavedPhoto(
            uri = uri,
            displayName = displayName,
            sizeBytes = runCatching {
                resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            }.getOrDefault(0L),
            width = width,
            height = height,
            capturedAtMillis = capturedAtMillis,
            lens = lens,
        )
    }

    @Suppress("DEPRECATION")
    private fun storeViaPublicDirectory(
        source: File,
        displayName: String,
        lens: CameraLens,
        capturedAtMillis: Long,
        width: Int,
        height: Int,
    ): SavedPhoto {
        val root = Environment.getExternalStorageDirectory()
        val directory = File(root, relativePath).apply { mkdirs() }
        if (!directory.isDirectory) {
            throw CameraError.StorageFailed(displayName, IOException("cannot create $directory"))
        }
        val target = File(directory, displayName)
        source.moveToAtomically(target)

        // Scanned URIs arrive asynchronously; the file is already complete, so publish it right away
        // and let the scanner update the gallery index.
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf(MIME_TYPE),
            null,
        )
        return SavedPhoto(
            uri = fileUri(target),
            displayName = displayName,
            sizeBytes = target.length(),
            width = width,
            height = height,
            capturedAtMillis = capturedAtMillis,
            lens = lens,
            file = target,
        )
    }

    /** Deletes the MediaStore row for a previously stored photo. */
    override suspend fun discard(photo: SavedPhoto) = withContext(Dispatchers.IO) {
        runCatching {
            if (photo.uri.scheme == "content") {
                val id = ContentUris.parseId(photo.uri)
                val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                context.contentResolver.delete(
                    ContentUris.withAppendedId(collection, id),
                    null,
                    null,
                )
            } else {
                photo.uri.path?.let { File(it).delete() }
            }
        }
        Unit
    }

    companion object {
        const val MIME_TYPE = "image/jpeg"
    }
}

/**
 * Moves a file into place, preferring an in-place rename and copying only across volumes.
 * Shared by every sink that stages a capture in the pending directory first.
 */
internal fun File.moveToAtomically(target: File) {
    if (!exists()) throw IOException("pending capture $this disappeared")
    if (target.exists()) target.delete()
    target.parentFile?.mkdirs()
    if (!renameTo(target)) {
        target.outputStream().use { output ->
            inputStream().use { input -> input.copyTo(output) }
        }
        delete()
    }
}
