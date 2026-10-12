package dev.inkstone.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `two pieces of one copy are one copy and a duplicated copy is not`() {
        val other = tag.copy(member = Matrix3.translate(60f, 10f))
        val placement = Matrix3.translate(100f, 100f)
        val piece = requireNotNull(tag.locate(placement * member))
        assertTrue(piece.sameCopyAs(requireNotNull(other.locate(placement * other.member))))
        // Duplicate puts the copy 18 pt away on each axis, tag and all.
        val duplicate = Matrix3.translate(118f, 118f)
        assertFalse(piece.sameCopyAs(requireNotNull(tag.locate(duplicate * member))))
        val anotherDrop = tag.copy(instanceId = "another-drop")
        assertFalse(piece.sameCopyAs(requireNotNull(anotherDrop.locate(placement * member))))
    }

    @Test
    fun `a resized piece is one copy with the rest once its tag has been through the register`() {
        // *"When you try to select an element it does not select the individual parts and instead
        // the whole element."* As the editor saves an Element: its strokes stay where they were drawn,
        // some 1,500 pt into the scratch page, and only their transforms are re-based — so a piece
        // that was resized carries a scale the tag writes to three decimals, multiplied by that lever.
        val origin = Matrix3.translate(-SCRATCH_X, -SCRATCH_Y)
        val members = listOf(
            origin,
            origin * Matrix3.translate(3.217f, -1.371f),
            origin * Matrix3.translate(250.123f, 240.456f) * Matrix3.scale(0.8333333f),
            Matrix3.rotate(0.3f) * origin,
        )
        val placement = Matrix3.translate(412.34567f, 233.45678f)
        val pieces = members.map { exact -> requireNotNull(stored(tag.copy(member = exact)).locate(placement * exact)) }

        // What the old key compared to a hundredth of a point: these four are not one placement.
        val spread = pieces.maxOf { it.placement.m02 } - pieces.minOf { it.placement.m02 }
        assertTrue(spread > KEY_STEP, "the pieces disagree by $spread pt, so rounding split them")
        for (a in pieces) for (b in pieces) assertTrue(a.sameCopyAs(b))
        assertEquals(1, CopyPlacement.group(pieces) { it }.size)
    }

    @Test
    fun `the same pieces dropped twice and duplicated are three copies`() {
        val origin = Matrix3.translate(-SCRATCH_X, -SCRATCH_Y)
        val members = listOf(origin, origin * Matrix3.translate(250.123f, 240.456f) * Matrix3.scale(0.8333333f))
        fun copyAt(at: Matrix3, instance: String) = members.map { exact ->
            requireNotNull(stored(tag.copy(instanceId = instance, member = exact)).locate(at * exact))
        }
        val dropped = Matrix3.translate(412.345f, 233.456f)
        val duplicated = Matrix3.translate(18f, 18f) * dropped
        val pieces = copyAt(dropped, INSTANCE) + copyAt(duplicated, INSTANCE) + copyAt(dropped, "second-drop")
        assertEquals(listOf(2, 2, 2), CopyPlacement.group(pieces) { it }.map { it.size })
    }

    @Test
    fun `a copy scaled and turned after it was placed is still one copy`() {
        val origin = Matrix3.translate(-SCRATCH_X, -SCRATCH_Y)
        val members = listOf(origin, origin * Matrix3.translate(80.5f, 12.25f) * Matrix3.scale(1.37f))
        // Placed, then grown to three times its size and turned — the copy's own pieces drift apart
        // by more than they did, and a second copy of it is still 18 pt away.
        val placement = Matrix3.translate(300.5f, 410.25f) * Matrix3.rotate(-0.7f) * Matrix3.scale(3f)
        val pieces = members.map { exact -> requireNotNull(stored(tag.copy(member = exact)).locate(placement * exact)) }
        assertTrue(pieces[0].sameCopyAs(pieces[1]))
        val duplicate = members.map { exact ->
            requireNotNull(stored(tag.copy(member = exact)).locate(Matrix3.translate(18f, 18f) * placement * exact))
        }
        assertFalse(pieces[0].sameCopyAs(duplicate[1]))
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

    /** [instance] as a piece carries it: written into the format register and read back. */
    private fun stored(instance: ElementInstance): ElementInstance =
        requireNotNull(ObjectFormat.decode(ObjectFormat(instance = instance).encode()).instance)

    private companion object {
        const val ELEMENT = "018f0000-0000-7000-8000-0000000000e1"
        const val INSTANCE = "018f0000-0000-7000-8000-0000000000f1"

        /** About where the Element editor's 3,000 pt scratch page puts an Element (`scratchOrigin`). */
        const val SCRATCH_X = 1464f
        const val SCRATCH_Y = 1440f

        /** The old `copyKey` rounded placements to this. */
        const val KEY_STEP = 0.01f
    }
}
