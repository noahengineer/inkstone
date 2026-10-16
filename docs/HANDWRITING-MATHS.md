# Handwritten maths: fractions, roots, brackets

**Status: built, tested, walked on `Test_Tablet`, stopped for review (2026-10-15).** It has not run
on the Galaxy Tab: the tablet was unplugged when the walk was due. **Re-measured through the real gate
on 2026-10-16**, under the rule the user adopted that day: a run with a digit is judged on its first
reading that holds a digit (§5.4).

The user's requirement (2026-10-15): *"it should be able to do fractions and square roots, cube
roots, brackets, stuff like that"*. Maths is their priority. This follows
docs/HANDWRITING-SYNTHESIS.md §20.6.2, item 3, which recorded the decision; this document is what was
built.

## 0. The short version

- **A *Maths* switch in the Write panel.** Off, nothing about writing changes. On, the text is maths
  notation (§2): `a/b` is a stacked fraction, `√`, `∛` and `root(n, x)` are radicals with their bar
  over the radicand, `^` and `_` raise and lower, `|x|` is drawn bars, and brackets stretch to what
  they hold.
- **Eight keys** under the field while it is on, the calculator's templates as text: `a/b`, `√`,
  `∛`, `ⁿ√`, `xⁿ`, `x₁`, `( )`, `|x|`. Each inserts at the cursor, wrapping a selection.
- **Every run is written by the existing ladder** (your letters first, the model for the rest) and
  **read back by the gate** like any word. Layout then places the runs in two dimensions and draws
  the structure: the bar, the radical, the brackets.
- **The structure is drawn from your own marks where you have them**: your `-` stretched into the
  bar, your `(` stretched to height, your `√` carried on over its radicand. Where you have none, it is
  drawn in plain geometry in your slant. The network never draws a lone structural mark.
- **On your profile**, 19 of 20 test expressions wrote (§5.1). The quadratic formula is refused for
  `±`: neither your hand nor the v1 model has one.
- **Through the real gate (§5.4).** Since 2026-10-16 a run with a digit must be its first reading
  that holds a digit, so a wrong number is refused. On your pulled profile:
  - 56 of 60 expressions are written (the quadratic aside), as many as under the old rule;
  - 44 of 63 come out first time, against 45 under the old rule.

  The first version held digits to the *top* reading alone. That wrote only 35 first time, because
  your lone `1` reads as `l` and your lone `5` as `s`.
- **The symbols come from you (§2.4).** No recogniser can read `± × ÷ √ π ≤ ≥ ≠ ≈ θ Δ °`, so the
  network can never be trusted to draw one. The quick start's eighth prompt asks you to write them
  once, and from then on a word holding one is built from your own copies only, never read back.
  Eleven more keys type them.

## 1. Why layout and not a model

Everything before this wrote on one line. A stacked fraction, a radical's bar, a cube root's index,
an exponent and a bracket as tall as a fraction are positions, not characters.

- The network writes along a line, one character after the next. Teaching it two-dimensional maths
  needs two-dimensional handwritten maths to train on. The one public corpus (MathWriting) is not
  licensed for commercial use (D13).
- `:core:calc` already models this: its textbook display has `Expr` runs with `Template`s
  (`FRACTION`, `MIXED_FRACTION`, `SQRT`, `CBRT`, `NTH_ROOT`, `POWER`, `ABS`) and a box layout
  (`FractionBox`, `RadicalBox`). Both are `internal`, the model is an editor's (cursor paths into
  slots), and the boxes are in LCD pixels. So the **design and the template set are shared, not the
  code**: `MathNode` restates the same shapes in x-heights.

## 2. The notation, the switch and the keys

### 2.1 What is read as what (`MathNotation`)

| Typed | Written |
|---|---|
| `a/b`, `(x+1)/(2x)` | a stacked fraction; brackets round a whole numerator or denominator only group it |
| `2x/3`, `x+1/2` | the numerator is the term before `/`, back to a sign that joins terms or a space |
| `2 3/4` | a mixed number: no word gap between the whole number and its fraction |
| `√x`, `√(x+1)`, `sqrt(x)` | a square root |
| `∛x`, `cbrt(x)`, `∜x`, `root(n, x)` | a cube, fourth or nth root, the index in the notch |
| `x^2`, `x^(n+1)`, `e^-x` | raised; a script is a number or one letter, so `x^2y` is x²·y |
| `x_1`, `CO_2`, `x_1^2` | lowered, and both |
| `(…)`, `[…]`, `{…}` | written as letters when they hold one line; drawn and stretched when taller |
| `|x|`, `abs(x)` | bars, always drawn |
| `÷` | a sign on the line |

