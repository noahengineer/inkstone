# M12 — recorded lectures, aligned to the notes

**Status: the CAPTURE half is built. The alignment/transcript half is still designed, not built.**

Sections 1–14 are the original design, **corrected where building it proved something wrong** —
every correction is marked ⚠️ **CORRECTED** and says what the code does instead. Section 15 is what
was built, what each claim actually rests on, and what is still open.

---

## 1. What it is for

Maths class. The teacher says *"this one is really important, you'll need it in the exam"* and you
write `y = mx + c`. Two months later the page says `y = mx + c` and nothing else.

The feature is not "record audio in a note". It is: **the emphasis, the aside and the worked
explanation that surrounded a line of writing are attached to that line**, so that reading the
note back — by you, or by an assistant with access to your Nextcloud — recovers what the page
alone cannot hold.

That framing decides most of what follows. In particular it means the deliverable is **one merged,
timestamped document**, not an audio file that happens to sit near a PDF.

---

## 2. The shape, end to end

```
tablet                                   Nextcloud                        your server
──────                                   ─────────                        ───────────
press ● in a note
  ├─ foreground service, mic
  ├─ audio.opus        (append)
  └─ …timeline         (append)
leave the note → stop
  └─ < 30 s? discard
enqueue outbox op ──────────────────────► <folder>/.inkstone-media/<documentId>/<recId>.opus
   (waits for a connection)               <folder>/.inkstone-media/<documentId>/<recId>.timeline
                                                   │
                                                   │ watched
                                                   ▼
                                          media/<documentId>/<recId>.md ◄── AssemblyAI
                                                   │                        (transcribe)
                                          .opus → Nextcloud trash
                                                   │
                                                   ▼
                                          Claude reads it over WebDAV
```

⭐ **The app never learns AssemblyAI exists.** No SDK, no API key, no polling job on a device that
sleeps. It writes two files and enqueues an upload; everything after that is the server's.

Three things fall out of that, and each is worth more than it looks:

- **An API key shipped in an APK is extractable.** Keeping it server-side is not tidiness, it is
  the difference between a credential that can leak and one that cannot.
- **Retry lives somewhere always on.** A tablet in a bag cannot poll a transcription job.
- **Swapping AssemblyAI for whisper later is a server-side edit**, not an app release.

---

## 3. ⭐ The timeline is capture data, not document data

The obvious designs are to put the stroke↔audio offsets in the op log (a new op, or a field on
`AddObject`) or in the bundle as a known entry with a merge rule like M7's `text/`. **Both are
wrong, and expensively so** — SPEC's position is that the op vocabulary *is* the sync format, and
changing it after sync is live is the milestone where everything breaks.

Neither is needed, because a recording has none of the properties that make something document
data:

| | document data | a recording's timeline |
|---|---|---|
| Written by | any device | **one** device, one sitting |
| Merged | yes, by the CRDT | **never** |
| Edited later | yes | **never** |
| Consumed | by every reader, forever | **once**, by one server job |

So it is an **append-only sidecar log**, `recordings/<recordingId>.timeline`, one line per stroke
(`objectId`, `offsetMs`), flushed on the same rhythm as the journal. Append-only is crash-safe by
construction, the way the journal already is.

⚠️ **CORRECTED — `recordings/` is a sibling of `notes/`, not a directory inside the note.** The
obvious reading puts it at `notes/<documentId>/recordings/`, and that destroys lectures three ways,
because a note directory is something this app **moves and copies as a unit**: `NoteTrash.trash`
moves the whole directory into `trash/` and `purgeExpired` hard-deletes it after thirty days — so
trashing the note would eventually destroy the un-uploaded lecture §5 says must still be delivered;
`NoteDuplicate` copies the directory, so duplicating a note would duplicate every hour of audio in
it; and the merge paths rewrite that directory's contents. `recordings/` sits beside `notes/` for
the same reason `folders/` does (see `NoteLayout`). Which note a recording belongs to is a column,
never the path.

**No op-vocabulary change. No new merge rule. No bundle schema bump. No `PageTextMerge`-shaped
convergence problem.** That deletes the risky half of this milestone.

⚠️ The one consequence to accept: a stroke deleted after the recording leaves a timeline line
pointing at nothing. The server should skip it and say so. That is strictly better than teaching
the CRDT about microphones.

### The offsets are elapsed time, not wall clock

CLAUDE.md forbids ordering sync operations by wall clock, and this looks like a violation. It is
not: the alignment is between strokes and audio **captured on one device in one session**, and is
never compared across peers. `SystemClock.elapsedRealtime` from the recording's start — monotonic,
immune to the clock drift the rule exists to protect against.

---

## 4. ⭐ Audio never enters the bundle

`PdfWriter` embeds the whole `note.inkstone` into every published PDF via `/Names /EmbeddedFiles`,
and `InkstonePdfMaterializer` rebases and re-embeds it on **every** materialize.

A bundle today is ~5 KB. Voice-grade Opus is ~11 MB/hour — ⚠️ **measured at ~15 MB/hour**; see
§15's findings, where `MediaRecorder` turned out to treat the requested bitrate as a hint. The
argument is unchanged and stronger. A three-hour lecture day would make the
bundle ~33 MB — six thousand times larger — re-embedded into the PDF on every close and, because
SPEC §7.6 uploads whole files with no delta, **re-uploaded in full every time**.

