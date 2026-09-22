# M8 follow-up 6 — undo lagged, then fired in a burst, and an erased-away stroke never came back

Reported as one symptom and it was two bugs:

> *"i have a bit of a problem with undoing — it seems to lag, like not register properly, and then
> after a few seconds it updates and all undo's that seem to do nothing happen and you undo a lot
> more than you want. But then also that seems to break the undo sometimes so you cant go back to
> how it was. Also erasing seems to mess it up sometimes as well."*

Two independent causes, one of which made the other worse. Neither had a test over it.

---

## 1. Every press was its own operation, queued behind a mutex

`InkSurfaceView.onFingersLifted` fires `undo()` per two-finger tap with no guard — it does not even
read `canUndo`, which is pushed into the view and never consulted. That reached
`NoteEditor.undo()`, which went through `withPage`:

```kotlin
work.launch { serial.withLock { block(session, page) } }
```

`serial` is the editor's single mutex and it is held for whole operations, file I/O included. So a
press made while the editor was busy — the two-second autosave, a reconciler pull, or simply the
press before it — launched a coroutine that sat waiting, and **nothing reached the screen until it
got the lock and ran all the way to its own `reconcile`**. A burst of taps therefore looked dead
and then landed together, several gestures past wherever the user meant to stop.

Two things made each press expensive enough for the window to be seconds rather than frames:

- **Undo opened with a full `saveLocked()`**, i.e. a journal append plus `NoteStore.saveUndo` plus
  `library.onSaved` (a library row, `SyncEngine.noteChanged`, a WorkManager enqueue). And
  `session.undo` leaves its inverse ops in `pending`, so `hasUnflushedWork` was true on the *next*
  press: the cost never amortised, every tap paid the lot.
- **`saveUndo` is O(the whole stack), not O(the change).** It re-encodes *both* stacks — up to 200
  entries, every stroke's geometry blob included — and atomically rewrites `undo.json`. `UndoStack`'s
  own KDoc already says an undone erase carries a whole absolute region and a scribble-erase region
  is thousands of capsules. **This is the link to the user's last sentence**: the more you erase, the
  slower every undo press and every autosave tick gets, and the wider the window in which taps pile
  up.

### What it is now

`undo()`/`redo()` append to a `historyRequests` deque and, if nothing is already draining, start
**one** operation. `drainHistory` takes the lock once, takes the fold before the batch, keeps
consuming the deque while it holds the lock — presses arriving mid-drain join the batch rather than
queueing another operation — and reconciles and publishes **once** at the end. So a burst is one
lock acquisition and one repaint, and the surface is never shown a state the user was only passing
through.

`saveLocked` is split: `drainLocked` gets the surface's finished gestures into the session and
**writes nothing**, and only the autosave path adds the flush. That is what undo actually needed —
queued ink must have reached the *session* so an inverse is computed against a page containing it;
it does not need to be on disk. The ops undo appends sit in `pending` like any others and the
two-second autosave flushes them, which is the same window every stroke already lives in
(SPEC §5.1). The failure direction is also the safe one: an undo lost to a kill in that window
leaves the user's ink *present*, never gone.

⚠️ **Not a signed counter.** Collapsing "five undos then two redos" to a net three is cheaper and is
wrong exactly where it matters: with two entries on the stack, five presses exhaust it and the two
redos come back to the start, while a net three leaves the user two gestures in the past.
Recovering from an overshoot is this bug's own aftermath. Arrival order is kept.

⚠️ The deque is emptied in `display`, because an entry's inverse is only meaningful on the page it
was computed against (`UndoEntry`).

---

## 2. ⛔ An erased-away stroke did not come back, and every layer was individually right

`pruneFullyErased` drops a fully erased stroke off the page and **emits no op** — correctly, because
a fully erased stroke is not deleted; its `SetErasedRegion` already describes it, and emitting a
delete would make un-erasing it impossible. Its own KDoc calls the drop *"a render optimisation"*.

But the drop went through `unindex`, so the stroke left `byId`. And then:

- the object is still in the fold, so `NoteEditor.reconcile` sees it in **both** folds and its
  `removed`/`added` sets are empty for it;
- so all it emits is a bare `SetErasedRegion` from `changedRegisters`;
- and `StrokeStore.applyRegister` opened with `val dry = byId[target] ?: return false`.

