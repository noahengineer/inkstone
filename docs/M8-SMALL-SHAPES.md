# M8 follow-up 7 — a small shape could not be drawn

> *"i noticed a really big problem where if you try to make a small shape it does not work or does
> not do it properly like if you try make a shape like a line or circle or whatever it just does
> not work and if you create a small line but not too small it creates a line that does not have
> the proper points or whatever instead its like this weird image like thing"*

> *"ok its much better but the shapes should be able to be created smaller"*

Four defects, all of them about size, all of them invisible at the sizes draw-and-hold had ever
been tested at. §1–3 came out of the first report and §4 out of the second. SPEC §4.8.7 has been
updated.

**Where it ended up.** The smallest gesture that snaps in at least 10 of 12 seeds, at ±0.25 pt of
per-sample jitter (about what an S Pen produces at fit width) and at ±0.75 pt (several times that):

| | ±0.25 pt | ±0.75 pt |
|---|---|---|
| line | **12 pt** | 12 pt |
| circle, across | **12 pt** | 15 pt |
| square | **8 pt** | 12 pt |

§1's and §4's own tables carry the before-and-after for each change. Between them a line went from
refusing everything under about 36 pt, to 24, to 12.

At fit width a page point is about a third of a millimetre, so the floor is now roughly **4 mm**.

---

## 1. The dwell radius was also the sampling spacing

`ShapeSnapDetector` has to keep the hold's own samples out of the gesture it is classifying — half
a second of tremor at 240 Hz piles ~130 points onto one spot, `ShapeSnap` divides by `pathLength`
twice, and every threshold moves. M8b solved that by recording a point **only when the pen left the
dwell window**, which is right about the dwell and made `DEFAULT_HOLD_RADIUS_PT` the gesture's
sampling resolution as a side effect.

A hold radius cannot go much below 6 pt — it is a fact about a hand at rest. So the classifier saw
a polyline whose vertices were 6 pt apart *however small the shape was*, and `ShapeSnap.MIN_POINTS`
needs five of them before there is anything to classify. Twelve seeds of a fixture with ±0.75 pt of
per-sample jitter, which is several times an S Pen's; the two columns differ **only** in the
sampling spacing and `MIN_TRAVEL`, so §2's fix is present in both and the column isolates this one:

| gesture | 6 pt spacing | 3 pt spacing |
|---|---|---|
| line, 120 pt | 12/12 | 12/12 |
| line, 36 pt | 12/12 | 12/12 |
| line, 30 pt | 12/12 | 12/12 |
| line, 24 pt | **3/12** | 12/12 |
| line, 18 pt | **0/12** | 4/12 |
| circle, 24 pt across | 12/12 | 12/12 |
| circle, 18 pt across | 8/12 | 12/12 |
| circle, 12 pt across | 0/12 | 6/12 |
| square, 18–36 pt | 12/12 | 12/12 |

§2's truncation compounded it in the shipped code: a 30 pt line reached the classifier as a 25.7 pt
one, so every row above was being asked about a shorter gesture than the user drew.

**A closed shape hid the bug for a milestone**, and the table says why: what gets sampled there is
the *perimeter*, so an 18 pt circle already had three times the points a 24 pt line did and a
square was never affected at any size a person draws. That is why the report named lines first.

The two jobs are two constants now. Points are recorded every `DEFAULT_SAMPLE_SPACING_PT`; the ones
taken since the pen last left the dwell window are a **scratch tail** that `classify` discards, so
the dwell still contributes nothing. A pen merely crossing the window keeps its resolution, which
is what the tail exists for, and `DWELL_PATH_LIMIT` bounds it for a pen that mills about inside the
window and then carries on drawing.

### …and finer is not better

The spacing is 3 pt and deliberately not less. `openCandidate` scores a line by
`chord / pathLength`, and a **summed path length grows with the digitiser's own jitter as the
spacing shrinks**. Worst of twelve seeds over a 100 pt line:

| per-sample jitter | 1.5 pt spacing | 3 pt | 6 pt |
|---|---|---|---|
| ±0.25 pt (an S Pen at fit width) | 0.99 | 0.99 | 1.00 |
| ±0.5 pt | 0.94 | 0.99 | 1.00 |
| ±0.75 pt | 0.79 | 0.96 | 0.99 |
| ±1.0 pt (zoomed well out) | **0.44** | 0.92 | 0.98 |

`LINE_MIN_STRAIGHTNESS` is 0.93, so at 1.5 pt a shaky hand's line stops being a line — the same
bug in the opposite direction. 3 pt keeps a factor of two of headroom over any jitter a stylus
plausibly has and is still half the dwell radius, which is all a small shape needed.

