package com.recordingcamera.core.internal

import com.recordingcamera.core.AspectRatio
import com.recordingcamera.core.CameraCoreConfig
import com.recordingcamera.core.CameraLens
import com.recordingcamera.core.ExtensionPreference
import com.recordingcamera.core.FlashMode
import com.recordingcamera.core.MediaDestination
import com.recordingcamera.core.PhotoQuality
import com.recordingcamera.core.StabilizationMode
import com.recordingcamera.core.VideoQuality
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDiffTest {

    private val base = CameraCoreConfig()

    @Test
    fun `flash mode and destination do not cost a rebind`() {
        assertFalse(ConfigDiff.requiresRebind(base, base.copy(photo = base.photo.copy(flashMode = FlashMode.ON))))
        assertFalse(
            ConfigDiff.requiresRebind(
                base,
                base.copy(photo = base.photo.copy(destination = MediaDestination.AppPrivate)),
            ),
        )
        assertFalse(
            ConfigDiff.requiresRebind(
                base,
                base.copy(video = base.video.copy(destination = MediaDestination.Gallery("Movies/Other"))),
            ),
        )
        assertFalse(ConfigDiff.requiresRebind(base, base.copy(video = base.video.copy(bitRate = 30_000_000))))
        assertFalse(ConfigDiff.requiresRebind(base, base.copy(video = base.video.copy(audioEnabled = false))))
        assertFalse(ConfigDiff.requiresRebind(base, base.copy(video = base.video.copy(maxDurationMillis = 5_000))))
    }

    @Test
    fun `an identical config never rebinds`() {
        assertFalse(ConfigDiff.requiresRebind(base, base.copy()))
        assertTrue(ConfigDiff.liveApplicable(base, base.copy()))
    }

    @Test
    fun `a lens switch rebinds`() {
        assertTrue(ConfigDiff.requiresRebind(base, base.copy(lens = CameraLens.FRONT)))
    }

    @Test
    fun `an extension change rebinds`() {
        assertTrue(ConfigDiff.requiresRebind(base, base.copy(extension = ExtensionPreference.NIGHT)))
    }

    @Test
    fun `still profile changes rebind because the use case resolves them at build time`() {
        assertTrue(ConfigDiff.requiresRebind(base, base.copy(photo = base.photo.copy(quality = PhotoQuality.SPEED))))
        assertTrue(
            ConfigDiff.requiresRebind(
                base,
                base.copy(photo = base.photo.copy(aspectRatio = AspectRatio.RATIO_16_9)),
            ),
        )
        assertTrue(
            ConfigDiff.requiresRebind(base, base.copy(photo = base.photo.copy(jpegQualityOverride = 90))),
        )
        assertTrue(
            ConfigDiff.requiresRebind(base, base.copy(photo = base.photo.copy(preferHardwareJpeg = false))),
        )
    }

    @Test
    fun `in flight budgets rebind because the controller owns one budget instance`() {
        assertTrue(ConfigDiff.requiresRebind(base, base.copy(photo = base.photo.copy(maxPendingCaptures = 2))))
        assertTrue(
            ConfigDiff.requiresRebind(base, base.copy(photo = base.photo.copy(maxPendingBytes = 1L))),
        )
    }

    @Test
    fun `video profile changes rebind`() {
        assertTrue(ConfigDiff.requiresRebind(base, base.copy(video = base.video.copy(quality = VideoQuality.FHD))))
        assertTrue(
            ConfigDiff.requiresRebind(
                base,
                base.copy(video = base.video.copy(aspectRatio = AspectRatio.RATIO_4_3)),
            ),
        )
        assertTrue(
            ConfigDiff.requiresRebind(
                base,
                base.copy(video = base.video.copy(stabilization = StabilizationMode.OFF)),
            ),
        )
        assertTrue(ConfigDiff.requiresRebind(base, base.copy(video = base.video.copy(frameRate = 60))))
    }

    @Test
    fun `the bind set itself rebinds`() {
        assertTrue(ConfigDiff.requiresRebind(base, base.copy(bindVideoUseCase = false)))
        assertTrue(ConfigDiff.requiresRebind(base, base.copy(keepAnalysisStreamForAutofocus = false)))
    }
}
