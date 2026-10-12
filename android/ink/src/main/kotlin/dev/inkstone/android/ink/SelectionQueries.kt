package dev.inkstone.android.ink

import androidx.ink.geometry.ImmutableParallelogram
import androidx.ink.geometry.ImmutableSegment
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.MutableVec
import dev.inkstone.core.model.CopyPlacement
import dev.inkstone.core.model.Lasso
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.SelectedObject
import dev.inkstone.core.model.ShapeHit
import dev.inkstone.core.model.SnapGrid
import dev.inkstone.core.model.isAnchorable
import dev.inkstone.core.model.unionOrNull

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
        if (!dry.pageBounds.overlaps(box) || dry.hiddenGuide(this)) return@forEachInRenderOrder
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
    if (guidesShown && !guidesHidden) return objectAtWithGuides(x, y, slopPageUnits)
    var hit: ObjectId? = null
    val local = PagePointF()
    forEachInRenderOrder(visibleBounds = null) { dry ->
        if (dry.hiddenGuide(this) || !dry.reachedBy(x, y, slopPageUnits, local)) return@forEachInRenderOrder
        // [DryObject.tappedAt] rather than `hitBy`: a tap and an eraser disagree about the inside
        // of a hollow shape, and only about that. See its KDoc — sharing one predicate is what made
        // a snapped rectangle need an 8 dp landing on a hairline.
        if (dry.tappedAt(local.x, local.y, dry.toLocalLength(slopPageUnits))) hit = dry.id
    }
    return hit
}

/**
 * ⭐ An area or the bounds on the Element editor's canvas with its guides switched off — not drawn,
 * so not something a tap or a lasso may take (`StrokeStore.guidesHidden`).
 */
private fun DryObject.hiddenGuide(store: StrokeStore): Boolean = store.guidesHidden && isGuide

/** Cheap bounds test, the map into [dry]'s space, and the erased-hole check — every tap's preamble. */
private fun DryObject.reachedBy(x: Float, y: Float, slopPageUnits: Float, local: PagePointF): Boolean {
    val bounds = pageBounds
    if (bounds != null && !bounds.containsWithin(x, y, slopPageUnits)) return false
    if (!toLocal(x, y, local)) return false
    // Erased away here means not here: tapping a hole in a stroke must not select it.
    return erased?.covers(local.x, local.y) != true
}

/**
 * ⭐ SPEC §4.9.7 — [objectAt] on the Element editor's canvas, where the guides are drawn on top.
 *
 * Knockout areas sit at the **bottom** of an Element (they hide the note behind a copy, not the
 * Element's own pieces), so plain topmost-wins handed a tap inside an area to whatever piece covered
 * it — usually a filled shape, whose inside counts as a hit. The area could be seen and not got hold
 * of: *"hard to position right with no … edit"*. The order here follows what is drawn:
 *
 * 1. a guide's **dashed line** — it is drawn over everything;
 * 2. anything's **ink** — a stroke, a shape's outline;
 * 3. the **inside of an area** — it is what this canvas is for, and it is never covered by chrome;
 * 4. the inside of anything else.
 *
 * The bounds offer only rule 1 ([DryShape.tappedAt]), so a tap inside them is a tap on the Element.
 */
private fun StrokeStore.objectAtWithGuides(x: Float, y: Float, slopPageUnits: Float): ObjectId? {
    var guideEdge: ObjectId? = null
    var ink: ObjectId? = null
    var areaInside: ObjectId? = null
    var inside: ObjectId? = null
    val local = PagePointF()
    forEachInRenderOrder(visibleBounds = null) { dry ->
        if (!dry.reachedBy(x, y, slopPageUnits, local)) return@forEachInRenderOrder
        val reach = dry.toLocalLength(slopPageUnits)
        val edge = dry.edgeTappedAt(local.x, local.y, reach)
        val anywhere = edge || dry.tappedAt(local.x, local.y, reach)
        when {
            dry.isGuide && edge -> guideEdge = dry.id
            edge -> ink = dry.id
            dry.cutter && anywhere -> areaInside = dry.id
            anywhere && !dry.isGuide -> inside = dry.id
        }
    }
    return guideEdge ?: ink ?: areaInside ?: inside
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
 *   ⚠️ It is only that clear when the line's **other** end lands on a different shape. On its own,
 *   an end inside a box is just as often a line that starts there — a radius, a divider, an arrow
 *   out of a labelled box — so `ShapeSnapController.connectorRecordFor` binds both ends or neither.
 * - **Closed shapes only** ([isAnchorable]). A line or an arrow has no inside for
 *   `filled = true` to test, so the question quietly became *"within slop of the segment"* — and a
 *   snapped line that merely **started on** another line bound to it, collapsed to a stub on it,
 *   and lost its point handles, because a connector has none.
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
        if (!dry.obj.shape.isAnchorable) return@forEachInRenderOrder
        val bounds = dry.pageBounds
        if (bounds != null && !bounds.containsWithin(x, y, slopPageUnits)) return@forEachInRenderOrder
        if (!dry.toLocal(x, y, local)) return@forEachInRenderOrder
        val slop = dry.toLocalLength(slopPageUnits)
        if (ShapeHit.hitsPoint(dry.outline, filled = true, x = local.x, y = local.y, reach = slop)) hit = dry.id
    }
    return hit
}

