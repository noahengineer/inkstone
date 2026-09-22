package dev.inkstone.core.model

/**
 * SPEC §4.2 ⭐ — **the complete op vocabulary, defined in M2.**
 *
 * Every variant below is defined now even though M8 is the first user of most of
 * them. This is not tidiness: the op vocabulary *is* the sync format, and
 * extending it after sync is live means a device running the older build receives
 * ops it cannot represent. The best it can then do is preserve them verbatim
 * ([Unknown]) and show a page that is missing whatever they said — which is why
 * [Unknown] exists too, and why SPEC §5.8 requires unknown ops to survive a
 * round-trip untouched.
 *
 * ## The three groups
 *
 * **Grow-only payload.** [AddObject] carries the object. Once an object is in the
 * log it stays there forever; nothing removes geometry (CLAUDE.md rule 1).
 *
 * **Presence.** [DeleteObject] and [AddObject] write a *presence register*
 * (SPEC §4.2 calls the delete "a tombstone, never a removal"). See [Presence]
 * below for why presence has to be a register and not a monotone flag.
 *
 * **Absolute LWW registers**, keyed by `(ObjectId, field)`: [SetTransform],
 * [SetStyle], [SetZOrder], [SetTextContent], [SetErasedRegion]. Each carries a
 * whole value, never a difference (CLAUDE.md rule 8). [SetPageMeta] is the same
 * thing keyed on the page instead of an object.
 *
 * ## Ordering
 *
 * Ops are ordered by [clock] alone (SPEC §4.3): sort the union, fold from empty,
 * get the same state everywhere. An op whose clock is below something already
 * applied cannot be appended to the folded state — the page is re-folded from the
 * last snapshot (CLAUDE.md rule 9). `PageLog` enforces that; it is not left to
 * the caller to remember.
 *
 * ## Forward compatibility
 *
 * [extras] carries any JSON members this build did not recognise, verbatim, so a
 * newer app can write a field an older app opens and re-saves without destroying
 * it (SPEC §5.8). It is empty for every op this build creates.
 */
public sealed interface PageOp {

    /** UUIDv7. Ops are never looked up by id; this exists for dedup and for logs. */
    public val opId: String

    public val clock: LamportClock

    /** Unrecognised JSON members, preserved verbatim (SPEC §5.8). */
    public val extras: OpExtras

    /** The object this op affects, or null for page-level ops. */
    public val target: ObjectId?

    // --- Grow-only set + presence -------------------------------------------

    /**
     * Introduces an object.
     *
     * A second [AddObject] for the same [ObjectId] keeps the first payload and only
     * writes presence — see [Presence]. Ids are UUIDv7 minted by the creating
     * device, so two devices cannot concurrently add the same id; the only way this
     * happens is a local undo of a local delete.
     */
    public data class AddObject(
        override val opId: String,
        override val clock: LamportClock,
        val obj: InkObject,
        override val extras: OpExtras = OpExtras.None,
    ) : PageOp {
        override val target: ObjectId get() = obj.id
    }

    /**
     * A tombstone. Never a removal — the [AddObject] stays in the log, so the
     * object's geometry is still there and undo is a re-add rather than a rebuild.
     *
     * **A delete of an object that does not exist is recorded anyway** (SPEC §4.2).
     * Its [AddObject] may arrive later, and that is precisely what makes arrival
     * order irrelevant.
     */
    public data class DeleteObject(
        override val opId: String,
        override val clock: LamportClock,
        override val target: ObjectId,
        override val extras: OpExtras = OpExtras.None,
    ) : PageOp

    // --- Absolute LWW registers ---------------------------------------------

    /**
     * ⭐ An **absolute** transform. SPEC §4.2's worked example is the reason:
     *
     * > Device A scales object Y by 2×. Device B concurrently rotates Y by 45°. A
     * > then undoes with an inverse-scale op. Total order is `scale, rotate,
     * > unscale` — the rotation gets smeared by the non-commuting scale, and Y ends
     * > up skewed. Consistently, on both devices, silently.
     *
     * With absolute matrices, concurrent writes are last-writer-wins, undo writes
     * back the previous matrix, and a transform aimed at a tombstoned object is a
     * no-op. `PageFoldTest` runs that example.
     */
    public data class SetTransform(
        override val opId: String,
        override val clock: LamportClock,
        override val target: ObjectId,
        val absolute: Matrix3,
        override val extras: OpExtras = OpExtras.None,
    ) : PageOp

