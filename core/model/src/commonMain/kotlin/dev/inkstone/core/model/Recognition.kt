package dev.inkstone.core.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * SPEC §8 — the vocabulary handwriting recognition and search are written in.
 *
 * It lives in :core:model because four modules have to agree on it and no two of
 * them may depend on each other: :android:ink produces [SampledStroke],
 * :core:search turns those into lines and indexes the result, :android:ocr does
 * the recognising, and :android:pdf writes SPEC §5.5's invisible text layer from
 * [PageText]. ML Kit types stay in :android:ocr and androidx.ink types stay in
 * :android:ink, exactly as dav4jvm stays in :core:sync.
 *
 * Block comment, not KDoc — a file-level KDoc attached to no declaration is a
 * dangling toplevel KDoc that ktlint cannot auto-correct.
 */

/**
 * An axis-aligned rectangle in **page space**: points (1/72"), origin at the
 * top-left of the CropBox with `/Rotate` applied, **y increasing downward**
 * (SPEC §4.4).
 *
 * So [top] is numerically *less* than [bottom], which is the opposite of a PDF
 * rectangle and the reason [PdfBox] is a different type. Nothing here converts;
 * `:android:pdf` does that at write time, once, in `PdfPageGeometry.toPdfUser`.
 */
public data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    public val width: Float get() = right - left

    public val height: Float get() = bottom - top

    public val centreX: Float get() = (left + right) / 2f

    public val centreY: Float get() = (top + bottom) / 2f

    public fun union(other: PageRect): PageRect = PageRect(
        left = min(left, other.left),
        top = min(top, other.top),
        right = max(right, other.right),
        bottom = max(bottom, other.bottom),
    )

    /**
     * How much of the **shorter** of the two boxes lies within the other's vertical band,
     * as a fraction in `0f..1f`.
     *
     * Normalising by the shorter box is what makes an `o` join the line its neighbouring
     * `l` established: dividing by the taller one instead scores that pair at roughly the
     * x-height over the ascender height and drops every short letter onto a line of its
     * own. Two boxes that do not overlap at all score 0.
     */
    public fun verticalOverlap(other: PageRect): Float {
        val shorter = min(height, other.height)
        if (shorter <= 0f) return 0f
        val overlap = min(bottom, other.bottom) - max(top, other.top)
        return (overlap / shorter).coerceIn(0f, 1f)
    }

    /** Negative when the boxes overlap horizontally; otherwise the gap between them. */
    public fun horizontalGapTo(other: PageRect): Float = max(left, other.left) - min(right, other.right)

    public companion object {
        /** @return null for an empty list — a box with no points has no position. */
        public fun around(points: List<PagePoint>): PageRect? {
            if (points.isEmpty()) return null
            var left = points[0].x
            var top = points[0].y
            var right = left
            var bottom = top
            for (point in points) {
                left = min(left, point.x)
                top = min(top, point.y)
                right = max(right, point.x)
                bottom = max(bottom, point.y)
            }
            return PageRect(left, top, right, bottom)
        }
    }
}

/**
 * One pen sample as the **recogniser** sees it (SPEC §8.1).
 *
 * ⚠️ **x, y and t only — no pressure, no tilt.** ML Kit's `Ink.Point` takes three
 * numbers and the renderer's extra channels have nowhere to go. That is not a
 * simplification made here; it is the API, and it is why image OCR is not a
 * substitute either — a bitmap throws away *t* as well.
 */
public data class InkSample(val x: Float, val y: Float, val tMillis: Long)

/**
 * One stroke reduced to what recognition and layout need: its samples and its box.
 *
 * ⚠️ [tMillis] on the samples is **normalised to the page**, not to the stroke.
 * `androidx.ink` measures a stroke's elapsed time from *its own* `MotionEvent`
 * `downTime`, so every stroke on a page comes back starting at ~0 and a page fed to
 * the recogniser raw looks like every word was written simultaneously. [StrokeSampler]
 * lays the strokes end to end in draw order; see its KDoc.
 *
 * @property bounds the box around the **samples** — the centreline — not the tessellated
 *   mesh. The mesh box is inflated by half a brush width on every side, which at a 3 pt
 *   nib is a tenth of an x-height of slop in the one measurement that decides both line
 *   clustering and where SPEC §5.5 puts the invisible word.
 */
public data class SampledStroke(val id: ObjectId, val samples: List<InkSample>, val bounds: PageRect)

/**
 * SPEC §2.2 — the platform capability `:core:` cannot implement.
 *
 * Reading `(x, y, t)` back out of a stroke means decoding SPEC §5.4's opaque blob,
 * which is `androidx.ink`'s native `StrokeInputBatch`: there is no JVM path, so this
 * interface is the seam and `:android:ink`'s `InkStrokeSampler` is the only
 * implementation. Same shape as [StrokeOutliner], for the same reason.
 *
 * ⚠️ **Implementations must lay strokes end to end on one page-wide clock** and must
 * skip anything that is not writing — the highlighter above all, which is a stroke like
 * any other and would otherwise be handed to the recogniser as a very wide letter.
 */
