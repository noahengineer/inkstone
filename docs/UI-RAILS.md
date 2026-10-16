# The editor's chrome, rearranged — the corner "L", and one column for every panel

Two rounds, and the first was rejected. Both are here, because the second is mostly the first's
mistakes corrected.

## 0. What was asked

> *"I really dont like the default UI, it does not look good at all it gets extremely cluttered … I
> would recommend having some things in grid shapes or more vertical or something."* — and test it
> by opening everything at once: the selection bar, the Elements library, the calculator.

Then, about round one:

> *"it seems to be taking up a lot of unnecessary space (like at least move the tools bar to the top
> left corner) and there seems to be weird borders … the page switching arrows dont make sense … the
> calculator should be resizable by like dragin in the corner it should also not be locked to the
> center, also the elements library is in a horrible, there is just so much overlapping still and
> that is what i wanted to avoid"*

and, asked how the tools should sit: *"look up references and see how good UI for notes apps is
done"*; asked where panels should open: *"Right-side column"*.

## 1. Round one, and why it was wrong

Tools as a **rail down the left, centred top to bottom**, the pen's settings as a **panel card** beside
it, pages as a **rail down the right** with the ‹ › arrows turned to point up and down, two-line
contextual cards, the Elements library as a grid opening beside the tools. On the emulator it was
cleaner than M10 and it was still wrong, for reasons the screenshots showed and nobody had looked for:

- **Centred rails spent the middle of both margins.** The space above and below them was empty and
  unusable; the rails sat exactly where people write.
- **Tracks inside cards inside bars.** The panel card had a bordered brush track, a full-width
  bordered swatch track with dots in the middle, a bordered readout — three nested outlines.
- **Rotated chevrons are not arrows.** A ‹ turned 90° in a column reads as a caret.
- **Every panel still placed itself**, each beside its own control: the library under the pen card,
  the colour panel beside it, the calculator between the rails, the diagnostics beside the page rail.
  Each placement was reasonable alone; together they overlapped, because none knew where the others
  were. That was the complaint in the first place.
- **The calculator was as tall as the space it could move in**, so it could only move sideways.

## 2. Round two — what the references do

