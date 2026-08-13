package dev.inkstone.core.storage

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.Document
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.InkObject
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.ShapeRecord
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
        // ⚠️ Not a bare `AddObject` loop since M8b — the text register is not collapsed onto the
        // payload and a connector's bindings have to be re-targeted. See [copiedObjectOps].
        ops += copiedObjectOps(folded, ids::newObjectId, session::nextOpId, session::nextClock)
        // `replay`, not `apply`: the copy has no history to undo *into*, and an undo stack
        // seeded with "everything on every page" is a stack whose first undo empties the note.
        session.replay(target, ops)
    }

    // A duplicate has never been near a server, whatever its original's state was.
    session.close(NoteSyncState.LOCAL_ONLY)
    return session.document
}

/**
 * The same object under a new identity.
 *
 * A near-twin of `NoteSession`'s private `withNewId`, and deliberately not shared with it:
 * that one is reachable from a session and this one from a store, and hoisting either onto
 * [InkObject] would advertise re-identification as something an editor may do. An object
 * that changes id mid-life is unmergeable.
 *
 * `internal` rather than private since M6, for the second store-level copy — SPEC §7.7 layer
 * 3's [conflictedCopyOf]. The two callers want the identical operation for the identical
 * reason, and a third hand-written `when` over the four object kinds is a place for a future
 * fifth kind to be forgotten.
 */
internal fun InkObject.withCopiedId(id: ObjectId): InkObject = when (this) {
    is StrokeObject -> copy(id = id)
    is ImageObject -> copy(id = id)
    is TextBoxObject -> copy(id = id)
    is ShapeObject -> copy(id = id)
}

/**
 * ⭐ A folded page re-expressed as the shortest log that reproduces it, **under fresh
 * [ObjectId]s** — the one implementation shared by all three copiers (a note duplicate, a page
 * duplicate, and SPEC §7.7 layer 3's conflicted copy).
 *
 * ## Why this exists at all, and what M8b broke
 *
 * Until M8b the three call sites were one line each, and [conflictedCopyOf]'s KDoc justified that
 * in as many words:
 *
 * > A fold collapses every register — transform, style, z-order, erased region, presence — onto
 * > the object payload (`withRegisters`), so the only op left to write is `AddObject` and there is
 * > **no second op carrying an [ObjectId] in a `target` field** that a re-identification pass
 * > could miss.
 *
 * Both halves of that stopped being true at once. The `SetTextContent` register is **not**
 * collapsed onto the payload for anything but a text box (`PageFolder.materialise`), and it is
 * where a shape's fill, corner radius and — the part that matters — a connector's attachment live
 * ([ShapeRecord]). So a one-line copy produced two failures, neither of which anything would have
 * reported:
 *
 * 1. **Every record vanished.** A duplicated diagram came back as unfilled boxes joined by
 *    straight authored lines, because the fill and the bindings were only ever in the register.
 * 2. **Every binding named the original's ids.** Even carried verbatim, a record points at objects
 *    the copy does not contain — so every connector in the copy would resolve as detached.
 *    Defined, non-crashing, and visibly wrong, which is the acceptance criterion passing while the
 *    feature does not work.
 *
 * Hoisting it here rather than fixing three call sites is the same reasoning [withCopiedId] gives
 * for being shared: a third hand-written copy is where the *next* register is forgotten.
 *
 * ## Which ops come out
 *
 * One `AddObject` per live object, then one `SetTextContent` per object that has a register value
 * — emitted whenever there is one, including for a text box whose payload already carries it. The
 * redundancy is a few dozen bytes per text box and it buys the rule *"if the fold answers for it,
 * the copy carries it"*, which has no per-kind branch for a future kind to fall through.
 *
 * A binding naming an object that is **not** in [folded] — its target was deleted before the copy
 * — keeps the original id, and therefore resolves in the copy as [ConnectorEndState.DETACHED_ABSENT]
 * rather than `DETACHED_TOMBSTONED`. The geometry is identical either way (see that type); only
 * the label the UI could show differs, and the copy genuinely does not contain the tombstone.
 */
internal fun copiedObjectOps(
    folded: FoldedPage,
    newObjectId: () -> ObjectId,
    nextOpId: () -> String,
    nextClock: () -> LamportClock,
): List<PageOp> {
    // The whole map first: a connector may be copied before the shape it points at.
    val remap = folded.objects.associate { it.id to newObjectId() }
    val ops = ArrayList<PageOp>(folded.objects.size)
    for (obj in folded.objects) {
        val id = remap.getValue(obj.id)
        ops += PageOp.AddObject(nextOpId(), nextClock(), obj.withRegisters().withCopiedId(id))
    }
    for (obj in folded.objects) {
        val text = folded.textOf(obj.id) ?: continue
        val id = remap.getValue(obj.id)
        // A record is re-targeted; anything else — a text box's text, or a register this build
        // cannot parse — is carried verbatim. Guessing at an unparseable one is how a stranger's
        // data becomes geometry (see `ShapeRecord.decode`).
        val carried = ShapeRecord.decode(text)?.remapTargets { remap[it] }?.encode() ?: text
        ops += PageOp.SetTextContent(nextOpId(), nextClock(), id, carried)
    }
    return ops
}
