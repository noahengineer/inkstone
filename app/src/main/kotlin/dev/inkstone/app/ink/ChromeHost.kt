package dev.inkstone.app.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import kotlin.math.roundToInt

/**
 * ⭐ Every bar of the editor's chrome, placed by one layout — in its stock slot, or where the user put
 * it ([ChromeLayout]) — including the bars the user made by dropping a chip on the page.
 *
 * ## Why one `Layout` and not the `Row` and `align`s it replaced
 *
 * 1. **The stock slots are reproduced exactly.** The dock is centred at the top; the record chip and
 *    the calculator take the corners beside it; history and zoom take the bottom corners; and the
 *    page bar is measured into **the space between them** and centred there — the `weight(1f)` box
 *    the old bottom row used, which was a bug fix and not tidiness (the page bar ran under the zoom
 *    widget when the three were aligned independently). A bar that has been moved leaves its slot,
 *    and the page bar gets the room it left.
 * 2. **A bar must not change parent when it starts moving.** The drag handle's gesture is a coroutine
 *    on the handle's node; had the first pixel of a drag moved the bar from a `Row` into a floating
 *    layer, that node would have been disposed and the drag cancelled mid-gesture. Here a bar is the
 *    same child of the same layout from open to close, and moving it is a placement pass.
 *
 * ## ⚠️ Nothing here recomposes during a drag
 *
 * The live drag offset ([ChromeDrag.offset]) is read only in the placement block, so a moving bar is
 * a re-layout per frame and never a recomposition. The layout is committed to [InkUiState.layout]
 * once, on lift.
 *
 * ## Coordinates and order
 *
 * [ChromeGeometry] records each bar's *pill* — its content without the edit handles — in this
 * layout's own px, which is the space a [ChromePosition] is a fraction of. Opening the editor adds
 * handles beside each bar ([insets]); a moved bar is shifted left by the left-hand ones so its pill
 * does not jump.
 *
 * Children are placed — and therefore drawn and hit-tested — stock bars first, then the floating ones
 * oldest first, so two bars dragged on top of each other overlap the same way every time.
 */
@Composable
internal fun ChromeHost(
    ui: InkUiState,
    geometry: ChromeGeometry,
    modifier: Modifier = Modifier,
    pagesInRow: Boolean = ui.layout.arrangement(PagesBar) == ChromeArrangement.ROW,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = modifier.fillMaxSize().onGloballyPositioned { geometry.origin = it.positionInRoot() },
    ) { measurables, constraints ->
        val (placed, space) = measureChrome(measurables, constraints, ui, pagesInRow)
        geometry.update(space)
        layout(constraints.maxWidth, constraints.maxHeight) {
            val order = ChromeBar.entries.map(BarId::Stock) +
                placed.keys.filterIsInstance<BarId.Floating>().sortedBy { it.n } + LabelSlot
            order.forEach { key ->
                val placeable = placed[key] ?: return@forEach
                val at = when (key) {
                    DrawBar -> ui.placeSecondary(placeable, space, geometry)
                    is BarId -> ui.place(key, placeable, stockSlot(key, placeable, space), space, geometry)
                    else -> Offset(space.edge, space.edge)
                }
                placeable.place(at.x.roundToInt(), at.y.roundToInt())
            }
        }
    }
}

/** The Element editor's label, which sits where the calculator chip would and is never moved. */
internal object LabelSlot

private val DockBar = BarId.Stock(ChromeBar.DOCK)
private val DrawBar = BarId.Stock(ChromeBar.DRAW)
private val PagesBar = BarId.Stock(ChromeBar.PAGES)

/**
 * Every bar measured against the screen less its edges — except the page bar in its stock slot as a
 * row, which is measured into what history and zoom leave of the bottom edge.
 */
