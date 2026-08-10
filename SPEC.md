# Inkstone — Technical Specification

> A handwriting-first note-taking app for Android tablets, with Nextcloud as the storage backend and true offline-first behaviour.
>
> **Working name.** Rename freely — it appears only in the package ID `dev.inkstone.*` and the app label.

**Spec version:** 1.1 (revised after design review) · **Written:** 5 August 2026
**Primary target:** Samsung Galaxy Tab + S Pen, Android 14+
**Future target:** iPadOS (shared core, native rendering)

---

## 0. Read this first

Every version number and API claim here was verified against primary sources on **5 August 2026**. Several contradict what you'd assume from older tutorials — read §0.1 before writing any code.

### 0.1 Corrections that change the design

**1. `androidx.ink` is stable now. Do not write your own ink engine.**
`androidx.ink:*:1.0.0` went stable on 17 Dec 2025. It gives you the brush model, stroke geometry, tessellation, and front-buffered low-latency rendering — the hardest part of this app. Use it.

**2. The Samsung S Pen SDK is dead. Build with zero Samsung dependency.**
The Galaxy S Pen Remote SDK's current download is version **1.0.2, dated September 2019**, and Samsung **removed Bluetooth from the S Pen entirely on the Galaxy Tab S11 (2025)**. Everything a handwriting app needs — pressure, tilt, orientation, hover, eraser tip, barrel button, palm rejection — is plain Android `MotionEvent`.

**3. Nextcloud's `loginName` is NOT the WebDAV path segment.**
Login Flow v2 returns a `loginName` that may be an email address. The DAV path needs the **UID** from `/ocs/v2.php/cloud/user` → `ocs.data.id`. Using `loginName` works on some instances and 404s on others. This is the single most likely bug in any Nextcloud client written from memory.

**4. `:core:sync` cannot use OkHttp or dav4jvm.**
Both are JVM-only. If they live in `:core:sync`, the iPad path (§11) is dead and you won't find out for ten milestones. **Core uses Ktor** (KMP) and hand-rolls the PROPFIND XML — which §7.3 gives you verbatim, so dav4jvm buys almost nothing here anyway.

### 0.2 Pinned versions

Pin these. Verified 5 Aug 2026.

| Purpose | Coordinate | Version |
|---|---|---|
| **Build** | | |
| Kotlin | — | **2.3.20** |
| Android Gradle Plugin | `com.android.tools.build:gradle` | **8.13.0** |
| Gradle wrapper | — | **8.14** |
| `compileSdk` / `targetSdk` / `minSdk` | — | **36 / 36 / 30** |
| **Ink** | | |
| Ink engine | `androidx.ink:ink-authoring`, `-brush`, `-geometry`, `-rendering`, `-strokes`, `-storage`, `-nativeloader` | **1.0.0** |
| Low-latency surface | `androidx.graphics:graphics-core` | **1.0.4** |
| Motion prediction | `androidx.input:input-motionprediction` | **1.0.0** |
| **Platform** | | |
| Compose BOM | `androidx.compose:compose-bom` | **2026.06.01** — resolved at M0 |
| WorkManager | `androidx.work:work-runtime-ktx` | **2.11.2** — resolved at M0 (2.12.0 is still beta) |
| DI | **Hilt** (`com.google.dagger:hilt-android`) | **2.60.1** — resolved at M0 |
| Annotation processing | `com.google.devtools.ksp` | **2.3.11** — resolved at M0. ⚠️ KSP left the `<kotlin>-<ksp>` version scheme after `2.2.21-2.0.5`; pick by checking that its POM depends on `kotlin-stdlib` 2.3.20, not by pattern-matching the Kotlin version |
| **Data** | | |
| KMP database | `app.cash.sqldelight` | **2.3.2** |
| KMP HTTP | `io.ktor:ktor-client-core` + `-okhttp` (Android) / `-darwin` (iOS) | **3.5.0** |
| Serialization | `org.jetbrains.kotlinx:kotlinx-serialization-json` | **1.11.0** — resolved at M0 |
| **Features** | | |
| Handwriting recognition | `com.google.mlkit:digital-ink-recognition` | **19.0.0** |
| PDF viewer | `androidx.pdf:pdf-viewer-fragment` | **1.0.0-alpha19** ⚠️ needs **compile SDK extension level ≥ 19** — see below |
| PDF writing (fallback) | `com.tom-roush:pdfbox-android` | **2.0.27.0** ⚠️ dormant since Jan 2023 — §6.3 |

**Explicitly not used:**
- ❌ `dav4jvm`, `okhttp` **in `:core:*`** — JVM-only, breaks §11. OkHttp appears only as Ktor's Android engine, inside `:app`.
- ❌ **Room 3.0** — first alpha March 2026, `androidx.room3` namespace. Use SQLDelight (also KMP-native).
- ❌ **Any Samsung SDK** — §0.1 #2.
- ⚠️ **`EncryptedSharedPreferences`** was deprecated by Google in 2025. **Do not use it.** Store credentials as an AES-GCM-encrypted blob with the key in the Android Keystore (`KeyGenParameterSpec`, `setUserAuthenticationRequired(false)`), written to a private file. ~60 lines, no deprecated dependency. See §14.

**If a coordinate doesn't resolve from a clean Gradle cache:** report the actual available version with a link to the release page, and stop. Do not silently substitute. Do not invent.

⚠️ **Resolving is not the same as building — added in M0.** Every Android library declares its own floor in `META-INF/com/android/build/gradle/aar-metadata.properties` (`minCompileSdk`, `minCompileSdkExtension`, `minAndroidGradlePluginVersion`). A too-new version resolves perfectly and then fails at `checkDebugAarMetadata`. Three collisions with the AGP 8.13.0 pin turned up in M0 alone:

- **Hilt**: 2.59+ needs AGP 9.x. Pinned **2.58**, the last AGP-8 release.
- **androidx** "latest stable": `core-ktx` 1.19.0, `lifecycle` 2.11.0 and `hilt-navigation-compose` 1.4.0 all require compileSdk 37 + AGP 9.1.0. Pinned **1.18.0 / 2.10.0 / 1.3.0**. Wherever this table says "latest stable at M0", read it as **latest stable that works with the pinned AGP**.
- **`androidx.pdf` 1.0.0-alpha19** requires compile SDK **extension level ≥ 19**. The API 36 platform ships extension level 17; API 37 ships 22. **`compileSdk = 36` alone cannot consume this pin.** ~~M4 must choose between an ext-19 platform package and moving to compileSdk 37~~ — **decided in M4: neither.** The M4.0 spike ruled `androidx.pdf` out for writing (§6.1), and the only thing left to want from it was one bitmap call, which §6.1's own fallback provides from plain `android-36`. `:android:pdf` leaves the dependency undeclared permanently, **compileSdk stays 36, the AGP 8.13.0 pin is untouched**, and `verifyCatalogCoordinates` still proves the coordinate resolves. (Both options were real — `platforms;android-36-ext19` exists — so this is a choice, not a limitation.)

Before bumping any Android library, read its `aar-metadata.properties`.

### 0.3 What this actually costs

**M0–M6 is a several-months-of-evenings project for one person**, even with an AI agent doing the typing. M6's acceptance criteria include a literal calendar week of two-device use. M1 and M6 are each worth multiple sessions on their own.

Milestone ≠ session. Budget accordingly, and don't be discouraged around M4 — that's where the middle of the project sags.

---

## 1. Product definition

### 1.1 What this is

A tablet note-taking app where:

- Writing with a stylus feels as good as Samsung Notes — that is the bar
- Every note can be **bound to a location in your Nextcloud**, chosen by you
- The app works **completely offline**; bound notes upload themselves when a connection next appears
- Notes are stored as **ordinary PDFs** that open anywhere, with the lossless editable data embedded inside them
- Nothing is ever silently lost — not to a conflict, not to a crash, not to a delete

### 1.2 Not in v1

No real-time collaboration. No AI. No infinite canvas. No audio recording. See §12.

### 1.3 The design principle

**Goodnotes forces a choice on export: searchable handwriting *or* preserved document structure, never both** — their own docs confirm it. This app refuses that trade-off (see **§5.5**). If you build nothing else distinctive, build that.

---

## 2. Architecture

### 2.1 Module graph

```
:core:model       KMP  · Document/Page/Op types, IDs, Lamport clocks, coordinate math. Zero platform deps.
:core:storage     KMP  · Bundle + journal serialization. Stroke geometry passes through as opaque bytes.
:core:database    KMP  · SQLDelight schema + queries.
:core:sync        KMP  · Nextcloud client (Ktor), outbox, reconciler, merge engine. NO OkHttp, NO dav4jvm.
:core:search      KMP  · Tokenizer + FTS index over recognised text.

:android:ink      AND  · androidx.ink, MotionEvent handling, renderer, stroke blob encode/decode.
:android:pdf      AND  · PDF import, render, materialize (annotations + text layer + embedded bundle).
:android:ocr      AND  · ML Kit Digital Ink Recognition, line segmentation.

:app              AND  · Compose UI, navigation, Hilt, WorkManager, platform impls of core interfaces.
```

**Dependency direction:** `:app` → `:android:*` → `:core:*`. Never the reverse. Third-party types never cross a module boundary — PdfBox stays behind a `PdfWriter` interface in `:android:pdf`, ML Kit stays in `:android:ocr`.

*Added in M5.* Within `:core:*` there is now one edge: **`:core:database` → `:core:sync`**. The outbox's semantics — coalescing, annihilation, backoff inheritance, the mid-flight `local_rev` guard — are the part of §7.6 that fails quietly, so they live in `:core:sync` behind ports (`OutboxStore`, `BindingStore`, `AccountStore`, `SyncNotes`) that `:core:database` implements. The reverse edge would have put the rules in the module that owns SQLDelight and made *"500 offline edits produce one upload"* an instrumented test. Acyclic, and the one-way rule above is untouched. There is also a **test-only** `:android:pdf` → `:core:sync` edge, for Appendix B #4's spike only.

*Added in M6.* One more edge inside `:core:*`: **`:core:sync` → `:core:storage`**, so the graph is
`:core:database → :core:sync → :core:storage → :core:model`. Still acyclic, still platform-free on
both sides, so the iOS link is unaffected. Without it the reconciler could not name §5.6's open
verdict or a `BundleReader`, and the only alternative was a **second four-way copy of §5.6's
decision enum** inside `:core:sync` — a second source of truth for the one decision this project
has already spent a milestone getting right.

`:core:model` gains a matching interface pair to §2.2's materializer: `RemoteBundleExtractor`, which
is materialization read backwards — a downloaded PDF in, the embedded bundle and §5.6's verdict out
— implemented in `:android:pdf` because PdfBox does not leave that module. Paths in, paths out, no
bytes: a 40 MB lecture must not cross the boundary in memory inside a WorkManager job on the 4 GB
device §10 names.

**Ktor is a third-party type too.** `:core:sync` exposes `NextcloudTransport`, which is opaque outside the module — so `:app` cannot name an `HttpClient`, and therefore has nothing to make a request *with* outside the outbox (§7.6). Added in M5, when Hilt asked `:app` to name a provider's return type.

### 2.2 Interfaces core needs from the platform

`:core:*` cannot do these itself. Define them in `:core:model`, implement in `:app`:

```kotlin
interface DocumentMaterializer {          // core:sync needs a PDF to upload; only :android:pdf can make one
    suspend fun materialize(noteId: DocumentId): MaterializedFile
}
interface CredentialStore { ... }          // Keystore-backed
interface WorkScheduler { ... }            // WorkManager
interface FileSystem { ... }               // okio, or expect/actual
interface Clock { ... }
```

### 2.3 The guardrail (build it in M0, not later)

Two mechanisms, both required:

1. A Gradle task failing the build if any `:core:*` module imports `android.*` or `androidx.*` (except `androidx.annotation`) **in a shared source set**.
2. **`iosSimulatorArm64` as a real target on every `:core:*` module from M0**, even with an empty `iosMain`. This is what actually catches a JVM-only dependency — the import check alone will happily stay green while OkHttp sits in `:core:sync`.

*Clarified in M0 (see `docs/M0-VERIFICATION.md`).* Mechanism 1 scans every Kotlin source set **except the `android*` ones**. A KMP module's `androidMain` is Android-by-definition — it is where `actual` implementations live — and `:core:database` cannot construct an `AndroidSqliteDriver` without `android.content.Context`. Scanning it would make this spec's own module graph impossible to build.

This concedes nothing that matters, because mechanism 1 was never the load-bearing one: **OkHttp and dav4jvm contain no `android.*` import at all.** They would pass a scan of *every* source set, `commonMain` included, while the iPad path was already dead. Mechanism 2 is what catches them. `tools/verify-guardrails.sh ios` demonstrates precisely this — it plants OkHttp in `:core:sync` **commonMain**, shows the iOS link failing, and shows the import check staying green throughout.

### 2.4 Threading

- Ink capture and rendering: **UI thread + front buffer.** Nothing else on it — no allocation in a loop, no I/O, no serialization, no recognition.
- Journal append, materialization, OCR, network: background dispatchers.
- Sync: **WorkManager only.**

---

## 3. Ink capture and rendering

Milestone M1, and the one that decides whether the project is worth continuing.

### 3.1 The stack

```
MotionEvent (unbuffered, with historical points)
    ↓
androidx.input:input-motionprediction  → predicted points
    ↓
androidx.ink InProgressStrokesView     → front-buffered wet ink
    ↓ (on stroke finish)
Stroke → journal append → dry rendering
```

✅ **Spike resolved in M1 (see `docs/M1-INK.md`): `androidx.ink` 1.0.0 does NOT predict internally. `input-motionprediction` is kept, and is the only predictor in the pipeline.**

Three pieces of evidence from the shipped artifacts: `ink-authoring-android`'s POM declares no dependency on `androidx.input:input-motionprediction`; the ink stack contains no Kalman filter, extrapolator, or class with `predict` in its name; and prediction is an explicit caller-supplied parameter at both levels — `InProgressStrokesView.addToStroke(event, pointerId, strokeId, prediction)` and `InProgressStroke.enqueueInputs(realInputs, predictedInputs)`.

**Corollary that saves work later:** ink tracks real and predicted inputs separately (`getRealInputCount` / `getPredictedInputCount`), so it un-draws the predicted tail itself when the next real event arrives. §3.3's "un-draw them when real events arrive" is ink's job, not the app's — do not implement it a second time.

### 3.2 Compose interop

**Decision: use stable 1.0.0, wrapped in `AndroidView`.** An alpha dependency in the most important code path is a bad trade.

⚠️ Google's own Compose setup page pins `ink_version = "1.0.0"` alongside the `ink-*-compose` artifacts. **That combination does not resolve.** Don't copy that snippet.

*Corrected in M0 (see `docs/M0-VERIFICATION.md`).* This spec previously said the Compose ink modules exist **only** at `1.1.0-alpha06`. That is wrong in its premise, though right in its conclusion. Measured against Google Maven on 5 Aug 2026:

```
androidx.ink:ink-authoring-compose:1.0.0   -> 200
androidx.ink:ink-rendering-compose:1.0.0   -> 404
```

The Compose set on the 1.0.0 line is **incomplete**, not absent — which is why Google's snippet fails to resolve. State it that way, because "they only exist at 1.1.0-alpha06" invites moving the whole ink stack to alpha in order to get them, and that is exactly the trade this section refuses.

### 3.3 Input handling requirements

Mandatory, each with a specific failure mode:

| Requirement | API | If skipped |
|---|---|---|
| Unbuffered dispatch on `ACTION_DOWN` | `View.requestUnbufferedDispatch(event)` | Input batched per frame; latency roughly doubles |
| **Historical points** | `getHistorySize()`, `getHistoricalX/Y/Pressure/AxisValue()` | Fast strokes become visible polygons |
| Pressure | `getAxisValue(AXIS_PRESSURE)` — 0..1, may exceed 1 | No line-weight variation; ink looks dead |
| Tilt | `getAxisValue(AXIS_TILT)` — radians, 0 = perpendicular | No shading behaviour |
| Orientation | `getAxisValue(AXIS_ORIENTATION)` | Chisel/calligraphy brushes broken |
| Eraser tip | `getToolType() == TOOL_TYPE_ERASER` | Flipping the S Pen does nothing |
| Barrel button | `getButtonState() and BUTTON_STYLUS_PRIMARY` | No quick-erase modifier |
| Palm rejection | `ACTION_CANCEL` + `FLAG_CANCELED` (API 33+), identify via `getPointerId(getActionIndex())` | Stray palm marks |
| Hover preview | `AXIS_DISTANCE` | Minor — deferred to M10 |

Predicted segments are speculative; un-draw them when real events arrive. Verify no predicted tail survives into the dry layer. *(M1: `androidx.ink` does this itself — see §3.1.)*

