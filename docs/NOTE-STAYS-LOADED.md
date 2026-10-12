# The note stays loaded — leaving for the Element editor or another app (2026-10)

> *"when exit the element editor it tends to lag and crash in that big note and if i like switch
> windows it also reloads the note"*

Same note as `docs/IMAGES-PERF-2026-10.md`: *Physical Science/physics/Electrical energy/Untitled*,
~18.6 MB, one infinite page. This doc is the second half of that session.

## 1. What was happening

**The note was not crashing first. Android was destroying its screen on purpose.**

ActivityThread registers a GC watcher (`BinderInternal.addGcWatcher`). After any collection, if some
activity changed state and the heap in use is above **three quarters of `maxMemory()`**, it asks the
system to release activities. The system then destroys every stopped activity that has saved state,
logged as `wm_destroy_activity … low-mem`. This is not the low-memory killer and not a crash. It is
an ordinary destroy: the view model is cleared, `NoteSessionHost.retire` closes the editor, and
coming back recreates the screen and opens the whole note again.

Without `largeHeap` the limit is `dalvik.vm.heapgrowthlimit`: 256m on the Tab S9 FE+ and 192m on the
emulator, so the watcher fired at **192 MB** and **144 MB**. The physics note does not fit
comfortably inside either. Three things allocate a whole bundle while the note's screen is stopped:

| Trigger | Where | When |
|---|---|---|
| `onStop` materialize | `NoteActivity.onStop` → `materializeIfChanged` | every stop with unsaved work: the Element editor opening, a switch to another app, the screen timing out |
| *Update them all* | `NoteEditor.updateElementCopies` → `materializeLocked()` | with the note stopped behind the Element editor |
| the outbox drain | `NoteSessionHost.materialize` → `materializeInSession` | at most every 30 s while a bound note is being edited. **Unchanged; see §5** |

`NoteStore.materialize` measured the encode at 250–325 MB of allocation for the two biggest notes.

Once the screen was destroyed, two copies of the note could be in memory at once, and that is where
the crash came from:

- **On the tablet** (13:55–13:56, before this change): `GC … 202MB/250MB` at 13:55:52.419, then
  `wm_destroy_activity … NoteActivity, low-mem` 4 ms later. The Element editor's Done and
  *Update them all* found no registered editor, so `NoteSessionHost` opened its own session over
  the note **on the UI thread**: the caller was a Compose scope, and the host never changed thread.
  Meanwhile the recreated note screen was opening the same note. Result: `OutOfMemoryError` in
  `BundleReader.page` (Bundle.kt:482) on `AndroidUiDispatcher` at 13:56:29.
- **On the emulator** (old build, reproduced twice): one stroke, Home, come back. The destroyed
  editor's close-time materialize was still holding its copy of the note while the recreated one
  opened. Result: `OutOfMemoryError` in `BundleReader.page`, `FATAL EXCEPTION: main`,
  `force-crash`, and the process restarted on the library.

*"Switching windows"* is the same path: a switch to another app is a stop. No config-change relaunch
appears in either log, but split screen and pop-up view would have caused one (§2.5).

## 2. What changed

1. **`android:largeHeap="true"`** (manifest). The limit becomes `dalvik.vm.heapsize`: 512m on the
   tablet and 576m on the emulator. The watcher fires at 384 MB and 432 MB, and the OOM ceiling
   doubles. Nothing here grows to fill the space. `PageAssetCache`'s budget derives from the limit
   but is clamped to its 256 MiB ceiling, and those pixels live in native memory anyway.
