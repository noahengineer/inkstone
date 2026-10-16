package dev.inkstone.core.handwriting

import dev.inkstone.core.handwriting.neural.FitCheckpoint
import dev.inkstone.core.handwriting.neural.FitPlan
import dev.inkstone.core.handwriting.neural.LicencePolicy
import dev.inkstone.core.handwriting.neural.ModelLoad
import dev.inkstone.core.handwriting.neural.Rung
import dev.inkstone.core.handwriting.neural.TinyModelFixture
import dev.inkstone.core.handwriting.neural.WeightsFile
import dev.inkstone.core.handwriting.quality.PrimeMode
import dev.inkstone.core.handwriting.quality.Stage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * docs/HANDWRITING-SYNTHESIS.md §8 — the personalisation pass's **state machine**, end to end, over the
 * real fit, the real fidelity measure and a fixture hand in the tiny model's alphabet: a pass that runs
 * out of time stops at a checkpoint and the next carries on from it; a finished rung lands its artefact
 * in `style/` or in the trash; a round ends in a measurement and §8.4's decision; nothing is owed until
 * the profile grows; and a new base model sends every artefact to the trash.
 *
 * ⚠️ The recogniser reads everything as the same few words, so the gate passes almost nothing: these
 * prove the bookkeeping, not that a rung helps. Whether one does is the user's profile's question (§20).
 */
class PersonaliserTest {

    private val weights = (WeightsFile.read(TinyModelFixture.bytes, LicencePolicy.RELEASE) as ModelLoad.Loaded).weights
    private val other = (
        WeightsFile.read(
            TinyModelFixture.nonCommercial,
            LicencePolicy.DEVELOPMENT,
        ) as ModelLoad.Loaded
        ).weights
    private val key = ProfileKey("test")
    private val reader = ScriptedReader(fallback = { listOf("abc", "bad") })

    /** The tiny model's letters, in this hand. */
    private val shapes: Map<Char, List<List<Pair<Float, Float>>>> = mapOf(
        'a' to listOf(listOf(0.6f to 0.8f, 0.3f to 1f, 0f to 0.5f, 0.3f to 0f, 0.6f to 0.2f, 0.6f to 1f, 0.62f to 0f)),
        'b' to listOf(listOf(0f to 2f, 0f to 0f, 0.4f to 0.1f, 0.5f to 0.6f, 0.2f to 0.9f, 0f to 0.6f)),
        'c' to listOf(listOf(0.5f to 0.85f, 0.2f to 1f, 0f to 0.5f, 0.2f to 0f, 0.5f to 0.15f)),
        'd' to listOf(listOf(0.5f to 0.8f, 0.2f to 1f, 0f to 0.5f, 0.2f to 0f, 0.5f to 0.2f, 0.55f to 2f, 0.55f to 0f)),
        '=' to listOf(listOf(0f to 0.35f, 0.6f to 0.35f), listOf(0f to 0.65f, 0.6f to 0.65f)),
    )

    private val vocabulary =
        listOf("abc", "bad", "cab", "dab", "add", "dad", "cad", "bac", "acd", "dcba", "abba", "bab")
    private val samples: List<WordSample> = List(40) { i ->
        HandFixtures.sample(
            vocabulary[i % vocabulary.size],
            id = "w$i",
            shapes = shapes,
            split = if (i >= 34) SampleSplit.HELD_OUT else SampleSplit.TRAIN,
            letterGap = 0.2f + (i % 5) * 0.03f,
        )
    } + List(2) { HandFixtures.sample("=", id = "eq$it", shapes = shapes) } // too few to calibrate a class

    private val plans = Personaliser.Plans(
        p2 = FitPlan(Rung.STYLE, epochs = 2, batch = 6, learningRate = 0.05f, prior = 0.01f, checkpointEvery = 2),
        p3 = FitPlan(
            Rung.ADAPTERS,
            epochs = 2,
            batch = 6,
            learningRate = 0.01f,
            prior = 0.001f,
            rank = 2,
            checkpointEvery = 2,
        ),
        gated = 12,
        acceptGated = 6,
        primeCandidates = 1,
    )