*Clarified in M1.* Every row from Pressure to Orientation is satisfied by **passing the whole `MotionEvent` to `addToStroke`**, because ink reads them off the event itself (`StrokeInputPool.obtainAllHistoryForMotionEvent` walks `getHistorySize()`, `getHistoricalAxisValue()`, `getToolType()` and normalises orientation). The way to fail these rows is to synthesise `StrokeInput`s by hand: it compiles, it draws, and it silently drops pressure, tilt, orientation and every historical point.

### 3.4 Brushes for v1

| Brush | Behaviour |
|---|---|
| **Fountain** | Pressure → width. Slight velocity taper. Default. |
| **Ballpoint** | Uniform width, no pressure response. |
| **Pencil** | Pressure + tilt → opacity and width. ~~Grain texture.~~ **Deferred to M10** — see below. |
| **Highlighter** | Uniform width, ~~multiply blend~~ plain alpha, draws **over** ink, stroke-stabilization slider. |

⚠️ **Corrected after M4, from the tablet, in three attempts — the middle one is the instructive part.** Originally *"multiply blend, draws behind ink"*. Reported first as *"you can't highlight your own writing, it gets put under it"*, and then twice more.

**1. "Behind" was only tenable because the multiply was never implemented.** M1 delivered the z-order and not the blend, so the highlighter was a plain translucent stroke. Plain translucent yellow over black writing lightens it, so behind was the only order that kept writing legible. It went unquestioned for three milestones because nothing in the app highlighted anything but blank paper, where the two orders are indistinguishable.

**2. "Multiply, on top" is the principled fix and is wrong here.** It is what a physical highlighter does, and the arithmetic defeats it: multiply cannot lighten, so yellow over black leaves the writing *exactly* black — **pixel-identical to drawing behind it**. Shipping it produced the same report again. Note what this means for debugging: over dark ink, *behind* and *multiply-on-top* are the same pixels, so the visible difference being reported lived entirely in the **wet** stroke, which is plain alpha and did tint the writing while the pen was down.

That produced one genuine finding, worth keeping even though what it enabled was reverted: **wet ink cannot carry a blend mode.** `InProgressStrokesView` renders through a **SurfaceView front buffer** (`CanvasInProgressStrokesRenderHelperV33` on API 33+) which SurfaceFlinger composites over the window, and a separate surface only alpha-blends. `useHighLatencyRenderHelper` (`@RestrictTo(LIBRARY_GROUP)`) moves it onto HWUI, where `setLayerType(LAYER_TYPE_HARDWARE, paint)` applies an xfermode — at the cost of the low-latency path. So **any brush whose dry rendering uses a blend mode must give up the front buffer to stay consistent.**

**3. Plain alpha, on top, is what shipped.** The highlight tints what it covers. "Leaves the writing untouched" and "looks like it is over the writing" are contradictory requirements and no blend mode satisfies both — tinting is the only thing that reads as *on top*. It also needs nothing from the wet layer, because plain alpha is what the wet layer already does: the stroke does not change at the instant it lands, and the highlighter keeps its front buffer.

On screen this is render order alone (`StrokeStore.forEachInRenderOrder` emits highlighters last); in the published PDF it is annotation order alone, since annotation order is paint order. **No `/BM` is written anywhere**, and `PdfPublishTest` asserts its absence — the invariant that outlives this episode is that the published file composites exactly the way the editor does.

Per-brush: colour (hex + wheel + eyedropper), width, 12 saved slots. *(M1 ships hex + wheel + 12 slots, which is what ROADMAP M1's scope line asks for. The eyedropper needs to sample the rendered canvas and belongs with M10's toolbar work.)*

⚠️ **Corrected in M1 — Inkstone defines its own brush families; `StockBrushes` is not used.** Two independent findings forced this, both measured against the shipped artifact on 5 Aug 2026.

**1. There is no usable stock pencil.** Of the stock families (`marker`, `pressurePen`, `highlighter`, `dashedLine`, `emojiHighlighter`, `pencilUnstable`), only `pencilUnstable` responds to tilt, and it is `@RestrictTo(LIBRARY_GROUP)`.

**2. The stock brushes cannot be cut.** They wire `DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE` and `DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE` into `SIZE_MULTIPLIER` and `OPACITY_MULTIPLIER` — ten behaviour entries across the set. Those are the start/end tapers, and they are parameterised **along the stroke**. Since a partial erase rebuilds each surviving piece as a new `Stroke` (§3.5), every cut end re-ran the taper and faded to a point instead of ending bluntly.

**The rule Inkstone's families are built around: no behaviour may depend on distance or time along the stroke.** Everything is driven by an instantaneous property of the input — pressure, tilt, speed — so a fragment renders exactly as that part of the original did. A fountain pen still starts thin, for the physical reason: pressure ramps as the nib lands.

**Every `BrushFamily` constructor is `@RestrictTo(LIBRARY_GROUP)`** on top of `@ExperimentalInkCustomBrushApi`, so this widens that suppression to the whole brush layer. It was taken deliberately, and what it buys is the reason `androidx.ink` was chosen at all: `BrushBehavior` in 1.0.0 is a **dataflow graph** (`SourceNode`, `ConstantNode`, `NoiseNode`, `BinaryOpNode`, `InterpolationNode`, `DampingNode`, `ResponseNode`, `FallbackFilterNode`, `ToolTypeFilterNode`, `PolarTargetNode`) over ~38 sources, with targets including hue, saturation, luminosity and lateral position offset, and `BrushPaint` supplying texture layers, blend modes and particle stamping. That is an art-application brush engine; `StockBrushes` is a thin sample on top of it.

**Added in M1: the input model is a setting.** `BrushFamily` takes `SPRING_MODEL` (default — spring dynamics, smoother, slightly laggier) or `EXPERIMENTAL_NAIVE_MODEL` (tracks samples more literally). This is the "does it follow the nib or chase a spline" knob, it is pure taste, and it cannot be settled off-device — so it is exposed in the toolbar's Measure panel rather than decided here.

Still not delivered: the Pencil **grain texture**. It needs a texture asset of ours; `BrushPaint.TextureLayer` is where it goes. Not an M1 acceptance criterion. Noted for M10.

**Revisit both this and the latency-callback suppression whenever the `androidxInk` pin moves** (`docs/M1-INK.md` §2 lists every restricted member and what M1 does instead).

**Added in M3, same category:** `CanvasStrokeRenderer.create(forcePathRendering, textureStore)` is `@RestrictTo(LIBRARY_GROUP)` too, and lint fails the build on it. That overload is exactly what a `Bitmap`-backed canvas wants — thumbnails (§5.7) draw onto one, where ink's `drawMesh` fast path is unavailable. Nothing is lost: the single-argument `create` is public, and the renderer picks the path renderer per draw call from the canvas it is handed.

**Steal this from Goodnotes:** after erasing, auto-return to the previously selected pen. Two lines, and their founder cited it as exemplifying the whole product strategy.

### 3.5 Erasing — scope note

~~**M1 and M2 implement whole-stroke erase only**~~ — **revised twice in M1, both times on the tablet.**

The original rule deferred stroke-splitting to M8a and made whole-stroke erase M1's behaviour. That was rejected on contact: an eraser that deletes an entire line because you clipped its tail is startling, and no other note app on the device does it.

The replacement — rebuilding each surviving piece from the input samples that were not erased — was **also** wrong, and wrong in a way worth recording because it looked right for a while:

> **Sample-based erasing cannot erase part of a line, only cut it.** It removes whole input samples, so it always takes the full width of the stroke, and only at whatever granularity the stylus happened to sample. You cannot shave the side off a thick stroke or take a bite out of one, because neither is expressible as "these samples are gone".

**M1 therefore erases an *area*.** The eraser records the region it swept, per stroke, and that region is subtracted at draw time (`DST_OUT` into a bounded layer, so the cut edge is antialiased exactly like the stroke's own edge). The eraser's precision is its own geometry rather than the input sampling rate, and partial-width erasing simply works.

**The stroke itself is never modified.** Its inputs and its mesh are exactly as drawn; erasing only ever appends to a region beside them. This is strictly stronger than the "never mutate a point list" rule it replaces — it satisfies §1's *never lose user data* at the stroke level, and makes un-erasing a matter of dropping region entries rather than reconstructing geometry that no longer exists.

`WHOLE_STROKE` remains as a setting. It is cheaper — it records no region — and some people prefer it.

⚠️ **This adds an op to §4.2, and M2 owes it.** The vocabulary has no way to say "part of this object is erased". The addition is:

```kotlin
data class SetErasedRegion(val target: ObjectId, val absolute: List<EraseCapsule>) : PageOp
```

**Absolute, not a delta** — the whole region every time, resolved last-writer-wins, exactly like `SetTransform` carries a whole matrix and for the same reason (rule 8: deltas break silently under concurrency). A region op targeting a tombstoned object is a trivial no-op, which is the property the rest of the vocabulary is built on.

§4.2 says to define the complete vocabulary in M2 *precisely so that it is never extended once sync is live*. This is that instruction paying off: the op is identified before M2 instead of after M6. Each capsule is a segment plus a tip radius in page space — five floats, resolution-independent, replayable on any device. The flattened path is a render-time cache and is never what gets written down.

M8a's "partial erase splits a stroke into fragments" is **superseded**. Nothing splits.

### 3.6 Latency verification (M1 gate)

Do not declare M1 done on vibes:

1. **Quantitative:** ≤ **2 frames** of additional visual lag versus Samsung Notes, measured at 240 fps capture of the same stroke on the same device.
2. Sustained 60fps (120 on a 120Hz panel) with 5,000 dry strokes on-page, via `dumpsys gfxinfo`.

**Exit condition:** if after three serious attempts you are within one frame of Samsung Notes, **accept and proceed**. Samsung uses vendor-private low-latency paths that public APIs may not match. Do not deadlock the project at milestone one.

*Added in M1.* Ink reports its own end-to-end latency per input — `LatencyData.osDetectsEvent` → `estimatedPixelPresentationTime` — surfaced in the toolbar's **Measure** panel. It does **not** replace the camera test (Samsung's number is not measurable from inside our process); it is the instrument you need *while* making the three attempts, and it makes the prediction A/B a ten-second check. The exact procedure for both measurements, including the `dumpsys gfxinfo` command and the pass/fail numbers, is in **`docs/M1-INK.md` §4**.

---

## 4. Document model

### 4.1 Core types

```kotlin
@JvmInline value class DocumentId(val value: String)   // UUIDv7
@JvmInline value class PageId(val value: String)
@JvmInline value class ObjectId(val value: String)     // strokes, images, text boxes, shapes

data class LamportClock(val counter: Long, val deviceId: String) : Comparable<LamportClock>
// Comparator is EXACTLY: compareBy({ it.counter }, { it.deviceId })
// deviceId compared as a plain lexicographic String comparison — no locale collation.
// On document open: counter = (max counter across all loaded ops) + 1.
// A reinstall generates a new deviceId; without the max+1 rule its ops sort behind all history.

data class Document(
    val id: DocumentId,
    val title: String,                 // LWW register — resolved by titleClock, added in M6 (§7.7)
    val titleClock: LamportClock?,     // when `title` was last written. null = never renamed
    val pages: List<PageRef>,          // ordered by (sortKey, pageId) — see 4.3
    val schemaVersion: Int,
)

// Deleting a page is a TOMBSTONE, not a removal (corrected in M2 — see below).
data class PageRef(
    val id: PageId,
    val sortKey: FractionalKey,
    val deleted: Boolean = false,
    val deletedEpochMillis: Long? = null,
)

data class Page(
    val id: PageId,
    val size: PageSize,                // page space, see §4.4
    val background: Background,        // Template(PageTemplate) | SourcePdfPage(index) | Blank
    val ops: List<PageOp>,             // the op-log
)
```

**Corrected in M2 — page deletion is a tombstone.** Dropping a `PageRef` loses the page's
data outright, and it also lets the page come back: §4.3's merge rule is *"any page not
present in a merged order is appended, never dropped"*, so a peer that still lists the page
re-adds it on the next sync. Deleting is therefore a flag and a timestamp, restoring is
clearing them, and the page's ops stay in the bundle either way (CLAUDE.md rule 1).

**Corrected in M2 — `Background.Template` carries a typed enum, not a `ref` string.** A
string reference implies a registry of user-supplied templates, which §12 keeps out of v1 and
which would have to sync: a note referencing a template file that is on the other tablet is a
blank page with no explanation. The five built-ins (blank, ruled, grid, dot grid, Cornell) are
a closed set with their metrics in the model, so a page rules identically on screen, in a
thumbnail and in the published PDF.

⚠️ **Corrected in M6 — `deviceId` is a property of the *install*, and until M6 it was not.**
The comparator above is only total if the two `deviceId`s differ, and
`Register.write`'s correctness argument says so in as many words. But the editor took the id
from `note.document.deviceId` — out of the manifest, i.e. from whichever device *created* the
note — so two tablets editing one note stamped every op with the **same** id. Two concurrent
ops at counter 47 then compare **equal**, `Register.write` keeps the incumbent, the incumbent
is whichever op that device's fold absorbed first, and the two devices hold different values
**forever, with every op present, in the same total order, and no error anywhere.** That is
§13.2 row 3 failing underneath every other row. The library index separately minted an id per
*process*, so one tablet broke ties differently after every restart.

It is now `:core:storage`'s `DeviceIdentity`: one id per install, in a plain file beside
`notes/` (§5.1), read once. A file rather than a database row because §13.1's
disaster-recovery story deletes the database — and a rebuild that re-minted this device's
identity would re-open every tie in every note it had ever contributed to. M2 recorded the
note-derived id as temporary and named M5 as the fix; M5 did not do it, and M6 is the first
milestone in which a second device exists to expose it.

⚠️ **`Document.localRev` is never adopted from a peer** (M6). It is this device's count of
this device's commits and the value §7.6's mid-flight check compares against, so taking
`maxOf(local, remote)` across devices — which is right when replaying a note's own journal
onto its own bundle — jumps the counter past what the outbox recorded and makes the merged
work invisible to sync forever. `mergeDocument` copies page order, title and title clock, and
nothing else.

### 4.2 The op vocabulary — complete, from M2

**Define all of these in M2 even though M8 is the first user.** Extending the op vocabulary after sync is live is the classic "and then everything broke" moment.

```kotlin
sealed interface PageOp {
    val opId: String                   // UUIDv7
    val clock: LamportClock
    val extras: OpExtras               // unrecognised JSON members, kept verbatim (§5.8)

    // --- Grow-only payload + presence ---
    data class AddObject(val obj: InkObject) : PageOp        // stroke, image, textbox, shape
    data class DeleteObject(val target: ObjectId) : PageOp   // tombstone, never a removal

    // --- LWW registers, keyed by (ObjectId, field). All ABSOLUTE. ---
    data class SetTransform(val target: ObjectId, val absolute: Matrix3) : PageOp
    data class SetStyle(val target: ObjectId, val style: ObjectStyle) : PageOp  // colour, width, opacity
    data class SetZOrder(val target: ObjectId, val z: FractionalKey) : PageOp
    data class SetTextContent(val target: ObjectId, val text: String) : PageOp
    data class SetErasedRegion(val target: ObjectId, val absolute: ErasedArea) : PageOp
    data class SetPageMeta(val background: Background?, val size: PageSize?) : PageOp

    // --- Forward compatibility (§5.8) ---
    data class Unknown(val type: String, val rawJson: ByteArray) : PageOp
}
```

**Three corrections from M2, all of which the original section implied but did not say:**

1. **`StyleDelta` is `ObjectStyle`, and it is absolute.** The old name contradicted the rule
   this section states two paragraphs down, and a type called `Delta` is exactly what a future
   contributor would implement as one.
2. **`SetErasedRegion` is part of the vocabulary.** §3.5 was revised in M1 to erase an *area*
   rather than input samples, and §4.2 as written could not express that. It is absolute like
   every other register here, plus one reason of its own: undo writes back the region as it
   was, which a difference cannot describe (CLAUDE.md rule 8).
3. **`Unknown` exists**, because §5.8 requires unknown op types to survive a round-trip
   untouched, and something has to hold them while they do.

**Presence is an LWW register, not a monotone flag.** This section says a delete is *"a
tombstone, never a removal"* and §4.6 says undo of a delete is an `AddObject`. Both are only
true if presence is a register: a monotone tombstone can never be undone, so the second
statement would be unimplementable. That is safe here in a way it would not be in a general
CRDT, because `ObjectId`s are UUIDv7 minted by the device that creates the object — two
devices cannot concurrently add the same id, so the only writer that can ever *un*-delete is
the device that deleted it, undoing itself. "Tombstone, never a removal" stays exactly true of
the data: the `AddObject` and its geometry remain in the log forever, and only visibility
moves.

**⭐ `SetTransform` carries an ABSOLUTE matrix, not a delta.** This is not a style preference — deltas are broken under concurrency:

> Device A scales object Y by 2×. Device B concurrently rotates Y by 45°. A then undoes with an inverse-scale op. Total order is `scale, rotate, unscale` — the rotation gets smeared by the non-commuting scale, and Y ends up skewed. Consistently, on both devices, silently.

