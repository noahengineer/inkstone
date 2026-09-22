# The paper, and the infinite canvas

Two changes, in this order: **ink stops where the paper stops**, and then **the paper can be
endless**. The second is only cheap because the first one made "where is the paper" a value that
something says out loud instead of a rectangle six files assumed.

SPEC §4.5 is the specification half. This is the record of what was found, what was decided, and
what was actually checked.

---

## 1. The bug

> Free panning (SPEC §4.5, revised in M1) means the empty space beside the page is one finger-drag
> away — and it takes ink exactly as the page does.

A stroke made there is captured, converted at a **negative page-space coordinate**, journaled, put
on the undo stack and synced like any other. It draws on screen, because the dry layer clips to the
viewport and not to the page. And **no PDF reader draws it**, because an annotation outside the
CropBox is not rendered.

Reproduced before any code changed, on an emulator, by the path a user takes:

```
paper at device x 714–1845; drew with `input stylus swipe 400 800 620 860`
journal.ops:  {"t":"add", ..., "obj":{"k":"s","geo":{"o":0,"n":298}}}   ← accepted
undo.json:    720 bytes                                                ← and undoable
```

Ink the app showed and every other program would drop, with nothing anywhere saying so. That is
CLAUDE.md rule 1's failure class arriving through the pen rather than through sync, and it had been
reachable since M1.

## 2. What was decided

### 2.1 The paper is a value — `PageCanvas`

`PageSize` is an *extent*; it cannot say where the paper is, and everything therefore assumed
`[0, 0, width, height]`. Nothing enforced that assumption, and an endless canvas cannot satisfy it.
`PageCanvas(size, origin, infinite)` replaces it wherever the question is *"where is the paper"* —
the two renderers, the camera, the thumbnail, and the publisher.

⚠️ **The origin is not a second coordinate space**, and that is deliberate. Nothing is ever expressed
relative to it: every stroke, capsule and transform stays in page space with the origin page space
has always had. It is read in exactly two kinds of place — the ones that draw the paper's rectangle,
and the one that hands a page to a PDF writer. CLAUDE.md's note about erase capsules — *"when a
coordinate space is only ever the identity, two different spaces have the same numbers and the code
says the wrong one for free"* — is about precisely this hazard, and the defence is that there is no
second space to name.

### 2.2 A fixed page refuses, and clips

- **Refuses at the ACTION_DOWN**, before `strokes.beginEdit()`, so nothing is opened that would have
  to be unwound: no op, no undo entry, no half-stroke on the wet layer. There is a 6 pt slack,
  because a hand aiming at the very edge of the page lands a little past it.
- **Clips the dry layer to the paper**, so a stroke that begins on the page and runs off the edge is
  cut where the PDF will cut it. Clipped, never culled: the geometry stays whole in the log, so
  widening the page or turning the canvas endless brings all of it back with nothing to reconstruct.

Refusing *and* clipping, rather than either alone: refusing handles writing beside the page (which
is how the bug is reached), clipping handles the stroke that crosses the boundary (which refusing
cannot, since the gesture legitimately began).

### 2.3 Growth is in all four directions, and moves nothing

Asked for explicitly. Growing up or to the left moves `origin` negative and **no object's
coordinates change** — so growth is one absolute value rather than a `SetTransform` per stroke, and
a peer's concurrent stroke still lands where they drew it. The alternative, re-anchoring page space
and translating everything, is a rewrite of the page that a concurrent `AddObject` from a peer would
silently miss.

### 2.4 ⭐ The paper is derived, not journaled

This is the decision the whole feature turns on. `SetPageMeta` carries only the switch (`infinite`);
how big an endless page *is* is `stored.grownToInclude(contentBounds)` — computed by the editor
after a stroke, by the thumbnail renderer, and by the publisher before it writes the box.

It buys three things at once:

| | Derived | If growth were journaled |
|---|---|---|
| Cost of writing down a long page | nothing | a `SetPageMeta` per slab, per device |
| SPEC §13.2 merges | no rule needed — a peer's strokes are *in* the fold, so the page grows to hold them the moment it is folded | an LWW size register that can resolve the other way and leave a peer's ink outside the box |
| Screen vs PDF | the same function of the same objects | two values that must be kept in step |

The trade, stated because it is real: the derivation is honest in both directions, so erasing or
undoing the outermost stroke takes the paper back down. Quantising growth to half a page (396 pt),
with a 72 pt margin beyond the furthest ink, is what keeps that from being twitchy.

⚠️ **The editor keeps the stored canvas and the grown one apart** (`storedCanvas` /`pageCanvas`).
Re-deriving from the grown one instead would make every growth permanent for the life of the note.

### 2.5 Per page, off by default

