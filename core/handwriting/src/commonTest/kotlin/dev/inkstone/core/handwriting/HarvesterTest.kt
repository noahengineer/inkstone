package dev.inkstone.core.handwriting

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.CapturedStroke
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.ElementInstance
import dev.inkstone.core.model.EraseCapsule
import dev.inkstone.core.model.ErasedArea
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.InkReader
import dev.inkstone.core.model.InkReading
import dev.inkstone.core.model.InkSample
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PageText
import dev.inkstone.core.model.PenKind
import dev.inkstone.core.model.PenStyle
import dev.inkstone.core.model.PenTrajectory
import dev.inkstone.core.model.ReadCandidate
import dev.inkstone.core.model.RecognisedLine
import dev.inkstone.core.model.RecognitionUnavailable
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.StrokeReader
import dev.inkstone.core.model.StrokeSampler
import dev.inkstone.core.search.LineSegmenter
import dev.inkstone.core.search.WordBoxes
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * docs/HANDWRITING-SYNTHESIS.md §5 — the harvester's *decisions*: whose ink, which words, which
 * letters, and what is stale.
 *
 * ⚠️ **The recogniser here is a fake that knows the answer**, which makes it far more generous than
 * ML Kit (CLAUDE.md: *"distrust a fixture that is more generous than production"*). These tests say
 * nothing about how often two real reads agree on a real hand — that number is measured on the
 * device and written into the phase notes. What they do pin is that a disagreement, an unavailable
 * model, or ink that is not the user's own is never learned from.
 *
 * The page is `hi ok`, printed: one stroke per letter, 12 pt tall, on a 10 pt pitch.
 */
class HarvesterTest {

    @Test
    fun `two agreed words become two samples with their letters cut and checked`() = runTest {
        val fixture = Fixture()
        val result = assertIs<PageHarvest.Harvested>(fixture.harvest())

        assertEquals(listOf("hi", "ok"), result.samples.map { it.label })
        assertTrue(result.samples.all { it.labelSource == LabelSource.AGREED })
        assertEquals(listOf(ObjectId("h"), ObjectId("i")), result.samples[0].sourceStrokes)
        assertEquals(listOf(GlyphSpan("h", listOf(0)), GlyphSpan("i", listOf(1))), result.samples[0].glyphs)
        assertEquals(listOf(GlyphSpan("o", listOf(0)), GlyphSpan("k", listOf(1))), result.samples[1].glyphs)
        assertEquals(2, result.samples[0].trajectory.strokeCount)
        assertTrue(result.samples.all { it.frame.xHeightPt > 0f })
        assertEquals(
            HarvestStats(
                linesFound = 1,
                linesRead = 1,
                linesSplit = 1,
                wordsSeen = 2,
                wordsEligible = 2,
                wordsAgreed = 2,
                wordsSteady = 2,
                glyphsKept = 4,
            ),
            result.stats,
        )
    }

    @Test
    fun `a shape both reads agree on is not learned when its other guesses scatter`() = runTest {
        // The walk's zigzag: read as mm twice, and then as mmm, umm, mmu and um.
        val fixture = Fixture(scattered = setOf("hi"))
        val result = assertIs<PageHarvest.Harvested>(fixture.harvest())

        assertEquals(listOf("ok"), result.samples.map { it.label })
        assertEquals(2, result.stats.wordsAgreed)
        assertEquals(1, result.stats.wordsSteady)
    }

