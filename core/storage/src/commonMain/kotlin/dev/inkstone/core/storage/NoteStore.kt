package dev.inkstone.core.storage

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.Document
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageOrder
import dev.inkstone.core.model.PageRef
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.UndoEntry
import dev.inkstone.core.model.foldPage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * SPEC §5.1 — reads and writes a note's working files. **The journal model lives here.**
 *
 * - **Autosave = [appendOps].** One append to `journal.ops`. The bundle is not
 *   rewritten, the PDF is not rewritten, nothing is re-serialised.
 * - **[materialize] rewrites the bundle** and resets the journal. Runs on close,
 *   before upload, and on idle-while-charging — never on the ink path.
 * - **[open] = bundle, then journal replayed on top.**
 *
 * Every write except the journal append is write-temp-then-atomic-rename, because that
 * is [FileSystem]'s only offer (CLAUDE.md rule 10). The append is the deliberate
 * exception: an append cut short by a process kill costs the in-progress stroke and
 * nothing else, and [Journal] detects the torn tail on the way back in.
 *
 * ## The invariant
 *
 * SPEC §5.1: *"`document.pdf` is never more than one editing session stale. State this
 * in the code as an assertion."* [assertNotStale] is that assertion; the session calls
 * it when it closes a note.
 */
public class NoteStore(private val fs: FileSystem, private val clock: Clock, private val ids: IdFactory) {

    // -----------------------------------------------------------------------
    // Create
    // -----------------------------------------------------------------------

    /** A new note with [pageCount] identical blank pages, written to disk before it returns. */
    public suspend fun create(
        title: String,
        deviceId: String,
        pageCount: Int = 1,
        size: PageSize = PageSize.Letter,
        background: Background = Background.Blank,
    ): OpenNote = create(title, deviceId, List(pageCount) { NewPage(size, background) })

    /**
     * A new note whose pages differ — which is what a PDF import is (SPEC §6.2).
     *
     * *Added in M4.* Every page of an imported PDF has its own size, because every
     * page has its own CropBox and its own `/Rotate` (SPEC §4.4), and a scan with
     * one landscape page among ninety-nine portrait ones is entirely ordinary. The
     * uniform-page [create] above is this with the same page repeated.
     *
     * @param id the document's identity. Supplied only by a **Normal**-mode reopen
     *   of a published PDF, which must keep the id the file already claims; every
     *   other caller lets it be minted, because a document id chosen by anything
     *   other than the device creating the note is how two notes end up as one.
     */
    public suspend fun create(
        title: String,
        deviceId: String,
        pages: List<NewPage>,
        id: DocumentId? = null,
    ): OpenNote {
        require(pages.isNotEmpty()) { "a note needs at least one page" }
        val now = clock.nowEpochMillis()
        val keys = FractionalKey.sequence(pages.size)
        val refs = pages.indices.map { PageRef(ids.newPageId(), keys[it]) }
        val document = Document(
            id = id ?: ids.newDocumentId(),
            title = title,
            pages = refs,
            localRev = 1L,
            deviceId = deviceId,
            createdEpochMillis = now,
            modifiedEpochMillis = now,
        )
        val contents = BundleContents(
            document = document,
            pages = refs.indices.associate { index ->
                refs[index].id to PageContents(pages[index].size, pages[index].background, emptyList())
            },
            highestClockCounter = 0L,
        )

        fs.createDirectories(NoteLayout.noteDir(document.id))
        fs.writeBytesAtomically(NoteLayout.bundle(document.id), BundleWriter.write(contents))
        fs.writeBytesAtomically(NoteLayout.journal(document.id), Journal.header())

        return OpenNote(
            document = document,
            journalOps = emptyMap(),
            highestClockCounter = 0L,
            journalBytes = Journal.HEADER_SIZE.toLong(),
            journalWasTruncated = false,
            reader = null,
        )
    }

    // -----------------------------------------------------------------------
    // Open
    // -----------------------------------------------------------------------

