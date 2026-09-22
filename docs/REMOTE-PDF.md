# Writing on a PDF that is already on Nextcloud

*Follow-up to M5/M6. Built 2026-08-26.*

> "I would like to be able to take a file from Nextcloud and write on it in the notes — so if I have
> a PDF on Nextcloud I could just add it to my notes and it would auto sync when I have written on
> it."

## 1. What it does

**Library → `FROM NEXTCLOUD`** opens the server's own file browser with the PDFs shown as well as
the folders. Tapping one downloads it, imports it exactly as a local PDF import would, and binds
the note **to the file it came from**. The user's ink then uploads back onto that path, so the
document they keep in Nextcloud Files gains their handwriting and is readable by every other PDF
reader they own.

The alternative — download a copy and sync it into the notes folder, leaving the original alone —
was offered and declined. *"Back onto the same file."*

Nothing is destroyed by that choice, and it is M4's design that makes the claim true rather than
hopeful: the imported PDF's bytes are the **verbatim prefix** of everything this app ever publishes
for that note (`originalLength` truncate-rebase, `originalSha256` verification, docs/M4-PDF.md
§7.3). The ink arrives as an incremental update after those bytes. Nextcloud's own versioning holds
the pre-annotation file as well.

## 2. The binding is a third kind, and that is the whole risk

`SyncEngine` had two ways to bind a note and **both are wrong here**, each in a way that is silent
for a day:

| | what it means | what it does here |
|---|---|---|
| `bind` | *"save this new note somewhere"* | derives a path from the title, enqueues MKCOL, and sends **`If-None-Match: *`** — at a file that exists. Permanent 412, parked 24 h, note flagged `CONFLICTED`, and the park inherited by every later edit. |
| `bindPulled` | *"this note came **from** the server, so nothing is owed"* | claims `lastSyncedRev = localRev`, so no upload is owed — and the server's copy stays **unmarked**. |
| `bindAdopted` *(new)* | *"the user picked this file to write on"* | binds at the version the **GET** reported, and enqueues exactly one upload, guarded by `If-Match`. |

### 2.1 Why the upload is owed immediately rather than at the first stroke

Leaving the file unmarked until the user draws something is the tempting version, and it is a
conflicted copy waiting to happen. SPEC §5.6 puts identity **in the file**, so `Reconciler.changed`
reads a bound path whose bytes carry no marker as layer 3 — *keep both* — which is right for a
stranger's file arriving at a note's path and wrong for the one this device deliberately adopted.
Three ordinary things put the pass on that branch: a **weak ETag** (`RemoteWalk.unchanged` cannot
answer for one), a proxy rewriting validators, or anybody touching the file.

So the invariant *"a bound file carries this note's marker"* is restored by one upload rather than
left to chance.

### 2.2 …but not in the same second

The adoption stamps `lastUploadAtEpochMillis`, so SPEC §7.6's **30 s floor** applies from the
moment the file is picked. That is deliberate on two counts: the note opens immediately, so the
first thirty seconds are exactly when the first strokes get drawn — marker and ink therefore land
in **one** PUT — and a user who changes their mind and unbinds inside that window has not had their
document rewritten at all.

### 2.3 `lastSyncedRev = 0`

The honest reading, not a trick to force the enqueue: the server holds **none of this note's
revisions**. What it holds is the PDF the note was made from, and the ETag is what remembers that.

## 3. What the file turns out to be still decides everything

`adoptRemotePdf` runs SPEC §5.6's three-way identity decision unchanged, and binds differently per
answer:

| the file is | binding | why |
|---|---|---|
| a stranger's PDF (`Foreign`) | `bindAdopted` | the ordinary case; one upload owed |
| one of our own published notes (`Normal`) | `bindPulled` | the bytes came from the server and are already ours, marker and bundle and all — owing an upload would re-upload what the server has |
| a recovery (`Recovered`) | **neither** | SPEC §5.6 point 4: report the losses first. Binding one would publish this build's reconstruction back over the file it was reconstructed from, stamped past every clock the document has seen, so it would win every later merge and take the lost fidelity with it. The note is imported; the user is told; they can bind it by hand. |
| `TooNew`, or nothing openable | **neither** | not opened and above all not re-saved |

⚠️ **A note this device already syncs is left alone.** Browsing to a file whose note is already
bound must not re-point that binding: the existing one may name a different path, and overwriting
it is how a note starts uploading over a stranger's file.

## 4. Read-only files are imported, not hidden and not refused

`oc:permissions` without `W` — a colleague's shared handout — cannot be annotated in place. Binding
it anyway would 403 on every PUT, park for a day, and present as a note that quietly never syncs.

The picker still **lists** it, marked *read only*, because a file you can see in Nextcloud Files and
not in this picker is indistinguishable from a broken listing. Tapping it imports an ordinary local
note and says why it will not write back.

