package dev.inkstone.app.platform

import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.sync.OAuthSecret
import dev.inkstone.core.sync.PendingSignIn
import dev.inkstone.core.sync.PendingSignInStore
import dev.inkstone.core.sync.PkceCrypto
import dev.inkstone.core.sync.TokenVault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OAuth refresh tokens, one sealed file per account (docs/CLOUD-PROVIDERS.md §5.2).
 *
 * A refresh token is a credential that outlives the access token by months, so it gets the same
 * treatment as the Nextcloud app password: AES-GCM under a Keystore key, atomic write, and **null —
 * not a crash — when it cannot be opened**. [get] answering null is what lets the app say "this
 * device no longer holds your sign-in" ([dev.inkstone.app.sync.OneDriveAccounts] flags the account
 * `NEEDS_REAUTH` on it) without having asked Microsoft anything.
 */
@Singleton
class KeystoreTokenVault(private val sealed: SealedFiles) : TokenVault {

    @Inject
    constructor(fs: FileSystem) : this(SealedFiles(fs, SealedFiles.keystoreKey(KEY_ALIAS)))

    override suspend fun get(accountId: String): OAuthSecret? {
        val bytes = sealed.read(pathFor(accountId)) ?: return null
        return runCatching { json.decodeFromString<Stored>(bytes.toString(Charsets.UTF_8)) }
            .getOrNull()
            ?.takeIf { it.refreshToken.isNotEmpty() }
            ?.let { OAuthSecret(it.refreshToken, it.forcedRefreshAtEpochMillis) }
    }

    override suspend fun put(accountId: String, secret: OAuthSecret) {
        val stored = Stored(secret.refreshToken, secret.forcedRefreshAtEpochMillis)
        sealed.write(pathFor(accountId), json.encodeToString(Stored.serializer(), stored).toByteArray(Charsets.UTF_8))
    }

    override suspend fun remove(accountId: String) {
        sealed.delete(pathFor(accountId))
    }

    private fun pathFor(accountId: String) =
        "$DIR/${accountId.filter { it.isLetterOrDigit() || it == '-' }.take(MAX_ID)}$SUFFIX"

    @Serializable
    private data class Stored(val refreshToken: String, val forcedRefreshAtEpochMillis: Long = 0L)

    companion object {
        /** Its own key: Nextcloud's `inkstone.credentials.v1` is not shared, so neither can lock the other out. */
        const val KEY_ALIAS = "inkstone.oauth.v1"
        private const val DIR = "oauth"
        private const val SUFFIX = ".bin"
        private const val MAX_ID = 64
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The one half-finished sign-in. Sealed, because it holds the PKCE verifier — which, with the
 * authorization code, is all an attacker needs to redeem a stolen redirect.
 *
 * [take] deletes **before** it decodes: a redirect delivered twice, or a forged one arriving after the
 * real one, must find nothing, even if decoding then fails.
 */
@Singleton
class KeystorePendingSignInStore(private val sealed: SealedFiles) : PendingSignInStore {

    @Inject
    constructor(fs: FileSystem) : this(SealedFiles(fs, SealedFiles.keystoreKey(KeystoreTokenVault.KEY_ALIAS)))

    override suspend fun put(pending: PendingSignIn) {
        val stored = Stored(
            pending.state,
            pending.codeVerifier,
            pending.startedAtEpochMillis,
            pending.expectedAccountId,
        )
        sealed.write(PATH, Json.encodeToString(Stored.serializer(), stored).toByteArray(Charsets.UTF_8))
    }

    override suspend fun take(): PendingSignIn? {
        val bytes = sealed.read(PATH)
        sealed.delete(PATH)
        val stored = bytes?.let {
            runCatching { Json.decodeFromString<Stored>(it.toString(Charsets.UTF_8)) }.getOrNull()
        } ?: return null
        return PendingSignIn(stored.state, stored.codeVerifier, stored.startedAtEpochMillis, stored.expectedAccountId)
    }

    @Serializable
    private data class Stored(
        val state: String,
        val codeVerifier: String,
        val startedAtEpochMillis: Long,
        val expectedAccountId: String? = null,
    )

    private companion object {
        const val PATH = "oauth/pending-sign-in.bin"
    }
}

/** `SecureRandom` and `MessageDigest` — the two things `:core:sync` cannot have in common code. */
@Singleton
class AndroidPkceCrypto @Inject constructor() : PkceCrypto {
    private val random = SecureRandom()

    override fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    override fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
