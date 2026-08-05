# Inkstone

A handwriting-first note-taking app for Android tablets, storing notes as ordinary PDFs in your Nextcloud, working fully offline.

`SPEC.md` is the technical specification. `ROADMAP.md` is the build order. `CLAUDE.md` is the repo rules. `START-HERE.md` explains how the three fit together.

**Current state: M0 — skeleton and guardrails.** The app builds and shows one empty Compose screen. That is the whole intended functionality of this milestone.

---

## Prerequisites

| | Version | Notes |
|---|---|---|
| JDK | **21 (LTS)** | Gradle 8.14 supports Java 17–24. **JDK 25/26 will not work** — the daemon refuses to start. |
| Android SDK | API 36 | Android Studio, or `cmdline-tools` + `sdkmanager "platforms;android-36" "build-tools;36.0.0"` |
| macOS | only for the iOS guardrail | Kotlin/Native cannot build Apple targets on Windows or Linux. CI covers it. |

```bash
winget install EclipseAdoptium.Temurin.21.JDK
```

Point `local.properties` at your SDK (Android Studio writes this for you):

```
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

## First run

The Gradle wrapper JAR is not checked in. Generate it once — or just open the project in Android Studio, which does the same thing:

```bash
powershell -ExecutionPolicy Bypass -File tools\bootstrap-wrapper.ps1
```

Then:

```bash
./gradlew verifyCatalogCoordinates
```

```bash
./gradlew build
```

---

## The two guardrails

`:core:*` must stay platform-free, and the iOS target is what proves it (SPEC §2.3).

**Guardrail 1 — no platform imports in shared code.** Runs as part of `check`:

```bash
./gradlew checkCoreIsPlatformFree
```

**Guardrail 2 — every `:core:*` module still links for iOS.** macOS only:

```bash
./gradlew linkIosCore
```

Guardrail 2 is the one that matters. OkHttp and dav4jvm contain no `android.*` import at all, so they sail past Guardrail 1 while killing the iPad path silently. Both run in CI, the second on a macOS runner.

Prove they still work by planting a violation (macOS for the `ios` case):

```bash
bash tools/verify-guardrails.sh all
```

---

## Module graph

```
:core:model       KMP  Document/Page/Op types, IDs, coordinate math. Zero platform deps.
:core:storage     KMP  Bundle + journal serialization. Stroke geometry is opaque bytes.
:core:database    KMP  SQLDelight schema + queries.
:core:sync        KMP  Nextcloud client (Ktor), outbox, reconciler, merge engine.
:core:search      KMP  Tokenizer + FTS index over recognised text.

:android:ink      AND  androidx.ink, MotionEvent handling, renderer.
:android:pdf      AND  PDF import, render, materialize.
:android:ocr      AND  ML Kit Digital Ink Recognition, line segmentation.

:app              AND  Compose UI, Hilt, WorkManager, platform impls of core interfaces.
```

Dependency direction is one-way: `:app` → `:android:*` → `:core:*`.

## Useful tasks

| Task | What it does |
|---|---|
| `verifyCatalogCoordinates` | Resolves every pinned coordinate, including ones no module uses yet |
| `checkCoreIsPlatformFree` | Guardrail 1 |
| `linkIosCore` | Guardrail 2 (macOS) |
| `spotlessApply` | Formats Kotlin with ktlint |
| `detekt` | Static analysis |
| `build` | Assemble + all checks |
