# M7 — handwriting recognition and search

What was built, what was measured, and the three things SPEC.md got wrong.

---

## 1. The headline number

**ROADMAP M7:** *"Line segmentation measurably beats whole-page-as-one-blob on a 20-page sample —
show both numbers."*

| Arm | `Ink` objects | `WritingArea` | **CER** ↓ | **Word recall** ↑ |
|---|---|---|---|---|
| **`segmented`** — what Inkstone ships | one per line | the line | **0.272** | **0.714** |
| `seg-drawn-order` | one per line | the line | 0.300 | 0.714 |
| `seg-x-order` | one per line | the line | 0.446 | 0.512 |
| `page-blob` — the naive implementation | one per page | the whole A4 page | **0.848** | **0.071** |
| `inkbox-blob` — the *charitable* baseline | one per page | the ink's bounding box | 0.741 | 0.165 |

**Segmenting cuts character error from 0.848 to 0.272 (3.1× lower) and takes word recall from
7.1% to 71.4% (10× more words findable).**

`inkbox-blob` exists so the comparison cannot be dismissed as a strawman: a page-sized writing
area around half-empty paper is a bad deal that a careful implementer might avoid without ever
thinking about lines. It is the best that implementer could do, and it is still one blob, and it
still finds one word in six.

Word recall is the number this milestone is actually for. Search does not care whether the
punctuation came out right; it cares whether the word you type is in the index.

### The sample

**IAMonDo v1.0** (IAM Online Document Database) — real A4 handwriting captured with an Anoto pen,
with per-text-line ground-truth transcriptions in the same file. **20 pages from 20 different
writers**, taken one-page-per-writer from the corpus's own writer-disjoint set 0: 472 ground-truth
text lines, 5 323 strokes, 6 780 reference characters. Pages deliberately mix prose with tables,
formulas, diagrams and margin notes, which makes it the realistic adversary rather than a tidy
one.

⚠️ **The corpus is not in this repo and must not be.** IAMonDo is licensed for non-commercial
research and confers no redistribution right; only these aggregate numbers are committed.
`tools/m7-accuracy.ps1` fetches it, builds the sample and runs the harness.

> E. Indermühle, M. Liwicki, H. Bunke, *"IAMonDo-database: an Online Handwritten Document Database
> with Non-uniform Contents"*, Proc. DAS, pp. 97–104, 2010.

### Three traps in the corpus, each of which produces a plausible wrong answer

1. **InkML trace values are difference-encoded, and the mode is sticky per channel.** A `'`
   prefix means first difference, `"` second difference, and an *unprefixed* value continues in
   whatever mode that channel was last put into. Read as absolutes they decode to noise that
   still looks like a trajectory.
2. **The `canvasTransform` is per document.** 380 of the 941 documents declare
   `<mapping type="identity"/>`, most of the rest a 180° affine, and at least one a 90° one.
   Assuming a single rotation renders two fifths of the corpus upside down — and a recogniser
   handed upside-down handwriting returns confident nonsense, not an error. **Caught by drawing a
   page as a PNG and looking at it**, after the first measurement came back at CER 0.626 for the
   segmented arm with no explanation. Fixing it moved the segmented arm to 0.272.
3. **The ground truth's line order is `traceView` order, which groups by `Textblock`** — so on a
   page with a side note it is not top-to-bottom, and disagrees with page order on 104 of 452
   adjacent pairs. The reference is re-sorted top-to-bottom and *both* arms are scored against
   the same string, so neither is charged for the corpus's structure.

### Reproducing

```bash
tools/m7-accuracy.ps1
```

The JVM half needs no device and runs in ~70 s — which is what made the segmenter tunable at all:

```bash
INKSTONE_M7_SAMPLE=<sample.json> ./gradlew :app:testDebugUnitTest --tests '*LineSegmenterCorpusTest'
```

---

## 2. `pdfgrep` — the hard criterion

**ROADMAP M7:** *"`pdfgrep` finds handwritten words in an exported PDF."*

`pdfgrep` is not installable on this machine (no package in winget, and WSL's VM platform is
disabled), so the check was run with **two readers that share no code with the writer**, one of
which is the engine `pdfgrep` is built on:

| Reader | Version | Result |
|---|---|---|
| **Xpdf `pdftotext`** — `pdfgrep` is this plus a regex | 4.00 | `handwriting searchable` |
| **`pypdf`** — an independent Python implementation | 6.15.0 | `handwriting searchable` |

