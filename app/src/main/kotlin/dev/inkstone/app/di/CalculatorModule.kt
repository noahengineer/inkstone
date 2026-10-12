package dev.inkstone.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.inkstone.app.calculator.CalculatorController
import dev.inkstone.app.calculator.CalculatorPreferences
import dev.inkstone.core.database.settings.SettingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * The built-in calculator's wiring (docs/CALCULATOR.md).
 *
 * Its own module for `PreferencesModule`'s reason: the subsystem should be findable by deleting
 * one file.
 */
@Module
@InstallIn(SingletonComponent::class)
object CalculatorModule {

    /**
     * ⚠️ **Its own scope, never cancelled** — `provideEditorPreferences`' argument: the debounced
     * write must outlive the screen that triggered it, and `viewModelScope` is cancelled before
     * `onCleared` runs. `flush()` from both activities' `onStop` bounds the tail.
     */
    @Provides
    @Singleton
    fun provideCalculatorPreferences(store: SettingStore): CalculatorPreferences =
        CalculatorPreferences(store, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    /**
     * ⚠️ **Its own scope, never cancelled**, and that is why the controller is a singleton at all:
     * the calculator outlives every note and is shared with the library (D3), so nothing with a
     * lifecycle shorter than the process may own the coroutine that drains its keys.
     * `Dispatchers.Default`, because that coroutine runs the engine — never on the main thread
     * (CLAUDE.md rule 7). `RecordingModule.provideRecordingController` is the precedent.
     */
    @Provides
    @Singleton
    fun provideCalculatorController(preferences: CalculatorPreferences): CalculatorController =
        CalculatorController(preferences, CoroutineScope(SupervisorJob() + Dispatchers.Default))
}
