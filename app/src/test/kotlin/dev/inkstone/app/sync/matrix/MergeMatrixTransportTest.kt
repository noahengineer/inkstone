package dev.inkstone.app.sync.matrix

import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.storage.NoteTrash
import dev.inkstone.core.sync.AttentionKind
import dev.inkstone.core.sync.OutboxOpType
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC §13.2 rows 11 to 16 — **what the wire does, and what the app must not conclude from it.**
 *
 * Every row here is about a fact only the transport can produce: an ETag that did not change when a
 * file moved, a file that vanished without this device deleting it, a fingerprint that moved
 * backwards, a validator that is weak. Four of them are also the four ways a healthy poll can be
 * made to conclude something destructive.
 */
class MergeMatrixTransportTest {

    /**
     * Row 11 — SPEC §7.4 rule 2: **same `oc:id` + same ETag + new href is a move, not a download.**
     *
     * The MOVE is made by a client neither device owns, which is what a rename in the Nextcloud web
     * UI is. If this were read as a change, every folder reorganisation would re-download the whole
     * library; if it were read by *path*, it would be read as a delete plus a create — and the
     * create would arrive with a `documentId` we already hold.
     */
    @Test
    fun `row 11 - a file renamed on the server repaths the binding and downloads nothing`() = matrixRow(
        row = "row 11 (move detection)",
        proven = "a repathed binding, zero GETs, one library row, and an untouched local revision",
        notProven = "that Nextcloud really preserves the ETag across a MOVE (it is documented to)",
    ) { devices ->
        val id = devices.share("Row11")
        val from = devices.a.remotePath(id)
        val to = from.substringBeforeLast('/') + "/Row11-renamed.pdf"
        val revBefore = assertNotNull(devices.a.document(id)).localRev

        devices.rawMove(from, to)
        devices.resetCounts()

        val pull = devices.a.pull()

        assertEquals(1, pull.moved, "the rename was not read as a move")
        assertEquals(0, pull.merged, "a move was merged")
        assertEquals(to, devices.a.remotePath(id), "the binding still points at the old path")
        devices.assertRequests("GET", 0, "a move cost a download")
        assertEquals(1, devices.a.index.allNotes().size, "the move produced a second library row")
        assertEquals(revBefore, assertNotNull(devices.a.document(id)).localRev, "a move rewrote the note")
    }

