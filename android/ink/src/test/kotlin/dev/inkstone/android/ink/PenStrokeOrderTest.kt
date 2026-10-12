package dev.inkstone.android.ink

import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.InkObject
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.TextBoxObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⭐ Which strokes the recogniser is given, and in what order — the JVM half of the sampler change.
 *
 * `InkStrokeSampler` stopped building a `StrokeStore` (and so stopped tessellating the page) and
 * walks the fold itself. The constraint was that the list it produces must not change, so every
 * rule the store used to apply implicitly is now [penStrokesInOrder]'s to apply explicitly — and
 * each one is asserted here against a fake decoder, because no real blob can be decoded off a device.
 *
 * ⚠️ The other half — that a real blob's samples, timestamps and boxes come out identical to what
 * the store-built path produced — needs `androidx.ink`'s native code and is
 * `InkStrokeSamplerTest`, instrumented. It was written, not run.
 */
class PenStrokeOrderTest {

    /** The decoder the fake blobs speak: first byte 0 = pen, 1 = highlighter, anything else = damaged. */
    private fun decode(obj: StrokeObject): String? = when (obj.geometry.bytes.firstOrNull()) {
        PEN -> "pen"
        HIGHLIGHTER -> "highlighter"
        else -> null
    }

    private fun order(objects: List<InkObject>): List<String> =
        penStrokesInOrder(objects, ::decode) { it == "highlighter" }.map { it.first.id.value }

    @Test
    fun strokesComeOutInZThenIdOrderWhateverOrderTheyArrived() {
        val objects = listOf(
            stroke("c", z = "b"),
            stroke("b", z = "a"),
            stroke("a", z = "b"),
            stroke("d", z = "c"),
        )

        // (z, id): ("a","b"), ("b","a"), ("b","c"), ("c","d").
        assertEquals(listOf("b", "a", "c", "d"), order(objects))
    }

    @Test
    fun highlightersAreLeftOut() {
        val objects = listOf(
            stroke("pen-1", z = "a"),
            stroke("marker", z = "b", kind = HIGHLIGHTER),
            stroke("pen-2", z = "c"),
        )

        assertEquals(listOf("pen-1", "pen-2"), order(objects))
    }

    @Test
    fun anUndecodableBlobIsLeftOut() {
        val objects = listOf(
            stroke("good", z = "a"),
            stroke("damaged", z = "b", kind = DAMAGED),
        )

        assertEquals(listOf("good"), order(objects))
    }

    /** Text boxes, images and shapes have no ink samples — SPEC §8 gives the recogniser handwriting. */
    @Test
    fun objectsThatAreNotStrokesAreLeftOut() {
        val objects = listOf(
            stroke("pen", z = "b"),
            TextBoxObject(id = ObjectId("words"), text = "hello", widthPt = 40f, heightPt = 12f, z = FractionalKey("a")),
        )

        assertEquals(listOf("pen"), order(objects))
    }

    /**
     * `StrokeStore.addObject` refused an id it already held, so the **first object that built**
     * won — whatever its kind — and a stroke whose blob could not be read never entered the store,
     * so it did not block a later object with its id. A fold cannot produce a repeated id; a
     * hand-built page can, and the list must not change for it either.
     */
    @Test
    fun aRepeatedIdKeepsTheFirstObjectThatBuilt() {
        val blockedByText = listOf(
            TextBoxObject(id = ObjectId("x"), text = "t", widthPt = 1f, heightPt = 1f, z = FractionalKey("a")),
            stroke("x", z = "b"),
        )
        assertEquals("a text box built first, so the stroke was refused", emptyList<String>(), order(blockedByText))

        val notBlockedByDamage = listOf(
            stroke("y", z = "a", kind = DAMAGED),
            stroke("y", z = "b"),
        )
        assertEquals("an unreadable stroke never entered the store", listOf("y"), order(notBlockedByDamage))

        val firstPenWins = listOf(
            stroke("z", z = "c"),
            stroke("z", z = "a"),
        )
        val kept = penStrokesInOrder(firstPenWins, ::decode) { it == "highlighter" }
        assertEquals(1, kept.size)
        assertEquals("the second copy replaced the first", FractionalKey("c"), kept.single().first.z)
    }

    private fun stroke(id: String, z: String, kind: Byte = PEN): StrokeObject = StrokeObject(
        id = ObjectId(id),
        geometry = StrokeGeometry(byteArrayOf(kind)),
        z = FractionalKey(z),
    )

    private companion object {
        const val PEN: Byte = 0
        const val HIGHLIGHTER: Byte = 1
        const val DAMAGED: Byte = 9
    }
}
