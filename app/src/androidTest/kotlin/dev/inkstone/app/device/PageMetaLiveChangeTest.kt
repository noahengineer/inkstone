package dev.inkstone.app.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.Offset
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PageTemplate
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * ⭐ **`InkSurfaceView.setPageMeta` — the method whose absence was a green test over a `= Unit`.**
 *
 * `NoteEditor.reconcile` diffs the fold and, when the paper or the extent changed, calls
 * `NoteSurface.setPageMeta`. The only production implementation of that interface was
 * `InkScreen`'s `override fun setPageMeta(…) = Unit` for the whole of M6, while
 * `FakeSurface.setPageMeta` in the JVM suite *recorded* the call and a test asserted the recording.
 * So there were assertions, they were true, and nothing on screen changed — CLAUDE.md's
 * *"correct in every test and inert in the app"*, with the fixture more generous than production.
 *
 * That gap is not only a sync one, which is what made it worth fixing rather than deferring: the
 * **undo of a local template change** takes the identical path, so *"pick a paper, press undo"* moved
 * the PageBar's label and left the page alone. One device, by hand, no peer involved.
 *
 * This is the half a JVM test cannot reach. What it asserts is not "the paper changed" alone but the
 * three-part claim SPEC §13.2 row 10 actually makes, because a paper change that repaints by
 * *reloading the page* satisfies the first part and destroys the other two:
 *
 * 1. the paper and the extent really change;
 * 2. **the camera does not move** — `leashedTo` keeps the pan and zoom, where `resetCamera` would
 *    replace both, unrecoverably, since the camera is persisted nowhere;
 * 3. **the stroke under the pen survives** and still lands when the pen lifts.
 *
 * `the forbidden path` in [WetInkRemoteMergeTest] is the negative control for the same three: it
 * drives `loadPage` and asserts the damage, so a `setPageMeta` that quietly delegated to `loadPage`
 * could not pass both files.
 */
@RunWith(AndroidJUnit4::class)
class PageMetaLiveChangeTest {

    private val host = InkHostDriver(DEVICE_ID)

    @AfterTest
    fun close() {
        host.close()
    }

    @Test
    fun `changing the paper mid stroke keeps the camera and the stroke`() {
        host.launch(pageSize = PageSize.Letter)
        // A dry stroke first, so "the page was cleared" and "the page was empty" cannot be confused.
        host.beginStroke(InkHostDriver.HARVEST_DOWN_X, InkHostDriver.HARVEST_DOWN_Y)
        host.endStroke(InkHostDriver.HARVEST_UP_X, InkHostDriver.HARVEST_UP_Y)
        host.awaitStrokeCount(1)

        host.beginStroke(InkHostDriver.PEN_DOWN_X, InkHostDriver.PEN_DOWN_Y)
        val cameraMidStroke = host.onSurface { it.camera }
        val paperBefore = host.onSurface { it.pageBackground }

        host.onSurface { it.setPageMeta(PageSize.Letter, Background.Template(PageTemplate.CORNELL)) }

        assertNotEquals(paperBefore, host.onSurface { it.pageBackground }, "the paper did not change")
        assertEquals(
            Background.Template(PageTemplate.CORNELL),
            host.onSurface { it.pageBackground },
            "the paper is not the one that was asked for",
        )
        assertEquals(
            cameraMidStroke,
            host.onSurface { it.camera },
            "the camera moved under the pen — the user's zoom and pan are gone and were never saved",
        )
        assertEquals(
            1,
            host.onSurface { it.strokeCount },
            "the dry stroke was cleared, so this repainted by rebuilding the page",
        )

        host.endStroke(InkHostDriver.PEN_UP_X, InkHostDriver.PEN_UP_Y)
        assertEquals(
            2,
            host.awaitStrokeCount(2),
            "the stroke in progress when the paper changed never finished",
        )
    }

