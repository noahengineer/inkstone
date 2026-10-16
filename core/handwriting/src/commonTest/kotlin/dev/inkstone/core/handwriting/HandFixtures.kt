package dev.inkstone.core.handwriting

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.InkReader
import dev.inkstone.core.model.InkReading
import dev.inkstone.core.model.InkToRead
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PenKind
import dev.inkstone.core.model.PenStyle
import dev.inkstone.core.model.PenTrajectory
import dev.inkstone.core.model.ReadCandidate
import dev.inkstone.core.model.RecognitionUnavailable
import kotlin.math.hypot
import kotlin.test.assertNotNull

/**
 * A synthetic hand for assembly's tests: a few letters drawn as polylines in the normalised frame
 * (x-height 1, baseline 0, y up), written into word samples the way the harvester would store them.
 *
 * ⚠️ **A generous witness** (CLAUDE.md, M7: *"a synthetic fixture is the worst possible witness for
 * a geometric heuristic"*). Every letter here is clean, every exit ends on the baseline and every
 * entry starts there, so a join always finds the ink it needs. These tests prove assembly's
 * *decisions* — which units, which joins, how much variation, what is refused — and nothing about
 * how a real hand looks assembled. That is the harness sheet's question, drawn from a real profile.
 *
 * Letters: `n u m l i x`, `N` as the one capital, and `3` and `.` for numbers. There is no `z`, on
 * purpose.
 */
internal object HandFixtures {

    private val NEAT = NormalFrame(originX = 40f, originY = 100f, angleRad = 0f, slantRad = 0f, xHeightPt = 6f)
    val PEN = PenStyle(PenKind.FOUNTAIN, 0xFF203040.toInt(), 1.2f)

    /** Each letter as strokes of points, (u, v), drawing order. */
    val LETTERS: Map<Char, List<List<Pair<Float, Float>>>> = mapOf(
        'n' to listOf(
            listOf(0f to 0f, 0.02f to 0.95f, 0.15f to 1f, 0.5f to 0.95f, 0.6f to 0.5f, 0.62f to 0.05f, 0.75f to 0f),
        ),
        'u' to listOf(
            listOf(0f to 1f, 0.05f to 0.2f, 0.3f to 0f, 0.6f to 0.15f, 0.62f to 1f, 0.64f to 0.1f, 0.75f to 0f),
        ),
        'm' to listOf(
            listOf(
                0f to 0f, 0.02f to 0.95f, 0.15f to 1f, 0.4f to 0.9f, 0.45f to 0.05f, 0.5f to 0.9f,
                0.7f to 1f, 0.85f to 0.9f, 0.9f to 0.05f, 1.05f to 0f,
            ),
        ),
        'l' to listOf(listOf(0f to 0f, 0.25f to 1.9f, 0.3f to 2f, 0.2f to 1.8f, 0.1f to 0.1f, 0.3f to 0f)),
        'i' to listOf(listOf(0f to 0f, 0.05f to 1f, 0.1f to 0.05f, 0.25f to 0f), listOf(0.06f to 1.4f, 0.08f to 1.45f)),
        'x' to listOf(listOf(0f to 0f, 0.6f to 1f), listOf(0.6f to 0f, 0f to 1f)),
        'N' to listOf(listOf(0f to 0f, 0f to 2f, 0.9f to 0f, 0.9f to 2f)),
        // §18.10: a digit at this hand's own digit height, and a decimal point the size of the tablet's.
        '3' to
            listOf(
                listOf(
                    0f to 1.2f,
                    0.4f to 1.25f,
                    0.5f to 0.95f,
                    0.2f to 0.65f,
                    0.55f to 0.4f,
                    0.4f to 0f,
                    0f to 0.05f,
                ),
            ),
        '.' to listOf(listOf(0f to 0f, 0.04f to 0.02f)),
    )

    /** [LETTERS] with [letter] drawn at [scale] and lowered by [drop] — a subscript, for one. */
    fun withShape(letter: Char, scale: Float, drop: Float): Map<Char, List<List<Pair<Float, Float>>>> =
        LETTERS + (letter to LETTERS.getValue(letter).map { s -> s.map { (u, v) -> u * scale to v * scale - drop } })

    private const val LETTER_GAP = 0.25f
    private const val STEP = 0.05f
    private const val MS_PER_POINT = 8
    private const val PEN_UP_MS = 120

