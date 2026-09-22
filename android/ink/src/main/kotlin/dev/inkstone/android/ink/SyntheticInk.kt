package dev.inkstone.android.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.StrokeObject
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The 5,000-stroke page that ROADMAP M1's second acceptance criterion requires.
 *
 * Hand-writing 5,000 strokes to run a frame-rate measurement is not a test, it
 * is an afternoon. This builds a page that is *harder* than real handwriting —
 * strokes are spread across the whole page rather than clustered in lines, so
 * viewport culling helps less than it would with real notes.
 *
 * Deterministic by seed, so two runs of the measurement are comparable.
 *
 * **Call this off the main thread.** Each [Stroke] tessellates in native code;
 * 5,000 of them is on the order of a second, and CLAUDE.md rule 7 does not make
 * an exception for setup code that happens to run while the user is not writing.
 */
public object SyntheticInk {

    /**
     * An opaque batch of finished strokes.
     *
     * Opaque because `androidx.ink`'s [Stroke] must not cross out of this
     * module (CLAUDE.md, "third-party types don't leak across module
     * boundaries"). `:app` receives one of these and hands it straight back to
     * [InkSurfaceView.addPregeneratedStrokes] without ever naming an ink type.
     */
    public class Batch internal constructor(internal val items: List<Pair<Stroke, InkBrushKind>>) {
        public val size: Int get() = items.size
    }

    /**
     * @param count how many strokes to generate.
     * @param pointsPerStroke roughly what a 1 cm pen mark produces at 240 Hz.
     */
    public fun generate(
        count: Int,
        page: PageSize,
        factory: InkBrushFactory,
        seed: Int = DEFAULT_SEED,
        pointsPerStroke: Int = DEFAULT_POINTS_PER_STROKE,
    ): Batch {
        val random = Random(seed)
        val result = ArrayList<Pair<Stroke, InkBrushKind>>(count)

        repeat(count) { index ->
            // One highlighter in twenty, so the behind/above render split is
            // actually exercised by the measurement.
            val kind = if (index % HIGHLIGHTER_EVERY == 0) InkBrushKind.HIGHLIGHTER else InkBrushKind.FOUNTAIN
            val config = PenConfig(
                kind = kind,
                colorArgb = PALETTE[index % PALETTE.size],
                widthDp = if (kind == InkBrushKind.HIGHLIGHTER) HIGHLIGHTER_WIDTH_DP else PEN_WIDTH_DP,
            )

            // A fresh batch per stroke: `Stroke` takes a StrokeInputBatch
            // directly, and the alternative — reusing one and calling
            // `toImmutable()` — is `@RestrictTo(LIBRARY_GROUP)` in ink 1.0.0.
            val batch = MutableStrokeInputBatch()
            val originX = random.nextFloat() * (page.widthPt - STROKE_EXTENT_PT)
            val originY = random.nextFloat() * (page.heightPt - STROKE_EXTENT_PT)
            val direction = random.nextFloat() * TAU
            val curl = (random.nextFloat() - HALF) * CURL

            for (sample in 0 until pointsPerStroke) {
                val t = sample.toFloat() / (pointsPerStroke - 1)
                val angle = direction + curl * t
                batch.add(
                    type = InputToolType.STYLUS,
                    x = originX + cos(angle) * STROKE_EXTENT_PT * t,
                    y = originY + sin(angle) * STROKE_EXTENT_PT * t,
                    elapsedTimeMillis = (t * STROKE_DURATION_MS).toLong(),
                    // A pressure ramp, so the fountain brush actually varies
                    // width and the mesh has the vertex count real ink has.
                    pressure = MIN_PRESSURE + (1f - MIN_PRESSURE) * sin(t * PI_F),
                    tiltRadians = TILT_RADIANS,
                    // Wrapped, and only here — x and y above keep the *unwrapped* [angle].
                    // See [turn].
                    orientationRadians = turn(angle),
                )
            }

            result += Stroke(factory.brushFor(config), batch) to kind
        }
        return Batch(result)
    }

