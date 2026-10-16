# Handwriting synthesis — typed text, written in your own hand

The request (2026-10-12): typed text dragged onto the page should appear *"as if they wrote it"*.
It should be vector strokes through the existing ink engine, never identical twice, and learned from
the user's own writing without a training step. It should be always legible, fully offline, and
never a signature. It came as six phases with a stop for review after each.

**This file is phase 1, the design, and the record of the phases built since.** Phases 2 and 3 are
built; §16 and §18 say, claim by claim, what each rests on and what is still owed. It records:

- what the codebase already has for this to stand on;
- the decisions, and why;
- the formats;
- the phases and how each is accepted;
- the questions only the user can answer (§14).

**Status: phase 1 reviewed (§14). Phase 2 is built and walked on the emulator, including a harvest
of real notes; recall was fixed at the line segmenter (§17). Phase 3 — glyph assembly, the Write
panel, placement that matches the note, and the quick start — is built, walked on the emulator, and
stopped for review (§18). Phase 3 has been on the tablet, which found three faults the emulator could
not, all fixed (§18.7). Phase 2's learner has not yet run there with phase 3's re-cut. Phase 4 — the
speed spike, the synthetic corpus, a model trained on it, and its Kotlin inference behind assembly — is
built, walked on the emulator and on the tablet's release build, and accepted (2026-10-14, §19). Phase 5 —
personalisation, the calibrated gate, fidelity and switching — is built, measured on the user's profile
through the tablet's release build, walked on the emulator, and stopped for review (§20). Its main
finding: the rung that makes the network write like the user (P3) costs legibility at every strength
tried, so no rung ships. Phase 6 — the SVG contact sheet test, the debug screen through the real ink
engine, the instrumented ML Kit gate test, and the per-claim table for the whole feature — is built,
run on the emulator, and stopped for review (§21). Its main finding: against the real recogniser the
gate passed a wrong digit or a gained decimal point. At the user's decision (2026-10-16) a token with a
digit is now judged on its first reading that holds a digit. That refuses all of them, and still
lets through a digit the recogniser reads as a letter first (§21.4.1, §21.4.2).**
Code facts are cited as read on 2026-10-12. External facts
(dataset terms, the ML Kit API, the paper, package versions) were checked that day, and each one
says where it was checked. Every number marked *estimate* is replaced by a measurement in the phase
that owns it.

**The user answered four questions on 2026-10-12, and the design below already reflects them (§14):**
- **Inkstone may be sold one day,** so nothing trained on research-only data may ship (D13).
- **The user writes a mix of print and joined-up letters, and wants this usable by anyone,** so
  joined writing and a first-run path for someone with no notes are requirements (D14).
- **Every note is the user's own,** so existing notes are learned from too.
- **Generated ink stays ordinary strokes** (D1).

---

## 0. The short version

- **Generated handwriting is ordinary ink.**
  - Each pen-down of a generated word becomes a `StrokeObject` drawn with the current pen. It takes
    the same `MutableStrokeInputBatch` → `Stroke` → `StrokeGeometryCodec` path that `SyntheticInk`
    already uses.
  - Erasing, selecting, undo, sync, merge, search and the published PDF need nothing new.
  - There is no new object kind and no new op type. One new format key, `hw`, marks a stroke as
    generated and groups a phrase so that it selects as one thing.
- **Two backends sit behind one interface,** in a new platform-free module, `:core:handwriting`:
  - **Glyph assembly** builds words from the user's own harvested words, letter pairs and letters.
    It is the day-one backend and the fallback.
  - A **Graves-style neural model**, run in pure Kotlin, becomes primary for a user once it beats
    assembly on a measured score.
- **Every word is read back by ML Kit before it is shown.**
  - The pass rules are calibrated against how the user's *own* writing reads back, because the
    recogniser's CER is 0.27 on M7's corpus.
  - A failed word is retried, then handed to the fallback. A word nothing can produce legibly is
    refused, never drawn.
- **It learns automatically, and only from the right ink.**
  - It learns after recognition has read a page.
  - It learns only from strokes this tablet's pen actually drew. A local ledger records them, fed
    by `InkSurfaceView.onStrokeCaptured`.
  - Generated ink is never learned from.
  - The user can correct labels on a *My handwriting* screen.
- **The model is pretrained off the tablet and shipped as a ~3 MB asset.**
- **Its training data must allow commercial use, and no public online-handwriting dataset does**
  (IAM-OnDB, BRUSH, UNIPEN, MathWriting, InkSight's inks; §7.2).
  - So it is pretrained on **synthetic handwriting**, built from fonts whose licences allow
    commercial use, animated by a published model of how a pen moves.
  - It learns the user's actual hand on the tablet, from the user's own writing.
- **Personalisation runs on the tablet, only when it is charging and idle,** in three steps:
  - priming with the user's own words (no training);
  - a fitted style vector;
  - small adapters.

  Each step is kept only if it measurably helps. Because the base model is synthetic, the third
  step is the one that does the real work.
- **Real, licensed handwriting is an upgrade path, not a dependency.** The pipeline does not care
  where its data came from, but every model file records its datasets' licences, and a release
  build refuses one that is not commercially clean.

## 1. What the request meets in this codebase

**Vector strokes, rendered through the existing ink engine.**
- *Already here:* `SyntheticInk.generatePageOps` turns generated points into
  `AddObject(StrokeObject)` via `MutableStrokeInputBatch` → `Stroke` →
  `StrokeGeometryCodec.encode` (SyntheticInk.kt:115-138). The codec is `internal`
  (StrokeGeometryCodec.kt:65).
- *So this design:* generates strokes the same way, through one new public encoder in
  `:android:ink` that takes a trajectory and a pen.

**A compact stroke format.**
- *Already here:* core never reads geometry; the stroke blob is opaque to it (SPEC §5.4).
  `InkStrokeSampler` gives core x, y and t only (InkStrokeSampler.kt:61-116):
  - no pressure;
  - a fixed 60 ms between strokes;
  - erased parts still included.
- *So this design:* core gets its own format, `PenTrajectory` (§4.1). It carries pressure, tilt and
  real timing, and `:android:ink` both produces and consumes it.

**Fully offline and on-device, on the existing targets.**
- *Already here:* every `:core` module builds for `iosArm64` and `iosSimulatorArm64`, and the macOS
  CI job links it (`inkstone.kmp-core.gradle.kts:30-44`; root `coreModules`).
- *So this design:* inference is pure Kotlin in `commonMain` (D5). The new module must be added to
  `coreModules`, or guardrail 2 skips it without a word.

**On-device recognition, to label writing and to check output.**
- *Already here:*
  - ML Kit 19.0.0 sits behind a **page-level** port. It returns the top guess per line and word
    boxes, and nothing else: no score, no alternatives, no stroke ids (Recogniser.kt:68-70;
    MlKitRecogniser.kt:117-122; WordBoxes.kt:66).
  - The ML Kit API has nothing finer. `RecognitionCandidate` is `getText()` plus `getScore()`;
    `DigitalInkRecognizerOptions.Builder.setMaxResultCount` exists. (javap on the 19.0.0 and
    common 16.0.0 AARs, 2026-10-12.)
- *So this design:*
  - adds a new single-line port that returns the top few candidates (§5.3);
  - finds letter boundaries itself (§5.4);
  - changes `WordBoxes` to keep which strokes made each word.

**"Always legible and correct."**
- *Already here:* M7's shipped recogniser scored CER **0.272** and word recall **0.714** on 20
  IAMonDo pages (M7-RECOGNITION.md, first table).
- *So this design:* cannot demand exact read-back; that would refuse a large share of good
  handwriting. The gate's rules are calibrated on the user's own writing instead (§8.2).

**Drag-and-drop text.**
- *Already here:* Elements already do a drag-and-drop. The path is `ElementPanel` drag →
  `ObjectLayers.drop` → `viewToPage` → `placeElement` → `NoteEditor.stampElement`
  (ObjectLayers.kt:349-356; InkScreen.kt:923-950; NoteEditor.kt:1532-1553). It is one
  `session.apply`, so one undo entry. Afterwards the result is selected and the Select tool is on.
- *So this design:* the *Write* panel reuses that path end to end, including the drag picture and
  "release over the panel cancels" (§6.2).

**A written phrase should behave as one thing.**
- *Already here:* Element copies carry an `ElementInstance` (`el`) in the format register, and
  `withWholeCopies` uses it so a tap on any piece selects the whole copy
  (SelectionQueries.kt:231-239).
- *So this design:* `hw` joins the same grouping (D2).

**Extending the file format safely.**
- *Already here:* a new object kind makes older builds **fail to open the note**. An unknown
  `SetFormat` key is **kept** (SPEC §4.9.1; `ObjectFormatCodec` decode, ObjectFormat.kt:360-379).
- *So this design:* adds no new kind and no new op type. It adds one format key and one content
  record (D1–D3).

**Background work "while idle, while charging".**
- *Already here:* `RecognitionWorker` runs every 6 h when charging and idle, with no network
  (WorkManagerScheduler.kt:152-165). It has **never been seen to fire** (M7-RECOGNITION.md:252-254).
- *So this design:* gets its own worker with the same constraints. Phase 2 is not accepted until
  that worker has been seen firing on the tablet.

**Tied to the user's account, stored locally.**
- *Already here:*
  - `SyncAccount(id, serverUrl, uid, displayName, …)`. Several accounts are allowed, and the id is
    stable for a given (server, uid) (SyncPorts.kt:37-54; NextcloudAccounts.kt:120-170).
  - All other local data belongs to the install, not the account.
  - The manifest has `allowBackup="false"` (AndroidManifest.xml:54-55).
- *So this design:* keeps one profile directory per account id (§4.2). It is already excluded from
  Google backup.

**Learn *the user's* handwriting, not anyone else's.**
- *Already here:* no per-stroke author exists.
  - Folded objects carry no clock.
  - An op's `deviceId` identifies the install, and copies, snapshots, Element stamps and Recovered
    imports all re-stamp it (DeviceIdentity.kt:22-80; NoteStore.kt:479-483; ElementLibrary.kt:336;
    RecoveredImport.kt:89-90).
  - `onStrokeCaptured` does fire **only** for strokes the pen just drew, with the time of the
    down event (InkSurfaceView.kt:564-595).
- *So this design:* keeps a local **pen ledger** of what this install's pen drew. Nothing else is
  learned from without an opt-in (D8).

**Ship pretrained weights.**
- *Already here:* there is no `app/src/main/assets`, and "no new asset in the APK" has been a point
  of pride (SPEC §4.9.5). The release APK is 87.0 MB.
- *So this design:* the weights become the app's first asset, about 3 MB, read through a small port
  (§4.4).

**Scope.**
- *Already here:* SPEC §1.2 says *"No AI"*. §12 keeps *"AI features"* out of v1 because
  *"cloud AI contradicts offline-first"*.
- *So this design:* is fully on-device, so the stated reason does not apply. Moving it into scope is
  still the user's decision, and asking for the feature is that decision. §1.2 and §12 are amended
  when phase 2 starts, in the same commit.

## 2. Decisions

**D1. Generated handwriting is ordinary ink.**
- *Decision:* one `StrokeObject` per generated pen-down. It is drawn with the pen of the user's own
  writing around it (D15), or the current pen where there is none. Pressure, tilt, timing and
  `strokeUnitLengthCm` are supplied explicitly.
- *Why:* everything strokes already do works with nothing added: erase, select, recolour, move,
  undo, sync, merge, `/Ink` in the PDF, M7 search.
- *Rejected: a text box drawn in your hand* (editable, one object). Its pixels would be computed on
  each device from that device's profile and model version. The tablet, the phone and the published
  PDF would then show *different* handwriting for the same note. What is stored has to be what
  everyone sees; keeping that true is what sync is for.

**D2. One format key, `hw`, on every generated stroke.**
- *Decision:* `hw=<group>:<backend>:<model>`.
  - `group` is a UUIDv7 per phrase.
  - `backend` is `a` (assembly) or `n` (neural).
  - `model` is the first 8 hex digits of the base model's SHA-256, or `-`.
- *Why — selection:* one tap selects the whole phrase, through `withWholeCopies`. The key is
  `group@placement`, exactly as `ElementInstance.copyKey` is, so a duplicated phrase counts as a
  second phrase.
- *Why — learning:* tagged strokes are kept out of the dataset permanently (D8).
- *Why — provenance:* it records which backend and model wrote the ink.
- *Compatibility:* an older build keeps the key in `extras`. Values avoid `;` and `=`, which the
  record syntax reserves, just as `ElementInstance` ids do.

**D3. The phrase's text rides on its first stroke,** in the content register:
`SetTextContent(first, "hw1\n" + text)`.
- *Why:* *Write again* and *Edit text* (§6.2) need the original words.
- *Why this register:* `SetTextContent` is the vocabulary's one arbitrary-length object register
  (SPEC §4.2), and nothing reads it for a stroke today. `copiedObjectOps` carries any text that is
  not a `ShapeRecord` verbatim (NoteDuplicate.kt:242-246).
- *If that stroke is erased or deleted,* the phrase becomes plain ink. That is a degrade, not a
  fault.

**D4. A new module, `:core:handwriting`.**
- *Decision:*
  - KMP, `commonMain` only, depending on `:core:model` and `:core:search`;
  - its shared types and ports (`PenTrajectory`, `InkReader`, `ModelSource`) go in `:core:model`;
  - the profile is stored as **files**, not database tables.
- *Why the ports go in `:core:model`:* recognition's ports already live there (Recogniser.kt).
- *Why files:* `ElementLibrary` is the precedent for a file store in core over `FileSystem`.
  §13.1's disaster-recovery story deletes the database, and the user's label corrections are the
  one part of the profile that cannot be re-derived.
- *Why `:core:search`:* to reuse `LineSegmenter`, `WordBoxes` and `TextDistance`. That is one new
  edge inside `:core`, and it is acyclic.

**D5. Inference runs in pure Kotlin, in `commonMain`.** No LiteRT or ONNX Runtime in core.
- *Why:*
  - guardrail 2;
  - no new version pin to verify (rule 3);
  - one fixed architecture is a few hundred lines;
  - it is deterministic, so it can be tested against Python's numbers.
- *Risk:* speed. Phase 4 therefore opens with a spike on the tablet's release build (§7.5). The
  measured fallback is LiteRT behind the same interface, in an `:android` module.

**D6. The model is Graves's synthesis network, sized down.** It is pretrained off the tablet on
commercially clean data only (D13) and shipped as `assets/handwriting/base-v1.ihwm`.
- *Why:* it is the boring, well-understood choice for vector output on a CPU. Its documented failure,
  *"missing, confused, or garbled letters"*, is exactly what the gate (D9) exists to catch. Nothing
  heavier earns its risk at a few MB.

**D7. Personalisation is a ladder.** Each rung is kept only if it raises a measured score.
- *The rungs:*
  - **P0:** the user's measured slant, size and spacing.
  - **P1:** priming with the user's own words.
  - **P2:** a fitted style vector.
  - **P3:** small adapters.
- *Why:* Graves says priming *"appears to work better for sequences in the training data than those
  the network has never seen"*, and the user is a writer the network has never seen. P1 alone will
  not get far.
- *With a synthetic base model,* the user is unlike *anything* in the training data, so P3 is where
  most of the user's hand is learned. These are the expensive rungs, and they have to earn their
  place (§8.1).

**D8. Learn only from ink the user's own pen drew.**
- *New writing:* the pen ledger.
- *Older notes:* learning from notes written before the feature existed is offered once, when the
  feature is first enabled, with per-folder exclusions. It is **on** for this user (§14).
- *Never learned from:* generated (`hw`), Element (`el`), merged, recovered, imported or highlighter
  ink.
- *Duplicates:* byte-identical geometry is learned from once. A duplicated word is the same sample,
  not new evidence.
- *Why:* as §1 shows, nothing else reliably means *this pen, this hand*. Feeding generated ink back
  into the dataset would teach the model its own mistakes.

**D9. Every word is read back before it is shown.**
- *Decision:* acceptance is calibrated against the user's real writing. A failing word gets bounded
  retries, then the fallback, then it is **refused**.
- *Why:* *"always legible"* can only be as strict as the recogniser is accurate. A refused word is
  honest; garbled ink on a page is the one outcome the request forbids.

**D10. The profile is per account, local, viewable, correctable and deletable.**
- *Decision:* deletion goes to a handwriting trash and is purged at 30 days, like notes. The profile
  is never synced and never written into a note's bundle.
- *Why:* the request's safety section, and handwriting is a behavioural biometric. Rule 1 applies to
  the corrections.

**D11. Signatures.**
- *Decision:*
  - a request whose text is only the account holder's name (Nextcloud display name, any order,
    initials allowed) is refused;
  - generated strokes are marked in the published PDF;
  - the harvester never learns from ink it cannot read.
- *Why:* *"never generate signatures"* needs a rule a test can check. How strict to make it is Q6.

**D12. ML Kit's model for the profile's language is a prerequisite.**
- *Decision:* without it there are no labels and no gate, so nothing is generated. The panel says so
  and offers the existing download.
- *Why:* models are never downloaded automatically (MlKitModels.kt:17-21).

**D13. Shipped weights are trained only on data whose licence allows commercial use.**
- *What qualifies:* data we generate ourselves; the user's own writing; or data licensed for the
  purpose.
- *How it is enforced:* the `.ihwm` header lists every training dataset with its licence. A release
  build refuses weights carrying any non-commercial licence, and a unit test pins that refusal.
- *Research-only corpora:* they are not used to train anything that could ship. Even using them to
  *evaluate* a product's model is arguably not "non-commercial research", so that use waits for the
  owners' answer too.
- *Why:* the user may sell Inkstone (§14). Weights trained on a dataset carry its terms.
  Retraining after launch is the expensive way to find that out.

**D14. Usable by anyone, not just by a print writer with months of notes.**
- **Joined writing is first-class:**
  - assembly joins letters where the user's own pairs show a join (§6.1);
  - attention-based segmentation (§5.4 v2) is a priority, not a refinement.
- **A brand-new user is offered an optional quick start:** about three minutes copying prompted
  sentences that cover the alphabet. Prompted text is its own label, so no recognition is needed.
  It is never required. Without it the feature waits until enough writing has been harvested.
- **Each profile has a language.** The recogniser and the alphabet follow it. Assembly covers any
  character the user has written; the model covers the alphabet its synthetic data was built from.
- *Why:* the user's answer to Q2 (§14).

**D15. Dropped text matches the note it lands in.**
- *The aim, in the user's words:* *"drag and drop a piece of text in and it looks just like you did
  it (stays consistent with the note)"*.
- *Decision:* at drop time, a `StyleContext` is measured from the user's own writing around the drop
  point. The ledger says which ink is theirs. It holds:
  - **the pen:** the brush kind, colour and width that dominate the nearest lines;
  - **the geometry:** x-height, slant, baseline angle, line pitch and left margin;
  - **which samples to prefer:** this page first, then this note, then recent writing, then the
    rest.
- *How the backends use it:*
  - the neural backend primes with the nearest lines on the page;
  - assembly weights its variants by the same proximity;
  - the pressure model is refitted to the page's own strokes.
- *Continuing a paragraph:* text dropped next to existing writing continues it. Its baseline lands on
  the paragraph's line pitch, its left edge on the paragraph's margin, and it wraps at the
  paragraph's right edge.
- *Why:* the user's own average hand, in the wrong pen, at the wrong size, three degrees off the line
  beside it, is exactly what makes text look pasted in. People's writing drifts between notes: neat
  in one, rushed in a lecture. The note is the reference, not the average.
- *Staying consistent afterwards* is D1's job. Once placed, the text is ink: the same on every
  device and in the PDF, permanently.

## 3. Architecture

```
:core:model        + PenTrajectory · InkReader (one line, top-k) · ModelSource (the weights' bytes)
:core:handwriting  NEW — KMP, commonMain only. Deps: :core:model, :core:search
  trajectory/        codec · resampling · NormalFrame (baseline, x-height, slant)
  profile/           ProfileStore over FileSystem · WordSample · glyph index · PenLedger · LabelBook
  harvest/           eligibility · word labelling · GlyphSegmenter (v1 gaps + recogniser, v2 attention)
  layout/            words → lines · wrap width · baseline drift · spacing
  backend/           HandwritingBackend · GlyphAssembly · NeuralBackend
  neural/            SynthesisNetwork (LSTM, window, MDN) · WeightsFile · Sampler · priming · P2/P3
  gate/              ReliabilityGate · GateCalibration
  quality/           Fidelity (DTW + chamfer) · BackendPolicy
  HandwritingSynthesizer — the one entry point the app calls
:core:search       ~ WordBoxes keeps each word's stroke ids, and says whether the gaps matched the words
:android:ink       + TrajectoryInk: a page + ids → PenTrajectory (pressure, tilt, real timing)
                                    PenTrajectory + PenConfig → StrokeGeometry, off the main thread
:android:ocr       + MlKitInkReader : InkReader (one client per batch, setMaxResultCount)
:app               Write panel · drag · Write again · text box → handwriting · My handwriting
                   neatness preference · ledger wiring · HandwritingWorker · DI
tools/handwriting  Python, off the device: prepare → train → sample → export → golden vectors
app/src/main/assets/handwriting/base-v1.ihwm
```

The dependency direction is unchanged: `:app` → `:android:*` → `:core:*`. The web reader and the MCP
server need nothing, because generated ink reaches them as ordinary PDF `/Ink`.

**The seam that makes backends swappable.** Capture and harvesting produce `PenTrajectory`s, and
rendering consumes them. Neither names a backend.

```kotlin
interface HandwritingBackend {
    val kind: BackendKind                       // ASSEMBLY, NEURAL; a third is a new class + a policy row
    /** One word in the normalised frame (§5.2), or null when this backend cannot write it. */
    suspend fun word(text: String, style: StyleState, rng: Random): PenTrajectory?
}

class HandwritingSynthesizer(/* profile, backends, gate, policy */) {
    /** Words in reading order, each one as it passes the gate — or Refused, with the reason. */
    fun write(request: WriteRequest): Flow<WordResult>
}
```

**Threads.**

| What | Where it runs | The rule it keeps |
|---|---|---|
| Pen ledger | `onStrokeCaptured` adds an id to a list on the UI thread; the file write rides the journal flush | Rule 7: no I/O on the ink path |
| Harvesting | `HandwritingWorker` (charging + idle), and *Learn now*. It reads a page through the session gate and does everything else unlocked | `NoteRecogniser.readPage`'s three phases; the lesson of M8-UNDO-LAG §5 |
| Generation | `Dispatchers.Default`, words in parallel, streamed to the panel | Never on the UI thread |
| Encoding blobs | `TrajectoryInk` off the main thread, before the editor lock is taken | Encoding is costly (StrokeGeometryCodec.kt:59-63) |
| Applying | One `session.apply` in the editor lock: `stampHandwriting`, mirroring `stampElement` | One undo entry |
| Tessellating the new strokes | Today: `reconcile` → `StrokeStore.addObject` on the main thread (StrokeStore.kt:931-943) | **Measured in phase 3.** If a 40-character phrase janks, build the `Stroke`s off the thread first, as `PreparedPage` does |
| Personalisation | WorkManager, charging + idle, chunked, checkpointed | Survives being killed |

## 4. Formats

### 4.1 `PenTrajectory` — the compact stroke format (`:core:model`)

```kotlin
class PenTrajectory(
    val x: FloatArray, val y: FloatArray,  // page points (harvested), or the normalised frame (§5.2)
    val tMs: IntArray,                     // ms from the first point; strictly increasing within a stroke
    val pressure: FloatArray?,             // 0..1, or null for unknown (synthetic and public data lack it, §7.1)
    val tilt: FloatArray?,                 // 0..π/2 radians, or null
    val strokeEnds: IntArray,              // exclusive end of each stroke; the pen lifts after each
    val unitLengthCm: Float?,              // androidx.ink's stroke_unit_length for these points
)
```

It uses arrays, not a point class, because a word is hundreds of points and a profile is thousands of
words.

**Its invariants are androidx.ink's own**, as read from `libink.so`'s validation messages:
- positions are finite;
- time is finite, non-negative and non-decreasing;
- no (position, time) pair repeats;
- pressure is in [0, 1] and tilt in [0, π/2];
- each channel is present on every point or on none.

Construction in core refuses a trajectory that breaks any of them. The reason is that
`MutableStrokeInputBatch.add` *throws* on bad input (SyntheticInk.kt:148), and a generated stroke must
never be the thing that throws inside the editor lock.

Per-stroke metadata is kept beside the trajectory in the sample record (§4.2): the source `ObjectId`,
the brush kind, and whether the ruler was engaged.

### 4.2 The profile on disk

```
handwriting/                              beside notes/ and elements/ — never under the top-level trash/,
│                                         whose every entry NoteTrash reads as a DocumentId (NoteTrash.kt:87-98)
├── ledger/<documentId>.ledger            per INSTALL: "<objectId> <downElapsedMs> [r]" per line (r = ruler on)
└── <accountId>/                          per ACCOUNT; local/ until the first sign-in, adopted then
    ├── profile.json                      version · base model sha · settings · stats · gate calibration · policy
    ├── words/<n>.hws                     word samples (below)
    ├── labels.json                       corrections and exclusions — the one file that cannot be re-derived
    ├── glyphs.idx                        letter / pair / triple → (word, point range); derived, rebuilt at will
    ├── style/                            P1 prime set · P2 style vector · P3 adapters, each naming its base model
    └── trash/                            displaced labels, style artefacts, deleted samples; purged at 30 days
```

**A word sample holds:**
- its id (UUIDv7);
- its source (note, page, stroke ids);
- the day it was written (a day, not a time);
- its label and where the label came from: `AGREED`, `CONFIRMED` or `CORRECTED`;
- the recogniser's alternatives at labelling time;
- its frame (§5.2);
- the trajectory **as measured**, relative to the word's origin;
- quality flags;
- its split, `TRAIN` or `HELD_OUT`.

**Stored as measured, not normalised.** The normaliser will improve, and a stored normalisation
cannot be undone. SPEC §3.5 stores the eraser's sweep rather than the cut geometry for the same
reason.

**Encoding.**
- Versioned binary, magic `IHWS`.
- Each channel is stored as zig-zag varint deltas of quantised integers: x and y at 1/100 pt, time at
  1 ms, pressure and tilt at 1/1023 of their range.
- *Estimate:* 6 bytes a point, 1–2 KB a word, about 30 MB at 20,000 words. Phase 2 measures it.
- Every write goes through `writeBytesAtomically`. `labels.json` moves its previous version to
  `trash/` first (rule 1).

**The dataset follows the notes.**
- A sample whose source strokes were later deleted, erased, or rotated or scaled is withdrawn to
  `trash/` at the next pass. A word the user scrubbed out was probably not one they would have chosen
  to copy.
- A note in the note trash keeps its samples until it is purged at 30 days. Restoring a note loses
  nothing, and a purged note leaves nothing behind. (Q8.)

### 4.3 In the note

Each phrase is written as **one** `session.apply`, and so one undo entry. It contains:

- one `AddObject(StrokeObject)` per stroke. Z keys come from `SelectionEdit.keysAbove(topZ, n)`. The
  style register is null (*as captured*), just as for real ink.
- one `SetFormat(hw=…)` per stroke.
- one `SetTextContent(hw1…)` on the first stroke.

**In the published PDF** these are ordinary `/Ink` annotations with `/AP` (SPEC §6.4). Each generated
annotation also gets a private `/INKS_Generated` key (D11). The text layer needs nothing extra: M7
recognises generated ink like any other ink, and the gate has already established that it reads.

⚠️ **Two copy paths need attention, and one of them is already broken.**
1. **Note and page duplication:** `copiedObjectOps` copies the format register verbatim
   (NoteDuplicate.kt:252-259). That is right for `hw` *because* the group key includes the
   placement. A duplicated page's phrase is a second phrase, for the same reason a duplicated Element
   copy is a second copy (SPEC §4.9.7).
2. **Selection duplicate: `SelectionController.duplicate` drops the format register entirely for
   strokes and shapes** (SelectionController.kt:1620-1639).
   - `strokeDuplicated` enqueues the `AddObject` alone (PageOpRecorder.kt:157-177).
   - `StrokeStore.duplicate` copies no format (StrokeStore.kt:1189-1219).
   - `ShapeEdit.duplicate` writes the content record but no format (ShapeEdit.kt:86-104).
   - Text boxes and images do keep theirs (SelectionController.kt:1713).

   **This already loses `ko` and `el` today:** a duplicated knockout stroke stops knocking out. So it
   is reported as its own task (§15) rather than fixed inside this feature. Until it is fixed, a
   duplicated generated phrase becomes plain, ungrouped ink, which the ledger keeps out of the
   dataset anyway.

### 4.4 The weights file

`.ihwm` contains, in order:
- the magic `IHWM` and a format version;
- the architecture: layers, cells, window components, mixture components, style dimension;
- the alphabet as one UTF-8 string (index = position);
- the input normalisation constants;
- provenance: dataset names and SHA-256s, `tools/handwriting`'s git sha, and the seed;
- the named tensors, each with name, shape, `f16|f32`, little-endian.

**The file's SHA-256 is the base model's identity.** Every personalisation artefact records it, and
goes to trash when the base model changes.

Core reads the file through `ModelSource`, a `ByteArray?` supplied by the platform, like
`PdfFontFaces`. A file that fails its own checksum turns the neural backend off and leaves assembly
running.

## 5. Phase 2 — capture, normalisation, labelling, harvesting

### 5.1 What is learned from

A stroke is eligible when **all** of these hold:

1. **It came from the right source.** Either it is in the pen ledger, or it was written before the
   feature existed in a note the user opted in. This user opted in every note (Q3); folders can
   still be excluded later.
2. **It used a writing brush:** fountain, ballpoint or pencil. Not the highlighter.
3. **It carries no `hw` or `el` format.**
4. **Its transform is a pure translation.** It may have been moved, but not scaled, rotated or
   skewed.
5. **It has no erased region.** `InkStrokeSampler` ignores erasure, so a partly erased stroke would
   be learned whole.
6. **It was not drawn against the ruler.** The ledger line carries the flag; the surface knows, and
   `onStrokeCaptured` gains it.
7. **Its note is not locked and not excluded** on the *My handwriting* screen.

The ledger is fed **only** by `onStrokeCaptured`. By design that callback never fires for these
(InkSurfaceView.kt:582-586):
- `addAll`;
- page loads;
- undo;
- merges;
- Element stamps;
- generated phrases.

A stroke that snapped into a shape leaves an id that matches no stroke, and is ignored.

### 5.2 Normalisation

This is done per **line**, using M7's lines, then per word.

- **Baseline:** a robust line fit through the lower envelope of the line's strokes, with
  descenders removed as outliers (median + MAD). M7 found the box *bottoms* to be the stable feature
  of a line (SPEC §8.2).
- **x-height:** the median distance from the baseline to the upper envelope, with ascenders
  removed the same way.
- **Slant:** the length-weighted circular mean of pen-down segment angles within ±45° of vertical.
- **The frame:**
  1. translate the word's left baseline point to the origin;
  2. rotate the baseline level;
  3. shear by minus the slant;
  4. scale the x-height to 1.
- **What is stored:** each sample keeps its (baseline angle, x-height in pt, slant, origin). Their
  distributions over the profile *are* part of the style; that is P0.
- **One estimator, two callers.** The same code measures the page around a drop point at drop time
  (D15). So it is built as a measurement of a page's lines (`PageStyle`), with per-sample
  normalisation as one of its uses, rather than as a private step of the harvester.

