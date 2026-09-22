package dev.inkstone.core.model

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * ⭐ ROADMAP M8b — **a shape's extended record, and where a connector's attachment lives.**
 *
 * SPEC **§4.8** is the normative account; this KDoc is the reasoning that produced it.
 *
 * ⚠️ This said "§4.9" until M8c, when a §4.9 came to exist and the reference started pointing at
 * a real but different section — text boxes, images and the *format* register. The shape record
 * is §4.8.2; §4.9.2 is where the two registers are told apart.
 *
 * ## The problem
 *
 * M8b needs to persist four things the op vocabulary cannot express: a fill colour, a fill
 * opacity, a corner radius, and — the milestone's whole point — which object each end of a
 * connector is attached to. SPEC §4.2 froze that vocabulary in M2, and the two obvious homes are
 * both wrong for reasons that are only visible once you look at what an *older build* does:
 *
 * - **A new object kind, a new `ShapeKind`, or a new field on [ShapeObject].** The first two make
 *   `OpCodec.decodeObject` throw — `error("Unknown object kind")` and `ShapeKind.valueOf` — and
 *   `BundleReader` decodes a page's ops unguarded, so an M8a-era build would **fail to open a
 *   note it had already synced**. The third is quieter and no better: `OpExtras` collects
 *   unknown members at the op's *top* level only, so a field hung inside the object payload is
 *   read by nobody, dropped on the next two-second autosave, and uploaded that way.
 * - **The [PageOp.AddObject] payload.** `PageFolder.absorb` does `payloads.getOrPut(id)`
 *   deliberately, so an object's payload is **first-write-wins** and a re-add cannot overwrite
 *   it. Anything the user can change afterwards — and an endpoint can be dragged to a different
 *   shape, a fill can be recoloured — is therefore not payload. Re-emitting `AddObject` for the
 *   same id looks right on screen, lands in `journal.ops`, passes every test, and is discarded
 *   by the next fold from empty.
 *
 * ## The decision
 *
 * A connector **is** a [ShapeObject] of kind [ShapeKind.LINE] or [ShapeKind.ARROW]. Everything
 * above lives in that object's **[PageOp.SetTextContent] register**, encoded as the string this
 * file defines.
 *
 * `SetTextContent` is the vocabulary's one absolute, object-keyed, arbitrary-length LWW register;
 * every other register is a fixed arity of numbers. It costs no format change: an M8a-era build
 * decodes the op, folds it into its register table, and re-encodes it **byte for byte** on save,
 * because `OpCodec` never looks at what the target is.
 *
 * ## The cost, stated rather than hidden
 *
 * One register holds four independently-edited properties, and an LWW register resolves **whole**.
 * So if one device recolours a shape's fill while another concurrently re-attaches its connector,
 * the loser's edit is lost — not corrupted, not half-applied, just overwritten. That is a real
 * cost and it is the *smallest* one available: the alternative that keeps them separate is a
 * format change, and a format change here is a note that will not open.
 *
 * It is also strictly better than the failure ROADMAP M8b warns about, which is what persisting
 * a *route* would do: an absolute value winning whole would leave a connector attached at one end
 * and visibly detached at the other, identically on both devices, with every op present and no
 * error. That cannot happen here, because a record is written whole and consumed whole — whichever
 * one wins is internally consistent.
 *
 * ## What is NOT in here
 *
 * ⭐ **The route.** A connector's path, and whether each end is currently attached, are derived
 * from the fold at draw time by [ConnectorRouting] and never persisted. Moving a shape emits that
 * shape's absolute `SetTransform` and nothing else, however many connectors touch it.
 *
 * ⭐ **Detachment.** SPEC §4.2: a delete of an object that has not arrived still records its
 * tombstone, and ops targeting a tombstone are no-ops. So "this end is detached" is a *question
 * about the fold*, not a fact about the connector — see [ConnectorEndState], where the two ways
 * an end fails to resolve are deliberately given the same geometry.
 *
 * ## Forward compatibility, one level down
 *
 * [extras] does for this record what `OpExtras` does for an op: keys this build does not
 * recognise are kept verbatim and written back. Without it, M8c adding a key to the record would
 * be silently destroyed by every M8b device that opened the note — the exact failure SPEC §5.8
 * exists to prevent, one level below where it is currently checked.
 */
