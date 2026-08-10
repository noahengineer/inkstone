package dev.inkstone.core.storage

import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageText
import dev.inkstone.core.model.RecognisedLine
import dev.inkstone.core.model.RecognisedWord
import dev.inkstone.core.model.StrokeObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SPEC §5.3's `text/<pageId>.json`, and SPEC §5.5's reason it can be merged at all.
 *
 * ```json
 * { "pageId": "…", "inkDigest": "…", "recogniser": "mlkit-digital-ink/19.0.0/en-US",
 *   "lines": [ { "text": "…", "box": [l,t,r,b],
 *                "words": [ { "text": "…", "box": [l,t,r,b] } ] } ] }
 * ```
 *
 * Boxes are four floats in page space (SPEC §4.4: y **down**), written as an array rather
 * than four named members because there are two of them per word and a page can hold a few
 * hundred words. The file is derived data — it can always be regenerated from the ink — so
 * compactness costs nothing that matters.
 */
public object PageTextCodec {

    public fun encode(text: PageText): ByteArray = buildJsonObject {
        put(F_PAGE_ID, JsonPrimitive(text.pageId.value))
        put(F_DIGEST, JsonPrimitive(text.inkDigest))
        put(F_RECOGNISER, JsonPrimitive(text.recogniser))
        put(
            F_LINES,
            buildJsonArray {
                for (line in text.lines) {
                    add(
                        buildJsonObject {
                            put(F_TEXT, JsonPrimitive(line.text))
                            put(F_BOX, box(line.bounds))
                            put(
                                F_WORDS,
                                buildJsonArray {
                                    for (word in line.words) {
                                        add(
                                            buildJsonObject {
                                                put(F_TEXT, JsonPrimitive(word.text))
                                                put(F_BOX, box(word.bounds))
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
    }.let { OpCodec.json.encodeToString(JsonObject.serializer(), it).encodeToByteArray() }

    /**
     * @return null when [bytes] are not this format. Never throws: the input is an entry a
     *   peer wrote, possibly with a newer build, and an exception out of a bundle read is a
     *   note that will not open (SPEC §5.8).
     */
    public fun decode(bytes: ByteArray): PageText? = runCatching {
        val root = OpCodec.json.parseToJsonElement(bytes.decodeToString()).jsonObject
        PageText(
            pageId = PageId(root.getValue(F_PAGE_ID).jsonPrimitive.content),
            inkDigest = root.getValue(F_DIGEST).jsonPrimitive.content,
            recogniser = root[F_RECOGNISER]?.jsonPrimitive?.content.orEmpty(),
            lines = root[F_LINES]?.jsonArray.orEmpty().map { element ->
                val line = element.jsonObject
                RecognisedLine(
                    text = line[F_TEXT]?.jsonPrimitive?.content.orEmpty(),
                    bounds = rect(line.getValue(F_BOX).jsonArray),
                    words = line[F_WORDS]?.jsonArray.orEmpty().map { wordElement ->
                        val word = wordElement.jsonObject
                        RecognisedWord(
                            text = word[F_TEXT]?.jsonPrimitive?.content.orEmpty(),
                            bounds = rect(word.getValue(F_BOX).jsonArray),
                        )
                    },
                )
            },
        )
    }.getOrNull()

    /**
     * ⭐ **The digest that makes recognised text orderable** — SPEC §5.5, and the prerequisite
     * M6 recorded rather than solved.
     *
     * Over the ink a recogniser would be given, in the order it would be given it: for every
     * live stroke in fold order, its id, its geometry bytes, its transform, its z-key and its
     * erased region. Two devices that have folded the same ops compute the same string, so
     * they agree on which of two `text/<pageId>.json` files is still about this page without
     * having to agree on which of them is *newer*.
     *
     * What is **not** in it is as deliberate as what is:
     *
     * - **Style is excluded.** Recolouring a word does not change what it says, and including
     *   it would throw away a page's search text every time someone changed a pen.
     * - **Non-stroke objects are excluded.** Images and shapes are not handed to the
     *   recogniser, so they cannot invalidate its output.
     * - **z-order is included**, because SPEC §8.1 says out-of-order strokes degrade the
     *   result, which makes stroke order an input rather than a presentation detail.
     *
     * ## Two implementation notes that are not incidental
     *
     * **Every field is hashed to fixed width before being joined, and no separator is used.**
     * A separator only works if it cannot occur inside a field, and one of these fields is a
     * `FractionalKey` read from a *peer's* bundle — an arbitrary string this build never
     * validated. Fixed-width parts make a field boundary unforgeable instead of merely
     * unlikely, which matters because two pages colliding here means one of them silently
     * adopts the other's text.
     *
     * **The geometry is hashed on its own** rather than concatenated into one buffer: a dense
     * page is megabytes of blob and this runs on a tablet SPEC §10 sizes at 4 GB.
     */
    public fun inkDigestOf(page: FoldedPage): String {
        val parts = StringBuilder(page.pageId.value.hashed())
        for (obj in page.objects) {
            if (obj !is StrokeObject) continue
            parts.append(obj.id.value.hashed())
            parts.append(obj.geometry.bytes.sha256Hex())
            parts.append(obj.z.value.hashed())
            with(obj.transform) { parts.append("$m00,$m01,$m02,$m10,$m11,$m12".hashed()) }
            parts.append(
                obj.erased.capsules.joinToString(";") { "${it.fromX},${it.fromY},${it.toX},${it.toY},${it.radius}" }
                    .hashed(),
            )
        }
        return parts.toString().encodeToByteArray().sha256Hex()
    }

    private fun String.hashed(): String = encodeToByteArray().sha256Hex()

    // -----------------------------------------------------------------------

    private fun box(rect: PageRect): JsonArray = buildJsonArray {
        add(JsonPrimitive(rect.left))
        add(JsonPrimitive(rect.top))
        add(JsonPrimitive(rect.right))
        add(JsonPrimitive(rect.bottom))
    }

    private fun rect(array: JsonArray): PageRect = PageRect(
        left = array[0].jsonPrimitive.float,
        top = array[1].jsonPrimitive.float,
        right = array[2].jsonPrimitive.float,
        bottom = array[3].jsonPrimitive.float,
    )

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

    private fun JsonObject.getValue(key: String) = requireNotNull(this[key]) { "page text has no '$key'" }

    private const val F_PAGE_ID = "pageId"
    private const val F_DIGEST = "inkDigest"
    private const val F_RECOGNISER = "recogniser"
    private const val F_LINES = "lines"
    private const val F_WORDS = "words"
    private const val F_TEXT = "text"
    private const val F_BOX = "box"
}
