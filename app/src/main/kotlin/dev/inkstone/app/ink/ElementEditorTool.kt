package dev.inkstone.app.ink

import dev.inkstone.core.model.ShapeKind

/**
 * ⭐ SPEC §4.9.7 — the Element editor's own tools, beside the ordinary ones the dock still offers.
 *
 * Only what a note does not have: drawing an invisible knockout area, and placing the pivot. Every
 * other tool — pen, eraser, shapes, select, the Knockout chip — is the note editor's own, which is
 * why this is a short list.
 */
enum class ElementEditorTool(val shape: ShapeKind?) {
    /** Drag a rectangle that hides what is beneath it in every copy, and is not itself drawn. */
    AREA_RECT(ShapeKind.RECTANGLE),

    /** The same, as an ellipse. */
    AREA_ELLIPSE(ShapeKind.ELLIPSE),

    /** Tap where a placed copy should land on the grid. */
    PIVOT(null),
}
