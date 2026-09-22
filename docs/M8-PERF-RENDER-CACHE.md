# Performance pass — the dry-layer cache, the dead thumbnail tier, and the dead compaction gate

The complaint, verbatim:

> There are serios optimization problems in this project when there are a large amount of strokes
> on the page it becomes very leggy, it starts to feel stuttery and the preview takes a long time
> to load and when opening the document it takes quite a bit to load

Three symptoms. All three were real and each had a distinct mechanism. This records what was
measured, what was fixed, what was found and **not** fixed, and — the part that matters most for
the next session — the two places where the obvious next step is the wrong one.

⚠️ **Every number here is from a Pixel Tablet AVD (x86_64, API 35, software GPU).** The tablet
was `offline` for the whole session and `adb reconnect` lost it entirely, so
CLAUDE.md's definition-of-done #4 is **unmet**. The emulator's software rasteriser makes GPU cost
look far worse than it is on an Adreno, which is exactly why §3's deferral is a deferral.

---

## 1. How the page was made heavy, and why the first attempt looked like a bug

`adb shell input stylus swipe` is one stroke per invocation — `motionevent` is one *event* per
invocation, so a stroke costs five round trips and 1,000 strokes is unusable. Batched ~30 swipes
per `adb shell` call, 1,000 strokes took about 3 minutes.

⚠️ **The first run ANR'd after ten strokes and it was not the app.** `Load: 33.67`, with
Calendar, Chromecast *and* Wellbeing ANR'ing in the same minute — the emulator was still
installing and syncing. Force-stopping the Play stack and waiting for load to fall below ~1.5 is
part of the setup, not an optimisation. `docs/M8-PERF-UNDO.md` recorded the same trap and it cost
time again here; **read `/proc/loadavg` before believing any frame number**, and record it beside
every measurement.

---

## 2. Draw lag — fixed on the CPU side, and the measurement says so

`dumpsys gfxinfo` over a scripted pan, per stroke count (load 4.0–4.8):

| strokes on page | 50th %ile frame | janky | Slow UI thread |
|---|---|---|---|
| 5 | 42 ms | 68% | 57 / 86 |
| 200 | 34 ms | 34% | 63 / 194 |
| 500 | 81 ms | 76% | 96 / 127 |
| 1000 | **101 ms** | **90%** | **93 / 103** |

`Slow UI thread` on nearly every frame is the finding: the cost was **display-list recording on
the CPU**, not the GPU. `DryStrokesView.onDraw` re-recorded the whole visible page — one
`CanvasStrokeRenderer.draw` per visible object plus a `saveLayer`/`DST_OUT` composite per erased
object — on every frame, while a pan changes nothing but six floats. That is exactly the deferral
`docs/M8-PERF-UNDO.md` §3 wrote down.

**The fix** (`DryStrokesView`): record the page into a `RenderNode` **in page space** and replay
it under the camera transform.

The correctness argument is one sentence, and it is the same sentence this file's class KDoc has
carried since M1: `strokeToScreenTransform` is not a drawing transform — **only its linear
component is read**, to size the antialiasing feather. So a **pan** changes only the translation
and the recording stays exactly right; a **zoom** changes the scale and it does not, so the node
is re-recorded. Re-recording on zoom is not a regression — it is what every frame used to do
unconditionally.

Two things deliberately not done, both because `docs/M8-PERF-UNDO.md` §3 warned about them:

- **Not a view-sized `LAYER_TYPE_HARDWARE`** — that clips to the view and shows blank margins on
  a pan. The node covers the viewport inflated by 50% and is re-recorded when the viewport leaves
  it, so a pan is free until it runs off the edge.
- **Not the whole page** — a re-record is O(recorded region), and the case that re-records most
  often (one per finished stroke) is the zoomed-in one, where recording the whole page would be
  the most expensive possible choice.

### What it actually bought, measured two ways

| | before | after |
|---|---|---|
| 50th %ile frame, 1,000 strokes, pan | 101 ms | **44–53 ms** |
| `Slow UI thread` | 93 / 103 | 78 / 123, then 108 / 206 |
| re-records during a 10-pan sequence | one per frame | **0** (instrumented, counted in logcat) |
| app drawing methods on the main thread | dominant | **none appear in the profile at all** |

