# 08 — Fitting an fx-991ZA PLUS II into Inkstone: where it goes and how it is wired

Research file 08. Siblings cover what the calculator *does* (02 onwards). This file covers only
**where it lives in this codebase**: the module layout, the button that opens it, how the window
behaves over the ink surface, how a result goes into a note, and which of CLAUDE.md's hard-won
lessons apply. No source file was changed to write it.

Everything below was read from the tree on branch `m5-nextcloud-auth-upload` on 2026-10-02.
Line numbers are from that tree. "Measured" means a command was run and its output read.
"Estimated" means arithmetic on numbers in the docs, still to be confirmed with a
`uiautomator dump` on the emulator.

---

## 0. The short version

| Question | Answer |
|---|---|
| Is a calculator out of scope? | **No.** SPEC §12 lists real-time collaboration, AI, audio sync, tags/backlinks, handwriting→text replace and editing imported annotations. A calculator is not among them, and SPEC §1.2 does not mention one. It is also not in ROADMAP (M0–M12). So it is a **new feature outside the milestone plan**. It gets its own decisions doc (convention: `docs/CALCULATOR.md`), a CLAUDE.md quick-reference row, and a SPEC §2.1 edit if a module is added. CLAUDE.md's *"one milestone per session"* and *"don't gold-plate"* both apply, and a ~452-function calculator is several sessions of work. |
| Engine | A new KMP module, **`:core:calc`**: pure Kotlin, no dependencies, platform-free and iOS-linkable. See §1. |
| UI | A new package, `app/src/main/kotlin/dev/inkstone/app/calculator/`, using Compose and Hilt. See §6. |
| State owner | A **process-wide `@Singleton` `CalculatorController`** with its own scope. This is the `RecordingController` pattern. A thin `@HiltViewModel CalculatorViewModel` gives screens access to it. See §4.9. |
| Persistence | The existing `setting` table through `SettingStore`, under a closed `calc.*` key space. This is the `EditorPreferences` / `EditorPrefsCodec` pattern. See §4.9. |
| Editor entry point | A one-chip `GlassBar` **`CALC` at `Alignment.TopStart`**. It is a new zone, and none of the shipped controls has to move. The alternatives and their measured costs are in §3. |
| Library entry point | A one-chip pill at `Alignment.BottomStart`, which is free. `CreateBar` is capped at "three chips and no more". |
| Window | An **in-window, non-modal, draggable** composable in `InkScreen`'s root `Box`. It is **not** a `Popup`, `Dialog` or sheet, has **no scrim**, and consumes every pointer inside its own bounds. See §4. |
| Insert into note | `NoteEditorViewModel.insertTextBox(box, text, ObjectToolState.DEFAULT_TEXT_STYLE, format)`. That is one gesture and one undo entry. **Use the Sans face only.** Several calculator glyphs are absent from the PDF font (measured), and the published PDF would silently drop them. See §5. |
| The ROM-emulator route | The engine would be C++ with an NDK build, and this repo has neither. It also needs a Casio ROM dump, which cannot be shipped. Casio's own fx-991ZA PLUS II Emulator is licensed Windows software (file 02 §0, [SA s.20]). It is useful as a **test oracle**, but it cannot be embedded. See §1.3. |

---

## 1. Module layout

### 1.1 Recommended: `:core:calc` (engine) + `:app` `calculator/` (UI)

```
:core:calc   KMP  · the fx-991ZA PLUS II engine: key → state reducer, 15-digit decimal arithmetic,
                    modes, display formatting, error model. Zero dependencies. Zero platform deps.
:app         AND  · calculator/ — window, Casio face (LCD + keypad), controller singleton, persistence,
                    insert-into-note glue. Depends on :core:calc.
```

Reasons, each tied to the repo:

1. **The engine is pure logic. CLAUDE.md asks for exactly that shape:** *"Hoist the decision into
   a pure function over plain data so a test can at least assert the decision was made."* A reducer
   `reduce(state, key) -> state` is that shape. It is serialisable, deterministic, and can be
   tested without Android.
2. **Its tests run on their own.** `:app:testDebugUnitTest` also runs SPEC §13.2's merge matrix
   (`app/src/test/kotlin/dev/inkstone/app/sync/matrix/*`), which is slow and drives an in-process
   server. `:core:calc:testDebugUnitTest` runs only the calculator.
3. **The iPad comes for free.** SPEC §11 and ROADMAP M11 keep `:core:*` linkable for iOS. A
   calculator engine in `:core` reaches the iPad with no further work.
4. **The guardrails protect it.** `checkCoreIsPlatformFree` rejects `android.*` / `androidx.*`.
   The iOS klib compile rejects `java.math.BigDecimal`, `String.format`, `java.text.*` and every other
   JVM-only API, and an engine written in a hurry would reach for all of them (§1.4).

Not recommended:

- **Engine inside `:app`.** It would work, but the tests would be slow, there would be no iOS path,
  and nothing would stop `java.math.BigDecimal` creeping in.
- **An `:android:calc` module.** The engine needs no Android type, so there is no reason for it.
  `:android:*` exists for platform implementations (ink, pdf, ocr).
- **Splitting the UI into its own module.** `:app` already owns all Compose UI (SPEC §2.1). The
  existing feature packages (`elements/`, `audio/`, `library/`) are packages, not modules.

### 1.2 Suggested shape inside `:core:calc` (to be refined by the engine research)

```
core/calc/
  build.gradle.kts
  src/commonMain/kotlin/dev/inkstone/core/calc/
    Calculator.kt          // facade: reduce(state, key, interrupt) -> CalcState; pure
    CalcState.kt           // modes, SETUP, memories A–F X Y M, Ans, PreAns, input buffer, display
    Key.kt                 // closed key vocabulary (SHIFT/ALPHA layers resolved by the reducer)
    CalcError.kt           // sealed: Math / Syntax / Stack / Argument / Dimension / ... (names per file 02)
    number/Dec.kt          // 15-significant-digit decimal, hand-written (see §1.4)
    number/Fraction.kt, number/Complex.kt
    input/…                // natural-textbook input tree + linear form
    eval/…                 // parser, precedence (file 02's priority table), functions
    format/…               // Norm 1/2, Fix, Sci, Eng, fractions, √ forms, decimal comma
    mode/…                 // COMP, CMPLX, STAT, BASE-N, EQN, MATRIX, TABLE, VECTOR, DIST, INEQ, RATIO
  src/commonTest/kotlin/dev/inkstone/core/calc/
    …Test.kt               // table-driven vectors from the guide (file 04)
```

Long operations (∫, d/dx, Σ, SOLVE, TABLE) should be cooperative. They take an
`interrupt: () -> Boolean` and poll it, so that `AC` can cancel them. That needs **no
`kotlinx-coroutines` dependency in `:core:calc`**. Coroutines are already pinned in the catalog,
so the dependency would be allowed, but there is no need for it.

### 1.3 The "use an emulator" route, and why it does not fit here

- Open-source emulators of Casio's ES PLUS CPU need a **ROM image dumped from a physical
  calculator**. That firmware is Casio's copyrighted code. It cannot be committed to this repo or
  shipped in an APK.
- They are C/C++. This repo has **no NDK or CMake anywhere** (measured: no `externalNativeBuild`,
  `ndkVersion` or `cmake` in any `*.gradle.kts`). Adding one is a new toolchain, a new pin under
  rule 3, and native code that the iOS guardrail cannot see.
- Casio's official **fx-991ZA PLUS II Emulator** is licensed Windows software with a 90-day trial
  (file 02 §0, [SA s.20]). It is the best **oracle** for settling file 02's UNKNOWNs, such as how
  ties round under Fix. It is not a component.

So the integration below assumes the engine is built from scratch in `:core:calc`.

### 1.4 What common Kotlin does not have (the engine will hit all of these)

