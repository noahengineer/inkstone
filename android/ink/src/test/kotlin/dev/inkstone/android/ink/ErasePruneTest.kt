package dev.inkstone.android.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * ⭐ The prune's scheduling and its coverage arithmetic, on the JVM — `ErasePrune.kt`.
 *
 * ## Why these can be unit tests at all
 *
 * The decisions that went wrong were never about `androidx.ink`: an estimate that overstated the
 * work 56–101×, a pass that restarted at the head of the page, and a pass that re-examined objects
 * nothing had touched. So the scheduler ([PruneQueue]), the meter ([CoverageMeter]) and the
 * resumable scan ([ResumableCoverage]) are plain Kotlin over [CoveragePoints] and
 * [CoverageRegion], and these tests drive them with points and capsules made of floats.
 *
 * ⚠️ **What they cannot say.** That `DryStroke` hands the scan the right points (its input samples
 * and its mesh outline, via `StrokeCoveragePoints`), that `StrokeStore` offers exactly the objects
 * a gesture bit, and that a page load parks what `PreparedPage.of` decided — those need a real
 * tessellated `Stroke` and are `StrokeStoreEraseTest` / `PreparedPagePruneTest`, instrumented. And
 * none of this measures time: a work unit here is a count, and how many milliseconds one costs is a
 * question for the tablet.
 *
 * ## The fixture, and why it is shaped the way it is
 *
 * [bShapedPage] is built to the measured shape of note B — 653 erased strokes, ~114,000 capsules,
 * so ~175 capsules each — with every stroke genuinely covered by a back-and-forth scribble, which is
 * what a person rubbing something out produces and what puts the capsule count where it was. M7's
 * warning stands (a synthetic fixture is the worst witness for a geometric heuristic), which is why
 * these tests assert **schedules and counts**, and assert the fixture reproduces the measured
 * estimate-to-work ratio before trusting anything it says about the old budget.
 */
class ErasePruneTest {

    // -----------------------------------------------------------------------------------------
    // The page shaped like B
    // -----------------------------------------------------------------------------------------

    /**
     * ⛔ **The load pass decides a whole B-shaped page, in one pass, well inside its budget.**
     *
     * This is the pass `PreparedPage.of` runs off the main thread. Before it, the load-time prune
     * was the UI pass with the estimate budget, and on B it pruned two to six of ~555.
     */
    @Test
    fun theLoadBudgetDecidesAWholeBShapedPageInOnePass() {
        val page = bShapedPage()
        val queue = PruneQueue<Candidate>()
        page.forEach(queue::offer)

        val erasedAway = queue.drain(PruneBudgets.LOAD) { candidate, meter -> candidate.probe(meter) }

        assertEquals("every fully covered stroke should be decided erased away", page.size, erasedAway.size)
        assertEquals("nothing should be left for the UI thread", 0, queue.size)
        assertTrue(
            "the load pass used ${queue.lastWork} of ${PruneBudgets.LOAD} — a page like B must be nowhere near it",
            queue.lastWork * LOAD_HEADROOM < PruneBudgets.LOAD,
        )
    }

    /**
     * ⛔ **Pen-up passes finish a B-shaped page, each one inside its bound, and redo no work.**
     *
     * The two halves of the old failure, asserted separately:
     *
     * - *The estimate.* The fixture's `inputs × 3 × capsules` must overstate its real work by
     *   [MIN_ESTIMATE_RATIO] or more — the measured note's was 56–101× — or the fixture is too cheap
     *   to say anything about the bug, and the test says so rather than passing.
     * - *The restart.* Summed over every pass, the work must equal what one unbounded pass spends.
     *   A pass that restarted a candidate, or re-scanned a point, would spend more.
     *
     * Measured on this fixture when it was written: **538,778** real units for 650 strokes against
     * an old estimate of **35.1M** — 65×, inside the note's 56–101× — so the old budget reached about
     * three strokes a pass, and these three passes finish all 650. Checked the other way too: with
     * [ResumableCoverage] made to restart on every probe, this and three other tests here go red.
     */
    @Test
    fun penUpPassesFinishABShapedPageWithinTheirBoundAndRedoNothing() {
        val unbounded = PruneQueue<Candidate>()
        bShapedPage().forEach(unbounded::offer)
        unbounded.drain(Long.MAX_VALUE) { candidate, meter -> candidate.probe(meter) }
        val realWork = unbounded.lastWork

        val page = bShapedPage()
        val oldEstimate = page.sumOf { it.inputCount.toLong() * OLD_POINTS_PER_INPUT * it.region.capsules.size }
        assertTrue(
            "the fixture's estimate is only ${oldEstimate / realWork}× its real work; the measured note's was 56–101×",
            oldEstimate >= realWork * MIN_ESTIMATE_RATIO,
        )

        val queue = PruneQueue<Candidate>()
        page.forEach(queue::offer)
        val maxCapsules = page.maxOf { it.region.capsules.size }
        var passes = 0
        var totalWork = 0L
        var pruned = 0
        while (queue.size > 0) {
            pruned += queue.drain(PruneBudgets.PEN_UP) { candidate, meter -> candidate.probe(meter) }.size
            passes++
            totalWork += queue.lastWork
            assertTrue(
                "pass $passes spent ${queue.lastWork}, over the budget by more than one point's scan",
                queue.lastWork <= PruneBudgets.PEN_UP + 1 + maxCapsules,
            )
            assertTrue("no progress after $passes passes", passes <= page.size)
        }

        assertEquals("every stroke should end up pruned", page.size, pruned)
        assertEquals("work was repeated across passes — something restarted", realWork, totalWork)
        val bound = ceil(realWork.toDouble() / PruneBudgets.PEN_UP).toInt() + 1
        assertTrue("took $passes passes for $realWork units; at most $bound expected", passes <= bound)
    }

