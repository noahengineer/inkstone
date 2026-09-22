package dev.inkstone.app.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.inkstone.core.model.Offset
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.pageToView
import dev.inkstone.core.model.viewToPage
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⭐ **Ink stops where the paper stops** — the bug this file exists for, driven the way a user
 * reaches it.
 *
 * SPEC §4.5 makes panning free, so the empty space beside the page is one finger-drag away and is
 * exactly as easy to write in as the page itself. A stroke made there was captured, journaled,
 * put on the undo stack and synced like any other — at a **negative page-space x**, which means
 * outside the CropBox, which means **no PDF reader draws it**. The app showed the user ink that
 * every other program would drop, and nothing anywhere said so.
 *
 * Confirmed on an emulator before the fix, by drawing at device x = 300 with the paper starting at
 * x ≈ 713 and reading the resulting `add` op straight out of `journal.ops`.
 *
 * ## What this asserts, and what it cannot
 *
 * The refusal is at the ACTION_DOWN, so this can watch the whole of it: nothing is recorded and
 * nothing is drawn. What it deliberately also asserts is the **control** — that the identical
 * gesture on the paper does produce a stroke — because a harness that had quietly stopped drawing
 * at all would otherwise make the real assertion pass for the wrong reason.
 *
 * What it does not reach is the *clip*: whether a stroke that begins on the paper and runs off the
 * edge is rendered cut at the edge. That is a pixel question about a front-buffered surface, and
 * an x86_64 emulator renders this view as garbage (CLAUDE.md). What is asserted here instead is
 * the half that is decidable — that such a stroke is **kept**, not refused — since clipping and
 * refusing are the two designs and only one of them keeps the ink.
 */
@RunWith(AndroidJUnit4::class)
class CanvasBoundsTest {

    private val driver = InkHostDriver(deviceId = "canvas-bounds")

    @After
    fun tearDown() = driver.close()

    /** The whole bug: a stroke begun beside the paper is not a stroke. */
    @Test
    fun aStrokeBegunBesideThePaperIsRefused() {
        driver.launch(pageSize = PageSize.Letter)
        val besideThePaper = panUntilThereIsRoomBesideThePaper()

        driver.beginStroke(besideThePaper, midHeight())
        driver.endStroke(besideThePaper + STROKE_DX, midHeight() + STROKE_DY)

        // Given a moment to be wrong in: a finished stroke arrives through a listener, so
        // asserting immediately would pass even if the stroke were on its way.
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(0, driver.onSurface { it.strokeCount }, "ink beside the paper was captured")
        assertTrue(
            driver.onSurface { it.drainOps() }.flatten().isEmpty(),
            "ink beside the paper reached the op log, where sync and the PDF would carry it",
        )
    }

    /**
     * The control. Same harness, same gesture, on the paper — where it must still work.
     *
     * Without this, "no stroke was recorded" is equally consistent with the gate being right and
     * with the driver having stopped drawing altogether.
     */
    @Test
    fun theSameStrokeOnThePaperIsKept() {
        driver.launch(pageSize = PageSize.Letter)
        panUntilThereIsRoomBesideThePaper()
        val onThePaper = viewXOf(PAGE_INSET_PT)

        driver.beginStroke(onThePaper, midHeight())
        driver.endStroke(onThePaper + STROKE_DX, midHeight() + STROKE_DY)

        assertEquals(1, driver.awaitStrokeCount(1))
    }

    /**
     * A stroke that starts on the paper and runs off the edge is **kept**, not thrown away.
     *
     * This is the difference between clipping and refusing, and it is the one that matters for
     * CLAUDE.md rule 1: the geometry stays whole in the log, so making the page bigger — or
     * turning the canvas infinite — brings all of it back with nothing to reconstruct. Only the
     * *rendering* stops at the paper's edge, which is what the PDF does with it in any case.
     */
    @Test
    fun aStrokeThatRunsOffTheEdgeIsKeptAndNotRefused() {
        driver.launch(pageSize = PageSize.Letter)
        val besideThePaper = panUntilThereIsRoomBesideThePaper()
        val nearTheEdge = viewXOf(PAGE_INSET_PT)

        // Right to left, from the page out into the margin: the down is on the paper, so the
        // stroke begins; the tail is not, so the renderer is the only thing that cuts it.
        driver.beginStroke(nearTheEdge, midHeight())
        driver.endStroke(besideThePaper, midHeight() + STROKE_DY)

        assertEquals(1, driver.awaitStrokeCount(1), "a stroke crossing the edge was refused outright")
    }