Synthetic fixtures check the arithmetic. M7's warning applies verbatim, though: *"a synthetic
fixture is the worst possible witness for a geometric heuristic."* The acceptance check is
therefore real ink. Normalise the words of the physics note's emulator copy, render them before and
after, and look at the result.

### 5.3 Labelling, a word at a time

**Two changes to existing code:**
1. **`WordBoxes` keeps each word's stroke ids, and reports whether the gap clusters matched the word
   count.** Today it keeps boxes only, and when the counts disagree it falls back to slicing the line
   by characters without saying so (WordBoxes.kt:34-46, 66). Only words whose gaps matched are
   harvested.
2. **A new port, `InkReader`, in `:core:model`,** implemented by `MlKitInkReader` in `:android:ocr`:
   - one ML Kit client per batch, not one per call;
   - `setMaxResultCount(k)`;
   - a `RecognitionContext` with the previous word as pre-context and a word-sized `WritingArea`.

   It returns each candidate with its score. The score's range and direction are **undocumented**,
   so phase 2 records the scores and does not threshold on them.

**A label is `AGREED`** when two reads give the same word: M7's line read of that word, and a fresh
word-level read of the same strokes. The word-level read must also be **steady**: at least two of
its other guesses must be the same word again, ignoring case, spacing and punctuation
(`Harvester.MIN_ECHOES`). The user's corrections are `CORRECTED`, and their approvals `CONFIRMED`.
Only these three are learned from. Anything else is shown on the review screen and nowhere else.

*(The steadiness rule was added after the first device harvest, §16.5. The two reads are not
independent: they read the same strokes, so a drawing the recogniser takes for a word is taken for it
twice. On real notes 16 of the first 18 "words" were drawings, and every one of them failed the
steadiness rule, while the two real words passed it.)*

**One consequence is worth stating: the dataset is filtered for legibility by construction.** A word
ML Kit cannot read twice is not learned from unless the user labels it. That is the right bias for a
system whose output must be legible.

### 5.4 Letters, from words (glyph segmentation)

**v1, in phase 2.**
- *Candidate cut points:* stroke boundaries, plus the low points between letters inside a stroke
  (in y-down page space, local maxima of y).
- *Delayed strokes* (i dots, t crossings) attach to the letter whose x-range covers them.
- *Choosing the cuts:* a dynamic program picks the cuts so that each segment, read on its own with
  k = 5, includes its expected letter. Only segments that pass are kept.
- *Cost:* O(cuts²) recogniser calls per word, so it runs only in the charging-and-idle worker,
  capped per pass.
- *Limit:* it will do poorly on cursive.

**v2, in phase 4.** This is forced alignment with the synthesis network's own attention window.
1. Run the network teacher-forced over the word, with its label.
2. φ(t, u) gives, for each point, a distribution over the word's letters.
3. A monotonic Viterbi pass turns that into a segmentation.

It works for cursive, and costs one forward pass per word. Since the user writes a mix of print and
joined letters, and the feature is meant for anyone (D14), **v2 is phase 4's first use of the
model, before generation.**

**Measured, not asserted.** The synthetic corpus (§7.2) knows the character of every point, so
segmentation accuracy is a number in the phase 4 notes rather than a claim. Synthetic ink is a
generous witness, though; M7's lesson applies. So the real-ink check is the user's own words:
- a sample of v1 and v2 segmentations from the physics-note copy, drawn on a contact sheet and
  checked by eye;
- every one the user corrects on *My handwriting* becomes a labelled test case.

BRUSH, which labels every point (its README), would be the better witness, but only if its owners
license it (§7.2).

### 5.5 What is kept

*Starting caps, all estimates:*
- whole words: up to 8 variants each;
- letter pairs and triples: up to 12 each;
- single letters: up to 16 each;
- up to 20,000 words in total.

Eviction is by quality, then diversity, then recency.

**10 % of words are held out.** They are never primed with, never trained on, and never used by
assembly. They exist only for the fidelity score (§8.3).

### 5.6 When it runs

**Never in the editor.**
- **`HandwritingWorker`** is periodic and runs only when charging and idle, with no network
  constraint. It harvests pages whose recognised text is current and that have unharvested ledger
  strokes.
- **"Learn from my writing now"**, on the *My handwriting* screen, runs the same work as an
  unconstrained one-shot job.

Learning may lag writing by a night, and that is fine. What is not fine is ML Kit calls competing
with the pen. M8-UNDO-LAG §5 is the reminder of what a lock held across recognition does to the
editor.

### 5.7 *My handwriting*

The screen shows:
- **coverage:** which letters, with how many variants each;
- the newest samples;
- low-confidence labels first.

From it the user can:
- correct a label, delete a sample, or exclude a whole note;
- pause learning;
- opt older notes in, per folder;
- see the fidelity score over time once phase 5 exists;
- delete everything, which goes to `trash/`.

## 6. Phase 3 — glyph assembly, and the Write panel

### 6.1 Assembly

**Building a word.**
- *Units:* cover the text with the longest units available — the whole word, then triples, then
  pairs, then single letters.
- *Choosing a variant:* pick at random among each unit's variants, weighted by quality. Never reuse
  a variant within a phrase while another one exists.
- *Placing units:* units sit on the frame's baseline, each by its own baseline. The gap between two
  units is drawn from the user's measured gaps for that pair of letter classes.

**Variation.** Everything is bounded, drawn fresh per generation, and tuned by eye on the visual
harness (§11). The values below are starting points:

| Scope | What varies | Starting bound |
|---|---|---|
| Per unit | Scale | ±4 % |
| Per unit | Slant | ±2° |
| Per unit | Baseline offset | ±0.04 x-height |
| Per unit | Rotation | ±1° |
| Per line | Baseline drift (a smooth random walk) | ≤ 0.08 x-height across the line |
| Per line | Slant drift | — |
| Per stroke | Wobble: a displacement of two or three random sinusoids along the arc length | ≤ 1.5 % of the x-height |
| Per word | Word gap | From the user's measured distribution |

The frame is then undone with a slant, x-height and baseline drawn from the user's *own*
distributions (P0), at the size the placement asks for (§6.2).

**Never identical twice.** Every drop seeds a fresh generator. A test drops one sentence N times and
asserts N pairwise-different trajectories (§11).

**Timing and pressure** come from the source samples. Pen-up gaps between units come from the
ledger's real down times.

**Characters the user has never written** go to the neural backend once it exists. Before phase 4,
the word is refused, with a reason (§8.2).

**Joins.** Where the user's own pairs show that a letter's exit runs into the next letter's entry,
assembly joins the two units:
- it draws a short bridge from the exit tangent to the entry tangent, with its length and curvature
  taken from the user's measured joins for that pair class;
- it merges the bridge and both letters into one stroke.

Where the user lifts the pen between two letters, they stay apart. A mixed hand comes out mixed in
the same places the user's does.

**Being honest about it anyway.** A join assembly has never seen is a guess. For a mostly cursive
writer, assembly will look stitched until pairs and words cover the text, or until the neural
backend takes over. That is the main reason the neural backend exists.

**Motor-plausible variation, as the upgrade to jitter.** The sigma-lognormal model describes a
stroke as a sum of lognormal velocity pulses (§7.2). Fitting it to a harvested letter and then
perturbing its parameters gives variants that move the way a hand moves, rather than the way noise
moves.
- It costs a parameter-fitting step per unit, at harvest time.
- It is built in phase 4, alongside the synthetic-data engine that needs the same code.
- It replaces the table above only if the harness sheets show the jitter looks mechanical.

**A brand-new user** with nothing harvested gets the optional quick start (D14). Until enough
writing exists, the panel says what is missing instead of drawing a stand-in.

### 6.2 The Write panel

**The tool.** A `WRITE` chip (`ChromeItem` plus `EditorTool`) opens a side panel, following
`ElementPanelLayer`'s precedent. The panel has:
- a text field;
- a live preview, generated as you type with a debounce;
- a neatness slider;
- a wrap width.

**Placing it.**
- *Drag* the preview onto the page using the Element drag: the picture drags at once, the ghost is
  drawn at page scale, and releasing over the panel cancels.
- *Tap* to continue the nearest paragraph: the phrase goes on the line after its last one.
- *Alignment:* the phrase's **left baseline point** lands at the drop point. Text aligns by its
  baseline; Elements are centred. Dropped beside existing writing, it snaps to that paragraph's line
  pitch and left margin (D15).
- *Ruled and Cornell paper:* with no writing nearby, the baseline snaps to the nearest rule.
  `SnapGrid.forBackground` already knows the paper's lattice.
- *Matching the note* (D15): the pen, size, slant and baseline angle come from the user's own
  writing around the drop point. Size falls back in this order:
  1. the user's own x-height on this page, if they have written on it;
  2. otherwise, the profile's median — on ruled paper, no bigger than fits between two rules;
  3. the ruling decides only where lines go. *(It decided the size too, until the tablet: §18.7.)*

  A *Match the note* switch in the panel, on by default, uses the current pen and the profile's
  median instead.
- *Afterwards:* the phrase is selected and the Select tool is active, exactly as after an Element
  drop.

**On a selected generated phrase,** the selection bar gains:
- **Write again** — new strokes, one undo entry;
- **Edit text** — opens the panel with the words; *Done* replaces the phrase, also as one undo entry.

