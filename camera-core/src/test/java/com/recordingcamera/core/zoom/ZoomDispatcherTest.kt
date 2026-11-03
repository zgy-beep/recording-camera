package com.recordingcamera.core.zoom

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ZoomDispatcherTest {

    @Test
    fun `a burst of pinch deltas collapses to the newest ratio`() = runTest {
        val applied = mutableListOf<Float>()
        val dispatcher = ZoomDispatcher(this, watchdogMillis = 150L) { applied += it }

        listOf(1.1f, 1.4f, 2.2f, 3.5f).forEach(dispatcher::submit)
        advanceUntilIdle()

        assertEquals(listOf(3.5f), applied)
        dispatcher.close()
    }

    @Test
    fun `a camera call that never completes trips the watchdog`() = runTest {
        val dispatcher = ZoomDispatcher(this, watchdogMillis = 50L) { delay(5_000) }

        dispatcher.submit(2f)
        advanceUntilIdle()

        assertEquals(1, dispatcher.timedOutCount)
        dispatcher.close()
    }

    @Test
    fun `the worker keeps draining after a watchdog trip`() = runTest {
        val applied = mutableListOf<Float>()
        var slow = true
        val dispatcher = ZoomDispatcher(this, watchdogMillis = 20L) { ratio ->
            if (slow) {
                slow = false
                delay(1_000)
            } else {
                applied += ratio
            }
        }

        dispatcher.submit(2f)
        advanceUntilIdle()
        dispatcher.submit(3f)
        advanceUntilIdle()

        assertEquals(1, dispatcher.timedOutCount)
        assertEquals(listOf(3f), applied)
        dispatcher.close()
    }

    @Test
    fun `closing the dispatcher drops further submissions`() = runTest {
        val applied = mutableListOf<Float>()
        val dispatcher = ZoomDispatcher(this, watchdogMillis = 50L) { applied += it }

        dispatcher.close()
        dispatcher.submit(4f)
        advanceUntilIdle()

        assertTrue(applied.isEmpty())
    }
}
