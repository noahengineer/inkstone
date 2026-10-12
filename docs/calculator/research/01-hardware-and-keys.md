# 01 — fx-991ZA PLUS II: the physical calculator and its keyboard

Research for redrawing the Casio **fx-991ZA PLUS II** in Jetpack Compose: its body, LCD, all 50 keys
and every printed legend, with colours and type.
Written 2026-10-02. Everything below is written in my own words. Where a fact comes from the
manual, it is a value, a key name or a sequence, not copied prose.

**Scope.** This file covers looks and the keyboard. How the calculator *computes* is in
`02-manual-core-and-display.md` and `03-manual-modes.md`. ROM, emulator and licence questions are in
`07-emulation-and-internals.md`. Where this file and those disagree, see §11.

**We do not reproduce the CASIO logo or the "NATURAL-V.P.A.M." wordmark** (CLAUDE.md, Artifact and
trademark rules). This file only records where they sit, so the space can be left blank or used for
something neutral.

---

## 0. Confidence tags and sources

| Tag | Meaning |
|---|---|
| **VERIFIED** | Read or measured directly in an official Casio source: the manual PDF, Casio's product render, Casio's comparison chart, or Casio packaging. |
| **SECONDARY** | From a retailer photo, a community review or reverse-engineering project, or an official distributor's teaching notes. |
| **INFERRED** | My own reasoning or a measurement whose method has stated error. |
| **UNKNOWN** | Not established. Each one says what would settle it. |

| Id | Source | Kind |
|---|---|---|
| **[G]** | CASIO, *User's Guide fx-991ZA PLUS II* (English, 96 pp., © 2020). <https://www.casio.com/content/dam/casio/global/support/manuals/calculators/pdf/004-en/f/fx-991ZA_PLUSII_EN.pdf> | Official |
| **[R]** | CASIO product render `main.png`, 410 × 860 px, served by Casio South Africa's education site. <https://edu.casio.com/assets/images/products/standard/fx991za_2/main.png> (product page <https://edu.casio.com/za/products/ntd/fx991za_2> returned HTTP 403 to scripts; the image itself downloads fine) | Official |
| **[CMP]** | CASIO South Africa comparison chart. <https://edu.casio.com/za/products/ntd/comparison.html> | Official |
| **[PKG]** | Back of the retail blister card, photographed by a retailer. Image: <https://cdn.shopify.com/s/files/1/0947/1387/2685/files/casio-fx-991za-plus-ii-advanced-scientific-calculator-white-570.jpg> | Official artwork, retailer photo |
| **[A]** | Amazon.co.za listing B0CJ8ZSSJX. Main front photo 2560 × 2560: <https://m.media-amazon.com/images/I/71dT8tDnANL.jpg>. Also used: `71lMfW1hhjL` (angled), `51UDuHXOGhL` (hard case), `51PsiDgxOmL` (side). | Retailer photo |
| **[CR]** | zwolken, *fx-115ES PLUS 2nd & fx-991ZA PLUS II review* (Chinese), with the author's own 3000–4000 px photos of a real unit, LCD on. <https://zwolken.github.io/Calc_Review/docs/02_991ZAII/> | Community. The page asks that its photos not be redistributed, so they are only *measured* here. |
| **[JR]** | James Ralph (Pty) Ltd, Casio's authorised South African distributor: product page <https://www.casio.jamesralphedu.co.za/products/p/casio-fx-991za-plus-ii-sa-education-advanced-scientific-calculator> and teacher notes <https://edumaterial.casio-intl.com/resource/contents/1368> | Official distributor (treated as SECONDARY) |
| **[EMU]** | LBPHacker/CasioEmu, `models/fx570esplus/model.lua` (geometry of the **fx-570ES PLUS** emulator skin). <https://raw.githubusercontent.com/LBPHacker/CasioEmu/master/models/fx570esplus/model.lua> | Community, a sibling model |
| **[WIKI]** | casiocalc.wikidot.com, "Display" page (ES PLUS LCD memory map). <http://casiocalc.wikidot.com/display> | Community, a sibling model |
| **[ZEPH]** | Wenting Zhang, "#LetsDriveLCD [12] – CASIO fx-ES clone" (ES-family LCD: 96 × 31 matrix and 18 status icons). <https://zephray.me/post/82es_lcd_drive/> | Community, a sibling model |
| **[YT]** | James Ralph tutorial videos, which use Casio's official emulator (thumbnails only). For example <https://i.ytimg.com/vi/aPh-TiwAnOY/hqdefault.jpg> | Official distributor |

---

## 1. What the fx-991ZA PLUS II is

| Fact | Value | Confidence and source |
|---|---|---|
| Market | South Africa. "Developed in collaboration with SA educators for the South African Curriculum". Ships with Afrikaans, English and isiZulu guides. | VERIFIED: blister-card front in [A] and [PKG] |
| Casio's own name for it | "fx-991ZA PLUS 2nd" | VERIFIED [CMP] |
| Family | fx-ES PLUS Series **2nd edition** (released from 2019). The review lists every model in that series; the fx-991ZA PLUS II and fx-82ZA PLUS II are the only ones whose functions changed. | SECONDARY [CR] |
| Physical twin | **fx-115ES PLUS 2nd edition** (US). Same white body, same mouldings and key shapes, same colour scheme. Only some printed legends differ (§5.5). [CR] says these two are the only white flagship models in the series; the rest are black. | Photographed side by side: SECONDARY [CR] |
| Nearest in functions | ES PLUS 2nd-edition firmware with 11 modes: COMP, CMPLX, STAT, BASE-N, EQN, MATRIX, TABLE, VECTOR, DIST, INEQ, RATIO. | Mode list VERIFIED [G p.10]. "Based on" is SECONDARY [CR] |
| Not the same as | **fx-991ZA PLUS** (1st gen). That model used a decimal **point**, defaulted to Norm 1, and had no GCD, LCM or ÷R. | SECONDARY [CR], [JR] |
| Not the same as | **fx-991EX / fx-991CW ClassWiz.** Those have different displays and keyboards. The ZA PLUS II has the ES-family **31 × 96** dot display. | 31 × 96 VERIFIED [CMP]. The ClassWiz difference is INFERRED |
| Decimal separator | **Comma**, on the key **and** on the display. The argument separator becomes **semicolon** (`Pol(√2;√2)`, `r=2;θ=45`). The SETUP menu has no dot/comma option. | VERIFIED: [PKG] "Decimal Point: Comma"; [G p.7] screenshots; [G p.11–14] SETUP; photos |
| Firmware ID (self-test) | `CY-849 VerB`, `SUM 06B3` | SECONDARY: [CR] photo `15_test.jpg` — the right-hand unit in that photo, the ZA PLUS II; the left-hand fx-115ES PLUS 2nd edition shows `CY-847 VerA`, `SUM 3C5D`. Cross-check corroboration: Calcupedia's self-test list gives the same IDs, and Casio's own ZA HTML guide names its ZA-only images `CY849_*.png`. |
| Body shared with | fx-991ES PLUS / fx-350ES PLUS / fx-85ES PLUS **2nd edition**: all "11.1 × 77 × 161.5 mm, 95 g" (the fx-82/95/570 2nd editions are 13.8 mm and 105 g) | VERIFIED: Casio fx-ES PLUS 2nd-edition catalogue <https://www.casio.com/content/dam/casio/global/calculator/scientific-calculators/catalog/fx-ES_PLUS_2.pdf> (cross-check) |
| Function count | 452 | SECONDARY [JR], [PKG] front ("452 Functions") |
| EAN | 4549526609855. Made in Thailand. | VERIFIED [PKG] |

