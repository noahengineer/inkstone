package dev.inkstone.core.database.settings

import dev.inkstone.core.database.InkstoneDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * ⭐ M10 — the app's global key-value settings, read whole and written whole.
 *
 * Backs the editor's remembered toolbar: the twelve-colour palette **in the user's own order**, the
 * saved stroke widths, the default width, a pen per brush, and the snap toggles. See `Setting.sq`
 * for why a generic table is right here and wrong for `folderSetting` beside it.
 *
 * ## It knows no keys and no meanings, and that is the module boundary
 *
 * Every value this currently stores describes something in `:android:ink` — `InkBrushKind`,
 * `EraseMode`, `PenConfig`. A `:core` module may not depend on an `:android` one (CLAUDE.md's
 * one-way dependency rule), so the encoding lives in `:app` next to the types it encodes and this
 * layer moves opaque strings. That also means the next caller with a completely different kind of
 * setting needs nothing here.
 *
 * ⚠️ **[putAll] is one transaction on purpose.** Reordering the palette and the brush it was picked
 * with is *one* user action; written as separate statements, a process kill between them leaves a
 * palette whose order does not match the pen that is selected — small, permanent, and impossible to
 * attribute later.
 *
 * ## Where this lives is a choice, not a constraint
 *
 * Two live precedents disagree: `LibraryIndex` is a wrapper in `:core:database`, and
 * `AutoRecordFolders` takes the `InkstoneDatabase` directly in `:app`. This follows `LibraryIndex`
 * because the caller is a process-wide singleton that must not see a blocking, row-typed API — but
 * the next person should know there is no rule here to obey.
 *
 * @param dispatcher where SQL runs. A parameter because `Dispatchers.IO` does not exist in common
 *   code; `:app` passes it, a test passes its own.
 */
public class SettingStore(private val database: InkstoneDatabase, private val dispatcher: CoroutineDispatcher) {

    private val queries = database.settingQueries

    /**
     * Every setting, as one snapshot.
     *
     * There is deliberately no per-key read. The key space is closed and known at compile time
     * (`Setting.sq`), so one query answers everything — and a per-key accessor is an invitation to
     * do a database round trip from a composable.
     */
    public suspend fun all(): Map<String, String> = withContext(dispatcher) {
        queries.selectSettings().executeAsList().associate { it.name to it.value_ }
    }

    /** Write [values], all of them or none. Keys absent from [values] are left alone. */
    public suspend fun putAll(values: Map<String, String>) {
        if (values.isEmpty()) return
        withContext(dispatcher) {
            database.transaction {
                values.forEach { (name, value) -> queries.upsertSetting(name, value) }
            }
        }
    }

    /** Forget [name], so the next read falls back to its default. */
    public suspend fun remove(name: String): Unit = withContext(dispatcher) {
        queries.deleteSetting(name)
    }
}