    /**
     * SPEC §5.1 — *"load the embedded bundle, then replay `journal.ops` on top"*.
     *
     * Neither step reads a page's contents. The manifest gives the document and its
     * page order; the journal gives whatever has happened since the last
     * materialization. A page's ops are read when the page is drawn ([loadPage]), which
     * is what makes a 200-page notebook open in under two seconds.
     *
     * @return null when there is no such note.
     */
    public suspend fun open(id: DocumentId): OpenNote? {
        val bundlePath = NoteLayout.bundle(id)
        if (!fs.exists(bundlePath)) return null
        val reader = BundleReader.open(FileByteSource(fs, bundlePath)) ?: return null

        val journal = Journal.read(fs, NoteLayout.journal(id))
        val opsByPage = LinkedHashMap<PageId, MutableList<PageOp>>()
        var highestCounter = reader.highestClockCounter
        for (entry in journal.ops) {
            val pageId = entry.pageId ?: continue
            opsByPage.getOrPut(pageId) { ArrayList() } += entry.op
            highestCounter = maxOf(highestCounter, entry.op.clock.counter)
        }

        // The journal's document metadata is newer than the manifest's by construction —
        // the manifest was written at the last materialization, the journal after it.
        val document = journal.document
            ?.let { applyDocumentRecord(reader.document, it) }
            ?: reader.document
        val repaired = document.copy(
            pages = PageOrder.repair(document.pages),
            localRev = maxOf(document.localRev, journal.revision),
        )
        // ⭐ The title clock counts, and it is not an op. It comes out of the same generator as
        // every op (SPEC §7.7 layer 2 needs it to, so a rename sits in the same total order as
        // every other edit), and a rename that has only been *flushed* lives in the journal's
        // document record and nowhere else. Without this the very next session issues that same
        // counter again — and two devices then hold two titles at one clock, permanently, with
        // every op present and no error. A *materialized* rename is already covered, by
        // [BundleContents.highestClockCounter], which is where the invariant is kept.
        highestCounter = maxOf(highestCounter, repaired.titleClock?.counter ?: 0L)

        return OpenNote(
            document = repaired,
            journalOps = opsByPage,
            highestClockCounter = highestCounter,
            journalBytes = journal.wholeBytes.toLong(),
            journalWasTruncated = journal.truncatedAtByte != null,
            reader = reader,
        )
    }

    /**
     * Re-read the journal into [note].
     *
     * Called when a page is folded again after having been evicted: the ops written since
     * the note was opened are in the journal file, not in the [OpenNote] captured at open
     * time. Re-reading from disk rather than holding every op of the session in memory is
     * the point — geometry blobs are the bulk of a session's writes, and keeping them all
     * would give back exactly the memory the page cache is bounded to save.
     */
    public suspend fun reloadJournal(note: OpenNote): OpenNote {
        val journal = Journal.read(fs, NoteLayout.journal(note.document.id))
        val opsByPage = LinkedHashMap<PageId, MutableList<PageOp>>()
        for (entry in journal.ops) {
            val pageId = entry.pageId ?: continue
            opsByPage.getOrPut(pageId) { ArrayList() } += entry.op
        }
        return note.copy(
            journalOps = opsByPage,
            journalBytes = journal.wholeBytes.toLong(),
            journalWasTruncated = note.journalWasTruncated || journal.truncatedAtByte != null,
        )
    }

    /**
     * One page's whole log: what the bundle holds, plus what the journal added.
     *
     * Two reads, both scoped to this page. Called when a page comes on screen and
     * discarded when it goes away — nothing here keeps a reference, so a scrolled-past
     * page is collectable (ROADMAP M2: *"memory doesn't grow linearly with page
     * count"*).
     */
    public suspend fun loadPage(note: OpenNote, pageId: PageId): LoadedPage {
        val stored = note.reader?.page(pageId)
        val journalOps = note.journalOps[pageId].orEmpty()
        return LoadedPage(
            pageId = pageId,
            size = stored?.size ?: PageSize.Letter,
            background = stored?.background ?: Background.Blank,
            ops = stored?.ops.orEmpty() + journalOps,
        )
    }

