package dev.inkstone.core.model

/**
 * SPEC §5.4 ⭐ — stroke geometry as an **opaque blob**.
 *
 * `:android:ink` produces these and `:android:ink` consumes them; `:core:*`
 * stores, journals, bundles and syncs them without ever looking inside. That is
 * the whole point:
 *
 * - **Size.** Points as JSON run 60–80 bytes each. A dense page is thousands of
 *   strokes sampled at ~200 Hz, so a notebook page becomes megabytes of text to
 *   parse — against a stated 2-second open target for 200 pages (SPEC §10).
 * - **Fidelity.** The blob carries pressure, tilt, orientation, tool type and the
 *   brush, because `androidx.ink`'s own serializer wrote it. A core-side point
 *   type would have to enumerate those fields and would silently drop whatever it
 *   forgot.
 * - **Cost.** No point-by-point conversion between an `androidx.ink.Stroke` and a
 *   core type on every save and every load.
 *
 * The accepted tradeoff (SPEC §5.4): a future iOS renderer cannot decode this. It
 * is recorded there as a decision rather than a discovery at M11. **Merge and sync
 * are unaffected** — they operate on op ids and clocks, never on geometry, which
 * is exactly why this can be opaque at all.
 *
 * Equality is by content, so a round-trip test can compare two [StrokeObject]s
 * directly. That is worth the scan: the alternative is every format test
 * comparing byte arrays by hand.
 */
public class StrokeGeometry(public val bytes: ByteArray) {

    public val sizeBytes: Int get() = bytes.size

    override fun equals(other: Any?): Boolean =
        this === other || (other is StrokeGeometry && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "StrokeGeometry(${bytes.size} bytes)"
}

/**
 * SPEC §3.5 / CLAUDE.md rule 8 — the area an eraser has taken out of one object.
 *
 * The durable form of what M1's `ErasedRegion` holds: the eraser's swept capsules,
 * five floats each, in page space. Resolution-independent and replayable
 * anywhere, which the render-side `Path` cache is not.
 *
 * This is what `SetErasedRegion` carries, and it carries **all of it** — the whole
 * region, absolutely, not the capsules one gesture added. Rule 8 again, and for a
 * second reason here: undo writes back the region as it was, which is impossible
 * if the op only describes a difference.
 *
 * Erasing never touches the stroke it erases. Nothing is destroyed, so nothing has
 * to be reconstructed to un-erase (CLAUDE.md rule 1).
 */
public data class ErasedArea(val capsules: List<EraseCapsule>) {

    public val isEmpty: Boolean get() = capsules.isEmpty()

    public companion object {
        public val None: ErasedArea = ErasedArea(emptyList())
    }
}

/** One eraser sample: the segment its tip swept, and the tip radius, in page points. */
public data class EraseCapsule(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float, val radius: Float)

/**
 * An object's visual style, as an **absolute** LWW register (SPEC §4.2).
 *
 * ## Note on the spec
 *
 * SPEC §4.2 names this type `StyleDelta`. That name contradicts the rule the same
 * section states two paragraphs later — "same for `SetStyle` and `SetZOrder`",
 * meaning absolute values — and a type called `Delta` is exactly the thing a
 * future contributor would implement as one. It is [ObjectStyle] here, holding a
 * complete style, and SPEC §4.2 has been corrected to match.
 *
 * ## Why nullable on the object
 *
 * A stroke's colour and width are already inside its geometry blob, where
 * `androidx.ink` put them at capture time. Duplicating them into a style register
 * would create two sources of truth that drift. So a [StrokeObject] carries a
 * *null* style meaning "as captured", and a non-null one only once something has
 * explicitly restyled it (M8's selection tools). Images and text boxes have no
 * capture-time style and always carry one.
 */
public data class ObjectStyle(val colorArgb: Int, val widthPt: Float, val opacity: Float = 1f) {
    init {
        require(widthPt >= 0f) { "widthPt must not be negative, was $widthPt" }
        require(opacity in 0f..1f) { "opacity must be within 0..1, was $opacity" }
    }
}

/**
 * SPEC §4.2 — anything an op can target: "stroke, image, textbox, shape".
 *
 * All four are defined now, for the same reason the whole op vocabulary is: the
 * vocabulary is what the sync format is made of, and adding to it after sync is
 * live is the milestone where everything breaks. M8 is the first user of the
 * non-stroke variants; they exist here so that when it arrives it adds behaviour
 * and not a format version.
 *
 * [transform] is identity for anything captured in page space, which is every
 * stroke — ink is authored directly in page space (SPEC §4.4 rule 1), so nothing
 * needs transforming until the user moves it.
 */
public sealed interface InkObject {
    public val id: ObjectId

