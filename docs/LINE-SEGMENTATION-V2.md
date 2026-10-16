# Line segmentation, version 2 — columns, mind maps, and the search hit that opened in the wrong place

2026-10-13. The user's request: *"fix the line splitting so search improves too."* It came from the
handwriting harvest's recall problem (`docs/HANDWRITING-SYNTHESIS.md` §16.3): 48 of the 109 lines it
met could not be split into words. Most of those were lines M7 had drawn across both columns of the
chemistry note.

Measured on the user's own notes, never on the IAMonDo sample, which is not on this machine and was
not downloaded (§7): two pages on the emulator first, then all 183 pages of the tablet's library
(§3.1).

## 1. What was wrong

**No horizontal limit in the clustering.** M7's first draft had one, measured in stroke heights, and
it split every line into its words (SPEC §8.2). So the limit was removed, and a gutter was looked for
only after a row existed. By then the damage was done:
- a line in one column joined the line beside it in the other;
- that line, at a different pitch, joined the next line of the first column;
- union-find chained the staircase down the page.

The chemistry note's lines were three and four lines tall, across both columns. Its text read like
*"synthes means mahi making and mating mating compounds or all the way down to the elements that
make it up electronegativity says hitch elements have have and priorit"*.

**A page it could tell had columns was skipped whole.** The physics note is a mind map. It came back
`MULTI_COLUMN`, with no lines, so none of its words could be searched or learned. That is the
*"M7 recognises nothing on the physics note"* of `HANDWRITING-SYNTHESIS.md` §16.4.

## 2. What version 2 does

`LineSegmenter.kt` has the steps in its KDoc. The changes, each found on one of the two pages:

1. **The horizontal limit is back, in the unit M7 said it must be in.** Two strokes join only within
   1.5 of the page's typical *line* height, which a first pass with no limit measures. That fixed the
   staircase. Then the first draft over-split, in half the lines it drew (413 single-stroke lines of
   807). It was over-splitting in three distinct ways, and each needed its own fix:
2. **Marks attach; they never link.** An i-dot, a bar, a subscript, a capital that missed the
   baseline: anything left on its own after clustering joins the line its centre is in, or the
   nearest line within one text scale above or half one below. Version 1 let a small mark join
   anything whose height it sat inside. Between two close lines, an i-dot sits inside a descender
   from above and an ascender from below, so one dot fused *"How to work out what"* with the line
   under it. Attaching is a leaf operation: a stroke joins one line, and boxes are measured before
   anything joins, so nothing joined can reach further.
3. **Big writing is measured against itself.** The baseline tolerance is 0.7 of the page's text
   scale *or* of the smaller stroke's height, whichever is larger. Headings three times the notes'
   size (*"reactions"*, *"CaCO3 → CaO + CO2"*) fell into fragments without it.
4. **Rotation, detected less wrongly.**
   - The run of lone strokes that lines up when the page is turned (a margin note) is held to the
     same gutter as a line. With no limit, the numerators, bars and denominators down a column of
     equations chained into one "margin note" 440 pt tall, and 263 strokes went unread.
   - A slanted line's slope is the median bottom of its right half of letters against its left
     half's, not its first stroke against its last. A serifed `I` measured first stroke to last has a
     slope of two. Groups under four letters or four text scales across are never called slanted.
5. **A line split at a descender is joined back.** Found on the tablet (§3.1). In *"Equations with
   squareds"* the `q` of *Eq* hangs too far below the `u` beside it, and the `E` is 26 pt from that
   `u`, past the 21 pt gutter. No pair of strokes joins the two halves, so the draft read *"Eq"* and
   *"uations"*, and *equations* could not be found. Groups of two or more strokes, side by side within
   the gutter, now join when their **median** baselines agree (`mergeNeighbours`). One descender cannot
   move a median.
   - A guard against joining writing of different sizes was tried and dropped. In a 16-merge sample,
     what it blocked was nearly all equations (*"P(B) = n(B)/n(S)"* with *"= 1/4"*), whose fractions
     give the halves different median heights.
   - The merge's own mistakes are labels side by side on one baseline. Big *"riding dolphine"* joined
     a smaller line beside it.
6. **A line's recognition context is the line above it in its own column**
   (`LineSegmenter.contextLineFor`), not the previous line in page order, which on a page with
   columns is the line beside it.

`LineSegmenter.VERSION` is 2 and is part of `MlKitRecogniser.id`. `NoteRecogniser` reads a page
again when its text was written under another id, so **every existing page is read once more**, in
the charging-and-idle batch. Each note is then rewritten and uploaded once.

