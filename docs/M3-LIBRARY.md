# M3 — the library and organisation

What M3 built, the decisions that are hard to reverse, and the two things the spec said
that turned out to be wrong.

Read with `SPEC.md` §5.7 (thumbnails) and §9 (library), and `ROADMAP.md` M3.

---

## 1. The decision the whole milestone turns on

**Thumbnails are keyed by the note's `local_rev`, not by a timestamp.**

SPEC §5.7 says a thumbnail is *"invalidated on materialize"* and leaves the mechanism open.
The obvious mechanism is a file timestamp — is the image older than the note? — and it is
wrong in a way that only shows up occasionally, which is the worst way for it to be wrong:

- A render that starts before an edit and finishes after it writes a file that is *newer*
  than the note and shows the page as it was before.
- Two saves inside one filesystem timestamp tick are indistinguishable.
- A device whose clock moves backwards (which is every device, occasionally) makes every
  cached image look fresh forever.

So the index stores `thumbnailRev`, and freshness is `thumbnailRev == localRev`. `local_rev`
already exists — SPEC §7.6 has it bumped on every committed change — and it is monotonic by
construction.

The part that makes this airtight is that **invalidation is not a separate write.** In
`Library.sq`:

```sql
updateNoteRevision:
UPDATE note SET localRev = :localRev, pageCount = :pageCount, modifiedAt = :modifiedAt,
                thumbnailRev = NULL
WHERE id = :id;
```

One statement. There is no window in which the index reports a fresh thumbnail for content
that has changed, and no ordering for a future change to get backwards.

`ThumbnailCache.invalidate` then **deletes** rather than overwrites, so a crash between "the
note changed" and "the picture was redrawn" leaves a miss — which costs a render — rather
than a hit on the wrong picture, which the user has no way to notice.

### Where the picture comes from

Not the bundle inside the PDF (SPEC §5.7's whole point: 500 notes would be 500 PDF opens and
500 ZIP extractions), and **not the published PDF either**. `document.pdf` is materialized on
close and SPEC §5.1 allows it to be one editing session stale, so a note edited and killed
would show the wrong picture with nothing to reveal it.

The source is the **folded page** — the same `FoldedPage` the editor draws — rendered by
`InkPageThumbnailRenderer` in `:android:ink`. It shares `PagePainter` with `DryStrokesView`,
which is new in M3 and is the reason the library's picture cannot drift from the editor's:
render order (highlighter behind ink), erasure compositing, and the antialiasing-scale
argument are one implementation, not two.

---

## 2. Three tiers, and a bound

`Thumbnails` (in `:app`) resolves a cell's image from, in order:

1. **Memory** — a decoded `ImageBitmap`, in an LRU bounded by *bytes*, not entries. A cover
   at 480 px is ~1.2 MB decoded; an entry-count bound would be a memory bound by accident.
2. **Disk** — `cache/thumbs/<documentId>.webp`, one small read plus a decode. Only consulted
   when the index says the image is fresh.
3. **Render** — fold page one and draw it.

Tier 3 is bounded to **two concurrent renders**. Without that, a fling through a 500-note
library is several hundred concurrent note opens, on a device SPEC §10 says must not need to
be a flagship. With it, the visible cells fill promptly and the rest queue; the user sees
placeholders resolve rather than a stalled app.

Each cell loads in a `LaunchedEffect` keyed on `(id, localRev)`, so a cell scrolled away
cancels its own load, and a cell whose note has *not* changed does not reload when a sibling
does.

---

## 3. Folders are a synced entity, not a UI convenience

SPEC §9: *"folders mirror Nextcloud both directions. An app folder maps to a remote folder;
a move in either place is reflected in the other."* That has three consequences M3 had to
build for now rather than at M5, because getting them wrong later means rewriting the user's
library rather than changing some code:

**Identity is a `FolderId`, never a path.** A folder renamed in the Nextcloud web UI is the
same folder at a different path. Keyed on path, that is a delete plus a create — which is how
a sync loses a hundred notes to a rename.

**Order is a `FractionalKey`**, for the reason page order uses one (SPEC §4.3): two devices
that each create a folder offline must both keep theirs.

**Names are sanitised at creation, not at upload.** `RemoteName.sanitise` implements SPEC
§7.8's *"then be stricter anyway"* — the Windows character set, reserved DOS basenames,
trailing dots and spaces, and a 250-**byte** limit. The dialog shows the sanitised name as
you type it. Sanitising at upload instead would silently rename the user's folders a
milestone after they created them.

M5 adds the runtime read of `capabilities.files.forbidden_*`, which can only ever forbid
more — so a name legal now is legal on a default install.

### The tree tolerates being broken

`FolderTree` handles a missing parent, a trashed parent, and a **cycle**, because M5 pulls
folders from a server one PROPFIND level at a time and all three are reachable. A cycle that
hangs the library is a worse outcome than a folder that shows up at the root. Tested.

---

## 4. No hard delete — what that actually took

ROADMAP M3's hard rule is *"no hard delete anywhere in the UI"*. The way that survives
contact with a UI is by there being no code path from a tap to a destroyed note:

