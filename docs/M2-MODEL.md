# M2 — the document model and storage

What M2 built, what the spec got wrong, and what M3 onwards inherits. Companion to
`docs/M1-INK.md`.

---

## 1. The three irreversible decisions

ROADMAP M2 names three things that cannot be undone later without rewriting sync. All three
landed as specified.

### 1.1 The complete op vocabulary

`core/model/.../PageOp.kt` defines all of it: `AddObject`, `DeleteObject`, `SetTransform`,
`SetStyle`, `SetZOrder`, `SetTextContent`, `SetPageMeta` — plus two the spec's list did not
have and needs:

- **`SetErasedRegion`.** M1 revised §3.5 to erase an *area* rather than input samples, and
  §4.2 as written could not express that. CLAUDE.md rule 8 already anticipated it.
- **`PageOp.Unknown`.** §5.8 requires unknown op types to survive a round-trip untouched, and
  something has to hold them while they do. It carries its own JSON bytes and re-encodes to
  exactly those bytes.

Every op also carries `extras: OpExtras` — the *other* half of §5.8, which is a newer build
adding a field to an op type that already exists. Without it, an older build opening and
autosaving a note would silently drop the newer device's data on every flush.

`BundleRoundTripTest` exercises one of every op and one of every object kind, because a format
test that only covered strokes would let an unencodable `ShapeObject` reach M8 undiscovered.

### 1.2 `SetTransform` carries an absolute matrix

`PageFoldTest.concurrent transforms resolve last-writer-wins — not a skewed compound` is
ROADMAP M2's acceptance criterion and SPEC §13.2 row 3. It runs §4.2's worked example — A
scales, B concurrently rotates, A undoes — and asserts the result is one of the three matrices
written rather than their product, and that it is not skewed.

**One thing the spec's example gets slightly wrong, and it matters for the test.** A *uniform*
scale is a scalar multiple of the identity, so it commutes with rotation: `S⁻¹RS = R`, no skew.
The delta failure needs the **non-uniform** scale a side handle produces. The test says so
explicitly, because the bug being invisible in the simplest case is exactly why it survived
long enough to be worth a spec rule.

The same test folds every permutation of the four ops and asserts they all agree — which is the
whole convergence claim of §4.3, checked rather than asserted.

### 1.3 Stroke geometry is an opaque blob

`:core:*` never looks inside one. `BundleRoundTripTest.stroke geometry comes back
byte-for-byte` feeds it arbitrary bytes, which is the only real test for "never interprets".

**The spec was wrong about how to produce one.** §5.4 said *":android:ink serializes strokes
using androidx.ink:ink-storage"*. `ink-storage` 1.0.0 has **no stroke serializer** — thirteen
classes, and `StrokeInputBatchSerialization` plus `BrushFamilySerialization` are all of it.
`StrokeGeometryCodec` therefore writes a small container of its own; SPEC §5.4 now records the
layout and why the brush is seven fields rather than a serialized `BrushFamily`.

---

## 2. The journal, and what a crash costs

```
notes/<documentId>/
├── note.inkstone     the bundle (M4 embeds these bytes in document.pdf)
├── journal.ops       append-only, flushed ~2 s
├── snapshot.bundle   compaction cache (§4.7)
└── undo.json         local undo/redo stacks — per device, never synced
```

Autosave is **one append**, and `NoteStoreTest.autosave appends and does not rewrite the
bundle` counts the calls to prove it.

The framing is `metaLen | blobLen | crc32 | meta(JSON) | blob(geometry)`. Two things about it
are load-bearing:

- **Geometry is not base64 in a JSON line.** That would add a third to the size of the one
  write that happens while the user is writing.
- **The checksum is not redundant with the lengths.** An append interrupted by power loss can
  leave a record of the right *length* and the wrong *content*. A length-only check accepts it
  and hands a corrupt op to the fold. `a record whose bytes are corrupt is rejected — not
  folded` is that case.

`FakeFileSystem.truncateNextAppendTo` is how a process kill is expressed in a unit test, which
is what makes the acceptance criterion — *at most the in-progress stroke lost* — checkable off
a device. The device half remains a manual gate.

### Two bugs the tests found

Worth recording, because both were silent and both were data loss:

1. **`materialize` wrote empty pages for evicted pages.** It collected each page from the cache
   or from a stale in-memory view of the journal, so on a note with more pages than the cache
   holds, closing it destroyed the session's work on all but the last few pages touched. Found
   by the open-cost test, of all things — the "heavy" note was not heavier, because its ink was
   never written.
2. **Re-folding an evicted page lost unflushed ops.** A page's ops live in three places — the
   bundle, the journal, and `pending` since the last flush — and the reload path consulted one.
   Scrolling a page away and back inside two seconds lost whatever was on it.

Both now have named regression tests.

### A third, found on the tablet: nothing was recorded at all

