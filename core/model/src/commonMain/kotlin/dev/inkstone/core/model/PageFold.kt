package dev.inkstone.core.model

/**
 * SPEC §4.3 — convergence, as a fold.
 *
 * Not a CRDT in the fancy sense: **total-order state-machine replication.** Sort
 * the union of ops by Lamport clock, fold from empty, and every device holds the
 * same state. The whole design leans on that being genuinely order-independent,
 * which is why every op is an absolute register and nothing is a delta.
 *
 * ## What the fold has to get right
 *
 * A register op can legitimately be folded *before* the [PageOp.AddObject] it
 * targets — a peer's ops arrive in whatever order the transport delivered them,
 * and the clock order is not the arrival order. So the fold keeps register values
 * in a table keyed by `(ObjectId, field)` independently of whether the object
 * exists yet, and applies them when the page is materialised. An op aimed at an
 * object that never turns up costs one map entry and no error.
 *
 * That is also why a fold over the *same set* of ops always produces the same
 * state regardless of the order they were handed in: this function sorts first.
 */
public fun foldPage(
    pageId: PageId,
    ops: Iterable<PageOp>,
    base: FoldedPage? = null,
    defaultSize: PageSize = PageSize.Letter,
    defaultBackground: Background = Background.Blank,
): FoldedPage {
    val folder = PageFolder(
        pageId = pageId,
        size = base?.size ?: defaultSize,
        background = base?.background ?: defaultBackground,
        base = base,
    )
    // Sorting is the whole convergence argument. `sortedBy` is stable, and the
    // comparator is total (counter then deviceId), so the result is a single
    // well-defined order across devices.
    for (op in ops.sortedBy { it.clock }) folder.absorb(op)
    return folder.build()
}

/**
 * The state you draw: a page folded from its log.
 *
 * [objects] is in render order — `(z, id)` (SPEC §4.3) — and contains only objects
 * whose presence register says they are here. Deleted ones are in [deleted], with
 * their geometry still in the log, because nothing is ever thrown away.
 *
 * @property throughClock the highest clock folded in. `PageLog` compares against
 *   it to decide whether an incoming op can be appended or forces a re-fold.
 * @property unknownOps ops this build could not interpret, kept so a save writes
 *   them back verbatim (SPEC §5.8).
 * @property texts the winning `SetTextContent` register per object — see [textOf].
 */
public class FoldedPage(
    public val pageId: PageId,
    public val size: PageSize,
    public val background: Background,
    public val objects: List<InkObject>,
    public val deleted: Set<ObjectId>,
    public val throughClock: LamportClock?,
    public val opCount: Int,
    public val unknownOps: List<PageOp.Unknown> = emptyList(),
    public val texts: Map<ObjectId, String> = emptyMap(),
) {

    public val isEmpty: Boolean get() = objects.isEmpty()

    public fun find(id: ObjectId): InkObject? = objects.firstOrNull { it.id == id }

    /**
     * ⭐ The `SetTextContent` register's winning value for [id], whatever kind of object it is.
     *
     * ⚠️ **Exposed in M8b, and until then this register was reachable for exactly one kind.**
     * `materialise` copies the winning text onto a [TextBoxObject]'s payload and drops it for
     * everything else — so a `SetTextContent` aimed at a shape folded correctly, converged
     * correctly, round-tripped through the file correctly, and was then discarded at the last
     * step with nothing to see. That register is where a connector's attachment lives
     * ([ShapeRecord]), so M8b needs the fold to answer for it rather than only for text boxes.
     *
     * Kept as a side table rather than pushed onto the payload, because the payload is
     * first-write-wins and this value is one the user can change (see [ShapeRecord]).
     */
    public fun textOf(id: ObjectId): String? = texts[id]

    public companion object {
        public fun empty(
            pageId: PageId,
            size: PageSize = PageSize.Letter,
            background: Background = Background.Blank,
        ): FoldedPage = FoldedPage(
            pageId = pageId,
            size = size,
            background = background,
            objects = emptyList(),
            deleted = emptySet(),
            throughClock = null,
            opCount = 0,
        )
    }
}

/**
 * The fold's working state. One instance per fold; not reused, not thread-safe.
 *
 * Kept separate from [foldPage] so that [PageLog] can append a single op to an
 * existing fold without re-deriving the register tables — which is sound only for
 * an op that sorts after everything already absorbed, and [PageLog] is what
 * enforces that.
 */
