package dev.inkstone.app.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.inkstone.app.platform.AndroidFileSystem
import dev.inkstone.app.platform.KeystoreCredentialStore
import dev.inkstone.app.platform.PdfDocumentMaterializer
import dev.inkstone.app.platform.SystemClock
import dev.inkstone.app.platform.WorkManagerScheduler
import dev.inkstone.core.database.AndroidDatabaseDriverFactory
import dev.inkstone.core.database.DatabaseDriverFactory
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.database.createDatabase
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.CredentialStore
import dev.inkstone.core.model.DocumentMaterializer
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.WorkScheduler
import javax.inject.Singleton

/**
 * SPEC §2.2 — this module is the seam. `:core:*` declares interfaces; `:app`
 * is the only place that knows they are backed by Android.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlatformBindingsModule {

    @Binds
    @Singleton
    abstract fun bindClock(impl: SystemClock): Clock

    @Binds
    @Singleton
    abstract fun bindFileSystem(impl: AndroidFileSystem): FileSystem

    @Binds
    @Singleton
    abstract fun bindCredentialStore(impl: KeystoreCredentialStore): CredentialStore

    @Binds
    @Singleton
    abstract fun bindWorkScheduler(impl: WorkManagerScheduler): WorkScheduler

    @Binds
    @Singleton
    abstract fun bindDocumentMaterializer(impl: PdfDocumentMaterializer): DocumentMaterializer
}

@Module
@InstallIn(SingletonComponent::class)
object PlatformProvidersModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideDatabaseDriverFactory(@ApplicationContext context: Context): DatabaseDriverFactory =
        AndroidDatabaseDriverFactory(context)

    @Provides
    @Singleton
    fun provideDatabase(driverFactory: DatabaseDriverFactory): InkstoneDatabase = createDatabase(driverFactory)
}
