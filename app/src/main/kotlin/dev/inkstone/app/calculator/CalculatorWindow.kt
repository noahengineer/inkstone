package dev.inkstone.app.calculator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.inkstone.app.ink.DockRowGap
import dev.inkstone.app.ink.DockSlot
import dev.inkstone.app.ink.DockTopPadding
import dev.inkstone.app.ink.PanelDock
import dev.inkstone.app.ink.PanelSide
import dev.inkstone.app.ink.docked
import dev.inkstone.app.ui.ChipKind
import dev.inkstone.app.ui.GlassPanel
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.PanelPadding
import dev.inkstone.app.ui.TextChip
import kotlin.math.roundToInt

/**
 * ⭐ The calculator window: a design-system frame with a drag strip and a `✕`, around the face.
 *
 * ## Not a `Popup`, `Dialog` or sheet, and no scrim
 *
 * An in-window layer in the screen's root `Box`, ordered by composition — the `ColourPopover`
 * argument: a popup is a second window, and this screen's wet ink is a `SurfaceView` composited over
 * the app's window, which is exactly what this codebase has been bitten by twice. And no scrim,
 * because a calculator is used *while writing*: read a number off the page, compute, write the answer
 * down. A press outside the window must reach the page (research 08 §4.1–4.2).
 *
 * ## ⚠️ …but every press inside it is the window's
 *
 * Compose hit-tests the topmost node first, and the ink surface below never sees an event a node
 * above it took. The face owns its whole rectangle (`CalculatorFace`), the strip and `✕` own theirs,
 * and a [swallowPointers] layer *underneath* them owns the frame between — so a stylus on the
 * window's padding cannot draw a stroke under the calculator that the user cannot see. It is a
 * sibling below the content rather than a parent around it so that it is never on the same hit path
 * as the `✕` chip: a consuming parent cancels a child's click.
 *
 * ## Placement
 *
 * - Sized by [CalculatorPlacement.faceWidth]: the available height with the body's aspect locked (D7).
 * - Placed by `Modifier.offset { }`, so a drag frame is a placement pass and never a recomposition.
 * - **Clamped at read** ([CalculatorPlacement.clamp]): the stored position is the user's; rotation
 *   cannot ratchet it into a corner.
 * - ⭐ **In the editor it sits in the right-hand panel column** (`PanelDock`, docs/UI-RAILS.md §3)
 *   until it is first dragged out of it, at a size that fits the column; *Dock* puts it back. And it
 *   is **resized from its corner** (`ResizeGrip`, `CalculatorPlacement.resized`) — *"resizable by
 *   like dragin in the corner … also not be locked to the center"*. Filling the height between the
 *   top and bottom chrome, as it used to, left a window exactly as tall as the space it could move
 *   in, so it could only ever move sideways.
 * - Until it is first dragged it **hangs from the chip** that opened it (D2) — in the library, which
 *   has no panel column — using the chip's
 *   `boundsInRoot` rebased into this layer's own space. ⚠️ The rebase is not optional: the screen's
 *   `Box` is inset by the `Scaffold` (the status bar), and skipping it *"opens one status bar too
 *   low, which reads as a rounding error"* (M10 §7.2).
 * - Invisible until it has been measured, so it never jumps in from the corner on its first frame
 *   (`AnchoredPanel`'s rule).
 */
