package dev.inkstone.app.handwriting

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkstone.android.ocr.MlKitInkReader
import dev.inkstone.android.ocr.MlKitModels
import dev.inkstone.core.handwriting.GateRule
import dev.inkstone.core.handwriting.GlyphIndex
import dev.inkstone.core.handwriting.HandwritingSynthesizer
import dev.inkstone.core.handwriting.LabelBook
import dev.inkstone.core.handwriting.LabelSource
import dev.inkstone.core.handwriting.NormalFrame
import dev.inkstone.core.handwriting.Refusal
import dev.inkstone.core.handwriting.ReliabilityGate
import dev.inkstone.core.handwriting.SampleSplit
import dev.inkstone.core.handwriting.WordOutcome
import dev.inkstone.core.handwriting.WordSample
import dev.inkstone.core.handwriting.WriteRequest
import dev.inkstone.core.handwriting.WriteStyle
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PenKind
import dev.inkstone.core.model.PenStyle
import dev.inkstone.core.model.PenTrajectory
import dev.inkstone.core.model.RecognitionUnavailable
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⭐ docs/HANDWRITING-SYNTHESIS.md §11 and H6: **the gate's behaviour against the real ML Kit**, on a
 * device. Every other gate test runs against a scripted reader, which proves the bookkeeping and nothing
 * about whether ink reads (`HandwritingSynthesizerTest`'s KDoc); this is the other half.
 *
 * **The ink** is `assets/handwriting/gate-fixtures.json` in the test APK, written by
 * `tools/handwriting/gatefixtures.py`: words, numbers and their look-alikes, each by two synthetic
 * writers — licensed single-line fonts animated by the sigma-lognormal model, the writers the shipped
 * model was trained on. Not the user's ink, which is never committed (§9), and not the model's, which
 * would move whenever the model does. The file carries every font's licence.
 *
 * **What it shows,** each through the production [MlKitInkReader] and [ReliabilityGate]:
 * - the positive control: legible ink passes under its own label;
 * - the negative control: the same ink under another word's label is refused, every time (§8.2);
 * - maths: numbers read back, and ink that **lost** its decimal point (§18.10), a wrong digit or a
 *   **gained** point is refused — the last two only since a token with a digit is judged on its first
 *   reading that holds one (§21.4; [aWrongDigitOrAGainedPointIsRefused]);
 * - a scribble is refused; ink turned upside down mostly is, so passing is the ink's doing;
 * - no model: the gate says *unavailable*, and the synthesizer writes nothing (D12);
 * - through the whole synthesizer: a word whose only ink reads as something else is **refused by
 *   name, as illegible** — §18.5's *"an illegible refusal from the real recogniser has not been seen"*.
 *
 * Every reading is logged under `GateMlKit` (the words are the fixture's, never the user's). Needs ML
 * Kit's `en-US` model on the device; without it the tests are skipped, which `am instrument` reports.
 * ⚠️ Run with `adb install -r` and `am instrument`, never `connectedAndroidTest`, which uninstalls the
 * app and with it the emulator's copy of the physics note (CLAUDE.md).
 */
class GateMlKitTest {

    private val reader = MlKitInkReader()
    private val gate = ReliabilityGate(reader)

    @Before
    fun theModelIsHere() {
        assumeTrue("needs ML Kit's $LANGUAGE model on the device", runBlocking { MlKitModels().isDownloaded(LANGUAGE) })
    }

    @Test
    fun legibleInkPassesUnderItsOwnLabel() = runBlocking {
        val samples = fixtures.samples.filter { it.text in fixtures.words }
        val verdict = read(samples.map { item(it, it.text) })
        val passed = verdict.passed.count { it }
        logEach("positive", samples.map { it.text }, verdict)
        Log.i(TAG, "positive: $passed/${samples.size} words passed under their own label")
        assertTrue(passed >= samples.size * POSITIVE_FLOOR, "only $passed/${samples.size} legible words passed")
    }

    @Test
    fun theSameInkUnderAnotherWordsLabelIsRefused() = runBlocking {
        val samples = fixtures.samples.filter { it.text in fixtures.words }
        val labels = samples.map { wrongLabelFor(it.text) }
        val verdict = read(samples.zip(labels).map { (s, label) -> item(s, label) })
        logEach("negative", labels, verdict)
        val passed = labels.indices.filter { verdict.passed[it] }.map { "${samples[it].text} as ${labels[it]}" }
        Log.i(TAG, "negative: ${labels.size - passed.size}/${labels.size} refused")
        assertTrue(passed.isEmpty(), "wrong labels passed: $passed")
    }

    @Test
    fun numbersReadBackUnderTheirOwnLabel() = runBlocking {
        val samples = fixtures.samples.filter { it.text in fixtures.numbers }
        val right = read(samples.map { item(it, it.text) })
        logEach("numbers", samples.map { it.text }, right)
        val passed = right.passed.count { it }
        Log.i(TAG, "numbers: $passed/${samples.size} passed under their own label")
        assertTrue(passed >= samples.size * NUMBER_FLOOR, "only $passed/${samples.size} numbers passed")
    }

    /**
     * §18.10's vanished decimal point, against the recogniser itself: ink whose point is **gone** must
     * not pass under the label that has one — `25`'s ink as `2.5`. The gate never folds punctuation for a
     * token with a digit (§20.2.1), so this is a question about ML Kit's candidates, which only a device
     * can answer. It holds: 10 of 10 refused on 2026-10-15.
     */
    @Test
    fun inkThatLostItsDecimalPointIsRefused() = runBlocking {
        val cases = pointPairs().flatMap { (dotted, bare) ->
            fixtures.samples.filter { it.text == bare }.map {
                it to
                    dotted
            }
        }
        assertTrue(cases.isNotEmpty())
        val verdict = read(cases.map { (s, label) -> item(s, label) })
        logEach("point lost", cases.map { it.second }, verdict)
        val passed = cases.indices.filter {
            verdict.passed[it]
        }.map { "${cases[it].first.text} as ${cases[it].second}" }
        Log.i(TAG, "point lost: ${cases.size - passed.size}/${cases.size} refused")
        assertTrue(passed.isEmpty(), "ink without its point passed: $passed")
    }

    /**
     * ⭐ The other two ways a number goes wrong (docs §21.4). While the rule was *"the label is among ML
     * Kit's five candidates"*, both passed on this ink, because ML Kit's candidates for a number are its
     * digit-for-digit alternatives — `9.81`'s are `9.81, 0.81, 1.81, 2.81, 981`. Measured on 2026-10-15
     * on `Test_Tablet`: **one wrong digit passed 5 of 24 times** (`9.81` as `0.81` twice, `25` as `35`,
     * `12875` as `22875`, `1625` as `2625`), and **a gained point passed 6 of 10** (`2.5` as `25`, `9.81`
     * as `981`, …). A token with a digit is now judged on its first reading that holds one
     * ([GateRule.digitReading]): a look-alike number there is refused, a digit read as a letter is skipped.
     * The user chose it over the 2 of 24 right numbers it refuses ([numbersReadBackUnderTheirOwnLabel]).
     */
    @Test
    fun aWrongDigitOrAGainedPointIsRefused() = runBlocking {
        val numbers = fixtures.samples.filter { it.text in fixtures.numbers }
        val cases = numbers.map { it to oneDigitOff(it.text) } +
            pointPairs().flatMap { (dotted, bare) -> fixtures.samples.filter { it.text == dotted }.map { it to bare } }
        val verdict = read(cases.map { (s, label) -> item(s, label) })
        logEach("wrong digit or gained point", cases.map { it.second }, verdict)
        val passed = cases.indices.filter {
            verdict.passed[it]
        }.map { "${cases[it].first.text} as ${cases[it].second}" }
        Log.i(TAG, "wrong digit or gained point: ${cases.size - passed.size}/${cases.size} refused")
        assertTrue(passed.isEmpty(), "a wrong number passed: $passed")
    }

    @Test
    fun aScribbleIsRefused() = runBlocking {
        val labels = fixtures.words
        val rng = Random(SCRIBBLE_SEED)
        val verdict = read(labels.map { ReliabilityGate.Item(it, scribble(rng), X_HEIGHT_PT, null) })
        logEach("scribble", labels, verdict)
        assertTrue(
            verdict.passed.none {
                it
            },
            "a scribble passed as ${labels.filterIndexed { i, _ -> verdict.passed[i] }}",
        )
    }

    /** The positive control's passes are the ink's doing: the same words upside down mostly fail. */
    @Test
    fun upsideDownInkMostlyFails() = runBlocking {
        val samples = fixtures.samples.filter { it.text in fixtures.words }
        val upright = read(samples.map { item(it, it.text) }).passed.count { it }
        val flipped = read(samples.map { item(it, it.text, upsideDown = true) })
        logEach("upside down", samples.map { it.text }, flipped)
        val passed = flipped.passed.count { it }
        Log.i(TAG, "upside down: $passed/${samples.size} passed, against $upright upright")
        assertTrue(
            passed * 2 < upright,
            "upside down $passed against upright $upright: the gate is not reading the ink",
        )
    }

    @Test
    fun withoutTheModelNothingIsWritten() = runBlocking {
        val absent = MlKitInkReader(ABSENT_LANGUAGE)
        assumeFalse("$ABSENT_LANGUAGE's model is on this device", MlKitModels().isDownloaded(ABSENT_LANGUAGE))
        val verdict = ReliabilityGate(
            absent,
        ).check(listOf(item(fixtures.samples.first(), fixtures.samples.first().text)))
        assertEquals(ReliabilityGate.Verdict.Unavailable(RecognitionUnavailable.MODEL_NOT_DOWNLOADED), verdict)

        val index = GlyphIndex.build(listOf(sample("salt", "salt")), LabelBook())
        val phrase = HandwritingSynthesizer(ReliabilityGate(absent)).write(WriteRequest("salt", STYLE, 1L), index)
        assertEquals(Refusal.Unavailable(RecognitionUnavailable.MODEL_NOT_DOWNLOADED), phrase.refusals.single().reason)
        assertTrue(phrase.strokes(PagePoint(0f, 0f)).isEmpty(), "nothing drawn without a recogniser")
    }

    /**
     * The whole synthesizer with the real recogniser. The hand's `water` is a `water` ML Kit reads; its
     * only `music` is really `paper`'s ink, as a mislabelled harvest would leave it. Assembly builds
     * `music` from it on every rung, ML Kit reads `paper` every time, and the word is refused **by name,
     * as illegible**, with what it was read as — while `water` is written, read back, and alone drawn.
     */
    @Test
    fun theSynthesizerRefusesAnIllegibleWordByNameAndWritesTheRest() = runBlocking {
        val index = GlyphIndex.build(listOf(sample("water", "water"), sample("paper", "music")), LabelBook())
        val phrase = HandwritingSynthesizer(gate).write(WriteRequest("water music", STYLE, 3L), index)

        val water = assertIs<WordOutcome.Written>(phrase.words[0], "water: ${phrase.words[0]}")
        assertTrue(water.readBack)
        val music = assertIs<WordOutcome.Refused>(phrase.words[1], "music: ${phrase.words[1]}")
        val illegible = assertIs<Refusal.Illegible>(music.reason)
        val readAs = assertNotNull(illegible.readAs, "the refusal says what it was read as")
        assertNotEquals("music", readAs)
        Log.i(TAG, "synthesizer: water written in ${water.attempts} attempt(s); music refused, read as '$readAs'")
        assertEquals(water.ink.strokeCount, phrase.strokes(PagePoint(0f, 0f)).size, "only water is drawn")
    }

    // --- The fixture ink -------------------------------------------------------------------------------

    private class Fixture(val text: String, val font: String, val strokes: List<FloatArray>)

    private class Fixtures(val words: List<String>, val numbers: List<String>, val samples: List<Fixture>)

    /** [fixture] as the file holds it: x-heights, y up, baseline 0 — the normalised frame (§5.2). */
    private fun normalOf(fixture: Fixture, upsideDown: Boolean = false): PenTrajectory {
        val builder = PenTrajectory.Builder(fixture.strokes.sumOf { it.size / 2 }, hasPressure = false, hasTilt = false)
        var t = 0
        for ((k, stroke) in fixture.strokes.withIndex()) {
            if (k > 0) t += PEN_UP_MS
            for (i in 0 until stroke.size / 2) {
                builder.add(stroke[2 * i], if (upsideDown) -stroke[2 * i + 1] else stroke[2 * i + 1], t)
                t += MS_PER_POINT
            }
            builder.endStroke()
        }
        return assertNotNull(builder.build(), "fixture '${fixture.text}' is not a trajectory")
    }

    /** [fixture] at the gate's size, in page orientation, its left baseline point at the origin. */
    private fun ink(fixture: Fixture, upsideDown: Boolean = false): PenTrajectory =
        assertNotNull(NormalFrame(0f, 0f, 0f, 0f, X_HEIGHT_PT).denormalise(normalOf(fixture, upsideDown)))

    private fun item(fixture: Fixture, label: String, upsideDown: Boolean = false) =
        ReliabilityGate.Item(label, ink(fixture, upsideDown), X_HEIGHT_PT, null)

    private suspend fun read(items: List<ReliabilityGate.Item>): ReliabilityGate.Verdict.Read =
        assertIs<ReliabilityGate.Verdict.Read>(gate.check(items))

    /** A word of similar length (±1, §8.2's negative control) that is not [text] and not a case of it. */
    private fun wrongLabelFor(text: String): String {
        val others = fixtures.words.filter { !it.equals(text, ignoreCase = true) }
        val k = fixtures.words.indexOf(text)
        return (others.drop(k) + others.take(k)).first { kotlin.math.abs(it.length - text.length) <= 1 }
    }

    /** Each fixture number with a point, beside the same digits without it, where the fixture has both. */
    private fun pointPairs(): List<Pair<String, String>> =
        fixtures.numbers.filter { '.' in it }.map { it to it.replace(".", "") }.filter { (_, bare) ->
            bare in
                fixtures.numbers
        }

    /** [text] with its first digit one higher: what a garbled number looks like. */
    private fun oneDigitOff(text: String): String {
        val i = text.indexOfFirst { it.isDigit() }
        return text.substring(0, i) + ((text[i] - '0' + 1) % DIGITS) + text.substring(i + 1)
    }

    /** Three pen-downs of a random walk the size of a word. */
    private fun scribble(rng: Random): PenTrajectory {
        val builder = PenTrajectory.Builder(SCRIBBLE_POINTS * SCRIBBLE_STROKES, hasPressure = false, hasTilt = false)
        var t = 0
        repeat(SCRIBBLE_STROKES) {
            var x = rng.nextFloat() * SCRIBBLE_WIDTH
            var y = -rng.nextFloat()
            repeat(SCRIBBLE_POINTS) {
                x = (x + (rng.nextFloat() - 0.5f) * 0.6f).coerceIn(0f, SCRIBBLE_WIDTH)
                y = (y + (rng.nextFloat() - 0.5f) * 0.6f).coerceIn(-1.4f, 0.2f)
                builder.add(x * X_HEIGHT_PT, y * X_HEIGHT_PT, t)
                t += MS_PER_POINT
            }
            builder.endStroke()
            t += PEN_UP_MS
        }
        return assertNotNull(builder.build())
    }

    /** A harvested word: [fixture]'s ink, under [label], as the profile would store it. */
    private fun sample(fixtureText: String, label: String): WordSample {
        val fixture = fixtures.samples.first { it.text == fixtureText }
        val frame = NormalFrame(SAMPLE_X, SAMPLE_Y, 0f, 0f, X_HEIGHT_PT)
        val page = assertNotNull(frame.denormalise(normalOf(fixture)))
        return WordSample(
            id = "gate-$label",
            noteId = DocumentId("gate-note"),
            pageId = PageId("gate-page"),
            sourceStrokes = (0 until page.strokeCount).map { ObjectId("gate-$label-$it") },
            sourceKey = "gate-key-$label",
            epochDay = EPOCH_DAY,
            label = label,
            labelSource = LabelSource.CONFIRMED,
            alternatives = emptyList(),
            frame = frame,
            pen = PenStyle(PenKind.BALLPOINT, BLACK, 1f),
            unitLengthCm = null,
            split = SampleSplit.TRAIN,
            trajectory = page,
            glyphs = emptyList(),
        )
    }

    private fun logEach(what: String, labels: List<String>, verdict: ReliabilityGate.Verdict.Read) {
        labels.forEachIndexed { i, label ->
            Log.i(TAG, "$what '$label': ${if (verdict.passed[i]) "PASS" else "refused"} read=${verdict.readings[i]}")
        }
    }

    private companion object {
        const val TAG = "GateMlKit"
        const val LANGUAGE = "en-US"

        /** A language this app offers and the emulator has never downloaded. */
        const val ABSENT_LANGUAGE = "fr-FR"

        /** The gate's own reading size (`Personaliser`'s, `NeuralGateReceiver`'s): 5.5 pt. */
        const val X_HEIGHT_PT = 5.5f

        /**
         * Of the fixture's 40 legible words, at least this share must pass. Set below what was measured on
         * 2026-10-15 (docs §21), as a floor a broken pipeline — the wrong area, ink upside down — falls
         * through by a long way.
         */
        const val POSITIVE_FLOOR = 0.8

        /**
         * Of the fixture's 24 numbers. Lower than [POSITIVE_FLOOR] because a number is judged on its first
         * reading that holds a digit (§21.4), which costs a number whose right text comes after a look-alike
         * number — 20/24 passed under the old rule, 18 under this one. Still far above what a broken
         * pipeline passes.
         */
        const val NUMBER_FLOOR = 0.6
        const val PEN_UP_MS = 120
        const val MS_PER_POINT = 8
        const val DIGITS = 10
        const val SCRIBBLE_SEED = 11
        const val SCRIBBLE_POINTS = 30
        const val SCRIBBLE_STROKES = 3
        const val SCRIBBLE_WIDTH = 4f
        const val SAMPLE_X = 40f
        const val SAMPLE_Y = 100f
        const val EPOCH_DAY = 20_000L
        const val BLACK = 0xFF000000.toInt()

        val STYLE =
            WriteStyle(xHeightPt = X_HEIGHT_PT, slantRad = 0f, angleRad = 0f, wrapWidthPt = 400f, linePitchPt = 20f)

        val fixtures: Fixtures by lazy {
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            val json = JSONObject(assets.open("handwriting/gate-fixtures.json").bufferedReader().use { it.readText() })
            fun strings(key: String) = json.getJSONArray(key).let { a -> List(a.length()) { a.getString(it) } }
            val samples = json.getJSONArray("samples").let { a ->
                List(a.length()) { i ->
                    val s = a.getJSONObject(i)
                    val strokes = s.getJSONArray("strokes").let { st ->
                        List(st.length()) { k ->
                            val p = st.getJSONArray(k)
                            FloatArray(p.length()) { j -> p.getDouble(j).toFloat() }
                        }
                    }
                    Fixture(s.getString("text"), s.getString("font"), strokes)
                }
            }
            Fixtures(strings("words"), strings("numbers"), samples)
        }
    }
}
