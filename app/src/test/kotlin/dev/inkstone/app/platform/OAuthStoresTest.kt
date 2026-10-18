package dev.inkstone.app.platform

import dev.inkstone.app.note.InMemoryFileSystem
import dev.inkstone.core.sync.OAuthSecret
import dev.inkstone.core.sync.PendingSignIn
import kotlinx.coroutines.test.runTest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sealed envelope behind the OAuth vault and the pending sign-in, with a plain AES key standing
 * in for the Keystore.
 *
 * ⚠️ **What a green run means.** The envelope — layout, authentication, "null and not a crash" on
 * every kind of damage — works. It does **not** show that the real Android Keystore accepts
 * `SealedFiles.keystoreKey`'s parameters or that the key survives a reboot; the emulator walk reads the
 * sealed file off a real device for that.
 */
class OAuthStoresTest {

    private val fs = InMemoryFileSystem()
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val vault = KeystoreTokenVault(SealedFiles(fs) { key })
    private val pending = KeystorePendingSignInStore(SealedFiles(fs) { key })

    @Test
    fun `a secret round-trips with its cooldown stamp`() = runTest {
        vault.put("acct-1", OAuthSecret("refresh-abc", forcedRefreshAtEpochMillis = 1234L))

        assertEquals(OAuthSecret("refresh-abc", 1234L), vault.get("acct-1"))
    }

    @Test
    fun `the token is not readable in the file`() = runTest {
        vault.put("acct-1", OAuthSecret("refresh-abc-distinctive"))

        val onDisk = fs.readBytes("oauth/acct-1.bin")

        val inTheClear = onDisk.decodeToString().contains("refresh-abc-distinctive")
        assertFalse(inTheClear, "the token is in the file in the clear")
    }

    @Test
    fun `accounts are independent and remove forgets only one`() = runTest {
        vault.put("a", OAuthSecret("one"))
        vault.put("b", OAuthSecret("two"))

        vault.remove("a")

        assertNull(vault.get("a"))
        assertEquals("two", vault.get("b")?.refreshToken)
    }

    @Test
    fun `a damaged file is no secret rather than a crash`() = runTest {
        vault.put("acct-1", OAuthSecret("refresh-abc"))
        val bytes = fs.readBytes("oauth/acct-1.bin")
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()
        fs.writeBytesAtomically("oauth/acct-1.bin", bytes)

        assertNull(vault.get("acct-1"), "a tampered file must not yield a token")
    }

    @Test
    fun `a file sealed under another key is no secret rather than a crash`() = runTest {
        vault.put("acct-1", OAuthSecret("refresh-abc"))
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        assertNull(KeystoreTokenVault(SealedFiles(fs) { other }).get("acct-1"))
    }

    @Test
    fun `a truncated or foreign file is no secret`() = runTest {
        fs.writeBytesAtomically("oauth/acct-1.bin", byteArrayOf(1, 12))
        assertNull(vault.get("acct-1"))
        fs.writeBytesAtomically("oauth/acct-1.bin", ByteArray(64) { 9 })
        assertNull(vault.get("acct-1"))
    }

    @Test
    fun `an account id cannot climb out of the oauth directory`() = runTest {
        vault.put("../../databases/x", OAuthSecret("t"))

        // Separators and dots are dropped, so the file is a plain name inside oauth/.
        assertTrue(fs.exists("oauth/databasesx.bin"))
        assertNotNull(vault.get("../../databases/x"))
        assertFalse(fs.exists("../../databases/x"))
    }

    @Test
    fun `the pending sign-in is taken once`() = runTest {
        pending.put(PendingSignIn("state-1", "verifier-1", 99L, expectedAccountId = "acct"))

        assertEquals(PendingSignIn("state-1", "verifier-1", 99L, "acct"), pending.take())
        assertNull(pending.take(), "a redirect delivered twice found the record a second time")
    }

    @Test
    fun `an unreadable pending record is consumed anyway`() = runTest {
        pending.put(PendingSignIn("s", "v", 1L))
        fs.writeBytesAtomically("oauth/pending-sign-in.bin", ByteArray(40) { 3 })

        assertNull(pending.take())
        assertFalse(fs.exists("oauth/pending-sign-in.bin"), "a damaged record was left to be tried again")
    }
}
