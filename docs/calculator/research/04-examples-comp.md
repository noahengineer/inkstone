# fx-991ZA PLUS II: worked examples from the general and COMP chapters, as test vectors

This file transcribes **every** worked example in the general chapters (setup, input, basic calculations, memory) and the COMP
chapters (function calculations, CALC/SOLVE, constants, conversion, errors, FAQ) of the official guide. Each one is written as a test vector.

- **Source [G]:** CASIO *User's Guide fx-991ZA PLUS II* (2020), <https://www.casio.com/content/dam/casio/global/support/manuals/calculators/pdf/004-en/f/fx-991ZA_PLUSII_EN.pdf>.
  Page numbers are **printed** page numbers (PDF page = printed + 1).
- **Source [SA]:** Casio South-Africa teacher handout with real ZA PLUS II screen captures,
  <https://edumaterial.casio-intl.com/resource/contents/1368>. Its vectors are in section H and kept separate from the guide's.
- The rules behind these vectors are in [`02-manual-core-and-display.md`](02-manual-core-and-display.md).

## Conventions

**Starting state.** Unless a vector says otherwise, it starts from **DEFAULT**: the state after `SHIFT` `9` `1` `=`
(CLR → Setup → Yes) followed by `AC`. That is:

- COMP mode
- MthIO-MathO
- Deg
- Norm 2
- d/c
- a+bi
- STAT OFF
- TABLE f(x),g(x)

[G p.4] says all sample operations assume the initial default setup unless stated. Where the guide labels an example `(LineIO)`, the setup is DEFAULT
plus `SHIFT MODE 2` (LineIO). Vectors that depend on memory contents say so. A test harness should also clear memory
(`SHIFT 9 2 =`) before any vector that reads variables, Ans or PreAns, unless the vector continues from the previous one ("cont.").

**Key tokens** are written as in 02 §0:

- `FRAC` is the fraction-template key.
- `log□` is the log-with-base-slot key.
- `x^` is x^□.
- `,` is the **decimal comma** key.
- `(−)` is the negative sign and `−` is subtraction.
- A function reached through SHIFT or ALPHA is written with its label in parentheses, for example `SHIFT` `)` (;).

**Display notation.**

- `a/b` written as **stacked** means a Natural-Display built-up fraction: numerator a above denominator b.
- `⌟` is the LineIO fraction separator.
- `×10ⁿ` stands for the calculator's compact "×10" glyph with a raised exponent (02 §8.3).
- Results are right-aligned on the second line. "Input line" is the first display line.

**Exactness tags.**

- **[printed]**: the value is printed in the guide's text or results column.
- **[screen]**: read from a screen capture in the guide.
- **[not printed]**: the guide gives no output, and any value shown is this researcher's expectation (INFERRED). Do not use such a value as an oracle without checking it on the emulator.
- **[keys INFERRED]**: the guide states the calculation but prints no keys.
- **UNREADABLE** would mark a figure that cannot be read. None was needed: every figure below was read at 4–6× zoom.

---

## A. Setup and display-format examples [G p.11–13]

### A-01: Natural display, fraction result [G p.11, screen]
- Setup: DEFAULT (MthIO-MathO)
- Keys: `1` `÷` `2` `0` `0` `=`
- Input line: `1÷200`
- Result: stacked `1/200` [screen]

### A-02: MthIO-LineO, Norm 1 [G p.12, screen]
- Setup: `SHIFT MODE 1 2` (MthIO → LineO), `SHIFT MODE 8 1` (Norm 1)
- Keys: `1 ÷ 2 0 0 =`
- Input line: `1÷200`
- Result: `5×10⁻³` [screen]

### A-03: MthIO-LineO, Norm 2 [G p.12, screen]
- Setup: `SHIFT MODE 1 2`, Norm 2 (default)
- Keys: `1 ÷ 2 0 0 =`
- Result: `0,005` [screen]

### A-04: LineIO, Norm 1 [G p.12, screen; also the text example on p.13]
- Setup: `SHIFT MODE 2` (LineIO), `SHIFT MODE 8 1`
- Keys: `1 ÷ 2 0 0 =`
- Result: `5×10⁻³` [screen] [printed]

### A-05: LineIO, Norm 2 [G p.13]
- Setup: LineIO, Norm 2
- Keys: `1 ÷ 2 0 0 =`
- Result: `0,005` [printed]

### A-06: Fix 3 [G p.13]
- Setup: LineIO, `SHIFT MODE 6 3`
- Keys: `1 0 0 ÷ 7 =`
- Result: `14,286` [printed]; the `FIX` indicator is lit

### A-07: Fix 2 [G p.13]
- Setup: LineIO, `SHIFT MODE 6 2`
- Keys: `1 0 0 ÷ 7 =`
- Result: `14,29` [printed]

### A-08: Sci 5 [G p.13]
- Setup: LineIO, `SHIFT MODE 7 5`
- Keys: `1 ÷ 7 =`
- Result: `1,4286×10⁻¹` [printed]; `SCI` lit

### A-09: Sci 4 [G p.13]
- Setup: LineIO, `SHIFT MODE 7 4`
- Keys: `1 ÷ 7 =`
- Result: `1,429×10⁻¹` [printed]