## 3. How it was measured

On the emulator's copy of the user's notes (23 pages: the chemistry note, the physics note, and test
notes):
- **The ink, as the recogniser sees it:** `InkExportReceiver` (debug only) writes every page through
  the production `StrokeSampler`.
- **The lines:** `RealInkSegmentationReport` runs the real `LineSegmenter` on the JVM over that export
  and draws every page with its lines. A Python copy of the algorithm, kept in the session scratchpad
  and not committed, was used to try variants in seconds. Its line counts match the Kotlin to within
  one stroke.
- **What ML Kit reads from each line:** `LineReadReceiver` (debug only) reads a plan's lines one at a
  time with no context. The plan is either a file naming each line's strokes or the build's own
  segmenter. "Known words" are the 56 and 34 words read off the two pages' pictures by eye. A known
  word counts as found when a recognised token starts with it.

| | Lines | Lone strokes | Fused lines (> 1.8 line heights) | Strokes skipped as rotated |
|---|---|---|---|---|
| | Lines | Lone strokes | Fused lines (> 1.8 line heights) | Strokes skipped as rotated |
|---|---|---|---|---|
| v1, without its multi-column skip | 385 | 151 | 29 | 163 |
| v2, first draft | 807 | 413 | 2 | 275 |
| v2 before the merge (step 5) | 584 | 155 | 2 | 66 |
| **v2** | **571** | **155** | **2** | **57** |

Python metrics over the whole export; the Kotlin report agrees on the line counts. Without its skip,
v1's physics row is hypothetical. v1 skipped the physics page, so for search it had nothing.

| Known words ML Kit read | Physics (34) | Chemistry (56) |
|---|---|---|
| v1 as shipped | **0**, page skipped | 48 |
| v2, first draft | 26 | 48 |
| v2 before the merge | 27 | 49 |
| **v2** | **27** | **49** |

Recall per page barely moves on the chemistry note, and that is worth saying plainly. ML Kit reads a
fused line in stroke order, so the words of two columns read as one line still mostly come back. What
v1's fused lines got wrong was *where*. `WordBoxes` shares a fused line's box out across both columns,
so the hit highlight and the PDF's invisible text layer put the word in the wrong place. Version 2's
lines are the lines on the page, and so are its boxes. The first draft's lone strokes came back as
stray characters: 758 tokens on the physics page against 670.

**Through the production path:** `RecognitionWorker` forced on the emulator
(`cmd jobscheduler run -f -n androidx.work.systemjobscheduler dev.inkstone 2`), with context, as
shipped:
- the search index went from **393 words to 1,191**;
- the physics note went from **0 to 755**, the chemistry note from 335 to 360;
- `brightness` 0 → 1 hit, `resistance` 0 → 10, `synthesis` 0 → 1.

**The harvest**, `HandwritingWorker` forced the same way, against `HANDWRITING-SYNTHESIS.md` §16.5.
Run before the merge step, whose lines the emulator's stored text does not have (§7):

| | Before | After |
|---|---|---|
| Lines met | 109 | 562 |
| … split into the recogniser's word count | 61 | 255 |
| Words on those lines | 68 | 352 |
| Eligible | 22 | 224 |
| Both reads agree | 18 | 121 |
| … and steady | 2 | 19 |
| Learned | 2 | 19 |
| Letters kept | 5 | 35 |

Most of the gain is the physics note being read at all. Its miscount rate is high (307 of 562 lines
over the export) because so much of it is equations, whose fractions and subscripts do not split
into the recogniser's word count.

### 3.1 The tablet's whole library

The tablet's 44 notes, exported the same way: 183 pages and 33,274 strokes. There are no known words
for them, so this is the segmentation alone, Python metrics with the Kotlin report agreeing on the
line counts (3,082 against 3,081). "Possible word splits" are pairs of lines side by side on one band,
a letter's gap apart.

| | Lines | Lone strokes | Fused lines | Possible word splits | Rotated strokes | Strokes in lines |
|---|---|---|---|---|---|---|
| v1, without its multi-column skip | 2,502 | 985 | 135 | 32 | 1,173 | 31,820 |
| v2 before the merge | 3,218 | 567 | 67 | 84 | 424 | 32,571 |
| **v2** | **3,081** | **561** | **68** | **54** | **335** | **32,660** |