    // -----------------------------------------------------------------------------------------
    // The queue
    // -----------------------------------------------------------------------------------------

    /**
     * The pen-up pass looks at what the gesture offered and at nothing else on the page.
     *
     * A page-walking pass re-examined every erased object on every pen-up — objects whose answer
     * nothing had changed — and paid for them out of the same budget as the ones that mattered.
     */
    @Test
    fun aPassLooksOnlyAtWhatWasOffered() {
        val page = bShapedPage()
        val queue = PruneQueue<Candidate>()
        val touched = listOf(page[3], page[200], page[512])
        touched.forEach(queue::offer)

        val erasedAway = queue.drain(PruneBudgets.PEN_UP) { candidate, meter -> candidate.probe(meter) }

        assertEquals(3, queue.lastProbes)
        assertEquals(touched, erasedAway)
        val untouchedProbes = page.filter { it !in touched }.sumOf { it.probes }
        assertEquals("an object nobody offered was probed", 0, untouchedProbes)
    }

    /** Offering what is already queued keeps its place — and with it, its turn at the head. */
    @Test
    fun reOfferingKeepsTheCandidatesPlace() {
        val page = bShapedPage().take(3)
        val queue = PruneQueue<Candidate>()
        page.forEach(queue::offer)
        queue.offer(page[0])

        assertEquals(page, queue.snapshot())
    }

    /**
     * ⛔ **A candidate the meter stops is resumed, not restarted, and stays first.**
     *
     * The old pass had to let one over-budget candidate run whatever it cost, because a candidate
     * skipped for being too expensive was skipped again on every pass, forever. Resuming is what
     * lets the bound be hard: the candidate is finished a budget at a time.
     */
    @Test
    fun anUndecidedCandidateResumesAtTheHeadOfTheNextPass() {
        val expensiveWork = unboundedWorkOf(coveredStroke(Random(SEED), points = EXPENSIVE_POINTS))
        val cheapWork = unboundedWorkOf(coveredStroke(Random(SEED + 1), points = CHEAP_POINTS))
        val expensive = coveredStroke(Random(SEED), points = EXPENSIVE_POINTS)
        val cheap = coveredStroke(Random(SEED + 1), points = CHEAP_POINTS)
        val budget = expensiveWork / SLICES
        val overshoot = 1L + expensive.region.capsules.size
        val queue = PruneQueue<Candidate>()
        queue.offer(expensive)
        queue.offer(cheap)

        var passes = 0
        var total = 0L
        var lastProgress = 0
        while (queue.size > 0) {
            queue.drain(budget) { candidate, meter -> candidate.probe(meter) }
            passes++
            total += queue.lastWork
            if (expensive in queue) {
                assertTrue("the paused candidate lost its place", queue.snapshot().first() === expensive)
                assertTrue("the candidate behind it was reached first", cheap.probes == 0)
                assertTrue("no progress on pass $passes", expensive.coverage.progress > lastProgress)
                lastProgress = expensive.coverage.progress
            }
            assertTrue("the queue never emptied", passes <= SLICES * 2)
        }

        val atLeast = ceil(expensiveWork.toDouble() / (budget + overshoot)).toInt()
        assertTrue("one budget cannot have finished it: took $passes passes, need $atLeast", passes >= atLeast)
        assertEquals("resuming re-did work", expensiveWork + cheapWork, total)
    }

