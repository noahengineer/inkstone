# 00 — fx-991ZA PLUS II research: index, settled identity, contradictions resolved, what is still unknown

Cross-check of research files 01–08, written 2026-10-02. I read all eight files in full and went back
to primary sources where they disagreed or left gaps. Every reference file was **edited in place**
where it was wrong; each edit says "cross-check" so it can be found with `grep -ni cross-check`.

Confidence tags are the same as in the other files: **VERIFIED** (official Casio source for this
model, or a Casio-hosted capture of this model's screen), **SECONDARY** (sibling firmware, community,
distributor, retailer), **INFERRED** (reasoning), **UNKNOWN**.

---

## 1. The model, settled

| Question | Answer | Confidence and source |
|---|---|---|
| What is it? | Casio's South-African-market **fx-991ZA PLUS II**, which Casio also calls **"fx-991ZA PLUS 2nd edition"** (SKU FX-991ZAPLUSII-SDT). A non-programmable NATURAL-V.P.A.M. scientific calculator. | VERIFIED: official guide <https://www.casio.com/content/dam/casio/global/support/manuals/calculators/pdf/004-en/f/fx-991ZA_PLUSII_EN.pdf>; edu.casio.com/za product page and comparison chart |
| Family | **fx-ES PLUS Series, 2nd edition** (2019 onwards). Not a ClassWiz (EX/CW). No OPTN key; mode menus are SHIFT + number. | VERIFIED: Casio catalogue <https://www.casio.com/content/dam/casio/global/calculator/scientific-calculators/catalog/fx-ES_PLUS_2.pdf>; Casio's fx-ES PLUS Emulator page lists "fx-991ZA PLUS-2" among the 2nd-edition models (07 [S2]) |
| Firmware | **CY-849, revision B, checksum 06B3** | SECONDARY, three independent agreeing sources: Calcupedia's self-test list; a real-unit self-test photo (01 [CR] `15_test.jpg`, which also shows the fx-115ES PLUS 2nd edition as CY-847); Casio's own ZA HTML guide names its ZA-only images `CY849_RAITO_*.png`, `CY849_TABLE_03.png` |
| CPU | ROHM/LAPIS nX-U8/100 (ES PLUS hardware) | SECONDARY (07 §2) |
| Display | **31 × 96** full-dot LCD plus a separate row of **18** segment status icons above it; two-line Natural Display | VERIFIED (ZA comparison chart; guide p.8) |
| Modes | **11**: page 1 COMP, CMPLX, STAT, BASE-N, EQN, MATRIX, TABLE, VECTOR; page 2 DIST, INEQ, RATIO. `MODE 0` = authenticity ID/QR screen | VERIFIED (guide p.10, p.92; SA captures) |
| ZA-specific rules that override every sibling | Decimal **comma** on key and display, no Dot/Comma setting · argument and result separator **`;`** (SHIFT `)`) · default **Norm 2** · SETUP page 2 is `1:ab/c 2:d/c 3:CMPLX 4:STAT 5:TABLE 6:APO 7:◀CONT▶` · APO 10/60 min · **no** recurring decimals, ∏, Int/Intg, VERIF mode, SI prefixes | VERIFIED (guide) |
| **Body and key layout sibling** | The international **fx-991ES PLUS 2nd edition** (fx-991ES PLUS-2, CY-845): same 11.1 × 77 × 161.5 mm, 95 g body and the same key positions. It is **black** and differs in legends and functions. | VERIFIED (catalogue dimensions); legend diff VERIFIED from Casio product images (06 §10.1) |
| **Look-alike twin** (white body, colour scheme, legend layout) | **fx-115ES PLUS 2nd edition** (US). Only some printed legends differ (01 §5.5). | SECONDARY (side-by-side photo, 01 [CR]) |
| **Behavioural sibling** — the one whose manual reads like the ZA's | **fx-115ES PLUS / fx-991ES PLUS C 2nd edition** (North America, CY-847). Shares PreAns, ÷R, FACT, GCD/LCM, RanInt#, Q1/med/Q3, Ref/Rref, two-function TABLE, the quadratic vertex output, DIST, INEQ and the 40/20/26 stat-editor rows. **The ZA adds** RATIO and APO 10/60; **the ZA removes** ∏, recurring decimals, Int/Intg, VERIF and the Disp setting; **the ZA changes** to comma, `;`, Norm 2. | VERIFIED: page-by-page guide diff (06 §10.2); Casio's ZA HTML guide reuses that model's `CY846_847_TABLE_01/02.png` screen images |
| Not to be confused with | fx-991ZA PLUS (1st generation: 8 modes, decimal point, Norm 1); fx-991EX / fx-991CW ClassWiz (different display, keys, menus); the international fx-991ES PLUS-2 (lacks DIST, INEQ, RATIO, ÷R, FACT, GCD/LCM, PreAns, Q1/Q3, Ref/Rref, dual TABLE — Casio's catalogue lists only "Random integers" as its main addition, 417 functions) | VERIFIED (catalogue, guides); 1st-gen facts SECONDARY (distributor) |
| Function count | **452** per Casio's SA distributor and Casio ZA social media; not on any Casio-owned page. Treat as marketing. (417 = fx-991ES PLUS-2, VERIFIED; 552 = fx-991EX, a wrong meta tag on the distributor page.) | SECONDARY |
| Emulate it, or build it? | **Build from scratch.** Casio's official emulator is Windows-only licensed software that forbids reverse engineering and redistribution; no ZA PLUS II ROM is public; open-source nX-U8 cores are GPL and need a ROM. Use the official emulator (90-day trial) only as a **black-box oracle**. Calcupedia reports Casio will discontinue its PC emulators on 14 May 2027 — collect golden vectors before then. | VERIFIED licence terms; ROM availability and discontinuation date SECONDARY (07 §3) |

---

## 2. Index of files

| File | What it holds | Status after the cross-check |
|---|---|---|
| [01-hardware-and-keys.md](01-hardware-and-keys.md) | Body, LCD, status icons, all 50 keys and legends with colours, palette, typography, per-key mm rectangles, a Compose drawing recipe | §3.6 font metrics **corrected** (small font is 5×6, not 5×7) and **filled** (large font 5×9; pixel layout of result line, fraction, superscript, subscript, minus, comma). Contrast steps filled (26). Status-bit map partly confirmed. ROM ID corroborated. |
| [02-manual-core-and-display.md](02-manual-core-and-display.md) | General operation, SETUP/CLR, input/cursor/templates, output rules (Norm/Fix/Sci/ENG, fractions, √, π, DMS), memory, every COMP feature, errors, priority, ranges | Sibling conclusion **corrected** (catalogue misread). MODE-menu font **corrected**. CLR confirmation text **corrected**. Error texts, SETUP sub-prompts, hyp/DRG menus, Ans-update keys **filled**. π-form eligibility caveat added. |
| [03-manual-modes.md](03-manual-modes.md) | CMPLX, STAT, BASE-N, EQN, MATRIX, TABLE, VECTOR, DIST, INEQ, RATIO, CONST, CONV | CONV factor table **rebuilt** (km/h was wrong; acre and hp flagged). [ES2] tag collision explained. Menu layouts, matrix dimension menu (1×n exists), EQN solution-screen layout, complex-root evidence, constant signs and stored digits **filled**. |
| [04-examples-comp.md](04-examples-comp.md) | ~100 COMP/general test vectors from the guide plus 8 from the SA handout | H-08 annotated as the km/h evidence. No vector was wrong. |
| [05-examples-modes.md](05-examples-modes.md) | 68 mode/CONST/CONV test vectors from the guide | EX-CONV-02 note narrowed. **5 new vectors** from SA captures (EQN ×2, INEQ, RATIO, STAT quartiles). |
| [06-function-catalog.md](06-function-catalog.md) | Every function by key/menu, syntax, range, modes; sibling diffs; "not on this model" list; errata | CONV table **rebuilt**; `(−)5 ÷R 2 → −2,5` downgraded to INFERRED; hyp, DRG, matrix-dimension items filled; key-count reconciled with 01. |
| [07-emulation-and-internals.md](07-emulation-and-internals.md) | Why not to emulate; licence/legal position; BCD number format, π/e, result-form mechanisms, special-angle tables, G7-K15, normal-CDF coefficients, error codes; black-box tests T1–T15 | LCD width upgraded to VERIFIED; ROM ID corroborated; **new §4.9a** (firmware string table and conversion-factor table, and why they carry over); π-form eligibility caveat; setup-byte encoding conflict noted; **tests T16–T18** added. |
| [08-inkstone-integration.md](08-inkstone-integration.md) | `:core:calc` KMP module, `:app` package, entry chip, window behaviour over ink, insert-into-note, PDF glyph coverage, detekt limits, verification plan | Dimensions source corrected; pointer to 01 §3.6 font metrics; identity pointer. No architectural change. |

### Sources the cross-check added

| Id | Source | Used for |
|---|---|---|
| [CAT] | Casio, *fx-ES PLUS Series 2nd edition* catalogue (PDF, 3 pp.), lineup page rendered and read: <https://www.casio.com/content/dam/casio/global/calculator/scientific-calculators/catalog/fx-ES_PLUS_2.pdf> | Settling the sibling question (C1); body dimensions shared with fx-991ES PLUS-2 |
| [SA-img] | The PNG screen captures embedded in the Casio-hosted SA handout <https://edumaterial.casio-intl.com/resource/contents/1368>, extracted with PyMuPDF. They are exact 2× (208×72) or 4× (388×144) nearest-neighbour copies of the emulator LCD (a 2×2-block fit has zero mismatched blocks), so dots can be counted exactly. | Font metrics and pixel layout (C4, C5), MODE-menu font (C2), km/h factor (C3), EQN/STAT screens |
| [GYX] | String, constant and conversion tables in the community decompilation of Casio's ES PLUS GYX firmware (07 [S13]; <https://codeberg.org/gwetm/gyx-decomp>). The repo has **no licence**, so it is used for *data the screen shows* (menu text, table values), never as code to translate. | Error texts, menu layouts, SETUP sub-prompts, CLR text, matrix/vector dimension menus, conversion factors, constant storage convention, contrast range, status bits |
| [CP-diag] | Calcupedia (backup) self-test page, <https://fxesdev.github.io/calcupedia_backup/docs/diagnostic.html> | ROM IDs for every ES PLUS 2nd-edition model; key-test counts (50 keys); ES PLUS contrast range |
| [ES2-intl] | Official online guide, fx-570ES/991ES/9910NG PLUS 2nd edition (already downloaded by file 06) | Which keys update Ans; complex roots in EQN |

Scratchpad tools (not committed): `…/scratchpad/calc-research/x00/` holds the catalogue, the extracted
SA images (`saimg/`), and `grid2.py` / `grid3.py`, which turn a capture back into a dot grid.

---

## 3. Contradictions found and how each was resolved

| # | What disagreed | Which file was wrong | What settled it |
|---|---|---|---|
| **C1** | **Nearest international sibling.** 02 said the fx-991ES PLUS 2nd edition, citing Casio's catalogue as listing "Prime factorization, Random integers, Inequality, Ratio calculation" for it. 06 said the fx-115ES PLUS / fx-991ES PLUS C 2nd edition. | **02.** It read a three-column page as one column. | [CAT] lineup page, rendered: fx-991ES PLUS-2 → "Random integers" only (417 functions); those four features are the **fx-95ES PLUS-2** column. Plus Casio's ZA guide reusing `CY846_847_*` images (VERIFIED). Final split: body/keys ↔ fx-991ES PLUS-2; behaviour ↔ fx-115ES PLUS / 991ES PLUS C 2nd ed.; look ↔ fx-115ES PLUS 2nd ed. (§1). |
| **C2** | **MODE-menu font.** 02 said "both pages use the large font"; 01 said MODE and SETUP use the small font. | **02.** | [SA-img] slide 2/15 captures: 4 text lines on an 8-dot pitch, 6-dot-tall glyphs. The large font (9 dots) cannot fit 4 lines in 31 rows. |
| **C3** | **Conversion factors.** 03 §15 and 06 §5.28 inferred, from the one oz screen, that *every* pair uses NIST SP 811's printed factor (so km/h ×0.2777778, acre 4046.873, hp 0.7456999). 04 H-08 recorded `120km/h▶m/s = 33,33333333` without drawing the conclusion. | **03 and 06.** | 120 × 0.2777778 = 33.333336, which would display `33,333336`; the ZA screen shows `33,33333333`, so km/h is **÷3.6 exact**. [GYX]'s factor table has one factor per pair, matches every ZA data point (2.54, 28.34952, 3.6, ×1.8+32), and differs from SP 811 for **acre (4046.856)** and **hp (0.7457)**. Both tables rebuilt on [GYX]; acre, hp, lb left as emulator checks (T17). The `S⇔D` in H-08's key sequence also implies conversions run exactly and can give fractions in MathO (INFERRED). |
| **C4** | **Small (menu) font height.** 01 said 5×7 in a 6×8 cell, from a real-unit photo ("70 px = 7 dots"). | **01.** | [SA-img] STAT-menu and `Norm 1~2?` captures: every glyph is 6 dots tall, lines on rows 1–6, 9–14, 17–22, 25–30. Recounting the real-unit photo at 4× zoom also gives 6 rows; the 7 included blur. |
| **C5** | **Large font height.** 01: "9 or 10 dots (UNKNOWN)". | Gap, not an error. | [SA-img]: digits and capitals are **9** dots; input line rows 1–9, result rows 22–30; last result glyph on cols 90–94. |
| **C6** | **LCD width confidence.** 07 marked 96 columns SECONDARY; 01 VERIFIED. | **07** understated. | Casio ZA comparison chart "31 x 96 Full Dot Display" (official). |
| **C7** | **Variables C, D, E, F on hyp/sin/cos/tan.** 02 said SECONDARY (OCR of a slide); 01 and 06 said VERIFIED from Casio's product image. | **02** understated. | The magenta ┌C┐…┌F┐ legends on Casio's render; the guide uses `hyp` as the MatC copy target. |
| **C8** | **Key count.** 01: 50. 06: 47. | Neither. Counting convention (06 counts the cursor pad as one). | Calcupedia's 2nd-edition key tests: 29 (excl. ON) + 20 + ON = 50. |
| **C9** | **`(−)5 ÷R 2` result.** 06 wrote "→ −2,5" inside a VERIFIED section; 02 and 04 said the guide prints no value and MathO probably shows `-5/2`. | **06** overstated. | Guide text (only "calculated as −5 ÷ 2"). Downgraded to INFERRED. |
| **C10** | **"[ES2] says nothing about EQN complex roots" (03) vs "[ES2] shows complex roots" (06).** | Neither — a **tag collision.** 03's [ES2] is the fx-115ES PLUS / 991ES PLUS C guide; 06's [ES2] is the international fx-570/991ES PLUS 2nd-edition guide. | Both statements are true of their own guide. The international guide's x²+x+3/4=0 → −1/2 ± (√2/2)i example is now cited in 03 §6.2 as V\* for the sibling; the ZA itself is still UNKNOWN (INFERRED same). Both files now explain the tags. |
| **C11** | **Statistics-editor capacities 40/40/20/26** were called "odd" / "looks inverted" in 03 and 06. | Neither (both kept the printed values). | ZA PDF text and the C115 guide print the same; the international ES PLUS 2nd edition prints 80/40/40/26. The ZA halves only the single-variable layouts. Keep as printed; confirm on the emulator. |
| **C12** | **CLR confirmation text.** 02 guessed `Reset Setup?`. | **02.** | [GYX] holds `Clear Setup?`, `Clear Memory?`, `Reset All?`; its `Reset All?` / `Reset All` / `Press [AC] key` strings match the SA captures exactly. |
| **C13** | **ROM ID evidence.** 01 cited a photo that shows *two* calculators. | Not wrong, ambiguous. | The ZA PLUS II is the right-hand unit (CY-849 VerB 06B3); the left is the fx-115ES PLUS 2nd edition (CY-847 VerA 3C5D), which matches Calcupedia. |
| **C14** | **π-form mechanism.** 07: numeric recognition of any decimal result with \|x\| < 10⁶. 02 (from the 2005 fx-570ES guide): fractional π only for inverse-trig results and "values normally expressed in radians". | **Unresolved.** Both readings fit the ZA guide's two examples (π÷6 in Deg, ∫ up to π), because both expressions contain π. | Flagged in 02 §8.7 and 07 §4.3; test **T16** distinguishes them. |
| **C15** | **Setup byte for Norm 2.** 07 (label file) says 4; the same repo's `config.asm` says 1. | Unresolved; irrelevant unless a ROM is emulated. | Noted in 07 §2. |
| **C16** | **Function count** 452 / 417 / 552 / 403 / 433. | No file was wrong (06 already judged them). | 417 is the fx-991ES PLUS-2 (VERIFIED, [CAT]); 552 is the fx-991EX. 452 stays SECONDARY marketing. |

No other cross-file disagreement was found. In particular the files agree on: the mode list and
numbering, SETUP pages and defaults, √-form coefficient ranges (1≤a<100, 1<b<1000, 1≤c<100,
0≤d<100, 0≤e<1000, 1≤f<100), the π-form range (\|x\|<10⁶), internal π = 3,14159265358980 and
e = 2,71828182845904, 15 internal / 10 displayed digits, the 13-level priority table, every input
range, BASE-N widths, TABLE row limits, DIST defaults, the errata (237,15 K, σ < 0, the `[+]` that
should be `[=]`, the d/h/b/o order), the key legends and their colours.

## 4. Gaps filled by the cross-check (UNKNOWN → known)

| Fact | Now | Where |
|---|---|---|
| Small font 5×6 in 6×8 cell; large font 5×9 on 6-dot advance; row/column placement of input, result, fraction, superscript, subscript, minus, comma | SECONDARY ([SA-img]) | 01 §3.6 |
| Contrast: 26 levels (0x04–0x1D) | SECONDARY | 01 §3.1, 02 §5.2, 07 §2 |
| Status-icon bits for S, A, STO, RCL, ▼, ▲, Disp | SECONDARY | 01 §3.2 |
| Every error message's text (`Can't Solve`, `Time Out`, `Insufficient MEM` …) | SECONDARY | 02 §11 |
| `Sci 0~9?`; SETUP sub-prompts `Result Format?`, `Complex Result?`, `Frequency?`; CONTRAST screen strings | SECONDARY | 02 §5.2 |
| CLR `Clear Setup?` / `Clear Memory?` | SECONDARY | 02 §5.3 |
| hyp menu (all six, 3×2 layout); DRG▶ item 1 = ° | SECONDARY | 02 §10.4–10.5, 06 §5.11–5.12 |
| `Continue:[=]`, `Solve for ` | SECONDARY | 02 §10.15 |
| Keys that update Ans (=, SHIFT =, M+, M−, RCL, STO) | V\* (international sibling guide) | 02 §9 |
| CMPLX, BASE (both pages), STAT Sum/Var/Distr/Reg/Edit menu layouts | SECONDARY | 03 §3–4 |
| 1-VAR STAT menu and MinMax with Q1/med/Q3 on a ZA screen; Q1 = 2198,5 example | VERIFIED-capture | 03 §4.2, 05 EX-SA-STAT-01 |
| Matrix dimension menu incl. page 2 (1×3, 1×2, 1×1 exist); `MatA(m×n) m×n?`; vector `1:3 2:2`, `VctA(m) m?` | SECONDARY | 03 §7, §9; 06 §6.7 |
| EQN solution-screen layout (label top-left, value bottom-right, subscripted `X₁=`) | VERIFIED-capture | 03 §6.2 |
| EQN complex roots shown as `-1/2+√2/2 i` on the international sibling | V\* | 03 §6.2 |
| μe, μn, μμ stored negative; constants stored at CODATA's published digits; ε₀, μ₀, Z₀ digits | SECONDARY/INFERRED | 03 §14, 06 §5.27 |
| km/h factor exact; acre/hp candidate values; directions of every pair | VERIFIED-capture (km/h) / SECONDARY | 03 §15, 06 §5.28 |
| CONV index above 40 → Syntax ERROR | SECONDARY | 02 §10.16, 06 §13 |
| Five new SA test vectors | VERIFIED-capture | 05 |

---

## 5. Remaining UNKNOWNs, ranked by how much they block a faithful re-implementation

Almost all of these are settled by one session on Casio's official fx-991ZA PLUS II Emulator
(Windows, 90-day trial) running the black-box tests T1–T18 in 07 §6 and recording each screen as a
golden vector. That session is the single highest-value next step.

### Tier 1 — affects the last digit or the form of a large share of all results

| # | Unknown | Why it blocks | Settle with |
|---|---|---|---|
| 1 | Arithmetic core: guard digits and rounding when storing to 15 digits; underflow below 1×10⁻⁹⁹ (0 or Math ERROR) | Every chained calculation's 10th displayed digit | 07 T3, T4 |
| 2 | Display rounding: tie rule (half-up vs half-even) for Norm/Fix/Sci/Rnd; whether the Norm exponential threshold is tested before or after rounding | Every displayed number at the edge cases | 07 T5, T6; 02 §15 #3 |
| 3 | Decimal→fraction algorithm and fraction digit limits in MathO (improper fractions especially) | Whether a MathO result shows as a fraction or a decimal | 07 T7; 02 §15 #11 |
| 4 | π-form: eligibility (C14) and tolerance / maximum denominator | Whether results show as `k/n π` | 07 T9, T16 |
| 5 | √-form closure: when surd arithmetic falls back to decimal; which inputs give exact trig (angle-keyed or value-keyed; Rad/Gra; 18°) | Whether results show as surds | 07 T8, T10; 02 §8.6 |
| 6 | Transcendental functions' last-digit behaviour; normal-CDF coefficients (ROM's non-textbook a₁, a₃) | 10th-digit agreement for sin, ln, xʸ…; STAT P/Q/R in the 7th decimal | 07 T11, T13 |

### Tier 2 — blocks a specific feature behaving identically

| # | Unknown | Settle with |
|---|---|---|
| 7 | EQN: complex-root display on the ZA (INFERRED as the international sibling); "no solution" / "infinite solutions" message text; cubic root order; vertex screens after a double or complex root | Emulator: x²+2x+3=0, a singular 2×2 system |
| 8 | DIST: prompt text and result labels for Normal CD, Inverse Normal, Binomial CD, Poisson PD/CD; list tags other than `Bpd`; allowed ranges of N, x, μ; σ ≤ 0 handling; the algorithms (accuracy is only "5 significant digits") | Emulator walk through all 7 types |
| 9 | ∫ error estimate and tol semantics, Time Out budget; d/dx step schedule; SOLVE iteration cap and convergence test | 07 T12, T15 |
| 10 | STAT: quartile rule for odd n; regression formulas for e^X, A•B^X, A•X^B, 1/X, quadratic (unchecked by any example); x̂₁/x̂₂ order; Q(t) for t < 0; the editor's SHIFT 1 menu | Emulator: 1,2,3,4,5,6,7 for Q1 (2 or 2,5) |
| 11 | CONV acre / hp / lb factors; whether MathO conversions return fractions (`5 cm▶in` → `250/127`?); CONST stored digits for every 2014 value; Vm value | 07 T17; `SHIFT 7 nn =` in Sci 0 |
| 12 | ÷R "very large" threshold; fraction operands for ÷R | 10- vs 11-digit dividends; `7/2 ÷R 1` |
| 13 | Byte cost of each token (99-byte buffer, ■ cursor at ≤10 left); parenthesis nesting and stack limits; history capacity (~200 bytes) in entries | Fill the buffer on the emulator |
| 14 | Which functions each non-COMP mode accepts (Σ, Pol, nPr… in CMPLX/STAT/MATRIX/VECTOR); CMPLX with √(−4), trig of complex | Try each in each mode |
| 15 | BASE-N out-of-range behaviour (e.g. DEC 70000 → BIN), logic-operator precedence in practice | Emulator |
| 16 | Whether Pol/Rec and errors update Ans/PreAns; which operators auto-insert `Ans` besides ÷ | Emulator |

### Tier 3 — looks ("exactly the same" visually)

| # | Unknown | Settle with |
|---|---|---|
| 17 | Every glyph bitmap of both fonts; pixel geometry of √ (overbar, hook), ∛, ⁿ√, ∫, Σ, d/dx, log▪▯, mixed fraction, Abs bars, ▷/▶ markers, the cursor shapes. Metrics are known (01 §3.6); the shapes are visible in the SA captures and emulator, which raises the **licensing question** of copying Casio's bitmaps (08 §10 #5) | Emulator 1:1 screenshots, or draw own glyphs on the measured grid |
| 18 | Status-icon segment shapes and exact x positions on the 2nd edition; bits for D, R, G, FIX, SCI, Math | Straight-on self-test photo; emulator skin |
| 19 | Calibrated colours (LCD background, ink, key and legend inks); contrast default; key-cap typeface | Calibrated photo; count presses from CLR All |
| 20 | 2nd-edition-only screens: SETUP TABLE and APO sub-prompts; `MODE 0` ID/QR layout; DIST screens; `All Real Numbers` vs the sibling's `All` | Emulator |
| 21 | Behaviours documented nowhere: key auto-repeat on cursor keys and DEL; cursor blink period; what `ON` does while already on; busy-indicator timing; how APO counts | Emulator / real unit with a stopwatch |

### Tier 4 — decisions, not facts (already listed in 08 §10)

Branding (CASIO wordmark, model name, Casio's legend artwork and LCD font), window placement in
Inkstone, palm policy, inserted-result format (comma vs point, natural forms vs decimal, PDF-safe
alphabet), and scope staging across sessions.

---

## 5a. Corrections found while building (2026-10-02/03)

The build re-measured the SA captures and read the ES-family guide's own tables. Where this list
disagrees with a file above, this list wins; the code cites it.

| Topic | The research said | What the build established | Evidence |
|---|---|---|---|
| Column origin (01 §3.6, C5) | input from col 0, result's last glyph on cols 90–94 | Every cell is a blank leading column plus 5 columns of ink: text starts on **col 1**, a result's last ink is on **col 95**, a fraction bar at line start is on col 1. Relative metrics (6-dot advance, 15-row fraction, bar ±1, 2-row superscript drop) all hold. | Ink spans exactly 96 dots on every SA capture, from the MODE cell (col 0) to the last result digit |
| MODE-menu font (02 §4, C2) | small 5×6 font | **5×7 capitals** with a **7×7 inverted number cell**; label from col 8 / 56 | SA slides 2 and 15 at 4× |
| Menu ▼/▲ (01 §3.3) | part of the dot matrix | the **status icons**; the matrix's top-right stays empty | same captures |
| Superscript (01 §3.6) | exponent ink one column after its cell | the exponent sits in the cell straight after its base, and a blank column follows it only when something else follows (`3A²+4B`: 2 on cols 13–17) | slides 12, 14 |
| Firmware hyphen | — | the 5-dot bar in all three faces (`1-VAR`, `L-R=`, `X-Value Minimum=`); a value's sign stays the short 3-dot one | slides 13, 16, 17 |
| CONST/CONV `[__]` field (03 §14–15) | — | rows 21–29, one above the result line | slide 19 |
| Byte costs (02 §7.2) | unknown | the ES-family Math-format table: fraction 9, mixed 13, log▪▯ 6, 10^▪/e^▪ 4, √ 4, ∛ 9, x²/x³ 4, x⁻¹ 5, x^▪ 4, ⁿ√ 9, ∫ 8, d/dx 6, Σ 8, Abs 4, others 1 | [S570] fx-570ES guide p.E-15 |
| Stack sizes | unknown | 10 numeric / 24 command levels, counted as the [S570] illustration shows (5 numeric and 7 command for `2×((3+4×(5+4)÷3)÷5)+8`); CMPLX values take two numeric levels; matrices 3, vectors 5 | [S570] pp.E-66/67 |
| Research 08 §4.5 window clamp | 78 dp (`DockSafeTop`) | 120 dp — dock row 2 (66–112 dp) is on screen in the default pen mode | stage-1 UI review |
| Research 08 §4.4 "consuming in the body is harmless to child clickables" | harmless | false for **move** events: a child sees the parent's consumption on the Final pass and cancels; the keypad tracks presses itself | stage-1 UI review |

## 6. Notes for whoever implements next

- Start from 02 + 03 for behaviour, 06 as the key/menu catalogue, 01 for the face and LCD metrics,
  07 for the number model and test plan, 08 for where code goes. Use 04 and 05 as the first test
  tables; vectors marked [not printed], C or INFERRED are **not** oracles.
- Where a value now comes from [GYX] (sibling firmware) it is marked SECONDARY. The ZA's own screens
  have agreed with [GYX] every time they could be compared (CLR, Math ERROR, Fix/Norm prompts, oz,
  km/h), but 2nd-edition-only features are not in it.
- Do not import anything from ClassWiz (EX/CW) knowledge: menus, OPTN, catalog, ReP/ImP, spreadsheet,
  unit vectors, 4×4 matrices, quartic equations, linear inequalities are all absent (06 §11).
- Repo hygiene: file 02 reported stray downloads in the repo root from a shell hook; it moved its own
  and left others (`chipset.cpp`, `cp_index.html`, `decomp_tree.json`, `fxesplus_tree.json`,
  `wd_all.html`, `img/key_57408.png`, a file named `'`). The cross-check did not touch them. The first
  five were written 2026-10-02 between 18:56 and 19:12 (two are empty) and fuller copies sit in the
  scratchpad's `r07/`, so they look like shell-hook debris from file 07's session; the `'` file is
  dated 2026-09-25 and predates this research. Deleting them is the user's call.