The zero is the load-independent evidence: a temporary `Log.i` in `record()` counted **not one
re-record** across the whole pan, and an `am profile` sample of the main thread during a pan shows
no `PagePainter`, no `CanvasStrokeRenderer`, no `StrokeStore` — only `nativePollOnce` (idle) and
`HardwareRenderer.nSyncAndDrawFrame`.

### ⚠️ Therefore: the dry layer was not the *whole* pan cost, and the rest is not CPU

Frames are still ~44 ms on the emulator with the recording free. What is left is
`nSyncAndDrawFrame` — the UI thread waiting on the **RenderThread**, which still rasterises 1,000
meshes every frame. Caching a *recording* removes CPU re-recording; it does not remove
rasterisation.

The fix for that is `RenderNode.setUseCompositingLayer(true, null)`, which rasterises once into a
texture so a pan is a textured blit. **It was not done, on purpose.** The recorded region is 4× the
viewport's area — 5120×3200 device pixels on this device, ~65 MB, and over `GL_MAX_TEXTURE_SIZE`
on some GPUs — and a layer that fails to allocate degrades silently. More importantly the software
GPU here inflates rasterisation cost enormously; on a real Adreno 1,000 mesh draws is a few
milliseconds. **Do not size this change against an emulator.** It needs the tablet.

Smaller and safe, also not done: `SelectionOverlayView` and `ToolOverlayView` are invalidated by
every camera write even when they draw nothing (no selection, no ruler, no laser). The pan profile
shows 392 `LayoutNode.invalidateLayer` calls and 421 `updateDisplayListIfDirty`. Guarding those
setters on "drew something last time, or would draw something now" is a few lines.

---

## 2b. ⚠️ Writing was worse than panning, and the first fix did not touch it

The user came back after the cache landed: *"i have a note that only has 800 strokes (it should
be able to handle significantly more) and its legging"*. They were right, and §2 had fixed the
wrong half.

Measured while **drawing** at ~1,100 strokes: **133 ms median frame**, 85% janky, GPU 13 ms — CPU
again. The cache was working exactly as designed and was irrelevant, because `store.revision`
changes on every finished stroke and the recording is keyed on it.

Two wrong turns worth recording, both from trusting the wrong instrument:

- ⚠️ **A `--sampling` trace's call *counts* are statistical, not exact.** The profile said
  `DryStrokesView.record ×3` against `NoteStore.saveUndo ×2` and made the undo write look like the
  dominant cost. Moving `saveUndo` off the UI thread changed the median from 133 ms to 150 ms —
  i.e. nothing. **Inclusive times in a sampling trace are also inflated by recursion**:
  `StreamingJsonEncoder.encodeSerializableValue` showed 757 ms across 95 calls purely because JSON
  encoding nests and every level counts the whole subtree.
- The instrument that settled it was the same one that settled §2: a `Log.i` counting re-records
  and timing them. **5 re-records for 30 strokes, 48–105 ms each.** No ambiguity, no sampling.

**The fix — an append tail.** `StrokeStore.appendsSince(revision)` answers the objects added since
a revision, **or null meaning "re-record everything"**. `DryStrokesView` replays the cached node
and draws the tail over it.

The safety argument is arithmetic rather than bookkeeping, and that is the point. `add` is the one
path that files an entry and it bumps `revision` by exactly one when it does, so the filed
revisions must come out **contiguous and complete** across the queried range. An erase, a
transform, a peer's op, an undo, a restyle, a delete — each bumps `revision` without filing
anything, leaves a gap, and is refused. **A mutation site added later that forgets this list costs
a re-record; it cannot leave a stale picture on the glass.** The opposite design — a flag each
mutation must remember to clear — fails the other way, and CLAUDE.md has six entries about
features that were correct in every test and wrong in the app.

Two render-order conditions gate an entry, both checked at append time: the object's key must
still be the page's top (`SetZOrder` may have put something above it), and an ink stroke is
refused while the page has highlighters, because the recording holds both bands and SPEC §3.4
draws the highlighter band **last** — appending ink after it would put the writing over the
highlight instead of under it.

| drawing at ~1,150 strokes | before §2 | after §2 | after the tail |
|---|---|---|---|
| 50th %ile frame | 133 ms | 150 ms | **65 ms** |
| 90th %ile | 250 ms | 200 ms | **85 ms** |
| re-records per 30 strokes | 30 (one per stroke, uncached) | 5 | **0** |

