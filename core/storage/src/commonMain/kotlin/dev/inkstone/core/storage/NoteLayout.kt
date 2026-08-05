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
 * ├── journal.ops       append-only op log since the last materialization. Flushed ~2s.
 * └── snapshot.bundle   optional compaction snapshot (SPEC §4.7)
 * ```
 *
 * Serialization itself lands in M2. M0 defines the layout only, so that the
 * names are settled before anything writes to them.
 */
public object NoteLayout {

    public const val NOTES_DIR: String = "notes"

    /** The materialized, publishable PDF. Rewritten on close/upload/idle — never on the ink path. */
    public const val DOCUMENT_PDF: String = "document.pdf"

    /** Append-only op log. This is what autosave touches (SPEC §5.1). */
    public const val JOURNAL_OPS: String = "journal.ops"

    /** Compaction snapshot. A cache, never the truth (SPEC §4.7). */
    public const val SNAPSHOT_BUNDLE: String = "snapshot.bundle"

    /** The lossless bundle embedded in the PDF via `/Names /EmbeddedFiles` (SPEC §5.3). */
    public const val EMBEDDED_BUNDLE_NAME: String = "note.inkstone"

    public fun noteDir(id: DocumentId): String = "$NOTES_DIR/${id.value}"

    public fun documentPdf(id: DocumentId): String = "${noteDir(id)}/$DOCUMENT_PDF"

    public fun journal(id: DocumentId): String = "${noteDir(id)}/$JOURNAL_OPS"

    public fun snapshot(id: DocumentId): String = "${noteDir(id)}/$SNAPSHOT_BUNDLE"
}
