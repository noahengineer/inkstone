package dev.inkstone.app.sync

import dev.inkstone.app.library.RemotePdfPick
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.CredentialStore
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.NextcloudCredential
import dev.inkstone.core.sync.AccountState
import dev.inkstone.core.sync.AccountStore
import dev.inkstone.core.sync.BindingStore
import dev.inkstone.core.sync.CachedFolder
import dev.inkstone.core.sync.DavFailure
import dev.inkstone.core.sync.DavOutcome
import dev.inkstone.core.sync.DavResource
import dev.inkstone.core.sync.DownloadedFile
import dev.inkstone.core.sync.LoginFlowResult
import dev.inkstone.core.sync.LoginFlowStart
import dev.inkstone.core.sync.LoginFlowV2
import dev.inkstone.core.sync.NextcloudSession
import dev.inkstone.core.sync.NextcloudTransport
import dev.inkstone.core.sync.OcsClient
import dev.inkstone.core.sync.ProviderKind
import dev.inkstone.core.sync.RemoteFolderCache
import dev.inkstone.core.sync.SyncAccount
import dev.inkstone.core.sync.SyncEngine
import dev.inkstone.core.sync.WebDavClient
import dev.inkstone.core.sync.isRetryable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Accounts: sign in, sign out, browse folders, keep capabilities fresh.
 *
 * The composition point for SPEC §7.1 and §7.9. `:core:sync` owns the protocol; this
 * owns *which* store each piece lands in — and that split matters here more than
 * elsewhere, because the app password and the account row deliberately live in different
 * places (Keystore vs SQLite, SPEC §14).
 */