    /** Absolute style — colour, width, opacity. See [ObjectStyle] on the naming. */
    public data class SetStyle(
        override val opId: String,
        override val clock: LamportClock,
        override val target: ObjectId,
        val style: ObjectStyle,
        override val extras: OpExtras = OpExtras.None,
    ) : PageOp

    /** Absolute z-order. A fractional key, so two devices can insert without agreeing. */
    public data class SetZOrder(
        override val opId: String,
        override val clock: LamportClock,
        override val target: ObjectId,
        val z: FractionalKey,
        override val extras: OpExtras = OpExtras.None,
    ) : PageOp

    /**
     * Absolute text content.
     *
     * Whole-string LWW, not a text CRDT. Two people typing in one text box on two
     * tablets is real-time collaboration, which SPEC §12 puts out of scope; the
     * conflict policy that does apply is never to discard bytes, and a conflicted
     * copy (SPEC §7.7) preserves both texts without a character-level merge.
     */
    public data class SetTextContent(
        override val opId: String,
        override val clock: LamportClock,
        override val target: ObjectId,
        val text: String,
        override val extras: OpExtras = OpExtras.None,
    ) : PageOp

    /**
     * ⭐ Absolute presentation record — SPEC §4.9.2. **The one op added after M2.**
     *
     * A text box's font, size, alignment and list level; an image's crop. Encoded
     * like [ShapeRecord] — a short ASCII `k=v;k=v` string with unknown keys kept
     * verbatim — because the alternative is a fixed arity of numbers per kind, and
     * the kinds do not agree on what the numbers are.
     *
     * ## Why this is a second register and not [SetTextContent]
     *
     * §4.8.2 put a *shape's* record in [SetTextContent], which is right there: every
     * property in a `ShapeRecord` is comparably cheap to redo, so the LWW register's
     * whole-value resolution costs at most one re-click.
     *
     * A text box is not that shape of problem. Its [SetTextContent] holds **the words
     * the user typed**, and an LWW register resolves *whole* — so sharing it with the
     * font would mean **changing the font on one device discards a paragraph typed on
     * the other**, converged, with every op present and no error. That is the cheap
     * edit destroying the expensive one, which is CLAUDE.md rule 1 written into the
     * format. Two registers make the two edits commute.
     *
     * ## Why adding an op type was affordable, when adding an object kind is not
     *
     * An unknown **op type** is preserved verbatim and simply not applied — `OpCodec`
     * re-emits [Unknown]'s own bytes, `PageFolder` carries it in `unknownOps`, and a
     * compaction snapshot re-seeds it. An unknown **object kind** makes
     * `OpCodec.decodeObject` throw inside an unguarded `BundleReader`, which is a note
     * that will not open. §4.9.1 has the table; the two were conflated before M8c.
     *
     * No build before M8c can render a text box or an image at all, so the degrade
     * here is invisible in every build that exists.
     */
    public data class SetFormat(
        override val opId: String,
        override val clock: LamportClock,
        override val target: ObjectId,
        val record: String,
        override val extras: OpExtras = OpExtras.None,
    ) : PageOp

    /**
     * ⭐ The whole erased area, absolutely (SPEC §3.5, revised in M1; CLAUDE.md
     * rule 8).
     *
     * Not in SPEC §4.2's original list, because §3.5 originally deferred partial
     * erase to M8a. M1 rejected that on the tablet — an eraser that deletes a whole
     * line because you clipped its tail — and the area-based model it adopted needs
     * an op that §4.2 could not express. It is **absolute** like every other
     * register here, and for one extra reason: undo writes back the region as it
     * was, which a difference cannot describe.
     */
    public data class SetErasedRegion(
        override val opId: String,
        override val clock: LamportClock,
        override val target: ObjectId,
        val absolute: ErasedArea,
        override val extras: OpExtras = OpExtras.None,
    ) : PageOp

