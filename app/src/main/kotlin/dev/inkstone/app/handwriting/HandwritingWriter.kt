package dev.inkstone.app.handwriting

import android.util.Log
import dev.inkstone.android.ink.InkBrushFactory
import dev.inkstone.android.ink.InkInputModel
import dev.inkstone.android.ink.TrajectoryInk
import dev.inkstone.core.handwriting.Artefact
import dev.inkstone.core.handwriting.GateCalibration
import dev.inkstone.core.handwriting.GlyphIndex
import dev.inkstone.core.handwriting.HandwritingLayout
import dev.inkstone.core.handwriting.HandwritingSynthesizer
import dev.inkstone.core.handwriting.HarvestPolicy
import dev.inkstone.core.handwriting.NeuralBackend
import dev.inkstone.core.handwriting.PageWriting
import dev.inkstone.core.handwriting.PenDynamics
import dev.inkstone.core.handwriting.ProfileKey
import dev.inkstone.core.handwriting.ProfileState
import dev.inkstone.core.handwriting.ProfileStore
import dev.inkstone.core.handwriting.ReliabilityGate
import dev.inkstone.core.handwriting.SourcePreference
import dev.inkstone.core.handwriting.WordOutcome
import dev.inkstone.core.handwriting.WriteRequest
import dev.inkstone.core.handwriting.WriteStyle
import dev.inkstone.core.handwriting.WrittenPhrase
import dev.inkstone.core.handwriting.neural.KotlinSynthesisNetwork
import dev.inkstone.core.handwriting.neural.LicencePolicy
import dev.inkstone.core.handwriting.neural.ModelLoad
import dev.inkstone.core.handwriting.neural.Primed
import dev.inkstone.core.handwriting.neural.SynthesisNetwork
import dev.inkstone.core.handwriting.neural.WeightsFile
import dev.inkstone.core.handwriting.quality.BackendPolicy
import dev.inkstone.core.handwriting.quality.PrimeMode
import dev.inkstone.core.handwriting.quality.Route
import dev.inkstone.core.handwriting.readLedger
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.InkReader
import dev.inkstone.core.model.ModelSource
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PenStyle
import dev.inkstone.core.model.RecognitionModels
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.StrokeReader
import dev.inkstone.core.model.StrokeSampler
import dev.inkstone.core.sync.AccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * ⭐ docs/HANDWRITING-SYNTHESIS.md phase 3 — everything the Write panel needs between *"these
 * words"* and *"these strokes"*, off the UI thread.
 *
 * - [profile]: the glyph index, built from the profile's files and kept until the learner says the
 *   profile changed ([HandwritingLearner.revision]) — the samples are small, but reading every shard
 *   per keystroke would not be.
 * - [pageWriting]: D15's measurement of a page, kept per page revision, because a drag onto the
 *   physics note's 3,900 strokes must not decode every stroke twice.
 * - [write]: the synthesizer — assembly, the read-back gate, layout — on `Dispatchers.Default`.
 * - [encode]: the phrase as ink blobs (`TrajectoryInk`), also off the UI thread, before the editor's
 *   lock is taken.
 *
 * It holds no note and writes nothing: the one write is `NoteEditor.stampHandwriting`, under the
 * editor's lock, which is where every edit to a note goes.
 */
