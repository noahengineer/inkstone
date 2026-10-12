# The Element editor — knockout areas, a pivot that snaps, and updating placed copies

> *"I'm thinking for elements I want there to be some sort of editor — you create an element, then
> double-click on it in the selection area to open an editor where you can change it and add things
> like non-destructive erasing to shapes or areas like a circle or rectangle. I would also want to be
> able to add a pivot point for snap so it snaps to a grid when you move it."*

Three decisions were asked before building, because each changes what gets built:

| Question | Answer |
|---|---|
| What is the editor? | A full canvas — **without paper**: *"a black grid background to show it's just that element … so you know you are editing an element"* |
| What happens to copies already placed? | **Ask on save**: *"if there are other copies it asks if you want to update them all"* |
| What does a knockout area look like in a note? | **An invisible cutter** — dashed only while editing |

SPEC §4.9.7 is the normative account. This file is the reasoning and the evidence.

## 1. The editor is the note editor

Everything a person can do in a note they can do to an Element — pen, eraser, shapes, text, images,
selection, knockout, nested Elements — because the editor **is** `NoteEditor`, opened by a second
`NoteActivity` with `EXTRA_ELEMENT_ID` and pointed at a scratch note. Three things make that safe
without a single other component knowing an Element editor exists:

- **`ScopedFileSystem`** puts the scratch note under `elements/editing/<session>/notes/…`. The library
  index, the reconciler, the trash sweeper and the outbox all read the real `notes/`.
- **No library, no publisher, no recogniser** are handed to that `NoteEditor` — the three things that
  make a note a note (indexed, published as a PDF, read for search).
- **No registration with `NoteSessionHost`**: the view model has no note id, so the gate never sees it.

The seed is applied with `replay`, so the Element itself is not an undo entry — the first undo takes
back the first thing the *user* did. A session's folder survives a process death (its id is in the
saved state), and finished sessions are moved to `elements/trash/editing/` when the next edit starts —
never while their editor might still autosave into them, which is how `NoteSessionHost` once lost a
note (CLAUDE.md, *trashRemotelyDeleted*).

The canvas is a 3000 pt page with the Element in the middle: the pen refuses a stroke that begins off
the paper (SPEC §4.5), and on a page larger than the screen there is nowhere for that to happen. The
first device run opened it at 19 %, because the load's own fit-to-width refit runs after anything a
caller does; `InkSurfaceView.preferredFrame` is what that refit honours now.

**Backdrop.** A grid replaces the paper (`ElementBackdrop`). ~~Dark, as asked~~ — **white** since §12:
dark hid near-black ink (the default pen is `#1A1A1A`), and the user called it *"a bad call on my
end"*. The editor's `White` / `Black` chip flips it; it changes nothing saved.

## 2. Knockout areas

`ObjectFormat.cutter` (`cut=1`): a closed shape that hides what is beneath its outline and draws
nothing (SPEC §4.9.6). Its silhouette is its outline filled — exact, since an invisible object has no
picture for `SilhouetteMask` to measure. `PagePainter.showCutters` draws it dashed on the editor's
canvas only; `InkStrokeOutliner` publishes nothing for it and still subtracts it.

⚠️ **Found on the emulator: an area drawn on top hid the Element's own eyes.** That is the rule
working — an area hides what is beneath it — and not what an area is for, which is the note *behind*
a placed copy. New areas now go to the **back** of the page (lowest key); Front and Back still move one
for the rarer area that should hide one of the Element's own pieces.

## 3. Copies, and updating them

Each drop tags its pieces with an `ElementInstance` (`el=` in the format register): Element id, one
instance id per drop, the piece's transform *inside* the Element, and the pivot. Two decisions carry
the design:

- **The piece's own matrix, not the drop position.** Objects move after they are placed, and a stored
  drop position goes stale on the first tidy. `placement = transform × member⁻¹` recovers where the
  copy is *now* from any one piece.
- **Grouped by instance id *and* placement.** Duplicating a placed copy copies its tag; grouped by id
  alone, two copies would be one, and an update would put a single replacement down and lose the
  other.

