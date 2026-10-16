package dev.inkstone.core.handwriting

import kotlin.math.PI
import kotlin.random.Random

/**
 * ⭐ How much generated writing varies — docs/HANDWRITING-SYNTHESIS.md §6.1's table.
 *
 * Every bound is **one side** of a uniform range, in x-heights or radians, and every value is drawn
 * fresh per generation: that, and a fresh seed per drop, is what makes the same sentence come out
 * different every time (*"never the same result twice"*). The bounds are deliberately small. A hand
 * varies less than people think, and the units are the user's own ink, which already varies the way
 * the user does; this only stops two copies of one unit being identical.
 *
 * @property unitScale per unit, as a fraction of its size.
 * @property unitSlantRad per unit, added to the slant.
 * @property unitBaseline per unit, up or down, in x-heights.
 * @property unitRotationRad per unit.
 * @property lineDrift the most a line's baseline wanders, in x-heights, end to end.
 * @property wobble the most a stroke is displaced along its length, in x-heights.
 * @property gapJitter how far a measured gap is stretched or shrunk, as a fraction of it.
 */
public data class Variation(
    val unitScale: Float = 0.04f,
    val unitSlantRad: Float = DEGREE * 2f,
    val unitBaseline: Float = 0.04f,
    val unitRotationRad: Float = DEGREE,
    val lineDrift: Float = 0.08f,
    val wobble: Float = 0.015f,
    val gapJitter: Float = 0.15f,
) {
    public fun scaled(factor: Float): Variation = Variation(
        unitScale = unitScale * factor,
        unitSlantRad = unitSlantRad * factor,
        unitBaseline = unitBaseline * factor,
        unitRotationRad = unitRotationRad * factor,
        lineDrift = lineDrift * factor,
        wobble = wobble * factor,
        gapJitter = gapJitter * factor,
    )

    public companion object {
        private const val DEGREE = (PI / 180.0).toFloat()

        /** §6.1's starting bounds, unscaled. */
        public val Table: Variation = Variation()

        /** No variation at all — the retry ladder's last rung before refusing (§8.2). */
        public val None: Variation = Table.scaled(0f)

        /** The default *neatness*, which writes with the table as it stands. */
        public const val DEFAULT_NEATNESS: Float = 0.5f

        /**
         * The slider: `0` is loose (half as much again as the table), `0.5` is the table, `1` is neat
         * (half the table). Neat never means none — identical copies are what the request forbids.
         */
        public fun forNeatness(neatness: Float): Variation = Table.scaled(LOOSEST - neatness.coerceIn(0f, 1f))

        private const val LOOSEST = 1.5f
    }
}

/** A uniform draw in `[-bound, bound]`. */
internal fun Random.within(bound: Float): Float = if (bound <= 0f) 0f else (nextFloat() * 2f - 1f) * bound

/** One of [values], uniformly, stretched by up to [jitter] either way. */
internal fun Random.pick(values: List<Float>, jitter: Float): Float {
    val base = values[nextInt(values.size)]
    return base * (1f + within(jitter))
}
