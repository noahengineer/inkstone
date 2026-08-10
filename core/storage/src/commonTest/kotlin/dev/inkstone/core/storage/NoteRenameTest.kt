package dev.inkstone.core.storage

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.IdFactory
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC §7.7 layer 2's **writer**, which M6 shipped without.
 *
 * The mechanism was all there — `Document.titleClock`, `resolveTitle`, `mergeDocument`, a
 * `TITLE_OVERRIDDEN` attention row — and the production rename renamed a library index row and
 * nothing else, so two devices renaming one note kept two titles indefinitely with no clock to
 * order them. These are the assertions that say the note's own document is what gets written.
 */
class NoteRenameTest {

    private val fs = FakeFileSystem()
    private val clock = TestClock()
    private val ids = IdFactory(clock, Random(11))
    private val store = NoteStore(fs, clock, ids)

    private suspend fun rename(id: DocumentId, title: String) =
        store.renameNote(id, title, deviceId = "device-a", ids = ids, clock = clock)

    @Test
    fun `renaming stamps the notes own title register`() = runTest {
        val note = store.create("Lecture 3", "device-a")

        val renamed = assertNotNull(rename(note.document.id, "Thermodynamics wk 4"))

        assertEquals("Thermodynamics wk 4", renamed.title)
        val stamped = assertNotNull(renamed.titleClock, "the rename left no clock, so no merge can order it")
        assertEquals("device-a", stamped.deviceId, "the clock must carry this install's id for SPEC §4.1's tiebreak")
        assertTrue(stamped.counter > 0, "a clock at counter zero cannot beat a peer that has never renamed")
    }

    @Test
    fun `a rename reaches the bundle so a peer can see it`() = runTest {
        // ⚠️ The assertion that matters for convergence, and the reason this materializes rather than
        // flushing: a published PDF embeds `note.inkstone` **as it is on disk**, so a title that
        // reached only the journal is one no peer ever receives — the upload would carry a bundle
        // still holding the old title until the note next happened to close.
        val note = store.create("Lecture 3", "device-a")

        assertNotNull(rename(note.document.id, "Thermodynamics wk 4"))

        val bundle = assertNotNull(BundleReader.open(FileByteSource(fs, NoteLayout.bundle(note.document.id))))
        assertEquals("Thermodynamics wk 4", bundle.document.title, "the rename never reached the bundle")
        assertNotNull(bundle.document.titleClock, "the bundle holds the title without the clock that orders it")
        // Throws if the journal still has content, i.e. if this only flushed (SPEC §5.1).
        store.assertNotStale(note.document.id)
    }

    @Test
    fun `the rename clock is recorded as seen so the next one sorts after it`() = runTest {
        val note = store.create("Lecture 3", "device-a")
        val first = assertNotNull(rename(note.document.id, "Thermodynamics wk 4"))
        val firstClock = assertNotNull(first.titleClock)

        val second = assertNotNull(rename(note.document.id, "Thermodynamics wk 4 final"))

        assertTrue(
            assertNotNull(second.titleClock) > firstClock,
            "two renames through the store produced clocks that do not increase, so a peer that saw " +
                "the first will refuse the second",
        )
    }

    @Test
    fun `renaming to the title it already has costs nothing`() = runTest {
        // Not a micro-optimisation: burning a Lamport counter and rewriting a whole bundle for a
        // rename that renames nothing would make the library's "rename" dialog a way to churn the
        // note's revision, and every churn is an upload (SPEC §7.6).
        val note = store.create("Lecture 3", "device-a")
        val before = assertNotNull(store.open(note.document.id)).document

        val same = assertNotNull(rename(note.document.id, "Lecture 3"))

        assertEquals(before.localRev, same.localRev, "an identical rename bumped the revision")
        assertNull(same.titleClock, "an identical rename stamped a clock")
    }

    @Test
    fun `renaming a note that is not there answers null`() = runTest {
        assertNull(rename(DocumentId("no-such-note"), "Anything"))
    }
}
