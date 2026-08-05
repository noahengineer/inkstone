package dev.inkstone.android.pdf

/**
 * ROADMAP M4 — PDF import and materialization. Empty in M0.
 *
 * **M4.0 is a spike, and it must be run and reported before anything here is
 * built** (SPEC Appendix B #1):
 *
 *  1. Does `androidx.pdf`'s `EditablePdfViewerFragment` write standard `/Ink`
 *     annotations readable by Acrobat, or a private representation?
 *  2. Does variable stroke width survive a save → reload cycle?
 *
 * Both yes → use it for annotation. Either no → rendering only, and annotations
 * get written via PdfBox-Android behind a `PdfWriter` interface. Either way, no
 * PdfBox or `androidx.pdf` type ever leaves this module (SPEC §2.1).
 *
 * The three things M4 must get right, recorded now because all three are
 * invisible until they are wrong:
 *
 *  - **Incremental update, not rewrite** (SPEC §5.2). The original bytes stay
 *    verbatim at the front of the file, so the embedded bundle needs no second
 *    copy of the source PDF. Without this a 20 MB lecture PDF becomes 40 MB+
 *    before a single stroke.
 *  - **Page size from CropBox with `/Rotate` applied** — not MediaBox
 *    (SPEC §4.4). Viewers display CropBox. Scanned lecture PDFs routinely have
 *    both a `/Rotate 90` and a non-zero-origin CropBox.
 *  - **Recovered mode REPLACES, never unions** (SPEC §5.6). A naive recovered
 *    import assigns new ObjectIds; if it then syncs, the other device's
 *    grow-only union duplicates every stroke.
 */
internal object PdfModule {
    const val IMPLEMENTED_IN: String = "M4"
}