@Composable
internal fun CalculatorWindow(
    calc: CalculatorViewModel,
    modifier: Modifier = Modifier,
    dock: PanelDock? = null,
    safeTop: Dp = WindowSafeTop,
    answers: AnswerDrag? = null,
) {
    val frame by calc.frame.collectAsState()
    val stored = calc.position.collectAsState()
    val face by calc.face.collectAsState()
    val density = LocalDensity.current.density
    val placement = remember { WindowPlacement() }
    BoxWithConstraints(modifier.onGloballyPositioned { placement.origin = it.positionInRoot() }) {
        val viewport = Viewport(
            width = maxWidth.value,
            height = maxHeight.value,
            safeTop = safeTop.value,
            bottomReserve = BottomReserve.value,
            edge = InkDims.EdgePadding.value,
        )
        // ⭐ In the panel column until it is first dragged out of it (docs/UI-RAILS.md §3), and sized to
        // fit the column there; anywhere the user likes after that.
        val column = dock?.takeIf { stored.value == null && placement.committed == null && !placement.dragging }
        val docked = column != null
        val faceWidth = placement.fit(
            viewport,
            column,
            density,
            face ?: CalculatorPlacement.DEFAULT_FACE_WIDTH_DP.takeIf { dock != null },
        )
        val shown =
            rememberUpdatedState { placement.shown(stored.value, calc.chipBounds, column?.let(placement::dockedAt)) }
        val commit = rememberUpdatedState { at: WindowPosition ->
            val clamped = CalculatorPlacement.clamp(at, placement.size(), placement.viewport)
            // ⭐ Let go against the panel column's edge, it docks — every panel's rule (docs/LAYOUTS.md).
            if (dock != null && dock.wantsDock(placement.boundsPx(clamped))) {
                placement.docker(calc)()
            } else {
                placement.committed = clamped
                calc.moveTo(clamped)
            }
        }
        // A window out of the column still tells it where it is, so a contextual bar keeps off it.
        DisposableEffect(dock, docked) { onDispose { dock?.reportLoose(DockSlot.CALCULATOR, null) } }
        val onDock = if (dock != null && !docked) placement.docker(calc) else null

        WindowFrame(
            place = Modifier.windowPlace(column, dock, shown, placement).reportingTo(answers),
            faceWidth = faceWidth,
            growsLeft = docked && dock?.side != PanelSide.LEFT,
            placement = placement,
            title = { TitleStrip(faceWidth.dp, Modifier.dragToMove(placement, shown, commit), calc::close, onDock) },
            face = {
                CalculatorFace(
                    frame,
                    calc::press,
                    Modifier.size(
                        faceWidth.dp,
                        (
                            faceWidth *
                                CalculatorPlacement.ASPECT
                            ).dp,
                    ),
                    answers,
                )
            },
            onResized = calc::resizeTo,
        )
    }
}

/** In the column, or at [shown] and telling the column where it is so a contextual bar keeps off it. */
private fun Modifier.windowPlace(
    column: PanelDock?,
    dock: PanelDock?,
    shown: State<() -> WindowPosition>,
    placement: WindowPlacement,
): Modifier = if (column != null) {
    docked(column, DockSlot.CALCULATOR)
} else {
    offset {
        val at = shown.value()
        IntOffset(at.x.dp.roundToPx(), at.y.dp.roundToPx())
    }
        .graphicsLayer { alpha = if (placement.measured == IntSize.Zero) 0f else 1f }
        .onGloballyPositioned { dock?.reportLoose(DockSlot.CALCULATOR, it.boundsInRoot().translate(-placement.origin)) }
}

/** Tells a drag of the answer where the window is, so letting go over it is *"never mind"* (`AnswerDrag`). */
private fun Modifier.reportingTo(answers: AnswerDrag?): Modifier =
    if (answers == null) this else onGloballyPositioned { answers.windowBounds = it.boundsInWindow() }

/** The window: its frame, which takes every press on it, the strip and face inside, and the corner grip. */
@Composable
@Suppress("LongParameterList") // The window's parts, each one thing.
private fun WindowFrame(
    place: Modifier,
    faceWidth: Float,
    growsLeft: Boolean,
    placement: WindowPlacement,
    title: @Composable () -> Unit,
    face: @Composable () -> Unit,
    onResized: (Float) -> Unit,
) {
    Box(place.onSizeChanged { placement.measured = it }) {
        Spacer(Modifier.matchParentSize().swallowPointers())
        GlassPanel {
            title()
            face()
        }
        // ⭐ In a right-hand column the window grows leftwards from its right edge, so the grip that
        // follows the finger is the bottom-left one; floating, or in a left-hand column, its left edge
        // stays put and it is the bottom-right.
        ResizeGrip(
            fromLeft = growsLeft,
            placement = placement,
            startFace = { faceWidth },
            commit = onResized,
            modifier = Modifier.align(if (growsLeft) Alignment.BottomStart else Alignment.BottomEnd),
        )
    }
}

/**
 * The frame's title bar: a caption to drag by, the way back into the panel column when the window has
 * been dragged out of it ([onDock]), and the close glyph (the `ElementPanel` pattern).
 */
@Composable
private fun TitleStrip(width: Dp, drag: Modifier, onClose: () -> Unit, onDock: (() -> Unit)?) {
    Row(Modifier.width(width).height(StripHeight), verticalAlignment = Alignment.CenterVertically) {
        Box(drag.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            MicroLabel("Calculator")
        }
        onDock?.let { TextChip(label = "Dock", onClick = it) }
        TextChip(label = "✕", kind = ChipKind.GLYPH, onClick = onClose, contentDescription = "Close")
    }
}

/**
 * Three short diagonals in a corner of the window: drag them to make the calculator bigger or smaller
 * (`CalculatorPlacement.resized`). The size is live while the finger is down and written once, on
 * release — the window's own drag rule.
 */