So the split is:

- **Transcript and timeline are small** (a 90-minute transcript is ~15 KB) and want to travel with
  the note.
- **Audio is large, immutable and consumed once.** It is a sidecar, written once, never rewritten.

The cost is real and was accepted deliberately: a recorded note is no longer *one* ordinary PDF in
your Nextcloud. Section 6 is how that is kept from becoming a mess.

---

## 5. ⚠️ The outbox coalesces, and that would eat a recording

SPEC §7.6 keys the outbox on `(note_id, op_type)` and coalesces with an UPSERT. `Outbox.kt`'s own
comment calls `UPLOAD` *"the one that coalesces"* — correct for the PDF, where a newer publish
strictly supersedes an older one.

**It is catastrophic for audio.** Record two lectures into one notebook while offline and the
second recording's upload row *overwrites* the first. One lecture uploads. The other sits on disk
with nothing pointing at it, and nothing reports anything. That is the same shape as M6's
discovery that a DELETE annihilates a pending UPLOAD.

**Every audio file is a distinct object and none supersedes another**, so the op must key on
`(noteId, recordingId)`.

Two more rules that follow:

- **The audio and its timeline ride in one op.** Two independent ops means one can land without
  the other, and a transcript with no timeline is just a transcript.
- **The audio op must not be annihilated by a note DELETE** the way an UPLOAD is. Trashing a note
  whose lecture has not uploaded yet should still deliver the lecture — or at minimum say it will
  not.

Everything else about offline is free: the outbox already has backoff, whole-file uploads, quota
handling and CLAUDE.md rule 5's *"never retry a 401"*. Airplane mode, a week in a bag, a flaky
hotel connection — the drainer already handles all of it, which is exactly why the feature routes
through it rather than around it.

---

## 6. Identity, and where the files live

```
Maths/Straight lines.pdf                          ← unchanged; still an ordinary PDF
Maths/.inkstone-media/<documentId>/
    2026-08-11T09-05.opus                         ← deleted after §8's ordering
    2026-08-11T09-05.timeline
    2026-08-11T09-05.md                           ← what Claude reads
```

⚠️ **Keyed by `documentId`, never by filename.** M6 spent a milestone on this: path-based identity
breaks the instant someone renames or drags a file in the Nextcloud web UI, and a transcript found
by *sitting next to* `Straight lines.pdf` is orphaned silently the moment that name changes. The
transcript carries the note's title in its front matter, so it stays readable on its own.

⚠️ **CORRECTED — the file name is the `recordingId`, not a timestamp.** `2026-08-11T09-05.opus`
reads better in a listing and is exactly the wrong key for this feature's headline case: at minute
resolution **two recordings in one note collide**, which is the acceptance criterion. A UUIDv7 sorts
by time anyway, so the listing stays chronological, and the date a human wants is in the
transcript's front matter where §7 already puts it.

⚠️ **CORRECTED — the media files do not reach `foreignRemote`, and do not need to.**
`RemoteWalk.isNote` filters on `.pdf` before the classifier that writes those rows is ever reached,
so a `.opus`, a `.timeline` or the server's `.md` is invisible to it. They are foreign for free.

What was **not** free is the *directory*: nothing in `:core:sync` has ever had a hidden-file rule,
so the leading dot bought nothing and the walk descended into `.inkstone-media/` and then into the
`<documentId>/` beneath it. That costs two extra `PROPFIND Depth:1` per note-with-recordings on
every unpruned walk — unbounded, because directory listings do not consume the pass budget — and,
far worse, **a directory ETag propagates to the root**, so every recording upload would defeat
`probeRoot`'s one-request poll for the whole account. `RemoteWalk` now skips the directory by name,
which also makes *"not mistaken for a note"* a guarantee rather than a consequence of the extension
filter.

---

## 7. ⭐ The alignment unit is a recognised line, and the window is asymmetric

A single stroke is too fine — the `y` and the `=` are not separate thoughts. A page is too coarse.
The right unit is **a line of writing**, which M7's `LineSegmenter` already produces, complete with
its text, its box and the strokes that made it. Give those strokes capture offsets and each line
has a time span for free. M7 built the granularity this feature needs without knowing it.

⚠️ **Writing lags speech, so a symmetric join attaches the wrong sentence.** The teacher says
*"y equals m x plus c — this is really important"* and you start writing partway through, finishing
after they have moved on. The window has to reach **backwards further than forwards** — roughly
*from 15 s before the line's first stroke to 5 s after its last*.

Those two numbers are guesses and are **the first thing to tune against real recordings**, the way
M7's segmentation thresholds were. They are the difference between the feature working and the
feature producing plausible nonsense, which is the failure mode this whole app keeps finding.