| Need | JVM habit | In `commonMain` |
|---|---|---|
| Exact decimal arithmetic (the calculator works internally to **15 digits**, file 02 §"Internal calculation") | `java.math.BigDecimal` | Not available, and it fails the iOS klib compile. Write a small decimal type by hand. **The repo has done this before:** SHA-256 and MD5 are hand-written in common code against published test vectors (CLAUDE.md, *"There is no digest in common Kotlin"*). Here the vectors are the guide's worked examples (file 04). A third-party KMP bignum library would need a version verified from its release page (rule 3). Prefer no dependency. |
| Formatting a number for display | `String.format`, `DecimalFormat` | Not available. Generate the digits yourself. |
| `Double.toString()` | — | Available, but **never format output with it**. The engine's tests run on the JVM (`testDebugUnitTest`) and on Kotlin/Native (`iosSimulatorArm64Test` on macOS CI). If a result string depends on the platform's float printing, it can pass on one and fail on the other. Keep floating-point formatting out of anything the display shows. |
| Error handling | Throwing `ArithmeticException` | CLAUDE.md: *"sealed result types across module boundaries. Exceptions only for genuine programming errors."* Detekt also has `TooGenericExceptionCaught` and `SwallowedException` turned on. `Math ERROR` and its siblings are **values**, not exceptions. |
| `!!` | — | `UnsafeCallOnNullableType` is active. CLAUDE.md: *"no `!!` outside tests."* |

---

## 2. Creating `:core:calc`: exact steps

### 2.1 Files

1. **`settings.gradle.kts`**: add `include(":core:calc")` beside the other `:core:*` includes
   (lines 33–37).
2. **`core/calc/build.gradle.kts`**: the smallest existing core module is `:core:search`, and its
   whole build file is a plugin plus one dependency. `:core:calc` needs no dependency at all:
   ```kotlin
   plugins {
       id("inkstone.kmp-core")
   }
   ```
   The convention plugin (`build-logic/src/main/kotlin/inkstone.kmp-core.gradle.kts`) supplies the
   Android target, `iosArm64()` + `iosSimulatorArm64()` with static frameworks (base name derived as
   `InkstoneCoreCalc`), namespace `dev.inkstone.core.calc`, `compileSdk 36`, `minSdk 30`,
   `kotlin("test")` on `commonTest`, the per-module `checkCoreIsPlatformFree` wired into `check`, and
   `inkstone.quality` (spotless + detekt).
3. **Root `build.gradle.kts`**: add `":core:calc"` to `val coreModules` (lines 27–33).
   **This step is the one that matters.** That list drives `linkIosCore`, and CI's macOS job
   (`.github/workflows/ci.yml`, job `ios-core-link`) runs `./gradlew linkIosCore`. If you forget
   it, guardrail 2 never links the new module. It still reports success, because it is not
   looking at it. That is the "green build that ran nothing" failure from CLAUDE.md DoD #3.
4. **`app/build.gradle.kts`**: add `implementation(project(":core:calc"))` in the block headed
   *"SPEC §2.1 — :app is the only module allowed to depend on everything"*.
5. **`SPEC.md` §2.1** and **`README.md` → *Module graph***: add the `:core:calc` line. CLAUDE.md
   DoD #5 and the working agreement require SPEC to be updated in the same commit.
6. **Sources:** `core/calc/src/commonMain/kotlin/dev/inkstone/core/calc/…`.
   **Tests:** `core/calc/src/commonTest/kotlin/dev/inkstone/core/calc/…`. `:core:search` uses the
   same layout.

### 2.2 Running the tests (per CLAUDE.md DoD #3)

```bash
# JVM (Android unit-test variant of the KMP module). --rerun is per task and must follow each task.
./gradlew :core:calc:testDebugUnitTest --rerun --no-build-cache
# iOS klib compile. Runs on Windows; catches JVM-only APIs and test names Kotlin/Native rejects.
./gradlew :core:calc:compileKotlinIosSimulatorArm64 :core:calc:compileTestKotlinIosArm64
# Guardrail 1 and style
./gradlew :core:calc:checkCoreIsPlatformFree :core:calc:detekt spotlessCheck
# macOS only (CI): the framework link and the native test run
./gradlew :core:calc:linkDebugFrameworkIosSimulatorArm64 :core:calc:iosSimulatorArm64Test
```

- Read the `> Task …` lines back. A test task reporting `UP-TO-DATE` or `FROM-CACHE` ran nothing.
- The test count is `tests=` summed over `core/calc/build/test-results/testDebugUnitTest/*.xml`.
  It is **not** Gradle's "N actionable tasks". `build` also runs `testReleaseUnitTest`, so "tests
  executed" is twice "tests written".
- `:app` pure-function tests (§6) live in `app/src/test/kotlin/dev/inkstone/app/calculator/` and run
  with `./gradlew :app:testDebugUnitTest --tests "dev.inkstone.app.calculator.*" --rerun`.

### 2.3 Test-name rule (a calculator suite will break it by reflex)