Verified on the emulator that the refusal path really fires: drawing 3 strokes logged **0**
re-records, then **Undo logged 1** and the framebuffer changed (different `screencap` hash, ~800 KB
so not a blank or garbled capture — `docs/M8-PERF-UNDO.md`'s warning about believing a bad
instrument).

⚠️ **`saveUndo`'s dispatcher must not be `Dispatchers.IO`.** `NoteEditor`'s tests inject an
`UnconfinedTestDispatcher` for `drainDispatcher`; a hard-coded `Dispatchers.IO` escapes `runTest`'s
scheduler and **six tests failed on real thread-pool timing**. It reuses `drainDispatcher`, which
is also the more honest dispatcher: the 62 ms is `kotlinx.serialization` encoding, not the write.

---

## 2c. Zoom — the case §2 deliberately excluded, and the user found it

> its much better but you can definitely still feel it like when you zoom out there is some
> input lag

Correct, and predicted by §2's own text: the recording was keyed on **exact** scale, so a pinch
re-recorded the whole page on every frame of the gesture. §2 argued that was acceptable because it
was "no worse than the unconditional re-record every frame used to do" — which is true and is not
the same as *good*. Once panning and writing were free, the one remaining per-frame re-record was
the one the user could still feel.

**The fix.** A zoom within [`ZOOM_REUSE_TOLERANCE`] (1.3×) replays the existing recording, and a
`settleRecording` runnable posted 120 ms after the last camera change re-records at the exact
scale.

What is and is not approximate matters here. The recording is a **display list**, so its geometry
re-rasterises exactly at whatever scale it is replayed under — nothing is stretched or blurred.
The *only* stale thing is the antialiasing feather uniform, by at most 1.3×, for at most the
length of the gesture plus 120 ms. The tolerance is deliberately small for that reason: a soft
edge is the exact symptom of the tablet bug this file's KDoc opens with, and a bounded
approximation held briefly must not be mistakable for it.

1.3 also keeps the zoom inside the *region* window: the recorded region is 2× the viewport
(`RECORD_MARGIN_FRACTION = 0.5`), so a zoom-out within tolerance cannot reach its edge and expose
the blank margin `docs/M8-PERF-UNDO.md` warned a cached layer would show.

⚠️ **Measured only as a mechanism, not as a feel.** `adb shell input` is single-pointer and
`motionevent` has no multi-pointer form, so **a real pinch cannot be injected on the emulator at
all**. What was verified there is the bound: 16 toolbar zoom changes produced **16** re-records —
one per change, no per-frame runaway. The win for a continuous pinch (~60 scale deltas per second,
each of which used to cost a 48–105 ms re-record) is reasoned, not measured, and **needs the
tablet**. Worth looking for specifically: any visible "re-sharpen" pop 120 ms after a pinch ends,
which would mean the tolerance is too wide.

---

## 2d. The draw-call wall, and why the answer is not "try harder at culling"

> i just opend a note with more then 2000 strokes and it ran horribly

§2–2c removed every *CPU* re-record. What is left is that replaying a cached display list still
**submits one draw call per stroke, every frame**. Researched properly this time, with `javap`
against the actual AARs in the Gradle cache rather than against documentation:

- ⚠️ **There is no batched draw in androidx.ink 1.0.0, and there cannot be one.** Every public
  entry point (`CanvasStrokeRenderer.draw`, `StrokeDrawScope.drawStroke`) takes exactly one
  `Stroke`; everything else in `ink-rendering` is in `…canvas.internal`. `PartitionedMesh` and
  `Mesh` have no public constructor, builder or merge — both are native-pointer wrappers. So
  `PagePainter`'s one-call-per-object loop is not naive, it is the only thing the library offers.
- ⚠️ **Skia cannot batch them either.** `MeshOp::onCombineIfPossible` rejects custom meshes on its
  first line (`if (!fMeshes[0].isFromVertices() …) return kCannotCombine;`).
- **Each call costs far more than a `drawMesh`**: two JNI `populate()` round trips to read the
  first and last `StrokeInput`, a `WeakHashMap` lookup, a `MutableObjectLongMap` put, and — inside
  the loop CLAUDE.md rule 7 governs — **androidx.ink allocates one boxed `java.lang.Long` per
  stroke per pass** (`Long.valueOf(SystemClock.elapsedRealtime())`, far outside the valueOf cache).
  It also does a full map scan every 2 s. None of this is suppressible while calling `draw` per
  stroke, which is an evidenced argument that the fix must reduce *the number of calls*.