⚠️ A missing `oc:permissions` counts as writable (`DavResource.mayWrite`'s default). Refusing on an
absent property would make the whole feature vanish against any server or proxy that does not send
it, with nothing on screen to explain it.

## 5. Two things this deliberately does not do

- **The note is filed in the library folder on screen, and that says nothing about where the file
  lives on the server.** Everywhere else a note's remote path is derived from its place in the
  library (SPEC §9's mirror); an adopted note is bound to a path the user chose long before, and
  nothing in this path consults a pin. Filing it under *Chemistry* must not move their handout, and
  does not. (This shipped as *“always the root”* out of caution about that mirror, and §7a records
  why that was over-cautious — and what the one remaining case is.)
- **The download is a foreground request.** Everything else is the outbox by rule (CLAUDE.md rule
  4) — but the outbox exists so that *writes* survive a dead network and a dead process, and this
  is a read whose whole point is that the note opens now. Nothing remote is mutated by it; the
  write that follows goes through the outbox like every other.

## 6. ⚠️ Trashing an adopted note deletes the file it was adopted from

The note **is** that file now, so `noteTrashed` enqueues a DELETE against it. That DELETE goes to
**Nextcloud's own trash** (CLAUDE.md rule 1: *"local trash for local deletes, Nextcloud trash for
remote"*), so it is recoverable for the server's retention period — but *"delete the note"* now
also means *"remove the handout from where I keep it"*, which is not what it means for a note this
app created.

The escape already exists and is one press: **Stop syncing** (`SyncEngine.unbind`) leaves the
server copy exactly where it is, and the note can then be trashed locally on its own.

This is recorded as a known sharp edge rather than fixed. Fixing it means a column on
`remote_binding` saying *"this file was adopted, so unbind instead of DELETE"* — a schema migration
and a new branch in the trash path, which is a larger change than the feature.

## 7. What is checked, and what is not

Buckets per docs/M6-SYNC.md §7 — *written*, *automated*, *automated against the real dependency*,
*run on a device* — because they are not the same set.

| claim | bucket |
|---|---|
| the adopted upload carries `If-Match` and never `If-None-Match: *` | **automated** — `AdoptedFileTest`, the real `SyncEngine` and real `OutboxDrainer` over a mock transport, asserting the header off the wire |
| `bind` on the same fixture *would* have sent `If-None-Match: *` | **automated** — the same file, so a refactor back through `bind` cannot be green |
| the binding carries the GET's ETag, `oc:id`, `everUploaded`, `lastSyncedRev = 0` | **automated** |
| one UPLOAD, no MKCOL; a halted account queues nothing; adoption + 19 edits coalesce to one row | **automated** |
| the file is not written in the first 30 s | **automated** |
| which files the picker offers, and what tapping one decides | **automated** — `RemotePdfPickTest`, 10 cases including `.PDF`, read-only, and a collection |
| `FROM NEXTCLOUD` is reachable and goes to the Nextcloud screen | **run on a device** — emulator, `uiautomator dump` → tap → dump |
| the create bar still fits at 800 dp | **run on a device** — `wm size 1600x2560`; the bar spans 348 dp of 800 dp, all three chips present (docs/M10-PORTRAIT.md's trap) |
| **the browser listing real files, the download, and the round trip to a real server** | ⛔ **not run.** The test Nextcloud is plain HTTP and the app refuses cleartext by three independent gates (CLAUDE.md, *Testing on an emulator*), and sign-in is the user's step by design — the app never sees the password (SPEC §7.1). This needs the tablet and a real HTTPS account, and it is the first thing to do there. |

⚠️ The last row is the one that matters. Everything above it is a fact about a row or a header; the
thing nobody has watched is a real handout coming down and a real stroke going back up.

## 7a. What the first run on the tablet found

Two reports, one real bug and one decision reversed.

### The blank page — `NoteEditorState.documentId` is written after the page is loaded

*"The preview shows the image but when I enter it is completely blank."* Then, decisively: *"If I
add a page then go back to the first page I can see it properly."*

The note, the file and the rasterizer were all correct — the library tile rendered the page from
the same `document.pdf` with the same `PdfPageRasterizer` throughout. What failed was the *first*
request for the paper: `openOrCreate` ends `bindSurface()` → `display()` → `publish()`, `display`
calls `loadPage`, and `NoteEditorViewModel.sourcePage` read the note id out of the **state flow**,
which `publish` has not written yet. Null means *"no PDF behind this page"*, so it painted white
and said nothing; nothing retries, so only a second `loadPage` — turning the page — cured it.

Always losable, never lost in practice, and lost **every time** here: an adopted note's open
follows a burst of index, binding and outbox writes, which changes which coroutine runs first. The
fix is `NoteEditor.openDocumentId`, read from the session rather than from what the screen has been
told. `SourcePageIdentityTest` pins both halves at the same instant — that the editor *can* name
its note during that load, and that the state flow *cannot* — so the second test is the record of
why the first one exists.

⚠️ Worth keeping: an hour went into the download, the publisher and the rasterizer before the
user's own sentence about adding a page pointed at the ordering. **When a symptom has a
reproducible cure, work out what the cure does before hypothesising about the cause.**

### An adopted PDF is filed where the user is standing

*"I can't seem to add it in a folder, it just goes to the root."* It did, deliberately — §5 argued
that a pinned library folder mirrors its notes onto the server, so filing an adopted document in
one might later move it. That was over-cautious: a pin set later leaves already-bound notes where
they are, and nothing in the adopt path consults a pin at all. The note now lands in the library
folder that was on screen, and the remote path is untouched by it.

The one case that does move the file is the user's own explicit *"Always sync here"* on a folder
containing it (`syncFolderToRemote`), which moves every note already in that folder.

## 8. Files

| | |
|---|---|
| `SyncEngine.bindAdopted` | the third binding, and the argument for it |
| `LibraryRepository.adoptRemotePdf` | import + the per-identity binding table above |
| `RemotePdfAdoption` / `RemotePdfPick` | the five outcomes, and the decision hoisted where a JVM test can reach it |
| `NextcloudAccounts.browse` / `.fetchPdf` | the listing keeps its files; the download is one GET |
| `SyncViewModel.openForPdfImport` / `.pickPdf` | the flow; tapping a file *is* the confirmation |
| `SyncScreen.PdfRow` | one file, with `read only` when it is |
| `LibraryScreen.CreateBar` | the entry point, beside `IMPORT PDF` |
