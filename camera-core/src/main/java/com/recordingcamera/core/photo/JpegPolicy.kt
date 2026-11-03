package com.recordingcamera.core.photo

import com.recordingcamera.core.PhotoConfig
import com.recordingcamera.core.PhotoQuality

/**
 * Pure JPEG / capture mode policy.
 *
 * Keeping it free of CameraX types makes the trade-off table unit testable: speed favours latency,
 * quality favours a bigger file, zero shutter lag sits between them but is only legal when the
 * device and the flash state allow it.
 */
internal object JpegPolicy {

    const val QUALITY_PRIORITY = 100
    const val QUALITY_ZERO_SHUTTER_LAG = 95
    const val QUALITY_DEFAULT = 98

    fun jpegQuality(config: PhotoConfig, zeroShutterLag: Boolean): Int =
        config.jpegQualityOverride ?: when {
            config.quality == PhotoQuality.QUALITY -> QUALITY_PRIORITY
            zeroShutterLag -> QUALITY_ZERO_SHUTTER_LAG
            else -> QUALITY_DEFAULT
        }

    /**
     * ZSL requires `CAPTURE_MODE_ZERO_SHUTTER_LAG`; quality priority requires
     * `CAPTURE_MODE_MAXIMIZE_QUALITY`; everything else wants the shortest possible shutter.
     */
    fun captureMode(quality: PhotoQuality, zeroShutterLag: Boolean): Int = when {
        zeroShutterLag && quality != PhotoQuality.QUALITY -> ZERO_SHUTTER_LAG
        quality == PhotoQuality.QUALITY -> MAXIMIZE_QUALITY
        else -> MINIMIZE_LATENCY
    }

    // Mirrors androidx.camera.core.ImageCapture so this policy stays a pure Kotlin file.
    const val MINIMIZE_LATENCY = 0
    const val MAXIMIZE_QUALITY = 1
    const val ZERO_SHUTTER_LAG = 2

    /**
     * Hardware JPEG is much faster and, on high resolution sensors, the only way to reach the full
     * sensor size (software JPEG is usually capped around 8 MP). Ask for it unless the caller
     * explicitly opted out.
     */
    fun requestHardwareJpeg(config: PhotoConfig): Boolean = config.preferHardwareJpeg

    fun shouldUseSoftwareJpegEncoder(config: PhotoConfig): Boolean = !requestHardwareJpeg(config)
}
