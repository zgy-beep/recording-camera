package com.recordingcamera.core.internal

import com.recordingcamera.core.CameraCoreConfig

/**
 * Decides whether a configuration change can be applied to the live session or needs a rebind.
 *
 * `open()` costs a hundred milliseconds and a visible preview blink, so a host that only changes the
 * flash mode or the destination folder must not pay for it. Everything that the CameraX use cases
 * resolve at build time (lens, extension, quality buckets, bind set, budgets) does need one.
 */
internal object ConfigDiff {

    fun requiresRebind(old: CameraCoreConfig, new: CameraCoreConfig): Boolean =
        old.lens != new.lens ||
            old.extension != new.extension ||
            old.bindVideoUseCase != new.bindVideoUseCase ||
            old.keepAnalysisStreamForAutofocus != new.keepAnalysisStreamForAutofocus ||
            old.photo.quality != new.photo.quality ||
            old.photo.aspectRatio != new.photo.aspectRatio ||
            old.photo.preferHardwareJpeg != new.photo.preferHardwareJpeg ||
            old.photo.jpegQualityOverride != new.photo.jpegQualityOverride ||
            old.photo.maxPendingCaptures != new.photo.maxPendingCaptures ||
            old.photo.maxPendingBytes != new.photo.maxPendingBytes ||
            old.video.quality != new.video.quality ||
            old.video.aspectRatio != new.video.aspectRatio ||
            old.video.stabilization != new.video.stabilization ||
            old.video.frameRate != new.video.frameRate

    /**
     * Changes that are safe to apply on a live session: flash mode, mirror flag, destination, JPEG
     * override, bit rate, audio and the auto-stop limits are all read per capture or per recording,
     * so no use case has to be rebuilt. The in-flight budgets do need a rebind because the
     * controller owns one budget instance for its whole life.
     */
    fun liveApplicable(old: CameraCoreConfig, new: CameraCoreConfig): Boolean = !requiresRebind(old, new)
}
