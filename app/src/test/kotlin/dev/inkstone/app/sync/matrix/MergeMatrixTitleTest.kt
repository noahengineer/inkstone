package dev.inkstone.app.sync.matrix

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.sync.AttentionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⭐ **The other row SPEC §13.2 does not list: the LWW title actually converges (SPEC §7.7 layer 2).**
 *
 * Layer 2 was *"correct and unreachable"* for the whole of M6, in three places at once, and each one
 * on its own is enough to keep two devices on two titles forever:
 *
 * 1. **No writer.** `NoteSession.rename` — the only thing that stamps `Document.titleClock` — was
 *    reachable from nothing. The production rename renamed a library index row and moved a file.
 * 2. **The counter was re-issued.** `rename` takes its clock from the note's Lamport generator and
 *    `materialize` computed the bundle's `highestClockCounter` over page ops only, so the session
 *    after a close stamped the *same* clock again. `resolveTitle` needs strictly later, so each
 *    device then refused the other's rename — with `titleOverridden` false and no attention row.
 * 3. **No reader.** `recordSave` has no title column, so a resolved title stayed in `note.inkstone`
 *    and the library tile — the only place a user sees a title — kept the old string.
 *
 * So this row asserts the *whole* path, in both directions, and reads the answer back through a note
 * **reopened from the files** on each device rather than from a session either of them still holds.
 * A test that compared two live sessions would be comparing two copies of one merge.
 *
 * What it does not cover is `NoteSessionHost.renameNote` — the hand-off to a live editor's session —
 * for the reason `MatrixGate`'s KDoc gives: that class needs `LibraryRepository`, and this suite
 * cannot build one. Everything below it, including `NoteStore.renameNote` and the outbox
 * consequences, is the production code.
 */
class MergeMatrixTitleTest {

    @Test
    fun `a rename on one device wins on the other and the next rename sorts after it`() = matrixRow(
        row = "layer 2 (LWW title)",
        proven = "a rename crossing in both directions, ordered by a clock that survives a round trip, " +
            "reaching the reopened note and the library row on both devices",
        notProven = "nothing extra — the ordering is entirely local, and the transport is a file whose " +
            "bytes the in-process server already round-trips",
    ) { devices ->
        val id = devices.share("Lecture 3")

        // ── A renames. B has to end up with the title *and* the clock: a title without its clock is a
        // rename B cannot beat later and cannot tell it has already seen.
        devices.a.rename(id, RENAMED_BY_A)
        devices.syncBothWays()

        val (titleOnA, clockFromA) = devices.a.reopened(id)
        val (titleOnB, clockOnB) = devices.b.reopened(id)
        assertEquals(RENAMED_BY_A, titleOnA, "A's own rename did not survive its round trip")
        assertEquals(RENAMED_BY_A, titleOnB, "B never received A's rename — layer 2 has no writer")
        val fromA = assertNotNull(clockFromA, "A's rename left no clock, so no merge can order it")
        assertEquals(fromA, clockOnB, "B took the title without the clock that ordered it")
        assertEquals(
            RENAMED_BY_A,
            assertNotNull(devices.b.summary(id)).title,
            "B's library tile still shows the old title — the resolved title reached the bundle and stopped",
        )

        // ── B renames back over it, which is the direction the bug hid in. B's clock has to be issued
        // from a counter that already accounts for A's; otherwise the two come out with equal counters
        // and which title survives is a lexicographic comparison of two UUIDv7 device ids.
        devices.b.rename(id, RENAMED_BY_B)
        devices.syncBothWays()

        val (finalOnB, clockFromB) = devices.b.reopened(id)
        val (finalOnA, clockOnA) = devices.a.reopened(id)
        val fromB = assertNotNull(clockFromB)
        assertTrue(fromB > fromA, "B's rename was stamped $fromB, which does not sort after A's $fromA")
        assertEquals(RENAMED_BY_B, finalOnB)
        assertEquals(RENAMED_BY_B, finalOnA, "A refused B's later rename, so the two devices disagree for good")
        assertEquals(fromB, clockOnA, "A adopted the title but not the clock, so its own next rename would lose")
        assertEquals(RENAMED_BY_B, assertNotNull(devices.a.summary(id)).title, "A's library tile kept the old title")

        // ⚠️ And nothing is owed or stuck. The rename in this direction found a **second** M6 defect:
        // the remote file is renamed too, so the peer sees a new ETag at a new href — and a pull that
        // advanced the ETag without following the path left the binding aimed at a file that no longer
        // existed. B's next MOVE 404'd, parked for a day, and took every later upload of that note
        // with it, because MOVE drains before UPLOAD. The title never left the device.
        assertEquals(
            assertNotNull(devices.a.binding(id)).remotePath,
            assertNotNull(devices.b.binding(id)).remotePath,
            "the two devices disagree about where the file is, so one of them is about to MOVE from nowhere",
        )
        assertTrue(devices.a.outboxRows().isEmpty(), "A still owes the server something after two renames")
        assertTrue(devices.b.outboxRows().isEmpty(), "B still owes the server something — a parked row is a day lost")

        // ⭐ And the override is *recorded* rather than discarded (SPEC §7.7: "with the override
        // recorded in history"). This row was unreachable in production too: with no writer both
        // clocks were always null, `resolveTitle` always kept the local title, and nothing ever lost.
        val overridden = devices.a.attention().firstOrNull { it.kindOrNull == AttentionKind.TITLE_OVERRIDDEN }
        assertEquals(
            RENAMED_BY_A,
            assertNotNull(overridden, "A's title lost and nothing was recorded").detail,
            "the attention row named the winner rather than the string the user typed and lost",
        )
    }

    /** The title and its clock as a note **reopened from disk** holds them. */
    private suspend fun Device.reopened(id: DocumentId): Pair<String, LamportClock?> {
        val document = session(id).document
        return document.title to document.titleClock
    }

    private companion object {
        const val RENAMED_BY_A = "Thermodynamics wk 4"
        const val RENAMED_BY_B = "Thermodynamics wk 4 final"
    }
}
