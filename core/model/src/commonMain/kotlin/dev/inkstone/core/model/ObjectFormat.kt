package dev.inkstone.core.model

import kotlin.math.roundToLong

/**
 * ⭐ ROADMAP M8c — **how a text box or an image is presented**, as one absolute LWW register.
 *
 * SPEC §4.9 is the normative account; this KDoc is the reasoning that produced it.
 *
 * ## The problem
 *
 * M8c must persist a text box's font, size, alignment and list level, and an image's crop. SPEC
 * §4.2 froze the op vocabulary in M2 and none of those fit any register it declared. The homes
 * that do not work are the same three §4.8.2 ruled out for a shape's record, for the same reasons:
 *
 * - **A new object kind, or a new field on [TextBoxObject] / [ImageObject].** The first makes
 *   `OpCodec.decodeObject` throw inside an unguarded `BundleReader` — a note that will not open.
 *   The second is quieter and no better: `OpExtras` collects unknown members at the op's *top*
 *   level only, so a field hung inside the object payload is read by nobody and dropped on the
 *   next two-second autosave.
 * - **A fourth slot in [ObjectStyle].** A style is encoded as a positional array and decoded back
 *   into a three-field type, so a fourth slot is re-encoded out of existence by any build that
 *   does not name it. Same failure as the payload, one level further in.
 * - **The [PageOp.AddObject] payload.** First-write-wins in the fold, and every one of these
 *   properties is one the user changes after creation.
 *
 * ## Why this is not [PageOp.SetTextContent]
 *
 * §4.8.2 put a *shape's* record in that register, and for a shape it is the right answer — every
 * property in a `ShapeRecord` is cheap to redo, so the LWW register's whole-value resolution costs
 * at most one re-click.
 *
 * A text box's content register holds **the words the user typed**. An LWW register resolves
 * *whole*, so sharing it with the font would mean **changing the font on one device discards a
 * paragraph typed on the other** — converged, on both devices, with every op present and no error.
 * That is the cheap edit destroying the expensive one, which is CLAUDE.md rule 1 written into the
 * format. [PageOp.SetFormat] is a second register so the two edits commute.
 *
 * ## Why one type covers both kinds
 *
 * The same reason `ShapeRecord` carries a fill (only closed shapes have one) and an attachment
 * (only connectors do): one record type with defaults, and only non-defaults encoded. A per-kind
 * record would need a discriminator to tell them apart, and the object already is one.
 *
 * ## ⚠️ Why a text box's **frame** is here and not in [PageOp.SetTransform]
 *
 * A text box has two different things a user could mean by "make this bigger", and until this
 * register carried [boxWidthPt] the app could only do the wrong one. `SetTransform` scales the
 * object's own space, so dragging a corner multiplied every glyph's size along with the frame —
 * `DryTextBox`'s KDoc called that *"the honest behaviour … exactly as it scales a photo"*, and it
 * is honest for a photo and wrong for a paragraph: the words the user typed at 14 pt came back at
 * 21 pt, and nothing re-wrapped. What resizing a text box means is **a different wrap width**, so
 * it is a change to the object's frame and not to the space its contents live in.
 *
 * It is a [PageOp.SetFormat] key rather than a new op for [ObjectFormat]'s own reason — the
 * vocabulary is frozen (SPEC §4.2) and this register is the extensible one — and it is **absolute**
 * like everything else in it (CLAUDE.md rule 8): the value written is the resulting width, never a
 * delta, so two devices resizing the same box converge on one of the two sizes rather than on the
 * product of both.
 *
 * Null means *"the extents the object was created with"* ([TextBoxObject.widthPt]). It has to mean
 * that rather than defaulting to a number, because the payload is first-write-wins and is the only
 * record of how big a box was placed; a default here would resize every text box in every note
 * written before this build.
 *
 * @property listLevel 0 means "not a list". 1 and up are nesting depths.
 * @property crop null means the whole image. Only an [ImageObject] has one.
 * @property boxWidthPt the frame's width in page points, overriding the payload's. Null means the
 *   payload's. Only a [TextBoxObject] uses it — an image is scaled, because scaling a photo is
 *   what resizing one means.
 * @property boxHeightPt the frame's height, on the same terms.
 * @property knockout ⭐ hides whatever is **underneath** this object's filled silhouette, without
 *   touching it (SPEC §4.9.6). Any kind may carry it — a stroke, a shape, an image or a text box —
 *   which is why it lives in this register and not in a kind's own record: the fold keeps a
 *   `SetFormat` side table for every object (`FoldedPage.formats`), so no new op and no new payload
 *   field is needed, and a build that predates the key carries it in [extras] and simply draws the
 *   object without the effect. Non-destructive by construction: nothing beneath is written to, so
 *   moving the object away, or switching this off, brings everything back exactly.
 * @property cutter ⭐ an **invisible** knockout area (SPEC §4.9.6): hides what is beneath its filled
 *   outline and is not itself drawn — except, as a dashed outline, while an Element is being edited.
 *   Implies [knockout]. Only meaningful on a closed shape; anything else carrying it hides nothing.
 * @property instance ⭐ which Element, and which drop of it, this object belongs to (SPEC §4.9.7) —
 *   what lets an edited Element find and replace its copies. See [ElementInstance].
 * @property elementFrame ⭐ this rectangle is the **bounds** of the Element being edited (SPEC
 *   §4.9.7) — a guide on the Element editor's canvas, never part of what is saved. An object, and not
 *   a value the screen holds, so that moving, resizing, snapping and undoing the bounds are the same
 *   gestures, on the same undo stack, as everything else in the editor.
 * @property framePivot the Element's pivot as an offset **in page points from the frame's top-left
 *   corner**, or null for its centre. On the frame rather than an object of its own so it moves with
 *   the bounds; points rather than a fraction so resizing the bounds does not slide it off the grid
 *   point it was put on (`ElementEditing`).
 * @property extras keys this build does not recognise, kept verbatim and written back — what
 *   [OpExtras] does for an op, one level down. Without it a later build adding a key would have it
 *   destroyed by every M8c device that opened the note.
 */