    // -----------------------------------------------------------------------
    // Autosave
    // -----------------------------------------------------------------------

    /**
     * Append a flush to the journal. **This is autosave** (SPEC §5.1).
     *
     * One append, one record, whatever the flush contains. Nothing else on disk is
     * touched: not the bundle, not the PDF.
     *
     * @param pages which page each op belongs to. A map rather than a field on the op
     *   because a page is a property of where an op is *stored*, not of the op — the
     *   same op vocabulary is used inside a page file, where the page is implied.
     * @param document the document's metadata when page order, title or tombstones
     *   changed in this flush. Absolute and tiny, so a crash loses no page management.
     * @return the note's new `local_rev` (SPEC §7.6 — bumped on every committed change).
     */
    public suspend fun appendOps(
        note: OpenNote,
        ops: List<PageOp>,
        pages: Map<PageOp, PageId>,
        document: Document? = null,
    ): Long {
        if (ops.isEmpty() && document == null) return note.document.localRev
        val revision = note.document.localRev + 1
        val record = Journal.record(revision, ops, pages, document?.let(::documentRecord))
        fs.appendBytes(NoteLayout.journal(note.document.id), record)
        return revision
    }

    // -----------------------------------------------------------------------
    // Materialize
    // -----------------------------------------------------------------------

    /**
     * Fold the journal into the bundle and reset the journal.
     *
     * Order matters and is not negotiable: the bundle is written and renamed into place
     * *first*, and only then is the journal reset. A crash between the two replays a
     * journal whose ops are already in the bundle, which is harmless — the fold is
     * idempotent over absolute registers. A crash the other way round would lose them.
     *
     * @param pages every page's whole log, as it should appear in the new bundle.
     * @param extraCarried bundle entries from somewhere other than this note's own
     *   bundle — in practice a peer's, after a merge (M6).
     *
     *   ⚠️ **Without this, the first materialize after a merge silently deletes a peer's
     *   data.** This method used to take `carried` from the *local* reader alone, so any
     *   entry that existed only in the remote bundle — a peer's recognised text
     *   (`text/<pageId>.json`, M7) or anything a newer build added — was dropped on the
     *   floor. The lost entries are by definition the ones this build cannot render, so
     *   nothing would ever have looked wrong (CLAUDE.md rule 1, SPEC §5.8).
     *
     *   Local wins a name clash: this note's own copy of an entry is the one it can
     *   account for. ⚠️ **That rule is symmetric, so for a clashing name it does not converge** —
     *   each device keeps its own entry, neither is told, and there is no clock to order the two.
     *   It is a recorded concern rather than a live bug only because nothing writes a deterministic
     *   entry name yet: SPEC §5.5's recognised text is `text/<pageId>.json`, named per *page*, so
     *   once M7 writes it every device produces the same name and a clash is the normal case.
     *   **Ordering carried entries is M7's prerequisite, not its cleanup** — `docs/M6-SYNC.md`.
     *
     *   ⭐ **Entries under `assets/` are taken out of this map and unioned instead** — see below.
     *   Passing them here is not a mistake; it is how [BundleReader.carried] delivers them, and
     *   it is what lets a merge report reach this method unchanged.
     * @param extraCarriedManifest the same, for unrecognised manifest members.
     */
    public suspend fun materialize(
        note: OpenNote,
        pages: Map<PageId, PageContents>,
        extraCarried: Map<String, ByteArray> = emptyMap(),
        extraCarriedManifest: Map<String, JsonElement> = emptyMap(),
    ): OpenNote {
        val id = note.document.id
        // Page ops only. The title clock is folded in by [BundleContents], for every writer at
        // once — see its `highestClockCounter`. Doing it here instead is what M6 got wrong.
        val highest = pages.values.flatMap { it.ops }.maxOfOrNull { it.clock.counter }
            ?: note.highestClockCounter
        val document = note.document.copy(modifiedEpochMillis = clock.nowEpochMillis())
        // ⭐ **SPEC §5.3 — `assets/` is a union by name, and it is the one family where "the peer
        // has an entry we do not" is the normal case rather than a conflict** (M8c).
        //
        // The carried rule above is *local-wins*, and local-wins is exactly wrong for a family
        // both devices write: a name only the peer has is a picture only the peer can draw, and
        // keeping the local map alone deletes it. The union is safe — not merely conservative —
        // because an asset name embeds a UUIDv7 ([Bundle.imageAsset]), so **the same name on two
        // devices is the same bytes**. There is therefore nothing to resolve, no clock to consult
        // and no tiebreak to get wrong, and the two devices converge on the identical set in one
        // step without exchanging anything further.
        //
        // ⚠️ The bytes of a clashing name are deliberately **not compared**. A comparison could
        // only ever answer "equal", at the cost of reading every image on both sides of every
        // merge; and if it somehow answered "not equal" the honest response would still be to
        // keep one, because the *object* naming it cannot tell them apart.
        //
        // ⚠️ And the local half is not optional. Assets left `carriedNames` in this same change,
        // so without `reader.assets()` the very first materialize after M8c would delete every
        // image the note already has — invisibly, as a blank rectangle.
        val everything = extraCarried + (note.reader?.carried() ?: emptyMap())
        val contents = BundleContents(
            document = document,
            pages = pages,
            highestClockCounter = maxOf(highest, note.highestClockCounter),
            carried = everything.filterKeys { !Bundle.isAsset(it) },
            carriedManifest = extraCarriedManifest + (note.reader?.carriedManifest ?: emptyMap()),
            assets = everything.filterKeys { Bundle.isAsset(it) },
        )

        fs.writeBytesAtomically(NoteLayout.bundle(id), BundleWriter.write(contents))
        fs.writeBytesAtomically(NoteLayout.journal(id), Journal.header())

        val reader = BundleReader.open(FileByteSource(fs, NoteLayout.bundle(id)))
        return note.copy(
            document = document,
            journalOps = emptyMap(),
            highestClockCounter = contents.highestClockCounter,
            journalBytes = Journal.HEADER_SIZE.toLong(),
            journalWasTruncated = false,
            reader = reader,
        )
    }

