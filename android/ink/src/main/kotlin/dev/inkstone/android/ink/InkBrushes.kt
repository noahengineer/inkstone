package dev.inkstone.android.ink

import android.annotation.SuppressLint
import androidx.annotation.ColorInt
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.ExperimentalInkCustomBrushApi

/**
 * ⭐ ROADMAP M8a — which tool the toolbar has selected.
 *
 * One value rather than a boolean per tool. Until M8a there was one tool that was not the pen
 * and `eraserSelected: Boolean` was an honest way to say so; adding a second would have made
 * "eraser and lasso both on" representable, and this codebase has twice paid for a wrong state
 * being expressible (`apply(…, undoable)` and `NoteSession.close`'s defaulted argument, both
 * deleted in M6 for the same reason).
 *
 * A flipped pen tip and a held barrel button are **not** here: they are decided per
 * `MotionEvent` and override whatever this says, because that is what SPEC §3.3 requires of
 * hardware.
 */
public enum class InkTool {
    PEN,
    ERASER,

    /** Lasso, move, resize, rotate — see `SelectionController`. */
    SELECT,

    /**
     * ⭐ ROADMAP M8c — a transient dot with a fading trail, for pointing at things while talking.
     *
     * A tool rather than a modifier, because while it is selected the pen does not draw at all.
     * ⚠️ It is here and **the ruler is not**: a ruler does not replace the pen, it guides it, so it
     * is a switch on the surface (`InkSurfaceView.ruler`) and the tool stays [PEN]. Making it a
     * tool would have made "the ruler is out" and "I am drawing" mutually exclusive, which is the
     * opposite of what a ruler is for.
     *
     * See [LaserController] for why it can write nothing.
     */
    LASER,
}

/**
 * SPEC §3.4 — the four v1 brushes.
 *
 * The families themselves live in `InkstoneBrushFamilies.kt` and are ours, not
 * `StockBrushes`. That file explains why; the short version is that the stock
 * brushes taper by distance-along-stroke, which makes a stroke impossible to
 * cut cleanly.
 */
public enum class InkBrushKind {
    FOUNTAIN,
    BALLPOINT,
    PENCIL,
    HIGHLIGHTER,
    ;

    /**
     * SPEC §3.4 — the highlighter draws **over** ink, with plain alpha, so it tints what
     * it covers.
     *
     * ⚠️ **Reversed after M4, from the tablet, and it took three attempts.** The rule was
     * "draws *behind* ink" and was reported as "you can't highlight your own writing". The
     * whole sequence is recorded because the wrong turn in the middle is instructive:
     *
     * 1. **Behind** was only ever tenable because SPEC §3.4's other half — *"multiply
     *    blend"* — was never implemented. The highlighter was a plain translucent stroke,
     *    and plain translucent yellow drawn over black writing lightens it, so behind was
     *    the only order that kept writing legible. It went unquestioned for three
     *    milestones because nothing in the app highlighted anything but blank paper.
     * 2. **Multiply, on top** looked like the principled fix and is what a physical
     *    highlighter does. It is wrong here, and the reason is arithmetic: multiply cannot
     *    lighten, so yellow over black leaves the writing *exactly* black — which is
     *    **pixel-identical to drawing behind it**. It was reported back as the highlight
     *    still snapping behind the pen marks, because that is precisely what it looked
     *    like. Chasing the remaining difference into the wet layer was chasing nothing.
     * 3. **Plain alpha, on top** is what shipped. The marks get tinted. That is the only
     *    thing that reads as *on top of* the writing, because "leaves the writing
     *    untouched" and "looks like it is over the writing" are contradictory requirements
     *    and no blend mode satisfies both.
     *
     * Plain alpha also has a property multiply could not have: it is what the wet layer
     * already does natively, so a stroke does not change appearance at the instant it
     * lands, and the highlighter keeps the low-latency front buffer.
     * `InProgressStrokesView` front-buffers through a SurfaceView, which cannot carry a
     * blend mode at all — so any blended brush must give that up to stay consistent.
     */
    public val drawsOverInk: Boolean get() = this == HIGHLIGHTER
}

/**
 * The smoothing model applied to raw input before geometry is generated.
 *
 * This is the "why does it feel like it's chasing a spline rather than tracking
 * the nib" knob. [SPRING] runs the input through spring dynamics, which is
 * smoother and slightly laggier; [DIRECT] follows the samples more literally.
 *
 * Exposed as a setting because it is a matter of taste that cannot be settled
 * off-device, and because the difference is immediately obvious once you write
 * a word with each.
 */
public enum class InkInputModel {
    SPRING,
    DIRECT,
    ;

    @OptIn(ExperimentalInkCustomBrushApi::class)
    @SuppressLint("RestrictedApi")
    internal fun toInkModel(): BrushFamily.InputModel = when (this) {
        SPRING -> BrushFamily.SPRING_MODEL
        DIRECT -> BrushFamily.EXPERIMENTAL_NAIVE_MODEL
    }
}

