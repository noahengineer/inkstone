package dev.inkstone.android.ink

import android.annotation.SuppressLint
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.EasingFunction
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.SelfOverlap
import kotlin.math.PI

/*
 * Inkstone's own brush families. SPEC §3.4.
 *
 * These replace `StockBrushes` entirely. That is a deliberate reversal of M1's
 * first implementation and the reasons are worth keeping.
 *
 * ## Why not the stock brushes
 *
 * `StockBrushes` wires `DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE` and
 * `DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE` into `SIZE_MULTIPLIER` and
 * `OPACITY_MULTIPLIER` — ten behaviour entries across the set. Those are the
 * start- and end-tapers, and they are parameterised **along the stroke**.
 *
 * That makes a stroke un-cuttable. Partial erase rebuilds each surviving piece
 * as a new `Stroke` (SPEC §3.5 — a split is delete + add, never mutation), and
 * a new stroke re-runs the whole width and opacity profile from its own start.
 * So erasing through the middle of a line produced two ends that faded to a
 * point instead of two blunt cuts. Reported from the tablet as "the erasing is
 * not that good", and correctly diagnosed there as something structural rather
 * than a tuning problem.
 *
 * **The rule these families are built around: nothing here may depend on
 * distance or time along the stroke.** Every behaviour below is driven by an
 * instantaneous property of the input — pressure, tilt, speed. A fragment cut
 * from the middle of a stroke then renders exactly as that part of the original
 * did, because none of its inputs know where the stroke began or ends.
 *
 * A fountain pen still starts thin, but for the physical reason rather than a
 * scripted one: pressure ramps as the nib lands, and pressure is what drives
 * the width.
 *
 * ## The restricted-API trade, revisited
 *
 * Every `BrushFamily` constructor is `@RestrictTo(LIBRARY_GROUP)` on top of
 * `@ExperimentalInkCustomBrushApi` (see `docs/M1-INK.md` §2.1). M1 first
 * suppressed that for a single function; this file widens it to the whole brush
 * layer, which was a decision made explicitly rather than by drift.
 *
 * What it buys is not just the taper fix. `BrushBehavior` in 1.0.0 is a
 * dataflow graph — `SourceNode`, `ConstantNode`, `NoiseNode`, `BinaryOpNode`,
 * `InterpolationNode`, `DampingNode`, `ResponseNode`, `FallbackFilterNode`,
 * `ToolTypeFilterNode`, `PolarTargetNode` — over ~38 input sources and targets
 * that include hue, saturation, luminosity and lateral position offset, with
 * `BrushPaint` supplying textured layers, blend modes and particle stamping.
 * That is an art-application brush engine, and `StockBrushes` is a thin sample
 * on top of it. Using it directly is the whole point of choosing `androidx.ink`.
 *
 * **Revisit when the `androidxInk` pin moves.** If the API is public by then,
 * delete the suppression. This file is where that decision lands.
 *
 * Block comment rather than KDoc: this describes the file, not one declaration.
 */

/**
 * Builds the four SPEC §3.4 families for a given [InkInputModel].
 *
 * Families are immutable and shared by every [androidx.ink.brush.Brush] of that
 * kind, so this runs once per input-model change and never on the ink path.
 */
@OptIn(ExperimentalInkCustomBrushApi::class)
@SuppressLint("RestrictedApi") // See the file comment. Deliberate, and confined to this file.
internal fun buildBrushFamilies(inputModel: InkInputModel): Map<InkBrushKind, BrushFamily> {
    val model = inputModel.toInkModel()
    return mapOf(
        InkBrushKind.FOUNTAIN to fountain(model),
        InkBrushKind.BALLPOINT to ballpoint(model),
        InkBrushKind.PENCIL to pencil(model),
        InkBrushKind.HIGHLIGHTER to highlighter(model),
    )
}

/**
 * A single source → target mapping.
 *
 * [responseTimeMillis] is a damping window: the behaviour's output eases toward
 * the new value over that period instead of snapping. It matters for anything
 * driven by speed, which is noisy sample to sample.
 */
