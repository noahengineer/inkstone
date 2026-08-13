package dev.inkstone.app.sync.matrix

import dev.inkstone.core.model.Document
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.EraseCapsule
import dev.inkstone.core.model.ErasedArea
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.SelectedObject
import dev.inkstone.core.model.SelectionEdit
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.selected
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.storage.renameNote
import kotlin.random.Random
import kotlin.test.assertNotNull

/*
 * The gestures a row of SPEC §13.2 makes, as one-liners.
 *
 * Every op here is stamped with `session.nextClock()` and `session.nextOpId()` — this device's
 * real generator, seeded from the note, carrying this install's real `deviceId`. That matters
 * more than it looks: the whole of row 3 is the `deviceId` tiebreak in `LamportClock.compareTo`,
 * and a helper that invented a device string would test the fixture instead.
 *
 * Block comment, not KDoc: a file-level KDoc attaches to no declaration.
 */

/** A stroke on the note's first page. Returns its [ObjectId] so a row can aim ops at it. */
internal suspend fun Device.drawStroke(noteId: DocumentId): ObjectId = edit(noteId) { session, page ->
    val id = ids.newObjectId()
    session.apply(page, listOf(addObject(session, id)))
    id
}

/** A stroke on a named page, for the rows whose subject is which page an op landed on. */
internal suspend fun Device.drawStrokeOn(noteId: DocumentId, pageId: PageId): ObjectId = edit(noteId) { session, _ ->
    val id = ids.newObjectId()
    session.apply(pageId, listOf(addObject(session, id)))
    id
}

/**
 * CLAUDE.md rule 8 — an **absolute** transform, as an LWW register.
 *
 * ⭐ **Through `SelectionEdit.transform`, which is what the app writes** (M8a). This used to
 * build its own `SetTransform` from a matrix the helper chose, and CLAUDE.md is blunt about what
 * that is worth: *"distrust a fixture that is more generous than production"*. A row that builds
 * its own op tests the row. `SelectionEdit` is the one production decision-maker for a selection
 * edit — `SelectionController` calls exactly this function with exactly these arguments — so
 * routing through it means rows 3 and 7 fail if the app's composition order, its absoluteness or
 * its degenerate-gesture refusal ever changes.
 *
 * What is still *not* production here is the gesture-to-matrix step and the ink surface around
 * it: there is no `MotionEvent` in a JVM test. That half is `SelectionHandleTest` and the
 * emulator walk in `docs/M8-SELECTION.md` §5.
 */
internal suspend fun Device.transform(noteId: DocumentId, target: ObjectId, gesture: Matrix3): Matrix3 =
    edit(noteId) { session, page ->
        val ops = SelectionEdit.transform(
            objects = selectedIn(session.page(page), target),
            gesture = gesture,
            nextOpId = session::nextOpId,
            nextClock = session::nextClock,
        )
        session.apply(page, ops)
        (ops.single() as PageOp.SetTransform).absolute
    }

/**
 * A transform stamped at an **explicit** counter, so two devices can be made to collide exactly.
 *
 * Row 3's premise is *equal* Lamport counters, which is the case `Register.write`'s tiebreak
 * exists for. Letting both devices call `nextClock()` usually produces equal counters anyway
 * (they are folding the same log), but "usually" is not a test — this makes it certain.
 *
 * The clock generator is a lambda for exactly this reason: `SelectionEdit` never mints one
 * itself, so a test can pin it without a second code path.
 */
internal suspend fun Device.transformAtCounter(
    noteId: DocumentId,
    target: ObjectId,
    counter: Long,
    gesture: Matrix3,
): Matrix3 = edit(noteId) { session, page ->
    // Read before the lambda: `deviceId()` suspends, and `SelectionEdit`'s generator is a plain
    // `() -> LamportClock` — deliberately, since it is called from the UI thread in production.
    val device = deviceId()
    val ops = SelectionEdit.transform(
        objects = selectedIn(session.page(page), target),
        gesture = gesture,
        nextOpId = session::nextOpId,
        nextClock = { LamportClock(counter, device) },
    )
    session.apply(page, ops)
    (ops.single() as PageOp.SetTransform).absolute
}

/** The object as a selection edit sees it, read out of the fold the way the app reads it. */
private fun selectedIn(page: FoldedPage, target: ObjectId): List<SelectedObject> =
    listOf(assertNotNull(page.find(target), "no object $target on ${page.pageId}").selected)

/** A tombstone. The `AddObject` stays in the log (SPEC §4.2), which is why row 2 can be asked. */
internal suspend fun Device.deleteObjectAt(noteId: DocumentId, target: ObjectId, counter: Long) {
    edit(noteId) { session, page ->
        session.apply(page, listOf(PageOp.DeleteObject(session.nextOpId(), LamportClock(counter, deviceId()), target)))
    }
}

