package dev.inkstone.app.sync.matrix

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.database.sync.SqlOutboxStore
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.sync.AccountState
import dev.inkstone.core.sync.OutboxCoalescing
import dev.inkstone.core.sync.OutboxOpType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * SPEC §13.2 rows 17 to 21 — **the outbox: coalescing, the mid-flight guard, durability, the 401
 * halt, and fileid churn.**
 *
 * These are the rows about work the app owes the server. Three of them are about *not* doing
 * something: not sending 500 uploads, not marking a note synced when it moved under the PUT, and not
 * sending a second request at a credential the server has already refused.
 */
class MergeMatrixOutboxTest {

    /**
     * Row 17 — 500 offline saves become **one** row and **one** PUT.
     *
     * The row's three preserved fields are each a rule: the newest `local_rev_at_enqueue` (the
     * drainer must upload the newest revision and the mid-flight guard compares against it), the
     * earliest `created_at` (so *"how long has this been waiting"* stays true), and the inherited
     * `next_attempt_at` (resetting it on every save is a hot retry loop against the user's server).
     */
    @Test
    fun `row 17 - five hundred offline saves coalesce into one row and one upload`() = matrixRow(
        row = "row 17 (coalescing)",
        proven = "one outbox row at the newest revision with the earliest created_at, and one PUT",
        notProven = "nothing extra",
        timeout = 8.minutes,
    ) { devices ->
        val id = devices.share("Row17")
        val page = devices.a.firstPage(id)
        val session = devices.a.session(id)
        val firstSaveAt = devices.a.clock.now + 1

        repeat(SAVES) {
            devices.a.clock.advance(1L)
            session.apply(page, listOf(tinyStroke(devices.a, session)))
            session.flush()
            // What `LibraryRepository.noteSaved` does on every two-second autosave.
            devices.a.index.recordSave(id, session.document.localRev, 1, devices.a.clock.now)
            devices.a.engine.noteChanged(id)
        }
        session.materialize()
        devices.a.index.recordSave(id, session.document.localRev, 1, devices.a.clock.now)

        val rows = devices.a.outboxRows()
        assertEquals(1, rows.size, "500 saves produced ${rows.size} rows")
        val row = rows.single()
        assertEquals(OutboxOpType.UPLOAD, row.opType)
        assertEquals(SAVES.toLong() + 1, row.localRevAtEnqueue, "the row is not at the newest revision")
        assertEquals(firstSaveAt, row.createdAt, "the row lost the earliest created_at")
        assertEquals(0, row.attempts, "coalescing consumed an attempt")

        devices.resetCounts()
        assertEquals(1, devices.a.drain().uploaded, "500 saves did not produce exactly one upload")
        devices.assertRequests("PUT", 1, "500 saves produced more than one PUT")
    }

    /**
     * ⚑ Row 18 — **SPEC §7.6's mid-flight `local_rev` guard.**
     *
     * The bug this rules out: the user keeps writing while a PUT is in the air, the 2xx arrives, the
     * note is marked `SYNCED`, and the last ten minutes of writing becomes **invisible to sync
     * forever** — no error, no badge, and the next poll finds nothing to do because the note claims
     * to be up to date.
     *
     * The race is entirely on this side of the socket, so no server can produce it. The seam is the
     * materializer: it reports the revision the bytes were built from, and a real local edit lands
     * between that and the response.
     */
    @Test
    fun `row 18 - a note edited mid-upload is re-enqueued instead of marked synced`() = matrixRow(
        row = "row 18 (mid-flight local_rev)",
        proven = "a 2xx that does not set SYNCED, a refreshed row, and a second PUT carrying the new bytes",
        notProven = "nothing extra — a fake server could not produce this and a real one cannot either",
    ) { devices ->
        val id = devices.share("Row18")
        val page = devices.a.firstPage(id)
        devices.a.drawStroke(id)
        devices.a.clock.advance(THIRTY_ONE_SECONDS)

        var duringUpload: dev.inkstone.core.model.ObjectId? = null
        val racing = EditDuringUploadMaterializer(devices.a.publisher) { note ->
            duringUpload = devices.a.drawStroke(note)
        }
        devices.a.materializer = racing

        val first = devices.a.drainer.drain()
        devices.a.materializer = devices.a.publishDirect

        assertTrue(racing.fired, "the seam never ran, so nothing was raced")
        assertEquals(1, first.requeued, "a note edited mid-flight was not re-enqueued")
        assertNotEquals(
            NoteSyncState.SYNCED,
            assertNotNull(devices.a.summary(id)).syncState,
            "a 2xx marked the note SYNCED while it was already ahead of the uploaded bytes",
        )
        val requeued = assertNotNull(
            devices.a.outboxRows().firstOrNull { it.opType == OutboxOpType.UPLOAD },
            "the row was removed, so the edit made during the upload has no route to the server",
        )
        assertEquals(
            assertNotNull(devices.a.document(id)).localRev,
            requeued.localRevAtEnqueue,
            "the row was not refreshed to the revision the note is actually at",
        )

        devices.a.clock.advance(THIRTY_ONE_SECONDS)
        assertEquals(1, devices.a.drain().uploaded, "the second upload never happened")
        assertEquals(NoteSyncState.SYNCED, assertNotNull(devices.a.summary(id)).syncState)
        assertContentEquals(
            devices.a.publishedBytes(id),
            devices.rawBytes(devices.a.remotePath(id)),
            "the second PUT did not carry the bytes that include the mid-flight edit",
        )
        assertTrue(
            devices.a.page(id, page).objects.any { it.id == duringUpload },
            "the stroke made during the upload is not in the note",
        )
    }