CLAUDE.md: Kotlin/Native rejects a comma in a backtick test name, and D8 rejects commas and
apostrophes in instrumented tests. The JVM also forbids `. ; [ ] / < > : \` in method names, and
**calculator test names want exactly those characters**: `` `1/3 + 1/6` ``, `` `sin 30, deg` ``,
`` `Ans: 2.5` ``. Keep names to `[A-Za-z0-9 _-]`. Put the expression **in the data** of a
table-driven test, not in the name. File 04's vectors become rows. A table also keeps each test
function under `LongMethod` (§2.4), which **does apply to test sources**: its default config has no
`excludes`.

### 2.4 Detekt: the limits an engine will trip

The baseline (`config/detekt/baseline.xml`) is **empty** and `build.maxIssues: 0`, so every finding
fails `build`. Defaults are from `default-detekt-config.yml` inside `detekt-core-1.23.8.jar`
(measured). Overrides are from `config/detekt/detekt.yml`.

| Rule | Limit here | Where a calculator trips it | What to do |
|---|---|---|---|
| `LongMethod` | **60** lines (default), tests included | the parser's main loop, a `when` over every key, long test bodies | split by key class; table-driven tests |
| `CyclomaticComplexMethod` | **15**, `ignoreSimpleWhenEntries: false` | `when (key) { … 50 branches … }`. **Every branch counts.** | dispatch through maps of key → handler, or several smaller `when`s by key group |
| `ComplexCondition` | 4 | mode × SHIFT × ALPHA × input-state guards | name the predicates |
| `NestedBlockDepth` | 4 | natural-display tree walking | extract functions |
| `MagicNumber` | active; ignores `-1 0 1 2`; **ignores property declarations and companion/const** (repo override); **`ignoreEnums: false`**; `ignoreNamedArgument: true`; **excluded in `commonTest`** | digit limits (10, 15), exponent bounds (±99, 1e100), key-grid coordinates in an `enum class Key(val row, val col)` | `private const val` per number; named arguments; keep layout tables in properties |
| `TooManyFunctions` | files 20, classes 30, **objects 20**, interfaces 15; `@Composable` ignored | `object Functions { sin cos tan … 60 of them }` | split by family (trig, log/exp, stat, …) across files/objects |
| `LongParameterList` | function **8**, constructor **10**; **defaulted params not counted** | placement maths (`ContextBarPlacement.place` carries `@Suppress("LongParameterList")`) | pass a small `data class Viewport` instead of suppressing |
| `LargeClass` | 700 | a single `Engine` class | the reducer + per-mode handlers |
| `ReturnCount` | 5 (override) | input editing guards | — |
| `ThrowsCount` | 2 | — | errors are values anyway |
| `LoopWithTooManyJumpStatements` | 1 | numeric solvers with `break`/`continue` | restructure |
| `MaxLineLength` | 120 (comments excluded) | — | — |
| `WildcardImport`, `UnusedPrivateMember` | active | — | — |

Formatting is ktlint 1.8.0, `ktlint_code_style = intellij_idea`, trailing commas allowed
(`.editorconfig`). Run `./gradlew spotlessApply` before `spotlessCheck`.

---

## 3. Where the button goes

### 3.1 The editor today: five zones, two of them full in portrait

From `InkScreen.kt` and `docs/M10-PORTRAIT.md` §1 (emulator `Test_Tablet`, 1,280 × 800 dp in
landscape, 800 × 1,280 dp in portrait):

| Zone | Composable | Natural width |
|---|---|---|
| Top centre | `EditorDock` (`ink/EditorDock.kt`). Row 1 has eight chips plus `RULER`; row 2 swaps with the mode | row 1 **621 dp**, row 2 915 dp (row 2 scrolls in portrait) |
| Top right, the design's *"Global Utilities (Top Right)"* | `RecordButton` (`ink/RecordButton.kt`): `GlassBar` + one `TextChip("Record")` | **78 dp** |
| Bottom left / centre / right | `BottomWidgets` → `HistoryWidget` · `PageBar` · `ZoomWidget`, **one `Row`** | 143 + 598 + 390 dp, which is **1,211 dp** with gutters against 1,280 |
| **Top left** | **empty** | — |

In portrait, dock row 1 runs from x = (800 − 621)/2 = **89.5 dp** to 710.5 dp, and `RECORD` begins
at about 710 dp. They **abut**. M10-PORTRAIT §3.2–3.3 records two attempts to fix that crowding,
and both were reverted, because the user's requirement is that **landscape stays exactly as
shipped**.

### 3.2 The options, with the cost of each

| Option | Landscape | Portrait | Verdict |
|---|---|---|---|
| **A. `CALC` toggle in dock row 1 after `RULER`.** This follows the Ruler precedent: a non-exclusive toggle beside the tools, `EditorDock.kt` line ~124. | Row 1 grows about 65 dp, so the dock re-centres and **every row-1 chip moves** about 33 dp. That breaks the "0 moved" diff (M10-PORTRAIT §4). | Row 1 reaches about 55 → 745 dp and runs **under `RECORD`** (about 710 → 788) by about 35 dp. `RecordButton` is composed after the dock, so it covers and **swallows taps on `CALC`**. That is the M10 §5.1 bug class. | Rejected unless the user accepts moving the dock. Fixing the overlap means the two previously-reverted fixes. |
| **B. Beside `RECORD`** (a top-right `Row`). This is the design's utility zone, and the semantically right one, because the calculator is not a pen tool (the KDoc of `RecordButton`). | Fits (zone about 1,120 → 1,268 dp; dock ends at 950.5). | The zone grows left to about 640 dp and **covers `RULER`** on dock row 1 (ends at 710.5). Reachability regression. | Rejected for portrait. |
| C. Stack under `RECORD` at `TopEnd`. | Fits. | Dock row 2 fills the whole width in portrait (it scrolls) at y ≈ 66 dp, so they **collide**. | Rejected. |
| D. Into `HistoryWidget` / `ZoomWidget`. | **1,211 + about 65 > 1,280**: the page bar starts scrolling **in landscape**. | Centre zone shrinks further from 188 dp. | Rejected. |
| E. Behind the zoom widget's `⋯` (`DiagnosticsPanel`). | — | — | Rejected. That panel is SPEC §3.6 instrumentation (`CanvasWidgets.kt` KDoc), not a utility. |
| F. A new `EditorTool.CALC`. | — | — | **Rejected outright.** A calculator is not a pen tool. Like the ruler (`EditorTool` KDoc), drawing must continue while it is open. `ownsTouch` would also make `PageOverlay` cover the surface. |
| **G. A one-chip `GlassBar` `CALC` at `Alignment.TopStart`** | 12 → about 72 dp. The dock starts at 329.5. Clear. | 12 → about 72 dp against the dock at 89.5, about **17 dp clear**. Height 46 dp (`ChipMinHeight` 34 + 2 × `BarPadding` 6) ends at y ≈ 58. Row 2 starts at y ≈ 66 (`DockRowGap` 8). Clear. `ContextBars` never rises above `DockSafeTop` = 78 dp. Clear. | **Recommended.** No existing control moves. |

The width of G is **estimated** from `RECORD` = 78 dp for six letters, so four letters come to about
60 dp. Confirm it with `uiautomator dump` in both orientations
(`adb shell settings put system user_rotation 1`), and diff the existing controls against a dump
taken before the change: M10-PORTRAIT §4's **41 landscape / 34 portrait controls, 0 moved**, plus
one new one.

If `CALC` does not fit in portrait, use a shorter word first. The design is words, not icons
(M10 §1, §7.1). If a symbol is ever chosen, it must be `ChipKind.GLYPH` **with
`contentDescription`** (`Glass.kt` `TextChip` KDoc: required for every glyph chip, both for
TalkBack and for `uiautomator dump`).

**This is a design departure.** The brief defines the zones and has no top-left zone. Say so to the
user and get their approval. M10's rule is that the reference HTML is the spec.

Code shape for G, inside `TopChrome` (`InkScreen.kt` 375–405). `TopChrome` has 7 parameters and
about 28 code lines, so there is room. The chip fetches its own view model, so no new parameter
is needed:

```kotlin
// ⭐ The calculator's chip — TopStart, the one zone the design leaves empty (docs/calculator/…).
CalculatorChip(Modifier.align(Alignment.TopStart).padding(edge))
```

```kotlin
@Composable
fun CalculatorChip(modifier: Modifier = Modifier, calc: CalculatorViewModel = hiltViewModel()) {
    val open by calc.open.collectAsState()
    GlassBar(modifier = modifier, scrollable = false) {
        TextChip(label = "Calc", selected = open, onClick = calc::toggle)
    }
}
```

### 3.3 The library

`LibraryScreen.kt` 102–132: the root `Box` holds the dot grid, the `LibraryChrome` column (top,
whose toolbar already overflows in portrait and loses `TRASH`) and `CreateBar` at `BottomEnd`. The
KDoc of `CreateBar` (line 159) says *"Three chips and no more"*. **`BottomStart` is free.** Put the
same `CalculatorChip` there and a `CalculatorLayer(onInsert = null)` last in the `Box`.

Both screens call `hiltViewModel<CalculatorViewModel>()` and so reach the same singleton. The
calculator you used in the library is the one you open in a note, with the same Ans and the same
memories, like a calculator on the desk. Whether its *open/closed* state is shared between screens
is an open question (§10).

---

## 4. Window behaviour

### 4.1 An in-window composable, never a `Popup`, `Dialog` or `ModalBottomSheet`

M10-DESIGN §7.3 and the KDoc of `InkScreen.ColourPopover`: *"Not a `Popup`. A popup is a second
window, and this screen's wet ink is a front-buffered `SurfaceView` that SurfaceFlinger composites
over the app's window … window ordering against it is exactly the thing this codebase has been
bitten by twice. An in-window layer is ordered by composition."* A `Dialog` is also a second window,
and it is modal. A bottom sheet is modal and has a scrim.

### 4.2 Non-modal: **no scrim**

The two existing summoned panels, `ColourPopover` and `SizePopover` (`InkScreen.kt` 498–586), lay a
full-screen `Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { onDismiss() } }`
under the card. A tap anywhere closes the panel and **does not reach the page**. **Do not copy
that.** A calculator is used *while writing*: read a number off the page, compute, write the answer
down. A scrim would make every pen-down outside the window a dismiss. The window must consume
pointers **only inside its own bounds**. It closes through its `✕`, the `CALC` chip, or Back
(§4.7).

### 4.3 Z-order inside `InkScreen`'s root `Box`

Composition order in a `Box` is both draw order and hit-test order. `EditorCanvas`'s KDoc
(`InkScreen.kt` 238–240) warns that re-ordering *"is how the app loses either its keyboard or its
pen"*. The recommended order:

```
EditorCanvas(...)          // dot grid, InkSurface (AndroidView), ObjectLayers (PageOverlay, crop, text field, ElementPanel)
CalculatorLayer(...)       // NEW — above every page layer, so it works in TEXT / ELEMENTS mode too
TopChrome(...)             // ContextBars, EditorDock, RecordButton (+ CalculatorChip)
BottomWidgets(...)         // history · page bar · zoom
SidePanels(...)            // diagnostics + colour/size popovers (with their scrims)
DragPreview                // colour drag blob, last
```

Why this order:

- It sits **above `ObjectLayers`**. With the Text or Elements tool active, `PageOverlay` covers the
  whole surface with a `pointerInput` (`EditorTool.ownsTouch`). A calculator below it would be dead
  in those modes.
- It sits **below the chrome**, so the dock, history, page bar and zoom are never covered by a
  window the user dragged there. M10 §5.1 is exactly this bug: `SHARE` was *"drawn, listed by
  `uiautomator`, and covered by a pill that swallowed the tap"*. Clamping (§4.5) keeps the window
  between the dock and the bottom zone, so the chrome cannot hide the window either.
- It sits **below the popovers' scrims**. With the colour panel open, a press on the calculator
  closes the panel. This is consistent with *"closes on a press anywhere else"* (M10 §7.3).
- **InkScreen has one line of `LongMethod` headroom.** By count (non-blank, non-`//` lines,
  `InkScreen.kt` 93–161) there are **58 code lines** against a threshold of 60, and its own KDoc
  says *"exactly at detekt's `LongMethod` limit"*. The calculator therefore enters `InkScreen` as
  **one statement on one line**:
  ```kotlin
  CalculatorLayer(onInsert = rememberCalculatorInsert(editor, ui, objects, objectActions, surface))
  ```
  Everything else lives in the `calculator/` package.

