package dev.inkstone.android.pdf

import dev.inkstone.core.model.ImageCrop
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PdfPageGeometry

/**
 * ⭐ M8c — a **text box** and an **image** as page content.
 *
 * Separate from [PdfContent] because the two files answer different questions. That one turns
 * *geometry* into fills: a stroke's outline, an eraser-subtracted contour, a template's rules —
 * polygons, all of them, converted point by point. This one places whole **objects**, which is a
 * matrix problem rather than a polygon problem (see [PdfPlacement]), and it is the first thing in
 * this module to care which way up a glyph is.
 *
 * Both emit into the **page's** content stream, so every name used here has to be defined in the
 * *page's* `/Resources` — never on the stream. A page content stream has no resources of its own;
 * that mistake shipped in M4 and was found in M6, and [PdfResources] is where the reasoning lives.
 */
internal object PdfObjectContent {

    /**
     * One text box, as `BT … Tj … ET` inside a clip of its own box.
     *
     * ## The clip is load-bearing
     *
     * [TextBoxLayout] wraps to the box's width but cannot break inside a word, and it does not
     * drop lines that run past the box's height — a box the user made too short still holds the
     * text they typed, and losing it at publish time would be CLAUDE.md rule 1 applied to the one
     * artefact that leaves the app. So the overflow is *hidden*, not deleted: the bundle keeps
     * every character, and the published page shows what the box shows on screen.
     *
     * ## One `Tm` per line, never a `TL`/`T*`
     *
     * Leading-relative positioning would be shorter and would make every line after the first
     * depend on the one before it, so a single wrong advance skews the rest of the paragraph. An
     * absolute matrix per line is the same discipline as SPEC §5.5's per-word matrix and CLAUDE.md
     * rule 8's absolute registers: nothing accumulates.
     *
     * @param state the `/ExtGState` name for the box's alpha, or null when it is opaque.
     */
    fun textBox(box: PdfTextBox, geometry: PdfPageGeometry, font: PdfPageFont, state: String?): ByteArray {
        val sizePt = TextBoxLayout.sizeOf(box.format)
        val lines = TextBoxLayout.lay(box.text, box.format, font, box.widthPt)
        if (lines.isEmpty()) return ByteArray(0)

        val toUser = PdfPlacement.objectToUser(geometry, box.transform)
        return ContentBuilder().apply {
            line("q")
            clipTo(PdfPlacement.boxCorners(box.widthPt, box.heightPt), toUser)
            if (state != null) line("/$state gs")
            line(fillColour(box.style.colorArgb))
            line("BT")
            line("/${font.resourceName} ${PdfContent.number(sizePt)} Tf")
            for (laid in lines) {
                val matrix = PdfPlacement.textLine(geometry, box.transform, laid.leftPt, laid.baselinePt)
                line("${PdfPlacement.args(matrix)} Tm")
                line("${font.show(laid.text)} Tj")
            }
            line("ET")
            line("Q")
        }.toByteArray()
    }

    /**
     * One image, as a clip plus a `cm` plus a `Do`.
     *
     * ## Crop is one rule with two shapes
     *
     * SPEC §4.9.2 gives an image two crops: a rectangle with drag handles, and a freehand polygon.
     * They are **not** two behaviours here. Both *fit* — the crop's bounding box is scaled to fill
     * the object's box, which is what cropping looks like in every editor a user has met — and
     * both *mask*, with a clip path that is the crop's actual shape. For a rectangle the two
     * coincide and the clip is the box; for a polygon the fit uses the bounds and the mask uses
     * the points.
     *
     * Two behaviours would be the trap: a rectangle that zoomed and a polygon that did not would
     * make "crop to a square" and "crop to a four-sided polygon" produce different pictures from
     * the same gesture.
     *
     * ⚠️ The clip is emitted **before** the `cm`, so its coordinates are the object's own space
     * mapped straight to user space. Emitting it after would put the clip in the image's unit
     * square, where the box is `0 0 1 1` and a polygon in page points clips everything away.
     */
    fun image(image: PdfImage, geometry: PdfPageGeometry, resourceName: String, state: String?): ByteArray {
        val crop = boundsOf(image.crop)
        val toUser = PdfPlacement.objectToUser(geometry, image.transform)
        val place = toUser * PdfPlacement.unitImageToObject(
            widthPt = image.widthPt,
            heightPt = image.heightPt,
            left = crop.left,
            top = crop.top,
            right = crop.right,
            bottom = crop.bottom,
        )
        return ContentBuilder().apply {
            line("q")
            if (state != null) line("/$state gs")
            clipTo(maskOf(image, crop), toUser)
            line("${PdfPlacement.args(place)} cm")
            line("/$resourceName Do")
            line("Q")
        }.toByteArray()
    }

