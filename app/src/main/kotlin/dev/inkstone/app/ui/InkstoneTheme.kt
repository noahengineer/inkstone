package dev.inkstone.app.ui

import androidx.activity.SystemBarStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * ⭐ ROADMAP M10 — the app's theme, and the reason the rest of the app changed for free.
 *
 * ## Why the Material scheme is filled in from [Ink] rather than bypassed
 *
 * The redesign replaces the *chrome* — the dock, the page bar, the contextual toolbars — with
 * bespoke glass components ([GlassBar], [TextChip]). It does not replace the platform pieces the app
 * legitimately gets from Material 3: the snackbar, the modal bottom sheet behind the page overview,
 * the dropdown menus, the dialogs, the text fields. Those read `MaterialTheme.colorScheme`, so
 * mapping the palette onto that scheme is what stops a Material-blue dialog appearing over a
 * near-black canvas — a whole class of "half the app got redesigned" defects, fixed by a table
 * rather than by visiting every call site.
 *
 * ## ⚠️ Dark only, and the parameter is gone rather than ignored
 *
 * This used to take `darkTheme: Boolean = isSystemInDarkTheme()`. The design is dark-first by
 * construction: translucent panels over a near-black canvas with inverted active chips is not a
 * scheme that has a light twin, it is a different design. A parameter that silently did nothing
 * would be the same lie as a `= Unit` override — CLAUDE.md records what that cost in M6 — so it is
 * deleted, and a light theme, if it is ever wanted, arrives as a second palette rather than as a
 * boolean.
 *
 * ⚠️ **Paper is not themed.** `PagePainter` draws white paper and always has; a note is a PDF and
 * the page is the document, not the chrome. Per-notebook paper colour remains M10's other half and
 * is unaffected by anything here.
 */
@Composable
fun InkstoneTheme(content: @Composable () -> Unit) {
    val typography = remember { inkTypography() }
    MaterialTheme(
        colorScheme = InkColorScheme,
        typography = typography,
        shapes = InkShapes,
        content = content,
    )
}

/**
 * [Ink] projected onto Material 3's roles.
 *
 * The two mappings worth stating, because they are choices and not translations:
 *
 * - **`primary` is [Ink.Focus] and not [Ink.Accent].** Material paints selection outlines, cursors,
 *   focused field borders and progress with `primary`, and every one of those is a *"this is live"*
 *   signal — which the design gives to sky, not to the near-white it uses for a chosen chip.
 * - **`surface` is opaque.** [Ink.Panel] is translucent by design, but Material composes menus and
 *   sheets on top of arbitrary content, and a translucent dropdown over a page of handwriting is
 *   unreadable. Translucency is opted into by the floating chrome, never inherited.
 */
private val InkColorScheme = darkColorScheme(
    primary = Ink.Focus,
    onPrimary = Ink.OnAccent,
    primaryContainer = Ink.SegmentActive,
    onPrimaryContainer = Ink.TextStrong,
    inversePrimary = Ink.Focus,

    secondary = Ink.TextNormal,
    onSecondary = Ink.OnAccent,
    secondaryContainer = Ink.SegmentActive,
    onSecondaryContainer = Ink.TextStrong,

    tertiary = Ink.Focus,
    onTertiary = Ink.OnAccent,
    tertiaryContainer = Ink.PanelRaised,
    onTertiaryContainer = Ink.TextStrong,

    background = Ink.Canvas,
    onBackground = Ink.TextNormal,

    surface = Ink.PanelSolid,
    onSurface = Ink.TextStrong,
    surfaceVariant = Ink.PanelRaised,
    onSurfaceVariant = Ink.TextNormal,
    surfaceTint = Ink.PanelRaised,

    surfaceContainerLowest = Ink.Canvas,
    surfaceContainerLow = Ink.PanelSolid,
    surfaceContainer = Ink.PanelSolid,
    surfaceContainerHigh = Ink.PanelRaised,
    surfaceContainerHighest = Ink.PanelRaised,

    inverseSurface = Ink.TextStrong,
    inverseOnSurface = Ink.Canvas,

    outline = Ink.Hairline,
    outlineVariant = Ink.SegmentTrack,

    error = Ink.Danger,
    onError = Ink.OnAccent,
    errorContainer = Ink.RecordingWash,
    onErrorContainer = Ink.Danger,

    scrim = Ink.Canvas,
)

/** The mock's radii, so a Material dialog and a bespoke panel round their corners identically. */
private val InkShapes = Shapes(
    extraSmall = InkDims.SegmentInnerCorner,
    small = InkDims.ChipCorner,
    medium = InkDims.SegmentCorner,
    large = InkDims.PanelCorner,
    extraLarge = InkDims.PanelCorner,
)

/**
 * Material's ramp with the label styles retuned, and nothing else touched.
 *
 * The design's typography is *Inter, or system sans-serif*. No font is bundled: shipping a variable
 * face for chrome would add a megabyte to an app whose page-one budget is a lecture PDF, and the
 * brief names the system fallback as acceptable. What is retuned is the part the design is actually
 * specific about — labels are tracked out, because a chip's word is a control and reads as one when
 * it is spaced like a legend.
 */
private fun inkTypography(): Typography {
    val base = Typography()
    return base.copy(
        labelLarge = base.labelLarge.tracked(InkType.ChipSize, FontWeight.Medium, InkType.ChipTracking),
        labelMedium = base.labelMedium.tracked(InkType.MicroSize, FontWeight.Medium, InkType.MicroTracking),
        labelSmall = base.labelSmall.copy(
            color = Ink.TextMuted,
            letterSpacing = InkType.MicroTracking,
        ),
        titleSmall = base.titleSmall.copy(color = Ink.TextStrong),
        titleMedium = base.titleMedium.copy(color = Ink.TextStrong),
        titleLarge = base.titleLarge.copy(color = Ink.TextStrong, fontWeight = FontWeight.SemiBold),
    )
}

private fun TextStyle.tracked(
    size: androidx.compose.ui.unit.TextUnit,
    weight: FontWeight,
    tracking: androidx.compose.ui.unit.TextUnit,
) = copy(fontSize = size, fontWeight = weight, letterSpacing = tracking)

/** The face every readout uses. Monospaced so a counting number does not shuffle its neighbours. */
internal val ReadoutFamily: FontFamily = FontFamily.Monospace

/**
 * ⭐ M10 — light system-bar icons over transparent bars, unconditionally.
 *
 * ⚠️ `enableEdgeToEdge()`'s default is `SystemBarStyle.auto(…)`, which chooses light or dark icons
 * from the **device's** day/night setting. This design is dark-first and has no light twin (see
 * [InkstoneTheme]), so on a tablet in light mode the default gives *dark* icons over a near-black
 * canvas — an invisible clock and an invisible battery, on every screen, in the configuration most
 * tablets ship in. Both activities pass this rather than relying on the default.
 */
val DarkBars: SystemBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