public fun interface StrokeSampler {
    /** @return one entry per recognisable stroke, in draw order; never on the ink path. */
    public suspend fun sample(page: FoldedPage): List<SampledStroke>
}

/**
 * One recognised word and the box of the strokes that produced it (SPEC §5.5).
 *
 * **This is the thing OCR cannot have.** OCRmyPDF rasterises, recognises, and then
 * guesses where the glyphs were; the box here came from the pen. It is what the
 * invisible text layer is positioned by and what a search hit highlights.
 */
public data class RecognisedWord(val text: String, val bounds: PageRect)

/** One recognised line — the unit ML Kit actually recognises (SPEC §8.1). */
public data class RecognisedLine(val text: String, val bounds: PageRect, val words: List<RecognisedWord>)

/**
 * A page's recognised text, as stored at `text/<pageId>.json` (SPEC §5.3).
 *
 * ## ⭐ [inkDigest] is what makes this file mergeable
 *
 * Recognised text is **derived data**, and until M7 the bundle had no way to say so.
 * `text/<pageId>.json` is named per *page*, so once recognition ships every device
 * produces the same entry name — and `NoteStore.materialize`'s union of carried
 * entries is *local-wins*, which is symmetric: each device would keep its own copy,
 * neither would be told, and there is no clock to order the two. SPEC §5.5 called
 * ordering these entries M7's prerequisite rather than its cleanup.
 *
 * A clock would work and would be wrong. The honest question is not *"whose text is
 * newer"* but *"is this text still about the ink that is on the page"* — and that has
 * an answer both devices compute identically: [inkDigest] over the folded page's ink
 * (`PageTextCodec.inkDigestOf`). A merge keeps whichever candidate matches the merged
 * page and drops both when neither does, so the two converge in one step without
 * anyone having to be believed. See `PageTextMerge`.
 *
 * The same field does a second job that a clock could not: it detects *local*
 * staleness. Erase a word and the digest moves, so the text stops being offered to
 * search and to the PDF immediately, rather than at whatever point recognition next
 * gets scheduled.
 *
 * @property recogniser which recogniser and language produced this, e.g.
 *   `mlkit-digital-ink/19.0.0/en`. Recorded so a model change can invalidate text that
 *   is still perfectly valid for its ink, and so a note that syncs between a device
 *   with a German model and one with an English model does not silently keep the
 *   wrong one. Never parsed — compared, and shown in a bug report.
 */
public data class PageText(
    val pageId: PageId,
    val inkDigest: String,
    val recogniser: String,
    val lines: List<RecognisedLine>,
) {
    /** Every word on the page, in reading order. What the index and the PDF both consume. */
    public val words: List<RecognisedWord> get() = lines.flatMap { it.words }

    /** The whole page as one string, lines separated by newlines. */
    public val text: String get() = lines.joinToString("\n") { it.text }

    /** True when this text was derived from exactly the ink [digest] describes. */
    public fun describes(digest: String): Boolean = inkDigest == digest
}

/**
 * Why a region of a page was **not** recognised.
 *
 * SPEC §8.2: *"Multi-column and rotated text are out of scope for v1 — detect and skip
 * rather than emitting nonsense."* Skipping silently would be indistinguishable from a
 * page with no writing on it, and the difference matters to anyone wondering why their
 * lecture notes are not searchable — so the reason is carried, counted, and logged.
 */
public enum class SkipReason {
    /** Strokes fell into two or more horizontally separated blocks. */
    MULTI_COLUMN,

    /** The line's baseline is too far from horizontal to be read as one line. */
    ROTATED,

    /** Too large, too tall or too sparse to be writing — a diagram, a bracket, a doodle. */
    NOT_WRITING,
}

/** A region [LineSegmenter] deliberately did not emit, and why. */
public data class SkippedRegion(val bounds: PageRect, val reason: SkipReason, val strokeCount: Int)

/**
 * One line of writing, ready to be recognised.
 *
 * @property writingArea the box handed to ML Kit's `WritingArea`. ⭐ **Sizing this to the
 *   line rather than the page is the single biggest accuracy lever in M7** (SPEC §8.1):
 *   the recogniser assumes one line of text, and a page-sized area tells it the writing
 *   is a hundredth of the height it really is.
 * @property strokes in reading order — left to right. Out-of-order strokes degrade the
 *   result, which is why this is a list and not a set.
 */
public data class TextLine(val strokes: List<SampledStroke>, val writingArea: PageRect) {
    public val isEmpty: Boolean get() = strokes.isEmpty()
}

/** What [LineSegmenter] makes of one page: the lines to recognise, and what it left out. */
public data class Segmentation(val lines: List<TextLine>, val skipped: List<SkippedRegion>) {
    public val isEmpty: Boolean get() = lines.isEmpty()

    public companion object {
        public val Empty: Segmentation = Segmentation(emptyList(), emptyList())
    }
}

/**
 * How near horizontal a baseline has to be to count as a line rather than as rotated text.
 *
 * Exposed so the segmenter and its tests name the same number, and so [SkipReason.ROTATED]
 * has a definition rather than a feeling.
 */
public fun isNearHorizontal(rise: Float, run: Float, maxSlope: Float): Boolean =
    abs(run) > 0f && abs(rise / run) <= maxSlope