- **Two lint-clean escape hatches exist**, both public and unannotated:
  `AndroidGraphicsConverter.outlinesToPath(PartitionedMesh, Int): Path` (merge N strokes of one
  colour into one `Path`, one `drawPath`), and `Mesh.getRawVertexData()/getRawTriangleIndexData()`
  (concatenate into one `android.graphics.Mesh`). The second needs a hand-written AGSL
  `MeshSpecification` — i.e. owning the antialiasing feather this file's KDoc opens with. Neither
  is free of visual risk.
- ⚠️ **Below API 34 there is no mesh renderer at all** — `CanvasStrokeUnifiedRenderer`'s
  constructor adds it only when `SDK_INT >= 34`, and `minSdk` is 30. Perf validated on API 35 says
  nothing about 30–33, where every stroke goes through `CanvasPathRenderer` instead.

### What was actually built here, and why it stops short of the redesign

**The diagnosis is not yet established, and building the redesign before establishing it would be
the M8-follow-up-3 mistake again** (widening a threshold twice before asking what the feature was
a function of). So this pass shipped the instrument and one real bug, not the cache.

⚠️ **`FrameStats` reads `COMMAND_ISSUE_DURATION`, not just `DRAW_DURATION`.** This is the whole
point: `DRAW_DURATION` is the UI thread *recording* a display list, and the draw-call wall is the
RenderThread *submitting* it. With a cached recording the first is near zero and the second is the
cost — so without both numbers the panel can say "waiting on the GPU" but cannot separate
submission (which a rasterised cache removes) from shading (which it does not). The jank threshold
also comes from `Display.refreshRate` now; a hardcoded 60 reports a stuttering 90 Hz Tab as perfect.

⚠️ **`loadPage` now calls `pruneFullyErased()`, and this was a real bug.** `SetErasedRegion` is an
absolute register and a fully erased stroke is *not* deleted — correct and lossless — so the fold
re-inserts it from disk with its region restored, and it then pays a `saveLayer`, a mesh draw and
a `DST_OUT` `drawPath` **every frame, to render nothing**, for the life of the session. The only
other caller was `EraserController.end`, so the cost was reclaimed only if the user happened to
erase again. A page rubbed out and reopened carried it forever.

Also fixed: `appendsSince` returned a freshly `map`ped list — an allocation of up to 48 entries in
`onDraw`, in the exact loop §2b added to make writing cheap. It fills a caller-owned list now.

### The measurement that decides the next step

The Measure panel now reports, on the device, with no adb:

```
Frames: 15 fps · mean 64.5 ms (draw 5.8 · issue 15.7) · worst 254.3 ms · 76 of 76 over 60Hz
Page: 1185 objects · 0 erased
```

Read `draw` and `issue` together. **draw low + issue high** = thousands of submissions, and a
rasterised cache is the fix. **Both low against a large total** = pixel shading or a stalled swap,
and it is not. A large `erased` count means the per-frame `saveLayer` render-pass switches are the
complaint instead — which on a tile-based deferred Mali is a very different and much more likely
cause of "horribly" than of "30 fps". `has highlighter` matters because **one highlighter anywhere
disables the append fast path for every pen stroke on the page** (`noteAppendedOnTop` refuses ink
while the highlight band is occupied).

⚠️ **The emulator's numbers above are worthless for this decision** — `emulator-5556` rasterises
through the host GPU, so the shading share is enormously inflated and the submission share
correspondingly understated. The Galaxy Tab's own panel reading, on the user's own 2,000-stroke
note, is the only evidence that counts.

### The tablet answered, and it was the draw calls

On the user's own >2,000-stroke note, read off the Measure panel on the Galaxy Tab:

```
Frames: 3 fps · mean 335 ms (draw 7.7 · issue 80 climbing) · worst 2336.0 ms · 500 of 560 over 60Hz
```

`draw 7.7 ms` — the UI thread was never the problem after §2–2c. **`issue 80 ms`** is the
RenderThread submitting ~2,000 draw calls, and the ~247 ms unaccounted for by the two is the GPU
shading them. That is the fork resolved: submission and shading both scale with the number of
strokes, and both are what a rasterised cache removes.

