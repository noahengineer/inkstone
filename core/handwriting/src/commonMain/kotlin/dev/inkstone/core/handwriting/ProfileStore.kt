package dev.inkstone.core.handwriting

import dev.inkstone.core.handwriting.quality.BackendChoice
import dev.inkstone.core.handwriting.quality.PersonalState
import dev.inkstone.core.handwriting.quality.PolicyState
import dev.inkstone.core.handwriting.quality.QualityJson
import dev.inkstone.core.handwriting.quality.QualityMeasure
import dev.inkstone.core.model.FileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A profile's settings and harvest progress — `profile.json` (docs/HANDWRITING-SYNTHESIS.md §4.2).
 *
 * @property learning whether new writing is learned from at all — *My handwriting*'s pause.
 * @property learnFromOlderNotes whether ink written before the feature was enabled, which the pen
 *   ledger cannot vouch for, may be learned from (D8). On by default, because this user said every
 *   note is theirs (Q3), and a choice on *My handwriting* either way. Asking anyone else once at
 *   first enable is a §9 commercial-release item and is **not built**.
 * @property language the recogniser's language for this profile (D14).
 * @property harvestedPages `"<noteId>/<pageId>"` → the page's ink digest when it was last
 *   harvested. A page whose digest has not moved has nothing new to learn.
 * @property harvestedNotes noteId → the note's `localRev` when every page of it was last
 *   harvested, so a pass can skip an unchanged note without opening it — which is what lets a
 *   time-limited pass make progress through a library instead of starting from the top each night.
 * @property recutTried the samples `Harvester.recut` has already tried to cut into letters. Phase 3
 *   cuts joined runs as well as single letters, so a word learned before it, or one the quick start
 *   recorded without a recogniser, is cut once more by a learning pass, and only once.
 * @property gate §8.2's calibration, measured on this hand; null until the first personalisation pass.
 * @property quality §8.3's fidelity, one point per personalisation pass — the *"improves with use"* curve.
 * @property policy §8.4's measured switch: which backend is primary, and which characters are routed.
 * @property backend §8.4's user override: *Auto*, *My letters* or *Model*.
 * @property personal which of §8.1's rungs are in force, and against which base model.
 * @property extras keys a later build wrote, kept verbatim (SPEC §5.8).
 */
public data class ProfileState(
    val learning: Boolean = true,
    val learnFromOlderNotes: Boolean = true,
    val language: String = DEFAULT_LANGUAGE,
    val harvestedPages: Map<String, String> = emptyMap(),
    val harvestedNotes: Map<String, Long> = emptyMap(),
    val recutTried: Set<String> = emptySet(),
    val gate: GateCalibration? = null,
    val quality: List<QualityMeasure> = emptyList(),
    val policy: PolicyState = PolicyState.INITIAL,
    val backend: BackendChoice = BackendChoice.AUTO,
    val personal: PersonalState = PersonalState(),
    val extras: Map<String, JsonElement> = emptyMap(),
) {
    public companion object {
        public const val DEFAULT_LANGUAGE: String = "en-US"

        public fun pageKey(noteId: String, pageId: String): String = "$noteId/$pageId"

        /** The note a [pageKey] belongs to. */
        public fun noteOfPageKey(pageKey: String): String = pageKey.substringBefore('/')
    }
}

/**
 * ⭐ The user's own word on what they wrote — `labels.json`, **the one file in a profile that
 * cannot be re-derived** (docs/HANDWRITING-SYNTHESIS.md §4.2). Everything else is rebuilt by
 * harvesting the notes again; this is what the user typed. CLAUDE.md rule 1 applies to it in full.
 *
 * Keyed by a sample's `sourceKey` — the fingerprint of the strokes — rather than its id, so a
 * correction survives the sample being harvested again.
 *
 * @property corrected what the strokes really say.
 * @property confirmed strokes the user said were read correctly.
 * @property rejected strokes the user removed: never learned from again, even though they are still
 *   in the note and would otherwise be harvested on the next pass.
 * @property excludedNotes notes the user said not to learn from (a page of someone else's writing).
 */
public data class LabelBook(
    val corrected: Map<String, String> = emptyMap(),
    val confirmed: Set<String> = emptySet(),
    val rejected: Set<String> = emptySet(),
    val excludedNotes: Set<String> = emptySet(),
    val extras: Map<String, JsonElement> = emptyMap(),
) {
    /** What [sample] says, once the user's corrections are applied. */
    public fun labelOf(sample: WordSample): String = corrected[sample.sourceKey] ?: sample.label

    /** Where that label came from, on the same terms. */
    public fun sourceOf(sample: WordSample): LabelSource = when (sample.sourceKey) {
        in corrected -> LabelSource.CORRECTED
        in confirmed -> LabelSource.CONFIRMED
        else -> sample.labelSource
    }
}

