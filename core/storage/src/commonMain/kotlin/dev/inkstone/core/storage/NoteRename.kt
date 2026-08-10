package dev.inkstone.core.storage

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.Document
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.IdFactory

/**
 * ⭐ **SPEC §7.7 layer 2's writer: stamp a note's own LWW title register.**
 *
 * ## Why this exists at all
 *
 * Because until M6's review, layer 2 had no writer. The mechanism was complete — `titleClock` on
 * the document, `Document.resolveTitle`, `NoteSession.mergeDocument`, a `TITLE_OVERRIDDEN`
 * attention row — and the only caller of `NoteSession.rename` was `NoteEditor.rename`, which
 * nothing reached: the production rename was the library's, and the library renamed an index row.
 * So two devices renaming one note kept two titles indefinitely, each one MOVEing the server file
 * back to its own name whenever anything else made it re-derive the path, with no conflict, no
 * attention row and nothing in any log. This is the missing half.
 *
 * ## Where it must be called from
 *
 * Through whatever owns the note's single [NoteSession] — `NoteSessionHost`, which hands the rename
 * to a live editor when there is one. Two sessions over one note each hold their own pending ops
 * and folded pages, so whichever materialized second would write a bundle missing the other's
 * work. Nothing here can enforce that; `:core:storage` cannot know what an editor is.
 *
 * @param title already legal for a filename and non-empty — the caller's index and this document
 *   must hold the *same* string, or the peer learns a name the library never shows.
 * @return the document as it now stands, or null when [noteId] is not a note. The document is
 *   returned rather than the clock because the caller needs its `localRev` too: a rename is a
 *   committed change and the outbox will not upload one it cannot see (SPEC §7.6).
 */
public suspend fun NoteStore.renameNote(
    noteId: DocumentId,
    title: String,
    deviceId: String,
    ids: IdFactory,
    clock: Clock,
): Document? {
    val note = open(noteId) ?: return null
    // `NoteSession.rename` is a no-op for an unchanged title, so this early return does not change
    // the document — it saves the caller a whole bundle rewrite for a rename that renames nothing.
    // The *index* may still be wrong, which is why the caller reports regardless.
    if (note.document.title == title) return note.document

    val session = NoteSession.open(this, ids, clock, note, deviceId)
    session.rename(title)
    // ⚠️ **Materialized, not merely flushed.** A published PDF embeds `note.inkstone` as it is *on
    // disk* (`InkstonePdfMaterializer` reads the bundle file, not the session), so a title that
    // reached only the journal is a title no peer can see: the upload would carry a bundle still
    // holding the old one until the note next happened to close. The rename would look done here
    // and be invisible everywhere else — which is the failure this whole function exists to end.
    //
    // `materialize()` rather than `close(syncState)`: compaction has to refuse a note the server
    // has not caught up with (SPEC §4.7) and nothing in `:core:storage` knows a note's standing.
    session.materialize()
    return session.document
}
