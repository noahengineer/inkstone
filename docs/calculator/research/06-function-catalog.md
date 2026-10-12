# 06 — Function catalog of the CASIO fx-991ZA PLUS II, organised by key

Research file 06. **Scope:** every function the fx-991ZA PLUS II offers, with the key or menu that
reaches it, the modes it works in, its syntax and range, and anything unusual about it. It also
covers the sub-menus behind SHIFT 1–5, MODE, SETUP, hyp and DRG▸, how CONST and CONV are
selected, the official function count, and every difference from the international siblings.

Other files cover the display (02), the per-mode walkthroughs (03), the worked examples (04, 05)
and the Inkstone integration (08). Where this file repeats a fact from them, it gives its own
source.

---

## 0. How to read this file

### 0.1 Confidence tags

| Tag | Meaning |
|---|---|
| **V** | VERIFIED. Read in an official CASIO source for **this model**: the fx-991ZA PLUS II user's guide, or CASIO's official product image or page for it. |
| **V\*** | VERIFIED, but in an official CASIO source for a **sibling** model, and used only to describe that sibling. It is never used as evidence about the ZA PLUS II. |
| **S** | SECONDARY. A CASIO distributor or retailer, a CASIO social-media post seen only as a search snippet, or a community source. |
| **I** | INFERRED. This researcher's reasoning. The reasoning is given. |
| **U** | UNKNOWN. Not established. §13 says what would settle each one. |

### 0.2 Source keys (full URLs in §14)

| Key | Source | Kind |
|---|---|---|
| **[M:path]** | Official online User's Guide for the fx-991ZA PLUS II: `https://support.casio.com/global/en/calc/manual/fx-991ZAPLUSII_en/<path>`. All 71 pages were downloaded on 2026-10-02 and converted to text. Every key image was mapped to its key by viewing the glyph images (`img/key/573xx.png`). | Official, this model |
| **[MP]** | The same guide as a PDF (`fx-991ZA_PLUSII_EN.pdf`, 2,063,378 bytes). Its text matches [M]. | Official, this model |
| **[KB]** | CASIO's official product image of the fx-991ZA PLUS II from casio.com/za (1309×1654 px). All key legends were read from enlarged crops. | Official, this model |
| **[ZAP]** | casio.com/za product page *fx-991ZA PLUSII-SDT* (its spec list). | Official, this model |
| **[CMP]** | edu.casio.com/za comparison chart ("fx-991ZA PLUS 2nd"). | Official, this model |
| **[ZAE]** | edu.casio.com/za product page "fx-991ZA PLUS 2nd edition". | Official, this model |
| **[JR]** | James Ralph Trading (Pty) Ltd product page. This is CASIO's South African calculator distributor. | S |
| **[JRW]** | James Ralph teacher-workshop slides "fx991za+ ii general", hosted on `edumaterial.casio-intl.com/resource/contents/1368`. | S (distributor material on a CASIO server) |
| **[FB]** | Search-result snippet of a CASIO Calculators ZA Facebook post. The page itself could not be fetched. | S |
| **[ES2]** | Official guide and online manual for the **fx-570ES PLUS / fx-991ES PLUS / fx-9910NG PLUS (2nd edition)**. All 57 HTML pages were downloaded and diffed against [M]. | V\* (sibling) |
| **[ES2P]** | casio.com/intl product page and product image for the fx-991ES PLUS-2. | V\* (sibling) |
| **[C115]** | Official online manual for the **fx-115ES PLUS / fx-991ES PLUS C (2nd edition)**. All 74 pages were downloaded and diffed against [M]. | V\* (sibling) |
| **[ES1]** | fx-570ES PLUS / fx-991ES PLUS (1st edition) guide, an official CASIO PDF hosted on a university mirror. | V\* (sibling) |
| **[CODATA14]** | NIST, *2014 Fundamental Physical Constants — Complete Listing* (`allascii_2014.txt`). | Official, NIST |
| **[SP811]** | NIST SP 811 (2008), Appendix B.8, conversion factors. | Official, NIST |

### 0.3 Notation

- `[KEY]` is a physical key, named by its keycap legend. `SHIFT [KEY]` and `ALPHA [KEY]` press the
  modifier first. The modifier is not held down, and it is cancelled by the next key press [M:before_using_calculator/getting_started.html].
- `→n` means "press number key *n* on the menu that is showing". `▼→n` means "press ▼ to show the
  menu's second page, then press *n*".
- **This model writes decimals with a comma.** Examples below keep the calculator's own display,
  so `0,5` means one half. Argument separators are `;` (§3.1).

---

## 1. Model identity, as this researcher established it

| Fact | Tag | Source |
|---|---|---|
| The model is the **fx-991ZA PLUS II**. CASIO also calls it the "**fx-991ZA PLUS 2nd edition**" or "fx-991ZA PLUS 2nd". Its SKU is **FX-991ZAPLUSII-SDT**. It is a South-African-market NATURAL-V.P.A.M. scientific calculator. | V | [MP] cover; [ZAE] page title; [CMP] column header; [ZAP] |
| It is two-way powered (solar cell plus one LR44/GPA76 button cell). Battery life is about 3 years. It measures 11.1 × 77 × 161.5 mm and weighs 95 g. | V | [M:technical_informatoin/specifications.html] |
| The display is a 31 × 96 full-dot LCD. There are 9 variables. Numbers show 10 digits plus a 2-digit exponent. | V | [CMP]; [ZAP] |
| It has **no OPTN key** and is **not a ClassWiz**. Its keyboard is the ES PLUS layout: SHIFT, ALPHA, MODE, ON, a 4-way cursor pad, CALC, ∫, x⁻¹, log▭▭ and so on. | V | [KB] |
| Its keyboard matches the **fx-991ES PLUS 2nd edition** key for key. The only differences are in the legends (§10.1). | V | [KB] compared with [ES2P] image |
| **Functionally**, its closest relative is the North-American **fx-115ES PLUS / fx-991ES PLUS C 2nd edition** (ROM CY-847), not the international fx-991ES PLUS 2nd edition (CY-845). *Cross-check confirmations:* Casio's ZA HTML guide reuses that model's screen images (`CY846_847_TABLE_01/02.png`), and Casio's ES PLUS 2nd-edition catalogue lists only "Random integers" as the fx-991ES PLUS-2's main added function (V). Both [ES2] and [C115] were diffed line by line against [M]. The C115 shares PreAns, ÷R, FACT, GCD/LCM, Q1/med/Q3, Ref/Rref, the two-function TABLE, the quadratic vertex output, DIST, INEQ, the statistics-editor row counts and the TABLE row limits. The international fx-991ES PLUS 2nd edition has **none** of these. | V (each diff line) / I (the word "closest") | §10 |
| The first-generation **fx-991ZA PLUS** had 8 modes (no DIST, INEQ or RATIO), a dot decimal and Norm 1 as its default. | S | [JRW] |

**Bottom line for implementers:** build the keyboard from [KB]. Build the behaviour from [M]. Do not
fill gaps from fx-991EX or fx-991CW (ClassWiz) knowledge, because those differ in menus, keys and
features (§11).

---

## 2. Official function count

