package com.recordingcamera.core.internal

import android.hardware.camera2.CameraCharacteristics
import android.annotation.SuppressLint
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import com.recordingcamera.core.AspectRatio
import com.recordingcamera.core.FlashMode
import com.recordingcamera.core.PhotoConfig
import com.recordingcamera.core.photo.JpegPolicy
import com.recordingcamera.core.photo.PhotoTargetSize
import com.recordingcamera.core.photo.PixelSize

/**
 * Builds the CameraX use cases with explicit resolutions.
 *
 * Leaving the viewfinder resolution to CameraX means 640x480 and a visibly soft preview, so the
 * target sizes are stated here and the fallback rule is left to pick the closest real mode.
 */
internal object UseCaseFactory {

    private val PREVIEW_4_3 = Size(1440, 1080)
    private val PREVIEW_16_9 = Size(1920, 1080)

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    fun preview(
        aspect: AspectRatio,
        rotation: Int,
        sessionCallback: android.hardware.camera2.CameraCaptureSession.CaptureCallback? = null,
    ): Preview {
        val builder = Preview.Builder()
            .setResolutionSelector(
                resolutionSelector(
                    aspect = aspect,
                    target = if (aspect == AspectRatio.RATIO_4_3) PREVIEW_4_3 else PREVIEW_16_9,
                ),
            )
            .setTargetRotation(rotation)
        // AF_STATE is only observable through a Camera2 session callback; the preview use case is the
        // one that runs for the whole session, so the callback is installed here.
        if (sessionCallback != null) {
            Camera2Interop.Extender<Preview>(builder).setSessionCaptureCallback(sessionCallback)
        }
        return builder.build()
    }

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    // `setSoftwareJpegEncoderRequested` is group-restricted in CameraX but is the documented way to
    // avoid the 8 MP software-JPEG ceiling on devices with a hardware encoder.
    @SuppressLint("RestrictedApi")
    fun imageCapture(
        config: PhotoConfig,
        profile: CaptureProfile,
        rotation: Int,
        extensionActive: Boolean,
        useSoftwareJpeg: Boolean,
    ): ImageCapture {
        val zeroShutterLag = profile.zeroShutterLag
        val builder = ImageCapture.Builder()
            .setCaptureMode(JpegPolicy.captureMode(config.quality, zeroShutterLag))
            .setJpegQuality(JpegPolicy.jpegQuality(config, zeroShutterLag))
            .setSoftwareJpegEncoderRequested(useSoftwareJpeg)
            // A Night/HDR extension owns the request pipeline; a flash request would conflict.
            .setFlashMode(
                if (extensionActive) FlashMode.OFF.imageCaptureMode else config.flashMode.imageCaptureMode,
            )
            .setResolutionSelector(
                resolutionSelector(
                    aspect = config.aspectRatio,
                    target = PhotoTargetSize.captureTarget(config.aspectRatio, config.quality).toSize(),
                    // Never round *up* for a still: the next larger mode on a high resolution sensor
                    // is its unbinned readout, which is slower and visibly softer than the binned one.
                    fallback = ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .setTargetRotation(rotation)

        if (!extensionActive) {
            val extender = Camera2Interop.Extender<ImageCapture>(builder)
            profile.frameRate?.let { fps ->
                extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(fps.first, fps.last),
                )
            }
            if (profile.opticalStabilization) {
                extender.setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON,
                )
            }
            // Deliberately no CONTROL_AF_MODE here. Pinning CONTINUOUS_PICTURE on the still request
            // makes the capture request re-enter a continuous scan instead of using the focus lock the
            // user (or CameraX's own precapture sequence) just established, so the frame is often
            // sampled mid-hunt and looks soft. Letting CameraX own 3A fixed most of the "blurry
            // photo" reports.
            if (profile.highQualityNoiseReduction) {
                extender.setCaptureRequestOption(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CameraCharacteristics.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                )
            }
            if (profile.highQualityEdge) {
                extender.setCaptureRequestOption(
                    CaptureRequest.EDGE_MODE,
                    CameraCharacteristics.EDGE_MODE_HIGH_QUALITY,
                )
            }
        }
        return builder.build()
    }

    /**
     * Analysis exists only to keep AE/AF converging on pipelines where nothing else drives the
     * control loop (no ZSL, extension enabled, video idle). Frames are dropped immediately.
     */
    fun analysis(): ImageAnalysis =
        ImageAnalysis.Builder()
            .setResolutionSelector(
                resolutionSelector(AspectRatio.RATIO_4_3, Size(640, 480)),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

    private fun resolutionSelector(
        aspect: AspectRatio,
        target: Size,
        fallback: Int = ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
    ): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                AspectRatioStrategy(aspect.cameraXAspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO),
            )
            .setResolutionStrategy(ResolutionStrategy(target, fallback))
            .build()

    private fun PixelSize.toSize(): Size = Size(width, height)
}