    /**
     * A word sample.
     *
     * @param joined runs of letters written as one stroke: `"nu"` joins the first two letters. Each
     *   letter's first stroke is merged; a later stroke (an `i`'s dot) stays its own.
     * @param cut whether the harvester cut it into glyphs (one span per letter, or per joined run).
     */
    fun sample(
        label: String,
        id: String = "s-$label-${counter++}",
        joined: Set<String> = emptySet(),
        cut: Boolean = true,
        noteId: String = "note",
        pageId: String = "page",
        frame: NormalFrame = NEAT,
        split: SampleSplit = SampleSplit.TRAIN,
        shapes: Map<Char, List<List<Pair<Float, Float>>>> = LETTERS,
        letterGap: Float = LETTER_GAP,
    ): WordSample {
        val groups = groupsOf(label, joined)
        val strokes = ArrayList<List<Pair<Float, Float>>>()
        val spans = ArrayList<GlyphSpan>()
        var left = 0f
        for (group in groups) {
            val first = ArrayList<Pair<Float, Float>>()
            val later = ArrayList<List<Pair<Float, Float>>>()
            for (letter in group) {
                val shape = assertNotNull(shapes[letter], "no fixture letter '$letter'")
                val width = shape.flatten().maxOf { it.first }
                first += shape[0].map { (u, v) -> (u + left) to v }
                shape.drop(1).forEach { stroke -> later += stroke.map { (u, v) -> (u + left) to v } }
                left += width + letterGap
            }
            val indices = ArrayList<Int>()
            indices += strokes.size
            strokes += first
            later.forEach {
                indices += strokes.size
                strokes += it
            }
            spans += GlyphSpan(group, indices)
        }
        val trajectory = pageTrajectoryOf(strokes, frame)
        return WordSample(
            id = id,
            noteId = DocumentId(noteId),
            pageId = PageId(pageId),
            sourceStrokes = strokes.indices.map { ObjectId("$id-$it") },
            sourceKey = "key-$id",
            epochDay = 20_000L,
            label = label,
            labelSource = LabelSource.AGREED,
            alternatives = emptyList(),
            frame = frame,
            pen = PEN,
            unitLengthCm = 0.0353f,
            split = split,
            trajectory = trajectory,
            glyphs = if (cut) spans else emptyList(),
        )
    }

    private var counter = 0

    private fun groupsOf(label: String, joined: Set<String>): List<String> {
        val groups = ArrayList<String>()
        var i = 0
        while (i < label.length) {
            val run = joined.firstOrNull { label.startsWith(it, i) }
            if (run != null) {
                groups += run
                i += run.length
            } else {
                groups += label[i].toString()
                i++
            }
        }
        return groups
    }

    /** Normalised polylines → a page-space trajectory through [frame], densified so curves have samples. */
    fun pageTrajectoryOf(strokes: List<List<Pair<Float, Float>>>, frame: NormalFrame): PenTrajectory {
        val builder = PenTrajectory.Builder(256, hasPressure = true, hasTilt = false)
        val out = FloatArray(2)
        var clock = 0
        for ((index, stroke) in strokes.withIndex()) {
            if (index > 0) clock += PEN_UP_MS
            for (point in densify(stroke)) {
                frame.toPage(point.first, point.second, out)
                builder.add(out[0], out[1], clock, pressure = 0.5f)
                clock += MS_PER_POINT
            }
            builder.endStroke()
        }
        return assertNotNull(builder.build())
    }

    private fun densify(stroke: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (stroke.size < 2) return stroke
        val points = ArrayList<Pair<Float, Float>>()
        for ((a, b) in stroke.zipWithNext()) {
            val steps = maxOf(1, (hypot(b.first - a.first, b.second - a.second) / STEP).toInt())
            for (s in 0 until steps) {
                val f = s.toFloat() / steps
                points += (a.first + (b.first - a.first) * f) to (a.second + (b.second - a.second) * f)
            }
        }
        points += stroke.last()
        return points
    }
}

/**
 * A recogniser that answers from a script: call *n* gets the *n*-th list, one answer per ink, best
 * first. Past the end of the script it answers with [fallback].
 *
 * ⚠️ It does not look at the ink, so a test that scripts *"reads as itself"* proves the synthesizer's
 * bookkeeping — retries, refusals, ordering — and nothing about whether assembled ink is legible.
 */
internal class ScriptedReader(
    private val script: List<List<List<String>>> = emptyList(),
    private val fallback: (InkToRead) -> List<String> = { emptyList() },
    private val unavailable: RecognitionUnavailable? = null,
) : InkReader {
    val calls = ArrayList<List<InkToRead>>()

    override suspend fun read(inks: List<InkToRead>, candidates: Int): InkReading {
        calls += inks
        if (unavailable != null) return InkReading.Unavailable(unavailable)
        val answers = script.getOrNull(calls.size - 1)
        return InkReading.Read(
            inks.mapIndexed { i, ink -> (answers?.getOrNull(i) ?: fallback(ink)).map { ReadCandidate(it, null) } },
        )
    }
}