    /**
     * SPEC §5.6 Normal mode — install a bundle lifted out of a published PDF.
     *
     * The bundle *is* the note, so this is a write and a fresh journal header and
     * nothing else. A journal is deliberately not preserved: everything a note
     * knows is in the bundle it was materialized with, and replaying a journal on
     * top would apply ops that are already folded into it.
     *
     * @return the note, opened from what was written, or null when [bundle] is not
     *   a readable bundle after all — a state the caller has to be able to see,
     *   because it is the difference between Normal and Recovered (SPEC §5.6).
     */
    public suspend fun installBundle(id: DocumentId, bundle: ByteArray): OpenNote? {
        if (BundleReader.open(MemoryByteSource(bundle)) == null) return null
        fs.createDirectories(NoteLayout.noteDir(id))
        fs.writeBytesAtomically(NoteLayout.bundle(id), bundle)
        fs.writeBytesAtomically(NoteLayout.journal(id), Journal.header())
        return open(id)
    }

    /**
     * SPEC §5.6 — install a recovered note, **replacing** whatever was there.
     *
     * The order is the whole safety argument and is not negotiable:
     *
     * 1. **Trash the pre-recovery version first** (SPEC §5.6 point 3). Recovery is
     *    lossy by definition — pressure, op history, undo — so the thing it replaces
     *    has to still exist afterwards. This is CLAUDE.md rule 1 at its most literal:
     *    the only operation in the app that deliberately supersedes a whole note.
     * 2. Write the new bundle, then reset the journal. A crash between them replays
     *    a journal whose ops are already in the bundle, which is harmless; the other
     *    order would lose them.
     *
     * There is deliberately no merge parameter. A recovered log **supersedes** the
     * previous one and the type offers no way to say otherwise — see [RecoveredImport].
     *
     * @return the note, reopened from what was just written.
     */
    public suspend fun replaceWithRecovered(trash: NoteTrash, recovered: RecoveredNote): OpenNote {
        val id = recovered.contents.document.id
        if (fs.exists(NoteLayout.noteDir(id))) trash.trash(id)

        fs.createDirectories(NoteLayout.noteDir(id))
        fs.writeBytesAtomically(NoteLayout.bundle(id), BundleWriter.write(recovered.contents))
        fs.writeBytesAtomically(NoteLayout.journal(id), Journal.header())
        // The local undo stack described ops that no longer exist. Keeping it would
        // let an undo write back a register for an object the recovered log never
        // introduced (SPEC §4.6 — undo is local, per device, and this device's
        // history did not survive).
        if (fs.exists(NoteLayout.undo(id))) fs.delete(NoteLayout.undo(id))
        discardSnapshot(id)

        return checkNotNull(open(id)) { "recovered note ${id.value} did not reopen" }
    }