    /**
     * ⭐ Row 11b — **the same move, into a folder no walk root covers.** Row 11's blind spot.
     *
     * Row 11 renames the file *inside* the folder the walk was already going to list, so rule 2's
     * *"same `oc:id` + same ETag + a different href"* has a new href to compare. `RemoteWalk.rootsOf`
     * visits only the account's default folder, the parent of every binding and the pinned folders —
     * so a note dragged in the web UI into anything outside that set is listed **nowhere**, and its
     * old directory is then walked and settled without it. Step 3's fourth branch read that as a
     * remote deletion: reported from a real tablet, and reproduced here before the fix, the note went
     * to the local trash with a *"Deleted on the server"* row while its file sat untouched on the
     * server one folder away.
     *
     * The repair is to stop inferring the answer from what was listed and ask for it: one `SEARCH` on
     * `/remote.php/dav/` resolving the binding's fileid to its current href. So this row asserts the
     * economy as hard as the behaviour — **one** search, and it is the whole cost of the pass. If the
     * search moved in front of `isAbsentRemotely`'s guards it would become one per note in the
     * library on every poll that changed anything, and this assertion is what would catch that.
     *
     * The last two lines are the follow-on, and they need no code: walk roots are derived from the
     * bindings, so the pass *after* this one lists the destination and reconciles the file there
     * normally. Without that, a move plus an edit would leave the note repathed and never read again.
     */
    @Test
    fun `row 11b - a file moved outside every walk root follows the binding and downloads nothing`() = matrixRow(
        row = "row 11b (a move the walk cannot see)",
        proven = "a binding repathed from one SEARCH, zero GETs, nothing trashed, and an untouched note",
        notProven = "nothing extra — the MOVE, the SEARCH and the ETag that survived both are the server's",
    ) { devices ->
        val id = devices.share("Row11b")
        val from = devices.a.remotePath(id)
        val to = "${devices.makeOutsideFolder()}/Row11b-moved.pdf"
        val revBefore = assertNotNull(devices.a.document(id)).localRev
        val etagBefore = assertNotNull(assertNotNull(devices.a.binding(id)).lastSyncedEtag)

        devices.rawMove(from, to)
        devices.resetCounts()

        val pull = devices.a.pull()

        assertEquals(1, pull.moved, "the move was not detected: $pull")
        assertEquals(0, pull.trashed, "a note that moved was trashed as though the server had deleted it")
        assertEquals(to, devices.a.remotePath(id), "the binding did not follow the file out of the walked set")
        assertEquals(
            etagBefore,
            assertNotNull(devices.a.binding(id)).lastSyncedEtag,
            "a move changed the stored validator, so the next If-Match upload would 412",
        )
        devices.assertRequests("GET", 0, "a move cost a download")
        devices.assertRequests("SEARCH", 1, "one search per absent candidate — never one per file")
        assertTrue(devices.a.fs.exists(NoteLayout.noteDir(id)), "the note was moved out of notes/")
        assertTrue(devices.a.fs.list(NoteLayout.TRASH_DIR).isEmpty(), "a moved note was put in the local trash")
        assertEquals(1, devices.a.index.allNotes().size, "the move produced a second library row")
        assertEquals(revBefore, assertNotNull(devices.a.document(id)).localRev, "a move rewrote the note")
        assertTrue(devices.a.attention().isEmpty(), "the user was told about something that did not happen")

        assertEquals(0, devices.a.pull().trashed, "the pass after the move concluded the deletion instead")
        assertEquals(to, devices.a.remotePath(id), "the second pass moved the binding back")
    }

    /**
     * Row 12, first half — **a cancelled upload leaves the row exactly as it was.**
     *
     * WorkManager kills a job at ten minutes and the OS kills the process whenever it likes, so a
     * PUT being abandoned is ordinary. What must not happen is the row being consumed: no attempt
     * spent, no reschedule, and no half-written file on the server.
     *
     * The cancellation is injected at the materializer rather than mid-socket, because mid-socket is
     * a race and a racy test is worse than a narrower one. The mid-socket half needs a server that
     * can be made slow — an nginx `limit_rate` sidecar — and is named as such in the report.
     */
    @Test
    fun `row 12 - a cancelled upload consumes no attempt and leaves the remote file whole`() = matrixRow(
        row = "row 12 (cancellation)",
        proven = "an untouched outbox row and an unchanged remote file after a cancelled drain",
        notProven = "what a real server does with a PUT whose body stops arriving half way",
    ) { devices ->
        val id = devices.share("Row12")
        val path = devices.a.remotePath(id)
        val whole = assertNotNull(devices.rawBytes(path), "nothing was uploaded")
        devices.a.drawStroke(id)
        val before = assertNotNull(devices.a.outboxRows().firstOrNull { it.opType == OutboxOpType.UPLOAD })

        devices.a.clock.advance(THIRTY_ONE_SECONDS)
        devices.a.materializer = EditDuringUploadMaterializer(devices.a.publisher) {
            throw CancellationException("the sync worker was stopped")
        }
        assertFailsWith<CancellationException> { devices.a.drainer.drain() }
        devices.a.materializer = devices.a.publisher

        val after = assertNotNull(
            devices.a.outboxRows().firstOrNull { it.opType == OutboxOpType.UPLOAD },
            "the cancelled row was removed, so the edit has no route to the server",
        )
        assertEquals(before.attempts, after.attempts, "a cancellation consumed a backoff attempt")
        assertEquals(before.createdAt, after.createdAt, "the row lost its place in the queue")
        assertContentEquals(whole, devices.rawBytes(path), "the remote file changed during a cancelled upload")
    }

