package dev.inkstone.app.sync

import dev.inkstone.app.library.RemotePdfPick
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.sync.AccountState
import dev.inkstone.core.sync.AccountStore
import dev.inkstone.core.sync.BindingStore
import dev.inkstone.core.sync.CachedFolder
import dev.inkstone.core.sync.CloudStore
import dev.inkstone.core.sync.DavFailure
import dev.inkstone.core.sync.DavOutcome
import dev.inkstone.core.sync.ListingDepth
import dev.inkstone.core.sync.MicrosoftSignIn
import dev.inkstone.core.sync.NextcloudTransport
import dev.inkstone.core.sync.OAuthSecret
import dev.inkstone.core.sync.OneDriveStoreFactory
import dev.inkstone.core.sync.PendingSignInStore
import dev.inkstone.core.sync.PkceCrypto
import dev.inkstone.core.sync.ProviderKind
import dev.inkstone.core.sync.RemoteFolderCache
import dev.inkstone.core.sync.SignInResult
import dev.inkstone.core.sync.SignedInIdentity
import dev.inkstone.core.sync.SyncAccount
import dev.inkstone.core.sync.SyncEngine
import dev.inkstone.core.sync.TokenVault
import dev.inkstone.core.sync.isRetryable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the Sync screen is told once a sign-in has finished. One value, consumed with [noticeShown].
 */
sealed interface SignInNotice {
    val message: String

    data class SignedIn(val account: SyncAccount, val resumed: Boolean) : SignInNotice {
        override val message: String = buildString {
            append("Signed in to OneDrive as ${account.loginName}.")
            if (resumed) append(" Your notes pick up where they left off.")
        }
    }

    data object Cancelled : SignInNotice {
        override val message: String = "Sign-in cancelled. Nothing was changed."
    }

    data class Failed(val reason: String) : SignInNotice {
        override val message: String = reason
    }
}

/**
 * OneDrive accounts: sign in, sign out, browse, and the one flag a lost token needs
 * (docs/CLOUD-PROVIDERS.md §5).
 *
 * The OneDrive counterpart of [NextcloudAccounts], which delegates to this for every account whose
 * provider is not Nextcloud — so the Sync screen has one accounts collaborator, and a Nextcloud
 * account's behaviour is not touched by any line here.
 *
 * ## The two things that differ from a Nextcloud sign-in
 *
 * - **The redirect arrives somewhere that may outlive the screen.** A Custom Tab returns through
 *   `OAuthRedirectActivity`, possibly into a process Android restarted while the user was on
 *   Microsoft's page. So the completion runs in this singleton's own scope ([onRedirect]), not in a
 *   view model's, and reports through [notice]; the screen, if it still exists, shows it.
 * - **There is nothing to revoke.** Nextcloud's sign-out deletes the app password server-side;
 *   Microsoft offers a public client no equivalent for a refresh token. Sign-out here forgets the
 *   token on this device and says so, with where to revoke the grant for real.
 */