/** Every sample a profile holds, and how many shards could not be read by this build. */
public class SampleSet(public val samples: List<WordSample>, public val unreadableShards: Int)

/**
 * ⭐ One profile's files — docs/HANDWRITING-SYNTHESIS.md §4.2.
 *
 * ## Three promises, each the reason for some code below
 *
 * **Every write is atomic** (rule 10): each file is replaced whole through `writeBytesAtomically`,
 * so nothing that reads a profile — the review screen, a phase-3 drop — can see half of one.
 *
 * **Nothing is deleted that has not been in the trash** (rule 1): a displaced `labels.json`, a
 * withdrawn sample, a profile the user wiped — each is moved or written to `trash/` first, and only
 * [purgeTrash] removes anything, after thirty days.
 *
 * **A file this build cannot read is never rewritten** (SPEC §5.8): a shard written by a later
 * version is left exactly as it is — not appended to, not withdrawn from, not compacted — and new
 * samples go to a new shard.
 *
 * Not thread-safe. `:app`'s `HandwritingLearner` is its only owner and serialises every call.
 */
public class ProfileStore(private val fs: FileSystem, public val key: ProfileKey) {

    public suspend fun state(): ProfileState {
        val json = readJson(HandwritingLayout.state(key)) ?: return ProfileState()
        return ProfileState(
            learning = json.boolean(KEY_LEARNING) ?: true,
            learnFromOlderNotes = json.boolean(KEY_OLDER_NOTES) ?: true,
            language = json.string(KEY_LANGUAGE) ?: ProfileState.DEFAULT_LANGUAGE,
            harvestedPages = json.stringMap(KEY_HARVESTED),
            harvestedNotes = json.stringMap(KEY_HARVESTED_NOTES).mapNotNull { (k, v) ->
                v.toLongOrNull()?.let { k to it }
            }.toMap(),
            recutTried = json.stringSet(KEY_RECUT_TRIED),
            gate = GateCalibration.fromJson(json[KEY_GATE]),
            quality = QualityJson.measuresOf(json[KEY_QUALITY]),
            policy = QualityJson.policyOf(json[KEY_POLICY]),
            backend = QualityJson.choiceOf(json[KEY_BACKEND]),
            personal = QualityJson.personalOf(json[KEY_PERSONAL]),
            extras = json.filterKeys { it !in STATE_KEYS },
        )
    }

    public suspend fun saveState(state: ProfileState) {
        val json = JsonObject(
            state.extras + mapOf(
                KEY_VERSION to JsonPrimitive(VERSION),
                KEY_LEARNING to JsonPrimitive(state.learning),
                KEY_OLDER_NOTES to JsonPrimitive(state.learnFromOlderNotes),
                KEY_LANGUAGE to JsonPrimitive(state.language),
                KEY_HARVESTED to JsonObject(state.harvestedPages.mapValues { JsonPrimitive(it.value) }),
                KEY_HARVESTED_NOTES to JsonObject(
                    state.harvestedNotes.mapValues {
                        JsonPrimitive(it.value.toString())
                    },
                ),
                KEY_RECUT_TRIED to JsonArray(state.recutTried.sorted().map(::JsonPrimitive)),
                KEY_QUALITY to QualityJson.measures(state.quality),
                KEY_POLICY to QualityJson.policy(state.policy),
                KEY_BACKEND to JsonPrimitive(state.backend.name),
                KEY_PERSONAL to QualityJson.personal(state.personal),
            ) + listOfNotNull(state.gate?.let { KEY_GATE to it.toJson() }),
        )
        writeJson(HandwritingLayout.state(key), json)
    }

    public suspend fun labels(): LabelBook {
        val json = readJson(HandwritingLayout.labels(key)) ?: return LabelBook()
        return LabelBook(
            corrected = json.stringMap(KEY_CORRECTED),
            confirmed = json.stringSet(KEY_CONFIRMED),
            rejected = json.stringSet(KEY_REJECTED),
            excludedNotes = json.stringSet(KEY_EXCLUDED_NOTES),
            extras = json.filterKeys { it !in LABEL_KEYS },
        )
    }