    @Test
    fun `real words from the walk echo in their other guesses and its drawings do not`() {
        fun read(vararg texts: String) = texts.map { ReadCandidate(it, null) }

        // Real writing, from the emulator's first harvest (docs/HANDWRITING-SYNTHESIS.md §16.5).
        assertEquals(3, Harvester.echoes("Examples", read("Examples", "examples", "Examples,", "Examples)", "Example")))
        assertEquals(4, Harvester.echoes("CaCO3", read("CaCO3", " CaCO3", "CaCO 3", " CaCO 3", "Ca CO3")))
        // Drawings from the same harvest: a zigzag, a wavy scribble and a bracket.
        assertEquals(0, Harvester.echoes("mm", read("mm", "mmm", "umm", "mmu", "um")))
        assertEquals(1, Harvester.echoes("M", read("M", "x", "m", "n")))
        assertEquals(1, Harvester.echoes("c", read("c", "C", "l", "L", "(")))
        assertTrue(listOf(3, 4).all { it >= Harvester.MIN_ECHOES } && listOf(0, 1).all { it < Harvester.MIN_ECHOES })
    }

    @Test
    fun `the real pause between strokes is kept when the ledger has it`() = runTest {
        // h went down at 1000 ms and i at 1400: the sample's second stroke starts 400 ms in.
        val fixture =
            Fixture(ledger = Fixture.ledgerOf(mapOf("h" to 1_000L, "i" to 1_400L, "o" to 3_000L, "k" to 3_100L)))
        val result = assertIs<PageHarvest.Harvested>(fixture.harvest())

        val hi = result.samples.first { it.label == "hi" }.trajectory
        assertEquals(400, hi.tMs[hi.strokeStart(1)])
    }

    @Test
    fun `ink the ledger cannot vouch for is learned only with the older notes opt in`() = runTest {
        val fixture = Fixture(ledger = emptyMap())

        val refused = assertIs<PageHarvest.Harvested>(fixture.harvest(HarvestPolicy(learnFromOlderNotes = false)))
        val allowed = assertIs<PageHarvest.Harvested>(fixture.harvest(HarvestPolicy(learnFromOlderNotes = true)))

        assertEquals(0, refused.stats.wordsEligible)
        assertEquals(2, allowed.samples.size)
    }

    @Test
    fun `generated element ruled erased and rotated strokes are never learned`() = runTest {
        val generated = Fixture(formats = mapOf("h" to ObjectFormat(extras = mapOf("hw" to "g1:a:-")).encode()))
        val element = Fixture(
            formats = mapOf("o" to ObjectFormat(instance = ElementInstance("el1", "in1", Matrix3.Identity)).encode()),
        )
        val ruled = Fixture(ledger = Fixture.ledgerOf(Fixture.ALL_DOWN, ruled = setOf("i")))
        val erased = Fixture(erased = setOf("k"))
        val rotated = Fixture(transforms = mapOf("h" to Matrix3.rotate(0.3f)))
        val moved = Fixture(transforms = mapOf("h" to Matrix3.translate(5f, 0f), "i" to Matrix3.translate(5f, 0f)))

        assertEquals(listOf("ok"), generated.labels())
        assertEquals(listOf("hi"), element.labels())
        assertEquals(listOf("ok"), ruled.labels())
        assertEquals(listOf("hi"), erased.labels())
        assertEquals(listOf("ok"), rotated.labels())
        assertEquals(listOf("hi", "ok"), moved.labels(), "a stroke that was only moved is still the user's writing")
    }

    @Test
    fun `each word not learned is counted under the first rule it failed`() = runTest {
        val erased = assertIs<PageHarvest.Harvested>(Fixture(erased = setOf("k")).harvest()).stats
        val turned = Fixture(transforms = mapOf("h" to Matrix3.rotate(0.3f)))
        val rotated = assertIs<PageHarvest.Harvested>(turned.harvest()).stats
        val fixture = Fixture()
        val known = assertIs<PageHarvest.Harvested>(fixture.harvest()).samples.map { it.sourceKey }.toSet()
        val again = assertIs<PageHarvest.Harvested>(fixture.harvest(known = known)).stats

        assertEquals(mapOf(SkipReason.ERASED to 1), erased.skipped)
        assertEquals(mapOf(SkipReason.TRANSFORMED to 1), rotated.skipped)
        assertEquals(mapOf(SkipReason.ALREADY_LEARNED to 2), again.skipped)
    }

