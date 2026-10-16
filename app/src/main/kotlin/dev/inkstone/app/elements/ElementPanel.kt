package dev.inkstone.app.elements

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inkstone.app.ui.ChipKind
import dev.inkstone.app.ui.GlassPanel
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.InkType
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.PanelHeader
import dev.inkstone.app.ui.TextChip
import dev.inkstone.core.model.ElementId
import dev.inkstone.core.storage.Element
import kotlin.math.roundToInt

/**
 * ⭐ ROADMAP M8c — *"a browsable panel grouped by collection; tap or drag to stamp"* — and since the
 * Elements follow-up, the drag half actually exists.
 *
 * ## Drag, or tap then tap
 *
 * > *"i want to be able to create an element then drag and drop an element into the note"*
 *
 * M8c built only the tap: select an element to arm it, tap the page to stamp it. Its KDoc argued a
 * drag was *"a second gesture system for the same outcome"* across an `AndroidView` seam — but the
 * panel and the page overlay are both Compose, in one tree, so there is no seam to cross: a drag here
 * reports **window** positions and `ObjectLayers` turns them into page space with the camera it
 * already holds. Both stay — the drag for *"put this there"*, the arm for *"put four of these down"*.
 *
 * Two ways to start a drag, because the grid scrolls:
 *
 * - **from the picture, sideways**, immediately — toward the page, which is beside the panel. Only
 *   sideways, because in a grid the pictures are most of the panel and a vertical drag on one has to
 *   stay a scroll (the list this replaced could take any direction: its pictures were small);
 * - **from anywhere on the tile**, after a long press — the convention every tablet list uses for
 *   "pick this up", in any direction.
 *
 * ## A picture rather than a name
 *
 * Each tile is the Element drawn by [ElementPreviews] — the same painter the page uses, so what you
 * pick up is what lands — two across (docs/UI-RAILS.md). M8c listed names on the grounds that drawing
 * one *"means rendering its members"*, which is exactly what makes it cheap: an Element is a small page.
 */
@Composable
@Suppress("LongParameterList") // What the panel shows, and the three things a row can do.
fun ElementPanel(
    elements: List<Element>,
    armed: Element?,
    previews: Map<ElementId, ImageBitmap>,
    onArm: (Element?) -> Unit,
    onDelete: (ElementId) -> Unit,
    onClose: () -> Unit,
    onEdit: (Element) -> Unit,
    drag: ElementDrag,
    modifier: Modifier = Modifier,
) {
    GlassPanel(modifier = modifier.width(PanelWidth)) {
        PanelHeader("Elements", onClose)
        if (elements.isEmpty()) {
            Hint(EMPTY_HINT)
            return@GlassPanel
        }
        // ⭐ A grid of pictures, not a list of rows (docs/UI-RAILS.md): what tells one Element from
        // another is what it looks like, and a row spent most of its width on a name, a size and a red
        // `REMOVE` repeated once per Element. What can be done to one is said once, under the grid,
        // for the one that is armed.
        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            modifier = Modifier.heightIn(max = ListMaxHeight),
            verticalArrangement = Arrangement.spacedBy(TileGap),
            horizontalArrangement = Arrangement.spacedBy(TileGap),
        ) {
            // Grouped by collection, in the library's own order (collection, then name), so the
            // panel does not reshuffle itself when an element is added.
            elements.groupBy { it.collection }.forEach { (collection, inGroup) ->
                item(key = "collection-$collection", span = { GridItemSpan(maxLineSpan) }) {
                    MicroLabel(collection, modifier = Modifier.padding(top = HeadingTopGap))
                }
                items(inGroup, key = { it.id.value }) { element ->
                    ElementTile(
                        element = element,
                        armed = armed?.id == element.id,
                        preview = previews[element.id],
                        onArm = onArm,
                        onEdit = onEdit,
                        drag = drag,
                    )
                }
            }
        }
        armed?.let { held -> ArmedLine(held, onEdit, onDelete) }
    }
}

/**
 * The armed Element's name and size, and the two things that can be done to it.
 *
 * ⚠️ `Edit` is the double-tap made visible — that gesture is still there, but it was the one thing the
 * panel could do that nothing on screen offered. `Remove` moves the Element to `elements/trash/`
 * (`ElementLibrary.delete`), so it is not the irreversible kind CLAUDE.md rule 1 is about and needs no
 * question; `danger` is still right, because the chip *does* the thing.
 */
@Composable
private fun ArmedLine(element: Element, onEdit: (Element) -> Unit, onDelete: (ElementId) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TileGap),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RowTextGap)) {
            Text(
                text = element.name,
                color = Ink.TextStrong,
                fontSize = InkType.ChipSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MicroLabel(element.summary())
        }
        TextChip(label = "Edit", onClick = { onEdit(element) })
        TextChip(label = "Remove", danger = true, onClick = { onDelete(element.id) })
    }
}

/**
 * The three moments of a drag out of the panel, in **window** coordinates.
 *
 * Window rather than the row's own, because the drop is decided by a different composable
 * (`ObjectLayers`) with a different origin; window space is the one both can convert from.
 */
@Immutable
class ElementDrag(
    val onStart: (Element, Offset) -> Unit,
    val onMove: (Offset) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit,
)

/**
 * What an empty library says.
 *
 * Left as a sentence rather than made a [MicroLabel]: an empty panel has nothing to tap, so it is the
 * one place the two ways of making an Element can be told — and a tracked-out uppercase single line
 * cannot hold either. Once there is something to tap, the grid needs no sentence: a tile is the obvious
 * thing to pick up, and tapping one says what else it can do ([ArmedLine]).
 */
