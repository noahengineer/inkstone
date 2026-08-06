package dev.inkstone.app.platform

import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.CredentialStore
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.DocumentMaterializer
import dev.inkstone.core.model.MaterializedFile
import dev.inkstone.core.model.NextcloudCredential
import dev.inkstone.core.model.WorkScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M0 implementations of the SPEC §2.2 interfaces.
 *
 * These exist so the dependency graph is real from day one — `:core` declares
 * what it needs, `:app` supplies it, and nothing in between reaches for an
 * `androidx` import. Each one names the milestone that fills it in.
 *
 * The stubs throw rather than returning empty results: an unimplemented
 * capability being called is a programming error, and CLAUDE.md reserves
 * exceptions for exactly that.
 */

@Singleton
class SystemClock @Inject constructor() : Clock {
    // Display only. Sync ordering uses Lamport clocks — device clocks disagree.
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}

/**
 * M5 fills this in, with **Keystore AES-GCM written to a private file**
 * (`KeyGenParameterSpec`, `setUserAuthenticationRequired(false)`) — roughly 60
 * lines and no deprecated dependency.
 *
 * Do **not** reach for `EncryptedSharedPreferences`: Google deprecated it in
 * 2025 (SPEC §0.2, §14). It is the first thing a model trained on older
 * material will suggest here.
 */
@Singleton
class KeystoreCredentialStore @Inject constructor(
    @ApplicationContext @Suppress("unused") private val context: Context,
) : CredentialStore {
    override suspend fun put(accountId: String, credential: NextcloudCredential): Unit =
        TODO("M5 — Keystore AES-GCM, not EncryptedSharedPreferences")

    override suspend fun get(accountId: String): NextcloudCredential? =
        TODO("M5 — Keystore AES-GCM, not EncryptedSharedPreferences")

    override suspend fun remove(accountId: String): Unit = TODO("M5 — Keystore AES-GCM, not EncryptedSharedPreferences")
}

/**
 * M5 fills this in. Every remote mutation goes through the outbox and is
 * drained by WorkManager — no code path performs a network write directly
 * (CLAUDE.md rule 4).
 */
@Singleton
class WorkManagerScheduler @Inject constructor(@Suppress("unused") private val workManager: WorkManager) :
    WorkScheduler {
    override fun enqueueOutboxDrain(): Unit = TODO("M5 — outbox drainer")

    override fun enqueueMaterialization(noteId: DocumentId): Unit = TODO("M4 — PDF materialization")
}

/*
 * The M4 materializer is `:android:pdf`'s `InkstonePdfMaterializer`, provided in
 * `PlatformProvidersModule`. There is no stub here any more — the milestone that
 * was going to fill it in has.
 */