### A-10: Sci 0 means 10 significant digits [G p.13]
- Setup: LineIO, `SHIFT MODE 7 0`
- Keys: `1 ÷ 7 =`
- Result: `1,428571429×10⁻¹` [printed]

---

## B. Input examples [G p.16–18]

### B-01: Function with explicit × and `)` [G p.16, screen]
- Setup: DEFAULT
- Keys: `4 × sin 3 0 ) × ( 3 0 + 1 0 × 3 ) =`
- Input line after `=`: `4×sin(30)×(30+1▷`. The line shows 15 characters plus the hollow "continues" marker.
- Result: `120` [screen]

### B-02: Same calculation, × and the final `)` omitted [G p.16, screen]
- Setup: DEFAULT
- Keys: `4 sin 3 0 ) ( 3 0 + 1 0 × 3 =`
- Input line after `=`: `4sin(30)(30+10×3`. It has 16 characters, no marker, and **no** `)` is added.
- Result: `120` [screen]

### B-03: Built-up fraction with surds [G p.17, screen]
- Setup: DEFAULT
- Keys: `FRAC 2 + √ 2 ▶ ▶ 1 + √ 2 =`
  - The first `▶` leaves the √.
  - The second `▶` moves from the numerator to the denominator.
- Input line: stacked `(2+√2)/(1+√2)`
- Result: `√2` [screen]

### B-04: INS wraps an existing fraction in √ [G p.18, screens]
- Setup: DEFAULT
- Keys: `1 + 7 FRAC 6`. Screen: `1+` then stacked `7/6`, with the cursor after the 6.
- Keys: `◀ ◀ ◀ ◀` `SHIFT DEL` (INS). Screen: `1+` then stacked `7/6`, with the cursor (changed shape) in front of the fraction.
- Keys: `√`. Screen: `1+√(7/6)`. The fraction is now inside the radical.
- Result: none. This is an **input-structure** vector, not an evaluation.

### B-05: √ form kept, within the coefficient ranges [G p.18, table]
- Setup: DEFAULT
- Keys [keys INFERRED]: `1 0 √ 2 ▶ + 1 5 × 3 √ 3 =`
- Result: `45√3+10√2` [printed]

### B-06: √ form refused because coefficient a = 297 ≥ 100, so the result is decimal [G p.18, table]
- Setup: DEFAULT
- Keys [keys INFERRED]: `9 9 √ 9 9 9 =`
- Result: `3129,089165` [printed]. Its value is 297√111.

---

## C. Basic calculations [G p.20–26]

### C-01: π form ↔ decimal [G p.20]
- Setup: DEFAULT
- Keys: `SHIFT ×10ˣ` (π) `÷ 6 =`
- Result: stacked `1/6` followed by `π` [printed]
- Then `S⇔D`: `0,5235987756` [printed]
- Then `S⇔D`: back to `1/6 π` [printed: shown as a two-way toggle]

### C-02: Surd sum ↔ decimal [G p.20]
- Setup: DEFAULT
- Keys: `( √ 2 ▶ + 2 ) × √ 3 =`
- Result: `√6+2√3` [printed]
- Then `S⇔D`: `5,913591358` [printed] (and back again)

### C-03: LineIO division gives a decimal first [G p.20]
- Setup: LineIO
- Keys: `1 ÷ 5 =`
- Result: `0,2` [printed]
- Then `S⇔D`: `1⌟5` [printed] (and back again)

### C-04: LineIO fraction input gives a fraction first [G p.20]
- Setup: LineIO
- Keys: `1 − 4 FRAC 5 =`
- Result: `1⌟5` [printed]
- Then `S⇔D`: `0,2` [printed]

### C-05: Fraction addition, number then FRAC [G p.21]
- Setup: DEFAULT
- Keys: `2 FRAC 3 ▶ + 1 FRAC 2 =`
- Result: stacked `7/6` [printed]

### C-06: Fraction addition, empty template first [G p.21]
- Setup: DEFAULT
- Keys: `FRAC 2 ▼ 3 ▶ + FRAC 1 ▼ 2 =`
- Result: stacked `7/6` [printed]

### C-07: Fraction addition in LineIO [G p.21]
- Setup: LineIO
- Keys: `2 FRAC 3 + 1 FRAC 2 =`
- Result: `7⌟6` [printed]

### C-08: Mixed-fraction template [G p.21]
- Setup: DEFAULT
- Keys: `4 − SHIFT FRAC` (mixed) `3 ▶ 1 ▼ 2 =`
- Result: stacked `1/2` [printed]

### C-09: Mixed fraction in LineIO [G p.21]
- Setup: LineIO
- Keys: `4 − 3 FRAC 1 FRAC 2 =`
- Result: `1⌟2` [printed]

### C-10: Percent of a value [G p.22]
- Setup: DEFAULT
- Keys: `1 5 0 × 2 0 SHIFT (` (%) `=`
- Result: `30` [printed]

### C-11: What percentage 660 is of 880 [G p.22]
- Setup: DEFAULT
- Keys: `6 6 0 ÷ 8 8 0 SHIFT (` (%) `=`
- Result: `75` [printed]

