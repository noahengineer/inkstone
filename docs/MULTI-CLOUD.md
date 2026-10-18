# Mirroring a note or folder to every cloud — design

**Status: design, 2026-10-18. Nothing here is built.** Asked for by the user: *"a shared path so that when syncing
a file or folder you set it once, not three times"*, and, asked which meaning, chose **mirror to every account**.
Read `docs/CLOUD-PROVIDERS.md` first; this builds on its `CloudStore` seam and its per-account reconciler.

## 1. What is being asked

Today a note is bound to **one** account. To have a note in Nextcloud, OneDrive and Google Drive you save it
three times, choosing an account and a path each time. The ask: choose a path **once** and have the note (or the
folder, and everything in it) sync to **every connected account**, each holding its own copy.

## 2. The thing that makes this big, and the thing that makes it possible

**Big:** one-binding-per-note is not a convention, it is the schema.

| Where | Today | Needs |
|---|---|---|
| `remoteBinding` | `PRIMARY KEY (noteId)` | `(noteId, accountId)` |
| `folderBinding` (a pinned folder) | `PRIMARY KEY (folderId)` | `(folderId, accountId)` |
| `outbox` coalescing key | `(noteId, opType, mediaKey)` | `(noteId, accountId, opType, mediaKey)` |
| `note.syncState` | one value | a value **per binding**, with the note's own state *derived* |
| call sites | `bindings.find(noteId)` ×24, `BindingStore` ×29, `setSyncState` ×26, `syncState` ×45 | each must name an account |

