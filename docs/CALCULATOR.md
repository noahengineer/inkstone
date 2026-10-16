# The built-in calculator — an fx-991ZA PLUS II, rebuilt from scratch

The user asked for a calculator inside Inkstone that is *"just like the fx-991ZA PLUS II in terms
of looks and function and everything"*, in *"some drop down or movable window"*, by emulation or
from scratch. This file is the decisions record. The facts it rests on are in
`docs/calculator/research/` (start at `00-index.md`); this file says what was **decided** where the
facts ran out, and how the build is organised.

---

## 1. Emulate or rebuild? Rebuild.

Research 07 §3 and 08 §1.3, in one paragraph: the real calculator is a LAPIS nX-U8/100 running
Casio's firmware **CY-849**. No dump of that ROM is public; the community ROMs are extracted from
Casio's Windows emulator, whose licence forbids it; open-source nX-U8 cores are GPL C++ and need
that ROM; and this repo has no NDK. So the engine is written from scratch in pure Kotlin, in a new
platform-free module, `:core:calc`, and matched to the real one by the guide's worked examples
(research 04, 05) — about 170 of them — plus every rule the guide states.

Casio's official *fx-991ZA PLUS II Emulator* (Windows, 90-day trial) is the oracle for everything
the guide does not settle (research 07 §6, tests T1–T18). Calcupedia reports Casio ends its PC
emulators on **14 May 2027**. Running those tests is the single highest-value follow-up.

## 2. Decisions the research left to us

| # | Question | Decision | Why |
|---|---|---|---|
| D1 | Branding | **No CASIO wordmark, no model name, no copied artwork.** The face is drawn in-house to the measured geometry and colours; the logo strip carries a small `INKSTONE`. LCD glyphs are our own bitmaps on Casio's measured cell sizes (5×6 small, 5×9 large). | Trademarks and Casio's font/legend artwork are theirs (research 00 Tier 4). A 5×9 digit has little room to differ, so the screen still reads the same. |
| D2 | Where it opens | A `CALC` chip at `TopStart` in the editor (the one empty zone; no shipped control moves) and at `BottomStart` in the library. It opens a **non-modal, draggable window** hanging from the chip; drag it anywhere; it remembers where it was left. Back closes it. | Research 08 §3–4. The user's "drop down or movable window" — both. |
| D3 | One calculator or many | **One**, process-wide, like the one on the desk: the library's and every note's are the same object with the same Ans and memories. Open/closed is shared too. | Research 08 §4.9. |
| D4 | What survives a restart | Everything the real one keeps while switched off: mode, SETUP, A–F X Y M, Ans, PreAns, statistics data, matrices, vectors, TABLE/EQN/… settings. The input line does not. Window position too. | Real behaviour (research 02 §9). |
| D5 | Palm on the keypad | Accepted, as on a real calculator. Android's own palm rejection cancels a flagged palm. Revisit on the tablet. | Needs the tablet to judge (research 08 §4.4). |
| D6 | Insert result into the note | **Drag the answer off the LCD onto the page** (2026-10-16). It is written in the user's **own hand**, and becomes a **text box** when it cannot be; either way with a decimal point. See §6. | Asked for on 2026-10-16; the user chose *"Handwriting, else text"* and *"Point"*. |
| D7 | Size | Fit to the available height with the real 77 × 161.5 mm aspect locked; laid out in proportional units of the window width. | 1:1 only fits in portrait (research 08 §4.6). |

## 3. Unknowns, and the rule chosen for each

Each of these is a **guess about Casio's firmware**, written here so a later test against the
emulator knows what to check. Tests that pin a guess say so in their KDoc.

