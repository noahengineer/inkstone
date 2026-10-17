package dev.inkstone.core.sync

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.CredentialStore
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.model.RemoteBundleExtractor
import dev.inkstone.core.model.RemoteBundleVerdict
import dev.inkstone.core.model.RemoteName
import dev.inkstone.core.storage.MergeReport
import dev.inkstone.core.storage.NoteLayout
import kotlinx.coroutines.sync.Mutex

/**
 * ⭐ SPEC §7.6 — the reconciler. The pull half of sync, and the mirror of [OutboxDrainer].
 *
 * ## The shape of one pass
 *
 * ```
 * 1. per usable account            — a halted one gets ZERO requests (CLAUDE.md rule 5)
 * 2. PROPFIND Depth:0 on the common ancestor of the roots
 *      nc:data-fingerprint moved   → drop the whole ETag cache, walk everything
 *      root ETag unchanged, strong → DONE. One request. This is the common case.
 * 3. BFS the roots at Depth:1, pruning subtrees by directory ETag       (RemoteWalk)
 * 4. per .pdf, matched on oc:id    — moved / changed / adopt / absent   (SPEC §7.6 step 3)
 * 5. download to download/<id>.pdf — never over notes/<id>/document.pdf
 * 6. extract the bundle            — RemoteBundleExtractor, in :android:pdf
 * 7. merge                         — RemoteMergeGate, in :app, under the note's mutex
 * 8. the outbox decision table     — the termination argument for the ETag ping-pong
 * 9. per binding the walk missed   — SEARCH its fileid before concluding a deletion (resolveAbsent)
 * ```
 *
 * ## It performs no remote writes. None.
 *
 * CLAUDE.md rule 4 has no pull-side exemption. The reconciler reads, merges locally, and — when a
 * merge leaves the local note ahead of the server — asks [SyncEngine] to enqueue an UPLOAD. Not
 * even a conflicted copy is PUT from here: the copy is created as a local note and *bound*, and
 * the outbox uploads it like anything else, with the `If-None-Match: *` that stops it landing on
 * top of the file it exists to preserve.
 *
 * ## Six things that are silent if they are wrong
 *
 * **Exactly one pass runs at a time.** Two overlapping passes share the staging directory and the
 * directory cache, and every way that goes wrong ends as a *conflict* rather than as an error — a
 * torn download, a bundle discarded from under the other pass, a cache row written over an unmerged
 * one. [reconcile] holds the single-flight; nothing outside this class can arrange it.
 *
 * **A weak ETag never short-circuits.** A false "unchanged" means the pull never happens and the
 * note simply stops syncing, with no error anywhere. A false "changed" costs one request. This
 * takes the expensive direction ([RemoteWalk.unchanged]).
 *
 * **A pruned subtree is not a mass deletion.** "Known `oc:id` absent remotely" is only decidable
 * for a directory that was actually walked **and finished**, so the deletion branch intersects the
 * unstamped bindings with the walked-and-settled set. Skipping that intersection puts a hundred
 * notes in the trash on one lucky ETag match — and *"walked"* alone was not enough: the pass limit,
 * a 404 and a failed file all left a directory in the walked set with its files unread.
 *
 * **A note absent from every walked folder may have simply been moved somewhere we do not walk.**
 * The walk only ever visits folders this account already has a note in, so SPEC §7.4 rule 2 — which
 * needs the *new* href to compare — cannot fire for a move out of that set, and the note was trashed
 * with its file untouched on the server. [resolveAbsent] asks the fileid where it went before
 * concluding anything, once per absent candidate and never per file.
 *
 * **A directory's ETag is cached only when everything under it finished.** The cache row means
 * *"fully reconciled at this version"*. Writing one over an unmerged download prunes that subtree
 * on every later pass until something else in it happens to change — so a note stops syncing and
 * the poll reports success.
 *
 * **`shouldRunAgain` excludes skipped and halted accounts — and every 401.** Folding any of them in
 * would make WorkManager's backoff *be* the credential retry loop rule 5 forbids. A 401 the
 * confirmation call could not confirm leaves the account ACTIVE, so it is the one that gets there
 * without looking like it: [ReconcileReport.refused] and [SyncCursor.refusedUntilEpochMillis] are
 * the two halves of not taking that ladder.
 */