public data class ShapeRecord(
    /** Interior colour, or null for an unfilled shape. Ignored for a kind with no interior. */
    val fillArgb: Int? = null,
    /** Multiplied into [fillArgb]'s own alpha, exactly as [ObjectStyle.opacity] is for a stroke. */
    val fillOpacity: Float = 1f,
    /** Rectangle only — see [ShapeGeometry.roundsCorners]. */
    val cornerRadiusPt: Float = 0f,
    /**
     * ⭐ **The curve.** A [ShapeKind.LINE] or [ShapeKind.ARROW]'s on-curve midpoint, in the shape's
     * **own space**; null for a straight one. See [ShapeGeometry.outline] and [ShapePoints].
     *
     * ## Why a curve is a register on a line and not a sixth [ShapeKind]
     *
     * `OpCodec.decodeObject` reads the kind with `ShapeKind.valueOf` and `BundleReader` decodes a
     * page's ops unguarded, so a new constant is not a degrade — it is **an older build failing to
     * open a note it has already synced** (docs/M8-CONNECTORS.md §1.1, which dropped `POLYGON` for
     * exactly this). Here the degrade is the best one available: a build that predates this key
     * keeps the bytes, re-encodes them verbatim, and draws the line **straight** — the same
     * fallback a detached connector already uses, and the same picture the payload has always
     * described.
     *
     * ## Why the shape's own space and not the page's
     *
     * The same reason [ConnectorAnchor.Pinned] is normalised: a point in the object's own box is
     * carried by the object's absolute matrix, so moving, resizing or rotating a curve writes one
     * `SetTransform` and nothing else. A page-space bend would need rewriting on every one of
     * them — the register ROADMAP M8b forbids, arrived at from a third direction.
     */
    val bend: PagePoint? = null,
    /**
     * ⭐ **The shape's own vertices**, in its local space — null until a point is dragged, and from
     * then on the outline itself rather than a modifier of it.
     *
     * ## Why this exists, in the user's words
     *
     * > *"editing the points is just resizeing the shape from those points rather then actually
     * > editing the shape, which is not what i want at all"*
     *
     * Exactly right, and it was a design decision rather than a bug: a drag used to be solved as a
     * new **placement** (see [ShapePoints]), which for a rectangle can only ever scale — an affine
     * has three degrees of freedom and a rectangle has four corners, so *"move this corner and
     * leave the other three"* has no solution as a matrix. It has an obvious solution as
     * **geometry**, and this is that geometry.
     *
     * The kind stays [ShapeKind.RECTANGLE]; these points override its outline. So a build that
     * predates this key draws the parametric rectangle it always drew and re-encodes these bytes
     * untouched — the same degrade [bend] gets, and the reason neither needed a new [ShapeKind]
     * (§4.8.8: a sixth constant is a note an older build cannot open).
     *
     * ⚠️ **Seeded from the kind's own outline on the first edit, so nothing moves that the user did
     * not move.** The seeds are exactly [ShapeGeometry]'s corners and quadrant points, and
     * [ShapeGeometry.outline] reproduces the parametric shape from them bit for bit — including the
     * ellipse, whose smoothing constant is chosen for precisely that (`KAPPA / 2`). Without that
     * property the first drag would visibly re-draw the *whole* shape, which is the thing being
     * complained about, arriving from the other direction.
     *
     * Local space, like everything else here: a move, a resize and a rotation are still the
     * object's absolute matrix and still write one `SetTransform` (§4.8.3).
     */
    val points: List<PagePoint>? = null,
    /**
     * ⭐ **Whether this segment shows an arrowhead**, overriding the kind it was created as — null
     * means *"whatever [ShapeKind] says"*, which is what every shape starts at.
     *
     * ## Why an override register and not a change of kind
     *
     * The user's ask was *"should be able to say if its an arrow or not if its a line"*, and the
     * obvious reading — rewrite `ShapeObject.shape` — is not available: the kind is **payload**, so
     * changing it means deleting the object and adding a new one, which mints a fresh [ObjectId] and
     * takes the shape's erased bites, its fill and every connector attached to it along with it. A
     * register is what this vocabulary has for *"a property of an object that changes"* (SPEC §4.2),
     * and this record is where a shape's are.
     *
     * The pattern is `SetStyle`'s exactly, and so is the reason it starts null: a stroke's colour
     * lives in its geometry blob and the style register is the **override**, because duplicating the
     * capture-time value into a register would give two sources that drift. Here the payload says
     * what was drawn and this says what the user has since asked for.
     *
     * The degrade for a build that predates this key is [bend]'s and [points]': it keeps the bytes,
     * re-encodes them verbatim, and draws the head the *kind* implies — the picture the payload has
     * always described.
     */
    val arrow: Boolean? = null,
    /** Null for a plain shape. Non-null at *either* end makes this object a connector. */
    val endA: ConnectorEnd? = null,
    val endB: ConnectorEnd? = null,
    val route: ConnectorRoute = ConnectorRoute.STRAIGHT,
    /** Unrecognised `key=value` members, preserved verbatim. See the class KDoc. */
    val extras: Map<String, String> = emptyMap(),
) {

    /** A shape is a connector exactly when it has at least one end. Nothing else marks it. */
    public val isConnector: Boolean get() = endA != null || endB != null

    /**
     * True when this record says nothing — the state a plain drawn shape starts in.
     *
     * ⚠️ [bend] belongs in here, and forgetting it would be quiet: `ShapeEdit.create` omits a
     * default record entirely, and `SelectionController.editRecords` writes its undo **baseline**
     * only for a shape whose record is default. A curved line reading as "default" would therefore
     * be published with no record at all by one path, and given a baseline that erases its curve by
     * the other.
     */
    public val isDefault: Boolean
        get() = fillArgb == null && cornerRadiusPt == 0f && bend == null && points == null &&
            arrow == null && !isConnector && extras.isEmpty()

    /**
     * Every object this record names, for the id remap a note or page duplicate needs.
     *
     * ⚠️ Duplication re-mints every [ObjectId] (`withCopiedId`), so a binding copied verbatim
     * would name objects that do not exist in the copy — every connector in a duplicated diagram
     * would resolve as detached. Correct, defined and non-crashing, and still visibly wrong.
     */
    public val targets: List<ObjectId>
        get() = listOfNotNull((endA as? ConnectorEnd.Attached)?.target, (endB as? ConnectorEnd.Attached)?.target)

    /** This record with every attached target passed through [remap]. */
    public fun remapTargets(remap: (ObjectId) -> ObjectId?): ShapeRecord =
        copy(endA = endA?.remap(remap), endB = endB?.remap(remap))

    public fun encode(): String = ShapeRecordCodec.encode(this)

    public companion object {
        public val None: ShapeRecord = ShapeRecord()

        /** @return null when [text] is not a record — a plain text register, or corruption. */
        public fun decode(text: String?): ShapeRecord? = ShapeRecordCodec.decode(text)
    }
}