**Text box → handwriting** is a chip on a selected text box. It uses the box's frame width as the
wrap width, and tombstones the box and adds the phrase in one entry. (*"There is no op that turns
one object into another kind"*, ElementLibrary.kt:360-362.)

**The neatness preference** is `ink.handwriting.neatness`. Adding it touches four places: a defaulted
field in `EditorPrefs`, a range-checked key in `EditorPrefsCodec`, an `InkUiState` var listed in both
`adopt` and `toPrefs`, and the slider.

### 6.3 Applying it

1. `TrajectoryInk` encodes the blobs off the main thread, with:
   - the current pen;
   - the user's input model;
   - a `strokeUnitLengthCm` equal to the median of the user's own harvested strokes, or 2.54/72 cm
     per point (100 % zoom) if there are none, so the fountain pen's speed term behaves as it does
     for them.
2. `stampHandwriting` then applies them through `NoteEditor.editShownPage(readAgain = true)`: the
   editor's own lock, `saveLocked`, the ops from `PhraseOps.build`, `session.apply`, `afterEdit`, and the
   page marked unread so recognition reads the new words. *(Built as a method on `NoteEditor` first;
   moved out in phase 3 so there is one edit path, not two.)*
3. `DryObject.copyKey` gains the `hw` branch.

## 7. Phase 4 — the model, its training, and inference on the tablet

### 7.1 The network

**Graves 2013, *Generating Sequences With Recurrent Neural Networks*, §5** (checked against the paper
on 2026-10-12):
- three LSTM layers of 400 cells;
- a 10-Gaussian soft attention window over the one-hot text;
- 20 bivariate Gaussian mixture components plus a pen-up probability;
- about 3.7M weights;
- offsets normalised to mean 0 and standard deviation 1;
- biased sampling, eqs. 61–62;
- sampling ends when φ(t, U+1) exceeds every φ(t, u).

**Ours.**
- *Size:* between 3×256 (*estimate* ~1.7M parameters) and 2×384, chosen by measuring both. The target
  is ≤ 3.5 MB at fp16.
- *One addition:* a 32-dimensional **style vector** is fed into every layer, and learned per writer
  during pretraining. This is what P2 fits for the user.
- *The alphabet:* whatever the synthetic data covers. The Hershey set includes Latin, Greek,
  Cyrillic and many symbols, so this can be wider than any public handwriting dataset. Anything
  outside it is assembly-only.

**Pressure and tilt are in neither the synthetic data nor any public online dataset.** So:
- The network outputs geometry only.
- Pressure and tilt come from a small per-user model fitted to the user's own samples. Pressure is a
  function of normalised speed, curvature, and distance from the stroke's ends, fitted as a few
  least-squares coefficients and refitted as the profile grows.
- Times come from the user's measured pen speed.

### 7.2 Data — commercially clean, by construction

**Every public online-handwriting dataset found forbids commercial use.** All were checked on
2026-10-12:

| Dataset | What it is | Terms |
|---|---|---|
| IAM-OnDB | ~86k words by 221 writers; needs registration | *"may be used for non-commercial research purpose only"* (fki.tic.heia-fr.ch) |
| BRUSH | 27,649 samples by 170 writers; a character label on every point | *"may only be used for non-commercial research purposes"*; other uses by contacting Prof. James Tompkin (its GitHub README). A third-party listing claims commercial use is fine; the dataset's own terms say otherwise |
| UNIPEN | the classic benchmark | *"Commercial use of UNIPEN data proper … is prohibited"* (its Zenodo record) |
| MathWriting (Google) | ~230k handwritten maths expressions | CC BY-NC-SA 4.0 (its dataset card) |
| InkSight's released inks (Google) | inks derendered from images, plus traced inks | *"CC BY-NC-SA 4.0 + non-distribution"*; only the code is Apache 2.0 (arXiv 2402.05804) |
| IAMonDo (M7's test corpus) | whole pages with line labels | the same IAM terms. ⚠️ M7's accuracy harness already uses it at development time; if Inkstone is sold, that use needs the same answer from the owners |

**So the shipped base model is pretrained on handwriting we synthesise.** The training script owns
every byte.
- **Letter skeletons come from single-line fonts whose licences allow commercial use:**
  - **The Hershey fonts:** *"may be used by anyone for any purpose, commercial or otherwise,
    providing that"* their acknowledgement travels with them (Fedora's licence wiki and Ubuntu's
    package copyright file). They include *script*, i.e. joined, faces.
  - **Relief SingleLine:** a single-line sans released under the SIL Open Font License 1.1 (its
    GitHub repo).
  - Other OFL single-line faces may be added, each recorded with its licence.
- **Motion comes from the sigma-lognormal model** of the kinematic theory of rapid human movement.
  It is a published mathematical model, and published work already uses it to generate *"a large
  database of synthetic samples from a small set of original online handwriting specimens"*
  (IJDAR 2017, doi 10.1007/s10032-017-0287-5). Each skeleton is fitted with lognormal velocity
  pulses, and perturbed per sample, so timing, speed and shape vary the way a hand does.
- **Style is randomised per synthetic "writer":** slant, width, size, letter spacing, which letter
  pairs join (and how the bridges curve), and baseline drift. Each synthetic writer gets a style
  vector, which is what pretraining's writer table needs (§7.1).
- **Labels are perfect and free.** Every point's character is known, which also gives v2 segmentation
  (§5.4) a ground truth to be measured against.

**What this costs, said plainly.** A model pretrained only on fonts, however animated, will write
like a well-behaved font until it is personalised. The user's own hand comes from P1–P3 (§8.1),
trained on the user's own writing on the tablet. That data is commercially clean because it never
leaves the device and belongs to the person it describes. Until the model's measured fidelity
passes assembly's (§8.4), assembly stays primary. The switch is a measurement, so a weak base model
costs quality, never correctness.

**Upgrade paths, all optional, all plugging into the same pipeline:**
1. **Ask the owners.** BRUSH's README invites contact for other uses; IAM-OnDB's terms come from the
   FKI group. The user writes to them; nothing is sent on the user's behalf.
2. **Collect a consented dataset.** A separate *contribute* build of the app records prompted
   sentences from people who agreed to it. The capture code already exists, and prompted text is its
   own label. A data vendor is a third route; nobody was approached and no prices were checked.
3. **Never:** pooling users' profiles from their devices. It contradicts D10.

**Provenance is enforced (D13).** `prepare.py` refuses a dataset without a licence entry, and
`export.py` writes each dataset's name, SHA-256 and licence into the `.ihwm` header. The app's
release build refuses a header listing a non-commercial licence.

⚠️ *Before a commercial release,* the font licences' attribution requirements and any patents on
sigma-lognormal applications get a proper legal read. This file records what the licences say. It
is not a legal opinion.

### 7.3 The training pipeline: `tools/handwriting/`

```
synth.py     font skeletons → sigma-lognormal trajectories, joins and per-writer styles → .npz,
             with a licence entry for every source font
prepare.py   any licensed corpus → the same .npz format, normalised by the same NormalFrame as Kotlin;
             refuses a corpus with no licence entry
model.py     the PyTorch model — the reference implementation
train.py     Adam/RMSProp, gradient clipping, writer-disjoint validation, checkpoints
sample.py    SVG contact sheets: N samples per sentence at several neatness values
export.py    → .ihwm with provenance and its SHA-256, plus the golden vectors for Kotlin's parity tests
test_*.py    stdlib unittest, like tools/m12 and tools/web
```

`synth.py`'s sigma-lognormal code is written in Kotlin as well (§6.1, motor-plausible variation), so
both sides are tested against one shared set of reference trajectories.

**Environment.**
- Python 3.14.5 is on this machine (`py -3`) with numpy 2.5.1.
- PyTorch is not installed. `pip index versions torch` lists **2.14.1** as the newest build for this
  interpreter (checked 2026-10-12). The exact version is pinned in a requirements file when phase 4
  starts (rule 3).

**Compute.**
- There is no NVIDIA GPU: an Intel Iris Xe, an i7-1355U (10 cores, 12 threads) and 16 GB of RAM.
- *Estimate,* replaced by the first epoch's wall time: tens of minutes per epoch for a ~1.7M-parameter
  model, and overnight to a day for a usable one. The synthetic corpus's size is ours to choose, so
  an epoch's length is too. **Q4.**

**Golden vectors.** `export.py` runs fixed inputs through the PyTorch model with teacher forcing. It
writes the window φ, the mixture parameters and the LSTM states at chosen steps. Kotlin tests assert
agreement to 1e-4. This follows `tools/calc/refgen`'s precedent: a reference written independently of
the code it checks.

### 7.4 Inference, in Kotlin

**One LSTM step** is a fused gate matrix–vector product. It is written as a column-wise multiply-add
over the output dimension, so the inner loop has no reduction. That gives ART the chance to vectorise
it; whether it does is what the spike measures.

**Words are generated independently.** Each word's text is the prime text, a space, and the word. The
state after the prime is computed once per drop and copied for each word, so priming costs one pass
whatever the phrase length.

**Batching.** Several words step together, so each weight is read once per step for all of them.
Autoregressive inference on one sequence is memory-bound: about 6.8 MB of float weights are read
every step.

**A runaway guard.** A word gets at most 40 × its characters in steps (*estimate*). An attention
window that wanders off is cut off and counted as a gate failure, never shown.

**Neatness is Graves's bias, b ∈ [0, 2]:** σ → exp(σ̂ − b) and π ∝ exp(π̂(1 + b)). The slider maps
onto b.

### 7.5 Phase 4.0 — the speed spike, first

**Measure on the tablet's release build.** Debug builds are not compiled ahead of time and run
3.5–4× slower (CLAUDE.md). Random weights are enough for timing.
- *What:* microseconds per step.
- *For:* 3×256, 2×384 and 3×400, each at 1, 4 and 8 words per batch.

**Decision rule.** Pure Kotlin stays if both of these hold:
- a 40-character phrase finishes in ≤ 1.5 s with 4 words per batch on the big cores;
- the first word appears in ≤ 300 ms.

Otherwise LiteRT goes behind the same `SynthesisNetwork` interface, in a new `:android` module, with
its version verified at that point and not before. **Report the numbers before building on them**
(*"spike before depending on the unverified"*).

## 8. Phase 5 — personalisation, gate, fidelity, switching

*As built (H5): §20. Where the build departs from this section, §20 says how and why. Acceptance
uses a paired mean over every word no fit saw, with shrinkage (§20.4.1). The positive control is the
user's vouched words (§20.2.1). Routing is per word (§20.5). The worker runs every 15 minutes under
its constraints (§20.7.1).*

### 8.1 The ladder

**P0 — measured style.**
- *What changes:* the slant, size, spacing and baseline-drift distributions, applied around a
  generic sample.
- *Cost:* free.
- *When:* always on.

**P1 — priming.**
- *What changes:* the prime set, i.e. which k of the user's words are used to prime.
- *Cost:* evaluation only; minutes.
- *When:* after each harvest pass.

**P2 — style vector.**
- *What changes:* the style vector s. It is fitted with the base model frozen, back-propagating
  through time.
- *The backward pass* is written by hand for exactly this architecture, and gradient-checked against
  PyTorch's golden values.
- *Cost:* overnight.
- *When:* charging + idle, checkpointed.

**P3 — adapters.**
- *What changes:* rank-r adapters (r ≈ 4–8) on the top LSTM layer's input and recurrent matrices,
  plus the output layer. The lower layers' outputs are cached, so the backward pass covers one layer
  only.
- *Cost:* overnight.
- *When:* after P2. With a synthetic base model this rung is **expected** to be needed (§7.2), so it
  is built in phase 5 rather than left as an option. It still has to show that it raises F before
  its artefact is kept.

**Acceptance.** A new artefact replaces the previous one only if held-out fidelity rises by at least
δ **and** the gate's first-pass rate does not fall. Otherwise it goes to trash.

### 8.2 The reliability gate

**What is checked.** Every word, from either backend, is read by `InkReader` with:
- k = 5 candidates;
- the previous words as pre-context;
- the word's own box as the writing area.

**How a word passes.** Its label must appear in the top k under the *strictest* rule set that
the user's own held-out real words still pass at ≥ 95 %. The rules range from exact, to case-folded,
to punctuation-folded.

⭐ **A token with a digit must be its first reading that holds a digit, not merely one of the k**
(§21.4.1 and §21.4.2, the user's decisions on 2026-10-16).
- ML Kit's other candidates for a number are its digit-for-digit look-alikes: `9.81`'s ink reads
  `[9.81, 0.81, 1.81, 2.81, 981]`. So *"among the k"* passed a wrong digit and a gained decimal point.
- Readings with no digit at all are skipped first. A digit read as a letter is never a wrong number on
  a page, and the user's own lone `1` reads `[l, (, I, i, 1]`.
- The cost is a few right numbers whose first reading with a digit is a look-alike. They are refused
  rather than drawn wrong.
- The rule applies to numbers, formulas and maths runs alike, and calibration measures them the same
  way.

**Two controls** set it:
- *Positive:* the user's real words, read against their own labels, must pass at ≥ 95 %.
  Otherwise the gate is demanding more than the user's own hand delivers.
- *Negative:* real words read against a *wrong* label of similar length must fail at ≥ 99 %.
  Otherwise the gate passes garbage.

Both numbers are stored in `profile.json` and reported in the phase 5 notes.

**Tokens the recogniser cannot judge.** Some token classes — digit strings, symbols, formulas,
codes — have a real-writing pass rate below a floor. For those:
- neural output is not trusted;
- only assembly from individually verified letters is used, laid out without overlap.

"Correct by construction" is the only proof available there, and the notes say so.

**The retry ladder.**
1. Neural, up to 3 samples, raising b one step each time.
2. Assembly, up to 3.
3. Assembly with minimum variation.
4. **Refuse the word.**

**What a refusal looks like.**
- The preview strikes the word through and gives the reason (*"can't write 'ß' yet — write it a few
  times"*).
- That phrase cannot be dropped until it is edited. Dropping it with a gap would silently change the
  text, and dropping garbled ink is the thing the request forbids.

**No recognition model** means no generation at all (D12).

### 8.3 Fidelity

**The distance** d(a, b) between two normalised word trajectories:
1. Resample both by arc length, 32 points per x-height of path.
2. Run DTW over (x, y, pen state), with a penalty when pen states disagree.
3. Add a symmetric chamfer term over the two point sets. It ignores order, so dotting the i after
   the word costs nothing.

So d = DTW / length + λ·chamfer.

**The score** for backend B on a held-out word w, with the user's real sample r:

```
F_B(w) = clamp( (d_generic − d_B) / (d_generic − d_self), 0, 1 )
```

- `d_generic` is the distance from an unprimed sample (P0 only) to r.
- `d_self` is the user's own variability: the median distance between two real samples of the same
  word. It is pooled per letter where the profile has no repeated words.

A profile's F_B is the median over at least 50 held-out words, with at least 3 samples each.

**Why it is relative.** A raw DTW number means nothing to a person or a threshold. This scale runs
from *no better than generic* (0) to *as close as you are to yourself* (1).

### 8.4 Switching

**When neural becomes primary.** Over the last 200 or more gated words, all three must hold:
- F_neural ≥ F_assembly − 0.05;
- F_neural ≥ 0.6;
- the neural first-pass gate rate is ≥ 0.9.

**When it reverts.** When any of the three falls below its threshold by a margin of 0.05. The
hysteresis keeps it from flapping.

**Per character.** Characters the neural backend fails more than 20 % of the time are routed to
assembly whenever assembly has them.

**User override** in settings: *Auto* / *My letters* / *Model*.

Every number here is a starting point. The phase 5 notes report the measured values that set them.

## 9. Safety and privacy

**Where the profile lives.**
- It never leaves the device: it is not in a bundle, not synced, and `allowBackup="false"` already
  keeps it out of Google's backup.
- *To verify in phase 2:* whether device-to-device transfer honours that flag on API 31+, or needs a
  `dataExtractionRules` exclusion for `handwriting/`.

**Signing out** keeps the account's profile, and signing back in reuses it. *Forget account* leaves it
for the user to delete.

**Signatures (D11).**
- A request is refused when its text consists only of the account's display name (any order) or its
  initials, and includes the surname.
- Generated strokes carry `/INKS_Generated` in the published PDF, so a generated mark is always
  distinguishable from a written one in the file.
- The harvester learns only from ink ML Kit reads as words. A signature is usually exactly the ink it
  cannot read.
- **Q6** asks how strict to be.

**Disclosure.** The ML Kit metrics disclosure (SPEC §14) is unchanged; this sends nothing new
anywhere.

**For a commercial release** (the user's stated plan):
- **Consent.** Handwriting is a behavioural biometric. Privacy law may class it as special personal
  information; the GDPR and South Africa's POPIA are the likely ones. Processing it only on the
  device is the strongest position, and this design never does otherwise. A shipped product still
  needs:
  - a first-enable screen saying what is learned, that it stays on the device, and how to delete it;
  - a privacy notice reviewed by someone qualified.
- **Notices.** The Hershey acknowledgement and the OFL text travel with the app, on a licences
  screen, and beside the fonts in `tools/handwriting`.
- **Misuse.** The signature rule (D11) ships at the strict setting, and generated ink is always
  marked in the PDF. These are the two things that make "it looks just like you did it" defensible
  rather than a forgery tool.

## 10. Budgets

| Thing | Budget | Who measures |
|---|---|---|
| First word in the preview | ≤ 300 ms after typing pauses | Phase 3 (assembly), phase 4 (neural) |
| A 40-character drop, start to finish, streamed | ≤ 1.5 s | Phase 4.0 spike |
| UI thread during a drop | No frame over 16 ms; `gfxinfo` before and after | Phase 3 |
| Pen latency while learning is enabled | Unchanged (rule 7); nothing new runs while writing | Phase 2 |
| Model in memory | ≤ 8 MB of floats | Phase 4 |
| Profile in memory | ≤ 10 MB (the index, not the samples) | Phase 2 |
| Battery | Personalisation only when charging and idle | Phase 5 |
| APK | + ≤ 3.5 MB | Phase 4 |

## 11. Verification, and the visual harness (phase 6)

**Every phase ships its own tests.** Phase 6 adds the harness and fills the gaps. It is not where
testing starts.

**Pure logic in `commonTest`.**
- the trajectory codec round-trips byte for byte;
- the normaliser is checked against fixtures, and then against real ink (§5.2);
- layout and assembly invariants;
- the gate's rules;
- the fidelity arithmetic;
- the switching policy;
- inference parity against the golden vectors (§7.3).

**Fakes are suspects.**
- A fake `InkReader` that always agrees is M6's `FakeMergeGate` trap again. Gate *logic* is tested
  against fakes whose KDoc says what they cannot prove.
- Gate *behaviour* is tested with real ML Kit, in an instrumented test on the emulator.

**Variation.**
- One sentence, 20 seeds: every pair of trajectories must differ by at least a floor (not
  identical), and no more than a ceiling (not garbage).
- That runs for both backends.

**The visual harness.**
1. **A JVM test that writes an SVG contact sheet** to `build/reports/`: one sentence, twelve times,
   per backend and per neatness. SVG is a renderer that is not androidx.ink, which answers M4's
   lesson about checking output with the library that wrote it.
2. **A debug screen** that draws the same sheet through the real ink engine.
3. **`sample.py`'s sheets** from the training side.

**On the device.**
- The Test_Tablet emulator holds a copy of the physics note. That is real handwriting, and the
  harvester's real-ink witness.
- It runs a release build, so `run-as` does not work there. The *My handwriting* screen and logcat
  are the readout.
- The tablet is for feel, the speed spike, and overnight personalisation.

**Definition of done #6, per feature:**
- every feature is reached the way a user reaches it: the drag, the text box chip, *Write again*,
  *Edit text*, a correction;
- the worker is seen firing (M7's periodic job never was);
- a generated phrase is confirmed absent from the ledger and the dataset after it is dropped.

## 12. Phases and their acceptance

**H1 — this design.**
- Reviewed, with §14 answered.

**H2 — capture, normalisation, labelling, harvesting.**
- `:core:handwriting` exists and is listed in `coreModules`.
- `checkCoreIsPlatformFree` and the iOS klib compile pass. On Windows, say which half ran (CLAUDE.md
  definition of done #3).
- The `WordBoxes` change and `InkReader` are in, and M7's tests are unchanged.
- **The ledger:**
  - a stroke drawn with `adb shell input stylus` on the emulator is in it;
  - a stamped Element, a duplicate, a merged stroke and an `addAll` stroke are not.
- **Harvesting on the physics-note copy:**
  - the *My handwriting* screen shows coverage;
  - words harvested, the `AGREED` rate and per-letter coverage are written into the notes;
  - the normaliser's before-and-after sheet has been looked at.
- **Corrections:**
  - they survive a restart and a deleted database (SPEC §13.1);
  - a displaced `labels.json` is in `trash/`.
- The worker has fired on the tablet.
- **Older notes:** learning from them is a choice on *My handwriting*, on by default because of Q3.
  On the physics-note copy it harvests, and byte-identical duplicates count once. *(Re-scoped while
  building: this said "offered once at first enable". A first-run question for other users is a §9
  commercial-release item.)*
- **`PageStyle`** is built and tested on synthetic lines. *(Its real-page check moved to H3 while
  building: nothing calls `PageStyle.of` until a phrase is dropped, so phase 2 has no way to reach
  it, and definition of done #6 asks for a feature reached the way a user reaches it.)*
- Codec round-trip, invariant refusal, and the quantisation bound are tested.

**H3 — assembly and the Write panel.**
- **Variation:** one sentence dropped 10 times gives 10 visibly different results (on the harness
  sheet), and no two equal trajectories (in a test).
- **A dropped phrase:**
  - one undo removes it;
  - a tap selects all of it, and it moves as one;
  - it erases partially like ink;
  - it publishes as ink that a non-PdfBox viewer draws;
  - `pdfgrep` finds its words.
- The phrase is in neither the ledger nor the dataset.
- **Conversions and edits:**
  - text box → handwriting works, and one undo restores the box;
  - *Write again* and *Edit text* work.
- **Joins:** a letter pair the user joins comes out joined, and a pair they lift between comes out
  apart. Checked with fixtures, and on the harness sheet from the user's own profile.
- **Matching the note (D15):**
  - `PageStyle` measures x-height, slant, baseline angle, line pitch and margin on real pages. Its
    numbers for the physics-note copy are in the notes, beside a sheet that shows them (moved here
    from H2);
  - dropped beside a paragraph, the phrase takes that paragraph's pen, size, slant, pitch and
    margin;
  - on an empty page it takes the ruling and the profile's median;
  - checked on two notes written in visibly different pens and sizes.
- **The optional quick start** (D14) records prompted sentences as `CONFIRMED` samples, with no
  recognition involved. It is driven through the UI on the emulator.
- **Gate and refusal:** every word passed the gate. A refusing recogniser, injected in a test,
  blocks the drop and names the word.
- **Performance:** `gfxinfo` is measured for a 40-character drop.
- One full airplane-mode pass.
- SPEC §1.2 and §12 are amended, and a §4 subsection documents `hw`.

**H4 — the model.**
- **4.0:** the spike's numbers and the decision are recorded *before* any inference code is written.
- **The synthetic data:**
  - `synth.py` produces a corpus from the licensed fonts alone;
  - each font's licence and attribution is recorded beside it;
  - its sigma-lognormal code agrees with the Kotlin implementation on shared reference
    trajectories.
- **Provenance (D13):**
  - `prepare.py` refuses a corpus with no licence entry;
  - the exported header lists every dataset;
  - a release build refuses a header that names a non-commercial licence, and a test proves it.
- **Training:**
  - `tools/handwriting` trains and exports;
  - its tests pass;
  - training curves and contact sheets are kept, outside git if large.
- **v2 segmentation** (attention alignment) is measured against the synthetic ground truth, and
  checked by eye on the user's real words, before the model is used for generation.
- Kotlin agrees with the golden vectors to 1e-4.
- The neural backend writes gated words in the app, timed on the tablet's release build.
- A corrupt asset turns the backend off and assembly carries on (fault test).

**H5 — personalisation, gate, fidelity, switching.**
- Gate calibration numbers are recorded, both positive and negative controls.
- F for both backends is tracked over the user's real data. That curve is the evidence for
  *"improves with use"*.
- P1, P2 and P3 are each shown to raise F, or recorded as not helping and not shipped.
- **Switching:** the policy is unit-tested, and a switch is observed on the device.
- **The personalisation worker:**
  - runs only when charging and idle;
  - killed mid-run, it resumes from its checkpoint with a stated loss bound (CLAUDE.md, crash safety).

**H6 — tests and the visual harness.**
- The SVG sheet test.
- The debug screen, through the real engine.
- The instrumented ML Kit gate test.
- A per-claim table in the style of `docs/M6-SYNC.md` §7, marking each claim as *written*,
  *automated*, *automated against the real dependency*, or *run on a device*.

## 13. Risks, most likely first

1. **The neural model may stay below assembly for a long time.** That is likely for a new writer
   with any base model (Graves says as much), and more likely with a synthetic one (§7.2). In that
   case:
   - assembly stays primary;
   - the model fills gaps: unseen letters, joins, and letters segmentation cannot cut;
   - P3 carries the personalisation.

   The switch is measured, so this costs quality, not correctness.
2. **The gate is only as good as ML Kit,** which has a CER of 0.27 on IAMonDo. Names, codes and
   formulas may be refused often. Mitigations: calibration, the verified-letter route, and clear
   refusals.
3. **Synthetic pretraining is the least-trodden part of this design.** The sigma-lognormal method is
   published, but a whole base model trained on animated fonts has to show it is worth shipping. If
   it is not:
   - assembly ships alone, and is still the whole day-one experience;
   - the neural backend waits for licensed or consented real data (§7.2's upgrade paths).

   Nothing else in the design changes.
4. **Pure-Kotlin speed** (phase 4.0). The fallback is measured, not assumed.
5. **Cursive.** v1 segmentation and assembly both struggle until the model exists (Q2).
6. **The hand-written backward pass** for P2 and P3 is the riskiest code here. It is gradient-checked,
   and can be dropped without losing P0 or P1.
7. **Tessellating a phrase on the UI thread** at drop time. It is measured in phase 3, and
   `PreparedPage` shows the fix.
8. **Overnight personalisation** costs battery and heat on a tablet left charging. It is bounded by
   chunking and by stopping when the job's constraints lapse.

## 14. Questions for the user

### Answered on 2026-10-12

| # | Question | Answer | What changed |
|---|---|---|---|
| Q1 | Ship weights trained on IAM-OnDB/BRUSH (non-commercial research only)? | *"im thinking of one day making this commercial so maybe theres something else or something you can do"* | D13 added; §7.2 rewritten. No public dataset allows commercial use, so the base model is pretrained on synthetic handwriting from commercially licensed fonts. Licensed or consented real data is an optional upgrade path. |
| Q2 | Print, cursive, or mixed? | *"Mixed, but I would like this to be useable for anyone"* | D14 added: joins in assembly, v2 segmentation moved earlier, an optional quick start for new users, a language per profile. |
| Q3 | Anyone else's handwriting in your notes? | *"No, it's all mine"* | D8: existing notes are learned from too, offered once at first enable. Duplicates count once. |
| Q5 | Ordinary strokes, or an editable text box in your hand? | *Ordinary strokes* | D1 confirmed. |

### Decided by default, at the user's request

The user answered the rest with *"Do whatever you think best"*. They added two things to keep in
mind: dropped text should look *"just like you did it (stays consistent with the note)"*, which
became D15, and the app may one day be sold. Each question below took its recommendation:

| # | Question | Decision |
|---|---|---|
| Q4 | Training compute | This laptop's CPU. The first epoch's wall time is measured before committing to a full run, and the synthetic corpus's size is chosen to fit. |
| Q6 | The signature rule | The strict setting (D11), because the app may be sold. A request that is only the account holder's name, surname included, in any order or with initials, is refused. Generated ink is always marked in the PDF. |
| Q7 | Languages | English first. The alphabet, recogniser and prompts are keyed by the profile's language from the start, so a second language is data, not code. |
| Q8 | A purged note's samples | Withdrawn with it, after the same 30 days. |
| Q9 | Where the Write panel lives | Its own tool chip, hideable like any other. |
| Q10 | Downloads and outreach | I ask before each download (the font files, in phase 4). Letters to BRUSH's and IAM-OnDB's owners are optional; I draft them on request, and the user sends them. |

## 15. Found while reading, not fixed

**Confirmed by reading the code:**
- **`SelectionController.duplicate` drops the format register for strokes and shapes** (§4.3 has
  the lines). A duplicated knockout stroke or shape stops knocking out, and a duplicated Element copy
  made of strokes stops being one thing. Filed as its own task, *"Keep format tags when duplicating
  strokes and shapes"*.

**Reported by the code survey for this design, cited, not re-checked:**
- SPEC §4.9.2's format-record examples (`it1;f=sans`, `ic1;cx=`) do not match the code (`if1`,
  `f=se`, `c=`, `cp=`). See `ObjectFormatCodec`, ObjectFormat.kt:283-297.
- ~~`LineSegmenter`'s `BASELINE_TOLERANCE` KDoc says 1.2, but the value is 0.7.~~ Fixed with §17.
- M7's word-recall figures disagree with each other:
  - 0.378 vs 0.313 in M7-RECOGNITION.md:138-140, SPEC §8.2 and CLAUDE.md;
  - 0.714 vs 0.512 in M7-RECOGNITION.md's first table.
- `RecognitionOutcome.Recognised.skipped` is never consumed, though its KDoc says it is *"carried,
  counted, and logged"* (Recognition.kt:189-192).
- `README.md` still gives the database schema version as 6. Nine migrations means it is 10.

## 16. Phase 2: what was built, and what each claim rests on

Written on 2026-10-12, at the stop for review. Updated on 2026-10-13 after the first harvest of real
notes on the emulator (§16.5). The buckets are the ones CLAUDE.md's definition of done asks for:
- **automated:** a test asserts it;
- **real dependency:** a test ran against the thing itself rather than a fake;
- **device:** reached through the UI on the `Test_Tablet` emulator.

The emulator ran a debug build of HEAD plus this work. It was built in a separate worktree, so that
another session's half-finished toolbar was not in it. The emulator had no Nextcloud account. It was
in airplane mode throughout, except for the few minutes needed to download ML Kit's model, which the
user approved on 2026-10-13.

### 16.1 Per claim

| H2 claim | Automated | Real dependency | Device |
|---|---|---|---|
| `:core:handwriting` exists, is in `coreModules`, and is platform-free | ✅ `checkCoreIsPlatformFree`; the iOS klib compiles, main and test | ❌ The framework **link** belongs to the macOS CI job; it logs SKIPPED on Windows. | — |
| `WordBoxes` keeps each word's stroke ids, and M7's tests are unchanged | ✅ 2 new tests in `WordBoxesTest`; M7's suites unchanged and green | — | ✅ It split 61 real lines into words (§16.5). |
| `PenTrajectory` refuses what androidx.ink refuses | ✅ `PenTrajectoryTest` (6), each rule refused by name | — | — |
| Codec round trip, refusal of damage, and the quantisation bound | ✅ `TrajectoryCodecTest` (5), `WordSampleCodecTest` (4) | — | ✅ The shards written on the emulator were read back by the screen. |
| A stylus stroke is in the ledger | ✅ `PenLedgerTest` (4) | — | ✅ Three strokes gave three lines under the note's id. |
| A ruled stroke is marked as ruled | ✅ | — | ✅ `01a12ef4-dfac-… 45055595 r` |
| Undo and redo, a duplicate, and a stamped Element stay out of the ledger | — | — | ✅ The line count stayed at 3 through each. |
| Merged strokes and `addAll` strokes stay out of the ledger | By construction: both arrive through `applyOps`, never through `FinishedStrokes`. | — | ❌ A merge needs two peers and an HTTPS Nextcloud. |
| Each stroke keeps its own start time, ruler flag and pen kind (§16.2) | ✅ `StrokeStartsTest` (4) | — | ✅ Three quick strokes landed 255 and 263 ms apart, two of them reported in one batch. |
| `InkReader` (`MlKitInkReader`) and `StrokeReader` (`InkStrokeReader`) | ❌ ML Kit and androidx.ink's native code do not load in a JVM test. | — | ✅ Both ran inside every harvest on the emulator. |
| Harvesting: eligibility, agreement, steadiness, letter cuts, staleness, duplicates counted once, the held-out split, the funnel counts | ✅ `HarvesterTest` (12). Its recogniser is a fake, and **more generous than ML Kit**. | — | ✅ The harvest of real notes (§16.5). |
| Normalisation: baseline, x-height, slant | ✅ `LineStyleTest` (4), on synthetic lines | — | ✅ After normalisation, each learned word sits level on its baseline and x-height guides on *My handwriting* (§16.5). |
| `PageStyle` on real pages | ✅ synthetic only | — | **Moved to H3.** Nothing calls `PageStyle.of` until a phrase is dropped. |
| Profile files: atomic, trash first, unknown keys kept, unreadable shards untouched | ✅ `ProfileStoreTest` (9) | — | ✅ *Delete my handwriting data* moved the profile to `trash/<millis>-profile`. A second delete leaves the entries side by side (§16.2). |
| Corrections survive a restart and a deleted database, and the displaced `labels.json` is in `trash/` | ✅ `ProfileStoreTest`. Labels are files beside the profile, outside the database. | — | ✅ Two words marked *Looks right* still read *"You confirmed it"* after a force-stop, and after the database was moved aside and rebuilt. The previous `labels.json` went to `trash/` first. |
| A purged note takes its samples with it, and a lost database does not (§16.2) | ✅ `DeparturesTest` (5), the decision only | — | ✅ With the database moved aside and rebuilt, a pass withdrew nothing (`withdrawn=0`). The exact race, a pass running before the index is rebuilt, was not reproduced. |
| *My handwriting* can be reached and shows coverage | — | — | ✅ Reached from the library's *Handwriting* chip. It said it needed the model until the model was there, and then showed samples and per-letter coverage. |
| The settings persist | ✅ | — | ✅ *New writing* survived a force-stop. Set back to *All my notes* afterwards. |
| The worker is scheduled for charging and idle with no network, and it fires | — | — | ✅ On the emulator: `Required constraints: CHARGING IDLE`. A forced run logged `Worker result SUCCESS`. Every pass now logs one line of counts. ❌ Not yet on the tablet. |
| Older notes are learned from, by choice | Re-scoped in §12 | — | ✅ The choice itself; all 23 notes on the emulator were visited. |
| Words harvested, the `AGREED` rate, per-letter coverage | — | — | ✅ §16.5. |

### 16.2 Found while building, and fixed

1. **A lost database would have withdrawn every word learned.** The learner asked the library index
   which notes still existed, so that it could withdraw samples whose note had been purged (Q8). But
   the index is a cache, rebuilt from `notes/` when it is lost (SPEC §13.1). A pass run against an
   empty or half-rebuilt index would find every note purged. Every sample would go to the trash. Each
   note's watermark would stay, still claiming the note was harvested, so nothing would learn it
   again. The learner now reads `notes/` and the local trash instead. A note it takes for departed
   loses its watermarks along with its samples, so if it was wrongly taken for gone it is simply
   learned again. An empty listing forgets nothing. The fix is in `Departures` and
   `HandwritingLearner.notesOnDevice`.
2. **A stroke that ink reported after the next stroke had begun was given that stroke's start.**
   `InkSurfaceView` kept the live stroke's start time, ruler flag and pen kind in single fields. Its
   KDoc justified that as *"never two strokes at once"*, which is true of strokes the pen is drawing
   and false of strokes ink has yet to report finished. It showed up in the ledger walk as two strokes
   at the same millisecond. The consequences reached beyond the ledger:
   - M12's lecture timeline got the same wrong time;
   - the pen kind could be swapped, so a stroke that finished just after a quick change of pen was
     stored as the next pen's kind.

   `StrokeStarts` now keeps all three per stroke, keyed by ink's own stroke id, in a fixed ring that
   allocates nothing on the down event.
3. **The coverage grid read "a 0b 0c 0".** Each count sat closer to the next letter than to its own.
4. **`ProfileState`'s KDoc said the first-enable question existed.** It does not, and §12 now says
   so.
5. **Drawings were learned as letters.** Both reads of a word read the same strokes, so a shape the
   recogniser takes for a word is taken for it twice. Of the first 18 samples learned from real notes,
   16 were drawings (§16.5). A word is now learned only if the second read is also *steady*: at least
   two of its other guesses are the same word again (`Harvester.MIN_ECHOES`, §5.3). After the change,
   the same notes yielded the 2 real words and none of the 16 drawings.
6. **A second *Delete my handwriting data* nested the first deletion inside it.** The profile was
   moved to the trash with its own `trash/` folder still inside it. `purgeTrash` reads only the top
   level, so anything older would have been kept for thirty days from the later delete, not from its
   own. The screen promises thirty days. The old trash now stays where it is
   (`ProfileStoreTest`, *wiping twice keeps each wipe on its own thirty day clock*), and this was
   checked again on the emulator.
7. **A pass's counts existed only in memory.** Every pass now logs one line, counts only and no
   words. The counts also show the funnel step by step (§16.5), which is what made the recall loss in
   §16.5 visible.

### 16.3 Not done, and why

**The tablet** (definition of done #4) has not been used. Using it would mean:
- installing this build on the user's own device;
- letting the learner run over notes that sync to their Nextcloud. The learner runs M7's recognition
  first, which rewrites and re-uploads any note it reads for the first time. That is what M7's own
  backlog worker does anyway, but it is the user's call.

The tablet is also where the remaining H2 check is made: that the worker fires there.

**Recall is low, and the choice of fix is the user's** (§16.5). Lines whose gaps do not split into
M7's word count are the largest real loss: 48 of 109 lines. On the chemistry page the cause is M7's
lines running across two columns. There are two ways to fix it:
- fix line segmentation in M7, which improves search as well;
- in the harvester, read each gap cluster on a miscounted line by itself. A cluster would be kept when
  its steady read is one of the words in M7's line.

The user chose the first, on 2026-10-13: §17.

**The final runs** used `--rerun` after each test task and `--no-build-cache`, so nothing was up to
date or restored from cache. Every test passed:

| Task | Tests | Run |
|---|---|---|
| `:app:testReleaseUnitTest` | 376 | 2026-10-13, the shared checkout, with the UI session's work in it |
| `:android:ink:testDebugUnitTest` | 40 | 2026-10-13, the shared checkout |
| `:core:handwriting:testDebugUnitTest` | 48 | 2026-10-13, the shared checkout |
| `:core:model:testDebugUnitTest` | 409 | 2026-10-12; `:core:model` has not changed since |
| `:core:search:testDebugUnitTest` | 24 | 2026-10-12; `:core:search` has not changed since |

`checkCoreIsPlatformFree` passed too, as did the iOS klib compiles of `:core:model`, `:core:search`
and `:core:handwriting`, main and test. The release task is the one that skips the merge matrix's live
half. The debug task's live half failed only because its server was down (§16.4).

**`./gradlew build` was red before this work and is still red, for reasons outside it.** Here is what
ran green: `spotlessCheck` and `detekt` on `:core:handwriting`, `:core:search`, `:android:ocr` and
`:app`, `spotlessCheck` on `:core:model`, `checkCoreIsPlatformFree`, and every unit-test task in the
table above. Here is what fails:
- `detekt` on `:core:model`, `:core:storage` and `:android:ink`;
- `spotlessCheck` on `:android:ink`;
- `:app:lintDebug`.

No finding is in code this work wrote (§16.4).

### 16.4 Found, not fixed

- ~~**M7 recognised nothing on the physics note.**~~ **Fixed (§17).** The page, 3,902 objects in a mind
  map with handwritten labels, came back from ML Kit with `lines: []` and a current digest. The cause
  was not ML Kit: `LineSegmenter` saw columns and skipped the whole page as `MULTI_COLUMN`.
- ~~**M7's lines cross columns.**~~ **Fixed (§17).** On the two-column chemistry page, many recognised
  lines joined both columns (*"Examples low to work out what they decompose intelectronegativity is
  given on the periodic"*). That garbled search and was most of §16.5's miscounted lines.
- **ML Kit's native library writes recognised text to logcat**
  (`helper.cc:160] Recognition result: … with score: …`). This is M7's behaviour, not new. It is
  worth knowing about for privacy, because logcat is readable over adb.
- **The merge matrix's live half fails instead of skipping when its server is down.** The gitignored
  `nextcloud-test.local.properties` names a LAN Nextcloud, at 192.168.1.13:8088, that did not answer.
  In `:app:testDebugUnitTest`, 40 of the matrix's 42 tests failed, every one in the live half. The
  release task runs only the in-process half, and was 42/42. `LiveNextcloud.find()` returns a
  configured server without checking that it answers. Filed as its own task.
- **Pre-existing lint debt that keeps `build` red:**
  - detekt: `FractionalKey.betweenOrNull` (ReturnCount), `NoteSession` (TooManyFunctions),
    `Zip.write` (LongMethod), `InkStrokeSampler` (two loops);
  - line length in `Bundle.kt`, `NoteSession.kt` and four test files;
  - formatting in three `:android:ink` test files;
  - Android lint `ContextCastToActivity` at `InkScreen.kt:241`.

### 16.5 The first harvest of real notes (2026-10-13, emulator)

The emulator holds 23 notes:
- **The chemistry note**, a full page of the user's real handwriting in two columns. M7 recognised 56
  lines and 335 words on it.
- **The physics note**, on which M7 recognised nothing (§16.4).
- **About twenty test notes**, of lines, scribbles, shapes and smileys left by earlier walks.

So the run measures precision against drawings well, and recall on one real page.

**The funnel**, in the order a word goes through it. Each count is a subset of the one before
(`HarvestStats`).

| Step | Before the steadiness rule | After |
|---|---|---|
| Lines found | — | 114 |
| … that meet a line M7 read | — | 109 |
| … whose gaps split into M7's word count | — | 61 (48 miscounted) |
| Words on those lines | 68 | 68 |
| … skipped: punctuation only | — | 37 |
| … skipped: partly erased | — | 9 |
| Eligible | 22 | 22 |
| Both reads agree | 18 | 18 |
| … and the second read is steady | (not checked) | **2** |
| Learned | 18 | **2** |
| Letters cut and read back as themselves | 20 | 5 |

**What was learned before the rule.** There were 18 samples: 2 words and 16 drawings.
- The words were `Examples` and `CaCO3`, both from the chemistry note.
- The drawings came from the test notes:
  - zigzags learned as `mm` and `m`;
  - five wavy scribbles, each learned as `M`;
  - an arrow learned as `b`;
  - brackets and corners learned as `c`, `l` and `n`;
  - a tick learned as `d`;
  - ovals learned as `o`.

All 16 had at most one echo among the recogniser's other guesses. Both words had three or more. The
zigzag's other guesses, for instance, were `mmm`, `umm`, `mmu` and `um`.

**What was learned after it.** `Examples` and `CaCO3`, and nothing else. Five letters were cut out of
`CaCO3` and each read back as itself: `C`, `a`, `C`, `O` and `3`. `Examples` gave no letters, because
its strokes do not separate into one cluster per letter.

**Normalisation, looked at.** *My handwriting* draws each learned word after normalisation, between
its baseline and x-height guides. Both words sit level, at the same scale, and are recognisably the
ink on the page.

**Where the real writing goes.** Of the chemistry page's 335 recognised words, few reach the end, for
four reasons:
1. Lines miscounted: 48 of 109. Their gaps do not split into as many words as M7 read, mostly because
   M7's lines run across both columns (§16.4).
2. Punctuation only: 37 words, the lone dashes, dots and brackets of a chemistry page.
3. Partly erased: 9 words. These are ruled out by design, because the sampler reads such a stroke
   whole.
4. Not steady: words that are real but whose second read wavered. The data cannot separate this case
   from drawings, so the rule errs towards learning less.

The two ways to recover the miscounted lines are in §16.3.

**On the emulator itself.** Driving two AVDs at once met four traps: a wrong serial, a broken Wi-Fi,
a stuck launch, and a ping that can't be trusted. They are in the README's *Testing on an emulator*.
The first of them cost a mistake: 91 seconds of the other session's AVD out of airplane mode. Its
outbox was empty, so nothing synced.

## 17. The miscounted lines, fixed at the line segmenter (2026-10-13)

§16.3 offered two fixes for recall, and the user chose the one that improves search as well:
*"fix the line splitting so search improves too."* `docs/LINE-SEGMENTATION-V2.md` is the whole
account. In short, `LineSegmenter` version 2:
- reads columns and mind maps instead of skipping them;
- never joins strokes across a gutter;
- attaches marks to one line instead of letting them link two;
- measures big writing against itself;
- joins a line that a descender split at a word boundary.

It also fixed a search hit that opened its note somewhere else.

**The funnel, the same emulator notes as §16.5**, with `HandwritingWorker` forced. It was measured
before the last of those steps, which the emulator's stored text predates:

| Step | §16.5 | Version 2 |
|---|---|---|
| Lines that meet a line M7 read | 109 | 562 |
| … whose gaps split into M7's word count | 61 (48 miscounted) | 255 (307 miscounted) |
| Words on those lines | 68 | 352 |
| Eligible | 22 | 224 |
| Both reads agree | 18 | 121 |
| … and the second read is steady | 2 | 19 |
| Learned | 2 | 19 |
| Letters cut and read back as themselves | 5 | 35 |

Most of the gain is the physics note, read at all for the first time. Its equations are most of the
miscounted lines: a fraction does not split into as many gap clusters as the recogniser reads words,
and that is the honest answer for a fraction.

**Every page is read once more.** `LineSegmenter.VERSION` is part of the recogniser id, so text drawn
from version 1's lines counts as stale and is read again in the charging-and-idle batch. The harvest
follows it.

## 18. Phase 3: what was built, and what each claim rests on (2026-10-14)

Written at the stop for review. The buckets are §16's:
- **automated:** a test asserts it;
- **real dependency:** checked against the thing itself rather than a fake;
- **device:** reached through the UI on the `Test_Tablet` emulator.

**The emulator** ran a debug build of the shared checkout, which also holds another session's
uncommitted toolbar and line-segmentation work. It had no Nextcloud account and stayed in airplane
mode for the whole walk. ML Kit's model was already on it, downloaded on 2026-10-13.

**Its profile is small, so fixtures stand in where it cannot answer.** It holds the 19 words learned
from the emulator's notes in §17: 128 units, and only one letter pair the hand joins (`an`, joined 3
times out of 3). So:
- joins and bridges are proved on fixtures (`HandFixtures`, a hand built from synthetic `n u m l i
  x`), and only looked at on the real profile;
- every device drop used words the profile can write, mostly *"it is not a cation"*;
- the sheets use the profile as it is, refusals included.

**The tablet came last, with the user's go-ahead (§18.7).** It holds 136 learned words. A release
build was installed over the phase 2 one and walked on a scratch note at the library root, which is
never synced. Of the user's own notes, one was opened and measured and none was written to. The tablet
found three faults the emulator's 19 words could not show, all fixed (§18.7).

### 18.1 What was built

- **`:core:model`:** `GeneratedInk` (the `hw` key) and `PhraseRecord` (the `hw1` record). Both are in
  SPEC §4.10.
- **`:core:handwriting`:**
  - `GlyphIndex`: every learned word, joined run and letter as a unit, plus `HandStats` (letter gaps by
    class pair, word gaps, pen-up time, and joins by pair, then by class pair).
  - `GlyphAssembly`: covers a word with the fewest units. It chooses variants by weight: verified
    first, then the same page, then the same note, then recent. It prefers a variant not yet used in
    the phrase. It applies bounded variation and joins where the hand joins.
  - `Variation`, with §6.1's table as its numbers and neatness scaling it.
  - `ReliabilityGate`: one recogniser call per round, five candidates, case-alike letters forgiven.
  - `HandwritingSynthesizer`: three varied rounds, one unvaried round, then a refusal with a reason.
  - `SignatureRule` (D11, strict).
  - `PageWriting` and `Placements` (D15).
  - `QuickStart` (D14).
  - `PhraseOps`, the one place a phrase's ops are built.
  - `Harvester.recut` and `GlyphClusters.partition`, for joined writing (§18.2, 1).
- **`:android:ink`:**
  - `TrajectoryInk` encodes a phrase's strokes, off the main thread.
  - `DryObject.copyKey` gained the `hw` branch.
  - `InkSelection.phrase` says when the selection is exactly one phrase.
- **`:android:pdf`:** `/INKS_Generated` on generated annotations.
- **`:app`:**
  - the `WRITE` tool and its panel (`WritePanel`, `WriteToolState`, `HandwritingWriter`);
  - `stampHandwriting`, which goes through `NoteEditor.editShownPage(readAgain = true)` so there is no
    second write path;
  - the selection bar's *Write again*, *Edit text* and *Handwrite* chips;
  - the quick start, on *My handwriting*;
  - `ink.handwriting.neatness`.

### 18.2 Decisions made while building

1. **Joined writing is learned as runs.** A word with fewer gap clusters than letters is shared out
   by width (`GlyphClusters.partition`, a small dynamic program over expected letter widths). Each
   share is read back and kept only if it reads as its letters. Words learned in phase 2 are re-cut
   once (`ProfileState.recutTried`). On the emulator: `recut=3`, which gave the runs `an`, `an` and
   `-120`.
2. **The user's own run beats a bridge.** A bridge is drawn only where no learned run covers a pair
   the hand joins. A bridge is a guess (§6.1); a run is the user's ink.
3. **A word corrects its own size** (`GlyphIndex.selfScaled`). A sample's frame is its line's, and a
   line of capitals, subscripts and an arrow measured `CaCO3`'s x-height at half its real size, so it
   was drawn twice as big as everything else. The word's own cut `a`, `c`, `e`, `n`… now say what its
   x-height is.
4. **Word spacing comes from the page first** (D15, `PhrasePlacement.wordGapXHeights`). After that
   it comes from the hand, then from a default of 1.15 / 1.35 / 1.55 x-heights. The emulator profile
   has **no** measured word gaps: its samples are single words.
5. **A letter gap is never tighter than −0.15 x-height.** Some measured gaps were −0.48, which made
   letters collide on the sheet.
6. **Only writing counts as a line for D15** (`PageWriting.writingLines`). A line counts if it has at
   least two strokes, is at least 1.5 x-heights wide, and stands at least 0.45 of the page's
   stroke-weighted median x-height. On the physics note, **173 of 417** segmented lines are marks:
   subscripts, arrowhead labels and dots. Counting them, a drop beside a paragraph came out at an
   x-height of **1.85 pt** against the paragraph's 4.5.
7. **`PageStyle`'s line pitch is measured within paragraphs.** Between any two neighbouring lines it
   said 2.6 pt on the mind map. It now says 16.4 pt.
8. **One page measurement at a time.** The panel measures ahead while the user types, and a drop that
   arrived mid-way started a second measurement. On the physics note that took **34 s**, against
   **6.9 s** for one. A second caller now waits and reads the first one's answer.
9. **Handwriting is never written with the highlighter.** On a page with nothing to match, the pen
   in hand is the pen, unless it is the highlighter, and then it is the fountain pen as last set.
10. **The quick start splits words at the N−1 widest gaps.** M7's word boxes refused a printed hand
   whose letters stand apart (*"The gaps did not make 9 words"*). The prompt says how many words
   there are, so the widest gaps are the spaces. A gap has to be at least 1.3 times the widest gap
   between letters.
11. **The preview waits 150 ms after typing stops.**

### 18.3 Per claim

| H3 claim | Automated | Real dependency | Device |
|---|---|---|---|
| One sentence ten times gives ten different results | ✅ `HandwritingSynthesizerTest` and `GlyphAssemblyTest`: drops are pairwise different | ✅ The sheet: one sentence twelve times at each of three neatnesses, from the emulator profile, looked at | ✅ *Write again* redrew the phrase in different strokes on screen |
| One undo removes a phrase | ✅ `NoteEditorHandwritingTest`, through the real `NoteEditor` and `NoteSession` | — | ✅ Chemistry note on 2026-10-13. Re-checked on 2026-10-14 after the last refactor: *Write again*, then one undo brought back the first version, then a second undo removed it. |
| A tap selects all of it, and it moves as one | ✅ `PhraseOpsTest`: found by any stroke, matched by copy key | — | ✅ A stylus tap on one stroke selected the whole phrase. A drag moved it as one. ⚠️ The first drag on a small phrase grabbed a handle and resized it (§18.6). |
| It erases partially like ink | By construction: they are ordinary `StrokeObject`s | — | ✅ The eraser cut through one letter; undo put it back |
| It publishes as ink a non-PdfBox viewer draws | — | ✅ MuPDF rendered the published pages; poppler read their text | ✅ A test note's published PDF had 24 `/Ink` annotations, 20 of them `/INKS_Generated true`. The chemistry page is flattened (§6.4's density rule), so it has no annotations; MuPDF drew the phrase in its content. |
| `pdfgrep` finds its words | — | ✅ poppler's `pdftotext` stood in (`pdfgrep` is not installed here; it uses the same text extraction). It found *"it is not a cation"* on the test note. On the chemistry page it found *"CaOis a salt"*, and MuPDF found `salt` at the phrase's position. | ✅ The text arrived via M7 recognition: the page is marked unread when a phrase is stamped. On 2026-10-14, recognition read the phrase 3 s after *Write again*. |
| Never in the ledger or the dataset | ✅ `NoteEditorHandwritingTest` asserts `SkipReason.GENERATED` for each stroke; `PlacementTest` shows generated ink is not measured | — | ✅ The ledger stayed at one line after a phrase was written. The next *Learn now* skipped `GENERATED=8` and added nothing. |
| Text box → handwriting, and one undo brings the box back | ✅ `NoteEditorHandwritingTest` | — | ✅ On ruled paper (x-height 9.6, pitch 24); undo restored the box. Found the highlighter bug (§18.2, 9). |
| *Write again* and *Edit text* | ✅ `NoteEditorHandwritingTest`: found by any stroke, all strokes replaced in one entry | — | ✅ *Write again* replaced 14 strokes. *Edit text* to a word with a letter never written was refused (*"can't write 'b' yet"*). Corrected, it replaced 14 strokes with 12. Undo and redo both worked. |
| A joined pair comes out joined, a lifted pair apart | ✅ `GlyphAssemblyTest`, on a joining fixture hand: a joined pair bridges into one stroke; a lifted pair stays apart; a pair's own count beats its class | ✅ The joins sheet from the emulator profile: `an` came out as one stroke from the user's own `an` run, and 30 lifted pairs came out apart | ✅ **Tablet:** the first drop from the 136-word profile drew a bridge (`joins=1`). Whether it looked right was not judged on its own. |
| `PageStyle` on real pages | ✅ synthetic lines | ✅ The physics note, through the sheet: 244 writing lines; x-height 4.61 pt (p10 2.64, p90 7.77); pitch 16.4 pt; slant 8.3° (p10 0.8, p90 13.1); baseline angle −0.1°. The sheet `page-style-00` was looked at. | ✅ The app measured the same page: 242 lines, x-height 4.58, pitch 16.3, word gap 1.17 x-heights over 513 gaps |
| Beside a paragraph it takes the paragraph's pen, size, slant, pitch and margin | ✅ `PlacementTest` (10), `SheetFixesTest` | — | ✅ Chemistry: red fountain pen `#b91c1c`, x-height 3.54, slant 0.061, pitch 11.8 (`PARAGRAPH`). Physics: black, x-height 4.50, pitch 26.0 (`PARAGRAPH`, by tap), and 4.58 away from paragraphs (`PAGE`). |
| On an empty page, the ruling, then the profile | ✅ `PlacementTest` (11) | — | ✅ **Tablet:** on ruled paper, lines on the rules at the hand's own x-height (5.57 pt, pitch 24; §18.7, 3). On grid paper, the profile (`PROFILE`, 5.57 pt). |
| Checked on two notes in different pens and sizes | — | — | ✅ Chemistry (red, 3.5 pt) and physics (black, 4.6 pt) |
| Quick start records `CONFIRMED` samples, no recognition | ✅ `QuickStartTest` (5) | — | ✅ One prompt, written over adb with `input stylus`. *"Recorded 9 words."* Every row said *You confirmed it*, and there were 0 ML Kit recognition lines during the save. The 9 scripted samples were then removed, which left 19 words again. |
| Every word passed the gate; a refusing recogniser blocks the drop and names the word | ✅ `HandwritingSynthesizerTest`: refusing, missing letters, signature, recogniser unavailable, unvaried last rung | ✅ ML Kit on the emulator read back every word written: in each logged write `attempts` equals `words`, so each word passed first time | ✅ *Missing letters* refused on screen. ❌ An *illegible* refusal from the real recogniser has not been seen, because nothing failed. |
| `gfxinfo` for a 40-character drop | — | — | ✅ Emulator debug build, 43 characters (11 words, 42 strokes, two lines; written in 485 ms). 58.9 % janky frames, p99 200 ms, worst frame 218 ms. The baseline, the same drag released over the panel, was 58.8 % janky, p99 150 ms. ✅ **Tablet release build**, 41 characters: written in 264–281 ms; 31–33 % janky frames over 15–16 frames, p99 97–105 ms. |
| One full airplane-mode pass | — | — | ✅ The whole walk |
| SPEC amended, and `hw` documented | ✅ §1.2, §12, §4.10, and the key in §5.6 | — | — |
| Platform-free, and the iOS target | ✅ `checkCoreIsPlatformFree`; the iOS klib compiles of `:core:model` and `:core:handwriting`, main and test | ❌ The framework link is the macOS CI job's; it is skipped on Windows | — |

### 18.4 Found while building, and fixed

**By the sheets** (`HandwritingSheetReport`, from the emulator profile):
- `CaCO3` was drawn at twice the size of the other words (§18.2, 3).
- Words sat too close together (§18.2, 4).
- Some letters collided (§18.2, 5).
- `PageStyle`'s pitch was 2.6 pt (§18.2, 7).

**On the device:**
- A drop beside a paragraph came out at x-height 1.85 pt (§18.2, 6).
- A page measurement took 34 s (§18.2, 8).
- Text box → handwriting wrote in translucent yellow (§18.2, 9).
- The quick start refused a printed hand (§18.2, 10).

**By the build:**
- Kotlin/Native rejected thirteen test names with commas in them (CLAUDE.md already says so).
- The user's own name was the example in `SignatureRule`'s tests and KDoc. It is *Ada Lovelace* now.

**By reading:**
- The preview's *"Reading it back…"* stayed on screen when the text was cleared while a preview was
  being written. The flag is now reset in a `finally`.

### 18.5 Not done, and why

- **An illegible refusal from the real recogniser** has not been seen. Every word anything wrote was
  read back first time, on the emulator and on the tablet.
- **The learner's re-cut of the tablet's profile** (§18.2, 1) runs at its next pass, which also reads
  any page whose recognised text is stale. That pass rewrites and re-uploads notes, so it was left to
  run as it normally does, charging and idle, rather than forced.
- **Dropping beside the user's real writing on the tablet** would write to a synced note, so it was
  not done. The measurement it uses was done, on a real page, read-only (§18.7).
- **Phase 4** has not been started.

### 18.6 Found, not fixed

- **The chemistry page's published PDF draws a large open circle that the app does not show.** The
  page holds 713 erase ops. It looks like an erased stroke published whole, which is flattened-page
  territory (§6.4) and older than this work. It is filed as its own task.
- **On a small phrase, the selection handles cover most of it.** So the first drag grabbed a handle
  and resized the phrase instead of moving it. Grabbing between the handles moves it. This is the
  selection chrome's behaviour for any small selection, not something phrases do.
- **Duplicating a selection drops its format register** (§4.3, §15), so a duplicated phrase becomes
  plain, ungrouped ink. It is still unfixed and still its own task.

### 18.7 On the tablet (2026-10-14)

**What was installed and touched.**
- A debug-key-signed release build was installed over the phase 2 build with `adb install -r`, so the
  data was kept.
- For a few minutes, a debug build was installed instead, only to copy the profile off for the sheet
  report. The release build went back on afterwards.
- The walk used a new note at the library root. A root note is bound to nothing, so nothing synced. The
  note was moved to the local trash at the end.
- One real note, *Definitions*, was opened, and the Write panel measured its page. Nothing was placed
  and the note did not change.
- The learner was not forced (§18.5).

**The numbers on the release build.**

| What | Emulator, debug | Tablet, release |
|---|---|---|
| Preview: first word read back after the pause (§10's budget is 300 ms) | 842–1,042 ms | **230–271 ms** |
| Writing a phrase: assemble, read back, encode, stamp | 293–1,271 ms | 264–281 ms |
| Measuring a page for D15 | 4.2 s (chemistry), 6.9 s (physics) | **356 ms** (*Definitions*: 29 lines) |
| Frames during a drop | 58.9 % janky, p99 200 ms | 31–33 % janky, p99 97–105 ms |

Measured on *Definitions*: x-height 5.46 pt, pitch 22.3 pt, word gap 1.28 x-heights over 134 gaps. The
profile's own x-height is 5.57 pt, so the page and the hand agree.

**What the real profile showed, and the fixes.** The first phrase written from the 136 words was
legible, read back first time, and visibly wrong in three ways:

1. **Letters at the wrong height.** A `p` floated a whole descender above the line, and a `t` dropped
   its stem one and a half x-heights into the next line. Both came from one learned word, `tap`, whose
   frame (§5.2: the *line's* baseline and angle) did not fit that word. Its letters had been cut and
   stored at those heights.
   - ML Kit passed them, because it reads a word, not a baseline. That is why the gate could not
     catch it.
   - **Fix:** `LetterFit`. Every lower-case cut letter is checked against where its kind belongs:
     - on the line within ±0.45 x-height;
     - x-height letters topping out at 0.55–1.6;
     - tall letters above 1.05;
     - descenders below −0.25.

     A word with any letter that does not fit gives no units. Measured on the profile, **11 of 85**
     judged words were set aside, including `tap`, `p.`, `be` and `Bidvest`.
   - Capitals and digits are not judged. In this user's chemistry (`NO2`, `CaCO3`, `2-`) they are
     written at x-height, and a first version that expected them tall set aside 49 words.
   - **The cost:** the profile's only `b` came from `be`, so `b` is now refused (*"can't write 'b'
     yet"*, seen on the tablet). A refused word is the design (§8.2). A misplaced letter is not.
2. **Ink the label did not name.** Every `the` began with a dot. The learned word `th` carried a dot in
   front of its `t`, and as a two-letter unit it was preferred for every `the`. One `a` was the user's
   `a)`, bracket included.
   - **Fix:** `GlyphIndex.carriesStrayInk`. A learned word is not used whole if it has more stroke
     clusters than letters, or a dot-sized mark standing clear of either end. Its cut letters are
     still used.
   - On the profile, 21 words are no longer used whole. Most of them are labels with punctuation or
     capitals the clusterer splits (`N.B`, `P(A)`, `x8_`).
3. **Twice the user's size on ruled paper.** An empty ruled page took its x-height from the ruling,
   0.4 of the spacing: **9.6 pt** on 24 pt rules. This user writes at 3.5–5.6 pt and ignores the rules,
   so the phrase came out double size and its second line ran into its first.
   - **Fix:** the ruling decides where lines go, and the **hand** decides the size. That size is the
     profile's x-height, capped at 0.3 of the spacing, which fits a hand's tall letters (about 1.6
     x-heights) and descenders (about 1.15) between two rules.
   - The same drop then came out on one line at 5.57 pt (`Placements.resolve`).
   - D15's order in §6.2 changes accordingly.

After the fixes the profile has 385 units, down from 500. The same sentence came out on the line on
the sheet and on the tablet. `FrameCheckTest` (4) and `PlacementTest` hold the three rules, and the
sheet report now prints where each kind of letter sits, which words are set aside, and why.

**How it was found.** The sheet report over the copied profile, with two additions that stay in the
report:
- each unit's source word on the units sheet, which named `tap`, `th` and `a`;
- the sentence's own words and runs on the units sheet, which found `th`.

Before the tablet there was no way to see any of it. The emulator's 19 words held none of these
words, and the 113 tests used a fixture hand in which every word is level.

### 18.8 The runs behind the table

Every task ran with `--rerun` and `--no-build-cache`, and none was up to date or restored from cache.
These are the counts read from the XML results:

| Task | Tests | Run |
|---|---|---|
| `:core:handwriting:testDebugUnitTest` | 118 | 2026-10-14, after the tablet's fixes (113 before them) |
| `:core:model:testDebugUnitTest` | 416 | 2026-10-14 |
| `:app:testReleaseUnitTest` | 413 | 2026-10-14, the shared checkout, with the other session's work in it |
| `:app:testDebugUnitTest`, the classes this phase touched | 48 | 2026-10-14: `NoteEditorHandwritingTest` 3, `ChromeLayoutTest` 29, `EditorPrefsCodecTest` 14, `HandwritingSheetReport` 2 (the last skips without a pulled profile) |
| `:android:ink:testDebugUnitTest` | 46 | 2026-10-14 |
| `:android:pdf:testDebugUnitTest` | 90 | 2026-10-14 |
| `:core:storage:testDebugUnitTest` | 220 | 2026-10-14 |
| `:core:search:testDebugUnitTest` | 33 | 2026-10-14 |

The release task is the one that skips the merge matrix's live half (§16.4). Instrumented tests were
not run: this phase added none.

`detekt` and `spotlessCheck` are green on `:core:handwriting`, `:core:model` (spotless) and `:app`.
`checkCoreIsPlatformFree` and the iOS klib compiles are green too. `./gradlew build` is still red, for
§16.4's pre-existing reasons, and nothing this phase wrote is among the findings.

### 18.9 The quick start gets undo, redo and an eraser (2026-10-14, after H4)

**The report.** *"There's no way to undo, so if you make a mistake you have to clear it."* Copying a
prompt meant writing a whole sentence. The writing area offered only *Clear*, so one slip cost the
whole sentence. It also took a finger as ink, so a palm could mark the sample.

**What changed.**
- **Every edit is a set of strokes shown or hidden** (`PromptInk`). Writing shows a new stroke,
  erasing hides the strokes the eraser touched, and *Clear* hides everything. Undo and redo show or
  hide the same strokes again, so *Clear* is undoable too.
  - Erasing is **whole-stroke**, unlike a note's area eraser (SPEC §3.5). What is written here is a
    *sample* of the hand, and half a stroke is a stroke the hand never made.
  - No sample's time is touched. A hidden stroke's time is left as a longer pen-up, which
    `GlyphIndex.penUpOf`'s median and its cap already absorb.
- **The pen takes the page's rules** (`PenInput`, SPEC §3.3):
  - the pen writes, and fingers never draw;
  - two fingers tapped undo, and three redo;
  - a flipped pen or a held barrel button erases, and so does the tip with *Erase* on.
- **No second implementation of those rules.** `MultiFingerTapDetector` and the new
  `isHardwareEraser` are now public in `:android:ink`. `InkSurfaceView` uses the helper, and its
  behaviour is unchanged.
- **Chips:** *Erase*, *Undo*, *Redo*, *Clear*, *Next* and *Skip*, with a line naming the gestures.
- **Failure messages:** a prompt that fails to split into its words now says to erase or undo the
  part that ran over, rather than to clear the whole thing.

| Claim | Automated | Device |
|---|---|---|
| Undo, redo, whole-stroke erase, one undo per erase gesture, *Clear* undoable, the saved trajectory | ✅ `PromptInkTest` (9) | ✅ The emulator walk (below) |
| The pen writes; a finger does not | ✅ `QuickStartPenInputTest` | ✅ The emulator: a finger swipe left the area empty, and a stylus swipe drew |
| Two fingers undo, three redo | ✅ `QuickStartPenInputTest`. It failed when fingers were cut off from the detector, and passed again once restored. | ❌ `adb` cannot tap with two fingers, so the user's tablet is the check |
| Barrel button and flipped pen erase | ✅ `QuickStartPenInputTest` | ❌ `adb` cannot hold a barrel button. Same as above. |
| A palm landing while the pen writes is neither ink nor a tap | ✅ `QuickStartPenInputTest` | — |
| *Erase*, *Undo*, *Redo* and *Clear* through the UI | — | ✅ The emulator: two strokes; *Undo* left one, and *Redo* brought it back; *Erase* across the first removed only that one, and *Undo* restored it; *Clear*, then *Undo*, brought both back. Screenshots at every step. |

`QuickStartPenInputTest` ran through `am instrument` on `Test_Tablet` (7/7), after `adb install -r`
of the app and the test APK. It deliberately did not use `connectedDebugAndroidTest`, which
uninstalls the app and would have wiped the emulator's copy of the physics note. Nothing was saved
from the walk, so the emulator's profile is unchanged.

`:app` and `:android:ink` unit tests passed (9 and 46), as did `:app` detekt and spotless. The
debug-key-signed release build went onto the user's tablet. Before installing it, I checked that the
only production files changed since the previous tablet build were this change's, and that the
APK's dex holds the new screen. `:android:ink`'s detekt and spotless fail, but only on files this
change did not touch (`PreparedPage*Test`, `InkStrokeSampler`, `PenStrokeOrderTest`), as in §16.4.

### 18.10 Word spacing, oversized characters, and numbers (2026-10-14, after H4)

**The report, from the tablet.** *"The spacing between words is a bit big … sometimes a letter, or
most of the time a number or symbol, will show up very big … I need this to be really good at math
and numbers, but it's not the best, especially random numbers and decimals."*

**How it was found.** The tablet's profile was copied off with the debug build: 196 samples, 39 of
them from the quick start. `HandwritingSheetReport` gained four things:
- word gaps by source;
- each capital, digit and symbol's spread, with the words its extremes came from;
- the geometry of the punctuation units;
- a maths sheet that runs **the whole synthesizer**, assembly and the shipped model, with each word
  labelled by the backend that wrote it.

The sheet now draws at the app's pen proportion. At its old 0.9 a decimal point drew as nothing, so
the sheet itself could not show the dot problem.

**What was wrong, and the fix for each.**

1. **Word gaps came from the quick start.** It asks for *"a clear gap between words"*, and its
   prompts gave 34 of the profile's 35 measured gaps: median about 1.7 x-heights, up to 3.4. The
   user's own pages measure 1.17–1.28. Layout also picked each gap at random from the raw list, so
   one gap in five was 2.3 x-heights or more.
   - **Fix:** `GlyphIndex.wordGapsOf` skips quick-start samples.
   - **Fix:** `layOut` uses the median ±15 %, as the page path (D15) already did. A gap measured
     between two *learned* words can span a word that was not learned, and a median is not moved by
     a few of those.
   - **Result:** the profile now has one gap from notes, so the default (1.35) applies until the
     notes give three.
2. **Capitals, digits and symbols were never judged** (§18.7 exempted them). So a `7` stored at
   **8.57** x-heights, from `=27`, was drawn at that size the moment assembly picked it. So was an `A`
   at 10.5, an `=` at 5.66, a `3` reaching −7.03, and `p.`'s dot below the line.
   - **Fix:** `LetterFit` now judges every character. Capitals and digits must sit on the line and be
     anywhere from x-height to 2.8 x-heights tall. That keeps `NO2` at x-height, and the tallest
     honest copy, a `C` at 2.74.
   - Points must sit at the line; `-`, `=` and `+` must cross the x-height band; anything else must
     not be absurd; `i` gets room for its dot.
   - **Result:** 38 of 153 judged words are now set aside, against 23 before. Every copy 3 or more
     x-heights out is gone, and no honest one is.
3. **A subscript digit stood in for a normal one.** The `3` of `CaCO3` was drawn in `y = … + 3` at
   two-thirds the height of its neighbours.
   - **Fix:** a digit whose top is under 0.8 x-heights (this hand's digits stand 1.1–1.25) is kept out
     of the *single-letter* units only.
   - Its word and runs stay, because inside a formula it is right, and nothing else from the word is
     lost.
4. **Decimal points vanished into the digit before them.** A letter gap may be negative (a `t`'s bar
   reaches over its neighbour). A digit-to-point pair has no measured gaps of its own, so it fell back
   to the hand's general gaps, at −0.15 x-heights. The profile's only `.` is 0.06 wide, so it landed
   inside the `2`'s foot.
   - The result was `2.5` drawn as `25`, `9.81` as `981`, `0.0375` as `00375`: a different number.
   - **Fix:** `GlyphAssembly.clearance` gives at least 0.2 x-heights either side of a `.` or `,`, and
     stops two digits overlapping.
5. **The model wrote out the rest of the prime before the word.** `=` came out as *"mass ="*, *"ove
   ="*, *"ned ="*, and `1/2` as *"lass 1/2"*.
   - Each word is written as *prime text, space, word*, and on the user's real ink the attention
     window lags the prime it reads. Generation then started wherever the window had stopped.
   - The gate refused these words, so none reached a page. But they spent the ladder's attempts, and
     this is the likely reason priming *lowered* the pass rate in §19.7. The rate has not been
     re-measured.
   - **Fix:** `KotlinSynthesisNetwork.catchUp` moves the window's α-weighted centre up to the end of
     the prime, all components together, never back. The LSTM state, which carries the style, is
     untouched. `model.py` does the same.
   - **Result:** on the sheet, every word that had begun with prime text now begins with its own
     first character.
6. **The profile has almost no maths.** From notes it held one `=` (misframed), one `+`, one `.` and
   no `/` or `%`, so every `=` fell to the model. The model's `=` carries stray marks, and its `%`
   reads as `°oo`.
   - **Fix:** the quick start gains two maths prompts, `x = 0.25 + 1.75 - 3/8 = 1.625` and
     `(4.9 - 2.6) / 0.5 = 4.6 or 46%`. Each symbol is written as a word of its own, so it is cut as a
     unit.
   - The quick start now opens at the first prompt not yet recorded (`QuickStart.firstToDo`). The
     tablet's profile has the first five, so it opens at 6 of 7.

| Claim | Automated | Real dependency | Device |
|---|---|---|---|
| Quick-start gaps are not the hand's spacing | ✅ `NumbersTest` | ✅ The tablet's profile: 35 gaps → 1 | — |
| Absurd capitals, digits and symbols are set aside, honest ones kept | ✅ `NumbersTest`, with the profile's own numbers | ✅ The report: 38 of 153 set aside, every copy over 3 x-heights gone | — |
| A subscript digit is not a single letter, and its word stays | ✅ `NumbersTest` | ✅ The maths sheet: `+ 3` at full height | — |
| A decimal point keeps clear of its digits | ✅ `NumbersTest`, on a hand with −0.15 gaps (the bug's own conditions) | ✅ The maths sheet: `2.5`, `9.81`, `0.0375` and `12.875` all show their point | ❌ Not looked at on the tablet yet |
| After a prime, the model starts on the word | ✅ `NumbersTest`: `catchUp`'s arithmetic; Python: a primed sample runs | ✅ The maths sheet through the shipped model: no prime text in any row | ❌ Pass rate not re-measured |
| Maths prompts, and the quick start opens where the user left off | ✅ `NumbersTest` | ✅ The report: the tablet's profile opens at 6 | ✅ Emulator: 1 of 7 (none done); skipping reaches 6 and 7 |

**Runs.**
- `:core:handwriting:testDebugUnitTest`: 157 of 157.
- `:app:testReleaseUnitTest`: 427 of 427.
- Python: 28 of 28.
- detekt, spotless, `checkCoreIsPlatformFree` and the iOS test compile are green on
  `:core:handwriting` and `:app`.
- The release build is on the tablet and launches.

**Not done.**
- **Retraining the model for numbers.** Only 8 % of its training words were numbers, and only 1.6 %
  decimals, all of the form `NN.NN`: no leading zeros, negatives or long decimals. Its alphabet has
  no `^ * < > [ ] × ÷ π √ ± ≤ ≥`.
- A corpus weighted toward maths, with those symbols from Hershey's maths sets, is the model-side
  answer. It needs new font downloads, which need asking first, and a run on the user's PC.
- With the maths prompts, the user's own hand covers the common symbols, so the model is the
  fallback and not the main path.

## 19. Phase 4: the model (H4, started 2026-10-14)

### 19.0 The speed spike (4.0), measured before any inference code

**What ran.** `SpeedSpike` (`:core:handwriting`, `neural/`) is one whole step of §7.1's network in pure
Kotlin on random weights:
- the LSTMs, with each layer fed the pen offset, the layer below and the window;
- the 10-Gaussian window over a 24-character text with an 80-letter alphabet;
- the 20-component mixture output read from every layer;
- a sample drawn from it.

The style vector is a per-word bias and costs nothing per step. The kernel is §7.4's column-wise
multiply-add, four input rows at a time, with every word of a batch reusing each row while it is in
cache.

**Where it ran.** It ran on the tablet (SM-X610, Android 16) under a debug-key-signed **release**
build, installed with `adb install -r` and compiled with `cmd package compile -m speed -f`. It is
reached from the library's *Measure* menu (*Handwriting model speed*) and writes to logcat
(`InkstoneH4`). Every step ran on a big core (`cpu4`/`cpu5`, Cortex-A78 at 2.4 GHz). Each figure is
the median of 5 rounds of 400 steps, after 200 warm-up steps.

| Shape | Parameters | 1 word, µs/step | 4 words, µs/step | 8 words, µs/step | µs per word-step at 4 | at 8 |
|---|---|---|---|---|---|---|
| 3×256 | 1.67 M | 559 | 1,550 | 2,860 | 387 | 357 |
| 2×384 | 2.13 M | 654 | 1,923 | 3,529 | 481 | 441 |
| 3×400 | 3.76 M | 1,221 | 3,399 | 6,167 | 850 | 771 |

The unrolled kernel is worth 31 %: the one-row kernel took 2,247 µs for 3×256 at 4 words.

At one word, the step is memory-bound. 3×256 reads 6.7 MB of float weights per step, which at
559 µs is 12 GB/s, close to what this SoC's memory delivers. Batching is what amortises that read.

**A 40-character phrase, end to end.** *"Electric charge is conserved in circuits"* is six words. It ran
as a 16-character prime at one word, then the words in batches of four, each batch until its longest
word is done:

| Shape | Points per character | Whole phrase | First batch done (prime included) |
|---|---|---|---|
| 3×256 | 16 | **610 ms** | **385 ms** |
| 3×256 | 25 | 967 ms | 616 ms |
| 2×384 | 16 | 750 ms | 475 ms |
| 2×384 | 25 | 1,172 ms | 745 ms |
| 3×400 | 16 | 1,369 ms | 880 ms |
| 3×400 | 25 | 2,100 ms | 1,330 ms |

**§7.5's rule, applied literally.** Pure Kotlin stays only if a 40-character phrase finishes in
≤ 1.5 s at four words a batch, and the first word appears in ≤ 300 ms.
- 3×256 passes the first clause with room to spare: 610–967 ms.
- As measured, it fails the second: 385 ms at 16 points per character.
- That 385 ms is the prime, 256 steps at one word (143 ms), plus a first batch whose longest word,
  *conserved*, is 160 steps at four words (248 ms).
- 2×384 and 3×400 fail it by more.

**So, read literally, the rule says LiteRT.** LiteRT is `com.google.ai.edge.litert:litert`
**2.3.0** on Google Maven, last updated 2026-10-06 (`maven-metadata.xml`; release notes at
github.com/google-ai-edge/LiteRT/releases). It was verified, and **not added**, as the request asks.

**What the rule's second clause does not say.** It is §10's *"first word in the preview ≤ 300 ms after
typing pauses"*.
- §7.4 computes the prime **once per drop**, so it does not depend on what is being typed. The panel
  already measures the page ahead of the typing for D15 (§18.2, 8), and the prime can be computed at
  the same moment. Without the prime, the first batch is 248 ms at 16 points per character.
- The corpus this phase built has **14** points per character (§19.2), not 16. At 14, the same batch
  is 140 steps × 1.55 ms = **217 ms**, and the whole phrase is 125 ms of prime plus 266 steps =
  **537 ms**. These are derived from the measured per-step times, not measured end to end.
- In H4 the neural backend writes only what assembly cannot, which is usually one word. One six-letter
  word at one word per batch is 98 steps × 0.559 ms = **55 ms**.
- Nothing above uses more than one core. The tablet has four big ones.

**The decision is the user's (§19.1).**

### 19.1 The decision: pure Kotlin, with the prime computed ahead

**Asked on 2026-10-14; the user answered *"whatever you think best"*.** This is the reasoning behind
the choice.

**Pure Kotlin in `commonMain` stays. LiteRT is not added.** The rule's first clause passes with room
to spare. Its second clause passes once the prime, which §7.4 already computes once per drop, is
computed when the panel opens, as D15's page measurement is (§18.2, 8). The panel starts priming then,
and a word written before the prime is ready waits for it.

**What this costs, said plainly.** The rule as literally measured failed at 385 ms. The design's own
rule was met only after reading *"once per drop"* as *"off the typing path"*. If phase 5's switching
makes neural primary and the preview gets slower than §10's 300 ms in practice, LiteRT 2.3.0 is the
measured fallback. The reasons to keep it out now are:
- it adds a new dependency;
- it needs a PyTorch → TFLite conversion step;
- an `:android` module would put the inference beyond guardrail 2, the iOS link.

The per-step numbers above are the baseline it has to beat.

**Two headrooms are not used yet,** and both are recorded so that neither has to be rediscovered:
- the tablet's other three big cores;
- fp16 weights in memory, which would halve the one-word step's memory traffic.

### 19.2 What was built

- **`tools/handwriting/`**, the training side, in Python with stdlib `unittest` tests (27 tests):
  - `sigmalognormal.py` and `reference.py`: the motion model, plus an independent reference;
  - `fontskel.py`: licence-checked font skeletons;
  - `texts.py`: the text the synthetic writers write;
  - `synth.py`: the corpus;
  - `prepare.py`, `model.py`, `train.py`, `sample.py`, `export.py` and `segment.py`;
  - `sheets.py`: PNG contact sheets.

  PyTorch is pinned in `requirements.txt` and lives in a gitignored `.venv` (§19.3).
- **`:core:model`:** the `ModelSource` port (SPEC §2.2).
- **`:core:handwriting`:**
  - `motion/SigmaLognormal`: the Kotlin twin of `sigmalognormal.py`.
  - `neural/WeightsFile`: the `.ihwm` reader. It checks the trailer, the shapes and D13's licence
    policy.
  - `neural/SynthesisNetwork` and `KotlinSynthesisNetwork`: priming, batched writing, the runaway
    guard, Graves's bias, alignment, and a public `trace` for parity tests.
  - `neural/ModelInk`: real ink resampled to the model's density.
  - `PenDynamics`: §7.1's pressure, tilt and timing fit.
  - `NeuralBackend`: the prime, the bias ladder, and words returned in assembly's frame.
  - `HandwritingSynthesizer` gained the neural rungs. `WordOutcome.Written` carries its backend and
    model, `WrittenPhrase.taggedStrokes` pairs every stroke with its own `hw` tag, and
    `PhraseOps.build` takes one tag per stroke.
  - `neural/SpeedSpike`: the 4.0 spike.
- **`:app`:**
  - `AssetModelSource` and `ModelPolicy` (D13: the release policy, read off `FLAG_DEBUGGABLE`).
  - `HandwritingWriter` loads the model once and fits the hand's dynamics per profile.
    `primeAhead` caches the prime per profile and note, and the panel computes it when it opens
    (`PrimeWhileTyping`).
  - The stamp path now writes one tag per stroke.
  - `ModelSpeedSpike` and the library's *Measure* item.
  - Debug only: `NeuralGateReceiver`, which measures the neural backend's gate pass rate on a device.
- **The asset:** `app/src/main/assets/handwriting/base-v1.ihwm` (3×256, f16, 3,545,352 bytes; epoch
  15, SHA-256 `a6a191cb…`). In the release APK, its compressed entry is **3,279,569 bytes (3.28 MB)**,
  against §10's 3.5 MB budget. That was read from the APK, whose entry hashes to the same SHA-256.

### 19.3 The synthetic corpus

**The fonts.** Each one was downloaded with the user's approval. Its licence text and attribution are
kept beside it, and `fonts/LICENCES.json` records its SHA-256.
- **Hershey** `futural`, `scripts` and `cursive`: any purpose, with two acknowledgements. The repo's
  `COPYING` is the GPL for its C library, plus the Hershey terms for the `.jhf` data. Only the data is
  used.
- **Relief SingleLine** (OFL-1.1).
- **Seven EMS single-line faces** (OFL-1.1, per each file's own metadata): Casual Hand, Tech,
  Pancakes, Delight, Neato, Felix and Capitol. gitlab.com did not answer from this machine, so these
  are GRBL-Plotter's GitHub copies, with their metadata re-read after download.
- **Left out as risky:**
  - EMS Elfin: its upstream is Apache-2.0, but EMS labels it OFL;
  - EMS SpaceRocks: it derives from an arcade font;
  - Mistral SingleLine: a rework of Excoffon's 1953 face, with no stated permission.

  So was EMS Allure, whose flourished formal capitals would hurt legibility.

**Refusal.** `fontskel.manifest` refuses, and a test pins each case:
- a font not on the commercial allow-list;
- a changed file;
- an entry with no licence.

**The writers.** Each of 4,000 synthetic writers is one font plus a random style:
- cap height 1.45–2.05 x-heights; digit height 1.3–1.85;
- width ×0.85–1.2;
- a residual slant of σ 3° (the model works in the deslanted frame);
- letter and word spacing;
- joins: script faces keep their own, and some print writers get bridges;
- i-dots and t-bars delayed to the end of the word;
- sigma-lognormal timing, with each sample's pulses perturbed.

Every point is labelled with its character, which is the ground truth for v2 segmentation.

**Two things the font faces got wrong, both fixed.**
- **A face's declared metrics are not its glyphs:** EMS says x-height 300 and draws `a` up to 624.
  Baseline and x-height are measured from the font's own lowercase letters, so capitals are rescaled
  per writer.
- **Overlapping pulses cancel at a reversal.** The first sheet wrote `the quick brown fox` as
  *"tne quick orown"*: `h`, `b` and `k` lost their ascenders, because a stem drawn down and back up
  is two opposite pulses, and overlapped they cancel.
  - The kinematic theory's own answer is that the pen's speed reaches zero at a cusp. `plan` now
    waits at any corner sharper than 110° until the pulse is 1.5 σ past its median.
  - It is the same in Kotlin, and the shared reference has a retracing `h` to prove it.

**Parity.** Python and Kotlin agree on 12 shared reference trajectories:
- positions to **1e-9**, against velocity integrated numerically in log-time (`reference.py`) rather
  than either closed form;
- the planned pulses to **1e-12**.

**The corpus, `data/synth-v1`.** 48,000 phrases of one to three words, 6.09 M points, 14 points per
character. Generated in **84 s** on 8 processes; prepared in under a second. The writer-disjoint split
is 45,600 for training and 2,400 for validation.

### 19.4 Training: three epochs on this laptop's CPU (Q4), twelve on the user's PC

**The laptop.** An i7-1355U with no GPU; `torch 2.14.1+cpu`, 10 threads.

**First-epoch probe.** On a 12,000-sample probe corpus, a 3×256 model trained at 1.8 s per batch of
32: about 960 samples a minute, or 24 GFLOP/s.
- Batches of 64 and 128, and 6 threads against 10, were no faster. The work is compute-bound, not
  bound by per-step Python overhead.
- So an epoch costs about 10.6 MFLOP per point.
- **The corpus was sized from this:** 48,000 samples, about 45 minutes an epoch at that rate.

**What actually happened.** The run started at 09:24, and the real first epoch took **87 minutes**,
for two reasons:
- Gradle builds in this session took cores from it;
- from about 11:00 the laptop was **on battery**, and Windows gave the trainer about four of ten cores.
  Batches slowed from 1.75 s to 6–13 s.

At 41 % charge, falling about a percentage point a minute, training was **paused at its 11:48
checkpoint** rather than letting the laptop die. `--resume` picked it up on mains power, losing at
most one checkpoint interval.
- **Epoch 2:** the 995 batches after that resume took 30 minutes, 1.83 s a batch, which is the
  probe's rate.
- **Epoch 3:** 64 minutes of training over **5 h 7 min** of wall time. The laptop went to sleep twice
  mid-epoch; `train.log` shows gaps of 41 minutes and 3 h 22 min. A sleeping process is suspended,
  not killed, so it carried on where it was when the laptop woke, and nothing was lost.

**The PC.** After epoch 3 the user moved training to their own PC, which runs Arch Linux with a GPU.
`train-on-pc.sh` does the setup:
- it makes a venv outside the system Python;
- if the distribution's Python is newer than torch 2.14.1 has wheels for, it fetches Python 3.14
  with `uv`;
- it installs the pinned torch build that fits the GPU (CUDA 13.0, CUDA 12.6, ROCm 7.2, or CPU);
- it resumes from the laptop's checkpoint.

The transfer was a 71.9 MB tarball of the prepared corpus and the checkpoint. The log says
`device cuda`.
- **Epochs 4–15 took 106 s each**, about 24 times the laptop's rate on mains (1,425 batches × 1.83 s
  = 43 minutes). Twelve epochs took 21 minutes.
- Checkpoints are saved as CPU tensors, so the laptop loads a GPU run's files unchanged. The results
  came back by way of `D:\` and live in `runs/pc/runs/v1-3x256/`, outside git. That folder holds
  every epoch's checkpoint, exports of epochs 3, 6, 9, 12 and 15, and contact sheets for 9 and 15.

**The curve.** `curve.csv` and `curve.png` sit beside the checkpoints, outside git.

| Epoch | Train NLL / point | Validation NLL / point (writers never seen) | Where | Wall time |
|---|---|---|---|---|
| 1 | −0.465 | −0.868 | laptop | 87 min (contended, then on battery) |
| 2 | −1.455 | −0.725 | laptop | 30 min after the resume (mains) |
| 3 | −1.928 | **−1.130** | laptop | 64 min of training in 5 h 7 min (slept twice) |
| 4 | −2.263 | −0.994 | PC, GPU | 107 s |
| 5 | −2.456 | −0.930 | PC, GPU | 106 s |
| 6 | −2.657 | −0.781 | PC, GPU | 106 s |
| 7 | −2.787 | −0.350 | PC, GPU | 106 s |
| 8 | −2.894 | −0.398 | PC, GPU | 106 s |
| 9 | −2.998 | −0.364 | PC, GPU | 106 s |
| 10 | −3.061 | −0.282 | PC, GPU | 106 s |
| 11 | −3.123 | −0.275 | PC, GPU | 106 s |
| 12 | −3.159 | −0.109 | PC, GPU | 106 s |
| 13 | −3.189 | −0.254 | PC, GPU | 106 s |
| 14 | −3.276 | −0.032 | PC, GPU | 106 s |
| 15 | −3.287 | −0.039 | PC, GPU | 106 s |

**What the curve says, and what it does not.**
- **By likelihood, the model overfits after epoch 3.** Validation NLL is best there and climbs from
  then on, while training NLL keeps falling.
- **Legibility kept rising all the same.** Legibility is what the gate measures and what H4 ships
  on (§19.7). How probable an unseen writer's exact trajectory is, and whether a sample reads as its
  word, are different questions, and the checkpoint was chosen on the second.
- **A plausible reason, not measured:** the 4,000-writer style table is 128,000 of the model's
  1.90 M parameters. As training goes on, the network can explain more of each training writer
  through that writer's own vector, and a validation writer has no vector.
- **Why it matters later:** phase 5's P2 fits exactly that vector for the user, so this is the
  number to watch there.

### 19.5 v2 segmentation, measured before generation

**Against the synthetic ground truth.** `segment.py` runs on validation writers, whose ground truth
is the per-point character label `synth.py` writes. Each sample's points are assigned to letters, and
the table shows the share that land on the right letter.

| Model | Samples | Points | v2, every point | v2, written in order | Naive, every point | Naive, in order |
|---|---|---|---|---|---|---|
| Epoch 1 | 200 | 25,644 | 69.0 % | 70.0 % | 62.3 % | 63.2 % |
| Epoch 9 | 400 | 51,803 | 74.4 % | 75.6 % | 61.0 % | 62.0 % |
| **Epoch 15 (shipped)** | 400 | 51,803 | **76.7 %** | **78.0 %** | 61.0 % | 62.0 % |

- v2 is attention plus a monotonic Viterbi pass. The naive baseline shares the points out evenly over
  the letters.
- "In order" leaves out delayed i-dots and t-bars, where a monotonic path is wrong by construction.
- The naive baseline needs no model, and it differs between rows only because epoch 1 was measured
  on a smaller seeded draw.
- Both sets of results are saved in `runs/pc/runs/v1-3x256/sheets/seg-e*.json`.

**On the user's real words,** by eye. `HandwritingNeuralReport` aligned the tablet's 136-word profile
through the **Kotlin** network and the shipped asset. It drew each word twice, coloured by letter: once
as v1 cut it and once as v2 reads it.
- **133 words aligned.** Where v1 had cut every letter, v2 agrees on **89.6 %** of 25,897 points with
  epoch 15. With epoch 1 it agreed on 80.9 %.
- **Where v1 cut every letter, the two agree:** `16384`, `128`, `200` and `23` are identical.
- **v2 also segments what v1 could not cut,** one colour per character: `(2x+3)`, `(58.5)`,
  `22:4`, and `3%` written as `3°|o`.
- **Its error is visible too:** the long minus of `-120` is split between `-` and `1`.

The sheet is `segmentation-v2.svg`. It is 12.5 MB and was not kept in the repo. To regenerate it,
run `HandwritingNeuralReport` with `INKSTONE_HW_PROFILE` set to a copied profile and `INKSTONE_HW_OUT`
set to a folder. Without them, the test prints `SKIPPED` and passes without asserting anything.
The profile is the user's own handwriting, so it stays out of the repo. That makes the test a
reporting tool: it is not a check that runs in CI.

### 19.6 Kotlin against PyTorch

- **The tiny model.** `TinyModelFixture` is a 2×8 model with random weights, written by `export.py`
  exactly as it writes the real one. Every recorded step matches PyTorch to **1e-4**: each layer's h
  and c, κ, w, φ, the raw outputs, and the biased mixture weights and spreads through the functions
  sampling uses.
  - **The test was checked by breaking the code.** With the forget and cell gates swapped in Kotlin,
    `everyRecordedStepMatchesPyTorch` failed. With the swap reverted, it passed.
- **The shipped model.** `ShippedModelTest` holds the 3×256 asset to its own golden vectors at
  **1e-4**, over 60 teacher-forced steps through the public `trace`. The golden file carries the
  asset's SHA-256, and the test refuses a mismatch.

### 19.7 The gate, epoch by epoch, and the checkpoint that ships

**How it was measured.** `NeuralGateReceiver` (debug only) ran on the emulator: `Test_Tablet`, debug
build, airplane mode, real ML Kit, and its 19-word profile.
- It wrote each test word *n* times, one word per call, at neatness 0.6 (Graves's bias 1.2), and
  passed each sample through the app's own `ReliabilityGate`. Each figure is therefore a
  **first-pass** rate: the ladder's later attempts at a higher bias are not counted.
- A candidate checkpoint was pushed into `files/debug` for its run, and the app's asset was not
  touched.
- The seven test words, *bob jab cab quiz buzz kelvin zebra*, are built from the letters these
  profiles lack.
- *cation*, *fix* and *little* were added later for their marks: a t-bar, i-dots, and an x's second
  stroke.

| Model | Words × samples | Unprimed | Primed with the user's words |
|---|---|---|---|
| Epoch 1 (`1b1ab5c5`) | 7 × 4 | 3/28 | 1/28 |
| Epoch 2 | 7 × 4 | 11/28 | 7/28 |
| Epoch 3 (`f8df73d4`), best validation NLL | 7 × 6 | 33/42 | 32/42 |
| Epoch 6 (`82af427e`) | 7 × 6 | 35/42 | 35/42 |
| Epoch 9 (`0beae9af`) | 7 × 6 | 35/42 | 41/42 |
| Epoch 12 (`ef2be785`) | 7 × 6 | **42/42** | 40/42, and one sample stopped by the runaway guard |
| **Epoch 15 (`a6a191cb`)** | 7 × 6 | 40/42 | **41/42** |
| Epoch 15, with the grace period (§19.8) | 10 × 6 | 57/60 | 52/60 |
| Epoch 15, with the grace period | 10 × 12 | 112/120 | 110/120 |

- At epoch 1, ML Kit read *bob* as *Yor*, *loo* and *loon*.
- By epoch 15, the words that fail most are the ones with marks: *fix* passed 10 of 12, and *bob* 9
  of 12.
- Apart from that one sample at epoch 12, every sample finished, and nothing else hit the runaway
  guard.

**Epoch 15 ships** (`base-v1.ihwm`, `a6a191cb`).
- **Why not epoch 12:** epochs 12 and 15 are a tie, 82 and 81 of 84 passing. The app primes every
  drop, and epoch 15's primed rate was the higher of the two. Epoch 12 also had the run's one
  runaway.
- **That is a tie broken by a reason, not a measured difference.**
- **Why not epoch 3:** it has the best likelihood, and it passes the fewest words of any checkpoint
  after epoch 2.

**Priming, decided on these numbers.** §8.1 keeps a prime set only if the gate's first-pass rate does
not fall.
- **At epochs 1 and 2, priming with every word in the profile clearly lowered it:** 3 → 1 and
  11 → 7 of 28.
- **The prime set has been narrowed since.** `NeuralBackend.prime` now takes only lowercase words of
  three letters or more, preferring the page being written on. A capital, a digit string or a
  two-letter fragment are the parts of a hand least like what the network was trained on beside
  them.
- **On the shipped model, the result is a small fall, within noise.** Over all three runs, primed
  passes 203/222 (91.4 %) against unprimed 209/222 (94.1 %). That is 2.7 points, about one standard
  error (z ≈ 1.1).
- **Read literally, §8.1 would not accept this prime set.**
- **Priming stays on in H4.** The request asks for the prime once per drop, and a sample that fails
  goes on to the next rung of the ladder rather than onto the page. Accepting or rejecting P1 belongs
  to phase 5, which has the fidelity measure F that H4 does not.
- **Turning it off** means having `NeuralBackend.prime` return `Primed.NONE`.

### 19.8 Graves's stop cut off the marks, so a word has a grace period

**What went wrong.** The epoch-15 sheet wrote *cation* as *calion*. The synthetic writers, like
people, add i-dots and t-bars after the word (§19.3). Graves's end rule, *φ(U) beats every φ(u<U)
and the pen lifts*, fires at the lift after the last letter, which is before the marks.
- The trained model was doing the right thing.
- The sampler stopped it.

**The fix** is in both `model.py`'s `sample` and `KotlinSynthesisNetwork` (`WordEnd`).
- After Graves's stop, a word gets **two characters' worth of steps**.
- A stroke made in that time is kept if both of these hold:
  - it starts within 0.3 x-heights of the word's ink;
  - it is at most 1.2 x-heights long, so it is a t-bar or an x's second stroke, never a letter.
- Anything else ends the word, and the half-drawn stroke is dropped.
- The runaway guard's limit grows by the grace.

**What is still wrong.** Marks come out, but sometimes in the wrong place: a bar beside its stem, a dot
a little off its `i`. ML Kit reads the word all the same, which is why it passes the gate (§19.10).

### 19.9 In the app

**The fault test, on the emulator.** One byte of the asset was changed, and a debug APK was built with
it.
- The log said `model: off, CORRUPT: fails its own checksum`.
- *it is a cation* was refused as *Missing letters*. The emulator profile has had no `c` and no `t`
  since phase 3's `LetterFit`.
- *it is a* was written by assembly: `wrote: words=3 strokes=8 … neural=0 model=-`. The journal held
  eight strokes tagged `hw=…:a:-`.
- Nothing else changed. `NeuralBackendTest` holds the same fault in the JVM.

**A neural word, written and dropped on the emulator.** The real asset was restored, and its SHA-256
was checked inside the APK.
- The model loaded: `model: loaded a6a191cb 3x256 policy=DEVELOPMENT nonCommercial=[] ms=1082`.
- The panel's prime took 182 ms.
- *it is a cation* previewed complete: `firstWordMs=668 allMs=670 neural=1 refused=[]`.
- Dragged onto a new note at the library root, it wrote:
  `wrote: words=4 strokes=18 … neural=1 model=a6a191cb neuralAttempts=1 ms=921`.
- The note's `journal.ops` holds **8 strokes tagged `hw=<group>:a:-` and 10 tagged
  `hw=<group>:n:a6a191cb`**, all in one group, so the phrase still selects, moves and undoes as one.
- The screenshot shows *it is a cation* on the ruled line. *cation* has its marks, slightly
  misplaced.

These are debug-build timings on an x86_64 emulator. §10's budget is a release-build number, and the
emulator cannot speak to it.

**The tablet, with the epoch-1 model** (release build, installed with the user's leave):
- the model **loaded under `policy=RELEASE`** in 122 ms;
- the prime took 58 ms;
- on a new note at the library root, *a buzz jab bib* (three words this profile cannot assemble)
  previewed in **489 ms**, including nine neural samples, each read by the gate;
- every sample was refused, and then shown as *Missing letters*. Nothing garbled was drawn.

**The tablet, with the shipped model** (2026-10-14, evening; a debug-key-signed release build, compiled
with `-m speed`, the user's 136-word profile):
- **Loading and priming.** The model **loaded under `policy=RELEASE`** in **93 ms**
  (`a6a191cb 3x256 nonCommercial=[]`). The panel's prime took 190 ms when it opened, ahead of any
  typing.
- **The phrase this morning's epoch-1 model could not write**, *a buzz jab bib*, previewed
  **complete**: `neural=3 refused=[]`, with all three `b` words written by the network and passed
  by the gate.

**Preview times** (after the first preview, which also warms ML Kit, as below):

| Phrase | Network words | First word | Whole phrase |
|---|---|---|---|
| *it is a* | 0 | 75–80 ms | 75–80 ms |
| *is a cab* | 1 | 129–139 ms | 129–139 ms |
| *a buzz jab bib* | 3 | 204–261 ms | 343–486 ms |

All of these are within §10's 300 ms for the first word. **The first preview after the app
started took 403 ms.** It was the session's first gate call, so ML Kit's warm-up is the likely
cause; that is not measured separately (§19.10).

**Word by word,** three previews each:
- *buzz* and *jab* were complete every time, at 106–142 ms;
- ***bib* was refused in 2 of 3**: it failed all three network attempts, so it was shown as refused
  and not drawn (§19.10).

**The drop.**
- *a buzz jab* was placed with a stylus tap on a new note at the library root: `wrote: words=3
  strokes=20 … xh=5.57 … neural=2 model=a6a191cb neuralAttempts=2 ms=200`. Both network words passed
  the gate on their first sample, and the whole write, from assembly through the stamp, took
  **200 ms**.
- The note's bundle, copied off with the debug build for a few minutes, holds **1 stroke tagged
  `hw=<group>:a:-` and 19 tagged `hw=<group>:n:a6a191cb`, all in one group**: 20 strokes, as logged.
- On the page, the phrase sits on the ruled line at the hand's x-height. *buzz* reads well. *jab* is
  rougher, and a few dots float above the word: the misplaced marks of §19.8.

**Housekeeping.**
- A note created on 13 October at 21:04, empty and not from this session, was left where it was.
- Both scratch notes were moved to the local trash through the app's own menu: this one, and the
  one from the morning's epoch-1 check. The library database confirmed both.
- The release build went back on afterwards.

**What did not work as a method:** dragging the preview onto the page with `input swipe` did not
place it on the tablet, with or without the keyboard showing. It had worked on the emulator. A
stylus tap on the page, the panel's other way to place a phrase, did. Whether a real finger drag
works on the tablet was not tried.

### 19.10 Found, and not fixed

- **Marks are sometimes misplaced** (§19.8). The gate reads words, not marks: the same lesson as
  `LetterFit` in §18.7. Nothing checks where a neural word's dot or bar sits. Phase 5's fidelity
  measure is the natural place for that check.
- **Validation NLL rises after epoch 3** (§19.4). The likely cause, the writer table, is unmeasured.
- ***bib* is refused 2 times in 3 on the tablet,** after all three network attempts. *buzz*, *jab* and
  *cab* were written every time. A refusal is the designed outcome, and nothing garbled is drawn. But
  this user cannot reliably write *bib* until either assembly has a `b` or phase 5 personalises the
  model. Short words built of ambiguous strokes (`b`, `i`, `l`, `h`) are the likely weak spot, which
  is not measured.
- **The first preview after the app starts took 403 ms on the tablet**, against §10's 300 ms. Later
  previews of the same phrase took 204–261 ms. The gap is most likely ML Kit warming up on the first
  gate call, but that was not measured on its own. If it is, warming the recogniser when the panel
  opens, as the prime already is, would close it.
- **The emulator's first word took 668 ms,** a debug build on x86_64. It says nothing about §10.
- **`input swipe` could not drag the preview onto the page on the tablet** (§19.9). A real finger
  drag was not tried there.
- **The 7 × 6 runs could not tell epochs 12 and 15 apart.** A sharper comparison needs more samples,
  or §8's F.
- **The emulator's profile has no `c` and no `t`** since phase 3's `LetterFit` set its only sources
  aside. That made the emulator a good place to test the neural fallback. It also means phase 3's
  emulator walks would refuse *cation* today.
- **`tools/handwriting/.venv` is 607 MB inside OneDrive.** It is gitignored, but OneDrive syncs it.

### 19.11 Decisions made while building

1. **Pure Kotlin, with the prime computed ahead** (§19.1).
2. **Training moved to the user's PC** at their request, after the laptop ran on battery and then
   slept through most of epoch 3 (§19.4).
3. **The checkpoint is chosen by the gate, not by likelihood** (§19.7).
4. **Priming stays on, with a narrower prime set**, and P1's acceptance is left to phase 5 (§19.7).
5. **A grace period after Graves's stop** (§19.8).
6. **`hw` is per stroke.** A phrase can now mix backends, so `WrittenPhrase.taggedStrokes` gives each
   stroke its own tag, and `PhraseOps.build` takes one tag per stroke, all with one group. SPEC §4.10
   says so.
7. **Where the neural rungs sit in §8.2's ladder.** A word assembly cannot build goes to the network
   first. A word assembly built and the gate refused through every rung goes to the network
   afterwards. The network gets three attempts, each 0.5 higher in Graves's bias. A word every
   attempt fails is refused, as before. Assembly stays primary.
8. **The model is an asset read through a `ModelSource` port** (SPEC §2.2). `:core:handwriting`
   never sees Android's `AssetManager`.
9. **The release policy is read off `FLAG_DEBUGGABLE`.** A debug build loads a non-commercial model
   and logs its licences; a release build refuses it.

### 19.12 Not done, and why

- **Pressure and tilt on a device.** The neural strokes on the tablet were not decoded to check that
  they carry the fitted pressure. `PenDynamicsTest` covers the fit.
- **A log line for batching.** The tablet's *a buzz jab bib* preview sent three network words in one
  round, and `generate` hands a round to a single `write` call, which batches four at a time. So the
  batched path ran on the device. That is read from the code, though; nothing logs the batch.
- **The iOS framework link** is skipped on Windows; the macOS CI job owns it. The klib compiles ran.
- **`./gradlew build` as a whole** is still red for §16.4's reasons, and was not run as a whole.
- **No instrumented tests** were added, so none were run.

### 19.13 Per claim

| H4 claim | Automated | Real dependency | Device |
|---|---|---|---|
| 4.0: numbers and the decision recorded before any inference code | — | — | ✅ The tablet's release build (§19.0), written into this doc before `KotlinSynthesisNetwork` existed |
| `synth.py` makes a corpus from the licensed fonts alone | ✅ `test_pipeline.py`: `fontskel.manifest` refuses a font off the allow-list, a changed file, and an entry with no licence | ✅ 48,000 phrases from 11 faces, each with its licence text, attribution and SHA-256 beside it | — |
| Sigma-lognormal: Python and Kotlin agree | ✅ `SigmaLognormalReferenceTest`, 12 shared trajectories: positions to 1e-9, against a third, numerically integrated reference; pulses to 1e-12 | — | — |
| `prepare.py` refuses a corpus with no licence entry | ✅ `test_pipeline.py` | — | — |
| The exported header lists every dataset | ✅ `test_pipeline.py`; `WeightsFileTest` reads `datasets` back | ✅ The shipped asset's header names the synthetic corpus and each font's licence | — |
| A release build refuses a non-commercial header | ✅ `WeightsFileTest` (`RELEASE` refuses, `DEVELOPMENT` flags); `ShippedModelTest`: the shipped asset loads under `RELEASE` | — | ✅ The tablet's release build logged `policy=RELEASE nonCommercial=[]` and loaded the shipped model in 93 ms. ❌ A non-commercial model was never put on a device: none exists, and none may ship. |
| A corrupt asset turns the backend off; assembly carries on | ✅ `NeuralBackendTest`, the fault test | — | ✅ The emulator: `CORRUPT: fails its own checksum`; assembly wrote *it is a* (§19.9) |
| `tools/handwriting` trains and exports; its tests pass | ✅ 27 `unittest` tests | ✅ 15 epochs trained, 5 exports, run on two machines | — |
| Curves and sheets kept, outside git | — | ✅ `runs/pc/runs/v1-3x256/`: `curve.csv`, `curve.png`, contact sheets for epochs 9 and 15, `seg-e*.json` | — |
| v2 segmentation against synthetic ground truth | ✅ `PenDynamicsTest` holds the Viterbi pass on fixtures | ✅ `segment.py`: 76.7 % against the naive split's 61.0 %, 400 samples (§19.5) | — |
| v2 segmentation by eye on the user's real words | — | ✅ 133 of 136 words, through the **Kotlin** network: 89.6 % agreement with v1. The sheet was looked at (§19.5) | — |
| Kotlin matches PyTorch to 1e-4 | ✅ `SynthesisNetworkGoldenTest` (the tiny model, every intermediate); `ShippedModelTest` (the shipped asset, 60 steps) | ✅ The goldens are written by `export.py` from PyTorch itself. A gate swap made the test fail, so it can fail | — |
| Primes once per drop | ✅ `NeuralBackendTest`: one prime across a drop's words and rounds, this page's words first; `KotlinSynthesisNetworkTest`: a word starts where the prime's pen left off | — | ✅ When the panel opened: 182 ms on the emulator, 190 ms on the tablet. A prime made inside the drop does not log, so the logs cannot show the drop reused that one. The cache is keyed per profile and note. |
| Batches words | ✅ `KotlinSynthesisNetworkTest`: a batch gives one result per word, in order, and skips what it cannot write; `NeuralBackendTest`: one batched call per round | — | ✅ The tablet: *a buzz jab bib* sent three network words in one round, and `generate` passes a round to one `write` call (batch 4). This is read from the code path; it is not logged. |
| Runaway guard | ✅ `KotlinSynthesisNetworkTest`: a frozen window ends at the guard | — | ✅ One sample in 84 at epoch 12 ended there, and nothing was drawn from it |
| Neatness → Graves's bias | ✅ `NeuralBackendTest` | — | ✅ Every receiver run used neatness 0.6 → bias 1.2 |
| Pressure and tilt from the user's fit | ✅ `PenDynamicsTest` | — | ❌ Not inspected on a device |
| Writes what assembly cannot, through §8.2's ladder | ✅ `NeuralBackendTest`: missing letters go to the network from the first round; a word assembly built but could not make legible goes to it after assembly's last rung; three neural attempts, then refused; a letter outside the model's alphabet stays refused | ✅ Real ML Kit read every neural word before it was used | ✅ On the emulator, the network wrote and dropped *cation* (that profile has no `c` and no `t`). On the tablet, it wrote and dropped *buzz* and *jab* (the profile has no `b`) (§19.9). ❌ The second route (assembly built a word and it was illegible) has not been seen on a device. |
| Every word goes through the gate | ✅ `NeuralBackendTest`: one gate call per round, with both backends' words in it; a refused neural word draws nothing | ✅ 892 samples read back on the emulator (§19.7) | ✅ The tablet: `neuralAttempts=2` for two network words. *bib* was refused, not drawn, in 2 of 3 previews. |
| Assembly stays primary | ✅ `NeuralBackendTest`: a word assembly can write stays assembly's, and the network is not asked until assembly's rungs run out | — | ✅ The emulator drop: 3 assembly words, 1 network word. The tablet drop: 1 assembly word, 2 network words, which were the ones with `b`. |
| Neural strokes tagged `hw=…:n:<sha8>` | ✅ `PhraseOpsTest` (mixed tags, one group); `NeuralBackendTest` (every stroke of both words, tagged) | — | ✅ Emulator: 8 × `:a:-`, 10 × `:n:a6a191cb`. Tablet: 1 × `:a:-`, 19 × `:n:a6a191cb`. One group each. |
| Gated neural words, timed on the tablet's release build | — | ✅ Real ML Kit on the tablet | ✅ First word 204–261 ms, whole three-network-word phrase 343–486 ms, drop 200 ms (§19.9). ⚠️ The first preview after launch took 403 ms (§19.10). |
| Platform-free, and the iOS target | ✅ `checkCoreIsPlatformFree`; the iOS klib compiles of `:core:model` and `:core:handwriting`, which caught a JVM-only `toSortedMap` | ❌ The framework link belongs to the macOS CI job | — |

### 19.14 The runs behind the table

Each test task ran with `--rerun` and `--no-build-cache`, and none was up to date or restored from
cache. The counts below are read from the XML results.

| Task | Tests | Run |
|---|---|---|
| `:core:handwriting:testDebugUnitTest` | 151 | 2026-10-14, on the final code. That includes the second-route test, which was checked by disabling the loop it covers: it failed, and it passed again once the loop was back. |
| `:app:testReleaseUnitTest` | 418 | 2026-10-14, all passing. This is the shared checkout, with other sessions' work in it. |
| `:app:testDebugUnitTest` | 418, of which 40 failed | 2026-10-14. All 40 are `MergeMatrix*Test`, the live half that needs the Docker Nextcloud, which is not running here (§16.4). The handwriting classes passed: `ShippedModelTest` 3, `NoteEditorHandwritingTest` 3, and `HandwritingNeuralReport` and `HandwritingSheetReport` 2 each. The two reports skip without a pulled profile. |
| `:core:model:testDebugUnitTest` | 416 | 2026-10-14 |
| `tools/handwriting` (`python -m unittest`) | 27 | 2026-10-14 |
| `segment.py` on epoch 15 | — | 2026-10-14. It reproduced 76.7 % / 78.0 % in 80 s. |

These are green:
- `:core:handwriting:detekt` and `spotlessCheck`;
- `:core:model:spotlessCheck`;
- `:app:spotlessCheck`;
- `checkCoreIsPlatformFree`;
- the iOS klib compiles of `:core:model` and `:core:handwriting`, main and test.

`:app:detekt` was up to date from its last green run. `./gradlew build` is still red, for §16.4's
reasons. Instrumented tests were not run: this phase added none.

## 20. Phase 5: personalisation, the gate, fidelity and switching (H5, 2026-10-14/15)

### 20.0 The short version

- **The gate is calibrated on this hand, per kind of token** (§20.2). Words get `PUNCTUATION`,
  formulas `EXACT`, numbers `CASE_ALIKE`; all three are judgeable on the tablet's profile, and symbols
  are too few to calibrate. The calibration found that the positive control had to be **vouched**
  words (the quick start's prompts, corrections), because a harvested word was chosen for reading back.
- **A token the recogniser cannot judge is built only from verified letters**, never by the network
  and never read back (§20.2.3). It is built and tested. On this hand no class needs it yet.
- **Fidelity F is built** (§20.3): DTW + chamfer, §8.3's score, on the held-out words, and a
  cross-validated version over every usable word. Assembly scores **0.45–0.72**; the network,
  unpersonalised, about **0.05**.
- **The F curve** is two things: the profile's own history, re-measured at a quarter, a half,
  three-quarters and all of its training words (assembly's coverage climbs **47 % → 83 %**, its F
  **0.64 → 0.72**); and one point per personalisation round from now on, stored in `profile.json`
  and listed on *My handwriting* (§20.3.3).
- **The ladder, measured** (§20.4):
  - **P1 (priming) does not help.** It lowers the gate's first-pass rate (**112 → 102 of 120**,
    re-measured after §18.10's `catchUp`) and does not raise F by δ. It is decided **off**.
  - **P2 (style vector) does not help.** Its hand-written backward pass matches PyTorch's autograd
    and finite differences. The fit cuts the network's loss on unseen words from 4.10 to 1.63 nats a
    point, but F barely moves, and the gate's first-pass rate **falls from 0.90 to 0.26**. Rejected.
  - **P3 (adapters) raises F a lot and costs legibility, at every strength.** On the tablet, F rose
    **0.075 → 0.357** (+0.28 ± 0.08 over 28 unseen words), and the first-pass rate fell
    **0.90 → 0.46**. Shrunk to a half or a quarter, it still gains F (+0.12, +0.08) and still costs
    0.15 and 0.10 of the first-pass rate, against an allowance of 0.03. **Not shipped** (§20.4.4).
- **Switching is built** (§20.5): the policy (unit-tested), per-character routing, the *Auto /
  My letters / Model* setting, and a switch seen on the emulator. With *Model*, *it is set* came out
  as 15 network strokes; with *Auto*, as 7 assembly strokes plus 13 network strokes.
- **The worker runs only while charging and idle.** It fits in five-minute slices with checkpoints.
  Killed in another process between checkpoints, or inside a checkpoint's write, it resumes **bit for
  bit**, losing at most one checkpoint interval (`CrashSafetyTest`, 6/6 on `Test_Tablet`).
- **Maths** (§20.6): numbers are judgeable on this hand, and every digit, `.`, `-`, `+` and bracket
  has verified single-letter copies. `=`, `/` and `%` have none, so they come only from the model.
  A maths-weighted base model (`v2`, 106 characters) is prepared and waiting for the PC run.

### 20.1 What was built

- **`:core:handwriting`:**
  - `quality/Fidelity`, `FidelityProbe`, `FidelitySet`: §8.3's distance and score.
  - `GateCalibration`, `TokenClass`, `GateCalibrator`: §8.2's two controls, per token class.
    `GateRule.PUNCTUATION` is new.
  - `quality/BackendPolicy`, `Route`, `QualityMeasure`, `PolicyState`, `BackendChoice`: §8.4.
  - `neural/TrainableNetwork`: the network with a **hand-written backward pass**, through time, for
    the style vector and for adapters.
  - `neural/Adapters`: P3's rank-r adapters, folded into `KotlinSynthesisNetwork` at construction.
  - `neural/Fit` (`Fitter`, `FitCheckpoint`, `FitPlan`): Adam, early stopping, a prior, and
    resumable, deterministic checkpoints.
  - `Personaliser`: one pass, a resumable stage machine (calibrate → P2 → P3 → P1 → measure), and
    §8.1's acceptance with shrinkage. `Artefact` names the base model inside every artefact file.
  - `GlyphAssembly.word(strict = true)`: §8.2's verified-letters route. `GlyphUnit.vouched`.
  - `HandwritingSynthesizer` takes a `Route`, writes un-judgeable tokens strictly, and puts the
    network first when it is primary.
  - `ProfileState` gains `gate`, `quality`, `policy`, `backend` and `personal`, all in `profile.json`.
  - `HandwritingLayout.styleDir`: `style/style.bin`, `style/adapters.bin`, `style/checkpoint.bin`.
- **`:app`:**
  - `PersonalisationWorker`: periodic, every 15 minutes, **charging + idle**.
  - `HandwritingPersonaliser`: the pass, under the learner's lock for `profile.json`.
  - `HandwritingWriter` builds the network from the accepted artefacts, the gate from the
    calibration, and the route from the policy and the setting. It honours P1.
  - *My handwriting*: *Who writes first* (Auto / My letters / Model), the fidelity history, and what
    the gate trusts.
  - `PersonaliseNowReceiver`: an **adb-only** trigger, guarded by `android.permission.DUMP`
    (§20.7.2 says why it exists).
- **Debug:** `PersonaliseReceiver`; `CrashPointService`'s `PERSONALISE` and `PERSONALISE_WRITE`;
  `PersonaliseCrash`.
- **Reports** (`androidUnitTest`, skip without a pulled profile): `FidelityReport`, `LadderReport`,
  `MathsReport`.
- **`tools/handwriting`:** `model.sequence_loss` (the reference for the backward pass); `export.py
  --tiny` now writes PyTorch's gradients into `TinyModelFixture`; and the v2 maths corpus (§20.6.3).

### 20.2 The reliability gate, calibrated (§8.2)

#### 20.2.1 How

`GateCalibrator.read` runs every usable word of the profile through **the real recogniser**, read
exactly as the gate reads a generated word (`ReliabilityGate.toRead`: the word denormalised at 5.5 pt,
in a line-height area, k = 5, no pre-context). `calibrate` then decides, per `TokenClass`:

- **positive:** each rule's pass rate on the user's words against their own labels;
- **negative:** each rule's *failure* rate against a wrong label of similar length (±1 character),
  of the same class where one exists — §8.2's control;
- **negative, one edit:** against the label with one character changed for one of its kind (a digit
  for a digit). Reported, not a threshold: it is what a garbled word actually looks like;
- **the rule:** the strictest with positive ≥ **0.95** and negative ≥ **0.99**. With none reaching
  0.95, the most lenient safe one. The class is **judgeable** only if that rule passes at least
  **0.80** (`FLOOR`) of the user's own words. A class with fewer than 8 words is not calibrated and
  keeps the default.

⭐ **The positive control must be words the user vouched for.** A harvested (`AGREED`) word was kept
*because* ML Kit read it back as its label, twice and steadily (§5.3), so it passes nearly by
construction. Only a quick-start prompt or a correction is an unbiased witness. The control uses the
vouched words when there are at least 10 of them, and is marked `biased` when it falls back to all.

⭐ **`PUNCTUATION` is never offered to a class with a digit.** Folding punctuation passes `25` for
`2.5`, which is §18.10's vanished decimal point.

⭐ **Since 2026-10-16 a class with a digit is measured on its first reading that holds a digit,** as
the gate now judges it (§21.4.2). A calibration written under an older rule has a lower `ruleVersion`
(or none) and is measured again on the next pass.

#### 20.2.2 The numbers (the tablet, real ML Kit, 2026-10-15 00:16)

These were measured under H5's rule, where every class could match any of the five candidates.
§21.4.1 has the same profile under the top-reading rule, and §21.4.2 under the rule now in force.

| Class | Words read | Vouched | Control | EXACT | SPACING | CASE_ALIKE | PUNCTUATION | Negative | Negative, one edit | Rule | Judgeable |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Words | 115 | 29 | vouched (unbiased) | 0.931 | 0.931 | 0.931 | **0.966** | 1.000 | 0.983 | `PUNCTUATION` | ✅ |
| Formulas | 24 | 2 | **biased** (all) | **0.958** | 0.958 | 0.958 | — | 1.000 | 1.000 | `EXACT` | ✅ |
| Numbers | 15 | 1 | **biased** (all) | 0.933 | 0.933 | 0.933 | — | 1.000 | 1.000 | `CASE_ALIKE` | ✅ (≥ 0.80) |
| Symbols | < 8 | — | — | — | — | — | — | — | — | default | not calibrated |

What sets the thresholds:
- **Words:** 2 of the 29 vouched words fail every rule but `PUNCTUATION`. Both are prompt words
  with punctuation the recogniser drops. So the 0.95 bar lands on `PUNCTUATION`, at 0.966.
- **Numbers:** no rule reaches 0.95 (1 of 15 fails all three), so the most lenient safe rule is used,
  and 0.933 clears the 0.80 floor.
- **One edit:** a wrong digit was refused **15 of 15** times for numbers and **24 of 24** for formulas.
  The gate catches the failure that matters most in maths.
- ⚠️ **Numbers and formulas rest on a biased control:** 1 and 2 vouched words. The quick start's
  maths prompts (6 and 7, §18.10) are not recorded on the tablet yet. Recording them is what would
  turn these into unbiased numbers.

#### 20.2.3 Tokens the recogniser cannot judge

Built and tested, not needed on this hand yet.
- `GlyphAssembly.word(strict = true)` builds only from **single cut letters** (each read back on its
  own when it was cut) and units the user **vouched** for. A harvested whole number is *not* used: its
  label came from the reader that class was just measured not to trust.
- No joins, and at least `STRICT_GAP` (0.12 x-heights) between two units: *"laid out without
  overlap"*.
- The synthesizer writes such a token first and never reads it back (`WordOutcome.Written.readBack =
  false`), and never asks the network. A letter with no verified copy refuses it, saying which.
- `MathsReport` drew 26 maths tokens both ways from the tablet's profile. The strict route writes
  every number the ordinary route writes. `46%`, `3/8`, `x=2` and `=` are refused by both: no `%`,
  `/` or `=` has a usable copy of its own (the one cut `=` is from a misframed word, §20.6.1).
- **Found by that sheet:** a cut `N` carried a full stop, so `NO2` drew as `N·O2`. Fixed: cut letters
  and digits now get the stray-ink test that whole words had (§20.8).

### 20.3 Fidelity (§8.3)

#### 20.3.1 As built

- **Distance:** `d = DTW / path length + λ·chamfer`, λ = 1. The resampling is 32 points per x-height
  of path, **pen-up travel included** as points with the pen up. A step costs the distance, plus 0.5
  x-heights when the pen states disagree. Words are compared in the normalised frame, left edges
  aligned.
- **Held-out words** are normalised exactly as assembly's units are (`GlyphIndex.normalisedWord`);
  words whose cut letters do not fit their frame are left out.
- **d_self:** the median distance between two samples of the same word, over all samples (it is a fact
  about the hand). Failing that, the median of its letters' pooled self-distances, then the
  profile's.
- **d_generic:** three unprimed samples of the unpersonalised network; **F per word** is §8.3's clamp;
  three samples per backend per word.

#### 20.3.2 The numbers (the tablet's profile, pulled; laptop JVM)

The profile holds 196 words: 183 training, 13 held out, 10 of them usable. §8.3 asks for 50. **Ten
words cannot carry a decision**, so every decision also uses a cross-validated F: 5 folds over all 155
usable words, each fold's backends built only from the other four.

| What | F (median) | F (mean) | Words written |
|---|---|---|---|
| Assembly, §8.3 (10 held out) | **0.475** | — | 9 / 10 |
| Network unprimed, §8.3 (the zero, a fresh draw) | 0.000 | — | 10 / 10 |
| Network primed (H4's P1), §8.3 | 0.000 | — | 10 / 10 |
| Assembly, cross-validated | **0.716** | 0.601 | 128 / 155 |
| Network primed, cross-validated | 0.000 | 0.094 | 155 / 155 |
| Network unprimed, cross-validated (noise floor) | 0.000 | 0.074 | — |

#### 20.3.3 The F curve

**Over the profile's own history** (cross-validated, each fold's training words subsampled):

| Training words used | Assembly coverage | Assembly F (median) | Assembly F (mean) |
|---|---|---|---|
| 25 % | 73 / 155 (47 %) | 0.644 | 0.562 |
| 50 % | 101 / 155 (65 %) | 0.687 | 0.591 |
| 75 % | 118 / 155 (76 %) | 0.638 | 0.584 |
| 100 % | 128 / 155 (83 %) | **0.716** | **0.601** |

What it says: *improves with use* is, so far, mostly **coverage**. More of the user's words become
writable, while the closeness of a word that can be written rises a little (mean 0.56 → 0.60).

**From now on**, every personalisation round appends a `QualityMeasure` to `profile.json`: the date,
the training-word count, F for both backends with their held-out count, assembly's coverage, the
network's first-pass rate over 200 gated words, failures per character, and the rungs in force.
*My handwriting* lists the latest six. The tablet's points so far:

| When | Training words | F assembly | F network | Held out | Coverage | First pass (200) | Rungs | Primary |
|---|---|---|---|---|---|---|---|---|
| 2026-10-15 00:47 (round 1) | 183 | 0.451 | 0.038 | 10 | 0.90 | 0.90 | none | assembly |
| 2026-10-15 01:11 (round 2) | 183 | 0.433 | 0.038 | 10 | 0.90 | 0.90 | none | assembly |

Both points have the same words, so they measure the instrument rather than any learning. Assembly's
0.02 between them is its noise over ten held-out words. The curve starts to say something once the
profile grows: a round is due at every max(10, a quarter) more training words.

### 20.4 The ladder (§8.1)

#### 20.4.1 Acceptance, as built

- An artefact is measured against what is in force on **every word no fit saw**: the held-out words
  plus the fit's own validation words (1 in 6 of the training words, by fingerprint). That is 43
  words on the laptop's split and 28 on the tablet's. §8.3's held-out ten alone cannot tell two
  configurations apart.
- **The statistic is the paired mean gain** in F per word. It must beat δ = **0.02** *and* its own
  standard error. The median is useless here: while the network is far from the user, most of its
  words score 0, and the median is 0 for every configuration.
- **The first-pass gate rate** over 120 of the user's own words must not fall by more than 0.03.
- **Shrinkage** (added after the tablet's first round): a finished fit is tried at full strength,
  then at a half and a quarter of the way from what is in force. The strongest blend that passes is
  kept. A rejected fit goes to the trash, never lost.

#### 20.4.2 P1: priming

- **The gate, re-measured** (`Test_Tablet`, epoch 15, H4's 10 words × 12, neatness 0.6, real ML
  Kit): **unprimed 112/120, primed 102/120**. In §19.7, before §18.10's `catchUp`, it was 112 against
  110. Fixing the prime-text leak did not rescue priming; it lowers the first-pass rate, now at
  z ≈ 2.1.
- **F, on the laptop (43 unseen words):** priming raises the base network's mean F from 0.053 to
  0.116, and *lowers* every personalised network's (P2: 0.097 → 0.027; P2+P3: 0.264 → 0.126).
- **On the tablet:** no prime 0.098, the page prime 0.0996, and no fixed prime set better. All are
  under δ, so **P1 is decided off** (`PrimeMode.NONE`), and the writer stops priming.

#### 20.4.3 P2 and P3 on the laptop (the tablet's profile, 87 fit words, 33 validation, 43 unseen)

**The backward pass** is checked three ways (`TrainableNetworkGradientTest`, 6 tests):
- against PyTorch's autograd on the tiny model (`model.sequence_loss`), for the loss, ∂L/∂s, and
  every adapter tensor, at 1e-4 relative;
- against finite differences of the loss itself;
- with the adapters folded into the inference network, which gives the same loss.

Two deliberate mutations (dropping κ's carried gradient; dropping the adapters' recurrent term) each
failed the tests, and passed again once restored.

| Configuration | Validation NLL / point | F median | F mean | Fit time (laptop) |
|---|---|---|---|---|
| Base, unprimed | 4.098 | 0.000 | 0.053 | — |
| Base, primed | — | 0.004 | 0.116 | — |
| P2 (style), unprimed | 1.629 | 0.000 | 0.097 | 179–213 s, 12 epochs |
| P2, primed | — | 0.000 | 0.027 | |
| P2 + P3 (rank 4), unprimed | 0.489 | **0.171** | **0.264** | +141 s, 12 epochs |
| P2 + P3, primed | — | 0.000 | 0.126 | |
| P3 alone, on the model's own style | 0.477 | 0.034 | 0.245 | 123 s |
| P3 alone, neatness 0.75 | — | 0.147 | 0.246 | |

Assembly on the same 43 words: median 0.455, mean 0.466, 31 of 43 written. The style vector travels
**2.8** from the model's own (whose norm is 0.03) despite the prior: P2 moves the network somewhere
its training never went.

#### 20.4.4 On the tablet (release build, real ML Kit, 100 fit words, 18 validation, 28 unseen)

**Round 1** (2026-10-15 00:16–00:47, no shrinkage yet):

| Rung | Mean F before → after | Paired gain | First pass before → after | Verdict |
|---|---|---|---|---|
| P2 | 0.075 → 0.078 | +0.003 ± 0.030 | 0.90 → **0.26** | rejected |
| P3 (on the model's style) | 0.075 → **0.357** | **+0.283 ± 0.077** | 0.90 → **0.46** | rejected |
| P1 | none 0.098, page 0.0996 | under δ | — | **off** |

The finding: **P3 makes the network write like the user and stops ML Kit reading it.** The gain in F
is real, at about 3.7 standard errors, and the gate rule rejects it, as §8.1 says it must.

**Round 2** (2026-10-15 00:57–01:11, with shrinkage, started at P2 with `--es from P2`; the same 183
training words, the same 28 unseen words, the same 120 for the first pass):

| Rung | Blend α | Mean F after (from 0.075) | Paired gain | First pass after (from 0.90) | Verdict |
|---|---|---|---|---|---|
| P2 | 1 | 0.078 | +0.003 ± 0.030 | 0.26 | rejected |
| P2 | ½ | 0.153 | +0.078 ± 0.048 | 0.71 | rejected |
| P2 | ¼ | 0.095 | +0.020 ± 0.034 | 0.83 | rejected |
| P3 | 1 | **0.357** | **+0.283 ± 0.077** | 0.46 | rejected |
| P3 | ½ | 0.199 | +0.124 ± 0.034 | 0.75 | rejected |
| P3 | ¼ | 0.153 | +0.079 ± 0.041 | 0.80 | rejected |
| P1 | fixed set | 0.046 → 0.072 | +0.026 ± 0.021 | 0.825 | rejected |

What it says:
- **No blend of either rung keeps the network legible.** P3 buys F at a nearly fixed price: every
  0.1 of mean F costs 0.12–0.16 of the first-pass rate, at all three strengths. The allowance is
  0.03, so even a quarter-strength P3 (−0.10) is three times over it. Under §8.1, **P2 and P3 do not
  ship**. A rejected fit goes to the profile's trash, not away; `PersonaliserTest` asserts it, and
  the tablet shows it: its `trash/` holds all four rejected fits (`rejected-style.bin` ×2,
  `rejected-adapters.bin` ×2) with their checkpoints, and `style/` is empty (read with the same code
  as a debug build, §20.13).
- **P2 is not monotonic in its strength.** At half strength it scores twice its full-strength F
  (0.153 against 0.078). The full style vector overshoots to where the base model never trained
  (§20.4.3), and the midpoint is closer to the user than either end.
- **The full-strength rows reproduce round 1 to every logged digit** (0.07455116 → 0.35730640 for
  P3), after a reinstall and from a fresh fit. The fit, the evaluation and the first-pass draw are
  deterministic, as §20.7.2 relies on.
- ⭐ **P1's selection is a maximum over noise, and the acceptance re-draw caught it.** Selection
  scores each candidate on a fresh draw of the network's ink: *no prime* scored 0.088 and the best of
  the fixed prime sets 0.140, which cleared *no prime* + δ. The acceptance check then draws again.
  There, *no prime* scored 0.046, the same set 0.072, a gain of +0.026 ± 0.021, at a first-pass cost
  of 0.075. The same configuration moved 0.04 between two draws. That is why `pairedGain` asks a
  rise to beat its own standard error and not δ alone (§20.4.1). **P1 stays off.**
- **The measurement** (2026-10-15 01:11, the F curve's second point, §20.3.3): assembly **0.433**,
  network **0.038**, over the same 10 held-out words; coverage 0.90; first pass 0.90 over 200 words;
  **primary: assembly**. Assembly moved 0.451 → 0.433 with no new word; that is the spread of its
  random variant choice over ten words.
- **Wall times** (from the log's timestamps):
  - the fits, as in round 1: P2 5.5 min (2.1 s an iteration), P3 4.9 min (1.9 s);
  - a rung's first decision about 30 s, because it evaluates what is in force as well; each further
    blend 12–13 s;
  - P1 62 s (36 s to select, 26 s to re-check);
  - the measurement 15 s.

  The round ran from 00:57 to 01:11: 14 minutes in three slices.

### 20.5 Switching (§8.4)

- **The policy** is `BackendPolicy.decide`, unit-tested (`BackendPolicyTest`, 7):
  - switch to the network when F_neural ≥ F_assembly − 0.05, F_neural ≥ 0.6 and its first-pass
    rate ≥ 0.9, over ≥ 200 gated words;
  - revert when any falls 0.05 past its threshold;
  - too little evidence changes nothing.
- **Per-character routing:** characters the network fails more than 20 % of the time, over at least
  5 words, go to assembly whenever assembly can write the word. ⚠️ Routing is at the **word**: a word
  holding a routed character is assembled whole. Splicing an assembled letter into a network word
  would need joins between the two backends' ink, and is not built.
- **On the tablet,** both rounds measured the same draw: a fixed seed, and no rung accepted. Eight
  characters are routed:

  | Character | Failed | Over |
  |---|---|---|
  | `(` | 3 | 7 words |
  | `)` | 4 | 17 |
  | `.` | 2 | 6 |
  | `L` | 2 | 5 |
  | `R` | 2 | 5 |
  | `W` | 2 | 5 |
  | `m` | 4 | 15 |
  | `y` | 2 | 8 |

  An earlier draft of this section named only the brackets. The decimal point is in the list:
  **numbers with a `.` go to assembly** whenever it can write them, which on this hand is every
  number (§20.6.1).
- **The setting:** *Auto / My letters / Model* on *My handwriting* (`ProfileState.backend`).
  - *My letters* is H4's behaviour: assembly first, the network for what assembly cannot write.
  - *Model* puts the network first and keeps the routing, because routing is about legibility, not
    style.
- **The measured numbers do not switch this profile:** F_neural 0.04 against assembly's 0.45, far
  from 0.6. The thresholds stand as §8.4 set them; nothing measured argues for moving them.
- **The switch, observed on a device** (`Test_Tablet`, debug build, its 19-word profile, a scratch
  note at the library root; the same phrase, *it is set*, which assembly can write):
  - *Model* selected: the panel said *"Writing with the model first (your choice)."*, and the dropped
    phrase was **15 strokes tagged `hw=…:n:a6a191cb`, none `:a:`**;
  - *Auto* selected: the preview logged `neural=1`, and the drop was **7 strokes `:a:-` and 13
    `:n:a6a191cb`, one group**. Two words were assembly's; the third was the network's, after
    assembly's rungs failed the gate (`attempts=4 neuralAttempts=2`);
  - `profile.json` read back `"backend":"MODEL"`, then `"AUTO"`.
- **The switch, observed on the tablet** (release build, the user's 196-word profile, a scratch note
  at the library root, since trashed; §20.13):
  - *Model*: preview `neural=3`; the drop `neural=3 model=a6a191cb neuralAttempts=3`, 11 strokes
    tagged `:n:a6a191cb`, 148 ms;
  - *Auto*: preview `neural=0`; the drop `neural=0 model=-`, 10 strokes tagged `:a:-`, 166 ms;
  - the writer logged the route in force: `primary=ASSEMBLY, avoidNeural=[(, ), ., L, R, W, m, y]`,
    no style vector, no adapters, `primed: false`.
- ⚠️ **Not observed:** *Auto* switching on its own measurement. No profile measured comes near it.

### 20.6 Maths

#### 20.6.1 What limits numbers now

- **On the tablet's hand:** every digit, `.`, `-`, `+`, `(` and `)` has a verified single-letter copy
  (`MathsReport`). So `0.0375`, `12.875`, `-0.25`, `9.81`, `2x+3` and `6.02` are all assembled from
  the user's own letters, by either route, with §18.10's clearance around points.
- `=`, `/` and `%` have **no usable** copy of their own: the maths quick-start prompts were not
  recorded on the tablet. One `=` was cut, from the harvested word `=27`, but that word's frame is
  wrong: its letters measure 5.7–8.6 x-heights tall, with no x-height letter to correct them, so
  `LetterFit` rightly keeps everything cut from it out of assembly. *My handwriting* nonetheless
  showed `= 1`, which is §20.13's fix. So every `=` comes from the network, whose v1 `=` carries
  stray marks and whose `%` reads as `°oo` (§18.10).
- The gate judges numbers and formulas on this hand (§20.2.2), on a biased control.

#### 20.6.2 What to do about it, in order of cost

1. **Record quick-start prompts 6 and 7** (`x = 0.25 + 1.75 - 3/8 = 1.625` and `(4.9 - 2.6) / 0.5 =
   4.6 or 46%`). That gives `=`, `/` and `%` verified copies, and gives numbers an unbiased control.
2. **The v2 base model** (§20.6.3), for symbols the user has not written: `× ÷ ± √ π ≤ ≥ ≠ ≈ ∞ °`,
   the Greek letters, `^ * < > [ ] { } | _ ~`.
3. **A maths layout** (the user's requirement, 2026-10-15: *"fractions and square roots, cube roots,
   brackets, stuff like that"*). Everything above writes maths **on one line**: `3/8`, `√(l/g)`,
   `x^2` with a caret. A stacked fraction, a radical whose bar runs over its radicand, a `3` in a
   cube root's notch, a raised exponent and a bracket as tall as the fraction inside it are **layout**,
   and no backend here does layout.
   - **Not the network's job.** It writes along a line, one character after the next. Teaching it
     two-dimensional maths would need two-dimensional handwritten maths to train on, and the one
     public corpus of it (MathWriting) is not licensed for commercial use (D13).
   - **The design:** a box layout that places pieces each backend already writes, in x-heights:
     numerator, denominator, radicand, index and exponent are each written as their own line, your
     letters first, the network for the rest. Then the structure is drawn as ink in the note's pen:
     the fraction bar; the radical as one stroke, a handwritten `√` (yours if you have written one,
     else the model's) with its last stroke carried on over the radicand; brackets stretched to the
     height of what they hold.
   - **It exists once already.** `:core:calc`'s textbook display has this exact structure:
     `Expr` runs with `Template`s (`FRACTION`, `MIXED_FRACTION`, `SQRT`, `CBRT`, `NTH_ROOT`, `POWER`,
     `ABS`, …) and a box layout (`FractionBox`, `RadicalBox` with an optional index, `ScriptBox`).
     Both are `internal` and measured in LCD pixels, so they are a design and an input model to share,
     not code to call as it is. Typing maths could reuse the calculator's keys and templates; a
     calculator result could then be written onto the page in the user's hand.
   - **v2 needs no change for it.** The layout asks the model only for single symbols and short
     runs. A cube root needs `√` and a small `3`, not a `∛` character, and a power never asks for
     `^`. What v2 is still for is a handwritten `√`, `[ ] { }` and the operators, in case the user has
     not written them.
   - **Built** (2026-10-15), and stopped for review: `docs/HANDWRITING-MATHS.md`. A *Maths* switch
     and eight keys in the Write panel; 19 of 20 test expressions written from the tablet's profile;
     walked on `Test_Tablet`. Not yet on the Galaxy Tab.

#### 20.6.3 The maths-weighted corpus (v2), prepared, not yet trained

Built by a delegated session, checked here: the tests ran, and the tarball was listed and hashed.
`tools/handwriting`; v1 is reproduced draw for draw, and a test pins it.

- **Alphabet:** 106 characters, v1's 79 plus `^*<>[]{}|_~×÷±√π°≤≥≠≈∞θΔμΩλ`.
- **New glyphs** come from Hershey `mathlow.jhf` and `greeks.jhf`. They were downloaded from the same
  repository as v1's Hershey files, under the same terms (`LicenseRef-Hershey`), with their sha256s
  in `fonts/LICENCES.json`. Every glyph was identified by rendering and looking. θ is a narrowed Θ,
  and ≈ is a doubled tilde.
- **Corpus:** 70,000 phrases from 5,000 writers; 10.19 M points; **49 % numeric or mathematical**.
  - The rarest new symbol (Δ) appears 2,034 times.
  - `.` appears 25,882 times (v1: 3,079) and `=` 9,292 (v1: 432).
  - Number forms covered: leading zeros, negatives, 4–5 decimal places, ×10^, e-notation,
    fractions, percentages, coordinates, spaced and glued equations, chemistry.
- **Fixed in v2 only,** so that v1 stays reproducible:
  - doubled strokes in Hershey `scripts`' digits and brackets;
  - EMS faces whose `×` is an `x`;
  - floating decimal points, and point gaps down to −0.11 x-heights (which made `2.5` read `25`);
  - looped corners in Relief (`4` as `9`);
  - old-style digits;
  - symbols sized to the writer's digits.
- **Licences:** export's provenance check passes. 13 sources: 7 EMS fonts and Relief under OFL-1.1,
  and 5 Hershey files.
- **Tests:** `tools/handwriting` 51/51 (28 old, 23 new), re-run here. The smoke run was 40 batches on
  the laptop's CPU; an epoch-1 export is 3.71 MB, about 3.36 MB compressed (estimate), against the
  3.5 MB budget.
- **The PC run:** `transfer/inkstone-hw-train-v2.tar.gz`, 74.9 MiB, sha256 `6ddac9e0…babf91f`. Run
  `bash train-v2-on-pc.sh`: 15 epochs from scratch, every epoch kept, an estimated ~3 min each.
- **The run** came back on 2026-10-15 (§20.6.4). What it owed:
  - choose an epoch by the gate, as §19.7 did;
  - Kotlin parity against new goldens;
  - F and first-pass against v1 on the user's profile;
  - the asset swap. A new base model sends every personalisation artefact to the trash (§4.4), and
    the ladder starts again on it.

#### 20.6.4 The v2 run, measured: **not shipped**

**The run.** The user's PC (CUDA), `train-v2-on-pc.sh`:
- 15 epochs of 2,079 batches, **177 s an epoch**, 47 minutes in all; 2,010,775 parameters;
- copied from `D:\runs\v2-maths-3x256` to `tools/handwriting/runs/v2-maths-3x256/`;
- every `epochNNN.pt` arrived whole. `checkpoint.pt`, the resume state, is **unreadable on D:\**
  (*"The file or directory is corrupted and unreadable"*), so `export.py` wrote no loss history into
  the files. Nothing else needs it.

The validation loss is best at **epoch 2** (−1.78 nats a point) and drifts up to −0.64 by epoch 15,
while the training loss falls to −3.69: the same divergence v1 showed (§19.7), which is why epochs
are chosen by the gate.

**The export.** Seven epochs (2, 3, 6, 9, 12, 13, 15), each 3,711,004 bytes. Every one passed
`export.py`'s commercial-licence check. Compressed, a file is about **3.45 MB** against §10's 3.5 MB
budget (v1: 3.28 MB): the 106-character alphabet costs 0.17 MB.

**The gate** (`NeuralGateReceiver`, `Test_Tablet`, debug build, real ML Kit, neatness 0.6, first-pass
rates, the same method as §19.7). The three sets:
- **letters:** §19.7's ten words;
- **numbers:** `2.5 0.0375 -3 46% 3/8 x=2 9.81 12.875 1.625 6.02`;
- **symbols:** twelve formulas using the new characters, such as `x≤3`, `2×3`, `a±b`, `π≈3.14`, `√2`.

"Exact" counts samples whose **top** reading was the label, because the gate's number rule passes
wrong digits (§21.4).

Six samples a word, unprimed:

| Model | Letters: gate / exact | Numbers: gate / exact | Symbols |
|---|---|---|---|
| **v1, epoch 15 (`a6a191cb`)** | **57 / 43** of 60 | 54 / 39 of 60 | cannot write them |
| v2 e2 (`df1bcbfc`) | 43 / 26 | 47 / 34 | 0 / 72 |
| v2 e3 (`fc26db20`) | 49 / 25 | 54 / 43 | 0 / 72 |
| v2 e6 (`044420aa`) | 50 / 35 | 55 / 40 | 0 / 72 |
| v2 e9 (`3555e0c6`) | 50 / 36 | 53 / 38 | 0 / 72 |
| v2 e12 (`046cbd96`) | 38 / 30 | 55 / 39 | 0 / 72 |
| v2 e13 (`fca44b5d`) | 45 / 35 | 55 / 41 | 0 / 72 |
| v2 e15 (`dd71c4e3`) | 44 / 32 | 53 / 37 | 0 / 72 |

Twelve samples a word, for v1 and the two best epochs:

| Model | Letters, unprimed: gate / exact | Numbers, unprimed: gate / exact | Letters, primed |
|---|---|---|---|
| **v1** | **112 / 83** of 120 | 111 / 77 | 102 |
| v2 e6 | 96 / 70 | 110 / 77 | 71 |
| v2 e9 | 95 / 69 | 109 / 82 | 88 |

- **v1's 112/120 repeats §19.7's own figure exactly**, on the same ten words: the method reproduces.
- **On letters v2 is worse at every epoch**, by 16 of 120 at its best: about three standard errors
  (z ≈ 3.0). The words it loses are `zebra` (5/12 against 12/12), `fix` (5 against 10) and `cab`
  (8 against 12). Half its corpus was maths, so it saw half as many words.
- **On numbers it is level**: 109–110 against 111, and 77–82 exact against 77.
- **Priming hurts v2 badly** (letters 71–88 primed against 95–96 unprimed). See below for why that
  matters on a model change.

**ML Kit cannot read a maths symbol.** Not one of 144 samples of the twelve symbol formulas passed at
either epoch, and the readings say why. The English model has no such character to answer with, and
it maps each shape to the nearest one it has:

| Written | Read as |
|---|---|
| `×` | `x` (*`2×3` → `2x3`, `2X3`*) |
| `÷` | `:` |
| `√` | `V`, `v` |
| `≤` | `<` |
| `≥` | `Z`, `2` |
| `≠` | `F`, `H` |
| `±` | `t`, `=` |
| `π` | `t`, `17` |
| `θ` | `0`, `6` |
| `μ` | `N` |
| `Δ` | `A` |

Several of those readings show the shape is right (a cross read as `x`).
- ML Kit offers **no maths model**. Its base models are the languages plus `zxx-Zsye-x-emoji`,
  `zxx-Zsym-x-autodraw` and `zxx-Zsym-x-shapes` (checked on
  developers.google.com/ml-kit/vision/digital-ink-recognition/base-models, 2026-10-15).
- So **no recogniser available to the app can check the network's symbols**. Under D9 and §8.2, a
  token the recogniser cannot judge is never the network's. v2's new characters therefore cannot
  reach a page however well it draws them, and a better model cannot fix that.

**Fidelity** (`FidelityReport`, the tablet's pulled profile).
- **F is not comparable across base models.** Its zero is the base model's own distance from the
  hand, so changing the model moves the zero. Under v2, even assembly's F fell from 0.588 to 0.000 on
  the same words.
- The comparable figure is that raw distance itself, **d_generic**: the model's unprimed writing
  against the user's held-out words. v2 is closer on 5 of 9 words; the medians are 0.845 against
  0.936. **Level**, on ten words.

**The maths sheet** (`MathsLayoutReport` with v2 e15, a reader that passes everything):
- all 20 expressions are written, the quadratic formula's `±` among them (3 of its 15 strokes);
- **v2's `=` sits on the fraction axis**, where v1's floats above it.

Neither helps in the app. The `±` cannot pass the real gate, and an `=` that lines up is not worth
16 letters in 120.

**Decision: v1 stays.**
- The bar set in advance was *at least as good as v1 on the gate and on F*. v2 is worse on letters,
  level on numbers and F, and its maths symbols cannot be read back.
- The asset, the goldens and every profile are untouched.
- The candidate `.ihwm` files stay in `runs/v2-maths-3x256/`, and on `Test_Tablet` in `files/debug/`
  as `v2e…`, beside v1's own candidates.

**What would get symbols onto a page**, in order of cost:
1. **The user's own copies.** A quick-start prompt of maths symbols gives them vouched single-letter
   units, which §8.2's strict route writes without asking a recogniser. That is the route D9 already
   allows. **Built the same day, at the user's request** (docs/HANDWRITING-MATHS.md §2.4): the eighth
   prompt is `± × ÷ √ π ≤ ≥ ≠ ≈ θ Δ °`, `GateCalibration.UNREADABLE` sends any token holding one down
   the strict route, and the Write panel has a key for each. Walked on `Test_Tablet`; on the Galaxy
   Tab, writing the prompt is the user's step.
2. **A gate rule for symbols**: accept a symbol when ML Kit reads it as its known lookalike (`×`→`x`,
   `√`→`V`, …). It would need §8.2's positive and negative controls first. A lookalike is a weak
   witness (any cross reads as `x`), and this is the user's call, not a default.
3. **A v3 with a lighter maths mix**, to keep v1's letters. Useful only alongside 1 or 2, since symbols
   still need something to check them.

**Found, and true of any base-model change.** `HandwritingWriter` honours a profile's P1 decision only
for the model it was decided on, and falls back to **priming** for a new one. Every measurement here
says priming lowers first-pass rates (§19.7, §20.4.2, and v2's letters above). So a model swap would
start every profile primed, at its worst, until a personalisation round re-decided. **Not changed**,
since no model is changing; a swap should first make the fallback *no prime*.

### 20.7 The personalisation worker

#### 20.7.1 Only while charging and idle

- `ensurePeriodicPersonalisation` sets `requiresCharging` and `requiresDeviceIdle`; nothing else in
  the app schedules the worker.
- On the tablet, `dumpsys jobscheduler` shows the job with **Required constraints: CHARGING IDLE**.
  While the tablet was in use, CHARGING was satisfied and IDLE was not, and the job did not run.
- The period is 15 minutes, WorkManager's least: a round is four or five five-minute slices, and the
  constraints, not the period, keep it off a tablet in use.
- The policy is `UPDATE`, so the change reached a tablet that already had the hourly job.

#### 20.7.2 Slices, checkpoints, and the loss bound

- A pass fits for at most **5 minutes**, then stops at a checkpoint. It also stops when WorkManager
  stops the job (`isStopped`), for example when the tablet is unplugged. The rest of WorkManager's ten
  minutes is for the evaluation a finished fit owes.
- **Checkpoints every 8 iterations** (`FitPlan.checkpointEvery`), written atomically (rule 10). They
  hold the optimiser's whole state, and the batch order is a function of (seed, epoch). So **a
  resumed fit ends bit for bit where an unbroken one does.** At the tablet's 1.9–2.1 s an iteration,
  8 iterations is **≤ 17 s of fitting**: that is the stated loss bound for a kill.
- **A finished fit keeps its checkpoint until its decision is recorded** (§20.8). A fit that finishes
  as its time runs out is decided by the next pass.
- **`CrashSafetyTest`, two new kills in `dev.inkstone:crashpoint`.** The production `Fitter`, the
  checkpoint writer `Personaliser.saveCheckpoint`, `AndroidFileSystem` and the shipped model fit P2's
  full backward pass:
  - `PERSONALISE`, killed between checkpoints as iteration 6 starts: the checkpoint on disk is
    iteration 4, **1 iteration lost** against a bound of 2 for that plan. Resumed, it ends with the
    unbroken fit's validation curve and the same style vector, bit for bit;
  - `PERSONALISE_WRITE`, killed **inside** the second checkpoint's atomic write: the first checkpoint
    decodes whole, and resumes to the same bit-for-bit result.
- **The suite:** **6/6** on `Test_Tablet`, installed with `adb install -r` and run with
  `am instrument`, 126 s. Adding two kills first made an *old* test fail: Android restarts a killed
  started service with a back-off that multiplies per crash, and the next scenario's start waited
  behind it for over 60 s. The victim now `stopSelf()`s before dying (§20.8).
- **Wall times, release build on the tablet:**
  - P2: 2.1 s an iteration, 27 s an epoch, 5.5 min for 12 epochs over 100 words;
  - P3 (rank 4): 1.9 s an iteration, 4.9 min;
  - a rung's decision: 30–35 s; P1: 36 s; the measurement: 16 s;
  - calibration: 2–3 s for 154 words.

  The laptop JVM is about 1.5× faster (§20.4.3).
- ⚠️ **A debug build cannot fit.** On the tablet it did not reach a checkpoint four iterations on in
  ten minutes, against 140 iterations in five on release. The device check therefore needs a
  release-build trigger, and the periodic job will not run *"before schedule"* even when JobScheduler
  forces it. `PersonaliseNowReceiver` is that trigger. It is guarded by `android.permission.DUMP`,
  which the shell holds and no installed app can be granted, so only a person at a computer can send
  it, exactly like `cmd jobscheduler run -f`. `--es from P2` starts a round at a stage.

### 20.8 Found while building, and fixed

1. **Calibration repeated every pass.** Staleness compared the profile against the calibrated classes'
   word counts, which leave out a class too small to calibrate. `GateCalibration.wordsRead` now
   records every word read. Caught on the tablet; `PersonaliserTest` holds it.
2. **A finished fit was lost if its decision was interrupted.** Its checkpoint went to the trash
   before the decision. It stays now until the stage edit, and a fit that ends at the budget is
   decided next pass. `PersonaliserTest` holds it.
3. **Cut letters carried stray ink** (`N·O2`, §20.2.3). They now get the stray-ink test.
4. **`CrashPointService`'s restart back-off** made six kills in a row time out (§20.7.2).
5. **The positive control was biased by construction** (§20.2.1). It is now measured on vouched words.
6. **A test of mine expected the gate to read words a scripted reader misreads.** The code was right:
   a class the recogniser cannot judge is never gated. The assertion now says so.
7. **The panel counted letters assembly cannot use** (found on the tablet, §20.13). `Coverage.letters`
   promised *"what assembly can use one letter at a time"* and counted every cut, including held-out
   words' and misframed words'. It counts `GlyphIndex`'s letters now. `RecutTest` holds it, and it
   was checked red against the old count (`{m=1, l=2, x=1, i=1}` for `{m=1, l=1}`).
8. **The routed characters were shown in capitals** (found on the tablet). The line went through
   `MicroLabel`, which upper-cases, so a routed `m` read as `M`. It is its own case-preserving line
   now, and says *"whenever they can write the word"*, not *"always"*.
9. **`spotlessCheck` was not re-run after the shrinkage test was added,** and that test broke it.
   §20.12's "green" predated it; the re-run is in §20.13.

### 20.9 Decisions made while building

1. **Acceptance uses every word no fit saw, and a paired mean that must beat δ and its own standard
   error** (§20.4.1), rather than §8.3's held-out median. There are 10 held-out words, and the
   network's median is 0 everywhere.
2. **The positive control is the vouched words** (§20.2.1).
3. **`PUNCTUATION` only for words** (§20.2.1).
4. **The strict route allows single letters and vouched units only** (§20.2.3).
5. **A round is P2 → P3 → P1 → measure.** P3 fits on whatever style is in force. P1 is decided last,
   because priming interacts with both.
6. **Shrinkage** (§20.4.1), after P3's tablet result: P3's gain in F is real, so it was worth
   finding a blend legibility allows, rather than throwing it away. None does on this hand (§20.4.4,
   round 2). Shrinkage stays, because it costs two evaluations and a later base model may have a
   blend that works.
7. **Routing is per word** (§20.5).
8. ***My letters* is H4's behaviour; *Model* keeps the routing** (§20.5).
9. **A new base model resets the switch** to assembly, since the evidence was the old model's.
10. **The worker runs every 15 minutes** under its constraints, not hourly (§20.7.1).
11. **An adb-only release trigger** (§20.7.2).
12. **The first-pass rate for §8.4** comes from 200 of the user's own words that the network may write.
    It is measured at each round, not logged from drops, because a log written from the editor path
    would add I/O where rule 7 forbids it.

### 20.10 Found, not fixed

- **The network is far from the user,** F 0.04 against assembly's 0.45, and the rung that closes the
  gap (P3) costs legibility. The likely cause is the base model's synthetic pretraining (§7.2, risk 3):
  adapters on one layer pull a font-trained network towards a hand it has no words for, and the result
  stops reading as letters. Shrinkage does not escape it: a quarter-strength P3 still costs 0.10 of
  the first-pass rate (§20.4.4). Options: a legibility term in P3's loss (a gate in the loop), a
  wider P3 (more layers), or real licensed data (§7.2). None was tried.
- **10 held-out words.** §8.3's F needs 50; the profile adds about one held-out word per ten
  harvested.
- **Numbers and formulas are calibrated on a biased control** (§20.2.2) until the maths prompts are
  recorded.
- **`=`, `/` and `%` exist only in the model** on this hand (§20.6.1).
- **Routing is at the word.**
- **Auto has not switched on a device.** No profile is close.
- **Earlier walks' scratch notes on `Test_Tablet`** (four *Untitled* notes at the library root) were
  left alone. This phase's own scratch note went to the local trash through *Move to trash* when the
  walk was done.
- **The F curve over time has two points so far.** Most of the curve is the historical
  re-measurement, not a record of passes.

### 20.11 Per claim

Buckets as §19.13: **automated** (a test asserts it), **real dependency** (run against the thing
itself), **device** (reached on `Test_Tablet` or the tablet).

| H5 claim | Automated | Real dependency | Device |
|---|---|---|---|
| Gate calibration: positive and negative controls, stored in `profile.json` | ✅ `GateCalibrationTest` (8): strictest rule, floor, safety, vouched control, JSON | ✅ Real ML Kit, 154 of the user's words (§20.2.2) | ✅ The tablet's release build; `profile.json` pulled and read |
| Classes the recogniser cannot judge are built from verified letters, laid out without overlap | ✅ `SwitchingTest`: strict units only, never overlapping, refused without a verified copy, vouched usable whole, never read back, never the network | ✅ `MathsReport` over the tablet's profile | ❌ No class is un-judgeable on this hand, so the route has not run on a device |
| Fidelity F for both backends over the held-out words | ✅ `FidelityTest` (6): zero self-distance, symmetry, ordering, dots cost DTW not chamfer, the score's clamp, a perfect writer scores 1 | ✅ The tablet's profile through the real network (`FidelityReport`, `LadderReport`) | ✅ The tablet's pass measured both (§20.3.3) |
| F tracked over time | ✅ `BackendPolicyTest` (the measure's JSON); `PersonaliserTest` (one point per round) | ✅ The historical curve (§20.3.3) | ✅ The tablet: two points in `profile.json`, both listed on *My handwriting* |
| P1 shown to help, or recorded as not | ✅ `PersonaliserTest` (P1 decided every round) | ✅ F on 43 unseen words (laptop) | ✅ Gate 112 → 102 (emulator); P1 decided off (tablet) |
| P2: backward pass gradient-checked against PyTorch | ✅ `TrainableNetworkGradientTest` (6), checked by two mutations | ✅ Goldens from PyTorch's autograd | — |
| P2 shown to help, or recorded as not | ✅ `FitterTest` (a fit lowers loss) | ✅ Laptop: F 0.053 → 0.097 | ✅ The tablet, two rounds: **not shipped** at any blend (first pass 0.26 / 0.71 / 0.83 at α = 1 / ½ / ¼); both fits found in its `trash/` |
| P3 shown to help, or recorded as not | ✅ `FitterTest` (incl. the shrinkage blend), `TrainableNetworkGradientTest` | ✅ Laptop: F mean → 0.245–0.264 | ✅ The tablet, two rounds: raises F (+0.28 / +0.12 / +0.08) and is **not shipped** at any blend (first pass 0.46 / 0.75 / 0.80) |
| Switching policy | ✅ `BackendPolicyTest` (7) | — | — |
| Per-character routing | ✅ `BackendPolicyTest`, `SwitchingTest` | — | ✅ The tablet: `policy.routed = "().LRWmy"` in `profile.json`; the writer logged the same `avoidNeural` set |
| Auto / My letters / Model | ✅ `BackendPolicyTest` (`route`) | — | ✅ `Test_Tablet` and the tablet: the setting read back from `profile.json` |
| A switch observed on a device | ✅ `SwitchingTest` | ✅ Real ML Kit gated every word | ✅ `Test_Tablet`: Model → 15 network strokes; Auto → 7 assembly + 13 network. ✅ **The tablet:** Model → 11 strokes `:n:a6a191cb`; Auto → 10 strokes `:a:-` |
| The worker runs only while charging and idle | — | — | ✅ `dumpsys jobscheduler`: CHARGING IDLE required; not run while in use |
| The worker checkpoints | ✅ `FitterTest`, `PersonaliserTest` | — | ✅ Every tablet slice paused at, and resumed from, a checkpoint |
| Killed mid-run, it resumes within a stated bound | ✅ `FitterTest`: bit for bit, ≤ one interval | ✅ `CrashSafetyTest` in a separate process, production classes | ✅ `Test_Tablet`, 6/6 |
| Platform-free; the iOS target | ✅ `checkCoreIsPlatformFree`; the iOS klib compiles, main and test | ❌ The framework link is the macOS CI job's | — |

### 20.12 The runs behind the table

Every test task ran with `--rerun` and `--no-build-cache`. None was up to date or from cache.

| Task | Tests | Run |
|---|---|---|
| `:core:handwriting:testDebugUnitTest` | 209, then **210** with §20.13's `Coverage` test | 2026-10-15; 157 before this phase. The three reports skip without a profile |
| `CrashSafetyTest` (`am instrument`, `Test_Tablet`) | 6 / 6 | 2026-10-15, 126 s |
| `tools/handwriting` (`python -m unittest`) | 51 / 51 | 2026-10-15 |
| `:app:testReleaseUnitTest` | 427, all passing (the shared checkout, with other sessions' work in it); this phase added no JVM test to `:app` | 2026-10-15 |

These are green, as re-run in §20.13 (an earlier "green" here predated the shrinkage test, which
broke `spotlessCheck`, §20.8 item 9):
- `:core:handwriting:spotlessCheck`, `detekt` and `checkCoreIsPlatformFree`;
- `compileKotlinIosSimulatorArm64`, `compileTestKotlinIosSimulatorArm64` and `compileKotlinIosArm64`
  for `:core:handwriting`.

The iOS **framework link** is SKIPPED on Windows and belongs to the macOS CI job. `./gradlew build` as a
whole is red for §16.4's reasons and was not run as a whole.

### 20.13 On the tablet itself (2026-10-15, 07:40–08:05)

The review asked what had been seen only on the emulator. SuperDisplay's adb (1.0.40) was first
fighting the SDK's for port 5037; the user stopped the service, and adb then held the tablet.

- **The build:** the H5 release build (00:56) for everything a user does. A debug build was installed
  over it (same key, same source: nothing outside `build/` had changed since 00:56) only to read
  private files, then the fixed release build went back on, compiled with `compile -m speed -f`.
- **The switch** (§20.5): *Model* gave 11 network strokes and *Auto* 10 of the user's own, in one
  scratch note at the library root. The note's bundle, read off the device, holds exactly
  `11 × hw=…:n:a6a191cb` and `10 × hw=…:a:-`. The setting was left on *Auto*, where the user had it.
  The scratch note went to the local trash with *Move to trash* (`files/trash/1792043875952-01a13e15…`);
  the note count went 42 → 41, so nothing else moved.
- **`profile.json`, read off the device:** `backend AUTO`; `policy` primary `ASSEMBLY`, routed
  `().LRWmy`; `personal` stage `DONE`, P1 `NONE` decided, no style, no adapters, fitted on 183;
  two quality points whose per-character failure arrays are identical (the same draw, §20.5).
- **The trash:** both rounds' rejected style vectors and adapters, with their checkpoints; `style/`
  empty.
- **Found and fixed** (§20.8 items 7–9): the panel's letter counts, the capitalised routed
  characters, and the formatting. After the fix the tablet's panel shows no `=`, and the counts drop
  to what assembly can use: digit `0` 10 → 2, `2` 24 → 15, `-` 12 → 5, `.` 2 → 1, and `B`, `F`, `P`
  to 0. Every digit, `(`, `)`, `+`, `-` and `.` still has at least one.
- **The re-run:** `:core:handwriting:testDebugUnitTest --rerun --no-build-cache` **210 / 210**;
  `spotlessCheck`, `detekt`, `checkCoreIsPlatformFree`, `compileKotlinIosSimulatorArm64` and
  `compileTestKotlinIosSimulatorArm64` executed and green; `:app:assembleRelease` (lint-vital
  included) and `:app:assembleDebug` green. `:app`'s change is one private composable, so its check
  is the compile and the panel on the tablet; `:app:testReleaseUnitTest` was not re-run.

## 21. Phase 6: the visual harness and the per-claim table (H6, 2026-10-15)

### 21.0 The short version

- **The SVG contact sheet is a test that runs in every build** (`ContactSheetSvgTest`, 3 tests). It
  writes one sentence twelve times, per backend and per neatness, to
  `core/handwriting/build/reports/handwriting/contact-sheet.svg`. It draws from a fixture hand and the
  shipped model, so it needs no pulled data. The JDK's XML parser reads it back, not the code that wrote
  it.
- **The debug screen draws the same sheet through the real ink engine** (`HandwritingSheetActivity`). On
  `Test_Tablet` it was handed **the same ink as the SVG, point for point**: all six block fingerprints
  agree between the JVM and the emulator. Laid side by side, the two pictures agree row for row.
- **The gate has an instrumented test against the real ML Kit** (`GateMlKitTest`, 9 tests on
  `Test_Tablet`; one was ignored until §21.4.1). It draws on 64 samples of commercially licensed
  synthetic ink. Words behave as designed:
  - 37/40 pass under their own label;
  - 40/40 wrong labels and 20/20 scribbles are refused;
  - upside down, 0/40 pass.

  The synthesizer refused an illegible word **by name, through the real recogniser**: `music`, read as
  `paper`. §18.5 recorded that this had never been seen.
- ⚠️ **The real recogniser found a hole in the gate for numbers** (§21.4). The rule passes a word if its
  label is *"among ML Kit's five candidates"*, and ML Kit's candidates for a number are its
  digit-for-digit look-alikes. **One wrong digit passed 5 of 24 times, and a gained decimal point 6 of
  10.** A *lost* point is refused 10 of 10, so §18.10's failure is caught.
- ⭐ **Closed at the user's decision (2026-10-16, §21.4.1, §21.4.2):** a token with a digit is judged
  on its first reading that holds a digit.
  - On the fixture ink all 34 wrong numbers are refused, and right numbers fall from 20/24 to 18/24.
  - On the user's own writing (155 words), numbers fall from 14/15 to 13/15 and formulas from 23/24
    to 21/24. Both stay judgeable.
  - The first attempt held digits to the *top* reading. Maths showed it refused the user's lone `1`
    and `5`, which ML Kit reads as `l` and `s` first, so readings with no digit are now skipped.
- **§11's variation rule is measured, and only half of it holds unaided.**
  - Assembly is never a copy and never garbage.
  - The network's raw offer passes the ceiling at every neatness. The gate brings it back under at
    neatness 0.5 and above, as measured on the device, but not at 0.
  - On a real hand, an absolute ceiling cannot tell an honest variant of a short word from a scribble
    (§21.5).
- **§21.8 is the whole feature in one table:** every claim from H2 to H6, sorted by the evidence it
  reached.

### 21.1 What was built

- **`:core:handwriting`:**
  - `ContactSheet` (commonMain), §11's sheet defined once: the seeds, the style, the rows, a trusting
    reader for where ML Kit cannot run, and two measurements. `Block.fingerprint()` hashes every point
    at 0.01 pt, from each row's own origin. `Block.spread()` gives §11's variation as
    `Fidelity.distance` between copies of a word.
  - `ContactSheetSvgTest` (androidUnitTest), the SVG sheet and its assertions. With
    `INKSTONE_HW_PROFILE` set it draws a pulled profile instead, and reports rather than asserts.
- **`:app` debug:** `HandwritingSheetActivity`.
  - It is reached with `adb shell am start -n dev.inkstone/.app.debug.HandwritingSheetActivity [--ez
    trusting true] [--es sentence …] [--ei times n]`.
  - Each row takes exactly a drop's path: `HandwritingWriter.encode` (`TrajectoryInk`), then
    `PhraseOps.build`, then the fold, then `InkPageThumbnailRenderer`, whose `PagePainter` is the
    editor's.
  - It writes `files/debug/hw-sheet/<backend>-<neatness>.webp` and logs one line per block under
    `HandwritingSheet`.
- **`:app` androidTest:** `GateMlKitTest`, and its ink, `assets/handwriting/gate-fixtures.json`
  (71 KB).
- **`tools/handwriting`:** `gatefixtures.py` writes that file, from 11 licensed faces, two writers per
  text. Two new tests check it:
  - every source is on the commercial allow-list;
  - the committed file is exactly what the generator writes.

### 21.2 The SVG contact sheet (JVM)

**What it asserts, on the fixture hand** (HandFixtures' `n u m l i x`, *"minimum mix in null"*) **and
the shipped model:**
- six blocks of twelve rows;
- every row complete, each word by its block's backend;
- one `<polyline>` per pen-down, every point on the sheet;
- a caption per row.

The reader agrees with everything (`ContactSheet.trusting`), so the sheet shows what each backend
*offers* the gate.

**Variation (§11), in x-heights, as `Fidelity.distance` between copies of one word in two rows** (66
pairs per word):

| Block | min | p50 | p95 | max |
|---|---|---|---|---|
| assembly, neatness 0 | 0.03 | 0.11 | 0.31 | 0.37 |
| assembly, 0.5 | 0.02 | 0.08 | 0.26 | 0.31 |
| assembly, 1 | 0.013 | 0.04 | 0.14 | 0.19 |
| network, 0 | 0.20 | 1.01 | 4.09 | **5.03** |
| network, 0.5 | 0.07 | 0.63 | 2.63 | **4.10** |
| network, 1 | 0.06 | 0.44 | 1.09 | **1.93** |

- **The floor is 0.005.** A phrase written twice with one seed measures 0, which shows the floor can
  fail. The closest honest pair was 0.013, an `in` at neatness 1.
- **The ceiling is 1.0, asserted on assembly only.** A scribble over `minimum`'s own box measures
  1.49, which shows the ceiling can fail.
- **The network's raw offer is over the ceiling at every neatness.** The cause is stray marks a
  word-length away, which cost DTW their whole pen-up path. The rendered sheet shows them (§19.8's
  marks).
- Six blocks take about 45 s to write on the laptop's JVM, a third of that through the shipped model.

**On the emulator's profile** (19 words, pulled to the scratchpad, never into the repo), *"it is not a
cation"*:
- assembly's spread reaches **1.07–1.15** at every neatness: two of the user's own `it`s, one with a
  `t` about twice the height of the other;
- a scribble over `it` measures **0.96**.

So the ceiling is asserted on the fixture hand only (§21.5).

### 21.3 The debug screen, through the real ink engine (`Test_Tablet`, debug build)

**The same ink, two renderers.**
- **The run:** the trusting mode on the emulator, and the JVM test on that emulator's pulled profile.
- **The fingerprints:** every one of the six blocks agrees:

  | Block | Fingerprint |
  |---|---|
  | assembly, 0 | `047c26469a30e515` |
  | assembly, 0.5 | `e8c8938b3976a134` |
  | assembly, 1 | `ac43a744c3f276b4` |
  | network, 0 | `5940b76740c370e8` |
  | network, 0.5 | `a00c87e8bd751a6f` |
  | network, 1 | `4d6c1c926ecf19a3` |

- **The network's ink matches too.** It was computed on ART on the device and on HotSpot on the
  laptop, and it agrees to 0.01 pt at every point.
- **The pictures agree.** The device's WebP and the SVG were laid side by side for two blocks, assembly
  and the network at neatness 0.5. They agree row for row: the same words, positions and strokes. One
  is drawn by `androidx.ink`'s brush, the other by polylines.
- **A first attempt** hashed page positions and disagreed everywhere, because the two sides stack
  blocks differently. The spreads agreed to two decimals all the same, which is what said the ink was
  the same. Hashing each row from its own origin fixed it.
- **The renderer is a software canvas,** so CLAUDE.md's x86_64 front-buffer garbage does not apply to
  it.

**Gated: real ML Kit, with the profile's calibration** (what a drop would draw):

| Block | Words written | Tries the read-back took | Time (emulator, debug) | Spread after the gate (p95 / max) |
|---|---|---|---|---|
| assembly, 0 / 0.5 / 1 | 36 of 60 each | 36 (every word first time) | 3.0–3.7 s | unchanged: same fingerprints as trusting |
| network, 0 | 60 of 60 | 71 | 30.7 s | 1.56 / **2.46** |
| network, 0.5 | 60 of 60 | 62 | 29.5 s | 0.67 / 0.96 |
| network, 1 | 60 of 60 | 64 | 30.8 s | 0.40 / 0.93 |

- Assembly's 24 refusals are `not` and `cation`, named: this profile has no `n` and no `c`.
- At the default neatness and above, what the gate lets through stays under §11's ceiling. At 0 it
  does not.
- **What the sheet shows that the gate does not catch.** In most rows, the network's `not` reads to a
  person as *"hot"*, and its `cation` as *"Calion"*. Asked directly, ML Kit reads them as written:
  `NeuralGateReceiver`, `not` ×5 at neatness 0.5, passed 5/5, read `[not, hot]`, `[not, nöt]`, … (§21.5).

### 21.4 The gate against the real ML Kit (`GateMlKitTest`, `Test_Tablet`)

**The ink.** 64 samples: 20 words and 12 numbers, each by two synthetic writers in different licensed
faces (§7.2). It is read at the gate's own 5.5 pt, through the production `MlKitInkReader` and
`ReliabilityGate`, uncalibrated (`CASE_ALIKE`). Every reading is logged under `GateMlKit`.

| Test | Result |
|---|---|
| Words under their own label | **37/40** pass (floor 0.8). The three misses: a `salt` read as `call`, an `energy` as `everdy`, a `house` as `howe` |
| Words under another word's label (±1 letter) | **40/40** refused |
| Scribbles labelled as words | **20/20** refused |
| The same words upside down | **0/40** pass, against 37 upright. So passing is the ink's doing |
| Numbers under their own label | **20/24** pass. **18/24** since §21.4.1 |
| Ink that **lost** its point (`25` labelled `2.5`) | **10/10** refused |
| No model (`fr-FR`, not downloaded) | `Unavailable(MODEL_NOT_DOWNLOADED)`; the synthesizer refuses every word and draws nothing |
| The synthesizer, whose only `music` is `paper`'s ink | `water` written in 1 attempt; `music` **refused as illegible, read as `paper`**, and only `water` drawn |
| ⚠️ One wrong digit, or a **gained** point | **11 of 34 passed**, `@Ignore`d (below). **34/34 refused** since §21.4.1 |

**The finding** (2026-10-15, under the rule then in force).
- **The rule.** A word passes if its label is among the recogniser's five candidates (k = 5, §8.2). For
  a number, ML Kit's other candidates are its digit-for-digit neighbours. `9.81`'s ink reads
  `[9.81, 0.81, 1.81, 2.81, 981]`.
- **What passed.** One wrong digit passed **5 of 24** times:
  - `9.81` as `0.81`, twice;
  - `25` as `35`;
  - `12875` as `22875`;
  - `1625` as `2625`.

  A gained point passed **6 of 10**: `2.5` as `25`, `9.81` as `981` twice, `12.875` as `12875`, and
  `1.625` as `1625` twice.
- **What it means in the app.** If generated ink for the typed `0.81` comes out reading `9.81`, the
  gate passes it, and a different number is on the page. D9 says that must not happen. A gained point
  is the network's misplaced mark (§19.8) landing between two digits.
- **Why the tablet's calibration missed it.** §20.2.2 measured one-edit refusal at **15/15**, on the
  user's own numbers, which are legible and few. This ink, and this many numbers, are what showed it.
- **The measured fix.** Hold a token with a digit to the **top** candidate, ignoring spaces.
  - On this ink it refuses all 34 of the wrong labels.
  - It costs **2 of the 24** right ones: `3.14` read first as `3.19`, and `1625` as `162`.
  - A refused number goes to the next rung and then to the panel, so the cost is a refusal, never a
    wrong page.
- **Not made in H6.** It changes what the gate lets onto a page, and numbers are this user's priority,
  so the call was the user's. `aWrongDigitOrAGainedPointIsRefused` was written and ignored, as the
  regression test for the change.

#### 21.4.1 The fix, made (2026-10-16)

⚠️ **Refined the same day by §21.4.2.** Everything here still holds except which reading a token with
a digit is judged on: its first reading that *holds a digit*, not its top one.

**The decision.** The user was asked whether to make the trade. The choice was: digits held to the
top reading, for numbers and formulas alike, with the cost measured on the tablet's release build.

**What changed** (`ReliabilityGate.kt`, `GateCalibration.kt`, `Personaliser.kt`):
- `GateRule.passes` judges a label with a digit (`GateRule.heldToTop`, now `digitBearing`) against
  the **first reading alone**, under every rule. Spaces are still ignored (`SPACING`), and a formula's letters keep their
  case rule (`CASE_ALIKE`). A word with no digit may still be any of the five.
- `GateRule.passesAmong` is the old test. Only the calibration's report asks it.
- **Calibration measures through `passes`**, so a class with digits is measured exactly as the gate
  will judge it. Its rule and its judgeability fall with what the top-reading rule costs, and below
  `FLOOR` the class goes to verified letters (§20.2.3), as §8.2 says. `PUNCTUATION` is still never
  offered to it.
- Each class also stores `positiveAmongCandidates`, its pass rate if any of the five counted. For a
  class with digits, the gap between the two rates is the cost on that hand. It is reported, not
  used as a threshold.
- **`GateCalibration.ruleVersion` is 2.** H5's `profile.json` has no version, so it reads as 1, and
  the next personalisation pass measures it again even when no word was added. Until then its
  stored rules apply, read the new way. That can only refuse more, never pass more.
- The pass logs both rates for each class's chosen rule, over its control:
  `calibrated: NUMBER=CASE_ALIKE/true 87% (93% among candidates) of 15, biased`. The build measured
  on the tablet printed the class's size there (`of 115` for words, whose control is the 29 vouched).
  The control is named since.
- `GateMlKitTest.aWrongDigitOrAGainedPointIsRefused` is no longer ignored. The numbers test has its
  own floor (`NUMBER_FLOOR`, 0.6), because 18/24 is under the words' 0.8.

**On the fixture ink** (`GateMlKitTest`, `Test_Tablet`, debug build, real ML Kit; 9 tests, 9 passing,
11.6 s):

| | Before (2026-10-15) | After (2026-10-16) |
|---|---|---|
| One wrong digit, or a gained point | 11 of 34 passed | **34/34 refused** |
| A lost point | 10/10 refused | 10/10 refused |
| Numbers under their own label | 20/24 | **18/24**. `3.14` read first as `3.19`; `1625` as `162` |
| Words under their own label; wrong labels; upside down | 37/40; 40/40; 0/40 | unchanged |

The other four were refused under the old rule too, because none of their five readings is the
number. Their top readings were:
- `2.5` as `..`;
- `981` as `Tel`;
- `314` as `BA4`;
- `46` as `16`.

**On the user's own writing** (the Galaxy Tab, debug-key-signed release build installed with the
user's go-ahead, 2026-10-16 06:15; real ML Kit; the profile's 155 words; one
`PersonaliseNowReceiver` pass, which recalibrated because of the version and then had nothing to fit:
`pass: Idle in 14556 ms`). Both rates come from the same readings, so the gap is the cost and nothing
else:

| Class | Words | Control | Among the five (old) | Top reading (now) | Rule | Judgeable |
|---|---|---|---|---|---|---|
| Words | 115 | 29 vouched | 97 % | 97 % | `PUNCTUATION` (unchanged) | ✅ |
| Formulas | 24 | all (biased) | 96 % (23/24) | **83 % (20/24)** | `CASE_ALIKE`, was `EXACT` | ✅, 0.03 above `FLOOR` |
| Numbers | 15 | all (biased) | 93 % (14/15) | **87 % (13/15)** | `CASE_ALIKE` (unchanged) | ✅ |

- **The rates match H5's.** The "among the five" column reproduces §20.2.2: 0.958 for formulas under
  `CASE_ALIKE`, 0.933 for numbers and 0.966 for words. The tablet reads this hand as it did on
  2026-10-15, and the drop is the rule's doing.
- **Formulas pay more than numbers here:** 3 of 24 refused, where the fixture's numbers lost 2 of 24.
  Which three cannot be read off a release build's log; it would take a debug build and `run-as`.
- ⚠️ **Formulas are now 0.03 above `FLOOR`.** One more formula of the user's reading wrong at the top
  would make the class unjudgeable. Formulas would then be written only from verified letters and
  never read back (§20.2.3). That is §8.2's design working, not a fault, but it is close.
- **The controls are still biased.** Numbers have 1 vouched word and formulas 2. Recording the quick
  start's maths prompts (§18.10) would make these unbiased.
- **What a refusal costs at drop time.** A word that fails is written again (up to three varied tries,
  then one with no variation), then by the next backend, and only then refused by name. So a 13 %
  first-reading miss on the user's own formulas is a ceiling on the cost, not the cost.

#### 21.4.2 The rule refined: the first reading that holds a digit (2026-10-16)

**Why.** Maths was measured through the real gate under §21.4.1's rule (docs/HANDWRITING-MATHS.md
§5.4). On the tablet's profile, most of the cost was a digit ML Kit reads as a *letter* first:
- the user's lone `1` reads `[l, (, I, i, 1]`;
- their `5` reads `[s, S, 5, g, 9]`.

A digit read as a letter is never a wrong number on a page. But the top-reading rule refused it, and
the retries often handed the digit to the model. A candidate rule skips readings with no digit at
all, then compares the label with the first that has one. It was measured, shown to the user, and
**adopted at their decision**.

**What changed:**
- `GateRule.digitReading(readings)` is the first reading that holds a digit, or nothing.
  `GateRule.passes` judges a `digitBearing` label (`heldToTop`, renamed) against that one reading under
  the class's rule. A token with a digit that reads only as letters is not confirmed.
- **`GateCalibration.ruleVersion` is 3**, so a calibration made under §21.4.1's rule is measured again
  on the next pass.
- `MathsGateReceiver` scores every reading three ways: the gate, the top reading alone, and any of
  five.
- Tests: `GateCalibrationTest` adds the user's own `1` and `5` readings, `x-3` read as `7-3`, `-4ac`
  read as `_4ac`, and a digit with no reading that holds one. `HandwritingSynthesizerTest` adds *a
  number read first as letters is written first time*.

**On H6's fixture ink** (`GateMlKitTest`, `Test_Tablet`, cold-booted, debug build, real ML Kit; 9 of 9
passing, 116 s): **34/34** wrong digits and gained points refused, **10/10** lost points refused,
**18/24** numbers under their own label, and the words unchanged. This is exactly what was computed
beforehand from the logged readings: a fixture number's look-alikes are digits, so its first reading
with a digit is its top reading.

**On the user's own writing** (the tablet's profile pulled on 2026-10-14, read on `Test_Tablet`
exactly as `GateCalibrator` reads it, 155 words):

| Class | Control | Any of five (H5) | Top reading (§21.4.1) | First reading with a digit (now) | Rule | Judgeable |
|---|---|---|---|---|---|---|
| Words | 29 vouched | 28/29 | 28/29 | 28/29 | `PUNCTUATION` | ✅ |
| Formulas | 24 (biased) | 23/24 | 20/24 | **21/24** | `CASE_ALIKE` | ✅, 0.075 above `FLOOR` |
| Numbers | 15 (biased) | 14/15 | 13/15 | **13/15** | `CASE_ALIKE` | ✅ |

- **The emulator reads this hand as the tablet does.** The top-reading column is what the tablet's
  release build logged at 06:15 for the same 155 words (§21.4.1), and the any-of-five column is
  §20.2.2's.
- **Maths on the same profile** (docs/HANDWRITING-MATHS.md §5.4): 56 of 63 expressions written, the
  same as the old rule and one more than the top-reading rule. 44 of 63 were written first time,
  against 35 under the top-reading rule and 45 under the old one.
- **On the tablet itself** (installed with the user's go-ahead, 2026-10-16 10:40:41: a debug-key-signed
  release build; one `PersonaliseNowReceiver` pass, which recalibrated from version 2 to 3, then
  `pass: Idle in 14186 ms`):

  `calibrated: WORD=PUNCTUATION/true 97% (97% among candidates) of 29 vouched,
  FORMULA=CASE_ALIKE/true 88% (96% among candidates) of 24, biased,
  NUMBER=CASE_ALIKE/true 87% (93% among candidates) of 15, biased over 155 words`

  That is 21/24 formulas and 13/15 numbers: **exactly the emulator's figures** for the pulled profile.
  Every class is judgeable, and formulas are 0.075 above `FLOOR`. The tablet's `profile.json` now holds
  a version-3 calibration.

### 21.5 Found, not fixed

- ~~**The gate passes a wrong digit or a gained point**~~ (§21.4). **Fixed 2026-10-16, §21.4.1 and
  §21.4.2.**
- **A cut digit is still checked against all five readings.** `Harvester.cut` keeps a cut letter when
  its text is any of the candidates for its cluster (`readsAs`). This matters only for the strict
  route, which builds a class the recogniser cannot judge from cut letters and never reads it back
  (§20.2.3). Through the gate, a number assembled from such a digit is judged on its first reading
  with a digit, like any other. Found while making §21.4.1; not changed, because it changes what the
  harvester learns.
- ~~**The maths numbers predate §21.4.1.**~~ Re-measured through the real gate under both rules
  (docs/HANDWRITING-MATHS.md §5.4). That measurement is what produced §21.4.2.
- **A low minus reads as an underscore** (`x-3` as `x_3`, `-4ac` as `_4ac`, on the tablet's profile).
  The rule refuses it because that reading holds a digit and is not the label, as the old rule did not.
  It costs one expression in 63 a first-time write on that profile. Not changed: an underscore is a
  different character, and telling the two apart is a question about the ink, not the rule.
- **Nothing checks a network letter's shape.** Its `n` grows an ascender and reads to a person as `h`,
  so *"hot"* passes as `not`. Its t-bar goes missing, so *"Calion"* passes as `cation`. ML Kit reads
  both as the word, so the gate cannot see them. `LetterFit` (§18.7) judges assembly's cut letters
  only. This is §19.10's *"the gate reads words, not marks"*, now seen for a whole letter and drawn on
  the sheet.
- **An absolute variation ceiling is the wrong instrument on a real hand.** On the emulator profile,
  two honest `it`s are 1.15 apart, more than a scribble over `it` (0.96). DTW charges a different
  stroke order (the dot before or after the bar) its whole pen-up path. A ceiling relative to the
  hand's own d_self (§20.3.1) would separate them. Not built: §11 asks for a bound, and on the fixture
  hand the absolute one is a sound bound.
- **The network's raw spread is over the ceiling at every neatness, and after the gate still is at
  neatness 0** (max 2.46). The default is 0.5.
- **`:app`'s detekt and spotless are red on files H5 changed:** `CrashSafetyTest`, `CrashPointService`,
  `PersonaliseCrash`, `HandwritingScreen` and `HandwritingWriter` (long lines). §20.12 claimed green for
  `:core:handwriting` only. Nothing this phase wrote is among the findings. They were left alone because
  they are another phase's files and nothing here depends on them.
- **Debris in the tree.** `core/handwriting/src/120` (143 KB) and
  `core/handwriting/src/commonMain/kotlin/dev/inkstone/core/handwriting/120` hold grep output, with
  timestamps older than this session. They match the ruflo hook's known debris pattern. They are not
  compiled. Not deleted, since they are not this phase's.

### 21.6 Decisions made while building

1. **One definition of the sheet, in `commonMain`** (`ContactSheet`). "The same sheet" on two renderers
   is true by construction, and the fingerprint proves it rather than asserting it.
2. **The gate's ink is font-derived synthetic handwriting.** It is not the user's, which may never be
   committed (§9). It is not the model's either, because then the witness would move whenever the
   model does. It is generated by a committed script, and a test pins it to that script.
3. **The trusting mode drops the profile's calibration**, so that it hands the renderers exactly the
   JVM's ink. The gated mode keeps the calibration.
4. **Variation bounds are asserted where they are true and reported where they are not.** The floor is
   asserted on every block; the ceiling on assembly, on the fixture hand. The rest is printed on every
   run, and the device's gated spread is logged.
5. **A known hole is an ignored test, not a red one, and not a test of the bug.** It names its section
   and its numbers.

### 21.7 Per claim: H6

| H6 claim | Written | Automated | Real dependency | Device |
|---|---|---|---|---|
| The SVG sheet test: one sentence, 12 times, per backend and neatness, in `build/reports/` | ✅ | ✅ `ContactSheetSvgTest` (3), in every `:core:handwriting` test run | ✅ The shipped model, under `LicencePolicy.RELEASE`; the JDK's DOM parser reads the SVG back | — |
| The debug screen, through the real ink engine | ✅ | — | ✅ `TrajectoryInk` → `PhraseOps` → fold → `PagePainter`, the editor's path | ✅ `Test_Tablet`: both modes walked; 6/6 fingerprints equal to the SVG's; two blocks (assembly and network at 0.5) compared side by side with the SVG |
| The instrumented ML Kit gate test | ✅ | ✅ `GateMlKitTest` (9: 8 run, 1 ignored; all 9 run since §21.4.1) | ✅ Real ML Kit 19.0.0, the `en-US` model | ✅ `am instrument` on `Test_Tablet`, after `adb install -r`: 40 s, and 233 s on the first run after a reinstall. 9/9 in 11.6 s on 2026-10-16 |
| §11's variation: never a copy, never garbage | ✅ | ✅ The floor on every block; the ceiling on assembly (fixture hand) | ✅ The gated spread on the device: the network is under the ceiling at neatness ≥ 0.5 | ✅ `Test_Tablet` |
| The per-claim table | ✅ §21.8 | — | — | — |

**Not on the tablet.** It was not connected. It runs a release build, which has no debug screen and no
test APK, and putting a debug build on it needs the user's go-ahead. Nothing in H6 needs the tablet's
hand: the fixtures stand in for it.

### 21.8 The whole feature, claim by claim

The buckets are CLAUDE.md's:
- **written:** the code exists;
- **automated:** a test asserts it;
- **real dependency:** checked against the thing itself (ML Kit, PyTorch, MuPDF, a real file), not a
  fake;
- **device:** reached the way a user reaches it, on `Test_Tablet` (*emu*) or the Galaxy Tab
  (*tab*).

Each cell cites the section that holds the evidence. ❌ marks a bucket that is owed; — marks one that
does not apply.

| Claim | Written | Automated | Real dependency | Device |
|---|---|---|---|---|
| **H2: learning the hand** | | | | |
| `:core:handwriting` is platform-free; the iOS target | ✅ | ✅ `checkCoreIsPlatformFree`, iOS klib compiles (§21.9) | ❌ The framework link is the macOS CI job's | — |
| A stylus stroke is in the pen ledger; a ruled one is marked | ✅ | ✅ `PenLedgerTest` | — | ✅ emu (§16.1) |
| Undo/redo, duplicates, Elements and generated ink stay out of the ledger | ✅ | ✅ `NoteEditorHandwritingTest` (`GENERATED`) | — | ✅ emu (§16.1, §18.3) |
| Merged and `addAll` strokes stay out of the ledger | ✅ by construction | ❌ | ❌ | ❌ Needs two peers and an HTTPS Nextcloud |
| Each stroke keeps its own start, ruler flag and pen kind | ✅ | ✅ `StrokeStartsTest` | — | ✅ emu (§16.1) |
| Codec round trip, refusal of damage, quantisation bound | ✅ | ✅ `TrajectoryCodecTest`, `WordSampleCodecTest` | — | ✅ emu: shards read back (§16.1) |
| Harvesting: agreement, steadiness, cuts, duplicates counted once | ✅ | ✅ `HarvesterTest`, whose fake reader is more generous than ML Kit | ✅ Real ML Kit in every harvest (§16.5, §17) | ✅ emu (§16.5, §17) |
| Normalisation: baseline, x-height, slant | ✅ | ✅ `LineStyleTest` (synthetic) | ✅ Real words, looked at (§16.5) | ✅ emu, *My handwriting* |
| Corrections survive a restart and a lost database; the old file goes to `trash/` | ✅ | ✅ `ProfileStoreTest` | — | ✅ emu (§16.1) |
| A purged note takes its samples with it; a lost database does not | ✅ | ✅ `DeparturesTest` (the decision) | — | ✅ emu: `withdrawn=0` after a rebuilt database (§16.1) |
| The learner worker runs charging and idle, and fires | ✅ | — | — | ✅ emu (§16.1). ❌ tab: never observed firing. By §18.7's and §18.10's counts the tablet's words not from the quick start went from 136 to 157; whether the worker or *Learn now* added them was not recorded |
| *My handwriting* is reachable and shows coverage; corrections from it | ✅ | ✅ `RecutTest` (coverage counts) | — | ✅ emu and tab (§16.1, §20.13) |
| **H3: assembly and the Write panel** | | | | |
| One sentence twice is two different phrases (§11's floor) | ✅ | ✅ `HandwritingSynthesizerTest`, `GlyphAssemblyTest`, `ContactSheetSvgTest` | ✅ The device sheet, real ML Kit (§21.3) | ✅ emu: *Write again* (§18.3); the debug screen |
| …and never garbage (§11's ceiling) | ✅ | ✅ Assembly, fixture hand (§21.2) | ✅ The network after the gate, neatness ≥ 0.5 (§21.3) | ⚠️ emu: on a real hand, unaided, the absolute ceiling does not separate (§21.5) |
| One undo removes a phrase | ✅ | ✅ `NoteEditorHandwritingTest` | — | ✅ emu (§18.3) |
| A tap selects all of it; it moves as one | ✅ | ✅ `PhraseOpsTest` | — | ✅ emu (§18.3) |
| It erases partially, like ink | ✅ | by construction | — | ✅ emu (§18.3) |
| It publishes as ink a non-PdfBox viewer draws; its words are found | ✅ | — | ✅ MuPDF, poppler (§18.3) | ✅ emu (§18.3) |
| Never in the ledger or the dataset | ✅ | ✅ `NoteEditorHandwritingTest`, `PlacementTest` | — | ✅ emu: `GENERATED=8` skipped (§18.3) |
| Text box → handwriting, and one undo brings the box back | ✅ | ✅ | — | ✅ emu (§18.3) |
| *Write again* and *Edit text* | ✅ | ✅ | — | ✅ emu (§18.3) |
| The drag onto the page | ✅ | — | — | ✅ emu: `input swipe` (§19.9). ✅ tab: by stylus tap only. ❌ tab: a finger drag was not tried (§19.10) |
| Joined pairs come out joined, lifted ones apart | ✅ | ✅ `GlyphAssemblyTest` (fixtures) | ✅ The joins sheet from the emu profile (§18.3) | ✅ tab: a bridge was drawn; not judged on its own |
| D15: the page's pen, size, slant, pitch and margin; the ruling, then the profile | ✅ | ✅ `PlacementTest`, `SheetFixesTest`, `FrameCheckTest` | ✅ The physics note, measured (§18.3) | ✅ emu and tab (§18.3, §18.7) |
| The quick start records `CONFIRMED` samples; undo, redo, erase | ✅ | ✅ `QuickStartTest`, `PromptInkTest` | ✅ `QuickStartPenInputTest` 7/7, instrumented (§18.9) | ✅ emu (§18.9). ❌ tab: two-finger undo and the barrel button |
| Every word passes the gate; a word nothing can write is refused by name | ✅ | ✅ `HandwritingSynthesizerTest` | ✅ **`GateMlKitTest`: an illegible refusal, with what it was read as, from the real recogniser (new, §21.4)** | ✅ emu and tab: *Missing letters* on screen. ❌ An illegible refusal through the panel's UI |
| D11: a signature is refused | ✅ | ✅ `SignatureRuleTest`, `HandwritingSynthesizerTest` | — | ❌ Not walked |
| `gfxinfo` for a 40-character drop | ✅ | — | — | ✅ emu and tab (§18.3, §18.7) |
| One full airplane-mode pass | ✅ | — | — | ✅ emu (§18.3); H6's walk was in airplane mode too |
| Numbers: decimal points keep clear; subscripts are not single letters | ✅ | ✅ `NumbersTest` | ✅ The maths sheet (§18.10) | ❌ tab: not looked at (§18.10) |
| **H4: the model** | | | | |
| The 4.0 spike was recorded before any inference code | ✅ | — | — | ✅ tab (§19.0) |
| The corpus comes from licensed fonts alone; an unlisted font is refused | ✅ | ✅ `test_pipeline.py` | ✅ 48,000 phrases, 11 faces (§19.3) | — |
| A release build refuses a non-commercial model | ✅ | ✅ `WeightsFileTest`, `ShippedModelTest` | — | ✅ tab: `policy=RELEASE nonCommercial=[]` |
| A corrupt asset turns the network off, and assembly carries on | ✅ | ✅ `NeuralBackendTest` | — | ✅ emu (§19.9) |
| Kotlin agrees with PyTorch to 1e-4 | ✅ | ✅ `SynthesisNetworkGoldenTest`, `ShippedModelTest` | ✅ Goldens written by PyTorch | — |
| The network writes what assembly cannot, through the gate | ✅ | ✅ `NeuralBackendTest` | ✅ Real ML Kit (§19.7, §21.3) | ✅ emu and tab (§19.9) |
| Neural strokes are tagged `hw=…:n:<sha8>` | ✅ | ✅ `PhraseOpsTest`, `NeuralBackendTest` | — | ✅ emu and tab (§19.9, §20.13) |
| Pressure and tilt come from the user's own fit | ✅ | ✅ `PenDynamicsTest` | — | ❌ Not inspected on a device |
| A network word's marks and letter shapes are right | ❌ | ❌ | ❌ ML Kit passes *"hot"* and *"Calion"* (§21.5) | ❌ Seen wrong on the device sheet |
| **H5: personalisation, the gate, fidelity, switching** | | | | |
| Gate calibration: positive and negative controls | ✅ | ✅ `GateCalibrationTest` | ✅ tab: real ML Kit, 154 words (§20.2); 155 again under the top-reading rule (§21.4.1) | ✅ tab |
| A wrong label is refused | ✅ | ✅ | ✅ **40/40** (§21.4) and 1.000 on the tab (§20.2.2) | — |
| A lost decimal point is refused | ✅ | ✅ `GateRule` never folds punctuation for digits | ✅ **10/10 (new, §21.4)** | — |
| A wrong digit, or a gained point, is refused | ✅ `GateRule.digitReading` (§21.4.2) | ✅ `GateCalibrationTest`, `HandwritingSynthesizerTest` | ✅ **34/34 refused** by `GateMlKitTest` under both §21.4.1 and §21.4.2; it was 11 of 34 passed (§21.4) | ✅ tab: §21.4.2's rule calibrated there, numbers 13/15 and formulas 21/24, the same as on emu (§21.4.2) |
| A digit read as a letter first still passes | ✅ (§21.4.2) | ✅ `GateCalibrationTest`, `HandwritingSynthesizerTest` | ✅ The tab's profile on emu: maths digit runs 87/108 at the first reading, against 78/108 under the top reading (HANDWRITING-MATHS §5.4) | ❌ Not on the tab |
| An older calibration is measured again under the new rule | ✅ `ruleVersion` 3 | ✅ `PersonaliserTest`, `GateCalibrationTest` | — | ✅ tab: version 1 → 2 (§21.4.1), then 2 → 3 (§21.4.2), each on the first pass after install |
| Tokens the recogniser cannot judge are built from verified letters | ✅ | ✅ `SwitchingTest` | ✅ `MathsReport` | ❌ No class is unjudgeable on this hand |
| Fidelity F for both backends; F over time | ✅ | ✅ `FidelityTest`, `BackendPolicyTest`, `PersonaliserTest` | ✅ The tab's profile through the real network | ✅ tab (§20.3) |
| P1, P2 and P3 are measured, and none ships | ✅ | ✅ `PersonaliserTest`, `FitterTest`, `TrainableNetworkGradientTest` | ✅ PyTorch's autograd; laptop F | ✅ tab, two rounds (§20.4) |
| Switching: the policy, routing, and *Auto / My letters / Model* | ✅ | ✅ `BackendPolicyTest`, `SwitchingTest` | ✅ Real ML Kit gated every word | ✅ emu and tab (§20.5). ❌ *Auto* has not switched on its own |
| The personalisation worker: charging and idle; checkpoints; resumes after a kill | ✅ | ✅ `FitterTest`, `PersonaliserTest` | ✅ `CrashSafetyTest`, in another process | ✅ emu 6/6; tab: `CHARGING IDLE`, slices resumed (§20.7) |
| **H6: the harness** | | | | |
| The SVG sheet, the debug screen, the ML Kit gate test | see §21.7 | | | |

### 21.9 The runs behind the tables

Every test task ran with `--rerun` and `--no-build-cache`, and none was up to date or restored from
cache. The counts come from the XML results.

| Task | Tests | Run |
|---|---|---|
| `:core:handwriting:testDebugUnitTest` | **213**, all passing (210 + `ContactSheetSvgTest`'s 3) | 2026-10-15, twice; the second after the formatting fixes |
| `ContactSheetSvgTest` on the emu profile (`INKSTONE_HW_PROFILE`) | 3, all passing; the bounds are reported, not asserted | 2026-10-15 |
| `:app:testReleaseUnitTest` | **427**, all passing | 2026-10-15. The shared checkout, with other sessions' work in it; H6 added no `:app` JVM test |
| `GateMlKitTest` (`am instrument`, `Test_Tablet`) | **8 run, 8 passing, 1 ignored** | 2026-10-15, twice, the second on the final formatted code (40 s, then 233 s straight after a reinstall). A first run, with the digit assertions still active, failed exactly as §21.4 says |
| The debug screen, trusting, on the final code | 6/6 fingerprints unchanged and equal to the SVG's | 2026-10-15 |
| `tools/handwriting` (`python -m unittest test_pipeline test_sigmalognormal`) | **53**, all passing (51 + 2 new) | 2026-10-15 |

These are green, and ran:
- `checkCoreIsPlatformFree`;
- `:core:handwriting:compileKotlinIosSimulatorArm64` and `compileTestKotlinIosSimulatorArm64`;
- `:core:handwriting:detekt` and `spotlessCheck`;
- `:app:assembleDebug` and `assembleDebugAndroidTest`.

`:app:detekt` and `spotlessCheck` are red, only on H5's files (§21.5). The iOS framework link is
SKIPPED on Windows and belongs to the macOS CI job. `./gradlew build` as a whole is red for §16.4's
reasons and was not run as a whole. `CrashSafetyTest` and `QuickStartPenInputTest` were not re-run:
nothing they depend on changed.

**The emulator** was `Test_Tablet` (`emulator-5554`, resolved by name before every command), cold-booted
for this phase. It ran a debug build of the shared checkout and stayed in airplane mode throughout. No
note was opened or written. The only files written were `files/debug/hw-sheet/*.webp`, from the debug
screen. `Test_Tablet_B` was not started.

#### 21.9.1 The runs behind §21.4.1 (2026-10-16)

Same rules: `--rerun` after each test task, `--no-build-cache`, counts from the XML.

| Task | Tests | Run |
|---|---|---|
| `:core:handwriting:testDebugUnitTest` | **253**, all passing | Twice, the second after the log line named its control. Four tests are new: two in `GateCalibrationTest`, one in `HandwritingSynthesizerTest`, one in `PersonaliserTest`. Two existing tests changed: `HandwritingSynthesizerTest`'s *the gate's rules* had asserted `CaCO3` passing from the second reading, and `MathsWritingTest`'s trusting reader now puts its digit run first |
| `:app:testReleaseUnitTest` | **433**, all passing | The shared checkout, with other sessions' work in it |
| `GateMlKitTest` (`am instrument`, `Test_Tablet`) | **9 run, 9 passing** | After `adb install -r` of the debug and test APKs, 11.6 s |
| The tablet's calibration pass | WORD, FORMULA and NUMBER calibrated over 155 words | Release build, `PersonaliseNowReceiver`, `pass: Idle in 14556 ms` |

These are green, and ran:
- `checkCoreIsPlatformFree`;
- `:core:handwriting:compileKotlinIosSimulatorArm64` and `compileTestKotlinIosSimulatorArm64`;
- `:core:handwriting:detekt` and `spotlessCheck`;
- `:app:spotlessCheck`;
- `:app:assembleDebug`, `assembleDebugAndroidTest` and `assembleRelease`.

`:app:detekt` is red on one line, `HandwritingScreen.kt:440` (H5's, too long), and nothing else. The
iOS framework link is the macOS CI job's. Neither device had a note opened or written. The tablet now
runs the measured build of the shared checkout, which differs from the tree only in that log line's
wording. Its `profile.json` holds the version-2 calibration.

#### 21.9.2 The runs behind §21.4.2 (2026-10-16)

Same rules: `--rerun` after each test task, `--no-build-cache`, counts from the XML.

| Task | Tests | Run |
|---|---|---|
| `:core:handwriting:testDebugUnitTest` | **255**, all passing | Twice, once straight after the change and once in the final check. New: *a number read first as letters is written first time*, and `MathsLayoutReport`'s plan test. `GateCalibrationTest`'s digit test now asserts the user's own `1` and `5` readings |
| `:app:testReleaseUnitTest` | **433**, all passing | The shared checkout |
| `GateMlKitTest` (`am instrument`, `Test_Tablet`) | **9 run, 9 passing** | Cold-booted emulator, after `adb install -r` of both APKs, 116 s |
| `MathsGateReceiver`, both profiles, with `calibrate` on the tablet's | 21 expressions × 3 seeds each; 155 words calibrated | `Test_Tablet`; the pulled profile was deleted from it afterwards |
| The tablet's calibration pass | WORD, FORMULA and NUMBER over 155 words, version 3 | Release build (SHA-256 `32dc7c70…`), installed 10:40:41; `PersonaliseNowReceiver`; `pass: Idle in 14186 ms` |

These are green, and ran:
- `checkCoreIsPlatformFree`;
- `:core:handwriting:compileKotlinIosSimulatorArm64` and `compileTestKotlinIosSimulatorArm64`;
- `:core:handwriting:detekt` and `spotlessCheck`;
- `:app:spotlessKotlinCheck`;
- `:app:assembleDebug` and `assembleDebugAndroidTest`.

`:app:detekt` is red on `HandwritingScreen.kt:440` alone, as before. The tablet now runs the §21.4.2
build of the shared checkout. No note was opened or written on it.
