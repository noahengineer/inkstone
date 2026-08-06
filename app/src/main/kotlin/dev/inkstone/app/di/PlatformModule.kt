package dev.inkstone.app.di

import android.content.ContentResolver
import android.content.Context
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.inkstone.android.ink.InkBrushFactory
import dev.inkstone.android.ink.InkPageThumbnailRenderer
import dev.inkstone.android.ink.InkStrokeOutliner
import dev.inkstone.android.ink.InkStrokeRebuilder
import dev.inkstone.android.pdf.InkstonePdfMaterializer
import dev.inkstone.android.pdf.PdfGateway
import dev.inkstone.android.pdf.PdfPageRasterizer
import dev.inkstone.app.library.ThumbnailSourcePagesImpl
import dev.inkstone.app.platform.AndroidFileSystem
import dev.inkstone.app.platform.KeystoreCredentialStore
import dev.inkstone.app.platform.SystemClock
import dev.inkstone.app.platform.WorkManagerScheduler
import dev.inkstone.core.database.AndroidDatabaseDriverFactory
import dev.inkstone.core.database.DatabaseDriverFactory
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.database.createDatabase
import dev.inkstone.core.database.library.LibraryIndex
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.CredentialStore
import dev.inkstone.core.model.DocumentMaterializer
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.PageThumbnailRenderer
import dev.inkstone.core.model.WorkScheduler
import dev.inkstone.core.storage.NoteStore
import dev.inkstone.core.storage.NoteTrash
import dev.inkstone.core.storage.ThumbnailCache
import kotlinx.coroutines.Dispatchers
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

    /**
     * SPEC §9 — the library index (M3).
     *
     * `Dispatchers.IO` is injected rather than hard-coded inside `:core:database`, which is
     * KMP and has no such dispatcher in common code (SPEC §2.3).
     */
    @Provides
    @Singleton
    fun provideLibraryIndex(database: InkstoneDatabase): LibraryIndex = LibraryIndex(database, Dispatchers.IO)

    @Provides
    @Singleton
    fun provideThumbnailCache(fs: FileSystem): ThumbnailCache = ThumbnailCache(fs)

    /**
     * SPEC §5.2 (M4) — the published PDF.
     *
     * Three modules meet here and none of them knows about the others: `:core:storage`
     * holds the note, `:android:ink` can outline a stroke because it owns the
     * tessellator, and `:android:pdf` can write a PDF because it owns PdfBox. The
     * dependency graph stays one-way (SPEC §2.1) precisely because this wiring is
     * `:app`'s job and not theirs.
     */
    @Provides
    @Singleton
    fun provideDocumentMaterializer(fs: FileSystem, clock: Clock, brushes: InkBrushFactory): DocumentMaterializer =
        InkstonePdfMaterializer(
            fs = fs,
            store = NoteStore(fs, clock, IdFactory(clock)),
            outliner = InkStrokeOutliner(brushes),
        )

    @Provides
    @Singleton
    fun providePdfPageRasterizer(): PdfPageRasterizer = PdfPageRasterizer()

    /**
     * For reading the PDF the document picker returned (M4).
     *
     * The resolver stops at `:app`. A `Uri` is only readable while this process holds
     * the grant the picker made, so everything below is handed bytes.
     */
    @Provides
    @Singleton
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver = context.contentResolver

    /**
     * SPEC §5.6 — the three-way open (M4). Every PDF the user picks comes through here.
     */
    @Provides
    @Singleton
    fun providePdfGateway(fs: FileSystem, clock: Clock, brushes: InkBrushFactory): PdfGateway {
        val ids = IdFactory(clock)
        return PdfGateway(
            fs = fs,
            store = NoteStore(fs, clock, ids),
            trash = NoteTrash(fs, clock),
            ids = ids,
            clock = clock,
            rebuilder = InkStrokeRebuilder(brushes),
        )
    }

    /**
     * SPEC §5.7 — the thumbnail renderer, which is `:android:ink`'s because drawing a
     * stroke needs `androidx.ink` and a `Canvas`.
     *
     * The brush factory is built from the display's real metrics for the same reason the
     * editor's is: a stored stroke's width is in page points and the epsilon that controls
     * its tessellation is a fraction of a physical pixel. Getting the density wrong here
     * would give the library a picture drawn with a different tolerance from the editor's.
     */
    @Provides
    @Singleton
    fun providePageThumbnailRenderer(
        brushes: InkBrushFactory,
        sourcePages: ThumbnailSourcePagesImpl,
    ): PageThumbnailRenderer = InkPageThumbnailRenderer(brushes, sourcePages)

    /**
     * The brush factory, shared by the thumbnail renderer and the measurement fixture.
     *
     * Built from the display's real metrics: a stored stroke's width is in page points and
     * the epsilon controlling its tessellation is a fraction of a physical pixel, so a wrong
     * density would draw the library's pictures at a different tolerance from the editor's.
     */
    @Provides
    @Singleton
    fun provideInkBrushFactory(@ApplicationContext context: Context): InkBrushFactory {
        val metrics = context.resources.displayMetrics
        return InkBrushFactory(
            displayDensity = metrics.density,
            pixelsPerPoint = metrics.densityDpi / POINTS_PER_INCH,
        )
    }

    /** PostScript points per inch — the unit page space is in (SPEC §4.4). */
    private const val POINTS_PER_INCH = 72f
}
