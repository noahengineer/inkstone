package dev.inkstone.core.model

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC §4.2 and §4.3 — the fold, and the reason every register is absolute.
 *
 * The load-bearing test in this file is [`concurrent transforms resolve last-writer-wins, not a skewed compound`],
 * which is ROADMAP M2's own acceptance criterion and SPEC §13.2 row 3.
 */
class PageFoldTest {

    private val pageId = PageId("page-1")
    private val deviceA = "aaaa"
    private val deviceB = "bbbb"

    private var opCounter = 0

    private fun op() = "op-${opCounter++}"

    private fun clock(counter: Long, device: String = deviceA) = LamportClock(counter, device)

    private fun stroke(id: String, z: String = "V") = StrokeObject(
        id = ObjectId(id),
        geometry = StrokeGeometry(byteArrayOf(1, 2, 3)),
        z = FractionalKey(z),
    )

    // -----------------------------------------------------------------------
    // The acceptance criterion
    // -----------------------------------------------------------------------

    /**
     * SPEC §4.2's worked example, and ROADMAP M2's acceptance criterion.
     *
     * > Device A scales object Y by 2×. Device B concurrently rotates Y by 45°. A then
     * > undoes with an inverse-scale op. Total order is `scale, rotate, unscale` — the
     * > rotation gets smeared by the non-commuting scale, and Y ends up skewed.
     * > Consistently, on both devices, silently.
     *
     * With absolute matrices the result must be **one of the three matrices written**,
     * chosen by clock — never their product, and never skewed.
     */
    @Test
    fun `concurrent transforms resolve last-writer-wins — not a skewed compound`() {
        val target = ObjectId("obj-Y")
        // Non-uniform, because that is what dragging a side handle does — and because a
        // *uniform* scale is a scalar multiple of the identity and therefore commutes
        // with rotation, which would hide the bug entirely. The delta failure is
        // invisible in the simplest case, which is exactly why it survived to be worth
        // writing a spec rule about.
        val scale = Matrix3.scale(2f, 1f)
        val rotate = Matrix3.rotate((PI / 4).toFloat())
        // A's undo writes back the absolute matrix that was there before its scale.
        val unscale = Matrix3.Identity

        val add = PageOp.AddObject(op(), clock(1, deviceA), stroke("obj-Y"))
        // A scales at counter 2; B rotates concurrently, also at counter 2. The
        // deviceId tiebreak puts B after A — deterministically, on both devices.
        val aScales = PageOp.SetTransform(op(), clock(2, deviceA), target, scale)
        val bRotates = PageOp.SetTransform(op(), clock(2, deviceB), target, rotate)
        // A then undoes its own scale, at counter 3.
        val aUndoes = PageOp.SetTransform(op(), clock(3, deviceA), target, unscale)

        val ops = listOf(add, aScales, bRotates, aUndoes)
        val folded = foldPage(pageId, ops)
        val actual = assertNotNull(folded.find(target)).transform

        // The last writer by clock is A's undo, so the winner is the matrix it wrote.
        assertEquals(unscale, actual, "SetTransform must be LWW over whole matrices")
        // The delta failure mode, named. Had these three ops been deltas, the fold would
        // have composed them in total order — and the result is not perpendicular any
        // more, which is the "skewed object on both devices, silently" of SPEC §4.2.
        assertFalse(actual.isSkewed(), "the fold produced a skewed compound transform")
        val asDeltas = requireNotNull(scale.inverse()) * rotate * scale
        assertTrue(asDeltas.isSkewed(), "the delta composition really is skewed")

        // Every permutation of arrival order folds to the same thing, which is the
        // whole convergence claim of SPEC §4.3.
        for (permutation in permutations(ops)) {
            val other = foldPage(pageId, permutation)
            assertEquals(actual, assertNotNull(other.find(target)).transform, "arrival order changed the result")
        }
    }

    // -----------------------------------------------------------------------
    // Registers
    // -----------------------------------------------------------------------

    @Test
    fun `a register op that sorts before its AddObject does not win`() {
        val target = ObjectId("obj-1")
        val moved = Matrix3.translate(10f, 20f)
        // The stale transform has the LOWER clock but arrives FIRST — the exact case a
        // naive "apply as they arrive" fold gets wrong.
        val stale = PageOp.SetTransform(op(), clock(1), target, moved)
        val add = PageOp.AddObject(op(), clock(2), stroke("obj-1"))

        val folded = foldPage(pageId, listOf(stale, add))

        assertEquals(Matrix3.Identity, assertNotNull(folded.find(target)).transform)
    }

    @Test
    fun `a register op that sorts after its AddObject wins even when it arrives first`() {
        val target = ObjectId("obj-1")
        val moved = Matrix3.translate(10f, 20f)
        val later = PageOp.SetTransform(op(), clock(5), target, moved)
        val add = PageOp.AddObject(op(), clock(2), stroke("obj-1"))

        val folded = foldPage(pageId, listOf(later, add))

        assertEquals(moved, assertNotNull(folded.find(target)).transform)
    }

