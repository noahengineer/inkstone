// Fixed-position arrays are this format's compact form: a matrix is six slots, a style three,
// a capsule five (see `encodeErased`). The index IS the field name, and naming each one would
// add a constant per slot without making the layout any easier to check against SPEC §5.3.
@file:Suppress("MagicNumber")

package dev.inkstone.core.storage

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.EraseCapsule
import dev.inkstone.core.model.ErasedArea
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.InkObject
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.OpExtras
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PageTemplate
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.TextBoxObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Ops ⇄ JSON, with stroke geometry left out of the JSON entirely.
 *
 * ## The shape
 *
 * An op is a small JSON object of ids, clocks, types and *offsets* (SPEC §5.3).
 * Geometry goes into a [GeometryWriter] and is referenced as `{"o":offset,"n":length}`
 * into a sibling `.ink` entry — so a page's JSON stays kilobytes whatever is drawn on
 * it, and the geometry is copied byte-for-byte with nothing parsed (SPEC §5.4).
 *
 * ## Field names
 *
 * Short. This file is written every two seconds and read on every open, and `"c"`
 * versus `"clockCounter"` is a few hundred kilobytes across a large notebook. They
 * are also **frozen**: renaming one is a format change requiring a schema bump and a
 * committed fixture (SPEC §5.8).
 *
 * ## Forward compatibility (SPEC §5.8)
 *
 * Two separate obligations, both handled here:
 *
 * - **An unknown op type** decodes to [PageOp.Unknown] carrying its own JSON bytes,
 *   and re-encodes to exactly those bytes.
 * - **An unknown field on a known op** is collected into [OpExtras] and merged back on
 *   encode.
 *
 * Neither is theoretical: a newer build writing either one must not lose data when an
 * older build opens the note and autosaves.
 */
internal object OpCodec {

    /** Compact and stable: no pretty-printing, insertion-ordered keys, no defaults elided. */
    val json: Json = Json {
        prettyPrint = false
        encodeDefaults = true
        isLenient = false
    }

    // Frozen field names. See the note above before changing one.
    private const val F_OP_ID = "i"
    private const val F_COUNTER = "c"
    private const val F_DEVICE = "d"
    private const val F_TYPE = "t"
    private const val F_TARGET = "g"
    private const val F_OBJECT = "obj"
    private const val F_MATRIX = "m"
    private const val F_STYLE = "s"
    private const val F_Z = "z"
    private const val F_TEXT = "x"
    private const val F_FORMAT = "fr"
    private const val F_ERASED = "e"
    private const val F_BACKGROUND = "bg"
    private const val F_SIZE = "sz"
    private const val F_PAGE = "p"

    private const val T_ADD = "add"
    private const val T_DELETE = "del"
    private const val T_TRANSFORM = "xf"
    private const val T_STYLE = "sty"
    private const val T_Z = "zo"
    private const val T_TEXT = "txt"

    /**
     * SPEC §4.9.2 — **the one op type added after M2.**
     *
     * A build that predates it decodes this to [PageOp.Unknown], carries it through the fold, and
     * re-emits these exact bytes on save. That degrade is what made the addition affordable; an
     * unknown *object kind* would instead throw inside an unguarded `BundleReader`.
     */
    private const val T_FORMAT = "fmt"

    private const val T_ERASED = "era"
    private const val T_PAGE_META = "meta"

    private val knownOpFields = setOf(F_OP_ID, F_COUNTER, F_DEVICE, F_TYPE, F_PAGE)

    private val fieldsByType = mapOf(
        T_ADD to setOf(F_OBJECT),
        T_DELETE to setOf(F_TARGET),
        T_TRANSFORM to setOf(F_TARGET, F_MATRIX),
        T_STYLE to setOf(F_TARGET, F_STYLE),
        T_Z to setOf(F_TARGET, F_Z),
        T_TEXT to setOf(F_TARGET, F_TEXT),
        T_FORMAT to setOf(F_TARGET, F_FORMAT),
        T_ERASED to setOf(F_TARGET, F_ERASED),
        T_PAGE_META to setOf(F_BACKGROUND, F_SIZE),
    )

    // -----------------------------------------------------------------------
    // Encode
    // -----------------------------------------------------------------------

