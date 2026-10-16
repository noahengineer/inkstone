package dev.inkstone.core.handwriting.neural

import dev.inkstone.core.storage.sha256Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The network's shape, read off the file's header. */
public data class NetworkShape(
    val layers: Int,
    val cells: Int,
    val window: Int,
    val mixtures: Int,
    val styleDim: Int,
    val alphabetSize: Int,
    val kappaScale: Float,
) {
    internal val gates: Int get() = GATES * cells
    internal val outputs: Int get() = 1 + MIXTURE_PARAMS * mixtures

    internal companion object {
        const val GATES = 4
        const val MIXTURE_PARAMS = 6
        const val WINDOW_PARAMS = 3
    }
}

/** One dataset the model was trained on, and the licences it and its sources carry (D13). */
public data class TrainingSet(val name: String, val licence: String, val sources: List<Source>) {
    public data class Source(val id: String, val licence: String, val attribution: String)

    /** Every licence this set brings with it. */
    public val licences: List<String> get() = listOf(licence) + sources.map { it.licence }
}

/**
 * Everything a `.ihwm` header says besides its tensors (docs/HANDWRITING-SYNTHESIS.md §4.4).
 *
 * @property normMean the training offsets' mean, `(dx, dy)`: the network reads and writes offsets as
 *   `(offset − mean) / std`.
 * @property pointsPerChar how densely the training corpus was sampled: how many steps a character
 *   takes, and so what real ink is resampled to before the network reads it.
 */
public data class ModelHeader(
    val shape: NetworkShape,
    val alphabet: String,
    val normMean: FloatArray,
    val normStd: FloatArray,
    val pointsPerChar: Float,
    val datasets: List<TrainingSet>,
    val git: String?,
)

/** How strict to be about the licences a model was trained under (D13). */
public enum class LicencePolicy {
    /** A release build: anything not known to allow commercial use is refused. */
    RELEASE,

    /** A debug build: such a model loads, flagged, so research weights can be looked at. Never shipped. */
    DEVELOPMENT,
}

/** What reading a weights file came to. */
public sealed interface ModelLoad {
    public class Loaded(public val weights: WeightsFile) : ModelLoad

    public data class Refused(val reason: Reason, val detail: String) : ModelLoad

    public enum class Reason {
        /** No file at all. */
        ABSENT,

        /** It fails its own checksum, or does not parse: never half-used. */
        CORRUPT,

        /** A format version or shape this build cannot run. */
        UNSUPPORTED,

        /** D13: its provenance names a licence that does not allow commercial use, and this is a release. */
        NOT_COMMERCIAL,
    }
}

/**
 * ⭐ A parsed, checked `.ihwm` — docs/HANDWRITING-SYNTHESIS.md §4.4, written by
 * `tools/handwriting/export.py`.
 *
 * ```
 * "IHWM"  u16 version  u16 0  u32 header-length  header (UTF-8 JSON)  zero padding to 4 bytes
 * tensor data at the header's offsets (from the start of the data), f16 weights and f32 biases
 * SHA-256 of every byte before it
 * ```
 *
 * [read] never throws and never half-loads. A file that fails its trailer, does not parse, or has a
 * shape this build cannot run is [ModelLoad.Reason.CORRUPT] or [ModelLoad.Reason.UNSUPPORTED]. Under
 * [LicencePolicy.RELEASE], a header naming any licence outside [COMMERCIAL_LICENCES] is
 * [ModelLoad.Reason.NOT_COMMERCIAL] (D13). In every refused case the neural backend is off and glyph
 * assembly carries on.
 *
 * @property sha256 of the whole file: the model's identity. [modelId], its first eight hex digits,
 *   is what `hw=…:n:<model>` records on every stroke it writes (D2).
 * @property nonCommercial licences outside the allow-list, which only [LicencePolicy.DEVELOPMENT]
 *   lets through. Empty for anything a release would load.
 */