### 4.4 Touch routing over the ink surface

How Compose and `InkSurfaceView` divide the input:

- **Compose hit-tests top-down and stops at the first sibling it hits.** The KDoc of `PageOverlay`
  says: *"Compose hit-tests the topmost node first, and the surface below never sees the event."*
  So if the window's root carries any `pointerInput`, its **entire rectangle** belongs to the
  window. The gaps between keys, the LCD and the frame then cannot pass a stylus through to the
  page.
  - **WARNING: without that root `pointerInput`, a stylus between two keys draws a stroke *under*
    the calculator.** The ink is journaled and synced, and the user cannot see it. Put a
    consume-everything handler on the window body:
    ```kotlin
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false).consume()
            do {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
    ```
    Children (the keys) see the Main pass before their ancestor, so consuming in the body does not
    stop a key's `clickable` from firing. The pattern is `CropOverlay.kt` around line 140 and
    `PageOverlay.boxGesture`.
- **Keys are ordinary Compose `clickable`s.** They take stylus and finger alike. `PalmGuard`
  (`android/ink/.../PalmGuard.kt`) belongs to `InkSurfaceView`. It does **not** guard Compose
  controls, and its state is `internal`.
  - **WARNING: palm on the keypad.** A right-hander writing beside the window can rest a palm on
    it. Android's API 33+ palm rejection arrives as `ACTION_CANCEL` / `FLAG_CANCELED`, and Compose
    cancels the press on that. A palm Android does *not* flag would press a key. Options: accept
    it, as a real calculator does; or treat `PointerType.Touch` presses as valid only when no stylus
    event has occurred within `PalmGuard.GRACE_MS` (300 ms). The second needs a read-only hook out
    of `:android:ink`, for example `InkSurfaceView.lastStylusEventUptimeMs`, because the surface
    sees stylus hover and the window does not. **This can only be judged on the tablet** (DoD #4).
    It is open question §10.
- **A stroke that starts on the page keeps going under the window.** Compose keeps a pointer's hit
  path from its DOWN, so the surface receives the whole gesture. The dry stroke then lies under the
  window. While the stroke is live, its **wet ink will be drawn over the window**, because
  `InProgressStrokesView` front-buffers through a `SurfaceView` composited **over** the app window
  (CLAUDE.md; SPEC §3.2; M10 §2). Every existing piece of chrome has the same property today.
  Expected; check it on the tablet.
- **Hover:** S Pen hover over the window goes to the window, not the surface. So `PalmGuard` is not
  refreshed while the pen hovers over the calculator. This is minor; note it if palm panning is
  reported.
- **Two-finger undo** (`MultiFingerTapDetector`) only sees fingers that land on the surface.
  Fingers on the window are the window's.
- **`adb` instrument note:** the window's keys respond to plain `adb shell input tap` because they
  are Compose controls. The *page* needs `input stylus tap` / `input stylus swipe` (CLAUDE.md; M10
  §9.5). Use `input stylus swipe` across the window body to prove the stylus does **not** draw
  through (§9).

### 4.5 Drag, clamp and rotation

- **Drag handle:** a strip at the top of the window using `detectDragGestures` with
  `change.consume()`. The pattern is `TextBoxLayer.kt`'s `Grip` (lines 221–260). Its
  `onDragEnd`/`onDragCancel` commit pattern is the place to persist the position.
- **WARNING: never key the `pointerInput` on the offset being dragged.** CLAUDE.md: *"Never key a
  `pointerInput` on the value the gesture is editing … tears the handler down mid-drag."* Use
  `pointerInput(Unit)` plus `rememberUpdatedState`.
- **Position via `Modifier.offset { IntOffset(...) }`**, as `AnchoredPanel` and
  `ContextBars.AnchoredBar` do. A lambda offset changes only the placement phase, so there is no
  recomposition per drag frame.
- **Clamp at read, never at write.** Store the user's position unclamped and clamp it on each
  layout against the current constraints. Rotating to portrait and back then returns the window to
  where it was, instead of ratcheting it into a corner. This is the same reasoning as *"the ladder
  is not re-sorted during an edit"* (M10 §10).
- **Hoist the clamp into a pure function with a JVM test.** This is the exact precedent of
  `ink/ContextBarPlacement.kt` and its test `app/src/test/kotlin/dev/inkstone/app/ink/ContextBarPlacementTest.kt`.
  For example, `calculator/CalculatorPlacement.kt` has
  `clamp(position, size, viewport: Viewport): Position` with `top >= DockSafeTop`, `bottom <=
  height − bottomReserve`, `EdgePadding` at the sides, and `size(viewport)` keeping the aspect.
  `DockSafeTop` is `private val DockSafeTop = 78.dp` in `ContextBars.kt` line 545. Make it
  `internal` and share it rather than copying 78. Two copies of one layout constant drift apart.
  - **Review correction (2026-10-03): 78 dp is the wrong line for this window.** It clears dock row 1
    only — right for `ContextBars`, since a contextual bar hides row 2 — but row 2 (the pen's
    brushes, colours, sizes) is on screen in the editor's default state at **66–112 dp**, so a window
    clamped at 78 sat under it, with its drag strip and `✕` beneath the pen row (§4.3's "the chrome
    cannot hide the window" did not hold). The library's chrome is the same two rows. The window
    clamps at `WindowSafeTop` = 12 + 46 + 8 + 46 + 8 = **120 dp** (`CalculatorWindow.kt`), built from
    the dock's tokens; `DockSafeTop` stays private. §4.6's landscape width falls to about 265 dp.
- **Rotation does not recreate the activity.** Both activities declare
  `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|density"`
  (`AndroidManifest.xml` 52, 69). `remember` state survives. Only the constraints change, which the
  clamp handles.
- **First-frame placement:** measure, then show. `AnchoredPanel` (`InkScreen.kt` 595–617) and
  `AnchoredBar` keep the element at `alpha(0f)` until `onSizeChanged` has reported, which avoids a
  visible jump from the corner.
- **"Drop down" + "movable window"** (the user's two words) combine naturally. The window **opens
  hanging from the chip that summoned it**, using `AnchoredPanel`'s below-or-flip arithmetic. It is
  then free to drag, and it remembers where it was left.
  - **WARNING: rebase root coordinates.** A chip's `boundsInRoot()` is in *window* coordinates, and
    `InkScreen`'s `Box` is inset by the `Scaffold` (status bar). Subtract `origin`
    (`InkScreen.kt` 107, 126, 151). M10 §7.2: skipping it *"opens one status bar too low, which
    reads as a rounding error"*.

### 4.6 Size, both orientations

- The real calculator is **161.5 × 77 × 11.1 mm, 95 g** (VERIFIED in the official guide p.91 and Casio's catalogue; the retailer sheet agrees), so its
  aspect is **h/w ≈ 2.10**.
- **Landscape:** the root `Box` is about 800 dp minus the system bars. Between `DockSafeTop` (78 dp)
  and the bottom zone (46 dp pill + 12 dp edge, plus a gap) about **580 dp** of height is left,
  which gives a window about **276 dp wide**. This is estimated; measure the real root height.
- **Portrait:** height is plentiful. The 800 dp width is the limit. Cap the height at about
  700–720 dp, which gives about 340 dp wide.
- **1:1 physical size does not fit in landscape.** On the Galaxy Tab S9 FE+ (12.4 in,
  2,560 × 1,600 px, about 243 ppi) 161.5 mm is about 1,550 px, nearly the full 1,600 px height. It
  fits only in portrait. Scale the face uniformly, with the aspect locked. A corner resize grip, or
  "small / large" presets, is optional; defer it unless asked.
- Lay the face out in **proportional units of the window width**, not fixed dp. The keypad then
  scales as one picture, and the same layout code serves 276 dp and 340 dp.

### 4.7 Dismissal

- `✕` as `TextChip(label = "✕", kind = ChipKind.GLYPH, onClick = …, contentDescription = "Close")`.
  This is exactly how `ElementPanel` and `DiagnosticsPanel` close.
- Pressing the `CALC` chip again toggles it closed. `TextChip(selected = open)` shows the state,
  following the `ROUNDED` toggle precedent in M10 §9.2.
- **Back closes the window first**, with `BackHandler(enabled = open) { close() }`. The precedent is
  `LibraryScreen.kt` line 98. In the editor this is safe, because Back with the window closed still
  falls through to the activity. CLAUDE.md's *"`Activity.onStop` → `viewModelScope.launch { save }`
  loses ink on Back"* is about the close-time save, which this does not touch.

### 4.8 Look: chrome versus face

- **Chrome** (frame, drag strip, `✕`) uses the design system: `GlassPanel` (`Glass.kt` 163). That
  means `Ink.PanelSolid`, a `PanelBorder` hairline, the 16 dp `PanelCorner`, and a 14 dp elevation
  shadow. It is opaque, which matters (next point).
- **Face** (body, LCD, keys): the user's requirement is *"just like the fx-991ZA PLUS II in terms of
  looks"*, so the face is **not themed**. The precedent is `Ink.Paper` (M10 §6): *"Paper is not
  themed … the page is the document, not the chrome."* The calculator face is content in the same
  sense. Make it fully opaque. The emulator's ink surface renders garbage, including fragments of
  the Google logo (CLAUDE.md; M10-PORTRAIT §4), and anything translucent over it will show that
  garbage in screenshots.
- **The LCD is a 31 × 96 dot-matrix** (file 02 §"display"; Casio ZA comparison chart), plus a separate
  row of 18 segment status icons above it (01 §3.2). Glyph metrics for drawing it are in 01 §3.6
  (small font 5×6 in a 6×8 cell, large font 5×9 on a 6-dot advance). **Do not draw
  2,976 `drawRect`/`drawCircle` calls per frame.** M10's dot grid was about 7,000 draw calls until
  `rememberDotGridBrush` (`Glass.kt` 652–677) baked a tile into an `ImageBitmap`. Instead, render
  the LCD's pixels into a **96 × 31 `ImageBitmap`** when the state changes, and draw it scaled with
  `FilterQuality.None`. That is one textured rectangle, and it sits next to the ink path (rule 7).
- **The cursor blink** should run in the **draw phase**: read the blink phase inside `drawBehind` or
  `drawWithCache`, not in composition. A twice-a-second blink then never recomposes `InkScreen`.
- **Fonts:** the app has no `res/font/` today. `ReadoutFamily` is `FontFamily.Monospace`
  (`InkstoneTheme.kt` 149). Casio's LCD glyphs and key legends are Casio's artwork. Draw your own
  bitmap glyphs (look research), and do not copy their font or logo (open question §10).
- **Semantics:** a Canvas-drawn LCD is **invisible to `uiautomator dump`**. Give it
  `Modifier.semantics { contentDescription = linearText }`, and give each key a stable
  `contentDescription` (`"SHIFT"`, `"sin"`, `"x squared"`). DoD #6's adb check is the main reason;
  TalkBack is the other. Driving it from Windows with `tools/emulator-ui.py` needs
  `PYTHONIOENCODING=utf-8`. The README notes that cp1252 raises on `✎ ⇩ ›`, and it will raise on
  `√ π ÷` too.

### 4.9 Who owns the state

- **`CalculatorController`, `@Singleton`, with its own `CoroutineScope(SupervisorJob() +
  Dispatchers.Default)`.** Copy `di/RecordingModule.kt` → `provideRecordingController` and
  `di/PreferencesModule.kt` → `provideEditorPreferences`. Both carry a KDoc explaining why the scope
  is their own and never cancelled. Put it in **`di/CalculatorModule.kt`**. Convention from the
  KDoc of `PreferencesModule`: *"the milestone that adds a subsystem should be findable by deleting
  one file."*
  - Why a singleton: the calculator outlives a note (closing a note clears `NoteEditorViewModel`),
    and it is shared with the library. Also *"`ViewModel.onCleared` cannot suspend"* (CLAUDE.md,
    M12), because `viewModelScope` is already cancelled by then.
- **`CalculatorViewModel` (`@HiltViewModel`)** is a pass-through to the controller (`open`,
  `state`, `press(key)`, `toggle()`). **Do not grow `NoteEditorViewModel`.** It already carries
  `@Suppress("LongParameterList", "TooManyFunctions")` with 14 constructor parameters, and a
  calculator has nothing to do with the note.
- **Keep calculator state out of `InkUiState` / `EditorPrefs`.** `rememberInkUiState` runs
  `snapshotFlow { ui.toPrefs() }`, and the KDoc of `toPrefs` restricts it to persisted toolbar
  fields: every state read inside it re-runs the flow and writes the settings row.
  `ObjectToolState` is also wrong, because it is `reset()` on every page turn and tool change.
- **Serialise key presses.** Use a single-consumer `Channel<Key>` drained by one coroutine that runs
  the pure reducer. CLAUDE.md (M8 follow-up 6): *"An unbounded `launch` per press turns one slow
  operation into a burst."* A slow ∫ must not let later keys overtake it. `AC` cancels the
  in-flight evaluation, as on the real calculator, through the reducer's `interrupt` callback.
  Never evaluate on the main thread (rule 7, SPEC §2.4).
- **Persist through `SettingStore` (`core/database/.../settings/SettingStore.kt`) under `calc.*`
  keys.** `Setting.sq` explains that the generic table is right exactly when the key space is
  **closed and known at compile time**. That holds here: SETUP items (angle unit, MthIO/LineIO,
  number format and digits, fraction format, complex form, STAT frequency, TABLE, APO, contrast),
  mode, the nine variables **A B C D E F X Y M** (file 02; ZA comparison chart: *"Variable Memory
  9"*), **Ans** and **PreAns**. Write values in the engine's own lossless decimal text, never
  through `Double.toString`. Copy the shape of `ink/EditorPrefsCodec.kt` (keys `ink.*`, lines
  159–170) and the debounce, seed-once and `flush()` of `ink/EditorPreferences.kt`. Add
  `calculatorPreferences.flush()` beside `editorPreferences.flush()` in **both** activities'
  `onStop` (`NoteActivity.kt`, `MainActivity.kt` line ~191).
  - **WARNING: `setting` is two columns forever** (`Setting.sq`). `INSERT OR REPLACE` resets any
    omitted column. Anything that looks like a third column is a new key.
  - **WARNING: `putAll` is one transaction**, and keys absent from the map are left alone, so `calc.*` writes
    cannot disturb `ink.*`.
- **The calculator writes nothing to the document.** It makes no op, no journal record and no undo
  entry, the same as the laser (`EditorTool.LASER`). Its own `DEL` and history-replay are
  independent of the note's undo. The **insert** action is the only document write (§5).

---

## 5. "Insert result into note"

### 5.1 The API that already exists

- **There is a text-box object type with a create API built for exactly this.**
  `core/model/.../TextBoxEdit.kt` → `TextBoxEdit.create(id, text, widthPt, heightPt, transform, z,
  style, format, nextOpId, nextClock)` returns `AddObject` + `SetTextContent` (+ `SetFormat` if not
  default), as **one gesture**.
- `:app` reaches it through
  `NoteEditorViewModel.insertTextBox(box: PageRect, text: String, style: ObjectStyle, format: ObjectFormat): ObjectId?`
  (`note/NoteEditorViewModel.kt` 317). That calls `NoteEditor.insertTextBox` (`note/NoteEditor.kt`
  1432), which runs under the editor's lock with `saveLocked()`, `ids.newObjectId()`,
  `Matrix3.translate(box.left, box.top)`, `z = SelectionEdit.keyAbove(topZ(before))` and
  `afterEdit`. It is synced through the outbox like any edit (rule 4). **Nothing new is needed in
  `NoteEditor`.**
- **Create it with the text already in it.** That is the API's whole design. The KDoc of
  `TextBoxEdit.create` says that a box created **empty** and typed into separately has no inverse
  for its first `SetTextContent`, so *"type a word, press undo"* would delete the box. A calculator
  result is known up front, so this is natural. Do not call `insertTextBox` with `""` and then
  `updateText`.
- **`style` must be non-null:** pass `ObjectToolState.DEFAULT_TEXT_STYLE`
  (`ink/ObjectToolState.kt` 347). SPEC §4.9.2: a box with a null style has no inverse for its first
  recolour.
- **Placement:** `visiblePage(camera, view.width, view.height, view.pageCanvas)` (public,
  `ink/ObjectLayers.kt` 218) gives the page-space rectangle on screen, clamped to the paper,
  including a grown infinite canvas. `insertPicture` (`InkScreen.kt` 755–769) is the template. The
  default box size is `NEW_BOX_WIDTH_PT = 220f` / `NEW_BOX_HEIGHT_PT = 44f`, which are `private` in
  `ObjectLayers.kt` 234–235. Make them `internal`; do not copy them.
  - **WARNING: do not drop the result under the calculator.** The visible centre is often behind the
    window itself, and the result would land *"where they were not looking"* (KDoc of
    `visiblePage`; CLAUDE.md's ruler lesson, *"a tool's chrome must not cover the thing the tool
    produces"*). Convert the window's rectangle into page space with
    `viewToPage(ViewOffset(x, y), ui.camera)`, then choose a box in the visible page that misses it,
    for example beside the window on the larger side. The window offset is **root-`Box`-local**,
    which is the surface's own view space, because the `AndroidView` fills that `Box`. Hoist the
    choice into a pure `CalculatorInsert.boxFor(visible, windowInPage, size)` with a JVM test.
- **Insert into the box being typed, if there is one.** When `objects.editing != null`, append to
  `objects.draft` and commit through `objectActions.onTextChanged(editing.id, draft)`. That is the
  existing path: `commitText` (`InkScreen.kt` 814–825) creates the object if `id` is still null,
  which is the "placed, not yet typed" state. The field keeps focus, because a Compose `clickable`
  does not take focus in touch mode.
- **Where the lambda is built:** `rememberCalculatorInsert(editor, ui, objects, objectActions,
  surface)` in the `calculator/` package. **Do not add it to `ObjectActions`.** That class has **9**
  constructor parameters, and `constructorThreshold` is **10**.
- **Feedback:** the box appears; optionally `editor.report("…")` (the editor's snackbar,
  `NoteEditorViewModel.kt` 374). CLAUDE.md (grid-snap lesson): *"a one-shot action whose feedback
  is gated on a mode is a button that appears to do nothing."*
- **In the library**, `onInsert = null`, and the insert control is **absent, not greyed** (M10
  §9.4).

### 5.2 Which characters survive into the PDF (measured)

The editor draws a text box with Android's own fonts. The **published PDF** uses
`android/pdf/.../PdfTextLayout.kt` + `PdfCidFont.kt`. A code point the embedded face cannot
represent is **dropped** (`PdfTextLayout.kt` 115–120; `PdfCidFont.kt` 97–102), *"reported, never
guessed"*. So a character can be **visible on screen and missing in the file**. That is
CLAUDE.md's oldest failure in a new place.

Measured on 2026-10-02 with fontTools 4.63.0, against the faces inside
`pdfbox-android-2.0.27.0.aar` (the version this project pins):

- **Only `LiberationSans-Regular.ttf` (410,712 bytes) ships in the AAR.** `unzip -l` lists one
  TTF.
  - `PdfFontFaces.kt` also names `LiberationSerif-Regular.ttf` and `LiberationMono-Regular.ttf`.
    They are **absent**, so a `SERIF` or `MONO` text box falls back to standard-14 Times or Courier
    with `canRepresent = printable ASCII only`.
  - **Do not insert results with `TextFont.MONO`, however natural it looks for numbers.**
    `× ÷ − √ π ²` would all vanish from the PDF.
- `LiberationSans-Regular` coverage:

| Present | Absent (dropped from the PDF) |
|---|---|
| `×` U+00D7, `÷` U+00F7, `−` U+2212, `√` U+221A, `π` U+03C0, `²` `³` `¹`, `°`, `∫`, `Σ` U+03A3, `∑`, `∞`, `≈`, `θ`, `μ`, `·` U+00B7, `ⁿ` U+207F, `⁄` U+2044, `ˉ` U+02C9, combining macron U+0304 | **`⁻` U+207B** (superscript minus, so `x⁻¹` and `10⁻³` lose it), **`∛` U+221B**, **`∠` U+2220** (CMPLX polar), **`ℯ` U+212F**, **`⋅` U+22C5**, and anything like a `×10` ligature |

Consequences:

- The insert path needs a **linear, PDF-safe text form** from the engine. Examples: `1.25×10^-3`,
  or the plain `0,00125` the display shows; `8^(1/3)` or `³√8`; `r∠θ` written as `r(cosθ+i·sinθ)`
  or `r ang θ`. Assert it with a JVM test whose alphabet is the "present" column.
  `PdfCidFont.canRepresent` is `internal` to `:android:pdf`, so `:app` cannot call it. Pin the
  alphabet as a constant with a comment pointing here.
- **Decimal comma.** File 02 Z3/Z4: the ZA PLUS II displays `0,00125` and has **no Dot/Comma
  setting**. Whether an inserted result keeps the comma, so that it matches the LCD, or uses a point
  is open question §10. A comma also affects search tokenisation of the text layer (SPEC §5.5).

---

## 6. Exact files to touch

### 6.1 New

| Path | What |
|---|---|
| `core/calc/build.gradle.kts` | `plugins { id("inkstone.kmp-core") }` |
| `core/calc/src/commonMain/kotlin/dev/inkstone/core/calc/**` | the engine (§1.2) |
| `core/calc/src/commonTest/kotlin/dev/inkstone/core/calc/**` | table-driven vectors (file 04), names `[A-Za-z0-9 _-]` |
| `app/src/main/kotlin/dev/inkstone/app/calculator/CalculatorController.kt` | singleton: engine state `StateFlow`, key channel, open/position, persistence hooks |
| `…/calculator/CalculatorViewModel.kt` | `@HiltViewModel` pass-through |
| `…/calculator/CalculatorPreferences.kt` + `CalculatorPrefsCodec.kt` | `calc.*` keys; debounce + `flush()` (copy `EditorPreferences` / `EditorPrefsCodec`) |
| `…/calculator/CalculatorLayer.kt` | `BoxScope.CalculatorLayer(onInsert: ((String) -> Unit)?)`: renders the window when open, plus `BackHandler` |
| `…/calculator/CalculatorWindow.kt` | `GlassPanel` frame, drag strip, `✕`, body `pointerInput` consume, offset + clamp |
| `…/calculator/CalculatorFace.kt`, `CalculatorKeypad.kt`, `CalculatorLcd.kt` | the Casio face; LCD to `ImageBitmap`; key semantics |
| `…/calculator/CalculatorChip.kt` | the `CALC` toggle pill (both screens) |
| `…/calculator/CalculatorPlacement.kt` | pure clamp + size (copy `ContextBarPlacement`) |
| `…/calculator/CalculatorInsert.kt` | `rememberCalculatorInsert(...)` + pure `boxFor(...)` + PDF-safe alphabet |
| `app/src/main/kotlin/dev/inkstone/app/di/CalculatorModule.kt` | provides controller + preferences, own scope |
| `app/src/test/kotlin/dev/inkstone/app/calculator/CalculatorPlacementTest.kt`, `CalculatorPrefsCodecTest.kt`, `CalculatorInsertTest.kt` | JVM tests of every hoisted decision |
| `docs/CALCULATOR.md` | the decisions doc (per-claim table: written / automated / run on emulator / run on tablet), per CLAUDE.md DoD #6 |

### 6.2 Modified

| Path | Change |
|---|---|
| `settings.gradle.kts` | `include(":core:calc")` |
| `build.gradle.kts` (root) | add `":core:calc"` to `coreModules` (**guardrail 2 coverage**) |
| `app/build.gradle.kts` | `implementation(project(":core:calc"))` |
| `app/.../ink/InkScreen.kt` | **one line** `CalculatorLayer(...)` after `EditorCanvas(...)` (LongMethod headroom = 1 line); `CalculatorChip(...)` inside `TopChrome` |
| `app/.../ink/ContextBars.kt` | `DockSafeTop` `private` → `internal` (shared with the clamp) |
| `app/.../ink/ObjectLayers.kt` | `NEW_BOX_WIDTH_PT` / `NEW_BOX_HEIGHT_PT` `private` → `internal` |
| `app/.../library/LibraryScreen.kt` | `CalculatorChip` at `BottomStart`; `CalculatorLayer(onInsert = null)` last in the root `Box` |
| `app/.../note/NoteActivity.kt`, `app/.../MainActivity.kt` | inject `CalculatorPreferences`; `flush()` in `onStop` |
| `SPEC.md` §2.1, `README.md` *Module graph* | add `:core:calc` |
| `CLAUDE.md` *Quick reference* | one row → `docs/CALCULATOR.md` |
| *(only if the palm policy in §4.4 is chosen)* `android/ink/.../InkSurfaceView.kt` | a read-only `lastStylusEventUptimeMs`. No allocation; rule 7. |

### 6.3 Deliberately not touched

`NoteEditor` (its insert API already exists), `NoteEditorViewModel` (already at its suppressed
limits), `EditorTool` (a calculator is not a tool), `EditorDock` (option G leaves the dock alone),
`InkUiState` / `EditorPrefs` (not toolbar state), `ObjectActions` (at 9/10 parameters), and
`ObjectToolState` (reset on every page turn).

---

## 7. Patterns to copy, by path

| Need | Copy from |
|---|---|
| Singleton with its own never-cancelled scope | `app/.../di/RecordingModule.kt` → `provideRecordingController`; `app/.../di/PreferencesModule.kt` |
| Settings codec + debounce + seed-once + `flush` | `app/.../ink/EditorPrefsCodec.kt`, `app/.../ink/EditorPreferences.kt`, `core/database/.../settings/SettingStore.kt`, `Setting.sq` |
| A floating panel's look | `ui/Glass.kt` `GlassPanel` (163); `elements/ElementPanel.kt` (280 dp side panel, `MicroLabel` header, `✕` glyph chip) |
| A chip / toggle | `ui/Glass.kt` `TextChip` (220), `ChipKind` (189); `ink/EditorDock.kt` Ruler toggle (~124) |
| A one-chip utility pill | `ink/RecordButton.kt` (`GlassBar(scrollable = false) { TextChip(...) }`) |
| Placement maths as a pure function + test | `ink/ContextBarPlacement.kt` + `app/src/test/.../ink/ContextBarPlacementTest.kt` |
| Measure-then-show anchored placement | `InkScreen.kt` `AnchoredPanel` (595–617); `ContextBars.kt` `AnchoredBar` (146–188) |
| Drag gesture with consume + commit on end/cancel | `ink/TextBoxLayer.kt` `Grip` (221–260) |
| Consume-all gesture loop | `ink/CropOverlay.kt` (~140), `ink/PageOverlay.kt` `boxGesture` |
| Bitmap instead of thousands of draw calls | `ui/Glass.kt` `rememberDotGridBrush` (652–677) |
| Insert-at-visible-page + report | `InkScreen.kt` `insertPicture` (755–769), `ink/ObjectLayers.kt` `visiblePage` (218) |
| Text-box creation as one gesture | `core/model/.../TextBoxEdit.kt` `create` (66); `note/NoteEditor.kt` `insertTextBox` (1432) |
| Back handling | `library/LibraryScreen.kt` line 98 |
| Theme tokens | `ui/InkTokens.kt`: `Ink.PanelSolid`, `Ink.PanelBorder`, `Ink.Accent`/`OnAccent` (active chip), `Ink.TextStrong/Normal/Muted`, `Ink.Danger`; `InkDims.EdgePadding` 12 dp, `PanelCorner` 16 dp, `ChipMinHeight` 34 dp, `ChipGap` 4 dp, `GroupGap` 8 dp; `InkType.ChipSize` 13 sp, `GlyphSize` 19 sp |

---

## 8. CLAUDE.md rules and lessons that apply here

1. **Rule 2 / guardrails.** `:core:calc` must stay platform-free *and* be in `coreModules`, or
   `linkIosCore` never sees it (§2.1).
2. **Rule 3. Never invent a version.** Prefer zero new dependencies. If a bignum or font library is
   ever proposed, cite its release page.
3. **Rule 4. Sync goes through the outbox.** The only document write, insert, uses
   `NoteEditor.insertTextBox`, which journals and enqueues like every edit. The calculator never
   touches the network.
4. **Rule 7. Nothing heavy on the ink path.** Evaluate off the main thread, render the LCD as one
   bitmap, blink in the draw phase, and add no root-level per-event work over the surface.
5. **DoD #6. Inert features.** For every entry point (chip, insert, Back, persistence), grep for its
   production caller up to something a user can press. *"A test is the caller"* is the illusion to
   avoid.
6. **"Hoist the decision into a pure function"**: placement, insert box, PDF-safe text, prefs
   codec. `:app` composables cannot be asserted in a JVM test.
7. **"Verify output with a reader that is not the writer."** Check an inserted result in the
   *published PDF* with `pdftotext` (§9), not by re-reading the register.
8. **"A KDoc explaining why the wrong thing is right is the expensive kind."** Do not write *"the
   PDF can't show ⁻ so it doesn't matter"*. Restrict the alphabet, or add the coverage.
9. **"Never key a `pointerInput` on the value the gesture is editing."** (§4.5)
10. **"Anchor arrives in root coordinates and has to be rebased."** (§4.5, M10 §7.2)
11. **"An unbounded `launch` per press turns one slow operation into a burst."** Serialise keys (§4.9).
12. **"`ViewModel.onCleared` cannot suspend."** Hence the singleton controller with its own scope.
13. **"`INSERT OR REPLACE` resets any column the statement omits."** Use new `calc.*` keys and never
    a third column.
14. **"`--rerun` is per task"; "a green `build` routinely executes zero tests"; "N actionable tasks
    is not a test count."** (§2.2)
15. **Test names:** commas are illegal on Kotlin/Native, `/ : . [ ] < > ;` are illegal on the JVM,
    and apostrophes are illegal in dex (§2.3).
16. **Emulator truths:** `input tap` works on Compose keys, but the page needs `input stylus`. A
    0-byte `uiautomator dump` makes every grep "pass". The ink surface renders garbage on x86_64, so
    judge nothing about rendering over the page from an emulator screenshot. On a cold emulator,
    `cmd package compile -m speed -f dev.inkstone` prevents the startup ANR. `connectedAndroidTest`
    uninstalls the app, so do adb walks **after** it.
17. **Portrait is a separate verification** (M10-PORTRAIT). A control past the end of a pill is
    absent from the dump. Check both orientations.
18. **"A layout fix for an appearance problem is not a reachability fix"** (M10-PORTRAIT §3.3). If
    `CALC` merely *touches* the dock in portrait, that is appearance. If it *overlaps*, that is
    reachability.

---

## 9. Verification plan (DoD #4 and #6, per claim)

| Claim | Instrument | Where |
|---|---|---|
| Engine answers match the guide | `:core:calc:testDebugUnitTest --rerun --no-build-cache`; counts from XML | JVM (+ iOS sim on macOS CI) |
| Engine has no JVM-only API | `:core:calc:compileKotlinIosSimulatorArm64` green; `linkIosCore` on CI | Windows / macOS |
| `CALC` reachable, nothing moved | `uiautomator dump` diff, landscape + portrait (`user_rotation 1`): 41 / 34 shipped controls **0 moved**, `Calc` present | emulator |
| Keys work; result readable | `input tap` on `Calc`, then `1`, `+`, `2`, `=` by `contentDescription`; read the LCD's semantics text from the dump | emulator |
| **Stylus does not draw through** | with the window open: `input stylus swipe` across the window body *between keys*; diagnostics `revision rN` unchanged, and no new `add` in `journal.ops` (`adb exec-out run-as dev.inkstone cat …`). The positive control is the same swipe beside the window, which **must** add a stroke, so the test cannot pass vacuously (CLAUDE.md, the gate test checked by disabling it) | emulator |
| Drawing still works beside the window | `input stylus swipe` outside, rN increments | emulator |
| Drag + clamp + rotation | drag the strip; rotate both ways; dump bounds inside the root `Box` and below 78 dp | emulator |
| Insert creates one text box, one undo | press INSERT; dump shows the box; `UNDO` removes it in one press; `setting`/journal via `run-as` | emulator |
| Inserted text survives into the PDF | `SHARE` → PDF, pull it, `pdftotext` / `pdfgrep` for the exact string (√, π, ×) | emulator + desktop |
| State survives app restart | set `A`, `am force-stop`, reopen; `calc.*` rows via `run-as … cat databases/inkstone.db` | emulator |
| Same calculator in the library | open from the library, compute, open a note: Ans carried over | emulator |
| Palm on the keypad; S Pen taps on keys; wet ink over the window; real-size feel | by hand | **tablet only** (DoD #4, `tools/send-to-tablet.ps1`, release build by default) |

---

## 10. Open questions for the user

1. **Placement:** is a new top-left `CALC` pill acceptable as a design departure (§3.2 G)? Or should
   it sit in dock row 1 beside `RULER` (option A), which means re-centring the dock and fixing a
   portrait overlap with `RECORD`?
2. **Shared window state:** should an open calculator stay open when moving between the library and
   a note? Should its position be remembered across app restarts, or only its memories and modes?
3. **Palm policy:** may a finger press a key while the S Pen is on or near the glass? If not, a small
   read-only hook from `:android:ink` is needed (§4.4).
4. **Inserted number format:** decimal comma, as the ZA PLUS II shows, or point? Natural-display
   forms (fractions, √) or decimal? Font Sans only (measured coverage), or add a math-capable face?
   Adding one would be a new APK asset, which SPEC §4.9.5 was proud to avoid.
5. **Branding:** matching the layout and function is the goal. Should the `CASIO` wordmark and the
   `fx-991ZA PLUS II` model name appear on the face? Those are Casio's marks, and the LCD font and
   key legends are Casio's artwork. Recommendation: a faithful layout, our own drawing, and no
   trademarks.
6. **Scope staging:** COMP and SETUP first, then CMPLX, STAT, TABLE, EQN, MATRIX, VECTOR, BASE-N,
   DIST, INEQ, RATIO in later sessions? The ~452 functions do not fit in one CLAUDE.md "milestone
   per session".
7. **Size control:** fixed fit-to-height, or a resize grip or presets? The 1:1 physical size fits
   only in portrait (§4.6).

---

## Sources

- In-repo (read 2026-10-02): `CLAUDE.md`; `SPEC.md` §1.2, §2.1–2.4, §3.1–3.3, §4.9.5, §12;
  `ROADMAP.md` (milestone map, working agreements); `README.md` (*Testing on an emulator*, *Module
  graph*); `docs/M10-DESIGN.md`; `docs/M10-PORTRAIT.md`; `settings.gradle.kts`; `build.gradle.kts`;
  `build-logic/src/main/kotlin/inkstone.kmp-core.gradle.kts`, `inkstone.quality.gradle.kts`,
  `inkstone.android-library.gradle.kts`, `dev/inkstone/gradle/CorePlatformFreeCheckTask.kt`;
  `config/detekt/detekt.yml`, `baseline.xml`; `.editorconfig`; `gradle/libs.versions.toml`;
  `core/search/build.gradle.kts`; `app/build.gradle.kts`; `app/src/main/AndroidManifest.xml`;
  `app/src/main/kotlin/dev/inkstone/app/`: `ink/{InkScreen, EditorDock, EditorTool, PanelKind,
  ObjectLayers, PageOverlay, ContextBars, ContextBarPlacement, CanvasWidgets, RecordButton,
  TextBoxLayer, ObjectToolState, ObjectActions, InkUiState, EditorPreferences, EditorPrefsCodec}.kt`,
  `ui/{Glass, InkTokens, InkstoneTheme}.kt`, `elements/ElementPanel.kt`, `note/{NoteActivity,
  NoteEditorViewModel, NoteEditor}.kt`, `library/LibraryScreen.kt`, `MainActivity.kt`,
  `di/{PreferencesModule, RecordingModule}.kt`; `core/model/.../TextBoxEdit.kt`;
  `core/database/.../settings/SettingStore.kt`, `Setting.sq`; `android/ink/.../{InkSurfaceView,
  PalmGuard}.kt`; `android/pdf/.../{PdfCidFont, PdfTextLayout, PdfFontFaces}.kt`.
- Measured: detekt defaults from `default-detekt-config.yml` in
  `~/.gradle/caches/.../detekt-core-1.23.8.jar`. Glyph coverage with fontTools 4.63.0 against
  `assets/com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf` from
  `~/.gradle/caches/.../pdfbox-android-2.0.27.0.aar`, which is the only TTF in that AAR. `InkScreen`
  code-line count by `grep`.
- Sibling: `docs/calculator/research/02-manual-core-and-display.md` (15-digit internals, Norm 2
  default, decimal comma, A–F X Y M memories, PreAns, the official emulator as oracle). The model
  identity, including which international sibling the ZA PLUS II follows, is settled in `00-index.md`
  §1 (cross-check); 02's original "fx-991ES PLUS 2nd edition" inference was revised there.
- Web: CASIO, *fx-991ZA PLUS II User's Guide* (PDF),
  <https://www.casio.com/content/dam/casio/global/support/manuals/calculators/pdf/004-en/f/fx-991ZA_PLUSII_EN.pdf>;
  CASIO Education ZA, *fx-991ZA PLUS 2nd edition*, <https://edu.casio.com/za/products/ntd/fx991za_2/>;
  CASIO Education ZA comparison chart (*"31 x 96 Full Dot Display, Variable Memory 9"*),
  <https://edu.casio.com/za/products/ntd/comparison.html>; James Ralph (Casio SA distributor)
  product page, spec list incl. *"161.5 mm × 77 mm × 11.1 mm"*, *"95 g"*, *"452 functions"*,
  <https://www.casio.jamesralphedu.co.za/products/p/casio-fx-991za-plus-ii-sa-education-advanced-scientific-calculator>.