@OptIn(ExperimentalInkCustomBrushApi::class)
@SuppressLint("RestrictedApi")
@Suppress("LongParameterList") // One behaviour is genuinely this many facts.
private fun behavior(
    source: BrushBehavior.Source,
    target: BrushBehavior.Target,
    sourceFrom: Float,
    sourceTo: Float,
    targetFrom: Float,
    targetTo: Float,
    curve: EasingFunction = EasingFunction.Predefined.LINEAR,
    responseTimeMillis: Long = 0L,
    fallbackFor: BrushBehavior.OptionalInputProperty? = null,
): BrushBehavior = BrushBehavior(
    source = source,
    target = target,
    sourceValueRangeStart = sourceFrom,
    sourceValueRangeEnd = sourceTo,
    targetModifierRangeStart = targetFrom,
    targetModifierRangeEnd = targetTo,
    // Pressure above 1 is legal on some digitisers (SPEC §3.3) and speed has no
    // upper bound at all, so every range here has to clamp rather than wrap.
    sourceOutOfRangeBehavior = BrushBehavior.OutOfRange.CLAMP,
    responseCurve = curve,
    responseTimeMillis = responseTimeMillis,
    enabledToolTypes = BrushBehavior.ALL_TOOL_TYPES,
    isFallbackFor = fallbackFor,
)

/**
 * **Fountain** (SPEC §3.4) — pressure drives width, with a slight velocity
 * taper. The default pen.
 *
 * The velocity term is what makes a fast stroke look drawn rather than
 * extruded, and it is damped because raw per-sample speed is noisy enough to
 * make the line shimmer if fed straight in.
 *
 * The third behaviour is the interesting one: `isFallbackFor = PRESSURE` means
 * it is active **only when the digitiser reports no pressure at all** — a
 * finger, a mouse, or a stylus that does not report it. Without it, those
 * inputs would draw a dead uniform line; with it, they get width from speed
 * instead. This is the kind of thing the stock brushes do not do for you.
 */
@OptIn(ExperimentalInkCustomBrushApi::class)
@SuppressLint("RestrictedApi")
private fun fountain(model: BrushFamily.InputModel) = BrushFamily(
    tip = BrushTip(
        behaviors = listOf(
            behavior(
                BrushBehavior.Source.NORMALIZED_PRESSURE,
                BrushBehavior.Target.SIZE_MULTIPLIER,
                0f,
                1f,
                LIGHTEST_NIB,
                1f,
                curve = EasingFunction.Predefined.EASE_IN_OUT,
            ),
            behavior(
                BrushBehavior.Source.INPUT_SPEED_IN_CENTIMETERS_PER_SECOND,
                BrushBehavior.Target.SIZE_MULTIPLIER,
                0f,
                FAST_CM_PER_SECOND,
                1f,
                FAST_NIB,
                responseTimeMillis = SPEED_DAMPING_MS,
            ),
            behavior(
                BrushBehavior.Source.INPUT_SPEED_IN_CENTIMETERS_PER_SECOND,
                BrushBehavior.Target.SIZE_MULTIPLIER,
                0f,
                FAST_CM_PER_SECOND,
                1f,
                NO_PRESSURE_FAST_NIB,
                responseTimeMillis = SPEED_DAMPING_MS,
                fallbackFor = BrushBehavior.OptionalInputProperty.PRESSURE,
            ),
        ),
    ),
    paint = BrushPaint(),
    clientBrushFamilyId = "inkstone-fountain-v1",
    inputModel = model,
)

/**
 * **Ballpoint** (SPEC §3.4) — uniform width, no pressure response.
 *
 * Deliberately no behaviours at all. It is the reference brush: if a ballpoint
 * line ever varies, something upstream of the brush system is wrong.
 */
@OptIn(ExperimentalInkCustomBrushApi::class)
@SuppressLint("RestrictedApi")
private fun ballpoint(model: BrushFamily.InputModel) = BrushFamily(
    tip = BrushTip(),
    paint = BrushPaint(),
    clientBrushFamilyId = "inkstone-ballpoint-v1",
    inputModel = model,
)

/**
 * **Pencil** (SPEC §3.4) — pressure *and* tilt drive opacity and width.
 *
 * What each term is for, holding a real pencil:
 *
 *  - press harder → darker (pressure → opacity)
 *  - press harder → slightly broader (pressure → size)
 *  - lay it flat → lighter, because less of the graphite core loads any one
 *    point (tilt → opacity)
 *  - lay it flat → wider, because more of the side meets the paper
 *    (tilt → width). This is the shading behaviour SPEC §3.3 says is lost
 *    entirely when tilt is ignored.
 *
 * Tilt is 0 perpendicular to the screen and π/2 flat against it (SPEC §3.3).
 * Both tilt terms declare no fallback, so on a device that reports no tilt they
 * simply contribute nothing rather than pinning to an extreme.
 *
 * Still missing the grain texture SPEC §3.4 asks for — that needs a texture
 * asset of ours, and `BrushPaint.TextureLayer` is where it will go.
 */
