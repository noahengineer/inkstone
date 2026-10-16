package dev.inkstone.app.calculator

/**
 * Where the user left the calculator window: its top-left corner, in dp, in the coordinates of the
 * screen's root `Box` (which is the layer's own space — see `CalculatorWindow`).
 *
 * Stored **unclamped**, and that is the point of the type: see [CalculatorPlacement.clamp].
 */
data class WindowPosition(val x: Float, val y: Float)

/** A window's outer size, in dp. */
internal data class WindowSize(val width: Float, val height: Float)

/**
 * The space a window may occupy, in dp, local to the layer.
 *
 * @param safeTop nothing above this line — both rows of top chrome (`WindowSafeTop`).
 * @param bottomReserve kept clear at the bottom for the history · page bar · zoom pills, and in the
 *   library for the `CALC` chip and the create bar.
 * @param edge the gap kept at the left and right edges (`InkDims.EdgePadding`).
 */
internal data class Viewport(
    val width: Float,
    val height: Float,
    val safeTop: Float,
    val bottomReserve: Float,
    val edge: Float,
)

/** The chip the window hangs from on first open, in dp, already rebased into the layer's space. */
internal data class Anchor(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * ⭐ The calculator window's size and position, as plain arithmetic over dp.
 *
 * Hoisted out of `CalculatorWindow` for the reason `ContextBarPlacement` was: a composable cannot be
 * asserted on in a JVM test, and placement is exactly the kind of decision that looks right in a
 * screenshot of one orientation and is wrong in the other (docs/M10-PORTRAIT.md).
 *
 * ## ⚠️ Clamp at read, never at write
 *
 * The stored position is the user's, unclamped; [clamp] runs on every layout against the current
 * viewport. Rotating to portrait pushes a window that sat at the right of a landscape screen in
 * from the edge, and rotating back returns it to exactly where it was left. Clamping the *stored*
 * value instead ratchets it: every rotation would drag the window a little further into a corner,
 * and it would never come back (research 08 §4.5; the same reasoning as M10 §10's *"the ladder is
 * not re-sorted during an edit"*). `CalculatorPlacementTest` asserts both halves.
 */
internal object CalculatorPlacement {

    /**
     * Height over width of the real body: 161.5 mm by 77 mm (research 01 §2.1, VERIFIED from the
     * guide p.91 and Casio's catalogue). Locked — docs/CALCULATOR.md D7.
     */
    const val ASPECT = FaceMm.HEIGHT / FaceMm.WIDTH

    /**
     * The widest the face is ever drawn, in dp.
     *
     * Research 08 §4.6: in portrait the height is plentiful and *"cap the height at about 700–720 dp,
     * which gives about 340 dp wide"*. The fit-to-height rule alone would draw a portrait face about
     * 500 dp wide — the real object's own size, and two thirds of the screen a user is writing on.
     * A decision rather than a fact; docs/CALCULATOR.md §2 lists size control as still open.
     */
    const val MAX_FACE_WIDTH_DP = 340f

    /** Below this the 50 keys stop being pressable at all; a viewport this small is not a tablet. */
    const val MIN_FACE_WIDTH_DP = 160f

    /**
     * ⭐ The face's width before the user has sized it, in the editor's panel column (docs/UI-RAILS.md §3).
     * Filling the height between the chrome made it the tallest thing on the screen, with no room
     * beside it in the column and no room to move up or down — *"it should also not be locked to the
     * center"*. At 200 dp a landscape tablet leaves it about 130 dp of travel; the corner makes it bigger.
     */
    const val DEFAULT_FACE_WIDTH_DP = 200f

    /** The widest a user can drag the face, before the screen's own limit applies. */
    const val MAX_RESIZED_FACE_WIDTH_DP = 480f

    /**
     * The face's width in dp: [preferred] — the user's own size from the corner grip — or, with none, as
     * tall as the space between the top chrome and the bottom zone allows and never wider than
     * [MAX_FACE_WIDTH_DP]. Either way the body's aspect is locked (D7) and it fits [viewport].
     *
     * @param chromeWidth the window's frame either side of the face.
     * @param chromeHeight the frame plus the drag strip above the face.
     */
    fun faceWidth(viewport: Viewport, chromeWidth: Float, chromeHeight: Float, preferred: Float? = null): Float {
        val byHeight = (viewport.height - viewport.safeTop - viewport.bottomReserve - chromeHeight) / ASPECT
        val byWidth = viewport.width - 2 * viewport.edge - chromeWidth
        val wanted = preferred?.coerceIn(MIN_FACE_WIDTH_DP, MAX_RESIZED_FACE_WIDTH_DP) ?: MAX_FACE_WIDTH_DP
        return minOf(byHeight, byWidth, wanted).coerceAtLeast(MIN_FACE_WIDTH_DP)
    }

    /**
     * ⭐ The face's width after its corner has been dragged by ([dx], [dy]) dp from where it was [start]
     * dp wide — *"resizable by like dragin in the corner"*.
     *
     * The drag is projected onto the window's own diagonal, because the aspect is locked (D7): a finger
     * moving straight down a corner has to grow the window as surely as one moving along the diagonal,
     * and one moving across the diagonal should do nothing much. [fromLeft] is the bottom-*left* grip of a
     * window in the right-hand column, which grows leftwards — dragging that one left makes it bigger.
     */
    fun resized(start: Float, dx: Float, dy: Float, fromLeft: Boolean): Float {
        val across = if (fromLeft) -dx else dx
        val grow = (across + dy * ASPECT) / (1f + ASPECT * ASPECT)
        return (start + grow).coerceIn(MIN_FACE_WIDTH_DP, MAX_RESIZED_FACE_WIDTH_DP)
    }

    /** The window's outer size for a face of [faceWidth] — the aspect is the face's, not the frame's. */
    fun windowSize(faceWidth: Float, chromeWidth: Float, chromeHeight: Float): WindowSize =
        WindowSize(faceWidth + chromeWidth, faceWidth * ASPECT + chromeHeight)

    /**
     * [position] moved the least distance that puts the window inside [viewport]: never above
     * [Viewport.safeTop], never into [Viewport.bottomReserve], [Viewport.edge] from either side.
     *
     * A window larger than the space keeps its **top-left** on screen (the LCD and the drag strip),
     * which is the half a user needs to move it somewhere it fits.
     */
    fun clamp(position: WindowPosition, size: WindowSize, viewport: Viewport): WindowPosition {
        val maxX = (viewport.width - size.width - viewport.edge).coerceAtLeast(viewport.edge)
        val maxY = (viewport.height - viewport.bottomReserve - size.height).coerceAtLeast(viewport.safeTop)
        return WindowPosition(position.x.coerceIn(viewport.edge, maxX), position.y.coerceIn(viewport.safeTop, maxY))
    }

    /**
     * Where a window that has never been dragged goes: hanging below the chip that opened it, centred
     * on it, flipped above when it will not fit below — `AnchoredPanel`'s arithmetic — then clamped.
     *
     * The user's two words were *"drop down or movable window"*; this is the drop-down half, and the
     * first drag is the movable half (docs/CALCULATOR.md D2). With no anchor yet (the chip has not
     * been laid out) the window starts at the top-left of the free space rather than not at all.
     *
     * The library's window. The editor's goes in its panel column until it is first dragged
     * (docs/UI-RAILS.md §3).
     */
    fun hangFrom(anchor: Anchor?, size: WindowSize, viewport: Viewport, gap: Float): WindowPosition {
        val wanted = if (anchor == null) {
            WindowPosition(viewport.edge, viewport.safeTop)
        } else {
            val below = anchor.bottom + gap
            val fits = below + size.height <= viewport.height - viewport.bottomReserve
            WindowPosition(
                x = (anchor.left + anchor.right) / 2f - size.width / 2f,
                y = if (fits) below else anchor.top - size.height - gap,
            )
        }
        return clamp(wanted, size, viewport)
    }
}