    /**
     * @param pageId written into the op when the container mixes pages (the journal
     *   does; a bundle page file does not, because the file *is* the page).
     */
    fun encode(op: PageOp, geometry: GeometryWriter, pageId: String? = null): JsonObject {
        if (op is PageOp.Unknown) {
            // Verbatim, including whatever fields this build could not name. Re-parsed
            // rather than spliced so the result is still one well-formed document; the
            // values inside are untouched.
            return json.parseToJsonElement(op.rawJson.decodeToString()).jsonObject
        }

        val body = buildJsonObject {
            put(F_OP_ID, JsonPrimitive(op.opId))
            put(F_COUNTER, JsonPrimitive(op.clock.counter))
            put(F_DEVICE, JsonPrimitive(op.clock.deviceId))
            put(F_TYPE, JsonPrimitive(typeOf(op)))
            if (pageId != null) put(F_PAGE, JsonPrimitive(pageId))
            putPayload(op, geometry)
        }
        return withExtras(body, op.extras)
    }

    /**
     * The per-type half of [encode], extracted in M8c.
     *
     * Not a style preference: adding `SetFormat` took [encode] to detekt's cyclomatic-complexity
     * threshold exactly. Splitting the envelope from the payload is the honest fix — the `when` is
     * one branch per op type and is meant to be read as a table, so **suppressing the warning would
     * have been the wrong half to keep**: the next op added would have hidden behind the
     * suppression instead of being noticed here.
     */
    private fun JsonObjectBuilder.putPayload(op: PageOp, geometry: GeometryWriter) {
        when (op) {
            is PageOp.AddObject -> put(F_OBJECT, encodeObject(op.obj, geometry))

            is PageOp.DeleteObject -> put(F_TARGET, JsonPrimitive(op.target.value))

            is PageOp.SetTransform -> {
                put(F_TARGET, JsonPrimitive(op.target.value))
                put(F_MATRIX, encodeMatrix(op.absolute))
            }

            is PageOp.SetStyle -> {
                put(F_TARGET, JsonPrimitive(op.target.value))
                put(F_STYLE, encodeStyle(op.style))
            }

            is PageOp.SetZOrder -> {
                put(F_TARGET, JsonPrimitive(op.target.value))
                put(F_Z, JsonPrimitive(op.z.value))
            }

            is PageOp.SetTextContent -> {
                put(F_TARGET, JsonPrimitive(op.target.value))
                put(F_TEXT, JsonPrimitive(op.text))
            }

            is PageOp.SetFormat -> {
                put(F_TARGET, JsonPrimitive(op.target.value))
                put(F_FORMAT, JsonPrimitive(op.record))
            }

            is PageOp.SetErasedRegion -> {
                put(F_TARGET, JsonPrimitive(op.target.value))
                put(F_ERASED, encodeErased(op.absolute))
            }

            is PageOp.SetPageMeta -> {
                op.background?.let { put(F_BACKGROUND, encodeBackground(it)) }
                op.size?.let { put(F_SIZE, encodeSize(it)) }
            }

            is PageOp.Unknown -> error("handled above")
        }
    }

    private fun withExtras(body: JsonObject, extras: OpExtras): JsonObject {
        if (extras.isEmpty) return body
        val parsed = json.parseToJsonElement(extras.rawJson).jsonObject
        // Known fields first, then the preserved ones, so the output is stable and a
        // future field cannot shadow a current one.
        return JsonObject(body + parsed.filterKeys { it !in body })
    }

    private fun typeOf(op: PageOp): String = when (op) {
        is PageOp.AddObject -> T_ADD
        is PageOp.DeleteObject -> T_DELETE
        is PageOp.SetTransform -> T_TRANSFORM
        is PageOp.SetStyle -> T_STYLE
        is PageOp.SetZOrder -> T_Z
        is PageOp.SetTextContent -> T_TEXT
        is PageOp.SetFormat -> T_FORMAT
        is PageOp.SetErasedRegion -> T_ERASED
        is PageOp.SetPageMeta -> T_PAGE_META
        is PageOp.Unknown -> op.type
    }

    private fun encodeObject(obj: InkObject, geometry: GeometryWriter): JsonObject = buildJsonObject {
        put(O_ID, JsonPrimitive(obj.id.value))
        put(O_KIND, JsonPrimitive(kindOf(obj)))
        when (obj) {
            is StrokeObject -> put(O_GEOMETRY, geometry.put(obj.geometry))

            is ImageObject -> {
                put(O_ASSET, JsonPrimitive(obj.asset))
                put(O_WIDTH, JsonPrimitive(obj.widthPt))
                put(O_HEIGHT, JsonPrimitive(obj.heightPt))
            }

            is TextBoxObject -> {
                put(F_TEXT, JsonPrimitive(obj.text))
                put(O_WIDTH, JsonPrimitive(obj.widthPt))
                put(O_HEIGHT, JsonPrimitive(obj.heightPt))
            }

            is ShapeObject -> {
                put(O_SHAPE, JsonPrimitive(obj.shape.name))
                put(O_WIDTH, JsonPrimitive(obj.widthPt))
                put(O_HEIGHT, JsonPrimitive(obj.heightPt))
            }
        }
        if (obj.transform != Matrix3.Identity) put(F_MATRIX, encodeMatrix(obj.transform))
        obj.style?.let { put(F_STYLE, encodeStyle(it)) }
        put(F_Z, JsonPrimitive(obj.z.value))
        if (!obj.erased.isEmpty) put(F_ERASED, encodeErased(obj.erased))
    }