Run against **the app's own `document.pdf`**, pulled off the emulator after drawing with a stylus
and letting recognition run:

```
$ pdftotext app-note.pdf - | grep -c "IIII"
1
```

That is the whole chain: stylus → ink → recognition → `text/<pageId>.json` → published PDF →
extracted by a stranger's parser.

⚠️ **Re-parsing with PdfBox would have proved nothing**, and M4 has the scar: the font for the
text layer was on the *content stream* rather than the *page*, so `/InkstoneF0 Tf` was an
undefined name in every file this app had ever published — and `pdftotext` still found the words,
because Xpdf resolves what it needs without the font being defined. The bug was invisible from
both sides. `InvisibleTextLayerTest` now asserts the font is resolvable **through the page
dictionary**, which is how a viewer looks for it.

*(Nextcloud full-text search was not tested: it needs the `fulltextsearch` app plus
Elasticsearch/OpenSearch plus a PDF extractor, none of which is in a default install. ROADMAP
marks it optional and environment-dependent.)*

---

## 3. Where SPEC.md was wrong

### 3.1 SPEC §8.4 said FTS5. Android has no FTS5

Android's SQLite is built with `SQLITE_ENABLE_FTS3` and `FTS4` and **not** `FTS5` — API 35,
SQLite 3.44.3, four years after FTS5 landed upstream. Found by scanning
`/system/lib64/libsqlite.so` (`fts3` and `fts4` present; `fts5`, `bm25` and `highlight` all zero
occurrences) and then **proved at runtime** by `FtsAvailabilityTest`, which creates the virtual
table on a real device and asserts the failure. Corroborated by Room shipping `@Fts3`/`@Fts4`
annotations and no `@Fts5`.

⚠️ **iOS's SQLite *does* have FTS5.** A `:core:*` module using it would have compiled, linked,
passed on the simulator and failed on every Android device — the exact shape of failure Guardrail
2 exists to catch, pointing the other way.

**But the deciding reason is the second one:** SPEC §8.4 also requires every hit to carry a
page-space box so a tap can scroll to the word. No FTS module of any version stores geometry, so
a per-word table has to exist regardless — and once it does, it answers word and prefix queries
by itself. An FTS table beside it would be a second copy of the same text kept in step by hand,
earning ranking. One table, one index, a prefix range scan.

### 3.2 SPEC §8.2 step 3: "strokes left-to-right within" a line

Right for laying words out, wrong for feeding the recogniser — a `t` is crossed after its word is
finished. Word recall 0.378 in draw order against 0.313 in x order. Only the lines are ordered
spatially now.

### 3.3 SPEC §8.2 step 2: overlap is not enough on its own

Baseline proximity is the other half, and without it tightly-spaced lines merge: 304 lines found
where there were 472. SPEC always said "vertical overlap **and** baseline proximity"; the first
implementation used only the first, and 20 real pages said so.

### 3.4 The ML Kit package is not what the documentation says

`com.google.mlkit.vision.digitalink.**recognition**.Ink`, not
`com.google.mlkit.vision.digitalink.Ink`. Google's own guide and every tutorial written from it
import the latter, which does not exist in 19.0.0; the shared result types live in a third
package (`…digitalink.common`) shipped in a separate artifact. Every signature in `:android:ocr`
was read off the AAR with `javap`.

Also: `WritingArea` is **width and height only — it has no origin**, so the ink has to be
translated so the line's own top-left is at zero. Passing page coordinates compiles and returns
plausible-looking text.

---

## 4. SPEC §5.5's prerequisite, discharged

M6 recorded it and correctly left it: `NoteStore.materialize` unions carried bundle entries
**local-wins**, which is symmetric, so an entry name *both* devices produce keeps a different
value on each and never converges. Nothing produced such a name — until `text/<pageId>.json`,
which is named per page.

**`text/` is now a known entry family**: written from `PageContents.text`, excluded from
`carriedNames`. ⚠️ Both halves matter — `Zip.write` neither de-duplicates nor complains, so
leaving it in `carriedNames` as well would append two entries with one name and let map iteration
order decide which one a reader sees.