@Singleton
@Suppress("LongParameterList", "TooManyFunctions") // One collaborator per responsibility, one method per press.
class OneDriveAccounts @Inject constructor(
    private val accounts: AccountStore,
    private val vault: TokenVault,
    private val bindings: BindingStore,
    private val sync: SyncEngine,
    private val clock: Clock,
    private val folderCache: RemoteFolderCache,
    private val settings: MicrosoftSettings,
    private val factory: OneDriveStoreFactory,
    transport: NextcloudTransport,
    pending: PendingSignInStore,
    crypto: PkceCrypto,
) {

    private val signIn = MicrosoftSignIn(transport, settings.signIn, vault, pending, crypto, clock)
    private val ids = IdFactory(clock)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val finishing = Mutex()

    private val latest = MutableStateFlow<SignInNotice?>(null)

    /** The result of the last sign-in, until [noticeShown]. */
    val notice: StateFlow<SignInNotice?> = latest.asStateFlow()

    val configured: Boolean get() = settings.configured

    /** What this install must be registered under in the Entra app registration. */
    val redirectUri: String get() = settings.redirectUri

    fun noticeShown() {
        latest.value = null
    }

    // -----------------------------------------------------------------------
    // Sign in
    // -----------------------------------------------------------------------

    /**
     * The URL for the Custom Tab, or null when this build has no client id.
     *
     * @param accountId set when signing in *again* to a known account, so that [onRedirect] can refuse a
     *   different Microsoft identity (docs §5.3) even if the process dies in between.
     */
    suspend fun begin(accountId: String? = null): String? = if (configured) signIn.begin(accountId) else null

    /**
     * The browser came back. Finishes the sign-in in this object's own scope and publishes the result
     * to [notice].
     *
     * Safe to call twice with the same redirect: Android can deliver an intent again (a rotation, a
     * second tap on a stale tab), and the second delivery must not tell a user who has just signed in
     * that "no sign-in was in progress". The pending record is single-use either way, so the repeat
     * would find nothing — this just stops it saying so. A *different* redirect is always processed.
     */
    fun onRedirect(redirect: String) {
        scope.launch {
            finishing.withLock {
                if (redirect == lastRedirect) return@withLock
                lastRedirect = redirect
                latest.value = finish(redirect)
            }
        }
    }

    private var lastRedirect: String? = null

    private suspend fun finish(redirect: String): SignInNotice {
        val provisional = ids.newDeviceId()
        return when (val result = signIn.complete(redirect, provisional)) {
            is SignInResult.Cancelled -> SignInNotice.Cancelled
            is SignInResult.Failed -> SignInNotice.Failed(result.reason)
            is SignInResult.Success -> adoptSafely(provisional, result.identity, result.expectedAccountId)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    // A failure after the token is saved must not leave the token behind with no account to own it.
    private suspend fun adoptSafely(provisional: String, identity: SignedInIdentity, expected: String?): SignInNotice =
        try {
            adopt(provisional, identity, expected)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            vault.remove(provisional)
            SignInNotice.Failed("Signed in, but Inkstone could not save the account: ${failure.message ?: failure}")
        }

    /**
     * The same two steps as Nextcloud's `adopt`: the secret is already saved under a provisional id,
     * and the identity — only known now — decides whether that is a new account or one we remember.
     */
    private suspend fun adopt(provisional: String, identity: SignedInIdentity, expectedId: String?): SignInNotice {
        val expected = expectedId?.let { accounts.find(it) }
        if (expected != null && expected.uid != identity.driveId) {
            vault.remove(provisional)
            return SignInNotice.Failed(
                "That is a different Microsoft account from the one this was set up with " +
                    "(${expected.loginName}). Sign in as ${expected.loginName}, or add it as a new account.",
            )
        }
        val existing = accounts.all().firstOrNull {
            it.provider == ProviderKind.ONEDRIVE && it.uid == identity.driveId && it.id != provisional
        }
        val accountId = existing?.id ?: provisional
        if (existing != null) {
            // The token moves before the provisional copy goes, so a crash in between leaves it reachable.
            vault.get(provisional)?.let { vault.put(accountId, OAuthSecret(it.refreshToken)) }
            vault.remove(provisional)
        }
        val account = SyncAccount(
            id = accountId,
            serverUrl = settings.signIn.graphBase,
            loginName = identity.loginName,
            uid = identity.driveId,
            displayName = identity.displayName,
            defaultFolder = existing?.defaultFolder ?: SyncAccount.DEFAULT_FOLDER,
            state = AccountState.ACTIVE,
            provider = ProviderKind.ONEDRIVE,
        )
        accounts.upsert(account)
        // Throws away the cached access token and — the point — the `invalid_grant` latch, which is
        // per object and would otherwise keep refusing an account that has just signed in.
        factory.forget(accountId)
        if (existing != null) sync.resumeAccount(accountId)
        return SignInNotice.SignedIn(account, resumed = existing != null)
    }

    // -----------------------------------------------------------------------
    // Sign out / forget / repair
    // -----------------------------------------------------------------------

    /** Forget the token on this device, pause the account's notes, keep the row. See the class KDoc. */
    suspend fun signOut(accountId: String): NextcloudAccounts.SignOutResult {
        val paused = sync.pauseAccount(accountId)
        vault.remove(accountId)
        factory.forget(accountId)
        accounts.setState(accountId, AccountState.SIGNED_OUT, null)
        return NextcloudAccounts.SignOutResult(pausedNotes = paused)
    }

    /** Remove the account locally. Nothing on OneDrive is touched (CLAUDE.md rule 1). */
    suspend fun forgetAccount(accountId: String) {
        for (binding in bindings.forAccount(accountId)) sync.unbind(binding.noteId)
        vault.remove(accountId)
        factory.forget(accountId)
        accounts.remove(accountId)
        folderCache.clearFor(accountId)
    }

    /**
     * At launch: an account that believes it is signed in but whose token this device cannot open
     * (a Keystore key that was invalidated, a restore to another device) is flagged for re-sign-in.
     *
     * **No request is made.** Nobody refused anything; the token is simply not here, and asking
     * Microsoft about it would be a credential retry for no reason (CLAUDE.md rule 5).
     */
    suspend fun repairAndRefresh() {
        for (account in accounts.all()) {
            if (account.provider != ProviderKind.ONEDRIVE || account.state != AccountState.ACTIVE) continue
            if (vault.get(account.id) == null) {
                accounts.setState(account.id, AccountState.NEEDS_REAUTH, MISSING_TOKEN_REASON)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Browsing — the interactive reads the Sync screen makes
    // -----------------------------------------------------------------------

    suspend fun browse(accountId: String, remotePath: String): NextcloudAccounts.BrowseResult {
        val here = remotePath.trim('/')
        val store = storeFor(accountId) ?: return NextcloudAccounts.BrowseResult.Unavailable
        return when (val outcome = store.listing(here, ListingDepth.CHILDREN)) {
            is DavOutcome.Ok -> {
                val folders = outcome.value.filter { it.isCollection && it.path.trim('/') != here }
                    .sortedBy { it.name.lowercase() }
                folderCache.remember(
                    accountId = accountId,
                    path = here,
                    folders = folders.map {
                        CachedFolder(accountId, it.path.trim('/'), here, it.name, it.ocId)
                    },
                    nowEpochMillis = clock.nowEpochMillis(),
                )
                NextcloudAccounts.BrowseResult.Listing(
                    path = here,
                    folders = folders.map { NextcloudAccounts.RemoteFolderEntry(it.path.trim('/'), it.name) },
                    pdfs = RemotePdfPick.pdfsIn(outcome.value),
                )
            }

            is DavOutcome.Failed -> {
                if (outcome.error is DavFailure.Unauthorized) haltOn401(accountId, store)
                remembered(accountId, here, outcome.error)
                    ?: NextcloudAccounts.BrowseResult.Failed(outcome.error.describe)
            }
        }
    }

    /**
     * Offline: answer from what the browser saw last time, exactly as the Nextcloud browser does and
     * for the same reason — choosing where a note goes needs no network. Only for a failure that means
     * "cannot see the server"; a 401 is a sign-in problem and must not be dressed up as a working list.
     */
    private suspend fun remembered(
        accountId: String,
        path: String,
        error: DavFailure,
    ): NextcloudAccounts.BrowseResult? {
        if (!(error.isRetryable && error !is DavFailure.Locked)) return null
        val cached = folderCache.childrenOf(accountId, path)
        return NextcloudAccounts.BrowseResult.Listing(
            path = path,
            folders = cached.folders.map {
                NextcloudAccounts.RemoteFolderEntry(path = it.path, name = it.name, pending = it.pending)
            },
            pdfs = emptyList(),
            remembered = NextcloudAccounts.Remembered(cached.listedAtEpochMillis, error.describe),
        )
    }

    suspend fun fetchPdf(accountId: String, remotePath: String): NextcloudAccounts.FetchResult {
        val store = storeFor(accountId) ?: return NextcloudAccounts.FetchResult.Unavailable
        return when (val outcome = store.download(remotePath)) {
            is DavOutcome.Ok -> NextcloudAccounts.FetchResult.Downloaded(outcome.value)

            is DavOutcome.Failed -> {
                if (outcome.error is DavFailure.Unauthorized) haltOn401(accountId, store)
                NextcloudAccounts.FetchResult.Failed(outcome.error.describe)
            }
        }
    }

    suspend fun createRemoteFolder(accountId: String, remotePath: String): NextcloudAccounts.FolderCreateResult {
        val store = storeFor(accountId)
            ?: return NextcloudAccounts.FolderCreateResult.Failed("That account needs signing in again.")
        return when (val outcome = store.ensureFolder(remotePath)) {
            is DavOutcome.Ok -> NextcloudAccounts.FolderCreateResult.Created

            is DavOutcome.Failed -> {
                if (outcome.error is DavFailure.Unauthorized) haltOn401(accountId, store)
                if (outcome.error is DavFailure.Transport) {
                    folderCache.rememberPending(accountId, remotePath.trim('/'), clock.nowEpochMillis())
                    NextcloudAccounts.FolderCreateResult.Deferred
                } else {
                    NextcloudAccounts.FolderCreateResult.Failed(outcome.error.describe)
                }
            }
        }
    }

    /**
     * SPEC §7.2 for OneDrive: confirm once, then halt. [CloudStore.confirmStillAuthorised] is the single
     * permitted follow-up — at most one refresh, never inside the cooldown — and "could not reach
     * Microsoft" is null, which flags nothing.
     */
    private suspend fun haltOn401(accountId: String, store: CloudStore) {
        if (store.confirmStillAuthorised() == false) {
            accounts.setState(accountId, AccountState.NEEDS_REAUTH, DavFailure.Unauthorized.describe)
        }
    }

    private suspend fun storeFor(accountId: String): CloudStore? {
        val account = accounts.find(accountId)?.takeIf { it.isUsable && it.provider == ProviderKind.ONEDRIVE }
        return account?.let { factory.open(it) }
    }

    companion object {
        /** The `lastError` of an account whose token this device cannot open. Shown by the Sync screen. */
        const val MISSING_TOKEN_REASON: String = "This device no longer holds the saved sign-in."
    }
}
