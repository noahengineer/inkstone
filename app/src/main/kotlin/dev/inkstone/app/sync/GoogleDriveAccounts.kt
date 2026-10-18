package dev.inkstone.app.sync

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.sync.AccountState
import dev.inkstone.core.sync.AccountStore
import dev.inkstone.core.sync.BearerResult
import dev.inkstone.core.sync.BindingStore
import dev.inkstone.core.sync.GoogleIdentity
import dev.inkstone.core.sync.GoogleIdentityAnswer
import dev.inkstone.core.sync.GoogleIdentityReader
import dev.inkstone.core.sync.NextcloudTransport
import dev.inkstone.core.sync.ProviderKind
import dev.inkstone.core.sync.RemoteFolderCache
import dev.inkstone.core.sync.SyncAccount
import dev.inkstone.core.sync.SyncEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where Google's API is. A value class of its own only so a test can point it at a stand-in; production is
 * always [DEFAULT_API_BASE]. (The same shape as [MicrosoftSettings]: a Hilt constructor and a plain one.)
 */
@Singleton
class GoogleSettings(val apiBase: String) {
    @Inject
    constructor() : this(DEFAULT_API_BASE)

    companion object {
        const val DEFAULT_API_BASE: String = "https://www.googleapis.com"
    }
}

/**
 * Google Drive accounts: sign in, sign out, forget, and the one flag a lost grant needs
 * (docs/CLOUD-PROVIDERS.md §5.2). The Google counterpart of [OneDriveAccounts]; browsing and reading go
 * through the shared cloud-store reads that class holds, so this one is only about *who is signed in*.
 *
 * ## How it differs from OneDrive's
 *
 * - **No browser tab and no redirect.** Google's Android sign-in is Play Services' `AuthorizationClient`:
 *   [begin] asks it, and either a token comes back at once (the grant already stands) or there is a consent
 *   screen to show ([Begin.Consent]); the Activity launches it and hands the result to [complete].
 * - **Nothing is stored.** No refresh token and no secret file: Play Services remembers the grant, so there
 *   is nothing here to seal, rotate or lose. That makes "lost token" mean "Google no longer lists the
 *   permission", which [repairAndRefresh] asks once per launch.
 * - **Sign-out really revokes.** `AuthorizationClient.revokeAccess` exists, so unlike OneDrive the
 *   permission is withdrawn, best effort — and it is only *best* effort, because signing out must work
 *   offline; the grant can also be removed at myaccount.google.com.
 *
 * ⚠️ Scope `drive.file`: this app sees only files it created. A PDF already in the user's Drive cannot be
 * listed or adopted, which is D3's decision and is said on the Sync screen.
 */
