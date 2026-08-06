package dev.inkstone.core.model

/*
 * SPEC §4.4 — page space ⇄ PDF user space.
 *
 * These belong here, not in `:android:pdf`, because §4.4 asks for a single
 * source of truth that is unit-tested: "define it once, in :core:model". The
 * two conversions are pure arithmetic with no PDF library in sight, so keeping
 * them platform-free costs nothing and means the iPad path gets them free.
 *
 * M1 does not call them — M4 does, when ink becomes PDF annotations. They are
 * written now because the milestone that discovers they are wrong is the one
 * that can least afford to be re-derived from scratch: SPEC §4.4 notes that
 * "annotations are 3mm off and rotated" is the single most reliable bug in this
 * class of app, and it is invisible until a real PDF is on screen.
 *
 * M4 still owes the acceptance test against a real `/Rotate 90` PDF with a
 * non-zero-origin CropBox (SPEC §4.4, last paragraph). What is tested here is
 * the arithmetic; what M4 tests is that a real producer agrees with it.
 */

/**
 * Normalise a `/Rotate` value to one of 0, 90, 180, 270.
 *
 * The PDF spec requires a multiple of 90 but permits negatives and values past
 * 360, and real files use both.
 */
public fun normalizeRotate(rotate: Int): Int {
    require(rotate % QUARTER_TURN == 0) { "/Rotate must be a multiple of 90, was $rotate" }
    return ((rotate % FULL_TURN) + FULL_TURN) % FULL_TURN
}

private const val QUARTER_TURN = 90
private const val HALF_TURN = 180
private const val THREE_QUARTER_TURN = 270
private const val FULL_TURN = 360

/**
 * SPEC §4.4 rule 3 — page size is the CropBox **with `/Rotate` applied**.
 *
 * A page with `/Rotate 90` and a 612×792 CropBox has a page space of 792×612.
 * `/Rotate 90` is extremely common in scanned lecture PDFs, which is why this
 * is a rule and not a footnote.
 */
public fun pageSizeOf(cropBox: PdfBox, rotate: Int): PageSize = when (normalizeRotate(rotate)) {
    QUARTER_TURN, THREE_QUARTER_TURN -> PageSize(cropBox.height, cropBox.width)
    else -> PageSize(cropBox.width, cropBox.height)
}

/**
 * Page space → PDF user space.
 *
 * Page space has its origin at the top-left of the **displayed** (rotated) page
 * with y down; PDF user space has its origin at the box's lower-left with y up.
 * SPEC §4.4 rule 4 gives the unrotated case,
 * `pageToPdfUser(p) = (cropBox.x0 + p.x, cropBox.y1 - p.y)`; the other three
 * quadrants fold the rotation in here rather than leaving it to the caller.
 *
 * The `/Rotate` a viewer applies is clockwise, so `/Rotate 90` sends the
 * unrotated top-left corner to the displayed top-right.
 */
public fun pageToPdfUser(p: PagePoint, box: PdfBox, rotate: Int): PdfPoint = when (normalizeRotate(rotate)) {
    0 -> PdfPoint(box.x0 + p.x, box.y1 - p.y)
    QUARTER_TURN -> PdfPoint(box.x0 + p.y, box.y0 + p.x)
    HALF_TURN -> PdfPoint(box.x1 - p.x, box.y0 + p.y)
    else -> PdfPoint(box.x1 - p.y, box.y1 - p.x)
}

/** PDF user space → page space. The exact inverse of [pageToPdfUser]. */
public fun pdfUserToPage(p: PdfPoint, box: PdfBox, rotate: Int): PagePoint = when (normalizeRotate(rotate)) {
    0 -> PagePoint(p.x - box.x0, box.y1 - p.y)
    QUARTER_TURN -> PagePoint(p.y - box.y0, p.x - box.x0)
    HALF_TURN -> PagePoint(box.x1 - p.x, p.y - box.y0)
    else -> PagePoint(box.y1 - p.y, box.x1 - p.x)
}

/**
 * One source page's coordinate system: the box a viewer displays, and the
 * quarter-turn it displays it through.
 *
 * *Added in M4.* The two conversions above take `(box, rotate)` as loose
 * arguments, which is fine for a unit test and wrong for a materializer that
 * converts a few thousand points per page — the pair travels together, must come
 * from the same page, and is exactly the thing SPEC §4.4 warns gets mismatched.
 *
 * **This is not persisted.** The note stores the resulting [PageSize] and a
 * [Background.SourcePdfPage] index; the box and the rotation are re-read from the
 * source PDF at materialization time, because the source PDF *is* the front of
 * `document.pdf` verbatim (SPEC §5.2) and so cannot disagree with itself. A copy
 * in the bundle would be a second source of truth for the one value the whole
 * "annotations are 3mm off" bug class comes from.
 */
public data class PdfPageGeometry(val cropBox: PdfBox, val rotate: Int) {

    /** SPEC §4.4 rule 3 — CropBox **with `/Rotate` applied**. */
    public val pageSize: PageSize get() = pageSizeOf(cropBox, rotate)

    public fun toPdfUser(p: PagePoint): PdfPoint = pageToPdfUser(p, cropBox, rotate)

    public fun toPage(p: PdfPoint): PagePoint = pdfUserToPage(p, cropBox, rotate)
}