    @Test
    fun `a word the second read disagrees with is not learned`() = runTest {
        val fixture = Fixture(disagree = setOf("hi"))
        assertEquals(listOf("ok"), fixture.labels())
    }

    @Test
    fun `strokes already learned are not learned twice`() = runTest {
        val fixture = Fixture()
        val first = assertIs<PageHarvest.Harvested>(fixture.harvest())
        val again = assertIs<PageHarvest.Harvested>(fixture.harvest(known = first.samples.map { it.sourceKey }.toSet()))
        assertTrue(again.samples.isEmpty())
    }

    @Test
    fun `no recogniser means nothing is learned and the reason is kept`() = runTest {
        val fixture = Fixture(unavailable = RecognitionUnavailable.MODEL_NOT_DOWNLOADED)
        assertEquals(PageHarvest.Unavailable(RecognitionUnavailable.MODEL_NOT_DOWNLOADED), fixture.harvest())
    }

    @Test
    fun `a sample whose strokes changed since is stale`() = runTest {
        val samples = assertIs<PageHarvest.Harvested>(Fixture().harvest()).samples
        val hi = samples.first { it.label == "hi" }.id
        val ok = samples.first { it.label == "ok" }.id

        assertTrue(Harvester.stale(samples, Fixture().page).isEmpty())
        assertEquals(setOf(hi), Harvester.stale(samples, Fixture(deleted = setOf("i")).page))
        assertEquals(setOf(ok), Harvester.stale(samples, Fixture(erased = setOf("k")).page))
        assertEquals(setOf(hi), Harvester.stale(samples, Fixture(transforms = mapOf("h" to Matrix3.scale(2f))).page))
    }

    @Test
    fun `the held out split depends on the strokes and keeps about one in ten`() {
        val random = Random(7)
        val keys = (0 until 2_000).map { random.nextLong().toString(16) }
        val heldOut = keys.count { Harvester.splitOf(it) == SampleSplit.HELD_OUT }

        assertTrue(keys.all { Harvester.splitOf(it) == Harvester.splitOf(it) })
        assertTrue(heldOut in 140..260, "held out $heldOut of 2000")
    }