private fun MeasureScope.measureChrome(
    measurables: List<Measurable>,
    constraints: Constraints,
    ui: InkUiState,
    pagesInRow: Boolean,
): Pair<Map<Any, Placeable>, HostSpace> {
    val width = constraints.maxWidth
    val edge = InkDims.EdgePadding.roundToPx()
    val loose = Constraints(
        maxWidth = (width - 2 * edge).coerceAtLeast(0),
        maxHeight = (constraints.maxHeight - 2 * edge).coerceAtLeast(0),
    )
    val byKey = measurables.mapNotNull { m -> m.layoutId?.let { it to m } }.toMap()
    val placed = HashMap<Any, Placeable>()
    byKey.forEach { (key, measurable) ->
        if (key != PagesBar &&
            key != DrawBar
        ) {
            placed[key] = measurable.measure(loose)
        }
    }

    val (left, right) = bottomGap(placed, ui, width, edge)
    byKey[PagesBar]?.let { pages ->
        val between = loose.copy(maxWidth = (right - left).coerceAtLeast(0))
        placed[PagesBar] = pages.measure(if (pagesInRow && ui.isStock(ChromeBar.PAGES)) between else loose)
    }
    val (utilityRight, calculatorLeft) = utilityCorner(placed, ui, width - edge)
    byKey[DrawBar]?.let { draw -> placed[DrawBar] = draw.measure(drawRoom(placed, ui, loose, utilityRight)) }
    return placed to HostSpace(
        width.toFloat(),
        constraints.maxHeight.toFloat(),
        edge.toFloat(),
        density,
        left,
        right,
        utilityRight.toFloat(),
        calculatorLeft,
    )
}

/** What history and zoom, in their stock corners, leave of the bottom edge for the page bar, in px. */
private fun MeasureScope.bottomGap(placed: Map<Any, Placeable>, ui: InkUiState, width: Int, edge: Int): Pair<Int, Int> {
    val zoneGap = ZoneGap.roundToPx()
    val history = placed[BarId.Stock(ChromeBar.HISTORY)]?.takeIf { it.width > 0 && ui.isStock(ChromeBar.HISTORY) }
    val zoom = placed[BarId.Stock(ChromeBar.ZOOM)]?.takeIf { it.width > 0 && ui.isStock(ChromeBar.ZOOM) }
    return edge + (history?.let { it.width + zoneGap } ?: 0) to width - edge - (zoom?.let { it.width + zoneGap } ?: 0)
}

/**
 * The room the pen's row has: everything, unless it is the top edge between a column of tools in the
 * top-left corner and the record chip in the top-right — the corner "L" (docs/UI-RAILS.md). Measured
 * into that gap, the row scrolls (and fades at its end) on a narrow screen rather than running under
 * the record chip, which is the M10 page bar's lesson applied to the top edge.
 */
private fun MeasureScope.drawRoom(
    placed: Map<Any, Placeable>,
    ui: InkUiState,
    loose: Constraints,
    utilityRight: Int,
): Constraints {
    val dock = placed[DockBar]?.takeIf { it.width > 0 && it.height > it.width && ui.isStock(ChromeBar.DOCK) }
        ?: return loose
    val record = placed[BarId.Stock(ChromeBar.RECORD)]?.takeIf { it.width > 0 && ui.isStock(ChromeBar.RECORD) }
    val gap = DockRowGap.roundToPx()
    val end = utilityRight - (record?.let { it.width + UtilityGap.roundToPx() } ?: 0)
    val start = InkDims.EdgePadding.roundToPx() + dock.width + gap
    return loose.copy(maxWidth = (end - start).coerceIn(0, loose.maxWidth))
}

/**
 * Where the record chip's right edge goes, and whether the calculator takes the top-*left* corner.
 *
 * ⭐ The calculator takes the top-right corner and the record chip sits beside it, so the record pill
 * grows *leftwards* when a lecture starts and the calculator never moves under a finger.
 *
 * ⚠️ Not while the tools are a row across the top: that is M10's dock, which in portrait already abuts
 * the record chip, and a second pill beside it would run under the dock. The calculator then goes back
 * to the top-left corner it had, so a user who chose the row gets M10's top edge exactly.
 */