    private class Profile(val fs: FakeFileSystem, var state: ProfileState) {
        val edit: suspend (
            (ProfileState) -> ProfileState,
        ) -> ProfileState = { change -> change(state).also { state = it } }
    }

    private fun personaliser(fs: FakeFileSystem, w: WeightsFile = weights): Personaliser {
        var clock = 1_000L
        return Personaliser(fs, key, w, reader, now = { clock++ }, plans = plans)
    }

    @Test
    fun aRoundPausesAtACheckpointResumesAndEndsInAMeasurement() = runTest {
        val profile = Profile(FakeFileSystem(), ProfileState())
        val p = personaliser(profile.fs)
        var calls = 0
        val paused = p.pass(samples, LabelBook(), profile.state, { ++calls > 3 }, profile.edit)

        assertEquals(Personaliser.Outcome.Paused(Stage.P2), paused)
        assertTrue(profile.fs.exists(HandwritingLayout.checkpoint(key)), "the fit stopped at a checkpoint")
        assertTrue(profile.state.gate != null, "calibrated first")
        assertEquals(weights.modelId, profile.state.personal.baseModel)

        val calibratedAt = profile.state.gate?.measuredEpochMillis
        val measured = p.pass(samples, LabelBook(), profile.state, { false }, profile.edit)
        assertEquals(calibratedAt, profile.state.gate?.measuredEpochMillis, "the same words: not calibrated again")

        val m = assertIs<Personaliser.Outcome.Measured>(measured).measure
        assertEquals(Stage.DONE, profile.state.personal.stage)
        assertFalse(
            profile.fs.exists(HandwritingLayout.checkpoint(key)),
            "a finished fit's checkpoint goes to the trash",
        )
        assertEquals(listOf(m), profile.state.quality, "one point on the curve")
        assertEquals(6, m.heldOut)
        // This reader misreads the user's own words, so calibration says words cannot be judged here —
        // and §8.2 then never hands one to the network, so there is nothing to gate.
        assertEquals(false, profile.state.gate?.classes?.get(TokenClass.WORD)?.judgeable)
        assertEquals(0, m.gatedWords)
        assertTrue(profile.state.personal.primeDecided)
        // Every rung either landed in style/ or was kept in the trash as rejected — never lost.
        val trash = profile.fs.list(HandwritingLayout.trashDir(key))
        assertEquals(profile.state.personal.style, profile.fs.exists(HandwritingLayout.styleVector(key)))
        assertEquals(profile.state.personal.adapters, profile.fs.exists(HandwritingLayout.adapters(key)))
        assertEquals(!profile.state.personal.style, trash.any { it.endsWith("rejected-style.bin") }, "trash: $trash")
        assertEquals(
            !profile.state.personal.adapters,
            trash.any {
                it.endsWith("rejected-adapters.bin")
            },
            "trash: $trash",
        )
        if (profile.state.personal.prime == PrimeMode.FIXED) assertTrue(profile.state.personal.primeWords.isNotEmpty())

        assertEquals(
            Personaliser.Outcome.Idle,
            p.pass(samples, LabelBook(), profile.state, {
                false
            }, profile.edit),
            "nothing owed until it grows",
        )
    }

