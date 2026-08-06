package dev.inkstone.core.model

/*
 * SPEC §4.4 — the three coordinate spaces, as types.
 *
 * "Underspecified coordinate handling is the most reliable source of
 * 'annotations are 3mm off and rotated' bugs." The defence is that the spaces
 * are different Kotlin types, so a view coordinate cannot be persisted by
 * accident — it will not compile.
 *
 * | Space    | Units          | Origin                                   | Y   |
 * |----------|----------------|------------------------------------------|-----|
 * | View     | pixels         | top-left of the view                     | down|
 * | Page     | points (1/72") | top-left of the CropBox after /Rotate    | down|
 * | PDF user | points         | lower-left of the box                    | up  |
 *
 * Block comment, not KDoc — a file-level KDoc attached to no declaration is a
 * "dangling toplevel KDoc" that ktlint cannot auto-correct.
 */

/**
 * A point or vector in **view space**: pixels, origin at the view's top-left,
 * y increasing downward.
 *
 * Named [Offset] to match the signature SPEC §4.4 gives for [viewToPage], and
 * because it is used for both positions and deltas (a pan is an offset).
 */
public data class Offset(val x: Float, val y: Float) {
    public operator fun plus(other: Offset): Offset = Offset(x + other.x, y + other.y)

    public operator fun minus(other: Offset): Offset = Offset(x - other.x, y - other.y)

    public operator fun times(factor: Float): Offset = Offset(x * factor, y * factor)

    public companion object {
        public val Zero: Offset = Offset(0f, 0f)
    }
}

/**
 * A point in **page space**: points (1/72"), origin at the top-left of the
 * CropBox with `/Rotate` already applied, y increasing downward.
 *
 * SPEC §4.4 rule 1: *all persisted geometry is in page space.* Ink arrives in
 * view space and is converted on stroke start; nothing view-shaped is ever
 * written to disk.
 */
public data class PagePoint(val x: Float, val y: Float)

/**
 * A point in **PDF user space**: points, origin at the lower-left of the box,
 * y increasing **upward**. Only `:android:pdf` should ever hold one of these.
 */
public data class PdfPoint(val x: Float, val y: Float)

/**
 * The size of a page in page space — that is, the CropBox **with `/Rotate`
 * applied** (SPEC §4.4 rule 3). A page with `/Rotate 90` and a 612×792 CropBox
 * has a [PageSize] of 792×612.
 */
public data class PageSize(val widthPt: Float, val heightPt: Float) {
    init {
        require(widthPt > 0f && heightPt > 0f) { "Page size must be positive, was ${widthPt}x$heightPt" }
    }

    public companion object {
        /** US Letter, the default for a blank page. */
        public val Letter: PageSize = PageSize(612f, 792f)

        /** ISO A4 in points, rounded the way every PDF producer rounds it. */
        public val A4: PageSize = PageSize(595f, 842f)
    }
}

/**
 * A PDF rectangle (`/CropBox`, `/MediaBox`, `/BBox`) in PDF user space.
 *
 * SPEC §4.4 rule 2: **page size comes from CropBox, not MediaBox.** Viewers
 * display the CropBox; sizing from the MediaBox puts annotations wrong on every
 * PDF where the two differ, which is most scanned material. Fall back to
 * MediaBox only when CropBox is absent.
 *
 * The origin is *not* assumed to be zero — `[x0, y0]` is frequently non-zero on
 * cropped scans, and assuming otherwise is the other half of the same bug.
 */
public data class PdfBox(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val width: Float get() = x1 - x0
    val height: Float get() = y1 - y0

    init {
        require(x1 > x0 && y1 > y0) { "PdfBox must have positive extent, was $this" }
    }
}
