# Pictures that drew as crossed boxes, and a note that grew three times — physics "Electrical energy", 2026-10

The report, in the user's words:

> *"ok there some image problems and performance problems this is shown on my tablet in the note in
> "Physical Science/physics/Electrical energy/Untitled""*

This is the same note `docs/PERF-CRASH-2026-09.md` was about two weeks earlier. It has roughly
tripled since, and most of what follows is what that growth exposed. Every claim says how it was
checked: **tablet** is the user's Galaxy Tab S9 FE+ (SM-X610, Android 16, release build); **emulator**
is the Pixel Tablet AVD (x86_64, debug build) with a byte-identical copy of the note's bundle —
pulled from the published PDF on Nextcloud, SHA-256 matching the XMP marker's `bundleSha256`; **file**
is an offline analysis of that bundle with a script over the page JSON.

---

## 1. The note

| | 2026-09-24 (`PERF-CRASH-2026-09.md`) | 2026-10-10 |
|---|---|---|
| published PDF | — | 21.1 MB, one page, canvas grown to 5,364 × 2,376 pt |
| `note.inkstone` | 6.74 MB | **18.6 MB** |
| page JSON (the whole op log) | 6.13 MB, 4,810 ops | **14.8 MB, 15,209 ops** |
| live objects | 1,140 | **4,116** — 3,903 strokes, 201 shapes, 11 images, 1 text box |
| `SetErasedRegion` bytes | 4.11 MB | **8.55 MB** (57% of the log), 55% of it superseded |
| erase capsules in live regions | ~82k | 75,210 over 262 objects (largest 5,542) |
| z keys | — | **852 characters** at the top; 2.2 MB of the log |
| image assets | 0 | 13 WebPs, all 2,048 × 1,536 (two belong to deleted images) |

*(file)* — counts from a fold of the page JSON; the image sizes read with PIL.

## 2. The pictures — seven of eleven drew the "missing" box

### What the user saw

At Fit Page, seven of the eleven photographs of slides were grey boxes with a dashed edge and a cross
— `PagePainter.paintMissingImage`, which is *"deliberately unmistakable … a crossed one reads as a
fault"*. Only the three-photo stack on the left and one slide in the middle were real. *(emulator,
before: screenshot at Fit Page.)*

### Why

`PageAssetCache` held **48 MB** of decoded pictures, *"about a dozen images at M8c's 2,048 px cap"*.
A 2,048 × 1,536 photograph decodes to `ARGB_8888` at **12 MiB**, so 48 MiB is **four**, exactly — and
four is how many drew. The arithmetic in the KDoc was off by a factor of three, and nothing checked
it against an entry's real size.

Three things made it worse than a small cache:

- **It decoded the note's whole `assets/` family** — thirteen photographs, including the two whose
  images had been deleted (SPEC §4.9.4 keeps an asset forever) — in central-directory order, so the
  last four decoded survived, whichever page they were on.
- **It ran again on every revision** (`LaunchedEffect(…, note.localRev)`), re-reading the bundle and
  re-decoding the nine it had just evicted: ~110 MB of decoded pixels per edit, for nothing.
- **Two of those runs could overlap.** The open's profile shows two `PageAssetCache.refresh` threads
  decoding at once, each evicting the other's work. *(emulator, simpleperf.)*

And beside it, a resource leak: `NoteEditor.assets()` opened a `BundleReader` over a
`FileByteSource` — which holds a descriptor since `PERF-CRASH-2026-09.md` §2 — and never closed it.
StrictMode's close guard logged *"A resource failed to call close"* twice per open of this note.
*(tablet logcat; emulator logcat.)*

### The fix

`PageAssetCache` is rebuilt over a policy class, `PictureCache`, that a JVM test can drive:

- **Only what is asked for is decoded.** The painter's own lookups are the request; a page load also
  names its pictures as it starts (`InkSurfaceView.onPagePictures`), so they decode *while* the
  strokes are being built rather than after the first frame asks. A deleted picture is never asked
  for, so it is never decoded.
- **The page on screen is never evicted.** Pictures from other pages are dropped first, least
  recently drawn first; when only this page's pictures are left, the cache holds them over budget
  rather than evict one being drawn — the alternative is a decode on every frame.
- **The budget is three quarters of the heap limit, between 64 and 256 MiB** — 192 MiB (fifteen
  photographs) on the tablet, whose `heapgrowthlimit` is 256m. A page that still does not fit is
  decoded at a power-of-two downsample, never partly.
- **One batch at a time, one notification per batch** — each notification is a full re-record of a
  4,000-object page, so eleven pictures must not cost eleven.