**The rule is content-keyed, not clocked.** A Lamport clock would order the two writes and answer
the wrong question: after a merge, *both* devices' text usually describes ink that no longer
exists, so the newer one is just a more recent wrong answer. Each file carries an `inkDigest`
over the ink it was read from, and `PageTextMerge.choose` keeps whichever candidate still
describes the merged page, dropping both when neither does. Converges in one step, no clock, no
`deviceId` tiebreak.

The same field detects *local* staleness for free: erase a word and the digest moves, so the text
stops being served to search and to the PDF immediately rather than at whatever point recognition
is next scheduled.

---

## 5. Degrading — ROADMAP M7's other hard criterion

*"Delete the language model → app works fully, search degrades to titles, message is clear."*

`RecognitionOutcome` makes *"there is no recogniser"* an ordinary value every caller must handle,
rather than an exception or a silence. Verified in the app on a fresh install, before any model
was downloaded:

> Searching titles and folders only — handwriting recognition needs a language model.
> **Download English (~20 MB) · ML Kit sends anonymous usage metrics to Google**

Shown only while searching — an answer to a question the user just asked, not a banner. The size
and SPEC §8.1's metrics disclosure are on the button rather than behind it.

⚠️ **Nothing deletes previously recognised text when the model goes missing.** A model that has
been removed is not evidence that the words it produced were wrong (CLAUDE.md rule 1).

---

## 6. What ran, and on what

Per CLAUDE.md's definition of done #6, each feature says **which** check reached it.

| Claim | Evidence | Level |
|---|---|---|
| Line segmentation groups a page into lines | `LineSegmenterTest` (14) + `LineSegmenterCorpusTest` over 20 real pages | automated, real data |
| …and beats one blob | `SegmentationAccuracyTest`, real ML Kit, emulator | **run on a device** |
| No FTS5 on Android | `FtsAvailabilityTest` | **run on a device** (both AVDs) |
| `text/` is a known bundle entry, not carried | `PageTextMergeTest` + the bundle pulled off the device | automated + device |
| Two devices converge on one page's text | `PageTextMergeTest` — including the symmetry test the old rule fails | automated |
| Invisible text layer is written and extractable | `InvisibleTextLayerTest` + Xpdf + pypdf on the app's own PDF | **run on a device** |
| Recognition is reachable from the app | drawn with `adb shell input stylus`, read out of `pageWord` with `run-as` | **driven over adb** |
| Search finds handwriting and jumps to it | typed `III` in the app, tapped the hit, screenshotted the highlight | **driven over adb** |
| Model-absent degradation | fresh install, searched, read the offer off the screen | **driven over adb** |
| Schema migration to v7 | `PRAGMA user_version` = 7 on the device after upgrade | **run on a device** |

**Not done: the Galaxy Tab.** Definition of done #4 is unmet — everything above is an x86_64
emulator. Ink feel, latency and frame timing are unmeasured, and `:android:ink`'s instrumented
suite still fails 10 of 15 on x86_64 for M1's reasons.

**JVM suite: 592 tests per variant, 0 failures** (`testDebugUnitTest`, forced with
`--rerun-tasks`; M6 shipped 539). `./gradlew build` is green including `checkCoreIsPlatformFree`
and the iOS **klib compile** — the macOS framework link is `SKIPPED` on Windows, as always.

⚠️ Guardrail 2 earned its keep this milestone: `SqlSearchIndex` used `Map.putIfAbsent`, which is
JVM-only. It compiled for Android and failed `compileKotlinIosArm64`.

---

## 7. Known gaps

- **No Galaxy Tab run.** Definition of done #4.
- **`RecognitionWorker`'s periodic registration has never actually fired** — it is constrained to
  charging *and* idle, which an emulator driven over adb never is. The one-shot path
  (`enqueueRecognition`) is what the device verification exercised, and it is the same `doWork`.
- **Only `en-US` is offered from the UI.** `MlKitModels.available()` lists ten languages and
  nothing renders that list yet; a second language needs a picker, not new plumbing.
- **Multi-column pages are skipped, not read.** SPEC §8.2 says to, and IAMonDo's own two-block
  pages are why the sample's reference had to be re-sorted. Reading them properly is a real
  feature and not this milestone's.
- **`WordBoxes` falls back to apportioning a line's box by character count** when the gap-derived
  word count disagrees with the recogniser's. The box is then within a word or two along the
  right line rather than exact.