@Singleton
class NextcloudAccounts @Inject constructor(
    private val transport: NextcloudTransport,
    private val accounts: AccountStore,
    private val credentials: CredentialStore,
    private val bindings: BindingStore,
    private val sync: SyncEngine,
    private val clock: Clock,
    /**
     * ⭐ What the folder browser remembers, so a path can be chosen with no network.
     *
     * ⚠️ Not the reconciler's `DirectoryCache`, and it must never become it — see
     * [RemoteFolderCache]. Nothing written here decides what syncs.
     */
    private val folderCache: RemoteFolderCache,
    /**
     * Every account whose provider is not Nextcloud is handled here instead (docs/CLOUD-PROVIDERS.md §5).
     * The Sync screen has one accounts collaborator; nothing below the checks that reach this changed.
     */
    private val oneDrive: OneDriveAccounts,
) {

    private val loginFlow = LoginFlowV2(transport)
    private val ocs = OcsClient(transport)
    private val ids = IdFactory(clock)

    suspend fun all(): List<SyncAccount> = accounts.all()

    suspend fun find(accountId: String): SyncAccount? = accounts.find(accountId)

    // -----------------------------------------------------------------------
    // Sign in
    // -----------------------------------------------------------------------

    /** SPEC §7.1 — step one. The returned [LoginFlowStart.browserUrl] goes to a Custom Tab. */
    suspend fun beginLogin(serverUrl: String): LoginFlowResult<LoginFlowStart> = loginFlow.begin(serverUrl)

    /**
     * Poll until the user approves, then create the account.
     *
     * The credential is persisted **inside** [LoginFlowV2.awaitApproval], before it
     * returns — SPEC §7.1 rule 3, because the server deletes its row the moment it
     * answers and a crash in between is unrecoverable. That is why the persist lambda is
     * where the account id is minted: the id has to exist before there is anything to key
     * the credential by.
     */
    suspend fun completeLogin(start: LoginFlowStart): LoginResult {
        val accountId = ids.newDeviceId()
        var stored: NextcloudCredential? = null

        val outcome = loginFlow.awaitApproval(
            start = start,
            persist = { credential ->
                // Rule 3: written the instant it arrives, twice — once with an empty uid
                // and again once §7.1 rule 6 has filled it in. See LoginFlowV2's KDoc.
                credentials.put(accountId, credential)
                stored = credential
                accounts.upsert(accountFor(accountId, credential))
            },
            now = clock::nowEpochMillis,
        )

        return when (outcome) {
            is LoginFlowResult.Success -> LoginResult.SignedIn(adopt(accountId, outcome.value))

            // The app password IS saved (rule 3). Only the uid is missing, and
            // [repairPendingUid] finishes the job — so this is not a failure the user has
            // to redo the browser dance for.
            is LoginFlowResult.NeedsUid -> LoginResult.NeedsUid(accountId)

            is LoginFlowResult.TimedOut -> {
                cleanUp(accountId, stored)
                LoginResult.Failed(outcome.advice)
            }

            is LoginFlowResult.Insecure -> {
                cleanUp(accountId, stored)
                LoginResult.Failed("Inkstone only connects over HTTPS. ${outcome.serverUrl} is not.")
            }

            is LoginFlowResult.Failed -> {
                cleanUp(accountId, stored)
                LoginResult.Failed(outcome.reason)
            }
        }
    }

    /**
     * ⭐ Signing in again as a user we already know is **the same account**, not a new one.
     *
     * This is what makes SPEC §7.2's *"stop and prompt for re-login"* actually recoverable.
     * A `NEEDS_REAUTH` account is halted and every `remote_binding.account_id` points at it;
     * minting a fresh id on re-login would leave a second account beside the first and
     * **orphan every bound note** — they would keep pointing at the halted account and
     * would never sync again, silently. The 401 policy would look correct and the recovery
     * from it would not work.
     *
     * Identity is `(serverUrl, uid)`, which is only knowable *after* the poll returns and
     * the uid is fetched — so the provisional id minted for rule 3's immediate write is
     * folded into the existing account here, and the provisional one is removed.
     */
    private suspend fun adopt(provisionalId: String, credential: NextcloudCredential): SyncAccount {
        val existing = accounts.all().firstOrNull {
            it.id != provisionalId &&
                it.uid == credential.uid &&
                it.serverUrl.equals(credential.serverUrl, ignoreCase = true)
        }

        val accountId = existing?.id ?: provisionalId
        if (existing != null) {
            // The credential has to move before the provisional row goes, so a crash in
            // between leaves the app password reachable rather than stranded (rule 3's
            // reasoning applied to the second write).
            credentials.put(accountId, credential)
            credentials.remove(provisionalId)
            accounts.remove(provisionalId)
        }

        // `accountFor` resets `state` to ACTIVE and `lastError` to null, which is the
        // clearing of the NEEDS_REAUTH *and* SIGNED_OUT flags — the whole point of coming
        // back here. `defaultFolder` and the cached capabilities are carried over rather than
        // re-derived, so the folder the user chose once is still the folder they land in.
        val account = accountFor(accountId, credential)
            .copy(defaultFolder = existing?.defaultFolder ?: SyncAccount.DEFAULT_FOLDER)
            .withCapabilities(credential)
        accounts.upsert(account)

        // ⭐ The other half of making sign-out reversible. Every binding this account still
        // holds becomes live again, and only the notes that actually changed while signed out
        // owe an upload — the rest are already on the server, byte for byte, under an ETag
        // this device still holds. Coming back to a library of 200 synced notes costs zero
        // requests, not 200.
        //
        // Must run *after* the upsert above: `resumeAccount` enqueues, and `SyncEngine` now
        // refuses to enqueue for an account that is not yet usable.
        if (existing != null) sync.resumeAccount(accountId)
        return account
    }

    /**
     * SPEC §0.1 #3 — finish any account whose uid was never fetched, and refresh
     * capabilities for the rest.
     *
     * Called at launch. An account stuck in [AccountState.NEEDS_UID] is the recoverable
     * half of SPEC §7.1's rule 3 / rule 6 tension: the password is safe, the WebDAV path
     * is unknown, and one request fixes it.
     */
    suspend fun repairAndRefresh() {
        // An account already flagged NEEDS_REAUTH is not touched at all: CLAUDE.md rule 5
        // means "no request", not "one polite request per launch".
        oneDrive.repairAndRefresh()
        val repairable = accounts.all()
            .filter { it.state != AccountState.NEEDS_REAUTH && it.provider == ProviderKind.NEXTCLOUD }
        for (account in repairable) {
            repair(account, credentials.get(account.id) ?: continue)
        }
    }

    private suspend fun repair(account: SyncAccount, credential: NextcloudCredential) {
        if (credential.uid.isEmpty() || account.uid.isEmpty()) {
            // SPEC §0.1 #3 — one request finishes what §7.1 rule 3 deliberately left half
            // done. Unreachable means try again next launch, not sign the user out.
            val resolved = loginFlow.resolveUid(credential) ?: return
            credentials.put(account.id, resolved)
            accounts.upsert(accountFor(account.id, resolved).withCapabilities(resolved))
            return
        }
        // SPEC §7.9 — fetched once, cached, refetched on a server version change.
        if (account.capabilities == null) accounts.upsert(account.withCapabilities(credential))
    }

    /**
     * SPEC §7.1 — sign out.
     *
     * > `DELETE /ocs/v2.php/core/apppassword`. If it returns non-200, **remove the local
     * > account anyway** (Nextcloud's own guidance).
     *
     * Which is why the outcome is not checked. A server that cannot be reached must not
     * be able to trap the user in a session they asked to end.
     */
    suspend fun signOut(accountId: String): SignOutResult {
        if (isOneDrive(accountId)) return oneDrive.signOut(accountId)
        credentials.get(accountId)?.let { ocs.revokeAppPassword(it) }

        // ⭐ **Paused, not unbound** (M6). Every note on this account stops syncing and its
        // queued rows are dropped, but its `remote_binding` row — the only record of which
        // note lives at which remote path — is kept.
        //
        // This used to call `sync.unbind`, which deleted those rows, and then
        // `accounts.remove`, which deleted the server URL, login name, uid, default folder
        // and cached capabilities too. Signing back in therefore meant re-typing a URL on a
        // tablet and then re-binding **every note individually, choosing its folder again**.
        // For a real library that is not a sign-in, it is a migration — and none of it was
        // required by SPEC §7.1, which asks only that the app password be revoked.
        //
        // The M5 bug that motivated the unbind is fixed properly instead: work is no longer
        // enqueued for an account that cannot send it (`SyncEngine.canSend`), and the drainer
        // drops any row that raced the sign-out. See [AccountState.SIGNED_OUT].
        val paused = sync.pauseAccount(accountId)

        // The secret, and only the secret. SPEC §14 protects the app password; a server URL
        // and a uid are not secrets, and keeping them is what the user is asking for when
        // they sign back into the same account.
        credentials.remove(accountId)
        accounts.setState(accountId, AccountState.SIGNED_OUT, null)
        return SignOutResult(pausedNotes = paused)
    }

    /**
     * Forget an account completely — the row, the bindings, everything local about it.
     *
     * Separate from [signOut] because they are different intentions and only one of them is
     * reversible. This is for "I will not use this server again"; sign-out is for "not now".
     *
     * **Still not a delete of anything remote** (CLAUDE.md rule 1): the notes stay on disk,
     * go back to `LOCAL_ONLY`, and their copies on the server are left exactly where they are.
     */
    suspend fun forgetAccount(accountId: String) {
        if (isOneDrive(accountId)) return oneDrive.forgetAccount(accountId)
        credentials.get(accountId)?.let { ocs.revokeAppPassword(it) }
        for (binding in bindings.forAccount(accountId)) sync.unbind(binding.noteId)
        credentials.remove(accountId)
        accounts.remove(accountId)
        // The remembered folder names go with it. They are one account's private directory
        // structure, and an account the user has asked to be forgotten should not leave its
        // filing system on the device.
        folderCache.clearFor(accountId)
    }

    /**
     * ⭐ Remember where this account's notes should go by default (M6).
     *
     * SPEC §7.6 says *"a default folder per account makes step 2 one tap"*, and the folder
     * browser has always been able to reach any path on the server — but the default itself was
     * the compile-time constant `Inkstone` and nothing could change it. So every note that
     * belonged anywhere else meant navigating out of `Inkstone` by hand, every time, which
     * reads as "the app insists on its own folder".
     *
     * Setting it changes only where the browser *starts* and where an unpinned note lands. It
     * moves nothing that is already on the server: notes keep the `remote_path` they were bound
     * with (CLAUDE.md rule 1 — a settings change is not a licence to move a user's files).
     */
    suspend fun setDefaultFolder(accountId: String, remotePath: String) {
        val account = accounts.find(accountId) ?: return
        accounts.upsert(account.copy(defaultFolder = remotePath.trim('/')))
    }

    /** After the user re-authenticates an account that was flagged `NEEDS_REAUTH`. */
    suspend fun clearReauthFlag(accountId: String) {
        accounts.setState(accountId, AccountState.ACTIVE, null)
    }

    /** @property pausedNotes how many bound notes stopped syncing and will resume on sign-in. */
    data class SignOutResult(val pausedNotes: Int)

    // -----------------------------------------------------------------------
    // Folder browsing — SPEC §7.6 step 2
    // -----------------------------------------------------------------------

    /**
     * One level of the remote tree, folders only (SPEC §7.6: *"remote folder browser
     * (PROPFIND `Depth:1`)"*).
     *
     * ⚠️ `Depth: 1`, always explicitly. Sabre's default when the header is omitted is
     * `infinity` — a folder browser that accidentally walked the user's entire Nextcloud
     * would be a denial of service against their own server (SPEC §7.3), which
     * [WebDavClient.Depth] makes unrepresentable.
     */
    suspend fun browse(accountId: String, remotePath: String): BrowseResult {
        if (isOneDrive(accountId)) return oneDrive.browse(accountId, remotePath)
        val here = remotePath.trim('/')
        val session = session(accountId) ?: return BrowseResult.Unavailable
        return when (val outcome = session.dav.propfind(remotePath, WebDavClient.Depth.One)) {
            is DavOutcome.Ok -> {
                val folders = outcome.value
                    .filter { it.isCollection && it.path.trim('/') != here }
                    .sortedBy { it.name.lowercase() }
                folderCache.remember(
                    accountId = accountId,
                    path = here,
                    folders = folders.map {
                        CachedFolder(
                            accountId = accountId,
                            path = it.path.trim('/'),
                            parentPath = here,
                            name = it.name,
                            ocId = it.ocId,
                        )
                    },
                    nowEpochMillis = clock.nowEpochMillis(),
                )
                BrowseResult.Listing(
                    path = here,
                    folders = folders.map { RemoteFolderEntry(path = it.path.trim('/'), name = it.name) },
                    // The same listing already carried these; only the filter was throwing them
                    // away. A browser that shows them costs no extra request (SPEC §7.3's one
                    // PROPFIND), and the screen decides whether to draw them — see
                    // `RemoteBrowser.pickingPdf`.
                    pdfs = RemotePdfPick.pdfsIn(outcome.value),
                )
            }

            is DavOutcome.Failed -> {
                if (outcome.error is DavFailure.Unauthorized) haltOn401(accountId, session)
                remembered(accountId, here, outcome.error) ?: BrowseResult.Failed(outcome.error.describe)
            }
        }
    }

    /**
     * ⭐ The server cannot be reached — answer from what the browser saw last time.
     *
     * Choosing where a note goes needs no network: [SyncEngine.bind] writes three rows and
     * enqueues MKCOL then UPLOAD without opening a socket, and that MKCOL is unconditional
     * precisely so the folder need not exist yet. The only part that ever needed a connection
     * was *seeing the names*, and this is that part, remembered.
     *
     * ⚠️ **Only for a failure that means "cannot see the server".** A 401 is halted above and
     * must not then be dressed up as a working browser (CLAUDE.md rule 5 — the user has to be
     * sent to sign in, not left choosing folders nothing will ever be written to); a 403 or a 404
     * is the server answering, and answering *about this path*, so serving the cache would be
     * contradicting something we have just been told. [isRetryable] is already this codebase's
     * name for the "could not be told" set, minus [DavFailure.Locked], which is a fact about the
     * folder rather than about the connection.
     *
     * Returns null when the failure is not of that kind, so the caller reports it as before.
     */
    private suspend fun remembered(accountId: String, path: String, error: DavFailure): BrowseResult? {
        val cannotSeeServer = error.isRetryable && error !is DavFailure.Locked
        if (!cannotSeeServer) return null
        val cached = folderCache.childrenOf(accountId, path)
        return BrowseResult.Listing(
            path = path,
            folders = cached.folders.map { RemoteFolderEntry(path = it.path, name = it.name, pending = it.pending) },
            // Deliberately empty. A remembered *file* list would be a list of documents that
            // cannot be opened — adopting one is a GET — so it would be the one dishonest row on
            // this screen.
            pdfs = emptyList(),
            remembered = Remembered(listedAtEpochMillis = cached.listedAtEpochMillis, reason = error.describe),
        )
    }

    /**
     * Download one remote PDF so it can be imported and written on.
     *
     * ⭐ **A UI action, not an outbox op**, for the same reason [createRemoteFolder] is one: the
     * outbox exists so that *writes* survive a dead network and a dead process (CLAUDE.md rule 4),
     * and this is a read the user is standing in front of waiting for. Nothing is mutated remotely.
     *
     * ⚠️ The [DownloadedFile] carries the ETag and `oc:id` **off the GET**, and those are what the
     * binding must record — not the PROPFIND's, which the listing may have been holding for a
     * while. `SyncEngine.bindAdopted` turns them into the `If-Match` that guards the first write
     * back onto this file.
     *
     * A 401 halts the account exactly as a walk would (SPEC §7.1 rule 5): one failure, no retry.
     */
    suspend fun fetchPdf(accountId: String, remotePath: String): FetchResult {
        if (isOneDrive(accountId)) return oneDrive.fetchPdf(accountId, remotePath)
        val session = session(accountId) ?: return FetchResult.Unavailable
        return when (val outcome = session.dav.download(remotePath)) {
            is DavOutcome.Ok -> FetchResult.Downloaded(outcome.value)

            is DavOutcome.Failed -> {
                if (outcome.error is DavFailure.Unauthorized) haltOn401(accountId, session)
                FetchResult.Failed(outcome.error.describe)
            }
        }
    }

    /**
     * Create a folder the user typed a name for, straight away — a UI action, not an outbox op.
     *
     * ⭐ With no network this is [FolderCreateResult.Deferred] rather than a failure: the folder
     * is remembered locally, marked as not-yet-on-the-server, and the MKCOL that really creates
     * it is the one [SyncEngine.bind] already enqueues unconditionally for every binding. So
     * *"new folder, save the note there"* works on a train, which is the whole point of the cache
     * beside it.
     *
     * ⚠️ Nothing is enqueued **here**. The outbox is keyed by note (SPEC §7.6), so a queued
     * MKCOL with no note behind it would be a row no binding owns — and a folder nobody saves
     * anything into never needs to exist.
     */
    suspend fun createRemoteFolder(accountId: String, remotePath: String): FolderCreateResult {
        if (isOneDrive(accountId)) return oneDrive.createRemoteFolder(accountId, remotePath)
        val session = session(accountId) ?: return FolderCreateResult.Failed("That account needs signing in again.")
        return when (val outcome = session.dav.mkcolRecursive(remotePath)) {
            is DavOutcome.Ok -> FolderCreateResult.Created

            is DavOutcome.Failed -> {
                if (outcome.error is DavFailure.Unauthorized) haltOn401(accountId, session)
                if (outcome.error is DavFailure.Transport) {
                    folderCache.rememberPending(accountId, remotePath.trim('/'), clock.nowEpochMillis())
                    FolderCreateResult.Deferred
                } else {
                    FolderCreateResult.Failed(outcome.error.describe)
                }
            }
        }
    }

    // -----------------------------------------------------------------------

    /**
     * SPEC §7.2 — confirm once, then halt. **Never a retry.**
     *
     * Shared by every interactive call, so a 401 raised while the user is browsing
     * folders flags the account exactly as one raised by the drainer does.
     */
    private suspend fun haltOn401(accountId: String, session: NextcloudSession) {
        if (session.confirmStillAuthorised() == false) {
            accounts.setState(accountId, AccountState.NEEDS_REAUTH, DavFailure.Unauthorized.describe)
        }
    }

    private suspend fun isOneDrive(accountId: String): Boolean =
        accounts.find(accountId)?.provider == ProviderKind.ONEDRIVE

    private suspend fun session(accountId: String): NextcloudSession? {
        val account = accounts.find(accountId)?.takeIf { it.isUsable } ?: return null
        val credential = credentials.get(accountId)?.takeIf { it.uid.isNotEmpty() } ?: return null
        return NextcloudSession(account, credential, transport)
    }

    private fun accountFor(accountId: String, credential: NextcloudCredential) = SyncAccount(
        id = accountId,
        serverUrl = credential.serverUrl,
        loginName = credential.loginName,
        uid = credential.uid,
        state = if (credential.uid.isEmpty()) AccountState.NEEDS_UID else AccountState.ACTIVE,
    )

    private suspend fun SyncAccount.withCapabilities(credential: NextcloudCredential): SyncAccount = copy(
        displayName = ocs.fetchDisplayName(credential).valueOrNull ?: displayName,
        capabilities = ocs.fetchCapabilities(credential).valueOrNull ?: capabilities,
    )

    /**
     * A login that did not complete leaves nothing behind.
     *
     * The app password may already have been persisted by rule 3's first write, and an
     * orphaned credential file for an account that does not exist is both a leak and a
     * secret nobody can revoke by name.
     */
    private suspend fun cleanUp(accountId: String, stored: NextcloudCredential?) {
        if (stored != null) {
            credentials.remove(accountId)
            accounts.remove(accountId)
        }
    }

    sealed interface LoginResult {
        data class SignedIn(val account: SyncAccount) : LoginResult

        /** Signed in, but the WebDAV path segment is not known yet. Recoverable at launch. */
        data class NeedsUid(val accountId: String) : LoginResult

        data class Failed(val reason: String) : LoginResult
    }

    sealed interface BrowseResult {
        data class Listing(
            val path: String,
            val folders: List<RemoteFolderEntry>,
            val pdfs: List<DavResource> = emptyList(),
            /** Null when this listing came off the server just now. */
            val remembered: Remembered? = null,
        ) : BrowseResult

        data class Failed(val reason: String) : BrowseResult

        /** The account is gone, halted, or has no uid — nothing to browse. */
        data object Unavailable : BrowseResult
    }

    /**
     * One folder in the browser — from the server, or from what the browser remembers.
     *
     * A type of its own rather than [DavResource], because half of these rows never came off a
     * PROPFIND and giving them an ETag, a permission string and a content length would be
     * inventing every one of them.
     *
     * @property pending true for a folder the user created with no network. It is a real
     *   destination — the binding's MKCOL creates it — and it is **not on the server yet**, so
     *   the screen marks it rather than drawing it like the others.
     */
    data class RemoteFolderEntry(val path: String, val name: String, val pending: Boolean = false)

    /**
     * Why a listing is being answered from memory, and how old that memory is.
     *
     * @property listedAtEpochMillis when this folder was last listed successfully. **0 means
     *   never**, which is not "empty" — the screen must not draw the two the same way: one is
     *   *"there are no folders in here"* and the other is *"I have never seen inside here"*.
     */
    data class Remembered(val listedAtEpochMillis: Long, val reason: String)

    /** What [createRemoteFolder] did. */
    sealed interface FolderCreateResult {
        data object Created : FolderCreateResult

        /** No network. Remembered locally; the binding's MKCOL is what creates it. */
        data object Deferred : FolderCreateResult

        data class Failed(val reason: String) : FolderCreateResult
    }

    /** What [fetchPdf] came back with. */
    sealed interface FetchResult {
        data class Downloaded(val file: DownloadedFile) : FetchResult

        data class Failed(val reason: String) : FetchResult

        data object Unavailable : FetchResult
    }
}
