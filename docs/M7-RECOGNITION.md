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

### 5.1 After the press (2026-10-13)

*"I pressed download and I think it worked but there was no real way of knowing."* The whole of the
feedback had been the chip's label changing to *Downloading…* and then the panel **vanishing** —
which is also what it did when nothing happened — and the next search still found only titles,
because the library had not been read yet. *My handwriting*'s chip had no feedback at all, and no
size or metrics line in front of it either. Three more things were wrong underneath:

- **The download ran in `LibraryViewModel`'s `viewModelScope`.** ML Kit's transfer survives the
  activity; the step after it — queueing a read of every note — did not, so backing out mid-download
  left the library unread until the periodic *charging and idle* batch.
- **Offline, ML Kit waits instead of failing.** Measured on Test_Tablet in airplane mode: the request
  stayed pending for over three minutes and completed 45 s after airplane mode was switched off. A moving bar
  over that is a promise of progress that is not happening.
- **An indeterminate bar draws nothing with animations off** (*Remove animations*, and every AVD
  this repo uses, which run at animator scale 0) — only its track, and `Ink.SegmentTrack` over a
  panel composited to (30,30,33) on (24,24,27).

What it does now — one panel (`RecognitionModelPanel`) on both screens, driven by one process-wide
`RecognitionModelController`:

| State | Says | Shown in the library |
|---|---|---|
| `Offer` | the size and SPEC §8.1's metrics line, *Download English* | only while searching |
| `Offer(error)` | *Download failed: …*, *Try again*, *Dismiss* | always — it answers a press |
| `Downloading(waitingForNetwork = true)` | *Waiting for an internet connection*, no bar | always |
| `Downloading` | a moving bar on a visible track | always |
| `Reading(n, m)` | *✓ Model downloaded*, *reading n of m notes*, a filling bar | always |
| `Ready` | *✓ Handwriting search is ready*, what it read, *OK* | always, until OK |

The bar counts **the batch the download started**: one WorkManager job per note, all tagged with
the download's id, so a note drawn on or synced in meanwhile cannot move it backwards. Two details
make the count honest. The jobs are enqueued with `APPEND_OR_REPLACE` — `KEEP` drops a note whose
earlier job is still pending, and the bar would stop one short for ever. And the controller knows
how many it queued, so the frame between writing the rows and WorkManager reporting them reads
*0 of 23*, not *ready*. A batch found still running after a restart is followed; one that finished
while nobody watched is not announced again. *OK* clears it on both screens.

Walked on Test_Tablet through the app (the model deleted through ML Kit with the debug-only
`RecognitionModelReceiver`): offer only while searching → *Download English* in airplane mode →
*waiting for an internet connection*, on the library and on *My handwriting* → network on → 22:01:03
*downloading* → 22:01:30 *0 of 23* → 22:01:40 *21 of 23* → 22:02:30 *ready, every note had already
been read* (the pages were current) → *Learn now* enabled itself → *OK* in the library cleared both
screens. **Not seen:** the moving bar actually moving — the AVD's animator scale is 0 — and the
panel on the tablet, whose model was already downloaded.

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
| ⚠️ …ASCII words only, since M8c | a recognised word containing any character above printable ASCII is **skipped** from the PDF layer (`PdfContent.invisibleText`'s filter, M8 follow-up audit) rather than truncated (pre-M8c) or failing the whole publish (M8c before the filter). In-app search still finds it; carrying it in the PDF needs `PdfCidFont`, which has no caller yet | automated (`InvisibleTextLayerTest`) |
| Recognition is reachable from the app | drawn with `adb shell input stylus`, read out of `pageWord` with `run-as` | **driven over adb** |
| Search finds handwriting and jumps to it | typed `III` in the app, tapped the hit, screenshotted the highlight | **driven over adb** |
| Model-absent degradation | fresh install, searched, read the offer off the screen | **driven over adb** |
| Schema migration to v7 | `MigrationOnDeviceTest` — a v4 and a v5 database with real rows, upgraded | **run on a device** |
| `WordBoxes` places a word on its own ink | `WordBoxesTest` (8), including the t-crossing case | automated |
| The search index stores, replaces, scopes and escapes | `SqlSearchIndexTest` (8), real SQLite | **run on a device** |
| Recognition skips current pages and stops when unavailable | `NoteRecogniserTest` (8), real `NoteSession` | automated |

**Not done: the Galaxy Tab.** Definition of done #4 is unmet — everything above is an x86_64
emulator. Ink feel, latency and frame timing are unmeasured, and `:android:ink`'s instrumented
suite still fails 10 of 15 on x86_64 for M1's reasons.

**JVM suite: 608 tests per variant, 0 failures** (`testDebugUnitTest`, forced with
`--rerun-tasks --no-build-cache`; M6 shipped 539). `./gradlew build` is green including
`checkCoreIsPlatformFree` and the iOS **klib compile** — the macOS framework link is `SKIPPED` on
Windows, as always.

**Instrumented, all on `emulator-5554` after the milestone's code was final:** `:core:database`
**58 tests, 0 failures** (migration, library index, both sync-store suites, FTS availability,
search index); `:app` **10 tests, 0 failures** (crash safety, live page-meta, wet-ink merge);
`:android:ink` **15 tests, 10 failures — the documented baseline, unchanged**. Those ten are M1's
`SyntheticInk` orientations (nine) and one arm64-tuned float tolerance; none touches the render
path M7 modified. Counted rather than assumed, because "10 failures" and "10 *pre-existing*
failures" look identical in a summary line.

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
- ~~**Multi-column pages are skipped, not read.**~~ **Read since `LineSegmenter` version 2
  (2026-10).** Skipping them was worse than it looked: version 1's clustering had no horizontal
  limit, so a page it *failed* to recognise as having columns was read straight across the gutter,
  and a mind map it did recognise was not read at all. `docs/LINE-SEGMENTATION-V2.md`.
- **`WordBoxes` falls back to apportioning a line's box by character count** when the gap-derived
  word count disagrees with the recogniser's. The box is then within a word or two along the
  right line rather than exact.

### Two things the closing audit found in `WordBoxes`, both fixed

Writing the tests that should have existed during the milestone found the bugs that only tests
find — which is the argument for definition-of-done #2 stated once more, at this project's own
expense.

- **The line height was the *tallest* stroke, not the median.** One bracket, integral sign or
  ascender-plus-descender raised the word-gap threshold for the whole line by a third, merging
  words that were properly spaced — and only on lines containing a tall letter, which is the
  hardest kind of bug to notice. `LineSegmenter` already refuses exactly this one level up. Now a
  median in both places.
- **A constant's KDoc claimed a guarantee it did not provide.** `MIN_HEIGHT_PT` said it stopped a
  line of full stops breaking at every gap; it does not, because a full stop is far taller than
  the floor. The real protection is `assign`'s count-mismatch fallback. The comment now says what
  the constant does rather than what someone hoped it did.