/** One end of a connector. */
public sealed interface ConnectorEnd {

    /**
     * Bound to an object.
     *
     * ⚠️ The object may be tombstoned, may not have arrived yet, or may be a kind with no
     * anchorable box. All three are handled by [ConnectorRouting] and **none of them is written
     * down** — see [ConnectorEndState].
     */
    public data class Attached(val target: ObjectId, val anchor: ConnectorAnchor = ConnectorAnchor.Auto) : ConnectorEnd

    /** Pinned to a page-space point. What dragging an endpoint into empty space produces. */
    public data class Free(val x: Float, val y: Float) : ConnectorEnd

    public fun remap(remap: (ObjectId) -> ObjectId?): ConnectorEnd = when (this) {
        is Attached -> remap(target)?.let { copy(target = it) } ?: this
        is Free -> this
    }
}

/** Where on the target an [ConnectorEnd.Attached] end lands. */
public sealed interface ConnectorAnchor {

    /**
     * The boundary point facing the other end, recomputed every draw.
     *
     * The default, and what dropping an endpoint on a shape's interior produces. It is why a
     * connector between two boxes looks right after either of them is dragged anywhere: nothing
     * about the attachment changed, so nothing was written.
     */
    public object Auto : ConnectorAnchor

    /**
     * A fixed point in the target's local box, normalised — `(0,0)` top-left, `(1,1)`
     * bottom-right.
     *
     * ⭐ **Normalised, and that is the whole answer to "what does a connector do under a resize
     * and a rotation".** The anchor is a point in the target's own space, so it is carried by the
     * target's absolute matrix like any other point on it: a resize scales it, a rotation turns
     * it, a shear shears it, and none of those writes an op. An anchor stored as a page-space
     * offset would need rewriting on every one of them — which is the register ROADMAP M8b
     * forbids, arrived at from the other direction.
     */
    public data class Pinned(val u: Float, val v: Float) : ConnectorAnchor
}

/** How the path between two resolved ends is drawn. All three are derived, none is stored. */
public enum class ConnectorRoute { STRAIGHT, ELBOW, CURVED }

/**
 * Why an end sits where it does — **derived every fold, never persisted**.
 *
 * ⚠️ [DETACHED_TOMBSTONED] and [DETACHED_ABSENT] deliberately produce **identical geometry**, and
 * that is the point of deriving rather than storing. Storing a detachment would make the second
 * case permanent: a connector whose target simply had not synced yet would detach on arrival day
 * and stay detached forever, on the device that noticed first. Deriving means the end re-attaches
 * the moment the object turns up — or the moment a delete is undone — with no op, no merge, and
 * nothing for the two devices to disagree about.
 *
 * They are told apart only so the UI can say *"the shape this pointed at was deleted"* without
 * saying it about a shape that is merely still in flight.
 */
public enum class ConnectorEndState {
    /** Resolved onto a live object. */
    ATTACHED,

    /** The end names no object — it was dragged into empty space. */
    FREE,

    /** The target's presence register says DELETED (SPEC §4.2). */
    DETACHED_TOMBSTONED,

    /** Nothing on this page has that id — its `AddObject` has not arrived, or never will. */
    DETACHED_ABSENT,

    /** The target is here but has no anchorable box — a stroke, until M8c gives one bounds. */
    DETACHED_UNANCHORABLE,
}

/** One end of a routed connector, in page space. */
public data class ResolvedEnd(
    val point: PagePoint,
    val state: ConnectorEndState,
    /** Unit vector pointing **out** of the target, for the curve's control point and the arrowhead. */
    val outX: Float,
    val outY: Float,
) {
    public val isAttached: Boolean get() = state == ConnectorEndState.ATTACHED
}

/** A connector's derived geometry. Recomputed from the fold; never in the log. */
public data class ConnectorPath(val path: ShapePath, val a: ResolvedEnd, val b: ResolvedEnd)

/**
 * Everything [ConnectorRouting] needs to know about one object: a local box and where it sits.
 *
 * Not [ShapeObject], because the renderer does not hold one — see [ConnectorRouting.route].
 */
