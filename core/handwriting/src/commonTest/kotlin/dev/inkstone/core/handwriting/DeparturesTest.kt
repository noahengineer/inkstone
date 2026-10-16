package dev.inkstone.core.handwriting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Q8 and SPEC §13.1: which notes a pass forgets.
 *
 * ⚠️ This is the decision only. Where `onDevice` comes from is one line in `:app`'s
 * `HandwritingLearner.notesOnDevice`, which lists `notes/` and the local trash. These tests cannot
 * reach that line. They can show that the decision is safe once it is given the files.
 */
class DeparturesTest {

    @Test
    fun `a note purged from the device takes its samples and its watermarks`() {
        val samples = listOf(Fixtures.sample("a", noteId = "kept"), Fixtures.sample("b", noteId = "purged"))
        val state = ProfileState(
            harvestedPages = mapOf(
                ProfileState.pageKey("kept", "p1") to "d1",
                ProfileState.pageKey("purged", "p1") to "d2",
            ),
            harvestedNotes = mapOf("kept" to 3L, "purged" to 7L),
        )

        assertEquals(setOf("purged"), Departures.of(samples, state, onDevice = setOf("kept", "other")))
    }

    @Test
    fun `a note in the trash has not departed`() {
        // The learner's listing includes the trash, so a trashed note is in onDevice. Restoring it
        // must lose nothing (Q8).
        val samples = listOf(Fixtures.sample("a", noteId = "trashed"))

        assertTrue(Departures.of(samples, ProfileState(), onDevice = setOf("trashed")).isEmpty())
    }

    @Test
    fun `a note harvested to nothing still loses its watermark when it goes`() {
        // A page that taught no words still has a watermark, and a purged note's would otherwise
        // stay in profile.json for ever.
        val state = ProfileState(
            harvestedPages = mapOf(ProfileState.pageKey("empty", "p1") to "d"),
            harvestedNotes = mapOf("empty" to 2L),
        )

        assertEquals(setOf("empty"), Departures.of(emptyList(), state, onDevice = setOf("live")))
    }

    @Test
    fun `nothing on the device at all forgets nothing`() {
        // The lost-index case this guards against reads as every note gone. An empty listing is
        // the files' version of the same mistake, and it costs every word learned if believed.
        val samples = listOf(Fixtures.sample("a", noteId = "n1"), Fixtures.sample("b", noteId = "n2"))
        val state = ProfileState(harvestedNotes = mapOf("n1" to 1L, "n2" to 1L))

        assertTrue(Departures.of(samples, state, onDevice = emptySet()).isEmpty())
    }

    @Test
    fun `a page key names the note it belongs to`() {
        val key = ProfileState.pageKey("018bcfe5-6800-7002-a81b-02163af13e5f", "018bcfe5-6800-7003-8000-000000000001")

        assertEquals("018bcfe5-6800-7002-a81b-02163af13e5f", ProfileState.noteOfPageKey(key))
    }
}