    /**
     * Row 19 — **the outbox survives the process going away.**
     *
     * A file-backed database rather than the fixture's in-memory one, because that is the claim:
     * the rows, their attempt counts and their schedules are on disk and a new process finds them
     * exactly as they were. Written against the real `SqlOutboxStore` and the real schema, so it is
     * also a check that `INSERT OR REPLACE` coalescing does not lose `attempts` across a reopen.
     *
     * ⚠️ The honest version of this row is instrumented — `adb shell am force-stop` mid-drain, which
     * is SPEC §13.3's crash-safety matrix and needs a device. This is the JVM half, and what it does
     * *not* cover is a kill during the write itself.
     */
    @Test
    fun `row 19 - outbox rows and their backoff survive a database reopen`() = runTest(timeout = 2.minutes) {
        val file = Files.createTempFile("inkstone-outbox-", ".db")
        // A JDBC URL, so forward slashes even on Windows.
        val url = "jdbc:sqlite:${file.toAbsolutePath().toString().replace('\\', '/')}"
        val noteId = DocumentId("018bcfe5-0000-7000-8000-00000000abcd")

        val opening = JdbcSqliteDriver(url)
        InkstoneDatabase.Schema.create(opening)
        val first = SqlOutboxStore(InkstoneDatabase(opening), Dispatchers.Unconfined)
        first.mutate(noteId) { pending ->
            OutboxCoalescing.plan(pending, OutboxOpType.UPLOAD, noteId, localRev = 7L, everUploaded = true, now = 100L)
        }
        first.reschedule(noteId, OutboxOpType.UPLOAD, attempts = 3, nextAttemptAt = 999_000L)
        val before = assertNotNull(first.pendingFor(noteId).firstOrNull())
        opening.close()

        val reopened = JdbcSqliteDriver(url)
        val second = SqlOutboxStore(InkstoneDatabase(reopened), Dispatchers.Unconfined)
        val after = assertNotNull(
            second.pendingFor(noteId).firstOrNull(),
            "the outbox was empty after a reopen — queued work does not survive process death",
        )

        assertEquals(before, after, "the row changed across a reopen")
        assertEquals(3, after.attempts, "the attempt count was lost, so the backoff ladder restarts")
        assertEquals(999_000L, after.nextAttemptAt, "the schedule was lost, so a parked row becomes due at once")
        assertTrue(second.due(nowEpochMillis = 1_000_000L, limit = 10).isNotEmpty(), "the row is not due after reopen")
        reopened.close()
        Files.deleteIfExists(file)
    }