    /**
     * Row 12, second half — a 507 **parks** rather than retrying, and nothing is corrupted.
     *
     * Also the `OC-Checksum` claim: the in-process server verifies the header and answers 400 on a
     * mismatch, so the successful upload at the end is proof that the app computed and sent a
     * correct MD5 over the real published bytes (SPEC §7.5 — Nextcloud verifies it too).
     */
    @Test
    fun `row 12b - a quota refusal parks the row and the next drain uploads it intact`() = matrixRow(
        row = "row 12b (507 and OC-Checksum)",
        proven = "a parked row on 507 and a checksum-verified upload once space is back",
        notProven = "that a real Nextcloud answers 507 rather than 413 or a 500 for a full quota",
    ) { devices ->
        val id = devices.share("Row12b")
        val path = devices.a.remotePath(id)
        val whole = assertNotNull(devices.rawBytes(path))
        devices.a.drawStroke(id)
        devices.a.clock.advance(THIRTY_ONE_SECONDS)

        if (!devices.server.refuseWithQuotaExceeded(true)) {
            announceNeedsAdmin("row 12b", "a quota the test can fill", "the cancellation half above")
            return@matrixRow
        }
        val refused = devices.a.drainer.drain()
        assertEquals(1, refused.parked, "a 507 was not parked")
        assertEquals(0, refused.failed, "a 507 was put on a retry ladder")
        assertContentEquals(whole, devices.rawBytes(path), "a refused upload changed the remote file")
        assertEquals(NoteSyncState.ERROR, assertNotNull(devices.a.summary(id)).syncState)

        devices.server.refuseWithQuotaExceeded(false)
        // ⚠️ The park is a **day** long (SPEC §7.7 rule 3 — flagged and skipped, so everything else
        // keeps syncing), and unlike a 412 there is nothing that can un-park a quota failure early:
        // a reconcile does not make space. So the clock is what releases it, and asserting that is
        // asserting the park's real duration rather than wishing it shorter.
        devices.a.clock.advance(ONE_DAY + THIRTY_ONE_SECONDS)
        val retried = devices.a.drain()
        assertEquals(1, retried.uploaded, "the upload never resumed after the quota came back")
    }

    /**
     * ⭐ Row 13 — **the 412 cycle, and the termination argument.**
     *
     * `412 → park → reconcile → merge → the same row re-uploads`. Three separate things have to be
     * true for it to close, and each of them was missing at some point in M6:
     *
     * 1. the 412 must not be retried (SPEC §7.5 — *"never blind-retry"*), so it parks;
     * 2. it must **name the note**, so a reconcile of that note is scheduled rather than the app
     *    waiting fifteen minutes for the periodic pass (`DrainReport.conflicted`);
     * 3. the completed merge must **un-park** the row. `OutboxCoalescing` inherits
     *    `next_attempt_at` — correctly, because resetting it on every save is a hot retry loop —
     *    so without `SyncEngine.remoteAdvanced` clearing the park the note waits a day and the
     *    merge never reaches the server. Found by this row.
     *
     * And no conflicted copy: a 412 that a merge resolved is layer 1, not layer 3.
     */
    @Test
    fun `row 13 - a 412 parks then reconciles then re-uploads with no conflicted copy`() = matrixRow(
        row = "row 13 (412 resolution)",
        proven = "the whole 412 cycle closing in two round trips with both devices' work intact",
        notProven = "nothing extra — a real 412 is produced here by a real concurrent upload",
    ) { devices ->
        val id = devices.share("Row13")
        val page = devices.a.firstPage(id)
        val fromA = devices.a.drawStroke(id)
        val fromB = devices.b.drawStroke(id)

        // B wins the race, so A's queued If-Match is now stale.
        devices.b.drain()
        devices.a.clock.advance(THIRTY_ONE_SECONDS)

        val refused = devices.a.drainer.drain()
        assertEquals(1, refused.parked, "the stale If-Match did not produce a parked 412")
        assertEquals(0, refused.failed, "a 412 was put on a retry ladder")
        assertTrue(id in refused.conflicted, "the 412 did not name the note whose reconcile resolves it")
        assertEquals(NoteSyncState.CONFLICTED, assertNotNull(devices.a.summary(id)).syncState)

        // The pass the 412 asked for.
        assertEquals(1, devices.a.pull().merged, "the conflict pass did not merge")
        devices.a.clock.advance(THIRTY_ONE_SECONDS)
        assertEquals(1, devices.a.drain().uploaded, "the merged union never reached the server")

        assertEquals(NoteSyncState.SYNCED, assertNotNull(devices.a.summary(id)).syncState)
        assertEquals(1, devices.a.index.allNotes().size, "a 412 a merge resolved still produced a conflicted copy")
        assertEquals(1, devices.b.pull().merged, "B did not pick up the union")
        for (device in listOf(devices.a, devices.b)) {
            val objects = device.page(id, page).objects.map { it.id }
            assertTrue(fromA in objects, "${device.name} lost A's stroke through the 412 cycle")
            assertTrue(fromB in objects, "${device.name} lost B's stroke through the 412 cycle")
        }
    }

