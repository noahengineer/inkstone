package dev.inkstone.core.search

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageText

/**
 * SPEC §8.4 — search over recognised handwriting, note titles and folder names.
 *
 * ## ⭐ SPEC §8.4 said FTS5. Android does not have FTS5, and it would not have been enough anyway
 *
 * Two independent reasons, and the second is the one that actually decided the design.
 *
 * **It is not there.** Android's SQLite is built with `SQLITE_ENABLE_FTS3` and `FTS4` and not
 * `FTS5` — on API 35, SQLite 3.44.3, four years after FTS5 landed upstream. Verified at runtime
 * by `FtsAvailabilityTest` in `:core:database`, which creates the virtual table and asserts the
 * failure, and corroborated by Room shipping `@Fts3`/`@Fts4` annotations and no `@Fts5`. iOS's
 * system SQLite *does* have it, which would have made a KMP module that used it compile, link,
 * pass on the simulator and fail on every Android device.
 *
 * **And an FTS table stores text, not geometry.** SPEC §8.4 also requires every hit to carry a
 * page-space bounding box so a tap can scroll to and highlight the word. No FTS module of any
 * version holds that, so a per-word table with boxes has to exist regardless — and once it
 * does, it answers word and prefix queries by itself with an ordinary index. An FTS table
 * beside it would be a second copy of the same text, kept in step by hand, earning ranking.
 *
 * So: one table, one index, a prefix range scan. Boring, portable to every SQLite this app will
 * ever meet, and it makes the highlight exact instead of approximate. SPEC §8.4 has been
 * corrected.
 *
 * ## Degrading
 *
 * ROADMAP M7: *"delete the language model → app works fully, search degrades to titles."* That
 * is not a mode; it is what happens when there are no rows in the word table, and [search]
 * matches titles and folder names whether or not any page has ever been recognised.
 */
public interface SearchIndex {

    /**
     * Replace everything indexed for one page.
     *
     * ⚠️ **Replace, not merge.** Recognition re-reads a whole page, so the previous words are
     * not a subset to add to — they are a previous answer to the same question, and leaving
     * them behind is how a word the user erased goes on being findable forever.
     */
    public suspend fun indexPage(noteId: DocumentId, text: PageText)

    /** Forget one page — it was deleted, or its recognised text went stale (SPEC §5.5). */
    public suspend fun forgetPage(noteId: DocumentId, pageId: PageId)

    /** Forget a whole note. Trashing is not deleting, so this is for a note leaving the library. */
    public suspend fun forgetNote(noteId: DocumentId)

    /**
     * @param query what the user typed. Matching is per **word**, prefix-wise and case-folded:
     *   typing `hand` finds `handwriting`.
     * @return hits ordered most-recently-modified note first. Text hits and title hits are the
     *   same list because the user asked one question.
     */
    public suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): List<SearchHit>

    public companion object {
        public const val DEFAULT_LIMIT: Int = 100
    }
}

/**
 * One search result.
 *
 * @property pageId null for a note matched by its title or folder rather than by its ink — the
 *   degraded case, and the reason this is one type rather than two: a caller that had to handle
 *   `TitleHit` separately would be a caller that could forget to.
 * @property bounds where on the page the word is, in page space (SPEC §4.4). Null for a title
 *   match. This is what SPEC §8.4's *"tap to scroll to and highlight"* needs, and having it at
 *   all is a consequence of the ink being born-digital.
 */
public data class SearchHit(
    val noteId: DocumentId,
    val pageId: PageId?,
    val word: String,
    val bounds: PageRect?,
    val noteTitle: String,
    val modifiedEpochMillis: Long,
) {
    public val isTitleMatch: Boolean get() = pageId == null
}

/**
 * How a word is normalised before it is stored or looked up.
 *
 * ⚠️ **Case folding happens in Kotlin, never in SQL.** SQLite's `lower()` and `COLLATE NOCASE`
 * are ASCII-only, so a search for `ÜBUNG` would miss `Übung` — the same reason `Library.sq`
 * keeps `titleFold` and `nameFold` columns rather than folding in a query.
 */
public object SearchTerms {

    public fun fold(word: String): String = word.lowercase()

    /** The user's query split into terms. Empty when they typed only punctuation. */
    public fun terms(query: String): List<String> = query.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

    /**
     * The exclusive upper bound for a prefix range scan: `word >= p AND word < p.upperBound()`.
     *
     * ⚠️ The obvious `LIKE 'p%'` cannot use the index and degrades to a full scan of every word
     * in the library. Appending the largest code point Kotlin can express to the prefix keeps
     * it a range query. `￿` is a non-character and cannot occur in real recognised text.
     */
    public fun upperBound(prefix: String): String = prefix + '￿'

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
}