### C-12: Increase by 15 % [G p.22]
- Setup: DEFAULT
- Keys: `2 5 0 0 + 2 5 0 0 × 1 5 SHIFT (` (%) `=`
- Result: `2875` [printed]

### C-13: Decrease by 25 % [G p.22]
- Setup: DEFAULT
- Keys: `3 5 0 0 − 3 5 0 0 × 2 5 SHIFT (` (%) `=`
- Result: `2625` [printed]

### C-14: Sexagesimal addition [G p.23]
- Setup: DEFAULT
- Keys: `2 °'" 2 0 °'" 3 0 °'" + 0 °'" 3 9 °'" 3 0 °'" =`
- Result: `3°0'0"` [printed]. There is no zero padding.

### C-15: Sexagesimal ↔ decimal [G p.23]
- Setup: DEFAULT
- Keys: `2 °'" 1 5 °'" 1 8 °'" =`
- Result: `2°15'18"` [printed]
- Then `°'"`: `2,255` [printed]
- Then `°'"`: `2°15'18"` [printed]

### C-16: Multi-statement [G p.23]
- Setup: DEFAULT
- Keys: `3 + 3 ALPHA ∫` (:) `3 × 3 =`
- Result: `6` [printed]. The `Disp` indicator is lit (INFERRED from G p.9; the guide does not draw the screen).
- Then `=`: `9` [printed]

### C-17: ENG, moving the decimal point right [G p.23]
- Setup: DEFAULT
- Keys: `1 2 3 4 =`, giving `1234`
- Then `ENG`: `1,234×10³` [printed]
- Then `ENG`: `1234×10⁰` [printed]

### C-18: ←ENG, moving the decimal point left [G p.23–24]
- Setup: DEFAULT
- Keys: `1 2 3 =`, giving `123`
- Then `SHIFT ENG` (←): `0,123×10³` [printed]
- Then `SHIFT ENG` (←): `0,000123×10⁶` [printed]

### C-19: ÷R in Natural Display [G p.24, screen]
- Setup: DEFAULT
- Keys: `5 ALPHA FRAC` (÷R) `2 =`
- Input line: `5÷R2`
- Result: `2;R=1` [screen]

### C-20: ÷R in LineIO [G p.24, screen]
- Setup: LineIO
- Keys: `5 ALPHA FRAC` (÷R) `2 =`
- Display:
  - Line 1: `5÷R2`.
  - Line 2, right-aligned: `2`.
  - Line 3: `R=` at the left and `1` at the right. [screen]

### C-21: STO of a ÷R result stores only the quotient [G p.24]
- Setup: DEFAULT
- Keys: `5 ALPHA FRAC` (÷R) `2 SHIFT RCL` (STO) `)` (X)
- Expected: X = 2 [printed as a statement; the display is not printed]. To check, run `RCL )` (X), which should show `2` (INFERRED check step).

### C-22: ÷R inside a longer expression passes only the quotient [G p.24]
- Setup: DEFAULT
- Keys: `1 0 + 1 7 ALPHA FRAC` (÷R) `6 =`
- Result: `12` [printed]. It is evaluated as 10 + 2.

### C-23: ÷R falls back to ordinary division for a very large operand [G p.24]
- Setup: DEFAULT
- Keys: `2 0 0 0 0 0 0 0 0 0 0 ALPHA FRAC` (÷R) `1 7 =`
- Expected: the same as `20000000000 ÷ 17`. [not printed]: the true value is 1176470588,235…; what the display shows (decimal or fraction) is UNKNOWN.

### C-24: ÷R falls back for a negative dividend [G p.24]
- Setup: DEFAULT
- Keys: `(−) 5 ALPHA FRAC` (÷R) `2 =`
- Expected: the same as `-5 ÷ 2`. [not printed]: presumably stacked `-5/2` in MathO (INFERRED).

### C-25: Prime factorisation [G p.25, screen]
- Setup: DEFAULT
- Keys: `1 0 1 4 =`, giving `1014`
- Then `SHIFT °'"` (FACT): `2×3×13²` [screen]

### C-26: Prime factorisation with a large prime factor [G p.25, screen]
- Setup: DEFAULT
- Keys: `4 1 0 4 6 7 6 =`, giving `4104676`
- Then `SHIFT °'"` (FACT): `2²×(1026169)` [screen]. Here 1026169 = 1013², left unfactored because 1013 has more than 3 digits.

### C-27: Calculation history [G p.26]
- Setup: DEFAULT, history empty (for example right after `ON` or a mode change)
- Keys: `1 + 1 =` gives `2`; `2 + 2 =` gives `4`; `3 + 3 =` gives `6` [printed]
- Then `▲`: `4` [printed] (the earlier entry `2+2`)
- Then `▲`: `2` [printed] (the entry `1+1`)

### C-28: Replay and edit [G p.26]
- Setup: DEFAULT
- Keys: `4 × 3 + 2 =`, giving `14` [printed]
- Cont.: `◀ DEL DEL − 7 =`, giving `5` [printed]. `◀` opens the expression with the cursor at its end, and the two DELs remove `2` and `+`.

---

