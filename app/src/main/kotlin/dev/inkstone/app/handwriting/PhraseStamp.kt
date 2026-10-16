package dev.inkstone.app.handwriting

import dev.inkstone.app.note.NoteEditor
import dev.inkstone.core.handwriting.PhraseOnPage
import dev.inkstone.core.handwriting.PhraseOps
import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PhraseRecord
import dev.inkstone.core.model.StrokeGeometry

/**
 * ⭐ docs/HANDWRITING-SYNTHESIS.md §6.3 — a written phrase onto the page on screen, as **one** undo
 * entry: [PhraseOps]' tombstones for whatever it is [replacing] (old strokes on *Write again* and
 * *Edit text*, a text box on *text box → handwriting*), its strokes, their `hw` tags and its record.
 *
 * Through [NoteEditor.editShownPage], so it is the editor's own lock, flush, `apply` and reconcile —
 * nothing here is a second write path. `readAgain` marks the page unread exactly as the pen's own
 * strokes do: recognition is what puts the phrase's words into search and into the PDF's text layer,
 * and an applied batch does not otherwise reach the debounce loop.
 *
 * The geometry is encoded before this is called, off the main thread (`TrajectoryInk`); nothing
 * here tessellates, encodes or reads a file under the lock. Ids already gone from the page — a
 * stroke a peer deleted since the panel opened — are dropped from [replacing] rather than
 * tombstoned twice.
 *
 * @return the new strokes' ids, in stacking order, so the caller can select what it wrote.
 */
suspend fun NoteEditor.stampHandwriting(
    strokes: List<StrokeGeometry>,
    tags: List<GeneratedInk>,
    record: PhraseRecord,
    ids: IdFactory,
    replacing: List<ObjectId> = emptyList(),
): List<ObjectId> {
    var built: List<PageOp> = emptyList()
    val applied = editShownPage(readAgain = true) { before, session ->
        PhraseOps.build(
            strokes = strokes,
            tags = tags,
            record = record,
            topZ = before.objects.maxOfOrNull { it.z },
            replacing = replacing.filter { before.find(it) != null },
            newObjectId = ids::newObjectId,
            nextOpId = session::nextOpId,
            nextClock = session::nextClock,
        ).also { built = it }
    }
    return if (applied) PhraseOps.addedIds(built) else emptyList()
}

/** The phrase [ids] belong to on the page on screen — its words and where it is now — or null. */
suspend fun NoteEditor.phraseOf(ids: List<ObjectId>): PhraseOnPage? = currentFold()?.let { PhraseOps.find(it, ids) }