    /**
     * A synthetic page as **encoded ops**, ready to write to a note.
     *
     * ROADMAP M3's frame-rate gate needs 500 notes, and a library of 500 *blank* notes would
     * measure nothing: every thumbnail would be an identical near-empty WebP that decodes in
     * no time, so the two costs the gate exists to catch — rendering a page and decoding its
     * picture — would both be missing.
     *
     * So the fixture writes real ink. This is [generate] plus the encode step, kept here
     * because `StrokeGeometryCodec` is internal to this module and `Stroke` must not cross
     * out of it (CLAUDE.md, "third-party types don't leak across module boundaries"). The
     * caller supplies its own id and clock generators and receives ordinary [PageOp]s.
     *
     * **Off the main thread.** Tessellation plus encoding, times [count].
     */
    public fun generatePageOps(
        count: Int,
        page: PageSize,
        factory: InkBrushFactory,
        seed: Int,
        nextObjectId: () -> ObjectId,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> {
        val batch = generate(count, page, factory, seed)
        var z = FractionalKey.Initial
        return batch.items.map { (stroke, kind) ->
            z = FractionalKey.between(z, null)
            PageOp.AddObject(
                opId = nextOpId(),
                clock = nextClock(),
                obj = StrokeObject(
                    id = nextObjectId(),
                    geometry = StrokeGeometryCodec.encode(stroke, kind, InkInputModel.SPRING),
                    z = z,
                ),
            )
        }
    }

    /**
     * [radians] reduced to `[0, TAU)` — the only range `StrokeInput::orientation` accepts.
     *
     * ⛔ **Without this, roughly one stroke in sixteen could not be built at all**, and it had
     * nothing to do with the architecture. [generate] draws `direction` uniformly from
     * `[0, TAU)` and bends it by `curl` in `[-0.8, +0.8]`; `angle` is monotonic in `t` and
     * starts at `direction`, so a stroke escapes exactly when `direction + curl` leaves the
     * range — `E|curl| / TAU` = **6.4%**, independent of `pointsPerStroke` and of the CPU.
     * `MutableStrokeInputBatch.add` then throws `IllegalArgumentException` and the whole
     * generation dies. That is why all twelve `StrokeStoreEraseTest` cases failed on the
     * Galaxy Tab and not only on an emulator, and why both production callers
     * (`InkScreen.loadSyntheticPage`, `LibraryFixture`) threw where a user pressed a button.
     *
     * ⚠️ **Wrap the orientation, never the angle.** `cos`/`sin` of the wrapped value differ in
     * the last bit for almost every slightly-negative input, so wrapping before the
     * trigonometry would move every generated point and make this fix change the fixture's
     * output. Wrapping here leaves x and y bit-identical, which is what keeps *"deterministic
     * by seed"* true: no seed that works today produces different bytes tomorrow. Nothing
     * reads the field back either — `InkstoneBrushFamilies` sources only pressure, speed and
     * tilt — so orientation is carried for fidelity and tessellates to nothing.
     *
     * ⚠️ **The `>= TAU` line is load-bearing, not defensive.** `TAU` is the nearest Float to
     * 2π and sits just *above* it, and flooring-mod rounds every input in roughly
     * `[-2.4e-7, 0)` up to exactly `TAU` — which the exclusive upper bound still rejects.
     * The next Float down is safely below 2π, so `< TAU` is exactly the right test.
     * For anything already in range the whole function is the identity, bit for bit.
     */
    internal fun turn(radians: Float): Float {
        val remainder = radians % TAU
        val wrapped = if (remainder < 0f) remainder + TAU else remainder
        return if (wrapped >= TAU) 0f else wrapped
    }

    private const val DEFAULT_SEED = 20260805
    private const val DEFAULT_POINTS_PER_STROKE = 24
    private const val HIGHLIGHTER_EVERY = 20
    private const val PEN_WIDTH_DP = 3f
    private const val HIGHLIGHTER_WIDTH_DP = 14f
    private const val STROKE_EXTENT_PT = 28f
    private const val STROKE_DURATION_MS = 120f
    private const val MIN_PRESSURE = 0.25f
    private const val TILT_RADIANS = 0.4f
    private const val CURL = 1.6f
    private const val HALF = 0.5f
    private const val TAU = 6.2831855f
    private const val PI_F = 3.1415927f

    private val PALETTE = intArrayOf(
        0xFF1A1A1A.toInt(),
        0xFF1D4ED8.toInt(),
        0xFFB91C1C.toInt(),
        0xFF15803D.toInt(),
    )
}