    /**
     * SPEC §5.1's invariant, as an assertion: the published file is never more than one
     * editing session stale.
     *
     * A journal that still has content when a note closes means materialization did not
     * run, which is the state that would make `document.pdf` two sessions old.
     */
    public suspend fun assertNotStale(id: DocumentId) {
        val journalSize = fs.size(NoteLayout.journal(id))
        check(journalSize <= Journal.HEADER_SIZE) {
            "note ${id.value} was closed with $journalSize bytes of unmaterialized journal — " +
                "document.pdf would be more than one session stale (SPEC §5.1)"
        }
    }

    // -----------------------------------------------------------------------
    // Compaction snapshot (SPEC §4.7)
    // -----------------------------------------------------------------------

    /**
     * Write a page's folded state as a cache.
     *
     * The snapshot is stored as a bundle whose single page holds one `AddObject` per
     * live object — i.e. the fold re-expressed as the shortest log that produces it.
     * Reusing the bundle format means one serialiser, one set of format tests, and a
     * snapshot that is readable by anything that can read a note.
     *
     * **One file per page since M6.** This used to write every page of a note to one
     * path, so on a multi-page note each page silently overwrote the last and only the
     * final page had a readable snapshot. See [NoteLayout.SNAPSHOT_DIR].
     */
    public suspend fun writeSnapshot(id: DocumentId, page: FoldedPage, deviceId: String) {
        val clockValue = page.throughClock ?: return
        val ops = ArrayList<PageOp>(page.objects.size)
        page.objects.mapTo(ops) { obj ->
            PageOp.AddObject(opId = ids.newOpId(), clock = clockValue, obj = obj)
        }
        // ⚠️ **`AddObject` alone is not the whole fold, and has not been since M8b.** Every other
        // register is baked onto the payload by `PageFolder.materialise`; the `SetTextContent` one
        // is baked on only for a text box, and it is where a shape's fill and a connector's
        // attachment live (`ShapeRecord`).
        //
        // The bundle keeps the raw log either way (SPEC §4.7 — *"the snapshot is a cache, not the
        // truth"*), so a snapshot that dropped this would not lose data: `PageLog` re-absorbs the
        // real `SetTextContent` from the log and the record comes back. Writing it anyway makes
        // the snapshot a faithful picture rather than one that happens to be repaired downstream,
        // which is the difference between a cache and a cache with a hole in it.
        for (obj in page.objects) {
            val text = page.textOf(obj.id) ?: continue
            ops += PageOp.SetTextContent(opId = ids.newOpId(), clock = clockValue, target = obj.id, text = text)
        }
        // The format register (SPEC §4.9.2), M8c — same argument as the text register above, and
        // the same reason it is a second loop: an object with a font and no text must not be
        // skipped by a `continue` meant for the other register.
        for (obj in page.objects) {
            val format = page.formatOf(obj.id) ?: continue
            ops += PageOp.SetFormat(opId = ids.newOpId(), clock = clockValue, target = obj.id, record = format)
        }
        val document = Document(
            id = id,
            title = "",
            pages = listOf(PageRef(page.pageId, FractionalKey.Initial)),
            deviceId = deviceId,
        )
        val contents = BundleContents(
            document = document,
            pages = mapOf(page.pageId to PageContents(page.size, page.background, ops)),
            highestClockCounter = clockValue.counter,
        )
        fs.createDirectories(NoteLayout.snapshotDir(id))
        fs.writeBytesAtomically(NoteLayout.snapshot(id, page.pageId), BundleWriter.write(contents))
    }

