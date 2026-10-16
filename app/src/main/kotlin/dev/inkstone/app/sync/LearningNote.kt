package dev.inkstone.app.sync

import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageText
import dev.inkstone.core.storage.NoteSession

/**
 * What handwriting synthesis may see of one note — its live pages, folded, and their recognised
 * text — handed out by [NoteSessionHost.readForLearning] and **nothing that writes**.
 *
 * A wrapper rather than the session itself: a parameter that can only be given the safe thing needs
 * no KDoc telling its caller to behave (M6's gate-5 lesson).
 */
class LearningNote internal constructor(private val session: NoteSession) {

    val pageIds: List<PageId> get() = session.document.livePages.map { it.id }

    /** This device's revision of the note — what a pass records so it can skip the note next time. */
    val localRev: Long get() = session.document.localRev

    suspend fun page(pageId: PageId): FoldedPage = session.page(pageId)

    /** Null unless the text was recognised from exactly the ink on the page now. */
    suspend fun text(pageId: PageId): PageText? = session.recognisedText(pageId)
}