⚠️ **Tune them in one direction: generous.** Measured against a real 48-minute lesson (§15.6),
consecutive lines' windows overlap heavily wherever writing is dense — and that costs nothing,
because the consumer is a *reader* who resolves redundancy by reading. What cannot be repaired is
speech left **out**: the audio is deleted once transcribed (§8), so an omission is permanent. The
same recording shows 24 pauses longer than the 15-second reach back, so the likelier error is that
the window is too short. Any change that *narrows* a window to make the output tidier is going the
wrong way — §15.6 records three such ideas and why they were dropped.

### The artifact

```markdown
---
note: Straight lines
documentId: 019fecef-…
recorded: 2026-08-11T09:05:00Z
recogniser: assemblyai/<model>
---

## 00:12:04 — page 2

**Wrote:** y = mx + c

> …so the general form of a straight line is y equals m x plus c.
> **This is really important** — you'll need it in the exam. m is the
> gradient and c is where it crosses the y axis.
```

⚠️ **The transcript must not go into the PDF's invisible text layer.** It would make "search my
handwriting" return words nobody wrote, which quietly destroys M7's entire premise.

---

## 8. Deleting the audio — the ordering is the whole safety net

The audio is discarded once transcribed. Two rules make that safe rather than reckless:

**It goes to Nextcloud's trash, not to a hard delete.** CLAUDE.md rule 1 already says remote
deletes land in the server's trash, so this needs nothing new, and the server's retention policy
decides how long there is to change your mind. That matters because if AssemblyAI mishears
*"y equals m x plus c"* as *"why equals MX plus sea"*, the lecture is otherwise gone for good.

**Transcript written and verified in Nextcloud *first*; audio deleted second.** Not *"AssemblyAI
returned 200, delete"*. Same reasoning as `materialize` writing the bundle before resetting the
journal: a crash between the two steps must leave a duplicate, never a hole.

---

## 9. Capture, and the four things that go wrong in a real lecture

**Ogg/Opus, not MP4.** `MediaRecorder` writing MPEG-4 finalises its index *on stop* — kill the
process at minute 50 and you have a large file that nothing will play. Ogg streams and survives
truncation. This is the same failure class as the torn ZIP that CLAUDE.md rule 10 exists to
prevent, and audio is the most irreplaceable data this app will ever hold: you cannot re-record a
lecture.

**A foreground service with the microphone type.** Android requires it for background mic capture,
and its permanent notification is the honesty floor for recording a room full of people.

**"Stops when you leave the note" means leaving the note — not the Activity stopping.** Screen
timeout, lock, a glance at a message, rotation, split-screen: keep recording. Back to the library
or opening a different note: stop. If it hangs off `onStop`, the first time the screen dims at
minute 8 you lose the other 42 and do not find out until you look.

**Losing the microphone is not the same as stopping.** A phone call takes the mic and the recorder
happily writes *silence* — worse than stopping, because it is discovered an hour later. Detect the
loss and mark a gap in the timeline.

And the recovery case: **a process killed mid-lecture leaves an orphaned recording.** The next
launch finalises it and offers to process it rather than discarding it. That is the second reason
for a streamable container.

---

## 10. Starting it

**A button in the note.** One press. Unmistakable while running — a visible elapsed timer, not a
tinted icon. The failure that hurts is recording for fifty minutes and finding out you were not;
its mirror, believing you stopped when you had not, is the privacy version of the same bug.

**Auto-record is per folder, not global.** *"Auto-record in Maths"* is what is actually wanted —
you record in lectures, not when reviewing at home — and M6's folder machinery already exists to
hang it on. A global setting means the microphone turns itself on whenever any note opens, which
is a materially different thing from a button and makes the notification the only signal.

**Recordings under 30 seconds are discarded, not uploaded.** Without it, auto-record plus
stop-on-leaving means opening a note for twenty seconds to check one thing costs an upload, a
transcription job and a line on the bill — every time, all day.

⚠️ **This is the app's first real user setting.** There is no settings store: no DataStore, no
preferences table, and `InkUiState` says so in as many words. A `setting` table beside `account` is
probably the least new machinery.

---

## 11. Storage, quota, and seeing what is pending

Five lectures a day and a week offline is ~275 MB of audio waiting on the tablet. That must be
**visible** — pending recordings and their sizes, somewhere reachable — rather than discovered when
the device fills up.

- **Refuse to start when free space is low.** Discovering at minute 50 that writes have been
  failing is not a recoverable outcome.
- **A 507 from Nextcloud leaves the audio exactly where it is.** SPEC §7.9 already reads quota,
  sentinel negatives and all.
- **An empty or failed transcript raises a "Needs attention" row** rather than writing a blank
  file. That machinery exists, it is non-modal by design (CLAUDE.md rule 6), and it means the
  audio's trash window is still open when it is noticed.

---

## 12. What leaves the device

Stated plainly so it is a decision rather than a discovery:

- Class audio goes to Nextcloud, then to a third-party transcription service, then is trashed.
- The transcript stays in Nextcloud and is read by an assistant — so a record of what a teacher
  said, in a room of identifiable people, reaches two companies.
- Folder-scoped auto-record is a microphone that turns itself on. Many institutions restrict
  recording lectures at all.

None of this is a blocker. It shapes the UI — which is why the notification, the visible timer and
the per-folder scope are design decisions in §10 rather than polish.

