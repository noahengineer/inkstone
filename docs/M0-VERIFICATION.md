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
| Hilt | **`2.58`** | **not** the latest — see §5.5. Latest stable Hilt does not work with the pinned AGP |
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

## 3. What has actually been run, and what has not

JDK 21 was installed after the first pass, so Gradle now runs. Everything below is a real result from this machine unless marked otherwise.

### Executed and green

| Check | Result |
|---|---|
| **`./gradlew build`** | ✅ **BUILD SUCCESSFUL**, 964 tasks. Debug and release APKs produced. |
| `verifyCatalogCoordinates` | ✅ **all 58 pinned coordinates resolved** |
| `checkCoreIsPlatformFree` | ✅ green, and demonstrably scanning: 3 files in `:core:model`, 2 in `:core:database`, 2 in `:core:sync`, 1 each in `:core:storage` / `:core:search` — `androidMain` correctly excluded |
| `spotlessCheck` + `detekt` | ✅ green on all 9 modules |
| `verify-guardrails.sh imports` | ✅ **PASS** |
| `verify-guardrails.sh ios` | ✅ **PASS** |
| **Runs on the target device** | ✅ installed and launched on a **Galaxy Tab S9 FE+**, correct screen, no crash |

ROADMAP M0 acceptance boxes 1, 3, 4 and 5 are ticked by the above, as is CLAUDE.md's definition-of-done item 4 ("used on the actual Galaxy Tab, not just an emulator").

### Device notes for M1

The target device is a **Galaxy Tab S9 FE+**, which ships with a passive S Pen. Nothing in SPEC §0.1 #2 changes for it — the dead Samsung SDK and the S Pen Bluetooth removal are both irrelevant to a passive pen. Pressure, tilt, orientation, the eraser tip and the barrel button all arrive as plain `MotionEvent` axes, which is exactly what SPEC §3.3 is built on.

⚠️ **`adb` is not yet working on this device** — USB debugging is not enabled, so M0 was installed by copying the APK over MTP. That is fine for "does it launch" and **not** fine from M1 onward: `dumpsys gfxinfo` (M1's sustained-framerate criterion), `adb logcat`, `./gradlew installDebug`, `adb shell am force-stop` mid-stroke (M2's crash-safety criterion) and all instrumented tests need it. Resolve before starting M1.

### Guardrail 2 turned out to be testable on Windows after all

I previously recorded that `linkDebugFrameworkIosSimulatorArm64` "cannot run here at all". That is true of *linking* and false of the thing the guardrail is actually for.

The build log shows all 10 `compileKotlinIosArm64` / `compileKotlinIosSimulatorArm64` tasks **executing** on this Windows host — zero skipped — while only the five `link*` tasks skip. Kotlin/Native cross-compiles klibs for Apple targets off macOS; only producing a framework binary needs the real toolchain.

So the guardrail's actual question — *does a JVM-only dependency break the iOS build?* — is answerable everywhere. `tools/verify-guardrails.sh ios` now picks its task by host (`compileKotlinIosSimulatorArm64` off macOS, the full link on it) and **passes on Windows**: planting OkHttp in `:core:sync` **commonMain** breaks the iOS compile while `checkCoreIsPlatformFree` stays green throughout — which is the entire point of having two guardrails.

The macOS CI job still runs the real link, because a klib compiling is not proof that a framework links.

### Still outstanding

| Box | Needs |
|---|---|
| `linkDebugFrameworkIosSimulatorArm64` succeeds | a macOS runner — the CI job exists, nothing has run it |
| CI green on a pushed branch | a GitHub remote; there is none yet |

Note the JDK trap for anyone repeating this: **installing JDK 21 does not make it the default.** Oracle's `javapath` shim stays ahead of it on `PATH`, so `java -version` still reports 26 and Gradle refuses to start. Set `JAVA_HOME` explicitly, or build from Android Studio, which uses its own bundled JBR 21.

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

### 5.5 Hilt: "latest stable" is not usable with the pinned AGP ⭐

Found by *running the build*, not by review. SPEC §0.2 said "DI — Hilt — latest stable at M0", and latest stable (2.60.1) **cannot be used with AGP 8.13.0**. Measured across the version range:

| Hilt | Against AGP 8.13.0 |
|---|---|
| ≤ 2.58 | works |
| 2.59 | applies, then dies configuring `:app` with `NoClassDefFoundError: ScopedArtifact$POST_COMPILATION_CLASSES`. Its own version check still claims a minimum of 8.4.0 — the check is stale, so the failure is a missing class rather than a clear message |
| 2.59.1 – 2.60.1 | hard-fail at apply: *"only compatible with Android Gradle plugin (AGP) version 9.0.0 or higher"* |

**Pinned 2.58.** The tempting "fix" is to bump AGP to 9.x — AGP 9 is released and 9.4.0-alpha07 exists. That is a spec change, not a version bump: SPEC §0.2 pins 8.13.0 and everything else is pinned around it, so it is not a decision to take in passing while chasing a DI library. Recorded here so the constraint is visible when someone does want to take it deliberately.

