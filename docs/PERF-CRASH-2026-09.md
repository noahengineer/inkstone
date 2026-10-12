# Slow, stuttering, and a crash — the chemistry revision and physics "Electrical energy" notes

The report, in the user's words:

> *"I am having performance problems, its slow it stutters and it even crashes. Some good notes to
> look at for reference for this is the chemistry revision and the physics electrical energy."*

Every claim below says how it was checked. Where it says **tablet**, it was the user's Galaxy Tab
S9 FE+ (SM-X610, Android 16); **emulator** is `Test_Tablet` (x86_64) with byte-identical copies of the
two notes pulled off the tablet with `run-as`.

---

## 1. The two notes

Both are **one page**, and both are the kind of page this app will make more of.

| | physics "Electrical energy" (A) | chemistry "Chemical reactions" (B) |
|---|---|---|
| `note.inkstone` | 6.74 MB, stored | 8.41 MB, stored |
| page JSON | 6.13 MB — **4,810 ops** | 7.38 MB — **5,133 ops** |
| live objects | 1,140 (infinite canvas, mind map) | 2,097 |
| `SetErasedRegion` bytes | 4.11 MB over 122 targets | 6.14 MB over 653 strokes |
| of which superseded | 2.56 MB | 0.52 MB |
| erase capsules (5 floats each) | ~82k | ~125k |
| `undo.json` | 8 KB | 618 KB (2 entries) |

The page file is the note's **whole lifetime op log** by design (SPEC §4.7 *"keep the raw log"*), and
erase regions are ~82–92% of it: one capsule per eraser sample, re-journaled in full on every erase
because `SetErasedRegion` is absolute (rule 8). Every open, cover thumbnail, recognition pass and
materialize paid for all of it — and several of them paid on the **UI thread**.

## 2. The crash

`IllegalStateException: Bundle entry 'pages/….ink' has no local header`, from `Zip.Directory.read`,
resumed on `AndroidUiDispatcher` inside a `LaunchedEffect` — at 19:15:11 on 2026-09-24, with note A
open in the editor.

- `FileByteSource` re-opened the **path** on every read. A `Zip.Directory` built from one version of
  `note.inkstone` read its entries out of whichever version the path named later.
- The editor materializes a synced note every ~30 s of writing (`OutboxDrainer`), replacing the file
  by rename. A materialize that appends ops lengthens the page JSON, so the `.ink` entry's old offset
  lands in the middle of the new JSON.
- The reader was a library **thumbnail** tile: `Thumbnails.cover` → `foldCoverPage` → `store.open` /
  `loadPage`, with no dispatcher switch and no catch — so the whole 6 MB fold ran on the UI thread,
  and its exception was fatal, as every exception escaping a `LaunchedEffect` is.
- A's journal on disk held exactly the last 9 ops of its bundle, and the bundle's `modifiedEpochMillis`
  was 19:15:09.5: the materialize renamed the bundle 1.5 s before the crash and was cut before it could
  reset the journal.

**Fix.** `FileSystem.openRead` returns a `ReadHandle` over one open descriptor; `FileByteSource` opens it
once and reads everything through it (`AndroidFileSystem` uses positional `FileChannel` reads). A
rename replaces the name and leaves the open file alone. The tile path no longer runs on the UI thread
and a failed thumbnail is a placeholder (`Thumbnails.orPlaceholder`).

⚠️ **Pinning made a second guard necessary.** A session whose bundle another writer replaced used to
fail loudly at its next read. Pinned, it reads its old file perfectly and would materialize over the
other writer's work. `NoteStore.materialize` now refuses when `reader.isCurrent()` is false — device and
inode of the held descriptor against what the path names now (`Os.fstat` / `Os.stat`).

## 3. Pressing Back lost the last strokes

Reproduced twice on the emulator: three strokes, Back at once, journal still an 8-byte header.

`NoteActivity.onStop` launched the close-time materialize in `viewModelScope`; Back runs `onStop` and
then clears the view model, which cancels the scope. The save's first suspension is the surface drain
— and the drain is destructive, so the strokes it had just taken came back to a cancelled coroutine and
were dropped. Even a save that survived could run after `onSurfaceDetached`, which dropped the only
reference to the recorder holding them.

**Fix.** `NoteSessionHost.saveOnLeaving` runs the save in a host-owned scope, under the note's mutex;
`retire` unregisters and closes the editor under the same mutex **after** it. `NoteEditor` keeps a
detached surface until the next drain empties it.

## 4. Why it stuttered

