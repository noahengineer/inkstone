package dev.inkstone.app.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.LocalPanelChrome
import dev.inkstone.app.ui.PanelChrome
import kotlin.math.roundToInt

/**
 * ⭐ *"Every panel like the calculator"* (docs/LAYOUTS.md): a panel sits in the panel column until it is
 * dragged out by its name, then stays wherever it was let go; its corner makes it bigger or smaller;
 * and *Dock* — or letting it go against the column's edge — puts it back.
 *
 * Where it is belongs to the **layout** (`ChromeLayout.floatingPanels`, its size `panelScales`), so each
 * saved layout keeps its own arrangement of panels, and switching layouts moves them.
 *
 * - ⚠️ **The corner grip is outside the panel's [ScaledChrome].** Resizing changes that density, and
 *   Compose restarts a gesture whose density changes under it (CLAUDE.md; `ChromeBarFrame` learned it
 *   first). The name the panel is dragged by is inside, which is safe: a move changes no density.
 * - Both gestures are live while the finger is down and written to the layout **once**, on lift, so a
 *   drag is a re-layout per frame and one settings write — the bars' rule.
 * - Out of the column it still tells the column where it is ([PanelDock.reportLoose]), so a contextual
 *   bar keeps off it as it keeps off the docked ones.
 */
@Composable
internal fun PanelWindow(
    slot: DockSlot,
    ui: InkUiState,
    geometry: ChromeGeometry,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dock = geometry.dock
    val panel = slot.panel ?: return
    val window = remember(slot) { PanelPlacement() }
    val stored = ui.layout.floatingPanels[slot]
    val floating = stored != null || window.dragging
    val scale = ui.layout.uiScale * (window.resize ?: ui.layout.panelScales[panel] ?: 1f)
    val latestStored = rememberUpdatedState(stored)
    val commit = rememberUpdatedState { at: ChromePosition? -> ui.layout = ui.layout.panelPlaced(slot, at) }
    dock.measuredWith(LocalDensity.current)

    DisposableEffect(slot, floating) { onDispose { dock.reportLoose(slot, null) } }
    val place = if (floating) Modifier.floatingAt(window, latestStored, geometry) else Modifier.docked(dock, slot)
    val chrome = PanelChrome(
        grab = Modifier
            .semantics { contentDescription = "Move ${slot.label}" }
            .pointerInput(slot) { dragToMove(window, geometry, slot) { commit.value(it) } },
        onDock = if (stored != null) ({ commit.value(null) }) else null,
    )
    Box(
        modifier = modifier
            .then(place)
            .onSizeChanged { window.measured = it }
            // Its parent fills the screen from the chrome's origin, so this is the host's own px.
            .onGloballyPositioned { if (floating) dock.reportLoose(slot, it.boundsInParent()) },
    ) {
        ScaledChrome(scale) {
            CompositionLocalProvider(LocalPanelChrome provides chrome, content = content)
        }
        val fromLeft = !floating && dock.side == PanelSide.RIGHT
        PanelResizeGrip(
            fromLeft = fromLeft,
            window = window,
            startScale = { ui.layout.panelScales[panel] ?: 1f },
            commit = { ui.layout = ui.layout.withPanelScale(panel, it) },
            modifier = Modifier.align(if (fromLeft) Alignment.BottomStart else Alignment.BottomEnd),
        )
    }
}

/**
 * Where a panel out of the column is drawn: the live drag, else its stored place at its measured size,
 * else where it was last — read in the placement phase, so a drag moves it without recomposing it.
 * Invisible until it has been measured, because a stored place means nothing without a size.
 */
private fun Modifier.floatingAt(
    window: PanelPlacement,
    stored: State<ChromePosition?>,
    geometry: ChromeGeometry,
): Modifier = offset {
    val at = window.drag ?: stored.value?.let { window.topLeftOf(it, geometry) } ?: window.shown
    window.shown = at
    IntOffset(at.x.roundToInt(), at.y.roundToInt())
}.graphicsLayer { alpha = if (window.measured == IntSize.Zero) 0f else 1f }

/**
 * Drags the panel by its name. The gesture starts from where the panel is **shown** — in the column or
 * floating — and only a drag past touch slop takes it out of the column, so a tap on a docked panel's
 * name is not a move. On lift it is docked if it was let go against the column's edge
 * ([PanelDock.wantsDock]), and kept where it is otherwise, as a fraction of the free space.
 *
 * While it moves it snaps to the screen and the bars ([BarSnap]), and the line it caught on is drawn
 * (`SnapGuides`); against the column's edge the guide is that edge, which is what *"let go here and it
 * docks"* looks like.
 */
