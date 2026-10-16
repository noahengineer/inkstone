package dev.inkstone.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.inkstone.android.ink.InkStrokeReader
import dev.inkstone.android.ocr.MlKitInkReader
import dev.inkstone.android.ocr.MlKitModels
import dev.inkstone.app.handwriting.AssetModelSource
import dev.inkstone.app.handwriting.ModelPolicy
import dev.inkstone.core.handwriting.neural.LicencePolicy
import dev.inkstone.core.model.InkReader
import dev.inkstone.core.model.ModelSource
import dev.inkstone.core.model.StrokeReader
import javax.inject.Singleton

/**
 * docs/HANDWRITING-SYNTHESIS.md — the two platform halves handwriting synthesis reads through.
 *
 * Both are ports in `:core:model` with one implementation each, in the `:android` module that owns
 * the third-party type behind them: `androidx.ink` stays in `:android:ink`, ML Kit in `:android:ocr`.
 */
@Module
@InstallIn(SingletonComponent::class)
object HandwritingModule {

    @Provides
    @Singleton
    fun provideStrokeReader(): StrokeReader = InkStrokeReader()

    /**
     * The same [MlKitModels] the recogniser is built with, so *"is the model downloaded"* has one
     * answer across the app. Constructing it touches no ML Kit API, as `provideRecogniser` notes.
     */
    @Provides
    @Singleton
    fun provideInkReader(models: MlKitModels): InkReader = MlKitInkReader(models = models)

    /** §4.4 — the network's weights, an APK asset. Absent or unreadable is `null`: assembly carries on. */
    @Provides
    @Singleton
    fun provideModelSource(@ApplicationContext context: Context): ModelSource = AssetModelSource(context.assets)

    /** D13 — a release build refuses a model trained on anything not known to allow commercial use. */
    @Provides
    @Singleton
    fun provideLicencePolicy(@ApplicationContext context: Context): LicencePolicy =
        ModelPolicy.of(context.applicationInfo.flags)
}
