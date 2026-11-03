package com.recordingcamera.core.internal

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import com.recordingcamera.core.ExtensionPreference
import com.recordingcamera.core.util.awaitValue

/** Result of asking the extensions service whether Night/HDR can drive this camera. */
internal data class ExtensionResolution(
    val mode: ExtensionPreference,
    val selector: CameraSelector,
    val imageAnalysisSupported: Boolean,
)

/**
 * Resolves camera extensions (Night / HDR) with a hard timeout.
 *
 * The extensions service is a separate, bindable component: on devices where it is missing, broken
 * or simply slow it can take seconds or never answer. Camera start-up therefore never waits on it
 * without a deadline — an unavailable extension degrades to the standard pipeline instead of
 * blocking the preview.
 */
internal class ExtensionsResolver(
    private val context: Context,
    private val timeoutMillis: Long,
) {

    /** Which extension modes this camera reports as available; empty when the service is absent. */
    suspend fun availableModes(
        provider: ProcessCameraProvider,
        baseSelector: CameraSelector,
    ): Set<ExtensionPreference> {
        val manager = manager(provider) ?: return emptySet()
        return buildSet {
            if (isAvailable(manager, baseSelector, ExtensionMode.NIGHT)) add(ExtensionPreference.NIGHT)
            if (isAvailable(manager, baseSelector, ExtensionMode.HDR)) add(ExtensionPreference.HDR)
        }
    }

    suspend fun resolve(
        provider: ProcessCameraProvider,
        baseSelector: CameraSelector,
        preference: ExtensionPreference,
    ): ExtensionResolution {
        if (preference == ExtensionPreference.OFF) {
            return ExtensionResolution(ExtensionPreference.OFF, baseSelector, imageAnalysisSupported = true)
        }

        val manager = manager(provider) ?: return fallback(baseSelector)

        candidateModes(preference).forEach { mode ->
            if (!isAvailable(manager, baseSelector, mode)) return@forEach

            val selector = runCatching { manager.getExtensionEnabledCameraSelector(baseSelector, mode) }
                .getOrNull() ?: return@forEach

            val analysisSupported = runCatching { manager.isImageAnalysisSupported(selector, mode) }
                .getOrDefault(false)

            return ExtensionResolution(
                mode = mode.toPreference(),
                selector = selector,
                imageAnalysisSupported = analysisSupported,
            )
        }
        return fallback(baseSelector)
    }

    private suspend fun manager(provider: ProcessCameraProvider): ExtensionsManager? =
        runCatching {
            ExtensionsManager.getInstanceAsync(context, provider)
                .awaitValue(timeoutMillis, "extensions manager")
        }.getOrNull()

    private fun isAvailable(manager: ExtensionsManager, selector: CameraSelector, mode: Int): Boolean =
        runCatching { manager.isExtensionAvailable(selector, mode) }.getOrDefault(false)

    private fun fallback(baseSelector: CameraSelector) =
        ExtensionResolution(ExtensionPreference.OFF, baseSelector, imageAnalysisSupported = true)

    private fun candidateModes(preference: ExtensionPreference): List<Int> = when (preference) {
        ExtensionPreference.NIGHT -> listOf(ExtensionMode.NIGHT)
        ExtensionPreference.HDR -> listOf(ExtensionMode.HDR)
        // Night first: it is the one users expect from "auto" on modern devices.
        ExtensionPreference.AUTO -> listOf(ExtensionMode.NIGHT, ExtensionMode.HDR)
        ExtensionPreference.OFF -> emptyList()
    }

    private fun Int.toPreference(): ExtensionPreference = when (this) {
        ExtensionMode.NIGHT -> ExtensionPreference.NIGHT
        ExtensionMode.HDR -> ExtensionPreference.HDR
        else -> ExtensionPreference.OFF
    }
}
