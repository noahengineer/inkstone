package dev.inkstone.core.storage

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.model.PageId

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
 * ├── undo.json         local undo/redo stacks — per device, never synced
 * └── view.json         which page this device last had open — per device, never synced
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

    /**
     * Where the reader was — which page this device last had open.
     *
     * Local and per-device for the same reason [UNDO_JSON] is: two tablets reading the same
     * 200-page notebook are in different places in it, and syncing this would have each one
     * yank the other back to where it was. It is also pure convenience, so it is stored
     * beside the note rather than inside the bundle where a corrupt byte would matter.
     */
    public const val VIEW_JSON: String = "view.json"

    /**
     * SPEC §5.7 — thumbnails are a cache, not bundle content.
     *
     * ```
     * cache/thumbs/<documentId>.webp             the library thumbnail
     * cache/thumbs/<documentId>/<pageId>.webp    page thumbnails (reorder UI, search jump)
     * ```
     *
     * **Never inside the bundle inside the PDF.** SPEC §5.7 states the reason and M3's
     * acceptance criterion is the consequence: drawing a 500-note library would mean
     * opening 500 PDFs and extracting 500 ZIPs, and 60 fps is not reachable from there.
     *
     * Under the same root as `notes/` rather than the platform cache directory. The
     * platform one gets cleared by the OS, which for a *cache* is correct — but it is also
     * a different filesystem on some devices, and [FileSystem] is single-rooted precisely
     * so that write-temp-then-rename is always a rename (CLAUDE.md rule 10). Losing the
     * whole thumbnail cache to a storage sweep is a worse trade than the disk it costs, and
     * [ThumbnailCache] bounds the size itself.
     */
    public const val THUMBNAIL_CACHE_DIR: String = "cache/thumbs"

    /** Image format for thumbnails. WebP: a third of PNG at the same visual quality. */
    public const val THUMBNAIL_EXTENSION: String = ".webp"

    /** The library thumbnail — page one of the note. */
    public fun coverThumbnail(id: DocumentId): String = "$THUMBNAIL_CACHE_DIR/${id.value}$THUMBNAIL_EXTENSION"

    /** Where a note's page thumbnails live. Deleted wholesale when the note changes. */
    public fun pageThumbnailDir(id: DocumentId): String = "$THUMBNAIL_CACHE_DIR/${id.value}"

    public fun pageThumbnail(id: DocumentId, pageId: PageId): String =
        "${pageThumbnailDir(id)}/${pageId.value}$THUMBNAIL_EXTENSION"

    /** CLAUDE.md rule 1 — no hard deletes. 30-day retention, see [NoteTrash]. */
    public const val TRASH_DIR: String = "trash"

    public fun noteDir(id: DocumentId): String = "$NOTES_DIR/${id.value}"

    public fun documentPdf(id: DocumentId): String = "${noteDir(id)}/$DOCUMENT_PDF"

    public fun bundle(id: DocumentId): String = "${noteDir(id)}/$EMBEDDED_BUNDLE_NAME"

    public fun journal(id: DocumentId): String = "${noteDir(id)}/$JOURNAL_OPS"

    public fun snapshot(id: DocumentId): String = "${noteDir(id)}/$SNAPSHOT_BUNDLE"

    public fun undo(id: DocumentId): String = "${noteDir(id)}/$UNDO_JSON"

    public fun viewState(id: DocumentId): String = "${noteDir(id)}/$VIEW_JSON"

    public fun trashEntry(id: DocumentId, deletedEpochMillis: Long): String =
        "$TRASH_DIR/$deletedEpochMillis-${id.value}"

    /**
     * Where a folder's own files live — currently just an imported cover image.
     *
     * Beside `notes/` rather than under the thumbnail cache, because this one is **not** a
     * cache: the user chose the picture and it cannot be regenerated. Evicting it would
     * lose it.
     */
    public const val FOLDERS_DIR: String = "folders"

    public fun folderDir(id: FolderId): String = "$FOLDERS_DIR/${id.value}"

    /**
     * A folder's imported cover image.
     *
     * The name carries no extension: the bytes are re-encoded to a known format on import,
     * so nothing downstream has to sniff a file the user picked.
     */
    public fun folderCoverImage(id: FolderId): String = "${folderDir(id)}/cover$THUMBNAIL_EXTENSION"
}
