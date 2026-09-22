package dev.inkstone.core.storage

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.Document
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.RemoteName
import dev.inkstone.core.model.foldPage

/**
 * ⭐ **SPEC §7.7 layer 3 — the conflicted copy, as a note this device can open.**
 *
 * Layer 3 is what happens when layers 1 and 2 could not run at all: the peer's file is a
 * schema from the future, or its bundle is gone, or a 412 survived a merge attempt. The
 * rule is that **both versions are kept** (CLAUDE.md rule 1 — never resolve a conflict by
 * discarding bytes), which needs two artifacts and not one:
 *
 * - **On the server, the remote bytes verbatim**, PUT to a second path under the name
 *   [RemoteName.conflictedCopy] gives. Nothing is re-written and no PDF is generated, so
 *   whatever this build could not understand is still there to be understood by a later one.
 *   That is the caller's half.
 * - **Locally, a note the user can actually open** — this. A file on a server that this
 *   build refuses to open is a safe place to keep bytes and a useless place to keep notes.
 *
 * A sibling of [duplicateNote], reading from *bytes* rather than a [DocumentId], and the
 * argument for folding-and-rewriting rather than copying files is the same one — see that
 * function's KDoc. Nothing here recovers anything: it reads a bundle or it answers null,
 * because SPEC §5.6's recovery is REPLACE-not-union and stamps every op at
 * `highestKnownCounter + 1_000_000`, so a peer's lossy recovery run from a background
 * worker would win every LWW register against this device's full-fidelity note.
 *
 * ## Identity: a new [DocumentId] **and** new [ObjectId]s, both mandatory
 *
 * Three independent reasons, any one of them sufficient:
 *
 * 1. **`notes/<id>/` is one directory and `note.id` is the library index's primary key.** A
 *    copy that shared the id would not be a copy — it would *be* the original, overwritten.
 * 2. **The XMP marker carries `documentId`** (SPEC §5.6). A copy that kept it is
 *    indistinguishable from the original to the next reconcile, so the two would be matched
 *    up and merged back together — and the copy exists precisely because merging was
 *    impossible. This is SPEC §5.6's duplication failure, inverted.
 * 3. **Two objects with one identity in a grow-only union converge onto one geometry.** The
 *    fold's payload rule is first-write-wins per [ObjectId], so whichever `AddObject` a
 *    device folded first would supply the picture for *both* notes — on every device,
 *    consistently, with no error.
 *
 * ## What is not touched
 *
 * **Nothing local is superseded, and that is the whole point of layer 3.** The local note
 * keeps its [DocumentId], its journal, its undo stack, its snapshot and its remote binding;
 * this function never opens it and never names it. Contrast [NoteStore.replaceWithRecovered],
 * which is the one operation in the app that deliberately supersedes a whole note, and which
 * a pulled file must never reach.
 *
 * @param remoteBundle the peer's `note.inkstone`, already lifted out of their published PDF.
 * @param title ⭐ **the copy's finished name, already stamped by the caller** with
 *   [RemoteName.conflictedCopy] — used verbatim. It reads as an odd division of labour and it is
 *   the only one that holds: the caller needs that string anyway, for the remote path the verbatim
 *   bytes are PUT to, and a stamp minted *here* would come from a second reading of the clock. The
 *   two readings are separated by a download, so the local note's title and the remote file's name
 *   disagreed by however long the file took to arrive — one conflict wearing two names, neither
 *   obviously the other's twin. Stamping once, in the caller, makes them equal by construction.
 * @return the new document, or null when [remoteBundle] is not a bundle this build can read —
 *   a stranger's PDF, a truncated download, or a schema from the future. A real state, never
 *   an exception: a conflict that surfaces as a crash inside a WorkManager job is read as a
 *   transport failure and retried forever.
 */
