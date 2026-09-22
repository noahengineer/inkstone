# M10 — the redesign

What the app looks like now, why, and the handful of things the design asked for that could not be
built as written.

The source of truth is two documents the user supplied together: a rendered reference page
(`aura_notes_typography_ui.html`) and a written brief. Where they disagree, **the HTML wins** — a
rendered artefact is a specification and a prose adjective is not — and §1.1 lists every place they
did.

---

## 1. The design, in one paragraph

A near-black canvas with a 24 dp dot grid. All chrome floats on it as translucent pills and panels
with a 1 dp hairline edge. Controls are **words, not icons**: uppercase, tracked out, in a chip that
inverts to near-white when it is the active one. Numbers are monospaced. The editor's controls are
organised by *progressive disclosure* — a two-row Dynamic Dock whose second row swaps with the mode
above it and collapses entirely when the mode has nothing to offer, plus contextual toolbars that
spawn beside the object they act on and nowhere else.

### 1.1 Where the two documents disagreed, and what shipped

| | Brief | HTML | Shipped |
|---|---|---|---|
| Canvas | `#121212` | `#09090B` (zinc-950) | **`#09090B`** |
| Accent | Electric Cyan `#00E5FF` | active chip zinc-200 `#E4E4E7`; selection sky `#38BDF8` | **both, in their HTML roles** |
| Zoom controls | bottom **right**, own widget | bottom **left**, merged with undo/redo | **bottom right** (the brief's zones are the more considered layout) |
| Primary modes | 7 (`DRAW ERASE SELECT TEXT IMAGE ELEMENTS RULER`) | 7, different set (`… MEDIA SHAPES RULER`) | **8** — see §3.2 |

`Ink.Canvas` and `Ink.Focus` in `InkTokens.kt` are the two lines that change if the brief's hues are
wanted instead.

---

## 2. ⚠️ There is no backdrop blur, and there cannot be one

The brief asks for `backdrop-filter: blur(12px)`. This is the one requirement that is impossible
rather than merely expensive, and it is worth writing down so it is not attempted again:

- `Modifier.blur` blurs a composable's **own** content, not what is behind it.
- Blurring a backdrop means capturing the pixels underneath, and on the note screen those pixels are
  not the app's to read. `InProgressStrokesView` front-buffers through a `SurfaceView` that
  SurfaceFlinger composites over the window (SPEC §3.2) — the same fact that stops wet ink carrying
  a blend mode. A blur pass over the editor would sample everything *except* the ink.

What actually produces the effect at these values is the **translucency**, not the blur:
`Ink.Panel` is zinc-900 at 65 % over a near-black canvas, so a panel is a lighter rectangle with a
hairline edge and a long soft shadow — which is what the mock reads as. The blur would be visible
only where the design deliberately has nothing detailed to blur.

---

## 3. The editor

### 3.1 Files

| File | What it is |
|---|---|
| `ui/InkTokens.kt` | the palette, radii, spacing and type ramp — every value read off the mock |
| `ui/InkstoneTheme.kt` | the same palette projected onto Material 3's roles, so dialogs/sheets/menus follow |
| `ui/Glass.kt` | `GlassBar`, `GlassPanel`, `TextChip`, `Segmented`, `MicroLabel`, `Readout`, `SwatchStrip`, `SlimSlider`, `rememberDotGridBrush` |
| `ink/EditorDock.kt` | the two-row Dynamic Dock |
| `ink/DockRows.kt` | row two, per mode |
| `ink/ContextBars.kt` | the three floating contextual toolbars and their anchoring |
| `ink/CanvasWidgets.kt` | history (bottom left), zoom (bottom right), the diagnostics panel |
| `ink/ColorPicker.kt` | the extended colour side panel |
| `note/PageBar.kt` | the page bar (bottom centre) |
| `ink/RecordButton.kt` | the record chip / running pill (top right) |

`ink/InkToolbar.kt` and `ink/ObjectToolbars.kt` are **deleted**. That card was M1's deliberate
placeholder — ROADMAP M1: *"minimal floating toolbar. **Don't design the toolbar here** — M10 owns
it."* — and by M12 it had accreted eight rows, all present at all times.

### 3.2 ⚠️ Row one has eight chips, not seven

`DRAW · ERASE · LASER | SELECT | TEXT · IMAGE · ELEMENTS | RULER`

Two deliberate departures from the brief's list:

- **Laser is an eighth chip.** `EditorTool.LASER` exists, is wired through the surface, and is
  reachable from nowhere else. Dropping it to match a list of seven would be CLAUDE.md's
  definition-of-done #6 — a feature correct in every test and inert in the app — introduced on
  purpose.
- **Ruler is in row one but is not in the exclusive group.** The brief lists it beside the tools;
  `EditorTool`'s KDoc explains why it cannot be one of them — the pen keeps drawing while the edge
  is out, so making it a tool would make *"the ruler is out"* and *"I am drawing"* mutually
  exclusive, which is the opposite of what a ruler is for. It sits after a divider as a toggle, and
  contributes its own controls to row two **in addition to** whatever the current tool contributes.

### 3.3 Row two, per mode

| Mode | Row two |
|---|---|
| `DRAW` | brush segmented · width slider + `N.N dp` · 3 swatches + `+` · `SNAP` · `ARROW` |
| `ERASE` | `PART` / `WHOLE` segmented |
| `SELECT` | `LASSO` / `BOX` segmented (+ `DESELECT` when something is held) |
| `IMAGE` | `GALLERY` · `CAMERA` — everything *done to* a picture is contextual |
| `ELEMENTS` | `LIBRARY` toggle |
| `TEXT`, `LASER` | **collapses** — the brief names `TEXT` as the case this exists for |
| *(ruler on)* | angle field + `0° 45° 90°`, appended after a divider |

### 3.4 Contextual toolbars

Three, and **exactly one is ever on screen** — a `when` chain rather than three independent
conditions that could all be true at once:

1. a text box being typed into → font/size/align/lists/indent/colour/`DONE`
2. a sole selected image → rotate/flip/crop/uncrop/duplicate/delete (and, while cropping, *only* the
   crop's own controls — a Rotate live during a crop writes a `SetTransform` under the overlay's feet)
3. any other selection → delete/duplicate/front/back/width/fill/round/route/colour, plus M8b's
   snap acknowledgement and its `DRAW AGAIN` return leg

`InkSelection.bounds` was added to `:android:ink` for this. The controller has maintained the box for
its own handles since M8a; publishing it is what stops `:app` recomputing a second, disagreeing one —
the mistake `tapImage` made when two tools each had their own idea of where a rotated picture was.

⚠️ **The bar is placed above the object if it fits and below if it does not, and never above
`DockSafeTop`.** An object near the top of the page would otherwise put its own toolbar behind the
dock, where it is un-pressable and looks exactly like a broken feature.

⚠️ **It keeps off the grips, not just off the box.** It was anchored to `InkSelection.bounds` with
a 14 dp gap while the rotate grip floats 28 pt above that box, so on every selection the bar was
drawn over the rotate grip: *"it covers the rotation handle for the object"*. A selection's bar now
anchors to `InkSelection.chromeBounds` (the box plus the rotate grip) with a clearance of at least
`InkSelection.HANDLE_REACH_DP` — the grip's touch target, which is the same constant the hit test
uses. The arithmetic is `ContextBarPlacement`, where `ContextBarPlacementTest` can reach it.

⚠️ **It does not chase a finger — it hides.** `SelectionController` fires `onChanged` at the end of a
gesture and `onPreview` per frame, because recomputing membership per input event fanned hundreds of
recompositions a second into Compose (CLAUDE.md rule 7). The first version concluded the bar should
stand still during a drag and snap on release, and called that the better behaviour. It was not: the
bar stood over whatever the object was being dragged across — *"it only updates its position once you
let go … which makes it hard to see what you are doing"*. It now hides while
`InkSelection.isManipulating` (a move, resize, rotate or point drag, not a lasso), which flips only at
pen-down and pen-up, so the cost is unchanged. `ContextBars` still reports a bar as up while it is
hidden, or the dock's secondary row would open and shut for every drag.

### 3.5 Two things that had to move, and why

- **The Element naming dialog** moved from the Elements toolbar row into `ObjectLayers`. Row two
  collapses whenever a contextual toolbar is up, so a dialog hung off that row would have been
  un-summonable the moment anything on the page was selected — definition-of-done #6 caused by a
  layout decision.
- **SPEC §3.6's measurement controls** moved behind the `⋯` chip in the zoom widget. They are not a
  drawing tool, but they are the *only* route to the frame-rate gate's readout on a tablet where
  USB debugging is not authorised — which is the device CLAUDE.md's definition of done #4 is about.
  Without that chip the gate is scored on whether it *felt* smooth, which is what SPEC §3.6 opens by
  refusing.

---

## 4. Everything else

The library, the sync screen, the trash, the needs-attention list, the dialogs, the element panel
and the page overview are all on the same canvas with the same chips. Two structural changes worth
naming:

- **No FABs.** The design has one shape for "a thing you press", and the library's two extended FABs
  became one bottom-end pill of two chips.
- **Material 3 is kept where the design system has no replacement** — `AlertDialog`,
  `ModalBottomSheet`, `DropdownMenu`, `OutlinedTextField`, `LinearProgressIndicator`, the lazy
  layouts, `Scaffold`, `Snackbar`. `InkstoneTheme` maps the palette onto `MaterialTheme.colorScheme`,
  so those arrive correct without a call-site override. That table is what stops a Material-blue
  dialog appearing over a near-black canvas.

### 4.1 ⚠️ Two colours that are not the chrome's, on purpose

Selection handles (`SelectionOverlayView.ACCENT`), the lasso/capture preview (`PageOverlay`) and the
text box's editing border are **sky-500 `#0EA5E9`**, not the chrome's sky-400 `#38BDF8`. The
difference is the surface: chrome sits on a near-black canvas where a lighter blue is what reads;
those three are drawn on **white paper**, where sky-400 is a pale line on a bright field. The mock
makes the same distinction — its canvas objects are outlined `border-sky-500` while its focus rings
are `#38bdf8`.

Alignment guides stay pink. A guide says *where the thing you are dragging lines up with something
else*; a handle says *where you may grab it*. Painting both in the selection colour would make a
guide look like a handle that cannot be gripped.

### 4.2 ⚠️ The window, before Compose exists

Two fixes that no amount of Compose theming reaches:

- `Theme.Inkstone`'s `windowBackground` is now `#09090B`. Left at `DayNight`'s default it is *white*
  on a device in light mode, so opening a note flashed a full-screen white rectangle before a
  near-black editor.
- Both activities pass `SystemBarStyle.dark(...)` to `enableEdgeToEdge`. The default is
  `SystemBarStyle.auto`, which picks icon colour from the **device's** day/night setting — on a
  tablet in light mode that is dark icons over a near-black canvas, i.e. an invisible clock, on
  every screen, in the configuration most tablets ship in.

---

## 5. What the emulator walk found

Four things, none of which any test would have caught, and all four found by driving the app over
`adb` on a 2560 × 1600 Pixel Tablet AVD — CLAUDE.md's definition-of-done #6 check.

1. **The three bottom zones overlapped.** Aligning them independently to `BottomStart` /
   `BottomCenter` / `BottomEnd` is what the brief describes, and on a real tablet the page bar's
   eleven chips ran *underneath* the zoom widget: `SHARE PDF` and `SHARE AUDIO` were drawn, listed
   by `uiautomator`, and covered by a pill that swallowed the tap. They are one `Row` now, so the
   overlap is unrepresentable rather than merely fixed.
2. **`ChipPaddingH` was a units mistake.** The mock's `padding: … 14px` sits in a ~1280 px browser
   layout; copied as `14.dp` it becomes 14 dp either side of every chip on a 1280 **dp** screen —
   the same proportion spent twice over, and about 70 dp of the page bar's overflow. It is 11 dp.
3. **`windowBackground` does not govern the Android 12+ splash.** With only `windowBackground` and
   `colorBackground` set, launching still showed the icon on a full-screen **white** field for about
   a second before the near-black library. `windowSplashScreenBackground` is the attribute that
   does, and it needs a `-v31` qualifier because it does not exist at this project's minSdk 30.
   Re-measured after the fix: the splash is `#09090B`.
4. **Two share chips became one `SHARE` menu**, which is what the brief's chip list said in the
   first place. Both exports are still two taps, and it is a fifth of the page bar's width back.

What the walk confirmed working, by pressing it: mode switching swaps row two and collapses it for
`TEXT`; a stylus stroke lands and lights `UNDO` while `REDO` stays dead; the revision readout moves
(`r0` → `r2`), so the journal advanced; lassoing the stroke spawns the selection toolbar **above the
stroke** and collapses the dock's second row; `+` on that toolbar summons the colour side panel with
its 12 slots, wheel, R/G/B/A and hex; `ERASE` swaps row two to `PART` / `WHOLE`.

⚠️ One thing the emulator cannot tell you: `screencap` on an AVD sometimes catches a
partially-composited frame and the previous surface bleeds through, because the wet-ink layer is a
front-buffered `SurfaceView` outside the app's window. Two of the captures above show a ghosted
Android robot from the splash. It is an artefact of the instrument, not of the app — the same class
of thing as `uiautomator dump` yielding a 0-byte file (CLAUDE.md).

---

## 6. `InkstoneTheme` lost its `darkTheme` parameter

It used to take `darkTheme: Boolean = isSystemInDarkTheme()`. Translucent panels over a near-black
canvas with inverted active chips is not a scheme that has a light twin — it is a different design.
A parameter that silently did nothing would be the same lie as a `= Unit` override (CLAUDE.md
records what that cost in M6), so it is deleted. A light theme, if it is ever wanted, arrives as a
second palette rather than as a boolean.

**Paper is not themed.** `PagePainter` draws white paper and always has; a note is a PDF and the page
is the document, not the chrome. Per-notebook paper colour remains M10's other half and is
unaffected by any of this.

---

## 7. Design review, round two

Three changes after the first build was looked at.

### 7.1 No explanatory captions beside controls

*"Cuts what it sweeps"*, *"tap to use · hold to save"*, *"tap a picture to edit it"*, *"drag a box
round shapes, pictures or text"*, *"drag the corners"* and *"newest note's first page"* are gone. A
chip that needs a sentence next to it is a chip whose word is wrong, and a permanent caption is a
tutorial the user reads once and then looks past for the life of the app. The design's whole premise
is that the word on the control *is* the affordance.

Two of those captions were carrying something real, and it is recorded where the code is rather than
on the glass:

- **The Elements capture gesture is a drag, and nothing on screen now says so.** What shows the armed
  state is `ElementPanel`'s highlight. `ElementControls`' KDoc says this, because if that highlight
  ever goes, this is a gesture with no affordance at all.
- **The trash's *"kept for 30 days"*** went from the header. Each row still counts its own days down,
  which is the same fact attached to the thing it is about.

Kept, because they are values and not explanations: `1 SELECTED`, `LVL 2`, `3.0 dp`, `r2`, `94%`,
`SNAPPED · CURVE`.

### 7.2 The colour panel opens under the `+`

It was pinned to the top-right corner — the one place on screen unrelated to the control that
summoned it. `SwatchStrip` now reports the `+`'s bounds and `ColourPopover` places the panel
centred under it, flipping above when there is no room below.

⚠️ **The open state is an anchor, not a boolean.** They carry the same information and only one of
them can also say *where*. There is never more than one `+` on screen to be ambiguous about, because
row two collapses whenever a contextual toolbar is up — which is also what lets `ColourPopover`
derive *which* colour it edits (text box → selection → pen) from the same `when` `ContextBars` uses,
instead of three callbacks threaded down that could disagree with it.

⚠️ **The anchor arrives in root coordinates and has to be rebased** against `InkScreen`'s own
`positionInRoot`, because the Scaffold insets that Box by the status bar. Skip it and the panel opens
one status bar too low, which reads as a rounding error rather than as a missing subtraction.

### 7.3 It closes on a press anywhere else, including the paper

A full-screen transparent layer under the panel, and **the tap that dismisses it does not reach the
page**. Letting it through would draw a stroke with the same press that put the panel away, which is
the one thing somebody adjusting a colour is not trying to do. Verified on the emulator: tapping bare
paper closed the panel and left the revision readout at `r2`.

⚠️ **Not a `Popup`.** A popup is a second window, and this screen's wet ink is a front-buffered
`SurfaceView` composited over the app's window by SurfaceFlinger (SPEC §3.2) — window ordering
against that surface is the thing this codebase keeps getting caught by. An in-window layer is
ordered by composition, which is a rule with no platform in it.

### 7.4 The page bar was clipped against its own pill

Reported as *"this bottom part looks very cut off"*, and it was: the page bar's eleven chips plus the
revision readout ran about 120 dp past the space `BottomWidgets` leaves between the history and zoom
widgets, so `GlassBar` scrolled and the last chip sat flush against the pill's rounded end with no
trailing padding. A scrollable bar that happens to be scrolled reads as broken, not as scrollable.

Two things left the bar, and neither became unreachable:

- **The page-move arrows** (`◀ ▶`) → the page overview sheet, which is the screen that exists for
  reordering, shows a thumbnail of what is being moved, and is one tap away on the counter beside
  them. M2's *"reorder"* acceptance criterion is still met, on a better surface.
- **`r{localRev}`** → the diagnostics panel, with the rest of the instrumentation. It is the autosave
  heartbeat (SPEC §7.6) and worth keeping; it is not a page control.

The gutter between the three bottom zones also went from 12 dp to 28 dp. Two pills a hairline apart
read as one bar that has been sliced; the design's three zones have to look like three.

The bar is now exactly the brief's own list — `‹ N of M ›`, `ADD`, `DUPLICATE`, `DELETE`, the
template chip, `SHARE`.

### 7.5 The chrome was washing out against the page

Reported as *"the arrows are very small and hard to see"*, and there were two independent causes.

**The panels were too translucent for this app.** The mock is a page with a dark canvas behind every
panel; the editor's chrome spends most of its life over **white paper**, because at fit-width the
page fills the viewport. A 65 %-opaque panel over white composites to roughly 40 % luminance, on
which [Ink.TextMuted] is very nearly invisible — so a disabled chip's contrast depended on what the
user happened to have scrolled underneath it. `Ink.Panel` is 90 % now. The effect survives (the page
still shows through, and the hairline and shadow do the rest) and every chip's contrast became a
property of the chip rather than of the document.

**And a symbol was being set as a measurement.** `TextChip` had one `verbatim: Boolean` meaning
*"monospace it and don't uppercase it"*, which is right for `4 dp` and `45°` and wrong for `‹` — a
glyph in the readout face at 13 sp is a hairline centred in an empty cell. That boolean is now
[ChipKind]: `WORD` (uppercase, tracked), `VALUE` (monospaced measurement) and `GLYPH` (larger, normal
face, a shade brighter). One value rather than two booleans, because a symbol and a measurement want
opposite things from a font and *"neither"* and *"both"* are not answers.

**Separately, the `94%` readout had a gap beside it.** Its width is fixed so the bar does not re-flow
between `9%` and `100%`, and left-aligned inside that box the slack read as a random space before the
divider. `Readout` takes a `textAlign` now; the three fixed-width readouts centre, and the colour
panel's channel values right-align so 0, 26 and 255 line up down R/G/B/A.

⚠️ The emulator threw one ANR during this session while the library was on screen. Its main-thread
stack is `ViewRootImpl.performDraw` → `HardwareRenderer.setStopped` blocked in a native wait, with no
app frame in it at all — a renderer stall on a software-GPU AVD, not an app deadlock. It did not
recur on restart. Recorded rather than dismissed, because *"it was the emulator"* is exactly the
thing this repo has been wrong about before; if it reappears on the tablet it is real.

---

## 8. Design review, round three

### 8.1 A filled shape now recolours

⚠️ **A real bug, not a styling one.** `recolour` was `restyle { it.copy(colorArgb = …) }`, and a
shape keeps its two colours in two different registers by design: the outline in `SetStyle`'s
`ObjectStyle`, the interior in `fillArgb` inside the `SetTextContent` record (SPEC §4.8.2).
Recolouring wrote one of them, so a filled box changed its outline and kept its old interior.

They are recoloured together, because there is no UI for a fill colour of its own — `FILL` takes the
pen's colour — so *"make this shape red"* is the only thing a user can mean. `fillOpacity` is
untouched, so a translucent fill stays translucent and only its hue moves.

⚠️ **One gesture**, which is why `recolour` no longer just calls `restyle` and then `editRecords`:
each of those applies two batches of its own, so the pair would be four, and an undo would put the
outline back while leaving the interior recoloured — a state worse than either end. `restyle` is
split into `styleBaseline` / `styleChange` so the two halves can share the batches.

### 8.2 The colour panel edits the swatch you chose

Tapping a slot now *chooses* it, and every later change in the panel — wheel, channel, hex — writes
into that slot as well as applying the colour. Mixing a colour and keeping it was previously a
long-press-to-save that nothing on screen mentioned; that gesture is gone, because a hidden gesture
and a visible one competing to write the same twelve values is one too many.

⚠️ `selectedSlot` is set **only** by an explicit tap on a slot. A colour arriving from anywhere else
— a brush switch, a peer, a hex paste — must not silently overwrite whichever swatch was chosen last.

### 8.3 Colours drag between swatches

Press and hold any swatch and drag it onto another; the quick strip in the toolbar is the first three
slots of the same palette, so *"drag a colour into the quick select"* is a drop with `to < 3`. It is
a **swap**, so twelve colours go in and twelve come out and a mis-aimed drag is undone by dragging
back.

Three things this needed, each with a reason:

- **`ColorDragState`**, because the two ends are in composables that never meet: the source is the
  panel's grid, the target a swatch inside a scrolling, clipped `GlassBar`, and their only common
  ancestor is `InkScreen`'s root `Box`. Every swatch reports itself in **root** coordinates and the
  drop is a rectangle test.
- **Targets keyed by a string, not by slot index.** The panel's grid and the quick strip both show
  slot 0 — they are the same twelve slots — so an index alone would have two rectangles claiming it.
- **`detectDragGesturesAfterLongPress`.** A swatch's whole job is to be tapped, and a plain drag
  detector claims the pointer on the first pixel of movement, so a tap with any tremor in it would
  pick the colour up instead of choosing it.

The drag preview is drawn by the **root**, last: parented to either end it would be clipped at that
end's edge halfway across the gesture.

### 8.4 The selection bar lost its last two captions

`1 SELECTED` and `SNAPPED · ARROW · DRAG ITS POINTS` are gone. The bar's presence says something is
selected and the handles say which; the shape appearing under its own handles says a snap happened.
`DRAW AGAIN` stays — that a snap *took the pen* is the one thing not visible on the page.

### 8.5 A snap hands the pen straight to the endpoint

> *"When you hold and it turns into a line, the selection end snaps to your stylus's end so you can
> more smoothly and easily refine the position."*

Draw a line, hold until it snaps, and **without lifting** carry on moving: the far end follows the
pen. The handle is already under the tip, because a snapped line ends where the gesture did.

`SelectionController.beginPointDrag` is the new seam, and it is deliberately narrow:

- ⚠️ **`Mode.POINT` or nothing.** It does not fall through to `MOVE` or `LASSO` the way `begin` does.
  MOVE would mean a pen that twitched after a snap dragged the whole shape off its own ink; LASSO
  would mean it drew a marquee that, on the lift, *replaced* the selection of the shape just made.
  Both are silent, and both undo work the user thought was finished.
- ⚠️ **A connector has no point handles** — its geometry is derived from what it is attached to — so
  the hand-over answers false for one and releases the pointer. A line drawn between two boxes
  *becomes* a connector, so this is the common case, not a corner. Found on the emulator: the first
  attempt drew a line near an existing rectangle, it bound, and nothing dragged.
- ⚠️ **Armed at the snap, begun on the first movement past `HAND_OVER_SLOP_DP`.** Starting the drag
  at the snap would commit one on the lift even when the pen never moved — and a point drag commits
  an explicit vertex list, so *every* snapped line would gain a `SetTextContent` it did not need, on
  the journal and on the wire, for a gesture the user had finished. Verified both ways on the
  emulator: snap-then-lift leaves the line exactly where it landed; snap-then-keep-moving drags the
  end.

---

## 9. Design review, round five — the selection bar only offers what the shape can do

> *"remove the DRAW AGAIN button when selecting something also the round/square thing should be
> combined into one toggle and it should work on more then just the rectangle and if a shape cant do
> it then it should not show up"*

Four changes, three of them in `ContextBars.SelectionBar` and one in `:core:model`.

### 9.1 `DRAW AGAIN` is gone

It was M8b's return leg for the pen a snap steals. The two routes back are the `DRAW` chip in the
dock and a tap on bare paper — `InkScreen` wires `onSelectTappedAway = { ui.selectBrush(ui.kind) }`,
which restores whichever brush was last in hand, so the chip was a third way to do what two things
already did. Noted in the code where it was, so that if putting the pen back ever feels like a hunt
again the history is there.

### 9.2 `ROUND` / `SQUARE` became one `ROUNDED` toggle

Two chips for one boolean, and neither of them showed which one you were on. The toggle is half the
width and paints itself active when the selection is rounded — `TextChip(selected = rounded)` — so
it states the value as well as setting it. `ui.selectedRounded > 0` is the state; pressing it writes
`0f` or `CORNER_RADIUS_PT`.

### 9.3 ⚠️ `roundsCorners` said *"only a rectangle"*, and the reasoning was wrong

The KDoc ran:

> *"a triangle's corners meet at angles a single radius cannot round consistently — an equilateral
> apex and a 15° sliver want visibly different radii to look equally rounded, and picking one makes
> the sliver disappear."*

The first half is true. The conclusion does not follow, and the KDoc is the expensive kind: it
explains why the wrong thing is right. **A fillet is not "a radius applied at a corner"** — it is
the largest circle *up to* that radius which fits between the two edges, and clamping it per corner
is what every vector editor does. The 90° corner takes the full radius, the 15° sliver takes what it
has room for, and nothing disappears, because a corner with no room simply does not round.

`ShapeGeometry.roundedPolygon` is the general version:

- interior half-angle from the two edge directions; tangent length `t = r / tan(θ/2)`
- `t` clamped to **half the shorter adjacent edge**, so two fillets on one edge cannot overlap, and
  the radius solved back from what actually fitted — this is the whole of the per-corner argument
- one cubic per corner, control handles at `(4/3)·tan(φ/4)·r` along each edge, `φ = π − θ`
- ⚠️ at θ = 90° that factor is exactly `KAPPA` and `t = r`, so it reduces to `rectangle`'s
  hand-written form. The rectangle keeps its own exact version and the two agree by construction.

`ELLIPSE` still answers false — it has no corners at all, which is a different statement from having
awkward ones — and `LINE`/`ARROW` are not closed.

**The same wrongness had a second home.** `outline` dropped the corner radius for an explicit vertex
list (`ShapeRecord.points`), citing the same reasoning from the other side: *"a radius is defined
against a rectangle's right angles, and an arbitrary quadrilateral has none"*. It rounds now. A
quadrilateral a user has dragged into shape is exactly the thing they then want rounded.

### 9.4 A control the selection cannot use is absent, not greyed

`ShapeSelectionCounts` grew `fillable` / `roundable` / `rounded`. `:app` holds ids and counts and
never the render state, so *"is there anything here I could round?"* has to be answered in
`:android:ink`; `SelectionController.shapeCounts` counts against `ShapeGeometry.isFillable` and
`roundsCorners` — the same two predicates `setFill` and `setCornerRadius` already refuse on. The bar
cannot drift from the model, because both read the same function.

Progressive disclosure is the design's word for it, and `UndoStack.commit`'s honest-button rule is
the same idea one layer down: a chip that does nothing when pressed is worse than no chip.

### 9.5 Verified on the emulator, per feature

`adb shell input stylus motionevent` draw-and-hold, then `uiautomator dump` of the bar:

| Gesture | Bar shows | |
|---|---|---|
| triangle, snapped | `FILL` `NO FILL` `ROUNDED` | ✅ rounding is no longer rectangle-only |
| ellipse, snapped | `FILL` `NO FILL`, **no** `ROUNDED` | ✅ no corners ⇒ no control |
| line, snapped | neither, **no** `DRAW AGAIN` | ✅ |
| line that bound as a connector | `STRAIGHT` `ELBOW` `CURVED` only | ✅ |

`ROUNDED` pressed on the triangle rounds all three corners on screen; pressed again it goes back
sharp. Closing the note and reopening it brings the rounded triangle back — journal → fold → render,
end to end.

⚠️ **`input tap` cannot select, only `input stylus tap` can.** CLAUDE.md records that *drawing*
needs `input stylus motionevent`, because `PalmGuard` accepts only `TOOL_TYPE_STYLUS`. The same is
true of a **tap on the surface**, and the failure is much more confusing: three taps in a row on the
interior of a plainly-visible triangle selected nothing, which reads exactly like the hit-test
regression `DryShape.tappedAt` exists to prevent. It was the instrument. Same lesson as the
`uiautomator dump` 0-byte file and the `sleep 1.5` on a timer that was never armed.

## 10. Design review, round six — the size panel has no presets, no count and no fixed maximum

> *"i dont like the size thing it should not be preset sizes and should not be a set amount you
> should be ablet to edit every one and you should be able to set the max size"*

The first size panel offered `SAVE` (the current width, up to ten) and a long press to forget, over a
slider hard-coded to 0.5–32 dp. The six stock sizes could therefore be removed but never *changed*,
and nothing could go past 32.

| Now | How |
|---|---|
| **Every saved size is editable** | Tap one: it lights, the pen takes it, and the panel's slider and number field write into it until it is tapped again, another is tapped, or the panel closes. The colour panel's *choose a slot, then edit it* model. |
| **No count** | `ADD` has no limit (`MAX_WIDTH_SLOTS` is gone); the row is a `FlowRow` and wraps. `DELETE` removes the one being edited — the last one too. |
| **The maximum is a setting** | `MAX SIZE (DP)`, persisted as `ink.maxWidth`, bounded 1–200 dp (`WidthLadder`). It is the top of the dock's slider and the panel's. Lowering it brings every brush width, saved size and the default down to it. |

Four decisions worth keeping:

- ⚠️ **The ladder is not re-sorted during an edit.** Dragging a saved 3 dp past a saved 4 dp passes
  through exactly 4.0; merging there deletes the size the user was not touching. `finishWidthEdit`
  sorts and merges when the edit ends, and `SizePopover`'s `DisposableEffect` calls it on every route
  that closes the panel.
- ⚠️ **Typed numbers apply on Done or focus loss, never per keystroke** — unlike the hex field. Typing
  `12` passes through `1`, and a maximum of 1 would flatten every width on the way.
- ⚠️ **An empty `ink.widths` is a value, not corruption.** Reading `""` as unparseable would bring all
  six stock sizes back on the next launch, which is the complaint reintroduced by a fallback. Only a
  missing row, or text with no number in it, is the stock ladder.
- **The dock readout stays seven glyphs.** `WidthLadder.label` drops the decimal from three digits
  up, so `150 dp` fits where `200.0 dp` would have ellipsised (§ `DockRows.WidthReadout`).

`EditorPrefs`' old coupling — *"every brush default must sit on a slot"* — is gone with the tap cycle
it protected; the readout opens the panel and nothing cycles.

### 10.1 Checked

`SizeLadderStateTest` (10), `WidthLadderTest` (4), `EditorPrefsCodecTest` (14), `WidthMagnetTest` (9)
— automated and run. On the `Test_Tablet` emulator, through the UI with `uiautomator dump`: tap `3.0`
→ `EDITING`; type `5.5` + Done → the row reads `1 2 5.5 4 8 16` (unsorted, as designed); tap it again
→ `1 2 4 5.5 8 16`; `MAX` `12` → `16` became `12`; width `7` + `ADD` → inserted and editing; `DELETE`
→ gone. `HOME`, `am force-stop`, reopen: the same row, pen at 7.0, and `setting` read with `run-as`
holds `ink.maxWidth = 12.0`, `ink.widths = 1.0,2.0,4.0,5.5,8.0,12.0` and the highlighter clamped from
16 to `12.0`. **Not** run on the Galaxy Tab.
