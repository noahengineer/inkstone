package dev.inkstone.core.database.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.sync.AttentionEntry
import dev.inkstone.core.sync.ETag
import dev.inkstone.core.sync.FolderBinding
import dev.inkstone.core.sync.ForeignRemote
import dev.inkstone.core.sync.PendingDownload
import dev.inkstone.core.sync.RemoteBinding
import dev.inkstone.core.sync.RemoteDirectory
import dev.inkstone.core.sync.SyncCursor
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
 * M6's reconciler rows, against **the device's own SQLite**.
 *
 * Everything here is something only a real database can settle:
 *
 * - `upsertBinding` carries `lastSeenRemoteAt` forward rather than resetting it. This is the
 *   one that matters: `INSERT OR REPLACE` deletes and re-inserts, so the default would win and
 *   the next reconcile pass would read the whole library as absent remotely. It is a scalar
 *   subquery evaluated against the row being replaced — a claim about SQLite, not about Kotlin.
 * - `stampBindingSeen` expands `IN :noteIds` to real placeholders, and `notSeenSince` is the
 *   complement of it.
 * - `upsertPeerClock`'s `max()` makes the stored counter monotonic.
 * - the weak-ETag flag survives a round trip in all five of the new tables that hold one, which
 *   is the piece of state SPEC §7.3 says cannot be recovered from the stripped value.
 * - the composite primary keys really coalesce.
 * - the two rows M6's review added persist what has to outlive a process WorkManager restarts:
 *   `syncCursor.refusedUntil` (CLAUDE.md rule 5's cooldown) and `foreignRemote` ("this remote PDF
 *   is not one of ours", SPEC §5.6).
 *
 * Test names are restricted to `[A-Za-z0-9 _-]`: Kotlin/Native rejects a comma and D8 rejects a
 * comma or an apostrophe, and neither shows up until a full build or a dex (M5 lost twelve
 * names to this).
 *
 * ```
 * ./gradlew :core:database:connectedDebugAndroidTest
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SqlReconcileStoresTest {

    private val driver = AndroidSqliteDriver(
        schema = InkstoneDatabase.Schema,
        context = ApplicationProvider.getApplicationContext(),
        name = null,
    )
    private val database = InkstoneDatabase(driver)
    private val dispatcher = UnconfinedTestDispatcher()

    private val bindings = SqlBindingStore(database, dispatcher)
    private val directories = SqlRemoteDirectoryStore(database, dispatcher)
    private val cursors = SqlSyncCursorStore(database, dispatcher)
    private val downloads = SqlPendingDownloadStore(database, dispatcher)
    private val attention = SqlAttentionStore(database, dispatcher)
    private val folderBindings = SqlFolderBindingStore(database, dispatcher)
    private val peers = SqlPeerClockStore(database, dispatcher)

    private val note = DocumentId("note-1")
    private val account = "acct-1"

    @AfterTest
    fun close() = driver.close()

    // -----------------------------------------------------------------------
    // remoteBinding.lastSeenRemoteAt
    // -----------------------------------------------------------------------

    @Test
    fun `lastSeenRemoteAt survives an ordinary binding upsert`() = runTest {
        // The hundred-notes-in-the-trash bug, as a test. Stamp the note as seen, then do what
        // a successful upload does — write the binding again with a new ETag. If INSERT OR
        // REPLACE reset the column to its DEFAULT of 0, `notSeenSince(5_000)` would report it.
        bindings.upsert(RemoteBinding(note, account, "Inkstone/Lecture.pdf", remoteOcId = "42ocxyz"))
        bindings.stampSeen(listOf(note), seenAtEpochMillis = 5_000L)

        bindings.upsert(
            RemoteBinding(
                noteId = note,
                accountId = account,
                remotePath = "Inkstone/Lecture.pdf",
                remoteOcId = "42ocxyz",
                lastSyncedEtag = ETag("fresh", weak = false),
                lastSyncedRev = 9L,
                lastUploadAtEpochMillis = 6_000L,
            ),
        )

        assertTrue(
            bindings.notSeenSince(account, sinceEpochMillis = 5_000L).isEmpty(),
            "an upload must not make a note look absent remotely",
        )
        assertEquals(ETag("fresh", weak = false), assertNotNull(bindings.find(note)).lastSyncedEtag)
    }

    @Test
    fun `stampBindingSeen marks only the notes it is given`() = runTest {
        val second = DocumentId("note-2")
        val third = DocumentId("note-3")
        for (id in listOf(note, second, third)) {
            bindings.upsert(RemoteBinding(id, account, "Inkstone/${id.value}.pdf"))
        }

        bindings.stampSeen(listOf(note, third), seenAtEpochMillis = 7_000L)

        assertEquals(
            listOf(second),
            bindings.notSeenSince(account, sinceEpochMillis = 7_000L).map { it.noteId },
        )
    }

    @Test
    fun `an empty stamp is a no-op rather than a syntax error`() = runTest {
        // SQLDelight expands `IN :noteIds` to one placeholder per value, and none is `IN ()`.
        bindings.upsert(RemoteBinding(note, account, "Inkstone/Lecture.pdf"))

        bindings.stampSeen(emptyList(), seenAtEpochMillis = 7_000L)

        assertEquals(1, bindings.notSeenSince(account, sinceEpochMillis = 1L).size)
    }

    @Test
    fun `notSeenSince carries the path so a pruned subtree can be excluded`() = runTest {
        // The caller has to intersect these candidates with the directories it actually walked,
        // which it does by path — so the path has to come back with them.
        bindings.upsert(RemoteBinding(note, account, "Inkstone/Term 1/Lecture.pdf"))

        val stale = bindings.notSeenSince(account, sinceEpochMillis = 1L).single()

        assertEquals(note, stale.noteId)
        assertEquals("Inkstone/Term 1/Lecture.pdf", stale.remotePath)
    }

    @Test
    fun `a binding is found by its oc id and by its path`() = runTest {
        bindings.upsert(RemoteBinding(note, account, "Inkstone/Lecture.pdf", remoteOcId = "00000042ocxyz"))

        assertEquals(note, bindings.findByOcId("00000042ocxyz").single().noteId)
        assertEquals(note, bindings.findByPath(account, "Inkstone/Lecture.pdf").single().noteId)
        assertTrue(bindings.findByOcId("nobody").isEmpty())
    }

    // -----------------------------------------------------------------------
    // The directory ETag cache
    // -----------------------------------------------------------------------

    @Test
    fun `a directory ETag round-trips with its weak flag`() = runTest {
        // A weak directory ETag must never prune, and "was this weak" cannot be recovered from
        // the stripped string — so it is a column, and this is the round trip.
        directories.upsert(
            RemoteDirectory(
                account,
                "Inkstone",
                ETag("weakdir", weak = true),
                ocId = "7ocxyz",
                seenAtEpochMillis = 11L,
            ),
        )

        val stored = directories.forAccount(account).single()
        assertEquals(ETag("weakdir", weak = true), stored.etag)
        assertTrue(assertNotNull(stored.etag).weak, "a weak directory ETag must still read as weak")
        assertEquals("7ocxyz", stored.ocId)
        assertEquals(11L, stored.seenAtEpochMillis)
    }

    @Test
    fun `the directory cache is keyed per account and not by path alone`() = runTest {
        // Both accounts have an Inkstone folder. Keying on path alone would let one server's
        // ETag prune the other server's subtree.
        directories.upsertAll(
            listOf(
                RemoteDirectory(account, "Inkstone", ETag("a", weak = false)),
                RemoteDirectory("acct-2", "Inkstone", ETag("b", weak = false)),
            ),
        )

        assertEquals(ETag("a", weak = false), directories.forAccount(account).single().etag)
        assertEquals(ETag("b", weak = false), directories.forAccount("acct-2").single().etag)
    }

    @Test
    fun `clearFor drops one accounts cache and leaves the other alone`() = runTest {
        directories.upsertAll(
            listOf(
                RemoteDirectory(account, "Inkstone", ETag("a", weak = false)),
                RemoteDirectory(account, "Inkstone/Term 1", ETag("a1", weak = false)),
                RemoteDirectory("acct-2", "Inkstone", ETag("b", weak = false)),
            ),
        )

        directories.clearFor(account)

        assertTrue(directories.forAccount(account).isEmpty())
        assertEquals(1, directories.forAccount("acct-2").size)
    }

    // -----------------------------------------------------------------------
    // Cursor
    // -----------------------------------------------------------------------

    @Test
    fun `the cursor round-trips a weak root ETag and a data fingerprint`() = runTest {
        assertNull(cursors.find(account), "no cursor before the first pass")

        cursors.upsert(
            SyncCursor(
                accountId = account,
                dataFingerprint = "fp-1",
                rootEtag = ETag("root", weak = true),
                lastReconcileAtEpochMillis = 1_000L,
                lastFullAtEpochMillis = 900L,
            ),
        )

        val stored = assertNotNull(cursors.find(account))
        assertEquals("fp-1", stored.dataFingerprint)
        assertEquals(ETag("root", weak = true), stored.rootEtag)
        assertEquals(1_000L, stored.lastReconcileAtEpochMillis)
        assertEquals(900L, stored.lastFullAtEpochMillis)

        cursors.upsert(stored.copy(dataFingerprint = "fp-2", rootEtag = null))
        val second = assertNotNull(cursors.find(account))
        assertEquals("fp-2", second.dataFingerprint)
        assertNull(second.rootEtag, "a cleared root ETag must clear")
    }

    @Test
    fun `the cursor remembers how long a refused account is left alone`() = runTest {
        // ⚠️ CLAUDE.md rule 5. A 401 the confirmation probe could not confirm leaves the account
        // ACTIVE, and an ACTIVE account is one the sixty-second poll walks again — so the cooldown
        // has to outlive the process WorkManager is going to restart. Held in memory it would be
        // cleared by every wake-up, which is the same as not having it.
        cursors.upsert(SyncCursor(accountId = account, refusedUntilEpochMillis = 9_000L))
        assertEquals(9_000L, assertNotNull(cursors.find(account)).refusedUntilEpochMillis)

        // And it is written by the pass's *own* cursor write, so an ordinary end-of-pass update
        // carries it rather than resetting it to the column default.
        val stored = assertNotNull(cursors.find(account))
        cursors.upsert(stored.copy(lastReconcileAtEpochMillis = 12_000L))
        assertEquals(9_000L, assertNotNull(cursors.find(account)).refusedUntilEpochMillis)
    }

    // -----------------------------------------------------------------------
    // Foreign files (SPEC §5.6's identity check, negatively)
    // -----------------------------------------------------------------------

    @Test
    fun `a not-ours answer round-trips per account with its weak flag`() = runTest {
        // The row that stops a handout beside the notes being re-downloaded on every note save, and
        // stops `limit` strangers starving the pass. Keyed per account for `remoteDirectory`'s
        // reason: two servers legitimately have a file at the same path.
        val foreign = SqlForeignRemoteStore(database, dispatcher)
        foreign.upsert(
            ForeignRemote(
                accountId = account,
                fileKey = "peer-00000099ocxyz",
                remotePath = "Inkstone/Scanned contract.pdf",
                etag = ETag("weakfile", weak = true),
                checksum = "d41d8cd98f00b204e9800998ecf8427e",
                seenAtEpochMillis = 77L,
            ),
        )
        foreign.upsert(
            ForeignRemote(
                accountId = "acct-2",
                fileKey = "peer-00000099ocxyz",
                remotePath = "Elsewhere/Scanned contract.pdf",
            ),
        )

        val stored = foreign.forAccount(account).single()
        assertEquals(ETag("weakfile", weak = true), stored.etag, "a weak validator must still read as weak")
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", stored.checksum)
        assertEquals(77L, stored.seenAtEpochMillis)
        assertEquals("Elsewhere/Scanned contract.pdf", foreign.forAccount("acct-2").single().remotePath)

        // A re-confirmation of the same file replaces the row rather than growing the table: the
        // same stranger's PDF, re-uploaded, is one answer and not two.
        foreign.upsert(stored.copy(etag = ETag("strongfile", weak = false), checksum = null))
        val replaced = foreign.forAccount(account).single()
        assertEquals(ETag("strongfile", weak = false), replaced.etag)
        assertNull(replaced.checksum, "a cleared checksum must clear")
    }

    // -----------------------------------------------------------------------
    // Pending downloads
    // -----------------------------------------------------------------------

    @Test
    fun `a pending download round-trips and one note holds at most one`() = runTest {
        downloads.upsert(
            PendingDownload(
                noteId = note,
                accountId = account,
                remotePath = "Inkstone/Lecture.pdf",
                remoteOcId = "42ocxyz",
                remoteEtag = ETag("remote", weak = false),
                tempPath = "tmp/note-1.part",
                fetchedAtEpochMillis = 4_242L,
            ),
        )
        downloads.upsert(
            PendingDownload(
                noteId = note,
                accountId = account,
                remotePath = "Inkstone/Lecture.pdf",
                remoteOcId = "42ocxyz",
                remoteEtag = ETag("newer", weak = true),
                tempPath = "tmp/note-1-again.part",
                fetchedAtEpochMillis = 5_000L,
            ),
        )

        val stored = assertNotNull(downloads.find(note))
        assertEquals("tmp/note-1-again.part", stored.tempPath)
        assertEquals(ETag("newer", weak = true), stored.remoteEtag)
        assertEquals(5_000L, stored.fetchedAtEpochMillis)

        downloads.remove(note)
        assertNull(downloads.find(note))
    }

    // -----------------------------------------------------------------------
    // Needs attention
    // -----------------------------------------------------------------------

    @Test
    fun `an acknowledged entry leaves the open list and keeps its row`() = runTest {
        attention.add(
            AttentionEntry(
                id = "att-1",
                accountId = account,
                kind = "CONFLICTED_COPY",
                createdAtEpochMillis = 100L,
                noteId = note,
                detail = "kept both versions",
                conflictedCopyNoteId = DocumentId("note-copy"),
            ),
        )

        val open = attention.open().single()
        assertEquals("att-1", open.id)
        assertTrue(open.isOpen)
        assertEquals(DocumentId("note-copy"), open.conflictedCopyNoteId)

        attention.acknowledge("att-1", acknowledgedAtEpochMillis = 500L)

        assertTrue(attention.open().isEmpty(), "an acknowledged entry is not open")
        // CLAUDE.md rule 1 — a tombstone, not a removal. The row is still there, and the only
        // way to see that through this API is that acknowledging it again is not an insert.
        attention.acknowledge("att-1", acknowledgedAtEpochMillis = 600L)
        assertTrue(attention.open().isEmpty())
    }

    @Test
    fun `an entry is still found by id after it has been acknowledged`() = runTest {
        // ⚠️ The read the reconciler dedups against, and the read layer 3 makes before it mints a
        // second conflicted copy. Both have to see *through* the tombstone, and `selectOpenAttention`
        // cannot: it is `acknowledgedAt = 0`, so a guard written against it let the same problem
        // through and `insertAttention`'s REPLACE reset the dismissal on the next poll. The copy
        // pointer has to survive too — the row is the only record that a copy was ever made.
        attention.add(
            AttentionEntry(
                id = "att-1",
                accountId = account,
                kind = "CONFLICTED_COPY",
                createdAtEpochMillis = 100L,
                noteId = note,
                conflictedCopyNoteId = DocumentId("note-copy"),
            ),
        )
        attention.acknowledge("att-1", acknowledgedAtEpochMillis = 500L)

        val row = assertNotNull(attention.find("att-1"), "an acknowledged row is still a row")
        assertEquals(500L, row.acknowledgedAtEpochMillis)
        assertEquals(DocumentId("note-copy"), row.conflictedCopyNoteId)
        assertNull(attention.find("att-never-written"))
    }

    @Test
    fun `an id derived from the problem gives one entry however many passes find it`() = runTest {
        // Three reconcile passes rediscovering the same conflict must not leave three rows.
        for (pass in 1L..3L) {
            attention.add(
                AttentionEntry(
                    id = "note-1/CONFLICTED_COPY",
                    accountId = account,
                    kind = "CONFLICTED_COPY",
                    createdAtEpochMillis = pass * 100L,
                    noteId = note,
                ),
            )
        }

        val open = attention.open().single()
        assertEquals(300L, open.createdAtEpochMillis)
    }

    @Test
    fun `the open list is newest first`() = runTest {
        for (index in 1L..3L) {
            attention.add(
                AttentionEntry(
                    id = "att-$index",
                    accountId = account,
                    kind = "QUOTA",
                    createdAtEpochMillis = index * 100L,
                ),
            )
        }

        assertEquals(listOf("att-3", "att-2", "att-1"), attention.open().map { it.id })
    }

    // -----------------------------------------------------------------------
    // Folder bindings
    // -----------------------------------------------------------------------

    @Test
    fun `a folder binding is found by its oc id so a rename stays a rename`() = runTest {
        val folder = FolderId("folder-1")
        folderBindings.upsert(
            FolderBinding(
                folder,
                account,
                "Inkstone/Term 1",
                remoteOcId = "9ocxyz",
                remoteEtag = ETag("f", weak = true),
            ),
        )

        assertEquals(folder, folderBindings.findByOcId(account, "9ocxyz").single().folderId)
        assertTrue(folderBindings.findByOcId("acct-2", "9ocxyz").isEmpty(), "oc id is scoped to the account here")

        val stored = assertNotNull(folderBindings.find(folder))
        assertEquals("Inkstone/Term 1", stored.remotePath)
        assertEquals(ETag("f", weak = true), stored.remoteEtag)

        folderBindings.remove(folder)
        assertNull(folderBindings.find(folder))
    }

    // -----------------------------------------------------------------------
    // Peer clocks
    // -----------------------------------------------------------------------

    @Test
    fun `a peer clock never moves backwards`() = runTest {
        // Downloads arrive out of order: a poll can hand back a peer's older bundle after its
        // newer one. The max is in SQL, so this is a claim about the statement.
        peers.record(note, "device-b", highestCounter = 40L, seenAtEpochMillis = 100L)
        peers.record(note, "device-b", highestCounter = 12L, seenAtEpochMillis = 200L)

        val stored = peers.forNote(note).single()
        assertEquals(40L, stored.highestCounter)
        assertEquals(200L, stored.seenAtEpochMillis, "seenAt still records the later contact")

        peers.record(note, "device-b", highestCounter = 55L, seenAtEpochMillis = 300L)
        assertEquals(55L, peers.forNote(note).single().highestCounter)
    }

    @Test
    fun `oldestAckedCounter is the min across peers and null when none are known`() = runTest {
        // SPEC §4.7 — this is the value Compaction has been passed null for since M2.
        assertNull(peers.oldestAckedCounter(note), "nothing known means the 30-day horizon is the only guard")

        peers.record(note, "device-b", highestCounter = 90L, seenAtEpochMillis = 1L)
        peers.record(note, "device-c", highestCounter = 30L, seenAtEpochMillis = 1L)
        peers.record(DocumentId("note-2"), "device-b", highestCounter = 5L, seenAtEpochMillis = 1L)

        assertEquals(30L, peers.oldestAckedCounter(note), "the min across peers of this note only")
        assertEquals(5L, peers.oldestAckedCounter(DocumentId("note-2")))
    }

    @Test
    fun `peer clocks are keyed per note and per device`() = runTest {
        peers.record(note, "device-b", highestCounter = 10L, seenAtEpochMillis = 1L)
        peers.record(note, "device-c", highestCounter = 20L, seenAtEpochMillis = 1L)
        peers.record(note, "device-b", highestCounter = 11L, seenAtEpochMillis = 2L)

        assertEquals(2, peers.forNote(note).size)
        assertEquals(
            mapOf("device-b" to 11L, "device-c" to 20L),
            peers.forNote(note).associate { it.peerDeviceId to it.highestCounter },
        )
    }
}
