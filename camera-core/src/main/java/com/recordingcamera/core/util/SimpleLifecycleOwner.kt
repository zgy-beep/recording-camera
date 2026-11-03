package com.recordingcamera.core.util

import androidx.lifecycle.Lifecycle
import android.annotation.SuppressLint
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * A [LifecycleOwner] for hosts that have no activity window, typically foreground services.
 *
 * CameraX binds use cases to a lifecycle; a plain service has none. Drive this owner from the
 * service callbacks (`onStartCommand` → [resume], `onDestroy` → [destroy]) and hand it to
 * [com.recordingcamera.core.CameraCore].
 *
 * The registry is created unsafe on purpose: services may drive it from any thread, and CameraX
 * only needs the state transitions, not main-thread enforcement.
 */
class SimpleLifecycleOwner : LifecycleOwner {

    // createUnsafe is the only factory that tolerates calls from any thread, which is exactly what a
    // background camera session needs.
    @SuppressLint("VisibleForTests")
    private val registry = LifecycleRegistry.createUnsafe(this)

    init {
        registry.currentState = Lifecycle.State.CREATED
    }

    override val lifecycle: Lifecycle get() = registry

    /** Moves the owner to STARTED/RESUMED so CameraX opens the device. */
    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    /** Moves the owner to STARTED/STOPPED and closes the camera while keeping the binding. */
    fun pause() {
        registry.currentState = Lifecycle.State.CREATED
    }

    /** Terminates the lifecycle; CameraX releases everything bound to it. */
    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
