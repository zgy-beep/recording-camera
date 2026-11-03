package com.recordingcamera.core.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingPhotoJournalTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun newJournal(): PendingPhotoJournal =
        PendingPhotoJournal(File(folder.newFolder(), "pending.tsv"))

    private fun entry(id: String, name: String = "camera_1.jpg") = PendingPhotoJournal.Entry(
        id = id,
        temporaryPath = folder.root.resolve("$id.part").absolutePath,
        displayName = name,
        capturedAtMillis = 1_700_000_000_000L,
        lensName = "BACK",
        stage = PendingPhotoJournal.Stage.CAPTURED,
    )

    @Test
    fun `entries survive a reopen`() {
        val file = File(folder.newFolder(), "pending.tsv")
        PendingPhotoJournal(file).apply {
            record(entry("a"))
            record(entry("b", "second.jpg"))
        }

        val reopened = PendingPhotoJournal(file).load()

        assertEquals(2, reopened.size)
        assertEquals(listOf("a", "b"), reopened.map { it.id })
        assertEquals("second.jpg", reopened[1].displayName)
        assertEquals(PendingPhotoJournal.Stage.CAPTURED, reopened[1].stage)
    }

    @Test
    fun `separators inside values survive escaping`() {
        val nasty = "tab\there\nnewline\\backslash"
        val journal = newJournal()
        journal.record(entry("x", nasty))

        val loaded = journal.load().single()

        assertEquals(nasty, loaded.displayName)
        assertEquals(1, journal.load().size)
    }

    @Test
    fun `markSaving is persisted and remove drops the row`() {
        val journal = newJournal()
        journal.record(entry("a"))
        journal.record(entry("b"))

        journal.markSaving("a")
        assertEquals(PendingPhotoJournal.Stage.SAVING, journal.load().first { it.id == "a" }.stage)

        journal.remove("b")
        assertEquals(listOf("a"), journal.load().map { it.id })
    }

    @Test
    fun `a corrupt journal is discarded instead of blocking captures`() {
        val file = File(folder.newFolder(), "pending.tsv")
        file.writeText("this is not a journal\n\u0000garbage\n")

        val journal = PendingPhotoJournal(file)
        assertTrue(journal.load().isEmpty())

        journal.record(entry("fresh"))
        assertEquals(listOf("fresh"), journal.load().map { it.id })
    }

    @Test
    fun `clear empties the journal`() {
        val journal = newJournal()
        journal.record(entry("a"))
        journal.clear()

        assertTrue(journal.load().isEmpty())
    }
}