- **A space inside brackets does not end a word** (`MathNotation.words`), so
  `(−b ± √(b^2−4ac))/(2a)` is one expression. Spaces outside brackets still separate words, and a line
  still wraps between them.
- **A token with nothing two-dimensional in it is an ordinary word**, written exactly as with the
  switch off. `MathsWritingTest` asserts the strokes are identical.
- **Half a construction is text**: `/2`, `x^`, `√` alone are written as typed.

### 2.2 The keys

Two rows of four, so a 320 dp panel holds them with nothing hidden behind a scroll (M10's portrait
lesson). A key types its template at the cursor and wraps a selection: `√` over a selected `2x+1`
gives `√(2x+1)`. Under them, two rows of the quick start's symbols (§2.4), `± × ÷ π θ Δ` and
`≤ ≥ ≠ ≈ °`, each typing itself; `√` is already a template key. Every key is a `GLYPH` chip, set as
typed: a word chip upper-cases, which turned `π` into `Π`, `θ` into `Θ` and `xⁿ` into `Xⁿ`.
`MathsKeyTest`, 5 tests.

### 2.3 The record

A phrase written with the switch on carries `m=1` in its record's header (D3). That is an unknown key
to an older build, which keeps it and writes it back. *Write again* writes the phrase as maths again,
and *Edit text* opens the panel with the switch on (§5.3). An older build's *Write again* writes the
text on one line: a degrade, never a fault.

### 2.4 The symbols prompt (`QuickStart.PROMPTS[7]`)

The user's request (2026-10-15): *"Add a quick-start prompt of maths symbols (± × ÷ √ π ≤ ≥ ≠ ≈ θ Δ °)
so my own copies can be used."*

**Why it has to be the user's copy.** ML Kit cannot output any of these characters. On `Test_Tablet`,
0 of 144 samples of twelve symbol formulas passed (docs/HANDWRITING-SYNTHESIS.md §20.6.4): `×` read as
`x`, `÷` as `:`, `√` as `V`, `≤` as `<`, `≥` as `Z`, `≠` as `F`, `±` as `t`, `π` as `t` or `17`, `θ`
as `0`, `Δ` as `A`, and `°` and `≈` vanished. So no reading can confirm a symbol, the network's or
anyone's, and v2 drawing a `±` does not help (§7).

What was built:

- **An eighth prompt**, `± × ÷ √ π ≤ ≥ ≠ ≈ θ Δ °`, appended after the seven, so the prompts a profile
  already recorded keep their indices. Each symbol becomes a sample labelled by the prompt
  (`CONFIRMED`), which is what makes its unit **vouched**.
- **`GateCalibration.UNREADABLE`** is `±×÷√π≤≥≠≈θΔμ°`. A token holding one is not judgeable, so it
  takes the strict route every unreadable class takes (§8.2 of the synthesis doc): built only from the
  user's verified or vouched units, never by the network, never read back. Before this, the symbol was
  handed to the gate, which refused every candidate, so the answer was the same refusal reached the
  expensive way. `μ` is in the set and not in the prompt, because the user did not ask for it.
- **The maths layout's own marks can come from it.** `MathWords.glyphsOf` now takes a vouched word
  unit as well as a cut letter, so the radical is drawn from the prompt's `√` (§3.4).
- **The one-line check ignores marks.** The quick start refuses a prompt not written on one line, and
  `LineSegmenter` split a line of symbols into 18 "lines": every bar of `=`, `≠` and `≈`, every dot of
  `÷` and the `°` sit beside the line rather than on it. A "line" under 0.4 of the tallest one's height
  is a mark now (`QuickStart.MARK_FRACTION`). A prompt truly written on two lines is still refused;
  there had been no test for that, and there is now.
