package com.recordingcamera.core

/**
 * Static capabilities of the selected camera, resolved once per binding.
 *
 * The core publishes this through [CameraCoreState.capabilities] so callers can build their UI
 * affordances (zoom slider bounds, torch button, extension toggle) without touching CameraX.
 */
data class CameraCapabilities(
    val lens: CameraLens,
    /** Minimum zoom ratio the device reports (1.0 on most devices, below 1 on some ultra-wides). */
    val minZoomRatio: Float,
    /** Maximum digital zoom ratio. */
    val maxZoomRatio: Float,
    /** Zoom ratio the sensor natively provides (1.0 when unknown or unsupported). */
    val intrinsicZoomRatio: Float,
    val hasFlashUnit: Boolean,
    val torchAvailable: Boolean,
    val zeroShutterLagSupported: Boolean,
    val opticalStabilizationSupported: Boolean,
    val continuousAutoFocusSupported: Boolean,
    val videoStabilizationSupported: Boolean,
    /** Largest JPEG the hardware encoder can produce, in pixels; `null` when unknown. */
    val maxHardwareJpegPixels: Long?,
    /** Vendor extensions the device advertises for this lens ([ExtensionPreference.NIGHT]/[HDR]). */
    val availableExtensions: Set<ExtensionPreference> = emptySet(),
    /** Lens facings this device exposes. */
    val availableLenses: Set<CameraLens> = setOf(lens),
) {
    init {
        require(maxZoomRatio >= minZoomRatio) { "maxZoomRatio must be >= minZoomRatio" }
    }

    val isZoomSupported: Boolean get() = maxZoomRatio > minZoomRatio + 1e-4f
}