## D. Memory [G p.26–29]

### D-01: Ans continuation [G p.26–27, screen]
- Setup: LineIO
- Keys: `3 × 4 =`, giving `12` [printed]
- Cont.: `÷ 3 0 =`
  - Input line: `Ans÷30` (Ans was inserted automatically).
  - Result: `0,4` [screen].
  - Indicators: `D` and `▲`, with no `Math` because the display is LineIO.

### D-02: The Ans key [G p.27, screen]
- Setup: DEFAULT
- Keys: `1 2 3 + 4 5 6 =`, giving `579` [printed]
- Cont.: `7 8 9 − Ans =`
  - Input line: `789-Ans`.
  - Result: `210` [screen].

### D-03: PreAns, Fibonacci [G p.27–28, screens]
- Setup: DEFAULT
- Keys: `1 =`. Screen: input `1`, result `1`. Ans = 1.
- Keys: `1 =`. Screen: `1` / `1`. Ans = 1 and PreAns = 1.
- Keys: `Ans + ALPHA Ans` (PreAns) `=`. Screen: `Ans+PreAns` / `2`.
- Keys: `=`. Result `3`.
- Keys: `=`. Result `5`.
- All [screen]. The sequence is {1, 1, 2, 3, 5}.

### D-04: Variables [G p.28]
- Setup: DEFAULT
- Keys: `3 + 5 SHIFT RCL` (STO) `(−)` (A). Result `8` [printed]. A = 8.
- Cont.: `ALPHA (−)` (A) `× 1 0 =`. Result `80` [printed].
- Cont.: `RCL (−)` (A). Result `8` [printed].
- Cont.: `0 SHIFT RCL` (STO) `(−)` (A). Result `0` [printed]. A cleared.

### D-05: Independent memory M [G p.28–29]
- Setup: DEFAULT
- Keys: `0 SHIFT RCL` (STO) `M+` (M). Result `0` [printed]. The M indicator is off.
- Cont.: `1 0 × 5 M+`. Result `50` [printed]. M = 50 and the M indicator is on.
- Cont.: `1 0 + 5 SHIFT M+` (M−). Result `15` [printed]. M = 35.
- Cont.: `RCL M+` (M). Result `35` [printed].

---

## E. Function calculations [G p.30–47]

### E-01: sin [G p.30]
- Setup: LineIO, Deg
- Keys: `sin 3 0 ) =`
- Result: `0,5` [printed]

### E-02: sin⁻¹ [G p.30]
- Setup: LineIO, Deg
- Keys: `SHIFT sin` (sin⁻¹) `0 , 5 ) =`
- Result: `30` [printed]

### E-03: sinh [G p.30–31]
- Setup: DEFAULT
- Keys: `hyp 1` (sinh) `1 ) =`
- Result: `1,175201194` [printed]

### E-04: cosh⁻¹ [G p.31]
- Setup: DEFAULT
- Keys: `hyp 5` (cosh⁻¹) `1 ) =`
- Result: `0` [printed]

### E-05: Radians converted to degrees [G p.31]
- Setup: DEFAULT (Deg)
- Keys: `( SHIFT ×10ˣ` (π) `÷ 2 ) SHIFT Ans` (DRG▶) `2` (ʳ) `=`
- Result: `90` [printed]

### E-06: Grads converted to degrees [G p.31]
- Setup: DEFAULT (Deg)
- Keys: `5 0 SHIFT Ans` (DRG▶) `3` (ᵍ) `=`
- Result: `45` [printed]

### E-07: e^□ in Natural Display, Sci 3 [G p.31]
- Setup: DEFAULT, then `SHIFT MODE 7 3`
- Keys: `SHIFT ln` (e^□) `5 ▶ × 2 =`
- Result: `2,97×10²` [printed]

### E-08: e^ in LineIO, Sci 3 [G p.31]
- Setup: LineIO, `SHIFT MODE 7 3`
- Keys: `SHIFT ln` (e^) `5 ) × 2 =`
- Result: `2,97×10²` [printed]

### E-09: log with default base 10 [G p.31]
- Setup: DEFAULT
- Keys: `log 1 0 0 0 ) =`
- Result: `3` [printed]

### E-10: log(a;b) [G p.32]
- Setup: DEFAULT (no display format is specified; valid in all of them)
- Keys: `log 2 SHIFT )` (;) `1 6 ) =`
- Result: `4` [printed]

### E-11: log□ template [G p.32]
- Setup: MthIO-MathO (also MthIO-LineO)
- Keys: `log□ 2 ▶ 1 6 =`
- Result: `4` [printed]

### E-12: log₂(4³) [G p.32]
- Setup: MthIO-MathO (also MthIO-LineO)
- Keys: `log□ 2 ▶ 4 SHIFT x²` (x³) `=`
- Result: `6` [printed]

### E-13: log₂(4)³ [G p.32]
- Setup: MthIO-MathO (also MthIO-LineO)
- Keys: `log□ 2 ▶ 4 ▶ SHIFT x²` (x³) `=`
- Result: `8` [printed]

### E-14: ln with Sci 3 [G p.32]
- Setup: DEFAULT, then `SHIFT MODE 7 3`
- Keys: `ln 9 0 ) =`
- Result: `4,50×10⁰` [printed]

