# M8c — Text, images, and tools

**Status: the two blocking format decisions are made, written into SPEC, and implemented.
The feature surface is not finished.** §7 says exactly what exists and what does not, per feature,
and §6 is the remainder in the order the next session should take it.

Read SPEC §4.9 first — it is the normative account and it is new.

---

## 1. The disagreements, resolved

ROADMAP M8c's prompt named two as blocking. There were four.

### 1.1 "font, size, colour, alignment, lists" and "crop" had no register — SPEC wins, and gains an op

§4.2's vocabulary has been closed since M2 and carries no register that can express any of them.
**Resolved by adding exactly one op, `SetFormat`** — the only op added since M2 — plus one type,
`ObjectFormat`, encoded as an ASCII record in the same shape as M8b's `ShapeRecord`.

**"Colour" needed nothing at all.** `ObjectStyle(colorArgb, widthPt, opacity)` and `PageOp.SetStyle`
already exist and already apply to any object kind. One of the five properties in the scope line
was never homeless; noticing that came from reading the type rather than the sentence.

### 1.2 ⭐ §4.8.1 conflated two failures, and that is why `SetTextContent` looked like the only home

M8b concluded that `SetTextContent` was the only place a record could live *"without a format
change"*. That is correct about what §4.8.1 examined and wrong about what it did not. Measured
against the codec, four kinds of extension behave four different ways:

| Extension | Older build | Where |
|---|---|---|
| new `InkObject` kind / new `ShapeKind` | **fails to open the note** | `OpCodec.decodeObject` ends `error("Unknown object kind")`, inside an unguarded `BundleReader.page` |
| new field nested in the `obj` payload | **silently dropped** on next autosave | `OpExtras` is collected at the op's *top* level only |
| new slot in an encoded value array | **silently dropped** | `decodeStyle` reads `array[0..2]`; the value is re-encoded from the decoded type |
| new **top-level field on a known op** | **preserved verbatim** | collected by `OpExtras`, merged back by `withExtras` |
| new **op type** | **preserved verbatim, not applied** | `encode` re-emits `Unknown`'s own bytes (`OpCodec.kt:126`); `PageFolder` collects it (`PageFold.kt:227`); `seedFrom` re-seeds it from a snapshot (`:164`) |

Only the first is a broken note. The last is the escape hatch **SPEC §5.8 has required since M2** —
*"unknown op types … are preserved verbatim on round-trip"* — and `PageOp.Unknown` exists to satisfy
it. §4.8.2's cost/benefit was computed against the wrong row.

This does **not** retroactively make M8b wrong. A shape's record genuinely belongs in one register:
everything in a `ShapeRecord` is comparably cheap to redo, so whole-value LWW resolution costs at
most one re-click. The next section is why a text box is not that shape of problem.

### 1.3 ⭐ Text and its formatting must not share a register

An LWW register resolves **whole**. Put the words and the font in one and *changing the font on one
device discards a paragraph typed on the other* — converged, on both devices, with every op present
and no error.

That is the **cheap edit destroying the expensive one**, and it is CLAUDE.md rule 1 written into the
format rather than into a code path where a test could catch it. Two registers make the two edits
commute, which is also what a user would predict.

The counter-argument — that `SetTextContent` is already whole-string LWW, so a concurrent *text*
edit already loses one — is true and is not the same. Two people typing in one text box is
real-time collaboration, which SPEC §12 puts out of scope. One person changing a font on the tablet
they are holding while the other tablet has yesterday's paragraph is Tuesday.

### 1.4 The font decision, booked against M4 and deferred by M7

See §3. Short version: **a subset-embedded CID font, not WinAnsi, and not `PDType0Font`.**

### 1.5 M8b is decided but not built, and its step 1 is this milestone's bite #4

`docs/M8-CONNECTORS.md` §6 item 1 asks for a `DryObject` that `DryStroke` and a future `DryShape`
both satisfy — for exactly the reason M8c needs one. M8c builds the abstraction; M8b inherits it.
Shapes and connectors themselves remain M8b's and were not touched.

---

## 2. What `SetFormat` carries, and what it deliberately does not

```
if1;f=se;sz=18;a=c;l=2;ls=b      a text box: serif, 18pt, centred, numbered list at level 2
if1;c=0.1:0:0.9:1                an image: rectangular crop, fractions of the source
if1;cp=0,0:1,0:1,1               an image: freehand crop, a normalised closed polygon
```