private fun MeasureScope.utilityCorner(
    placed: Map<Any, Placeable>,
    ui: InkUiState,
    rightEdge: Int,
): Pair<Int, Boolean> {
    val dock = placed[DockBar]?.takeIf { it.width > 0 }
    val dockAcross = dock != null && dock.width > dock.height && ui.isStock(ChromeBar.DOCK)
    val calculator = placed[BarId.Stock(ChromeBar.CALCULATOR)]
        ?.takeIf { it.width > 0 && ui.isStock(ChromeBar.CALCULATOR) && !dockAcross }
    return rightEdge - (calculator?.let { it.width + UtilityGap.roundToPx() } ?: 0) to dockAcross
}

/**
 * The host's own px, the density its dp were measured in, the stock page bar's span as a row, and
 * where the record chip's right edge goes — passed around as one value.
 */
internal class HostSpace(
    val width: Float,
    val height: Float,
    val edge: Float,
    val density: Float,
    val pagesLeft: Int = 0,
    val pagesRight: Int = 0,
    val utilityRight: Float = width - edge,
    val calculatorLeft: Boolean = false,
)

/**
 * The stock top-left of [id]'s whole child, edit handles included. A floating bar's is the middle.
 *
 * ⭐ **The tools and the pages go by their measured shape**, not by a name (docs/UI-RAILS.md). Taller
 * than wide — a column, or a grid that came out tall — and the tools sit in the **top-left corner**
 * (the pen's row then runs along the top beside them), and the pages down the right. Wider than tall
 * and they are the original M10 dock across the top and page bar across the bottom, slot for slot — so
 * a user who sets the tools back to a row gets exactly what shipped before, and the Element editor's
 * Done bar, which stands in the page bar's place, still sits along the bottom.
 *
 * ⚠️ The corner and not centred top to bottom: centred, the column was in the middle of the page's
 * left margin, where people write, with empty space above it that nothing used — *"taking up a lot
 * of unnecessary space … at least move the tools bar to the top left corner"*.
 */
private fun stockSlot(id: BarId, placeable: Placeable, space: HostSpace): Offset {
    val w = placeable.width.toFloat()
    val h = placeable.height.toFloat()
    val bottom = space.height - space.edge - h
    val middle = (space.height - h) / 2f
    val upright = h > w
    if (id !is BarId.Stock) return Offset((space.width - w) / 2f, middle)
    val right = space.width - space.edge - w
    return when (id.bar) {
        ChromeBar.DOCK, ChromeBar.DRAW -> if (upright) {
            Offset(space.edge, space.edge)
        } else {
            Offset((space.width - w) / 2f, space.edge)
        }

        ChromeBar.RECORD -> Offset(space.utilityRight - w, space.edge)

        ChromeBar.CALCULATOR -> Offset(if (space.calculatorLeft) space.edge else right, space.edge)

        ChromeBar.HISTORY -> Offset(space.edge, bottom)

        ChromeBar.ZOOM -> Offset(right, bottom)

        ChromeBar.PAGES -> if (upright) {
            Offset(right, middle)
        } else {
            Offset(space.pagesLeft + (space.pagesRight - space.pagesLeft - w) / 2f, bottom)
        }
    }
}

/**
 * Where [id]'s child goes: its stored position (or [stock]) plus any live drag, kept on screen.
 * Records the pill in [geometry] as it goes, which is what a drag commits from.
 */
