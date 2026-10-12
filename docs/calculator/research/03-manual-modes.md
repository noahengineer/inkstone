# 03 — fx-991ZA PLUS II User's Guide: every mode except COMP, plus CONST and CONV

Research note for the Inkstone calculator. **Scope:** what Casio's official user's guide for the
**fx-991ZA PLUS II** says about the calculation modes other than COMP (CMPLX, STAT, BASE-N, EQN,
MATRIX, TABLE, VECTOR, DIST, INEQ, RATIO), plus the scientific constants (CONST) and metric
conversions (CONV). Every worked example from those chapters is written out as a numbered test
vector in the companion file [`05-examples-modes.md`](05-examples-modes.md).

The manual's prose is copyrighted, so this file records facts in my own words: key paths, menu
item names and numbers, screen texts, ranges, and values. Nothing here is pasted manual text.

---

## 0. Sources and confidence labels

| Tag | Source | What it is |
|---|---|---|
| **[G]** | <https://www.casio.com/content/dam/casio/global/support/manuals/calculators/pdf/004-en/f/fx-991ZA_PLUSII_EN.pdf> | **Official** user's guide PDF, *fx-991ZA PLUS II, User's Guide (NATURAL-V.P.A.M.)*, 96 pages, author CASIO COMPUTER CO., LTD., PDF created 2020-05-20. Printed page numbers are used below (PDF page = printed page + 1). |
| **[H]** | <https://support.casio.com/global/en/calc/manual/fx-991ZAPLUSII_en/> | **Official** HTML version of the same guide (same text and the same screen images). Key icons there are images named by a private-use code point; the mapping is in Appendix B. Used to read key sequences as text. |
| **[C14]** | <https://physics.nist.gov/cuu/Constants/ArchiveASCII/allascii_2014.txt> | NIST's archived **CODATA 2014** table, which [G] p.46 names as the source of the constants. |
| **[SP811]** | <https://www.nist.gov/pml/special-publication-811/nist-guide-si-appendix-b-conversion-factors/nist-guide-si-appendix-b8> | NIST SP 811 Appendix B.8, the conversion-factor table, which [G] p.47 names as the source of the conversions. |
| **[ES2]** | fx-115ES PLUS / fx-991ES PLUS C *(2nd edition)* user's guide, CASIO, PDF dated 2019-10-28, a copy downloaded into this research's scratchpad by a sibling researcher (`guide02_cmp_991ESPLUS2_nz.pdf`) | **Official, but a different model.** I used it only for comparison, never as the authority for the ZA PLUS II. ⚠ **Tag collision (cross-check):** in *this* file [ES2] is the North-American fx-115ES PLUS / fx-991ES PLUS C 2nd edition, which file 06 calls **[C115]**. File 06's **[ES2]** is a different guide: the international fx-570ES PLUS / fx-991ES PLUS / fx-9910NG PLUS 2nd edition. Read each file's [ES2] in its own sense. |
| **[SA]** *(added by the cross-check)* | James Ralph (Casio's SA distributor) teacher handout hosted by Casio: <https://edumaterial.casio-intl.com/resource/contents/1368> | Screen captures of the fx-991ZA PLUS II emulator, embedded as exact 2×/4× pixel copies. Treated as VERIFIED for what a capture shows (as file 02 does), SECONDARY for anything else on the slides. |
| **[GYX]** *(added by the cross-check)* | String and constant tables of the community decompilation of Casio's ES PLUS **GYX** firmware (fx-570/991ES PLUS, 1st edition): <https://codeberg.org/gwetm/gyx-decomp> (`src/asm/generals.asm`, `src/asm/unk5.asm`); file 07 [S13] | **SECONDARY**, sibling firmware. Used only for on-screen menu text and table values. Its `Reset All?` / `Math ERROR` / `Fix 0~9?` strings match ZA screen captures character for character, so its menu texts are a strong guide; ZA-only (2nd-edition) menus are not in it. |

Confidence on every fact:

- **VERIFIED**: I read it in [G]/[H], either the text or a screen image. For screen images I say "(screen)".
- **SECONDARY**: from a non-Casio or a different-model source.
- **INFERRED**: my own reasoning, with the reasoning given. Treat it as a hypothesis to check on the
  Casio emulator ([G] does not cover it). Casio South Africa says an fx-991ZA PLUS II emulator is
  downloadable as a 90-day trial (<https://edumaterial.casio-intl.com/resource/contents/1368>). The
  emulator is the cheapest way to settle almost every UNKNOWN below.
- **UNKNOWN**: not settled. I say what would settle it.

---

## 1. What [G] establishes about the model (only the parts that matter for modes)

- **Eleven calculation modes**, reached from `[MODE]`. VERIFIED, [G] p.10:

  | Key | Mode | Key | Mode |
  |---|---|---|---|
  | `[MODE][1]` | COMP | `[MODE][7]` | TABLE |
  | `[MODE][2]` | CMPLX | `[MODE][8]` | VECTOR |
  | `[MODE][3]` | STAT | `[MODE][▼][1]` | DIST |
  | `[MODE][4]` | BASE-N | `[MODE][▼][2]` | INEQ |
  | `[MODE][5]` | EQN | `[MODE][▼][3]` | RATIO |
  | `[MODE][6]` | MATRIX | | |

  The MODE menu therefore has two pages (8 items, then 3). The default mode is COMP.
  The on-screen text of the two MODE-menu pages is **not shown** in [G], but the Casio-hosted [SA]
  handout captures both (file 02 §4): inverted-digit item numbers, no colon, small font, four rows of
  two (`■COMP ■CMPLX` …) with ▼ / ▲ at top right. VERIFIED from those captures (cross-check).
- **It is ES-PLUS-family hardware and firmware, not ClassWiz.** There is no `OPTN` or `MENU` key.
  Mode-specific menus are `[SHIFT]` + a number key: `SHIFT 1` STAT/DIST, `SHIFT 2` CMPLX,
  `SHIFT 3` BASE, `SHIFT 4` MATRIX, `SHIFT 5` VECTOR, `SHIFT 7` CONST, `SHIFT 8` CONV, `SHIFT 9` CLR.
  The display has two lines, with Natural Display (`Math` indicator). VERIFIED, [G] throughout.
- **Comparison with [ES2]:** the mode chapters of [G] read almost word for word like the fx-991ES PLUS
  C / fx-115ES PLUS 2nd edition guide. The differences that matter here are below. VERIFIED by comparing the two tables of contents and the setup tables.
  - ZA PLUS II **has RATIO**, which [ES2] does not.
  - ZA PLUS II **has no VERIF mode**, which [ES2] does have. [G] does not mention VERIF anywhere.
  - [G]'s table of contents has no recurring-decimal section and no `Int`/`Intg`. Those are COMP topics and outside this file's scope.
  - ZA PLUS II's setup has **no decimal-point setting**. [ES2] has "Decimal Point: Dot". Every example in [G] uses a **decimal comma**.
  - The **default Number Format is Norm 2** on the ZA PLUS II and Norm 1 on [ES2].
- **There is no SPREADSHEET mode and no VERIF mode.** VERIFIED by absence from [G]'s mode table,
  setup menu and table of contents.

### 1.1 Regional conventions that change what every mode displays

| Fact | Status |
|---|---|
| The decimal separator on screen is a **comma**, e.g. `1,154700538`, `0,1760326634`. | VERIFIED (screens throughout) |
| The decimal-point key is drawn as **`[,]`**, e.g. "0 `[,]` 5" for 0.5. | VERIFIED: [H] key image `57447+.png` and [G] p.70, p.77 |
| The argument / list separator is a **semicolon**: `Pol(x; y)`, `RanInt#(a; b)`, data pairs written `(170; 66)`. INEQ solutions join intervals with `;`, e.g. `X≤-3;1≤X`. | VERIFIED: [G] p.50, p.89–90, screens p.80–81 |
| The setup offers no way to switch to a decimal point. | INFERRED from both setup screens (§2.3), which list no such item |

---

## 2. Conventions that hold across modes

### 2.1 Key names used in this file

`[SHIFT] [ALPHA] [MODE] [AC] [DEL] [=] [▲] [▼] [◀] [▶] [(−)] [°′″] [hyp] [sin] [cos] [tan]
[log] [ln] [x²] [x■]` (power key) `[x⁻¹] [√■] [■/■]` (fraction key) `[ENG] [RCL] [M+] [Ans]
[(] [)] [+] [-] [×] [÷] [,]` (decimal key) `[0]…[9]`. Plain digits inside a sequence (e.g. `170`)
are typed on the number keys, as [G] writes them.

A `(LABEL)` after a key, e.g. `[SHIFT][2](CMPLX)`, names the function or menu item that the key
selects. It is not a separate keystroke. [G] uses the same convention.

Secondary functions that matter in these modes (VERIFIED from [G] key sequences):

| Key | With SHIFT | In a specific mode / ALPHA |
|---|---|---|
| `[1]` | STAT/DIST menu (STAT and DIST modes) | |
| `[2]` | CMPLX menu (CMPLX mode) | |
| `[3]` | BASE menu (BASE-N mode) | |
| `[4]` | MATRIX menu (MATRIX mode) | |
| `[5]` | VECTOR menu (VECTOR mode) | |
| `[7]` | CONST | |
| `[8]` | CONV | |
| `[9]` | CLR | |
| `[MODE]` | SETUP | |
| `[ENG]` | | `i` in CMPLX mode |
| `[(−)]` | `∠` | hex digit **A** in BASE-N. Also variable A, used as the copy target MatA / VctA. |
| `[°′″]` | | hex **B**. Variable B, used as the copy target MatB / VctB. |
| `[hyp]` | `Abs` | hex **C**. Variable C, used as the copy target MatC / VctC. |
| `[sin]` `[cos]` `[tan]` | `sin⁻¹` `cos⁻¹` `tan⁻¹` | hex **D**, **E**, **F** in BASE-N |
| `[x²]` | `x³` | **DEC** in BASE-N |
| `[x■]` | | **HEX** in BASE-N |
| `[log]` | | **BIN** in BASE-N |
| `[ln]` | | **OCT** in BASE-N |
| `[RCL]` | `STO` | |
| `[M+]` | `M-` | |
| `[)]` | | `ALPHA )` = variable **X** (TABLE uses it) |

[G] p.6 says the keycap legends for CMPLX and BASE-N are printed in brackets, coloured to match `i` and
`DEC/HEX/BIN/OCT` respectively. VERIFIED (text). The exact colours are a hardware/looks question and
are not covered here.

### 2.2 Indicators per mode (top line of the LCD)

VERIFIED from [G] p.8 and the screens:

| Mode | Mode indicator | Display format seen on its screens |
|---|---|---|
| CMPLX | `CMPLX` | `Math` shown (Natural Display allowed) |
| STAT | `STAT` | No `Math`. [G] p.12 says STAT forces Linear Display. |
| BASE-N | none on the screens shown | No `Math` (forced Linear). The number base is shown as `Dec`/`Hex`/`Bin`/`Oct` at the right of the screen. |
| EQN | none | `Math` shown |
| MATRIX | `MAT` | No `Math` (forced Linear) |
| TABLE | none | `Math` shown |
| VECTOR | `VCT` | No `Math` (forced Linear) |
| DIST | none | No `Math` on any DIST screen in [G]. INFERRED: DIST also displays in Linear form, although [G] p.12's forced-Linear list names only STAT, BASE-N, MATRIX, VECTOR. |
| INEQ | none | `Math` shown |
| RATIO | none | `Math` shown |

The angle-unit indicator (a boxed `D` for Deg) appears on most screens. `FIX` appears when Fix is set.
`▲`/`▼` in the top right means more history or menu exists above or below.

### 2.3 Setup items that belong to modes

`[SHIFT][MODE](SETUP)` opens two pages, switched with `▼`/`▲`. VERIFIED (screen `06_Setup_01`):

```
Page 1                       Page 2
1:MthIO   2:LineIO      ▼    1:ab/c    2:d/c        ▲
3:Deg     4:Rad              3:CMPLX   4:STAT
5:Gra     6:Fix              5:TABLE   6:APO
7:Sci     8:Norm             7:◀CONT▶
```

| Setting | Keys | Options | **ZA default** |
|---|---|---|---|
| Complex format | `SETUP ▼ 3(CMPLX)` then `1`/`2` | `a+bi` / `r∠θ` | `a+bi` |
| Stat format (FREQ column) | `SETUP ▼ 4(STAT)` then `1`/`2` | `ON` / `OFF` | `OFF` |
| Table format | `SETUP ▼ 5(TABLE)` then `1`/`2` | `f(x)` / `f(x),g(x)` | **`f(x),g(x)`** |
| Display | `SETUP 1(MthIO)` then `1(MathO)`/`2(LineO)`, or `SETUP 2(LineIO)` | | MthIO-MathO |
| Angle | `SETUP 3/4/5` | Deg/Rad/Gra | Deg |
| Number format | `SETUP 6(Fix)` 0–9, `7(Sci)` 0–9, `8(Norm)` 1/2 | | **Norm 2** |
| Fraction | `SETUP ▼ 1/2` | ab/c / d/c | **d/c** |

All VERIFIED, [G] pp.11–15. Norm 1 uses exponential display when |x| < 10⁻² or |x| ≥ 10¹⁰.
Norm 2 uses it when |x| < 10⁻⁹ or |x| ≥ 10¹⁰.

Mode-relevant side effects (VERIFIED):

- Entering STAT, BASE-N, MATRIX or VECTOR switches the display to Linear automatically ([G] p.12).
- Changing the Stat Format **deletes all Statistics Editor data** ([G] p.51).
- In TABLE mode, opening setup and switching between Natural and Linear Display **deletes the
  table functions** ([G] p.70).
- `[SHIFT][9](CLR)[1](Setup)[=](Yes)` puts the mode back to COMP and resets the setup. Initialising
  with `CLR 3(All)` also clears memory ([G] p.4, p.15).

### 2.4 Rules shared by every editor

VERIFIED: [G] p.51, 63, 66, 72, 79, 80, 81. The rules apply to the Statistics Editor, the Coefficient
Editor (EQN/INEQ/RATIO), the Matrix Editor and the Vector Editor.

- These **cannot be used inside an editor cell**: `[M+]`, `[SHIFT][M+](M-)`, `[SHIFT][RCL](STO)`,
  `Pol`, `Rec`, `÷R`, and multi-statements. Any other expression can be typed into a cell and is
  evaluated when `[=]` is pressed. That last point is INFERRED from the restriction list.
- To edit a cell, move the cursor onto it, type the new value and press `[=]`.
- The **bottom-right of the screen shows the full value of the cell under the cursor**. Cells show
  a shortened form. VERIFIED from screens: Matrix Editor, Vector Editor, Coefficient Editor,
  Statistics Editor, DIST List Screen, TABLE.
- After `[=]` on the **last** cell, the cursor stays on that cell. VERIFIED, screens
  `EQN_2`, `EQN_3`, `MATRIX_07`, `VECTOR_05`, `RATIO_02`.
- Cell text is **truncated**, not rounded, to fit. In Ref (§7) 1.142857… shows as `1,1428`, where
  rounding would give `1,1429`. VERIFIED (screen `MAT_01`). The general truncation rule and the cell
  width are INFERRED from this one example.
- **In the Coefficient Editors (EQN/INEQ/RATIO), a `[=]` pressed with nothing newly typed solves**,
  even when the cursor is not on the last cell. In INEQ examples 3 and 4 the trailing coefficients
  are left at 0 and a bare `[=]` on the cursor cell produces the solution. INFERRED from [G]'s key
  sequences pp.64, 79–82; the rule is never stated in words. In the DIST List Screen a bare `[=]`
  on the empty row moves on to the parameter prompts instead. VERIFIED, EX-DIST-02. What a bare
  `[=]` does in the Statistics Editor is UNKNOWN.

---

## 3. CMPLX — complex numbers

**Enter:** `[MODE][2]`. The screen is an empty calculation line with `CMPLX`, `D`, `Math`. VERIFIED, p.48.

- Input either rectangular `a+bi` (`i` = `[ENG]`) or polar `r∠θ` (`∠` = `[SHIFT][(−)]`). VERIFIED.
- Results follow the setup's complex format, `a+bi` or `r∠θ`. VERIFIED.
- **Display rules** (VERIFIED, p.48):
  - θ of a polar result lies in **−180° < θ ≤ 180°**. The equivalent range in Rad/Gra is INFERRED.
  - Set the angle unit **before** a polar input or output calculation.
  - In **Linear Display** a complex result is shown on **two lines**: `a` and `bi`, or `r` and `θ`.
  - In MathO, results can be exact forms such as `√2+√2i` or `1/2+1/2 i`.
- **CMPLX menu** `[SHIFT][2](CMPLX)`. VERIFIED from the key sequences, p.49:

  | # | Item | Effect |
  |---|---|---|
  | 1 | `arg` | argument function `arg(` |
  | 2 | `Conjg` | conjugate function `Conjg(` |
  | 3 | `▶r∠θ` | postfix command: show this result in polar form |
  | 4 | `▶a+bi` | postfix command: show this result in rectangular form |

  The menu screen itself is not shown in [G]. Layout, SECONDARY ([GYX] strings): `1:arg   2:Conjg` /
  `3:▶r∠θ  4:▶a+bi` — two lines, two items each.
- `▶r∠θ` / `▶a+bi` go at the **end** of an expression and override the setup for that one result. VERIFIED.
- `Abs(` (`[SHIFT][hyp]`) gives the modulus. [G]'s example leaves off the closing parenthesis:
  `Abs(1+i)` with no `)` returns `√2`. VERIFIED, p.49.
- Not covered by [G] (all UNKNOWN):
  - which functions are refused in CMPLX mode (∫, d/dx, Σ, Pol/Rec, BASE…);
  - what happens with a complex argument to `sin`, `log` and similar;
  - the error raised.

  Settle on the emulator.

Examples: EX-CMPLX-01 … 10 in `05-examples-modes.md`.

---

## 4. STAT — statistics and regression

**Enter:** `[MODE][3]`. A type menu appears. VERIFIED (screen `img36`, p.50):

```
1:1-VAR   2:A+BX
3:_+CX²   4:ln X
5:e^X     6:A•B^X
7:A•X^B   8:1/X
```

| # | Menu text | Model | Formula |
|---|---|---|---|
| 1 | `1-VAR` | single variable X | — |
| 2 | `A+BX` | linear | y = A + Bx |
| 3 | `_+CX²` | quadratic | y = A + Bx + Cx² |
| 4 | `ln X` | logarithmic | y = A + B·ln x |
| 5 | `e^X` | e-exponential | y = A·e^(Bx) |
| 6 | `A•B^X` | ab-exponential | y = A·Bˣ |
| 7 | `A•X^B` | power | y = A·x^B |
| 8 | `1/X` | inverse | y = A + B/x |

Choosing a type opens the **Statistics Editor**. VERIFIED.

### 4.1 Statistics Editor

VERIFIED, pp.50–52 and screens `img37`–`img39`, `img42`, `img43`, `img44`.

- Columns:
  - `X` for 1-VAR;
  - `X`, `Y` for the paired types;
  - `FREQ` added when Stat Format = ON.

  Column headers sit on the top line. Row numbers run down the left edge, three rows are visible at
  a time, and the bottom line shows the value of the current cell.
- **Row capacity** (VERIFIED as printed; the same figures appear in [ES2]):

  | Columns | Rows |
  |---|---|
  | X only | 40 |
  | X, Y | 40 |
  | X, FREQ | 20 |
  | X, Y, FREQ | 26 |

  The figures are odd: two columns hold fewer rows than three. UNKNOWN which is right; check the
  emulator. [G] prints the figures as above.
- Typing a value and pressing `[=]` stores it and moves down one row. Once X values exist, the
  untouched cells of the `Y` column show **0**, and `FREQ` cells default to **1**. VERIFIED, screens
  `img38` and `img42`.
- [G]'s sequences, written exactly as printed:
  - `170 = 173 = 179 = ▼ ▶`, after which the cursor is in **row 1 of Y**;
  - `1=2=3=4=5= ▼ ▶`, after which the cursor is in row 1 of FREQ.

  VERIFIED (key icons and the resulting screen). INFERRED: `▼` from the empty row after the last
  data **wraps to row 1**. That is the only way the shown screen follows from the shown keys.
- Edit commands. VERIFIED:
  - **delete a row**: put the cursor on it and press `[DEL]`;
  - **insert a row**: `[SHIFT][1](STAT/DIST)[3](Edit)[1](Ins)`;
  - **delete all**: `[SHIFT][1][3](Edit)[2](Del-A)`.

  So when the **editor** is on screen, item 3 of the STAT/DIST menu is `Edit`. INFERRED: the editor
  menu is different from the calculation-screen menu in §4.2. [G] shows neither the editor menu
  screen nor what items 4 onwards are there. UNKNOWN.
- **Everything in the editor is deleted** when you:
  - leave STAT mode;
  - switch between 1-VAR and a paired type;
  - change the Stat Format setting.

  VERIFIED.
- Show the editor again with `[SHIFT][1](STAT/DIST)[2](Data)`. VERIFIED.

### 4.2 Statistics Calculation Screen and the STAT/DIST menu

`[AC]` in the editor goes to the Statistics Calculation Screen, an ordinary calculation line.
`[SHIFT][1](STAT/DIST)` there opens one of these menus. VERIFIED (screens `img40`/`img41`, p.52):

```
1-VAR:                        paired types:
1:Type    2:Data              1:Type    2:Data
3:Sum     4:Var               3:Sum     4:Var
5:Distr   6:MinMax            5:Reg     6:MinMax
```

`Type` returns to the type menu (§4 top). `Data` opens the editor. VERIFIED.

The 1-VAR menu and its MinMax sub-menu are also captured on a real ZA PLUS II screen in the [SA]
handout (slides 17–18): `1:Type  2:Data` / `3:Sum   4:Var` / `5:Distr 6:MinMax`, and MinMax =
`1:minX  2:maxX` / `3:Q1    4:med` / `5:Q3`, both in the small font with colons. VERIFIED
(cross-check). The same slide computes Q1 = `2198,5` for 12 values (even n; lower-half median).
The other sub-menu layouts (Sum, Var, Distr, Reg) follow the [GYX] strings, two items per line, e.g.
`1:Σx²   2:Σx` / `3:Σy²   4:Σy` / `5:Σxy   6:Σx³` / `7:Σx²y  8:Σx⁴` and `1:A     2:B` / `3:r     4:x̂` /
`5:ŷ` (SECONDARY). The editor's Edit sub-menu is `1:Ins   2:Del-A` (SECONDARY).

**1-VAR sub-menus** (VERIFIED, pp.53–54; the sub-menu screens themselves are not shown):

| Sub-menu | Items |
|---|---|
| `3 Sum` | 1 `Σx²`, 2 `Σx` |
| `4 Var` | 1 `n`, 2 `x̄`, 3 `σx` (population SD), 4 `sx` (sample SD) |
| `5 Distr` | 1 `P(`, 2 `Q(`, 3 `R(`, 4 `▶t` |
| `6 MinMax` | 1 `minX`, 2 `maxX`, 3 `Q1`, 4 `med`, 5 `Q3` |

**Paired-variable sub-menus.** [G] gives these under "linear regression (A+BX)". INFERRED that they apply
to every paired type except where §4.3 differs. VERIFIED, pp.54–56:

| Sub-menu | Items |
|---|---|
| `3 Sum` | 1 `Σx²`, 2 `Σx`, 3 `Σy²`, 4 `Σy`, 5 `Σxy`, 6 `Σx³`, 7 `Σx²y`, 8 `Σx⁴` |
| `4 Var` | 1 `n`, 2 `x̄`, 3 `σx`, 4 `sx`, 5 `ȳ`, 6 `σy`, 7 `sy` |
| `5 Reg` | 1 `A`, 2 `B`, 3 `r`, 4 `x̂`, 5 `ŷ` |
| `6 MinMax` | 1 `minX`, 2 `maxX`, 3 `minY`, 4 `maxY` |

Paired types have **no quartiles or median**. VERIFIED (MinMax list).

### 4.3 Quadratic regression (`_+CX²`) differences

`5 Reg` = 1 `A`, 2 `B`, 3 `C`, 4 `x̂₁`, 5 `x̂₂`, 6 `ŷ`. There is **no `r`**. VERIFIED, p.56.

### 4.4 Estimates, P/Q/R and ▶t

- `x̂`, `x̂₁`, `x̂₂`, `ŷ` are **postfix commands**, not variables. The argument goes before them:
  `-130 x̂` gives x for y = −130. VERIFIED, p.56–57.
- `P(t)`, `Q(t)`, `R(t)`: standard-normal probabilities. VERIFIED from the illustration, p.58.
  - `P(t)` is the area from −∞ to t.
  - `Q(t)` is the area between 0 and t.
  - `R(t)` is the area from t to +∞.
  - What `Q(t)` returns for t < 0 (a signed or an absolute area) is UNKNOWN.
- `X▶t` is postfix and gives the normalised variate **(X − x̄)/σx**, using the **population** σx.
  VERIFIED, p.58. Checked numerically with example EX-STAT-05.
- `Distr` exists only for 1-VAR. VERIFIED.
- Regression and estimate calculations can take noticeably long with many data. VERIFIED, p.57.

### 4.5 Formulas (INFERRED; the guide gives none)

[G] contains no statistical formulas. I recomputed [G]'s examples with standard least squares in
Python, and the following **reproduces every printed STAT result**:

- linear r = 0.92285 → `0,923`;
- ln-regression A = −3857.98441, B = 2357.53155, r = 0.99833 → `-3857,984`, `2357,532`, `0,998`;
- x̂(−130) = 4.86131 → `4,861`;
- σx = 1.154700538;
- ▶t = −0.76225 → `-0,762`, and P(−0.76225) = 0.22295 → `0,223`.

So:

- Sxx = Σ(x−x̄)², Syy = Σ(y−ȳ)², Sxy = Σ(x−x̄)(y−ȳ). Frequencies weight every sum, and n = ΣFREQ.
- σx = √(Sxx/n). sx = √(Sxx/(n−1)).
- **Linear**: B = Sxy/Sxx, A = ȳ − B·x̄, r = Sxy/√(Sxx·Syy). Estimates: ŷ = A + Bx, x̂ = (y − A)/B.
- **ln X**: linear regression of y on ln x. x̂ = e^((y−A)/B). *Checked against the example.*
- **e^X**: regress ln y on x, then B = slope and A = e^intercept. *Not checked: [G] has no example.*
- **A•B^X**: regress ln y on x, then A = e^intercept and B = e^slope. *Not checked.*
- **A•X^B**: regress ln y on ln x, then A = e^intercept and B = slope. *Not checked.*
- **1/X**: regress y on 1/x. *Not checked.*
- **Quadratic**: ordinary least squares for A, B, C. x̂₁,₂ = (−B ± √(B² − 4C(A − y)))/(2C). *Not checked.*
  Which root is x̂₁ is UNKNOWN.
- How r is defined for the transformed models: INFERRED to be the correlation of the transformed
  variables. Checked only for ln X.
- **The quartile/median method is UNKNOWN.** The ES-family method differs from the textbook ones, and
  [G] has no worked quartile example. Check on the emulator with an even and an odd n, and with FREQ.

Examples: EX-STAT-01 … 05.

---

## 5. BASE-N — number bases and logic

**Enter:** `[MODE][4]`. The screen shows `Dec` at the right with `0` below it. VERIFIED (screen `BASE_1`).
The initial base is decimal.

### 5.1 Switching base and digit entry

- `[x²]` DEC, `[x■]` HEX, `[log]` BIN, `[ln]` OCT. VERIFIED, p.59.
- Pressing one of these while a **result** is on screen converts that result to the new base. VERIFIED, p.60.
  - Example: 555 → HEX `0000022B` → BIN `0000001000101011` → OCT `00000001053`.
- Hex digits: A `[(−)]`, B `[°′″]`, C `[hyp]`, D `[sin]`, E `[cos]`, F `[tan]`. VERIFIED.
- **Result width and padding** (VERIFIED, screens and p.60):
  - BIN: always 16 digits, zero-padded;
  - HEX: 8 digits, zero-padded;
  - OCT: 11 digits, zero-padded;
  - DEC: no padding.

  The base name is shown above the result on the right.
- **No fractions and no exponents** can be entered. A fractional part in a result is **cut off**
  (truncated). VERIFIED, p.59.

### 5.2 Ranges (two's complement)

VERIFIED, p.60:

| Base | Width | Positive | Negative |
|---|---|---|---|
| BIN | **16 bits** | `0000000000000000`–`0111111111111111` | `1000000000000000`–`1111111111111111` |
| OCT | 32 bits | `00000000000`–`17777777777` | `20000000000`–`37777777777` |
| DEC | 32 bits | −2147483648 … 2147483647 | (plain minus sign) |
| HEX | 32 bits | `00000000`–`7FFFFFFF` | `80000000`–`FFFFFFFF` |

- A negative value in BIN/OCT/HEX is the **two's complement**: the calculator converts to binary,
  negates, and converts back. DEC simply shows a minus sign. VERIFIED, p.62.
- Bitwise operations in binary mode give 16-bit results, e.g. `Not(1010)` = `1111111111110101`. VERIFIED.
- **Out of range: UNKNOWN.** [G] does not say what happens when a result exceeds the range, or when
  a 32-bit DEC value is converted to 16-bit BIN, e.g. DEC 70000 then `BIN`. INFERRED: Math ERROR.
  Check on the emulator.

### 5.3 BASE menu `[SHIFT][3](BASE)`

VERIFIED from the key sequences, pp.60–62. The menu screens are not shown in [G]; their layout is
SECONDARY from [GYX]: page 1 `1:and   2:or` / `3:xor   4:xnor` / `5:Not   6:Neg`, page 2 `1:d     2:h` /
`3:b     4:o`. The base label strings are `Dec`, `Hex`, `Bin`, `Oct`.

| Page | # | Item | Meaning |
|---|---|---|---|
| 1 | 1 | `and` | bitwise AND (infix) |
| 1 | 2 | `or` | bitwise OR (infix) |
| 1 | 3 | `xor` | bitwise XOR (infix) |
| 1 | 4 | `xnor` | bitwise XNOR (infix) |
| 1 | 5 | `Not(` | bitwise complement (function, needs `)`) |
| 1 | 6 | `Neg(` | two's complement (function, needs `)`) |
| 2 (`▼`) | 1 | `d` | prefix: the digits typed after it are decimal |
| 2 | 2 | `h` | prefix: hexadecimal |
| 2 | 3 | `b` | prefix: binary |
| 2 | 4 | `o` | prefix: octal |

- In [G]'s key sequence the `d/h/b/o` command comes **before** the digits. VERIFIED:
  `[SHIFT][3][▼][2](h) 10` means 10₁₆. [G]'s prose says the command goes "immediately following a
  value", which contradicts its own sequence. The **key sequence is the evidence**, because it produces
  the stated result of 36. The on-screen form, e.g. `h10`, is INFERRED.
- Operator precedence of `and/or/xor/xnor` relative to `+ − × ÷`, and among themselves, is not in the
  mode chapter. [G]'s Calculation Priority Sequence (p.87) is outside this file's scope. See the COMP research.
- `CONST` cannot be used in BASE-N. VERIFIED, p.44.
- `CONV` cannot be used in BASE-N. VERIFIED, p.46.

Examples: EX-BASEN-01 … 11.

---

## 6. EQN — equations

**Enter:** `[MODE][5]`. A type menu appears. VERIFIED (screen `EQN_1`, p.62):

```
1:anX+bnY=cn
2:anX+bnY+cnZ=dn
3:aX²+bX+c=0
4:aX³+bX²+cX+d=0
```

The four types are 2×2 linear, 3×3 linear, quadratic and cubic. There is **no quartic.** VERIFIED.

### 6.1 Coefficient Editor

VERIFIED, p.63 and screens `EQN_2`/`EQN_3`.

- The layout is a matrix: row numbers on the left, coefficient letters as column headers, brackets
  around the rows. The bottom line shows the current cell's value.
- At most **three columns** are visible at once. In the 3-unknown editor with the cursor on `d`, the
  headers shown are `b c d`, so the editor scrolls sideways.
- Entry order is row by row, left to right, with `[=]` after each value. Example for 2 unknowns:
  `1=2=3=2=3=4=`.
- `[AC]` in the editor sets **all coefficients to zero**.
- Choosing a different equation type also sets all coefficients to zero.
- Editor restrictions: see §2.4.

### 6.2 Solving and the solution screens

VERIFIED, p.63–64.

- `[=]` in the editor shows the first solution.
- Each further `[=]` shows the next one. `[=]` on the last solution goes back to the editor.
- `▼`/`▲` scroll between solutions. `[AC]` goes back to the editor from any solution.
- Solution labels, as [G] prints them:

  | Type | Labels |
  |---|---|
  | 2×2 | `X=`, `Y=` |
  | 3×3 | `X=`, `Y=`, `Z=` |
  | quadratic | `X1=`, `X2=`, then `X-Value Minimum=`, `Y-Value Minimum=` |
  | cubic | `X1=`, `X2=`, `X3=` |

  - For the quadratic the extremum is a **minimum when a > 0 and a maximum when a < 0**. VERIFIED.
    The maximum labels are INFERRED to read `X-Value Maximum=` / `Y-Value Maximum=`.
  - The solution screens themselves are not reproduced in [G]; only label-and-value pairs are given.
    **Layout, VERIFIED from the [SA] handout's ZA captures (slides 15–16, cross-check):** the label is
    on the top line, left, in the large font (`X=`, `Y=`, `X₁=`, `X₂=`, `X-Value Minimum=`,
    `Y-Value Minimum=`); the value is bottom-right in the large font; `▼` (and `▲` from the second
    screen on) is lit with `Math` and `D`. The subscript in `X₁=` is a small-font digit 5 dots wide and
    6 tall, sitting on the large glyphs' baseline (01 §3.6). Example from the slide: x²+8x+12=0 gives
    `X₁=` −2, `X₂=` −6, then `X-Value Minimum=` −4 and `Y-Value Minimum=` −4. The label strings `X=`,
    `Y=`, `Z=`, `X₁=`, `X₂=`, `X₃=` are also in [GYX] (SECONDARY).
- A **double root** shows a single `X=`: `x² − 2√2x + 2 = 0` gives `X= √2`. VERIFIED. Whether the
  extremum screens follow it is UNKNOWN.
- The cubic order is not sorted. [G] shows X1=−1, X2=2, X3=1 for x³−2x²−x+2. VERIFIED. The ordering
  rule is UNKNOWN; reproduce it on the emulator.
- Display rules (VERIFIED, p.63):
  - Even in Natural Display, **2×2/3×3 solutions never use √ forms**. INFERRED: fractions are still allowed.
  - Quadratic and cubic roots can be exact, e.g. `(3+√57)/4`.
  - Solutions **cannot be converted with ENG**.
- **No solution / infinitely many solutions**: a message appears, and `[AC]` or `[=]` returns to the
  editor. VERIFIED. **The message text is UNKNOWN** because [G] does not print it.
- **Complex roots of the quadratic or cubic: UNKNOWN for the ZA.** [G] and this file's [ES2] (the
  fx-115ES PLUS / 991ES PLUS C guide) say nothing about them. **But** the *international* fx-570/991ES
  PLUS 2nd-edition guide (file 06's [ES2]) has the worked example x² + x + 3/4 = 0 →
  `X₁=` −1/2 + (√2/2)i, `X₂=` −1/2 − (√2/2)i in MthIO-MathO (official sibling guide, V\*; found by the
  cross-check). Same codebase, so INFERRED: the ZA shows complex roots the same way, with `i`, in
  stacked natural form. For the older fx-991MS, a different family, Casio documents an `R⇔I`
  indicator — **do not** copy that. Settle on the fx-991ZA PLUS II emulator with x² + 2x + 3 = 0, in
  MathO and in LineIO, and check whether the vertex screens follow complex roots.

Examples: EX-EQN-01 … 06.

---

## 7. MATRIX

**Enter:** `[MODE][6]`. VERIFIED (screen `MATRIX_01`):

```
Matrix?
1:MatA    2:MatB
3:MatC
```

Choose a variable, then a dimension.

- Matrices are **at most 3×3**. Three variables, `MatA`, `MatB`, `MatC`, plus the answer matrix
  `MatAns`. VERIFIED, p.65.
- **Dimension menu**: [G] shows only that `[5]` is **2×2** and `[4]` is **2×3**. VERIFIED. The menu
  screen is not shown in [G]. **Full menu, SECONDARY ([GYX] strings, cross-check), consistent with both
  VERIFIED keys:** page 1 `1:3×3   2:3×2` / `3:3×1   4:2×3` / `5:2×2   6:2×1`, page 2 (`▼`)
  `1:1×3   2:1×2` / `3:1×1`. So 1×n matrices **exist**. The menu's title line is `MatA(m×n) m×n?`
  (or MatB/MatC), and the variable menu is `Matrix?` / `1:MatA  2:MatB` / `3:MatC`.
- **Matrix Editor** (VERIFIED, screens `MATRIX_02`, `MATRIX_07`):
  - `MAT` indicator;
  - the variable letter (`A`/`B`/`C`) at the top left;
  - brackets;
  - row-major entry with `[=]`;
  - [G]'s own sequences define MatB with `[SHIFT][4][2](**Data**)[2](MatB)[5](2×2)`, i.e. Data on a
    matrix with **no dimension yet**, and a dimension menu appears. VERIFIED sequence; INFERRED rule:
    `Data` on an undefined matrix asks for a dimension first, exactly like `Dim`. VECTOR does the same,
    EX-VEC-01;
  - the bottom line shows the current cell.
- `[AC]` from the editor goes to the calculation screen. VERIFIED.

### 7.1 MATRIX menu `[SHIFT][4](MATRIX)`

VERIFIED from key sequences, pp.65–68:

| Page | # | Item | Use |
|---|---|---|---|
| 1 | 1 | `Dim` | choose a variable, then a dimension, then the editor (new data) |
| 1 | 2 | `Data` | choose a variable, then the editor (edit existing data) |
| 1 | 3 | `MatA` | insert the variable |
| 1 | 4 | `MatB` | |
| 1 | 5 | `MatC` | |
| 1 | 6 | `MatAns` | |
| 1 | 7 | `det` | `det(` function |
| 1 | 8 | `Trn` | `Trn(` transpose function |
| 2 (`▼`) | 1 | `Ref` | `Ref(` row echelon form |
| 2 | 2 | `Rref` | `Rref(` reduced row echelon form |

- **No Identity command** is documented. UNKNOWN whether page 2 holds anything else.

Operations ([G] examples):

- `+`, `−`, `×` between matrices;
- a scalar times a matrix;
- `det(`, `Trn(`;
- `Abs(`, applied element by element;
- inverse with **`[x⁻¹]` only**, because `[x■]` is not allowed;
- square with `[x²]` and cube with `[SHIFT][x²](x³)`. Other powers are not allowed with `[x■]`.

All VERIFIED, pp.67–68.

### 7.2 MatAns behaviour

VERIFIED, p.66:

- Any matrix-valued result opens the **MatAns screen** (label `Ans`) and is stored in `MatAns`.
- A **scalar** result such as `det` appears on the normal calculation line. VERIFIED: `det(MatA)` gives `1`.
- On the MatAns screen, pressing `+ − × ÷ x⁻¹ x²` or `SHIFT x²(x³)` jumps to the calculation line with
  `MatAns` followed by that operator.
- **Copying**: show the source in the editor (`SHIFT 4 2 1` for MatA, or `AC SHIFT 4 6 =` for
  MatAns), then press `[SHIFT][RCL](STO)` and a destination: `[(−)]` MatA, `[°′″]` MatB, `[hyp]` MatC.
  The destination's editor then opens.

### 7.3 Ref / Rref numerical behaviour

VERIFIED (screens) for MatA = [[1,2,3],[4,5,6],[7,8,9]]:

- `Ref` → [[1, 1.1428…, 1.2857…],[0,1,2],[0,0,0]]. The first row is (7,8,9)/7. INFERRED: the
  elimination uses **partial pivoting**, choosing the largest |value| in the column as the pivot.
  Without pivoting the first row would be (1,2,3). I hand-checked that partial pivoting reproduces all
  three rows.
- `Rref` → [[1,0,−1],[0,1,2],[0,0,0]].

### 7.4 Errors

VERIFIED, p.85:

- **Dimension ERROR**: a matrix used without a defined dimension, or dimensions that do not fit the
  operation.
- **Stack ERROR** also covers the matrix/vector stack.
- Inverting a singular matrix: INFERRED Math ERROR. Not stated in [G].

Examples: EX-MAT-01 … 11.

---

## 8. TABLE

**Enter:** `[MODE][7]`. The screen shows `f(X)=` with the cursor. VERIFIED (screen `TABLE_1`).

- Type f(x) using **X** (`[ALPHA][)]`) and press `[=]`. The screen then shows `g(X)=`, but only when
  Table Format = `f(x),g(x)`, which is the **ZA default**. VERIFIED, p.69–70.
- At `g(X)=`, pressing `[=]` with nothing typed builds the table from **f only**. VERIFIED.
- Any variable other than X is treated as a constant, i.e. its stored value is used. VERIFIED.
- `Pol`, `Rec`, `∫`, `d/dx` and `Σ` are not allowed in the functions. VERIFIED.
- `CONV` is not allowed in TABLE mode. VERIFIED, p.46.
- Then three prompts, each followed by `[=]`:

  | Prompt | Meaning | Default |
  |---|---|---|
  | `Start?` | lower limit of X | 1 |
  | `End?` | upper limit of X | 5 |
  | `Step?` | increment | 1 |

  All VERIFIED. End must be greater than Start, per [G]'s note. The consequence of breaking that is
  UNKNOWN; INFERRED Math ERROR or similar.
- **Table screen** (VERIFIED, screen `TABLE_03`):
  - columns `X | F(X) | G(X)` with row numbers;
  - three rows visible;
  - the cursor starts on X of row 1;
  - the bottom line shows the current cell's full value.

  With f only, the columns are INFERRED to be `X | F(X)`.
- The table is **view only**. `[AC]` returns to the function-input screen. VERIFIED.
- **Row limits:** **30 rows** for `f(x)`, **20 rows** for `f(x),g(x)`. If Start/End/Step would
  exceed the limit you get **Insufficient MEM**. VERIFIED, p.70, p.86.
- Generating a table **overwrites variable X**. VERIFIED.
- The functions are deleted if, in TABLE mode, you open setup and switch between Natural and Linear
  Display. VERIFIED.
- An undefined value in a cell, e.g. 1/0 at a row: display UNKNOWN. ([ES2]-family units show
  `ERROR` in the cell, but that is unverified for the ZA.)

Examples: EX-TABLE-01.

---

## 9. VECTOR

**Enter:** `[MODE][8]`. VERIFIED (screen `VECTOR_01`):

```
Vector?
1:VctA    2:VctB
3:VctC
```

- Vectors are 2-D or 3-D. Variables: `VctA`, `VctB`, `VctC`, `VctAns`. VERIFIED.
- **Dimension**: `[1]` = **3**, `[2]` = **2**. VERIFIED from the sequences. The screen is not shown
  in [G]; SECONDARY ([GYX]): title `VctA(m) m?` (or VctB/VctC), items `1:3     2:2`.
- **Vector Editor** (screens `VECTOR_02`, `VECTOR_05`): `VCT` indicator, letter at top left, one bracketed row.

### 9.1 VECTOR menu `[SHIFT][5](VECTOR)`

VERIFIED, pp.71–73:

| # | Item |
|---|---|
| 1 | `Dim` |
| 2 | `Data` |
| 3 | `VctA` |
| 4 | `VctB` |
| 5 | `VctC` |
| 6 | `VctAns` |
| 7 | `Dot` (infix `•`) |

- **No angle command and no unit-vector command** are documented. The angle is worked out by hand
  with Dot, Abs and cos⁻¹, as in EX-VEC-07. Whether menu item 8 exists is UNKNOWN.

### 9.2 Operations

- `+`, `−`, scalar `×`.
- **Cross product with `×` between two vectors.** VERIFIED. A cross product of two **2-D** vectors
  gives a **3-D** vector, (1,2)×(3,4) = (0,0,−2). VERIFIED (screen `VECTOR_09`).
- **Dot product** `VctA•VctB` gives a scalar on the calculation line: `11`. VERIFIED.
- `Abs(VctC)` gives the **magnitude**, a scalar: (2,−1,2) → `3`. VERIFIED.

### 9.3 VctAns and copying

VERIFIED:

- A vector result opens the **VctAns** screen and is stored in `VctAns`.
- On that screen only `+ − × ÷` jump to the calculation line with `VctAns` in front. (MATRIX also
  allows `x⁻¹`, `x²` and `x³`.)
- Copying works as in MATRIX: `STO` then `[(−)]`/`[°′″]`/`[hyp]` for VctA/B/C.

### 9.4 Precision note

In EX-VEC-07 the Fix 3 display shows `0,984`, yet `cos⁻¹(Ans)` gives `10,305`. cos⁻¹(0.984) would be
10.26°, so **Ans keeps full internal precision, not the rounded display**. VERIFIED by my arithmetic on
[G]'s numbers. It is a good regression test.

Examples: EX-VEC-01 … 07.

---

## 10. DIST — distributions

**Enter:** `[MODE][▼][1]`. VERIFIED (screen `DIST_01`):

```
1:Normal PD            ▼
2:Normal CD
3:Inverse Normal
4:Binomial PD
```

`[▼]` gives page 2: `1:Binomial CD 2:Poisson PD 3:Poisson CD`. VERIFIED from the keys. The page-2
screen text is INFERRED.

**Changing type later**: `[SHIFT][1](STAT/DIST)[1](Type)`. VERIFIED, p.75.

### 10.1 Inputs

VERIFIED, p.75:

| Type | Inputs, in prompt order | Notes |
|---|---|---|
| Normal PD | x, σ, μ | |
| Normal CD | Lower, Upper, σ, μ | |
| Inverse Normal | Area, σ, μ | **Tail is always left** |
| Binomial PD / CD | x (or List), N, p | 0 ≤ p ≤ 1 |
| Poisson PD / CD | x (or List), μ | |

- Area must satisfy 0 ≤ Area ≤ 1.
- [G] prints the σ condition as "**σ < 0**". This is almost certainly a misprint for σ > 0; the same
  text appears in [ES2]. INFERRED: σ must be > 0. Check on the emulator whether σ = 0 or a negative σ
  errors.
- **Prompt screens** (VERIFIED, `DIST_03`–`DIST_05`): `Normal PD:x?`, `Normal PD:σ?`, `Normal PD:μ?`,
  each with its current value bottom right. The values shown on first entry were **x 0, σ 1, μ 0**.
- Binomial prompts are `Binomial PD:N?` and `Binomial PD:p?`, both showing 0. VERIFIED.
- The prompt texts for the other types are INFERRED to follow the same `Type:var?` pattern, e.g.
  `Normal CD:Lower?`. UNKNOWN exactly.
- **Result screen** for Normal PD: `p=` on the top line and the value bottom right, e.g.
  `0,1760326634`. VERIFIED (`DIST_06`). The result labels for Normal CD, Inverse Normal and the
  Var-format Binomial/Poisson results are UNKNOWN.
- After a result, `[=]` or `[AC]` returns to the first prompt. VERIFIED.
- **Accuracy is up to 5 significant digits**, but the display still shows 10 digits. VERIFIED, p.75
  and `DIST_06`.
- `Pol`, `Rec`, `÷R`, `∫` and `d/dx` cannot be used. VERIFIED, p.78.

### 10.2 List or Var (Binomial PD/CD, Poisson PD/CD)

- After choosing the type, a menu `1:List  2:Var` appears. VERIFIED (screen `DIST_07`).
- **Var** takes a single x through prompts. The result is **stored in Ans**. VERIFIED, p.78.
- **List** opens the **List Screen** (VERIFIED, `DIST_02`, `DIST_08`–`DIST_13`):
  - columns `X | Ans`, row numbers on the left;
  - a type tag on the right: **`Bpd`** for Binomial PD. Tags for the other three are UNKNOWN;
    INFERRED `Bcd`, `Ppd`, `Pcd`;
  - the bottom line shows the full value of the current cell;
  - **up to 25 samples**.
- Enter samples with `[=]`. A further `[=]` on the empty row goes to the `N?` (or `μ?`) prompt.
  VERIFIED: in EX-DIST-02, after `14=` the next `[=]` gave `Binomial PD:N?`.
- After the last parameter, the List Screen comes back with the `Ans` column filled. The cursor is on
  X row 1. VERIFIED.
- Ans cells are shortened (`0,1859`). The full value appears bottom right when the cursor is on the
  cell: `0,00470184984` for x = 14. VERIFIED.
- Edit commands. VERIFIED, p.76:
  - delete a sample with `[DEL]`;
  - insert with `[SHIFT][1](STAT/DIST)[2](Edit)[1](Ins)`;
  - delete all with `[SHIFT][1][2](Edit)[2](Del-A)`.
- `[=]` from the result list goes to the N prompt. `[AC]` goes back to the List Screen, and the samples
  are kept. VERIFIED.
- **Errors**: an input outside the allowed range shows an error message. INFERRED: Math ERROR, which
  the text does not name. A list sample out of range shows **`ERROR` in its Ans cell**. VERIFIED (text), p.78.

### 10.3 Formulas (INFERRED, standard)

- Normal PD: (1/(σ√(2π)))·e^(−(x−μ)²/(2σ²)). Reproduces `0,1760326634`.
- Binomial PD: C(N,x)·pˣ(1−p)^(N−x). Reproduces 0.18594, 0.12678, 0.063388, 0.021942, 4.7018×10⁻³.

The figures are from my own Python, not from [G].

Note on the last value: the exact value is 0.0047018498458, but the screen shows `0,00470184984`,
which is 9 significant digits. This is consistent with the "5 significant digits" accuracy caveat.
**Do not "fix" it to 10 digits** without checking on the emulator.

Examples: EX-DIST-01, 02.

---

## 11. INEQ — inequalities

**Enter:** `[MODE][▼][2]`. VERIFIED (screen `INEQ_01`):

```
1:aX²+bX+c
2:aX³+bX²+cX+d
```

Quadratic and cubic only. **No linear and no quartic.**

### 11.1 Symbol menu

VERIFIED (screen `INEQ_02` for the quadratic; for the cubic, VERIFIED from the keys `[1]` = `>0` and
`[3]` = `≥0`, with the other two INFERRED):

```
1:aX²+bX+c>0
2:aX²+bX+c<0
3:aX²+bX+c≥0
4:aX²+bX+c≤0
```

### 11.2 Coefficient Editor

VERIFIED (screens `INEQ_03`/`INEQ_04`):

- Headers `a b c` (or `a b c d`) in one bracketed row.
- **The chosen inequality is shown as a template under the row**, e.g. `aX²+bX+c<0`.
- The bottom line shows the current cell.
- `[AC]` zeroes all coefficients. Changing the type also zeroes them.

`[=]` solves. VERIFIED: `[AC]` returns to the editor.

### 11.3 Solution display

VERIFIED (screens):

- The **top line is a template** of the solution shape. The bottom line(s) carry the values.

  | Template | Example values |
  |---|---|
  | `A<X<B` | `-3<X<1` |
  | `X≤A;B≤X` | `X≤-3;1≤X` |
  | `X=A;B≤X` | `X=0;3/2≤X` (fraction stacked) |
  | `A<X<B;C<X` | `(-3-√21)/6<X<0;(-3+√21)/6<X` (fractions stacked) |

- A solution wider than the screen scrolls with `◀`/`▶`. Arrows mark the hidden parts (a `▶` at the
  right edge, `◀` at the left). VERIFIED (screens `INEQ_12`/`INEQ_13`; the example presses `▶` three times).
- In **Linear Display**, the template stays on line 1 and the values are listed below as `A=`, `B=`,
  `C=`. VERIFIED (screens `INEQ_08`, `INEQ_14`):
  - `A= -1,263762616`, `B= 0`, `C= 0,2637626158`.

  `INEQ_08` shows no `Math` indicator. `INEQ_14` does, although both are captioned as Linear Display.
  [G] is inconsistent here. INFERRED: `INEQ_14` was captured in MthIO-LineO. UNKNOWN.
- **Special results**: `All Real Numbers` (e.g. x² ≥ 0, VERIFIED screen) and `No-Solution`
  (e.g. x² < 0, VERIFIED text).
- Results **cannot be converted with ENG**. VERIFIED.
- The full set of templates the firmware can produce, e.g. `X<A;B<X<C`, `X=A`, `X≠A`, is UNKNOWN beyond
  the four shown.

Examples: EX-INEQ-01 … 05 (plus the stated No-Solution case, EX-INEQ-06).

---

## 12. RATIO

**Enter:** `[MODE][▼][3]`. VERIFIED (screen `RAITO_01`):

```
1:a:b=X:d
2:a:b=c:X
```

- The **Coefficient Editor** shows three cells, `a b d` for type 1 or `a b c` for type 2, with the
  template (`a:b=X:d` or `a:b=c:X`) underneath. The bottom line shows the current cell. VERIFIED
  (screens `RAITO_02`, `04`, `06`).
- Each value can have **up to 10 digits**. VERIFIED, p.81.
- `[AC]` zeroes the coefficients. Re-entering RATIO and choosing a type also zeroes them. VERIFIED.
- `[=]` gives `X=` on the top line and the value bottom right. Fractions appear in Natural Display:
  3:8=X:12 gives `9/2`. VERIFIED (screen `RAITO_03`).
- `[=]` again returns to the editor. VERIFIED.
- **Any coefficient of 0 gives Math ERROR.** VERIFIED, p.82.
- Formulas (INFERRED, the obvious ones; they reproduce all three of [G]'s results):
  - type 1: X = a·d/b;
  - type 2: X = b·c/a.

Examples: EX-RATIO-01 … 04.

---

## 13. Modes NOT on this model

| Mode | Status |
|---|---|
| SPREADSHEET | **Not present.** VERIFIED by absence. It is a ClassWiz feature. |
| VERIF | **Not present.** VERIFIED by absence. [ES2] has it, so do not port it from [ES2]. |
| Quartic EQN, linear INEQ, vector angle / unit vector, matrix Identity | **Not documented.** UNKNOWN whether hidden; INFERRED absent. |

---

## 14. CONST — 40 scientific constants

- Open with `[SHIFT][7](CONST)`. The screen reads `CONSTANT` / `Number 01~40?` / `[__]`. VERIFIED
  (screen `CONST_1`).
- Type **two digits**. The constant's **symbol** is inserted into the expression, e.g. `C0` shows as
  `Co`, and `[=]` evaluates. VERIFIED (screen `CONST_2`: `C₀ =` gives `299792458`).
- INFERRED: the insert happens as soon as the second digit is typed.
- Constants work in **every mode except BASE-N**. VERIFIED, p.44.
- [G] names **CODATA 2014** as the source of the values. VERIFIED, p.46. **[G] does not print the
  values.**

In the table:

- Number, symbol and name are VERIFIED from [G] pp.45–46.
- The value is the CODATA 2014 value from [C14]. It is VERIFIED to be CODATA 2014, and INFERRED to be
  what the calculator stores. How many digits are stored is **UNKNOWN**: internal precision is 15
  digits ([G] p.88), and some CODATA values have more significant digits than the 10 shown.
- The unit is the SI unit CODATA uses. INFERRED to be the unit the calculator assumes; [G] gives no units.

| # | Symbol | Name | Value (CODATA 2014) | Unit |
|---|---|---|---|---|
| 01 | mp | proton mass | 1.672621898 × 10⁻²⁷ | kg |
| 02 | mn | neutron mass | 1.674927471 × 10⁻²⁷ | kg |
| 03 | me | electron mass | 9.10938356 × 10⁻³¹ | kg |
| 04 | mμ | muon mass | 1.883531594 × 10⁻²⁸ | kg |
| 05 | a₀ | Bohr radius | 0.52917721067 × 10⁻¹⁰ | m |
| 06 | h | Planck constant | 6.626070040 × 10⁻³⁴ | J s |
| 07 | μN | nuclear magneton | 5.050783699 × 10⁻²⁷ | J T⁻¹ |
| 08 | μB | Bohr magneton | 927.4009994 × 10⁻²⁶ | J T⁻¹ |
| 09 | ℏ | Planck constant, rationalized | 1.054571800 × 10⁻³⁴ | J s |
| 10 | α | fine-structure constant | 7.2973525664 × 10⁻³ | (none) |
| 11 | re | classical electron radius | 2.8179403227 × 10⁻¹⁵ | m |
| 12 | λC | Compton wavelength | 2.4263102367 × 10⁻¹² | m |
| 13 | γp | proton gyromagnetic ratio | 2.675221900 × 10⁸ | s⁻¹ T⁻¹ |
| 14 | λCp | proton Compton wavelength | 1.32140985396 × 10⁻¹⁵ | m |
| 15 | λCn | neutron Compton wavelength | 1.31959090481 × 10⁻¹⁵ | m |
| 16 | R∞ | Rydberg constant | 10973731.568508 | m⁻¹ |
| 17 | u | atomic mass unit | 1.660539040 × 10⁻²⁷ | kg |
| 18 | μp | proton magnetic moment | 1.4106067873 × 10⁻²⁶ | J T⁻¹ |
| 19 | μe | electron magnetic moment | −928.4764620 × 10⁻²⁶ | J T⁻¹ |
| 20 | μn | neutron magnetic moment | −0.96623650 × 10⁻²⁶ | J T⁻¹ |
| 21 | μμ | muon magnetic moment | −4.49044826 × 10⁻²⁶ | J T⁻¹ |
| 22 | F | Faraday constant | 96485.33289 | C mol⁻¹ |
| 23 | e | elementary charge | 1.6021766208 × 10⁻¹⁹ | C |
| 24 | NA | Avogadro constant | 6.022140857 × 10²³ | mol⁻¹ |
| 25 | k | Boltzmann constant | 1.38064852 × 10⁻²³ | J K⁻¹ |
| 26 | Vm | molar volume of ideal gas (273.15 K; 100 kPa) ‡ | 22.710947 × 10⁻³ | m³ mol⁻¹ |
| 27 | R | molar gas constant | 8.3144598 | J mol⁻¹ K⁻¹ |
| 28 | C₀ | speed of light in vacuum | 299792458 (exact) | m s⁻¹ |
| 29 | C₁ | first radiation constant | 3.741771790 × 10⁻¹⁶ | W m² |
| 30 | C₂ | second radiation constant | 1.43877736 × 10⁻² | m K |
| 31 | σ | Stefan–Boltzmann constant | 5.670367 × 10⁻⁸ | W m⁻² K⁻⁴ |
| 32 | ε₀ | electric constant | 8.854187817… × 10⁻¹² (exact, = 1/(μ₀c²)) | F m⁻¹ |
| 33 | μ₀ | magnetic constant | 12.566370614… × 10⁻⁷ (exact, = 4π×10⁻⁷) | N A⁻² |
| 34 | Φ₀ | magnetic flux quantum | 2.067833831 × 10⁻¹⁵ | Wb |
| 35 | g | standard acceleration of gravity | 9.80665 (exact) | m s⁻² |
| 36 | G₀ | conductance quantum | 7.7480917310 × 10⁻⁵ | S |
| 37 | Z₀ | characteristic impedance of vacuum | 376.730313461… (exact) | Ω |
| 38 | t | Celsius temperature | 273.15 § | K |
| 39 | G | Newtonian constant of gravitation | 6.67408 × 10⁻¹¹ | m³ kg⁻¹ s⁻² |
| 40 | atm | standard atmosphere | 101325 (exact) | Pa |

Notes on the table:

- ‡ [G] prints "**237,15K**", which is a misprint for 273.15 K. VERIFIED that it is printed that way.
  INFERRED that it is a typo, since CODATA's 100 kPa entry is at 273.15 K.
- § "Celsius temperature" is not a CODATA entry. 273.15 K is the defined offset. INFERRED.
- Signs: CODATA gives μe, μn and μμ as **negative**. The sibling [GYX] firmware stores all three with
  the negative sign byte (SECONDARY, cross-check), so INFERRED the ZA does too.
- **Stored digits (cross-check, SECONDARY/INFERRED).** The [GYX] table stores each constant at exactly
  the digits CODATA *publishes* (its 2006 values there, e.g. a₀ = 5.2917720859×10⁻¹¹, R∞ =
  10973731.568527), not padded or rounded to 15. The CODATA-exact ones are stored truncated:
  ε₀ = 8.854187817×10⁻¹², μ₀ = 1.2566370614×10⁻⁶, Z₀ = 376.730313461. Those three did not change
  between CODATA 2006 and 2014, so INFERRED the ZA stores those exact digits. For the rest, store the
  CODATA 2014 value with its published digits, as in the table above.
- [GYX]'s Vm is 0.022413996 — CODATA 2006's value at **101.325 kPa**. The ZA guide's "100kPa" wording
  means the 2nd edition changed it to the 100 kPa value 22.710947×10⁻³ (INFERRED; check `SHIFT 7 26 =`).
- Only one value is VERIFIED on a screen: C₀ shows `299792458`, and `1/√(ε₀μ₀)` also shows
  `299792458`. That holds with 10, 11 or 15 stored digits, so it does not tell us the storage precision.

**To settle the stored values:** recall each constant on the emulator in Sci 0 or Norm 1 and record the
10 displayed digits. Then compute `(constant − typed CODATA value)` to expose any hidden digits.

---

## 15. CONV — 40 metric conversions

- Open with `[SHIFT][8](CONV)`. The screen reads `CONVERSION` / `Number 01~40?` / `[__]`. VERIFIED
  (screen `CONV_1`).
- The command is **postfix**: `5 [SHIFT][8][0][2] [=]` shows `5cm▶in` and gives `1,968503937`. VERIFIED.
- Conversions work in **every mode except BASE-N and TABLE**. VERIFIED, p.46.
- [G] names **NIST SP 811 (2008)** as the source. VERIFIED, p.47.
- **J▶cal uses the 15 °C calorie.** VERIFIED, p.47.

**Key evidence about the factors.** `100 g▶oz` displays **`3,527396584`** (VERIFIED, screen `CONV_3`).
That equals 100 ÷ **28.34952**, which is SP 811's printed 7-digit factor. It does **not** equal
100 ÷ 28.349523125, the exact definition, which would give 3.527396195.

⚠ **Corrected by the cross-check (00-index.md, C3).** This section used to conclude that the
calculator uses "the SP 811 table values as printed" for every pair. Two pieces of evidence break
that rule:

1. **A ZA screen contradicts it for km/h.** The Casio-hosted [SA] handout (slide 19) shows
   `120km/h▶m/s` → **`33,33333333`** (MthIO-MathO, after `S⇔D`). With SP 811's printed 0.2777778 the
   answer would be 33.333336, displayed `33,333336`. So km/h▶m/s divides by **3.6 exactly**. The key
   sequence ends in `S⇔D`, which implies the `=` result was the fraction `100/3` before it was turned
   into a decimal — i.e. conversions are computed exactly in MathO and can produce fractions
   (INFERRED from the key sequence; same for `5cm▶in` in MathO, which would then show `250/127`).
2. **The sibling firmware's own factor table** ([GYX] `_num_convs`, SECONDARY) has one factor per pair,
   and it matches every ZA data point (oz 28.34952, km/h 3.6, in 2.54, °C 1.8 + 32) while differing
   from SP 811's printed table in three places: **acre 4046.856** (not 4046.873), **hp 0.7457** (not
   0.7456999), and km/h stored as **m/s→km/h = 3.6**.

The table is therefore rebuilt on [GYX]. Columns:

- Command numbers and names: VERIFIED from [G] p.47.
- "Stored factor": [GYX] value (SECONDARY). The ZA firmware is newer (CY-849, citing SP 811 **2008**
  where GYX cited the 1995 edition), so each factor is INFERRED for the ZA unless the last column says
  a ZA screen confirms it.
- Direction: each stored factor converts one named way ([GYX] comments `in>cm`, `oz>g`, `m/s>km/h`,
  `cal>J`, `°C>°F (+32)` …) and the other command of the pair applies the inverse with the **same**
  factor (divide, not a rounded reciprocal — the oz screen proves this for one pair). For most pairs
  the multiplying command is the odd one; for **19/20, 37/38 and 39/40 it is the even one**
  (20 m/s▶km/h ×3.6, 38 °C▶°F ×1.8+32, 40 cal▶J ×4.1858).

| # | Command | Operation | Stored factor ([GYX]) | SP 811 printed | ZA evidence |
|---|---|---|---|---|---|
| 01 / 02 | in▶cm / cm▶in | × / ÷ | 2.54 | 2.54 (exact) | `5cm▶in` = `1,968503937` (VERIFIED) |
| 03 / 04 | ft▶m / m▶ft | × / ÷ | 0.3048 | same | — |
| 05 / 06 | yd▶m / m▶yd | × / ÷ | 0.9144 | same | — |
| 07 / 08 | mile▶km / km▶mile | × / ÷ | 1.609344 | same | — |
| 09 / 10 | n mile▶m / m▶n mile | × / ÷ | 1852 | same | — |
| 11 / 12 | acre▶m² / m²▶acre | × / ÷ | **4046.856** | 4046.873 (US-survey acre) | **UNKNOWN** — test `1 acre▶m²` |
| 13 / 14 | gal (US)▶ℓ / ℓ▶gal (US) | × / ÷ | 3.785412 | same | — |
| 15 / 16 | gal (UK)▶ℓ / ℓ▶gal (UK) | × / ÷ | 4.54609 | same | — |
| 17 / 18 | pc▶km / km▶pc | × / ÷ | 3.085678 × 10¹³ | same (3.085678 × 10¹⁶ m) | — |
| 19 / 20 | km/h▶m/s / m/s▶km/h | **÷ / ×** | **3.6** (stored as m/s▶km/h) | 0.2777778 | **[SA] s.19: `120km/h▶m/s` = `33,33333333`** — matches 3.6, rules out 0.2777778 |
| 21 / 22 | oz▶g / g▶oz | × / ÷ | 28.34952 | same | `100g▶oz` = `3,527396584` (VERIFIED) |
| 23 / 24 | lb▶kg / kg▶lb | × / ÷ | 0.4535924 | same (definition is 0.45359237) | UNKNOWN — test `1 kg▶lb`: 2,204622476 (table) vs 2,204622622 (definition) |
| 25 / 26 | atm▶Pa / Pa▶atm | × / ÷ | 101325 | same | — |
| 27 / 28 | mmHg▶Pa / Pa▶mmHg | × / ÷ | 133.3224 | same | — |
| 29 / 30 | hp▶kW / kW▶hp | × / ÷ | **0.7457** | 0.7456999 | **UNKNOWN** — test `1 hp▶kW` |
| 31 / 32 | kgf/cm²▶Pa / Pa▶kgf/cm² | × / ÷ | 98066.5 | same | — |
| 33 / 34 | kgf•m▶J / J▶kgf•m | × / ÷ | 9.80665 | same | — |
| 35 / 36 | lbf/in²▶kPa / kPa▶lbf/in² | × / ÷ | 6.894757 | same | — |
| 37 | °F▶°C | (t − 32) ÷ 1.8 | 1.8, offset 32 | exact | [SA] s.19: `356°F▶°C` = `180` |
| 38 | °C▶°F | t × 1.8 + 32 | 1.8, offset 32 | exact | `-31°C▶°F` = `-23,8` (VERIFIED) |
| 39 / 40 | J▶cal / cal▶J | ÷ / × | 4.1858 | 4.18580 (15 °C calorie) | — |

**Before committing, test on the emulator:** `1 acre▶m²`, `1 hp▶kW`, `1 kg▶lb`, and one exact pair in
MathO (`5 cm▶in`, expect `250/127` if conversions are exact).

---

## 16. Limits and errors relevant to the modes

VERIFIED from [G] pp.83–90, unless marked otherwise.

- **Range**: ±1×10⁻⁹⁹ to ±9.999999999×10⁹⁹, or 0. **15-digit** internal precision. Results are
  generally correct to ±1 in the 10th digit.
- Error screen: the message on line 1, then `[AC]  :Cancel` and `[◀][▶]:Goto`. VERIFIED (screen `Errors_1`).
  `◀`/`▶` returns with the cursor at the error.
- Mode-specific errors:

  | Error | When |
  |---|---|
  | **Dimension ERROR** | MATRIX/VECTOR only (§7.4) |
  | **Insufficient MEM** | TABLE row limit (§8) |
  | **Math ERROR** | RATIO with a 0 coefficient (§12); out-of-range DIST input (INFERRED name) |
  | **Stack ERROR** | includes the matrix/vector stack |
  | **Time Out** | ∫ and d/dx only. Not a mode error. |

---

## 17. Open questions

The emulator settles most of these.

1. ~~The MODE menu screen text (both pages). The CMPLX, BASE, MATRIX, VECTOR and STAT sub-menu screen
   layouts.~~ MODE: VERIFIED from [SA] captures. CMPLX/BASE/MATRIX/VECTOR/STAT: SECONDARY from [GYX]
   (cross-check). Still UNKNOWN: the DIST page-2 screen and the DIST prompt/result labels.
2. EQN: how complex roots are displayed on the ZA (the international sibling guide shows
   `-1/2+√2/2 i` style, §6.2; INFERRED same); the exact no-solution and infinite-solutions messages
   (not in [GYX] either); the cubic root ordering; whether the extremum screens follow a double root or
   complex roots.
3. STAT: the quartile/median method; the Statistics-Editor STAT/DIST menu (item 3 = Edit, the rest
   unknown); the 40/20/26 row-capacity oddity; formulas for e^X, A•B^X, A•X^B, 1/X and quadratic
   (unverified, no example); the x̂₁/x̂₂ order; Q(t) for t < 0.
4. BASE-N: out-of-range behaviour and the error name; how the `d/h/b/o` prefixes render on screen;
   precedence of the logical operators.
5. MATRIX: ~~the dimension-menu order~~ (SECONDARY from [GYX], §7); the singular-inverse error;
   whether Identity exists (no `Identity` string in [GYX]; INFERRED absent).
6. DIST: prompt texts and result labels for Normal CD, Inverse Normal, Binomial CD and Poisson; List
   tags other than `Bpd`; the σ condition misprint; the 9-digit display of the binomial value.
7. INEQ: every template the firmware can emit; the `Math` indicator inconsistency between `INEQ_08`
   and `INEQ_14`.
8. CONST: the stored digits of each CODATA 2014 value (signs now SECONDARY-negative, §14). CONV:
   km/h is settled (÷3.6; [SA] capture). Still open: acre (4046.856 vs 4046.873), hp (0.7457 vs
   0.7456999), lb, and whether MathO conversions return fractions (§15).
9. TABLE: how an undefined cell displays; the error when End ≤ Start.

---

## Appendix A — mode screens quoted above, by [G] page

| Screen | [G] page | [H] image |
|---|---|---|
| CMPLX entry | 48 | `img35.png` |
| STAT type menu | 50 | `img36.png` |
| STAT editor | 51, 56–58 | `img37`–`img39`, `img42`–`img44` |
| STAT menus | 52 | `img40.png`, `img41.png` |
| BASE-N | 59 | `BASE_1`…`BASE_6` |
| EQN | 62, 64 | `EQN_1`–`EQN_3` |
| MATRIX | 65–68 | `MATRIX_01`…`13`, `MAT_01`, `MAT_02` |
| TABLE | 69–70 | `TABLE_1`–`3`, `CY846_847_TABLE_01/02`, `CY849_TABLE_03` |
| VECTOR | 71–74 | `VECTOR_01`…`12` |
| DIST | 74–77 | `DIST_01`…`13` |
| INEQ | 78–80 | `INEQ_01`…`15` |
| RATIO | 81–82 | `CY849_RAITO_01`…`07` |
| CONST / CONV | 45–47 | `CONST_1`–`3`, `CONV_1`–`4` |

## Appendix B — [H] key-image IDs

For anyone scraping the HTML guide. The IDs are private-use code points 0xE000+. VERIFIED by viewing each image.

| ID | Key | ID | Key | ID | Key | ID | Key |
|---|---|---|---|---|---|---|---|
| 57344 | AC | 57355 | 6 | 57366 | ■/■ (fraction) | 57389 | Ans |
| 57345 | ◀ | 57356 | 7 | 57367 | √■ | 57391 | MODE |
| 57346 | ▶ | 57357 | 8 | 57368 | x² | 57392 | M+ |
| 57347 | ▼ | 57358 | 9 | 57369 | x■ | 57393 | sin |
| 57348 | ▲ | 57359 | = | 57382 | x⁻¹ | 57394 | cos |
| 57349 | 0 | 57360 | + | 57383 | ENG | 57395 | tan |
| 57350 | 1 | 57361 | − | 57384 | DEL | 57396 | log |
| 57351 | 2 | 57362 | × | 57386 | hyp | 57397 | ln |
| 57352 | 3 | 57363 | ÷ | 57387 | RCL | 57398 | SHIFT |
| 57353 | 4 | 57364 | ( | 57388 | ALPHA | 57399 | (−) |
| 57354 | 5 | 57365 | ) | 57401 | °′″ | 57447 | , (decimal key) |

The glyphs, not keys: 57381 ℏ, 57402 ◀, 57403 ▶ (as in `▶r∠θ`), 57455 ▶.