public data class AnchorTarget(val kind: ShapeKind, val widthPt: Float, val heightPt: Float, val transform: Matrix3)

public fun ShapeObject.asAnchorTarget(): AnchorTarget = AnchorTarget(shape, widthPt, heightPt, transform)

/**
 * Whether a connector end may **attach** to a shape of this kind — only one with an inside.
 *
 * ⚠️ A line answered yes by omission, so a snapped line that merely *started on* another line
 * became a connector bound to it. Its route left from the boundary of a box one pen-width tall and
 * collapsed to a stub on the line it touched, and being a connector it offered the frame's grips
 * instead of its own two points. An `Auto` anchor is the boundary point facing the other end
 * (SPEC §4.8.3), and a segment has no boundary worth the name.
 *
 * Asked when a binding is **made**, not when one is routed: a note that already holds a connector
 * attached to a line keeps drawing it as before, on every device, because the fold is unchanged.
 * An exhaustive `when` rather than a reuse of [ShapeGeometry.isFillable], so a new kind has to decide.
 */
public val ShapeKind.isAnchorable: Boolean
    get() = when (this) {
        ShapeKind.LINE, ShapeKind.ARROW -> false
        ShapeKind.RECTANGLE, ShapeKind.ELLIPSE, ShapeKind.TRIANGLE -> true
    }

/**
 * Whatever can answer *"where is object X and is it still here"* — a fold, or the ink surface's
 * own render cache.
 *
 * ⚠️ **[unresolvedState] is a second method and not a boolean beside [anchorable].** An end can
 * fail to resolve three ways — the object is tombstoned, it has not arrived, or it is a kind with
 * no measurable box — and a null return cannot say which. Collapsing them would lose exactly the
 * distinction [ConnectorEndState] exists to keep, and the geometry is identical for all three, so
 * nothing downstream would ever have failed to make the loss visible.
 */
public interface ConnectorTargets {

    /** Null when [id] is absent, deleted, or a kind with no local box this module can measure. */
    public fun anchorable(id: ObjectId): AnchorTarget?

    /** Why [anchorable] answered null. Only asked when it did. */
    public fun unresolvedState(id: ObjectId): ConnectorEndState
}

/**
 * A fold, as something a connector can be routed against.
 *
 * ⚠️ Only a [ShapeObject] answers. A stroke's geometry is an opaque blob by design (SPEC §5.4) and
 * `:core:model` cannot measure it, so attaching to one is **refused rather than guessed at** —
 * nothing in M8b can create such a binding, and the branch exists so a future build sending one
 * degrades to a detached end instead of inventing a point. M8c, which gives images and text boxes
 * real extents, is where this widens.
 */
public fun FoldedPage.asConnectorTargets(): ConnectorTargets = object : ConnectorTargets {
    override fun anchorable(id: ObjectId): AnchorTarget? = (find(id) as? ShapeObject)?.asAnchorTarget()

    override fun unresolvedState(id: ObjectId): ConnectorEndState = when {
        id in deleted -> ConnectorEndState.DETACHED_TOMBSTONED
        find(id) != null -> ConnectorEndState.DETACHED_UNANCHORABLE
        else -> ConnectorEndState.DETACHED_ABSENT
    }
}

/**
 * ⭐ Turns a [ShapeRecord]'s bindings plus a [FoldedPage] into a path — the whole of *"connectors
 * stay attached and reroute when a connected shape is moved or resized"*, and it writes nothing.
 *
 * ## Why this is a pure function of the fold
 *
 * Because a fold is what two devices agree on. Every input here — the targets' absolute
 * transforms, their sizes, their presence — is a value the merge already converges on, so the
 * route converges too, without a single byte about the route being exchanged. Persisting one
 * would put two devices' independent edits into a single LWW register, and an absolute value wins
 * whole: the connector would end up attached at one end and visibly detached at the other,
 * identically on both devices, with every op present and no error (ROADMAP M8b).
 *
 * ## The fallback, and why it is the object's own payload
 *
 * An end that does not resolve falls back to the connector's **own** [ShapeObject] geometry — the
 * segment `(0, h/2) → (w, h/2)` under its transform. Three things then agree for free: an
 * M8a-era build (which knows nothing of records) draws exactly that; a detached end draws exactly
 * that; and undoing the delete puts it back with no op. The payload's first-write-wins property,
 * which made it the wrong home for the binding, is exactly right for a fallback that must never
 * change.
 */
public object ConnectorRouting {

    /** Route against a whole fold — what a test, the PDF writer and a merge assertion have. */
    public fun route(connector: ShapeObject, record: ShapeRecord, page: FoldedPage): ConnectorPath? =
        route(connector.asAnchorTarget(), record, page.asConnectorTargets())