    /** One page, `hi ok`, and every port the harvester needs, faked over it. */
    private class Fixture(
        val ledger: Map<ObjectId, LedgerEntry> = ledgerOf(ALL_DOWN),
        formats: Map<String, String> = emptyMap(),
        erased: Set<String> = emptySet(),
        transforms: Map<String, Matrix3> = emptyMap(),
        deleted: Set<String> = emptySet(),
        private val disagree: Set<String> = emptySet(),
        private val scattered: Set<String> = emptySet(),
        private val unavailable: RecognitionUnavailable? = null,
    ) {
        // A 10 pt word gap: wide enough to be a word space (12 × 0.33 pt), narrow enough not to be a
        // gutter (12 × 1.5 pt) that would put the two words on separate lines.
        private val letters = listOf("h" to 100f, "i" to 110f, "o" to 128f, "k" to 138f)
        private val words = mapOf(setOf("h", "i") to "hi", setOf("o", "k") to "ok")

        val page: FoldedPage = FoldedPage(
            pageId = PageId("page"),
            canvas = PageCanvas.fixed(PageSize.Letter),
            background = Background.Blank,
            objects = letters.filter { it.first !in deleted }.mapIndexed { index, (id, _) ->
                StrokeObject(
                    id = ObjectId(id),
                    geometry = StrokeGeometry("geometry-of-$id".encodeToByteArray()),
                    transform = transforms[id] ?: Matrix3.Identity,
                    z = FractionalKey("${'a' + index}"),
                    erased = if (id in
                        erased
                    ) {
                        ErasedArea(listOf(EraseCapsule(0f, 0f, 1f, 1f, 1f)))
                    } else {
                        ErasedArea.None
                    },
                )
            },
            deleted = emptySet(),
            throughClock = null,
            opCount = 0,
            formats = formats.mapKeys { ObjectId(it.key) },
        )

        private val sampled: Map<String, SampledStroke> = letters.associate { (id, left) -> id to stroke(id, left) }

        private val sampler = StrokeSampler { page ->
            page.objects.mapNotNull { sampled[it.id.value] }
        }

        private val reader = StrokeReader { page, ids ->
            ids.mapNotNull { id ->
                val obj = page.find(id) ?: return@mapNotNull null
                val samples = sampled.getValue(id.value).samples
                val trajectory = assertNotNull(
                    PenTrajectory.of(
                        x = samples.map { obj.transform.map(PagePoint(it.x, it.y)).x }.toFloatArray(),
                        y = samples.map { obj.transform.map(PagePoint(it.x, it.y)).y }.toFloatArray(),
                        tMs = samples.map { it.tMillis.toInt() }.toIntArray(),
                        pressure = FloatArray(samples.size) { 0.5f },
                        tilt = null,
                        strokeEnds = intArrayOf(samples.size),
                    ),
                )
                id to CapturedStroke(id, trajectory, PenStyle(PenKind.FOUNTAIN, 0xFF000000.toInt(), 1.2f), 0.035f)
            }.toMap()
        }

        private val ink = InkReader { inks, _ ->
            if (unavailable != null) return@InkReader InkReading.Unavailable(unavailable)
            InkReading.Read(
                inks.map { read ->
                    val ids = read.strokes.map { it.id.value }.toSet()
                    val truth = words[ids] ?: ids.singleOrNull() ?: "?"
                    val answer = if (truth in disagree) "wrong" else truth
                    // ML Kit's other guesses for writing it really reads repeat the answer with different
                    // spacing and punctuation. For a shape it takes for a word, they scatter.
                    val others = if (truth in scattered) {
                        listOf("${answer}x", "u$answer", "zz")
                    } else {
                        listOf("$answer!", " $answer", "$answer.")
                    }
                    (listOf(answer) + others).map { ReadCandidate(it, null) }
                },
            )
        }

        private val text: PageText = run {
            val line = LineSegmenter.segment(letters.map { sampled.getValue(it.first) }).lines.single()
            PageText(
                pageId = PageId("page"),
                inkDigest = "digest",
                recogniser = "test",
                lines = listOf(RecognisedLine("hi ok", line.writingArea, WordBoxes.assign(line, "hi ok").words)),
            )
        }

        suspend fun harvest(
            policy: HarvestPolicy = HarvestPolicy(learnFromOlderNotes = false),
            known: Set<String> = emptySet(),
        ): PageHarvest = Harvester(sampler, reader, ink, IdFactory(Clock { 1_800_000_000_000L }, Random(1)))
            .harvest(PageInput(DocumentId("note"), page, text, ledger, epochDay = 20_000L), policy, known)

        suspend fun labels(): List<String> = assertIs<PageHarvest.Harvested>(harvest()).samples.map { it.label }

        companion object {
            val ALL_DOWN = mapOf("h" to 1_000L, "i" to 1_200L, "o" to 2_000L, "k" to 2_200L)

            fun ledgerOf(downs: Map<String, Long>, ruled: Set<String> = emptySet()): Map<ObjectId, LedgerEntry> =
                downs.entries.associate { (id, at) -> ObjectId(id) to LedgerEntry(ObjectId(id), at, id in ruled) }

            /** A zig-zag inside an 8 × 12 pt box, so the line has real turning points and lean. */
            fun stroke(id: String, left: Float): SampledStroke {
                val points = listOf(
                    left to 112f,
                    left + 2f to 100f,
                    left + 4f to 112f,
                    left + 6f to 104f,
                    left + 8f to 112f,
                )
                val samples = points.mapIndexed { index, (x, y) -> InkSample(x, y, index * 8L) }
                return SampledStroke(ObjectId(id), samples, PageRect(left, 100f, left + 8f, 112f))
            }
        }
    }
}
