# M5 — Nextcloud, part 1: auth and upload

What M5 built, the three places SPEC turned out to be incomplete, and the closing of
Appendix B spike #4 against a live server.

Read alongside SPEC §7.1–§7.6, §7.8–§7.9 and §14.

---

## 1. The spike (SPEC Appendix B #4) — ✅ CLOSED

> *Does a PDF incremental update survive Nextcloud's storage and any server-side
> processing intact?*

**Yes, byte-identically.** Verified against a live Nextcloud, not only a mock.

```
SPIKE B#4: uploaded 4649 bytes to https://…/Inkstone-spike/spike-0192f3c4-….pdf
           UploadReceipt(status=201, etag="0e05169878fe8201cca6821a7a2b039a",
                         ocId=00001770ocajwlgij0fa)
SPIKE B#4: PASS — 4649 bytes round-tripped byte-identically, the original prefix is
           intact and the file still opens as ours.
```

A real published PDF — built by M4's writer, original bytes verbatim at the front plus one
incremental update carrying `/Ink` — was PUT to the server, downloaded, and compared byte
for byte. The original prefix survived, the XMP marker parsed, `detectOpenMode` still said
`Normal`, the page count was unchanged and the annotations were still readable.

**SPEC §5.2's incremental update stands. The fallback it names — full rewrite,
`assets/source.pdf` back in the bundle, larger files — is not needed.**

### Three things it confirmed on the way past

Each of these was an assumption until this ran:

- **The uid path is right on a real server.** The request addressed
  `/remote.php/dav/files/<uid>/…` built from `ocs.data.id` and got a `201`. Get SPEC §0.1
  #3 wrong and it 404s — so the project's self-declared "most likely bug" is now checked
  against real hardware and not only against `LoginFlowV2Test`'s mock.
- **`OC-ETag` and `OC-FileId` do come back on the PUT**, and the ETag was **strong** — no
  `W/` prefix. That is SPEC §7.4's *"no follow-up PROPFIND"* confirmed, which is what keeps
  an upload to one round trip. It also means §7.3's weak-ETag trap is a real-but-uncommon
  case rather than the default path.
- **The client is byte-transparent independently of the server.** `:core:sync`'s
  `ByteTransparencyTest` round-trips a 3 MB payload shaped like a published note (every
  byte value 0–255, `%%EOF`/`startxref` landmarks, big enough to span many buffers) through
  the real `WebDavClient`, and asserts on the outgoing request directly so a forgiving
  store cannot hide an outbound transcoding. A future failure can therefore be attributed
  to one side or the other rather than guessed at.

### Re-running it

```bash
INKSTONE_SPIKE_URL=https://cloud.example.com INKSTONE_SPIKE_LOGIN=you@example.com INKSTONE_SPIKE_UID=you INKSTONE_SPIKE_APP_PASSWORD=… ./gradlew :android:pdf:testDebugUnitTest --tests '*PdfRoundTripSpikeTest*' --rerun-tasks
```

`_LOGIN` and `_UID` are two variables **on purpose** — see §3.1. Without them the live test
**skips loudly** rather than passing quietly, because a spike that reports success without
having run is worse than no spike. It uploads one file, downloads it, compares, and deletes
it (Nextcloud's `DELETE` is soft, so it lands in the server's trash even on a failed run).

## 2. What was built

| | Where |
|---|---|
| Login Flow v2, all six rules | `:core:sync` `LoginFlowV2` |
| UID from `ocs.data.id` | `OcsClient.fetchUid` |
| Capabilities, cached | `NextcloudCapabilities`, `account.capabilities` |
| Hand-rolled PROPFIND + multistatus | `WebDavXml`, `Xml` |
| WebDAV verbs, SPEC §7.5's response table | `WebDavClient`, `DavOutcome` |
| Outbox semantics | `OutboxCoalescing` (pure) + `SqlOutboxStore` (one transaction) |
| The drainer | `OutboxDrainer` |
| Binding a note | `SyncEngine`, `LibraryRepository.bindToNextcloud` |
| Keystore AES-GCM credentials | `:app` `KeystoreCredentialStore` |
| WorkManager | `:app` `OutboxDrainWorker`, `MaterializeWorker`, `WorkManagerScheduler` |
| UI | `:app` `SyncScreen`, `SyncViewModel`, `NextcloudAccounts` |

### The module edge M5 added: `:core:database → :core:sync`