| Property | Register | Reasoning |
|---|---|---|
| font, size, alignment, list level/style | **`SetFormat`** | must not share with the text (§1.3) |
| image crop, rectangular **and** freehand | **`SetFormat`** | absolute, and an image's `SetTextContent` is free |
| colour, opacity | `SetStyle` | already existed |
| rotate, **flip** | `SetTransform` | absolute; a flip is a matrix negation |

**Crop is normalised to the source image's own box, never page points.** Same reasoning as SPEC
§4.8.3's anchor: a crop in page space would need rewriting under every resize, rotate and flip —
and those are `SetTransform`, a *different* register, so the two would have to be written together
and could then disagree. Normalised, the crop is carried by the object's matrix like any other
point on it and a resize writes one op.

**Crop is not `SetErasedRegion`.** An erased area is a union of eraser capsules and grows
monotonically; a crop is a single absolute region the user can widen again. "Erase part of a photo"
and "crop a photo" are different operations on the same image, and one register cannot be both.

⚠️ **A text box and an image must be created with a non-null `style`.** `SetStyle` cannot express
*"unset"*, so if the register is not seeded by the `AddObject`, the first recolour has no inverse,
`UndoStack.commit` drops the entry, and *"change the colour, press undo"* does nothing **while
eating the press**. `ObjectStyle`'s own KDoc anticipated this — *"images and text boxes have no
capture-time style and always carry one"* — which is only true if the code that creates them obeys
it.

**`ObjectFormat`'s font is a closed enum, not a family name.** A record is a `k=v;k=v` string, so a
value containing `;` or `=` corrupts it, and a font family name is the one value here a user could
ever supply. A closed set makes that unrepresentable rather than validated — and it is also what
lets the PDF writer guarantee an embeddable face for every value.

### The bug this uncovered next door

`ShapeRecordCodec`'s KDoc claimed *"a value containing a separator is rejected, not escaped"* and
**`encode` performs no such check** — it is a plain string concatenation. Safe only because every
value it had ever encoded was a UUIDv7 or a hand-formatted float. A reader who believed the comment
and put a user-typed value into the record would have got silent truncation *on the peer, one sync
later*.

The fix is **not** to add the check. A runtime rejection would fire on a *later* build's extras
value and turn forward compatibility into a crash on save — worse than the truncation it prevents.
The guard is the types. The KDoc now says what is true.

---

## 3. ⭐ The font: a subset-embedded CID font, written at the COS level

### 3.1 What was actually wrong

`PdfContent`'s `ContentBuilder.toByteArray` was `ByteArray(out.length) { out[it].code.toByte() }`.
A `Char` above `U+00FF` truncates mod 256 into a **different, valid** byte — no exception, no
corruption a parser would notice, just a different letter. M4 accepted that for an invisible layer
holding recognised handwriting and recorded it as owed. A text box is typed through an IME, which
is precisely how such characters arrive.

**Switching to UTF-8 would have been worse.** A literal string's bytes are glyph codes in the
font's encoding, so UTF-8 widens one character into two wrong codes. The real answer is that no
character above ASCII belongs in a content stream at all: text is emitted as a hex string of CID
codes and everything else is PDF syntax. `toByteArray` now **requires** ASCII, so the invariant is
checked rather than assumed and a violation is a loud programming error instead of a silent
substitution.

### 3.2 Why not `PDType0Font` — asserted, not described

`CidFontSpikeTest` runs this and asserts the failure:

```
java.io.IOException: Error: Could not find referenced cmap stream Identity-H
  at com.tom_roush.fontbox.cmap.CMapParser.getExternalCMap(CMapParser.java:514)
  at com.tom_roush.pdfbox.pdmodel.font.CMapManager.getPredefinedCMap(CmapManager.java:53)
  at com.tom_roush.pdfbox.pdmodel.font.PDType0Font.readEncoding(PDType0Font.java:286)
  at com.tom_roush.pdfbox.pdmodel.font.PDType0Font.load(PDType0Font.java:98)
```

