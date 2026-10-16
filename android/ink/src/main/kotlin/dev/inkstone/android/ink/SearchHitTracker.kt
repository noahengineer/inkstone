package dev.inkstone.android.ink

import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageRect

/**
 * SPEC §8.4 — which page a search hit belongs to, and so when its box is drawn and when it goes.
 *
 * A hit arrives with no page: `NoteSurface.highlight` is told a box, and the editor has already
 * turned to the hit's page — `NoteEditorViewModel` waits for that turn before sending the box. So a
 * hit belongs to the page it is first **centred** on: the page on screen when it arrives, or the
 * first one to load after it if none is yet (a surface that attached after the turn). Until then, a
 * page loading does not clear it. Once centred, loading any other page clears it, so its box is not
 * drawn on every page the user turns to afterwards.
 *
 * ⚠️ **Sending the box before the turn puts it on the wrong page**, and that shipped: the box
 * arrived while the note was still opening, was centred on the page the note opened at, and the
 * turn — when it happened — cleared it. Invisible on a one-page note.
 *
 * Plain data and no view, so the decision can be tested on the JVM. When to *centre* is
 * [InkSurfaceView]'s: after every refit, which is the half that was broken (see its `highlight`).
 */
internal class SearchHitTracker {

    private var box: PageRect? = null

    /** The page the hit was centred on, once it has been. */
    private var page: PageId? = null

    /** The page being shown: the one whose load began last. */
    private var onScreen: PageId? = null

    /** A new hit, or null to clear the one there is. */
    fun hit(box: PageRect?) {
        this.box = box
        page = null
    }

    /** A page began loading, and is from now the one on screen. */
    fun pageLoading(id: PageId) {
        if (page != null && page != id) {
            box = null
            page = null
        }
        onScreen = id
    }

    /** The box to draw now: the hit's, when the page on screen is (or may become) its page. */
    val drawn: PageRect?
        get() = box?.takeIf { onScreen != null && (page == null || page == onScreen) }

    /** The camera was centred on [drawn]: the hit now belongs to the page on screen. */
    fun centred() {
        if (drawn != null) page = onScreen
    }
}
