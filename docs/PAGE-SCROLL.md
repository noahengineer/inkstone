# Scrolling through the pages like a PDF

> *"make it so that there is a setting to allow you to scroll through pages like pdf"*

A setting, **off by default**: *paper menu → Scroll pages like a PDF*. Turned on, the note's pages
run on down the screen with a gap between them, and panning past the bottom of a page carries on
into the top of the next instead of stopping at the edge.

SPEC §4.5 ("Scrolling through the pages like a PDF") is the specification half. This is the record
of what was decided, what was built, and — per CLAUDE.md definition-of-done #6 — which claims have
actually been checked and how.

---

## 1. The constraint that decided the design

The editor holds **one page at a time**: one fold, one `StrokeStore`, one `PageOpRecorder`, one undo
stack per page. Every writer — pen, eraser, selection, text, undo — is bound to it. A continuous
scroll that kept two pages live would make *"which page does this stroke belong to"* a question
every input path had to answer, on the path CLAUDE.md rule 7 says may not do anything heavy.

So the surface does **not** become a multi-page canvas. It stays a one-page editor, and:

| | |
|---|---|
| The page above and below | **bitmaps**, drawn under the page, in the active page's space |
| What is editable | the one page the middle of the view is on |
| Moving between them | a normal page turn (`showPage`), with the camera carried so nothing moves on the glass |

The consequence is that nothing durable changes: no op, no new field in the bundle, no change to
the published PDF or to sync. The setting only changes how the camera behaves and what is drawn
beside the paper. That is also why it is safe to switch on and off at any time.

## 2. The pieces

- **`PageStrip`** (`:core:model`, `commonMain`) — the arithmetic, and the only part with a
  mathematical claim: where a neighbour sits, what the camera may reach, when the view has *entered*
  a neighbour, and the camera after a turn. Platform-free, so it also links for iOS.
- **`PageNeighbour`, `InkSurfaceView.setStrip`, `DryStrokesView.drawPicture`** (`:android:ink`) —
  hold and draw the pictures. Drawn **outside the recorded `RenderNode`**: a picture arriving from a
  background render must never invalidate a display list that costs 48–105 ms to make.
- **`InkPageThumbnailRenderer.renderBitmap`** — the thumbnail renderer's drawing routine with a
  bitmap out instead of WebP bytes. One routine behind both entry points, so a neighbour cannot
  disagree with the page it becomes about spacing, fills or erasures.
- **`PageStripRenderer` / `PageStripCache` / `NoteEditorViewModel.pagePicture`** (`:app`) — render
  from the *open session* (the journal has ops the bundle does not), reuse a picture while the page's
  revision (`opCount` + `throughClock`) is unchanged, bounded at five pictures.
- **`PageScrollEffects`** (`InkScreen`) — the two hand-offs: a crossing calls `showPage`; the
  pictures are drawn immediately when the page changes and two and a half seconds after the *last*
  change otherwise.

## 3. Decisions worth writing down

**Turn when the view's centre *enters* the neighbour, not at the gap's midpoint.** Entering leaves
the centre exactly one gap inside the new page; turning back needs it to cross that gap again. A
midpoint threshold has no margin: a hand resting at the boundary turns the page over and back on
every wobble. (`PageStripTest` asserts the gap itself never turns.)

**The surface rotates its own pictures at the moment of the turn.** The page just left becomes the
neighbour on the side the camera came from, and the page turned to is drawn over its own bare paper
until its ink is built (`DryStrokesView.interim`). Without that, a scroll visibly emptied the page
you were reading for as long as the editor takes to render what comes next. The editor then replaces
all three with authoritative ones.

**Neighbours are `RGB_565`, at most 1,200 px wide.** Three Letter pages at the screen's 2,560 px in
`ARGB_8888` are ~100 MB, and this app has already had its own screen destroyed by its own heap
(`docs/NOTE-STAYS-LOADED.md`). At 1,200 px / 565 it is ~11 MB for the three. The cost is that a
neighbour is soft at fit-width on a high-density tablet — it is a stand-in, replaced by the real page
as soon as it is the one being edited.

