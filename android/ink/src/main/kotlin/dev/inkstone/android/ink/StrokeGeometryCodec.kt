// A byte layout's masks, shifts and field offsets ARE the specification — 0xFF is not a
// magic number here, it is a byte, and `shl 8` is not a tunable, it is the next byte along.
// Naming each one would add a constant per bit position and make the layout harder to check
// against the format it implements, which is the opposite of what the rule is for.
@file:Suppress("MagicNumber")

package dev.inkstone.android.ink

import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.ImmutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import dev.inkstone.core.model.StrokeGeometry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * SPEC §5.4 — turns a `Stroke` into the opaque blob `:core:*` stores, and back.
 *
 * ## The spec was wrong about one thing, and it matters
 *
 * SPEC §5.4 says *":android:ink serializes strokes using androidx.ink:ink-storage"*.
 * **`ink-storage` 1.0.0 has no stroke serializer.** Its entire public surface is
 * `StrokeInputBatchSerialization.encode`/`decode` and `BrushFamilySerialization` — there
 * is no `Stroke.encode`, no `decodeStroke`, and no container format. Verified against the
 * shipped artifact: `ink-storage.aar` contains thirteen classes and none of them mentions
 * `Stroke`.
 *
 * So a stroke's blob is a small container written here:
 *
 * ```
 * "INKS" | version u8 | brushVersion u8 | brushKind u8 | inputModel u8
 *        | colorArgb i32 | sizePt f32 | epsilonPt f32
 *        | inputBatch (ink-storage bytes, to the end of the blob)
 * ```
 *
 * ## Why the brush is a few bytes rather than a serialized `BrushFamily`
 *
 * `ink-storage` *can* encode a `BrushFamily`, and this deliberately does not use it:
 *
 * - Inkstone's families are **code** (`InkstoneBrushFamilies.kt`), version-controlled and
 *   re-derivable from four bytes. A family blob would be a second copy of them in every
 *   stroke on disk, for no fidelity gained.
 * - Every `BrushFamily` constructor in 1.0.0 is `@RestrictTo(LIBRARY_GROUP)` and lint
 *   fails the build on them (SPEC §3.4). Round-tripping through a serializer would put
 *   another one of those on the load path.
 * - The families forbid any behaviour driven by distance or time along the stroke, which
 *   is what makes partial erase correct. That constraint lives in the source, and a blob
 *   that carried its own copy of a family could reintroduce a tapering brush from an old
 *   file.
 *
 * [BRUSH_VERSION] is the honest cost of that choice: it records *which* generation of
 * Inkstone's families a stroke was captured with, so if a family ever changes shape, a
 * future build can reconstruct the historical one rather than silently re-rendering old
 * ink with new geometry.
 *
 * ## Threading
 *
 * Encoding is not free — it walks every input sample and compresses. It must not run
 * between `ACTION_UP` and the dry layer's redraw (CLAUDE.md rule 7), which is why
 * [PageOpRecorder] does it on a worker and nothing calls this from `onTouchEvent`.
 */
internal object StrokeGeometryCodec {

    /** "INKS" — the container's marker, so a blob from somewhere else is recognisably not ours. */
    private val MAGIC = byteArrayOf('I'.code.toByte(), 'N'.code.toByte(), 'K'.code.toByte(), 'S'.code.toByte())

    /** The container's own version. Bump only with a migration and a committed fixture. */
    private const val FORMAT_VERSION: Byte = 1

    /**
     * Which generation of `InkstoneBrushFamilies` a stroke was captured with.
     *
     * Bump this whenever a family's *shape* changes — not when a colour default moves.
     * Nothing reads it yet; it is here so that the day it is needed, the strokes that need
     * it already carry it.
     */
    private const val BRUSH_VERSION: Byte = 1

    private const val HEADER_SIZE = 8 + 4 + 4 + 4

