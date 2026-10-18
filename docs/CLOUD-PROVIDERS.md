# Cloud providers — Google Drive and OneDrive beside Nextcloud

## Decisions (user, 2026-10-17)

- **D1** OneDrive first. **D2** Drive option (a): **Drive is not shipped until Google adds a
  precondition** (§3.2) — no Drive code, no rule 1 amendment. **D3** OneDrive `Files.ReadWrite`; Drive,
  if it ever ships, `drive.file` with no adoption. **D4** assume commercial. P0 and P1 authorised.

## Progress

- **P1 — built, behaviour-preserving** (`core/sync/.../CloudStore.kt`). `OutboxDrainer` and
  `Reconciler` now hold a `CloudStore`; `NextcloudStore` forwards every call to the unchanged
  `WebDavClient`. Where it differs from the §2 sketch, and why:
  - the change-feed method is **not** there yet — an interface with one implementation that cannot
    return it is an untested seam; it arrives with OneDrive's `delta` (P3);
  - `DavOutcome` / `DavFailure` keep their names (renaming is churn with no behaviour change);
  - addressing is `RemoteTarget(path, id)`, so OneDrive can update by id without reshaping call sites;
  - the write precondition is `WritePrecondition { CreateOnly, IfMatch, Unchecked }`. `Unchecked` is
    what Nextcloud already does when no usable ETag is stored (weak or stripped: no header is sent, and
    the checksum stands in). **That is pre-existing, unchanged, and a rule 1 soft spot of its own** —
    it is exactly why a store with `supportsUncheckedReplace = false` (OneDrive) must refuse it.
- **P0 — instrument written, not run.** `tools/cloud/graph_spike.py` + `tools/cloud/README.md`
  (now M-M1 … M-M10). It needs an Entra app registration, and a plain personal Microsoft account has no
  Entra directory (the portal answers `AADSTS50020`), so registering means an Azure free sign-up first.
  **Decision (user, 2026-10-17): skip it for now and build against a stand-in.** The premise of all
  of the below — that a stale `If-Match` on `createUploadSession` is refused — rests on Microsoft's docs
  and one outside developer's log, **not on anything seen here**. Run the spike before anyone relies on this.
- **P2 — account model, built.** `10.sqm` (`account.provider`, `account.providerData`, both additive);
  `SyncAccount.provider`; `ProviderKind { NEXTCLOUD, ONEDRIVE, UNKNOWN }` where **UNKNOWN is what an
  unrecognised name reads as and no factory opens it**; `ProviderRoutingFactory` (no fallback factory);
  `NextcloudStoreFactory` declines a non-Nextcloud account. `upsertAccount` is `INSERT OR REPLACE`, so
  `provider` is in its column list — a unit test checks it survives `setState` and `upsert`.
  *Not done:* the on-device migration test is **written and not run** (it needs the emulator, and
  `connectedAndroidTest` uninstalls the app and takes its data with it); and the Keystore-backed
  `TokenVault` is an interface only — the Android implementation shares the credential file's crypto and is
  written with the sign-in UI, where it can be exercised on a device.