/**
 * An independent register from the transform — row 4's whole point.
 *
 * Through `SelectionEdit.restyle`, for the reason [transform] gives. Note the per-object
 * `styleOf` lambda: the production caller composes each object's *own* style with the one field
 * the user changed, and a helper that passed one shared style would not exercise that.
 */
internal suspend fun Device.setStyle(noteId: DocumentId, target: ObjectId, colorArgb: Int): ObjectStyle =
    edit(noteId) { session, page ->
        val style = ObjectStyle(colorArgb = colorArgb, widthPt = STYLE_WIDTH_PT)
        session.apply(
            page,
            SelectionEdit.restyle(
                objects = selectedIn(session.page(page), target),
                styleOf = { style },
                nextOpId = session::nextOpId,
                nextClock = session::nextClock,
            ),
        )
        style
    }

/**
 * SPEC §3.5 — a partial erase, as the **area the eraser swept**, absolutely.
 *
 * The stroke is not touched and no fragment is created. Row 7 is the assertion that a concurrent
 * transform of the same stroke therefore cannot duplicate it.
 *
 * ⚠️ **This one op is still hand-built, and it is the one place in this file where the fixture
 * is more generous than production** — say so rather than let a green row imply otherwise. The
 * real capsules come from `StrokeStore.eraseAlong`, which needs `androidx.ink`'s native coverage
 * test to decide *which* strokes an eraser touched and where, and there is no ink in a JVM test.
 * So what row 7 proves is that an absolute region and a concurrent transform **converge** — the
 * merge question — and what it does not prove is that an eraser produces the right region, or
 * that erasing a *moved* stroke maps the sweep into the object's own space. Both of those are
 * `SelectionEraseTest` in `:android:ink`'s instrumented suite, on a device, with real ink.
 *
 * The capsule's coordinates are in the object's own space (M8a). For this fixture's stroke that
 * is the same as page space, because its transform starts as the identity — which is the whole
 * reason the distinction went unnoticed until something could move.
 */
internal suspend fun Device.erase(noteId: DocumentId, target: ObjectId): ErasedArea = edit(noteId) { session, page ->
    val area = ErasedArea(listOf(EraseCapsule(ERASE_FROM, ERASE_FROM, ERASE_TO, ERASE_FROM, ERASE_RADIUS)))
    session.apply(page, listOf(PageOp.SetErasedRegion(session.nextOpId(), session.nextClock(), target, area)))
    area
}

/** A new page at the end. Row 5's gesture, on both devices at once. */
internal suspend fun Device.addPage(noteId: DocumentId): PageId = edit(noteId) { session, _ -> session.addPage() }

/**
 * SPEC §7.7 layer 2 — a rename, through the **production** writer.
 *
 * ⚠️ `store.renameNote`, not a hand-rolled `session.rename` inside [Device.edit]. That is what this
 * helper used to do, and it was the fixture being more generous than reality: the production rename
 * renamed a library row and left `Document.title` and `titleClock` untouched, so a matrix row written
 * against the old helper would have passed against a layer 2 with **no writer at all**. The rest is
 * `LibraryRepository.noteRenamed` — the index row, the remote MOVE, and the upload the changed bundle
 * now owes. `NoteSessionHost.renameNote` is the one link above this that a JVM test cannot reach.
 */
internal suspend fun Device.rename(noteId: DocumentId, title: String) {
    val document = assertNotNull(store.renameNote(noteId, title, deviceId(), ids, clock), "$name has no $noteId")
    val now = clock.nowEpochMillis()
    index.renameNote(noteId, title, now)
    // The revision has to move or `SyncEngine.noteChanged` reads `localRev <= lastSyncedRev` and the
    // rename is a change no upload is ever owed for.
    index.recordSave(noteId, document.localRev, document.livePages.size, now)
    engine.notePathChanged(noteId, title)
    engine.noteChanged(noteId)
}

/** Reorder the live pages by moving [pageId] to [toIndex]. Row 6's gesture. */
internal suspend fun Device.movePage(noteId: DocumentId, pageId: PageId, toIndex: Int) {
    edit(noteId) { session, _ -> session.movePage(pageId, toIndex) }
}

/**
 * Every op in a page's log, bundle and journal together.
 *
 * Rows 2 and 3 are about the log rather than the fold: rule 1 says a losing register keeps its op
 * forever, and row 3's tiebreak claim is *"no two ops share a `LamportClock`"*, which is not a
 * question a `FoldedPage` can answer.
 */
internal suspend fun Device.opsOn(noteId: DocumentId, pageId: PageId): List<PageOp> {
    val note = assertNotNull(store.open(noteId), "$name cannot open ${noteId.value}")
    return store.loadPage(note, pageId).ops
}

/** Where this device believes the note lives on the server. */
internal suspend fun Device.remotePath(noteId: DocumentId): String =
    assertNotNull(binding(noteId), "$name has no binding for ${noteId.value}").remotePath