    /** Encodes a finished stroke. Off the ink path only. */
    fun encode(stroke: Stroke, kind: InkBrushKind, inputModel: InkInputModel): StrokeGeometry {
        val brush = stroke.brush
        val out = ByteArrayOutputStream(HEADER_SIZE + ESTIMATED_INPUT_BYTES)
        out.write(MAGIC)
        out.write(FORMAT_VERSION.toInt())
        out.write(BRUSH_VERSION.toInt())
        out.write(kind.ordinal)
        out.write(inputModel.ordinal)
        out.writeInt(brush.colorIntArgb)
        out.writeInt(brush.size.toRawBits())
        out.writeInt(brush.epsilon.toRawBits())
        // `ink-storage`'s only stroke-shaped offering. It carries pressure, tilt,
        // orientation, tool type and timing — every field a hand-rolled point list drops.
        StrokeInputBatchSerializer.encode(stroke.inputs, out)
        return StrokeGeometry(out.toByteArray())
    }

    /**
     * Rebuilds a stroke, re-tessellating its mesh natively.
     *
     * @return null when the blob is not one of ours or is damaged. A null is a stroke that
     *   does not draw; an exception here would be a note that does not open, and one
     *   unreadable stroke must not cost the other 4,999.
     */
    // Every return is a different way the blob is unreadable, and they all mean the same thing
    // to the caller: a stroke that does not draw. See the KDoc on why that is not an exception.
    @Suppress("ReturnCount")
    fun decode(geometry: StrokeGeometry, brushes: InkBrushFactory): DecodedStroke? {
        val bytes = geometry.bytes
        if (bytes.size < HEADER_SIZE || !bytes.startsWithMagic()) return null
        if (bytes[VERSION_OFFSET] > FORMAT_VERSION) return null

        val kind = InkBrushKind.entries.getOrNull(bytes[KIND_OFFSET].toInt()) ?: return null
        val inputModel = InkInputModel.entries.getOrNull(bytes[MODEL_OFFSET].toInt()) ?: return null
        val colorArgb = bytes.readInt(COLOR_OFFSET)
        val sizePt = Float.fromBits(bytes.readInt(SIZE_OFFSET))
        val epsilonPt = Float.fromBits(bytes.readInt(EPSILON_OFFSET))

        val inputs = runCatching {
            StrokeInputBatchSerializer.decode(ByteArrayInputStream(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE))
        }.getOrNull() ?: return null

        val brush = brushes.brushFor(kind, colorArgb, sizePt, epsilonPt, inputModel)
        return DecodedStroke(Stroke(brush, inputs), kind)
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArray.readInt(offset: Int): Int = ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

    private fun ByteArray.startsWithMagic(): Boolean {
        for (index in MAGIC.indices) {
            if (this[index] != MAGIC[index]) return false
        }
        return true
    }

    private const val VERSION_OFFSET = 4
    private const val KIND_OFFSET = 6
    private const val MODEL_OFFSET = 7
    private const val COLOR_OFFSET = 8
    private const val SIZE_OFFSET = 12
    private const val EPSILON_OFFSET = 16

    /** A page of dense handwriting is a few hundred samples per stroke. */
    private const val ESTIMATED_INPUT_BYTES = 2048
}

/** A stroke rebuilt from a blob, with the brush kind the dry layer needs for render order. */
internal class DecodedStroke(val stroke: Stroke, val kind: InkBrushKind)

/**
 * The two `ink-storage` calls, in one place.
 *
 * They are extension functions on `StrokeInputBatch` and its companion, which reads
 * oddly at the call site and — more to the point — puts an `androidx.ink.storage` import
 * wherever it is used. Wrapping them here keeps that import in one file, next to the note
 * explaining what the library does and does not offer.
 */
private object StrokeInputBatchSerializer {

    fun encode(inputs: StrokeInputBatch, out: java.io.OutputStream) {
        inputs.encode(out)
    }

    fun decode(input: java.io.InputStream): ImmutableStrokeInputBatch = StrokeInputBatch.decode(input)
}
