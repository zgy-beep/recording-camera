package com.recordingcamera.core.video

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.OutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import android.util.Range
import com.recordingcamera.core.MediaDestination
import com.recordingcamera.core.VideoConfig
import com.recordingcamera.core.VideoQuality
import com.recordingcamera.core.storage.fileUri
import java.io.File
import java.io.IOException

/**
 * Builds the CameraX video objects and reserves wherever the MP4 has to land.
 *
 * Output allocation is separated from recording on purpose: the destination has to exist *before*
 * `start()` (CameraX hands us a file descriptor or a MediaStore row, not a path), and the caller must
 * be able to learn the final URI only after the finalizer ran.
 */
internal object VideoOutputFactory {

    fun createRecorder(
        config: VideoConfig,
        decision: VideoStabilizationPolicy.Decision,
        cameraInfo: CameraInfo?,
    ): Recorder {
        val builder = Recorder.Builder()
        builder.setAspectRatio(config.aspectRatio.cameraXAspectRatio)

        val quality = decision.quality.toCameraxQuality()
        val selector = if (decision.stabilizationEnabled) {
            // Stabilised modes are published for the smaller qualities; ordering FHD first keeps
            // the promise instead of falling back to an unstabilised HIGHEST recording.
            QualitySelector.fromOrderedList(
                listOf(Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
            )
        } else {
            QualitySelector.from(quality, FallbackStrategy.higherQualityOrLowerThan(quality))
        }
        builder.setQualitySelector(selector)
        // Explicit bit rate matters more than resolution for a moving scene: CameraX derives a
        // "messaging-friendly" default from the quality, which turns foliage, plates and road texture
        // into mush. The table below is the high-quality bucket of the AVC level tables.
        val bitRate = config.bitRate?.takeIf { it > 0 } ?: defaultBitRate(decision.quality)
        builder.setTargetVideoEncodingBitRate(bitRate)
        return builder.build()
    }

    internal fun defaultBitRate(quality: VideoQuality): Int = when (quality) {
        VideoQuality.LOWEST -> 4_000_000
        VideoQuality.SD -> 6_000_000
        VideoQuality.HD -> 12_000_000
        VideoQuality.FHD -> 20_000_000
        VideoQuality.UHD -> 45_000_000
        VideoQuality.HIGHEST -> 45_000_000
    }

    /**
     * Caps the exposure time by pinning a frame rate floor.
     *
     * Motion blur is an exposure time problem, not a resolution problem: with a 15..30 range AE is
     * free to stretch the exposure to 1/15 s whenever the light drops, and everything that moves
     * smears. A 30 fps floor bounds the exposure at ~33 ms, which is the usable limit for a moving
     * camera, at the cost of a little more gain noise.
     */
    internal fun motionFrameRateRange(ranges: List<IntRange>): IntRange? {
        val usable = ranges.filter { it.first >= MOTION_FPS_FLOOR }
        if (usable.isEmpty()) return null
        // Highest usable ceiling: it shortens the longest exposure the sensor may pick, which is what
        // motion blur actually comes from. On a tie the narrowest range wins, so the AE has the least
        // room to wander.
        return usable.maxWithOrNull(
            compareBy<IntRange> { it.last }.thenBy { -(it.last - it.first) },
        )
    }

    const val MOTION_FPS_FLOOR = 30

    /**
     * Maps a requested frame rate onto a range the sensor actually advertises.
     *
     * `setTargetFrameRate(Range(fps, fps))` throws when the pair is not one of the advertised
     * ranges, which is how a "record at 60 fps" setting used to kill the whole recording. Asking the
     * sensor for a range it published always works; when the request cannot be honoured exactly the
     * closest advertised range is used and the caller still gets video at a sane rate.
     */
    internal fun clampToSupported(fps: Int, ranges: List<IntRange>): IntRange? {
        if (fps <= 0) return null
        val exact = ranges.firstOrNull { fps in it }
        if (exact != null) return fps..fps
        return ranges.minByOrNull { range ->
            when {
                fps < range.first -> range.first - fps
                fps > range.last -> fps - range.last
                else -> 0
            }
        }
    }

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    fun createVideoCapture(
        recorder: Recorder,
        config: VideoConfig,
        decision: VideoStabilizationPolicy.Decision,
        rotation: Int,
        opticalStabilizationSupported: Boolean,
        frameRateRanges: List<IntRange>,
    ): VideoCapture<Recorder> {
        val builder = VideoCapture.Builder(recorder).setTargetRotation(rotation)
        if (decision.stabilizationEnabled) {
            builder.setVideoStabilizationEnabled(true)
        }

        // Video never asked for OIS: the electronic stabiliser crops and shifts the frame, which
        // hides shake but also softens detail and cannot fix motion blur. Turning the lens stabiliser
        // on as well is the cheapest sharpness win available here.
        val extender = Camera2Interop.Extender<VideoCapture<Recorder>>(builder)
        if (opticalStabilizationSupported) {
            extender.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON,
            )
        }
        // Every frame rate request goes through the advertised ranges instead of
        // `setTargetFrameRate`, which throws on a pair the device does not publish.
        val requested = decision.frameRate?.takeIf { it > 0 }
        val capped = requested?.let { clampToSupported(it, frameRateRanges) }
            ?: motionFrameRateRange(frameRateRanges)
        capped?.let { range ->
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(range.first, range.last),
            )
        }
        return builder.build()
    }

    fun allocate(context: Context, destination: MediaDestination, displayName: String): AllocatedVideoOutput =
        when (destination) {
            is MediaDestination.AppPrivate -> AppPrivateOutput(
                File(context.filesDir, "camera-core/videos/$displayName"),
            )

            is MediaDestination.Gallery -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStoreOutput(context, displayName, destination.normalizedRelativePath())
            } else {
                LegacyGalleryOutput(
                    context,
                    File(Environment.getExternalStorageDirectory(), "${destination.normalizedRelativePath()}/$displayName"),
                )
            }

            is MediaDestination.DocumentTree -> DocumentTreeOutput.create(
                context.contentResolver,
                destination.treeUri,
                destination.folderName,
                displayName,
            )
        }

    private fun MediaDestination.Gallery.normalizedRelativePath(): String =
        relativePath.trim().trim('/').ifEmpty { "Movies/RecordingCamera" }

    private fun VideoQuality.toCameraxQuality(): Quality = when (this) {
        VideoQuality.LOWEST -> Quality.LOWEST
        VideoQuality.SD -> Quality.SD
        VideoQuality.HD -> Quality.HD
        VideoQuality.FHD -> Quality.FHD
        VideoQuality.UHD -> Quality.UHD
        VideoQuality.HIGHEST -> Quality.HIGHEST
    }
}