Correct op, correct journal, correct fold, one consumer that could not act on it. The stroke stayed
gone until the page was reloaded, with nothing anywhere saying so. Intermittent because pruning is
rationed by `PRUNE_BUDGET`: whether a given stroke had actually left the page depended on how much
else had been erased first.

This is the same shape as `DryShape` never calling `setErased` on load (`docs/M8-SHAPE-POINTS.md`)
and as M6's `setPageMeta` stub — and the habit that would have caught it is the one both of those
recorded: **when a register is written, grep every consumer that has to read it, not just the one
that writes it.**

### What it is now

`StrokeStore.prunedByErase` holds what the prune took off the page. `applyRegister` consults it
before the `byId` lookup — for `SetErasedRegion` only, which is the one register that can legally
name a stroke that is not on the page — and `unprune` writes the region and re-`insert`s the stroke
when the region no longer covers it. A region that still covers leaves it pruned and reports **no**
change, so an undo that only shrinks the region does not repaint the page for a stroke that is
invisible either way.

Retaining the object costs the memory the page was already carrying. Pruning was never a way to
release a stroke; it is a render optimisation by its own KDoc, and nothing about it was meant to be
one-way.

A `DeleteObject` naming a pruned stroke now tombstones it and drops the entry, or a connector aimed
at it would report *not yet arrived* rather than *deleted* (SPEC §4.8.4).

---

## 3. What was checked, and what was not

| | Written | Automated | Run |
|---|---|---|---|
| Undo presses during a busy editor become one batch | ✅ | ✅ `NoteEditorConcurrencyTest` | ✅ **and checked red against the old code** — `expected:<1> but was:<3>` |
| An over-pressed burst is put right by redos in the same burst | ✅ | ✅ same file | ✅ — but it passes against the old code too, and says so: it guards against the netting being added later, it does not reproduce this bug |
| Undoing an erase brings back a stroke the prune dropped | ✅ | ✅ `StrokeStoreEraseTest` | ❌ **compiled only** |
| An un-erase that still covers leaves the stroke pruned | ✅ | ✅ same file | ❌ **compiled only** |

⚠️ **The two erase tests have never executed.** `:android:ink` has only an `androidTest` source set —
`androidx.ink`'s geometry is native, so a `Stroke` cannot be hit-tested off a device — and
`adb devices` listed nothing at all this session: no tablet, no emulator. `check` compiles
instrumented tests and does not dex or run them, which is exactly the gap CLAUDE.md's testing
section is about. **They have not been checked red against the broken code either**, so by that
section's own standard they are not yet evidence.

Fix #2 is therefore *reasoned* rather than *observed*. Before this is believed:

```bash
adb shell input stylus tap 600 500
```

drive it by hand on a device — draw a stroke, rub it out completely with the partial eraser, tap two
fingers — and run `./gradlew :android:ink:connectedDebugAndroidTest`, watching the two new tests
first go red with `prunedByErase` disabled.

What did run: `:app:testDebugUnitTest` forced with `--rerun --no-build-cache` — **176 tests, 0
failures** — plus `detekt` and `spotlessCheck` on both touched modules, and a green `./gradlew
build` including `checkCoreIsPlatformFree` for all four core modules. Guardrail 2's framework link
is `SKIPPED` on Windows as always; only the klib half ran here.

---

## 4. Follow-up: *"it still hitches sometimes especially when you first open the note"*

§1's coalescing removed the queue of presses. It could not remove the reason the window existed:
**opening a note blocked the UI thread outright**, so a two-finger tap in that window was not slow,
the thread it needed was gone.

`docs/M8-PERF-RENDER-CACHE.md` §5 had already measured it — `Skipped 190 frames!`,
`Displayed +3s632ms` on a 1,187-object note, halved to ~1.8 s by removing a duplicate load and no
further — and listed *"get the decode off `Dispatchers.Main.immediate`"* as the next step. Two things
sat on that thread:

- **The reads.** `NoteEditor.work` is `Dispatchers.Main.immediate` deliberately (it is what
  serialises the session), and the whole of `openOrCreate` ran in it: the journal read and CRC, the
  full op decode, `NoteStore.loadUndo` (597,640 B, 1,898 ops on the measured note), and the fold in
  `display`. None of it touches a view.
- **The tessellation**, which is the dominant term: `InkSurfaceView.loadPage` built every object in
  one unyielding loop, and `Stroke(brush, inputs)` is native mesh construction.

### The unknown that was blocking it was already answered