private fun InkUiState.place(
    id: BarId,
    placeable: Placeable,
    stock: Offset,
    space: HostSpace,
    geometry: ChromeGeometry,
): Offset {
    val (left, right) = insets(id, space.density)
    val pillW = (placeable.width - left - right).coerceAtLeast(0f)
    val pillH = placeable.height.toFloat()
    val stored = layout.bar(id).position ?: if (id is BarId.Floating) ChromePosition(HALF, HALF) else null
    val base = stored?.topLeft(pillW, pillH, space.width, space.height, space.edge)?.let { Offset(it.first, it.second) }
        ?: Offset(stock.x + left, stock.y)
    var pill = clamped(base + chromeDrag.offsetFor(id), pillW, pillH, space)
    if (chromeDrag.isMoving(id)) {
        // ⭐ Lined up with the screen and the other bars when it comes near (docs/LAYOUTS.md). The commit
        // on lift reads the pill recorded below, so what snapped is what is kept.
        val followers = if (id == DockBar) setOf(id, DrawBar) else setOf(id)
        val others = geometry.pillsNow().filterKeys { it !in followers }.values.toList()
        val snapped = BarSnap.snap(
            Rect(pill, Size(pillW, pillH)),
            others,
            Size(space.width, space.height),
            space.edge,
            SnapReach.value * space.density,
        )
        pill = clamped(pill + snapped.by, pillW, pillH, space)
        geometry.snapGuide = snapped
    }
    geometry.record(id, Rect(pill, Size(pillW, pillH)))
    return Offset(pill.x - left, pill.y)
}

/** How near a dragged bar comes to lining up before it snaps. */
private val SnapReach = 12.dp

/**
 * The dock's second row — the pen's settings — placed against the dock: centred **below** it; **above**
 * it once the dock has been put in the lower half of the screen, so a dock on the bottom edge opens
 * its row upwards instead of off the screen; and **beside** it once the dock is taller than it is wide,
 * so a column of tools down one side opens its settings into the page rather than across itself.
 */
private fun InkUiState.placeSecondary(row: Placeable, space: HostSpace, geometry: ChromeGeometry): Offset {
    val dock = geometry.pillNow(DockBar) ?: return Offset.Zero
    val (left, right) = insets(DrawBar, space.density)
    val w = (row.width - left - right).coerceAtLeast(0f)
    val h = row.height.toFloat()
    val gap = DockRowGap.value * space.density * chromeScale(DrawBar)
    val wanted = if (dock.height > dock.width) {
        val x = if (dock.center.x < space.width / 2f) dock.right + gap else dock.left - gap - w
        Offset(x, dock.top)
    } else {
        val above = !isStock(ChromeBar.DOCK) && dock.center.y > space.height / 2f
        Offset(dock.center.x - w / 2f, if (above) dock.top - gap - h else dock.bottom + gap)
    }
    val pill = clamped(wanted, w, h, space)
    geometry.record(DrawBar, Rect(pill, Size(w, h)))
    return Offset(pill.x - left, pill.y)
}

private fun clamped(at: Offset, w: Float, h: Float, space: HostSpace): Offset = Offset(
    at.x.coerceIn(space.edge, maxOf(space.edge, space.width - space.edge - w)),
    at.y.coerceIn(space.edge, maxOf(space.edge, space.height - space.edge - h)),
)

/** True when [bar] has never been dragged out of its stock slot (or has been put back). */
private fun InkUiState.isStock(bar: ChromeBar): Boolean = layout.bar(BarId.Stock(bar)).position == null

/** What [id] is drawn at right now — its own size live while it is being resized. */
internal fun InkUiState.chromeScale(id: BarId): Float =
    layout.uiScale * (chromeDrag.scaleFor(id) ?: layout.bar(id).scale)

/** Whether a bar has a move handle: everything but the dock's second row, which follows the dock. */
internal val BarId.isMovable: Boolean get() = this !is BarId.Stock || bar.movable

/** Whether a bar is made of chips — and so takes them, and can be laid out as a column or a grid. */
internal val BarId.holdsChips: Boolean get() = this !is BarId.Stock || bar.holdsChips

/**
 * How far the edit handles widen a bar on the left and on the right, in px: the move handle on the
 * left where the bar has one, the resize grip on the right on every bar. At the overall size and not
 * the bar's own, because that is what the handles are drawn at — see [ChromeBarFrame].
 */
private fun InkUiState.insets(id: BarId, density: Float): Pair<Float, Float> {
    if (!editingLayout) return 0f to 0f
    val slot = (HandleWidth.value + HandleGap.value) * density * layout.uiScale
    return (if (id.isMovable) slot else 0f) to slot
}

