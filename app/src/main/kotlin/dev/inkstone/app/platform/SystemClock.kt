package dev.inkstone.app.platform

import dev.inkstone.core.model.Clock
import javax.inject.Inject
import javax.inject.Singleton

/*
 * What is left of M0's stubs.
 *
 * `:core` declares what it needs (SPEC §2.2) and `:app` supplies it. Every entry that
 * once lived here has been filled in by the milestone that owned it:
 *
 *   DocumentMaterializer  M4  ->  :android:pdf's InkstonePdfMaterializer, wired in PlatformModule
 *   PageThumbnailRenderer M3  ->  :android:ink's InkPageThumbnailRenderer
 *   CredentialStore       M5  ->  KeystoreCredentialStore  (AES-GCM, Keystore key, SPEC §14)
 *   WorkScheduler         M5  ->  WorkManagerScheduler
 *   FileSystem            M0  ->  AndroidFileSystem
 *
 * Only the clock is small enough to still live here.
 */

@Singleton
class SystemClock @Inject constructor() : Clock {
    // Display only. Sync ordering uses Lamport clocks — device clocks disagree.
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}