public data class ObjectFormat(
    val font: TextFont = TextFont.SANS,
    val sizePt: Float = DEFAULT_SIZE_PT,
    val align: TextAlign = TextAlign.START,
    val listLevel: Int = 0,
    val listStyle: ListStyle = ListStyle.NONE,
    val crop: ImageCrop? = null,
    val boxWidthPt: Float? = null,
    val boxHeightPt: Float? = null,
    val knockout: Boolean = false,
    val cutter: Boolean = false,
    val instance: ElementInstance? = null,
    val elementFrame: Boolean = false,
    val framePivot: PagePoint? = null,
    val extras: Map<String, String> = emptyMap(),
) {

    /** Whether this object hides what is beneath it — a flagged object, or an invisible cutter. */
    public val hidesBeneath: Boolean get() = knockout || cutter

    /** The frame this object is laid out in, given the extents its payload was created with. */
    public fun frameWidth(payloadWidthPt: Float): Float = boxWidthPt ?: payloadWidthPt

    /** The frame's height, on [frameWidth]'s terms. */
    public fun frameHeight(payloadHeightPt: Float): Float = boxHeightPt ?: payloadHeightPt

    /**
     * True when this is the default presentation — nothing to persist.
     *
     * ⚠️ [extras] counts. A record carrying only a key from a *later* build is not default, and
     * treating it as one would drop that build's data on the first restyle here.
     */
    public val isDefault: Boolean
        get() = this == Default && extras.isEmpty()

    /** The encoded register value. Empty for the default, so nothing is written for nothing. */
    public fun encode(): String = if (isDefault) "" else ObjectFormatCodec.encode(this)

    public companion object {
        public const val DEFAULT_SIZE_PT: Float = 14f

        /** Every field at its default. [ObjectFormat.isDefault] also requires empty extras. */
        public val Default: ObjectFormat = ObjectFormat()

        /**
         * Reads a register value. Anything unparseable — including a genuine piece of text left in
         * the wrong register — yields [Default] rather than a guess.
         */
        public fun decode(record: String?): ObjectFormat = ObjectFormatCodec.decode(record) ?: Default
    }
}

/**
 * The three families M8c ships, as a closed enum rather than a font *name*.
 *
 * ⚠️ **Deliberately not an arbitrary family string.** A record is a `k=v;k=v` string, so a value
 * containing `;` or `=` corrupts it — and a font family name is the one value here a user could
 * ever supply. A closed set makes that unrepresentable rather than validated. It is also what lets
 * the PDF writer guarantee an embeddable font for every value (SPEC §4.9.5).
 */
public enum class TextFont { SANS, SERIF, MONO }

public enum class TextAlign { START, CENTER, END }

public enum class ListStyle { NONE, BULLET, NUMBERED }