With absolute matrices: concurrent transforms resolve last-writer-wins, undo writes back the previous absolute matrix, and a transform targeting a tombstoned object is a trivial no-op. Same for `SetStyle` and `SetZOrder`.

**Transform on a deleted object:** no-op. **Delete of a non-existent object:** record the tombstone anyway (the AddObject may arrive later — this is what makes ordering irrelevant).

### 4.3 Convergence — how it actually works

Not a CRDT in the fancy sense. **Total-order state-machine replication:** sort the union of ops by Lamport clock, fold from empty, get the same state everywhere.

**The rule the agent will get wrong:** you cannot append an out-of-order op to already-applied state. When an incoming op has a clock lower than ops you've already applied, you must **re-fold the page from the last snapshot**. Never patch in place.

**Page ordering** uses fractional keys (`sortKey`), resolved by `(sortKey, pageId)`. This means two devices adding a page offline both keep their page — a plain LWW list would silently drop one device's page while orphaning its file. **Any page not present in a merged order is appended at the end, never dropped.**

*Made structural in M6.* Three things this section describes as rules are now properties of
the types, because each was a rule a caller could silently break:

- **The re-fold is enforced by the return value, not by the caller.** `NoteSession.merge`
  delegates to `PageLog.applyAll`, which answers `APPENDED` / `REFOLDED` / `SNAPSHOT_STALE`
  and offers no way to patch state in place. A merge that folded a union itself and assigned
  the result would bypass the snapshot signal, and §13.2 row 8 would fail in a way no local
  test reproduces.
- **`merge` is the only door a peer's ops come through**, and it is the only one that cannot
  reach the undo stack (§4.6).
- **The document is merged before any page's ops are.** `merge` will accept ops for a page the
  document has never heard of and journal them; `BundleWriter` then skips any page absent from
  `document.pages`, and `materialize` resets the journal immediately afterwards. Remote ops
  journaled without their `PageRef` present are **destroyed, permanently, with no error** — the
  output is a valid PDF with the right page count and a page missing. Merging page order first
  is what makes "appended, never dropped" true of the *file* and not only of the list.

**Page-order merges must be repaired, not just merged** (M6). `mergePageOrder` takes the
lexicographically smaller key and does not validate well-formedness, so a malformed `sortKey`
in a peer's file survives the merge and surfaces much later at `keyForInsert`, whose fallback
silently appends at the end instead of inserting where the user asked. `PageOrder.repair` runs
on every merged order.

⚠️ **A page restored from trash does not propagate, and cannot yet** (M6, corrected at close-out).
A tombstone beats a live entry — deliberately, because the alternative is a page the user deleted
on one device reappearing on the other on every sync — and `PageOrder.reconcile` implements that as
`a.deleted || b.deleted`, with no clock anywhere near it. So once a peer holds the tombstone it
keeps it, and the *next* merge in the other direction re-deletes the page locally: the restore is
undone by sync, silently. Earlier text here claimed a restore propagates "with a fresh entry"; that
was never true of any `PageRef` shape, because freshness is not something an unconditional OR can
see. **Nothing is lost** — the page's whole op log survives under the tombstone (rule 1) and
restoring again works until the next sync — so this is a visible-correctness gap, not a data one.
The fix is `deleted` as a Lamport register per page (rule 8), which means a new member inside the
manifest's `pages[]` array, where §5.8's carry-unknown-fields rule does **not** reach: an older
build would drop it on the next rewrite and silently return to OR semantics. It therefore needs a
bundle schema decision, and it is owed by whichever milestone makes one.

### 4.4 Coordinate spaces ⭐

Underspecified coordinate handling is the most reliable source of "annotations are 3mm off and rotated" bugs. Define it once, in `:core:model`, with unit tests.

**Three spaces:**

