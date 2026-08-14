package dev.inkstone.app.sync.matrix

import dev.inkstone.core.model.ConnectorEnd
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ListStyle
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.ShapeEdit
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapeRecord
import dev.inkstone.core.model.TextAlign
import dev.inkstone.core.model.TextFont
import dev.inkstone.core.model.recordOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/M8-CONNECTORS.md §7 item 5 and docs/M8-TEXT.md §6 item 9 — the two **record registers**
 * under concurrency, through the real pipe.
 *
 * The fold-level claims are already made in `:core:model` (`ConnectorTest`, `ObjectFormatTest`),
 * over one fold and no network, and that is where they are cheapest to read. What these rows add
 * is what `MergeMatrixLayerOneTest`'s KDoc lists: a real published PDF, a real PUT, a real
 * PROPFIND walk, a real download, a real merge and a real bundle rewrite — once per device.
 * SPEC §4.8.2 *states* the cost of one register holding a whole `ShapeRecord` — a concurrent fill
 * change and endpoint re-attachment lose one of the two — and until row 4d that cost was argued
 * rather than demonstrated.
 *
 * The two collision rows pin their counters to [COLLISION_COUNTER], for row 3's reason: two
 * devices folding the same log *usually* collide anyway, and "usually" is not a test.
 *
 * ⚠️ **Where this fixture is more generous than production — said here so a green row does not
 * imply more (CLAUDE.md definition-of-done #6):**
 *
 * - **The `SetFormat` op is hand-built** ([setFormat] / [setFormatAtCounter]). Production's only
 *   writers of that op today are the copy paths (`copiedObjectOps`), the snapshot writer and the
 *   render-state replays — ROADMAP M8c's text-box and image tools are not built (docs/M8-TEXT.md
 *   §7), so there is no gesture-level builder to route through the way [Device.transform] routes
 *   through `SelectionEdit`. The same caveat `MatrixOps.erase` carries, for the same reason.
 * - **The format's target is a `ShapeObject`**, because nothing in production can create a
 *   `TextBoxObject` either. The register is object-keyed and the fold treats every kind alike
 *   (`PageFolder.absorb` — `write(formats, op.target, …)`), so the merge claims here are
 *   kind-independent; what no row in this class proves is anything about how a format is
 *   *rendered*, which stays owed to M8c's feature work.
 */
class MergeMatrixRecordTest {

