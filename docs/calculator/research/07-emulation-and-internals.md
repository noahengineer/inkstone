# 07 — Emulation, and how the real fx-991ZA PLUS II computes

Research for the Inkstone calculator feature. Two questions:

- **(a)** Could Inkstone *emulate* the real calculator (run Casio's own firmware)?
- **(b)** What is known about how the real one computes, so a from-scratch engine can match its outputs?

Researched 2026-10-02. Facts are in my own words; Casio's manual prose is not reproduced.

## Confidence legend

| Tag | Meaning |
|---|---|
| **VERIFIED** | Read in an official Casio source (manual, Casio website), or a primary legal text |
| **SECONDARY** | Community or retailer source: Calcupedia, GitHub reverse-engineering repos, the decompiled firmware, forums |
| **INFERRED** | My own reasoning from the sources. Treat it as a hypothesis and test it |
| **UNKNOWN** | Not established. Each one says what would settle it |

Sources are numbered `[S1]`… and listed in §8.

---

## 0. Summary

1. **Identity.** The fx-991ZA PLUS II is Casio's South-African **"fx-991ZA PLUS 2nd edition"**. It belongs to the **ES PLUS 2nd edition** family (the "-2" models). VERIFIED [S1][S2][S3]. Its firmware is version **CY-849, revision B, checksum 06B3** (SECONDARY [S8]). It has 11 modes: COMP, CMPLX, STAT, BASE-N, EQN, MATRIX, TABLE, VECTOR, DIST, INEQ and RATIO. VERIFIED [S1].
2. **Hardware.** The ES PLUS family runs on ROHM/LAPIS **nX-U8/100**, an 8-bit RISC core. SECONDARY [S9][S10]. The open-source emulators drive the 2nd-edition ROMs with the same ES PLUS hardware model (`hardware_id = 3`, nX-U8, 128 KiB code space). SECONDARY [S11]. No public source gives the exact MCU part number of a 2nd-edition unit (UNKNOWN).
3. **Emulation is not a viable shipping path.**
   - Casio's official emulator for this model is a **Windows-only** licensed program. It is a 90-day trial, then a paid 1-year licence. VERIFIED [S2]. Its licence forbids reverse engineering, derivative works and redistribution. VERIFIED [S4].
   - Open-source nX-U8 emulators exist, including one that builds for **Android** (GPL-3.0). SECONDARY [S11][S12]. They ship **no ROM**.
   - No public dump of the fx-991ZA PLUS II ROM was found. Community "_emu" ROMs are pulled out of Casio's emulator, which its licence forbids.
   - **A from-scratch re-implementation is the realistic route.** §3.5 gives the full conclusion.
4. **The internals are better known than expected.** A community decompilation of the earlier ES PLUS firmware (GYX, fx-570/991ES PLUS) [S13] contains Casio's actual numeric tables. INFERRED that the ZA PLUS II's CY-849 code shares them, because Calcupedia says the 2nd edition changed only menus, diagnostics and the ID screen [S9]. The tables show:
   - numbers are **10-byte BCD records**: a 15-digit mantissa and a 2-digit exponent, with separate **fraction** and **two-term surd** record types (§4.1);
   - internal **π = 3.14159265358980** and **e = 2.71828182845904**, exactly as the manual states (§4.2);
   - integration is **Gauss–Kronrod 7/15** (G7-K15). The node and weight table is in the ROM (§4.6);
   - **exact trig values** come from a lookup table of multiples of 15° (§4.4);
   - the **π form is recognised numerically**, while the **√ form is tracked exactly** (§4.3).

---

## 1. Model identity (only what this topic needs)

| Fact | Value | Confidence / source |
|---|---|---|
| Casio's own product name on edu.casio.com/za | "fx-991ZA PLUS 2nd edition" | VERIFIED [S3] |
| Family | ES PLUS 2nd edition. Casio's emulator page lists it beside the fx-82/85/350/570/991ES PLUS 2nd edition and others | VERIFIED [S2] |
| Emulator package name | "fx-991ZA PLUS-2" in Casio's fx-ES PLUS Emulator v5.00.0021 | VERIFIED [S2] |
| ROM version / revision / checksum | CY-849 / B / 06B3. The sibling fx-82ZA PLUS II is CY-848 rev B, checksum unknown | SECONDARY [S8], corroborated twice (cross-check): a real-unit self-test photo in the zwolken review (01 [CR] `15_test.jpg`) shows `CY-849 VerB / SUM 06B3 OK IDOK` beside an fx-115ES PLUS 2nd edition showing `CY-847 VerA / SUM 3C5D`; and Casio's own ZA HTML guide names its ZA-only screen images `CY849_RAITO_*.png`, `CY849_TABLE_03.png`, `CY848_849_html-1.png` |
| Behavioural sibling | fx-115ES PLUS / fx-991ES PLUS C 2nd edition (CY-847). The ZA guide reuses its images `CY846_847_TABLE_01/02.png`. Keyboard/body sibling: fx-991ES PLUS 2nd edition (CY-845) | Cross-check; see 00-index.md §1 |
| Nearest sibling with a public emulator config | fx-570VN PLUS 2nd edition (CY-860): also ES PLUS 2nd edition, 453 functions, same extra modes | SECONDARY [S11][S9] |
| What "2nd edition" changed vs. ES PLUS | A new mode menu, a new diagnostic mode and a Calculator-ID/QR authenticity screen. No calculation changes are reported | SECONDARY [S9] |
| Calculator ID screen | Shows a 24-character ID and a QR code pointing at wes.casio.com/calc | VERIFIED [S1] |
| Decimal mark / argument separator | Comma for decimals (e.g. `0,5235987756`); semicolon between arguments (`log(2;16)`) | VERIFIED [S1] |
| Display | Dot matrix, 31 dots tall (input height limit is "31 dots × 2"). 96 px wide | Both VERIFIED: height [S1]; "31 x 96 Full Dot Display" on Casio South Africa's comparison chart <https://edu.casio.com/za/products/ntd/comparison.html> (cross-check; was marked SECONDARY) |
| Recurring-decimal feature | **Absent.** "Rdec" appears nowhere in the ZA PLUS II manual, though other ES PLUS models have it | VERIFIED [S1] (by absence) |

The function count is 452 according to James Ralph, Casio's South African distributor (SECONDARY). One manual-hosting site says 552, which is probably wrong. Mode and function details belong to the other research files; this file only uses them where the internals depend on them.

---

## 2. Hardware and firmware architecture

| Item | Value | Confidence / source |
|---|---|---|
| CPU core | ROHM (formerly OKI/LAPIS) **nX-U8/100**: 8-bit RISC, 16 byte registers, separate code and data spaces | SECONDARY [S10][S13] |
| ES PLUS MCU | The decompilation targets "ML610904". That definition file was written by the community, so treat the part number as a community designation | SECONDARY [S13] |
| 2nd-edition MCU part number | UNKNOWN. Settle it by reading the chip marking under the keypad PCB. Nothing found online | UNKNOWN |
| Code ROM (ES PLUS) | 2 × 64 KiB segments (0 and 1) = **128 KiB**, mirrored at segment 8. CasioEmu masks ES PLUS code addresses with `0x1FFFE` | SECONDARY [S11][S13] |
| RAM (ES PLUS) | 0x8000–0x8DFF (3.5 KiB). SFRs at 0xF000–0xFFFF. VRAM at 0xF800–0xF9FF | SECONDARY [S15][S13] |
| Variables in RAM (fx-570/991ES PLUS) | 10 bytes each: M 0x8226, Ans 0x8230, A 0x823A, B 0x8244, C 0x824E, D 0x8258, E 0x8262, F 0x826C, X 0x8276, Y 0x8280 | SECONDARY [S15] |
| RAM integrity check | A 15-byte "magic" run at 0x860E. The calculator resets on boot if it is corrupted | SECONDARY [S15] |
| Input buffer | 100 bytes at 0x8154, plus a 100-byte "last formula" cache. The manual allows **99 bytes** of input | Addresses SECONDARY [S15]; 99-byte limit VERIFIED [S1] |
| How the firmware was written | A mix of hand-written U8 assembly and ANSI-C compiled with Casio's/LAPIS's CCU8 compiler | SECONDARY [S13] |
| Mode byte values (ES PLUS) | COMP 0xC1, CMPLX 0xC4, STAT 0x03, BASE-N 0x02, EQN 0x45, MATRIX 0x06, TABLE 0x88, VECTOR 0x07, RATIO 0x4A, INEQ 0x4B, DIST 0x0C | SECONDARY [S15][S13] |
| Setup byte encodings (ES PLUS) | Number format: Norm1 = 0, Norm2 = 4, Fix = 8, Sci = 9 (digit count kept separately). Angle: D = 4, R = 5, G = 6 | SECONDARY [S13] (label file). ⚠ The same repo's `src/asm/config.asm` comment says "Norm1 = 0, Norm2 = 1" for the ROM's default-settings byte. The two disagree; it matters only for ROM emulation. |
| Contrast | Valid range 0x04–0x1D (26 levels): `_need_reset` treats any other stored value as corrupt RAM and resets. Default UNKNOWN | SECONDARY [S13] (cross-check); Calcupedia's self-test page gives the same normal range for ES PLUS |

There is no hardware BCD coprocessor on ES PLUS. CasioEmuX attaches its `BCDCalc` peripheral only for ClassWiz II (CW) hardware [S11], so ES PLUS arithmetic is done in software. That makes the firmware's arithmetic the thing a re-implementation must match.

---

## 3. (a) Could we emulate it?

### 3.1 Casio's official emulators

| Product | Platforms | Models | Licence model | Embeddable in Inkstone? |
|---|---|---|---|---|
| **fx-ES PLUS Emulator (Subscription)** v5.00.0021 | **Windows 10/11 only.** ARM PCs and virtual PCs are not supported (except with a network licence) | All ES PLUS 2nd edition models, **including fx-82ZA PLUS II and fx-991ZA PLUS II** | 90-day free trial, then a **1-year licence**: standalone FA-CW1SA / FA-CW1-W1A… or network FA-CW1-W1I… | **No.** It is a Windows x86 binary with SafeNet Sentinel licensing, and its licence forbids redistribution |
| ClassWiz Calc App Plus | Android 9+, iOS 15+ | ClassWiz EX/CW models only (fx-991CW, fx-570/991EX…). **No ES PLUS or ZA PLUS II model** | Free with ads, or a subscription | No. It is a separate app, and its EULA forbids copying or reverse engineering |
| ClassWiz emulator on ClassPad.net | Web, Windows, macOS, ChromeOS, iPadOS | ClassWiz models only. **No ES PLUS** | Subscription | No |

- The official emulator rows are VERIFIED [S2][S5][S6][S7].
- The ZA PLUS II emulators were added in v5.00.0010 (20 Jul 2020). Calcupedia also lists a v5.00.0022 (28 May 2025) whose internal version string still reads 5.00.0020. SECONDARY [S16].
- Casio's South African material confirms that a ZA PLUS II emulator exists, with a 90-day trial. VERIFIED [S17].
- Calcupedia reports that Casio announced in June 2026 that all PC emulators will be discontinued on **14 May 2027** (SECONDARY [S16]). Casio UK confirms that date only for the fx-CG50 PC emulator (VERIFIED for that product, [S18]).

**How the official emulators work.** They run Casio's real firmware ROM on **SimU8**, ROHM's in-house nX-U8 simulator core. SECONDARY [S16]. Emulator builds of the ROM differ slightly from shipped ROMs, mainly in keyboard handling: the decompilation has a `REAL=1/0` build switch and an `emu_kb.c` [S13]; CasioEmuX labels emulator-extracted ROMs `_emu` [S11].

### 3.2 Open-source emulators

| Project | What it is | Licence | ES PLUS 2nd-ed support | Android? | ROM included? |
|---|---|---|---|---|---|
| LBPHacker/CasioEmu (archived 2019) | The original nX-U8 emulator and disassembler (C++, SDL, Lua model configs) | GPL-3.0 | — | No | **No** |
| user202729/CasioEmu | The main fork | GPL-3.0 | ES PLUS / ClassWiz | No | No |
| fxesdev/CasioEmuX (and the Xyzstk, qiufuyu123 forks) | Adds nX-U16 (ClassWiz) and ClassWiz II | GPL-3.0 | **Yes:** model configs `fx570vnplus2_emu` and `fx82auplusii_emu`, both `hardware_id = 3` (HW_ES_PLUS). **No ZA PLUS II config** | No | No ("for copyright reasons") |
| telecomadm1145/CasioEmuMsvc (active, pushed Sep 2026) | nX-U8 and nX-U16 emulator with debugger. Has a WebAssembly core build | GPL-3.0 | Inherits ES PLUS support | **Yes:** README lists Android (Gradle/NDK). The older CasioEmuAndroid repo is archived and points here | No |
| fxesdev/RomDumper | Dumps ROM and RAM **out of Casio's official Windows emulators** | GPL-3.0 | n/a | n/a | n/a |

All rows are SECONDARY [S11][S12][S19]. The licences and push dates were read from the GitHub API on 2026-10-02.

INFERRED: a ZA PLUS II model config could be adapted from `fx570vnplus2_emu`, because both are 2nd-edition ES PLUS hardware. It would need the key-matrix coordinates of the ZA PLUS II faceplate and a faceplate image.

### 3.3 Is the ROM available?

| ROM | Public? | Origin | Source |
|---|---|---|---|
| fx-570ES PLUS (real) | Yes, committed in two GitHub repos | Dumped from a physical calculator via ROP "hackstrings" | SECONDARY [S20][S21] |
| fx-82ES PLUS A, fx-991CN X (real) | Yes, in user202729/fxesplus | Remote dump: video of the LCD, decoded | SECONDARY [S20][S22] |
| fx-580VN X (real) | Yes, `580vnx/rom.bin` in user202729/fxesplus | Origin not stated | SECONDARY [S20] |
| Various "_emu" ROMs (fx-570VN PLUS 2nd ed., fx-82AU PLUS II 2nd ed., fx-991ES PLUS…) | Circulated in the community, not shipped with emulators | Extracted from Casio's official emulators | SECONDARY [S11][S19] |
| **fx-991ZA PLUS II (CY-849)** | **None found** | — | UNKNOWN. Searched Calcupedia, GitHub and casiocalc.org |
| Any **real** (physical) ES PLUS 2nd-edition ROM | None found | — | UNKNOWN |

Getting a real ROM out of a calculator is hard:

- The ROM is mask ROM.
- The community dumps relied on ROP exploits found by trial and error, then aimed with the help of an emulator-derived ROM [S22].
- This research found no published exploit for the 2nd-edition firmware.

So even an owner who wanted to dump their own ZA PLUS II has **no documented method**. UNKNOWN whether one exists.

### 3.4 Licence and copyright position (a report, not legal advice)

- **Casio's education software licence.** This is the version published by Casio UK for its downloadable education software. It says the user may not reverse engineer, decompose, disassemble or create derivative works, and may not distribute, rent, assign, loan or transfer copies. VERIFIED [S4]. The page adds that an EULA shown by the installer takes precedence. The installer text of the fx-ES PLUS Emulator itself was not retrieved (UNKNOWN). The ClassWiz app EULA likewise forbids copying, reverse engineering, decompiling or distributing (Article 3). VERIFIED [S5].
- **What that means for the routes** (INFERRED):
  - extracting a ROM from the official emulator with RomDumper is contrary to the licence's no-reverse-engineering clause;
  - shipping the emulator, or its ROM, inside an APK is contrary to the no-distribution clause.
- **South Africa (the model's market).** Copyright Act 98 of 1978, s19B:
  - **s19B(1)** applies only paragraphs **(b) and (c)** of s12(1) to computer programs. Those are criticism or review, and reporting current events. The **"research, private study, personal or private use"** fair-dealing limb, s12(1)(a), is **not** among them. VERIFIED [S23].
  - **s19B(2)** lets a person in **lawful possession** of a program make copies "reasonably necessary for back-up purposes". The copy must be exclusively for personal or private purposes and destroyed when lawful possession ends. VERIFIED [S23].
  - Whether a dump run inside an emulator on a different device counts as a "back-up" is untested. UNKNOWN; that is a question for a lawyer.
  - The **Copyright Amendment Bill** (fair use, a broader personal-use exception and a new s19B) is **not law**. On 26 June 2026 the Constitutional Court found s12D(1)–(5) unconstitutional, so the Bill must go back to Parliament. The existing Act remains in force. VERIFIED [S24].
- **US, for comparison.** 17 U.S.C. §117(a) lets the owner of a copy of a program make a copy that is an essential step in using it with a machine, or an archival copy. VERIFIED [S25].
- **GPL-3.0, if an open-source core were embedded.**
  - Making and running a covered work that you do not "convey" is unconditional.
  - Conveying means any propagation that enables others to make or receive copies.
  - So a privately built, never-distributed app would carry no GPL source obligations. Any distribution of the APK would require GPL-compatible licensing of the combined work.
  - VERIFIED [S26] (GPLv3 §0 and §2).
- **Look-alike UI.** The manual and the faceplate artwork are Casio's. "CASIO" is a trademark, and reproducing the logo is a trademark question separate from copyright. INFERRED; not researched further.

### 3.5 Conclusion: what is possible for a personal Android app

| Route | Technically | Licence / legal | Verdict |
|---|---|---|---|
| Embed Casio's official emulator | Impossible: Windows x86 binary, licence-server DRM, no Android build | Licence forbids it | **Not possible** |
| Open-source nX-U8 core (CasioEmuMsvc builds for Android) + **ROM shipped in the app** | Feasible | Distributing Casio's copyrighted ROM; no licence permits it | **Not acceptable** |
| Same core + **"bring your own ROM"** | Feasible: the core is GPL and a model config is adaptable | Needs a ZA PLUS II ROM. None is public; the only practical source (Casio's emulator) is licence-barred; dumping a physical unit has no known method; the SA backup exception's reach is untested. GPL obligations would apply if the app is ever shared | **Possible only as a private experiment, and only if the user already lawfully holds a ROM. Do not plan on it** |
| **From-scratch engine** that reproduces behaviour documented in Casio's public manual, with the **official Windows emulator used only as a black-box reference** (type keys, record what it shows) | Feasible. Inkstone's `:core:*` must stay KMP and platform-free (CLAUDE.md rule 2); a pure-Kotlin engine fits there, while a C++ NDK emulator core could not | Uses no Casio code. Operating the emulator to observe outputs is the software's normal use; its restrictions concern reverse engineering, derivatives and redistribution [S4] | **Recommended** |

The rest of this file supports that last row.

Caveat on §4. The decompilation is of Casio firmware, and it carries no licence on GitHub (none declared). Implement from **mathematics and observed behaviour**, not by translating its code. The Gauss–Kronrod nodes below are standard published values that happen to match the ROM. The ROM is only evidence of which rule Casio chose.

---

## 4. (b) How the real one computes

Provenance: unless marked VERIFIED, everything in §4 comes from the GYX decompilation of the **fx-570/991ES PLUS** firmware ([S13]; ROM version GY454X/GY455X ver E). The ZA PLUS II runs the later **CY-849**. **INFERRED** that the numeric core is shared; it is the same codebase per [S9]. Every such item is listed in §6 for checking against the ZA PLUS II emulator or a physical unit.

### 4.1 Number representation: 10-byte BCD records

Every value the firmware's arithmetic manipulates is a 10-byte record. Complex values are two records (re, im). The arithmetic function table takes pointers to 10-byte numbers [S20][S13]. The record has three areas:

```
byte 0         bytes 1..7            byte 8     byte 9
[T | p0]       [p1 p2 … p14]         [ A3 ]     [ A4 ]
 ^type nibble   15-nibble payload p0..p14
```

| Type `T` (high nibble of byte 0) | Meaning | Payload | A3 (byte 8) | A4 (byte 9) |
|---|---|---|---|---|
| `0` | **Float** | 15 BCD mantissa digits d₀.d₁…d₁₄, normalised (d₀ ≠ 0) | Exponent as 2 BCD digits: E if E ≥ 0, else 100+E | `01` positive with E ≥ 0; `00` positive with E < 0; `06` negative with E ≥ 0; `05` negative with E < 0 |
| `2` | **Fraction** | Digits with nibble `A` as the separator, left-aligned and zero-padded: `n A d` or `w A n A d` (mixed) | Number of payload nibbles used | Sign: `01` positive, `06` negative |
| `8` | **Two-term surd** (√ form) | `r1 r1 r1  c1 c1  q1 q1  0  r2 r2 r2  c2 c2  q2 q2`. Value = s₁·(c₁/q₁)·√r₁ + s₂·(c₂/q₂)·√r₂. An integer term is stored as √1 | s₂: `01` +, `06` − | s₁ (`00` means "same as s₂"; used when term 1 is zero) |
| `4` | Float flagged for sexagesimal (DMS) display | as float | as float | as float |
| `F` | **Error value**. The first payload nibble is the error code (§4.9) | | | |
| all-zero | **Zero** | | | |

Confidence for the table: SECONDARY. The field layout is from the community codec [S27] and the ROM data tables [S13].

**The sign convention is my correction.** INFERRED, but strongly supported:

- The community codec [S27] and the decompilation's comments read fraction and surd sign codes the other way round (`01` = negative).
- The decompiled routines disagree. `_num_sign` treats `A4 ≥ 4` as negative for **every** non-surd type. `_num_negate` flips the sign by BCD-adding 5 and keeping the low nibble (`01 ↔ 06`, `00 ↔ 05`) [S13].
- Under that reading, both of Casio's special-angle tables (§4.4) decode to the **positive** values that match their float twins.

Worked records, decoded from ROM data [S13]:

| Value | Bytes (hex) |
|---|---|
| 1 | `01 00 00 00 00 00 00 00 · 00 · 01` |
| 100 | `01 00 00 00 00 00 00 00 · 02 · 01` |
| 25 200 | `02 52 00 00 00 00 00 00 · 04 · 01` |
| 0.2316419 | `02 31 64 19 00 00 00 00 · 99 · 00` (exponent 99 → −1) |
| −0.356563782 | `03 56 56 37 82 00 00 00 · 99 · 05` |
| −1.821255978 | `01 82 12 55 97 80 00 00 · 00 · 06` |
| 1.672621637 × 10⁻²⁷ | `01 67 26 21 63 70 00 00 · 73 · 00` |
| π | `03 14 15 92 65 35 89 80 · 00 · 01` |
| ½ (fraction) | `21 A2 00 00 00 00 00 00 · 03 · 01` |
| (√6 − √2)/4 (surd) | `80 02 01 04 00 06 01 04 · 01 · 06` → −(1/4)√2 + (1/4)√6 |
| 2 − √3 (surd) | `80 01 02 01 00 03 01 01 · 06 · 01` → +(2/1)√1 − (1/1)√3 |

What the format implies for a re-implementation:

- **15 significant digits, exponent −99…+99.** This matches the manual: "15 digits" for internal calculation, range ±1×10⁻⁹⁹ to ±9.999999999×10⁹⁹ or 0. VERIFIED [S1].
- **Surd limits.** The payload stores radicands as 3 digits and coefficients and denominators as 2 digits. The manual's √-form limits are exactly 1 ≤ a < 100, 1 < b < 1000, 1 ≤ c < 100, 0 ≤ d < 100, 0 ≤ e < 1000, 1 ≤ f < 100 for ±a√b/c ± d√e/f. VERIFIED [S1].
  - Manual example: 99√999 would simplify to 297√111. a = 297 ≥ 100, so it is shown as the **decimal** 3129,089165. VERIFIED [S1].
- **Fractions** live in a 15-nibble field including separators. The manual's display rule for a mixed fraction is integer + numerator + denominator ≤ **10 digits including separators**. VERIFIED [S1]. Whether improper fractions are allowed more digits is UNKNOWN (test T7).
- **There is no π-form record type.** So the π form must be a display-time recognition (§4.3). INFERRED.
- **Guard digits.** The firmware's working registers (`reg0`…`reg6`) are 16-byte slots at 0x8000, 0x8010…. A number occupies 10 of the bytes, stored byte-reversed: `_num_pi_val` writes the digits into R9…R2 and the exponent/sign into R0/R1 [S13]. The community codec also mentions an unsupported **14-byte** number format [S27]. Whether arithmetic carries extra guard digits beyond the 15 stored is **UNKNOWN** (test T3).

### 4.2 Range, constants and display rounding

| Item | Value | Confidence |
|---|---|---|
| Internal digits | 15 | VERIFIED [S1] |
| Range | ±1×10⁻⁹⁹ … ±9.999999999×10⁹⁹, or 0. Results outside it give Math ERROR (overflow) | VERIFIED [S1] |
| Underflow (\|x\| < 1×10⁻⁹⁹) | UNKNOWN whether it flushes to 0 or raises Math ERROR (test T4) | UNKNOWN |
| Precision claim | ±1 in the 10th digit for a single operation. Errors accumulate. xʸ, ˣ√, ³√, x!, nPr and nCr are computed in internal steps and accumulate more | VERIFIED [S1] |
| Internal **π** | **3.14159265358980**. That is π rounded to 14 significant digits, then padded with a zero; it is *not* the 15-digit rounding 3.14159265358979 | VERIFIED [S1] (manual) **and** SECONDARY [S13] (ROM bytes `3 14 15 92 65 35 89 80`) |
| Internal **e** | **2.71828182845904** (truncated; 15-digit rounding would end …905) | VERIFIED [S1] **and** SECONDARY [S13] |
| Display: Norm | 10 significant digits, trailing zeros dropped (e.g. `0,5`). **Norm 1:** exponential when \|x\| < 10⁻² or \|x\| ≥ 10¹⁰. **Norm 2:** exponential when \|x\| < 10⁻⁹ or \|x\| ≥ 10¹⁰. ZA PLUS II default = **Norm 2** | VERIFIED [S1][S17] |
| Display: Fix n | n = 0…9 decimal places. The result is "rounded off" to n places, trailing zeros kept (`10,000`) | VERIFIED [S1] |
| Display: Sci n | n significant digits, n = 1…9. **Sci 0 means 10 digits** (1÷7 → `1,428571429×10⁻¹`) | VERIFIED [S1] |
| Rounding rule for display | "Rounded off" (Japanese 四捨五入): **round half away from zero** | INFERRED from the wording. Confirm with T5 |
| Display rounding vs. stored value | Display rounding does **not** change the stored 15-digit value. 10÷3×3 shows 10,000 under Fix 3 | VERIFIED [S1] |
| `Rnd(x)` | Rounds the *stored* value to the current display setting: 10 digits under Norm 1/2, else the Fix/Sci digits. Rnd(10÷3)×3 = 9,999 under Fix 3 | VERIFIED [S1] |
| Where the Norm exponential threshold is tested (before or after 10-digit rounding) | UNKNOWN. Edge case 9 999 999 999.6 (test T6) | UNKNOWN |
| Ran# | 3-digit pseudo-random 0.000…0.999. Shown as a fraction in Natural display | VERIFIED [S1] |
| Random generator | 10-byte state at 0x821C: 8 bytes changed only by Ran#/RanInt#, plus a 2-byte counter that ticks with the cursor blink. Algorithm UNKNOWN (and irrelevant: outputs need not match) | SECONDARY [S15] |

### 4.3 How Natural display decides between a fraction, a √ form and a π form

The evidence divides the three cases. The calculator does **not** use a single "recognise the decimal" step.

| Form | Mechanism | Evidence |
|---|---|---|
| **Fraction** | **Both.** (1) Exact: a fraction record type exists, and the arithmetic table has a fraction-construct operator [S20]. (2) Recognition: a decimal can be converted to a fraction on demand with S⇔D. Firmware routine `num_float_to_frac`: it splits off the integer part, converts the fractional part by an undocumented routine, adds the integer back, and succeeds only if the result is a fraction record [S13] | VERIFIED: S⇔D toggles fraction ↔ decimal in both Natural and Linear display, e.g. LineIO 1÷5 = 0,2 → 1/5 [S1]. Mechanism SECONDARY |
| **√ form** | **Exact tracking only.** A dedicated two-term surd record, with coefficients limited to the manual's ranges (§4.1) | VERIFIED: after SHIFT = (≈, decimal result), pressing S⇔D can give a **fraction or π form but never a √ form** [S1]. A √ form therefore cannot be recovered from a decimal; it must be carried through the arithmetic. The surd record type is SECONDARY [S13][S27] |
| **π form** | **Numeric recognition at display time** | VERIFIED: π form is only possible for \|x\| < 10⁶, and the manual warns that internal error can stop a result reaching π form or push a decimal into it [S1]. The manual's own integration example, ∫₀^π (sin x + cos x)² dx, is a **numerical Gauss–Kronrod integral** and displays as **π** [S1]. A numeric integral can only be shown as π by recognising the decimal. There is no π record type (§4.1, INFERRED) |

Design consequence for Inkstone (INFERRED):

- **Exact layer.** Represent values as `Float | Fraction | Surd(two terms) | Complex | Error`. Implement rational and two-term-surd arithmetic. Demote to float whenever a result leaves the representable surd/fraction space: more than two terms, a coefficient ≥ 100, a radicand ≥ 1000, or a non-square-root irrational.
- **Display-time π recognition.** For a float result with \|x\| < 10⁶, compute x/π and try to express it as a fraction within the same digit limits.
- The **exact algorithms** (the fraction search, the π tolerance) are **UNKNOWN**. Settle them by black-box testing (T7–T10) against the emulator.
- ⚠ **Eligibility is also UNKNOWN (cross-check).** Both pieces of evidence above (π÷6 and the ∫ to π) come from expressions that *contain* π. The 2005 fx-570ES guide restricts fractional-π display to "inverse trigonometric results and values normally expressed in radians" (file 02 §8.7). So the trigger may be a property of the expression, not of the number. A re-implementation that tests every decimal for a π multiple would turn `0,7853981634 =` into `1/4 π`; one that does not, would not. Test T16.

### 4.4 Exact trig values of special angles come from a table

The ES PLUS ROM holds two **paired** tables [S13]. My decoding uses the sign convention from §4.1.

| Table | Float twins (15 digits) | Exact twins (record type) |
|---|---|---|
| sin | sin 15° = 0.258819045102521, sin 30° = 0.5, sin 45° = 0.707106781186548, sin 60° = 0.866025403784439, sin 75° = 0.965925826289068 | (√6−√2)/4, ½, √2/2, √3/2, (√6+√2)/4 |
| tan | tan 15° = 0.267949192431123, tan 30° = 0.577350269189626, tan 60° = 1.73205080756888, tan 75° = 3.73205080756888 | 2−√3, √3/3, √3, 2+√3 |

INFERRED behaviour:

- sin, cos and tan give exact surd or fraction results **only at multiples of 15°** (and the Rad/Gra equivalents). cos at a multiple of 15° can use sin of the complement; tan 45° = 1 is already an integer.
- Angles such as 18°, where sin 18° = (√5−1)/4 is two-term representable, are **not** in the table. They would come out as **decimals**.
- Whether the substitution is keyed on the *angle* or on the *computed value* matching the float twin is UNKNOWN (test T8). It matters for near-misses like sin(15.0000000000001°) and for Rad input such as sin(π/12).

### 4.5 Inverse and other functions

The arithmetic dispatch table [S13][S20] names a routine for each of these:

| Group | Operations |
|---|---|
| Arithmetic | + − × ÷, negate, inverse, square, cube, %, x!, nPr, nCr |
| Powers and roots | power (complex-capable), n-th root (computed as y^(1/x)), √, ³√ |
| Exponential and log | eˣ, 10ˣ, ln, log₁₀, log_a b |
| Trig | sin, cos, tan and their inverses |
| Hyperbolic | sinh, cosh, tanh and their inverses |
| Coordinates and angles | Pol, Rec, °/ʳ/ᵍ conversions |
| Statistics | P, Q, R, ▸t |
| Other | Abs, Rnd, RanInt#, ÷R, fraction-construct |
| Complex | complex add, sub, mul, div, abs, sqrt, inverse, square, cube |

The **algorithms** inside the transcendental routines (CORDIC versus polynomial, range-reduction constants) are **UNKNOWN**. The decompilation has not documented them. Since only 10 digits are displayed and the claim is ±1 in the 10th digit, a correctly rounded implementation on 15-plus-digit decimal arithmetic should agree on almost every displayed digit. Last-digit disagreements must be catalogued by testing (T11), not guessed.

Function input ranges are VERIFIED [S1]. The full table is in the manual; the bounds that most often matter:

| Function | Allowed input |
|---|---|
| sin, cos, tan (Deg) | \|x\| < 9×10⁹ |
| sin, cos, tan (Rad) | \|x\| < 157 079 632.7 |
| sin, cos, tan (Gra) | \|x\| < 10¹⁰ |
| tan | additionally excludes odd multiples of 90° / π/2 / 100 grad |
| sinh, cosh | \|x\| ≤ 230.2585092 |
| eˣ | x ≤ 230.2585092 |
| 10ˣ | x ≤ 99.99999999 |
| x² | \|x\| < 10⁵⁰ |
| x! | 0 ≤ x ≤ 69, integers only |
| nPr, nCr | n < 10¹⁰ |
| RanInt# | b − a < 10¹⁰ |
| GCD | \|a\|, \|b\| < 10¹⁰ |
| LCM | 0 ≤ a, b < 10¹⁰ |
| BASE-N | 16-bit binary; 32-bit for the other bases; fractional parts are cut off |

### 4.6 Integration (∫): adaptive Gauss–Kronrod 7/15

| Item | Value | Confidence |
|---|---|---|
| Method | "Gauss–Kronrod" | VERIFIED [S1] |
| **Rule** | **G7–K15**. The ROM table holds the 7 Kronrod abscissae > 0, the K15 weights, the G7 weights, and the centre weights (K15 0.209482141084728, G7 0.417959183673469) | SECONDARY [S13] |
| Default tol | 1×10⁻⁵ when omitted. The ROM builds it by giving the number 1 an exponent byte of 0x95 | VERIFIED [S1]; ROM SECONDARY [S13] |
| Allowed tol | ≥ 1×10⁻¹⁴ recommended | VERIFIED [S1] |
| Negative tol | Argument ERROR | INFERRED from [S13] (a negative-sign check returns code 8) |
| a = b | f(a) and f(b) are still evaluated (so domain errors surface). The result is 0 | INFERRED from [S13] |
| Adaptivity | Intervals are halved (÷2 steps visible). A work counter starts at **324** (0x0144) and a second counter at **47** (0x2F). Exhaustion gives error code 0x0B = **Time Out** | INFERRED from [S13]; the counters' exact meaning is UNCONFIRMED |
| Time Out | Raised when ∫ or d/dx ends without its ending condition being met. The manual suggests a larger tol | VERIFIED [S1] |
| Angle unit | The manual says to use Rad for trig inside ∫ | VERIFIED [S1] |
| Forbidden inside | Pol, Rec, ÷R in f(x). ∫, d/dx, Σ nested in f(x), a, b or tol | VERIFIED [S1] |
| Variable X after ∫ | **Restored.** The parser saves X before calling ∫, d/dx or Σ and writes it back afterwards | SECONDARY/INFERRED [S13] |
| Mode | COMP only | VERIFIED [S1] |

G7–K15 constants. These are the standard published values (QUADPACK `qk15`). The ROM stores each one rounded to 15 significant digits, shown in brackets where it differs visibly.

| Kronrod abscissa ±xₖ | K15 weight | G7 weight (Gauss nodes only) |
|---|---|---|
| 0.991455371120812639 [ROM …813] | 0.022935322010529225 | — |
| 0.949107912342758525 [ROM …759] | 0.063092092629978553 | 0.129484966168869693 |
| 0.864864423359769073 | 0.104790010322250184 | — |
| 0.741531185599394440 | 0.140653259715525919 | 0.279705391489276668 |
| 0.586087235467691130 | 0.169004726639267903 | — |
| 0.405845151377397167 | 0.190350578064785410 | 0.381830050505118945 |
| 0.207784955007898468 [ROM …899] | 0.204432940075298892 | — |
| 0 | 0.209482141084727828 | 0.417959183673469388 |

What is UNKNOWN about the error test, and how to settle it:

- The exact error estimate used: |K15 − G7| (QUADPACK uses a scaled variant).
- Whether tol is absolute or relative.
- How the result is accumulated.

These are unknown because the decompiled routine is uncommented. Settle them with test T12: the polynomial-degree sweep plus timing behaviour on the emulator. Alternatively, a careful read of `_num_integral` / `_f_038C8` / `_f_03A1A` in [S13]. That read is reverse-engineering Casio code, which a team may prefer not to do.

### 4.7 Differentiation (d/dx), SOLVE, Σ

| Item | Value | Confidence |
|---|---|---|
| d/dx method | "Central difference" approximation | VERIFIED [S1] |
| d/dx default tol | 1×10⁻¹⁰ when omitted. The tol is adjusted automatically if it does not converge | VERIFIED [S1] |
| d/dx step and extrapolation schedule | UNKNOWN. The routine contains constants with exponents −10, −7, −3 and −2, whose roles are not established | UNKNOWN (constants SECONDARY [S13]) |
| d/dx caveats | Poor precision or error near discontinuities, inflection points, or when the derivative approaches 0. COMP only. Same forbidden-function list as ∫ | VERIFIED [S1] |
| SOLVE method | Newton's method, solving for X (or the named variable). The initial guess is the variable's current value | VERIFIED [S1] |
| SOLVE output | Always **decimal**. Shows the variable, the solution and "L−R" (left − right at the solution) | VERIFIED [S1] |
| SOLVE iteration cap | A preset number of iterations, then a "Continue: [=]" prompt. The number is UNKNOWN; the decompiled routine has several small loop bounds whose roles are not established | VERIFIED existence [S1]; count UNKNOWN |
| SOLVE errors | Variable ERROR (no solution variable in the equation); Can't Solve | VERIFIED [S1] |
| SOLVE forbidden | ∫, d/dx, Σ, Pol, Rec, ÷R | VERIFIED [S1] |
| Σ | Direct sum f(a) + f(a+1) + … + f(b). Integers with −1×10¹⁰ < a ≤ b < 1×10¹⁰ | VERIFIED [S1] |

### 4.8 Normal distribution functions

| Item | Value | Confidence |
|---|---|---|
| STAT-mode P(t), Q(t), R(t) | Standard normal probabilities. t = (x − x̄)/σx | VERIFIED [S1] |
| Approximation in the ES PLUS ROM | Polynomial of the Zelen–Severo / Abramowitz & Stegun 26.2.17 form. Constants: p = 0.2316419, a₁ = 0.319381**503**, a₂ = −0.356563782, a₃ = 1.78147937, a₄ = −1.821255978, a₅ = 1.330274429 | SECONDARY [S13] |
| ⚠ Deviation from the textbook | The published coefficients are a₁ = 0.319381**530** and a₃ = 1.78147**7**937. The ROM values, as transcribed, differ in both. If accurate, outputs differ from the textbook formula by up to ~6×10⁻⁷, which is visible in a 10-digit display | INFERRED; verify with T13 |
| DIST mode (Normal PD/CD, Inverse Normal, Binomial, Poisson) | Accuracy "up to five significant digits". Inverse Normal is left-tail only | VERIFIED [S1] |
| DIST-mode algorithms | UNKNOWN | UNKNOWN |

Predictions to settle the ⚠ row. I computed these with double precision; they are not calculator output.

| t | exact P(t) | textbook A&S 26.2.17 | ROM-coefficient variant |
|---|---|---|---|
| 0.5 | 0.6914624613 | 0.6914624678 | 0.6914621132 |
| 1 | 0.8413447461 | 0.8413447404 | 0.8413445602 |
| 2 | 0.9772498681 | 0.9772499380 | 0.9772499143 |

### 4.9 Error codes

The internal code is the first payload nibble of a type-F record. Code 2 = Syntax and code 3 = Math also appear as literal constants in the decompiled parser [S13]. The code-to-number mapping is SECONDARY [S27][S13]; the message names are VERIFIED in the ZA PLUS II manual [S1].

| Code | Message |
|---|---|
| 1 | (AC break; internal) |
| 2 | Syntax ERROR |
| 3 | Math ERROR |
| 4 | Insufficient MEM (TABLE mode row limit: 30 rows for f(x), 20 for f(x),g(x)) |
| 7 | Stack ERROR |
| 8 | Argument ERROR |
| 9 | Dimension ERROR |
| 10 | Can't Solve |
| 11 | Time Out |
| 12 | Variable ERROR |
| 13 | (named "NULL ERROR" by the community tool; no ZA PLUS II manual entry) |

### 4.9a Tables in the sibling firmware that settle display text and conversion factors (added by the cross-check)

The GYX decompilation [S13] also carries the firmware's **string table** (`src/asm/generals.asm`, `src/asm/unk5.asm`) and its **conversion-factor table** (`_num_convs`). These are data, not code, and they are what a screen shows, so they are recorded as facts (SECONDARY; INFERRED for the ZA where a 2nd-edition change is possible).

Why they can be trusted for the ZA: the strings `Reset All?`, `[=]   :Yes`, `[AC]  :Cancel`, `   Reset All`, ` Press [AC] key`, `Clear?`, `1:Setup 2:Memory`, `3:All`, `Fix 0~9?`, `Norm 1~2?`, `CONVERSION`, `Number 01~40?`, `[__]`, `Math ERROR` and `[◀][▶]:Goto` all match ZA screen captures (official guide and the Casio-hosted SA handout) character for character, including the run-of-spaces alignment.

What they settle, by file:

- **02**: every error message text; `Sci 0~9?`; SETUP sub-prompts `Result Format?` / `1:MathO 2:LineO`, `Complex Result?` / `1:a+bi  2:r∠θ`, `Frequency?` / `1:ON    2:OFF`; CLR confirmations `Clear Setup?`, `Clear Memory?`; `Continue:[=]`; `Solve for `; hyp and DRG▶ menus.
- **03/06**: CMPLX, BASE, MATRIX, VECTOR and STAT sub-menus, the matrix dimension menu (with a second page `1:1×3 2:1×2 / 3:1×1`), `MatA(m×n) m×n?`, `VctA(m) m?`, `Start?`, `End?`, `Step?`, `f(X)=`, `X₁=` … `ERROR` (cell), `Dec`/`Hex`/`Bin`/`Oct`.
- **Not present** (2nd-edition additions, still UNKNOWN): the inverted-digit MODE menu, SETUP TABLE/APO prompts, every DIST screen, `All Real Numbers` (GYX has only `All` and `No-Solution`), Q1/med/Q3 entries.

Conversion factors (`_num_convs`, one per command pair, stored as 10-byte BCD): in>cm 2.54 · ft>m 0.3048 · yd>m 0.9144 · mile>km 1.609344 · n mile>m 1852 · acre>m² **4046.856** · gal(US)>ℓ 3.785412 · gal(UK)>ℓ 4.54609 · pc>km 3.085678E13 · m/s>km/h **3.6** · oz>g 28.34952 · lb>kg 0.4535924 · atm>Pa 101325 · mmHg>Pa 133.3224 · hp>kW **0.7457** · kgf/cm²>Pa 98066.5 · kgf•m>J 9.80665 · lbf/in²>kPa 6.894757 · °C>°F 1.8 (+32) · cal>J 4.1858. The ZA's two observable data points (oz, km/h) both match this table; the SP 811 printed table would get km/h wrong. Full analysis in 03 §15.

Constants (`_num_consts`) are CODATA **2006** in GYX, stored at CODATA's published digits with negative signs on μe, μn, μμ. The ZA guide says CODATA **2014**, so its values differ, but the storage convention is the best available model (03 §14).

### 4.10 Other limits that the internals impose

All VERIFIED [S1]:

- Input is limited to 99 bytes. The cursor changes shape when 10 bytes or fewer remain.
- A natural-display expression can be at most two screens (2 × 31 dots) tall.
- Calculation history holds about 200 bytes, in COMP, CMPLX and BASE-N.
- Prime factorisation (FACT) works on positive integers of up to 10 digits. A prime factor with more than three digits is shown in parentheses (unfactored).
- ÷R falls back to ordinary division when an operand is very large (e.g. 20000000000 ÷R 17), or when the quotient or remainder is not a positive integer.
- Only the quotient goes to Ans.

---

## 5. Known quirks a faithful engine should reproduce

| # | Quirk | Confidence |
|---|---|---|
| Q1 | π is 3.14159265358980 internally. For example π − 3.14159265358 should give 9.8×10⁻¹² rather than 9.79323846×10⁻¹² (test T2) | Constant VERIFIED; the displayed result is INFERRED |
| Q2 | 99√999 shows as a decimal, because the simplified coefficient (297) exceeds 99 | VERIFIED [S1] |
| Q3 | A numerical integral can come out as an exact-looking π. Conversely, a value that should be decimal can be shown in π form, through internal error | VERIFIED [S1] |
| Q4 | SHIFT= followed by S⇔D never yields a √ form | VERIFIED [S1] |
| Q5 | Mixing a fraction with a decimal always gives a decimal result | VERIFIED [S1] |
| Q6 | Rnd changes the stored value; Fix/Sci only change the display | VERIFIED [S1] |
| Q7 | sin 18° (and other non-15°-multiple angles) comes out as a decimal, even though its exact form fits the surd record | INFERRED (T8) |
| Q8 | P(t) may differ from the textbook normal-CDF approximation in the 7th decimal | INFERRED (T13) |
| Q9 | ∫, d/dx and Σ leave X unchanged | SECONDARY/INFERRED (T14) |
| Q10 | Without a closing parenthesis, sin 30 + 15 is sin(45) = 0,7071067812 | VERIFIED [S1] |

---

## 6. Open questions and the test that settles each one

Run these on the **official fx-991ZA PLUS II Emulator** (Windows, 90-day trial [S2]) or a physical unit. Record the exact screen text as golden test vectors. Everything here is black-box observation.

| Id | Question | Test |
|---|---|---|
| T1 | Is the ZA PLUS II numeric core identical to ES PLUS GYX? | Run T2–T14. Any deviation from §4 means the CY-849 code differs |
| T2 | Internal π / e | π − 3.14159265358 and e − 2.71828182845 in LineIO, Norm 2; also Sci 9 |
| T3 | Guard digits; how rounding is stored | (2 ÷ 3,0) × 3 − 2 and 1 − (1 ÷ 3,0) × 3 in LineIO. The ",0" forces decimals. Result 0 means extra guard digits or round-to-nearest; ±1×10⁻¹⁵ / ±2×10⁻¹⁵ shows the stored rounding direction |
| T4 | Underflow | 1×10⁻⁹⁹ ÷ 10 and 1×10⁻⁵⁰ × 1×10⁻⁵⁰: does it give 0 or Math ERROR? |
| T5 | Display tie rounding | 0,12345678905 (11 significant digits, tie at the 10th) and its negative, in Norm 2 |
| T6 | Norm threshold vs. rounding | 9999999999,6 and 9999999999,4 in Norm 2 |
| T7 | Fraction limits | 1 ÷ 123456789; 1 ÷ 1234567; 12345 ÷ 99999; mixed vs. improper (SETUP ab/c vs. d/c); 0,333333333333333 = (15 threes) and 0,3333333333 = in MathO |
| T8 | Special angles | sin 18°, sin 15°, sin(π ÷ 12) in Rad, sin 105°, cos 75°, tan 15°, sin 15,0000000001°, sin 22,5° |
| T9 | π recognition tolerance | π ÷ 7, 22 ÷ 7, 355 ÷ 113, 3,14159265359 (MathO), 1000000π vs. 999999π (the 10⁶ limit), √2·π |
| T10 | Surd arithmetic closure | √2 + √3 + √5 (three terms → decimal?); (√2+√3)²; 1 ÷ (√2 + √3); √(1 ÷ 3); √0,5; √999 vs. √1000 |
| T11 | Transcendental last digits | A sweep of ln, eˣ, sin, tan and xʸ at ~200 random arguments. Diff against a correctly rounded reference |
| T12 | Integration internals | ∫₀¹ xⁿ dx for n = 20…30 with tol 1×10⁻¹⁴ (K15 is exact through degree 22); ∫ of \|x\| across 0; a slow integrand, to time Time Out |
| T13 | Normal CDF coefficients | STAT 1-VAR with any data, then P(1), P(0,5), P(2) at Sci 0. Compare with the §4.8 table |
| T14 | X preservation | Set X = 7, compute ∫₀¹ X dx, then recall X |
| T15 | SOLVE iteration cap | Solve X² + 1 = 0 from X = 1. Count "Continue" prompts; log the value after each |
| T16 | π-form eligibility (cross-check) | MathO: `0,7853981634 =`, `1÷4×3,1415926535898 =`, `22÷7 =`, then `cos⁻¹(0)` in Rad as the control. Does a π-free expression ever display in π form? |
| T17 | Conversion factors that differ between GYX and SP 811 (cross-check) | `1 acre▶m²` (4046,856 vs 4046,873), `1 hp▶kW` (0,7457 vs 0,7456999), `1 kg▶lb` (2,204622476 vs 2,204622622), and `5 cm▶in` in MathO (`250/127` if conversions are exact) |
| T18 | Large/small font metrics (cross-check) | Screenshot at 1:1 any menu and any result; confirm 5×6 small and 5×9 large glyphs (01 §3.6) |

Not settled by this research:

- **Natural-V.P.A.M. patents.** A search of Google Patents and FreePatentsOnline found only Casio UI patents: formula input and intake (US 8214413), ENG and fraction gestures (US 8745112), colour-coded natural display (JP2011186567A) and handwriting correction (US 2010/0231597). None describes the fraction, √ or π result algorithm. UNKNOWN. A J-PlatPat search of Casio filings from 2003–2006 (the fx-82ES launched in 2004), using keywords such as 根号 / 分数 / 計算結果 / 表示形式, might find one.
- **A "CASIO VPAM" academic paper, or a Bisqwit write-up.** None found.
- **MCU part number of the 2nd edition**, and whether a ROP exploit or dump exists for CY-8xx firmware.

---

## 7. Where this leaves the build

INFERRED from all of the above:

1. **Do not plan on emulation.** There is no lawful, practical ROM source for this model, and Casio's emulator cannot be embedded.
2. **Build a decimal engine.** Use 15-digit BCD semantics (or a ≥ 18-digit decimal with explicit rounding to 15), exponent −99…99, π = 3.14159265358980, e = 2.71828182845904.
3. **Use typed values:**
   - float;
   - exact fraction;
   - two-term surd with the manual's coefficient limits;
   - complex as a pair;
   - error values carrying the §4.9 codes.
4. **Special-case trig** at multiples of 15° from a table. Recognise π forms at display time for |x| < 10⁶.
5. **Integrate** with adaptive G7-K15, default tol 1e-5, and a Time Out budget. **Differentiate** by central differences with default tol 1e-10. **Solve** by Newton with a Continue prompt.
6. **Collect the §6 golden vectors** from the official emulator before tuning anything.

---

## 8. Sources

| Id | Source | Type |
|---|---|---|
| S1 | Casio, *fx-991ZA PLUS II User's Guide (NATURAL-V.P.A.M.)*, © 2020. https://www.casio.com/content/dam/casio/global/support/manuals/calculators/pdf/004-en/f/fx-991ZA_PLUSII_EN.pdf (online version: https://support.casio.com/global/en/calc/manual/fx-991ZAPLUSII_en/) | Official |
| S2 | Casio, fx-ES PLUS Emulator product page. https://edu.casio.com/intl/software/emulators/product/es_plus/ ; also https://edu.casio.com/products/classroom/es_plus/ and https://edu.casio.com/intl/software/emulators/windows/ | Official |
| S3 | Casio South Africa, "fx-991ZA PLUS 2nd edition". https://edu.casio.com/za/products/ntd/fx991za_2/ | Official |
| S4 | Casio UK, emulator terms (Casio education software licence). https://education.casio.co.uk/emulator/emulator-guidance/ | Official |
| S5 | Casio, ClassWiz Calc App Plus EULA (Article 3). https://edu.casio.com/app/classwiz/license_plus/en/en_eula.html ; app page https://edu.casio.com/app/classwiz/license/en/ | Official |
| S6 | Google Play, ClassWiz Calc App Plus. https://play.google.com/store/apps/details?id=jp.co.casio.fx.ClassWizCalcAppPlus | Official listing |
| S7 | Casio ClassPad.net plans and compatible models. https://classpad.net/intl/billing/ | Official |
| S8 | Calcupedia (backup), "Calculator self-test": ROM version and checksum lists. https://fxesdev.github.io/calcupedia_backup/docs/diagnostic.html | Community |
| S9 | Calcupedia, "Casio ES" (ES PLUS 2nd edition section) and "Casio fx-991ES PLUS 2nd edition". https://gwe.dpdns.org/calcupedia/Casio_ES , https://gwe.dpdns.org/calcupedia/Casio_fx-991ES_PLUS_2nd_edition | Community |
| S10 | Calcupedia, "nX-U8". https://gwe.dpdns.org/calcupedia/NX-U8/100 | Community |
| S11 | fxesdev/CasioEmuX (GPL-3.0): `emulator/src/Data/HardwareId.hpp`, `Chipset.cpp`, `MMU.cpp`, `models/*/model.lua`. https://github.com/fxesdev/CasioEmuX | Community code |
| S12 | telecomadm1145/CasioEmuMsvc (GPL-3.0; Android/NDK build). https://github.com/telecomadm1145/CasioEmuMsvc ; archived predecessor https://github.com/telecomadm1145/CasioEmuAndroid | Community code |
| S13 | gamingwithevets/gyx-decomp, decompilation of the ES PLUS GYX firmware (archived on GitHub, continued on Codeberg; no licence file). Files used: `src/asm/unk5.asm` (`_num_*` constant tables, `_num_sin_consts`, `_num_tan_consts`, `_num_normal_cdf`, `_num_float_to_frac`, `_num_parse_calculus`), `src/asm/generals.asm` (`_num_gk_*`, `_num_integral`, `_num_ddx`), `src/asm/unk6.asm` (`_num_pi_val`, `_num_euler_val`, `_num_sign`, `_num_negate`), `assets/labels_esp`, `assets/ML610904.DCL`. https://github.com/gamingwithevets/gyx-decomp ; https://codeberg.org/gwetm/gyx-decomp | Community decompilation |
| S14 | manuals.ca listing for the fx-991ZA PLUS II (31 × 96 px). https://www.manuals.ca/casio/fx-991za-plus-ii/manual | Retailer/aggregator |
| S15 | "CASIO fx-ES PLUS calculators exploits" wiki, Memory Map. http://casiocalc.wikidot.com/memory-map | Community |
| S16 | Calcupedia, "Casio calculator emulators" (SimU8, version history, discontinuation note). https://gwe.dpdns.org/calcupedia/Casio_calculator_emulators | Community |
| S17 | Casio Calculators South Africa / James Ralph, "FX 82 ZA PLUS II / FX 991 ZA PLUS II" teaching notes (Norm 2 default, comma decimal mark, emulator trial). https://edumaterial.casio-intl.com/resource/contents/1368 ; product page https://www.casio.jamesralphedu.co.za/products/p/casio-fx-991za-plus-ii-sa-education-advanced-scientific-calculator | Official distributor |
| S18 | Casio UK Education, emulators landing page (fx-CG50 PC emulator support ends 14 May 2027). https://education.casio.co.uk/emulators-landing-page/ | Official |
| S19 | fxesdev/RomDumper (GPL-3.0). https://github.com/fxesdev/RomDumper | Community code |
| S20 | user202729/fxesplus: `570esp/labels`, `570esp/note.md` (arithmetic function table, 10-byte number struct), `chipset_docs/`. https://github.com/user202729/fxesplus | Community |
| S21 | ZaoHan415/fxesplus (ROM dumps from a real calculator). https://github.com/ZaoHan415/fxesplus | Community |
| S22 | user202729/fxesplus issue #4, "Question of dumping ROMs". https://github.com/user202729/fxesplus/issues/4 | Community |
| S23 | South Africa, Copyright Act 98 of 1978, ss 12 and 19B (WIPO Lex). https://wipolex-res.wipo.int/edocs/lexdocs/laws/en/za/za002en.html ; https://www.acts.co.za/copyright-act-1978/19b_general_exceptions_regarding_protection_of_computer_programs | Primary law |
| S24 | Constitutional Court of South Africa, *Ex parte President … Copyright Amendment Bill* [2026] ZACC 26 (26 June 2026), media summary. https://www.concourt.org.za/index.php/judgement/647-ex-parte-president-of-the-republic-of-south-africa-in-re-constitutionality-of-the-copyright-amendment-bill-and-the-performers-protection-amendment-bill-cct306-24 ; commentary https://www.golegal.co.za/court-copyright-amendment-bill/ | Primary / legal press |
| S25 | 17 U.S.C. §117. https://www.law.cornell.edu/uscode/text/17/117 | Primary law |
| S26 | GNU GPL v3, §0 ("convey") and §2. https://www.gnu.org/licenses/gpl-3.0.txt | Primary licence text |
| S27 | gyx-decomp `assets/num_lookup.py` and `assets/num_maker.py`, community codec for the 10-byte format. Its fraction/surd sign reading is contradicted by `_num_sign` / `_num_negate` in S13 (see §4.1) | Community code |
| S28 | Casio patents found, all UI-only: US 8214413, US 8745112, JP2011186567A, US 2010/0231597A1. https://www.freepatentsonline.com/8214413.html , https://www.freepatentsonline.com/8745112.html , https://patents.google.com/patent/JP2011186567A/en , https://patents.google.com/patent/US20100231597A1/en | Patents |