The open question was whether `androidx.ink` tolerates building a `Stroke` off the main thread.
**It does, and this app has depended on it since M3**: `InkPageThumbnailRenderer.render` builds a
whole `StrokeStore` out of a folded page — same `buildDryObject`, same `StrokeGeometryCodec.decode`,
same native tessellation — on `Dispatchers.Default`, for every thumbnail in the library. Two
milestones of production evidence, in a file the previous session did not look at.

### What changed

- `PreparedPage.of(page, brushes)` is the expensive half, and it is pure: ops in, built objects out,
  no view, no store, no `StrokeStore` state. `StrokeStore.install(prepared, brushes)` is the cheap
  half — `reset()` plus one `insert` per object — and stays on the UI thread.
- `InkSurfaceView.loadPage` is `suspend` and splits into `beginPageLoad` (paper, camera, background,
  immediately) → `withContext(dispatcher) { PreparedPage.of(…) }` → `finishPageLoad`. A generation
  counter discards a build whose page has since been turned away from.
- `dryObjectFor` moved out of `StrokeStore` as `buildDryObject`. The only thing tying it to an
  instance was the shape branch setting `routesDirty`, and `insert` sets that for *every* object it
  takes — a stronger statement that was already true.
- `openOrCreate` and `display` do their reading, decoding and folding in
  `withContext(drainDispatcher)`. `serial` is held across the hop, so nothing about ordering
  changes; only which thread pays.

### ⚠️ The pen is refused while a page is loading, and that is load-bearing

Until `install` runs the store is empty, so `StrokeStore.topZ` is null: a stroke drawn against it
takes a base z key and the page's whole contents then install **above** it. That is M8a's `lastZ`
bug — *"the stroke I just drew went behind the picture"* — recreated by a loading page, and
`docs/M8-PERF-RENDER-CACHE.md` §5 names it as the trap that sank the earlier attempt.

It costs the user nothing they had: the load blocked the UI thread before, so input in that window
was already going nowhere. What changes is that it is a page visibly filling in instead of a frozen
app — and it says so, because **a note whose ink appears after its paper must not read as an empty
note**. `InkUiState.pageLoading` drives a `GlassBar` over the canvas.

### What was checked

| | Written | Automated | Run |
|---|---|---|---|
| The open does no file work on the editor's own thread | ✅ | ✅ `NoteEditorBindingTest` | ✅ **and checked red** — *"the open read and decoded the note on the editor's own thread: [fake-editor-main …]"* |
| Exactly one page load per open | ✅ | ✅ same test | ✅ (the readout §5 of the render-cache doc asked for) |
| Tessellation off the UI thread | ✅ | ❌ | ❌ **not verified** — see below |