    /** A candidate that is still visible leaves the queue and does not stand in front of the rest. */
    @Test
    fun aVisibleCandidateLeavesAndDoesNotBlockTheOnesBehindIt() {
        val random = Random(SEED)
        val visible = coveredStroke(random, points = 50).also { it.uncover(25) }
        val covered = coveredStroke(random, points = 50)
        val queue = PruneQueue<Candidate>()
        queue.offer(visible)
        queue.offer(covered)

        val erasedAway = queue.drain(PruneBudgets.PEN_UP) { candidate, meter -> candidate.probe(meter) }

        assertEquals(listOf(covered), erasedAway)
        assertEquals("a decided candidate stayed queued", 0, queue.size)
    }

    // -----------------------------------------------------------------------------------------
    // The scan
    // -----------------------------------------------------------------------------------------

    /**
     * Stopping a scan anywhere and resuming it gives the answer an uninterrupted scan gives, for the
     * same total work — at every budget from one unit up.
     */
    @Test
    fun interruptingAScanNeverChangesItsAnswerOrItsCost() {
        val random = Random(SEED)
        repeat(RANDOM_CASES) { case ->
            val points = 5 + random.nextInt(60)
            val reference = coveredStroke(Random(case), points).also { if (case % 3 == 0) it.uncover(points / 2) }
            val meter = CoverageMeter.unlimited()
            val expected = reference.probe(meter)
            val expectedWork = meter.spent

            for (budget in listOf(1L, 2L, 3L, 7L, 50L)) {
                val subject = coveredStroke(Random(case), points).also { if (case % 3 == 0) it.uncover(points / 2) }
                var verdict: PruneVerdict
                var work = 0L
                var passes = 0
                do {
                    val slice = CoverageMeter(budget)
                    verdict = subject.probe(slice)
                    work += slice.spent
                    passes++
                    assertTrue("case $case at budget $budget did not converge", passes < 100_000)
                } while (verdict == PruneVerdict.UNDECIDED)
                assertEquals("case $case at budget $budget", expected, verdict)
                assertEquals("case $case at budget $budget re-did work", expectedWork, work)
            }
        }
    }

    /**
     * The scan's answer against a coverage test written independently of it — `hypot`, no hint, no
     * early exit. *Verify with a reader that is not the writer.*
     */
    @Test
    fun theScanAgreesWithABruteForceTest() {
        val random = Random(SEED)
        var covered = 0
        var uncovered = 0
        repeat(RANDOM_CASES) {
            val xy = FloatArray(2 * (1 + random.nextInt(30))) { random.nextFloat() * 40f }
            val capsules = List(1 + random.nextInt(40)) {
                ErasedRegion.Capsule(
                    random.nextFloat() * 40f,
                    random.nextFloat() * 40f,
                    random.nextFloat() * 40f,
                    random.nextFloat() * 40f,
                    random.nextFloat() * 12f,
                )
            }
            val expected = (0 until xy.size / 2).all { i -> capsules.any { bruteCovers(it, xy[2 * i], xy[2 * i + 1]) } }
            val verdict = ResumableCoverage().probe(FakePoints(xy), FakeRegion(capsules), CoverageMeter.unlimited())
            assertEquals(if (expected) PruneVerdict.ERASED_AWAY else PruneVerdict.VISIBLE, verdict)
            if (expected) covered++ else uncovered++
        }
        assertTrue("the random cases never exercised both answers", covered > 0 && uncovered > 0)
    }

    /**
     * A negative radius covered nothing when the test was `hypot(...) <= radius`; squared distances
     * must not make it cover `|radius|`. Only a damaged or foreign op carries one.
     */
    @Test
    fun aNegativeRadiusCoversNothing() {
        val region = FakeRegion(listOf(ErasedRegion.Capsule(0f, 0f, 10f, 0f, -5f)))
        val verdict = ResumableCoverage().probe(FakePoints(floatArrayOf(5f, 1f)), region, CoverageMeter.unlimited())
        assertEquals(PruneVerdict.VISIBLE, verdict)
    }

