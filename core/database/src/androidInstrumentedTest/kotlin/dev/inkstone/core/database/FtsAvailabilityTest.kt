package dev.inkstone.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ⭐ **SPEC §8.4 said FTS5. Android does not have FTS5.** This test is the evidence, and it
 * is here so the claim is re-checked on every device the suite ever runs on rather than
 * remembered from one afternoon in M7.
 *
 * Android's SQLite is built with `SQLITE_ENABLE_FTS3` and `SQLITE_ENABLE_FTS4` and **not**
 * `SQLITE_ENABLE_FTS5`. It is not a version thing — the emulator this was found on runs
 * SQLite 3.44.3 on API 35, which is four years newer than FTS5's arrival upstream. Scanning
 * `/system/lib64/libsqlite.so` finds `fts3` and `fts4` and **zero** occurrences of `fts5`,
 * `bm25` or `highlight`; this test is the runtime half of that, because a string scan is
 * archaeology and `CREATE VIRTUAL TABLE` is a fact. (The same reason Room ships `@Fts3` and
 * `@Fts4` annotations and no `@Fts5`.)
 *
 * **Why it matters beyond the module name.** The design that falls out is not "use FTS4
 * instead" — see `SearchIndex` in `:core:search`. SPEC §8.4 also requires every hit to carry
 * a page-space bounding box so a tap can scroll to and highlight the word, and *no* FTS
 * module stores geometry. The per-word table that has to exist for the boxes already answers
 * word and prefix queries, so an FTS table alongside it would be a second index over the
 * same text, kept in sync by hand, earning only ranking. SPEC §8.4 has been corrected.
 *
 * ⚠️ **iOS is the opposite way round and that is not a reason to depend on it.** Apple's
 * system SQLite *does* ship FTS5, so a KMP module that used it would compile, link, pass on
 * the simulator and fail on every Android device — the exact shape of failure Guardrail 2
 * exists to catch in the other direction.
 *
 * ```
 * ./gradlew :core:database:connectedDebugAndroidTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class FtsAvailabilityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var opened: SQLiteDatabase? = null

    @AfterTest
    fun tearDown() {
        opened?.close()
        context.deleteDatabase(NAME)
    }

    @Test
    fun `fts5 is not available on this platform`() {
        val db = open()

        val failure = runCatching { db.execSQL("CREATE VIRTUAL TABLE probe5 USING fts5(body)") }
            .exceptionOrNull()

        assertTrue(
            failure is SQLiteException,
            "FTS5 is now available on this device. That is good news and a decision to revisit, " +
                "not a test to delete: see SearchIndex, which needs per-word boxes either way. " +
                "SQLite ${versionOf(db)} accepted CREATE VIRTUAL TABLE ... USING fts5.",
        )
        assertTrue(
            failure.message.orEmpty().contains("no such module", ignoreCase = true),
            "expected a missing-module error, got: ${failure.message}",
        )
    }

    @Test
    fun `fts4 is available on this platform`() {
        val db = open()

        // Not because anything uses it — nothing does. It is the control: without it, a
        // failure above could equally mean this test cannot create a virtual table at all.
        db.execSQL("CREATE VIRTUAL TABLE probe4 USING fts4(body)")
        db.execSQL("INSERT INTO probe4(body) VALUES ('the quick brown fox')")

        db.rawQuery("SELECT body FROM probe4 WHERE probe4 MATCH 'brown'", null).use { cursor ->
            assertTrue(cursor.moveToFirst(), "FTS4 matched nothing, so neither module is usable here")
            assertEquals("the quick brown fox", cursor.getString(0))
        }
    }

    @Test
    fun `the search index needs no virtual table at all`() {
        val db = open()

        // What :core:search actually does, proven against the real engine: a prefix range scan
        // over an ordinary index. The `word < prefix + ￿` upper bound is the half of it
        // that is easy to get wrong, and it is the half that makes typing 'hand' find
        // 'handwriting' without a LIKE scan of the table.
        db.execSQL("CREATE TABLE probeWord (word TEXT NOT NULL, box TEXT NOT NULL)")
        db.execSQL("CREATE INDEX probeWordByWord ON probeWord(word)")
        for (word in listOf("hand", "handwriting", "handle", "handy", "hazel", "ink")) {
            db.execSQL("INSERT INTO probeWord(word, box) VALUES (?, 'box')", arrayOf(word))
        }

        db.rawQuery(
            "SELECT word FROM probeWord WHERE word >= ? AND word < ? ORDER BY word",
            arrayOf("hand", "hand￿"),
        ).use { cursor ->
            val found = buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            assertEquals(listOf("hand", "handle", "handwriting", "handy"), found)
            assertFalse("hazel" in found, "the upper bound must stop at the prefix")
        }
    }

    // -----------------------------------------------------------------------

    private fun open(): SQLiteDatabase {
        context.deleteDatabase(NAME)
        val file = context.getDatabasePath(NAME)
        file.parentFile?.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(file, null).also { opened = it }
    }

    private fun versionOf(db: SQLiteDatabase): String = db.rawQuery("SELECT sqlite_version()", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else "unknown"
    }

    private companion object {
        const val NAME = "fts-availability.db"
    }
}