    private fun kindOf(obj: InkObject): String = when (obj) {
        is StrokeObject -> K_STROKE
        is ImageObject -> K_IMAGE
        is TextBoxObject -> K_TEXT
        is ShapeObject -> K_SHAPE
    }

    private fun encodeMatrix(m: Matrix3): JsonArray = buildJsonArray {
        add(JsonPrimitive(m.m00))
        add(JsonPrimitive(m.m01))
        add(JsonPrimitive(m.m02))
        add(JsonPrimitive(m.m10))
        add(JsonPrimitive(m.m11))
        add(JsonPrimitive(m.m12))
    }

    private fun encodeStyle(style: ObjectStyle): JsonArray = buildJsonArray {
        add(JsonPrimitive(style.colorArgb))
        add(JsonPrimitive(style.widthPt))
        add(JsonPrimitive(style.opacity))
    }

    /**
     * Capsules as one flat number array, five per capsule.
     *
     * A scribble-erase produces thousands of them, and an object per capsule would
     * cost `{"fromX":...}` keys on every one — roughly four times the bytes for
     * exactly the same information.
     */
    private fun encodeErased(area: ErasedArea): JsonArray = buildJsonArray {
        for (capsule in area.capsules) {
            add(JsonPrimitive(capsule.fromX))
            add(JsonPrimitive(capsule.fromY))
            add(JsonPrimitive(capsule.toX))
            add(JsonPrimitive(capsule.toY))
            add(JsonPrimitive(capsule.radius))
        }
    }

    fun encodeBackground(background: Background): JsonObject = buildJsonObject {
        when (background) {
            is Background.Blank -> put(B_KIND, JsonPrimitive(B_BLANK))

            is Background.Template -> {
                put(B_KIND, JsonPrimitive(B_TEMPLATE))
                put(B_TEMPLATE, JsonPrimitive(background.template.name))
            }

            is Background.SourcePdfPage -> {
                put(B_KIND, JsonPrimitive(B_PDF))
                put(B_INDEX, JsonPrimitive(background.index))
            }
        }
    }

    fun encodeSize(size: PageSize): JsonArray = buildJsonArray {
        add(JsonPrimitive(size.widthPt))
        add(JsonPrimitive(size.heightPt))
    }

    // -----------------------------------------------------------------------
    // Decode
    // -----------------------------------------------------------------------

    /**
     * @return the op, and the page it belongs to when the container recorded one.
     * @throws IllegalArgumentException when the JSON is not an op at all — a missing id
     *   or clock is corruption, not a future format, and silently inventing one would
     *   put a stroke somewhere in the total order at random.
     */
    fun decode(element: JsonObject, geometry: GeometryReader): DecodedOp {
        val opId = requireNotNull(element[F_OP_ID]?.jsonPrimitive?.content) { "op has no id: $element" }
        val counter = requireNotNull(element[F_COUNTER]?.jsonPrimitive?.long) { "op $opId has no clock" }
        val device = requireNotNull(element[F_DEVICE]?.jsonPrimitive?.content) { "op $opId has no device" }
        val type = requireNotNull(element[F_TYPE]?.jsonPrimitive?.content) { "op $opId has no type" }
        val clock = LamportClock(counter, device)
        val pageId = element[F_PAGE]?.jsonPrimitive?.content

        val known = fieldsByType[type]
        if (known == null) {
            // SPEC §5.8 — a type from a newer build. Keep the bytes, fold it as a no-op,
            // write it back untouched.
            val raw = json.encodeToString(JsonObject.serializer(), element).encodeToByteArray()
            return DecodedOp(PageOp.Unknown(opId, clock, type, raw), pageId)
        }

        val extras = element.filterKeys { it !in knownOpFields && it !in known }
        val opExtras = if (extras.isEmpty()) {
            OpExtras.None
        } else {
            OpExtras(json.encodeToString(JsonObject.serializer(), JsonObject(extras)))
        }

        return DecodedOp(decodeKnown(type, opId, clock, element, geometry, opExtras), pageId)
    }

