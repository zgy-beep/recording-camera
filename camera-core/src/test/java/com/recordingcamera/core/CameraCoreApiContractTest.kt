package com.recordingcamera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the shape of the public surface.
 *
 * The library is consumed from several hosts at once, so a dropped or renamed member is a compile
 * break somewhere else. This test is deliberately about *presence and arity*, not about behaviour:
 * behaviour belongs in the unit tests of the controller that owns it.
 */
class CameraCoreApiContractTest {

    private val methods = CameraCore::class.java.methods

    private fun has(name: String, parameterCount: Int): Boolean =
        methods.any { it.name == name && it.parameterTypes.size == parameterCount }

    @Test
    fun `session lifecycle is reachable`() {
        assertTrue("open", methods.any { it.name == "open" })
        assertTrue("switchLens", methods.any { it.name == "switchLens" })
        assertTrue("updateConfig", methods.any { it.name == "updateConfig" })
        assertTrue("close", has("close", 0))
    }

    @Test
    fun `still capture surface is complete`() {
        assertTrue("capture", has("capture", 0))
        assertTrue("getPendingPhotos", methods.any { it.name == "getPendingPhotos" })
        assertTrue("retryFailedPhotoSaves", has("retryFailedPhotoSaves", 0))
        assertTrue("setFlashMode", has("setFlashMode", 1))
    }

    @Test
    fun `video surface is complete`() {
        assertTrue("startRecording", has("startRecording", 0))
        assertTrue("stopRecording", has("stopRecording", 0))
        assertTrue("pauseRecording", has("pauseRecording", 0))
        assertTrue("resumeRecording", has("resumeRecording", 0))
    }

    @Test
    fun `zoom and focus surface is complete`() {
        assertTrue("setZoomRatio", has("setZoomRatio", 1))
        assertTrue("setZoomFraction", has("setZoomFraction", 1))
        assertTrue("zoomBy", has("zoomBy", 1))
        assertTrue("stepZoom", has("stepZoom", 1))
        assertTrue("cancelZoomAnimation", has("cancelZoomAnimation", 0))
        assertTrue("cancelFocus", has("cancelFocus", 0))
        assertTrue(
            "focusAt",
            methods.any { it.name == "focusAt" && it.parameterTypes.first() == Float::class.javaPrimitiveType },
        )
    }

    @Test
    fun `torch reports whether the request was accepted`() {
        val torch = methods.first { it.name == "setTorch" }

        assertEquals(1, torch.parameterTypes.size)
        // Result<Unit> is a value class, so the JVM signature returns Object rather than void.
        assertEquals(Any::class.java, torch.returnType)
    }

    @Test
    fun `preview and metering wiring is reachable`() {
        assertTrue("setPreviewSurfaceProvider", has("setPreviewSurfaceProvider", 1))
        assertTrue("setMeteringPointFactory", has("setMeteringPointFactory", 1))
        assertTrue("updateRotation", has("updateRotation", 0))
    }

    @Test
    fun `permission helpers are static so Java callers do not need an instance`() {
        val static = CameraCore::class.java.methods.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }

        assertTrue(static.any { it.name == "requiredPermissions" })
        assertTrue(static.any { it.name == "missingPermissions" })
        assertTrue(static.any { it.name == "isSupported" })
    }

    @Test
    fun `configs expose javadoc friendly overloads`() {
        // `@JvmOverloads` materialises one constructor per trailing default group; a single
        // constructor means a Java host would have to pass every default by hand.
        assertTrue(PhotoConfig::class.java.constructors.size > 1)
        assertTrue(VideoConfig::class.java.constructors.size > 1)
        assertTrue(CameraCoreConfig::class.java.constructors.size > 1)
    }

    @Test
    fun `state exposes derived render helpers`() {
        val state = CameraCoreState::class.java.methods.map { it.name }

        assertTrue(state.contains("getLinearZoom"))
        assertTrue(state.contains("isReady"))
        assertTrue(state.contains("isRecording"))
        assertTrue(state.contains("getCanCapturePhoto"))
        assertTrue(state.contains("isFinalizing"))
    }

    @Test
    fun `errors stay exhaustive over recoverability`() {
        val samples = listOf(
            CameraError.NotReady(),
            CameraError.PermissionMissing(android.Manifest.permission.CAMERA),
            CameraError.InUse(),
            CameraError.Disconnected(),
            CameraError.CameraUnavailable(CameraLens.BACK),
            CameraError.BindFailed(1),
            CameraError.SessionClosed(),
            CameraError.CaptureFailed(),
            CameraError.QueueSaturated(1, 1L),
            CameraError.StorageFailed(null),
            CameraError.StorageInsufficient(),
            CameraError.RecordingFailed(),
            CameraError.Timeout("bind", 10L),
            CameraError.Unsupported("no torch"),
            CameraError.InvalidState("closed"),
        )

        samples.forEach { error ->
            assertTrue("${error::class.simpleName} must render a message", error.message!!.isNotBlank())
        }
        assertTrue(CameraError.PermissionMissing("x").isRecoverable.not())
        assertTrue(CameraError.InUse().isRecoverable)
        assertTrue(CameraError.SessionClosed().isRecoverable.not())
    }
}
