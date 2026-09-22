# M10 follow-up — the editor held in portrait

The redesign was built, and every time verified, on a tablet in landscape. Held in portrait, three
of its floating bars overflow the window and hide their own last controls behind a sideways drag
that nothing on screen suggests.

**What shipped from this is one change: an overflowing `GlassBar` now fades at the end it has more
content beyond.** No pill moved. Three layout fixes were built for the bars that overflow, and all
three were reverted — the record of why is §3, and it is the useful half of this document.

---

## 1. The measurements

An emulator (`Test_Tablet`, 2560 × 1600 at density 2 — 1,280 × 800 dp in landscape, 800 × 1,280 dp
in portrait), rotated with `settings put system user_rotation 1`, driven over `adb`, read back with
`uiautomator dump`. The dump is the instrument that matters: a chip past the end of a scrolling pill
is **absent from the XML**, which is the distinction a screenshot makes badly and a green test makes
not at all.

Natural widths, measured in landscape where nothing is squeezed:

| Bar | Natural width |
|---|---|
| History (`UNDO` `REDO`) | 143 dp |
| Page bar (`‹ n of m ›`, `ADD`, `DUPLICATE`, `DELETE`, paper, `SHARE`) | 598 dp |
| Zoom (`59%`, `FIT WIDTH`, `FIT PAGE`, `100%`, `⋯`) | 390 dp |
| Dock row 1 (eight tool chips) | 621 dp |
| Dock row 2 (brushes, width, swatches, `+`, `SNAP`, `ARROW`) | 915 dp |
| `RECORD` chip | 78 dp, and wider once it is a running timer |

The bottom three plus two 28 dp gutters and 12 dp of edge padding at each end is **1,211 dp**.
Landscape has 1,280 and clears it. Portrait has 800 and does not, so the weighted centre zone
collapses to **188 dp** and `ADD`, `DUPLICATE`, `DELETE`, the paper chip and `SHARE` are off the end.
Dock row 2 overflows the same way, taking `SNAP` and `ARROW` with it; so does the library's toolbar,
losing `TRASH`.

---

## 2. What shipped: `GlassBar` fades at an overflowing end

`GlassBar`'s KDoc already argued that scrolling is what stops *"a toolbar whose last two tools are
simply gone on the axis the user happens to be holding"* — and scrolling **is** that failure, one
step later, for anyone who does not already know the chips are there. Worse, an overflowing bar
fills the width it is given, so both of its rounded ends are cut square against the screen: the one
shape that says *"this pill continues"* is the first thing an overflow removes, and what is left
reads as broken chrome rather than as a scroll.

A 22 dp gradient now paints over whichever end has content beyond it, in the bar's own translucent
panel colour, so a chip slides *under* the edge rather than behind a patch of different material.
Two mechanics matter:

- The fade is on the **outer** element and the scroll on the inner `Row`. A fade painted inside the
  scrolling row scrolls away with the chips it is meant to be covering.
- `drawWithCache`, so the gradients are built per size change and not per frame, and both `if`s read
  `scroll.value` — a bar that fits, which is every bar in landscape, draws nothing extra.

It is a paint-only change: it adds no layout node that affects measurement, which the dumps in §4
confirm.

---

## 3. What was reverted, and why — three attempts

### 3.1 Stacking the page bar onto a second line

The obvious fix for a 188 dp centre zone: below a measured 1,220 dp threshold, give the page bar a
line of its own. Built with a pure `BottomZones.stacked(width)` so a JVM test could assert the
decision, broken on window **width** rather than `Configuration.orientation` so split-screen and
folded windows got it too. It worked — the portrait dump showed the bar `scrollable=false` with all
598 dp of it on screen.

It was rejected on sight, twice. First with the bar on the upper line — *"the thing that has ADD
DUPLICATE and DELETE is moved up and i dont like that"*. Then, after swapping so the page bar kept
the bottom line and the other two moved up — *"its not like how it was at all"*.

⚠️ **The second version was worse than the first, and the reason is worth keeping.** A stacked row
pushes the history and zoom pills **up onto the paper**, so they straddle the page's bottom edge
instead of sitting on the canvas below it. Two pills cut in half by the edge of the page read as
broken, not as separated. The bottom chrome's whole visual premise is that it sits *below* the
document; a second row has nowhere to go that does not violate it.

### 3.2 Laying the dock and the record chip out as siblings