    /**
     * Route against anything that can answer for a target.
     *
     * ⚠️ **The renderer does not have a [FoldedPage].** `:android:ink` holds a render cache — a
     * decoded stroke or a shape and its registers — and never the objects it was folded from; the
     * geometry blob was consumed at load. So the lookup is an interface rather than the fold, for
     * the same reason [SelectedObject] exists rather than `SelectionEdit` taking an [InkObject]:
     * a signature the surface can only satisfy by fabricating a payload is a signature that
     * invites a fabricated payload to reach an `AddObject`.
     *
     * @return null when [record] is not a connector — the caller draws a plain shape.
     */
    public fun route(connector: AnchorTarget, record: ShapeRecord, targets: ConnectorTargets): ConnectorPath? {
        if (!record.isConnector) return null
        val fallback = fallbackEnds(connector)

        // Two passes, because an Auto anchor is defined relative to the *other* end and both may
        // be Auto. Pass one places every end at something that does not depend on its partner —
        // the target's centre, the pinned point, the free point — and pass two re-asks the Auto
        // ones now that there is something to face. One refinement is enough and it terminates,
        // which iterating to a fixed point would not obviously do.
        val provisionalA = resolve(record.endA, targets, fallback.first, toward = null)
        val provisionalB = resolve(record.endB, targets, fallback.second, toward = null)
        val a = resolve(record.endA, targets, fallback.first, toward = provisionalB.point)
        val b = resolve(record.endB, targets, fallback.second, toward = provisionalA.point)

        return ConnectorPath(pathFor(record.route, a, b), a, b)
    }

    /** The connector's own authored endpoints, in page space. */
    private fun fallbackEnds(connector: AnchorTarget): Pair<PagePoint, PagePoint> {
        val y = connector.heightPt / 2f
        val m = connector.transform
        return m.map(PagePoint(0f, y)) to m.map(PagePoint(connector.widthPt, y))
    }

    private fun resolve(
        end: ConnectorEnd?,
        targets: ConnectorTargets,
        fallback: PagePoint,
        toward: PagePoint?,
    ): ResolvedEnd = when (end) {
        null -> ResolvedEnd(fallback, ConnectorEndState.FREE, 0f, 0f)
        is ConnectorEnd.Free -> ResolvedEnd(PagePoint(end.x, end.y), ConnectorEndState.FREE, 0f, 0f)
        is ConnectorEnd.Attached -> resolveAttached(end, targets, fallback, toward)
    }

    private fun resolveAttached(
        end: ConnectorEnd.Attached,
        targets: ConnectorTargets,
        fallback: PagePoint,
        toward: PagePoint?,
    ): ResolvedEnd {
        val shape = targets.anchorable(end.target)
            ?: return ResolvedEnd(fallback, targets.unresolvedState(end.target), 0f, 0f)

        val inverse = shape.transform.inverse()
            ?: return ResolvedEnd(fallback, ConnectorEndState.DETACHED_UNANCHORABLE, 0f, 0f)

        val local = localAnchor(shape, end.anchor, toward?.let { inverse.map(it) })
        val point = shape.transform.map(local)
        val centre = shape.transform.map(ShapeGeometry.centre(shape.widthPt, shape.heightPt))
        val (outX, outY) = unit(point.x - centre.x, point.y - centre.y)
        return ResolvedEnd(point, ConnectorEndState.ATTACHED, outX, outY)
    }

    private fun localAnchor(shape: AnchorTarget, anchor: ConnectorAnchor, towardLocal: PagePoint?): PagePoint =
        when (anchor) {
            is ConnectorAnchor.Pinned -> ShapeGeometry.pinned(shape.widthPt, shape.heightPt, anchor.u, anchor.v)

            ConnectorAnchor.Auto ->
                towardLocal
                    ?.let { ShapeGeometry.boundaryToward(shape.kind, shape.widthPt, shape.heightPt, it) }
                    ?: ShapeGeometry.centre(shape.widthPt, shape.heightPt)
        }

    // -----------------------------------------------------------------------
    // The three routes
    // -----------------------------------------------------------------------

    private fun pathFor(route: ConnectorRoute, a: ResolvedEnd, b: ResolvedEnd): ShapePath = when (route) {
        ConnectorRoute.STRAIGHT -> straight(a, b)
        ConnectorRoute.ELBOW -> elbow(a, b)
        ConnectorRoute.CURVED -> curved(a, b)
    }

    private fun straight(a: ResolvedEnd, b: ResolvedEnd): ShapePath = ShapePath(
        listOf(
            ShapeSegment.MoveTo(a.point.x, a.point.y),
            ShapeSegment.LineTo(b.point.x, b.point.y),
        ),
        closed = false,
    )

    /**
     * Orthogonal, three segments, turning on the axis with the larger separation.
     *
     * Choosing the axis by separation is what stops an elbow flipping between shapes that are
     * roughly level: the alternative — always going horizontal first — puts a long spur out of
     * the side of a box that is directly above its partner.
     */
    private fun elbow(a: ResolvedEnd, b: ResolvedEnd): ShapePath {
        val dx = b.point.x - a.point.x
        val dy = b.point.y - a.point.y
        val segments = ArrayList<ShapeSegment>(ELBOW_SEGMENTS)
        segments += ShapeSegment.MoveTo(a.point.x, a.point.y)
        if (abs(dx) >= abs(dy)) {
            val midX = a.point.x + dx / 2f
            segments += ShapeSegment.LineTo(midX, a.point.y)
            segments += ShapeSegment.LineTo(midX, b.point.y)
        } else {
            val midY = a.point.y + dy / 2f
            segments += ShapeSegment.LineTo(a.point.x, midY)
            segments += ShapeSegment.LineTo(b.point.x, midY)
        }
        segments += ShapeSegment.LineTo(b.point.x, b.point.y)
        return ShapePath(segments, closed = false)
    }