- `TrashScreen` has **Restore** and nothing else. No "delete forever", no "empty trash".
- `LibraryRepository` has no method that destroys a note. `purgeExpired` is driven by a
  30-day clock, runs at startup, and is not reachable from any button.
- The wording is "Move to trash" throughout, including the multi-select bar.

### Trashing a folder

The interesting case, because a folder holds notes and notes are directories on disk.

1. Every note's **directory moves first** (`NoteTrash.trash`).
2. Then one index transaction marks the folder, its descendant folders, and those notes.

That order is not arbitrary. A crash between the two leaves a directory in the trash with a
row that still says "live" — a note that fails to open, which is visible and which
`LibraryRepository.reconcile` repairs. The other order leaves a row saying "trashed" with the
directory still in `notes/`: looks fine, and quietly loses the note on the next purge.

Descendants carry `deletedWithFolderId`, so the trash shows **one** entry for a deleted folder
of fifty notes, and restoring is one action.

Restore goes the other way and one note at a time, because a directory move back can fail —
a live note of the same id already exists, say. `restoreFolderSubtree` therefore touches only
*folder* rows; a note whose files did not come back keeps its trashed row and stays visible in
the trash. There is an instrumented test for exactly this.

---

## 5. Two places the spec was wrong, and one that was incomplete

### 5.1 SPEC §8.4 implies FTS5 for title search. M3 does not build it.

SPEC §8.4 puts titles and folder names in the same FTS5 index as recognised handwriting. M3
needs *"title search returns results as you type"* and builds it as an indexed `LIKE` over a
folded-case column instead.

The reason is grain. M7's FTS row is `(noteId, pageId, text, bbox)` — SPEC §8.4 requires
results to carry a page-space bounding box so a tap can scroll to the word. A note-grain FTS
table built now would have to be **thrown away** rather than extended, and a `LIKE` over 500
rows is instant. SPEC §9 has been annotated; §8.4 is unchanged and still owns the real thing.

### 5.2 Case folding cannot be done in SQL

SQLite's `lower()` and `COLLATE NOCASE` fold ASCII only. A note called `Übung` would not be
found by a search for `übung`, which is not an edge case in a note app used in any language
with accents. So `titleFold` / `nameFold` are written by Kotlin's Unicode-aware `lowercase()`,
and sorting by name uses the same column.

### 5.3 `CanvasStrokeRenderer.create(Boolean, TextureBitmapStore)` is `@RestrictTo`

Another entry in CLAUDE.md's list of `androidx.ink` 1.0.0's stable-but-restricted surface. The
two-argument overload takes `forcePathRendering`, which is exactly what a `Bitmap`-backed
canvas wants; lint fails the build on it. The single-argument `create` is public, and the
renderer decides per draw call from the canvas it is handed anyway — so nothing is lost.

---

## 6. A bug the tests found

`RemoteName.truncateToBytes` stepped by `Char` and charged each surrogate two bytes. That
gives the right *total* for a pair and the wrong answer at the boundary: with two bytes of
room left, the high surrogate fits and the low one does not, and the name ends in half an
emoji — a string that is not valid UTF-16, let alone a legal filename.

Fixed by stepping per code point. It would never have been noticed by hand.

---

## 7. Migrations are verified from this milestone on

`:core:database` now has `verifyMigrations = true` and a committed `1.db` — the pre-M3
schema. The verifier applies `1.sqm` to it and asserts the result matches the `CREATE`
statements in the `.sq` files, so the classic failure (a column added to the schema and
forgotten in the migration, which only crashes on a device that has the old database) fails a
build instead.

One surprise worth writing down: **the schema-generating task does not exist unless
`schemaOutputDirectory` is set.** `generateCommonMainInkstoneDatabaseSchema` is registered
conditionally, and without it `verifyMigrations` fails with "requires a database file to be
present" and no way to produce one.

---

## 8. What M3 does not do

- **No drag-and-drop page reorder.** The overview has thumbnails, tap-to-go and
  move-left/right. The model already supports arbitrary reordering by fractional key, so
  adding the gesture later changes the input and nothing else.
- **No tags, backlinks or page-level links.** SPEC §9 defers them explicitly.
- **No visual design.** M10 owns polish. This is the plainest thing that makes every M3
  acceptance criterion reachable on the tablet.
- **No `remote_binding` or `outbox`.** M5. The `note` table already has SPEC §7.6's columns
  (`syncState`, `localRev`, `lastError`) so M5 adds tables rather than migrating this one.

---

## 9. What still has to be measured on the device

Two of M3's acceptance criteria are device gates and are **outstanding**:

- **500 notes across 50 nested folders → 60 fps.** Needs a fixture generator and
  `dumpsys gfxinfo`, the same instrument M1's frame-rate gate uses.
- **Thumbnails generate off the main thread with no jank while scrolling.** The renderer
  hops to `Dispatchers.Default` itself and generation is bounded to two at a time, but "no
  jank" is a measurement, not an argument.

`LibraryIndexTest` is instrumented rather than a JVM unit test, for the reason `:android:ink`'s
erase tests are — the thing under test is the engine:

```bash
./gradlew :core:database:connectedDebugAndroidTest
```

It has not been run in this session: no device was attached, and the JVM alternative
(`sqldelight-driver-sqlite`) could not be fetched through this network.