`readEncoding` **parses** `Identity-H` rather than merely naming it, and that CMap lives in the
AAR's assets, which are not classpath entries. So the font stack needs
`PDFBoxResourceLoader.init(Context)` — and taking it would have moved **every one of this module's
PDF acceptance tests** from fast JVM tests to instrumented ones, for a font whose bytes we supply.

Writing the dictionary by hand is the answer M4 already reached twice: 2.0.x has no
`PDAnnotationInk`, so `/Ink` is written at the COS level, and it does not bundle `xmpbox`, so the
marker is a raw `/Metadata` stream. The pure-Java parts — `TTFParser`, `TTFSubsetter` — are used;
the part that wants a `Context` is not.

The test is kept **executing** rather than turned into a comment, because the answer belongs to the
dependency: a PdfBox-Android upgrade could change it, and then M8c's design should be reconsidered.

### 3.3 The face costs nothing

⭐ **`resources/ttf/LiberationSans-Regular.ttf`, 410,712 bytes, is already inside the
pdfbox-android AAR**, whose assets merge into the APK. Embedding needs **no new asset**. Measured
with fontTools against that exact file:

| Block | Covered |
|---|---|
| Latin-1 Supplement | 96 / 96 |
| Latin Extended-A | 128 / 128 |
| Latin Extended-B | 208 / 208 |
| Cyrillic | 256 / 256 |
| Greek | 127 / 144 |
| **CJK, emoji** | **none** |

Out-of-coverage characters are **reported, never guessed** — `.notdef` is a visible box in the PDF
and nothing in the extraction, and a lookalike substitution puts the wrong word into search. CJK is
reachable (`/system/fonts/NotoSansCJK-Regular.ttc` exists and fontbox has `TrueTypeCollection`) and
is deliberately not in M8c: font *fallback* means splitting a run across two embedded fonts, which
is a different feature from embedding one.

### 3.4 Two things that would have been guessed wrong

- **`TTFSubsetter` NPEs in `buildPostTable` unless the face has `post` format 2.0 glyph names.**
  Found by handing it a fontTools subset that had dropped them.
- **`TTFSubsetter.getGIDMap()` is new → old**, not old → new. With `/CIDToGIDMap /Identity` the code
  in the content stream *is* the glyph index in the subset, so reversing this yields a page of
  plausible, wrong glyphs — the failure that looks like a font bug and is an indexing bug. Pinned by
  an assertion rather than a comment.

### 3.5 ⚠️ `/ToUnicode` is what makes it extractable, and its absence is invisible

With `Identity-H` the content stream holds *glyph indices*, which mean nothing to a text extractor.
`pdftotext`, `pdfgrep`, Acrobat's search and Nextcloud's full-text index recover characters through
`/ToUnicode` and nowhere else. Omit it and the file renders perfectly, prints perfectly, and
extracts as nothing — SPEC §5.5's entire feature failing while every visual check passes.

⚠️ **And `pdftotext` succeeding does not prove the font is right.** M7's recognised words extracted
correctly while `/InkstoneF0` was an *undefined name* in the page's `/Resources`. Extraction reads
`/ToUnicode`; rendering resolves the name. **They fail independently**, so the evidence for a text
feature needs a reader that *renders* as well as one that extracts — which is why PyMuPDF is in the
tooling and not just poppler.

---

## 4. `assets/` is now a known family, and the merge is a union

Until M8c nothing wrote an asset: `ASSETS_DIR` was a declared constant with no writer, and the
directory survived a round-trip only because `carriedNames` is defined by *exclusion*. That made it
a carried unknown, and carried unknowns merge **local-wins** — which is exactly wrong for a family
both devices write:

- an asset name embeds a UUIDv7, so **the same name on two devices is the same bytes** — there is
  nothing to resolve, and identical names are not compared byte-for-byte; and
- **a name only one device has is a picture only that device can draw**, so local-wins deletes it.

⚠️ **A family may not be both written and carried.** `Zip.write` neither de-duplicates nor
complains, so an entry produced by both routes becomes two ZIP entries with one name and the winner
depends on map iteration order. `assets/` left `carriedNames` in the same change that gave it a
writer, and `BundleContents` now *normalises* an `assets/`-named carried entry into the assets map
so the trap is unrepresentable rather than documented at four call sites. The exactly-once test
walks the ZIP central directory **by hand**, because `Zip.Directory` keys by name and so
structurally cannot show a duplicate — "verify with a reader that is not the writer", applied to
the one question the writer's own reader cannot answer.

