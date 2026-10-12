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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
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
import kotlin.math.min
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
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = modifier.fillMaxSize().onGloballyPositioned { geometry.origin = it.positionInRoot() },
    ) { measurables, constraints ->
        val (placed, space) = measureChrome(measurables, constraints, ui)
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
 * Every bar measured against the screen less its edges — except the page bar in its stock slot, which
 * is measured into what history and zoom leave of the bottom edge.
 */
private fun MeasureScope.measureChrome(
    measurables: List<Measurable>,
    constraints: Constraints,
    ui: InkUiState,
): Pair<Map<Any, Placeable>, HostSpace> {
    val width = constraints.maxWidth
    val edge = InkDims.EdgePadding.roundToPx()
    val loose = Constraints(
        maxWidth = (width - 2 * edge).coerceAtLeast(0),
        maxHeight = (constraints.maxHeight - 2 * edge).coerceAtLeast(0),
    )
    val byKey = measurables.mapNotNull { m -> m.layoutId?.let { it to m } }.toMap()
    val placed = HashMap<Any, Placeable>()
    byKey.forEach { (key, measurable) -> if (key != PagesBar) placed[key] = measurable.measure(loose) }

    val zoneGap = ZoneGap.roundToPx()
    val history = placed[BarId.Stock(ChromeBar.HISTORY)]?.takeIf { it.width > 0 && ui.isStock(ChromeBar.HISTORY) }
    val zoom = placed[BarId.Stock(ChromeBar.ZOOM)]?.takeIf { it.width > 0 && ui.isStock(ChromeBar.ZOOM) }
    val left = edge + (history?.let { it.width + zoneGap } ?: 0)
    val right = width - edge - (zoom?.let { it.width + zoneGap } ?: 0)
    byKey[PagesBar]?.let { pages ->
        val between = loose.copy(maxWidth = (right - left).coerceAtLeast(0))
        placed[PagesBar] = pages.measure(if (ui.isStock(ChromeBar.PAGES)) between else loose)
    }
    return placed to HostSpace(width.toFloat(), constraints.maxHeight.toFloat(), edge.toFloat(), density, left, right)
}

/**
 * The host's own px, the density its dp were measured in, and the stock page bar's span — passed
 * around as one value.
 */
internal class HostSpace(
    val width: Float,
    val height: Float,
    val edge: Float,
    val density: Float,
    val pagesLeft: Int = 0,
    val pagesRight: Int = 0,
)

/** The stock top-left of [id]'s whole child, edit handles included. A floating bar's is the middle. */
private fun stockSlot(id: BarId, placeable: Placeable, space: HostSpace): Offset {
    val w = placeable.width.toFloat()
    val h = placeable.height.toFloat()
    val bottom = space.height - space.edge - h
    if (id !is BarId.Stock) return Offset((space.width - w) / 2f, (space.height - h) / 2f)
    return when (id.bar) {
        ChromeBar.DOCK, ChromeBar.DRAW -> Offset((space.width - w) / 2f, space.edge)
        ChromeBar.RECORD -> Offset(space.width - space.edge - w, space.edge)
        ChromeBar.CALCULATOR -> Offset(space.edge, space.edge)
        ChromeBar.HISTORY -> Offset(space.edge, bottom)
        ChromeBar.ZOOM -> Offset(space.width - space.edge - w, bottom)
        ChromeBar.PAGES -> Offset(space.pagesLeft + (space.pagesRight - space.pagesLeft - w) / 2f, bottom)
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
    val pill = clamped(base + chromeDrag.offsetFor(id), pillW, pillH, space)
    geometry.pills[id] = Rect(pill, Size(pillW, pillH))
    return Offset(pill.x - left, pill.y)
}

/**
 * The dock's second row — the pen's settings — placed against the dock: centred **below** it; **above**
 * it once the dock has been put in the lower half of the screen, so a dock on the bottom edge opens
 * its row upwards instead of off the screen; and **beside** it once the dock is taller than it is wide,
 * so a column of tools down one side opens its settings into the page rather than across itself.
 */
private fun InkUiState.placeSecondary(row: Placeable, space: HostSpace, geometry: ChromeGeometry): Offset {
    val dock = geometry.pills[DockBar] ?: return Offset.Zero
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
    geometry.pills[DrawBar] = Rect(pill, Size(w, h))
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
    layout.uiScale * (chromeDrag.scaleFor(id.sizedBy()) ?: layout.bar(id.sizedBy()).scale)

/** Whether a bar has a move handle and a resize grip: everything but the dock's second row. */
internal val BarId.isMovable: Boolean get() = this !is BarId.Stock || bar.movable

/** Whether a bar is made of chips — and so takes them, and can be laid out as a column or a grid. */
internal val BarId.holdsChips: Boolean get() = this !is BarId.Stock || bar.holdsChips

/**
 * How far the edit handles widen a bar on the left and on the right, in px: move and arrange on the
 * left, resize on the right — each only where the bar has it. At the overall size and not the bar's
 * own, because that is what the handles are drawn at — see [ChromeBarFrame].
 */
private fun InkUiState.insets(id: BarId, density: Float): Pair<Float, Float> {
    if (!editingLayout) return 0f to 0f
    val slot = (HandleWidth.value + HandleGap.value) * density * layout.uiScale
    val left = (if (id.isMovable) 1 else 0) + (if (id.holdsChips) 1 else 0)
    val right = if (id.isMovable) 1 else 0
    return left * slot to right * slot
}

/**
 * Where each bar's pill was last placed, in [ChromeHost]'s px, and where the host itself is. Written in
 * the layout passes and read by a drag's commit — plain fields, never state: nothing should recompose
 * because a bar was placed.
 */
internal class ChromeGeometry {
    val pills = HashMap<BarId, Rect>()

    /** The host's top-left in root px — what turns a finger's root position into the host's own. */
    var origin: Offset = Offset.Zero
    private var space = HostSpace(0f, 0f, 0f, 1f)

    fun update(next: HostSpace) {
        space = next
    }

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
 * handle to move it by, a button to lay it out as a row, a column or a grid, and a grip to resize it
 * by. Outside the editor a **long press on the bar itself** — its padding, a divider, anything that is
 * not a control — opens the editor.
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
            if (editing && id.holdsChips) ArrangeButton(id, ui)
            // ⚠️ Weighted, so the Row measures the handles **first**. A scrolling bar takes every
            // pixel it is offered, and measured in order it left the grip after it zero wide — the
            // page bar, in its stock slot, had no resize grip at all (found on the emulator).
            val slotted = Modifier.weight(1f, fill = false)
            val shape = if (ui.layout.bar(id).arrangement ==
                ChromeArrangement.ROW
            ) {
                InkDims.Pill
            } else {
                InkDims.PanelCorner
            }
            ScaledChrome(ui.chromeDrag.scaleFor(id.sizedBy()) ?: ui.layout.bar(id.sizedBy()).scale) {
                Box(
                    modifier = slotted.then(
                        if (editing) {
                            Modifier.border(EditOutline, Ink.Focus, shape)
                        } else {
                            Modifier.pointerInput(Unit) {
                                detectTapGestures(onLongPress = { ui.editingLayout = true })
                            }
                        },
                    ),
                ) { content() }
            }
            if (editing && id.isMovable) ResizeGrip(id, ui, geometry)
        }
    }
}

/** A grip of six dots. Drag it to move the bar; it is what *"Move Tools"* is in the view tree. */
@Composable
private fun MoveHandle(id: BarId, ui: InkUiState, geometry: ChromeGeometry) {
    val where = remember { RootMapper() }
    val gesture = Modifier.pointerInput(id) {
        detectDragGestures(
            onDragStart = { at -> where.root(at)?.let { ui.chromeDrag.beginMove(id, it) } },
            onDrag = { change, _ ->
                change.consume()
                where.root(change.position)?.let(ui.chromeDrag::moveTo)
            },
            onDragEnd = {
                geometry.positionOf(id)?.let { ui.layout = ui.layout.placed(id, it) }
                ui.chromeDrag.end()
            },
            onDragCancel = { ui.chromeDrag.end() },
        )
    }
    Handle("Move ${id.label}", where, gesture) { dot ->
        dots(GRIP_COLUMNS, GRIP_ROWS, dot)
    }
}

/**
 * Row, column, two, three or four across — one tap each, round and back to a row. The key draws the
 * shape it is now, so a glance says which it is without a word beside it.
 */
@Composable
private fun ArrangeButton(id: BarId, ui: InkUiState) {
    val arrangement = ui.layout.bar(id).arrangement
    val gesture = Modifier.pointerInput(id) {
        detectTapGestures(onTap = { ui.layout = ui.layout.arranged(id, ui.layout.bar(id).arrangement.next()) })
    }
    Handle("Arrange ${id.label}: ${arrangement.label}", where = null, gesture = gesture) { dot ->
        when (arrangement) {
            ChromeArrangement.ROW -> blocks(ROW_BLOCKS, 1, dot)
            ChromeArrangement.COLUMN -> blocks(1, ROW_BLOCKS, dot)
            else -> blocks(arrangement.columns, 2, dot)
        }
    }
}

/** Three diagonal strokes in a corner. Drag right to grow the bar, left to shrink it. */
@Composable
private fun ResizeGrip(id: BarId, ui: InkUiState, geometry: ChromeGeometry) {
    val where = remember { RootMapper() }
    val gesture = Modifier.pointerInput(id) {
        detectDragGestures(
            onDragStart = { at ->
                val from = ui.layout.bar(id.sizedBy()).scale
                val width = geometry.pills[id]?.width ?: 1f
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
    }
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

/** [columns] × [rows] small rounded blocks filling the key — the arrangement as a picture. */
private fun DrawScope.blocks(columns: Int, rows: Int, dot: Float) {
    val pad = size.width * BLOCK_PAD
    val cellW = (size.width - 2 * pad) / columns
    val cellH = (size.height - 2 * pad) / rows
    val side = min(cellW, cellH) - dot
    for (c in 0 until columns) {
        for (r in 0 until rows) {
            val x = pad + cellW * c + (cellW - side) / 2f
            val y = pad + cellH * r + (cellH - side) / 2f
            drawRoundRect(Ink.TextStrong, Offset(x, y), Size(side, side), CornerRadius(dot))
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
private val HandleWidth = 26.dp
private val HandleHeight = 34.dp
private val HandleGap = 6.dp
private val HandleCorner = 8.dp
private val HandleDot = 2.dp
private val EditOutline = 1.5.dp
private const val GRIP_ROWS = 3
private const val GRIP_COLUMNS = 2
private const val GRIP_INSET = 0.22f
private const val ROW_BLOCKS = 3
private const val BLOCK_PAD = 0.2f
private const val HALF = 0.5f