@OptIn(ExperimentalInkCustomBrushApi::class)
@SuppressLint("RestrictedApi")
private fun pencil(model: BrushFamily.InputModel) = BrushFamily(
    tip = BrushTip(
        behaviors = listOf(
            behavior(
                BrushBehavior.Source.NORMALIZED_PRESSURE,
                BrushBehavior.Target.OPACITY_MULTIPLIER,
                0f,
                1f,
                LIGHTEST_TOUCH_OPACITY,
                1f,
            ),
            behavior(
                BrushBehavior.Source.NORMALIZED_PRESSURE,
                BrushBehavior.Target.SIZE_MULTIPLIER,
                0f,
                1f,
                LIGHTEST_TOUCH_SIZE,
                1f,
            ),
            behavior(
                BrushBehavior.Source.TILT_IN_RADIANS,
                BrushBehavior.Target.OPACITY_MULTIPLIER,
                UPRIGHT_RADIANS,
                FLAT_RADIANS,
                1f,
                FLAT_PENCIL_OPACITY,
            ),
            behavior(
                BrushBehavior.Source.TILT_IN_RADIANS,
                BrushBehavior.Target.WIDTH_MULTIPLIER,
                UPRIGHT_RADIANS,
                FLAT_RADIANS,
                1f,
                FLAT_PENCIL_WIDTH,
            ),
        ),
    ),
    paint = BrushPaint(),
    clientBrushFamilyId = "inkstone-pencil-v2",
    inputModel = model,
)

/**
 * **Highlighter** (SPEC §3.4) — uniform width, drawn behind ink.
 *
 * [SelfOverlap.DISCARD] is the whole trick: without it, a highlighter stroke
 * that crosses itself darkens at the crossing, which no physical highlighter
 * does and which is the usual complaint about highlighters in note apps.
 *
 * "Draws behind ink" is a z-order rule and belongs to [StrokeStore], not here.
 * The stroke-stabilization slider SPEC §3.4 also mentions is M10's.
 */
@OptIn(ExperimentalInkCustomBrushApi::class)
@SuppressLint("RestrictedApi")
private fun highlighter(model: BrushFamily.InputModel) = BrushFamily(
    tip = BrushTip(),
    paint = BrushPaint(selfOverlap = SelfOverlap.DISCARD),
    clientBrushFamilyId = "inkstone-highlighter-v1",
    inputModel = model,
)

// --- Tuning -----------------------------------------------------------------
// Every one of these is a feel decision, and every one is meant to be argued
// with on the tablet rather than defended on a desk.

/**
 * Fountain width at the lightest touch, as a fraction of full-pressure width.
 *
 * Was 0.35, which is roughly what a fountain pen does on paper and which was reported from
 * the tablet as not reacting to pressure at all. 0.18 is more than a real nib varies, and
 * that is the point: a line that visibly thins and swells is the thing people mean by
 * "pressure sensitive", and a physically faithful 3× range reads as uniform next to it.
 *
 * If the Measure panel shows pressure **not varying** on this device, this constant is not
 * the problem and no value of it will be — see [StylusStats].
 */
private const val LIGHTEST_NIB = 0.18f

/** Speed at which the velocity taper reaches full effect. */
private const val FAST_CM_PER_SECOND = 40f

/** Fountain width at speed. Slight, per SPEC §3.4's "slight velocity taper". */
private const val FAST_NIB = 0.85f

/** Fountain width at speed when there is no pressure to read — a stronger cue. */
private const val NO_PRESSURE_FAST_NIB = 0.55f

/** Damping for speed-driven terms. Raw per-sample speed shimmers without it. */
private const val SPEED_DAMPING_MS = 24L

private const val LIGHTEST_TOUCH_OPACITY = 0.35f
private const val LIGHTEST_TOUCH_SIZE = 0.7f
private const val FLAT_PENCIL_OPACITY = 0.55f
private const val FLAT_PENCIL_WIDTH = 1.8f

private const val UPRIGHT_RADIANS = 0f
private val FLAT_RADIANS = (PI / 2).toFloat()