The first device run reported "undo and redo is not working". The cause was not undo. The ink
surface is created during the first layout pass and opening a note is file I/O, so **the surface
attached before the session existed** — and `onSurfaceAttached` gave up quietly when `session` was
null, leaving the surface with no `PageOpRecorder`. An unbound surface produces no ops, so nothing
was journaled, nothing survived a close, and undo was greyed out because the stack never received
an entry.

Every layer below the view model was tested and correct. The bug lived in the one place that could
not be tested without a device: `viewModelScope` is wired to `Dispatchers.Main` and the autosave
loop is only ended by `onCleared`. So the fix was two things, not one:

- Binding is now driven from **both** sides and is idempotent, so it does not matter which becomes
  ready first.
- The editor is a plain class (`NoteEditor`) taking a scope, a drain dispatcher and the autosave
  interval; `NoteEditorViewModel` is a shell that supplies `viewModelScope`. `NoteEditorBindingTest`
  drives the device's ordering on the JVM and fails without the fix.

The lesson worth keeping: a `?: return` on a field that is populated asynchronously is not a guard,
it is a silent branch. Both of this milestone's other data-loss bugs had the same shape — code that
read one of several places state could be.

---

## 3. What M2 replaced in M1's ink code

M1's `EditHistory` is **gone**. Its own KDoc said M2 would replace the mechanism and keep the
behaviour, and that is what happened:

| M1 | M2 |
|---|---|
| `EditHistory` stack in `StrokeStore` | `UndoStack` in the session, entries persisted to `undo.json` |
| `strokes.undo()` mutates the lists | `session.undo()` appends inverse ops; the surface applies the diff |
| `DryStroke.id: Long` (process-local counter) | `DryStroke.id: ObjectId` (UUIDv7) |
| restoring a stroke needs its old list index | z-order is a fractional key on the object, so it sorts back by construction |
| `clear()` deliberately not undoable | `clear()` emits one `DeleteObject` per stroke, and is |

The gesture grain is unchanged — `beginEdit`/`endEdit` still bracket a whole stroke or a whole
erase drag — and it is now what decides op batching too: `PageOpRecorder.drain()` returns **one
list per gesture**, not one flat list, so a two-second flush that catches three strokes produces
three undo entries rather than one.

Undo/redo state now has exactly one home. The screen is brought in line with the fold by
diffing, never by replaying ops the log already contains.

### Rule 7 on the save path

Encoding a stroke walks every input sample and compresses it — serialization, which rule 7
forbids on the ink path by name. So:

- **UI thread, on `ACTION_UP`:** mint an `ObjectId`, put the immutable `Stroke` on a concurrent
  queue, return.
- **`Dispatchers.Default`, every ~2 s:** drain, encode, append.

`androidx.ink`'s `Stroke` is immutable once finished, so a worker can read one while the UI
thread draws it. No lock anywhere on the edit path.

---

## 4. Things worth knowing before M3

- **`FractionalKey` is base-62 in ASCII order**, so plain string comparison *is* the order. A
  key must never end in the smallest digit or it cannot be used as an upper bound;
  `PageOrder.repair` re-keys an order that arrives malformed from a foreign file, preserving the
  order it reads in (a sort key is derived data — rewriting one loses nothing).
- **The ZIP writer is hand-rolled and stored, not deflated.** `java.util.zip` is JVM-only and
  would have killed the iOS link — the same class of mistake as OkHttp in `:core:*`. Stored
  output is byte-identical for identical content, which is an acceptance criterion, and M4
  compresses the whole bundle as a PDF stream anyway.
- **`NoteSession.open` is the way to start a session.** The constructor takes the clock
  generator and undo stacks so tests can control them, and getting either wrong is a silent data
  bug — a stale clock loses every conflict it takes part in. There is now a `check` for it, added
  after a test hit exactly that.
- **`Compaction.isAllowed` is not called on the autosave path** and should not be: it folds a
  page's whole log. It runs on close.
- **Page cache eviction is unconditionally safe** — a folded page is only ever a cache of the
  log, and the log is on disk or in `pending`.

---

## 5. What M2 does not do

- No PDF. `document.pdf` is not written at all yet; M4 owns materialization, the invisible text
  layer, and `/Ink` annotations. `NoteStore.assertNotStale` is in place for when it exists.
- No library UI. M2 opens the first note in `notes/` and creates one if there is none; M3 owns
  browsing, and `NoteTrash` is already there for it.
- No thumbnails. `NoteLayout.THUMBNAIL_CACHE_DIR` is reserved (§5.7); M3 fills it.
- No sync. `NoteSyncState` is defined so the storage layer can persist one without a migration
  later, and `PageOrder.mergePageOrder` implements §4.3's never-drop-a-page rule with tests —
  M6 is the first caller.
- **Reorder is prev/next, not drag-and-drop.** M2's page bar is the plainest thing that makes
  every acceptance criterion reachable on the tablet; M3's reorder UI and M10's toolbar own the
  real interaction.