    /** The type dispatch, split out so [decode]'s validation stays readable beside it. */
    @Suppress("CyclomaticComplexMethod", "LongParameterList")
    private fun decodeKnown(
        type: String,
        opId: String,
        clock: LamportClock,
        element: JsonObject,
        geometry: GeometryReader,
        opExtras: OpExtras,
    ): PageOp = when (type) {
        T_ADD -> PageOp.AddObject(
            opId,
            clock,
            decodeObject(element.getValue(F_OBJECT).jsonObject, geometry),
            opExtras,
        )

        T_DELETE -> PageOp.DeleteObject(opId, clock, target(element), opExtras)

        T_TRANSFORM -> PageOp.SetTransform(
            opId,
            clock,
            target(element),
            decodeMatrix(element.getValue(F_MATRIX).jsonArray),
            opExtras,
        )

        T_STYLE -> PageOp.SetStyle(
            opId,
            clock,
            target(element),
            decodeStyle(element.getValue(F_STYLE).jsonArray),
            opExtras,
        )

        T_Z -> PageOp.SetZOrder(
            opId,
            clock,
            target(element),
            FractionalKey(element.getValue(F_Z).jsonPrimitive.content),
            opExtras,
        )

        T_TEXT -> PageOp.SetTextContent(
            opId,
            clock,
            target(element),
            element.getValue(F_TEXT).jsonPrimitive.content,
            opExtras,
        )

        T_FORMAT -> PageOp.SetFormat(
            opId,
            clock,
            target(element),
            element.getValue(F_FORMAT).jsonPrimitive.content,
            opExtras,
        )

        T_ERASED -> PageOp.SetErasedRegion(
            opId,
            clock,
            target(element),
            decodeErased(element.getValue(F_ERASED).jsonArray),
            opExtras,
        )

        T_PAGE_META -> PageOp.SetPageMeta(
            opId = opId,
            clock = clock,
            background = element[F_BACKGROUND]?.let { decodeBackground(it.jsonObject) },
            size = element[F_SIZE]?.let { decodeSize(it.jsonArray) },
            extras = opExtras,
        )

        else -> error("unreachable: $type was in fieldsByType")
    }

    private fun target(element: JsonObject) = ObjectId(element.getValue(F_TARGET).jsonPrimitive.content)

    private fun decodeObject(element: JsonObject, geometry: GeometryReader): InkObject {
        val id = ObjectId(element.getValue(O_ID).jsonPrimitive.content)
        val transform = element[F_MATRIX]?.let { decodeMatrix(it.jsonArray) } ?: Matrix3.Identity
        val style = element[F_STYLE]?.let { decodeStyle(it.jsonArray) }
        val z = FractionalKey(element.getValue(F_Z).jsonPrimitive.content)
        val erased = element[F_ERASED]?.let { decodeErased(it.jsonArray) } ?: ErasedArea.None
        val width = element[O_WIDTH]?.jsonPrimitive?.float ?: 0f
        val height = element[O_HEIGHT]?.jsonPrimitive?.float ?: 0f

        return when (val kind = element.getValue(O_KIND).jsonPrimitive.content) {
            K_STROKE -> StrokeObject(
                id = id,
                geometry = geometry.get(element.getValue(O_GEOMETRY).jsonObject),
                transform = transform,
                style = style,
                z = z,
                erased = erased,
            )

            K_IMAGE -> ImageObject(
                id = id,
                asset = element.getValue(O_ASSET).jsonPrimitive.content,
                widthPt = width,
                heightPt = height,
                transform = transform,
                style = style,
                z = z,
                erased = erased,
            )

            K_TEXT -> TextBoxObject(
                id = id,
                text = element[F_TEXT]?.jsonPrimitive?.content.orEmpty(),
                widthPt = width,
                heightPt = height,
                transform = transform,
                style = style,
                z = z,
                erased = erased,
            )

            K_SHAPE -> ShapeObject(
                id = id,
                shape = ShapeKind.valueOf(element.getValue(O_SHAPE).jsonPrimitive.content),
                widthPt = width,
                heightPt = height,
                transform = transform,
                style = style,
                z = z,
                erased = erased,
            )

            else -> error("Unknown object kind '$kind'")
        }
    }

