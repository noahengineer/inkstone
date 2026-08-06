# M4 — PDF import, materialization, and the identity marker

The M4.0 spike's answer, what was built on it, and the six things the spec turned out to
have wrong.

Read with `SPEC.md` §4.4 (coordinate spaces), §5.1–5.2 (working vs published format),
§5.6 (identity and Recovered mode) and §6 (PDF handling), and `ROADMAP.md` M4.

---

## 1. ⭐ The M4.0 spike — `androidx.pdf` cannot write our annotations

SPEC Appendix B #1 asked two questions. Both were answered against the **shipped
artifacts** — `androidx.pdf:pdf-ink`, `pdf-core` and `pdf-document-service` at
`1.0.0-alpha19`, plus the API 37 framework stubs they compile against — rather than
against the release notes, for the same reason M1 and M2 did it that way.

### Q1: does it write standard `/Ink` annotations readable by Acrobat? **No.**

The entire path is one method, `androidx.pdf.ink.util.StrokeExtensionsKt.toStampAnnotation`:

```
Stroke
  → stroke.shape                                    (PartitionedMesh: the tessellated OUTLINE)
  → AndroidGraphicsConverter.outlinesToPath(mesh, group)
  → getPathInputsFromPath()                         (Path.approximate → MOVE_TO / LINE_TO)
  → PathPdfObject(brush.colorIntArgb, brush.size, inputs)
  → StampAnnotation(pageNum, bounds, [path])
```

It becomes a **`/Stamp`**, not an `/Ink`. That is not a choice the library could reverse:
the framework type it converts into, `android.graphics.pdf.component.PdfAnnotationType`,
has exactly four values — `UNKNOWN`, `FREETEXT`, `HIGHLIGHT`, `STAMP`. **There is no ink
annotation in the platform API at all.** So no `/InkList`, and nothing else can select,
move or delete the stroke.

Two further findings, neither of which the spike asked about and both of which matter more
than the question:

- **No `/NM`.** `android.graphics.pdf.component.PdfAnnotation` exposes exactly one method,
  `getPdfAnnotationType()`. `PdfRendererAnnotationsProcessor.insertPdfAnnotation` returns
  `String.valueOf(page.addPageAnnotation(...))` — an annotation's identity is its **integer
  index on the page**. That is CLAUDE.md's *"never derive identity from array position"*, in
  the one place where getting it wrong duplicates every stroke on the next sync (SPEC §5.6).
- **The save is a full rewrite.** `PdfWriteHandleImpl` → `PdfDocumentRemote.write` →
  `android.graphics.pdf.PdfRenderer.write(pfd, removePasswordProtection)`, whose own javadoc
  reads *"Saves the current state of the loaded PDF document"* — a whole-document PDFium
  re-serialisation to a fresh descriptor. The boolean is about passwords; there is no
  incremental option. **SPEC §5.2 is unreachable through `androidx.pdf`**, and so is
  "give me my original PDF back".

### Q2: does variable width survive save → reload? **Outbound yes, inbound no.**

Outbound is better than expected. `PathPdfObjectConverter` calls `setRenderMode(0)`, and
`RENDER_MODE_FILL` **is** 0 (`RENDER_MODE_UNKNOWN` is `-1`), so what gets written is the
*filled outline* of a variable-width stroke — visually correct.

Reload is the problem. `AospPathPdfObjectConverter` reads `PdfPagePathObject.toPath()` →
`Path.approximate()` → `PathPdfObject(fillColor, strokeWidth, inputs)`: a flattened polygon.
No pressure, no inputs, no brush. The round trip is outline → polygon → outline. You can
redraw it; you cannot get a stroke back from it, which is exactly what Recovered mode needs.

### Verdict

**SPEC §6.3's fallback: PdfBox-Android `2.0.27.0` behind a `PdfWriter` interface.**

And `androidx.pdf` is dropped **entirely**, not merely for writing — see §2.

---

## 2. The compileSdk decision SPEC §0.2 left to M4

SPEC §0.2 flagged that every `androidx.pdf` 1.0.0-alpha19 artifact declares
`minCompileSdkExtension=19` while the API 36 platform ships extension level 17, and said M4
must *"choose between an ext-19 platform package and moving to compileSdk 37 — decide it
deliberately"*.