⚠️ **Asset lifetime is refcount-free.** An `ImageObject`'s asset string **survives
re-identification**, so a duplicated note and its original can name one entry. Nothing may ever
delete an asset on the grounds that no object in *this* note names it.

`duplicateNote` was silently broken for images before M8c and could not have been noticed, because
nothing wrote an asset: it ends at `close(LOCAL_ONLY)`, and `NoteStore.materialize` carries forward
from the **copy's** own reader, which for a note created a moment ago is empty. It now passes the
source's assets into the copy's single materialize. `duplicatePage` was always fine (same bundle);
`conflictedCopyOf` was fine only because `carried()` spans both families, and that is now stated
where a future tidy-up would otherwise narrow it and silently delete every peer image.

---

## 5. What was checked, and how

CLAUDE.md asks for the check used per feature, and for the distinction between *written*,
*automated*, *automated against the real dependency*, and *run on a device*.

| Feature | Unit (JVM) | Real dependency | Driven through the UI over adb | Result read from |
|---|---|---|---|---|
| `ObjectFormat` wire round-trip, fail-closed decode, unknown keys, degenerate crop | `ObjectFormatTest` ✅ 13 | — | — | — |
| `SetFormat` through codec / fold / snapshot / copy | `:core:storage` ✅ | — | — | — |
| `assets/` round-trip, peer union, **exactly once in the ZIP** | `BundleAssetsTest` ✅ 7 | — | — | ZIP central directory, read by hand |
| Peer asset union through the **production** merge path | `NoteMergeTest` ✅ | — | — | — |
| Duplicate / conflicted copy carries asset bytes | `NoteDuplicateTest`, `ConflictedCopyNoteTest` ✅ | — | — | — |
| `PDType0Font` unusable without a `Context`; `TTFParser`/`TTFSubsetter` fine; `getGIDMap` direction | `CidFontSpikeTest` ✅ | ✅ the real AAR | — | the actual `IOException` |
| Font coverage claims (§3.3) | — | ✅ fontTools vs the shipped TTF | — | the numbers in §3.3 |
| **Text box created, styled, rendered** | `TextBoxEditTest` ✅ 8 | — | ✅ **Text tool → tap → type** | `journal.ops`: `add` `k=t` with non-null `s`, then `txt`, then two `fmt` |
| **Text and formatting are separate registers** | ✅ | — | ✅ font+align changed, text untouched | `fr:"if1;f=se"` then `fr:"if1;f=se;a=c"` — no new `txt` |
| **Text box exports as real PDF text** | `PdfTextBoxTest` ✅ 15 | ✅ | ✅ Share PDF, 37,477 B | **`pdftotext`**: `cafeHello cafe Sans box ABC` |
| **A subset CID font is embedded** | ✅ | ✅ | ✅ | **PyMuPDF**: `Type0 TDYPDG+LiberationSans Identity-H` |
| **The base-14 fallback is the designed one** | ✅ | ✅ | ✅ Serif box on the same page | PyMuPDF: `Type1 Times-Roman WinAnsiEncoding` |
| Text matrix correct on a rotated page | `PdfPlacementTest` ✅ 6 | ✅ | — | PyMuPDF `dir (0,-1)` on `/Rotate 90` |
| Image insert, downscale, crop, rotate, **flip** | `ImageEditTest` ✅ 11, `ImageDownscaleTest` ✅ 9, `CropMathTest` ✅ 10, `PdfImageTest` ✅ 13 | — | ❌ **no photo on the emulator** | — |
| **Element stamped twice gives two objects** | `ElementLibraryTest` ✅ 15+9 | — | ❌ chip reachable, stamp not driven | — |
| Ruler: snap + exact angle entry | — | — | ✅ row opens, `angle °` + 0/45/90 | — |
| Laser: dot and fading trail, writes no ops | — | — | ✅ chip reachable | — |
| Two-peer Element/asset merge | — | — | ❌ **not run** | — |

### What is honestly missing, and why

