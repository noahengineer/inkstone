package dev.inkstone.app.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import dev.inkstone.app.ui.GlassPanel
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.InkType
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.TextChip

/**
 * ⭐ SPEC §8.1's language model — offered at the moment it would have helped, and then followed until
 * it has. One panel for both screens that offer it, so the two cannot drift apart, and so the
 * disclosure below cannot be on one of them and missing from the other (it was: *My handwriting*
 * offered a bare *"Download recognition model"* chip).
 *
 * ⚠️ **The size and the metrics disclosure are in front of the user, not behind the button.** SPEC
 * §8.1 requires disclosing that ML Kit reports anonymous performance metrics to Google, and the only
 * useful place for that is before the download rather than after. A [TextChip] is one clipped line,
 * so the sentence is a line of its own above the chip, and the chip keeps only the verb (ROADMAP M10).
 *
 * @param purpose the offer's first sentence — what the model is for *on this screen*.
 */
@Composable
fun RecognitionModelPanel(
    panel: ModelPanel,
    purpose: String,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPanel(modifier = modifier.fillMaxWidth()) {
        when (panel) {
            is ModelPanel.Offer -> Offer(panel, purpose, onDownload, onDismiss)
            is ModelPanel.Downloading -> Downloading(panel)
            is ModelPanel.Reading -> Reading(panel)
            is ModelPanel.Ready -> Ready(panel, onDismiss)
        }
    }
}

@Composable
private fun Offer(offer: ModelPanel.Offer, purpose: String, onDownload: () -> Unit, onDismiss: () -> Unit) {
    MicroLabel("Handwriting recognition")
    Body(purpose)
    Small("English (~20 MB) · ML Kit sends anonymous usage metrics to Google")
    offer.error?.let { Small("Download failed: $it", color = Ink.Danger) }
    Row(horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap)) {
        TextChip(label = if (offer.error != null) "Try again" else "Download English", onClick = onDownload)
        if (offer.error != null) TextChip(label = "Dismiss", onClick = onDismiss)
    }
}

/**
 * ML Kit's download is a `Task<Void>` with no bytes in it, so this bar moves rather than fills — and
 * with no network there is no bar at all, because nothing is moving (see [ModelPanel.Downloading]).
 *
 * ⚠️ **The words carry it, not the bar.** With *Remove animations* on — and on every emulator this repo
 * tests with, where the animator scale is 0 — an indeterminate bar draws no segment, only its track.
 * The track is [Ink.Hairline] so that it at least reads as a bar; [Ink.SegmentTrack] composited to
 * within six levels of the panel and was invisible.
 */
@Composable
private fun Downloading(downloading: ModelPanel.Downloading) {
    if (downloading.waitingForNetwork) {
        MicroLabel("Waiting for an internet connection", color = Ink.TextStrong)
        Small(
            "The English model (about 20 MB) downloads by itself as soon as there is one. " +
                "You can leave this screen.",
        )
    } else {
        MicroLabel("Downloading the English model", color = Ink.TextStrong)
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = Ink.Focus,
            trackColor = Ink.Hairline,
            strokeCap = StrokeCap.Round,
        )
        Small("About 20 MB. It carries on if you leave this screen.")
    }
}

@Composable
private fun Reading(reading: ModelPanel.Reading) {
    Done("Model downloaded")
    Body("Reading your handwriting — ${reading.notesRead} of ${reading.notes} ${notesWord(reading.notes)}")
    LinearProgressIndicator(
        progress = { if (reading.notes == 0) 0f else reading.notesRead.toFloat() / reading.notes },
        modifier = Modifier.fillMaxWidth(),
        color = Ink.Focus,
        trackColor = Ink.Hairline,
        strokeCap = StrokeCap.Round,
    )
    Small("Search finds more as each note is read. It carries on if you leave this screen.")
}

@Composable
private fun Ready(ready: ModelPanel.Ready, onDismiss: () -> Unit) {
    Done("Handwriting search is ready")
    Body(readySummary(ready))
    if (ready.notesUnread > 0) {
        Small(
            "${ready.notesUnread} ${notesWord(ready.notesUnread)} could not be read yet. " +
                "They are tried again while the tablet is charging.",
        )
    }
    TextChip(label = "OK", onClick = onDismiss)
}

/**
 * What *Ready* says it did. Pages as well as notes, because a page is what a search hit opens, and
 * *"already read"* when it read none — a model downloaded a second time finds every page current, and
 * *"read 0 pages"* would read as a failure.
 */
internal fun readySummary(ready: ModelPanel.Ready): String = when {
    ready.notes == 0 -> "There were no notes to read. What you write from now on is read as you go."

    ready.pagesRead == 0 -> "Every note had already been read. What you write from now on is read as you go."

    else ->
        "Read ${ready.pagesRead} ${if (ready.pagesRead == 1) "page" else "pages"} in ${ready.notes} " +
            "${notesWord(ready.notes)}. What you write from now on is read as you go."
}

/** A check and a line in the accent: the one part of this panel that should be read at a glance. */
@Composable
private fun Done(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
    ) {
        Text(text = "✓", color = Ink.Focus, fontSize = InkType.GlyphSize)
        MicroLabel(text, color = Ink.TextStrong)
    }
}

@Composable
private fun Body(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = Ink.TextNormal)
}

@Composable
private fun Small(text: String, color: Color = Ink.TextMuted) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}

private fun notesWord(count: Int) = if (count == 1) "note" else "notes"
