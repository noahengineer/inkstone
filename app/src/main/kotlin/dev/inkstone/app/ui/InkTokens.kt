package dev.inkstone.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * ⭐ ROADMAP M10 — the palette the whole app is drawn from.
 *
 * Every value here is read straight off the reference design (`aura_notes_typography_ui.html`)
 * rather than invented, because the point of a redesign delivered as a rendered page is that the
 * page *is* the specification — a colour "close to" the mock is a colour that will read as wrong
 * beside it.
 *
 * ## Two documents, two accents, and which one won
 *
 * The written design brief names Deep Charcoal `#121212` and Electric Cyan `#00E5FF`; the HTML it
 * describes uses zinc-950 `#09090B` and sky-400 `#38BDF8`, and paints *active chips* in zinc-200
 * rather than in any accent at all. They are the same design at two levels of precision, so the
 * concrete artefact wins and the brief's values are recorded beside each token as a comment. If the
 * brief's hues are wanted instead, [Canvas] and [Focus] are the only two lines that change.
 *
 * ## ⚠️ There is no backdrop blur, and there cannot be one
 *
 * The brief asks for `backdrop-filter: blur(12px)`. Compose's `Modifier.blur` blurs a composable's
 * **own** content, not what is behind it, and the only way to blur a backdrop is to capture the
 * pixels underneath — which on this screen is impossible in principle, not merely expensive:
 * `InProgressStrokesView` front-buffers through a `SurfaceView` that SurfaceFlinger composites over
 * the window (SPEC §3.2, and the same fact that stops wet ink carrying a blend mode). An app cannot
 * read those pixels back, so a blur pass over the note editor would sample everything except the
 * ink.
 *
 * What actually produces the effect at these values is the translucency, not the blur: [Panel] is
 * 65 % opaque over a near-black canvas, so a panel is a *lighter* rectangle with a hairline edge and
 * a long soft shadow — which is what the mock reads as. The blur would be visible only where the
 * design deliberately has nothing detailed to blur.
 */
object Ink {

    /** The paper's surround. Brief: `#121212`. */
    val Canvas: Color = Color(0xFF09090B)

    /** The dot grid over [Canvas] — `rgba(113,113,122,0.15)` at 24 dp. */
    val CanvasDot: Color = Color(0x26717179)

    /**
     * Frosted-glass fill: zinc-900 at **90 %**, not the mock's 65 %.
     *
     * ⚠️ The mock is a page with a dark canvas behind every panel. This app's chrome spends most of
     * its life over **white paper** — at fit-width the page fills the viewport — and a 65 % panel
     * over white composites to about 40 % luminance, on which [TextMuted] is very nearly invisible.
     * That is what *"the arrows are hard to see"* was: a disabled chip whose contrast depended on
     * what the user happened to have scrolled underneath it.
     *
     * 90 % keeps the effect (the page still shows faintly through, and the hairline edge and shadow
     * do the rest) while making every chip's contrast a property of the chip rather than of the
     * document. See the class KDoc on why none of this is blurred.
     */
    val Panel: Color = Color(0xE618181B)

    /** The same surface where translucency would let content behind it interfere — menus, sheets. */
    val PanelSolid: Color = Color(0xFF18181B)

    /** One step up, for a panel that sits on top of another panel. */
    val PanelRaised: Color = Color(0xFF212124)

    /** `rgba(63,63,70,0.3)` — the 1 dp edge that separates a panel from the canvas without a shadow. */
    val PanelBorder: Color = Color(0x4D3F3F46)

    /** A solid hairline, for dividers inside a panel where a translucent one would disappear. */
    val Hairline: Color = Color(0xFF3F3F46)

    /** A chip under a finger, before it becomes [Accent]. */
    val ChipPressed: Color = Color(0x663F3F46)

    /**
     * The active-chip fill — zinc-200, inverted against [OnAccent].
     *
     * ⚠️ Deliberately **not** the accent hue. The mock's contrast comes from inversion, and a row of
     * seven chips with one painted cyan reads as a warning; one painted near-white reads as chosen.
     */
    val Accent: Color = Color(0xFFE4E4E7)

    val OnAccent: Color = Color(0xFF09090B)

    /** Selection, handles, focus rings and live readouts. Brief: `#00E5FF`. */
    val Focus: Color = Color(0xFF38BDF8)

    /**
     * ⚠️ [Focus]'s twin for anything drawn **on the page** rather than on the canvas — sky-500.
     *
     * Chrome sits on a near-black canvas, where sky-400 is what reads. Selection handles, the lasso,
     * a crop frame and a text box's editing border sit on **white paper**, where the same sky-400 is
     * a pale line on a bright field. One accent for both surfaces is a colour that is correct on
     * neither, so there are two — and the reference design makes the same split, outlining its canvas
     * objects `border-sky-500` while its focus rings are `#38bdf8`.
     *
     * ⚠️ `:android:ink` cannot import this (`:app` is downstream of it), so
     * `SelectionOverlayView.ACCENT` carries the same value as a literal and says so. If one moves,
     * move both.
     */
    val FocusOnPaper: Color = Color(0xFF0EA5E9)