/** The note's live page order, as `(sortKey, pageId)` — what row 5 and row 6 compare. */
internal suspend fun Device.pageOrder(noteId: DocumentId): List<Pair<String, String>> =
    assertNotNull(document(noteId), "$name has no ${noteId.value}")
        .livePages
        .map { it.sortKey.value to it.id.value }

internal suspend fun Device.title(noteId: DocumentId): String =
    assertNotNull(document(noteId), "$name has no ${noteId.value}").title

private fun addObject(session: dev.inkstone.core.storage.NoteSession, id: ObjectId): PageOp.AddObject =
    PageOp.AddObject(
        opId = session.nextOpId(),
        clock = session.nextClock(),
        // Random bytes, not a shared constant: the geometry blob is opaque to everything the
        // matrix asserts on (SPEC §5.4), and two strokes with identical bytes would let a merge
        // that silently deduplicated by content pass row 1.
        obj = StrokeObject(id = id, geometry = StrokeGeometry(Random.nextBytes(GEOMETRY_BYTES))),
    )

/**
 * ⭐ A note both devices hold, bound, uploaded, and fully settled.
 *
 * 1. A creates it, binds it and drains — one MKCOL and one `If-None-Match: *` PUT.
 * 2. B pulls and *adopts* it: `oc:id` unknown, marker present, `documentId` unknown, so
 *    `NoteStore.installBundle`, a library row, and a binding **at the version just downloaded**.
 *    Nothing is owed to the server afterwards — see `SyncEngine.bindPulled`, which exists because
 *    the first version of this helper produced a permanent 412 here instead.
 * 3. A pulls, so that its own cursor holds the current root ETag. Without it a row's first
 *    measured poll would be paying for the setup's traffic.
 *
 * Counts are reset afterwards, so a row asserting "one PROPFIND and one GET" is measuring itself.
 */
internal suspend fun TwoDevices.share(title: String = "Shared", pageCount: Int = 1): DocumentId {
    val id = a.createNote(title, pageCount)
    a.bind(id, title)
    a.drain()
    b.pull()
    a.pull()
    resetCounts()
    return id
}

/**
 * ⭐ M12 — a lecture recorded into [noteId], as far as the outbox (docs/M12-AUDIO.md).
 *
 * The two files and the row, then `SyncEngine.recordingCaptured`. **No drain**, which is what
 * makes it an offline recording: coalescing happens at enqueue time, in one transaction, before
 * a socket is opened.
 *
 * ⚠️ The row goes through the real [dev.inkstone.core.database.recording.RecordingIndex] — `start`
 * then `finish`, in that order — rather than being inserted by hand. That order is §9's orphan
 * recovery, and a fixture that wrote a finished row in one statement could not fail if the app
 * stopped writing the CAPTURING one. It is also the "build harnesses out of production objects"
 * rule: what this fixture cannot reach is the *recorder* — there is no microphone here — so the
 * bytes are stand-ins and the claim is about the queue and the wire, never about the audio.
 *
 * @param audioBytes how large to make the stand-in .opus. Distinct sizes per recording, so a test
 *   that mixed the two files up would fail rather than pass on identical content.
 * @return the recording id, which is also `outbox.subject` and the remote filename.
 */
internal suspend fun Device.recordLecture(noteId: DocumentId, label: String, audioBytes: Int): String {
    val recordingId = ids.newRecordingId()
    val startedAt = clock.nowEpochMillis()
    recordingIndex.start(recordingId, noteId, startedAt)

    // Deterministic, distinct, and not all-zero — a byte-equality assertion over two runs of
    // zeroes proves nothing about which file arrived where.
    val audio = ByteArray(audioBytes) { i -> (label.hashCode() + i).toByte() }
    fs.writeBytesAtomically(NoteLayout.recordingAudio(recordingId), audio)
    fs.appendBytes(
        NoteLayout.recordingTimeline(recordingId),
        "$label-object 1200\n$label-object-2 4800\n".encodeToByteArray(),
    )

    // Over §10's thirty-second floor, which is the app's decision and not the engine's — the
    // engine is never told about a recording that did not clear it.
    recordingIndex.finish(recordingId, durationMillis = RECORDED_MILLIS, audioBytes = audio.size.toLong())
    engine.recordingCaptured(noteId, recordingId)
    return recordingId
}

/** Both devices reconcile, then drain what the reconcile decided was owed. */
internal suspend fun TwoDevices.syncBothWays() {
    a.pull()
    a.drain()
    b.pull()
    b.drain()
    a.pull()
    a.drain()
    b.pull()
    b.drain()
}

internal val Document.liveIds: List<String> get() = livePages.map { it.id.value }

/** Well over §10's thirty-second floor, so the fixture never accidentally tests the floor. */
private const val RECORDED_MILLIS = 45L * 60 * 1000

private const val GEOMETRY_BYTES = 64
private const val STYLE_WIDTH_PT = 3f
private const val ERASE_FROM = 100f
private const val ERASE_TO = 140f
private const val ERASE_RADIUS = 6f