    /**
     * One cubic, with each control point pushed out along its end's outward normal.
     *
     * The push is a fraction of the end-to-end distance, so the curve keeps its shape at any
     * separation rather than looping tightly when the shapes are far apart. A free end has no
     * normal, so its control point falls back to the straight direction — which makes a
     * half-attached connector curve out of the shape and run straight into the empty end, which
     * is what it should look like.
     */
    private fun curved(a: ResolvedEnd, b: ResolvedEnd): ShapePath {
        val dx = b.point.x - a.point.x
        val dy = b.point.y - a.point.y
        val distance = sqrt(dx * dx + dy * dy)
        val push = distance * CURVE_PUSH
        val (ax, ay) = controlDirection(a, dx, dy)
        val (bx, by) = controlDirection(b, -dx, -dy)
        return ShapePath(
            listOf(
                ShapeSegment.MoveTo(a.point.x, a.point.y),
                ShapeSegment.CubicTo(
                    a.point.x + ax * push,
                    a.point.y + ay * push,
                    b.point.x + bx * push,
                    b.point.y + by * push,
                    b.point.x,
                    b.point.y,
                ),
            ),
            closed = false,
        )
    }

    private fun controlDirection(end: ResolvedEnd, fallbackX: Float, fallbackY: Float): Pair<Float, Float> =
        if (abs(end.outX) > EPSILON || abs(end.outY) > EPSILON) {
            end.outX to end.outY
        } else {
            unit(fallbackX, fallbackY)
        }

    private fun unit(x: Float, y: Float): Pair<Float, Float> {
        val length = sqrt(x * x + y * y)
        return if (length < EPSILON) 0f to 0f else (x / length) to (y / length)
    }

    /** Control points at 40% of the separation — enough curve to read as one, not a loop. */
    private const val CURVE_PUSH = 0.4f

    /** A move plus three lines. */
    private const val ELBOW_SEGMENTS = 4

    private const val EPSILON = 1e-6f
}

/**
 * The wire form of a [ShapeRecord] — what goes in the `SetTextContent` register.
 *
 * ```
 * is1;f=-16777216;fo=0.5;r=8;a=o:0192…-…:0.5:1;b=o:0192…-…;rt=e;bd=40:-12.5
 * ```
 *
 * Deliberately terse and ASCII-only. It is written on the same two-second autosave as everything
 * else and read on every open, and it sits inside a JSON string, where every byte is a byte the
 * JSON encoder may have to escape.
 *
 * ## Rules that keep it safe
 *
 * - **The `is1` prefix is the discriminator.** Anything else — including an empty string and a
 *   genuine piece of text — decodes to null and the object is drawn as a plain shape. A register
 *   this build cannot parse must never be *guessed* at, because guessing turns a stranger's data
 *   into geometry.
 * - **Unknown keys are kept.** See [ShapeRecord.extras].
 * - **Floats are formatted by hand** rather than by `toString`, so the same record produces the
 *   same bytes on every platform this module compiles for. `Float.toString` is not specified to
 *   agree between Kotlin/JVM and Kotlin/Native, and a re-save that rewrites a register only
 *   because the formatter disagreed is churn the sync engine would faithfully upload.
 * - **No value this build writes can contain a separator**, because every one of them is a UUIDv7,
 *   a fixed tag, or a hand-formatted float. There is no escaping scheme, deliberately: one that is
 *   never exercised is one that is wrong.
 *
 *   ⚠️ **Corrected in M8c — this said values were "rejected, not escaped", and nothing rejected
 *   anything.** `encode` is a plain concatenation. The claim was harmless only because it happened
 *   to be true of every value that had ever reached it; a reader who believed it and added a
 *   user-typed value to the record would have got silent truncation on the *peer*, one sync later.
 *   The guard is the **types**, not a check — which is why `ObjectFormat`'s font is a closed enum
 *   and not a family name. Adding a runtime check would be worse than useless here: it would fire
 *   on a *later* build's extras value and turn forward compatibility into a crash on save.
 */
internal object ShapeRecordCodec {

    private const val PREFIX = "is1"
    private const val PAIR = ';'
    private const val EQUALS = '='
    private const val PART = ':'

    private const val K_FILL = "f"
    private const val K_FILL_OPACITY = "fo"
    private const val K_RADIUS = "r"
    private const val K_END_A = "a"
    private const val K_END_B = "b"
    private const val K_ROUTE = "rt"

    /** `bd=x:y` — the curve's on-curve midpoint, in the shape's own space. */
    private const val K_BEND = "bd"

    /** `x:y`, and nothing else — an arity that is not two is a value from a build that meant more. */
    private const val POINT_PARTS = 2

    /** `pts=x:y,x:y,…` — the shape's own vertices, in its local space. */
    private const val K_POINTS = "pts"