| Claim | Value | Tag | Source |
|---|---|---|---|
| CASIO's own ZA product page | **No number given.** The spec list names features only. | V | [ZAP] |
| CASIO's ZA comparison chart | **No number given.** | V | [CMP] |
| CASIO SA distributor (James Ralph Trading) spec list | **452 functions** | S | [JR] |
| CASIO Calculators ZA Facebook post title | "452 Functions with Natural Textbook 2-line Display" | S (snippet only) | [FB] |
| SA retailers (PnP, GeeWiz, New World, Bob Shop, Avo and others) | 452 | S | search results, §14 |
| Other figures seen: 403 (Amazon.co.za, Wize Books), 417 (Instagram), 433 (speciality.co.za), "450+" (Facebook group), 552 (the James Ralph page's own *meta description*, which contradicts its spec list) | — | S, judged **wrong** | 552 is the fx-991EX's count. 417 is the fx-991ES PLUS-2's count. |
| International sibling fx-991ES PLUS-2 | **417** ("Number of Functions: 417") | V\* | [ES2P] |

**Adopted figure: 452 (S).** No CASIO-owned page or document seen states it. The best sources are
CASIO's own SA distributor and CASIO ZA's social account.

### 2.1 Can 452 be accounted for? Partly. (I)

CASIO does not publish how it counts. A literal count of the distinct commands in §4–§6 comes out
well below 452 (about 300). So CASIO's method must count some things more than once. One
plausible example: the statistics commands counted once per regression type. That is a guess (U).

The **difference** from the fx-991ES PLUS-2 is 452 − 417 = **35**. The features the ZA PLUS II has
and the ES PLUS-2 lacks, with granularity this researcher chose, are:

| Item | Count |
|---|---|
| PreAns, ÷R, FACT, GCD, LCM | 5 |
| Q1, med, Q3 | 3 |
| Ref, Rref | 2 |
| g(x) in TABLE | 1 |
| DIST types | 7 |
| INEQ (2 types × 4 symbols) | 8 |
| RATIO types | 2 |
| Quadratic X-value and Y-value of the min/max | 2 |
| APO 10 min / 60 min | 2 |
| Removed: Disp Dot/Comma setting | −2 |
| **Total** | **30** |

This is close to 35, not equal. **Treat 452 as a marketing number, not a specification.** Build
the inventory in §4–§6 and do not chase the count.

---

## 3. Global conventions that every function obeys

### 3.1 ZA-specific number formatting (all V unless marked)

| Rule | Detail | Source |
|---|---|---|
| Decimal separator | The **comma**, both on the keycap (bottom row, second key) and on the display: `0,5`, `1,175201194`. | [KB]; every example in [M]; key glyph 57447+ is "," where [ES2] uses 57408 "•" |
| No dot/comma option | The ZA PLUS II setup has **no "Disp" item**. Page 2 of [ES2]'s setup has `5:Disp` (Dot/Comma). On the ZA, the slot is `5:TABLE` and the comma is fixed. | [M:calculation_calculator/configuring_calculator.html] compared with [ES2] |
| Argument separator | **`;`**, typed with `SHIFT [)]`. The keycap legend is ";". Examples: `log(2;16)`, `Pol(√2;√2)`, `GCD(28;35)`, `RanInt#(1;6)`, `∫(f(x);a;b;tol)`. | [KB]; [M] throughout |
| Multi-result separator on screen | `;`. Examples: `r=2;θ=45`, `X=1;Y=1`, `2;R=1`, `X≤-3;1≤X`. | [M] images |
| Data written in the guide | `(170; 66)`, `{10; 11; 12}`. | [M:using_calculation_modes/stat.html], [M:using_calculation_modes/dist.html] |
| Earlier ZA model | The fx-991ZA PLUS (1st gen) used a dot. "The dot has also been changed into a comma on the display and on the calculator." | S [JRW] |

### 3.2 Input rules (V, [M:inputting_expressions_values/basic_input_rules.html] unless noted)

- **Precedence is applied automatically** when `=` is pressed (§8).
- **Functions that open a parenthesis need a closing `)`.** This covers sin, sinh, log, Abs (in
  Linear mode), Pol, Rec, GCD, RanInt# and the others. The FAQ example: with Deg set,
  `sin 30 + 15 =` without `)` computes sin 45 = 0,7071067812. Typing `sin 30 ) + 15 =` gives
  15,5 [M:frequently_asked_questions/frequently.html].
- **A `)` just before `=` may be omitted.**
- **Implied multiplication** is allowed immediately before: `(`, sin and other functions that take
  parentheses, Ran#, a variable (A, B, C, D, E, F, M, X, Y), a scientific constant, π and e.
  Example: `4sin(30)(30+10×3)` = 120.
- **Input buffer:** 99 bytes. Most items take 1 byte. Some functions take 3 to 13 bytes. When 10
  bytes or fewer remain, the cursor becomes `■`.
- **Natural Display height limit:** two screens (31 dots × 2). Too much nesting stops further
  input [M:inputting_expressions_values/inputting_with_natural_display.html].
- **Cursor:** in Linear, ▲ jumps to the start and ▼ to the end. In Natural, ▶ at the end wraps to
  the start and ◀ at the start wraps to the end.
- **Insert / overwrite:** Linear only. `SHIFT [DEL]` (INS) toggles them. The cursor is `|` in insert
  mode and `_` in overwrite mode. Natural is always insert
  [M:inputting_expressions_values/overwrite_input_mode.html].
- **`SHIFT [DEL]` (INS) in Natural Display** turns everything right of the cursor into the argument
  of the next template function pressed. The argument runs up to the first `(` to the right, or up
  to the first function to the right. It works with: fraction, mixed fraction, log▭▭, ∫, d/dx, Σ,
  ▭√▭, 10^▭, e^▭, √, x^▭, ∛ and Abs
  [M:inputting_expressions_values/expressions_as_arguments.html].
- **No consecutive postfix powers.** x², x³, x^▭ and x⁻¹ cannot follow each other. `2 x² x²` ignores
  the second x². To get (2²)², use 2 x², then ◀, then x² (Natural)
  [M:function_calculations/power_functions.html].
- **Negatives:** `(−)2 x²` = −(2²). Write `((−)2)x²` for (−2)² [M:technical_informatoin/sequence.html].
- **During CALC and SOLVE** you must use Linear-style input until AC is pressed.

### 3.3 Internal arithmetic (V, [M:technical_informatoin/calculation_ranges.html])

| Property | Value |
|---|---|
| Range | ±1 × 10⁻⁹⁹ to ±9,999999999 × 10⁹⁹, or 0 |
| Internal digits | 15 |
| Precision | ±1 at the 10th digit for a single operation. In exponential display, ±1 at the last displayed digit. Errors accumulate. |
| π used internally | **3,14159265358980** (the guide's own figure) [M:function_calculations/pi_base-e.html] |
| e used internally | **2,71828182845904** (the guide's own figure) |
| π-form result range (Natural) | \|x\| < 10⁶. Rounding error can block π form, or wrongly produce it. |
| √-form result | At most two terms, of the forms ±a√b, ±d±a√b, or (±a′√b ± d′√e)/c′. Limits: 1≤a<100, 1<b<1000, 1≤c<100, 0≤d<100, 0≤e<1000, 1≤f<100, all integers. Example: `10√2 + 15×3√3` → `45√3+10√2`. `99√999` = 3129,089165 (= 297√111) is shown in decimal because it is outside the range [M:inputting_expressions_values/form_calculation_range.html]. |
| Fraction display limit | Integer part + numerator + denominator + separators ≤ 10 digits. Results are reduced to lowest terms. Mixing a fraction and a decimal gives a decimal. |

**Note on π and e (I):** 3,14159265358980 is π rounded to 14 significant digits, then padded with
a 0. The correct 15-digit rounding would be …58979. For e, the correct 15-digit rounding is
…845905, but the guide prints …845904, which is truncation. **Use the guide's values exactly**
if results must match to the 15th digit (U whether the device really does this; see §13).

---

## 4. Key-by-key catalog

Legend colours from [KB]: **SHIFT functions are amber/orange** and **ALPHA functions are red**.
Legends in **purple brackets** are CMPLX-mode functions. Legends in **teal brackets or teal text**
are BASE-N functions [M:before_using_calculator/getting_started.html]. All rows are **V** from [KB]
and are confirmed by the key sequences in [M], unless marked.

| Row | Key (keycap) | Plain press | SHIFT + key | ALPHA + key | CMPLX-mode / BASE-N-mode role |
|---|---|---|---|---|---|
| 1 | SHIFT | shift lock for one key (indicator **S**) | — | — | — |
| 1 | ALPHA | alpha lock for one key (indicator **A**) | — | — | — |
| 1 | ◀ ▶ ▲ ▼ (cursor pad) | move cursor, replay, scroll history and menus | — | — | — |
| 1 | MODE | mode menu (§6.1). `MODE 0` = authenticity screen | **SETUP** (§6.2) | — | — |
| 1 | ON | power on, self-check | — | — | — |
| 2 | CALC | CALC (§5.23) | **SOLVE** (§5.24) | **=** (the equals sign inside an equation) | — |
| 2 | ∫▭ (∫ with box templates) | ∫ (§5.20) | **d/dx▭** (§5.21) | **:** (multi-statement) | — |
| 2 | x⁻¹ | reciprocal x⁻¹ | **x!** | — | — |
| 2 | log▭▭ | log_▭(▭) template (Natural only) | **Σ▭** (§5.22) | — | — |
| 3 | ▭/▭ (fraction) | fraction template; `⌟` in Linear | **▭▭/▭** (mixed fraction) | **÷R** (§5.6) | — |
| 3 | √▭ | √ | **∛▭** | — | — |
| 3 | x² | x² | **x³** | — | BASE-N: **DEC** |
| 3 | x^▭ | power x^▭ | **▭√▭** (x-th root) | — | BASE-N: **HEX** |
| 3 | log | log (base 10, or log(a;b)) | **10^▭** | — | BASE-N: **BIN** |
| 3 | ln | ln | **e^▭** | — | BASE-N: **OCT** |
| 4 | (−) | negative sign | **∠** (purple: polar entry in CMPLX) | **A** | BASE-N: hex digit **A** (pressed without ALPHA) |
| 4 | °'" | sexagesimal entry and toggle | **FACT** (§5.7) | **B** | BASE-N: **B** |
| 4 | hyp | hyp menu (§5.11) | **Abs** | **C** | BASE-N: **C** |
| 4 | sin | sin | **sin⁻¹** | **D** | BASE-N: **D** |
| 4 | cos | cos | **cos⁻¹** | **E** | BASE-N: **E** |
| 4 | tan | tan | **tan⁻¹** | **F** | BASE-N: **F** |
| 5 | RCL | recall a variable | **STO** | — | — |
| 5 | ENG | engineering notation | **←** (ENG with the exponent shifted the other way) | — | CMPLX: **i** (purple; plain press) |
| 5 | ( | ( | **%** | — | — |
| 5 | ) | ) | **;** (argument separator) | **X** | — |
| 5 | S⇔D | toggle the result form | **a b/c ⇔ d/c** (mixed ⇔ improper) | **Y** | — |
| 5 | M+ | add to M | **M−** | **M** | — |
| 6 | 7 | 7 | **CONST** (§5.27) | — | — |
| 6 | 8 | 8 | **CONV** (§5.28) | — | — |
| 6 | 9 | 9 | **CLR** (§5.25) | — | — |
| 6 | DEL (green) | delete | **INS** | — | — |
| 6 | AC (green) | clear, cancel, interrupt | **OFF** | — | — |
| 7 | 4 | 4 | **MATRIX** (menu, §6.7) | — | — |
| 7 | 5 | 5 | **VECTOR** (menu, §6.9) | — | — |
| 7 | 6 | 6 | *(no legend)* | — | — |
| 7 | × | × | **nPr** | **GCD** | — |
| 7 | ÷ | ÷ | **nCr** | **LCM** | — |
| 8 | 1 | 1 | **STAT/DIST** (menu, §6.4 and §6.10) | — | — |
| 8 | 2 | 2 | **CMPLX** (purple bracket; menu, §6.3) | — | — |
| 8 | 3 | 3 | **BASE** (teal bracket; menu, §6.5) | — | — |
| 8 | + | + | **Pol** | — | — |
| 8 | − | − | **Rec** | — | — |
| 9 | 0 | 0 | **Rnd** | — | — |
| 9 | , | decimal comma | **Ran#** | **RanInt** (inserts `RanInt#(`) | — |
| 9 | ×10^x | ×10^x (exponent entry) | **π** | **e** | — |
| 9 | Ans | Ans | **DRG▸** (menu, §5.12) | **PreAns** | — |
| 9 | = | execute | *(no printed legend)* `SHIFT =` executes and shows a **decimal** result in Natural Display (V [M:basic_calculations/toggling_calculation.html]) | — | — |

There are **47 keys**, counting the cursor pad as one (I, by counting [KB]). *Cross-check:* this is
the same keyboard as file 01's **50 physical keys**, which counts the pad as its four separate keys;
Calcupedia's 2nd-edition self-test (key tests of 29 keys excluding ON, plus 20) also gives 50 (S).

- 4 top-row keys (SHIFT, ALPHA, MODE, ON) and the cursor pad
- 22 function keys, in rows of 4 + 6 + 6 + 6
- 20 number-block keys, in 4 rows of 5

**Ambiguous SHIFT/ALPHA combinations.** Some combinations have no legend, for example `ALPHA [6]`,
`ALPHA [sin]` in BASE-N, and `SHIFT [6]`. What they do is **U**. The usual Casio behaviour is that
they do nothing (I).

---

## 5. Function reference

Each entry gives: **display name** · **how to type it** · **modes** · **syntax** · **domain** ·
**special behaviour**. "COMP-only" means the guide says so explicitly. If an entry gives no mode
restriction, the guide does not state one (U, §13 item 5).

### 5.1 Arithmetic and number entry (V)

| Function | Key | Notes | Source |
|---|---|---|---|
| + − × ÷ | `[+] [−] [×] [÷]` | Infix. × and ÷ are priority 10. + and − are priority 11. | [M:technical_informatoin/sequence.html] |
| Negative sign | `[(−)]` | Prefix, priority 5. It binds more loosely than x², so `(−)2²` = −4. | same |
| Parentheses | `[(] [)]` | Priority 1. | same |
| ×10^x | `[×10^x]` | Exponent entry. Example: `1[×10^x][(−)]12` = 1 × 10⁻¹². | [M:function_calculations/differential_calculations.html] |
| Decimal comma | `[,]` | — | [KB] |
| Execute | `[=]` | Repeating `=` after a result re-executes the last expression. Seen with Ran#, RanInt and PreAns. | [M:function_calculations/random_number.html] |
| Decimal result | `SHIFT [=]` | Natural Display only. It forces a decimal result for a calculation that would otherwise give a √ or π form, or a fraction from a division. S⇔D afterwards gives the fraction or π form, **but not the √ form**. | [M:basic_calculations/toggling_calculation.html] |
| Ans | `[Ans]` | Holds the last result. It survives AC, mode changes and power-off. Pressing an operator first (`÷30=`) automatically inserts `Ans÷30`. | [M:basic_calculations/memory_functions.html] |
| PreAns | `ALPHA [Ans]` | Holds the result **before** the last one. **COMP only.** It is cleared when leaving COMP. It survives `SHIFT AC` (OFF) if you stay in COMP. Example (Fibonacci): `1=`, `1=`, `Ans+PreAns=` → 2, `=` → 3, `=` → 5. | same |

What updates Ans exactly: [ES2] says Ans changes on `=`, `SHIFT =`, `M+`, `M−`, `RCL` and `STO`, and
holds 15 digits. That is V\* for [ES2] and **I** for the ZA, because [M] dropped that sentence.

### 5.2 Fractions and result-form toggles (V, [M:basic_calculations/fraction_calculations.html], [M:basic_calculations/toggling_calculation.html])

| Function | Key | Natural (MathIO) syntax | Linear syntax | Notes |
|---|---|---|---|---|
| Fraction | `[▭/▭]` | `2[▭/▭]3[▶]` (numerator first), or `[▭/▭]2[▼]3[▶]` (empty template first) | `2[▭/▭]3` shows as `2⌟3` | 2/3 + 1/2 = 7/6. In Linear the result shows as `7⌟6`. |
| Mixed fraction | `SHIFT [▭/▭]` | template: whole number, numerator, denominator | `3[▭/▭]1[▭/▭]2` = 3⌟1⌟2 | 4 − 3½ = ½ |
| Fraction ⇔ decimal | `[S⇔D]` | In Natural it also toggles √ form ⇔ decimal and π form ⇔ decimal. | In Linear it toggles decimal ⇔ fraction only. | π÷6 → `1/6 π` ⇔ 0,5235987756. (√2+2)×√3 → `√6+2√3` ⇔ 5,913591358. 1÷5 → 0,2 ⇔ `1⌟5`. Some results do not convert, and some take time. Decimal → mixed is refused if the mixed form needs more than 10 digits. |
| Mixed ⇔ improper | `SHIFT [S⇔D]` (a b/c⇔d/c) | — | — | The default result format is **d/c** (improper) (§6.2). |

### 5.3 Percent (V, [M:basic_calculations/percent_calculations.html])

`SHIFT [(]` (%) is a postfix operator, priority 3. It makes the value before it a percentage
(divides by 100).

| Input | Result |
|---|---|
| `150×20%` | 30 |
| `660÷880%` | 75 |
| `2500+2500×15%` | 2875 |
| `3500−3500×25%` | 2625 |

Unlike older models, % has no special "add-on" behaviour (I, from the examples).

### 5.4 Degrees, minutes, seconds (V, [M:basic_calculations/degree_calculations.html])

- **Entry:** `deg [°'"] min [°'"] sec [°'"]`. Degrees and minutes must always be typed, even when
  they are 0. Example: `2°20'30" + 0°39'30"` = `3°0'0"`.
- **Result typing:** sexagesimal ± sexagesimal gives sexagesimal. Sexagesimal ×/÷ decimal gives
  sexagesimal.
- **Conversion:** pressing `[°'"]` on a result toggles decimal ⇔ sexagesimal. `2°15'18"` → 2,255 →
  `2°15'18"`.
- **Ranges:** \|a\|, b, c < 10¹⁰⁰ with b, c ≥ 0. Conversion works for 0°0'0" ≤ \|x\| ≤ 9999999°59'59".
  The displayed seconds may be off by ±1 in the second decimal place
  [M:technical_informatoin/calculation_ranges.html].
- `°'"` is a postfix function, priority 3.

### 5.5 Engineering notation (V, [M:basic_calculations/engineering_notation.html])

| Key | Effect | Example |
|---|---|---|
| `[ENG]` | Rewrites the shown result with an exponent that is a multiple of 3, moving the decimal point **right** on each press | 1234 → `1,234×10³` → `1234×10⁰` |
| `SHIFT [ENG]` (←) | The same, moving the decimal point **left** | 123 → `0,123×10³` → `0,000123×10⁶` |

ENG cannot be used on EQN or INEQ solution screens (V). In CMPLX mode the ENG key types `i`
instead (V, [M:using_calculation_modes/cmplx.html]), so whether ENG notation exists in CMPLX at all
is I/U. ENG exits a FACT display.

### 5.6 ÷R — quotient and remainder (V, [M:basic_calculations/remainder_calculations.html])

- **Key:** `ALPHA [▭/▭]`. **Syntax:** `a ÷R b`, infix, priority 10 (same as × and ÷).
- **Display:** in MathIO, `5÷R2` → `2;R=1` on one line. In LineIO, the quotient `2` is on the
  result line and `R=` with `1` is on the next line. [JRW] example: `75÷R4` → `18; R=3` (S).
- **Ans** receives only the quotient. `STO` stores only the quotient: `5÷R2 STO X` gives X = 2.
  Inside a longer expression only the quotient carries on: `10+17÷R6` = 10+2 = 12.
- S⇔D and °'" are disabled while a ÷R result is displayed. FACT is refused on a ÷R result.
- **It falls back to ordinary division** when:
  - the dividend or divisor is "very large". The example is `20000000000 ÷R 17` (2×10¹⁰). The exact
    threshold is **U**.
  - the quotient is not a positive integer, or the remainder is not a positive integer or positive
    fraction. Example: `(−)5 ÷R 2` is calculated as `−5÷2`. *(Cross-check: an earlier version wrote
    "→ −2,5" here as if printed. The guide prints no value; in MathIO an integer division normally
    gives a fraction, so `−5/2` stacked is at least as likely. **I**, see vector C-24.)*
- **Forbidden inside:** ∫, d/dx and Σ (f(x)), SOLVE, the editors (EQN, INEQ, RATIO, MATRIX,
  VECTOR, STAT) and DIST.
- **Mode:** the guide's examples are in COMP. Other modes are U.

### 5.7 FACT — prime factorisation (V, [M:basic_calculations/factrization.html])

- **Key:** `SHIFT [°'"]`. It acts on the **result currently displayed**, which must be a positive
  integer of at most 10 digits. **COMP only.**
- **Output:** `1014` → `2×3×13²`. Any part that cannot be split into prime factors of three digits
  or fewer is shown **in parentheses**: `4104676` → `2²×(1026169)`, where 1026169 = 1013².
- **Implementation note (I, from that example):** the device trial-divides by primes ≤ 997 and
  prints whatever is left in parentheses, **even when that leftover is composite**. It does not
  fully factor.
- **Leaving the FACT display:** press FACT again, `=`, ENG or °'", or change the angle unit or
  Fix/Sci/Norm in SETUP.
- **Errors:** a decimal, fraction or negative result gives **Math ERROR**. A Pol, Rec or ÷R result
  is refused.

### 5.8 Powers and roots (V, [M:function_calculations/power_functions.html], ranges from [M:technical_informatoin/calculation_ranges.html])

| Display | Key | Syntax | Range | Notes |
|---|---|---|---|---|
| x² | `[x²]` | postfix | \|x\| < 10⁵⁰ | Allowed in CMPLX. Applies to a matrix (squares it). |
| x³ | `SHIFT [x²]` | postfix | (not listed separately) | Allowed in CMPLX. Applies to a matrix. `(5²)³` = 15625. |
| x⁻¹ | `[x⁻¹]` | postfix | \|x\| < 10¹⁰⁰, x ≠ 0 | Allowed in CMPLX. Gives the matrix inverse (x^▭ cannot do this). `(3⁻¹−4⁻¹)⁻¹` = 12. |
| x^▭ | `[x^▭]` | Natural: exponent template (`(1+1)^(2+2)` = 16). Linear: `^(` … `)` (I) | x>0: −10¹⁰⁰ < y·log x < 100. x=0: y>0. x<0: y = n or m/(2n+1) (m, n integers), with −10¹⁰⁰ < y·log\|x\| < 100 | Cannot be used for matrix powers. `1,2×10³` → 1200. |
| √ | `[√▭]` | Natural: template. Linear: `√(`…`)` | 0 ≤ x < 10¹⁰⁰ | `√2×3` → `3√2` (Natural) → `SHIFT =` → 4,243 (Fix 3) |
| ∛ | `SHIFT [√▭]` | template / `∛(` | \|x\| < 10¹⁰⁰ | `∛5 + ∛(−27)` = −1,290024053 |
| ▭√▭ (x-th root) | `SHIFT [x^▭]` | Natural: `[SHIFT][x^▭] 5 [▶] 32` (index first). Linear: `5 [SHIFT][x^▭] 32 )`, i.e. index **before** the key | y>0: x≠0, −10¹⁰⁰ < (1/x)·log y < 100. y=0: x>0. y<0: x = 2n+1 or (2n+1)/m (m≠0) | ⁵√32 = 2. It is postfix-style in Linear (priority 3). |

### 5.9 Logarithms and exponentials (V, [M:function_calculations/logarithmic_functions.html], [M:function_calculations/exponential_functions.html])

| Display | Key | Syntax | Range | Notes |
|---|---|---|---|---|
| log | `[log]` | `log(x)` or `log(a;b)` | 0 < x ≤ 9,999999999×10⁹⁹ | If the base is omitted it is 10. `log 1000` = 3. `log(2;16)` = 4. Works in Natural and Linear. |
| log_▭▭ | `[log▭▭]` | template `log_a(b)` | as log | **Natural only.** The base must be typed. `log₂(4³)` = 6. `log₂(4)³` = 8 (the cursor left the template before x³). |
| ln | `[ln]` | `ln(x)` | 0 < x ≤ 9,999999999×10⁹⁹ | `ln 90` = 4,50×10⁰ (Sci 3) |
| 10^▭ | `SHIFT [log]` | template / `10^(` | −9,999999999×10⁹⁹ ≤ x ≤ 99,99999999 | — |
| e^▭ | `SHIFT [ln]` | template / `e^(` | −9,999999999×10⁹⁹ ≤ x ≤ 230,2585092 | `e⁵×2` = 2,97×10² (Sci 3) |

### 5.10 Trigonometric functions (V, [M:function_calculations/trigonometric_functions.html], ranges)

| Display | Key | Range |
|---|---|---|
| sin(, cos( | `[sin]`, `[cos]` | Deg: \|x\| < 9×10⁹. Rad: \|x\| < 157079632,7. Gra: \|x\| < 10¹⁰. |
| tan( | `[tan]` | As sin, except \|x\| = (2n−1)×90° / (2n−1)×π/2 / (2n−1)×100 grad |
| sin⁻¹(, cos⁻¹( | `SHIFT [sin]`, `SHIFT [cos]` | 0 ≤ \|x\| ≤ 1 |
| tan⁻¹( | `SHIFT [tan]` | \|x\| ≤ 9,999999999×10⁹⁹ |

They use the current default angle unit (§6.2). `sin 30` = 0,5 and `sin⁻¹ 0,5` = 30 in Deg.

### 5.11 Hyperbolic functions — the hyp menu

`[hyp]` opens a numbered menu. The angle unit does not affect these.

| Menu item | Function | Range | Tag |
|---|---|---|---|
| 1 | sinh( | \|x\| ≤ 230,2585092 | **V** (`[hyp][1](sinh) 1)` = 1,175201194) |
| 2 | cosh( | \|x\| ≤ 230,2585092 | I (order) |
| 3 | tanh( | \|x\| ≤ 9,999999999×10⁹⁹ | I (order) |
| 4 | sinh⁻¹( | \|x\| ≤ 4,999999999×10⁹⁹ | I (order) |
| 5 | cosh⁻¹( | 1 ≤ x ≤ 4,999999999×10⁹⁹ | **V** (`[hyp][5](cosh⁻¹) 1)` = 0) |
| 6 | tanh⁻¹( | \|x\| ≤ 9,999999999×10⁻¹ | I (order) |

The order 1–6 is inferred. [ES1] lists them in the order sinh, cosh, tanh, sinh⁻¹, cosh⁻¹, tanh⁻¹,
and items 1 and 5 are confirmed. **Cross-check:** the menu's own string table in the sibling ES PLUS
GYX firmware (file 07 [S13]) is `1:sinh  2:cosh` / `3:tanh  4:sinh⁻¹` / `5:cosh⁻¹ 6:tanh⁻¹` — three
lines of two. That makes items 2, 3, 4, 6 and the layout **S** (sibling firmware), no longer U.

### 5.12 Angle-unit symbols — the DRG▸ menu (`SHIFT [Ans]`)

| Item | Symbol | Meaning | Tag |
|---|---|---|---|
| 1 | ° | the value is in degrees | S (cross-check: the GYX firmware's DRG menu string is `1:°     2:r` / `3:g`) |
| 2 | ʳ | the value is in radians | **V** (`(π÷2)ʳ` = 90 in Deg) |
| 3 | ᵍ | the value is in grads | **V** (`50ᵍ` = 45 in Deg) |

These are postfix, priority 3. Each converts its operand **to the current default unit**.
90° = π/2 rad = 100 grad [M:function_calculations/angle_unit_conversion.html].

### 5.13 Constants π and e (V)

`SHIFT [×10^x]` gives π. `ALPHA [×10^x]` gives e. They display as 3,141592654 and 2,718281828.
For the internal values see §3.3. Implied multiplication applies before them.

### 5.14 Pol and Rec (V, [M:function_calculations/coordinate_conversion.html])

| | Pol | Rec |
|---|---|---|
| Key | `SHIFT [+]` | `SHIFT [−]` |
| Syntax | `Pol(x;y)` | `Rec(r;θ)` (θ in the current angle unit) |
| Output | `r=2;θ=45` (Natural, one line). In Linear, `r=2` and `θ=45` are on two lines. | `X=1;Y=1`. In Linear, two lines. |
| Side effect | **r goes to X and θ goes to Y** | **x goes to X and y goes to Y** |
| θ range | −180° < θ ≤ 180° | — |
| Domain | \|x\|, \|y\| ≤ 9,999999999×10⁹⁹ and √(x²+y²) ≤ 9,999999999×10⁹⁹ | 0 ≤ r ≤ 9,999999999×10⁹⁹; θ as for sin |
| Banned in | ∫, d/dx, Σ (f(x)), SOLVE, TABLE, DIST and every editor | same |

Pol(√2;√2) in Rad shows `r=2;θ=0,7853981▸`, scrolled [M:before_using_calculator/getting_started.html].
After a Pol or Rec result, FACT is refused.

### 5.15 Factorial, permutations and combinations (V)

| Display | Key | Syntax | Range |
|---|---|---|---|
| x! | `SHIFT [x⁻¹]` | postfix | 0 ≤ x ≤ 69, integer. `(5+3)!` = 40320. |
| nPr | `SHIFT [×]` | infix `n P r`, priority 8 | 0 ≤ n < 10¹⁰, 0 ≤ r ≤ n, integers; 1 ≤ n!/(n−r)! < 10¹⁰⁰. `10P4` = 5040. |
| nCr | `SHIFT [÷]` | infix, priority 8 | same n and r; 1 ≤ n!/r! < 10¹⁰⁰ or 1 ≤ n!/(n−r)! < 10¹⁰⁰. `10C4` = 210. |

### 5.16 Abs (V, [M:function_calculations/absolute.html])

- **Key:** `SHIFT [hyp]`.
- **Natural:** a `|▭|` template. Press ▶ to leave it: `|2−7|×2` = 10. **Linear:** `Abs(`…`)`.
- **Complex:** gives the modulus. `Abs(1+i)` → `√2`.
- **Matrix:** applies to each element. `Abs(MatB)` → [[2,1],[1,2]].
- **Vector:** gives the magnitude. `Abs(VctC)` with VctC = (2,−1,2) → 3.

### 5.17 Random numbers (V)

| Display | Key | Syntax | Detail |
|---|---|---|---|
| Ran# | `SHIFT [,]` | no argument | A pseudo-random value from 0,000 to 0,999 (3 decimal places). **In Natural Display it is shown as a fraction.** `1000Ran#` → e.g. 634, 92, 175. Implied multiplication works before it. |
| RanInt#( | `ALPHA [,]` | `RanInt#(a;b)` | A random integer from a to b inclusive. Requires a < b, \|a\|, \|b\| < 10¹⁰ and b − a < 10¹⁰. Each `=` draws again. |

The generator algorithm and its seed behaviour are **U**.

### 5.18 Rnd (V, [M:function_calculations/rounding_function.html])

- **Key:** `SHIFT [0]`. **Syntax:** `Rnd(expr)`.
- It rounds the argument to the **current display setting**: Norm 1 or 2 → 10 significant digits;
  Fix n → n decimal places; Sci n → n significant digits.
- **The internal value changes**, unlike display rounding. With Fix 3: `10÷3×3` = 10,000, but
  `Rnd(10÷3)×3` = 9,999.

### 5.19 GCD and LCM (V, [M:function_calculations/gcd_lcm.html])

| Display | Key | Syntax | Range |
|---|---|---|---|
| GCD( | `ALPHA [×]` | `GCD(a;b)`, exactly 2 arguments | \|a\|, \|b\| < 10¹⁰, integers. `GCD(28;35)` = 7. |
| LCM( | `ALPHA [÷]` | `LCM(a;b)` | 0 ≤ a, b < 10¹⁰, integers. `LCM(9;15)` = 45. |

[JRW] (S) adds that only two numbers can be used.

### 5.20 ∫ — numerical integration (V, [M:function_calculations/integration_calculations.html])

- **Key:** `[∫▭]`. **COMP only.**
- **Method:** Gauss–Kronrod.
- **Syntax.** Natural: the template ∫ₐᵇ f(x)dx, with **no tol field**. Linear:
  `∫(f(x); a; b; tol)`, where tol is optional and defaults to **1×10⁻⁵**. tol must be **≥ 1×10⁻¹⁴**.
  Use X (`ALPHA [)]`) as the variable.
- **Examples:**
  - ∫₁ᵉ ln(x) = 1.
  - `∫(1/x²; 1; 5; 1×10⁻⁷)` = 0,8.
  - ∫₀^π (sin x + cos x)² dx shows **π** in Natural (Rad). A π-form result is possible.
- **Rules:**
  - Use Rad for trigonometric integrands.
  - f(x) may not contain Pol, Rec or ÷R.
  - f, a, b and tol may not contain ∫, d/dx or Σ.
  - It can be slow. AC interrupts it.
  - If accuracy fails: **Time Out** (end condition not met), or an error when the error exceeds
    tol. The guide advises splitting the interval at sign changes or sharp changes.

### 5.21 d/dx — numerical derivative (V, [M:function_calculations/differential_calculations.html])

- **Key:** `SHIFT [∫▭]`. **COMP only.**
- **Method:** central difference.
- **Syntax.** Natural: d/dx(f(x))\|ₓ₌ₐ, with no tol field. Linear: `d/dx(f(x); a; tol)`, tol
  optional, default **1×10⁻¹⁰**, and it must be ≥ 1×10⁻¹⁴. If tol is omitted and no convergence is
  found, tol is adjusted automatically.
- **Examples:** d/dx(sin x) at π/2 (Rad) = 0. `d/dx(3x²−5x+2; 2; 1×10⁻¹²)` = 7.
- **Rules:** the same exclusions as ∫. Use Rad for trig. Accuracy is poor near discontinuities,
  jumps, inflection points, non-differentiable points and results near 0. **Time Out** is possible.

### 5.22 Σ — summation (V, [M:function_calculations/calculations.html])

- **Key:** `SHIFT [log▭▭]`.
- **Syntax.** Natural: the template Σ with x=a below and b above, then (f(x)). Linear:
  `Σ(f(x); a; b)`. It adds f(a)+f(a+1)+…+f(b).
- **Range:** a and b are integers with −1×10¹⁰ < a ≤ b < 1×10¹⁰.
- **Example:** Σ from x=1 to 5 of (x+1) = 20.
- **Rules:** f(x) may not contain Pol, Rec or ÷R. f, a and b may not contain ∫, d/dx or Σ.
- **Mode:** not stated (U). Most likely COMP and other non-editor modes (I).

### 5.23 CALC (V, [M:function_calculations/using_CALC.html])

- **Key:** `[CALC]` after typing an expression. **COMP and CMPLX only.**
- **What can be stored:** expressions (`2X+3Y`, `A+Bi`), multi-statements (`X+Y : X(X+Y)`), and
  "variable = expression" equations (`Y=X²+X+3`). Type the `=` with `ALPHA [CALC]`.
- **Flow:** CALC asks for each variable in turn as `A?`, showing its current value. Type a value
  and press `=`. The result is shown. Press `CALC` (or `=`) again to re-prompt with the last values.
  `AC` exits.
- **Examples:** `3A+B` with (5;10) → 25, and with (7;20) → 41. In CMPLX, `A+Bi ▸r∠θ` with (√3;1) →
  `2∠30`, and with (1;√3) → `2∠60`.
- Input during CALC is Linear-style.

### 5.24 SOLVE (V, [M:function_calculations/using_SOLVE.html])

- **Key:** `SHIFT [CALC]`. **COMP only.** **Method:** Newton's method.
- **Equations accepted:**
  - anything containing X, such as `X²+2X−2` (taken as = 0), `Y=X+5`, `X=sin(M)`, `X+3=B+C`. It
    solves for X.
  - `{equation}, {variable}` to solve for another variable, such as `Y=X+5, Y`.
- **Banned inside:** ∫, d/dx, Σ, Pol, Rec, ÷R. Closing parentheses must not be omitted.
- **Flow:**
  1. SOLVE prompts `Y?` (and so on) for every other variable.
  2. It shows `Solve for X` with the current X as the starting guess.
  3. Press `=`.
- **Result screen:** the equation, then `X=` and the solution (always decimal), then `L−R=` and
  (left − right) evaluated at that solution.
- **Example:** `Y=AX²+B` with Y=0, A=1, B=−2 and guess 1 → X = 1,414213562, L−R = 0. For
  `Y=X²−X+1` with Y = 3, 7, 13: X = 2, 3, 4.
- **Continue screen:** if the iteration limit is reached, it shows `Continue: [=]`. `=` continues
  and `AC` cancels.
- **Errors:** **Variable ERROR** (no X and no named variable, or the named variable is absent) and
  **Can't Solve**.
- It finds only one root. It is weak on y = sin x, eˣ, √x. Input is Linear-style.
- The iteration count and convergence test are **U**.

### 5.25 Memory: variables, STO, RCL, M+, M− and CLR (V, [M:basic_calculations/memory_functions.html])

| Item | Detail |
|---|---|
| Variables | **A, B, C, D, E, F, M, X, Y** (9). ALPHA keys: A `(−)`, B `°'"`, C `hyp`, D `sin`, E `cos`, F `tan`, X `)`, Y `S⇔D`, M `M+`. |
| Store | `value SHIFT [RCL] (STO) <variable key>`. No ALPHA is needed after STO: `3+5 STO (−)` gives A = 8. The indicator **STO** waits for the variable key. |
| Recall | `[RCL] <variable key>` shows the value (indicator **RCL**). In an expression use `ALPHA <key>`: `ALPHA A × 10` = 80. |
| Clear one | `0 STO <variable>` |
| Independent memory | `[M+]` adds the result (or the expression's value) to M. `SHIFT [M+]` (M−) subtracts. The **M** indicator shows when M ≠ 0. Example: 10×5 M+, then 10+5 M− → M = 35. M is the same as variable M. |
| Persistence | Ans, M and the variables survive AC, mode changes and power-off. |
| CLR menu | `SHIFT [9]` → `Clear?` `1:Setup 2:Memory 3:All` → confirm with `=` (Yes) or cancel with AC. 1 resets the setup and mode. 2 clears all memories. 3 resets everything. [JRW] shows the prompt `Reset All? [=]:Yes [AC]:Cancel` (S). [M:before_using_calculator/initializing.html] |
| Not in editors | M+, M− and STO are refused in the EQN, INEQ, RATIO, MATRIX, VECTOR and STAT editors. |

### 5.26 Multi-statement (V, [M:basic_calculations/multi_statements.html])

`ALPHA [∫▭]` types `:`. Statements run left to right. Each `=` shows the next result. The **Disp**
indicator marks an intermediate result. Example: `3+3:3×3` → 6, then `=` → 9. Multi-statements
are refused in all editors.

### 5.27 CONST — 40 scientific constants

**Selection** (V, [M:function_calculations/scientific_constants.html]):

1. `SHIFT [7]` shows `CONSTANT` / `Number 01~40?` / `[__]`.
2. Type **two digits**, from 01 to 40.
3. The constant's **symbol** is inserted at the cursor, for example `C₀`. `=` shows the value
   (C₀ → 299792458).
4. Constants work in **every mode except BASE-N**. Implied multiplication works before them.

What happens with 00, with 41–99 and with AC partway through is **U** (I: AC cancels).

**Values:** the guide says "CODATA (2014)" (V). The digits below are from [CODATA14] (V for the
2014 values). That the device stores exactly these is **I**. The number and symbol columns are V
from [M].

| No. | Symbol | Quantity | CODATA 2014 value | Unit |
|---|---|---|---|---|
| 01 | mp | proton mass | 1.672621898×10⁻²⁷ | kg |
| 02 | mn | neutron mass | 1.674927471×10⁻²⁷ | kg |
| 03 | me | electron mass | 9.10938356×10⁻³¹ | kg |
| 04 | mμ | muon mass | 1.883531594×10⁻²⁸ | kg |
| 05 | a₀ | Bohr radius | 5.2917721067×10⁻¹¹ | m |
| 06 | h | Planck constant | 6.626070040×10⁻³⁴ | J s |
| 07 | μN | nuclear magneton | 5.050783699×10⁻²⁷ | J T⁻¹ |
| 08 | μB | Bohr magneton | 9.274009994×10⁻²⁴ | J T⁻¹ |
| 09 | ħ | Planck constant, rationalised | 1.054571800×10⁻³⁴ | J s |
| 10 | α | fine-structure constant | 7.2973525664×10⁻³ | — |
| 11 | re | classical electron radius | 2.8179403227×10⁻¹⁵ | m |
| 12 | λC | Compton wavelength | 2.4263102367×10⁻¹² | m |
| 13 | γp | proton gyromagnetic ratio | 2.675221900×10⁸ | s⁻¹ T⁻¹ |
| 14 | λCp | proton Compton wavelength | 1.32140985396×10⁻¹⁵ | m |
| 15 | λCn | neutron Compton wavelength | 1.31959090481×10⁻¹⁵ | m |
| 16 | R∞ | Rydberg constant | 10973731.568508 | m⁻¹ |
| 17 | u | atomic mass unit | 1.660539040×10⁻²⁷ | kg |
| 18 | μp | proton magnetic moment | 1.4106067873×10⁻²⁶ | J T⁻¹ |
| 19 | μe | electron magnetic moment | −9.284764620×10⁻²⁴ | J T⁻¹ |
| 20 | μn | neutron magnetic moment | −9.6623650×10⁻²⁷ | J T⁻¹ |
| 21 | μμ | muon magnetic moment | −4.49044826×10⁻²⁶ | J T⁻¹ |
| 22 | F | Faraday constant | 96485.33289 | C mol⁻¹ |
| 23 | e | elementary charge | 1.6021766208×10⁻¹⁹ | C |
| 24 | NA | Avogadro constant | 6.022140857×10²³ | mol⁻¹ |
| 25 | k | Boltzmann constant | 1.38064852×10⁻²³ | J K⁻¹ |
| 26 | Vm | molar volume of ideal gas (273.15 K; 100 kPa) | 22.710947×10⁻³ | m³ mol⁻¹ |
| 27 | R | molar gas constant | 8.3144598 | J mol⁻¹ K⁻¹ |
| 28 | C₀ | speed of light in vacuum | 299792458 (exact) | m s⁻¹ |
| 29 | C₁ | first radiation constant | 3.741771790×10⁻¹⁶ | W m² |
| 30 | C₂ | second radiation constant | 1.43877736×10⁻² | m K |
| 31 | σ | Stefan–Boltzmann constant | 5.670367×10⁻⁸ | W m⁻² K⁻⁴ |
| 32 | ε₀ | electric constant | 8.854187817…×10⁻¹² (exact in 2014; = 1/(μ₀c²)) | F m⁻¹ |
| 33 | μ₀ | magnetic constant | 12.566370614…×10⁻⁷ (= 4π×10⁻⁷ exactly) | N A⁻² |
| 34 | Φ₀ | magnetic flux quantum | 2.067833831×10⁻¹⁵ | Wb |
| 35 | g | standard acceleration of gravity | 9.80665 (exact) | m s⁻² |
| 36 | G₀ | conductance quantum | 7.7480917310×10⁻⁵ | S |
| 37 | Z₀ | characteristic impedance of vacuum | 376.730313461… (exact; = μ₀c) | Ω |
| 38 | t | Celsius temperature | 273.15 (I; not in the CODATA file) | K |
| 39 | G | Newtonian constant of gravitation | 6.67408×10⁻¹¹ | m³ kg⁻¹ s⁻² |
| 40 | atm | standard atmosphere | 101325 (exact) | Pa |

**Notes:**
- The guide prints Vm's condition as "237,15K", a typo for 273.15 K. The CODATA quantity is
  273.15 K / 100 kPa. Example check: `1/√(ε₀μ₀)` → 299792458 (V, image CONST_3).
- How ε₀, μ₀ and Z₀ are rounded internally to 15 digits is **U**. The signs of 19–21 follow CODATA
  (I).
- **Cross-check (S, sibling firmware):** the ES PLUS GYX firmware stores every constant at the digits
  CODATA *publishes* (its CODATA 2006 set), the negative magnetic moments with a negative sign, and
  the exact ones truncated: ε₀ 8.854187817×10⁻¹², μ₀ 1.2566370614×10⁻⁶, Z₀ 376.730313461. Those three
  are unchanged in CODATA 2014, so use those digits. Its Vm is 22.413996×10⁻³ (101.325 kPa); the ZA
  guide's 100 kPa wording implies the 2nd edition moved to 22.710947×10⁻³ (I). See 03 §14.

### 5.28 CONV — 40 metric conversions

**Selection** (V, [M:function_calculations/metric_conversion.html]):

1. Type the value.
2. `SHIFT [8]` shows `CONVERSION` / `Number 01~40?` / `[__]`.
3. Type two digits. A **postfix** command such as `cm▸in` is inserted after the value.
4. Press `=`.

- CONV works in **every mode except BASE-N and TABLE**.
- Precedence is 6, the same as STAT x̂ and ŷ, so it binds tighter than implied multiplication and ×.
- The data are based on NIST SP 811 (2008). J▸cal uses the **15 °C** calorie.
- [JRW] (S) shows the command can be inserted first, then the value typed before it by moving the
  cursor left.

**Officially shown results (V, images CONV_2–4):**

| Input | Result |
|---|---|
| `5cm▸in` | 1,968503937 |
| `100g▸oz` | **3,527396584** |
| `−31°C▸°F` | −23,8 |

**Important (V by arithmetic on an official screenshot):** 100 ÷ 28.34952 = 3.527396584, which
matches the screen. The exact definition, 1 oz = 28.349523125 g, would give 3.527396195, which does
not. So for oz the device uses **SP 811's 7-significant-digit table factor**, and the reverse command
**divides by that same factor** rather than using a rounded reciprocal.

⚠ **Cross-check correction (00-index.md, C3).** "Assume this holds for every pair" was wrong:

- The Casio-hosted [JRW] handout (slide 19, a ZA screen) shows `120km/h▸m/s` → `33,33333333`. With
  SP 811's 0.2777778 it would be `33,333336`. So km/h is **÷3.6 exactly** — the `36 km/h▸m/s` test
  below is answered: **10**. **S** (distributor capture of this model).
- The sibling ES PLUS GYX firmware's factor table (file 07 [S13], `_num_convs`; **S**) agrees with
  every ZA data point (2.54, 28.34952, 3.6, °C×1.8+32) but differs from SP 811's printed table for
  **acre (4046.856)** and **hp (0.7457)**. It is the better model of the device; its values are used
  below. Full table with directions in 03 §15.

| No. | Command | Factor (best evidence) | Tag for the factor |
|---|---|---|---|
| 01 / 02 | in▸cm / cm▸in | ×2.54 / ÷2.54 (exact) | V (matches CONV_2) |
| 03 / 04 | ft▸m / m▸ft | 0.3048 (exact) | S (GYX; = SP 811) |
| 05 / 06 | yd▸m / m▸yd | 0.9144 (exact) | S (GYX; = SP 811) |
| 07 / 08 | mile▸km / km▸mile | 1.609344 (exact) | S (GYX; = SP 811) |
| 09 / 10 | n mile▸m / m▸n mile | 1852 (exact) | S (GYX; = SP 811) |
| 11 / 12 | acre▸m² / m²▸acre | **4046.856** (GYX). SP 811 prints 4046.873 (US-survey acre). | **U** for the ZA: test `1 acre▸m²` |
| 13 / 14 | gal (US)▸ℓ / ℓ▸gal (US) | 3.785412 | S (GYX; = SP 811) |
| 15 / 16 | gal (UK)▸ℓ / ℓ▸gal (UK) | 4.54609 (exact) | S (GYX; = SP 811) |
| 17 / 18 | pc▸km / km▸pc | 3.085678×10¹³ | S (GYX; = SP 811) |
| 19 / 20 | km/h▸m/s / m/s▸km/h | **÷3.6 / ×3.6** (GYX stores m/s→km/h = 3.6) | **S, confirmed by a ZA screen** ([JRW] s.19) |
| 21 / 22 | oz▸g / g▸oz | 28.34952 | **V** (matches CONV_3) |
| 23 / 24 | lb▸kg / kg▸lb | 0.4535924 | S (GYX; = SP 811 printed) — test `1 kg▸lb` |
| 25 / 26 | atm▸Pa / Pa▸atm | 101325 (exact) | S |
| 27 / 28 | mmHg▸Pa / Pa▸mmHg | 133.3224 (conventional mmHg) | S |
| 29 / 30 | hp▸kW / kW▸hp | **0.7457** (GYX). SP 811 prints 0.7456999. | **U** for the ZA: test `1 hp▸kW` |
| 31 / 32 | kgf/cm²▸Pa / Pa▸kgf/cm² | 98066.5 (exact) | S |
| 33 / 34 | kgf•m▸J / J▸kgf•m | 9.80665 (exact) | S |
| 35 / 36 | lbf/in²▸kPa / kPa▸lbf/in² | 6.894757 | S |
| 37 / 38 | °F▸°C / °C▸°F | (F−32)÷1.8 / C×1.8+32 | V (matches CONV_4); [JRW] s.19 `356°F▸°C` = 180 |
| 39 / 40 | J▸cal / cal▸J | ÷4.1858 / ×4.1858 (15 °C calorie) | S (GYX); the guide confirms the 15 °C calorie |

Because [JRW]'s key sequence ends in `S⇔D` to reach that decimal, the `=` result was presumably the
fraction `100/3`: conversions appear to run in exact arithmetic and can give fractions in MathIO
(**I**). In LineIO they are decimal.

The command names and numbers 01–40 are **V** [M].

---

## 6. Modes and their menus

### 6.1 MODE menu (V, [M:calculation_calculator/calculation_modes.html])

| Page | Key | Mode | Indicator |
|---|---|---|---|
| 1 | `MODE 1` | COMP (the default) | none |
| 1 | `MODE 2` | CMPLX | **CMPLX** |
| 1 | `MODE 3` | STAT | **STAT** |
| 1 | `MODE 4` | BASE-N | (number base shown, e.g. `Dec`) |
| 1 | `MODE 5` | EQN | — |
| 1 | `MODE 6` | MATRIX | **MAT** |
| 1 | `MODE 7` | TABLE | — |
| 1 | `MODE 8` | VECTOR | **VCT** |
| 2 | `MODE ▼ 1` | DIST | — |
| 2 | `MODE ▼ 2` | INEQ | — |
| 2 | `MODE ▼ 3` | RATIO | — |
| — | `MODE 0` | Authenticity screen: a 24-character calculator ID and a QR code for `wes.casio.com/calc/`. AC returns to the mode menu. | — |

The `MODE 0` row is V [M:technical_informatoin/calculator.html].

- Entering STAT, BASE-N, MATRIX or VECTOR switches the display to **Linear** automatically.
- Changing mode clears the calculation history.
- How the page 1 layout looks on screen is in 02/03. Its contents are as above.

### 6.2 SETUP (`SHIFT MODE`) — two pages (V, [M:calculation_calculator/configuring_calculator.html], image 06_Setup_01)

Page 1 screen: `1:MthIO 2:LineIO / 3:Deg 4:Rad / 5:Gra 6:Fix / 7:Sci 8:Norm`, with ▼ in the corner.

Page 2 screen: `1:ab/c 2:d/c / 3:CMPLX 4:STAT / 5:TABLE 6:APO / 7:◀CONT▶`, with ▲ in the corner.

| Item | Sub-choice | Default |
|---|---|---|
| 1 MthIO | `1` MathO (Natural input and output) / `2` LineO (Natural input, Linear output) | **MthIO-MathO** |
| 2 LineIO | — | |
| 3 Deg / 4 Rad / 5 Gra | — | **Deg** |
| 6 Fix | 0–9 decimal places (prompt `Fix 0~9?`, S [JRW]) | |
| 7 Sci | 0–9 significant digits. **Sci 0 means 10.** `1÷7` = 1,428571429×10⁻¹. | |
| 8 Norm | `1` Norm 1: exponential when \|x\| < 10⁻² or \|x\| ≥ 10¹⁰. `2` Norm 2: exponential when \|x\| < 10⁻⁹ or \|x\| ≥ 10¹⁰. | **Norm 2** (ZA-specific; [ES2] defaults to Norm 1). `1÷200` → `5×10⁻³` (Norm 1) or `0,005` (Norm 2). |
| ▼1 ab/c / ▼2 d/c | fraction result format | **d/c** |
| ▼3 CMPLX | `1` a+bi / `2` r∠θ | **a+bi** |
| ▼4 STAT | `1` ON (show the FREQ column) / `2` OFF | **OFF** |
| ▼5 TABLE | `1` f(x) / `2` f(x),g(x) | **f(x),g(x)** |
| ▼6 APO | `1` 10Min. / `2` 60Min. (auto power-off) | **10Min.** |
| ▼7 ◀CONT▶ | Contrast screen `CONTRAST / LIGHT [◀] DARK [▶]`. AC exits. | — |

The Fix / Sci / Norm examples are V:
- Fix 3: `100÷7` = 14,286. Fix 2: 14,29.
- Sci 5: `1÷7` = 1,4286×10⁻¹. Sci 4: 1,429×10⁻¹.

The indicators are **FIX**, **SCI** and **Math**, plus **D**, **R** or **G** in a box for the angle unit.

`SHIFT 9 1 =` resets all of the above and returns to COMP. Changing Natural ⇔ Linear inside TABLE
deletes the stored f(x) and g(x).

### 6.3 CMPLX mode (V, [M:using_calculation_modes/cmplx.html])

| Function | Key | Notes |
|---|---|---|
| i | `[ENG]` (purple i) | `(2+6i)÷(2i)` = `3−i` |
| ∠ | `SHIFT [(−)]` | Polar entry `r∠θ`, in the current angle unit. Priority 8. `2∠45` → `√2+√2i` (Deg, a+bi). |
| CMPLX menu | `SHIFT [2]` | **1:arg** · **2:Conjg** · **3:▸r∠θ** · **4:▸a+bi** |
| arg( | `SHIFT 2 1` | `arg(1+i)` = 45 (Deg) |
| Conjg( | `SHIFT 2 2` | `Conjg(2+3i)` = `2−3i` |
| ▸r∠θ / ▸a+bi | `SHIFT 2 3` / `SHIFT 2 4` | Typed at the end of an expression. It overrides the setup format for that result: `√2+√2i▸r∠θ` → `2∠45`. |
| Abs( | `SHIFT [hyp]` | Modulus. `Abs(1+i)` → `√2`. |
| x², x³, x⁻¹ | as usual | Allowed. `(1−i)⁻¹` → `1/2+1/2i`. `(1+i)²+(1−i)²` → 0. |

- θ is shown in −180° < θ ≤ 180°. In Linear, a and bi (or r and θ) are shown on **separate lines**.
- History is kept in CMPLX. CALC works in CMPLX.
- **Not documented and likely absent:** ReP, ImP, √ of a negative number giving an imaginary
  result, and trig or log of complex arguments. These are **U**. ReP/ImP are ClassWiz menu items
  (I).

### 6.4 STAT mode (V, [M:using_calculation_modes/stat.html])

**Type menu** (`MODE 3`, or `SHIFT 1 → 1:Type` later). Screen: `1:1-VAR 2:A+BX / 3:_+CX² 4:ln X /
5:e^X 6:A•B^X / 7:A•X^B 8:1/X`.

| Key | Type | Model |
|---|---|---|
| 1 | 1-VAR | single variable |
| 2 | A+BX | y = A + Bx |
| 3 | _+CX² | y = A + Bx + Cx² |
| 4 | ln X | y = A + B ln x |
| 5 | e^X | y = A e^(Bx) |
| 6 | A•B^X | y = A·Bˣ |
| 7 | A•X^B | y = A·x^B |
| 8 | 1/X | y = A + B/x |

**Statistics Editor:**
- Columns are X, plus Y for paired types, plus FREQ when the setup STAT item is ON.
- **Row capacity: 40** (X only, or X+Y), **20** (X+FREQ), **26** (X+Y+FREQ). These are the guide's
  figures (V). The X+FREQ figure being lower than X+Y+FREQ looks odd but is printed the same way in
  [C115] (V\*).
- Editing: type a value and press `=` into the cell. `DEL` deletes a row. `SHIFT 1 → 3:Edit →
  1:Ins` inserts a row. `SHIFT 1 → 3:Edit → 2:Del-A` deletes all.
- The data are **erased** when you leave STAT, switch between 1-VAR and paired types, or change
  the STAT setup.
- `AC` goes to the statistics calculation screen. `SHIFT 1 → 2:Data` returns to the editor.

**SHIFT 1 (STAT/DIST) menu on the calculation screen:**
- 1-VAR: `1:Type 2:Data 3:Sum 4:Var 5:Distr 6:MinMax`.
- Paired: `1:Type 2:Data 3:Sum 4:Var 5:Reg 6:MinMax`.
- **Edit** appears as item 3 only while the editor is showing.

| Sub-menu | 1-VAR items | Paired items |
|---|---|---|
| Sum | 1 Σx², 2 Σx | 1 Σx², 2 Σx, 3 Σy², 4 Σy, 5 Σxy, 6 Σx³, 7 Σx²y, 8 Σx⁴ |
| Var | 1 n, 2 x̄, 3 σx (population), 4 sx (sample) | 1 n, 2 x̄, 3 σx, 4 sx, 5 ȳ, 6 σy, 7 sy |
| Distr (1-VAR only) | 1 P(, 2 Q(, 3 R(, 4 ▸t | — |
| Reg (linear and the others) | — | 1 A, 2 B, 3 r, 4 x̂, 5 ŷ |
| Reg (_+CX²) | — | 1 A, 2 B, 3 C, 4 x̂₁, 5 x̂₂, 6 ŷ |
| MinMax | 1 minX, 2 maxX, 3 **Q1**, 4 **med**, 5 **Q3** | 1 minX, 2 maxX, 3 minY, 4 maxY |

- The paired Sum items 6–8 (Σx³, Σx²y, Σx⁴) are listed by the guide under linear regression. That
  they appear for every paired type is **I**.
- That the ln X, e^X, A•B^X, A•X^B and 1/X types use the linear Reg menu is V for ln X (Example 3
  uses A, B, r, x̂) and **I** for the others.
- **x̂, x̂₁, x̂₂ and ŷ are postfix commands, not variables.** The value goes before them:
  `−130 x̂` = 4,861 (ln-X regression, Fix 3). Priority 6.
- **P(t), Q(t), R(t)** use the standard normal distribution (V, illustration):
  - P(t) is the area from −∞ to t.
  - Q(t) is the area from 0 to t.
  - R(t) is the area from t to +∞.
- **▸t** is postfix: `X▸t` = (X − x̄)/σx.
- **Quartile method:** for n even, Q1 is the median of the lower half. [JRW]'s 12-value example gives
  2198,5 (S, and it checks out arithmetically). The odd-n rule (median included or excluded) is
  **U**.

**Worked results from the guide (V):**
- x = {1,2,2,3,3,3,4,4,5} → x̄ = 3, σx = 1,154700538.
- The data (20;3150), (110;7310), (200;8800), (290;9310), Fix 3: linear r = 0,923; ln-X r = 0,998,
  A = −3857,984, B = 2357,532.
- The 1-VAR data {0×1, 1×2, 2×1, 3×2, 4×2, 5×2, 6×3, 7×4, 9×2, 10×1}, Fix 3: `3▸t` = −0,762, then
  `P(Ans)` = 0,223.

The regression and statistics commands can only be used in STAT (I). Large data sets are slow (V).

### 6.5 BASE-N mode (V, [M:using_calculation_modes/base-n_calculations.html])

- **Number system keys:** `[x²]` DEC (the default on entry), `[x^▭]` HEX, `[log]` BIN, `[ln]` OCT.
  Pressing one on a displayed result **converts** it. `15×37=555` → HEX `0000022B` → BIN
  `0000001000101011` → OCT `00000001053`.
- **Hex digits:** `(−)`=A, `°'"`=B, `hyp`=C, `sin`=D, `cos`=E, `tan`=F, without ALPHA.
- **Integers only.** No decimals or exponents. Fractional parts are cut off.
- **Ranges** (16-bit binary, 32-bit otherwise, two's complement):

| Base | Positive | Negative |
|---|---|---|
| BIN | 0000000000000000 to 0111111111111111 | 1000000000000000 to 1111111111111111 |
| OCT | 00000000000 to 17777777777 | 20000000000 to 37777777777 |
| DEC | −2147483648 to 2147483647 | |
| HEX | 00000000 to 7FFFFFFF | 80000000 to FFFFFFFF |

- **BASE menu** (`SHIFT 3`):
  - page 1: `1:and 2:or 3:xor 4:xnor 5:Not 6:Neg`
  - page 2 (`▼`): `1:d 2:h 3:b 4:o`. These are prefix-to-value base specifiers, priority 5.
    `d10+h10+b10+o10` = 36.
- **Operator priorities:** `and` is 12, then `or`, `xor`, `xnor` at 13. These are the lowest of all.
  `Not(` and `Neg(` are functions.
- **Guide examples (BIN mode):**
  - `1010 and 1100` → 0000000000001000
  - `1011 or 11010` → 0000000000011011
  - `1010 xor 1100` → 0000000000000110
  - `1111 xnor 101` → 1111111111110101
  - `Not(1010)` → 1111111111110101
  - `Neg(101101)` → 1111111111010011
- **Negative values:** for BIN, OCT and HEX the calculator converts to binary, takes the two's
  complement and converts back. DEC just shows a minus sign.
- CONST and CONV are **not** available. History is kept.

### 6.6 EQN mode (V, [M:using_calculation_modes/equation_calculations.html])

**Type menu** (`MODE 5`): `1:anX+bnY=cn`, `2:anX+bnY+cnZ=dn`, `3:aX²+bX+c=0`, `4:aX³+bX²+cX+d=0`.

- **Coefficient Editor:** type a value and press `=` per cell. `AC` zeros all coefficients.
  Choosing the type again also zeros them. M+, M−, STO, Pol, Rec, ÷R and multi-statements are
  refused.
- **Solving:** `=` solves and shows one solution. Each `=` shows the next; ▼ and ▲ scroll. `=` after
  the last returns to the editor. `AC` returns to the editor at any time.

| Type | Output order | Example (V) |
|---|---|---|
| 2 unknowns | X=, Y= | x+2y=3, 2x+3y=4 → X = −1, Y = 2 |
| 3 unknowns | X=, Y=, Z= | x−y+z=2, x+y−z=0, −x+y+z=4 → 1, 2, 3 |
| Quadratic | X₁=, X₂=, then **X-Value Minimum=** and **Y-Value Minimum=** (or Maximum when a < 0) | 2x²−3x−6=0 → (3+√57)/4, (3−√57)/4, 3/4, −57/8. x²−2√2x+2=0 → `X=√2` (a single repeated root) |
| Cubic | X₁=, X₂=, X₃= | x³−2x²−x+2=0 → −1, 2, 1 |

- **Simultaneous** solutions are never shown in √ form.
- **No solution / infinite solutions** shows a message. AC or `=` returns. The exact wording is **U**.
- **Complex roots** of quadratics and cubics: [ES2] shows them (V\*, x²+x+¾=0 → −½ ± (√2/2)i). The ZA
  guide replaced that example, so this is **U** for the ZA (I: probably still shown).
- ENG is not allowed on the solution screens.

### 6.7 MATRIX mode (V, [M:using_calculation_modes/matrix.html])

- **Variables:** MatA, MatB, MatC, MatAns. Up to **3×3**.
- **Entry screen** (`MODE 6`): `Matrix? 1:MatA 2:MatB 3:MatC`, then a dimension menu.
- **MATRIX menu** (`SHIFT 4`):
  - page 1: `1:Dim 2:Data 3:MatA 4:MatB 5:MatC 6:MatAns 7:det 8:Trn`
  - page 2 (`▼`): `1:Ref 2:Rref`
- **Dimension menu:** item **4 = 2×3** and item **5 = 2×2** are V. **Cross-check:** the GYX firmware
  string table gives the whole menu — page 1 `1:3×3 2:3×2 / 3:3×1 4:2×3 / 5:2×2 6:2×1`, page 2 (▼)
  `1:1×3 2:1×2 / 3:1×1` — so 1×n matrices **exist** (S, sibling firmware). Title line `MatA(m×n) m×n?`.
- **Editor:** type a value and press `=` per cell. M+, M−, STO, Pol, Rec, ÷R and multi-statements
  are refused.
- **Copying:** show a matrix (or MatAns via `MatAns =`), then `STO` and `(−)` / `°'"` / `hyp` copies
  it to MatA / MatB / MatC.
- **MatAns screen:** pressing `+ − × ÷ x⁻¹ x² x³` jumps to the calculation line with `MatAns`
  already typed.

**Operations, with MatA = [[2,1],[1,1]], MatB = [[2,−1],[−1,2]], MatC = [[1,0,−1],[0,−1,1]] (V):**

| Operation | Input | Result |
|---|---|---|
| Product | `MatA×MatB` | [[3,0],[1,1]] |
| Sum | `MatA+MatB` | [[4,0],[0,3]] |
| Scalar | `3×MatA` | [[6,3],[3,3]] |
| Determinant | `det(MatA)` | 1 |
| Transpose | `Trn(MatC)` | [[1,0],[0,−1],[−1,1]] |
| Inverse (**x⁻¹ key only**) | `MatA⁻¹` | [[1,−1],[−1,2]] |
| Element-wise absolute value | `Abs(MatB)` | [[2,1],[1,2]] |
| Square / cube (**x² and x³ keys only**, not x^▭) | `MatA²`, `MatA³` | [[5,3],[3,2]], [[13,8],[8,5]] |
| Row echelon form | `Ref([[1,2,3],[4,5,6],[7,8,9]])` | [[1, 1,1428…, 1,2857…],[0,1,2],[0,0,0]] |
| Reduced row echelon form | `Rref(` same matrix `)` | [[1,0,−1],[0,1,2],[0,0,0]] |

**Ref uses partial pivoting** (V by reproducing the screen). The first row of the result is the
original row 3 divided by 7: 8/7 = 1,142857…, 9/7 = 1,285714…. Elimination without row swaps would
have given [[1,2,3],[0,1,2],[0,0,0]], which is not what the screen shows. So the largest absolute
value in the column becomes the pivot row.

- **Dimension ERROR:** a matrix with no dimension set, or sizes that do not fit the operation.
- **Stack ERROR:** the matrix/vector stack overflowed.
- Whether `MatA÷2` is allowed: ÷ is listed as a MatAns hot key (V), so scalar division is I.

### 6.8 TABLE mode (V, [M:using_calculation_modes/creating_number.html])

- Enter f(X), then `=`. Then enter g(X), then `=`. Leaving g blank gives a table of f only. If the
  setup TABLE item is `f(x)`, there is no g prompt. Natural input is allowed.
- **Prompts:** `Start?` (default 1), `End?` (default 5, must be greater than Start), `Step?`
  (default 1).
- **Columns:** X, F(X), G(X).
- **Example:** f = x²+½, g = x²−½, Start −1, End 1, Step 0,5 → X = −1, −0,5, 0, … with F = 1,5,
  0,75, 0,5 and G = 0,5, −0,25, −0,5.
- **Limits:** **30 rows** with f(x) only and **20 rows** with f(x),g(x). Going over gives
  **Insufficient MEM**.
- The table is view-only. **X is overwritten** by the generation. Pol, Rec, ∫, d/dx and Σ are
  banned in f and g.
- CONV is not available in TABLE. CONST is.
- AC returns to the f(X) input.

### 6.9 VECTOR mode (V, [M:using_calculation_modes/vector_calculations.html])

- **Variables:** VctA, VctB, VctC, VctAns. 2-D or 3-D.
- **Entry:** `MODE 8` → `Vector? 1:VctA 2:VctB 3:VctC` → dimension `1:3` or `2:2`.
- **VECTOR menu** (`SHIFT 5`): `1:Dim 2:Data 3:VctA 4:VctB 5:VctC 6:VctAns 7:Dot`.
- **VctAns screen:** pressing `+ − × ÷` jumps to the calculation line with VctAns typed.
- **Copying:** works the same way as matrices.

**Operations, with VctA=(1,2), VctB=(3,4), VctC=(2,−1,2) (V):**

| Operation | Input | Result |
|---|---|---|
| Sum | `VctA+VctB` | (4,6) |
| Scalar | `3×VctA` | (3,6) |
| Using VctAns | then `−VctB` | (0,2) |
| Dot product (`SHIFT 5 7`, shown as `•`, priority 9) | `VctA•VctB` | 11 |
| Cross product (`×` between two vectors; 2-D vectors are treated as z = 0) | `VctA×VctB` | (0,0,−2) |
| Magnitude | `Abs(VctC)` | 3 |
| Angle, Fix 3 | `(VctA•VctB)÷(Abs(VctA)Abs(VctB))` = 0,984; `cos⁻¹(Ans)` | 10,305 |

There is no unit-vector or angle command (not documented; I absent).

### 6.10 DIST mode (V, [M:using_calculation_modes/dist.html])

**Menu** (`MODE ▼ 1`):
- page 1: `1:Normal PD 2:Normal CD 3:Inverse Normal 4:Binomial PD`, with ▼
- page 2: `1:Binomial CD 2:Poisson PD 3:Poisson CD`

Change the type later with `SHIFT 1 → 1:Type`.

| Type | Inputs, prompted in this order |
|---|---|
| Normal PD | x, σ, μ |
| Normal CD | Lower, Upper, σ, μ |
| Inverse Normal | Area, σ, μ. The tail is **always left**. |
| Binomial PD / CD | x (or List), N, p |
| Poisson PD / CD | x (or List), μ |

- **Constraints:** 0 ≤ Area ≤ 1. 0 ≤ p ≤ 1. σ must be positive (the guide prints "σ < 0", a typo;
  see §12).
- **Ranges for N, x and Poisson μ are U.**
- **Prompts:** `Normal PD:x?` and so on. The defaults shown on screen are x = 0, σ = 1, μ = 0
  (V, images).
- **Results:** the PD result is labelled `p=`. Example: x=36, σ=2, μ=35 → `p=` 0,1760326634. The
  labels for CD and Inverse Normal results are **U**.
- **Accuracy:** "up to five significant digits".
- **Binomial and Poisson** first ask `1:List 2:Var`.
  - **List:** up to **25** x values. The screen has X and Ans columns and a type tag such as `Bpd`.
    Edit with `SHIFT 1 → 2:Edit → 1:Ins` or `2:Del-A`; `DEL` deletes one. An out-of-range x shows
    `ERROR` in its Ans cell.
  - **Var:** a single x. The result goes to **Ans**.
- **Example:** x = {10;11;12;13;14}, N=15, p=0,6 (Binomial PD) → 0,18594, 0,12678, 0,063388,
  0,021942, 4,7018×10⁻³. The cell shows `0,1859` and so on. Selecting a cell shows the full value
  at the bottom, e.g. 0,00470184984.
- **Navigation:** `=` or AC on a result returns to the first input (Normal) or to N (List mode).
  AC in List mode keeps the data.
- **Banned in inputs:** Pol, Rec, ÷R, ∫ and d/dx.
- The type tags for the other list types (Bcd, Ppd, Pcd) are I.

### 6.11 INEQ mode (V, [M:using_calculation_modes/ineq.html])

- **Menu** (`MODE ▼ 2`): `1:aX²+bX+c`, `2:aX³+bX²+cX+d`.
- **Symbol menu:** `1: …>0`, `2: …<0`, `3: …≥0`, `4: …≤0`.
- **Editor:** a, b, c(, d). AC zeros them. The editor has the same refusals as EQN.

| Inequality | Result template | Values |
|---|---|---|
| x²+2x−3 < 0 | `A<X<B` | −3<X<1 |
| x²+2x−3 ≥ 0 | `X≤A;B≤X` | X≤−3;1≤X. In Linear: the template line, then `A=`, `B=` on separate lines. |
| 2x³−3x² ≥ 0 | `X=A;B≤X` | X=0;3/2≤X |
| 3x³+3x²−x > 0 | `A<X<B;C<X` | (−3−√21)/6<X<0; (−3+√21)/6<X. Scroll with ▶. In Linear: A=−1,263762616, B=0, C=0,2637626158. |
| x² ≥ 0 | — | `All Real Numbers` |
| x² < 0 | — | `No-Solution` |

- ENG is not allowed on the solution screen.
- The full set of templates the device can produce is **U** beyond those listed.

### 6.12 RATIO mode (V, [M:using_calculation_modes/ratio.html])

- **Menu** (`MODE ▼ 3`): `1:a:b=X:d`, `2:a:b=c:X`.
- **Editor:** up to 10 digits per value.

| Type | Result |
|---|---|
| `a:b=X:d` | X = a·d/b |
| `a:b=c:X` | X = b·c/a |

- **Examples (V):** 3:8=X:12 → `X=` 9/2. 1:2=X:10 → 5. 1:2=10:X → 20. [JRW] (S): 1,5:12,5 = 5:X →
  125/3, then S⇔D → 41,6666667.
- **A 0 coefficient gives Math ERROR.**
- `=` on a result returns to the editor. Re-entering RATIO and choosing a type zeros the values.

---

## 7. Function input ranges (complete table)

All V, from [M:technical_informatoin/calculation_ranges.html].

| Function | Input range |
|---|---|
| sin x, cos x | Deg 0≤\|x\|<9×10⁹; Rad 0≤\|x\|<157079632,7; Gra 0≤\|x\|<10¹⁰ |
| tan x | as sin, except \|x\| = (2n−1)×90 (Deg), (2n−1)×π/2 (Rad), (2n−1)×100 (Gra) |
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
| √x | 0 ≤ x < 10¹⁰⁰ |
| x² | \|x\| < 10⁵⁰ |
| x⁻¹ | \|x\| < 10¹⁰⁰, x ≠ 0 |
| ∛x | \|x\| < 10¹⁰⁰ |
| x! | 0 ≤ x ≤ 69, integer |
| nPr | 0 ≤ n < 10¹⁰, 0 ≤ r ≤ n, integers; 1 ≤ n!/(n−r)! < 10¹⁰⁰ |
| nCr | 0 ≤ n < 10¹⁰, 0 ≤ r ≤ n, integers; 1 ≤ n!/r! < 10¹⁰⁰ or 1 ≤ n!/(n−r)! < 10¹⁰⁰ |
| Pol(x;y) | \|x\|, \|y\| ≤ 9,999999999×10⁹⁹; √(x²+y²) ≤ 9,999999999×10⁹⁹ |
| Rec(r;θ) | 0 ≤ r ≤ 9,999999999×10⁹⁹; θ as sin x |
| °'" | a°b'c": \|a\|, b, c < 10¹⁰⁰; 0 ≤ b, c. Displayed seconds ±1 in the 2nd decimal. |
| °'" ← (decimal → sexagesimal) | \|x\| < 10¹⁰⁰; conversions 0°0'0" ≤ \|x\| ≤ 9999999°59'59" |
| xʸ | x>0: −10¹⁰⁰ < y log x < 100. x=0: y>0. x<0: y = n or m/(2n+1), with −10¹⁰⁰ < y log\|x\| < 100 (m, n integers) |
| ˣ√y | y>0: x≠0, −10¹⁰⁰ < (1/x) log y < 100. y=0: x>0. y<0: x = 2n+1 or (2n+1)/m, m≠0, with −10¹⁰⁰ < (1/x) log\|y\| < 100 |
| a b/c | integer + numerator + denominator ≤ 10 digits, including separators |
| RanInt#(a;b) | a < b; \|a\|, \|b\| < 10¹⁰; b − a < 10¹⁰ |
| GCD(a;b) | \|a\|, \|b\| < 10¹⁰, integers |
| LCM(a;b) | 0 ≤ a, b < 10¹⁰, integers |
| Σ | integers a ≤ b, −10¹⁰ < a ≤ b < 10¹⁰ ([M:function_calculations/calculations.html]) |
| ∫, d/dx tol | ≥ 1×10⁻¹⁴; defaults 1×10⁻⁵ (∫) and 1×10⁻¹⁰ (d/dx) |
| FACT | positive integer ≤ 10 digits |

xʸ, ˣ√y, ∛, x!, nPr and nCr accumulate error internally. Error is largest near singular points and
inflection points.

---

## 8. Priority order

V, [M:technical_informatoin/sequence.html]. Equal priorities are evaluated left to right.

| # | Group |
|---|---|
| 1 | parenthetical expressions |
| 2 | functions with a right-hand argument and a closing `)` (sin(, log(, Abs(, Pol(, GCD(, …) |
| 3 | postfix functions: x², x³, x⁻¹, x!, °'", °, ʳ, ᵍ, %, ▸t; powers x^▮; roots ˣ√ |
| 4 | fractions |
| 5 | negative sign (−); base prefixes d, h, b, o |
| 6 | metric conversion commands; STAT estimates x̂, ŷ, x̂₁, x̂₂ |
| 7 | multiplication with the × omitted |
| 8 | nPr, nCr, ∠ |
| 9 | dot product • |
| 10 | ×, ÷, ÷R |
| 11 | +, − |
| 12 | and |
| 13 | or, xor, xnor |

---

## 9. Errors

V, [M:technical_informatoin/errors.html]. The error screen is: the message, then `[AC] :Cancel`,
then `[◀][▶]:Goto`. ◀ or ▶ puts the cursor at the error. AC clears the error and the expression.

| Message | Cause |
|---|---|
| Math ERROR | A result or an intermediate value is out of range; an input is out of range; an illegal operation (÷0); FACT on a non-positive-integer; a 0 in RATIO |
| Stack ERROR | The numeric, command, matrix or vector stack overflowed |
| Syntax ERROR | Malformed expression |
| Argument ERROR | Bad argument |
| Dimension ERROR | MATRIX/VECTOR: no dimension set, or incompatible dimensions |
| Variable ERROR | SOLVE: no X and no named variable, or the named variable is absent |
| Can't Solve | SOLVE found no solution |
| Insufficient MEM | TABLE needs more than 30 rows (f only) or 20 rows (f,g) |
| Time Out | ∫ or d/dx did not meet its end condition. The guide suggests a larger tol. |

---

## 10. Differences from the international siblings

### 10.1 fx-991ZA PLUS II compared with fx-991ES PLUS 2nd edition (the global model)

Sources: [M] diffed against [ES2], page by page, and [KB] compared with the [ES2P] image. All rows V
(V\* for the ES side).

| Area | fx-991ES PLUS 2nd edition | fx-991ZA PLUS II |
|---|---|---|
| Decimal key and display | `•` key; display chosen by SETUP ▼5 Disp (Dot/Comma); input always uses a dot | `,` key; comma always; **no Disp item** |
| Separators | `,` (`SHIFT )` legend ","); `;` only when Disp = Comma | `;` (`SHIFT )` legend ";") |
| Default Norm | Norm 1 | **Norm 2** |
| SETUP page 2 | 1 ab/c, 2 d/c, 3 CMPLX, 4 STAT, 5 Disp, 6 ◀CONT▶ | 1 ab/c, 2 d/c, 3 CMPLX, 4 STAT, **5 TABLE, 6 APO**, 7 ◀CONT▶ |
| Auto power-off | 10 min only | 10 or 60 min |
| Modes | 8 (no page 2) | 8 plus **DIST, INEQ, RATIO** |
| `SHIFT 1` legend | STAT | **STAT/DIST** |
| `SHIFT °'"` | "←" | **FACT** |
| `ALPHA ▭/▭` | — | **÷R** |
| `ALPHA ×` / `ALPHA ÷` | — | **GCD / LCM** |
| `ALPHA Ans` | — | **PreAns** |
| 1-VAR MinMax | minX, maxX | + **Q1, med, Q3** |
| Statistics editor rows | 80 / 40 / 26 | **40 / 20 / 26** |
| MATRIX menu page 2 | — | **Ref, Rref** |
| TABLE | f(x) only, 30 rows | **f(x) and g(x)**; 30 rows (f) or 20 rows (f,g) |
| EQN quadratic | complex-root example shown; no vertex | **X-Value / Y-Value Minimum (Maximum)** after the roots |
| EQN | — | a message for no solution or infinite solutions |
| Body (looks) | black | white (for the other researcher) |
| Shared, unchanged | everything else in [M]: RanInt#, Rnd, CONST/CONV lists and numbering, hyp menu, CMPLX menu, BASE menu, ∫/d/dx/Σ rules, MATRIX/VECTOR core, ranges, errors, MODE 0 authenticity | |

### 10.2 fx-991ZA PLUS II compared with fx-115ES PLUS / fx-991ES PLUS C 2nd edition (North America)

[M] diffed against [C115]. All rows V (V\* for the C115 side). Everything not listed is identical.
Identical items include PreAns, ÷R, FACT, GCD/LCM, Q1/med/Q3, Ref/Rref, dual TABLE, the quadratic
vertex, DIST, INEQ, and the 40/20/26 editor rows.

| Area | fx-115ES PLUS / 991ES PLUS C 2nd edition | fx-991ZA PLUS II |
|---|---|---|
| Π (product) | `ALPHA log▭▭` | **absent** |
| Recurring decimals | `ALPHA √▭` and setup Rdec (default ON) | **absent** |
| Int / Intg | `ALPHA +` / `ALPHA −` | **absent** (`+` and `−` have no ALPHA legend [KB]) |
| VERIF mode | MODE ▼ 2 | **absent** |
| RATIO mode | absent | **MODE ▼ 3** |
| Page 2 of the mode menu | 1 INEQ, 2 VERIF, 3 DIST | **1 DIST, 2 INEQ, 3 RATIO** |
| SETUP page 2 | … 6 Rdec, 7 Disp, 8 ◀CONT▶ | … **6 APO**, 7 ◀CONT▶ |
| Default Norm | Norm 1 | Norm 2 |
| Decimal and separator | dot and `,` (Disp selectable) | comma and `;` (fixed) |

### 10.3 fx-991ZA PLUS II compared with the fx-991ZA PLUS (1st generation)

S, from [JRW]:
- The 1st generation had modes 1–8 only.
- The II adds DIST, INEQ and RATIO.
- The II changed the default to Norm 2.
- The II changed the dot to a comma, on the keycap and on the display.
- [JRW] also presents GCD/LCM and quotient-and-remainder as new.

---

## 11. Not on this model — do not implement

These come from ClassWiz or other models and are absent from [M] and [KB]. Tags: V that [M] and
[KB] do not have them; I that the device lacks them.

- The **OPTN key** and OPTN sub-menus. Function catalogs by menu ("CATALOG").
- **ReP, ImP**, polar/rectangular conversion of complex numbers beyond ▸r∠θ / ▸a+bi.
- **Π product**, recurring decimals, **Int, Intg**, VERIF mode. (These are in [C115].)
- Engineering SI-prefix symbols (k, M, m, μ, …).
- Spreadsheet, QR graphing, unit vectors, vector angle commands, matrix Identity, matrices larger
  than 3×3, quartic equations, linear inequalities.
- A Disp (dot/comma) setting, and a recurring-decimal setting.

---

## 12. Errata in the official guide

All V that the guide says this. The **I** column says why it is wrong.

| Location | Printed | Correct (I) |
|---|---|---|
| Scientific constants, item 26 | "molar volume of ideal gas (237,15K; 100kPa)" | 273.15 K. The same typo is in [ES2] and [C115], and CODATA's quantity is 273.15 K / 100 kPa. |
| DIST, "Variables that Accept Input" | "σ: standard deviation (σ < 0)" | σ > 0 |
| Statistics editor capacity | 20 rows for X+FREQ, but 26 rows for X+Y+FREQ | It looks inverted, but [C115] prints the same. **Keep it as printed** until a device or emulator check (§13). |
| Calculation ranges table | lists "°'"←", a decimal → sexagesimal command | On the ZA, `SHIFT °'"` is FACT and the conversion is done by pressing `°'"` on a result. This row probably comes from the ES PLUS legend "←". |
| π / e internal values | π = 3,14159265358980; e = 2,71828182845904 | Not the correctly rounded 15-digit values (§3.3). Keep the guide's values. |

---

## 13. Open questions, and what would settle them

The **fx-ZA PLUS Emulator** for this exact model is available from CASIO as a 90-day trial
(`edu.casio.com/softwarelicense/aid/manager/en/`, per [JRW]; it is listed on
`edu.casio.com/intl/software/emulators/`). It would settle almost every item below without hardware.

1. **The basis of "452 functions".** Only distributor and social sources give it. Ask CASIO South
   Africa or James Ralph for the breakdown.
2. ~~hyp menu items 2, 3, 4 and 6, and the screen layout.~~ Now S from the GYX string table (§5.11).
   The emulator would promote it to V.
3. ~~DRG▸ item 1 (°).~~ Now S (§5.12).
4. ~~Matrix dimension menu items other than 4 and 5, and whether 1×n exists.~~ Now S (§6.7): 1×n exists.
5. **Mode availability** of each function not stated in [M], such as Σ, Pol, Rec, nPr, GCD, Ran# and
   Rnd in CMPLX, STAT, MATRIX and VECTOR. Try each one in each mode.
6. **The ÷R "very large" threshold.** Bisect between 10¹⁰ and 2×10¹⁰.
7. **Complex roots in EQN**, and the exact no-solution and infinite-solution messages.
8. **The DIST result labels** for Normal CD and Inverse Normal; the ranges for N, x and Poisson μ;
   the List type tags.
9. **The quartile rule for odd n.** Try 1,2,3,4,5,6,7: Q1 = 2 (exclusive) or 2,5 (inclusive).
10. **Conversion factors:** km/h is settled (÷3,6; [JRW] s.19 screen). Still open: acre (GYX 4046,856
    vs SP 811 4046,873), hp (GYX 0,7457 vs SP 811 0,7456999), lb. Try `1 acre▸m²`, `1 hp▸kW`, `1 kg▸lb`.
11. **The internal π and e:** `π − 3,1415926535898` and `e − 2,7182818284590` with high display
    precision (Sci 0, or by subtracting).
12. **The statistics editor capacities** (40/20/26). Fill them on the emulator.
13. **CONST/CONV prompt handling** of 00, 41–99 and AC. (S, partial: the GYX ROM configuration
    stores a maximum CONV index of 40 and documents that a higher index returns **Syntax ERROR**.)
14. **Behaviour of keys with no legend** (`SHIFT 6`, `ALPHA` on number keys, and so on).
15. **Whether CMPLX allows √(−4)**, and trig of a complex number.
16. **The Ran# and RanInt# generator** (sequence and seeding) cannot be matched byte for byte. Only
    the range and the 3-decimal granularity are specified.
17. **The SOLVE iteration count, convergence test and default starting value** (the current X).

---

## 14. Sources

Accessed 2026-10-02.

**Official CASIO, this model**
- User's Guide (HTML, all 71 pages): https://support.casio.com/global/en/calc/manual/fx-991ZAPLUSII_en/ . The page paths are cited inline as [M:path].
- User's Guide (PDF): https://www.casio.com/content/dam/casio/global/support/manuals/calculators/pdf/004-en/f/fx-991ZA_PLUSII_EN.pdf
- Key glyph images used for the key mapping: https://support.casio.com/global/en/calc/manual/fx-991ZAPLUSII_en/img/key/ (57344–57467)
- Product page: https://www.casio.com/za/scientific-calculators/product.FX-991ZAPLUSII-SDT/
- Product image (key legends): https://www.casio.com/content/dam/casio/product-info/locales/za/en/calc/product/scientific/F/FX/FX9/fx-991zaplusii-sdt/assets/fx-991ZA_PLUS-2.png
- edu.casio.com ZA product page: https://edu.casio.com/za/products/ntd/fx991za_2/
- edu.casio.com ZA comparison chart: https://edu.casio.com/za/products/ntd/comparison.html

**Official CASIO, siblings (comparison only)**
- fx-570ES/991ES/9910NG PLUS 2nd edition, HTML: https://support.casio.com/global/en/calc/manual/fx-570ESPLUS_991ESPLUS_en/ ; PDF: https://www.casio.com/content/dam/casio/global/support/manuals/calculators/pdf/2022/mutual/fx-570ES_991ES_9910NG_PLUS_EN.pdf
- fx-991ES PLUS-2 product page ("Number of Functions: 417") and image: https://www.casio.com/intl/scientific-calculators/product.FX-991ESPLUS-2/
- fx-115ES PLUS / fx-991ES PLUS C 2nd edition, HTML: https://support.casio.com/global/en/calc/manual/fx-115ESPLUS_991ESPLUSC_en/
- fx-570/991ES PLUS 1st edition (official PDF, mirrored): https://biomath.med.uth.gr/files/bsc/en/fx-570_991ES_PLUS_EN.pdf

**Distributor and secondary**
- James Ralph Trading (CASIO SA distributor) product page ("452 functions"): https://www.casio.jamesralphedu.co.za/products/p/casio-fx-991za-plus-ii-sa-education-advanced-scientific-calculator
- James Ralph workshop slides on CASIO's edumaterial server: https://edumaterial.casio-intl.com/resource/contents/1368
- CASIO Calculators ZA Facebook post (search snippet only; the page could not be fetched): https://www.facebook.com/CasioCalculatorsZA/photos/make-your-calculations-stress-free-with-the-casio-fx-991za-plus-ii-advanced-scie/1738345946371138/
- Retailer counts: https://www.amazon.co.za/Casio-FX-991ZA-Advanced-Scientific-Calculator/dp/B0CJ8ZSSJX (403), https://www.speciality.co.za/product/casio-fx991za-plus-ii-advanced-scientific-calculator/ (433), https://newworld.co.za/products/casio-fx-991za-plus-ii-calculator-white (452), https://www.geewiz.co.za/calculators/152655-casio-fx-991-za-plus-ii-calculator.html (452)

**Reference data**
- NIST CODATA 2014 complete listing: https://physics.nist.gov/cuu/Constants/ArchiveASCII/allascii_2014.txt
- NIST SP 811 Appendix B.8: https://www.nist.gov/pml/special-publication-811/nist-guide-si-appendix-b-conversion-factors/nist-guide-si-appendix-b8

**Local working copies** (scratchpad, not committed):
`…/scratchpad/calc-research/f06/`. This holds the HTML and text of all three manuals, the diffs
(`es_vs_za.diff`, `c115_vs_za.diff`), the key-glyph montages and the product images.