    public suspend fun readSnapshot(id: DocumentId, pageId: PageId): FoldedPage? {
        val path = NoteLayout.snapshot(id, pageId)
        if (!fs.exists(path)) return null
        val reader = BundleReader.open(FileByteSource(fs, path)) ?: return null
        val page = reader.page(pageId) ?: return null
        return foldPage(pageId, page.ops, defaultSize = page.size)
    }

    /**
     * SPEC §4.7 — *"if an op older than the snapshot ever arrives, discard the snapshot"*.
     *
     * Discards **every** page's snapshot, not just the page the late op landed on. That is
     * deliberate and it is the conservative direction: a snapshot is a cache, throwing one
     * away costs a re-fold, and an op that arrived out of order is evidence that this
     * device's picture of what a peer has seen was wrong — which is a statement about the
     * note, not about one page of it.
     */
    public suspend fun discardSnapshot(id: DocumentId) {
        val dir = NoteLayout.snapshotDir(id)
        if (fs.exists(dir)) fs.delete(dir)
    }

    /** Drop one page's snapshot, leaving the rest. Used when only that page re-folded. */
    public suspend fun discardSnapshot(id: DocumentId, pageId: PageId) {
        val path = NoteLayout.snapshot(id, pageId)
        if (fs.exists(path)) fs.delete(path)
    }

    // -----------------------------------------------------------------------
    // Undo stacks — local, per device (SPEC §4.6)
    // -----------------------------------------------------------------------

    public suspend fun saveUndo(id: DocumentId, undo: List<UndoEntry>, redo: List<UndoEntry>) {
        val geometry = GeometryWriter()
        val json = buildJsonObject {
            put(U_UNDO, encodeEntries(undo, geometry))
            put(U_REDO, encodeEntries(redo, geometry))
        }
        // The stacks reference geometry too — an undone stroke's AddObject carries its
        // blob — so the same offset scheme applies, with the blob appended after the
        // JSON in one file.
        val jsonBytes = OpCodec.json.encodeToString(JsonObject.serializer(), json).encodeToByteArray()
        val blob = geometry.toByteArray()
        val out = ByteBuffer(HEADER_BYTES + jsonBytes.size + blob.size).apply {
            u32(jsonBytes.size.toLong())
            bytes(jsonBytes)
            bytes(blob)
        }
        fs.writeBytesAtomically(NoteLayout.undo(id), out.toByteArray())
    }

    /**
     * Remember which page [id] was left on, so reopening it lands where the user was.
     *
     * Local and per-device (see [NoteLayout.VIEW_JSON]). Written on every page change, which
     * is a few hundred bytes at human speed and nowhere near the ink path.
     *
     * **Not fatal.** A note whose view state cannot be written still opens, on page one —
     * so this deliberately does not participate in the save's success or failure.
     */
    public suspend fun saveViewState(id: DocumentId, pageId: PageId) {
        val json = buildJsonObject { put(V_PAGE, JsonPrimitive(pageId.value)) }
        fs.writeBytesAtomically(
            NoteLayout.viewState(id),
            OpCodec.json.encodeToString(JsonObject.serializer(), json).encodeToByteArray(),
        )
    }

