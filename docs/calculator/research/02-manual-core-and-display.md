# fx-991ZA PLUS II: general operation, COMP mode and display rules

Research file 02. It covers the parts of the official User's Guide that describe general operation
(modes, SETUP, input, display, memory), the COMP-mode functions, errors, operator priority and the
calculation-range appendix. The CMPLX, STAT, BASE-N, EQN, MATRIX, TABLE, VECTOR, DIST, INEQ and RATIO
chapters are out of scope here; this file mentions them only where a general rule refers to them.

Every worked example from the general and COMP chapters is transcribed as a test vector in
[`04-examples-comp.md`](04-examples-comp.md).

---

## 0. How to read this file

### Sources

| Key | Source | Status |
|---|---|---|
| **[G]** | CASIO, *User's Guide (NATURAL-V.P.A.M.) fx-991ZA PLUS II*, English, 96 PDF pages. PDF metadata: created 2020-05-20, modified 2020-05-25, "© 2020 CASIO COMPUTER CO., LTD." on the last page. <https://www.casio.com/content/dam/casio/global/support/manuals/calculators/pdf/004-en/f/fx-991ZA_PLUSII_EN.pdf> | **Primary.** Official Casio guide for this exact model. |
| [G-html] | The same guide as HTML pages: <https://support.casio.com/global/en/calc/manual/fx-991ZAPLUSII_en/>. Its table of contents matches the PDF section for section. | Official, identical content |
| **[SA]** | Teacher handout *"FX 82 ZA PLUS II / FX 991 ZA PLUS II"* by James Ralph (Pty) Ltd, Casio's South-African distributor. It is hosted on Casio's own education server at <https://edumaterial.casio-intl.com/resource/contents/1368> (PDF created 2021-11-02, 21 slides) and contains real screen captures from the ZA PLUS II. | Casio-hosted distributor material. Its screen captures are treated as VERIFIED for what they show. |
| [S570] | CASIO *fx-570ES User's Guide* (2005), official: <https://support.casio.com/pdf/004/fx-570ES_Eng.pdf> | **Older sibling.** Its facts are at most INFERRED for the ZA PLUS II. |
| [S991] | CASIO *fx-570ES PLUS / fx-991ES PLUS User's Guide* (2008), official: <https://support.casio.com/pdf/004/fx-570_991ES_PLUS_E.pdf> | Sibling. INFERRED at most. |
| [SC] | CASIO *fx-115ES PLUS / fx-991ES PLUS C User's Guide (2nd edition)*, official: <https://www.casio.com/content/dam/casio/global/support/manuals/calculators/pdf/004-en/f/fx-115ESPLUS_991ESPLUSC_EN.pdf> | Sibling. Used only to compare feature sets. |
| [S2022] | CASIO *fx-570ES PLUS / fx-991ES PLUS / fx-9910NG PLUS User's Guide (2nd edition)*, official: <https://www.casio.com/content/dam/casio/global/support/manuals/calculators/pdf/2022/mutual/fx-570ES_991ES_9910NG_PLUS_EN.pdf> | Sibling. Used only for the table-of-contents comparison. |

**Page citations.** `[G p.N]` uses the **printed** page number in the guide, which is the PDF page minus 1 (the cover is
PDF page 1). For example, "Calculation Priority Sequence" is printed page 87, which is PDF page 88.

### Confidence tags

- **VERIFIED**: read in [G], or in a [SA] screen capture of the ZA PLUS II.
- **SECONDARY**: read in a retailer, community or OCR source, or in a part of [SA] that is OCR text rather than a screen capture.
- **INFERRED**: this researcher's reasoning, or a fact taken from a sibling model's official guide.
- **UNKNOWN**: not established. The entry says what would settle it, which is usually the official
  *fx-991ZA PLUS II Emulator*. [SA s.20] says it can be downloaded from <https://edu.casio.com/softwarelicense/aid/manager/en/> with a 90-day free trial.

### Key notation used in this file and in 04

Keys are written in backticks with the label printed **on the key**. A shifted or alpha function is written as the key
sequence followed by the function name in parentheses: `SHIFT` `MODE` (SETUP) means "press SHIFT, then MODE; this
selects SETUP". This is the same convention [G p.7] uses. Two keys have labels that are hard to type, so this file
names them:

- `FRAC` is the fraction template key. On the keycap it is a filled box over an empty box with a bar between them.
- `log□` is the "log with a base slot" key (log_□ □).

Other key names: `x^` is x^□, `x⁻¹`, `x²`, `√`, `∫`, `°'"`, `S⇔D`, `,` (the decimal-separator key, a **comma** on this
model), `×10ˣ`, `(−)` (negative sign), `−` (subtract).

---

## 1. ZA PLUS II specifics. Read these before any international Casio material.

When an international Casio manual (fx-991ES PLUS, fx-991EX…) disagrees with this table, the ZA PLUS II wins.

| # | Fact | Evidence | Confidence |
|---|---|---|---|
| Z1 | The **decimal separator is a comma**, both on the display and on the keypad. The key that sits where `.` is on other models is printed `,`. Results look like `0,5`, `3,141592654` and `-1,290024053`. The comma also appears **during input**, for example the input line `Pol(1,414213562▷`. | [G p.7] screen; [G p.13, p.30] examples; [SA s.3] shows the old and new keycaps side by side | VERIFIED |
| Z2 | The **argument and list separator is a semicolon `;`**, entered with `SHIFT` `)`. Examples: `log(2;16)`, `GCD(28;35)`, `Pol(√2;√2)`, `RanInt#(1;6)`, `∫(f(x);a;b;tol)`, `d/dx(f(x);a;tol)`, `Σ(f(x);a;b)`. Two-value results are joined with `;` too, as in `r=2;θ=45` and `2;R=1`. | [G p.7, p.31–40] key sequences and screens | VERIFIED |
| Z3 | The **default number format is Norm 2** (Norm 1 on the earlier fx-991ZA PLUS and on the international ES PLUS). So `1÷800` shows `0,00125` and not `1,25×10⁻³`. | [G p.15] initialization table; [SA s.3] before/after screens | VERIFIED |
| Z4 | The SETUP menu has **no "Disp: Dot/Comma" item**. The international fx-991ES PLUS (2008) had one at SETUP ▼5 [S991]. On the ZA PLUS II, SETUP page 2 is `1:ab/c 2:d/c 3:CMPLX 4:STAT 5:TABLE 6:APO 7:◀CONT▶`. | [G p.11] screen | VERIFIED |
| Z5 | **Sci takes 0–9.** Sci 0 means 10 significant digits: `1÷7` in Sci 0 gives `1,428571429×10⁻¹`. The 2008 international guide described Sci as "1 to 10". | [G p.13] | VERIFIED |
| Z6 | SETUP has **APO** (auto power off, 10 or 60 min) and **TABLE** (f(x) or f(x),g(x)) items. | [G p.14] | VERIFIED |
| Z7 | The MODE menu has **2 pages**: `1 COMP 2 CMPLX 3 STAT 4 BASE-N 5 EQN 6 MATRIX 7 TABLE 8 VECTOR` and then `1 DIST 2 INEQ 3 RATIO`. The menu screen draws each item number as an **inverted (white-on-black) digit**; the first-generation fx-991ZA PLUS drew `1:COMP`. | [G p.10] key table; [SA s.2, s.15] screen captures | VERIFIED |
| Z8 | COMP has these extras compared with a plain ES PLUS: **÷R** (remainder division), **FACT** (prime factorisation), **PreAns**, **GCD/LCM**, **RanInt#**. | [G p.24–27, p.38, p.40] | VERIFIED |
| Z9 | **Not in the ZA PLUS II guide:** recurring-decimal display, ∏ (product), Int/Intg, VERIFY mode, engineering SI-prefix symbols. [SC] (fx-991ES PLUS C) has the first four. Treat them as **absent**. | ToC of [G] compared with ToC of [SC] | VERIFIED that the guide does not mention them. INFERRED that the model lacks them. |

---

## 2. Model identity: what this guide establishes

- [G] is a model-specific Casio guide titled "fx-991ZA PLUS II". It is not a reprint of an international guide. **VERIFIED.**
- Its structure and wording follow the newer "web-style" Casio ES PLUS guides ([SC], [S2022]) section for section, so
  the calculator belongs to the **ES PLUS (NATURAL-V.P.A.M.) family**: a two-line dot-matrix display whose height [G p.17]
  gives as 31 dots, 15-digit internal precision, and the same key layout conventions. **VERIFIED** for the structure. Family membership is **INFERRED**.