@Composable
private fun Hint(text: String) {
    Text(text = text, color = Ink.TextMuted, fontSize = InkType.ChipSize, lineHeight = HintLineHeight)
}

/**
 * One Element: its picture on paper and its name under it.
 *
 * ⭐ **Tap arms; double-tap opens the Element editor** (SPEC §4.9.7) — one detector for both, so a
 * double-tap never arms and disarms on its way to opening. **Drag it sideways** and it comes off the
 * panel at once; **hold, then drag** picks it up in any direction. Sideways rather than any direction,
 * because the grid scrolls up and down and a tile is most of the grid: a vertical drag on a picture is
 * a scroll, and one toward the page beside the panel is a pick-up.
 */
@Composable
@Suppress("LongParameterList")
private fun ElementTile(
    element: Element,
    armed: Boolean,
    preview: ImageBitmap?,
    onArm: (Element?) -> Unit,
    onEdit: (Element) -> Unit,
    drag: ElementDrag,
) {
    val current by rememberUpdatedState(element)
    val isArmed by rememberUpdatedState(armed)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RowTextGap),
        modifier = Modifier
            .fillMaxWidth()
            .clip(InkDims.ChipCorner)
            .background(if (armed) Ink.PanelRaised else Color.Transparent)
            .dragsElement(element, drag, DragStart.AFTER_LONG_PRESS)
            .pointerInput(element.id) {
                detectTapGestures(
                    onTap = { onArm(if (isArmed) null else current) },
                    onDoubleTap = { onEdit(current) },
                )
            }
            .padding(TilePadding),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(InkDims.ChipCorner)
                .background(Ink.Paper)
                // The armed tile is ringed in the focus colour — the one sign on screen that the next
                // tap on the page will stamp it.
                .border(if (armed) TileRing else TileBorder, if (armed) Ink.Focus else Ink.Hairline, InkDims.ChipCorner)
                .dragsElement(element, drag, DragStart.SIDEWAYS),
        ) {
            if (preview != null) {
                Image(bitmap = preview, contentDescription = element.name, contentScale = ContentScale.Fit)
            }
        }
        Text(
            text = element.name,
            color = if (armed) Ink.TextStrong else Ink.TextNormal,
            fontSize = InkType.MicroSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** How a drag out of the panel begins — see [ElementTile]. */
private enum class DragStart { SIDEWAYS, AFTER_LONG_PRESS }

/**
 * Picks [element] up on a drag and reports where it is, in window space.
 *
 * ⚠️ The coordinates are the node's own, captured by [onGloballyPositioned] on **this** node, so a
 * position the detector reports and the coordinates converting it belong to the same box. Reading
 * them off a parent would be off by the parent's padding — a ghost that hangs a few millimetres away
 * from the finger, and an Element that lands there too.
 */
private fun Modifier.dragsElement(element: Element, drag: ElementDrag, start: DragStart): Modifier = composed {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val current by rememberUpdatedState(element)
    val callbacks by rememberUpdatedState(drag)
    fun window(local: Offset): Offset = coordinates?.localToWindow(local) ?: local
    Modifier
        .onGloballyPositioned { coordinates = it }
        .pointerInput(element.id, start) {
            val onStart: (Offset) -> Unit = { callbacks.onStart(current, window(it)) }
            val onEnd: () -> Unit = { callbacks.onEnd() }
            val onCancel: () -> Unit = { callbacks.onCancel() }
            when (start) {
                DragStart.AFTER_LONG_PRESS -> detectDragGesturesAfterLongPress(
                    onDragStart = onStart,
                    onDragEnd = onEnd,
                    onDragCancel = onCancel,
                    onDrag = { change, _ ->
                        change.consume()
                        callbacks.onMove(window(change.position))
                    },
                )

                // ⚠️ Only the *start* is sideways. After the slop the pointer is followed wherever it
                // goes — `change.position` is the whole position, not the horizontal part.
                DragStart.SIDEWAYS -> detectHorizontalDragGestures(
                    onDragStart = onStart,
                    onDragEnd = onEnd,
                    onDragCancel = onCancel,
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        callbacks.onMove(window(change.position))
                    },
                )
            }
        }
}

/**
 * The line under an element's name.
 *
 * ⚠️ Deliberately terse. [MicroLabel] is one line in a monospaced, tracked-out face, which is
 * roughly 40 % wider than the same words in the body font. The number of pieces and the size are the
 * two facts that distinguish one saved element from another, and the word "pieces" is not one of
 * them.
 */
private fun Element.summary(): String = "${objects.size} · ${widthPt.roundToInt()}×${heightPt.roundToInt()} pt"

private const val EMPTY_HINT =
    "Nothing saved yet. Select something and press Save as Element, or drag a box round it with this tool."

/**
 * Narrower than the reference design's 280 dp side panel, so that the library, the colour panel, the
 * calculator and the diagnostics all fit side by side in the right-hand column on a landscape tablet
 * without reaching the tool column (docs/UI-RAILS.md §3).
 */
private val PanelWidth = 240.dp

/**
 * Two ~100 dp pictures across the panel. Three made ~74 dp tiles whose names were cut at ten letters
 * (*"DiagonalLi…"*), and a picture you have to squint at is not a library.
 */
private const val GRID_COLUMNS = 2

/** Tall enough for four rows of tiles, and clear of the dock on a portrait tablet. */
private val ListMaxHeight = 420.dp

private val TileBorder = 1.dp
private val TileRing = 2.dp
private val TileGap = 8.dp
private val TilePadding = 4.dp
private val HeadingTopGap = 6.dp
private val RowTextGap = 2.dp
private val HintLineHeight = 18.sp
