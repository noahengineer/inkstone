# M0 — verification record

What was checked, what it said, and what could not be checked here. Written 2026-08-05.

---

## 1. Every pinned coordinate resolves

SPEC §0.2 says: *"If a coordinate doesn't resolve from a clean Gradle cache: report the actual available version with a link to the release page, and stop."*

Each coordinate below was fetched directly from Google Maven (`dl.google.com/dl/android/maven2`) or Maven Central (`repo1.maven.org/maven2`) — `maven-metadata.xml` for the version list, then an `HTTP 200` on the exact POM. **Every pin in SPEC §0.2 resolved. Nothing was substituted.**

| Pin | Result | Note |
|---|---|---|
| Kotlin 2.3.20 | ✅ | 2.3.21 and 2.4.x also released; pin kept |
| AGP 8.13.0 | ✅ | 8.13.1 / 8.13.2 patches also released; pin kept |
| Gradle 8.14 | ⚠️ not verifiable | `services.gradle.org` is unreachable from this machine's network. Known release; the wrapper fetches it on first build |
| `androidx.ink:*` **1.0.0** | ✅ all 7 artifacts | authoring, brush, geometry, rendering, strokes, storage, nativeloader — SPEC §0.1 #1 confirmed, it is stable, not alpha |
| `androidx.graphics:graphics-core` 1.0.4 | ✅ | latest |
| `androidx.input:input-motionprediction` 1.0.0 | ✅ | latest |
| `androidx.pdf:pdf-viewer-fragment` 1.0.0-alpha19 | ✅ | latest |
| SQLDelight 2.3.2 | ✅ | latest |
| Ktor 3.5.0 | ✅ | 3.5.2 released; pin kept per SPEC |
| ML Kit `digital-ink-recognition` 19.0.0 | ✅ | latest |
| `pdfbox-android` 2.0.27.0 | ✅ | latest — still nothing since Jan 2023, as SPEC §6.3 warns |

**"Latest stable at M0" — now resolved and pinned:**

| Item | Pinned | Why this one |
|---|---|---|
| Compose BOM | `2026.06.01` | latest stable |
| WorkManager | `2.11.2` | latest **stable**; 2.12.0-beta01 exists and was not taken |
| Hilt | `2.60.1` | latest stable |
| kotlinx-serialization-json | `1.11.0` | latest stable |
| kotlinx-coroutines | `1.11.0` | latest stable |
| KSP | `2.3.11` | KSP left the `<kotlin>-<ksp>` scheme after `2.2.21-2.0.5`. `2.3.11`'s POM depends on `kotlin-stdlib` **2.3.20** — exact alignment with our Kotlin pin |
| detekt | `1.23.8` | latest release |
| Spotless | `8.9.0` / ktlint `1.8.0` | latest |

`./gradlew verifyCatalogCoordinates` re-runs this check for real, from Gradle, against every catalog entry — including pins no module uses yet (PdfBox, ktor-mock, the ink artifacts M1 needs). It resolves metadata only (`@pom`, non-transitive) so it works from the root project, which has no Android plugin and therefore cannot match AAR variants.

---

## 2. Two corrections to SPEC.md

CLAUDE.md: *"Say when the spec is wrong… reality wins — tell me, and update SPEC.md in the same commit as the fix."* Both are applied to `SPEC.md` in this commit.

### 2.1 The ink Compose artifacts — right conclusion, wrong reason

SPEC §0.2 and §3.2 said the Compose ink modules *"exist only at `1.1.0-alpha06`"*.

Measured:

```
androidx.ink:ink-authoring-compose:1.0.0   -> 200
androidx.ink:ink-rendering-compose:1.0.0   -> 404
```

So `ink-authoring-compose` **does** exist on the 1.0.0 line; `ink-rendering-compose` does not. The practical conclusion is unchanged and still correct — pinning `ink_version = "1.0.0"` across the Compose artifacts, as Google's own setup page does, **does not resolve** — but it fails because the set is incomplete, not because none of them exists. Worth being precise about, because "they only exist at 1.1.0-alpha06" invites someone to move the whole ink stack to alpha to get them, which SPEC §3.2 explicitly does not want.

