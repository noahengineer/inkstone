package dev.inkstone.android.ink

import androidx.ink.geometry.ImmutableParallelogram
import androidx.ink.geometry.ImmutableSegment
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.MutableVec
import dev.inkstone.core.model.Lasso
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.SelectedObject

/*
 * ⭐ M8a — asking a page of ink what a lasso or a tap just hit.
 *
 * Extension functions on [StrokeStore] rather than members, and not only to keep that class under
 * detekt's size limit: everything here is a **read**. The store is the one thing that mutates
 * render state, and separating the questions from the mutations makes it visible at a glance that
 * a selection gesture cannot change the page merely by asking what it selected.
 *
 * They are written against exactly two of the store's members — `forEachInRenderOrder` and
 * `strokeOf` — so nothing here can reach a field the store maintains an invariant over.
 *
 * Block comment, not KDoc: a file-level KDoc attaches to no declaration.
 */

/**
 * Every object [lasso] takes, in render order.
 *
 * The containment rule itself is [Lasso.selects] in `:core:model` — majority-inside, and that file
 * argues why. What is irreducibly here is turning an opaque geometry blob into page-space points
 * (SPEC §5.4), which is this module's whole reason to exist.
 *
 * Two cheap rejections come first, because a lasso on a page of 5,000 strokes must not tessellate
 * all of them: the lasso's own bounding box against each stroke's, and then the outline walk only
 * for the survivors.
 */
internal fun StrokeStore.objectsIn(lasso: Lasso): List<ObjectId> {
    val box = lasso.bounds ?: return emptyList()
    val hits = ArrayList<ObjectId>()
    val scratch = MutableVec()
    val points = ArrayList<PagePoint>()
    forEachInRenderOrder(visibleBounds = null) { dry ->
        if (!dry.pageBounds.overlaps(box)) return@forEachInRenderOrder
        points.clear()
        dry.forEachPagePoint(scratch) { x, y -> points += PagePoint(x, y) }
        if (lasso.selects(points)) hits += dry.id
    }
    return hits
}

/**
 * The topmost object under a tap, or null.
 *
 * Topmost, so tapping where two strokes cross selects the one drawn on top — which is the one the
 * eye says was tapped. [StrokeStore.forEachInRenderOrder] emits bottom-first, so the last hit wins.
 *
 * @param slopPageUnits how far off the ink a tap may land and still count, in page points. A
 *   finger-width of slop is the difference between a tool that feels precise and one that feels
 *   broken; the caller converts from dp through the camera.
 */
internal fun StrokeStore.objectAt(x: Float, y: Float, slopPageUnits: Float): ObjectId? {
    var hit: ObjectId? = null
    val local = PagePointF()
    forEachInRenderOrder(visibleBounds = null) { dry ->
        val bounds = dry.pageBounds
        if (bounds != null && !bounds.containsWithin(x, y, slopPageUnits)) return@forEachInRenderOrder
        if (!dry.toLocal(x, y, local)) return@forEachInRenderOrder
        // Erased away here means not here: tapping a hole in a stroke must not select it.
        if (dry.erased?.covers(local.x, local.y) == true) return@forEachInRenderOrder
        val slop = dry.toLocalLength(slopPageUnits)
        val tip = ImmutableParallelogram.fromSegmentAndPadding(
            ImmutableSegment(ImmutableVec(local.x, local.y), ImmutableVec(local.x, local.y)),
            slop,
        )
        if (dry.hitBy(tip)) hit = dry.id
    }
    return hit
}

/** The registers a selection edit reads, for the ids given, in render order. */
internal fun StrokeStore.selected(ids: Collection<ObjectId>): List<SelectedObject> {
    val wanted = ids.toHashSet()
    val result = ArrayList<SelectedObject>(wanted.size)
    forEachInRenderOrder(visibleBounds = null) { dry ->
        if (dry.id in wanted) result += dry.asSelected()
    }
    return result
}

/** The whole page, for the z-order decisions that need to know what else is here. */
internal fun StrokeStore.allSelected(): List<SelectedObject> {
    val result = ArrayList<SelectedObject>(size)
    forEachInRenderOrder(visibleBounds = null) { dry -> result += dry.asSelected() }
    return result
}

/** The page-space box round a set of objects, or null when none of them are here. */
internal fun StrokeStore.pageBoundsOf(ids: Collection<ObjectId>): PageRect? {
    var union: PageRect? = null
    for (id in ids) {
        val bounds = strokeOf(id)?.pageBounds ?: continue
        val rect = bounds.asRect()
        union = union?.union(rect) ?: rect
    }
    return union
}

/** Everything *not* selected, as page-space boxes — what alignment guides line up with. */
internal fun StrokeStore.pageBoundsExcluding(ids: Collection<ObjectId>): List<PageRect> {
    val excluded = ids.toHashSet()
    val result = ArrayList<PageRect>(size)
    forEachInRenderOrder(visibleBounds = null) { dry ->
        if (dry.id !in excluded) dry.pageBounds?.let { result += it.asRect() }
    }
    return result
}

/** Null bounds means an empty mesh, which cannot be culled and must therefore be considered. */
private fun PageBounds?.overlaps(other: PageRect): Boolean =
    this == null || (xMin <= other.right && xMax >= other.left && yMin <= other.bottom && yMax >= other.top)
