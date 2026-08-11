package dev.inkstone.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.inkstone.app.audio.AudioRecorder
import dev.inkstone.app.audio.AutoRecordFolders
import dev.inkstone.app.audio.OpusRecorder
import dev.inkstone.app.audio.RecordingController
import dev.inkstone.app.audio.RecordingService
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.database.library.LibraryIndex
import dev.inkstone.core.database.recording.RecordingIndex
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Singleton

/**
 * M12's wiring (docs/M12-AUDIO.md).
 *
 * The only thing here that is a decision rather than plumbing is [provideRecordingController]'s
 * scope — see its KDoc.
 */
@Module
@InstallIn(SingletonComponent::class)
object RecordingModule {

    @Provides
    @Singleton
    fun provideRecordingIndex(database: InkstoneDatabase): RecordingIndex = RecordingIndex(database, Dispatchers.IO)

    @Provides
    @Singleton
    fun provideAudioRecorder(@ApplicationContext context: Context): AudioRecorder =
        OpusRecorder(context, Dispatchers.IO)

    /**
     * §10's per-folder setting.
     *
     * Constructed here rather than `@Inject`-annotated because it takes a `CoroutineDispatcher`,
     * and adding an unqualified binding for one would put `Dispatchers.IO` in front of every
     * constructor in the app that happens to name that type. The repo's other stores are provided
     * the same way for the same reason.
     */
    @Provides
    @Singleton
    fun provideAutoRecordFolders(database: InkstoneDatabase, index: LibraryIndex): AutoRecordFolders =
        AutoRecordFolders(database, index, Dispatchers.IO)

    /**
     * ⚠️ **Its own scope, and that is the whole reason this class is a singleton.**
     *
     * `SupervisorJob` on `Dispatchers.Default`, tied to nothing. A lecture has to survive the
     * screen dimming, the Activity being destroyed and the note's `ViewModel` being cleared — and
     * the *stop* has to be able to suspend, which `onCleared` cannot do because `viewModelScope`
     * is already cancelled by the time it runs (`NoteSessionHost` documents the same trap).
     *
     * Never cancelled: the process ending is what ends it, and at that point §9's orphan recovery
     * is the mechanism, not a graceful shutdown.
     *
     * ⚠️ `absolutePathOf` resolves a [FileSystem] path to a real one because `MediaRecorder`
     * writes through its own file descriptor and cannot be handed an abstraction. It is the only
     * place in the app that opens that seam, which is why it is a lambda here rather than a method
     * on `FileSystem` — widening that interface would let anything else write outside the atomic
     * rules CLAUDE.md rule 10 depends on.
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // Everything the state machine needs; see the class KDoc.
    fun provideRecordingController(
        @ApplicationContext context: Context,
        recorder: AudioRecorder,
        recordings: RecordingIndex,
        sync: SyncEngine,
        fs: FileSystem,
        clock: Clock,
        autoRecord: AutoRecordFolders,
    ): RecordingController = RecordingController(
        recorder = recorder,
        recordings = recordings,
        sync = sync,
        fs = fs,
        // Built here rather than injected: `IdFactory` has no binding, because every other user
        // of it constructs its own from the clock (`NoteEditor` does). One shared instance would
        // be a shared UUIDv7 counter across unrelated call sites for no benefit.
        ids = IdFactory(clock),
        clock = clock,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        foreground = object : RecordingController.ForegroundHost {
            override fun start() = RecordingService.start(context)

            override fun stop() = RecordingService.stop(context)
        },
        absolutePathOf = { path -> File(context.filesDir, path).absolutePath },
        // §11 — the partition the recordings are written to, which is the one `filesDir` is on.
        freeBytes = { context.filesDir.usableSpace },
        autoRecordFor = { noteId -> autoRecord.shouldAutoRecord(noteId) },
        elapsedRealtime = { android.os.SystemClock.elapsedRealtime() },
    )
}