    /**
     * Replace `labels.json`, **moving the previous version to the trash first** — the file is
     * user-authored, and a bug that wrote a book missing an entry must not be able to lose it.
     */
    public suspend fun saveLabels(book: LabelBook, nowEpochMillis: Long) {
        val path = HandwritingLayout.labels(key)
        if (fs.exists(path)) {
            fs.createDirectories(HandwritingLayout.trashDir(key))
            fs.move(path, trashPath(nowEpochMillis, "labels.json"))
        }
        val json = JsonObject(
            book.extras + mapOf(
                KEY_VERSION to JsonPrimitive(VERSION),
                KEY_CORRECTED to JsonObject(book.corrected.mapValues { JsonPrimitive(it.value) }),
                KEY_CONFIRMED to JsonArray(book.confirmed.sorted().map(::JsonPrimitive)),
                KEY_REJECTED to JsonArray(book.rejected.sorted().map(::JsonPrimitive)),
                KEY_EXCLUDED_NOTES to JsonArray(book.excludedNotes.sorted().map(::JsonPrimitive)),
            ),
        )
        writeJson(path, json)
    }

    public suspend fun samples(): SampleSet {
        val all = ArrayList<WordSample>()
        var unreadable = 0
        for (shard in shards()) {
            val decoded = runCatching { fs.readBytes(shardPath(shard)) }.getOrNull()?.let(WordSampleCodec::decode)
            if (decoded == null) unreadable++ else all += decoded
        }
        return SampleSet(all, unreadable)
    }

    /**
     * Add samples: into the newest shard while it has room and this build can read it, then into
     * new ones. Each shard is rewritten whole, atomically.
     */
    public suspend fun add(samples: List<WordSample>) {
        if (samples.isEmpty()) return
        fs.createDirectories(HandwritingLayout.wordsDir(key))
        val existing = shards()
        var pending = samples
        val last = existing.lastOrNull()
        val lastContents = last?.let { readShard(it) }
        if (last != null && lastContents != null && lastContents.size < SHARD_SIZE) {
            val room = SHARD_SIZE - lastContents.size
            fs.writeBytesAtomically(shardPath(last), WordSampleCodec.encode(lastContents + pending.take(room)))
            pending = pending.drop(room)
        }
        var next = (existing.maxOrNull() ?: -1) + 1
        while (pending.isNotEmpty()) {
            fs.writeBytesAtomically(shardPath(next), WordSampleCodec.encode(pending.take(SHARD_SIZE)))
            pending = pending.drop(SHARD_SIZE)
            next++
        }
    }

    /**
     * Replace samples in place, by id — derived data only (a re-cut's glyphs), never a label. Each
     * readable shard holding one is rewritten whole, atomically; a shard this build cannot read is
     * left alone, as everywhere else here.
     *
     * @return how many were replaced.
     */
    public suspend fun replace(samples: List<WordSample>): Int {
        if (samples.isEmpty()) return 0
        val byId = samples.associateBy { it.id }
        var replaced = 0
        for (shard in shards()) {
            val contents = readShard(shard)
            if (contents == null || contents.none { it.id in byId }) continue
            val rewritten = contents.map { old -> byId[old.id]?.also { replaced++ } ?: old }
            fs.writeBytesAtomically(shardPath(shard), WordSampleCodec.encode(rewritten))
        }
        return replaced
    }

    /**
     * Remove samples by id — **written to the trash first**, then the shards rewritten without them.
     * Shards this build cannot read are not touched, so a sample in one cannot be withdrawn here.
     *
     * @return how many were withdrawn.
     */
    public suspend fun withdraw(ids: Set<String>, nowEpochMillis: Long): Int {
        if (ids.isEmpty()) return 0
        // Each readable shard holding one of [ids], split into what goes and what stays.
        val affected = shards().mapNotNull { shard ->
            readShard(shard)?.partition { it.id in ids }?.takeIf { it.first.isNotEmpty() }?.let { shard to it }
        }
        val withdrawn = affected.flatMap { it.second.first }
        val rewrites = affected.map { (shard, split) -> shard to split.second }
        if (withdrawn.isEmpty()) return 0
        fs.createDirectories(HandwritingLayout.trashDir(key))
        fs.writeBytesAtomically(trashPath(nowEpochMillis, "withdrawn.hws"), WordSampleCodec.encode(withdrawn))
        for ((shard, kept) in rewrites) {
            fs.writeBytesAtomically(shardPath(shard), WordSampleCodec.encode(kept))
        }
        return withdrawn.size
    }

