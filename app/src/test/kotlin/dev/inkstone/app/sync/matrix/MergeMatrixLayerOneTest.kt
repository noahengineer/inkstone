package dev.inkstone.app.sync.matrix

import dev.inkstone.core.model.ConnectorEnd
import dev.inkstone.core.model.ConnectorEndState
import dev.inkstone.core.model.ConnectorRouting
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.ShapeRecord
import dev.inkstone.core.model.recordOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC §13.2 rows 1 to 7 — **layer 1, end to end.**
 *
 * The convergence claims themselves are also made in `:core:storage`'s `NoteMergeTest`, over two
 * `NoteStore`s and no network, and that is where they are cheapest to read. What these add is the
 * whole pipe: a real published PDF with a real XMP marker and a real embedded bundle, a real
 * `If-None-Match: *` PUT, a real PROPFIND walk, a real download, a real
 * `PulledPdfExtractor.extract`, a real `NoteMerge.merge`, and a real bundle rewrite — twice, once
 * per device. Every one of those steps is a place a correct merge can be delivered to the wrong
 * note, or not delivered at all, and none of them exists in the JVM half.
 *
 * Rows 2, 3, 5 and 7 are the design-bug rows in this file (ROADMAP M6 names them): each one is a
 * mechanism whose failure mode is *all ops present, no error, two devices permanently disagreeing*.
 */
class MergeMatrixLayerOneTest {

    /**
     * Row 1 — the grow-only union, and the property every other row rests on.
     *
     * Also the traffic claim: one PROPFIND pair and exactly **one** GET per direction. A second
     * GET would mean the ETag the merge stored was not the ETag of the bytes it merged, which is
     * the bug `WebDavClient.download` exists to prevent.
     */
    @Test
    fun `row 1 - both devices strokes survive in both directions with one download each`() = matrixRow(
        row = "row 1 (grow-only union)",
        proven = "the union over a real published PDF round trip through a real WebDAV client",
        notProven = "that Nextcloud's own storage layer leaves the published bytes alone",
    ) { devices ->
        val id = devices.share("Row1")
        val page = devices.a.firstPage(id)

        // Offline on both — no request is made by an edit (CLAUDE.md rule 4).
        val fromA = devices.a.drawStroke(id)
        val fromB = devices.b.drawStroke(id)

        devices.a.drain()
        devices.resetCounts()

        val intoB = devices.b.pull()
        assertEquals(1, intoB.merged, "B did not merge A's file")
        devices.assertRequests("GET", 1, "row 1: B downloaded the file more than once")
        devices.b.drain()
        devices.resetCounts()

        val intoA = devices.a.pull()
        assertEquals(1, intoA.merged, "A did not merge B's file")
        devices.assertRequests("GET", 1, "row 1: A downloaded the file more than once")

        for (device in listOf(devices.a, devices.b)) {
            val objects = device.page(id, page).objects.map { it.id }
            assertTrue(fromA in objects, "${device.name} lost A's stroke")
            assertTrue(fromB in objects, "${device.name} lost B's stroke")
            assertTrue(device.attention().isEmpty(), "${device.name} raised attention for an ordinary merge")
        }
    }

    /**
     * ⚑ Row 2 — `Presence` is an LWW register, keyed independently of when `AddObject` arrived.
     *
     * A delete at a higher clock beats a transform, on both devices, and leaves **no ghost**: the
     * object is absent from `objects`, present in `deleted`, and its ops are all still in the log
     * (rule 1 — a losing register never costs bytes).
     */
    @Test
    fun `row 2 - a concurrent delete beats a transform and leaves no ghost on either device`() = matrixRow(
        row = "row 2 (presence as a register)",
        proven = "delete-beats-transform through the real pipe on both devices",
        notProven = "nothing extra — this row is pure fold, and the server only carries it",
    ) { devices ->
        val id = devices.share("Row2")
        val page = devices.a.firstPage(id)
        val target = devices.a.drawStroke(id)
        devices.a.drain()
        devices.b.pull()
        devices.b.drain()
        devices.a.pull()

        // Concurrent, and deliberately ordered: the delete is later, so it must win everywhere.
        devices.a.transformAtCounter(id, target, COLLISION_COUNTER, Matrix3.scale(2f, 2f))
        devices.b.deleteObjectAt(id, target, counter = COLLISION_COUNTER + 5L)

        devices.syncBothWays()

        for (device in listOf(devices.a, devices.b)) {
            val folded = device.page(id, page)
            assertNull(folded.find(target), "${device.name} kept a ghost of a deleted object")
            assertTrue(target in folded.deleted, "${device.name} lost the tombstone")
            assertTrue(folded.objects.none { it.id == target }, "${device.name} still lists the object")
            val ops = device.opsOn(id, page)
            assertTrue(
                ops.any { it is PageOp.SetTransform && it.target == target },
                "${device.name} discarded the losing transform op — rule 1 forbids it",
            )
        }
    }

