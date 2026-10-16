package dev.inkstone.app.calculator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import dev.inkstone.android.ink.InkSurfaceView
import dev.inkstone.app.handwriting.HandReadiness
import dev.inkstone.app.handwriting.PhraseWriter
import dev.inkstone.app.handwriting.PhraseWritten
import dev.inkstone.app.handwriting.WriteToolState
import dev.inkstone.app.ink.InkUiState
import dev.inkstone.app.ink.ObjectToolState
import dev.inkstone.app.note.NoteEditorViewModel
import dev.inkstone.core.handwriting.PlaceMode
import dev.inkstone.core.handwriting.Placements
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.viewToPage
import kotlinx.coroutines.launch
import dev.inkstone.core.model.Offset as ViewOffset

/**
 * ⭐ The editor's [AnswerTarget]: a dropped answer lands as [AnswerLanding] decides — the user's
 * handwriting, else a text box (docs/CALCULATOR.md §6). Both writes are the editor's own, under its
 * lock, journaled, one undo entry each, and uploaded through the outbox like every edit (CLAUDE.md
 * rules 4 and 10). Nothing here writes anything else.
 *
 * - **Handwriting** is the Write panel's write ([PhraseWriter]), with *Maths* on so `1/200` stacks and
 *   `√(2)` gets its bar (docs/HANDWRITING-MATHS.md), and the panel's own *Match the note* and wrap
 *   width — an answer is placed by D15's rules exactly as a phrase dropped from the panel is.
 * - **The text box** is created with its text already in it, never empty and then typed into: the
 *   first `SetTextContent` on an empty box has no inverse, so *"drop, press undo"* would have nothing
 *   to undo to (`TextBoxEdit.create`'s KDoc). A non-null style ([ObjectToolState.DEFAULT_TEXT_STYLE])
 *   and the default format — sans, 14 pt, the face the PDF embeds (research 08 §5.2: never MONO).
 * - **Nothing is selected and the tool is left alone.** An answer is put down so the user can go on
 *   writing beside it; an Element is selected after a drop because moving it is the next thing done
 *   with one, and that is not true here.
 */
@Composable
internal fun rememberAnswerTarget(
    ui: InkUiState,
    objects: ObjectToolState,
    editor: NoteEditorViewModel,
    surface: InkSurfaceView?,
): AnswerTarget {
    val scope = rememberCoroutineScope()
    val view = rememberUpdatedState(surface)
    return remember(ui, objects, editor) {
        val landing = AnswerLanding(
            handwrite = { words, start ->
                handwrite(words, start, PhraseWriter(editor, ui), editor, ui, objects.write)
            },
            typeset = { box, words ->
                editor.insertTextBox(box, words, ObjectToolState.DEFAULT_TEXT_STYLE, ObjectFormat.Default) != null
            },
            report = editor::report,
        )
        AnswerTarget(
            pxPerPt = { ui.camera.scale },
            drop = drop@{ answer, at, text ->
                val paper = view.value?.pageCanvas ?: return@drop
                val centre = viewToPage(ViewOffset(at.x, at.y), ui.camera)
                val box = AnswerPlacement.boxFor(centre, text.widthPt, text.heightPt, paper)
                    ?: return@drop editor.report(OFF_THE_PAPER)
                scope.launch { landing.land(answer, box, text.baselinePt) }
            },
        )
    }
}

/** [words] in the user's hand from [start]; null when written, else why not, in words for the user. */
@Suppress("LongParameterList")
private suspend fun handwrite(
    words: String,
    start: PagePoint,
    phrases: PhraseWriter,
    editor: NoteEditorViewModel,
    ui: InkUiState,
    write: WriteToolState,
): String? {
    val profile = when (val hand = phrases.ready()) {
        is HandReadiness.Ready -> hand.profile
        is HandReadiness.NotReady -> return hand.reason
    }
    val fold = editor.currentFold() ?: return NO_PAGE
    val writing = phrases.measure(fold, write.matchNote)
    val placement =
        Placements.resolve(start, PlaceMode.DROP, writing, profile.index.stats, write.wrapWidthPt, write.matchNote)
    val written = phrases.write(words, placement, ui.handwritingNeatness, profile, fold, emptyList(), maths = true)
    return (written as? PhraseWritten.NotWritten)?.reason
}

private const val OFF_THE_PAPER = "Drop the answer on the paper."
private const val NO_PAGE = "no page is open."
