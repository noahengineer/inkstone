package dev.inkstone.app.note

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.inkstone.app.ink.ChromeItem
import dev.inkstone.app.ui.ChipKind
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.Readout
import dev.inkstone.app.ui.TextChip
import dev.inkstone.core.model.PageTemplate

/**
 * ⭐ ROADMAP M10 — the design's *"Bottom Center (Page Bar)"* zone, one chip at a time.
 *
 * > *"Paging controls `‹ Page 1 of 5 ›`, `ADD`, `DUPLICATE`, `SHARE`, and template toggles."*
 *
 * ROADMAP M2 built this as *"the plainest thing that makes every acceptance criterion reachable"*,
 * and M10 gave it four groups behind dividers — where you are, what pages there are, what they look
 * like, and how one leaves the app. Since the layout editor, each chip can live in any bar, so the
 * page bar is only where these start out (`ChromeLayout`); whichever bar holds one draws it from here.
 *
 * ⚠️ **It has to fit between the history and zoom widgets, and it did not.** Eleven chips plus a
 * revision readout ran about 120 dp past the space between them, so the bar scrolled and its last chip
 * sat clipped flush against the pill's rounded end. Two things left: the page-*move* arrows, to the
 * page overview that exists for reordering, and `local_rev`, to the diagnostics panel.
 *
 * @param exporting whether an export is in flight. UI-local rather than part of [NoteEditorState]:
 *   the export is driven from the composition, because handing a Uri to another app needs a
 *   `Context` and a chooser, and neither belongs in the editor.
 */
@Composable
internal fun PageChip(item: ChromeItem, state: NoteEditorState, actions: PageBarActions, exporting: Boolean) {
    when (item) {
        ChromeItem.PAGE_PREVIOUS, ChromeItem.PAGE_COUNTER, ChromeItem.PAGE_NEXT -> Paging(item, state, actions)

        ChromeItem.PAGE_ADD -> TextChip(label = "Add", onClick = actions.onAddPage)

        ChromeItem.PAGE_DUPLICATE -> TextChip(label = "Duplicate", onClick = actions.onDuplicatePage)

        ChromeItem.PAGE_DELETE -> TextChip(label = "Delete", danger = true, onClick = actions.onDeletePage)

        ChromeItem.PAGE_TEMPLATE -> {
            var open by remember { mutableStateOf(false) }
            TemplateChip(
                template = state.template,
                infinite = state.infiniteCanvas,
                open = open,
                onOpen = { open = true },
                onDismiss = { open = false },
                onPick = actions.onTemplate,
                onToggleInfinite = actions.onToggleInfiniteCanvas,
            )
        }

        else -> ShareChip(exporting = exporting, actions = actions)
    }
}

/** Where you are: `‹ 3 of 12 ›`. The counter is also the overview's handle. */
@Composable
private fun Paging(item: ChromeItem, state: NoteEditorState, actions: PageBarActions) {
    when (item) {
        ChromeItem.PAGE_PREVIOUS -> TextChip(
            label = "‹",
            kind = ChipKind.GLYPH,
            contentDescription = "Previous page",
            enabled = state.pageIndex > 0,
            onClick = actions.onPrevious,
        )

        // M3 gave the overview sheet thumbnails, and "tap the page number to see the pages" needs no
        // extra button to explain.
        ChromeItem.PAGE_COUNTER -> TextChip(
            label = "${state.pageIndex + 1} of ${state.pageCount}",
            kind = ChipKind.VALUE,
            onClick = actions.onShowPages,
            modifier = Modifier.width(CounterWidth),
        )

        else -> TextChip(
            label = "›",
            kind = ChipKind.GLYPH,
            contentDescription = "Next page",
            enabled = state.pageIndex < state.pageCount - 1,
            onClick = actions.onNext,
        )
    }
}

/*
 * What pages there are: add, duplicate, delete — the three `PAGE_ADD`/`PAGE_DUPLICATE`/`PAGE_DELETE`
 * chips in `PageBar` above.
 *
 * ⚠️ **The two page-*move* chips are not here, and reordering is not lost.** They were, and between
 * them and the revision readout the bar was about 120 dp wider than the space between the history
 * and zoom widgets — so its last chip was clipped flush against the pill's edge and the whole bar
 * read as cut off. Reordering lives in the page overview (`PageOverviewSheet`), which is the screen
 * that exists for it, shows a thumbnail of what is being moved, and is one tap away on the counter
 * to the left. M2's *"reorder"* acceptance criterion is still met, on a better surface.
 *
 * Deleting stays, because it acts on *this* page and that is where you are. It is refused by the
 * session when it is the last one rather than hidden here, so the reason arrives as a message
 * instead of as a greyed-out chip with no explanation.
 */