---

## 13. Open spikes — settle before building

⚠️ **Spikes 1 and 3 are answered — see §15.7, measured on a real lesson.** Spike 1's assumption
that diarization identifies *the teacher* was **wrong**: the API returns anonymous `A`/`B` with no
enrolment, so the label is a heuristic (most speech time) and is marked as one in the artifact.

1. **Does AssemblyAI return word-level timestamps and speaker labels, in what shape?** The entire
   alignment rests on per-word start/end times, and diarization is what allows *"the teacher said
   this"* instead of mixing in the person behind you. **Verify against the current API docs, not
   against anyone's memory** — this is CLAUDE.md rule 3's habit applied to an API surface, which is
   exactly what caught ML Kit's package name in M7.
2. **The alignment window.** −15 s / +5 s is a guess. Tune it against real recordings and report
   both the before and after numbers, the way M7 reported segmented against unsegmented.
3. **Cost over a school year.** Priced per hour of audio; five lectures a day across two terms is
   several hundred hours. It may not change the decision, but it should be a number that was seen
   rather than discovered.
4. **What the server watches with.** A Nextcloud folder poll is the least machinery and needs no
   webhook; `notify_push` is not commonly available (CLAUDE.md), so do not build on it.

---

## 14. Milestone shape

Two halves that are independently useful, and the first is worth having on its own:

- **Capture** — button, per-folder setting, foreground service, durable Opus, append-only timeline,
  30-second floor, orphan recovery, pending-uploads UI, outbox op that does not coalesce.
- **Alignment and transcript** — the server side, the −15/+5 window over M7's lines, the merged
  markdown, "Needs attention" on failure, audio to trash after verification.

⚠️ **The number is an identifier, not a build position — this can be built next, after M7.**
M8–M11 already exist as sections and renumbering would invalidate references throughout SPEC,
ROADMAP and six `docs/` files for no benefit, so 12 was chosen to avoid churn rather than to
schedule anything. Nothing here depends on M8–M11.

SPEC §12's *"out of scope for v1"* row stays true in its own terms — v1 ends at M11 — but that
describes what v1 contains, not what order things get built in.

---

## 15. What was built — the capture half

Sections 1–14 are the design. This is what exists, what each claim actually rests on, and what a
green tick here does **not** mean.

### 15.1 The bucket per claim

CLAUDE.md: *"a checklist tick belongs to a claim, not to a file existing. Say which of written,
automated, automated against the real dependency, and run on a device each one is."*

| Claim | Evidence | Bucket |
|---|---|---|
| Two recordings offline both arrive | `MergeMatrixMediaTest` — real `SqlOutboxStore`, real `SyncEngine`, real `OutboxDrainer`, real OkHttp against an in-process WebDAV server | automated, real dependency |
| …and the widened key works on **Android's** SQLite | `SqlSyncStoresTest` (instrumented; 60 tests green on the emulator) | run on a device |
| A note DELETE does not annihilate a queued lecture | `MergeMatrixMediaTest`, `OutboxCoalescingTest` | automated |
| MEDIA_UPLOAD drains before DELETE | `OutboxCoalescingTest` (the ordinal) + `SqlSyncStoresTest` (the SQL `CASE`, on-device) | run on a device |
| Audio is announced as `audio/ogg` | `MergeMatrixMediaTest`, read **off the wire** from the server's recorded request | automated, real dependency |
| The 30-second floor discards and does not upload | `RecordingControllerTest`; **and on the emulator** — an 8.1 s recording became `DISCARDED`, reason recorded, outbox empty | run on a device |
| The audio is kept when discarded | `RecordingControllerTest` | automated |
| Leaving the note stops it; another note's teardown does not | `RecordingControllerTest` | automated |
| **Screen off mid-recording keeps recording** | emulator: `KEYCODE_SLEEP`, `mWakefulness=Dozing`, the `.opus` grew 112,260 → 131,387 bytes over 12 s | run on a device |
| Leaving the note stops it, in the app | emulator: back out of the editor → the service is gone, row `READY` at 80,007 ms / 161,085 bytes | run on a device |
| The record button is reachable and shows a timer | emulator: `uiautomator` sees `Start recording`; after the tap, `Stop recording` and `00:03` | run on a device |
| Strokes land in the timeline at real offsets | emulator: two `input stylus` swipes 2 s apart produced two lines, 23089 and 25676 ms | run on a device |
| **Ogg survives a kill and is playable** | emulator `am force-stop` mid-recording, then `tools/verify-ogg-opus.py`: **1,154 complete pages, 0 CRC failures, 23.03 s recoverable, 0 torn bytes** — and again at 1,258 pages / 25.11 s | run on a device, verified by a reader that is **not** the encoder |
| Orphan recovery finalises it on next launch | emulator: `CAPTURING` → `READY`, 25,120 ms, 109,882 bytes — the duration matching the container's own 25.11 s | run on a device |
| A microphone taken away leaves a gap | `RecordingControllerTest` only — ⚠️ **not** reproduced on a device; see §15.4 | automated |
| Per-folder auto-record | `RecordingControllerTest` drives the resolved decision; the menu item and the resolver are wired | automated + written |
| Pending recordings and their sizes are visible | on the Nextcloud screen | ⚠️ **written only** |
| The recorder's messages reach the user | emulator: stopping under the floor showed *"That recording was under 30 seconds, so it was not uploaded."* | run on a device |