/**
 * True when the page point is inside shape [id]'s outline — the nesting test a connector's two ends
 * must pass (`ShapeSnapController.connectorRecordFor`).
 *
 * The same [ShapeHit] question [shapeAt] asks, with **no** slop: this asks whether a point is
 * *within* a shape, not whether a pen aimed at it, and a box beside a frame is not inside the frame
 * just because it comes within a finger's width of it.
 */
internal fun StrokeStore.shapeEncloses(id: ObjectId, x: Float, y: Float): Boolean {
    val dry = shapeOf(id) ?: return false
    val local = PagePointF()
    if (!dry.toLocal(x, y, local)) return false
    return ShapeHit.hitsPoint(dry.outline, filled = true, x = local.x, y = local.y, reach = 0f)
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
    val dries = ids.mapNotNull { objectOf(it) }
    for (dry in dries) dry.pageBounds?.let { union = union.unionOrNull(it.asRect()) }
    // ⭐ An Element copy's own bounds as well (SPEC §4.9.7): the box set in the editor, which may be
    // larger than what its pieces cover — a margin, or a corner placed on the grid. Once per copy,
    // from its surest piece, so the frame is not widened by every piece's rounding.
    for (dry in surestPieceOfEachCopy(dries)) dry.copyBoundsOnPage?.let { union = union.unionOrNull(it) }
    return union
}

/** ⭐ The pivot a selection of Element copies snaps by — read from the surest piece. SPEC §4.9.7. */
internal fun StrokeStore.copyPivotOf(ids: Collection<ObjectId>): PagePoint? =
    surestPieceOfEachCopy(ids.mapNotNull { objectOf(it) }).firstNotNullOfOrNull { it.pivotOnPage }

/**
 * One piece per Element copy among [pieces] — the one whose tag says most exactly where the copy is
 * (`CopyPlacement.errorPt`). Every piece can say where its copy's pivot and bounds are, each a little
 * differently; a pivot read off a resized stroke could land a grid snap most of a point off a line.
 */
private fun surestPieceOfEachCopy(pieces: List<DryObject>): List<DryObject> {
    val placed = pieces.mapNotNull { dry -> dry.copyPlacement?.let { dry to it } }
    return CopyPlacement.group(placed) { it.second }.map { copy -> copy.minBy { it.second.errorPt }.first }
}

/**
 * ⭐ [ids], with every **other** piece of any Element copy among them — so a tap on one stroke of a
 * placed copy selects the copy, and moving it moves all of it (SPEC §4.9.7). A copy is one thing to
 * the person who dropped it; selecting a single stroke of it and dragging that stroke off by its
 * copy's pivot was half of *"the pivot does not work"*.
 */
internal fun StrokeStore.withWholeCopies(ids: List<ObjectId>): List<ObjectId> {
    // One placement per copy hit, so a lasso across a few copies compares each object a few times.
    val copies = CopyPlacement.group(ids.mapNotNull { objectOf(it)?.copyPlacement }) { it }.map { it.first() }
    if (copies.isEmpty()) return ids
    val result = LinkedHashSet(ids)
    forEachInRenderOrder(visibleBounds = null) { dry ->
        val placed = dry.copyPlacement ?: return@forEachInRenderOrder
        if (copies.any { it.sameCopyAs(placed) }) result += dry.id
    }
    return result.toList()
}

/**
 * ⭐ Everything on the page, as one page-space box — **what an infinite canvas grows to hold**
 * (SPEC §4.5).
 *
 * Null for an empty page: *"nothing here"* rather than an empty box at the origin, because the
 * two differ for a page whose only writing is a long way from `(0, 0)`.
 *
 * Derived rather than remembered, and that is the design. The canvas is
 * `stored.grownToInclude(this)` everywhere it is asked for — the editor after a stroke, the
 * thumbnail, the publisher before it writes the box — so no `SetPageMeta` records growth, nothing
 * has to converge, and there is no state that can disagree with the ink. A merged page grows for a
 * peer's writing the moment it is folded, because the peer's strokes are simply in here.
 */
internal fun StrokeStore.contentBounds(): PageRect? {
    var bounds: PageRect? = null
    forEachInRenderOrder(visibleBounds = null) { dry ->
        dry.pageBounds?.let { bounds = bounds.unionOrNull(it.asRect()) }
    }
    return bounds
}

/**
 * ⭐ [grid] when every object in [selection] is a shape, [SnapGrid.None] otherwise.
 *
 * ⚠️ **Shapes only, and that is a scope rather than an oversight.** A lattice is a diagram tool:
 * quantising a drag of somebody's handwriting to 18 pt would move their writing away from the word
 * it belongs beside, in steps they can see, for a setting they turned on to tidy boxes. A mixed
 * selection keeps the alignment guides it has always had, which are about the objects on the page
 * rather than about a lattice.
 *
 * A connector counts as a shape: its own transform is derived and re-derived every fold
 * (SPEC §4.8.3), so a drag of one writes nothing the router honours and excluding it would be a
 * condition with no observable effect.
 */
internal fun StrokeStore.gridFor(selection: Collection<ObjectId>, grid: SnapGrid): SnapGrid {
    if (grid.isEmpty || selection.isEmpty()) return SnapGrid.None
    return if (selection.all { shapeOf(it) != null }) grid else SnapGrid.None
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
