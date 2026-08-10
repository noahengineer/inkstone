# M12 — recorded lectures, aligned to the notes

**Status: designed, not built.** Nothing in this document exists in the code. It records the
decisions and the reasons, so the milestone starts from a settled design rather than an idea.

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
enqueue outbox op ──────────────────────► media/<documentId>/<recId>.opus
   (waits for a connection)               media/<documentId>/<recId>.timeline
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

A bundle today is ~5 KB. Voice-grade Opus is ~11 MB/hour. A three-hour lecture day would make the
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

The reconciler will see these files. It already has `foreignRemote` (M6's `5.sqm`) for
*"unrecognised file, looked inside once, remembered"* — this must land there and must **not** be
mistaken for a note, nor pruned as a deleted one.

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

Numbered **M12** so that M8–M11's cross-references stay valid; renumbering would invalidate
references throughout SPEC, ROADMAP and six `docs/` files for no benefit. It is *out of scope for
v1* in SPEC §12's sense, which remains true — v1 ends at M11.