@Suppress("LongParameterList", "TooManyFunctions")
// LongParameterList: nine collaborators, and the alternatives are worse. The two store bundles are
// already grouped for exactly this reason (SyncStores, ReconcileStores), and grouping further would
// mean inventing a holder whose only member is "the things a reconcile needs" — which is what this
// class is. `provideOutboxDrainer` set the precedent in :app's DI module.
//
// TooManyFunctions: one pass through SPEC §7.6, as one named function per step of it — probe, walk,
// list, visit, the four branches of step 3, download, layer 3, the outbox table, the end-of-pass
// guards. The count is the spec's, not this class's: collapsing steps to satisfy it would destroy
// the one-to-one correspondence with §7.6 that is the only way to check the thing is right, and the
// two functions most likely to be wrong (`apply`'s decision table and `finish`'s two guards) are
// exactly the ones that would get buried. Everything with state of its own is already extracted —
// the walk is pure in `RemoteWalk`, the attention policy is pure in `Attention`, and the persistence
// is six ports. This is the same judgement `InkSurfaceView` gets in config/detekt/detekt.yml. The
// natural next extraction, if it grows, is the staging directory (`fetch`/`fetchTo`/`discard` plus
// the two path helpers) into a `RemoteStaging` collaborator.
public class Reconciler(
    private val stores: SyncStores,
    private val pull: ReconcileStores,
    credentials: CredentialStore,
    private val fs: FileSystem,
    private val clock: Clock,
    transport: NextcloudTransport,
    private val extractor: RemoteBundleExtractor,
    private val gate: RemoteMergeGate,
    private val engine: SyncEngine,
    /** docs/CLOUD-PROVIDERS.md §2. Defaulted to Nextcloud's so existing construction sites are unchanged. */
    private val storeFactory: CloudStoreFactory = NextcloudStoreFactory(credentials, transport),
) {

    private val accounts get() = stores.accounts
    private val bindings get() = stores.bindings
    private val notes get() = stores.notes

    /**
     * ⭐ **One pass at a time, across every caller.** See [reconcile].
     *
     * Not `withLock`: a waiter would hold a WorkManager thread for the length of somebody else's
     * pass, and `K` notes 412ing in one drain enqueue `K` uniquely-named jobs against a four-wide
     * executor — the drain worker would starve behind them. [ReconcileReport.busy] plus
     * `shouldRunAgain` sends the loser away and has WorkManager bring it back, which is the same
     * "come back later" the outbox rows' `next_attempt_at` gives the drainer.
     */
    private val inFlight = Mutex()

    /**
     * Pull everything that has changed.
     *
     * ⚠️ **Two passes must never overlap, and nothing outside this class can arrange that.** Three
     * unique WorkManager names run this same whole-account pass — the ticker's, the periodic one, and
     * one per note a 412 refused — and `ExistingWorkPolicy` deduplicates only *within* a name. Two
     * passes over one account share the staging directory and the ETag cache, and both sharings lose
     * data:
     *
     * - `download/<id>.pdf` is one fixed path, written through `writeBytesAtomically`, whose temp
     *   file is named after its destination. Two GETs of the same note interleave into one temp file
     *   and one of them renames a **torn PDF** into place — the corruption class CLAUDE.md rule 10
     *   exists to prevent. A torn download routes `changed` into layer 3, so the visible result is a
     *   spurious conflicted copy and the original flagged `CONFLICTED`, from a race, with both
     *   copies identical.
     * - `discard` deletes the staged bundle and the `pendingDownload` row. Doing that while the
     *   other pass sits between `extract` and `gate.merge` turns its bundle unreadable, which is
     *   `DifferentDocument`, which is layer 3 again.
     * - [finish]'s cache row means *"fully reconciled at this version"*. One pass writing it while
     *   the other still holds an unmerged download in the same directory prunes that subtree on
     *   every later pass, and the note stops syncing while the poll reports success.
     *
     * So the guarantee lives here, where the state is, rather than in a work-name policy: this class
     * is a `@Singleton` and `PollTicker`, the workers and the merge-matrix harness all reach it
     * directly. A single unique name would also have to choose between `KEEP` — which drops the pass
     * a 412 asked for, the one case where a *fresh* pass is the point — and `APPEND_OR_REPLACE`,
     * which serialises by building a chain as long as the number of conflicts.
     *
     * @param limit how many remote files to *act on* in one pass. WorkManager gives a job ten
     *   minutes and a first pull of a shared folder can be hundreds of megabytes; a bounded pass
     *   that reports [ReconcileReport.shouldRunAgain] is safer than an unbounded one that is
     *   killed halfway. Nothing is lost either way — no binding's ETag advances for work that did
     *   not finish, so the next pass sees exactly the same work.
     * @return [ReconcileReport.busy] and nothing else when another pass is already running. **Zero
     *   requests**, so a thundering herd of workers costs wake-ups and not traffic.
     */
    public suspend fun reconcile(limit: Int = DEFAULT_LIMIT): ReconcileReport {
        if (!inFlight.tryLock()) return ReconcileReport(busy = true)
        try {
            var report = ReconcileReport()
            for (account in accounts.all()) {
                report = reconcileAccount(account, limit, report)
            }
            return report
        } finally {
            // `finally`, not the end of the block: a pass that throws — a full disk inside the
            // gate, a cancelled worker — must not leave the lock held. One held lock is every
            // later pass returning `busy` forever, which is sync stopping with the poll reporting
            // success.
            inFlight.unlock()
        }
    }

    // -----------------------------------------------------------------------
    // One account
    // -----------------------------------------------------------------------

    private suspend fun reconcileAccount(account: SyncAccount, limit: Int, report: ReconcileReport): ReconcileReport {
        if (!account.isUsable) return unusable(account, report)
        // Null covers a missing credential and a stored one whose uid disagrees with the account
        // row (`isUsable` has already vouched for the row's view of it).
        val store = storeFactory.open(account) ?: return report.skipping(account.id)
        return pass(store, limit, report)
    }

    /**
     * ⚠️ **An unusable account gets zero requests, not one polite probe.**
     *
     * SPEC §7.2 and CLAUDE.md rule 5. A reconciler that iterated `all()` and tried anyway would
     * make one request per halted account per poll, and at the sixty-second floor SPEC §7.6 asks
     * for, that is a brute-force pattern against the user's own server — from inside their own
     * app, on the credential the server has already refused.
     *
     * The three unusable states are told apart, because they are the drainer's permanent-vs-wait
     * split seen from the pull side and M5 shipped two of them collapsed into one:
     *
     * - **`SIGNED_OUT`** — the user's own decision. No alarm and no attention row. The binding
     *   survives, which is what makes signing back in one tap rather than a migration.
     * - **`NEEDS_REAUTH`** — the server refused us. One account-level attention row, so that
     *   *"your notes stopped syncing"* is visible without ever retrying a credential to find out.
     * - **`NEEDS_UID`, or no credential** — recoverable on the next launch (SPEC §0.1 #3).
     *   Silent, because there is nothing for the user to do.
     */
    private suspend fun unusable(account: SyncAccount, report: ReconcileReport): ReconcileReport =
        if (account.state == AccountState.NEEDS_REAUTH) {
            recordReauth(account.id)
            report.copy(haltedAccounts = report.haltedAccounts + account.id)
        } else {
            report.skipping(account.id)
        }

    /**
     * SPEC §7.6 steps 1 to 4 for one authenticated account.
     *
     * The pass start is read from the clock **once** and used for every stamp in it, so that
     * `stampSeen(…, passStartedAt)` and `notSeenSince(…, passStartedAt)` are exact complements.
     * Reading the clock twice would put a note stamped mid-pass on the wrong side of that
     * comparison, and the wrong side of *that* comparison is the trash.
     */
    private suspend fun pass(session: CloudStore, limit: Int, report: ReconcileReport): ReconcileReport {
        val accountId = session.account.id
        val passStartedAt = clock.nowEpochMillis()
        val cursor = pull.cursors.find(accountId) ?: SyncCursor(accountId)
        // ⚠️ CLAUDE.md rule 5, read **before** the first request. A 401 the confirmation call could
        // not confirm leaves the account ACTIVE — and an ACTIVE account that walks again on the next
        // sixty-second poll re-sends the refused credential 1 440 times a day. See
        // [SyncCursor.refusedUntilEpochMillis]; the cooldown is what makes "halted" mean something
        // for the two answers that are not `NEEDS_REAUTH`.
        if (passStartedAt < cursor.refusedUntilEpochMillis) return report.skipping(accountId)
        val bindingPaths = bindings.forAccount(accountId).map { it.remotePath }
        val pinned = pull.folders.pathsForAccount(accountId)
        val roots = RemoteWalk.rootsOf(session.account.defaultFolder, bindingPaths, pinned.values)
        val polled = report.copy(accountsPolled = report.accountsPolled + 1)

        return when (val probe = probeRoot(session, roots, cursor, passStartedAt)) {
            is RootProbe.Failed -> {
                val outcome = ReconcileOutcome.Failed(null, probe.path, probe.failure)
                record(outcome, accountId)
                // The walk never started, so [finish] will not run and this is the only place the
                // cooldown can be opened for a root that refused us.
                if (probe.failure == DavFailure.Unauthorized) {
                    pull.cursors.upsert(cursor.copy(refusedUntilEpochMillis = passStartedAt + REFUSAL_COOLDOWN_MILLIS))
                }
                halting(probe.failure, accountId, polled.counting(outcome))
            }

            RootProbe.Unchanged -> polled.copy(unchanged = polled.unchanged + 1)

            is RootProbe.Walk -> walkAccount(
                Pass(session, accountId, passStartedAt, roots, probe.force, limit),
                probe,
                cursor,
                polled,
            )
        }
    }

    /**
     * SPEC §7.6 steps 1 and 4 — the one request that usually ends the pass.
     *
     * `nc:data-fingerprint` is checked **before** the ETag, because a restored backup makes every
     * cached ETag describe a file that moved *backwards*: matching one there would prove the
     * opposite of what a match usually proves. The cache is dropped and the walk runs unpruned.
     *
     * A first pass has no stored fingerprint, and that is not a change — recording it without
     * invalidating anything is the difference between "an admin restored a backup" and "we have
     * never asked before".
     */
    private suspend fun probeRoot(
        session: CloudStore,
        roots: List<String>,
        cursor: SyncCursor,
        passStartedAt: Long,
    ): RootProbe {
        val ancestor = RemoteWalk.commonAncestor(roots)
        val resource = when (val outcome = session.listing(ancestor, ListingDepth.SELF)) {
            is DavOutcome.Failed -> return RootProbe.Failed(ancestor, outcome.error)

            is DavOutcome.Ok -> outcome.value.firstOrNull()
                ?: return RootProbe.Failed(ancestor, DavFailure.NotFound(ancestor))
        }

        val fingerprintMoved = cursor.dataFingerprint != null &&
            resource.dataFingerprint != null &&
            resource.dataFingerprint != cursor.dataFingerprint
        if (fingerprintMoved) {
            pull.directories.clearFor(session.account.id)
            pull.cursors.upsert(
                cursor.copy(
                    dataFingerprint = resource.dataFingerprint,
                    rootEtag = null,
                    lastReconcileAtEpochMillis = passStartedAt,
                ),
            )
            return RootProbe.Walk(resource.etag, resource.dataFingerprint, force = true)
        }

        // ⭐ SPEC §7.6 step 4's *actual* protection, wired. `nc:data-fingerprint` is not
        // retrievable from a real Nextcloud (a 404 propstat on 32.0.13 — SPEC §7.6), so the
        // branch above never fires in production, and the periodic unpruned walk is the only
        // thing that covers a restored backup — or any other way ETag pruning can lie. It was
        // write-only for two milestones: [finish] maintained the stamp and nothing read it.
        // A due walk beats the unchanged short-circuit below, because a restore can leave the
        // root ETag looking exactly as it did. Nothing is cleared here — an unpruned walk
        // re-reads every directory anyway and concludes deletions under the same rules as any
        // other walk — and the stamp advances only when a forced walk *completes* ([finish]),
        // so an interrupted one stays owed. A cursor that has never recorded one (0) reads as
        // maximally stale, which on a real clock makes an install's — or an upgrade's — first
        // pass the full walk, at the cost of nothing on a fresh install (there is no cache to
        // decline to prune).
        val fullWalkDue = passStartedAt - cursor.lastFullAtEpochMillis >= FULL_WALK_INTERVAL_MILLIS

        // ⚠️ `RemoteWalk.unchanged`, not `==`. A weak validator says "semantically equivalent",
        // which is exactly not what is being asked, and treating one as a match ends the pass
        // before it starts — losing the pull with no error anywhere (SPEC §7.3).
        if (!fullWalkDue && RemoteWalk.unchanged(cursor.rootEtag, resource.etag)) {
            pull.cursors.upsert(
                cursor.copy(
                    dataFingerprint = resource.dataFingerprint ?: cursor.dataFingerprint,
                    lastReconcileAtEpochMillis = passStartedAt,
                ),
            )
            return RootProbe.Unchanged
        }
        return RootProbe.Walk(resource.etag, resource.dataFingerprint, force = fullWalkDue)
    }

    /**
     * SPEC §7.6 step 2 — breadth-first from every root, pruning as it goes.
     *
     * Breadth-first rather than recursive, so the pass is a loop with a queue and cannot blow a
     * stack on somebody's deeply nested folder tree, and so the [Pass.limit] is checked between
     * directories rather than only between roots.
     */
    private suspend fun walkAccount(
        pass: Pass,
        probe: RootProbe.Walk,
        cursor: SyncCursor,
        report: ReconcileReport,
    ): ReconcileReport {
        var running = report
        val queue = ArrayDeque(pass.roots)
        val listed = HashSet<String>()
        val visited = Visited()

        // ⚠️ `!visited.refused` is SPEC §7.2's *"nothing else is sent to it, and that includes the
        // rest of its tree"*. It was a `queue.clear()` in the listing branch only, so a 401 on a
        // **download** left the walk running and sent one more PROPFIND on an account that had just
        // been flagged NEEDS_REAUTH. A flag on the pass covers every branch that can meet one.
        while (queue.isNotEmpty() && visited.acted < pass.limit && !visited.refused) {
            val path = queue.removeFirst()
            if (!listed.add(path)) continue
            running = when (val step = list(pass, path)) {
                is Listing.Failed -> {
                    val outcome = ReconcileOutcome.Failed(null, path, step.failure)
                    record(outcome, pass.accountId)
                    // ⚠️ The *parent* is what must not be cached. Its ETag would otherwise be
                    // stored as "fully reconciled" over a subtree nobody could read, and rule 1's
                    // propagation then prunes that subtree on every later pass until something
                    // else in it changes — so the notes in it stop syncing and the poll keeps
                    // reporting success.
                    //
                    // `owed = false` for a 401: the subtree is unreconciled, but asking WorkManager
                    // to come back for it is the credential retry loop rule 5 forbids.
                    visited.refused = visited.refused || step.failure == DavFailure.Unauthorized
                    visited.unsettled(path.substringBeforeLast('/', ""), owed = !visited.refused)
                    halting(step.failure, pass.accountId, running.counting(outcome))
                }

                is Listing.Absent -> running

                is Listing.Ok -> {
                    queue += step.walk.descend
                    visited.walked += step.walk.self.path
                    visited.directories += step.walk.self
                    visitFiles(pass, step.walk, visited, running.copy(pruned = running.pruned + step.walk.pruned.size))
                }
            }
        }

        // Anything still queued was never listed, so its subtree was not reconciled and its
        // parent must not be cached as though it had been. Owed unless a 401 ended the walk — see
        // the loop condition.
        for (path in queue) visited.unsettled(path.substringBeforeLast('/', ""), owed = !visited.refused)
        return finish(pass, probe, cursor, visited, running.copy(walked = running.walked + visited.walked.size))
    }

    private suspend fun list(pass: Pass, path: String): Listing =
        when (val outcome = pass.session.listing(path, ListingDepth.CHILDREN)) {
            is DavOutcome.Failed ->
                // A root the user deleted is not a failure worth stopping for. There is nothing
                // under it to reconcile — and nothing to trash either, because the bindings that
                // were under it go unstamped *and* their directory never enters the walked set.
                //
                // ⚠️ [Listing.Absent] and **not** an empty [Listing.Ok], which is what this was.
                // An empty `Ok` put the missing path straight into `visited.walked`, and `walked` is
                // the one guard between an unstamped binding and the trash — so renaming a bound
                // folder in the Nextcloud web UI (SPEC §7.4 rule 2: a move, not a delete) trashed
                // and unbound every note under it while the files sat untouched on the server. A
                // 404 on a walk root means *look elsewhere*, never *everything under it is gone*.
                if (outcome.error is DavFailure.NotFound) {
                    Listing.Absent
                } else {
                    Listing.Failed(outcome.error)
                }

            is DavOutcome.Ok -> Listing.Ok(
                RemoteWalk.plan(
                    accountId = pass.accountId,
                    path = path,
                    listing = outcome.value,
                    cached = pass.cached(),
                    nowEpochMillis = pass.passStartedAt,
                    // ⚠️ Rule 3. The DAV root is walked for its files only; descending from there
                    // is a crawl of the user's photo library.
                    allowDescend = path.isNotEmpty(),
                    force = pass.force,
                ),
            )
        }

    // -----------------------------------------------------------------------
    // One remote file — SPEC §7.6 step 3
    // -----------------------------------------------------------------------

    private suspend fun visitFiles(
        pass: Pass,
        walk: WalkStep,
        visited: Visited,
        report: ReconcileReport,
    ): ReconcileReport {
        var running = report
        for (file in walk.files) {
            if (visited.acted >= pass.limit) {
                // Cut short. The directory holds a file nobody looked at, so it is not settled —
                // and ⚠️ that is what stops the *rest of the directory* being read as deleted
                // remotely. `isAbsentRemotely` asks whether the parent was walked **and settled**;
                // before it asked only "walked", so on a library bigger than the limit every bound
                // note past file 25 went to the trash with its remote file untouched on the server.
                visited.unsettled(walk.self.path, owed = true)
                break
            }
            val outcome = visit(pass, walk, file, visited)
            record(outcome, pass.accountId)
            running = running.counting(outcome)
            if (outcome is ReconcileOutcome.Failed && outcome.failure == DavFailure.Unauthorized) {
                // The account is refused, so the walk ends here (see [walkAccount]'s loop) and this
                // directory is unsettled but **not owed** — rule 5 again.
                visited.refused = true
                visited.unsettled(walk.self.path, owed = false)
                return halting(outcome.failure, pass.accountId, running)
            }
            if (outcome is ReconcileOutcome.Deferred || outcome is ReconcileOutcome.Failed) {
                visited.unsettled(walk.self.path, owed = true)
            }
        }
        return running
    }

    /**
     * SPEC §7.6 step 3's four branches, matched on `oc:id`.
     *
     * `oc:id` and not `oc:fileid` (SPEC §7.3): it is `<padded-fileid><instanceid>` and therefore
     * globally unique, which is what keeps this right if the user ever adds a second instance.
     * And never the path — CLAUDE.md's *"identity is never a file path"*.
     */
    @Suppress("ReturnCount")
    // Three of SPEC §7.6 step 3's branches end here without a download, each for a different
    // reason about identity. Nesting them would bury the one branch that actually fetches.
    private suspend fun visit(pass: Pass, walk: WalkStep, file: DavResource, visited: Visited): ReconcileOutcome {
        val ocId = file.ocId
        val known = ocId?.let { id -> bindings.findByOcId(id).firstOrNull { it.accountId == pass.accountId } }
        // The secondary key SPEC §7.4 requires. A trash-restore changes the fileid, and a proxy
        // that strips `OC-FileId` leaves a successfully uploaded note with a null `remoteOcId` —
        // this is where both are repaired rather than adopted as a second copy of themselves.
        val binding = known ?: bindings.findByPath(pass.accountId, file.path).firstOrNull()
        if (binding == null) return adopt(pass, walk, file, visited)

        visited.seen += binding.noteId

        // SPEC §7.4 rule 2 — a file's ETag does not change when it moves. Same id, same ETag and
        // a new href is therefore a move, and a move is **not** a download.
        if (RemoteWalk.unchanged(binding.lastSyncedEtag, file.etag)) {
            return if (file.path == binding.remotePath) {
                repair(binding, file)
                ReconcileOutcome.Unchanged
            } else {
                moved(pass, walk, binding, file)
            }
        }

        // A weak stored ETag cannot answer "has this changed" at all, so SPEC §7.3's fallback
        // applies: compare `OC-Checksum` instead. Equal checksums mean equal bytes, which is a
        // stronger statement than the ETag would have made even if it had been usable.
        val checksum = file.checksum("md5")
        if (binding.lastSyncedEtag?.weak == true && checksum != null && checksum == binding.lastChecksum) {
            repair(binding, file)
            return ReconcileOutcome.Unchanged
        }

        visited.acted++
        // ⭐ The bytes changed **and** the file may have moved, which for a rename is the ordinary
        // case rather than an exotic one: a rename changes the title inside the bundle and the
        // filename outside it, so a peer sees a new ETag at a new href. [RemoteVersion] carries no
        // path, so without this the binding keeps pointing at the old one — and then this device's
        // next MOVE is from a file that no longer exists (404, parked for a day) and its next PUT
        // goes to a path the server does not have. Because MOVE drains before UPLOAD for one note,
        // *every* later upload of it is stuck behind that park. Found by SPEC §7.7 layer 2's row in
        // its second direction: the peer's rename crossed, and the rename back never left the device.
        val followed = binding.following(file.path)
        if (followed != binding) bindings.upsert(followed)
        return changed(pass, walk, followed, file)
    }

    /** SPEC §7.4 rule 2's whole point: update the path, mirror the folder, spend no bytes. */
    private suspend fun moved(pass: Pass, walk: WalkStep, binding: RemoteBinding, file: DavResource): ReconcileOutcome {
        bindings.upsert(binding.following(file.path).copy(remoteOcId = file.ocId ?: binding.remoteOcId))
        folderFor(pass, walk)?.let { gate.noteFolderChanged(binding.noteId, it) }
        return ReconcileOutcome.Moved(binding.noteId, binding.remotePath, file.path)
    }

    /**
     * The file is at [path]; make the binding say so — **without cancelling a rename this device has
     * already queued.**
     *
     * The two columns mean different things (SPEC §7.6): `remotePath` is where the note *should* be
     * and `movedFromPath` is where it still *is*. So a binding with a MOVE pending keeps its
     * destination and has its **source** corrected, and one with nothing pending simply follows the
     * file. Overwriting `remotePath` in the first case would silently undo a rename the user has
     * already been shown in the library.
     */
    private fun RemoteBinding.following(path: String): RemoteBinding = when {
        path == remotePath || path == movedFromPath -> this
        movedFromPath != null -> copy(movedFromPath = path)
        else -> copy(remotePath = path)
    }

    /**
     * REMOTE EDIT — the branch the whole milestone is about.
     *
     * Download, extract, merge, then the outbox decision table. `localNovel` is read from the
     * revisions **before** the merge, because afterwards the local note is a superset of both and
     * the question is unanswerable.
     */
    private suspend fun changed(
        pass: Pass,
        walk: WalkStep,
        binding: RemoteBinding,
        file: DavResource,
    ): ReconcileOutcome {
        val localNovel = (notes.localRev(binding.noteId) ?: 0L) > binding.lastSyncedRev
        val staged = when (val fetch = fetch(pass, binding.noteId, file)) {
            is Fetch.Failed -> return ReconcileOutcome.Failed(binding.noteId, file.path, fetch.failure)
            is Fetch.Ok -> fetch
        }

        val folderId = folderFor(pass, walk)
        val extracted = extractor.extract(staged.pdfPath, staged.bundlePath)
        // SPEC §5.6 inverted: the marker's documentId *is* identity, so a file at this note's
        // path carrying somebody else's is a conflicted copy and not a merge. `NoteMerge` refuses
        // it too (`DifferentDocument`); catching it here saves opening a session to be told.
        val foreignId = extracted.documentId != null && extracted.documentId != binding.noteId
        if (extracted.verdict != RemoteBundleVerdict.NORMAL || !extracted.bundleWritten || foreignId) {
            return layerThree(pass, folderId, binding.noteId, file, staged, extracted.verdict, extracted.schemaVersion)
        }

        val commit = gate.merge(binding.noteId, staged.bundlePath)
        return apply(Merge(pass, folderId, binding, file, staged, localNovel), commit)
    }

    /**
     * ⭐ SPEC §7.6's outbox decision table — **the termination argument for the ETag ping-pong.**
     *
     * | `localNovel` | `remoteNovel` | what happens |
     * |---|---|---|
     * | no | no | ETag advances, `SYNCED`. **No UPLOAD.** |
     * | no | yes | ETag *and* revision advance, `SYNCED`. **No UPLOAD.** |
     * | yes | — | ETag advances **first**, then one UPLOAD carrying `If-Match:` that ETag. |
     *
     * The obvious wiring — reporting a merge through the library's ordinary save path, which ends
     * in `SyncEngine.noteChanged` — uploads unconditionally. That changes the remote ETag, the
     * peer sees a remote change on its next poll, merges nothing novel, and uploads back. Forever,
     * at the poll interval, looking exactly like healthy sync.
     *
     * With the table: both devices upload once after a real merge, the loser's PUT 412s, the 412
     * schedules a reconcile of that one note, and *that* pass finds `localNovel = false` and stops.
     * Bounded at two round trips per conflict.
     *
     * The ETag is advanced **before** the enqueue, and that ordering is the other half: an UPLOAD
     * enqueued against the stale ETag is a guaranteed 412, which would turn one merge into an
     * unbounded 412-reconcile-412 cycle instead.
     */
    private suspend fun apply(merge: Merge, commit: MergeCommit): ReconcileOutcome {
        val noteId = merge.binding.noteId
        return when (val merged = commit.report) {
            // The gate saw something the extractor could not. Both go to layer 3, and neither
            // advances the ETag — so the pending UPLOAD behind this note 412s and parks rather
            // than overwriting a file this build cannot read.
            is MergeReport.TooNew -> merge.toLayerThree(RemoteBundleVerdict.TOO_NEW, merged.remoteVersion)

            is MergeReport.DifferentDocument -> merge.toLayerThree(RemoteBundleVerdict.NORMAL, 0)

            is MergeReport.Merged -> {
                recordPeer(noteId, merged, merge.pass.passStartedAt)
                discard(noteId, merge.staged)
                engine.remoteAdvanced(
                    noteId,
                    RemoteVersion(
                        etag = merge.staged.etag ?: merge.file.etag,
                        ocId = merge.file.ocId,
                        checksum = merge.staged.md5Hex,
                        // Row 2 of the table only. In row 3 the local note is ahead of these
                        // bytes, and claiming otherwise is SPEC §7.6's mid-flight bug one layer up.
                        syncedRev = commit.localRev.takeIf { !merge.localNovel },
                    ),
                )
                if (merge.localNovel) {
                    notes.setSyncState(noteId, NoteSyncState.PENDING_UPLOAD, null)
                    engine.noteChanged(noteId)
                } else {
                    notes.setSyncState(noteId, NoteSyncState.SYNCED, null)
                }
                ReconcileOutcome.Merged(noteId, merged.remoteNovelOps, merged.overriddenTitle)
            }
        }
    }

    /**
     * SPEC §7.6 step 3, third branch — an `oc:id` and a path this device has never seen.
     *
     * The marker is the only thing that can tell a peer's note from a stranger's Acrobat markup,
     * and the marker lives in the PDF catalog — so this costs a whole download to answer, each
     * time the directory's ETag moves. That is the price of SPEC §5.6's identity-lives-in-the-file
     * rule, and it is worth paying: the alternative is trusting a filename.
     *
     * Four answers. No marker → **ignore entirely**, no library row and no attention row. A marker
     * naming a note we hold → the binding lost its `oc:id` to a proxy; repair it and merge on the
     * next pass. A readable marker naming a note we do not hold → a peer created it, install it.
     * An unreadable one → layer 3.
     *
     * ⚠️ **And the answer is remembered** ([ForeignRemote]). It was not, and a directory's ETag moves
     * every time any note in it uploads — so a handout beside the notes was re-downloaded in full on
     * every save, and `limit` strangers ahead of the notes in listing order starved the pass
     * permanently while the poll reported success.
     */
    @Suppress("ReturnCount") // Five distinct answers about identity, each one ending the branch.
    private suspend fun adopt(pass: Pass, walk: WalkStep, file: DavResource, visited: Visited): ReconcileOutcome {
        val fileKey = stagingKey(file)
        // Before `acted++`, deliberately: a file already known not to be ours costs neither a
        // request nor a unit of the pass budget, and the budget is what the livelock was made of.
        if (knownForeign(pass, fileKey, file)) return ReconcileOutcome.Ignored(file.path, NOT_OURS)

        visited.acted++
        val folderId = folderFor(pass, walk)
        val staged = when (val fetch = fetchTo(pass, "${NoteLayout.DOWNLOAD_DIR}/$fileKey", file)) {
            is Fetch.Failed -> return ReconcileOutcome.Failed(null, file.path, fetch.failure)
            is Fetch.Ok -> fetch
        }

        val extracted = extractor.extract(staged.pdfPath, staged.bundlePath)
        val remoteId = extracted.documentId
        if (extracted.verdict == RemoteBundleVerdict.FOREIGN || remoteId == null) {
            rememberForeign(pass, fileKey, file, staged)
            discard(null, staged)
            return ReconcileOutcome.Ignored(file.path, NOT_OURS)
        }

        val existing = bindings.find(remoteId)
        if (existing != null) {
            // Ours, and bound — most often to this very path, with a null `remoteOcId` because a
            // reverse proxy stripped `OC-FileId` from the PUT response. Repairing the id rather
            // than adopting a second copy is what stops SPEC §5.6's duplication failure happening
            // in reverse. Stamped as seen, or the deletion branch would read it as absent.
            bindings.upsert(existing.copy(remotePath = file.path, remoteOcId = file.ocId ?: existing.remoteOcId))
            visited.seen += remoteId
            discard(null, staged)
            return ReconcileOutcome.Deferred(remoteId, file.path, "binding repaired; merging on the next pass")
        }

        if (extracted.verdict != RemoteBundleVerdict.NORMAL || !extracted.bundleWritten) {
            return layerThree(pass, folderId, null, file, staged, extracted.verdict, extracted.schemaVersion)
        }

        val installed = gate.install(
            RemoteInstall(
                noteId = remoteId,
                accountId = pass.accountId,
                remotePath = file.path,
                pdfPath = staged.pdfPath,
                bundlePath = staged.bundlePath,
                folderId = folderId,
            ),
        )
        if (installed == null) {
            // Not an error the pass stops on. Nothing was bound, so nothing claims a version it
            // does not have, and the next pass tries again with the file still on the server.
            discard(null, staged)
            return ReconcileOutcome.Deferred(remoteId, file.path, "could not install the peer's note")
        }
        // ⚠️ `bindPulled`, not `bind`. A note that arrived by download owes the server nothing, and
        // binding it as though the user had just asked to save it enqueues an UPLOAD whose
        // `If-None-Match: *` 412s against the very file it was created from — permanently, flagging
        // a freshly adopted note `CONFLICTED`. Found by SPEC §13.2 row 1; see `SyncEngine.bindPulled`.
        engine.bindPulled(
            noteId = installed,
            accountId = pass.accountId,
            remotePath = file.path,
            observed = RemoteVersion(
                etag = staged.etag ?: file.etag,
                ocId = file.ocId,
                checksum = staged.md5Hex,
                // Unused by `bindPulled` (it takes `localRev` directly), but the record of what the
                // download reported is the same four facts everywhere else in this class.
                syncedRev = null,
            ),
            localRev = notes.localRev(installed) ?: 0L,
        )
        folderId?.let { gate.noteFolderChanged(installed, it) }
        visited.seen += installed
        discard(null, staged)
        return ReconcileOutcome.Adopted(installed, file.path)
    }

    /**
     * SPEC §7.7 layer 3 — unmergeable. Keep both, tell nobody synchronously, block nothing.
     *
     * The **local note is not touched at all**: it keeps its `DocumentId`, its journal, its undo
     * stack and its binding. What changes is that its ETag is deliberately *not* advanced, so the
     * pending UPLOAD behind it 412s at the drainer and parks (SPEC §7.7 rule 3) instead of
     * overwriting a remote file this build cannot read.
     *
     * The peer's bytes are preserved by **leaving them where they are** — untouched on the server,
     * at a path the attention row carries. A local copy is made only when a readable bundle came
     * out of the file, because there is nothing to make a note out of otherwise: a `TOO_NEW`
     * bundle cannot be opened by this build by definition, and a `RECOVERED` one has no intact
     * bundle to open. Recovering either would rewrite it in this build's vocabulary under the
     * original `documentId`, stamped past every clock it has ever seen — so it would win the next
     * sync and take the newer, or the full-fidelity, data with it.
     *
     * ⚠️ **And exactly one copy, however many passes get here.** Not advancing the ETag means this
     * same file comes back on every poll, so "keep both" without a key was "keep one more each time"
     * — see [existingCopy], which is read before anything is minted.
     */
    @Suppress("LongParameterList") // Which file, what came out of it, and which of the three triggers.
    private suspend fun layerThree(
        pass: Pass,
        folderId: FolderId?,
        noteId: DocumentId?,
        file: DavResource,
        staged: Fetch.Ok,
        verdict: RemoteBundleVerdict,
        schemaVersion: Int,
    ): ReconcileOutcome {
        val openable = verdict == RemoteBundleVerdict.NORMAL
        val fileKey = remoteKey(file)
        val already = if (openable) existingCopy(pass, noteId, fileKey) else null
        val title = RemoteName.conflictedCopy(titleOf(file.path), pass.passStartedAt)
        val copy = already ?: gate.conflictedCopy(
            ConflictedCopy(
                accountId = pass.accountId,
                localNoteId = noteId,
                remotePath = file.path,
                pdfPath = staged.pdfPath,
                bundlePath = staged.bundlePath.takeIf { openable },
                title = title,
                folderId = folderId,
            ),
        )
        // Bound, never PUT. CLAUDE.md rule 4 has no layer-3 exemption, and the outbox already
        // knows how to create a file that must not clobber one (`If-None-Match: *`).
        //
        // ⚠️ Only for a copy this pass actually made. [SyncEngine.bind] claims a *fresh unique path*
        // and enqueues an UPLOAD to it, so re-binding the same copy on the next poll would ask the
        // server for a second file under a second name for a note that already has one.
        if (already == null) copy?.let { bind(pass, it, file, folderId, title) }
        noteId?.let { notes.setSyncState(it, NoteSyncState.CONFLICTED, CONFLICT_REASON) }
        discard(noteId, staged)

        return when (verdict) {
            RemoteBundleVerdict.TOO_NEW -> ReconcileOutcome.TooNew(noteId, file.path, schemaVersion)

            RemoteBundleVerdict.RECOVERED, RemoteBundleVerdict.FOREIGN ->
                ReconcileOutcome.NotRecoverable(noteId, file.path)

            RemoteBundleVerdict.NORMAL ->
                ReconcileOutcome.Unmergeable(noteId, file.path, CONFLICT_REASON, copy, fileKey)
        }
    }

    /**
     * ⭐ The copy this conflict already has, if it has one — **one copy per conflict, not one per poll.**
     *
     * Its own function for [isAbsentRemotely]'s reason: this is the whole rule, and the rule is what
     * stands between one unresolvable file and an unbounded number of notes. Layer 3 never advances
     * the ETag, so the file is fetched and refused again on every pass for as long as the condition
     * lasts — which is deliberate, because the condition may clear — and `conflictedCopyOf` mints a
     * new `DocumentId` every time it is asked. Unkeyed, a peer's bundle-less file produced a new local
     * note *and a new bound remote file* per poll: `Lecture (conflicted copy … 120000).pdf`,
     * `… 120500.pdf`, … at the poll interval, none of them removable by anything but the user.
     *
     * The key is the conflict — this local note against this remote file — and the record of it is the
     * attention row, which is never deleted (see [Attention.conflictId]). Two consequences worth
     * stating:
     *
     * - a **dismissal is what remembers the copy**, so the tombstone rule and this one hold each
     *   other up: delete an acknowledged row and layer 3 starts minting again;
     * - a pass killed between the mint and the row can still make a second copy. That is bounded by
     *   process deaths rather than by polls, and neither copy destroys anything, which is why the
     *   alternative — writing the row from in here — is not worth the second place the attention
     *   policy would then live in.
     */
    private suspend fun existingCopy(pass: Pass, noteId: DocumentId?, fileKey: String): DocumentId? =
        pull.attention.find(Attention.conflictId(pass.accountId, noteId, fileKey))?.conflictedCopyNoteId

    // -----------------------------------------------------------------------
    // Download — SPEC §7.6 step 3, and CLAUDE.md rule 10
    // -----------------------------------------------------------------------

    /**
     * Fetch a bound note's remote file, or resume a download a previous pass already made.
     *
     * ⚠️ **Never over `notes/<id>/document.pdf`** — [NoteLayout.download] documents the three
     * separate failures that collapse into that one rule, and the first of them is a torn ZIP
     * inside a PDF, atomically renamed into place.
     *
     * The `pendingDownload` row is written **before** the merge and removed only after it, so a
     * process kill in between is resumable (SPEC §13.3) and the binding never claims a version the
     * local note does not have. Resuming also skips a GET, which for a 40 MB lecture is the
     * difference between a pass finishing and WorkManager killing the job for it.
     */
    private suspend fun fetch(pass: Pass, noteId: DocumentId, file: DavResource): Fetch {
        val resumable = pull.downloads.find(noteId)
        if (resumable != null && resumable.remoteEtag == file.etag && fs.exists(resumable.tempPath)) {
            return Fetch.Ok(
                pdfPath = resumable.tempPath,
                bundlePath = stagedBundle(NoteLayout.download(noteId)),
                etag = resumable.remoteEtag,
                // Preferred over re-hashing the staged file: our own uploads send `OC-Checksum`,
                // so Nextcloud usually has it, and re-reading 40 MB to learn what the PROPFIND
                // already said would be the largest allocation in the pass.
                md5Hex = file.checksum("md5"),
            )
        }

        val staged = fetchTo(pass, NoteLayout.download(noteId).removeSuffix(PDF_EXTENSION), file)
        if (staged is Fetch.Ok) {
            // ⭐ **After the GET, not before it.** `PENDING_DOWNLOAD` means *"bytes are on disk and a
            // merge is owed"* — which is exactly what the `pendingDownload` row beside it means, so
            // the two are written together or not at all. Set ahead of the GET it was a claim about
            // the future, and a download that failed (a dropped connection, a 507, a 404 between the
            // PROPFIND and the GET) left the note stuck in it: nothing on the failure path clears the
            // state, so the tile said "downloading" until some later pass happened to succeed, and
            // SPEC §4.7's compaction gate refused to compact the note for as long as it did. The
            // failure is now reported as `ReconcileOutcome.Failed` and the note's standing is left
            // exactly as the last completed pass left it.
            notes.setSyncState(noteId, NoteSyncState.PENDING_DOWNLOAD, null)
            pull.downloads.upsert(
                PendingDownload(
                    noteId = noteId,
                    accountId = pass.accountId,
                    remotePath = file.path,
                    remoteOcId = file.ocId,
                    remoteEtag = staged.etag,
                    tempPath = staged.pdfPath,
                    fetchedAtEpochMillis = pass.passStartedAt,
                ),
            )
        }
        return staged
    }

    /**
     * GET the whole file to [stem]`.pdf`, atomically.
     *
     * ⚠️ The ETag recorded is **the one off the GET response**, not the one the PROPFIND
     * advertised. They differ whenever somebody saved between the two calls, and it is the ETag of
     * *the bytes actually received* that may become `lastSyncedEtag` after a merge — which is why
     * [WebDavClient.download] exists at all and `get()` was not enough.
     */
    private suspend fun fetchTo(pass: Pass, stem: String, file: DavResource): Fetch {
        val downloaded = when (val outcome = pass.session.download(file.path)) {
            is DavOutcome.Failed -> return Fetch.Failed(outcome.error)
            is DavOutcome.Ok -> outcome.value
        }
        val pdfPath = "$stem$PDF_EXTENSION"
        fs.writeBytesAtomically(pdfPath, downloaded.bytes)
        return Fetch.Ok(
            pdfPath = pdfPath,
            bundlePath = stagedBundle(pdfPath),
            etag = downloaded.etag ?: file.etag,
            md5Hex = Md5.hex(downloaded.bytes),
        )
    }

    /** The row first would leak the file: nothing else knows its name (see [PendingDownload]). */
    private suspend fun discard(noteId: DocumentId?, staged: Fetch.Ok) {
        fs.delete(staged.pdfPath)
        fs.delete(staged.bundlePath)
        noteId?.let { pull.downloads.remove(it) }
    }

    // -----------------------------------------------------------------------
    // End of pass
    // -----------------------------------------------------------------------

    /**
     * SPEC §7.6 step 3, fourth branch, and the cache write — in that order, and both guarded.
     *
     * ⚠️ **"Known id absent remotely" is only decidable for a directory that was walked *and
     * settled*.** The unstamped bindings are *candidates*: a pruned subtree leaves its bindings
     * unstamped too, and so does a subtree whose PROPFIND failed, and so does one the limit stopped
     * short of. So the candidates are intersected with the walked-and-settled set by parent path and
     * everything else is left exactly alone. Skipping that intersection is a hundred notes in the
     * trash on one lucky ETag match.
     *
     * ⚠️ **A directory's ETag is cached only when nothing under it was left unfinished**, and the
     * root ETag only when the whole pass was. A cache row means "fully reconciled at this
     * version"; one written over an unmerged download prunes that subtree on every later pass
     * until something else in it happens to change, and the note stops syncing while the poll
     * reports success.
     */
    private suspend fun finish(
        pass: Pass,
        probe: RootProbe.Walk,
        cursor: SyncCursor,
        visited: Visited,
        report: ReconcileReport,
    ): ReconcileReport {
        bindings.stampSeen(visited.seen, pass.passStartedAt)
        val running = trashAbsent(pass, visited, report)

        val settled = visited.directories.filterNot { it.path in visited.unfinished }
        pull.directories.upsertAll(settled)
        val complete = visited.unfinished.isEmpty() && visited.acted < pass.limit
        pull.cursors.upsert(
            cursor.copy(
                // ⚠️ Written on **every** completed walk, not only when it moved. Storing it only
                // in the moved branch left it null forever on a device that had never seen a
                // restored backup — and `fingerprintMoved` requires a stored value, so SPEC §7.6
                // step 4 was quietly dead. Found by the two tests either side of this line.
                dataFingerprint = probe.dataFingerprint ?: cursor.dataFingerprint,
                rootEtag = probe.rootEtag.takeIf { complete },
                lastReconcileAtEpochMillis = pass.passStartedAt,
                lastFullAtEpochMillis = if (pass.force &&
                    complete
                ) {
                    pass.passStartedAt
                } else {
                    cursor.lastFullAtEpochMillis
                },
                // CLAUDE.md rule 5's cooldown, written here rather than in [halting] because this is
                // the pass's own cursor write and a later one would clobber an earlier one.
                refusedUntilEpochMillis = if (visited.refused) {
                    pass.passStartedAt + REFUSAL_COOLDOWN_MILLIS
                } else {
                    cursor.refusedUntilEpochMillis
                },
            ),
        )
        return running.copy(deferred = running.deferred + visited.owed.size)
    }

    /**
     * SPEC §7.6 step 3, fourth branch — act on the candidates the guards let through.
     *
     * The gate may answer *"not now"*, and the only reason it does is that an editor holds the note
     * (see [RemoteMergeGate.trashRemotelyDeleted]). Then **nothing** happens: the note is not
     * trashed, [SyncEngine.noteDeletedRemotely] is not called so the binding keeps its remote
     * identity and its outbox rows, and no attention row claims a deletion that did not occur. The
     * binding stays unstamped, so the pass after the editor lets go concludes the same thing and
     * acts on it.
     */
    private suspend fun trashAbsent(pass: Pass, visited: Visited, report: ReconcileReport): ReconcileReport {
        var running = report
        val deferred = ArrayList<String>()
        for (candidate in bindings.notSeenSince(pass.accountId, pass.passStartedAt)) {
            if (!isAbsentRemotely(candidate, visited)) continue
            val outcome = resolveAbsent(pass, candidate)
            record(outcome, pass.accountId)
            running = running.counting(outcome)
            if (outcome is ReconcileOutcome.Deferred) deferred += candidate.remotePath.substringBeforeLast('/', "")
        }
        // After the loop, never inside it: unsettling a directory mid-loop would change
        // [isAbsentRemotely]'s answer for every candidate after it in the same directory, which
        // would make the outcome depend on the order the rows came back in.
        for (directory in deferred) visited.unsettled(directory, owed = true)
        return running
    }

    /**
     * ⭐ *"Absent from every folder we walk"* is not *"deleted"* — **ask where the file went first.**
     *
     * SPEC §7.4 rule 2 detects a move as *"same `oc:id` + same ETag + a different href"*, and all
     * three of those are properties of a file the walk **listed**. [RemoteWalk.rootsOf] visits only
     * the account's default folder, the parent of every binding and the pinned folders — so a note
     * the user drags in the Nextcloud web UI into a folder outside that set is listed nowhere, its
     * old directory is walked and settled without it, and this branch concluded a **remote
     * deletion**. Reported from a real tablet: the note went to the local trash while its file sat
     * untouched on the server one folder away. SPEC §13.2 row 11 passed throughout, because its
     * fixture moves the file *within* a walked folder.
     *
     * So the fileid is asked directly ([locate]), and three answers are kept apart because only one
     * of them may reach the trash:
     *
     * - **found** → rule 2, with no href to have compared: follow it, spend no bytes. The stored
     *   ETag stays valid, because a move does not change it.
     * - **found nowhere** → genuinely gone, and the existing path runs. A file in the server's own
     *   trash answers this way too, which is what keeps row 14 working.
     * - **cannot tell** — no search backend, a 405, an unreadable body, no fileid to derive — →
     *   ⚠️ **exactly what this pass would have done without asking.** A failed search is evidence of
     *   nothing: concluding a deletion from one would make an unsupported method delete notes, and
     *   keeping the binding regardless would make a note stop syncing with the poll reporting
     *   success. The walk's conclusion stands either way.
     *
     * ⚠️ **One SEARCH per absent *candidate*, and the guards come first.** [isAbsentRemotely] is what
     * keeps this from being a request per note per pass: a pruned subtree leaves every binding under
     * it unstamped, so searching before that guard would cost one SEARCH per note in the library
     * every time anything anywhere changed. An idle account still costs one `PROPFIND Depth:0`.
     *
     * And it is asked **before** the gate, not after. A note an editor holds refuses the trash and
     * defers forever, which is *"the app shows SYNCED and nothing happens"* — the other half of the
     * reported symptom, and it would survive a fix that only ran when the trash succeeded.
     */
    private suspend fun resolveAbsent(pass: Pass, candidate: StaleBinding): ReconcileOutcome {
        val binding = bindings.find(candidate.noteId)
        if (binding != null) {
            val located = locate(pass, binding)
            if (located is FileIdSearch.At) return follow(pass, binding, located.path)
        }
        if (!gate.trashRemotelyDeleted(candidate.noteId, DELETED_REASON)) {
            return ReconcileOutcome.Deferred(candidate.noteId, candidate.remotePath, EDITOR_HOLDS)
        }
        engine.noteDeletedRemotely(candidate.noteId)
        return ReconcileOutcome.DeletedRemotely(candidate.noteId, candidate.remotePath)
    }

    /**
     * Where the file with this binding's fileid is now, or why that is not knowable.
     *
     * The `oc:id` comparison is the guard, not a sanity check: [RemoteBinding.remoteFileId] reads
     * digits off a string, and a hit whose identity cannot be confirmed must not repath a binding
     * onto somebody else's file — the next UPLOAD would land on it. A 207 carrying something we
     * cannot identify is therefore *"cannot tell"* and **not** *"gone"*; only an empty answer is
     * gone.
     */
    private suspend fun locate(pass: Pass, binding: RemoteBinding): FileIdSearch {
        val ocId = binding.remoteOcId ?: return FileIdSearch.CannotTell
        val fileId = binding.remoteFileId ?: return FileIdSearch.CannotTell
        val found = when (val outcome = pass.session.locate(fileId)) {
            is DavOutcome.Failed -> return FileIdSearch.CannotTell
            is DavOutcome.Ok -> outcome.value
        }
        val match = found.firstOrNull { it.ocId == ocId }
        return when {
            match != null -> FileIdSearch.At(match.path)
            found.isEmpty() -> FileIdSearch.Gone
            else -> FileIdSearch.CannotTell
        }
    }

    /**
     * Point the binding at [path] — and **nothing else**.
     *
     * No download (rule 2: the ETag did not change, so the stored one still describes these bytes),
     * and no library-folder move: [moved] can mirror a folder because the walk handed it the new
     * parent's `oc:id`, and this branch has no walked directory at all. A path is not identity
     * (CLAUDE.md), so matching the new parent against the folder pins by string here would be the
     * one lookup in this class that did.
     *
     * ⭐ **The new parent joins the walk roots by itself, and that is the whole follow-on.**
     * [RemoteWalk.rootsOf] derives roots from `bindingPaths`, so the pass after this one lists the
     * destination folder and reconciles the file there normally — which is what makes a move *plus*
     * an edit converge rather than leaving the note repathed and never read.
     *
     * Stamped seen although no directory was walked: a `SEARCH` that answered with this file's own
     * `oc:id` is a stronger statement than a listing, and leaving it unstamped spends one more
     * SEARCH on every pass until the walk happens to reach the new parent.
     *
     * @return [ReconcileOutcome.Moved] when the path actually changed. Otherwise the file is exactly
     *   where the binding already says it is — a queued MOVE that has not drained, or a race with
     *   the listing — so nothing is concluded and the directory is owed another look. Never a
     *   deletion: the server has just said the file is there.
     */
    private suspend fun follow(pass: Pass, binding: RemoteBinding, path: String): ReconcileOutcome {
        val followed = binding.following(path)
        if (followed != binding) bindings.upsert(followed)
        bindings.stampSeen(listOf(binding.noteId), pass.passStartedAt)
        if (followed == binding) return ReconcileOutcome.Deferred(binding.noteId, path, STILL_ON_THE_SERVER)
        return ReconcileOutcome.Moved(binding.noteId, binding.remotePath, path)
    }

    /**
     * Whether an unstamped binding really means *"the file is gone from the server"*.
     *
     * Its own function rather than three `continue`s in the loop above, because these are the three
     * guards that stand between a walk and a hundred notes in the trash, and they are worth reading
     * as one statement of the rule:
     *
     * 1. the file's **directory was walked and settled** — not pruned, not 404, not failed, not cut
     *    short by the limit — otherwise "we did not see it" only means "we did not look";
     * 2. the binding is still there, because a concurrent unbind is not a deletion;
     * 3. the note was **ever uploaded** (SPEC §7.6) — a note that was never up there cannot have
     *    been deleted from up there, and without this, binding a note offline and polling once
     *    would put it straight in the trash.
     *
     * ⚠️ Guard 1 read `walked` alone, and `walked` says only *"a listing came back"*. Three ways a
     * directory gets in there without its files having been looked at: the pass limit stopped the
     * file loop ([visitFiles]), a 404 was mapped to an empty listing ([list]), or a file in it
     * failed. All three trashed live notes whose files were sitting on the server. `unfinished` is
     * the record of *"and we did not finish"*, so the two together are the rule the KDoc always
     * claimed. Being over-conservative here only ever **keeps** a note.
     */
    private suspend fun isAbsentRemotely(candidate: StaleBinding, visited: Visited): Boolean {
        if (!visited.settled(candidate.remotePath.substringBeforeLast('/', ""))) return false
        return bindings.find(candidate.noteId)?.everUploaded == true
    }

    // -----------------------------------------------------------------------
    // Plumbing
    // -----------------------------------------------------------------------

    private suspend fun record(outcome: ReconcileOutcome, accountId: String) {
        // ⚠️ A 401 is deliberately not surfaced from here. SPEC §7.2's one confirmation call is
        // what distinguishes "that path 401s" — a withdrawn share, a folder an admin locked — from
        // "this app password is dead", and only [halting] has asked. Raising NEEDS_REAUTH on the
        // first 401 would tell the user to sign in again while their password is perfectly fine.
        if (outcome is ReconcileOutcome.Failed && outcome.failure == DavFailure.Unauthorized) return
        add(Attention.entryFor(outcome, accountId, clock.nowEpochMillis()))
    }

    private suspend fun recordReauth(accountId: String) {
        val outcome = ReconcileOutcome.Failed(null, "", DavFailure.Unauthorized)
        add(Attention.entryFor(outcome, accountId, clock.nowEpochMillis()))
    }

    /**
     * ⭐ Write the row — unless the list already says exactly this.
     *
     * Rediscovering the same problem must neither grow the list nor clear a dismissal: an
     * acknowledged row that keeps re-opening itself is a notification loop.
     *
     * ⚠️ **The guard has to see acknowledged rows**, and reading [AttentionLog.open] is how this
     * shipped. `open()` is `acknowledgedAt = 0`, so a dismissed row was never in it: the guard passed
     * and the UPSERT rewrote the row open again. Every outcome layer 3 reaches recurs on *every*
     * pass by design (the ETag is deliberately not advanced so the next pass can find out whether the
     * condition cleared), and the ids are derived from the problem, so the same row re-opened itself
     * inside the poll interval — a dismissal the user could not make stick for a condition like
     * *"the peer's file lost its bundle"*, which may never clear on its own.
     *
     * Something genuinely new still reaches them, by whichever of the two routes it is: a different
     * problem derives a different id ([Attention.idFor]) and is its own row, and the same problem
     * with something new to say ([AttentionEntry.saysTheSameAs]) is rewritten *and* re-opened.
     */
    private suspend fun add(entry: AttentionEntry?) {
        if (entry == null) return
        val known = pull.attention.find(entry.id)
        if (known != null && known.saysTheSameAs(entry)) return
        pull.attention.add(entry)
    }

    /**
     * SPEC §7.2 — a 401 halts the account and **nothing else is sent to it.**
     *
     * The one permitted follow-up is `GET /ocs/v2.php/cloud/user`. If that cannot be *reached*,
     * nothing is concluded: treating unreachable as "the credential is dead" signs the user out
     * every time they walk into a lift.
     *
     * ⚠️ **Called at most once per pass**, because `visited.refused` ends the walk at the first 401
     * (see [walkAccount] and [visitFiles]). It was reachable once per directory holding a refused
     * file, which is N confirmation calls in one pass against SPEC §7.2's *"confirm once"*.
     *
     * ⚠️ **The two answers that are not `false` still cool the account down.** They leave it ACTIVE,
     * and an ACTIVE account is one the next poll walks again sixty seconds later — see
     * [SyncCursor.refusedUntilEpochMillis]. The cooldown is written by the pass's own cursor write,
     * not from here, so that an earlier write cannot be clobbered by a later one.
     */
    private suspend fun halting(failure: DavFailure, accountId: String, report: ReconcileReport): ReconcileReport {
        if (failure != DavFailure.Unauthorized) return report
        val session = sessionFor(accountId) ?: return report
        return if (session.confirmStillAuthorised() == false) {
            accounts.setState(accountId, AccountState.NEEDS_REAUTH, failure.describe)
            recordReauth(accountId)
            report.copy(haltedAccounts = report.haltedAccounts + accountId)
        } else {
            report
        }
    }

    private suspend fun sessionFor(accountId: String): CloudStore? {
        val account = accounts.find(accountId)?.takeIf { it.isUsable } ?: return null
        return storeFactory.open(account)
    }

    /** SPEC §4.7's horizon: what this peer has seen, so `oldestAckedCounter` stops being null. */
    private suspend fun recordPeer(noteId: DocumentId, merged: MergeReport.Merged, nowEpochMillis: Long) {
        val peer = merged.peerDeviceId ?: return
        pull.peers.record(noteId, peer, merged.peerHighestCounter, nowEpochMillis)
    }

    /** A `remoteOcId` a proxy stripped, or a path the server moved. Free, and it is the identity. */
    private suspend fun repair(binding: RemoteBinding, file: DavResource) {
        val ocId = file.ocId ?: binding.remoteOcId
        if (ocId != binding.remoteOcId || file.path != binding.remotePath) {
            bindings.upsert(binding.copy(remoteOcId = ocId, remotePath = file.path))
        }
    }

    /** The library folder mirroring the directory being walked, if any (ROADMAP M6). */
    private suspend fun folderFor(pass: Pass, walk: WalkStep): FolderId? {
        val ocId = walk.self.ocId ?: return null
        return pull.folders.findByOcId(pass.accountId, ocId).firstOrNull()?.folderId
    }

    /**
     * Bind a note the reconciler created, so the **outbox** puts it on the server.
     *
     * Every remote write goes through the outbox (CLAUDE.md rule 4), including a conflicted copy.
     * [SyncEngine.bind] derives the path from the title through the account's own filename rules
     * and makes it unique against the paths already claimed, which is what stops the copy landing
     * on top of the file it exists to preserve.
     */
    private suspend fun bind(pass: Pass, noteId: DocumentId, file: DavResource, folderId: FolderId?, title: String) {
        val folder = file.path.substringBeforeLast('/', "")
        engine.bind(noteId, pass.accountId, title, folder.takeIf { it.isNotEmpty() })
        folderId?.let { gate.noteFolderChanged(noteId, it) }
    }

    private fun titleOf(remotePath: String): String = remotePath.substringAfterLast('/').removeSuffix(PDF_EXTENSION)

    private fun stagedBundle(pdfPath: String): String = pdfPath.removeSuffix(PDF_EXTENSION) + BUNDLE_EXTENSION

    /** Has this exact version of this file already been opened and found not to be ours? */
    private suspend fun knownForeign(pass: Pass, fileKey: String, file: DavResource): Boolean =
        pass.foreign()[fileKey]?.let { stillForeign(it, file) } == true

    /**
     * Write down that a remote `.pdf` has no Inkstone marker in it (SPEC §5.6, [ForeignRemote]).
     *
     * ⚠️ The ETag stored is the one off the **GET**, like every other version this class records: it
     * is the version of the bytes the marker was actually read out of, and the PROPFIND's may already
     * be stale. The PROPFIND's would make the row answer for bytes nobody ever saw.
     */
    private suspend fun rememberForeign(pass: Pass, fileKey: String, file: DavResource, staged: Fetch.Ok) {
        pull.foreign.upsert(
            ForeignRemote(
                accountId = pass.accountId,
                fileKey = fileKey,
                remotePath = file.path,
                etag = staged.etag ?: file.etag,
                checksum = staged.md5Hex,
                seenAtEpochMillis = pass.passStartedAt,
            ),
        )
    }

    /**
     * Whether a remembered "not ours" answer still describes the file the walk is looking at.
     *
     * The same two questions [visit] asks of a bound note's ETag, in the same order and for the same
     * reasons: a strong equal ETag proves the bytes, and a weak one proves nothing so SPEC §7.3's
     * `OC-Checksum` fallback answers instead. A file whose version moved is downloaded again, because
     * a path that held a stranger's scan yesterday can hold a peer's note today.
     */
    private fun stillForeign(known: ForeignRemote, file: DavResource): Boolean =
        RemoteWalk.unchanged(known.etag, file.etag) ||
            (known.checksum != null && known.checksum == file.checksum("md5"))

    /**
     * A remote file's identity, as one string.
     *
     * Its `oc:id`, which is `<padded-fileid><instanceid>` and therefore globally unique; its path
     * only when a proxy stripped `OC-FileId`, because a path is not identity (SPEC §7.4 rule 2 — a
     * file that moved is the same file). Three things key off it: where the file is staged
     * ([stagingKey]), the "not ours" answer ([ForeignRemote.fileKey]) and which conflict a layer-3
     * copy belongs to ([Attention.conflictId]).
     */
    private fun remoteKey(file: DavResource): String =
        file.ocId?.takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) } ?: file.path

    /**
     * Where an unrecognised remote file is staged: [remoteKey], flattened to something legal as a
     * filename.
     *
     * A **stable** key on purpose: a re-run overwrites the same path rather than accumulating one
     * staged copy per pass, and a pass killed before it wrote its `pendingDownload` row leaves a
     * file the next pass reuses instead of one that nothing can name.
     */
    private fun stagingKey(file: DavResource): String =
        "peer-" + remoteKey(file).map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

    /** One account's pass. Everything in it is fixed for the whole walk. */
    private inner class Pass(
        val session: CloudStore,
        val accountId: String,
        val passStartedAt: Long,
        val roots: List<String>,
        val force: Boolean,
        val limit: Int,
    ) {
        private var cache: Map<String, RemoteDirectory>? = null
        private var notOurs: Map<String, ForeignRemote>? = null

        /** Read once per pass. The walk consults it per directory and it cannot change mid-walk. */
        suspend fun cached(): Map<String, RemoteDirectory> = cache
            ?: pull.directories.forAccount(accountId).associateBy { it.path }.also { cache = it }

        /**
         * The "not ours" answers, read **lazily** — a pass that meets no unknown file never asks.
         *
         * Once per pass like [cached], and for the same reason: a row written during the pass is
         * about a file this pass has already visited, so it cannot change an answer still to come.
         */
        suspend fun foreign(): Map<String, ForeignRemote> = notOurs
            ?: pull.foreign.forAccount(accountId).associateBy { it.fileKey }.also { notOurs = it }
    }

    /**
     * One REMOTE EDIT in flight.
     *
     * A holder rather than six parameters, because [apply] would otherwise be past detekt's limit
     * for a reason that is not real — every field is one fact about the same file. It also gives
     * the layer-3 fallback somewhere to live that cannot forget the folder or the staged paths.
     */
    private inner class Merge(
        val pass: Pass,
        val folderId: FolderId?,
        val binding: RemoteBinding,
        val file: DavResource,
        val staged: Fetch.Ok,
        /** Read **before** the merge: afterwards the local note is a superset and cannot answer. */
        val localNovel: Boolean,
    ) {
        suspend fun toLayerThree(verdict: RemoteBundleVerdict, schemaVersion: Int): ReconcileOutcome =
            layerThree(pass, folderId, binding.noteId, file, staged, verdict, schemaVersion)
    }

    /** What the walk has touched. Mutable on purpose: it accumulates across the whole pass. */
    private class Visited {
        /** Directories whose `Depth:1` listing is in hand. Half of the deletion branch's guard. */
        val walked = HashSet<String>()

        /** Cache rows for those directories, written only if their subtree finished. */
        val directories = ArrayList<RemoteDirectory>()

        /** Bindings whose remote file was found. Stamped in one statement at the end. */
        val seen = HashSet<DocumentId>()

        /** Directories holding a file whose reconcile did not conclude. Never cached. */
        val unfinished = HashSet<String>()

        /**
         * The subset of [unfinished] that running again would help with.
         *
         * ⚠️ Not the same set, and the difference is CLAUDE.md rule 5. A subtree a **401** left
         * unread is unfinished — its ETag must not be cached — but it is not *owed*: it becomes
         * [ReconcileReport.deferred], which becomes `shouldRunAgain`, which becomes
         * `Result.retry()`, which is WorkManager's exponential backoff re-sending a refused
         * credential every thirty seconds for five hours. That is the ladder rule 5 forbids, and
         * one set for both jobs was how it was reached.
         */
        val owed = HashSet<String>()

        /** Files downloaded or merged, against the pass limit. A pruned directory costs nothing. */
        var acted = 0

        /**
         * A 401 was met. SPEC §7.2 — the rest of this account's tree gets nothing, whatever the
         * confirmation call went on to say.
         */
        var refused = false

        /** Record that a directory's subtree did not finish, and whether coming back would help. */
        fun unsettled(directory: String, owed: Boolean) {
            unfinished += directory
            if (owed) this.owed += directory
        }

        /** Walked **and** finished: the only state in which "we did not see it" means "it is gone". */
        fun settled(directory: String): Boolean = directory in walked && directory !in unfinished
    }

    private sealed interface RootProbe {
        data object Unchanged : RootProbe

        /**
         * @property dataFingerprint carried through the whole pass and written by [finish], so that
         *   a device which has never seen a restored backup still ends up with a stored value to
         *   compare against the next one.
         */
        data class Walk(val rootEtag: ETag?, val dataFingerprint: String?, val force: Boolean) : RootProbe

        data class Failed(val path: String, val failure: DavFailure) : RootProbe
    }

    /**
     * ⭐ What a fileid `SEARCH` established — and the three answers kept deliberately apart.
     *
     * A `Boolean?` would have collapsed the two that matter: *"the server says nothing has this
     * fileid"* is a fact worth trashing on, and *"this server cannot answer"* is not a fact at all.
     * Both look like *"not found"* from the call site, and only one of them may destroy a note.
     * See [resolveAbsent].
     */
    private sealed interface FileIdSearch {
        /** The file exists, here. Whatever the walk did or did not list, this is never a deletion. */
        data class At(val path: String) : FileIdSearch

        /** 207, and nothing on the server has that fileid. Includes a file in the server's trash. */
        data object Gone : FileIdSearch

        /** No usable answer. The conclusion the walk had already reached stands, unchanged. */
        data object CannotTell : FileIdSearch
    }

    private sealed interface Listing {
        data class Ok(val walk: WalkStep) : Listing

        /**
         * A 404. Neither a walk nor a failure: **nothing is known** about what was under it.
         *
         * Its own variant rather than an empty [Ok] because an empty `Ok` is a *claim* — "we listed
         * it and it holds nothing" — and that claim, carried into `visited.walked`, is what turned a
         * folder renamed in the web UI into a mass trashing (see [list]).
         */
        data object Absent : Listing

        data class Failed(val failure: DavFailure) : Listing
    }

    /**
     * Where a remote file's bytes are staged, and what came back with them.
     *
     * @property md5Hex null when the server published no `oc:checksums` for a resumed download.
     *   Null means *"leave `lastChecksum` alone"* — see [SyncEngine.remoteAdvanced] — because
     *   clearing it would disarm SPEC §7.3's weak-ETag fallback on the next pass.
     */
    private sealed interface Fetch {
        data class Ok(val pdfPath: String, val bundlePath: String, val etag: ETag?, val md5Hex: String?) : Fetch

        data class Failed(val failure: DavFailure) : Fetch
    }

    private companion object {
        /** How many remote files one pass will download or merge. See [reconcile]'s `limit`. */
        const val DEFAULT_LIMIT = 25

        const val PDF_EXTENSION = ".pdf"

        /** SPEC §5.3's name, staged beside the download rather than inside `notes/<id>/`. */
        const val BUNDLE_EXTENSION = ".inkstone"

        const val CONFLICT_REASON = "This note could not be merged with the copy on the server. Both were kept."

        const val DELETED_REASON = "Deleted on the server. The local copy is in the trash for 30 days."

        /** Not surfaced to anyone — [Attention] says nothing about an [ReconcileOutcome.Ignored]. */
        const val NOT_OURS = "no Inkstone marker"

        const val EDITOR_HOLDS = "the note is open in the editor; trashing it would make it unrestorable"

        /** A `SEARCH` found the file where the binding already says it is. See [follow]. */
        const val STILL_ON_THE_SERVER = "the file is still on the server at the path we hold"

        /**
         * How long an account is left alone after a 401 SPEC §7.2's probe could not confirm.
         *
         * One hour, against a sixty-second poll floor: twenty-four attempts a day instead of one
         * thousand four hundred and forty. The number is a compromise and both directions of it are
         * real. Longer would keep a user who has just fixed a folder permission — or whose captive
         * portal has just let go — waiting for sync with nothing to press. Shorter starts to look
         * like the credential retry loop CLAUDE.md rule 5 exists to prevent, against an instance
         * whose brute-force protection is on by default and counted per IP, which behind a NAT is
         * every device in the building.
         *
         * A **confirmed** dead credential does not use this: that is `NEEDS_REAUTH`, which is zero
         * requests until a human signs in. This is only for the two answers that leave the account
         * ACTIVE — the probe said the credential is alive, or the probe could not be reached.
         */
        const val REFUSAL_COOLDOWN_MILLIS = 60L * 60 * 1000

        /**
         * How often the unpruned walk runs — SPEC §7.6 step 4's backstop cadence, recorded in
         * SPEC in the same change that wired it. A day, and both directions of the compromise
         * are real, the same shape as [REFUSAL_COOLDOWN_MILLIS]'s: shorter approaches "prune
         * nothing", which is request amplification against the user's own server (§7.2);
         * longer leaves a restored backup — or any other way ETag pruning can lie — undetected
         * for that much longer. The cost when it fires is one `Depth:1` PROPFIND per known
         * directory, once a day, which is what a single ordinary edit already costs.
         */
        const val FULL_WALK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
    }
}