Both are real. `platforms;android-36-ext19` exists in Google's repository, and
`platforms;android-37.0` (ext 22) is installed on this machine.

**Neither is taken.** Once §1 rules `androidx.pdf` out for writing, the only thing left to
want from it is a bitmap — and SPEC §6.1's own fallback, framework `PdfRenderer` (API 35+) /
`PdfRendererPreV` (30–34), is in plain `android-36`. Taking a toolchain change (SPEC §0.2
pins AGP 8.13.0 *around* compileSdk 36) to obtain one method would also drag in
`PdfViewerFragment`, whose gesture handling would be competing with `InkSurfaceView` for
every touch.

**compileSdk stays 36. The AGP pin is untouched.** `verifyCatalogCoordinates` still proves
the `androidx.pdf` coordinate resolves, so nothing about the pin is lost.

---

## 3. ⭐ The incremental update: rebase, then append exactly once

```
document.pdf  =  original bytes (verbatim, forever)  +  ONE incremental update
```

`saveIncremental` copies the loaded bytes to the output verbatim and appends a body, an
xref and a trailer whose `/Prev` chains back. That is SPEC §5.2's requirement directly, and
it makes "give me my original PDF back" a `copyOfRange`.

**The part that is easy to get wrong and expensive to discover:** appending this session's
update onto the *previous published file* also satisfies "the original is at the front", and
grows the file by a whole annotation set every time the note is closed. Fifty sessions later
a 20 MB lecture is 40 MB of superseded ink and nothing says when it happened.

So every materialization **truncates back to the original first**, using
`inkstone:originalLength` from the marker. `PdfPublishTest` asserts both halves: the
original is byte-identical after five cycles, *and* the file has not grown past 2× the
first publish.

### The landmine, stated because it fails silently

In incremental mode PDFBox writes **only** objects whose `COSUpdateInfo.isNeedToBeUpdated()`
is true — including objects the calling code just created. An unmarked annotation is simply
absent from the output, with no error anywhere.

Worse, the mark has to be on the **whole parent chain**. The first version of `PdfBoxWriter`
marked every page and the catalog, and produced files that were valid, opened everywhere,
had the right page count, and contained **no ink at all** — because PDFBox walks from the
trailer and stopped at the unmarked `/Pages` node in between. `markParentChain` is that fix,
and the reason it was found is that `PdfPublishTest` asserts on the parsed output rather
than on the intent.

---

## 4. Why the acceptance tests are JVM unit tests

PdfBox-Android is a repackaged pure-Java PDFBox. Across the whole structural half —
`PDDocument`, `COSWriter`, `PDPage`, `PDAnnotation` — it references exactly **one** Android
class: `android.util.Log`. With `unitTests.isReturnDefaultValues = true`, all of it runs on
the JVM.

That is what makes `/Rotate 90`, non-zero CropBox, byte-identical-original, the density rule
and Recovered mode real tests over real PDF bytes instead of instrumented ones. SPEC §4.4
calls this bug class *"invisible until a real PDF is on screen"*; it is now visible in three
seconds on a laptop.