/**
 * An image crop, in **fractions of the source image's own box**, never in page points.
 *
 * ⚠️ Normalised for the same reason SPEC §4.8.3 pins a connector's anchor inside its target's box:
 * a crop stored in page space would need rewriting under every resize, rotate and flip — and those
 * are `SetTransform`, a *different* register, so the two would have to be written together and
 * would then be two absolute values that can disagree. Normalised, the crop is carried by the
 * object's matrix like any other point on it, and a resize writes one op.
 */
public sealed interface ImageCrop {

    /** The common case, and the one with drag handles. Fractions in `[0,1]`, `left < right`. */
    public data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) : ImageCrop {
        public val isWhole: Boolean
            get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f
    }

    /**
     * A freehand crop: a closed polygon of normalised `x,y` pairs.
     *
     * A polygon rather than a curve because it is what a clip path in a PDF content stream can say
     * without an appearance stream, and what a `Path` on the ink side rasterises identically.
     */
    public data class Polygon(val points: List<Float>) : ImageCrop {
        public val pointCount: Int get() = points.size / 2
    }
}

/**
 * The wire format for [ObjectFormat] — a short ASCII record, exactly like `ShapeRecordCodec`.
 *
 * ```
 * if1;f=se;sz=18;a=c;l=2;ls=b
 * if1;c=0.1:0:0.9:1
 * if1;cp=0,0:1,0:1,1
 * ```
 *
 * Terse and ASCII-only for the same reasons the shape record is: it is written on every two-second
 * autosave, read on every open, and lives inside a JSON string where every byte may need escaping.
 *
 * ## Rules that keep it safe
 *
 * - **The `if1` prefix is the discriminator.** Anything else — including an empty string, and
 *   including a *shape's* `is1` record — decodes to null. The two records share a codec shape but
 *   not a register, and a build that confused them would draw a fill as a font size.
 * - **Unknown keys are kept** ([ObjectFormat.extras]).
 * - **Floats are formatted by hand** so the same record produces the same bytes on Kotlin/JVM and
 *   Kotlin/Native. `Float.toString` is not specified to agree, and a re-save that rewrites a
 *   register only because the formatter disagreed is churn the sync engine would upload.
 * - **Every value this build writes is separator-free by construction** — enums encode to fixed
 *   tags, numbers to digits. That is a property of the *types*, not a runtime check, which is why
 *   [TextFont] is a closed enum and not a font family string.
 *
 * ⚠️ `ShapeRecordCodec`'s KDoc claimed to *reject* separator-bearing values and never checked;
 * it was safe only because everything it had ever encoded was a UUID or a formatted float. The
 * lesson taken here is not "add the check" — a check would break forward compatibility the first
 * time a later build put a comma in an extras value — but **make the values unable to contain
 * one**.
 */
private const val K_BOX_WIDTH = "w"
private const val K_BOX_HEIGHT = "h"
private const val K_KNOCKOUT = "ko"
private const val K_CUTTER = "cut"
private const val K_ELEMENT = "el"
private const val K_FRAME = "ef"
private const val K_PIVOT = "pv"
private const val PIVOT_PARTS = 2

/** The only value this build writes for [K_KNOCKOUT]. Absent means off, so off is never written. */
private const val KNOCKOUT_ON = "1"

/**
 * A text box's frame, from one record key.
 *
 * Top-level so it costs [ObjectFormatCodec] no size, which is the same reason `countShapes` sits
 * beside `SelectionController` rather than inside it.
 *
 * A frame with no extent cannot be grabbed again and gives the object a singular matrix — what
 * `ShapeGeometry.MIN_EXTENT_PT` guards on the payload side. **Refused rather than clamped**: an
 * unreadable value leaves the property alone, which here means *"the extents it was created with"*,
 * a size the user has already seen rather than one this build invented.
 */
private fun ObjectFormat.withFrame(key: String, value: String): ObjectFormat {
    val extent = value.toFloatOrNull()?.takeIf { it >= ShapeGeometry.MIN_EXTENT_PT } ?: return this
    return if (key == K_BOX_WIDTH) copy(boxWidthPt = extent) else copy(boxHeightPt = extent)
}

/**
 * The three keys knockout and Element copies added (SPEC §4.9.6–7), outside [ObjectFormatCodec] so the
 * encoder stays a list of one line per property — and so that object stays under detekt's size limit.
 */