**A pen on a neighbour turns to it, and loses that stroke.** There is nothing yet to put the stroke
on (the page is a picture), and the alternative — refusing for ever — would be a page you can see and
cannot write on until you scroll a little further.

**Where the setting lives.** Not a twelfth chip: `docs/M10-PORTRAIT.md` records the page bar running
past its edge at 800 dp. The paper menu already answers *"what is a page in this note"*, and this is
the next question after it. It is an editor-wide preference stored with the toolbar's memory
(`ink.scrollPages`), not a property of the note — it does not sync, and two tablets can disagree.

## 4. What it does not do (yet)

- **Pictures inside a neighbour draw as placeholders.** The editor's decoded-picture cache keeps the
  page on screen and evicts the rest, so lending it to a neighbour would push out the page being
  written on. A grey box where a photograph will be is an honest stand-in.
- **A neighbour can be a couple of seconds stale.** The page being edited is re-drawn 2.5 s after
  the pen stops. Write, then scroll away inside that window, and the page you left shows its previous
  contents for the moment it takes to re-render — then catches up. The data is never affected; it is
  a picture.
- **An infinite page's neighbours hang from the paper as it is now.** Writing near the bottom of one
  grows it, and the page below moves away with the edge.
- **No fling.** Panning has never had momentum in this app, so a long scroll is a long drag.
- **Zoom is per page.** A turn does not change the scale, but pages of different widths are centred
  on one another rather than fitted.

## 5. Per-claim status

| Claim | Check | Level |
|---|---|---|
| A neighbour is placed a gap away, centred, from the paper's own edge (including a grown one) | `PageStripTest` | **automated** |
| The camera may reach all three pages and no further | `PageStripTest` (`reach`) | **automated** |
| The gap does not turn; entering a neighbour does; the ends have nowhere to turn | `PageStripTest` | **automated** |
| A turn leaves every point on the glass where it was, across widths, and forward-then-back is the identity | `PageStripTest` | **automated** |
| The picture cache is bounded, per page, and never serves a changed page | `PageStripCacheTest` | **automated** |
| The preference is off by default and round-trips | `EditorPrefsCodecTest` | **automated** |
| `PageStripController`: pictures for a page not on screen are dropped, one crossing asks once, the strip after a turn has the page just left on the right side | `PageStripControllerTest` (15) | **automated** |
| The paper menu shows *Scroll pages like a PDF* and flips the setting | driven over `adb` on the `Test_Tablet` emulator (3-page note) | **run on a device** (emulator) |
| The page above is drawn beside the current one, with a gap | same walk, `screencap` | **run on a device** (emulator) |
| A finger drag far enough turns the page: counter `2 of 3` → `1 of 3`, no crash, the seam stays where it was on the glass | same walk, `uiautomator dump` + `screencap` | **run on a device** (emulator) |
| How the neighbour *looks* (sharpness, the paper's edge) | the emulator's ink surface is unreliable for appearance (CLAUDE.md) | **not judged** |
| Frame rate / ink latency with the setting on | — | **not measured**; needs the tablet (the emulator cannot answer it) |

## 6. Not verified, and how to

Everything on the surface is unverified until somebody holds it. CLAUDE.md says the emulator can
answer *"is this reachable"* but **not** *"how does ink or paper look"* (its x86_64 ink surface renders
garbage), so:

1. **Reachable — done on the emulator** (2026-10-17): new note, `ADD` twice, paper chip → *Scroll
   pages like a PDF*, `Previous page`, then two `adb shell input swipe 1280 400 1280 900 800`. The
   first revealed the picture of the page above under a dark gap; the second crossed into it and the
   counter went `2 of 3` → `1 of 3` with the page below now the picture. ⚠️ A swipe made *immediately*
   after a page change moved nothing — the pictures had not arrived yet and the leash still covered
   the paper alone; wait a moment after turning. Left on the emulator: an `Untitled` 3-page note.
2. **Feel** — on the tablet: no jump at the boundary, no flicker of the page you left, no ping-pong
   when a drag stops at the seam, and writing on the new page straight away.
3. **Cost** — scroll across the physics note's pages on the tablet's release build and watch the
   dropped-frames counter; the render is on `Dispatchers.Default` but competes for the same cores.
