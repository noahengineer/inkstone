package dev.inkstone.core.handwriting

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PenKind
import dev.inkstone.core.model.PenStyle
import dev.inkstone.core.model.PenTrajectory

/**
 * Where a sample's label came from — docs/HANDWRITING-SYNTHESIS.md §5.3. Only these are learned
 * from; a word the recogniser read only once is never stored at all.
 */
// The codes are written to disk and fixed for ever; naming 3 as a constant would only hide that.
@Suppress("MagicNumber")
public enum class LabelSource(internal val code: Int) {
    /** Two independent reads agreed: M7's line read and a fresh read of just this word's strokes. */
    AGREED(1),

    /** The user looked at it and said it is right. */
    CONFIRMED(2),

    /** The user typed what it really says. */
    CORRECTED(3),
    ;

    internal companion object {
        fun of(code: Int): LabelSource? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The held-out split exists so fidelity (§8.3) is measured on words the system has never learned
 * from. A held-out word is never primed with, never trained on, and never used by assembly.
 */
public enum class SampleSplit(internal val code: Int) {
    TRAIN(0),
    HELD_OUT(1),
    ;

    internal companion object {
        fun of(code: Int): SampleSplit? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One letter (or mark) cut out of a word — docs/HANDWRITING-SYNTHESIS.md §5.4.
 *
 * @property strokes indices into the word's trajectory, in drawing order. A letter is whole strokes
 *   in v1, which is why v1 only cuts printed letters; v2 will add point ranges.
 */
public data class GlyphSpan(val text: String, val strokes: List<Int>)

/**
 * ⭐ One word the user wrote, learned — docs/HANDWRITING-SYNTHESIS.md §4.2.
 *
 * @property sourceStrokes the strokes it was learned from, in drawing order.
 * @property sourceKey a fingerprint of those strokes' **geometry**: how a re-harvest knows it has
 *   seen these exact strokes before, how a duplicated word counts once (D8), and what a correction
 *   or a rejection is keyed by — so they survive the sample being rebuilt.
 * @property epochDay the day it was written. A day, not a time: nothing needs more, and a profile
 *   should not be a log of when someone was awake.
 * @property label what it says, as learned. The user's correction, if any, is applied over this
 *   from [LabelBook] — it is not written here.
 * @property alternatives the other readings the recogniser offered, for the review screen.
 * @property frame the line's frame at the word, so the word can be normalised (§5.2).
 * @property trajectory **as measured**, in page space: a stored normalisation could not be undone
 *   when the normaliser improves.
 * @property glyphs letters cut from it and verified one by one (§5.4); empty when it could not be.
 */
public data class WordSample(
    val id: String,
    val noteId: DocumentId,
    val pageId: PageId,
    val sourceStrokes: List<ObjectId>,
    val sourceKey: String,
    val epochDay: Long,
    val label: String,
    val labelSource: LabelSource,
    val alternatives: List<String>,
    val frame: NormalFrame,
    val pen: PenStyle,
    val unitLengthCm: Float?,
    val split: SampleSplit,
    val trajectory: PenTrajectory,
    val glyphs: List<GlyphSpan>,
)

/**
 * A shard of word samples on disk — `words/<n>.hws`.
 *
 * ```
 * "IHWS" | version u8 | count varint | count × (length varint | record)
 * ```
 *
 * ⭐ **Every record is length-prefixed**, so a later build can append fields to a record and this
 * build reads what it knows and skips the rest: SPEC §5.8's forward-compatibility rule, applied to a
 * file that is not a note. A shard whose *header* is newer than this build is not read at all, and
 * the store never rewrites one it could not read (`ProfileStore`).
 */
public object WordSampleCodec {
    private val MAGIC = "IHWS".encodeToByteArray()
    private const val VERSION = 1
    private const val NO_UNIT_LENGTH = -1f

    public fun encode(samples: List<WordSample>): ByteArray {
        val sink = ByteSink()
        sink.bytes(MAGIC)
        sink.byte(VERSION)
        sink.varint(samples.size.toLong())
        for (sample in samples) {
            val record = ByteSink()
            writeRecord(sample, record)
            val bytes = record.toByteArray()
            sink.varint(bytes.size.toLong())
            sink.bytes(bytes)
        }
        return sink.toByteArray()
    }

    /**
     * @return the samples, or null when the shard is not one this build can read — wrong magic, a
     *   newer version, or a header cut short. A damaged *record* is skipped and the rest are kept.
     */
    @Suppress("ReturnCount")
    public fun decode(bytes: ByteArray): List<WordSample>? {
        val cursor = ByteCursor(bytes)
        val magic = cursor.take(MAGIC.size) ?: return null
        if (!magic.contentEquals(MAGIC)) return null
        val version = cursor.byte() ?: return null
        if (version > VERSION) return null
        var remaining = cursor.varint() ?: return null
        val samples = ArrayList<WordSample>()
        while (remaining-- > 0) {
            // A length that runs past the end is a shard cut short: keep what came before it.
            val length = cursor.varint()?.takeIf { it in 0..cursor.remaining.toLong() }?.toInt() ?: break
            val start = cursor.position
            readRecord(ByteCursor(bytes, start, start + length))?.let { samples += it }
            cursor.skip(length)
        }
        return samples
    }

    private fun writeRecord(sample: WordSample, sink: ByteSink) {
        sink.string(sample.id)
        sink.string(sample.noteId.value)
        sink.string(sample.pageId.value)
        sink.varint(sample.sourceStrokes.size.toLong())
        sample.sourceStrokes.forEach { sink.string(it.value) }
        sink.string(sample.sourceKey)
        sink.zigzag(sample.epochDay)
        sink.string(sample.label)
        sink.byte(sample.labelSource.code)
        sink.varint(sample.alternatives.size.toLong())
        sample.alternatives.forEach(sink::string)
        sink.float(sample.frame.originX)
        sink.float(sample.frame.originY)
        sink.float(sample.frame.angleRad)
        sink.float(sample.frame.slantRad)
        sink.float(sample.frame.xHeightPt)
        sink.string(sample.pen.kind.name)
        sink.int32(sample.pen.colorArgb)
        sink.float(sample.pen.widthPt)
        sink.float(sample.unitLengthCm ?: NO_UNIT_LENGTH)
        sink.byte(sample.split.code)
        val trajectory = ByteSink()
        TrajectoryCodec.write(sample.trajectory, trajectory)
        val trajectoryBytes = trajectory.toByteArray()
        sink.varint(trajectoryBytes.size.toLong())
        sink.bytes(trajectoryBytes)
        sink.varint(sample.glyphs.size.toLong())
        for (glyph in sample.glyphs) {
            sink.string(glyph.text)
            sink.varint(glyph.strokes.size.toLong())
            glyph.strokes.forEach { sink.varint(it.toLong()) }
        }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun readRecord(cursor: ByteCursor): WordSample? {
        val id = cursor.string() ?: return null
        val noteId = cursor.string() ?: return null
        val pageId = cursor.string() ?: return null
        val strokeCount = cursor.varint() ?: return null
        if (strokeCount < 0 || strokeCount > cursor.remaining) return null
        val strokes = ArrayList<ObjectId>()
        repeat(strokeCount.toInt()) { strokes += ObjectId(cursor.string() ?: return null) }
        val sourceKey = cursor.string() ?: return null
        val epochDay = cursor.zigzag() ?: return null
        val label = cursor.string() ?: return null
        val labelSource = cursor.byte()?.let { LabelSource.of(it) } ?: return null
        val alternativeCount = cursor.varint() ?: return null
        if (alternativeCount < 0 || alternativeCount > cursor.remaining) return null
        val alternatives = ArrayList<String>()
        repeat(alternativeCount.toInt()) { alternatives += cursor.string() ?: return null }
        val frame = NormalFrame(
            originX = cursor.float() ?: return null,
            originY = cursor.float() ?: return null,
            angleRad = cursor.float() ?: return null,
            slantRad = cursor.float() ?: return null,
            xHeightPt = cursor.float() ?: return null,
        )
        val kind = cursor.string()?.let { name -> PenKind.entries.firstOrNull { it.name == name } } ?: return null
        val pen = PenStyle(kind, cursor.int32() ?: return null, cursor.float() ?: return null)
        val unitLength = (cursor.float() ?: return null).takeIf { it > 0f }
        val split = cursor.byte()?.let { SampleSplit.of(it) } ?: return null
        val trajectoryLength = cursor.varint() ?: return null
        val trajectoryBytes = cursor.take(trajectoryLength.toInt()) ?: return null
        val trajectory = TrajectoryCodec.decode(trajectoryBytes) ?: return null
        val glyphCount = cursor.varint() ?: return null
        if (glyphCount < 0 || glyphCount > cursor.remaining) return null
        val glyphs = ArrayList<GlyphSpan>()
        repeat(glyphCount.toInt()) {
            val text = cursor.string() ?: return null
            val indices = cursor.varint() ?: return null
            if (indices < 0 || indices > cursor.remaining) return null
            val list = ArrayList<Int>()
            repeat(indices.toInt()) { list += (cursor.varint() ?: return null).toInt() }
            if (list.all { it in 0 until trajectory.strokeCount }) glyphs += GlyphSpan(text, list)
        }
        // Anything after this is a field a later build added. Ignored, by design — see the KDoc.
        return WordSample(
            id = id,
            noteId = DocumentId(noteId),
            pageId = PageId(pageId),
            sourceStrokes = strokes,
            sourceKey = sourceKey,
            epochDay = epochDay,
            label = label,
            labelSource = labelSource,
            alternatives = alternatives,
            frame = frame,
            pen = pen,
            unitLengthCm = unitLength,
            split = split,
            trajectory = trajectory,
            glyphs = glyphs,
        )
    }
}