### 15.2 The trap, watched to fail

ROADMAP M12 asks for the two-recordings test first. It was written first, and against the outbox's
original `PRIMARY KEY (noteId, opType)` it failed exactly as §5 predicts:

```
the second recording replaced the first in the outbox — one lecture would never upload,
silently   expected:<2> but was:<1>
```

The fix is one column. `outbox` gains `subject`, the key becomes `(noteId, opType, subject)`, and
`subject` is empty for MKCOL/MOVE/UPLOAD/DELETE — so those still coalesce one-per-note and SPEC
§13.2 row 17's *"500 offline saves become one row"* is untouched. Every recording carries its own
id, so none can supersede another. **The two rules are one mechanism with a different key, not two
mechanisms.**

⚠️ `7.sqm` is therefore **the first migration in this project that is not purely additive** —
SQLite cannot alter a primary key, so it rebuilds the table. Three things made that affordable, and
they are in the file's own comments: the outbox holds markers rather than data, `verifyMigrations`
proves the resulting schema, and `MigrationOnDeviceTest` already asserts that a v4 fixture's row
survives *with its backoff state*, which is the part a DDL diff cannot see.

### 15.3 Findings — what the design or the platform got wrong

1. **`WebDavClient.put` hard-coded `Content-Type: application/pdf`.** Everything this app had ever
   uploaded was a PDF, so nothing noticed. An `.opus` announced as a PDF stores fine and reads back
   byte-identical; the only component that can tell is one that selects by declared type, which is
   precisely what §2's watched folder is. Same shape as M4's PDFs — valid, parseable, and blank in
   every viewer that was not the library that wrote them. It is a parameter now, and the matrix
   test reads the declared type **off the wire** rather than off the code.
2. **`MediaRecorder` treats `setAudioEncodingBitRate` as a hint.** Asked for 24 kbit/s it produced
   **34.3 kbit/s measured over a real 48-minute lesson** — 12,545,686 bytes for 2,928 seconds. So a
   lecture hour is **14.7 MB**, not §4's ~11 MB, and §11's arithmetic should use it: five lectures
   a day for a week offline is ~370 MB, not ~275 MB. §4's argument is unaffected — the comparison
   is against a 5 KB bundle — and is if anything stronger.
3. **A duration must be read from the container, not from bytes ÷ bitrate.** Following from (2),
   the recovery estimate measured **42% high**. `OggOpus.durationMillis` reads the last Ogg page's
   granule through `FileSystem.readRange` — a 96 KB tail read — and is exact.
4. **SQLDelight's migration verifier does not model an index dying with its table.** `DROP TABLE`
   and `ALTER TABLE … RENAME TO` are both accepted, but the `CREATE INDEX` that follows fails the
   build with *"Duplicate index name"*, naming an index whose table is two statements dead. An
   explicit `DROP INDEX` first is a no-op on a real database and is what makes the file build.
5. **`ViewModel.onCleared` cannot suspend**, which is why `RecordingController` is a process-wide
   singleton with its own scope rather than anything the editor owns. `clear()` cancels
   `viewModelScope` *before* calling `onCleared`, and finalising an Ogg, flushing the timeline,
   applying the floor and enqueuing a row are all suspending I/O. `NoteSessionHost` documents the
   same trap for `unregister`.
6. **`NoteActivity` is `launchMode="standard"`.** Nothing opens note B from inside note A today, so
   `onCleared` always fires — but the moment anything does, a second editor stacks and the first
   note's microphone would run until the process died. `noteOpened` stops a recording belonging to
   a different note, which closes that before it can open.
7. **Android lint's `ForegroundServiceType` check does not find the manifest entry** for an
   `@AndroidEntryPoint` service, though the merged manifest plainly carries it. Suppressed **and
   then checked on the device**, which is the part that matters: `dumpsys activity services`
   reports `isForeground=true types=0x00000080` — `FOREGROUND_SERVICE_TYPE_MICROPHONE`, accepted at
   API 35 with targetSdk 36.
8. **`MediaRecorder(Context)` is API 31 and `minSdk` is 30.** Caught by lint; without the version
   check the app would crash on the oldest device it claims to support, on the one code path that
   only runs when somebody presses record.
9. ⚠️ **This milestone shipped its own inert path and it was caught by grep, not by a test.**
   `RecordingUiState.message` was being *set* — `LOW_SPACE`, the thirty-second notice, the
   microphone failure — with **nothing collecting it**. Pressing record with a full disk would have
   done nothing and said nothing, which is the exact failure §11 says is unrecoverable. Found by
   CLAUDE.md's second check (*"grep for the production caller of every new public entry point"*),
   applied to a new *field* rather than a method; fixed with a second `LaunchedEffect` in
   `NoteActivity`, and then confirmed on the emulator. Nine features in this repo's history have
   had this shape; this is the tenth, and the only one caught before it shipped.

