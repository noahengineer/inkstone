package dev.inkstone.app.elements

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
 * Two ways to start a drag, because the list scrolls:
 *
 * - **from the picture**, immediately — the tile is the obvious thing to pick up, and it is small
 *   enough that grabbing it is never an attempt to scroll;
 * - **from anywhere on the row**, after a long press — the convention every tablet list uses for
 *   "pick this up", which leaves a plain drag on the row free to scroll the panel.
 *
 * ## A picture rather than a name
 *
 * Each row shows the Element drawn by [ElementPreviews] — the same painter the page uses, so what you
 * pick up is what lands. M8c listed names on the grounds that drawing one *"means rendering its
 * members"*, which is exactly what makes it cheap: an Element is a small page.
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MicroLabel("Elements")
            // ✕ rather than the word: close is the one control here whose meaning survives being a
            // symbol. `ChipKind.GLYPH` sets it larger and in the normal face, and the
            // `contentDescription` is what stops it reaching the a11y tree as a bare codepoint.
            TextChip(label = "✕", kind = ChipKind.GLYPH, onClick = onClose, contentDescription = "Close")
        }
        if (elements.isEmpty()) {
            Hint(EMPTY_HINT)
        } else {
            Hint(DRAG_HINT)
            LazyColumn(
                modifier = Modifier.heightIn(max = ListMaxHeight),
                verticalArrangement = Arrangement.spacedBy(RowGap),
            ) {
                // Grouped by collection, in the library's own order (collection, then name), so the
                // panel does not reshuffle itself when an element is added.
                elements.groupBy { it.collection }.forEach { (collection, inGroup) ->
                    item(key = "collection-$collection") {
                        MicroLabel(collection, modifier = Modifier.padding(top = HeadingTopGap))
                    }
                    items(inGroup, key = { it.id.value }) { element ->
                        ElementRow(
                            element = element,
                            armed = armed?.id == element.id,
                            preview = previews[element.id],
                            onArm = onArm,
                            onDelete = onDelete,
                            onEdit = onEdit,
                            drag = drag,
                        )
                    }
                }
            }
        }
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
 * A line of guidance under the heading.
 *
 * Left as a sentence rather than made a [MicroLabel]: both gestures — capturing and dragging — are
 * undiscoverable, so this is the one place they are explained, and a tracked-out uppercase single
 * line cannot hold either.
 */
@Composable
private fun Hint(text: String) {
    Text(text = text, color = Ink.TextMuted, fontSize = InkType.ChipSize, lineHeight = HintLineHeight)
}

@Composable
@Suppress("LongParameterList")
private fun ElementRow(
    element: Element,
    armed: Boolean,
    preview: ImageBitmap?,
    onArm: (Element?) -> Unit,
    onDelete: (ElementId) -> Unit,
    onEdit: (Element) -> Unit,
    drag: ElementDrag,
) {
    val current by rememberUpdatedState(element)
    val isArmed by rememberUpdatedState(armed)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RowContentGap),
        modifier = Modifier
            .fillMaxWidth()
            .clip(InkDims.ChipCorner)
            .background(if (armed) Ink.PanelRaised else Color.Transparent)
            .dragsElement(element, drag, afterLongPress = true)
            // ⭐ Tap arms; **double-tap opens the Element editor** (SPEC §4.9.7). One detector for
            // both, so a double-tap never arms and disarms on its way to opening.
            .pointerInput(element.id) {
                detectTapGestures(
                    onTap = { onArm(if (isArmed) null else current) },
                    onDoubleTap = { onEdit(current) },
                )
            }
            .padding(horizontal = RowPaddingH, vertical = RowPaddingV),
    ) {
        Tile(element, preview, drag)
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
        // Deliberately not a confirmation dialog: this moves the Element to `elements/trash/`
        // (see `ElementLibrary.delete`), so nothing here is irreversible in the way CLAUDE.md
        // rule 1 is about. `danger` is still right — the chip *does* the thing rather than opening
        // a question about it, which is exactly the case that colour is reserved for.
        TextChip(label = "Remove", danger = true, onClick = { onDelete(element.id) })
    }
}

/** The Element on paper — a white square, because that is what it is drawn on when it lands. */
@Composable
private fun Tile(element: Element, preview: ImageBitmap?, drag: ElementDrag) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(TileSize)
            .clip(InkDims.ChipCorner)
            .background(Ink.Paper)
            .border(TileBorder, Ink.Hairline, InkDims.ChipCorner)
            .dragsElement(element, drag, afterLongPress = false),
    ) {
        if (preview != null) {
            Image(bitmap = preview, contentDescription = element.name, contentScale = ContentScale.Fit)
        }
    }
}

/**
 * Picks [element] up on a drag and reports where it is, in window space.
 *
 * ⚠️ The coordinates are the node's own, captured by [onGloballyPositioned] on **this** node, so a
 * position the detector reports and the coordinates converting it belong to the same box. Reading
 * them off a parent would be off by the parent's padding — a ghost that hangs a few millimetres away
 * from the finger, and an Element that lands there too.
 */
private fun Modifier.dragsElement(element: Element, drag: ElementDrag, afterLongPress: Boolean): Modifier = composed {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val current by rememberUpdatedState(element)
    val callbacks by rememberUpdatedState(drag)
    fun window(local: Offset): Offset = coordinates?.localToWindow(local) ?: local
    Modifier
        .onGloballyPositioned { coordinates = it }
        .pointerInput(element.id, afterLongPress) {
            val onStart: (Offset) -> Unit = { callbacks.onStart(current, window(it)) }
            val onEnd: () -> Unit = { callbacks.onEnd() }
            val onCancel: () -> Unit = { callbacks.onCancel() }
            if (afterLongPress) {
                detectDragGesturesAfterLongPress(
                    onDragStart = onStart,
                    onDragEnd = onEnd,
                    onDragCancel = onCancel,
                    onDrag = { change, _ ->
                        change.consume()
                        callbacks.onMove(window(change.position))
                    },
                )
            } else {
                detectDragGestures(
                    onDragStart = onStart,
                    onDragEnd = onEnd,
                    onDragCancel = onCancel,
                    onDrag = { change, _ ->
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
private const val DRAG_HINT =
    "Drag one onto the page, or tap to arm it and tap the page. Double-tap one to edit it."

/** The reference design's side-panel width. */
private val PanelWidth = 280.dp

/** Tall enough for about six rows, and clear of the dock on a portrait tablet. */
private val ListMaxHeight = 420.dp

private val TileSize = 52.dp
private val TileBorder = 1.dp
private val RowGap = 4.dp
private val RowContentGap = 10.dp
private val HeadingTopGap = 6.dp
private val RowPaddingH = 8.dp
private val RowPaddingV = 6.dp
private val RowTextGap = 2.dp
private val HintLineHeight = 18.sp
