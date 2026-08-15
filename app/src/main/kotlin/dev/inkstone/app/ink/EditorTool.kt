package dev.inkstone.app.ink

import dev.inkstone.android.ink.InkTool

/**
 * ⭐ ROADMAP M8c — **what the pen does, as one value.**
 *
 * ## Why this replaced `InkTool` in the UI state rather than sitting beside it
 *
 * M8a's own note on `InkUiState.tool` is the argument, and M8c is the second time it applies:
 *
 * > Was `eraserSelected: Boolean`. Selection made a second boolean the obvious move and a wrong
 * > one: two flags make "eraser and lasso both on" representable and nothing would have resolved
 * > it.
 *
 * The five tools M8c adds are not ink tools — three of them are handled by a Compose overlay that
 * `:android:ink` never sees — so the obvious move here was an `overlayTool: OverlayTool?` beside
 * `tool: InkTool`, which makes *"the text tool and the eraser are both on"* representable in
 * exactly the same way. One enum, one source of truth, and [ink] derives what the surface is told.
 *
 * ## ⚠️ Why the app-level tools map to `SELECT` and not to `PEN`
 *
 * While one of them is active the overlay covers the surface and consumes every touch, so in
 * principle the value underneath cannot matter. It matters when the overlay is wrong. A tool that
 * fell through to `PEN` would draw ink the user did not ask for and journal it; falling through to
 * `SELECT` draws a lasso that writes nothing and clears itself. The failure mode is chosen rather
 * than inherited.
 */
enum class EditorTool(val ink: InkTool) {
    PEN(InkTool.PEN),
    ERASER(InkTool.ERASER),
    SELECT(InkTool.SELECT),

    /** Tap to place a text box, or tap an existing one to edit it. */
    TEXT(InkTool.SELECT),

    /**
     * Select an image to rotate, flip or crop it; the insert buttons are in the toolbar.
     *
     * ⚠️ **This tool does not own touch unless it is cropping**, and that is the fix for *"you can't
     * move an image without the select tool"*. It used to: a full-screen overlay sat in front of the
     * surface and offered a tap and nothing else, so while the one tool that knew about images was
     * active the surface received no events at all and a drag was not merely unimplemented, it was
     * unreachable. Falling through to [InkTool.SELECT] gives move, resize and rotate — the code that
     * already works for every other object — and the bar now follows `InkSelection.soleImage`
     * however the picture got selected.
     */
    IMAGE(InkTool.SELECT),

    /** Drag a box to capture an Element; tap to stamp the one the panel has selected. */
    ELEMENT(InkTool.SELECT),

    /**
     * ROADMAP M8c — the laser pointer. **Writes nothing to the document**: no op, no journal
     * record, no undo entry.
     *
     * ⚠️ Straight through to `InkTool.LASER` — the trail is drawn by `:android:ink`'s own
     * `LaserController`, **not** by an overlay here. Both would have worked in isolation and
     * having both would have been the failure: two pointers, one fading on a frame clock and one
     * on input, drawn at different z and disagreeing under a finger held still. Same reason the
     * ruler is not in this enum at all.
     */
    LASER(InkTool.LASER),
    ;

    /**
     * True when the Compose overlay takes the touches instead of the ink surface.
     *
     * ⚠️ Deliberately **not** true for the laser: the ink surface has its own trail and its own
     * pointer handling, so an overlay in front of it would consume the events that draw it.
     *
     * ⚠️ And the ruler is not a tool here at all. It is a *mode* on `InkSurfaceView.ruler` and the
     * pen keeps drawing while it is on — the snap is applied by handing ink a projection matrix at
     * `ACTION_DOWN`, which is what makes it free on the ink path (CLAUDE.md rule 7). Making it a
     * tool would have made *"the ruler is out"* and *"I am drawing"* mutually exclusive, which is
     * the opposite of what a ruler is for.
     *
     * ⚠️ **[IMAGE] is no longer here**, and its absence is the fix for *"you can't move an image
     * without the select tool"* — see that value's KDoc. Cropping does not bring it back either:
     * `CropOverlay` is its own composable with its own `pointerInput`, shown only while a crop is
     * open, so the one gesture that genuinely needs to be captured says so where it happens
     * instead of making the whole tool modal.
     */
    val ownsTouch: Boolean get() = this == TEXT || this == ELEMENT
}