The decision stands: **stable 1.0.0, View-based, wrapped in `AndroidView`.**

### 2.2 The platform-free guardrail cannot scan `androidMain`

SPEC §2.3 / CLAUDE.md rule 2 say `:core:*` "may not import `android.*` or `androidx.*`".

Taken literally over all source sets, that makes ROADMAP M0's own scope impossible: `:core:database` is required to use SQLDelight, and `AndroidSqliteDriver` cannot be constructed without `android.content.Context`. A KMP module's `androidMain` is Android-by-definition — it is *where `actual` implementations live*.

So the guardrail scans every Kotlin source set **except** the `android*` ones. That is the honest reading of the rule's intent: shared code is platform-free.

This costs nothing that matters, because the import scan was never the load-bearing guardrail. **OkHttp and dav4jvm contain no `android.*` import at all** — they'd pass a scan of every source set, including `commonMain`, while the iPad path was already dead. The check that catches them is the iOS link, and `tools/verify-guardrails.sh ios` demonstrates exactly that: it plants OkHttp in `:core:sync` **commonMain**, shows the iOS link failing, and shows Guardrail 1 staying green the whole time.

---

## 3. What could NOT be verified on this machine

This is the honest part. **The build has not been executed.** Three blockers, all environmental:

| Blocker | Detail | Fix |
|---|---|---|
| **JDK 26** | The only JDK installed is `26.0.1`. Gradle 8.14 supports Java 17–24 and its daemon refuses to start on 25+. The error message does not say so clearly. | Install JDK 21: `winget install EclipseAdoptium.Temurin.21.JDK` |
| **No Android SDK** | No Android Studio, no `%LOCALAPPDATA%\Android\Sdk`. `compileSdk 36` cannot be resolved. | Android Studio, or `cmdline-tools` + `sdkmanager "platforms;android-36"` |
| **Windows host** | Kotlin/Native cannot compile Apple targets off macOS. `linkDebugFrameworkIosSimulatorArm64` cannot run here **at all** — this is a hard platform limit, not a configuration gap. | CI runs it on `macos-latest`. `kotlin.native.ignoreDisabledTargets=true` makes the local build skip those tasks instead of failing configuration. |

**Consequence for ROADMAP M0's acceptance list:** the first four boxes (`./gradlew build`, the iOS link, the two planted-violation demonstrations) are *set up and automated* but **not yet ticked**. They tick the first time CI runs, or locally once a JDK 21 and the Android SDK are installed. The last two — every coordinate resolving, and CI green on a pushed branch — are respectively verified out-of-band above, and pending a push.

Both planted-violation demonstrations run as their own CI jobs (`guardrail-negative-imports`, `guardrail-negative-ios`), so they are proven on every push rather than once by hand.

---

## 4. Decisions taken, with reasons

| Decision | Reason |
|---|---|
| `build-logic` composite build with convention plugins | Nine modules. A guardrail that must be remembered per-module is a guardrail that will be forgotten. |
| Both `iosArm64` **and** `iosSimulatorArm64`, framework binary on **every** `:core:*` | ROADMAP M11 expects both to have been in CI since M0. A framework per module means a JVM-only dependency in `:core:storage` is caught too, not just in `:core:sync`. |
| Gradle configuration cache **off** | KMP + KSP + Hilt + SQLDelight is a lot to bring under it at once. A green, boring M0 beats a fast one. Turning it on is an M9 task. |
| R8 / minify **off** in release | ROADMAP assigns R8 verification to M10. Leaving it on would let `build` (which assembles release) fail on rules no code exercises yet. |
| `verifyMigrations` **off** in SQLDelight | Needs a baseline `.db` to compare against, which arrives with the first real schema in M2. |
| PdfBox declared in the catalog but **not** wired into `:android:pdf` | It is the fallback if the M4.0 spike fails (SPEC §6.3). `verifyCatalogCoordinates` proves the pin resolves without pre-committing the module to a dormant dependency. |
| No `INTERNET` permission in the manifest | It arrives with M5, so nothing in M0–M4 can accidentally grow a network path outside the outbox (CLAUDE.md rule 4). |
| Wrapper JAR not committed | It is a binary this environment cannot produce. `tools/bootstrap-wrapper.ps1` generates it, and CI generates it too if absent — so nothing is blocked either way. |

