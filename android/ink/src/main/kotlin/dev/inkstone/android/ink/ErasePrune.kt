package dev.inkstone.android.ink

/**
 * ⭐ Pruning fully erased ink — the scheduling and the arithmetic, with no `androidx.ink` and no
 * `android.*` in them, so a JVM test can reach every decision this file makes.
 *
 * ## ⛔ What this replaced, and what was measured
 *
 * `StrokeStore.pruneFullyErased` used to walk the whole page, charge each candidate an
 * **estimate** of `inputs × 3 × capsules` against a budget of 200,000, and stop. An adversarial
 * review ran that against two real notes — **B**: 2,097 objects, 653 erased strokes, ~114,000
 * eraser capsules; **A**: 1,143 objects, 101 erased objects — and the estimate came out
 * **56–101× the distance tests the coverage test really runs** (B: 48.8M estimated against
 * 0.70–0.87M real), because [coveringCapsuleIn]'s rolling hint turns almost every point into a
 * hit on the first compare. So one pass pruned two to six strokes and gave up, and every pass
 * restarted at the head of the page, re-charging the same candidates. B kept ~555 invisible
 * strokes and ~555 of its ~647 `saveLayer` + `DST_OUT` pairs; A kept 78 of 101. Those pairs are
 * paid on **every full re-record of the dry layer** — each erase frame, each selection-drag
 * frame, each zoom settle, each pan off the recorded region, each undo. Nothing was wrong on
 * screen; the page was just slow in proportion to how much had ever been rubbed out.
 *
 * ## The three changes, and why each one
 *
 * 1. **Charge what the work costs, not a guess at it.** [CoverageMeter] counts one unit per point
 *    read and one per point-against-capsule distance test, as they happen. A budget denominated
 *    in an estimate that is two orders of magnitude out is not a budget; it is a much smaller
 *    number with a comment beside it.
 * 2. **Resume, never restart.** A candidate the meter stops mid-way keeps its progress
 *    ([ResumableCoverage]) and stays at the head of the [PruneQueue], so the next pass carries on
 *    from the point it stopped at. That is what lets the budget be a *hard* bound per pass and
 *    still guarantee every candidate is eventually decided — the previous design needed a
 *    "forced" candidate that ran whatever it cost, because a candidate over the budget would
 *    otherwise be skipped forever. Progress is only kept while it is still true: see
 *    [ResumableCoverage] on why an erased region may grow under a paused scan but not shrink.
 * 3. **Look only at what changed.** The queue holds objects whose region, mesh or presence
 *    changed since they were last decided; an eraser pen-up offers exactly the objects that
 *    gesture erased. Untouched objects are never re-examined, because nothing about their answer
 *    can have moved.
 *
 * The page-load pass goes further and is not on the UI thread at all — see `PreparedPage.of`.
 */
internal object PruneBudgets {
    /**
     * Work units per UI-thread prune pass — an eraser pen-up, or an `applyOps` that changed an
     * erased region.
     *
     * The same number as the old estimate budget, now in **real** units, so the pass is allowed to
     * do roughly 56–101× what it actually did before. Not measured on a device: at a guessed
     * ~10–50 ns per unit (a JNI point read is the dear half, a squared-distance compare the cheap
     * one) it is 2–10 ms, and the number to trust is whatever the tablet says. What *is* exact is
     * the bound — [PruneQueue.drain] stops within one point's scan of it (see [CoverageScan]).
     */
    const val PEN_UP = 200_000L

    /**
     * Work units for the load-time pass, which runs **off the main thread** inside
     * `PreparedPage.of`, beside the tessellation that already dominates an open.
     *
     * Sized from B, the worst real note measured: ~0.7–0.9M distance tests plus roughly one point
     * read per test, so ~1.5M units for the whole page. Twenty million is a dozen B's; it is bounded
     * at all only because the page's ink is not shown until this build finishes, so a pathological
     * page should not be able to hold it back for long. What is left undecided past it is handed to
     * the UI queue *with its progress*, so the bound costs such a page some pen-up time rather than
     * correctness.
     */
    const val LOAD = 20_000_000L
}

/** What one look at a prune candidate found. */
internal enum class PruneVerdict {
    /**
     * Every point is under the erased region: the object may leave the render state. It is **not
     * deleted** — no op is emitted and the store keeps it for an un-erase (CLAUDE.md rule 1).
     */
    ERASED_AWAY,

    /** Some point is not covered, or a cheap gate already said so: keep drawing it. */
    VISIBLE,

    /** The meter ran out first. The candidate keeps its progress and is looked at again. */
    UNDECIDED,
}

/**
 * Coverage work allowed, and spent — in **work units**: one per point read, one per
 * point-against-capsule distance test.
 *
 * Deliberately counted rather than estimated; see the file KDoc for what the estimate cost.
 */
