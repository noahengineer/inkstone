package dev.inkstone.core.storage

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Document
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageRef
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject

/**
 * SPEC §5.6 ⭐ — **Recovered mode is a REPLACE, never a union.**
 *
 * ## The failure this exists to prevent
 *
 * > A naive recovered import assigns new ObjectIds to everything. If it keeps the
 * > original `documentId` and syncs, the other device's grow-only union produces
 * > **every stroke twice** — the exact silent corruption this design exists to
 * > prevent.
 *
 * It is worth being precise about *why* reusing ids fixes it, because the fix is
 * not obvious and the wrong version of it looks identical:
 *
 * - The fold's payload rule is **first-write-wins per [ObjectId]** (`PageFolder`).
 * - So when a recovered `AddObject` for object *X* meets the peer's original
 *   `AddObject` for object *X*, the union has one object and the peer's original
 *   geometry — with its pressure intact — is the one that survives. Recovery
 *   costs nothing on the device that still has the good copy.
 * - Mint a fresh id instead and the union has two objects, both drawn, forever.
 *
 * That is the entire job of `/NM` on every annotation (SPEC §5.6) and of the
 * page-identity keys this milestone added beside it (see [RecoveredPage]).
 *
 * ## What "replace" means concretely
 *
 * This builds a **whole** [BundleContents] from the reconstruction alone. It is
 * given no previous ops and offers no way to pass any: the previous log is not
 * merged, appended to, or diffed — it is superseded, and the caller has already
 * moved the pre-recovery file to trash (SPEC §5.6 point 3, [NoteStore.replaceWithRecovered]).
 *
 * ## The clock bump
 *
 * SPEC §5.6 point 5: *"bump the Lamport counter past anything previously seen, so
 * recovered state wins on the next sync"*. What a device can honestly claim to
 * have *seen* is bounded — if the bundle is gone, so is the note's own record of
 * its highest counter. So the caller passes the best figure it has (the library
 * index, a surviving journal, or zero) and this adds [RECOVERY_BUMP] on top.
 *
 * The margin is a heuristic and is documented as one. It does not need to beat
 * every op a peer might ever write; it needs to beat the ops a peer wrote while
 * this device was unaware, and a million is several lifetimes of handwriting. It
 * matters only for the **registers** — presence, transform, style, erasure —
 * because the grow-only payload set is settled by first-write-wins on the id,
 * which is the part that actually prevents duplication.
 */
public object RecoveredImport {

    /**
     * How far past the last counter this device knows about a recovered op is
     * stamped. See the class KDoc — a margin, deliberately generous, not a proof.
     */
    public const val RECOVERY_BUMP: Long = 1_000_000L

