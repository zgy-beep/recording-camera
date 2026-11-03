package com.recordingcamera.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import androidx.camera.core.CameraSelector
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview.SurfaceProvider
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.recordingcamera.core.internal.CameraBinder
import com.recordingcamera.core.internal.CameraCapabilitiesReader
import com.recordingcamera.core.internal.CaptureProfile
import com.recordingcamera.core.internal.CaptureProfileFactory
import com.recordingcamera.core.internal.ConfigDiff
import com.recordingcamera.core.internal.CoreStateSink
import com.recordingcamera.core.internal.ExtensionsResolver
import com.recordingcamera.core.photo.PendingCaptureBudget
import com.recordingcamera.core.photo.PhotoCaptureController
import com.recordingcamera.core.storage.PendingPhotoJournal
import com.recordingcamera.core.focus.FocusController
import com.recordingcamera.core.util.SimpleLifecycleOwner
import com.recordingcamera.core.util.WindowManagerRotationSource
import com.recordingcamera.core.util.awaitValue
import com.recordingcamera.core.video.VideoRecordingController
import com.recordingcamera.core.video.VideoStabilizationPolicy
import com.recordingcamera.core.zoom.ZoomController
import com.recordingcamera.core.zoom.ZoomMath
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The camera session: one CameraX binding, one zoom/focus/torch owner, one ordered event stream.
 *
 * Typical use:
 * ```
 * val core = CameraCore(context)
 * core.setPreviewSurfaceProvider(previewView.surfaceProvider)
 * core.setMeteringPointFactory(previewView.meteringPointFactory)
 * core.open(CameraCoreConfig(lens = CameraLens.BACK))
 * core.state.collect { /* render */ }
 * core.capture()
 * ```
 *
 * Threading: every public method is safe to call from the main thread. `open`, `switchLens` and
 * `focusAt` are suspending; everything else starts the work and reports through [state] and [events].
 * The class owns a main-immediate coroutine scope that survives rebinds, so collectors do not have
 * to re-subscribe when the lens or the use case set changes.
 */
