package dev.inkstone.app.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.inkstone.core.model.FileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM sealed files, for secrets that are not a Nextcloud app password: OAuth refresh tokens and
 * the half-finished sign-in record (docs/CLOUD-PROVIDERS.md §5).
 *
 * The same envelope [KeystoreCredentialStore] writes — `[version][ivLength][iv][ciphertext + tag]` —
 * and the same reasons (SPEC §14: not `EncryptedSharedPreferences`, deprecated in 2025; authenticated, so
 * a tampered file fails to open rather than yielding a token; IV chosen by the Keystore, never by us;
 * written atomically, CLAUDE.md rule 10). It is its own class and not a refactor of that one because
 * the Nextcloud store is not being touched in this change.
 *
 * ## The key is a parameter
 *
 * Production passes [keystoreKey]. A JVM test passes a plain AES key, which is what lets the envelope
 * — the part that is easy to get wrong — be tested without a device. What a test with a plain key
 * does **not** show is that the real Keystore accepts these parameters; that is the emulator's job.
 *
 * ## When the key or the file is unusable
 *
 * [read] answers **null**, for every reason: no file, a wrong version, a bad tag, a key the Keystore
 * has invalidated. The caller's answer is then "there is no saved sign-in" — a re-login, recoverable
 * and involving no retry against any server (CLAUDE.md rule 5) — rather than a crash.
 */
class SealedFiles(private val fs: FileSystem, private val key: () -> SecretKey) {

    suspend fun write(path: String, plaintext: ByteArray) {
        val sealed = withContext(Dispatchers.Default) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val body = cipher.doFinal(plaintext)
            ByteArray(2 + iv.size + body.size).also { out ->
                out[0] = FORMAT_VERSION
                out[1] = iv.size.toByte()
                iv.copyInto(out, 2)
                body.copyInto(out, 2 + iv.size)
            }
        }
        fs.writeBytesAtomically(path, sealed)
    }

    @Suppress("ReturnCount")
    suspend fun read(path: String): ByteArray? {
        if (!fs.exists(path)) return null
        val sealed = fs.readBytes(path)
        if (sealed.size < MINIMUM_ENVELOPE || sealed[0] != FORMAT_VERSION) return null
        val ivLength = sealed[1].toInt()
        if (ivLength <= 0 || sealed.size < 2 + ivLength + 1) return null
        return withContext(Dispatchers.Default) {
            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            // KeyStoreException, AEADBadTagException, UnrecoverableKeyException,
            // KeyPermanentlyInvalidatedException… the recovery is the same for all of them.
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed.copyOfRange(2, 2 + ivLength)))
                cipher.doFinal(sealed, 2 + ivLength, sealed.size - 2 - ivLength)
            } catch (unrecoverable: Exception) {
                null
            }
        }
    }

    suspend fun delete(path: String) {
        fs.delete(path)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val FORMAT_VERSION: Byte = 1
        private const val MINIMUM_ENVELOPE = 3
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_BITS = 256

        /** The Android Keystore key under [alias], created on first use. Not user-authenticated: sync runs locked. */
        fun keystoreKey(alias: String): () -> SecretKey = {
            // One lock for the process: the vault and the pending-sign-in store share an alias, and
            // two first uses racing check-then-create would mint two keys and orphan one's files.
            synchronized(keyLock) {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                val existing = (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
                existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                    .apply {
                        init(
                            KeyGenParameterSpec.Builder(
                                alias,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .setKeySize(KEY_BITS)
                                .setUserAuthenticationRequired(false)
                                .setRandomizedEncryptionRequired(true)
                                .build(),
                        )
                    }
                    .generateKey()
            }
        }

        private val keyLock = Any()
    }
}