private suspend fun PointerInputScope.dragToMove(
    window: PanelPlacement,
    geometry: ChromeGeometry,
    slot: DockSlot,
    commit: (ChromePosition?) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val from = geometry.dock.offsetOf(slot)?.let { Offset(it.x.toFloat(), it.y.toFloat()) } ?: window.shown
        var moved = Offset.Zero
        try {
            do {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                if (change != null && change.pressed) {
                    moved += change.positionChange()
                    if (!window.dragging && moved.getDistance() > viewConfiguration.touchSlop) window.dragging = true
                    if (window.dragging) {
                        change.consume()
                        window.drag = window.snapped(from + moved, geometry, slot)
                    }
                }
            } while (change?.pressed == true)
        } finally {
            window.drag?.let { at ->
                val bounds = Rect(at, window.sizePx())
                commit(if (geometry.dock.wantsDock(bounds)) null else window.positionOf(at, geometry))
            }
            window.drag = null
            window.dragging = false
            geometry.snapGuide = null
        }
    }
}

/**
 * Three short diagonals in a corner: drag them to make the panel bigger or smaller. Its size is live
 * while the finger is down ([PanelPlacement.resize]) and written once, on lift.
 *
 * In the right-hand column a panel grows leftwards from its right edge, so the grip that follows the
 * finger is the bottom-left one; anywhere else it is the bottom-right — the calculator's rule.
 */
@Composable
private fun PanelResizeGrip(
    fromLeft: Boolean,
    window: PanelPlacement,
    startScale: () -> Float,
    commit: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val start = rememberUpdatedState(startScale)
    val done = rememberUpdatedState(commit)
    Canvas(
        modifier = modifier
            .size(GripSize)
            .semantics { contentDescription = "Resize panel" }
            .pointerInput(fromLeft) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val from = start.value()
                    val size = window.sizePx()
                    var moved = Offset.Zero
                    try {
                        do {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change != null && change.pressed) {
                                moved += change.positionChange()
                                change.consume()
                                window.resize = PanelPlacement.resized(from, size, moved, fromLeft)
                            }
                        } while (change?.pressed == true)
                    } finally {
                        window.resize?.let { done.value(it) }
                        window.resize = null
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
 * A panel window's live state: where it is being dragged to and how big it is being made, both in px
 * and read in the placement phase, plus what it measured and where it was last drawn.
 */
@Stable
internal class PanelPlacement {
    var drag by mutableStateOf<Offset?>(null)
    var dragging by mutableStateOf(false)

    /** The panel's own size while its corner is being dragged; null otherwise. */
    var resize by mutableStateOf<Float?>(null)
    var measured by mutableStateOf(IntSize.Zero)

    /** Where the panel was last placed, in the host's px. Not state: only gestures and the tracker read it. */
    var shown = Offset.Zero

    fun sizePx(): Size = Size(measured.width.toFloat(), measured.height.toFloat())

    /** The top-left a stored [position] stands for at the panel's measured size. */
    fun topLeftOf(position: ChromePosition, geometry: ChromeGeometry): Offset {
        val (x, y) = position.topLeft(
            measured.width.toFloat(),
            measured.height.toFloat(),
            geometry.width,
            geometry.height,
            geometry.edge,
        )
        return Offset(x, y)
    }

    fun positionOf(topLeft: Offset, geometry: ChromeGeometry): ChromePosition = ChromePosition.at(
        topLeft.x,
        topLeft.y,
        measured.width.toFloat(),
        measured.height.toFloat(),
        geometry.width,
        geometry.height,
        geometry.edge,
    )

    /** [at], lined up with the screen and the bars if it is near them, with the guide to draw. */
    fun snapped(at: Offset, geometry: ChromeGeometry, slot: DockSlot): Offset {
        val size = sizePx()
        val bounds = Rect(at, size)
        if (geometry.dock.wantsDock(bounds)) {
            val area = geometry.dock.area()
            val edge = if (geometry.dock.side == PanelSide.LEFT) area.left.toFloat() else area.right.toFloat()
            geometry.snapGuide = Snapped(Offset.Zero, x = edge)
            return at
        }
        val others = geometry.pillsNow().values.toList() + geometry.dock.occupied(except = slot)
        // The same reach as a bar's: the margin, about 12 dp.
        val snap = BarSnap.snap(bounds, others, Size(geometry.width, geometry.height), geometry.edge, geometry.edge)
        geometry.snapGuide = snap
        return at + snap.by
    }

    companion object {
        /**
         * The panel's own size after its corner moved [moved] from a panel of [size] at [from]: the mean of
         * how much wider and how much taller the corner asks it to be, so a diagonal drag tracks the
         * finger and a straight one still works. Toward the column's edge is smaller for a [fromLeft] grip.
         */
        fun resized(from: Float, size: Size, moved: Offset, fromLeft: Boolean): Float {
            if (size.width <= 0f || size.height <= 0f) return from
            val across = if (fromLeft) -moved.x else moved.x
            val factor = ((size.width + across) / size.width + (size.height + moved.y) / size.height) / 2f
            return (from * factor).coerceIn(ChromeLayout.PANEL_SCALE)
        }
    }
}

private val GripSize = 22.dp
private val GripStroke = 1.5.dp
private const val GRIP_INSET = 0.2f
private const val GRIP_LINES = 3