private fun elementParts(format: ObjectFormat, equals: Char): List<String> = buildList {
    if (format.knockout) add("$K_KNOCKOUT$equals$KNOCKOUT_ON")
    if (format.cutter) add("$K_CUTTER$equals$KNOCKOUT_ON")
    // Ids a separator could hide in are not written at all — an instance that will not decode is
    // worse than none, because the copy it names could then never be found again.
    format.instance
        ?.takeIf { ElementInstance.isCarriable(it.elementId) && ElementInstance.isCarriable(it.instanceId) }
        ?.let { add("$K_ELEMENT$equals${it.encode()}") }
    if (format.elementFrame) add("$K_FRAME$equals$KNOCKOUT_ON")
    format.framePivot?.let { add("$K_PIVOT$equals${RecordText.number(it.x)}:${RecordText.number(it.y)}") }
}

/** A frame's pivot, `x:y` — null for anything else, which [ObjectFormat.extras] then keeps. */
private fun pivotOf(value: String): PagePoint? {
    val parts = value.split(':')
    if (parts.size != PIVOT_PARTS) return null
    val x = parts[0].toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
    val y = parts[1].toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
    return PagePoint(x, y)
}

/** One of [elementParts]' keys, read back. Only reached with a value the codec's `understood` accepted. */
private fun applyElementKey(format: ObjectFormat, key: String, value: String): ObjectFormat = when (key) {
    K_KNOCKOUT -> format.copy(knockout = true)
    K_CUTTER -> format.copy(cutter = true)
    K_FRAME -> format.copy(elementFrame = true)
    K_PIVOT -> format.copy(framePivot = pivotOf(value))
    else -> format.copy(instance = ElementInstance.decode(value))
}

internal object ObjectFormatCodec {

    private const val PREFIX = "if1"
    private const val PAIR = ';'
    private const val EQUALS = '='
    private const val PART = ':'
    private const val POINT = ','

    private const val K_FONT = "f"
    private const val K_SIZE = "sz"
    private const val K_ALIGN = "a"
    private const val K_LIST_LEVEL = "l"
    private const val K_LIST_STYLE = "ls"
    private const val K_CROP_RECT = "c"
    private const val K_CROP_POLY = "cp"

    private val knownKeys = setOf(
        K_FONT,
        K_SIZE,
        K_ALIGN,
        K_LIST_LEVEL,
        K_LIST_STYLE,
        K_CROP_RECT,
        K_CROP_POLY,
        K_BOX_WIDTH,
        K_BOX_HEIGHT,
        K_KNOCKOUT,
        K_CUTTER,
        K_ELEMENT,
        K_FRAME,
        K_PIVOT,
    )

    private const val RECT_PARTS = 4
    private const val RECT_LEFT = 0
    private const val RECT_TOP = 1
    private const val RECT_RIGHT = 2
    private const val RECT_BOTTOM = 3
    private const val MIN_POLYGON_POINTS = 3

    fun encode(format: ObjectFormat): String {
        val parts = ArrayList<String>()
        parts += PREFIX
        if (format.font != TextFont.SANS) parts += "$K_FONT$EQUALS${fontTag(format.font)}"
        if (format.sizePt != ObjectFormat.DEFAULT_SIZE_PT) {
            parts += "$K_SIZE$EQUALS${RecordText.number(format.sizePt)}"
        }
        if (format.align != TextAlign.START) parts += "$K_ALIGN$EQUALS${alignTag(format.align)}"
        if (format.listLevel != 0) parts += "$K_LIST_LEVEL$EQUALS${format.listLevel}"
        if (format.listStyle != ListStyle.NONE) {
            parts += "$K_LIST_STYLE$EQUALS${listTag(format.listStyle)}"
        }
        when (val crop = format.crop) {
            null -> Unit
            is ImageCrop.Rect -> parts += "$K_CROP_RECT$EQUALS${encodeRect(crop)}"
            is ImageCrop.Polygon -> parts += "$K_CROP_POLY$EQUALS${encodePolygon(crop)}"
        }
        // Null is "the extents it was created with", so an absent key and a key holding the payload's
        // own number are deliberately different records: writing the second would put a frame in
        // every note that has never been resized.
        format.boxWidthPt?.let { parts += "$K_BOX_WIDTH$EQUALS${RecordText.number(it)}" }
        format.boxHeightPt?.let { parts += "$K_BOX_HEIGHT$EQUALS${RecordText.number(it)}" }
        parts += elementParts(format, EQUALS)
        // Sorted, so identical content produces identical bytes whatever order a future build read
        // them in — the discipline `BundleReader` applies to unknown manifest members (SPEC §5.8).
        // `sortedBy`, not `toSortedMap`: the latter is JVM-only and fails the iOS compile.
        // ⚠️ Extras are **not** validated. They are a later build's bytes, and this build's job is
        // to hand them back unchanged (SPEC §5.8) — rejecting one because it contains a separator
        // would turn forward compatibility into a crash on save, which is worse than the silent
        // truncation the validation was meant to prevent. What keeps *this* build's own values
        // safe is that none of them is a free string: see the note on [TextFont].
        for (entry in format.extras.entries.sortedBy { it.key }) {
            parts += "${entry.key}$EQUALS${entry.value}"
        }
        return parts.joinToString(PAIR.toString())
    }