Generalised lesson for the rest of the roadmap: **"latest stable" is not a specification.** It has to mean "latest stable that works with the pinned AGP", and the only way to know is to apply the plugin. A coordinate resolving proves nothing about compatibility.

### 5.6 `verifyCatalogCoordinates` was resolving nothing

The first implementation used a resolvable `Configuration` with `@pom` artifact-only notation. `@pom` does **not** bypass variant-aware resolution, and the catalog spans modules with no common variant — `ktor-client-darwin` and `sqldelight-native-driver` publish Apple targets only, `junit` and the plugin markers are plain JVM — so no single set of consumer attributes can satisfy both. It failed with a wall of "cannot choose between the available variants".

Rewritten to use `createArtifactResolutionQuery().withArtifacts(MavenModule, MavenPomArtifact)`, which asks for the POM directly and sidesteps variant selection. That is exactly the question being asked: does this coordinate exist?

### 5.7 The androidx ecosystem has moved past AGP 8.13 — five more downgrades

Same failure class as Hilt (§5.5), found the same way: by running the build. `:app` and all three `:android:*` modules failed `checkDebugAarMetadata`. Every AAR declares its own floor in `META-INF/.../aar-metadata.properties`, and the newest releases have moved to AGP 9.1.0 / compileSdk 37:

| Library | I had picked | Ceiling for AGP 8.13.0 + compileSdk 36 | What the newest release demands |
|---|---|---|---|
| `androidx.core:core-ktx` | 1.19.0 | **1.18.0** | compileSdk 37, AGP 9.1.0 |
| `androidx.lifecycle:*` | 2.11.0 | **2.10.0** | compileSdk 37, AGP 9.1.0 |
| `androidx.hilt:hilt-navigation-compose` | 1.4.0 | **1.3.0** | compileSdk 37, AGP 9.1.0 |
| `androidx.activity:activity-compose` | 1.13.0 | **1.13.0** — already fine | (min 36 / AGP 8.9.1) |
| Compose BOM `2026.06.01` | — | fine (`ui-android` 1.11.x needs only 35) | — |

**No SPEC-pinned version changed.** Every one of these was a "latest stable at M0" slot, and SPEC §0.2 delegates that choice — which can only mean *latest stable that works with the pinned AGP*. AGP stays at 8.13.0.

The check worth internalising for M1+: **read `aar-metadata.properties` before bumping an androidx version.** A coordinate that resolves is not a coordinate that builds, and the failure surfaces late, at `checkDebugAarMetadata`, long after dependency resolution has happily succeeded.

### 5.8 SPEC §0.2's `androidx.pdf` pin needs an SDK extension level it doesn't mention

Every `androidx.pdf:1.0.0-alpha19` artifact requires a compile SDK with **extension level ≥ 19**. The installed API 36 platform ships extension level **17** (API 37 ships 22). So `compileSdk = 36` alone cannot consume the pin SPEC §0.2 names.

This is a real gap in SPEC §0.2, not a version-picking mistake — and it bites at **M4**, not M0. `:android:pdf` has no code until then, so M0 leaves the dependency undeclared with the constraint recorded in the build file; `verifyCatalogCoordinates` still proves the coordinate resolves. M4 will have to choose between an ext-19 platform package and compileSdk 37, and that is a decision to take deliberately rather than discover.

### 5.9 ktlint rejects KDoc in `.gradle.kts` and dangling file-level KDoc

Five files used `/** … */` where no declaration follows — the four convention plugins and `Platform.kt`. `standard:kdoc` rejects both "KDoc not allowed inside 'block'" and "dangling toplevel KDoc", and the second is **not auto-correctable**, so `spotlessApply` could not fix it. Converted to plain block comments.

### Findings that did not survive

Three claims were refuted on inspection and correctly *not* acted on: that the root `clean` task collided with Spotless's `base` plugin (the task had already been removed for that exact reason); that `verifyCatalogCoordinates` could not resolve Spotless's portal-only plugin marker (already fixed with a content-filtered `gradlePluginPortal()`); and that `:app` could not see the generated `InkstoneDatabase` type (the SQLDelight plugin adds its runtime as an `api` dependency, so it is visible).

---

## 6. Risks carried into M1

**detekt 1.23.8 against Kotlin 2.3.20 — retired.** This was flagged as unverified. It has now run green on all nine modules as part of `./gradlew build`. No action needed.

**The AGP 8.13 ceiling is a standing tax.** Three separate libraries (Hilt, five androidx artifacts, and `androidx.pdf`'s extension level) collided with the pinned AGP inside a single milestone. The ecosystem has moved to AGP 9.1 / compileSdk 37 and will keep moving, so this recurs on every dependency bump for the rest of the project. Two consequences worth holding:

- Before bumping any Android library, read its `aar-metadata.properties` (`minCompileSdk`, `minCompileSdkExtension`, `minAndroidGradlePluginVersion`). Resolution success proves nothing.
- At some point the honest move is to reconsider the AGP 8.13.0 / compileSdk 36 pin itself. That is a SPEC decision, not a maintenance one, and M4 will force the question anyway via `androidx.pdf` (§5.8). Worth deciding then rather than drifting into it.