    /**
     * ⚑ Row 3 — **`Register.write`'s `deviceId` tiebreak, which was broken until M6.**
     *
     * Both devices write the same register at the *same counter*. `LamportClock.compareTo` breaks
     * that by `deviceId`, and `Register.write` keeps the incumbent when the clocks compare equal —
     * so if both devices stamped the same id (M2 read it out of the note's manifest, which meant
     * they did), the winner is whichever op the local fold happened to see first, and the two
     * devices disagree **forever, with all ops present and no error**.
     *
     * Two assertions, and the second is the one that would have caught the original bug: the
     * resolved matrix is identical on both devices, and no two ops in the merged log share a
     * `LamportClock`.
     *
     * ⭐ **Two things changed in M8a.** The ops now come from `SelectionEdit.transform` — the
     * function the selection tool itself calls — rather than being built by the helper, because
     * a row that builds its own op tests the row. And the gestures are **non-uniform**: a
     * `scale(s, s)` is a scalar multiple of the identity and therefore *commutes* with rotation,
     * so the composed-instead-of-resolved failure this row exists to catch would come out
     * identical to the correct answer. The row could not have failed for its own reason.
     */
    @Test
    fun `row 3 - two concurrent transforms at one counter resolve identically and never to a product`() = matrixRow(
        row = "row 3 (Lamport tiebreak)",
        proven = "an equal-counter collision resolving to one input on both devices, through the app's own op builder",
        notProven = "nothing extra — the tiebreak is local, and this is the row a shared deviceId broke",
    ) { devices ->
        val id = devices.share("Row3")
        val page = devices.a.firstPage(id)
        val target = devices.a.drawStroke(id)
        devices.a.drain()
        devices.b.pull()
        devices.b.drain()
        devices.a.pull()

        // Non-uniform, and not commuting with each other: a squash and a quarter-turn. Their
        // product is skewed, so "composed rather than resolved" is a state this row can now
        // actually reach and therefore actually rule out.
        val fromA = devices.a.transformAtCounter(id, target, COLLISION_COUNTER, Matrix3.scale(2f, 1f))
        val fromB = devices.b.transformAtCounter(id, target, COLLISION_COUNTER, Matrix3.rotate(QUARTER_TURN))

        devices.syncBothWays()

        val onA = assertNotNull(devices.a.page(id, page).find(target), "A lost the object").transform
        val onB = assertNotNull(devices.b.page(id, page).find(target), "B lost the object").transform
        assertEquals(onA, onB, "the two devices disagree about an absolute register — the tiebreak degenerated")
        assertTrue(
            onA == fromA || onA == fromB,
            "the resolved matrix is neither input — the ops were composed rather than resolved (CLAUDE.md rule 8)",
        )
        assertFalse(
            onA.isSkewed(),
            "the resolved matrix is skewed, which is what SPEC §4.2's worked example produces from a delta log",
        )

        for (device in listOf(devices.a, devices.b)) {
            val clocks = device.opsOn(id, page).map { it.clock }
            assertEquals(
                clocks.size,
                clocks.toSet().size,
                "${device.name} has two ops sharing a Lamport clock - the deviceId tiebreak is not distinguishing",
            )
        }
    }

    /** Row 4 — style and transform are independent registers, so both concurrent writes apply. */
    @Test
    fun `row 4 - a concurrent style and transform on one object both survive`() = matrixRow(
        row = "row 4 (independent registers)",
        proven = "two different registers on one object both applying on both devices",
        notProven = "nothing extra",
    ) { devices ->
        val id = devices.share("Row4")
        val page = devices.a.firstPage(id)
        val target = devices.a.drawStroke(id)
        devices.a.drain()
        devices.b.pull()
        devices.b.drain()
        devices.a.pull()

        val style = devices.a.setStyle(id, target, colorArgb = RED)
        val transform = devices.b.transform(id, target, Matrix3.scale(2f, 2f))

        devices.syncBothWays()

        for (device in listOf(devices.a, devices.b)) {
            val obj = assertNotNull(device.page(id, page).find(target), "${device.name} lost the object")
            assertEquals(style, obj.style, "${device.name} dropped the concurrent style")
            assertEquals(transform, obj.transform, "${device.name} dropped the concurrent transform")
        }
    }

