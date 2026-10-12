package dev.inkstone.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC §4.9.7 — the Element-instance tag and the arithmetic that finds a copy again after it moved.
 *
 * ⚠️ Test names may contain no commas and no apostrophes — these compile for `iosSimulatorArm64`.
 */
class ElementInstanceTest {

    private val member = Matrix3.translate(10f, 20f)
    private val tag = ElementInstance(ELEMENT, INSTANCE, member, pivot = PagePoint(30f, 40f))

    @Test
    fun `the tag round-trips through the format register beside the other keys`() {
        val format = ObjectFormat(knockout = true, cutter = true, instance = tag)
        val decoded = ObjectFormat.decode(format.encode())
        assertEquals(format, decoded)
        assertTrue(decoded.hidesBeneath)
    }

    @Test
    fun `the editor's bounds and their pivot round-trip and hide nothing`() {
        val format = ObjectFormat(elementFrame = true, framePivot = PagePoint(18.5f, 36f))
        val decoded = ObjectFormat.decode(format.encode())
        assertEquals(format, decoded)
        assertFalse(decoded.hidesBeneath)
    }

    @Test
    fun `a pivot this build cannot read is kept rather than reset`() {
        val later = "if1;ef=1;pv=1:2:3"
        val decoded = ObjectFormat.decode(later)
        assertTrue(decoded.elementFrame)
        assertNull(decoded.framePivot)
        assertEquals(later, decoded.encode())
    }

    @Test
    fun `a tag carrying the Element's bounds round-trips with and without a pivot`() {
        val sized = tag.copy(size = PagePoint(108f, 72f))
        assertEquals(sized, ObjectFormat.decode(ObjectFormat(instance = sized).encode()).instance)
        val bare = sized.copy(pivot = null)
        assertEquals(bare, ObjectFormat.decode(ObjectFormat(instance = bare).encode()).instance)
    }

    @Test
    fun `a copy with bounds and no pivot snaps by its bounds' top-left corner`() {
        val bare = ElementInstance(ELEMENT, INSTANCE, member, size = PagePoint(108f, 72f))
        // Placed at (100, 100): the piece's transform is placement × member.
        val piece = Matrix3.translate(100f, 100f) * member
        assertEquals(PagePoint(100f, 100f), bare.pivotOn(piece))
        assertEquals(PageRect(100f, 100f, 208f, 172f), bare.boundsOn(piece))
    }

    @Test
    fun `a copy placed before bounds were carried has no pivot and no bounds`() {
        val old = ElementInstance(ELEMENT, INSTANCE, member)
        assertNull(old.pivotOn(member))
        assertNull(old.boundsOn(member))
    }

    @Test
    fun `two pieces of one copy share a key and a duplicated copy does not`() {
        val other = tag.copy(member = Matrix3.translate(60f, 10f))
        val placement = Matrix3.translate(100f, 100f)
        assertEquals(tag.copyKey(placement * member), other.copyKey(placement * other.member))
        val duplicate = Matrix3.translate(300f, 100f)
        assertNotEquals(tag.copyKey(placement * member), tag.copyKey(duplicate * member))
    }

    @Test
    fun `a tag without a pivot round-trips too`() {
        val bare = tag.copy(pivot = null)
        assertEquals(bare, ObjectFormat.decode(ObjectFormat(instance = bare).encode()).instance)
    }

    @Test
    fun `a placement is recovered from a piece however the copy was moved`() {
        // Dropped at (100, 100), then dragged by (50, -20): the piece is now at drop * drag * member.
        val dropped = Matrix3.translate(100f, 100f)
        val dragged = Matrix3.translate(50f, -20f) * dropped
        val pieceNow = dragged * member

        val placement = tag.placementOf(pieceNow)
        assertEquals(150f, placement?.m02)
        assertEquals(80f, placement?.m12)
        assertEquals(PagePoint(180f, 120f), tag.pivotOn(pieceNow))
    }

    @Test
    fun `a value this build did not write is kept and not read as a tag`() {
        val later = "if1;el=only-one-part"
        val decoded = ObjectFormat.decode(later)
        assertNull(decoded.instance)
        assertEquals(later, decoded.encode())
    }

    @Test
    fun `an id a separator could hide in is never written`() {
        val unsafe = tag.copy(elementId = "a:b")
        assertNull(ObjectFormat.decode(ObjectFormat(instance = unsafe).encode()).instance)
    }

    private companion object {
        const val ELEMENT = "018f0000-0000-7000-8000-0000000000e1"
        const val INSTANCE = "018f0000-0000-7000-8000-0000000000f1"
    }
}
