# M8b — Shapes and connectors

**Status: the format half is built and verified; the renderer and the UI are not started.**
§6 is the remaining work with the traps each step inherits.

This document is what was decided and why, in the order the milestone's prompt asked for it —
the three known disagreements first, because they had to be resolved before any code.

---

## 1. The three disagreements, resolved

### 1.1 POLYGON — ROADMAP loses

`ShapeKind` was frozen in M2 as `{LINE, ARROW, RECTANGLE, ELLIPSE, TRIANGLE}`. `OpCodec.decodeObject`
reads it with `ShapeKind.valueOf`, and `BundleReader` decodes a page's ops unguarded — so a sixth
constant is not a degrade, it is an **older build failing to open a note it has already synced**.
Dropped. SPEC §4.8.8 and ROADMAP now both say so.

A closed run of straight segments stays expressible as an ordinary ink stroke with no format change
at all. It just cannot carry a fill, which is the honest reason it is not the same feature.

### 1.2 "Connector" appears nowhere in SPEC — decided, and written into SPEC

**A connector is a `ShapeObject` of kind `LINE` or `ARROW` whose `SetTextContent` register holds a
`ShapeRecord`.** No new object kind, no new `ShapeKind`, no new op type. SPEC §4.8 is the normative
account and was committed with the code.

The reasoning is in `Connector.kt`'s KDoc and in SPEC §4.8.2; the short version is that the four
things M8b must persist — fill, fill opacity, corner radius, attachment — have exactly one home
that an older build round-trips losslessly, and `SetTextContent` is it: the vocabulary's only
absolute, object-keyed, arbitrary-length register.

**The cost is stated, not hidden.** One register holding four independently-edited properties
resolves LWW *whole*, so a concurrent fill change and endpoint re-attachment lose one of the two.
That is a lost edit, not a corrupt state — a record is written whole and consumed whole, so
whichever wins is internally consistent and both devices hold the same one.

### 1.3 "Detached" is derived, never stored

Three ways an end fails to resolve — tombstoned, not-yet-arrived, unanchorable — and they produce
**identical geometry**: the connector's own payload segment under its own transform. They are told
apart only for a label.

Storing a detachment would make the *not-yet-arrived* case permanent: a connector whose target was
merely still in flight, written off by whichever device noticed first and still written off after
it arrived. `ConnectorTest` asserts the two cases are indistinguishable geometrically, and asserts
that an end re-attaches by itself when its target arrives and when a delete is undone.

---

## 2. The four traps, and what each one actually forced

| Trap | What it changed |
|---|---|
| **No new kinds or ops** | The whole design above. Nothing was added to `ShapeKind`, `InkObject` or `PageOp`. |
| **A reroute writes no op** | The route is a pure function of the fold. An anchor is a **normalised point in the target's own box**, so it is carried by the target's absolute matrix — which is why a connector tracks a resize and a rotation and not merely a translate, with nothing written. |
| **`AddObject` payload is first-write-wins** | Decided up front: an endpoint **can** be re-attached, so the binding is a register and not payload. The payload is used for one thing only — the fallback geometry, which must never change, and where first-write-wins is exactly right. |
| **Shapes live in `StrokeStore`** | Not yet reached — §6. Recorded as the first constraint on the renderer. |

---

## 3. Three things the prompt did not list, found by grepping for what names an id

CLAUDE.md's M8a lesson — *"before adding the thing that makes two spaces differ, grep for every
place that names either one"* — applied here to *"every place that copies an object"*.

