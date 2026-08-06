package dev.inkstone.core.storage

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.Document
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.InkObject
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.TextBoxObject
import dev.inkstone.core.model.foldPage

/**
 * ROADMAP M3 — *"rename, move, duplicate"*. The duplicate.
 *
 * ## Why this is a fold-and-rewrite rather than a file copy
 *
 * Copying `notes/<id>/` and giving the copy a new [DocumentId] would be one line and would
 * be wrong in two ways that only show up later:
 *
 * 1. **Object identity.** The copy would carry every original [ObjectId]. Two objects with
 *    one identity in a grow-only union means the fold's first-write-wins payload rule picks
 *    one geometry and applies it to both — on every device, consistently, with no error.
 *    SPEC §5.6 names the same failure for recovered imports; `NoteSession.duplicatePage`
 *    already re-identifies for the same reason.
 * 2. **History.** A duplicate inherits nothing worth inheriting — no undo stack, no
 *    journal, no snapshot, and no Lamport history that a peer has ever seen. Folding first
 *    means the copy starts as the shortest log that produces the current picture, which is
 *    also what compaction would have produced (SPEC §4.7).
 *
 * The original is not touched. It is opened read-only and closed by going out of scope.
 *
 * @return the new document, or null when [sourceId] is not a note.
 */
public suspend fun NoteStore.duplicateNote(
    sourceId: DocumentId,
    title: String,
    deviceId: String,
    ids: IdFactory,
    clock: Clock,
): Document? {
    val source = open(sourceId) ?: return null
    val sourcePages = source.document.livePages
    if (sourcePages.isEmpty()) return null

    val copy = create(title = title, deviceId = deviceId, pageCount = sourcePages.size)
    val session = NoteSession.open(this, ids, clock, copy, deviceId)
    val targetPages = copy.document.livePages

    sourcePages.forEachIndexed { index, ref ->
        val loaded = loadPage(source, ref.id)
        val folded = foldPage(ref.id, loaded.ops, defaultSize = loaded.size, defaultBackground = loaded.background)
        val target = targetPages[index].id

        val ops = ArrayList<PageOp>(folded.objects.size + 1)
        // Page metadata first: `create` gave the copy default blank pages, and a duplicate
        // of a Cornell page that comes back blank is a bug the user notices immediately.
        ops += PageOp.SetPageMeta(
            opId = session.nextOpId(),
            clock = session.nextClock(),
            size = folded.size,
            background = folded.background,
        )
        for (obj in folded.objects) {
            ops += PageOp.AddObject(
                opId = session.nextOpId(),
                clock = session.nextClock(),
                // `withRegisters()` with no arguments materialises the winning register
                // values onto the payload, so the copy needs no register ops to look right.
                obj = obj.withRegisters().withCopiedId(ids.newObjectId()),
            )
        }
        // Not undoable: the copy has no history to undo *into*, and an undo stack seeded
        // with "everything on every page" is a stack whose first undo empties the note.
        session.apply(target, ops, undoable = false)
    }

    session.close()
    return session.document
}

/**
 * The same object under a new identity.
 *
 * A near-twin of `NoteSession`'s private `withNewId`, and deliberately not shared with it:
 * that one is reachable from a session and this one from a store, and hoisting either onto
 * [InkObject] would advertise re-identification as something an editor may do. An object
 * that changes id mid-life is unmergeable.
 */
private fun InkObject.withCopiedId(id: ObjectId): InkObject = when (this) {
    is StrokeObject -> copy(id = id)
    is ImageObject -> copy(id = id)
    is TextBoxObject -> copy(id = id)
    is ShapeObject -> copy(id = id)
}