2. **`onStop` saves, and materializes only when `isFinishing`** (`NoteEditorViewModel.onStopped`).
   This is SPEC §5.1's "document close", taken literally; the SPEC now says so. Back still
   materializes and still redraws the thumbnail. A screen the system destroys without finishing is
   *not* materialized on the way out (`onCleared`'s comment says why). Its journal holds everything,
   and the next close folds it in.
3. ***Update them all* no longer materializes** (`NoteEditor.updateElementCopies`). The ops are in
   the journal. The new pieces' picture bytes are in `pending-assets/`, which is durable and is what
   `assets()` draws from. `materializeIfChanged` does not skip while anything is staged.
4. **`NoteSessionHost`'s own-session branches of `countElementCopies` and `updateElementCopies` run
   on `Dispatchers.Default`.** The merge and recognition branches were already off the UI thread,
   because their WorkManager callers are.
5. **`smallestScreenSize` added to both activities' `configChanges`.** Split screen, pop-up view and
   any window resize change it. Each one used to recreate the note's activity, and although the view
   model survived, the ink surface did not, so the page was rebuilt from scratch.

## 3. Measured

Emulator `Test_Tablet` (x86_64, `heapgrowthlimit` 192m, `heapsize` 576m), release builds, the
physics note's copy, airplane mode on throughout. `scratchpad/scripts/stop-walk.ps1` does the same
steps each time: cold start, open the note, draw one stroke, Home, come back.

| | old build | new build |
|---|---|---|
| Opening the note: heap peak | 143–163 MB of a 192 MB limit. At 163 MB the **library behind it was destroyed** (`low-mem`) | 158 MB of 576 MB. Nothing destroyed, `Activities: 2` |
| One unsaved stroke, then Home | 96 → 149 MB in 330 ms; **the note destroyed** 1 ms after that GC. A second run: 151 → 153 MB, destroyed | 83–98 MB, nothing destroyed |
| Come back | note recreated, then **`OutOfMemoryError`, process died** (2 of 2 runs) | `wm_on_restart_called`, **no loading chip** within 6 s |
| Element editor round trip | not reached: the note was gone before Done (as on the tablet) | note stopped behind it, nothing destroyed. *Update all* updated 3 copies in 2 notes, and the note resumed with the new copy drawn and no reload |

`LARGE_HEAP` appears in `dumpsys package dev.inkstone`'s `pkgFlags` after install, which is how to
check the flag actually shipped.

## 4. Per claim

| Claim | Automated | Run on a device |
|---|---|---|
| A stop that is not a close saves and does not materialize | ✅ editor half: `NoteEditorCloseTest` *"a stop that does not close the note…"*. ❌ the `isFinishing` choice is in the view model, which a JVM test cannot construct | ✅ emulator: Home with an unsaved stroke, no spike, no destroy |
| Coming back does not reload | ❌ | ✅ emulator: restart, not create; no chip |
| *Update them all* does not materialize, and the close does | ✅ `NoteEditorCloseTest` *"updating an Element's copies…"*, **checked red** against the old line (*expected 1, was 2*) | ✅ emulator: copy updated on screen, note never reloaded |
| `largeHeap` is in force | — | ✅ emulator and tablet: `LARGE_HEAP` in `pkgFlags` (tablet build installed 15:41:43, APK SHA-256 `E1C01B23…`) |
| Host's own sessions are off the UI thread | ❌ the host cannot be built in a JVM test | ⚠️ ran on the emulator (the second note had no editor, so the fallback did the work). No frame warnings, but the thread was not asserted |
| `smallestScreenSize` | ❌ | ❌ no split-screen walk was done |
| The Galaxy Tab | — | ⏳ definition-of-done #4. The build is installed, but the walk needs the tablet unlocked, and a walk on the user's own notes would also change them (see §6) |

`:app:testDebugUnitTest`: 291 tests, 40 failures. All 40 are `MergeMatrix*`, the pre-existing
two-device harness failures (*"device-b cannot open …"*), and their count is unchanged by this work.
`:app:spotlessCheck` is green. `:app:detekt` reports 3 findings, all unused properties in the
calculator refactor another session has in progress (`CalculatorController.kt`, which needed a
temporary patch even to compile and was restored byte-for-byte afterwards). Two findings were this
day's own (`PageAssetCache`'s magic numbers) and one came from earlier uncommitted work (a deliberate
catch-all in `Thumbnails.orPlaceholder`, now `@Suppress`ed like its siblings); all three are fixed.

## 5. Found and not fixed

- **A page edit that removes objects costs ~150 MB of transient heap on this note.** *Update all*
  went 146 → 230 MB → 96 MB in about a second, and a plain undo went 90 → 241 → 94 MB. *Keep as they
  are* showed no GC at all. It happens off the UI thread (no `Choreographer` warnings) and is well
  inside the new limit, but on the old 192 MB line it would have destroyed the library behind the
  note. Not diagnosed. `NoteStore.saveUndo` re-encoding both whole undo stacks
  (docs/M8-UNDO-LAG.md) is the first suspect, and it is a suspect, not a measurement.
- **The outbox drain still materializes through an open editor**, at most every 30 s while a bound
  note is being edited, including while its screen is stopped. It is now the only remaining
  whole-bundle allocation behind another screen. `largeHeap` is what keeps it below the line, not a
  design.
- `stampElement` and `insertImage` still materialize immediately. The screen is visible then, so
  there is no destroy risk, but it is seconds of work on a big note.
- `BundleWriter.write` builds the whole page JSON as one string. Streaming it would cut the
  materialize's live peak, which is the root of every row in §1's table.
- *"A resource failed to call close"* ×12 after *Update all*: the host's own sessions and
  `ElementLibrary.list` never close their `FileByteSource`s. The collector does it eventually.
- If the system still destroys a stopped note (real memory pressure), the recreated editor can open
  while the old editor is still retiring. Same race as `NoteSessionHost`'s class KDoc; unchanged.

## 6. Traps met on the way

- **The library is sorted by modification time, and every walk writes a stroke**, so the note moves
  to the front and a fixed tap opens a different note. The first new-build walk timed a tiny note
  and reported no loading chip. Find the tile from a screenshot before each run.
- `logcat -c` clears main, system and crash, but **not `events`**, which is where
  `wm_destroy_activity` lives. Use `logcat -b all -c`, or old destroys read as new ones.
- Start the app with the **launcher's intent** (`-a MAIN -c LAUNCHER`). With plain `am start -n`,
  "come back" through the launcher intent does not match the task's root intent, creates a second
  `MainActivity`, and that one finishes itself.
- After an undo the emulator showed a whole-screen garbage frame, byte-identical across captures,
  while `uiautomator dump` returned the full live toolbar. That is CLAUDE.md's x86_64 compositing
  note again, not the app.
- Walking the tablet on the user's real notes means drawing in them and, for *Update all*,
  rewriting an Element and its copies. Those notes sync to Nextcloud. The emulator's copy, in
  airplane mode, is where that belongs; the tablet walk is the user's call.
