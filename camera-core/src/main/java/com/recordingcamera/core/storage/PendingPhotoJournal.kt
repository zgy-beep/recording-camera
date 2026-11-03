package com.recordingcamera.core.storage

import android.content.Context
import java.io.File

/**
 * Crash-safe journal of stills that were written to a temp file but not yet persisted.
 *
 * Without it, a process death between `onImageSaved` and the storage copy loses the photo silently:
 * the bytes are on disk but nothing knows about them. With it, the next [CameraCore.open] finds the
 * leftovers and pushes them through the normal save path.
 *
 * Format is one tab separated record per line; paths are escaped so a file name containing a tab
 * cannot corrupt the journal.
 *
 * The file is **append only**: `record` costs one line write instead of re-reading and rewriting the
 * whole table, which matters because it happens on the main thread before every shutter. Removals are
 * appended as tombstones and the table is compacted once they outnumber live rows. A crash mid-write
 * can only damage the line being written, and a damaged line is skipped instead of taking the whole
 * journal with it.
 */
internal class PendingPhotoJournal(private val file: File) {

    enum class Stage { CAPTURED, SAVING }

    data class Entry(
        val id: String,
        val temporaryPath: String,
        val displayName: String,
        val capturedAtMillis: Long,
        val lensName: String,
        val stage: Stage,
    )

    private val lock = Any()

    /** Live rows, in insertion order. Loaded lazily on the first access. */
    private val entries = LinkedHashMap<String, Entry>()

    private var loaded = false
    private var tombstones = 0

    fun load(): List<Entry> = synchronized(lock) {
        ensureLoaded()
        entries.values.toList()
    }

    fun record(entry: Entry) = synchronized(lock) {
        ensureLoaded()
        entries[entry.id] = entry
        append(format(entry))
    }

    fun markSaving(id: String) = synchronized(lock) {
        ensureLoaded()
        val current = entries[id] ?: return
        if (current.stage == Stage.SAVING) return
        val updated = current.copy(stage = Stage.SAVING)
        entries[id] = updated
        append(format(updated))
    }

    fun remove(id: String) = synchronized(lock) {
        ensureLoaded()
        if (entries.remove(id) == null) return
        tombstones++
        append("$TOMBSTONE\t$id")
        if (tombstones > entries.size + COMPACTION_SLACK) compact()
    }

    fun clear() = synchronized(lock) {
        loaded = true
        entries.clear()
        tombstones = 0
        runCatching { file.delete() }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        runCatching {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                if (line.startsWith("$TOMBSTONE\t")) {
                    entries.remove(line.removePrefix("$TOMBSTONE\t"))
                    return@forEachLine
                }
                parseLine(line)?.let { entries[it.id] = it }
            }
        }.onFailure {
            // A journal we cannot even read must never block captures. Drop it and start over; the
            // .part files it pointed at are collected by the orphan sweep.
            runCatching { file.delete() }
            entries.clear()
        }
    }

    private fun append(line: String) {
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
    }

    private fun compact() {
        tombstones = 0
        if (entries.isEmpty()) {
            runCatching { file.delete() }
            return
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(
            entries.values.joinToString(separator = "\n", postfix = "\n", transform = ::format),
        )
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }

    private fun format(entry: Entry): String = listOf(
        entry.id,
        entry.stage.name,
        escape(entry.temporaryPath),
        escape(entry.displayName),
        entry.capturedAtMillis.toString(),
        entry.lensName,
    ).joinToString(separator = "\t")

    private fun parseLine(line: String): Entry? {
        val parts = line.split('\t')
        if (parts.size < 6) return null
        val stage = runCatching { Stage.valueOf(parts[1]) }.getOrNull() ?: return null
        val capturedAt = parts[4].toLongOrNull() ?: return null
        return Entry(
            id = parts[0],
            stage = stage,
            temporaryPath = unescape(parts[2]),
            displayName = unescape(parts[3]),
            capturedAtMillis = capturedAt,
            lensName = parts[5],
        )
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(value: String): String {
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '\\' && index + 1 < value.length) {
                when (val next = value[index + 1]) {
                    't' -> builder.append('\t')
                    'n' -> builder.append('\n')
                    '\\' -> builder.append('\\')
                    else -> builder.append(next)
                }
                index += 2
            } else {
                builder.append(character)
                index++
            }
        }
        return builder.toString()
    }

    companion object {
        /** A tombstone line marks an id as gone; ids are UUIDs so the marker cannot collide. */
        private const val TOMBSTONE = "-"

        /** Rewrite the table once dead rows outnumber live ones by this much. */
        private const val COMPACTION_SLACK = 8

        fun create(context: Context): PendingPhotoJournal =
            PendingPhotoJournal(File(context.filesDir, "camera-core/pending_photos.tsv"))
    }
}