### E-15: Power of ten via x^ [G p.32]
- Setup: DEFAULT
- Keys: `1 , 2 × 1 0 x^ 3 =`
- Result: `1200` [printed]

### E-16: Expression as exponent [G p.32]
- Setup: DEFAULT
- Keys: `( 1 + 1 ) x^ 2 + 2 =`
- Result: `16` [printed]. The exponent slot holds `2+2`.

### E-17: Cube of a square [G p.32]
- Setup: DEFAULT
- Keys: `( 5 x² ) SHIFT x²` (x³) `=`
- Result: `15625` [printed]

### E-18: Index root, Natural Display [G p.32]
- Setup: DEFAULT
- Keys: `SHIFT x^` (ⁿ√) `5 ▶ 3 2 =`
- Result: `2` [printed]

### E-19: Index root, LineIO [G p.32]
- Setup: LineIO
- Keys: `5 SHIFT x^` (ˣ√) `3 2 ) =`
- Result: `2` [printed]

### E-20: Surd result under Fix 3, then SHIFT = [G p.32–33]
- Setup: DEFAULT, then `SHIFT MODE 6 3`
- Keys: `√ 2 ▶ × 3 =`
- Result: `3√2` [printed]. Fix does not convert the surd.
- Then `SHIFT =`: `4,243` [printed]

### E-21: The same in LineIO [G p.33]
- Setup: LineIO, `SHIFT MODE 6 3`
- Keys: `√ 2 ) × 3 =`
- Result: `4,243` [printed]

### E-22: Cube roots [G p.33]
- Setup: LineIO
- Keys: `SHIFT √` (∛) `5 ) + SHIFT √` (∛) `(−) 2 7 ) =`
- Result: `-1,290024053` [printed]

### E-23: Reciprocals [G p.33]
- Setup: LineIO
- Keys: `( 3 x⁻¹ − 4 x⁻¹ ) x⁻¹ =`
- Result: `12` [printed]

### E-24: ∫ in Natural Display [G p.33]
- Setup: DEFAULT
- Keys: `∫ ln ALPHA )` (X) `) ▶ 1 ▶ ALPHA ×10ˣ` (e) `=`
- Result: `1` [printed]. This is ∫₁ᵉ ln(x) dx.

### E-25: ∫ in LineIO [G p.33]
- Setup: LineIO
- Keys: `∫ ln ALPHA )` (X) `) SHIFT )` (;) `1 SHIFT )` (;) `ALPHA ×10ˣ` (e) `) =`
- Result: `1` [printed]

### E-26: ∫ with an explicit tol [G p.34]
- Setup: LineIO
- Keys: `∫ 1 ÷ ALPHA )` (X) `x² SHIFT )` (;) `1 SHIFT )` (;) `5 SHIFT )` (;) `1 ×10ˣ (−) 7 ) =`
- Result: `0,8` [printed]. This is ∫(1/x²; 1; 5; 1×10⁻⁷).

### E-27: ∫ giving a π-form result [G p.34]
- Setup: DEFAULT, then `SHIFT MODE 4` (Rad)
- Keys: `∫ ( sin ALPHA )` (X) `) + cos ALPHA )` (X) `) ) x² ▶ 0 ▲ SHIFT ×10ˣ` (π) `=`
- Result: `π` [printed]. This is ∫₀^π (sin x + cos x)² dx with no tol.

### E-28: d/dx in Natural Display [G p.35]
- Setup: DEFAULT, Rad
- Keys: `SHIFT ∫` (d/dx) `sin ALPHA )` (X) `) ▶ FRAC SHIFT ×10ˣ` (π) `▶ 2 =`
- Result: `0` [printed]. This is d/dx sin(x) at x = π/2.

### E-29: d/dx in LineIO [G p.35]
- Setup: LineIO, Rad
- Keys: `SHIFT ∫` (d/dx) `sin ALPHA )` (X) `) SHIFT )` (;) `SHIFT ×10ˣ` (π) `FRAC 2 ) =`
- Result: `0` [printed]. The input contains `π⌟2`.

### E-30: d/dx with an explicit tol [G p.35–36]
- Setup: LineIO
- Keys: `SHIFT ∫` (d/dx) `3 ALPHA )` (X) `x² − 5 ALPHA )` (X) `+ 2 SHIFT )` (;) `2 SHIFT )` (;) `1 ×10ˣ (−) 1 2 ) =`
- Result: `7` [printed]

### E-31: Σ in Natural Display [G p.36]
- Setup: DEFAULT
- Keys: `SHIFT log□` (Σ) `ALPHA )` (X) `+ 1 ▶ 1 ▶ 5 =`
- Result: `20` [printed]. This is Σ from x=1 to 5 of (x+1).

### E-32: Σ in LineIO [G p.36]
- Setup: LineIO
- Keys: `SHIFT log□` (Σ) `ALPHA )` (X) `+ 1 SHIFT )` (;) `1 SHIFT )` (;) `5 ) =`
- Result: `20` [printed]