⚠️ **The half that matters most is the half with no test.** A JVM unit test cannot build a `Stroke`
(`androidx.ink`'s geometry is native), so nothing here exercises `PreparedPage.of` at all; the
thread assertion covers the *reads*, which were the 12–50 ms stages, not the ~1.8 s one. The
argument for the tessellation half is the thumbnail precedent plus the type split, and an argument
is not a check. `adb devices` listed nothing this session — no tablet, no emulator.

**Before this is believed, on a device:** open the largest note in the library and confirm the
chrome stays responsive and the *"Loading page…"* pill appears and goes; confirm a stroke drawn the
instant a page opens is not accepted and, once it is, lands **on top**; and run
`:android:ink`'s and `:app`'s instrumented suites, `PageMetaLiveChangeTest` and
`WetInkRemoteMergeTest` especially — both call `loadPage` and both had to be wrapped in
`runBlocking` for this change.

### ⚠️ One instrument bug, found only by the revert-and-check-red step

`ThreadRecordingFileSystem` first compared `Thread.currentThread().name` to a `ThreadFactory` name by
equality. `kotlinx-coroutines-debug` — which the test runtime enables — appends ` @coroutine#N`, so
the comparison never matched and the test **passed against the deliberately broken build**. Had the
red check been skipped it would have shipped as coverage of nothing. It also recorded the thread the
delegate *returned* on rather than the one it ran on, which is a different thread the moment any
implementation hops internally. Both fixed; `ranOn` carries the reason.

---

## 5. Follow-up 2: ⛔ undo waited for the handwriting recogniser

§1 and §4 were both real and neither was *this*. Reported with the detail that pinned it:

> *"basically testing it i do a lot of drawing and undoing then at some point the undo does not
> seem to work anymore and then i press undo a couple of times to see if it just did not register
> when everything suddenly gets undone in an instant"*

**Drawing, then a pause, then undo.** That is SPEC §8.3's debounce, exactly: the recognition loop
fires `RECOGNITION_DEBOUNCE_MS` (three seconds) after the pen stops — which is the moment a person
stops writing and reaches for undo. And `NoteEditor.recogniseNow` was:

```kotlin
suspend fun recogniseNow(): RecognitionReport = serial.withLock { recogniseLocked() }
```

`serial` held across the **whole** pass: fold every unread page, sample it, and run ML Kit — one
recogniser call per line of handwriting, so seconds on a dense page. Every undo press in that
window queued behind the lock, and §1's coalescing then delivered the accumulated batch in one
frame the moment it was released. *"Everything suddenly gets undone in an instant"* is §1's fix
working correctly on top of a stall §1 never touched.

It is intermittent for a reason that reads exactly like the report: it needs a page with unread
strokes **and** three seconds of stillness, and it re-runs after every pause, because new ink
invalidates the page's digest.

### ⭐ The storage layer had already been written for a concurrent pass

`NoteSession.recognised`'s KDoc:

> *"Recognition is asynchronous by construction — SPEC §8.3 debounces it three seconds behind the
> pen — so the ink can and does change while it runs […] @return false when the ink moved under
> the recogniser, in which case nothing is stored."*

And `NoteRecogniser.readPage` reads its result back through `recognisedText`, *"so a stroke that
landed while ML Kit was working keeps its page out of the index"*. **Both were unreachable**: the
editor's lock made a concurrent stroke impossible, so the guards guarded nothing and the comments
described a situation the code prevented. The lock was strictly more than the design asked for, and
the whole cost of it was the editor's responsiveness.

### What changed

`SessionGate` — take the lock, give it back, take it again. `NoteRecogniser.readPage` is three
phases now: **fold and sample** through the gate, **ML Kit** with no lock at all, **commit and
index** through the gate. `NoteEditor` passes a gate over `serial` and no longer wraps the pass;
`NoteSessionHost`'s batch pass gets `SessionGate.Direct`, which is the old behaviour and correct
there, because it opened the session itself and has nobody to stay responsive for.

The two guards above are load-bearing now rather than decorative, and so is the comment about
`unreadPages` being cleared before the pass so that a stroke landing mid-pass re-queues its page.

⚠️ `serial` is a `Mutex` and a `Mutex` is never re-entrant, so a gate handed out from *inside*
`serial.withLock` deadlocks the editor permanently. `recogniseThrough` exists so that neither caller
has to remember; `SessionGate`'s KDoc says it in the one place someone would look.

### What was checked

| | Written | Automated | Run |
|---|---|---|---|
| An undo pressed during a recognition pass does not wait for it | ✅ | ✅ `NoteEditorConcurrencyTest` | ✅ **and checked red** — `expected:<1> but was:<0>` |

The recogniser is parked **inside** the ML Kit call, which is the only place the split is
observable — park it anywhere else and the old code and the new one behave identically.

⚠️ **The first version of that test reproduced the bug as a one-minute hang, not a failure**,
because its assertion read `editor.foldedPage(…)` — which takes `serial` too, so against the
pre-fix code the *assertion* blocked. **A test whose assertion needs the very lock it is about can
only ever hang.** It asserts on `surface.applied` now, a plain field, and fails in six seconds
saying what is wrong.

### Still owed: the other long holders of `serial`

Recognition is the one that fires on a three-second idle after drawing, which is why it matches this
report. It is not the only operation that holds the editor's lock for a long time, and none of these
is fixed:

- **`materializeInSession`** — `MaterializeWorker`'s "idle while charging" rewrite of the whole
  bundle, at a moment WorkManager picks.
- **`mergeRemoteBundle`** — a pull that actually has remote changes to merge.
- **`save()`** — `NoteStore.saveUndo` re-encodes *both* undo stacks with every geometry blob in
  them, on every flush, i.e. every two seconds while writing (§1). Off the UI thread but awaited
  under the lock, and O(the whole stack) — so it grows with the session, and worst of all after a
  lot of erasing.

If the hitch is ever reported again *without* the draw-pause-undo shape, `saveUndo` is the next
suspect, and the fix is to stop rewriting the whole file per flush.

---

## 5. Follow-up 2: ⛔ undo waited for the handwriting recogniser

§1 and §4 were both real and neither was *this*. Reported with the detail that pinned it:

> *"basically testing it i do a lot of drawing and undoing then at some point the undo does not
> seem to work anymore and then i press undo a couple of times to see if it just did not register
> when everything suddenly gets undone in an instant"*

**Drawing, then a pause, then undo.** That is SPEC §8.3's debounce, exactly: the recognition loop
fires `RECOGNITION_DEBOUNCE_MS` (three seconds) after the pen stops — which is the moment a person
stops writing and reaches for undo. And `NoteEditor.recogniseNow` was:

```kotlin
suspend fun recogniseNow(): RecognitionReport = serial.withLock { recogniseLocked() }
```

`serial` held across the **whole** pass: fold every unread page, sample it, and run ML Kit — one
recogniser call per line of handwriting, so seconds on a dense page. Every undo press in that
window queued behind the lock, and §1's coalescing then delivered the accumulated batch in one
frame the moment it was released. *"Everything suddenly gets undone in an instant"* is §1's fix
working correctly on top of a stall §1 never touched.

It is intermittent for a reason that reads exactly like the report: it needs a page with unread
strokes **and** three seconds of stillness, and it re-runs after every pause, because new ink
invalidates the page's digest.

### ⭐ The storage layer had already been written for a concurrent pass

`NoteSession.recognised`'s KDoc:

> *"Recognition is asynchronous by construction — SPEC §8.3 debounces it three seconds behind the
> pen — so the ink can and does change while it runs […] @return false when the ink moved under
> the recogniser, in which case nothing is stored."*

And `NoteRecogniser.readPage` reads its result back through `recognisedText`, *"so a stroke that
landed while ML Kit was working keeps its page out of the index"*. **Both were unreachable**: the
editor's lock made a concurrent stroke impossible, so the guards guarded nothing and the comments
described a situation the code prevented. The lock was strictly more than the design asked for, and
the whole cost of it was the editor's responsiveness.

### What changed

`SessionGate` — take the lock, give it back, take it again. `NoteRecogniser.readPage` is three
phases now: **fold and sample** through the gate, **ML Kit** with no lock at all, **commit and
index** through the gate. `NoteEditor` passes a gate over `serial` and no longer wraps the pass;
`NoteSessionHost`'s batch pass gets `SessionGate.Direct`, which is the old behaviour and correct
there, because it opened the session itself and has nobody to stay responsive for.

The two guards above are load-bearing now rather than decorative, and so is the comment about
`unreadPages` being cleared before the pass so that a stroke landing mid-pass re-queues its page.

⚠️ `serial` is a `Mutex` and a `Mutex` is never re-entrant, so a gate handed out from *inside*
`serial.withLock` deadlocks the editor permanently. `recogniseThrough` exists so that neither caller
has to remember; `SessionGate`'s KDoc says it in the one place someone would look.

### What was checked

| | Written | Automated | Run |
|---|---|---|---|
| An undo pressed during a recognition pass does not wait for it | ✅ | ✅ `NoteEditorConcurrencyTest` | ✅ **and checked red** — `expected:<1> but was:<0>` |

The recogniser is parked **inside** the ML Kit call, which is the only place the split is
observable — park it anywhere else and the old code and the new one behave identically.

⚠️ **The first version of that test reproduced the bug as a one-minute hang, not a failure**,
because its assertion read `editor.foldedPage(…)` — which takes `serial` too, so against the
pre-fix code the *assertion* blocked. **A test whose assertion needs the very lock it is about can
only ever hang.** It asserts on `surface.applied` now, a plain field, and fails in six seconds
saying what is wrong.

### Still owed: the other long holders of `serial`

Recognition is the one that fires on a three-second idle after drawing, which is why it matches this
report. It is not the only operation that holds the editor's lock for a long time, and none of these
is fixed:

- **`materializeInSession`** — `MaterializeWorker`'s "idle while charging" rewrite of the whole
  bundle, at a moment WorkManager picks.
- **`mergeRemoteBundle`** — a pull that actually has remote changes to merge.
- **`save()`** — `NoteStore.saveUndo` re-encodes *both* undo stacks with every geometry blob in
  them, on every flush, i.e. every two seconds while writing (§1). Off the UI thread but awaited
  under the lock, and O(the whole stack) — so it grows with the session, and worst of all after a
  lot of erasing.

If the hitch is ever reported again *without* the draw-pause-undo shape, `saveUndo` is the next
suspect, and the fix is to stop rewriting the whole file per flush.
