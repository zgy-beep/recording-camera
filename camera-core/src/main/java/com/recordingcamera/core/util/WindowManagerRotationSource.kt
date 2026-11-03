package com.recordingcamera.core.util

import android.content.Context
import android.view.Surface
import android.view.WindowManager
import com.recordingcamera.core.RotationSource

/**
 * Default [RotationSource]: the rotation of the display the process windows live on.
 *
 * Falls back to [Surface.ROTATION_0] whenever the window manager is unavailable, which is the only
 * safe assumption for headless sessions.
 */
internal class WindowManagerRotationSource(context: Context) : RotationSource {

    private val windowManager =
        context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    override fun currentRotation(): Int = try {
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
    } catch (error: Throwable) {
        Surface.ROTATION_0
    }
}