    /**
     * ⚑ Row 4d — SPEC §4.8.2's stated cost, demonstrated: a `ShapeRecord` resolves LWW **whole**.
     *
     * A recolours the connector's fill; B concurrently re-attaches an end. One of the two edits is
     * lost — by design — and the assertions are that both devices lose the *same* one, that the
     * winner is exactly one *input* (never a field-wise union carrying both the fill and the new
     * end), and that the losing op stays in the log (rule 1 — a losing register never costs bytes).
     */
    @Test
    fun `row 4d - two concurrent record writes resolve to one whole record on both devices`() = matrixRow(
        row = "row 4d (record LWW whole)",
        proven = "two concurrent ShapeRecord writes resolving to one whole input on both devices through the real pipe",
        notProven = "nothing extra - the resolution is the fold's, and the server only carries it",
    ) { devices ->
        val id = devices.share("Row4d")
        val page = devices.a.firstPage(id)
        val from = devices.a.addShape(id, ShapeKind.RECTANGLE, PageRect(0f, 0f, BOX, BOX))
        val to = devices.a.addShape(id, ShapeKind.RECTANGLE, PageRect(FAR, 0f, FAR + BOX, BOX))
        val third = devices.a.addShape(id, ShapeKind.RECTANGLE, PageRect(FAR, FAR, FAR + BOX, FAR + BOX))
        val connector = devices.a.addShape(
            id,
            ShapeKind.ARROW,
            PageRect(BOX, BOX / 2f, FAR, BOX / 2f),
            ShapeRecord(endA = ConnectorEnd.Attached(from), endB = ConnectorEnd.Attached(to)),
        )
        devices.syncBothWays()

        // Concurrent, at one pinned counter. A field-wise merge would produce a record carrying
        // both the fill and the re-attached end - a third value the either-input assertion below
        // rules out.
        val fromA = devices.a.writeRecordAtCounter(
            id,
            connector,
            COLLISION_COUNTER,
            ShapeRecord(
                fillArgb = GREEN,
                fillOpacity = FILL_OPACITY,
                endA = ConnectorEnd.Attached(from),
                endB = ConnectorEnd.Attached(to),
            ),
        )
        val fromB = devices.b.writeRecordAtCounter(
            id,
            connector,
            COLLISION_COUNTER,
            ShapeRecord(endA = ConnectorEnd.Attached(from), endB = ConnectorEnd.Attached(third)),
        )

        devices.syncBothWays()

        val onA = devices.a.page(id, page).recordOf(connector)
        val onB = devices.b.page(id, page).recordOf(connector)
        assertEquals(onA, onB, "the two devices disagree about the record - the register did not converge")
        assertTrue(
            onA == fromA || onA == fromB,
            "the resolved record is neither input - the register was merged field-wise rather than resolved whole",
        )

        for (device in listOf(devices.a, devices.b)) {
            val texts = device.opsOn(id, page)
                .filterIsInstance<PageOp.SetTextContent>()
                .filter { it.target == connector }
                .map { it.text }
            assertTrue(fromA.encode() in texts, "${device.name} discarded A's record op - rule 1 forbids it")
            assertTrue(fromB.encode() in texts, "${device.name} discarded B's record op - rule 1 forbids it")
            assertTrue(device.attention().isEmpty(), "${device.name} raised attention for a register resolution")
        }
    }

    /**
     * Row 4e — the record and the transform are independent registers, so both concurrent writes
     * apply. Row 4's claim for style and transform, made for the register M8b added.
     */
    @Test
    fun `row 4e - a concurrent record and transform on one shape both survive`() = matrixRow(
        row = "row 4e (record and transform independent)",
        proven = "the record register and the transform register on one shape both applying on both devices",
        notProven = "nothing extra",
    ) { devices ->
        val id = devices.share("Row4e")
        val page = devices.a.firstPage(id)
        val shape = devices.a.addShape(id, ShapeKind.RECTANGLE, PageRect(0f, 0f, BOX, BOX))
        devices.syncBothWays()

        val record = devices.a.writeRecord(id, shape, ShapeRecord(fillArgb = GREEN, fillOpacity = FILL_OPACITY))
        val transform = devices.b.transform(id, shape, Matrix3.translate(FAR, 0f))

        devices.syncBothWays()

        for (device in listOf(devices.a, devices.b)) {
            val folded = device.page(id, page)
            val obj = assertNotNull(folded.find(shape), "${device.name} lost the shape")
            assertEquals(record, folded.recordOf(shape), "${device.name} dropped the concurrent record")
            assertEquals(transform, obj.transform, "${device.name} dropped the concurrent transform")
        }
    }