    // -----------------------------------------------------------------------
    // ⭐ The infinite canvas (SPEC §4.5)
    // -----------------------------------------------------------------------

    /**
     * ⭐ The other half: on an endless page the same stroke is **kept**, and the paper comes to
     * meet it.
     *
     * Both assertions matter and they fail differently. A stroke that was refused would leave the
     * count at zero — the gate firing on a page that has no edge. A stroke that was kept while the
     * paper stayed where it was is the worse of the two and the quieter: the ink is in the log, on
     * screen, and outside the box every PDF reader will draw, which is the exact state this whole
     * change exists to make unreachable.
     */
    @Test
    fun anInfiniteCanvasGrowsToMeetTheStrokeInsteadOfRefusingIt() {
        driver.launch(pageSize = PageSize.Letter, infinite = true)
        val besideThePaper = panUntilThereIsRoomBesideThePaper()
        val before = driver.onSurface { it.pageCanvas }

        driver.beginStroke(besideThePaper, midHeight())
        driver.endStroke(besideThePaper + STROKE_DX, midHeight() + STROKE_DY)

        assertEquals(1, driver.awaitStrokeCount(1), "an endless page refused a stroke")
        val after = driver.onSurface { it.pageCanvas }
        assertTrue(after.left < before.left, "the paper did not grow to the left: $before then $after")
        assertTrue(
            after.contains(pageXOf(besideThePaper), 0f, slackPt = 0f) ||
                pageXOf(besideThePaper) >= after.left,
            "the stroke's own down point is still off the paper: $after",
        )
    }

    /** A fixed page next door still refuses, so the gate is the mode's and not the harness's. */
    @Test
    fun theSameGestureOnAFixedPageStillDrawsNothing() {
        driver.launch(pageSize = PageSize.Letter, infinite = false)
        val besideThePaper = panUntilThereIsRoomBesideThePaper()
        val before = driver.onSurface { it.pageCanvas }

        driver.beginStroke(besideThePaper, midHeight())
        driver.endStroke(besideThePaper + STROKE_DX, midHeight() + STROKE_DY)

        Thread.sleep(SETTLE_MILLIS)
        assertEquals(0, driver.onSurface { it.strokeCount })
        assertEquals(before, driver.onSurface { it.pageCanvas }, "a fixed page's paper moved")
    }

    // -----------------------------------------------------------------------
    // Getting to the empty space beside the paper
    // -----------------------------------------------------------------------

    /**
     * Pan the page to the right until there is room to the left of it, and answer a view x that
     * is well outside the paper.
     *
     * Fails rather than skips when the leash will not allow it: a surface too narrow to show any
     * margin at all would make every assertion in this file vacuous, and a test that quietly
     * proves nothing is the failure mode CLAUDE.md's *"distrust a fixture"* note is about.
     */
    private fun panUntilThereIsRoomBesideThePaper(): Float {
        val width = driver.onSurface { it.width }.toFloat()
        driver.panBy(fromX = width * PAN_FROM, fromY = midHeight(), dx = width * PAN_FRACTION, dy = 0f)

        val paperLeft = viewXOf(0f)
        assertTrue(
            paperLeft > MARGIN_NEEDED_PX,
            "panning left no room beside the paper (its left edge is at $paperLeft), so nothing here is a test",
        )
        return paperLeft - MARGIN_NEEDED_PX / 2f
    }

    /** Where page-space x lands on screen, under the camera as it is now. */
    private fun viewXOf(pageX: Float): Float = driver.onSurface { pageToView(PagePoint(pageX, 0f), it.camera).x }

    /** The inverse of [viewXOf]. */
    private fun pageXOf(viewX: Float): Float = driver.onSurface { viewToPage(Offset(viewX, 0f), it.camera).x }

    private fun midHeight(): Float = driver.onSurface { it.height }.toFloat() / 2f

    private companion object {
        /** Start the pan on the left of the surface and drag right, so the paper follows. */
        const val PAN_FROM = 0.2f
        const val PAN_FRACTION = 0.5f

        /** How much clear space beside the paper the assertions need, in pixels. */
        const val MARGIN_NEEDED_PX = 120f

        /** A comfortable distance inside the paper's left edge, in page points. */
        const val PAGE_INSET_PT = 40f

        const val STROKE_DX = 60f
        const val STROKE_DY = 30f

        /** Long enough for a finished stroke to have come back through the listener. */
        const val SETTLE_MILLIS = 750L
    }
}
