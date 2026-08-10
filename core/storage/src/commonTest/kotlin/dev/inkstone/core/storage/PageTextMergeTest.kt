package dev.inkstone.core.storage

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageText
import dev.inkstone.core.model.RecognisedLine
import dev.inkstone.core.model.RecognisedWord
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⭐ **SPEC §5.5's prerequisite: two devices agreeing on one page's recognised text.**
 *
 * M6 wrote this hazard down and left it, correctly — nothing produced a clashing bundle-entry
 * name until recognition shipped, and `text/<pageId>.json` is named per *page*, so every device
 * produces the same name from M7's first pass. `NoteStore.materialize`'s union is *local-wins*
 * and local-wins is symmetric, so the two devices would keep different bytes forever with
 * nothing reported.
 *
 * The test that matters here is `both devices choose the same text whichever side they are on`.
 * It is the one that fails under the old rule, and it fails the same way the real bug would: no
 * exception, no lost stroke, just two devices quietly disagreeing about what the page says.
 */
class PageTextMergeTest {

    private val clock = TestClock()

    // -----------------------------------------------------------------------
    // The rule, as a pure function
    // -----------------------------------------------------------------------

    @Test
    fun `text that still describes the ink is kept`() {
        val local = text("ink-1", "the quick brown fox")

        assertEquals(local, PageTextMerge.choose(local, remote = null, inkDigest = "ink-1"))
    }

    @Test
    fun `text about ink that has moved on is not kept`() {
        val local = text("ink-1", "the quick brown fox")

        // The local-staleness case, and it needs no peer at all: erase a word and the page's
        // digest moves, so the text stops being offered without anything having to run.
        assertNull(PageTextMerge.choose(local, remote = null, inkDigest = "ink-2"))
    }

    @Test
    fun `a peer's text is adopted when it is the one that matches`() {
        val local = text("ink-1", "stale")
        val remote = text("ink-2", "current")

        assertEquals(remote, PageTextMerge.choose(local, remote, inkDigest = "ink-2"))
    }

    @Test
    fun `when neither describes the merged page both are discarded`() {
        // The ordinary outcome of a real two-device merge: the union of two devices' strokes
        // is ink that neither of them recognised. Dropping converges in one step; keeping
        // either one would publish text about a page that no longer exists.
        val local = text("ink-a", "device A's reading")
        val remote = text("ink-b", "device B's reading")

        assertNull(PageTextMerge.choose(local, remote, inkDigest = "ink-merged"))
    }

    @Test
    fun `both devices choose the same text whichever side they are on`() {
        // ⭐ THE property. Device A sees (local = a, remote = b); device B sees (local = b,
        // remote = a). Under M6's local-wins union each kept its own and neither was told.
        val a = text("ink-1", "a reading of the page")
        val b = text("ink-1", "another reading of it")

        val onDeviceA = PageTextMerge.choose(local = a, remote = b, inkDigest = "ink-1")
        val onDeviceB = PageTextMerge.choose(local = b, remote = a, inkDigest = "ink-1")

        assertEquals(onDeviceA, onDeviceB, "the rule must not depend on which device is asking")
        assertNotNull(onDeviceA)
    }

    @Test
    fun `a page neither device has read has no text and no drama`() {
        assertNull(PageTextMerge.choose(local = null, remote = null, inkDigest = "ink-1"))
    }

    // -----------------------------------------------------------------------
    // The digest
    // -----------------------------------------------------------------------

    @Test
    fun `the digest follows the ink`() = runTest {
        val device = Device("device-a")
        val (session, page) = device.note()

        session.apply(page, listOf(add("s1", "device-a", 10)))
        val one = session.inkDigestOf(page)

        session.apply(page, listOf(add("s2", "device-a", 11)))
        val two = session.inkDigestOf(page)
        assertNotEquals(one, two, "adding a stroke must invalidate the page's recognised text")

        session.apply(page, listOf(erase("s2", "device-a", 12)))
        assertNotEquals(two, session.inkDigestOf(page), "erasing part of a stroke changes what it says")

        session.apply(page, listOf(move("s1", "device-a", 13)))
        val moved = session.inkDigestOf(page)
        assertNotEquals(two, moved, "moving a stroke changes where the words are")
    }

    @Test
    fun `restyling a stroke does not throw away the page's text`() = runTest {
        // Recolouring a word does not change what it says. Including style in the digest
        // would make every pen change cost a recognition pass and a re-upload.
        val device = Device("device-a")
        val (session, page) = device.note()
        session.apply(page, listOf(add("s1", "device-a", 10)))
        val before = session.inkDigestOf(page)

        session.apply(page, listOf(restyle("s1", "device-a", 11)))

        assertEquals(before, session.inkDigestOf(page))
    }

