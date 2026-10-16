package dev.inkstone.core.handwriting.quality

/** Which backend writes a word first. */
public enum class Backend {
    /** Glyph assembly: the user's own letters (§6.1). */
    ASSEMBLY,

    /** The synthesis network (§7). */
    NEURAL,
}

/** §8.4's user override, *My handwriting*'s setting. */
public enum class BackendChoice {
    /** The measured policy decides ([BackendPolicy.decide]). */
    AUTO,

    /** The user's own letters first; the network only for what they cannot write (H4's behaviour). */
    MY_LETTERS,

    /** The network first; the user's letters for characters it fails, and when it fails a word. */
    MODEL,
}

/**
 * What one personalisation pass measured — a point on the fidelity curve (§8.3: *"improves with
 * use"*) and the evidence §8.4's switch is decided on. Counts and rates only: no word the user wrote.
 *
 * @property words the training words the profile held: the curve's x-axis.
 * @property heldOut how many held-out words F was measured over (§8.3 asks for 50; a young profile has
 *   fewer, and the number travels with the score so nobody mistakes ten words for fifty).
 * @property fAssembly F of glyph assembly, the median over the held-out words it could write.
 * @property fNeural F of the network as personalised by every accepted rung.
 * @property assemblyCoverage the share of the held-out words assembly could write at all.
 * @property neuralFirstPass the share of the network's words that passed the gate on their first sample,
 *   over [gatedWords] of them.
 * @property charFails per character, (first-pass failures, words) among the network's words holding it.
 * @property rungs which of P1, P2 and P3 were in force when this was measured.
 */
public data class QualityMeasure(
    val epochMillis: Long,
    val words: Int,
    val heldOut: Int,
    val fAssembly: Float?,
    val fNeural: Float?,
    val assemblyCoverage: Float,
    val neuralFirstPass: Float?,
    val gatedWords: Int,
    val charFails: Map<Char, CharFails> = emptyMap(),
    val rungs: Set<String> = emptySet(),
)

/** [failed] of [words] network words holding a character failed the gate on their first sample. */
public data class CharFails(val failed: Int, val words: Int) {
    public val rate: Float get() = if (words == 0) 0f else failed.toFloat() / words
}

/**
 * Which backend is primary, and which characters go to assembly whatever is primary.
 *
 * @property since when [primary] last changed — the device check reads this to show a switch happened.
 */
public data class PolicyState(
    val primary: Backend = Backend.ASSEMBLY,
    val routedToAssembly: Set<Char> = emptySet(),
    val since: Long = 0L,
) {
    public companion object {
        public val INITIAL: PolicyState = PolicyState()
    }
}

/** What the synthesizer does with a word: who goes first, and which characters the network does not get first. */
public data class Route(val primary: Backend, val avoidNeural: Set<Char> = emptySet()) {
    public companion object {
        /** H3/H4's behaviour: assembly first, the network for what assembly cannot write. */
        public val ASSEMBLY_FIRST: Route = Route(Backend.ASSEMBLY)
    }
}

/**
 * ⭐ docs/HANDWRITING-SYNTHESIS.md §8.4 — when the network becomes primary, and when it stops being.
 *
 * **Switch to the network** when, over at least [MIN_GATED] gated words, all three hold:
 * `F_neural ≥ F_assembly − F_MARGIN`, `F_neural ≥ F_MIN`, and the network's first-pass gate rate is at
 * least [GATE_MIN]. **Revert** when any of the three falls below its threshold by [HYSTERESIS] — the
 * gap between the two is what stops a profile on the boundary flapping between hands from one night to
 * the next. **Too little evidence changes nothing.**
 *
 * **Per character** ([routed]): characters the network fails more than [CHAR_FAIL] of the time, over at
 * least [MIN_CHAR_WORDS] words, go to assembly whenever assembly can write the word.
 */
public object BackendPolicy {
    public const val F_MARGIN: Float = 0.05f
    public const val F_MIN: Float = 0.6f
    public const val GATE_MIN: Float = 0.9f
    public const val HYSTERESIS: Float = 0.05f
    public const val MIN_GATED: Int = 200
    public const val CHAR_FAIL: Float = 0.2f
    public const val MIN_CHAR_WORDS: Int = 5

    public fun decide(previous: PolicyState, m: QualityMeasure): PolicyState {
        val routed = routed(m.charFails)
        val fN = m.fNeural
        val fA = m.fAssembly
        // A first-pass rate over too few words is no evidence at all.
        val gate = m.neuralFirstPass?.takeIf { m.gatedWords >= MIN_GATED }
        if (fN == null || fA == null || gate == null) return previous.copy(routedToAssembly = routed)
        val primary = when (previous.primary) {
            Backend.ASSEMBLY -> if (fN >= fA - F_MARGIN && fN >= F_MIN &&
                gate >= GATE_MIN
            ) {
                Backend.NEURAL
            } else {
                Backend.ASSEMBLY
            }

            Backend.NEURAL -> {
                val falls = fN < fA - F_MARGIN - HYSTERESIS || fN < F_MIN - HYSTERESIS || gate < GATE_MIN - HYSTERESIS
                if (falls) Backend.ASSEMBLY else Backend.NEURAL
            }
        }
        val since = if (primary != previous.primary) m.epochMillis else previous.since
        return PolicyState(primary, routed, since)
    }

    /** §8.4's per-character routing. */
    public fun routed(fails: Map<Char, CharFails>): Set<Char> =
        fails.filter { (_, f) -> f.words >= MIN_CHAR_WORDS && f.rate > CHAR_FAIL }.keys

    /** What the synthesizer is told, given the user's [choice] and the measured [policy]. */
    public fun route(choice: BackendChoice, policy: PolicyState): Route = when (choice) {
        BackendChoice.AUTO -> Route(policy.primary, policy.routedToAssembly)
        BackendChoice.MY_LETTERS -> Route(Backend.ASSEMBLY, policy.routedToAssembly)
        BackendChoice.MODEL -> Route(Backend.NEURAL, policy.routedToAssembly)
    }
}