    /**
     * @return the page [id] was last left on, or null when there is no record of one — a note
     *   never opened on this device, or one whose pages have changed underneath it. **The
     *   caller must check the page still exists**: it may have been deleted here, or deleted
     *   on the other tablet and merged in since.
     */
    public suspend fun loadViewState(id: DocumentId): PageId? {
        val path = NoteLayout.viewState(id)
        if (!fs.exists(path)) return null
        // A truncated or hand-mangled view.json is a note that opens on page one, never a
        // note that fails to open.
        return runCatching {
            OpCodec.json.parseToJsonElement(fs.readBytes(path).decodeToString())
                .jsonObject[V_PAGE]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }?.let(::PageId)
        }.getOrNull()
    }

    public suspend fun loadUndo(id: DocumentId): Pair<List<UndoEntry>, List<UndoEntry>> {
        val path = NoteLayout.undo(id)
        if (!fs.exists(path)) return emptyList<UndoEntry>() to emptyList()
        val bytes = fs.readBytes(path)
        if (bytes.size < HEADER_BYTES) return emptyList<UndoEntry>() to emptyList()
        val jsonLength = bytes.readU32(0).toInt()
        if (HEADER_BYTES + jsonLength > bytes.size) return emptyList<UndoEntry>() to emptyList()
        val json = OpCodec.json
            .parseToJsonElement(bytes.decodeToString(HEADER_BYTES, HEADER_BYTES + jsonLength))
            .jsonObject
        val geometry = GeometryReader(bytes.copyOfRange(HEADER_BYTES + jsonLength, bytes.size))
        return decodeEntries(json[U_UNDO], geometry) to decodeEntries(json[U_REDO], geometry)
    }

    private fun encodeEntries(entries: List<UndoEntry>, geometry: GeometryWriter) = buildJsonArray {
        for (entry in entries) {
            add(
                buildJsonObject {
                    put(U_PAGE, JsonPrimitive(entry.pageId.value))
                    put(U_FORWARD, buildJsonArray { for (op in entry.forward) add(OpCodec.encode(op, geometry)) })
                    put(U_INVERSE, buildJsonArray { for (op in entry.inverse) add(OpCodec.encode(op, geometry)) })
                },
            )
        }
    }

    /**
     * ⚠️ **An entry with no page is dropped, deliberately.**
     *
     * `undo.json` written before [UndoEntry] carried a [PageId] does not say which page a gesture
     * happened on, and the only ways to proceed are to guess a page or to drop the entry. Guessing
     * is the bug this field exists to remove — an inverse applied to the wrong page repapers it or
     * leaves a tombstone for an object that was never there, journaled and uploaded, with no error.
     *
     * Dropping costs the user the *ability to undo* gestures made before the upgrade, once. It
     * costs no data: every forward op is already in the log and in the bundle, so the drawing is
     * untouched — this is the same class of loss as `view.json` forgetting which page you were on.
     * A malformed page id is treated the same way rather than trusted.
     */
    private fun decodeEntries(element: JsonElement?, geometry: GeometryReader) =
        element?.jsonArray.orEmpty().mapNotNull { entry ->
            val obj = entry.jsonObject
            val page = obj[U_PAGE]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            UndoEntry(
                pageId = PageId(page),
                forward = obj[U_FORWARD]?.jsonArray.orEmpty().map { OpCodec.decode(it.jsonObject, geometry).op },
                inverse = obj[U_INVERSE]?.jsonArray.orEmpty().map { OpCodec.decode(it.jsonObject, geometry).op },
            )
        }

    // -----------------------------------------------------------------------
    // Document metadata in the journal
    // -----------------------------------------------------------------------

    private fun documentRecord(document: Document): JsonObject = buildJsonObject {
        put(Bundle.F_TITLE, JsonPrimitive(document.title))
        put(Bundle.F_LOCAL_REV, JsonPrimitive(document.localRev))
        // A rename's clock has to reach the journal, not just the bundle. The journal is what
        // survives a crash between an autosave and the next materialization (SPEC §5.1), and a
        // title recovered from it without its clock would be a rename that cannot win — or
        // lose — a merge (SPEC §7.7 layer 2).
        document.titleClock?.let { clock ->
            put(Bundle.F_TITLE_CLOCK_COUNTER, JsonPrimitive(clock.counter))
            put(Bundle.F_TITLE_CLOCK_DEVICE, JsonPrimitive(clock.deviceId))
        }
        put(
            Bundle.F_PAGES,
            buildJsonArray {
                for (ref in document.pages) {
                    add(
                        buildJsonObject {
                            put(Bundle.F_PAGE_ID, JsonPrimitive(ref.id.value))
                            put(Bundle.F_SORT_KEY, JsonPrimitive(ref.sortKey.value))
                            if (ref.deleted) {
                                put(Bundle.F_DELETED, JsonPrimitive(true))
                                ref.deletedEpochMillis?.let { put(Bundle.F_DELETED_AT, JsonPrimitive(it)) }
                            }
                        },
                    )
                }
            },
        )
    }

    private fun applyDocumentRecord(base: Document, record: JsonObject): Document {
        val pages = record[Bundle.F_PAGES]?.jsonArray.orEmpty().map { element ->
            val page = element.jsonObject
            PageRef(
                id = PageId(page.getValue(Bundle.F_PAGE_ID).jsonPrimitive.content),
                sortKey = FractionalKey(page.getValue(Bundle.F_SORT_KEY).jsonPrimitive.content),
                deleted = page[Bundle.F_DELETED]?.jsonPrimitive?.content == "true",
                deletedEpochMillis = page[Bundle.F_DELETED_AT]?.jsonPrimitive?.long,
            )
        }
        val journalClock = record[Bundle.F_TITLE_CLOCK_COUNTER]?.jsonPrimitive?.long?.let { counter ->
            record[Bundle.F_TITLE_CLOCK_DEVICE]?.jsonPrimitive?.content
                ?.takeIf { it.isNotEmpty() }
                ?.let { device -> LamportClock(counter, device) }
        }
        val journalTitle = record[Bundle.F_TITLE]?.jsonPrimitive?.content
        // The journal is newer than the manifest by construction — the manifest was written at
        // the last materialization and the journal after it — so a journal record's title wins
        // outright here. This is a note's own history replaying onto its own bundle, which is a
        // different question from resolving two devices' titles (Document.resolveTitle).
        return base.copy(
            title = journalTitle ?: base.title,
            titleClock = journalClock ?: base.titleClock,
            // Union, never replacement: a page the journal does not mention is one the
            // bundle knows about and this record simply did not change (SPEC §4.3).
            pages = if (pages.isEmpty()) base.pages else PageOrder.mergePageOrder(pages, base.pages),
            localRev = maxOf(base.localRev, record[Bundle.F_LOCAL_REV]?.jsonPrimitive?.long ?: 0L),
        )
    }

    private companion object {
        const val HEADER_BYTES = 4
        const val U_UNDO = "undo"
        const val U_REDO = "redo"

        /** `view.json`'s only key — the page this device was last on. */
        const val V_PAGE = "pageId"
        const val U_PAGE = "p"
        const val U_FORWARD = "f"
        const val U_INVERSE = "i"
    }
}

/**
 * An open note: its metadata, and the handles needed to read pages on demand.
 *
 * Deliberately not a page cache. Whoever draws decides what to keep in memory; this
 * only knows how to fetch.
 *
 * @property journalWasTruncated true when the journal ended in a torn record — a crash
 *   mid-stroke. The session heals it on the next materialization and can tell the user
 *   what happened, which is more honest than pretending nothing did.
 */
public data class OpenNote(
    val document: Document,
    val journalOps: Map<PageId, List<PageOp>>,
    val highestClockCounter: Long,
    val journalBytes: Long,
    val journalWasTruncated: Boolean,
    val reader: BundleReader?,
)

/** One page's log, ready to fold. */
public data class LoadedPage(val pageId: PageId, val size: PageSize, val background: Background, val ops: List<PageOp>)

/**
 * A page a note is being created with.
 *
 * [size] is already **CropBox with `/Rotate` applied** when the page came from a
 * PDF (SPEC §4.4 rule 3) — nothing downstream re-derives it, because the one place
 * that conversion happens is the import.
 */
public data class NewPage(val size: PageSize, val background: Background)

private fun JsonObject.getValue(key: String) = requireNotNull(this[key]) { "record has no '$key'" }
