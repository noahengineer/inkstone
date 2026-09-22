package dev.inkstone.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.inkstone.app.ink.EditorPreferences
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.database.settings.SettingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * ⭐ M10's wiring — the editor's remembered toolbar (docs/M10-DESIGN.md §10).
 *
 * Its own module rather than another `@Provides` in `PlatformModule`, for the reason
 * `RecordingModule` is separate: the milestone that adds a subsystem should be findable by
 * deleting one file.
 */
@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {

    /** `Dispatchers.IO` is injected rather than hard-coded in `:core:database` — see `LibraryIndex`. */
    @Provides
    @Singleton
    fun provideSettingStore(database: InkstoneDatabase): SettingStore = SettingStore(database, Dispatchers.IO)

    /**
     * ⚠️ **Its own scope, and never cancelled** — the same shape as `RecordingController`'s, for a
     * related reason.
     *
     * The debounced write must be able to outlive the composition that triggered it: the user's last
     * act before leaving a note is often the one worth keeping, and `viewModelScope` is cancelled
     * before `onCleared` runs. `flush()` from both activities' `onStop` is what makes the tail
     * bounded; this scope is what gives it somewhere to run.
     *
     * `Dispatchers.Default` and not `IO`: the work here is a debounce timer and a codec, and
     * `SettingStore` moves to `IO` itself for the SQL.
     */
    @Provides
    @Singleton
    fun provideEditorPreferences(store: SettingStore): EditorPreferences =
        EditorPreferences(store, CoroutineScope(SupervisorJob() + Dispatchers.Default))
}