### 15.4 ⚠️ What is **not** proven

- ⚠️ **A real 48-minute lesson has now been recorded on the Galaxy Tab** — see §15.6. That covers
  definition-of-done #4 for *capture*, and it is where the storage numbers below come from. It does
  **not** cover ink feel: nothing was added to the ink path but one `elapsedRealtime()` and one
  queue push, and that is still an argument rather than a measurement.
- **Audio quality is still unjudged.** The container is provably intact and the timeline is
  complete, but nobody has yet said whether `VOICE_RECOGNITION` renders a teacher across a room
  intelligibly. That is a listening test, and it should happen before transcription is built on
  top of it.
- **The microphone-loss gap is unit-tested only.** Making Android actually pre-empt our capture
  needs a second recorder or a real call; the path from `AudioRecordingConfiguration
  .isClientSilenced` has never fired on a device.
- **Nothing has been uploaded to a real Nextcloud.** The matrix's live half runs when a server is
  configured; this run had none, so the media rows' server evidence is the in-process WebDAV
  server — real OkHttp, real sockets, real multistatus parsing, but not a real Nextcloud.
- **The alignment window (−15 s / +5 s) is untuned**, because tuning it needs the server half and a
  real recording. So is `VOICE_RECOGNITION` as the audio source.

### 15.5 Open questions this half raises

1. **Local audio is never deleted.** §8 covers the *remote* copy — trashed after the transcript is
   verified — and says nothing about the tablet's. Uploaded recordings keep their file, so a term
   of lectures accumulates. The app cannot know when deletion is safe, because it deliberately
   never learns whether a transcript exists (§2). The pending list shows sizes; a *"delete uploaded
   audio older than N days"* policy is a decision for the server half, when something exists that
   knows the answer.
2. **A recording in a note that is never bound to Nextcloud waits forever**, correctly and
   silently. `SyncEngine.bind`, `noteRestored`, `resumeAccount` and every `noteChanged` re-enqueue
   pending recordings, so it cannot be *lost* — but nothing tells the user their lecture is going
   nowhere.
3. ⚠️ **A recording has no route off the device except Nextcloud** — found the first time a real
   lecture was recorded on the tablet. The files live in app-private storage, so MTP cannot see
   them and `send-to-tablet.ps1` only works in the other direction; until the note is bound and the
   outbox has drained, the **one copy of an irreplaceable file is on one device**. `adb` covers it
   for a debug build (`tools/pull-recordings.ps1`, which also verifies each file), and `run-as`
   would not work on a release build at all.
   That is arguably the design working as §2 intends — Nextcloud *is* the route — but it means a
   user who has not set up an account records a lecture that is visible in the pending list and
   cannot be got at, moved, or backed up. Worth deciding before this is somebody's only copy of a
   term's teaching.
4. **The `recording` table is never swept.** No row is ever deleted (rule 1), which is right, and
   it grows by one row per recording forever. Bounded by how often somebody lectures, but it should
   eventually be a number somebody has seen.

### 15.6 ⭐ The first real lesson — 48 minutes on the Galaxy Tab

One lesson, recorded end to end on the tablet, deliberately chosen as a hard case: *"a lesson where
things are delayed and things are quite confusing"* — which is precisely the case §7's asymmetric
window exists for and a clean lecture would have said nothing about.

**The container, checked with `tools/verify-ogg-opus.py`** (which parses Ogg by hand rather than
asking the encoder about its own output):

```
12,545,686 bytes                 146,410 complete pages
CRC failures: 0                  torn tail: 0 bytes
final granule 140,550,720   ->   2,928.13 s = 48 m 48 s
```

Zero torn bytes means it was **stopped**, not killed — so *"leaving the note stops it"* held for
forty-eight minutes of real use on real hardware, which no emulator run could establish.

**The timeline: 867 strokes, and every integrity property holds.**

| Property | Result |
|---|---|
| Unparseable lines | 0 |
| Duplicate `objectId`s | 0 of 867 — no stroke logged twice |
| Offsets monotonic | yes |
| Offsets past the end of the audio | 0 |
| Negative offsets | 0 |
| First / last stroke | 6.1 s / 2,907.8 s, against 2,928.1 s of audio |
| Marked gaps | 0 — the microphone was never taken |

**Storage, measured rather than budgeted:** 34.3 kbit/s → **14.7 MB per hour**, ~44 MB for a
three-hour day. §4's ~11 MB was optimistic; the sidecar argument is unaffected and stronger.

#### ⚠️ What the writing pattern says about §7's window

This is the part worth carrying into the alignment half, and it is the reason a *confusing* lesson
was the right fixture. Strokes per five-minute block:

```
 0- 5   99      20-25  208
 5-10  195      25-30   27
10-15   17      30-35   25
15-20   62      35-40  141
40-45   71      45-50   22
```

Writing is violently bursty — 208 strokes in one block against 17 in another. Two consequences:

1. **The long pauses are fine.** Median gap between consecutive strokes is 0.65 s, the 90th
   percentile 2.9 s, and the longest is **340 s** — nearly six minutes of listening without
   writing. Twenty-four pauses exceed the window's 15-second reach back. Those are exactly the
   *"delayed"* case: the window looks backwards into speech that happened while nothing was being
   written, which is what it is for.