**Built:** `DryStrokesView.updateCompositingLayer` promotes the page node to an HWUI compositing
layer via `setUseCompositingLayer(true, null)` — **only when `recordedScale == camera.scale`
exactly**. HWUI then rasterises the recording into a texture when its display list changes, and
every frame after that is one textured quad.

The exact-scale gate is the entire safety argument, and it is why this is not the risky version of
the idea. A stretched *display list* re-rasterises exactly, which is what makes §2c's 1.3× zoom
tolerance sound; a stretched *texture* is resampled, i.e. soft. So the layer exists only where it
is provably a 1:1 blit and is off for every frame of a pinch, which then behaves exactly as the
previous build did. Every way the gate can fail degrades to the shipped build, never to a wrong
picture.

⚠️ **The first version of it shipped pixelated, and the mechanism is worth knowing.**
`RenderNode.setPosition` defines the node's size, and a node promoted to a compositing layer is
rasterised into a texture **of exactly that size**. The node was positioned with the *page-space*
rectangle — about 918×574 for a Letter page at fit-width — so the layer was a 918×574 texture that
the camera then magnified to fill a 2560×1600 viewport, roughly 2.8×. The user's report named the
gate precisely: *"when you dont move around everything is very pixelated"* — the layer engages only
at rest, so the artefact appeared exactly when they stopped to look.

The fix is that **the node is positioned in device pixels**: its rectangle is the recorded region
mapped through the camera, the recording undoes that origin and then applies `pageToView` itself,
and [drawNode] maps *record-time device space* onto *current device space* (a scale of
`C.scale / R.scale` and a translation of `C.translate − s·R.translate` — a pure translation at the
same scale, which is every frame the layer is allowed on).

The Measure panel now prints the texture size in every state — `Dry layer: on 4142×2498 (max
16384×16384)` against a 2560×1600 viewport. **That number being at least the viewport's size is the
whole check**, and it turns a rendering investigation into a glance. It is also a caution about the
instrument: the emulator's `screencap` came back garbled (the launcher composited over the app —
the same corruption `docs/M8-PERF-UNDO.md` records), so the pixel evidence was worthless and the
*number* is what settled it.

Two further consequences, recorded because they are not obvious:

- ⚠️ **`RECORD_MARGIN_FRACTION` dropped from 0.5 to 0.25, for memory rather than time.** The layer
  is a texture the size of the recorded region *in device pixels*. At 0.5 that is 2× the viewport
  each way — 5120×3200 and ~65 MB on a 2560×1600 tablet, close enough to `GL_MAX_TEXTURE_SIZE` on
  a mid-range Mali to be refused. 0.25 is ~37 MB and still half a screen of pan slack. It is now
  coupled to `ZOOM_REUSE_TOLERANCE`: the region is 1.5× the viewport and the tolerance is 1.3×, so
  a zoom-out within tolerance still cannot reach the recorded edge.
- ⚠️ **An oversized layer is dropped silently** — no crash, no log, just the slow path, presenting
  as "fast on my tablet, slow on yours". The size is checked against the canvas's own
  `maximumBitmapWidth/Height` rather than a hardcoded 4096/8192, and the outcome is printed in the
  Measure panel (`Dry layer: on (max 16384×16384)` / `REFUSED (too big for …)`).

Emulator, same note, clean pan: `issue` **15.7 → 5.6 ms**, mean 64.5 → 44.9 ms, layer reported
`on`, and a `screencap` confirms the page still draws correctly. ⚠️ That emulator delta badly
understates the expected gain — its software rasteriser dominates the total either way. The
tablet's `issue 80 ms` and ~247 ms of shading are what this targets, and **that measurement is
still owed**: the tablet disconnected before the build could be pushed.

### The design behind it, and the two alternatives rejected

Judged against two rejected alternatives (a tile grid, and a merged-`Path` LOD proxy):

**A single viewport-sized `RenderNode` positioned in device pixels, promoted to a compositing
layer only at exact 1:1 scale.** The exact-scale gate is what makes it safe — a stretched *display
list* re-rasterises exactly, but a stretched *texture* is genuinely soft, so the layer must exist
only where it is provably a 1:1 blit and degrade to today's behaviour everywhere else. That also
means §2c's `ZOOM_REUSE_TOLERANCE = 1.3` and its KDoc argument survive unchanged.

