package dev.inkstone.core.sync

import dev.inkstone.core.model.DocumentId
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SPEC §7.6 steps 1, 2 and 4 — the poll, the walk, and which accounts are touched at all.
 *
 * The half of the reconciler that decides **how many requests a pass costs**, which is what makes
 * a note app on a tablet either free to leave running or a denial of service against the user's
 * own server.
 */
class ReconcilerWalkTest {

    private val lecture = DocumentId("note-lecture")

    private companion object {
        /**
         * `Reconciler.FULL_WALK_INTERVAL_MILLIS`, repeated rather than referenced — the
         * companion is private, and if the two diverge the stale-stamp test fails, which is
         * the right failure (the same argument `InvisibleTextLayerTest` makes for the font name).
         */
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }

    // -----------------------------------------------------------------------
    // SPEC §7.6 step 1 — the one-request common case
    // -----------------------------------------------------------------------

    @Test
    fun `an unchanged strong root ETag costs exactly one request`() = runTest {
        // SPEC §7.4 rule 1's whole point, and the number that should dominate in steady state.
        val server = FakeServer().directory("Inkstone", etag = "r1", ocId = "oc-root")
        val h = ReconcileHarness(server)
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))
        h.cursors.upsert(SyncCursor(testAccount.id, rootEtag = ETag("r1", weak = false)))

        val report = h.reconciler.reconcile()

        assertEquals(1, h.requests("PROPFIND"), "the poll must be one Depth:0 when nothing moved")
        assertEquals(0, h.requests("GET"))
        assertEquals(1, report.unchanged)
        assertFalse(report.shouldRunAgain)
    }

    @Test
    fun `a weak root ETag never short circuits`() = runTest {
        // ⚠️ The silent one. A false "unchanged" here loses the pull entirely — the note stops
        // syncing and nothing reports an error, ever. A false "changed" costs one PROPFIND.
        val server = FakeServer()
            .directory("Inkstone", etag = "r1", ocId = "oc-root", weak = true)
        val h = ReconcileHarness(server)
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))
        h.cursors.upsert(SyncCursor(testAccount.id, rootEtag = ETag("r1", weak = true)))

        val report = h.reconciler.reconcile()

        assertEquals(0, report.unchanged, "a weak validator must never end the pass")
        assertEquals(2, h.requests("PROPFIND"), "one Depth:0 probe, then the root walked at Depth:1")
        assertEquals(1, report.walked)
    }

    @Test
    fun `the root ETag is not cached when the pass left work unfinished`() = runTest {
        // A cache row means "fully reconciled at this version". Writing one over unfinished work
        // prunes that subtree on every later pass until something else in it changes — the note
        // stops syncing while the poll keeps reporting success.
        val server = FakeServer()
            .directory("Inkstone", etag = "r1", ocId = "oc-root")
            .directory("Inkstone/Physics", etag = "p1", ocId = "oc-phys")
            .refusing("Inkstone/Physics", HttpStatusCode.ServiceUnavailable)
        val h = ReconcileHarness(server)

        val report = h.reconciler.reconcile()

        assertEquals(null, h.cursors.rows[testAccount.id]?.rootEtag, "an incomplete pass must not cache the root")
        assertEquals(
            emptyList(),
            h.directories.rows.values.filter { it.path == "Inkstone" },
            "the parent of an unreadable subtree is not settled either",
        )
        assertTrue(report.shouldRunAgain)
    }

    @Test
    fun `a completed walk caches every directory it walked and the root`() = runTest {
        val server = FakeServer()
            .directory("Inkstone", etag = "r1", ocId = "oc-root")
            .directory("Inkstone/Physics", etag = "p1", ocId = "oc-phys")
        val h = ReconcileHarness(server)

        val report = h.reconciler.reconcile()

        assertEquals(setOf("Inkstone", "Inkstone/Physics"), h.directories.rows.values.map { it.path }.toSet())
        assertEquals(ETag("r1", weak = false), h.cursors.rows[testAccount.id]?.rootEtag)
        assertEquals(2, report.walked)
        assertFalse(report.shouldRunAgain)
    }

    @Test
    fun `a pruned subtree costs no request and is not read as a mass deletion`() = runTest {
        // ⚠️ The hundred-notes-in-the-trash bug. A pruned directory's bindings go unstamped
        // exactly like a deleted file's, and the only thing that tells them apart is whether the
        // directory was WALKED. Assert both halves at once: no request, and no trashing.
        val server = FakeServer()
            .directory("Inkstone", etag = "r2", ocId = "oc-root")
            .directory("Inkstone/Physics", etag = "p1", ocId = "oc-phys")
            .file("Inkstone/Physics/Lecture.pdf", etag = "f1", ocId = "oc-42")
        val h = ReconcileHarness(server)
        h.bind(lecture, "Inkstone/Physics/Lecture.pdf", "oc-42", ETag("f1", weak = false))
        h.cache("Inkstone/Physics", etag = "p1", ocId = "oc-phys")

        val report = h.reconciler.reconcile()

        assertEquals(1, report.pruned)
        assertEquals(
            listOf("/remote.php/dav/files/noah/Inkstone", "/remote.php/dav/files/noah/Inkstone"),
            h.paths("PROPFIND"),
            "the pruned subtree must not be listed",
        )
        assertEquals(0, report.trashed)
        assertTrue(h.gate.trashed.isEmpty(), "a pruned subtree is not a deletion")
    }

    // -----------------------------------------------------------------------
    // SPEC §7.6 step 4's backstop — the periodic unpruned walk
    // -----------------------------------------------------------------------

    @Test
    fun `a stale full-walk stamp forces an unpruned walk past a matching root ETag`() = runTest {
        // The fingerprint is unretrievable in production (SPEC §7.6), so this is the only path
        // that ever catches a restored backup — and a restore can leave the root ETag exactly
        // as it was, so the check must beat the unchanged short-circuit. This stamp was
        // write-only for two milestones; this test is what makes that unrepeatable.
        val server = FakeServer()
            .directory("Inkstone", etag = "r1", ocId = "oc-root")
            .directory("Inkstone/Physics", etag = "p1", ocId = "oc-phys")
        val h = ReconcileHarness(server)
        h.cache("Inkstone/Physics", etag = "p1", ocId = "oc-phys")
        h.cursors.upsert(
            SyncCursor(testAccount.id, rootEtag = ETag("r1", weak = false), lastFullAtEpochMillis = 1L),
        )
        h.clock.now = 1L + DAY_MILLIS

        val report = h.reconciler.reconcile()

        assertEquals(0, report.unchanged, "a due full walk must not be short-circuited by the root ETag")
        assertEquals(0, report.pruned, "a full walk prunes nothing")
        assertEquals(2, report.walked)
        assertEquals(
            1L + DAY_MILLIS,
            h.cursors.rows[testAccount.id]?.lastFullAtEpochMillis,
            "a completed full walk must advance the stamp",
        )
    }

    @Test
    fun `a fresh full-walk stamp leaves pruning intact and does not advance`() = runTest {
        // The other half of the compromise: the backstop must not become "prune nothing" —
        // request amplification against the user's own server is the failure SPEC §7.2 forbids.
        val server = FakeServer()
            .directory("Inkstone", etag = "r2", ocId = "oc-root")
            .directory("Inkstone/Physics", etag = "p1", ocId = "oc-phys")
            .file("Inkstone/Physics/Lecture.pdf", etag = "f1", ocId = "oc-42")
        val h = ReconcileHarness(server)
        h.bind(lecture, "Inkstone/Physics/Lecture.pdf", "oc-42", ETag("f1", weak = false))
        h.cache("Inkstone/Physics", etag = "p1", ocId = "oc-phys")
        val stamped = h.clock.now
        h.cursors.upsert(SyncCursor(testAccount.id, lastFullAtEpochMillis = stamped))

        val report = h.reconciler.reconcile()

        assertEquals(1, report.pruned, "a fresh stamp must not defeat pruning")
        assertEquals(
            stamped,
            h.cursors.rows[testAccount.id]?.lastFullAtEpochMillis,
            "an ordinary pruned pass must not claim to have been a full walk",
        )
    }

    @Test
    fun `an interrupted full walk leaves the stamp owed`() = runTest {
        // The stamp is a claim — "everything was read at this instant" — and a bounded or
        // refused pass has not earned it. Advancing it anyway would postpone the re-walk a day.
        val server = FakeServer()
            .directory("Inkstone", etag = "r1", ocId = "oc-root")
            .directory("Inkstone/Physics", etag = "p1", ocId = "oc-phys")
            .refusing("Inkstone/Physics", HttpStatusCode.ServiceUnavailable)
        val h = ReconcileHarness(server)
        h.cursors.upsert(SyncCursor(testAccount.id, lastFullAtEpochMillis = 1L))
        h.clock.now = 1L + DAY_MILLIS

        val report = h.reconciler.reconcile()

        assertTrue(report.shouldRunAgain)
        assertEquals(
            1L,
            h.cursors.rows[testAccount.id]?.lastFullAtEpochMillis,
            "an interrupted full walk must stay due",
        )
    }

    @Test
    fun `a fingerprint change clears the directory cache and walks everything`() = runTest {
        // SPEC §7.6 step 4 — an admin restored a backup, so every cached ETag now describes a file
        // that went *backwards*. Invalidate; never mass-re-download, and never mass-trash.
        val server = FakeServer()
            .directory("Inkstone", etag = "r1", ocId = "oc-root", fingerprint = "fp-2")
            .directory("Inkstone/Physics", etag = "p1", ocId = "oc-phys")
        val h = ReconcileHarness(server)
        h.cache("Inkstone/Physics", etag = "p1", ocId = "oc-phys")
        h.cursors.upsert(
            SyncCursor(testAccount.id, dataFingerprint = "fp-1", rootEtag = ETag("r1", weak = false)),
        )

        val report = h.reconciler.reconcile()

        assertEquals(1, h.directories.clears, "the whole cache must go, not one row of it")
        assertEquals(0, report.unchanged, "a matching root ETag must not short-circuit a restored backup")
        assertEquals(0, report.pruned, "a forced walk prunes nothing")
        assertEquals(2, report.walked)
        assertEquals("fp-2", h.cursors.rows[testAccount.id]?.dataFingerprint)
    }

    @Test
    fun `a first pass records the fingerprint without invalidating anything`() = runTest {
        // "We have never asked before" is not "an admin restored a backup".
        val server = FakeServer().directory("Inkstone", etag = "r1", ocId = "oc-root", fingerprint = "fp-1")
        val h = ReconcileHarness(server)

        h.reconciler.reconcile()

        assertEquals(0, h.directories.clears)
        assertEquals("fp-1", h.cursors.rows[testAccount.id]?.dataFingerprint)
    }

    // -----------------------------------------------------------------------
    // CLAUDE.md rule 5 — which accounts are touched
    // -----------------------------------------------------------------------

    @Test
    fun `a needs reauth account gets zero requests and one account level attention row`() = runTest {
        // ⚠️ CLAUDE.md rule 5 means "no request", not "one polite request per launch". At SPEC
        // §7.6's sixty-second poll floor, one request per halted account per poll is a brute-force
        // pattern against the user's own server, on a credential it has already refused.
        val halted = testAccount.copy(state = AccountState.NEEDS_REAUTH)
        val h = ReconcileHarness(FakeServer().directory("Inkstone", "r1", "oc-root"), account = halted)

        val report = h.reconciler.reconcile()

        assertEquals(0, h.recorded.all.size, "a halted account must produce no traffic whatsoever")
        assertEquals(setOf(testAccount.id), report.haltedAccounts)
        assertEquals(listOf(AttentionKind.NEEDS_REAUTH), h.attention.kinds())
        assertEquals(null, h.attention.rows.values.single().noteId, "reauth is about the account, not a file")
        // ⚠️ And it must not ask WorkManager to come back — its backoff would BE the retry loop.
        assertFalse(report.shouldRunAgain)
    }

    @Test
    fun `a signed out account gets zero requests and no alarm`() = runTest {
        // The user's own decision, not the server refusing us. No attention row: signing out is
        // not a problem to review later.
        val out = testAccount.copy(state = AccountState.SIGNED_OUT)
        val h = ReconcileHarness(FakeServer().directory("Inkstone", "r1", "oc-root"), account = out)

        val report = h.reconciler.reconcile()

        assertEquals(0, h.recorded.all.size)
        assertEquals(setOf(testAccount.id), report.skippedAccounts)
        assertTrue(h.attention.rows.isEmpty())
        assertFalse(report.shouldRunAgain)
    }

    @Test
    fun `an account with no resolved uid gets zero requests and no alarm`() = runTest {
        // SPEC §0.1 #3 — recoverable on the next launch, and there is nothing for a user to do.
        val pending = testAccount.copy(state = AccountState.NEEDS_UID, uid = "")
        val h = ReconcileHarness(FakeServer().directory("Inkstone", "r1", "oc-root"), account = pending)

        val report = h.reconciler.reconcile()

        assertEquals(0, h.recorded.all.size)
        assertEquals(setOf(testAccount.id), report.skippedAccounts)
        assertTrue(h.attention.rows.isEmpty())
    }

    @Test
    fun `a 401 mid walk halts the account after exactly one confirmation probe`() = runTest {
        // SPEC §7.2. One `GET /ocs/v2.php/cloud/user`, then nothing — and the rest of the tree is
        // abandoned, because "halted" has to mean the whole account and not the one path.
        val server = FakeServer()
            .directory("Inkstone", etag = "r1", ocId = "oc-root")
            .directory("Inkstone/A", etag = "a1", ocId = "oc-a")
            .directory("Inkstone/B", etag = "b1", ocId = "oc-b")
            .refusing("Inkstone/A", HttpStatusCode.Unauthorized)
            .refusing("Inkstone/B", HttpStatusCode.Unauthorized)
        val h = ReconcileHarness(server)

        val report = h.reconciler.reconcile()

        assertEquals(1, h.server.uidProbes, "SPEC §7.2 permits exactly one confirmation call")
        // The probe answered 200, so the credential is alive and this path is simply forbidden to
        // us — not a reauth. The account stays ACTIVE and the queue is still abandoned.
        assertEquals(AccountState.ACTIVE, h.accounts.rows.getValue(testAccount.id).state)
        assertTrue(
            h.attention.rows.isEmpty(),
            "a first 401 must not tell the user to sign in again — only the confirmation call can",
        )
        assertEquals(1, report.walked, "nothing is sent to an account after a 401")
        // ⚠️ This block used to read `assertTrue(report.failed > 0)`, and it was green while
        // asserting the bug. `failed > 0` is `shouldRunAgain`, and `ReconcileWorker` turns that into
        // `Result.retry()` — WorkManager's EXPONENTIAL/30 s ladder, uncapped in attempts, re-sending
        // the refused credential for five hours. The account is ACTIVE (the probe said so), so
        // `haltedAccounts` cannot save it. A 401 gets its own counter for exactly this reason.
        assertEquals(1, report.refused)
        assertEquals(0, report.failed, "a 401 is not a failure to retry — CLAUDE.md rule 5")
        assertFalse(report.shouldRunAgain, "WorkManager's backoff must not become the retry loop")
        assertEquals(0, report.deferred, "an unread subtree is uncached, but it is not owed either")
    }

    @Test
    fun `a 401 on a download abandons the rest of the account tree`() = runTest {
        // SPEC §7.2's *"once an account is halted nothing else is sent to it, and that includes the
        // rest of its tree"*. The listing branch drained the queue; the **file** branch did not, so a
        // 401 on a GET flagged the account and then sent one more PROPFIND to it. And with the
        // confirmation call answering `true` or unreachable the walk simply continued, calling it
        // again for every later directory holding a refused file — N probes against §7.2's
        // "confirm once".
        val server = FakeServer()
            .directory("Inkstone", etag = "r1", ocId = "oc-root")
            .directory("Inkstone/B", etag = "b1", ocId = "oc-b")
            .file("Inkstone/Lecture.pdf", etag = "f2", ocId = "oc-42")
            .refusing("Inkstone/Lecture.pdf", HttpStatusCode.Unauthorized)
        val h = ReconcileHarness(server)
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))

        val report = h.reconciler.reconcile()

        assertEquals(
            listOf("/remote.php/dav/files/noah/Inkstone", "/remote.php/dav/files/noah/Inkstone"),
            h.paths("PROPFIND"),
            "the Depth:0 probe and the root's Depth:1, and nothing after the 401",
        )
        assertEquals(1, h.server.uidProbes)
        assertEquals(1, report.refused)
        assertFalse(report.shouldRunAgain)
        assertEquals(0, report.trashed, "a tree nobody finished reading holds no deletions")
    }

    @Test
    fun `a refused account is left alone until the cooldown expires`() = runTest {
        // ⚠️ The other half of CLAUDE.md rule 5, and the half `shouldRunAgain` cannot cover. The
        // account is still ACTIVE — the confirmation probe said the credential is alive — so the
        // sixty-second foreground poll walks the whole tree again and 401s again, 1 440 times a day.
        // `SyncCursor.refusedUntilEpochMillis` is what makes the second poll cost nothing.
        val server = FakeServer()
            .directory("Inkstone", etag = "r1", ocId = "oc-root")
            .refusing("Inkstone", HttpStatusCode.Unauthorized)
        val h = ReconcileHarness(server)

        h.reconciler.reconcile()
        val afterFirst = h.recorded.all.size
        h.clock.advance(60_000)
        val second = h.reconciler.reconcile()

        assertEquals(afterFirst, h.recorded.all.size, "a poll inside the cooldown sends nothing at all")
        assertEquals(setOf(testAccount.id), second.skippedAccounts)
        assertFalse(second.shouldRunAgain)

        // And it is a cooldown, not a sign-out: an hour later the account is asked again, because
        // the credential was never confirmed dead and the folder may have been unlocked since.
        h.clock.advance(60L * 60 * 1000)
        h.reconciler.reconcile()
        assertTrue(h.recorded.all.size > afterFirst, "the account must be reachable again once it expires")
    }

    @Test
    fun `a 404 walk root is never read as a mass deletion`() = runTest {
        // ⚠️ The mass-trash path a rename in the Nextcloud web UI opens. SPEC §7.4 rule 2: a folder
        // that moved keeps its `oc:id`, so `Archive` 404ing is *"look elsewhere"* — but a 404 was
        // mapped to an empty `Listing.Ok`, which put `Archive` in `visited.walked`, and `walked` is
        // the one guard between an unstamped binding and the trash. Every note under it went to the
        // local trash, was unbound, and got a "Deleted on the server" row, while the files sat
        // untouched on the server under the new name.
        //
        // Two roots with no common prefix are what make this reachable: the ancestor is `""`, which
        // always exists, so the pass gets past `probeRoot` and into the walk.
        val server = FakeServer()
            .directory("", etag = "root1", ocId = "oc-dav")
            .directory("Inkstone", etag = "r1", ocId = "oc-root")
        val h = ReconcileHarness(server)
        h.bind(lecture, "Archive/Lecture.pdf", "oc-42", ETag("f1", weak = false))

        val report = h.reconciler.reconcile()

        assertEquals(0, report.trashed, "a directory nobody could list proves nothing about its contents")
        assertTrue(h.gate.trashed.isEmpty())
        assertEquals(
            ETag("f1", weak = false),
            h.bindings.rows.getValue(lecture.value).lastSyncedEtag,
            "the binding must keep its remote identity — unbinding here makes a restore a duplicate",
        )
        assertTrue(h.attention.rows.isEmpty(), "nothing was deleted, so nothing is worth telling the user")
    }

    @Test
    fun `a 401 that the confirmation call agrees with halts the account`() = runTest {
        // The other side of SPEC §7.2. The probe 401s too, so the app password really is dead:
        // flag NEEDS_REAUTH, raise the one account-level row, and send nothing else ever.
        val server = FakeServer()
            .directory("Inkstone", etag = "r1", ocId = "oc-root")
            .refusing("Inkstone", HttpStatusCode.Unauthorized)
            .refusingUid()
        val h = ReconcileHarness(server)

        val report = h.reconciler.reconcile()

        assertEquals(AccountState.NEEDS_REAUTH, h.accounts.rows.getValue(testAccount.id).state)
        assertEquals(setOf(testAccount.id), report.haltedAccounts)
        assertEquals(listOf(AttentionKind.NEEDS_REAUTH), h.attention.kinds())
    }

    @Test
    fun `shouldRunAgain excludes skipped and halted accounts`() = runTest {
        // ⚠️ Folding either in makes WorkManager's exponential backoff *be* the credential retry
        // loop CLAUDE.md rule 5 forbids. Asserted on the type directly, because the branch that
        // produces them is only one of several ways they get set.
        val report = ReconcileReport(
            skippedAccounts = setOf("a"),
            haltedAccounts = setOf("b"),
        )

        assertFalse(report.shouldRunAgain)
        assertTrue(report.copy(deferred = 1).shouldRunAgain)
        assertTrue(report.copy(failed = 1).shouldRunAgain)
    }

    @Test
    fun `a root the user deleted is not a failure and trashes nothing`() = runTest {
        // The bindings under a 404 root go unstamped, but their directory never enters the walked
        // set — so the deletion branch cannot reach them. Which is right: we learned nothing.
        val h = ReconcileHarness(FakeServer())
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))

        val report = h.reconciler.reconcile()

        assertEquals(0, report.trashed)
        assertTrue(h.gate.trashed.isEmpty())
    }
}
