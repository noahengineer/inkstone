package dev.inkstone.core.handwriting

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * docs/HANDWRITING-SYNTHESIS.md §6.1 — what assembly builds a word from, and the decisions it makes:
 * which units, which joins, how much variation, and what it refuses.
 *
 * The hand is [HandFixtures]' synthetic one, which is a generous witness: see its KDoc.
 */
class GlyphAssemblyTest {

    private fun index(vararg samples: WordSample, labels: LabelBook = LabelBook()) =
        GlyphIndex.build(samples.toList(), labels)

    /** [text] assembled from [index] with a fresh used-set, as a write's first attempt would. */
    private fun assemble(index: GlyphIndex, text: String, variation: Variation, seed: Int): AssembledWord =
        GlyphAssembly(index).word(text, variation, Random(seed), HashSet())

    @Test
    fun `words letters and runs become units - and nothing the user did not write`() {
        val index = index(HandFixtures.sample("nun"), HandFixtures.sample("mix"))

        assertEquals(UnitKind.WORD, index.variants("nun").single().kind)
        assertEquals(2, index.variants("n").size)
        assertEquals(UnitKind.RUN, index.variants("nu").single().kind)
        // A run never duplicates the whole word.
        assertTrue(index.variants("mix").all { it.kind == UnitKind.WORD })
        assertTrue(index.variants("z").isEmpty())
        assertEquals(setOf("n", "u", "m", "i", "x"), index.alphabet)
    }

    @Test
    fun `held out removed excluded and corrected words do not teach assembly`() {
        val heldOut = HandFixtures.sample("nun", split = SampleSplit.HELD_OUT)
        val removed = HandFixtures.sample("mum")
        val excluded = HandFixtures.sample("lux", noteId = "secret")
        val corrected = HandFixtures.sample("mix")
        val labels = LabelBook(
            rejected = setOf(removed.sourceKey),
            excludedNotes = setOf("secret"),
            corrected = mapOf(corrected.sourceKey to "max"),
        )

        val index = index(heldOut, removed, excluded, corrected, labels = labels)

        assertTrue(index.variants("nun").isEmpty())
        assertTrue(index.variants("mum").isEmpty())
        assertTrue(index.variants("lux").isEmpty())
        // The user's correction names the word, and invalidates the letters cut against the old label.
        assertEquals(1, index.variants("max").size)
        assertTrue(index.variants("i").isEmpty())
    }

    @Test
    fun `a whole word the user wrote is used whole`() {
        val index = index(HandFixtures.sample("nun"), HandFixtures.sample("mun"))
        val word = assertIs<AssembledWord.Written>(assemble(index, "nun", Variation.None, 1))

        assertEquals(listOf(UnitKind.WORD), word.units.map { it.kind })
    }

    @Test
    fun `a word never written is covered by the fewest pieces`() {
        val index = index(HandFixtures.sample("nun"), HandFixtures.sample("mix"))
        val word = assertIs<AssembledWord.Written>(assemble(index, "numix", Variation.None, 2))

        // "nu" + "mix": two pieces, which beats five letters or any split of three.
        assertEquals(2, word.units.size)
        assertEquals("numix", word.units.joinToString("") { it.text })
    }

    @Test
    fun `a letter never written is refused by name and nothing stands in for it`() {
        val index = index(HandFixtures.sample("nun"))
        val refused = assertIs<AssembledWord.Missing>(assemble(index, "nuz", Variation.Table, 3))

        assertEquals(listOf("z"), refused.letters)
    }

    @Test
    fun `one word written ten times gives ten different trajectories`() {
        val index = index(HandFixtures.sample("nun"), HandFixtures.sample("mum"), HandFixtures.sample("mix"))
        val assembly = GlyphAssembly(index)
        val words = (1..10).map { seed ->
            assertIs<AssembledWord.Written>(assembly.word("minx", Variation.Table, Random(seed), HashSet())).trajectory
        }

        for (i in words.indices) {
            for (j in i + 1 until words.size) assertNotEquals(words[i], words[j], "drops $i and $j")
        }
    }

    @Test
    fun `with no variation the user's ink is copied exactly - only moved`() {
        val source = HandFixtures.sample("nun")
        val index = index(source)
        val word = assertIs<AssembledWord.Written>(assemble(index, "nun", Variation.None, 4))
        val unit = index.variants("nun").single().trajectory

        assertEquals(unit.pointCount, word.trajectory.pointCount)
        for (i in 0 until unit.pointCount) {
            assertEquals(unit.x[i], word.trajectory.x[i], 1e-4f)
            assertEquals(unit.y[i], word.trajectory.y[i], 1e-4f)
        }
    }