Half the fused lines are gone, and nearly three quarters of the strokes v1 skipped as rotated are read. The
pages were looked at as well as counted: a finance mind map reads node by node (*"Investing"*,
*"Pension fund"*, *"Types of bank account"*), and an equations page reads one line per equation.
Possible word splits are the one count v2 raised. Drawing them found step 5's case, and the merge
took them from 84 to 54. Widening the gutter instead (2.0 line heights: 43 splits) fused more lines
and skipped more strokes as rotated.

⚠️ **The tablet's search index held no handwritten word at all, under either version.** The ML Kit
model was never downloaded there: the emulator has `files/mlkit_digital_ink_recognition`, and the
tablet has none. So on the user's own device, handwriting search has only ever found titles. The
download is the user's to start from the library screen, and SPEC §8.1 says why the app never
starts it on their behalf.

## 4. The search hit that opened in the wrong place

Found by the walk this work ended with: search *brightness*, tap the hit. **The right note opened at
its top, fitted to width, with the word off screen.** Tapping *synthesis* on the chemistry note did
the same.

The cause predates this work. `InkSurfaceView.highlight` centred the camera when the hit arrived, and
the hit arrives while its note is still opening. Both things that happen next refit the camera to the
page's width: the first layout, and the page load (`fitOrFrame`). They came second, every time.
`InkSurfaceView.highlight`'s own KDoc said a search hit *"is the one caller most likely to be early"*.

Now the surface holds the hit, and `fitOrFrame` ends by centring it. `SearchHitTracker` decides which
page the hit belongs to and when it goes. Before, nothing ever cleared it, so its amber box was drawn
at the same coordinates on every page turned to afterwards. Checked on the emulator: *synthesis*
opens centred on *"and in a synthesis reaction"*, *resistance* on *"internal resistanse using the
formula"*, each with the box on the word.

The `NoteSurface` interface is unchanged. `InkScreen.kt` carries another session's uncommitted work,
and the fix did not need to touch it.

### 4.1 …and on the wrong page (found on the tablet, 2026-10-13)

Both notes above are **one page long**, and so was the first hit tried on the tablet — the physics
mind-map, which opened centred on *"by the Source"*. The second hit, *Theoretical* in a 37-page
imported note, opened on **page 1** with the amber box centred on empty paper.

Two causes, one behind the other. `NoteEditorViewModel` asks for the hit's page from its `init`,
which is always before the note has opened, and `NoteEditor.showPage` went through `withPage` — which
reads `session` *before* it queues, so the turn was dropped. (The editor's own `init` says anything
arriving mid-open *"queues behind the open rather than concluding there is no session here"*; this
was the one path that did not.) And the box was sent straight after asking, so even a turn that
happened would have been too late: the surface gives a hit to the page it is first centred on
(`SearchHitTracker`), which was the page the note opened at, and the turn would then have cleared it.

Now `showPage` queues behind the open and returns its job; the view model waits for it and sends the
box only if the hit's page is the one on screen. A page no longer in the note (a stale index) leaves
the note where it is, and turning to the page already on screen no longer loads it twice.
`NoteEditorShowPageTest` reproduces the drop — two of its three tests fail with the old `showPage`
restored. Its third, the stale page, passed against the old code until it was moved to ask *after*
the open: asked mid-open, the old code dropped it anyway, which is the bug itself standing in for the
guard.

Checked on the tablet with the release build, by the same tap: *Theoretical* now opens on **page 29
of 37**, centred on *"m = 238g NH₃ Theoretical"* with the box on the word; *reaction* opens a two-page
note on page 1, centred on *"required for the reaction"*.

## 5. Per claim

