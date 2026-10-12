# 05 — fx-991ZA PLUS II: every worked example from the mode, CONST and CONV chapters, as test vectors

This file is the companion to [`03-manual-modes.md`](03-manual-modes.md). It holds every worked
example in the official *fx-991ZA PLUS II User's Guide*, printed pp.44–82: Scientific Constants, Metric
Conversion, CMPLX, STAT, BASE-N, EQN, MATRIX, TABLE, VECTOR, DIST, INEQ and RATIO. Each one is written
as a numbered test vector.

**Source** (official CASIO):

- PDF: <https://www.casio.com/content/dam/casio/global/support/manuals/calculators/pdf/004-en/f/fx-991ZA_PLUSII_EN.pdf>
- HTML: <https://support.casio.com/global/en/calc/manual/fx-991ZAPLUSII_en/>

Page numbers are the guide's printed numbers. Screen names, e.g. `DIST_06`, are the [H] image file names.

## How to read a vector

- **Start state**: the guide assumes the *initial default setup* unless an example says otherwise
  (p.4). On the ZA PLUS II the defaults are COMP, MthIO-MathO, Deg, **Norm 2**, **d/c**, a+bi,
  STAT (FREQ) OFF, **TABLE f(x),g(x)**, APO 10 min (p.15).

  To reproduce a vector from a clean state, run `[SHIFT][9](CLR)[3](All)[=](Yes)` first, then any
  setup the vector lists. Key notation is as in `03-manual-modes.md` §2.1. `(LABEL)` names what a key
  selects and is not a keystroke.
- **Decimal comma**: every displayed result uses `,` as the decimal separator, exactly as printed.
  `[,]` is the decimal-point key.
- **Status of each expected value**:
  - **P** (printed): the value appears in the guide's text or result column.
  - **S** (screen): read from the guide's LCD image.
  - **C** (computed): not visible in the guide, because the cursor highlight covers it or it is
    off-screen. I worked it out by arithmetic. Treat as INFERRED.
  - **UNREADABLE**: not legible in the guide.
- **Setup inserted by me**: some examples state a format, e.g. "(Complex number format: r∠θ)",
  without the keys. Where I add the keys, the step is marked *(setup keys added; INFERRED from p.11–14)*.
- **Printed misprints** are kept as printed and flagged. A vector never silently changes them.

---

## Scientific constants (pp.44–46)

### EX-CONST-01 — recall C₀
- Mode: COMP. Display: MthIO-MathO.
- Keys: `[AC] [SHIFT][7](CONST)`
  - Screen **S**: `CONSTANT` / `Number 01~40?` / `[__]`
- Keys: `[2][8](C₀) [=]`
  - Screen **S**: input `Co`. Result **P/S** `299792458`.

### EX-CONST-02 — C₀ = 1/√(ε₀μ₀)
- Mode: COMP, MthIO-MathO.
- Keys: `[AC] [■/■] 1 [▼] [√■] [SHIFT][7](CONST)[3][2](ε₀) [SHIFT][7](CONST)[3][3](μ₀) [=]`
- Expected: input shown as the fraction 1 over √ε₀μ₀. Result **P/S** `299792458`.

## Metric conversion (pp.46–47)

All three examples are **LineIO**. *Setup keys added (INFERRED from p.11):* `[SHIFT][MODE](SETUP)[2](LineIO)`.

### EX-CONV-01 — 5 cm to inches
- Keys: `[AC] 5 [SHIFT][8](CONV)`
  - Screen **S**: `CONVERSION` / `Number 01~40?` / `[__]`
- Keys: `[0][2](cm▶in) [=]`
  - Screen **S**: input `5cm▶in`. Result **S** `1,968503937`.

