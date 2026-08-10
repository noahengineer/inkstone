# M6 — Nextcloud, part 2: pull, merge, conflicts

What M6 built, the **four silent data-loss bugs** it had to fix before any of it could be
tested, the places SPEC turned out to be wrong, and an honest account of what the 21 rows of
SPEC §13.2 do and do not prove.

Read alongside SPEC §4.1, §4.3, §4.6, §4.7, §5.1, §5.5, §5.8, §6.4, §7.2–§7.7, §9 and §13.

---

## 1. The four bugs that came first

Every one of these is a way this app loses or diverges a user's data **with every op present,
in the same total order, and no error anywhere**. All four existed before M6 wrote a line of
merge code, and none of them could have been found by a milestone with only one device — which
is why they are the top of this document rather than a footnote.

### 1.1 Every device adopted the *creating* device's `deviceId`

The worst of the four, and the smallest diff.

SPEC §4.1 fixes the Lamport comparator as `compareBy({ counter }, { deviceId })`, and
`Register.write` rests its whole correctness argument on that tiebreak being *total*:

> *"Equal clocks are impossible between different devices (the `deviceId` tiebreak) and
> impossible on one device (the counter only increases), so 'not later' resolving to the
> incumbent is a defensive choice with no observable behaviour."*

That sentence was **false**. `NoteEditor.openOrCreate` set
`deviceId = note.document.deviceId.ifEmpty { … }` — read out of the manifest, i.e. from
whichever device *created* the note. So two tablets editing one note stamped every op with the
**same** id.

Two concurrent ops at counter 47 then compare **equal**. `Register.write` keeps the incumbent.
The incumbent is whichever op that device's fold happened to absorb first — which depends on
arrival order, which differs per device. **The two devices hold different values forever.**
Not a lost op; not a corrupted file; a permanent, symmetric disagreement that no amount of
re-syncing repairs, because both devices already have every op and both folds are internally
consistent. That is SPEC §13.2 row 3, failing underneath every other row.

`LibraryRepository` had the mirror-image bug: it minted a fresh id per **process**, so one
tablet broke ties differently after every restart.

Both were written down as temporary. M2's comment said *"M5 moves this to a proper per-install
value"*; M5 did not, and did not notice, because a single device never disagrees with itself.

The fix is `:core:storage`'s `DeviceIdentity`: one id per install, in a plain file
(`device.id`) beside `notes/`, read once and cached. A **file** rather than a database row
because SPEC §13.1's disaster-recovery story deletes the database, and a rebuild that also
re-minted this device's identity would re-open every tie in every note the device had ever
contributed to. A sibling of `notes/`, not a member of it, because it must never travel with a
note that is copied, duplicated, published or synced.

Losing it is survivable by design: SPEC §4.1 anticipates a reinstall getting a new id, and
`LamportClockGenerator`'s `max + 1` rule already handles the consequence.

### 1.2 A peer's strokes could land on the local undo stack, durably

`NoteSession.apply(pageId, ops, undoable: Boolean = true)` — **and the default was the
dangerous one.** A caller that meant to ingest a peer's ops and omitted the third argument put
a collaborator's strokes on the *local* undo stack; `flush()` persisted that to `undo.json`;
and the user's next two-finger tap deleted work they had never made, surviving a restart.

SPEC §4.6 and §7.7 both forbid it, and nothing in `UndoStack` could enforce it, because it was
a property of the call site alone.

The boolean is gone rather than defaulted the other way. Three named methods replace it:

```kotlin
public suspend fun apply(pageId: PageId, ops: List<PageOp>): FoldOutcome    // local gesture
internal suspend fun replay(pageId: PageId, ops: List<PageOp>): FoldOutcome // undo/redo/duplicate
public suspend fun merge(pageId: PageId, remoteOps: List<PageOp>): FoldOutcome // a peer's
```

"Forgot an argument" stops being a reachable state. It also removed an O(n·m) trap:
`Undo.inverseOf` linear-scans the fold once per op, so merging thousands of remote ops through
the undoable path was quadratic before it was wrong.

### 1.3 `materialize` deleted a peer's carried bundle entries

`NoteStore.materialize` took `carried = note.reader?.carried()` — the **local** reader alone.
So the first bundle rewrite after a merge dropped every entry that existed only in the remote
bundle: a peer's recognised text (`text/<pageId>.json`, M7), assets, anything a newer build
added.

SPEC §5.8 requires those to survive a round trip, and the entries lost are **by definition the
ones this build cannot render** — so nothing would ever have looked wrong. The same hole
existed one level up in the manifest (§2.2 below).

### 1.4 An ordinary binding write reset "have we seen this file remotely?"

`lastSeenRemoteAt` is the column that makes SPEC §7.6 step 3's fourth branch — *"known id
absent remotely"* — decidable. `upsertBinding` is `INSERT OR REPLACE`, which **deletes the
conflicting row and inserts a new one**, so a column omitted from the statement comes back as
its `DEFAULT 0`: *never seen*.

Every ordinary binding write would therefore have reset it — a successful upload stamping an
ETag, a MOVE recording a path — and the next reconcile pass would have read **the whole library
as deleted on the server and trashed it**. Nothing hard-deletes, so it would have been
recoverable; it would also have been every note in the user's library, at once, on a poll that
reported success.

The value is now read back through a subquery inside the upsert, and exactly one statement
(`stampBindingSeen`) is allowed to change it.

---

## 2. What was built

