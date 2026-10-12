package dev.inkstone.app.ink

import dev.inkstone.android.ink.InkSurfaceView
import dev.inkstone.app.note.NoteEditorViewModel
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.ShapeKind

/**
 * ⭐ SPEC §4.9.7 — what the Element editor's three tools do once a gesture has finished.
 *
 * *"That non-destructive erase is hard to position right with no undo or edit, there is no grid snap
 * for that either, and you should be able to edit the bounds … and use the grid for that."* Each of
 * these lands on the lattice the backdrop draws (`InkSurfaceView.snapGrid`, which is
 * [SnapGrid.None][dev.inkstone.core.model.SnapGrid.None] with the grid switched off), writes ops on the
 * editor's own undo stack, and — for an area — leaves it **selected**, so the next touch can move or
 * resize it with the grips every other object has, snapping as it goes.
 */
class ElementEditorActions(
    private val surface: () -> InkSurfaceView?,
    private val editor: NoteEditorViewModel,
    private val ui: InkUiState,
) {

    /** A knockout area dragged out with Area ▭ / Area ◯: on the grid, then selected. */
    suspend fun addArea(kind: ShapeKind, box: PageRect) {
        val view = surface()
        val placed = view?.snapGrid?.snappedBox(box) ?: box
        val id = editor.insertCutter(kind, placed) ?: return
        // Selected first, then handed the Select tool — `InkSurfaceView.select`'s order.
        view?.select(listOf(id))
        ui.tool = EditorTool.SELECT
    }

    /** The pivot, put down where the Pivot tool was tapped — on a grid intersection when it is on. */
    suspend fun placePivot(at: PagePoint) {
        val view = surface()
        val snapped = view?.snapGrid?.snap(at) ?: at
        // Only used when the bounds were deleted, but measured here: it is the surface's to measure.
        val content = content(view)
        if (!editor.setElementPivot(snapped, content)) editor.report(NOTHING_TO_BOUND)
    }

    /**
     * *Fit*: the bounds made the content's own box, widened outward to whole grid cells so every edge
     * lands on a line. Recreates bounds somebody deleted.
     */
    suspend fun fit() {
        val view = surface()
        val box = content(view) ?: run {
            editor.report(NOTHING_TO_BOUND)
            return
        }
        editor.fitElementFrame(view?.snapGrid?.enclosing(box) ?: box)
    }

    private suspend fun content(view: InkSurfaceView?): PageRect? =
        view?.boundsOf(editor.elementPieces().toSet())?.takeIf { it.width > 0f && it.height > 0f }

    private companion object {
        const val NOTHING_TO_BOUND = "Draw something first — the bounds fit what is on the canvas"
    }
}
