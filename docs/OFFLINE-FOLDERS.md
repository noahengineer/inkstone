# Choosing an upload path with no network

> *"I don't like how you have to be connected to the wifi to set an upload path for folders/files
> since it does not remember the file structure of the server."*

The report is exactly right, and the interesting part is how narrow the real dependency was.

---

## 1. What was actually broken

Binding a note to Nextcloud (SPEC §7.6) is five steps, and **four of them never touch the
network**. `SyncEngine.bind` writes three rows — the binding, a `MKCOL` outbox row, an `UPLOAD`
outbox row — sets `PENDING_UPLOAD` and returns. It opens no socket, and its `MKCOL` is
*unconditional* precisely so that the folder need not exist yet:

> *"finding out whether the folder exists is a network call, this method must not make one, and a
> MKCOL against an existing collection is a 405 that `mkcolRecursive` already reads as success."*

So the whole *decision* — this note goes in `Physics/Term 2` — has always been offline-safe. The
one step that needed a connection was **step 2, seeing the names**: the browser is one
`PROPFIND Depth:1` per level and it kept nothing. With no server, `browse` returned
`BrowseResult.Failed`, `SyncViewModel` drew an empty folder list with a red line, and a decision
that costs nothing became impossible to make.

The same is true of the browser's other three uses: pinning a folder (`syncFolderToRemote`),
setting the account default (`setDefaultFolder`), and moving a note. None of them is a request.

**The one exception is adopting a remote PDF**, which is a `GET` of the file's bytes. That cannot
be remembered, and the browser now says so instead of listing documents it could not open.

---

## 2. The table, and the table it is deliberately not

`browsedFolder` (`9.sqm`) holds one row per remote folder the browser has seen: `accountId`,
`path`, `parentPath`, `name`, `ocId`, `seenAt`, `listedAt`, `pending`.

⚠️ **It is not `remoteDirectory`, and merging them would be a data-loss bug.** They look alike:
both are per-account caches of remote collections. They mean opposite things.

| | `remoteDirectory` | `browsedFolder` |
|---|---|---|
| Written by | the reconciler's walk | the folder browser |
| A row means | *this directory was **walked to completion** at this ETag* | *a folder by this name was listed here at this time* |
| Read by | `RemoteWalk` — to decide what **not** to request | the picker — to decide what to draw |
| A wrong row causes | a subtree silently stops syncing; a note read as deleted and trashed | a folder name in a list is stale |

CLAUDE.md already records what a row `remoteDirectory` did not earn does: *"a pruned subtree looks
exactly like a deleted one"*, and *"cache a directory's ETag only once everything beneath it
finished"*. A browser that wrote into it would be writing rows for directories nothing walked.

Traffic between them runs **one way and read-only**: `SqlBrowsedFolderStore.childrenOf` unions in
the reconciler's walked directories for *display*, so a user who has never opened the browser
while connected still sees the folders their notes already sync to. Nothing that decides what
syncs reads `browsedFolder`.

---

## 3. Three things that were nearly wrong

**`INSERT OR REPLACE` would have forgotten every child's own listing.** It deletes the conflicting
row and re-inserts, resetting every column the statement omits — here `listedAt`, i.e. *"I have
seen inside this folder"*. Re-listing a parent would silently forget that its children had ever
been looked into, and a user offline would be told a folder they browsed last week has never been
listed. This is `remoteBinding.lastSeenRemoteAt`'s bug, one table along; the fix is
insert-if-absent plus a narrow `UPDATE`, so the trap is unrepresentable rather than remembered.
Asserted by `SqlReconcileStoresTest`'s *"re listing a parent keeps what is known about its
children"* — on the **device's own SQLite**, because it is a claim about SQLite.

**"Empty" and "never listed" are different sentences.** `listedAt = 0` means the browser has never
seen inside this folder; a timestamp with no children means there is nothing in it. Drawing them
the same way tells a user their Nextcloud is empty, which is the alarming direction and the one an
offline picker falls into by default. `OfflineBrowsing.notice` is a pure function over three
values precisely so a JVM test can assert the strings (`SyncViewModel` cannot be constructed in
one).