| | Where |
|---|---|
| Per-install device identity | `:core:storage` `DeviceIdentity`, `NoteLayout.DEVICE_ID` |
| The union (SPEC §7.7 layers 1–2) | `:core:storage` `NoteMerge`, `NoteSession.merge`/`mergeDocument`/`opIdsOn` |
| Layer 3, byte-preserving | `:core:storage` `ConflictedCopy`, `RemoteName.conflictedCopy` |
| LWW title | `:core:model` `Document.titleClock`/`resolveTitle`, `NoteSession.rename` |
| Forward-compatible manifest | `:core:storage` `Bundle.KNOWN_MANIFEST_FIELDS`, `BundleReader.carriedManifest` |
| Per-page compaction snapshots | `:core:storage` `NoteLayout.snapshot(id, pageId)`, `NoteStore.discardSnapshot` |
| The pull | `:core:sync` `Reconciler` |
| The pruning walk, pure | `:core:sync` `RemoteWalk` |
| Ports the pull needs | `:core:sync` `ReconcilePorts` (`ReconcileStores`, `RemoteMergeGate`) |
| "Needs attention", pure policy | `:core:sync` `Attention`, `AttentionKind` (9 kinds) |
| Pull-side binding + un-parking | `:core:sync` `SyncEngine.bindPulled`/`remoteAdvanced`/`noteDeletedRemotely`, `pauseAccount`/`resumeAccount` |
| 412 → reconcile hop | `:core:sync` `DrainReport.conflicted` → `:app` `OutboxDrainWorker` |
| Schema v5, then v6 | `:core:database` `4.sqm` and `5.sqm` (§5.5), `Sync.sq`, `SqlReconcileStores` |
| Bundle out of a pulled PDF | `:android:pdf` `PulledPdfExtractor` (the module's third public door) |
| `/Resources` in the right place | `:android:pdf` `PdfResources` |
| Single-writer merge gate | `:app` `NoteSessionHost` |
| Foreground poll | `:app` `PollTicker` |
| Background pull | `:app` `ReconcileWorker`, `WorkManagerScheduler.enqueueReconcile` |
| The non-modal list | `:app` `NeedsAttentionScreen`, `AttentionViewModel` |
| Live re-render | `:app` `NoteEditor.mergeRemoteBundle` → `absorbRemote` → `reconcile` (§5.9) |
| Folder pinning | `:core:model` `FolderTree.remoteFolderFor`/`planFolderSync` |
| Reversible sign-out | `AccountState.SIGNED_OUT`, `NoteSyncState.PAUSED` |

### The module edge M6 added: `:core:sync → :core:storage`

One new edge, inside `:core:*`, acyclic:
`:core:database → :core:sync → :core:storage → :core:model`. Both modules are platform-free
KMP, so `checkCoreIsPlatformFree` sees no new `android*` imports and the iOS klib compile is
unaffected. Precedent: M5 added `:core:database → :core:sync` for the same reason (§2 of the M5
notes).

⚠️ **What that does *not* say is that guardrail 2 ran.** `linkIosCore` and every
`link*IosSimulatorArm64` task log `SKIPPED` on this host — Kotlin/Native cannot target Apple on
Windows — so what a local `build` proves for iOS is `compileKotlinIos*` plus the `*MainKlibrary`
and `compileTestKotlinIos*` tasks, which is the klib half. The framework link belongs to the
macOS `ios-core-link` job in `.github/workflows/ci.yml`, and there is no evidence of it having
run for M6. Given that M6 adds the first new `:core:*` edge since M5 and ~1,900 lines to
`:core:sync`, that is worth stating rather than rounding up to "both guardrails green".

The reconciler decides what to do with a merge, so it has to be able to *name* one. Without the
edge, `MergeReport`, `PdfOpenMode` and `NoteLayout.download` would each need a second copy
inside `:core:sync` — a second source of truth for the one decision this codebase has already
spent a milestone getting right (SPEC §5.6). It is `api` and not `implementation` because
`RemoteMergeGate`, which `:app` implements, returns a `MergeReport`.

**Ktor still does not leave `:core:sync`, and this milestone tested that claim by accident.**
`NextcloudTransport`'s constructor is `internal`, so the merge-matrix harness in `:app` could
not hand the client a Ktor `MockEngine` even in a test. Rather than widen it, the harness stands
up a real HTTP server (§7). The boundary held under pressure from the one caller with a good
reason to breach it.

---

## 3. Where SPEC was incomplete or wrong

Six places, all corrected in `SPEC.md` in this milestone.

### 3.1 §7.7 layer 2 called the title LWW and gave nothing to resolve it with

*"Title and other document metadata are LWW with the override recorded in history."* A title
was a bare `String`. Two devices renaming one note offline gave a merge no way to say which
rename was later, and both available answers were wrong:

- **`modifiedEpochMillis`** — forbidden outright. CLAUDE.md: *"Never order sync operations by
  wall clock — device clocks disagree."* §4.7's `nowEpochMillis` is documented as the *one*
  legitimate exception in the codebase, and it is a 30-day horizon, not a tiebreak.
- **Lexicographically smaller wins** — deterministic, converges, needs no new field, and
  **silently discards whichever rename the user actually meant.** It is the same trap §4.3
  rejects for page order; there the answer was to keep both, and a title cannot be kept twice.

So `Document.titleClock: LamportClock?`, stamped by `rename` from the same generator every op
uses and resolved by the same `Register` semantics: the remote wins only on a **strictly later**
clock, which is what makes the merge give the same answer in either direction instead of
oscillating. Null means *"never renamed, or renamed by a build older than M6"*, and null loses
to any recorded clock.

When the local title loses, the string the user typed becomes a `TITLE_OVERRIDDEN` attention
row. That is §7.7's *"recorded in history"*, and it is the one place layer 2 could have quietly
discarded something somebody wrote.

⚠️ **Everything in this section was true and none of it did anything until the review** — no
production writer, no production reader, and a counter that was issued twice. See §5.7.

### 3.2 §5.8's forward-compatibility rule was honoured for ops and not for the manifest

*"Unknown op types and unknown JSON fields are preserved verbatim on round-trip."* M2 honoured
the op half with `OpExtras`. `BundleReader` rebuilt the **manifest** field by field from a fixed
list, so any member it did not recognise was dropped on **every** round trip — the exact failure
the rule exists to prevent, one level up from where it was being checked.

Unknown manifest members are now carried through verbatim, written first and sorted so identical
content still produces identical bytes.

That fix is what lets `titleClock` be an additive field with **`schemaVersion` still 1**. A bump
to 2 makes an older build refuse to open the note at all (`BundleReader.open`'s `require`
throws), and M6's acceptance gate is a week of two-device use where the two devices are very
likely on different builds. An older build that *preserves* an unknown `titleClock` degrades —
it cannot stamp its own renames, so it may lose a later one, which the next rename heals.
Dropping the field corrupts the register for every device, which never heals.

### 3.3 §4.7's snapshot was per document, so row 8 was meaningless

`NoteLayout.snapshot(id)` took only a `DocumentId` and `writeSnapshot` built a single-page
bundle at that one path — while `compactIfDue` loops **every live page** calling it. On a
multi-page note each page overwrote the previous one, so `readSnapshot` answered null for every
page but the last.

Nothing failed: a missing snapshot is a full re-fold, which is correct and merely slower. It
became load-bearing in M6, because SPEC §13.2 row 8 asserts that an op older than the snapshot
discards it and re-folds — and with one snapshot per document **that row passed or failed
according to which page the test happened to pick.** A green result would have proven nothing.

Now `snapshot/<pageId>.bundle`. `discardSnapshot(id)` drops the whole directory, which is the
conservative direction: an out-of-order op is evidence that this device's picture of what a peer
had seen was wrong, and that is a statement about the note rather than about one page of it.

### 3.4 §4.7's compaction gate was two-thirds dead in production

Three separate things, each of which made a documented refusal never fire:

1. **Every caller took `close()`'s `LOCAL_ONLY` default**, so the first and most important
   refusal — *"compact only when `syncState ∈ {LOCAL_ONLY, SYNCED}`"* — had never been evaluated
   against a real state. A `CONFLICTED` or `PENDING_UPLOAD` note was compacted as though the
   server had never heard of it, which is precisely the case the check exists to prevent. The
   default is gone; the parameter is required.
2. **The horizon was measured from `document.createdEpochMillis`**, not from the oldest op being
   folded. On any note older than 30 days the causal-stability gate therefore passed for ops
   written *seconds* ago — the guard inverted, on exactly the long-lived notebooks it protects.
   It now uses the last materialization, which is the honest floor (every journal op was written
   after it) and errs towards refusing, which costs disk and never data.
3. **`oldestAckedCounter` was always null.** The parameter has existed since M2 and no caller
   could fill it, because nothing knew what a peer had seen. M6 is the first milestone where a
   peer exists: every downloaded bundle reports its `highestClockCounter` and its
   `document.deviceId`, recorded per `(noteId, peerDeviceId)` in the new `peerClock` table and
   passed as the `min` across peers.

A fourth refusal was added on the way past: **compaction refuses while the session has unflushed
work.** A snapshot is a cache *of the log*, so folding one over ops that exist only in memory
produces a cache holding state the log does not — and discarding it, which CLAUDE.md rule 9
requires the moment an older op arrives, would lose that work outright. `close()` materializes
first so it cannot happen today; the check is one field read and the cost of being wrong is
silent.

### 3.5 §7.6's state sets were missing two members, both about sign-out

`AccountState` gained **`SIGNED_OUT`** and `NoteSyncState` gained **`PAUSED`**.

M5's sign-out deleted the account row *and every binding*. Signing back in therefore meant
re-typing a server URL and then re-binding **every note, one at a time, choosing its folder
again**, because `remote_binding` was the only record of which note lived where. For a library
of any size that is not a sign-in, it is a migration. None of it was needed: the account row
holds a URL, a login name, a uid, a default folder and a cached capabilities document, and the
one secret — the app password — is exactly what this state does not keep (SPEC §14).

So the row and the bindings survive, `isUsable` is false so no request can be made with the
account, and signing in again resumes every note where it left off — comparing revisions, so a
library of 200 already-synced notes costs **zero** uploads to come back to. What distinguishes
`SIGNED_OUT` from `NEEDS_REAUTH` is whose decision it was: a reauth is the server refusing us
and warrants a warning; this is the user doing what they asked for and warrants no alarm.

The outbox rows *are* dropped, because work queued for an account that cannot send it is M5's
silent stall. `PAUSED` exists for the note-level half: calling such a note `LOCAL_ONLY` is a lie
the user can see, since `LOCAL_ONLY` means *"never bound"* and the library therefore showed **no
sync badge at all** on a note that is sitting on Nextcloud right now. It is also not
compactable, for §3.4's reason with the server as the other tablet.

### 3.6 §9's folder mirror had no mapping model

*"An app folder maps to a remote folder"* left the shape open, and the shape decides whether the
feature is usable. It is a **pin**: point one library folder at any path on the server and
everything beneath it follows. Pin `School` → `Documents/Uni` and a note in
`School / Physics / Term 1` lands at `Documents/Uni/Physics/Term 1/<note>.pdf` with nothing else
configured. Five rules, in SPEC §9; the two that are not obvious are **deepest pin wins** (the
only rule under which pinning a subfolder means anything) and **the pinned folder contributes
its path, not its name** (otherwise you get `Documents/Uni/School`, which cannot be corrected
from the UI).

### 3.7 §6.4 and §5.5 — the `/Resources` defect

The one M6 found in **M4's** code, and the one worth reading if you read nothing else in this
section.

A **form XObject** — which is what an annotation's `/AP /N` is — is a stream *with* its own
`/Resources`. A **page content stream** has none: it resolves every name it uses through the
*page dictionary* (PDF 32000-1 §7.8.3, `/Resources`, *"Required; inheritable"*). M4 set them on
the stream in both cases. So two things were **undefined names in every file this app has ever
published**:

- the flattened overlay's `/GS… gs`, on any page above SPEC §6.4's 500-stroke threshold; and
- SPEC §5.5's invisible text layer's `/InkstoneF0 Tf`, on every page with recognised text.

**What a viewer does with an undefined `gs` is up to the viewer, and both answers are bad.**
PDF.js — which is Nextcloud's own viewer — warns and continues with the *default* graphics
state, so a translucent highlighter came out **fully opaque, covering the writing underneath
it**. A stricter reader abandons the rest of the content stream, and the rest of the stream is
the overlay, so the page's ink is simply **not drawn**. Neither tells the user anything.

**Nothing local noticed, and that is the durable lesson.** PdfBox re-parses the file happily,
`PdfReader` finds the ink, `detectOpenMode` says `Normal`, the page count is right, and M4's
acceptance tests asserted on the content stream's *text* — which was correct. The file was
valid, parseable, and blank. Finding it took a test that asks a different question:
`PdfViewerCompatTest`, *"what does a renderer that is **not** the library that wrote this
need?"* — `/Type /Annot`, a normalised `/Rect`, `/AP /N` as a stream and not a state
sub-dictionary, a `/BBox` that covers the content, `/F` without Hidden, and every name the
content uses actually defined.

**And the inheritance rule caught a second, worse bug.** `/Resources`, `/MediaBox`, `/CropBox`
and `/Rotate` are the four inheritable page attributes (§7.7.3.4), and the writer **flattens the
page tree** when it reorders pages — which cuts the intermediate `/Pages` node out of the
`/Parent` chain. A page that inherited its `/MediaBox` from that node was left with **no page
size at all**: a viewer either refuses the page or falls back to US Letter, and the ink — written
against the geometry read *before* the reorder — is then on paper of a different size. That is
SPEC §4.4's failure mode arriving by a route §4.4 does not mention, and `PDDocument` and
`PdfReader` report the same page count they always did.

All four attributes are now copied down **before** `/Parent` is rewritten, because what a page
inherits has to be read through the chain the reorder is about to shorten. Our entries are merged
into a *copy* of the resource dictionary, because a source PDF's resource dictionaries are
routinely shared between pages and adding a name to the original would put it on pages that never
asked for it.

---

## 4. Decisions worth recording

**The outbox decision table is the termination argument, and it needed writing down.** A merge
reported through the path a local save takes ends in an unconditional upload, which changes the
remote ETag, which the peer reads as a remote change on its next poll — forever, at the poll
interval, looking exactly like healthy sync. The table (SPEC §7.6) is three rows: nothing novel
either side → advance the ETag, no upload; only the remote was novel → fast-forward, **no
upload**, because re-uploading a note that merely absorbed the remote's work only tells the peer
to pull again; local was novel → advance the ETag *first*, then one UPLOAD carrying
`If-Match: <the ETag just downloaded>`. Both devices upload once after a true conflict, the
loser 412s, the 412 reconciles that one note, and that pass finds nothing novel locally and
stops. Bounded at two round trips, with a test of its own.

**A 412 parks the upload for a day, and only a completed merge may un-park it.** Coalescing
deliberately *inherits* `next_attempt_at` — resetting it per save is a hot retry loop against
the user's own server — so without an explicit un-park, the `noteChanged` that follows a merge
inherits the day-long park and the union never uploads. A backoff is about the transport; a park
is about a conflict; only the merge knows the conflict is over. `attempts` carries over rather
than clearing, because that history is still true and the pass has just proved the server is
reachable.

**A downloaded note is bound at the version it was downloaded at, and owes the server nothing.**
This is a separate method from the "Save to Nextcloud…" bind, not a flag on it — see §5.1 for
what happened when it was not.

**A pulled file is never auto-recovered.** Recovery is REPLACE-not-union and deliberately stamps
everything past every clock it has seen (SPEC §5.6 point 5), so a peer's lossy recovery folded
into this device's note would win every LWW register against full-fidelity local data, and would
look like a successful sync while doing it. `Recovered` on a pulled file is layer 3, always.

**A conflicted copy is the remote bytes, verbatim.** Nothing is re-written and no PDF is
generated: CLAUDE.md rule 1 taken literally, no new PDF code, and sound only because M5's spike
proved a published PDF round-trips a Nextcloud PUT byte-identically. The local note keeps its
`DocumentId`, journal, undo stack and binding — nothing local is superseded — and the copy gets a
**new `DocumentId` and new `ObjectId`s** for three independent reasons (SPEC §7.7).

**Downloads land in `download/<id>.pdf`, never over `notes/<id>/document.pdf`.** Three failures
collapse into that rule and the first is the corruption class this project exists to prevent:
`writeBytesAtomically` derives its temp filename from the destination, so two writers aimed at
one path share one temp file and then atomically rename a **torn PDF** into place — and the
reconciler downloading while the materialize worker republishes is exactly that. The other two
are a `bundleSha256` that would describe the wrong bundle, and a later publish silently rebasing
on a peer's original bytes (SPEC §5.1).

**The reconciler performs no remote writes at all.** CLAUDE.md rule 4 has no pull-side
exemption. Not even a conflicted copy is PUT from there: the copy is created as a local note and
*bound*, and the outbox uploads it like anything else, with the `If-None-Match: *` that stops it
landing on top of the file it exists to preserve.

**An unusable account gets zero requests, not one polite probe per pass.** At the sixty-second
poll floor, one request per halted account per pass is a brute-force pattern against the user's
own server, on the credential the server has already refused. The account-level warning comes
from stored state, not from asking. `capabilities.bruteforce.delay` — parsed since M5 and
unused — now *widens* the poll interval, because a non-zero value means this client is already
in the penalty box.

**A pruned subtree is not a mass deletion.** "Known `oc:id` absent remotely" is only decidable
for a directory that was actually *walked*, so the deletion branch intersects unstamped bindings
with the walked set and additionally requires `everUploaded`. And a directory's ETag is cached
only when everything under it finished, because the row means *"fully reconciled at this
version"* — writing one over an unmerged download prunes that subtree on every later pass until
something else in it happens to change, which is a note that silently stops syncing while the
poll reports success.

**A weak root ETag never short-circuits the poll.** A false *"unchanged"* means the pull never
happens and the note simply stops syncing; a false *"changed"* costs one request. Take the
expensive direction.

**"Needs attention" is a table, not a state on the note.** `syncState` is one value and
`lastError` is one string, and a note can need attention for two reasons at once. The row id is
derived from the *problem* rather than minted fresh, so a poll that rediscovers the same conflict
every sixty seconds updates one row instead of adding 1,440 entries a day. Dismissing sets
`acknowledgedAt`; nothing is deleted (rule 1). Nine kinds, and the mapping from outcome to row is
a pure exhaustive `when`, so a new outcome cannot be added without deciding whether it is worth
telling anyone about — most are not, because SPEC §7.7 layer 1 is explicit that two people
writing in different corners of a page are told nothing.

**One `NoteSession` per note, enforced outside the session.** Two sessions over one note each
hold their own pending ops and folded pages, so whichever materializes second writes a bundle
missing the other's work — silently, with a valid PDF and the right page count. The session is
single-threaded by contract and has no lock; the exclusion is a per-note `Mutex` plus a registry
of live editors in `:app`, and when an editor holds the note the merge is handed to *it*. The
mutex map never evicts: a map that removed an unlocked entry would have to check-then-act, and
losing that race hands two callers two different mutexes for one note, which is the absence of
the exclusion arrived at down a path that looks like tidying up.

**The foreground poll is a coroutine, not a worker.** WorkManager's periodic floor is fifteen
minutes and a shorter period is silently clamped, so the background job cannot be what makes a
peer's edit appear while somebody is looking at the library. The ticker does not talk to
Nextcloud — it enqueues the same worker, so the request is made under `NetworkType.CONNECTED`
and survives the screen going away mid-pass. That is also why there is no *"am I online"*
boolean anywhere: an offline device enqueues work that does not run, which is what keeps
airplane-mode editing free of requests.

**`notify_push` is deliberately not implemented.** Not bundled with Nextcloud, not commonly
available (a separate app, a Rust daemon, Redis and a reverse-proxy route), and best-effort by
its own README. Polling is the mechanism; push would only ever be an optimisation on top of it.

---

## 5. Defects found in M6's own code

Three the merge matrix found while it was being written, and six an adversarial review found
afterwards (§5.5). All are fixed except the two recorded as out of scope in §5.4.

### 5.1 An adopted note 412'd against itself, permanently

`Reconciler.adopt` bound a downloaded note with `SyncEngine.bind` — the "Save to Nextcloud…"
path, which always enqueues MKCOL then UPLOAD. That binding has no ETag and no `oc:id`, so
`everUploaded` is false, so the drainer's very next PUT sent `If-None-Match: *` **at the file the
note was created from**: a permanent 412, the row parked for a day, the note flagged
`CONFLICTED`. A note that had just arrived byte-identical to the server's copy read as a
conflict — and because a park is inherited by coalescing, the next real edit to it never uploaded
either.

`bindPulled` is the pull-side sibling: same binding row, filled in with the version actually
downloaded, and **no outbox row at all**.

### 5.2 A parked 412 swallowed the merge that resolved it

See §4. The park is now lifted by `remoteAdvanced`.

### 5.3 `enqueueReconcile(noteId)` had no caller

`WorkManagerScheduler.enqueueReconcile(noteId)` and `ReconcileWorker.nameFor` both existed and
nothing invoked them, so SPEC §7.6's 412 → reconcile hop was dead code. `DrainReport` now carries
`conflicted: Set<DocumentId>` — **412s only**, and deliberately *not* part of `shouldRunAgain`,
because a 412 is not something a retry of the same PUT can fix and folding it in would put the
conflict on WorkManager's backoff ladder — and `OutboxDrainWorker` asks for a pass per note.

Without it a note still converges, on the next periodic pass; "eventually" was fifteen minutes
with the note reading `CONFLICTED` and a day-long park underneath it.

### 5.4 Two more, recorded and not fixed

Both are outside M6's scope and are written into the test comments where they were found:

- `performDelete` removes the binding, so `noteRestored` is a **no-op** once a DELETE has
  drained. A restore after that point has to re-bind.
- `noteRestored` clears the binding identity unconditionally, so trash → restore → trash-again
  enqueues no DELETE and orphans the remote file, which the next pass adopts back as a new note.

Row 21 is written on two notes so it does not assert around either.

### 5.5 Six the review found afterwards — one permanent loss, two mass trashings, three about rule 5

Every one of them was behind a comment asserting the opposite, which is the pattern worth noting:
the *rules* were written down correctly and the code checked a weaker version of them.

**The trash was where a note went to be destroyed (CLAUDE.md rule 1).** A remote delete that landed
while the note was open trashed `notes/<id>/` under a live `NoteSession`. The session is not told, so
its next two-second autosave appended to `journal.ops` at the old path — and every `FileSystem` write
creates the parent directory first, so the directory came back holding a journal and **no bundle**.
`NoteTrash.restore` refused outright while `notes/<id>/` existed, so Restore silently did nothing,
every time, forever; the note could not be opened either; and `purgeExpired` deleted the only copy of
every page at day thirty. Two halves to the fix. `RemoteMergeGate.trashRemotelyDeleted` now returns a
`Boolean` and `NoteSessionHost` answers **false while an editor holds the note** — the binding stays
unstamped, so the pass after the user closes it reaches the same conclusion and acts on it. And
`NoteTrash.restore` now tells a *stub* from a live note by whether `note.inkstone` is there, moves the
stub into the trash under `<millis>-<id>.orphaned` (rule 1: the ink drawn after the delete is not ours
to throw away, and that name is invisible to a restore because `entries()` reads the id after the
millis) and proceeds.

**Two mass-trash paths, both from one guard being weaker than its own KDoc.** `isAbsentRemotely`
asked whether the file's directory was in `visited.walked`, and `walked` means only *"a listing came
back"*. A directory whose file loop the pass **limit** cut short was in it — so on any library bigger
than `DEFAULT_LIMIT = 25`, which is what `ReconcileWorker` always uses, every bound note past the cut
was trashed, unbound, stripped of its queued upload and given a "Deleted on the server" row while its
file sat untouched on the server. And `list` mapped a **404 to an empty `Listing.Ok`**, which put a
missing directory in `walked` too: renaming a bound folder in the Nextcloud web UI (SPEC §7.4 rule 2 —
a move, not a delete) did the same to everything under it. The guard is now *walked **and** settled*,
`Visited.unfinished` being the record of "and we did not finish", and a 404 is its own `Listing.Absent`
— neither a walk nor a failure, because *nothing was learned*.

**A 401 ladder, which rule 5 forbids outright.** SPEC §7.2's confirmation call has three answers and
only *"the credential is dead"* halts the account. The other two — the probe said the credential is
alive, the probe could not be reached — left `haltedAccounts` empty with the 401 counted in `failed`,
and `failed > 0` is `shouldRunAgain`, which `ReconcileWorker` turns into `Result.retry()`: WorkManager's
EXPONENTIAL/30 s ladder, no attempt cap, five hours of re-sending a refused credential. A committed
test asserted `report.failed > 0` and was green. A 401 now has its own `refused` counter, excluded from
`shouldRunAgain`, and an unread subtree is `unfinished` (never cached) without being *owed* (nothing
asks WorkManager to come back for it).

**A 401 on a download did not abandon the account's tree.** The listing branch drained the queue; the
file branch did not, so a 401 on a GET flagged the account `NEEDS_REAUTH` and then sent one more
PROPFIND to it — and with the probe answering `true` or unreachable the walk simply continued and
called `halting` again for every later directory holding a refused file, N confirmation calls against
§7.2's "confirm once". One flag on the pass (`Visited.refused`) ends the walk at the first 401,
whatever the probe goes on to say.

**And the poll itself was the ladder's slower half**, because an unconfirmed 401 leaves the account
ACTIVE and `PollTicker` walks an ACTIVE account every sixty seconds. `syncCursor.refusedUntil` (5.sqm)
leaves a refused account alone for an hour — persisted, because WorkManager restarts the process for a
living and an in-memory cooldown would be cleared by every wake-up. The cost is that a user who has
just fixed a folder permission waits up to an hour; the alternative is 1,440 refused logins a day
against an instance whose brute-force protection is on by default and counted per IP.

**Nothing remembered "this remote file is not ours" (SPEC §5.6).** Identity lives in the XMP marker in
the PDF catalog, so answering it costs the whole file — and a directory's ETag moves every time any
note in it uploads, so a 40 MB handout beside the notes was re-downloaded on every note save. The loud
half is a livelock: each unknown file spends one unit of the pass budget, so `limit` strangers ahead of
the notes in listing order consumed every pass, the directory stayed unfinished so the root ETag was
never cached, and the next pass re-downloaded the same files in the same order and never reached file
26. The note behind them never synced and every pass reported success. `foreignRemote` (5.sqm) keys the
answer on the same stable identity the staged download is named with — `oc:id`, or the flattened path
behind a proxy that strips `OC-FileId` — beside the ETag and MD5 it answers for, so changed bytes are
looked at again and unchanged ones cost nothing.

**Schema.** Both new rows are in `5.sqm` (version 6): `CREATE TABLE foreignRemote` and
`ALTER TABLE syncCursor ADD COLUMN refusedUntil`, purely additive, both defaults meaning what the code
did before them, and the new column last in its `CREATE TABLE` so `verifyMigrations` agrees.

### 5.6 Three more from the same review: nobody was serializing the writers

M6 added a second writer at each of the two levels that have one, and neither level noticed.

**A merge into an open note ran as a second coroutine over one `NoteSession`.** `NoteSessionHost`'s
gate is the guarantee that there is exactly one session per note, and it delivers on it — but
delivering the merge into the editor's `work` scope is not the same as serializing it against what
that scope is already doing. `work` is `Dispatchers.Main.immediate`: no memory races, and free
interleaving at every suspension point. The autosave loop is one long-lived coroutine there and the
merge is another, and `NoteSession.materialize` snapshots every page and *then* resets the journal:

```
merge:     materialize() — snapshot the pages, write the bundle      ← suspends
autosave:  save() → apply(gesture) → flush() → a journal record on disk
merge:     ... reset the journal to a bare header
```

The stroke drawn during the merge ends up in no journal, no bundle, no `pending` list and no page
cache. `localRev` regresses over the flush's, so `MergeCommit` reports the older revision,
`library.onPulled` writes it back over the newer one, and the binding is marked `SYNCED` — every
layer downstream agreeing that nothing is owed. The output is a valid PDF with the right page count.
A merge of a real notebook holds that scope for hundreds of milliseconds, so the two-second autosave
tick landing inside it is the ordinary case rather than the unlucky one.

`NoteEditor` now holds a `Mutex` and every operation takes it — the autosave, the merge, undo, the
page operations, `materializeNow`, even `foldedPage`, because folding re-reads the journal and fills a
cache that `materialize` clears. `NoteSession` is single-threaded *by contract* and deliberately has
no lock (CLAUDE.md rule 7), so this belongs to whoever owns the session. Two consequences worth
naming: the shared helpers are spelled `saveLocked`/`materializeLocked` because a `Mutex` is not
re-entrant and a locked function calling a locked function deadlocks the editor for good; and the
three page operations that used to finish by calling `showPage` now call `display` directly, so that
"add a page and show it" is one serialized operation rather than two with the note visible between
them.

**The mutex is created *locked*, and that is what closes the note-open window.** `NoteEditorViewModel`
registers the editor's handle in its `init` — before the open has read a byte, deliberately, because
that is exactly when a pull is dangerous. `mergeRemoteBundle` read `session` first and answered null
while it was still null, and null means *"no session here, do it yourself"*: the host opened a second
session and rewrote `note.inkstone` under the byte offsets the editor's `BundleReader` had already
recorded. The review filed this as a suspicion with a benign-sounding KDoc — *"nothing is destroyed …
its next materialize re-reads the journal and folds both"* — and that KDoc was wrong. A `Zip.Directory`
of stale offsets fails its local-header or CRC check and `error()`s, so the editor's next materialize
**throws**, out of `viewModelScope.launch`, with nothing written. No bytes lost, but the note does not
materialize and the exception is uncaught. Because the lock is held from construction until the open
finishes, the merge now waits for the session it should be running against, and null is left meaning
only *"this editor is cancelled or its open failed"* — the two cases where the host doing it itself is
correct. The residual is one window narrower than it was and is written down where it lives: an editor
that registers *after* the host has read the registry still gets a stale reader, and closing that
needs the editor to hold the host's per-note lock across its own open — which it cannot, because with
no `documentId` (M2's fallback) it does not know which note it is opening until it has read the
directory.

**And nothing serialized reconcile passes.** Three unique WorkManager names run the same whole-account
pass — the foreground ticker's, the fifteen-minute periodic one, and one per note a 412 refused — and
`ExistingWorkPolicy` deduplicates only *within* a name, so `K` notes 412ing in one drain enqueue `K`
jobs that WorkManager runs four at a time. Two passes over one account share the staging directory, and
every way that goes wrong surfaces as a **conflict** rather than as an error: `download/<id>.pdf` is
one fixed path whose temp file is named after its destination, so two GETs interleave into one temp
file and one of them renames a torn PDF into place (rule 10) — and a torn PDF routes `changed` into
layer 3; `discard` deletes the staged bundle and the resume row, which while the other pass sits
between `extract` and `gate.merge` makes its bundle unreadable — `DifferentDocument`, layer 3 again;
and a directory cache row written over the other pass's unmerged download prunes that subtree on every
later pass. A spurious conflicted copy, from a race, with both copies identical.

The guard is a **single-flight in `Reconciler`, not a work-name policy**, for three reasons. The class
is a `@Singleton` that `PollTicker`, both workers and the merge-matrix harness all reach directly, so
the guarantee has to live where the state is. One unique name would have to choose between `KEEP` —
which drops the pass a 412 asked for, the one case where a *fresh* pass is the point — and
`APPEND_OR_REPLACE`, which serialises by building a chain as long as the number of conflicts. And a
`tryLock` that reports `ReconcileReport.busy` rather than a `withLock` that waits means the loser
holds no WorkManager thread: `busy` is part of `shouldRunAgain`, so the worker returns `Result.retry()`
and WorkManager brings it back — the same "come back later" the outbox rows' `next_attempt_at` gives
the drainer. A turned-away pass makes **zero requests**, so the three names racing costs a wake-up and
not a walk, and it cannot become rule 5's ladder either, because the pass that retry eventually runs
re-reads the account state first: a halted account is still zero requests and `refusedUntil` is still
honoured.

### 5.7 The LWW title: correct, tested, and connected to nothing — the fourth time

§3.1 above describes the mechanism, and every word of it was true and inert. The review found layer 2
broken at **three** independent points, each one on its own enough to keep two devices on two titles
forever, and the unit tests for `resolveTitle` and `mergeDocument` passed throughout.

**No writer.** The only caller of `NoteSession.rename` was `NoteEditor.rename`, whose only caller was
`NoteEditorViewModel.rename`, which **nothing called** — there is no rename control in the editor. The
production rename was `LibraryRepository.renameNote`: an index row and a remote MOVE. `Document.title`
and `Document.titleClock` were never touched by anything a user could do. Two devices renaming in the
library therefore kept two index titles indefinitely, and each device's next `notePathChanged` moved
the server file back to its own name.

A rename is now `NoteSessionHost.renameNote` — the same class the reconciler goes through, because a
rename writes the note's own document and there must be exactly one `NoteSession` per note. It hands
off to a live editor when one holds the note (`NoteEditorHandle.rename`) and otherwise calls
`NoteStore.renameNote` under that note's mutex; either side then reports to
`LibraryRepository.noteRenamed`, which does the index row, the remote MOVE and — new — the UPLOAD,
because the bundle genuinely changed. And the rename **materializes**: a published PDF embeds
`note.inkstone` *as it is on disk*, so a title that reached only the journal is one the peer does not
receive until the note happens to close.

**No reader.** `LibraryRepository.notePulled` called `index.recordSave`, which has no title column. So
a resolved title lived in `note.inkstone` and the library tile — the only place a user ever sees a
title — kept the old string permanently. `notePulled` now takes the **resolved** title (the local one
when the local one won, so it can be written back without asking who won) and leaves an empty one
alone. It deliberately does **not** call `notePathChanged`: the file is already at the peer's name,
which is how the rename arrived, and asking the server to repeat a move it has already made is a pull
turning into a push.

**The counter was issued twice.** `rename` takes its clock from the note's Lamport generator, and
`NoteStore.materialize` computed the bundle's `highestClockCounter` over **page ops only**. So a
rename's counter was never recorded and the session after a close re-issued it. A peer that merged the
first rename then holds the *identical* clock, `Register` needs strictly later, and each device refuses
the other's title from then on — `titleOverridden` false, no attention row, every op present. Reached
with no concurrency at all: a rename, a close, a rename. The invariant now lives on `BundleContents`,
which all four bundle writers go through, and `NoteStore.open` folds in a rename that has only been
flushed (which lives in the journal's document record and in no op). `mergeDocument` also *observes* a
peer's clock whether or not it wins — without that, the next rename here is issued behind the peer's,
the counters come out equal, and which title survives is a lexicographic comparison of two UUIDv7
device ids.

Second-order: `Register.write`'s KDoc rested its correctness on *"impossible on one device (the counter
only increases)"*. M6 fixed the cross-device half of that sentence with `DeviceIdentity` (§1.1) and
introduced a violation of the same-device half in the same milestone. The KDoc now names the condition
it depends on and where the invariant is kept.

**And one more, found by the test rather than by the review.** A rename changes the filename outside the
bundle as well as the title inside it, so the peer's pull sees a new ETag **at a new href** — the
ordinary case for a rename. `RemoteVersion` carries no path, so the pull advanced the ETag and left the
binding aimed at the old one; that device's next MOVE then ran from a file that no longer existed, 404'd,
parked for a day, and took every later UPLOAD of the note with it, because MOVE drains before UPLOAD.
The reverse rename never left the device. `Reconciler` now follows the href on the changed branch as
well as the moved one, through one rule: a binding with a MOVE pending keeps its **destination** and has
its **source** corrected (overwriting the destination would cancel a rename the user has already been
shown), and a binding with nothing pending simply follows the file.

Also recorded, not fixed: **carried bundle entries are unioned local-wins on both sides**, which is
symmetric and therefore does not converge for a name two devices both produce. Nothing produces one
today, which is the only reason it is a note rather than a defect; SPEC §5.5's `text/<pageId>.json` is
named per page, so the day M7 writes recognition every device produces the same name and a clash is the
normal case. Ordering carried entries is M7's prerequisite, not its cleanup — noted in SPEC §5.5, at
`NoteStore.materialize` and at `NoteMerge`'s `carried`.

### 5.8 Layer 3 was unbounded in both of the ways it can be

Two defects with one premise, and the premise is deliberate. Layer 3 does not advance the file's ETag —
that is what makes the parked upload park rather than overwrite a file this build cannot read, and what
lets a later pass find out whether the condition cleared. So an unresolvable file is met **again on
every poll**, for as long as it lasts, and a directory's ETag moves whenever any note in it uploads, so
"every poll" is the ordinary case. Everything below follows from *recurring forever* not being the same
thing as *costing nothing extra forever*.

**A dismissed row un-dismissed itself.** The dedup guard was `attention.open().none { it.id == entry.id }`
and the comment above it said the rule correctly — *"must neither grow the list nor clear a dismissal"* —
while `open()` is `WHERE acknowledgedAt = 0`. An acknowledged row is by definition not in that set, so
the guard passed and `INSERT OR REPLACE` wrote `acknowledgedAt = 0` back over it. At SPEC §7.6's
sixty-second floor the user's dismissal lasted one poll, forever, for conditions like *"the peer's file
lost its bundle"* that may never clear on their own. The read is now `find(id)`, which sees through the
tombstone, and the write happens only when the row would **say something different**
(`AttentionEntry.saysTheSameAs`, everything except the two timestamps). So a repeat says nothing, and
something genuinely new still arrives by whichever route applies: a different problem is a different id
and its own row, and the same problem with a new payload — the peer's schema moved from 3 to 4 — is
rewritten *and* re-opened. `AttentionLog.open`'s KDoc now says what it is not for.

**And every re-encounter minted another conflicted copy.** `conflictedCopyOf` mints a fresh
`DocumentId` every time it is asked, and it must (SPEC §5.6 inverted: a copy keeping the id would be
merged back into the note it exists because it could not merge with). The call was unconditional, so one
unresolved condition produced one new local note **and one new bound remote file** per poll —
`Lecture (conflicted copy … 120000).pdf`, `… 120500).pdf`, … each one uploaded to the user's server. The
key is the *conflict*: the pair (local note, remote file by `oc:id`), which is `Attention.conflictId`
and is now the attention row's discriminator; keying it on the **copy**, which is what it did, made the
id move every time a copy was made — the exact opposite of a dedup handle. `Reconciler.existingCopy`
reads that row before minting, and the copy is bound only when this pass actually made it, because
`SyncEngine.bind` claims a fresh unique path and would otherwise ask for a second file under a second
name.

The two fixes hold each other up, and that is worth knowing before someone tidies either: **the record
of "this conflict already has a copy" is the attention row**, so acknowledging has to stay a tombstone
for a second and quite separate reason. Delete an acknowledged row and layer 3 starts minting again on
the next poll. What remains is a pass killed between the mint and the row write, which can still make a
second copy — bounded by process deaths rather than by polls, and neither copy destroys anything, which
is why the alternative (writing the row from inside `layerThree`) is not worth giving the attention
policy a second place to live.

**A third thing, found by the tests rather than by the review.** Three of the assertions in this area —
including the committed *"the same problem rediscovered on the next pass does not grow the attention
list"* — polled by calling `reconcile()` in a loop, and a pass that leaves nothing unfinished **caches
the directory's ETag**. The second and third polls pruned the subtree and never reached the file, so the
test proved SPEC §7.4 rule 1 and nothing else; it would have passed with the dedup deleted outright.
`ReconcilerFileTest.pollAgain` moves the folder's ETag between passes, which is what a neighbouring
note's upload does in practice, and every one of these tests now asserts the GET count as well so that
"met four times" is a claim and not an assumption.

### 5.9 Close-out: two doors that should have been one, and a branch that could not be reached

Five things, and four of them are the same shape — a decision expressed in two places, where only one
of them ran.

**`NoteEditor.applyRemoteOps` was a second door into the merge, and it never opened.** §1 of the
design brief described the live-re-render path as *the gate emits, the editor observes* — a
`RemoteOpBus` carrying `(noteId, pagesTouched)` to a collector in `NoteEditorViewModel`, which would
call `applyRemoteOps(pageId, ops)`. What shipped is better and different: `NoteSessionHost` hands the
whole merge to the editor's own `mergeRemoteBundle`, so the peer's ops are folded and the screen
reconciled inside one critical section over the one session. The bus was never written, and
`applyRemoteOps` sat with six green tests and no production caller for the entire milestone — which
is §6's lesson wearing the opposite face: not *a feature that is inert*, but *a test suite that looks
like coverage of a row and is coverage of a seam*.

It could not be given a correct caller either. After `mergeRemoteBundle` has run, the ops are already
in the log and a second delivery subtracts to nothing; before it has, ops arriving on their own would
enter through `NoteSession.merge` with **no `NoteMerge.mergeDocument` in front of them** — SPEC §4.3's
trap, where a remote page's ops are journaled without a `PageRef`, skipped by `BundleWriter`, and then
destroyed by the journal reset inside `materialize`, leaving a valid PDF with the right page count and
a page missing. So the door is gone and row 10's JVM half now drives `mergeRemoteBundle` over a real
`BundleWriter`-authored peer bundle: every assertion the old tests made, made against the path the
host actually calls. "Forgot `mergeDocument`" is unrepresentable rather than guarded, which is the
move the `undoable` boolean got in §1.2.

One guard moved rather than disappeared. `applyRemoteOps` began `if (ops.isEmpty()) return`, and an
empty result is the *common* case on the pull side — the remote ETag moves every time this device
uploads, so most merges fold a log this device already has and the two folds come out identical. That
now lives in `NoteEditor.reconcile`, where the calls actually arrive.

**`MergeReport.TooNew` was unreachable.** `NoteMerge.merge` takes an already-open `BundleReader` and
checked `remote.document.schemaVersion > SCHEMA_VERSION` — but `BundleReader.open` refuses a schema
from the future by *throwing* (SPEC §5.8's `require`) and `BundleReader`'s constructor is private, so
no reader the check could fire on has ever existed. Both gate implementations wrapped the open in
`runCatching { … }.getOrNull()`, so every refusal arrived as `DifferentDocument`. Layer 3 was correct
throughout — both copies kept, the ETag not advanced — and told the user *"this file cannot be
merged"* where the truth was *"update Inkstone and it opens"*.

Made reachable rather than deleted, because the file it describes is real: the schema number is
written in **two** places, the XMP marker and the bundle's manifest, and `PulledPdfExtractor`'s
`TOO_NEW` verdict comes off the marker and never stages a bundle at all. What reaches the gate is a
file where those disagree. `NoteMerge.mergeBundle(session, ByteSource)` is now the bytes-in door: it
tries the open, and on refusal asks `BundleReader.schemaVersionOf` — a non-throwing probe — which
turn of events distinguishes the two. Both `NoteSessionHost` and `NoteEditor` lost their
`runCatching`; `MatrixGate` follows them so the matrix keeps mirroring production.

**`MaterializeWorker` was the other half of §5.6's overlap finding.** It republished `document.pdf` by
calling `DocumentMaterializer` straight out of the job, which for an open note is a second writer
against the editor's own `materialize`. The damage is not a torn file — both writes are atomic and,
since M6, uniquely named — it is a **stale marker**. `InkstonePdfMaterializer` reads `note.inkstone`,
embeds it and records its SHA-256 (SPEC §5.6); a bundle rewritten by the editor between that read and
the PDF write leaves `document.pdf` describing a bundle that no longer exists, which the next library
rebuild reads as `Recovered(BUNDLE_HASH_MISMATCH)`. Recovery is REPLACE-not-union stamped at
`highestKnownCounter + 1_000_000`, so it then wins every register on the next sync: a healthy note
rewritten lossily *because it was published twice*. The worker now goes through
`NoteSessionHost.materialize`, which holds the note's mutex and hands the job to the live editor
(`NoteEditor.materializeInSession`, which also flushes the journal first). The exception is
deliberately not caught in the host — the worker's `catch` is the retry policy and swallowing it would
turn a full disk into a silent success.

**The conflicted-copy name came from two clock readings.** `Reconciler.layerThree` stamped one with
`RemoteName.conflictedCopy(title, pass.passStartedAt)` for the remote path, and
`NoteStore.conflictedCopyOf` stamped its own for the local note's title — separated by the download,
so one conflict wore two names. The gate could not simply pass its string through, because
`conflictedCopyOf` would have stamped it again into
`Lecture (conflicted copy …) (conflicted copy …)`; it passed the *base* title instead, which is what
produced the divergence. `conflictedCopyOf` now takes the finished name and stamps nothing, so
`RemoteName.conflictedCopy` has exactly one call site and the two names are equal by construction.

**Two smaller ones.** `Reconciler.fetch` set `PENDING_DOWNLOAD` *before* the GET, i.e. as a claim about
the future, and nothing on the failure path took it back — a dropped connection left the note stuck in
it until some later pass succeeded, which also blocks SPEC §4.7's compaction gate for as long as it
lasts. The state and the `pendingDownload` row now say the same thing at the same moment: bytes are on
disk and a merge is owed. And `PollTicker` honoured its interval only *within* one run of its loop,
which does not survive the screen count reaching zero: a rotation is `onStop` then `onStart` with
nothing in front, so it cancelled the job and the new one polled immediately. Ten rotations were ten
passes inside a minute, out of the class whose subject is not asking the server too often. The pacing
is now a remembered last-poll instant and a cooldown at the head of the loop — `null` for "never", not
zero, because zero only polls immediately by accident of a real epoch being eleven orders of magnitude
larger than the interval.

**One thing declined.** `NoteSession.restorePage` clears `deleted` in place, and `PageOrder`'s KDoc
claimed a restore propagates *"with a fresh entry"*. The code was not the problem: `reconcile` ORs the
two `deleted` flags unconditionally, so once a peer holds the tombstone no shape of `PageRef` wins
against it — and the *next* merge in the other direction re-deletes the page here. Ordering a delete
against a restore needs `deleted` to be a Lamport register per page (CLAUDE.md rule 8), which is a new
member inside the manifest's `pages[]` array, where SPEC §5.8's carry-unknown-fields rule does not
reach — so an older build would drop it on the next rewrite. That is a schema decision, not a patch.
The KDocs now say what happens instead of what was intended, `PageOrderTest` pins it in both the
in-place and the fresh-entry form, and SPEC §4.3 records it. No bytes are at risk: the page's whole op
log survives under the tombstone and restoring again works — until the next sync.

### 5.10 The device pass: what running it actually found

An emulator (`Test_Tablet(AVD) - 15`, Pixel Tablet, API 35, x86_64) was attached the whole time, and
until this pass nothing in M6 had run on it but `:core:database`'s tests in isolation. Running the rest
cost about an hour and produced four findings, three of which no JVM test could have reached.

**`:app` had no `androidTest` source set and did not declare `androidx.test:runner`.** The runner is
not transitive from `androidx.test.ext:junit`, and `testInstrumentationRunner` names a class from it —
so the *first* instrumented test written in that module would have died with
`ClassNotFoundException` before running, which is exactly how `:core:database` and `:android:ink` each
sat with zero working instrumented tests for two milestones. Declared from the catalogue with the
reason written beside it.

**The app could not start in a secondary process.** SPEC §13.3 needs a process the test is not running
in — an instrumented test lives inside `dev.inkstone`, so killing that process takes the assertions
with it, and no external `am force-stop` can be aimed at the instant *between* two writes. The victim
is therefore a debug-only `Service` in `dev.inkstone:crashpoint`; it died on startup with
*"WorkManager is not initialized properly"*. `Application.onCreate` runs in every process, WorkManager's
initializer is a `ContentProvider` and Android creates those only in the default process, and
field-injecting `WorkManagerScheduler` is eager by definition — so the graph was being partly built,
in every process, before the app ran a line of its own code. Now a `Provider` behind a default-process
guard, which is also a small cold-start win. Nothing in production has a second process, so nothing in
production would ever have found this.

**A synthetic stylus stroke silently never finishes if its `MotionEvent` clock is wrong.** Row 10's wet
half draws real ink, and the first version drew nothing: `MotionEvent` times are
`SystemClock.uptimeMillis()`, not wall clock, and every event of one gesture must carry the same
`downTime` — ink measures a stroke's elapsed time from it, and a mismatched one yields elapsed times
`StrokeInput` rejects. No exception, no log, `strokeCount` simply stays at zero. The test now asserts
that the surface consumed the ACTION_DOWN, which separates *"the event never arrived"* from *"the
stroke never landed"*; they had the same symptom and different causes.

**`connectedAndroidTest` uninstalls the app afterwards.** The `adb` walk through the UI had to be
redone from scratch because a suite ran between setting it up and reading the result.

What the pass proves, beyond the tests themselves:

- **The four §13.3 kills each assert a bound.** The most interesting is materialization: `NoteStore`
  writes the new bundle and *then* truncates the journal, so a kill in between leaves every op in
  both places and the journal replays on top of the bundle at the next open. Nothing is lost **and
  nothing is doubled** — which is only true because the fold is keyed on `opId` rather than on
  arrival. Driven by hand as well: `am force-stop` immediately after a stroke reopened the note at
  the last autosaved revision, so the loss is bounded by the autosave interval and not by anything
  larger.
- **The first on-device schema migration this project has run.** Both existing instrumented suites
  open in-memory databases with `name = null`, which calls `Schema.create` and never
  `Schema.migrate`. `MigrationOnDeviceTest` builds a real version-4 database with real rows —
  including a binding carrying a *weak* ETag and a queued UPLOAD with attempts already spent — and
  opens it through the production driver. Two traps worth keeping: `AndroidSqliteDriver` is **lazy**,
  so a version check straight after the constructor reads the old number and asserts nothing (one
  statement is what forces the upgrade, which is also true of the app itself); and the version-4 and
  version-5 fixtures were *generated* from the committed `1.db` plus `1.sqm`–`4.sqm` rather than
  transcribed from the current `.sq`, so they cannot drift into describing today's schema.
- **The M6 features are reachable in the app.** Folder created through the UI; *"Always sync to…"* in
  its overflow menu; the folder's cloud badge with *"Notes in this folder sync to Nextcloud"*; a note
  created inside the pinned folder producing a `remoteBinding` at `Inkstone/Lectures/Untitled.pdf`
  and **MKCOL at `local_rev` 1 then UPLOAD at 2** in the outbox; two strokes drawn with
  `input stylus swipe`; and the *"Needs attention"* screen rendering a `REMOTE_TOO_NEW` row in full
  and an unknown kind as a generic item rather than dropping it. Both workers returned
  `SUCCESS`, never `retry`, and the queued rows kept `attempts = 0` across several drains — a missing
  credential does not spend an attempt.

Where the walk stopped, and why: **the app cannot talk to the test Nextcloud.** It is plain HTTP,
`network_security_config.xml` trusts `system` anchors only and permits no cleartext, and there is no
way to add a system CA to a Play-Store emulator image. Signing in is therefore impossible here, so the
account row and the folder-pin row were written straight into the device's database and everything
downstream of them was driven through the UI. The folder browser itself needs a PROPFIND to complete,
so *"Always sync here"* was not tapped; what was verified is that the menu item reaches the account
screen and that a pin, once present, is consulted by the real `LibraryRepository.autoBindIfPinned`.
One consequence of the seeding is worth naming so it is not mistaken for a bug: an ACTIVE account with
no credential is unreachable in production (sign-in writes both, sign-out clears both), and it is what
makes the note read `NEEDS_REAUTH` while the account screen still calls the account active.

⚠️ **The walk's evidence no longer exists, and that is a property of the method rather than an
oversight.** `connectedAndroidTest` uninstalls the app when it finishes, which takes `databases/` with
it — `run-as dev.inkstone` now answers *"unknown package"*. So the `remoteBinding` row, the
MKCOL-at-rev-1-then-UPLOAD-at-rev-2 pair and the *Needs attention* rendering are the **one class of
M6 claim that cannot be re-checked from a later session**, unlike the unit suite (re-run on demand),
the instrumented suites (result XML committed to `build/outputs/`) and the live matrix (re-run against
the Pi). Anything a future audit needs to confirm from an `adb` walk has to be captured *during* it:
paste the `uiautomator dump` and the `run-as … cat` output into the milestone notes, or accept that
the tick rests on a transcript.

---

## 6. The process lesson: a feature can be correct in every test and inert in the app

This happened **four times** in one milestone, and the shape was identical each time.

- **Folder pinning** recorded the mapping and bound nothing. A user could pin a folder holding a
  note, watch their Nextcloud, and see no folder and no upload. The path resolver was right and
  had unit tests; nothing called it. *"Always sync this folder"* is a statement about the
  **notes**, not about a mapping — so the decision was hoisted out into a pure function over
  plain data (`planFolderSync`), which makes "a note in a pinned folder produces a step"
  something a test can say at all.
- **`enqueueReconcile(noteId)`** existed on both sides of the boundary with nothing in between
  (§5.3).
- **The PDF `/Resources`** were verified by re-parsing the output with the same library that
  wrote it (§3.7).
- **The LWW title** had a mechanism, unit tests, an attention row and a SPEC section, and no
  production writer and no production reader (§5.7). The one thing that could have caught it was a
  test that renamed through the path a *user* takes, and the matrix helper for a rename called
  `session.rename` itself — the fixture being more generous than reality, which is the same shape
  as the other three with the fixture standing in for the missing wiring.

All four were green. The gap each time was the absence of anything that ran the **real path** —
and in three of the four, the reason the real path was unreachable from a test is structural:
`LibraryRepository` cannot be constructed in a JVM test because its `PdfGateway` needs
`androidx.ink`'s native code, and a PDF is only ever checked by the writer's own reader.

The practical response, and the reason the merge matrix is shaped the way it is: build the
harness out of the **production** objects wherever it is possible at all, and when a test can only
reach a seam, say so in the test's own KDoc rather than letting the green tick imply more. M5
learnt the same thing from a different direction — three bugs in twenty minutes on an emulator,
none of which its 286 JVM unit tests had caught. (That number was published as *"1068 JVM tests"*
for two milestones. §7.)

⚠️ **And the fixture half of it happened again in this repair, twice in one area** (§5.8).
`FakeMergeGate` answered `copy-of-<note>` for every conflicted copy it was ever asked for, so two
copies were indistinguishable from one and the assertion that catches layer 3 minting one per poll
could not have been written against it; it now mints a fresh id per call, like the real
`conflictedCopyOf`. And the tests that polled repeatedly did so by calling `reconcile()` in a loop,
which the directory-ETag cache turns into one poll and two prunes. Both are the same failure as the
rename helper's: **the fixture doing something the production code does not**, in the direction that
makes the code look right.

---

## 7. Testing

```
./gradlew build      # BUILD SUCCESSFUL — 539 unit tests per variant, 0 failures, 0 skipped
```

539 per variant (`:core:model` 142, `:core:sync` 174, `:core:storage` 118, `:app` 62,
`:android:pdf` 43), counted as `tests=` summed over `*/build/test-results/testDebugUnitTest/*.xml`.
`build` runs the same suite again as `testReleaseUnitTest`, so it *executes* 1,078 and there are
**539** — 529 before the close-out of §5.9, which added ten. M6 added 253 of them: M5 shipped **286** — counted by `@Test` over the same five unit-test
source sets at `d6993e3`, a method that reproduces today's per-module XML figures exactly, which is
why it can be trusted about a commit whose results are long gone. 27 of the 253 are the matrix
directory — the 21 rows, the 9b/12b/13b variants and the three extra rows the table below names —
and the rest are the pull, the merge and the review repairs, each of the repair's tests checked to
fail against the code as it was except where the note against it says which direction it guards
instead.

⚠️ **This document said "1,068 JVM tests" for M5 in two places, and 465 in this one, and neither
was a test count.** 1,068 is Gradle's `1068 actionable tasks` from the summary line — today's build
prints exactly that number and 539 tests, which is how far apart the two are. 465 was a mid-M6
figure attributed to M5. The count is per variant, of `testDebugUnitTest`, from the XML; say which
task any number belongs to, because that is the part that misled.

⚠️ **And a second way the summary line misleads, which cost this milestone's audit two runs: a
green `./gradlew build` routinely executes zero tests.** Verified 2026-08-10. Three consecutive
`BUILD SUCCESSFUL` lines were produced without a single test method running:

```
BUILD SUCCESSFUL in 20s    1068 actionable tasks: 14 executed, 1 from cache, 1053 up-to-date
```

Every `test*UnitTest`, `detekt`, `spotlessCheck`, `checkCoreIsPlatformFree` and
`verifyCommonMainInkstoneDatabaseMigration` logged `UP-TO-DATE`. Deleting `build/test-results` does
**not** fix it either — the second attempt came back `:app:testDebugUnitTest FROM-CACHE`, restored
from the Gradle build cache without executing anything. The figures in this section come from a run
forced with `--rerun --no-build-cache` on all five unit-test tasks
(`BUILD SUCCESSFUL in 4m 27s`, 539 tests, 0 failures, 0 skipped, and the live half genuinely dialling
`192.168.1.13:8088`). **A build line is evidence about the suite only if the test tasks in it are
neither `UP-TO-DATE` nor `FROM-CACHE`** — and the live matrix, which reaches over the network, is
exactly the thing an up-to-date check will happily skip.

### Automated, and run against a real Nextcloud

**Every one of SPEC §13.2's 21 rows is written and every one runs on every build.** 26 of the 27
matrix test bodies additionally run against a real Nextcloud 32.0.13 — a throwaway container on a
Raspberry Pi, addressed by `nextcloud-test.local.properties` at the repo root (gitignored;
`tools/nextcloud-docker/` has a compose file, an `occ` wrapper and `up.sh` for standing up another).
Those 26 run their body **twice**, unchanged: once in-process, once live. A local-only body and a
live-only body would drift, and the one that drifted would be the one still passing.

⚠️ **Three qualifications, and earlier drafts of this section rounded all three away.**

1. **Row 19 is not one of the 26 and never touches a server.** It is `runTest`, not `matrixRow`:
   a file-backed `JdbcSqliteDriver`, `SqlOutboxStore`, close, reopen. That is the right shape for
   the claim — queued work survives a process boundary — but "every row runs live" was false, by
   one row, and the row it was false about is the one whose subject is *durability*, where a real
   server has nothing to contribute and a real **process kill** has everything. That half is
   `CrashSafetyTest`'s fourth kill (§5.10), which is instrumented and did run.
2. **Rows 15 and 12b return early on the live half**, at a trigger that needs a shell on the
   instance — `occ maintenance:data-fingerprint` and a quota the test can fill, neither reachable
   without an SSH key to the Pi. Their live halves execute the *setup* against the real server and
   then print `LIVE HALF SKIPPED` with what is and is not proven. Everything each row is actually
   about is in-process only.
3. **"Live" means through `CountingProxy` on loopback, not straight at the server.** Every live
   request is re-framed by a hand-rolled HTTP/1.1 relay (`Content-Length` added, hop-by-hop headers
   dropped, one request per connection). That is what makes counting exact, and it is also a layer
   of this project's own code between the app and Nextcloud. Row 11 passing live is the evidence it
   is faithful — a wrong `Destination` rewrite makes Sabre refuse the MOVE — but it is evidence,
   not a guarantee.

### What the rows *do* run against

A real HTTP/1.1 server on loopback, hand-rolled over `ServerSocket`
(`app/src/test/…/sync/matrix/MatrixServer.kt`). `com.sun.net.httpserver` does not exist on the
Android unit-test classpath — `android.jar` plus `java.*`, no `jdk.httpserver` — and
`NextcloudTransport`'s constructor is `internal`, so a Ktor `MockEngine` was not available
either. Which turned out better than the alternative: the production
`NextcloudTransport.create()`, real OkHttp, the real `WebDavClient`, the real PROPFIND body and
the real multistatus parser are all in the loop, and **only the storage behind the socket is
fake**. Directory ETags are derived from the subtree, so SPEC §7.4 rule 1's propagation is
genuinely exercised rather than pruned correctly by accident.

Each device is an independent stack: its own temp-directory `FileSystem`, `NoteStore`, persisted
`DeviceIdentity`, `InkstoneDatabase` on `JdbcSqliteDriver(IN_MEMORY)` with the **real** `Sql*`
stores, `SyncEngine`, `Reconciler`, `OutboxDrainer`, and the real `InkstonePdfMaterializer` and
`PulledPdfExtractor`. Nothing is shared but bytes over HTTP.

Two rows still need something a healthy server will not do on request, and for those the in-process
switch stands in for the trigger: **15** (`occ maintenance:data-fingerprint`) and the 507 half of
**12b** (a full quota). The client-side behaviour is asserted; the server-side cause is not real.
The mid-socket half of **12** — a body that stops arriving — is a third, and wants an nginx
`limit_rate` sidecar.

### The counting reverse proxy, and what it made assertable

`app/src/test/…/sync/matrix/CountingProxy.kt`. Half of SPEC §7's design is about **economy** rather
than behaviour — one PROPFIND for an idle poll, one PUT for five hundred offline saves, one
confirmation call after a 401 and then nothing — and every one of those claims is a request count.
The live half could not make any of them: it printed `(live) request counts not observable` and
proved behaviour without economy, which is the half of the design that protects the user's own
server. Nothing may count from the client side, either: that would mean `:app` holding a Ktor
plugin, and `NextcloudTransport` is opaque up there precisely to prevent it.

So `LiveMatrixServer` now points the account at an in-process pass-through proxy on loopback and
counts what goes past. It is hand-rolled over `ServerSocket` like `MatrixServer.kt`'s in-process
server, rather than `ktor-server-cio`: no new coordinate to keep verified (CLAUDE.md rule 3), and no
Ktor type on `:app`'s test compile classpath. Every method is forwarded (PROPFIND, MKCOL and MOVE
included), every header both ways, both bodies, and the status verbatim; three rewrites are
unavoidable and each is a bug if missed — `Host` (Nextcloud checks `trusted_domains`), `Destination`
(an absolute URL naming the proxy is a cross-server MOVE to Sabre) and the upstream base path.

⚠️ **Every request count in the suite is now checked live** — rows 1 (one GET per direction), 11
(zero GETs for a move), 16, 17 (one PUT for 500 saves), 20, and the convergence row's five idle
polls. Not row 15: its live half returns at the missing `occ` before it reaches its own `GET == 0`.
If the proxy were wrong these would go red, which is the point — the 27 rows were green before it
went in.

Once there is a proxy, two rows stop needing an admin:

- **Row 16** is now a genuine weak validator: the proxy rewrites `ETag` and `OC-ETag` to `W/"…"` in
  front of a real Nextcloud, which is exactly the compressing reverse proxy SPEC §7.3 describes.
  Headers only, deliberately, and **not** `<d:getetag>` inside a multistatus body — that is what a
  real one does, because nginx weakens the validator it emits and does not rewrite XML it forwards.
  The in-process server weakens the body too, so the two halves of the row cover both shapes.
- **Row 20** is now a genuine 401: the proxy replaces `Authorization` with a deliberately wrong app
  password, so the refusal is the real server's, through its real brute-force protection. ⚠️ It
  therefore spends **two** of that instance's brute-force attempts (the default is about ten per
  thirty minutes per IP, and behind a NAT the IP is the whole household). The row is written to spend
  exactly two, which is also what it asserts, so the cost and the claim are the same number and a
  re-run cannot lock the account out.

The proxy also made two claims assertable that were previously one layer too early. `If-Match` for a
weak validator and *anything at all* after a 401 are rules about a header's **absence**, and
`assertNull(etag.asIfMatch)` is a claim about a getter, not about the wire. `RequestCounts` now
counts which headers each method carried, so row 16 asserts *no PUT carried an `If-Match`* — with the
first upload, whose validator is still strong, as the control that proves the counter can reach one.

### Row by row

Re-measured on **2026-08-10** from a forced run of all five unit-test tasks (`--rerun`,
`--no-build-cache`, `BUILD SUCCESSFUL in 4m 27s`): 27 matrix tests, 0 failures, 0 skipped, and 26
`— LIVE: http://192.168.1.13:8088` lines in the captured stdout. The phrase
`request counts not observable` appears **zero** times in the results XML, which is the check that
the proxy is in the loop rather than merely present.

**`Live` column:** ✅ = the row's own assertions ran against the real Nextcloud.
**setup only** = the live half ran and returned at a trigger it cannot produce.
**n/a** = the row has no server in it by design.

| # | What it asserts | Where | Live | Still owed |
|---|---|---|---|---|
| 1 | Grow-only union, `foldPage`'s clock sort, one PROPFIND pair and exactly one GET per direction | `MergeMatrixLayerOneTest`; JVM half in `NoteMergeTest` | ✅ incl. both GET counts | — |
| 2 ⚑ | `Presence` as an LWW register: delete beats transform, no ghost, losing op still in the log | `MergeMatrixLayerOneTest` | ✅ | — |
| 3 ⚑ | Strictly-later-wins plus the `deviceId` tiebreak; identical matrix on both, never a product; **no two ops share a clock** | `MergeMatrixLayerOneTest` | ✅ | — |
| 4 | Independent registers per field (style ∥ transform) | `MergeMatrixLayerOneTest` | ✅ | — |
| 5 ⚑ | Page-order merge and never-drop; document merged before page ops; **both new pages survive `materialize()`** | `MergeMatrixLayerOneTest` | ✅ | — |
| 6 | Clock-free deterministic tiebreak; two conflicting reorders of 4 pages converge byte-identically | `MergeMatrixLayerOneTest` | ✅ | — |
| 7 ⚑ | `SetErasedRegion` absolute; erase never touches geometry; one object carries erase *and* transform | `MergeMatrixLayerOneTest` | ✅ | — |
| 8 ⚑ | `SNAPSHOT_STALE` → discard → re-fold, 3-page note with the peer's op on **page 2**; real 2 MB journal, real 30-day horizon, constants untouched | `MergeMatrixFoldTest` | ✅ | — |
| 9 ⚑ | Layer 3 on an unreadable pull: never auto-recovered, local note and ETag untouched, `REMOTE_NOT_RECOVERABLE` row; plus 9b, first-write-wins on a re-merge | `MergeMatrixFoldTest`, `PulledPdfExtractorTest` | ✅ | The **flattened-page** (>500 strokes) and **recover-then-re-upload** variants are not written at all |
| 10 ⚑ | `merge` bypasses the undo stack; undo after a pull removes this device's stroke and not the peer's, through a **reopened** session | `MergeMatrixFoldTest`, `NoteEditorRemoteOpsTest` | ✅ (JVM half) | The wet-ink/camera half is **not** live and not JVM: it is `WetInkRemoteMergeTest`, instrumented, 2 tests, green on the AVD only. `activeStrokeId` is private to `InkSurfaceView`, so a device is the only vantage point. And a peer's `SetPageMeta`/`SetTransform` on an **existing** object still does not render until a page switch (§8) |
| 11 | Same `oc:id`, same ETag, new href: zero GETs, one library row, `localRev` unchanged | `MergeMatrixTransportTest` | ✅ — and **only** live is the real thing | In-process, the third-party rename is `LocalMatrixServer.moveDirectly`, so `WebDavClient.move` and the proxy's `Destination` rewrite are in the loop live only. Live therefore also settles the row's own `notProven` string: a real Nextcloud MOVE **did** preserve the ETag, since `moved == 1` is unreachable otherwise |
| 12 | Cancellation spends no attempt and leaves the remote file whole | `MergeMatrixTransportTest` | ✅ | A body that stops arriving **mid-socket** — needs an nginx `limit_rate` sidecar. The cancellation is injected at the materializer instead, deliberately, because mid-socket is a race |
| 12b | A 507 parks rather than laddering; the day-long park releases on the clock; `OC-Checksum` verified server-side so a 2xx proves the header | `MergeMatrixTransportTest` | **setup only** | A quota the test can fill. Live, `refuseWithQuotaExceeded` returns false and the row returns after `share`/`drawStroke`, so **every assertion in this row is in-process only**. Also unproven: that a real Nextcloud answers 507 rather than 413 or 500 |
| 13 | 412 → park → `DrainReport.conflicted` → reconcile → un-park → re-upload with the fresh `If-Match`, **no conflicted copy**; 13b: unmergeable → copy with a **new `DocumentId`** | `MergeMatrixTransportTest`, `NoteMergeTest`, `ReconcilerFileTest` | ✅ both halves | — |
| 14 | Local trash not a hard delete, **no DELETE enqueued**, `REMOTE_DELETED` row, restore with the unsynced edit intact | `MergeMatrixTransportTest` | ✅ | — |
| 15 | `nc:data-fingerprint` moved → cache cleared, unpruned walk, **zero GETs, zero trashes** | `MergeMatrixTransportTest`, and the pruning half in `ReconcilerWalkTest` | **setup only** | `occ maintenance:data-fingerprint`, i.e. an SSH key to the Pi. The live half asserts that a real pass cached a directory ETag and then returns, so its `GET == 0` is the one count in the suite the proxy did **not** make assertable. Its `pruned == 0` is additionally **vacuous in the matrix fixture** — a run folder holds files and no child collections — and that half is carried by `ReconcilerWalkTest`'s *a fingerprint change clears the directory cache and walks everything* (`pruned == 0, walked == 2`). The ETag-churn half needs a real backup restore and stays a manual gate |
| 16 | `ETag(value, weak)`, `asIfMatch` null, **no PUT carries an `If-Match`** and one still succeeds in one PUT, and the change decision falls back to a server-published `OC-Checksum` | `MergeMatrixTransportTest` | ✅ | A server that also weakens `<d:getetag>` **inside a multistatus body**. `CountingProxy` weakens response headers only, which is what nginx does; `LocalMatrixServer.useWeakEtags` weakens the body too, so between the two halves both shapes run — but no single run sees both |
| 17 | `(noteId, opType)` UPSERT: 500 saves → one row at the newest rev with the earliest `created_at`, no attempt spent → exactly **one** PUT | `MergeMatrixOutboxTest` | ✅ incl. the PUT count | — |
| 18 ⚑ | The mid-flight `local_rev` guard: 2xx does **not** set `SYNCED`, row refreshed to the new rev, second PUT carries the new bytes | `MergeMatrixOutboxTest` | ✅ | — |
| 19 | Rows, `attempts` and `next_attempt_at` survive a file-backed close/reopen and are still due | `MergeMatrixOutboxTest` | **n/a** | Nothing from a server. This row is `runTest`, not `matrixRow`: a file-backed `JdbcSqliteDriver` closed and reopened, no HTTP anywhere in it. The half a server could never give it — a kill *during* the write — is `CrashSafetyTest`'s **upload kill**, instrumented and green on the AVD (§5.10) |
| 20 | Exactly one PUT, exactly one `GET /ocs/v2.php/cloud/user`, **two requests in total**, `NEEDS_REAUTH`, `shouldRunAgain == false`, then **zero requests of any method** from the reconciler | `MergeMatrixOutboxTest` | ✅ — a real 401 | Reading `nextcloud.log` to confirm no brute-force lockout entry. ⚠️ The live half spends exactly **two** of that instance's brute-force attempts, which is also what it asserts |
| 21 | A restore before the DELETE drains cancels it; after it drains the re-upload is a create and `remoteOcId` is repaired from the PUT response with a **different** fileid | `MergeMatrixOutboxTest` | ✅ | The fileid the live half observes comes from a **re-create** after a drained DELETE, not from a Nextcloud **trash-restore** in the web UI. That the latter also mints a new fileid is documented and still unobserved here |
| **+** | **ETag ping-pong terminates**: ≤3 rounds to settle, then 5 polls each cost one `PROPFIND`, zero GETs, zero PUTs, no ETag movement | `MergeMatrixConvergenceTest` | ✅ incl. all three counts | — |
| **+** | **§7.7 layer 2 converges**: a rename crosses in **both** directions, the clock survives each round trip and orders the next rename, the reopened note *and* the library row agree on both devices, the loser is recorded, and neither device is left owing anything | `MergeMatrixTitleTest`; the mechanisms in `MergeFoundationTest`, `NoteRenameTest`, `ReconcilerFileTest` | ✅ | — |
| **+** | **§7.7 layer 3 is bounded**: an unmergeable file met on four polls (a neighbour's uploads moving the folder ETag, so the walk really re-reaches it) leaves **one** copy and **one** row, and a row the user dismissed in between stays dismissed and still names that copy | `MergeMatrixConvergenceTest`; the mechanisms in `ReconcilerFileTest`, `AttentionTest` | ✅ | — |

⚑ marks the rows ROADMAP M6 names as catching design bugs rather than coding bugs.

**Summary of the column:** 24 of the 27 bodies assert their own claims against a real Nextcloud.
Two (15, 12b) reach it and return at a trigger needing a shell on the instance. One (19) has no
server in it and should not have. Nothing in the table is *skipped* in the JUnit sense — the run
reports 0 skipped — which is why the distinction has to be written down rather than read off a
green tick.

### What M6 actually verified

Three claims this project has previously blurred, kept apart here on purpose. The ordering is by
how much each is worth, and the gap between the first and the third is where every one of §6's four
inert features lived.

**Bucket A — *written*.** Code exists and compiles; no assertion has been executed against it on
any machine, or the assertion runs somewhere that cannot reach the behaviour. Nothing in the matrix
is here any more. What still is: the flattened-page and recover-then-re-upload variants of row 9
(not written at all), and `CountingProxy`'s **HTTPS upstream branch** — written, commented as
untested, and unreachable while the throwaway instance is plain HTTP.

**Bucket B — *automated*, i.e. it runs on every `./gradlew build` against something that is not a
real Nextcloud.** In-process here means a real HTTP/1.1 server on loopback with the production
`NextcloudTransport`, real OkHttp, the real `WebDavClient`, the real PROPFIND body and the real
multistatus parser in the loop — only the storage behind the socket is fake. Rows fully in this
bucket and no further: **12b** and **15**. Row **19** is in it in a different sense: it has no
server at all, by design, and its missing half is instrumented rather than live.

**Bucket C — *automated and run against a real Nextcloud 32.0.13*.** Rows **1, 2, 3, 4, 5, 6, 7, 8,
9, 9b, 10 (JVM half), 11, 12, 13, 13b, 14, 16, 17, 18, 20, 21** plus all three unnumbered rows
(convergence, layer 2, layer 3). Since `CountingProxy` this bucket also covers SPEC §7's **economy**
claims, which are request counts and were previously unmade live: one GET per direction (1), zero
GETs for a move (11), no `If-Match` for a weak validator with a strong-validator control (16), one
PUT for 500 saves (17), two requests total for a 401 and then zero (20), and one PROPFIND per idle
poll for five polls (convergence).

**A fourth bucket the matrix cannot enter at all: *run on a device*.** Row 10's wet half, SPEC
§13.3's four process kills and SPEC §13.1's migration are instrumented and green on an AVD; none of
them is live-Nextcloud, because the app cannot reach the test instance (plain HTTP, and a
Play-Store image takes no system CA). And **nothing in any bucket has run on the physical Galaxy
Tab**, which is CLAUDE.md definition of done #4 and where ink feel, latency and frame timing live.

Every row has an explicit `runTest(timeout = …)` — 4 minutes, 8 for rows 8 and 17 — because
`kotlinx-coroutines-test` defaults to 60 s, which is wrong for a suite that may wait on a real
server. The live half runs under `testDebugUnitTest` only, so 21 rows do not make every round trip
twice, and each fixture claims a unique remote folder (`INKSTONE-M6-MATRIX/run-<millis>-<rand>`) so
two runs cannot collide and manufacture a false conflict in the milestone whose subject is
conflicts.

### The one seam worth naming

`MatrixGate` performs `NoteSessionHost`'s steps in its order against the real `NoteStore`,
`NoteMerge` and `LibraryIndex` — but it **is not** `NoteSessionHost`, which needs
`LibraryRepository`, which needs `PdfGateway`, `ThumbnailCache` and a Compose `ImageBitmap`. So the
per-note `Mutex` and two-tier thumbnail invalidation are exercised **nowhere**, here or elsewhere,
and the same is true of the *choice* the host makes on each of its four entry points — merge, rename,
materialize, trash — between a live editor and its own session. What each of those hands *to* the
editor is covered: `NoteEditorRemoteOpsTest` and `NoteEditorConcurrencyTest` drive
`mergeRemoteBundle` and `materializeInSession` directly, which are the methods the registry resolves
to. The registry lookup itself is covered by inspection only. That is stated in `MatrixGate`'s own
KDoc as well as here, because §6's whole lesson is that a green suite must not imply coverage it does
not have.

---

## 8. What M6 deliberately did not do

- **No chunked upload.** SPEC §7.5: not in v1. Appendix B #3 stays open. Note that a 412 on a
  large PDF means download → extract → union → rewrite → re-upload inside a job with a
  ten-minute ceiling, so files over ~50 MB will eventually need a foreground service.
- **No `notify_push`.** §4.
- **No conflict UI beyond the list.** Reviewing a conflicted copy means opening it like any other
  note; there is no side-by-side, no per-object picker, and SPEC §12 keeps it that way.
- **The `:android:ink` half of live re-render.** `NoteSurface.setPageMeta` is a **documented
  no-op** on the real surface. Changing the paper without moving the camera needs a method
  `InkSurfaceView` does not have — its only route is `loadPage`, which calls
  `resetCamera(FIT_WIDTH)` unconditionally and cancels unfinished strokes, i.e. both of row 10's
  forbidden outcomes — and `StrokeStore.applyOps`'s `else -> false` drops a remote `SetPageMeta`
  regardless. So a peer's paper change, and a peer's transform of an *existing* object, fold
  correctly, reach the bundle, and appear on screen at the next page switch. **Data-safe, visibly
  late.** Doing it badly would have traded a stale picture for an eaten stroke and a jumped view,
  which is the worse of the two.
- **`:android:ink`'s instrumented x86_64 failures**, which are M1's orientation-clamping fixture
  and were left alone in M5 for the same reason.
- **The two `noteRestored` defects** in §5.4.

---

## 9. Acceptance criteria not yet met

In rough order of how much they would change what is known:

- ~~**Any of the 21 rows against a real Nextcloud.**~~ **Done, with three qualifications.** 26 of
  the 27 matrix bodies run twice — in-process and against a real Nextcloud 32.0.13 — and both halves
  are green. 24 assert their own claims live. Rows **15** and **12b** reach the server and return at
  a trigger needing a shell on the instance; row **19** has no server in it by design and is not one
  of the 26. Every request count that exists is now measured live. §7 and its `Live` column.
- **All six of ROADMAP M6's manual gates**, including the calendar week of two-device use with
  deliberate airplane-mode toggling. **None has happened**, and none can be faked from here: five of
  the six need a second physical device and the sixth needs seven days of elapsed time. What is
  automated in their place is named per gate in `ROADMAP.md`, so the substitution is visible rather
  than implied.
- **A pass on the actual Galaxy Tab** (CLAUDE.md definition of done #4). The tablet still shows as
  `unauthorized`, so `tools/send-to-tablet.ps1` is the way on and `dumpsys gfxinfo` is unavailable
  until the USB-debugging dialog is accepted. The *emulator* pass is done (§5.10) and deliberately
  does not substitute: ink feel, latency and frame timing exist only on the tablet, and
  `:android:ink`'s suite is red on x86_64 for reasons that are green on arm64.
- ~~**The three instrumented tests written after the one `connectedDebugAndroidTest` run.**~~
  **Done.** Every module with instrumented tests has now run on `Test_Tablet(AVD) - 15`:
  `:core:database` **47, 0 failures** (`LibraryIndexTest` 15, `SqlSyncStoresTest` 9,
  `SqlReconcileStoresTest` **20** — the three the repairs added have now executed — and
  `MigrationOnDeviceTest` 3); `:app` **6, 0 failures**; `:android:ink` **15 with 10 failures, all
  of them M1's** and all x86_64-only (nine on the orientation-clamping fixture, one on an
  arm64-tuned float tolerance). Row 10's wet half is written and green — §5.10.
- **Guardrail 2's framework link.** `linkIosCore` is macOS-only and logs `SKIPPED` here (§2), so the
  iPad path has been proved this milestone as far as the klib compile and no further. Counted from a
  build on 2026-08-10: **25 `link*Ios*` tasks** skip (5 `:core:*` modules × `linkDebugFrameworkIosArm64`,
  `linkReleaseFrameworkIosArm64`, the two `IosSimulatorArm64` framework links, and
  `linkDebugTestIosSimulatorArm64`).

  ⚠️ **And a second thing skips with them: `iosSimulatorArm64Test`, in all five modules.** So the
  common-source tests are *compiled* for Kotlin/Native and never *executed* on it. That is not
  nothing: it is exactly the axis on which the same source diverges — M5's finding that a backtick
  test name containing a comma is rejected by Kotlin/Native alone was caught by
  `compileTestKotlinIosArm64`, but a *behavioural* difference (arithmetic, `Char` handling, the
  hand-written SHA-256 and MD5 in §2) would not be. Everything §7 reports is a JVM result.

  ⚠️ **Corrected at the audit: it is not "one CI run away".** `git remote -v` is **empty**. There is
  no `origin`, so the macOS `ios-core-link` job has never run for this repository at all, on any
  branch, since M0. Earlier text here said *"CI has not run on this branch"*, which implied a remote
  that does not exist. Satisfied by a remote and a push, or by a Mac.

- **M6 is not committed.** 54 modified files and 45 untracked paths, +5,774 lines, working tree only;
  `HEAD` is still `d6993e3` (M5) on a branch called `m5-nextcloud-auth-upload`. Every measurement in
  §7 describes a tree that exists on one disk, uncommitted, with nowhere to push it. Worth stating
  beside the test counts, because a green suite over an uncommitted tree is one lost directory away
  from being no suite at all.
- **SPEC §13.1's remaining gap: no bundle fixture is committed for any schema version.** The
  *migration* half is closed — `MigrationOnDeviceTest` builds a real version-4 database with real
  rows and opens it through the production driver, so `Schema.migrate` runs on a device for the
  first time (§5.10). What is still owed is a committed **bundle** fixture per historical schema
  version, which is a different artefact from a database fixture and belongs to `:core:storage`.
  Until one exists, *"round-trip every schema version"* is a round trip of whatever the current
  build writes.

  ⚠️ **`SPEC.md` §13.1 still says both halves are open** — *"there is still no on-device migration
  test"* — which was true when it was written and is now false. That paragraph needs the same
  correction this bullet got; it is left flagged here rather than edited because this pass was
  scoped to `ROADMAP.md`, `README.md`, `CLAUDE.md` and this file.
- **The manual PDF gate** the `/Resources` fix now makes worth repeating: the same file opened in
  Acrobat, Chrome and Preview. `INKSTONE_PDF_DUMP` writes one out.
- Still carried from M5: an instance where the login name is an email address (uid ≠ loginName),
  and reading the Nextcloud log for zero brute-force entries after a revoked app password.