SPEC §2.1 lists the `:core:*` modules without saying how they relate to each other. M5
adds one edge between two of them, and the direction is deliberate.

The outbox's *semantics* — coalescing, annihilation, backoff inheritance, the mid-flight
`local_rev` guard — are the part of this milestone that can be got wrong quietly. They
live in `:core:sync`, which declares `OutboxStore` / `BindingStore` / `AccountStore` /
`SyncNotes` as ports, so they can be tested exhaustively against fakes with no driver and
no device. `:core:database` implements those ports. The alternative — `:core:sync`
depending on `:core:database` — would have put the rules in the module that owns SQLDelight
and made "500 offline edits produce one upload" an instrumented test.

The edge is inside `:core:*`, so SPEC §2.1's one-way `:app → :android:* → :core:*` rule is
untouched, and it is acyclic: `:core:database → :core:sync → :core:model`.

There is also a **test-only** edge `:android:pdf → :core:sync`, which exists solely for the
spike above. `:android:pdf`'s main source set has no idea sync exists.

### Ktor does not leave `:core:sync`

CLAUDE.md: *"third-party types don't leak across module boundaries. `dav4jvm` stays inside
`:core:sync`."* The same has to apply to Ktor, and it applies harder — `:app` is Android-only,
so nothing would complain if it started holding an `HttpClient` and, two milestones later,
made a request outside the outbox (CLAUDE.md rule 4).

`NextcloudTransport` is that boundary: opaque from outside the module, so there is nothing
in `:app` to make a request *with*. It was added mid-milestone, when Hilt asked `:app` to
name the return type of a provider and the honest answer turned out to be *"a type `:app`
should not be able to name"*.

---

## 3. Where SPEC was incomplete

### 3.1 §7.1 rules 3 and 6 pull in opposite directions

Rule 3: *"persist the credential before acknowledging the 200 — the server deletes its row
immediately; a crash in between is unrecoverable."*
Rule 6: *"fetch the UID from `/ocs/v2.php/cloud/user` → `ocs.data.id`."*

The uid can only be fetched **using** the app password, over the network, and that can
fail. So the credential is persisted **twice**: once the instant the 200 arrives, with
`uid = ""` (rule 3 satisfied, nothing lost), and again once the uid is known.

An account whose stored credential has an empty uid sits in `AccountState.NEEDS_UID` and is
finished on the next launch by `NextcloudAccounts.repairAndRefresh`. Nothing may use such a
credential for WebDAV in the meantime, which `NextcloudSession`'s constructor enforces with
a `require` rather than a comment — the alternative is `/remote.php/dav/files//…`, whose 404
reads as "the file is not there" rather than "you built the wrong URL".

`LoginFlowV2.awaitApproval` therefore takes a `persist` lambda and calls it before it
returns. A caller that only honoured the second call would have reintroduced the
unrecoverable window, which is why the signature makes that hard rather than documenting it.

### 3.2 §7.6's `sync_state` set was missing from the model

SPEC §7.6 lists `{ LOCAL_ONLY, SYNCED, PENDING_UPLOAD, PENDING_DOWNLOAD, CONFLICT, ERROR,
NEEDS_REAUTH }`. `NoteSyncState` (added in M2 for `Compaction`) had six of the seven —
**`ERROR` was absent** — and spells the fifth `CONFLICTED`.