/**
 * What one reconcile pass did. Reported to WorkManager so an incomplete pass runs again.
 *
 * @property unchanged accounts whose root ETag matched — SPEC §7.4 rule 1's one-request case, and
 *   the number that should dominate in steady state.
 * @property pruned child collections proven unchanged and therefore never listed. The measure of
 *   whether rule 1 is earning its keep.
 * @property deferred files whose reconcile did not conclude — a download that has not merged, a
 *   gate that could not take the note, a pass cut short by the limit. Their directories were
 *   deliberately left uncached, so the next pass sees them again.
 * @property refused requests the server answered **401**. ⚠️ Its own counter and not [failed],
 *   because [failed] means `shouldRunAgain` — see it.
 * @property skippedAccounts accounts that got **zero requests** because they cannot be used:
 *   signed out, waiting on a uid, or inside the cooldown a 401 opened
 *   ([SyncCursor.refusedUntilEpochMillis]). ⚠️ Excluded from [shouldRunAgain]; see it.
 * @property haltedAccounts accounts a 401 halted (SPEC §7.2). Also excluded.
 * @property busy true when another pass was already in flight and this one did **nothing** — no
 *   account read, no request sent. Every other counter is zero, so a caller that sums reports is
 *   not misled. ⚠️ Included in [shouldRunAgain]; see it.
 */
