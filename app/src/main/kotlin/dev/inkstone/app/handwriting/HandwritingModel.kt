package dev.inkstone.app.handwriting

import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import dev.inkstone.core.handwriting.neural.LicencePolicy
import dev.inkstone.core.model.ModelSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/**
 * docs/HANDWRITING-SYNTHESIS.md §4.4 — the synthesis network's weights, `assets/handwriting/base-v1.ihwm`,
 * the app's first asset. `:core` checks and parses them ([dev.inkstone.core.handwriting.neural.WeightsFile]);
 * this only reads bytes.
 *
 * A missing or unreadable asset is `null`, which turns the neural backend off and leaves glyph
 * assembly running — the same as a corrupt file or a refused licence. Nothing here throws.
 */
class AssetModelSource(private val assets: AssetManager, private val path: String = PATH) : ModelSource {

    override suspend fun bytes(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            assets.open(path).use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        const val PATH: String = "handwriting/base-v1.ihwm"
    }
}

/**
 * ⭐ D13: which licences this build will run a model under, read off the installed APK rather than a
 * build flag — so the release build on a tablet is held to [LicencePolicy.RELEASE] whatever key it
 * was signed with. Only a debuggable build may load a research model, and then flagged.
 */
object ModelPolicy {
    fun of(applicationFlags: Int): LicencePolicy = if (applicationFlags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        LicencePolicy.DEVELOPMENT
    } else {
        LicencePolicy.RELEASE
    }
}
