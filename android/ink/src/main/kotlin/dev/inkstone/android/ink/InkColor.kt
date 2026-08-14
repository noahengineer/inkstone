package dev.inkstone.android.ink

/**
 * ARGB arithmetic shared by everything in this module that paints a colour at an opacity.
 *
 * ## Why this file exists
 *
 * [withOpacity] was written twice — once in `StrokeStore.kt`, for the brush a restyle rebuilds,
 * and once in [PagePainter], for a `Paint`. The second carried a note saying exactly what to do
 * next:
 *
 * > duplicated here rather than shared because that one is private to a file about brushes and
 * > this is a paint. **If a third appears, hoist all three.**
 *
 * M8b's shape fill in [InkStrokeOutliner] is the third. Hoisted, per that instruction — and it
 * matters more than tidiness here, because the three consumers are the **screen**, the **brush**
 * and the **published PDF**, and a translucent fill that differed between them by a rounding rule
 * would look like the PDF writer being wrong about colour.
 */
internal object InkColor {

    /**
     * [this] at [opacity] of its **own** alpha.
     *
     * ⚠️ Multiplied into the existing alpha rather than replacing it, so putting a highlighter at
     * 50% gives half of *its* translucency and not half of opaque. `androidx.ink`'s brush has no
     * separate opacity field, which is why the multiplication has to happen in the colour at all.
     */
    fun Int.withOpacity(opacity: Float): Int {
        if (opacity >= 1f) return this
        val alpha = ((this ushr ALPHA_SHIFT and BYTE_MAX) * opacity).toInt().coerceIn(0, BYTE_MAX)
        return (alpha shl ALPHA_SHIFT) or (this and RGB_MASK)
    }

    private const val ALPHA_SHIFT = 24
    private const val BYTE_MAX = 0xFF
    private const val RGB_MASK = 0x00FFFFFF
}