    fun decode(text: String?): ObjectFormat? {
        if (text == null) return null
        val parts = text.split(PAIR)
        if (parts.firstOrNull() != PREFIX) return null
        var format = ObjectFormat.Default
        val extras = LinkedHashMap<String, String>()
        for (index in 1 until parts.size) {
            val pair = parts[index]
            val split = pair.indexOf(EQUALS)
            if (split <= 0) continue
            val key = pair.substring(0, split)
            val value = pair.substring(split + 1)
            if (key in knownKeys && understood(key, value)) {
                format = applyKnown(format, key, value)
            } else {
                extras[key] = value
            }
        }
        return format.copy(extras = extras)
    }

    /**
     * False for a known key carrying a value this build did not write, which is then kept verbatim
     * in [ObjectFormat.extras] instead of being interpreted.
     *
     * Only the knockout flag needs it. A later build that widens `ko` into a mode (`ko=2`) must get
     * its value back on the next autosave here, and parsing it as "off" would write nothing and
     * destroy it; parsing it as "on" would hide ink this build cannot know should be hidden.
     */
    private fun understood(key: String, value: String): Boolean = when (key) {
        K_KNOCKOUT, K_CUTTER, K_FRAME -> value == KNOCKOUT_ON
        K_ELEMENT -> ElementInstance.decode(value) != null
        K_PIVOT -> pivotOf(value) != null
        else -> true
    }

    /**
     * One key, applied if it parses.
     *
     * ⚠️ **Every branch falls back to the record unchanged** — the fail-closed rule
     * `ShapeRecordCodec` is built on. A record is several independent properties, and one
     * unreadable crop is no reason to forget a font size.
     */
    private fun applyKnown(format: ObjectFormat, key: String, value: String): ObjectFormat = when (key) {
        K_FONT -> format.withFont(value)

        K_SIZE -> format.withSize(value)

        K_ALIGN -> format.withAlign(value)

        K_LIST_LEVEL -> format.withListLevel(value)

        K_LIST_STYLE -> format.withListStyle(value)

        K_CROP_RECT -> decodeRect(value)?.let { format.copy(crop = it) } ?: format

        K_CROP_POLY -> decodePolygon(value)?.let { format.copy(crop = it) } ?: format

        // Only ever reached with the one value this build writes — see [understood].
        K_KNOCKOUT, K_CUTTER, K_ELEMENT, K_FRAME, K_PIVOT -> applyElementKey(format, key, value)

        // The frame, in the `else` rather than in two branches of its own. `applyKnown` is reached
        // only for a key in [knownKeys], so the remaining two are exactly `w` and `h`.
        else -> format.withFrame(key, value)
    }

    // One per key, so the dispatch above stays a table. Each is the fail-closed rule spelled once:
    // an unreadable value leaves its own property alone and touches nothing else.
    private fun ObjectFormat.withFont(value: String) = fontOf(value)?.let { copy(font = it) } ?: this

    private fun ObjectFormat.withSize(value: String) =
        value.toFloatOrNull()?.takeIf { it > 0f }?.let { copy(sizePt = it) } ?: this

    private fun ObjectFormat.withAlign(value: String) = alignOf(value)?.let { copy(align = it) } ?: this

    private fun ObjectFormat.withListLevel(value: String) =
        value.toIntOrNull()?.takeIf { it >= 0 }?.let { copy(listLevel = it) } ?: this

    private fun ObjectFormat.withListStyle(value: String) = listOf(value)?.let { copy(listStyle = it) } ?: this

    private fun encodeRect(crop: ImageCrop.Rect): String = listOf(
        crop.left,
        crop.top,
        crop.right,
        crop.bottom,
    ).joinToString(PART.toString()) { RecordText.number(it) }

