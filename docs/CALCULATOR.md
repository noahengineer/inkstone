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
| D6 | Insert result into the note | **Not in this build.** | Not asked for; the PDF font cannot carry ⁻ ∛ ∠ (research 08 §5.2), so it needs its own decisions. |
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