`elements/uses/<id>.uses` lists the notes this device placed an Element in, so Done asks *"how many
copies are there"* of a handful of notes rather than folding the whole library. On **Update all**,
`NoteSessionHost.updateElementCopies` goes to the live editor if the note is open (its session holds
pending ops a second session would lose) or opens its own, then materializes (the new pieces' picture
bytes ride `extraCarried`, in the same write as the ops that name them) and calls `noteSaved` — the
only route to a fresh thumbnail and an upload. Old pieces are **tombstoned**, not rewritten: an edit
can add or remove pieces, and the log keeps the old ones. Each page's replacement is one undo entry.

⚠️ **An edit that adds ink above or left of everything moves the origin** (it is the top-left of what
was saved), and replacing at the old placement would slide every unchanged piece by that much.
`ElementRevision` carries the shift beside the Element so no caller can apply one without the other.

## 4. The pivot

`Element.pivot` (Element space; the centre for one saved without a pivot). The editor's **Pivot** tool
places it; a selected copy shows it in amber. With grid snap on, `SelectionController` latches the
copy's pivot at pen-down and `SnapGrid.moveBy(anchor, …)` lands **that** on the lattice instead of the
box's top-left — and lets a copy of handwriting snap, which the lattice otherwise leaves alone. The
Grid chip appears on a selection with a pivot even when it holds no shapes.

