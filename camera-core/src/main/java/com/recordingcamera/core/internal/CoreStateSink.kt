package com.recordingcamera.core.internal

import com.recordingcamera.core.CameraCoreEvent
import com.recordingcamera.core.CameraCoreState

/**
 * Narrow write side of the session state, handed to the controllers.
 *
 * Keeping this internal (instead of letting every controller own a `MutableStateFlow`) guarantees a
 * single atomic snapshot per update and a single ordered event stream.
 */
internal interface CoreStateSink {
    fun current(): CameraCoreState
    fun update(transform: (CameraCoreState) -> CameraCoreState)
    fun emit(event: CameraCoreEvent)
}