### E-33: Pol in Natural Display [G p.37, and the p.7 screen]
- Setup: DEFAULT (Deg)
- Keys: `SHIFT +` (Pol) `√ 2 ▶ SHIFT )` (;) `√ 2 ▶ ) =`
- Input line: `Pol(√2;√2)`
- Result: `r=2;θ=45` [screen p.7; printed p.37 as "r = 2; θ = 45"]
- Side effect: X = 2 and Y = 45.

### E-34: Pol in LineIO [G p.37]
- Setup: LineIO (Deg)
- Keys: `SHIFT +` (Pol) `√ 2 ) SHIFT )` (;) `√ 2 ) ) =`
- Result: two lines, `r=2` and then `θ=45` [printed]

### E-35: Rec [G p.38]
- Setup: DEFAULT (Deg)
- Keys: `SHIFT −` (Rec) `√ 2 ▶ SHIFT )` (;) `4 5 ) =`
- Result: `X=1;Y=1` [printed as "X = 1; Y = 1". Spacing on screen INFERRED from the Pol capture: no spaces.]

### E-36: Factorial [G p.38]
- Setup: DEFAULT
- Keys: `( 5 + 3 ) SHIFT x⁻¹` (x!) `=`
- Result: `40320` [printed]

### E-37: Abs in Natural Display [G p.38]
- Setup: DEFAULT
- Keys: `SHIFT hyp` (Abs) `2 − 7 ▶ × 2 =`
- Result: `10` [printed]

### E-38: Abs in LineIO [G p.38]
- Setup: LineIO
- Keys: `SHIFT hyp` (Abs) `2 − 7 ) × 2 =`
- Result: `10` [printed]