⚠️ Alignment guides still win on an axis they speak for (`SnapGrid.moveBy`'s rule). On a crowded page
that is most drags — the first two device attempts landed off the lattice for exactly this reason, and
the third, in open space, landed the pivot at y = 108.001 = 36 + 3 × 24.

## 5. Known gaps

- **Copies this device did not place are not updated** — stamped on another tablet and synced here,
  or carried into a duplicated note. They are still found by a later update from the device that
  placed them.
- ~~**Dark backdrop + dark ink**~~ — white by default since §12.
- **The panel's double-tap is undiscoverable except by its hint line**; there is no Edit button.
- The emulator's ink surface showed stale buffers (app icons, library tiles) over the editor's dark
  canvas — CLAUDE.md's x86_64 note. Every visual claim below was made on the light backdrop or from
  files, never from that.

## 6. How each claim was checked

| Claim | Automated | Run on the emulator |
|---|---|---|
| `cut` and `el` keys round-trip; unknown values kept | ✅ `ElementInstanceTest` (5), `ObjectFormatTest` | ✅ `if1;cut=1` in the saved Element |
| Placement recovered after a drag; duplicates are two copies; replacement keeps stacking; tags stripped on save; revision keeps the id and trashes the old file | ✅ `ElementCopiesTest` (8) | ✅ `trash/<id>.v1.element`, `.v2` |
| Cutter hidden in a note, cuts the rule, dashed only on the editor's canvas, publishes nothing | ✅ `KnockoutRenderTest` (3 new, 9 total, pixels read back) | ✅ hole in the lines, nothing drawn |
| Pivot snap by anchor | ✅ `SnapGridTest` (2 new) | ✅ pivot y = 108.001 on a ruled line |
| Double-tap opens the editor; framed; area/pivot tools; Done | ❌ | ✅ |
| *"Update copies?"* with the right count; Update all replaces in place | ❌ | ✅ *1 copy in 1 note*; mouth and hole appeared on the copy |
| Shelf revise and use registry | ✅ `ElementShelfTest` (2 new) | ✅ `elements/uses/<id>.uses` |
| New areas go to the back | ❌ | ✅ ellipse area key `F`, below every piece |
| Selection bar no longer covers the panel | ❌ | ✅ (it stole the first double-tap — §7) |

## 7. Found on the device, fixed

- **The selection bar sat over the Elements panel**, and the double-tap meant to open the editor
  pressed *Save as Element* instead. The panel is its own layer now (`ElementPanelLayer`), composed
  after the contextual bars.
- **Opened at 19 %** — §1.
- **The bar overflowed** and cut Done in half; the label moved to the top-left corner.
- **Areas hid the Element's own pieces** — §2.

## 8. Follow-up — the guides became objects

> *"That non-destructive erase is hard to position right with no undo or edit, there is no grid snap
> for that either, and you should be able to edit the bounds of an element as well and use the grid
> for that. Also it seems if you undo something while editing you could potentially undo the stuff
> out of the edit."*

Four complaints, and they had three causes. Each was measured before anything changed.

**An area could be seen and not got hold of.** Areas go to the back of the Element (§2), and
`objectAt` is topmost-wins — so a tap inside an area under the face circle selected the *circle*,
whose inside counts as a hit. Undo had worked all along (four presses took back a stroke and an area
and stopped at the Element as it opened); what was missing was any way to *select* the area afterwards.
On the editor's canvas a tap now prefers a guide's dashed line, then ink, then the inside of an area,
then the inside of anything else (`objectAtWithGuides`), and guides are drawn on top
(`PagePainter.paintGuides`). A note is unchanged.

**Nothing snapped where the user was aiming.** The area tool placed its drag as dragged. And the
backdrop drew **12 pt** cells while the lattice is **18 pt** — so even a snapped edit landed between
the lines on screen. The backdrop now draws `SnapGrid.DEFAULT_SPACING_PT`, the area tool lands its box
on the lattice (`SnapGrid.snappedBox`) and leaves the area selected with the Select tool, and an area
has a **zero-width** style: the grid lands a dragged selection by its bounds' corner, and the 1 pt
stroke put every area half a point off the lattice it had just been placed on (found on the emulator,
by reading the transform out of the journal).

**The bounds and the pivot were not things.** The bounds were whatever the content measured on Done,
and the pivot was a value in the view model — no handles, no grid, no undo. Both are now **one object
on the scratch page**: a rectangle whose format register says `ef=1` and carries the pivot (`pv`). It
is moved, resized and snapped by the gestures every object has, it is on the editor's undo stack in
order, and `ElementEditing` (`:core:storage`, pure) decides what it means: which object is the bounds,
what Done keeps (everything else), where an area goes, what the Pivot tool and *Fit* write.
`NoteEditor.editShownPage` is the one entry point that applies them — which also took `insertCutter`
and its style out of `NoteEditor`, bringing it back under its size limit rather than raising it.

⚠️ **The first version stored the pivot as a fraction of the bounds**, which reads naturally (*"a
point of the bounds rides along with them"*) and is wrong on a grid: the device walk put a pivot on a
grid point, pressed *Fit*, and found it at x = 1570.5 — the bounds had grown and the fraction had grown
with them. It is an offset in **points** from the bounds' top-left now, so a resize from the far corner
leaves it exactly where it was and a move carries it by whole cells.

**Undo cannot reach the note — and that was checked rather than argued.** The editor has no note id,
registers nothing with `NoteSessionHost`, and writes only under `elements/editing/<session>/` through
`ScopedFileSystem`. On the emulator: draw in a note, open the editor over it, draw, press undo six
times and two-finger-tap twice, Cancel — the note's `undo.json` was byte-identical before and after.
(Its `journal.ops` changed in that window: that was the note's own close-time save folding into
`note.inkstone` when the editor covered it, which grew by exactly the stroke drawn.)

### What is still true

- Alignment guides still win on an axis they speak for (`SnapGrid.moveBy`). In the editor the bounds
  are a guide target, so dragging an area near a bounds edge lines it up with the edge rather than the
  lattice — visible as a guide line while dragging.
- An Element saved before this keeps its old box when opened: its bounds start at its saved size,
  whose far edges need not be on the lattice. *Fit* puts them there.
- The selection bar offers *Fill* and colours for an area or the bounds, which do nothing visible.

## 9. Follow-up — how each claim was checked

| Claim | Automated | Run on the emulator |
|---|---|---|
| Editor undo cannot reach the note | ❌ (structural: no note id, scoped storage) | ✅ note `undo.json` byte-identical after 6 undos + 2 two-finger taps |
| A tap inside an area under a piece selects the area; ink still wins; a note unchanged | ✅ `ElementGuidesTest` (3) | ✅ the square area inside the face circle selected |
| Bounds grabbed by edge only, never erased, never published, drawn only in the editor | ✅ `ElementGuidesTest` (4, pixels read back) | ✅ selected by the edge, box grips, no point handles, no Knockout chip |
| Areas drawn on top, dashed | ✅ `KnockoutRenderTest` (through `paintGuides`) | ✅ |
| An area lands on the lattice and is selected | ✅ `SnapGridTest` (`snappedBox`, 3) | ✅ 36 × 18 at (1566, 1530) = 2 × 1 cells at (87, 85); Select tool armed |
| Moving an area snaps; undo puts it back | ❌ | ✅ x → 1584 (88 × 18); undo → (1566, 1530) |
| Resizing the bounds snaps | ❌ (the selection's existing `snappedResize`) | ✅ 65.3 × 96.8 → 72 × 108, every edge on the lattice |
| Pivot placed on an intersection, undone, redone | ✅ `ElementEditingTest` | ✅ `pv=54:36` → (1530, 1494); undo and redo each restored the register |
| Pivot stays on its grid point through a resize and *Fit* | ✅ `ElementEditingTest` (4) | ✅ `pv` unchanged by the grip resize |
| *Fit* widens to whole cells and recreates deleted bounds | ✅ `SnapGridTest` (`enclosing`, 2), `ElementEditingTest` | ✅ bounds → 1476..1602 = 82..89 × 18 |
| Done saves the bounds as the box, the pivot, and not the bounds object | ✅ `ElementEditingTest` (`pieces`) | ✅ `w 72 h 108 px 54 py 36`, six pieces, no `ef=1` in the file |
| `ef` / `pv` round-trip; an unreadable `pv` is kept | ✅ `ElementInstanceTest` (2) | — |

## 10. Follow-up 2 — one grid, the bounds on the copy, and a pivot that actually snaps

> *"The grid of the note and the elements editor seem to be different, they seem to be offset, so when
> creating an element it's not very correct; and when you change the bounding box of the element it
> does not update the bounding box of the element dragged in; and the pivot does not work — it just
> does not snap at all really."*

Every clause was a separate cause, and two of them compounded.

**The pivot did not snap, for three reasons at once.**

1. **A tap selected one stroke of a copy.** `objectAt` returns one object, so a tap on a placed face
   selected the circle *or* an eye, and dragging moved that piece — by its copy's pivot, which then
   looked like nothing in particular. A tap (or lasso) now takes the whole copy (`withWholeCopies`,
   keyed by `ElementInstance.copyKey`, the same instance-plus-placement rule `ElementCopies` uses —
   one definition now, not two).
2. **Alignment guides won the axis.** `SnapGrid.moveBy` lets a guide beat the grid on any axis it
   speaks for, and on a page of handwriting some edge is almost always within reach. For a selection
   with a pivot — an explicit request to snap — and on the editor's canvas, the grid now leads and no
   guide is drawn (`SelectionController.gridLeads`). `ElementCopySelectionTest` holds a stray box two
   points from where the copy's bottom lands; with the rule disabled the pivot went to x = 246 instead
   of 270, so the test is red without the fix.
3. **A copy without a pivot had no snap point at all**, and the grid otherwise only moves shapes, so a
   handwritten Element did not snap. A copy whose tag carries its bounds now snaps by its pivot or, with
   none, by its **bounds' top-left corner** — which is also where the editor marks the default pivot
   now. (It used to mark the centre, which nothing snapped by.)

**The bounds never reached the copy.** The tag (`el=`) gains the Element's size — `…:px:py:w:h`, with
`-` for "no pivot" so the layout stays positional — and a selected copy's frame is its pieces **and**
its bounds (`pageBoundsOf`). Old tags still decode and simply have no bounds. ⚠️ Copies placed before
this build keep the old tag until the next *Update all* from the editor.

**The grids were different, and on a grown canvas also offset.**

- **The editor uses the opening note's spacing** (`NoteActivity.EXTRA_GRID_PT`): 24 pt from ruled or
  Cornell paper, 18 pt from grid, dot grid and blank. Ruled paper has rules and no columns, so the
  editor draws 24 pt squares: an Element drawn on them lands its pivot on a rule. Its bounds start on
  the lattice too.
- ⚠️ **The paper's lines start at the paper's corner; the lattice started at zero.** `PagePainter`
  draws a template from `pageCanvas.left/top`, which goes negative when an infinite canvas grows up or
  left (SPEC §4.5), and `SnapGrid.forBackground` ignored it — so on any page that had grown, every snap
  missed the lines by however far it had grown. It takes the paper's corner now, and the surface
  re-derives the lattice when the corner moves.
- **A drop snaps** — the pivot (or corner) lands on the lattice as dragging it would — and **a capture
  is saved with its bounds widened onto the note's lattice** (`SnapGrid.enclosing`), so a new Element's
  corner sits on a line exactly as its ink did.

## 11. Follow-up 2 — how each claim was checked

| Claim | Automated | Run on the emulator (a ruled note, grid on) |
|---|---|---|
| A tap selects the whole copy | ✅ `ElementCopySelectionTest` | ✅ the face, its mouth and its areas selected together |
| A copy's frame is its Element's bounds | ✅ `ElementCopySelectionTest` | ✅ for a fresh drop (old tags have none) |
| With a pivot the grid beats a guide | ✅ `ElementCopySelectionTest`, red with the rule off | ✅ pivot at y = 108 = 36 + 3 × 24 after a drag |
| A drop lands its pivot on the lattice and tags the bounds | ❌ | ✅ pivot y = 228 = 36 + 8 × 24; tag `…:54:36:72:108` |
| Default pivot is the bounds' corner; old tags still decode | ✅ `ElementInstanceTest` (4), `ElementEditingTest` | — |
| The paper's lattice starts at the paper's corner | ✅ `SnapGridTest` (2) | ❌ the emulator's notes have not grown up or left |
| The editor opens at the note's spacing, bounds on it | ❌ | ✅ 24 pt cells; bounds at (1464, 1440) = (61, 60) × 24 |
| A capture's bounds are widened onto the note's lattice | ❌ (`SnapGrid.enclosing` is) | ❌ not walked |

## 12. Follow-up 3 — a white canvas, guides that hide, and a copy that is one thing at last

> *"For the Elements editor having a black background was a bad call on my end, I think it would be
> better white or maybe make it so you can switch between black and white. Also I would like it so
> that you can hide those gizmos like the pivot point, border and non-destructible areas, so I would
> like it that when you try to select an element it does not select the individual parts and instead
> the whole element."*

Asked before building: *where* does a selection take parts? **In a note, on a placed copy.**

**White, with the switch kept.** `ElementBackdrop.LIGHT` is the default and is now true white
(`#FFFFFF`, grid `#EDEDF0` / `#D4D4D8`); the chip that said `Dark` / `Light` says `White` / `Black`.
Neither is remembered between sessions — a viewing choice, like the guides.

**Guides.** A *Guides* chip hides the areas, the bounds and the pivot (`PagePainter.guidesVisible`).
Hidden means **not there to touch** as well as not drawn (`StrokeStore.guidesHidden`): a tap takes the
ink, as on a note, and a lasso round the whole canvas takes the Element's pieces and nothing else. A
guide that was selected is let go. Arming *Area* or *Pivot*, or pressing *Fit*, shows them again —
each writes a guide, and one placed blind is one placed wrong. A note never sets the flag: an area
carried into a note is a piece of its copy, and a tap inside it selects the copy.

**⛔ The copy was never reliably one thing.** §10 made a tap take every piece with the same
`copyKey` — the instance id and the recovered placement **rounded to a hundredth of a point**. Two
facts made that a coin toss:

- the tag writes the piece's member to **three decimals** (`RecordText.number`), while the piece's
  transform on the page is exact; and
- a stroke is **never re-based** (SPEC §5.4) — its geometry stays where it was drawn, ~1,500 pt into
  the editor's scratch page — so its member carries a translation that large, and `transform × member⁻¹`
  multiplies any rounding of the member's scale by it.

So every piece recovers a slightly different placement. A float32 simulation of the arithmetic: **one
drop in seven** split into two or more groups at random once the Element's strokes had been nudged at
all in the editor (a fractional move is rounded too), and **every** drop of an Element with a piece
resized there (a scale of `0.8333333` stored as `0.833`, times ~1,460 pt, is half a point). Strokes
never touched since capture are exact — the origin sits on the lattice — which is why §11's walk,
on a freshly drawn face, did not see it. A tap took only the group under the finger. The same key counted copies
for *"Update copies?"*, so one copy could be reported as several and **Update all would stamp a
replacement for each fragment** — tombstoned and undoable, but wrong.

Pieces are now compared, not keyed. `ElementInstance.locate` returns a `CopyPlacement`: where the
piece says its copy is, and how far off that can be (`slackPt`) — derived from what was rounded: half
a thousandth on every member entry, through the lever of member⁻¹'s translation, scaled by the copy,
plus float32 slack. Two pieces are one copy when their placements agree within the sum of their
slacks (`sameCopyAs`); `CopyPlacement.group` clusters a page's pieces by that. The tap
(`withWholeCopies`) and *Update all* (`ElementCopies.on`) now share it — §10 promised one definition,
and that is still true.

- **A duplicate is still a second copy.** It lands 18 pt away on each axis; slack is capped at
  **8 pt** per piece, so two copies a Duplicate apart are never one, however far the copy is scaled.
- **Which piece to believe.** The worst-case bound treats every piece as possibly rounded, which is
  right for grouping (a scale of `0.9997` is also written `1`). To choose where a copy *is* — its
  replacement's placement, the pivot a drag snaps by, the bounds in its frame — the piece whose member
  is a pure move or quarter turn wins (`errorPt`): that is written without loss. The first version
  picked by `slackPt` and put a replacement half a point off; `ElementCopiesTest` caught it.

⚠️ **Not fixed: a copy scaled up past about 6× with resized or turned pieces can still split.** At that
scale three decimals cannot place it to within the 18 pt a duplicate moves (measured: 66 of 3,000
random drops between 6× and 10×, none below). Writing more decimals into new tags is the fix; old tags
would still need this tolerance.

⚠️ **Copies placed before the Element editor build carry no tag at all**, so a tap takes one piece of
them. Drop them again from the panel.

## 13. Follow-up 3 — how each claim was checked

There is no Android SDK in the environment this was written in (`dl.google.com` is blocked), so no
Gradle build ran. `:core:model` and `:core:storage` were compiled and run with a standalone Kotlin
2.3.20 compiler on the JVM; ktlint 1.8.0 and detekt 1.23.8 ran standalone with the repo's config and
baseline. **Nothing was run on the emulator or the tablet.**

| Claim | Automated | Run |
|---|---|---|
| Pieces whose tags were rounded apart are one copy; a duplicate 18 pt away and a second drop are not | ✅ `ElementInstanceTest` (3 new, 1 rewritten) | ✅ JVM, 399/399 `:core:model` |
| *Update copies?* counts one copy and *Update all* stamps one replacement, at the exact piece's placement | ✅ `ElementCopiesTest` (1 new) — **red on the old code** (*"List has more than one element"*) | ✅ JVM, 214/214 `:core:storage` |
| No split and no duplicate merged up to 6× copy scale | ✅ simulation, 12,000 random drops | ✅ JVM |
| A tap on one piece of such a copy selects both | ✅ `ElementCopySelectionTest` (1 new) | ❌ instrumented, not run |
| Hidden guides: not drawn, not tapped, not lassoed | ✅ `ElementGuidesTest` (3 new) | ❌ instrumented, not run |
| White default, *Guides* chip, tools and *Fit* show guides, a selected guide let go | ❌ | ❌ not walked |
| ktlint, detekt (incl. `LargeClass` headroom on `InkSurfaceView`) | ✅ | ✅ standalone |