**Still owed to a device** (ROADMAP M4's manual gates, unchanged): Acrobat, Chrome and macOS
Preview each drawing the result correctly, and a 2,000-stroke page opening without hanging
Preview. No unit test replaces those.

---

## 5. Erasure is subtracted on the ink side, not in the PDF

SPEC §3.5 erases by *area*: the stroke is untouched and the swept region is subtracted at
draw time, which on screen is a `DST_OUT` layer. A PDF has no cheap equivalent, and the two
constructions that look like one both fail on the input that actually occurs:

- **Even-odd fill** of *stroke ∪ capsules* removes one capsule correctly and **re-fills**
  wherever two capsules overlap — which a scribbled erase produces on nearly every stroke.
- **Reversed-winding subpaths** under nonzero fill fail identically, for the same reason.

A luminosity soft mask would be exact and is a lot of machinery. But there is already an
exact boolean path engine on the other side of the boundary — Skia — so `InkStrokeOutliner`
does `Path.op(eraser, DIFFERENCE)` and hands `:android:pdf` what is *left*. The PDF writer
fills a polygon and never learns an eraser was involved.

---

## 6. What the spec had wrong

Six corrections. All are amended in `SPEC.md` in the same commit as this file.

### 6.1 `androidx.pdf` writes `/Stamp`, not `/Ink` — SPEC §6.1, Appendix B #1

§1 above. The spike is closed; Appendix B row 1 records the answer.

### 6.2 PdfBox-Android 2.0.x has no `PDAnnotationInk` — SPEC §6.3

SPEC §6.3's table says PdfBox-Android *"can create `PDAnnotationInk` + content streams"*.
There is no such class: the annotation package stops at `PDAnnotationRubberStamp` and
`PDAnnotationTextMarkup`. `/Ink` is still entirely writable — `PDAnnotation` is COS-backed,
so the subtype, `/InkList`, `/AP` and `/NM` are written directly — but there is no typed
helper. A correction, not a blocker.

### 6.3 `xmpbox` is not bundled — SPEC §6.3

Also absent from the AAR. The XMP marker is written as a raw uncompressed `/Metadata`
stream, which is what Adobe's XMP specification asks for anyway (so a tool can find the
packet by scanning rather than by walking the object graph) and gives exact control over the
bytes.

### 6.4 ⭐ The marker needs five properties, not three — SPEC §5.6

SPEC §5.6 lists `documentId`, `schemaVersion`, `bundleSha256`. Two more are load-bearing:

- **`originalLength`** is what makes the file stop growing (§3). Without it there is no way
  to find where the original ends, and every publish appends to the last one.
- **`originalSha256`** is what makes the contract *checkable*. ROADMAP M4 requires the
  original bytes to be recoverable byte-identically; with a hash that is verified rather
  than asserted, and `RecoveryLoss.PRISTINE_ORIGINAL` becomes a fact rather than a guess —
  which matters precisely because the bundle it would otherwise consult is the thing that
  went missing.

### 6.5 ⭐ Page identity was missing — SPEC §5.6

SPEC §5.6 puts `documentId` in the marker and an `ObjectId` in each annotation's `/NM`, and
stops. That covers strokes and misses **pages**.

A Recovered import with no page identity has to invent a `PageId` per page. A peer merging
that order keeps *both* the old page and its replacement, because SPEC §4.3's rule is *"any
page not present in a merged order is appended at the end, never dropped"*. The note
silently doubles its page count: the same failure as duplicated strokes, one level up.

So M4 writes two private keys into every page dictionary it publishes — `/INKS_PageId` and
`/INKS_SortKey` — plus `/INKS_Flattened` where SPEC §6.4's density rule applied. Private
keys are legal, ignored by every viewer, and cost two strings per page.

### 6.6 A marker from the future must be refused, not recovered — SPEC §5.6, §5.8

SPEC §5.6's table has three rows and assumes every marker found is one this build
understands. A marker carrying a newer `schemaVersion` is not: recovering it would rewrite
a newer format's document in *this* build's vocabulary, under the original `documentId`,
with the Lamport counter deliberately bumped past everything — so it would win the next sync
and take the newer data with it. SPEC §5.8 already forbids re-saving a file from the future;
`PdfOpenMode.TooNew` is that rule reaching the one path that was about to break it.

### 6.7 `PdfRendererPreV` needs an SDK *extension*, not just API 30 — SPEC §6.1

SPEC §6.1 says `PdfRendererPreV` *"backports to 30–34"*. It does — via the **mainline PDF
module**, and it requires **S extension level ≥ 13**. A device on API 30–34 that never took
that update does not have the class at all, which Android lint catches and which
`PdfPageRasterizer.hasPreVRenderer` now checks at runtime.

The consequence is bounded and is the right one: an imported page draws on white paper and
the ink still draws on top of it, in the right place, because the geometry never came from
the renderer. Nothing is lost; the scan is not shown. The Galaxy Tab this is built for is
API 35+, where `PdfRenderer` applies.

---

## 7. Two choices that look like assumptions and are not

### 7.1 `/AP` uses an identity `/Matrix`