Two traps recorded so they are not rediscovered: the layer's screen origin is
`camera.translate + recordedLeft × recordedScale` and **both** terms must be snapped to whole
device pixels or the composite is half-pixel soft — which is the same symptom class as the
original feather bug and will be misdiagnosed as it. And `GL_MAX_TEXTURE_SIZE` must be read at
runtime off the `RecordingCanvas` and surfaced in the panel; an oversized layer is dropped
silently, presenting as "slow on that one tablet".

The tile grid was rejected as a *first* step: tiles are earned by damage-driven re-raster, not by
draw calls, and a grid with layers off is bit-identical to today for pure new risk. The merged-
`Path` proxy was rejected outright — it switches antialiasing weight at the moment the user stops
and looks, which is the same symptom class again, and its central performance claim (that Skia
caches the tessellation of one ~800k-point path across frames) is unverified.

---

## 5. Opening a note — every stroke was tessellated **twice**

> i think there needs to be some sort of cashing because when you enter a note with a lot of
> strokes it takes a while to load

⚠️ **The obvious reading of that request would have built the wrong thing**, and so would my first
measurement. A sampling profile of a cold open showed ML Kit's recognition thread with more than
twice the main thread's samples, and I reported that as the cause. It is not: recognition runs on a
*background* thread and does not block the open. **A sampling trace attributes badly and its call
counts are statistical** — the same trap as §2b, made twice in one session.

The hard evidence, measured off the device:

| | |
|---|---|
| `Skipped 190 frames!` then `Displayed +3s632ms` | ~3.2 s of unbroken main-thread work on a 1,187-object note |
| `journal.ops` = 583,290 B, 83 records, **1,190 ops** | all decoded on open |
| `undo.json` = 597,640 B, 199 entries, **1,898 ops** | decoded on open too, and never reset by materialize |
| CRC-32 throughput | **387 MB/s** — ~1.5 ms, i.e. *not* the problem, contrary to the earlier audit |

The dominant term is `Stroke(brush, inputs)` — **native tessellation, on the UI thread**. Every
other stage is 12–50 ms of a ~3,600 ms open.

### And it ran twice

`NoteEditor.openOrCreate` called `display(...)` and then `bindSurface()`. `display` sets
`currentPage` and calls `loadPage`; `bindSurface` is guarded on `currentPage?.let` — which `display`
has just set — so it launched `loadPage` **again**. Every stroke on the page was decoded and
tessellated twice, on the UI thread, on every open.

The fix is a reorder, not a new guard, and that distinction is the point: **binding first makes the
duplicate unreachable rather than merely unlikely.** `bindSurface` then runs with `currentPage` null
and only installs the recorder; `display` performs the single load. The other ordering — a surface
attaching *later* — is unchanged and still loads exactly once, because `onSurfaceAttached` finds
`currentPage` set and is then the only loader. Both paths end at one load.

Safe because `bindSurface` needs only `session`, which is assigned above it, and because
`StrokeStore.reset()` inside `loadPage` clears `ink`, `highlight`, `byId`, `shapes`, `tombstones`
and `topZ` but **not** `recorder` — so the recorder installed first survives the load that follows.

### Still owed

Halving the work does not make tessellation cheap; it is still ~1.8 s of main thread on that note.
The next steps, in order, are to get the decode off `Dispatchers.Main.immediate` and to add an
open-path readout (`loads`, `mainThreadBlockedMs`, per-stage times, object/sample counts) to the
Measure panel — because **`loads = 2` before and `1` after is the only way the tablet can confirm
this reorder without adb**.

> ⭐ **Done, in `docs/M8-UNDO-LAG.md` §4** — reached from the other end, by a user reporting that
> undo *"still hitches sometimes especially when you first open the note"*. The decode, the fold and
> the tessellation are all off the UI thread now (`PreparedPage` / `StrokeStore.install`), and
> `loads` is asserted rather than displayed: `NoteEditorBindingTest` fails if a page is loaded twice.
>
> ⭐ **And the question this section left open was already answered in the repo.** *"Can
> `androidx.ink` build a `Stroke` off the main thread?"* — `InkPageThumbnailRenderer` has done
> exactly that on `Dispatchers.Default` for every library thumbnail since **M3**. The two rejected
> designs above stand: nothing paints a cached raster over the load, and nothing streams in waves.
> The z-key hazard this section names is handled by refusing the pen while `pageLoading` is true.