    /**
     * A fit that finishes just as its time runs out keeps its checkpoint, and the next pass decides it
     * rather than fitting it again — a job stopped during the decision used to lose the whole fit.
     */
    @Test
    fun aFitFinishedAsTimeRanOutIsDecidedNextPassNotRefitted() = runTest {
        val profile = Profile(FakeFileSystem(), ProfileState())
        val logs = ArrayList<String>()
        var clock = 1_000L
        val p = Personaliser(profile.fs, key, weights, reader, now = { clock++ }, log = { logs += it }, plans = plans)
        val path = HandwritingLayout.checkpoint(key)
        fun finished(c: FitCheckpoint) =
            c.iteration == c.plan.epochs * ((c.words.size + c.plan.batch - 1) / c.plan.batch)
        val outOfTime = { profile.fs.peek(path)?.let(FitCheckpoint::decode)?.let(::finished) == true }

        assertEquals(
            Personaliser.Outcome.Paused(Stage.P2),
            p.pass(samples, LabelBook(), profile.state, outOfTime, profile.edit),
        )
        val saved =
            FitCheckpoint.decode(profile.fs.peek(path) ?: error("the finished fit is on disk")) ?: error("decodes")
        assertTrue(finished(saved))
        assertEquals(Stage.P2, profile.state.personal.stage, "not decided yet")

        p.pass(samples, LabelBook(), profile.state, { false }, profile.edit)
        assertTrue("resuming STYLE at iteration ${saved.iteration}" in logs, "decided from the checkpoint: $logs")
        assertEquals(Stage.DONE, profile.state.personal.stage)
    }

    @Test
    fun aNewBaseModelSendsEveryArtefactToTheTrashAndStartsAgain() = runTest {
        val profile = Profile(FakeFileSystem(), ProfileState())
        personaliser(profile.fs).pass(samples, LabelBook(), profile.state, { false }, profile.edit)
        val before = listOf(HandwritingLayout.styleVector(key), HandwritingLayout.adapters(key)).filter {
            profile.fs.exists(it)
        }

        var calls = 0
        personaliser(profile.fs, other).pass(samples, LabelBook(), profile.state, { ++calls > 1 }, profile.edit)

        assertEquals(other.modelId, profile.state.personal.baseModel)
        assertFalse(
            profile.state.personal.style || profile.state.personal.adapters,
            "nothing fitted to the old weights is in force",
        )
        for (path in before) assertFalse(profile.fs.exists(path), "$path moved out")
        val trash = profile.fs.list(HandwritingLayout.trashDir(key))
        for (path in before) {
            assertTrue(
                trash.any {
                    it.endsWith(path.substringAfterLast('/'))
                },
                "$path is in the trash: $trash",
            )
        }
    }

    /**
     * §21.4 changed what a number must read as, so a calibration from before it describes a gate that no
     * longer exists: it is measured again on the next pass, though no word was added.
     */
    @Test
    fun aCalibrationFromBeforeTheRuleChangedIsMeasuredAgain() = runTest {
        val old = GateCalibration(5L, emptyMap(), wordsRead = samples.size, ruleVersion = 1)
        val profile = Profile(FakeFileSystem(), ProfileState(gate = old))
        val logs = ArrayList<String>()
        val p = Personaliser(profile.fs, key, weights, reader, now = { 7L }, log = { logs += it }, plans = plans)

        p.pass(samples, LabelBook(), profile.state, { true }, profile.edit)

        val gate = assertNotNull(profile.state.gate)
        assertEquals(GateCalibration.RULE_VERSION, gate.ruleVersion)
        assertEquals(7L, gate.measuredEpochMillis, "measured again")
        assertTrue(logs.any { it.startsWith("calibrated: ") && "among candidates" in it }, "$logs")
    }

    @Test
    fun tooFewWordsFitsNothing() = runTest {
        val profile = Profile(FakeFileSystem(), ProfileState())
        val outcome = personaliser(profile.fs).pass(samples.take(8), LabelBook(), profile.state, {
            false
        }, profile.edit)
        assertIs<Personaliser.Outcome.TooLittle>(outcome)
        assertFalse(profile.fs.exists(HandwritingLayout.checkpoint(key)))
    }

    @Test
    fun anArtefactForOtherWeightsIsNeverRead() {
        val style = FloatArray(4) { it.toFloat() }
        val bytes = Artefact.style(weights.modelId, style)
        assertTrue(Artefact.readStyle(bytes, weights.modelId).contentEquals(style))
        assertEquals(null, Artefact.readStyle(bytes, other.modelId))
        assertEquals(null, Artefact.readAdapters(bytes, weights.modelId), "a style file is not adapters")
    }
}