    @Test
    fun `changing the page extent re-leashes the camera instead of refitting it`() {
        host.launch(pageSize = PageSize.Letter)
        host.beginStroke(InkHostDriver.HARVEST_DOWN_X, InkHostDriver.HARVEST_DOWN_Y)
        host.endStroke(InkHostDriver.HARVEST_UP_X, InkHostDriver.HARVEST_UP_Y)
        host.awaitStrokeCount(1)

        // ⚠️ Zoomed away from the fit first, and asserted, because **a fit-width camera is
        // `resetCamera`'s own fixed point**: from there a refit and a re-leash return the same value
        // and the assertion below would pass over the bug it exists to catch.
        val fitted = host.onSurface { it.camera }
        val zoomed = host.onSurface { surface ->
            surface.toggleZoom(Offset(ZOOM_FOCUS_X, ZOOM_FOCUS_Y))
            surface.camera
        }
        assertNotEquals(fitted.scale, zoomed.scale, "the zoom did not take, so this test proves nothing")

        // A4 is *narrower* than Letter, which is the direction that matters: the leash tightens, so a
        // re-leash may legitimately clamp the pan. Scale must survive either way — that is the whole
        // difference between clamping and refitting.
        host.onSurface { it.setPageMeta(PageSize.A4, Background.Template(PageTemplate.RULED)) }

        assertEquals(PageSize.A4, host.onSurface { it.pageSize }, "the extent did not change")
        assertEquals(
            zoomed.scale,
            host.onSurface { it.camera.scale },
            "the zoom changed, so the camera was refit rather than re-leashed",
        )
        assertEquals(1, host.onSurface { it.strokeCount }, "the page was rebuilt to change its extent")
    }

    @Test
    fun `an unchanged paper and extent is not a repaint`() {
        host.launch(pageSize = PageSize.Letter)
        host.onSurface { it.setPageMeta(PageSize.Letter, Background.Template(PageTemplate.RULED)) }
        val camera = host.onSurface { it.camera }

        // The common case on the pull side: a poll re-delivers a peer's whole log, the folds come out
        // identical, and reaching the surface with nothing to say would redraw the page once per poll
        // on every open note. `NoteEditor.reconcile` guards this too; the surface guards it as well
        // because the guard here is the one that protects the camera.
        host.onSurface { it.setPageMeta(PageSize.Letter, Background.Template(PageTemplate.RULED)) }

        assertEquals(camera, host.onSurface { it.camera })
        assertEquals(PageSize.Letter, host.onSurface { it.pageSize })
    }

    @Test
    fun `loading a page still refits deliberately`() {
        // The counterpart claim, so removing the refit from `pageSize`'s setter cannot have quietly
        // removed it from the one caller that wants it. A page *switch* is a different piece of paper
        // and the old camera means nothing on it.
        host.launch(pageSize = PageSize.Letter)
        host.beginStroke(InkHostDriver.HARVEST_DOWN_X, InkHostDriver.HARVEST_DOWN_Y)
        host.endStroke(InkHostDriver.HARVEST_UP_X, InkHostDriver.HARVEST_UP_Y)
        host.awaitStrokeCount(1)
        val fitted = host.onSurface { surface ->
            surface.toggleZoom(Offset(ZOOM_FOCUS_X, ZOOM_FOCUS_Y))
            surface.camera
        }

        host.onSurface { it.loadPage(FoldedPage.empty(PAGE, size = PageSize.A4)) }

        assertNotEquals(fitted, host.onSurface { it.camera }, "loadPage stopped refitting the camera")
        assertEquals(0, host.onSurface { it.strokeCount }, "loadPage stopped clearing the page")
        assertNull(host.onSurface { it.sourcePage }, "loadPage stopped clearing the rasterised paper")
    }

    private companion object {
        const val DEVICE_ID = "page-meta-device"
        val PAGE = PageId("page-meta-page")
        const val ZOOM_FOCUS_X = 300f
        const val ZOOM_FOCUS_Y = 300f
    }
}