class CameraCore(
    context: Context,
    initialConfig: CameraCoreConfig = CameraCoreConfig(),
) : AutoCloseable {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(appContext)
    private val lifecycleOwner = SimpleLifecycleOwner()
    private val binder = CameraBinder(appContext)

    private val _state = MutableStateFlow(CameraCoreState())
    val state: StateFlow<CameraCoreState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<CameraCoreEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<CameraCoreEvent> = _events.asSharedFlow()

    private val stateSink = object : CoreStateSink {
        override fun current(): CameraCoreState = _state.value
        override fun update(transform: (CameraCoreState) -> CameraCoreState) {
            _state.update(transform)
        }

        override fun emit(event: CameraCoreEvent) {
            // Mirror the newest artifacts into the state so a host that re-attaches (or missed the
            // event while collecting) can still show the last capture without replaying the flow.
            when (event) {
                is CameraCoreEvent.PhotoSaved -> _state.update { it.copy(lastPhoto = event.photo) }
                is CameraCoreEvent.RecoveredPhoto -> _state.update { it.copy(lastPhoto = event.photo) }
                is CameraCoreEvent.VideoSaved -> _state.update { it.copy(lastVideo = event.video) }
                else -> Unit
            }
            _events.tryEmit(event)
        }
    }

    /** Configuration of the current (or next) session. */
    var config: CameraCoreConfig = initialConfig
        private set

    private var provider: ProcessCameraProvider? = null
    private var session: CameraBinder.BoundSession? = null
    private var zoom: ZoomController? = null
    private var focus: FocusController? = null
    private var photo: PhotoCaptureController? = null
    private var video: VideoRecordingController? = null
    private var decision: VideoStabilizationPolicy.Decision =
        VideoStabilizationPolicy.Decision(false, initialConfig.video.quality, initialConfig.video.frameRate)

    private var activeRotationSource: RotationSource = RotationSource.Zero
    private var surfaceProvider: SurfaceProvider? = null
    private var meteringPointFactory: MeteringPointFactory? = null
    private var closed = false

    // ------------------------------------------------------------------------------------ session

    /**
     * Opens (or re-opens) the session and binds every use case that survived the degrade chain.
     * Failures are returned instead of thrown so callers can decide whether to retry or degrade.
     */
    @JvmName("open")
    suspend fun open(config: CameraCoreConfig = this.config): Result<Unit> {
        if (closed) return Result.failure(CameraError.SessionClosed())
        if (video?.isActive == true) {
            return Result.failure(CameraError.InvalidState("stop the active recording before rebinding"))
        }
        // Binding without the permission ends in an opaque SecurityException deep inside CameraX,
        // and sometimes in a zero-use-case session that looks "ready" but never renders.
        val missingCamera = missingPermissions(appContext, audioEnabled = false).firstOrNull()
        if (missingCamera != null) {
            val error = CameraError.PermissionMissing(missingCamera)
            this.config = config
            fail(error)
            return Result.failure(error)
        }
        this.config = config
        // A lens switch or a rebind must not throw the user's zoom away.
        val previousZoomRatio = state.value.zoomRatio
        _state.update {
            it.copy(
                status = CameraStatus.OPENING,
                lens = config.lens,
                flashMode = config.photo.flashMode,
                error = null,
            )
        }
        lifecycleOwner.resume()

        return try {
            // Drop the previous binding first: CameraX refuses a second bind for the same owner and
            // the zoom/focus controllers must not keep stale CameraControl references.
            detachControllers()
            runCatching { provider?.unbindAll() }

            val cameraProvider = obtainProvider(config)
            provider = cameraProvider
            bindSession(cameraProvider, config, previousZoomRatio)
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: CameraError) {
            fail(error)
            Result.failure(error)
        } catch (error: Throwable) {
            val wrapped = CameraError.BindFailed(0, error)
            fail(wrapped)
            Result.failure(wrapped)
        }
    }

    /** Switches to the other physical camera and rebinds; zoom is preserved and clamped. */
    @JvmName("switchLens")
    suspend fun switchLens(lens: CameraLens): Result<Unit> {
        if (lens == config.lens && state.value.status == CameraStatus.READY) return Result.success(Unit)
        return open(config.copy(lens = lens))
    }

    /**
     * Applies a new configuration, rebinding only when the request actually needs it.
     *
     * Flash mode, destination folders, mirroring, bit rate and the auto-stop limits are read per
     * capture or per recording, so they are applied in place and the preview keeps running. Anything
     * the use cases resolve when they are built (lens, extension, quality buckets, bind set, still
     * budgets) triggers a rebind through [open]. While a recording is in progress a rebind is refused
     * with [CameraError.InvalidState] instead of killing the file.
     */
    @JvmName("updateConfig")
    suspend fun updateConfig(config: CameraCoreConfig): Result<Unit> {
        if (closed) return Result.failure(CameraError.SessionClosed())
        if (ConfigDiff.requiresRebind(this.config, config)) {
            if (video?.isActive == true) {
                return Result.failure(
                    CameraError.InvalidState(
                        "stop the active recording before changing the bound profile",
                    ),
                )
            }
            return open(config)
        }
        this.config = config
        // An extension pipeline owns its own flash decision; respect it while the extension is on.
        val extensionActive = state.value.activeExtension != null
        session?.imageCapture?.flashMode =
            if (extensionActive) FlashMode.OFF.imageCaptureMode else config.photo.flashMode.imageCaptureMode
        _state.update {
            it.copy(
                lens = config.lens,
                flashMode = if (extensionActive) FlashMode.OFF else config.photo.flashMode,
                error = null,
            )
        }
        return Result.success(Unit)
    }

    companion object {

        /**
         * Permissions the core needs for the requested feature set.
         *
         * The library declares them in its own manifest, but declaring is not granting: hosts must
         * request them at runtime, and [open] / [startRecording] report [CameraError.PermissionMissing]
         * when they are absent.
         */
        @JvmStatic
        @JvmOverloads
        fun requiredPermissions(audioEnabled: Boolean = true): List<String> = buildList {
            add(Manifest.permission.CAMERA)
            if (audioEnabled) add(Manifest.permission.RECORD_AUDIO)
        }

        /** Subset of [requiredPermissions] that the process does not hold right now. */
        @JvmStatic
        @JvmOverloads
        fun missingPermissions(context: Context, audioEnabled: Boolean = true): List<String> =
            requiredPermissions(audioEnabled).filter { permission ->
                ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
            }

        /** Whether the device advertises a camera for [lens]; useful before showing a camera screen. */
        @JvmStatic
        @JvmOverloads
        fun isSupported(context: Context, lens: CameraLens = CameraLens.BACK): Boolean =
            if (lens == CameraLens.FRONT) {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
            } else {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
            }
    }

    override fun close() {
        if (closed) return
        closed = true
        video?.close()
        zoom?.close()
        detachControllers()
        runCatching { lifecycleOwner.destroy() }
        mainExecutor.execute { runCatching { provider?.unbindAll() } }
        provider = null
        session = null
        _state.update { it.copy(status = CameraStatus.CLOSED, video = VideoState.Idle, photo = PhotoState.Idle) }
        scope.cancel()
    }

    private suspend fun obtainProvider(config: CameraCoreConfig): ProcessCameraProvider =
        ProcessCameraProvider.getInstance(appContext)
            .awaitValue(config.openTimeoutMillis, "camera provider")

    private suspend fun bindSession(
        cameraProvider: ProcessCameraProvider,
        config: CameraCoreConfig,
        previousZoomRatio: Float,
    ) {
        val baseSelector = config.lens.selector()
        if (!cameraProvider.hasCamera(baseSelector)) {
            throw CameraError.CameraUnavailable(config.lens, null)
        }

        val resolver = ExtensionsResolver(appContext, config.extensionTimeoutMillis)
        val availableExtensions = resolver.availableModes(cameraProvider, baseSelector)
        val extension = resolver.resolve(cameraProvider, baseSelector, config.extension)
        val cameraInfo = cameraProvider.getCameraInfo(extension.selector)
        val capabilities = CameraCapabilitiesReader.read(cameraInfo, config.lens, availableExtensions)
            .copy(availableLenses = availableLenses(cameraProvider))

        val frameRateRanges = CameraCapabilitiesReader.frameRateRanges(cameraInfo)
        val profile: CaptureProfile = CaptureProfileFactory.build(
            quality = config.photo.quality,
            flashMode = config.photo.flashMode,
            extensionActive = extension.mode != ExtensionPreference.OFF,
            zeroShutterLagSupported = capabilities.zeroShutterLagSupported,
            opticalStabilizationSupported = capabilities.opticalStabilizationSupported,
            frameRateRanges = frameRateRanges,
        )
        decision = VideoStabilizationPolicy.resolve(
            requested = config.video.stabilization,
            deviceSupports = capabilities.videoStabilizationSupported,
            requestedQuality = config.video.quality,
            requestedFrameRate = config.video.frameRate,
        )

        val rotationSource = config.rotationSource ?: WindowManagerRotationSource(appContext)
        activeRotationSource = rotationSource
        // Created before binding so its Camera2 session callback can be installed on the preview use
        // case: that callback is the only way to observe AF_STATE and reject stale focus locks.
        val focusController = focus ?: FocusController(scope, stateSink).also { focus = it }
        focusController.setMeteringPointFactory(meteringPointFactory)

        val bound = binder.bind(
            provider = cameraProvider,
            lifecycleOwner = lifecycleOwner,
            selector = extension.selector,
            config = config,
            capabilities = capabilities,
            profile = profile,
            decision = decision,
            rotation = rotationSource.currentRotation(),
            cameraInfo = cameraInfo,
            frameRateRanges = frameRateRanges,
            extensionActive = extension.mode != ExtensionPreference.OFF,
            analysisSupported = extension.imageAnalysisSupported,
            focusCallback = focusController.captureCallback,
        )
        session = bound
        surfaceProvider?.let { bound.preview.setSurfaceProvider(it) }

        val cameraControl = bound.camera.cameraControl
        val zoomRange = android.util.Range(
            capabilities.minZoomRatio,
            capabilities.maxZoomRatio.coerceAtLeast(capabilities.minZoomRatio),
        )

        val zoomController = ZoomController(scope, stateSink)
        zoomController.attach(cameraControl, zoomRange)
        if (previousZoomRatio.isFinite() && previousZoomRatio > 0f && previousZoomRatio != 1f) {
            // Re-applies the ratio the user had before the rebind; the controller clamps it into the
            // new camera's range.
            zoomController.setZoomRatio(previousZoomRatio)
        }
        zoom = zoomController

        focusController.attach(cameraControl)

        val budget = PendingCaptureBudget(config.photo.maxPendingCaptures, config.photo.maxPendingBytes)
        photo = PhotoCaptureController(
            context = appContext,
            scope = scope,
            mainExecutor = mainExecutor,
            stateSink = stateSink,
            journal = PendingPhotoJournal.create(appContext),
            budget = budget,
            configProvider = { this.config.photo },
            lensProvider = { this.config.lens },
            imageCaptureProvider = { session?.imageCapture },
            rotationSource = rotationSource,
        ).also { it.recoverPending() }

        video = VideoRecordingController(
            context = appContext,
            scope = scope,
            mainExecutor = mainExecutor,
            stateSink = stateSink,
            configProvider = { this.config.video },
            videoCaptureProvider = { session?.videoCapture },
            decisionProvider = { decision },
            rotationSource = rotationSource,
        )

        _state.update {
            it.copy(
                status = CameraStatus.READY,
                lens = config.lens,
                capabilities = capabilities,
                availableUseCases = bound.useCases,
                zoomRatioRange = zoomRange.lower..zoomRange.upper,
                flashMode = config.photo.flashMode,
                activeExtension = extension.mode.takeIf { mode -> mode != ExtensionPreference.OFF },
                torchEnabled = false,
                error = null,
            )
        }
    }

    private fun availableLenses(cameraProvider: ProcessCameraProvider): Set<CameraLens> =
        CameraLens.values().filterTo(mutableSetOf()) { cameraProvider.hasCamera(it.selector()) }

    private fun detachControllers() {
        zoom?.detach()
        zoom?.close()
        zoom = null
        focus?.detach()
        video?.close()
        video = null
        // Closing keeps its queued saves draining; dropping the reference without closing would leave
        // a worker coroutine per rebind behind.
        photo?.close()
        photo = null
    }

    private fun fail(error: CameraError) {
        detachControllers()
        session = null
        _state.update { it.copy(status = CameraStatus.FAILED, error = error) }
        _events.tryEmit(CameraCoreEvent.SessionFailed(error))
    }

    // ------------------------------------------------------------------------------------ preview

    /** Attaches a preview surface. The provider survives rebinds and is re-applied automatically. */
    fun setPreviewSurfaceProvider(provider: SurfaceProvider?) {
        surfaceProvider = provider
        if (provider == null) {
            session?.preview?.setSurfaceProvider(null)
        } else {
            session?.preview?.setSurfaceProvider(provider)
        }
    }

    /**
     * Supplies the coordinate mapper for tap-to-focus, typically `PreviewView.meteringPointFactory`.
     * Without it [focusAt] reports [FocusFailure.UNSUPPORTED].
     */
    fun setMeteringPointFactory(factory: MeteringPointFactory?) {
        meteringPointFactory = factory
        focus?.setMeteringPointFactory(factory)
    }

    // -------------------------------------------------------------------------------------- photo

    /** Fires the shutter. Returns a failure only for requests rejected before reaching the sensor. */
    @JvmName("capture")
    fun capture(): Result<Unit> {
        val controller = photo ?: return Result.failure(CameraError.NotReady("no session is open"))
        if (state.value.status != CameraStatus.READY) {
            return Result.failure(CameraError.NotReady("camera status is ${state.value.status}"))
        }
        return controller.capture()
    }

    /** Number of stills that are captured but not persisted yet. */
    val pendingPhotos: Int get() = photo?.inFlight ?: 0

    /** Retries saves that failed earlier (revoked SAF grant, storage full, ...). */
    fun retryFailedPhotoSaves(): Int = photo?.retryFailedSaves() ?: 0

    fun setFlashMode(mode: FlashMode) {
        config = config.copy(photo = config.photo.copy(flashMode = mode))
        // ImageCapture accepts a mode change without rebinding, which keeps the preview alive.
        session?.imageCapture?.flashMode = mode.imageCaptureMode
        _state.update { it.copy(flashMode = mode) }
    }

    // -------------------------------------------------------------------------------------- video

    @JvmName("startRecording")
    fun startRecording(): Result<Unit> {
        val controller = video ?: return Result.failure(CameraError.NotReady("no session is open"))
        if (state.value.status != CameraStatus.READY) {
            return Result.failure(CameraError.NotReady("camera status is ${state.value.status}"))
        }
        if (session?.videoCapture == null) {
            return Result.failure(CameraError.NotReady("video use case is not bound on this device"))
        }
        // CameraX throws IllegalStateException from inside start() when audio is requested without
        // RECORD_AUDIO, which used to surface as a crash in the caller's coroutine.
        if (config.video.audioEnabled &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Result.failure(CameraError.PermissionMissing(Manifest.permission.RECORD_AUDIO))
        }
        return controller.start()
    }

    fun stopRecording() {
        video?.stop()
    }

    fun pauseRecording(): Boolean = video?.pause() ?: false

    fun resumeRecording(): Boolean = video?.resume() ?: false

    // ------------------------------------------------------------------------------- zoom / focus

    /** Absolute zoom (slider, buttons, restore). Applied immediately, no easing. */
    fun setZoomRatio(ratio: Float) {
        zoom?.setZoomRatio(ratio)
    }

    /** Relative zoom (pinch): eased towards `current * scale`. */
    fun zoomBy(scale: Float) {
        zoom?.zoomBy(scale)
    }

    /** Discrete zoom step in ratios, eased. */
    fun stepZoom(delta: Float) {
        zoom?.stepBy(delta)
    }

    /**
     * Re-applies the display rotation to the still capture use case, e.g. after a configuration
     * change. Video rotation is baked into the bound use case and needs [open] to change.
     */
    fun updateRotation() {
        session?.imageCapture?.targetRotation = activeRotationSource.currentRotation()
    }

    /** Cancels an in-flight zoom animation where it is. */
    fun cancelZoomAnimation() {
        zoom?.cancelAnimation()
    }

    suspend fun focusAt(x: Float, y: Float, size: Float = 0f): FocusState {
        val controller = focus ?: return FocusState.Failed(FocusFailure.UNSUPPORTED)
        return controller.focusAt(x, y, size)
    }

    fun cancelFocus() {
        focus?.cancel()
    }

    /** Torch (video light) toggle; independent from the still-capture flash mode. */
    @JvmName("setTorch")
    fun setTorch(enabled: Boolean): Result<Unit> {
        val control = session?.camera?.cameraControl
            ?: return Result.failure(CameraError.NotReady("no session is open"))
        if (state.value.capabilities?.hasFlashUnit == false) {
            return Result.failure(CameraError.Unsupported("this camera has no flash unit"))
        }
        val future = control.enableTorch(enabled)
        future.addListener(
            {
                // CameraX reports failure through the future, not through an exception.
                val applied = runCatching { future.get() }.isSuccess
                if (applied) _state.update { it.copy(torchEnabled = enabled) }
            },
            mainExecutor,
        )
        return Result.success(Unit)
    }

    /** Whether the bound camera reports a flash unit that can act as a torch. */
    val torchAvailable: Boolean get() = state.value.capabilities?.hasFlashUnit == true

    /**
     * Zoom by slider position in `0..1`, eased like [setZoomRatio].
     *
     * A position maps logarithmically onto the device range, so `1×` sits at the same place on every
     * phone instead of sliding around with the telephoto factor.
     */
    fun setZoomFraction(fraction: Float) {
        val range = state.value.zoomRatioRange
        setZoomRatio(ZoomMath.ratioForFraction(range.start, range.endInclusive, fraction))
    }

    /** Exposes the Camera2 session callback used for AF tracking (tests and advanced integrations). */
    internal val focusSessionCallback: CameraCaptureSession.CaptureCallback? get() = focus?.captureCallback
}