`DEFAULT_CAPACITY` had to move with the spacing: capacity × spacing is the longest path the buffer
holds, and halving one without doubling the other truncates a large circle mid-gesture. The product
is unchanged at 3,072 pt.

`DEFAULT_MIN_TRAVEL_PT` drops 24 → 16, because it is a real path length now rather than a count of
6 pt window exits. Tremor still cannot reach it: distance only counts once the pen has *left* the
window, which a resting hand never does.

## 2. A snapped line stopped short of where it was drawn

A `LINE` is placed by its two endpoints and not by its box (SPEC §4.8.9), and `SnapCandidate.from`
/ `to` are the classified run's first and last points. That run ends at the last sample which was
unambiguously drawing — **one dwell radius behind the tip, by construction**. So every snapped line
came out up to 6 pt shorter than the gesture: invisible at 120 pt, a fifth of a 30 pt one.

`classify` appends the pen's resting position once. Measured end to end on the emulator, a 63 px
gesture at 2.606 px/pt now yields a 24.48 pt line where the gesture was 24.2 pt.

The general lesson: **when a buffer is deliberately truncated for one consumer's sake, check what
the other consumers read off its ends.** The truncation was correct for `pathLength` and wrong for
the two values that are the shape's actual geometry.

## 3. The shape was drawn underneath its own handles

A snapped shape is selected the moment it is made (SPEC §4.8.7), so its point handles appear
immediately: two 13 dp vertex squares and a 12 dp bend ring, about 18 pt of chrome at fit width. A
30 pt line is three-fifths covered by them; a 24 pt one has nothing visible in between. What is on
screen is a filled square, a ring and a filled square — a glyph, not a line, which is what
*"this weird image like thing"* is describing.

`SelectionOverlayView.drawPoints` scales the drawn handles so the closest pair never fills more
than `HANDLE_GAP` of the gap between them, with a floor. ⚠️ **The touch target is untouched**
(`SelectionController.HANDLE_TOUCH_DP`): a handle you can *see* is a drawing question and a handle
you can *hit* is a finger question, and tying them together is what would make a small shape
unusable rather than merely unreadable.

---

## 4. `travel` counted in 6 pt lumps, and dropped the last one

§1 fixed how the gesture was *sampled* and left a second, independent size limit standing: the
gate that decides whether a gesture happened at all.

`travel` only grows when the pen **leaves** the dwell window, so it is a multiple of the hold
radius and it never sees the final leg. A 15 pt line exits at 6 and at 12 and then finishes 3 pt
later — measured as **12**, against a floor of 16. So the whole range from about 12 to 21 pt was
refused *by the dwell*, before the classifier was consulted at all. Which of the three gates was
refusing turned out to be the whole diagnosis:

| gesture | dwell never fired | classifier refused | ok |
|---|---|---|---|
| line, 21 pt | 0 | 0 | 12 |
| line, 18 pt | **7** | 1 | 4 |
| line, 15 pt | **12** | 0 | 0 |
| line, 12 pt | **12** | 0 | 0 |

Nothing about the *shape* was ever the problem: a 12 pt line's straightness measures 0.93–1.00
against a bar of 0.93. It looked like the hold was not being detected, because that is exactly what
was happening.

Three things were wrong with `travel`, and only the first was visible from a fixture.

**It missed the last leg.** Adding the straight-line distance from the anchor to the pen (`reach`)
is exact for it and **tremor-proof by construction** — it is bounded by the hold radius, and a pen
resting where it went down never moves the anchor, so a rest contributes its own wobble and nothing
more.

⚠️ **It was summed per *recorded point*, not per sample.** Writing `pendingTravel += step` inside
the branch that records a point reads perfectly naturally and loses whatever the pen covered after
the last point worth recording — a systematic under-estimate of the one value here with a threshold
on it. **Every fixture passed it**, because a fixture's samples land on the spacing by
construction; a 12 pt line drawn through the app over `adb` did not snap. It is summed per sample
now, and `the size floor does not depend on the sample rate` is the guard.

⚠️ **And the classified gesture excluded up to a whole dwell window of real drawing.** §1's scratch
tail ended at the last window *exit*, so a 12 pt line was classified from its first 8 pt — three
points, where `MIN_POINTS` wants four. Negligible for a big shape, half of a small one. The
exclusion is unnecessary now and has been deleted: the problem it existed for was tremor piling
~130 points onto one spot, and at one sample per 3 pt of *movement* those points never exist.
`DEFAULT_SAMPLE_SPACING_PT` does the job a rule was doing, and `DWELL_PATH_LIMIT` is the backstop
for a pen that mills about rather than rests.

### What this costs, stated plainly