| where | what | fix |
|---|---|---|
| library, every stale cover | fold of the whole note (CRC 6–8 MB, parse ~0.5–0.65 M JSON numbers, 5k ops) **on the UI thread** — the emulator logged *"Skipped 5409 frames"* (90 s) drawing A and B | fold on `Dispatchers.Default` |
| every materialize (~30 s of writing, and every `onStop`) | bundle encode (257 / 325 MB allocated for A / B) **on the UI thread** | encode on `saveContext` |
| every materialize | `cache.clear()` then an immediate re-decode of the page for the cover, **on the UI thread** | cache kept — a kept log is identical to what a reload would build |
| every materialize | the cover render awaited **inside the editor lock**, blocking the autosave for seconds | queued (`Thumbnails.refreshCoverLater`), one lane, in order |
| every `onStop`, even with nothing changed | full rewrite + republish + cover render | `materializeIfChanged` skips when `bundleIsCurrent` |
| every recognition pass (3 s after writing) | ink digest ×2–3 **on the UI thread** (B: ~5 MB of stringified capsules each), and a full-page tessellation **under the editor lock** | digest memoized per fold and computed off-thread; sampled outside the lock, from inputs only |
| every rebuild of a page | `androidx.ink` `DecompressedBytes` allocates **32 KB per stroke** (`javap`) — B: ~69 MB of large-object garbage per full rebuild | fewer rebuilds (above); the allocation itself is the library's |
| every full dry-layer re-record | ~555 invisible strokes on B each paying `saveLayer` + `DST_OUT`: the load prune's estimate was 56–101× the real work, so it pruned 2–6 | real work units, resumable scans, only-what-changed queue; load-time prune off the UI thread |
| every eraser sample | `recomputeCanvas` walked the page (≈2.3k allocations/sample on A) | at pen-up only |
| every bundle write | output buffer doubled from 1 KB | sized exactly |

And, separately from the code: **the tablet had only ever run the debug APK** — `DEBUGGABLE`,
`run-from-apk`, partly in ART's switch interpreter. `send-to-tablet.ps1` now sends a release build
signed with the same key (so it upgrades in place).

## 5. What was measured, and what was not

| claim | how it was checked |
|---|---|
| the crash is a torn read through a re-opening `FileByteSource` | **read**, reproduced line for line in a Python model of `Zip.kt` against both real bundles (only one rename point gives the logged message), and **`PinnedReaderTest` fails with the same message** when `FileByteSource` is reverted |
| a pinned reader survives a replacement | `PinnedReaderTest`, red on the old reader |
| materialize refuses to overwrite another writer's bundle | `PinnedReaderTest` with a generation-counting fake; ⚠️ the Android identity (`Os.fstat`) is exercised on the emulator only through ordinary saves passing it, never through a real second writer |
| keeping the page cache does not duplicate ops | `PinnedReaderTest`; `:core:storage` 197/197 |
| Back lost the last strokes | **emulator**, twice, before the fix |
| Back keeps them now | **emulator** on the rebuilt APK — 3 strokes + Back → 3 strokes in the bundle, PDF published; `NoteEditorCloseTest` covers the detached-surface half |
| a failed thumbnail is not a crash | `ThumbnailsTest` (two new cases) |
| the library no longer freezes | **emulator**, same data, debug build both times: *Displayed* 16.6 s → 4.5 s; covers 167 / 182 s → 21 / 25 s; worst stall 5,409 → 142 frames |
| debug vs release | **emulator**, same code and data: *Displayed* 16.6 s → 4.6 s; covers 167 / 182 s → 44 / 51 s |
| the tablet runs release | **tablet**: `pkgFlags` without `DEBUGGABLE`, `status=speed` after `cmd package compile`; library *Displayed* +397 ms (debug: +942 ms) |
| the render/prune changes | `:android:ink` JVM tests (`ErasePruneTest`, `PenStrokeOrderTest`); instrumented `PreparedPagePruneTest`, `InkStrokeSamplerTest`, `StrokeStoreEraseTest` — see §6 |
| per-frame cost of the remaining `saveLayer`s on the tablet | ⚠️ **not measured** — a release build cannot be profiled with `simpleperf --app` |

## 6. Found and not fixed

- **The page log only grows.** Strictly dominated register writes are 47% of A's page JSON and can be
  dropped without a horizon — but that is a SPEC §4.7 change, and a merge would re-deliver them from
  any peer that still has them unless the merge filters the same way.
- **Erase capsules are one per 240 Hz sample**, and re-scrubbing an already invisible stroke keeps
  appending (39–51% of the capsules on fully erased strokes were written after full coverage).
- **`undo.json` is re-encoded whole on every flush** (B: ~25 MB allocated per autosave while writing),
  bounded by entry count, not bytes.
- **Publishing still holds the editor lock** — a full re-tessellation in the outliner plus PdfBox. Moving
  it out needs the raw publisher to report the revision of the bundle it *embedded*: today
  `InkstonePdfMaterializer` returns `max(bundle, journal)`, which on the no-editor path records
  `lastSyncedRev` past ops the uploaded bundle lacks (M6 gate 5's shape).
- **`NoteSessionHost.register` overwrites** a live registration; the launcher-stacking guard closes the
  way to a second editor that was observed, not the possibility.
- **`peerClock` is keyed by the note creator's `deviceId`**, and both notes were created by the install
  that predates the 2026-09-22 factory reset.