/**
 * Where each bar's pill was last placed, in [ChromeHost]'s px, and where the host itself is. Written in
 * the layout passes and read by a drag's commit.
 *
 * ⭐ **What opens around the chrome reads it too** — the right-hand panel column (`PanelDock`) finds
 * its top and bottom from the bars in the corners, and a contextual bar keeps off a column of tools
 * (docs/UI-RAILS.md). Those read [pill], which subscribes to [revision]; it is bumped only when a
 * pill actually *changes*, so a layout pass that puts every bar where it already was invalidates
 * nothing and the chrome cannot drive itself round a loop. A drag in the layout editor bumps it per
 * frame, which is the one time anything should follow a bar.
 */
@Stable
internal class ChromeGeometry {
    private val pills = HashMap<BarId, Rect>()
    private var revision by mutableIntStateOf(0)

    /** The host's top-left in root px — what turns a finger's root position into the host's own. */
    var origin: Offset = Offset.Zero
    private var space = HostSpace(0f, 0f, 0f, 1f)

    /** The right-hand panel column, which takes its top and bottom from the bars placed here. */
    val dock: PanelDock = PanelDock(this)

    fun update(next: HostSpace) {
        space = next
    }

    /** Written by the placement pass. */
    fun record(id: BarId, rect: Rect) {
        if (id == DrawBar && rect.width > 0f && rect.height > 0f) rememberRow(rect)
        if (pills[id] == rect) return
        pills[id] = rect
        revision++
    }

    /** The pen's row at its full height, for while a contextual bar has folded it away. */
    private var lastRow: Rect? = null

    /**
     * ⚠️ The row folds with an animation (`DockSecondary`, `shrinkVertically`), so it is recorded at every
     * height on its way to nothing. Only a row at least as tall, or one that has moved, replaces the one
     * kept — otherwise the kept row is the last sliver of the animation, and the column under it rose
     * anyway (found on the emulator, after the first fix).
     */
    private fun rememberRow(rect: Rect) {
        val last = lastRow
        if (last == null || rect.height >= last.height || (rect.topLeft - last.topLeft).getDistance() > 1f) {
            lastRow = rect
        }
    }

    /**
     * Every placed pill, with the pen's row at its full height even while a contextual bar folds it away —
     * what the panel column measures its space from. Without it, a left-hand column under the row rose to
     * the top of the screen the moment something was selected and fell back when it was not (found on
     * the emulator).
     */
    fun pillsWithFoldedRow(): Collection<Rect> {
        val others = pills().filterKeys { it != DrawBar }.values
        return others + listOfNotNull(lastRow?.takeIf { DrawBar in pills })
    }

    /** [id]'s pill in the host's px, or null before it has been placed. Read from composition, this follows it. */
    fun pill(id: BarId): Rect? {
        revision
        return pills[id]?.takeIf { it.width > 0f && it.height > 0f }
    }

    /** Every placed pill, in the host's px. Read from composition, this follows them. */
    fun pills(): Map<BarId, Rect> {
        revision
        return pills.filterValues { it.width > 0f && it.height > 0f }
    }

    /** The host's own size and edge padding, in px — what [pill]s are positioned within. */
    val width: Float get() = space.width
    val height: Float get() = space.height
    val edge: Float get() = space.edge

    /**
     * The upright bars among [bars] — a column of tools down the side — which a contextual bar keeps
     * out from under (`ContextBarPlacement.place`).
     */
    fun keepOut(bars: Collection<BarId>): List<Rect> = bars.mapNotNull(::pill).filter { it.height > it.width }

    /** [id]'s pill without subscribing — for a gesture's commit, which must not recompose anything. */
    fun pillNow(id: BarId): Rect? = pills[id]

    /** Every placed pill without subscribing — what a bar being dragged snaps to, read in placement. */
    fun pillsNow(): Map<BarId, Rect> = pills.filterValues { it.width > 0f && it.height > 0f }

    /** What the bar or panel under the finger has lined up with, drawn as guides; null otherwise. */
    var snapGuide by mutableStateOf<Snapped?>(null)