Draw-and-hold cannot tell a deliberate 12 pt dash from the downstroke of a letter somebody paused
in the middle of. Nothing separates them but the half-second of stillness, and the smaller the
floor, the more ordinary marks are eligible. `DEFAULT_MIN_TRAVEL_PT` and `MIN_PATH_LENGTH_PT` are
the pair to raise if that starts happening — **not** the sampling spacing, which decides how well a
gesture is measured rather than how small one may be, and which has its own guard
(`a shaky hand can still draw a line`).

Below about 10 pt the mechanism itself runs out rather than a threshold biting: a gesture smaller
than the 6 pt hold radius never leaves the dwell window, so it can never accumulate travel. That is
the floor under all of this, and it is around 12 pt of extent.

## 5. A confidence bar downstream of a ramp, for the fourth time

Removing the tail exposed one more thing, and it is the trap CLAUDE.md already records three
instances of. `openCandidate` scored a line by ramping `0 → 1` across
`[LINE_MIN_STRAIGHTNESS, LINE_CERTAIN]`, and `recognise` then drops anything under
`DEFAULT_CONFIDENCE = 0.55`. So the **effective** floor was
`0.93 + 0.55 × (0.96 − 0.93)` = **0.9465**, not the 0.93 the constant names: a 12 pt line whose
last sample sat 0.4 pt off the chord scored straightness 0.945, confidence 0.50, and was refused by
a limit it was comfortably inside.

The repair is the one `arcCandidate` already carried and this file's own KDoc preaches: **ramp down
to the bar, not to zero**, so everything the check accepts also clears the bar by construction and
a straighter line still ranks above a less straight one. Previous victims: `FILL_WINDOW`,
`LINE_CERTAIN` itself, and `ARC_MAX_RESIDUAL`.

⚠️ Worth stating why this only surfaced now: appending the pen's resting tip (§2) makes the *last*
segment of every gesture the length of a tremor, which is where a narrow ramp is least forgiving.
A fix in one place moved a threshold in another, which is the argument for the ramps being
normalised the same way rather than tuned.

---

## 6. How each of these was checked

| | small shapes | line's length | handles |
|---|---|---|---|
| automated | `ShapeSnapDetectorTest` — 12 seeds per size | same, ±2 pt | — |
| goes red without the fix | ✅ 12/12 refused at the old floors | ✅ 30 pt came out 25.7 pt | — |
| driven through the app over `adb` | ✅ 10 / 12 / 15 pt lines, 12 pt circle, 12 pt square | ✅ read off `journal.ops` | ✅ screenshot |

⚠️ **Two of the four defects were found only by the `adb` walk**, and both had passed every fixture:
the per-sample path length in §4, and the excluded tail beside it. A fixture chooses its own sample
positions and a digitiser does not.

The `adb` walk is the one that matters (CLAUDE.md, definition of done #6): each gesture was drawn
with `input stylus motionevent` — plain `input` draws nothing, `PalmGuard` accepts only
`TOOL_TYPE_STYLUS` — and the resulting `AddObject` read back out of `journal.ops` with `run-as`.
Recipe for a hold the shell can express: `DOWN; N× MOVE; MOVE <1 px away>; sleep 2; MOVE; sleep 2;
UP`.

⚠️ **`adb shell input` delivers about ten events a second, which is slow enough to fire the dwell
mid-gesture.** Each `input stylus motionevent` is its own process, so a 40-segment circle takes four
seconds; the pen then spends more than the 550 ms hold inside the dwell window while still drawing,
and what snaps is the arc drawn so far. Keep a shell-driven gesture to roughly a dozen events, or
read the result as an artefact. (Two apparent bugs were this. A third — shapes with a spike coming
off them — turned out to be *correct*: a test line drawn near a snapped shape bound to it as a
connector and re-routed, which is M8b working.)

⚠️ **The emulator cannot answer §3's question and can answer it anyway.** Its ink surface renders
uninitialised-buffer garbage on x86_64 (M10 follow-up), so nothing about how the *ink* looks is
believable there — but `SelectionOverlayView` is an ordinary View drawing to HWUI, so the
handle-to-shape ratio measured off a screenshot is exact, and it is a ratio rather than an
appearance.

---

## 7. What is not settled

The **feel** — whether 12 pt is now too eager, and whether a comma or a tick held still gets eaten
— is a tablet question and only the tablet can answer it. See §4's *"what this costs"*: the pair to
raise is `DEFAULT_MIN_TRAVEL_PT` and `ShapeSnap.MIN_PATH_LENGTH_PT`, together.

The closed-shape floor is also where the *fixture* stops being a witness. At ±0.75 pt of jitter a
12 pt circle is refused 8 times in 12, and at ±0.25 pt it is refused none — because independent
white noise at an eighth of the radius is nothing like a hand, whose tremor is correlated. M7's
rule, again: only the tablet can say what a person's 4 mm circle scores.
