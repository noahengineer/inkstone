package dev.inkstone.android.ink

import androidx.ink.strokes.Stroke
import dev.inkstone.core.model.EraseCapsule
import dev.inkstone.core.model.ErasedArea
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.StrokeObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Turns edits on the dry layer into [PageOp]s — **without serialising anything on the ink
 * path** (CLAUDE.md rule 7).
 *
 * ## The problem this solves
 *
 * An `AddObject` carries a stroke's geometry blob, and producing that blob means walking
 * every input sample and compressing it. `onStrokesFinished` runs on the UI thread,
 * immediately after `ACTION_UP`, in the same frame the dry layer has to redraw — rule 7
 * says nothing heavy runs there, and "no serialization" is on its list by name.
 *
 * So the UI thread does the cheap half: mint an [ObjectId], put the finished `Stroke` on a
 * queue, and return. Encoding happens later, off-thread, in [drain].
 *
 * That is safe without a lock because `androidx.ink`'s `Stroke` is **immutable** — its
 * inputs and its mesh are fixed once the stroke is finished — so a worker may read one
 * while the UI thread is drawing it. The queue itself is the only shared mutable state, and
 * it is a `ConcurrentLinkedQueue`.
 *
 * ## What is *not* deferred
 *
 * Ids and clocks are assigned on the UI thread, at the moment of the edit. They have to be:
 * the Lamport order is the order the user did things in, and assigning clocks on a worker
 * would let two edits swap places depending on how the encoder was scheduled.
 *
 * Erase and undo ops carry no geometry, so they are built on the spot and queued ready.
 */