    @Test
    fun `two devices folding the same ops compute the same digest`() = runTest {
        // Without this the whole rule is decoration: the comparison only converges if both
        // sides derive the same key from the same page.
        val a = Device("device-a")
        val b = Device("device-b")
        val (sessionA, page) = a.note()
        sessionA.apply(page, listOf(add("s1", "device-a", 10), add("s2", "device-a", 11)))
        sessionA.materialize()

        b.adopt(a.noteId, a.bundle())
        val sessionB = b.open(a.noteId)

        assertEquals(sessionA.inkDigestOf(page), sessionB.inkDigestOf(page))
    }

    // -----------------------------------------------------------------------
    // Through the bundle
    // -----------------------------------------------------------------------

    @Test
    fun `recognised text survives a materialize and is still readable`() = runTest {
        val device = Device("device-a")
        val (session, page) = device.note()
        session.apply(page, listOf(add("s1", "device-a", 10)))

        assertTrue(session.recognised(page, lines(), "mlkit/test"))
        session.materialize()

        val reopened = device.open(device.noteId)
        val read = assertNotNull(reopened.recognisedText(page), "the text did not survive the bundle")
        assertEquals("hello world", read.lines.single().text)
        assertEquals(2, read.words.size)
        assertEquals("mlkit/test", read.recogniser)
    }

    @Test
    fun `text is withdrawn the moment the ink under it changes`() = runTest {
        val device = Device("device-a")
        val (session, page) = device.note()
        session.apply(page, listOf(add("s1", "device-a", 10)))
        session.recognised(page, lines(), "mlkit/test")
        session.materialize()

        val reopened = device.open(device.noteId)
        assertNotNull(reopened.recognisedText(page))

        reopened.apply(page, listOf(add("s2", "device-a", 20)))

        assertNull(reopened.recognisedText(page), "text about ink that has changed must not be served")
    }

    @Test
    fun `a merge that changes the ink drops both devices text and converges`() = runTest {
        // End to end, through two real bundles: the case the local-wins union could not settle.
        //
        // ⚠️ The two devices must diverge from a COMMON BASE and each recognise what it can
        // see. Writing this as "A writes and recognises, then B adopts A's bundle, adds a
        // stroke and recognises" is not concurrency — B's reading covers both strokes, so it
        // legitimately describes the merged page and A is right to adopt it. That version of
        // the test passes while proving nothing about the case it is named for.
        val a = Device("device-a")
        val b = Device("device-b")
        val (sessionA, page) = a.note()
        sessionA.apply(page, listOf(add("base", "device-a", 5)))
        sessionA.materialize()

        b.adopt(a.noteId, a.bundle())
        val sessionB = b.open(a.noteId)

        // Now both write, offline from each other, and each recognises its own page.
        sessionA.apply(page, listOf(add("from-a", "device-a", 10)))
        sessionA.recognised(page, lines("device A read this"), "mlkit/test")
        sessionA.materialize()

        sessionB.apply(page, listOf(add("from-b", "device-b", 11)))
        sessionB.recognised(page, lines("device B read this"), "mlkit/test")
        sessionB.materialize()

        val intoA = NoteMerge.merge(sessionA, a.readerOver(b.bundle()))
        val mergedA = assertNotNull(intoA as? MergeReport.Merged)
        sessionA.materialize(mergedA.carried, mergedA.carriedManifest)

        val intoB = NoteMerge.merge(sessionB, b.readerOver(a.bundle()))
        val mergedB = assertNotNull(intoB as? MergeReport.Merged)
        sessionB.materialize(mergedB.carried, mergedB.carriedManifest)

        assertEquals(1, mergedA.textDropped, "A's text described only A's stroke")
        assertEquals(1, mergedB.textDropped, "and B's only B's")
        assertNull(a.open(a.noteId).recognisedText(page))
        assertNull(b.open(a.noteId).recognisedText(page))
    }

    @Test
    fun `a peer's recognised text is adopted by a device that has none`() = runTest {
        // The device with no language model. It cannot recognise anything itself, and it
        // must still end up able to search the page (ROADMAP M7's degradation clause working
        // in the useful direction).
        val a = Device("device-a")
        val b = Device("device-b")
        val (sessionA, page) = a.note()
        sessionA.apply(page, listOf(add("s1", "device-a", 10)))
        sessionA.materialize()

        b.adopt(a.noteId, a.bundle())
        val sessionB = b.open(a.noteId)
        assertNull(sessionB.recognisedText(page))

        // A recognises and republishes without changing a stroke.
        sessionA.recognised(page, lines("what A read"), "mlkit/test")
        sessionA.materialize()

        val intoB = assertNotNull(NoteMerge.merge(sessionB, b.readerOver(a.bundle())) as? MergeReport.Merged)
        sessionB.materialize(intoB.carried, intoB.carriedManifest)

        assertEquals(1, intoB.textAdopted)
        assertEquals("what A read", assertNotNull(b.open(a.noteId).recognisedText(page)).lines.single().text)
    }

