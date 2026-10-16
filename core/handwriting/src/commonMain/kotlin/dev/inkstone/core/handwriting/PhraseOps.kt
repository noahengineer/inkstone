package dev.inkstone.core.handwriting

import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PhrasePlacement
import dev.inkstone.core.model.PhraseRecord
import dev.inkstone.core.model.SelectionEdit
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject

/** A phrase found on a page: its record, where it is now, and every stroke that belongs to it. */
public class PhraseOnPage(
    public val first: ObjectId,
    public val record: PhraseRecord,
    public val placement: PhrasePlacement?,
    public val strokes: List<ObjectId>,
)

/**
 * ⭐ docs/HANDWRITING-SYNTHESIS.md §4.3 — the ops that put a phrase into a note, as one
 * `session.apply` and therefore **one undo entry**.
 *
 * In order: a tombstone for everything the phrase replaces (the old strokes on *Write again* and
 * *Edit text*, the text box on *text box → handwriting*), one `AddObject(StrokeObject)` per stroke
 * with z keys above everything on the page, one `SetFormat(hw)` per stroke, and the phrase's
 * record on the first stroke's content register (D3).
 *
 * The style register is left null — *as captured* — exactly as for ink the pen drew: the pen is in
 * the blob, and a duplicate of it in a register would be two sources that drift (CLAUDE.md).
 *
 * Pure, so the shape of the one undo entry is a test, not a hope.
 */
public object PhraseOps {

    /**
     * @param tags one per stroke, in the same order (`WrittenPhrase.taggedStrokes`): the phrase's
     *   group, and the backend and model of the word each stroke belongs to (D2). One list per stroke
     *   rather than one tag per phrase, so a neural word's strokes can never be written down as
     *   assembly's.
     */
    @Suppress("LongParameterList")
    public fun build(
        strokes: List<StrokeGeometry>,
        tags: List<GeneratedInk>,
        record: PhraseRecord,
        topZ: FractionalKey?,
        replacing: List<ObjectId>,
        newObjectId: () -> ObjectId,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> {
        if (strokes.isEmpty()) return emptyList()
        require(tags.size == strokes.size) { "${tags.size} tags for ${strokes.size} strokes" }
        require(tags.map { it.group }.toSet().size == 1) { "one phrase is one group" }
        val ops = ArrayList<PageOp>()
        for (old in replacing) ops += PageOp.DeleteObject(nextOpId(), nextClock(), old)
        val keys = SelectionEdit.keysAbove(topZ, strokes.size)
        val ids = strokes.map { newObjectId() }
        strokes.forEachIndexed { i, geometry ->
            val stroke = StrokeObject(id = ids[i], geometry = geometry, z = keys[i])
            ops += PageOp.AddObject(nextOpId(), nextClock(), stroke)
        }
        ids.forEachIndexed { i, id ->
            ops += PageOp.SetFormat(nextOpId(), nextClock(), id, tags[i].applyTo(ObjectFormat.Default).encode())
        }
        ops += PageOp.SetTextContent(nextOpId(), nextClock(), ids.first(), record.encode())
        return ops
    }

    /** The ids [build]'s ops add, in stacking order. */
    public fun addedIds(ops: List<PageOp>): List<ObjectId> = ops.mapNotNull { (it as? PageOp.AddObject)?.obj?.id }

    /**
     * The phrase [ids] belong to on [page]: its record, read off whichever stroke carries it, and its
     * placement mapped through that stroke's current transform so *Write again* lands where the phrase
     * is now, not where it was first put. Null when none of the strokes carries a record — the first
     * stroke was erased away or deleted, and the phrase has become plain ink (D3).
     */
    public fun find(page: FoldedPage, ids: List<ObjectId>): PhraseOnPage? {
        val key = ids.mapNotNull { id -> keyOf(page, id) }.toSet().singleOrNull() ?: return null
        // The same copy key, not just the same group: a duplicate of the phrase elsewhere on the page
        // is another phrase, as an Element copy is (D2).
        val members = page.objects.filter { it is StrokeObject && keyOf(page, it.id) == key }.map { it.id }
        return members.firstNotNullOfOrNull { id ->
            val record = PhraseRecord.decode(page.texts[id])
            val transform = page.find(id)?.transform
            if (record == null || transform == null) {
                null
            } else {
                PhraseOnPage(id, record, record.placementOn(transform), members)
            }
        }
    }

    private fun keyOf(page: FoldedPage, id: ObjectId): String? {
        val obj = page.find(id) ?: return null
        return GeneratedInk.ofRecord(page.formatOf(id))?.copyKey(obj.transform)
    }
}