1. ⚠️ **Duplication drops the register and keeps the old ids.** `duplicateNote`, `duplicatePage`
   and `conflictedCopyOf` each folded a page and emitted one `AddObject` per object under a fresh
   `ObjectId`. Two failures, neither of which anything reported: every record vanished (a
   duplicated diagram comes back as unfilled boxes joined by straight authored lines), and every
   surviving binding named the *original's* ids, so every connector in the copy would resolve as
   detached — defined, non-crashing, and visibly wrong.

   `ConflictedCopy`'s own KDoc had asserted the invariant that made a one-line copy safe:

   > A fold collapses **every** register … onto the object payload, so the only op left to write is
   > `AddObject` and there is **no second op carrying an `ObjectId` in a `target` field** that a
   > re-identification pass could miss.

   Both halves stopped being true at once. All three call sites now go through one
   `copiedObjectOps`, which emits the register and re-targets the ids inside it. The private
   `withNewId` in `NoteSession` — a third hand-written `when` over the four object kinds — is gone
   for the reason its neighbour's KDoc gives: a third copy is where the *next* register is
   forgotten.

2. **`PageFolder.materialise` dropped the register for every kind but `TextBoxObject`.** A
   `SetTextContent` aimed at a shape folded correctly, converged correctly, round-tripped through
   the file correctly, and was discarded at the last step with nothing to see. `FoldedPage.textOf`
   exposes it now, and `seedFrom` seeds it so a snapshot is not a hole.

3. **`Undo.inverseOf` cast the target to `TextBoxObject`.** So *"change a shape's fill, press
   undo"* produced no inverse — and `UndoStack.commit` drops an entry with no inverse, so the press
   would have looked as though it worked. That is SPEC §4.2's first-`SetStyle` trap, in the
   register next door, and it was one cast away from shipping.

And one that is *not* a bug and is worth recording so nobody "fixes" it: **compaction was already
safe.** SPEC §4.7 keeps the raw log and uses the snapshot only as a base, so `PageLog` re-absorbs
the real `SetTextContent` on every load. `writeSnapshot` emits it anyway — the difference between a
cache and a cache with a hole that happens to be repaired downstream.

---

## 4. The questions the prompt asked

**Where connector attachment lives in the bundle.** In the page's op log, as an ordinary
`SetTextContent` op (`"t":"txt"`) targeting the connector's `ObjectId`, whose `"x"` member is the
record string. Nowhere else — not in the manifest, not in the object payload, not in a sidecar.

**What an M8a-era build does when it opens such a note.** It opens it, keeps every byte, and shows
no shapes. `StrokeStore.addObject` opens with `as? StrokeObject ?: return false`, so a
`ShapeObject` is dropped from the *screen* while its `AddObject` and its record sit correctly in
the log, survive an autosave, survive a compaction snapshot, and re-materialise the next time an
M8b build opens the note. The PDF that older build publishes will lack the shapes; the bundle
inside it will not, and the bundle is the truth (SPEC §5.1). That is a degrade, and it is the
strongest one available without a schema bump.

**A tombstoned endpoint versus one that has not arrived.** Identical geometry — the connector's own
payload segment — and different labels (`DETACHED_TOMBSTONED` / `DETACHED_ABSENT`). Both re-attach
by themselves if the object appears or the delete is undone, because nothing was written down.
§1.3 above and SPEC §4.8.4.

**A connector tracking a RESIZE and a ROTATE.** `ConnectorTest` has one test each, and they assert
page coordinates rather than "it moved": a pinned anchor on a box's right edge follows a 2× x-scale
from 400 to 500, and a pinned anchor on a box's top edge follows a quarter-turn about the box's
centre from (350, 0) to (400, 50). Both work because the anchor is a normalised point in the
target's own box and is mapped by the target's absolute matrix — one affine map, no special cases.
An `Auto` anchor additionally re-faces its partner, which the translate test asserts explicitly
(and which is why the endpoint on the shape that *did not* move also moves).

**The ink-path cost of draw-and-hold against SPEC §3.6.** By construction rather than by
measurement, because there is nothing on a device to measure yet:

- **Per `MotionEvent` sample:** two float writes into a buffer the caller already owns, and one
  distance compare against a threshold. No allocation, no branch into `ShapeSnap`. This is the part
  that runs at 240 Hz and it is what §3.6's *"sustained 60 fps with 5,000 dry strokes"* budget sees.