    /**
     * ⚑ Row 2b — a record write against a concurrently deleted shape. The tombstone wins on both
     * devices, nothing resurrects, and nothing crashes — SPEC §4.2's *"ops targeting a tombstone
     * are no-ops"*, matching what row 2 established for the transform register.
     */
    @Test
    fun `row 2b - a concurrent delete beats a record write and leaves no ghost on either device`() = matrixRow(
        row = "row 2b (delete beats record)",
        proven = "delete-beats-record through the real pipe on both devices",
        notProven = "nothing extra - this row is pure fold, and the server only carries it",
    ) { devices ->
        val id = devices.share("Row2b")
        val page = devices.a.firstPage(id)
        val target = devices.a.addShape(id, ShapeKind.RECTANGLE, PageRect(0f, 0f, BOX, BOX))
        devices.syncBothWays()

        // Concurrent, and deliberately ordered as row 2 orders it: the delete is later, so it
        // must win everywhere.
        val record = devices.a.writeRecordAtCounter(
            id,
            target,
            COLLISION_COUNTER,
            ShapeRecord(fillArgb = GREEN, fillOpacity = FILL_OPACITY),
        )
        devices.b.deleteObjectAt(id, target, counter = COLLISION_COUNTER + 5L)

        devices.syncBothWays()

        for (device in listOf(devices.a, devices.b)) {
            val folded = device.page(id, page)
            assertNull(folded.find(target), "${device.name} kept a ghost of a deleted shape")
            assertTrue(target in folded.deleted, "${device.name} lost the tombstone")
            assertTrue(folded.objects.none { it.id == target }, "${device.name} still lists the shape")
            val texts = device.opsOn(id, page)
                .filterIsInstance<PageOp.SetTextContent>()
                .filter { it.target == target }
                .map { it.text }
            assertTrue(record.encode() in texts, "${device.name} discarded the losing record op - rule 1 forbids it")
        }
    }

    /**
     * ⚑ Row 4f — SPEC §4.9.2's register resolves LWW **whole**, like the record next door.
     *
     * The two formats touch disjoint fields on purpose: a field-wise union would produce a third
     * record string distinguishable from both inputs, which is exactly what the either-input
     * assertion rules out.
     */
    @Test
    fun `row 4f - two concurrent format writes resolve to one whole format on both devices`() = matrixRow(
        row = "row 4f (format LWW whole)",
        proven = "two concurrent SetFormat writes resolving to one whole input on both devices through the real pipe",
        notProven = "nothing extra - the resolution is the fold's, and the server only carries it",
    ) { devices ->
        val id = devices.share("Row4f")
        val page = devices.a.firstPage(id)
        val target = devices.a.addShape(id, ShapeKind.RECTANGLE, PageRect(0f, 0f, BOX, BOX))
        devices.syncBothWays()

        val fromA = devices.a.setFormatAtCounter(
            id,
            target,
            COLLISION_COUNTER,
            ObjectFormat(font = TextFont.SERIF, sizePt = FORMAT_SIZE_PT),
        )
        val fromB = devices.b.setFormatAtCounter(
            id,
            target,
            COLLISION_COUNTER,
            ObjectFormat(align = TextAlign.CENTER, listLevel = LIST_LEVEL, listStyle = ListStyle.BULLET),
        )

        devices.syncBothWays()

        val onA = devices.a.page(id, page).formatOf(target)
        val onB = devices.b.page(id, page).formatOf(target)
        assertEquals(onA, onB, "the two devices disagree about the format - the register did not converge")
        assertTrue(
            onA == fromA || onA == fromB,
            "the resolved format is neither input - the register was merged field-wise rather than resolved whole",
        )

        for (device in listOf(devices.a, devices.b)) {
            val records = device.opsOn(id, page)
                .filterIsInstance<PageOp.SetFormat>()
                .filter { it.target == target }
                .map { it.record }
            assertTrue(fromA in records, "${device.name} discarded A's format op - rule 1 forbids it")
            assertTrue(fromB in records, "${device.name} discarded B's format op - rule 1 forbids it")
        }
    }

