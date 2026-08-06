package dev.inkstone.android.ink

import android.graphics.Matrix
import dev.inkstone.core.model.AFFINE_SIZE
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.pageToViewAffine
import dev.inkstone.core.model.viewToPageAffine

/**
 * Turns a [Camera] into an `android.graphics.Matrix`.
 *
 * The arithmetic lives in `:core:model` — it has to, because `:core` is where
 * SPEC §4.4 says the coordinate conversions live and `:core` may not import
 * `android.graphics`. What is left here is the mechanical copy from a 3×2
 * affine into the 3×3 array `Matrix.setValues` wants.
 *
 * An instance owns its scratch arrays and is reused, because both callers are
 * on paths that run per stroke or per frame and CLAUDE.md rule 7 forbids
 * allocation there. Not thread-safe, and does not need to be: both callers are
 * on the UI thread.
 */
internal class CameraMatrix {

    private val affine = FloatArray(AFFINE_SIZE)
    private val values = FloatArray(MATRIX_VALUE_COUNT)
    private val matrix = Matrix()

    fun pageToView(camera: Camera): Matrix = fill(camera.pageToViewAffine(affine))

    fun viewToPage(camera: Camera): Matrix = fill(camera.viewToPageAffine(affine))

    private fun fill(source: FloatArray): Matrix {
        values[Matrix.MSCALE_X] = source[A_SCALE_X]
        values[Matrix.MSKEW_X] = source[A_SKEW_X]
        values[Matrix.MTRANS_X] = source[A_TRANS_X]
        values[Matrix.MSKEW_Y] = source[A_SKEW_Y]
        values[Matrix.MSCALE_Y] = source[A_SCALE_Y]
        values[Matrix.MTRANS_Y] = source[A_TRANS_Y]
        values[Matrix.MPERSP_0] = 0f
        values[Matrix.MPERSP_1] = 0f
        values[Matrix.MPERSP_2] = 1f
        matrix.setValues(values)
        return matrix
    }

    private companion object {
        const val MATRIX_VALUE_COUNT = 9

        // Row-major 3x2, as documented on Camera.pageToViewAffine.
        const val A_SCALE_X = 0
        const val A_SKEW_X = 1
        const val A_TRANS_X = 2
        const val A_SKEW_Y = 3
        const val A_SCALE_Y = 4
        const val A_TRANS_Y = 5
    }
}
