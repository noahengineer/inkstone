package dev.inkstone.core.database.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.database.library.LibraryIndex
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.NoteSummary
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.sync.AccountState
import dev.inkstone.core.sync.ETag
import dev.inkstone.core.sync.OutboxCoalescing
import dev.inkstone.core.sync.OutboxKey
import dev.inkstone.core.sync.OutboxOpType
import dev.inkstone.core.sync.RemoteBinding
import dev.inkstone.core.sync.SyncAccount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC §7.6's tables, against **the device's own SQLite**.
 *
 * `:core:sync`'s tests prove the *semantics* against a fake store — that is where the
 * coalescing rules live and where they can be stated one per test. What only a real
 * database can prove is here:
 *
 * - the composite primary key `(noteId, opType)` actually coalesces, rather than the fake's
 *   `LinkedHashMap` doing it
 * - `mutate` is one transaction
 * - `selectDueOutbox`'s `CASE` ordering runs on Android SQLite, not just in SQLDelight's parser
 * - the weak-ETag flag survives a round trip, which is the one piece of state that cannot be
 *   recovered from the stored string (SPEC §7.3's permanent-412 trap)
 *
 * ```
 * ./gradlew :core:database:connectedDebugAndroidTest
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SqlSyncStoresTest {

    private val driver = AndroidSqliteDriver(
        schema = InkstoneDatabase.Schema,
        context = ApplicationProvider.getApplicationContext(),
        name = null,
    )
    private val database = InkstoneDatabase(driver)
    private val dispatcher = UnconfinedTestDispatcher()

    private val accounts = SqlAccountStore(database, dispatcher)
    private val bindings = SqlBindingStore(database, dispatcher)
    private val outbox = SqlOutboxStore(database, dispatcher)
    private val syncNotes = SqlSyncNotes(database, dispatcher)
    private val library = LibraryIndex(database, dispatcher)

    private val note = DocumentId("note-1")

    @AfterTest
    fun close() = driver.close()

    private suspend fun enqueue(
        id: DocumentId,
        opType: OutboxOpType,
        localRev: Long,
        everUploaded: Boolean = true,
        now: Long,
    ) = outbox.mutate(id) { pending ->
        OutboxCoalescing.plan(pending, opType, id, localRev, everUploaded, now)
    }

    @Test
    fun `an account round-trips with its capabilities`() = runTest {
        accounts.upsert(
            SyncAccount(
                id = "acct-1",
                serverUrl = "https://cloud.example.com",
                // SPEC §0.1 #3 — the two are stored separately and are not interchangeable.
                loginName = "noah@example.com",
                uid = "noah",
                displayName = "Noah B",
                defaultFolder = "Inkstone",
            ),
        )

        val stored = assertNotNull(accounts.find("acct-1"))
        assertEquals("noah@example.com", stored.loginName)
        assertEquals("noah", stored.uid)
        assertTrue(stored.isUsable)

        accounts.setState("acct-1", AccountState.NEEDS_REAUTH, "401")
        val halted = assertNotNull(accounts.find("acct-1"))
        assertEquals(AccountState.NEEDS_REAUTH, halted.state)
        assertTrue(!halted.isUsable, "a halted account must not be usable")
    }

    @Test
    fun `an account row never holds the app password`() {
        // SPEC §14 — it lives in the Keystore, keyed by account id. This asserts on the
        // *schema*, because the pressure to add a convenient column arrives later.
        val columns = database.syncQueries
            .let {
                driver.executeQuery(null, "PRAGMA table_info(account)", { cursor ->
                    val names = mutableListOf<String>()
                    while (cursor.next().value) names += cursor.getString(1).orEmpty()
                    app.cash.sqldelight.db.QueryResult.Value(names)
                }, 0)
            }
            .value

        assertTrue(columns.isNotEmpty())
        for (forbidden in listOf("password", "appPassword", "app_password", "secret", "token")) {
            assertTrue(columns.none { it.equals(forbidden, ignoreCase = true) }, "account.$forbidden must not exist")
        }
    }

    @Test
    fun `the weak-ETag flag survives a round trip`() {
        // SPEC §7.3: a weak ETag returned in If-Match is a PERMANENT 412 loop, and
        // "was this weak" cannot be recovered from the stripped value — so it is a column.
        runTest {
            bindings.upsert(
                RemoteBinding(
                    noteId = note,
                    accountId = "acct-1",
                    remotePath = "Inkstone/Lecture.pdf",
                    remoteOcId = "00000042ocxyz",
                    lastSyncedEtag = ETag("weakvalue", weak = true),
                    lastSyncedRev = 7L,
                    lastUploadAtEpochMillis = 12_345L,
                    lastChecksum = "0123abcd",
                ),
            )

            val stored = assertNotNull(bindings.find(note))
            assertEquals(ETag("weakvalue", weak = true), stored.lastSyncedEtag)
            assertNull(stored.lastSyncedEtag?.asIfMatch, "a weak ETag must never become an If-Match")
            assertEquals(7L, stored.lastSyncedRev)
            assertEquals(12_345L, stored.lastUploadAtEpochMillis)
            assertTrue(stored.everUploaded)
        }
    }

    @Test
    fun `the composite primary key coalesces uploads in real SQLite`() = runTest {
        // The whole of "500 offline edits, one upload" is this key doing its job.
        for (rev in 1L..500L) enqueue(note, OutboxOpType.UPLOAD, rev, now = 1000L + rev)

        assertEquals(1, outbox.count())
        val row = assertNotNull(outbox.pendingFor(note).singleOrNull())
        assertEquals(500L, row.localRevAtEnqueue)
        assertEquals(1001L, row.createdAt, "the earliest created_at must be kept")
    }

    @Test
    fun `a delete annihilates a pending upload inside one transaction`() = runTest {
        enqueue(note, OutboxOpType.UPLOAD, 3L, now = 100L)
        enqueue(note, OutboxOpType.MOVE, 3L, now = 100L)

        enqueue(note, OutboxOpType.DELETE, 3L, everUploaded = true, now = 200L)

        assertEquals(listOf(OutboxOpType.DELETE), outbox.pendingFor(note).map { it.opType })
    }

    @Test
    fun `due rows come back in drain order`() = runTest {
        // SPEC §7.6: MKCOL before anything inside it, MOVE before UPLOAD, DELETE last.
        // The ORDER BY is a CASE expression, so this is also a check that Android's SQLite
        // runs it — SQLDelight only proves it parses.
        val second = DocumentId("note-2")
        enqueue(note, OutboxOpType.UPLOAD, 1L, now = 10L)
        enqueue(note, OutboxOpType.MOVE, 1L, now = 20L)
        enqueue(note, OutboxOpType.MKCOL, 1L, now = 30L)
        enqueue(second, OutboxOpType.DELETE, 1L, everUploaded = true, now = 5L)

        val due = outbox.due(nowEpochMillis = 1_000L, limit = 10)

        assertEquals(
            listOf(OutboxOpType.MKCOL, OutboxOpType.MOVE, OutboxOpType.UPLOAD, OutboxOpType.DELETE),
            due.map { it.opType },
        )
    }

    /**
     * ⭐ M12 — the widened coalescing key, against **Android's own SQLite**.
     *
     * The JVM matrix proves the semantics; this proves the schema on the engine the tablet runs.
     * Two things only a real device can answer: that `7.sqm`'s table rebuild leaves a primary key
     * of `(noteId, opType, subject)` so `INSERT OR REPLACE` no longer collapses two recordings,
     * and that `selectDueOutbox`'s `CASE` puts MEDIA_UPLOAD before DELETE — which is what stops a
     * drained DELETE removing the binding a queued lecture needs.
     */
    @Test
    fun `two recordings on one note are two rows and drain before a delete`() = runTest {
        enqueueMedia(note, "rec-1", now = 10L)
        enqueueMedia(note, "rec-2", now = 20L)
        enqueue(note, OutboxOpType.DELETE, 1L, everUploaded = true, now = 5L)

        val pending = outbox.pendingFor(note)
        assertEquals(
            setOf("rec-1", "rec-2"),
            pending.filter { it.opType == OutboxOpType.MEDIA_UPLOAD }.mapTo(HashSet()) { it.subject },
            "one recording replaced the other — Android's SQLite coalesced on the old key",
        )

        val due = outbox.due(nowEpochMillis = 1_000L, limit = 10).map { it.opType }
        assertTrue(
            due.indexOf(OutboxOpType.MEDIA_UPLOAD) < due.indexOf(OutboxOpType.DELETE),
            "a DELETE would drain first and take the binding the lecture needs with it",
        )

        // And the row is removable by its whole key, which is what the drainer does on success.
        outbox.remove(note, OutboxKey(OutboxOpType.MEDIA_UPLOAD, "rec-1"))
        assertEquals(
            listOf("rec-2"),
            outbox.pendingFor(note).filter { it.opType == OutboxOpType.MEDIA_UPLOAD }.map { it.subject },
            "removing one recording removed the other as well",
        )
    }

    /** ⚠️ `removeAll` — the loop over `OutboxOpType.entries` could not name a recording's row. */
    @Test
    fun `removeAll clears rows the op-type vocabulary cannot name`() = runTest {
        enqueue(note, OutboxOpType.UPLOAD, 1L, now = 10L)
        enqueueMedia(note, "rec-1", now = 20L)

        outbox.removeAll(note)

        assertTrue(outbox.pendingFor(note).isEmpty())
    }

    private suspend fun enqueueMedia(noteId: DocumentId, recordingId: String, now: Long) {
        outbox.mutate(noteId) { pending ->
            OutboxCoalescing.plan(
                pending = pending,
                request = OutboxOpType.MEDIA_UPLOAD,
                noteId = noteId,
                localRev = 1L,
                everUploaded = true,
                now = now,
                subject = recordingId,
            )
        }
    }

    @Test
    fun `a row that is not due yet is not returned`() = runTest {
        enqueue(note, OutboxOpType.UPLOAD, 1L, now = 100L)
        outbox.reschedule(note, OutboxKey(OutboxOpType.UPLOAD), attempts = 2, nextAttemptAt = 5_000L)

        assertTrue(outbox.due(nowEpochMillis = 4_999L, limit = 10).isEmpty())
        val due = outbox.due(nowEpochMillis = 5_000L, limit = 10).single()
        assertEquals(2, due.attempts)
    }

    @Test
    fun `sync notes reads and writes the same note row the library does`() = runTest {
        // The narrow port over `note` — proving it is the same row LibraryIndex writes,
        // which is what makes SPEC §7.6's mid-flight local_rev comparison meaningful.
        library.insertNote(NoteSummary(id = note, title = "Lecture", localRev = 4L))

        assertEquals(4L, syncNotes.localRev(note))

        syncNotes.setSyncState(note, NoteSyncState.SYNCED, null)
        assertEquals(NoteSyncState.SYNCED, assertNotNull(library.note(note)).syncState)

        syncNotes.setSyncState(note, NoteSyncState.ERROR, "quota")
        assertEquals(NoteSyncState.ERROR, assertNotNull(library.note(note)).syncState)
    }

    @Test
    fun `bindings are listed per account and removed cleanly`() = runTest {
        bindings.upsert(RemoteBinding(note, "acct-1", "Inkstone/a.pdf"))
        bindings.upsert(RemoteBinding(DocumentId("note-2"), "acct-1", "Inkstone/b.pdf"))
        bindings.upsert(RemoteBinding(DocumentId("note-3"), "acct-2", "Elsewhere/c.pdf"))

        assertEquals(2, bindings.forAccount("acct-1").size)
        assertEquals(setOf("Inkstone/a.pdf", "Inkstone/b.pdf"), bindings.pathsOn("acct-1"))

        bindings.remove(note)
        assertNull(bindings.find(note))
        assertEquals(1, bindings.forAccount("acct-1").size)
    }
}