In portrait the dock's primary row ends at x=1401 and `RECORD` begins at x=1401 — they abut, and the
rounded ends read as one merged blob with `RULER` squashed against it. The fix that suggests itself
is the one `BottomWidgets` already carries: put them in a `Row` so overlap is unrepresentable.

Reverted, because it centres the dock in *what is left* rather than on the window, which moved it
**63 dp in landscape** — and landscape was not what needed fixing.

### 3.3 Reserving the record chip's measured width symmetrically

The repair for 3.2: pad the dock by the chip's `onSizeChanged` width at **both** ends. Landscape is
then byte-identical (the cap, 1,044 dp, is wider than the dock's widest row at 915 dp) and portrait
cannot collide.

Reverted too. It narrows the dock in portrait by 212 dp, which pushes `RULER` off the end of a
scrolling bar: it trades a chip that *looks* cramped for a chip that is *not there*.

⚠️ **And the premise was wrong anyway.** Re-reading the original dump: the dock's hit rectangle ends
at 1401 and the record chip's begins at 1401. They touch; they do not overlap, and every chip keeps
its own hit target. Row two is 100 dp lower and never meets the chip at all. This was an appearance
problem being fixed as if it were a reachability problem — the exact inversion of the failure this
repo usually has, and it cost two builds and a regression before the dump was read carefully enough
to say so.

---

## 4. What was checked

| Claim | Check |
|---|---|
| Landscape is exactly as it shipped | `uiautomator dump` diffed against the dump taken **before any edit**: **41 controls, 0 moved** |
| Portrait is exactly as it shipped | same diff in portrait: **34 controls, 0 moved** |
| Nothing regressed | `:app:testDebugUnitTest --rerun --no-build-cache` — counts read from `test-results/*.xml` after stopping the daemons |
| Style and guardrail | `:app:detekt`, `spotlessCheck`, `checkCoreIsPlatformFree` green |

⚠️ **A test count is only evidence if the run that produced it was clean, and two runs here were
not.** The first reading summed `tests=` over `test-results/*.xml` **while Gradle was still writing
them** and undercounted by the whole `MergeMatrix*` set. The second was taken after that directory
had been deleted out from under a live daemon, and reported **40 failures across all eight matrix
classes** — a number that survived a repeat and looked exactly like a real regression. Both vanished
once the daemons were stopped and `build/test-results` removed cleanly; the baseline tree, measured
the same way, gave 175/0 and the changed tree 178/0. CLAUDE.md already warns that a green `build`
can execute nothing; the mirror is that a **red** one can be measuring a corrupted build directory.
Stop the daemons, delete the results directory, force the task, read the count back — and on a
branch with this much uncommitted work, take the baseline the same way before believing a failure
is yours.

⚠️ **The ink surface renders garbage on this emulator and it is not ours.** Screenshots of the page
show white blocks, checkerboards and — decisively — fragments of the Google logo: an uninitialised
buffer being composited for `InProgressStrokesView`'s front-buffered `SurfaceView`. It was chased
for two builds before being pinned down by reverting the change and finding the artefact *worse* on
the untouched build. It varies run to run because the garbage is whatever was in memory, which is
why the first screenshot of a session can happen to be clean. Same territory as CLAUDE.md's
*"`:android:ink`'s instrumented tests fail on an x86_64 emulator and pass on the arm64 tablet"*:
**judge nothing about ink rendering from an emulator screenshot.**

---

## 5. Still true, and deliberately

- **The page bar, dock row 2 and the library toolbar all scroll in portrait.** That is the shipped
  behaviour and it is unchanged; what changed is that they now show they can be dragged. Making
  everything fit needs either a second row (§3.1) or fewer controls, and neither is wanted.
- **Rotating does not refit the camera.** `InkSurfaceView.onSizeChanged` re-leashes and keeps the
  user's pan and zoom on any size change after the first, so a landscape→portrait rotation leaves the
  page at the old scale until `FIT WIDTH` is pressed. That is `setPageMeta`'s documented policy
  (*"a refit would be unrecoverable: the camera is persisted nowhere"*) reaching a case it was not
  written for — a viewport whose **width** changed, where the scale that was fit-width no longer is.
  Not changed here: it is a camera decision rather than a layout one, and it deserves the tablet
  rather than an emulator whose ink layer cannot be trusted (§4).
- **Definition of done #4 is not met by this document.** Everything above is an emulator.
