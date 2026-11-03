package com.recordingcamera.core.internal

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.recordingcamera.core.AspectRatio
import com.recordingcamera.core.CameraCapabilities
import com.recordingcamera.core.CameraCoreConfig
import com.recordingcamera.core.CameraError
import com.recordingcamera.core.CoreUseCase
import com.recordingcamera.core.PhotoConfig
import com.recordingcamera.core.photo.JpegPolicy
import com.recordingcamera.core.photo.PhotoTargetSize
import com.recordingcamera.core.photo.PixelSize
import com.recordingcamera.core.video.VideoOutputFactory
import com.recordingcamera.core.video.VideoStabilizationPolicy

/**
 * Binds preview + photo + video (and the optional analysis keep-alive stream) in one session.
 *
 * All three use cases are bound together on purpose: rebinding per mode is what makes camera apps
 * flicker, lose zoom and drop the first frame of a recording. The degrade chain exists because some
 * devices reject three or four concurrent use cases — the session then falls back to a smaller set
 * instead of failing outright.
 */
internal class CameraBinder(private val context: Context) {

    data class BoundSession(
        val camera: Camera,
        val preview: Preview,
        val imageCapture: ImageCapture?,
        val analysis: ImageAnalysis?,
        val videoCapture: VideoCapture<Recorder>?,
        val useCases: Set<CoreUseCase>,
    )

    suspend fun bind(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        selector: CameraSelector,
        config: CameraCoreConfig,
        capabilities: CameraCapabilities,
        profile: CaptureProfile,
        decision: VideoStabilizationPolicy.Decision,
        rotation: Int,
        cameraInfo: CameraInfo,
        frameRateRanges: List<IntRange>,
        extensionActive: Boolean,
        analysisSupported: Boolean,
        focusCallback: android.hardware.camera2.CameraCaptureSession.CaptureCallback? = null,
    ): BoundSession {
        val attempts = buildList {
            add(Attempt(withAnalysis = config.keepAnalysisStreamForAutofocus && analysisSupported, withVideo = config.bindVideoUseCase, explicitPreview = true))
            add(Attempt(withAnalysis = false, withVideo = config.bindVideoUseCase, explicitPreview = true))
            add(Attempt(withAnalysis = false, withVideo = false, explicitPreview = true))
            // Last resort: let CameraX pick the preview resolution when the device rejects the
            // requested size on every earlier attempt.
            add(Attempt(withAnalysis = false, withVideo = false, explicitPreview = false))
        }.distinct()

        var lastError: Throwable? = null
        var attemptIndex = 0

        for (attempt in attempts) {
            attemptIndex++
            val useCases = mutableListOf<UseCase>()
            val preview = if (attempt.explicitPreview) {
                UseCaseFactory.preview(config.photo.aspectRatio, rotation, focusCallback)
            } else {
                Preview.Builder().setTargetRotation(rotation).build()
            }
            useCases += preview

            val imageCapture = UseCaseFactory.imageCapture(
                config = config.photo,
                profile = profile,
                rotation = rotation,
                extensionActive = extensionActive,
                useSoftwareJpeg = !PhotoTargetSize.fitsHardwareJpeg(
                    target = PixelTarget.of(config.photo, capabilities),
                    maxHardwarePixels = capabilities.maxHardwareJpegPixels,
                ) || JpegPolicy.shouldUseSoftwareJpegEncoder(config.photo),
            )
            useCases += imageCapture

            val analysis = if (attempt.withAnalysis) {
                UseCaseFactory.analysis().also { useCase ->
                    useCase.setAnalyzer(ContextCompat.getMainExecutor(context)) { image -> image.close() }
                }
            } else {
                null
            }
            analysis?.let { useCases += it }

            val videoCapture = if (attempt.withVideo) {
                val recorder = VideoOutputFactory.createRecorder(config.video, decision, cameraInfo)
                VideoOutputFactory.createVideoCapture(
                    recorder = recorder,
                    config = config.video,
                    decision = decision,
                    rotation = rotation,
                    opticalStabilizationSupported = capabilities.opticalStabilizationSupported,
                    frameRateRanges = frameRateRanges,
                )
            } else {
                null
            }
            videoCapture?.let { useCases += it }

            try {
                val camera = provider.bindToLifecycle(lifecycleOwner, selector, *useCases.toTypedArray())
                return BoundSession(
                    camera = camera,
                    preview = preview,
                    imageCapture = imageCapture,
                    analysis = analysis,
                    videoCapture = videoCapture,
                    useCases = buildSet {
                        add(CoreUseCase.PREVIEW)
                        add(CoreUseCase.PHOTO)
                        if (videoCapture != null) add(CoreUseCase.VIDEO)
                    },
                )
            } catch (error: Throwable) {
                lastError = error
                runCatching { provider.unbindAll() }
            }
        }

        throw CameraError.BindFailed(attemptIndex, lastError)
    }

    private data class Attempt(
        val withAnalysis: Boolean,
        val withVideo: Boolean,
        val explicitPreview: Boolean,
    )

    /** Small helper that maps the photo configuration onto the size the sensor is asked for. */
    private object PixelTarget {
        fun of(config: PhotoConfig, capabilities: CameraCapabilities): PixelSize {
            val base = PhotoTargetSize.captureTarget(config.aspectRatio, config.quality)
            return PhotoTargetSize.clampToHardware(base, capabilities.maxHardwareJpegPixels)
        }
    }
}