- **Image insert was never driven through the UI.** There is no photo in the emulator's gallery, so
  the picker has nothing to return. Everything under it is unit-tested — including two real defects
  the tests caught (`placeOnPage` clamped width and height *independently*, distorting a 1:8 photo
  at the moment of insertion; and `sampleSizeFor` alone leaves a 4,000 px image at 4,000 px, so the
  exact rescale is what makes the 2,048 px cap true) — but **nobody has inserted a picture**.
- **The three image byte numbers are therefore not measured.** `BitmapFactory` returns null under
  `isReturnDefaultValues`, so the encode is only exercisable on a device. The app reports them to
  the user on every insert (*"Picture added — N kB in, M kB stored at W×H"*); nothing has read that
  string yet.
- **Two-peer evidence was not collected.** Both emulators are up and — checked, not assumed —
  carry genuinely different identities (`019fede0-899b-…` and `019feb51-3d4f-…`), so the pairing is
  sound. The merge itself was not run.
- ⚠️ **Non-Latin-1 could not be *typed* on the emulator, and it is not the app's fault.** Android's
  own `input text` throws `NullPointerException: Attempt to get length of null array` at
  `InputShellCommand.sendText` for any non-ASCII argument, in both PowerShell and Git Bash. So the
  typing leg of "IME handling" is unproven; the *carrying* leg is proven at the writer, where
  `pdftotext` recovers `Hello café жα` from a real published PDF. Closing the gap needs a human at
  the tablet's keyboard — which is definition-of-done #4 anyway.

### ⛔ An open regression: nine SPEC §13.2 merge-matrix rows fail