| Research ref | Unknown | Rule implemented |
|---|---|---|
| 07 T3/T4 | Rounding of stored values; underflow | Every operation rounds to **15 significant digits, half away from zero**. No guard digits are kept between operations. A result smaller than 1×10⁻⁹⁹ in magnitude becomes **0**; one of 1×10¹⁰⁰ or more is **Math ERROR**. |
| 07 T5/T6 | Display rounding; where the Norm threshold is tested | Half away from zero. The value is rounded to 10 significant digits **first**, and the Norm 1/2 range is tested on the rounded value. |
| 02 §8.5 | Do decimals become fractions? | **No, not by themselves.** A typed decimal (`0,5`) is a decimal; anything that mixes a decimal with a fraction is a decimal (guide, VERIFIED). Integer literals, `÷`, `FRAC`, x², x³, x⁻¹, √, ∛ and powers with integer exponents stay **exact** while they fit. `S⇔D` converts a decimal to a fraction on demand. |
| 07 T7 | Fraction limits | A fraction is **displayed** when integer + numerator + denominator + separators ≤ **10 digits** (guide's mixed-fraction rule, applied to both forms). It is **held** exactly while numerator digits + denominator digits ≤ 14 (the record's 15 nibbles); past that it becomes a decimal. |
| 07 T7 | Decimal → fraction on `S⇔D` | Simplest fraction (continued fractions) within the display limit whose value agrees with the decimal to within 5 × 10⁻¹⁵ relative. |
| 07 T10 | Surd closure | Values are carried as at most **two terms** `(c/q)·√r`, each with `c < 100`, `q < 100`, `r < 1000` squarefree. Anything that would need a third term, or a larger coefficient, becomes a decimal **at that step** and stays one (guide: an intermediate with three terms gives a decimal result). Display combines the two terms over `lcm(q₁, q₂)`. |
| 07 T8 | Exact trig | Angle-keyed: sin, cos, tan give exact values when the angle is a multiple of 15° (Deg), of π/12 (Rad, \|x\| < 20π) or of 50/3 grad (Gra, \|x\| < 10000), tested within 1×10⁻¹³ relative. Everything else is a decimal (so sin 18° is a decimal). |
| 07 T9/T16 | When π form is tried | Only when the expression contains `π`, or an inverse trig function, `Pol` or `arg` evaluated in **Rad** — the 2005 guide's rule, which both ZA examples satisfy. Then: \|x\| < 10⁶ and x/π is a fraction within the display limits, to 1×10⁻¹³ relative. |
| 07 T11 | Transcendental last digits | Computed at ≥ 25 digits and rounded to 15 (correctly rounded in practice). |
| 07 T13 | Normal CDF | STAT `P/Q/R` use the **ROM's** coefficient set (research 07 §4.8), because a faithful copy is the goal. DIST mode uses accurate algorithms (the guide promises only 5 digits there). |
| 07 T12/T15 | ∫, d/dx, SOLVE internals | Adaptive G7–K15, default tol 1×10⁻⁵, absolute-or-relative error test, a work budget ending in Time Out. d/dx: central differences with Richardson extrapolation, default tol 1×10⁻¹⁰. SOLVE: Newton (numeric derivative) from the variable's current value, 50 iterations then `Continue:[=]`. |
| 02 §15 | Bytes per token | The ES-family guide's printed Math-format table ([S570] p.E-15, found by the stage-1 review): fraction 9, mixed fraction 13, log▪▯ 6, 10^▪/e^▪ 4, √ 4, ∛ 9, x²/x³ 4, x⁻¹ 5, x^▪ 4, ⁿ√ 9, ∫ 8, d/dx 6, Σ 8, Abs 4, everything else 1. Its maximum, 13, matches the ZA guide's "3 to 13 bytes". The ■ cursor appears with ≤ 10 bytes left; at 99 nothing more is accepted. |

## 4. Architecture

```
:core:calc   (KMP, pure Kotlin, zero dependencies — iOS-linkable like every :core module)
  Key.kt          the 50 physical keys (contract)
  Setup.kt        Mode, SETUP items, CalcError (contract)
  Calculator.kt   the facade: press(key) → LcdFrame; snapshot()/restore()
  num/            Dec — 15-significant-digit decimal; DecMath — transcendentals
  input/          Tok, Expr/Template/Cursor (contract); Editor — keystroke editing, cursor, bytes
  parse/          Expr → AST, with the guide's 13-level priority table and error positions
  value/          Real (decimal | fraction | surd), Complex, matrices, vectors; exact arithmetic
  eval/           AST → value: every function, ∫ d/dx Σ SOLVE CALC, Pol/Rec, DMS, % ÷R …
  format/         value → display Expr (Norm/Fix/Sci/ENG, fraction, surd, π, DMS, a+bi, r∠θ, bases)
  lcd/            LcdFrame (contract); fonts; Natural/Linear layout; menus → 96×31 dots
  machine/        key dispatch: SHIFT/ALPHA, menus, SETUP, CLR, STO/RCL, history, errors, modes
  mode/           STAT, BASE-N, EQN, MATRIX, TABLE, VECTOR, DIST, INEQ, RATIO screens
:app  calculator/  window, face, keypad, LCD bitmap, controller singleton, persistence
```

Contracts (files marked *contract* above) were written first so the parts could be built in
parallel. One layout engine draws both LCD lines: the formatter turns a result into an `Expr`
(a fraction result is a `FRACTION` template) and the renderer draws `Expr`s.

