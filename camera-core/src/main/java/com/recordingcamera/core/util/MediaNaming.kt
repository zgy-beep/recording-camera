package com.recordingcamera.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ThreadLocalRandom

/**
 * Collision-free, sortable media file names.
 *
 * Format: `<prefix>_yyyyMMdd_HHmmss_SSS_<8 random base36>.<ext>`. Lexicographic order matches
 * capture order, which matters for gallery folders and for the pending-media journal.
 */
internal object MediaNaming {

    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"
    private const val TOKEN_LENGTH = 8

    fun photoName(
        timestampMillis: Long = System.currentTimeMillis(),
        prefix: String = "camera",
        timeZone: TimeZone = TimeZone.getDefault(),
        token: String = randomToken(),
    ): String = buildName(prefix, timestampMillis, timeZone, token, "jpg")

    fun videoName(
        timestampMillis: Long = System.currentTimeMillis(),
        prefix: String = "camera",
        timeZone: TimeZone = TimeZone.getDefault(),
        token: String = randomToken(),
    ): String = buildName(prefix, timestampMillis, timeZone, token, "mp4")

    fun buildName(
        prefix: String,
        timestampMillis: Long,
        timeZone: TimeZone,
        token: String,
        extension: String,
    ): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply {
            this.timeZone = timeZone
        }
        return "${prefix}_${formatter.format(Date(timestampMillis))}_$token.$extension"
    }

    fun randomToken(length: Int = TOKEN_LENGTH): String {
        val random = ThreadLocalRandom.current()
        val builder = StringBuilder(length)
        repeat(length) { builder.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return builder.toString()
    }
}