    /** ⭐ The arrowhead override — `1` or `0`. See [ShapeRecord.arrow]. */
    private const val K_ARROW = "ah"

    private const val TRUE = "1"
    private const val FALSE = "0"

    /** The list separator. The third and last: `;` divides pairs, `:` divides a value's parts. */
    private const val POINT = ','

    /**
     * A segment stores **two** points and a closed shape at least three; more than [MAX_POINTS] is
     * not a hand-edited shape.
     *
     * ⚠️ Was 3. A segment's endpoints moved into this register when *"i dont want any shape to do
     * that no matter what"* took the last transform-based edit away, and a two-entry list would
     * otherwise have been refused by the decoder it had just been written by.
     */
    private const val MIN_POINTS = 2
    private const val MAX_POINTS = 64

    private const val TAG_FREE = "p"
    private const val TAG_OBJECT = "o"

    /** `p:x:y` · `o:id` · `o:id:u:v` — the arity is how an end says which kind it is. */
    private const val FREE_PARTS = 3
    private const val AUTO_PARTS = 2
    private const val PINNED_PARTS = 4
    private const val PINNED_U = 2
    private const val PINNED_V = 3

    private val knownKeys =
        setOf(K_FILL, K_FILL_OPACITY, K_RADIUS, K_END_A, K_END_B, K_ROUTE, K_BEND, K_POINTS, K_ARROW)

    fun encode(record: ShapeRecord): String {
        val parts = ArrayList<String>()
        parts += PREFIX
        record.fillArgb?.let { parts += "$K_FILL$EQUALS$it" }
        if (record.fillOpacity != 1f) parts += "$K_FILL_OPACITY$EQUALS${format(record.fillOpacity)}"
        if (record.cornerRadiusPt != 0f) parts += "$K_RADIUS$EQUALS${format(record.cornerRadiusPt)}"
        record.endA?.let { parts += "$K_END_A$EQUALS${encodeEnd(it)}" }
        record.endB?.let { parts += "$K_END_B$EQUALS${encodeEnd(it)}" }
        if (record.route != ConnectorRoute.STRAIGHT) parts += "$K_ROUTE$EQUALS${encodeRoute(record.route)}"
        record.bend?.let { parts += "$K_BEND$EQUALS${format(it.x)}$PART${format(it.y)}" }
        record.arrow?.let { parts += "$K_ARROW$EQUALS${if (it) TRUE else FALSE}" }
        record.points?.takeIf { it.isNotEmpty() }?.let { points ->
            parts += K_POINTS + EQUALS +
                points.joinToString(POINT.toString()) { "${format(it.x)}$PART${format(it.y)}" }
        }
        // Sorted, so identical content produces identical bytes whatever order a future build
        // happened to read them in — the same discipline `BundleReader` applies to unknown
        // manifest members (SPEC §5.8).
        //
        // ⚠️ `sortedBy`, not `toSortedMap`: the latter is `java.util.SortedMap` and does not exist
        // in common Kotlin, so it compiles for Android and fails `compileKotlinIosSimulatorArm64`.
        // Guardrail 2 (SPEC §2.3) caught this one; the import check could not have.
        for (entry in record.extras.entries.sortedBy { it.key }) parts += "${entry.key}$EQUALS${entry.value}"
        return parts.joinToString(PAIR.toString())
    }

    fun decode(text: String?): ShapeRecord? {
        if (text == null) return null
        val parts = text.split(PAIR)
        if (parts.firstOrNull() != PREFIX) return null
        var record = ShapeRecord.None
        val extras = LinkedHashMap<String, String>()
        for (index in 1 until parts.size) {
            val pair = parts[index]
            val split = pair.indexOf(EQUALS)
            if (split <= 0) continue
            val key = pair.substring(0, split)
            val value = pair.substring(split + 1)
            if (key in knownKeys) record = applyKnown(record, key, value) else extras[key] = value
        }
        return record.copy(extras = extras)
    }

    /**
     * One key, applied if it parses.
     *
     * ⚠️ **Every branch falls back to the record unchanged**, which is the fail-closed rule this
     * codec is built on: a value this build cannot read leaves the property at its default rather
     * than aborting the whole record. A record is four independent properties and one unreadable
     * fill is no reason to forget an attachment.
     */
    private fun applyKnown(record: ShapeRecord, key: String, value: String): ShapeRecord = when (key) {
        K_FILL -> record.withFill(value)

        K_FILL_OPACITY -> record.withFillOpacity(value)

        K_RADIUS -> record.withRadius(value)

        K_END_A -> record.withEnd(value, isA = true)

        K_END_B -> record.withEnd(value, isA = false)

        K_ROUTE -> record.copy(route = decodeRoute(value))

        K_BEND -> record.withBend(value)

        K_POINTS -> record.withPoints(value)

        // Fail-closed like every branch here: an unreadable flag leaves the head to the kind.
        K_ARROW -> when (value) {
            TRUE -> record.copy(arrow = true)
            FALSE -> record.copy(arrow = false)
            else -> record
        }

        else -> record
    }

