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

    /**
     * Compaction snapshots. A cache, never the truth (SPEC §4.7).
     *
     * **A directory, one entry per page — corrected in M6.** It was a single file per
     * *document*, while `NoteSession.compactIfDue` loops every live page writing a snapshot
     * to it, so on any multi-page note each page overwrote the previous one and
     * `readSnapshot` returned null for every page but the last. Nothing failed: a missing
     * snapshot is a full re-fold, which is correct, just slower.
     *
     * It became load-bearing in M6. SPEC §13.2 row 8 — *"A compacts while B is offline
     * holding older ops"* — asserts that an op older than the snapshot discards it and
     * re-folds, and with a per-document snapshot that row passed or failed according to
     * which page the test happened to pick. A green result would have proven nothing.
     */
    public const val SNAPSHOT_DIR: String = "snapshot"

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

    public fun snapshotDir(id: DocumentId): String = "${noteDir(id)}/$SNAPSHOT_DIR"

    public fun snapshot(id: DocumentId, pageId: PageId): String = "${snapshotDir(id)}/${pageId.value}.bundle"

    /**
     * Where a pulled remote file is staged before it is merged (M6).
     *
     * **Never `notes/<id>/document.pdf`.** Three separate failures collapse into that one
     * rule, and the first is the kind this project exists to prevent:
     *
     * 1. `writeBytesAtomically` writes through a temp file whose name is derived from the
     *    destination, so two writers aimed at one path share one temp file — and then
     *    atomically rename a **torn PDF** into place. The reconciler downloading while
     *    `MaterializeWorker` republishes is exactly two writers aimed at one path.
     * 2. The file's XMP `bundleSha256` describes the *remote* bundle. Written over
     *    `document.pdf` it would disagree with the merged `note.inkstone` beside it, and the
     *    next open would read `Recovered(BUNDLE_HASH_MISMATCH)` on a note that is perfectly
     *    intact (SPEC §5.6).
     * 3. Materialization truncates back to `originalLength` from the marker in whatever file
     *    is there (SPEC §5.2), so the next local publish would silently rebase this note on
     *    a *peer's* base bytes.
     */
    public const val DOWNLOAD_DIR: String = "download"

    public fun download(id: DocumentId): String = "$DOWNLOAD_DIR/${id.value}.pdf"

    /**
     * This install's device id (SPEC §4.1's Lamport tiebreak) — see [DeviceIdentity].
     *
     * A sibling of `notes/`, because it belongs to the install and must never travel with a
     * note that is copied, duplicated, published or synced.
     */
    public const val DEVICE_ID: String = "device.id"

    public fun undo(id: DocumentId): String = "${noteDir(id)}/$UNDO_JSON"

    public fun viewState(id: DocumentId): String = "${noteDir(id)}/$VIEW_JSON"

    public fun trashEntry(id: DocumentId, deletedEpochMillis: Long): String =
        "$TRASH_DIR/$deletedEpochMillis-${id.value}"

    /**
     * Where a **stub** `notes/<id>/` is set aside so a restore can proceed (M6). See
     * [NoteTrash.restore].
     *
     * A stub is a note directory with no `note.inkstone` in it: the shape a live [NoteSession]
     * leaves behind when the note is trashed underneath it and its next autosave appends to
     * `journal.ops` at the old path (every write does `mkdirs` first). It holds ops with no bundle
     * to fold them onto, so it is not a note and nothing can open it — but it is the user's ink, so
     * it goes to the trash rather than to `delete` (CLAUDE.md rule 1) and expires on the same
     * thirty-day clock as everything else in there.
     *
     * ⚠️ The suffix is what keeps it **invisible to [NoteTrash.restore]**: `entries()` parses the
     * name after the millis as the id, so this entry's id is `<uuid>.orphaned`, which no note has
     * and no restore can therefore pick up in place of the real thing. A UUIDv7 cannot contain a
     * `.`, so the two can never be confused. [NoteTrash.purgeExpired] reads only the millis and
     * sweeps it like any other entry.
     */
    public fun orphanedStub(id: DocumentId, setAsideEpochMillis: Long): String =
        "${trashEntry(id, setAsideEpochMillis)}$ORPHANED_SUFFIX"

    /** See [orphanedStub]. Not a legal part of a [DocumentId], which is the point. */
    public const val ORPHANED_SUFFIX: String = ".orphaned"

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

    // -----------------------------------------------------------------------
    // M12 — recorded lectures (docs/M12-AUDIO.md)
    // -----------------------------------------------------------------------

    /**
     * ⭐ A recording's two files: the audio, and the stroke↔audio timeline beside it.
     *
     * ```
     * recordings/<recordingId>.opus       Ogg/Opus, written by the foreground service
     * recordings/<recordingId>.timeline   append-only, one line per stroke
     * ```
     *
     * ## ⚠️ A **sibling** of `notes/`, and not under `notes/<documentId>/`
     *
     * The doc's §3 sketch writes `recordings/<recordingId>.timeline` without saying where the
     * directory hangs, and putting it inside the note directory — which is the obvious
     * reading — destroys lectures three separate ways, because a note directory is something
     * this app **moves and copies as a unit**:
     *
     * 1. [NoteTrash.trash] moves `notes/<id>/` wholesale into [TRASH_DIR], and
     *    `purgeExpired` **hard-deletes** the entry after thirty days. An un-uploaded lecture
     *    inside it is then gone for good — while doc §5 requires the opposite, that trashing
     *    a note whose lecture has not uploaded yet still delivers the lecture.
     * 2. `NoteDuplicate` copies the note directory, so duplicating a note would silently
     *    duplicate every hour of audio in it.
     * 3. [orphanedStub] and the merge paths both rewrite that directory's contents.
     *
     * Audio is the one thing in this app that cannot be re-derived from anything else
     * (doc §9: *"you cannot re-record a lecture"*), so it lives where nothing sweeps.
     * `recordings/` is a sibling for the same reason [FOLDERS_DIR] is: it is not a cache, and
     * losing it loses something the user cannot get back.
     *
     * Which note a recording belongs to is a column on the `recording` row, never the path —
     * CLAUDE.md's *"identity is never derived from a file path"*, and the same rule doc §6
     * states for the remote side.
     */
    public const val RECORDINGS_DIR: String = "recordings"

    /** Ogg, never MP4 — an MP4 killed mid-lecture is unplayable (doc §9). */
    public const val AUDIO_EXTENSION: String = ".opus"

    /** The append-only stroke↔audio log. Not an op, not a bundle entry (doc §3). */
    public const val TIMELINE_EXTENSION: String = ".timeline"

    public fun recordingAudio(recordingId: String): String = "$RECORDINGS_DIR/$recordingId$AUDIO_EXTENSION"

    public fun recordingTimeline(recordingId: String): String = "$RECORDINGS_DIR/$recordingId$TIMELINE_EXTENSION"
}