---

## 5. Defects found by adversarial review, and fixed

Because the build cannot be executed here (§3), the skeleton was put through a multi-agent static review — five independent lenses (convention plugins, version catalog, KMP modules, `:app`/`:android`, CI and spec compliance), with every claim then handed to a separate agent instructed to *refute* it. Seven findings survived refutation; they deduplicate to four defects, all fixed in this commit.

### 5.1 Guardrail 1 was passing vacuously ⭐

**The worst possible failure mode for a guardrail, and it was live.**

`CorePlatformFreeCheckTask.sources` is populated from `KotlinSourceSet.kotlin.srcDirs` — *directories*. A Gradle `FileCollection` never expands a directory into its contents; that is what `asFileTree` exists for. So `sources.filter { it.isFile && … }` tested `isFile` on directories, matched nothing, and the task wrote `OK — 0 shared Kotlin file(s) scanned` and exited 0 **regardless of what the code contained**. CLAUDE.md rule 2 would have been unenforced from the first commit, and `tools/verify-guardrails.sh imports` would have failed in CI with "Guardrail 1 did NOT fail on a planted import".

Fixed by expanding through `sources.asFileTree.files`. And because "scanned nothing" and "found nothing" were indistinguishable in the report, **a zero-file scan is now itself a failure** — every `:core:*` module has at least one hand-written shared Kotlin file, so zero always means broken wiring.

### 5.2 `android:Theme.DeviceDefault.DayNight.NoActionBar` does not exist

An aapt2 link error, so `:app:processDebugResources` fails before any Kotlin compiles. The framework publishes exactly two DayNight styles (`Theme.DeviceDefault.DayNight`, `ThemeOverlay.DeviceDefault.Accent.DayNight`); every `DeviceDefault.*NoActionBar` variant is non-DayNight. Because the `parent` attribute is explicit and namespaced to `android`, aapt2 cannot fall back to dot-name inference. Fixed by parenting to `Theme.DeviceDefault.DayNight` and removing the action bar via `android:windowActionBar` / `android:windowNoTitle`.

### 5.3 `.editorconfig` did not pin `ktlint_code_style`

ktlint 1.x defaults to `ktlint_official`, and Spotless 8.9.0 only substitutes `intellij_idea` when an `editorConfigOverride` map is passed — which the Gradle setup does not do. So the OfficialCodeStyle-only rules (`no-empty-first-line-in-class-body`, `function-signature`, `multiline-expression-wrapping`) were live against code written in conventional IDEA style, failing `spotlessCheck` and therefore `build` in five files. Fixed by pinning `ktlint_code_style = intellij_idea`, which is also what makes the two existing `ktlint_standard_*-naming = disabled` lines coherent rather than incidental.

### 5.4 One misordered import block

`InkstoneTheme.kt` had `androidx.compose.foundation.isSystemInDarkTheme` last instead of first. Cosmetic in nature, build-breaking in effect, because `spotlessCheck` is wired into `check`. Fixed.

### Findings that did not survive

Three claims were refuted on inspection and correctly *not* acted on: that the root `clean` task collided with Spotless's `base` plugin (the task had already been removed for that exact reason); that `verifyCatalogCoordinates` could not resolve Spotless's portal-only plugin marker (already fixed with a content-filtered `gradlePluginPortal()`); and that `:app` could not see the generated `InkstoneDatabase` type (the SQLDelight plugin adds its runtime as an `api` dependency, so it is visible).

---

## 6. Known risk carried into M1

**detekt 1.23.8 against Kotlin 2.3.20 is unverified here.** detekt 1.23.x embeds a Kotlin 1.9-era compiler frontend. It is normally fine alongside a newer Kotlin because it analyses with its own embedded compiler, but that combination has not been executed on this machine and 1.23.8 is the newest release available.

If `./gradlew detekt` fails at configuration or analysis time, that is the cause. It is isolated to `build-logic/src/main/kotlin/inkstone.quality.gradle.kts` and removing the `id("io.gitlab.arturbosch.detekt")` line there degrades cleanly to Spotless + ktlint only. Do not "fix" it by changing the Kotlin version.