public suspend fun NoteStore.conflictedCopyOf(
    remoteBundle: ByteSource,
    title: String,
    deviceId: String,
    ids: IdFactory,
    clock: Clock,
): Document? {
    // ⚠️ `BundleReader.open` *throws* on a bundle from a newer schema rather than answering
    // null (SPEC §5.8's `require`) — and a newer schema is one of layer 3's three triggers,
    // so this is the likeliest input, not an edge case. `NoteStore.loadViewState` uses the
    // same idiom for the same reason: a file this build cannot read is an answer, not a fault.
    val remote = runCatching { BundleReader.open(remoteBundle) }.getOrNull() ?: return null
    val sourcePages = remote.document.livePages
    if (sourcePages.isEmpty()) return null

    // `create` with no `id` mints one — identity reason 1, structurally rather than by
    // convention: there is no argument here that could carry the remote's id through.
    //
    // [title] verbatim, and no reading of the clock: the caller stamped it once and is using the
    // same string for the remote path. See the `title` parameter.
    val copy = create(title = title, deviceId = deviceId, pageCount = sourcePages.size)
    val session = NoteSession.open(this, ids, clock, copy, deviceId)
    val targetPages = copy.document.livePages

    // Tombstones excluded (`livePages`): a page the peer deleted is deleted, and the deleted
    // page's ops are still in the verbatim bytes on the server if they are ever wanted.
    sourcePages.forEachIndexed { index, ref ->
        session.copyPage(remote, ref.id, targetPages[index].id, ids)
    }

    // The peer's unrecognised entries, its images and its manifest members, carried onto the
    // copy (SPEC §5.8, SPEC §5.3). `assets/` is the one that would be *visible* if it were
    // dropped — an `ImageObject` names its asset by string, and that string survives
    // re-identification, so a copy without the assets shows a blank rectangle where the picture
    // was.
    //
    // ⚠️ [BundleReader.carried] spans two families since M8c — the unrecognised entries and the
    // images — and that is why this line did not have to change when `assets/` gained a writer.
    // Narrowing it to `carriedUnknowns()` would compile, read as a tidy-up, and lose every
    // picture in the note layer 3 exists to preserve.
    //
    // Carrying the manifest members is safe for the one reason that matters: `documentId` is a
    // *known* field, so it is never in this map and there is no identity in it to leak onto the
    // new note.
    //
    // Materialized here rather than through `close(syncState)`, which takes no carried
    // arguments and would therefore rewrite the whole bundle a second time — and which would
    // also require naming a sync state this function does not know, since whether the copy is
    // bound to the remote path is the caller's decision. There is nothing to compact: a note
    // created a moment ago fails every one of `Compaction.isAllowed`'s gates.
    session.materialize(remote.carried(), remote.carriedManifest)
    // SPEC §5.1's invariant, for the same reason `duplicateNote` asserts it: a note handed
    // back as a journal with no bundle is already one session stale.
    assertNotStale(copy.document.id)
    return session.document
}

/**
 * One remote page, folded and re-expressed as the shortest log that produces its picture.
 *
 * Folding first is what makes re-identification tractable: it collapses the transform, style,
 * z-order, erased region and presence registers onto the object payload (`withRegisters`), so
 * none of those needs an op of its own.
 *
 * ⚠️ **This paragraph used to say "every register", and to conclude that `AddObject` was
 * therefore the only op carrying an [ObjectId] a re-identification pass could miss. M8b made both
 * halves false.** The `SetTextContent` register is collapsed for a text box and for nothing else,
 * and it is where a shape's fill and a connector's attachment live. Copying is [copiedObjectOps]
 * now, which emits that register too and re-targets the ids inside it.
 *
 * The cost is that an op this build cannot parse is **not** carried into the copy.
 * [PageOp.Unknown] holds its own JSON verbatim (SPEC §5.8) but nothing here can tell whether
 * it references an object, so re-targeting it is impossible and copying it unchanged would
 * leave it pointing at an id the copy does not contain. That is the honest reason layer 3
 * keeps the remote *bytes* and not merely a picture of them.
 */
private suspend fun NoteSession.copyPage(remote: BundleReader, source: PageId, target: PageId, ids: IdFactory) {
    // A manifest and its page entries can disagree. A page listed with no entry still has to
    // become a page in the copy: dropping it would change the page count, which is the one
    // difference a reader notices immediately and cannot account for.
    val contents = remote.page(source)
    val folded = foldPage(
        source,
        contents?.ops.orEmpty(),
        defaultCanvas = contents?.canvas ?: PageCanvas.fixed(PageSize.Letter),
        defaultBackground = contents?.background ?: Background.Blank,
    )

    val ops = ArrayList<PageOp>(folded.objects.size + 1)
    // Page metadata first — `create` gave the copy default blank pages, and a conflicted copy
    // of a Cornell page that comes back blank looks like the copy lost something.
    ops += PageOp.SetPageMeta(
        opId = nextOpId(),
        clock = nextClock(),
        size = folded.size,
        background = folded.background,
    )
    ops += copiedObjectOps(folded, ids::newObjectId, ::nextOpId, ::nextClock)

    // `replay`, not `apply`: a copy has no history to undo *into*, and an undo stack seeded
    // with "every object on every page" is a stack whose first undo empties the note. Not
    // `merge` either — these ops are this device's own now. They carry this device's clocks
    // from a generator seeded at zero, because no peer has ever seen this document.
    replay(target, ops)
    // Per page, not once at the end. `NoteSession.pending` is an unbounded list holding every
    // applied op *including its geometry*, and `Journal.record` builds one buffer containing a
    // whole flush's JSON plus its whole geometry blob before it appends a byte. A conflicted
    // copy of a 200-page lecture notebook is an ordinary size for this, not a pathological one.
    flush()
}