    /** The position that keeps [id] exactly where it is on screen now. */
    fun positionOf(id: BarId): ChromePosition? = pills[id]?.let { pill ->
        ChromePosition.at(pill.left, pill.top, pill.width, pill.height, space.width, space.height, space.edge)
    }

    /** The position for a new bar of [size] whose top-left is at [rootTopLeft] — a chip dropped on the page. */
    fun pagePosition(rootTopLeft: Offset, size: Size): ChromePosition? {
        if (space.width <= 0f) return null
        val at = rootTopLeft - origin
        return ChromePosition.at(at.x, at.y, size.width, size.height, space.width, space.height, space.edge)
    }
}

/**
 * The bar under the finger in the layout editor, and what it has done so far.
 *
 * ⚠️ Read in two places only: [ChromeHost]'s placement (the offset) and the moving bar's own
 * [ScaledChrome] (the scale). A frame of a drag is therefore a re-layout, and a frame of a resize
 * recomposes the one bar being resized — [scaleFor] reads [scale] only for that bar.
 */
@Stable
class ChromeDrag {
    var bar by mutableStateOf<BarId?>(null)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var scale by mutableStateOf<Float?>(null)
        private set

    private var startPointer = Offset.Zero
    private var startScale = 1f
    private var startWidth = 1f

    fun offsetFor(target: BarId): Offset = if (bar == target) offset else Offset.Zero

    /** True while [target] is being moved — not resized, which snaps nothing. */
    fun isMoving(target: BarId): Boolean = bar == target && scale == null

    fun scaleFor(target: BarId): Float? = if (bar == target) scale else null

    fun beginMove(target: BarId, pointer: Offset) {
        bar = target
        startPointer = pointer
        offset = Offset.Zero
        scale = null
    }

    /** [pointer] in root px, so a bar that moves under its own handle does not skew the delta. */
    fun moveTo(pointer: Offset) {
        offset = pointer - startPointer
    }

    fun beginResize(target: BarId, pointer: Offset, fromScale: Float, pillWidth: Float) {
        bar = target
        startPointer = pointer
        startScale = fromScale
        startWidth = pillWidth.coerceAtLeast(1f)
        offset = Offset.Zero
        scale = fromScale
    }

    /** The bar's own scale, chosen so that its right-hand end follows the finger. */
    fun resizeTo(pointer: Offset) {
        val grown = (startWidth + pointer.x - startPointer.x).coerceAtLeast(1f)
        scale = (startScale * grown / startWidth).coerceIn(ChromeLayout.BAR_SCALE)
    }

    fun end() {
        bar = null
        offset = Offset.Zero
        scale = null
    }
}

/**
 * Draws [content] at [scale] times the screen's density — every dp and sp in it, so a bigger bar has
 * bigger *targets* and not just bigger pixels, and its layout, hit-testing and text all agree.
 *
 * ⚠️ **Nesting multiplies.** [ChromeBarFrame] relies on that — the overall size outside, the bar's
 * own inside — and everything else passes the whole product from the unscaled screen. A new caller
 * nested by accident is a bar drawn at its size squared.
 */
@Composable
fun ScaledChrome(scale: Float, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val scaled = remember(base, scale) { Density(base.density * scale, base.fontScale) }
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}

/**
 * One bar of chrome, as a child of [ChromeHost]: drawn at its size, and in the layout editor given a
 * handle to move it by and a grip to resize it by.
 *
 * ## ⭐ Tap a bar to choose it
 *
 * *"I want to be able to click on a panel (like the undo panel) and be able to change the size of only
 * that."* In the layout editor a tap anywhere on a bar — a chip, its background, its handles — makes it
 * [InkUiState.selectedBar]: it is outlined brighter, and the editor's panel shows that bar's size, its
 * shape and a reset for it alone (`LayoutEditorPanel`). Outside the editor a **long press on the
 * bar's background** opens the editor with that bar already chosen.
 *
 * ⚠️ The content keeps the same place in the tree in both modes (the handles are `if`s either side of
 * it), so opening the editor does not reset a bar's own state — an open template menu, the width
 * readout's anchor.
 *
 * ⚠️ The long press only ever reaches here from the bar's background. Every chip consumes its own
 * press, so holding a chip still does what holding it did before — a swatch still picks its colour
 * up — and nothing here can take that away.
 */
