package dev.inkstone.core.model

/**
 * SPEC §4.1 — the document, as metadata. Page contents live in [Page].
 *
 * [pages] is ordered by `(sortKey, pageId)` (SPEC §4.3) and is the authority on
 * page order; a page's position in the list is never its identity.
 *
 * @property title an LWW register at the sync layer. Held as a plain string here
 *   because a document's title is not part of any page's op log.
 * @property localRev SPEC §7.6 — bumped on every committed change, and the value
 *   an upload is validated against so a note edited mid-flight is not marked
 *   SYNCED with the edit left invisible to sync forever.
 */
public data class Document(
    val id: DocumentId,
    val title: String,
    val pages: List<PageRef>,
    val schemaVersion: Int = SCHEMA_VERSION,
    val localRev: Long = 0L,
    val deviceId: String = "",
    val createdEpochMillis: Long = 0L,
    val modifiedEpochMillis: Long = 0L,
) {

    /** Visible pages, in order. Deleted pages keep their slot in [pages] as tombstones. */
    public val livePages: List<PageRef> get() = pages.filterNot { it.deleted }

    public fun page(id: PageId): PageRef? = pages.firstOrNull { it.id == id }

    public companion object {
        /**
         * SPEC §5.8 — checked on every open. Bumped only together with a migration
         * and a committed fixture for the previous version (SPEC §13.1).
         */
        public const val SCHEMA_VERSION: Int = 1
    }
}

/**
 * A page's identity, order and liveness — everything the document level needs to
 * know without opening the page (SPEC §5.3, "one file per page gives lazy
 * loading").
 *
 * @property deleted a **tombstone**, not a removal (CLAUDE.md rule 1). Dropping
 *   the entry would lose the page's data outright, and would also let the page
 *   come back: a peer that still lists it would re-add it on the next merge, since
 *   the merge rule is "any page not present in a merged order is appended, never
 *   dropped" (SPEC §4.3). Deleting is therefore a flag plus a timestamp, and
 *   restoring is clearing them.
 */
public data class PageRef(
    val id: PageId,
    val sortKey: FractionalKey,
    val deleted: Boolean = false,
    val deletedEpochMillis: Long? = null,
)

/**
 * SPEC §4.1 — a page and its op log.
 *
 * The log is the truth; [FoldedPage] is what you draw. Nothing folds a page by
 * mutating it in place (SPEC §4.3, CLAUDE.md rule 9).
 */
public data class Page(val id: PageId, val size: PageSize, val background: Background, val ops: List<PageOp>)

/**
 * What is behind the ink.
 *
 * [SourcePdfPage] is the imported case and its [index] is a page index into the
 * original PDF's bytes, which are the front of `document.pdf` verbatim (SPEC
 * §5.2) — so a background is a reference, never a copy.
 */
public sealed interface Background {

    /** Nothing. White paper. */
    public data object Blank : Background

    /** One of the built-in rules — see [PageTemplate]. */
    public data class Template(val template: PageTemplate) : Background

    /** Page [index] of the note's original imported PDF. */
    public data class SourcePdfPage(val index: Int) : Background {
        init {
            require(index >= 0) { "PDF page index must not be negative, was $index" }
        }
    }
}

// Top-level, not in the companion: an enum entry's constructor runs before its own
// companion object is initialised, so a companion constant is unusable here.
private const val RULED_SPACING_PT = 24f
private const val GRID_SPACING_PT = 18f

/**
 * ROADMAP M2 — "templates: blank, ruled, grid, dot grid, Cornell".
 *
 * Five built-ins, as an enum rather than SPEC §4.2's `Template(ref)` string. A
 * string reference implies a registry of user-supplied templates, which is not in
 * v1 (SPEC §12 keeps the surface small deliberately) and would have to be synced —
 * a template referenced by a note whose template file is on the other tablet is a
 * blank page with no explanation. The enum is a closed set the format can render
 * anywhere. SPEC §4.1 has been corrected to match.
 *
 * The metrics live here, not in the renderer, so that a page rules identically on
 * screen, in a thumbnail and in the published PDF.
 */
public enum class PageTemplate(
    /** Line or cell spacing in page points. Zero for [BLANK]. */
    public val spacingPt: Float,
) {
    BLANK(0f),

    /** Horizontal rules, the default for handwriting. */
    RULED(RULED_SPACING_PT),

    /** Square grid. */
    GRID(GRID_SPACING_PT),

    /** A dot at each grid intersection — grid spacing, far less ink. */
    DOT_GRID(GRID_SPACING_PT),

    /**
     * Cornell notes: a cue column on the left and a summary band at the bottom,
     * with rules in the note area. The proportions are the standard ones.
     */
    CORNELL(RULED_SPACING_PT),
    ;

    public companion object {
        /** Fraction of page width taken by the Cornell cue column. */
        public const val CORNELL_CUE_FRACTION: Float = 0.235f

        /** Fraction of page height taken by the Cornell summary band. */
        public const val CORNELL_SUMMARY_FRACTION: Float = 0.222f

        /** Margin left clear at the top and bottom of a ruled page, in points. */
        public const val RULED_MARGIN_PT: Float = 36f
    }
}
