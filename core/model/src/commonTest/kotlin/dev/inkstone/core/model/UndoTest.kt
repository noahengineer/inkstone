package dev.inkstone.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SPEC §4.6 — undo is a new op, and every inverse is "write back what was there". */
class UndoTest {

    private val pageId = PageId("page-1")
    private var counter = 0L

    private fun clock() = LamportClock(++counter, "device-a")

    private var ops = 0

    private fun opId() = "op-${ops++}"

    private fun stroke(id: String) = StrokeObject(ObjectId(id), StrokeGeometry(byteArrayOf(7)))

    /** A page plus its log, applying ops the way the editor does. */
    private class Fixture(pageId: PageId) {
        val log = PageLog(pageId)

        fun apply(vararg op: PageOp) = log.applyAll(op.toList())
    }

    @Test
    fun `undoing an add deletes it — and undoing that delete restores the same geometry`() {
        val fixture = Fixture(pageId)
        val original = stroke("obj-1")
        val add = PageOp.AddObject(opId(), clock(), original)

        val beforeAdd = fixture.log.state
        fixture.apply(add)
        assertNotNull(fixture.log.state.find(original.id))

        val undo = assertNotNull(Undo.inverseOf(add, beforeAdd, opId(), clock()))
        assertTrue(undo is PageOp.DeleteObject)
        val beforeUndo = fixture.log.state
        fixture.apply(undo)
        assertNull(fixture.log.state.find(original.id))

        // Redo: the inverse of the delete, computed from the state before it — where
        // the object was still live, which is why no tombstone side table is needed.
        val redo = assertNotNull(Undo.inverseOf(undo, beforeUndo, opId(), clock()))
        fixture.apply(redo)
        val restored = assertNotNull(fixture.log.state.find(original.id)) as StrokeObject
        assertEquals(original.geometry, restored.geometry)
    }

    @Test
    fun `undoing a transform writes back the previous absolute matrix`() {
        val fixture = Fixture(pageId)
        val target = ObjectId("obj-1")
        fixture.apply(PageOp.AddObject(opId(), clock(), stroke("obj-1")))
        fixture.apply(PageOp.SetTransform(opId(), clock(), target, Matrix3.translate(3f, 4f)))

        val before = fixture.log.state
        val scale = PageOp.SetTransform(opId(), clock(), target, Matrix3.scale(2f))
        fixture.apply(scale)

        val undo = assertNotNull(Undo.inverseOf(scale, before, opId(), clock()))
        fixture.apply(undo)

        assertEquals(Matrix3.translate(3f, 4f), assertNotNull(fixture.log.state.find(target)).transform)
    }

    @Test
    fun `undoing an erase drops the capsules that gesture added`() {
        val fixture = Fixture(pageId)
        val target = ObjectId("obj-1")
        fixture.apply(PageOp.AddObject(opId(), clock(), stroke("obj-1")))

        val before = fixture.log.state
        val region = ErasedArea(listOf(EraseCapsule(0f, 0f, 5f, 0f, 1f)))
        val erase = PageOp.SetErasedRegion(opId(), clock(), target, region)
        fixture.apply(erase)
        assertEquals(region, assertNotNull(fixture.log.state.find(target)).erased)

        fixture.apply(assertNotNull(Undo.inverseOf(erase, before, opId(), clock())))

        // Nothing was destroyed to erase, so nothing is reconstructed to un-erase.
        assertEquals(ErasedArea.None, assertNotNull(fixture.log.state.find(target)).erased)
    }

    @Test
    fun `a gesture's inverse is applied in reverse order`() {
        val fixture = Fixture(pageId)
        val a = PageOp.AddObject(opId(), clock(), stroke("obj-a"))
        val b = PageOp.AddObject(opId(), clock(), stroke("obj-b"))
        val before = fixture.log.state
        fixture.apply(a, b)

        val inverse = Undo.inverseOf(listOf(a, b), before, ::opId, ::clock)

        assertEquals(listOf(ObjectId("obj-b"), ObjectId("obj-a")), inverse.map { it.target })
    }

    @Test
    fun `an unknown op has no inverse`() {
        val unknown = PageOp.Unknown(opId(), clock(), "FromTheFuture", byteArrayOf())
        assertNull(Undo.inverseOf(unknown, FoldedPage.empty(pageId), opId(), clock()))
    }

    // -----------------------------------------------------------------------
    // The stack — ROADMAP M2: "undo/redo across 200 ops including an app restart"
    // -----------------------------------------------------------------------

    @Test
    fun `undo and redo survive being rebuilt from persisted entries`() {
        val fixture = Fixture(pageId)
        val stack = UndoStack()

        // 200 gestures, each one stroke.
        val ids = (0 until GESTURES).map { ObjectId("obj-$it") }
        for (id in ids) {
            val before = fixture.log.state
            val add = PageOp.AddObject(opId(), clock(), StrokeObject(id, StrokeGeometry(byteArrayOf(1))))
            fixture.apply(add)
            stack.commit(UndoEntry(pageId, listOf(add), Undo.inverseOf(listOf(add), before, ::opId, ::clock)))
        }
        assertEquals(GESTURES, fixture.log.state.objects.size)

        // The restart: the stacks come back from the bundle, nothing else survives.
        val reloaded = UndoStack(stack.undoEntries, stack.redoEntries)

        repeat(UNDO_STEPS) { fixture.apply(*reloaded.undo(pageId, ::opId, ::clock).toTypedArray()) }
        assertEquals(GESTURES - UNDO_STEPS, fixture.log.state.objects.size)

        repeat(UNDO_STEPS) { fixture.apply(*reloaded.redo(pageId, ::opId, ::clock).toTypedArray()) }
        assertEquals(GESTURES, fixture.log.state.objects.size)
        // Re-applying a stored op only works because every op is absolute: a replayed
        // AddObject writes presence and cannot overwrite geometry.
        assertTrue(fixture.log.state.objects.map { it.id }.containsAll(ids))
    }

    @Test
    fun `a new gesture invalidates the redo branch`() {
        val stack = UndoStack()
        val add = PageOp.AddObject(opId(), clock(), stroke("obj-1"))
        stack.commit(UndoEntry(pageId, listOf(add), listOf(PageOp.DeleteObject(opId(), clock(), ObjectId("obj-1")))))
        stack.undo(pageId, ::opId, ::clock)
        assertTrue(stack.canRedo(pageId))

        stack.commit(UndoEntry(pageId, listOf(add), emptyList()))

        assertFalse(stack.canRedo(pageId))
        assertTrue(stack.canUndo(pageId))
    }

    @Test
    fun `a re-stamped op gets a newer clock so the fold prefers it`() {
        val stored = PageOp.SetTransform("old", LamportClock(1, "a"), ObjectId("obj-1"), Matrix3.scale(2f))
        val fresh = Undo.restamp(stored, "new", LamportClock(9, "a"))

        assertEquals(LamportClock(9, "a"), fresh.clock)
        assertEquals("new", fresh.opId)
        assertEquals(Matrix3.scale(2f), (fresh as PageOp.SetTransform).absolute)
    }

    private companion object {
        const val GESTURES = 200
        const val UNDO_STEPS = 120
    }
}