    /**
     * ⚑ Row 5 — a page added on each device, and **the page that survives a materialize.**
     *
     * `NoteSession.merge` will accept ops for a page `document.pages` has never heard of;
     * `BundleWriter` then skips any page not in the order and `materialize` truncates the journal
     * immediately afterwards. So a remote page merged without its `PageRef` is destroyed
     * permanently, with a valid PDF and the right page count as the only evidence. `NoteMerge`
     * merges the document *first* for exactly this reason, and the assertion below is the one that
     * notices if it ever stops: every page is re-read from the bundle after the round trip.
     */
    @Test
    fun `row 5 - a page added on each device gives three live pages that survive materialize`() = matrixRow(
        row = "row 5 (page order union)",
        proven = "three pages on both devices after a real publish-upload-download-merge-republish",
        notProven = "nothing extra",
    ) { devices ->
        val id = devices.share("Row5")
        val onlyOnA = devices.a.addPage(id)
        val onlyOnB = devices.b.addPage(id)

        devices.syncBothWays()

        for (device in listOf(devices.a, devices.b)) {
            val document = assertNotNull(device.document(id), "${device.name} lost the note")
            assertEquals(3, document.livePages.size, "${device.name} does not have three pages")
            assertTrue(onlyOnA.value in document.liveIds, "${device.name} lost the page A added")
            assertTrue(onlyOnB.value in document.liveIds, "${device.name} lost the page B added")
            // Re-read through the *bundle*, which is what `materialize` just rewrote. A page in
            // `document.pages` whose contents `BundleWriter` skipped fails here and nowhere else.
            for (ref in document.livePages) {
                assertEquals(ref.id, device.page(id, ref.id).pageId, "${device.name} cannot load ${ref.id.value}")
            }
        }
        assertEquals(devices.a.pageOrder(id), devices.b.pageOrder(id), "the two devices disagree about page order")
    }

    /** Row 6 — two different reorders of four pages converge to one byte-identical order. */
    @Test
    fun `row 6 - two different reorders of four pages converge to one order`() = matrixRow(
        row = "row 6 (deterministic reorder)",
        proven = "an identical page order on both devices after conflicting reorders",
        notProven = "nothing extra",
    ) { devices ->
        val id = devices.share("Row6", pageCount = 4)
        val pages = assertNotNull(devices.a.document(id), "no note").livePages

        devices.a.movePage(id, pages[3].id, toIndex = 0)
        devices.b.movePage(id, pages[0].id, toIndex = 3)

        devices.syncBothWays()

        assertEquals(4, assertNotNull(devices.a.document(id)).livePages.size, "A gained or lost a page")
        assertEquals(4, assertNotNull(devices.b.document(id)).livePages.size, "B gained or lost a page")
        assertEquals(
            devices.a.pageOrder(id),
            devices.b.pageOrder(id),
            "the reorders did not converge - the tiebreak is not deterministic",
        )
    }