**A 401 must not become a working browser.** The fallback fires only when the failure means
*"could not be told"* — `DavFailure.isRetryable` minus `Locked`, which is a fact about the folder
rather than the connection. A 401 is halted first (CLAUDE.md rule 5) and then reported: the user
has to be sent to sign in, not left choosing folders nothing will ever be written to. A 403 or 404
is the server answering *about this path*, so serving the cache would contradict something we have
just been told.

---

## 4. A folder made with no network

`createRemoteFolder` answers `Deferred` rather than `Failed` when the MKCOL cannot reach the
server. The folder is remembered with `pending = 1`, drawn in the list with *"not created yet"*
beside it, and can be walked into and picked. What actually creates it is the binding's own
unconditional `MKCOL` — **nothing is enqueued at folder-creation time**, because the outbox is
keyed by note (SPEC §7.6) and a queued `MKCOL` with no note behind it would be a row no binding
owns. A folder nobody saves anything into never needs to exist.

A `pending` row is exempt from the sweep that removes folders a successful listing did not
mention: it is absent from the server *because* its MKCOL has not run, and sweeping it would take
away a destination the user has already chosen. A live sighting clears the marker.

---

## 5. What was checked, and how

Per CLAUDE.md's definition of done #6, each claim with the check that backs it — and which bucket
that check is in.

| Claim | Check | Bucket |
|---|---|---|
| Folders listed while connected are offered when the server is unreachable | `OfflineFolderBrowserTest` — real `NextcloudAccounts`, real OkHttp transport, real `WebDavClient`, real SQLite; offline produced by **closing the socket**, not by injecting a failure | automated, against the production path |
| …and the choice lands: the note binds and `MKCOL` + `UPLOAD` are queued with the server still shut | same test, asserted off the outbox | automated |
| A folder created offline is offered, marked, and can be picked | same test | automated |
| A 404 is reported and never answered from the cache | same test | automated |
| A folder deleted on the server stops being offered | same test | automated |
| The three offline sentences, and relative ages | `OfflineBrowsingTest` | automated |
| `listedAt` survives a re-listing of the parent; the sweep spares `pending`; the root is not its own child; walked directories are offered; accounts do not leak into each other | `SqlReconcileStoresTest`, 7 new cases | automated, **on the emulator's own SQLite** |
| A v4 database upgrades to v9 and the new table arrives empty (and reads as *never listed*, not *empty*) | `MigrationOnDeviceTest` | automated, on device |
| The tests fail against the unfixed build | the fallback was disabled and 3 of the 4 went red; the 404 case passes either way, which is correct — it asserts the *absence* of a fallback | done |
| Hilt graph, and the whole flow through the UI | ⛔ **not done here** — see §6 | — |

⚠️ **§6 is not a formality.** Two of this repo's milestones shipped features that were green in
every test and inert in the app.

---

## 6. What is NOT verified, and why

**Nothing in this change has been driven through the app by hand.** The folder browser needs a
signed-in account, and CLAUDE.md records why the emulator cannot have one:

> *"Neither peer can sign in to the plain-HTTP test container, and no manifest or resource change
> fixes it. […] Two-peer work needs an HTTPS Nextcloud, and the sign-in is the user's step either
> way — the app never sees the password (SPEC §7.1)."*

So the two checks that remain are both on the tablet, with the real account:

1. **Sign in, open "Save to Nextcloud…", turn off wifi, and browse.** The folders should still be
   there, with a line saying when they were last seen. Save into one, turn wifi back on, and
   confirm the note appears in that folder in Nextcloud Files.
2. **New folder while offline**, save a note into it, reconnect, and confirm the folder *and* the
   note appear on the server.

Until those happen, the honest statement is: the logic is covered against the production
transport and the device's SQLite, and the wiring from the screen has been read but not pressed.