internal class PageFolder(
    private val pageId: PageId,
    private var size: PageSize,
    private var background: Background,
    base: FoldedPage?,
) {

    /** First-write-wins: an object's payload comes from its earliest [PageOp.AddObject]. */
    private val payloads = LinkedHashMap<ObjectId, InkObject>()

    private val presence = HashMap<ObjectId, Register<Presence>>()
    private val transforms = HashMap<ObjectId, Register<Matrix3>>()
    private val styles = HashMap<ObjectId, Register<ObjectStyle>>()
    private val zOrders = HashMap<ObjectId, Register<FractionalKey>>()
    private val texts = HashMap<ObjectId, Register<String>>()
    private val erasures = HashMap<ObjectId, Register<ErasedArea>>()

    private var sizeClock: LamportClock? = null
    private var backgroundClock: LamportClock? = null

    private val unknown = ArrayList<PageOp.Unknown>()

    private var highest: LamportClock? = null
    private var count = 0

    init {
        if (base != null) seedFrom(base)
    }

    /**
     * Reload a snapshot as if it had been folded.
     *
     * A snapshot is a cache of a fold, so its objects already have their winning
     * register values; they are seeded at the snapshot's own clock so that any op
     * older than the snapshot loses — which is correct, because an op older than
     * the snapshot is already inside it. An op older than the snapshot's *base*
     * arriving later is the case CLAUDE.md rule 9 covers: discard the snapshot and
     * re-fold, which [PageLog] does.
     */
    private fun seedFrom(base: FoldedPage) {
        val clock = base.throughClock ?: return
        for (obj in base.objects) {
            payloads[obj.id] = obj
            presence[obj.id] = Register(Presence.PRESENT, clock)
        }
        for (id in base.deleted) presence[id] = Register(Presence.DELETED, clock)
        // ⚠️ The text register is seeded too, and it is the one register here that is *not*
        // already baked into the payload. `withRegisters` carries the transform, style, z key and
        // erased region onto the object, so re-seeding those would be redundant — but a shape's
        // record ([ShapeRecord]) lives only in this table, and a snapshot that dropped it would
        // hand back a fold in which every connector had quietly become a plain line.
        for ((id, text) in base.texts) texts[id] = Register(text, clock)
        unknown += base.unknownOps
        highest = clock
        count = base.opCount
    }

    // One branch per op type. This is the fold, and the whole convergence argument in SPEC §4.3
    // rests on being able to read every case in one place and see that each writes an absolute
    // value into a clock-ordered register.
    @Suppress("CyclomaticComplexMethod")
    fun absorb(op: PageOp) {
        count++
        val clock = op.clock
        val current = highest
        if (current == null || clock > current) highest = clock

        when (op) {
            is PageOp.AddObject -> {
                // Grow-only: the first payload for an id is the one that sticks. A
                // re-add (an undone delete) must not replace geometry with whatever
                // the undoing device happened to hold.
                payloads.getOrPut(op.obj.id) { op.obj }
                write(presence, op.obj.id, Presence.PRESENT, clock)
                // The AddObject's own field values are the baseline for every
                // register, at its own clock — so a register op that sorts after it
                // wins, and one that sorts before it does not.
                write(transforms, op.obj.id, op.obj.transform, clock)
                op.obj.style?.let { write(styles, op.obj.id, it, clock) }
                write(zOrders, op.obj.id, op.obj.z, clock)
                write(erasures, op.obj.id, op.obj.erased, clock)
                if (op.obj is TextBoxObject) write(texts, op.obj.id, op.obj.text, clock)
            }

            // Recorded even when the object is unknown here: its AddObject may
            // arrive later (SPEC §4.2).
            is PageOp.DeleteObject -> write(presence, op.target, Presence.DELETED, clock)

            is PageOp.SetTransform -> write(transforms, op.target, op.absolute, clock)

            is PageOp.SetStyle -> write(styles, op.target, op.style, clock)

            is PageOp.SetZOrder -> write(zOrders, op.target, op.z, clock)

            is PageOp.SetTextContent -> write(texts, op.target, op.text, clock)

            is PageOp.SetErasedRegion -> write(erasures, op.target, op.absolute, clock)

            is PageOp.SetPageMeta -> {
                op.size?.let {
                    if (isLater(sizeClock, clock)) {
                        size = it
                        sizeClock = clock
                    }
                }
                op.background?.let {
                    if (isLater(backgroundClock, clock)) {
                        background = it
                        backgroundClock = clock
                    }
                }
            }

            // A no-op by definition, but it still counts towards the log and still
            // has to be written back (SPEC §5.8).
            is PageOp.Unknown -> unknown += op
        }
    }

    private fun <T> write(into: MutableMap<ObjectId, Register<T>>, id: ObjectId, value: T, clock: LamportClock) {
        val existing = into[id]
        into[id] = existing?.write(value, clock) ?: Register(value, clock)
    }

    private fun isLater(existing: LamportClock?, candidate: LamportClock): Boolean =
        existing == null || candidate > existing

    fun build(): FoldedPage {
        val deleted = LinkedHashSet<ObjectId>()
        val live = ArrayList<InkObject>(payloads.size)

        for ((id, payload) in payloads) {
            if (presence[id]?.value == Presence.DELETED) {
                deleted += id
                continue
            }
            live += materialise(payload)
        }
        // A delete whose AddObject never arrived is still a tombstone we know about.
        for ((id, state) in presence) {
            if (state.value == Presence.DELETED && id !in payloads) deleted += id
        }

        // Render order. `(z, id)` and not z alone: two devices can pick the same
        // key, and a tie broken by map iteration order is a tie broken differently
        // on each device.
        live.sortWith(compareBy({ it.z.value }, { it.id.value }))

        return FoldedPage(
            pageId = pageId,
            size = size,
            background = background,
            objects = live,
            deleted = deleted,
            throughClock = highest,
            opCount = count,
            unknownOps = unknown.toList(),
            // Every winning value, including ones whose object has not arrived — a register op
            // may legitimately be folded before its `AddObject` (see the class KDoc), and a
            // connector's record is exactly the kind that can.
            texts = texts.mapValues { (_, register) -> register.value },
        )
    }

    /** Applies the winning register values to an object's payload. */
    private fun materialise(payload: InkObject): InkObject {
        val id = payload.id
        val withRegisters = payload.withRegisters(
            transform = transforms[id]?.value ?: payload.transform,
            style = styles[id]?.value ?: payload.style,
            z = zOrders[id]?.value ?: payload.z,
            erased = erasures[id]?.value ?: payload.erased,
        )
        val text = texts[id]?.value
        return if (withRegisters is TextBoxObject && text != null && text != withRegisters.text) {
            withRegisters.copy(text = text)
        } else {
            withRegisters
        }
    }
}