## 5. Status

Per claim: **written** / **automated** (JVM tests) / **emulator** / **tablet**. Nothing below has
been compared against Casio's own emulator: every behaviour is checked against the guide's printed
examples and the rules in §3, and §3's rows are guesses until then.

| Part | Written | Automated | Emulator | Tablet |
|---|---|---|---|---|
| `num` — Dec (15-digit, half away, underflow → 0, overflow flagged) and DecMath (≥ 42-digit internals, ~820 independent reference values) | ✅ | ✅ | — | — |
| `input` — editing, templates, cursor, INS, DEL, capacity; `parse` — 13-level priority, implicit ×, stack limits, Goto positions | ✅ | ✅ incl. every key sequence in research 04/05 | — | — |
| `lcd` — fonts (our glyphs on Casio's grid), Natural/Linear layout, every screen type | ✅ | ✅ measured layouts reproduced from the SA captures | ✅ drawn in the walk below | ✅ (the user's screenshots) |
| `value` (exact fractions, two-term surds, special angles, complex, matrix, vector), `eval`, `format`, `machine` (keymap, menus, SETUP, CLR, memory, history, CALC, SOLVE, the mode extension point) | ✅ | ✅ each layer on its own | — | — |
| **End to end**: ~150 of the guide's printed examples (research 04 A–E and H, 05 CMPLX/BASE-N/RATIO/CONV) typed on the public `Calculator()` — `e2e/GuideCompExamplesTest`, `e2e/GuideModeExamplesTest`, `e2e/EverydayCalculationsTest` | ✅ | ✅ all match the printed results | ✅ `100 ÷ 1,80 =` typed on the app's keys, `55,55555556` read off the LCD | fix built 2026-10-11, not yet installed |
| Modes COMP, CMPLX, BASE-N, RATIO | ✅ | ✅ | — | — |
| Modes STAT, EQN, MATRIX, TABLE, VECTOR, DIST, INEQ | ❌ placeholder screen | — | — | — |
| `:app` window, face, keypad, LCD bitmap, controller, persistence, SETUP contrast, auto power-off | ✅ | ✅ 49 tests | ✅ opened from the library, keys tapped by content description | ✅ (the user's screenshots) |

⚠️ **2026-10-11: the tablet showed Math ERROR for `100 ÷ 1,80`, and every test was green.** The key
machine was built against a stand-in evaluator (integers only) while the real one was written in
parallel, and `Calculator()` — the constructor the app uses — still built it. Every machine test ran
on documented fakes through the *internal* constructor, so nothing exercised the public one. Fixed by
wiring `Evaluation`/`Formatting` and deleting the stand-ins; the end-to-end tests above use only the
public constructor, so that wiring is now under test.

## 6. Dragging an answer onto the page

*"After calculating something, the user can drag the answer onto the note page"* (2026-10-16). D6
was "not in this build"; this is what replaced it.

### 6.1 What happens

1. A result is on the glass. The **LCD itself** is the drag source: press on the glass and move past
   the touch slop. A tap does nothing (a real LCD has no tap either), and a press with no result
   showing is left to the keypad's own handler exactly as before.
2. A ghost of the answer follows the finger, in the text box's own face and size at the page's
   zoom, so it is the size it will land at (`AnswerGhost`; the Element ghost's rule).
3. Let go over the page and the answer is **written in the user's hand**, by the Write panel's own
   write (`PhraseWriter`) with *Maths* on, starting on the ghost's baseline and placed by D15's rules
   (docs/HANDWRITING-SYNTHESIS.md): it continues a paragraph it is dropped beside, sits on a rule, takes
   the page's size and pen. Like every generated phrase it is read back by ML Kit first.
4. **When the hand cannot write it** — no recognition model, nothing learned yet, a word the read-back
   gate refused, or the synthesizer failing — the answer becomes a **text box** in the ghost's place,
   and the snackbar says why (*"The answer went down as text — …"*). The rule is `AnswerLanding`.
5. Let go over the calculator window: nothing — *"never mind"*, the Element and Write panels' rule.
   Let go beside a fixed sheet: nothing, and *"Drop the answer on the paper."*
6. One undo removes it. Either way it is journaled, survives a reopen and syncs like any edit,
   because the only writes are the editor's own: `stampHandwriting` for the hand, and
   `insertTextBox` for the box (research 08 §5.1) — each one gesture under the editor's lock.

The library's calculator has no page, so its LCD is not a drag source at all (absent, not greyed).

### 6.2 Decisions

| # | Question | Decision | Why |
|---|---|---|---|
| A1 | What the answer becomes | **Handwriting, else a text box**: the user's hand when the profile, the model and the read-back gate allow it, and a text box holding the same words when they do not, with the reason in the snackbar. | The user's choice, 2026-10-16. A text box alone was the first build (it never fails); handwriting is what the rest of the page looks like. |
| A2 | Which text | `LcdFrame.answer`: the result the display shows, in the LCD's plain linear spelling (`55,55555556`, `1/200`, `2√(2)`, `5×10^-7`, `-1,5`). A two-part result is its two lines (`r=2` / `θ=45`); SOLVE's is `X=…` without `L-R=`. Null while typing, in menus, on errors and with the glass off. | The engine already wrote this text for TalkBack (`LcdFrame.text`); `answer` says which line of it is the result, set only where the machine renders one (`renderResult`, CALC/SOLVE). |
| A3 | Decimal comma | **A point on the page** (`55,5` → `55.5`), by hand or in a box. | The user's choice, 2026-10-16. The ZA PLUS II has no Dot/Comma setting (research 02 Z3/Z4), so the comma is the display's; an answer contains no other comma (values are separated by `;`, parts by a new line). |
| A4 | Characters the PDF drops | CMPLX's polar `∠` is written ` ang `; everything else an answer can show is in `LiberationSans-Regular`. | Research 08 §5.2 measured the embedded face. `AnswerPlacementTest` types answers on the real engine and holds them to that alphabet. |
| A5 | Where it lands | Centred on the finger. The frame hugs the text plus one em (it is also the wrap width, and the ghost is measured by Compose while the box is laid out by `StaticLayout`). On a fixed sheet a drop beside the paper is refused with a message, and one that would overhang an edge is slid onto the paper; an infinite page takes it anywhere, as it does ink. | SPEC §4.5: anything outside the sheet is drawn by the app and by no PDF reader. |
| A6 | After the drop | Nothing selected, the tool unchanged — handwriting included, although a phrase dropped from the Write panel is selected. | An answer is put down to go on writing beside it; an Element or a panel phrase is selected after a drop because working on it is what is done next. |
| A7 | The ghost | The answer in the text box's face and size, on a paper-coloured card. Handwriting lands at the hand's own size, its first baseline where the ghost's was. | Generating handwriting under a moving finger is the Write panel's preview, which costs a read-back per word; an answer is a few characters and the drop shows the real thing a moment later. |

### 6.3 Where it lives

| Part | File |
|---|---|
| The answer on the frame | `:core:calc` `lcd/LcdFrame.answer`, set in `lcd/Screens.calculation` / `stacked`, threaded through `machine/Machine.render` and `LineEditor.showWrap` |
| Drag source, drag state, ghost | `:app` `calculator/AnswerDrag.kt` (`Modifier.answerDragSource`, `AnswerDrag`, `AnswerGhost`) |
| Placement and the page's spelling | `calculator/AnswerPlacement.kt` (pure, JVM-tested) |
| Handwriting, else text | `calculator/AnswerLanding.kt` (pure, JVM-tested) |
| The writes | `calculator/CalculatorInsert.kt` (`rememberAnswerTarget`): handwriting through `handwriting/PhraseWriter.kt`, the text box through `NoteEditorViewModel.insertTextBox` |
| The shared write | `handwriting/PhraseWriter.kt` — the Write panel's write lifted out of its runner unchanged, so a calculator answer and a panel phrase are written by one function |
| Wiring | `CalculatorLayer(target = …)` → `CalculatorWindow` → `CalculatorFace` (the LCD); `InkScreen.DockedCalculator` passes the target, `LibraryScreen` passes none |

### 6.4 Status

Per claim, as in §5: **written** / **automated** (JVM tests) / **emulator** (`Test_Tablet`, debug
build, 2026-10-16, driven over `adb`; its profile is the emulator's small one, 31 words) /
**tablet**.

| Claim | Written | Automated | Emulator | Tablet |
|---|---|---|---|---|
| `LcdFrame.answer` is the result and only the result (null while typing, in a menu, on an error, with the glass off; a two-part result is two lines; SOLVE answers `X=`) | ✅ | ✅ `e2e/AnswerTest`, 5 tests on the public `Calculator()` | ✅ the LCD read `100÷1,80` / `55,55555556`; with *Syntax ERROR* on the glass a drag wrote nothing | — |
| The LCD is a drag source; a ghost follows the finger at landing size | ✅ | — the gesture has no JVM harness | ✅ `input swipe`, and `input motionevent` step by step with screenshots mid-drag | — |
| **Handwriting first**, with *Maths* on, placed by D15 | ✅ | ✅ `AnswerLandingTest`, 6 tests on plain functions | ✅ `55.55555556` (15 strokes, neural, 1.8 s, baseline snapped to the rule), `1/200` written **stacked** (10 strokes, 1.2 s), `2π` (4 strokes); the phrase record reads `m=1` and `55.55555556` | — |
| **Else a text box**, with the reason | ✅ | ✅ `AnswerLandingTest`: refused, no hand, a throw, cancellation, a page that takes neither | ✅ `3×10^12`: a text box holding `3×10^12`, and *"The answer went down as text — Not written: '3×10^12' can't write '1' '0' yet — write it a few times"* | — |
| A decimal point on the page | ✅ | ✅ `AnswerPlacementTest` | ✅ in the handwriting, the phrase record and the journal | — |
| Each drop is one gesture in the journal | ✅ | — | ✅ handwriting: one record per answer, an `add` and a `fmt` tag per stroke and one phrase `txt`; text box: `add` + `txt` together | — |
| It survives a reopen | ✅ | — | ✅ both kinds: back to the library (the thumbnail shows it), reopened, still there | — |
| One undo removes it | ✅ | — | ✅ a text box: its `del`; a handwritten `2π`: all four strokes went on one UNDO | — |
| The published PDF carries it | ✅ | ✅ the answers the engine shows are inside the embedded face's alphabet | ✅ read by readers that are not PdfBox: `1/200` as text at x 562–597 on the 612-pt page (poppler `pdftotext`, MuPDF); the handwriting as 25 `/Ink` annotations — 15 + 10, none from the undone `2π` (MuPDF) | — |
| Placement: centred on the finger; refused beside a fixed sheet; slid onto it at an edge; a grown canvas clamps to its own edge | ✅ | ✅ `AnswerPlacementTest`, 8 tests with the spelling | ✅ into the margin at 42 %: *"Drop the answer on the paper."* and no write; at the right edge: the box's right side at exactly 612 pt | — |
| Let go over the calculator: nothing, and the key under the finger is not pressed | ✅ | — | ✅ journal and LCD unchanged | — |
| The library's LCD is not a drag source | ✅ | — | ✅ no ghost, no effect | — |
| The Write panel still writes, through the shared `PhraseWriter` | ✅ | — | ✅ typed, tapped the page: written, selected, its phrase bar up — as before | — |
| It reaches Nextcloud | ✅ through the editor's ordinary edit path (CLAUDE.md rule 4) | — | — the AVD has no account | — |

### 6.5 Not done, and worth knowing

- **The walk ran on the build before a lint-only rewrite of the drag loop** (`answerDragSource`: the
  same gesture, written without the extra `break`/`continue` detekt refused). The final APK was
  installed on `Test_Tablet` but not walked, because another session was using the AVD by then.
- **Not on the tablet.** How the drag feels under an S Pen, whether a resting pen ever crosses the
  touch slop, and how often the user's own 155-word profile writes an answer rather than falling back
  are tablet questions. The emulator's profile is 31 words; it could not write `1` and `0` inside
  `3×10^12` though it wrote them in `55.55555556` with the network.
- **The handwriting's quality is the synthesizer's.** `55.55555556`'s last digits crowd each other
  and `2π`'s `π` is crude; both passed the read-back gate. Nothing here judges a written answer
  beyond that gate.
- A handwritten answer takes a second or two to appear after the drop (1.2–1.8 s on the emulator),
  with nothing on screen in between. The Write panel has a status line for this; the calculator has
  none.
- The ghost is drawn in the calculator's layer, so it passes **under** the chrome bars, as the window
  does; it is text, not a preview of the handwriting (A7).
- A dropped answer does not snap to the grid; an Element does (SPEC §4.9.7).
- Found on the emulator and fixed before it shipped: the ghost's dark text vanished over the dark
  margin beside the paper and over the calculator; it is drawn on a paper-coloured card now.
- Seen in passing and left alone: a tap at the centre of SHIFT's accessibility box (x 2144 on the
  emulator) appears not to have pressed it — twice `2 SHIFT ×10^x =` gave *Syntax ERROR* — while a tap
  on the drawn key (x 2163) did. Presses go through `CalculatorKeypad.keyAt`, not those boxes, so this
  is about `adb` walks and not about users; TalkBack presses through `onClick`. Not investigated.