internal class PageOpRecorder(
    private val nextObjectId: () -> ObjectId,
    private val nextOpId: () -> String,
    private val nextClock: () -> LamportClock,
) {

    /** An edit waiting to become an op. Either already an op, or a stroke still to encode. */
    private sealed interface Entry {
        val opId: String
        val clock: LamportClock

        class Ready(val op: PageOp) : Entry {
            override val opId: String get() = op.opId
            override val clock: LamportClock get() = op.clock
        }

        class UnencodedStroke(
            override val opId: String,
            override val clock: LamportClock,
            val objectId: ObjectId,
            val stroke: Stroke,
            val kind: InkBrushKind,
            val inputModel: InkInputModel,
            val z: FractionalKey,
            val transform: Matrix3,
        ) : Entry
    }

    /**
     * Completed gestures, oldest first. **One queue entry per gesture, not per op** — the grain
     * SPEC §4.6 requires for undo.
     *
     * Autosave drains on a two-second timer, so several gestures can be waiting at once. If
     * they were drained as one flat list they would become one undo entry, and undoing would
     * remove the last few seconds of writing instead of the last stroke.
     */
    private val queue = ConcurrentLinkedQueue<List<Entry>>()

    /** The gesture in progress. UI thread only, so it needs no synchronisation. */
    private var current: MutableList<Entry>? = null

    val isEmpty: Boolean get() = queue.isEmpty() && current.isNullOrEmpty()

    /**
     * The note's own id and clock generators, for ops built by the selection tool.
     *
     * ⚠️ **Exposed rather than duplicated.** A selection edit is a local gesture like any
     * other, and its ops must be stamped **on the UI thread at the moment of the edit** for the
     * reason this class exists (see the KDoc): the Lamport order is the order the user did
     * things in, and a clock assigned on a worker lets two edits swap places depending on how
     * the encoder was scheduled. So the selection controller stamps here and enqueues through
     * [record], rather than handing the surface a second pair of generators.
     */
    fun newOpId(): String = nextOpId()

    fun newClock(): LamportClock = nextClock()

    fun newObjectId(): ObjectId = nextObjectId()

    /** Opens a gesture. Nested calls are ignored — see `StrokeStore.beginEdit`. */
    fun beginGesture() {
        if (current == null) current = ArrayList()
    }

    /** Closes a gesture, making it available to [drain]. An empty gesture is discarded. */
    fun endGesture() {
        val gesture = current ?: return
        current = null
        if (gesture.isNotEmpty()) queue += gesture
    }

    /**
     * Add to the open gesture, or — for an edit outside any gesture, such as a page-metadata
     * change — as a gesture of its own.
     */
    private fun enqueue(entry: Entry) {
        val gesture = current
        if (gesture != null) gesture += entry else queue += listOf(entry)
    }

    /**
     * A finished stroke. **Called on the UI thread** — this must stay cheap.
     *
     * @return the [ObjectId] the stroke now has, so the dry layer can carry it and later
     *   ops can target it.
     */
    fun strokeFinished(stroke: Stroke, kind: InkBrushKind, inputModel: InkInputModel, z: FractionalKey): ObjectId {
        val id = nextObjectId()
        enqueue(
            Entry.UnencodedStroke(
                opId = nextOpId(),
                clock = nextClock(),
                objectId = id,
                stroke = stroke,
                kind = kind,
                inputModel = inputModel,
                z = z,
                transform = Matrix3.Identity,
            ),
        )
        return id
    }

    /**
     * ⭐ M8a — a copy of a stroke already on the page, at [id], offset by [transform].
     *
     * The `androidx.ink` [Stroke] is **shared with the original**, not copied: it is immutable
     * once finished, so two objects may point at one mesh, and the encode below reads it on a
     * worker exactly as it does for a freshly drawn stroke.
     *
     * That is the whole reason duplication does not build its op in `:core:model` alongside
     * every other selection edit: an `AddObject` carries a geometry blob, producing one means
     * running `androidx.ink`'s encoder, and CLAUDE.md rule 7 forbids that between the tap and
     * the next frame. The *decision* — the new z key and the offset — is `SelectionEdit`'s;
     * this is the mechanics.
     */
    fun strokeDuplicated(
        source: Stroke,
        id: ObjectId,
        kind: InkBrushKind,
        inputModel: InkInputModel,
        transform: Matrix3,
        z: FractionalKey,
    ) {
        enqueue(
            Entry.UnencodedStroke(
                opId = nextOpId(),
                clock = nextClock(),
                objectId = id,
                stroke = source,
                kind = kind,
                inputModel = inputModel,
                z = z,
                transform = transform,
            ),
        )
    }

    /** Whole-stroke erase, or a delete from any other tool. A tombstone, never a removal. */
    fun deleted(id: ObjectId) {
        enqueue(Entry.Ready(PageOp.DeleteObject(nextOpId(), nextClock(), id)))
    }

    /**
     * Partial erase — the **whole** region, absolutely (CLAUDE.md rule 8).
     *
     * Called once per stroke per gesture, at gesture end, not per eraser sample: a
     * scribble-erase at 240 Hz would otherwise emit thousands of ops each carrying a
     * slightly longer copy of the same region.
     */
    fun erasedRegionChanged(id: ObjectId, region: ErasedRegion?) {
        enqueue(Entry.Ready(PageOp.SetErasedRegion(nextOpId(), nextClock(), id, region.toAbsolute())))
    }

    /** An op built elsewhere — undo, redo, page metadata. */
    fun record(op: PageOp) {
        enqueue(Entry.Ready(op))
    }

    /**
     * Drain everything queued, encoding geometry as it goes.
     *
     * **Call this off the UI thread.** It is the autosave path: the two-second timer hops to
     * a worker, drains, and hands the ops to the session.
     *
     * Ops come back in the order they were queued, which is clock order, because clocks were
     * assigned when the edits happened.
     */
    fun drain(): List<List<PageOp>> {
        val gestures = ArrayList<List<PageOp>>()
        while (true) {
            val gesture = queue.poll() ?: break
            gestures += gesture.map { entry ->
                when (entry) {
                    is Entry.Ready -> entry.op

                    is Entry.UnencodedStroke -> PageOp.AddObject(
                        opId = entry.opId,
                        clock = entry.clock,
                        obj = StrokeObject(
                            id = entry.objectId,
                            geometry = StrokeGeometryCodec.encode(entry.stroke, entry.kind, entry.inputModel),
                            transform = entry.transform,
                            z = entry.z,
                        ),
                    )
                }
            }
        }
        return gestures
    }

    /**
     * Discard everything queued.
     *
     * Only for switching pages after a drain, where the queue is empty anyway. There is no
     * legitimate path that throws away recorded edits — that would be losing user data
     * (CLAUDE.md rule 1) — so this exists to make the empty case explicit, not to enable one.
     */
    fun clear() {
        queue.clear()
        current = null
    }
}

/**
 * The render cache → the durable form (SPEC §3.5).
 *
 * [ErasedRegion] holds capsules *and* a `Path` per constant-radius run; only the capsules
 * are durable. Five floats each, resolution-independent, replayable anywhere — a `Path`
 * is none of those things.
 */
internal fun ErasedRegion?.toAbsolute(): ErasedArea {
    val region = this ?: return ErasedArea.None
    return ErasedArea(
        region.capsules.map { EraseCapsule(it.fromX, it.fromY, it.toX, it.toY, it.radius) },
    )
}

/** The durable form → the render cache. */
internal fun ErasedArea.toRegion(): ErasedRegion? {
    if (isEmpty) return null
    val region = ErasedRegion()
    for (capsule in capsules) {
        region.add(ErasedRegion.Capsule(capsule.fromX, capsule.fromY, capsule.toX, capsule.toY, capsule.radius))
    }
    return region
}