@Singleton
@Suppress("LongParameterList")
class GoogleDriveAccounts @Inject constructor(
    private val accounts: AccountStore,
    private val bindings: BindingStore,
    private val sync: SyncEngine,
    private val clock: Clock,
    private val folderCache: RemoteFolderCache,
    private val registry: GoogleTokenRegistry,
    private val authorizer: GoogleAuthorizer,
    private val settings: GoogleSettings,
    transport: NextcloudTransport,
) {

    /** What [begin] needs the caller to do next. */
    sealed interface Begin {
        /** Finished without a screen — the grant already stood, or it failed. */
        data class Done(val notice: SignInNotice) : Begin

        /** Show this (a `PendingIntent`) and pass the returned `Intent` to [complete]. */
        data class Consent(val request: Any) : Begin
    }

    private val reader = GoogleIdentityReader(transport, settings.apiBase)
    private val ids = IdFactory(clock)
    private val lock = Mutex()
    private var pendingExpected: String? = null

    /**
     * @param expectedAccountId set when signing in *again* to a known account, so a different Google identity
     *   is refused rather than becoming a second account that orphans every bound note.
     */
    suspend fun begin(expectedAccountId: String? = null): Begin = lock.withLock {
        val expected = expectedAccountId?.let { accounts.find(it) }
        when (val answer = authorizer.authorize(expected?.loginName)) {
            is GoogleAuthorization.Granted -> Begin.Done(finish(answer.accessToken, expectedAccountId))

            is GoogleAuthorization.NeedsConsent -> {
                pendingExpected = expectedAccountId
                Begin.Consent(answer.request)
            }

            is GoogleAuthorization.Failed -> Begin.Done(SignInNotice.Failed(answer.reason))
        }
    }

    /** The consent screen came back. [data] is its result `Intent`, or null if it was dismissed. */
    suspend fun complete(data: Any?): SignInNotice = lock.withLock {
        val expected = pendingExpected.also { pendingExpected = null }
        if (data == null) return@withLock SignInNotice.Cancelled
        when (val answer = authorizer.fromConsentResult(data)) {
            is GoogleAuthorization.Granted -> finish(answer.accessToken, expected)
            is GoogleAuthorization.NeedsConsent -> SignInNotice.Cancelled
            is GoogleAuthorization.Failed -> SignInNotice.Failed(answer.reason)
        }
    }

    private suspend fun finish(accessToken: String, expectedId: String?): SignInNotice {
        val identity = when (val found = reader.read(accessToken)) {
            is GoogleIdentityAnswer.Found -> found.identity

            is GoogleIdentityAnswer.Missing ->
                return SignInNotice.Failed("Signed in, but Google would not say who you are (${found.why}). Try again.")
        }
        val expected = expectedId?.let { accounts.find(it) }
        if (expected != null && expected.uid != identity.permissionId) {
            return SignInNotice.Failed(
                "That is a different Google account from the one this was set up with (${expected.loginName}). " +
                    "Sign in as ${expected.loginName}, or add it as a new account.",
            )
        }
        return adopt(identity, accessToken)
    }

    private suspend fun adopt(identity: GoogleIdentity, accessToken: String): SignInNotice {
        val existing = accounts.all().firstOrNull {
            it.provider == ProviderKind.GOOGLE_DRIVE && it.uid == identity.permissionId
        }
        val account = SyncAccount(
            id = existing?.id ?: ids.newDeviceId(),
            serverUrl = settings.apiBase,
            loginName = identity.email,
            uid = identity.permissionId,
            displayName = identity.displayName,
            defaultFolder = existing?.defaultFolder ?: SyncAccount.DEFAULT_FOLDER,
            state = AccountState.ACTIVE,
            provider = ProviderKind.GOOGLE_DRIVE,
        )
        accounts.upsert(account)
        // A new start: clears a sticky "needs consent" and the 401 cooldown, and begins from the token just granted.
        registry.reset(account, fresh = accessToken)
        if (existing != null) sync.resumeAccount(account.id)
        return SignInNotice.SignedIn(account, resumed = existing != null)
    }

    /** Withdraw the permission (best effort), pause the account's notes, keep the row. */
    suspend fun signOut(accountId: String): NextcloudAccounts.SignOutResult {
        val account = accounts.find(accountId)
        authorizer.revoke(account?.loginName)
        val paused = sync.pauseAccount(accountId)
        registry.forget(accountId)
        accounts.setState(accountId, AccountState.SIGNED_OUT, null)
        return NextcloudAccounts.SignOutResult(pausedNotes = paused)
    }

    /** Remove the account locally. Nothing in Drive is touched (CLAUDE.md rule 1). */
    suspend fun forgetAccount(accountId: String) {
        val account = accounts.find(accountId)
        for (binding in bindings.forAccount(accountId)) sync.unbind(binding.noteId)
        authorizer.revoke(account?.loginName)
        registry.forget(accountId)
        accounts.remove(accountId)
        folderCache.clearFor(accountId)
    }

    /**
     * At launch: an ACTIVE account whose grant Google no longer lists is flagged for re-sign-in.
     *
     * One silent question to Play Services per account per launch, and **none for an account already flagged**
     * (CLAUDE.md rule 5's principle: a flagged account is left alone until a person acts). "Could not
     * reach Google" flags nothing.
     */
    suspend fun repairAndRefresh() {
        val live = accounts.all().filter { it.provider == ProviderKind.GOOGLE_DRIVE && it.state == AccountState.ACTIVE }
        for (account in live) {
            val dead = registry.tokensFor(account).bearer() as? BearerResult.Dead
            if (dead != null) accounts.setState(account.id, AccountState.NEEDS_REAUTH, dead.reason)
        }
    }
}