    // -----------------------------------------------------------------------

    /**
     * `W n` over [points], which are in the **object's own space** and are mapped into user space
     * here.
     *
     * ⚠️ [PagePoint] is the type because [Matrix3.map] takes one, and these are object-space
     * points rather than page-space ones. CLAUDE.md records what that conflation cost in M8a — two
     * spaces with the same numbers while nothing could move. Nothing here is at the identity, so
     * the mapping is explicit at the one place it happens rather than implied by a type.
     */
    private fun ContentBuilder.clipTo(points: List<PagePoint>, toUser: Matrix3) {
        if (points.size < MIN_POLYGON_POINTS) return
        for ((index, point) in points.withIndex()) {
            val user = toUser.map(point)
            line("${PdfContent.number(user.x)} ${PdfContent.number(user.y)} ${if (index == 0) "m" else "l"}")
        }
        line("h")
        // Nonzero winding, like every other fill this module writes: even-odd would punch a hole
        // through a self-intersecting freehand crop, which is what a shaky finger draws.
        line("W n")
    }

    /** The crop's bounding box, in fractions of the source image. The whole image when there is none. */
    private fun boundsOf(crop: ImageCrop?): CropBounds = when (crop) {
        null -> CropBounds(0f, 0f, 1f, 1f)
        is ImageCrop.Rect -> CropBounds(crop.left, crop.top, crop.right, crop.bottom)
        is ImageCrop.Polygon -> polygonBounds(crop)
    }

    private fun polygonBounds(crop: ImageCrop.Polygon): CropBounds {
        if (crop.pointCount < MIN_POLYGON_POINTS) return CropBounds(0f, 0f, 1f, 1f)
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (index in 0 until crop.pointCount) {
            val x = crop.points[index * 2]
            val y = crop.points[index * 2 + 1]
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        return CropBounds(left, top, right, bottom)
    }

    /** The clip shape, in the object's own space: the box, or the polygon fitted into it. */
    private fun maskOf(image: PdfImage, crop: CropBounds): List<PagePoint> {
        val polygon = image.crop as? ImageCrop.Polygon ?: return PdfPlacement.boxCorners(image.widthPt, image.heightPt)
        if (polygon.pointCount < MIN_POLYGON_POINTS) return PdfPlacement.boxCorners(image.widthPt, image.heightPt)
        val spanX = (crop.right - crop.left).takeIf { it > MIN_SPAN } ?: 1f
        val spanY = (crop.bottom - crop.top).takeIf { it > MIN_SPAN } ?: 1f
        return (0 until polygon.pointCount).map { index ->
            PagePoint(
                x = (polygon.points[index * 2] - crop.left) / spanX * image.widthPt,
                y = (polygon.points[index * 2 + 1] - crop.top) / spanY * image.heightPt,
            )
        }
    }

    /** `r g b rg`, from an ARGB int. Alpha travels in the `/ExtGState`, never here. */
    private fun fillColour(argb: Int): String {
        val red = PdfContent.number(((argb shr RED_SHIFT) and BYTE_MASK) / BYTE_MASK.toFloat())
        val green = PdfContent.number(((argb shr GREEN_SHIFT) and BYTE_MASK) / BYTE_MASK.toFloat())
        val blue = PdfContent.number((argb and BYTE_MASK) / BYTE_MASK.toFloat())
        return "$red $green $blue rg"
    }

    private const val MIN_POLYGON_POINTS = 3
    private const val MIN_SPAN = 1e-4f
    private const val BYTE_MASK = 0xFF
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
}

/** A crop's extent in fractions of the source image, y down from its top row (SPEC §4.9.2). */
internal data class CropBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)