    /**
     * Page-level registers: the paper's background, extent, position and whether it grows. Null
     * means "leave that one alone", which is not a delta — each field is its own LWW register,
     * and this op writes whichever ones it names with an absolute value.
     *
     * ⭐ **[origin] and [infinite] are the infinite canvas** (SPEC §4.5). [origin] is the paper's
     * top-left **in page space**, `(0, 0)` on every fixed page and negative on one that has grown
     * up or to the left; [infinite] is whether the page grows to hold what is written on it.
     *
     * ⚠️ **Growing the canvas must never move an object**, which is the whole reason the origin is
     * a page register rather than a re-anchoring of page space. Extending the paper upwards writes
     * a negative [origin] and nothing else — every stroke, capsule and transform keeps the
     * coordinates its author gave it, so growth is one absolute op (CLAUDE.md rule 8) rather than
     * a `SetTransform` per object, and a peer's concurrent stroke still lands where they drew it.
     * The alternative — shifting page space and translating everything — is a rewrite of the page
     * that a concurrent `AddObject` from a peer would silently miss.
     *
     * Both are omitted from the encoding when null, so a build that never sets them writes exactly
     * the bytes it wrote before they existed, and an older build reading them ignores two unknown
     * fields (SPEC §5.8) and sees the page at the extent it has grown to.
     */
    public data class SetPageMeta(
        override val opId: String,
        override val clock: LamportClock,
        val background: Background? = null,
        val size: PageSize? = null,
        val origin: PagePoint? = null,
        val infinite: Boolean? = null,
        override val extras: OpExtras = OpExtras.None,
    ) : PageOp {
        override val target: ObjectId? get() = null

        init {
            require(background != null || size != null || origin != null || infinite != null) {
                "SetPageMeta must set at least one field"
            }
        }
    }

    /**
     * An op this build does not understand, kept verbatim (SPEC §5.8).
     *
     * A newer version of the app writing a new op type must not cause an older
     * version to destroy it on the next save. So an unrecognised op is carried
     * through the fold as a no-op and written back exactly as it was read —
     * including its [clock], so it keeps its place in the total order and a
     * newer build reading the file later still folds it correctly.
     *
     * [rawJson] is the op's own JSON object, bytes as they were on disk. Bytes
     * rather than a parsed tree because verbatim is the requirement and a
     * re-serialised tree is only as verbatim as the serialiser.
     */
    public data class Unknown(
        override val opId: String,
        override val clock: LamportClock,
        val type: String,
        val rawJson: ByteArray,
        override val extras: OpExtras = OpExtras.None,
    ) : PageOp {
        override val target: ObjectId? get() = null

        override fun equals(other: Any?): Boolean = this === other ||
            (
                other is Unknown && opId == other.opId && clock == other.clock &&
                    type == other.type && rawJson.contentEquals(other.rawJson)
                )

        override fun hashCode(): Int {
            var result = opId.hashCode()
            result = HASH_MULTIPLIER * result + clock.hashCode()
            result = HASH_MULTIPLIER * result + type.hashCode()
            result = HASH_MULTIPLIER * result + rawJson.contentHashCode()
            return result
        }

        private companion object {
            const val HASH_MULTIPLIER = 31
        }
    }
}

/**
 * Unrecognised JSON members of a *known* op, preserved verbatim (SPEC §5.8).
 *
 * The unknown-op case is [PageOp.Unknown]; this is the subtler one — a newer build
 * adding a field to an op type that already exists. Dropping it would silently
 * degrade the newer device's data on every save the older device makes.
 *
 * [rawJson] holds the members only (`"a":1,"b":2`, no braces), so writing them back
 * is a string splice with no re-encoding.
 */
public class OpExtras(public val rawJson: String) {

    public val isEmpty: Boolean get() = rawJson.isEmpty()

    override fun equals(other: Any?): Boolean = this === other || (other is OpExtras && rawJson == other.rawJson)

    override fun hashCode(): Int = rawJson.hashCode()

    override fun toString(): String = if (isEmpty) "OpExtras.None" else "OpExtras($rawJson)"

    public companion object {
        public val None: OpExtras = OpExtras("")
    }
}

/**
 * Whether an object is currently on the page.
 *
 * ## Why presence is a register and not a one-way flag
 *
 * SPEC §4.2 says a delete is "a tombstone, never a removal", and SPEC §4.6 says
 * undo of a delete is an [PageOp.AddObject]. Both can only be true if presence is
 * an LWW register: a monotone tombstone can never be undone, so the second
 * statement would be unimplementable.
 *
 * That is safe here in a way it would not be in a general CRDT, because
 * [ObjectId]s are UUIDv7 minted by the device that creates the object. Two devices
 * cannot concurrently add the same id, so the only writer that can ever *un*-delete
 * is the device that deleted it, undoing itself. "Tombstone, never a removal"
 * remains exactly true of the data: the [PageOp.AddObject] and its geometry stay in
 * the log forever, and only visibility moves.
 *
 * SPEC §4.2 has been amended to record this.
 */
public enum class Presence { PRESENT, DELETED }