    /**
     * ⚑ Row 7 — SPEC §3.5's erase is an **area beside the stroke**, not a cut in it.
     *
     * The failure this rules out is a sample-based erase, which removes whole samples at full
     * stroke width and therefore has to split one stroke into fragments. Under concurrency those
     * fragments are new objects, so a peer's transform of the original lands on nothing and the
     * object count differs between the two devices. With `SetErasedRegion` as an absolute
     * register, there is still exactly one object and it carries both.
     *
     * ⚠️ **ROADMAP M8a's prompt asks for the fragment model in as many words** — *"partial erase
     * splits a stroke and must be expressed as `DeleteObject(original) + AddObject(fragment1) +
     * AddObject(fragment2)`"* — and SPEC §3.5 supersedes it: *"Nothing splits."* The
     * `assertEquals(1, folded.objects.size)` below is the automated guard on that, and it is the
     * assertion that would fail if anyone implemented the prompt. Do not weaken it.
     *
     * ⭐ **What M8a added: the erase and the transform must commute.** They are different
     * registers on one object, so a device that erases before a peer's move arrives and one that
     * erases after must finish identically — which is true only because the region is stored in
     * the object's **own** space. Had the capsules been page-space, the erase would have had to
     * be re-expressed when the transform arrived, nothing does that, and the two devices would
     * hold the same numbers meaning different places. The second half of this row runs the two
     * orders on the two devices and asserts they agree.
     */
    @Test
    fun `row 7 - a partial erase and a concurrent transform converge without duplicating the stroke`() = matrixRow(
        row = "row 7 (area-based erase)",
        proven = "one object carrying both an erased region and a transform, identical whichever order they arrived",
        notProven = "that an eraser produces the right region, or that erasing a *moved* stroke maps the " +
            "sweep into its own space — there is no ink in a JVM test; both are :android:ink's " +
            "SelectionEraseTest, on a device",
    ) { devices ->
        val id = devices.share("Row7")
        val page = devices.a.firstPage(id)
        val target = devices.a.drawStroke(id)
        devices.a.drain()
        devices.b.pull()
        devices.b.drain()
        devices.a.pull()

        // Concurrent and in opposite orders locally: A erases and only then hears about the
        // move; B moves and only then hears about the erase.
        val erased = devices.a.erase(id, target)
        val transform = devices.b.transform(id, target, Matrix3.scale(2f, 2f))

        devices.syncBothWays()

        for (device in listOf(devices.a, devices.b)) {
            val folded = device.page(id, page)
            assertEquals(1, folded.objects.size, "${device.name} has more than one object - the erase fragmented it")
            val obj = assertNotNull(folded.find(target), "${device.name} lost the stroke it erased part of")
            assertEquals(erased, obj.erased, "${device.name} dropped the erased region")
            assertEquals(transform, obj.transform, "${device.name} dropped the concurrent transform")
        }

        // The capsules are byte-identical on both devices — neither re-expressed them when the
        // transform arrived, which is what "the region is in the object's own space" means for
        // the merge. A page-space region would have had to move and does not.
        val onA = assertNotNull(devices.a.page(id, page).find(target))
        val onB = assertNotNull(devices.b.page(id, page).find(target))
        assertEquals(
            onA.erased.capsules,
            onB.erased.capsules,
            "the two devices hold different capsules — the region was re-expressed by one of them",
        )
        assertEquals(onA.transform, onB.transform, "the two devices disagree about the transform")
    }

    /**
     * ⭐ **Row 4b — a connector's attachment survives a sync round trip, and a peer's move of the
     * shape it points at sends nothing about the route.**
     *
     * ROADMAP M8b's fourth acceptance criterion, and the companion the table did not ask for. It
     * asserts three separable things, because each fails differently:
     *
     * 1. **The binding arrives.** A `SetTextContent` carrying a `ShapeRecord` is an ordinary op on
     *    an ordinary `ShapeObject`, so this is really the assertion that SPEC §4.8.2's choice of
     *    register survives the transport — which is the whole reason that choice was available.
     * 2. **The route is identical on both devices without either having sent one.** Every input to
     *    it is a value the merge already converges on, so the two agree with **zero** bytes about
     *    the route exchanged (SPEC §4.8.3).
     * 3. ⭐ **Moving the target writes no record.** The op count for `SetTextContent` is unchanged
     *    across a move that visibly re-routes the connector on both devices. This is the assertion
     *    that would fail if a future change ever "helpfully" persisted a route — and it is the one
     *    an eyeball test cannot make, because a persisted route looks *correct* right up until two
     *    devices write one concurrently.
     */
    @Test
    fun `row 4b - a connector attachment survives a round trip and a peer move sends no route`() = matrixRow(
        row = "row 4b (connector attachment)",
        proven = "a binding crossing the wire, a route derived identically on both sides, and no op for a reroute",
        notProven = "anything about how the route is drawn — that is ShapeSnapController and the painter",
    ) { devices ->
        val id = devices.share("Row4b")
        val page = devices.a.firstPage(id)
        val from = devices.a.addShape(id, ShapeKind.RECTANGLE, PageRect(0f, 0f, BOX, BOX))
        val to = devices.a.addShape(id, ShapeKind.RECTANGLE, PageRect(FAR, 0f, FAR + BOX, BOX))
        val connector = devices.a.addShape(
            id,
            ShapeKind.ARROW,
            PageRect(BOX, BOX / 2f, FAR, BOX / 2f),
            ShapeRecord(endA = ConnectorEnd.Attached(from), endB = ConnectorEnd.Attached(to)),
        )
        devices.syncBothWays()

        // 1 — the binding crossed.
        val onB = devices.b.page(id, page)
        assertEquals(
            ShapeRecord(endA = ConnectorEnd.Attached(from), endB = ConnectorEnd.Attached(to)),
            onB.recordOf(connector),
            "device-b did not receive the attachment",
        )

        // 2 — and both devices derive the same route from it, having exchanged nothing about it.
        assertEquals(routeOf(devices.a.page(id, page), connector), routeOf(onB, connector))

        // 3 — b moves the box; a re-routes without either writing a record.
        val recordsBefore = devices.a.opsOn(id, page).count { it is PageOp.SetTextContent }
        devices.b.transform(id, to, Matrix3.translate(0f, FAR))
        devices.syncBothWays()

        val movedOnA = devices.a.page(id, page)
        assertEquals(
            recordsBefore,
            devices.a.opsOn(id, page).count { it is PageOp.SetTextContent },
            "a reroute wrote a record — the route is being persisted (SPEC §4.8.3)",
        )
        val rerouted = assertNotNull(routeOf(movedOnA, connector))
        assertTrue(rerouted.a.isAttached && rerouted.b.isAttached, "an end detached across the move")
        assertEquals(rerouted, routeOf(devices.b.page(id, page), connector), "the devices disagree about the route")
        assertTrue(rerouted.b.point.y > BOX, "the connector did not follow the box that moved")
    }

