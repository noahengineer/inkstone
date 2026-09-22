package dev.inkstone.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.inkstone.core.database.library.LibraryIndex
import dev.inkstone.core.database.sync.SqlAttentionStore
import dev.inkstone.core.database.sync.SqlBindingStore
import dev.inkstone.core.database.sync.SqlBrowsedFolderStore
import dev.inkstone.core.database.sync.SqlForeignRemoteStore
import dev.inkstone.core.database.sync.SqlOutboxStore
import dev.inkstone.core.database.sync.SqlRemoteDirectoryStore
import dev.inkstone.core.database.sync.SqlSyncCursorStore
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.sync.OutboxOpType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SPEC §13.1 — **the on-device migration test.** *"Install version N with real data, upgrade to
 * N+1, verify."*
 *
 * Never written before M6, and the gap was invisible: both existing instrumented suites open
 * in-memory databases with `name = null`, which calls `Schema.create` and **never**
 * `Schema.migrate`. So the migration path had run on a developer's machine (via
 * `verifyMigrations`) and on no device, ever — while M6 is the first milestone to migrate tables
 * already holding real user sync state.
 *
 * `verifyMigrations` and this test check different things and neither replaces the other.
 * `verifyMigrations` diffs *DDL*: it applies every `.sqm` to the committed `1.db` and asserts the
 * result matches the `.sq` files. It cannot see a migration that is structurally perfect and
 * **mis-defaults the rows that were already there** — which is exactly what `4.sqm`'s
 * `lastSeenRemoteAt` and `5.sqm`'s `refusedUntil` are: `DEFAULT 0` on a table that already has
 * user data in it, where 0 has to mean *what the code did before the column existed*. A wrong
 * default on `lastSeenRemoteAt` reads every note in the library as deleted remotely on the first
 * poll after an update.
 *
 * ## Where the version-4 fixture came from
 *
 * [SCHEMA_4] and [MIGRATION_4_TO_5] were **generated, not transcribed**: the committed
 * `databases/1.db` with `1.sqm`, `2.sqm` and `3.sqm` applied to it, then dumped from
 * `sqlite_master` with the column comments stripped. So it is the schema a device that installed
 * before M3 and upgraded twice actually has, rather than someone's reading of the `.sq` files.
 *
 * ⚠️ **Never re-generate these from the current `.sq`.** They record what versions 4 and 5 looked
 * like, for the same reason a `.sqm` is never re-synced: the whole point is to start from what is
 * on a device that has not been updated yet.
 *
 * ⚠️ **The test name says "the current schema", not a number, deliberately.** It said "the
 * upgrade to 6" until M7 added `6.sqm` and made it 7 — at which point the name was false while
 * the assertions were still right, because they compare against `InkstoneDatabase.Schema.version`
 * rather than a literal. A name that has to be edited by every migration is a name that
 * eventually is not.
 *
 * M7's `6.sqm` is the first purely additive one: a `CREATE TABLE` and two indices, no `ALTER` on
 * a table holding user data. So it cannot mis-default an existing row the way `4.sqm` and
 * `5.sqm` could — which is the failure `verifyMigrations` is blind to and this file exists for.
 * It is still run here, because "cannot" is a claim and this is the thing that checks it.
 *
 * ```
 * ./gradlew :core:database:connectedDebugAndroidTest
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MigrationOnDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private var opened: SqlDriver? = null

    @AfterTest
    fun tearDown() {
        opened?.close()
        context.deleteDatabase(NAME)
    }

    @Test
    fun `a version 4 database with real data survives the upgrade to the current schema`() = runTest(dispatcher) {
        writeVersion4WithData()

        val database = upgradeInPlace()

        // Every row that was there before the migration, read back through the production stores.
        val bindings = SqlBindingStore(database, dispatcher)
        val binding = assertNotNull(bindings.find(NOTE), "the remote binding must survive the upgrade")
        assertEquals("Inkstone/Seminar.pdf", binding.remotePath)
        assertEquals("00000123oc", binding.remoteOcId)
        assertEquals("abc123", binding.lastSyncedEtag?.value)
        // SPEC §7.3's permanent-412 trap: the weak flag is the one piece of state that cannot be
        // recovered from the stored string, so a migration that dropped it would be silent.
        assertTrue(binding.lastSyncedEtag?.weak == true, "the weak-ETag flag survives")
        assertEquals(4L, binding.lastSyncedRev)

        val outbox = SqlOutboxStore(database, dispatcher).all()
        assertEquals(1, outbox.size, "queued work is not a schema detail and must not be dropped")
        assertEquals(OutboxOpType.UPLOAD, outbox.single().opType)
        assertEquals(2, outbox.single().attempts, "including how many attempts it has already spent")

        val index = LibraryIndex(database, dispatcher)
        val note = assertNotNull(index.note(NOTE))
        assertEquals("Seminar", note.title)
        assertEquals(NoteSyncState.PENDING_UPLOAD, note.syncState)
        assertEquals(1, index.allFolders().size, "the folder from 2.sqm's era is still there")
    }

    @Test
    fun `the new column on an existing binding defaults to never seen remotely`() = runTest(dispatcher) {
        writeVersion4WithData()

        val database = upgradeInPlace()

        // `4.sqm`'s one ALTER, read through the query the reconciler actually uses. 0 means "no
        // reconcile pass has ever seen this note", which is the truth for every row written before
        // M6 — and it is deliberately never on its own a reason to trash a note. Any other default
        // here is a library-wide mass trash on the first poll after an update.
        val bindings = SqlBindingStore(database, dispatcher)
        assertEquals(
            listOf(NOTE),
            bindings.notSeenSince(ACCOUNT, sinceEpochMillis = 1L).map { it.noteId },
            "a migrated binding has never been seen remotely, so it is a candidate and not a verdict",
        )
        assertTrue(
            bindings.notSeenSince(ACCOUNT, sinceEpochMillis = 0L).isEmpty(),
            "and 0 is the exact boundary — stamped-at-0 is not before 0",
        )

        // The new tables arrive empty rather than absent, which is what lets the first pass after an
        // upgrade look inside each unrecognised file once and then remember (`5.sqm`).
        assertTrue(SqlRemoteDirectoryStore(database, dispatcher).forAccount(ACCOUNT).isEmpty())
        assertTrue(SqlForeignRemoteStore(database, dispatcher).forAccount(ACCOUNT).isEmpty())
        assertTrue(SqlAttentionStore(database, dispatcher).open().isEmpty())

        // `9.sqm`'s table, same shape. ⚠️ An empty browser cache reads as *"I have never listed
        // this folder"* and **not** as *"this folder is empty"* — the two are different sentences
        // and only one of them is true after an upgrade, which is why `listedAt` is 0 here rather
        // than the upgrade's own timestamp.
        val browsed = SqlBrowsedFolderStore(database, dispatcher).childrenOf(ACCOUNT, "Inkstone")
        assertTrue(browsed.folders.isEmpty())
        assertEquals(0L, browsed.listedAtEpochMillis, "an upgrade has not listed anything")
    }

    @Test
    fun `a version 5 cursor row gains refusedUntil as not cooling down`() = runTest(dispatcher) {
        writeVersion5WithCursor()

        val database = upgradeInPlace()

        // `5.sqm`'s ALTER, on a row that existed before it. CLAUDE.md rule 5: 0 means "no 401 is
        // outstanding". A non-zero default would leave an upgraded device refusing to sync for an
        // hour with nothing wrong, and `verifyMigrations` — which only diffs DDL — cannot see it.
        val cursor = assertNotNull(SqlSyncCursorStore(database, dispatcher).find(ACCOUNT))
        assertEquals(0L, cursor.refusedUntilEpochMillis, "an upgrade does not put an account into a cooldown")
        assertEquals("fingerprint-v5", cursor.dataFingerprint, "and the row it was attached to survives")
    }

    // -----------------------------------------------------------------------
    // The fixture
    // -----------------------------------------------------------------------

    /**
     * Open through the production driver, which is what runs `Schema.migrate`.
     *
     * ⚠️ [AndroidSqliteDriver] is **lazy**: constructing it opens nothing and migrates nothing, so
     * a version check straight after the constructor reads the old number and a test written that
     * way asserts nothing at all. One statement is what forces `onUpgrade` — which is also true of
     * the app itself, where the migration happens on the first query after an update rather than at
     * startup.
     */
    private suspend fun upgradeInPlace(): InkstoneDatabase {
        val driver = AndroidSqliteDriver(InkstoneDatabase.Schema, context, NAME)
        opened = driver
        val database = InkstoneDatabase(driver)
        SqlBindingStore(database, dispatcher).forAccount(ACCOUNT)
        assertEquals(
            InkstoneDatabase.Schema.version,
            versionOf(),
            "the driver must have migrated the file rather than opened it as it was",
        )
        return database
    }

    private fun versionOf(): Long = SQLiteDatabase.openDatabase(
        context.getDatabasePath(NAME).path,
        null,
        SQLiteDatabase.OPEN_READONLY,
    ).use { it.version.toLong() }

    private fun writeVersion4WithData() = writeFixture(version = 4, ddl = SCHEMA_4)

    private fun writeVersion5WithCursor() = writeFixture(version = 5, ddl = SCHEMA_4 + MIGRATION_4_TO_5) { db ->
        db.execSQL(
            "INSERT INTO syncCursor (accountId, dataFingerprint, rootEtag, rootEtagWeak, " +
                "lastReconcileAt, lastFullAt) VALUES (?, 'fingerprint-v5', 'root-etag', 0, 1000, 1000)",
            arrayOf(ACCOUNT),
        )
    }

    private fun writeFixture(version: Int, ddl: String, extraRows: (SQLiteDatabase) -> Unit = {}) {
        context.deleteDatabase(NAME)
        val file = context.getDatabasePath(NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (statement in ddl.split(';')) {
                if (statement.isNotBlank()) db.execSQL(statement)
            }
            insertUserData(db)
            extraRows(db)
            // The version is what makes the next open an *upgrade*. Set last, so a half-written
            // fixture cannot masquerade as a complete one.
            db.version = version
        }
    }

    /** A small but real library: one folder, one note in it, one account, a binding, queued work. */
    private fun insertUserData(db: SQLiteDatabase) {
        db.execSQL(
            "INSERT INTO folder (id, parentId, name, nameFold, sortKey, colorArgb, icon, createdAt, " +
                "modifiedAt, deletedAt, deletedWithFolderId, coverKind, coverRef) " +
                "VALUES ('folder-1', NULL, 'Lectures', 'lectures', 'm', NULL, 'FOLDER', 1, 1, NULL, NULL, " +
                "'ICON', NULL)",
        )
        db.execSQL(
            "INSERT INTO note (id, title, titleFold, folderId, favourite, pageCount, syncState, localRev, " +
                "thumbnailRev, lastError, createdAt, modifiedAt, deletedAt, deletedWithFolderId, trashPath, " +
                "isCover) VALUES (?, 'Seminar', 'seminar', 'folder-1', 0, 3, 'PENDING_UPLOAD', 5, NULL, NULL, " +
                "1, 2, NULL, NULL, NULL, 0)",
            arrayOf(NOTE.value),
        )
        db.execSQL(
            "INSERT INTO account (id, serverUrl, loginName, uid, displayName, defaultFolder, state, " +
                "capabilities, lastError) VALUES (?, 'https://cloud.example.com', 'me@example.com', 'me', " +
                "'Me', 'Inkstone', 'ACTIVE', NULL, NULL)",
            arrayOf(ACCOUNT),
        )
        db.execSQL(
            "INSERT INTO remoteBinding (noteId, accountId, remotePath, remoteOcId, lastSyncedEtag, " +
                "lastSyncedEtagWeak, lastSyncedRev, lastUploadAt, lastChecksum, movedFromPath) " +
                "VALUES (?, ?, 'Inkstone/Seminar.pdf', '00000123oc', 'abc123', 1, 4, 99, NULL, NULL)",
            arrayOf(NOTE.value, ACCOUNT),
        )
        db.execSQL(
            "INSERT INTO outbox (noteId, opType, localRevAtEnqueue, attempts, nextAttemptAt, createdAt) " +
                "VALUES (?, 'UPLOAD', 5, 2, 0, 7)",
            arrayOf(NOTE.value),
        )
    }

    private companion object {
        const val NAME = "migration-on-device.db"
        const val ACCOUNT = "account-1"
        val NOTE = DocumentId("019fe000-0000-7000-8000-00000000note")

        /**
         * Schema version 4 — `1.db` with `1.sqm`, `2.sqm` and `3.sqm` applied, dumped from
         * `sqlite_master`. Generated; see this class's KDoc. Do not edit to match `Sync.sq`.
         */
        val SCHEMA_4 = """
            CREATE TABLE schemaMeta (
                id INTEGER NOT NULL PRIMARY KEY,
                schemaVersion INTEGER NOT NULL
            );
            CREATE TABLE folder (
                id                  TEXT    NOT NULL PRIMARY KEY,
                parentId            TEXT,
                name                TEXT    NOT NULL,
                nameFold            TEXT    NOT NULL,
                sortKey             TEXT    NOT NULL,
                colorArgb           INTEGER,
                icon                TEXT    NOT NULL,
                createdAt           INTEGER NOT NULL,
                modifiedAt          INTEGER NOT NULL,
                deletedAt           INTEGER,
                deletedWithFolderId TEXT
            , coverKind TEXT NOT NULL DEFAULT 'ICON', coverRef TEXT);
            CREATE INDEX folderByParent  ON folder(parentId);
            CREATE INDEX folderByDeleted ON folder(deletedAt);
            CREATE TABLE note (
                id                  TEXT    NOT NULL PRIMARY KEY,
                title               TEXT    NOT NULL,
                titleFold           TEXT    NOT NULL,
                folderId            TEXT,
                favourite           INTEGER NOT NULL DEFAULT 0,
                pageCount           INTEGER NOT NULL DEFAULT 1,
                syncState           TEXT    NOT NULL DEFAULT 'LOCAL_ONLY',
                localRev            INTEGER NOT NULL DEFAULT 0,
                thumbnailRev        INTEGER,
                lastError           TEXT,
                createdAt           INTEGER NOT NULL,
                modifiedAt          INTEGER NOT NULL,
                deletedAt           INTEGER,
                deletedWithFolderId TEXT,
                trashPath           TEXT
            , isCover INTEGER NOT NULL DEFAULT 0);
            CREATE INDEX noteByFolder   ON note(folderId);
            CREATE INDEX noteByDeleted  ON note(deletedAt);
            CREATE INDEX noteByModified ON note(modifiedAt);
            CREATE TABLE account (
                id            TEXT    NOT NULL PRIMARY KEY,
                serverUrl     TEXT    NOT NULL,
                loginName     TEXT    NOT NULL,
                uid           TEXT    NOT NULL,
                displayName   TEXT,
                defaultFolder TEXT    NOT NULL DEFAULT 'Inkstone',
                state         TEXT    NOT NULL DEFAULT 'ACTIVE',
                capabilities  TEXT,
                lastError     TEXT
            );
            CREATE TABLE remoteBinding (
                noteId             TEXT    NOT NULL PRIMARY KEY,
                accountId          TEXT    NOT NULL,
                remotePath         TEXT    NOT NULL,
                remoteOcId         TEXT,
                lastSyncedEtag     TEXT,
                lastSyncedEtagWeak INTEGER NOT NULL DEFAULT 0,
                lastSyncedRev      INTEGER NOT NULL DEFAULT 0,
                lastUploadAt       INTEGER NOT NULL DEFAULT 0,
                lastChecksum       TEXT,
                movedFromPath      TEXT
            );
            CREATE INDEX remoteBindingByAccount ON remoteBinding(accountId);
            CREATE TABLE outbox (
                noteId            TEXT    NOT NULL,
                opType            TEXT    NOT NULL,
                localRevAtEnqueue INTEGER NOT NULL,
                attempts          INTEGER NOT NULL DEFAULT 0,
                nextAttemptAt     INTEGER NOT NULL DEFAULT 0,
                createdAt         INTEGER NOT NULL,
                PRIMARY KEY (noteId, opType)
            );
            CREATE INDEX outboxDue ON outbox(nextAttemptAt);
        """.trimIndent()

        /**
         * `4.sqm`, so a version-**5** fixture can be built too — which is what makes `5.sqm`'s
         * `ALTER TABLE syncCursor ADD COLUMN refusedUntil` land on a row that was already there.
         */
        val MIGRATION_4_TO_5 = """
            CREATE TABLE remoteDirectory (
                accountId TEXT    NOT NULL,
                path      TEXT    NOT NULL,
                etag      TEXT,
                etagWeak  INTEGER NOT NULL DEFAULT 0,
                ocId      TEXT,
                seenAt    INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (accountId, path)
            );
            CREATE INDEX remoteDirectoryBySeen ON remoteDirectory(accountId, seenAt);
            CREATE TABLE syncCursor (
                accountId       TEXT    NOT NULL PRIMARY KEY,
                dataFingerprint TEXT,
                rootEtag        TEXT,
                rootEtagWeak    INTEGER NOT NULL DEFAULT 0,
                lastReconcileAt INTEGER NOT NULL DEFAULT 0,
                lastFullAt      INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE pendingDownload (
                noteId         TEXT    NOT NULL PRIMARY KEY,
                accountId      TEXT    NOT NULL,
                remotePath     TEXT    NOT NULL,
                remoteOcId     TEXT,
                remoteEtag     TEXT,
                remoteEtagWeak INTEGER NOT NULL DEFAULT 0,
                tempPath       TEXT    NOT NULL,
                fetchedAt      INTEGER NOT NULL
            );
            CREATE TABLE syncAttention (
                id                   TEXT    NOT NULL PRIMARY KEY,
                accountId            TEXT    NOT NULL,
                noteId               TEXT,
                kind                 TEXT    NOT NULL,
                detail               TEXT,
                conflictedCopyNoteId TEXT,
                remotePath           TEXT,
                createdAt            INTEGER NOT NULL,
                acknowledgedAt       INTEGER
            );
            CREATE INDEX syncAttentionOpen   ON syncAttention(acknowledgedAt, createdAt);
            CREATE INDEX syncAttentionByNote ON syncAttention(noteId);
            CREATE TABLE folderBinding (
                folderId       TEXT    NOT NULL PRIMARY KEY,
                accountId      TEXT    NOT NULL,
                remotePath     TEXT    NOT NULL,
                remoteOcId     TEXT,
                remoteEtag     TEXT,
                remoteEtagWeak INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX folderBindingByOcId ON folderBinding(accountId, remoteOcId);
            CREATE TABLE peerClock (
                noteId         TEXT    NOT NULL,
                peerDeviceId   TEXT    NOT NULL,
                highestCounter INTEGER NOT NULL DEFAULT 0,
                seenAt         INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (noteId, peerDeviceId)
            );
            ALTER TABLE remoteBinding ADD COLUMN lastSeenRemoteAt INTEGER NOT NULL DEFAULT 0;
            CREATE INDEX remoteBindingByOcId ON remoteBinding(remoteOcId);
        """.trimIndent()
    }
}
