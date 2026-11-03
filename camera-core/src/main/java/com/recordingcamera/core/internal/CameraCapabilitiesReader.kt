package com.recordingcamera.core.internal

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.video.Recorder
import com.recordingcamera.core.CameraCapabilities
import com.recordingcamera.core.CameraLens
import com.recordingcamera.core.ExtensionPreference

/**
 * Reads what the device can actually do, once per bind.
 *
 * Callers must not assume any of these values: the same app runs on devices without OIS, without
 * ZSL, without video stabilisation and without extensions, and every one of those is a normal case
 * rather than an error.
 */
internal object CameraCapabilitiesReader {

    @OptIn(markerClass = [ExperimentalCamera2Interop::class, ExperimentalZeroShutterLag::class])
    fun read(
        cameraInfo: CameraInfo,
        lens: CameraLens,
        extensions: Set<ExtensionPreference>,
    ): CameraCapabilities {
        val camera2 = runCatching { Camera2CameraInfo.from(cameraInfo) }.getOrNull()

        fun <T> characteristic(key: CameraCharacteristics.Key<T>): T? =
            runCatching { camera2?.getCameraCharacteristic(key) }.getOrNull()

        val zoomState = cameraInfo.zoomState.value
        val minZoom = zoomState?.minZoomRatio ?: 1f
        val maxZoom = zoomState?.maxZoomRatio ?: 1f

        // `INTRINSIC_ZOOM_RATIO_UNKNOWN` is a restricted constant; an unknown ratio is simply 1x.
        val intrinsicZoom = runCatching { cameraInfo.intrinsicZoomRatio }
            .getOrDefault(1f)
            .takeIf { it.isFinite() && it > 0f } ?: 1f

        val opticalStabilization = characteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true

        val continuousAutofocus = characteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.contains(CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true

        val maxJpegPixels = runCatching {
            camera2?.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
                ?.maxOfOrNull { it.width.toLong() * it.height.toLong() }
        }.getOrNull()

        val stabilizationModes = characteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        val stabilizationByCharacteristic = stabilizationModes
            ?.contains(CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
        val stabilizationByRecorder = runCatching {
            Recorder.getVideoCapabilities(cameraInfo).isStabilizationSupported
        }.getOrDefault(false)

        val hasFlash = runCatching { cameraInfo.hasFlashUnit() }.getOrDefault(false)

        return CameraCapabilities(
            lens = lens,
            minZoomRatio = minZoom,
            maxZoomRatio = maxZoom,
            intrinsicZoomRatio = intrinsicZoom,
            hasFlashUnit = hasFlash,
            torchAvailable = hasFlash,
            zeroShutterLagSupported = runCatching { cameraInfo.isZslSupported }.getOrDefault(false),
            opticalStabilizationSupported = opticalStabilization,
            continuousAutoFocusSupported = continuousAutofocus,
            videoStabilizationSupported = stabilizationByCharacteristic || stabilizationByRecorder,
            maxHardwareJpegPixels = maxJpegPixels,
            availableExtensions = extensions,
            availableLenses = setOf(lens),
        )
    }

    /**
     * Frame rate ranges the sensor advertises. Used by [CaptureProfileFactory] to raise the shutter
     * rate in speed mode instead of guessing a range the device does not support.
     */
    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    fun frameRateRanges(cameraInfo: CameraInfo): List<IntRange> = runCatching {
        Camera2CameraInfo.from(cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.lower..it.upper }
    }.getOrNull().orEmpty()
}
