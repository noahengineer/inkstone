package dev.inkstone.app.elements

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import kotlin.math.roundToInt

/**
 * ⭐ ROADMAP M8c — *"a browsable panel grouped by collection; tap or drag to stamp"*.
 *
 * A list rather than a grid of previews, and that is a scoping decision worth stating: drawing a
 * thumbnail of an element means rendering its members, which for one holding a picture means
 * decoding an asset and for one holding a stroke would mean `androidx.ink` — the same machinery
 * `Thumbnails` already owns for whole pages, aimed at something an order of magnitude smaller.
 * SPEC §12's *"don't gold-plate"* applies; the name, the collection and the size are what
 * distinguish one element from another in practice.
 *
 * ## Tap, then tap
 *
 * Selecting an element here arms it; the next tap on the page stamps it, and the toolbar says so.
 * Drag-and-drop from a panel across an `AndroidView` seam is a second gesture system for the same
 * outcome — and the arming step is what lets one element be stamped four times without reopening
 * anything.
 *
 * ## ⭐ ROADMAP M10 — one of the design's two side panels
 *
 * A [GlassPanel] rather than a `Card`, at the reference design's 280 dp. Two things about the restyle
 * are decisions rather than substitutions:
 *
 * - **An armed row is a *filled* surface ([Ink.PanelRaised]), and an idle one has no fill and no
 *   border at all.** The old row was bordered either way and differed only in its background tint,
 *   which on a dark scheme is a few percent of luminance — *"which element is armed"* is the one
 *   question this panel exists to answer, and it has to be answerable from across a tablet.
 * - **The size line is a [MicroLabel]**, i.e. monospaced. `12 PIECES · 340×210 PT` beside
 *   `3 PIECES · 90×88 PT` lines up under a proportional face only by accident.
 */
@Composable
fun ElementPanel(
    elements: List<Element>,
    armed: Element?,
    onArm: (Element?) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
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
            EmptyLibrary()
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(RowGap)) {
                // Grouped by collection, in first-use order. `groupBy` keeps insertion order, so
                // the panel does not reshuffle itself when an element is added to an old group.
                elements.groupBy { it.collection }.forEach { (collection, inGroup) ->
                    item(key = "collection-$collection") {
                        MicroLabel(collection, modifier = Modifier.padding(top = HeadingTopGap))
                    }
                    items(inGroup, key = { it.id }) { element ->
                        ElementRow(element, armed?.id == element.id, onArm, onDelete)
                    }
                }
            }
        }
    }
}

/**
 * What an empty library says.
 *
 * Left as a sentence rather than made a [MicroLabel]: the capture gesture is not discoverable, so
 * this is the only place it is explained, and a tracked-out uppercase single line cannot hold it.
 */
@Composable
private fun EmptyLibrary() {
    Text(
        text = "Nothing saved yet. Pick the Elements tool and drag a box round a diagram to keep it.",
        color = Ink.TextMuted,
        fontSize = InkType.ChipSize,
        lineHeight = EmptyLineHeight,
    )
}

@Composable
private fun ElementRow(element: Element, armed: Boolean, onArm: (Element?) -> Unit, onDelete: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(InkDims.ChipCorner)
            .background(if (armed) Ink.PanelRaised else Color.Transparent)
            .clickable { onArm(if (armed) null else element) }
            .padding(horizontal = RowPaddingH, vertical = RowPaddingV),
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
        // Deliberately not a confirmation dialog: this removes the index row and leaves the bytes
        // (see `ElementLibrary.delete`), so nothing here is irreversible in the way CLAUDE.md
        // rule 1 is about. `danger` is still right — the chip *does* the thing rather than opening
        // a question about it, which is exactly the case that colour is reserved for.
        TextChip(label = "Remove", danger = true, onClick = { onDelete(element.id) })
    }
}

/**
 * The line under an element's name.
 *
 * ⚠️ Deliberately terse. [MicroLabel] is one line in a monospaced, tracked-out face, which is
 * roughly 40 % wider than the same words in the body font — and the column it sits in is about
 * 160 dp. "12 pieces · 340×210 pt" does not fit, and the label it replaced *wrapped*. It ellipsises
 * now rather than clipping, but the honest fix is fewer characters: the number of pieces and the
 * size are the two facts that distinguish one saved element from another, and the word "pieces" is
 * not one of them.
 */
private fun Element.summary(): String = "${members.size} · ${widthPt.roundToInt()}×${heightPt.roundToInt()} pt"

/** The reference design's side-panel width. */
private val PanelWidth = 280.dp

private val RowGap = 4.dp
private val HeadingTopGap = 6.dp
private val RowPaddingH = 8.dp
private val RowPaddingV = 6.dp
private val RowTextGap = 2.dp
private val EmptyLineHeight = 18.sp