    @Test
    fun `a transform on a deleted object is a no-op — not a resurrection`() {
        val target = ObjectId("obj-1")
        val folded = foldPage(
            pageId,
            listOf(
                PageOp.AddObject(op(), clock(1), stroke("obj-1")),
                PageOp.DeleteObject(op(), clock(2), target),
                PageOp.SetTransform(op(), clock(3), target, Matrix3.translate(5f, 5f)),
            ),
        )

        assertNull(folded.find(target))
        assertTrue(target in folded.deleted)
    }

    @Test
    fun `a delete of an object that does not exist yet is still recorded`() {
        // SPEC §4.2, verbatim: "record the tombstone anyway (the AddObject may arrive
        // later — this is what makes ordering irrelevant)".
        val target = ObjectId("obj-1")
        val delete = PageOp.DeleteObject(op(), clock(9), target)
        val add = PageOp.AddObject(op(), clock(3), stroke("obj-1"))

        assertTrue(target in foldPage(pageId, listOf(delete)).deleted)
        // And when the AddObject turns up afterwards, the object stays deleted.
        val both = foldPage(pageId, listOf(delete, add))
        assertNull(both.find(target))
        assertTrue(target in both.deleted)
    }

    @Test
    fun `a re-add keeps the original payload and only restores presence`() {
        // Undo of a delete is an AddObject (SPEC §4.6). It must not be able to
        // overwrite geometry with whatever the undoing device happened to hold.
        val target = ObjectId("obj-1")
        val original = stroke("obj-1")
        val impostor = StrokeObject(target, StrokeGeometry(byteArrayOf(9, 9, 9)))

        val folded = foldPage(
            pageId,
            listOf(
                PageOp.AddObject(op(), clock(1), original),
                PageOp.DeleteObject(op(), clock(2), target),
                PageOp.AddObject(op(), clock(3), impostor),
            ),
        )

        val live = assertNotNull(folded.find(target)) as StrokeObject
        assertEquals(original.geometry, live.geometry, "a re-add overwrote geometry")
    }

    @Test
    fun `erased region is an absolute register`() {
        val target = ObjectId("obj-1")
        val first = ErasedArea(listOf(EraseCapsule(0f, 0f, 10f, 0f, 2f)))
        val second = ErasedArea(first.capsules + EraseCapsule(10f, 0f, 20f, 0f, 2f))

        val folded = foldPage(
            pageId,
            listOf(
                PageOp.AddObject(op(), clock(1), stroke("obj-1")),
                PageOp.SetErasedRegion(op(), clock(3), target, second),
                PageOp.SetErasedRegion(op(), clock(2), target, first),
            ),
        )

        // The later clock wins whole, so the region is `second` and not the union of
        // both — a union would be a delta by another name.
        assertEquals(second, assertNotNull(folded.find(target)).erased)
    }

    @Test
    fun `render order is by z then id — so ties are not resolved by map order`() {
        val folded = foldPage(
            pageId,
            listOf(
                PageOp.AddObject(op(), clock(3), stroke("obj-c", z = "M")),
                PageOp.AddObject(op(), clock(1), stroke("obj-b", z = "M")),
                PageOp.AddObject(op(), clock(2), stroke("obj-a", z = "Z")),
            ),
        )

        assertEquals(
            listOf("obj-b", "obj-c", "obj-a"),
            folded.objects.map { it.id.value },
        )
    }

    @Test
    fun `page meta is a per-field register`() {
        val folded = foldPage(
            pageId,
            listOf(
                PageOp.SetPageMeta(op(), clock(1), background = Background.Template(PageTemplate.RULED)),
                PageOp.SetPageMeta(op(), clock(2), size = PageSize.A4),
                // Older, so it must not win the background back.
                PageOp.SetPageMeta(op(), clock(0), background = Background.Blank),
            ),
        )

        assertEquals(Background.Template(PageTemplate.RULED), folded.background)
        assertEquals(PageSize.A4, folded.size)
    }

    // -----------------------------------------------------------------------
    // Rule 9 — re-fold, never patch
    // -----------------------------------------------------------------------

    @Test
    fun `an op with a lower clock forces a re-fold rather than an append`() {
        val log = PageLog(pageId)
        val target = ObjectId("obj-1")

        assertEquals(FoldOutcome.APPENDED, log.apply(PageOp.AddObject(op(), clock(5), stroke("obj-1"))))
        assertEquals(
            FoldOutcome.APPENDED,
            log.apply(PageOp.SetTransform(op(), clock(6), target, Matrix3.translate(1f, 1f))),
        )

        // Arrives late, sorts early. Appending it would let a stale value win.
        val late = PageOp.SetTransform(op(), clock(4), target, Matrix3.scale(3f))
        assertEquals(FoldOutcome.REFOLDED, log.apply(late))

        assertEquals(Matrix3.translate(1f, 1f), assertNotNull(log.state.find(target)).transform)
    }