⚠️ **Two things the design pass rejected, recorded so they are not revived.** Painting a cached
raster over the load would make a frozen page *look* ready and eat the pen — and worse, a stroke
drawn against the reset store gets a base z key while 2,000 objects then install above it, which is
M8a's `lastZ` bug recreated by a loading screen. And streaming strokes in waves must, if it ever
ships, put every **non-stroke** object in wave 1: `dryObjectFor` marks `routesDirty` when a shape
lands, so a naive z-ordered stream draws every connector straight and then snaps it.

---

## 3. Preview — the thumbnail disk tier was dead code

**Measured:** rendering one 1,000-stroke cover took **21.4 seconds** (from the WebP's own mtime,
against the previous file's). Six small covers rendered serially at ~0.8 s each before it —
`MAX_CONCURRENT_RENDERS = 2`.

Two compounding defects, both confirmed against the source before anything was changed:

1. **`Thumbnails.cover`'s miss path wrote the WebP and never recorded `thumbnailRev`.** Tier 2 is
   gated on `hasFreshThumbnail` (`thumbnailRev != null && thumbnailRev == localRev`), and
   `recordThumbnail`'s only production caller was `LibraryRepository.noteMaterialized`. So the file
   an on-demand render had just written **could never be read back by the next on-demand render**.
2. **`noteSaved` called `thumbnails.invalidate` on every 2-second autosave tick**, which
   recursively deletes the note's whole page-thumbnail directory. While a user writes, the page
   thumbnails the overview sheet needs were destroyed every two seconds.

**Fixed:** `storeCover` is now the only place `cache.writeCover` is called and it writes the file
and the revision together, so writing one without the other has no call site. Page thumbnails got
a revision guard of their own (per-process, because the WebP carries no revision — the trade-off
is in its KDoc) instead of being deleted on a timer, and `noteSaved` now only forgets the decoded
cover in memory, with no filesystem work at all.

**The staleness argument, because this is the half that could show a wrong picture:** the revision
recorded is `note.localRev` off the **immutable snapshot taken before the permit and before the
fold** — never re-read afterwards. A save landing mid-render therefore makes the record *older*
than the bytes, which reads as "not fresh" and costs a re-render. Under-recording is the only
direction available, and a redundant render is the failure mode.

Verified: 9 new JVM tests in `ThumbnailsTest` (real `LibraryIndex` over an in-memory driver, real
`ThumbnailCache` over `InMemoryFileSystem`), plus an emulator walk — write in a note, return to the
library, cold start twice — with the cover's mtime unchanged across both revisits.

⚠️ **Not fixed: the cost of a render itself.** A thumbnail still folds the page and tessellates
every stroke at full page-point resolution and then draws it through software path rendering. The
cache now means it happens once instead of every library visit, which is the difference the user
sees; the 21 seconds is still 21 seconds the first time.

---

## 4. Opening a document — a real bug found, and **it is not the fix**

`Compaction.isAllowed` needs `journalBytes >= 2 MB` **and** `age >= 30 days`. Every production
caller of `compactIfDue` **materializes immediately before it**, and `materialize` sets
`modifiedEpochMillis = now` and returns `journalBytes = Journal.HEADER_SIZE` (8). The gate asked
*"is this 8-byte journal over 2 MB, and is a note modified this instant over 30 days old?"* — no
and no, on every note, at every close, since M2. **Compaction has never fired in production.**

That is fixed (size now counts the bundle's ops too; age comes from a `contentChangedEpochMillis`
that `materialize` does not reset), with 8 new tests including one that pins CLAUDE.md rule 9 — a
snapshot written *through the live gate* is still discarded by a late peer op.

⚠️ **But this does not make opening faster, and the next session must not assume it did.**
`NoteSession.pageLog` builds `PageLog(ops = loaded.ops + unflushed, snapshot = …)` where
`loaded.ops` is the **whole** bundle log, and `PageLog.refold` absorbs every op it holds. The
snapshot is only a seed base, so it saves **zero** fold work today and adds a snapshot write per
live page. `PageLog`'s own KDoc says it holds "every op **since the snapshot**" — the caller has
been violating that contract, which is why the dead gate was never noticed.

**Do not "fix" this by filtering ops in `NoteSession.pageLog`.** `PageLog.ops` is what
`materialize` writes back into the bundle and what `merge` subtracts peer opIds against, so
dropping ops there deletes the raw log SPEC §4.7 requires be kept and regrows the journal by the
note's size on every pull. The real change is for `PageLog` to separate *the ops it holds* from
*the ops it folds* — core convergence code, and it needs the 21-row matrix against a real
Nextcloud, not a JVM run.

### What actually makes opening slow, all confirmed, none fixed

- `InkSurfaceView.loadPage` decodes and **natively tessellates every object on the page
  synchronously on the UI thread**, in one unyielding loop. `NoteEditor`'s work scope is
  `Dispatchers.Main.immediate`, so the fold is there too.
- The whole journal is read, CRC-32'd **byte-at-a-time in Kotlin** and fully decoded on open — and
  again on the first page-turn after every flush.
- Every bundle entry read CRC-32s its whole payload the same way; `OpCodec.decode` allocates a
  `filterKeys` map per op for unknown-field forward compatibility it usually does not need.
- `materialize` decodes and re-encodes **every page**, including pages nothing touched; the page
  cache is 6, so a large notebook misses almost every time.

Cold start into the library measured 40.8 s and 32.5 s before, 5.2 s and 3.7 s after — but **load
average was 4.3–4.8 for the first pair and ~1.0 for the second, so that is not a clean A/B and
should not be quoted as one.** The thumbnail fix is established by the mtime walk and the unit
tests, not by those numbers.

---

## 5. Also fixed while in there: a per-frame scan, and a real correctness bug behind it

`StrokeStore.rerouteConnectors` linearly scanned every object on every drawn frame — 5,000 float
compares on a page of handwriting with **zero** connectors, to find nothing. The class KDoc already
claimed the pass was "O(connectors), not O(objects)", which was true of the work and false of the
scan that found it. It now walks an incrementally-maintained shape index.

It indexes **shapes**, not connectors, deliberately: "is a connector" is a property of the
`SetTextContent` register and can be turned on and off, so a connector-keyed index needs a
notification at every writer of that register and a forgotten one is a silent
"the arrow doesn't follow the box". "Is a shape" is a property of the object's kind and cannot
change while it is on the page.

⚠️ **The eraser's whole-object branch removed objects without recording a tombstone or dirtying
the routes** — so rubbing out a box with the eraser left a connector attached to it, reporting
`ATTACHED` at coordinates the box no longer occupied, until some unrelated edit dirtied the routes.
Deleting the same box via `DeleteObject` was always correct, which is why nothing caught it.
Removal now goes through one choke point. 11 instrumented tests on the emulator, all passing.

---

## 6. Evidence table

| Claim | How it was checked |
|---|---|
| Dry layer re-recorded per frame; now does not | `Log.i` counter in `record()`: **0** re-records across a 10-pan sequence; `am profile` of the main thread shows no app drawing methods |
| Frame time at 1,000 strokes | `dumpsys gfxinfo` percentiles, load recorded beside each |
| Thumbnail render cost | the WebP's own mtime — 21.4 s for the 1,000-stroke cover |
| Cover cache now reused | emulator walk: write, revisit library, cold start ×2, mtime unchanged; + 9 JVM tests |
| Compaction gate was dead / is now reachable | 8 new JVM tests in `NoteSessionCompactionTest` |
| Connector index + the eraser tombstone bug | 11 instrumented tests on emulator-5556, 0 failures |
| JVM suite | **938 tests, 0 failures** — `testDebugUnitTest` across all modules, `--rerun` per task with `--no-build-cache`, task lines read back to confirm neither `UP-TO-DATE` nor `FROM-CACHE` |
| The physical Galaxy Tab | ❌ **not checked** — `offline` all session. Definition of done #4 unmet |

---

## 7. What the next session should do, in order

1. **The tablet.** Every frame number here is emulator. The GPU-side question in §2 cannot be
   decided without it.
2. **Open-time**, which is untouched: get `loadPage`'s decode + tessellation off the UI thread, and
   stop CRC-32ing megabytes a byte at a time in Kotlin. This is the symptom with the least done
   about it.
3. **The `PageLog` op separation** in §4, so compaction earns the snapshot it now writes.
4. Only then the compositing layer, and only with before/after frame times on the tablet.
