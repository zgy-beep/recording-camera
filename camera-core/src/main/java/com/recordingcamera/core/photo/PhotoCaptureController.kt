package com.recordingcamera.core.photo

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import com.recordingcamera.core.CameraCoreEvent
import com.recordingcamera.core.CameraError
import com.recordingcamera.core.CameraLens
import com.recordingcamera.core.MediaDestination
import com.recordingcamera.core.PhotoConfig
import com.recordingcamera.core.PhotoState
import com.recordingcamera.core.RotationSource
import com.recordingcamera.core.internal.CoreStateSink
import com.recordingcamera.core.storage.PendingPhotoJournal
import com.recordingcamera.core.storage.PhotoSinks
import com.recordingcamera.core.storage.pendingCaptureDirectory
import com.recordingcamera.core.util.MediaNaming
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Owns the still image pipeline: shutter → temp file → journal → storage sink → event.
 *
 * Two properties matter for a camera and both are enforced here rather than by callers:
 *  - nothing is lost. A capture is journalled before `takePicture`, so a process death leaves a
 *    recoverable record instead of an orphaned JPEG;
 *  - captures stay ordered. Saves run through a single worker coroutine, so a slow MediaStore
 *    commit cannot interleave with the next photo.
 */
internal class PhotoCaptureController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mainExecutor: Executor,
    private val stateSink: CoreStateSink,
    private val journal: PendingPhotoJournal,
    private val budget: PendingCaptureBudget,
    private val configProvider: () -> PhotoConfig,
    private val lensProvider: () -> CameraLens,
    private val imageCaptureProvider: () -> ImageCapture?,
    private val rotationSource: RotationSource,
) {

    private class SaveTask(
        val id: String,
        val temporaryFile: File,
        val displayName: String,
        val capturedAtMillis: Long,
        val lens: CameraLens,
        val destination: MediaDestination,
        val recovered: Boolean = false,
    ) {
        var bytes: Long = 0L
    }

    private val saveQueue = Channel<SaveTask>(Channel.UNLIMITED)
    private val queuedCount = AtomicInteger(0)
    private val failedLock = Any()
    private val failedTasks = mutableListOf<SaveTask>()

    init {
        scope.launch(Dispatchers.IO) {
            for (task in saveQueue) {
                persist(task)
            }
        }
    }

    /** Number of captures that reached the sensor but are not persisted yet. */
    val inFlight: Int get() = queuedCount.get() + budget.pending

    /**
     * Stops accepting new captures. Already queued saves still run to completion, because the worker
     * drains the channel after it is closed — dropping them here would lose photos the user saw the
     * shutter for.
     */
    fun close() {
        saveQueue.close()
    }

    fun capture(): Result<Unit> {
        val imageCapture = imageCaptureProvider()
            ?: return Result.failure(CameraError.NotReady("photo use case is not bound"))
        val config = configProvider()
        if (!budget.tryAcquire()) {
            return Result.failure(CameraError.QueueSaturated(budget.pending, budget.pendingBytes))
        }

        val lens = lensProvider()
        val timestamp = System.currentTimeMillis()
        val displayName = MediaNaming.photoName(timestamp)
        val temporaryFile = File(pendingCaptureDirectory(context), "$displayName.part")
        val id = UUID.randomUUID().toString()
        val task = SaveTask(id, temporaryFile, displayName, timestamp, lens, config.destination)

        try {
            temporaryFile.parentFile?.mkdirs()
            journal.record(
                PendingPhotoJournal.Entry(
                    id = id,
                    temporaryPath = temporaryFile.absolutePath,
                    displayName = displayName,
                    capturedAtMillis = timestamp,
                    lensName = lens.name,
                    stage = PendingPhotoJournal.Stage.CAPTURED,
                ),
            )
        } catch (error: Throwable) {
            budget.release()
            return Result.failure(CameraError.StorageFailed(displayName, error))
        }

        imageCapture.flashMode = config.flashMode.imageCaptureMode
        imageCapture.targetRotation = rotationSource.currentRotation()
        val metadata = ImageCapture.Metadata().apply {
            // Front camera preview is mirrored, so the file has to be mirrored as well or the photo
            // does not match what the user saw.
            isReversedHorizontal = config.mirrorFrontCamera && lens == CameraLens.FRONT
        }
        val options = ImageCapture.OutputFileOptions.Builder(temporaryFile)
            .setMetadata(metadata)
            .build()

        return try {
            imageCapture.takePicture(
                options,
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        onCaptured(task)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        onCaptureFailed(task, exception)
                    }
                },
            )
            publish()
            Result.success(Unit)
        } catch (error: Throwable) {
            journal.remove(id)
            budget.release()
            publish()
            Result.failure(CameraError.CaptureFailed(error))
        }
    }

    /**
     * Re-queues leftovers found in the journal. Called from [com.recordingcamera.core.CameraCore.open]
     * so a photo that survived a process death still lands in the gallery.
     */
    fun recoverPending() {
        val entries = journal.load()
        if (entries.isEmpty()) return
        val destination = configProvider().destination
        val fallbackLens = lensProvider()

        entries.forEach { entry ->
            val file = File(entry.temporaryPath)
            if (!file.exists() || file.length() == 0L) {
                // The capture never completed (or the temp file was cleaned up): drop the record.
                journal.remove(entry.id)
                return@forEach
            }
            if (!budget.tryAcquire()) return@forEach
            val lens = CameraLens.values().firstOrNull { it.name == entry.lensName } ?: fallbackLens
            val task = SaveTask(
                id = entry.id,
                temporaryFile = file,
                displayName = entry.displayName,
                capturedAtMillis = entry.capturedAtMillis,
                lens = lens,
                destination = destination,
                recovered = true,
            )
            task.bytes = file.length()
            budget.addBytes(task.bytes)
            queuedCount.incrementAndGet()
            if (saveQueue.trySend(task).isFailure) {
                queuedCount.decrementAndGet()
                budget.release(task.bytes)
            }
        }
        publish()
    }

    /** Retries every save that failed (revoked SAF grant, full disk, ...). Returns the retry count. */
    fun retryFailedSaves(): Int {
        val tasks = synchronized(failedLock) {
            failedTasks.toList().also { failedTasks.clear() }
        }
        tasks.forEach(::enqueue)
        return tasks.size
    }

    val failedSaveCount: Int get() = synchronized(failedLock) { failedTasks.size }

    private fun onCaptured(task: SaveTask) {
        task.bytes = task.temporaryFile.length()
        runCatching { journal.markSaving(task.id) }
        budget.addBytes(task.bytes)
        enqueue(task)
    }

    private fun onCaptureFailed(task: SaveTask, exception: ImageCaptureException) {
        journal.remove(task.id)
        budget.release(task.bytes)
        publish()
        stateSink.emit(
            CameraCoreEvent.PhotoSaveFailed(
                task.displayName,
                CameraError.CaptureFailed(exception),
            ),
        )
    }

    private fun enqueue(task: SaveTask) {
        queuedCount.incrementAndGet()
        if (saveQueue.trySend(task).isFailure) {
            queuedCount.decrementAndGet()
            recordFailure(task)
        } else {
            publish()
        }
    }

    private suspend fun persist(task: SaveTask) {
        try {
            val sink = PhotoSinks.create(context, task.destination)
            val photo = sink.store(
                source = task.temporaryFile,
                displayName = task.displayName,
                lens = task.lens,
                capturedAtMillis = task.capturedAtMillis,
            )
            journal.remove(task.id)
            queuedCount.decrementAndGet()
            budget.release(task.bytes)
            stateSink.emit(
                if (task.recovered) {
                    CameraCoreEvent.RecoveredPhoto(photo)
                } else {
                    CameraCoreEvent.PhotoSaved(photo)
                },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // Keep the temp file and the journal entry: the next open() retries the copy.
            queuedCount.decrementAndGet()
            budget.release(task.bytes)
            recordFailure(task)
            stateSink.emit(
                CameraCoreEvent.PhotoSaveFailed(
                    task.displayName,
                    CameraError.StorageFailed(task.displayName, error),
                ),
            )
        } finally {
            publish()
        }
    }

    private fun recordFailure(task: SaveTask) {
        synchronized(failedLock) {
            if (failedTasks.none { it.id == task.id }) failedTasks += task
        }
    }

    private fun publish() {
        val busy = budget.pending > 0 || queuedCount.get() > 0
        stateSink.update { current ->
            current.copy(
                photo = if (busy) {
                    PhotoState.Busy(pending = budget.pending, queued = queuedCount.get())
                } else {
                    PhotoState.Idle
                },
            )
        }
    }
}