/**
 * A page's log plus its folded state, with **CLAUDE.md rule 9 enforced by the type**.
 *
 * > When an incoming op has a Lamport clock lower than ops already applied, re-fold
 * > the page from the last snapshot. Appending it to already-applied state diverges.
 *
 * The rule is easy to state and easy to forget under pressure, so [apply] does not
 * offer the wrong option: it appends when the clock allows and re-folds when it
 * does not, and reports which it did. Nothing else can mutate the state.
 *
 * @param snapshot the compaction snapshot to re-fold from, if there is one
 *   (SPEC §4.7). Held rather than recomputed, because a snapshot is a cache and the
 *   log is the truth.
 */
public class PageLog(
    public val pageId: PageId,
    ops: List<PageOp> = emptyList(),
    private val snapshot: FoldedPage? = null,
    private val defaultSize: PageSize = PageSize.Letter,
    private val defaultBackground: Background = Background.Blank,
) {

    private val log = ArrayList<PageOp>(ops)

    private var folder = newFolder()

    /**
     * True once an op older than [snapshot] has arrived.
     *
     * SPEC §4.7: *"the snapshot is a cache, not the truth. If an op older than the
     * snapshot ever arrives, discard the snapshot and re-fold."* This log cannot do
     * that on its own — it holds only the ops since the snapshot, and the full log
     * lives in the bundle — so it reports the fact and the storage layer discards
     * `snapshot.bundle` and rebuilds from the whole log.
     */
    public var snapshotStale: Boolean = false
        private set

    public var state: FoldedPage = FoldedPage.empty(pageId, defaultSize, defaultBackground)
        private set

    /** Every op this log holds — since the snapshot, if there is one. */
    public val ops: List<PageOp> get() = log

    init {
        refold()
    }

    /**
     * Fold [op] in.
     *
     * @return [FoldOutcome.APPENDED] when the op sorted after everything already
     *   applied, [FoldOutcome.REFOLDED] when it did not and the page was re-derived,
     *   or [FoldOutcome.SNAPSHOT_STALE] when it predates the snapshot and the
     *   snapshot itself has to go (SPEC §4.7).
     */
    public fun apply(op: PageOp): FoldOutcome {
        val through = state.throughClock
        val snapshotClock = snapshot?.throughClock
        log += op

        if (snapshotClock != null && op.clock <= snapshotClock) {
            // The snapshot has already folded ops this one should have sorted
            // before, so it is not a valid base any more. Re-fold from what is
            // here, and tell the caller the snapshot has to be discarded.
            snapshotStale = true
            refold(useSnapshot = false)
            return FoldOutcome.SNAPSHOT_STALE
        }

        return if (through == null || op.clock > through) {
            folder.absorb(op)
            state = folder.build()
            FoldOutcome.APPENDED
        } else {
            refold()
            FoldOutcome.REFOLDED
        }
    }

    /** Fold several ops in, reporting the most serious outcome any of them caused. */
    public fun applyAll(ops: Iterable<PageOp>): FoldOutcome {
        var outcome = FoldOutcome.APPENDED
        for (op in ops) {
            val one = apply(op)
            if (one.ordinal > outcome.ordinal) outcome = one
        }
        return outcome
    }

    private fun refold(useSnapshot: Boolean = !snapshotStale) {
        folder = newFolder(useSnapshot)
        for (op in log.sortedBy { it.clock }) folder.absorb(op)
        state = folder.build()
    }

    private fun newFolder(useSnapshot: Boolean = !snapshotStale) = PageFolder(
        pageId = pageId,
        size = snapshot?.takeIf { useSnapshot }?.size ?: defaultSize,
        background = snapshot?.takeIf { useSnapshot }?.background ?: defaultBackground,
        base = snapshot?.takeIf { useSnapshot },
    )
}

/**
 * What [PageLog.apply] had to do — reported so callers can log a re-fold, not
 * choose one. Ordered by severity: `applyAll` reports the worst.
 */
public enum class FoldOutcome { APPENDED, REFOLDED, SNAPSHOT_STALE }