All of the apps the user pointed at — GoodNotes, Notability, Samsung Notes, Apple's PencilKit palette,
Noteshelf — agree on a few things (sources: GoodNotes' pen-tool docs and feedback board, Samsung
Notes guides, Apple's `PKToolPicker` docs, reviews of Noteshelf 3):

- **One compact toolbar**, and the active tool's *few* quick options inline beside it — GoodNotes'
  colours and thicknesses at the right of its toolbar, Samsung Notes' three quick colours.
- **The pen *type* is one tap behind the pen** (Samsung Notes' arrow beside the pen, GoodNotes' pen
  popover); the toolbar is spent on what changes every minute — colour and thickness.
- **The full pickers are a tap away**, not on screen.
- **Chrome hugs the edges and corners**; the page is the point. Users of all of them ask for *less*.

## 3. What the stock layout is now

```
┌──────┬──────────────────────────────────────────┐ ┌───────┐┌──────┐
│ DRAW │ FOUNTAIN ▾ │━━━○━━ 16.0 dp│● ● ● +│SNAP ARROW GRID │ │RECORD ││ CALC │
│ ERASE└──────────────────────────────────────────┘ └───────┘└──────┘
│ LASER│                                     ┌────────┐┌──────────┐
│──────│                                     │ COLOUR ││ ELEMENTS │
│SELECT│            (the page)               │  …     ││ ▢  ▢     │
│──────│                                     │        │└──────────┘
│ TEXT │                                     │        │
│ IMAGE│                                     └────────┘
│ ELEM.│
│──────│
│ RULER│
└──────┘
[UNDO REDO]   [‹ 1 of 3 › ADD DUPLICATE DELETE PAPER SHARE]   [94% FIT WIDTH FIT PAGE 100% ⋯]
```

| Bar | Shape | Where |
|---|---|---|
| Tools | **Column** | **top-left corner** |
| Pen settings (the active tool's row) | row | along the top, beside the tools — measured into the gap before the record chip, so it scrolls rather than running under it |
| Record, Calculator | one chip each | top-right corner, the calculator in the corner and record beside it |
| Undo · Redo / Pages / Zoom | rows | along the bottom, as M10 had them, with ordinary ‹ › arrows |
| **Every panel** | — | **one column down the right edge** (§4) |

- **The four brush names are one chip** (`BrushMenu`): `FOUNTAIN ▾` names the brush in hand and opens
  the other three. That alone took the pen's row from ~960 dp to ~670 dp. In a column, a grid, a panel
  or the layout editor each brush is still its own chip, so each can still be moved or hidden.
- **No outlines inside bars.** `Segmented`, `SegmentedGrid` and `SwatchStrip` lost their hairlines;
  the recessed fill is what makes a track a track.
- **The contextual cards** (selection, text, image) are two lines, *what to do with it* over *what it
  looks like* — kept from round one, which the user did not object to — and now **wrap** (`CardFlow`)
  when the gap they have is narrower than they are (§4).
- A bar's stock slot is decided by its **measured shape**: a tall tools bar goes in the top-left corner,
  a wide one is M10's top-centre dock. **Tools → Row** in the layout editor gives M10's top edge back
  exactly, and then the calculator returns to the top-left corner it had, so the dock does not run into
  it in portrait.

## 4. One column for every panel (`PanelDock`)

The Elements library, the colour panel, the size panel, the ⋯ diagnostics and the calculator all take
a place in **one column down the right edge**, from under the top-right chips to above the zoom bar.
`DockFlow.place` is the whole rule, and it is pure:

- down the column from the top-right; a panel that does not fit goes **under the first column with room**
  for it, or starts a **new column to the left**;
- a panel never joins a *narrower* earlier column (unless it is the last one), because it would then
  reach into the column beside it — the overlap the column exists to prevent.

Each panel reports its size and reads its place in the layout phase (`Modifier.docked`), so opening
one moves the others without recomposing them. The library went from 280 dp to 240 dp and the
diagnostics from 380 dp to 320 dp, which is what lets all four big panels sit side by side on a
landscape tablet without reaching the tool column.

**The colour and size panels stay open while you write** — there is no scrim now. The `+` (or the
width readout) that opened one closes it, and so does its `✕`. Which colour the colour panel edits is
still derived, not threaded: a text box being typed into, else a selection, else the pen.

### The calculator

- **Docked** until it is first dragged by its title strip; then it floats wherever it is dropped,
  anywhere on screen. **Dock** in its title strip puts it back (`calc.window.docked`, which outranks a
  stale position, because `putAll` cannot delete one).
- **Resized from its corner** (`CalculatorPlacement.resized`): the drag is projected onto the window's
  own diagonal, because its aspect is locked. Bottom-right when floating; bottom-left when docked,
  where it grows leftwards from the column's edge. Remembered as `calc.window.face`.
- **Smaller by default** — a 200 dp face instead of filling the height. Filling the height left a
  window exactly as tall as the space it could move in.
- A tap on the title strip does **not** undock it: only a drag past touch slop commits a position.

### Contextual cards keep off all of it

`ContextBarPlacement.place` keeps a card out from under the tool column, every docked panel and a
floating calculator (`PanelDock.reportLoose`), and `freeWidth` caps the card at the free gap around the
object so its lines wrap rather than slide under a panel.

⚠️ **The free gap is found, not sorted.** The first version sorted obstacles into "left half" and
"right half" by their centres. With three panels open the column's leftmost panel has its centre left
of the middle, so it counted as a *left* obstacle; the card found no room between it and the tools,
fell back to full width and slid under the panels. `gapAround` now walks the covered intervals and
takes the free stretch that holds the object's centre (or the widest one).

⭐ **When there is no gap beside the object, the card goes to the nearest free space anywhere**
(`ContextBarPlacement.refuge`). With four panels open the column reaches from the calculator chip
nearly to the tools, and the card used to take the screen's width and slide under all four while the
space under the shortest panel stood empty. Every free rectangle is bounded by two block edges, so
trying each pair of edges and each vertical run between them finds them all; the card goes in the one
nearest its object. ⚠️ **A space only counts at its own width**: the lines wrap, so a narrower space
holds a taller card. `AnchoredBar` therefore places the card in the *layout* pass and asks it its
intrinsic height at each width it considers — measuring once and moving it a frame later would let the
choice of space change the height that chose it. Only a screen with no such space at all puts the
card over the panels.

⛔ **The first version asked the card its height at every space's own width, and froze the editor.**
An intrinsic measurement of the whole card took 11 ms on the emulator's debug build, there were up to
~150 distinct widths, and the pass re-ran whenever a panel changed size: opening the library as the
fourth panel stalled the main thread for **215 s** (`Skipped 12946 frames`). It now tries five widths,
from the card's natural width down to the minimum, and a space holds the widest of them that fits.
`ContextBarPlacementTest` asserts the count. The card is then measured once and placed a second time
at the width it actually took — a card whose lines wrapped is as wide as its longest line, and
centring the allowance instead left it pressed against the tools.

## 5. Old layouts

Every device that had opened a note had a table that said `ROW` for every bar, because a row was the
only stock there was. Read literally, nobody would have seen any of this. Tables carry `ui.version = 2`
now; a table without it reads a stock bar still in its stock slot with `ROW` as *no choice*. A bar the
user had moved keeps its `ROW`. A shape chosen in the old editor is kept. `BarLayout.arrangement` is
null for "the bar's stock shape".

## 6. Per claim

Emulator: `Test_Tablet_B` (2560 × 1600, density 2), debug build, airplane mode on.

| Claim | Written | Automated | Run on the emulator |
|---|---|---|---|
| Tools in the top-left corner, pen row beside them along the top | ✅ | ✅ `ChromeLayoutTest` (stock shapes) | ✅ landscape; ✅ portrait (row scrolls short of Record) |
| Brushes as one `FOUNTAIN ▾` chip with a menu | ✅ | — | ✅ menu opens, lists four, marks the one in hand |
| No nested outlines in bars | ✅ | — | ✅ |
| Page bar back along the bottom with ‹ › | ✅ | ✅ (stock shape) | ✅ |
| Panels never overlap each other | ✅ | ✅ `PanelDockTest` (five real sizes) | ✅ library + colour + diagnostics + calculator side by side, none on the tool column |
| Calculator docked, dragged out, docked back | ✅ | ✅ codec (`docked` outranks a stale position) | ✅ |
| Calculator resized from the corner | ✅ | ✅ `CalculatorPlacementTest` (`resized`) | ✅ shrunk to the minimum floating; kept its size when docked |
| Default calculator can move up and down | ✅ | ✅ (> 100 dp of travel) | ✅ |
| Selection card wraps between the tools and the panels | ✅ | ✅ `ContextBarPlacementTest` (`gapAround`, the centre-left-of-middle case) | ✅ three lines between the tools and the calculator |
| Selection card keeps off a floating calculator | ✅ | — | ✅ |
| With four panels open, the card goes to the nearest free space | ✅ | ✅ `ContextBarPlacementTest` (the emulator's four panels; a space counts at the card's width there; at most five height queries) | ✅ calculator + diagnostics + colour + library open, a stroke selected beside them: the card sits under the first two, clear of all four and of the bottom bars, no skipped frames |
| The card is centred at the width it took, not the width it was allowed | ✅ | — | ✅ three panels open: before, it sat hard against the tools; after, beside its object |
| Library as a two-across grid, Edit/Remove on the armed tile | ✅ | — | ✅ |
| A pre-redesign table reads as the new stock | ✅ | ✅ (red with the migration disabled) | ✅ |
| Feel with an S Pen on the Galaxy Tab | — | — | ❌ **not yet** — definition of done #4 |

⚠️ Several emulator captures carry a brown or pink speckle and a ghost of the splash robot. That is the
AVD compositing a stale front-buffered `SurfaceView` (M10 §5); nothing about how anything *looks*
should be judged from those frames, only where things are.

## 7. Not done

- **The library screen** (the note grid) is unchanged; its top bar still scrolls in portrait.
- **In portrait the bottom page bar is narrow** (between undo and zoom) and scrolls, as in M10.
- **A screen with no free space at all** — every panel open, plus a selection whose card fits nowhere
  else — still draws the card over the panels. The four-panel case that used to do this now finds the
  space under the diagnostics panel (§4).
