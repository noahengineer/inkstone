package dev.inkstone.app.note

import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.InkObject
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.TextBoxObject

/**
 * The page-space box an object occupies, from its extents and its absolute transform.
 *
 * ## ⚠️ Only for the kinds whose extents `:core:model` knows
 *
 * [ImageObject], [TextBoxObject] and [ShapeObject] carry a `widthPt` and a `heightPt`. A
 * `StrokeObject` carries an **opaque geometry blob** that only `:android:ink` can decode (SPEC
 * §5.4), so this answers null for one rather than guessing a box, and every caller treats null as
 * *"not something this layer can hit-test"*.
 *
 * That null is the reason `:app`'s Element capture takes a dragged **area** rather than the
 * lasso's selection: the ink surface exposes a selection *count* and not its object ids
 * (`InkSelection`), and even given the ids `:app` could not measure a stroke to decide whether it
 * was inside the area. Both halves of that limitation are stated where they bite —
 * `ElementLibrary`, and the Elements row's own label — rather than left to be discovered by
 * stamping an element and finding the handwriting missing.
 *
 * The box is the axis-aligned hull of the four transformed corners, so a rotated image reports the
 * box a finger has to be inside to hit it and not the box it would occupy unrotated.
 *
 * @param format the object's `SetFormat` register, which is where a text box's **frame** lives. The
 *   default is the placed size and is only right for a box nobody has resized — pass the fold's
 *   `formatting(id)` wherever one is in reach.
 */
fun InkObject.pageBounds(format: ObjectFormat = ObjectFormat.Default): PageRect? {
    val width: Float
    val height: Float
    when (this) {
        is ImageObject -> {
            width = widthPt
            height = heightPt
        }

        is TextBoxObject -> {
            // ⚠️ The frame, not the payload. A text box is resized by `SetFormat`
            // (`ObjectFormat.boxWidthPt`) and not by a scale, so a caller that passes no format is
            // hit-testing a dragged box at the size it was *placed* at — which is why the parameter
            // exists rather than a second function, and why the default is documented as an
            // approximation rather than as the answer.
            width = format.frameWidth(widthPt)
            height = format.frameHeight(heightPt)
        }

        is ShapeObject -> {
            width = widthPt
            height = heightPt
        }

        else -> return null
    }
    return PageRect.around(
        listOf(
            transform.map(PagePoint(0f, 0f)),
            transform.map(PagePoint(width, 0f)),
            transform.map(PagePoint(width, height)),
            transform.map(PagePoint(0f, height)),
        ),
    )
}

/**
 * The topmost object at [at] that this layer can hit-test, or null.
 *
 * Topmost by z, because that is what the user sees on top and therefore what they mean. Strokes
 * are skipped for [pageBounds]'s reason — which is correct behaviour rather than a limitation
 * here: the Text and Image tools are aimed at text boxes and pictures, and a tap that "hit" a
 * stroke would have nothing to do.
 */
fun List<InkObject>.topmostAt(
    at: PagePoint,
    formatOf: (InkObject) -> ObjectFormat = { ObjectFormat.Default },
    kind: (InkObject) -> Boolean,
): InkObject? = this
    .filter(kind)
    .filter { obj ->
        val box = obj.pageBounds(formatOf(obj)) ?: return@filter false
        at.x >= box.left && at.x <= box.right && at.y >= box.top && at.y <= box.bottom
    }
    .maxByOrNull { it.z }