    /**
     * ⭐ Row 20 — **SPEC §7.2: one confirmation call, then nothing.**
     *
     * Nextcloud's brute-force protection is on by default and is keyed by IP, so it is shared across
     * a household NAT. CLAUDE.md rule 5: a 401 halts the worker and flags `NEEDS_REAUTH`;
     * auto-retrying credentials is the documented way real users IP-block their own server.
     *
     * Four assertions, and the last two are the ones that were wrong in M5:
     * exactly one PUT, exactly one `GET /ocs/v2.php/cloud/user`, **zero** requests from the
     * reconciler afterwards, and `shouldRunAgain == false` so that WorkManager's exponential
     * backoff does not *become* the retry loop.
     *
     * ## How the live half revokes a credential with no shell on the instance
     *
     * `occ user:delete-app-password` needs SSH to the Pi and there is no key, so the refusal is
     * produced the honest way instead: [CountingProxy] replaces the `Authorization` header with a
     * **deliberately wrong app password** and the real Nextcloud answers a real 401, through its real
     * brute-force protection. Nothing about the refusal is simulated, and the count is read from the
     * proxy rather than assumed.
     *
     * ⚠️ **The live half therefore spends two of that server's brute-force attempts** — the default
     * is roughly ten failures per thirty minutes per IP, and behind a NAT the IP is the whole
     * household. It is written to spend *exactly* two, so a re-run cannot lock the account out; the
     * cost and the claim are deliberately the same number, which is why the total is asserted and
     * not just the per-method split.
     */
    @Test
    fun `row 20 - a refused credential costs one probe and then nothing at all`() = matrixRow(
        row = "row 20 (401 halt)",
        proven = "one PUT, one confirmation call, zero further requests, and no WorkManager retry",
        notProven = "that the server logged no brute-force entries — that needs nextcloud.log",
    ) { devices ->
        val id = devices.share("Row20")
        devices.a.drawStroke(id)
        devices.a.clock.advance(THIRTY_ONE_SECONDS)

        if (!devices.server.refuseAuthorization(true)) {
            announceNeedsAdmin("row 20", "occ user:delete-app-password or the web UI", "nothing — this row needs it")
            return@matrixRow
        }
        devices.resetCounts()

        val drain = devices.a.drainer.drain()

        assertTrue(MATRIX_ACCOUNT_ID in drain.haltedAccounts, "a 401 did not halt the account")
        // ⚠️ `OutboxDrainWorker` reads exactly this, at `SyncWorkers.kt:67`, **before** it looks at
        // `shouldRunAgain` at `:73`. A true here is WorkManager's doubling backoff *being* the
        // credential retry loop rule 5 forbids, without any line of code ever "retrying a 401".
        assertFalse(drain.shouldRunAgain, "a halted account asked WorkManager for a backoff retry — rule 5")
        devices.assertRequests("PUT", 1, "more than one PUT was sent at a credential the server refused")
        devices.assertRequests("GET", 1, "SPEC 7.2 allows exactly one confirmation call")
        devices.assertTotalRequests(
            REQUESTS_A_401_MAY_COST,
            "a refused credential cost more than the one PUT and the one confirmation call",
        )
        assertEquals(
            AccountState.NEEDS_REAUTH,
            assertNotNull(devices.a.accounts.find(MATRIX_ACCOUNT_ID)).state,
            "the account was not flagged, so the UI cannot ask for a login",
        )

        // And the pull side: a halted account gets zero requests, not one polite probe per poll. At
        // the 60 s foreground floor that difference is 1,440 refused credential attempts a day
        // against a server whose brute-force protection is keyed by IP.
        devices.resetCounts()
        val pull = devices.a.pull()
        assertTrue(MATRIX_ACCOUNT_ID in pull.haltedAccounts)
        assertFalse(pull.shouldRunAgain, "a halted account asked the reconcile worker for a backoff retry")
        devices.assertTotalRequests(0, "a halted account was sent a request of some method")
        devices.server.refuseAuthorization(false)
    }