### E-39: Ran#, a property test [G p.38]
- Setup: DEFAULT
- Keys: `1 0 0 0 SHIFT ,` (Ran#) `=`, then `=`, then `=`
- Guide's sample results: `634`, `92`, `175` [printed, but the guide marks them illustrative only]
- **Oracle:** each result is an integer from 0 to 999. Ran# itself lies in [0,000, 0,999] and has 3 decimal places.

### E-40: RanInt#, a property test [G p.39]
- Setup: DEFAULT
- Keys: `ALPHA ,` (RanInt) `1 SHIFT )` (;) `6 ) =`, then `=`, then `=`
- Guide's samples: `2`, `6`, `1` [printed, illustrative]
- **Oracle:** each result is an integer from 1 to 6 inclusive.

### E-41: nPr [G p.39]
- Setup: DEFAULT
- Keys: `1 0 SHIFT ×` (nPr) `4 =`
- Result: `5040` [printed]

### E-42: nCr [G p.39]
- Setup: DEFAULT
- Keys: `1 0 SHIFT ÷` (nCr) `4 =`
- Result: `210` [printed]

### E-43: Fix 3 keeps 15 internal digits [G p.39]
- Setup: LineIO, `SHIFT MODE 6 3`
- Keys: `1 0 ÷ 3 × 3 =`
- Result: `10,000` [printed]

### E-44: Rnd replaces the internal value [G p.39]
- Setup: cont. from E-43 (LineIO, Fix 3)
- Keys: `SHIFT 0` (Rnd) `1 0 ÷ 3 ) × 3 =`
- Result: `9,999` [printed]

### E-45: GCD [G p.40]
- Setup: DEFAULT
- Keys: `ALPHA ×` (GCD) `2 8 SHIFT )` (;) `3 5 ) =`
- Result: `7` [printed]. [SA s.8] screen shows the input line `GCD(28;35)`.

### E-46: LCM [G p.40]
- Setup: DEFAULT
- Keys: `ALPHA ÷` (LCM) `9 SHIFT )` (;) `1 5 ) =`
- Result: `45` [printed]. [SA s.8] screen shows the input line `LCM(9;15)`.

### E-47: CALC with two variables [G p.40–41, screens]
- Setup: DEFAULT
- Keys: `3 ALPHA (−)` (A) `+ ALPHA °'"` (B). Screen: `3A+B` followed by the cursor.
- Keys: `CALC`. Screen: `A?` at the top left, `0` at the bottom right (the current A; assumes A = 0).
- Keys: `5 = 1 0 =`. Screen: `3A+B` / `25`. (`B?` is shown between the two entries but not drawn in the guide.)
- Keys: `CALC` (or `=`). Screen: `A?` / `5`.
- Keys: `7 = 2 0 =`. Screen: `3A+B` / `41`.
- Exit with `AC`.

### E-48: CALC in CMPLX with a polar output command [G p.41]
- Setup: DEFAULT (Deg), then `MODE 2` (CMPLX)
- Keys: `ALPHA (−)` (A) `+ ALPHA °'"` (B) `ENG` (i) `SHIFT 2` (CMPLX menu) `3` (▶r∠θ)
- Screen: `A+Bi▶r∠θ` with the `CMPLX`, `D` and `Math` indicators lit [screen]
- Keys: `CALC √ 3 ) = 1 =`. Result `2∠30` [printed]. (In the CALC prompt, input is linear: `√3)`.)
- Keys: `CALC` (or `=`) `1 = √ 3 ) =`. Result `2∠60` [printed]
- Exit with `AC`.

### E-49: SOLVE for X with other variables [G p.42–43, screens]
- Setup: DEFAULT
- Keys: `ALPHA S⇔D` (Y) `ALPHA CALC` (=) `ALPHA (−)` (A) `ALPHA )` (X) `x² + ALPHA °'"` (B). Screen: `Y=AX²+B`.
- Keys: `SHIFT CALC` (SOLVE). Screen: `Y?` / `0` (current Y).
- Keys: `0 = 1 = (−) 2 =`. These set Y = 0, A = 1 and B = −2; the A? and B? prompts are not drawn. Screen: `Solve for X` / `0` (current X; assumes X = 0).
- Keys: `1 =` (initial guess 1). Solution screen [screen]:

  ```
  Y=AX²+B
  X=   1,414213562
  L-R=           0
  ```

- Exit with `AC`.

### E-50: SOLVE repeated for several Y values [G p.44, screens]
- Setup: DEFAULT
- Keys: `ALPHA S⇔D` (Y) `ALPHA CALC` (=) `ALPHA )` (X) `x² − ALPHA )` (X) `+ 1`. Screen: `Y=X²-X+1`.
- Keys: `SHIFT CALC` (SOLVE). Screen: `Y?` / `0`.
- Keys: `3 =`. Screen: `Solve for X` / `0`.
- Keys: `1 =`. Screen: `Y=X²-X+1` / `X=` `2` / `L-R=` `0`.
- Keys: `= 7 = =`. Solution `X=` `3`, `L-R=` `0`. The first `=` returns to `Y?` and the last `=` accepts the current X (2) as the guess.
- Keys: `= 1 3 = =`. Solution `X=` `4`, `L-R=` `0`. [All screen.]

### E-51: Scientific constant C₀ [G p.44–45, screens]
- Setup: DEFAULT
- Keys: `AC SHIFT 7` (CONST). Screen:

  ```
  CONSTANT
  Number 01~40?
            [__]
  ```

- Keys: `2 8` (C₀) `=`. Screen: input `C₀` (drawn like `Co`), result `299792458`.

### E-52: Constants in an expression [G p.45, screen]
- Setup: DEFAULT
- Keys: `AC FRAC 1 ▼ √ SHIFT 7` (CONST) `3 2` (ε₀) `SHIFT 7` (CONST) `3 3` (μ₀) `=`
- Input line: stacked `1/√(ε₀μ₀)`
- Result: `299792458` [screen]

### E-53: Conversion cm to in [G p.46, screens]
- Setup: LineIO
- Keys: `AC 5 SHIFT 8` (CONV). Screen: `CONVERSION` / `Number 01~40?` / `[__]`.
- Keys: `0 2` (cm▶in) `=`. Screen: `5cm▶in` / `1,968503937`.

### E-54: Conversion g to oz [G p.47, screen]
- Setup: LineIO
- Keys: `AC 1 0 0 SHIFT 8` (CONV) `2 2` (g▶oz) `=`
- Screen: `100g▶oz` / `3,527396584`

### E-55: Conversion °C to °F [G p.47, screen]
- Setup: LineIO
- Keys: `AC (−) 3 1 SHIFT 8` (CONV) `3 8` (°C▶°F) `=`
- Screen: `-31°C▶°F` / `-23,8`

---

## F. Display, errors and FAQ [G p.7, p.83, p.88, p.93–94]

### F-01: Truncated input and result with continuation markers [G p.7, screen]
- Setup: DEFAULT, then `SHIFT MODE 4` (Rad)
- Keys [keys INFERRED]: `SHIFT +` (Pol) `1 , 4 1 4 2 1 3 5 6 2 SHIFT )` (;) `1 , 4 1 4 2 1 3 5 6 2 ) =`
- Input line: `Pol(1,414213562▷` (the input continues)
- Result: `r=2;θ=0,7853981▶` (the result continues; scroll with `▶`) [screen]
- The full θ is π/4 = 0,7853981634, an INFERRED value seen by scrolling.

### F-02: Math ERROR and Goto [G p.83, screens]
- Setup: DEFAULT
- Keys: `1 4 ÷ 0 × 2 =`. Screen:

  ```
  Math ERROR

  [AC]  :Cancel
  [◀][▶]:Goto
  ```

- Keys: `▶` (or `◀`). Screen: `14÷0×2`, with the cursor immediately after the `0`.
- Keys: `◀ 1 =`. Screen: input `14÷10×2`, result stacked `14/5` [screen]

### F-03: AC on an error screen [G p.83]
- Setup: DEFAULT
- Keys: `1 4 ÷ 0 × 2 =`, then `AC`
- Expected: the error is cleared, **and the expression is cleared too** [printed as a rule; the screen is not drawn]

### F-04: Squaring a negative number [G p.88]
- Setup: DEFAULT
- Keys: `( (−) 2 ) x² =`. Expected `4`. Keys: `(−) 2 x² =`. Expected `-4`.
- [not printed]: the guide states the rule (x² binds tighter than the negative sign) but prints no outputs. The values are INFERRED.

### F-05: Closing the function parenthesis [G p.94]
- Setup: LineIO, Deg
- Keys: `sin 3 0 ) + 1 5 =`
- Result: `15,5` [printed]

### F-06: Forgetting the parenthesis [G p.94]
- Setup: LineIO, Deg
- Keys: `sin 3 0 + 1 5 =`
- Result: `0,7071067812` [printed]. This is sin(45).

### F-07: Authenticity screen [G p.92]
- Setup: any
- Keys: `MODE 0`
- Expected: a 24-character calculator ID and a QR code (for <https://wes.casio.com/calc/>). `AC` returns to the MODE menu. The ID is device-specific: no fixed oracle.

---

## G. Settings/initialisation checks [G p.4, p.15, p.26]

### G-01: Initialisation table
- Keys: `SHIFT 9 1 =` (CLR Setup)
- Expected state [printed table, p.15]:
  - COMP
  - MthIO-MathO
  - Deg
  - Norm 2
  - d/c
  - a+bi
  - Stat OFF
  - Table f(x),g(x)
  - APO 10Min.
- History is cleared [p.26].
- Check: `1 ÷ 2 0 0 =` gives stacked `1/200` (same as A-01), and after `S⇔D` it gives `0,005` (Norm 2; INFERRED from A-03).

### G-02: Memory retained across AC [G p.29]
- Keys: `5 SHIFT RCL` (STO) `(−)` (A) `AC` `RCL (−)` (A)
- Expected: `5`. [not printed as an example; it is the rule "variables are retained even if AC is pressed".] INFERRED.

### G-03: CLR Memory
- Keys: `5 SHIFT RCL` (STO) `(−)` (A), then `SHIFT 9 2 =`, then `RCL (−)` (A)
- Expected: `0`. [not printed as an example; rule from p.29.] INFERRED.

---

## H. Vectors from the Casio South-Africa handout [SA] (real ZA PLUS II screen captures)

These come from distributor material, not the user's guide. They are included because they are captures of this model.

### H-01: Norm 2 default [SA s.3]
- Setup: DEFAULT
- Keys [keys INFERRED]: `FRAC 1 ▼ 8 0 0 =` then `S⇔D`
- Screen: input stacked `1/800`, result `0,00125` [screen]. The same calculation on the first-generation fx-991ZA PLUS (Norm 1, dot decimal) showed `1.25×10⁻³`.

### H-02: Fix 2 applies only after S⇔D on a fraction [SA s.5]
- Setup: DEFAULT, then `SHIFT MODE 6 2`. The prompt screen shows `Fix 0~9?`.
- Keys [keys INFERRED]: `FRAC 2 ▼ 3 =`, then `S⇔D`
- Screen: input stacked `2/3`, result `0,67`, with the `D`, `FIX`, `Math` and `▲` indicators lit [screen]
- The Norm prompt screen is `Norm 1~2?` [screen].

### H-03: ÷R [SA s.9]
- Setup: DEFAULT
- Keys: `7 5 ALPHA FRAC 4 =`
- Screen: `75÷R4` / `18;R=3` [screen]

### H-04: CALC with x² [SA s.12]
- Setup: DEFAULT
- Keys: `3 ALPHA (−)` (A) `x² + 4 ALPHA °'"` (B), then `CALC`
- Screens: `A?` / `0`; then `6 =` gives `B?` / `0`; then `2 =` gives `3A²+4B` / `116` [screen]

### H-05: SOLVE a linear equation [SA s.13]
- Setup: DEFAULT
- Keys: `3 ALPHA )` (X) `+ 4 ALPHA CALC` (=) `2 ALPHA )` (X) `+ 6`, then `SHIFT CALC`, giving `Solve for X` / `0`
- Then `=`. Solution screen: `3X+4=2X+6` / `X=` `2` / `L-R=` `0` [screen]

### H-06: SOLVE starts from the current X [SA s.14]
- Setup: cont. from H-05 (X = 2)
- Keys: `ALPHA )` (X) `x² − 6 ALPHA )` (X) `ALPHA CALC` (=) `0`, then `SHIFT CALC`, giving `Solve for X` / `2`
- Then `=`. Solution: `X²-6X=0` / `X=` `0` / `L-R=` `0` [screen]
- After changing the initial value (the value is not shown in the handout) and pressing `=`, the solution is `X=` `6` [screen].

### H-07: Conversion °F to °C, editing before the command [SA s.19]
- Setup: DEFAULT
- Keys: `SHIFT 8 3 7`. The screen shows `°F▶°C`.
- Keys: `◀ 3 5 6`. Input becomes `356°F▶°C`.
- Keys: `=`. Result `180` [screen]

### H-08: Conversion km/h to m/s [SA s.19]
- Setup: DEFAULT
- Keys: `SHIFT 8 1 9 ◀ 1 2 0 = S⇔D`
- Screen: `120km/h▶m/s` / `33,33333333` [screen]. Before `S⇔D` the result is presumably stacked `100/3` (INFERRED; the handout's key sequence includes `S⇔D`).
- **Cross-check significance:** this vector proves km/h▶m/s divides by **3,6 exactly**. NIST SP 811's printed factor 0,2777778 would give `33,333336`. Files 03 §15 and 06 §5.28 had assumed the printed factor for every pair; both are corrected.
