package dev.inkstone.app.sync.matrix

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.sync.Backoff
import dev.inkstone.core.sync.OutboxOpType
import dev.inkstone.core.sync.RemoteMedia
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⭐ M12 — **the row that decides whether the capture half works** (docs/M12-AUDIO.md §5,
 * ROADMAP M12's third acceptance criterion).
 *
 * Not one of SPEC §13.2's twenty-one, but it belongs beside them: it is a claim about the
 * outbox, it is written against the same fixture, and it runs against a real Nextcloud when one
 * is configured. Rows 17 and 21 are its negative twins and must keep passing unchanged — row 17
 * says 500 note saves become **one** row, and this says two recordings stay **two**. The whole
 * design is that those are the same mechanism with a different key, not two mechanisms.
 *
 * ## What "offline" means here, exactly
 *
 * Coalescing happens at **enqueue** time, in one SQLite transaction, before any socket is
 * opened. So "both recordings were made while offline" is precisely "both were enqueued with no
 * drain in between", which is what these tests do. Nothing is simulated: an aeroplane, a
 * basement and a week in a bag all reach the outbox as exactly this sequence.
 *
 * ## ⚠️ The standing caveat, restated because it is load-bearing here
 *
 * There is no `NoteSessionHost` and no live editor in this fixture (see `Device.publishDirect`),
 * so **no row here can model an open note** — and a recording is made *while the note is open*.
 * A green run proves the drainer's ordering, the schema's key and the bytes on the wire. It does
 * not prove the feature is reachable, that the microphone stops when you leave the note, or that
 * an Ogg file survives a kill. Those are `adb`, an emulator and the tablet.
 */
class MergeMatrixMediaTest {

    /**
     * ⭐ **Two recordings made offline into one note, and both arrive.**
     *
     * This is the trap doc §5 names. SPEC §7.6 keys the outbox on `(note_id, op_type)` and
     * coalesces with an `INSERT OR REPLACE` — correct for the PDF, where a newer publish
     * strictly supersedes an older one, and catastrophic for audio, where every file is a
     * distinct irreplaceable object and none supersedes another. With the two-column key the
     * second recording's row **replaces** the first's: one lecture uploads, the other sits on
     * disk with nothing pointing at it, and nothing reports anything.
     *
     * Written before the capture code and watched to fail, which it did — *"the second
     * recording replaced the first in the outbox"*, one row where there should be two.
     */
    @Test
    fun `two recordings made offline into one note both reach the server`() = matrixRow(
        row = "M12 (audio must not coalesce)",
        proven = "two outbox rows keyed on the recording id, two pairs of files on the server, correct bytes",
        notProven = "that recording is reachable from the UI, or that Ogg survives a kill — both need a device",
    ) { devices ->
        val note = devices.share("Lecture")

        // Both recorded before anything drains: an aeroplane, a basement, a week in a bag.
        val first = devices.a.recordLecture(note, "monday", audioBytes = 4_096)
        val second = devices.a.recordLecture(note, "tuesday", audioBytes = 8_192)

        val queued = devices.a.outboxRows().filter { it.opType == OutboxOpType.MEDIA_UPLOAD }
        assertEquals(
            2,
            queued.size,
            "the second recording replaced the first in the outbox — one lecture would never upload, silently",
        )
        assertEquals(
            setOf(first, second),
            queued.mapTo(HashSet()) { it.subject },
            "the rows do not name the two recordings",
        )

        devices.resetCounts()
        val drained = devices.a.drain()
        assertEquals(2, drained.mediaUploaded, "both recordings did not upload")

        // Four files: two audio, two timelines. The timeline rides in the same op as its audio,
        // because two independent ops means one can land without the other — and a transcript
        // with no timeline is just a transcript (doc §5).
        for ((recordingId, size) in listOf(first to 4_096, second to 8_192)) {
            val audioPath = devices.a.remoteAudio(note, recordingId)
            val timelinePath = devices.a.remoteTimeline(note, recordingId)

            val audio = assertNotNull(devices.rawBytes(audioPath), "$recordingId's audio is not on the server")
            assertEquals(size, audio.size, "$recordingId's audio arrived the wrong length")
            assertContentEquals(
                devices.a.fs.readBytes(NoteLayout.recordingAudio(recordingId)),
                audio,
                "$recordingId's audio does not match what is on the device",
            )
            assertContentEquals(
                devices.a.fs.readBytes(NoteLayout.recordingTimeline(recordingId)),
                assertNotNull(devices.rawBytes(timelinePath), "$recordingId's timeline is not on the server"),
                "$recordingId's timeline does not match what is on the device",
            )
        }

        // ⚠️ Read off the wire, not off the code. `WebDavClient.put` hard-coded
        // `application/pdf` until M12 and every byte-level assertion above would still pass
        // with an .opus announced as a PDF — the component that can tell is the server-side
        // watcher, which selects by declared type and is not in this test.
        val declared = devices.declaredContentType(devices.a.remoteAudio(note, first))
        if (declared == null) {
            println("    (live) declared Content-Type not observable — unchecked: audio/ogg")
        } else {
            assertEquals("audio/ogg", declared, "the audio was announced to the server as something else")
        }

        // Nothing is owed twice. A second drain must be silent, or every poll re-uploads a
        // lecture — and, once the server half has trashed the audio it transcribed, re-creates
        // it and buys a second transcription of the same hour.
        devices.resetCounts()
        assertEquals(0, devices.a.drain().mediaUploaded, "a second drain re-uploaded the recordings")
        devices.assertRequests("PUT", 0, "a settled recording was uploaded again")
    }

    /**
     * ⚠️ Doc §5's third rule: *"Trashing a note whose lecture has not uploaded yet should still
     * deliver the lecture."*
     *
     * A DELETE **annihilates** a pending UPLOAD by design (SPEC §7.6) — otherwise a note the user
     * just deleted would be re-created on the server moments later. A recording is not that: its
     * files live in a sibling directory the note's DELETE does not touch, and it is the one thing
     * in this app that cannot be made again. Same shape as M6's row 14, where treating a
     * remote-side delete as `noteTrashed` destroyed the only queued route a note's unsynced edits
     * had to the server.
     */
    @Test
    fun `trashing the note does not destroy its queued lecture`() = matrixRow(
        row = "M12 (a delete must not annihilate a recording)",
        proven = "the MEDIA_UPLOAD row survives a trash, drains before the DELETE, and the files land",
        notProven = "nothing extra",
    ) { devices ->
        val note = devices.share("Trashed lecture")
        val recordingId = devices.a.recordLecture(note, "monday", audioBytes = 2_048)
        // ⚠️ Read **before** the trash. `performDelete` ends in `bindings.remove(noteId)`, so
        // once the DELETE has drained there is no binding left to derive the media path from —
        // which is the same fact this test is about, seen from the fixture's side.
        val audioPath = devices.a.remoteAudio(note, recordingId)

        devices.a.engine.noteTrashed(note)

        val rows = devices.a.outboxRows().filter { it.noteId == note }
        assertTrue(
            rows.any { it.opType == OutboxOpType.MEDIA_UPLOAD && it.subject == recordingId },
            "trashing the note destroyed the queued lecture",
        )
        assertTrue(rows.any { it.opType == OutboxOpType.DELETE }, "trashing a bound note owes the server no DELETE")

        val drained = devices.a.drain()
        assertEquals(1, drained.mediaUploaded, "the lecture did not upload")
        assertEquals(1, drained.deleted, "the note's own file was not deleted")

        // ⚠️ The ordering claim, and it is the reason MEDIA_UPLOAD sits before DELETE in
        // `OutboxOpType`: `performDelete` ends in `bindings.remove(noteId)`, and `drainOne`
        // drops any row whose note has no binding — so a DELETE draining first would have
        // discarded this lecture with no error anywhere.
        assertNotNull(
            devices.rawBytes(audioPath),
            "the lecture is not on the server — the DELETE drained first and took its binding with it",
        )
    }

    /**
     * ⚠️ **A recording whose audio has gone parks that row and leaves every other row alone.**
     *
     * The bug this pins is the one M12 met on the first real device, and it was invisible twice
     * over. `performMediaUpload` read the file with `fs.readBytes` and tested `audio.isEmpty()` —
     * which reads exactly like a missing-file guard and is not one, because `AndroidFileSystem`
     * uses `File.readBytes()`, which **throws**. Only the test double ever returned empty, so
     * `MEDIA_MISSING` was unreachable in production: a fixture more generous than production, the
     * failure mode CLAUDE.md names.
     *
     * The throw then escaped `drain` and the worker from a line **14 above** `mkcolRecursive` — so
     * not even the media directory appeared on the server, no row was parked, no note state was
     * written and nothing was reported. Every later "Sync now" did the same nothing. That is why
     * the second assertion matters more than the first: one unreadable lecture must not silently
     * abandon the queue behind it, which on a real device includes the note's own DELETEs.
     */
    @Test
    fun `a recording whose audio is missing parks without abandoning the rest of the queue`() = matrixRow(
        row = "M12 (an unreadable recording is one row's problem)",
        proven = "the missing file parks its own row and the sibling recording still uploads",
        notProven = "the Android FileSystem's throwing behaviour itself - TempFileSystem is not AndroidFileSystem",
    ) { devices ->
        val note = devices.share("Two lectures, one lost")
        val lost = devices.a.recordLecture(note, "lost", audioBytes = 1_024)
        val kept = devices.a.recordLecture(note, "kept", audioBytes = 2_048)

        // The file goes away after the row was enqueued: a user clearing app storage, or the
        // crash-recovery path deciding it was unusable.
        devices.a.fs.delete(NoteLayout.recordingAudio(lost))

        val drained = devices.a.drain()

        assertEquals(1, drained.mediaUploaded, "the surviving lecture did not upload")
        assertNotNull(
            devices.rawBytes(devices.a.remoteAudio(note, kept)),
            "one unreadable recording took the whole pass with it",
        )
        val lostRow = devices.a.outboxRows()
            .firstOrNull { it.opType == OutboxOpType.MEDIA_UPLOAD && it.subject == lost }
        assertNotNull(lostRow, "the unreadable recording's row was dropped rather than parked - its only trace is gone")
        // ⚠️ **Parked, not merely retried**, and the difference is what the `fs.exists` guard buys.
        // A park is a day; the longest ordinary backoff is `Backoff.MAX_MILLIS` (6 h). Without the
        // guard the throw is caught by `drain`'s safety net and becomes a *transport retry*, which
        // says "the network glitched" about a file that does not exist and will hammer for it
        // forever. `MEDIA_MISSING` says the true thing, on the note, where a user can see it.
        assertTrue(
            lostRow.nextAttemptAt > devices.a.clock.nowEpochMillis() + Backoff.MAX_MILLIS,
            "a missing recording was retried like a network blip instead of parked as MEDIA_MISSING",
        )
    }

    /**
     * ⭐ **"Sync now" rebuilds a queue that lost a row**, which nothing in the app could do before.
     *
     * The Sync screen counts the **recording** table (`state='READY' AND uploadedAt=0`), which
     * knows nothing about the outbox. So a lecture whose `MEDIA_UPLOAD` row was never written —
     * the process killed between `recordings.finish` and `recordingCaptured`, or a sign-out
     * dropping every row — shows in the header forever while the drainer has nothing to do. Until
     * `syncNowRequested`, the only repair was `bind` / `noteRestored` / `resumeAccount` /
     * `noteChanged`: the user had to *draw a stroke in that note and save it*, and finishing a
     * lecture is the archetypal moment somebody puts the tablet down instead.
     */
    @Test
    fun `sync now re-derives a recording whose outbox row was lost`() = matrixRow(
        row = "M12 (Sync now reconciles what is owed)",
        proven = "a recording with no outbox row is re-queued by the engine call the button makes, and uploads",
        notProven = "that the Sync screen calls it - that is SyncViewModel, and a JVM test cannot press a button",
    ) { devices ->
        val note = devices.share("Orphaned lecture")
        val recordingId = devices.a.recordLecture(note, "orphan", audioBytes = 1_536)

        // Exactly the state the real device was found in: READY, uploadedAt=0, and no row.
        devices.a.outbox.removeAll(note)
        assertTrue(
            devices.a.outboxRows().none { it.opType == OutboxOpType.MEDIA_UPLOAD },
            "the fixture did not clear the row",
        )
        assertEquals(0, devices.a.drain().mediaUploaded, "a drain invented a row that was not there")

        devices.a.engine.syncNowRequested()

        assertTrue(
            devices.a.outboxRows().any { it.opType == OutboxOpType.MEDIA_UPLOAD && it.subject == recordingId },
            "Sync now did not re-derive the missing row",
        )
        assertEquals(1, devices.a.drain().mediaUploaded, "the re-derived row did not upload")
        assertNotNull(devices.rawBytes(devices.a.remoteAudio(note, recordingId)), "the lecture is not on the server")
    }

    /**
     * ⚠️ **A parked `MEDIA_UPLOAD` was unliftable by anything in the app.**
     *
     * A park is 24 h, `due()` filters on `next_attempt_at`, and coalescing *inherits* it — so
     * every later sweep re-UPSERTs the same row at the same park and every "Sync now" opened no
     * socket at all. The only lifter, `unparkUpload`, filtered `opType == UPLOAD`, so a media row
     * was structurally excluded; and its one caller is a completed reconcile, which a lecture with
     * no note edit behind it never triggers.
     *
     * Coalescing's inheritance is deliberately **not** changed — resetting a park on every save is
     * a hot retry loop against the user's own server. A person pressing a button is the one signal
     * in the system that is not a loop.
     */
    @Test
    fun `sync now lifts a parked recording that nothing else could`() = matrixRow(
        row = "M12 (a park is not a life sentence)",
        proven = "a parked MEDIA_UPLOAD is invisible to a drain, and a user press makes it due again",
        notProven = "nothing extra",
    ) { devices ->
        val note = devices.share("Parked lecture")
        val recordingId = devices.a.recordLecture(note, "parked", audioBytes = 1_280)

        val row = devices.a.outboxRows().first { it.opType == OutboxOpType.MEDIA_UPLOAD }
        val aDayAway = devices.a.clock.nowEpochMillis() + 24L * 60 * 60 * 1000
        devices.a.outbox.reschedule(note, row.key, row.attempts, aDayAway)

        assertEquals(0, devices.a.drain().mediaUploaded, "a parked row drained anyway - the park is not real")

        devices.a.engine.syncNowRequested()

        assertEquals(1, devices.a.drain().mediaUploaded, "Sync now could not lift the park")
        assertNotNull(devices.rawBytes(devices.a.remoteAudio(note, recordingId)), "the lecture is not on the server")
    }
}

/** The note's media directory on the server, from the binding this device holds. */
internal suspend fun Device.remoteAudio(noteId: DocumentId, recordingId: String): String =
    RemoteMedia.audio(remotePath(noteId), noteId, recordingId)

internal suspend fun Device.remoteTimeline(noteId: DocumentId, recordingId: String): String =
    RemoteMedia.timeline(remotePath(noteId), noteId, recordingId)
