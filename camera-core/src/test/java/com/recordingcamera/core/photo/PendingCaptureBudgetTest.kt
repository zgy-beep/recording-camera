package com.recordingcamera.core.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingCaptureBudgetTest {

    @Test
    fun `slots are limited independently of the byte budget`() {
        val budget = PendingCaptureBudget(maxItems = 2, maxBytes = 1_000L)

        assertTrue(budget.tryAcquire())
        assertTrue(budget.tryAcquire())
        assertFalse(budget.tryAcquire())
        assertEquals(2, budget.pending)

        budget.release()
        assertEquals(1, budget.pending)
        assertTrue(budget.tryAcquire())
    }

    @Test
    fun `the byte budget closes the gate before the slot budget does`() {
        val budget = PendingCaptureBudget(maxItems = 8, maxBytes = 100L)

        assertTrue(budget.tryAcquire())
        budget.addBytes(150L)

        assertFalse(budget.canAccept())
        assertFalse(budget.tryAcquire())
    }

    @Test
    fun `releasing bytes brings the budget back`() {
        val budget = PendingCaptureBudget(maxItems = 4, maxBytes = 100L)
        budget.tryAcquire()
        budget.addBytes(100L)
        assertFalse(budget.canAccept())

        budget.release(bytesToRelease = 100L)

        assertTrue(budget.canAccept())
        assertEquals(0L, budget.pendingBytes)
    }

    @Test
    fun `release never goes negative`() {
        val budget = PendingCaptureBudget(maxItems = 2, maxBytes = 10L)

        assertEquals(0, budget.release(bytesToRelease = 500L))
        assertEquals(0L, budget.pendingBytes)
    }

    @Test
    fun `degenerate limits are coerced to one slot and one byte`() {
        val budget = PendingCaptureBudget(maxItems = 0, maxBytes = 0L)

        assertTrue("a zero limit must not block every capture", budget.tryAcquire())
        assertFalse(budget.tryAcquire())
    }
}