    /**
     * Fail-closed, and **all or nothing**.
     *
     * ⚠️ Unlike every other key here, a half-read value would not degrade — it would be a *different
     * shape*. Dropping one unreadable vertex from a quadrilateral silently yields a triangle, which
     * is defined, non-crashing and wrong, so a single bad number abandons the whole list and the
     * shape falls back to the parametric outline its kind has always had.
     */
    private fun ShapeRecord.withPoints(value: String): ShapeRecord {
        val parts = value.split(POINT)
        if (parts.size !in MIN_POINTS..MAX_POINTS) return this
        val points = ArrayList<PagePoint>(parts.size)
        for (part in parts) points += decodePoint(part) ?: return this
        return copy(points = points)
    }

    private fun ShapeRecord.withEnd(value: String, isA: Boolean): ShapeRecord {
        val end = decodeEnd(value) ?: return this
        return if (isA) copy(endA = end) else copy(endB = end)
    }

    /** Fail-closed like every branch above: an unreadable bend leaves the line straight. */
    private fun ShapeRecord.withBend(value: String): ShapeRecord = decodePoint(value)?.let { copy(bend = it) } ?: this

    /**
     * `x:y`, or null.
     *
     * ⚠️ **Non-finite is null.** A NaN would reach [ShapeGeometry.outline] and put NaN into a path,
     * which propagates into the object's bounds, the culling test and the published PDF — none of
     * which report it. `toFloatOrNull` parses `NaN` and `Infinity` perfectly happily.
     */
    private fun decodePoint(value: String): PagePoint? {
        val parts = value.split(PART)
        if (parts.size != POINT_PARTS) return null
        val x = parts[0].toFloatOrNull() ?: return null
        val y = parts[1].toFloatOrNull() ?: return null
        return if (x.isFinite() && y.isFinite()) PagePoint(x, y) else null
    }

    private fun ShapeRecord.withFill(value: String) = value.toIntOrNull()?.let { copy(fillArgb = it) } ?: this

    private fun ShapeRecord.withFillOpacity(value: String) =
        value.toFloatOrNull()?.let { copy(fillOpacity = it.coerceIn(0f, 1f)) } ?: this

    private fun ShapeRecord.withRadius(value: String) =
        value.toFloatOrNull()?.let { copy(cornerRadiusPt = maxOf(it, 0f)) } ?: this

    private fun encodeEnd(end: ConnectorEnd): String = when (end) {
        is ConnectorEnd.Free -> "$TAG_FREE$PART${format(end.x)}$PART${format(end.y)}"
        is ConnectorEnd.Attached -> encodeAttached(end)
    }

    private fun encodeAttached(end: ConnectorEnd.Attached): String = when (val anchor = end.anchor) {
        ConnectorAnchor.Auto -> "$TAG_OBJECT$PART${end.target.value}"

        is ConnectorAnchor.Pinned ->
            "$TAG_OBJECT$PART${end.target.value}$PART${format(anchor.u)}$PART${format(anchor.v)}"
    }

    private fun decodeEnd(value: String): ConnectorEnd? {
        val parts = value.split(PART)
        return when (parts.firstOrNull()) {
            TAG_FREE -> freeEnd(parts)
            TAG_OBJECT -> attachedEnd(parts)
            else -> null
        }
    }

    private fun freeEnd(parts: List<String>): ConnectorEnd? {
        if (parts.size != FREE_PARTS) return null
        val x = parts[1].toFloatOrNull() ?: return null
        val y = parts[2].toFloatOrNull() ?: return null
        return ConnectorEnd.Free(x, y)
    }

    private fun attachedEnd(parts: List<String>): ConnectorEnd? {
        val id = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        val anchor = when (parts.size) {
            AUTO_PARTS -> ConnectorAnchor.Auto
            PINNED_PARTS -> pinned(parts) ?: return null
            else -> return null
        }
        return ConnectorEnd.Attached(ObjectId(id), anchor)
    }

    /** `parts` is known to be [PINNED_PARTS] long here — [attachedEnd] is the only caller. */
    private fun pinned(parts: List<String>): ConnectorAnchor.Pinned? {
        val u = parts[PINNED_U].toFloatOrNull() ?: return null
        val v = parts[PINNED_V].toFloatOrNull() ?: return null
        return ConnectorAnchor.Pinned(u, v)
    }

    private fun encodeRoute(route: ConnectorRoute): String = when (route) {
        ConnectorRoute.STRAIGHT -> "s"
        ConnectorRoute.ELBOW -> "e"
        ConnectorRoute.CURVED -> "c"
    }

    /** An unrecognised route degrades to straight — a line the user can see and re-pick. */
    private fun decodeRoute(value: String): ConnectorRoute = when (value) {
        "e" -> ConnectorRoute.ELBOW
        "c" -> ConnectorRoute.CURVED
        else -> ConnectorRoute.STRAIGHT
    }

    /**
     * Three decimal places, no exponent, platform-independent — see [RecordText.number].
     *
     * Shared with `ObjectFormatCodec` since M8c. It was a private copy here, and a byte-stability
     * rule with two implementations is only as strong as the one nobody is maintaining.
     */
    private fun format(value: Float): String = RecordText.number(value)
}