- **Once, when the hold fires:** `ShapeSnap.recognise` makes two passes over that buffer with **no
  allocation at all** — which is why its signature is `FloatArray, FloatArray, Int` rather than a
  `List<PagePoint>` that would allocate one object per sample at the exact moment the user is
  waiting for the shape to appear.
- *"Recognition"* in SPEC §2.4's sense means a recogniser — ML Kit, a model, a dictionary. This is a
  bounding box and a shoelace area.

⚠️ **This is a design claim, not a measurement.** §3.6's gate is a camera test against Samsung Notes
and `dumpsys gfxinfo`, and neither has been run for this feature because the feature is not on a
screen. It is owed by whoever builds §6.

**What a non-PdfBox viewer shows for a translucent filled shape.** **Unknown, and deliberately not
guessed at** — nothing writes a shape to a PDF yet. What is already known and must not be
rediscovered is in CLAUDE.md: a translucent fill needs an `/ExtGState` with `/ca`, a page content
stream has **no `/Resources` of its own** and resolves names through the page dictionary, and
`/Resources` is inheritable so a fresh one *shadows* whatever a source PDF kept on a `/Pages` node.
M4 shipped exactly that bug — a translucent highlighter came out fully opaque in PDF.js and the
overlay was not drawn at all in a stricter reader — and it was green under PdfBox the whole time.
`PdfViewerCompatTest` is the test that found it and is where the answer has to come from.

**Test counts.** `tests=` summed over `*/build/test-results/testDebugUnitTest/*.xml`:

| Module | tests | failures |
|---|---|---|
| `:core:model` | 202 | 0 |
| `:core:storage` | 153 | 0 |
| `:core:sync` | 187 | 0 |
| `:core:search` | 22 | 0 |
| `:app` | 95 | 0 |
| **Total** | **659** | **0** |

M8b added **47** of those (`ConnectorTest` 21, `ShapesTest` 21, `ShapeRecordStorageTest` 5). M8a
recorded 566 across four modules; this figure includes `:core:search`, which that one did not.
Every task was forced with `--rerun` **per task** plus `--no-build-cache`, and the `> Task` lines
were read back — none logged `UP-TO-DATE` or `FROM-CACHE`.

---

## 5. What was checked, and how

CLAUDE.md asks which of *written*, *automated*, *automated against the real dependency* and *run on
a device* each claim is. Being blunt: **nothing here has been run on a device, and nothing here is
reachable from the app.** The column exists to say so.

| Claim | Unit (JVM) | Through storage | On a device / in the app |
|---|---|---|---|
| Record round-trips its wire form; re-encode is byte-stable | `ConnectorTest` ✅ | — | ❌ |
| Unknown record keys survive | `ConnectorTest` ✅ | — | ❌ |
| A non-record register decodes to null (fail closed) | `ConnectorTest` ✅ | — | ❌ |
| Connector tracks translate / resize / rotate | `ConnectorTest` ✅ | — | ❌ |
| Moving a shape writes no op | `ConnectorTest` ✅ (asserts the op count) | — | ❌ |
| Tombstoned vs absent endpoint; re-attach on arrival and on undo | `ConnectorTest` ✅ | `ShapeRecordStorageTest` ✅ | ❌ |
| Elbow and curved routes | `ConnectorTest` ✅ | — | ❌ |
| Routing is order-independent | `ConnectorTest` ✅ | — | ❌ |
| Shape outlines, boundaries, placement | `ShapesTest` ✅ | — | ❌ |
| An axis-aligned line has an invertible matrix | `ShapesTest` ✅ | — | ❌ |
| Draw-and-hold classifies; a squiggle snaps to nothing | `ShapesTest` ✅ **synthetic only** | — | ❌ |
| Record survives a bundle round trip | — | `ShapeRecordStorageTest` ✅ | ❌ |
| Record survives a compaction snapshot | — | `ShapeRecordStorageTest` ✅ | ❌ |
| Page duplicate re-targets bindings | — | `ShapeRecordStorageTest` ✅ | ❌ |
| Note duplicate re-targets bindings | — | `ShapeRecordStorageTest` ✅ | ❌ |
| Merge behaviour of a record under concurrency | ❌ **not written** | ❌ | ❌ |