SQLite cannot alter a primary key, so migration 12 is a **table rebuild** of three tables holding every user's
sync state. SPEC §13's migration rules apply in full, and the on-device migration test written in M6 and
**still never run** (it would uninstall the app and wipe the emulator's fixture) becomes a hard prerequisite.

**Possible:** a note is not a file, it is a journal that merges. Two replicas that diverged converge by merging
their ops (SPEC §4.3, §7.7) — which is exactly what lets three clouds be three *replicas* of one document
rather than three competing authorities. The local store is the hub; each cloud is a spoke; **no cloud talks to
another**, so there is no cross-cloud protocol and no cross-cloud loop, only the per-account loop that exists now.

## 3. The model

- **A binding is `(note, account, path)`**; a note has zero or more. The path is *shared*: the user chooses it
  once and it is stored per binding, so an account may later differ (renamed on the server) without breaking
  the others.
- **Each binding keeps its own** `lastSyncedEtag`, `lastSyncedRev`, `remoteOcId`, `lastChecksum` — the things
  `If-Match` and the pull depend on. Sharing any of them across accounts would be the original sin.
- **A change fans out through the outbox as one row per account.** `noteChanged` enqueues `(note, A)`,
  `(note, B)`, `(note, C)`. Rows are still markers (rule 4). **A failure on one account must not delay or block
  another**: its row backs off, parks, or halts (401 → `NEEDS_REAUTH`) on its own, and the others drain.
- **A pull is per account, as today.** A remote change from A merges into the local note (existing path), bumps
  `localRev`, and `noteChanged` then enqueues uploads to B and C — *and* to A, where it finds nothing new to send
  (the merged result equals A's file; the existing unchanged-ETag no-op covers it).
- **The note's sync state is derived**: `SYNCED` only when **every** binding is; otherwise the worst one, with a
  per-cloud breakdown for the screen ("Nextcloud ✓ · OneDrive ✓ · Drive — waiting"). A cloud that is offline
  must not make a note look unsaved everywhere, and must not make it look saved when it is not.
- **Mirror policy.** A binding set is explicit per note/folder, **plus** an optional "all accounts" flag on a
  folder pin so a *newly connected* cloud adopts it (with a confirmation, because that starts uploading a
  library). See decision D2.

## 4. What must stay true (CLAUDE.md, applied to many accounts)

1. **No data loss / no discarded bytes.** Per-account `If-Match` (or Drive's check-write-verify) is unchanged.
   Fan-out must never turn one account's 412 into an overwrite on another.
2. **One writer per file** still holds: the single-writer gate (`NoteSessionHost`) guards the *local* bundle,
   and every account's upload reads from it, so three uploads of one note read one consistent file.
3. **Rule 4 (outbox) and rule 5 (never retry a 401)** are per account. One account's dead credential halts that
   account's rows only; the rest of the library keeps syncing to the others.
4. **Deletion is the dangerous verb** — see D1.
5. **No conflict dialogs.** A divergence between clouds heals by merge and shows in "Needs attention" if it
   cannot, as today.

## 5. Decisions that are the user's

- **D1 — Deletion.** *Locally trashing a note* trashes it in every cloud (each soft-trash, never permanent) — that
  is what "mirror" means. But *a deletion noticed on one cloud*: (a) detaches only that cloud and keeps the note
  and the other clouds' copies, or (b) propagates (local trash → trashed everywhere). **Recommend (a)**: a
  mirror whose purpose is redundancy should not let one cloud's mistake or one person with access to one
  account delete the other two copies. It is the safer reading of rule 1.
- **D2 — Which accounts.** (a) an explicit set chosen per note/folder, (b) always every account, or (c) explicit
  set **plus** an "all accounts" flag on a folder pin. **Recommend (c).**
- **D3 — The shared path's meaning.** One relative path (`School/Physics/Waves.pdf`) used under each account's own
  default folder, so each cloud keeps its own top-level folder name. Or one absolute path required to exist
  identically everywhere. **Recommend relative-under-default-folder**; Drive's folders are by id, so an absolute
  path is not even a stable idea there.
- **D4 — Order of work and the risk budget.** This rewrites the schema under every synced note. Recommend
  doing it in the phases of §6, stopping after each for the user to try it on the emulator, **not** on the
  tablet's real library until phase 1's migration has run on a real device DB.

## 6. Phases (each one shippable and checkable on its own)

0. **Prerequisites.** Run the unrun on-device migration test on a *throwaway* AVD (not `Test_Tablet`, whose
   fixture it would wipe). Add a fixture DB of schema 11 to the migration test. Decide D1–D3.
1. **Model with one binding per note still enforced by the UI.** Migration 12 (rebuild three tables; every
   existing row becomes the single binding it already is); every call site made account-aware; the derived
   note state; `SyncEngine.bind` takes an account. **No user-visible change.** The whole existing suite
   (SPEC §13.2's 21 rows, the OneDrive and Drive suites) must pass unchanged — that is the proof that nothing
   moved. This is the phase that can lose data if wrong, so it ships alone.
2. **Fan-out.** `noteChanged`/`noteTrashed`/`noteMoved` enqueue per binding; the drainer already isolates by
   row; add the cross-account propagation after a pull. New matrix rows (§7).
3. **UI.** "Save to…" becomes a checklist of accounts with **one** path; folder pin the same, with the
   "all accounts" flag; per-cloud state on the note and in Sync; "add this cloud to my synced notes".
4. **Backfill and edge cases.** Connecting a new cloud to a library already bound elsewhere; disconnecting one;
   a note bound to the same account twice (refused).

## 7. New tests the design demands (beyond the existing 21 rows)

- Two accounts converge: edit on A is on B and C; edit on B while A is offline merges when A returns.
- **Isolation:** A returns 401/412/429/500 while B and C drain — B and C complete, A parks or halts alone.
- A remote deletion on one account follows D1; a local trash reaches every account as a trash, not a delete.
- The same note bound to two accounts whose paths differ; a rename on one only.
- A new account connected to a library: uploads begin only after confirmation, one account's rows at a time,
  and nothing already on that account is overwritten (`CreateOnly`).
- Migration 12: every row of a schema-11 fixture survives with its ETag, rev and path, **on a device**.

## 8. What I do not know and have not measured

- How many of the ~130 call sites are trivially mechanical and how many hide a real "which account?" question.
  The count above is from `grep`, not from reading each one.
- Whether the Library screen's tile (one badge, "Saved to Nextcloud") is the right unit once a note has three
  states; that is UX, and it should be tried before being decided.
- Cost: three uploads of a 15–90 MB note per save. The drainer already coalesces and backs off per row, but
  mobile-data behaviour with three destinations has not been thought through.
