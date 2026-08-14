package dev.inkstone.android.pdf

import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PdfPageGeometry
import dev.inkstone.core.model.normalizeRotate

/**
 * ⭐ SPEC §4.4 as a **matrix**, which is what M8c needed and M4 did not.
 *
 * ## Why a matrix and not another point conversion
 *
 * Everything this module published before M8c was a *polygon*: a stroke's outline, a template's
 * rules, an eraser-subtracted contour. A polygon can be converted point by point, so
 * [PdfPageGeometry.toPdfUser] was the whole interface and the object's own [Matrix3] never
 * appeared here — every stroke is authored directly in page space at the identity (SPEC §4.4
 * rule 1), so there was nothing to fold in.
 *
 * A text box and an image are not polygons. Text is placed by a **text matrix** and an image by
 * a **`cm`**, and both take the whole affine at once. Converting their corners point-by-point
 * would tell you where the box lands and nothing about which way up the letters are.
 *
 * ⚠️ **`placeWord` wrote `1 0 0 1 x y Tm` for two milestones**, which is the identity rotation
 * with the origin converted — correct on a plain page and wrong on every `/Rotate 90` scan, where
 * the invisible layer's words ran across the handwriting at ninety degrees. It never showed,
 * because the layer is invisible and `pdftotext` reports the characters whatever the matrix says.
 * Two independent things have to be right for a text feature and they fail independently
 * (docs/M8-TEXT.md §3.5); this file is the half that only a *renderer* can see.
 *
 * ## The three spaces, and the one composition
 *
 * ```
 * text space  --Flip-->  object space  --M-->  page space  --P-->  PDF user space
 * ```
 *
 * - **P** ([pageToUser]) is SPEC §4.4's own conversion, written as a matrix instead of a `when`
 *   over four cases returning points. [PdfPlacementTest] asserts the two agree on every fixture
 *   page, so this is a second *form* of that rule and not a second copy of it.
 * - **M** is the object's absolute `SetTransform` register (CLAUDE.md rule 8).
 * - **Flip** is the one that is easy to forget: page space is y-**down** (SPEC §4.4 rule 1) and a
 *   glyph's own space is y-**up**. Omit it and every text box comes out mirrored — which, on a
 *   single line of symmetric characters, looks like a font problem.
 */
internal object PdfPlacement {

    /**
     * Page space → PDF user space, as the matrix that [PdfPageGeometry.toPdfUser] applies.
     *
     * Rigid: a quarter turn, a reflection and a translation, determinant ±1. That matters because
     * the *scale* a glyph is drawn at then comes entirely from the object's own transform and the
     * `Tf` size, so a text box on a rotated page is the same size as one on a plain page.
     */
    fun pageToUser(geometry: PdfPageGeometry): Matrix3 {
        val box = geometry.cropBox
        return when (normalizeRotate(geometry.rotate)) {
            0 -> Matrix3(1f, 0f, box.x0, 0f, -1f, box.y1)
            QUARTER_TURN -> Matrix3(0f, 1f, box.x0, 1f, 0f, box.y0)
            HALF_TURN -> Matrix3(-1f, 0f, box.x1, 0f, 1f, box.y0)
            else -> Matrix3(0f, -1f, box.x1, -1f, 0f, box.y1)
        }
    }

    /** The object's own space → PDF user space: [pageToUser] after the object's absolute matrix. */
    fun objectToUser(geometry: PdfPageGeometry, transform: Matrix3): Matrix3 = pageToUser(geometry) * transform

    /**
     * The `Tm` for one line of text whose baseline starts at ([leftPt], [baselinePt]) in the
     * object's own space.
     *
     * ⚠️ The trailing [FLIP] is not cosmetic. Without it the glyphs are drawn with their ascenders
     * pointing the way page space's y points — down — and a viewer shows the line mirrored about
     * its own baseline. It is the same y-flip [pageToUser] already carries for the page, applied
     * once more for the glyph, and the two cancel on an unrotated page: the result there is
     * `1 0 0 1 x y`, byte-identical to what M4 wrote, which is why the invisible layer's existing
     * assertions did not have to change.
     */
    fun textLine(geometry: PdfPageGeometry, transform: Matrix3, leftPt: Float, baselinePt: Float): Matrix3 =
        objectToUser(geometry, transform) * Matrix3.translate(leftPt, baselinePt) * FLIP

    /**
     * The `cm` that maps a PDF image XObject's unit square onto an object [widthPt] × [heightPt]
     * in the object's own space, showing only the source rectangle ([left], [top])–([right],
     * [bottom]) — normalised fractions of the **source image**, y down from its top row
     * (`ImageCrop`, SPEC §4.9.2).
     *
     * ⚠️ **A PDF image's unit square has `v = 1` at the image's *first* row**, not its last: the
     * format draws image space y-down into a y-up square. Getting that backwards produces a
     * picture that is upside down and nothing else — no error, and symmetric enough test images
     * do not show it, which is why the fixture in [PdfImageTest] is deliberately not symmetric.
     *
     * The crop *fits*: its box is scaled to fill the object's box, which is what a crop looks like
     * in every editor a user has met. Clipping to the exact shape is the caller's job — a polygon
     * crop fits its bounding box here and is masked by the clip path (SPEC §4.9.2).
     */
    @Suppress("LongParameterList")
    fun unitImageToObject(
        widthPt: Float,
        heightPt: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Matrix3 {
        val spanX = (right - left).takeIf { it > MIN_CROP_SPAN } ?: 1f
        val spanY = (bottom - top).takeIf { it > MIN_CROP_SPAN } ?: 1f
        return Matrix3(
            m00 = widthPt / spanX,
            m01 = 0f,
            m02 = -left * widthPt / spanX,
            m10 = 0f,
            m11 = -heightPt / spanY,
            m12 = (1f - top) * heightPt / spanY,
        )
    }

    /**
     * `a b c d e f` — the six operands `cm` and `Tm` take, in the order PDF writes them.
     *
     * ⚠️ **PDF's matrix is row-vector and [Matrix3]'s is column-vector, so `b` and `c` swap.**
     * Emitting `m00 m01 m02 m10 m11 m12` compiles, parses, renders, and transposes every rotation
     * — a 30° text box comes out rotated -30° and sheared, which reads as a geometry bug in the
     * editor rather than as a transcription bug here.
     */
    fun args(matrix: Matrix3): String = listOf(
        matrix.m00,
        matrix.m10,
        matrix.m01,
        matrix.m11,
        matrix.m02,
        matrix.m12,
    ).joinToString(" ") { PdfContent.number(it) }

    /** The object's box, as the four corners a clip path needs, in the object's own space. */
    fun boxCorners(widthPt: Float, heightPt: Float): List<PagePoint> = listOf(
        PagePoint(0f, 0f),
        PagePoint(widthPt, 0f),
        PagePoint(widthPt, heightPt),
        PagePoint(0f, heightPt),
    )

    /** Text space → object space: y-up becomes y-down. See [textLine]. */
    private val FLIP = Matrix3(1f, 0f, 0f, 0f, -1f, 0f)

    private const val QUARTER_TURN = 90
    private const val HALF_TURN = 180

    /** Below this a crop has collapsed; the whole image is the least-wrong thing to show. */
    private const val MIN_CROP_SPAN = 1e-4f
}
