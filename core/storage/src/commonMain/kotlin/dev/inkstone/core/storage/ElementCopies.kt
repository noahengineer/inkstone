package dev.inkstone.core.storage

import dev.inkstone.core.model.CopyPlacement
import dev.inkstone.core.model.ElementId
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.SelectionEdit

/**
 * One placed copy of an Element on a page — its pieces, where it is now, and where it stacks.
 *
 * @property placement where the Element's origin is on the page *now*, recovered from the pieces
 *   (`ElementInstance.placementOf`), so a copy that was dragged, scaled or rotated is replaced
 *   where the user left it, not where it was first dropped.
 */
public data class ElementCopy(
    val instanceId: String,
    val placement: Matrix3,
    val pieces: List<ObjectId>,
    val lowest: FractionalKey,
)

/**
 * ⭐ SPEC §4.9.7 — finding the copies of an Element on a page.
 *
 * Pure over a fold, so "how many copies are there" and "what replacing them writes" are both
 * decisions a JVM test can see, and the app half only routes them to a session.
 */
public object ElementCopies {

    /**
     * Every copy of [elementId] on [page], in the page's own order.
     *
     * Pieces are grouped by **instance id and placement**, not by instance id alone. Duplicating a
     * placed copy copies its format register — tag included — so two copies can share an instance
     * id at two different places. Grouped by id alone they would be "one copy" whose replacement
     * lands at one of the two places, and the other would vanish.
     *
     * ⛔ Placement is compared within each piece's own error (`CopyPlacement.sameCopyAs`), not
     * rounded and matched: rounding cut one copy into several, so *"Update copies?"* counted them
     * all and *Update all* put a whole replacement down for each part. A copy's placement is read
     * from its most precise piece, so a replacement lands where the copy is.
     */
    public fun on(page: FoldedPage, elementId: ElementId): List<ElementCopy> {
        val pieces = page.objects.mapNotNull { obj ->
            val tag = ObjectFormat.decode(page.formatOf(obj.id)).instance?.takeIf { it.elementId == elementId.value }
            tag?.locate(obj.transform)?.let { obj to it }
        }
        return CopyPlacement.group(pieces) { it.second }.map { copy ->
            val surest = copy.minBy { it.second.errorPt }.second
            ElementCopy(
                instanceId = surest.instanceId,
                placement = surest.placement,
                pieces = copy.map { it.first.id },
                lowest = copy.minOf { it.first.z },
            )
        }
    }

    /** How many copies of [elementId] are on [page]. */
    public fun count(page: FoldedPage, elementId: ElementId): Int = on(page, elementId).size

    /**
     * [count] new z keys for a replacement of [copy], in order, sitting exactly where the old copy
     * sat: above everything that was below it, below everything else.
     *
     * Falls back to the top of the page only when the page's own keys are malformed — a file from
     * elsewhere — because a replacement on top is a visible change of stacking, and one that throws
     * would be no replacement at all.
     */
    internal fun keysFor(page: FoldedPage, copy: ElementCopy, count: Int): List<FractionalKey> {
        val others = page.objects.filter { it.id !in copy.pieces }
        val before = others.map { it.z }.filter { it < copy.lowest }.maxOrNull()
        val after = others.map { it.z }.filter { before == null || it > before }.minOrNull()
        val keys = ArrayList<FractionalKey>(count)
        var previous = before
        repeat(count) {
            val next = FractionalKey.betweenOrNull(previous, after)
                ?: return SelectionEdit.keysAbove(page.objects.maxOfOrNull { it.z }, count)
            keys += next
            previous = next
        }
        return keys
    }
}
