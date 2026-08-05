package dev.inkstone.core.model

/*
 * SPEC §2.2 — the capabilities :core:* cannot implement itself.
 *
 * Declared here, implemented in :app. This is the escape hatch that lets core
 * stay platform-free without becoming useless: when core needs something only
 * the platform can do, it gets an interface, not an androidx import.
 *
 * Every member below is a stub in M0. The owning milestone is named on each.
 *
 * Block comment, not KDoc — a file-level KDoc attached to no declaration is a
 * "dangling toplevel KDoc" and ktlint cannot auto-correct it.
 */

/**
 * SPEC §5.2 — `:core:sync` needs a PDF to upload, and only `:android:pdf` can
 * produce one. Implemented in M4.
 */
public fun interface DocumentMaterializer {
    public suspend fun materialize(noteId: DocumentId): MaterializedFile
}

/**
 * @property localRev the note's `local_rev` at the moment these bytes were
 *   produced. SPEC §7.6: the upload is marked SYNCED only if `local_rev` still
 *   matches afterwards — otherwise the note changed mid-flight and that work
 *   becomes invisible to sync forever.
 */
public data class MaterializedFile(val path: String, val sizeBytes: Long, val sha256: String, val localRev: Long)

/**
 * SPEC §7.1 — [loginName] and [uid] are not interchangeable.
 *
 * [loginName] authenticates (Basic auth); [uid] is the WebDAV path segment,
 * fetched from `/ocs/v2.php/cloud/user` → `ocs.data.id`. Using `loginName` as
 * the path works on some instances and 404s on others, and SPEC §0.1 #3 calls
 * it the single most likely bug in the project — so both are stored, separately,
 * and the type makes you choose.
 */
public data class NextcloudCredential(
    val serverUrl: String,
    val loginName: String,
    val uid: String,
    val appPassword: String,
)

/**
 * SPEC §14 — AES-GCM with the key in the Android Keystore, written to a private
 * file. **Not** `EncryptedSharedPreferences`, which Google deprecated in 2025.
 * Implemented in M5.
 */
public interface CredentialStore {
    public suspend fun put(accountId: String, credential: NextcloudCredential)

    public suspend fun get(accountId: String): NextcloudCredential?

    public suspend fun remove(accountId: String)
}

/**
 * SPEC §2.4 — sync runs under WorkManager only, because it must survive process
 * death. Implemented in M5.
 */
public interface WorkScheduler {
    /** Ask the outbox drainer to run when constraints allow (SPEC §7.6). */
    public fun enqueueOutboxDrain()

    /** Rewrite `document.pdf` off the ink path (SPEC §5.1). */
    public fun enqueueMaterialization(noteId: DocumentId)
}

/**
 * CLAUDE.md rule 10 — every write is write-temp-then-atomic-rename.
 *
 * There is deliberately no plain `write`: the upload worker must never be able
 * to observe a half-written file, because a torn ZIP inside a PDF is the exact
 * corruption class this design exists to prevent. [appendBytes] is the journal
 * path (SPEC §5.1) and is the only non-atomic operation, by design — an
 * append that is cut short loses at most the in-progress stroke.
 */
public interface FileSystem {
    public suspend fun exists(path: String): Boolean

    public suspend fun readBytes(path: String): ByteArray

    public suspend fun writeBytesAtomically(path: String, bytes: ByteArray)

    public suspend fun appendBytes(path: String, bytes: ByteArray)

    public suspend fun createDirectories(path: String)
}

/**
 * Wall-clock time, for display only.
 *
 * Sync ordering uses Lamport clocks (SPEC §4.1). Never order sync operations by
 * wall clock — device clocks disagree, and the resulting reordering is silent.
 */
public fun interface Clock {
    public fun nowEpochMillis(): Long
}