    @Test
    fun `an op older than the snapshot makes the snapshot stale`() {
        // SPEC §4.7 — "the snapshot is a cache, not the truth. If an op older than the
        // snapshot ever arrives, discard the snapshot and re-fold."
        val snapshot = foldPage(pageId, listOf(PageOp.AddObject(op(), clock(10), stroke("obj-1"))))
        val log = PageLog(pageId, snapshot = snapshot)

        assertFalse(log.snapshotStale)
        val outcome = log.apply(PageOp.AddObject(op(), clock(4), stroke("obj-2")))

        assertEquals(FoldOutcome.SNAPSHOT_STALE, outcome)
        assertTrue(log.snapshotStale)
    }

    @Test
    fun `folding from a snapshot gives the same state as folding the whole log`() {
        val ops = listOf(
            PageOp.AddObject(op(), clock(1), stroke("obj-1")),
            PageOp.AddObject(op(), clock(2), stroke("obj-2", z = "Z")),
            PageOp.DeleteObject(op(), clock(3), ObjectId("obj-1")),
        )
        val whole = foldPage(pageId, ops)

        val snapshot = foldPage(pageId, ops.take(2))
        val incremental = PageLog(pageId, snapshot = snapshot).also { it.applyAll(ops.drop(2)) }.state

        assertEquals(whole.objects.map { it.id }, incremental.objects.map { it.id })
        assertEquals(whole.deleted, incremental.deleted)
        assertEquals(whole.throughClock, incremental.throughClock)
    }

    /**
     * `applyAll` builds the folded state once per batch — a selection gesture is one op per
     * object, and the per-op build behind the old `applyAll` was a page-turn-sized cost per
     * autosave tick — and the batching must be invisible: same state, same worst outcome as
     * [PageLog.apply] one op at a time, for a batch mixing in-order and late ops.
     */
    @Test
    fun `applyAll matches per-op apply for a batch with a late op`() {
        val target = ObjectId("obj-1")
        val ops = listOf(
            PageOp.AddObject(op(), clock(1), stroke("obj-1")),
            PageOp.AddObject(op(), clock(3), stroke("obj-2", z = "Z")),
            // Late: sorts before obj-2's add, so the batch must re-fold — and the winning
            // transform must still be the clock-6 write below, not this one.
            PageOp.SetTransform(op(), clock(2), target, Matrix3.scale(3f)),
            PageOp.SetTransform(op(), clock(6), target, Matrix3.translate(1f, 1f)),
        )
        val oneAtATime = PageLog(pageId)
        var worst = FoldOutcome.APPENDED
        for (one in ops) {
            val each = oneAtATime.apply(one)
            if (each.ordinal > worst.ordinal) worst = each
        }

        val batched = PageLog(pageId)
        val outcome = batched.applyAll(ops)

        assertEquals(FoldOutcome.REFOLDED, outcome)
        assertEquals(worst, outcome)
        assertEquals(oneAtATime.state.objects.map { it.id }, batched.state.objects.map { it.id })
        assertEquals(oneAtATime.state.throughClock, batched.state.throughClock)
        assertEquals(oneAtATime.state.opCount, batched.state.opCount)
        assertEquals(Matrix3.translate(1f, 1f), assertNotNull(batched.state.find(target)).transform)
    }

    @Test
    fun `applyAll reports a stale snapshot and refolds without it`() {
        val snapshot = foldPage(pageId, listOf(PageOp.AddObject(op(), clock(10), stroke("obj-1"))))
        val log = PageLog(pageId, snapshot = snapshot)

        val outcome = log.applyAll(
            listOf(
                PageOp.AddObject(op(), clock(4), stroke("obj-2")),
                PageOp.AddObject(op(), clock(12), stroke("obj-3")),
            ),
        )

        assertEquals(FoldOutcome.SNAPSHOT_STALE, outcome)
        assertTrue(log.snapshotStale)
        // Refolded from the log alone: the stale snapshot's object is gone, both batch ops are in.
        assertEquals(setOf(ObjectId("obj-2"), ObjectId("obj-3")), log.state.objects.map { it.id }.toSet())
    }

    // -----------------------------------------------------------------------
    // Forward compatibility (SPEC §5.8)
    // -----------------------------------------------------------------------

    @Test
    fun `an unknown op folds as a no-op and is kept for writing back`() {
        val unknown = PageOp.Unknown(
            opId = op(),
            clock = clock(2),
            type = "SetSomethingFromTheFuture",
            rawJson = """{"type":"SetSomethingFromTheFuture","v":42}""".encodeToByteArray(),
        )
        val folded = foldPage(pageId, listOf(PageOp.AddObject(op(), clock(1), stroke("obj-1")), unknown))

        assertEquals(1, folded.objects.size)
        assertEquals(listOf(unknown), folded.unknownOps)
        // It still counts towards the log, so compaction sizing is honest about it.
        assertEquals(2, folded.opCount)
    }

    private fun <T> permutations(items: List<T>): List<List<T>> {
        if (items.size <= 1) return listOf(items)
        return items.indices.flatMap { index ->
            val rest = items.toMutableList().also { it.removeAt(index) }
            permutations(rest).map { listOf(items[index]) + it }
        }
    }
}