| Claim | Written | Automated | Real dependency | Device |
|---|---|---|---|---|
| Columns are read, never across the gutter | ✓ | `LineSegmenterTest` | the user's two pages, real `LineSegmenter` | emulator: index and search |
| A line's context is the line above in its column | ✓ | ✓ | — | — (production reads with it; not measured apart) |
| A dot or mark never fuses two lines | ✓ | ✓, and fails under v1's rule | chemistry page | — |
| Marks and lone letters join their line | ✓ | ✓ | both pages | — |
| Big writing keeps its letters together | ✓ | ✓, and fails under v1's rule | chemistry page | — |
| Equations are not margin notes; a serifed `I` is not rotated | ✓ | ✓, both fail under v1's rules | physics page | — |
| A word split at a descender is joined back | ✓ | ✓, fails with the merge off | the tablet's 183 pages | — |
| Old text is read again under the new version | ✓ | — | — | emulator: physics 0 → 755 words |
| Search finds the physics note | — | — | — | emulator, through the library |
| A tapped hit is centred, and its box goes with its page | ✓ | `SearchHitTrackerTest` (when the box is drawn) | — | emulator, both notes (the centring) |
| A tapped hit opens on its own page, not page 1 (§4.1) | ✓ | `NoteEditorShowPageTest` (the editor's half; fails with the old `showPage`) | — | tablet, release build: page 29 of 37, and page 1 of 2 |

"Fails under v1's rule": the test was run against `LineSegmenter` with version 1's mark rule,
tolerance, rotated-run limit and slope restored, and exactly those four tests failed. The merge's
test fails alone when the merge is switched off.

**The final runs**, 2026-10-13, each test task with `--rerun` and `--no-build-cache`, none up to date
or from the cache, every test passing:

| Task | Tests |
|---|---|
| `:core:search:testDebugUnitTest` | 33 (`LineSegmenterTest` 23) |
| `:core:model:testDebugUnitTest` | 409 |
| `:android:ink:testDebugUnitTest` | 46 (`SearchHitTrackerTest` 6) |
| `:core:handwriting:testDebugUnitTest` | 48 |
| `:app:testReleaseUnitTest` | 377 |

The iOS klib compiles of `:core:model`, `:core:search` and `:core:handwriting` (main and test) and
`checkCoreIsPlatformFree` passed. On Windows the framework link logs `SKIPPED`, so guardrail 2's link
half is the macOS job's. `spotlessCheck` and `detekt` are clean on `:core:search`, `:android:ocr` and
`:app`. On `:android:ink` and `:core:model` they fail only on the debt `HANDWRITING-SYNTHESIS.md`
§16.4 lists, none of it in a file this work touched.

## 6. Two traps in the debug tools

- **A receiver's coroutine is frozen.** Android's cached-apps freezer suspends a background process a
  few seconds after its broadcast returns, every thread in `do_freezer_trap`. On the emulator that
  looked exactly like an ML Kit deadlock at a random line, until
  `cat /proc/<pid>/task/*/wchan` said `do_freezer_trap`. On the locked tablet it froze the export
  outright. Both receivers now queue an expedited `DebugTaskWorker` job. The receiver also has to
  *wait* for WorkManager to write the job down, or the process is frozen before the enqueue thread
  runs, and no job ever appears.
- **WorkManager's jobs live in a job namespace.** `cmd jobscheduler run -f dev.inkstone 2` answers
  *"Could not find job 2"*. The job is `-n androidx.work.systemjobscheduler`.

## 7. Not done, and what is unverified

- **The tablet has not recognised anything**, because it has no model (§3.1). It now runs a release
  build of this work (debug-key signed, `install -r`, data kept). Once the model is downloaded, every
  page is read for the first time. That is one long pass and one upload per note, as the download
  already queues today. Nothing has been searched on it.
- **A copy of the tablet's ink** (`files/debug/ink-export.json`, 71 MB) is still in the app's private
  storage there. The debug export wrote it, and a release build cannot reach that directory.
- **Emulators that ran a draft of version 2 keep the draft's lines.** `Test_Tablet`'s pages were read
  before the merge step, under the same `lines-2` id, so the final build does not read them again.
  No user has a draft: the tablet never recognised a page while it had one.
- **No regression check on other writers.** M7 tuned version 1 on 20 IAMonDo pages; that sample is
  not on this machine, and it is non-commercial data that would be a download. Every change here is
  structural (a limit, a leaf step, a robust median) rather than a retuned threshold, but that is an
  argument, not a measurement.
- **No regression check on other writers.** M7 tuned version 1 on 20 IAMonDo pages; that sample is
  not on this machine, and it is non-commercial data that would be a download. Every change here is
  structural (a limit, a leaf step, a robust median) rather than a retuned threshold, but that is an
  argument, not a measurement.
- **What still fuses:** small annotations written over or into a heading. On the chemistry note:
  *"Decomposes"* with three lines of notes beside it, *"Types of chemical"* with *"in order of EN"*
  written through it, and one line written over another. Strokes of the two sizes share a baseline by
  chance, and splitting by size class is a feature of its own.
- **Slanted labels stay skipped.** The mind map's *"the same brightness"*, written at 15–25°, is
  `ROTATED`. Levelling each slanted line before reading it was tried with the same harness and found
  nothing more; it was not built.
- **Equations** come back as junk tokens, because fractions are two-dimensional. Out of scope (SPEC
  §12).
