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
import dev.inkstone.core.model.ShapeHit

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
        // A tap is a zero-length sweep, and it is handed over in both forms for the reason
        // [DryObject.hitBy] gives: a mesh is measured by ink, a shape's outline by arithmetic.
        // Using one predicate for the tap and the eraser is what stops a shape you can erase from
        // being a shape you cannot select.
        val dab = ErasedRegion.Capsule(local.x, local.y, local.x, local.y, slop)
        if (dry.hitBy(tip, dab)) hit = dry.id
    }
    return hit
}

/**
 * ⭐ M8b — the topmost **shape** a connector endpoint would attach to, or null.
 *
 * ROADMAP M8b's *"snap-to-other-shapes for endpoints"*. Deliberately not [objectAt]:
 *
 * - **Shapes only.** `ConnectorRouting` can anchor against a local box and nothing else, so a
 *   binding naming a stroke resolves as `DETACHED_UNANCHORABLE` forever (SPEC §4.8.4). Offering
 *   the user an attachment that can never resolve is worse than not offering one — the connector
 *   would look right at the moment it was drawn and detach on the next fold.
 * - **The whole box, not the outline.** [objectAt] asks *"did the tap land on the ink"*, which is
 *   right for selecting and wrong here: dropping a line's end in the **middle** of a rectangle is
 *   the clearest way a person can say "attach to this box", and an outline test would answer no.
 *   [ShapeHit] with `filled = true` is that question, asked of the shape's own outline.
 *
 * @param slopPageUnits how far outside the shape the endpoint may land and still bind. A drawn
 *   line usually stops just short of the box it points at, so zero slop would bind almost nothing.
 */
internal fun StrokeStore.shapeAt(x: Float, y: Float, slopPageUnits: Float): ObjectId? {
    var hit: ObjectId? = null
    val local = PagePointF()
    forEachInRenderOrder(visibleBounds = null) { dry ->
        if (dry !is DryShape) return@forEachInRenderOrder
        // A connector is not an anchor. Attaching one to another is expressible and routable, but
        // the route would then depend on a route, and two connectors attached to each other have
        // no fixed point to resolve from — so it is refused here rather than left to converge.
        if (dry.isConnector) return@forEachInRenderOrder
        val bounds = dry.pageBounds
        if (bounds != null && !bounds.containsWithin(x, y, slopPageUnits)) return@forEachInRenderOrder
        if (!dry.toLocal(x, y, local)) return@forEachInRenderOrder
        val slop = dry.toLocalLength(slopPageUnits)
        if (ShapeHit.hitsPoint(dry.outline, filled = true, x = local.x, y = local.y, reach = slop)) hit = dry.id
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

/**
 * The page-space box round a set of objects, or null when none of them are here.
 *
 * ⭐ **M8c: [StrokeStore.objectOf], not `strokeOf`.** This is the selection frame the handles are
 * drawn on and the box every resize is computed against, so an object the union silently skipped
 * would be dragged by a frame that does not contain it — and a resize would scale it about the
 * wrong centre. `strokeOf` returns null for every non-stroke kind, which is the same silent skip
 * `filterIsInstance<StrokeObject>()` was in [InkSurfaceView], one screen further on.
 *
 * ⭐ **M8c: no longer merely defensive.** It was written this way while the only non-stroke kinds
 * were unselectable, on the grounds that *"the first kind that paints itself is selectable the
 * moment it exists"*. Both of them do now, so `strokeOf` here would be a selection frame that does
 * not contain the picture it is drawn round and a resize about the wrong centre.
 */
internal fun StrokeStore.pageBoundsOf(ids: Collection<ObjectId>): PageRect? {
    var union: PageRect? = null
    for (id in ids) {
        val bounds = objectOf(id)?.pageBounds ?: continue
        val rect = bounds.asRect()
        union = union?.union(rect) ?: rect
    }
    return union
}

/**
 * Everything *not* selected, as page-space boxes — what alignment guides line up with.
 *
 * Every kind, which since M8c is every kind the user can see: dragging a stroke against a photo
 * snaps to the photo's edges and its centre, exactly as it does against another stroke.
 */
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
