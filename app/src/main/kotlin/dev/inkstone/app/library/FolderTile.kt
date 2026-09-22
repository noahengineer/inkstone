package dev.inkstone.app.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import dev.inkstone.app.ui.Ink
import dev.inkstone.core.model.Folder
import dev.inkstone.core.model.FolderCover

/**
 * The picture part of a folder — colour, icon, or whatever cover it has been given.
 *
 * ## The bug this exists to fix
 *
 * M3's first version tinted the **whole card**, label strip included. Choosing a colour
 * therefore painted the folder's name onto its own background: at best low contrast, and
 * for the darker swatches the name disappeared entirely. Reported from the tablet as "the
 * bottom bit that shows the text gets removed or changes to the same colour".
 *
 * Two separate mistakes, and both are fixed here:
 *
 * 1. **Scope.** The colour belongs to the *tile*, not the card. The label strip stays on the
 *    theme's surface, so a folder's name is legible whatever colour it is.
 * 2. **Contrast.** Even inside the tile, a fixed ink colour cannot work across a palette.
 *    [readableInkOn] picks black or white by luminance, so the icon is visible on a pale
 *    swatch and on a dark one.
 *
 * ## ⭐ ROADMAP M10 — the default fill
 *
 * An uncoloured folder used to take Material's `secondaryContainer`. It takes [Ink.PanelRaised] now:
 * the tile sits *inside* an `Ink.PanelSolid` card, so "one step up, for a panel that sits on top of
 * another panel" is literally what this is, and a folder with no colour reads as a recess in the
 * card rather than as a block of some other hue.
 *
 * ⚠️ Nothing else here changed, and that is the point of [readableInkOn]: the contrast rule is a
 * function of the tile's own colour, so a palette swap underneath it needs no second decision. The
 * new default is dark, so the glyph comes out white — by the same arithmetic that put black on a
 * pale yellow swatch before the redesign and still does.
 *
 * A **cover image is never tinted**, for the same reason a note's thumbnail is not: it is the user's
 * picture, or a page of their handwriting, and the design darkens the chrome around content rather
 * than the content.
 */
@Composable
fun FolderTile(folder: Folder, cover: ImageBitmap?, modifier: Modifier = Modifier) {
    // ⚠️ **Not [Ink.PanelRaised].** That is the fill the appearance dialog paints its preview
    // *panel* in, so an uncoloured folder and the panel behind it were byte-identical and the tile
    // vanished into its own preview — the one screen whose entire job is showing you what the tile
    // will look like. [Ink.SegmentActive] is the colour this defaulted to before the redesign
    // (Material's `secondaryContainer`), so nothing about the uncoloured folder actually changed.
    val tint = folder.colorArgb?.let { Color(it) } ?: Ink.SegmentActive
    Box(modifier = modifier.background(tint), contentAlignment = Alignment.Center) {
        // A cover that has not loaded — or a note that has no thumbnail yet — falls back to
        // the icon rather than to a blank tile. The icon and colour are always set, which is
        // the reason FolderCover keeps them alongside the picture.
        if (cover != null && folder.cover != FolderCover.Icon) {
            Image(
                bitmap = cover,
                contentDescription = null,
                // Crop, not Fit: a page is taller than the tile and letterboxing a cover
                // leaves bands of tint that read as a rendering fault.
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            FolderGlyph(
                icon = folder.icon,
                tint = readableInkOn(tint),
                modifier = Modifier.fillMaxWidth(GLYPH_FRACTION).fillMaxHeight(GLYPH_FRACTION),
            )
        }
    }
}

/**
 * Black or white, whichever is legible on [background].
 *
 * Relative luminance rather than a naive average of the channels: the eye is far more
 * sensitive to green than to blue, so an average calls a saturated blue "light" and puts
 * black on it. Compose's [luminance] already applies the sRGB curve and the ITU weights.
 *
 * The threshold is 0.5 rather than the WCAG contrast computation because there are only two
 * candidates here; picking the further one is the same answer with none of the arithmetic.
 */
fun readableInkOn(background: Color): Color =
    if (background.luminance() > MID_LUMINANCE) Color.Black.copy(alpha = INK_ALPHA) else Color.White

/** How much of the tile the glyph fills. Leaves a margin so it reads as an icon, not a fill. */
private const val GLYPH_FRACTION = 0.42f

private const val MID_LUMINANCE = 0.5f

/** Black at full strength is harsher than the palette; a touch of transparency softens it. */
private const val INK_ALPHA = 0.75f
