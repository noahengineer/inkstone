# Elements follow-up — handwriting in Elements, drag-and-drop, and knockout

> *"The elements thing does not seem to work too well. I want to be able to create an element then
> drag and drop an element into the note. Also I would like to make it so that you could apply a
> non-destructive erase effect on shapes/elements — for example I create an icon and I can drag it
> on something, and since there is some transparency in that icon it non-destructively erases the
> stuff behind it."*

Asked before building, because the three readings build different things: **filled silhouette**
(chosen), bounding box, or stencil. SPEC §4.9.6 is the normative account of what knockout is.

## 1. Why Elements "did not work too well"

Three separate things, and only the first was a bug in the usual sense.

1. **An Element could not hold handwriting.** The app had been running M8c's *stand-in* library in
   `:app` the whole time. Its KDoc said so — *"It should not, and this is a stand-in … moving this is
   a change of import"* — and the move never happened. The stand-in skipped every stroke because
   `:app` cannot measure one (SPEC §5.4) and `InkSelection` gave out a count rather than ids. So a
   hand-drawn icon, which is what a person makes an icon *with*, saved as *"Nothing in that box could
   be saved yet"*. `:core:storage`'s `ElementLibrary` — complete, tested, holding every kind — had no
   caller.
2. **There was no drag.** M8c built tap-to-arm then tap-to-stamp and argued a drag was *"a second
   gesture system across an `AndroidView` seam"*. There is no seam: the panel and the page overlay are
   both Compose in one tree.
3. **The panel was a list of names.** Nothing showed what an Element looked like or how big it was.

## 2. What changed

| | Before | Now |
|---|---|---|
| Library | `:app` stand-in, `elements/index.json` | `:core:storage` `ElementLibrary`, `elements/<id>.element`, behind `ElementShelf` |
| Handwriting | skipped | copied as its geometry blob; origin from `InkSurfaceView.boundsOf` |
| Capture | Elements tool box → area | Elements tool box → `InkSurfaceView.objectsWithin` → ids; **or** *Save as Element* on any selection |
| Placing | tap with one armed (top-left at the tap) | **drag from the panel** (picture: immediately; row: after a long press), or tap with one armed — both **centred** on the point |
| After a drop | nothing selected | what landed is selected and the Select tool is active, so it can be moved at once. A tap-stamp keeps the Elements tool, so arming still stamps many |
| Panel | names | a picture of each Element, drawn by the page's own painter (`ElementPreviews`) |
| Old saves | — | migrated once on first open of the panel; the index row is removed, the picture files are left on disk (rule 1) |

The drag ghost is drawn at the page's own scale, centred on the finger — *"how big will this be"* is
the question a drag answers. Letting go over the panel cancels.

## 3. Knockout

`ObjectFormat.knockout` (`ko=1` in the `SetFormat` register), toggled by the **Knockout** chip on the
selection bar and the image bar. Nothing beneath is written to.

- **Silhouette**: the object drawn into a picture, the outside flooded from the border, gaps under
  3 pt closed, eroded one pixel (`SilhouetteMask`, `PagePainter.silhouetteOf`). Cached on the
  `DryObject` in its own space, so dragging a knockout never rebuilds it. A text box uses its frame.
- **Screen**: objects are drawn into a layer; a knockout clears its silhouette from everything drawn
  so far, repaints the knockouts beneath it clipped to that silhouette, then draws itself. The
  highlight band gets its own layer, interleaved by `(z, id)`. A page with no knockout pays nothing.
- **Knockouts never hide each other** — the case that matters is an icon of several flagged strokes.
- **PDF**: `InkStrokeOutliner` subtracts the silhouettes above each stroke and shape, exactly as an
  erasure is subtracted.

## 4. How each claim was checked