    /**
     * Row 4g — `SetFormat` and `SetTextContent` are independent registers, so both concurrent
     * writes apply. This independence is SPEC §4.9.2's whole argument for a second register: a
     * restyle must not be able to discard the other register's value.
     */
    @Test
    fun `row 4g - a concurrent format and record on one object both survive`() = matrixRow(
        row = "row 4g (format and record independent)",
        proven = "the SetFormat and SetTextContent registers on one object both applying on both devices",
        notProven = "anything about how a format is rendered - nothing in the app can create a text box yet",
    ) { devices ->
        val id = devices.share("Row4g")
        val page = devices.a.firstPage(id)
        val target = devices.a.addShape(id, ShapeKind.RECTANGLE, PageRect(0f, 0f, BOX, BOX))
        devices.syncBothWays()

        val record = devices.a.writeRecord(id, target, ShapeRecord(fillArgb = GREEN, fillOpacity = FILL_OPACITY))
        val format = devices.b.setFormat(id, target, ObjectFormat(font = TextFont.MONO))

        devices.syncBothWays()

        for (device in listOf(devices.a, devices.b)) {
            val folded = device.page(id, page)
            assertEquals(record, folded.recordOf(target), "${device.name} dropped the concurrent record")
            assertEquals(format, folded.formatOf(target), "${device.name} dropped the concurrent format")
        }
    }

    private companion object {
        /**
         * A counter far above anything the fixture's own gestures reach, so "the same counter on
         * both devices" is a fact about the test rather than about how many edits were made.
         */
        const val COLLISION_COUNTER = 10_000L

        /** Two boxes far enough apart that the connector between them has an unambiguous direction. */
        const val BOX = 100f
        const val FAR = 400f

        const val GREEN = 0xFF00AA44.toInt()
        const val FILL_OPACITY = 0.35f

        /** Non-default format fields, disjoint between row 4f's two writers. */
        const val FORMAT_SIZE_PT = 18f
        const val LIST_LEVEL = 2
    }
}

/**
 * A record write at an **explicit** counter, so two devices can be made to collide exactly.
 *
 * Through `ShapeEdit.writeRecord` — the production op builder — with the clock generator pinned
 * the way [transformAtCounter] pins `SelectionEdit`'s, and for the same reason: row 4d's premise
 * is *equal* Lamport counters, and "usually" is not a test.
 */
private suspend fun Device.writeRecordAtCounter(
    noteId: DocumentId,
    target: ObjectId,
    counter: Long,
    record: ShapeRecord,
): ShapeRecord = edit(noteId) { session, page ->
    // Read before the lambda for the reason transformAtCounter gives: `deviceId()` suspends, and
    // the clock generator is a plain `() -> LamportClock`.
    val device = deviceId()
    session.apply(
        page,
        listOf(
            ShapeEdit.writeRecord(
                target = target,
                record = record,
                nextOpId = session::nextOpId,
                nextClock = { LamportClock(counter, device) },
            ),
        ),
    )
    record
}

/**
 * SPEC §4.9.2 — an absolute presentation record, as one `SetFormat` op.
 *
 * ⚠️ **Hand-built, and it is the one place this file is more generous than production** — say so
 * rather than let a green row imply otherwise (the discipline `MatrixOps.erase` sets). There is no
 * gesture-level builder for this op to route through: ROADMAP M8c's text-box and image tools do
 * not exist, so production's only writers are the copy paths, the snapshot writer and the
 * render-state replays, none of which is a user's format change. What these rows prove is that
 * the register **converges** — the merge question — and what they do not prove is that any user
 * gesture produces the op, which stays owed to M8c.
 *
 * @return the encoded register value, which is what [dev.inkstone.core.model.FoldedPage.formatOf]
 *   answers and what the assertions compare.
 */
private suspend fun Device.setFormat(noteId: DocumentId, target: ObjectId, format: ObjectFormat): String =
    edit(noteId) { session, page ->
        val record = format.encode()
        session.apply(page, listOf(PageOp.SetFormat(session.nextOpId(), session.nextClock(), target, record)))
        record
    }

/** [setFormat] at an explicit counter — row 4f's certain collision, with [setFormat]'s caveat. */
private suspend fun Device.setFormatAtCounter(
    noteId: DocumentId,
    target: ObjectId,
    counter: Long,
    format: ObjectFormat,
): String = edit(noteId) { session, page ->
    val record = format.encode()
    val op = PageOp.SetFormat(session.nextOpId(), LamportClock(counter, deviceId()), target, record)
    session.apply(page, listOf(op))
    record
}