2. **The dense blocks make consecutive windows overlap — and that is fine.** At 208 strokes in
   five minutes a line finishes roughly every 15 seconds, so consecutive lines' −15 s windows cover
   much of the same speech.

   ⚠️ **This was first written up here as a design problem with three candidate fixes. It is not,
   and the fixes were worse than the thing they fixed** — recorded rather than quietly deleted,
   because the reasoning error is the useful part.

   The consumer of the merged document is a **reader** — the user, or an assistant with access to
   their Nextcloud — not a matcher that needs disjoint buckets. Overlap is *redundancy*, and a
   reader resolves redundancy trivially: they can see which sentence goes with which line. The
   failure that actually costs something is speech that is **missing**, or speech attached to a
   line it has nothing to do with, and overlap causes neither.

   Every candidate fix — clamp a line's window at the previous line's end, shrink the reach-back
   when lines are dense, attribute speech only to the nearest line — worked by **removing
   context**. Clamping truncates for line B exactly the explanation that prompted both A and B.
   That trades information for a tidier-looking artifact, on the one feature whose whole purpose is
   recovering what the page could not hold. Having all three share that property should have been
   the clue.

   **So the rule for the alignment half is: when in doubt, include more.** Duplicated transcript
   costs a few kilobytes in a file that is already tiny; omitted transcript cannot be recovered
   without the audio, which §8 deletes.

3. ⚠️ **The window may be too *short*, which is the opposite of the above.** Twenty-four pauses in
   this lesson ran longer than the window's 15-second reach back — the longest nearly six minutes.
   In the genuinely delayed case, the speech that prompted a line can sit further back than −15 s
   reaches, and *that* is a loss the reader cannot repair. It is the direction to test first when
   the transcript exists, and it points the same way as the rule above.

The lesson's audio and timeline are kept as the tuning fixture. The before/after numbers ROADMAP
M12 asks for should be measured against **this** recording.

### 15.7 ⭐ The first end-to-end run — measured, not assumed

The 48-minute lesson from §15.6, transcribed and aligned. **$0.19.** This answers §13's spikes 1
and 3 with numbers rather than expectations.

| | Measured |
|---|---|
| Words | 3,111 |
| Model actually used | `universal-3-5-pro` (read from `speech_model_used`, not from what was asked) |
| Speakers separated | 2 — `A` 15.2 min, `B` 3.6 min |
| Word confidence | median **1.00**, 10th percentile 0.70 |
| Speech coverage | 1,131 s of 2,929 s — **39% of the lesson** |
| Merged document | 20,812 characters |

**`VOICE_RECOGNITION` was the right audio source.** The transcript is coherent and the chemistry
survives intact — the molar mass of sodium chloride is transcribed correctly as `35.5 + 23 = 58.5`,
which is the kind of detail a mangled recording loses first. Errors exist and are minor and
recoverable in context (`sodium ion` → *"sodium iron"*).

⚠️ **One honest limit on that verdict.** This lesson is a *dialogue* — two people close to the
tablet, taking turns. It is not the lecture-theatre case §9 worried about, where a teacher is five
metres away over room noise. The audio source is vindicated **for this acoustic**, and the hall case
is still unmeasured.

**The 64 words/minute figure is not poor pickup — it is the lesson.** Speech covers 39% of the
recording, so *during* speech the rate is ~164 wpm, an ordinary conversational pace. The rest is
working time, which is exactly what a maths or chemistry lesson looks like and exactly why §1's
premise holds: the page ends up with `58.5` on it and the twenty minutes of explanation around it
are gone.

#### The dead-time collapse, on real data