public data class ReconcileReport(
    val accountsPolled: Int = 0,
    val unchanged: Int = 0,
    val walked: Int = 0,
    val pruned: Int = 0,
    val moved: Int = 0,
    val merged: Int = 0,
    val adopted: Int = 0,
    val ignored: Int = 0,
    val conflicted: Int = 0,
    val trashed: Int = 0,
    val deferred: Int = 0,
    val failed: Int = 0,
    val refused: Int = 0,
    val skippedAccounts: Set<String> = emptySet(),
    val haltedAccounts: Set<String> = emptySet(),
    val busy: Boolean = false,
) {

    /**
     * True when something is still owed and running again later would help.
     *
     * ⚠️ **[skippedAccounts], [haltedAccounts] and [refused] are deliberately absent**, and the
     * first two exactly as `DrainReport.shouldRunAgain` omits them. A halted account is not waiting
     * for a retry — it is waiting for a human. Folding any of them in here would make WorkManager's
     * exponential backoff *be* the credential retry loop CLAUDE.md rule 5 forbids, on a doubling
     * ladder capped at five hours, against a server whose brute-force protection is on by default
     * and shared across a NAT.
     *
     * ⚠️ **[refused] is the subtle one**, and it shipped wrong. SPEC §7.2's confirmation call has
     * three answers, and only *"the credential is dead"* halts the account — so a 401 the probe
     * answered `true` or could not reach left `haltedAccounts` **empty** while the 401 sat in
     * `failed`. `ReconcileWorker` checks halted first and then falls through to `Result.retry()`:
     * the ladder, in full, reached without any code ever "retrying a 401" in the shape the rule
     * names. The counter is separate so the arithmetic cannot be got wrong again by adding a
     * branch.
     *
     * ⭐ **[busy] is included, and it is the one that has to be.** A pass turned away by the
     * single-flight has not decided anything — least of all that the work is done — and the caller
     * most likely to be turned away is the one a 412 scheduled, which is the caller whose whole
     * purpose is that a pass runs *after* it. Coming back cannot become rule 5's ladder either: the
     * pass this retry eventually runs re-reads the account state first, so a halted account is still
     * zero requests and a 401's cooldown ([SyncCursor.refusedUntilEpochMillis]) is still honoured.
     */
    public val shouldRunAgain: Boolean get() = deferred > 0 || failed > 0 || busy

    internal fun skipping(accountId: String): ReconcileReport = copy(skippedAccounts = skippedAccounts + accountId)

    internal fun counting(outcome: ReconcileOutcome): ReconcileReport = when (outcome) {
        is ReconcileOutcome.Unchanged -> this

        is ReconcileOutcome.Moved -> copy(moved = moved + 1)

        is ReconcileOutcome.Merged -> copy(merged = merged + 1)

        is ReconcileOutcome.Adopted -> copy(adopted = adopted + 1)

        is ReconcileOutcome.Ignored -> copy(ignored = ignored + 1)

        is ReconcileOutcome.DeletedRemotely -> copy(trashed = trashed + 1)

        // Not counted here — `finish` adds one per unfinished *directory*, which is the unit the
        // next pass repeats. Counting both would double it and make `shouldRunAgain` unreadable.
        is ReconcileOutcome.Deferred -> this

        // ⚠️ A 401 is [refused], never [failed]. See [shouldRunAgain]: `failed` is what asks
        // WorkManager to come back on its backoff, and coming back is what rule 5 forbids here.
        is ReconcileOutcome.Failed ->
            if (outcome.failure == DavFailure.Unauthorized) copy(refused = refused + 1) else copy(failed = failed + 1)

        is ReconcileOutcome.TooNew,
        is ReconcileOutcome.NotRecoverable,
        is ReconcileOutcome.Unmergeable,
        -> copy(conflicted = conflicted + 1)
    }
}