internal class CoverageMeter(val limit: Long) {
    var spent: Long = 0L
        private set

    val exhausted: Boolean get() = spent >= limit

    fun charge(units: Long) {
        spent += units
    }

    companion object {
        /** For a decision that must be made now and in full — an un-erase (`StrokeStore.unprune`). */
        fun unlimited(): CoverageMeter = CoverageMeter(Long.MAX_VALUE)
    }
}

/**
 * The points a coverage test must find under the erased region, in a fixed order.
 *
 * Random access by index rather than an iterator, because a paused scan resumes at an index.
 */
internal interface CoveragePoints {
    val count: Int

    fun load(index: Int, into: PagePointF)
}

/**
 * The erased region as a coverage test sees it: its capsules, and whether any were ever taken away.
 */
internal interface CoverageRegion {
    val capsules: List<ErasedRegion.Capsule>

    /**
     * Bumped by anything that **removes** a capsule without replacing the region object.
     *
     * Appending cannot make a covered point uncovered, so progress survives it; removing can, so
     * progress measured before a removal must be thrown away. See [ResumableCoverage].
     */
    val shrinks: Int
}

/**
 * *"Is every one of these points under some capsule?"* — resumable, and metered.
 *
 * The order is the caller's ([CoveragePoints]), and so is the rolling [cursor] that makes the test
 * cheap: consecutive points of a stroke are near each other, so the capsule that covered the last
 * one usually covers this one, and [coveringCapsuleIn] starts there.
 *
 * ⚠️ **The meter is checked between points, never inside one point's scan.** That is what makes
 * resuming exact — a point is either wholly decided or not started, so no work is ever done twice —
 * and it bounds a pass's overshoot at one point: one read plus at most one full scan of one region.
 */
internal class CoverageScan {
    /** Points `[0, next)` are known to be covered. */
    var next: Int = 0
        private set

    private var cursor: Int = 0

    fun reset() {
        next = 0
        cursor = 0
    }

    /**
     * Carry on from [next].
     *
     * On [PruneVerdict.VISIBLE], [next] is left **at** the uncovered point, not past it: the points
     * before it stay covered however the region grows, and a later look (after more erasing) resumes
     * by re-testing exactly the one that failed.
     */
    fun run(
        points: CoveragePoints,
        capsules: List<ErasedRegion.Capsule>,
        meter: CoverageMeter,
        scratch: PagePointF,
    ): PruneVerdict {
        val count = points.count
        while (next < count) {
            if (meter.exhausted) return PruneVerdict.UNDECIDED
            points.load(next, scratch)
            meter.charge(1L)
            val found = coveringCapsuleIn(capsules, scratch.x, scratch.y, cursor, meter)
            if (found < 0) return PruneVerdict.VISIBLE
            cursor = found
            next++
        }
        return PruneVerdict.ERASED_AWAY
    }
}

/**
 * A [CoverageScan] plus **what its progress was measured against**, so it is discarded exactly when
 * it stops being true.
 *
 * Three keys, compared by identity:
 *
 * - the **points** — a restyle re-tessellates a stroke, which is a different mesh and a different
 *   outline, so the owner hands in a new [CoveragePoints] and progress starts again;
 * - the **region** object — `SetErasedRegion` is absolute (CLAUDE.md rule 8) and *replaces* the
 *   region, which can be smaller (an undo), so a new object means start again;
 * - the region's [CoverageRegion.shrinks] — the same thing happening in place.
 *
 * What does **not** reset it is the region growing: `eraseCapsule` appends to the same object, and
 * a point under some capsule of a set is under some capsule of every superset. That is the case a
 * scribble produces — the next erase gesture over a half-decided stroke — and it is why resuming is
 * sound rather than hopeful. The cursor stays valid for the same reason: capsule indices only grow.
 */
internal class ResumableCoverage {
    private val scan = CoverageScan()
    private val scratch = PagePointF()
    private var points: CoveragePoints? = null
    private var region: CoverageRegion? = null
    private var shrinks: Int = 0

    /** How far the current scan has got — for tests, which must be able to see that nothing restarted. */
    val progress: Int get() = scan.next

    fun probe(points: CoveragePoints, region: CoverageRegion, meter: CoverageMeter): PruneVerdict {
        if (points !== this.points || region !== this.region || region.shrinks != shrinks) {
            scan.reset()
            this.points = points
            this.region = region
            shrinks = region.shrinks
        }
        return scan.run(points, region.capsules, meter, scratch)
    }
}