@Composable
private fun ResizeGrip(
    fromLeft: Boolean,
    placement: WindowPlacement,
    startFace: () -> Float,
    commit: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val start = rememberUpdatedState(startFace)
    val done = rememberUpdatedState(commit)
    Canvas(
        modifier = modifier
            .size(GripSize)
            .semantics { contentDescription = "Resize calculator" }
            .pointerInput(fromLeft) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val from = start.value()
                    var moved = Offset.Zero
                    try {
                        do {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change != null && change.pressed) {
                                moved += change.positionChange()
                                change.consume()
                                placement.resize = CalculatorPlacement.resized(
                                    from,
                                    moved.x / density,
                                    moved.y / density,
                                    fromLeft,
                                )
                            }
                        } while (change?.pressed == true)
                    } finally {
                        placement.resize?.let { done.value(it) }
                        placement.resize = null
                    }
                }
            },
    ) {
        val inset = size.width * GRIP_INSET
        val stroke = GripStroke.toPx()
        for (step in 1..GRIP_LINES) {
            val reach = (size.width - 2 * inset) * step / GRIP_LINES
            val y = size.height - inset
            if (fromLeft) {
                drawLine(Ink.TextMuted, Offset(inset, y - reach), Offset(inset + reach, y), stroke, StrokeCap.Round)
            } else {
                val x = size.width - inset
                drawLine(Ink.TextMuted, Offset(x - reach, y), Offset(x, y - reach), stroke, StrokeCap.Round)
            }
        }
    }
}

/**
 * What the window's position is made of, as state read only in the placement and draw phases.
 *
 * ⚠️ Every field here is written from composition or a callback and read **only** inside
 * `Modifier.offset { }` / `graphicsLayer { }` / the drag handler, so a drag or a re-measure moves the
 * window without recomposing it or the screen under it — except [dragging], which flips once at each
 * end of a drag so the window can leave the panel column, and [resize], which changes the face's size
 * and so has to recompose it.
 */
@Stable
private class WindowPlacement {
    var viewport by mutableStateOf(Viewport(0f, 0f, 0f, 0f, 0f))
    var estimate by mutableStateOf(WindowSize(0f, 0f))
    var origin by mutableStateOf(Offset.Zero)
    var measured by mutableStateOf(IntSize.Zero)

    /** The live drag, unclamped, in dp; null when nothing is being dragged. */
    var drag by mutableStateOf<WindowPosition?>(null)

    /**
     * Where the last drag was let go, until the controller's position arrives to say the same — so the
     * window does not spend that one frame back in the column.
     */
    var committed by mutableStateOf<WindowPosition?>(null)

    /** True from the first movement of a drag to its end — what takes the window out of the column. */
    var dragging by mutableStateOf(false)

    /** The face's width while its corner is being dragged, in dp; null otherwise. */
    var resize by mutableStateOf<Float?>(null)
    var density = 1f

    /**
     * The face's width, and the window's size and space recorded from it: [preferred] — the user's size,
     * or the column's default — fitted to the column when [column] is the window's place, and to
     * [viewport] when it floats.
     */
    fun fit(viewport: Viewport, column: PanelDock?, density: Float, preferred: Float?): Float {
        val room = column?.let { viewport.copy(height = it.columnHeight() / density, safeTop = 0f, bottomReserve = 0f) }
            ?: viewport
        val face = CalculatorPlacement.faceWidth(room, ChromeWidth.value, ChromeHeight.value, resize ?: preferred)
        this.viewport = viewport
        this.density = density
        estimate = CalculatorPlacement.windowSize(face, ChromeWidth.value, ChromeHeight.value)
        return face
    }

    /** What *Dock* does: forget the last drop, then put the window back in the column. */
    fun docker(calc: CalculatorViewModel): () -> Unit = {
        committed = null
        calc.dock()
    }

    /** The window's place in [column], in dp; null before the column has placed it. */
    fun dockedAt(column: PanelDock): WindowPosition? =
        column.offsetOf(DockSlot.CALCULATOR)?.let { WindowPosition(it.x / density, it.y / density) }

    /** The window at [at] in px — the panel column's space. */
    fun boundsPx(at: WindowPosition): Rect {
        val size = size()
        return Rect(at.x * density, at.y * density, (at.x + size.width) * density, (at.y + size.height) * density)
    }

    /** The window's size in dp: measured once it has been, the computed estimate before that. */
    fun size(): WindowSize =
        if (measured == IntSize.Zero) estimate else WindowSize(measured.width / density, measured.height / density)