The output-side trim (§15.6, from the user's suggestion) removed **9 stretches totalling 7.4
minutes — 15% of the lesson** — from the document. Longest was 00:17:43 → 00:19:02.

⚠️ The important property: **nothing was cut from the audio and no timestamp moved.** Trimming the
*input* would have shifted every word timestamp after the first cut and needed a cut-map to undo,
which mis-aligns a whole lesson in a way that reads as entirely plausible. The saving would have
been about $30 a year against a $112 bill. Collapsing in the output gets the readability and
carries no risk at all — and it uses a signal AssemblyAI does not have, because the timeline knows
when the pen was moving.

#### ⚠️ What this run still cannot show

The recording predates the page column, so it reports **0 pages** and the transcript is unanchored.
Page anchoring — the whole of §15.6's revision — has been built and tested but has **never run on
real data**. The next lesson recorded on the current build is the first that can show it.

---

## 16. What was built — the server half

§2 said the app writes two files and enqueues an outbox op, and that *"the app never talks to
AssemblyAI"*. This section is the other end of that arrow: the thing that notices the two files,
turns them into a transcript, and puts the transcript back.

```
tools/m12/
  nextcloud.py     stdlib WebDAV — PROPFIND, GET, PUT, MKCOL, DELETE
  transcribe.py    AssemblyAI: upload → queue → poll.  `run(bytes, key)` is the library entry
  align.py         timeline + transcript → merged markdown  (pure, no I/O, no network)
  watch.py         ⭐ the loop: discover → transcribe → align → PUT → verify → trash
  test_watch.py    13 tests over a real HTTP server
  test_align.py    20 tests over the aligner
  run-watcher.ps1  launcher; credentials from `watcher.local.properties`, gitignored
```

### 16.1 Running it

```
./tools/m12/run-watcher.ps1 -DryRun -Once     # find work, send nothing, change nothing
./tools/m12/run-watcher.ps1                   # poll every five minutes
```

It is one process with no state of its own — **the server is the state**. That is what makes it
safe to kill, safe to run from Task Scheduler, and safe to run on a different machine tomorrow: a
recording that already has a `.md` beside it is finished work, and a recording that does not is
work to do. There is no queue to corrupt and no cursor to lose.

### 16.2 ⚠️ The ordering, which is the only part that can lose a lecture

```
PUT  <recordingId>.md          write the transcript
GET  <recordingId>.md          ← verify it is really there
DELETE <recordingId>.opus      only now, and DELETE on Nextcloud is soft
```

The verify step between the write and the delete is not defensive padding. A PUT that returned
`201` and a file that is actually readable are two different claims — a proxy, a quota or a
half-written body separates them — and §8 spends the audio on the strength of the first one.
Same shape as `materialize` writing the bundle before resetting the journal: **a crash between
two steps must leave a duplicate, never a hole.**

Three things it refuses, each for a reason already paid for elsewhere in this repo:

| Refusal | Why |
|---|---|
| Never hard-delete | Nextcloud's `DELETE` is soft, and that trash window is the entire recovery story for a mis-transcription |
| Never retry a 401 | CLAUDE.md rule 5 — a polling loop with a stale password is how a device gets its own server to IP-block it |
| Never transcribe twice | The `.md` is the idempotency marker, so a crash costs one repeated download and not a repeated bill |

### 16.3 Evidence — which bucket each claim is in

CLAUDE.md's definition of done #6 asks which of *written*, *automated*, *automated against the
real dependency*, and *run on a device* each claim is. They are not the same set.

| Claim | Bucket |
|---|---|
| Discovery finds `.opus` with no `.md`, skips ones with | automated, real HTTP server |
| Transcript is written back as `text/markdown` | automated, asserted off the **server's** stored bytes |
| Audio is trashed **after** the transcript verifies | automated, real server, soft-delete modelled |
| A failed transcription leaves the audio untouched | automated |
| Two recordings in one note both transcribe | automated — §5's trap, server-side this time |
| A 401 halts the pass | automated |
| The walk terminates and lists each path once | automated |
| Page column → "when each page was written" | automated |
| Percent-encoded folder names | automated — **and it found a real bug**, see §16.4 |
| End to end against a live Nextcloud | ❌ **never run** — see §16.5 |
| AssemblyAI called by the watcher rather than by hand | ❌ **never run** — faked at `transcribe.run` |

⚠️ **The last two rows are the honest limit of this section.** The transcription itself was proven
against the real API in §15.7, and the WebDAV client is proven against a real HTTP server — but
*this loop* has never made both calls in one pass against live services. §16.5 is what closes it.

### 16.4 ⚠️ What the test harness caught that a mock would not have

`test_watch.py` runs the production `Nextcloud` client over a real socket against a hand-rolled
server, for the reason SPEC §13.2's matrix does the same: a `FakeNextcloud` would have agreed with
every mistake the client made. Three bugs, all of which a mock would have passed:

1. **The DAV path was never percent-encoded.** A folder called `Chemistry Y12` — a space, which is
   the *normal* case for a school folder — raised `InvalidURL: URL can't contain control
   characters` before a socket was opened. Caught only because the fixture folder has a space in
   it, which was deliberate.
2. **The `Depth: 1` self-entry was matched by name.** At the account root the collection's own name
   is the *uid*, which matches no child name, so the root was walked as though it were its own
   child — rediscovering itself at every level down to the depth bound. It is `is_self` now,
   computed by comparing hrefs, and flagged rather than dropped because `exists()` asks with
   `Depth: 0` and needs precisely that entry.
3. **A note in the account root** has its media at `.inkstone-media/<id>` with no parent segment,
   so splitting on `/<MEDIA_DIR>/` matched nothing and returned the media path as its own parent.

The third bug had a twin in the harness itself — `add_file` computed a parent with `rsplit("/")`,
which for a root-level file returns the **file name**, registering every root-level file as a
directory too. Worth recording because it is the same lesson from the other side: the nested
fixture could not see it, and only the root case could.

### 16.5 What is left before this is finished

1. **One real pass against the user's Nextcloud.** Everything above is proven in halves. Needs the
   three lines in `watcher.local.properties` and `-DryRun -Once` first.
2. **The app has still never uploaded a recording to a live server.** `MEDIA_UPLOAD` is tested and
   its path convention matches `RemoteMedia.kt` exactly, but M6's lesson — *"a feature can be
   correct in every test and inert in the app"* — is not retired by a matching string.
3. **Page anchoring has never run on real data** (§15.8 already says this). The 48-minute lesson
   predates the page column.