public class WeightsFile internal constructor(
    public val header: ModelHeader,
    private val tensors: Map<String, Pair<IntArray, FloatArray>>,
    public val sha256: String,
    public val nonCommercial: List<String>,
) {
    public val modelId: String get() = sha256.take(MODEL_ID_DIGITS)

    /** The tensor [name] as floats, in the file's (PyTorch's) layout, and its shape. */
    internal fun tensor(name: String): Pair<IntArray, FloatArray> = tensors[name] ?: error("validated at read: $name")

    public companion object {
        /**
         * Licences whose terms allow a model trained on the data to ship in a product that is sold —
         * the same allow-list as `tools/handwriting/fontskel.py` and `prepare.py`. An allow-list, so
         * an unknown licence is refused rather than guessed at.
         */
        public val COMMERCIAL_LICENCES: Set<String> = setOf(
            "OFL-1.1",
            "LicenseRef-Hershey",
            "CC0-1.0",
            "MIT",
            "Apache-2.0",
            "CC-BY-4.0",
            "LicenseRef-Inkstone-Synthetic",
            "LicenseRef-User-Own",
        )

        private const val MAGIC = "IHWM"
        private const val VERSION = 1
        private const val VERSION_AT = 4
        private const val HEADER_LENGTH_AT = 8
        private const val PREFIX = 12
        private const val F16_BYTES = 2
        private const val F32_BYTES = 4
        private const val TRAILER = 32
        private const val MODEL_ID_DIGITS = 8
        private const val ALIGN = 4

        public fun read(bytes: ByteArray?, policy: LicencePolicy): ModelLoad {
            if (bytes == null) return ModelLoad.Refused(ModelLoad.Reason.ABSENT, "no model file")
            return try {
                parse(bytes, policy)
            } catch (e: IllegalArgumentException) {
                ModelLoad.Refused(ModelLoad.Reason.CORRUPT, e.message ?: "malformed")
            } catch (e: NoSuchElementException) {
                ModelLoad.Refused(ModelLoad.Reason.CORRUPT, "header lacks ${e.message}")
            } catch (e: kotlinx.serialization.SerializationException) {
                ModelLoad.Refused(ModelLoad.Reason.CORRUPT, "header: ${e.message}")
            }
        }

        @Suppress("ReturnCount")
        private fun parse(bytes: ByteArray, policy: LicencePolicy): ModelLoad {
            require(bytes.size > PREFIX + TRAILER) { "too short to be a model (${bytes.size} bytes)" }
            require(bytes.decodeToString(0, MAGIC.length) == MAGIC) { "not an IHWM file" }
            val body = bytes.copyOfRange(0, bytes.size - TRAILER)
            val trailer = bytes.copyOfRange(bytes.size - TRAILER, bytes.size)
            require(body.sha256Hex() == hex(trailer)) { "fails its own checksum" }
            val version = u16(bytes, VERSION_AT)
            if (version != VERSION) return ModelLoad.Refused(ModelLoad.Reason.UNSUPPORTED, "format version $version")
            val headerLength = u32(bytes, HEADER_LENGTH_AT)
            require(headerLength in 1..body.size - PREFIX) { "a header of $headerLength bytes does not fit" }
            val json = Json.parseToJsonElement(bytes.decodeToString(PREFIX, PREFIX + headerLength)).jsonObject
            val header = headerOf(json)
            val outside = header.datasets.flatMap { it.licences }.filter { it !in COMMERCIAL_LICENCES }.distinct()
            if (header.datasets.isEmpty()) {
                return ModelLoad.Refused(ModelLoad.Reason.NOT_COMMERCIAL, "the header lists no training data")
            }
            if (outside.isNotEmpty() && policy == LicencePolicy.RELEASE) {
                return ModelLoad.Refused(ModelLoad.Reason.NOT_COMMERCIAL, "trained on ${outside.joinToString()}")
            }
            var dataStart = PREFIX + headerLength
            dataStart += (ALIGN - dataStart % ALIGN) % ALIGN
            val tensors = LinkedHashMap<String, Pair<IntArray, FloatArray>>()
            for (entry in json.getValue("tensors").jsonArray) {
                val t = entry.jsonObject
                val name = t.getValue("name").jsonPrimitive.content
                val shape = t.getValue("shape").jsonArray.map { it.jsonPrimitive.int }.toIntArray()
                val count = shape.fold(1) { a, b -> a * b }
                val offset = dataStart + t.getValue("offset").jsonPrimitive.int
                val dtype = t.getValue("dtype").jsonPrimitive.content
                val length = t.getValue("length").jsonPrimitive.int
                val width = when (dtype) {
                    "f16" -> F16_BYTES
                    "f32" -> F32_BYTES
                    else -> return ModelLoad.Refused(ModelLoad.Reason.UNSUPPORTED, "$name is $dtype")
                }
                require(count >= 0 && length == count * width) { "$name: $length bytes for $count values" }
                require(offset >= dataStart && offset + length <= body.size) { "$name runs past the end" }
                tensors[name] = shape to if (width == F16_BYTES) {
                    FloatArray(count) { halfToFloat(u16(bytes, offset + F16_BYTES * it)) }
                } else {
                    FloatArray(count) { Float.fromBits(u32(bytes, offset + F32_BYTES * it)) }
                }
            }
            val problem = shapeProblem(header.shape, tensors)
            if (problem != null) return ModelLoad.Refused(ModelLoad.Reason.UNSUPPORTED, problem)
            return ModelLoad.Loaded(WeightsFile(header, tensors, bytes.sha256Hex(), outside))
        }

        private fun headerOf(json: JsonObject): ModelHeader {
            val arch = json.getValue("arch").jsonObject
            fun int(key: String) = arch.getValue(key).jsonPrimitive.int
            val shape = NetworkShape(
                layers = int("layers"),
                cells = int("cells"),
                window = int("window"),
                mixtures = int("mixtures"),
                styleDim = int("style_dim"),
                alphabetSize = int("alphabet_size"),
                kappaScale = arch.getValue("kappa_scale").jsonPrimitive.float,
            )
            val norm = json.getValue("normalisation").jsonObject
            val provenance = json.getValue("provenance").jsonObject
            val datasets = (provenance["datasets"] as? JsonArray).orEmpty().map { d ->
                val o = d.jsonObject
                TrainingSet(
                    name = o.getValue("name").jsonPrimitive.content,
                    licence = o["licence"]?.jsonPrimitive?.content.orEmpty(),
                    sources = (o["sources"] as? JsonArray).orEmpty().map { s ->
                        val so = s.jsonObject
                        TrainingSet.Source(
                            id = so["id"]?.jsonPrimitive?.content.orEmpty(),
                            licence = so["licence"]?.jsonPrimitive?.content.orEmpty(),
                            attribution = so["attribution"]?.jsonPrimitive?.content.orEmpty(),
                        )
                    },
                )
            }
            val alphabet = json.getValue("alphabet").jsonPrimitive.content
            require(alphabet.length == shape.alphabetSize) {
                "alphabet has ${alphabet.length}, arch says ${shape.alphabetSize}"
            }
            return ModelHeader(
                shape = shape,
                alphabet = alphabet,
                normMean = norm.getValue("mean").jsonArray.map { it.jsonPrimitive.float }.toFloatArray(),
                normStd = norm.getValue("std").jsonArray.map { it.jsonPrimitive.float }.toFloatArray(),
                pointsPerChar = json.getValue("points_per_char").jsonPrimitive.float,
                datasets = datasets,
                git = provenance["git"]?.jsonPrimitive?.content,
            )
        }

        /** Every tensor the network needs, at the size its header says — or what is wrong. */
        @Suppress("ReturnCount")
        private fun shapeProblem(s: NetworkShape, t: Map<String, Pair<IntArray, FloatArray>>): String? {
            val g = s.gates
            fun has(name: String, vararg dims: Int): Boolean = t[name]?.first?.contentEquals(dims) == true
            for (l in 0 until s.layers) {
                val input = PEN + s.alphabetSize + s.styleDim + if (l == 0) 0 else s.cells
                if (!has("l$l.w_ih", g, input)) return "l$l.w_ih is not $g×$input"
                if (!has("l$l.w_hh", g, s.cells)) return "l$l.w_hh is not $g×${s.cells}"
                if (!has("l$l.b", g)) return "l$l.b is not $g"
            }
            val win = NetworkShape.WINDOW_PARAMS * s.window
            if (!has("window.w", win, s.cells) || !has("window.b", win)) return "the window layer is the wrong size"
            if (!has("out.w", s.outputs, s.layers * s.cells) || !has("out.b", s.outputs)) {
                return "the output layer is the wrong size"
            }
            if (!has("style.default", s.styleDim)) return "style.default is not ${s.styleDim}"
            return null
        }

        private const val PEN = 3

        // Little-endian, and IEEE 754's bit layout below: the literals are the format, not tuning.
        @Suppress("MagicNumber")
        private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and BYTE) or ((b[at + 1].toInt() and BYTE) shl 8)

        @Suppress("MagicNumber")
        private fun u32(b: ByteArray, at: Int): Int = u16(b, at) or (u16(b, at + 2) shl 16)

        @Suppress("MagicNumber")
        private fun hex(b: ByteArray): String = b.joinToString("") {
            (it.toInt() and BYTE).toString(16).padStart(2, '0')
        }

        private const val BYTE = 0xFF

        /** IEEE 754 half → float, subnormals and infinities included. */
        @Suppress("MagicNumber")
        internal fun halfToFloat(h: Int): Float {
            val sign = (h ushr 15) and 1
            val exponent = (h ushr 10) and 0x1F
            val mantissa = h and 0x3FF
            val magnitude = when (exponent) {
                0 -> mantissa * SUBNORMAL_UNIT
                0x1F -> if (mantissa == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> Float.fromBits(((exponent - HALF_BIAS + FLOAT_BIAS) shl 23) or (mantissa shl 13))
            }
            return if (sign == 1) -magnitude else magnitude
        }

        private const val HALF_BIAS = 15
        private const val FLOAT_BIAS = 127
        private const val SUBNORMAL_UNIT = 5.9604645E-8f // 2^-24
    }
}