@Composable
internal fun ChromeBarFrame(id: BarId, ui: InkUiState, geometry: ChromeGeometry, content: @Composable () -> Unit) {
    val editing = ui.editingLayout
    val chosen = editing && ui.selectedBar == id
    // ⛔ **The handles are drawn at the overall size only, never at the bar's own.** Compose restarts
    // a `pointerInput` gesture whenever the density under it changes, so a grip inside the bar it
    // resizes cancelled its own drag on the first frame — the bar flickered and stayed the size it
    // was (found on the emulator; moving was unaffected, because a move changes no density).
    ScaledChrome(ui.layout.uiScale) {
        Row(
            modifier = Modifier.layoutId(id),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HandleGap),
        ) {
            if (editing && id.isMovable) MoveHandle(id, ui, geometry)
            // ⚠️ Weighted, so the Row measures the handles **first**. A scrolling bar takes every
            // pixel it is offered, and measured in order it left the grip after it zero wide — the
            // page bar, in its stock slot, had no resize grip at all (found on the emulator).
            val slotted = Modifier.weight(1f, fill = false)
            val row = ui.layout.arrangement(id) == ChromeArrangement.ROW
            val shape = if (row) InkDims.Pill else InkDims.PanelCorner
            val edge = if (chosen) ChosenOutline else EditOutline
            val tint = if (chosen) Ink.Accent else Ink.Focus
            ScaledChrome(ui.chromeDrag.scaleFor(id) ?: ui.layout.bar(id).scale) {
                Box(
                    modifier = slotted.then(
                        if (editing) {
                            Modifier
                                .border(edge, tint, shape)
                                .pointerInput(id) { detectTapGestures(onTap = { ui.selectedBar = id }) }
                        } else {
                            Modifier.pointerInput(id) {
                                detectTapGestures(
                                    onLongPress = {
                                        ui.editingLayout = true
                                        ui.selectedBar = id
                                    },
                                )
                            }
                        },
                    ),
                ) { content() }
            }
            if (editing) ResizeGrip(id, ui, geometry)
        }
    }
}

/**
 * A grip of six dots. Drag it to move the bar, tap it to choose the bar; it is what *"Move Tools"* is
 * in the view tree.
 */
@Composable
private fun MoveHandle(id: BarId, ui: InkUiState, geometry: ChromeGeometry) {
    val where = remember { RootMapper() }
    val gesture = Modifier
        .pointerInput(id) {
            detectDragGestures(
                onDragStart = { at ->
                    ui.selectedBar = id
                    where.root(at)?.let { ui.chromeDrag.beginMove(id, it) }
                },
                onDrag = { change, _ ->
                    change.consume()
                    where.root(change.position)?.let(ui.chromeDrag::moveTo)
                },
                onDragEnd = {
                    geometry.positionOf(id)?.let { ui.layout = ui.layout.placed(id, it) }
                    ui.chromeDrag.end()
                    geometry.snapGuide = null
                },
                onDragCancel = {
                    ui.chromeDrag.end()
                    geometry.snapGuide = null
                },
            )
        }
        .pointerInput(id) { detectTapGestures(onTap = { ui.selectedBar = id }) }
    Handle("Move ${id.label}", where, gesture) { dot ->
        dots(GRIP_COLUMNS, GRIP_ROWS, dot)
    }
}