/**
 * A brush the user has configured: which family, what colour, how wide.
 *
 * @property widthDp stroke width in dp, so a brush looks the same size on every
 *   panel. It is converted to page points when a [Brush] is built, because
 *   [Brush.size] is in the stroke's own coordinate space — which for this app is
 *   page space (SPEC §4.4 rule 1).
 */
public data class PenConfig(val kind: InkBrushKind, @param:ColorInt val colorArgb: Int, val widthDp: Float) {
    public companion object {
        /** SPEC §3.4: Fountain is the default. */
        public val Default: PenConfig = PenConfig(InkBrushKind.FOUNTAIN, BLACK_ARGB, DEFAULT_WIDTH_DP)

        private const val BLACK_ARGB = 0xFF1A1A1A.toInt()
        private const val DEFAULT_WIDTH_DP = 3f
    }
}

/**
 * Builds `androidx.ink` [Brush] objects for a [PenConfig].
 *
 * Families are built once and cached: constructing one crosses into native
 * code, and CLAUDE.md rule 7 forbids that between `ACTION_DOWN` and the front
 * buffer.
 *
 * @param pixelsPerPoint the display's `densityDpi / 72f`. A *display* constant,
 *   not the camera scale — a pen must not thicken on the page because the user
 *   zoomed in.
 */
public class InkBrushFactory(
    private val displayDensity: Float,
    private val pixelsPerPoint: Float,
    inputModel: InkInputModel = InkInputModel.SPRING,
) {

    private var families = buildBrushFamilies(inputModel)

    /**
     * The input smoothing model. Changing it rebuilds every family, so do it
     * from the toolbar and not while a stroke is in flight.
     *
     * Strokes already on the page keep the geometry they were drawn with —
     * their meshes are already tessellated. That is the honest behaviour: the
     * model affects capture, not replay.
     */
    public var inputModel: InkInputModel = inputModel
        set(value) {
            if (field != value) {
                field = value
                families = buildBrushFamilies(value)
            }
        }

    /**
     * @return a [Brush] whose size is in **page points**, because strokes are
     *   authored directly in page space (SPEC §4.4 rule 1). A 3 dp pen is 3 dp
     *   on the glass at 100% zoom and scales with the page thereafter, which is
     *   what a pen drawing on paper does.
     */
    internal fun brushFor(config: PenConfig): Brush {
        val widthPt = config.widthDp * displayDensity / pixelsPerPoint
        return Brush.createWithColorIntArgb(
            family = families.getValue(config.kind),
            colorIntArgb = config.colorArgb,
            size = widthPt,
            epsilon = epsilonPt,
        )
    }

    /**
     * The load path: rebuild the brush a stored stroke was captured with (SPEC §5.4).
     *
     * Everything is taken from the blob rather than from the current toolbar — size in
     * **page points**, not dp, and the capture-time [InkInputModel]. A stroke drawn at 3 dp
     * on a 2× panel and reopened on a 1× panel must be the same width on the page, and one
     * captured with spring smoothing must not be re-tessellated with the naive model
     * because the user has since changed the setting.
     *
     * [inputModel] therefore builds its own family rather than reading [families], which
     * follows the toolbar.
     */
    internal fun brushFor(
        kind: InkBrushKind,
        @ColorInt colorArgb: Int,
        sizePt: Float,
        epsilonPt: Float,
        inputModel: InkInputModel,
    ): Brush {
        val family = if (inputModel == this.inputModel) {
            families.getValue(kind)
        } else {
            historicalFamilies.getOrPut(inputModel) { buildBrushFamilies(inputModel) }.getValue(kind)
        }
        return Brush.createWithColorIntArgb(
            family = family,
            colorIntArgb = colorArgb,
            size = sizePt,
            epsilon = epsilonPt,
        )
    }

    /**
     * Families for input models other than the current one, built once each.
     *
     * A note whose strokes were captured with both models loads both, and constructing a
     * family crosses into native code — so they are cached rather than rebuilt per stroke.
     * There are two models, so this holds at most one entry.
     */
    private val historicalFamilies = HashMap<InkInputModel, Map<InkBrushKind, BrushFamily>>()

    /**
     * Ink's geometric simplification tolerance, in page points.
     *
     * **This must not scale with brush width.** It did until M1's device test,
     * and the symptom was a fat brush drawing visible polygons: a tolerance
     * proportional to width means the absolute error grows with the brush.
     *
     * Epsilon is a *rendering* quantity, so it comes from the display — a
     * fraction of a physical pixel converted into stroke units. Google's ink
     * samples use 0.1 with stroke space in pixels; this is half that, because
     * unlike those samples this app zooms, and a stroke tessellated once at
     * 100% is still on screen at 1600%.
     *
     * Internal rather than private since M4: a stroke rebuilt from a PDF
     * ([InkStrokeRebuilder]) has no captured epsilon to restore, and the display's
     * is the right default for one — the same one a stroke drawn here would get.
     */
    internal val epsilonPt: Float = EPSILON_PX / pixelsPerPoint

    private companion object {
        const val EPSILON_PX = 0.05f
    }
}