    val TextStrong: Color = Color(0xFFE4E4E7)
    val TextNormal: Color = Color(0xFFA1A1AA)
    val TextMuted: Color = Color(0xFF71717A)

    /** Destructive chips. Never used for a chip that only *starts* a destructive flow. */
    val Danger: Color = Color(0xFFF87171)
    val DangerWash: Color = Color(0x26EF4444)

    /** The recessed track a segmented control's options sit in. */
    val SegmentTrack: Color = Color(0x6627272A)

    /** The raised pill inside that track. Lighter than the track, darker than [Accent]. */
    val SegmentActive: Color = Color(0xCC52525B)

    /** M12's running-recording pill. */
    val Recording: Color = Color(0xFFEF4444)

    /** The wash behind [Recording], so the pill reads as a state and not as an error. */
    val RecordingWash: Color = Color(0x3DEF4444)

    /** Paper. The one light surface in the app, and it is the document rather than the chrome. */
    val Paper: Color = Color(0xFFFFFFFF)
}

/**
 * Corner radii, paddings and gaps, all taken from the reference design.
 *
 * Named rather than inlined because the same numbers appear in the dock, the page bar, the
 * contextual toolbars and the panels — five places that must not drift apart, which is exactly what
 * happens when `16.dp` is typed five times.
 */
object InkDims {

    /** `border-radius: 16px` on `.glass-panel`. */
    val PanelCorner = RoundedCornerShape(16.dp)

    /** `!rounded-full` — every floating bar in the mock is a pill. */
    val Pill = RoundedCornerShape(percent = 50)

    /** `.chip { border-radius: 10px }`. */
    val ChipCorner = RoundedCornerShape(10.dp)

    /** `.segmented { border-radius: 12px; padding: 3px }` and its 8 px inner chips. */
    val SegmentCorner = RoundedCornerShape(12.dp)
    val SegmentInnerCorner = RoundedCornerShape(8.dp)

    /** `p-1.5` — the ring of space between a pill's edge and the chips inside it. */
    val BarPadding = 6.dp

    /** `gap-1` / `gap-2` between chips. */
    val ChipGap = 4.dp
    val GroupGap = 8.dp

    /** How far a floating widget sits from the edge of the screen. */
    val EdgePadding = 12.dp

    /** `background-size: 24px 24px`. */
    val DotSpacing = 24.dp
    val DotRadius = 1.dp

    /**
     * `.chip { padding: 6px 14px }` — but 11 dp, not 14, and the difference is a units mistake worth
     * recording.
     *
     * The mock's 14 **CSS px** sit in a ~1280 px-wide layout. A dp on the Galaxy Tab is a device
     * pixel at density 1, so copying "14" put 14 dp of padding either side of every chip on a screen
     * that is 1280 **dp** wide — the same proportion the mock spends on a browser window, spent
     * twice over. Measured consequence: the page bar's eleven chips ran ~70 dp past the space
     * between the history and zoom widgets and `SHARE` scrolled off the end.
     */
    val ChipPaddingH = 11.dp
    val ChipPaddingV = 6.dp

    /** A chip's minimum tap target. Below this a text chip is a 24 dp-tall pill nobody can hit. */
    val ChipMinHeight = 34.dp

    /** `.color-swatch-circle { width: 20px }`, scaled up to something a stylus can land on. */
    val SwatchSize = 22.dp

    /** How far a contextual toolbar floats above the object it belongs to. */
    val ContextGap = 14.dp
}

/**
 * The type ramp for chrome.
 *
 * The brief's *"M1-era typographic approach — explicit text chips over ambiguous icons"* is
 * implemented literally: a chip is a word, uppercased and tracked out, and every number the UI
 * reports is monospaced so a readout does not change width as it counts.
 */
object InkType {

    /** `.chip` — 13 px / 500, uppercased and tracked at the call site. */
    val ChipSize = 13.sp

    /**
     * A chip whose whole label is a symbol — `‹`, `›`, `◀`, `▶`, `✕`, `⋯`.
     *
     * ⚠️ Bigger than [ChipSize], because a glyph carries its meaning in a few strokes where a word
     * carries it in six letters. At 13 sp in the monospaced readout face — which is what these used
     * before, by being marked as *values* — a `‹` is a hairline in the middle of an otherwise empty
     * cell, and the page arrows were reported as *"very small and hard to see"*.
     */
    val GlyphSize = 19.sp
    val ChipTracking: TextUnit = 0.08.em

    /** `.micro-label` — 10.4 px mono, uppercase, `letter-spacing: 0.05em`. */
    val MicroSize = 11.sp
    val MicroTracking: TextUnit = 0.06.em

    /** A live number — the zoom percentage, the ruler's angle, the elapsed recording. */
    val ReadoutSize = 13.sp
}

/**
 * `box-shadow: 0 12px 40px -12px rgba(0,0,0,0.7)`, as far as Compose expresses it.
 *
 * A [Shadow] on text is not the panel shadow; panels use `Modifier.shadow`, and this exists for the
 * one place a light glyph sits directly on the canvas.
 */
val InkTextShadow: Shadow = Shadow(color = Color(0xB3000000), blurRadius = 8f)
