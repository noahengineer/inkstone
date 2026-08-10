package dev.inkstone.core.database.search

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.database.library.LibraryIndex
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.NoteSummary
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageText
import dev.inkstone.core.model.RecognisedLine
import dev.inkstone.core.model.RecognisedWord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC §8.4's search index, against **the device's own SQLite**.
 *
 * Instrumented rather than a JVM unit test for the reason `core/database/build.gradle.kts`
 * gives: the thing under test *is* the engine. The prefix match is a range scan over a real
 * index with the engine's own collation, the boxes are `REAL` columns, and the replace is a
 * `DELETE` and a run of inserts inside one transaction — none of which a fake would be
 * testing. A desktop SQLite is a different build from the tablet's, and this table exists at
 * all because Android's build has no FTS5 (`FtsAvailabilityTest`).
 *
 * ```
 * ./gradlew :core:database:connectedDebugAndroidTest
 * ```
 *
 * In-memory (`name = null`), so each test starts empty and nothing is left on the device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SqlSearchIndexTest {

    private val driver = AndroidSqliteDriver(
        schema = InkstoneDatabase.Schema,
        context = ApplicationProvider.getApplicationContext(),
        name = null,
    )
    private val database = InkstoneDatabase(driver)
    private val dispatcher = UnconfinedTestDispatcher()

    private val index = SqlSearchIndex(database, dispatcher)

    /**
     * The real library index, not a fixture that inserts `note` rows by hand.
     *
     * `searchWords` joins to `note`, and `searchTitles` reads `titleFold` — a column only
     * `LibraryIndex` knows to fold in Kotlin. Writing those rows directly would let this
     * suite pass against a folding rule production does not use.
     */
    private val library = LibraryIndex(database, dispatcher)

    private val note = DocumentId("note-1")
    private val pageOne = PageId("page-1")
    private val pageTwo = PageId("page-2")

    /** Fractional on every edge, so a column that had drifted to INTEGER affinity rounds it away. */
    private val box = PageRect(left = 12.5f, top = 34.25f, right = 96.75f, bottom = 48.125f)

    @AfterTest
    fun close() = driver.close()

    private fun noteRow(id: DocumentId, title: String, modified: Long = 1_000L) = NoteSummary(
        id = id,
        title = title,
        localRev = 1L,
        createdEpochMillis = modified,
        modifiedEpochMillis = modified,
    )

    /**
     * One page of recognised text, one word per entry, laid out left to right.
     *
     * Built as a [PageText] and handed to `indexPage`, so the ordinals, the boxes and the
     * fold under test are the ones production writes rather than ones a fixture chose.
     */
    private fun recognisedPage(pageId: PageId, vararg words: String): PageText {
        val recognised = words.mapIndexed { ordinal, word ->
            val shift = ordinal * WORD_SPACING
            RecognisedWord(word, box.copy(left = box.left + shift, right = box.right + shift))
        }
        return PageText(
            pageId = pageId,
            inkDigest = "digest-${pageId.value}",
            recogniser = "mlkit-digital-ink/19.0.0/en",
            lines = listOf(RecognisedLine(words.joinToString(" "), box, recognised)),
        )
    }

    // -----------------------------------------------------------------------
    // Words, boxes and the prefix range scan
    // -----------------------------------------------------------------------

    @Test
    fun `an indexed word comes back naming its note its page and its box`() = runTest {
        library.insertNote(noteRow(note, "Lecture"))
        index.indexPage(note, recognisedPage(pageOne, "handwriting"))

        val hit = index.search("handwriting").single()

        assertEquals(note, hit.noteId)
        assertEquals(pageOne, hit.pageId, "a hit that cannot name its page cannot be scrolled to")
        assertEquals("handwriting", hit.word)
        assertEquals("Lecture", hit.noteTitle)
        // The box is Float in Kotlin and REAL in SQLite, and SPEC §8.4's tap-to-highlight is
        // wrong by exactly whatever this round trip loses. Having the box at all is what the
        // ink being born-digital buys over rasterise-then-OCR, so losing precision here gives
        // back the only advantage.
        assertEquals(box, hit.bounds)
    }

    @Test
    fun `a prefix finds the longer word and the upper bound stops the scan`() = runTest {
        library.insertNote(noteRow(note, "Lecture"))
        index.indexPage(note, recognisedPage(pageOne, "handwriting"))
        index.indexPage(note, recognisedPage(pageTwo, "hazel"))

        // Without `SearchTerms.upperBound`, `wordFold >= 'hand'` is every word in the library
        // that sorts after it — the entire second half of the alphabet, returned as matches.
        // That range scan is the whole reason this design needs no FTS table.
        assertEquals(listOf("handwriting"), index.search("hand").map { it.word })

        // The control: "hazel" is present and reachable, so the miss above is the bound doing
        // its job rather than a row that was never written.
        assertEquals(setOf("handwriting", "hazel"), index.search("ha").mapTo(HashSet()) { it.word })
    }

    @Test
    fun `case folding is Unicode-aware because it happens in Kotlin`() = runTest {
        library.insertNote(noteRow(note, "Deutsch"))
        index.indexPage(note, recognisedPage(pageOne, "Übung"))

        // SQLite's own lower() and COLLATE NOCASE are ASCII-only: they leave 'Ü' alone, so
        // folding in SQL would make this search miss a word that is plainly on the page.
        // That is why `wordFold` is a stored column and not an expression.
        assertEquals(listOf("Übung"), index.search("übung").map { it.word })
        assertEquals(listOf("Übung"), index.search("ÜBUNG").map { it.word })
    }

    // -----------------------------------------------------------------------
    // Forgetting
    // -----------------------------------------------------------------------

    @Test
    fun `re-indexing a page replaces its words rather than merging them`() = runTest {
        library.insertNote(noteRow(note, "Lecture"))
        index.indexPage(note, recognisedPage(pageOne, "alpha", "beta"))

        index.indexPage(note, recognisedPage(pageOne, "alpha"))

        assertEquals(listOf("alpha"), index.search("alpha").map { it.word })
        // Recognition answers a whole page at once, so the previous words are a previous
        // *answer*, not a subset to add to. Merging is how a word the user rubbed out goes on
        // being findable for ever — and the note it points into no longer contains it.
        assertTrue(index.search("beta").isEmpty(), "an erased word stayed findable")
        // A bound, not just an absence: a delete that removed the wrong rows would also
        // satisfy the line above.
        assertEquals(1L, index.wordCount())
    }

    @Test
    fun `forgetPage removes one page and forgetNote removes all of them`() = runTest {
        library.insertNote(noteRow(note, "Lecture"))
        index.indexPage(note, recognisedPage(pageOne, "alpha"))
        index.indexPage(note, recognisedPage(pageTwo, "beta"))

        index.forgetPage(note, pageOne)

        assertTrue(index.search("alpha").isEmpty(), "a deleted page stayed findable")
        // The sibling page is the assertion that matters: a `DELETE` missing its pageId
        // predicate would take the whole note down with one deleted page.
        assertEquals(listOf(pageTwo), index.search("beta").map { it.pageId })
        assertEquals(1L, index.wordCount())

        index.forgetNote(note)

        assertTrue(index.search("beta").isEmpty())
        assertEquals(0L, index.wordCount())
    }

    @Test
    fun `a trashed note is not searchable by its ink or by its title`() = runTest {
        library.insertNote(noteRow(note, "Lecture on waves"))
        index.indexPage(note, recognisedPage(pageOne, "handwriting"))
        assertEquals(1, index.search("handwriting").size, "the ink hit is the control for the trash check")
        assertEquals(1, index.search("waves").size, "the title hit is the control for the trash check")

        library.trashNote(note, trashPath = "trash/1-note-1", deletedEpochMillis = 5_000L)

        // A result the user taps and cannot open is worse than a miss; the trash has its own
        // screen. Both queries filter on `deletedAt`, and both have to.
        assertTrue(index.search("handwriting").isEmpty(), "a trashed note surfaced by its ink")
        assertTrue(index.search("waves").isEmpty(), "a trashed note surfaced by its title")
        // The rows stay. Trashing is not deleting (CLAUDE.md rule 1), and a restore that had
        // to re-recognise every page would be minutes of work to undo one tap.
        assertEquals(1L, index.wordCount())
    }

    // -----------------------------------------------------------------------
    // The degraded path — ROADMAP M7
    // -----------------------------------------------------------------------

    @Test
    fun `with no recognised words at all a note is still found by its title`() = runTest {
        // *"Delete the language model — app works fully, search degrades to titles."* That is
        // not a mode with a flag; it is what an empty word table already does, which is why
        // there is nothing here to switch on.
        library.insertNote(noteRow(note, "Fourier transforms"))

        assertEquals(0L, index.wordCount())
        val hit = index.search("fourier").single()

        assertEquals(note, hit.noteId)
        assertNull(hit.pageId, "a title hit named a page the search never looked at")
        assertNull(hit.bounds, "a title hit carried a box the search never read")
        assertTrue(hit.isTitleMatch)
        assertEquals("Fourier transforms", hit.noteTitle)
    }

    @Test
    fun `a query containing a LIKE wildcard does not match the whole library`() = runTest {
        library.insertNote(noteRow(note, "100 metres"))
        library.insertNote(noteRow(DocumentId("note-2"), "Zebra"))

        // `%` and `_` are LIKE wildcards, so one that reached the pattern unescaped would turn
        // a single search into a full-library match — and on the degraded path that is the
        // *only* path, so every note in the app would come back for every query.
        assertEquals(listOf(note), index.search("100%").map { it.noteId })
        assertEquals(listOf(note), index.search("100_").map { it.noteId })
        assertTrue(index.search("%").isEmpty(), "a bare wildcard matched every note")
        assertTrue(index.search("%_%").isEmpty(), "a bare wildcard matched every note")
    }

    private companion object {
        /** Enough to keep two word boxes apart. Nothing asserted here depends on the value. */
        const val WORD_SPACING = 40f
    }
}