    private fun decodeRect(value: String): ImageCrop.Rect? {
        val parts = value.split(PART)
        if (parts.size != RECT_PARTS) return null
        val numbers = parts.map { it.toFloatOrNull() ?: return null }
        // Named indices: `parts` is known to be [RECT_PARTS] long here, and the order is the wire
        // format's, not an arbitrary one.
        val left = numbers[RECT_LEFT]
        val top = numbers[RECT_TOP]
        val right = numbers[RECT_RIGHT]
        val bottom = numbers[RECT_BOTTOM]
        // A degenerate crop would render an image with no pixels and no way back through the UI.
        if (right <= left || bottom <= top) return null
        return ImageCrop.Rect(left, top, right, bottom)
    }

    private fun encodePolygon(crop: ImageCrop.Polygon): String {
        val builder = StringBuilder()
        for (index in 0 until crop.pointCount) {
            if (index > 0) builder.append(PART)
            builder.append(RecordText.number(crop.points[index * 2]))
            builder.append(POINT)
            builder.append(RecordText.number(crop.points[index * 2 + 1]))
        }
        return builder.toString()
    }

    private fun decodePolygon(value: String): ImageCrop.Polygon? {
        val pairs = value.split(PART)
        if (pairs.size < MIN_POLYGON_POINTS) return null
        val points = ArrayList<Float>(pairs.size * 2)
        for (pair in pairs) {
            val comma = pair.indexOf(POINT)
            if (comma <= 0) return null
            val x = pair.substring(0, comma).toFloatOrNull() ?: return null
            val y = pair.substring(comma + 1).toFloatOrNull() ?: return null
            points += x
            points += y
        }
        return ImageCrop.Polygon(points)
    }

    private const val TAG_SERIF = "se"
    private const val TAG_MONO = "mo"
    private const val TAG_CENTER = "c"
    private const val TAG_END = "e"
    private const val TAG_BULLET = "b"
    private const val TAG_NUMBERED = "n"

    private fun fontTag(font: TextFont): String = when (font) {
        TextFont.SANS -> "sa"
        TextFont.SERIF -> TAG_SERIF
        TextFont.MONO -> TAG_MONO
    }

    private fun fontOf(tag: String): TextFont? = when (tag) {
        "sa" -> TextFont.SANS
        TAG_SERIF -> TextFont.SERIF
        TAG_MONO -> TextFont.MONO
        else -> null
    }

    private fun alignTag(align: TextAlign): String = when (align) {
        TextAlign.START -> "s"
        TextAlign.CENTER -> TAG_CENTER
        TextAlign.END -> TAG_END
    }

    private fun alignOf(tag: String): TextAlign? = when (tag) {
        "s" -> TextAlign.START
        TAG_CENTER -> TextAlign.CENTER
        TAG_END -> TextAlign.END
        else -> null
    }

    private fun listTag(style: ListStyle): String = when (style) {
        ListStyle.NONE -> "x"
        ListStyle.BULLET -> TAG_BULLET
        ListStyle.NUMBERED -> TAG_NUMBERED
    }

    private fun listOf(tag: String): ListStyle? = when (tag) {
        "x" -> ListStyle.NONE
        TAG_BULLET -> ListStyle.BULLET
        TAG_NUMBERED -> ListStyle.NUMBERED
        else -> null
    }
}

/**
 * Shared record-string helpers, so the two record codecs cannot drift.
 *
 * `ShapeRecordCodec` had its own copy of [number] before M8c. Two copies of a byte-stability rule
 * is one copy too many: the whole point of formatting by hand is that every platform produces the
 * same bytes, and that guarantee is only as good as the least-maintained implementation of it.
 */
internal object RecordText {

    /**
     * Three decimal places, no exponent, platform-independent.
     *
     * Sub-micron at any page size, and stable under round-trip: decoding this and re-encoding it
     * produces the same string, which is what keeps an autosave from rewriting a register nobody
     * touched.
     */
    fun number(value: Float): String {
        if (!value.isFinite()) return "0"
        val scaled = (value.toDouble() * SCALE).roundToLong()
        if (scaled % SCALE == 0L) return (scaled / SCALE).toString()
        val negative = scaled < 0
        val magnitude = if (negative) -scaled else scaled
        val whole = magnitude / SCALE
        val fraction = (magnitude % SCALE).toString().padStart(DECIMALS, '0').trimEnd('0')
        return "${if (negative) "-" else ""}$whole.$fraction"
    }

    private const val DECIMALS = 3
    private const val SCALE = 1000L
}