    /**
     * Build the replacement note.
     *
     * @param highestKnownCounter the highest Lamport counter this device can still
     *   account for. Zero is a legitimate answer and simply makes the bump absolute.
     */
    public fun rebuild(
        marker: InkstoneMarker,
        pages: List<RecoveredPage>,
        title: String,
        deviceId: String,
        nowEpochMillis: Long,
        highestKnownCounter: Long,
        ids: RecoveryIds,
    ): RecoveredNote {
        val newOpId = ids.opId
        val newPageId = ids.pageId
        val newObjectId = ids.objectId
        require(pages.isNotEmpty()) { "a recovered note needs at least one page" }

        var counter = highestKnownCounter + RECOVERY_BUMP
        fun nextClock(): LamportClock = LamportClock(++counter, deviceId)

        var unidentifiedStrokes = 0
        val refs = ArrayList<PageRef>(pages.size)
        val contents = LinkedHashMap<PageId, PageContents>(pages.size)
        // Fresh keys only for pages whose own sort key did not survive; a page that
        // kept its key keeps its place in a peer's merged order (SPEC §4.3).
        val fallbackKeys = FractionalKey.sequence(pages.size)

        for ((index, page) in pages.withIndex()) {
            val pageId = page.pageId ?: newPageId()
            val sortKey = page.sortKey ?: fallbackKeys[index]
            refs += PageRef(pageId, sortKey)

            val ops = ArrayList<PageOp>(page.strokes.size + 1)
            // The page's own metadata first, so a page whose size came from a
            // `/Rotate 90` CropBox is that size again on the way back in.
            ops += PageOp.SetPageMeta(
                opId = newOpId(),
                clock = nextClock(),
                background = page.background,
                size = page.size,
            )
            var z = FractionalKey.Initial
            for (stroke in page.strokes) {
                if (stroke.id == null) unidentifiedStrokes++
                z = FractionalKey.between(z, null)
                ops += PageOp.AddObject(
                    opId = newOpId(),
                    clock = nextClock(),
                    obj = StrokeObject(
                        // The whole point (see the class KDoc): an id recovered from
                        // `/NM` collides with the peer's original and merges into it.
                        id = stroke.id ?: newObjectId(),
                        geometry = stroke.geometry,
                        z = z,
                    ),
                )
            }
            contents[pageId] = PageContents(PageCanvas.fixed(page.size), page.background, ops)
        }

        val document = Document(
            id = marker.documentId,
            title = title,
            pages = refs,
            schemaVersion = Document.SCHEMA_VERSION,
            // A recovery is a change like any other, and sync must see it as one.
            localRev = 1L,
            deviceId = deviceId,
            createdEpochMillis = nowEpochMillis,
            modifiedEpochMillis = nowEpochMillis,
        )

        return RecoveredNote(
            contents = BundleContents(
                document = document,
                pages = contents,
                highestClockCounter = counter,
                // Deliberately empty. Carrying entries forward is what a *merge*
                // does; there is nothing to carry, because the bundle those entries
                // would have come from is the one that did not survive.
                carried = emptyMap(),
            ),
            unidentifiedStrokes = unidentifiedStrokes,
            highestClockCounter = counter,
        )
    }
}

/**
 * The three generators a recovery needs, together.
 *
 * Together rather than as three parameters because they are used together and
 * *only* together — a recovery that minted page ids from one source and object ids
 * from another would be a recovery with two ideas about identity, which is the
 * failure this whole file is about.
 */
public class RecoveryIds(
    public val opId: () -> String,
    public val pageId: () -> PageId,
    public val objectId: () -> ObjectId,
)

/**
 * One page as reconstructed from the PDF.
 *
 * @property pageId from the page dictionary's `/INKS_PageId`, or null when it did
 *   not survive. **Null is not benign**: a page with a new id is a new page to a
 *   peer, and a merged order that keeps both (SPEC §4.3 — *"any page not present
 *   in a merged order is appended at the end, never dropped"*) doubles the
 *   notebook. It is the page-level form of the stroke-level failure SPEC §5.6
 *   describes, and it is why M4 writes page identity into the PDF at all.
 * @property strokes in render order. A null [RecoveredStroke.id] means the
 *   annotation carried no `/NM` of ours — a third party's markup, or a page that
 *   used the flattened overlay (SPEC §6.4).
 */
public class RecoveredPage(
    public val pageId: PageId?,
    public val sortKey: FractionalKey?,
    public val size: PageSize,
    public val background: Background,
    public val strokes: List<RecoveredStroke>,
)

/**
 * One stroke rebuilt from an `/Ink` annotation.
 *
 * [geometry] comes from the annotation's **`/InkList`**, not its `/AP`: the
 * appearance stream is a filled outline with no centre and no inputs, while
 * `/InkList` is the polyline the stroke was written along. That is exactly what
 * SPEC §6.4 wrote the second, "redundant" representation for — *"for interop, and
 * so other editors can select/move/delete it"* turns out to also be the only
 * thing that makes recovery produce ink rather than a picture of ink.
 *
 * Uniform width, hence [RecoveryLoss.PRESSURE_AND_WIDTH].
 */
public class RecoveredStroke(public val id: ObjectId?, public val geometry: StrokeGeometry)

/**
 * @property unidentifiedStrokes how many strokes had no recoverable [ObjectId].
 *   Non-zero means those strokes **will** appear twice if a peer still holds the
 *   originals — the union has nothing to merge them onto. The caller must say so
 *   rather than let it be discovered later.
 */
public class RecoveredNote(
    public val contents: BundleContents,
    public val unidentifiedStrokes: Int,
    public val highestClockCounter: Long,
)