In the paper menu beside the templates, not as a twelfth chip on the page bar —
`docs/M10-PORTRAIT.md` records that bar running past its own edge at 800 dp, where a control that
has scrolled out of a pill is *absent from the view tree* rather than merely off-screen. The chip's
label carries `∞` so the state is visible without opening anything.

## 3. What the PDF does

For an **imported** page the box is enlarged to reach the canvas and `PdfPageGeometry.origin`
cancels the move: page space is measured from the box's displayed top-left (SPEC §4.4), so enlarging
the box without that would slide every annotation already on the page by however far it grew.

For a **generated** page there is nothing to preserve — it is created at the canvas's own extent, so
its box needs no adjusting at all.

⚠️ **Conflating those two published a 1008 pt canvas as a 1404 pt page** with a slab of blank paper
down one side: the page was created at the canvas size *and* then unioned with the canvas mapped
into it, counting the growth twice. Every assertion made with PDFBox agreed with itself. What caught
it was reading the published `/MediaBox` back with a regex over the file's own syntax — CLAUDE.md's
*"verifying a PDF by re-parsing it with the library that wrote it proves almost nothing"*, once
more.

## 4. What was checked, and how

CLAUDE.md's definition of done #6 asks which of *written*, *automated*, *automated against the real
dependency*, and *run on a device* each claim is. Per claim:

| Claim | Check | Level |
|---|---|---|
| A stroke begun beside a fixed page is refused | `CanvasBoundsTest` — finger-pans the real `InkSurfaceView` in a real window, then draws with a synthetic stylus | **run on a device** (emulator), and **fails without the fix** — verified by disabling the gate and watching it go red |
| The same gesture on the paper still draws | the control in the same file, so "nothing was recorded" cannot pass for the wrong reason | run on a device |
| A stroke crossing the edge is kept, not refused | same file | run on a device |
| An endless page grows instead of refusing | `CanvasBoundsTest`, plus the emulator walk below | run on a device |
| A fixed page beside it still refuses | same file | run on a device |
| Growth is order-independent, so a merge cannot lose a peer's ink | `PageCanvasTest` | automated |
| Growing a page moves nothing already on it, at every `/Rotate` | `PageCanvasPdfTest` | automated |
| The registers survive the file, and a fixed page's bytes are unchanged | `InfiniteCanvasFormatTest` | automated |
| The published page is the size of the paper, with the ink inside it | the walk below, read with a **non-PDFBox** reader | run on a device |
| Ink feel, latency, frame timing after the clip | — | **not checked.** The emulator cannot answer it (CLAUDE.md), and the tablet pass is outstanding |

### The emulator walk, end to end

1. `NEW NOTE` → paper chip (`RULED`) → **`INFINITE CANVAS`**.
   Chip becomes `RULED ∞`; `journal.ops` holds `{"t":"meta", ..., "inf":true}`.
2. `FIT PAGE`, then `input stylus swipe 400 800 620 860` — entirely to the left of the paper.
   The paper's white rectangle in a `screencap` goes from device x `714–1845` to `0–1845`: it grew
   one slab to the left, under the stroke.
3. Close the note, reopen it. Chip still reads `RULED ∞`; the paper is still 1008 pt wide, re-derived
   from the ink on load rather than remembered.
4. The paper chip again → **`FIXED PAGE`**. The paper snaps back to the Letter sheet, live and
   without a page reload — measured off a `screencap`, device x `0–1845` → `714–1845`. The ink
   outside it is clipped, not deleted; turning the canvas endless again brings it back.
5. `SHARE` → `PUBLISHED PDF`, and read `document.pdf` back with a regex over its own syntax:

```
MediaBox: [0, 0, 612, 792]      ← the base, written once and rebased on (SPEC §5.2)
MediaBox: [0, 0, 1008, 792]     ← the update: Letter plus one 396 pt slab
/Rect   : [224.3, 364.1, 346.2, 399.8]      every /Ink annotation inside the box: True
```

Before the change that annotation sat at x ≈ −171 in a 612-wide box — present in the file, drawn by
nothing.

## 5. What is not done

- **The tablet.** Definition of done #4; the emulator does not substitute, and nothing here has been
  judged for ink feel or for how the clip looks at the paper's edge on real hardware.
- **Two peers.** The merge argument in §2.4 is structural — growth is derived from the fold, so
  there is nothing to converge — and it is asserted as a property in `PageCanvasTest`. It has not
  been run against two devices with different `device.id`s.
- **Objects, as opposed to ink.** A text box or an image can still be *placed* outside a fixed
  page's paper; the gate is on the pen. They are clipped like everything else, so nothing is
  invisible-but-exported, but they are not refused.
- **`Add page` does not inherit the canvas mode**, only the template. A notebook that should be
  endless throughout is set per page.