    /** One unit per point read and one per distance test — counted, never estimated. */
    @Test
    fun theMeterCountsReadsAndDistanceTests() {
        // Three points; capsule 2 covers the first, capsule 2 the second, capsule 0 the third.
        val points = FakePoints(floatArrayOf(20f, 0f, 21f, 0f, 0f, 0f))
        val region = FakeRegion(
            listOf(
                ErasedRegion.Capsule(0f, 0f, 1f, 0f, 1f),
                ErasedRegion.Capsule(10f, 0f, 11f, 0f, 1f),
                ErasedRegion.Capsule(20f, 0f, 21f, 0f, 1f),
            ),
        )
        val meter = CoverageMeter.unlimited()

        val verdict = ResumableCoverage().probe(points, region, meter)

        assertEquals(PruneVerdict.ERASED_AWAY, verdict)
        // Point 1: 3 tests from hint 0 (0, 1, 2). Point 2: 1 test at hint 2. Point 3: hint 2 misses,
        // wraps to 0 — 2 tests. Plus three reads.
        assertEquals(3L + 3L + 1L + 2L, meter.spent)
    }

    /**
     * Progress survives the region **growing** — a point under some capsule of a set is under some
     * capsule of every superset — and is thrown away when it **shrinks** or is **replaced**, or when
     * the points (a re-tessellated mesh) are.
     */
    @Test
    fun progressSurvivesGrowthAndIsDroppedWhenItStopsBeingTrue() {
        val candidate = coveredStroke(Random(SEED), points = 200)
        candidate.probe(CoverageMeter(PARTIAL_BUDGET))
        // What one slice reaches from a standing start. Every reset below must land exactly here —
        // the fixture is deterministic — and every resumption must land past it.
        val firstSlice = candidate.coverage.progress
        assertTrue(
            "the fixture finished in one slice, so this proves nothing",
            firstSlice in 1 until candidate.points.count,
        )

        candidate.region.list += ErasedRegion.Capsule(-100f, -100f, -99f, -100f, 1f)
        candidate.probe(CoverageMeter(PARTIAL_BUDGET))
        assertTrue("growing the region restarted the scan", candidate.coverage.progress > firstSlice)

        candidate.region.shrink()
        candidate.probe(CoverageMeter(PARTIAL_BUDGET))
        assertEquals("shrinking the region kept stale progress", firstSlice, candidate.coverage.progress)

        candidate.probe(CoverageMeter(PARTIAL_BUDGET), region = FakeRegion(candidate.region.list))
        assertEquals("a replaced region kept stale progress", firstSlice, candidate.coverage.progress)

        candidate.probe(CoverageMeter(PARTIAL_BUDGET), points = FakePoints(candidate.points.xy.copyOf()))
        assertEquals("re-tessellated points kept stale progress", firstSlice, candidate.coverage.progress)
    }

    // -----------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------

    private class FakePoints(val xy: FloatArray) : CoveragePoints {
        override val count: Int get() = xy.size / 2

        override fun load(index: Int, into: PagePointF) {
            into.set(xy[2 * index], xy[2 * index + 1])
        }
    }

    private class FakeRegion(capsules: List<ErasedRegion.Capsule>) : CoverageRegion {
        val list = ArrayList(capsules)
        override val capsules: List<ErasedRegion.Capsule> get() = list
        override var shrinks: Int = 0

        fun shrink() {
            list.removeAt(list.size - 1)
            shrinks++
        }
    }

    /** One erased stroke: its coverage points, its region, and its own paused scan. */
    private class Candidate(val inputCount: Int, var points: FakePoints, var region: FakeRegion) {
        val coverage = ResumableCoverage()
        var probes = 0

        fun probe(
            meter: CoverageMeter,
            points: FakePoints = this.points,
            region: FakeRegion = this.region,
        ): PruneVerdict {
            this.points = points
            this.region = region
            probes++
            return coverage.probe(points, region, meter)
        }

        /** Move one point far off, so the stroke is no longer fully covered. */
        fun uncover(index: Int) {
            points.xy[2 * index] = UNCOVERED
            points.xy[2 * index + 1] = UNCOVERED
        }
    }

    /**
     * [STROKES] strokes, each ~[STROKE_LENGTH] pt long, each covered by a back-and-forth scribble of
     * ~175 capsules — B's measured average. Deterministic.
     */
    private fun bShapedPage(): List<Candidate> {
        val random = Random(SEED)
        return List(STROKES) { coveredStroke(random, points = INPUTS_PER_STROKE) }
    }