    /** Absolute, never a delta (CLAUDE.md rule 8). */
    public val transform: Matrix3

    /** Null means "as captured" — see [ObjectStyle]. */
    public val style: ObjectStyle?

    /** Z-order within the page, resolved by `(z, id)`. */
    public val z: FractionalKey

    /** The area an eraser has removed. [ErasedArea.None] for an untouched object. */
    public val erased: ErasedArea

    /** Every variant supports the same absolute registers, so the fold needs no `when`. */
    public fun withRegisters(
        transform: Matrix3 = this.transform,
        style: ObjectStyle? = this.style,
        z: FractionalKey = this.z,
        erased: ErasedArea = this.erased,
    ): InkObject
}

/** A pen stroke. [geometry] is opaque to this module — see [StrokeGeometry]. */
public data class StrokeObject(
    override val id: ObjectId,
    val geometry: StrokeGeometry,
    override val transform: Matrix3 = Matrix3.Identity,
    override val style: ObjectStyle? = null,
    override val z: FractionalKey = FractionalKey.Initial,
    override val erased: ErasedArea = ErasedArea.None,
) : InkObject {
    override fun withRegisters(
        transform: Matrix3,
        style: ObjectStyle?,
        z: FractionalKey,
        erased: ErasedArea,
    ): InkObject = copy(transform = transform, style = style, z = z, erased = erased)
}

/**
 * An inserted image. [asset] names an entry under `assets/` in the bundle
 * (SPEC §5.3) — the bytes are not in the op log, because an op log is a log.
 */
public data class ImageObject(
    override val id: ObjectId,
    val asset: String,
    val widthPt: Float,
    val heightPt: Float,
    override val transform: Matrix3 = Matrix3.Identity,
    override val style: ObjectStyle? = null,
    override val z: FractionalKey = FractionalKey.Initial,
    override val erased: ErasedArea = ErasedArea.None,
) : InkObject {
    override fun withRegisters(
        transform: Matrix3,
        style: ObjectStyle?,
        z: FractionalKey,
        erased: ErasedArea,
    ): InkObject = copy(transform = transform, style = style, z = z, erased = erased)
}

/** A typed text box. [text] is the `SetTextContent` register's initial value. */
public data class TextBoxObject(
    override val id: ObjectId,
    val text: String,
    val widthPt: Float,
    val heightPt: Float,
    override val transform: Matrix3 = Matrix3.Identity,
    override val style: ObjectStyle? = null,
    override val z: FractionalKey = FractionalKey.Initial,
    override val erased: ErasedArea = ErasedArea.None,
) : InkObject {
    override fun withRegisters(
        transform: Matrix3,
        style: ObjectStyle?,
        z: FractionalKey,
        erased: ErasedArea,
    ): InkObject = copy(transform = transform, style = style, z = z, erased = erased)
}

/** A recognised or drawn shape, sized in its own unit square and placed by [transform]. */
public data class ShapeObject(
    override val id: ObjectId,
    val shape: ShapeKind,
    val widthPt: Float,
    val heightPt: Float,
    override val transform: Matrix3 = Matrix3.Identity,
    override val style: ObjectStyle? = null,
    override val z: FractionalKey = FractionalKey.Initial,
    override val erased: ErasedArea = ErasedArea.None,
) : InkObject {
    override fun withRegisters(
        transform: Matrix3,
        style: ObjectStyle?,
        z: FractionalKey,
        erased: ErasedArea,
    ): InkObject = copy(transform = transform, style = style, z = z, erased = erased)
}

public enum class ShapeKind { LINE, ARROW, RECTANGLE, ELLIPSE, TRIANGLE }