---

## 2. The body

### 2.1 Size, weight, power

| Item | Value | Confidence |
|---|---|---|
| Dimensions | **11.1 (H) × 77 (W) × 161.5 (D) mm** (the manual writes 11,1 and 161,5). H is thickness and D is length. | VERIFIED [G p.91] |
| Weight | 95 g with battery | VERIFIED [G p.91] |
| Aspect (length ÷ width) | 161.5 / 77 = **2.097**. Casio's render [R] measures 859 / 410 = 2.095, so [R] is drawn **to scale**. Every mm figure below is measured from [R] at **5.325 px/mm** (410 px = 77 mm). | INFERRED, error ≈ ±0.3 mm |
| Power | Solar cell + one LR44 (GPA76) button cell. "Two Way Power". | VERIFIED [G p.91], [PKG] |
| Battery access | No battery hatch. The back cover comes off after removing screws: [G p.86] says "remove the screws and the cover". There are six screws: two near the top corners, two mid-body, two near the bottom corners. | Hatch-less VERIFIED [G p.86]. Screw count SECONDARY ([CR] `08_back.jpg`) |
| Corner radius (plan view) | ≈ **8 mm** on all four corners. Possibly a superellipse rather than a true arc. | INFERRED from [R]'s alpha mask: the top row is inset 41 px, row 10 is inset 16 px, row 30 is inset 3 px |

### 2.2 Layout of the face (origin at top-left of the body, mm, from [R])

| Region | x from → to | y from → to | Size (W × H) | Notes |
|---|---|---|---|---|
| CASIO logo (**do not reproduce**) | 9.8 → 26.5 | 8.3 → 11.1 | 16.7 × 2.8 | Mid-grey wordmark, left-aligned in the top band |
| Model name "fx-991ZA PLUS II" | 7.9 → 29.1 | 14.3 → 16.2 | 21.2 × 1.9 | Dark grey, bold sans. "fx-" is lighter than "991ZA PLUS". "II" is a Roman numeral. |
| "NATURAL-V.P.A.M." (**do not reproduce**) | 6.6 → 30.4 | 17.7 → 19.2 | 23.8 × 1.5 | Dark grey stylised italic |
| Solar panel | 34.2 → 66.7 | 6.4 → 18.0 | **32.5 × 11.7** | Near-black, top right, in a shallow light-grey recess. The 115ES twin shows four cell divisions; on the ZA it looks plain black. |
| LCD bezel (raised frame) | 5.8 → 70.8 | 22.9 → 52.0 | 65.0 × 29.1 | Light-grey moulded frame around the glass |
| LCD glass (visible window) | 7.7 → 69.1 | 25.2 → 49.8 | **61.4 × 24.6** | Retailer photo [A] gives 62.6 mm wide, which agrees within ±1 mm |
| Top key cluster (centre line) | — | ≈ 62.7 | — | SHIFT, ALPHA, cursor pad, MODE, ON |
| Function keys (4 rows × 6 columns, with 2 gaps in row 1) | 6.8 → 70.2 | 72.9 → 106.6 | — | White keys |
| Number pad (4 rows × 5 columns) | 6.8 → 70.2 | 111.4 → 153.8 | — | Grey keys, plus lime DEL and AC |
| Bottom margin | — | 153.8 → 161.5 | ≈ 7.7 | Plain face plate |

Side margins of the keyboard are 6.8 mm on each side, so the keyboard is centred (INFERRED).

### 2.3 Materials, finish and textured regions

- **Face plate:** smooth, matte **off-white** plastic over the whole front. There is no separate keyboard panel or contrasting band. VERIFIED from [R] and photos [A], [CR].
- **Honeycomb plate behind the cursor keys:** the only patterned area on the front. It is a translucent, frosted-white plate moulded with a **hexagonal (honeycomb) cell** pattern. Its outline is roughly a rounded rectangle, ≈ **19 mm wide × 15 mm tall**, centred on the pad. You only see it in the four diagonal gaps between the arrow keys. Cells are ≈ 2 mm across. Pattern: VERIFIED by sight in [R], [A] and [CR]. Size: INFERRED from [A], ±1.5 mm.
- **Wells under the round key pairs:** SHIFT+ALPHA share a shallow stadium-shaped well, and so do MODE+ON. Each well is ≈ 16.7 × 6.0 mm, outlined by a faint groove. Visible in [R] and [CR] `07_front.jpg`. INFERRED dimensions.
- **Spun texture:** the four round keys and the four cursor keys have a fine **concentric-circle ("spun metal") texture** on their tops. VERIFIED by sight in [A] crops and [R].
- **Back:** plain white, with six screw holes and a moulded label area near the top (CASIO, model, CE / UKCA / EAC marks, MADE IN THAILAND). No battery door. SECONDARY [CR] `08_back.jpg`.

### 2.4 Hard case (slide cover)

- White rigid slide case. It slides **off downwards**, then clips onto the **back** of the calculator in use. VERIFIED [G p.5].
- Outside: plain white, with an embossed "CASIO" roughly a quarter of the way down and four small round bumps near the corners. SECONDARY [A] `51UDuHXOGhL`, [CR] `08_back.jpg`.
- Inside: a printed **quick-reference sticker**. Its sections are listed below. Seen in [CR] `07_front.jpg`. The South African teacher notes [JR] confirm the unit conversions are printed inside the lid. SECONDARY.
  - Restoring mode defaults (COMP, Deg, Norm2, MthIO-MathO): SHIFT 9 (CLR) 1 (Setup) = (Yes)
  - Initialising the calculator: SHIFT 9 (CLR) 3 (All) = (Yes)
  - MthIO-MathO: SHIFT MODE (SETUP) 1 (MthIO) 1 (MathO)
  - MthIO-LineO: SHIFT MODE (SETUP) 1 (MthIO) 2 (LineO)
  - LineIO: SHIFT MODE (SETUP) 2 (LineIO)
  - Contrast setting: SHIFT MODE (SETUP) ▼ 7 (◀CONT▶)
  - Improper fraction: the fraction key. Mixed fraction: SHIFT then the fraction key.
  - Scientific constants: SHIFT 7 (CONST) followed by a 2-digit code, 01–40
  - Unit conversions: SHIFT 8 (CONV) followed by a 2-digit code, 01–40
- **Constants table on the sticker** (code → symbol): 01 mp, 02 mn, 03 me, 04 mμ, 05 a0, 06 h, 07 μN, 08 μB, 09 ħ, 10 α, 11 re, 12 λc, 13 γp, 14 λc,p, 15 λc,n, 16 R∞, 17 u, 18 μp, 19 μe, 20 μn, 21 μμ, 22 F, 23 e, 24 NA, 25 k, 26 Vm, 27 R, 28 C0, 29 C1, 30 C2, 31 σ, 32 ε0, 33 μ0, 34 φ0, 35 g, 36 G0, 37 Z0, 38 t, 39 G, 40 atm. SECONDARY (read from the photo).
- **Conversions table on the sticker:** 01 in→cm, 02 cm→in, 03 ft→m, 04 m→ft, 05 yd→m, 06 m→yd, 07 mile→km, 08 km→mile, 09 n mile→m, 10 m→n mile, 11 acre→m², 12 m²→acre, 13 gal(US)→ℓ, 14 ℓ→gal(US), 15 gal(UK)→ℓ, 16 ℓ→gal(UK), 17 pc→km, 18 km→pc, 19 km/h→m/s, 20 m/s→km/h, 21 oz→g, 22 g→oz, 23 lb→kg, 24 kg→lb, 25 atm→Pa, 26 Pa→atm, 27 mmHg→Pa, 28 Pa→mmHg, 29 hp→kW, 30 kW→hp, 31 kgf/cm²→Pa, 32 Pa→kgf/cm², 33 kgf·m→J, 34 J→kgf·m, 35 lbf/in²→kPa, 36 kPa→lbf/in², 37 °F→°C, 38 °C→°F, 39 J→cal, 40 cal→J. SECONDARY (read from the photo).
- Case dimensions: **UNKNOWN**. It is slightly larger than the body. A measured unit would settle it.

