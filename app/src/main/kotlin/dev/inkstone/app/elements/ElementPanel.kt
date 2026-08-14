package dev.inkstone.app.elements

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    Card(modifier = modifier.width(PANEL_WIDTH_DP.dp)) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Elements", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onClose) { Text("Close") }
            }
            if (elements.isEmpty()) {
                Text(
                    "Nothing saved yet. Pick the Elements tool and drag a box round a diagram to keep it.",
                    style = MaterialTheme.typography.labelSmall,
                )
                return@Column
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Grouped by collection, in first-use order. `groupBy` keeps insertion order, so
                // the panel does not reshuffle itself when an element is added to an old group.
                elements.groupBy { it.collection }.forEach { (collection, inGroup) ->
                    item(key = "collection-$collection") {
                        Text(collection, style = MaterialTheme.typography.labelMedium)
                    }
                    items(inGroup, key = { it.id }) { element ->
                        ElementRow(element, armed?.id == element.id, onArm, onDelete)
                    }
                }
            }
        }
    }
}

@Composable
private fun ElementRow(element: Element, armed: Boolean, onArm: (Element?) -> Unit, onDelete: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .background(
                if (armed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(4.dp),
            )
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
            .clickable { onArm(if (armed) null else element) }
            .padding(6.dp),
    ) {
        Column(modifier = Modifier.width(ROW_TEXT_WIDTH_DP.dp)) {
            Text(element.name, style = MaterialTheme.typography.labelLarge)
            Text(
                "${element.members.size} pieces · ${element.widthPt.roundToInt()}×${element.heightPt.roundToInt()} pt",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        // Deliberately not a confirmation dialog: this removes the index row and leaves the bytes
        // (see `ElementLibrary.delete`), so nothing here is irreversible in the way CLAUDE.md
        // rule 1 is about.
        TextButton(onClick = { onDelete(element.id) }) { Text("Remove") }
    }
}

private const val PANEL_WIDTH_DP = 280
private const val ROW_TEXT_WIDTH_DP = 160
