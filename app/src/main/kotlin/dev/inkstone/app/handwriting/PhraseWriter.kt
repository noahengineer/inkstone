package dev.inkstone.app.handwriting

import android.util.Log
import dev.inkstone.android.ink.InkBrushKind
import dev.inkstone.android.ink.PenConfig
import dev.inkstone.app.ink.InkUiState
import dev.inkstone.app.note.NoteEditorViewModel
import dev.inkstone.core.handwriting.MathsPhrase
import dev.inkstone.core.handwriting.PageWriting
import dev.inkstone.core.handwriting.Placement
import dev.inkstone.core.handwriting.SourcePreference
import dev.inkstone.core.handwriting.WordOutcome
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PenKind
import dev.inkstone.core.model.PenStyle
import dev.inkstone.core.model.PhrasePlacement
import dev.inkstone.core.model.PhraseRecord
import dev.inkstone.core.model.SnapGrid

/** Whether there is a hand to write in: the profile, or why there is none, in words for the user. */
internal sealed interface HandReadiness {
    data class Ready(val profile: HandwritingWriter.Profile) : HandReadiness

    data class NotReady(val reason: String) : HandReadiness
}

/** What one write came to. */
internal sealed interface PhraseWritten {
    /** Stamped onto the page; [ids] are the new strokes. */
    data class Stamped(val ids: List<ObjectId>) : PhraseWritten

    /** Nothing was written, and [reason] says why, in words for the user. */
    data class NotWritten(val reason: String) : PhraseWritten
}

/**
 * ⭐ Words to journaled strokes — the one write behind the Write panel (docs/HANDWRITING-SYNTHESIS.md
 * §6.2) and behind a calculator answer dropped onto the page (docs/CALCULATOR.md §6).
 *
 * Lifted out of the panel's runner unchanged, so that the second caller could not become a second way
 * of writing a phrase: the record, the per-stroke tags, the pen rule and the log line are written here
 * and nowhere else (CLAUDE.md, M12: *"a second client for a protocol the project already speaks is
 * where the old bugs come back"*). It reports nothing and selects nothing; each caller decides what
 * the user is told and what happens to the strokes afterwards.
 *
 * Everything slow — measuring the page, generating, reading back, encoding — is off the UI thread
 * inside [HandwritingWriter]; the one write is `stampHandwriting`, through the editor's own lock.
 */
internal class PhraseWriter(private val editor: NoteEditorViewModel, private val ui: InkUiState) {
    private val writer get() = editor.handwriting

    suspend fun ready(): HandReadiness {
        val profile = writer.profile()
        return when {
            !profile.modelReady -> HandReadiness.NotReady("Writing needs the recognition model — see My handwriting.")
            profile.index.isEmpty -> HandReadiness.NotReady("Nothing learned yet — see My handwriting.")
            else -> HandReadiness.Ready(profile)
        }
    }

    /** D15's measurement of the page, or an empty one when *Match the note* is off. */
    suspend fun measure(fold: FoldedPage, matchNote: Boolean): PageWriting {
        val noteId = editor.openNoteId
        return if (matchNote && noteId != null) {
            writer.pageWriting(noteId, fold)
        } else {
            PageWriting.empty(SnapGrid.forBackground(fold.background, fold.canvas.left, fold.canvas.top))
        }
    }

    /**
     * The current pen, unless it is the highlighter — then the fountain pen as the user last set it.
     * Handwriting is written, never highlighted: on a page with no writing to match, the first device
     * walk wrote a text box out in translucent yellow because the highlighter was the pen in hand.
     */
    fun writingPen(): PenStyle {
        val current = writer.penStyleOf(ui.pen)
        if (current.kind != PenKind.HIGHLIGHTER) return current
        return writer.penStyleOf(ui.toPrefs().pens[InkBrushKind.FOUNTAIN] ?: PenConfig.Default)
    }

    /** [text] written at [placement], replacing [replacing] in the same undo entry. */
    @Suppress("LongParameterList")
    suspend fun write(
        text: String,
        placement: Placement,
        neatness: Float,
        profile: HandwritingWriter.Profile,
        fold: FoldedPage,
        replacing: List<ObjectId>,
        maths: Boolean,
    ): PhraseWritten {
        val started = System.nanoTime()
        val preference = SourcePreference(editor.openNoteId?.value, fold.pageId.value, writer.today())
        val phrase = writer.write(text, placement.style(neatness), profile, preference, maths)
        if (!phrase.complete) {
            val refused = phrase.refusals.joinToString("; ") { "'${it.text}' ${reasonOf(it.reason)}" }
            return PhraseWritten.NotWritten("Not written: $refused")
        }
        val pen: PenStyle = placement.pen ?: writingPen()
        // Each stroke with its own word's backend and model (D2): a neural word says so, stroke by stroke.
        val tagged = phrase.taggedStrokes(placement.origin, writer.newGroup())
        val strokes = writer.encode(tagged.map { it.first }, pen, ui.inputModel, profile)
        if (strokes.size != tagged.size) {
            return PhraseWritten.NotWritten(
                "Not written: ${tagged.size - strokes.size} strokes could not be made into ink.",
            )
        }
        val record = PhraseRecord(
            text = text,
            placement = PhrasePlacement(
                originX = placement.origin.x,
                originY = placement.origin.y,
                xHeightPt = placement.xHeightPt,
                slantRad = placement.slantRad,
                angleRad = placement.angleRad,
                wrapWidthPt = placement.wrapWidthPt,
                linePitchPt = placement.linePitchPt,
                neatness = neatness,
                wordGapXHeights = placement.wordGapXHeights,
            ),
            extras = MathsPhrase.extras(maths),
        )
        val ids = editor.stampHandwriting(strokes, tagged.map { it.second }, record, replacing)
        val neural = phrase.words.filterIsInstance<WordOutcome.Written>().filter { it.backend == GeneratedInk.NEURAL }
        // Counts and measurements only — never the words (§9).
        Log.i(
            TAG,
            "wrote: words=${phrase.words.size} strokes=${ids.size} lines=${phrase.lineCount} maths=$maths " +
                "mathsWords=${phrase.words.count { (it as? WordOutcome.Written)?.isMaths == true }} " +
                "netStrokes=${tagged.count { it.second.backend == GeneratedInk.NEURAL }} " +
                "attempts=${phrase.words.sumOf { (it as? WordOutcome.Written)?.attempts ?: 0 }} " +
                "joins=${phrase.words.sumOf { (it as? WordOutcome.Written)?.joins ?: 0 }} " +
                "source=${placement.source} xh=${"%.2f".format(placement.xHeightPt)} " +
                "slant=${"%.3f".format(placement.slantRad)} angle=${"%.3f".format(placement.angleRad)} " +
                "pitch=${"%.1f".format(placement.linePitchPt)} pen=${pen.kind}/${Integer.toHexString(pen.colorArgb)}/" +
                "${"%.2f".format(pen.widthPt)} replaced=${replacing.size} " +
                "neural=${neural.size} model=${phrase.neuralModel ?: "-"} " +
                "neuralAttempts=${neural.sumOf { it.attempts }} " +
                "ms=${(System.nanoTime() - started) / NANOS_PER_MS}",
        )
        return PhraseWritten.Stamped(ids)
    }

    private companion object {
        const val TAG = "HandwritingWrite"
        const val NANOS_PER_MS = 1_000_000L
    }
}