---

## 3. The LCD

### 3.1 Resolution and structure

| Fact | Value | Confidence |
|---|---|---|
| Dot matrix | **96 columns × 31 rows** ("31 x 96 Full Dot Display") | VERIFIED [CMP]. Also SECONDARY: manuals.ca, and [ZEPH] for the ES family. |
| Status bar | A **separate row of fixed segment icons above the matrix**. These are shaped segments, not dots. The controller addresses it as a 32nd "row" (row 0x80) whose bits drive the 18 icons and no pixels. | Icon set VERIFIED [G p.8–9]. Electrical layout SECONDARY [WIKI], [ZEPH] (ES PLUS 1st gen); assumed the same on 2nd edition (INFERRED) |
| Lines of text | Natural/Math display: input on top, result at bottom right. Menus and self-test: 4 lines of small font. LineIO: up to 3 lines of large font (see §3.6). | VERIFIED from screenshots [G] and photos [CR] |
| Dot pitch (physical) | ≈ **0.59–0.61 mm**, so the matrix is ≈ **57–59 × 18.3–18.9 mm** | INFERRED. Real-unit photo [CR] `21_setup.jpg`: 10 px per dot against 16.3 px/mm body scale, giving 0.61 mm. Render [R]: 3.13 px per dot, giving 0.59 mm. |
| Position in the glass (in dot pitches *p*) | The glass is ≈ 101 p wide × 41 p tall. From the glass top: ≈ 2.6 p margin, then the status-icon band (≈ 3 p tall), then ≈ 1.2 p gap, then the 31-row matrix, then ≈ 3 p bottom margin. Left/right margins are ≈ 2.5 p each. | INFERRED from [CR] `21_setup.jpg`, ±1 p |
| Dot shape | Square dots with a thin unlit gap between them. The gap is visible in close-up photos. | SECONDARY [CR] close-ups |
| Contrast | Adjustable at SHIFT MODE (SETUP) ▼ 7 (◀CONT▶). ◀ = LIGHT, ▶ = DARK, AC = done. **Steps (cross-check, SECONDARY):** the ES PLUS firmware treats stored values outside **0x04–0x1D** as corrupt (07 [S13] `_need_reset`), and Calcupedia gives the same normal range, so the user range is **26 levels**. The 5-bit register [WIKI] is 0x00–0x1F. The **default** level is still **UNKNOWN** (the ES series' was 0x11; not confirmed for ES PLUS). | Key sequence VERIFIED [G p.6, p.15] |

### 3.2 Status icons, left to right

The order is **S, A, M, STO, RCL, STAT, CMPLX, MAT, VCT, D, R, G, FIX, SCI, Math, ▼, ▲, Disp** (18 icons).

- **Set and meanings:** VERIFIED from the indicator table [G p.8–9]. The table is printed in this order.
- **Left-to-right order on a real 2nd-edition LCD:** SECONDARY. The review photo [CR] `15_test.jpg` shows the twin fx-115ES PLUS 2nd edition in self-test with every icon lit. It reads `[S][A] M STO RCL STAT CMPLX MAT VCT [D][R][G] FIX SCI Math ▼▲ Disp`. Both [ZEPH] and [EMU] give the same order.
- **Inverse video:** **S, A, D, R and G** are drawn as light letters in a dark filled box. VERIFIED [G p.8]. The rest are plain dark text.
- **Look:** small, condensed, bold capitals ("Math" is mixed case), about 3 dot-pitches tall (≈ 1.8 mm). The arrows are solid triangles.

| Icon | Meaning (paraphrased) | x start (dots) | width (dots) | Wiki byte / bit |
|---|---|---|---|---|
| **S** (boxed) | SHIFT pressed | 0.0 | 3.3 | F800 / 0x10 |
| **A** (boxed) | ALPHA pressed | 3.0 | 3.7 | F800 / 0x04 |
| M | Independent memory is non-zero | 6.7 | 3.3 | F801 / 0x10 |
| STO | Waiting for a variable name to store into | 10.0 | 6.7 | F801 / 0x02 |
| RCL | Waiting for a variable name to recall | 16.3 | 6.3 | F802 / 0x40 |
| STAT | STAT mode | 23.0 | 8.0 | F803 / 0x40 |
| CMPLX | CMPLX mode | 31.0 | 10.7 | F804 / 0x80 |
| MAT | MATRIX mode | 41.7 | 6.7 | F805 / 0x40 |
| VCT | VECTOR mode | 48.0 | 6.7 | F805 / 0x01 |
| **D** (boxed) | Angle unit is degrees | 54.7 | 4.0 | F807 / 0x20 |
| **R** (boxed) | Angle unit is radians | 58.3 | 3.3 | F807 / 0x02 |
| **G** (boxed) | Angle unit is grads | 61.7 | 3.7 | F808 / 0x10 |
| FIX | Fixed decimal places in effect | 65.3 | 5.7 | F808 / 0x01 |
| SCI | Fixed significant digits in effect | 70.7 | 5.3 | F809 / 0x20 |
| Math | Natural display selected | 76.0 | 8.0 | F80A / 0x40 |
| ▼ | History or more data below | 84.0 | 3.3 | F80A / 0x08 |
| ▲ | History or more data above | 86.0 | 3.3 | F80B / 0x80 |
| Disp | Showing an intermediate result of a multi-statement | 89.3 | 6.7 | F80B / 0x10 |

Notes on the geometry columns:

- **x and width** are in units of the matrix dot pitch, measured from the matrix's left edge. They are converted from [EMU]'s 1st-gen skin, where each dot is 3 px and the matrix starts at x = 61. In that skin the icon band is 4.7 dots tall and sits directly on top of the matrix. SECONDARY for the sibling. INFERRED for this model.
- **Cross-check:** in Casio's ZA render [R], **D** sits at 0.56 and **Math** at 0.77 of the matrix width. [EMU] gives 0.57 and 0.79. That agrees within one dot. INFERRED.
- **Byte / bit** is the ES PLUS 1st-gen status-bar map from [WIKI]. Each byte covers about 8 columns, which agrees with the x positions. It only matters if you emulate a ROM. SECONDARY.
- **Caveat on the byte column:** I read the columns from the tab-separated copy of this table in the casiocalc.org forum thread (post #305 by user202729). The wiki's space-aligned copy is ambiguous by one byte for D, R, G, FIX, SCI, Math, ▼, ▲ and Disp. Re-check against an emulator before relying on those nine.
  - *Cross-check:* the GYX decompilation (07 [S13], `generals.asm`) names the bits it sets and clears: [S] F800.4, [A] F800.2, STO F801.1, RCL F802.6, ▼ F80A.3, ▲ F80B.7, Disp F80B.4. That confirms the forum reading for ▼, ▲ and Disp (and S, A, STO, RCL). D, R, G, FIX, SCI and Math remain to check.

### 3.3 Other display furniture (part of the dot matrix)

| Glyph | Where | Meaning | Confidence |
|---|---|---|---|
| ▷ (hollow triangle) | right end of the input line | The input continues to the right | VERIFIED [G p.7] |
| ▶ (solid triangle) | right end of the result line | The result continues to the right | VERIFIED [G p.7] |
| ▼ / ▲ in the **top-right of a menu** | top right of the matrix | Another menu page below / above | VERIFIED [G p.9] |
| Reverse-video menu numbers | menus | MODE-menu item numbers are light digits in dark boxes, for example "■1 COMP". | SECONDARY ([YT] emulator thumbnail; [CR] photos) |
| Cursor | input line | A blinking vertical bar. It becomes a short dash in some contexts. Details are in 02. | SECONDARY |

### 3.4 LCD colours

Every value is a photo or render estimate. They depend on lighting.

| Condition | Source | Background | Lit dots / icons |
|---|---|---|---|
| Real unit, powered, indoor light | [CR] `21_setup.jpg` (face plate in the same photo: #D3D1D4) | Median #708072. 75th percentile #889889. 90th percentile #9AAA9B. A warm grey-green, lighter towards the lower right. | **Dark navy blue**: darkest #092772, typical #15377A |
| Real unit, powered | [CR] `15_test.jpg` | Median #6F7877 | #0F2359 to #264575 |
| Casio's render | [R] | **#CFE3E4** (pale cyan-grey) | **#000000** (pure black) |
| Casio's official PC emulator | [YT] thumbnail, a small JPEG, low confidence | ≈ #D8E6D8 (pale green) | ≈ black |
| Community emulator choice | [EMU] `ink_colour = {30,52,90}` | (skin image, not retrieved) | **#1E345A** |
| Real unit, **off** | [A] main photo | #334539 (dark green-grey reflection) | — |

How to read these numbers:

- On a real unit the LCD background is about **0.6–0.75 times as bright as the white body** in the same photo. The ink is navy, not black.
- Casio's marketing art brightens the LCD to near-white and uses black ink.
- The palette in §6 gives both a "real" and a "render-like" choice.

### 3.5 Contrast and power-on look

- Unlit dots are invisible. Photos show at most a faint ghost of the status icons. SECONDARY [CR].
- Auto Power Off is set in SETUP (APO, 10 or 60 min, default 10). VERIFIED [G p.14–15]. Turning off (SHIFT AC) blanks the whole glass.

### 3.6 LCD fonts (what is known)

| Font | Glyph | Cell / advance | Lines | Where it is used | Confidence |
|---|---|---|---|---|---|
| **Small** | **5 × 6 dots** *(corrected by the cross-check; was 5 × 7)* | 6 × 8 dots, so 16 characters per line | 4 lines; glyphs on matrix rows **1–6, 9–14, 17–22, 25–30** (row 0 and the last row of each 8-row band blank) | MODE and SETUP menus, every numbered sub-menu, prompts (`Fix 0~9?`), self-test, "Dec/Bin/Hex/Oct" labels, fraction numerators/denominators, exponents and subscripts in Math display | SECONDARY: measured on the Casio-hosted [JR] handout's emulator captures, which are exact 2× nearest-neighbour copies of the LCD (a 2×2-block fit with zero mismatches). Lower-case letters with tails (`p`, `y`) are squeezed into the same 6 rows; there are no descenders. The real-unit photo [CR] `21_setup.jpg` agrees when the dots are counted at 4× zoom (6 rows per glyph, 8-row pitch); the earlier "70 px = 7 dots" reading included blur. |
| **Large** | **5 × 9 dots** for digits and capitals *(was "9 or 10", UNKNOWN)* | **6-dot advance** (5 glyph + 1 space), so 16 characters fill a line | Input line glyphs on rows **1–9**; result glyphs on rows **22–30** | Main input and result lines, EQN/SOLVE labels (`X=`, `X-Value Minimum=`) | Advance VERIFIED: [G p.7] shows `Pol(1,414213562▷` filling the line (16 cells), and [G p.59] shows a 16-digit binary across the full width. Height and rows SECONDARY (same [JR] captures: `120km/h▶m/s` / `33,33333333`, `0,67`, `116`, `X₂=` / `-6`). |
| Math-display extras | Fractions, roots and exponents are drawn as stacked layouts using the small font | — | — | Natural display | See the measurements below. Other templates (√ overbar, ∫, Σ, d/dx, log▪▯) are still UNKNOWN in pixels. |

**Pixel layout measured on the [JR] emulator captures (cross-check; SECONDARY; matrix coordinates, row 0 at top, col 0 at left):**

- **Text grid.** Input text starts at col 0 (first glyph on cols 0–4). Results are right-aligned so the
  last glyph sits on cols **90–94**; col 95 is that cell's spacing column. Menus put the second item
  of a row at cell 8 (col 48): `1:Type  2:Data`.
- **Result line.** Large glyphs on rows 22–30 (the bottom row of the matrix is the baseline).
- **Input line, flat.** Large glyphs on rows 1–9; row 0 blank.
- **Superscript** (`3A²+4B`): the exponent is a small-font glyph on rows 1–6 in its own 6-col cell,
  and the base-line glyphs of the whole input line drop to rows 3–11. So the exponent's bottom row is
  the base glyph's 4th row.
- **Fraction** (`2/3`, `1/800`): numerator in small font on rows 1–6, blank row 7, a 1-dot bar on row 8,
  blank row 9, denominator rows 10–15 (15 rows tall). The bar is the wider operand's width plus one dot
  each side; both operands are centred on it. A fraction at the start of the line puts its bar at col 0.
- **Subscript** in `X₁=`: a small-font digit whose bottom row is the large glyphs' bottom row.
- **Minus sign** in a result (`-6`): a 3-dot bar on the 5th of the 9 rows, in box cols 1–3.
- **Decimal comma** (large): a 2×2 block on glyph rows 6–7, then one dot at row 8 (col 2) and one at
  row 9 (col 1); it stays within the 9 rows and takes a full 6-col cell.
- How to reproduce: the PNGs embedded in the [JR] PDF (`edumaterial.casio-intl.com/resource/contents/1368`)
  are 208×72 (2×) or 388×144 (4×). The scratchpad script `calc-research/x00/grid2.py` recovers the dot
  grid from any of them. Whether to copy Casio's glyph *bitmaps* from those captures is a licensing
  decision for the user (08 §10 item 5); this file records only metrics.

To settle the exact glyph bitmaps (every character, both sizes, the Math templates), take a
pixel-exact screenshot from Casio's fx-ES PLUS Emulator for this model, or read the font out of a
2nd-edition ROM. Both are covered in 07, and both have licence problems. Until then, draw glyphs on a
96 × 31 grid with the cell sizes above.

---

## 4. Keyboard geometry

### 4.1 Key count

There are **50 physical keys**: 4 round keys, 4 separate cursor keys, 4 keys in function row 1, 3 × 6 white function keys, and 5 × 4 number-pad keys.

- **Official count: none found.** Casio does not publish a key count for ES PLUS models.
- **Cross-checks:**
  - Detecting key shapes in Casio's render [R] by connected components finds exactly **50** keys. VERIFIED by measurement.
  - [EMU]'s fx-570ES PLUS key map has **50** entries, including ON. SECONDARY.
  - Calcupedia's ES PLUS 2nd-edition self-test has a "Key1" test of the first 29 keys excluding ON and a "Key2" test of the last 20: 29 + 20 + ON = **50**. SECONDARY (cross-check). File 06's "47 keys" counts the cursor pad as one key; it is the same keyboard.
  - The manual describes the cursor as one key "marked with four arrows" [G p.7]. Its own drawing, and every photo, show **four separate keys**, so draw four.

### 4.2 Grid (mm from the body's top-left; key sizes are outer key-top sizes, ±0.4 mm)

| Group | Column centres x (mm) | Row centres y (mm) | Pitch | Key size |
|---|---|---|---|---|
| Round keys | SHIFT 11.1, ALPHA 21.2, MODE 55.7, ON 65.8 | 62.7 | — | **Ø 6.6** |
| Cursor pad | UP (38.4, 62.8), LEFT (30.7, 67.9), RIGHT (46.1, 67.9), DOWN (38.5, 73.0) | — | — | UP and DOWN **6.3 × 8.5**; LEFT and RIGHT **9.0 × 6.0** |
| Function row 1 | 11.4, 22.2, (gap), (gap), 54.7, 65.6 | 75.7 | 10.84 horizontal | **9.4 × 5.8** |
| Function rows 2–4 | 11.4, 22.3, 33.0, 43.9, 54.7, 65.6 | 85.1, 94.3, 103.7 | 10.84 horizontal, ≈ 9.33 vertical (9.4 from row 1 to row 2) | **9.4 × 5.8** |
| Number pad | 12.2, 25.5, 38.6, 51.6, 64.7 | 115.6, 126.9, 138.2, 149.7 | ≈ 13.1 horizontal, ≈ 11.4 vertical | **≈ 11.1 × 8.2** (DEL and AC measured 11.5 × 8.5, within error) |

All of this is INFERRED from [R]. It was measured by detecting each key top (white keys by their
bright interior, grey and lime keys by colour) and growing each box by 2 px for the outline. The
full per-key table is in Appendix A.

Printed legends sit in the band between rows: about 1 mm above the key's top edge, with cap
height ≈ **1.5–1.7 mm**.

### 4.3 Key shapes

| Kind | Shape | Confidence |
|---|---|---|
| **Round keys** (SHIFT, ALPHA, MODE, ON) | Circular. Light grey-white with spun texture. **Nothing printed on the key itself.** The label is printed on the face above. | VERIFIED [R], [A], [CR] |
| **Cursor keys** | Four separate keys. Each is a **pentagon ("home plate")**: rounded on its outer side, with **two chamfered edges meeting in a blunt point aimed at the pad centre**. For example, UP is rounded on top and pointed downward. Same colour and spun texture as the round keys. **No arrows are printed.** They sit on the honeycomb plate (§2.3). | VERIFIED by sight [R], [A] `k_pad` crop, [CR] |
| **White function keys** | Rounded rectangles, ≈ 1.2 mm corner radius (radius INFERRED). Slightly raised and domed. Separated from the face by a **thin dark gap/outline**, heaviest along the bottom and right as a shadow. | VERIFIED by sight |
| **Grey number keys, lime DEL/AC** | Larger rounded rectangles, ≈ 1.2 mm radius (INFERRED). Flat-topped, with a darker grey outline/shadow. | VERIFIED by sight |
| **Corner-styled keys** | The key at each **outer corner of a key block** has **that one corner** rounded much more (≈ 3 mm radius), so each block reads as one rounded shape. See the list below. All other corners have the small radius. | VERIFIED: [R] and [A] crops agree on all ten. The radius is INFERRED |

The corner-styled keys:

- **Number block:** **7** top-left, **AC** top-right, **0** bottom-left, **=** bottom-right.
- **Function block:** **CALC** top-left, **log▪▯** top-right, **RCL** bottom-left, **M+** bottom-right.
- **Next to the cursor pad**, wrapping around it: **∫▯▬** top-right, **x⁻¹** top-left.

---

## 5. Every key, by physical row

### 5.1 Legend colour rules

| Printed in | Means | Confidence |
|---|---|---|
| **Gold/ochre** text above a key, at the left (or centred if it is the only legend) | **SHIFT** function | VERIFIED: [G p.6] says SHIFT/ALPHA functions are printed above the key; the SHIFT label is gold. [JR]: "Solve is in gold so we use [SHIFT]". |
| **Magenta / crimson** text above a key, at the right | **ALPHA** function (variables A–F, M, X, Y; =, :, ÷R, GCD, LCM, RanInt, e, PreAns) | VERIFIED: the ALPHA label is the same colour. [JR]: "equals is in red so we use [ALPHA]". |
| **Cyan** text with no bracket (DEC, HEX, BIN, OCT) | Pressed **directly** (no SHIFT) in **BASE-N** mode | VERIFIED [G p.59]: x² gives DEC, x▪ gives HEX, log gives BIN, ln gives OCT |
| **Cyan corner brackets** ┌ ┐ around a legend | Used in **BASE-N** mode. Around magenta A–F: those keys type hex digits **directly** in BASE-N. Around gold BASE (on 3): the SHIFT 3 menu belongs to BASE-N. | VERIFIED [G p.6, p.59] |
| **Violet corner brackets** ┌ ┐ around a legend | Used in **CMPLX** mode: ∠ (SHIFT (−)) and CMPLX (SHIFT 2) | VERIFIED [G p.6, p.49] |
| **Violet italic *i*** at the ALPHA position on ENG | In CMPLX mode, pressing **ENG on its own** types *i* | VERIFIED [G p.49] |
| **Dark grey corner brackets** around gold MATRIX, VECTOR, STAT/DIST | Menus that only work in that mode | INFERRED: same convention, and these modes have no colour of their own. [G] does not state it. |
| Dark grey text above round keys (MODE, ON) | The key's own name. The keys are blank. | VERIFIED |

A bracket is two corner marks: "┌" at the left end and "┐" at the right end of the legend. Their
short vertical strokes point **down** towards the key. The bracket colour can differ from the text
inside it. For example ┌A┐ has **cyan** brackets around a **magenta** A, and ┌∠┐ has **violet**
brackets around a **gold** ∠. VERIFIED [A] crops, [R].

Notation in the tables below:

- ▪ is a **filled** square and ▯ is a **hollow** box, as printed (Casio's placeholder glyphs).
- Italic *x* and *e* are **serif italic** on the real keys.
- "—" means nothing is printed in that slot.
- Where nothing else is said, the legend information comes from [R] and [A], with [CR] as a third view. All three agree on every key.

### 5.2 Top cluster (y ≈ 62.7 mm)

| Col | Key | Shape and colour | Printed **on** the key | Printed above (face) |
|---|---|---|---|---|
| 1 | SHIFT | round, light grey-white | — | **SHIFT** (gold) |
| 2 | ALPHA | round, light grey-white | — | **ALPHA** (magenta) |
| 3 | UP | pentagon, light grey-white | — (blank) | — |
| 3 | LEFT | pentagon | — | — |
| 3 | RIGHT | pentagon | — | — |
| 3 | DOWN | pentagon | — | — |
| 4 | MODE | round | — | **MODE** (dark grey) followed by **SETUP** (gold) = SHIFT MODE |
| 5 | ON | round | — | **ON** (dark grey) |

The four cursor keys are the REPLAY pad. "REPLAY" is not printed anywhere on this model (VERIFIED by
sight). Note that **OFF is SHIFT AC**, not on the ON key.

### 5.3 Function keys: white keys with dark-grey key-cap text

| Row | Col | Key cap | SHIFT (gold) | ALPHA / other |
|---|---|---|---|---|
| 1 | 1 | **CALC** | SOLVE | **=** (magenta) |
| 1 | 2 | **∫▯▬**: integral sign with small hollow boxes at its upper and lower limits, then a filled bar | **d/dx▪**: a small stacked "d over dx", then a filled square | **:** (magenta colon) |
| 1 | 3–4 | (gap: the cursor pad) | | |
| 1 | 5 | ***x*⁻¹** | ***x*!** | — |
| 1 | 6 | **log▪▯**: "log", a small filled square (base), a tall hollow box (argument) | **Σ▪**: Σ with hollow boxes above and below for the limits, then a filled square | — |
| 2 | 1 | **▬ over ▯**: a filled bar above a line above a hollow box (fraction template) | **▪▯/▯**: a filled square, then a fraction of two hollow boxes (mixed-fraction template) | **÷R** (magenta) |
| 2 | 2 | **√▪** | **∛▪**: small superscript 3 before √, then a filled square | — |
| 2 | 3 | ***x*²** | ***x*³** | **DEC** (cyan) |
| 2 | 4 | ***x*▪**: *x* with a filled-square exponent | **▪√▯**: a filled-square index, √, a hollow-box radicand | **HEX** (cyan) |
| 2 | 5 | **log** | **10▪** | **BIN** (cyan) |
| 2 | 6 | **ln** | ***e*▪** | **OCT** (cyan) |
| 3 | 1 | **(−)**: a minus inside parentheses | **┌∠┐**: gold ∠ in **violet** brackets | **┌A┐**: magenta A in **cyan** brackets |
| 3 | 2 | **° ’ ’’**: a small circle, then one prime, then a double prime. The primes are drawn as raised, comma-like tadpoles. | **FACT** | **┌B┐** (magenta in cyan) |
| 3 | 3 | **hyp** | **Abs** | **┌C┐** |
| 3 | 4 | **sin** | **sin⁻¹** | **┌D┐** |
| 3 | 5 | **cos** | **cos⁻¹** | **┌E┐** |
| 3 | 6 | **tan** | **tan⁻¹** | **┌F┐** |
| 4 | 1 | **RCL** | **STO** | — |
| 4 | 2 | **ENG** | **←**: a left arrow, i.e. ENG towards the left | ***i*** (violet, serif italic; typed with ENG alone in CMPLX) |
| 4 | 3 | **(** | **%** | — |
| 4 | 4 | **)** | **;** (**semicolon**: this model's argument separator) | **X** (magenta) |
| 4 | 5 | **S⇔D** | **a b/c ⇔ d/c**: a mixed number "a b/c", a double arrow, then "d/c", with the fractions drawn small and stacked | **Y** (magenta) |
| 4 | 6 | **M+** | **M−** | **M** (magenta) |

How the SHIFT and ALPHA functions behave was spot-checked against [G]:

| Press | Gives | Manual page |
|---|---|---|
| ALPHA + fraction key | ÷R | p.24 |
| ALPHA × / ALPHA ÷ | GCD / LCM | p.40 |
| SHIFT ) | ; | p.40 |
| ALPHA CALC | = | p.40 |
| ALPHA (−) / ALPHA °’’’ | A / B | p.28, p.40 |
| SHIFT RCL | STO | p.8, p.28 |
| SHIFT M+ | M− | p.28 |
| SHIFT ENG | ← | p.24 |
| SHIFT hyp | Abs | p.49 |
| ENG (in CMPLX) | *i* | p.49 |
| SHIFT (−) | ∠ | p.49 |
| SHIFT 2 | CMPLX menu | p.49 |
| SHIFT MODE, then ▼ 7 | SETUP, then CONT | p.6 |
| SHIFT AC | OFF | p.6 |

### 5.4 Number pad: grey keys with white text, except DEL and AC (lime with white text)

| Row | Col | Key cap | SHIFT (gold) | ALPHA (magenta) / bracket |
|---|---|---|---|---|
| 5 | 1 | **7** | CONST | — |
| 5 | 2 | **8** | CONV | — |
| 5 | 3 | **9** | CLR | — |
| 5 | 4 | **DEL** (lime key) | INS | — |
| 5 | 5 | **AC** (lime key) | OFF | — |
| 6 | 1 | **4** | **┌MATRIX┐** (dark-grey brackets) | — |
| 6 | 2 | **5** | **┌VECTOR┐** (dark-grey brackets) | — |
| 6 | 3 | **6** | — (**nothing printed**; the US twin has VERIFY here) | — |
| 6 | 4 | **×** | nPr | **GCD** |
| 6 | 5 | **÷** | nCr | **LCM** |
| 7 | 1 | **1** | **┌STAT/DIST┐** (dark-grey brackets) | — |
| 7 | 2 | **2** | **┌CMPLX┐** (**violet** brackets) | — |
| 7 | 3 | **3** | **┌BASE┐** (**cyan** brackets) | — |
| 7 | 4 | **+** | Pol | — |
| 7 | 5 | **−** | Rec | — |
| 8 | 1 | **0** | Rnd | — |
| 8 | 2 | **,** (**comma**: the decimal separator) | Ran# | **RanInt** |
| 8 | 3 | **×10*ˣ***: "×10" with a serif-italic superscript *x* | ***π*** (serif) | ***e*** (serif italic) |
| 8 | 4 | **Ans** | **DRG▶** (with a filled triangle) | **PreAns** |
| 8 | 5 | **=** | — | — |

### 5.5 Where the ZA PLUS II's printing differs from its twins

| Key | fx-991ZA PLUS II | fx-115ES PLUS 2nd ed. (US twin, [CR] photo) | International fx-991ES PLUS |
|---|---|---|---|
| Decimal key | **,** | **•** | **.** |
| SHIFT ) | **;** | **,** | **,** |
| 6 | nothing | ┌VERIFY┐ | — |
| + / − ALPHA | nothing | Int / Intg | — |
| log▪▯ ALPHA | nothing | ∏▪ (product) | — |
| √▪ ALPHA | nothing | recurring-decimal symbol | — |
| Model text | fx-991ZA PLUS II | fx-115ES PLUS, plus a separate "2nd edition" mark at the right under the solar panel | — |

The twin columns are SECONDARY ([CR] side-by-side photo `15_test.jpg`). The ZA column is VERIFIED [R].

---

## 6. Colour palette

These are **estimates from photos and renders**, not Casio specifications. Three sources were sampled:

- **[R]** Casio's render. Clean, but stylised: pure-red ALPHA, black LCD ink.
- **[A]** The Amazon front photo `71dT8tDnANL`. Neutral but underexposed: the white face reads as #DADCD9.
- **[CR]** Real units under warm indoor light, `15_test.jpg` and `21_setup.jpg`.

Method:

- **Inks:** the most saturated 5 %, or the darkest 1 %, of pixels in each legend's box. This reduces anti-aliasing blur.
- **Surfaces:** median of a uniform patch.
- **"Recommended":** INFERRED. Source [A] scaled so its face plate becomes off-white (factor ≈ 1.08), then sanity-checked against [R] and [CR].

| Element | [R] render | [A] photo (raw) | [CR] real unit (raw) | **Recommended** |
|---|---|---|---|---|
| Face plate / body | #DEDEDE – #E2E2E2 | #DADCD9 | #D2D6D5 | **#ECEDE9** |
| White function-key top | #DFDFDF (same as face) | #D8D9D3 | #CDCDCF | **#EEEEEA** (a hair brighter than the face) |
| White key outline / gap shadow | dark rim | #505048 | — | **#55554F** |
| Round and cursor keys | #D5D5D4 / #E4E4E4 | #D7D8D2 | #D1D1D1 | **#E9EAE6** with a subtle radial highlight |
| Honeycomb plate | #D6D6D6 | #D8D9D4 | — | **#E2E3DF** with hex cells ~4 % darker |
| Grey number keys | #9D9D9C – #A6A6A5 | #AAACAB | #ABAEAD | **#A9ABAA** |
| Grey key outline | dark rim | #32332E | — | **#3A3B37** |
| Lime DEL / AC | #A1CB3F (#97C525 most saturated) | #B7CD40 | #98B035 (warm light) | **#B4CF3A** |
| Key text on white keys | #1A1E20 | #66655E (darkest 1 %, blurred) | — | **#4A4A46** (real keys are dark grey, not black) |
| Key text on grey and lime keys | #FFFFFF | #E1E3E3 | — | **#FFFFFF** |
| SHIFT legend (gold / ochre) | #D78700 – #D78F00 | #B48F4C | #87633D | **#C9963F** |
| ALPHA legend (magenta / crimson) | #CE010C (render is pure red) | #B03B63 | #6C0737 | **#C42D63** (real ink is crimson-magenta) |
| BASE-N legend and brackets (cyan) | #00ADC0 | #1694B3 | #186A8D | **#1A9DC2** |
| CMPLX *i*, ∠ and CMPLX brackets (violet) | #704BD1 | #8C7CC0 (thin glyph) | #6A4C96 | **#7457C4** |
| Mode brackets (MATRIX, VECTOR, STAT/DIST) | #6D7071 | #7E807D (thin) | — | **#4F514E** |
| MODE and ON labels, model name | #272A2B | #646563 | — | **#50524F** |
| CASIO logo (not reproduced) | #C0C0C1 render / mid-grey | #606366 | #80848A | (leave blank) |
| Solar panel | #391D1B (reddish brown) | #090908 | #221D1A | **#1E1817** |
| LCD background, "like the real thing" | — | — | about 0.65–0.75 × face brightness (#889889 against face #D3D1D4) | **#A9B4A6** |
| LCD background, "like Casio's art" | #CFE3E4 | — | emulator ≈ #D8E6D8 | **#D3E2D5** |
| LCD dots and status icons | #000000 | — | #092772 – #264575 | **#1B2D63** (navy; [EMU] uses #1E345A) |
| Hard case | — | #E1E1DF | — | **#EFEFEC** |

For a dark-theme app (Inkstone is dark-only, M10), it is probably best to keep the device's own
colours and not theme them. The calculator is an object with its own paint. This is INFERRED
design advice, not a fact about the hardware.

---

## 7. Typography

| Text | Description | Confidence |
|---|---|---|
| Key-cap words (CALC, RCL, ENG, DEL, AC, Ans, hyp, sin, cos, tan, log, ln, S⇔D, M+) | Clean **geometric / humanist sans**, regular weight, mixed case as printed. Cap height ≈ **3 mm** on white keys. "sin" and "cos" have an x-height of about 2.1 mm. The typeface is **UNKNOWN**; Avenir Next or Nunito Sans at regular weight is close. | Look VERIFIED. Size INFERRED from [R]. Font name UNKNOWN |
| Digits and operators on grey keys | The same sans at a **light** weight, white. Digits ≈ **4.9 mm** tall. The 1 has a flag and no foot. ×, ÷, +, −, = are thin and drawn to the same stroke weight. | VERIFIED by sight. Size INFERRED [R] |
| Math variables (*x* in *x*², *x*▪, *x*⁻¹, ×10*ˣ*; *e*; *i*; *π*; *x*!, *x*³, *e*▪) | **Serif italic** in the style of Times Italic | VERIFIED by sight |
| Placeholder glyphs (▪ ▯ ▬) | Solid filled squares and bars; hollow boxes with a thin outline | VERIFIED by sight |
| Face legends (SHIFT, ALPHA, CONST, MATRIX, nPr, GCD…) | Small **medium-to-bold sans**, slightly condensed, cap height ≈ **1.5–1.7 mm** | Size INFERRED [R]. Look VERIFIED |
| Model name | Bold sans, ≈ 1.9 mm. "fx-" in a lighter weight. | VERIFIED by sight [A] |
| LCD | Dot-matrix only; see §3.6. Status icons are fixed segment shapes. | as §3 |

---

## 8. A drawing recipe for Compose (INFERRED; derived from the facts above)

1. Lay out in **millimetres**, with the origin at the top-left of a **77 × 161.5 mm** rounded
   rectangle (r ≈ 8 mm). Scale to the window with one factor so the proportions stay exact.
2. Paint the face, then the solar recess and panel, then the LCD bezel and glass (§2.2).
3. Draw the LCD as a 96 × 31 grid of square dots at pitch *p* = 61.4 mm ÷ 101 ≈ 0.608 mm. Use a
   ≈ 0.1 *p* unlit gap between dots. Place the 18 status icons in a band 3 *p* tall, about 1 *p*
   above the matrix (x positions in §3.2).
4. Key positions come from §4.2 and Appendix A. Apply the large corner radius to the ten keys listed
   in §4.3, and draw the honeycomb plate *under* the cursor keys.
5. Legends follow §5. The SHIFT legend goes left (or centred), ALPHA and BASE-N right, and brackets
   around the text.

---

## 9. Keyboard matrix (only if a ROM is emulated)

These are the KI/KO key codes from [EMU] for the **fx-570ES PLUS (1st gen)**. Whether the 2nd-edition
ZA PLUS II uses the same codes is **UNKNOWN**. Reading the ZA PLUS II's own emulator model, or probing
a real unit, would settle it. SECONDARY.

| Key | Code | Key | Code | Key | Code |
|---|---|---|---|---|---|
| SHIFT | 0x07 | ALPHA | 0x17 | MODE | 0x47 |
| ON | 0xFF (separate line) | UP | 0x27 | DOWN | 0x36 |
| LEFT | 0x26 | RIGHT | 0x37 | CALC | 0x06 |
| ∫ | 0x16 | x⁻¹ | 0x46 | log▪▯ | 0x56 |
| Fraction row (6 keys, left→right) | 0x05, 0x15, 0x25, 0x35, 0x45, 0x55 | (−) row | 0x04 … 0x54 | RCL row | 0x03 … 0x53 |
| 7 8 9 DEL AC | 0x02, 0x12, 0x22, 0x32, 0x42 | 4 5 6 × ÷ | 0x01, 0x11, 0x21, 0x31, 0x41 | 1 2 3 + − | 0x00, 0x10, 0x20, 0x30, 0x40 |
| 0 , ×10ˣ Ans = | 0x64, 0x63, 0x62, 0x61, 0x60 | | | | |

---

## 10. Photos used (saved locally)

The scratchpad root is
`C:\Users\NoahB\AppData\Local\Temp\claude\C--Users-NoahB-OneDrive-Documents-Dev-Claude-Notes-app\e45f9470-8338-45c8-abbd-1c04b4985d26\scratchpad\calc-research\`.
Paths below are relative to it.

| File | What it is | Used for |
|---|---|---|
| `photos\casio_official_fx991za_2_main.png` | **Official Casio render**, to scale, 410 × 860 [R] | All mm geometry, key detection, legend check |
| `photos\amazon_71dT8tDnANL_orig.jpg` (and the `…71dT8tDnANL.jpg` 750 × 1500 crop) | **Straight-on retailer photo**, 2560 × 2560 [A] | Legend reading at high resolution, colours, key shapes |
| `photos\amazon_71lMfW1hhjL.jpg` | Angled retailer photo | Depth and LCD-off look |
| `photos\amazon_51UDuHXOGhL.jpg`, `photos\amazon_51PsiDgxOmL.jpg` | Hard case; side profile | §2.4 |
| `photos\technomobi_709.jpg`, `photos\technomobi_570.jpg` | Blister card front and back [PKG] | Identity, "Decimal Point: Comma", EAN |
| `photos\dynamicbooks_1.jpg`, `photos\dynamicbooks_2.png` | Casio packaging and render variants with the LCD on | LCD look in Casio art |
| `photos\calcreview\15_test.jpg`, `21_setup.jpg`, `22_norm.jpg`, `07_front.jpg`, `08_back.jpg` | Real units, LCD on [CR] | LCD colours, fonts, status-icon order, case sticker, back |
| `photos\yt\aPh-TiwAnOY.jpg` (and others) | Official emulator, as seen in a distributor's video [YT] | Emulator LCD colour, menu style |
| `photos\crops\*.png` | My crops and enlargements of the above | Working evidence |
| `fx-991ZA_PLUSII_EN.pdf` | **[G]**, the official guide | Indicators, legend rules, specifications |
| `label_keys.py` | Script that finds the 50 key rectangles in [R] | Appendix A |

---

## 11. Notes for the other research files

- `07-emulation-and-internals.md` marks the 96-px **width** as SECONDARY. Casio's own ZA comparison
  chart [CMP] says "31 x 96 Full Dot Display", so width and height are both **VERIFIED**.
- `02-manual-core-and-display.md` §1 called the nearest sibling the **fx-991ES PLUS 2nd edition**
  (INFERRED, for functions). The cross-check overturned that (00-index.md §1): the international
  fx-991ES PLUS 2nd edition shares the **body and key layout** (same 11.1 mm body, VERIFIED catalogue)
  but not the functions; the **behavioural** sibling is the fx-115ES PLUS / fx-991ES PLUS C 2nd
  edition, which is also this file's **look-alike** twin (white body, same colour scheme; SECONDARY [CR]).

## 12. Open questions

| # | Question | What would settle it |
|---|---|---|
| 1 | Exact LCD glyph bitmaps (small and large font, Math templates). *Metrics now SECONDARY (§3.6): small 5×6, large 5×9; fraction and superscript layout measured.* Still open: every glyph's bitmap (observable in the [JR] captures, a licensing decision), and the pixel geometry of the √, ∫, Σ, d/dx and log▪▯ templates | A pixel-exact screenshot of Casio's fx-ES PLUS Emulator set to this model |
| 2 | ~~Number of contrast steps~~ (26, SECONDARY §3.1) and the default level | Count presses from a CLR All on a real unit or the emulator |
| 3 | Whether the 2nd-edition status-bar segment shapes and positions match the 1st-gen [EMU] geometry exactly | A close, straight-on photo of a ZA PLUS II in self-test, or its emulator skin |
| 4 | Real typeface of the key caps and legends | Casio design documentation (unlikely to be public); otherwise accept a near match |
| 5 | Exact colour values | A colour-calibrated photo next to a reference card under D65 light, or a spectrophotometer on a real unit |
| 6 | Hard-case dimensions and its exact sticker layout | A measured unit |
| 7 | KI/KO codes for the 2nd-edition board | The ZA PLUS II emulator model, or probing hardware |
| 8 | Physical key travel and click feel (not visual, but relevant to "feel the same") | Not documented anywhere found |

---

## Appendix A — measured key rectangles (from [R], mm, origin at body top-left)

`cx` and `cy` are key centres; `w` and `h` include the outline. Error is about ±0.4 mm. The bottom
number row measures ≈ 0.4 mm shorter than the others; that is probably render shading, so use 8.2 mm
for every number key (INFERRED).

| Key | Kind | cx | cy | w | h |
|---|---|---|---|---|---|
| SHIFT | round | 11.1 | 62.7 | 6.6 | 6.6 |
| ALPHA | round | 21.2 | 62.8 | 6.6 | 6.8 |
| UP | pad | 38.4 | 62.8 | 6.4 | 8.6 |
| LEFT | pad | 30.7 | 67.9 | 9.0 | 6.0 |
| RIGHT | pad | 46.1 | 67.9 | 9.0 | 6.0 |
| DOWN | pad | 38.5 | 73.0 | 6.2 | 8.3 |
| MODE | round | 55.7 | 62.7 | 6.8 | 6.6 |
| ON | round | 65.8 | 62.6 | 6.8 | 6.4 |
| CALC | white | 11.4 | 75.7 | 9.4 | 5.8 |
| ∫ | white | 22.2 | 75.7 | 9.2 | 5.8 |
| x⁻¹ | white | 54.7 | 75.7 | 9.4 | 5.8 |
| log▪▯ | white | 65.6 | 75.7 | 9.4 | 5.8 |
| Fraction | white | 11.4 | 85.1 | 9.4 | 5.8 |
| √▪ | white | 22.3 | 85.1 | 9.4 | 5.8 |
| x² | white | 33.0 | 85.1 | 9.4 | 5.8 |
| x▪ | white | 43.9 | 85.1 | 9.4 | 5.8 |
| log | white | 54.7 | 85.1 | 9.4 | 5.8 |
| ln | white | 65.6 | 85.0 | 9.4 | 5.6 |
| (−) | white | 11.4 | 94.3 | 9.4 | 5.8 |
| °’’’ | white | 22.3 | 94.3 | 9.4 | 5.8 |
| hyp | white | 33.0 | 94.3 | 9.4 | 5.8 |
| sin | white | 43.9 | 94.3 | 9.4 | 5.8 |
| cos | white | 54.7 | 94.3 | 9.4 | 5.8 |
| tan | white | 65.6 | 94.3 | 9.4 | 5.8 |
| RCL | white | 11.4 | 103.7 | 9.4 | 5.8 |
| ENG | white | 22.3 | 103.7 | 9.4 | 5.8 |
| ( | white | 33.1 | 103.7 | 9.2 | 5.8 |
| ) | white | 43.9 | 103.7 | 9.4 | 5.8 |
| S⇔D | white | 54.7 | 103.7 | 9.4 | 5.8 |
| M+ | white | 65.6 | 103.6 | 9.4 | 6.0 |
| 7 | grey | 12.2 | 115.6 | 11.1 | 8.3 |
| 8 | grey | 25.5 | 115.6 | 11.1 | 8.3 |
| 9 | grey | 38.6 | 115.6 | 10.9 | 8.3 |
| DEL | lime | 51.6 | 115.5 | 11.5 | 8.5 |
| AC | lime | 64.6 | 115.5 | 11.5 | 8.5 |
| 4 | grey | 12.2 | 126.9 | 11.1 | 8.3 |
| 5 | grey | 25.4 | 126.9 | 10.9 | 8.3 |
| 6 | grey | 38.5 | 126.9 | 10.7 | 8.3 |
| × | grey | 51.6 | 126.9 | 10.9 | 8.3 |
| ÷ | grey | 64.8 | 126.9 | 11.1 | 8.3 |
| 1 | grey | 12.2 | 138.2 | 11.1 | 8.1 |
| 2 | grey | 25.5 | 138.2 | 11.1 | 8.1 |
| 3 | grey | 38.7 | 138.2 | 11.1 | 8.1 |
| + | grey | 51.6 | 138.2 | 11.3 | 8.1 |
| − | grey | 64.8 | 138.1 | 11.1 | 8.3 |
| 0 | grey | 12.4 | 149.8 | 11.5 | 7.9 |
| , | grey | 25.4 | 149.9 | 11.3 | 7.7 |
| ×10ˣ | grey | 38.7 | 149.7 | 11.1 | 7.7 |
| Ans | grey | 51.8 | 149.7 | 11.1 | 7.7 |
| = | grey | 64.8 | 149.6 | 11.1 | 7.9 |
