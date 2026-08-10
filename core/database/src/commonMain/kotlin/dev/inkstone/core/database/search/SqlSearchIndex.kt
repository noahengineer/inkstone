package dev.inkstone.core.database.search

import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageText
import dev.inkstone.core.search.SearchHit
import dev.inkstone.core.search.SearchIndex
import dev.inkstone.core.search.SearchTerms
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * SPEC §8.4's index, over the real engine. See `Search.sq` for why there is no FTS table.
 *
 * `:core:database` implementing a port declared in `:core:search` is the same shape as
 * `SqlAttentionStore` implementing `:core:sync`'s `AttentionLog` (M6): the semantics live in
 * the module that can be tested without a driver, the rows live here.
 */
public class SqlSearchIndex(private val database: InkstoneDatabase, private val dispatcher: CoroutineDispatcher) :
    SearchIndex {

    override suspend fun indexPage(noteId: DocumentId, text: PageText): Unit = withContext(dispatcher) {
        // One transaction, and the delete is inside it. Recognition answers the whole page at
        // once, so the previous words are a previous *answer*, not a subset to add to —
        // leaving them is how a word the user rubbed out stays findable forever. Doing the
        // delete outside the transaction would leave a window in which the page is not
        // searchable at all.
        database.transaction {
            database.searchQueries.deletePage(noteId.value, text.pageId.value)
            for ((ordinal, word) in text.words.withIndex()) {
                if (word.text.isBlank()) continue
                database.searchQueries.insertWord(
                    noteId = noteId.value,
                    pageId = text.pageId.value,
                    ordinal = ordinal.toLong(),
                    word = word.text,
                    wordFold = SearchTerms.fold(word.text),
                    boxLeft = word.bounds.left.toDouble(),
                    boxTop = word.bounds.top.toDouble(),
                    boxRight = word.bounds.right.toDouble(),
                    boxBottom = word.bounds.bottom.toDouble(),
                )
            }
        }
    }

    override suspend fun forgetPage(noteId: DocumentId, pageId: PageId): Unit = withContext(dispatcher) {
        database.searchQueries.deletePage(noteId.value, pageId.value)
    }

    override suspend fun forgetNote(noteId: DocumentId): Unit = withContext(dispatcher) {
        database.searchQueries.deleteNote(noteId.value)
    }

    override suspend fun search(query: String, limit: Int): List<SearchHit> = withContext(dispatcher) {
        val terms = SearchTerms.terms(query)
        if (terms.isEmpty()) return@withContext emptyList()

        // Words first, titles second, and de-duplicated by note+page: a note whose ink and
        // whose title both match is one answer to the user's one question, and the ink hit is
        // the more useful of the two because it can be scrolled to.
        val hits = LinkedHashMap<Pair<String, String?>, SearchHit>()
        for (term in terms) {
            database.searchQueries.searchWords(term, SearchTerms.upperBound(term), limit.toLong()) {
                    noteId,
                    pageId,
                    word,
                    left,
                    top,
                    right,
                    bottom,
                    title,
                    modifiedAt,
                ->
                SearchHit(
                    noteId = DocumentId(noteId),
                    pageId = PageId(pageId),
                    word = word,
                    bounds = PageRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()),
                    noteTitle = title,
                    modifiedEpochMillis = modifiedAt,
                )
                // ⚠️ `getOrPut`, not `putIfAbsent`: the latter is a JVM-only extension and this
                // module links for iosArm64. It compiled, and Guardrail 2 caught it.
            }.executeAsList().forEach { hit -> hits.getOrPut(hit.noteId.value to hit.pageId?.value) { hit } }
        }

        for (term in terms) {
            val like = "%${escapeLike(term)}%"
            database.searchQueries.searchTitles(like, like, limit.toLong()) { id, title, modifiedAt ->
                SearchHit(
                    noteId = DocumentId(id),
                    pageId = null,
                    word = title,
                    bounds = null,
                    noteTitle = title,
                    modifiedEpochMillis = modifiedAt,
                )
            }.executeAsList().forEach { hit -> hits.getOrPut(hit.noteId.value to null) { hit } }
        }

        hits.values.sortedByDescending { it.modifiedEpochMillis }.take(limit)
    }

    /**
     * How many words the index holds — what the settings screen shows beside the model, and
     * the only honest way to tell *"recognition has not run yet"* from *"this note is blank"*.
     */
    public suspend fun wordCount(): Long = withContext(dispatcher) {
        database.searchQueries.countWords().executeAsOne()
    }

    /**
     * ⚠️ `LIKE` treats `%` and `_` as wildcards, so a user searching for `100%` would match
     * every title. The query declares `ESCAPE '\'` and this is the other half of that.
     */
    private fun escapeLike(term: String): String = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