    /**
     * Where the window is drawn: the live drag, else the stored place, else its place in the panel
     * column ([docked]), else hanging from the chip.
     */
    fun shown(stored: WindowPosition?, chipInRoot: Rect?, docked: WindowPosition?): WindowPosition {
        val size = size()
        val placed = drag ?: stored ?: committed
        return when {
            placed != null -> CalculatorPlacement.clamp(placed, size, viewport)

            docked != null -> docked

            else -> CalculatorPlacement.clamp(
                CalculatorPlacement.hangFrom(anchorOf(chipInRoot), size, viewport, InkDims.ChipGap.value),
                size,
                viewport,
            )
        }
    }

    /** The chip's root-coordinate bounds, rebased into this layer and turned into dp. */
    private fun anchorOf(chipInRoot: Rect?): Anchor? = chipInRoot?.let {
        Anchor(
            left = (it.left - origin.x) / density,
            top = (it.top - origin.y) / density,
            right = (it.right - origin.x) / density,
            bottom = (it.bottom - origin.y) / density,
        )
    }
}

/**
 * Drags the window by the strip. The gesture starts from where the window is **shown** (already
 * clamped, or its place in the panel column), not from the stored value, so a window pushed in from a
 * portrait edge moves the moment the finger does instead of first paying back the overshoot. On release
 * — or cancellation, which is a second pointer or the window losing the gesture, not a change of mind —
 * the clamped end is committed, and only then does the controller hear about it: one write per drag,
 * not per frame.
 *
 * ⚠️ **Only a drag that moved commits.** A tap on the strip of a window in the column would otherwise
 * write its column place as a position of its own, and take it out of the column for good.
 */
private fun Modifier.dragToMove(
    placement: WindowPlacement,
    shown: State<() -> WindowPosition>,
    commit: State<(WindowPosition) -> Unit>,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        val from = shown.value()
        var moved = Offset.Zero
        try {
            do {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                if (change != null && change.pressed) {
                    moved += change.positionChange()
                    change.consume()
                    if (!placement.dragging && moved.getDistance() > viewConfiguration.touchSlop) {
                        placement.dragging = true
                    }
                    if (placement.dragging) {
                        placement.drag = WindowPosition(from.x + moved.x / density, from.y + moved.y / density)
                    }
                }
            } while (change?.pressed == true)
        } finally {
            placement.drag?.let { commit.value(it) }
            placement.drag = null
            placement.dragging = false
        }
    }
}

/**
 * Takes every gesture that reaches it and does nothing with it. Laid *under* the window's content,
 * it only ever receives presses on the frame between the strip and the face — which would otherwise
 * fall through to the page.
 */
private fun Modifier.swallowPointers(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/** One floating `GlassBar`: a chip plus the bar's padding above and below it. */
private val BarHeight: Dp = InkDims.ChipMinHeight + InkDims.BarPadding * 2

/**
 * The window's top line: below **both** rows of top chrome, never inside the second.
 *
 * ⚠️ Not `DockSafeTop`, which research 08 §4.5 named and this window first clamped against. That
 * line (78 dp) clears the dock's *primary* row only — right for `ContextBars`, because a contextual
 * bar hides row two — but row two is on screen in the editor's **default** state (the pen's
 * brushes, colours and sizes: 66–112 dp, 915 dp wide), and the window is composed under the chrome,
 * so the pen row covered the window's drag strip and its `✕` and took their presses. The library's
 * chrome is the same two rows on the same tokens (toolbar, then breadcrumb), and there the window is
 * the top layer, so it covered `LIBRARY` instead. Built from the dock's own tokens so it moves with
 * them; `CalculatorPlacementTest` asserts it against row two rather than against a copied number.
 */
internal val WindowSafeTop: Dp = DockTopPadding + BarHeight + DockRowGap + BarHeight + InkDims.GroupGap

/** The corner grip: a finger's width, inside the frame's padding as far as it will go. */
private val GripSize: Dp = 22.dp
private val GripStroke: Dp = 1.5.dp
private const val GRIP_LINES = 3
private const val GRIP_INSET = 0.22f

/** The title strip: one chip tall. */
private val StripHeight: Dp = InkDims.ChipMinHeight

/** `GlassPanel`'s padding either side of the face. */
private val ChromeWidth: Dp = PanelPadding * 2

/** `GlassPanel`'s padding above and below, the strip, and the panel's gap between strip and face. */
private val ChromeHeight: Dp = PanelPadding * 2 + StripHeight + InkDims.GroupGap

/**
 * Kept clear at the bottom: the bottom zone's one-chip pill (a chip plus the bar's padding), its
 * edge padding, and a gap (research 08 §4.6). Built from the tokens so it moves when they do.
 */
private val BottomReserve: Dp = BarHeight + InkDims.EdgePadding + InkDims.GroupGap