    /**
     * Row 21 — **a Nextcloud trash-restore changes the fileid, so identity is repaired from the PUT.**
     *
     * Two halves, and both are about `RemoteBinding.everUploaded`:
     *
     * 1. a restore *before* the DELETE has drained removes the DELETE row, so nothing is deleted at
     *    all — SPEC §7.6's annihilation rule, run backwards;
     * 2. after a DELETE really has drained, the path is free (the file is in the *server's* trash),
     *    so the re-upload is a **create** and comes back with a **different** `oc:id`, which the
     *    binding takes from the PUT response rather than assuming survived.
     */
    @Test
    fun `row 21 - a restore before the delete drains cancels it and after it re-creates with a new fileid`() =
        matrixRow(
            row = "row 21 (fileid churn)",
            proven = "a cancelled DELETE, a create-shaped re-upload, and an oc:id repaired from the response",
            notProven = "that a real Nextcloud trash-restore mints a new fileid (it is documented to)",
        ) { devices ->
            // ⚠️ Two notes, one per half, deliberately. Doing both to one note does not work, and the
            // reason is a finding rather than a fixture problem: `noteRestored` clears the binding's
            // identity *unconditionally* so that the re-upload is a legal create — but if the DELETE
            // was cancelled rather than drained, the file is still there and the binding now claims
            // it was never uploaded. Trashing that note a second time therefore enqueues **no**
            // DELETE at all (`OutboxCoalescing` needs `everUploaded`), so the remote file is
            // orphaned and the next reconcile pass adopts it back as a new note. Recorded in the M6
            // report; not fixed here.
            val cancelled = devices.share("Row21-cancelled")
            val cancelledPath = devices.a.remotePath(cancelled)

            devices.a.engine.noteTrashed(cancelled)
            assertTrue(
                devices.a.outboxRows().any { it.noteId == cancelled && it.opType == OutboxOpType.DELETE },
                "trashing a bound note owes the server no DELETE",
            )
            devices.a.engine.noteRestored(cancelled)
            assertTrue(
                devices.a.outboxRows().none { it.noteId == cancelled && it.opType == OutboxOpType.DELETE },
                "the restore did not cancel the queued DELETE, so the file is about to be deleted anyway",
            )
            assertTrue(devices.remoteExists(cancelledPath), "the file was deleted despite the restore")

            // Half two: a note whose DELETE really drains, then comes back.
            val churned = devices.share("Row21-churned")
            val path = devices.a.remotePath(churned)
            val ocIdBefore = assertNotNull(devices.rawOcId(path), "the server published no oc:id")

            devices.a.engine.noteTrashed(churned)
            devices.a.clock.advance(THIRTY_ONE_SECONDS)
            assertEquals(1, devices.a.drain().deleted, "the DELETE never drained")
            assertFalse(devices.remoteExists(path), "the file is still on the server")
            // ⚠️ `performDelete` removes the binding, so `noteRestored` finds nothing to restore and
            // a re-bind is what a restore has to do. Also in the report.
            assertNull(devices.a.binding(churned), "the drained DELETE left the binding behind")

            devices.a.bind(churned, "Row21-churned")
            devices.a.clock.advance(THIRTY_ONE_SECONDS)
            assertEquals(1, devices.a.drain().uploaded, "the note never came back to the server")

            val ocIdAfter = assertNotNull(devices.rawOcId(path), "the re-created file has no oc:id")
            assertNotEquals(ocIdBefore, ocIdAfter, "the fileid did not churn, so this row proves nothing")
            assertEquals(
                ocIdAfter,
                assertNotNull(devices.a.binding(churned)).remoteOcId,
                "the oc:id was not repaired from the PUT response, so the next pass adopts the note twice",
            )
        }

    // -----------------------------------------------------------------------

    private fun tinyStroke(device: Device, session: dev.inkstone.core.storage.NoteSession): PageOp.AddObject =
        PageOp.AddObject(
            opId = session.nextOpId(),
            clock = session.nextClock(),
            obj = StrokeObject(
                id = device.ids.newObjectId(),
                geometry = StrokeGeometry(Random.nextBytes(TINY_GEOMETRY_BYTES)),
            ),
        )

    private companion object {
        const val SAVES = 500
        const val TINY_GEOMETRY_BYTES = 24
        const val THIRTY_ONE_SECONDS = 31_000L

        /**
         * SPEC §7.2's whole budget: the request that met the 401, and the one confirmation call.
         *
         * Also, live, the number of brute-force attempts row 20 spends on the real server — see the
         * row's KDoc. Keeping the two the same is what makes a re-run safe.
         */
        const val REQUESTS_A_401_MAY_COST = 2
    }
}