    /**
     * *My handwriting*'s "delete everything": the whole profile moves to the trash, where it stays
     * recoverable for thirty days like a note, and the next pass starts from nothing.
     */
    public suspend fun trashEverything(nowEpochMillis: Long) {
        val dir = HandwritingLayout.profileDir(key)
        if (!fs.exists(dir)) return
        val holding = "${HandwritingLayout.ROOT}/.trash-$nowEpochMillis-${key.value}"
        // Out of the profile first, so the trash is not inside what is being moved.
        fs.move(dir, holding)
        // ⚠️ The trash the profile already had goes back to being the trash. Left inside `holding`, it
        // would be nested in the new entry, where purgeTrash, which reads only the top level, keeps
        // everything older for thirty days from *this* delete instead of from its own. The screen
        // promises thirty days. Found on the emulator, after two deletes.
        val oldTrash = "$holding/${HandwritingLayout.TRASH_DIR_NAME}"
        fs.createDirectories(dir)
        if (fs.exists(oldTrash)) fs.move(oldTrash, HandwritingLayout.trashDir(key))
        fs.createDirectories(HandwritingLayout.trashDir(key))
        fs.move(holding, trashPath(nowEpochMillis, "profile"))
    }

    /** Removes trash entries older than [HandwritingLayout.TRASH_RETENTION_MILLIS]. The only delete here. */
    public suspend fun purgeTrash(nowEpochMillis: Long): Int {
        val dir = HandwritingLayout.trashDir(key)
        var purged = 0
        for (name in fs.list(dir)) {
            val stamp = name.substringBefore('-').toLongOrNull() ?: continue
            if (nowEpochMillis - stamp > HandwritingLayout.TRASH_RETENTION_MILLIS) {
                fs.delete("$dir/$name")
                purged++
            }
        }
        return purged
    }

    private suspend fun shards(): List<Int> = fs.list(HandwritingLayout.wordsDir(key))
        .mapNotNull { name ->
            name.removeSuffix(SHARD_SUFFIX).takeIf { name.endsWith(SHARD_SUFFIX) }?.toIntOrNull()
        }
        .sorted()

    private suspend fun readShard(shard: Int): List<WordSample>? =
        runCatching { fs.readBytes(shardPath(shard)) }.getOrNull()?.let(WordSampleCodec::decode)

    private fun shardPath(shard: Int): String =
        "${HandwritingLayout.wordsDir(key)}/${shard.toString().padStart(SHARD_DIGITS, '0')}$SHARD_SUFFIX"

    private fun trashPath(nowEpochMillis: Long, name: String): String =
        "${HandwritingLayout.trashDir(key)}/$nowEpochMillis-$name"

    private suspend fun readJson(path: String): JsonObject? {
        if (!fs.exists(path)) return null
        val text = runCatching { fs.readBytes(path).decodeToString() }.getOrNull() ?: return null
        return runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull()
    }

    private suspend fun writeJson(path: String, json: JsonObject) {
        fs.createDirectories(HandwritingLayout.profileDir(key))
        fs.writeBytesAtomically(path, JSON.encodeToString(JsonObject.serializer(), json).encodeToByteArray())
    }

    private fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.stringMap(name: String): Map<String, String> =
        (this[name] as? JsonObject)?.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
        }?.toMap()
            ?: emptyMap()

    private fun JsonObject.stringSet(name: String): Set<String> =
        runCatching { this[name]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() }.getOrNull()
            ?: emptySet()

    private companion object {
        val JSON = Json { prettyPrint = false }
        const val VERSION = 1
        const val SHARD_SIZE = 256
        const val SHARD_DIGITS = 6
        const val SHARD_SUFFIX = ".hws"

        const val KEY_VERSION = "v"
        const val KEY_LEARNING = "learning"
        const val KEY_OLDER_NOTES = "learnFromOlderNotes"
        const val KEY_LANGUAGE = "language"
        const val KEY_HARVESTED = "harvestedPages"
        const val KEY_HARVESTED_NOTES = "harvestedNotes"
        const val KEY_RECUT_TRIED = "recutTried"
        const val KEY_GATE = "gate"
        const val KEY_QUALITY = "quality"
        const val KEY_POLICY = "policy"
        const val KEY_BACKEND = "backend"
        const val KEY_PERSONAL = "personal"
        val STATE_KEYS = setOf(
            KEY_VERSION,
            KEY_LEARNING,
            KEY_OLDER_NOTES,
            KEY_LANGUAGE,
            KEY_HARVESTED,
            KEY_HARVESTED_NOTES,
            KEY_RECUT_TRIED,
            KEY_GATE,
            KEY_QUALITY,
            KEY_POLICY,
            KEY_BACKEND,
            KEY_PERSONAL,
        )

        const val KEY_CORRECTED = "corrected"
        const val KEY_CONFIRMED = "confirmed"
        const val KEY_REJECTED = "rejected"
        const val KEY_EXCLUDED_NOTES = "excludedNotes"
        val LABEL_KEYS = setOf(KEY_VERSION, KEY_CORRECTED, KEY_CONFIRMED, KEY_REJECTED, KEY_EXCLUDED_NOTES)
    }
}