    private fun decodeMatrix(array: JsonArray): Matrix3 {
        require(array.size == MATRIX_VALUES) { "a matrix must have $MATRIX_VALUES values, had ${array.size}" }
        return Matrix3(
            array[0].jsonPrimitive.float,
            array[1].jsonPrimitive.float,
            array[2].jsonPrimitive.float,
            array[3].jsonPrimitive.float,
            array[4].jsonPrimitive.float,
            array[5].jsonPrimitive.float,
        )
    }

    private fun decodeStyle(array: JsonArray): ObjectStyle = ObjectStyle(
        colorArgb = array[0].jsonPrimitive.int,
        widthPt = array[1].jsonPrimitive.float,
        opacity = array[2].jsonPrimitive.float,
    )

    private fun decodeErased(array: JsonArray): ErasedArea {
        require(array.size % CAPSULE_VALUES == 0) { "an erased region must be a multiple of $CAPSULE_VALUES values" }
        val capsules = ArrayList<EraseCapsule>(array.size / CAPSULE_VALUES)
        var index = 0
        while (index < array.size) {
            capsules += EraseCapsule(
                fromX = array[index].jsonPrimitive.float,
                fromY = array[index + 1].jsonPrimitive.float,
                toX = array[index + 2].jsonPrimitive.float,
                toY = array[index + 3].jsonPrimitive.float,
                radius = array[index + 4].jsonPrimitive.float,
            )
            index += CAPSULE_VALUES
        }
        return ErasedArea(capsules)
    }

    fun decodeBackground(element: JsonObject): Background =
        when (val kind = element.getValue(B_KIND).jsonPrimitive.content) {
            B_BLANK -> Background.Blank
            B_TEMPLATE -> Background.Template(PageTemplate.valueOf(element.getValue(B_TEMPLATE).jsonPrimitive.content))
            B_PDF -> Background.SourcePdfPage(element.getValue(B_INDEX).jsonPrimitive.int)
            else -> error("Unknown background kind '$kind'")
        }

    fun decodeSize(array: JsonArray): PageSize = PageSize(array[0].jsonPrimitive.float, array[1].jsonPrimitive.float)

    private const val O_ID = "id"
    private const val O_KIND = "k"
    private const val O_GEOMETRY = "geo"
    private const val O_ASSET = "a"
    private const val O_SHAPE = "sh"
    private const val O_WIDTH = "w"
    private const val O_HEIGHT = "h"

    private const val K_STROKE = "s"
    private const val K_IMAGE = "i"
    private const val K_TEXT = "t"
    private const val K_SHAPE = "p"

    private const val B_KIND = "k"
    private const val B_BLANK = "blank"
    private const val B_TEMPLATE = "tpl"
    private const val B_PDF = "pdf"
    private const val B_INDEX = "n"

    private const val MATRIX_VALUES = 6
    private const val CAPSULE_VALUES = 5
}

/** An op plus the page it was filed under, when the container recorded one. */
internal data class DecodedOp(val op: PageOp, val pageId: String?)

/**
 * Collects geometry blobs into one contiguous buffer and hands back references.
 *
 * This is the mechanism behind SPEC §5.3's *"offsets into the geometry blob"*: the
 * bytes are appended untouched and the op JSON records where they landed. Nothing
 * here interprets them (SPEC §5.4).
 */
internal class GeometryWriter {

    private val buffer = ByteBuffer()

    val sizeBytes: Int get() = buffer.size

    fun put(geometry: StrokeGeometry): JsonObject {
        val offset = buffer.size
        buffer.bytes(geometry.bytes)
        return buildJsonObject {
            put(OFFSET, JsonPrimitive(offset))
            put(LENGTH, JsonPrimitive(geometry.bytes.size))
        }
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()

    companion object {
        const val OFFSET = "o"
        const val LENGTH = "n"
    }
}

/** Resolves the references [GeometryWriter] produced. */
internal class GeometryReader(private val blob: ByteArray) {

    fun get(reference: JsonObject): StrokeGeometry {
        val offset = reference.getValue(GeometryWriter.OFFSET).jsonPrimitive.int
        val length = reference.getValue(GeometryWriter.LENGTH).jsonPrimitive.int
        require(offset >= 0 && length >= 0 && offset + length <= blob.size) {
            "geometry reference $offset+$length is outside a ${blob.size}-byte blob"
        }
        return StrokeGeometry(blob.copyOfRange(offset, offset + length))
    }

    companion object {
        val Empty: GeometryReader = GeometryReader(ByteArray(0))
    }
}

private fun JsonObject.getValue(key: String): JsonElement =
    requireNotNull(this[key]) { "missing field '$key' in $this" }
