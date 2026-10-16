package dev.inkstone.core.handwriting.quality

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** P1: what the network reads before it writes (§8.1). */
public enum class PrimeMode {
    /** H4's: the user's own words, this page's first (D15) — chosen per drop. */
    PAGE,

    /** No prime at all — a result too, when priming does not help. */
    NONE,

    /** A fixed set of the user's words ([PersonalState.primeWords]), the one that measured best. */
    FIXED,
}

/** Where a personalisation round has got to; one round is P2, P3, P1, then a measurement. */
public enum class Stage { P2, P3, P1, MEASURE, DONE }

/**
 * Which rungs of §8.1's ladder are in force for a profile, and against which base model.
 *
 * @property baseModel the weights every artefact was fitted to (§4.4). A different base model makes
 *   every artefact stale: it goes to the trash and the ladder starts again.
 * @property prime P1's result; [PrimeMode.PAGE] (H4's behaviour) until P1 has been decided.
 * @property primeWords the sample ids of a [PrimeMode.FIXED] prime set.
 * @property primeDecided whether P1 has been measured at all.
 * @property style P2 accepted: `style/style.bin` is in force.
 * @property adapters P3 accepted: `style/adapters.bin` is in force.
 * @property stage where the current round is — a pass that runs out of time carries on from here.
 * @property fittedOn how many training words the last round saw — the next round starts when the
 *   profile has grown by a quarter.
 */
public data class PersonalState(
    val baseModel: String? = null,
    val prime: PrimeMode = PrimeMode.PAGE,
    val primeWords: List<String> = emptyList(),
    val primeDecided: Boolean = false,
    val style: Boolean = false,
    val adapters: Boolean = false,
    val stage: Stage = Stage.DONE,
    val fittedOn: Int = 0,
    val lastRunEpochMillis: Long = 0L,
) {
    val rungs: Set<String>
        get() = buildSet {
            if (primeDecided && prime != PrimeMode.NONE) add("P1:${prime.name.lowercase()}")
            if (style) add("P2")
            if (adapters) add("P3")
        }
}

/** JSON for what phase 5 keeps in `profile.json`. Every reader returns a default for anything it cannot read. */
public object QualityJson {
    private const val MAX_HISTORY = 200

    public fun measures(list: List<QualityMeasure>): JsonArray = JsonArray(list.takeLast(MAX_HISTORY).map(::measure))

    public fun measure(m: QualityMeasure): JsonObject = JsonObject(
        mapOf(
            "at" to JsonPrimitive(m.epochMillis),
            "words" to JsonPrimitive(m.words),
            "heldOut" to JsonPrimitive(m.heldOut),
            "fAssembly" to (m.fAssembly?.let(::JsonPrimitive) ?: JsonNull),
            "fNeural" to (m.fNeural?.let(::JsonPrimitive) ?: JsonNull),
            "coverage" to JsonPrimitive(m.assemblyCoverage),
            "firstPass" to (m.neuralFirstPass?.let(::JsonPrimitive) ?: JsonNull),
            "gated" to JsonPrimitive(m.gatedWords),
            "chars" to JsonObject(
                m.charFails.entries.associate { (c, f) ->
                    c.toString() to
                        JsonArray(listOf(JsonPrimitive(f.failed), JsonPrimitive(f.words)))
                },
            ),
            "rungs" to JsonArray(m.rungs.sorted().map(::JsonPrimitive)),
        ),
    )

    public fun measuresOf(element: JsonElement?): List<QualityMeasure> =
        (element as? JsonArray).orEmpty().mapNotNull { measureOf(it) }

    @Suppress("ReturnCount")
    public fun measureOf(element: JsonElement?): QualityMeasure? {
        val o = element as? JsonObject ?: return null
        fun f(k: String) = (o[k] as? JsonPrimitive)?.floatOrNull
        fun i(k: String) = (o[k] as? JsonPrimitive)?.intOrNull
        val chars = (o["chars"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
            val pair = (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            if (k.length == 1 && pair != null && pair.size == 2) k[0] to CharFails(pair[0], pair[1]) else null
        }.toMap()
        return QualityMeasure(
            epochMillis = (o["at"] as? JsonPrimitive)?.longOrNull ?: return null,
            words = i("words") ?: 0,
            heldOut = i("heldOut") ?: 0,
            fAssembly = f("fAssembly"),
            fNeural = f("fNeural"),
            assemblyCoverage = f("coverage") ?: 0f,
            neuralFirstPass = f("firstPass"),
            gatedWords = i("gated") ?: 0,
            charFails = chars,
            rungs = (o["rungs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet(),
        )
    }

    public fun policy(p: PolicyState): JsonObject = JsonObject(
        mapOf(
            "primary" to JsonPrimitive(p.primary.name),
            "routed" to JsonPrimitive(p.routedToAssembly.sorted().joinToString("")),
            "since" to JsonPrimitive(p.since),
        ),
    )

    public fun policyOf(element: JsonElement?): PolicyState {
        val o = element as? JsonObject ?: return PolicyState.INITIAL
        return PolicyState(
            primary =
            Backend.entries.firstOrNull { it.name == (o["primary"] as? JsonPrimitive)?.contentOrNull }
                ?: Backend.ASSEMBLY,
            routedToAssembly = (o["routed"] as? JsonPrimitive)?.contentOrNull.orEmpty().toSet(),
            since = (o["since"] as? JsonPrimitive)?.longOrNull ?: 0L,
        )
    }

    public fun personal(p: PersonalState): JsonObject = JsonObject(
        mapOf(
            "base" to (p.baseModel?.let(::JsonPrimitive) ?: JsonNull),
            "prime" to JsonPrimitive(p.prime.name),
            "primeWords" to JsonArray(p.primeWords.map(::JsonPrimitive)),
            "primeDecided" to JsonPrimitive(p.primeDecided),
            "style" to JsonPrimitive(p.style),
            "adapters" to JsonPrimitive(p.adapters),
            "stage" to JsonPrimitive(p.stage.name),
            "fittedOn" to JsonPrimitive(p.fittedOn),
            "lastRun" to JsonPrimitive(p.lastRunEpochMillis),
        ),
    )

    public fun personalOf(element: JsonElement?): PersonalState {
        val o = element as? JsonObject ?: return PersonalState()
        fun b(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull == "true"
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        return PersonalState(
            baseModel = s("base"),
            prime = PrimeMode.entries.firstOrNull { it.name == s("prime") } ?: PrimeMode.PAGE,
            primeWords = (o["primeWords"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            primeDecided = b("primeDecided"),
            style = b("style"),
            adapters = b("adapters"),
            stage = Stage.entries.firstOrNull { it.name == s("stage") } ?: Stage.DONE,
            fittedOn = (o["fittedOn"] as? JsonPrimitive)?.intOrNull ?: 0,
            lastRunEpochMillis = (o["lastRun"] as? JsonPrimitive)?.longOrNull ?: 0L,
        )
    }

    public fun choiceOf(element: JsonElement?): BackendChoice =
        BackendChoice.entries.firstOrNull { it.name == (element as? JsonPrimitive)?.contentOrNull }
            ?: BackendChoice.AUTO
}