/**
 * The objects whose "is it erased away?" answer may have changed, oldest first.
 *
 * ## Why a queue and not a walk of the page
 *
 * A pass used to walk every object on the page from the front. That re-examined objects nothing had
 * touched — whose answer therefore could not have changed — and, because it stopped when the budget
 * ran out and started from the front next time, it spent every pass on the same head of the list:
 * simulated against B it stopped making progress after 19 pen-ups with 145 of 561 pruned. Here the
 * store offers exactly what changed, a decided object leaves, and an undecided one stays where it
 * is, so the next pass begins with it.
 *
 * Identity semantics: `DryObject` does not override `equals`, and must not start to — two objects
 * with equal payloads are still two objects.
 *
 * Not thread-safe; each instance belongs to one thread (the store's UI thread, or one page build).
 */
internal class PruneQueue<T : Any> {
    private val pending = LinkedHashSet<T>()

    val size: Int get() = pending.size

    /** How many candidates the last [drain] looked at — for the tests that assert a pass stayed small. */
    var lastProbes: Int = 0
        private set

    /** Work units the last [drain] spent. */
    var lastWork: Long = 0L
        private set

    /** Queue [item], or leave it where it is if it is already queued (and keep its progress). */
    fun offer(item: T) {
        pending.add(item)
    }

    fun remove(item: T) {
        pending.remove(item)
    }

    fun clear() {
        pending.clear()
    }

    operator fun contains(item: T): Boolean = item in pending

    /** What is still queued, in order. */
    fun snapshot(): List<T> = pending.toList()

    /**
     * Look at queued candidates in order until [budget] work units are spent.
     *
     * A decided candidate leaves the queue either way; an undecided one — the meter ran out inside
     * it — stays, and since everything before it has left, it is at the head for the next pass.
     *
     * @return the candidates found erased away, already off the queue. The caller acts on them
     *   **after** this returns, so its bookkeeping may freely call [remove] without disturbing the
     *   iteration.
     */
    fun drain(budget: Long, probe: (T, CoverageMeter) -> PruneVerdict): List<T> {
        val meter = CoverageMeter(budget)
        var erasedAway: MutableList<T>? = null
        var probes = 0
        val iterator = pending.iterator()
        while (iterator.hasNext() && !meter.exhausted) {
            val item = iterator.next()
            probes++
            when (probe(item, meter)) {
                PruneVerdict.ERASED_AWAY -> {
                    iterator.remove()
                    val found = erasedAway ?: ArrayList<T>().also { erasedAway = it }
                    found += item
                }

                PruneVerdict.VISIBLE -> iterator.remove()

                // Stays, with its progress. The loop condition ends the pass.
                PruneVerdict.UNDECIDED -> Unit
            }
        }
        lastProbes = probes
        lastWork = meter.spent
        return erasedAway ?: emptyList()
    }
}

/**
 * The index of a capsule covering [x], [y], or -1 — starting the scan at [hint] and wrapping, and
 * charging [meter] one unit per distance test when there is one.
 *
 * Every capsule is visited at most once, so the start point changes only *how long* the search
 * takes, never what it finds. See [ErasedRegion.coveringCapsule], which is this with no meter.
 */
internal fun coveringCapsuleIn(
    capsules: List<ErasedRegion.Capsule>,
    x: Float,
    y: Float,
    hint: Int,
    meter: CoverageMeter?,
): Int {
    val size = capsules.size
    if (size == 0) return -1
    val start = if (hint in 0 until size) hint else 0
    for (step in 0 until size) {
        val index = if (start + step < size) start + step else start + step - size
        meter?.charge(1L)
        if (capsuleCovers(capsules[index], x, y)) return index
    }
    return -1
}

/**
 * True when [x], [y] lies within the capsule's radius of its segment.
 *
 * ⚠️ **Squared distances, not `hypot`.** This is the innermost loop of every coverage test, and
 * `hypot`'s overflow-safe square root bought nothing at page coordinates, whose squares are nowhere
 * near a float's range. `d² ≤ r²` is `d ≤ r` for non-negative values up to rounding at the boundary,
 * and a point within an ulp of an eraser's edge is not one anybody can see either way. The
 * `radius >= 0` guard keeps the one real difference out: a negative radius (a damaged or foreign
 * op) covered nothing under `hypot ≤ r` and would cover `|r|` squared.
 */
private fun capsuleCovers(c: ErasedRegion.Capsule, x: Float, y: Float): Boolean {
    if (!(c.radius >= 0f)) return false
    val dx = c.toX - c.fromX
    val dy = c.toY - c.fromY
    val lengthSquared = dx * dx + dy * dy
    val t = if (lengthSquared <= 0f) {
        0f
    } else {
        (((x - c.fromX) * dx + (y - c.fromY) * dy) / lengthSquared).coerceIn(0f, 1f)
    }
    val ex = x - (c.fromX + t * dx)
    val ey = y - (c.fromY + t * dy)
    return ex * ex + ey * ey <= c.radius * c.radius
}