`ERROR` is added in M5 and is load-bearing: it is what a quota failure, a forbidden folder
and a non-retryable 4xx land in. It has to be distinct from `CONFLICTED` because the two
need different things from the user (merge vs change something on the server) and from
`NEEDS_REAUTH` because the credential is fine. SPEC §7.6 has been left as it is and the
enum now matches it, `CONFLICT`/`CONFLICTED` spelling aside. *(⚠️ An eighth state, `PAUSED`,
was added in M6 for a signed-out account's notes — non-compactable, its own badge — so "matches
the seven-state set" is stale by one.)*

### 3.3 §7.5's `OC-Checksum` needs a digest `:core:*` does not have

`OC-Checksum: md5:<hex>` is in SPEC §7.5's upload block, and Nextcloud *verifies* it — a
wrong implementation turns every upload into a 400. There is no MD5 in common Kotlin, and
`java.security.MessageDigest` is JVM-only, so it would kill the iPad path exactly as OkHttp
would (CLAUDE.md rule 2).

So `:core:sync` has a hand-written MD5, for the same reason `:core:storage` has a
hand-written SHA-256, checked against RFC 1321's own test vectors. It is `internal`, it is
used for exactly one header, and its KDoc says in as many words that it is a transport
integrity check against accidental corruption and must never become a security primitive.
The project's content fingerprint is still SHA-256 (SPEC §5.6).

---

## 4. Decisions worth recording

**A weak ETag is a type, not a string.** SPEC §7.3 warns that returning `W/"…"` in
`If-Match` causes a permanent 412 loop. "Was this weak" cannot be recovered from the
stripped value, so recomputing it at each call site is how three of four call sites get it
right and the fourth bricks a note forever. `ETag(value, weak)` carries it, `asIfMatch`
returns **null** for a weak one, and the flag is a column in `remoteBinding`.

**`everUploaded` includes the upload timestamp, not just the ETag and `oc:id`.** A reverse
proxy that strips `OC-ETag` and `OC-FileId` would otherwise leave a *successfully uploaded*
note looking un-uploaded — and the next PUT would send `If-None-Match: *` against a file
that now exists, which 412s forever. `lastUploadAtEpochMillis` is ours and nothing on the
wire can remove it.

**Deferring is not failing.** The 30-second inter-upload floor reschedules without
incrementing `attempts`. Charging it an attempt would push a note that is merely being
written to into exponential backoff.

**Backoff is inherited on coalesce.** This is the rule in SPEC §7.6 that looks like a
detail and is not: resetting `next_attempt_at` on every UPSERT means a note being actively
written to has its retry timer reset every two seconds, so a note that is failing to upload
retries *immediately, forever* — a hot loop against the user's own server, which is the
thing SPEC §7.2 exists to prevent.

**A 412 parks; it does not retry and it does not drop.** SPEC §7.5 says never blind-retry
and §7.7 rule 3 says a conflicted note is flagged and skipped while everything else keeps
syncing. So the row stays (M6 will resolve it) with `next_attempt_at` pushed a day out, and
the note is flagged `CONFLICTED`. *(As forecast: M6 added the resolvers — the drainer reports
412'd note ids and `OutboxDrainWorker` enqueues a per-note reconcile, and the completing merge
un-parks — and M12 added `syncNowRequested`, which lifts parks on an explicit user press.)*

**A 401 costs exactly one extra request, ever.** SPEC §7.2's *"confirm once with
`GET /ocs/v2.php/cloud/user`"*. If that also 401s the account halts; if it succeeds the 401
was about that path (a withdrawn share, a locked folder) and the note is flagged `ERROR`
rather than the user being signed out. If the confirmation cannot be *reached*, **nothing is
concluded** — treating unreachable as "credentials dead" would sign the user out every time
they walk into a lift.

`OutboxDrainWorker` returns `Result.success()` — not `retry()` — when an account halts,
because WorkManager's backoff would otherwise become precisely the credential retry loop
CLAUDE.md rule 5 forbids.

**A Custom Tab, and a new pinned coordinate.** SPEC §7.1 says to open the login URL in a
Custom Tab, which needs `androidx.browser`. It was pinned at **1.10.0** after reading its
`aar-metadata.properties` (`minCompileSdk 36`, `minAGP 8.9.1` — both satisfied), per SPEC
§0.2's rule that resolving is not the same as building. There is a plain `ACTION_VIEW`
fallback for a device with no Custom Tabs provider.

**No `<debug-overrides>` in the network security config.** SPEC §14 is HTTPS-only with no
"accept anyway", and a debug override is the usual route to shipping a user-trusted CA in
release. A self-hosted Nextcloud on a private CA is a real situation and the answer is to
install the CA in the system trust store — the user's decision, not the app's to bypass.

---

## 5. What M5 deliberately did not do

- **No pull, no reconciler, no merge.** That is M6 (SPEC §7.4, §7.7, §13.2's 21 rows).
  `capabilities.core.pollinterval` is parsed and cached but nothing paces against it yet,
  because nothing polls. *(Superseded as forecast: `PollTicker` paces the foreground poll at
  `pollIntervalSeconds + bruteforceDelaySeconds`, and `ReconcileWorker` polls in the
  background.)*
- **`capabilities.bruteforce.delay` is parsed and surfaced but not yet acted on.** SPEC §7.2
  says a non-zero value means the client is already throttled; using it to widen backoff
  belongs with M6's polling loop. *(Superseded with the bullet above — the delay is in
  `PollTicker`'s pacing sum.)*
- **Folder mirroring is one-directional and partial.** Renaming a note renames the remote
  file (a MOVE, which drains before that note's UPLOAD). Moving a note between *library*
  folders does **not** move the remote file: the remote folder is the one the user picked
  when they bound the note. SPEC §9's *"folders mirror Nextcloud both directions"* needs the
  pull side to be meaningful and is M6's.
- **No chunked upload.** SPEC §7.5: not in v1. Appendix B #3 stays open.
- **Push (`notify_push`) is detected and not used.** SPEC §7.6: assume it is absent; it is an
  optimisation only and never replaces polling.

---

## 6. Testing

```bash
./gradlew build                                    # 286 unit tests per variant, all green
./gradlew :core:database:connectedDebugAndroidTest # 24 tests, green on an emulator
```

⚠️ **That line said "1068 JVM tests" until M6's review, and 1,068 is not a test count** — it is
Gradle's `1068 actionable tasks` from the same summary line, read as one and then quoted as one here,
in `CLAUDE.md`, in `ROADMAP.md` and twice in M6's notes. The measured figure is `tests=` summed over
`*/build/test-results/testDebugUnitTest/*.xml`, which at this milestone's commit (`d6993e3`) is
**286** — `:core:model` 108, `:core:sync` 79, `:core:storage` 59, `:app` 17, `:android:pdf` 23 — and
it is *per variant*: `build` runs the same tests again as `testReleaseUnitTest`, so 286 written and
572 executed.

`:core:sync`'s `commonTest` (79 tests) covers the protocol and the policy against Ktor's
`MockEngine` and in-memory ports: SPEC §7.1's six rules, §7.3's PROPFIND body and
multistatus parse, the weak-ETag trap, §7.5's whole response table, §7.6's coalescing table
row by row, and the four drainer behaviours ROADMAP M5 lists as acceptance criteria.

A mock is the right tool for exactly these, because it can produce a weak ETag, a 507 or a
401 on demand and a healthy server will not. **It is not the right tool for M6** — SPEC §13
requires the merge matrix, fileid churn and brute-force lockout to run against a real
Nextcloud in Docker, and none of that is faked here.

`:core:database`'s `SqlSyncStoresTest` is instrumented, because what it proves needs real
SQLite: that the composite primary key coalesces rather than the fake's `LinkedHashMap`
doing it, that `mutate` is one transaction, that `selectDueOutbox`'s `CASE` ordering runs on
Android's SQLite and not only in SQLDelight's parser, and that the weak-ETag flag survives a
round trip.

### The instrumented tests had never actually run

`:core:database`'s `SqlSyncStoresTest` was written, compiled by `check`, and could not have
run — nor could M3's `LibraryIndexTest`, nor `:android:ink`'s. Three separate things were
wrong, and each was invisible to a compile:

1. **Dex rejects a comma or an apostrophe in a method name.** `check` compiles instrumented
   tests (M3 added that after a broken one sat unnoticed for two commits) but compiling is
   not dexing.
2. **`androidx.test:runner` is not transitive from `androidx.test.ext:junit`**, and
   `testInstrumentationRunner` names a class from it. Every instrumented test in
   `:core:database` and `:android:ink` would have died with `ClassNotFoundException` before
   the first test. Now pinned at 1.7.0 and declared in both.
3. **`:android:ink`'s tests fail on x86_64** — see below.

With those fixed, **24 instrumented tests pass** (M3's 16 + M5's 8) against real Android
SQLite: the composite primary key really coalesces, `mutate` really is one transaction, the
`CASE` in `selectDueOutbox` really runs, and the weak-ETag flag really round-trips.

The lesson for future milestones: `./gradlew build` proving an instrumented test *compiles*
is not the same as it working. Run `connectedDebugAndroidTest` at least once per milestone
that adds one.

### `:android:ink`'s instrumented tests are architecture-dependent

Ten of fifteen fail on an x86_64 emulator and pass on the arm64 tablet. The cause is float
precision, not logic: M1's synthetic-ink fixture computes stroke orientations without
clamping to `[0, 2π)`, and x86_64 rounding lands a thousandth outside the range, which
`androidx.ink` rejects outright (`Got: -0.00147393π`, `Got: 2.00116π`). One assertion also
carries an arm64-tuned tolerance (`expected:<415.70966> but was:<415.7085>`).

**Not fixed in M5** — it is M1's fixture, and CLAUDE.md is one milestone per session. Worth
about a one-line clamp when M1's owner next touches it.

### Two bugs only a real device found

Both were invisible to all 286 JVM unit tests and to 24 instrumented ones, and both were about
*account lifecycle* rather than the protocol.

**Re-login created a second account.** `completeLogin` minted a fresh account id every
time, so signing in again after a `NEEDS_REAUTH` halt left the halted account beside the
new one — and every `remote_binding.account_id` still pointed at the halted one. SPEC
§7.2's *"prompt for re-login"* worked; recovering from it silently orphaned every bound
note. Identity is now `(serverUrl, uid)` and re-login **adopts** the existing account,
which is also what clears the reauth flag.

**Signing out stranded the notes.** `signOut` removed the account row and the credential
and left the bindings and outbox rows behind. The notes then read `PENDING_UPLOAD`
forever, the rows never drained, `attempts` never incremented, and
`OutboxDrainWorker` returned **`SUCCESS`** on every pass — because "could not open a
session" counted as `skipped`, and `skipped` does not set `shouldRunAgain`. A user who
signed out with work queued got a permanent, silent stall.

Two changes: `signOut` now unbinds every note on that account first (back to
`LOCAL_ONLY` — nothing local destroyed, nothing remote touched) *(⚠️ superseded in M6:
`signOut` **pauses** instead — `remote_binding` rows survive, outbox rows drop, notes go
`PAUSED` — which is what makes signing back in one tap rather than a migration; the
unbind-to-`LOCAL_ONLY` behaviour lives only in `forgetAccount` now)*, and the drainer
distinguishes the two cases. An account that is **gone** is permanent, so the row is
dropped and the note goes to `LOCAL_ONLY`; an account that **exists but is unusable**
(`NEEDS_REAUTH`, no uid) is a wait, so the work is kept and the note is flagged
`NEEDS_REAUTH` instead of saying nothing.

Both have regression tests in `OutboxDrainerTest`, and both were re-verified on the
device afterwards.

### A Kotlin/Native trap worth knowing

**A backtick test-function name in `commonTest` may not contain a comma.** Kotlin/Native
rejects it (`Name contains illegal characters: ","`), the JVM compiler does not, and the
error therefore appears only when `compileTestKotlinIosArm64` runs — i.e. in a full
`./gradlew build`, long after the tests were written and passing. Ten names had to be
reworded.

---

## 7. What was verified on a device

Driven over adb against a Pixel Tablet emulator and the author's real Nextcloud 32.0.6.
`adb shell input stylus` produces `TOOL_TYPE_STYLUS`, which is what `PalmGuard` accepts —
so real strokes, not synthetic geometry.

| | Result |
|---|---|
| Login, capabilities cached | `serverVersion 32.0.6`, `forbidden_filename_characters "\/"` |
| Offline: create, draw 20 pages, bind | binding + `MKCOL` + `UPLOAD` written instantly, note stays fully editable |
| ~55 revisions of offline editing | **one** outbox row, `localRevAtEnqueue` 49 → 55 |
| Reconnect | uploaded **within 9 s, no user action** |
| Force-stop with a full outbox | row survived, UI showed "1 waiting to upload", drained on reconnect |
| `Physics: Term 1 / 2 📘` | → `Inkstone/Physics_ Term 1 _ 2 📘.pdf`; `:` and `/` replaced, emoji kept |
| First upload | `MKCOL` created the folder, `OC-ETag` + `OC-FileId` came back on the PUT |

The `oc:id` and a **strong** ETag arriving on the PUT confirms SPEC §7.4's *"no follow-up
PROPFIND"* on a real server, which is what keeps an upload to one round trip.

## 8. Acceptance criteria not yet met

Three, after §7:

- **An instance where the login name is an email address** (uid ≠ loginName). Covered by
  `LoginFlowV2Test` against a mock; the live account tested happened to have uid ==
  loginName, so the difference SPEC §0.1 #3 warns about was not exercised end to end.
- **Revoke the app password server-side → zero brute-force warnings in the Nextcloud log.**
  The app-side behaviour has automated coverage (`OutboxDrainerTest` asserts one PUT, one
  confirmation GET, nothing after), but the server's log is the only real evidence.
- **A confirmation pass on the actual Galaxy Tab** — CLAUDE.md definition of done #4. An
  emulator does not substitute, and this project has already been burned by emulator-green
  (see §6).

SPEC Appendix B #4 is **no longer on this list** — it was closed against a live server (§1).
- SPEC Appendix B #4's live half (§1 above)