| Claim | Written | Automated | Run on the emulator (`Test_Tablet`, API 35) |
|---|---|---|---|
| A box round handwriting saves it | ✅ | ✅ `ElementShelfTest` (core save + measured box) | ✅ face of 3 strokes → `elements/<id>.element`, *3 · 65×65 pt* |
| Old `index.json` Elements are migrated | ✅ | ✅ `ElementShelfTest` (3 tests) | ✅ a real Element saved in August appeared with its picture; `index.json` → `{"elements":[]}` |
| Panel shows a picture | ✅ | ❌ | ✅ screenshot |
| Drag from the panel places it, centred, selected, Select tool on | ✅ | ❌ (Compose drag over a `SurfaceView`) | ✅ ghost at page scale; dropped where released |
| Tap with one armed still stamps, and keeps the tool | ✅ | ❌ | ✅ two taps, two copies, each centred on its tap; Elements tool kept |
| *Save as Element* on a selection | ✅ | ❌ | ✅ chip present; not pressed through to a save |
| `ko=1` round-trips; unknown values survive | ✅ | ✅ `ObjectFormatTest` (3) | ✅ 3 × `if1;ko=1` in `journal.ops` |
| Filled-silhouette rule | ✅ | ✅ `SilhouetteMaskTest` (8, JVM) | — |
| Screen: hides what is beneath, not above; not other knockouts; not the paper | ✅ | ✅ `KnockoutRenderTest` (5, pixels read back) | ✅ lines hidden inside the face, eyes kept, ruled template pixel-identical to bare paper |
| Non-destructive: undo, redo, move away | ✅ | — | ✅ line pixel 255 → 26 → 255 across undo/redo; dragging the face off brings the lines back |
| Published PDF has the same hole | ✅ | ✅ `KnockoutRenderTest.thePublishedRule…` — a `Region` over the outline, not the painter | ❌ not opened in another reader |
| Nothing else in `:android:ink` broke | — | ✅ **125 / 125** instrumented on the emulator | — |

**Build gates (Windows).** Forced reruns, no build cache: `:core:model` 378, `:core:storage` 198,
`:android:ink` 36, `:android:pdf` 90 — all green. `:app` 269 with **40 failing, every one a
`MergeMatrix*` row**, failing at `store.open` (`BundleReader.open` → null) — and still 9/9 failing in
`MergeMatrixLayerOneTest` with this change's one line on that path reverted, so it belongs to the
uncommitted bundle/ZIP-reader work in the same tree (`NoteStore`, `Zip`, `Bytes`, `FileSystem.openRead`),
not to this. detekt and spotless are clean for every file this change touched; `compileKotlinIosSimulatorArm64`
for `:core:model` and `:core:storage` ran (klib compile); the framework link is macOS-only and did not.

⚠️ The PDF row is the weakest: the outline is checked by a reader that is not the painter, but no
published file was opened in a viewer that is not this app.

## 5. Known gaps

- ⚠️ **A text box or an image beneath a knockout is not cut in the published PDF.** They are page
  content, written before the annotations; the content-stream writer has no subtraction. On screen
  they are cut. (Note the converse is an existing, wider mismatch: a stroke drawn *under* a picture
  in the app is drawn *over* it in every PDF reader, because annotations paint after page content.)
- **Two separate knockout icons overlapping show through each other** — there are no groups, and the
  rule that keeps an icon's own pieces intact cannot tell one icon from two.
- **The eraser and the lasso still reach objects hidden by a knockout.** Hidden is not deleted; they
  are there, and a lasso round them selects them.
- **A knockout picture cuts nothing until its pixels have loaded**, on screen and when published — a
  picture whose bytes have not arrived from a peer has no silhouette to cut with.

## 6. Device walk, and what it cost

Drawn with `adb shell input stylus motionevent` (three ruled lines and a hand-drawn face — a loop
that was *not* held, so it stayed ink rather than snapping to an ellipse); captured with the
Elements tool's box over `input swipe`; dragged out of the panel with `input motionevent`
DOWN / 20 × MOVE / UP; Knockout pressed with `input tap`; the journal read with `run-as`.

Three things went wrong in the instrument, none in the feature:

- **A `uiautomator dump` answered from a previous screen** — the library, while the app showed a note
  with a connector selected and none of the new chips. CLAUDE.md's stale-dump warning, met again.
  The screenshot settled it.
- **`input keyevent 111` (Escape) to drop the keyboard dismissed the Save dialog**, so the first
  capture saved nothing. Tapping Save with the defaults is the reliable route.
- **A capture swipe made while the keyboard was still up ended over the IME** and opened nothing.
  `input keyevent 4` first.

## 7. Left behind

- The selection bar can sit over the Elements panel when the selection is near the top right — both
  are floating chrome and neither knows about the other. Seen, not fixed.
- Switching from Select to the Elements tool keeps the selection (and its bar) on screen, because
  `EditorTool.ELEMENT` maps to `InkTool.SELECT` and `InkSurfaceView.tool` only clears on *leaving*
  Select. Pre-existing; seen during the walk, not fixed.
