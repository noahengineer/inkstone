package dev.inkstone.core.handwriting.neural

import dev.inkstone.core.handwriting.ByteCursor
import dev.inkstone.core.handwriting.ByteSink
import kotlin.random.Random

/**
 * ⭐ P3 — docs/HANDWRITING-SYNTHESIS.md §8.1: rank-[rank] adapters on the **top** LSTM layer's input and
 * recurrent matrices and on the output layer. Each is a pair `B·A` added to the frozen base matrix:
 *
 * ```
 * W_ih(top) + ihB·ihA      ihA: rank × inputs(top)    ihB: gates × rank
 * W_hh(top) + hhB·hhA      hhA: rank × cells          hhB: gates × rank
 * W_out     + outB·outA    outA: rank × layers·cells   outB: outputs × rank
 * ```
 *
 * Row-major throughout. [fresh] starts every `B` at zero, so an untrained set is exactly the base model
 * — the rung can only move the network away from where it started by being trained to.
 *
 * Only the top layer and the output change, so the layers below see exactly what they saw before and
 * their outputs can be computed once per training word ([TrainableNetwork]). The window is read off the
 * first layer and is untouched.
 */
public class Adapters(
    public val rank: Int,
    internal val ihA: FloatArray,
    internal val ihB: FloatArray,
    internal val hhA: FloatArray,
    internal val hhB: FloatArray,
    internal val outA: FloatArray,
    internal val outB: FloatArray,
) {
    internal val tensors: List<FloatArray> get() = listOf(ihA, ihB, hhA, hhB, outA, outB)

    public fun copy(): Adapters = Adapters(
        rank,
        ihA.copyOf(),
        ihB.copyOf(),
        hhA.copyOf(),
        hhB.copyOf(),
        outA.copyOf(),
        outB.copyOf(),
    )

    /** A set of the same shape, every value zero — gradients accumulate into one. */
    internal fun zeros(): Adapters = Adapters(
        rank,
        FloatArray(ihA.size),
        FloatArray(ihB.size),
        FloatArray(hhA.size),
        FloatArray(hhB.size),
        FloatArray(outA.size),
        FloatArray(outB.size),
    )

    internal fun fits(shape: NetworkShape): Boolean {
        val top = topInputs(shape)
        return ihA.size == rank * top && ihB.size == shape.gates * rank &&
            hhA.size == rank * shape.cells && hhB.size == shape.gates * rank &&
            outA.size == rank * shape.layers * shape.cells && outB.size == shape.outputs * rank
    }

    public companion object {
        private val MAGIC = "IHWA".encodeToByteArray()
        private const val VERSION = 1
        private const val INIT_SPREAD = 0.01f

        internal fun topInputs(shape: NetworkShape): Int = PEN + shape.cells + shape.alphabetSize + shape.styleDim

        /** Untrained: every `A` small and random, every `B` zero — the base model exactly. */
        public fun fresh(shape: NetworkShape, rank: Int, random: Random): Adapters {
            fun small(n: Int) = FloatArray(n) { (random.nextFloat() * 2f - 1f) * INIT_SPREAD }
            return Adapters(
                rank,
                small(rank * topInputs(shape)),
                FloatArray(shape.gates * rank),
                small(rank * shape.cells),
                FloatArray(shape.gates * rank),
                small(rank * shape.layers * shape.cells),
                FloatArray(shape.outputs * rank),
            )
        }

        public fun encode(adapters: Adapters): ByteArray {
            val sink = ByteSink()
            sink.bytes(MAGIC)
            sink.byte(VERSION)
            sink.varint(adapters.rank.toLong())
            for (t in adapters.tensors) {
                sink.varint(t.size.toLong())
                t.forEach(sink::float)
            }
            return sink.toByteArray()
        }

        /** Null for anything this build did not write: wrong magic, newer version, or cut short. */
        @Suppress("ReturnCount")
        public fun decode(bytes: ByteArray): Adapters? {
            val c = ByteCursor(bytes)
            if (c.take(MAGIC.size)?.contentEquals(MAGIC) != true) return null
            val version = c.byte() ?: return null
            if (version > VERSION) return null
            val rank = c.varint()?.toInt() ?: return null
            val tensors = ArrayList<FloatArray>()
            repeat(TENSORS) {
                val n = c.varint()?.toInt()?.takeIf { it >= 0 && it * FLOAT_BYTES <= c.remaining } ?: return null
                tensors += FloatArray(n) { c.float() ?: return null }
            }
            return of(rank, tensors)
        }

        /** [tensors] in [Adapters.tensors]' order: ihA, ihB, hhA, hhB, outA, outB. */
        public fun of(rank: Int, tensors: List<FloatArray>): Adapters {
            require(tensors.size == TENSORS) { "${tensors.size} tensors, adapters have $TENSORS" }
            val t = tensors.iterator()
            return Adapters(rank, t.next(), t.next(), t.next(), t.next(), t.next(), t.next())
        }

        /**
         * Every `B` scaled by [alpha]: `base + α·B·A`, the adapters' effect blended back towards the base
         * model. §8.1's shrinkage (`Personaliser`) — the strongest blend that keeps the user's words legible.
         */
        public fun scaled(adapters: Adapters, alpha: Float): Adapters = adapters.copy().also { a ->
            for (b in listOf(a.ihB, a.hhB, a.outB)) for (i in b.indices) b[i] *= alpha
        }

        /**
         * `base + B·A` for a `rows × cols` [base] (row-major), with `B` `rows × rank` and `A`
         * `rank × cols` — how an adapter is folded into the weights it adapts, once, at construction.
         */
        internal fun merged(
            base: FloatArray,
            rows: Int,
            cols: Int,
            b: FloatArray,
            a: FloatArray,
            rank: Int,
        ): FloatArray {
            val out = base.copyOf()
            for (r in 0 until rows) {
                for (k in 0 until rank) {
                    val bv = b[r * rank + k]
                    if (bv == 0f) continue
                    val row = r * cols
                    val ar = k * cols
                    for (c in 0 until cols) out[row + c] += bv * a[ar + c]
                }
            }
            return out
        }

        private const val PEN = 3
        private const val TENSORS = 6
        private const val FLOAT_BYTES = 4
    }
}