    /**
     * ⚑ Row 4c — deleting the shape a connector points at.
     *
     * ROADMAP's third criterion, *"a defined state (detached, not dangling or crashing)"*, across
     * the wire. The record is **untouched** by the delete — detachment is derived from the fold,
     * never stored (SPEC §4.8.4) — and both devices agree on the fallback geometry, so an undo of
     * the delete on either side re-attaches with no op at all.
     */
    @Test
    fun `row 4c - deleting a connected shape detaches without writing anything`() = matrixRow(
        row = "row 4c (derived detachment)",
        proven = "a tombstoned target detaching identically on both devices with the record unchanged",
        notProven = "the not-yet-arrived case, which ConnectorTest covers — it needs no server",
    ) { devices ->
        val id = devices.share("Row4c")
        val page = devices.a.firstPage(id)
        val from = devices.a.addShape(id, ShapeKind.RECTANGLE, PageRect(0f, 0f, BOX, BOX))
        val to = devices.a.addShape(id, ShapeKind.RECTANGLE, PageRect(FAR, 0f, FAR + BOX, BOX))
        val connector = devices.a.addShape(
            id,
            ShapeKind.ARROW,
            PageRect(BOX, BOX / 2f, FAR, BOX / 2f),
            ShapeRecord(endA = ConnectorEnd.Attached(from), endB = ConnectorEnd.Attached(to)),
        )
        devices.syncBothWays()

        val record = devices.a.page(id, page).recordOf(connector)
        devices.b.deleteObjectAt(id, to, COLLISION_COUNTER)
        devices.syncBothWays()

        for (device in listOf(devices.a, devices.b)) {
            val folded = device.page(id, page)
            assertEquals(record, folded.recordOf(connector), "${device.name} rewrote the record on a delete")
            val routed = assertNotNull(routeOf(folded, connector))
            assertEquals(
                ConnectorEndState.DETACHED_TOMBSTONED,
                routed.b.state,
                "${device.name} did not derive the detachment",
            )
            assertTrue(routed.a.isAttached, "${device.name} detached the end that was untouched")
        }
    }

    /** The route as both devices derive it — the thing that is never sent. */
    private fun routeOf(page: dev.inkstone.core.model.FoldedPage, connector: ObjectId) = ConnectorRouting.route(
        assertNotNull(page.find(connector) as? ShapeObject, "the connector is not on the page"),
        page.recordOf(connector),
        page,
    )

    private companion object {
        /**
         * A counter far above anything the fixture's own gestures reach, so "the same counter on
         * both devices" is a fact about the test rather than about how many strokes were drawn.
         */
        const val COLLISION_COUNTER = 10_000L
        const val RED = 0xFFCC0000.toInt()

        /** Two boxes far enough apart that the connector between them has an unambiguous direction. */
        const val BOX = 100f
        const val FAR = 400f

        /** π/2, as a literal so the test has no arithmetic of its own to be wrong about. */
        const val QUARTER_TURN = 1.5707964f
    }
}