    @Test
    fun `variation stays within its bounds`() {
        val index = index(HandFixtures.sample("nun"))
        val unit = index.variants("nun").single().trajectory
        val loosest = Variation.forNeatness(0f)
        repeat(20) { seed ->
            val word = assertIs<AssembledWord.Written>(assemble(index, "nun", loosest, seed))
            // Scale, slant, rotation, lift and wobble together move no point more than this far.
            val reach = loosest.unitScale * 2f + loosest.unitSlantRad + loosest.unitRotationRad * 2f +
                loosest.unitBaseline + loosest.wobble + 0.05f
            val dy = (0 until unit.pointCount).maxOf { abs(unit.y[it] - word.trajectory.y[it]) }
            assertTrue(dy <= reach, "seed $seed moved a point $dy, more than $reach")
        }
    }

    @Test
    fun `a variant is not reused within a phrase while another exists`() {
        val index = index(HandFixtures.sample("nun"), HandFixtures.sample("nun"))
        val used = HashSet<UnitSource>()
        val assembly = GlyphAssembly(index)
        val first = assertIs<AssembledWord.Written>(assembly.word("nun", Variation.None, Random(5), used))
        val second = assertIs<AssembledWord.Written>(assembly.word("nun", Variation.None, Random(5), used))

        assertNotEquals(first.units.single().source, second.units.single().source)
    }

    @Test
    fun `this page's writing is preferred`() {
        val here = HandFixtures.sample("nun", noteId = "this", pageId = "p1")
        val there = HandFixtures.sample("nun", noteId = "other", pageId = "p9")
        val index = index(here, there)
        val preference = SourcePreference(noteId = "this", pageId = "p1")
        val picks = (1..200).map { seed ->
            assertIs<AssembledWord.Written>(
                GlyphAssembly(index).word("nun", Variation.None, Random(seed), HashSet(), preference),
            ).units.single().source.noteId
        }

        // Four to one: about 160 of 200.
        assertTrue(picks.count { it == "this" } in 130..190, "picked this page ${picks.count { it == "this" }} times")
    }

    /** Six joined lower-case pairs and four lifted ones: a hand that joins its lower case. */
    private fun joiningHand(vararg extra: WordSample) = index(
        HandFixtures.sample("nm", joined = setOf("nm")),
        HandFixtures.sample("mn", joined = setOf("mn")),
        HandFixtures.sample("un", joined = setOf("un")),
        HandFixtures.sample("mu", joined = setOf("mu")),
        HandFixtures.sample("nu", joined = setOf("nu")),
        HandFixtures.sample("um", joined = setOf("um")),
        HandFixtures.sample("lil"),
        HandFixtures.sample("xu"),
        HandFixtures.sample("xn"),
        *extra,
    )

    @Test
    fun `a pair the user joins comes out joined`() {
        val index = joiningHand()
        assertTrue(index.stats.joins("u", "l"))

        // "ul" was never written; u and l only exist as single letters (from "xu" and "lil").
        val word = assertIs<AssembledWord.Written>(assemble(index, "ul", Variation.None, 6))
        assertEquals(1, word.joins)
        assertEquals(1, word.trajectory.strokeCount)
    }

    @Test
    fun `a pair the user lifts between comes out apart`() {
        // The same letters, every one written on its own.
        val index = index(
            HandFixtures.sample("nm"),
            HandFixtures.sample("mn"),
            HandFixtures.sample("un"),
            HandFixtures.sample("lul"),
        )
        assertFalse(index.stats.joins("u", "l"))

        val word = assertIs<AssembledWord.Written>(assemble(index, "ul", Variation.None, 7))
        assertEquals(0, word.joins)
        assertEquals(2, word.trajectory.strokeCount)
    }

    @Test
    fun `a pair counted lifted stays apart even in a hand that joins`() {
        val index = joiningHand(
            HandFixtures.sample("lul"),
            HandFixtures.sample("lul"),
            HandFixtures.sample("mm", joined = setOf("mm")),
            HandFixtures.sample("nn", joined = setOf("nn")),
            HandFixtures.sample("uu", joined = setOf("uu")),
        )

        // Nine lower-case pairs joined against eight lifted: the class joins, so a pair never seen
        // is joined. But u to l was seen lifted twice, and the pair's own count wins.
        assertTrue(index.stats.joins("n", "l"))
        assertEquals(2, index.stats.joinCount("u", "l").lifted)
        assertFalse(index.stats.joins("u", "l"))
    }

    @Test
    fun `a dot is never a join's exit`() {
        val word = assertIs<AssembledWord.Written>(assemble(joiningHand(), "in", Variation.None, 8))

        // The i's body joins the n; its dot stays a stroke of its own.
        assertEquals(1, word.joins)
        assertEquals(2, word.trajectory.strokeCount)
    }

    @Test
    fun `a word's ink starts at zero on the baseline frame`() {
        val index = index(HandFixtures.sample("mix"))
        val word = assertIs<AssembledWord.Written>(assemble(index, "mix", Variation.Table, 9))
        val box = word.trajectory.bounds()

        assertEquals(0f, box.left, 1e-4f)
        assertEquals(box.width, word.width, 1e-4f)
    }
}
