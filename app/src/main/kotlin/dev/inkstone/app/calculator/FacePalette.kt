package dev.inkstone.app.calculator

import androidx.compose.ui.graphics.Color

/**
 * The face's paint, from research 01 §6's **Recommended** column (INFERRED: photos and Casio's render
 * balanced against each other, not a Casio specification).
 *
 * ⚠️ Not themed, and not translucent. Inkstone is dark-only, but the calculator is an object with its
 * own paint — the same argument that keeps `Ink.Paper` white (M10 §6). It is opaque for a second,
 * practical reason: over the emulator's garbage-filled ink surface anything translucent shows the
 * garbage (research 08 §4.8).
 */
internal object FacePalette {
    val Face = Color(0xFFECEDE9)
    val FaceEdge = Color(0xFFCFD0CC)

    val FunctionKey = Color(0xFFEEEEEA)
    val FunctionRim = Color(0xFF55554F)

    /** Round and cursor keys: a radial highlight over this, for the spun-metal tops (§2.3). */
    val Silver = Color(0xFFE9EAE6)
    val SilverHighlight = Color(0xFFF8F9F5)
    val SilverShade = Color(0xFFD2D3CF)
    val SilverRim = Color(0xFF9C9D99)

    val NumberKey = Color(0xFFA9ABAA)
    val NumberRim = Color(0xFF3A3B37)
    val ClearKey = Color(0xFFB4CF3A)

    /** "Real keys are dark grey, not black" (§6). */
    val CapOnWhite = Color(0xFF4A4A46)
    val CapOnGrey = Color(0xFFFFFFFF)

    val Shift = Color(0xFFC9963F)
    val Alpha = Color(0xFFC42D63)
    val Base = Color(0xFF1A9DC2)
    val Cmplx = Color(0xFF7457C4)
    val Mode = Color(0xFF4F514E)
    val Label = Color(0xFF50524F)

    val Plate = Color(0xFFE2E3DF)
    val PlateCell = Color(0xFFD6D7D3)
    val Well = Color(0xFFE5E6E2)
    val WellGroove = Color(0xFFCBCCC8)

    val Solar = Color(0xFF1E1817)
    val SolarRecess = Color(0xFFD9DAD6)
    val Bezel = Color(0xFFD8D9D5)
    val GlassRim = Color(0xFF8F928D)

    /** Mid-grey, as the maker's wordmark is — but the word is Inkstone's (docs/CALCULATOR.md D1). */
    val Logo = Color(0xFFA2A4A0)

    /** Laid over a held key: darker, as a real key in shadow under a fingertip. */
    val Pressed = Color(0x29000000)
}
