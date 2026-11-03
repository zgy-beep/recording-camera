package com.recordingcamera.core

import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture

/**
 * Physical camera the core is asked to drive.
 *
 * The enum is deliberately small: a lens facing is the only camera identity that is stable across
 * devices, and it is what [androidx.camera.core.CameraSelector] actually consumes. Devices that
 * expose several back cameras (ultra wide, telephoto) are handled by the platform through logical
 * multi-camera zoom ranges, not by picking a physical id here.
 */
enum class CameraLens(internal val lensFacing: Int) {
    BACK(CameraSelector.LENS_FACING_BACK),
    FRONT(CameraSelector.LENS_FACING_FRONT);

    internal fun selector(): CameraSelector = CameraSelector.Builder()
        .requireLensFacing(lensFacing)
        .build()
}

/** Flash behaviour for still capture. Mirrors [ImageCapture] flash modes plus an unset value. */
enum class FlashMode {
    OFF,
    AUTO,
    ON;

    internal val imageCaptureMode: Int
        get() = when (this) {
            OFF -> ImageCapture.FLASH_MODE_OFF
            AUTO -> ImageCapture.FLASH_MODE_AUTO
            ON -> ImageCapture.FLASH_MODE_ON
        }

    internal companion object {
        fun fromImageCaptureMode(mode: Int): FlashMode = when (mode) {
            ImageCapture.FLASH_MODE_AUTO -> AUTO
            ImageCapture.FLASH_MODE_ON -> ON
            else -> OFF
        }
    }
}

/** Output aspect ratio shared by the still and video pipelines. */
enum class AspectRatio {
    RATIO_4_3,
    RATIO_16_9;

    /** Width divided by height, in sensor orientation (landscape). */
    val ratio: Float
        get() = when (this) {
            RATIO_4_3 -> 4f / 3f
            RATIO_16_9 -> 16f / 9f
        }

    internal val cameraXAspectRatio: Int
        get() = when (this) {
            RATIO_4_3 -> androidx.camera.core.AspectRatio.RATIO_4_3
            RATIO_16_9 -> androidx.camera.core.AspectRatio.RATIO_16_9
        }
}

/** Trade-off between capture latency and pixel count for stills. */
enum class PhotoQuality {
    /** Smallest latencies, moderate resolution. Good for bursts and snapshots. */
    SPEED,

    /** Balanced default: full sensor pixels when the hardware encoder can handle them. */
    BALANCED,

    /** Maximum detail: forces the quality-priority capture pipeline. */
    QUALITY,
}

/** How a still image is stored. */
sealed interface MediaDestination {
    /** App-private storage; the resulting URI is a `file://` URI owned by this app. */
    data object AppPrivate : MediaDestination

    /**
     * Public gallery through MediaStore on API 29+, legacy public directory + media scanner below.
     * [relativePath] is the folder under the primary external volume, e.g. `Pictures/MyApp`.
     */
    data class Gallery(val relativePath: String) : MediaDestination

    /**
     * User-picked storage access framework tree (`ACTION_OPEN_DOCUMENT_TREE` result).
     * Files are created inside [folderName] below the tree root.
     */
    data class DocumentTree(val treeUri: android.net.Uri, val folderName: String = "RecordingCamera") :
        MediaDestination
}

/** Preferred camera extension (OEM night/HDR pipelines). */
enum class ExtensionPreference {
    /** Never use vendor extensions; always the standard CameraX pipeline. */
    OFF,

    /** Use the night extension when the device offers it. */
    NIGHT,

    /** Use the HDR extension when the device offers it. */
    HDR,

    /** Prefer night, fall back to HDR, then to the standard pipeline. */
    AUTO,
}

