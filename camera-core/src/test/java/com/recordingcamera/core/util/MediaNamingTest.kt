package com.recordingcamera.core.util

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaNamingTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    @Test
    fun `photo names embed an unambiguous timestamp and a random token`() {
        val name = MediaNaming.photoName(timestampMillis = 0L, timeZone = utc, token = "abcd1234")

        assertEquals("camera_19700101_000000_000_abcd1234.jpg", name)
    }

    @Test
    fun `video names use the mp4 extension`() {
        val name = MediaNaming.videoName(timestampMillis = 0L, timeZone = utc, token = "abcd1234")

        assertEquals("camera_19700101_000000_000_abcd1234.mp4", name)
    }

    @Test
    fun `names sort lexicographically in capture order`() {
        val hour = 3_600_000L
        val first = MediaNaming.photoName(timestampMillis = 1_700_000_000_000L, timeZone = utc)
        val second = MediaNaming.photoName(timestampMillis = 1_700_000_000_000L + hour, timeZone = utc)

        assertTrue("timestamp ordering must survive the name format", first < second)
    }

    @Test
    fun `two captures in the same millisecond still get distinct names`() {
        val stamp = 1_700_000_000_000L
        val names = (1..64).map { MediaNaming.photoName(timestampMillis = stamp, timeZone = utc) }.toSet()

        assertEquals("tokens collide too often", 64, names.size)
        assertNotEquals(
            MediaNaming.photoName(timestampMillis = stamp, timeZone = utc),
            MediaNaming.photoName(timestampMillis = stamp + 1, timeZone = utc),
        )
    }

    @Test
    fun `the token is lowercase alphanumeric and fixed length`() {
        val token = MediaNaming.randomToken()

        assertEquals(8, token.length)
        assertTrue(token.all { it in '0'..'9' || it in 'a'..'z' })
    }
}