    /**
     * Row 13, second variant — a merge that **cannot** happen becomes a conflicted copy with a new
     * `DocumentId`.
     *
     * The impossible merge here is a file at this note's path whose embedded bundle names a
     * *different* note — which is SPEC §5.6's identity rule inverted, and the one layer-3 trigger
     * that can be produced without authoring a bundle from the future. (`TooNew` is covered over
     * hand-built schema-99 bundles in `:core:storage`'s `NoteMergeTest` and `:core:sync`'s
     * `ReconcilerFileTest`.)
     *
     * A new `DocumentId` is mandatory: the XMP marker carries `documentId`, so a copy that kept it
     * would be merged straight back into the note it exists because it could not merge with.
     */
    @Test
    fun `row 13b - an unmergeable file becomes a conflicted copy with a new document id`() = matrixRow(
        row = "row 13b (layer 3 keeps both)",
        proven = "a new local note, a new DocumentId, and the original untouched",
        notProven = "nothing extra",
    ) { devices ->
        val id = devices.share("Row13b")
        val page = devices.a.firstPage(id)
        val mine = devices.a.drawStroke(id)
        devices.a.drain()
        val path = devices.a.remotePath(id)
        val revBefore = assertNotNull(devices.a.document(id)).localRev

        // A different note's published bytes, put at this note's path. Materialized on B and never
        // uploaded, so the server gains no extra file for the walk to adopt.
        val stranger = devices.b.createNote("Stranger")
        devices.b.publisher.materialize(stranger)
        devices.rawWrite(path, devices.b.publishedBytes(stranger))

        val pull = devices.a.pull()

        assertEquals(1, pull.conflicted, "a file naming a different note was not treated as a conflict")
        assertEquals(revBefore, assertNotNull(devices.a.document(id)).localRev, "the local note was rewritten")
        assertTrue(devices.a.page(id, page).objects.any { it.id == mine }, "the local note lost its stroke")

        val entry = assertNotNull(
            devices.a.attention().firstOrNull { it.kindOrNull == AttentionKind.CONFLICTED_COPY },
            "no attention row offers the copy to the user",
        )
        val copyId = assertNotNull(entry.conflictedCopyNoteId, "the attention row does not name a copy to open")
        assertTrue(copyId != id, "the copy kept the original DocumentId, so the next pass merges them back together")
        assertNotNull(devices.a.document(copyId), "the copy is not a note that can be opened")
        assertTrue(
            assertNotNull(devices.a.summary(copyId)).title.contains("conflicted copy"),
            "the copy is not named so a user can tell what it is",
        )
    }

