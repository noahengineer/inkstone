package dev.inkstone.core.handwriting.quality

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/HANDWRITING-SYNTHESIS.md §8.4 — the switching policy, its hysteresis, per-character routing and
 * the user's override. Pure decisions over plain numbers; the numbers themselves are the device's.
 */
class BackendPolicyTest {

    private fun measure(
        fN: Float? = 0.7f,
        fA: Float? = 0.7f,
        gate: Float? = 0.95f,
        gated: Int = 250,
        fails: Map<Char, CharFails> = emptyMap(),
        at: Long = 100L,
    ) = QualityMeasure(
        at, words = 300, heldOut = 50, fAssembly = fA, fNeural = fN, assemblyCoverage = 0.9f,
        neuralFirstPass = gate, gatedWords = gated, charFails = fails,
    )

    @Test
    fun theNetworkBecomesPrimaryWhenAllThreeHold() {
        val next = BackendPolicy.decide(PolicyState.INITIAL, measure(fN = 0.66f, fA = 0.7f, gate = 0.9f))
        assertEquals(Backend.NEURAL, next.primary)
        assertEquals(100L, next.since, "when it changed")
    }

    @Test
    fun eachConditionAloneHoldsTheSwitchBack() {
        val start = PolicyState.INITIAL
        assertEquals(
            Backend.ASSEMBLY,
            BackendPolicy.decide(start, measure(fN = 0.64f, fA = 0.7f)).primary,
            "F_neural < F_assembly - 0.05",
        )
        assertEquals(
            Backend.ASSEMBLY,
            BackendPolicy.decide(start, measure(fN = 0.59f, fA = 0.5f)).primary,
            "F_neural < 0.6",
        )
        assertEquals(Backend.ASSEMBLY, BackendPolicy.decide(start, measure(gate = 0.89f)).primary, "first pass < 0.9")
    }

    @Test
    fun tooLittleEvidenceChangesNothing() {
        assertEquals(Backend.ASSEMBLY, BackendPolicy.decide(PolicyState.INITIAL, measure(gated = 199)).primary)
        val neural = PolicyState(Backend.NEURAL, emptySet(), 5L)
        assertEquals(neural, BackendPolicy.decide(neural, measure(fN = 0.1f, gated = 10)), "nor does it revert")
        assertEquals(Backend.ASSEMBLY, BackendPolicy.decide(PolicyState.INITIAL, measure(fN = null)).primary)
    }

    @Test
    fun theHysteresisKeepsANeuralProfileOnTheBoundaryWhereItIs() {
        val neural = PolicyState(Backend.NEURAL, emptySet(), 5L)
        // Below every switching threshold, but inside the 0.05 margin of each: it stays.
        val inside = BackendPolicy.decide(neural, measure(fN = 0.56f, fA = 0.65f, gate = 0.86f))
        assertEquals(Backend.NEURAL, inside.primary)
        assertEquals(5L, inside.since, "nothing changed, so `since` does not move")
        // Past the margin on any one: it reverts.
        assertEquals(Backend.ASSEMBLY, BackendPolicy.decide(neural, measure(fN = 0.54f, fA = 0.5f)).primary)
        assertEquals(Backend.ASSEMBLY, BackendPolicy.decide(neural, measure(fN = 0.6f, fA = 0.71f)).primary)
        assertEquals(Backend.ASSEMBLY, BackendPolicy.decide(neural, measure(gate = 0.84f)).primary)
        // And the same numbers do not switch an assembly profile: the gap between the two is the point.
        assertEquals(
            Backend.ASSEMBLY,
            BackendPolicy.decide(PolicyState.INITIAL, measure(fN = 0.56f, fA = 0.65f, gate = 0.86f)).primary,
        )
    }

    @Test
    fun charactersTheNetworkFailsOverAFifthOfTheTimeAreRoutedToAssembly() {
        val fails = mapOf(
            '7' to CharFails(failed = 3, words = 10),
            'a' to CharFails(failed = 2, words = 10),
            'q' to CharFails(failed = 3, words = 4),
        )
        assertEquals(setOf('7'), BackendPolicy.routed(fails), "0.3 of 10: routed; 0.2: not; 3 of 4 is too few words")
        assertEquals(
            setOf('7'),
            BackendPolicy.decide(PolicyState.INITIAL, measure(fails = fails, gated = 10)).routedToAssembly,
            "routing is refreshed even when the evidence is too thin to switch",
        )
    }

    @Test
    fun theUsersChoiceOverridesThePrimaryAndKeepsTheRouting() {
        val policy = PolicyState(Backend.ASSEMBLY, setOf('%'), 0L)
        assertEquals(Route(Backend.ASSEMBLY, setOf('%')), BackendPolicy.route(BackendChoice.AUTO, policy))
        assertEquals(Route(Backend.NEURAL, setOf('%')), BackendPolicy.route(BackendChoice.MODEL, policy))
        val neural = policy.copy(primary = Backend.NEURAL)
        assertEquals(Route(Backend.ASSEMBLY, setOf('%')), BackendPolicy.route(BackendChoice.MY_LETTERS, neural))
        assertEquals(Route(Backend.NEURAL, setOf('%')), BackendPolicy.route(BackendChoice.AUTO, neural))
    }

    @Test
    fun theStateSurvivesItsJson() {
        val m = measure(fails = mapOf('7' to CharFails(3, 10))).copy(rungs = setOf("P2", "P3"))
        assertEquals(m, QualityJson.measureOf(QualityJson.measure(m)))
        val p = PolicyState(Backend.NEURAL, setOf('7', '%'), 42L)
        assertEquals(p, QualityJson.policyOf(QualityJson.policy(p)))
        val s = PersonalState("a6a191cb", PrimeMode.FIXED, listOf("w1", "w2"), true, true, false, Stage.P3, 120, 7L)
        assertEquals(s, QualityJson.personalOf(QualityJson.personal(s)))
        assertEquals(BackendChoice.AUTO, QualityJson.choiceOf(null), "a missing choice is Auto")
    }
}