/** Where a still image is written. */
data class PhotoConfig @JvmOverloads constructor(
    val destination: MediaDestination = MediaDestination.Gallery("Pictures/RecordingCamera"),
    val aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
    val quality: PhotoQuality = PhotoQuality.BALANCED,
    val flashMode: FlashMode = FlashMode.OFF,
    /** Mirror front-camera stills horizontally (what users expect from a selfie). */
    val mirrorFrontCamera: Boolean = true,
    /** Upper bound of still captures that may be in flight (captured but not yet persisted). */
    val maxPendingCaptures: Int = 8,
    /** Upper bound of bytes held by in-flight captures (temp files + queued saves). */
    val maxPendingBytes: Long = 128L * 1024 * 1024,
    /**
     * When true (default) the capture target size is clamped to the JPEG size the hardware encoder
     * can actually produce, so no software JPEG round-trip is needed. When false the software
     * encoder is requested for sizes above the hardware limit.
     */
    val preferHardwareJpeg: Boolean = true,
    /** Optional JPEG quality override in `1..100`. */
    val jpegQualityOverride: Int? = null,
)

/** Video quality buckets, mapped onto [androidx.camera.video.Quality]. */
enum class VideoQuality { LOWEST, SD, HD, FHD, UHD, HIGHEST }

/** Video stabilisation policy. */
enum class StabilizationMode {
    /** Never request stabilisation. */
    OFF,

    /** Request stabilisation when the device reports support, otherwise record normally. */
    AUTO,

    /**
     * Request stabilisation and constrain the profile (quality/fps) so that it stays inside the
     * stabilisation envelope CameraX documents; see [com.recordingcamera.core.video.VideoStabilizationPolicy].
     */
    REQUIRE,
}

/** Where a recording is written. */
data class VideoConfig @JvmOverloads constructor(
    val destination: MediaDestination = MediaDestination.Gallery("Movies/RecordingCamera"),
    val aspectRatio: AspectRatio = AspectRatio.RATIO_16_9,
    val quality: VideoQuality = VideoQuality.HIGHEST,
    /** Explicit capture frame rate; `null` lets the encoder profile decide. */
    val frameRate: Int? = null,
    /** Explicit encoder bit rate in bits per second; `null` uses the device profile. */
    val bitRate: Int? = null,
    val audioEnabled: Boolean = true,
    val stabilization: StabilizationMode = StabilizationMode.AUTO,
    /** Automatically stop the recording after this many milliseconds. */
    val maxDurationMillis: Long? = null,
    /** Automatically stop the recording after this many recorded bytes. */
    val maxFileBytes: Long? = null,
)

/**
 * Supplies the current display rotation as a [Surface] rotation constant.
 *
 * CameraX needs the display rotation to tag captures with the right EXIF orientation and to keep
 * the viewfinder upright. Apps usually derive it from their window; foreground services and
 * headless use cases cannot, which is why this is injectable.
 */
fun interface RotationSource {
    fun currentRotation(): Int

    companion object {
        /** Rotation of `ROTATION_0`; useful for tests and headless sessions. */
        val Zero: RotationSource = RotationSource { Surface.ROTATION_0 }
    }
}

/** Full configuration of one [CameraCore] session. */
data class CameraCoreConfig @JvmOverloads constructor(
    val lens: CameraLens = CameraLens.BACK,
    val extension: ExtensionPreference = ExtensionPreference.OFF,
    val photo: PhotoConfig = PhotoConfig(),
    val video: VideoConfig = VideoConfig(),
    /** Bind [androidx.camera.video.VideoCapture] together with the photo use case. */
    val bindVideoUseCase: Boolean = true,
    /**
     * Bind a no-op [androidx.camera.core.ImageAnalysis] use case alongside preview and capture.
     * Some devices only keep AE/AF converged while an analysis stream is active.
     */
    val keepAnalysisStreamForAutofocus: Boolean = true,
    /** Display rotation source; `null` uses the default display of the process window manager. */
    val rotationSource: RotationSource? = null,
    val openTimeoutMillis: Long = 5_000,
    val extensionTimeoutMillis: Long = 3_000,
)