- **P3a — `OneDriveStore` and `MicrosoftTokenSource`, built, tested against a stand-in.**
  `OneDriveStore` writes only through upload sessions with `If-Match`, addresses updates by item id,
  creates with `conflictBehavior: fail`, deletes to the recycle bin, never retries, and sends no bearer token
  to the pre-authorised upload or download URL. `MicrosoftTokenSource` implements §5.3: refresh before
  sending when nearly spent; one forced refresh after a 401, persisted cooldown; `invalid_grant` is sticky
  and never asked about again; unreachable concludes nothing; the rotated refresh token is saved **before**
  the access token is used. `GraphStandIn` (a `ServerSocket` server) and 26 tests drive it (plus 4 for the provider column, 3 for routing, 1 for the feed skip) — including
  `OneDriveThroughOutboxTest`, which runs the production `OutboxDrainer` over real SQL stores.
  Five mutations (drop `If-Match`; bearer to the upload URL; non-sticky `invalid_grant`; no cooldown; and
  the reconciler's feed guard) each turned tests red; a sixth (allow an unchecked write) was **not** run.
- **P3b — the `delta` pull, built and tested against the stand-in** (user: "build the OneDrive pull next").
  `CloudStore.changes(cursor)` returns a whole, path-resolved `ChangeBatch` (`FIRST` / `INCREMENTAL` /
  `RESYNC`); `Reconciler.feedPass` reads it and **reuses every per-file decision the folder walk makes**
  (`visit`: moved / changed / adopt / layer 3, the merge, the outbox table, the foreign-file cache) by
  handing each changed PDF to the same code. What is different, and why it is safe:
  - **a deletion is named, never inferred** — only a `Removed` in an `INCREMENTAL` batch trashes a note.
    After a first enumeration or a `410` resync a missing file is *not* a deletion (Graph's own
    `resyncChangesUploadDifferences` means the *server* lost things), so the note stays bound and, if the
    file is truly gone, its next upload answers 404 and the note says `ERROR`. The "pruned subtree looks
    deleted" bug class cannot occur;
  - **the cursor moves only when every change was dealt with** (a failure, a deferral, an editor holding a
    note, or the pass limit keeps it, and the changes come back — they are idempotent);
  - **a cursor, `nextLink` or `deltaLink` that is not on the account's own API host is refused**, so a
    response cannot steer the bearer token elsewhere;
  - **a 401 opens the same persisted cooldown** the walk uses, and a halted account is not asked again;
  - unknown PDFs are adopted only inside the walk roots; a known note is followed anywhere.
  Migration `11.sqm` adds `syncCursor.feedCursor` (and `upsertCursor` names it — `INSERT OR REPLACE`).
  `OneDriveTwoDevicesTest` runs **two production tablets over the stand-in** (real `NoteStore`, PDF
  publisher, extractor, merge gate, drainer, reconciler): a note written on one arrives on the other as a
  real PDF; idle polls cost one feed call and nothing else; concurrent offline writes converge with both
  strokes; a named deletion trashes the note with its unsynced edit restorable; an edit behind a tablet's
  back is a conflict that overwrites nothing; a refused grant halts one tablet and sends nothing more.
  Each safety rule above was checked red: nine mutations (a deletion trusted in any batch; the cursor always
  advancing; adoption anywhere; no 401 cooldown; deletions applied before files; a `nextLink` on another host
  followed; a `410` not treated as a resync; deletions not reported; paging stopping early) each failed at
  least one test. One mutation *survived* — a "binding was re-pointed" check on the deletion path — because
  the check was dead code (`findByOcId` already guarantees it); it was removed, and the ordering that really
  protects a re-created file is what the last mutation now covers.
  **Limits, none of them fixed:**
  - a first enumeration is the **whole drive** (`root/delta`) — on a real library that is large; scoping
    it (the App Folder, or a `children` walk of one folder plus `?token=latest`) is open (M-M3);
  - a **folder rename** reports no descendants, so a binding under it keeps a stale *path* (writes use the
    item id and are unaffected, but a later local rename would re-create the old folder name and move the
    file into it);
  - a **rename costs one download** (Graph's eTag moves on any metadata change; the merge then finds
    nothing new and uploads nothing);
  - the feed is **unverified against Graph**: that parents repeat, that delta items have no path, what
    `410` carries. M-M7 in the spike is that check, and it has not run.

- **P4 — sign-in, key storage, the Sync-screen button, and an emulator walk** (peer message relaying the
  user's "get this working end to end"). *The spike still has not run, so what follows is proven against a
  stand-in only.*
  - `MicrosoftSignIn` (`:core:sync`, pure protocol, no SDK): authorization code + **PKCE S256** with a
    32-byte verifier from a platform `SecureRandom`, a `state`, and a **pending record written before the
    URL is returned** and **taken (read and deleted) by the redirect**, so a redirect delivered twice or
    forged finds nothing. `state` is checked before the code is looked at; a redirect not starting with
    ours is refused; a 15-minute-old record is refused; `access_denied` is a *cancel*, not an error.
    **The refresh token is saved before the access token is used — including by this class's own `/me` and
    `/me/drive` calls** — and if Microsoft will not say who the user is the token is removed again, so no
    orphan is left. Nothing here retries: an authorization code is single-use, so a failed exchange is a
    failed sign-in and the user presses the button again.
  - `:app`: `SealedFiles` (the same AES-GCM envelope as `KeystoreCredentialStore`, key as a parameter),
    `KeystoreTokenVault` (alias `inkstone.oauth.v1`, its own key), `KeystorePendingSignInStore`,
    `AndroidPkceCrypto`; `MicrosoftSettings` (client id from the `inkstone.msClientId` Gradle property, and
    the redirect `msauth://dev.inkstone/<URL-encoded base64 SHA-1 of the signing certificate>` **computed at
    run time and shown on the Sync screen**, so the user registers exactly what the installed APK will send);
    `OAuthRedirectActivity` (exported, `msauth://${applicationId}` only; hands the URL to a singleton scope
    because the process may have been restarted while the user was on Microsoft's page);
    `OneDriveAccounts` (begin / complete / sign out / forget / browse / fetch / create folder, and
    `repairAndRefresh`). `NextcloudAccounts` delegates to it for every account whose provider is OneDrive and
    is otherwise unchanged. `ProviderRoutingFactory` is now what the drainer and reconciler are given.
  - **Lost token.** An ACTIVE OneDrive account whose token this device cannot open (a deleted or
    undecryptable file — what an invalidated Keystore key looks like to the vault) is flagged `NEEDS_REAUTH`
    with its own message **without any request**: nobody refused anything. A 401 while browsing goes through
    the same single permitted `confirmStillAuthorised` as everywhere else and halts the account; a halted
    account is not asked again. Signing in again releases the in-process `invalid_grant` latch
    (`OneDriveStoreFactory.forget`), which is per object and otherwise outlives the sign-in.
  - **Identity.** Re-signing-in to an account refuses a different Microsoft identity (`driveId` must match)
    and leaves nothing behind; signing in again as the same person finds the existing account rather than
    making a second, which would orphan every bound note. *Sign-out* forgets the token on the device and
    **does not claim to revoke it** — Microsoft has no such call for a public client — and says where to.
  - **Tests, with what each proves.** `MicrosoftSignInTest` (10, over `GraphStandIn`'s new authorize page,
    code grant and `/me`: the RFC 7636 vector, state mismatch, single use, cancel, stale, foreign redirect,
    a verifier that does not match, no identity → no token); `OneDriveAccountsTest` (12: production
    `MicrosoftSignIn`, vault, pending store, SQL stores and transport — one account per person, resume,
    identity refusal, lost token with **zero requests**, 401 halts once, latch release, an unconfigured
    build offers nothing); `OAuthStoresTest` (9: envelope, tamper, wrong key, traversal, single-use pending);
    `MicrosoftRedirectTest` (2, SHA-1 and base64url vectors). Mutation checks, each *red*: state check off,
    token saved after identity, redirect prefix unchecked, expiry off, orphan kept, latch not released,
    identity check off, missing-token repair off, 401 halt off, duplicate-redirect dedupe off.
  - **Driven on the emulator** (`Test_Tablet`, debug APK built with `-Pinkstone.msClientId=standin-client
    -Pinkstone.msStandIn=http://10.0.2.2:8099`, `GraphStandInHost` serving the stand-in on the host): the
    Sync screen's OneDrive button opened a **Chrome Custom Tab**; pressing *Continue* on the stand-in page
    sent `msauth://…` back to `OAuthRedirectActivity`; the app exchanged the code, called `/me` and
    `/me/drive`, wrote `account` (`ONEDRIVE`, `ACTIVE`, drive id in `uid`) and one sealed file named after
    that account id; *Browse folders* listed the stand-in's folders (the refresh token rotated `rt-2 → rt-3`
    as designed); *Sign out* removed the file and kept the row; *Sign in again* resumed the **same** account;
    a corrupted token file gave `NEEDS_REAUTH` and the "no longer holds the saved sign-in" card with **no
    request logged**; *Remove* deleted the row and left `files/oauth` empty. The `provider` and
    `providerData` columns were present on an install that had been upgraded in place (migration 10 ran on a
    real device; the instrumented test written for it has still **not** run — see below).
    The displayed redirect hash was checked against `keytool`'s SHA-1 of the debug key.
  - **Debug-only plumbing**: a `src/debug` network-security overlay allowing cleartext to `10.0.2.2` only,
    and `BuildConfig.MS_STAND_IN`, which the release block pins to the empty string. Neither exists in a
    release APK.
  - **Not proven, in order of how much it matters:**
    1. **Microsoft accepting any of it.** The authorize URL's parameters, `response_mode=query`, the
       `msauth://` redirect for a hand-rolled (non-MSAL) flow, `prompt=select_account`, the scopes, and the
       token response carrying a refresh token — all from documentation. The user's real sign-in is the
       test, and the first thing to look at if it fails is the redirect URI string against the registration.
    2. **The Android Keystore on a real tablet.** The envelope is tested with a plain key; the emulator
       exercised the real Keystore, but a hardware-backed key on the Galaxy Tab has not been used.
    3. **Sign-in is the only OneDrive path driven through the UI.** Upload, pull and the two-tablet cases
       are tested on the stand-in in `:app`'s JVM suite and have not been driven on a device.
    4. **A forged `msauth://` intent** can consume the pending record (the user presses the button again).
       It cannot sign anyone in — `state` and the verifier stand in the way — but it is a nuisance.
    5. The on-device schema-migration test (`MigrationOnDeviceTest`) was written and **has not been run**:
       `connectedAndroidTest` uninstalls the app, and `Test_Tablet` holds the user's physics-note fixture.
  - **Merge hazard with the unmerged Nextcloud sign-out fix** (`claude/nextcloud-signout-cloud-integration-5e9675`):
    that branch flags any account with no *Nextcloud* credential as `NEEDS_REAUTH`, which would hit every
    OneDrive account unless it skips `provider != NEXTCLOUD`. It edits the same lines of `SyncScreen`
    (the `NEEDS_REAUTH` button — made identical here, on purpose), `NextcloudAccounts`, `SyncPorts`,
    `OutboxDrainer` and `Reconciler`. Merge them together, not one after the other.

## Original design (2026-10-17)

**Status: design. Nothing in the sections below was built unless Progress above says so. Not in SPEC.md, ROADMAP.md or SPEC §12 — this is
new scope the user asked for.** Written from the official docs and the code; **no provider call was
made** (no account, and the app never sees a password). Every API claim below cites the page it was
read from; §10 lists what was *not* read or measured, and §9 lists the measurements that must come
before any code depends on them (CLAUDE.md rule 3: report, don't invent).

## 0. The answer in one screen

| Question | Answer |
|---|---|
| Can both providers be added? | Yes. Both are plain REST/JSON, so they live in `:core:sync` on Ktor alone — no Google or Microsoft SDK in core, iOS link unaffected. |
| **Safe conditional write — OneDrive** | **Yes, documented.** `createUploadSession` accepts `If-Match: <eTag>` and answers **412** on mismatch. Rule 1 can be kept literally. |
| **Safe conditional write — Google Drive** | **No.** The v3 `File` resource has no `etag`, the upload and update docs mention no precondition, and a third party reports `If-Match` on a content `PATCH` is silently ignored. **This is a blocker for rule 1 as written** (§3.2). I describe the best mitigation and recommend the user decide before any Drive code. |
| Build first | **OneDrive.** It is the only one that meets rule 1 today, its change feed (`delta`) reports moves and deletes explicitly, and it needs no restricted scope. |
| Auth | OAuth 2.0 authorization-code + **PKCE** in a Custom Tab, for Microsoft done by hand over Ktor. Google is different: its Android guidance is Play Services' `AuthorizationClient` (no refresh token on the device) — §5.2. |
| Schema | One additive migration (`10.sqm`): a `provider` column defaulting to `'NEXTCLOUD'`, plus a cursor table. Existing rows are untouched. |
| Biggest risk | Not the providers — the **refactor**: `OutboxDrainer` (751 lines) and `Reconciler` (1,589) take a `NextcloudSession` directly. The call surface is small (§1), but it is the part that can regress Nextcloud, so it goes first, alone, with zero behaviour change. |

**Decisions needed from the user before building** — D1 to D4 in §11.

## 1. What is Nextcloud-shaped today

Read from the code, not assumed. `:core:sync` talks to the server through one class, `WebDavClient`
(`core/sync/…/WebDavClient.kt`), reached only via `NextcloudSession.dav`. The call sites are few:

| Caller | Calls | Nextcloud assumption baked in |
|---|---|---|
| `OutboxDrainer.perform` (`:262`) | `mkcolRecursive`, `put`, `move`, `delete` | **path** addressing; `If-Match`/`If-None-Match: *`; `MOVE Overwrite: F`; `OC-Checksum` MD5 (`Md5.kt`); DELETE lands in the server trash |
| `Reconciler` (`:276`, `:389`, `:881`, `:1049`) | `propfind Depth:0/1`, `download`, `searchByFileId` | **directory-ETag pruning** (§7.4 rule 1); `oc:id`; "absent from a settled listing ⇒ deleted"; `SEARCH` to find a moved file |
| `NextcloudAccounts` (app) | `propfind`, `download`, `mkcolRecursive`, Login Flow v2, OCS | folder browser; app password; uid |
| Storage | `SyncAccount{serverUrl, loginName, uid}`, `NextcloudCredential`, `remoteBinding.remoteOcId`, `lastSyncedEtag` | all three account columns and the credential type are Nextcloud words |

Things that are **already provider-neutral** and must stay so: the outbox (op *types* and
coalescing — rows are markers, rule 4), `local_rev` and the mid-flight guard (§7.6), the
three-row pull table (§7.6), the layer 1–3 merge (§7.7), `DavFailure` as a sealed outcome type, the
30-second upload floor, the `refusedUntil` cooldown column.

## 2. Where the seam goes

The seam is **not** "WebDAV verbs behind an interface" — Drive has no `MKCOL` and Graph has no
`PROPFIND`, and a verb-shaped interface would make both providers pretend to be WebDAV. It sits at the
level the drainer and the reconciler actually reason at: *identified files, opaque versions,
conditional writes, a way to learn what changed*.

```kotlin
// :core:sync commonMain — Ktor only; no provider types cross this line.
interface CloudStore {
    val kind: ProviderKind                       // NEXTCLOUD | ONEDRIVE | GOOGLE_DRIVE
    val traits: StoreTraits                      // see below

    // Addressing is by RemoteId. A path is metadata, never the key (Drive allows two files of
    // one name in one folder, so `pathsOn` uniqueness (§7.6) cannot be assumed off Nextcloud).
    suspend fun ensureFolder(path: String): CloudOutcome<RemoteId>
    suspend fun list(folder: RemoteId?): CloudOutcome<List<RemoteEntry>>        // the browser
    suspend fun download(id: RemoteId): CloudOutcome<Downloaded>                // bytes + RemoteVersion + id

    // The three writes each state their precondition in the type — no "optional ETag" parameter.
    suspend fun create(parent: RemoteId, name: String, bytes: Source): CloudOutcome<Receipt>   // fails if exists
    suspend fun replace(id: RemoteId, ifVersion: RemoteVersion, bytes: Source): CloudOutcome<Receipt>
    suspend fun move(id: RemoteId, ifVersion: RemoteVersion, toParent: RemoteId, name: String): CloudOutcome<Receipt>
    suspend fun trash(id: RemoteId, ifVersion: RemoteVersion?): CloudOutcome<Unit>   // soft only — see below

    // Pull. A provider answers with one of the two shapes; the Reconciler handles both.
    suspend fun changes(since: ChangeCursor?): CloudOutcome<ChangeBatch>
}
```

- **There is no `delete`.** The only removal is `trash`. CLAUDE.md rule 1 (*no hard deletes*) becomes a
  property of the type: Drive's `files.delete` and Graph's permanent delete simply have no method.
- **`RemoteVersion` is opaque.** Nextcloud/Graph: the ETag (kept as the existing `ETag` with its `weak`
  flag, so `asIfMatch` still returns null for a weak one). Drive: `version` + `headRevisionId` +
  `md5Checksum` (§3.2). It is stored in the existing `lastSyncedEtag` column.
- **`RemoteId` is opaque.** Nextcloud `oc:id`, Graph `driveItem.id`, Drive `fileId`. Stored in the
  existing `remoteBinding.remoteOcId` column — **the column keeps its name** (renaming it is a
  migration that buys nothing); `RemoteBinding.remoteFileId` (leading digits of an `oc:id`, used only
  for Nextcloud's `SEARCH`) must be guarded by provider.
- **`StoreTraits`** is how a store says what it cannot do, so the engine never discovers it by failing:
  `conditionalReplace: ENFORCED | DETECTED_AFTER_THE_FACT`, `changeFeed: WALK | FEED`,
  `supportsMedia: Boolean`, `supportsAdoptExisting: Boolean`, `idsSurviveMove: Boolean`.
  Anything unsupported is **reported to the user** (Needs-attention row), never silently skipped — the
  M12 lesson that a missing capability must not look like a successful sync.

### 2.1 Pull: two shapes behind one reconciler

`ChangeBatch` is either **`Listing`** (Nextcloud: the walked directories, today's behaviour) or
**`Delta`** (items added/modified/moved/deleted, plus a new `ChangeCursor`, or `ResyncRequired`).

- *Listing* keeps §7.6 step 3 exactly, including the "settled directory" guards — that logic is the
  hard-won part and **does not move**.
- *Delta* is simpler in the way that matters: **deletions and moves are explicit events**, so the whole
  class of bugs where an unlisted file looks deleted (§7.4 rule 2's M7 note, the "pruned subtree" entry
  in CLAUDE.md) cannot occur, and Nextcloud's `SEARCH` workaround has no counterpart. A
  `ResyncRequired` (Graph `410 Gone`, Drive's expired page token) does one full enumeration and then
  applies the *same* walked-and-settled absence rules as the Listing path — never "absent ⇒ trash"
  from a partial list.

Both feed the unchanged parts: the three-row outbox table, `NoteMerge`, layer 3, the attention list.

### 2.2 What stays per provider

| In `:core:sync` (common, Ktor) | In `:app` (Android) |
|---|---|
| `OneDriveStore`, `GoogleDriveStore`, their JSON/URL builders, their `DavFailure`-style mapping | Launching the Custom Tab / `AuthorizationClient`, the redirect `Activity`, the pending-auth file |
| Token endpoint calls (Microsoft refresh/exchange are plain HTTPS) | Keystore-backed `CredentialStore` (existing, SPEC §14) |
| `TokenSource` interface (`suspend fun bearer(): Token`) | `TokenSource` implementations that need Play Services |

Third-party types stay out of core (CLAUDE.md): no `msal`, no `google-api-client`, no Drive/Graph SDK —
they are JVM-only and would fail the iOS link exactly as OkHttp would. Hand-written JSON over Ktor.

### 2.3 Refactor order (this is the risky part)

1. Introduce `CloudStore` and `NextcloudStore` (a thin wrapper over the existing `WebDavClient`).
2. Make `OutboxDrainer` and `Reconciler` depend on `CloudStore`, not `NextcloudSession`.
3. **All existing tests pass unchanged** — the 21-row matrix (`app/src/test/…/matrix`) is the safety
   net, and it runs on the real HTTP stack. That commit changes no behaviour and ships alone.

Only then does a second provider appear.

## 3. The concurrency primitive

Rule 1: *never overwrite a remote file without a precondition; a 412 is a conflict, not a retry.*

### 3.1 OneDrive / Microsoft Graph — enforced

- **`createUploadSession` takes `If-Match`.** The page lists `if-match: etag` — *"If this request
  header is included and the eTag (or cTag) provided doesn't match the current etag on the item, a 412
  Precondition Failed error response is returned."* — and `if-none-match`, and a body field
  `"@microsoft.graph.conflictBehavior": "fail (default) | replace | rename"`.
  <https://learn.microsoft.com/en-us/graph/api/driveitem-createuploadsession?view=graph-rest-1.0>
- **Measured by a third party (not by us):** an OneDrive client author's log shows
  `createUploadSession` with a stale eTag returning `412 resourceModified — "ETag does not match
  current item's value"`, and a Microsoft engineer's reply that **a 412 is also returned when the target
  is not found but a precondition was given** (so update by **item id**, never by path, or a missing file
  reads as a conflict). <https://github.com/OneDrive/onedrive-api-docs/issues/1716>
- **Create = `conflictBehavior: fail`** (the documented default) — the analogue of `If-None-Match: *`.
  The simple `PUT …/content` page documents **no** `If-Match` and limits it to 250 MB
  (<https://learn.microsoft.com/en-us/graph/api/driveitem-put-content?view=graph-rest-1.0>), so
  **every update goes through an upload session**, whatever the size. Fragments must be multiples of
  320 KiB (327,680 B), 5–10 MiB recommended, each request under 60 MiB (createUploadSession page).
- **Delete** moves to the recycle bin (*"moves the items to the recycle bin instead of permanently
  deleting"*) and accepts `If-Match` → 412. <https://learn.microsoft.com/en-us/graph/api/driveitem-delete?view=graph-rest-1.0>
  Rule 1's soft delete holds.
- **Move/rename** is `PATCH /drives/{d}/items/{id}` with a new `parentReference` and/or `name`; the
  page documents an `if-match` header. The id is the stable key.
  <https://learn.microsoft.com/en-us/graph/api/driveitem-move?view=graph-rest-1.0>
  *(The page words the header as "the current eTag on the folder"; whether the precondition is checked
  against the item or the folder is one of the §9 measurements.)*
- **One caveat that decides how conflicts feel:** the eTag moves on *metadata* changes too (the docs
  distinguish `eTag` from `cTag`, the content tag). A metadata-only touch by OneDrive itself therefore
  produces a spurious 412. That is the *safe* direction: it costs one reconcile that finds identical
  content and takes §7.6's "no novel ops either side ⇒ advance the ETag, no upload" row. It cannot lose
  bytes. It is on the §9 list as something to measure, because it sets how often it happens.

**Verdict: rule 1 holds literally.**

### 3.2 Google Drive v3 — not enforced (**blocker, reported not papered over**)

- The `File` resource has **no `etag` field** (zero occurrences of "etag" on the reference page). What it
  has: `version` (*"A monotonically increasing version number for the file. This reflects every change
  made to the file on the server, even those not visible to the user"*), `headRevisionId`,
  `md5Checksum`, `modifiedTime`, `trashed`, `appProperties`.
  <https://developers.google.com/workspace/drive/api/reference/rest/v3/files>
- The upload guide (simple/multipart ≤ 5 MB, resumable for larger) contains no `ETag`, `If-Match` or
  precondition at all. <https://developers.google.com/workspace/drive/api/guides/manage-uploads>
- A developer building exactly this sync engine reported in January 2026 that `If-Match` on a content
  `PATCH` (and on `DELETE`) is accepted and **ignored** — no 412 — and that Drive returns no `ETag`
  header to feed it; the one answer says there is **no server-enforced way** to abort an update if the file
  changed, and that the best available is checking revisions *after the fact*.
  <https://stackoverflow.com/questions/79865579/is-raceless-optimistic-concurrency-possible-in-google-drive-v3-interface>
  *This is a community report, not Google's statement, and I did not reproduce it — §9 M-G1.*

**So `replace(id, ifVersion, …)` cannot be enforced on Drive.** The strongest honest substitute, if the
user wants Drive at all:

1. *Check, then write:* read `version`, `headRevisionId`, `md5Checksum`; if they differ from what the
   binding last saw, do **not** upload — hand it to the merge. This narrows the race to the length of
   one upload; it does not close it.
2. *Keep the loser:* upload with the new head revision pinned (`keepForever`), then **verify after the
   fact** that the revision immediately before ours is the one we based the write on. If not, someone
   wrote in between; their revision still exists, is downloadable once pinned, and goes into the §7.7
   merge as the "remote" side → merge or conflicted copy.
   Retention, from the docs: *"Purgeable revisions are typically preserved for 30 days"*, *"The head
   revision is never auto-purged"*, up to 200 revisions may be kept forever and *"count towards your
   storage limit"*; and *"you can only download blob file content revisions marked as 'Keep Forever'"*.
   <https://developers.google.com/workspace/drive/api/guides/manage-revisions>
3. *Make the check survive a kill:* the outbox row gets a **`verify pending`** state (a marker, not a
   payload — rule 4) so a process death between the PUT and the post-check still ends in a check.

This satisfies rule 1's **intent** (no bytes discarded; every overwrite detectable and recoverable) but
**not its wording** (no precondition; a window exists in which a concurrent writer's bytes are no longer
the head). It costs extra calls, extra storage and a `verify pending` state in a schema that does not
have one. **I do not recommend building it on my own authority** — it changes what rule 1 means. D2.

## 4. Change feeds, moves, trash and identity

| | Nextcloud (today) | OneDrive / Graph | Google Drive |
|---|---|---|---|
| Learn what changed | root `PROPFIND Depth:0`, descend where the directory ETag moved | `GET …/delta` → pages (`@odata.nextLink`) → `@odata.deltaLink`; `?token=latest` skips enumeration | `changes.getStartPageToken`, then `changes.list(pageToken)` → `nextPageToken` / `newStartPageToken` |
| Deleted | inferred: known id absent from a settled listing | **explicit**: item carries the `deleted` facet | explicit change entries (`includeRemoved`); `trashed` is a separate field on the file |
| Moved/renamed | same `oc:id`+ETag at a new href; `SEARCH` if the new folder was never walked | same item **id** with a new `parentReference`/`name`; *"if an item were renamed twice, it would only show up once, with its latest name"*; *"you should always track items by id"* | same `fileId`, new `parents`/`name` (`files.update` `addParents`/`removeParents`) |
| Resync | periodic unpruned walk (24 h) | `410 Gone` with a `Location` for a fresh enumeration; error `resyncChangesApplyDifferences` / `resyncChangesUploadDifferences` say how to reconcile | expired/invalid page token (not read this session) |
| Push | `notify_push` (rare) | n/a — poll | `changes.watch` — *"Notifications don't contain details about the changes"*; webhook needs a server, so **poll** |
| Trash on remove | server trash | recycle bin | set `trashed` via `files.update`; **never** `files.delete` (permanent) |

Sources: Graph delta <https://learn.microsoft.com/en-us/graph/api/driveitem-delta?view=graph-rest-1.0>;
Drive changes <https://developers.google.com/workspace/drive/api/guides/manage-changes>;
`files.update` parameters <https://developers.google.com/workspace/drive/api/reference/rest/v3/files/update>.

Consequences worth stating:

- **The §7.4 rule 2 / M7 `SEARCH` machinery has no counterpart** on either provider; a move is a delta
  entry for an id we already hold. SPEC §13.2 row 11b ("moved outside every walk root") becomes trivially
  true and should still be run.
- **Row 15 (backup restore) maps to `410 Gone`/resync**, and row 16 (weak ETag) does not apply — the
  matrix is parameterised per provider, with the non-applicable rows named and skipped *loudly*, as the
  live-Nextcloud half already does.
- **Delta is drive-wide.** Every Graph `delta` URL in the docs is rooted at a drive (`/me/drive/root/delta`).
  On a personal drive the first enumeration lists *everything the user owns* — easily 100k items — so the
  design **scopes** the app: the **App Folder** (`Files.ReadWrite.AppFolder`, `/Apps/<app>`) if it can
  be delta-scoped (§9 M-M3), otherwise a one-time `children` walk of our folder followed by
  `?token=latest`. The delta page itself warns that paging `children` is not guaranteed complete under
  concurrent writes, which is acceptable only because the periodic full walk (§7.6) backstops it.
- **No folder ETag propagation exists** on either, so §7.4 rule 1's "one request when idle" becomes "one
  `delta` call when idle" — equally cheap, and the cursor replaces `syncCursor.rootEtag`.
- **Drive file names are not unique in a folder.** `BindingStore.pathsOn` (a second note cannot take a
  path) and §7.8's sanitising assumed a server that rejects duplicates. On Drive the binding is by
  `fileId`, and a duplicate-name file is legal, so "name collision" stops being an error to prevent and
  becomes a rename the engine must make itself.

## 5. Auth

CLAUDE.md rule 5 and SPEC §7.2 are written for Nextcloud's brute-force protection. The analogue here is
not the same threat (Google and Microsoft do not lock an IP out for a bad refresh), but the **principle**
— a dead credential halts the account, nothing loops on it — carries over, and the refresh token adds
one subtlety the app password never had.

### 5.1 Microsoft (OneDrive) — Custom Tab + PKCE, done by hand

- Authorization-code flow with PKCE `S256` against
  `https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize`; *"Public clients, which include
  native applications and single page apps, must not use secrets or certificates when redeeming an
  authorization code."* <https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow>
  Tenant `common` so personal Microsoft accounts and work/school accounts both sign in.
- Scopes: **`Files.ReadWrite`** (*"read, create, update and delete the signed-in user's files"*, admin
  consent **not** required) or **`Files.ReadWrite.AppFolder`** (app's own folder only), plus
  **`offline_access`** to get a refresh token. <https://learn.microsoft.com/en-us/graph/permissions-reference>
  *(The `.AppFolder` and `offline_access` rows were not read — §10.)*
- **Refresh tokens last 90 days and replace themselves on every use** (*"Refresh tokens replace
  themselves with a fresh token upon every use"*), and can be revoked at any time (change of credentials,
  user or admin action). <https://learn.microsoft.com/en-us/entra/identity-platform/refresh-tokens>

**The rotation is the subtlety.** A new refresh token arrives *with* each new access token. If the
process dies after the response and before the new token is written, the old one may already be spent.
So: **persist the new token before using the access token** (the same rule as Login Flow v2's *"persist
before acknowledging"*, §7.1 rule 3), under a per-account single-flight mutex, and treat a lost
rotation as `NEEDS_REAUTH` — never as a reason to try the old token again.

### 5.2 Google — different on Android, and the user should know

Google's Android guidance is **`AuthorizationClient` from Play Services**, and it says to *strongly
discourage* keeping refresh tokens on the device; a refresh token needs an auth code exchanged on a
server. <https://developers.google.com/identity/sign-in/android/authorize-access> Its token page also
notes *"Custom URI scheme is not supported on Android or Chrome apps"* for the Android client type
<https://developers.google.com/identity/protocols/oauth2/native-app>, which rules out the obvious
Custom-Tab-with-custom-scheme route for an Android-type client ID (loopback `http://127.0.0.1:port` is
the desktop mechanism; using it from a phone is off-guidance and I would not build on it unasked).

So Drive takes **`AuthorizationClient`**: it hands the app short-lived access tokens, silently while the
grant stands (`hasResolution()` false), and needs UI only when the grant is gone. That is actually
*simpler* for rule 5 — **no refresh token is stored at all** — but it adds a Play Services dependency
(fine on a Galaxy Tab; not on iOS, which would use `ASWebAuthenticationSession` later) and means a
background `WorkManager` job can obtain a token only while the grant holds. `TokenSource` (§2.2) is the
seam: core asks for a bearer, `:app` decides how.

### 5.3 When one refresh is allowed — the exact rule

A refresh is a request to the **token endpoint**, not to the resource server, so it is not "retrying a
credential against the thing that rejected it". It is still bounded:

| Situation | Action |
|---|---|
| No cached access token, or it expires within 5 min (`expires_in` is on the response) | **One refresh, before sending.** Not a retry. |
| A request returns **401** with an access token our clock says is still valid | **Do not replay it.** Halt this account's drain, and allow **at most one** refresh, guarded by a persisted cooldown (the existing `syncCursor.refusedUntil` pattern — a cooldown cleared by every process restart is not a cooldown). The next pass uses the result. |
| Refresh returns **`invalid_grant`** | Account → `NEEDS_REAUTH` immediately. **No retry**, ever. |
| Refresh cannot be *reached* (network, 5xx) | Conclude **nothing**; account stays ACTIVE; back off. (Same as `confirmStillAuthorised() == null`: unreachable ≠ revoked, or the user is signed out every time they enter a lift.) |
| 429 / `Retry-After` | Honour it (Graph: *"Backing off requests using the Retry-After delay is the fastest way to recover from throttling"* — <https://learn.microsoft.com/en-us/graph/throttling>). |
| A worker that halted on a dead credential | Returns **`success`, not `retry`** — WorkManager backoff must not become the loop (CLAUDE.md rule 5, corollary). |

**Re-sign-in must prove it is the same account.** The Nextcloud flow re-binds to the same server+user.
A refresh token for a *different* Microsoft/Google user would make every bound file a 404, which the
reconciler reads as "deleted remotely" → local trash for the whole library. So re-auth checks the
identity (Graph `/me` id / Drive `about.user.permissionId`) against the stored one and **refuses** a
mismatch, offering "Add as a new account" instead. This is the same family as CLAUDE.md's
"absent ≠ deleted".

### 5.4 Storage

Existing Keystore AES-GCM `CredentialStore` (SPEC §14; **not** `EncryptedSharedPreferences`). Today its
API is `NextcloudCredential`-typed (`core/model/…/Platform.kt:213`). It gains a provider-tagged secret —
`ProviderSecret(kind, refreshToken?, …)` — stored in the same file format with a type tag; the
Nextcloud entries read exactly as before. The PKCE verifier and `state` for an in-flight sign-in are
written to a pending-auth file **before** the Custom Tab opens, because the tab can outlive the process
(the Login Flow v2 persist-before-open lesson again). Access tokens are memory-only.

## 6. What the user must register — and what it implies

### 6.1 Microsoft (needed for OneDrive) — free, no review for personal accounts

1. Sign in at the Microsoft Entra admin center → **App registrations → New registration**.
   <https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-register-app> *(page opened; the
   exact portal labels were not extracted — check them against the live portal)*
2. **Supported account types:** *Accounts in any organizational directory and personal Microsoft
   accounts*. (Single-tenant would exclude the user's personal OneDrive.)
3. **Authentication → Add a platform → Mobile and desktop applications**, and add a redirect URI of the
   form `dev.inkstone:/oauth2redirect/microsoft` (I will pick the final string; it must match the
   Android intent-filter byte for byte). **Do not create a client secret** — a public client must not use one.
4. **API permissions → Microsoft Graph → Delegated:** `Files.ReadWrite`, `offline_access`
   (and `User.Read` only if the account name/id is wanted — it is, for §5.3's identity check; `openid
   profile` would also work). No admin consent is required for `Files.ReadWrite`.
5. Copy the **Application (client) ID** to me. It is not a secret.

*Commercial note (unverified here):* work/school tenants can restrict consent to apps with a verified
publisher; a personal-only audience is unaffected. Verification needs a Microsoft Partner Network
account. Decide when the audience decision is made.

### 6.2 Google (needed only if D2 says yes)

1. <https://console.cloud.google.com> → create a project → **APIs & Services → Library → Google Drive API
   → Enable**.
2. **OAuth consent screen** (Google Auth Platform → Branding/Audience): app name, support email,
   **User type: External**.
3. **Data access → add scope.** The scopes and their classes, from
   <https://developers.google.com/workspace/drive/api/guides/api-specific-auth>:
   `drive.file` (*"Create new Drive files, or modify existing files, that you open with an app or that the
   user shares with an app while using the Google Picker API or the app's file picker"*) and
   `drive.appdata` are the **non-sensitive** tier; `drive` and `drive.readonly` are **restricted**.
   *(The tier labels come from the page's table headings; confirm in the console.)*
4. **Credentials → Create OAuth client ID → Android**, with package `dev.inkstone` and the **SHA-1 of the
   signing key** (the debug-key-signed release the tablet runs today, and the release key later — these
   are two different client IDs or two fingerprints).
5. **Publishing status: set it to "In production".** In *Testing* with an external user type Google
   issues *"a refresh token expiring in 7 days"* unless the scopes are only name/email/profile
   (<https://developers.google.com/identity/protocols/oauth2>). `AuthorizationClient` does not store a
   refresh token on the device, but whether the 7-day clock still ends the grant is **not** documented
   on the pages I read — §9 M-G3.

**Which scope, and what it costs** — this is the real fork, because of D3 (adopting existing PDFs):

| Scope | Sees | Verification | Fits Inkstone |
|---|---|---|---|
| `drive.file` | only files the app created, or the user picked with Google's Picker/the app's picker | non-sensitive; the verification page says it is not required while testing and required for sensitive/restricted scopes (<https://developers.google.com/identity/protocols/oauth2/production-readiness/sensitive-scope-verification>) | ✅ notes the app creates. ❌ **cannot list or adopt a PDF the user already has** — there is no native Picker on Android, so the "write on a PDF that lives on my Drive" feature (`docs/REMOTE-PDF.md`) is unavailable |
| `drive.appdata` | a hidden app-private folder | non-sensitive | ✅ safest, ❌ the files are invisible to the user and to their other devices' Drive UI — defeats "ordinary PDFs in *my* storage" |
| `drive` | everything | **restricted** → full verification and, I believe, a third-party security assessment for apps that touch the data from a server — **not read this session; do not assume** | ✅ everything works; ❌ heavy for a commercial app, and the strongest scope on the user's whole Drive |

**Recommendation for Drive: `drive.file`**, accepting that adoption of pre-existing Drive PDFs is not
offered on that provider. OneDrive's `Files.ReadWrite` has no such limit (and no verification).

## 7. Account model and the migration

`SyncAccount{id, serverUrl, loginName, uid, …}` and the `account` table are Nextcloud words, but only
three columns are. Do not rename them (every query and 9 `.sqm` files assume them) — **add**, like
`5.sqm` and `8.sqm` did:

```sql
-- 10.sqm — additive; the DEFAULT is what every existing row already is.
ALTER TABLE account ADD COLUMN provider     TEXT NOT NULL DEFAULT 'NEXTCLOUD';
ALTER TABLE account ADD COLUMN providerData TEXT;            -- JSON: driveId, approot id, display name…
CREATE TABLE changeCursor (                                  -- NOT syncCursor.rootEtag: different meaning
    accountId   TEXT NOT NULL PRIMARY KEY,
    cursor      TEXT,                                        -- Graph deltaLink / Drive page token
    lastFullAt  INTEGER NOT NULL DEFAULT 0
);
```

Column mapping for a non-Nextcloud account (documented in `Sync.sq` beside the columns, because a column
that means two things is this repo's oldest bug):

| Column | Nextcloud | OneDrive | Google Drive |
|---|---|---|---|
| `serverUrl` | server | `https://graph.microsoft.com/v1.0` | `https://www.googleapis.com/drive/v3` |
| `loginName` | login | account email / UPN | account email |
| `uid` | WebDAV path uid | **drive id** (resolved by `/me/drive`; empty ⇒ `NEEDS_UID`, same gate) | `permissionId` (identity check, §5.3) |

`SyncAccount.isUsable` keeps requiring `state == ACTIVE && uid.isNotEmpty()`, so the existing
"cannot be constructed without a uid" guard has a provider-neutral equivalent for free.

- `AccountState` is reused unchanged (`ACTIVE`, `NEEDS_REAUTH`, `NEEDS_UID`, `SIGNED_OUT`). The
  just-landed sign-out fix (ACTIVE account whose credential is missing ⇒ `NEEDS_REAUTH` with
  `MISSING_CREDENTIAL_REASON`) applies to every provider with no change — it reads the credential
  store, not the provider.
- **Tests (CLAUDE.md "required"):** a committed schema-9 fixture holding an account, a binding and an
  outbox row; an **on-device** migration test (`MigrationOnDeviceTest` pattern) that asserts the rows
  survive *and* `provider = 'NEXTCLOUD'`; `verifyMigrations` alone is not enough (it compares DDL only).
- `BindingStore.findByOcId` is global ("`oc:id` is globally unique", §7.3). That is true of Nextcloud
  and **false across providers** — a Drive `fileId` and a Graph `id` can collide in principle — so the
  lookup gains an `accountId` argument. This is a place the provider seam changes an existing
  invariant, and it needs its own test.

## 8. UI

`SyncScreen` lists accounts; adding a provider changes three things only.

1. **Add account** opens a chooser (*Nextcloud · OneDrive · Google Drive*). Nextcloud keeps its URL
   field; the other two have no field — one button that opens the Custom Tab / Play Services sheet.
   The app never sees the password (SPEC §7.1) on any of them.
2. **Per-provider row** shows provider icon, account name, state. `NEEDS_REAUTH` now offers **Sign in
   again** (the just-landed fix) and that button must route to *that account's provider*, with the
   identity check of §5.3; routing a OneDrive account to the Nextcloud URL screen is the obvious
   regression and gets a test.
3. **Capability notices, never silent skips:** a recording on a provider with `supportsMedia = false`
   shows a Needs-attention row; adoption of an existing PDF is hidden where `supportsAdoptExisting =
   false`, with the reason shown.

No modal for conflicts (rule 6): unchanged — layer 3 and the attention list are provider-agnostic.

## 9. Test plan, and measurements owed before code depends on them

**Automated.** A hand-rolled `ServerSocket` stand-in per provider, the same technique and for the same
reason as `MatrixServer` (the real transport and parser stay in the loop; only storage is fake):
`GraphStandIn` serving `/me/drive`, items, `createUploadSession` + ranged `PUT` with `If-Match`/`409`/`412`,
`delta` (+ `410`), move, recycle-bin delete, a token endpoint with rotation and `invalid_grant`, and
switches for `401`/`429`. The 21-row matrix is parameterised by provider (§4 names the rows that
change). Required new assertions: **one refresh per cooldown** (rule 5 analogue), **a rotated refresh
token is persisted before the access token is used** (kill between the two), **re-auth to a different
identity is refused**, **a worker that halted on `invalid_grant` returns `success`**, and **an
unsupported capability produces an attention row**.

**Device.** The sign-in step is the user's (the app never sees the password); everything after it is
driven over `adb` on the emulator and read from `inkstone.db` with `run-as` (CLAUDE.md DoD #6). Two
real peers need an HTTPS-reachable service — Graph is HTTPS, so unlike the plain-HTTP Nextcloud
container this works.

**Measurements owed (spike P0, needs one throwaway personal Microsoft account; the user does the
sign-in).** Each is a claim I read in docs but did not observe:

| # | Question | Why it matters |
|---|---|---|
| M-M1 | Does `createUploadSession` + stale `If-Match` give 412 on a **personal** account, for an **id**-addressed update? | the whole of rule 1 for OneDrive |
| M-M2 | Does `conflictBehavior: fail` on an existing name give 409 `nameAlreadyExists` and leave the old file untouched? | create-if-absent |
| M-M3 | Can `delta` be scoped to `special/approot`, or only drive root? Cost of the first call on a real library? | §4's scoping decision |
| M-M4 | Does the eTag change on a metadata-only touch (rename, favourite, OneDrive's own indexing)? How often does that happen to an idle PDF? | spurious-412 rate |
| M-M5 | Is `If-Match` on move checked against the item or the folder? | the page's wording is ambiguous |
| M-M6 | Is the final fragment atomic — is a half-uploaded session invisible? | rule 10 (no torn remote file) |
| M-M7 | After a move, does `delta` give the same id + new `parentReference`, and are descendants omitted on a folder rename? | binding repair |
| M-G1 | (only if D2 = yes) Does Drive ignore `If-Match` on a content `PATCH`, as reported? | the blocker's premise |
| M-G2 | (D2 = yes) Does the revision-chain post-check detect a concurrent write, and how long after? | mitigation's soundness |
| M-G3 | (D2 = yes) Does a *Testing*-status project end an `AuthorizationClient` grant at 7 days? | whether "In production" is mandatory |

M-M1 is the gate: if it fails, OneDrive is a blocker too and I stop and report.

## 10. What I did not read or measure — do not rely on these

- **Nothing here was measured against a live provider.** Sections 3 and 4 are official-doc reads, plus
  one third-party measurement each for Graph (issue 1716) and Drive (the Stack Overflow thread).
- Not read: Drive `changes` *resource* reference (the `removed`/`file` fields — I know the shape, did not
  cite it); Drive error/quotas (`403 rateLimitExceeded` vs `429`); `files.update` `keepRevisionForever`;
  Graph `Files.ReadWrite.AppFolder` and `offline_access` rows; Google's restricted-scope assessment rules;
  Microsoft publisher verification; the user cap for unverified Google apps; how `AuthorizationClient`
  behaves in a background process.
- `quickXorHash` / `sha1Hash` on Graph `file.hashes` (usable as a content check where an eTag lies) —
  known from experience, not cited.
- Statuses below use CLAUDE.md's four buckets: **everything in this file is *written*; nothing is
  *automated*, *run against a real dependency*, or *run on a device*.**

## 11. Decisions the user owns

- **D1 — Order.** Recommend OneDrive first (§0). Drive waits on D2.
- **D2 — Drive and rule 1.** Drive has no precondition (§3.2). Options: **(a)** do not ship Drive until
  Google adds one; **(b)** ship it with the check-then-write + pinned-revision + post-verify design, and
  amend rule 1 to say *"or, where the provider offers none, a pinned-revision post-check"*; **(c)** never.
  I recommend **(a) for now**, revisited after OneDrive proves the seam. This is a rule change, so it is
  not mine to make.
- **D3 — Adopting existing PDFs.** Recommend OneDrive `Files.ReadWrite` (full), Drive `drive.file` (no
  adoption). Say if adoption on Drive is required — that forces the restricted `drive` scope and its review.
- **D4 — Audience.** Personal use vs commercial changes what Microsoft publisher verification and Google
  production publishing require. `inkstone-may-go-commercial` is on record; assume commercial and do the
  cheap registrations properly now (a real app name and privacy-policy URL on the consent screens).

## 12. Order of work, and what is out of scope for the first provider

0. **P0** — measurements M-M1…M-M7 (needs the user's throwaway account sign-in).
1. **P1** — `CloudStore` seam over Nextcloud, no behaviour change (§2.3). *Ships alone.*
2. **P2** — `10.sqm`, `provider` on the account, `ProviderSecret` in `CredentialStore`, migration tests.
3. **P3** — `OneDriveStore` + `GraphStandIn` + parameterised matrix.
4. **P4** — Custom Tab + PKCE + pending-auth file + redirect activity + the Sync-screen chooser; user does
   the sign-in; `adb` walk on the emulator.
5. **P5** — Drive, only per D2.

**Out of scope for the first provider, and said so in the UI (§8.3):** lecture recordings
(`MEDIA_UPLOAD` and the M12 transcript watcher are Nextcloud-specific); the web reader and MCP server
(`tools/web`, Nextcloud-only); adopting a PDF from OneDrive in the first cut (the folder browser comes
with P3, adoption is a follow-up); Nextcloud-only extras (`SEARCH`, quota, capabilities, Login Flow).

## 13. Sources

Microsoft: createUploadSession · put-content · delete · move · delta · throttling · permissions-reference
· refresh-tokens · v2-oauth2-auth-code-flow · quickstart-register-app (all `learn.microsoft.com`, paths in
§3–§6) · <https://github.com/OneDrive/onedrive-api-docs/issues/1716>.
Google: `files` resource · `files.update` · manage-uploads · manage-revisions · manage-changes ·
api-specific-auth · oauth2 · oauth2/native-app · sign-in/android/authorize-access ·
production-readiness/sensitive-scope-verification (all `developers.google.com`, paths in §3–§6) ·
<https://stackoverflow.com/questions/79865579/is-raceless-optimistic-concurrency-possible-in-google-drive-v3-interface>.
Read 2026-10-17.