    /**
     * ⭐ Row 14 — **the remote file is gone, and this device did not delete it.**
     *
     * Three things must be true and the middle one is the destructive mistake:
     *
     * 1. the local note goes to the **trash**, never a hard delete (CLAUDE.md rule 1);
     * 2. **no remote DELETE is enqueued.** `SyncEngine.noteTrashed` would enqueue one, and
     *    `OutboxCoalescing` makes a DELETE *annihilate* the pending UPLOAD — which is correct when
     *    the user deletes a note and catastrophic here, because row 14's premise is a note with
     *    local unsynced edits;
     * 3. the note is restorable **with those edits intact**.
     */
    @Test
    fun `row 14 - a file deleted on the server trashes the note and never enqueues a delete`() = matrixRow(
        row = "row 14 (remote deletion)",
        proven = "local trash, no DELETE row, an attention entry, and a restore with the edits intact",
        notProven = "that Nextcloud's own trash still holds the file (it is documented to; DELETE is soft)",
    ) { devices ->
        val id = devices.share("Row14")
        val page = devices.a.firstPage(id)
        val unsynced = devices.a.drawStroke(id)
        val path = devices.a.remotePath(id)

        devices.rawDelete(path)
        val pull = devices.a.pull()

        assertEquals(1, pull.trashed, "the missing file was not read as a deletion")
        assertFalse(devices.a.fs.exists(NoteLayout.noteDir(id)), "the note is still in notes/")
        assertTrue(devices.a.fs.list(NoteLayout.TRASH_DIR).isNotEmpty(), "the note was hard-deleted")
        assertTrue(
            devices.a.outboxRows().none { it.opType == OutboxOpType.DELETE },
            "a remote DELETE was enqueued, which annihilates the pending UPLOAD",
        )
        assertTrue(
            devices.a.attention().any { it.kindOrNull == AttentionKind.REMOTE_DELETED },
            "the user is not told their note left the server",
        )

        assertTrue(NoteTrash(devices.a.fs, devices.a.clock).restore(id), "the note could not be restored")
        devices.a.index.restoreNote(id)
        assertTrue(
            devices.a.page(id, page).objects.any { it.id == unsynced },
            "the restored note lost the edit that was never uploaded",
        )
    }

    /**
     * Row 15 — `nc:data-fingerprint` moved: **invalidate the cache, walk everything, download nothing.**
     *
     * A fingerprint change means an admin restored a backup, so every cached ETag now describes a
     * file that moved *backwards* — matching one there proves the opposite of what a match usually
     * proves. What must **not** follow is a mass re-download (the content is the same, and the
     * per-file ETag comparison still says so) or a mass trashing (a re-walked tree is not a
     * deletion).
     */
    @Test
    fun `row 15 - a data fingerprint change re-walks everything and downloads nothing`() = matrixRow(
        row = "row 15 (data-fingerprint)",
        proven = "cache invalidation, a full unpruned walk, zero GETs and zero trashes",
        notProven = "the ETag churn half — that needs a real backup restore, which stays a manual gate",
    ) { devices ->
        val id = devices.share("Row15")
        assertTrue(devices.a.directories.forAccount(MATRIX_ACCOUNT_ID).isNotEmpty(), "nothing was cached to invalidate")

        // ⭐ **This row cannot be made live, and the reason is a SPEC defect rather than a missing
        // shell.** Measured against the real Nextcloud 32.0.13 this suite runs on, with the value
        // set (`occ maintenance:data-fingerprint` → `Updated data-fingerprint to e137b2ff…`),
        // `nc:data-fingerprint` comes back as a **404 propstat** from every endpoint it could
        // plausibly live on:
        //
        //     PROPFIND /remote.php/dav/files/<uid>/   → <nc:data-fingerprint/> 404 Not Found
        //     PROPFIND /remote.php/webdav/            → 404
        //     PROPFIND /remote.php/dav/               → 404
        //     GET /ocs/v2.php/cloud/capabilities      → absent
        //     GET /status.php                         → absent
        //
        // SPEC §7.3 puts `<nc:data-fingerprint/>` in the PROPFIND body and §7.6 step 4 builds the
        // whole backup-restore safety net on it, so **that net does not exist against a current
        // Nextcloud** — `Reconciler`'s `fingerprintMoved` branch requires the property to arrive and
        // it never does. SPEC has been corrected; see `docs/M6-SYNC.md`.
        //
        // Seeding the *cached* value instead does not substitute, and the attempt is what found
        // this: `fingerprintMoved` requires **both** sides non-null on purpose, because a server
        // that never reports one would otherwise force an unpruned walk on every single poll —
        // permanent amplification, which is the failure mode SPEC §7.2's neighbourhood exists to
        // prevent. Absent must not mean changed. That asymmetry is now the only reachable live
        // behaviour, so it is asserted below rather than left implicit.
        val drivenByAdmin = devices.server.setDataFingerprint("fingerprint-restored")
        if (!drivenByAdmin) {
            val cached = assertNotNull(
                devices.a.cursors.find(MATRIX_ACCOUNT_ID),
                "the first pull cached no cursor",
            )
            devices.a.cursors.upsert(cached.copy(dataFingerprint = "fingerprint-before-the-restore"))
            devices.resetCounts()

            val absent = devices.a.pull()

            assertEquals(0, absent.trashed, "a server that reports no fingerprint caused a trashing")
            devices.assertRequests("GET", 0, "a server that reports no fingerprint caused a download")
            assertTrue(
                devices.a.directories.forAccount(MATRIX_ACCOUNT_ID).isNotEmpty(),
                "an absent fingerprint cleared the directory cache — absent must not mean changed, " +
                    "or every poll against a real Nextcloud is an unpruned walk",
            )
            announceNeedsAdmin(
                "row 15",
                "a server that actually returns nc:data-fingerprint — Nextcloud 32.0.13 does not, " +
                    "even with the value set (see this test's KDoc)",
                "that an absent fingerprint neither clears the cache, re-downloads, nor trashes",
            )
            return@matrixRow
        }
        devices.resetCounts()

        val pull = devices.a.pull()

        assertTrue(pull.walked > 0, "the fingerprint change did not force a walk")
        assertEquals(0, pull.pruned, "a forced walk pruned a subtree")
        assertEquals(0, pull.trashed, "a re-walked tree was read as a mass deletion")
        devices.assertRequests("GET", 0, "unchanged content was re-downloaded after a fingerprint change")
        assertTrue(devices.a.directories.forAccount(MATRIX_ACCOUNT_ID).isNotEmpty(), "the cache was not rebuilt")
        assertNotNull(devices.a.document(id), "the note went away")
    }