    /**
     * A stroke — [points] input samples along a gentle wave, then its outline either side of them
     * (as `StrokeCoveragePoints` orders them: inputs first, outline after) — and a scribble over it.
     */
    private fun coveredStroke(random: Random, points: Int): Candidate {
        val originX = random.nextFloat() * PAGE_WIDTH
        val originY = random.nextFloat() * PAGE_HEIGHT
        val phase = random.nextFloat() * 2f * PI.toFloat()
        val centre = FloatArray(2 * points)
        for (i in 0 until points) {
            val t = i.toFloat() / (points - 1).coerceAtLeast(1)
            centre[2 * i] = originX + t * STROKE_LENGTH
            centre[2 * i + 1] = originY + WAVE * sin(phase + t * 2f * PI.toFloat())
        }
        // Outline: along one side, back along the other — two vertices per input, as a mesh has.
        val outline = FloatArray(4 * points)
        for (i in 0 until points) {
            outline[2 * i] = centre[2 * i]
            outline[2 * i + 1] = centre[2 * i + 1] - HALF_WIDTH
            val back = points - 1 - i
            outline[2 * (points + i)] = centre[2 * back]
            outline[2 * (points + i) + 1] = centre[2 * back + 1] + HALF_WIDTH
        }
        val xy = centre + outline

        val scribble = Scribble(
            left = originX - MARGIN,
            right = originX + STROKE_LENGTH + MARGIN,
            top = originY - WAVE - HALF_WIDTH - MARGIN,
            bottom = originY + WAVE + HALF_WIDTH + MARGIN,
        )
        repeat(PASSES) { scribble.passOver() }
        return Candidate(points, FakePoints(xy), FakeRegion(scribble.capsules))
    }

    /**
     * An eraser rubbed back and forth over a box: rows left-to-right then right-to-left, sampled
     * every [SAMPLE_STEP] pt — so consecutive capsules are consecutive samples, as they are off a
     * pen, and the rolling hint sees what it sees in the app.
     */
    private class Scribble(val left: Float, val right: Float, val top: Float, val bottom: Float) {
        val capsules = ArrayList<ErasedRegion.Capsule>()
        private var lastX = left
        private var lastY = top

        fun passOver() {
            var y = top
            var forward = true
            while (y <= bottom + ROW_SPACING) {
                row(y, forward)
                y += ROW_SPACING
                forward = !forward
            }
        }

        private fun row(y: Float, forward: Boolean) {
            val step = if (forward) SAMPLE_STEP else -SAMPLE_STEP
            var x = if (forward) left else right
            while (x in left..right) {
                capsules += ErasedRegion.Capsule(lastX, lastY, x, y, ERASER_RADIUS)
                lastX = x
                lastY = y
                x += step
            }
        }
    }

    /** What deciding [candidate] costs in one uninterrupted look — the reference for "no rework". */
    private fun unboundedWorkOf(candidate: Candidate): Long {
        val meter = CoverageMeter.unlimited()
        candidate.probe(meter)
        return meter.spent
    }

    private fun bruteCovers(c: ErasedRegion.Capsule, x: Float, y: Float): Boolean {
        val dx = c.toX - c.fromX
        val dy = c.toY - c.fromY
        val length = dx * dx + dy * dy
        val t = if (length <= 0f) 0f else (((x - c.fromX) * dx + (y - c.fromY) * dy) / length).coerceIn(0f, 1f)
        return hypot(x - (c.fromX + t * dx), y - (c.fromY + t * dy)) <= c.radius
    }

    private companion object {
        const val SEED = 20260924

        /** B: 653 erased strokes; ~555 of them fully erased. */
        const val STROKES = 650
        const val INPUTS_PER_STROKE = 100
        const val STROKE_LENGTH = 30f
        const val WAVE = 3f
        const val HALF_WIDTH = 1.5f
        const val PAGE_WIDTH = 560f
        const val PAGE_HEIGHT = 760f

        /** The eraser tip, in page points — `EraserController.RADIUS_DP` at roughly fit width. */
        const val ERASER_RADIUS = 4f
        const val ROW_SPACING = 4f
        const val SAMPLE_STEP = 3f
        const val MARGIN = 2f

        /** Three goes over the stroke: 5 rows × 12 samples × 3 = 180 capsules, B's ~175. */
        const val PASSES = 3

        /** The old estimate's per-input factor: `COVERAGE_POINTS_PER_INPUT`. */
        const val OLD_POINTS_PER_INPUT = 3L

        /** The measured note's estimate was 56–101× its real work; a fixture far below that is not B. */
        const val MIN_ESTIMATE_RATIO = 30L

        /** The load budget must be a comfortable multiple of a B-shaped page, not a near miss. */
        const val LOAD_HEADROOM = 5L

        const val SLICES = 5
        const val EXPENSIVE_POINTS = 4_000
        const val CHEAP_POINTS = 20
        const val PARTIAL_BUDGET = 150L
        const val RANDOM_CASES = 200
        const val UNCOVERED = 10_000f
    }
}