/** A reserved recording destination that knows how to finalise or roll itself back. */
internal sealed class AllocatedVideoOutput {
    abstract val options: OutputOptions
    abstract val displayName: String

    /** The URI the caller should publish if the finaliser resolves nothing better. */
    abstract val expectedUri: Uri?

    abstract fun finalize(success: Boolean, resolvedUri: Uri?)
}

internal class AppPrivateOutput(private val file: File) : AllocatedVideoOutput() {
    override val options: OutputOptions = FileOutputOptions.Builder(file).build()
    override val displayName: String = file.name
    override val expectedUri: Uri get() = fileUri(file)

    override fun finalize(success: Boolean, resolvedUri: Uri?) {
        if (!success) file.delete()
    }
}

internal class LegacyGalleryOutput(
    private val context: Context,
    private val file: File,
) : AllocatedVideoOutput() {
    override val options: OutputOptions = FileOutputOptions.Builder(file).build()
    override val displayName: String = file.name
    override val expectedUri: Uri get() = fileUri(file)

    override fun finalize(success: Boolean, resolvedUri: Uri?) {
        if (!success) {
            file.delete()
            return
        }
        // Pre-Q there is no MediaStore row until the file is scanned.
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("video/mp4"),
            null,
        )
    }
}

internal class MediaStoreOutput(
    private val context: Context,
    override val displayName: String,
    relativePath: String,
) : AllocatedVideoOutput() {

    override val options: OutputOptions = MediaStoreOutputOptions.Builder(
        context.contentResolver,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
    ).setContentValues(
        ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            // Hidden from other apps until the finaliser says the file is complete.
            put(MediaStore.Video.Media.IS_PENDING, 1)
        },
    ).build()

    override val expectedUri: Uri? = null

    override fun finalize(success: Boolean, resolvedUri: Uri?) {
        val uri = resolvedUri ?: return
        if (!success) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            return
        }
        runCatching {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null,
            )
        }
    }

    private companion object {
        const val MIME_TYPE = "video/mp4"
    }
}

internal class DocumentTreeOutput private constructor(
    private val resolver: ContentResolver,
    private val documentUri: Uri,
    private val descriptor: ParcelFileDescriptor,
) : AllocatedVideoOutput() {

    override val options: OutputOptions = FileDescriptorOutputOptions.Builder(descriptor).build()
    override val displayName: String = documentUri.lastPathSegment?.substringAfterLast(':') ?: "video.mp4"
    override val expectedUri: Uri get() = documentUri

    override fun finalize(success: Boolean, resolvedUri: Uri?) {
        runCatching { descriptor.close() }
        if (!success) runCatching { DocumentsContract.deleteDocument(resolver, documentUri) }
    }

    companion object {
        private const val MIME_TYPE = "video/mp4"
        private const val MIME_TYPE_DIR = DocumentsContract.Document.MIME_TYPE_DIR

        fun create(
            resolver: ContentResolver,
            treeUri: Uri,
            folderName: String,
            displayName: String,
        ): AllocatedVideoOutput {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
            val folderUri = findOrCreateFolder(resolver, treeUri, rootUri, folderName)
            val documentUri = DocumentsContract.createDocument(resolver, folderUri, MIME_TYPE, displayName)
                ?: throw IOException("could not create $displayName in $folderName")
            val descriptor = resolver.openFileDescriptor(documentUri, "rw")
                ?: run {
                    runCatching { DocumentsContract.deleteDocument(resolver, documentUri) }
                    throw IOException("could not open $displayName for writing")
                }
            return DocumentTreeOutput(resolver, documentUri, descriptor)
        }

        private fun findOrCreateFolder(
            resolver: ContentResolver,
            treeUri: Uri,
            parentUri: Uri,
            name: String,
        ): Uri {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parentUri))
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0)
                    val displayName = cursor.getString(1)
                    val mimeType = cursor.getString(2)
                    if (displayName == name && mimeType == MIME_TYPE_DIR) {
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    }
                }
            }
            return DocumentsContract.createDocument(resolver, parentUri, MIME_TYPE_DIR, name)
                ?: throw IOException("could not create folder $name")
        }
    }
}