### EX-CONV-02 — 100 g to ounces
- Keys: `[AC] 100 [SHIFT][8](CONV)[2][2](g▶oz) [=]`
- Expected **S**: input `100g▶oz`. Result **`3,527396584`**.
- This equals 100 ÷ 28.34952, the NIST SP 811 printed factor, not 100 ÷ 28.349523125. It is
  evidence for the **oz** factor only. *(Cross-check: it does not generalise. A ZA capture shows
  km/h uses an exact 3,6, not SP 811's 0,2777778 — vector H-08 in 04 and 03 §15.)*

### EX-CONV-03 — −31 °C to °F
- Keys: `[AC] [(−)] 31 [SHIFT][8](CONV)[3][8](°C▶°F) [=]`
- Expected **S**: input `-31°C▶°F`. Result `-23,8`.

---

## CMPLX (pp.48–49)

Mode: `[MODE][2](CMPLX)`. Screen **S**: an empty line with `CMPLX`, `D`, `Math` indicators.

### EX-CMPLX-01 — (2+6i)÷(2i)
- Format: a+bi (default).
- Keys: `[(] 2 [+] 6 [ENG](i) [)] [÷] [(] 2 [ENG](i) [)] [=]`
- Expected **P**: `3-i`

### EX-CMPLX-02 — 2∠45 in rectangular form
- Setup: MthIO-MathO, Deg, a+bi.
- Keys: `2 [SHIFT][(−)](∠) 45 [=]`
- Expected **P**: `√2+√2i`

### EX-CMPLX-03 — √2+√2i in polar form
- Setup: MthIO-MathO, Deg, **complex format r∠θ**. *Setup keys added:* `[SHIFT][MODE][▼][3](CMPLX)[2](r∠θ)`.
- Keys: `[√■] 2 [▶] [+] [√■] 2 [▶] [ENG](i) [=]`
- Expected **P**: `2∠45`

### EX-CMPLX-04 — (1−i)⁻¹
- Setup: MthIO-MathO, a+bi.
- Keys: `[(] 1 [-] [ENG](i) [)] [x⁻¹] [=]`
- Expected **P**: `1/2+1/2 i`, shown as stacked fractions.

### EX-CMPLX-05 — (1+i)² + (1−i)²
- Setup: MthIO-MathO.
- Keys: `[(] 1 [+] [ENG](i) [)] [x²] [+] [(] 1 [-] [ENG](i) [)] [x²] [=]`
- Expected **P**: `0`

### EX-CMPLX-06 — conjugate of 2+3i
- Setup: a+bi.
- Keys: `[SHIFT][2](CMPLX)[2](Conjg) 2 [+] 3 [ENG](i) [)] [=]`
- Expected **P**: `2-3i`

### EX-CMPLX-07 — |1+i|
- Setup: MthIO-MathO, Deg.
- Keys: `[SHIFT][hyp](Abs) 1 [+] [ENG](i) [=]`. As printed there is **no** closing `)`.
- Expected **P**: `√2`

### EX-CMPLX-08 — arg(1+i)
- Setup: MthIO-MathO, Deg.
- Keys: `[SHIFT][2](CMPLX)[1](arg) 1 [+] [ENG](i) [)] [=]`
- Expected **P**: `45`

### EX-CMPLX-09 — ▶r∠θ command
- Setup: MthIO-MathO, Deg. The format setting is not stated; the command overrides it.
- Keys: `[√■] 2 [▶] [+] [√■] 2 [▶] [ENG](i) [SHIFT][2](CMPLX)[3](▶r∠θ) [=]`
- Expected **P**: `2∠45`

### EX-CMPLX-10 — ▶a+bi command
- Setup: as EX-CMPLX-09.
- Keys: `2 [SHIFT][(−)](∠) 45 [SHIFT][2](CMPLX)[4](▶a+bi) [=]`
- Expected **P**: `√2+√2i`

---

## STAT (pp.49–58)

### EX-STAT-01 — enter paired data for linear regression (editor behaviour only)
- Keys: `[MODE][3](STAT)[2](A+BX)`
  - Screen **S**: an empty editor, header `X  Y`, rows 1–3, cursor on row 1 X, `STAT` indicator.
- Keys: `170 [=] 173 [=] 179 [=] [▼] [▶]`
  - Screen **S**: X column 170/173/179. The **Y column shows 0** in rows 2–3. The cursor is on
    **row 1 Y** and the bottom line shows `0`.
  - The key `[▼]` is as printed. The cursor ends up in row 1, so INFERRED: `▼` wraps from the empty
    row 4 to row 1.
- Keys: `66 [=] 68 [=] 75 [=]`
  - Screen **S**: rows 2–4 visible (173/68, 179/75). The cursor is on the empty row 4 Y.
- No numeric result.

### EX-STAT-02 — 1-VAR with FREQ: mean and population SD
- Setup: `[SHIFT][MODE](SETUP)[▼][4](STAT)[1](ON)`
- Keys: `[MODE][3](STAT)[1](1-VAR)`, then `1[=]2[=]3[=]4[=]5[=] [▼] [▶] 1[=]2[=]3[=]2[=]`
  - Screen **S**: columns `X | FREQ`. The cursor is on row 5 FREQ, which shows **1** because the
    default frequency was never overwritten.
  - Data meant: {1;1, 2;2, 3;3, 4;2, 5;1}, n = 9.
- Keys: `[AC] [SHIFT][1](STAT/DIST)[4](Var)[2](x̄) [=]` → **P** `3`
- Keys: `[AC] [SHIFT][1](STAT/DIST)[4](Var)[3](σx) [=]` → **P** `1,154700538`

### EX-STAT-03 — linear vs logarithmic regression, Fix 3
- Setup: `[SHIFT][MODE][▼][4](STAT)[2](OFF)`, then `[SHIFT][MODE][6](Fix)[3]`
- Keys: `[MODE][3](STAT)[2](A+BX)`, then `20[=]110[=]200[=]290[=] [▼] [▶] 3150[=]7310[=]8800[=]9310[=]`
  - Screen **S**: `STAT` and `FIX` indicators. Rows 3–5 visible (200/8800, 290/9310).
- Keys: `[AC] [SHIFT][1](STAT/DIST)[5](Reg)[3](r) [=]` → **P** `0,923` (linear r)
- Keys: `[AC] [SHIFT][1](STAT/DIST)[1](Type)[4](ln X)`, then `[AC] [SHIFT][1](STAT/DIST)[5](Reg)[3](r) [=]` → **P** `0,998`
  - Changing **between paired types keeps the data**.
- Keys: `[AC] [SHIFT][1](STAT/DIST)[5](Reg)[1](A) [=]` → **P** `-3857,984`
- Keys: `[AC] [SHIFT][1](STAT/DIST)[5](Reg)[2](B) [=]` → **P** `2357,532`
- Unrounded values, **C** (my least-squares recomputation): r_lin = 0.92284790, r_ln = 0.99833447,
  A = −3857.98441, B = 2357.53155.

### EX-STAT-04 — estimate x for y = −130 (continues EX-STAT-03, Fix 3, ln X)
- Keys: `[AC] [(−)] 130 [SHIFT][1](STAT/DIST)[5](Reg)[4](x̂) [=]`
- Expected **P**: `4,861`. **C**: 4.861312.

### EX-STAT-05 — normalised variate and P(t)
- Setup: `[SHIFT][MODE][▼][4](STAT)[1](ON)`, then `[SHIFT][MODE][6](Fix)[3]`
- Keys: `[MODE][3](STAT)[1](1-VAR)`, then
  `0[=]1[=]2[=]3[=]4[=]5[=]6[=]7[=]9[=]10[=] [▼] [▶] 1[=]2[=]1[=]2[=]2[=]2[=]3[=]4[=]2[=]1[=]`
  - Screen **S**: rows 9–11 visible. X shows 9, 10 and an empty row 11. FREQ row 10 shows 2 and the
    cursor is on row 11 FREQ.
  - Data: {0;1, 1;2, 2;1, 3;2, 4;2, 5;2, 6;3, 7;4, 9;2, 10;1}, n = 20.
- Keys: `[AC] 3 [SHIFT][1](STAT/DIST)[5](Distr)[4](▶t) [=]`
  - Screen **S**: input `3▶t`. Result `-0,762`.
- Keys: `[SHIFT][1](STAT/DIST)[5](Distr)[1](P() [Ans] [)] [=]`
  - Screen **S**: input `P(Ans)`. Result `0,223`.
- **C**: x̄ = 5.1, σx = 2.75499546, t = −0.76225171, P(t) = 0.22295489.

---

## BASE-N (pp.58–62)

### EX-BASEN-01 — enter mode, BIN, 11₂ + 1₂
- Keys: `[MODE][4](BASE-N)` → **S** `Dec` / `0`
- Keys: `[log](BIN)` → **S** `Bin` / `0000000000000000`
- Keys: `11 [+] 1 [=]` → **S** `Bin` / `0000000000000100`

### EX-BASEN-02 — HEX 1F₁₆ + 1₁₆ (continues)
- Keys: `[AC] [x■](HEX) 1 [tan](F) [+] 1 [=]` → **S** `Hex` / `00000020`

### EX-BASEN-03 — OCT 7₈ + 1₈ (continues)
- Keys: `[AC] [ln](OCT) 7 [+] 1 [=]` → **S** `Oct` / `00000000010`

### EX-BASEN-04 — mixed-base literals, result in DEC
- Keys **as printed**:
  `[AC] [x²](DEC) [SHIFT][3](BASE)[▼][1](d) 10 [+] [SHIFT][3](BASE)[▼][2](h) 10 [+] [SHIFT][3](BASE)[▼][3](b) 10 [+] [SHIFT][3](BASE)[▼][4](o) 10 [+]`
- Expected **P**: `36` (= 10 + 16 + 2 + 8).
- ⚠ The printed sequence **ends in `[+]`**, in both the PDF and the HTML. It must be **`[=]`** to
  produce a result. INFERRED misprint.

### EX-BASEN-05 — 15 × 37 in DEC, then convert
- Keys: `[AC] [x²](DEC) 15 [×] 37 [=]` → **P** `555`
- Keys: `[x■](HEX)` → **P** `0000022B`
- Keys: `[log](BIN)` → **P** `0000001000101011`
- Keys: `[ln](OCT)` → **P** `00000001053`

All of EX-BASEN-06 … 11 are in **BIN** mode (`[log](BIN)` first).

### EX-BASEN-06 — 1010 and 1100
- Keys: `[AC] 1010 [SHIFT][3](BASE)[1](and) 1100 [=]` → **P** `0000000000001000`

### EX-BASEN-07 — 1011 or 11010
- Keys: `[AC] 1011 [SHIFT][3](BASE)[2](or) 11010 [=]` → **P** `0000000000011011`

### EX-BASEN-08 — 1010 xor 1100
- Keys: `[AC] 1010 [SHIFT][3](BASE)[3](xor) 1100 [=]` → **P** `0000000000000110`

### EX-BASEN-09 — 1111 xnor 101
- Keys: `[AC] 1111 [SHIFT][3](BASE)[4](xnor) 101 [=]` → **P** `1111111111110101`

### EX-BASEN-10 — Not(1010)
- Keys: `[AC] [SHIFT][3](BASE)[5](Not) 1010 [)] [=]` → **P** `1111111111110101`

### EX-BASEN-11 — Neg(101101)
- Keys: `[AC] [SHIFT][3](BASE)[6](Neg) 101101 [)] [=]` → **P** `1111111111010011` (= 65536 − 45)

---

## EQN (pp.62–65)

### EX-EQN-01 — x + 2y = 3, 2x + 3y = 4
- Keys: `[MODE][5](EQN)[1](anX+bnY=cn)`, then `1[=]2[=]3[=] 2[=]3[=]4[=]`
  - Screen **S** (`EQN_2`): headers `a b c`, rows 1–2. The cursor **stays on row 2 c**, bottom `4`,
    `Math` indicator.
- Keys: `[=]` → **P** `X=` `-1`
- Keys: `[▼]` → **P** `Y=` `2`

### EX-EQN-02 — x − y + z = 2, x + y − z = 0, −x + y + z = 4
- Keys: `[MODE][5](EQN)[2](anX+bnY+cnZ=dn)`, then
  `1[=][(−)]1[=]1[=]2[=] 1[=]1[=][(−)]1[=]0[=] [(−)]1[=]1[=]1[=]4[=]`
  - Screen **S** (`EQN_3`): visible headers `b c d` (scrolled sideways), rows 1–3, cursor on row 3 d, bottom `4`.
- Keys: `[=]` → **P** `X=` `1`
- Keys: `[▼]` → **P** `Y=` `2`
- Keys: `[▼]` → **P** `Z=` `3`

### EX-EQN-03 — 2x² − 3x − 6 = 0 (MthIO-MathO)
- Keys: `[MODE][5](EQN)[3](aX²+bX+c=0)`, then `2[=][(−)]3[=][(−)]6[=] [=]`
- Expected **P**, in order, stepping with `[▼]`:

  | Label | Value |
  |---|---|
  | `X1=` | `(3+√57)/4` |
  | `X2=` | `(3−√57)/4` |
  | `X-Value Minimum=` | `3/4` |
  | `Y-Value Minimum=` | `−57/8` |

- The extremum is a minimum because a > 0. With a < 0 it would be a maximum.
- **C**: X1 = 2.637458609, X2 = −1.137458609.

### EX-EQN-04 — x² − 2√2x + 2 = 0 (MthIO-MathO), double root
- Keys **as printed**: `[MODE][5](EQN)[3](aX²+bX+c=0)`, then `1[=] [(−)]2[√■]2[)][=] 2[=] [=]`
- Expected **P**: one solution only, `X=` `√2`. Whether `▼` then shows extremum screens is not printed.

### EX-EQN-05 — x³ − 2x² − x + 2 = 0
- Keys: `[MODE][5](EQN)[4](aX³+bX²+cX+d=0)`, then `1[=][(−)]2[=][(−)]1[=]2[=] [=]`
- Expected **P**: `X1=` `-1`, then `[▼]` `X2=` `2`, then `[▼]` `X3=` `1`. Keep this exact order.

### EX-EQN-06 — procedure illustration 2x² + x − 3 = 0 (input only)
- Keys: `[MODE][5](EQN)[3]`, then `2[=]1[=][(−)]3[=]`. The guide prints no result.
- Expected **C** after `[=]`: X1 = 1, X2 = −3/2; the extremum at x = −1/4 has y = −25/8. Root order
  is UNKNOWN.

---

## MATRIX (pp.65–68)

Shared state:

- MatA = [[2,1],[1,1]]
- MatB = [[2,−1],[−1,2]]
- MatC = [[1,0,−1],[0,−1,1]]

On result screens the (1,1) cell is covered by the cursor highlight, so its value is **C** unless the
bottom line shows it.

### EX-MAT-01 — define MatA and MatB, MatA×MatB
- Keys: `[MODE][6](MATRIX)` → **S** `Matrix?` / `1:MatA  2:MatB` / `3:MatC`
- Keys: `[1](MatA)[5](2×2)`
  - Screen **S** (`MATRIX_02`): `MAT`, letter `A`, an empty 2×2, cursor on (1,1), bottom `0`.
- Keys: `2[=]1[=]1[=]1[=]`
- Keys **as printed**: `[SHIFT][4](MATRIX)[2](Data)[2](MatB)[5](2×2)`
  - This uses **Data** on a matrix not yet defined, and a dimension menu appears. INFERRED: Data on an
    undefined matrix asks for a dimension.
- Keys: `2[=][(−)]1[=][(−)]1[=]2[=]`
- Keys: `[AC] [SHIFT][4](MATRIX)[3](MatA) [×] [SHIFT][4](MATRIX)[4](MatB) [=]`
  - Calc screen **S**: `MatA×MatB`.
  - Result **S**: the MatAns screen, label `Ans`, shows `[■ 0]` / `[1 1]` with bottom **`3`**.
  - So MatAns = [[3,0],[1,1]].

### EX-MAT-02 — MatA+MatB
- Keys: `[AC] [SHIFT][4](MATRIX)[3](MatA) [+] [SHIFT][4](MATRIX)[4](MatB) [=]`
- Expected **S**: MatAns `[■ 0]` / `[0 3]`, bottom **`4`**. So [[4,0],[0,3]].

### EX-MAT-03 — define MatC (2×3)
- Keys: `[SHIFT][4](MATRIX)[1](Dim)[3](MatC)[4](2×3)`, then `1[=]0[=][(−)]1[=]0[=][(−)]1[=]1[=]`
- Expected **S** (`MATRIX_07`): letter `C`, rows `1 0 −1` / `0 −1 1`. The cursor stays on (2,3) and the
  bottom shows `1`.

### EX-MAT-04 — 3 × MatA
- Keys: `[AC] 3 [×] [SHIFT][4](MATRIX)[3](MatA) [=]`
- Expected **S**: MatAns with (1,2) = 3, (2,1) = 3, (2,2) = 3, and (1,1) = 6 **C**. So [[6,3],[3,3]].

### EX-MAT-05 — det(MatA)
- Keys: `[AC] [SHIFT][4](MATRIX)[7](det) [SHIFT][4](MATRIX)[3](MatA) [)] [=]`
- Expected **P**: `1`, a scalar on the calculation line.

### EX-MAT-06 — Trn(MatC)
- Keys: `[AC] [SHIFT][4](MATRIX)[8](Trn) [SHIFT][4](MATRIX)[5](MatC) [)] [=]`
- Expected **S**: 3×2 MatAns, rows `■ 0` / `0 −1` / `−1 1`, with (1,1) = 1 **C**.
  So [[1,0],[0,−1],[−1,1]].

### EX-MAT-07 — MatA⁻¹
- Keys: `[AC] [SHIFT][4](MATRIX)[3](MatA) [x⁻¹] [=]`. **`[x■]` cannot be used for this.**
- Expected **S**: rows `■ −1` / `−1 2`, with (1,1) = 1 **C**. So [[1,−1],[−1,2]].

### EX-MAT-08 — Abs(MatB)
- Keys: `[AC] [SHIFT][hyp](Abs) [SHIFT][4](MATRIX)[4](MatB) [)] [=]`
- Expected **S**: rows `■ 1` / `1 2`, with (1,1) = 2 **C**. So [[2,1],[1,2]].

### EX-MAT-09 — MatA² and MatA³
- Keys: `[AC] [SHIFT][4](MATRIX)[3](MatA) [x²] [=]`
  - Expected **S**: rows `■ 3` / `3 2`, with (1,1) = 5 **C**. So [[5,3],[3,2]].
- Keys: `[AC] [SHIFT][4](MATRIX)[3](MatA) [SHIFT][x²](x³) [=]`
  - Expected **S**: rows `■ 8` / `8 5`, with (1,1) = 13 **C**. So [[13,8],[8,5]].

### EX-MAT-10 — Ref of [[1,2,3],[4,5,6],[7,8,9]]
- Precondition: MatA = [[1,2,3],[4,5,6],[7,8,9]].
  - The guide gives no keys. *Added, INFERRED:* `[SHIFT][4][1](Dim)[1](MatA)` + 3×3 (menu number
    UNKNOWN, probably `[1]`), then `1=2=3=4=5=6=7=8=9=`.
- Keys: `[AC] [SHIFT][4](MATRIX)[▼][1](Ref) [SHIFT][4](MATRIX)[3](MatA) [)] [=]`
- Expected **S** (`MAT_01`):

  ```
  ■      1,1428  1,2857
  0      1       2
  0      0       0
  ```

  - (1,1) = 1 **C**.
  - The cells are **truncated** displays of 8/7 = 1.142857… and 9/7 = 1.285714….
  - The first row being (7,8,9)/7 implies partial pivoting.

### EX-MAT-11 — Rref of the same MatA
- Keys: `[AC] [SHIFT][4](MATRIX)[▼][2](Rref) [SHIFT][4](MATRIX)[3](MatA) [)] [=]`
- Expected **S** (`MAT_02`): rows `■ 0 −1` / `0 1 2` / `0 0 0`, with (1,1) = 1 **C**.
  So [[1,0,−1],[0,1,2],[0,0,0]].

---

## TABLE (pp.68–70)

### EX-TABLE-01 — f(x) = x² + 1/2, g(x) = x² − 1/2, −1 ≤ x ≤ 1, step 0,5 (MthIO-MathO)
- Keys: `[MODE][7](TABLE)` → **S** `f(X)=` with the cursor.
- Keys: `[SHIFT][MODE](SETUP)[▼][5](TABLE)[2](f(x),g(x))`. This is already the ZA default; the guide sets it anyway.
- Keys: `[ALPHA][)](X) [x²] [+] 1 [■/■] 2` → **S** `f(X)=X²+1/2`, the fraction stacked.
- Keys: `[=]` → **S** `g(X)=` with the cursor.
- Keys: `[ALPHA][)](X) [x²] [-] 1 [■/■] 2` → **S** `g(X)=X²−1/2`
- Keys: `[=]`, then `Start?` `[(−)]1[=]`, `End?` `1[=]`, `Step?` `0[,]5[=]`
- Expected **S** (`CY849_TABLE_03`): columns `X | F(X) | G(X)`, cursor on row 1 X, bottom `-1`.

  | Row | X | F(X) | G(X) | Status |
  |---|---|---|---|---|
  | 1 | -1 | 1,5 | 0,5 | **S** |
  | 2 | -0,5 | 0,75 | -0,25 | **S** |
  | 3 | 0 | 0,5 | -0,5 | **S** |
  | 4 | 0,5 | 0,75 | -0,25 | **C** |
  | 5 | 1 | 1,5 | 0,5 | **C** |

- After this, variable X has been changed. Its exact value is UNKNOWN; INFERRED to be the last X used.

---

## VECTOR (pp.71–74)

Shared state: VctA = (1,2), VctB = (3,4), VctC = (2,−1,2).

### EX-VEC-01 — define VctA and VctB, VctA+VctB
- Keys: `[MODE][8](VECTOR)` → **S** `Vector?` / `1:VctA  2:VctB` / `3:VctC`
- Keys: `[1](VctA)[2](2)` → **S** `VCT`, letter `A`, `[■ 0]`, bottom `0`.
- Keys: `1[=]2[=]`
- Keys **as printed**: `[SHIFT][5](VECTOR)[2](Data)[2](VctB)[2](2)`, then `3[=]4[=]`
- Keys: `[AC] [SHIFT][5](VECTOR)[3](VctA) [+] [SHIFT][5](VECTOR)[4](VctB) [=]`
  - Calc **S**: `VctA+VctB`.
  - Result **S**: VctAns `Ans [■ 6]`, bottom `4`. So (4,6).

### EX-VEC-02 — define VctC (3-D)
- Keys: `[SHIFT][5](VECTOR)[1](Dim)[3](VctC)[1](3)`, then `2[=][(−)]1[=]2[=]`
- Expected **S** (`VECTOR_05`): letter `C`, `[2 −1 ■]`, the cursor stays on element 3, bottom `2`.

### EX-VEC-03 — 3×VctA, then −VctB using VctAns
- Keys: `[AC] 3 [×] [SHIFT][5](VECTOR)[3](VctA) [=]` → **S** VctAns `[■ 6]`, bottom `3`. So (3,6).
- Keys: `[-] [SHIFT][5](VECTOR)[4](VctB) [=]`
  - `[-]` on the VctAns screen starts `VctAns−`.
  - Expected **S**: VctAns `[■ 2]`, bottom `0`. So (0,2).

### EX-VEC-04 — dot product
- Keys: `[AC] [SHIFT][5](VECTOR)[3](VctA) [SHIFT][5](VECTOR)[7](Dot) [SHIFT][5](VECTOR)[4](VctB) [=]`
- Expected **S**: input `VctA•VctB`, result `11`, a scalar on the calculation line.

### EX-VEC-05 — cross product of two 2-D vectors
- Keys: `[AC] [SHIFT][5](VECTOR)[3](VctA) [×] [SHIFT][5](VECTOR)[4](VctB) [=]`
- Expected **S**: VctAns `[■ 0 −2]`, bottom `0`. So **(0,0,−2)**: the result is 3-D.

### EX-VEC-06 — magnitude of VctC
- Keys: `[AC] [SHIFT][hyp](Abs) [SHIFT][5](VECTOR)[5](VctC) [)] [=]`
- Expected **S**: input `Abs(VctC)`, result `3`.

### EX-VEC-07 — angle between VctA and VctB, Fix 3, Deg
- Setup: `[SHIFT][MODE](SETUP)[6](Fix)[3]`
- Keys:
  `[AC] [(] [SHIFT][5][3](VctA) [SHIFT][5][7](Dot) [SHIFT][5][4](VctB) [)] [÷] [(] [SHIFT][hyp](Abs) [SHIFT][5][3](VctA) [)] [SHIFT][hyp](Abs) [SHIFT][5][4](VctB) [)] [)] [=]`
  - Screen **S**: input `(VctA•VctB)÷(Ab▶`, truncated with a scroll arrow. Result `0,984`, with
    `VCT` and `FIX` indicators.
- Keys: `[SHIFT][cos](cos⁻¹) [Ans] [)] [=]`
  - Screen **S**: input `cos⁻¹(Ans)`. Result **`10,305`**.
- **C**: Ans = 0.98386991 and the angle = 10.30485°. cos⁻¹(0.984) would be 10.26°, so Ans keeps full
  precision under Fix.

---

## DIST (pp.74–78)

### EX-DIST-01 — Normal PD, x = 36, σ = 2, μ = 35
- Keys: `[MODE][▼][1](DIST)`
  - Screen **S**: `1:Normal PD` / `2:Normal CD` / `3:Inverse Normal` / `4:Binomial PD`, with `▼` at top right.
- Keys: `[1](Normal PD)` → **S** `Normal PD:x?`, bottom `0`.
- Keys: `36 [=]` → **S** `Normal PD:σ?`, bottom `1`.
- Keys: `2 [=]` → **S** `Normal PD:μ?`, bottom `0`.
- Keys: `35 [=]` → **S** `p=` / **`0,1760326634`**
- After the result, `[=]` or `[AC]` returns to the `x?` prompt.
- **C**: 0.17603266338.

### EX-DIST-02 — Binomial PD, list {10;11;12;13;14}, N = 15, p = 0,6
- Keys: `[MODE][▼][1](DIST)[4](Binomial PD)` → **S** `1:List  2:Var`
- Keys: `[1](List)`
  - Screen **S**: List Screen, headers `X | Ans`, tag `Bpd`, rows 1–3 empty, cursor on row 1 X.
- Keys: `10[=]11[=]12[=]13[=]14[=]`
  - Screen **S**: rows 4–6 visible, X 13 and 14, cursor on the empty row 6.
- Keys: `[=]` → **S** `Binomial PD:N?`, bottom `0`.
- Keys: `15 [=]` → **S** `Binomial PD:p?`, bottom `0`.
- Keys: `0 [,] 6 [=]`
  - Screen **S**: rows 1–3 show X 10/11/12 and Ans `0,1859` / `0,1267` / `0,0633`. The cursor is on
    row 1 X and the bottom shows `10`.
- Keys: `[▶] [▼] [▼] [▼] [▼]`
  - Screen **S**: rows 3–5 show X 12/13/14 and Ans `0,0633` / `0,0219` / (highlighted). The bottom
    shows **`0,00470184984`**.
- Printed results **P**:

  | x | Value |
  |---|---|
  | 10 | ≒ `0,18594` |
  | 11 | ≒ `0,12678` |
  | 12 | ≒ `0,063388` |
  | 13 | ≒ `0,021942` |
  | 14 | ≒ `4,7018 × 10⁻³` |

- **C** (exact): 0.185937845, 0.126775803, 0.063387902, 0.021941966, 0.004701849846.
  - The x = 14 screen value has **9** significant digits where the exact value would round to
    `0,004701849846`. Treat the screen value as the target and confirm on the emulator.
- After the results, `[=]` goes to the N prompt. `[AC]` goes to the List Screen, and the samples are kept.

---

## INEQ (pp.78–80)

General rule seen in every INEQ (and EQN) example: once the coefficients are in, a `[=]` pressed with
**no new value typed** solves. It does so even when the cursor is not on the last cell, as in
EX-INEQ-03 and 04, where trailing coefficients are left at 0. INFERRED from the sequences.

### EX-INEQ-01 — x² + 2x − 3 < 0 (MthIO-MathO)
- Keys: `[MODE][▼][2](INEQ)[1](aX²+bX+c)`
  - Screen **S**: `1:aX²+bX+c>0` / `2:aX²+bX+c<0` / `3:aX²+bX+c≥0` / `4:aX²+bX+c≤0`
- Keys: `[2](aX²+bX+c<0)`
  - Screen **S**: editor `a b c`, `[■ 0 0]`, the template `aX²+bX+c<0` underneath, bottom `0`.
- Keys: `1[=]2[=][(−)]3[=]` → **S** `[1 2 ■]`, bottom `-3`.
- Keys: `[=]` → **S** `A<X<B` / `-3<X<1`

### EX-INEQ-02 — x² + 2x − 3 ≥ 0 (MthIO-MathO)
- Keys: `[MODE][▼][2](INEQ)[1](aX²+bX+c)[3](aX²+bX+c≥0)`, then `1[=]2[=][(−)]3[=]`
  - Screen **S**: template `aX²+bX+c≥0`, bottom `-3`.
- Keys: `[=]` → **S** `X≤A;B≤X` / `X≤-3;1≤X`
- In **Linear Display** the guide shows instead **S** (`INEQ_08`): `X≤A;B≤X` / `A=  -3` / `B=  1`.

### EX-INEQ-03 — 2x³ − 3x² ≥ 0 (MthIO-MathO)
- Keys: `[MODE][▼][2](INEQ)[2](aX³+bX²+cX+d)[3](aX³+bX²+cX+d≥0)`, then `2[=][(−)]3[=]`
  - Screen **S**: headers `a b c`, `[2 −3 ■`, template `aX³+bX²+cX+d≥0`, bottom `0`.
- Keys: `[=]` → **S** `X=A;B≤X` / `X=0;3/2≤X`, with 3/2 stacked.

### EX-INEQ-04 — 3x³ + 3x² − x > 0 (MthIO-MathO)
- Keys: `[MODE][▼][2](INEQ)[2](aX³+bX²+cX+d)[1](aX³+bX²+cX+d>0)`, then `3[=]3[=][(−)]1[=]`
  - Screen **S**: headers `b c d` (scrolled), `3 −1 ■]`, template `aX³+bX²+cX+d>0`, bottom `0`.
- Keys: `[=]`
  - Screen **S**: `A<X<B;C<X` / `(-3-√21)/6<X<0;(-3+…` with a `▶` marker at the right edge.
- Keys: `[▶][▶][▶]`
  - Screen **S**: the line scrolled, `◀…21)/6<X<0;(-3+√21)/6<X`.
- In **Linear Display** **S** (`INEQ_14`): `A<X<B;C<X` / `A=  -1,263762616` / `B=  0` / `C=  0,2637626158`.
- **C**: −1.2637626158, 0.2637626158.

### EX-INEQ-05 — x² ≥ 0 (special display)
- Keys: `[MODE][▼][2](INEQ)[1](aX²+bX+c)[3](aX²+bX+c≥0)`, then `1[=]0[=]0[=] [=]`
- Expected **S**: `All Real Numbers`

### EX-INEQ-06 — x² < 0 (stated, no keys printed)
- *Keys INFERRED:* `[MODE][▼][2][1][2](aX²+bX+c<0)`, then `1[=]0[=]0[=] [=]`
- Expected **P** (text): `No-Solution`

---

## RATIO (pp.81–82)

### EX-RATIO-01 — 3 : 8 = X : 12 (procedure walk-through)
- Keys: `[MODE][▼][3](RATIO)` → **S** `1:a:b=X:d` / `2:a:b=c:X`
- Keys: `[1](a:b=X:d)`, then `3[=]8[=]12[=]`
  - Screen **S**: headers `a b d`, `[3 8 ■]`, template `a:b=X:d`, bottom `12`.
- Keys: `[=]` → **S** `X=` / `9/2`, stacked.
- `[=]` again returns to the editor.

### EX-RATIO-02 — 1 : 2 = X : 10
- Keys: `[MODE][▼][3](RATIO)[1](a:b=X:d)`, then `1[=]2[=]10[=]`
  - Screen **S**: `[1 2 ■]`, template `a:b=X:d`, bottom `10`.
- Keys: `[=]` → **S** `X=` / `5`

### EX-RATIO-03 — 1 : 2 = 10 : X
- Keys: `[MODE][▼][3](RATIO)[2](a:b=c:X)`, then `1[=]2[=]10[=]`
  - Screen **S**: headers `a b c`, template `a:b=c:X`, bottom `10`.
- Keys: `[=]` → **S** `X=` / `20`

### EX-RATIO-04 — zero coefficient (stated rule, no example printed)
- Any RATIO calculation with a 0 among the coefficients → **P** (text) `Math ERROR`.
- Suggested vector, *INFERRED*: `[MODE][▼][3][1]`, then `0[=]2[=]10[=] [=]` → `Math ERROR`.

---

## SA handout vectors (added by the cross-check)

Source: James Ralph (Casio's SA distributor) teacher handout hosted by Casio at
<https://edumaterial.casio-intl.com/resource/contents/1368>, screen captures of the fx-991ZA PLUS II
(emulator). Not the user's guide, so kept apart. **S** = read off a capture; **C** = computed by me.

### EX-SA-EQN-01 — 2x − 3y = 3, 4x − 2y = 10 (slide 15)
- Keys as printed: `[MODE][5](EQN)[1]`, then `2[=][−]3[=]3[=]4[=][−]2[=]10[=]`. ⚠ The slide shows the
  **subtraction** key, not `[(−)]`, for the negative coefficients; the editor cell still reads −3.
  Whether the binary-minus key works as a sign at the start of an editor cell is worth one emulator
  check (INFERRED: yes, it parses `−3`).
- `[=]` → **S** `X=` / `3`; `[=]` → **S** `Y=` / `1`. Label top-left, value bottom-right, `▼` then `▲` lit.

### EX-SA-EQN-02 — x² + 8x + 12 = 0 (slide 16)
- Keys: `[MODE][5](EQN)[3]`, then `1[=]8[=]12[=]`, then `[=]` four times.
- **S**: `X₁=` `-2`, `X₂=` `-6`, `X-Value Minimum=` `-4`, `Y-Value Minimum=` `-4`.

### EX-SA-INEQ-01 — x³ − 5x² + 6x > 0 (slide 10)
- Keys: `[MODE][▼][2](INEQ)[2](cubic)[1](>0)`, then `1[=][−]5[=]6[=]0[=]` (same minus-key caveat), then `[=]`.
- **S**: template `A<X<B;C<X`, values `0<X<2;3<X`.

### EX-SA-RATIO-01 — 1,5 : 12,5 = 5 : X (slide 6)
- Keys: `[MODE][▼][3](RATIO)[2](a:b=c:X)`, then `1[,]5[=]12[,]50[=]5[=]`, then `[=]`.
- **S**: `X=` / stacked `125/3`; then `[S⇔D]` → `41,66666667`.

### EX-SA-STAT-01 — five-number summary, 1-VAR, 12 values (slides 17–18)
- Data: 2182, 2323, 2267, 2334, 2346, 2175, 2293, 2263, 2215, 2271, 2018, 2254 (FREQ off).
- Keys: `[MODE][3](STAT)[1](1-VAR)`, the twelve values each followed by `[=]`, then
  `[AC][SHIFT][1](STAT/DIST)[6](MinMax)[1](minX)[=]` → **S** `2018`;
  `[AC][SHIFT][1][6][3](Q1)[=]` → **S** `2198,5`.
- **C** (not on the slides): med = 2265, Q3 = 2308, maxX = 2346 (even n, halves exclude nothing).
- Menus captured: `1:Type  2:Data` / `3:Sum   4:Var` / `5:Distr 6:MinMax`; MinMax `1:minX  2:maxX` /
  `3:Q1    4:med` / `5:Q3`.

---

## Count

| Chapter | Vectors |
|---|---|
| CONST | 2 |
| CONV | 3 |
| CMPLX | 10 |
| STAT | 5 |
| BASE-N | 11 |
| EQN | 6 |
| MATRIX | 11 |
| TABLE | 1 |
| VECTOR | 7 |
| DIST | 2 |
| INEQ | 6 |
| RATIO | 4 |
| **Total** | **68** |
| SA handout vectors (cross-check, separate source) | 5 |

Figures that are UNREADABLE in the guide: **none**. Every value the guide shows could be read. Values
hidden under the cursor highlight are marked **C** and were computed instead.