    /**
     * ⭐ Row 16 — a **weak** validator: no `If-Match`, no 412 loop, and the checksum decides instead.
     *
     * SPEC §7.3: returning `W/"…"` in `If-Match` is a permanent 412, and *"was this weak"* cannot be
     * recovered from the stripped string — so it is stored as a flag beside the value and
     * `asIfMatch` answers **null**, making the trap unrepresentable rather than remembered. What
     * then answers *"has this changed"* is `OC-Checksum`, which is why `lastChecksum` exists.
     *
     * Live, this is no longer a switch inside a fake. [CountingProxy] rewrites `ETag` and `OC-ETag` to
     * `W/"…"` in front of a real Nextcloud — which *is* the compressing reverse proxy SPEC §7.3
     * describes — and its counters are what turn *"no `If-Match` was sent"* from a claim about a
     * getter into a claim about the wire.
     *
     * Four steps, in the order the trap springs:
     *
     * 1. one upload turns the binding weak. Its own `If-Match` is still the strong one the setup left,
     *    so nothing is asked to cope yet — the weakness arrives in the **response**.
     * 2. the **next** upload has no `If-Match` to offer and must still succeed, in **one** PUT. That
     *    is the whole of "no 412 loop": the failure mode is a ladder, not a single refusal.
     * 3. the poll after it must not re-download a file that has not changed. With a weak stored
     *    validator `RemoteWalk.unchanged` is false *by construction*, so the only branch left in
     *    `Reconciler.visit` that neither downloads nor reports an outcome is the `OC-Checksum`
     *    comparison — a walked pass with every outcome counter at zero and zero GETs is therefore a
     *    proof by exhaustion that the fallback ran.
     * 4. and the flag survives all of it. A validator that quietly became strong again is the
     *    permanent 412 back.
     */
    @Test
    fun `row 16 - a weak ETag withholds If-Match and the checksum answers instead`() = matrixRow(
        row = "row 16 (weak ETags)",
        proven = "an upload that succeeds with no If-Match on the wire, and a checksum-settled poll",
        notProven = "what a server does when it weakens the getetag inside a multistatus as well — the " +
            "in-process half does that and a compressing proxy does not, so between them both shapes run",
    ) { devices ->
        val id = devices.share("Row16")
        if (!devices.server.useWeakEtags(true)) {
            announceNeedsAdmin("row 16", "a weak-ETag proxy in front of the server", "nothing — this row needs it")
            return@matrixRow
        }

        devices.resetCounts()
        devices.a.drawStroke(id)
        devices.a.clock.advance(THIRTY_ONE_SECONDS)
        assertEquals(1, devices.a.drain().uploaded, "the upload that stores a weak validator did not happen")
        // ⚠️ The control for the assertion three blocks down. This upload's stored validator is still
        // the strong one the setup left, so its PUT **must** carry an `If-Match` — without proving the
        // counter can reach one, "no PUT carried an If-Match" would also be what a broken counter
        // says, and the row would pass having measured nothing.
        devices.assertRequestsWithHeader("PUT", "If-Match", 1, "a strong validator was not sent as If-Match")
        val stored = assertNotNull(
            assertNotNull(devices.a.binding(id)).lastSyncedEtag,
            "no validator was stored at all",
        )
        assertTrue(stored.weak, "the weak flag was not stored beside the value, so it cannot be recovered")
        assertNull(stored.asIfMatch, "a weak validator was offered as If-Match — a permanent 412")

        // ⭐ The upload that matters: there is no usable precondition left to send.
        devices.resetCounts()
        devices.a.drawStroke(id)
        devices.a.clock.advance(THIRTY_ONE_SECONDS)
        val second = devices.a.drain()

        assertEquals(1, second.uploaded, "a weak ETag produced a 412 loop instead of an upload")
        assertEquals(0, second.failed, "the upload failed under a weak validator")
        assertEquals(0, second.parked, "the upload was parked — the app read a 412 as a conflict")
        devices.assertRequests("PUT", 1, "the weak validator produced a PUT ladder rather than one upload")
        devices.assertRequestsWithHeader("PUT", "If-Match", 0, "a weak validator went back out as If-Match")
        val binding = assertNotNull(devices.a.binding(id))
        assertTrue(assertNotNull(binding.lastSyncedEtag).weak, "the stored validator quietly became strong again")
        val checksum = assertNotNull(binding.lastChecksum, "no checksum was stored, so the fallback compares nothing")

        // Step 3 — the change decision, with the ETag unable to make it.
        devices.resetCounts()
        val pull = devices.a.pull()
        assertTrue(pull.walked > 0, "the pass short-circuited, so it never reached the file at all")
        assertEquals(0, pull.merged, "an unchanged file was merged because its ETag could not be compared")
        assertEquals(0, pull.moved, "the file was read as having moved")
        assertEquals(0, pull.conflicted, "an unchanged file became a conflict")
        assertEquals(0, pull.deferred + pull.failed, "the pass did not conclude: $pull")
        devices.assertRequests("GET", 0, "an unchanged file was downloaded because its ETag was weak")

        // …and the material the fallback compared really came from the server, which is the half of
        // "Nextcloud verifies OC-Checksum" that nothing else here reads.
        assertEquals(
            checksum.lowercase(),
            assertNotNull(
                devices.remoteChecksum(devices.a.remotePath(id)),
                "the server published no oc:checksums, so the weak-ETag fallback had nothing to compare",
            ).lowercase(),
            "the server's MD5 and the stored one disagree, so the fallback settled the file by luck",
        )
    }

    private companion object {
        /** Just past SPEC §7.6's 30 s floor between uploads of one note. */
        const val THIRTY_ONE_SECONDS = 31_000L

        /** `OutboxDrainer.PARK_MILLIS` — how long a flagged-and-skipped row stays out of the way. */
        const val ONE_DAY = 24L * 60 * 60 * 1000
    }
}