SPEC §4.4 rule 5 warns not to *assume* an identity `/Matrix`, and rule 4 says the rotation
*"is then folded into the annotation's `/Matrix`"*. M4 folds it one step earlier:
`PdfPageGeometry.toPdfUser` already maps page space through `/Rotate` into unrotated PDF
user space, so by the time a coordinate reaches the writer there is no rotation left to
express. The content, the `/BBox` and the `/Rect` are then all in the same space, and the
viewer's BBox→Rect fit is the identity it would compute anyway.

This is simpler *and* safer than a rotated matrix, because a non-identity `/Matrix` has to
agree with a `/BBox` computed in form space and the two disagreeing is exactly the
"annotations are 3mm off and rotated" failure §4.4 exists to prevent.
`PdfPublishTest` pins it: `/BBox` equals `/Rect`, `/Matrix` is identity, and the `/InkList`
round-trips through the page's geometry on a page that is both rotated *and* cropped.

### 7.2 Recovery reads `/InkList`, not `/AP`

The appearance stream is a filled outline — an edge with no centre, no inputs and no brush —
so re-importing it would give a polygon shaped like ink rather than ink. `/InkList` is the
path the stroke was written along, which is what a `StrokeInputBatch` wants.

SPEC §6.4 justified writing both representations by interop, *"so other editors can
select/move/delete it"*. It turns out the redundant half is also the only one that
reconstructs as editable ink. The cost is uniform width, which is
`RecoveryLoss.PRESSURE_AND_WIDTH` and is reported rather than absorbed.

---

## 7.3 The bundle is a file *and* a copy inside the PDF

SPEC §5.1 said *"open = load the embedded bundle from `document.pdf`"*. M4 does not, and
§5.1 is amended.

Extracting the bundle from the PDF on every open costs a full parse and a stream decode
before the first page can be drawn — and it defeats §5.3's lazy loading outright, which
depends on random access into the ZIP's central directory. A `/EmbeddedFile` has to be
inflated whole first, so a 200-page notebook would be fully materialised in memory to open
page one.

So `note.inkstone` stays a file of its own and `document.pdf` carries a copy. **The
published copy is what travels; the local file is what is read.** The marker's
`bundleSha256` is what keeps them honest — it always describes the copy inside the PDF, so a
mismatch means something outside this app touched it, which is exactly SPEC §5.6's Recovered
trigger.

The duplication is a few hundred KB of op metadata beside a 20 MB PDF. The copy §5.2 was
careful about — `source.pdf` — is the one that would have cost real money, and that one is
genuinely gone.

---

## 8. Where the pieces live

| Concern | Module | Why there |
|---|---|---|
| Coordinate conversion, density rule, `/NM` naming | `:core:model` | Decisions, not platform capabilities. Unit-tested without a device; the iPad path gets them free. |
| SHA-256, XMP packet, three-way detection, Recovered **replace** | `:core:storage` | Policy and format. `detectOpenMode` is tested one case per row of SPEC §5.6's table. |
| Stroke → fillable outline, `/InkList` → stroke | `:android:ink` | Only it owns the tessellator and Skia. |
| Everything PDF | `:android:pdf` | No PdfBox type leaves it (SPEC §6.3). |
| Wiring the three together | `:app` | Which is why the graph stays one-way (SPEC §2.1). |

`PdfWriter` is `internal`: SPEC §6.3 puts the interface there so PdfBox can be *replaced*,
not so `:app` can choose a backend.

---

## 9. What M4 did not do

- **The invisible text layer is scaffolding.** The code path exists, is written, and is
  exercised with an empty word list; M7 supplies the words from ML Kit and the per-word
  boxes from the strokes that made them. `PdfPublishTest` asserts that a word given to it
  comes out as `3 Tr`, so M7 inherits a path that already works.
- **A non-ASCII word would be truncated** by `ContentBuilder`'s Latin-1 encoding. Fine for
  scaffolding, and M7 owes a font decision (WinAnsi covers Latin-1; anything else needs a
  CID font).
- **Idle-while-charging materialization** (SPEC §5.1) is still unscheduled.
  `WorkManagerScheduler.enqueueMaterialization` is M5's, together with the outbox it
  belongs beside. Close and before-upload both work today.
- **Recovered ink is uniform width**, permanently. That is the format's limit, not an
  implementation gap.