@Singleton
@Suppress("LongParameterList")
class HandwritingWriter @Inject constructor(
    private val fs: FileSystem,
    private val clock: Clock,
    private val accounts: AccountStore,
    private val learner: HandwritingLearner,
    private val sampler: StrokeSampler,
    private val strokes: StrokeReader,
    reader: InkReader,
    private val models: RecognitionModels,
    brushes: InkBrushFactory,
    private val modelSource: ModelSource,
    private val policy: LicencePolicy,
) {
    private val ink = TrajectoryInk(brushes)
    private val reader = reader
    private val ids = IdFactory(clock)
    private val lock = Mutex()
    private val measuring = Mutex()
    private val priming = Mutex()
    private var cached: Profile? = null
    private var pageCache: Pair<String, PageWriting>? = null
    private var primeCache: Pair<String, Primed>? = null
    private var weightsRead = false
    private var weights: WeightsFile? = null

    /**
     * What a phrase is written from.
     *
     * @property modelReady D12: without the recognition model nothing can be checked, so nothing is
     *   written. The panel says so instead of drawing.
     * @property accountName for D11's signature rule.
     * @property unitLengthCm the median stroke-unit length of the user's own strokes (§6.3).
     * @property neural phase 4's backend, with this hand's pressure, tilt and timing fitted (§7.1) and
     *   every personalisation rung phase 5 accepted (§8.1); null when there is no model, or it was
     *   refused (D13, §4.4).
     * @property calibration §8.2's gate rules for this hand, or the uncalibrated default.
     * @property route §8.4: who writes first — the measured switch, or the user's override.
     * @property prime P1's choice of what the network reads first (§8.1).
     */
    class Profile(
        val index: GlyphIndex,
        val language: String,
        val modelReady: Boolean,
        val accountName: String?,
        val unitLengthCm: Float?,
        val words: Int,
        internal val revision: Long,
        val neural: NeuralBackend? = null,
        val calibration: GateCalibration = GateCalibration.UNCALIBRATED,
        val route: Route = Route.ASSEMBLY_FIRST,
        val prime: PrimeMode = PrimeMode.PAGE,
        val primeWords: List<String> = emptyList(),
    )

    suspend fun profile(): Profile = withContext(Dispatchers.Default) {
        lock.withLock {
            val revision = learner.revision.value
            cached?.takeIf { it.revision == revision }?.let { profile ->
                // The model can arrive while the panel is open; it is a cheap question.
                return@withLock profile.withModel(models.isDownloaded(profile.language))
            }
            val key = learner.profileKey()
            val store = ProfileStore(fs, key)
            val state = store.state()
            val labels = store.labels()
            val samples = store.samples().samples
            val units = samples.mapNotNull { it.unitLengthCm }.sorted()
            val owner = accounts.all().filter { it.isUsable }.minByOrNull { it.id }
            val index = GlyphIndex.build(samples, labels)
            Profile(
                index = index,
                language = state.language,
                modelReady = models.isDownloaded(state.language),
                accountName = owner?.displayName,
                unitLengthCm = units.getOrNull(units.size / 2),
                words = samples.size,
                revision = revision,
                neural = weights()?.let { w ->
                    val dynamics = PenDynamics.fit(index.wholeWords().map { it.trajectory }, index.stats.penUpMs)
                    NeuralBackend(personalised(w, key, state), dynamics)
                },
                calibration = state.gate ?: GateCalibration.UNCALIBRATED,
                route = BackendPolicy.route(state.backend, state.policy),
                prime = state.personal.prime.takeIf {
                    state.personal.baseModel == weights()?.modelId
                } ?: PrimeMode.PAGE,
                primeWords = state.personal.primeWords,
            ).also { cached = it }
        }
    }

    private fun Profile.withModel(ready: Boolean): Profile = if (ready == modelReady) {
        this
    } else {
        Profile(
            index,
            language,
            ready,
            accountName,
            unitLengthCm,
            words,
            revision,
            neural,
            calibration,
            route,
            prime,
            primeWords,
        ).also { cached = it }
    }

    /**
     * The synthesis network's weights, read once (§4.4). A missing asset, one that fails its own
     * checksum, or one a release refuses for its licences (D13) leaves this null — the neural backend is
     * off, and glyph assembly carries on exactly as in phase 3. Each outcome is logged, never the user's
     * words. The personalisation worker reads the same copy ([HandwritingPersonaliser]).
     */
    suspend fun weights(): WeightsFile? = weightsLock.withLock {
        if (weightsRead) return@withLock weights
        weightsRead = true
        val started = clock.nowEpochMillis()
        weights = when (val load = WeightsFile.read(modelSource.bytes(), policy)) {
            is ModelLoad.Loaded -> load.weights.also {
                val shape = it.header.shape
                Log.i(
                    TAG,
                    "model: loaded ${it.modelId} ${shape.layers}x${shape.cells} policy=$policy " +
                        "nonCommercial=${it.nonCommercial} ms=${clock.nowEpochMillis() - started}",
                )
            }

            is ModelLoad.Refused -> null.also { Log.i(TAG, "model: off, ${load.reason}: ${load.detail}") }
        }
        weights
    }

    private val weightsLock = Mutex()

    /**
     * The network with every rung §8.1 accepted for this profile: P2's style vector and P3's adapters,
     * each read only if it names these weights ([Artefact]) — so a stale artefact can never be applied
     * to a model it was not fitted to.
     */
    private suspend fun personalised(w: WeightsFile, key: ProfileKey, state: ProfileState): SynthesisNetwork {
        suspend fun read(path: String) = if (fs.exists(path)) runCatching { fs.readBytes(path) }.getOrNull() else null
        val style = if (state.personal.style) {
            Artefact.readStyle(
                read(HandwritingLayout.styleVector(key)),
                w.modelId,
            )
        } else {
            null
        }
        val adapters = if (state.personal.adapters) {
            Artefact.readAdapters(
                read(HandwritingLayout.adapters(key)),
                w.modelId,
            )
        } else {
            null
        }
        Log.i(
            TAG,
            "network: style=${style != null} adapters=${adapters != null} route=${BackendPolicy.route(
                state.backend,
                state.policy,
            )}",
        )
        return KotlinSynthesisNetwork(w, style, adapters)
    }

    /**
     * ⭐ §19.1 — the neural backend's prime for a drop on [preference]'s page, computed **ahead**: the
     * panel calls this when it opens and when the page changes, so the first word a typed phrase
     * needs from the network does not wait for it. Cached per profile and note; a second caller
     * waits for the first and reads its answer.
     */
    suspend fun primeAhead(profile: Profile, preference: SourcePreference): Primed? = withContext(Dispatchers.Default) {
        val backend = profile.neural ?: return@withContext null
        priming.withLock {
            val key = primeKey(profile, preference)
            primeCache?.takeIf { it.first == key }?.let { return@withLock it.second }
            val started = clock.nowEpochMillis()
            val primed = when (profile.prime) {
                PrimeMode.NONE -> Primed.NONE

                PrimeMode.PAGE -> backend.prime(profile.index, preference, Random.Default)

                PrimeMode.FIXED -> {
                    val byId = profile.index.wholeWords().associateBy { it.source.sampleId }
                    backend.prime(profile.primeWords.mapNotNull { byId[it] }, profile.index)
                }
            }
            Log.i(TAG, "primed: ${primed.isPrimed} ms=${clock.nowEpochMillis() - started}")
            primeCache = key to primed
            primed
        }
    }

    /**
     * Per profile and **note**: the panel primes with the page it is over, and the preview, which
     * names no page, reuses that prime rather than computing a second.
     */
    private fun primeKey(profile: Profile, preference: SourcePreference): String =
        "${profile.revision}/${preference.noteId}"

    private fun cachedPrime(profile: Profile, preference: SourcePreference): Primed? =
        primeCache?.takeIf { it.first == primeKey(profile, preference) }?.second

    /**
     * D15's measurement of [page] in [noteId]: the user's own lines on it, and its paper. Cached by
     * the page's fold revision, so the second drop on an unchanged page measures nothing.
     */
    suspend fun pageWriting(noteId: DocumentId, page: FoldedPage): PageWriting = withContext(Dispatchers.Default) {
        // ⚠️ One measurement at a time: the panel measures ahead while the user types, and a drop that
        // arrives mid-way used to start a second one beside it. On the physics note that was 34 s of
        // two passes fighting over 3,900 stroke decodes, against 6.9 s for one; the second caller now
        // waits and finds the first one's answer in the cache.
        measuring.withLock { measured(noteId, page) }
    }

    private suspend fun measured(noteId: DocumentId, page: FoldedPage): PageWriting {
        val key = "${noteId.value}/${page.pageId.value}/${writingKeyOf(page)}"
        pageCache?.takeIf { it.first == key }?.let { return it.second }
        val started = clock.nowEpochMillis()
        val ledger = fs.readLedger(noteId)
        val policy = HarvestPolicy(ProfileStore(fs, learner.profileKey()).state().learnFromOlderNotes)
        val writing = PageWriting.measure(page, sampler, strokes, ledger, policy)
        val gaps = writing.lines.flatMap { it.wordGaps }.sorted()
        Log.i(
            TAG,
            "measured page: lines=${writing.lines.size} xh=${writing.style.xHeightPt} " +
                "pitch=${writing.style.linePitchPt} " +
                "wordGap=${gaps.getOrNull(gaps.size / 2)} (n=${gaps.size}) ms=${clock.nowEpochMillis() - started}",
        )
        pageCache = key to writing
        return writing
    }

    /**
     * What the measurement depends on: the user's own strokes, where they are, and the paper. Generated
     * ink is left out — it is not measured (D15) — so dropping a phrase does not make the next drop
     * measure the page again. Nothing is decoded; it is ids, matrices and a flag per stroke.
     */
    private fun writingKeyOf(page: FoldedPage): Int {
        var hash = page.background.hashCode()
        for (obj in page.objects) {
            if (obj !is StrokeObject || GeneratedInk.ofRecord(page.formatOf(obj.id)) != null) continue
            hash = HASH_PRIME * hash + obj.id.hashCode()
            hash = HASH_PRIME * hash + obj.transform.hashCode()
            hash = HASH_PRIME * hash + obj.erased.isEmpty.hashCode()
        }
        return hash
    }

    /**
     * One phrase, assembled and read back. A fresh seed every call — never the same result twice.
     * [maths] is the panel's *Maths* switch (docs/HANDWRITING-MATHS.md).
     */
    suspend fun write(
        text: String,
        style: WriteStyle,
        profile: Profile,
        preference: SourcePreference,
        maths: Boolean = false,
        onWord: (WordOutcome) -> Unit = {},
    ): WrittenPhrase = withContext(Dispatchers.Default) {
        val request = WriteRequest(text, style, Random.nextLong(), preference, profile.accountName, maths)
        // P1 said no prime: hand the synthesizer an explicit NONE, or it would compute D15's own.
        val primed = if (profile.prime == PrimeMode.NONE) Primed.NONE else cachedPrime(profile, preference)
        HandwritingSynthesizer(ReliabilityGate(reader, profile.calibration), profile.neural, profile.route)
            .write(request, profile.index, onWord, primed)
    }

    /** These strokes, as ordinary ink blobs in [pen]. */
    suspend fun encode(
        strokes: List<dev.inkstone.core.model.PenTrajectory>,
        pen: PenStyle,
        inputModel: InkInputModel,
        profile: Profile,
    ): List<StrokeGeometry> = ink.encode(strokes, pen, inputModel, profile.unitLengthCm)

    /** The pen the user has picked, as `:core` names one. */
    fun penStyleOf(config: dev.inkstone.android.ink.PenConfig): PenStyle = ink.penStyleOf(config)

    /** The pen [id] was written with, read off its blob — what *Write again* keeps. */
    suspend fun penOf(page: FoldedPage, id: ObjectId): PenStyle? = strokes.read(page, listOf(id))[id]?.pen

    /** A new phrase's group (D2); each stroke's backend and model come from its own word. */
    fun newGroup(): String = ids.newUuid7()

    /** The learner's revision: a panel reloads [profile] when it moves. */
    val revision: kotlinx.coroutines.flow.StateFlow<Long> get() = learner.revision

    /** Today, for D15's *recent writing first*. */
    fun today(): Long = clock.nowEpochMillis() / MILLIS_PER_DAY

    private companion object {
        const val TAG = "HandwritingWriter"
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        const val HASH_PRIME = 31
    }
}