    @Test
    fun `the bundle carries text as a known entry and not as carried data`() = runTest {
        // ⚠️ If `text/` were still in `carriedNames` it would be written twice — once by the
        // writer and once from the carried map — and Zip.write neither de-duplicates nor
        // complains. Two entries, one name, and which one wins on read is map iteration order.
        val device = Device("device-a")
        val (session, page) = device.note()
        session.apply(page, listOf(add("s1", "device-a", 10)))
        session.recognised(page, lines(), "mlkit/test")
        session.materialize()

        val reader = device.readerOver(device.bundle())
        assertTrue(
            reader.carriedNames.none { it.startsWith("text/") },
            "text/ must not be carried as well as written; got ${reader.carriedNames}",
        )
        assertNotNull(reader.pageText(page), "…and it must actually be in the bundle")
    }

    @Test
    fun `page text survives an encode and decode unchanged`() {
        val original = PageText(
            pageId = PageId("page-1"),
            inkDigest = "abc123",
            recogniser = "mlkit-digital-ink/19.0.0/en-US",
            lines = listOf(
                RecognisedLine(
                    text = "hello world",
                    bounds = PageRect(10f, 20f, 200f, 44f),
                    words = listOf(
                        RecognisedWord("hello", PageRect(10f, 20f, 90f, 44f)),
                        RecognisedWord("world", PageRect(100f, 20f, 200f, 44f)),
                    ),
                ),
            ),
        )

        assertEquals(original, PageTextCodec.decode(PageTextCodec.encode(original)))
    }

    @Test
    fun `nonsense bytes decode to null rather than throwing`() {
        // It is a peer's file, possibly from a newer build. An exception out of a bundle read
        // is a note that will not open (SPEC §5.8).
        assertNull(PageTextCodec.decode("not json at all".encodeToByteArray()))
        assertNull(PageTextCodec.decode(ByteArray(0)))
        assertNull(PageTextCodec.decode("{\"pageId\":\"p\"}".encodeToByteArray()))
    }

    // -----------------------------------------------------------------------

    private inner class Device(val name: String) {
        val fs = FakeFileSystem()
        val ids = IdFactory(clock, Random(name.hashCode()))
        val store = NoteStore(fs, clock, ids)
        var noteId: DocumentId = DocumentId("unset")

        suspend fun note(): Pair<NoteSession, PageId> {
            val created = store.create("Notes", name)
            noteId = created.document.id
            val session = NoteSession.open(store, ids, clock, created, deviceId = name)
            return session to created.document.pages.single().id
        }

        suspend fun open(id: DocumentId): NoteSession =
            NoteSession.open(store, ids, clock, assertNotNull(store.open(id)), deviceId = name)

        suspend fun bundle(): ByteArray = fs.readBytes(NoteLayout.bundle(noteId))

        /**
         * Take a peer's published bundle as this device's copy of the note.
         *
         * Sets [noteId] as well as writing the bytes — a device that adopted a note and did
         * not remember which one it was could still open it (the id is in the caller's hand)
         * while [bundle] silently read a path that does not exist.
         */
        suspend fun adopt(id: DocumentId, bytes: ByteArray) {
            noteId = id
            fs.writeBytesAtomically(NoteLayout.bundle(id), bytes)
            fs.writeBytesAtomically(NoteLayout.journal(id), Journal.header())
        }

        suspend fun readerOver(bytes: ByteArray): BundleReader =
            assertNotNull(BundleReader.open(MemoryByteSource(bytes)))
    }

    private fun text(digest: String, body: String) = PageText(
        pageId = PageId("page-1"),
        inkDigest = digest,
        recogniser = "mlkit/test",
        lines = listOf(RecognisedLine(body, PageRect(0f, 0f, 100f, 20f), emptyList())),
    )

    private fun lines(body: String = "hello world") = listOf(
        RecognisedLine(
            text = body,
            bounds = PageRect(10f, 20f, 200f, 44f),
            words = body.split(" ").mapIndexed { index, word ->
                RecognisedWord(word, PageRect(10f + index * 90f, 20f, 90f + index * 90f, 44f))
            },
        ),
    )

    private fun add(id: String, device: String, at: Long) = PageOp.AddObject(
        opId = "op-$id-$device",
        clock = LamportClock(at, device),
        obj = StrokeObject(ObjectId(id), StrokeGeometry(ByteArray(32) { (it + id.length).toByte() })),
    )

    private fun erase(target: String, device: String, at: Long) = PageOp.SetErasedRegion(
        opId = "op-erase-$target-$device",
        clock = LamportClock(at, device),
        target = ObjectId(target),
        absolute = dev.inkstone.core.model.ErasedArea(
            listOf(dev.inkstone.core.model.EraseCapsule(1f, 1f, 5f, 5f, 2f)),
        ),
    )

    private fun move(target: String, device: String, at: Long) = PageOp.SetTransform(
        opId = "op-move-$target-$device",
        clock = LamportClock(at, device),
        target = ObjectId(target),
        absolute = Matrix3.translate(10f, 0f),
    )

    private fun restyle(target: String, device: String, at: Long) = PageOp.SetStyle(
        opId = "op-style-$target-$device",
        clock = LamportClock(at, device),
        target = ObjectId(target),
        style = ObjectStyle(colorArgb = 0xFFFF0000.toInt(), widthPt = 2f),
    )
}