- Feature comparison:
  - [S2022] (fx-570ES PLUS / fx-991ES PLUS / fx-9910NG PLUS) **lacks** ÷R, FACT, PreAns, GCD/LCM, DIST, INEQ and RATIO.
  - [SC] (fx-115ES PLUS / fx-991ES PLUS C) **has** ÷R, FACT, PreAns and GCD/LCM, adds recurring decimals, ∏, Int/Intg and VERIF, and **lacks** RATIO.
  - ~~Casio's catalogue lists, for the fx-991ES PLUS 2nd edition, "Prime factorization, Random integers, Inequality, Ratio calculation" as additions.~~ **Corrected by the cross-check (00-index.md, C1): this was a misreading of a three-column page.** In Casio's catalogue (<https://www.casio.com/content/dam/casio/global/calculator/scientific-calculators/catalog/fx-ES_PLUS_2.pdf>, lineup page, rendered and read 2026-10-02) the fx-991ES PLUS-2 column lists **only "Random integers"** as a main function, with 417 functions. "Prime factorization · Random integers · Inequality · Ratio calculation" is the **fx-95ES PLUS-2** column, and "Prime factorization · Random integers" the fx-82ES PLUS-2 column. **VERIFIED.**
  - Conclusion (revised): the international fx-991ES PLUS-2 is the **keyboard and body** sibling (same 11.1 × 77 × 161.5 mm, 95 g body per the same catalogue, VERIFIED), but **not** the behavioural one. The behavioural sibling is the North-American **fx-115ES PLUS / fx-991ES PLUS C 2nd edition (ROM CY-847)**: file 06 diffed both guides line by line, and Casio's own ZA HTML guide reuses that model's screen images (`CY846_847_TABLE_01.png`, `CY846_847_TABLE_02.png`) next to ZA-only ones (`CY849_RAITO_*`, `CY849_TABLE_03`). **VERIFIED** (image file names in [G-html]). [S991] and [S2022] are definitely **not** a drop-in match.
- [SA s.2] compares the ZA PLUS II with the first-generation **fx-991ZA PLUS**. The old mode menu page 2 held only `1:DIST`; the new one holds `DIST, INEQ, RATIO`. The old default was Norm 1 and the old decimal was a point. **VERIFIED** (Casio-hosted screen captures).

---

## 3. Key assignments confirmed by the general/COMP chapters

These assignments are taken only from key sequences printed in [G] (or [SA] where marked). They are **VERIFIED** unless marked otherwise.

| Function | Keys | Source |
|---|---|---|
| Power on / off | `ON` / `SHIFT` `AC` (OFF) | G p.6 |
| SETUP | `SHIFT` `MODE` | G p.11 |
| CLR (clear menu) | `SHIFT` `9` | G p.4, p.15 |
| INS | `SHIFT` `DEL` | G p.18–19 |
| π | `SHIFT` `×10ˣ` | G p.20 |
| e (constant) | `ALPHA` `×10ˣ` | G p.33 |
| % | `SHIFT` `(` | G p.22 |
| `;` separator | `SHIFT` `)` | G p.31 |
| `:` multi-statement | `ALPHA` `∫` | G p.23 |
| `=` (equation equals sign) | `ALPHA` `CALC` | G p.40 |
| SOLVE | `SHIFT` `CALC` | G p.42 |
| d/dx | `SHIFT` `∫` | G p.35 |
| Σ | `SHIFT` `log□` | G p.36 |
| Mixed-fraction template | `SHIFT` `FRAC` | G p.21 |
| ÷R | `ALPHA` `FRAC` | G p.24 |
| a b/c ↔ d/c | `SHIFT` `S⇔D` | G p.22 |
| x³ | `SHIFT` `x²` | G p.32 |
| x! | `SHIFT` `x⁻¹` | G p.38 |
| ⁿ√ (index root) | `SHIFT` `x^` | G p.32 |
| ∛ | `SHIFT` `√` | G p.33 |
| 10^□ | `SHIFT` `log` | G p.18 |
| e^□ | `SHIFT` `ln` | G p.31 |
| Abs | `SHIFT` `hyp` | G p.38 |
| sin⁻¹ (cos⁻¹, tan⁻¹ by analogy) | `SHIFT` `sin` | G p.30 (cos⁻¹ and tan⁻¹ are INFERRED) |
| hyperbolic menu | `hyp`, then `1` sinh … `5` cosh⁻¹ | G p.30–31 (full menu in §10.5) |
| DRG▶ menu | `SHIFT` `Ans` | G p.31 |
| PreAns | `ALPHA` `Ans` | G p.27 |
| STO | `SHIFT` `RCL` | G p.28 |
| M− | `SHIFT` `M+` | G p.28 |
| FACT | `SHIFT` `°'"` | G p.25 |
| Pol / Rec | `SHIFT` `+` / `SHIFT` `−` | G p.37–38 |
| nPr / nCr | `SHIFT` `×` / `SHIFT` `÷` | G p.39 |
| GCD / LCM | `ALPHA` `×` / `ALPHA` `÷` | G p.40 |
| Rnd | `SHIFT` `0` | G p.39 |
| Ran# / RanInt# | `SHIFT` `,` / `ALPHA` `,` | G p.38–39 |
| CONST / CONV | `SHIFT` `7` / `SHIFT` `8` | G p.44, p.46 |
| CMPLX (mode entry: `MODE` `2`) | in CMPLX mode, `SHIFT` `2` opens the CMPLX menu; item `3` is ▶r∠θ | G p.41 |
| i (imaginary unit, CMPLX mode) | `ENG` | G p.41 |
| Variable A / B / X / Y / M | `ALPHA` + `(−)` / `°'"` / `)` / `S⇔D` / `M+` | G p.28, p.40, p.42 |
| Variable C / D / E / F | `ALPHA` + `hyp` / `sin` / `cos` / `tan` | **VERIFIED** (cross-check): the magenta ┌C┐ ┌D┐ ┌E┐ ┌F┐ legends are printed on those keys in Casio's official product render (01 [R], 06 [KB]), and [G p.66] uses `hyp` as the MatC copy target after STO (03 §7.2). Earlier this row said SECONDARY (OCR of [SA]). |
| Authenticity screen | `MODE` `0` | G p.92 |

---

## 4. Calculation modes (MODE menu)

| Keys | Mode | Purpose (paraphrased) |
|---|---|---|
| `MODE` `1` | COMP | general calculations |
| `MODE` `2` | CMPLX | complex numbers |
| `MODE` `3` | STAT | statistics and regression |
| `MODE` `4` | BASE-N | binary, octal, decimal and hexadecimal |
| `MODE` `5` | EQN | equations |
| `MODE` `6` | MATRIX | matrices |
| `MODE` `7` | TABLE | table of one or two functions |
| `MODE` `8` | VECTOR | vectors |
| `MODE` `▼` `1` | DIST | distributions |
| `MODE` `▼` `2` | INEQ | inequalities |
| `MODE` `▼` `3` | RATIO | ratios |
| `MODE` `0` | (not a mode) | shows the authenticity screen: a 24-character calculator ID plus a QR code for <https://wes.casio.com/calc/>. `AC` returns to the mode menu. |

- **VERIFIED** [G p.10, p.92]. The **initial mode is COMP** [G p.10].
- **Screen layout, VERIFIED from [SA s.2, s.15] captures.** Page 1 lays the items out in four rows of two: `[1]COMP [2]CMPLX` / `[3]STAT [4]BASE-N` / `[5]EQN [6]MATRIX` / `[7]TABLE [8]VECTOR`. `[n]` is an inverted digit cell, and ▼ appears at top right. Page 2 is `[1]DIST [2]INEQ` / `[3]RATIO`, with ▲ at top right. ~~Both pages use the large font.~~ **Corrected by the cross-check:** both pages use the **small menu font** (4 text lines on an 8-dot pitch; the large font is 9 dots tall and cannot fit 4 lines in 31 rows). Measured on the [SA] captures, which are exact 2× pixel copies of the emulator LCD; see 01 §3.6.
- Entering COMP shows an empty input line with the cursor at the left and the indicators `D` (angle unit, boxed) and `Math` [G p.20 screen]. **VERIFIED.**
- Entering STAT, BASE-N, MATRIX or VECTOR switches the display to Linear Display automatically [G p.12]. **VERIFIED.**

### Menus in general [G p.9], VERIFIED

- Press the item's number key to select it.
- ▼ at the top right of a menu means another page exists below, and ▲ means one exists above. Use `▼`/`▲` to switch pages.
- `AC` closes a menu without selecting anything.

---

## 5. SETUP menu (`SHIFT` `MODE`)

### 5.1 Screens. VERIFIED [G p.11] screen captures, transcribed exactly.

Page 1 (▼ at top right):

```
1:MthIO 2:LineIO
3:Deg   4:Rad
5:Gra   6:Fix
7:Sci   8:Norm
```

Page 2 (▲ at top right):

```
1:ab/c  2:d/c
3:CMPLX 4:STAT
5:TABLE 6:APO
7:◀CONT▶
```

Both pages use the small menu font. `▼` goes from page 1 to page 2 and `▲` goes back. In the dot-matrix font "Deg" looks like "Des".

### 5.2 Items, sub-choices and defaults

Defaults are underlined in [G], as listed in the initialization table [G p.15]. **VERIFIED** unless noted.

| Item (keys after SETUP) | Choices | Default | Notes |
|---|---|---|---|
| `1` MthIO → `1` MathO / `2` LineO | Natural Display with natural results (MthIO-MathO), or natural input with linear results (MthIO-LineO) | **MthIO-MathO** | The submenu labels "MathO"/"LineO" come from [G]'s key annotations. Submenu screen: `Result Format?` / `1:MathO 2:LineO` — **SECONDARY** (string table of the sibling ES PLUS GYX firmware, 07 [S13]; added by the cross-check). |
| `2` LineIO | Linear Display for input and output | | |
| `3` Deg / `4` Rad / `5` Gra | default angle unit | **Deg** | 90° = π/2 rad = 100 grad |
| `6` Fix then `0`–`9` | fixed decimal places | | The prompt screen is `Fix 0~9?` (VERIFIED [SA s.5] capture) |
| `7` Sci then `0`–`9` | fixed significant digits (0 means 10) | | The prompt is `Sci 0~9?` — SECONDARY (GYX string table, which also holds `Fix 0~9?` and `Norm 1~2?` exactly as the [SA] captures show them) |
| `8` Norm then `1`/`2` | exponential-display range | **Norm 2** | The prompt screen is `Norm 1~2?` (VERIFIED [SA s.5]) |
| ▼`1` ab/c / ▼`2` d/c | mixed or improper fraction results | **d/c** | |
| ▼`3` CMPLX → `1` a+bi / `2` r∠θ | complex result format | **a+bi** | Submenu screen `Complex Result?` / `1:a+bi  2:r∠θ` — SECONDARY (GYX string table) |
| ▼`4` STAT → `1` ON / `2` OFF | FREQ column in the STAT editor | **OFF** | Submenu screen `Frequency?` / `1:ON    2:OFF` — SECONDARY (GYX string table) |
| ▼`5` TABLE → `1` f(x) / `2` f(x),g(x) | one or two functions in TABLE | **f(x),g(x)** | Submenu screen text **UNKNOWN** (a 2nd-edition item; not in the GYX firmware) |
| ▼`6` APO → `1` 10Min. / `2` 60Min. | auto power-off time | **10Min.** | Submenu screen text **UNKNOWN** (2nd-edition item) |
| ▼`7` ◀CONT▶ | contrast | not part of the init table | Screen [G p.6]: `CONTRAST` on line 1, then `LIGHT` above `[◀]` on the left and `DARK` above `[▶]` on the right. `◀`/`▶` adjust it and `AC` exits. The GYX strings are `CONTRAST`, an empty line, `LIGHT       DARK`, ` [◀]        [▶]` (SECONDARY). The ES PLUS firmware accepts contrast values 0x04–0x1D, i.e. 26 levels (SECONDARY: `_need_reset` in 07 [S13]; Calcupedia self-test page). Default level UNKNOWN. |

**MthIO-MathO compared with MthIO-LineO**, as screens in [G p.11–12]. **VERIFIED.**

- MthIO-MathO: `1÷200` gives the fraction `1/200`.
- MthIO-LineO in Norm 1 gives `5×10⁻³`.
- MthIO-LineO in Norm 2 gives `0,005`.
- LineIO in Norm 1 gives `5×10⁻³`.

### 5.3 CLR menu (`SHIFT` `9`) and resets

Screens, VERIFIED from [SA s.4] captures:

```
Clear?
1:Setup 2:Memory
3:All
```

After choosing `3`:

```
Reset All?

[=]   :Yes
[AC]  :Cancel
```

After pressing `=`, the screen shows `Reset All` centred, with `Press [AC] key` below it. The equivalent screens for Setup and Memory: the sibling GYX firmware's string table holds `Clear Setup?`, `Clear Memory?`, `Reset All?`, `   Reset All`, `   Complete!`, ` Press [AC] key`, `[=]   :Yes` and `[AC]  :Cancel` (SECONDARY, 07 [S13]). Its `Reset All?` / `   Reset All` / ` Press [AC] key` strings match the [SA s.4] captures character for character, which is good evidence the table carries over. So the Setup and Memory confirmations are **`Clear Setup?`** and **`Clear Memory?`** with the same `[=]`/`[AC]` lines, and their completion screen is presumably `Complete!` + `Press [AC] key` (INFERRED; confirm on the emulator). *(The previous guess `Reset Setup?` was wrong.)*

| Operation | Keys | Effect | Source |
|---|---|---|---|
| Initialise settings | `SHIFT` `9` `1` (Setup) `=` (Yes) | Mode becomes COMP and every setup item returns to its default (table above). Also clears calculation history. | G p.15, p.26. VERIFIED |
| Clear memories | `SHIFT` `9` `2` (Memory) `=` | Clears "all memories": Ans, independent memory M and the variables. Whether PreAns is included is INFERRED (yes). | G p.29 |
| Initialise everything | `SHIFT` `9` `3` (All) `=` | Mode and setup return to their defaults, and all data in memory is cleared. Also clears history. Required after a battery change. | G p.4, p.26, p.87 |
| Self-check | `ON` | When an error or unexpected result occurs, pressing `ON` runs an internal check. If the check finds an abnormality, the calculator initialises the mode and clears memory automatically. | G p.86. VERIFIED |

Removing the battery deletes all memory contents [G p.86].

---

## 6. Display indicators [G p.8–9]. VERIFIED.

| Indicator | Meaning |
|---|---|
| boxed `S` | SHIFT pressed. It clears after the next key. |
| boxed `A` | ALPHA pressed. It clears after the next key. |
| `M` | Independent memory holds a value other than zero. |
| `STO` | Waiting for a variable name to store into (after `SHIFT` `RCL`). |
| `RCL` | Waiting for a variable name to recall (after `RCL`). |
| `STAT` / `CMPLX` / `MAT` / `VCT` | In STAT, CMPLX, MATRIX or VECTOR mode. |
| boxed `D` / `R` / `G` | Default angle unit is degrees, radians or grads. |
| `FIX` | Fix is in effect. |
| `SCI` | Sci is in effect. |
| `Math` | Natural Display is selected. |
| `▼` `▲` | History data exists and can be replayed, **or** there is more data above or below (in menus). |
| `Disp` | The display shows an intermediate result of a multi-statement. |

- During a long calculation the display can show only the indicators, with no value, until the result is ready [G p.9].
- Indicators observed together in screen captures, left to right: `A` … `D` … `FIX` … `Math` … `▲`
  ([G p.40 example screens]; [SA s.5] shows `D FIX Math ▲`; [SA s.8] shows `A D Math ▲`). Exact pixel positions belong to
  the display-hardware research and are not specified in [G].
- In **LineIO** screen captures the `Math` indicator is absent ([G p.27, p.46–47]). **VERIFIED.** Whether `Math` shows in
  MthIO-LineO is **INFERRED** yes, because natural *input* is still active. UNKNOWN until checked on the emulator.

---

## 7. Input

### 7.1 Basic rules [G p.16–17]. VERIFIED.

- Expressions are entered as written. `=` evaluates them with the priority rules in §12.
- **A closing `)` is required** after the argument of any function that opens a parenthesis (sin, sinh, log(, etc.).
  Without it, everything typed afterwards becomes part of the argument. [G p.93] shows `sin 30 + 15 =` (LineIO, Deg)
  evaluating sin(45), which gives `0,7071067812`, whereas `sin 30 ) + 15 =` gives `15,5`.
- The closing `)` **immediately before `=`** may be omitted. Whether several trailing `)` can all be omitted is **INFERRED** yes. The guide's example omits one.
- **A `×` may be omitted** immediately before:
  - `(`
  - sin or any other function that includes a parenthesis
  - Ran#
  - a variable (A, B, C, D, E, F, M, X, Y)
  - a scientific constant
  - π
  - e

  An omitted multiplication has **its own, higher priority** than `×` (§12, level 7 against level 10). The consequence
  is **INFERRED**: `1÷2π` is evaluated as 1÷(2π).
- Example screens show that **the input line is not rewritten**. When the trailing `)` is omitted the line still reads
  `4sin(30)(30+10×3` after `=`, without a `)` added [G p.16 screen].

### 7.2 Input capacity and cursor shapes. VERIFIED [G p.17, p.19].

- Up to **99 bytes** per expression. Each numeral, symbol or function normally takes 1 byte. "Some functions" take
  **3 to 13 bytes**. Which functions, and how many bytes each takes, is **UNKNOWN**: the guide does not say. Settle it by
  filling the buffer on the emulator and watching for the cursor change.
- When **10 bytes or fewer** remain, the cursor changes to a **filled block ■**. The guide says to finish the expression and press `=`.
- Behaviour exactly at 99 bytes: further keys are not accepted (**INFERRED**; the guide implies it).
- **Insert cursor**: a vertical bar `|`, shown blinking at the left edge of an empty line in the COMP entry screen [G p.20].
  **Overwrite cursor**: an underline `_`. Overwrite exists **only in LineIO** and is toggled with `SHIFT` `DEL` (INS).
  Natural Display is always in insert mode. Switching from LineIO to MthIO forces insert mode.
- Natural Display height limit: an expression may be up to **two screens tall (31 dots × 2)**. Beyond that, input is refused.
- Nesting of functions and parentheses is allowed up to an unspecified limit. Beyond it, input is refused [G p.17].
  The limit is **UNKNOWN**. Compare *Stack ERROR* in §11, which is the limit at evaluation time.

### 7.3 Cursor movement. VERIFIED [G p.16–17] unless noted.

- **LineIO:** `▲` jumps to the beginning of the expression and `▼` to the end.
- **MathO:** `▶` at the end of the expression wraps to the beginning, and `◀` at the beginning wraps to the end.
- When the expression is wider than the screen, the display scrolls automatically and a `◀` indicator appears. Scroll back with `◀`/`▶`.
- **Moving around templates (MathO).** These rules are VERIFIED from the key sequences in [G]:
  - Fraction (`FRAC`): the cursor starts in the numerator. `▼` moves to the denominator (`FRAC 2 ▼ 3`). `▶` at the end of the numerator also moves into the denominator: `FRAC 2 + √ 2 ▶ ▶ 1 + √ 2` gives (2+√2)/(1+√2), where the first `▶` leaves the √ and the second moves to the denominator [G p.17]. `▶` at the end of the denominator leaves the fraction.
  - Mixed fraction (`SHIFT FRAC`): starts in the whole-number slot. `▶` goes to the numerator and `▼` to the denominator (`4 − SHIFT FRAC 3 ▶ 1 ▼ 2`) [G p.20].
  - ∫ template: starts in the integrand. `▶` goes to the **lower** limit and `▶` again to the **upper** limit (`∫ ln X ) ▶ 1 ▶ e`). From the lower limit, `▲` goes to the upper limit (`… ▶ 0 ▲ π`) [G p.32–34].
  - d/dx template: starts in f(x). `▶` goes to the `x=` value slot [G p.35].
  - Σ template: starts in f(x). `▶` goes to the lower bound (x=) and `▶` again to the upper bound [G p.36].
  - log□ template: starts in the base. `▶` goes to the argument and `▶` again leaves it (`log□ 2 ▶ 4 ▶ x³` gives log₂(4)³) [G p.32].
  - x^□, √, e^□, Abs: `▶` leaves the slot [G p.31–33, p.38].
  - ⁿ√ (`SHIFT x^`): starts in the index slot. `▶` goes to the radicand (`SHIFT x^ 5 ▶ 32`) [G p.32].
- In LineIO, the same functions are typed with parentheses and `;` separators (§7.4).

### 7.4 Templates and what each key inserts. VERIFIED [G p.17–18].

Natural Display allows textbook-style input of:

- log (with a base slot)
- x², x³, x^□, √□, ∛□, ⁿ√□, x⁻¹
- 10^□, e^□
- ∫, d/dx, Σ
- Abs

| Key | MathO inserts | LineIO inserts (from [G] LineIO examples) |
|---|---|---|
| `FRAC` | stacked fraction □/□ | the fraction separator `⌟`, as in `2⌟3+1⌟2` giving `7⌟6` |
| `SHIFT` `FRAC` | mixed fraction □ □/□ | no LineIO example. LineIO mixed input is `3 FRAC 1 FRAC 2` (gives 3⌟1⌟2) [G p.20]. |
| `√` | √□ | `√(` closed with `)` |
| `SHIFT` `√` | ∛□ | `∛(` … `)` |
| `x²` / `SHIFT x²` | ² / ³ postfix | same |
| `x^` | exponent slot □ | `^(` … `)` (INFERRED; the LineIO example `5 SHIFT x^ 32 )` uses the ⁿ√ form) |
| `SHIFT` `x^` | index-root template with an index slot | infix `ˣ√(`: `5 ˣ√( 32 )` |
| `x⁻¹` | ⁻¹ postfix | same |
| `log` | `log(` (base 10, or `log(a;b)` with a `;`) | same |
| `log□` | log with a base slot | **MathO / MthIO only.** The base must be entered. |
| `SHIFT` `log` | 10^□ | `10^(` … `)` (INFERRED) |
| `SHIFT` `ln` | e^□ | `e^(` … `)` |
| `∫` | ∫ from □ to □ of □ dx | `∫(f(x);a;b;tol)` |
| `SHIFT` `∫` | d/dx(□)│x=□ | `d/dx(f(x);a;tol)` |
| `SHIFT` `log□` | Σ with x=□ below and □ above, applied to (□) | `Σ(f(x);a;b)` |
| `SHIFT` `hyp` | Abs as │□│ | `Abs(` … `)` |

- **Template keys pressed after a number (MathO).**
  - Pressing `FRAC` **directly after typing a number** makes that number the numerator and moves the cursor to the denominator. `1 + 7 FRAC 6` gives 1 + 7/6, and `2 FRAC 3 ▶ + 1 FRAC 2 =` gives 7/6 [G p.18, p.20]. **VERIFIED.**
  - Pressing `FRAC` with nothing before it opens an empty fraction with the cursor in the numerator (`FRAC 2 ▼ 3`) [G p.20]. **VERIFIED.**
  - `x^`, `x²`, `x³` and `x⁻¹` apply to the preceding operand: `10 x^ 3`, or `( 1 + 1 ) x^ 2 + 2`, where the exponent slot holds `2+2` [G p.32]. **VERIFIED.**
- **x², x³, x^□ and x⁻¹ cannot follow one another directly.** The second key is ignored: `2 x² x²` ignores the last `x²`. To get 2^(2²), enter `2 x²`, press `◀`, then `x²` (MthIO-MathO) [G p.33]. **VERIFIED.**
- x², x³ and x⁻¹ also work on complex numbers [G p.33].

### 7.5 Using a value already entered as an argument (`SHIFT DEL` (INS) in Natural Display). VERIFIED [G p.18].

- Put the cursor **before** the value or expression and press `SHIFT` `DEL` (INS). The cursor changes shape: [G p.18]'s capture shows a heavy bar at the left edge of the fraction. Then press a function key. Everything to the right of the cursor, **up to the first opening parenthesis** (if there is one) **or up to the first function** (sin(30), log2(4) and so on), becomes the argument.
- Example: `1 + 7/6`, then `◀◀◀◀` `SHIFT DEL` `√`, gives `1+√(7/6)`.
- Functions that can wrap like this: fraction, mixed fraction, log□, ∫, d/dx, Σ, ⁿ√, 10^□, e^□, √, x^□, ∛, Abs.
- [S570] shows the result for wrapping `1+│(X+3)+4`. Wrapping with ∫ gives `1+∫(X+3)dX+4` with empty limits; d/dx and Σ wrap similarly. **INFERRED** to be the same on the ZA.

### 7.6 Deleting and clearing [G p.19]. VERIFIED.

- In insert mode, `DEL` deletes the character or function **to the left** of the cursor.
- In overwrite mode (LineIO), `DEL` deletes the character **under** the cursor.
- To insert, move with `◀`/`▶` and type. The guide says always to use insert mode for this in LineIO.
- `AC` clears the whole expression being entered.
- After `=` in Natural Display, part of a long input may be cut off. Press `AC`, then `◀`/`▶`, to scroll the input [G p.17].
  If both the result-continues marker `▶` and the input-continues marker `▷` are shown, press `AC` first and then scroll [G p.7].

---

## 8. Output display rules

### 8.1 Layout [G p.7]. VERIFIED.

- Two lines: the input expression at the top and the result at the bottom, **right-aligned**.
- A **filled `▶`** at the right of the **result** means the result continues: scroll it with `▶`/`◀`.
- A **hollow `▷`** at the right of the **input** means the input continues.
- Width of the input line, VERIFIED by counting characters in [G p.16] captures. A full line holds 16 characters (`4sin(30)(30+10×3`). When more input follows, the line shows 15 characters plus `▷` (`4×sin(30)×(30+1▷`).
- Two-part results:
  - In MathO they are joined on one line with `;`: `r=2;θ=45` and `2;R=1`.
  - In LineIO they are stacked on separate lines: `r=` above `θ=`. For ÷R, the line under the input holds the quotient on the right, and the bottom line holds `R=` on the left with the remainder on the right [G p.24, p.36].

### 8.2 Precision and digits [G p.88–89]. VERIFIED.

- Internal calculation uses **15 digits**.
- Displayed results have up to **10 significant digits**. π displays as `3,141592654` but is held internally as 3,14159265358980, and e displays as `2,718281828` but is held as 2,71828182845904 [G p.30].
- Calculation range: **±1×10⁻⁹⁹ to ±9,999999999×10⁹⁹**, or 0.
- Precision: generally ±1 at the 10th digit for a single calculation. In exponential display, ±1 at the least significant digit. Errors accumulate over consecutive calculations.
- Display rounding: Fix and Sci results are "rounded off" to the set digit before display. Norm shows 10 digits (Rnd in Norm rounds to 10 digits). How **ties** are rounded (half-up or half-even) is **UNKNOWN**; half-up is INFERRED, as is usual for Casio. Settle it with, for example, `0,125` under Fix 2 on the emulator.
- Internal number representation (BCD) is **INFERRED**, not stated.

### 8.3 Norm / Fix / Sci [G p.12–13]. VERIFIED.

| Setting | Rule | Guide example (LineIO) |
|---|---|---|
| Fix n (0–9) | n decimal places, rounded | `100÷7` gives `14,286` (Fix 3) and `14,29` (Fix 2) |
| Sci n (0–9, where 0 means 10) | n significant digits in exponential form, rounded | `1÷7` gives `1,4286×10⁻¹` (Sci 5), `1,429×10⁻¹` (Sci 4) and `1,428571429×10⁻¹` (Sci 0) |
| Norm 1 | exponential form when **\|x\| < 10⁻²** or **\|x\| ≥ 10¹⁰** | `1÷200` gives `5×10⁻³` |
| Norm 2 (default) | exponential form when **\|x\| < 10⁻⁹** or **\|x\| ≥ 10¹⁰** | `1÷200` gives `0,005` |

- Fix keeps trailing zeros: Fix 3 shows `10,000` [G p.39]. **VERIFIED.**
- In MathO, Fix does **not** turn a √-form or fraction result into a decimal:
  - Fix 3 with `√2 × 3 =` shows `3√2`, and `SHIFT =` then gives `4,243` [G p.33].
  - [SA s.5]: Fix 2 with `2/3` shows `2/3`, and `S⇔D` gives `0,67` (with `FIX` shown).
  - **VERIFIED.**
- **Exponent rendering** ([G p.12] capture of `5×10⁻³`): the mantissa, then a compact multiplication sign followed by a small "10", then the exponent in small raised digits. A negative exponent's minus is a short raised bar. **VERIFIED as an appearance.** Pixel details belong to the display research.
- Negative results are drawn with a leading minus, as in `-23,8` and `-1,290024053`. Whether the negative-sign glyph differs from the subtraction glyph is **UNKNOWN**. In the expression they are different tokens (§12 levels 5 and 11).

### 8.4 Engineering notation [G p.23–24]. VERIFIED examples. The general algorithm is INFERRED.

- `ENG` rewrites the displayed value with an exponent that is a multiple of 3: `1234 =` then `ENG` gives `1,234×10³`, and `ENG` again gives `1234×10⁰`.
- `SHIFT` `ENG` (←) does the same in the other direction: `123 =` then `SHIFT ENG` gives `0,123×10³`, and again gives `0,000123×10⁶`.
- **INFERRED model:**
  - The first `ENG` gives standard engineering form (1 ≤ \|mantissa\| < 1000).
  - Each further `ENG` lowers the exponent by 3, so the mantissa is ×1000.
  - Each `SHIFT ENG` raises the exponent by 3, so the mantissa is ÷1000.
  - The first `SHIFT ENG` on 123 gave exponent 3, which is one step above standard form (exponent 0).
  - Limits on how many steps are allowed (bounded by the 10-digit mantissa) are **UNKNOWN**.
- There are no SI-prefix symbols (k, M, m…) in this guide (§1 Z9).

### 8.5 Fractions [G p.19–22]. VERIFIED unless noted.

- **MathO:** a division or fraction input gives a **fraction** result, reduced to lowest terms (`1÷200` gives `1/200`; `14÷10×2` gives `14/5`). Improper or mixed form follows the ab/c / d/c setting.
- **LineIO:** fractions use `⌟` (`7⌟6`, `1⌟2`). A plain **division gives a decimal** (`1÷5 =` gives `0,2`, then `S⇔D` gives `1⌟5`). A fraction **typed with `FRAC`** gives a fraction (`1−4⌟5 =` gives `1⌟5`, then `S⇔D` gives `0,2`).
- Any calculation mixing fractions and decimals gives a **decimal** result, in both displays.
- `S⇔D` toggles a fraction result and its decimal. `SHIFT` `S⇔D` (a b/c ↔ d/c) toggles improper and mixed form.
- Converting a decimal to **mixed** form is impossible when the mixed fraction would need **more than 10 digits including the separators**. The range table says the same for a b/c in general: the integer, numerator and denominator together must be at most 10 digits including separators [G p.21, p.90].
- Digit limits for **MathO** fraction display are **UNKNOWN**. Presumably the same 10-digit rule applies (INFERRED). Settle with a fraction whose numerator and denominator have 6 or more digits each.
- How a mixed fraction is drawn in LineIO (presumably `1⌟1⌟6`) is INFERRED. [S570] shows `3⌟2` for 3÷2 with S⇔D.
- Ran# results display as fractions in Natural Display [G p.38].

### 8.6 √ form (surds) [G p.17–18]. Ranges VERIFIED.

- A surd result may have **at most two terms**. An integer term counts as a term.
- Results of the shape ± a√b/c ± d√e/f are displayed in one of these forms:
  - ± a√b
  - ± d ± a√b
  - (± a′√b ± d′√e) / c′
- Coefficient ranges, all integers:

  | | | |
  |---|---|---|
  | 1 ≤ a < 100 | 1 < b < 1000 | 1 ≤ c < 100 |
  | 0 ≤ d < 100 | 0 ≤ e < 1000 | 1 ≤ f < 100 |

- Outside these ranges the result is shown in decimal form.
- Guide examples:
  - `10√2 + 15×3√3` gives `45√3+10√2` (√ form).
  - `99√999` gives `3129,089165` (decimal form; it equals 297√111, and a = 297 is out of range).
- Results that combine a fraction and a surd use a common denominator c′ = lcm(c, f). After that reduction, a′, c′ and d′ may exceed their nominal ranges and still display in √ form, as in √3/11 + √2/10 = (10√3 + 11√2)/110. **INFERRED** from [S570 p.E-19].
- If any **intermediate** result has three or more terms, the final result is decimal. For example, (1+√2+√3)(1−√2−√3), which equals −4−2√6, displays −8.898979486. **INFERRED** from [S570 p.E-19].
- Which calculations can produce √ form, **INFERRED** from [S570 p.E-18]:
  - arithmetic on values containing √, x², x³ and x⁻¹
  - trigonometric functions
  - complex Abs
  - CMPLX polar display
- Trigonometric results are always in √ form, **INFERRED** from [S570], for these inputs:

  | Angle unit | Input |
  |---|---|
  | Deg | multiples of 15°, \|x\| < 9×10⁹ |
  | Rad | multiples of π/12, \|x\| < 20π |
  | Gra | multiples of 50/3 grad, \|x\| < 10000 |

  [S570] example: sin(60) gives √3/2.
- Additional examples from [S570] (INFERRED applicable):

  | Calculation | Display |
  |---|---|
  | 2√3×4 | 8√3 |
  | 35√2×3 | decimal 148.492424 (equals 105√2; out of range) |
  | 150√2/25 | decimal 8.485281374 |
  | 2×(3−2√5) | 6−4√5 |
  | 23×(5−2√3) | decimal 35.32566285 |
  | 15×(10√2+3√3) | decimal 290.0743207 |
  | √2+√3+√8 | √3+3√2 |
  | √2+√3+√6 | decimal 5.595754113 (three terms) |

  These would display with a comma on the ZA.
- Once a result is in decimal form, it cannot be turned into √ form [S570 p.E-41]. The ZA guide says the same about `SHIFT =` (§8.8). **VERIFIED** for the `SHIFT =` case.

### 8.7 π form [G p.91]. Range VERIFIED.

- π form can be displayed when the result satisfies **\|x\| < 10⁶**, in Natural Display.
- Internal rounding error can stop some results from showing in π form, and can also make a result that should be decimal appear in π form.
- Example [G p.20]: `π÷6 =` gives `1/6 π`, displayed as the stacked fraction 1 over 6 followed by π. `S⇔D` gives `0,5235987756`.
- The integration result ∫₀^π (sin x + cos x)² dx displays as `π` [G p.34].
- Supported shapes: nπ (n an integer), (d/c)π, or a(b/c)π (following the fraction setting). Fractional-π forms are limited to inverse-trigonometric results and values normally expressed in radians. **INFERRED** from [S570 p.E-41]; [S570] gives sin⁻¹(0.5) = (1/6)π in Rad.
- **Cross-check caveat.** That [S570] restriction is from the *original* 2005 ES, and the ZA's own example C-01 (`π÷6` → `1/6 π`) is computed in **Deg** mode from an expression that merely contains π. So the ZA's eligibility rule is **UNKNOWN**: either every result with |x| < 10⁶ is tested for a π multiple (07 §4.3's reading), or only results whose expression involves π / inverse trig / radian values. The two disagree on, e.g., `0,7853981634 =` or `1÷4×3,1415926535898 =` in MathO (decimal, or `1/4 π`?), where no π token is involved. `cos⁻¹(0)` in Rad should give `1/2 π` under either rule (control case). Settle with test T16 in 07 §6.

### 8.8 `S⇔D` and `SHIFT =` [G p.20–21]. VERIFIED.

- **Natural Display:** `S⇔D` toggles fraction ↔ decimal, √ form ↔ decimal, or π form ↔ decimal.
- **LineIO:** `S⇔D` toggles decimal ↔ fraction.
- Conversion can take time, and some results do not convert.
- `S⇔D` and `°'"` are **disabled while a ÷R result is displayed** [G p.24].
- In MathO, pressing **`SHIFT` `=`** instead of `=` gives a **decimal** result for a calculation that would otherwise produce √ form, π form, or a division (fraction) result.
  - A following `S⇔D` then switches to fraction or π form.
  - It never switches to √ form.
- [S570 p.E-17] adds that in LineIO, results are always decimal whether `=` or `SHIFT =` is used. **INFERRED** for the ZA.

### 8.9 Degrees, minutes, seconds [G p.21–22, p.90]. VERIFIED.

- **Input:** degrees `°'"` minutes `°'"` seconds `°'"`. Degrees and minutes must always be entered, even when they are 0.
- **Results:**
  - DMS ± DMS gives a DMS result.
  - DMS × or ÷ a decimal gives a DMS result.
- **Display:** no zero padding, as in `3°0'0"` and `2°15'18"`.
- Pressing `°'"` on a displayed result toggles decimal ↔ sexagesimal: `2°15'18"` gives `2,255`, which gives `2°15'18"` again.
- **Ranges:**
  - Input a°b'c" requires \|a\|, b, c < 1×10¹⁰⁰ and 0 ≤ b, c.
  - Displayed seconds may be off by ±1 at the second decimal place.
  - Decimal ↔ sexagesimal conversion works for 0°0'0" ≤ \|x\| ≤ 9999999°59'59".

### 8.10 Recurring decimals, SI symbols

Neither appears in [G]. Treat both as **not present** (§1 Z9).

### 8.11 "Disp"

The `Disp` indicator marks an intermediate multi-statement result (§10.1) [G p.9]. **VERIFIED.**

---

## 9. Memory [G p.26–29]. VERIFIED unless noted.

| Memory | Behaviour |
|---|---|
| **Ans** | Holds the last result. It is updated each time a new result is displayed. Pressing an operator first after a result inserts `Ans` automatically: `3×4 =` then `÷30 =` shows the input `Ans÷30` [G p.27 screen]. The screen confirms this for `÷`. That other binary and postfix operators do the same is **INFERRED**. The `Ans` key inserts `Ans` (`789−Ans`). Only the quotient of a ÷R result goes into Ans. **Which keys update Ans** (added by the cross-check): the international fx-570/991ES PLUS 2nd-edition guide says Ans is updated by `=`, `SHIFT =`, `M+`, `M−` (SHIFT M+), `RCL` and `STO` (SHIFT RCL), and holds 15 digits (official sibling guide, file 06 [ES2]; the ZA guide dropped the sentence) — so INFERRED the same here. Whether Pol/Rec results and errors update Ans stays **UNKNOWN** (Pol/Rec write X and Y). |
| **PreAns** | Holds the result before the last one. A new result moves Ans into PreAns and then stores the new value in Ans. **COMP only.** It is cleared when you leave COMP for another mode. It survives `AC` and power-off as long as COMP is not left. Inserted with `ALPHA` `Ans` (PreAns). Example: `Ans+PreAns` with `=` repeated generates the Fibonacci numbers. |
| **Variables A, B, C, D, E, F, M, X, Y** | Nine variables. Store with `SHIFT` `RCL` (STO), then the variable key. The displayed result is the stored value: `3+5 STO A` shows `8`. Recall with `RCL` + variable, or use `ALPHA` + variable inside an expression. Clear one by storing 0 in it. |
| **M (independent memory)** | M is the same storage as variable M. `M+` evaluates the current expression and adds the result to M; the displayed value is that result (`10×5 M+` shows `50`). `SHIFT` `M+` (M−) subtracts (`10+5 M−` shows `15`). Recall with `RCL M+`. The `M` indicator shows when M ≠ 0. |
| Retention | Ans, M and the variables survive `AC`, mode changes and power-off. CLR Memory or CLR All clears them. Removing the battery clears everything. |

Pol and Rec write their results into **X and Y** (r→X, θ→Y; x→X, y→Y) [G p.37]. ÷R with STO stores only the quotient (`5÷R2 STO X` gives X = 2) [G p.24].

---

## 10. COMP-mode features

### 10.1 Multi-statements [G p.23]

- `ALPHA` `∫` enters `:`. The statements run left to right, one per `=`.
- Example: `3+3:3×3 =` shows `6`, with **Disp** shown, and `=` again shows `9`.
- **VERIFIED.**

### 10.2 History and replay [G p.25–26]

- **History** exists in COMP, CMPLX and BASE-N. It holds about **200 bytes** of the newest calculations. Scroll with `▲`/`▼`: after `1+1=`, `2+2=` and `3+3=`, pressing `▲` shows `4` and `▲` again shows `2`.
- History is **cleared** by:
  - `ON`
  - a mode change
  - a display-format change
  - CLR Setup
  - CLR All
- **Replay:** while a result is shown, `◀` or `▶` re-opens the previous expression for editing. VERIFIED by example: `4×3+2 =` gives `14`, then `◀ DEL DEL − 7 =` gives `5`. So `◀` puts the cursor at the **end**. That `▶` puts it at the **start** is INFERRED.
- When the result shows `▶` (it continues), press `AC` first, then scroll [G p.7].

### 10.3 Percent [G p.22]

- `SHIFT` `(` (%) is a postfix operator that divides the value before it by 100 (priority level 3).
- Examples:

  | Input | Result |
  |---|---|
  | `150×20%` | `30` |
  | `660÷880%` | `75` |
  | `2500+2500×15%` | `2875` |
  | `3500−3500×25%` | `2625` |

- **VERIFIED** examples. The ÷100 semantics are confirmed by [S570], which gives "2% = 0.02".

### 10.4 Angle units and conversion

- Set the angle unit in SETUP (§5).
- Inside an expression, the DRG▶ menu (`SHIFT` `Ans`) appends a unit to a value: `1` °, `2` ʳ, `3` ᵍ. Items 2 and 3 are VERIFIED; item 1 is SECONDARY (cross-check: the GYX string table's DRG menu is `1:°     2:r` / `3:g`, two items per row).
- The value is converted to the current default unit. In Deg, `(π÷2)ʳ =` gives `90` and `50ᵍ =` gives `45` [G p.31].
- For trigonometric functions inside ∫ or d/dx, use Rad [G p.34–35].

### 10.5 Hyperbolic [G p.30–31]

- `hyp` opens a menu. Item `1` is sinh and item `5` is cosh⁻¹ (VERIFIED).
- Full menu, **SECONDARY** (cross-check; GYX string table, consistent with the two VERIFIED items): three lines of two, `1:sinh  2:cosh` / `3:tanh  4:sinh⁻¹` / `5:cosh⁻¹ 6:tanh⁻¹`.
- The angle unit does not affect these functions.
- Examples: `sinh(1)` gives `1,175201194`, and `cosh⁻¹(1)` gives `0`.

### 10.6 Logarithms, exponentials, powers [G p.31–33]

- `log(a;b)` means logₐb. With no base given, the base is 10.
- `log□` (MathO only) requires a base to be entered.
- Examples:

  | Calculation | Result | Setup |
  |---|---|---|
  | log 1000 | `3` | |
  | log₂16 | `4` | |
  | log₂(4³) | `6` | |
  | log₂(4)³ | `8` | |
  | ln 90 | `4,50×10⁰` | Sci 3 |
  | e⁵×2 | `2,97×10²` | Sci 3 |
  | 1,2×10³ | `1200` | |
  | (1+1)^(2+2) | `16` | |
  | (5²)³ | `15625` | |
  | ⁵√32 | `2` | |
  | ∛5 + ∛−27 | `-1,290024053` | |
  | 1/(1/3 − 1/4) via `x⁻¹` | `12` | |

- See 04 for the exact keys.

### 10.7 Integration ∫ [G p.33–34]. VERIFIED.

- Gauss–Kronrod numerical integration.
- Syntax: MathO ∫ₐᵇ f(x)dx; LineIO `∫(f(x);a;b;tol)`.
- **tol defaults to 1×10⁻⁵.** Use tol ≥ 1×10⁻¹⁴. A smaller tol is more precise and slower.
- **COMP only.**
- Not allowed in f(x): Pol, Rec, ÷R. Not allowed in f(x), a, b or tol: ∫, d/dx, Σ.
- Use Rad for trigonometric f(x).
- Usually slow. `AC` interrupts it [G p.30].
- If the error exceeds the tolerance, an error is shown. **Time Out** occurs when the end condition is not met (§11).
- Accuracy suffers with discontinuities, abrupt changes or very wide intervals. Split the interval, or integrate positive and negative parts separately.
- Integrating an f(x) that is negative over [a, b] gives a negative result [S570] (INFERRED; mathematically obvious).

### 10.8 Differentiation d/dx [G p.34–35]. VERIFIED.

- Central-difference approximation.
- Syntax: MathO d/dx(f(x))│x=a; LineIO `d/dx(f(x);a;tol)`.
- **tol defaults to 1×10⁻¹⁰.** Use tol ≥ 1×10⁻¹⁴.
- COMP only. Same exclusions as ∫. Use Rad for trigonometric functions.
- If tol is omitted and the calculation does not converge, tol is adjusted automatically.
- Discontinuities, abrupt fluctuation, extreme points, inflection points, non-differentiable points, or derivatives near zero may give poor precision or an error.

### 10.9 Σ [G p.35–36]. VERIFIED.

- Σ = f(a) + f(a+1) + … + f(b).
- Syntax: MathO Σ with x=a below and b above; LineIO `Σ(f(x);a;b)`. There is **no tol**.
- a and b are **integers** with **−1×10¹⁰ < a ≤ b < 1×10¹⁰**.
- Exclusions: Pol, Rec and ÷R are not allowed in f(x); ∫, d/dx and Σ are not allowed in f(x), a or b.
- Example: Σ from x=1 to 5 of (x+1) gives `20`.
- There is no ∏ (§1 Z9).

### 10.10 Pol / Rec [G p.36–37]. VERIFIED.

- `Pol(x;y)` gives (r; θ) and `Rec(r;θ)` gives (x; y).
- Results go into X and Y.
- θ is displayed in **−180° < θ ≤ 180°**.
- MathO display: `r=2;θ=45` and `X=1;Y=1`. LineIO stacks the two values.
- The printed text in [G] has spaces ("r = 2; θ = 45"). The screen capture [G p.7] shows no spaces: `r=2;θ=45`.
- Prime factorisation is refused while a Pol/Rec result is displayed.

### 10.11 x!, Abs, Ran#, RanInt#, nPr, nCr, Rnd, GCD, LCM [G p.38–40]. VERIFIED.

- **x!**: integers 0–69.
- **Abs**: │□│ template in MathO, `Abs(` in LineIO.
- **Ran#**: 3-digit pseudo-random value from 0,000 to 0,999, shown as a fraction in Natural Display. The guide's sample values are illustrative.
- **RanInt#(a;b)**: random integer from a to b.
- **nPr / nCr**: infix (`10 nPr 4` gives `5040`; `10 nCr 4` gives `210`).
- **Rnd(x)**: makes the argument a decimal and rounds it to the current display setting, replacing the internal value.
  - Norm 1/2: 10 digits.
  - Fix/Sci: the set digit.
  - Example in Fix 3: `10÷3×3` gives `10,000`, but `Rnd(10÷3)×3` gives `9,999`, because 10÷3 is held internally as 3,33333333333333 (15 digits).
- **GCD(a;b)** and **LCM(a;b)**: two arguments only (GCD(28;35) gives `7`; LCM(9;15) gives `45`).
- **Int / Intg**: not present.

### 10.12 Remainder division ÷R [G p.24]. VERIFIED.

- `a ÷R b` gives the quotient and remainder. Its priority is the same as × and ÷.
- Display:
  - MathO: `2;R=1`.
  - LineIO: two lines. [SA s.9] shows `75÷R4` giving `18;R=3`.
- Only the **quotient** goes to Ans, to STO, and on into a longer expression: `10+17÷R6 =` is evaluated as 10+2, which gives **12**.
- `S⇔D` and `°'"` are disabled while the result is displayed.
- **Falls back to ordinary division** when:
  - the dividend or divisor is very large (`20000000000 ÷R 17` is treated as `20000000000÷17`). The threshold for "very large" is **UNKNOWN**; the 10-digit integer limits elsewhere suggest more than 10 digits (INFERRED).
  - the quotient is not a positive integer, or the remainder is not a positive integer or positive fraction (`−5 ÷R 2` is treated as `−5÷2`).

  The guide does not print the resulting values for these cases. (File 06 §5.6 wrote `(−)5 ÷R 2 → −2,5`; that value is INFERRED, not printed. In MathO a plain division of integers gives a fraction, so `-5/2` stacked is at least as likely — vector C-24 in 04.) Note also the guide's wording "remainder is not a positive integer **or positive fractional value**": ÷R evidently accepts fraction operands and can return a fractional remainder (e.g. `7/2 ÷R 1` → quotient 3, remainder 1/2 is the expected reading; UNKNOWN until tried).

### 10.13 Prime factorisation FACT [G p.25]. VERIFIED.

- Works on a displayed **positive integer of up to 10 digits**, in COMP.
- Press `SHIFT` `°'"` (FACT) while the result is displayed:
  - `1014` gives `2×3×13²`.
  - A prime factor with **more than three digits** is left unfactored in parentheses: `4104676` gives `2²×(1026169)`, where 1026169 = 1013².
- The factorisation display is left by:
  - FACT again
  - `=`
  - `ENG`
  - `°'"`
  - changing the angle unit or Fix/Sci/Norm in SETUP
- A decimal, fraction or negative result gives **Math ERROR**.
- FACT is not available while a Pol, Rec or ÷R result is displayed.

### 10.14 CALC [G p.40–41]. VERIFIED.

- **COMP and CMPLX.**
- It stores an expression containing variables and evaluates it repeatedly. Allowed forms:
  - plain expressions (`2X+3Y`, `2AX+3BX+C`, `A+Bi`)
  - multi-statements (`X+Y:X(X+Y)`)
  - an assignment with a single variable on the left (`A=B+C`, `Y=X²+X+3`, typed with `ALPHA CALC` (=))
- Pressing `CALC` prompts for each variable in turn. The prompt is `A?` on the top line with the variable's **current value** at the bottom right.
- Each value is entered with `=`. After the last one, the result is shown with the expression on the top line.
- `CALC` (or `=`) starts the prompts again. `AC` exits.
- Inputs typed during the prompts use **Linear Display** procedures, for example `√3)` closed with `)`.

### 10.15 SOLVE [G p.41–43]. VERIFIED unless noted.

- Newton's method. **COMP only.**
- Equation forms:
  - an equation containing X (an expression without `=` is treated as `= 0`)
  - `{equation}, {solution variable}` to solve for another variable
- On the ZA the separator in the second form is presumably **`;`** (`Y=X+5;Y`), because the comma key is the decimal point. [G]'s prose still prints a comma here. **UNKNOWN**: settle on the emulator.
- Do not omit closing parentheses.
- ∫, d/dx, Σ, Pol, Rec and ÷R are not allowed.
- **Flow (`SHIFT` `CALC`):**
  1. The calculator prompts for every other variable in order of appearance: `Y?`, then the next variable, and so on. Each prompt shows the current value at the bottom right, and the values are entered with `=`.
  2. The screen `Solve for X` appears, with the current X as the initial guess at the bottom right. Enter a guess, or keep the value shown, and press `=`.
  3. The solution screen appears.
- **Solution screen**, three lines in the small font: the equation, then `X=` with the solution right-aligned, then `L-R=` with the left side minus the right side right-aligned. The solution is **always decimal**. Example: `Y=AX²+B`, `X= 1,414213562`, `L-R= 0`.
- `=` on the solution screen starts the prompts again. `AC` exits.
- **Continue screen:** after a preset number of iterations without convergence, the calculator shows "Continue: [=]". `=` continues and `AC` cancels. The firmware string is `Continue:[=]` (no space; SECONDARY, GYX string table). The prompt before solving is built from `Solve for ` + the variable name (same source). The screen layout and the iteration count are **UNKNOWN**.
- Only one root is returned. It depends on the initial value. Functions like sin, eˣ and √x are hard for the method.
- Errors: **Variable ERROR** and **Can't Solve** (§11).
- [SA s.14] shows that the initial value offered by `Solve for X` is the **current X**. After an earlier solve gave X=2, solving `X²−6X=0` from 2 gives `0`.

### 10.16 Scientific constants and metric conversion [G p.43–47]

- `SHIFT 7` (CONST) shows `CONSTANT`, `Number 01~40?` and an input field `[__]` (screen VERIFIED). Two digits insert the constant symbol, for example `C₀` (28). `=` evaluates it: `299792458`.
- 40 constants (CODATA 2014). They can be used in every mode except BASE-N.
- `SHIFT 8` (CONV) shows `CONVERSION`, `Number 01~40?` and `[__]`. 40 conversions (NIST SP 811, 2008). They can be used in every mode except BASE-N and TABLE.
- Examples (LineIO):

  | Input | Result |
  |---|---|
  | `5cm▶in` | `1,968503937` |
  | `100g▶oz` | `3,527396584` |
  | `-31°C▶°F` | `-23,8` |

- J▶cal is for 15 °C.
- Conversion commands are priority level 6 (§12).
- **Factors (cross-check):** see 03 §15. The km/h pair is exact (÷3,6 / ×3,6), **not** NIST's printed 0,2777778: [SA s.19] shows `120km/h▶m/s` → `33,33333333`. The sibling firmware's factor table also differs from NIST's printed table for acre (4046,856) and hp (0,7457).
- A CONV number above 40 gives **Syntax ERROR** (SECONDARY: the GYX ROM configuration stores the maximum conversion index, 40, with that behaviour documented beside it).
- The full code tables are in [G p.44–46]. They are probably covered by another research file; codes:

  | Code | Constant |
  |---|---|
  | 01 | mp |
  | 02 | mn |
  | 03 | me |
  | 04 | mμ |
  | 05 | a0 |
  | 06 | h |
  | 07 | μN |
  | 08 | μB |
  | 09 | ħ |
  | 10 | α |
  | 11 | re |
  | 12 | λC |
  | 13 | γp |
  | 14 | λCp |
  | 15 | λCn |
  | 16 | R∞ |
  | 17 | u |
  | 18 | μp |
  | 19 | μe |
  | 20 | μn |
  | 21 | μμ |
  | 22 | F |
  | 23 | e |
  | 24 | NA |
  | 25 | k |
  | 26 | Vm |
  | 27 | R |
  | 28 | C0 |
  | 29 | C1 |
  | 30 | C2 |
  | 31 | σ |
  | 32 | ε0 |
  | 33 | μ0 |
  | 34 | Φ0 |
  | 35 | g |
  | 36 | G0 |
  | 37 | Z0 |
  | 38 | t |
  | 39 | G |
  | 40 | atm |

  | Code | Conversion | Code | Conversion |
  |---|---|---|---|
  | 01 | in▶cm | 02 | cm▶in |
  | 03 | ft▶m | 04 | m▶ft |
  | 05 | yd▶m | 06 | m▶yd |
  | 07 | mile▶km | 08 | km▶mile |
  | 09 | n mile▶m | 10 | m▶n mile |
  | 11 | acre▶m² | 12 | m²▶acre |
  | 13 | gal(US)▶ℓ | 14 | ℓ▶gal(US) |
  | 15 | gal(UK)▶ℓ | 16 | ℓ▶gal(UK) |
  | 17 | pc▶km | 18 | km▶pc |
  | 19 | km/h▶m/s | 20 | m/s▶km/h |
  | 21 | oz▶g | 22 | g▶oz |
  | 23 | lb▶kg | 24 | kg▶lb |
  | 25 | atm▶Pa | 26 | Pa▶atm |
  | 27 | mmHg▶Pa | 28 | Pa▶mmHg |
  | 29 | hp▶kW | 30 | kW▶hp |
  | 31 | kgf/cm²▶Pa | 32 | Pa▶kgf/cm² |
  | 33 | kgf·m▶J | 34 | J▶kgf·m |
  | 35 | lbf/in²▶kPa | 36 | kPa▶lbf/in² |
  | 37 | °F▶°C | 38 | °C▶°F |
  | 39 | J▶cal | 40 | cal▶J |

- **VERIFIED.**
- **Note:** [G p.46] describes constant 26 (Vm) as "molar volume of ideal gas (237,15K; 100kPa)". 237,15 K is almost certainly a **misprint for 273,15 K** (INFERRED). The value stored in the calculator must be checked on the emulator.

---

## 11. Errors [G p.83–86]. VERIFIED unless noted.

### Keys on an error screen

- `◀` or `▶` returns to the calculation with the **cursor at the error position**, ready for editing.
  - Example: `14÷0×2 =` gives Math ERROR. Pressing `▶` shows `14÷0|×2`, with the cursor just after the 0.
  - Then `◀ 1 =` gives `14÷10×2` and the result `14/5`.
- `AC` clears the message **and the expression**.

### Math ERROR screen (VERIFIED capture [G p.83])

```
Math ERROR

[AC]  :Cancel
[◀][▶]:Goto
```

It uses the small font, and the `D` and `Math` indicators stay lit. The other errors are assumed to use the same layout with their own first line (INFERRED). Whether **every** error type offers `[◀][▶]:Goto` is **UNKNOWN**.

**Firmware strings (added by the cross-check).** The sibling ES PLUS GYX firmware (07 [S13]) holds exactly these message strings: `Syntax ERROR`, `Math ERROR`, `Insufficient MEM`, `Stack ERROR`, `Argument ERROR`, `Dimension ERROR`, `Can't Solve`, `Time Out`, `Variable ERROR`, plus two internal ones (`AC Break`, `NULL ERROR`) and the footer lines `[AC]  :Cancel` and `[◀][▶]:Goto`. SECONDARY for the ZA; the `Math ERROR` screen and the CLR screens it shares with the ZA match character for character.

### Error messages

| Message (heading in [G]) | On-screen first line | Causes |
|---|---|---|
| Math ERROR | `Math ERROR` (VERIFIED) | An intermediate or final result is outside the calculation range. An input is outside a function's allowed range. An illegal operation such as division by zero. FACT on a non-positive-integer result. RATIO with a 0 coefficient. |
| Stack ERROR | `Stack ERROR` (SECONDARY, firmware string) | The numeric or command stack capacity is exceeded, or (MATRIX/VECTOR) the matrix or vector stack is exceeded. Stack sizes are **UNKNOWN**. |
| Syntax ERROR | `Syntax ERROR` (SECONDARY) | Badly formed expression. |
| Argument ERROR | `Argument ERROR` (SECONDARY) | Problem with a function argument. |
| Dimension ERROR | `Dimension ERROR` (SECONDARY) | MATRIX/VECTOR only. Dimension not set, or dimensions incompatible. |
| Variable ERROR | `Variable ERROR` (SECONDARY) | SOLVE only. No solution variable given and no X in the equation, or the given variable is not in the equation. |
| Can't Solve Error | `Can't Solve` (SECONDARY; no "ERROR" suffix) | SOLVE only. No solution found. |
| Insufficient MEM Error | `Insufficient MEM` (SECONDARY) | TABLE only. Too many rows: more than 30 with f(x), more than 20 with f(x),g(x). |
| Time Out Error | `Time Out` (SECONDARY) | ∫ or d/dx ended without meeting its end condition. Increase tol. |

---

## 12. Calculation priority sequence [G p.87–88]. VERIFIED.

Operations with equal priority are evaluated left to right. Level 1 binds tightest.

| Level | Operations |
|---|---|
| 1 | Parenthesised expressions |
| 2 | Functions that take an argument on the right followed by `)`: sin(, log(, Pol(, etc. |
| 3 | Postfix functions: x², x³, x⁻¹, x!, °'", °, ʳ, ᵍ, %, ▶t (DIST); powers x^□; roots ⁿ√ |
| 4 | Fractions |
| 5 | Negative sign (−); base-n prefixes d, h, b, o |
| 6 | Metric conversion commands (cm▶in, etc.); STAT estimated values x̂, ŷ, x̂₁, x̂₂ |
| 7 | Multiplication with the sign omitted |
| 8 | nPr, nCr; complex polar ∠ |
| 9 | Dot product (•) |
| 10 | ×, ÷, ÷R |
| 11 | +, − |
| 12 | and (logical) |
| 13 | or, xor, xnor (logical) |

- Because x² (level 3) binds tighter than the negative sign (level 5), `(−) 2 x²` squares 2 and then negates it. Use `( (−) 2 ) x²` to square −2 [G p.88]. The values are not printed; they would be −4 and 4 (INFERRED).

---

## 13. Function input ranges [G p.89–91]. VERIFIED. Comma decimals as printed.

| Function | Input range |
|---|---|
| sin x, cos x | Deg: 0 ≤ \|x\| < 9×10⁹. Rad: 0 ≤ \|x\| < 157079632,7. Gra: 0 ≤ \|x\| < 1×10¹⁰ |
| tan x | As sin x, except \|x\| = (2n−1)×90 (Deg), (2n−1)×π/2 (Rad), (2n−1)×100 (Gra) |
| sin⁻¹x, cos⁻¹x | 0 ≤ \|x\| ≤ 1 |
| tan⁻¹x | 0 ≤ \|x\| ≤ 9,999999999×10⁹⁹ |
| sinh x, cosh x | 0 ≤ \|x\| ≤ 230,2585092 |
| sinh⁻¹x | 0 ≤ \|x\| ≤ 4,999999999×10⁹⁹ |
| cosh⁻¹x | 1 ≤ x ≤ 4,999999999×10⁹⁹ |
| tanh x | 0 ≤ \|x\| ≤ 9,999999999×10⁹⁹ |
| tanh⁻¹x | 0 ≤ \|x\| ≤ 9,999999999×10⁻¹ |
| log x, ln x | 0 < x ≤ 9,999999999×10⁹⁹ |
| 10ˣ | −9,999999999×10⁹⁹ ≤ x ≤ 99,99999999 |
| eˣ | −9,999999999×10⁹⁹ ≤ x ≤ 230,2585092 |
| √x | 0 ≤ x < 1×10¹⁰⁰ |
| x² | \|x\| < 1×10⁵⁰ |
| x⁻¹ | \|x\| < 1×10¹⁰⁰, x ≠ 0 |
| ∛x | \|x\| < 1×10¹⁰⁰ |
| x! | 0 ≤ x ≤ 69, x an integer |
| nPr | 0 ≤ n < 1×10¹⁰, 0 ≤ r ≤ n (integers); 1 ≤ n!/(n−r)! < 1×10¹⁰⁰ |
| nCr | 0 ≤ n < 1×10¹⁰, 0 ≤ r ≤ n (integers); 1 ≤ n!/r! < 1×10¹⁰⁰ or 1 ≤ n!/(n−r)! < 1×10¹⁰⁰ |
| Pol(x;y) | \|x\|, \|y\| ≤ 9,999999999×10⁹⁹; √(x²+y²) ≤ 9,999999999×10⁹⁹ |
| Rec(r;θ) | 0 ≤ r ≤ 9,999999999×10⁹⁹; θ as for sin x |
| °'" | a°b'c": \|a\|, b, c < 1×10¹⁰⁰; 0 ≤ b, c. Displayed seconds are ±1 at the 2nd decimal place. |
| °'" ← (sexagesimal ↔ decimal) | \|x\| < 1×10¹⁰⁰; conversions 0°0'0" ≤ \|x\| ≤ 9999999°59'59" |
| xʸ | x > 0: −1×10¹⁰⁰ < y·log x < 100. x = 0: y > 0. x < 0: y = n or m/(2n+1) (m, n integers), with −1×10¹⁰⁰ < y·log\|x\| < 100 |
| ˣ√y | y > 0: x ≠ 0, −1×10¹⁰⁰ < (1/x)·log y < 100. y = 0: x > 0. y < 0: x = 2n+1 or (2n+1)/m (m ≠ 0; m, n integers), with −1×10¹⁰⁰ < (1/x)·log\|y\| < 100 |
| a b/c | integer + numerator + denominator ≤ 10 digits, including the separators |
| RanInt#(a;b) | a < b; \|a\|, \|b\| < 1×10¹⁰; b − a < 1×10¹⁰ |
| GCD(a;b) | \|a\|, \|b\| < 1×10¹⁰ (integers) |
| LCM(a;b) | 0 ≤ a, b < 1×10¹⁰ (integers) |
| Σ bounds | integers, −1×10¹⁰ < a ≤ b < 1×10¹⁰ [G p.35] |
| FACT | positive integer of up to 10 digits [G p.25] |
| π form | \|result\| < 10⁶ [G p.91] |

Notes [G p.91]:

- Precision is as described in §8.2.
- xʸ, ˣ√y, ∛, x!, nPr and nCr chain internal calculations, so errors can accumulate.
- Errors grow near singular points and inflection points.

---

## 14. Miscellaneous facts [G p.5–6, p.91]. VERIFIED.

- Power: solar cell plus one LR44 (GPA76).
- Battery life about 3 years at 1 h/day. Replace the battery every 3 years.
- Operating temperature 0–40 °C.
- Size 11,1 × 77 × 161,5 mm. Weight 95 g.
- Auto power-off after about 10 or about 60 minutes of non-use (APO setting). `ON` restores power.
- A hard case slides off and fits on the back.

---

## 15. Open questions / UNKNOWN (with how to settle)

| # | Question | How to settle |
|---|---|---|
| 1 | ~~Exact submenu screens for MthIO, CMPLX, STAT, and the Sci prompt text.~~ Now SECONDARY from the GYX string table (§5.2). Still UNKNOWN: the TABLE and APO submenu screens (2nd-edition items). | Emulator screenshots |
| 2 | Byte cost of each token. Which functions cost 3–13 bytes. | Fill the 99-byte buffer on the emulator and note when the ■ cursor appears |
| 3 | Rounding on ties (Fix/Sci/Norm display and Rnd). Internal arithmetic (BCD, 15 digits, guard digits). | Emulator: `0,125` in Fix 2; `2,5` in Fix 0 |
| 4 | ENG / ←ENG algorithm beyond the guide's 4 data points. Its limits. | Emulator sweep over values like 1, 12, 0,5, 1×10⁻⁵ |
| 5 | ~~On-screen text of every error other than Math ERROR~~ (now SECONDARY, §11). Still UNKNOWN: whether each offers Goto. | Trigger each error on the emulator |
| 6 | SOLVE `{equation}{sep}{variable}` separator on the ZA (`;` expected). Continue-screen layout. Iteration count. Can't Solve screen. | Emulator |
| 7 | ∫ / d/dx iteration limits and time-out criteria. | Not documented. Black-box test on the emulator. |
| 8 | Numeric and command stack sizes behind Stack ERROR. Parenthesis nesting limit. | Emulator: nest `(` until it fails |
| 9 | √-form production rules (which operations, trig input sets). The ZA guide gives only the coefficient ranges; the rest is from [S570]. | Emulator: `sin 60`, `cos 45`, `2√3×4`, `35√2×3` |
| 10 | π-form shapes and producers (from [S570]). | Emulator: `sin⁻¹ 0,5` in Rad, `π×2÷3` |
| 11 | Digit limits for MathO fraction results. Mixed-fraction rendering in LineIO. | Emulator |
| 12 | Whether Pol/Rec, STO, M+ and errors update Ans/PreAns. Which keys auto-insert `Ans`. | Emulator |
| 13 | Glyph difference between the negative sign and the subtraction sign. | Emulator pixel capture |
| 14 | Whether `Math` shows in MthIO-LineO. | Emulator |
| 15 | `▶` replay cursor position (start of expression, as expected). | Emulator |
| 16 | The ÷R "very large" threshold. | Emulator: 10-digit vs 11-digit dividends |
| 17 | ~~Hyperbolic menu items 2, 3, 4, 6, and DRG▶ item 1.~~ SECONDARY from the GYX string table (§10.4, §10.5). | Emulator, to promote to VERIFIED |
| 18 | The Vm constant's reference temperature (guide prints 237,15 K). | Emulator: `SHIFT 7 26 =` (expect 0,022710947 for 273,15 K / 100 kPa) |
| 19 | ~~Model identity~~ — settled by the cross-check (00-index.md §1): keyboard/body sibling fx-991ES PLUS-2, behavioural sibling fx-115ES PLUS / fx-991ES PLUS C 2nd edition. | — |