- **"Being decoded" draws a plain wash, not a fault marker** (`PageAssets.isPending`); a picture
  whose bytes are genuinely missing still gets the cross, and is retried once per revision rather
  than per frame.
- `NoteEditor.assets(names)` reads only the named entries and closes its source in a `finally`,
  including when `BundleReader.open` answers null.

## 3. The open — what it cost, and what changed

### The profile

`simpleperf record -g` on the emulator, opening the note (debug build, so absolute times are 3–4×
the tablet's; proportions are what matter). Per thread:

| thread | where the time went |
|---|---|
| loader (`DefaultDispatch`) | **66% building objects** — `StrokeGeometryCodec.decode`: half `androidx.ink`'s input-batch decode (`DecompressedBytes`), half native tessellation; **25% `BundleReader.page`** — the 14.8 MB JSON tree and `OpCodec` (erase capsules alone 8%) |
| main | **11% `TemplateBackground.drawDots`** — 14× the cost of painting every object on screen; **8% `FractionalKey.isWellFormed`** inside `StrokeStore.install` |
| two I/O threads | `PageAssetCache.refresh`, both decoding all thirteen photographs |

### Three fixes on the main thread

- **The dot grid was one `drawCircle` per dot, unculled.** Its comment budgets *"a Letter page at
  18pt is about 1,400 of them"*; the infinite canvas (SPEC §4.5) grew this paper to 5,364 × 2,376 pt,
  which is **39,000 circles** per re-record of the dry layer — recorded on the UI thread and replayed
  by the RenderThread. Culling would not have been enough: at fit-width every dot is on screen. One
  cell is baked into a tile at the density it will be drawn at and repeated with a `BitmapShader` —
  one rectangle at any size or zoom, the lattice still at exact multiples of the spacing (which
  `SnapGrid` relies on). The published PDF has its own template code and is untouched.
- **`isWellFormed` scanned a 62-character alphabet per character** (`value.all { it in DIGITS }`), and
  `StrokeStore.raiseTop` asks it of every object a load installs. At 852 characters a key that is
  ~26,000 comparisons, times 4,116. It is a range test now.
- **The images no longer cost a second full re-record** after the page appears: they are decoding
  before it does (§2).

### Why the keys were 852 characters

`FractionalKey.between(top, null)` — *put this above everything*, which every stroke drawn does —
**bisected** towards the end of the alphabet: `z`, `zV`, `zk`, `zs`, `zw`, `zy`, `zz`, `zzV`…, one
character longer every ~6 strokes. Linear in the strokes a page has ever had, quadratic in what the log
stores. It now **steps** the first digit that is not `z` (`V`→`W`, `zzk`→`zzl`, `zz`→`zz1`) — a
character per ~61 strokes. Ordering is unchanged (a key is still a digit string compared as a
string), so older builds read the new keys exactly as before; SPEC §4.3 records it. This note's
keys stay at ~852: nothing can sort above an all-`z` key without being longer, and shortening the
existing ones would mean a `SetZOrder` per object.

### The parallel build, and why it did not shorten the open on the emulator

`PreparedPage.build` builds a page's objects in 128-object runs across `Dispatchers.Default`,
rejoined in order, so the objects, registers and erase decisions are exactly `PreparedPage.of`'s
(`PreparedPageParallelTest`). `InkBrushFactory`'s one shared cache became concurrent for it.

On the emulator it made **no difference to the time-to-ink**, and the profile says why: four worker
threads were busy, but **60–72% of each one's time is inside `androidx.ink.storage.DecompressedBytes`**,
which allocates a fresh 32 KB array and a fresh `GZIPInputStream`/`Inflater` for every stroke
(`javap` on `ink-storage` 1.0.0: `newarray byte[32768]`, then `Arrays.fill` of the tail). A 32 KB
array is a large object in ART, and every one goes through `LargeObjectMapSpace::Alloc` under one
lock; the workers were mostly waiting for the allocator and the collector, ~6 CPU-seconds spread over
a 9–11 s phase. The only public decode is `StrokeInputBatch.decode(InputStream)`; the native entry it
calls (`StrokeInputBatchSerializationNative.newFromProto`) is internal. So this is the library's cost
for as long as the blob is its gzipped proto, and the build is kept because it is correct and costs
nothing — whether it helps on the tablet's release build is a measurement, not a claim (§5).

## 4. What was checked, and how

| claim | written | automated | emulator | tablet |
|---|---|---|---|---|
| 48 MiB held four of this note's photographs | — | `PictureCacheTest` (the arithmetic as a test) | ✅ 7/11 crossed before | — |
| every picture on a page stays held | ✅ | `PictureCacheTest` | ✅ **11/11 real at Fit Page** | see §5 |
| a page too big for the budget is decoded smaller | ✅ | `PictureCacheTest` | — (no such page) | — |
| pending draws a wash; missing keeps the cross and retries per revision | ✅ | `PictureCacheTest` (policy only) | — | — |
| the asset reader is closed | ✅ | — | ✅ no *"failed to call close"* in the open's log (was 2) | see §5 |
| a key above the top steps; stacking 1,000 stays ≤ 20 chars | ✅ | `FractionalKeyTest` (4 new) | — | — |
| `isWellFormed` is the alphabet exactly | ✅ | `FractionalKeyTest` | — | — |
| dot grid as one shaded rectangle | ✅ | — (no JVM canvas) | ✅ dots drawn, page renders | see §5 |
| `build` = `of`, object for object | ✅ | `PreparedPageParallelTest` (instrumented) | see the test run | — |
| main-thread stall after the page appears | — | — | **113 + 297 + 321 → 38–50 frames** | see §5 |
| time to ink on open | — | — | ~27 s → ~27 s (GC-bound, §3) | see §5 |
| Fit Page freeze | — | — | 594 → 671 frames, now with 11 photographs drawn instead of 4 | see §5 |

⚠️ The emulator's ink surface composites garbage over part of the page on x86_64 (CLAUDE.md, M10
follow-up). One band appeared over "Symbols used" at Fit Page; panning moved the page under it and
**the band stayed on the same screen pixels**, which is the wet layer and not the dry one. Nothing in
this change draws on the wet layer.

