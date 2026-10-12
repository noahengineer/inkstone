package dev.inkstone.app.calculator

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.inkstone.core.calc.Setup

/**
 * The LCD's two colours.
 *
 * ## ⚠️ "Render-like" glass, the real unit's ink — and why
 *
 * Research 01 §3.4/§6 offers two backgrounds: the real unit's warm grey-green (#A9B4A6, about 0.7 of
 * the body's brightness) and Casio's marketing art's pale glass (#D3E2D5). This draws the **pale**
 * one with the **navy** ink a real unit actually shows (#1B2D63; the render's is pure black).
 *
 * The reason is size. On a real calculator a dot is 0.6 mm; in a window fitted to a landscape
 * tablet it is about 2 dp, and the real glass's low contrast — fine at arm's length on a desk —
 * turns a 5 × 9 digit at 2 dp into grey mush. The pale glass buys back that contrast; the navy keeps
 * the one thing about the real screen that is unmistakable at a glance. A decision, not a fact.
 */
internal object LcdPalette {
    val Glass = Color(0xFFD3E2D5)
    val Ink = Color(0xFF1B2D63)

    /** Where the darkest contrast setting heads: the same navy, deeper. */
    private val InkDeep = Color(0xFF0B1636)

    /** The faintest setting still leaves a quarter of the ink visible, as a real LCD's ghost does. */
    private const val FAINTEST = 0.25f

    /**
     * The ink at SETUP contrast [contrast] (research 01 §3.1: 26 levels, `0..Setup.MAX_CONTRAST`).
     * The middle level — `Setup.DEFAULT_CONTRAST`, itself a guess at an unknown default — is exactly
     * [Ink]; lower fades it into the glass, higher deepens it. The engine reports the setting on
     * every frame (`LcdFrame.contrast`), so SETUP ◀CONT▶ changes the glass as it is pressed.
     */
    fun inkFor(contrast: Int): Color {
        val level = contrast.coerceIn(Setup.MIN_CONTRAST, Setup.MAX_CONTRAST)
        val middle = Setup.DEFAULT_CONTRAST
        return if (level <= middle) {
            lerp(Glass, Ink, FAINTEST + (1f - FAINTEST) * level / middle)
        } else {
            lerp(Ink, InkDeep, (level - middle).toFloat() / (Setup.MAX_CONTRAST - middle))
        }
    }
}