- ⛔ **Stray ink had been measured in the wrong units, and it was dropping clean words.**
  `GlyphIndex.carriesStrayInk` refuses a word whose ink makes more clusters than it has letters (the
  tablet's `a` that arrived with its `)`). It clustered in the normalised frame, in x-heights, while
  `GlyphClusters`' constants are in **points**: its 0.25 pt minimum width became 0.25 x-heights, about
  1.4 pt on the page. A stroke narrower than that could not join the stroke it sat under. A straight `t`
  stem under its bar, and both legs of a `π` under theirs, were each a cluster of their own. The
  prompt's `π` was dropped as stray ink, which is how it was found. It now clusters at a writing size,
  5.5 pt (`CLUSTER_X_HEIGHT_PT`). On the tablet's pulled profile:

  | | Before | After |
  |---|---|---|
  | Letter units | 348 | 358 |
  | Words refused as stray ink | 30 | 16 |

  The index's stray-ink list under each clustering, diffed: the fix gives back 14 words and refuses
  none it did not refuse before. The 14 are clean when drawn: `+3`, `B`, `DE`, `DOTS`, `FIVE`, `JUMP`,
  `N.B`, `Spreads`, `big`, `fine.`, `output`, `sphinx`, `st`, `to`. The 16 still refused were not
  re-examined.
  `FrameCheckTest` asserts a `t` and a `π` with a hand's lean on each stem; it fails with the old
  clustering and passes with the new.
- **The ready line says what was checked.** The panel said *"Every word was read back"* for any
  complete phrase, which was already untrue of a strict-route word and became untrue of every symbol.
  It reads each word's `readBack` now (`checkedLine`): *"Built from your own copies: nothing here can be
  read back"*, or *"Every word was read back but one, built from your own copies"*. Found on the walk.

**The walk** (`Test_Tablet`, debug build, the emulator's own 19-word profile, 2026-10-15):

1. The quick start reads *"1 of 8"*; *Skip* seven times reaches *"8 of 8"* and the symbols.
2. The twelve symbols drawn with `input stylus motionevent`, in the shapes `SymbolsPromptTest` uses.
   *Next*: *"Done: 12 words recorded."* The profile went from 19 to 31 words, and the coverage grid
   shows `± 1` and `Δ 1`. The pulled shard holds every symbol's label and 12 quick-start samples.
3. A scratch note at the library root, Write panel, Maths on. The symbol keys show as typed (`π`,
   `θ`, `xⁿ`, no capitals). `2`, the `≤` key, `3` gives `2≤3`, and the preview logs
   `complete=true neural=0 refused=[]`.
4. A tap drops it: `wrote: words=1 strokes=4 … netStrokes=0 neural=0`. Four strokes, which is the
   `2`, both strokes of the `≤` and the `3`, all from the profile.
5. The `≤3` looked like one blob on screen. Measured rather than judged (the emulator's ink surface is
   not to be trusted on looks): written from the pulled profile at the walk's 6.43 pt x-height, the
   `2` spans 0.7–15.9 pt, the `≤` 17.2–22.5 and the `3` 23.7–28.9, over four seeds. They do not
   overlap. The pen was a 3.15 pt fountain pen, which spreads 1.6 pt either side of its line and fills
   1.3 pt gaps. That is a pen too wide for the size, not the layout.
6. The scratch note went to the trash. The symbol samples stay in the emulator's profile.

⚠️ **Walk trap:** the soft keyboard covers the panel's lower keys, and a `uiautomator dump` still lists
them at the bounds they have underneath it, so a tap aimed at `≤` opened Gboard's settings, twice. Back
dismisses the keyboard; check `dumpsys input_method | grep mInputShown` first, because a Back with no
keyboard up leaves the note.

## 3. The layout (`MathWords`, `MathLayout`)

### 3.1 Runs through the ladder

`MathWords.of` turns each maths token into its runs, in writing order (`MathNotation.texts`):
numerator then denominator, index then radicand, base then script. The synthesizer writes and reads
them exactly as it writes words:
- assembly first, the network for what the user has never written;
- every run read back by the gate, all of a phrase's runs in one recogniser call;
- a run of a class the recogniser cannot judge built strictly (§8.2).

An expression is refused if any of its runs is, saying which letters are missing across all of them.

### 3.2 One frame

Each run is taken back to the normalised frame (x-heights, y up, slant removed), laid out there, and
the whole expression is put on the page once. So the bar, the radical and the brackets lean with the
hand's slant like the letters. Runs are only translated and uniformly scaled: **what the gate read is
what is drawn**.

### 3.3 Boxes, measured by ink

A box's height is its ink's, not a font's, so a numerator with a descender sits higher over the bar,
as it does on paper. In x-heights:

| What | Value |
|---|---|
| Fraction parts, per level | × 0.88 |
| Scripts | × 0.62 |
| A root's index | × 0.55 (never under 0.4) |
| The bar | on the maths axis, 0.5; 0.22 clear of each part; 0.14 past the wider |
| An exponent's foot | 0.32 below the top of what it follows, never under 0.68 |
| A subscript's baseline | 0.3 below, more under a descender |
| A radical's bar | 0.22 above the radicand's ink; running 0.1 past its end |
| Brackets | at least 1.3 up and 0.25 down, 0.12 beyond the content |

The exponent's numbers were raised once, on the sheet (§5.2).

### 3.4 Structural marks

- **The bar** is the user's own `-`, stretched along it: its slope is removed and its wobble kept,
  clamped to ±0.05. With no `-`, a line with a sag under 0.03.
- **The radical** is the user's `√` fitted to height, its width growing at most 1.4×, then carried on
  as one stroke along the top. With no `√`, a tick, a vee and a rising stroke, the same one stroke.
- **Brackets and bars** are the user's own, stretched: they grow much taller than wider (+25 % width
  per unit of stretch). With none, they are drawn: an arc, a square bracket, a Catmull-Rom brace, a
  line.
- **Never the network.** A lone bracket is nothing a recogniser can check, and a stretched one is
  geometry whoever drew the original.

### 3.5 Tags, the clock and the channels

- **Each stroke keeps its own `hw` tag** (`WordOutcome.Written.strokeTags`). A run's strokes say
  which backend wrote them, and drawn marks are `a` with no model.
  - ⚠️ `hw` has two backends, and `GeneratedInk.decode` refuses a third letter. A new letter would
    make drawn marks unreadable as generated ink.
  - Selection grouping reads the tag, so those strokes would also fall out of the phrase. The learner
    alone tests only for the key.
- **One clock.** Written runs keep their timing. Drawn marks are timed at the hand's own speed,
  measured from the runs, so the fountain pen's speed-driven width matches. A pen-up between parts.
- **Every channel on every sample.** Pressure and tilt are on all samples or none (`androidx.ink`
  throws otherwise). A drawn mark borrows the median of the runs'.

### 3.6 Tall lines

A fraction reaches about 1.6 x-heights up and 0.6 down. Where a line of maths would reach into its
neighbour, the following lines move down by **whole line pitches**, so ruled paper stays on its
ruling. Only lines holding maths are looked at. `MathsWritingTest` checks both halves.

## 4. What the gate can and cannot judge

- **It reads each run, on its own line**, with the rule calibrated for that run's class (§20.2). A run
  of a class it cannot judge is built strictly from verified letters, as any token is.
- **It cannot read the structure.** ML Kit reads a line, and a stacked fraction is not one. The
  structure is correct by construction: the parser decides what is over what, and the layout puts it
  there.
- A lone symbol run the recogniser can read (`=`, `+`) is held to the **default** rule, since
  symbols are too few on this hand to calibrate (§20.2.2). One it cannot read (`±`, `≤`, `π`, §2.4)
  is not judged at all: it is the user's own copy or nothing.
- **A run with a digit is judged on its first reading that holds a digit** (docs/HANDWRITING-SYNTHESIS.md
  §21.4.1–§21.4.2, the user's decisions on 2026-10-16).
  - Under the old rule, *"any of the five"*, H6 measured a wrong digit passing 5 times in 24 and a
    gained decimal point 6 times in 10. A fraction's `3` over `8` is two number runs, so it inherited
    that rate exactly. Now both are refused.
  - Readings with no digit are skipped first, so a lone `1` read as `l` still passes (§5.4).

## 5. What was measured

### 5.1 The sheet (`MathsLayoutReport`, the tablet's pulled profile, the shipped v1 model, java.awt)

20 expressions, written through the real synthesizer with the switch on, with a reader that passes
every run (ML Kit does not run on the JVM). The model's strokes are drawn blue.

- **19 of 20 written.** `x = (−b ± √(b²−4ac))/(2a)` is refused, `MissingLetters([±])`: neither the
  hand nor v1 has a `±`. v2 draws one, but no recogniser can read it back (§20.6.4).
- **From the user's own letters:** every fraction, the mixed number, `√2`, `√(x+1)`, `∛27`,
  `root(5, x)`, `√(a²+b²)`, `x²`, `xⁿ⁺¹`, `e⁻ˣ`, `a₁`, `CO₂`, `|x−3|`, `(a/b)²` and `∛(8/27)`. Every bar
  is the user's `-`, stretched.
- **The network wrote** only the `=` in the two equations. v1's `=` sits above the axis, so it does
  not line up with a fraction's bar.

### 5.2 Found on the sheet, and fixed

1. **A root's index and radicand were swapped.** `∛27` came out as `27∛3`. The layout read the
   radicand before the index, while the runs are in writing order (index first).
   - `MathLayoutTest` had not caught it: its pieces were the same shape.
   - It now uses pieces of different widths, and goes red against the old order (*"the 3 is in the
     notch"*).
2. **An exponent came out as a dot.** One of the profile's fifteen `2`s is 0.55 x-heights tall,
   raised 0.28: an exponent cut from the notes, kept as a single-letter digit.
   - `LetterFit.isSubscript` checks only how high a digit's top reaches, and an exponent's top is
     where a digit's belongs.
   - Every other digit on the profile stands 0.94–2.07 tall. `LetterFit.isScript` keeps a digit under
     0.7 out of single letters.
   - This mattered for ordinary numbers too: assembly could have used that `2` in a phone number.
   - `NumbersTest` has the case, and it goes red with the rule off.
3. **Exponents sat too low.** At 0.42 below the base's top, with a floor of 0.5, `e⁻ˣ` read as `e-x`
   on one line. Now 0.32 and 0.68.

### 5.3 The walk (`Test_Tablet`, debug build, its 19-word profile, a scratch note at the library root)

- **The switch and keys:** *Maths* on showed the eight keys. `a/b` inserted `()/()` with the cursor
  in the numerator, and typing filled it: `(x+1)/(2)`.
- **The preview** logged `maths=true mathsWords=1 complete=true`, every run read back by **real
  ML Kit**. It drew a stacked fraction.
- **The drop:** `maths=true mathsWords=1`, 12 strokes in one group, 10 tagged `:n:a6a191cb` and 2
  `:a:-` (read off the note's journal). This profile has no `x`, so the network wrote the runs.
- **The record** reads `hw1;…;n=0.5;m=1` and then `(x+1)/(2)`.
- **Write again:** `maths=true mathsWords=1 replaced=12`. It took the switch from the record.
- **Edit text:** with the panel's switch turned **off** first, opening the phrase turned it back on
  (the keys reappeared). The field held `(x+1)/(2)`. The `√` key and `x+1` made `√(x+1)`, and *Done*
  wrote `maths=true replaced=12`: 10 strokes, the drawn radical among them.
- **The page,** through `androidx.ink`, shows `√(x+1)` with its bar over the radicand.
- The scratch note went to the local trash (`files/trash/…-01a13f04…`).

### 5.4 Through the real gate (2026-10-16)

On 2026-10-16 the user decided that a run with a digit is held to something stricter than *"any of
ML Kit's five readings"* (docs/HANDWRITING-SYNTHESIS.md §21.4.1–§21.4.2). In this notation most runs
have a digit: a numerator and a denominator, a root's index, an exponent. §5.1's sheet trusted every
run, and §5.3's walk read two expressions under the old rule. So this is the first measurement of
maths through the real gate. It was made twice that day:
- first under the **top reading** rule (§21.4.1);
- then under the rule that replaced it, the **first reading that holds a digit** (§21.4.2), which this
  measurement is what argued for.

**The instrument.** `MathsGateReceiver` (debug) does the following:
- It writes the sheet's 20 expressions and the walk's `(x+1)/(2)` with the switch on, three seeds
  each, through the production synthesizer and `MlKitInkReader`.
- It records every reading on its way through the gate.
- It names each reading's run by its pre-context, from a plan `MathsLayoutReport` writes on the JVM
  (`maths-plan.json`: each expression's runs). All 213 and 288 readings per pass were named.

So each run's first reading is scored under all three rules on the same ink:
- the first reading that holds a digit (the gate now);
- the top reading alone (§21.4.1);
- any of the five (the old rule).

It ran on `Test_Tablet` (debug build, airplane mode) with two profiles:
- **the tablet's**, pulled on 2026-10-14: 196 words, 852 units, no corrections. It used the tablet's
  calibrated rules (words `PUNCTUATION`, numbers and formulas `CASE_ALIKE`) and the shipped v1 model
  fitted to it. This is the profile §5.1 drew from. It was deleted from the emulator after each run.
- **the emulator's own**: 31 words, 69 units, no `x`. This is the profile §5.3 walked with.

The counts were identical at 9 pt and at the panel's 6.43 pt.

| 21 expressions × 3 seeds | Tablet's profile | Emulator's profile |
|---|---|---|
| Written, the gate now | **56 of 63** | **53 of 63** |
| Written, top reading alone | 55 | 53 |
| Written first time: now / top reading alone / any of five | **44** / 35 / 45 | **36** / 34 / 37 |
| Runs with a digit, first reading passes: now / top reading / any of five | **87** / 78 / 90 of 108 | **89** / 87 / 91 of 108 |
| Runs without a digit | 54 of 54 under all three | 40 of 54 under all three |
| Refused, the gate now | `√(x+1)` ×2, `x^(n+1)` ×2, and the quadratic ×3 for `±` | `√(a^2+b^2)` ×3, `x^(n+1)` ×3, `\|x-3\|` ×1, and the quadratic ×3 |

"Written" and the first-time count under the gate come from the gate's own pass. The top-reading row
comes from that rule's own pass, made first. "Any of five" first-time is estimated from each run's
first reading.

**What the top-reading rule cost on the tablet's profile, and what the gate now gives back.** Under
the top-reading rule, 11 first readings were refused that the old rule passed:
- **a lone `1`, 6 times** (`1/2`, `1/2 + 3/4`, `a_1`). It reads as `[l, (, I, i, 1]`: with nothing
  beside it, an upright stroke is an `l`;
- **a lone `5`, 3 times** (`root(5, x)`'s index). It reads as `[s, S, 5, g, 9]`;
- **a low minus, twice.** `x-3` reads as `[x_3, X_3, X-3, …]`, and `-4ac` as `_4ac`.

The retries usually got there, but late and often in the model's hand. `1/2` passed on the 4th or 5th
try. `a_1` and `root(5, x)` passed on the 5th try with the model's `1` and `5`, or not at all.

Under the gate now, the `1`s and `5`s pass where the old rule passed them:
- `1/2`, `1/2 + 3/4` and `root(5, x)` are written first time in all three seeds, entirely from the
  user's own letters (`net 0`).
- Only the low minus is still refused where the old rule passed it. Its first reading with a digit,
  `x_3` or `_4ac`, is not the label. That costs one first-time write in 63.
- `a_1`'s subscript `1` sometimes reads `[l, l, (, i, I]`, with no digit at all. That is refused under
  every rule, and `a_1` took 2 to 5 tries.
- The other refusals failed every reading under every rule. `√(x+1)` failed as `++1` and `it1`, and
  `x^(n+1)` as `Att` and `int`: this hand's `+` reads as a `t`.

**On the emulator's profile,** the gate refused 2 first readings the old rule passed: `x+1` and `x-3`
runs the model wrote (this profile has no `x`). One `x-3` read as `[7-3, 1-3, X-3, …]`. The old rule
would have drawn ink read first as `7-3` for `x-3`, which is exactly the substitution D9 exists to stop.

**Through the Write panel** (`Test_Tablet`, its own profile, a scratch note at the library root each
time, both now in the local trash):
- **Under the top-reading build:** `(x+1)/(2)` with Maths on. The preview logged
  `complete=true maths=true … refused=[]`, and the panel said *"Every word was read back"*. A stylus
  tap dropped it: `wrote: … maths=true attempts=1`, 11 strokes. *Write again* gave
  `maths=true attempts=3 replaced=11`. `sqrt(x+1)` was refused in the preview, *"can't write 'x' '+'
  '1' yet"*.
- **Under the gate now:** the final preview of `(x+1)/(2)` was refused the same way, while a stylus tap
  wrote it: `wrote: … maths=true attempts=1`, 7 strokes. A preview and a drop are separate writes, each
  with its own draw.
- The model's `x+1` is this profile's weak run under every rule (above). The panel logs no readings,
  so a single refused preview cannot be put down to a rule. The receiver's three seeds are the
  measurement.

## 6. Per claim

| Claim | Automated | Real dependency | Device |
|---|---|---|---|
| The notation is read as §2.1 says | ✅ `MathNotationTest` (11) | — | ✅ `Test_Tablet`: the keys' text parsed as maths |
| Fractions, roots, scripts and fences laid out | ✅ `MathLayoutTest` (8); the root order checked red | ✅ The sheet from the user's profile and v1 | ✅ `Test_Tablet`: a stacked fraction in the preview, `√(x+1)` on the page |
| Each run written by the ladder and read back | ✅ `MathsWritingTest` (6) | ✅ Real ML Kit on `Test_Tablet` (`complete=true`); 21 expressions × 3 seeds × 2 profiles through `MathsGateReceiver` (§5.4) | ✅ `Test_Tablet`: §5.3, and `(x+1)/(2)` dropped under both of 2026-10-16's rules (§5.4) |
| A run with a digit is judged on its first reading that holds a digit | ✅ `MathsWritingTest` (its trusting reader puts the digit run before `Nix3`), `GateCalibrationTest`, `HandwritingSynthesizerTest` | ✅ §5.4, the tablet's pulled profile: digit runs 87/108 against 78 at the top and 90 among five; 56 of 60 expressions written, 44 of 63 first time | ✅ `Test_Tablet`, through the panel. The Galaxy Tab runs the rule and calibrated under it (numbers 13/15, formulas 21/24; docs/HANDWRITING-SYNTHESIS.md §21.4.2). ❌ No maths was written on the tab |
| The receiver's plan names each run | ✅ `MathsLayoutReport` writes `maths-plan.json`, asserting no run holds a space | — | ✅ Every reading in §5.4 was named; none was unresolved |
| Switch off is exactly as before | ✅ `MathsWritingTest`: identical strokes | — | — |
| Refusal names the missing letters | ✅ `MathsWritingTest` | ✅ The sheet: `±` | — |
| Drawn marks tagged `a`, runs by backend | ✅ `MathLayoutTest`, `MathsWritingTest` | — | ✅ `Test_Tablet`: 10 `:n:` + 2 `:a:-`, one group |
| Tall lines keep to whole pitches | ✅ `MathsWritingTest` | — | — |
| An exponent digit is not a letter unit | ✅ `NumbersTest`, checked red | ✅ The tablet's profile: one `2` at 0.55 | — |
| Keys insert at the cursor | ✅ `MathsKeyTest` | — | ✅ `Test_Tablet`: `a/b` and `√`; `≤` |
| The symbols prompt records twelve vouched copies | ✅ `SymbolsPromptTest` (6) | — | ✅ `Test_Tablet`: *"12 words recorded"*, labels in the shard |
| A symbol is built from the user's copy, never read back, never the network | ✅ `SymbolsPromptTest` | — | ✅ `Test_Tablet`: `2≤3`, `netStrokes=0`, `neural=0` |
| Without the prompt, a symbol is refused by name | ✅ `SymbolsPromptTest` | ✅ The sheet: `±` | — |
| A line of symbols is one line | ✅ `QuickStartTest`; two lines still refused | — | ✅ `Test_Tablet` |
| Stray ink clustered at writing size | ✅ `FrameCheckTest`, checked red | ✅ The tablet's profile: 14 words given back, none newly refused | — |
| Coverage counts usable letters | ✅ `RecutTest`, checked red | — | — |
| The ready line says what was read back | ✅ `MathsKeyTest` (`checkedLine`) | — | ✅ `Test_Tablet` |
| Write again and Edit text keep maths | — | — | ✅ `Test_Tablet`: `replaced=12 maths=true`; switch restored from the record |
| Platform-free; the iOS target | ✅ `checkCoreIsPlatformFree`; iOS klib compiles, main and test | ❌ The framework link is the macOS CI job's | — |
| On the Galaxy Tab | — | — | ❌ Unplugged when the layout walk was due. For the symbols, writing the prompt is the user's step: drawing test symbols into its quick start would put test ink in their profile |

**Runs** (2026-10-15, every test task with `--rerun --no-build-cache`):
- `:core:handwriting:testDebugUnitTest` 248, none failed (240 before the symbols prompt);
- `:app:testDebugUnitTest --tests '*MathsKeyTest'` 6/6;
- `spotlessCheck` (core and app), `detekt` and `checkCoreIsPlatformFree` green;
- `compileKotlinIosSimulatorArm64` and `compileTestKotlinIosSimulatorArm64` green;
- `:app:assembleDebug` green.

Not run: the whole of `:app:testReleaseUnitTest`, and `./gradlew build` as a whole (red for
§16.4's reasons).

**Runs** (2026-10-16, §5.4, every test task with `--rerun --no-build-cache`):
- `:core:handwriting:testDebugUnitTest` 254, none failed. 253 before; the new one is
  `MathsLayoutReport`'s plan test.
- `:core:handwriting:detekt` and `spotlessCheck` green; `:app:spotlessKotlinCheck` green.
- `:app:detekt` is red on one line, `HandwritingScreen.kt:440` (H5's), and nothing else.
- `:app:assembleDebug` green.
- `MathsGateReceiver` on `Test_Tablet`, 21 expressions × 3 seeds:
  - each profile at 9 pt and at 6.43 pt, the same counts each time;
  - the tablet's profile once more after the receiver's last change, with unchanged counts;
  - 213 and 288 readings per profile, every one named by its run.
- The panel walk on the same emulator.

**Runs** (2026-10-16, after the user adopted the first-reading-with-a-digit rule):
- `:core:handwriting:testDebugUnitTest` 255, none failed; `:app:testReleaseUnitTest` 433, none failed.
- `:core:handwriting:detekt`, `spotlessCheck` and the iOS klib compiles (main and test) green;
  `checkCoreIsPlatformFree` green; `:app:spotlessKotlinCheck` green.
- `:app:detekt` red on `HandwritingScreen.kt:440` alone (H5's).
- `GateMlKitTest` 9/9 on `Test_Tablet` (cold-booted).
- `MathsGateReceiver` on both profiles, 21 × 3 each, scoring all three rules. The tablet's profile was
  also calibrated, then deleted from the emulator.
- The panel walk again: `(x+1)/(2)` dropped, `attempts=1`. The scratch note is in the local trash
  (`…-01a143cc…`).

## 7. Found, not fixed

- ~~**On the user's hand, a lone `1` reads as `l` and a lone `5` as `s`, so maths often goes to a
  retry.**~~ This was true under the top-reading rule. **Fixed by the rule the user adopted on
  2026-10-16** (§5.4): readings with no digit are skipped, so `1/2` and `root(5, x)` are written first
  time in the user's own hand.
- **A low minus reads as an underscore** (`x_3`, `_4ac`, on the tablet's profile), and is refused at
  first reading where the old rule passed it. It costs one first-time write in 63.
- **`a_1`'s subscript `1` sometimes reads with no digit at all** (`[l, l, (, i, I]`). That is refused
  under every rule, and it takes 2 to 5 tries.
- **This hand's `+` reads as a `t`** (`xt1`, `nti`), so `√(x+1)` and `x^(n+1)` are sometimes refused
  under every rule. The symbols prompt (§2.4) does not ask for a `+`.
- **The panel logs no readings.** A refused maths preview cannot say which run refused it or what that
  run read as. `MathsGateReceiver` can, for the same expression.
- **A `±` only once the user has written one.** The symbols prompt (§2.4) is the way; until it is
  recorded, the quadratic formula is refused for `±` by name. **v2 does not change this.** It draws a
  `±`, but ML Kit cannot read any maths symbol, so nothing could confirm the network's
  (docs/HANDWRITING-SYNTHESIS.md §20.6.4). v2 is not shipped.
- **One copy of each symbol.** The prompt is written once, so every `≤` on a page is the same `≤`.
  Writing the prompt again (the quick start's *Again*) adds a second copy of each.
- **v1's `=` floats above the axis**, so `y = (x+1)/(2x)` does not line up. That is the model, not the
  layout. The user's own `=` would sit right, but the profile's one `=` is from a misframed word
  (§20.6.1).
- **The scales are fixed** (0.88, 0.62, 0.55), not measured from the user's own exponents and
  fractions. The profile has examples of both; learning them would be the next refinement.
- **`adb shell input text` cannot type `√` or any symbol** (non-ASCII). The keys and `sqrt(` cover
  it. Noted for device walks.
- **Not walked on the Galaxy Tab.**
- **Emulator trap, found on the walk:** `screencap`, and the emulator console's own screenshot,
  returned a **frozen frame** for minutes, the status-bar clock stuck at 10:14 while the device's was
  12:07. The accessibility tree was live throughout. A power-key cycle (`input keyevent 26` twice)
  made it draw again. Check the clock in a screenshot before believing what it shows.

## 8. Decisions

1. **A switch, not detection.** `/` is everywhere in prose (`and/or`, `km/h`, `15/10/2026`). Reading
   it as a fraction unasked would turn a date into nested fractions. Off is H5, exactly.
2. **Brackets that hold one line are written as letters.** `(x+1)²` is the user's own `(x+1)`. Only
   brackets round something taller are drawn.
3. **Bars are always drawn.** Few hands have written a `|` on its own.
4. **Structure from the user's marks first, geometry second, never the network** (§3.4).
5. **Drawn marks are tagged `a`** (§3.5).
6. **Runs are read, structure is constructed** (§4).
7. **Tall lines move by whole pitches,** and only lines with maths (§3.6).
8. **The calculator's template set, re-stated rather than shared** (§1). Writing a calculator result
   onto the page would need its `Expr` public, and a converter in `:app`. Not built.