| Space | Units | Origin | Y axis |
|---|---|---|---|
| **View** | pixels | top-left of the view | down |
| **Page** (canonical — all persisted data) | points (1/72") | top-left of the **CropBox after applying `/Rotate`** | **down** |
| **PDF user** | points | lower-left of the box | **up** |

**Rules:**

1. **All persisted geometry is in page space.** Ink arrives in view space and is converted on stroke finish. Never persist view coordinates.
2. Page size comes from **CropBox, not MediaBox.** Viewers display CropBox; sizing from MediaBox puts annotations wrong on any PDF where they differ. Fall back to MediaBox only when CropBox is absent.
3. **Apply `/Rotate` when computing page space.** `/Rotate 90` is extremely common in scanned lecture PDFs. A page with `/Rotate 90` and a 612×792 CropBox has a page space of **792×612**.
4. `pageToPdfUser(p) = (cropBox.x0 + p.x, cropBox.y1 - p.y)` **before** rotation compensation; ~~the rotation is then folded into the annotation's `/Matrix`~~ — *revised in M4.* The rotation is folded into the **coordinates**, one step earlier, by `pageToPdfUser` itself: all four quadrants are handled there, so by the time a point reaches the writer it is already in unrotated PDF user space and there is no rotation left for a matrix to express.
5. `/AP` form XObjects need a `/BBox` in form space plus a `/Matrix` — do not assume identity. *M4 does not assume it; it chooses it, having removed the reason for anything else.* With rule 4 applied, the appearance content, its `/BBox` and the annotation's `/Rect` are all in the same space, so `/BBox == /Rect` with an identity `/Matrix` is the only self-consistent combination — and it is safer than a rotated matrix, which has to agree with a `/BBox` computed in form space. The two disagreeing is precisely the failure this section exists to prevent. See `docs/M4-PDF.md` §7.1.

⚠️ **Rules 2 and 3 read a page size that a later write can take away** (found in M6). `/MediaBox`,
`/CropBox` and `/Rotate` are *inheritable* page attributes, so a page can be sized entirely by a
`/Pages` node above it — and the writer flattens the page tree when it reorders pages, which cuts
that node out. The geometry is read before the reorder and the page loses its size after it, so the
ink lands on paper of a different size with nothing reporting an error. See §6.4.

```kotlin
// :core:model — single source of truth, unit-tested against real PDFs
fun viewToPage(v: Offset, camera: Camera): PagePoint
fun pageToPdfUser(p: PagePoint, box: PdfBox, rotate: Int): PdfPoint
fun pdfUserToPage(p: PdfPoint, box: PdfBox, rotate: Int): PagePoint
```

**M4 must include an acceptance test using a source PDF with `/Rotate 90` and a CropBox whose origin is non-zero.** Both are common; both break naive implementations.

### 4.5 Viewport / camera

The spec would otherwise never mention zoom, which M1's 5,000-stroke gate and M2's 200-page notebooks both require.

```kotlin
data class Camera(val translate: Offset, val scale: Float)   // page space → view space
```

- Zoom range **10%–1600%**, pinch and double-tap
- Fit modes: fit-width (default), fit-page, actual size — these **centre** the page; nothing else does
- **Panning is free.** ~~The page is kept inside the viewport~~ — *revised in M1.* Pinning the page to the viewport, and centring it whenever it fitted, was rejected on the tablet for fighting the user: zoomed out the page could not be moved at all, and zoomed in it stopped dead at the margins. The page may now sit anywhere, off-centre, mostly off-screen, at any zoom. The **only** bound is a leash: at least ~64 dp of page stays on screen on each axis, so no gesture can strand the user on a blank screen with no edge to grab. `Camera.leashedTo` is the whole rule.
- **Ink is captured in view space and converted at the camera in effect when the stroke started.** A camera change mid-stroke does not retroactively alter the stroke.
- Dry-layer re-render is debounced on scale change; the front buffer is not scaled, it is re-created

### 4.6 Undo/redo

Undo is a **new op appended to the log**, never a pop:

- `AddObject` ↔ `DeleteObject`
- `SetTransform` ↔ `SetTransform(previous absolute matrix)`
- `SetStyle` ↔ `SetStyle(previous style)`

Undo is **local-only and per-device.** An incoming remote op does not enter your undo stack, and undoing does not undo someone else's work.

*Added in M1, ahead of the op-log.* One entry per **gesture** — a whole stroke, or a whole erase drag however many samples it contained. Bound to a **two-finger tap (undo)** and **three-finger tap (redo)** as well as toolbar buttons.

M1 has no log to append to, so `EditHistory` is a plain stack: the honest implementation of §4.6's model when there is nothing to append to. **M2 replaces the mechanism, not the behaviour** — `Added` becomes `DeleteObject`, `Removed` becomes `AddObject`, and an erasure becomes `SetErasedRegion(previous absolute region)`.

*Done in M2.* `EditHistory` is gone; `UndoStack` holds gesture-grained entries and the session appends their inverses. Two consequences worth knowing: the entries are **persisted** to `undo.json`, because "undo across an app restart" otherwise means re-folding the log once per undo step; and both directions replay by **re-stamping with a fresh clock**, which is only sound because every op is absolute — replaying `SetTransform(M)` writes `M` whatever is currently there. A delta log could not do this at all.

That last one is why §3.5's new op **must** be absolute rather than a delta (rule 8): undo writes back the region as it was, and cannot do that if the op only carries a difference. Erasing an area rather than destroying geometry is also what makes undo cheap here — an erase is undone by dropping capsules, because nothing was thrown away.

⚠️ **"An incoming remote op does not enter your undo stack" is now unrepresentable, and in M6
it was one forgotten argument away** (§7.7). The session's ingest method was
`apply(pageId, ops, undoable: Boolean = true)` — and the default was the dangerous one. A
caller that meant to absorb a peer's ops and omitted the third argument put a collaborator's
strokes on the *local* undo stack, `flush()` persisted that to `undo.json`, and the user's next
two-finger tap deleted work they had never made — durably, surviving a restart. Nothing in
`UndoStack` could catch it, because it was a property of the call site alone.

The boolean is gone. Three named methods replace it: **`apply`** (a local gesture, always
undoable), **`replay`** (undo/redo/duplicate — ops whose inverses are already on the stack),
and **`merge`** (a peer's ops: subtracted by `opId`, never near `undoStack`). "Forgot an
argument" is no longer a reachable state. It also removed an O(n·m) trap — `Undo.inverseOf`
does a linear scan of the fold per op, so merging thousands of remote ops through the undoable
path was quadratic before it was wrong.

### 4.7 Compaction

**The naive rule ("compact when SYNCED") is wrong twice:** it would never compact a `LOCAL_ONLY` note (i.e. most notes, and all notes before M5), and "SYNCED" means "this device uploaded", not "every peer has seen these ops" — compacting while your other tablet holds older ops offline leaves them nowhere to reintegrate.

**The rule:**

- Compact when `syncState ∈ {LOCAL_ONLY, SYNCED}` **and** all ops being folded are older than the **causal-stability horizon**: 30 days, or the oldest clock any known device has acked, whichever is more conservative. For a personal 1–2 device setup, 30 days is fine.
- Threshold in **bytes** (≈2 MB of journal), not op count. "2,000 ops" is about one page of dense handwriting.
- **The snapshot is a cache, not the truth.** Keep the raw log. If an op older than the snapshot ever arrives, discard the snapshot and re-fold.

⚠️ **Corrected in M6 — the snapshot is per page, and the gate was mostly dead.** Four separate
things, and the first two meant this section described behaviour the code did not have.

1. **`snapshot.bundle` was one file per *document*.** `compactIfDue` loops every live page
   writing a snapshot, and every write went to the same path — so on a multi-page note each
   page overwrote the previous one and `readSnapshot` answered null for every page but the
   last. Nothing failed, because a missing snapshot is a full re-fold, which is correct and
   merely slower. It became load-bearing in M6: §13.2 row 8 asserts that an older op discards
   the snapshot and re-folds, and with one snapshot per document that row passed or failed
   according to which page the test happened to pick. A green result would have proven
   nothing. It is now `snapshot/<pageId>.bundle` (§5.1).
2. **Every caller took `close()`'s `LOCAL_ONLY` default**, so the first and most important
   refusal above had never fired in production: a `CONFLICTED` or `PENDING_UPLOAD` note was
   compacted as though the server had never heard of it. The default is gone — the parameter
   is required, so a call site cannot omit it.
3. **The horizon was measured from the document's *creation* date**, not from the oldest op
   being folded. On any note older than 30 days the causal-stability gate therefore passed for
   ops written seconds ago — the guard inverted, on exactly the long-lived notebooks it exists
   to protect. The last materialization is the honest floor: every op in the journal was
   written after it, and erring later costs disk, never data.
4. **`oldestAckedCounter` was always null.** The parameter has existed since M2 and no caller
   could fill it, because nothing knew what a peer had seen — so 30 days of wall clock was the
   whole guard. M6 is the first milestone where a peer exists: every downloaded bundle reports
   its `highestClockCounter` and its `document.deviceId`, which is recorded per
   `(noteId, peerDeviceId)` and passed as the `min` across peers.

**A fifth refusal, added in M6: `PAUSED`** — a note whose account is signed out (§7.6). Its
binding survives and it may hold edits the server has never seen, which is precisely this
section's *"compacting while your other tablet holds older ops offline leaves them nowhere to
reintegrate"* with the server as the other tablet.

And one guard that is defensive rather than corrective: **compaction refuses while the session
has unflushed work.** A snapshot is a cache *of the log*, so folding one over ops that exist
only in memory produces a cache holding state the log does not — and discarding it, which rule
9 requires the moment an older op arrives, would then lose that work outright. `close()`
materializes first so it cannot happen today; a caller reaching `compactIfDue` directly is one
refactor away and the cost of being wrong is silent.

---

## 5. Storage and file format

### 5.1 Working format vs published format ⭐

**The distinction the whole storage design turns on.** Autosave cannot rewrite a 40 MB PDF every two seconds, so the PDF is not the working format.

**On disk, per note:**

```
device.id               this install's deviceId (§4.1). A sibling of notes/, added in M6.
download/<id>.pdf       a pulled remote file, staged for merge. Added in M6.
notes/<documentId>/
├── document.pdf        the materialized, publishable file. May be up to one session stale.
├── journal.ops         append-only op log since the last materialization. Flushed ~2s.
└── snapshot/<pageId>.bundle   optional compaction snapshot, per page (§4.7)
```

*Both top-level entries were added in M6 and neither is incidental.* `device.id` must not
travel with a note that is copied, duplicated, published or synced, which is why it is not
inside `notes/` (§4.1).

⚠️ **A downloaded file is never written over `notes/<id>/document.pdf`.** Three failures
collapse into that one rule, and the first is the corruption class this design exists to
prevent:

1. `writeBytesAtomically` writes through a temp file whose name is *derived from the
   destination*, so two writers aimed at one path share one temp file and then atomically
   rename a **torn PDF** into place. The reconciler downloading while the materialize worker
   republishes is exactly two writers aimed at one path, and there is no lock anywhere.
2. The downloaded file's XMP `bundleSha256` describes the **remote** bundle. Written over
   `document.pdf` it disagrees with the merged `note.inkstone` beside it, and the next open
   reads `Recovered(BUNDLE_HASH_MISMATCH)` on a note that is perfectly intact (§5.6).
3. Materialization truncates back to `originalLength` from the marker *in whatever file is
   there* (§5.2), so the next local publish would silently rebase this note on a peer's base
   bytes.

**Where these files actually are in M2.** PDF materialization is M4's, so until then the
bundle is a file of its own — `notes/<documentId>/note.inkstone` — and there is nothing to
embed it in (ROADMAP M2: *"for M2 the materialized artifact is just the bundle"*). M4 moves
where those bytes live, not what they are. There is also a fourth file, `undo.json`: the
undo/redo stacks are local and per-device (§4.6), so putting them in a bundle that syncs would
ship one tablet's undo history to the other, where it means nothing and where the two would
overwrite each other.

- **Autosave = append to `journal.ops`.** Cheap, bounded, crash-safe. This is what runs every 2 seconds.
- **Materialization = rewrite `document.pdf`.** Runs on: document close, before upload, and on idle-while-charging. Never on the ink path.
- **Open = load the bundle, then replay `journal.ops` on top.** *Refined in M4:* the bundle stays a file of its own, `note.inkstone`, and `document.pdf` carries a **copy** of it. Opening reads the file, not the PDF. ~~"load the embedded bundle from `document.pdf`"~~ reads well and would cost a full PDF parse plus a stream decode on every open, which defeats §5.3's whole lazy-loading design — that depends on random access into the ZIP's central directory, and a bundle inflated out of a `/EmbeddedFile` has to be materialised whole first. The published copy is what *travels*; the local file is what is *read*. The marker's `bundleSha256` is what keeps the two honest: it always describes the copy inside the PDF, so a mismatch means something outside this app touched it (§5.6). The duplication is a few hundred KB of op metadata beside a 20 MB PDF — and it is the second copy §5.2 was careful about, `source.pdf`, that would have been the expensive one.
- **Invariant: `document.pdf` is never more than one editing session stale.** State this in the code as an assertion.

**All file writes are write-temp-then-atomic-rename.** The upload worker must never read a file mid-write — that produces a torn ZIP inside a PDF, which is exactly the corruption class this design exists to prevent.

### 5.2 The published PDF

```
document.pdf
├── original PDF bytes            ← verbatim, if the note was imported from a PDF
├── ── incremental update ──      ← everything below is appended, original never rewritten
├── /Annots → /Ink[]              ← one per stroke, with /NM = ObjectId
│     └── /AP /N                  ← appearance stream: filled variable-width path
├── invisible text layer          ← recognised handwriting, render mode `3 Tr`
├── XMP metadata                  ← documentId, schemaVersion, bundleSha256  (§5.6)
└── /Names /EmbeddedFiles
      └── note.inkstone           ← the lossless bundle
```

**⭐ Materialize as a PDF incremental update, not a rewrite.** Appending an update section to the original file:

- preserves the original bytes **verbatim at the front of the file**, which satisfies the "give me my original PDF back" contract for free
- means the bundle does **not** need to carry a second copy of `source.pdf` — halving the file. (A 20 MB lecture PDF would otherwise become 40 MB+ before a single stroke.)
- is a standard PDF feature, not a hack

For notes created from scratch, generate base pages first, then treat that as the original.

**What this buys:** opens in any PDF viewer, in Nextcloud's web viewer, on an iPad, emailed to anyone — ink visible and correct. Handwriting searchable everywhere. One file in the browser. Lossless round-trip in the app.

### 5.3 The embedded bundle (`note.inkstone`)

A ZIP:

```
manifest.json           schemaVersion, documentId, title, pages[{id,sortKey}], timestamps, deviceId
pages/<pageId>.json     op-log metadata: op ids, clocks, types, and offsets into the geometry blob
pages/<pageId>.ink      OPAQUE stroke geometry blob — see §5.4
assets/img-<id>.webp    inserted images
text/<pageId>.json      recognised text + per-word page-space bounding boxes
```

**No `source.pdf`.** The original is the front of the PDF (§5.2).

**No thumbnail.** Thumbnails live in a local cache (§5.7).

One file per page gives **lazy loading** — you can open a 500-page notebook without deserializing 500 pages. *(It does not give granular sync: the transport unit is the whole PDF. Don't build page-level sync plumbing that can't be used.)*

### 5.4 Stroke geometry is an opaque blob ⭐

**Do not store stroke points as JSON.** At ~60–80 bytes/point of text, with dense pages holding thousands of strokes sampled at ~200 Hz, you'd be parsing megabytes of JSON per page — against a stated NFR of opening a 200-page notebook in under 2 seconds.

**Instead:** `:android:ink` serializes strokes using `androidx.ink:ink-storage` and hands `:core:storage` a `ByteArray`. Core stores and syncs it as opaque bytes and never interprets it. Core stays Android-free; you get Google's compact format for free; and there's no point-by-point conversion between `androidx.ink.Stroke` and a core type on every save and load.

**⚠️ Corrected in M2 — `ink-storage` 1.0.0 has no stroke serializer.** Its entire public
surface is `StrokeInputBatchSerialization.encode`/`decode` and `BrushFamilySerialization`.
There is no `Stroke.encode`, no `decodeStroke`, and no container format. Verified against the
shipped artifact: `ink-storage.aar` holds thirteen classes and none of them mentions `Stroke`.

So the blob is a small container written by `StrokeGeometryCodec`:

```
"INKS" | version u8 | brushVersion u8 | brushKind u8 | inputModel u8
       | colorArgb i32 | sizePt f32 | epsilonPt f32
       | inputBatch (ink-storage bytes, to the end of the blob)
```

The brush is four bytes and three floats rather than a serialized `BrushFamily`, deliberately.
Inkstone's families are code (`InkstoneBrushFamilies.kt`) and re-derivable, so a family blob
would be a second copy of them inside every stroke on disk; every `BrushFamily` constructor in
1.0.0 is `@RestrictTo(LIBRARY_GROUP)` and lint fails the build on them (§3.4); and the
families' ban on distance- or time-driven behaviour — the thing that makes partial erase
correct — lives in the source, where a blob carrying its own family could not undo it.
`brushVersion` records which generation of the families a stroke was captured with, so if one
ever changes shape a future build can reconstruct the historical one instead of silently
re-rendering old ink with new geometry. `inputModel` is recorded for the same reason: a stroke
captured with spring smoothing must not be re-tessellated with the naive model because the
setting changed afterwards.

**The deliberate tradeoff:** a future iOS renderer cannot decode `ink-storage`'s format. That's an accepted, explicit cost — recorded here so it's a decision, not a discovery at M11. The iOS options at that point are to write a decoder, or to add a portable secondary encoding then. Merge and sync are unaffected either way, because merge operates on op metadata (ids and clocks), never on geometry.

### 5.5 Invisible searchable text layer ⭐

PDF text rendering mode **`3 Tr`** (neither fill nor stroke) — the "sandwich" approach OCRmyPDF uses.

**Yours is better than OCR's, cheaply.** OCRmyPDF must rasterise and guess. Your ink is born-digital: you already have ML Kit's recognised text *and* exact stroke bounding boxes. Emit invisible text positioned directly from those boxes — no rasterise-then-recognise round trip, and better positioning than any OCR pipeline achieves.

Result: `pdfgrep` finds your handwriting. So does Acrobat, Preview, and (if configured) Nextcloud full-text search. **This is the thing Goodnotes structurally cannot do.**

⚠️ **Before M7 writes the first `text/<pageId>.json`, carried bundle entries have to be ordered.**
A merge unions the peer's carried entries with the local ones **local-wins** (§7.7, `NoteStore.materialize`),
and that rule is symmetric: for an entry name *both* devices produce, each keeps its own value, neither
is told, and no clock exists to order the two. Nothing produces a clashing name today — which is the
only reason M6 left it — and this file is named per *page*, so every device writes the same name and a
clash becomes the normal case the day recognition ships. Recognised text is derived data, so the cheap
answer is a clock or a "regenerate rather than merge" rule; what is not available is leaving it.

⚠️ **The font this layer names has to be in the *page's* `/Resources`, not the stream's**
(corrected in M6 — see §6.4, where the same defect had a more visible effect). M4 put it on the
content stream, so `/InkstoneF0 Tf` was an undefined name in every file this app published. A
text layer whose font is undefined is a text layer a viewer may decline to draw *and decline to
extract* — which is the whole feature — while `pdfgrep` on the app's own output, and every test
that read the stream back with the library that wrote it, said it was there.

### 5.6 Document identity and Recovered mode ⭐

**The problem:** "a PDF with ink annotations but no embedded bundle" also describes any PDF someone annotated in Acrobat. Without a marker, the app cannot distinguish *our file, mangled* from *a stranger's file*.

**Worse:** a naive recovered import assigns new ObjectIds to everything. If it keeps the original `documentId` and syncs, the other device's grow-only union produces **every stroke twice** — the exact silent corruption this design exists to prevent.

**The marker.** Write to the PDF's XMP metadata (survives more tools than `/Info`):

```
inkstone:documentId     UUIDv7
inkstone:schemaVersion  int
inkstone:bundleSha256   hex
inkstone:originalLength long   ← added in M4
inkstone:originalSha256 hex    ← added in M4
```

*The last two were added in M4 and are load-bearing, not bookkeeping.*
**`originalLength`** is what makes the published file stop growing: §5.2's update is
appended to the *original*, and without knowing where the original ends every
materialization appends to the previous update instead — a note re-published a hundred
times would carry a hundred annotation sets. **`originalSha256`** is what makes §5.2's
contract checkable rather than merely asserted, and it is the only way Recovered mode can
report "the pristine original is gone" as a fact — the bundle it would otherwise consult
being exactly the thing that went missing.

Plus `/NM` on every ink annotation carrying its `ObjectId`.

**And page identity — added in M4.** `documentId` plus `/NM` covers documents and strokes
and misses **pages**. A recovered import with no page identity invents a `PageId` per page,
and a peer merging that order keeps *both* the old page and its replacement, because §4.3
says *"any page not present in a merged order is appended at the end, never dropped"*. The
note silently doubles its page count — the stroke-duplication failure, one level up. So
every published page dictionary carries private keys:

```
/INKS_PageId     the PageId
/INKS_SortKey    its fractional key, so a recovered page keeps its place
/INKS_Flattened  true where §6.4's density rule applied, so recovery can say so
```

**Three-way detection on open:**

| Marker | Bundle | Mode |
|---|---|---|
| ✅ | ✅, sha matches | **Normal.** Full fidelity. |
| ✅ | ❌ or sha mismatch | **Recovered.** See below. |
| ❌ | — | **Foreign PDF.** Import as a new document with a new `documentId`. |
| ✅, newer `schemaVersion` | — | **Too new.** *Added in M4.* Do not open, and above all do not re-save. |

*Why the fourth row.* The three rows above assume every marker found is one this build
understands. Recovering a file from a newer schema would rewrite it in *this* build's
vocabulary, under the original `documentId`, with the Lamport counter deliberately bumped
past everything (point 5 below) — so it would win the next sync and take the newer data with
it. §5.8 already forbids re-saving a file from the future; this is that rule reaching the one
path that was about to break it.

**Recovered mode is a REPLACE, never a union:**

1. Reconstruct strokes from `/Ink` annotations, reusing the `ObjectId` from each `/NM` where present
2. The reconstructed page op-log **supersedes** the previous one — do not merge, do not union
3. Move the pre-recovery version to trash first
4. **Warn explicitly**, and say what was lost: pressure/width variation (`/AP` is a filled path, not a stroke), op history, undo stack, and — if the file was rewritten rather than incrementally updated — the pristine original PDF
5. Bump the Lamport counter past anything previously seen, so recovered state wins on the next sync

### 5.7 Thumbnails

**Not in the bundle.** Rendering a 500-note library must not mean opening 500 PDFs and extracting 500 ZIPs — M3's 60fps target is unreachable that way.

```
cache/thumbs/<documentId>.webp        library thumbnail, invalidated on materialize
cache/thumbs/<documentId>/<pageId>.webp   page thumbnails for reorder UI and search jump
```

Generated off the main thread, LRU-evicted, regenerated on demand. A bound-but-not-yet-downloaded note shows a placeholder with a sync badge.

**Invalidation is keyed by `local_rev`, not by a timestamp** (added in M3). The index stores the `local_rev` a thumbnail was rendered from; fresh means the two are equal. A file timestamp is the obvious mechanism and is wrong in three ways that each show up only occasionally: a render that starts before an edit and finishes after it writes a *newer* file showing older content; two saves inside one filesystem tick are indistinguishable; and a clock that steps backwards makes every cached image look fresh forever.

The invalidation is **the same SQL statement** that bumps `local_rev`, so there is no window in which the index reports a fresh thumbnail for content that has changed. `ThumbnailCache.invalidate` deletes rather than overwrites, so a crash between "the note changed" and "the picture was redrawn" is a miss (which costs a render), never a hit on the wrong picture.

**The source is the folded page, not `document.pdf`.** The published file is materialized on close and §5.1 allows it to be one editing session stale, so rendering a thumbnail from it would show the wrong picture after a crash with nothing to reveal it. `:android:ink` renders the same `FoldedPage` the editor draws, through the same painter — so the library's picture cannot drift from the editor's.

**Eviction is by least-recently-modified**, which the library index already knows, rather than by a separate LRU table that would have to be kept in step with the files it describes. A thumbnail is a cache of something still on disk, so evicting one costs a render, not data.

### 5.8 Format rules

- `schemaVersion` checked on every open
- **Forward compatibility:** unknown op types and unknown JSON fields are **preserved verbatim** on round-trip. A newer app version must be able to write a file an older version opens without destroying data.
- Migrations are one-way, versioned, and tested against fixture files committed to the repo — **including an on-device test** that a real app update migrates real data (§13)

⚠️ **Corrected in M6 — the forward-compatibility rule was honoured for ops and not for the
manifest.** Unknown members of an *op* were kept (`OpExtras`, M2), but `BundleReader` rebuilt
the manifest field by field from a fixed list, so any member it did not recognise was **dropped
on every round trip**. A newer build's document-level metadata therefore did not survive an
older build opening and saving the note — the exact failure this rule is written to prevent,
one level up from where it was being checked. Unknown manifest members are now carried through
verbatim, sorted so that identical content still produces identical bytes.

That fix is what lets `titleClock` (§7.7) be an **additive field with `schemaVersion` still 1**.
Bumping to 2 would make an older build refuse to open the note at all, and M6's acceptance gate
is a week of two-device use where the two devices are very likely on different builds. An older
build that *preserves* an unknown `titleClock` degrades — it cannot stamp its own renames, so it
may lose a later one, which is a wrong title that the next rename heals. Dropping the field
corrupts the register for every device, which is a wrong title that never heals.

---

## 6. PDF handling

### 6.1 Rendering

~~**Use `androidx.pdf` (`1.0.0-alpha19`) for rendering.**~~ **Corrected in M4 — `androidx.pdf` is not used at all.**

The M4.0 spike (Appendix B #1) answered both questions **no**, and decisively:

1. **It does not write `/Ink`.** `androidx.pdf.ink` turns every stroke into a `/Stamp`
   annotation holding a filled path object — and that is not the library's choice to
   reverse, because the framework type it converts into,
   `android.graphics.pdf.component.PdfAnnotationType`, has exactly four values:
   `UNKNOWN`, `FREETEXT`, `HIGHLIGHT`, `STAMP`. There is no ink annotation in the platform
   API. It also cannot set `/NM` — annotation identity there is the **integer index on the
   page** — and its save is a whole-document PDFium re-serialisation via
   `PdfRenderer.write`, which forecloses §5.2's incremental update outright.
2. **Variable width survives outbound and not inbound.** The written path is the filled
   outline (so it looks right); reading it back yields a flattened polygon with no pressure,
   no inputs and no brush.

**So annotations are written with PdfBox-Android behind a `PdfWriter` interface (§6.3), and
rendering uses the fallback below.** Once writing is gone the only thing left to want from
`androidx.pdf` is a bitmap, and every 1.0.0-alpha19 artifact demands compile SDK extension
level ≥ 19 while the API 36 platform ships 17 (§0.2) — a toolchain change for one method,
plus a `PdfViewerFragment` whose gestures would fight `InkSurfaceView`. **compileSdk stays
36.** Full report: `docs/M4-PDF.md` §1–2.

**Rendering: framework `PdfRenderer`** (expanded in API 35; `PdfRendererPreV` covers 30–34).

⚠️ *Corrected in M4:* `PdfRendererPreV` is delivered by the **mainline PDF module** and
requires **S extension level ≥ 13**, not merely API 30. A device on 30–34 that never took
that update does not have the class, so the renderer checks at runtime and falls back to
white paper — the ink still draws in the right place, because its geometry never came from
the renderer.

### 6.2 Import

- Original bytes become the base of `document.pdf` (§5.2). **Never rewritten.**
- Each PDF page → a `Page` with `Background.SourcePdfPage(index)`
- **Page size from CropBox, with `/Rotate` applied** (§4.4)
- Existing annotations: rendered, preserved on export, **not editable in v1**

### 6.3 Writing annotations

| Option | License | Verdict |
|---|---|---|
| **PdfBox-Android** `2.0.27.0` | Apache 2.0 | ~~Can create `PDAnnotationInk`~~ — **corrected in M4: there is no such class in 2.0.x.** The annotation package stops at `PDAnnotationRubberStamp` / `PDAnnotationTextMarkup`. `/Ink` is still entirely writable at the COS level (subtype, `/InkList`, `/AP`, `/NM`), just without a typed helper. Likewise **`xmpbox` is not bundled**, so the §5.6 marker is a raw uncompressed `/Metadata` stream — which is what Adobe's XMP spec asks for anyway. **No release since Jan 2023**; ports PDFBox 2.0.x (upstream is on 3.0.x), and 2.0.x is the line that has `saveIncremental`, which is the feature §5.2 turns on. Works, unmaintained. |
| Apache PDFBox 3.0.x | Apache 2.0 | Not Android-compatible (AWT deps) — which is why PdfBox-Android exists. |
| iText 7/8/9 | **AGPLv3 or commercial** | AGPL means publishing your app's full source. |
| Nutrient (ex-PSPDFKit) | Commercial | Priced for companies. |

**Plan:** ~~`androidx.pdf` if M4.0 passes, else~~ **PdfBox-Android behind a `PdfWriter` interface** — M4.0 did not pass (§6.1). No PdfBox types outside `:android:pdf`, and `PdfWriter` is `internal` to that module: the interface exists so PdfBox can be *replaced*, not so `:app` can choose a backend.

### 6.4 Ink → PDF, and the annotation-count decision ⭐

**The constraint:** PDF `/Ink` **cannot express variable stroke width.** `/InkList` is polylines; `/BS` gives one border width for the whole annotation. There is no per-point pressure in the spec.

**The workaround:** `/AP` (appearance stream) **takes rendering precedence** over `/InkList` and `/BS`. Write both:

1. `/AP /N` — the stroke outline as a **filled path**, capturing full pressure variation. This is what viewers render.
2. `/InkList` — a uniform-width polyline approximation, for interop and so other editors can select/move/delete it.

**The density decision — decide, don't hedge.** 5,000 annotations on a page will hang Preview and Chrome's PDFium.

- **≤ 500 strokes/page:** one annotation per stroke, each with `/NM` = ObjectId. Editable and identifiable elsewhere.
- **> 500 strokes/page:** a single flattened page overlay, no per-stroke annotations. The embedded bundle remains the source of truth; Recovered mode for such a page can only recover a flat image, and must say so.

**Stated limitation:** if a third-party editor (Acrobat and others do this) regenerates appearance streams, your variable-width `/AP` is replaced with a uniform stroke. The visual fidelity claim holds for *viewing*, not for *round-tripping through another editor*.

---

⚠️ **Found in M6: `/Resources` do not live in the same place twice, and M4 put them in the
wrong one.** A **form XObject** — which is what an annotation's `/AP /N` is — is a stream *with*
its own `/Resources`. A **page content stream** has none: it resolves every name it uses through
the *page dictionary* (PDF 32000-1 §7.8.3, `/Resources`, *"Required; inheritable"*). M4 set them
on the stream in both cases, so two things were undefined names in **every file this app has ever
published**:

- the flattened overlay's `/GS… gs`, above the 500-stroke threshold; and
- §5.5's invisible text layer's `/InkstoneF0 Tf`, on every page that had recognised text.

**What a viewer does with an undefined `gs` is up to the viewer, and both answers are bad.**
PDF.js — Nextcloud's own viewer — warns and continues with the *default* graphics state, so a
translucent highlighter came out fully opaque and covering the writing underneath it. A stricter
reader abandons the rest of the content stream, and the rest of the stream is the overlay, so the
page's ink is simply not drawn. Neither reports an error to the user.

**Nothing local noticed, and that is the part worth keeping.** PdfBox re-parses the file happily,
`PdfReader` finds the ink, `detectOpenMode` says `Normal`, the page count is right, and M4's
acceptance tests asserted on the content stream's *text* — which was correct. The file was valid,
parseable, and blank. It took a test that asked *"what does a renderer that is not the library
that wrote this need?"* (`PdfViewerCompatTest`) to see it.

⚠️ **`/Resources` is inheritable, so absent on the page does not mean absent.** A source PDF may
keep one dictionary on a `/Pages` node for every page beneath it, and writing a fresh one on the
page **shadows** it — trading our one missing name for all of theirs. The inherited dictionary is
copied down first, and our entries merged into a *copy*, because a source PDF's resource
dictionaries are routinely shared between pages.

**The same inheritance rule caught a second, worse bug on the way past.** `/Resources`,
`/MediaBox`, `/CropBox` and `/Rotate` are the four inheritable page attributes (PDF 32000-1
§7.7.3.4), and the writer **flattens the page tree** when it reorders pages — which cuts the
intermediate node out of the `/Parent` chain. A page that inherited its `/MediaBox` from that node
was left with **no page size at all**: a viewer either refuses the page or falls back to US
Letter, and the ink — written against the geometry read *before* the reorder — is then on paper of
a different size, which is §4.4's failure arriving by a route §4.4 does not mention. `PDDocument`
and `PdfReader` report the same page count they always did. All four attributes are now copied
down *before* `/Parent` is rewritten, because what a page inherits has to be read through the
chain that is about to be shortened.

---

## 7. Nextcloud integration

### 7.1 Authentication — Login Flow v2

```
POST <server>/index.php/login/v2                (anonymous, no body)
  → { poll: { token, endpoint }, login: <browser URL> }

open `login` in a Custom Tab; user authenticates and approves

POST <poll.endpoint>  body: token=<pollToken>   every 2–5 s
  → 404  while pending
  → 200  { server, loginName, appPassword }     ONCE, then the row is deleted
```

**Six rules, each a real bug if skipped:**

1. **Never hardcode the poll or login URL.** The server builds them with `linkToRouteAbsolute`, so `/index.php` may or may not be present. POST to `poll.endpoint` verbatim.
2. **404 is overloaded** — "not ready", "invalid", and "expired" are indistinguishable. Run your own timer; server-side lifetime is **1200 s**.
3. **Persist the credential before acknowledging the 200.** The server deletes its row immediately; a crash in between is unrecoverable.
4. **Send a human-readable `User-Agent`** (`Inkstone/1.0 (Android)`). It becomes the app-password's display name in the user's security settings.
5. ⚠️ Admins can set `core.login_flow_v2.allowed_user_agents` (PCRE). No match → the browser 403s and **the client polls 404 forever with no signal.** Name this possibility in your timeout message.
6. **Fetch the UID** (§0.1 #3):
   ```
   GET <server>/ocs/v2.php/cloud/user
   OCS-APIRequest: true
   Accept: application/json
   → ocs.data.id      ← THIS is the WebDAV path segment
   ```
   Store both: `loginName` for Basic auth, `id` for the path.

⚠️ **Rules 3 and 6 pull in opposite directions, and M5 found the resolution.** The uid can only be fetched *using* the app password, over the network, which can fail — so the credential is persisted **twice**: once the instant the 200 arrives with `uid = ""` (rule 3 satisfied, nothing lost), and again once the uid is known. An account in that intermediate state is `NEEDS_UID`, is finished on the next launch, and **may not be used for WebDAV in the meantime** — `NextcloudSession` refuses to be constructed without a uid, because the alternative is `/remote.php/dav/files//…` whose 404 reads as "the file is not there". See `docs/M5-NEXTCLOUD.md` §3.1.

**Sign-out:** `DELETE /ocs/v2.php/core/apppassword`. If it returns non-200, **remove the local account anyway** (Nextcloud's own guidance).

### 7.2 On 401 — stop, never retry

Nextcloud's brute-force protection is on by default: escalating delays to 25 s, then **429** after ~10 failures in 30 minutes. Devices behind one NAT share the budget.

- A 401 halts the sync worker and flags `NEEDS_REAUTH`. Confirm once with `GET /ocs/v2.php/cloud/user`; if that also 401s, stop and prompt for re-login.
- **Never auto-retry credentials.** This is the documented cause of real users IP-blocking themselves.
- On 429 honour `Retry-After`, else exponential backoff with jitter.
- `capabilities.bruteforce.delay` non-zero means you're already throttled.

⚠️ **The pull side has to obey this too, and it is where the rule is easiest to break** (M6).
A reconciler that iterated every account and tried anyway would make one request per halted
account **per poll** — and the poll floor is sixty seconds (§7.6), so that is a brute-force
pattern against the user's own server, from inside their own app, on the credential the server
has already refused. An unusable account gets **zero** requests, not one polite probe per pass;
the account-level "your notes have stopped syncing" comes from the stored state, not from asking.
`capabilities.bruteforce.delay` is now acted on rather than merely surfaced: it *widens* the poll
interval, because a non-zero value means this client is already in the penalty box and the only
useful response is to slow down.

### 7.3 WebDAV

**Base:** `<server>/remote.php/dav/files/<uid>/`. No v2 exists. Don't use legacy `/remote.php/webdav`.

**PROPFIND body — complete, including everything later sections need:**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<d:propfind xmlns:d="DAV:"
            xmlns:oc="http://owncloud.org/ns"
            xmlns:nc="http://nextcloud.org/ns">
  <d:prop>
    <d:getetag/><d:getlastmodified/><d:getcontentlength/>
    <d:resourcetype/>
    <oc:id/><oc:fileid/><oc:permissions/><oc:size/><oc:checksums/>
    <nc:data-fingerprint/>
    <nc:lock/><nc:lock-owner-displayname/>
  </d:prop>
</d:propfind>
```

⚠️ **`nc:data-fingerprint` always comes back as a 404 propstat** on Nextcloud 32.0.13, even with
the value set. Keep asking for it — one unrequested-but-harmless property costs nothing and a
future server version may answer — but do not build a behaviour that depends on the answer
arriving. Measured in M6; see §7.6 step 4 for what that costs and what protects against a
restored backup instead.

⚠️ **Always send an explicit `Depth`.** Sabre's default when omitted is `infinity` — a self-inflicted DoS on a large tree.

**Identifier: use `oc:id` everywhere** (globally unique, `<padded-fileid><instanceid>`). `remote_binding.remote_file_id` stores `oc:id`, not `oc:fileid`. Being consistent about this matters if a user ever adds a second instance.

| Property | Note |
|---|---|
| `d:getetag` | Quoted. **Store unquoted, re-add quotes for `If-Match`.** |
| `d:getlastmodified` | Second granularity. Never the sole change signal. |
| `oc:permissions` | Check `W`/`N`/`V`/`D`/`CK` before offering the action. |
| `oc:size` | Recursive folder size; `getcontentlength` is files only. |

⚠️ **Weak ETags:** behind a gzip-compressing proxy, servers may emit `W/"..."`. Returning one in `If-Match` causes a **permanent 412 loop**. Strip `W/` and quotes on read; if you had to strip `W/`, treat the ETag as advisory and compare checksums instead of using `If-Match`.

### 7.4 The two ETag rules that make sync cheap

**Rule 1 — directory ETags propagate.** A change anywhere under a folder changes that folder's ETag, up to the root.
⇒ **The whole poll is one `PROPFIND Depth:0` on the notes root.** Descend only on mismatch, pruning by directory ETag.

**Rule 2 — a file's ETag does NOT change when it moves.** Only source and destination directories change.
⇒ **Same `oc:id` + same ETag + different href = a move.** Update the binding; do not re-download, do not treat as delete-plus-create.

⚠️ **Rule 2 can only fire for a file the walk LISTED, and the walk deliberately does not list
everything** (M7). Every word of *"a different href"* presumes the new href is in hand, and it is
not: rule 3 of §7.6's walk visits only the account's default folder, the parent of every binding and
the pinned folders, because `Depth:1` from `/` is a crawl of the user's photo library. So a note the
user drags in the **web UI** into a folder outside that set is listed nowhere — after which its old
directory is walked and settled without it, and §7.6 step 3's **fourth branch** concludes a remote
deletion. Reported from a real tablet: the local note went to the trash with a *"Deleted on the
server"* row while its file sat untouched on the server one folder away. §13.2 row 11 passed
throughout, because its fixture moves the file *within* a walked folder.

**Fix: stop inferring the file's location and ask for it.** `SEARCH` on `/remote.php/dav/` resolves a
fileid to its current path, verified against Nextcloud 32.0.13:

```
SEARCH /remote.php/dav/    Content-Type: text/xml
<?xml version="1.0" encoding="UTF-8"?>
<d:searchrequest xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
  <d:basicsearch>
    <d:select><d:prop><oc:id/><oc:fileid/><d:getetag/><d:resourcetype/></d:prop></d:select>
    <d:from><d:scope><d:href>/files/<uid></d:href><d:depth>infinity</d:depth></d:scope></d:from>
    <d:where><d:eq><d:prop><oc:fileid/></d:prop><d:literal>1773</d:literal></d:eq></d:where>
  </d:basicsearch>
</d:searchrequest>
   → 207  <d:href>/remote.php/dav/files/inkstone/MoveProbeB/probe.pdf</d:href>
```

Four measured details the implementation depends on:

- ⚠️ **The URL is the DAV root, not `files/<uid>/`.** The search backend is mounted there and the
  `<d:scope>` is what narrows it to one user; `SEARCH` against `files/<uid>/` is a **404**, which is
  indistinguishable from the deletion this is trying to rule out.
- **A fileid with nothing at it answers 207 with an empty `multistatus`** — including one whose file
  is in the server's *own* trash. That is what keeps §13.2 row 14's deletion decidable.
- **`<oc:id/>` is selectable and must be selected.** The numeric fileid is derived from the stored
  `oc:id`'s leading digits (§7.3), and a hit whose identity cannot be *confirmed* must never repath a
  binding — the next `PUT` would land on a stranger's file. An unidentifiable hit is *"cannot tell"*,
  never *"gone"*. (`oc:checksums` is **not** selectable: 404 propstat inside a search.)
- **Two alternatives do not work.** `PROPFIND /remote.php/dav/meta/<id>` is a 404 on 32.0.13
  (`Sabre\DAV\Exception\NotFound: File not found: meta in 'root'`), and `REPORT oc:filter-files`
  supports favourite and systemtag filters only, not fileid.

**Cost:** one `SEARCH` per *absent candidate*, behind step 3's existing guards — never one per file.
An idle account with 200 notes still costs one `PROPFIND Depth:0`. **And a failed search concludes
nothing:** a non-207 or an unreadable body leaves the pass with exactly the conclusion it already
had, because a server with no search backend must not be able to delete notes.

**Caveats:** trash→restore **changes the fileid** (keep a secondary key: content hash + name); one file shared in twice has one id at two paths (key on `(oc:id, path)`); external-storage rescans have changed ids. Degrade gracefully.

✅ `OC-FileId` and `OC-Etag` come back as **response headers on PUT/MOVE/COPY** — no follow-up PROPFIND.

### 7.5 Upload

```
PUT /remote.php/dav/files/<uid>/Notes/Lecture.pdf
If-Match: "<last etag seen>"
X-OC-Mtime: <unix seconds>
OC-Checksum: md5:<hex>
```

| Response | Action |
|---|---|
| 200/201/204 | Persist `OC-Etag` + `OC-FileId` from response headers |
| **412** | Conflict — §7.7. **Never blind-retry.** |
| 507 | Quota exceeded; pause that account's uploads |
| 423 | Locked; retry with backoff (`nc:lock*` says who) |
| 401 | §7.2 — halt |

⚠️ **`OC-Checksum` needs a digest `:core:*` does not have** (M5). Nextcloud *verifies* the header, so a wrong implementation turns every upload into a 400 — and there is no MD5 in common Kotlin, while `java.security.MessageDigest` is JVM-only and would kill the iPad path exactly as OkHttp would. `:core:sync` therefore hand-writes MD5 against RFC 1321's own vectors, for the same reason `:core:storage` hand-writes SHA-256 (§5.4). It is `internal`, used for this one header, and is **not** a security primitive; the content fingerprint is still SHA-256 (§5.6).

**Create:** `If-None-Match: *`. **Move:** `MOVE` with **`Overwrite: F`**.

⚠️ *"Was this note ever uploaded"* — which decides whether a PUT carries `If-None-Match: *` — **must not be read from `oc:id` and the ETag alone** (M5). A reverse proxy that strips `OC-ETag` and `OC-FileId` leaves a successfully uploaded note looking un-uploaded, and the next PUT then sends `If-None-Match: *` against a file that exists and 412s forever. Keep a local upload timestamp in `remote_binding` and include it in the test.

**Chunked upload: not in v1.** ⚠️ Note for later: Nextcloud's *own* Android client silently drops `If-Match` on the chunked path, so chunked uploads there aren't ETag-protected. If you add it, put `If-Match` on the final `MOVE` yourself. Also note a 412 on a large PDF means download-remote → extract → union → rewrite → re-upload, inside a WorkManager job with a 10-minute execution ceiling; for files over ~50 MB that needs a foreground service.

### 7.6 The sync engine

#### Schema

```sql
note(id, title, sync_state, local_rev, last_error, updated_at)

-- local_rev: monotonic integer, bumped on EVERY committed change (journal append or materialize).
-- This is what prevents the classic bug: file changes while a PUT is in flight, 2xx arrives,
-- you mark SYNCED, and the last ten minutes of writing becomes invisible to sync forever.

remote_binding(note_id, account_id, remote_path,
               remote_oc_id,            -- oc:id, not oc:fileid
               last_synced_etag, last_synced_rev)

outbox(note_id, op_type, local_rev_at_enqueue, attempts, next_attempt_at, created_at,
       PRIMARY KEY (note_id, op_type))     -- the coalescing key
```

`sync_state ∈ { LOCAL_ONLY, SYNCED, PENDING_UPLOAD, PENDING_DOWNLOAD, CONFLICT, ERROR, NEEDS_REAUTH }`

*M5:* the enum is `NoteSyncState` in `:core:model` (it lives there because §4.7's compaction rule needs it). It spells the fifth state `CONFLICTED`, and **`ERROR` was missing until M5 added it** — it is where a 507, a forbidden folder and a non-retryable 4xx land, and it has to be distinct from `CONFLICTED` (which needs a merge) and from `NEEDS_REAUTH` (where the credential is fine).

**`PENDING_DOWNLOAD` means "bytes are on disk and a merge is owed"** — the same thing the
`pendingDownload` row means, so the two are written in the same moment and neither is a claim about
the future (M6, close-out). Set ahead of the GET it was exactly that, and nothing on the failure
path took it back: a dropped connection or a 503 left the note stuck in it until some later pass
happened to succeed, which shows a permanent "downloading" badge and — because the state is not
`SYNCED` or `LOCAL_ONLY` — stops the note compacting for as long as it lasts (§4.7). A failed
download reports itself and leaves the note's standing exactly as the last completed pass left it.

*M6 adds an eighth: **`PAUSED`** — bound, but its account is signed out.* It exists because the
alternative was `LOCAL_ONLY`, and that is a lie the user can see: `LOCAL_ONLY` means *"never bound
to a server"*, so the library showed **no sync badge at all** on a note that is sitting on
Nextcloud right now, and "is this one actually up there?" is the question the badge exists to
answer. It is also **not compactable**, unlike `LOCAL_ONLY` and `SYNCED` (§4.7).

The account states gain a matching one: **`SIGNED_OUT`**. M5's sign-out deleted the account row
*and every binding*, so signing back in meant re-typing a URL and then re-binding **every note,
one at a time, choosing its folder again** — because `remote_binding` was the only record of which
note lived where. None of that was needed: the row holds a URL, a login name, a uid, a default
folder and a cached capabilities document, and the one secret — the app password — is exactly what
this state does not keep (§14). So the account row and the bindings survive, `isUsable` is false so
no request can be made with it, and signing in again resumes every note where it left off. What
distinguishes it from `NEEDS_REAUTH` is **whose decision it was**: a reauth is the server refusing
us and warrants a warning, this is the user doing what they asked for and warrants no alarm at all.
The outbox rows *are* dropped, because work queued for an account that cannot send it is M5's
silent stall.

**New tables in M6** (`4.sqm`, schema version 5), all additive, no BLOB column and no foreign key:
`remoteDirectory` (§7.4 rule 1's ETag cache), `syncCursor` (the per-account root ETag and
`nc:data-fingerprint`), `pendingDownload` (a marker for a download not yet merged — a path, never
bytes), `syncAttention` (§7.7's list), `folderBinding` (§9's mirror), `peerClock` (§4.7's
causal-stability horizon), plus one column on `remote_binding`:

```sql
lastSeenRemoteAt   INTEGER NOT NULL DEFAULT 0   -- stamped only on a directory that was WALKED
```

⚠️ **`lastSeenRemoteAt` must be carried forward by every binding write, not passed in.**
`INSERT OR REPLACE` deletes the conflicting row and inserts a new one, so a column omitted from
the statement comes back as its default of 0 — *"never seen"*. Every ordinary binding write (an
upload stamping an ETag, a MOVE recording a path) would therefore reset it, and the next reconcile
pass would read **the whole library as absent remotely and trash it**. The upsert reads the old
value back through a subquery; one statement changes it.

**Two more, added by M6's own review** (`5.sqm`, schema version 6 — both additive, both defaults
meaning what the code did before them; `docs/M6-SYNC.md` §5.5 has the failures):

```sql
CREATE TABLE foreignRemote (...)                             -- "this remote PDF is not one of ours"
ALTER TABLE syncCursor ADD COLUMN refusedUntil INTEGER ...   -- when a refused 401 may be met again
```

⚠️ **`foreignRemote` is not an optimisation.** Identity lives in the file (§5.6) and the marker is in
the PDF catalog, so *"is this ours"* costs a whole download — and a directory's ETag moves on every
note upload into it. Without the row, each unrecognised PDF beside the notes is re-fetched on every
save **and spends one unit of the pass budget**, so `limit` of them ahead of the notes in listing
order starves the pass permanently while the poll reports success. The row answers for a *version*
(ETag plus `OC-Checksum`), never for a path: a path that held a stranger's scan yesterday can hold a
peer's note today.

⚠️ **`refusedUntil` is CLAUDE.md rule 5.** §7.2's confirmation call has three answers and only *"the
credential is dead"* halts the account; the other two leave it ACTIVE, and an ACTIVE account is one
the sixty-second poll walks again. Persisted rather than held in memory because WorkManager restarts
the process, and a cooldown cleared by every wake-up is not a cooldown.

#### Outbox semantics ⭐

**Op types:** `UPLOAD`, `MOVE`, `DELETE`, `MKCOL`.

**Rows are markers, never payloads.** Never store note bytes in the outbox — that's a second copy of every note. The drainer reads a snapshot of `document.pdf` at drain time.

**Coalescing rules:**

| Situation | Result |
|---|---|
| UPLOAD enqueued while UPLOAD pending | UPSERT on `(note_id, UPLOAD)`. Keep the earliest `created_at`, update `local_rev_at_enqueue`, **inherit existing backoff** (don't reset — that's a hot loop). |
| MOVE then UPLOAD | Both rows kept. **MOVE always drains before UPLOAD** for the same note. |
| DELETE enqueued with UPLOAD pending | DELETE **annihilates** the pending UPLOAD. If the note was ever uploaded, the remote DELETE still runs. |
| MKCOL | Drains before any op targeting a path inside it. |

**Marking SYNCED:** on 2xx, set `SYNCED` **only if `note.local_rev == outbox.local_rev_at_enqueue`**. Otherwise the note changed mid-flight — re-enqueue instead.

**Rate limiting:** minimum **30 s** between uploads of the same note while actively editing. This resolves the tension between "coalesce aggressively" and "upload immediately if online" — immediate means *at most once per 30 s*, not once per stroke.

#### Binding a note to Nextcloud

The core user-facing feature:

1. New note → `LOCAL_ONLY`, immediately usable, no network
2. **"Save to Nextcloud…"** → account → remote folder browser (PROPFIND `Depth:1`) → confirm
3. Creates `remote_binding`, enqueues `MKCOL` (if needed) then `UPLOAD`, sets `PENDING_UPLOAD`
4. **The note is fully editable throughout.** Binding never blocks editing.
5. A default folder per account makes step 2 one tap

**Deleting a bound note:** local trash immediately + enqueue remote `DELETE` (which lands in Nextcloud's trash). Restoring from local trash within 30 days re-enqueues an `UPLOAD` — note that a Nextcloud trash-restore **changes the fileid**, so re-bind by path and repair `remote_oc_id` from the PUT response.

⚠️ **A note the *server* deleted takes a different path, and confusing the two is destructive**
(M6). The obvious call is the one above — and a DELETE **annihilates the pending UPLOAD**, which
is correct when the user deletes a note and catastrophic here: §13.2 row 14's premise is a note
with *local unsynced edits* whose remote copy was deleted from another device, and annihilating
that UPLOAD destroys the only queued route those edits had to the server. Every comment on that
path reads correct while it happens. There is also nothing to delete — the file is already in
Nextcloud's own trash, put there by whoever removed it, so a DELETE is a 404 at best. So: the
queued rows go, the local note goes to **local trash** (never a hard delete, rule 1), and the
binding **stays with its remote identity cleared** — which is what makes a restore re-upload as a
*create*, and what stops the next pass concluding the same deletion again.

#### The reconciler (pull)

```
1. PROPFIND Depth:0 on the notes root → root ETag unchanged? DONE. (The common case, one tiny request.)

2. Changed → walk down Depth:1, pruning subtrees whose directory ETag matches cache

3. Per remote file, match on oc:id:
   ├─ known id, same etag, different href  → MOVED. Update path. No download.
   ├─ known id, different etag             → REMOTE EDIT. Download → merge (§7.7).
   ├─ unknown id, PDF with an inkstone
   │  XMP marker                           → offer import
   └─ known id absent remotely             → DELETED REMOTELY. Local copy → trash.
                                             NEVER hard-delete.

4. `nc:data-fingerprint` changed → admin restored a backup. Invalidate all cached ETags
   and full-reconcile rather than trusting them.
```

⚠️ **Step 4 does not work against a real Nextcloud, and M6 measured that rather than assuming
it.** `nc:data-fingerprint` is **not retrievable over WebDAV** on Nextcloud 32.0.13 — not even
when the value is set. With `occ maintenance:data-fingerprint` having just reported *"Updated
data-fingerprint to e137b2ff…"*, the property comes back as a **404 propstat** from every
endpoint it could plausibly live on, and is absent from the two documents that might carry it
instead:

```
PROPFIND /remote.php/dav/files/<uid>/   →  <nc:data-fingerprint/>  404 Not Found
PROPFIND /remote.php/webdav/            →  404
PROPFIND /remote.php/dav/               →  404
GET      /ocs/v2.php/cloud/capabilities →  absent
GET      /status.php                    →  absent
```

So the backup-restore safety net this step describes **has no trigger in production.** The
`fingerprintMoved` branch in `Reconciler` is retained — it is correct if a server ever does
report the property, and costs one nullable comparison — but it must not be counted as the
protection against a restored backup.

**What actually protects against it** is `syncCursor.lastFullAtEpochMillis`: a periodic
*unpruned* walk, which is the backstop for every way ETag pruning can lie, restores included.
That is the mechanism to strengthen if this matters, not the fingerprint.

**And absent must never mean changed.** The comparison requires *both* the cached and the
reported value to be non-null. Reading "no property" as "it changed" would force an unpruned
walk on **every poll against every real Nextcloud** — permanent request amplification, which is
the failure §7.2 exists to prevent. SPEC §13.2 row 15's live half therefore asserts the
asymmetry (an absent fingerprint clears no cache, downloads nothing and trashes nothing) and
skips the change-detection half loudly, naming this defect as the reason.

#### What the four steps above leave unsaid — filled in by M6

Each of these fails **silently**: the poll reports success and a note simply stops syncing.

- **A weak root ETag never short-circuits.** A false *"unchanged"* means the pull never happens;
  a false *"changed"* costs one request. Take the expensive direction (§7.3).
- **Step 3's fourth branch is only decidable for a directory that was walked *and settled*.** A
  pruned subtree leaves its bindings unstamped exactly as a deleted file does, so "known id absent
  remotely" must be intersected with the set of directories this pass actually descended into —
  and must additionally require that the note was **ever uploaded**. Without the intersection,
  one lucky ETag match puts a hundred notes in the trash.

  ⚠️ *"Walked"* alone is **not enough**, and M6 shipped it that way — this sentence is the repair.
  A listing coming back says nothing about whether its *files* were looked at. Three ways a
  directory is walked with its files unread, and all three trashed live notes: the pass **limit**
  cut the file loop short (so any library bigger than the limit lost its tail on the first poll), a
  **404** was recorded as an empty listing (so renaming a bound folder in the web UI — §7.4 rule 2's
  *move* — trashed everything under it), and a file in it failed. The guard is
  `walked − unfinished`, and being over-conservative here only ever *keeps* a note.

  ⚠️ **And even a settled directory only proves the file is not *there*** (M7). The walk visits the
  folders this account already has notes in, so "absent from all of them" includes every file the
  user moved somewhere else — see §7.4 rule 2, which cannot fire without the new href. Before this
  branch may conclude anything, the binding's fileid is `SEARCH`ed for; found is a move, an empty
  answer is a deletion, and a search that failed leaves the walk's conclusion alone. The search sits
  **behind** the guards above, or it becomes one request per note in the library per poll, and
  **before** the trash gate, or a note an editor holds never gets the benefit of it.
- **The trash is not reachable while an editor holds the note.** `NoteTrash.trash` moves
  `notes/<id>/`, a live session is not told, and its next autosave recreates the directory with a
  journal and no bundle — after which the note is unrestorable, unopenable, and destroyed by the
  30-day sweeper. The pull-side trash therefore has an answer meaning *"not now"*, and the binding
  is left unstamped so a later pass concludes it again. (`NoteTrash.restore` also tells a stub from
  a live note, and sets the stub aside into the trash rather than deleting it — rule 1.)
- **A 401 is neither a failure nor a deferral.** §7.2's confirmation call has three answers and only
  *"the credential is dead"* halts the account; the other two leave it ACTIVE. Counting the 401 as a
  failure therefore reports *"run again"* with nothing halted, and WorkManager's exponential backoff
  becomes the credential retry loop rule 5 forbids. It needs its own counter, an unread-but-not-owed
  subtree, one confirmation call **per pass** (a 401 abandons the whole tree, downloads included),
  and a per-account cooldown — or the sixty-second poll is the ladder's slower half.
- **A directory's cached ETag is written only when everything under it finished.** The row means
  *"fully reconciled at this version"*. Writing one over an unmerged download prunes that subtree
  on every later pass until something else in it happens to change.
- **Exactly one pass runs at a time, and only the reconciler can promise that** (M6). Three unique
  WorkManager names run the same whole-account pass — the foreground poll, the periodic job, and one
  per note a 412 refused — and a unique-work policy deduplicates only *within* a name. Two passes
  share the staging directory and the ETag cache, and every way that sharing goes wrong is a
  **conflict** and not an error: two GETs of one note interleave into one fixed temp filename and
  rename a torn PDF into place (rule 10), one pass's `discard` deletes the bundle the other is about
  to merge, and a cache row written over the other's unmerged download prunes that subtree. The
  result is a conflicted copy minted from a race, with both copies identical. A pass that finds
  another in flight must make **zero requests** and report that it did nothing — never wait, or a
  thundering herd of workers each holds a thread for the length of somebody else's pass.
- **A merge into an *open* note must be serialized against the editor, not merely handed to it.**
  Delivering the merge into the editor's coroutine scope keeps the one-session-per-note rule and
  still lets it interleave with the two-second autosave at every suspension point — and
  `materialize` snapshots every page *before* it resets the journal, so a stroke flushed in between
  is in no journal, no bundle and no cache, with `local_rev` regressing over the flush's so that the
  index and the binding both agree nothing is owed. The editor holds one lock across each of its
  operations; it is taken **from construction until the note is open**, so a pull that arrives
  during the note-open I/O waits for that session instead of being told there is none — which is
  what made a second session, and a stale byte-offset reader, reachable at all.
- **The walk starts at the notes roots, not the DAV root.** `Depth:1` from `/` crawls the user's
  photo library.
- **Step 2's `nc:data-fingerprint` check comes before the root-ETag check**, or a restored backup
  is short-circuited away by a cached ETag that is no longer about the same bytes.

#### ⭐ What the reconciler does with the outbox — the ETag ping-pong's termination argument

The reconciler performs **no remote writes** (CLAUDE.md rule 4 has no pull-side exemption). What
it *does* is decide whether the merge leaves this device owing the server an upload, and the
decision is a three-row table:

| local had novel ops | remote had novel ops | Action |
|---|---|---|
| no | no | advance `last_synced_etag`, stamp seen, `SYNCED`. **No UPLOAD.** |
| no | yes | fast-forward: advance the ETag *and* `last_synced_rev`, `SYNCED`. **No UPLOAD** — re-uploading a note that only absorbed the remote's work churns the ETag and tells the peer to pull again. |
| yes | — | advance `last_synced_etag` **first**, then enqueue one UPLOAD, which carries `If-Match: <the ETag just downloaded>`. |

**This table is the whole reason two devices stop talking.** The obvious wiring — report a merge
through the same path a local save takes — ends in an unconditional upload, which changes the
remote ETag, which the peer sees as a remote change on its next poll, forever, at the poll
interval, looking exactly like healthy sync. With the table: both devices upload once after a true
merge, the loser's PUT 412s, the 412 triggers a reconcile of that one note, and *that* pass finds
no novel local ops and stops. **Bounded at two round trips per real conflict** — and worth an
automated test of its own, because an echo loop is invisible until it is hammering a real server.

⚠️ **A 412 parks the UPLOAD for a day, and a completed merge is what un-parks it.** Coalescing
deliberately *inherits* `next_attempt_at` (resetting it per save is the hot loop this section
warns about), so without an explicit un-park the merge that resolves the conflict inherits the
day-long park and the union never uploads. The distinction is that a backoff is about the
transport and a park is about a conflict; only the merge knows the conflict is over. `attempts` is
carried over rather than cleared — that history is still true, and the pass has just proved the
server is reachable.

⚠️ **A note the reconciler *downloads* is bound at the version it downloaded, and owes nothing.**
Binding it the way the "Save to Nextcloud…" flow does always enqueues MKCOL then UPLOAD — and that
binding has no ETag and no `oc:id`, so *"was this ever uploaded"* is false, so the next PUT sends
`If-None-Match: *` at the very file it was created from: a **permanent 412**, a day-long park, and
the note flagged `CONFLICTED`. A note that has just arrived byte-identical to the server's copy
reads as a conflict, and because a park is inherited, the next real edit to it never uploads
either.

#### Polling and push

**Assume `notify_push` is absent** — separate app + Rust daemon + Redis + reverse proxy. Default installs don't have it.

- **Baseline: poll.** Honour `capabilities.core.pollinterval` (default 60 s) as the floor. Foreground: that interval. Background: WorkManager periodic ≥15 min.
  ⚠️ **The floor is on the interval between requests, not on the interval inside one loop** (M6,
  close-out). The foreground ticker is alive only while a screen is, and Android's lifecycle
  stops and starts screens far more often than a minute: a rotation is `onStop` then `onStart`
  with nothing in front, so the loop is cancelled and a new one begins. A loop that polls and
  *then* sleeps keeps its pacing in its own stack frame, which the cancellation discards — so ten
  rotations were ten passes inside a minute. The last-poll instant is remembered across the
  cancellation and the wait is computed at the head of the loop.
- **If push exists** (`capabilities.notify_push.endpoints.websocket`), use it as an optimisation only. Nextcloud's README states notifications are **best-effort** — updates can occur without one, and one can fire without an update. Push never replaces polling; it lets you drop background polling to ~1 h as a safety net.
- Push auth is a bespoke handshake: connect the WebSocket, then send **two separate text frames** (username, then app password); server replies `"authenticated"`. Then send `listen notify_file_id` to receive changed file IDs and skip the tree walk.

### 7.7 Conflict resolution — the never-lose-data contract

**Layer 1 — op-log merge (~95% of real conflicts).** Union the ops, sort by Lamport clock, fold from empty (§4.3). Grow-only objects, tombstone deletes, LWW registers for transform/style/z-order. Two people writing in different corners of a page both keep their work, automatically, no prompt.

**Layer 2 — structural.** `pageOrder` merges by fractional key (§4.3); pages missing from the winning order are appended, never dropped. Title and other document metadata are LWW with the override recorded in history.

⚠️ **"LWW" had nothing to resolve with until M6.** A title was a bare `String`, so two devices
renaming one note offline gave a merge no way to tell which rename was later. Both available
answers were wrong: order by `modifiedEpochMillis`, which CLAUDE.md forbids outright because
device clocks disagree (§4.7's `nowEpochMillis` is documented as the *one* legitimate exception);
or take the lexicographically smaller string, which is deterministic and converges and **silently
discards whichever rename the user actually meant** — the same trap §4.3 rejects for page order,
where the fix was to keep both, and a title cannot be kept twice.

So `Document.titleClock: LamportClock?` (§4.1), stamped by `rename` from the same generator every
op uses, resolved by the same `Register` semantics: the remote wins only on a **strictly later**
clock, which is what makes the merge give the same answer in either direction rather than
oscillating. Null means *"never renamed, or renamed by a build older than M6"*, and null loses to
any recorded clock — a device that knows when it renamed something knows more than one that does
not. It is an additive manifest field with `schemaVersion` still 1; §5.8 explains why that is safe
and what had to be fixed first.

When the local title loses, the string the user typed goes into the "Needs attention" list as a
`TITLE_OVERRIDDEN` item — that is this section's *"with the override recorded in history"*, and
dropping it is the one place layer 2 could quietly discard something somebody wrote. Page-order
merges stay silent: there is nothing for a user to decide about a fractional key.

⚠️ **Three things had to be connected before any of the above did anything**, and M6 shipped
without them — the mechanism was complete and unreachable at both ends, which is the failure mode
this milestone hit three times:

- **A writer.** The only thing that stamps `titleClock` is `NoteSession.rename`, and the production
  rename was the library's, which renamed an index row and moved a file. A rename therefore goes
  through the note's **one session** (`NoteSessionHost.renameNote`, handing off to a live editor if
  one holds the note), and it **materializes**: a published PDF embeds `note.inkstone` as it is on
  disk, so a title that reached only the journal is a title no peer receives.
- **A reader.** The resolved title is written back to the library index by
  `LibraryRepository.notePulled`. `recordSave` has no title column, so without it a peer's rename
  landed in the bundle and the library tile — the only place a user sees a title — kept the old
  string permanently.
- **The counter.** A rename consumes a Lamport counter, and a bundle's `highestClockCounter` was
  computed over page ops only, so the session after a close re-issued it. Two devices then held two
  titles at one *identical* clock; `Register` needs strictly later, so each refused the other's
  rename forever with `titleOverridden` false and nothing in the attention list. The invariant now
  lives on `BundleContents`, which every bundle writer goes through, and `NoteStore.open` folds in a
  rename that has only been flushed.

And because a rename changes the filename outside the bundle as well as the title inside it, the
peer's pull sees **a new ETag at a new href** — the ordinary case, not an edge one. `RemoteVersion`
carries no path, so the binding has to follow the file itself (`Reconciler`); a binding left on the
old href queues its next MOVE from a file that no longer exists, which 404s, parks for a day, and
takes every later upload of that note with it because MOVE drains before UPLOAD.

**Layer 3 — unmergeable.** Write `Lecture Notes (conflicted copy 2026-08-05 143012).pdf` alongside. Keep both. Surface in "Needs attention".

*M6 fixes the triggers, the contents and the name.* **Exactly three things reach layer 3**, and
they are the only three: a remote file whose bundle schema is **too new** (§5.6 row 4); a remote
file that opens **Recovered** (a peer's file lost its bundle — auto-recovering it would let a
lossy REPLACE stamped past every clock it has seen overwrite this device's full-fidelity note, and
look like a successful sync while doing it); and a **412 that survives a merge attempt**.

**A conflicted copy is the remote bytes, verbatim.** Nothing is re-written and no PDF is
generated — which is rule 1 taken literally, needs no new PDF code, and is only sound because M5
proved a published PDF round-trips a Nextcloud PUT byte-identically. The local note keeps its
`DocumentId`, its journal, its undo stack and its binding; **nothing local is superseded.**

**The copy gets a new `DocumentId` and new `ObjectId`s, and all three reasons are independent:**
`notes/<id>/` is one directory and the index's primary key, so a copy sharing the id would *be*
the original, overwritten; the XMP marker carries `documentId`, so a copy keeping it is
indistinguishable from the original to the next reconcile, which would merge the two back together
— and the copy exists precisely because merging was impossible; and keeping `ObjectId`s puts two
objects under one identity in a grow-only union, where the fold's first-write-wins payload rule
makes one copy's geometry silently win on both notes.

**The name has three constraints and each is a bug if missed.** No `:` in the time, because
sanitisation rewrites it and two devices spelling the time differently then land on *different*
paths for one conflict, each creating its own copy. **UTC**, or two tablets in different timezones
do the same thing. And the title gives way to the 250-byte limit, never the stamp — a copy whose
name equals the original's is not a copy.

**And it is stamped exactly once per conflict** (M6, close-out). The same string is the local copy's
title *and* the remote path the verbatim bytes are PUT to, so a second call to `RemoteName.conflictedCopy`
for one conflict is a second reading of the clock. `Reconciler` stamps it from the pass's own
`passStartedAt`; `NoteStore.conflictedCopyOf` takes the finished name and stamps nothing. It used to
stamp its own, separated from the reconciler's by the length of the download, which gave one conflict
two names — the same failure as the `:` above, reached from the opposite end.

**"Too new" is decided in two places, because the schema version is written in two.** The XMP marker
carries one (§5.6) and the bundle's `manifest.json` carries one, and the marker is read first: a
`TOO_NEW` verdict there never extracts a bundle at all, which is what §5.8 requires. The manifest's is
the fallback for a file where the two disagree, and it has to be read *without* opening the bundle,
because `BundleReader.open` refuses a schema from the future by throwing. `NoteMerge.mergeBundle` owns
that decision — before M6's close-out the check sat behind the open, where no input could reach it, and
every unreadable pull was reported to the user as an unmergeable conflict instead.

⚠️ **One copy per conflict, not one per poll** (M6). Layer 3 deliberately does *not* advance the
file's ETag — that is what makes the pending upload park instead of overwriting a file this build
cannot read, and what lets a later pass find out whether the condition cleared. So the same file is
fetched and refused again on every poll for as long as it lasts, and a directory's ETag moves every
time any note in it uploads, so "every poll" is the ordinary case rather than a rare one. Since a copy
*must* get a new `DocumentId`, an unkeyed layer 3 therefore produced one new local note **and one new
bound remote file per poll** from a single unresolved condition. The identity of a conflict is the pair
(local note, remote file, by `oc:id`), and the record of it is the note's "Needs attention" row, which
is read before anything is minted.

**Dismissing an item is a tombstone, and the dedup has to read through it.** The row stays with a
timestamp (rule 1 has no exception for metadata), and it now carries a second load: it is what
remembers that a conflicted copy already exists, so deleting an acknowledged row would start the
minting again. A rediscovery must therefore compare against **every** row for the id and not only the
open ones — an acknowledged row is by definition not "open", so an open-rows guard passes and the
UPSERT puts the dismissal back to zero. The user could not put an item away for longer than one poll
interval, for conditions like *"the peer's file lost its bundle"* that may never clear on their own.
Something genuinely new still reaches the list, by whichever route applies: a different problem is a
different id and its own row, and the same problem with a different **payload** — the peer's schema
version moved, a copy now exists — is rewritten and re-opened.

**Hard rules:**

1. **Never resolve a conflict by discarding bytes.**
2. **Never show a modal dialog.** Nextcloud's official Android client does exactly this and has ~9 open bugs from it. Resolve automatically and non-destructively; let the user review later.
3. **Never block sync on user input.** A conflicted note is flagged and skipped; everything else keeps syncing.
4. Before any destructive local operation, the previous version goes to local trash (30-day retention).

**Live editing during a remote change:** if a page is open in the editor when a remote op arrives, re-fold and re-render the page, preserving the camera and any in-progress stroke. **Incoming remote ops never enter the local undo stack** (§4.6).

⚠️ **"Preserving the camera" is a rule about what the re-render must *not* call** (M6). Reloading
the page is two indirections from anything camera-shaped: `loadPage` sets `pageSize`, whose setter
calls `resetCamera(FIT_WIDTH)` unconditionally, and also cancels unfinished strokes. The camera is
persisted nowhere, so once refit the previous value is gone — both of this paragraph's forbidden
outcomes, reached by a call that looks like a refresh, with every JVM test still passing. The
supported route is to **diff the folds** and push only the delta with recording off, which is
correct for an append and a re-fold alike and touches neither the camera nor the wet layer.

⚠️ **Not yet true of page *metadata*.** A peer's `SetPageMeta` folds correctly, reaches the bundle,
and does not appear on screen until the page is switched: changing the paper without moving the
camera needs a method `:android:ink` does not have yet, and its `StrokeStore` drops the op besides.
Data-safe, visibly late; recorded in `docs/M6-SYNC.md` and ROADMAP M6 rather than quietly worked
around, because the available workaround is `loadPage`, which is worse.

**One `NoteSession` per note, enforced outside the session.** Two sessions over one note each hold
their own pending ops and their own folded pages, so whichever materializes **second** writes a
bundle missing the other's work — silently, with a valid PDF and the right page count. The session
is single-threaded by contract and deliberately has no lock; the exclusion is a per-note mutex plus
a registry of live editors in `:app`, and when an editor holds the note the merge is handed to *it*
rather than a second session being opened.

**Server-side safety nets** (verify via capabilities, don't assume): Nextcloud `DELETE` is soft; versions live at `/remote.php/dav/versions/<uid>/versions/<FILEID>`, keyed by fileid.

### 7.8 Filename sanitisation

⚠️ **Nextcloud forbids only `\` and `/` by default** — *not* the Windows character set. Also: control chars `[0-31]`, **250-byte** filename limit (bytes, not chars), no empty/`.`/`..`, no `.htaccess`, no `.part`/`.filepart`. **Emoji fail if the DB isn't utf8mb4.**

**Read the real rules at runtime** from `capabilities.files.forbidden_filenames`, `forbidden_filename_basenames`, `forbidden_filename_characters`, `forbidden_filename_extensions` (returned lowercased; compare case-insensitively).

**Then be stricter anyway** — assume Windows rules (`? < > : * | "`, reserved basenames `CON`/`AUX`/`NUL`, no trailing dot or space) so notes sync cleanly to the user's desktop. Cheap insurance, and it makes the §7.9 capability gate redundant by design rather than by accident.

Sanitiser: strip control chars → replace forbidden with `_` → strip forbidden extensions → suffix reserved basenames → truncate to 250 **bytes**, UTF-8-aware → reject empty.

### 7.9 Capabilities

`GET /ocs/v2.php/cloud/capabilities` (`OCS-APIRequest: true`) once at login, again on server version change. Cache it.

Quota via PROPFIND `d:quota-available-bytes` — ⚠️ **sentinel negatives**: `-1` uncomputed, `-2` unknown, `-3` **unlimited**. Check before rendering "N MB left".

---

## 8. Handwriting recognition and search

### 8.1 ML Kit Digital Ink Recognition

`com.google.mlkit:digital-ink-recognition:19.0.0`. On-device, offline, 300+ languages, **stroke-based not bitmap** — which is why it beats image OCR on handwriting.

**Constraints:**

- **Input is x/y/t only. No pressure or tilt.** Your renderer uses them; the recogniser can't.
- Models are **~20 MB per language**, downloaded on demand. Handle failure gracefully — the app must work fully without recognition.
- **⭐ The recogniser assumes a single line of text.** Passing a whole page produces garbage. Segment first (§8.2) and size the `WritingArea` to the line. This is the biggest accuracy lever and the most commonly missed.
- `preContext` (max 20 chars) helps — feed it the previous line.
- Out-of-order strokes degrade results.
- ML Kit sends anonymous performance metrics to Google. Disclose it.

**No successor exists.** ML Kit GenAI and Gemini Nano do **not** do handwriting. Image OCR (Tesseract, PaddleOCR) throws away the temporal signal. Treat this as a single point of dependency and degrade gracefully.

### 8.2 Line segmentation

1. Compute stroke bounding boxes (page space)
2. Cluster by vertical overlap and baseline proximity
3. Order clusters top-to-bottom, strokes left-to-right within
4. Emit one `Ink` per line with a line-sized `WritingArea`

Multi-column and rotated text are out of scope for v1 — detect and skip rather than emitting nonsense.

### 8.3 When it runs

Never during writing. Never on the UI thread. Debounced ~3 s after the last stroke on a page, or on close; batched in WorkManager when charging and idle for backlog. Output goes to `text/<pageId>.json` and the FTS index. **Recognition is advisory — it never modifies strokes.**

### 8.4 Search

SQLite FTS5 over recognised text + titles + folder names. Results carry note + page + page-space bounding box, so a tap scrolls to and highlights the word. The same data feeds §5.5's invisible PDF text layer.

---

## 9. Library and organisation

**v1:** folders (arbitrary nesting, colour, icon) · favourites · title search · full-text search (§8.4) · thumbnails (§5.7) · rename, move, duplicate · **trash, 30-day retention** · sort by name/modified/created · multi-select.

**No hard delete anywhere in the UI.**

**Deferred:** tags, backlinks, page-level linking. *(Goodnotes has had a tagging request open ~10 years; their official workaround is "handwrite the tag and search." Doing it properly later is a real differentiator — but not at the cost of shipping M1–M6.)*

**Folders mirror Nextcloud both directions.** An app folder maps to a remote folder; a move in either place is reflected in the other (§7.4 rule 2). Same mental model in both places — the point of the feature.

#### The mapping, settled in M6

*"An app folder maps to a remote folder"* left the mapping's shape open, and the shape decides
whether the feature is usable. It is a **pin**: the user points one library folder at any path on
the server, and everything beneath it follows. Pin `School` → `Documents/Uni`, and a note in
`School / Physics / Term 1` lands at `Documents/Uni/Physics/Term 1/<note>.pdf` with nothing else
configured. Five rules, each the answer to a way it goes wrong:

1. **The deepest pinned ancestor wins.** Most-specific-wins is the only rule under which pinning a
   subfolder means anything; "shallowest wins" makes the second pin silently dead.
2. **Only folders *below* the pin contribute segments.** The pinned folder gives its *path*, not
   its name — appending the library name on top yields `Documents/Uni/School`, which is not what
   was asked for and cannot be corrected from the UI.
3. **Nothing pinned → the account's default folder**, i.e. exactly the pre-M6 behaviour. This is a
   generalisation, not a replacement, and an unpinned library keeps syncing where it did.
4. **Every derived segment is sanitised; the pinned path is not.** A folder named `Physics / Term 2`
   must become one legal segment rather than two accidental levels (§7.8) — whereas the pinned path
   *is* a path, chosen against a real remote listing, and its slashes are meant.
5. **A broken parent chain degrades to the default folder.** The tree tolerates a missing parent, a
   trashed parent and a cycle (above), so a folder whose ancestry does not reach the root cannot see
   a pin above the break; falling back puts the note somewhere real rather than at a path assembled
   from half an ancestry.

⚠️ **And *"always sync this folder"* is a statement about the notes, not about the mapping.**
Recording the mapping is the easy half and accomplishes nothing on its own: the first cut stored
the pin and bound nothing, so a user pinned a folder holding a note, watched their Nextcloud, and
saw no folder and no upload. Every unit test passed, because the path resolver was right. A pin has
to produce one step per note in the subtree — a *bind* for a note that has never been uploaded
(which is what enqueues the `MKCOL` that creates the remote folder), a *move* for one that already
has a binding.

#### What that requires of the folder model (settled in M3)

A folder is a **synced entity**, so three things are fixed now rather than at M5, because changing any of them later means rewriting the user's library rather than changing code:

1. **Identity is a `FolderId`, never a path.** A folder renamed in the Nextcloud web UI is the same folder at a different path; keyed on path that is a delete plus a create, which is how a sync loses a hundred notes to a rename. Same rule as §4.1 for notes and pages.
2. **Order is a fractional key** (§4.3). Two devices that each create a folder offline must both keep theirs.
3. **Names are sanitised at creation** by §7.8's strict ruleset, not at upload. A folder created today has to be nameable on a server the app has not met yet; discovering otherwise after a hundred folders exist means renaming the user's library for them. The UI shows the sanitised name as it is typed.

The tree must tolerate a **missing parent, a trashed parent, and a cycle** — M5 pulls folders one PROPFIND level at a time, and all three are reachable. A cycle that hangs the library is a worse outcome than a folder that shows up at the root.

**Trashing a folder** moves every contained note's directory first, then marks the folder, its descendant folders and those notes in one index transaction. That order is the recoverable one: a crash leaves a note that visibly fails to open (and is repaired by reconciling against the filesystem), rather than a "trashed" row whose directory is still live and which the next purge would quietly destroy. Descendants are marked with the folder they went down with, so the trash shows one entry and restore is one action.

#### Title search in v1

Title and folder search is an indexed prefix/substring match over a **Unicode-case-folded column**, not FTS5 — folding cannot be done in SQL, because SQLite's `lower()` and `COLLATE NOCASE` are ASCII-only and would miss `Übung` for `übung`.

§8.4's FTS5 index is still the plan for **recognised handwriting**, and it arrives with M7 that owns it. It is not built early because its row grain is different — `(noteId, pageId, text, bbox)`, so a tap can scroll to the word — so a note-grain index built in M3 would have to be thrown away rather than extended.

---

## 10. Non-functional requirements

| Area | Requirement | Verified by |
|---|---|---|
| **Ink latency** | ≤ 2 frames behind Samsung Notes at 240 fps capture | M1 gate, §3.6 |
| **Frame rate** | Sustained 60fps (120 on 120Hz) with 5,000 dry strokes | `dumpsys gfxinfo` |
| **Cold start** | < 1.5 s to library on a mid-range tablet | Macrobenchmark |
| **Open 200-page notebook** | < 2 s to first interactive page | Lazy page loading |
| **Crash safety** | Kill mid-stroke → at most the in-progress stroke lost | Instrumented process kill |
| **Offline** | Everything except sync works in airplane mode, indefinitely | Full manual pass |
| **Data loss** | Zero, under every tested failure mode | §13 matrix |
| **Memory** | No OOM on a 500-page PDF-backed notebook | LeakCanary + stress fixture |
| **Battery** | Background sync not a measurable drain | Battery Historian, 24 h |

**Explicit anti-goal:** don't require a flagship. Goodnotes' Android build needs 4 GB / 8" minimum and scores **2.8★ on phones** vs 4.7★ on iOS — the gap is almost entirely performance. **Being fast on mid-range hardware is the competitive position.** Test on a 4 GB device.

---

## 11. iPad path

**What ports:** `:core:model`, `:core:storage`, `:core:database`, `:core:sync`, `:core:search` — document model, sync engine, conflict resolution, search. The majority of non-UI logic.

**What cannot port, on any architecture:** ink capture and rendering (`androidx.ink` is Android-only; iPad needs PencilKit or Metal), PDF writing, and OCR.

**One known cost, decided deliberately in §5.4:** stroke geometry blobs use `ink-storage`'s format, which an iOS renderer can't decode. Sync and merge are unaffected (they operate on op metadata, not geometry). iOS would need a decoder or a portable secondary encoding added at that point.

**Keeping the door open costs two things, both cheap and both due in M0:**

1. CI enforcement that `:core:*` imports no Android
2. **`iosSimulatorArm64` as a real target on every core module from day one** — this is what catches a JVM-only dependency like OkHttp, which the import check would miss entirely

Toolchain is ready: Kotlin 2.3.20, Compose Multiplatform iOS stable since 1.8.0, SQLDelight 2.3.2, Ktor 3.5.0. Keep the KMP boundary coarse — whole documents or pages, never strokes or points.

---

## 12. Out of scope for v1

| Feature | Why not now |
|---|---|
| Real-time collaboration | The op-log supports it; transport and presence UI are their own project |
| AI features | ML Kit GenAI can't do handwriting; cloud AI contradicts offline-first |
| Infinite canvas | Different rendering and viewport model |
| Audio + stroke sync | Large surface, and a data-loss risk area |
| Tags / backlinks | Worth doing properly later |
| Handwriting→text replace-in-place | Recognition exists in M7; the editing UX is its own design problem |
| Editing annotations from imported PDFs | Render and preserve only in v1 |

---

## 13. Test strategy

### 13.1 Format tests

- Round-trip: every schema version → open → edit → save → reopen → byte-equivalent
- Forward compatibility: unknown ops and unknown JSON fields survive untouched
- Fixture files per historical schema version, **committed**
- **On-device migration test:** install version N with real data, upgrade to N+1, verify migration
- **Library rebuild:** delete the SQLite database, rebuild the library by scanning a folder of PDFs. This is the disaster-recovery story for a design where PDFs are the truth and the DB is an index.

⚠️ **Two of these are still unmet as of M6, and both look met.** No **bundle fixture** has ever been
committed for any schema version, so "round-trip every schema version" is currently a round trip of
whatever the current build writes. And there is still no **on-device migration test**: every
instrumented database test opens an in-memory database (`name = null`), which calls `Schema.create`
and therefore never runs `Schema.migrate` at all, while `verifyMigrations` compares DDL only — so a
migration that is structurally right and mis-defaults existing rows passes both. M6 is the first
milestone to migrate a table already holding real user sync state (`remote_binding`), which is
exactly the case that needed it.

### 13.2 The merge matrix

**All rows are automated tests, run against a real Nextcloud in Docker** — mocks won't reproduce weak ETags, fileid churn on restore, or brute-force lockout.

*M6 wrote all 21, and this line needed one distinction it did not make: **automated** and
**automated and run against a real server** are different claims.* Every row is automated and every
row runs in `./gradlew build`. What it runs against by default is not Docker: it is a real
HTTP/1.1 server on loopback, hand-rolled over `ServerSocket`, that speaks enough Nextcloud to put
the production transport, the real WebDAV client, the real PROPFIND body and the real multistatus
parser in the loop — only the storage behind the socket is fake, and directory ETags are derived
from the subtree so rule 1's propagation is genuinely exercised rather than pruned correctly by
accident. Each row *also* runs against a live account when one is configured, and skips **loudly**
when one is not, printing per row what is proven without a server and what is not. Four rows
(12's torn body, 15's fingerprint, 16's weak ETag, 20's revoked password) need something a healthy
server will not do on request — `occ`, a proxy, or a killed socket — and the in-process server
stands in for those switches; see `docs/M6-SYNC.md`.

**Plus one row this table does not list and the design requires:** after any two-device merge, poll
both devices repeatedly and assert the request count settles to one `PROPFIND Depth:0` each with no
further ETag movement. That is §7.6's termination argument, and an echo loop is invisible until it
is hammering somebody's server.

| # | Scenario | Required outcome |
|---|---|---|
| 1 | Both devices add strokes to the same page offline | Both sets present. No prompt. |
| 2 | A transforms an object, B deletes it concurrently | Delete wins; transform is a no-op. No crash, no ghost. |
| 3 | A and B transform the same object concurrently | LWW by clock. **Never a skewed compound transform** (§4.2). |
| 4 | A restyles an object, B moves it | Both apply — different LWW registers. |
| 5 | **Both devices add a page offline** | **Both pages present**, ordered by fractional key. Neither orphaned. |
| 6 | Both devices reorder pages concurrently | Deterministic, identical order on both. No page lost. |
| 7 | Partial erase (split) vs concurrent transform of the same stroke | Converges; no duplicate fragments. |
| 8 | A compacts while B is offline holding older ops | B's ops reintegrate — snapshot discarded and re-folded (§4.7). |
| 9 | Recovered-mode document re-uploaded | **Strokes are NOT duplicated** (§5.6 replace-not-union). |
| 10 | Both devices online, same page open in the editor | Remote ops apply live; camera and in-progress stroke preserved; local undo unaffected. |
| 11 | User moves the file in the Nextcloud web UI | Detected as a move. No re-download, no duplicate. **Row 11b is the companion the table did not ask for and the design needs**: the same move into a folder **outside every walk root**, where rule 2 has no href to compare and step 3's fourth branch trashed the note instead. Asserts a repathed binding, one `SEARCH`, zero GETs, nothing trashed and an untouched `local_rev` (M7, §7.4 rule 2). |
| 12 | Upload interrupted mid-PUT | Outbox retries. No partial remote file. |
| 13 | 412 on `If-Match` | Merge attempted, then conflicted copy. Never a blind overwrite. |
| 14 | Remote deleted while local has unsynced edits | Local → trash, not hard-deleted. User notified. |
| 15 | Server ETags all change (backup restore) | `nc:data-fingerprint` triggers full reconcile, not mass re-download. ⚠️ **Not reachable on Nextcloud 32.0.13** — the property is a 404 propstat over WebDAV even when set (see §7.6 step 4). The live half asserts the asymmetry instead: an absent fingerprint clears no cache, downloads nothing, trashes nothing. |
| 16 | Weak ETag (`W/"..."`) from a gzip proxy | No 412 loop. Falls back to checksum. |
| 17 | 500 offline edits, then reconnect | **Exactly one** upload. |
| 18 | Note edited while its PUT is in flight | Not marked SYNCED. Re-enqueued (`local_rev` check, §7.6). |
| 19 | App force-stopped with a full outbox | Outbox intact; drains on next launch. |
| 20 | App password revoked server-side | One confirmation probe, then `NEEDS_REAUTH`. **No retry loop.** |
| 21 | Delete a bound note, then restore from local trash | Re-uploads; `remote_oc_id` repaired from the PUT response. |

### 13.3 Crash safety

Instrumented process kill during: stroke capture · journal flush · materialization · upload. Assert bounded, documented loss in each case.

### 13.4 Manual gates

- **M1:** 240 fps side-by-side vs Samsung Notes (§3.6)
- **M6:** a full week of real daily use across two devices with deliberate airplane-mode toggling
- **Every milestone:** one full pass in airplane mode

---

## 14. Security and privacy

- **Credentials:** app password encrypted with AES-GCM, key in the Android Keystore, written to a private file. ⚠️ **Not `EncryptedSharedPreferences`** — deprecated by Google in 2025. Never store the user's real password.
- HTTPS only, no cleartext exception. Refuse an invalid certificate; do not offer "accept anyway".
- Notes in app-private storage. Optional **per-note** biometric lock — not one global password. *(Goodnotes uses a single universal password for all locked documents, which is strictly weaker.)* A locked note is excluded from thumbnails and search results.
- **Zero telemetry by default.** Crash reporting opt-in.
- ML Kit's anonymous performance metrics are the only outbound data besides the user's own Nextcloud — disclose explicitly.
- Note content goes nowhere except the user's own server.

---

## Appendix A — Source-verified claims

Verified 5 Aug 2026:

- androidx.ink 1.0.0 stable; Compose modules only at 1.1.0-alpha06 → [releases/ink](https://developer.android.com/jetpack/androidx/releases/ink) — *corrected in M0 (§3.2: the 1.0.0 Compose set is incomplete, not absent) and extended in M1 (§3.4: much of the brush-authoring surface is `@RestrictTo(LIBRARY_GROUP)`). Both verified against the shipped artifacts, not the release notes.*
- graphics-core 1.0.4; input-motionprediction 1.0.0 → [releases/graphics](https://developer.android.com/jetpack/androidx/releases/graphics), [releases/input](https://developer.android.com/jetpack/androidx/releases/input)
- Stylus MotionEvent APIs → [advanced stylus features](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features)
- S Pen Remote SDK 1.0.2 (Sep 2019); BLE removed from S Pen on Tab S11 → developer.samsung.com/galaxy-spen-remote
- ML Kit Digital Ink 19.0.0; stroke input; 300+ languages; ~20 MB/model; single-line assumption → [ML Kit docs](https://developers.google.com/ml-kit/vision/digital-ink-recognition/android)
- androidx.pdf 1.0.0-alpha19; `EditablePdfViewerFragment` since alpha13 → [releases/pdf](https://developer.android.com/jetpack/androidx/releases/pdf)
- PdfBox-Android last release 2.0.27.0, Jan 2023 → mvnrepository.com/artifact/com.tom-roush/pdfbox-android
- PDF `/Ink` has no per-point width; `/AP` takes precedence; `3 Tr` = invisible text; incremental update preserves original bytes → Adobe PDF Reference; [OCRmyPDF docs](https://ocrmypdf.readthedocs.io/en/latest/advanced.html)
- Login Flow v2 endpoints; 1200 s lifetime; 404 overloading; User-Agent as token name; `loginName` ≠ UID → [Nextcloud dev manual](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/LoginFlow/index.html) + `ClientFlowLoginV2Controller.php`, `LoginFlowV2Mapper.php`
- Directory ETag propagation; file ETag unchanged on move; fileid caveats → [owncloud/client wiki](https://github.com/owncloud/client/wiki/Etags-and-file-ids)
- `If-Match` → 412; official client re-quotes ETags; chunked path drops `If-Match` → `UploadFileRemoteOperation.java`, `ChunkedFileUploadRemoteOperation.java`
- `FILENAME_INVALID_CHARS = '\\/'`; 250-byte limit → `lib/public/Constants.php`, `lib/private/Files/FilenameValidator.php`
- notify_push best-effort and not bundled → [notify_push README](https://github.com/nextcloud/notify_push)
- Brute-force defaults → [Nextcloud admin manual](https://docs.nextcloud.com/server/stable/admin_manual/configuration_server/bruteforce_configuration.html)
- Kotlin 2.3.20; Compose Multiplatform iOS stable since 1.8.0 → [kotlinlang.org/docs/releases](https://kotlinlang.org/docs/releases.html)
- EncryptedSharedPreferences deprecated 2025 → [androidx.security release notes](https://developer.android.com/jetpack/androidx/releases/security)

## Appendix B — Open spikes, each with an owner

| # | Question | Milestone | If the answer is bad |
|---|---|---|---|
| 1 | ~~Does `androidx.pdf` write standard `/Ink` readable by Acrobat, and does variable width survive save→reload?~~ **CLOSED in M4.0: no, and no.** It writes `/Stamp` annotations holding filled paths — the platform's `PdfAnnotationType` has no ink type at all — cannot set `/NM`, and saves by full PDFium re-serialisation, which forecloses §5.2. Width survives outbound only; reading it back gives a polygon. PdfBox-Android behind `PdfWriter` it is, and `androidx.pdf` is dropped entirely, so compileSdk stays 36. See §6.1 and `docs/M4-PDF.md` §1. | M4 ✅ | — |
| 2 | ~~Does `androidx.ink` 1.0.0 apply motion prediction internally?~~ **CLOSED in M1: no.** `input-motionprediction` kept; it is the only predictor in the pipeline. Ink takes prediction as an explicit parameter and un-draws the predicted tail itself. See §3.1 and `docs/M1-INK.md` §1. | M1 ✅ | — |
| 3 | Does `If-Match` work on the chunked-assembly `MOVE`? | Only if chunking is added (post-v1) | Treat chunked uploads as unprotected; verify by checksum after |
| 4 | ~~Does a PDF incremental update survive Nextcloud's storage and any server-side processing intact?~~ **CLOSED in M5: yes, byte-identically.** Verified against a live Nextcloud (`PdfRoundTripSpikeTest`), not only a mock: a real published PDF — original bytes verbatim at the front, one incremental update appended — was PUT, GET back, and compared byte for byte. The original prefix survived, the XMP marker parsed, `detectOpenMode` still returned `Normal`, the page count was unchanged and the `/Ink` annotations were still readable. **§5.2's incremental update stands and the full-rewrite fallback is not needed.** | M5 ✅ | — |

**Three things spike #4 confirmed on the way past**, each worth more than the headline because each was assumed rather than tested:

- **The uid path is right on a real server.** The upload addressed `/remote.php/dav/files/<uid>/…` built from `ocs.data.id` and got a `201`. Had §0.1 #3 been got wrong it would have 404'd — so the project's "most likely bug" is now checked against real hardware.
- **`OC-ETag` and `OC-FileId` really do come back on the PUT** (§7.4), and the ETag was **strong**, no `W/`. That is what keeps an upload to one round trip with no follow-up PROPFIND — and it means the weak-ETag path in §7.3 is a real-but-uncommon case, not the default.
- **The client is byte-transparent independently of the server.** `:core:sync`'s `ByteTransparencyTest` round-trips a 3 MB payload shaped like a published note through the real `WebDavClient`, so a future failure can be attributed to one side or the other rather than guessed at.

Re-run at any time with `INKSTONE_SPIKE_URL` / `_LOGIN` / `_UID` / `_APP_PASSWORD` set; unset, it skips loudly rather than passing quietly. See `docs/M5-NEXTCLOUD.md` §1.