/**
 * ⭐ ROADMAP M10 — the two exports behind one `SHARE`, which is what the design's chip list names.
 *
 * They were two chips, *"Share PDF"* and *"Share audio"*, and together they were about a fifth of a
 * bar that already did not fit — the pair is what pushed the page bar under the zoom widget on a
 * 2560 px tablet (see `InkScreen.BottomWidgets`). One chip and a two-item menu is the same two taps
 * for the PDF and the same two for a recording.
 *
 * ⚠️ **Both are always offered, and the recording one is never hidden when the note has none.**
 * Whether a lecture exists is a database read, and putting one behind every recomposition of this
 * bar is what SPEC §5.7 keeps off the drawing path; `exportRecordings` says so instead when there is
 * nothing to hand over. This is the only route a recording has out of the app when Nextcloud is not
 * set up (docs/M12-AUDIO.md §15.5).
 *
 * SPEC §5.2 — the PDF entry is disabled while an export is running rather than hidden: it
 * materializes first, which on a large notebook takes long enough that a second tap would otherwise
 * queue a second rewrite.
 */
@Composable
private fun ShareChip(exporting: Boolean, actions: PageBarActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextChip(
            label = if (exporting) "Exporting…" else "Share",
            selected = open,
            enabled = !exporting,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { MicroLabel("Published PDF") },
                onClick = {
                    open = false
                    actions.onShare()
                },
            )
            DropdownMenuItem(
                text = { MicroLabel("Recordings") },
                onClick = {
                    open = false
                    actions.onShareRecording()
                },
            )
        }
    }
}

/**
 * The paper: a chip that says what this page is, and a menu of the five templates — plus the
 * switch between a sheet and an endless canvas.
 *
 * ⭐ **The canvas toggle is in this menu rather than beside it**, and that is a width decision as
 * much as a taxonomy one. `docs/M10-PORTRAIT.md` records this bar running past its own edge at
 * 800 dp, where a control that has scrolled out of a pill is *absent from the view tree* and not
 * merely off-screen; a twelfth chip would have spent the space that fixed. It also belongs here:
 * "what is this page made of" is the question the chip already answers, and the ∞ on the label is
 * what says which answer is in force without opening anything.
 */
@Composable
private fun TemplateChip(
    template: PageTemplate,
    infinite: Boolean,
    open: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (PageTemplate) -> Unit,
    onToggleInfinite: () -> Unit,
) {
    Box {
        TextChip(
            label = if (infinite) "${template.label()} ∞" else template.label(),
            selected = open,
            onClick = onOpen,
        )
        DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
            for (entry in PageTemplate.entries) {
                DropdownMenuItem(
                    text = { MicroLabel(entry.label()) },
                    onClick = {
                        onDismiss()
                        onPick(entry)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                // Says what pressing it does, not what is currently true: a menu item that reads
                // "Infinite canvas ✓" leaves the reader to work out whether the tick is a state or
                // a promise.
                text = { MicroLabel(if (infinite) "Fixed page" else "Infinite canvas") },
                onClick = {
                    onDismiss()
                    onToggleInfinite()
                },
            )
        }
    }
}

private fun PageTemplate.label(): String = when (this) {
    PageTemplate.BLANK -> "Blank"
    PageTemplate.RULED -> "Ruled"
    PageTemplate.GRID -> "Grid"
    PageTemplate.DOT_GRID -> "Dots"
    PageTemplate.CORNELL -> "Cornell"
}

/** Fixed, so the bar does not re-flow when the note passes page 9. */
private val CounterWidth = 96.dp

/** Everything the page bar can ask the editor to do. */
data class PageBarActions(
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onAddPage: () -> Unit,
    val onDuplicatePage: () -> Unit,
    val onDeletePage: () -> Unit,
    val onMoveLeft: () -> Unit,
    val onMoveRight: () -> Unit,
    val onTemplate: (PageTemplate) -> Unit,
    /**
     * ⭐ SPEC §4.5 — make this page's canvas endless, or put it back to a fixed sheet.
     *
     * Per page, like the template beside it. A note whose pages disagree is a real thing to want —
     * an imported handout stays A4 while the page of workings after it grows — and "the same for
     * every page" is what `Add` already does by inheriting the current page's paper.
     */
    val onToggleInfiniteCanvas: () -> Unit,
    /** ROADMAP M3 — the page overview, with a thumbnail per page. */
    val onShowPages: () -> Unit,
    /** ROADMAP M4 — materialize and hand the published PDF to another app (SPEC §5.2). */
    val onShare: () -> Unit,
    /**
     * ⭐ M12 — hand this note's lecture recordings to another app.
     *
     * Not a convenience: a recording is in app-private storage, so without this the only copy of the
     * one file this app cannot re-make sits on one device with no way off it (§15.5).
     */
    val onShareRecording: () -> Unit,
)
