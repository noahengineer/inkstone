# Inkstone

A handwriting-first note-taking app for Android tablets, storing notes as ordinary PDFs in your Nextcloud, working fully offline.

`SPEC.md` is the technical specification. `ROADMAP.md` is the build order. `CLAUDE.md` is the repo rules. `START-HERE.md` explains how the three fit together.

**Current state: M0 — skeleton and guardrails.** One empty Compose screen. That is the whole intended functionality of this milestone.

**Verified green locally:** `./gradlew build` (964 tasks, both APKs), all 58 pinned coordinates resolving, `spotlessCheck` + `detekt` on all 9 modules, and **both guardrails including their planted-violation tests**. Outstanding: the real iOS *framework link* (needs macOS) and CI-green-on-a-branch (needs a remote). See [docs/M0-VERIFICATION.md](docs/M0-VERIFICATION.md).

---

## Prerequisites

| | Version | Notes |
|---|---|---|
| JDK | **21 (LTS)** | Gradle 8.14 supports Java 17–24. **JDK 25/26 will not work** — the daemon refuses to start, and the error doesn't say why. |
| Android SDK | API 36 | Easiest via Android Studio. |
| macOS | only for the iOS guardrail | Kotlin/Native cannot build Apple targets on Windows or Linux. CI covers it. |

```bash
winget install Google.AndroidStudio
```

Open this folder in Android Studio once. It installs the SDK, writes `local.properties` (gitignored), and runs Gradle on its own bundled JBR 21 — so builds from inside the IDE work regardless of what's on your `PATH`.

### ⚠️ For command-line builds, set `JAVA_HOME`

Installing JDK 21 does **not** make it the default. Oracle's `javapath` shim stays ahead of it on `PATH`, so `java -version` keeps reporting 26 and Gradle refuses to start:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot'
```

To make it permanent, set `JAVA_HOME` in System Properties → Environment Variables, and move the JDK 21 `\bin` above `C:\Program Files\Common Files\Oracle\Java\javapath` in `PATH`.

## First run

The wrapper is committed, so there is nothing to bootstrap:

```bash
./gradlew verifyCatalogCoordinates
```

```bash
./gradlew build
```

(`tools/bootstrap-wrapper.ps1` regenerates the wrapper if it is ever lost, and checks your JDK first.)

---

## The two guardrails

`:core:*` must stay platform-free, and the iOS target is what proves it (SPEC §2.3).

**Guardrail 1 — no platform imports in shared code.** Runs as part of `check`:

```bash
./gradlew checkCoreIsPlatformFree
```

**Guardrail 2 — every `:core:*` module still builds for iOS.** The full framework link is macOS-only:

```bash
./gradlew linkIosCore
```

Guardrail 2 is the one that matters. OkHttp and dav4jvm contain no `android.*` import at all, so they sail past Guardrail 1 while killing the iPad path silently.

Kotlin/Native *cross-compiles* Apple klibs on Windows and Linux — only linking a framework needs macOS. So the guardrail's substance is testable anywhere. Prove both, on any OS:

```bash
bash tools/verify-guardrails.sh all
```

It plants a real violation for each (an `android.content.Context` import in `:core:model`, an OkHttp dependency in `:core:sync` **commonMain**), asserts the right one fails, and reverts. Off macOS the iOS half uses `compileKotlinIosSimulatorArm64`; the macOS CI job runs the real link.

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
