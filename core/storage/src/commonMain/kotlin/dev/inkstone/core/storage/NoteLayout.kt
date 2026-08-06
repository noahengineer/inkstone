package dev.inkstone.core.storage

import dev.inkstone.core.model.DocumentId

/**
 * SPEC §5.1 — the working format.
 *
 * The distinction the whole storage design turns on: autosave cannot rewrite a
 * 40 MB PDF every two seconds, so the PDF is not the working format.
 *
 * ```
 * notes/<documentId>/
 * ├── document.pdf      the materialized, publishable file. May be one session stale.
 * ├── note.inkstone     the lossless bundle (SPEC §5.3)
 * ├── journal.ops       append-only op log since the last materialization. Flushed ~2s.
 * ├── snapshot.bundle   optional compaction snapshot (SPEC §4.7)
 * └── undo.json         local undo/redo stacks — per device, never synced
 * ```
 *
 * ## Two notes on this, added in M2
 *
 * **The bundle is a file of its own here.** SPEC §5.3 has it embedded in
 * `document.pdf` via `/Names /EmbeddedFiles`, and M4 puts it there — that is the
 * published format. Until PDF materialization exists there is nothing to embed it in,
 * and ROADMAP M2 says so explicitly: *"for M2 the materialized artifact is just the
 * bundle"*. M4 changes where these bytes live, not what they are.
 *
 * **`undo.json` is not in the bundle.** SPEC §4.6 makes undo local-only and
 * per-device, so putting the stacks in a file that syncs would ship one tablet's undo
 * history to the other, where it means nothing and where the two would overwrite each
 * other. It is also the largest thing a bundle could gain for no remote benefit: an
 * undone scribble-erase carries a whole absolute region.
 */
public object NoteLayout {

    public const val NOTES_DIR: String = "notes"

    /** The materialized, publishable PDF. Rewritten on close/upload/idle — never on the ink path. */
    public const val DOCUMENT_PDF: String = "document.pdf"

    /** Append-only op log. This is what autosave touches (SPEC §5.1). */
    public const val JOURNAL_OPS: String = "journal.ops"

    /** Compaction snapshot. A cache, never the truth (SPEC §4.7). */
    public const val SNAPSHOT_BUNDLE: String = "snapshot.bundle"

    /** The lossless bundle. M4 embeds these bytes in the PDF via `/Names /EmbeddedFiles` (SPEC §5.3). */
    public const val EMBEDDED_BUNDLE_NAME: String = "note.inkstone"

    /** Local, per-device undo/redo stacks (SPEC §4.6). Never uploaded. */
    public const val UNDO_JSON: String = "undo.json"

    /** SPEC §5.7 — thumbnails are a cache, not bundle content. */
    public const val THUMBNAIL_CACHE_DIR: String = "cache/thumbs"

    /** CLAUDE.md rule 1 — no hard deletes. 30-day retention, see [NoteTrash]. */
    public const val TRASH_DIR: String = "trash"

    public fun noteDir(id: DocumentId): String = "$NOTES_DIR/${id.value}"

    public fun documentPdf(id: DocumentId): String = "${noteDir(id)}/$DOCUMENT_PDF"

    public fun bundle(id: DocumentId): String = "${noteDir(id)}/$EMBEDDED_BUNDLE_NAME"

    public fun journal(id: DocumentId): String = "${noteDir(id)}/$JOURNAL_OPS"

    public fun snapshot(id: DocumentId): String = "${noteDir(id)}/$SNAPSHOT_BUNDLE"

    public fun undo(id: DocumentId): String = "${noteDir(id)}/$UNDO_JSON"

    public fun trashEntry(id: DocumentId, deletedEpochMillis: Long): String =
        "$TRASH_DIR/$deletedEpochMillis-${id.value}"
}