/** Three diagonal strokes in a corner. Drag right to grow the bar, left to shrink it; tap to choose it. */
@Composable
private fun ResizeGrip(id: BarId, ui: InkUiState, geometry: ChromeGeometry) {
    val where = remember { RootMapper() }
    val gesture = Modifier.pointerInput(id) {
        detectDragGestures(
            onDragStart = { at ->
                ui.selectedBar = id
                val from = ui.layout.bar(id).scale
                val width = geometry.pillNow(id)?.width ?: 1f
                where.root(at)?.let { ui.chromeDrag.beginResize(id, it, from, width) }
            },
            onDrag = { change, _ ->
                change.consume()
                where.root(change.position)?.let(ui.chromeDrag::resizeTo)
            },
            onDragEnd = {
                ui.chromeDrag.scaleFor(id)?.let { ui.layout = ui.layout.scaled(id, it) }
                ui.chromeDrag.end()
            },
            onDragCancel = { ui.chromeDrag.end() },
        )
    }.pointerInput(id) { detectTapGestures(onTap = { ui.selectedBar = id }) }
    Handle("Resize ${id.label}", where, gesture) { dot ->
        val inset = size.width * GRIP_INSET
        val corner = Offset(size.width - inset, size.height / 2f + size.width / 2f - inset)
        val reach = size.width - 2 * inset
        for (step in 1..GRIP_ROWS) {
            val r = reach * step / GRIP_ROWS
            drawLine(
                Ink.TextStrong,
                Offset(corner.x - r, corner.y),
                Offset(corner.x, corner.y - r),
                strokeWidth = dot,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** [columns] × [rows] dots, evenly spread. */
private fun DrawScope.dots(columns: Int, rows: Int, dot: Float) {
    for (column in 1..columns) {
        for (row in 1..rows) {
            drawCircle(Ink.TextStrong, dot, Offset(size.width * column / (columns + 1), size.height * row / (rows + 1)))
        }
    }
}

/**
 * The body every handle shares: a raised key with a sky edge, drawn by hand so no glyph can be tofu.
 *
 * ⚠️ **Exempt from the system's edge gestures.** Every stock bar sits 12 dp from a screen edge, so the
 * zoom bar's grip and the calculator's handle are inside the band where a swipe *from* the edge is
 * Android's Back — and Back closes the layout editor. On the emulator the first drag of the zoom grip
 * did exactly that (`startBackNavigation` in logcat, at the instant of the drag). A handle is 34 dp
 * tall, well inside the 200 dp per edge the platform allows an app to exclude.
 */
@Composable
private fun Handle(description: String, where: RootMapper?, gesture: Modifier, draw: DrawScope.(dot: Float) -> Unit) {
    val shape = RoundedCornerShape(HandleCorner)
    Canvas(
        modifier = Modifier
            .size(HandleWidth, HandleHeight)
            .systemGestureExclusion()
            .then(if (where != null) Modifier.onGloballyPositioned { where.coordinates = it } else Modifier)
            .clip(shape)
            .background(Ink.PanelRaised)
            .border(EditOutline, Ink.Focus, shape)
            .then(gesture)
            .semantics { contentDescription = description },
    ) {
        draw(HandleDot.toPx())
    }
}

/**
 * A node's coordinates, kept so a gesture can turn its local positions into **root** ones.
 *
 * Both handles move with the bar they drag — that is the point — so a delta between two local
 * positions is measured against a moving origin and comes out wrong. Root positions are not.
 */
internal class RootMapper {
    var coordinates: LayoutCoordinates? = null

    fun root(local: Offset): Offset? = coordinates?.takeIf { it.isAttached }?.localToRoot(local)
}

/** `ZONE_GAP` from the `Row` this replaced: the space between the three bottom bars. */
private val ZoneGap = 28.dp

/** Between the record chip and the calculator chip in the top-right corner — two pills, not one. */
private val UtilityGap = InkDims.GroupGap
private val HandleWidth = 26.dp
private val HandleHeight = 34.dp
private val HandleGap = 6.dp
private val HandleCorner = 8.dp
private val HandleDot = 2.dp
private val EditOutline = 1.5.dp

/** The chosen bar's edge: thicker, and near-white rather than sky, so it reads as *this one*. */
private val ChosenOutline = 3.dp
private const val GRIP_ROWS = 3
private const val GRIP_COLUMNS = 2
private const val GRIP_INSET = 0.22f
private const val HALF = 0.5f