## 5. Release builds, and the tablet

Release vs release on the emulator, same note and protocol (cold start, open, Fit Page): "before" is
the APK pulled off the tablet (installed there 2026-10-09 23:28), "after" is this change; both
`compile -m speed`.

| | before | after |
|---|---|---|
| loading chip gone (tap → ink) | 28.5 s | **24.3 s** |
| main-thread stalls once the page is up | 38 + 335 + 263 frames | **41 + 40 frames** |
| *"A resource failed to call close"* | 2 | **0** |
| Fit Page | 760 + 74 frames | 604 + 76 frames (11 photographs drawn instead of 4) |

The emulator is ~4–5× slower than the tablet in absolute terms.

**On the tablet** (SM-X610, release, `compile -m speed`, same protocol with the tablet's own tile
positions), the build installed there before this change:

| | before |
|---|---|
| loading chip gone (tap → ink) | 5.9 s |
| main-thread stalls once the page is up | 82 + 40 frames |
| *"A resource failed to call close"* | 2 |
| Fit Page | 53 skipped frames; `gfxinfo` 95th percentile **1,050 ms** |
| photographs at Fit Page | **7 of 11 crossed boxes** — the same seven as on the emulator |

This change is **installed** on the tablet (2026-10-10 12:25, APK SHA-256 `DEB98850…`) and opened the
note without a crash (nothing in the crash buffer). The "after" pass has **not** been measured: the
tablet locked itself the moment the note opened, so that run captured the lock screen.

## 6. Found and not fixed

- **The page log still only grows.** 55% of the erase bytes are superseded `SetErasedRegion`s and
  most `SetTransform`s are dominated; `PERF-CRASH-2026-09.md` §6 named it, and it is a SPEC §4.7
  change with a merge-side twin. The open's JSON parse is now the largest single term after stroke
  decoding.
- **`androidx.ink`'s input-batch decode allocates 32 KB per stroke** and serialises on ART's
  large-object allocator (§3). Not reachable through public API.
- **A full re-record of this page is expensive on the GPU side** — 262 `saveLayer`/`DST_OUT` eraser
  composites, ~3,900 mesh draws, and now eleven 12 MiB textures at Fit Page, where each photograph is
  ~150 px on screen. Decoding pictures at the size they are drawn (a downsample chosen per zoom) would
  cut the upload and the memory; not done, because full resolution is what a zoomed-in slide needs
  and the trade wants measuring on the tablet first.
- **Library covers draw the crossed box for every photograph.** `PlatformModule` builds
  `InkPageThumbnailRenderer` without a `PageAssets`, so it gets the default `{ null }`. This note's
  cover happens to show no photographs; a note whose cover area does will show crosses.
- **This note's z keys stay at ~852 characters** (§3).
- **`:app` did not compile when this session started.** `calculator/CalculatorController.kt` (untracked,
  another session's work in progress) declares a new `requests` channel and still uses the old
  `keys` one. It was patched for the build — the old channel declared back, `Job` imported, and the
  two new `CalcEngine` members stubbed in its test's fake — and **restored byte-for-byte afterwards**
  (SHA-256 checked), exactly as the Element session had done the night before.
