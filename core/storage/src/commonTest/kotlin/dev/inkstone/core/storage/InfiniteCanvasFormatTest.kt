package dev.inkstone.core.storage

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Document
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRef
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.foldPage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SPEC §4.5 — the infinite canvas's two page registers, through the file and back.
 *
 * ## The one that is easy to miss
 *
 * A page's canvas has to be in the **page record** and not only in the ops, because a compaction
 * snapshot *is* a bundle (SPEC §4.7) and the `SetPageMeta` that made the page infinite is exactly
 * the sort of old op compaction drops. Without the record, compacting a note would quietly turn
 * every endless page back into a sheet of Letter — and the ink outside it would stop being
 * published, which is the failure this whole feature exists to prevent, arriving by the back door.
 *
 * ## And the one that protects every other test in the suite
 *
 * A fixed page must encode **exactly as it did before these fields existed**, or every committed
 * fixture and every byte-identity assertion in [BundleRoundTripTest] would have to be regenerated
 * — and a format change nobody meant to make is how a build stops reading its own old files.
 */
class InfiniteCanvasFormatTest {

    private val documentId = DocumentId("doc-canvas")
    private val pageId = PageId("page-canvas")

    private var counter = 0L

    private fun clock() = LamportClock(++counter, "device-a")

    // -----------------------------------------------------------------------
    // The op
    // -----------------------------------------------------------------------

    @Test
    fun theOpCarriesBothRegistersThroughTheCodec() {
        val op = PageOp.SetPageMeta(
            opId = "op-1",
            clock = clock(),
            size = PageSize.A4,
            origin = PagePoint(-396f, -792f),
            infinite = true,
        )

        val decoded = OpCodec.decode(OpCodec.encode(op, GeometryWriter()), GeometryReader(ByteArray(0))).op

        assertEquals(op, decoded)
    }

    @Test
    fun anOpThatSetsNeitherWritesNeither() {
        val op = PageOp.SetPageMeta("op-1", clock(), background = Background.Blank)

        val json = OpCodec.encode(op, GeometryWriter()).toString()

        assertFalse(json.contains("\"o\""), "an op that says nothing about the origin must not encode one: $json")
        assertFalse(json.contains("\"inf\""), "an op that says nothing about the mode must not encode one: $json")
    }

    /**
     * ⚠️ **`infinite = false` is a value, not an absence.** Turning an endless page back into a
     * sheet writes `false`, and an encoder that dropped falsey fields would encode that as *"say
     * nothing"* — which folds as *"leave it alone"*, i.e. the page stays endless and the user's
     * choice is silently discarded. The same shape as SPEC §4.2's `SetStyle` having no way to
     * express "unset".
     */
    @Test
    fun turningItOffIsWrittenDownRatherThanOmitted() {
        val off = PageOp.SetPageMeta("op-off", clock(), infinite = false)

        val decoded = OpCodec.decode(OpCodec.encode(off, GeometryWriter()), GeometryReader(ByteArray(0))).op

        assertEquals(false, (decoded as PageOp.SetPageMeta).infinite)
    }

    // -----------------------------------------------------------------------
    // The fold
    // -----------------------------------------------------------------------

    @Test
    fun theFoldAnswersWithTheCanvasTheOpsAskedFor() {
        val folded = foldPage(
            pageId,
            listOf(
                PageOp.SetPageMeta("op-1", clock(), size = PageSize.Letter, background = Background.Blank),
                PageOp.SetPageMeta("op-2", clock(), infinite = true),
            ),
        )

        assertTrue(folded.canvas.infinite)
        assertEquals(PageSize.Letter, folded.size)
        assertEquals(PagePoint(0f, 0f), folded.canvas.origin, "nothing has grown it yet")
    }

    /**
     * The registers are independent, and the fold has to keep them so: a peer changing the paper
     * must not put the canvas back to fixed just because its op is newer.
     */
    @Test
    fun aLaterOpAboutThePaperDoesNotUndoTheCanvasMode() {
        val folded = foldPage(
            pageId,
            listOf(
                PageOp.SetPageMeta("op-1", clock(), infinite = true),
                PageOp.SetPageMeta("op-2", clock(), background = Background.Blank, size = PageSize.A4),
            ),
        )

        assertTrue(folded.canvas.infinite, "a paper change silently turned the canvas back into a sheet")
        assertEquals(PageSize.A4, folded.size)
    }

    // -----------------------------------------------------------------------
    // The bundle
    // -----------------------------------------------------------------------

    @Test
    fun theBundleKeepsAnEndlessPageEndless() = runTest {
        val canvas = PageCanvas(size = PageSize.Letter, origin = PagePoint(-396f, 0f), infinite = true)

        val reread = roundTrip(canvas)

        assertEquals(canvas, reread.canvas)
    }

    @Test
    fun aFixedPageEncodesExactlyAsItDidBeforeAnyOfThisExisted() = runTest {
        val bytes = write(PageCanvas.fixed(PageSize.Letter))

        val text = bytes.decodeToString()
        assertFalse(text.contains("\"origin\""), "a fixed page wrote an origin field into its record")
        assertFalse(text.contains("\"infinite\""), "a fixed page wrote a canvas-mode field into its record")
    }

    private suspend fun roundTrip(canvas: PageCanvas): PageContents {
        val reader = assertNotNull(BundleReader.open(MemoryByteSource(write(canvas))))
        return assertNotNull(reader.page(pageId))
    }

    private fun write(canvas: PageCanvas): ByteArray = BundleWriter.write(
        BundleContents(
            document = Document(
                id = documentId,
                title = "canvas",
                pages = listOf(PageRef(pageId, FractionalKey.Initial)),
                deviceId = "device-a",
            ),
            pages = mapOf(pageId to PageContents(canvas, Background.Blank, emptyList())),
            highestClockCounter = counter,
        ),
    )
}