⚠️ **The snapping row is the weakest thing in this table** and M7's lesson names why: *"a synthetic
fixture is the worst possible witness for a geometric heuristic"* — its rotated-text detector passed
its unit test and produced 105 false positives on 20 real pages. `ShapesTest` proves a mathematically
perfect ellipse classifies as an ellipse. It proves nothing about a person's ellipse, and the
acceptance criterion *"no fighting it to draw a freehand curve"* is a device question.

⚠️ **`adb devices` lists `emulator-5554` (a Pixel Tablet x86_64 image), and two AVDs exist.** It was
checked first, as the prompt asked. It is **unused**, and the reason is not availability: there is
nothing to drive. No tool creates a shape, so there is no button to press and no framebuffer to
compare. That is the honest version of definition-of-done #6, and it is why the ROADMAP boxes are
unticked.

### Gates

- **`detekt`, `spotlessCheck`, `checkCoreIsPlatformFree`: green**, all executed.
- **Guardrail 2 — the klib compile half only, and it caught a real defect.** `toSortedMap` is
  `java.util.SortedMap` and does not exist in common Kotlin: `compileDebugKotlinAndroid` passed it
  and `compileKotlinIosSimulatorArm64` failed it. Exactly the class of thing CLAUDE.md says the
  import check cannot see. `compileTestKotlinIosSimulatorArm64` was also forced, which is what
  catches an illegal backticked test name before it reaches D8.
  The **framework link** (`linkIosCore`) logs `SKIPPED` on Windows and is the macOS CI job's to
  prove. This is not a claim that it passed.
- **Definition of done #4 (the Galaxy Tab): not met, and not close.** Nothing runs.

---

## 6. What is left, in order, and what each step inherits

1. **`StrokeStore` must hold shapes.** `addObject` opens with `as? StrokeObject ?: return false`, so
   a shape arriving from a peer, from an undo's re-add or from a page reload is dropped from the
   screen while being perfectly correct in the log. It needs a `DryObject` that `DryStroke` and a
   new `DryShape` both satisfy — **not a second store**, because `StrokeStore` owns the top z key
   and a controller with its own counter goes stale the moment `SetZOrder` exists (M8a's recorded
   regression), and because one list is what lets `(z, id)` interleave a shape and a stroke.
2. **`PagePainter` draws them**, sharing the `saveLayer`/`DST_OUT` erase compositing that already
   works for any drawn content. `ConnectorRouting.route` takes a `ConnectorTargets` rather than a
   `FoldedPage` precisely so the surface can answer from its own render cache — it does not hold
   `InkObject`s and must not be given a signature it can only satisfy by fabricating one.
3. **`NoteEditor.reconcile` must diff the record.** It compares presence and the four object
   registers; a record is a fifth, and it lives outside `InkObject`. A peer's *re-attachment* would
   otherwise arrive invisibly. Note that a peer's *move* re-routes for free — that is a
   `SetTransform` the diff already carries, and the route is derived — which is the payoff of
   §1.2, and also the reason this gap is easy to miss.
4. **A tool that creates one**, and `InkToolbarActions` wiring. Until this exists nothing above is
   reachable, and a green suite establishes nothing (CLAUDE.md definition-of-done #6).
5. **Draw-and-hold**, per SPEC §4.8.7's split. The per-sample buffer belongs to the surface.
6. **The PDF**, with `PdfViewerCompatTest` and not PdfBox as the witness — see §4's last question.
7. **A merge-matrix row for a record under concurrency.** Rows 2, 3 and 4 have connector analogues
   that nothing currently exercises: a `SetTextContent` on a tombstoned connector, two concurrent
   record writes, and a record write concurrent with a `SetTransform` on its target (which must
   *both* apply — different registers).
8. **Then the device**, including SPEC §3.6's two measurements, which §4 records as owed.
