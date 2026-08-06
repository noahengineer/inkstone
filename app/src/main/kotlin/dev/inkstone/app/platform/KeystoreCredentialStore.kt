package dev.inkstone.app.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.inkstone.core.model.CredentialStore
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.NextcloudCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SPEC §14 — the Nextcloud app password, **AES-GCM with the key in the Android Keystore,
 * written to a private file**.
 *
 * ## Not `EncryptedSharedPreferences`
 *
 * SPEC §0.2 and §14 both say so, and it is the first thing a model trained on older
 * material suggests here: Google deprecated `androidx.security:security-crypto` in 2025
 * with no replacement. The whole of what it provided is below — about sixty lines and no
 * deprecated dependency.
 *
 * ## What is stored and what is not
 *
 * Only the **app password** issued by Login Flow v2, never the user's real password —
 * this app never sees one, because the login happens in a browser (SPEC §7.1). An app
 * password is revocable from the user's Nextcloud security settings under a readable
 * name (SPEC §7.1 rule 4), which is the point of the whole flow.
 *
 * The server URL, login name and uid are stored here too rather than only in the
 * database, so a credential is self-contained: a device that loses its library index
 * still has enough to re-authenticate rather than making the user log in again.
 *
 * ## Key parameters, and why each one
 *
 * - `AES/GCM/NoPadding`, 256-bit. GCM is authenticated: a file tampered with fails to
 *   decrypt rather than yielding garbage that gets sent to a server as a password.
 * - `setUserAuthenticationRequired(false)` — SPEC §0.2 names this explicitly. Sync runs
 *   under WorkManager while the device is locked (SPEC §2.4); a key that needed the user
 *   present would make background upload impossible, which is the feature.
 * - `setRandomizedEncryptionRequired(true)` (the default) — the IV comes from the
 *   Keystore, never from us. Reusing a GCM IV under one key is a total break, and this
 *   is the only way to be sure we have not.
 *
 * ## When the key disappears
 *
 * A Keystore key can be invalidated — a factory reset, a restore to a different device,
 * some lock-screen changes. Decryption then throws, and the honest answer is *"there is
 * no credential"*: the account falls back to needing a re-login, which is recoverable and
 * involves no retry against the server (CLAUDE.md rule 5). Crashing instead would make an
 * unopenable app out of a solvable problem.
 */
@Singleton
class KeystoreCredentialStore @Inject constructor(private val fs: FileSystem) : CredentialStore {

    override suspend fun put(accountId: String, credential: NextcloudCredential) {
        val plaintext = json.encodeToString(StoredCredential.of(credential)).toByteArray(Charsets.UTF_8)
        val sealed = withContext(Dispatchers.Default) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val body = cipher.doFinal(plaintext)
            // [version][ivLength][iv][ciphertext+tag]. The IV length is written rather than
            // assumed: GCM's 12 bytes is a recommendation, not a guarantee, and a provider
            // that ever chose differently would otherwise produce files nothing can read.
            ByteArray(2 + iv.size + body.size).also { out ->
                out[0] = FORMAT_VERSION
                out[1] = iv.size.toByte()
                iv.copyInto(out, 2)
                body.copyInto(out, 2 + iv.size)
            }
        }
        // Atomic (CLAUDE.md rule 10). A half-written credential file is a user who cannot
        // sign in and does not know why.
        fs.writeBytesAtomically(pathFor(accountId), sealed)
    }

    override suspend fun get(accountId: String): NextcloudCredential? {
        val path = pathFor(accountId)
        if (!fs.exists(path)) return null
        val sealed = fs.readBytes(path)
        if (sealed.size < MINIMUM_ENVELOPE || sealed[0] != FORMAT_VERSION) return null

        val ivLength = sealed[1].toInt()
        if (ivLength <= 0 || sealed.size < 2 + ivLength + 1) return null

        return withContext(Dispatchers.Default) {
            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            // Every failure here means the same thing to the caller — there is no usable
            // credential — and the reasons are a scattered set of platform exception types
            // (KeyStoreException, AEADBadTagException, UnrecoverableKeyException,
            // KeyPermanentlyInvalidatedException, and a SerializationException behind them).
            // Enumerating them would be a list that goes stale; the recovery is identical
            // for all of them, and it is a re-login rather than a crash. See the class KDoc.
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                val iv = sealed.copyOfRange(2, 2 + ivLength)
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
                val plaintext = cipher.doFinal(sealed, 2 + ivLength, sealed.size - 2 - ivLength)
                json.decodeFromString<StoredCredential>(plaintext.toString(Charsets.UTF_8)).toCredential()
            } catch (unrecoverable: Exception) {
                null
            }
        }
    }

    /**
     * Forget an account's credential.
     *
     * The Keystore key is **not** deleted: it is shared by every account, and dropping it
     * would make the other accounts' files undecryptable. There is nothing to gain — a
     * file that is gone cannot be decrypted whatever the key does.
     */
    override suspend fun remove(accountId: String) {
        fs.delete(pathFor(accountId))
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                // SPEC §0.2, verbatim. Sync runs under WorkManager on a locked device.
                .setUserAuthenticationRequired(false)
                // The IV must come from the Keystore, never from us. See the class KDoc.
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun pathFor(accountId: String): String = "$CREDENTIALS_DIR/${accountId.sanitisedForFilename()}$SUFFIX"

    /**
     * Account ids are UUIDv7 strings, so this changes nothing today.
     *
     * It is here because the id is the only part of the path that is not a constant, and
     * a path built from an untrusted string is how a store like this grows a traversal
     * bug three milestones from now.
     */
    private fun String.sanitisedForFilename(): String = map { if (it.isLetterOrDigit() || it == '-') it else '_' }
        .joinToString("")
        .take(MAX_ID_LENGTH)

    @Serializable
    private data class StoredCredential(
        val serverUrl: String,
        val loginName: String,
        val uid: String,
        val appPassword: String,
    ) {
        fun toCredential() = NextcloudCredential(serverUrl, loginName, uid, appPassword)

        companion object {
            fun of(credential: NextcloudCredential) = StoredCredential(
                serverUrl = credential.serverUrl,
                loginName = credential.loginName,
                uid = credential.uid,
                appPassword = credential.appPassword,
            )
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "inkstone.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val FORMAT_VERSION: Byte = 1
        const val MINIMUM_ENVELOPE = 3
        const val CREDENTIALS_DIR = "credentials"
        const val SUFFIX = ".bin"
        const val MAX_ID_LENGTH = 64

        val json = Json { ignoreUnknownKeys = true }
    }
}