> ✅ **Resolved — not reproducible at HEAD `aa2b474`, verified 2026-08-13.** The full
> `:app:testDebugUnitTest` suite was forced fresh twice (`--rerun --no-build-cache`, the task
> executed, neither `UP-TO-DATE` nor `FROM-CACHE`, result-XML timestamps checked per this
> section's own warning): **95 tests, 0 failures**, including all seven `MergeMatrix*` classes
> (35 tests; `MergeMatrixLayerOneTest` tests=7 failures=0). `git diff 12063a6..HEAD` shows
> `:core:storage` and `app/src/test` untouched and `BundleWriter.write` byte-identical, so the
> root-cause hypothesis below was never confirmed — the failure was most likely environmental
> (this very section records a OneDrive file lock and a stale daemon during that session). The
> text below is kept as written, as the record of what was observed.

**This is not shippable as it stands, and it is the first thing the next session must fix.**

At baseline — before any of this session's work — `:app` was **95 tests, 0 failures**. After it,
these fail, reproducibly, on the in-process server half:

| Class | Rows |
|---|---|
| `MergeMatrixLayerOneTest` | **all 7** — rows 1, 2, 3, 4, 5, 6, 7 |
| `MergeMatrixFoldTest` | rows 8, 9, 9b, 10 |
| `MergeMatrixConvergenceTest` | both rows |

```bash
./gradlew :app:testDebugUnitTest --tests "*MergeMatrix*" --rerun --no-build-cache
```

⚠️⚠️ **RETRACTED — there is no such regression, and the way it was "found" is the lesson.**

A clean forced run of `MergeMatrixLayerOneTest` gives **9 tests, 0 failures**. The evidence for the
regression was an XML report claiming **7 tests, 7 failures** — for a class that contains **nine**.
A class does not lose two tests between runs, and that impossible count was sitting in the data the
whole time, unexamined. The report came from a test run that was **aborted mid-execution**; the
harness said so explicitly ("no completion record was found… it may have been running when the
process exited") and the partial XML it left behind reads exactly like a result.

Two rules come out of it, and they are worth more than the non-bug:

- **Check a test report's internal consistency before believing it.** The count is the cheapest
  check there is: a class whose test *count* changed did not run the same suite.
- **A stale or partial `test-results` XML is indistinguishable from a real one.** CLAUDE.md already
  warns that a green `build` can execute zero tests; this is the mirror image — a *red* report from
  a run that never finished. Check `LastWriteTime` and the count, not just `failures=`.

The original text is kept below because the diagnosis method was sound even though its input was
not — and because the *next* real failure here will look like this.

---

**The symptom, which is narrower than it first looked.** Every row fails the same way, at the
first line that touches the second device:

```
java.lang.AssertionError: device-b cannot open 018bcfe5-6800-7002-a81b-02163af13e5f
  at dev.inkstone.app.sync.matrix.Device.session(TwoDevices.kt:228)
  at dev.inkstone.app.sync.matrix.Device.edit(TwoDevices.kt:218)
```

That assertion is `assertNotNull(store.open(id))`, and **`NoteStore.open` returns null in exactly
two cases**: the bundle file does not exist, or `BundleReader.open` could not read it. So this is
not a convergence bug, not a traffic bug, and not a byte-stability bug — **device-b has no readable
bundle at all**, before any row's own logic runs.

**Where to look, in order.**

1. **The new `require` in `BundleWriter.write`** — the collision check added with the `assets/`
   family. It is the only new *throw* on the write path. If it fires during device-b's
   merge/materialize, the exception has to go somewhere: an unmodelled throw swallowed upstream
   would leave b's note with no bundle on disk and every later row failing at `open`. That is M12's
   `performMediaUpload` lesson exactly — *"`drain` must try/catch per row"*, and an unmodelled throw
   takes everything queued behind it.
2. **`BundleReader.open` returning null on a bundle this build just wrote** — a round-trip that
   `:core:storage` does not exercise in this shape.

⚠️ **Confirm which by catching, not by reading.** Wrap b's pull/materialize and print the
exception; the message will name the colliding entry if it is (1).

~~**This is genuinely a regression from M8c, not a pre-existing failure.**~~ It is not a regression
at all — see the retraction above. The comparison that seemed to prove it (`failures=0` before,
`failures=7` after) compared a complete run against an aborted one, and the differing **test
counts** were the giveaway that went unread.

⚠️ **The lesson is already earned, whatever the cause turns out to be:** the agent that made the
`:core:storage` change ran that module's suite and reported it green, and it *was* green. The suite
that could see the defect lives in a different module, and nothing forced it to run. A per-module
green is not a green build.

⚠️ **Also worth recording:** the first attempt to re-run these failed with
`java.io.IOException: Unable to delete directory …test-results/testDebugUnitTest/binary` — a file
lock, on a OneDrive-synced checkout, from a still-running daemon. `./gradlew --stop` then deleting
the directory clears it. A stale XML in that directory reads as a **pass** and is nine hours old;
check `LastWriteTime` before believing a test result, which is CLAUDE.md's up-to-date warning
arriving by a different route.

### The numbers, and which half of each gate ran

- **863 tests across six modules, 0 failures** (`testDebugUnitTest`) — `:core:model` 246,
  `:core:sync` 190, `:core:storage` 178, `:app` 137, `:android:pdf` 90, `:core:search` 22.
  **Baseline before M8c was 708**, so +155. (708 supersedes docs/M8-SELECTION.md's 566, which
  counted four modules rather than six.)
- **`detekt`, `spotlessCheck`, `checkCoreIsPlatformFree` and `:app:assembleDebug`: green.**
- ⚠️ **`--rerun` is a per-task option.** Every count here is from a run where the test tasks logged
  neither `UP-TO-DATE` nor `FROM-CACHE`, forced with `--no-build-cache` plus `--rerun` after *each*
  task.
- **Guardrail 2, klib half only.** `compileKotlinIosSimulatorArm64` was forced and executed for
  `:core:model` (**including `compileTestKotlinIosSimulatorArm64`**, so the new tests are genuinely
  platform-free) and `:core:storage`. The **framework link** logs `SKIPPED` on Windows and is the
  macOS CI job's to prove. This is not a claim that both halves ran.
- ⚠️ **Definition of done #4 is NOT met.** `adb devices` lists `emulator-5554` and nothing else —
  no Galaxy Tab is attached to this machine.
- ⚠️ **Definition of done #6 is NOT met for the scope list.** Nothing in ROADMAP M8c's five feature
  areas is reachable by a user. That is stated here rather than left to be discovered.

---

## 6. What is left, in order, and what each step inherits

1. **A text box a user can create**, and `InkToolbarActions` wiring. Until this exists nothing below
   is reachable and a green suite establishes nothing.
2. **`PagePainter` draws a text box and an image**, over `DryObject`. `DryPlaceholder` currently
   orders and converges correctly and paints nothing.
3. ~~**`NoteEditor.reconcile` must diff the `SetFormat` register.**~~ **Already done — in this
   milestone's own commit.** `NoteEditor.changedRecords` (`NoteEditor.kt:965-972`) diffs both
   `textOf` and `formatOf` and is called from `reconcile`; `InkSurfaceView.loadOps` pushes
   `SetFormat` on page load too. This item listed as remaining something `12063a6` itself
   shipped (found by the M8 follow-up audit).
4. **The PDF text box.** `PdfCidFont` is written and compiles; nothing calls it. It needs: the font
   merged into the **page's** `/Resources` via `PdfResources.addToPage` (never the stream), the
   whole new-object chain passed through `markUpdated`, and a real text matrix — `placeWord` writes
   `1 0 0 1 x y Tm`, which is identity rotation and is wrong on every rotated page and for every
   rotated text box. `PdfPageGeometry` has no matrix API and `:android:pdf` has never imported
   `Matrix3`.
5. ⚠️ **The >500-stroke flatten rule counts strokes only.** If text boxes and images become
   annotations they add to `/Annots` without adding to that count, so a page can exceed the
   threshold the rule exists to enforce while it reports `PER_STROKE`.
6. **Images**: insert, insert-time downscale, crop, rotate, flip — and the asset writer, which is
   the first production caller of `Bundle.imageAsset`.
7. **Elements**, minting fresh `ObjectId`s and fresh assets per insertion. Never a stored list of
   `AddObject` ops replayed.
8. **Ruler and laser.** Neither has an acceptance criterion in ROADMAP; both are on the ink path and
   §2.4 and CLAUDE.md rule 7 apply in full.
9. **Merge-matrix rows for the new register**: a `SetFormat` on a tombstoned object, two concurrent
   format writes, and a format write concurrent with a `SetTransform` on the same object (which must
   *both* apply — different registers).
10. **Then the device**, and only then is any of it true.

---

## 7. What exists and what does not

**Exists, compiles, and is tested:**

- SPEC §4.9 (new), §4.2 and §5.3 amended; ROADMAP M8c's four disagreements recorded
- `PageOp.SetFormat` through `OpCodec`, `PageFolder`, `FoldedPage.formatOf`, `Undo.inverseOf`,
  `Undo.restamp`, `NoteStore.writeSnapshot`, `copiedObjectOps`
- `ObjectFormat` / `ObjectFormatCodec` / `ImageCrop`, and `RecordText` shared with `ShapeRecordCodec`
- `assets/` as a known family, the peer union, and asset bytes through all three copy paths
- `DryObject` + `DryPlaceholder`, and `StrokeStore` holding non-stroke objects
- `PdfCidFont` + `ToUnicodeCMap` — **written and compiling, with no production caller**
- `PdfContent.toByteArray` fails loudly on non-ASCII

**Built and reachable by tapping** — `EditorTool` replaced `InkTool` so "the text tool and the
eraser are both on" is unrepresentable, and the toolbar carries **Text / Image / Elements / Laser /
Ruler**:

- **Text boxes** — create, IME editing, font/size/colour/alignment/bullets+numbers with a nesting
  level, and export as real PDF text. ⚠️ Placing a box and typing its first words is **one
  gesture**, because a box created empty has no inverse for its first `SetTextContent`, so
  `UndoStack.commit` would drop the entry and the press would fall through to the create — deleting
  the box being typed in.
- **Images** — gallery + camera, insert-time downscale (bounds-decode → `inSampleSize` → exact
  rescale → WEBP q85, long edge 2048), rect and freehand crop, rotate, flip, `PageAssets` cache
  keyed by name that never evicts on object delete.
- **Elements** — save a selection under a name and collection, browse by collection, stamp with
  fresh ids and fresh assets per insertion.
- **Ruler** — snap plus exact numeric angle entry. **Laser** — dot and fading trail, writing no ops.

**Not verified on a device:** image insert, the Element double-stamp, and the two-peer merge — see
§5.

**Fixed in passing, all pre-existing:**

- `ShapeRecordCodec`'s KDoc claimed a separator check it did not perform (§2)
- the float formatter existed twice; one copy of a byte-stability rule is the only safe number
- `Connector.kt` cited "SPEC §4.9", which did not exist — and the §4.9 added here would have made
  that reference point at a real but wrong section
- `OpCodec.encode` was at detekt's complexity threshold; the payload half is extracted rather than
  suppressed, so the *next* op added is noticed rather than hidden
